"""A campaign's live settings: what may change while it runs, in campaign-settings.json at the campaign root.

The controller reloads the settings every loop. Every change is made under campaign-settings.lock, held only while
the file is rewritten: it bumps the version and appends an event naming its actor to campaign-settings-events.tsv,
so no change is anonymous. A change is staged beside the file and takes effect only once its event is recorded, and
the next change finishes or discards one a failure interrupted, so no version takes effect without its event. What
decides a measurement's validity is frozen elsewhere and never lives here.
"""

import contextlib
import copy
import csv
import fcntl
import json
import os
import re
import time

from campaign_io import append_ledger, fsync_directory, ledger_lines

SETTINGS = "campaign-settings.json"
LOCK = "campaign-settings.lock"
EVENTS = "campaign-settings-events.tsv"
EVENT_FIELDS = ("version", "at", "actor", "change")
# Dain and the monitor steer a campaign; the controller changes only phase states, the boot window, and the spending
# stop.
ACTORS = ("dain", "monitor", "controller")
REGION_PATTERN = r"[a-z]+(?:-[a-z]+)+-[0-9]+"
PURCHASE_POLICIES = ("spot", "spot-then-on-demand", "on-demand")
PHASE_STATES = ("pending", "running", "paused", "draining", "complete", "failed")
INSTANCE_TYPES = frozenset(family + size for family in ("r8i", "r9g") for size in (".large", ".2xlarge"))
# The boot window's first and largest number of hosts launched but not yet reporting, unless the settings name
# others, and the smallest it ever halves to.
BOOT_WINDOW_FIRST = 10
BOOT_WINDOW_MAX = 50
BOOT_WINDOW_MIN = 5
FIELDS = ("version", "max_hosts", "max_hosts_per_platform", "max_usd", "max_machine_hours", "regions",
          "platform_regions", "purchase", "hourly_rates", "phases", "boot_window", "authorized", "spending_stop",
          "stop")


class SettingsError(RuntimeError):
    pass


# OpenTelemetry exports an integer in 64 bits, and a number setting may be one, so every number stays below 2**63.
def positive_integer(value):
    return type(value) is int and 0 < value < 2 ** 63


def positive_number(value):
    return type(value) in (int, float) and 0 < value < 2 ** 63


def validate(settings):
    """Refuse settings the controller could not run under."""
    def check(condition, field):
        if not condition:
            raise SettingsError(f"invalid campaign settings: {field}")

    check(isinstance(settings, dict) and set(settings) == set(FIELDS), f"the fields are {', '.join(FIELDS)}")
    for name in ("version", "max_hosts", "max_hosts_per_platform"):
        check(positive_integer(settings[name]), name)
    for name in ("max_usd", "max_machine_hours"):
        check(positive_number(settings[name]), name)
    regions = settings["regions"]
    check(isinstance(regions, dict) and regions, "regions")
    for region, limits in regions.items():
        check(isinstance(region, str) and re.fullmatch(REGION_PATTERN, region), "region name")
        check(isinstance(limits, dict) and {"bucket", "max_vcpus"} <= set(limits) <=
              {"bucket", "max_vcpus", "on_demand_max_vcpus"}, f"{region} takes bucket, max_vcpus and "
                                                              "on_demand_max_vcpus")
        check(isinstance(limits["bucket"], str) and limits["bucket"], f"{region} bucket")
        check(positive_integer(limits["max_vcpus"]), f"{region} max_vcpus")
        check(positive_integer(limits.get("on_demand_max_vcpus", 1)), f"{region} on_demand_max_vcpus")
    check(isinstance(settings["platform_regions"], dict), "platform_regions")
    for platform, listed in settings["platform_regions"].items():
        check(isinstance(listed, list) and listed and all(isinstance(region, str) for region in listed) and
              len(listed) == len(set(listed)) and all(region in regions for region in listed),
              f"{platform} platform_regions")
    check(isinstance(settings["purchase"], dict) and all(policy in PURCHASE_POLICIES
                                                         for policy in settings["purchase"].values()),
          f"purchase is one of {', '.join(PURCHASE_POLICIES)} per platform")
    # Settings allowing On-Demand anywhere price every worker type's On-Demand hour.
    on_demand = any("on_demand_max_vcpus" in limits for limits in regions.values())
    prices = {"spot", "total", "on_demand_total"} if on_demand else {"spot", "total"}
    rates = settings["hourly_rates"]
    check(isinstance(rates, dict) and rates and set(rates) <= INSTANCE_TYPES, "hourly_rates")
    for instance_type, rate in rates.items():
        check(isinstance(rate, dict) and set(rate) == prices and all(map(positive_number, rate.values())) and
              rate["spot"] < rate["total"], f"{instance_type} hourly price ceiling")
    check(isinstance(settings["phases"], dict), "phases")
    for phase, steering in settings["phases"].items():
        # A paused or draining phase records who paused or drained it, and a paused one why, and a phase since when
        # it has had its state, as the change that set it recorded.
        state = steering.get("state") if isinstance(steering, dict) else None
        check(isinstance(steering, dict) and set(steering) - {"since"} == {"state", "share"} | (
                  {"by", "reason"} if state == "paused" else {"by"} if state == "draining" else set()) and
              state in PHASE_STATES and (steering["share"] == "auto" or positive_integer(steering["share"])) and
              steering.get("by", "dain") in ACTORS and (state != "paused" or isinstance(steering["reason"], str) and
                                                         steering["reason"]) and
              positive_number(steering.get("since", 1)), f"{phase} phase")
    window = settings["boot_window"]
    check(isinstance(window, dict) and set(window) == {"initial", "max", "size"} and
          all(map(positive_integer, window.values())) and
          BOOT_WINDOW_MIN <= window["initial"] <= window["max"] and window["size"] >= BOOT_WINDOW_MIN, "boot_window")
    authorized = settings["authorized"]
    check(isinstance(authorized, dict) and set(authorized) == {"max_hosts", "max_usd", "regions"} and
          positive_integer(authorized["max_hosts"]) and positive_number(authorized["max_usd"]) and
          isinstance(authorized["regions"], list) and
          all(isinstance(region, str) and re.fullmatch(REGION_PATTERN, region) for region in authorized["regions"]),
          "authorized")
    for name in ("spending_stop", "stop"):
        value = settings[name]
        check(value is None or isinstance(value, dict) and set(value) == {"by", "at", "reason"} and
              value["by"] in ACTORS and positive_number(value["at"]) and isinstance(value["reason"], str) and
              value["reason"], name)


