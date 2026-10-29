#!/usr/bin/env python3

import argparse
import base64
import contextlib
import csv
import fcntl
import hashlib
import io
import json
import math
import os
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
from dataclasses import dataclass, field, replace
from pathlib import Path

import engine_build
import unit_session
import work_units
from acceptance import normalize_heap_size
from fleet_budget import (
    ON_DEMAND_VCPU_SAFETY_RESERVE, REGION_PATTERN, BudgetError, FleetBudget, FleetHalted, on_demand_regions,
    region_limits)
from campaign_io import (
    UnsafeCampaignError, aws_output, check_output, fsync_directory, write_ledger)
from unit_dispatch import (
    UNIT_COMPLETION_FIELDS, UNIT_UPLOADS, completion_key, extract_unit_result, get_unit_worker_result,
    group_identity, heartbeat_key, heartbeat_sequence, interruption_key, list_unit_worker_results,
    persist_accepted_unit, persist_semantic_rejection, persist_unit_upload, put_unit_lease, read_accepted_units,
    read_semantic_rejections, read_unit_attempts, read_unit_receipt, recovered_keys, result_key, retain_unit_attempt,
    shows_worker_start, unit_attempt_directory, unit_logical_identity, unit_worker_instance_state, write_unit_attempt,
    write_unresolved_units)
from unit_session import PROTOCOL_REJECTION_STATUS, SEMANTIC_FAILURE_STATUS

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "language"))
import fleet as language_fleet
import released_artifact

PROACTIVE_SPOT_CAPACITY_THRESHOLD = 32
DEFAULT_MAX_CONCURRENT = 64
STANDARD_ON_DEMAND_QUOTA_CODE = "L-1216C47A"
STANDARD_SPOT_QUOTA_CODE = "L-34B43A08"
STANDARD_INSTANCE_FAMILIES = frozenset("acdhimrtz")
SPOT_VCPU_SAFETY_RESERVE = 32
CAPACITY_RETRY_SECONDS = 30
MAX_REPLACEMENTS_PER_JOB = 2
# Once this share of a pool's estimated work is all that remains unaccepted, the pool's new
# launches use On-Demand so the phase's tail does not wait on Spot capacity or a reclaim,
# unless the pool's platform buys only Spot.
ON_DEMAND_TAIL_PERCENT = 10
WRAPPER_NOT_CREATED = "wrapper process was not created"
UNIT_WORKER_SHARD = "unit-worker"
UNIT_WORKER_LIFETIME_SECONDS = 6 * 60 * 60
# The wrapper waits for a unit worker's host evidence through its lifetime, this allowance for its host
# setup and final uploads, and its last unit: the pool's longest unit, and at least the unit budget.
UNIT_WORKER_SETUP_AND_UPLOAD_SECONDS = 75 * 60
UNIT_WORKER_LAST_UNIT_SECONDS = work_units.UNIT_SECONDS
# A worker whose heartbeat is this stale has lost its lease once EC2 no longer runs its instance,
# and after the longer bound whatever EC2 reports.
UNIT_HEARTBEAT_STALE_SECONDS = 10 * 60
UNIT_HEARTBEAT_LOST_SECONDS = 30 * 60
UNIT_POLL_SECONDS = 15
# A unit pool's new Spot workers skip a region this long after it found no Spot capacity.
SPOT_REGION_COOL_DOWN_SECONDS = 15 * 60
# How a platform's workers buy capacity: Spot only, waiting for it however long; Spot, turning to On-Demand once
# Spot has fallen short for SPOT_SHORTAGE_WAIT_SECONDS; or On-Demand only.
PURCHASE_POLICIES = ("spot", "spot-then-on-demand", "on-demand")
DEFAULT_PURCHASE = "spot-then-on-demand"
# A spot-then-on-demand pool keeps trying Spot this long after its first launch that found no Spot capacity, and
# then launches On-Demand while every one of its platform's configured regions cools, until one of its Spot
# workers starts. One cool-down lets every region be tried again, so a brief shortage buys no On-Demand hours,
# while a platform whose Spot ran dry for the day, as r8i's did in every US region on October 28, 2026, loses
# only this long.
SPOT_SHORTAGE_WAIT_SECONDS = 15 * 60
# A new unit worker is expected to start its first lease this long after launch. Recent workers took
# 2.4 to 2.9 minutes from boot to their first unit. A pool launches a worker only for work its workers
# would start later than this.
UNIT_WORKER_BOOT_SECONDS = 5 * 60
# A unit worker's host powers off at its stop time, which only a lease extends, and the controller covers each stop
# time and the host's teardown in the fleet budget before it issues the lease. So a host whose controller died stops
# within its reservation, and a reservation holds only the work its host was issued: the leases it holds, each run
# for its timeout, after its boot allowance before its first lease, then its idle stop and wind-down.
# The boot allowance runs from admission to the latest start of the worker's first lease.
UNIT_WORKER_BOOT_ALLOWANCE_SECONDS = 20 * 60
# The worker stops once its next lease has been absent this long.
UNIT_WORKER_IDLE_SECONDS = 10 * 60
# From the worker's idle stop until its host powers off: up to 8 minutes of failing lease reads, the final
# heartbeat, the host evidence, and its upload.
UNIT_WORKER_WIND_DOWN_SECONDS = 15 * 60
# From the host's stop time until its cleanup is verified and its reservation released.
UNIT_WORKER_TEARDOWN_SECONDS = 15 * 60
# A lease's timeout is this multiple of its groups' estimate, or of the unit budget when that is longer.
UNIT_LEASE_TIMEOUT_FACTOR = 2
# A work item stays unresolved once this many of its leases failed, were interrupted, or were lost;
# a lease released before it started does not count.
MAX_UNIT_ATTEMPTS = MAX_REPLACEMENTS_PER_JOB + 1
SEMANTIC_FAILURE_DETAIL = f"semantic failure: status {SEMANTIC_FAILURE_STATUS}"
# A controller holds this lock in the root it owns, a phase's result root or the campaign root.
CONTROLLER_LOCK = "controller.lock"
# A controller that finds another owning its root exits with this status, so a campaign driver that started it
# adopts the owner rather than failing the phase.
CONTROLLER_BUSY_STATUS = 75
# A phase's controller records its process ID and start time here, in its result root, once it holds the root's
# controller lock.
DRIVER_CHILD = "driver-child.txt"
# A detached phase controller, or any step's child a campaign driver started, writes its output here, in its root.
DRIVER_CHILD_LOG = "driver-child.log"
# A campaign driver records its process ID and start time here, in the campaign root, once it holds the root's
# controller lock, and a detached one writes its output beside it.
CAMPAIGN_DRIVER = "campaign-driver.txt"
CAMPAIGN_DRIVER_LOG = "campaign-driver.log"
DRIVER_POLL_SECONDS = 30
# A unit campaign phase's digest of the tooling that started it, in its result root.
TOOLING_DIGEST = "tooling-digest.txt"
# Written once a unit phase's controller has verified every worker's cleanup and its phase gate has passed;
# a later phase consumes the result root only once it exists.
PHASE_COMPLETE = "phase-complete.txt"
WORK_ITEM_FIELDS = ("platform", "instance_type", "shard_id", "unit_id", "replica", "estimated_seconds", "groups",
                    "excluded_instances")
# Lease outcomes that spend one of the item's attempts. An interruption after the lease's first accepted group,
# interrupted-progress, spends none: the item kept that progress.
COUNTED_UNIT_OUTCOMES = frozenset(("failed", "interrupted", "lost"))
# Lease outcomes after which the lease's host can no longer change what it became.
SETTLED_UNIT_OUTCOMES = frozenset(
    ("stop", "accepted", "superseded", "failed", "rejected", "interrupted", "interrupted-progress"))
# platforms.tsv pins each AMI by its ID in this region; other regions run the image of the same name.
PINNED_AMI_REGION = "us-west-2"
AMAZON_AMI_OWNER = "137112412989"
# Spot placement scores are rate-limited per configuration, so reuse each answer briefly.
PLACEMENT_SCORE_CACHE_SECONDS = 5 * 60
# A shared archive upload slower than this has stalled; it is retried rather than left to
# hold every launch behind it.
SHARED_ARCHIVE_UPLOAD_MINIMUM_RATE = 256 * 1024
SHARED_ARCHIVE_UPLOAD_ATTEMPTS = 3
CANDIDATE_PROVENANCE_FIELDS = (
    "candidate_ref",
    "candidate_commit",
    "root_tree",
    "engine_tree",
    "archive_sha256",
    "archive_file_list_sha256",
    "row_manifest_sha256",
    "platform_manifest_sha256",
    "comparator_manifest_sha256",
    "shard_manifest_sha256",
    "protocol_representatives_sha256",
)
# The checked-in manifests whose digests a candidate's provenance records, by provenance field.
PROVENANCE_MANIFESTS = {
    "row_manifest_sha256": "rows.tsv",
    "platform_manifest_sha256": "platforms.tsv",
    "comparator_manifest_sha256": "comparators.tsv",
    "shard_manifest_sha256": "shards.tsv",
    "protocol_representatives_sha256": "protocol-representatives.tsv",
}
# A campaign's candidates.tsv: each candidate's name, commit, benchmark ref, and archive SHA-256.
CANDIDATE_FIELDS = ("name", "commit", "ref", "archive_sha256")
# A frozen candidate's archive and provenance, in its candidate directory.
CANDIDATE_ARCHIVE = "regulator.tar.gz"
CANDIDATE_PROVENANCE = "provenance.tsv"
CANDIDATE_NAME_PATTERN = r"[A-Za-z0-9][A-Za-z0-9._-]*"
# A phase's campaign ID tags its AWS resources and names its S3 prefixes.
CAMPAIGN_ID_PATTERN = r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}"
# The driver's options that decide what its candidates' phases measure, recorded beside candidates.tsv.
CAMPAIGN_OPTIONS = "campaign-options.json"
BENCHMARK_TOOLING = "tools/re2-benchmark"
BASELINE_TOOLING = "tools/re2-benchmark/baseline"
JOB_MANIFEST_FIELDS = (
    "platform", "shard_id", "replica", "host_epoch", "attempt", "market", "instance_type", "architecture")
PHASE_TIMEOUT_SECONDS = {
    "build": 3 * 60 * 60,
    "smoke": 6 * 60 * 60,
    "primary": 10 * 60 * 60,
    "confirmation": 2 * 60 * 60,
}
PHASE_HOST_EPOCH_START = {
    "smoke": 1,
    "primary": 100_001,
    "confirmation": 200_001,
    "build": 300_001,
}


@dataclass(frozen=True)
class Platform:
    name: str
    architecture: str
    instance_type: str
    vcpus: int
    ami_id: str
    jdk_url: str
    jdk_sha256: str
    native_compiler_package: str
    cmake_package: str
    glibc_package: str
    cargo_package: str
    rust_package: str
    time_package: str
    zstd_package: str
    concurrency_instance_type: str = ""
    concurrency_vcpus: int = 0


@dataclass(frozen=True)
class WorkItem:
    """One replica of a work unit on one platform, measured by any unit worker of its pool: the platform's
    workers of the unit's instance type, the frozen type for a baseline unit or engine build and the plan's
    allocation for a language unit."""
    platform: str
    instance_type: str
    shard: str
    unit_id: str
    replica: int
    estimated_seconds: float
    # The unit's measured groups in this replica's run order, each as (group ID, estimated seconds).
    groups: tuple
    # Instances a confirmation must avoid: those that measured the unit's primary replicas.
    excluded_instances: frozenset = frozenset()

    @property
    def identity(self):
        return f"{self.platform}/{self.unit_id}/replica-{self.replica}"

    @property
    def pool(self):
        return self.platform, self.instance_type


@dataclass(frozen=True)
class Job:
    platform: Platform
    shard: str
    replica: int
    host_epoch: int
    attempt: int
    market: str
    workload: str
    # Chosen at launch; empty until the controller places the attempt.
    region: str = ""
    placement_score: str = ""
    # Regions where the pool's Spot launches found no capacity within the cool-down.
    spot_capacity_failures: tuple = ()
    # Chosen at admission, in epoch seconds: the end of the worker's boot allowance, and its host's first stop time.
    boot_by: int = 0
    stop_by: int = 0

    @property
    def identity(self):
        return f"{self.platform.name}/{self.shard}/replica-{self.replica}/epoch-{self.host_epoch}"

    @property
    def logical_identity(self):
        return f"{self.platform.name}/{self.shard}/replica-{self.replica}"


def campaign_engine_tree(arguments):
    return arguments.engine_tree


def parse_args():
    # No abbreviations: a detached controller drops only the exact --detach from the command it runs again.
    parser = argparse.ArgumentParser(allow_abbrev=False)
    parser.add_argument("phase", choices=("campaign", *PHASE_TIMEOUT_SECONDS),
                        help="one phase of a candidate, or campaign to run every candidate's phases")
    parser.add_argument("campaign",
                        help="the campaign; each candidate's phases run as <campaign>.<name>, and its language "
                             "phases as <campaign>.<name>.lang")
    parser.add_argument("--candidate", action="append", default=[], dest="candidate_values",
                        metavar="NAME=COMMITISH",
                        help="a candidate commit, frozen from Git as refs/benchmarks/<campaign>/<name>; a phase takes "
                             "one and a campaign takes one or more")
    parser.add_argument("--campaign-root", type=Path,
                        help="the campaign's results, holding candidates.tsv and <name>/candidate and each phase "
                             "(default: benchmark-results/pre-review-baseline/<campaign>)")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--detach", action="store_true",
                        help="with --execute, start the controller in its own session, logging beside its results, "
                             "and return; run the same command again to resume one that stopped")
    parser.add_argument("--confirmation-jobs", type=Path)
    parser.add_argument("--smoke-results", type=Path,
                        help="accepted smoke a primary requires (default: the candidate's smoke)")
    parser.add_argument("--primary-results", type=Path,
                        help="accepted primary a confirmation requires (default: the candidate's primary)")
    parser.add_argument("--max-concurrent", type=int, default=DEFAULT_MAX_CONCURRENT)
    parser.add_argument("--max-concurrent-per-platform", type=int, default=DEFAULT_MAX_CONCURRENT)
    parser.add_argument("--spot-vcpu-reserve", type=int, default=SPOT_VCPU_SAFETY_RESERVE)
    parser.add_argument("--release-version", default="",
                        help="measure the pinned published JAR for this version instead of source-built classes")
    parser.add_argument("--smoke-protocol", choices=("smoke", "qualification"), default="smoke",
                        help="use qualification timing in baseline smoke to validate full shard durations")
    parser.add_argument("--phase-timeout-seconds", type=int,
                        help="frozen phase wall-clock limit; otherwise derive from topology plus capacity waits")
    parser.add_argument("--fleet-budget", type=Path,
                        help="shared fleet policy; all controllers must use the same coordinator directory")
    parser.add_argument("--shard", action="append", dest="selected_shards",
                        help="baseline shard to recollect; repeat to select a subset")
    parser.add_argument("--language-plan", action="append", type=Path, default=[],
                        help="language plan prepared by fleet.py, whose units a phase measures instead of the "
                             "baseline's and a campaign measures in language phases beside the baseline's; repeat "
                             "for lifecycle and bulk")
    parser.add_argument("--platform-regions", action="append", default=[], dest="platform_region_values",
                        metavar="PLATFORM=REGION[,REGION...]",
                        help="AWS regions the platform's hosts may launch in; with several, Spot placement scores "
                             "choose, ties going to the earlier region (default: AWS_REGION or us-west-2)")
    parser.add_argument("--purchase", action="append", default=[], dest="purchase_values",
                        metavar="PLATFORM=POLICY",
                        help="how the platform's hosts buy capacity: spot never launches On-Demand, on-demand "
                             f"never launches Spot, and {DEFAULT_PURCHASE}, the default, turns to On-Demand where "
                             "Spot falls short")
    parser.add_argument("--engine-builds", type=Path,
                        help="accepted build-phase result root whose per-platform prebuilt archives every "
                             "measuring unit worker installs, verified against their pinned SHA-256 (default: the "
                             "candidate's build)")
    parser.add_argument("--transfer-bucket", action="append", default=[], metavar="REGION=BUCKET",
                        help="shared transfer bucket for hosts launched in REGION; regions without one "
                             "use a per-session bucket")
    return parser.parse_args()


def parse_transfer_buckets(values):
    buckets = {}
    for value in values:
        region, separator, bucket = value.partition("=")
        if not separator or not region or not bucket or region in buckets:
            raise SystemExit(f"--transfer-bucket takes one REGION=BUCKET value per region: {value}")
        buckets[region] = bucket
    return buckets


def parse_platform_settings(values, option, platforms):
    """Each named platform's setting from repeated PLATFORM=SETTING values."""
    settings = {}
    for value in values:
        platform, separator, setting = value.partition("=")
        if not separator or not setting or platform in settings:
            raise SystemExit(f"{option} takes one PLATFORM=VALUE per platform: {value}")
        if platform not in platforms:
            raise SystemExit(f"{option} names a platform not in platforms.tsv: {value}")
        settings[platform] = setting
    return settings


def parse_launch_options(arguments, platforms):
    names = {platform.name for platform in platforms}
    arguments.platform_regions = {
        platform: regions.split(",") for platform, regions in parse_platform_settings(
            arguments.platform_region_values, "--platform-regions", names).items()}
    arguments.purchases = parse_platform_settings(arguments.purchase_values, "--purchase", names)
    invalid = sorted(set(arguments.purchases.values()) - set(PURCHASE_POLICIES))
    if invalid:
        raise SystemExit(f"--purchase policies are {', '.join(PURCHASE_POLICIES)}, not {', '.join(invalid)}")


def default_regions():
    return [os.environ.get("AWS_REGION", "us-west-2")]


def platform_regions(arguments, platform):
    """The regions the platform's hosts may launch in, in the order its ties go."""
    return getattr(arguments, "platform_regions", {}).get(platform) or default_regions()


def purchase_policy(arguments, platform):
    return getattr(arguments, "purchases", {}).get(platform, DEFAULT_PURCHASE)


def campaign_platforms(arguments):
    """The platforms whose workers the phase launches, or before its work is planned, those given regions."""
    return sorted({platform.name for platform in getattr(arguments, "unit_platforms", {}).values()} or
                  set(getattr(arguments, "platform_regions", {})))


def campaign_regions(arguments):
    """Every region a platform of the campaign may launch in."""
    return list(dict.fromkeys(region for platform in campaign_platforms(arguments)
                              for region in platform_regions(arguments, platform))) or default_regions()


def shared_transfer_buckets(arguments):
    """Return each allowed region's shared transfer bucket; a single-region campaign may name its own."""
    buckets = getattr(arguments, "transfer_buckets", None)
    if buckets:
        return buckets
    bucket = os.environ.get("TRANSFER_BUCKET")
    return {campaign_regions(arguments)[0]: bucket} if bucket else {}


def execution_policy(arguments):
    """What a restart must repeat. Launch regions and purchase are not part of it: each result records its host's
    region and market, so a restarted phase may move to other regions or buy differently."""
    return {"spot_vcpu_reserve": getattr(arguments, "spot_vcpu_reserve", SPOT_VCPU_SAFETY_RESERVE),
            "release_version": getattr(arguments, "release_version", ""),
            "smoke_protocol": getattr(arguments, "smoke_protocol", "smoke"),
            "max_concurrent": getattr(arguments, "max_concurrent", DEFAULT_MAX_CONCURRENT),
            "max_concurrent_per_platform": getattr(arguments, "max_concurrent_per_platform", DEFAULT_MAX_CONCURRENT),
            "phase_timeout_seconds": getattr(arguments, "topology_deadline_seconds", None),
            "fleet_budget": str(arguments.shared_budget.policy_path) if getattr(arguments, "shared_budget", None) else None,
            "fleet_policy": arguments.shared_budget.policy if getattr(arguments, "shared_budget", None) else None,
            # Each platform's engine-build manifest SHA-256, or "local" for phases that install no
            # prebuilt engines.
            "engine_builds": getattr(arguments, "engine_build_policy", "local"),
            # A language campaign's plan digests by shard.
            "language_plans": {shard: hashlib.sha256((directory / "plan.json").read_bytes()).hexdigest()
                               for shard, directory in sorted(getattr(arguments, "language_plans", {}).items())}}


def validate_execution_policy(arguments, result_root):
    expected = execution_policy(arguments)
    path = result_root / "execution-policy.json"
    if path.exists():
        recorded = json.loads(path.read_text())
        if recorded != expected:
            raise UnsafeCampaignError("campaign execution policy changed on restart")
        return
    temporary = path.with_suffix(".new")
    with temporary.open("w") as output:
        json.dump(expected, output, sort_keys=True)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
    fsync_directory(result_root)


def phase_timeout(arguments):
    if getattr(arguments, "topology_deadline_seconds", None) is not None:
        return arguments.topology_deadline_seconds
    return PHASE_TIMEOUT_SECONDS[arguments.phase]


def read_platforms(directory):
    with (directory / "platforms.tsv").open(newline="") as input_file:
        return [Platform(
            row["platform"],
            row["architecture"],
            row["instance_type"],
            int(row["vcpus"]),
            row["ami_id"],
            row["jdk_url"],
            row["jdk_sha256"],
            row["native_compiler_package"],
            row["cmake_package"],
            row["glibc_package"],
            row["cargo_package"],
            row["rust_package"],
            row["time_package"],
            row["zstd_package"],
            row.get("concurrency_instance_type", ""),
            int(row.get("concurrency_vcpus", 0)))
            for row in csv.DictReader(input_file, delimiter="\t")]


def read_platform_regions(directory):
    with (directory / "platform-regions.tsv").open(newline="") as input_file:
        return {(row["platform"], row["region"]): row["ami_id"]
                for row in csv.DictReader(input_file, delimiter="\t")}


def campaign_manifest(root, arguments):
    return root / "tools/re2-benchmark/baseline/rows.tsv"


def campaign_shards(root, arguments):
    return root / "tools/re2-benchmark/baseline/shards.tsv"


def read_shards(directory):
    with (directory / "shards.tsv").open(newline="") as input_file:
        return [row["shard_id"] for row in csv.DictReader(input_file, delimiter="\t")]


def read_confirmation_units(path, platforms, units, primary_receipts):
    """Replica 4 of each unstable unit, excluding every instance that measured a group of its replicas 1 to 3.

    Each platform and unit has one confirmation allowance, so a unit is listed at most once.
    """
    platform_names = {platform.name for platform in platforms}
    used = {}
    covered = {}
    for receipt in primary_receipts:
        used.setdefault((receipt["platform"], receipt["unit_id"]), set()).add(receipt["instance_id"])
        covered.setdefault((receipt["platform"], receipt["unit_id"]), set()).add(
            (receipt["replica_id"], receipt["group_id"]))
    selected = []
    with path.open(newline="") as input_file:
        for row in csv.DictReader(input_file, delimiter="\t"):
            key = (row["platform"], row["unit_id"])
            if key in {(platform, unit_id) for platform, unit_id, _, _ in selected}:
                raise SystemExit(f"duplicate confirmation unit: {key}")
            if row["platform"] not in platform_names:
                raise SystemExit(f"unknown confirmation platform: {row['platform']}")
            if units.get(row["unit_id"], {}).get("shard_id") != row["shard_id"]:
                raise SystemExit(f"unknown confirmation unit: {row['shard_id']}/{row['unit_id']}")
            groups = {(str(replica), group["group_id"]) for replica in (1, 2, 3)
                      for group in units[row["unit_id"]]["groups"]}
            if covered.get(key, set()) != groups:
                raise SystemExit(f"confirmation unit needs every group of its primary replicas accepted: {key}")
            selected.append((row["platform"], row["unit_id"], 4, frozenset(used[key])))
    return selected


def build_work_items(phase, platforms, units, shards, confirmation_units=(), allocations=None, mirror=False):
    """Each platform's replicas of the phase's units: replica 1 in build and smoke, 1 to 3 in primary,
    and the listed replica 4 confirmations. allocations names each shard's {platform: instance type};
    a baseline unit or engine build runs on its platform's frozen type. With mirror, as a baseline unit's
    order rule requires, an even replica runs its unit's groups in reverse."""
    if phase == "confirmation":
        selected = confirmation_units
    else:
        replicas = (1,) if phase in ("build", "smoke") else (1, 2, 3)
        ordered = [unit_id for unit_id, unit in sorted(units.items(), key=lambda entry: (
            entry[1]["shard_id"], int(entry[1]["unit_index"]))) if unit["shard_id"] in shards]
        selected = [(platform.name, unit_id, replica, frozenset())
                    for platform in platforms
                    for unit_id in ordered
                    for replica in replicas]
    allocations = allocations or {shard: {platform.name: platform.instance_type for platform in platforms}
                                  for shard in shards}

    def groups(unit_id, replica):
        ordered = tuple((group["group_id"], float(group["estimated_seconds"])) for group in units[unit_id]["groups"])
        return ordered[::-1] if mirror and replica % 2 == 0 else ordered

    return [WorkItem(platform, allocations[units[unit_id]["shard_id"]][platform], units[unit_id]["shard_id"], unit_id,
                     replica, float(units[unit_id]["estimated_seconds"]), groups(unit_id, replica), instances)
            for platform, unit_id, replica, instances in selected]


