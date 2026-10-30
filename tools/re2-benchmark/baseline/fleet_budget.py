"""Durable admission reservations shared by controllers on one coordinator.

A reservation holds a host's hours and cost until the stop time its controller
reserved for it. That time covers only the work the host was issued: the
controller extends the reservation before issuing more, and the host stops by
then on its own.
Never expire a reservation just because its controller disappeared. The caller
must prove resource cleanup before releasing it, including during recovery.
"""

from contextlib import contextmanager
import fcntl
import json
import math
import os
from pathlib import Path
import re
import time


REGION_PATTERN = r"[a-z]+(?:-[a-z]+)+-[0-9]+"
# Every On-Demand launch leaves this much of its region's On-Demand quota free.
ON_DEMAND_VCPU_SAFETY_RESERVE = 32
STATE_FILE = "fleet-state.json"


class BudgetError(RuntimeError):
    pass


class FleetHalted(BudgetError):
    """An owner's halt stands, so the fleet admits nothing. A controller that observes it stops and cleans up as a
    cancelled one does, without recording a halt of its own."""


def validate_region_limits(limits):
    if (not isinstance(limits, dict) or {"max_vcpus", "spot_vcpu_reserve"} - set(limits) or
            set(limits) - {"max_vcpus", "spot_vcpu_reserve", "on_demand_max_vcpus"}):
        raise BudgetError("fleet region limits require max_vcpus and spot_vcpu_reserve and allow on_demand_max_vcpus")
    if type(limits["max_vcpus"]) is not int or limits["max_vcpus"] <= 0:
        raise BudgetError("invalid fleet budget: max_vcpus")
    if type(limits["spot_vcpu_reserve"]) is not int or limits["spot_vcpu_reserve"] < 0:
        raise BudgetError("invalid fleet Spot vCPU reserve")
    if "on_demand_max_vcpus" in limits and (
            type(limits["on_demand_max_vcpus"]) is not int or limits["on_demand_max_vcpus"] <= 0):
        raise BudgetError("invalid fleet On-Demand vCPU allowance")


def validate_policy(policy):
    if policy.get("schema_version") != 1 or policy.get("market") != "spot":
        raise BudgetError("shared fleet requires a schema-1 Spot policy")
    for name, pattern in (("account", r"[0-9]{12}"), ("region", REGION_PATTERN)):
        value = policy.get(name)
        if not isinstance(value, str) or not re.fullmatch(pattern, value):
            raise BudgetError("invalid fleet " + name)
    for name in ("max_hosts", "max_pending_launches"):
        value = policy.get(name)
        if type(value) is not int or value <= 0:
            raise BudgetError("invalid fleet budget: " + name)
    validate_region_limits(home_limits(policy))
    # The top-level limits cover the home region; each other region a campaign may
    # launch in names its own, since quotas are regional.
    regions = policy.get("regions", {})
    if not isinstance(regions, dict) or ("regions" in policy and not regions):
        raise BudgetError("invalid fleet regions")
    for region, limits in regions.items():
        if not isinstance(region, str) or not re.fullmatch(REGION_PATTERN, region) or region == policy["region"]:
            raise BudgetError("invalid fleet region")
        validate_region_limits(limits)
    for name in ("max_machine_hours", "max_usd", "ancillary_reserve_usd", "minimum_launch_interval_seconds"):
        value = policy.get(name)
        if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
            raise BudgetError("invalid fleet budget: " + name)
        if name in ("max_machine_hours", "max_usd") and value == 0:
            raise BudgetError("invalid fleet budget: " + name)
    if policy["ancillary_reserve_usd"] >= policy["max_usd"]:
        raise BudgetError("ancillary reserve exhausts the fleet budget")
    if not isinstance(policy.get("hourly_rates"), dict) or not policy["hourly_rates"]:
        raise BudgetError("fleet budget requires per-type hourly ceilings")
    # A policy allowing On-Demand anywhere prices every worker type's On-Demand hour.
    on_demand = bool(on_demand_regions(policy))
    price_fields = {"spot", "total", "on_demand_total"} if on_demand else {"spot", "total"}
    for instance_type, rates in policy["hourly_rates"].items():
        if instance_type not in {family + size for family in ("r8i", "r9g") for size in (".large", ".2xlarge")}:
            raise BudgetError("unapproved instance type in fleet budget")
        if (set(rates) != price_fields or
                any(type(value) not in (int, float) or not math.isfinite(value) for value in rates.values()) or
                not 0 < rates["spot"] < rates["total"] or (on_demand and rates["on_demand_total"] <= 0)):
            raise BudgetError("invalid hourly price ceiling")