def check_actor(actor, before, after):
    """Refuse a change its actor may not make. Only Dain changes the authorized envelope and the hourly rates, which
    set the Spot price ceilings and what every hour is charged. The monitor raises no
    limit past it: hosts in all and per platform up to its hosts, spending up to its dollars, and a region's vCPUs, or
    a new region, only among its regions, where admission still holds the fleet within the region's quota after its
    reserve. The envelope names no machine hours, so the monitor never raises their limit. Any actor may lower a
    limit. The monitor changes a phase's state only to resume one the controller paused for a fault; a pause or a
    drain Dain made is his."""
    for name, description in (("authorized", "the authorized envelope"), ("hourly_rates", "the hourly rates")):
        if after[name] != before[name] and actor != "dain":
            raise SettingsError(f"only dain changes {description}")
    if actor != "monitor":
        return
    authorized = after["authorized"]
    for name, ceiling in (("max_hosts", authorized["max_hosts"]), ("max_hosts_per_platform", authorized["max_hosts"]),
                          ("max_usd", authorized["max_usd"]), ("max_machine_hours", before["max_machine_hours"])):
        if after[name] > before[name] and after[name] > ceiling:
            raise SettingsError(f"the monitor raises {name} only within the authorized envelope, to {ceiling:g}")
    for region, limits in after["regions"].items():
        earlier = before["regions"].get(region, {})
        if region not in authorized["regions"] and any(
                limits.get(name, 0) > earlier.get(name, 0) for name in ("max_vcpus", "on_demand_max_vcpus")):
            raise SettingsError(f"the monitor adds or raises only the authorized regions, "
                                f"{', '.join(authorized['regions'])}, not {region}")
    for phase, steering in after["phases"].items():
        earlier = before["phases"].get(phase, {})
        if steering["state"] != earlier.get("state") and (earlier.get("state"), earlier.get("by")) != (
                "paused", "controller"):
            raise SettingsError(f"the monitor resumes only a phase the controller paused for a fault; {phase} is "
                                f"{earlier.get('state')} by {earlier.get('by')}")


def changed(before, actor, update, at):
    """The settings update makes of before, as the actor's change at epoch time at, once they validate and the actor
    may make it; each phase whose state it changes has it since then."""
    if actor not in ACTORS:
        raise SettingsError(f"a settings change names its actor, one of {', '.join(ACTORS)}")
    settings = copy.deepcopy(before)
    update(settings)
    for phase, steering in settings["phases"].items():
        if steering.get("state") != before["phases"].get(phase, {}).get("state"):
            steering["since"] = at
    if settings != before:
        settings["version"] = before["version"] + 1
        validate(settings)
        check_actor(actor, before, settings)
    return settings