def language_plans(arguments):
    """Each language plan, validated, by its directory; arguments.language_plans names each directory by its
    shard. A language campaign has no confirmation phase and runs every phase at the qualification protocol."""
    if arguments.phase not in ("smoke", "primary"):
        raise SystemExit("a language campaign runs only smoke and primary phases")
    plans, arguments.language_plans = {}, {}
    for directory in arguments.language_plan:
        directory = directory.resolve()
        try:
            plan = language_fleet.validate(directory)
        except (OSError, ValueError, KeyError) as error:
            raise SystemExit(f"invalid language plan {directory}: {error}") from error
        if plan["shard_id"] in arguments.language_plans:
            raise SystemExit(f"language plans share shard {plan['shard_id']}")
        plans[directory] = plan
        arguments.language_plans[plan["shard_id"]] = directory
    arguments.smoke_protocol = "qualification"
    return plans


def write_language_bundles(arguments, result_root):
    """Write each language plan's bundle under the result root, or reuse the one an earlier start wrote for the
    same plan, keeping its path and SHA-256 by shard."""
    directory = result_root / "language-plans"
    directory.mkdir(parents=True, exist_ok=True)
    arguments.language_bundles = {shard: language_fleet.reuse_bundle(plan, directory)
                                  for shard, plan in sorted(arguments.language_plans.items())}


def aggregate_language_plans(arguments, result_root):
    """Export each language plan's accepted primary units as <shard>-results.json; a restart must reproduce it."""
    for shard, directory in sorted(arguments.language_plans.items()):
        output = result_root / f"{shard}-results.json"
        with tempfile.TemporaryDirectory(prefix="language-aggregate-", dir=result_root) as temporary:
            export = Path(temporary) / output.name
            try:
                language_fleet.aggregate(directory, result_root, export)
            except (OSError, ValueError, KeyError) as error:
                raise SystemExit(f"language plan {shard} cannot be exported: {error}") from error
            if output.exists():
                if output.read_bytes() != export.read_bytes():
                    raise UnsafeCampaignError(f"language export {output} changed on restart")
            else:
                os.replace(export, output)
    fsync_directory(result_root)


def unit_worker_job(platform, ordinal, host_epoch, market, spot_capacity_failures=()):
    """A unit worker host of a platform's pool; its replica is the worker's ordinal within the phase."""
    return Job(platform, UNIT_WORKER_SHARD, ordinal, host_epoch, 1, market, "unit-worker",
               spot_capacity_failures=tuple(spot_capacity_failures))


def unit_worker_pool(job):
    return job.platform.name, job.platform.instance_type


def allocated_platform(platform, instance_type, vcpus):
    allowed = {
        (platform.instance_type, platform.vcpus),
        (platform.concurrency_instance_type, platform.concurrency_vcpus),
    }
    if (instance_type, vcpus) not in allowed:
        raise ValueError("planned worker allocation is not available for the platform")
    return replace(platform, instance_type=instance_type, vcpus=vcpus)


def pool_platform(platform, instance_type):
    """The platform as a pool's workers launch it: its frozen type or its concurrency type."""
    vcpus = platform.vcpus if instance_type == platform.instance_type else platform.concurrency_vcpus
    return allocated_platform(platform, instance_type, vcpus)


def pool_platforms(platforms, items):
    """Each pool's worker platform."""
    by_name = {platform.name: platform for platform in platforms}
    return {item.pool: pool_platform(by_name[item.platform], item.instance_type) for item in items}


def unit_worker_timeout(longest_unit_seconds):
    """A worker's wrapper timeout: setup, lifetime, uploads, and a last unit as long as the pool's longest."""
    return (UNIT_WORKER_LIFETIME_SECONDS + UNIT_WORKER_SETUP_AND_UPLOAD_SECONDS +
            max(UNIT_WORKER_LAST_UNIT_SECONDS, math.ceil(longest_unit_seconds)))


def lease_timeout(estimated_seconds):
    """The time a host's stop time allots a lease of groups estimated at this many seconds."""
    return UNIT_LEASE_TIMEOUT_FACTOR * max(work_units.UNIT_SECONDS, math.ceil(estimated_seconds))


def longest_units(items):
    """Each pool's longest estimated unit."""
    longest = {}
    for item in items:
        longest[item.pool] = max(longest.get(item.pool, 0), item.estimated_seconds)
    return longest


def pool_shards(items):
    """Each pool's shards, whose language plan bundles its workers install."""
    shards = {}
    for item in items:
        shards.setdefault(item.pool, set()).add(item.shard)
    return {pool: sorted(names) for pool, names in shards.items()}


def platform_has_capacity(candidate, running, arguments):
    limit = getattr(arguments, 'max_concurrent_per_platform', DEFAULT_MAX_CONCURRENT)
    return sum(job.platform.name == candidate.platform.name for _, job, _ in running.values()) < limit


def validate_configuration(arguments, items):
    """Check the campaign's limits and derive its phase deadline from the phase's work items."""
    campaign_heap_size(arguments)
    if getattr(arguments, "spot_vcpu_reserve", SPOT_VCPU_SAFETY_RESERVE) < 0:
        raise SystemExit("Spot vCPU reserve must not be negative")
    regions = campaign_regions(arguments)
    amis = getattr(arguments, "regional_amis", None)
    for platform in campaign_platforms(arguments):
        listed = platform_regions(arguments, platform)
        if len(listed) != len(set(listed)) or not all(re.fullmatch(REGION_PATTERN, region) for region in listed):
            raise SystemExit(f"{platform} regions must be distinct AWS region names: {listed}")
        # A platform launches only in regions holding its image.
        missing = [region for region in listed if amis is not None and (platform, region) not in amis]
        if missing:
            raise SystemExit(f"platform-regions.tsv has no {platform} AMI for its regions: {missing}")
    buckets = getattr(arguments, "transfer_buckets", None) or {}
    if not set(buckets) <= set(regions):
        raise SystemExit(
            f"--transfer-bucket names regions the campaign does not allow: {sorted(set(buckets) - set(regions))}")
    if os.environ.get("TRANSFER_BUCKET") and (buckets or len(regions) > 1):
        # A shared bucket serves hosts in its own region, so name each one with its region.
        raise SystemExit("TRANSFER_BUCKET serves only a single-region campaign without --transfer-bucket; "
                         "name each region's shared bucket with --transfer-bucket")
    if shared_transfer_buckets(arguments) and not os.environ.get("TRANSFER_PREFIX"):
        raise SystemExit("--transfer-bucket and TRANSFER_BUCKET require TRANSFER_PREFIX")
    budget = getattr(arguments, "shared_budget", None)
    unit_platforms = getattr(arguments, "unit_platforms", {})
    workers = [unit_platforms[pool] for pool in sorted({item.pool for item in items}) if pool in unit_platforms]
    launchable = {platform.name: platform_regions(arguments, platform.name) for platform in workers}
    fleet_limits, fleet_vcpus = {}, None
    if budget:
        limits = region_limits(budget.policy)
        fleet_limits = {region: limits[region] for region in regions if region in limits}
        # A wave fills every allowed region's limit at once.
        fleet_vcpus = max(1, sum(fleet_limits[region]["max_vcpus"] for region in fleet_limits))
    if budget and getattr(arguments, "spot_vcpu_reserve", None) != budget.policy["spot_vcpu_reserve"]:
        raise SystemExit("shared fleet requires a reserve matching its policy")
    on_demand = set(on_demand_regions(budget.policy)) if budget else set()
    refused = sorted({platform.name for platform in workers
                      if purchase_policy(arguments, platform.name) != "spot" and
                      not on_demand & set(launchable[platform.name])})
    if budget and refused:
        raise SystemExit(f"shared fleet allows no On-Demand in a region of {', '.join(refused)}; give the "
                         "policy an On-Demand allowance in one of its regions, or buy with --purchase PLATFORM=spot")
    if getattr(arguments, "execute", False) and arguments.max_concurrent > DEFAULT_MAX_CONCURRENT and budget is None:
        raise SystemExit("more than 64 workers requires a shared fleet budget")
    validate_campaign_id(arguments.campaign_id)
    if type(arguments.max_concurrent) is not int or arguments.max_concurrent < 1:
        raise SystemExit("max concurrency must be a positive integer")
    per_platform = getattr(arguments, 'max_concurrent_per_platform', DEFAULT_MAX_CONCURRENT)
    if type(per_platform) is not int or per_platform < 1:
        raise SystemExit("per-platform concurrency must be a positive integer")
    if arguments.phase == "primary" and not items:
        raise SystemExit("primary campaign has no work items")
    host_limit = min(arguments.max_concurrent, budget.policy["max_hosts"]) if budget else arguments.max_concurrent
    if budget and any(platform.vcpus > max((fleet_limits[region]["max_vcpus"] for region in launchable[platform.name]
                                            if region in fleet_limits), default=0) for platform in workers):
        raise SystemExit("shared fleet vCPU limit cannot admit every worker type")
    required_seconds = unit_phase_seconds(arguments, items, host_limit, per_platform, fleet_vcpus)
    explicit_deadline = getattr(arguments, "phase_timeout_seconds", None)
    arguments.topology_deadline_seconds = (explicit_deadline if explicit_deadline is not None else
                                          max(PHASE_TIMEOUT_SECONDS[arguments.phase], required_seconds + 3600))
    if arguments.topology_deadline_seconds < required_seconds:
        raise SystemExit("campaign topology cannot fit its phase deadline with one retry wave: "
                         f"requires {required_seconds}s, allows {arguments.topology_deadline_seconds}s")
    if getattr(arguments, "language_plans", None) and campaign_heap_size(arguments) != "8g":
        raise SystemExit("language protocol requires an 8g heap")


def unit_phase_seconds(arguments, items, host_limit, per_platform, fleet_vcpus):
    """The wall clock the phase's units need: worker lifetimes of work at the concurrency limits,
    plus one retry wave, each wave the wrapper's full worker timeout."""
    platforms = getattr(arguments, "unit_platforms", {})
    work = {}
    vcpu_seconds = 0
    for item in items:
        work[item.pool] = work.get(item.pool, 0) + item.estimated_seconds
        platform = platforms.get(item.pool)
        if platform is not None:
            vcpu_seconds += platform.vcpus * item.estimated_seconds
    lifetime = UNIT_WORKER_LIFETIME_SECONDS
    waves = max([math.ceil(sum(work.values()) / (host_limit * lifetime))] +
                [math.ceil(seconds / (min(per_platform, host_limit) * lifetime)) for seconds in work.values()] +
                ([math.ceil(vcpu_seconds / (fleet_vcpus * lifetime))] if fleet_vcpus else []))
    return (waves + 1) * unit_worker_timeout(max(longest_units(items).values(), default=0))


def validate_durable_paths(root, arguments, result_root):
    build_output = (root / "target").resolve()
    paths = (
        ("result root", result_root),
        ("campaign root", getattr(arguments, "campaign_root", None)),
        ("shared fleet policy", getattr(arguments, "fleet_budget", None)),
        ("engine builds", getattr(arguments, "engine_builds", None)),
    )
    for label, path in paths:
        if path is None:
            continue
        resolved = path.resolve()
        if resolved == build_output or build_output in resolved.parents:
            raise SystemExit(
                f"{label} must be outside Maven build output {build_output}: {resolved}")


def campaign_heap_size(arguments):
    # Snapshot once; launch, phase prerequisites and recovery use this exact value.
    if not hasattr(arguments, "heap_size"):
        arguments.heap_size = normalize_heap_size(os.environ.get("BENCHMARK_HEAP_SIZE") or "8g")
    return arguments.heap_size


def validate_campaign_id(campaign_id, label="campaign ID"):
    if not re.fullmatch(CAMPAIGN_ID_PATTERN, campaign_id):
        raise SystemExit(f"{label} must be 1-63 letters, digits, '.', '_', and '-', starting with a letter or digit: "
                         f"{campaign_id}")


def candidate_campaign_id(campaign, name, language=False):
    """A candidate's campaign ID, under which its phases reserve the budget, tag their hosts, and number their host
    epochs; a language plan's phases run under their own."""
    return f"{campaign}.{name}" + (".lang" if language else "")


def parse_candidates(values):
    """Each --candidate NAME=COMMITISH as (name, commitish), in the order given."""
    candidates = {}
    for value in values:
        name, separator, commitish = value.partition("=")
        if not separator or not commitish or not re.fullmatch(CANDIDATE_NAME_PATTERN, name) or name in candidates:
            raise SystemExit(f"--candidate takes one NAME=COMMITISH per distinct name of letters, digits, '.', '_', "
                             f"and '-', starting with a letter or digit: {value}")
        candidates[name] = commitish
    return list(candidates.items())


def resolve_commit(root, commitish):
    commit = "" if commitish.startswith("-") else subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{commitish}^{{commit}}"], cwd=root, capture_output=True,
        text=True).stdout.strip()
    if not commit:
        raise SystemExit(f"candidate {commitish} is not a commit of this checkout")
    return commit


def check_candidate_tooling(root, commit):
    """Hosts run the candidate archive's benchmark tooling while this checkout's copy checks their results, so the
    two must be the same committed tree. The rest of this checkout may differ from the candidate."""
    if check_output(["git", "status", "--porcelain", "--", BENCHMARK_TOOLING], cwd=root):
        raise SystemExit(f"campaign execution requires committed benchmark tooling: {BENCHMARK_TOOLING} has changes")
    if (check_output(["git", "rev-parse", f"{commit}:{BENCHMARK_TOOLING}"], cwd=root) !=
            check_output(["git", "rev-parse", f"HEAD:{BENCHMARK_TOOLING}"], cwd=root)):
        raise SystemExit(f"candidate {commit} has other benchmark tooling than this checkout's HEAD; run the campaign "
                         "from a checkout whose tools/re2-benchmark matches the candidate's")


def generate_candidate_tables(root, commit, output):
    """Generate the candidate's row protocols and work units with its own committed generator."""
    with tempfile.TemporaryDirectory(prefix="candidate-tooling-") as temporary:
        archive = subprocess.Popen(["git", "archive", "--format=tar", commit, BASELINE_TOOLING], cwd=root,
                                   stdout=subprocess.PIPE)
        extract = subprocess.run(["tar", "-xf", "-", "-C", temporary], stdin=archive.stdout)
        archive.stdout.close()
        if archive.wait() != 0 or extract.returncode != 0:
            raise SystemExit(f"could not extract candidate {commit}'s benchmark tooling")
        output.mkdir(parents=True, exist_ok=True)
        # The generator imports the modules beside it, so it runs from the extracted copy without -I.
        subprocess.run([sys.executable, str(Path(temporary) / BASELINE_TOOLING / "generate-units.py"),
                        "--output-directory", str(output)], cwd=temporary, check=True, stdout=subprocess.DEVNULL)


def write_candidate_archive(root, commit, tables, output=None):
    """The SHA-256 of the candidate's deterministic archive: its commit, with its generated tables beside the
    baseline tooling, compressed without a timestamp. Writes it to output when given."""
    archive = subprocess.Popen(
        ["git", "archive", "--format=tar", "--prefix=" + BASELINE_TOOLING + "/",
         *(f"--add-file={tables / table}" for table in work_units.TABLES), "--prefix=", commit],
        cwd=root, stdout=subprocess.PIPE)
    compressor = subprocess.Popen(["gzip", "-n"], stdin=archive.stdout, stdout=subprocess.PIPE)
    archive.stdout.close()
    digest = hashlib.sha256()
    try:
        with (output.open("wb") if output else contextlib.nullcontext()) as output_file:
            while chunk := compressor.stdout.read(1024 * 1024):
                digest.update(chunk)
                if output_file:
                    output_file.write(chunk)
            if output_file:
                output_file.flush()
                os.fsync(output_file.fileno())
    finally:
        compressor.stdout.close()
        compressor_status = compressor.wait()
        archive_status = archive.wait()
    if archive_status != 0 or compressor_status != 0:
        raise SystemExit(f"could not archive candidate {commit}: git={archive_status}, gzip={compressor_status}")
    return digest.hexdigest()


def archive_file_list_sha256(path):
    with tarfile.open(path, "r:gz") as archive:
        # Directory entries keep the trailing slash tar -tf lists, which Python's TarInfo removes.
        names = [member.name.rstrip("/") + "/" if member.isdir() else member.name for member in archive]
        file_list = "".join(name + "\n" for name in sorted(names))
    return hashlib.sha256(file_list.encode()).hexdigest()