def home_limits(policy):
    limits = {name: policy.get(name) for name in ("max_vcpus", "spot_vcpu_reserve")}
    if "on_demand_max_vcpus" in policy:
        limits["on_demand_max_vcpus"] = policy["on_demand_max_vcpus"]
    return limits


def region_limits(policy):
    return {policy["region"]: home_limits(policy), **policy.get("regions", {})}


def on_demand_regions(policy):
    """Name the regions whose On-Demand allowance lets the fleet launch On-Demand there."""
    return [region for region, limits in region_limits(policy).items() if "on_demand_max_vcpus" in limits]


def reservation_key(campaign_id, host_epoch):
    return f"{campaign_id}/{host_epoch}"


def affordable(policy, state, reservations, now):
    """Whether completed usage plus active reservations fits both of the fleet's ceilings, machine hours and
    spending: each completed attempt at its actual hours, and each reservation's hours to its reserved end, or to
    now once it ran past it."""
    hours = [(max(item["reserved_until"], now) - item["started_at"]) / 3600 for item in reservations.values()]
    cost = state["cost_usd"] + policy["ancillary_reserve_usd"] + sum(
        duration * item["hourly_rate"] for duration, item in zip(hours, reservations.values()))
    return state["machine_hours"] + sum(hours) <= policy["max_machine_hours"] and cost <= policy["max_usd"]


def capacity(snapshot, reservations, policy, *, region=None, market="spot", remember=False):
    """Count one region's and market's instance/request unions and reserved hosts not yet visible in EC2."""
    region = region or policy["region"]
    limits = region_limits(policy)[region]
    # Reservations made before regions and markets were recorded all launched on Spot in the home region.
    reservations = {key: item for key, item in reservations.items()
                    if item.get("region", policy["region"]) == region and item.get("market", "spot") == market}
    instances = {instance["id"]: instance for instance in snapshot["instances"]}
    if len(instances) != len(snapshot["instances"]):
        raise BudgetError("duplicate EC2 instances in capacity snapshot")
    observed = set()
    launched = set()
    external_vcpus = 0
    for instance in instances.values():
        key = reservation_key(instance.get("campaign_id"), instance.get("host_epoch"))
        if key in reservations:
            if key in observed or instance["vcpus"] != reservations[key]["vcpus"]:
                raise BudgetError("EC2 instance disagrees with its reserved topology")
            observed.add(key)
            launched.add(key)
        else:
            external_vcpus += instance["vcpus"]
    resource_claims = set(observed)
    for request in snapshot["requests"]:
        if request.get("instance_id") in instances:
            continue
        # A fulfilled request can appear before describe-instances sees its
        # instance. Unidentified requests are counted conservatively as extra.
        key = reservation_key(request.get("campaign_id"), request.get("host_epoch"))
        if key in reservations:
            if key in resource_claims:
                raise BudgetError("multiple Spot resources use one fleet reservation")
            resource_claims.add(key)
            if request["vcpus"] != reservations[key]["vcpus"]:
                raise BudgetError("Spot request disagrees with reserved topology")
            if request.get("instance_id") and key not in observed:
                observed.add(key)
                launched.add(key)
        else:
            external_vcpus += request["vcpus"]
    # A terminated worker can still be returning results or awaiting verified
    # cleanup. Retain its full reservation without calling it a new launch.
    # Persist proof only under the caller's ledger lock, after topology checks.
    if remember:
        for key in launched:
            reservations[key]["launch_observed"] = True
    pending = sum(key not in observed and not item.get("launch_observed", False)
                  for key, item in reservations.items())
    reserved = sum(item["vcpus"] for item in reservations.values())
    if market == "spot":
        # max_vcpus bounds all Standard Spot use in the region, the fleet's and others'.
        ceiling = min(snapshot["quota"] - limits["spot_vcpu_reserve"], limits["max_vcpus"]) - external_vcpus
    else:
        # The allowance bounds only the fleet's own On-Demand use; other workloads take quota headroom.
        ceiling = min(snapshot["quota"] - ON_DEMAND_VCPU_SAFETY_RESERVE - external_vcpus,
                      limits.get("on_demand_max_vcpus", 0))
    available = max(0, ceiling - reserved)
    return {"available": available, "reserved": reserved, "external": external_vcpus,
            "pending_launches": pending}