def read(path):
    try:
        settings = json.loads(path.read_text())
    except (OSError, ValueError) as error:
        raise SettingsError(f"cannot read the campaign settings {path}: {error}") from error
    validate(settings)
    return settings


def staged(path):
    return path.with_name(path.name + ".new")


def record(path, events_path, settings, actor, change, at):
    """Make settings the campaign's, as the actor's change at epoch time at: staged beside the file first, then its
    event recorded, and only then put in place, so no version takes effect without its event. The staged file and
    its name are durable before the event is, so a crash never leaves an event whose change recovery cannot find."""
    with staged(path).open("w") as output:
        json.dump(settings, output, indent=2, sort_keys=True)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    fsync_directory(path.parent)
    append_ledger(events_path, EVENT_FIELDS, {"version": settings["version"], "at": at, "actor": actor,
                                              "change": change})
    os.replace(staged(path), path)
    fsync_directory(path.parent)


def recover(path, events_path):
    """Finish the change a failure interrupted, under the lock: staged settings whose event was recorded take effect,
    and any others are discarded, since their change was never recorded."""
    if not staged(path).exists():
        return
    try:
        version = json.loads(staged(path).read_text())["version"]
        recorded = [row["version"] for row in csv.DictReader(ledger_lines(events_path), delimiter="\t")]
    except (OSError, ValueError, KeyError, TypeError):
        version, recorded = None, []
    if recorded and recorded[-1] == str(version):
        os.replace(staged(path), path)
        fsync_directory(path.parent)
    else:
        staged(path).unlink()


@contextlib.contextmanager
def locked(lock_path):
    with lock_path.open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def seeded(given):
    """A campaign's first settings, version 1, from those given: no phase steered yet, no stop, the boot window at
    its first size, and unless given, the default boot window and an authorized envelope of the given limits."""
    if not isinstance(given, dict):
        raise SettingsError("campaign settings are a JSON object")
    settings = {"phases": {}, "spending_stop": None, "stop": None,
                "boot_window": {"initial": BOOT_WINDOW_FIRST, "max": BOOT_WINDOW_MAX},
                "authorized": {"max_hosts": given.get("max_hosts"), "max_usd": given.get("max_usd"),
                               "regions": sorted(given.get("regions") or ())},
                **copy.deepcopy(given), "version": 1}
    if isinstance(settings["boot_window"], dict):
        settings["boot_window"].setdefault("size", settings["boot_window"].get("initial"))
    validate(settings)
    return settings


class CampaignSettings:
    """A campaign's live settings, as the controller last loaded them, and their changes."""

    def __init__(self, root):
        self.path = root / SETTINGS
        self.lock_path = root / LOCK
        self.events_path = root / EVENTS
        self.current = read(self.path)

    @classmethod
    def seed(cls, root, given, actor="dain"):
        """Write the campaign's first settings, unless it has some, and load them."""
        path = root / SETTINGS
        with locked(root / LOCK):
            recover(path, root / EVENTS)
            if not path.exists():
                record(path, root / EVENTS, seeded(given), actor, "seed", round(time.time()))
        return cls(root)

    def reload(self):
        """Read the settings as they stand now."""
        self.current = read(self.path)
        return self.current

    def change(self, actor, change, update):
        """Apply update to the settings as they stand, as the actor's change, described by change: once the result
        validates, and check_actor finds the actor may make it, it takes the next version and its event is recorded,
        the time its event records being when each phase it steers took its state. An update that changes nothing
        records nothing. Returns the settings."""
        with locked(self.lock_path):
            recover(self.path, self.events_path)
            before = read(self.path)
            at = round(time.time())
            settings = changed(before, actor, update, at)
            if settings != before:
                record(self.path, self.events_path, settings, actor, change, at)
        self.current = settings
        return settings

    def controller_change(self, change, update):
        """Make the controller's own change, as change does; returns whether it was recorded. While the settings
        cannot be read, or the change does not validate, the controller logs why and goes on under the last settings
        that validated, and its caller tries again later."""
        try:
            self.change("controller", change, update)
        except (OSError, SettingsError) as error:
            print(f"cannot record {change} in the campaign settings: {error}", flush=True)
            return False
        return True