def write_new_file(path, content):
    """Write content to path durably unless the path already exists; returns whether it wrote. Callers may write the
    same path concurrently, so each writes its own temporary file."""
    temporary = path.with_name(f"{path.name}.{os.getpid()}.new")
    try:
        with temporary.open("wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.link(temporary, path)
    except FileExistsError:
        return False
    finally:
        temporary.unlink(missing_ok=True)
    fsync_directory(path.parent)
    return True


def tsv_bytes(fields, rows):
    output = io.StringIO()
    writer = csv.DictWriter(output, fieldnames=fields, delimiter="\t", lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return output.getvalue().encode()


@dataclass(frozen=True)
class FrozenCandidate:
    """A candidate commit as a campaign measures it: its immutable ref, and its archive and provenance under
    <campaign root>/<name>/candidate."""
    name: str
    commit: str
    ref: str
    directory: Path
    archive_sha256: str
    engine_tree: str

    @property
    def archive(self):
        return self.directory / CANDIDATE_ARCHIVE

    @property
    def provenance(self):
        return self.directory / CANDIDATE_PROVENANCE

    @property
    def row(self):
        return {"name": self.name, "commit": self.commit, "ref": self.ref, "archive_sha256": self.archive_sha256}


def freeze_candidate(root, campaign_root, campaign, name, commitish):
    """Freeze a candidate commit from Git, never from the working tree: create its benchmark ref, generate its tables
    with its own generator, and write its deterministic archive and provenance. A restart reproduces each and
    refuses any that differ."""
    commit = resolve_commit(root, commitish)
    check_candidate_tooling(root, commit)
    ref = f"refs/benchmarks/{campaign}/{name}"
    if subprocess.run(["git", "check-ref-format", ref], cwd=root).returncode != 0:
        raise SystemExit(f"campaign {campaign} and candidate {name} do not form a valid Git ref: {ref}")

    def ref_commit():
        return subprocess.run(["git", "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"], cwd=root,
                              capture_output=True, text=True).stdout.strip()

    existing = ref_commit()
    if not existing:
        created = subprocess.run(["git", "update-ref", ref, commit, "0" * 40], cwd=root, capture_output=True, text=True)
        # Another freeze of the candidate, such as its other phase's, may have created the ref first.
        existing = commit if created.returncode == 0 else ref_commit()
        if not existing:
            raise SystemExit(f"could not create candidate ref {ref}: {created.stderr.strip()}")
    if existing != commit:
        raise SystemExit(f"candidate ref {ref} already names {existing}, not {commit}")
    directory = campaign_root / name / "candidate"
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="candidate-tables-") as temporary:
        tables = Path(temporary)
        generate_candidate_tables(root, commit, tables)
        # The controller plans and checks units from this checkout's tables, which hosts read from the archive.
        stale = [table for table in work_units.TABLES
                 if not (root / BASELINE_TOOLING / table).is_file() or
                 (root / BASELINE_TOOLING / table).read_bytes() != (tables / table).read_bytes()]
        if stale:
            raise SystemExit(f"this checkout's generated tables {stale} differ from candidate {name}'s; regenerate "
                             f"them with {BASELINE_TOOLING}/generate-units.py")
        archive = directory / CANDIDATE_ARCHIVE
        if archive.exists():
            archive_sha256 = write_candidate_archive(root, commit, tables)
            if engine_build.sha256_file(archive) != archive_sha256:
                raise SystemExit(f"candidate archive {archive} differs from the deterministic archive of {commit}")
        else:
            # The candidate's phases may freeze it concurrently, so each stages its own copy of the deterministic
            # archive.
            descriptor, temporary_archive = tempfile.mkstemp(prefix=f"{archive.name}.", suffix=".new", dir=directory)
            os.close(descriptor)
            try:
                archive_sha256 = write_candidate_archive(root, commit, tables, Path(temporary_archive))
                os.replace(temporary_archive, archive)
            finally:
                Path(temporary_archive).unlink(missing_ok=True)
            fsync_directory(directory)

    def blob_sha256(path):
        return hashlib.sha256(subprocess.check_output(["git", "cat-file", "blob", f"{commit}:{path}"],
                                                      cwd=root)).hexdigest()

    engine_tree = check_output(["git", "rev-parse", f"{commit}:src/main"], cwd=root)
    provenance = tsv_bytes(CANDIDATE_PROVENANCE_FIELDS, [{
        "candidate_ref": ref,
        "candidate_commit": commit,
        "root_tree": check_output(["git", "rev-parse", f"{commit}^{{tree}}"], cwd=root),
        "engine_tree": engine_tree,
        "archive_sha256": archive_sha256,
        "archive_file_list_sha256": archive_file_list_sha256(archive),
        **{field: blob_sha256(f"{BASELINE_TOOLING}/{manifest}") for field, manifest in PROVENANCE_MANIFESTS.items()},
    }])
    path = directory / CANDIDATE_PROVENANCE
    if not write_new_file(path, provenance) and path.read_bytes() != provenance:
        raise SystemExit(f"candidate provenance {path} differs from candidate {name} at {commit}")
    return FrozenCandidate(name, commit, ref, directory, archive_sha256, engine_tree)


def read_candidates(campaign_root):
    """The campaign's candidates, as candidates.tsv records them."""
    path = campaign_root / "candidates.tsv"
    if not path.exists():
        return []
    with path.open(newline="") as input_file:
        return list(csv.DictReader(input_file, delimiter="\t"))


def record_candidate(campaign_root, candidate):
    """Record the candidate in the campaign's candidates.tsv, or check that it records the candidate at the same
    commit and archive."""
    with (campaign_root / "candidates.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        recorded = read_candidates(campaign_root)
        same = [row for row in recorded if row["name"] == candidate.name]
        if same and same != [candidate.row]:
            raise SystemExit(f"campaign candidates.tsv records candidate {candidate.name} as {same[0]}, not "
                             f"{candidate.row}")
        if not same:
            write_ledger(campaign_root / "candidates.tsv", recorded + [candidate.row], CANDIDATE_FIELDS,
                         key=lambda row: 0)


def validate_source(root, arguments):
    """Freeze the phase's candidate, or check the earlier freeze, and select it for the phase's hosts."""
    [(name, commitish)] = arguments.candidates
    candidate = freeze_candidate(root, arguments.campaign_root, arguments.campaign, name, commitish)
    record_candidate(arguments.campaign_root, candidate)
    arguments.candidate_ref = candidate.ref
    arguments.candidate_commit = candidate.commit
    arguments.candidate_archive = candidate.archive
    arguments.candidate_provenance = candidate.provenance
    arguments.candidate_archive_sha256 = candidate.archive_sha256
    arguments.engine_tree = candidate.engine_tree


def tracked_tooling(root):
    """The benchmark tooling files Git tracks, which the frozen inputs and the tooling digest hash."""
    return [root / path for path in check_output(["git", "ls-files", "tools/re2-benchmark"], cwd=root).splitlines()]


def tooling_digest(root):
    """The SHA-256 of the tracked benchmark tooling, over each file's path and content."""
    if not (root / ".git").exists():
        raise UnsafeCampaignError("a unit campaign records the digest of its tracked tooling, so it runs from a Git "
                                  "checkout")
    digest = hashlib.sha256()
    for path in tracked_tooling(root):
        digest.update(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(root)}\n".encode())
    return digest.hexdigest()


def frozen_input_fingerprint(root, arguments, items_path):
    if not (root / ".git").exists():
        return None
    tracked_inputs = tracked_tooling(root)
    tracked_inputs.extend((
        arguments.candidate_archive,
        arguments.candidate_provenance,
        items_path))
    tracked_inputs.extend(directory / "plan.json" for _, directory in
                          sorted(getattr(arguments, "language_plans", {}).items()))
    # The policy binds each manifest's SHA-256 and each wrapper checks the archives against
    # it, so large archives are not rehashed at every launch.
    tracked_inputs.extend(directory / engine_build.MANIFEST for _, directory in
                          sorted(getattr(arguments, "engine_build_directories", {}).items()))
    return (
        execution_policy(arguments),
        check_output(["git", "rev-parse", f"{arguments.candidate_ref}^{{commit}}"], cwd=root),
        tuple((str(path), hashlib.sha256(path.read_bytes()).hexdigest()) for path in tracked_inputs),
    )


def validate_frozen_inputs(root, arguments, items_path, expected_fingerprint):
    if expected_fingerprint is None:
        return
    if frozen_input_fingerprint(root, arguments, items_path) != expected_fingerprint:
        raise UnsafeCampaignError("the frozen candidate or campaign manifest changed during execution")


@dataclass(frozen=True)
class UnitValidation:
    """What a unit campaign's attempts and ledgers are accepted against, resolved once for its kind: an engine
    build, a language campaign's plans, or the baseline units of the campaign's shards."""
    kind: str
    # Each accepted receipt's frozen campaign fields.
    receipt_fields: dict
    # The environment-manifest fields each attempt's host must record.
    host_fields: dict
    # Regenerates an attempt's receipt from its kind's evidence, given the attempt's directory and receipt.
    validate_attempt: object
    # Accepts a phase's ledger of receipts as one campaign, given its path, its phase, and a confirmation
    # phase's confirmations.
    validate_ledger: object
    # The SHA-256 of each platform's accepted classes archive, which each attempt's host must record installing;
    # empty when the campaign installs no engine builds.
    classes_archives: dict = field(default_factory=dict)


def accepted_classes_archives(arguments):
    """Each platform's accepted classes archive SHA-256, from the build manifest of its accepted engine build."""
    return {platform: engine_build.load_manifest(directory, arguments.engine_build_policy[platform])
            ["archives"]["classes"]["sha256"]
            for platform, directory in getattr(arguments, "engine_build_directories", {}).items()}


def unit_validation(root, arguments, kind, plans=None):
    """Resolve once what the campaign's unit attempts and ledgers are accepted against; plans holds a language
    campaign's validated plans by their directories."""
    receipt_fields = {
        "campaign_id": arguments.campaign_id,
        "candidate_commit": arguments.candidate_commit,
        "candidate_archive_sha256": arguments.candidate_archive_sha256,
        "engine_tree": campaign_engine_tree(arguments),
        "heap_size": campaign_heap_size(arguments),
    }
    if kind == "build":
        return engine_build_validation(root, receipt_fields)
    version = getattr(arguments, "release_version", "")
    if kind == "language":
        # A language attempt checks its plan, and its host the campaign's comparator pins and release selection.
        directories = arguments.language_plans

        def validate_language_attempt(directory, receipt):
            return language_fleet.validate_unit_attempt(directory, directories[receipt["shard_id"]])

        def validate_language_ledger(receipts_path, phase, confirmations=()):
            language_fleet.validate_unit_campaign(
                plans, read_accepted_units(receipts_path), arguments.campaign_id, phase)

        host_fields = {"comparator_manifest_sha256": hashlib.sha256(
            (root / "tools/re2-benchmark/baseline/comparators.tsv").read_bytes()).hexdigest(),
            "regulator_release_version": version}
        return UnitValidation(kind, receipt_fields, host_fields, validate_language_attempt, validate_language_ledger,
                              accepted_classes_archives(arguments))
    tables = unit_session.load_unit_tables(campaign_manifest(root, arguments))
    release = released_artifact.manifest(root, version) if version else None

    def validate_baseline_attempt(directory, receipt):
        """An attempt's unit checks, and in a release campaign the proof that each route measured the selected
        release's JAR."""
        regenerated = unit_session.validate_unit_results(directory, tables=tables)
        if release:
            released_artifact.validate_baseline(directory, release)
        return regenerated

    def validate_baseline_ledger(receipts_path, phase, confirmations=()):
        unit_session.validate_unit_campaign(
            campaign_manifest(root, arguments),
            root / "tools/re2-benchmark/baseline/platforms.tsv",
            campaign_shards(root, arguments),
            receipts_path,
            arguments.campaign_id,
            (1,) if phase == "smoke" else (1, 2, 3),
            selected_shards=getattr(arguments, "selected_shards", None),
            confirmations=confirmations)

    return UnitValidation(kind, receipt_fields, {}, validate_baseline_attempt, validate_baseline_ledger,
                          accepted_classes_archives(arguments))


def engine_build_validation(root, receipt_fields):
    """An engine build phase's validation: one build per platform, each attempt checked against its own archives."""
    def validate_build_attempt(directory, receipt):
        return engine_build.validate_unit_attempt(directory)

    def validate_build_ledger(receipts_path, phase, confirmations=()):
        engine_build.validate_unit_campaign(root, read_accepted_units(receipts_path), receipt_fields["campaign_id"])

    return UnitValidation("build", receipt_fields, {}, validate_build_attempt, validate_build_ledger)


def write_phase_complete(result_root, arguments):
    """Mark a unit phase complete, once its controller verified every worker's cleanup and its gate passed."""
    path = result_root / PHASE_COMPLETE
    temporary = path.with_name(path.name + ".new")
    with temporary.open("w") as output:
        output.write(f"campaign_id={arguments.campaign_id}\nphase={arguments.phase}\ncleanup=verified\n")
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
    fsync_directory(result_root)


def require_phase_complete(result_root, campaign_id, phase):
    """A previous phase is consumed only once its controller marked it complete, its workers' cleanup verified."""
    path = result_root / PHASE_COMPLETE
    try:
        marker = read_properties(path)
    except (OSError, RuntimeError) as error:
        raise ValueError(f"{phase} phase has no completion marker after verified cleanup: {error}") from error
    if marker != {"campaign_id": campaign_id, "phase": phase, "cleanup": "verified"}:
        raise ValueError(f"{path} does not mark campaign {campaign_id}'s {phase} phase complete")


def validate_unit_phase_prerequisite(arguments, validation, result_root, phase):
    """Revalidate the accepted unit campaign of the previous phase and its retained attempts."""
    if result_root is None:
        raise SystemExit(f"{arguments.phase} execution requires --{phase}-results")
    policy_path = result_root / "execution-policy.json"
    recorded = json.loads(policy_path.read_text()) if policy_path.exists() else {}
    if recorded.get("engine_builds") != execution_policy(arguments)["engine_builds"]:
        raise SystemExit(f"{arguments.phase} must use the engine builds its {phase} prerequisite used")
    accepted_units = result_root / "accepted-units.tsv"
    try:
        unit_session.check_semantic_rejections(result_root)
        require_phase_complete(result_root, arguments.campaign_id, phase)
        validation.validate_ledger(accepted_units, phase)
        for receipt in read_accepted_units(accepted_units):
            validate_unit_artifacts(validation, result_root, receipt)
    except (OSError, ValueError, UnsafeCampaignError) as error:
        raise SystemExit(f"{phase} prerequisite campaign is not accepted: {error}") from error


def validate_prerequisites(arguments, validation):
    if arguments.phase == "primary":
        validate_unit_phase_prerequisite(arguments, validation, arguments.smoke_results, "smoke")
    elif arguments.phase == "confirmation":
        validate_unit_phase_prerequisite(arguments, validation, arguments.primary_results, "primary")


def resolve_engine_builds(root, arguments):
    builds = getattr(arguments, "engine_builds", None)
    if arguments.phase == "build":
        if builds is not None:
            raise SystemExit("engine builds apply only to measuring phases")
        return
    if builds is None:
        raise SystemExit(f"{arguments.phase} execution requires --engine-builds from an accepted build phase")
    buckets = shared_transfer_buckets(arguments)
    missing = [region for region in campaign_regions(arguments) if region not in buckets]
    if missing:
        # Hosts install the archives from a bucket in their own region, uploaded once before launch.
        raise SystemExit(f"--engine-builds requires a shared transfer bucket in every allowed region; "
                         f"name one for {missing} with --transfer-bucket REGION=BUCKET, created as "
                         "aws/README.md \"Launch regions\" describes")
    # The build phase's accepted units, one build per platform, each revalidated from its retained attempt.
    # Any measuring campaign may install them, so they are checked under the build phase's own campaign ID, and
    # against this checkout only for what the engines are made from: the candidate, the build scripts, the
    # Trino pin, and each platform's builder.
    accepted = builds / "accepted-units.tsv"
    try:
        state = read_campaign_state(builds / "campaign-state.tsv")
        if state["schema_version"] != "2" or state["phase"] != "build" or not state["campaign_id"]:
            raise ValueError(f"{builds} is not a build phase's result root")
        validation = engine_build_validation(root, {
            "campaign_id": state["campaign_id"],
            "candidate_commit": arguments.candidate_commit,
            "candidate_archive_sha256": arguments.candidate_archive_sha256})
        unit_session.check_semantic_rejections(builds)
        require_phase_complete(builds, state["campaign_id"], "build")
        validation.validate_ledger(accepted, "build")
        receipts = read_accepted_units(accepted)
        for receipt in receipts:
            validate_unit_artifacts(validation, builds, receipt)
            engine_build.validate_trino_pin(root, unit_attempt_directory(builds, receipt) / engine_build.DIRECTORY)
    except (OSError, RuntimeError, ValueError) as error:
        raise SystemExit(f"engine builds are not accepted: {error}") from error
    # A measuring worker names its platform's build by the build manifest's SHA-256.
    arguments.engine_build_policy = {receipt["platform"]: receipt["observed_rows_sha256"] for receipt in receipts}
    arguments.engine_build_directories = {
        receipt["platform"]: unit_attempt_directory(builds, receipt) / engine_build.DIRECTORY for receipt in receipts}


def validate_dependencies(root, arguments):
    if getattr(arguments, "language_plans", None):
        # Plans already contain checksummed source workloads. The unit worker
        # verifies the pinned Joni jar in its engine build's candidate classes.
        for directory in arguments.language_plans.values():
            language_fleet.validate(directory)
    else:
        subprocess.run([str(root / "tools/re2-benchmark/baseline/validate-manifest.py")], check=True)


def describe_image(arguments, region, ami_id):
    return check_output(aws_output(arguments, region) + [
        "ec2", "describe-images", "--image-ids", ami_id,
        "--query", "Images[0].[Architecture,State,OwnerId,Name]", "--output", "text",
    ]).split("\t")


def validate_aws(arguments, platforms, jobs=None):
    required_platforms = list({
        (platform.name, platform.instance_type): platform
        for platform in ([job.platform for job in jobs] if jobs is not None else platforms)
    }.values())
    if not required_platforms:
        required_platforms = platforms
    regions = list(dict.fromkeys(campaign_regions(arguments) + [
        region for platform in required_platforms for region in platform_regions(arguments, platform.name)]))
    account = check_output(aws_output(arguments, regions[0]) + [
        "sts", "get-caller-identity", "--query", "Account", "--output", "text"])
    budget = getattr(arguments, "shared_budget", None)
    if budget and (account != budget.policy["account"] or not set(regions) <= set(region_limits(budget.policy))):
        raise SystemExit("AWS account or region differs from the shared fleet policy")
    spot_quotas, on_demand_quotas = {}, {}
    # The wrapper launches into a public subnet of each region's default VPC.
    public_zones = {}
    for region in regions:
        prefix = aws_output(arguments, region)
        vpc = check_output(prefix + ["ec2", "describe-vpcs", "--filters", "Name=is-default,Values=true",
                                     "--query", "Vpcs[0].VpcId", "--output", "text"])
        public_zones[region] = set() if vpc in ("", "None") else set(check_output(prefix + [
            "ec2", "describe-subnets", "--filters", f"Name=vpc-id,Values={vpc}", "Name=state,Values=available",
            "--query", "Subnets[?MapPublicIpOnLaunch].AvailabilityZone", "--output", "text"]).split())
        if not public_zones[region]:
            raise SystemExit(f"allowed region {region} has no public default-VPC subnet")
        spot_quota = float(check_output(prefix + [
            "service-quotas", "get-service-quota", "--service-code", "ec2",
            "--quota-code", STANDARD_SPOT_QUOTA_CODE, "--query", "Quota.Value", "--output", "text",
        ]))
        on_demand_quota = float(check_output(prefix + [
            "service-quotas", "get-service-quota", "--service-code", "ec2",
            "--quota-code", STANDARD_ON_DEMAND_QUOTA_CODE, "--query", "Quota.Value", "--output", "text",
        ]))
        if budget:
            limits = region_limits(budget.policy)[region]
            if limits["max_vcpus"] > spot_quota - limits["spot_vcpu_reserve"]:
                raise SystemExit(
                    f"shared fleet max_vcpus exceeds the Standard Spot quota after its reserve in {region}")
            # A region without an On-Demand allowance never launches On-Demand, whatever its quota.
            if ("on_demand_max_vcpus" in limits and
                    limits["on_demand_max_vcpus"] > on_demand_quota - ON_DEMAND_VCPU_SAFETY_RESERVE):
                raise SystemExit(
                    f"shared fleet on_demand_max_vcpus exceeds the Standard On-Demand quota after its reserve in {region}")
        spot_quotas[region], on_demand_quotas[region] = spot_quota, on_demand_quota
    # Each platform needs room for one host, after the safety reserves, in a region of its list in a market it buys.
    for platform in required_platforms:
        listed = platform_regions(arguments, platform.name)
        spot = any(spot_quotas[region] >= getattr(arguments, "spot_vcpu_reserve", SPOT_VCPU_SAFETY_RESERVE) +
                   platform.vcpus for region in listed)
        on_demand = any(on_demand_quotas[region] >= ON_DEMAND_VCPU_SAFETY_RESERVE + platform.vcpus for region in listed)
        purchase = purchase_policy(arguments, platform.name)
        quotas = "; ".join(f"{region}: spot={spot_quotas[region]:g}, on-demand={on_demand_quotas[region]:g}"
                           for region in listed)
        if purchase == "spot" and not spot:
            raise SystemExit(f"Standard Spot quota cannot admit one host of {platform.name} after the configured "
                             f"reserve: {quotas}")
        if purchase == "on-demand" and not on_demand:
            raise SystemExit(f"Standard On-Demand quota cannot admit one host of {platform.name} after its safety "
                             f"reserve: {quotas}")
        if not spot and not on_demand:
            raise SystemExit(f"Neither Standard Spot nor On-Demand quota can admit one host of {platform.name} "
                             f"after safety reserves: {quotas}")
    # The Oregon pin names the image; every other region must run an image of that name.
    amis = getattr(arguments, "regional_amis", None)
    names = {}
    offerings = {}
    verified = set()
    for platform in required_platforms:
        expected_architecture = "x86_64" if platform.architecture == "intel" else "arm64"
        if platform.name not in names:
            image = describe_image(arguments, PINNED_AMI_REGION, platform.ami_id)
            if (len(image) != 4 or image[:3] != [expected_architecture, "available", AMAZON_AMI_OWNER] or
                    image[3] in ("", "None")):
                raise SystemExit(f"unexpected AMI identity for {platform.name}: {image}")
            names[platform.name] = image[3]
        offered = []
        for region in platform_regions(arguments, platform.name):
            zones = check_output(aws_output(arguments, region) + [
                "ec2", "describe-instance-type-offerings", "--location-type", "availability-zone",
                "--filters", f"Name=instance-type,Values={platform.instance_type}",
                "--query", "InstanceTypeOfferings[].Location", "--output", "text",
            ]).split()
            if not set(zones) & public_zones[region]:
                continue
            ami_id = platform.ami_id if amis is None else amis.get((platform.name, region))
            if ami_id is None:
                raise SystemExit(f"platform-regions.tsv has no {platform.name} AMI for {region}")
            if (platform.name, region) not in verified and (region, ami_id) != (PINNED_AMI_REGION, platform.ami_id):
                image = describe_image(arguments, region, ami_id)
                if image != [expected_architecture, "available", AMAZON_AMI_OWNER, names[platform.name]]:
                    raise SystemExit(f"{platform.name} AMI {ami_id} in {region} is not {names[platform.name]}: {image}")
            verified.add((platform.name, region))
            offered.append(region)
        if not offered:
            raise SystemExit(f"{platform.instance_type} is not offered in any region of {platform.name}'s list")
        offerings[platform.instance_type] = offered
    for region, bucket in shared_transfer_buckets(arguments).items():
        prefix = aws_output(arguments, region)
        location = check_output(prefix + ["s3api", "get-bucket-location", "--bucket", bucket,
                                          "--query", "LocationConstraint", "--output", "text"])
        # S3 reports us-east-1 as no constraint and keeps the legacy EU name for eu-west-1.
        location = {"None": "us-east-1", "": "us-east-1", "EU": "eu-west-1"}.get(location, location)
        tagged = check_output(prefix + ["s3api", "get-bucket-tagging", "--bucket", bucket, "--query",
                                        "length(TagSet[?Key==`Project` && Value==`regulator-benchmark`])",
                                        "--output", "text"])
        if location != region or tagged != "1":
            raise SystemExit(f"shared transfer bucket {bucket} is not a tagged Project=regulator-benchmark "
                             f"bucket in {region}: location={location}")
    arguments.ami_names = names
    arguments.region_offerings = offerings


def engine_archives(arguments, platform):
    """The archives of a platform's engine build that its measuring workers install, by name, for the phase's
    units: a baseline unit runs the candidate's classes, the Trino harness, and the native runners, and a language
    unit the classes and the native runners."""
    manifest = engine_build.load_manifest(arguments.engine_build_directories[platform],
                                          arguments.engine_build_policy[platform])
    kind = "language" if getattr(arguments, "language_plans", None) else "baseline"
    return {name: manifest["archives"][name] for name in engine_build.UNIT_ARCHIVES[kind]}


def upload_engine_builds(arguments, jobs):
    """Put each engine archive its workers install once in every region that may launch one of them."""
    directories = getattr(arguments, "engine_build_directories", None)
    if not directories:
        return
    uploads = {}
    for job in jobs:
        for name, archive in engine_archives(arguments, job.platform.name).items():
            for region in launch_regions(arguments, job):
                uploads[region, f"{os.environ['TRANSFER_PREFIX']}/engine-builds/{archive['sha256']}.tar.zst"] = (
                    directories[job.platform.name] / archive["file"], archive["sha256"],
                    f"{job.platform.name} {name} archive")
    upload_shared_archives(arguments, uploads)


def upload_language_bundles(arguments, jobs):
    """Put each language plan's bundle once in every region that may launch a worker of a pool that measures it."""
    bundles = arguments.language_bundles
    upload_shared_archives(arguments, {
        (region, f"{os.environ['TRANSFER_PREFIX']}/language-plans/{bundles[shard][1]}.tar.zst"): (
            bundles[shard][0], bundles[shard][1], f"{shard} language plan bundle")
        for job in jobs for shard in arguments.pool_shards[unit_worker_pool(job)]
        for region in launch_regions(arguments, job)})


def jdk_archive(campaign_root, platform):
    return campaign_root / "jdks" / f"{platform.jdk_sha256}.tar.gz"


def stage_jdks(arguments, jobs):
    """Download each pinned JDK once for the campaign, verified against its pin, into the campaign root, where
    every phase's workers take it from, and put it in the shared transfer bucket of every region that may launch a
    worker installing it. A worker launched elsewhere carries the verified copy in its own session's bucket."""
    buckets = shared_transfer_buckets(arguments)
    uploads = {}
    directory = arguments.campaign_root / "jdks"
    directory.mkdir(parents=True, exist_ok=True)
    # Phases running side by side download one at a time. A copy appears only once verified, so a later phase or
    # a restart reuses it.
    with (directory / "download.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        for job in jobs:
            path = jdk_archive(arguments.campaign_root, job.platform)
            if path.exists():
                continue
            download = path.with_name(path.name + ".download")
            print(f"downloading the {job.platform.name} JDK", flush=True)
            # A download slower than the uploads' minimum rate for a minute fails, as a stalled upload times out.
            subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location", "--retry", "5",
                            "--connect-timeout", "60", "--speed-limit", str(SHARED_ARCHIVE_UPLOAD_MINIMUM_RATE),
                            "--speed-time", "60", "--output", str(download), job.platform.jdk_url], check=True)
            if engine_build.sha256_file(download) != job.platform.jdk_sha256:
                download.unlink()
                raise SystemExit(f"the {job.platform.name} JDK at {job.platform.jdk_url} differs from its pin")
            os.replace(download, path)
    for job in jobs:
        path = jdk_archive(arguments.campaign_root, job.platform)
        for region in launch_regions(arguments, job):
            if region in buckets:
                uploads[region, f"{os.environ['TRANSFER_PREFIX']}/jdks/{job.platform.jdk_sha256}.tar.gz"] = (
                    path, job.platform.jdk_sha256, f"{job.platform.name} JDK")
    upload_shared_archives(arguments, uploads)


def upload_shared_archives(arguments, uploads):
    """Put each content-addressed archive, {(region, key): (path, sha256, label)}, in its region's transfer bucket."""
    buckets = shared_transfer_buckets(arguments)
    for (region, key), (path, sha256, label) in sorted(uploads.items()):
        checksum = base64.b64encode(bytes.fromhex(sha256)).decode()
        prefix = aws_output(arguments, region) + ["s3api"]
        for attempt in range(1, SHARED_ARCHIVE_UPLOAD_ATTEMPTS + 1):
            try:
                existing = check_output(prefix + [
                    "head-object", "--bucket", buckets[region], "--key", key, "--checksum-mode", "ENABLED",
                    "--query", "ChecksumSHA256", "--output", "text"], stderr=subprocess.DEVNULL, timeout=60)
            except subprocess.SubprocessError:
                existing = None
            if existing == checksum:
                break
            print(f"uploading {label} to {region} (attempt {attempt})", flush=True)
            try:
                # A single PUT keeps S3's checksum the whole-object SHA-256, which S3 verifies on upload.
                check_output(prefix + [
                    "put-object", "--bucket", buckets[region], "--key", key, "--body", str(path),
                    "--checksum-algorithm", "SHA256", "--checksum-sha256", checksum],
                    timeout=60 + path.stat().st_size / SHARED_ARCHIVE_UPLOAD_MINIMUM_RATE)
                break
            except subprocess.SubprocessError as error:
                if attempt == SHARED_ARCHIVE_UPLOAD_ATTEMPTS:
                    raise SystemExit(f"could not upload the {label} to s3://{buckets[region]}/{key}: {error}") from error


def spot_placement_scores(arguments, instance_type, regions):
    """Return each region's 1-10 Spot placement score, or None when AWS cannot say."""
    if not hasattr(arguments, "placement_scores"):
        arguments.placement_scores = {}
    key = (instance_type, tuple(regions))
    cached = arguments.placement_scores.get(key)
    now = time.monotonic()
    if cached is not None and now - cached[0] < PLACEMENT_SCORE_CACHE_SECONDS:
        return cached[1]
    # A campaign-constant target keeps every query the same configuration.
    target = min(arguments.max_concurrent, getattr(arguments, "max_concurrent_per_platform", DEFAULT_MAX_CONCURRENT))
    try:
        output = check_output(aws_output(arguments, regions[0]) + [
            "ec2", "get-spot-placement-scores", "--instance-types", instance_type,
            "--target-capacity", str(target), "--region-names", *regions,
            "--query", "SpotPlacementScores[].[Region,Score]", "--output", "text"])
        scores = {}
        for line in output.splitlines():
            region, score = line.split()
            if region in regions and 1 <= int(score) <= 10:
                scores[region] = int(score)
    except (OSError, ValueError, subprocess.SubprocessError):
        scores = None
    arguments.placement_scores[key] = (now, scores)
    return scores


def launch_regions(arguments, job):
    """Return the regions of the job's platform's list that offer its instance type and image."""
    offered = getattr(arguments, "region_offerings", {}).get(job.platform.instance_type)
    amis = getattr(arguments, "regional_amis", None)
    regions = [region for region in platform_regions(arguments, job.platform.name)
               if (offered is None or region in offered) and (amis is None or (job.platform.name, region) in amis)]
    if not regions:
        raise UnsafeCampaignError(
            f"no region of {job.platform.name}'s list can launch {job.platform.name} {job.platform.instance_type}")
    return regions


def launch_placements(arguments, job):
    """Order the regions of the job's platform's list that can launch it, best Spot placement score first."""
    regions = launch_regions(arguments, job)
    if job.market != "spot":
        return [(region, "") for region in regions]
    # A Spot launch skips the regions where its pool's Spot launches found no capacity within the cool-down.
    eligible = [region for region in regions if region not in job.spot_capacity_failures]
    if len(eligible) <= 1:
        return [(region, "") for region in eligible]
    scores = spot_placement_scores(arguments, job.platform.instance_type, regions)
    if scores is None:
        # Without scores, keep the list's order; launch admission picks the first region with headroom
        # only where it queries quota.
        return [(region, "") for region in eligible]
    # The sort is stable, so equal scores keep the list's order.
    return [(region, str(scores.get(region, ""))) for region in
            sorted(eligible, key=lambda region: -scores.get(region, 0))]


def place(job, placement):
    region, score = placement
    return replace(job, region=region, placement_score=score)


def place_with_headroom(job, placements, query):
    """Place the job in the first region with room, else return each region's capacity."""
    capacities = []
    failure = None
    for placement in placements:
        try:
            capacity = query(placement[0])
        except UnsafeCampaignError:
            raise
        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
            failure = error
            continue
        if capacity["available"] >= job.platform.vcpus:
            return place(job, placement), capacities, None
        capacities.append((placement[0], capacity))
    return None, capacities, failure


def capacity_detail(capacities):
    details = [";".join(f"{key}={value}" for key, value in capacity.items()) for _, capacity in capacities]
    if len(capacities) == 1:
        return details[0]
    return " ".join(f"{region}:{detail}" for (region, _), detail in zip(capacities, details))


def is_standard_instance_type(instance_type):
    return bool(instance_type) and instance_type[0].lower() in STANDARD_INSTANCE_FAMILIES


def shared_fleet_snapshot(arguments, region=None, market="spot"):
    prefix = aws_output(arguments, region)
    spot = market == "spot"
    quota = int(float(check_output(prefix + ["service-quotas", "get-service-quota", "--service-code", "ec2",
                      "--quota-code", STANDARD_SPOT_QUOTA_CODE if spot else STANDARD_ON_DEMAND_QUOTA_CODE,
                      "--query", "Quota.Value", "--output", "text"])))
    instances = json.loads(check_output(prefix + ["ec2", "describe-instances", "--filters",
                           *(["Name=instance-lifecycle,Values=spot"] if spot else []),
                           "Name=instance-state-name,Values=pending,running", "--query",
                           "Reservations[].Instances[].{id:InstanceId,type:InstanceType,lifecycle:InstanceLifecycle,tags:Tags}",
                           "--output", "json"]))
    # EC2 cannot filter for the absent lifecycle of On-Demand instances.
    instances = [item for item in instances if (item.pop("lifecycle", None) == "spot") == spot]
    requests = json.loads(check_output(prefix + ["ec2", "describe-spot-instance-requests", "--filters",
                          "Name=state,Values=open,active", "--query",
                          "SpotInstanceRequests[].{id:SpotInstanceRequestId,instance_id:InstanceId,type:LaunchSpecification.InstanceType,tags:Tags}",
                          "--output", "json"])) if spot else []
    if any(not item.get("type") for item in instances + requests):
        raise BudgetError(f"cannot account for {'a Spot instance or request' if spot else 'an On-Demand instance'} "
                          "with no instance type")
    instances = [item for item in instances if is_standard_instance_type(item["type"])]
    requests = [item for item in requests if is_standard_instance_type(item["type"])]
    types = sorted({item["type"] for item in instances + requests})
    metadata = json.loads(check_output(prefix + ["ec2", "describe-instance-types", "--instance-types", *types,
                          "--query", "InstanceTypes[].{type:InstanceType,vcpus:VCpuInfo.DefaultVCpus}",
                          "--output", "json"])) if types else []
    vcpus = {item["type"]: int(item["vcpus"]) for item in metadata}
    for item in instances + requests:
        if item["type"] not in vcpus:
            raise BudgetError("AWS omitted requested vCPU metadata")
        tags = {tag["Key"]: tag["Value"] for tag in item.pop("tags", None) or []}
        item.update(vcpus=vcpus[item["type"]], campaign_id=tags.get("BaselineCampaign"), host_epoch=tags.get("HostEpoch"))
    return {"quota": quota, "instances": instances, "requests": requests}


def region_jobs(arguments, running_jobs, region):
    """Keep the running jobs placed in the region whose capacity is being counted."""
    if region is None:
        return running_jobs
    return [job for job in running_jobs if (job.region or platform_regions(arguments, job.platform.name)[0]) == region]


def current_on_demand_capacity(arguments, campaign_id, running_jobs, region=None):
    prefix = aws_output(arguments, region)
    running_jobs = region_jobs(arguments, running_jobs, region)
    quota = int(float(check_output(prefix + [
        "service-quotas", "get-service-quota", "--service-code", "ec2",
        "--quota-code", STANDARD_ON_DEMAND_QUOTA_CODE,
        "--query", "Quota.Value", "--output", "text",
    ])))
    instances = json.loads(check_output(prefix + [
        "ec2", "describe-instances",
        "--filters", "Name=instance-state-name,Values=pending,running",
        "--query", "Reservations[].Instances[].{instance_type:InstanceType,lifecycle:InstanceLifecycle,tags:Tags}",
        "--output", "json",
    ]) or "[]")
    instance_types = sorted({
        instance["instance_type"]
        for instance in instances
        if instance.get("lifecycle") != "spot" and is_standard_instance_type(instance.get("instance_type"))
    })
    vcpus_by_type = {}
    if instance_types:
        descriptions = json.loads(check_output(prefix + [
            "ec2", "describe-instance-types", "--instance-types", *instance_types,
            "--query", "InstanceTypes[].{instance_type:InstanceType,vcpus:VCpuInfo.DefaultVCpus}",
            "--output", "json",
        ]))
        vcpus_by_type = {
            description["instance_type"]: int(description["vcpus"])
            for description in descriptions
        }

    running_epochs = {
        str(job.host_epoch)
        for job in running_jobs
        if job.market == "on-demand"
    }
    used_vcpus = 0
    observed_running_epochs = set()
    for instance in instances:
        instance_type = instance.get("instance_type")
        if instance.get("lifecycle") == "spot" or not is_standard_instance_type(instance_type):
            continue
        tags = {
            tag["Key"]: tag["Value"]
            for tag in instance.get("tags") or []
        }
        if tags.get("BaselineCampaign") == campaign_id and tags.get("HostEpoch") in running_epochs:
            # The local reservation below already covers this instance. This also
            # covers the interval before a newly launched instance is visible.
            host_epoch = tags["HostEpoch"]
            if host_epoch in observed_running_epochs:
                raise UnsafeCampaignError(
                    f"multiple active campaign instances use host epoch {host_epoch}")
            observed_running_epochs.add(host_epoch)
            continue
        try:
            used_vcpus += vcpus_by_type[instance_type]
        except KeyError as error:
            raise RuntimeError(f"AWS omitted vCPU metadata for {instance_type}") from error

    reserved_vcpus = sum(
        job.platform.vcpus
        for job in running_jobs
        if job.market == "on-demand"
    )
    available_vcpus = max(
        0,
        quota - used_vcpus - reserved_vcpus - ON_DEMAND_VCPU_SAFETY_RESERVE)
    return {
        "quota": quota,
        "used": used_vcpus,
        "reserved": reserved_vcpus,
        "safety_reserve": ON_DEMAND_VCPU_SAFETY_RESERVE,
        "available": available_vcpus,
    }


def current_spot_capacity(arguments, campaign_id, running_jobs, region=None):
    prefix = aws_output(arguments, region)
    running_jobs = region_jobs(arguments, running_jobs, region)
    quota = int(float(check_output(prefix + [
        "service-quotas", "get-service-quota", "--service-code", "ec2",
        "--quota-code", STANDARD_SPOT_QUOTA_CODE,
        "--query", "Quota.Value", "--output", "text",
    ])))
    instances = json.loads(check_output(prefix + [
        "ec2", "describe-instances",
        "--filters", "Name=instance-state-name,Values=pending,running",
        "--query", "Reservations[].Instances[].{instance_id:InstanceId,instance_type:InstanceType,lifecycle:InstanceLifecycle,tags:Tags}",
        "--output", "json",
    ]) or "[]")
    requests = json.loads(check_output(prefix + [
        "ec2", "describe-spot-instance-requests",
        "--filters", "Name=state,Values=open,active",
        "--query", "SpotInstanceRequests[].{instance_id:InstanceId,instance_type:LaunchSpecification.InstanceType,tags:Tags}",
        "--output", "json",
    ]) or "[]")
    instance_types = sorted(
        {
            instance["instance_type"]
            for instance in instances
            if instance.get("lifecycle") == "spot" and is_standard_instance_type(instance.get("instance_type"))
        } |
        {
            request["instance_type"]
            for request in requests
            if is_standard_instance_type(request.get("instance_type"))
        })
    vcpus_by_type = {}
    if instance_types:
        descriptions = json.loads(check_output(prefix + [
            "ec2", "describe-instance-types", "--instance-types", *instance_types,
            "--query", "InstanceTypes[].{instance_type:InstanceType,vcpus:VCpuInfo.DefaultVCpus}",
            "--output", "json",
        ]))
        vcpus_by_type = {
            description["instance_type"]: int(description["vcpus"])
            for description in descriptions
        }

    running_epochs = {
        str(job.host_epoch)
        for job in running_jobs
        if job.market == "spot"
    }
    used_vcpus = 0
    observed_running_epochs = set()
    visible_instance_ids = {instance.get("instance_id") for instance in instances if instance.get("instance_id")}
    for instance in instances:
        instance_type = instance.get("instance_type")
        if instance.get("lifecycle") != "spot" or not is_standard_instance_type(instance_type):
            continue
        tags = {
            tag["Key"]: tag["Value"]
            for tag in instance.get("tags") or []
        }
        if tags.get("BaselineCampaign") == campaign_id and tags.get("HostEpoch") in running_epochs:
            host_epoch = tags["HostEpoch"]
            if host_epoch in observed_running_epochs:
                raise UnsafeCampaignError(
                    f"multiple active campaign instances use host epoch {host_epoch}")
            observed_running_epochs.add(host_epoch)
            continue
        try:
            used_vcpus += vcpus_by_type[instance_type]
        except KeyError as error:
            raise RuntimeError(f"AWS omitted vCPU metadata for {instance_type}") from error
    for request in requests:
        instance_type = request.get("instance_type")
        if request.get("instance_id") in visible_instance_ids or not is_standard_instance_type(instance_type):
            continue
        tags = {
            tag["Key"]: tag["Value"]
            for tag in request.get("tags") or []
        }
        if tags.get("BaselineCampaign") == campaign_id and tags.get("HostEpoch") in running_epochs:
            host_epoch = tags["HostEpoch"]
            if host_epoch in observed_running_epochs:
                raise UnsafeCampaignError(
                    f"multiple active campaign resources use host epoch {host_epoch}")
            observed_running_epochs.add(host_epoch)
            continue
        try:
            used_vcpus += vcpus_by_type[instance_type]
        except KeyError as error:
            raise RuntimeError(f"AWS omitted vCPU metadata for {instance_type}") from error

    reserved_vcpus = sum(
        job.platform.vcpus
        for job in running_jobs
        if job.market == "spot"
    )
    safety_reserve = getattr(arguments, "spot_vcpu_reserve", SPOT_VCPU_SAFETY_RESERVE)
    available_vcpus = max(
        0,
        quota - used_vcpus - reserved_vcpus - safety_reserve)
    return {
        "quota": quota,
        "used": used_vcpus,
        "reserved": reserved_vcpus,
        "safety_reserve": safety_reserve,
        "available": available_vcpus,
    }


def write_jobs(path, jobs, *, extend=False):
    write_manifest(path, JOB_MANIFEST_FIELDS, [
        (job.platform.name, job.shard, job.replica, job.host_epoch, job.attempt, job.market,
         job.platform.instance_type, job.platform.architecture) for job in jobs], extend=extend)


def write_work_items(path, items):
    write_manifest(path, WORK_ITEM_FIELDS, [
        (item.platform, item.instance_type, item.shard, item.unit_id, item.replica, f"{item.estimated_seconds:g}",
         ",".join(f"{group}:{seconds:g}" for group, seconds in item.groups),
         ",".join(sorted(item.excluded_instances)) or "-") for item in items])


def write_manifest(path, fields, rows, *, extend=False):
    """Freeze a manifest; a later write may only reproduce it or, with extend, append to it."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_name(path.name + ".new")
    with temporary_path.open("w", newline="") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        writer.writerow(fields)
        writer.writerows(rows)
        output_file.flush()
        os.fsync(output_file.fileno())
    existing = path.read_bytes() if path.exists() else None
    written = temporary_path.read_bytes()
    if existing is not None and existing != written and not (extend and written.startswith(existing)):
        temporary_path.unlink()
        raise UnsafeCampaignError(f"existing manifest changed: {path}")
    os.replace(temporary_path, path)
    fsync_directory(path.parent)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    path.with_suffix(".sha256").write_text(f"{digest}  {path.name}\n")


def job_result_root(result_root, job):
    return (result_root / "jobs" / job.platform.name / job.shard /
            f"replica-{job.replica}" / f"epoch-{job.host_epoch}")


def phase_protocol(arguments):
    return getattr(arguments, "smoke_protocol", "smoke") if arguments.phase == "smoke" else "qualification"


def job_environment(job, arguments, root, result_root):
    purchase = purchase_policy(arguments, job.platform.name)
    if purchase == "spot" and job.market != "spot":
        raise UnsafeCampaignError(f"{job.platform.name} buys only Spot, so it may not launch an On-Demand job")
    if purchase == "on-demand" and job.market != "on-demand":
        raise UnsafeCampaignError(f"{job.platform.name} buys only On-Demand, so it may not launch a Spot job")
    environment = os.environ.copy()
    if getattr(arguments, "shared_budget", None):
        environment["CAMPAIGN_SPOT_MAX_PRICE"] = str(arguments.shared_budget.policy["hourly_rates"][job.platform.instance_type]["spot"])
    architecture = job.platform.architecture
    benchmark_protocol = phase_protocol(arguments)
    regions = platform_regions(arguments, job.platform.name)
    region = job.region or regions[0]
    amis = getattr(arguments, "regional_amis", None)
    ami_id = job.platform.ami_id if amis is None else amis.get((job.platform.name, region))
    if ami_id is None:
        raise UnsafeCampaignError(f"platform-regions.tsv has no {job.platform.name} AMI for {region}")
    buckets = getattr(arguments, "transfer_buckets", None)
    if buckets:
        # Hosts read inputs from a bucket in their own region; others get a per-session bucket.
        environment.pop("TRANSFER_BUCKET", None)
        if region in buckets:
            environment["TRANSFER_BUCKET"] = buckets[region]
    if len(regions) > 1:
        # The controller chose the region, so it alone reports the score it chose by.
        environment["CAMPAIGN_PLACEMENT_SCORE"] = job.placement_score
    environment.update({
        "AWS_REGION": region,
        "CAMPAIGN_PROVENANCE": "qualification",
        "CAMPAIGN_MODE": job.workload,
        "BASELINE_PROTOCOL": benchmark_protocol,
        "CAMPAIGN_ID": arguments.campaign_id,
        "CAMPAIGN_PLATFORM": job.platform.name,
        "CAMPAIGN_SHARD_ID": job.shard,
        "CAMPAIGN_REPLICA_ID": str(job.replica),
        "CAMPAIGN_HOST_EPOCH": str(job.host_epoch),
        "CAMPAIGN_ATTEMPT": str(job.attempt),
        "CAMPAIGN_ARCHITECTURES": architecture,
        "INSTANCE_MARKET_TYPE": job.market,
        "CAMPAIGN_SPOT_ONLY": "1" if purchase == "spot" else "0",
        "REGULATOR_RELEASE_VERSION": getattr(arguments, "release_version", ""),
        "BENCHMARK_CPU_LIST": "0",
        "BENCHMARK_EXPECTED_VCPUS": str(job.platform.vcpus),
        "TIMEOUT_SECONDS": str(unit_worker_timeout(getattr(arguments, "longest_units", {}).get(
            unit_worker_pool(job), 0))),
        "CAMPAIGN_HOST_STOP_BY": str(job.stop_by),
        "RESULT_ROOT": str(job_result_root(result_root, job)),
        "TRINO_REVISION": "c7503d170344c4e266f03fdb39e53c82aa7e3594",
        "JONI_COMPARATOR_ORDER": "forward" if job.replica % 2 else "reverse",
        "NATIVE_COMPILER_PACKAGE": job.platform.native_compiler_package,
        "CMAKE_PACKAGE": job.platform.cmake_package,
        "GLIBC_PACKAGE": job.platform.glibc_package,
        "CARGO_PACKAGE": job.platform.cargo_package,
        "RUST_PACKAGE": job.platform.rust_package,
        "TIME_PACKAGE": job.platform.time_package,
        "ZSTD_PACKAGE": job.platform.zstd_package,
        "BENCHMARK_JAVA_ARCHIVE": str(jdk_archive(arguments.campaign_root, job.platform).resolve()),
        "BASELINE_CANDIDATE_ARCHIVE": str(arguments.candidate_archive.resolve()),
        "BASELINE_CANDIDATE_ARCHIVE_SHA256": arguments.candidate_archive_sha256,
        "BASELINE_EXPECTED_ENGINE_TREE": campaign_engine_tree(arguments),
        "BENCHMARK_HEAP_SIZE": campaign_heap_size(arguments),
    })
    environment["UNIT_WORKER_LIFETIME_SECONDS"] = str(UNIT_WORKER_LIFETIME_SECONDS)
    environment["UNIT_WORKER_IDLE_SECONDS"] = str(UNIT_WORKER_IDLE_SECONDS)
    # Only a measuring phase's workers install engine builds; never pass the controller's own environment through.
    environment.pop("ENGINE_BUILD_ARCHIVES", None)
    environment.pop("ENGINE_BUILD_MANIFEST_SHA256", None)
    if getattr(arguments, "engine_build_directories", None):
        environment["ENGINE_BUILD_MANIFEST_SHA256"] = arguments.engine_build_policy[job.platform.name]
        environment["ENGINE_BUILD_ARCHIVES"] = " ".join(
            f"{name}={archive['sha256']}" for name, archive in engine_archives(arguments, job.platform.name).items())
    for name in ("LANGUAGE_PLAN_BUNDLE_SHA256S", "LANGUAGE_CANDIDATE_PROVENANCE", "LANGUAGE_CANDIDATE_PROVENANCE_SHA256"):
        environment.pop(name, None)
    bundles = getattr(arguments, "language_bundles", None)
    if bundles:
        # A language worker installs the bundles of its pool's plans and the candidate provenance their units measure.
        environment.update({
            "LANGUAGE_PLAN_BUNDLE_SHA256S": " ".join(
                bundles[shard][1] for shard in arguments.pool_shards[unit_worker_pool(job)]),
            "LANGUAGE_CANDIDATE_PROVENANCE": str(arguments.candidate_provenance.resolve()),
            "LANGUAGE_CANDIDATE_PROVENANCE_SHA256": hashlib.sha256(arguments.candidate_provenance.read_bytes()).hexdigest(),
        })
    if architecture == "intel":
        environment.update({
            "INTEL_INSTANCE_TYPE": job.platform.instance_type,
            "BENCHMARK_INTEL_AMI_ID": ami_id,
            "BENCHMARK_INTEL_AMI_NAME": getattr(arguments, "ami_names", {}).get(job.platform.name, ""),
            "BENCHMARK_INTEL_JAVA_ARCHIVE_URL": job.platform.jdk_url,
            "BENCHMARK_INTEL_JAVA_ARCHIVE_SHA256": job.platform.jdk_sha256,
        })
    else:
        environment.update({
            "ARM_INSTANCE_TYPE": job.platform.instance_type,
            "BENCHMARK_ARM_AMI_ID": ami_id,
            "BENCHMARK_ARM_AMI_NAME": getattr(arguments, "ami_names", {}).get(job.platform.name, ""),
            "BENCHMARK_ARM_JAVA_ARCHIVE_URL": job.platform.jdk_url,
            "BENCHMARK_ARM_JAVA_ARCHIVE_SHA256": job.platform.jdk_sha256,
        })
    return environment


def terminate_and_validate_cleanup(processes, result_root):
    records = list(processes.values())
    wait_failures = {}
    for process, _, _ in records:
        if process.poll() is None:
            try:
                process.send_signal(signal.SIGTERM)
            except OSError:
                # The wrapper may have exited between poll() and signal delivery.
                pass
    for process, job, log in records:
        try:
            process.wait(timeout=15 * 60)
        except subprocess.TimeoutExpired:
            process.kill()
            try:
                process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                wait_failures[job] = f"wrapper pid {process.pid} did not exit after SIGKILL"
            except OSError as error:
                wait_failures[job] = f"wrapper pid {process.pid} could not be reaped after SIGKILL: {error}"
        except OSError as error:
            wait_failures[job] = f"wrapper pid {process.pid} could not be reaped after SIGTERM: {error}"
        finally:
            log.close()

    cleanup_failures = []
    for process, job, _ in records:
        wait_failure = wait_failures.get(job)
        try:
            session, cleanup = validate_cleanup_manifest(result_root, job)
        except (OSError, RuntimeError) as error:
            detail = f"{wait_failure}; {error}" if wait_failure is not None else str(error)
            cleanup_failures.append(f"wrapper_pid={process.pid}; {detail}")
        else:
            if wait_failure is not None:
                cleanup_failures.append(
                    f"wrapper_pid={process.pid}; {wait_failure}; "
                    f"{cleanup_evidence(result_root, job, session, cleanup)}")
    if cleanup_failures:
        raise RuntimeError(
            "cleanup could not be proven for terminated wrappers: " + "; ".join(cleanup_failures))


def read_properties(path):
    values = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise RuntimeError(f"invalid property line in {path}: {line!r}")
        values[key] = value
    return values


def cleanup_evidence(result_root, job, session=None, cleanup=None):
    job_root = job_result_root(result_root, job)
    details = [f"job={job.identity}", f"result_root={job_root}"]
    if session is None:
        details.append("session=unavailable")
        details.append("resources=unavailable")
    else:
        details.append(f"session={session}")
        metadata = {}
        try:
            metadata = read_properties(session / "session.txt")
        except (OSError, RuntimeError):
            pass
        resources = [
            f"{field}={metadata[field]}"
            for field in ("bucket", "intel_instance_id", "arm_instance_id")
            if metadata.get(field) not in (None, "", "none", "None")
        ]
        details.append("resources=" + (",".join(resources) if resources else "unavailable"))
    if cleanup is not None:
        fields = (
            "instances_terminated",
            "iam_removed",
            "bucket_removed",
            "network_resources",
            "cleanup_status",
        )
        details.append("cleanup=" + ",".join(f"{field}={cleanup.get(field)!r}" for field in fields))
    return "; ".join(details)


def validate_cleanup_manifest(result_root, job):
    try:
        session = single_session(result_root, job)
    except (OSError, RuntimeError) as error:
        raise RuntimeError(f"{error}; {cleanup_evidence(result_root, job)}") from error
    manifest = session / "cleanup-manifest.txt"
    try:
        cleanup = read_properties(manifest)
    except (OSError, RuntimeError) as error:
        raise RuntimeError(
            f"{job.identity} cleanup manifest {manifest} is unavailable or invalid: {error}; "
            f"{cleanup_evidence(result_root, job, session)}") from error
    for field in ("instances_terminated", "iam_removed", "bucket_removed", "cleanup_status"):
        if cleanup.get(field) != "verified":
            raise RuntimeError(
                f"{job.identity} cleanup {field} is {cleanup.get(field)!r}; "
                f"{cleanup_evidence(result_root, job, session, cleanup)}")
    if cleanup.get("network_resources") != "default-vpc-reused":
        raise RuntimeError(
            f"{job.identity} has unexpected network cleanup state; "
            f"{cleanup_evidence(result_root, job, session, cleanup)}")
    return session, cleanup


def validate_capacity(path):
    values = read_properties(path)
    required = (
        "wall_seconds",
        "maximum_resident_kibibytes",
        "exit_status",
        "memory_total_bytes",
        "memory_available_before_bytes",
        "memory_available_after_bytes",
        "minimum_available_memory_bytes",
        "swap_total_bytes",
        "swap_free_before_bytes",
        "swap_free_after_bytes",
        "oom_kills_before",
        "oom_kills_after",
        "capacity_status",
    )
    missing = [field for field in required if field not in values]
    if missing:
        raise RuntimeError(f"capacity evidence is missing {missing}")
    try:
        wall_seconds = float(values["wall_seconds"])
        maximum_resident_kibibytes = int(values["maximum_resident_kibibytes"])
        memory_total_bytes = int(values["memory_total_bytes"])
        memory_available_before_bytes = int(values["memory_available_before_bytes"])
        memory_available_after_bytes = int(values["memory_available_after_bytes"])
        minimum_available_memory_bytes = int(values["minimum_available_memory_bytes"])
        swap_total_bytes = int(values["swap_total_bytes"])
        swap_free_before_bytes = int(values["swap_free_before_bytes"])
        swap_free_after_bytes = int(values["swap_free_after_bytes"])
        oom_kills_before = int(values["oom_kills_before"])
        oom_kills_after = int(values["oom_kills_after"])
        exit_status = int(values["exit_status"])
    except ValueError as error:
        raise RuntimeError("capacity evidence contains a non-numeric value") from error
    if not math.isfinite(wall_seconds) or wall_seconds <= 0:
        raise RuntimeError(f"invalid host-session wall time: {wall_seconds}")
    if maximum_resident_kibibytes <= 0:
        raise RuntimeError(f"invalid host-session peak RSS: {maximum_resident_kibibytes}")
    if memory_total_bytes <= 0:
        raise RuntimeError(f"invalid host memory total: {memory_total_bytes}")
    for name, value in (
            ("before", memory_available_before_bytes),
            ("after", memory_available_after_bytes)):
        if value <= 0 or value > memory_total_bytes:
            raise RuntimeError(f"invalid host available memory {name}: {value}")
    if maximum_resident_kibibytes * 1024 > memory_total_bytes:
        raise RuntimeError("host-session peak RSS exceeds total host memory")
    if minimum_available_memory_bytes <= 0 or memory_available_after_bytes < minimum_available_memory_bytes:
        raise RuntimeError("host-session did not preserve the required memory headroom")
    if not (0 <= swap_free_before_bytes <= swap_total_bytes and 0 <= swap_free_after_bytes <= swap_total_bytes):
        raise RuntimeError("host-session capacity evidence contains invalid swap values")
    if swap_free_before_bytes != swap_total_bytes or swap_free_after_bytes != swap_total_bytes:
        raise RuntimeError("host-session consumed swap")
    if oom_kills_before < 0 or oom_kills_after != oom_kills_before:
        raise RuntimeError("host-session observed an OOM kill")
    if exit_status != 0:
        raise RuntimeError(f"host-session capacity evidence reports exit status {exit_status}")
    disk_fields = ("disk_available_before_bytes", "disk_available_after_bytes", "minimum_available_disk_bytes")
    if any(field in values for field in disk_fields):
        try:
            before, after, minimum = (int(values[field]) for field in disk_fields)
        except (KeyError, ValueError) as error:
            raise RuntimeError("invalid disk capacity evidence") from error
        if minimum < 2 * 1024**3 or min(before, after) < minimum:
            raise RuntimeError("host-session did not preserve the required disk headroom")
    if values["capacity_status"] != "accepted":
        raise RuntimeError(f"host-session capacity status is {values['capacity_status']!r}")
    return values


def single_session(result_root, job):
    job_root = job_result_root(result_root, job)
    if not job_root.is_dir():
        raise RuntimeError(f"{job.identity} produced no result directory")
    sessions = [path for path in job_root.iterdir() if path.is_dir()]
    if len(sessions) != 1:
        raise RuntimeError(f"{job.identity} produced {len(sessions)} session directories")
    return sessions[0]


def boot_to_first_unit(session, job):
    """Seconds from a worker's boot to the start of its first unit, by the startup timeline its host uploaded, or
    None when the host uploaded none or never began a unit."""
    path = session / job.platform.architecture / "re2-results" / "startup-timeline.tsv"
    try:
        with path.open(newline="") as input_file:
            steps = {row["step"]: row for row in csv.DictReader(input_file, delimiter="\t")}
        return float(steps["worker"]["finished"]) - float(steps["boot"]["started"])
    except (OSError, KeyError, TypeError, ValueError):
        return None


def write_attempt(path, job, outcome, detail="-"):
    write_header = not path.exists()
    with path.open("a", newline="") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        if write_header:
            writer.writerow(("platform", "shard_id", "replica", "host_epoch", "attempt", "market",
                             "outcome", "detail"))
        writer.writerow((job.platform.name, job.shard, job.replica, job.host_epoch, job.attempt,
                         job.market, outcome, detail.replace("\t", " ").replace("\n", " ")))
        output_file.flush()
        os.fsync(output_file.fileno())


def write_event(path, event_type, campaign_id, job, session, reason, details="", unit_id=None, unit_attempt_id=""):
    """Append one campaign event; a unit campaign's events, given a unit_id, name the unit and attempt."""
    # A launch that failed before the host started leaves only the cleanup manifest.
    metadata = {}
    for name in ("cleanup-manifest.txt", "session.txt"):
        if (session / name).is_file():
            metadata.update(read_properties(session / name))
    label = job.platform.architecture
    instance_id = metadata.get(f"{label}_instance_id", "")
    availability_zone = metadata.get(f"{label}_availability_zone", "")
    failure_classification = metadata.get("failure_classification", "")
    fields = (
        "event_type", "campaign_id", "platform", "shard_id", "replica_id", "instance_id",
        "host_epoch", "reason", "artifact_uri", "artifact_sha256", "region", "availability_zone",
        "placement_score", "failure_classification", "details",
    )
    if unit_id is not None:
        index = fields.index("host_epoch") + 1
        fields = (*fields[:index], "unit_id", "unit_attempt_id", *fields[index:])
    write_header = not path.exists()
    if not write_header:
        # Resumed campaigns keep the event columns their file started with.
        with path.open(newline="") as input_file:
            fields = tuple(csv.DictReader(input_file, delimiter="\t").fieldnames or fields)
    with path.open("a", newline="") as output_file:
        writer = csv.DictWriter(
            output_file, fieldnames=fields, extrasaction="ignore", delimiter="\t", lineterminator="\n")
        if write_header:
            writer.writeheader()
        writer.writerow({
            "event_type": event_type,
            "campaign_id": campaign_id,
            "platform": job.platform.name,
            "shard_id": job.shard,
            "replica_id": str(job.replica),
            "instance_id": "" if instance_id in {"none", "None"} else instance_id,
            "host_epoch": str(job.host_epoch),
            "unit_id": unit_id or "",
            "unit_attempt_id": unit_attempt_id,
            "reason": reason,
            "artifact_uri": str(session),
            "artifact_sha256": "",
            "region": metadata.get("aws_region", ""),
            "availability_zone": "" if availability_zone in {"none", "None"} else availability_zone,
            "placement_score": metadata.get("placement_score", ""),
            "failure_classification": "" if failure_classification == "none" else failure_classification,
            "details": details.replace("\t", " ").replace("\n", " "),
        })
        output_file.flush()
        os.fsync(output_file.fileno())


def validate_unit_artifacts(validation, result_root, receipt, directory=None):
    """Revalidate a unit attempt from its evidence by its campaign's validation; the receipt must regenerate
    exactly and match the frozen campaign. The attempt is the retained one unless directory names another copy.
    """
    directory = directory or unit_attempt_directory(result_root, receipt)
    identity = f"{group_identity(receipt)} attempt {receipt['unit_attempt_id']}"
    try:
        if (directory / "status.txt").read_text() != "status=complete\n":
            raise RuntimeError("unit attempt did not complete")
        validate_capacity(directory / "unit-capacity.txt")
        artifact_receipt = read_unit_receipt(directory / "unit-receipt.tsv")
        regenerated = validation.validate_attempt(directory, receipt)
        if validation.host_fields:
            environment = read_properties(directory / "environment-manifest.txt")
            differing = [field for field, value in validation.host_fields.items() if environment.get(field) != value]
            if differing:
                raise RuntimeError(f"host differs from the campaign's comparator pins or release: {differing}")
        if validation.classes_archives:
            installed = read_properties(directory / "engine-build.txt").get("classes_archive_sha256")
            expected = validation.classes_archives.get(receipt["platform"])
            if installed != expected:
                raise RuntimeError(f"host installed classes archive {installed}, not its platform's accepted build's "
                                   f"{expected}")
    except (OSError, RuntimeError, ValueError, KeyError, TypeError) as error:
        raise UnsafeCampaignError(f"unit artifacts are not accepted for {identity}: {error}") from error
    if artifact_receipt != receipt or regenerated != receipt:
        raise UnsafeCampaignError(f"accepted unit receipt cannot be reproduced from its artifacts: {identity}")
    mismatches = [field for field, value in validation.receipt_fields.items() if receipt[field] != value]
    if mismatches:
        raise UnsafeCampaignError(f"accepted unit receipt does not match the frozen campaign for {identity}: "
                                  f"{mismatches}")


def recover_accepted_units(validation, result_root, accepted_units):
    """Revalidate accepted-units.tsv against retained attempts and add a completed group it lacks; returns each
    unit replica's accepted receipts by group.

    The first accepted attempt of a unit replica's group wins; a later completed one is superseded.
    """
    accepted = {}
    for receipt in read_accepted_units(accepted_units):
        validate_unit_artifacts(validation, result_root, receipt)
        accepted.setdefault(unit_logical_identity(receipt), {})[receipt["group_id"]] = receipt
    for receipt_path in sorted((result_root / "units").glob("*/*/replica-*/*/lease-*/group-*/unit-receipt.tsv")):
        try:
            receipt = read_unit_receipt(receipt_path)
        except (OSError, RuntimeError) as error:
            raise UnsafeCampaignError(f"invalid retained unit receipt {receipt_path}: {error}") from error
        if unit_attempt_directory(result_root, receipt) != receipt_path.parent:
            raise UnsafeCampaignError(f"unit receipt is not in its attempt's directory: {receipt_path}")
        groups = accepted.setdefault(unit_logical_identity(receipt), {})
        if receipt["group_id"] in groups:
            continue
        validate_unit_artifacts(validation, result_root, receipt)
        groups[receipt["group_id"]] = persist_accepted_unit(accepted_units, receipt_path)
    return accepted


def read_attempts(path):
    if not path.exists():
        return []
    with path.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        expected_fields = (
            "platform", "shard_id", "replica", "host_epoch", "attempt", "market", "outcome", "detail")
        if tuple(reader.fieldnames or ()) != expected_fields:
            raise UnsafeCampaignError(f"unexpected attempt schema in {path}")
        return list(reader)


def recover_budget_reservations(budget, arguments, result_root, jobs, attempts_path, live=frozenset()):
    """Release the reservations of attempts proven to have stopped, keeping those of live host epochs."""
    by_identity = {job.logical_identity: job for job in jobs}
    attempts = read_attempts(attempts_path)
    latest_by_epoch = {}
    started_epochs = set()
    for row in attempts:
        key = (row["platform"], row["shard_id"], int(row["replica"]), int(row["host_epoch"]))
        latest_by_epoch[key] = row
        if row["outcome"] == "started":
            started_epochs.add(key)
    for item in budget.reservations(result_root):
        if item["campaign_id"] != arguments.campaign_id or item["logical_identity"] not in by_identity:
            raise UnsafeCampaignError("shared fleet reservation identifies a different controller")
        if item["host_epoch"] in live:
            continue
        job = replace(by_identity[item["logical_identity"]], host_epoch=item["host_epoch"], attempt=item["attempt"])
        key = (job.platform.name, job.shard, job.replica, job.host_epoch)
        latest = latest_by_epoch.get(key)
        # A reservation whose attempt was still waiting for capacity was made just before a stop.
        stopped_before_launch = latest is None or (
            latest["outcome"] in ("retryable-infrastructure", "queued-capacity") and
            key not in started_epochs)
        if stopped_before_launch:
            # No attempt record, or the settled pre-Popen outcome, proves this
            # reservation could not have launched a wrapper.
            if latest is None:
                write_attempt(
                    attempts_path,
                    job,
                    "retryable-infrastructure",
                    "controller stopped before wrapper start")
        else:
            validate_cleanup_manifest(result_root, job)
        budget.release(arguments.campaign_id, job.host_epoch, cleanup_verified=True)


def wrapper_command(command, campaign_id, job):
    """Whether a process's command line is the job's wrapper in the campaign: run-campaign.sh with the campaign ID
    and the job's identity as its arguments, which start_wrapper gives it."""
    arguments = command.split()
    return (len(arguments) >= 3 and Path(arguments[-3]).name == "run-campaign.sh" and
            arguments[-2:] == [campaign_id, job.identity])


def wrapper_process_id(campaign_id, job, detail):
    marker = "wrapper_pid="
    if marker in detail:
        value = detail.split(marker, 1)[1].split(";", 1)[0].strip()
        try:
            process_id = int(value)
        except ValueError:
            return None
        try:
            command = check_output(["ps", "-p", str(process_id), "-o", "command="])
        except subprocess.CalledProcessError:
            return None
        return process_id if wrapper_command(command, campaign_id, job) else None
    processes = check_output(["ps", "ax", "-o", "pid=,command="])
    matches = []
    for line in processes.splitlines():
        process_id, separator, command = line.strip().partition(" ")
        if separator and wrapper_command(command, campaign_id, job):
            matches.append(int(process_id))
    if len(matches) > 1:
        raise UnsafeCampaignError(f"multiple detached wrappers match {job.identity}: {matches}")
    return matches[0] if matches else None


CAMPAIGN_STATE_FIELDS = ("schema_version", "campaign_id", "phase", "started_at_epoch_seconds", "heap_size")


def read_campaign_state(path):
    """A phase's campaign state: the one row its controller recorded when the phase first started."""
    with path.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != CAMPAIGN_STATE_FIELDS:
            raise UnsafeCampaignError(f"unexpected campaign-state schema in {path}")
        rows = list(reader)
    if len(rows) != 1:
        raise UnsafeCampaignError(f"campaign state must contain exactly one row: {path}")
    return rows[0]


def campaign_started_at(path, arguments):
    if path.exists():
        row = read_campaign_state(path)
        if row["schema_version"] != "2" or row["heap_size"] != campaign_heap_size(arguments):
            raise UnsafeCampaignError(f"campaign state has a different schema or heap_size: {path}")
        if row["campaign_id"] != arguments.campaign_id or row["phase"] != arguments.phase:
            raise UnsafeCampaignError(f"campaign state does not match this execution: {path}")
        try:
            return float(row["started_at_epoch_seconds"])
        except ValueError as error:
            raise UnsafeCampaignError(f"invalid campaign start time in {path}") from error

    started_at = time.time()
    temporary_path = path.with_name(path.name + ".new")
    with temporary_path.open("w", newline="") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=CAMPAIGN_STATE_FIELDS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerow({
            "schema_version": "2",
            "campaign_id": arguments.campaign_id,
            "phase": arguments.phase,
            "started_at_epoch_seconds": f"{started_at:.6f}",
            "heap_size": campaign_heap_size(arguments),
        })
        output_file.flush()
        os.fsync(output_file.fileno())
    os.replace(temporary_path, path)
    fsync_directory(path.parent)
    return started_at


def admit_launch(arguments, result_root, attempts_path, capacity_waits, candidate, ready_at, now, running_jobs,
                 spot_fallback=False):
    """Place a launch candidate and reserve its capacity.

    An unbudgeted Spot candidate that finds no Spot quota headroom launches On-Demand instead only with
    spot_fallback, once its pool has waited out a Spot shortage; otherwise it waits for Spot.

    Returns the candidate as placed, when it may next be tried, whether it was admitted, whether
    the launch scan may continue to later candidates, and the vCPU count a full fleet quota now
    blocks, if any.
    """
    budget = getattr(arguments, "shared_budget", None)
    purchase = purchase_policy(arguments, candidate.platform.name)
    placements = launch_placements(arguments, candidate)
    candidate = place(candidate, placements[0])
    # An unbudgeted Spot launch queries quota headroom only when its platform buys only Spot or above
    # the proactive threshold; otherwise it takes the first placement and learns of a full region from EC2.
    if candidate.market == "spot" and (
            purchase == "spot" or arguments.max_concurrent > PROACTIVE_SPOT_CAPACITY_THRESHOLD) and budget is None:
        placed, spot_capacities, error = place_with_headroom(
            candidate, placements,
            lambda region: current_spot_capacity(arguments, arguments.campaign_id, running_jobs, region))
        if placed is None and error is not None:
            wait_key = (candidate.identity, "spot-capacity-query")
            if wait_key not in capacity_waits:
                capacity_waits.add(wait_key)
                write_attempt(attempts_path, candidate, "queued-capacity", f"Spot capacity query failed: {error}")
            return candidate, now + CAPACITY_RETRY_SECONDS, False, True, None
        if placed is not None:
            candidate = placed
        elif purchase == "spot" or not spot_fallback:
            wait_key = (candidate.identity, "spot-capacity")
            if wait_key not in capacity_waits:
                capacity_waits.add(wait_key)
                write_attempt(attempts_path, candidate, "queued-capacity",
                              f"{purchase} purchase waiting for Spot quota; " + capacity_detail(spot_capacities))
            return candidate, now + CAPACITY_RETRY_SECONDS, False, True, None
        else:
            candidate = replace(candidate, market="on-demand")
            wait_key = (candidate.identity, "spot-to-on-demand")
            if wait_key not in capacity_waits:
                capacity_waits.add(wait_key)
                write_attempt(attempts_path, candidate, "queued-capacity",
                              "Spot capacity exhausted; " + capacity_detail(spot_capacities))
    if candidate.market == "on-demand" and budget is None:
        placements = launch_placements(arguments, candidate)
        placed, capacities, error = place_with_headroom(
            candidate, placements,
            lambda region: current_on_demand_capacity(arguments, arguments.campaign_id, running_jobs, region))
        if placed is None and error is not None:
            wait_key = (candidate.identity, "capacity-query")
            if wait_key not in capacity_waits:
                capacity_waits.add(wait_key)
                write_attempt(attempts_path, candidate, "queued-capacity", f"capacity query failed: {error}")
            return candidate, now + CAPACITY_RETRY_SECONDS, False, True, None
        if placed is None:
            wait_key = (candidate.identity, "capacity")
            if wait_key not in capacity_waits:
                capacity_waits.add(wait_key)
                write_attempt(attempts_path, candidate, "queued-capacity", capacity_detail(capacities))
            return candidate, now + CAPACITY_RETRY_SECONDS, False, True, None
        candidate = placed
    if budget:
        for placement in placements:
            try:
                admitted, reason = budget.reserve(
                    arguments.campaign_id, place(candidate, placement), result_root,
                    lambda region=placement[0], market=candidate.market:
                    shared_fleet_snapshot(arguments, region, market),
                    until=candidate.stop_by + UNIT_WORKER_TEARDOWN_SECONDS)
            except BudgetError:
                raise
            except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                admitted, reason = False, f"shared capacity query failed: {error}"
            if admitted:
                candidate = place(candidate, placement)
                break
            # Fleet-wide host, spending and launch-rate limits hold in every region.
            if reason in ("fleet launch limit", "fleet spending or machine-hour ceiling"):
                break
        if not admitted:
            if (candidate.identity, reason) not in capacity_waits:
                capacity_waits.add((candidate.identity, reason))
                write_attempt(attempts_path, candidate, "queued-capacity", reason)
            if reason != "fleet quota or pending-launch limit" or candidate.platform.vcpus == 2:
                return candidate, now + CAPACITY_RETRY_SECONDS, False, False, None
            return candidate, now + CAPACITY_RETRY_SECONDS, False, True, candidate.platform.vcpus
    return candidate, ready_at, True, False, None


class WrapperStartError(RuntimeError):
    """The wrapper process did not start, so no host was launched and its job may be retried."""


def start_wrapper(arguments, root, result_root, runner, attempts_path, job, slot=None, slot_failures=0):
    """Record and start the wrapper for an admitted job; the caller records it as started.

    A unit worker's starting record names its launch slot and the failures it inherited from the slot.
    Only a failed process start raises WrapperStartError; any other failure stops the campaign.
    """
    environment = job_environment(job, arguments, root, result_root)
    log_path = result_root / "controller-logs" / (job.identity.replace("/", "-") + ".log")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = log_path.open("w")
    try:
        write_attempt(
            attempts_path, job, "starting",
            f"region={environment['AWS_REGION']};"
            f"placement_score={environment.get('CAMPAIGN_PLACEMENT_SCORE', '')}" +
            (f";spot_capacity_failures={','.join(job.spot_capacity_failures)}"
             if job.spot_capacity_failures else "") +
            (f";slot={slot};slot_failures={slot_failures}" if slot is not None else "") +
            f";boot_by={job.boot_by};stop_by={job.stop_by}")
        try:
            # The wrapper reads only its environment; its arguments name it, so recovery finds this campaign's.
            process = subprocess.Popen(
                [str(runner), arguments.campaign_id, job.identity],
                cwd=root,
                env=environment,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True)
        except OSError as error:
            raise WrapperStartError(error) from error
    except BaseException:
        log.close()
        raise
    return process, log


def launch_record(row):
    """The placement, and a unit worker's launch slot, that start_wrapper records in a starting row's detail."""
    return dict(item.split("=", 1) for item in row["detail"].split(";") if "=" in item)


def halt_and_clean_up(arguments, result_root, budget, running, error):
    """Stop a controller that failed with error: terminate the running hosts, as process ID to (process, job, log),
    prove their cleanup, and release their reservations. A failure halts the fleet budget in the name of
    result_root, its owner, whose restart clears it once its cleanup is verified. A cancellation, Ctrl-C or SIGTERM,
    or FleetHalted, which another owner's halt raises, halts it only when cleanup cannot be proven for every host the
    owner holds a reservation for, so a halt stays its owner's alone to clear. The caller then re-raises error."""
    reason = str(error) or type(error).__name__

    def halt(reason):
        # Cleanup must still run if persisting the stop marker fails.
        try:
            budget.halt(reason, result_root)
        except (OSError, RuntimeError):
            pass

    cancelled = isinstance(error, (KeyboardInterrupt, FleetHalted))
    if budget and not cancelled:
        # Stop the other collectors' admissions and ask them to drain too.
        halt(reason)
    try:
        terminate_and_validate_cleanup(running, result_root)
        if budget:
            for _, job, _ in running.values():
                budget.release(arguments.campaign_id, job.host_epoch, cleanup_verified=True)
            # A host the controller had not adopted yet, such as one an earlier run left, still awaits cleanup.
            if cancelled and budget.reservations(result_root):
                halt(f"{reason}; hosts of {result_root} await verified cleanup")
    except (OSError, RuntimeError) as cleanup_error:
        if budget and cancelled:
            halt(f"{reason}; {cleanup_error}")
        raise UnsafeCampaignError(f"{reason}; {cleanup_error}") from error


def interrupt(signal_number, frame):
    raise KeyboardInterrupt


def process_start(process_id):
    """When the process process_id names started, as ps reports it, or None while no process has that ID. With the
    process ID, it identifies the process, as a later one given the same ID starts later. It is read in one time zone
    and locale, so processes with different environments read the same value."""
    try:
        return check_output(["ps", "-p", str(process_id), "-o", "lstart="],
                            env={**os.environ, "TZ": "UTC", "LC_ALL": "C"}) or None
    except subprocess.CalledProcessError:
        return None


@contextlib.contextmanager
def controller_lock(result_root, record):
    """Own result_root as its only controller, and once it does, record this process's ID and start time in record
    there. While another controller owns it, exit with CONTROLLER_BUSY_STATUS. While it does, SIGTERM stops it as
    Ctrl-C does: its workers are cleaned up, and the shared fleet keeps running unless their cleanup cannot be
    proven or their release cannot be recorded."""
    result_root.mkdir(parents=True, exist_ok=True)
    lock_path = result_root / CONTROLLER_LOCK
    with lock_path.open("w") as lock_file:
        try:
            fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(f"another controller owns campaign result root {result_root}", file=sys.stderr, flush=True)
            raise SystemExit(CONTROLLER_BUSY_STATUS) from None
        temporary = result_root / (record + ".new")
        temporary.write_text(f"pid={os.getpid()}\nstarted={process_start(os.getpid())}\n")
        os.replace(temporary, result_root / record)
        previous = signal.signal(signal.SIGTERM, interrupt)
        try:
            yield
        finally:
            signal.signal(signal.SIGTERM, previous)


def read_unit_workers(path, platforms):
    """The phase's planned unit worker hosts, read from unit-workers.tsv."""
    if not path.exists():
        return []
    by_name = {platform.name: platform for platform in platforms}
    with path.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != JOB_MANIFEST_FIELDS:
            raise UnsafeCampaignError(f"unexpected unit worker schema in {path}")
        rows = list(reader)
    jobs = []
    for row in rows:
        platform = by_name.get(row["platform"])
        if platform is None or row["shard_id"] != UNIT_WORKER_SHARD:
            raise UnsafeCampaignError(f"unit worker manifest names an unexpected worker: {row}")
        try:
            platform = pool_platform(platform, row["instance_type"])
        except ValueError as error:
            raise UnsafeCampaignError(f"unit worker manifest names an unexpected instance type: {row}") from error
        jobs.append(unit_worker_job(platform, int(row["replica"]), int(row["host_epoch"]), row["market"]))
    return jobs


class DetachedWrapper:
    """A worker wrapper an earlier controller started, followed through its process ID and session."""

    def __init__(self, process_id, campaign_id, job, detail, result_root):
        self.pid = process_id
        self.campaign_id = campaign_id
        self.job = job
        self.detail = detail
        self.result_root = result_root

    def poll(self):
        if wrapper_process_id(self.campaign_id, self.job, self.detail) is not None:
            return None
        try:
            session = single_session(self.result_root, self.job)
        except (OSError, RuntimeError):
            return 1
        return 0 if (session / "campaign-success").is_file() else 1

    def send_signal(self, sent_signal):
        try:
            os.kill(self.pid, sent_signal)
        except ProcessLookupError:
            pass

    def wait(self, timeout):
        deadline = time.monotonic() + timeout
        while (status := self.poll()) is None:
            if time.monotonic() >= deadline:
                raise subprocess.TimeoutExpired(f"wrapper {self.pid}", timeout)
            time.sleep(5)
        return status

    def kill(self):
        self.send_signal(signal.SIGKILL)


@dataclass
class UnitWorker:
    """A unit worker host: its wrapper, its session, and the leases it was given."""
    job: Job
    process: object
    log: object
    session: Path = None
    metadata: dict = None
    # Each lease's work item identity, or None for a stop lease, and each work lease's groups in run order.
    leases: dict = field(default_factory=dict)
    lease_groups: dict = field(default_factory=dict)
    # Each lease's groups whose completion markers this controller processed, in run order, each with None when
    # this lease's attempt was accepted for it, or why it was superseded.
    finished: dict = field(default_factory=dict)
    # Leases with a final outcome, and leases requeued, lost with their host or released before they
    # started, whose completion may still land.
    settled: set = field(default_factory=set)
    abandoned: set = field(default_factory=set)
    unsent: set = field(default_factory=set)
    units: set = field(default_factory=set)
    started: bool = False
    stopping: bool = False
    lost: bool = False
    # The launch slot this worker took over, as the host epoch of its first worker, and the slot's
    # consecutive failures before a start.
    slot: int = None
    slot_failures: int = 0
    # When this controller launched or adopted the worker, and when the lease it runs began: when it was
    # leased, the lease before it settled, or the worker started, whichever came last.
    launched_at: float = 0.0
    running_since: float = 0.0
    # The host's stop time, the latest of its admission's and its work leases', and each work lease's, in epoch
    # seconds.
    stop_by: int = 0
    lease_stop_by: dict = field(default_factory=dict)

    @property
    def pool(self):
        return unit_worker_pool(self.job)

    @property
    def host_session(self):
        return self.metadata["session"]

    @property
    def instance_id(self):
        value = (self.metadata or {}).get(f"{self.job.platform.architecture}_instance_id", "")
        return "" if value in ("none", "None") else value

    def outstanding(self):
        done = self.settled | self.abandoned
        return sorted(sequence for sequence in self.leases if sequence not in done)

    def open(self):
        return not (self.stopping or self.lost)


class UnitDispatcher:
    """One phase's unit queue: the work items that remain, each worker's leases, and what each became.

    Workers are hosts the campaign wrapper launches and cleans up, each within the fleet budget.
    unit-workers.tsv freezes each worker before its launch, and unit-attempts.tsv records
    each lease before it is written and each outcome it reaches, so a restart rebuilds this state
    from the ledgers, the live workers' uploads, and the uploads recovered while cleaning up exited workers.
    """

    def __init__(self, arguments, root, result_root, items, platforms, validation):
        self.arguments = arguments
        self.root = root
        self.validation = validation
        self.result_root = result_root
        self.items = {item.identity: item for item in items}
        self.pools = sorted({item.pool for item in items})
        self.platforms = pool_platforms(platforms, items)
        self.budget = getattr(arguments, "shared_budget", None)
        self.purchases = {pool: purchase_policy(arguments, platform.name) for pool, platform in self.platforms.items()}
        self.attempts_path = result_root / "job-attempts.tsv"
        self.leases_path = result_root / "unit-attempts.tsv"
        self.workers_path = result_root / "unit-workers.tsv"
        self.events_path = result_root / "campaign-events.tsv"
        self.accepted_path = result_root / "accepted-units.tsv"
        self.rejections_path = result_root / unit_session.SEMANTIC_REJECTIONS
        self.items_path = result_root / "work-items.tsv"
        self.runner = root / "tools/re2-benchmark/aws/run-campaign.sh"
        # The frozen inputs' fingerprint, taken when the phase runs; each launch checks it.
        self.fingerprint = None
        self.uploads_path = result_root / UNIT_UPLOADS
        # Each work item's accepted receipts, by group.
        self.accepted = {}
        # Each leased item's live lease, as its worker's host epoch and lease sequence.
        self.held = {}
        self.failures = {}
        # Items a Spot interruption stopped before any group of the lease was accepted.
        self.interrupted = set()
        self.unresolved = {}
        # Each item an attempt found semantically wrong, by that attempt, as semantic-rejections.tsv lists it;
        # no other attempt clears it.
        self.semantic = {}
        self.tail = set()
        self.workers = {}
        self.planned = read_unit_workers(self.workers_path, platforms)
        self.next_ordinal = max((job.replica for job in self.planned), default=0) + 1
        self.next_epoch = max([PHASE_HOST_EPOCH_START[arguments.phase] - 1] +
                              [job.host_epoch for job in self.planned]) + 1
        self.candidates = {}
        self.pool_regions = {}
        # When each pool's Spot launches first found no capacity since one of its Spot workers last started.
        self.spot_shortages = {}
        # Each pool's free launch slots, as (slot, consecutive failures), and the pools a slot gave up on.
        self.free_slots = {}
        self.exhausted = {}
        # Pools whose wrapper refused its inputs, where every launch would be refused the same way.
        self.refused = set()
        self.pool_ready = {}
        self.on_demand_ready = {}
        # Each pool's On-Demand workers admitted in its tail, and the Spot workers they retired.
        self.tail_on_demand = {}
        self.retired = {}
        # Each lease, as its worker's host epoch and sequence, that spent one of its item's attempts.
        self.counted = set()
        self.capacity_waits = set()
        self.terminated = set()

    def remaining(self, item):
        """The item's groups not yet accepted, in its run order, each as (group ID, estimated seconds)."""
        accepted = self.accepted.get(item.identity, {})
        return [(group, seconds) for group, seconds in item.groups if group not in accepted]

    def remaining_seconds(self, item):
        return sum(seconds for _, seconds in self.remaining(item))

    def done(self, identity):
        return not self.remaining(self.items[identity])

    def queued(self, pool=None):
        return [item for identity, item in self.items.items()
                if (pool is None or item.pool == pool) and identity not in self.unresolved and identity not in self.held
                and not self.done(identity)]

    def on_demand_only(self, item):
        """Whether only On-Demand hosts may take the item, unless its pool's platform buys only Spot: a Spot
        interruption stopped it before any group of the lease was accepted, or its longest remaining group outlasts
        the 15-minute budget, which would risk more Spot interruptions than it can absorb."""
        return self.purchases[item.pool] != "spot" and (item.identity in self.interrupted or max(
            (seconds for _, seconds in self.remaining(item)), default=0) > work_units.UNIT_SECONDS)

    def on_demand(self, item):
        purchase = self.purchases[item.pool]
        return purchase == "on-demand" or self.on_demand_only(item) or (purchase != "spot" and item.pool in self.tail)

    def pool_workers(self, pool):
        return [worker for worker in self.workers.values() if worker.pool == pool]

    def gave_up(self, pool):
        """Whether the pool opens no new worker: its wrapper refused its inputs, or it gave up with no free slot."""
        return pool in self.exhausted and (pool in self.refused or not self.free_slots.get(pool))

    def worker_session(self, worker):
        if worker.session is None:
            try:
                worker.session = single_session(self.result_root, worker.job)
            except (OSError, RuntimeError):
                return False
        if worker.metadata is None:
            try:
                metadata = read_properties(worker.session / "session.txt")
            except (OSError, RuntimeError):
                return False
            # The wrapper renames session.txt into place; a read that lacks the session or its result
            # prefix waits for the next poll.
            if not metadata.get("session") or not metadata.get("result_prefix"):
                return False
            worker.metadata = metadata
        return True

    def recover(self):
        """Adopt the live workers an earlier controller started, and settle the ones that exited.

        Each launch slot is rebuilt from its latest worker's starting record: a live worker holds it, and
        one that exited leaves it free with its count, or given up.
        """
        latest = {}
        placements = {}
        for row in read_attempts(self.attempts_path):
            if row["shard_id"] == UNIT_WORKER_SHARD:
                latest[int(row["host_epoch"])] = row
                if row["outcome"] == "starting":
                    placements[int(row["host_epoch"])] = launch_record(row)
        slots = {}
        for epoch, placement in placements.items():
            placement["slot"] = int(placement["slot"])
            placement["slot_failures"] = int(placement["slot_failures"])
            slots[placement["slot"]] = max(slots.get(placement["slot"], epoch), epoch)
        workers = {}
        exited = []
        for job in self.planned:
            row = latest.get(job.host_epoch)
            placement = placements.get(job.host_epoch)
            if row is None or placement is None:
                continue
            slot, slot_failures = placement["slot"], placement["slot_failures"]
            if row["outcome"] not in ("starting", "started"):
                if slots[slot] == job.host_epoch:
                    # The earlier controller settled this worker; its slot is as that settlement left it.
                    self.release_slot(job, slot, slot_failures, self.recovered_start(job),
                                      0 if row["outcome"] == "stopped" else 1, row["outcome"], row["detail"])
                continue
            # The starting record names the region the worker was placed in.
            job = replace(job, market=row["market"], region=placement.get("region", ""),
                          placement_score=placement.get("placement_score", ""),
                          boot_by=int(placement["boot_by"]), stop_by=int(placement["stop_by"]))
            process_id = None
            for probe in range(2):
                try:
                    session = single_session(self.result_root, job)
                except (OSError, RuntimeError):
                    session = None
                if session is not None and (session / "cleanup-manifest.txt").is_file():
                    break
                process_id = wrapper_process_id(self.arguments.campaign_id, job, row["detail"])
                if process_id is not None:
                    break
                if probe == 0:
                    time.sleep(2)
            if process_id is not None:
                log = (self.result_root / "controller-logs" / (job.identity.replace("/", "-") + ".log")).open("a")
                # A live worker is registered at once, so a recovery step that fails still stops it.
                wrapper = DetachedWrapper(process_id, self.arguments.campaign_id, job, row["detail"], self.result_root)
                workers[job.host_epoch] = self.workers[job.host_epoch] = UnitWorker(
                    job, wrapper, log, slot=slot, slot_failures=slot_failures, launched_at=time.monotonic(),
                    stop_by=job.stop_by)
            elif session is not None and (session / "cleanup-manifest.txt").is_file():
                workers[job.host_epoch] = UnitWorker(job, None, None, session=session,
                                                     slot=slot, slot_failures=slot_failures)
                exited.append(workers[job.host_epoch])
            elif session is None and row["outcome"] == "starting":
                write_attempt(self.attempts_path, job, "retryable-infrastructure", WRAPPER_NOT_CREATED)
                self.release_slot(job, slot, slot_failures, False, 1, "retryable-infrastructure",
                                  WRAPPER_NOT_CREATED)
            else:
                raise UnsafeCampaignError(f"detached unit worker {job.identity} exited without cleanup evidence")
        if self.budget:
            live = {epoch for epoch, worker in workers.items() if worker.process is not None}
            recover_budget_reservations(self.budget, self.arguments, self.result_root, self.planned,
                                        self.attempts_path, live)
            # A halt this controller's failure set clears once no host of its own awaits verified cleanup.
            self.budget.resume(self.result_root)
        self.accepted = recover_accepted_units(self.validation, self.result_root, self.accepted_path)
        for row in read_semantic_rejections(self.rejections_path):
            identity = unit_logical_identity(row)
            if identity not in self.items:
                raise UnsafeCampaignError(f"semantic-rejection ledger names an unexpected work item: {identity}")
            self.semantic.setdefault(identity, row["unit_attempt_id"])
        outcomes = {}
        for row in read_unit_attempts(self.leases_path):
            epoch, sequence = int(row["host_epoch"]), int(row["sequence"])
            identity = None if row["unit_id"] == "-" else f"{row['platform']}/{row['unit_id']}/replica-{row['replica']}"
            if identity is not None and identity not in self.items:
                raise UnsafeCampaignError(f"unit attempt ledger names an unexpected work item: {identity}")
            outcomes.setdefault((epoch, sequence), (identity, set()))[1].add(row["outcome"])
            if row["outcome"] == "rejected":
                self.unresolved[identity] = row["detail"]
            if row["outcome"] == "interrupted":
                self.interrupted.add(identity)
            worker = workers.get(epoch)
            if worker is None:
                continue
            self.worker_session(worker)
            if row["outcome"] in ("leased", "stop"):
                worker.leases[sequence] = identity
                worker.unsent.add(sequence)
                if identity is not None:
                    worker.lease_groups[sequence] = tuple(row["groups"].split(","))
                    worker.units.add(self.items[identity].unit_id)
                    worker.lease_stop_by[sequence] = int(launch_record(row)["stop_by"])
                    worker.stop_by = max(worker.stop_by, worker.lease_stop_by[sequence])
            if row["outcome"] == "stop":
                worker.stopping = True
            if row["outcome"] in SETTLED_UNIT_OUTCOMES:
                worker.settled.add(sequence)
            elif row["outcome"] in ("lost", "released"):
                # A live worker's lease is abandoned only when its host was judged lost.
                worker.abandoned.add(sequence)
                worker.lost = True
        for lease, (identity, seen) in outcomes.items():
            if identity is not None and seen & COUNTED_UNIT_OUTCOMES:
                self.count(self.items[identity], "earlier attempts", lease)
        for identity in self.accepted:
            if identity in self.items and self.done(identity):
                self.unresolved.pop(identity, None)
        for epoch, worker in self.workers.items():
            for sequence in worker.outstanding():
                if worker.leases[sequence] is not None:
                    self.held[worker.leases[sequence]] = (epoch, sequence)
        for worker in exited:
            self.finish(worker, 0 if (worker.session / "campaign-success").is_file() else 1)

    def recovered_start(self, job):
        """Whether a settled worker's recovered uploads show it started, as its settlement judged."""
        try:
            objects = single_session(self.result_root, job) / "recovered-uploads" / "objects"
        except (OSError, RuntimeError):
            return False
        return shows_worker_start(recovered_keys(objects))

    def count(self, item, detail, lease):
        """Spend one of the item's attempts, once per lease; an item that spent them all stays unresolved."""
        if lease in self.counted:
            return
        self.counted.add(lease)
        self.failures[item.identity] = self.failures.get(item.identity, 0) + 1
        if self.failures[item.identity] >= MAX_UNIT_ATTEMPTS and not self.done(item.identity):
            self.unresolved[item.identity] = f"{detail}; spent its {MAX_UNIT_ATTEMPTS} attempts"

    def settle(self, worker, sequence, outcome, detail="-"):
        identity = worker.leases[sequence]
        write_unit_attempt(self.leases_path, worker, sequence, self.items[identity], outcome, detail,
                           worker.finished.get(sequence, {}))
        worker.settled.add(sequence)
        # The worker goes on to its next lease.
        worker.running_since = time.monotonic()
        if self.held.get(identity) == (worker.job.host_epoch, sequence):
            del self.held[identity]

    def abandon(self, worker, sequence, outcome, detail):
        """Requeue a lease whose host was lost or exited; a completion that still lands is processed."""
        identity = worker.leases[sequence]
        write_unit_attempt(self.leases_path, worker, sequence, self.items[identity], outcome, detail,
                           worker.finished.get(sequence, {}))
        worker.abandoned.add(sequence)
        if self.held.get(identity) == (worker.job.host_epoch, sequence):
            del self.held[identity]
        if outcome == "lost":
            self.count(self.items[identity], detail, (worker.job.host_epoch, sequence))

    def event_job(self, worker, item):
        return replace(worker.job, shard=item.shard, replica=item.replica)

    def event_unit(self, worker, sequence):
        """The unit_id and unit_attempt_id keywords of a lease's events; a stop lease names no unit."""
        identity = worker.leases.get(sequence)
        if identity is None:
            return {"unit_id": ""}
        return {"unit_id": self.items[identity].unit_id,
                "unit_attempt_id": unit_session.format_unit_attempt(worker.host_session, sequence)}

    def reject(self, worker, sequence, detail):
        """Leave the item unresolved without retrying it, and let other work go on."""
        item = self.items[worker.leases[sequence]]
        self.settle(worker, sequence, "rejected", detail)
        if not self.done(item.identity):
            self.unresolved[item.identity] = detail
        write_event(self.events_path, "rejected-session", self.arguments.campaign_id, self.event_job(worker, item),
                    worker.session, "unit-rejected", detail, **self.event_unit(worker, sequence))

    def interrupt(self, worker, sequence):
        """Settle a lease a Spot interruption stopped. Once a group of the lease was accepted, the item keeps
        it and requeues its remaining groups on any market, without spending an attempt; with none, the whole
        item spends one and runs On-Demand, unless its pool's platform buys only Spot."""
        item = self.items[worker.leases[sequence]]
        worker.stopping = True
        if None in worker.finished.get(sequence, {}).values():
            self.settle(worker, sequence, "interrupted-progress", "spot-interruption-notice")
        else:
            self.settle(worker, sequence, "interrupted", "spot-interruption-notice")
            self.interrupted.add(item.identity)
            self.count(item, "Spot interruption", (worker.job.host_epoch, sequence))
        write_event(self.events_path, "interruption", self.arguments.campaign_id, self.event_job(worker, item),
                    worker.session, "spot-interruption-notice",
                    f"replacement_market={'on-demand' if self.on_demand_only(item) else 'any'}",
                    **self.event_unit(worker, sequence))

    def complete_groups(self, worker, sequence, keys, fetch):
        """Process the completion markers of the lease's groups in run order; returns whether the lease settled.

        The lease is done once its last group's marker is processed, or once a marker ends it: a group that
        failed, or failed its checks, settles the lease, and a semantic failure rejects the unit.
        """
        finished = worker.finished.setdefault(sequence, {})
        for group in worker.lease_groups[sequence]:
            if group in finished:
                continue
            if completion_key(sequence, group) not in keys or not self.complete(worker, sequence, group, fetch):
                return sequence in worker.settled
        superseded = "; ".join(f"group {group}: {detail}" for group, detail in finished.items() if detail)
        if None in finished.values():
            self.settle(worker, sequence, "accepted", superseded or "-")
        else:
            self.settle(worker, sequence, "superseded", superseded)
        return True

    def complete(self, worker, sequence, group, fetch):
        """Accept or supersede one group of a lease from its completion marker and result, or settle the lease;
        returns whether the group finished and the lease goes on."""
        item = self.items[worker.leases[sequence]]
        attempt = unit_session.format_unit_attempt(worker.host_session, sequence)
        context = f"{attempt} group {group}"
        staging = self.result_root / "unit-results" / worker.host_session
        marker_path = staging / f"lease-{sequence}-group-{group}-completion.json"
        fetch(completion_key(sequence, group), marker_path)
        try:
            marker = json.loads(marker_path.read_text())
        except ValueError as error:
            raise UnsafeCampaignError(f"unit completion marker {context} is not JSON") from error
        absent = [key for key in UNIT_COMPLETION_FIELDS if key not in marker]
        if absent:
            raise UnsafeCampaignError(f"unit completion marker {context} lacks {absent}")
        expected = {
            "schema_version": 2, "host_session": worker.host_session, "sequence": sequence,
            "campaign_id": self.arguments.campaign_id, "phase": self.arguments.phase, "platform": item.platform,
            "unit_id": item.unit_id, "replica_id": item.replica, "group_id": group, "unit_attempt_id": attempt,
        }
        mismatches = [key for key, value in expected.items() if marker.get(key) != value]
        if mismatches:
            raise UnsafeCampaignError(f"unit completion marker {context} does not match its lease: {mismatches}")
        persist_unit_upload(self.uploads_path, item, worker.host_session, sequence, marker)
        finished = worker.finished[sequence]
        if marker.get("exit_status") == SEMANTIC_FAILURE_STATUS:
            # A verdict that the semantics are wrong rejects the unit for good, before or after another attempt.
            # Its ledger row is durable before the lease's outcome is.
            persist_semantic_rejection(self.rejections_path, item, attempt)
            self.semantic.setdefault(item.identity, attempt)
            self.reject(worker, sequence, SEMANTIC_FAILURE_DETAIL)
            return False
        # A group already settled needs none of this lease's evidence.
        accepted = self.accepted.get(item.identity, {}).get(group)
        if accepted is not None:
            # Another attempt was accepted first, or this one was and the controller stopped before recording the
            # lease's outcome.
            finished[group] = (None if accepted["unit_attempt_id"] == attempt else
                               f"{accepted['unit_attempt_id']} was accepted first")
            return True
        if item.identity in self.semantic:
            finished[group] = f"{self.semantic[item.identity]} was rejected for a semantic failure"
            return True
        archive = staging / f"lease-{sequence}-group-{group}.tar.gz"
        fetch(result_key(sequence, group), archive)
        if engine_build.sha256_file(archive) != marker.get("result_sha256"):
            self.reject(worker, sequence, "result archive does not match its completion marker")
            return False
        directory = staging / f"lease-{sequence}-group-{group}"
        try:
            extract_unit_result(archive, directory)
        except (OSError, tarfile.TarError) as error:
            self.reject(worker, sequence, f"result archive is unreadable: {error}")
            return False
        if marker.get("unit_status") != "complete" or marker.get("exit_status") != 0:
            if marker.get("exit_status") == PROTOCOL_REJECTION_STATUS:
                self.reject(worker, sequence, f"numerical protocol rejection: status {PROTOCOL_REJECTION_STATUS}")
                return False
            try:
                capacity = read_properties(directory / "unit-capacity.txt")
            except (OSError, RuntimeError):
                capacity = {}
            if capacity.get("capacity_status") == "rejected":
                self.reject(worker, sequence, "unit capacity gate rejected the host's resources")
                return False
            detail = f"unit failed with status {marker.get('exit_status')}"
            self.settle(worker, sequence, "failed", detail)
            self.count(item, detail, (worker.job.host_epoch, sequence))
            return False
        try:
            receipt = read_unit_receipt(directory / "unit-receipt.tsv")
            if (unit_logical_identity(receipt), receipt["group_id"], receipt["unit_attempt_id"]) != (
                    item.identity, group, attempt):
                raise RuntimeError(f"unit receipt names {group_identity(receipt)} attempt "
                                   f"{receipt['unit_attempt_id']}, expected {item.identity}/group-{group} attempt "
                                   f"{attempt}")
            # The host's receipt names the region and market it ran in, which must be those the worker launched in.
            launched = (worker.metadata.get("aws_region"), worker.job.market)
            if (receipt["region"], receipt["market"]) != launched:
                raise RuntimeError(f"unit receipt names a {receipt['market']} host in {receipt['region']}, but the "
                                   f"worker launched as {launched[1]} in {launched[0]}")
            validate_unit_artifacts(self.validation, self.result_root, receipt, directory)
        except (OSError, RuntimeError) as error:
            self.reject(worker, sequence, f"artifact gate: {error}")
            return False
        final = unit_attempt_directory(self.result_root, receipt)
        retain_unit_attempt(directory, final)
        self.accepted.setdefault(item.identity, {})[group] = persist_accepted_unit(
            self.accepted_path, final / "unit-receipt.tsv")
        if self.done(item.identity):
            self.unresolved.pop(item.identity, None)
        finished[group] = None
        return True

    def lease_started(self, worker, sequence, keys, fetch):
        """Whether the host began the lease: a group of it finished, or its heartbeats show it began."""
        if worker.finished.get(sequence):
            return True
        beats = {heartbeat_sequence(key) for key in keys} - {None}
        if any(beat > sequence for beat in beats):
            return True
        if sequence not in beats:
            return False
        path = self.result_root / "unit-results" / worker.host_session / f"heartbeat-{sequence}.json"
        fetch(heartbeat_key(sequence), path)
        return json.loads(path.read_text()).get("unit_id") is not None

    def interruption_names_unit(self, worker, sequence, fetch):
        """Whether the lease's interruption bundle names a unit; a notice between units names none."""
        path = self.result_root / "unit-results" / worker.host_session / f"lease-{sequence}-interrupted.tar.gz"
        fetch(interruption_key(sequence), path)
        try:
            with tarfile.open(path, "r:gz") as bundle:
                notice = json.load(bundle.extractfile("interruption.json"))
        except (OSError, KeyError, AttributeError, ValueError, tarfile.TarError):
            # An unreadable bundle is charged, as an interruption of the leased unit.
            return True
        return notice.get("unit_id") is not None

    def reconcile(self, worker, keys, fetch, classification=None):
        """Settle the worker's leases from its uploads; classification is that of an exited worker."""
        for sequence in sorted(worker.leases):
            if sequence in worker.settled or worker.leases[sequence] is None:
                continue
            if self.complete_groups(worker, sequence, keys, fetch):
                continue
            if interruption_key(sequence) in keys:
                if self.interruption_names_unit(worker, sequence, fetch):
                    self.interrupt(worker, sequence)
                elif sequence not in worker.abandoned:
                    self.abandon(worker, sequence, "released", "Spot notice came before the lease started")
            elif classification is not None and sequence not in worker.abandoned:
                if not self.lease_started(worker, sequence, keys, fetch):
                    self.abandon(worker, sequence, "released", "worker stopped before the lease started")
                elif classification == "retryable-spot":
                    # Status 43, or a reclaim that left no interruption record.
                    self.interrupt(worker, sequence)
                else:
                    self.abandon(worker, sequence, "lost", f"worker exited: classification={classification}")

    def poll(self, worker):
        """Process a live worker's uploads and decide whether its host was lost."""
        if not self.worker_session(worker):
            return
        try:
            objects = list_unit_worker_results(self.arguments, worker)
            keys = set(objects)
            # Worker evidence shows the worker started, even one whose heartbeats never landed; its first lease
            # runs from then.
            if not worker.started and shows_worker_start(keys):
                worker.started, worker.running_since = True, time.monotonic()
                if worker.job.market == "spot":
                    # Spot capacity has returned for the pool.
                    self.spot_shortages.pop(worker.pool, None)

            def fetch(key, path):
                get_unit_worker_result(self.arguments, worker, key, path)

            self.reconcile(worker, keys, fetch)
            beats = {beat: modified for key, modified in objects.items()
                     if (beat := heartbeat_sequence(key)) is not None}
            if worker.lost or not beats or not worker.outstanding():
                return
            age = time.time() - beats[max(beats)]
            if age < UNIT_HEARTBEAT_STALE_SECONDS or (
                    age < UNIT_HEARTBEAT_LOST_SECONDS and
                    unit_worker_instance_state(self.arguments, worker) in ("pending", "running", "unknown")):
                return
            worker.lost = True
            for sequence in worker.outstanding():
                if worker.leases[sequence] is None:
                    continue
                started = self.lease_started(worker, sequence, keys, fetch)
                self.abandon(worker, sequence, "lost" if started else "released", f"heartbeat {age:.0f}s old")
        except (OSError, ValueError, subprocess.SubprocessError):
            # S3 may be unavailable for a moment; the next poll, or cleanup's recovered uploads, retries.
            return

    def finish(self, worker, status):
        """Settle an exited worker from its verified cleanup and the uploads cleanup recovered."""
        try:
            session, cleanup = validate_cleanup_manifest(self.result_root, worker.job)
        except (OSError, RuntimeError) as error:
            raise UnsafeCampaignError(
                f"cleanup could not be proven for unit worker {worker.job.identity}: {error}") from error
        validate_frozen_inputs(self.root, self.arguments, self.items_path, self.fingerprint)
        if worker.log is not None:
            worker.log.close()
        if self.budget:
            self.budget.release(self.arguments.campaign_id, worker.job.host_epoch, cleanup_verified=True)
        self.workers.pop(worker.job.host_epoch, None)
        classification = cleanup.get("failure_classification", "none")
        detail = cleanup.get("failure_detail", "unknown")
        objects = session / "recovered-uploads" / "objects"
        keys = recovered_keys(objects)
        if self.worker_session(worker):
            def fetch(key, path):
                path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(objects / key, path)

            self.reconcile(worker, keys, fetch, classification if status != 0 else "none")
        worker.started |= shows_worker_start(keys)
        # The attempt's record reports how long its host took from boot to its first unit.
        startup = boot_to_first_unit(session, worker.job)
        details = [] if status == 0 else [detail]
        if startup is not None:
            details.append(f"boot_to_first_unit_seconds={startup:.1f}")
        write_attempt(self.attempts_path, worker.job, "stopped" if status == 0 else classification,
                      "; ".join(details) or "-")
        pool = worker.pool
        self.release_slot(worker.job, worker.slot, worker.slot_failures, worker.started, status,
                          classification, detail, worker.job.host_epoch in self.terminated)
        if worker.started:
            return
        if status != 0 and worker.job.host_epoch not in self.terminated:
            # Each launch that failed before its worker started is recorded as a failed session.
            retryable = classification in ("retryable-spot", "retryable-capacity", "retryable-infrastructure")
            write_event(self.events_path, "interruption" if retryable else "rejected-session",
                        self.arguments.campaign_id, worker.job, session, detail if retryable else "job-failure",
                        f"status={status}; classification={classification}; detail={detail}", unit_id="")
        if classification == "retryable-spot" and detail.startswith("launch-capacity:"):
            # Spot moves past a region whose launch found no capacity.
            self.cool_down(pool, cleanup.get("aws_region") or worker.job.region)
        elif classification == "retryable-capacity":
            self.pool_ready[pool] = time.monotonic() + CAPACITY_RETRY_SECONDS

    def cool_down(self, pool, region):
        """Keep the pool's new Spot workers out of the region for its cool-down."""
        self.pool_regions.setdefault(pool, {})[region] = time.monotonic()
        self.spot_shortages.setdefault(pool, time.monotonic())

    def spot_waited(self, pool, now):
        """Whether a spot-then-on-demand pool has been short of Spot capacity for its whole bounded wait."""
        return (self.purchases[pool] == "spot-then-on-demand" and pool in self.spot_shortages and
                now - self.spot_shortages[pool] >= SPOT_SHORTAGE_WAIT_SECONDS)

    def release_slot(self, job, slot, failures, started, status, classification, detail, terminated=False):
        """Free the launch slot of an exited worker, from its exit as the attempts ledger records it.

        A worker that started, by uploading worker evidence, clears its slot's count. One that failed before it
        started passes the count, one higher, to its replacement, and a slot past its replacements gives up, so
        its pool opens no new slot. A wrapper that refused its inputs gives up its pool at once, every free slot
        included, since any launch would be refused the same way. A launch that found no capacity, or a wrapper
        never created, booted nothing.
        """
        pool = unit_worker_pool(job)
        if started:
            failures = 0
        elif status != 0 and classification == "input-rejection":
            self.exhausted[pool] = f"unit worker {job.identity} was refused before launch: {detail}"
            self.refused.add(pool)
            return
        elif (status != 0 and not terminated and detail != WRAPPER_NOT_CREATED and
              classification != "retryable-capacity" and
              not (classification == "retryable-spot" and detail.startswith("launch-capacity:"))):
            failures += 1
        if failures > MAX_REPLACEMENTS_PER_JOB:
            self.exhausted[pool] = (f"unit worker {job.identity} failed before it started: {detail}; "
                                    "the pool's workers exhausted their replacements")
        else:
            self.free_slots.setdefault(pool, []).append((slot, failures))

    def resolve_unlaunchable(self, deadline_reached):
        """Leave unresolved the queued items no worker can take: past the deadline, or in a pool that gave up."""
        for item in self.queued():
            if deadline_reached:
                self.unresolved[item.identity] = f"{self.arguments.phase} campaign reached its wall-clock launch limit"
            elif self.gave_up(item.pool) and not self.pool_workers(item.pool):
                self.unresolved[item.identity] = self.exhausted[item.pool]

    def lease_document(self, worker, sequence):
        document = {"schema_version": 2, "campaign_id": self.arguments.campaign_id,
                    "platform": worker.job.platform.name, "host_session": worker.host_session, "sequence": sequence}
        identity = worker.leases[sequence]
        if identity is None:
            return {**document, "stop": True}
        item = self.items[identity]
        return {**document, "phase": self.arguments.phase, "unit_id": item.unit_id, "replica_id": item.replica,
                "groups": list(worker.lease_groups[sequence]), "protocol": phase_protocol(self.arguments),
                "stop_by": worker.lease_stop_by[sequence]}

    def lease_stop_by(self, worker, item):
        """The host stop time a further lease of the item needs, never earlier than the host's current one: the
        timeouts of that lease and each lease the worker still holds, run one after another from now, or from the
        end of its boot allowance when that is later, then its idle stop and wind-down."""
        timeouts = [lease_timeout(self.remaining_seconds(item))]
        for sequence in worker.outstanding():
            identity = worker.leases[sequence]
            if identity is not None:
                seconds = dict(self.items[identity].groups)
                timeouts.append(lease_timeout(sum(seconds[group] for group in worker.lease_groups[sequence])))
        start = max(math.ceil(time.time()), worker.job.boot_by)
        return max(worker.stop_by, start + sum(timeouts) + UNIT_WORKER_IDLE_SECONDS + UNIT_WORKER_WIND_DOWN_SECONDS)

    def extend(self, worker, stop_by):
        """Whether the fleet budget covers the host until stop_by and its teardown, extending its reservation."""
        if not self.budget or stop_by <= worker.stop_by:
            return True
        admitted, _ = self.budget.extend(self.arguments.campaign_id, worker.job.host_epoch,
                                         until=stop_by + UNIT_WORKER_TEARDOWN_SECONDS)
        return admitted

    def issue(self, worker, item, stop_by=None):
        """Record a lease, or a stop lease without an item or stop_by, before it is written; a lease runs the item's
        remaining groups, and its host's stop time becomes stop_by."""
        if (item is None) != (stop_by is None):
            raise ValueError("a lease of an item needs a stop time, and a stop lease takes none")
        sequence = max(worker.leases, default=0) + 1
        if not worker.outstanding():
            worker.running_since = time.monotonic()
        groups = tuple(group for group, _ in self.remaining(item)) if item else ()
        write_unit_attempt(self.leases_path, worker, sequence, item, "leased" if item else "stop",
                           f"stop_by={stop_by}" if item else "-", groups=groups)
        worker.leases[sequence] = item.identity if item else None
        worker.unsent.add(sequence)
        if item is None:
            worker.settled.add(sequence)
            worker.stopping = True
        else:
            worker.lease_groups[sequence] = groups
            worker.lease_stop_by[sequence] = stop_by
            worker.stop_by = max(worker.stop_by, stop_by)
            worker.units.add(item.unit_id)
            self.held[item.identity] = (worker.job.host_epoch, sequence)

    def send(self, worker):
        for sequence in sorted(worker.unsent):
            try:
                put_unit_lease(self.arguments, worker, sequence, self.lease_document(worker, sequence))
            except (OSError, subprocess.SubprocessError):
                return
            worker.unsent.discard(sequence)

    def next_item(self, worker, queued=None, units=None):
        """The eligible queued item to lease next: On-Demand work first, then the longest remaining. An item
        is never a unit this host already held, nor one on an instance that measured any group of any replica
        of its unit or that its confirmation excludes, and On-Demand-only items go to On-Demand hosts;
        On-Demand hosts also take the rest. A projection passes the items still queued in it and the units the
        worker would hold by then."""
        used = {}
        for groups in self.accepted.values():
            for receipt in groups.values():
                used.setdefault((receipt["platform"], receipt["unit_id"]), set()).add(receipt["instance_id"])
        on_demand_worker = worker.job.market == "on-demand"
        units = worker.units if units is None else units
        # A Spot worker keeps taking its pool's tail items until an On-Demand worker replaces it.
        candidates = [
            item for item in (self.queued(worker.pool) if queued is None else queued)
            if item.unit_id not in units and worker.instance_id not in item.excluded_instances and
            worker.instance_id not in used.get((item.platform, item.unit_id), ()) and
            (on_demand_worker or not self.on_demand_only(item))]
        return min(candidates, key=lambda item: (not self.on_demand(item), -self.remaining_seconds(item),
                                                 item.identity), default=None)

    def lease(self, worker):
        """Keep the worker's next lease written one ahead of its current one, or stop it."""
        if worker.metadata is None:
            return
        if (worker.open() and worker.job.market == "spot" and self.purchases[worker.pool] != "spot" and
                worker.pool in self.tail and
                self.retired.get(worker.pool, 0) < self.tail_on_demand.get(worker.pool, 0)):
            # A tail Spot worker retires once an On-Demand worker admitted in the tail replaces it.
            self.retired[worker.pool] = self.retired.get(worker.pool, 0) + 1
            self.issue(worker, None)
        if worker.open() and worker.instance_id:
            outstanding = worker.outstanding()
            while len(outstanding) < 2:
                # A worker leases ahead only while no waiting worker of its pool could take the item now.
                waiting = sum(1 for other in self.pool_workers(worker.pool)
                              if other is not worker and other.open() and not other.outstanding())
                if outstanding and len(self.queued(worker.pool)) <= waiting:
                    break
                item = self.next_item(worker)
                if item is None:
                    if not outstanding or self.final(worker):
                        self.issue(worker, None)
                    break
                stop_by = self.lease_stop_by(worker, item)
                if not self.extend(worker, stop_by):
                    # The budget refused the lease, so an idle worker stops rather than waiting for its idle stop.
                    if not outstanding:
                        self.issue(worker, None)
                    break
                self.issue(worker, item, stop_by)
                outstanding = worker.outstanding()
        self.send(worker)

    def unneeded(self, worker):
        """Whether a worker that has not started holds no lease of an open item and could take no queued one."""
        return not worker.started and self.next_item(worker) is None and all(
            identity is None or self.done(identity) or identity in self.unresolved
            for identity in map(worker.leases.get, worker.outstanding()))

    def final(self, worker):
        """Whether the worker's pool can give it no further lease."""
        others = {self.held[identity][0] for identity in self.held} - {worker.job.host_epoch}
        return not self.queued(worker.pool) and not any(
            self.workers[epoch].pool == worker.pool for epoch in others if epoch in self.workers)

    def update_tail(self):
        """Mark a pool for On-Demand replacement once its remaining estimated work, the groups not yet accepted,
        falls to its tail share.

        Its Spot workers keep taking tail items until an On-Demand worker admitted in the tail replaces them.
        """
        for pool in self.pools:
            if self.purchases[pool] != "spot-then-on-demand":
                continue
            items = [item for item in self.items.values() if item.pool == pool]
            remaining = sum(self.remaining_seconds(item) for item in items if item.identity not in self.unresolved)
            if remaining <= sum(seconds for item in items for _, seconds in item.groups) * ON_DEMAND_TAIL_PERCENT / 100:
                self.tail.add(pool)

    def free_at(self, worker, now):
        """When the worker is projected to take new work: once it boots, and once the leases it holds have run
        their groups' estimates, the running one counted from when it began."""
        start = worker.running_since if worker.started else max(now, worker.launched_at + UNIT_WORKER_BOOT_SECONDS)
        held = []
        for sequence in worker.outstanding():
            identity = worker.leases[sequence]
            if identity is not None:
                seconds = dict(self.items[identity].groups)
                held.append(sum(seconds[group] for group in worker.lease_groups[sequence]))
        if not held:
            return max(now, start)
        return max(now, start + held[0]) + sum(held[1:])

    def late_items(self, pool, now):
        """The pool's queued items its workers would start later than a new worker could.

        A list schedule projects the pool's open workers, booting ones included: the worker free soonest that may
        take an item takes the one it would be leased next and is busy for its estimate. Once that worker is free
        only after a new worker's boot, every item still queued is late, as is any item no worker may take.
        """
        workers = [worker for worker in self.pool_workers(pool) if worker.open()]
        free = {worker.job.host_epoch: self.free_at(worker, now) for worker in workers}
        units = {worker.job.host_epoch: set(worker.units) for worker in workers}
        queued = self.queued(pool)
        while queued:
            for worker in sorted(workers, key=lambda worker: free[worker.job.host_epoch]):
                epoch = worker.job.host_epoch
                item = self.next_item(worker, queued, units[epoch])
                if item is not None:
                    break
            else:
                return queued
            if free[epoch] > now + UNIT_WORKER_BOOT_SECONDS:
                return queued
            queued.remove(item)
            units[epoch].add(item.unit_id)
            free[epoch] += self.remaining_seconds(item)
        return []

    def pool_demand(self, pool, now):
        """The market of the pool's next worker, if a new one would start some queued item sooner."""
        if self.gave_up(pool):
            return None
        late = self.late_items(pool, now)
        if self.on_demand_ready.get(pool, 0) <= now:
            if any(self.on_demand(item) for item in late):
                return "on-demand"
            return "spot" if late else None
        # While On-Demand admission is refused, Spot workers take every item Spot may still run.
        return "spot" if self.purchases[pool] != "on-demand" and any(
            not self.on_demand_only(item) for item in late) else None

    def candidate(self, pool, market):
        candidate = self.candidates.get(pool)
        if candidate is None:
            candidate = unit_worker_job(self.platforms[pool], self.next_ordinal, self.next_epoch, market)
            self.next_ordinal += 1
            self.next_epoch += 1
            # The manifest names each worker before the budget can reserve its host epoch.
            self.planned.append(candidate)
            write_jobs(self.workers_path, self.planned, extend=True)
            # A launch the budget defers keeps this worker for the pool's next launch.
            self.candidates[pool] = candidate
        now = time.monotonic()
        cooling = self.pool_regions[pool] = {
            region: failed_at for region, failed_at in self.pool_regions.get(pool, {}).items()
            if now - failed_at < SPOT_REGION_COOL_DOWN_SECONDS}
        candidate = replace(candidate, market=market, spot_capacity_failures=tuple(cooling))
        if market == "spot" and not launch_placements(self.arguments, candidate):
            # Each region of the platform's list has found no Spot capacity for this pool within its cool-down.
            # Once the pool's bounded wait is over it launches On-Demand; until then it tries every region again.
            if self.spot_waited(pool, now):
                candidate = replace(candidate, market="on-demand")
            else:
                self.pool_regions.pop(pool, None)
                candidate = replace(candidate, spot_capacity_failures=())
        return candidate

    def launch(self, now):
        running = {worker.job.host_epoch: (worker.process, worker.job, worker.log) for worker in self.workers.values()}
        blocked_vcpus = None
        for pool in self.pools:
            while len(self.workers) < self.arguments.max_concurrent and self.pool_ready.get(pool, 0) <= now:
                market = self.pool_demand(pool, now)
                # A worker is planned only once its platform has room for it.
                if market is None or not platform_has_capacity(
                        unit_worker_job(self.platforms[pool], 0, 0, market), running, self.arguments):
                    break
                candidate = self.candidate(pool, market)
                if candidate.market == "on-demand" and self.on_demand_ready.get(pool, 0) > now:
                    break
                # A launch reserves the worker's boot and a first lease of the pool's longest queued item.
                boot_by = math.ceil(time.time()) + UNIT_WORKER_BOOT_ALLOWANCE_SECONDS
                first_lease = lease_timeout(max(map(self.remaining_seconds, self.queued(pool)), default=0))
                candidate = replace(candidate, boot_by=boot_by, stop_by=boot_by + first_lease +
                                    UNIT_WORKER_IDLE_SECONDS + UNIT_WORKER_WIND_DOWN_SECONDS)
                if self.budget and blocked_vcpus is not None and candidate.platform.vcpus >= blocked_vcpus:
                    break
                candidate, ready_at, admitted, scan, blocked = admit_launch(
                    self.arguments, self.result_root, self.attempts_path, self.capacity_waits, candidate, now, now,
                    [record[1] for record in running.values()], self.spot_waited(pool, now))
                self.candidates[pool] = candidate
                if not admitted and candidate.market == "spot" and not self.budget:
                    # Without a shared budget, a Spot launch is refused only when no region has Spot quota
                    # headroom, or the query for it failed.
                    self.spot_shortages.setdefault(pool, now)
                if not admitted and candidate.market == "on-demand" and market == "on-demand":
                    # The pool's Spot-eligible items need not wait for On-Demand admission.
                    self.on_demand_ready[pool] = ready_at
                    if self.pool_demand(pool, now) == "spot":
                        continue
                if not admitted:
                    self.pool_ready[pool] = ready_at
                    blocked_vcpus = blocked or blocked_vcpus
                    if not scan:
                        return
                    break
                worker = self.start(pool, candidate, now)
                if worker is not None:
                    running[candidate.host_epoch] = (worker.process, worker.job, worker.log)

    def start(self, pool, job, now):
        del self.candidates[pool]
        # A launch takes over a free slot, and its failures, before it opens a new one named for it.
        slots = self.free_slots.get(pool)
        slot, slot_failures = slots.pop(0) if slots else (job.host_epoch, 0)
        validate_frozen_inputs(self.root, self.arguments, self.items_path, self.fingerprint)
        try:
            process, log = start_wrapper(self.arguments, self.root, self.result_root, self.runner,
                                         self.attempts_path, job, slot, slot_failures)
        except WrapperStartError as error:
            if self.budget:
                self.budget.release(self.arguments.campaign_id, job.host_epoch, cleanup_verified=True)
            detail = f"controller could not start wrapper: {error}"
            write_attempt(self.attempts_path, job, "retryable-infrastructure", detail)
            self.pool_ready[pool] = time.monotonic() + CAPACITY_RETRY_SECONDS
            self.release_slot(job, slot, slot_failures, False, 1, "retryable-infrastructure", detail)
            return None
        # The worker boots from the poll that launched it, so a projection in that poll counts it as booting
        # no later than a further launch would.
        worker = self.workers[job.host_epoch] = UnitWorker(job, process, log, slot=slot, slot_failures=slot_failures,
                                                           launched_at=now, stop_by=job.stop_by)
        if job.market == "on-demand" and pool in self.tail:
            self.tail_on_demand[pool] = self.tail_on_demand.get(pool, 0) + 1
        write_attempt(self.attempts_path, job, "started", f"wrapper_pid={process.pid}")
        return worker

    def start_campaign(self):
        """The phase's start time. A first start records the tooling digest and an empty semantic-rejection
        ledger before its campaign state; a restart must run the tooling that started the phase."""
        state = self.result_root / "campaign-state.tsv"
        digest_path = self.result_root / TOOLING_DIGEST
        digest = tooling_digest(self.root)
        if state.exists():
            try:
                recorded = read_properties(digest_path)["tooling_sha256"]
            except (OSError, RuntimeError, KeyError) as error:
                raise UnsafeCampaignError(
                    f"unit campaign phase {self.result_root} records no tooling digest that can be read, so it "
                    f"cannot be resumed: {error}") from error
            if recorded != digest:
                raise UnsafeCampaignError(
                    f"unit campaign phase {self.result_root} was started by other tooling (tooling_sha256={recorded}, "
                    f"now {digest}); resume it with the tooling that started it")
            read_semantic_rejections(self.rejections_path)
        else:
            temporary = digest_path.with_name(digest_path.name + ".new")
            with temporary.open("w") as output:
                output.write(f"tooling_sha256={digest}\n")
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, digest_path)
            write_ledger(self.rejections_path, [], unit_session.SEMANTIC_REJECTION_FIELDS)
        return campaign_started_at(state, self.arguments)

    def run(self):
        arguments = self.arguments
        validate_execution_policy(arguments, self.result_root)
        self.fingerprint = frozen_input_fingerprint(self.root, arguments, self.items_path)
        started_at = self.start_campaign()
        remaining_seconds = max(0, phase_timeout(arguments) - max(0, time.time() - started_at))
        deadline = time.monotonic() + remaining_seconds
        try:
            self.recover()
            while True:
                if self.budget:
                    self.budget.check()
                now = time.monotonic()
                deadline_reached = now >= deadline
                for worker in list(self.workers.values()):
                    status = worker.process.poll()
                    if status is None:
                        self.poll(worker)
                    else:
                        self.finish(worker, status)
                # Work handed back after the deadline, or to a pool that gave up, is not leased again.
                self.resolve_unlaunchable(deadline_reached)
                self.update_tail()
                for worker in self.workers.values():
                    self.lease(worker)
                if not deadline_reached:
                    self.launch(now)
                for epoch, worker in self.workers.items():
                    # A lost host is stopped at once; its cleanup still recovers a completion that lands.
                    # So is a booting host once the pool has nothing it could take. Its stop lease first records
                    # it stopping, so through its cleanup neither this controller nor a restarted one counts it
                    # as open for work.
                    if (worker.lost or self.unneeded(worker)) and epoch not in self.terminated:
                        if not (worker.lost or worker.stopping):
                            self.issue(worker, None)
                        self.terminated.add(epoch)
                        worker.process.send_signal(signal.SIGTERM)
                if not self.workers and not self.queued():
                    break
                time.sleep(UNIT_POLL_SECONDS)
        except (Exception, KeyboardInterrupt) as error:
            records = {worker.process.pid: (worker.process, worker.job, worker.log) for worker in self.workers.values()}
            halt_and_clean_up(arguments, self.result_root, self.budget, records, error)
            raise
        # A semantic failure fails the campaign, even where another attempt was accepted.
        unresolved = [(item, SEMANTIC_FAILURE_DETAIL if identity in self.semantic else
                       self.unresolved.get(identity, "not accepted"))
                      for identity, item in sorted(self.items.items())
                      if not self.done(identity) or identity in self.semantic]
        write_unresolved_units(self.result_root / "unresolved-units.tsv", unresolved)
        if unresolved:
            details = ", ".join(f"{item.identity} {detail}" for item, detail in unresolved)
            raise SystemExit(f"campaign completed with unresolved units: {details}")
        self.validate_phase()
        if self.validation.kind == "language" and self.arguments.phase == "primary":
            aggregate_language_plans(self.arguments, self.result_root)
        # Every worker has exited, each settled only once its cleanup was verified.
        write_phase_complete(self.result_root, arguments)

    def validate_phase(self):
        """The phase gate: every unit replica of the phase accepted as one campaign."""
        arguments = self.arguments
        receipts, confirmations, phase = self.accepted_path, (), arguments.phase
        if arguments.phase == "confirmation":
            receipts, phase = arguments.primary_results / "accepted-units.tsv", "primary"
            confirmations = read_accepted_units(self.accepted_path)
        try:
            self.validation.validate_ledger(receipts, phase, confirmations)
        except (OSError, ValueError) as error:
            raise SystemExit(f"{arguments.phase} unit campaign is not accepted: {error}") from error


