"""A campaign's durable admission reservations, in fleet-state.json at its root, within its live settings' limits.

A reservation holds a host's hours and cost until the stop time its controller
reserved for it. That time covers only the work the host was issued: the
controller extends the reservation before issuing more, and the host stops by
then on its own.
Never expire a reservation just because its controller disappeared. The caller
must prove resource cleanup before releasing it, including during recovery.
"""

from contextlib import contextmanager
import json
import os
from pathlib import Path, PurePosixPath
import time
from types import SimpleNamespace

from campaign_io import fsync_directory, locked as file_locked
from campaign_settings import BOOT_WINDOW_MIN
from control_host import CAMPAIGN_ROOT


# Every On-Demand launch leaves this much of its region's On-Demand quota free.
ON_DEMAND_VCPU_SAFETY_RESERVE = 32
# The refusal of a launch its market's quota in the region cannot hold, by market.
QUOTA_REFUSALS = {"spot": "fleet Spot quota", "on-demand": "fleet On-Demand quota"}
STATE_FILE = "fleet-state.json"
# How soon after its launch a host must report.
BOOT_REPORT_SECONDS = 5 * 60


class BudgetError(RuntimeError):
    pass


class AccountMismatch(BudgetError):
    """AWS lists a resource the fleet cannot account for, so no region's quota can be trusted for a launch."""


def on_demand_regions(settings):
    """Name the regions whose On-Demand allowance lets the fleet launch On-Demand there."""
    return [region for region, limits in settings["regions"].items() if "on_demand_max_vcpus" in limits]


def reservation_key(campaign_id, host_epoch):
    return f"{campaign_id}/{host_epoch}"


def affordable(settings, state, reservations, now):
    """Whether completed usage plus active reservations fits both of the fleet's ceilings, machine hours and
    spending: each completed attempt at its actual hours, and each reservation's hours to its reserved end, or to
    now once it ran past it."""
    hours = [(max(item["reserved_until"], now) - item["started_at"]) / 3600 for item in reservations.values()]
    cost = state["cost_usd"] + sum(duration * item["hourly_rate"] for duration, item in zip(hours, reservations.values()))
    return state["machine_hours"] + sum(hours) <= settings["max_machine_hours"] and cost <= settings["max_usd"]


def capacity(snapshot, reservations, region, limits, spot_vcpu_reserve, *, market="spot"):
    """Count one region's and market's instance/request unions, given the region's limits, and the vCPUs left for
    the fleet's next host there."""
    reservations = {key: item for key, item in reservations.items()
                    if item["region"] == region and item["market"] == market}
    instances = {instance["id"]: instance for instance in snapshot["instances"]}
    if len(instances) != len(snapshot["instances"]):
        raise BudgetError("duplicate EC2 instances in capacity snapshot")
    observed = set()
    external_vcpus = 0
    for instance in instances.values():
        key = reservation_key(instance.get("campaign_id"), instance.get("host_epoch"))
        if key in reservations:
            if key in observed or instance["vcpus"] != reservations[key]["vcpus"]:
                raise BudgetError("EC2 instance disagrees with its reserved topology")
            observed.add(key)
        else:
            external_vcpus += instance["vcpus"]
    for request in snapshot["requests"]:
        if request.get("instance_id") in instances:
            continue
        # A fulfilled request can appear before describe-instances sees its
        # instance. Unidentified requests are counted conservatively as extra.
        key = reservation_key(request.get("campaign_id"), request.get("host_epoch"))
        if key in reservations:
            if key in observed:
                raise BudgetError("multiple Spot resources use one fleet reservation")
            observed.add(key)
            if request["vcpus"] != reservations[key]["vcpus"]:
                raise BudgetError("Spot request disagrees with reserved topology")
        else:
            external_vcpus += request["vcpus"]
    # A terminated worker can still be returning results or awaiting verified cleanup, so it keeps its full
    # reservation.
    reserved = sum(item["vcpus"] for item in reservations.values())
    if market == "spot":
        # max_vcpus bounds all Standard Spot use in the region, the fleet's and others'.
        ceiling = min(snapshot["quota"] - spot_vcpu_reserve, limits["max_vcpus"]) - external_vcpus
    else:
        # The allowance bounds only the fleet's own On-Demand use; other workloads take quota headroom.
        ceiling = min(snapshot["quota"] - ON_DEMAND_VCPU_SAFETY_RESERVE - external_vcpus,
                      limits.get("on_demand_max_vcpus", 0))
    available = max(0, ceiling - reserved)
    return {"available": available, "reserved": reserved, "external": external_vcpus}


