"""A campaign's durable admission reservations, in fleet-state.json at its root, within its live settings' limits.

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
import os
from pathlib import Path
import time

from campaign_settings import BOOT_WINDOW_MIN


# Every On-Demand launch leaves this much of its region's On-Demand quota free.
ON_DEMAND_VCPU_SAFETY_RESERVE = 32
STATE_FILE = "fleet-state.json"
# How soon after its launch a host must report.
BOOT_REPORT_SECONDS = 5 * 60


class BudgetError(RuntimeError):
    pass


class FleetHalted(BudgetError):
    """An owner's halt stands, so the fleet admits nothing. A controller that observes it stops and cleans up as a
    cancelled one does, without recording a halt of its own."""


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
        size = min(self.settings.current["boot_window"]["max"], max(BOOT_WINDOW_MIN, size))
        self.settings.change("controller", f"boot window {self.size()} to {size}",
                             lambda settings: settings["boot_window"].update(size=size))
        self.wave.update(changed_at=now, healthy=0)


def refuse_halted(state):
    """Refuse to go on while any owner's halt stands."""
    if state.get("halts"):
        raise FleetHalted("shared fleet is halted: " + "; ".join(state["halts"].values()))


class FleetBudget:
    """The campaign's reservations, admitted within the limits of its settings as they stand, with the Spot vCPU
    reserve the campaign froze left in each region's quota."""

    def __init__(self, campaign_root, settings, spot_vcpu_reserve):
        self.settings = settings
        self.spot_vcpu_reserve = spot_vcpu_reserve
        self.state_path = Path(campaign_root) / STATE_FILE
        self.lock_path = Path(campaign_root) / "fleet-budget.lock"

    @contextmanager
    def locked(self):
        with self.lock_path.open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            if self.state_path.exists():
                state = json.loads(self.state_path.read_text())
            else:
                state = {"schema_version": 2, "reservations": {}, "completed": {}, "machine_hours": 0, "cost_usd": 0}
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
        now = time.time() if now is None else now
        key = reservation_key(campaign_id, job.host_epoch)
        settings = self.settings.current
        region = job.region
        if region not in settings["regions"]:
            raise BudgetError("the campaign settings do not cover region " + region)
        with self.locked() as state:
            refuse_halted(state)
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
                return False, "fleet quota"
            hourly_rate = rate["total"] if job.market == "spot" else rate["on_demand_total"]
            item = {"campaign_id": campaign_id, "host_epoch": job.host_epoch, "owner": str(Path(owner).resolve()),
                    "attempt": getattr(job, "attempt", 1),
                    "logical_identity": job.logical_identity, "region": region, "market": job.market,
                    "instance_type": job.platform.instance_type,
                    "vcpus": job.platform.vcpus, "started_at": now, "reserved_until": until, "hourly_rate": hourly_rate}
            if not affordable(settings, state, {**active, key: item}, now):
                return False, "fleet spending or machine-hour ceiling"
            available = capacity(snapshot_reader(), active, region, settings["regions"][region],
                                 self.spot_vcpu_reserve, market=job.market)
            if available["available"] < job.platform.vcpus:
                return False, "fleet quota"
            active[key] = item
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

    def stop_spending(self, reason):
        """Stop the fleet's spending, as the controller's settings change: no launch or extension is admitted until
        the stop is cleared, while every host keeps the reservation it holds, so running leases finish. The first
        reason stands. Unlike a halt, it stops no controller."""
        stop = {"by": "controller", "at": round(time.time()), "reason": reason}
        self.settings.change("controller", f"spending stop: {reason}",
                             lambda settings: settings.update(spending_stop=settings["spending_stop"] or stop))

    def check(self):
        """Raise FleetHalted while the fleet is halted. A host past its reservation's end is no halt: it stops at its
        stop time, and its reservation counts as spent until its owner releases it."""
        with self.locked() as state:
            refuse_halted(state)


def spend_by_owner(state_path, now):
    """Each owner's spend in the fleet whose state is at state_path, in USD, as (spent so far, reserved beyond now):
    its completed attempts' actual hours and its reservations' hours since they started, and their hours left to
    their reserved ends. Reads the state without the budget's lock, as each change replaces the whole file; None
    before the fleet's first reservation."""
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
