"""Durable admission reservations shared by controllers on one coordinator.

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


class BudgetError(RuntimeError):
    pass


def validate_policy(policy):
    if policy.get("schema_version") != 1 or policy.get("market") != "spot":
        raise BudgetError("shared fleet requires a schema-1 Spot policy")
    for name, pattern in (("account", r"[0-9]{12}"), ("region", r"[a-z]+(?:-[a-z]+)+-[0-9]+")):
        value = policy.get(name)
        if not isinstance(value, str) or not re.fullmatch(pattern, value):
            raise BudgetError("invalid fleet " + name)
    for name in ("max_vcpus", "max_hosts", "max_pending_launches", "max_attempt_seconds"):
        value = policy.get(name)
        if type(value) is not int or value <= 0:
            raise BudgetError("invalid fleet budget: " + name)
    reserve = policy.get("spot_vcpu_reserve")
    if type(reserve) is not int or reserve < 0:
        raise BudgetError("invalid fleet Spot vCPU reserve")
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
    for instance_type, rates in policy["hourly_rates"].items():
        if instance_type not in {family + size for family in ("r8i", "r8g", "r9g") for size in (".large", ".2xlarge")}:
            raise BudgetError("unapproved instance type in fleet budget")
        if (set(rates) != {"spot", "total"} or
                any(type(value) not in (int, float) or not math.isfinite(value) for value in rates.values()) or
                not 0 < rates["spot"] < rates["total"]):
            raise BudgetError("invalid hourly price ceiling")


def reservation_key(campaign_id, host_epoch):
    return f"{campaign_id}/{host_epoch}"


def capacity(snapshot, reservations, policy, *, remember=False):
    """Count instance/request unions and wrappers not yet visible in EC2."""
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
    available = max(0, min(snapshot["quota"] - policy["spot_vcpu_reserve"], policy["max_vcpus"]) - external_vcpus - reserved)
    return {"available": available, "reserved": reserved, "external": external_vcpus,
            "pending_launches": pending}


class FleetBudget:
    def __init__(self, policy_path):
        self.policy_path = Path(policy_path).resolve()
        self.policy = json.loads(self.policy_path.read_text())
        validate_policy(self.policy)
        self.state_path = self.policy_path.with_name("fleet-state.json")
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

    def reserve(self, campaign_id, job, owner, snapshot_reader, now=None):
        if job.market != "spot":
            raise BudgetError("shared fleet forbids On-Demand")
        now = time.time() if now is None else now
        key = reservation_key(campaign_id, job.host_epoch)
        with self.locked() as state:
            if state.get("halted"):
                raise BudgetError("shared fleet is halted: " + state["halted"])
            if key in state["reservations"] or key in state["completed"]:
                raise BudgetError("host epoch already used by the shared fleet")
            active = state["reservations"]
            policy = self.policy
            if len(active) >= policy["max_hosts"] or now - state["last_launch_at"] < policy["minimum_launch_interval_seconds"]:
                return False, "fleet launch limit"
            rate = policy["hourly_rates"].get(job.platform.instance_type)
            if rate is None:
                raise BudgetError("instance type has no frozen price ceiling")
            # Outstanding attempts reserve their whole remaining allowance. If
            # cleanup is delayed, charge the longer elapsed lifetime instead.
            hours = policy["max_attempt_seconds"] / 3600
            active_hours = [max(hours, (now - item["started_at"]) / 3600) for item in active.values()]
            committed_hours = state["machine_hours"] + sum(active_hours) + hours
            committed_cost = state["cost_usd"] + policy["ancillary_reserve_usd"] + hours * rate["total"] + sum(
                duration * item["hourly_rate"] for duration, item in zip(active_hours, active.values()))
            if committed_hours > policy["max_machine_hours"] or committed_cost > policy["max_usd"]:
                return False, "fleet spending or machine-hour ceiling"
            available = capacity(snapshot_reader(), active, policy, remember=True)
            if available["available"] < job.platform.vcpus or available["pending_launches"] >= policy["max_pending_launches"]:
                return False, "fleet quota or pending-launch limit"
            active[key] = {"campaign_id": campaign_id, "host_epoch": job.host_epoch, "owner": str(Path(owner).resolve()),
                           "attempt": getattr(job, "attempt", 1),
                           "logical_identity": job.logical_identity, "instance_type": job.platform.instance_type,
                           "vcpus": job.platform.vcpus, "started_at": now, "hourly_rate": rate["total"]}
            state["last_launch_at"] = now
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

    def halt(self, reason):
        with self.locked() as state:
            if not state.get("halted"):
                state["halted"] = reason

    def check(self, now=None):
        now = time.time() if now is None else now
        with self.locked() as state:
            if state.get("halted"):
                raise BudgetError("shared fleet is halted: " + state["halted"])
            if any(now - item["started_at"] > self.policy["max_attempt_seconds"] for item in state["reservations"].values()):
                raise BudgetError("shared fleet attempt exceeded its lifetime allowance")