class BootWindow:
    """How many of the fleet's hosts may be launched but not yet reporting, which bounds what a bad image, region or
    user data costs before it shows.

    The window's size is a setting, which starts at its initial size. Its wave is the hosts admitted since the size
    last changed: once as many of them as the window holds have reported within BOOT_REPORT_SECONDS of their launch,
    it doubles, up to the settings' maximum; once any is late, or ends before reporting for a reason other than a
    Spot reclaim, it halves, down to BOOT_WINDOW_MIN. The controller changes the size as its own settings change. The
    wave lives in the fleet state, and the window counts the reservations whose hosts have not reported."""

    def __init__(self, state, settings, now):
        self.reservations = state["reservations"]
        self.settings = settings
        self.wave = state.setdefault("boot_wave", {"changed_at": 0, "healthy": 0})
        if any(self.member(item) and now - item["started_at"] > BOOT_REPORT_SECONDS for item in self.booting()):
            self.change(self.size() // 2, now)

    def size(self):
        window = self.settings.current["boot_window"]
        return min(window["size"], window["max"])

    def booting(self):
        return [item for item in self.reservations.values() if "reported_at" not in item]

    def member(self, item):
        return "reported_at" not in item and item["started_at"] >= self.wave["changed_at"]

    def room(self):
        return max(0, self.size() - len(self.booting()))

    def report(self, item, now):
        if self.member(item):
            self.wave["healthy"] += 1
            if self.wave["healthy"] >= self.size():
                self.change(self.size() * 2, now)
        item["reported_at"] = now

    def fail(self, item, now):
        if self.member(item):
            self.change(self.size() // 2, now)

    def change(self, size, now):
        # A change the settings refuse leaves the wave as it is, so the next look tries it again.
        size = min(self.settings.current["boot_window"]["max"], max(BOOT_WINDOW_MIN, size))
        if self.settings.controller_change(f"boot window {self.size()} to {size}",
                                           lambda settings: settings["boot_window"].update(size=size)):
            self.wave.update(changed_at=now, healthy=0)


class FleetBudget:
    """The campaign's reservations, admitted within the limits of its settings as they stand, with the Spot vCPU
    reserve the campaign froze left in each region's quota."""

    def __init__(self, campaign_root, settings, spot_vcpu_reserve):
        self.settings = settings
        self.spot_vcpu_reserve = spot_vcpu_reserve
        self.campaign_root = Path(campaign_root).resolve()
        self.state_path = self.campaign_root / STATE_FILE
        self.lock_path = self.campaign_root / "fleet-budget.lock"

    @contextmanager
    def locked(self):
        """The fleet's state under its lock, written back durably once it changed."""
        with file_locked(self.lock_path):
            if self.state_path.exists():
                text = self.state_path.read_text()
                state = json.loads(text)
            else:
                text = None
                state = {"schema_version": 2, "reservations": {}, "completed": {}, "machine_hours": 0, "cost_usd": 0}
            yield state
            written = json.dumps(state, sort_keys=True) + "\n"
            if written == text:
                return
            temporary = self.state_path.with_suffix(".new")
            with temporary.open("w") as output:
                output.write(written)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.state_path)
            fsync_directory(self.state_path.parent)

    def reserve(self, campaign_id, job, owner, snapshot_reader, now=None, *, until):
        """Admit a launch whose host stops by until, in epoch seconds, unless a later extension covers more."""
        now = time.time() if now is None else now
        key = reservation_key(campaign_id, job.host_epoch)
        settings = self.settings.current
        region = job.region
        if region not in settings["regions"]:
            raise BudgetError("the campaign settings do not cover region " + region)
        with self.locked() as state:
            if key in state["reservations"] or key in state["completed"]:
                raise BudgetError("host epoch already used by the shared fleet")
            if settings["spending_stop"]:
                return False, "spending_stop"
            if not BootWindow(state, self.settings, now).room():
                return False, "boot window"
            active = state["reservations"]
            if len(active) >= settings["max_hosts"]:
                return False, "fleet launch limit"
            rate = settings["hourly_rates"].get(job.platform.instance_type)
            if rate is None:
                raise BudgetError(f"the campaign settings price no {job.platform.instance_type} hour")
            if job.market != "spot" and region not in on_demand_regions(settings):
                return False, QUOTA_REFUSALS["on-demand"]
            hourly_rate = rate["total"] if job.market == "spot" else rate["on_demand_total"]
            item = {"campaign_id": campaign_id, "host_epoch": job.host_epoch, "owner": self.owner(owner),
                    "attempt": getattr(job, "attempt", 1),
                    "logical_identity": job.logical_identity, "region": region, "market": job.market,
                    "instance_type": job.platform.instance_type,
                    "vcpus": job.platform.vcpus, "started_at": now, "reserved_until": until, "hourly_rate": hourly_rate}
            if not affordable(settings, state, {**active, key: item}, now):
                return False, "fleet spending or machine-hour ceiling"
            available = capacity(snapshot_reader(), active, region, settings["regions"][region],
                                 self.spot_vcpu_reserve, market=job.market)
            if available["available"] < job.platform.vcpus:
                return False, QUOTA_REFUSALS[job.market]
            active[key] = item
            return True, "reserved"

    def extend(self, campaign_id, host_epoch, now=None, *, until):
        """Extend a host's reservation to until, in epoch seconds, if the fleet's ceilings admit it."""
        now = time.time() if now is None else now
        key = reservation_key(campaign_id, host_epoch)
        with self.locked() as state:
            item = state["reservations"].get(key)
            if item is None:
                raise BudgetError("host epoch holds no shared fleet reservation")
            if until <= item["reserved_until"]:
                return True, "reserved"
            if self.settings.current["spending_stop"]:
                return False, "spending_stop"
            extended = {**state["reservations"], key: {**item, "reserved_until": until}}
            if not affordable(self.settings.current, state, extended, now):
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

    def report_boots(self, campaign_id, host_epochs, now=None):
        """Record that the campaign's hosts of host_epochs reported; returns how many more hosts the boot window
        admits."""
        now = time.time() if now is None else now
        with self.locked() as state:
            window = BootWindow(state, self.settings, now)
            for host_epoch in host_epochs:
                item = state["reservations"].get(reservation_key(campaign_id, host_epoch))
                if item is not None and "reported_at" not in item:
                    window.report(item, now)
            return window.room()

    def boot_failed(self, campaign_id, host_epoch, now=None):
        """Record that the campaign's host ended before it reported, for a reason other than a Spot reclaim."""
        now = time.time() if now is None else now
        with self.locked() as state:
            item = state["reservations"].get(reservation_key(campaign_id, host_epoch))
            if item is not None:
                BootWindow(state, self.settings, now).fail(item, now)

    def owner(self, root):
        """The owner a phase's root names in the fleet's state: its key, its root relative to the campaign root, so
        the state reads the same wherever the root is copied."""
        return Path(root).resolve().relative_to(self.campaign_root).as_posix()

    def reservations(self, owner):
        owner = self.owner(owner)
        with self.locked() as state:
            return [item.copy() for item in state["reservations"].values() if item["owner"] == owner]

    def stop_spending(self, reason):
        """Stop the fleet's spending, as the controller's settings change: no launch or extension is admitted until
        the stop is cleared, while every host keeps the reservation it holds, so running leases finish. The first
        reason stands. Returns whether the stop was recorded."""
        stop = {"by": "controller", "at": round(time.time()), "reason": reason}
        return self.settings.controller_change(f"spending stop: {reason}", lambda settings: settings.update(
            spending_stop=settings["spending_stop"] or stop))


def read_state(state_path):
    """The fleet's state at state_path, read without the budget's lock, as each change replaces the whole file; None
    before the fleet's first reservation."""
    return json.loads(state_path.read_text()) if state_path.is_file() else None


def earlier_owner(owner):
    """Whether an owner in the fleet's state is an earlier controller's. Controllers before phase keys named each
    phase by its absolute root on the control host. r20's root names its phases so, and status still reads it, but
    no controller resumes such a root."""
    return PurePosixPath(owner).is_absolute()


def owner_key(owner):
    """The phase key an owner in the fleet's state names. An earlier controller's owner reads as its key under the
    host's campaign root."""
    path = PurePosixPath(owner)
    if earlier_owner(owner) and path.is_relative_to(CAMPAIGN_ROOT):
        return path.relative_to(CAMPAIGN_ROOT).as_posix()
    return owner


def owner_fleet(state, owner, now):
    """What one owner, a phase's key, holds of the fleet whose state is state: its spend in USD so far, its completed
    hosts' actual hours and its reservations' hours since they started; what its reservations hold beyond now; its
    reservations, one per live host; and the seconds from each of its hosts' launch to its first report."""
    completed = [item for item in state["completed"].values() if owner_key(item["owner"]) == owner]
    live = [item for item in state["reservations"].values() if owner_key(item["owner"]) == owner]
    return SimpleNamespace(
        spent=sum(item["machine_hours"] * item["hourly_rate"] for item in completed) +
        sum(max(0, now - item["started_at"]) / 3600 * item["hourly_rate"] for item in live),
        reserved=sum(max(0, item["reserved_until"] - now) / 3600 * item["hourly_rate"] for item in live),
        hosts=live, ready=[item["reported_at"] - item["started_at"] for item in completed + live
                           if "reported_at" in item])