@dataclass
class CampaignStep:
    """One step of a candidate's chain: a phase's controller, or the reduction that lists its confirmation jobs.
    It runs as a child process the driver can adopt after a restart."""
    label: str
    root: Path
    # The child's command, given the step's (max concurrent, max concurrent per platform) share.
    command: object
    complete: object
    # Whether the step has anything to do once its predecessors completed; confirmation runs only for listed jobs.
    needed: object = lambda: True
    # Whether the child is a phase controller, whose command takes a concurrency share and which records its
    # process ID in root; a reduction is not.
    shared: bool = True


@dataclass
class CampaignChain:
    """A candidate's steps, each started once the one before it completed."""
    name: str
    steps: list
    position: int = 0
    child: object = None
    # The share of the campaign's hosts the driver started its child with; an adopted child's is its frozen one.
    share: object = None
    failure: str = ""

    @property
    def step(self):
        return self.steps[self.position]

    @property
    def finished(self):
        return bool(self.failure) or self.position == len(self.steps)


class AdoptedChild:
    """A step's child process an earlier driver started, followed through its process ID."""

    def __init__(self, process_id, step):
        self.pid = process_id
        self.step = step

    def poll(self):
        return None if child_process_id(self.step) == self.pid else 0


def child_process_id(step):
    """The process ID of the step's live child. A phase's is its controller, found by the process ID and start time it
    records, whatever options a restarted driver gives it. A reduction keeps no record of itself, so its command,
    which names the step's root, is the one way to find it."""
    if step.shared:
        return controller_process(step.root, DRIVER_CHILD)
    # ps joins the arguments with spaces, so the step's are matched exactly as joined, whatever spaces its root
    # holds. The interpreter's path is ps's, which may name another path to the same interpreter, spaces and all.
    expected = re.compile(r".+? " + " ".join(re.escape(argument) for argument in step.command(None)[1:]))
    for line in check_output(["ps", "ax", "-o", "pid=,command="]).splitlines():
        process_id, _, command = line.lstrip().partition(" ")
        if expected.fullmatch(command):
            return int(process_id)
    return None