def refuse_halted(state):
    """Refuse to go on while any owner's halt stands."""
    if state.get("halts"):
        raise FleetHalted("shared fleet is halted: " + "; ".join(state["halts"].values()))


class FleetBudget:
    def __init__(self, policy_path):
        self.policy_path = Path(policy_path).resolve()
        self.policy = json.loads(self.policy_path.read_text())
        validate_policy(self.policy)
        self.state_path = self.policy_path.with_name(STATE_FILE)
        self.lock_path = self.policy_path.with_name("fleet-budget.lock")

    @contextmanager
    def locked(self):
        with self.lock_path.open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            if json.loads(self.policy_path.read_text()) != self.policy:
                raise BudgetError("fleet policy changed during execution")
            if self.state_path.exists():
                state = json.loads(self.state_path.read_text())
                if state["policy"] != self.policy:
                    raise BudgetError("fleet policy changed on restart")
            else:
                state = {"schema_version": 1, "policy": self.policy, "reservations": {},
                         "completed": {}, "machine_hours": 0, "cost_usd": 0, "last_launch_at": 0}
            yield state
            temporary = self.state_path.with_suffix(".new")
            with temporary.open("w") as output:
                json.dump(state, output, sort_keys=True)
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.state_path)
            descriptor = os.open(self.state_path.parent, os.O_RDONLY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)

    def reserve(self, campaign_id, job, owner, snapshot_reader, now=None, *, until):
        """Admit a launch whose host stops by until, in epoch seconds, unless a later extension covers more."""
        if job.market != "spot" and not on_demand_regions(self.policy):
            raise BudgetError("shared fleet forbids On-Demand")
        now = time.time() if now is None else now
        key = reservation_key(campaign_id, job.host_epoch)
        region = getattr(job, "region", "") or self.policy["region"]
        if region not in region_limits(self.policy):
            raise BudgetError("shared fleet policy does not cover region " + region)
        with self.locked() as state:
            refuse_halted(state)
            if key in state["reservations"] or key in state["completed"]:
                raise BudgetError("host epoch already used by the shared fleet")
            active = state["reservations"]
            policy = self.policy
            if len(active) >= policy["max_hosts"] or now - state["last_launch_at"] < policy["minimum_launch_interval_seconds"]:
                return False, "fleet launch limit"
            rate = policy["hourly_rates"].get(job.platform.instance_type)
            if rate is None:
                raise BudgetError("instance type has no frozen price ceiling")
            hourly_rate = rate["total"] if job.market == "spot" else rate["on_demand_total"]
            item = {"campaign_id": campaign_id, "host_epoch": job.host_epoch, "owner": str(Path(owner).resolve()),
                    "attempt": getattr(job, "attempt", 1),
                    "logical_identity": job.logical_identity, "region": region, "market": job.market,
                    "instance_type": job.platform.instance_type,
                    "vcpus": job.platform.vcpus, "started_at": now, "reserved_until": until, "hourly_rate": hourly_rate}
            if not affordable(policy, state, {**active, key: item}, now):
                return False, "fleet spending or machine-hour ceiling"
            if job.market != "spot" and region not in on_demand_regions(policy):
                return False, "fleet quota or pending-launch limit"
            available = capacity(snapshot_reader(), active, policy, region=region, market=job.market, remember=True)
            if available["available"] < job.platform.vcpus or available["pending_launches"] >= policy["max_pending_launches"]:
                return False, "fleet quota or pending-launch limit"
            active[key] = item
            state["last_launch_at"] = now
            return True, "reserved"

    def extend(self, campaign_id, host_epoch, now=None, *, until):
        """Extend a host's reservation to until, in epoch seconds, if the fleet's ceilings admit it."""
        now = time.time() if now is None else now
        key = reservation_key(campaign_id, host_epoch)
        with self.locked() as state:
            refuse_halted(state)
            item = state["reservations"].get(key)
            if item is None:
                raise BudgetError("host epoch holds no shared fleet reservation")
            if until <= item["reserved_until"]:
                return True, "reserved"
            if not affordable(self.policy, state, {**state["reservations"], key: {**item, "reserved_until": until}},
                              now):
                return False, "fleet spending or machine-hour ceiling"
            item["reserved_until"] = until
            return True, "reserved"

    def release(self, campaign_id, host_epoch, *, cleanup_verified, now=None):
        if not cleanup_verified:
            raise BudgetError("cannot release fleet capacity before verified cleanup")
        now = time.time() if now is None else now
        key = reservation_key(campaign_id, host_epoch)
        with self.locked() as state:
            if key in state["completed"]:
                return
            item = state["reservations"].pop(key)
            hours = max(0, now - item["started_at"]) / 3600
            state["machine_hours"] += hours
            state["cost_usd"] += hours * item["hourly_rate"]
            state["completed"][key] = {**item, "finished_at": now, "machine_hours": hours}

    def reservations(self, owner):
        with self.locked() as state:
            return [item.copy() for item in state["reservations"].values() if item["owner"] == str(Path(owner).resolve())]

    def halt(self, reason, owner):
        """Halt every controller's admissions for the owner's failure; each owner's first reason stands."""
        with self.locked() as state:
            state.setdefault("halts", {}).setdefault(str(Path(owner).resolve()), reason)

    def resume(self, owner):
        """Clear the owner's halt, once verified cleanup has released every reservation it holds. The fleet stays
        halted while another owner's halt stands."""
        owner = str(Path(owner).resolve())
        with self.locked() as state:
            if owner in state.get("halts", {}) and not any(
                    item["owner"] == owner for item in state["reservations"].values()):
                del state["halts"][owner]

    def check(self):
        """Raise FleetHalted while the fleet is halted. A host past its reservation's end is no halt: it stops at its
        stop time, and its reservation counts as spent until its owner releases it."""
        with self.locked() as state:
            refuse_halted(state)


def spend_by_owner(policy_path, now):
    """Each owner's spend in the fleet of policy_path, in USD, as (spent so far, reserved beyond now): its completed
    attempts' actual hours and its reservations' hours since they started, and their hours left to their reserved
    ends. Reads the state without the budget's lock, as each change replaces the whole file; None before the
    fleet's first reservation."""
    state_path = Path(policy_path).with_name(STATE_FILE)
    if not state_path.is_file():
        return None
    state = json.loads(state_path.read_text())
    spend = {}
    for item in state["completed"].values():
        spent, reserved = spend.get(item["owner"], (0.0, 0.0))
        spend[item["owner"]] = (spent + item["machine_hours"] * item["hourly_rate"], reserved)
    for item in state["reservations"].values():
        spent, reserved = spend.get(item["owner"], (0.0, 0.0))
        spend[item["owner"]] = (spent + max(0, now - item["started_at"]) / 3600 * item["hourly_rate"],
                                reserved + max(0, item["reserved_until"] - now) / 3600 * item["hourly_rate"])
    return spend