def adopt_child(step):
    process_id = child_process_id(step)
    return None if process_id is None else AdoptedChild(process_id, step)


def start_detached(command, root, log):
    """Start a command in its own session, so it outlives its starter, with its output appended to the log in root.
    A controller records its own process ID and start time there once it holds root's controller lock."""
    root.mkdir(parents=True, exist_ok=True)
    with (root / log).open("a") as output:
        return subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                start_new_session=True)


def start_child(step, share):
    """Start the step's child in its own session, so it outlives the driver."""
    return start_detached(step.command(share), step.root, DRIVER_CHILD_LOG)


def detach(root, record, log):
    """Run this command again without --detach, in its own session, as the controller of root, and return.

    While a live controller holds root's lock, only report it. Otherwise the new controller resumes from the
    ledgers, adopting whatever its predecessor left running.
    """
    root.mkdir(parents=True, exist_ok=True)
    with (root / CONTROLLER_LOCK).open("a") as lock_file:
        try:
            fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            try:
                owner = f" as process {read_properties(root / record)['pid']}"
            except (OSError, RuntimeError, KeyError):
                owner = ""
            print(f"a controller already runs{owner} for {root}; its log is {root / log}")
            return
    command = [sys.executable, str(Path(__file__).resolve()),
               *(argument for argument in sys.argv[1:] if argument != "--detach")]
    process = start_detached(command, root, log)
    print(f"started controller process {process.pid} for {root}; its log is {root / log}")


def frozen_share(step):
    """The (max concurrent, max concurrent per platform) share a phase's execution policy froze, or None before the
    phase froze one."""
    policy = step.root / "execution-policy.json"
    if not policy.exists():
        return None
    recorded = json.loads(policy.read_text())
    return recorded["max_concurrent"], recorded["max_concurrent_per_platform"]


def step_share(step, open_chains, limits, free):
    """The share a phase starts with, as (max concurrent, max concurrent per platform), or None while the hosts free
    of the campaign's limits cannot hold it. A restarted phase needs the share its execution policy froze. A new one
    takes an even share, rounded down, of the limits among the chains still open, or what is free of it, at least one
    host; the campaign refuses limits smaller than its number of chains, so an even share is at least one host."""
    share = frozen_share(step) or tuple(min(limit // open_chains, left) for limit, left in zip(limits, free))
    return share if all(1 <= want <= left for want, left in zip(share, free)) else None


def run_chains(chains, maximum, per_platform, start=start_child, adopt=adopt_child, poll_seconds=DRIVER_POLL_SECONDS):
    """Advance every chain until each completes or fails, starting each chain's next step once the one before it
    completed and the campaign's hosts are free for its share. A chain's failure starts no more of its steps, though
    a phase's dispatch failure also halts any fleet budget it shares, which stops every phase sharing it. A step
    already complete is skipped, and a step whose child still runs is adopted before any step starts, so the hosts
    it holds stay counted: an adopted phase holds the share its execution policy froze, and until it freezes one, no
    other phase starts. A child that exits because another controller owns its phase leaves the step to be adopted,
    or started again once that controller is gone. Any chain's current phase, adopted or not, that froze a share
    past the limits stops the driver, naming the limits that hold it."""
    limits = (maximum, per_platform)

    def join(chain):
        # A candidate's language chain shares its build with the candidate's baseline chain.
        other = next((other for other in chains if other.child is not None and other.step is chain.step), None)
        if other is not None:
            chain.child, chain.share = other.child, other.share
        return other is not None

    while True:
        for chain in chains:
            while chain.child is None and not chain.finished and (chain.step.complete() or not chain.step.needed()):
                chain.position += 1
        # A phase keeps the share it froze, adopted or restarted, so limits that cannot hold it stop the driver.
        oversized = {}
        for chain in chains:
            share = None if chain.finished or not chain.step.shared else frozen_share(chain.step)
            if share and any(want > limit for want, limit in zip(share, limits)):
                oversized.setdefault(chain.step.root, (f"{chain.name} {chain.step.label}", share))
        if oversized:
            needed = [max(limit, *(share[index] for _, share in oversized.values()))
                      for index, limit in enumerate(limits)]
            raise SystemExit("phases froze shares past the campaign's limits: " + ", ".join(
                f"{phase} {share[0]} hosts, {share[1]} per platform" for phase, share in oversized.values()) +
                f"; give at least --max-concurrent {needed[0]} --max-concurrent-per-platform {needed[1]}")
        open_chains = sum(not chain.finished for chain in chains)
        waiting = [chain for chain in chains if chain.child is None and not chain.finished]
        for chain in waiting:
            if not join(chain):
                chain.child, chain.share = adopt(chain.step), None
            if chain.child is not None:
                print(f"{chain.name}: adopted the running {chain.step.label}", flush=True)
        held = {id(chain.child): chain.share or frozen_share(chain.step)
                for chain in chains if chain.child is not None and chain.step.shared}
        free = None if None in held.values() else [limit - sum(share[index] for share in held.values())
                                                   for index, limit in enumerate(limits)]
        for chain in waiting:
            if chain.child is not None:
                continue
            step = chain.step
            if join(chain):
                print(f"{chain.name}: adopted the running {step.label}", flush=True)
                continue
            share = None
            if step.shared:
                share = None if free is None else step_share(step, open_chains, limits, free)
                if share is None:
                    continue
                free = [left - want for left, want in zip(free, share)]
            print(f"{chain.name}: starting {step.label}" + (f" with {share[0]} hosts" if share else ""), flush=True)
            chain.child, chain.share = start(step, share), share
        if open_chains == 0:
            return
        time.sleep(poll_seconds)
        for chain in chains:
            if chain.child is not None and (status := chain.child.poll()) is not None:
                chain.child = None
                if chain.step.complete():
                    print(f"{chain.name}: {chain.step.label} complete", flush=True)
                    chain.position += 1
                elif status == CONTROLLER_BUSY_STATUS:
                    print(f"{chain.name}: another controller owns {chain.step.label}; adopting it", flush=True)
                else:
                    chain.failure = f"{chain.step.label} failed; see {chain.step.root / DRIVER_CHILD_LOG}"
                    print(f"{chain.name}: {chain.failure}", flush=True)


def confirmation_jobs_listed(path):
    with path.open(newline="") as input_file:
        return any(True for _ in csv.DictReader(input_file, delimiter="\t"))


def campaign_chains(arguments, candidates):
    """Each candidate's chain, build, smoke, primary, reduction, and confirmation when the reduction lists jobs, and
    with language plans, a second chain running their smoke and primary on the same build. The reduction takes the
    primary's events; a campaign archive, and with it any retrieval metadata, exists only once the campaign ends."""
    script = str(Path(__file__).resolve())
    common = [f"--spot-vcpu-reserve={arguments.spot_vcpu_reserve}",
              *([f"--release-version={arguments.release_version}"] if arguments.release_version else []),
              *([f"--phase-timeout-seconds={arguments.phase_timeout_seconds}"]
                if arguments.phase_timeout_seconds is not None else []),
              *([f"--fleet-budget={arguments.fleet_budget.resolve()}"] if arguments.fleet_budget else []),
              *(f"--platform-regions={value}" for value in arguments.platform_region_values),
              *(f"--purchase={value}" for value in arguments.purchase_values),
              *(f"--transfer-bucket={value}" for value in arguments.transfer_bucket)]
    shards = [f"--shard={shard}" for shard in arguments.selected_shards or ()]

    def phase(candidate, label, root, *options):
        selection = f"{candidate.name}={candidate.commit}"
        return CampaignStep(label, root, lambda share: [
            sys.executable, script, label, arguments.campaign, "--execute", f"--candidate={selection}",
            f"--campaign-root={arguments.campaign_root.resolve()}", f"--max-concurrent={share[0]}",
            f"--max-concurrent-per-platform={share[1]}", *common, *options], lambda: (root / PHASE_COMPLETE).is_file())

    chains = []
    for candidate in candidates:
        directory = arguments.campaign_root / candidate.name
        reduction = directory / "reduction"
        build = phase(candidate, "build", directory / "build")
        chains.append(CampaignChain(candidate_campaign_id(arguments.campaign, candidate.name), [
            build,
            phase(candidate, "smoke", directory / "smoke", f"--smoke-protocol={arguments.smoke_protocol}", *shards),
            phase(candidate, "primary", directory / "primary", *shards),
            CampaignStep("reduction", reduction, lambda share, primary=directory / "primary", output=reduction: [
                sys.executable, str(Path(script).with_name("aggregate_results.py")),
                f"--primary-receipts={primary / 'accepted-units.tsv'}", f"--artifacts-root={primary}",
                # A phase writes its events file with its first event.
                *([f"--events={primary / 'campaign-events.tsv'}"] if (primary / "campaign-events.tsv").is_file()
                  else []),
                f"--output-dir={output.resolve()}", *shards,
                *([f"--release-version={arguments.release_version}"] if arguments.release_version else [])],
                lambda output=reduction: (output / "output-checksums.tsv").is_file(), shared=False),
            replace(phase(candidate, "confirmation", directory / "confirmation",
                          f"--confirmation-jobs={(reduction / 'confirmation-jobs.tsv').resolve()}", *shards),
                    needed=lambda jobs=reduction / "confirmation-jobs.tsv": confirmation_jobs_listed(jobs))]))
        if arguments.language_plan:
            plans = [f"--language-plan={plan.resolve()}" for plan in arguments.language_plan]
            chains.append(CampaignChain(candidate_campaign_id(arguments.campaign, candidate.name, language=True), [
                build,
                phase(candidate, "smoke", directory / "lang" / "smoke", *plans),
                phase(candidate, "primary", directory / "lang" / "primary", *plans)]))
    return chains


def check_campaign_options(arguments, record=True):
    """Record the options that decide what the campaign's phases measure, or refuse a restart that changes them: the
    restart skips each completed phase, so it would measure nothing under the new options. Without record, only
    compare them with any the campaign records."""
    options = json.dumps({
        "selected_shards": sorted(arguments.selected_shards) if arguments.selected_shards else None,
        "smoke_protocol": arguments.smoke_protocol,
        "release_version": arguments.release_version,
        "language_plans": {str(plan.resolve()): hashlib.sha256((plan / "plan.json").read_bytes()).hexdigest()
                           for plan in arguments.language_plan},
    }, indent=2, sort_keys=True) + "\n"
    path = arguments.campaign_root / CAMPAIGN_OPTIONS
    written = record and write_new_file(path, options.encode())
    if not written and path.is_file() and path.read_text() != options:
        raise SystemExit(f"the campaign's options differ from those {path} records; a restart measures what the "
                         f"campaign started measuring, so start another campaign for these:\n{options}")


def check_campaign_candidates(campaign_root, candidates):
    """Refuse a restart that names other candidates than the campaign's candidates.tsv records, or the same ones at
    other commits. Each of candidates is a row of its fields, compared on the fields it holds, as a candidate not yet
    frozen has no archive digest."""
    recorded = read_candidates(campaign_root)
    if recorded and [{field: row[field] for field in candidates[0]} for row in recorded] != candidates:
        raise SystemExit(f"the campaign's candidates differ from those {campaign_root}/candidates.tsv records; a "
                         "restart names the same candidates at the same commits")


def run_campaign(arguments, root):
    """Freeze every candidate, then run each candidate's phases as their own controllers, side by side."""
    if not arguments.candidates:
        raise SystemExit("a campaign requires at least one --candidate NAME=COMMITISH")
    if len(arguments.candidates) > 1 and arguments.fleet_budget is None:
        raise SystemExit("a campaign of several candidates shares one fleet; name its policy with --fleet-budget")
    given = [option for option, value in (("--smoke-results", arguments.smoke_results),
                                          ("--primary-results", arguments.primary_results),
                                          ("--confirmation-jobs", arguments.confirmation_jobs),
                                          ("--engine-builds", arguments.engine_builds)) if value is not None]
    if given:
        raise SystemExit(f"a campaign passes each phase its prerequisites itself; it takes no {', '.join(given)}")
    # Each chain reserves the fleet and numbers its hosts under its own campaign ID.
    chain_names = {}
    for name, _ in arguments.candidates:
        for language in (False, True) if arguments.language_plan else (False,):
            campaign_id = candidate_campaign_id(arguments.campaign, name, language)
            validate_campaign_id(campaign_id, f"candidate {name}'s campaign ID")
            if campaign_id in chain_names:
                raise SystemExit(f"candidates {chain_names[campaign_id]} and {name} would both run as campaign "
                                 f"{campaign_id}; rename one")
            chain_names[campaign_id] = name
    # Each chain's running phase takes at least one host.
    for option, limit in (("--max-concurrent", arguments.max_concurrent),
                          ("--max-concurrent-per-platform", arguments.max_concurrent_per_platform)):
        if limit < len(chain_names):
            raise SystemExit(f"{option} {limit} cannot give each of the campaign's {len(chain_names)} phase sequences "
                             f"running together a host; give at least {len(chain_names)}")
    if not arguments.execute:
        for name, commitish in arguments.candidates:
            print(f"candidate {name}: {resolve_commit(root, commitish)}, campaign "
                  f"{candidate_campaign_id(arguments.campaign, name)}, results {arguments.campaign_root / name}")
        return
    if arguments.detach:
        # The detached driver checks again under the campaign's lock; checking here puts a refusal in this shell.
        check_campaign_candidates(arguments.campaign_root, [
            {"name": name, "commit": resolve_commit(root, commitish)} for name, commitish in arguments.candidates])
        check_campaign_options(arguments, record=False)
        detach(arguments.campaign_root, CAMPAIGN_DRIVER, CAMPAIGN_DRIVER_LOG)
        return
    with controller_lock(arguments.campaign_root, CAMPAIGN_DRIVER):
        candidates = [freeze_candidate(root, arguments.campaign_root, arguments.campaign, name, commitish)
                      for name, commitish in arguments.candidates]
        check_campaign_candidates(arguments.campaign_root, [candidate.row for candidate in candidates])
        for candidate in candidates:
            record_candidate(arguments.campaign_root, candidate)
        check_campaign_options(arguments)
        chains = campaign_chains(arguments, candidates)
        run_chains(chains, arguments.max_concurrent, arguments.max_concurrent_per_platform)
    failed = [f"{chain.name}: {chain.failure}" for chain in chains if chain.failure]
    if failed:
        raise SystemExit("campaign finished with failed candidates:\n" + "\n".join(failed))


def controller_process(root, record):
    """The process ID root's controller records in record, while the process with that ID started when the record
    says. The record outlives its controller, whose process ID may since name another process. Root's controller
    lock is left alone, as even a shared hold of it would stop a controller that is starting from taking it."""
    try:
        values = read_properties(root / record)
        process_id, started = int(values["pid"]), values["started"]
    except (OSError, RuntimeError, KeyError, ValueError):
        return None
    return process_id if process_start(process_id) == started else None


def main():
    arguments = parse_args()
    if arguments.fleet_budget:
        arguments.shared_budget = FleetBudget(arguments.fleet_budget)
    directory = Path(__file__).resolve().parent
    root = directory.parents[2]
    arguments.candidates = parse_candidates(arguments.candidate_values)
    arguments.campaign_root = (arguments.campaign_root or
                               root / "benchmark-results" / "pre-review-baseline" / arguments.campaign).absolute()
    platforms = read_platforms(directory)
    parse_launch_options(arguments, platforms)
    arguments.transfer_buckets = parse_transfer_buckets(arguments.transfer_bucket)
    if arguments.detach and not arguments.execute:
        raise SystemExit("--detach starts an execution; it requires --execute")
    if arguments.phase == "campaign":
        run_campaign(arguments, root)
        return
    if len(arguments.candidates) != 1:
        raise SystemExit("a phase measures one --candidate NAME=COMMITISH")
    [(name, commitish)] = arguments.candidates
    # A candidate's phases are its campaign, laid out under <campaign root>/<name>; a language plan's phases are
    # another, under <name>/lang, measuring the same build.
    arguments.campaign_id = candidate_campaign_id(arguments.campaign, name, language=bool(arguments.language_plan))
    candidate_root = arguments.campaign_root / name
    phases = candidate_root / "lang" if arguments.language_plan else candidate_root
    result_root = phases / arguments.phase
    if arguments.phase != "build" and arguments.engine_builds is None:
        arguments.engine_builds = candidate_root / "build"
    arguments.smoke_results = arguments.smoke_results or phases / "smoke"
    arguments.primary_results = arguments.primary_results or phases / "primary"
    if arguments.release_version:
        release = released_artifact.manifest(root, arguments.release_version)
        commit = resolve_commit(root, commitish)
        if check_output(["git", "rev-parse", f"{commit}:src/main"], cwd=root) != release["engine_tree"]:
            raise SystemExit(f"candidate {name}'s production source differs from the selected release")
    arguments.regional_amis = read_platform_regions(directory)
    shards = read_shards(directory)
    if arguments.selected_shards is not None:
        if (arguments.language_plan or arguments.phase == 'build' or
                not set(arguments.selected_shards) <= set(shards) or
                len(arguments.selected_shards) != len(set(arguments.selected_shards))):
            raise SystemExit('invalid baseline shard selection')
        shards = arguments.selected_shards
    kind = "build" if arguments.phase == "build" else "language" if arguments.language_plan else "baseline"
    allocations = plans = None
    if kind == "build":
        if arguments.confirmation_jobs is not None:
            raise SystemExit("an engine build phase confirms nothing; it takes no --confirmation-jobs")
        if arguments.language_plan:
            raise SystemExit("an engine build phase measures no language plan; it takes no --language-plan")
        units, shards = engine_build.units(), [engine_build.SHARD]
    elif kind == "language":
        plans = language_plans(arguments)
        campaign = language_fleet.campaign_units(plans, arguments.phase)
        units, allocations, shards = campaign["units"], campaign["allocations"], sorted(campaign["allocations"])
    else:
        units = work_units.load(directory)
    confirmation_units = ()
    if arguments.phase == "confirmation":
        if arguments.confirmation_jobs is None:
            raise SystemExit("confirmation requires --confirmation-jobs")
        confirmation_units = read_confirmation_units(
            arguments.confirmation_jobs, platforms, units,
            read_accepted_units(arguments.primary_results / "accepted-units.tsv"))
    items = build_work_items(arguments.phase, platforms, units, shards, confirmation_units, allocations,
                             mirror=kind == "baseline")
    arguments.unit_platforms = pool_platforms(platforms, items)
    arguments.longest_units = longest_units(items)
    arguments.pool_shards = pool_shards(items)
    validate_configuration(arguments, items)
    validate_durable_paths(root, arguments, result_root)
    items_path = result_root / "work-items.tsv"
    write_work_items(items_path, items)
    print(f"planned {len(items)} work items with maximum concurrency {arguments.max_concurrent}")
    print(f"work item manifest: {items_path}")
    if arguments.detach:
        # The plan is checked here; the detached controller plans again and executes it.
        detach(result_root, DRIVER_CHILD, DRIVER_CHILD_LOG)
        return
    if arguments.execute:
        # The controller owns its result root before its slow setup, so a second one stops at once.
        with controller_lock(result_root, DRIVER_CHILD):
            validate_source(root, arguments)
            validate_dependencies(root, arguments)
            resolve_engine_builds(root, arguments)
            validation = unit_validation(root, arguments, kind, plans)
            validate_prerequisites(arguments, validation)
            workers = [unit_worker_job(arguments.unit_platforms[pool], 0, 0, "spot")
                       for pool in sorted({item.pool for item in items})]
            validate_aws(arguments, platforms, workers)
            stage_jdks(arguments, workers)
            upload_engine_builds(arguments, workers)
            if arguments.language_plan:
                write_language_bundles(arguments, result_root)
                upload_language_bundles(arguments, workers)
            UnitDispatcher(arguments, root, result_root, items, platforms, validation).run()


if __name__ == "__main__":
    main()
