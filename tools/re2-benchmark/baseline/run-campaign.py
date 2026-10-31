#!/usr/bin/env python3

import argparse
import contextlib
import csv
import fcntl
import functools
import gzip
import hashlib
import io
import json
import math
import os
import re
import shlex
import shutil
import signal
import statistics
import subprocess
import sys
import tarfile
import tempfile
import time
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field, replace
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

import aggregate_results
import cloud
import control_host
import engine_build
import telemetry
import unit_session
import work_units
from acceptance import normalize_heap_size
from campaign_settings import (
    PURCHASE_POLICIES, REGION_PATTERN, SETTINGS as CAMPAIGN_SETTINGS, CampaignSettings, SettingsError,
    changed as changed_settings, locked as settings_locked, read as read_settings, seeded as seeded_settings)
from fleet_budget import (
    ON_DEMAND_VCPU_SAFETY_RESERVE, STATE_FILE, AccountMismatch, BudgetError, FleetBudget, on_demand_regions,
    owner_fleet, read_state as read_fleet_state)
from campaign_io import (UnsafeCampaignError, append_ledger, check_output, fsync_directory, ledger_lines,
                         write_ledger)
from cloud import CloudError
from unit_dispatch import (
    UNIT_COMPLETION_FIELDS, UNIT_UPLOADS, completion_key, extract_unit_result,
    get_unit_worker_result, group_identity, heartbeat_key, heartbeat_sequence, interruption_key, lease_key,
    list_unit_worker_results, marker_seconds, marker_time, measured_group_seconds, persist_accepted_unit,
    persist_semantic_rejection, persist_unit_upload, read_accepted_units, read_semantic_rejections, read_unit_attempts,
    read_unit_receipt, read_unit_uploads, recovered_keys, result_key, retain_unit_attempt, shows_worker_start,
    unit_attempt_directory, unit_logical_identity, unit_upload_row, write_leases,
    write_unit_attempt, write_unresolved_units)
from unit_session import PROTOCOL_REJECTION_STATUS, SEMANTIC_FAILURE_STATUS

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "language"))
import fleet as language_fleet
import released_artifact

STANDARD_INSTANCE_FAMILIES = frozenset("acdhimrtz")
SPOT_VCPU_SAFETY_RESERVE = 32
CAPACITY_RETRY_SECONDS = 30
MAX_REPLACEMENTS_PER_JOB = 2
# Once this share of a pool's estimated work is all that remains unaccepted, the pool's new
# launches use On-Demand so the phase's tail does not wait on Spot capacity or a reclaim,
# unless the pool's platform buys only Spot.
ON_DEMAND_TAIL_PERCENT = 10
# A launch whose answer never came, and whose instance no tag query found by the end of its boot allowance, booted
# nothing.
LAUNCH_NOT_FOUND = "launched instance was not found"
BOOT_DEADLINE_MISSED = "worker did not report by its boot deadline"
# A worker launches only once S3 holds every lease it was launched with. One whose leases S3 did not take booted
# nothing, so it spends none of its slot's replacements, and its pool waits CAPACITY_RETRY_SECONDS before it launches
# again.
FIRST_LEASES_UNWRITTEN = "launch-infrastructure:first leases not written"
# A worker admitted in a loop whose launch the controller never asked for, as when an error ended the loop first,
# booted nothing either, and spends none of its slot's replacements.
LAUNCH_NOT_ATTEMPTED = "launch-infrastructure:launch not attempted"
# A request one region refused while its pool had another region to try spends none of its slot's replacements.
REGION_REFUSAL = "launch-region-refused:"
UNIT_WORKER_SHARD = "unit-worker"
# run-unit-worker.sh exits with this status after a Spot interruption notice.
UNIT_WORKER_INTERRUPTED_STATUS = 43
UNIT_WORKER_LIFETIME_SECONDS = 6 * 60 * 60
# A phase plans each unit worker to run through its lifetime, this allowance for its host setup and final uploads,
# and its last unit: the pool's longest unit, and at least the unit budget.
UNIT_WORKER_SETUP_AND_UPLOAD_SECONDS = 75 * 60
UNIT_WORKER_LAST_UNIT_SECONDS = work_units.UNIT_SECONDS
UNIT_POLL_SECONDS = 15
# A loop's round trips, its listings, lease writes, launches, cleanups and deletions, run on this many threads, so the
# listings of 200 workers take under a second.
REQUEST_THREADS = 16
# Group completions are downloaded, checked and extracted on this many threads, beside the loop.
ACCEPTANCE_THREADS = 4
# A unit pool's new workers in a market skip a region this long after it found no capacity in that market, or
# refused their request.
REGION_COOL_DOWN_SECONDS = 15 * 60
# How a platform's workers buy capacity, unless the settings name a purchase policy for it: Spot only, waiting for it
# however long; Spot, turning to On-Demand once Spot has fallen short for SPOT_SHORTAGE_WAIT_SECONDS; or On-Demand
# only.
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
# The boot allowance runs from admission to the latest start of the worker's first lease; a worker that has not
# reported by then is stopped, and the leases it was launched with are released.
UNIT_WORKER_BOOT_ALLOWANCE_SECONDS = 20 * 60
# The worker stops once its next lease has been absent this long.
UNIT_WORKER_IDLE_SECONDS = 10 * 60
# From the worker's idle stop until its host powers off: up to 8 minutes of failing lease reads, the final
# heartbeat, the host evidence, and its upload.
UNIT_WORKER_WIND_DOWN_SECONDS = 15 * 60
# From the host's stop time until its cleanup is verified and its reservation released.
UNIT_WORKER_TEARDOWN_SECONDS = 15 * 60
# An instance EC2 has still not terminated this long after the controller asked it to has leaked, which stops the
# fleet's spending until the stop is cleared.
TERMINATION_LEAK_SECONDS = 15 * 60
# A lease's timeout is this multiple of its groups' estimate, or of the unit budget when that is longer.
UNIT_LEASE_TIMEOUT_FACTOR = 2
# A running host whose lease outran its lease timeout from its latest heartbeat has stalled, and loses that lease.
STALLED = "stalled"
# An idle host claims its next lease as its own stop before it stops; a lease the controller writes after the claim
# never runs, so its item is requeued without spending an attempt.
SELF_STOP_CLAIMED = "host claimed the lease as its idle stop"
# A work item stays unresolved once this many of its leases failed, were interrupted, or were lost;
# a lease released before it started does not count.
MAX_UNIT_ATTEMPTS = MAX_REPLACEMENTS_PER_JOB + 1
SEMANTIC_FAILURE_DETAIL = f"semantic failure: status {SEMANTIC_FAILURE_STATUS}"
# The campaign's controller holds this lock in the campaign root for as long as it runs, and once it does,
# records its process ID and start time in the record beside it.
CONTROLLER_LOCK = "controller.lock"
CONTROLLER_RECORD = "controller.txt"
# What only the earlier controllers, which ran each phase as its own process, wrote: the campaign driver's record in
# the campaign root, a phase's own controller's record in its root, and a phase's frozen share of the hosts, or the
# shared fleet policy whose limits it froze, in its execution policy.
OLD_DRIVER_RECORD = "campaign-driver.txt"
OLD_PHASE_RECORD = "driver-child.txt"
OLD_EXECUTION_POLICY_FIELDS = ("max_concurrent", "max_concurrent_per_platform", "fleet_budget", "fleet_policy")
# A unit campaign phase's digest of the tooling that started it, in its result root.
TOOLING_DIGEST = "tooling-digest.txt"
# A unit phase's pauses, in its result root: each one's start and end, which its deadline does not count.
PHASE_PAUSES = "phase-pauses.tsv"
PHASE_PAUSE_FIELDS = ("event", "at")
# Written once a unit phase's controller has verified every worker's cleanup and its phase gate has passed;
# a later phase consumes the result root only once it exists.
PHASE_COMPLETE = "phase-complete.txt"
# The checkout this tooling runs from.
REPOSITORY = Path(__file__).resolve().parents[3]
# The status command's results compare each row with the 1.0 release's, from these repository files.
RELEASE_1_0_BASELINE_TABLES = "docs/benchmarks/results-1.0/baseline-tables.tar.gz"
RELEASE_1_0_REPORT = "benchmark-report/data/regulator-68e42b7d2a104798524189aac7e2a008b49c3d4a-f045d62d4340a0fd.json.gz"
RESULT_FIELDS = ("candidate", "phase", "platform", "row", "ratio", "replicas", "host_ratios", "ratio_1_0", "flags")
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
# Admission reuses a region's vCPU quota this long, so one AWS raises or lowers soon holds.
VCPU_QUOTA_CACHE_SECONDS = 5 * 60
# AWS refused every placement query of the 2.0 run, whose target of 64 instances was above this.
PLACEMENT_SCORE_MAXIMUM_TARGET = 50
# A JDK download slower than this for a minute has stalled.
JDK_DOWNLOAD_MINIMUM_RATE = 256 * 1024
# A shared archive upload that fails, a part stalled past the SDK's read timeout among the causes, is retried rather
# than left to hold every launch behind it.
SHARED_ARCHIVE_UPLOAD_ATTEMPTS = 3
# Each shared bucket keeps every campaign's content-addressed archives under this prefix, which its lifecycle rule
# expires seven days after upload, and each campaign's host sessions under campaigns/<campaign>, which it expires
# after one day. A phase's region check, or a region added to the campaign, puts an archive again once it is older
# than this, and a running phase checks its regions again every REGION_RECHECK_SECONDS, so an archive outlives the
# phase however long it runs.
SHARED_PREFIX = "shared"
SHARED_ARCHIVE_REFRESH_SECONDS = 5 * 24 * 60 * 60
REGION_RECHECK_SECONDS = 24 * 60 * 60
# Every archive the campaign has put in its regions' shared buckets, by key, in the campaign root, so a region added
# later gets each one before any phase may launch there.
SHARED_ARCHIVES = "shared-archives.tsv"
SHARED_ARCHIVE_FIELDS = ("key", "sha256", "path", "label")
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
# Every worker's instance profile, which exists in the account before any campaign, with the policy in
# aws/worker-policy.json, and its user data, which the controller sends with the worker's settings.
WORKER_PROFILE = "regulator-benchmark-worker"
WORKER_USER_DATA = REPOSITORY / BENCHMARK_TOOLING / "aws" / "worker-user-data.sh"
# Every shared bucket, in the region its name ends with, carries this tag and expires objects as this lifecycle
# configuration says.
SHARED_BUCKET_TAGS = {"Project": "regulator-benchmark"}
SHARED_BUCKET_LIFECYCLE = REPOSITORY / BENCHMARK_TOOLING / "aws" / "bucket-lifecycle.json"
# The Trino commit every worker's harness pins.
TRINO_REVISION = "c7503d170344c4e266f03fdb39e53c82aa7e3594"
JOB_MANIFEST_FIELDS = (
    "platform", "shard_id", "replica", "host_epoch", "attempt", "market", "instance_type", "architecture")
JOB_ATTEMPT_FIELDS = ("platform", "shard_id", "replica", "host_epoch", "attempt", "market", "outcome", "detail")
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

    @functools.cached_property
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
    # Regions where the pool's launches in the job's market found no capacity within the cool-down.
    capacity_failures: tuple = ()
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
    parser = argparse.ArgumentParser(
        allow_abbrev=False,
        epilog=f"{Path(__file__).name} status ROOT reports a campaign's or one phase's progress from its ledgers, "
               "and with --results each primary phase's, or the given phase's, ratios so far; "
               f"{Path(__file__).name} control ROOT --campaign CAMPAIGN --actor ACTOR COMMAND changes a campaign's live "
               "settings")
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
    parser.add_argument("--execute", action="store_true",
                        help="launch the campaign's control host, which runs it; a phase alone only plans its work "
                             "items")
    parser.add_argument("--controller", action="store_true",
                        help="with --execute, run the campaign's controller in this process, as the control host's "
                             "unit does")
    parser.add_argument("--ssh-public-key", type=Path,
                        help="the public key ssh reaches the control host with, which launching one requires")
    parser.add_argument("--confirmation-jobs", type=Path)
    parser.add_argument("--smoke-results", type=Path,
                        help="accepted smoke a primary requires (default: the candidate's smoke)")
    parser.add_argument("--primary-results", type=Path,
                        help="accepted primary a confirmation requires (default: the candidate's primary)")
    parser.add_argument("--settings", type=Path,
                        help="the campaign's first live settings, which a campaign's first execution requires and "
                             "seeds as version 1 of its campaign-settings.json; a restart runs under the settings "
                             "the campaign holds")
    parser.add_argument("--spot-vcpu-reserve", type=int, default=SPOT_VCPU_SAFETY_RESERVE,
                        help="the vCPUs of each region's Standard Spot quota the fleet leaves free, frozen when the "
                             "campaign starts")
    parser.add_argument("--release-version", default="",
                        help="measure the pinned published JAR for this version instead of source-built classes")
    parser.add_argument("--smoke-protocol", choices=("smoke", "qualification"), default="smoke",
                        help="use qualification timing in baseline smoke to validate full shard durations")
    parser.add_argument("--phase-timeout-seconds", type=int,
                        help="frozen phase wall-clock limit; otherwise derive from topology plus capacity waits")
    parser.add_argument("--shard", action="append", dest="selected_shards",
                        help="baseline shard to recollect; repeat to select a subset")
    parser.add_argument("--language-plan", action="append", type=Path, default=[],
                        help="language plan prepared by fleet.py, whose units a phase measures instead of the "
                             "baseline's and a campaign measures in language phases beside the baseline's; repeat "
                             "for lifecycle and bulk")
    parser.add_argument("--engine-builds", type=Path,
                        help="accepted build-phase result root whose per-platform prebuilt archives every "
                             "measuring unit worker installs, verified against their pinned SHA-256 (default: the "
                             "candidate's build)")
    return parser.parse_args()


def default_regions():
    return [os.environ.get("AWS_REGION", "us-west-2")]


def live_settings(arguments):
    """The campaign's settings as the controller last loaded them, or None for a phase planned alone before its
    campaign has any."""
    store = getattr(arguments, "settings", None)
    return store.current if store else None


def platform_regions(arguments, platform):
    """The regions the platform's hosts may launch in, in the order its ties go; a phase planned alone, before its
    campaign has settings, plans for the default region."""
    settings = live_settings(arguments)
    return settings["platform_regions"].get(platform, []) if settings else default_regions()


def purchase_policy(arguments, platform):
    settings = live_settings(arguments)
    return settings["purchase"].get(platform, DEFAULT_PURCHASE) if settings else DEFAULT_PURCHASE


def region_bucket(arguments, region):
    """The region's shared transfer bucket, which its hosts read every input from."""
    return live_settings(arguments)["regions"][region]["bucket"]


def campaign_platforms(arguments):
    """The platforms whose workers the phase launches, or before its work is planned, those the settings give
    regions."""
    settings = live_settings(arguments)
    return sorted({platform.name for platform in getattr(arguments, "unit_platforms", {}).values()} or
                  set(settings["platform_regions"] if settings else ()))


def campaign_regions(arguments):
    """Every region a platform of the campaign may launch in."""
    return list(dict.fromkeys(region for platform in campaign_platforms(arguments)
                              for region in platform_regions(arguments, platform))) or default_regions()


def execution_policy(arguments):
    """What a restart must repeat. None of the campaign's settings are part of it: each result records its host's
    region and market, so a phase may move to other regions or buy differently, and its share of the hosts follows
    the work every phase has left."""
    return {"release_version": getattr(arguments, "release_version", ""),
            "smoke_protocol": getattr(arguments, "smoke_protocol", "smoke"),
            "phase_timeout_seconds": getattr(arguments, "topology_deadline_seconds", None),
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


def read_work_items(path):
    with path.open(newline="") as input_file:
        return [WorkItem(row["platform"], row["instance_type"], row["shard_id"], row["unit_id"], int(row["replica"]),
                         float(row["estimated_seconds"]),
                         tuple((group, float(seconds)) for group, seconds in
                               (entry.split(":") for entry in row["groups"].split(","))),
                         frozenset() if row["excluded_instances"] == "-" else frozenset(
                             row["excluded_instances"].split(",")))
                for row in csv.DictReader(input_file, delimiter="\t")]


@dataclass(frozen=True)
class Progress:
    """A pool's work left: the estimated seconds of its groups not yet accepted, the items they belong to, and the
    ratio of measured to estimated seconds over its accepted groups, 1.0 before the first."""
    seconds: float
    items: int
    correction: float

    @property
    def corrected(self):
        return self.seconds * self.correction


def progress(items, accepted, measured, unresolved=()):
    """Each pool's work left, given its work items, their accepted receipts by identity and group, and the measured
    seconds of each group by its unit attempt and group. An item left unresolved has no work left, and a group whose
    measured seconds are unknown, or whose estimate is none, corrects nothing."""
    pools = {}
    for item in items:
        seconds, count, estimated, actual = pools.get(item.pool, (0.0, 0, 0.0, 0.0))
        receipts = accepted.get(item.identity, {})
        left = [estimate for group, estimate in item.groups if group not in receipts]
        if left and item.identity not in unresolved:
            seconds, count = seconds + sum(left), count + 1
        for group, estimate in item.groups:
            receipt = receipts.get(group)
            took = None if receipt is None else measured.get((receipt["unit_attempt_id"], group))
            if took is not None and estimate:
                estimated, actual = estimated + estimate, actual + took
        pools[item.pool] = seconds, count, estimated, actual
    return {pool: Progress(seconds, count, actual / estimated if estimated else 1.0)
            for pool, (seconds, count, estimated, actual) in pools.items()}


def finish_seconds(pools, hosts):
    """The seconds until the work left finishes, its latest pool's corrected seconds over the pool's live hosts, or
    None while a pool with work left has no host."""
    finishes = [left.corrected / hosts[pool] if hosts.get(pool) else None for pool, left in pools.items() if left.items]
    return None if None in finishes else max(finishes, default=0.0)


def language_plans(arguments):
    """Each language plan, validated, by its directory; arguments.language_plans names each directory by its
    shard. A language campaign has no confirmation phase and runs every phase at the qualification protocol."""
    if arguments.phase not in ("smoke", "primary"):
        raise SystemExit("a language campaign runs only smoke and primary phases")
    plans = validate_language_plans(arguments.language_plan)
    arguments.language_plans = {plan["shard_id"]: directory for directory, plan in plans.items()}
    arguments.smoke_protocol = "qualification"
    return plans


def validate_language_plans(directories):
    """Each language plan, validated, by its directory, refusing plans that share a shard."""
    plans, shards = {}, set()
    for directory in directories:
        directory = directory.resolve()
        try:
            plan = language_fleet.validate(directory)
        except (OSError, ValueError, KeyError) as error:
            raise SystemExit(f"invalid language plan {directory}: {error}") from error
        if plan["shard_id"] in shards:
            raise SystemExit(f"language plans share shard {plan['shard_id']}")
        plans[directory] = plan
        shards.add(plan["shard_id"])
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


def unit_worker_job(platform, ordinal, host_epoch, market, capacity_failures=()):
    """A unit worker host of a platform's pool; its replica is the worker's ordinal within the phase."""
    return Job(platform, UNIT_WORKER_SHARD, ordinal, host_epoch, 1, market, "unit-worker",
               capacity_failures=tuple(capacity_failures))


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
    """A worker's planned span: setup, lifetime, uploads, and a last unit as long as the pool's longest."""
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


def validate_configuration(arguments, items, frozen_deadline=None):
    """Check the phase against the campaign's settings and derive its deadline from its work items at the settings'
    limits, or keep the frozen deadline its first start derived, whatever the limits are now. A phase planned alone,
    before its campaign has settings, is checked against no limits."""
    campaign_heap_size(arguments)
    if getattr(arguments, "spot_vcpu_reserve", SPOT_VCPU_SAFETY_RESERVE) < 0:
        raise SystemExit("Spot vCPU reserve must not be negative")
    settings = live_settings(arguments)
    amis = getattr(arguments, "regional_amis", None)
    for platform in campaign_platforms(arguments):
        listed = platform_regions(arguments, platform)
        if not listed or not all(re.fullmatch(REGION_PATTERN, region) for region in listed):
            raise SystemExit(f"{platform} needs launch regions named in the campaign settings' platform_regions: "
                             f"{listed}")
        # A platform launches only in regions holding its image.
        missing = [region for region in listed if amis is not None and (platform, region) not in amis]
        if missing:
            raise SystemExit(f"platform-regions.tsv has no {platform} AMI for its regions: {missing}")
    if getattr(arguments, "execute", False) and settings is None:
        raise SystemExit("launching hosts requires the campaign's settings")
    unit_platforms = getattr(arguments, "unit_platforms", {})
    workers = [unit_platforms[pool] for pool in sorted({item.pool for item in items}) if pool in unit_platforms]
    validate_campaign_id(arguments.campaign_id)
    if arguments.phase == "primary" and not items:
        raise SystemExit("primary campaign has no work items")
    required_seconds = 0
    if settings:
        launchable = {platform.name: platform_regions(arguments, platform.name) for platform in workers}
        unpriced = sorted({platform.instance_type for platform in workers} - set(settings["hourly_rates"]))
        if unpriced:
            raise SystemExit(f"the campaign settings' hourly_rates price no {', '.join(unpriced)} hour")
        on_demand = set(on_demand_regions(settings))
        refused = sorted({platform.name for platform in workers
                          if purchase_policy(arguments, platform.name) != "spot" and
                          not on_demand & set(launchable[platform.name])})
        if refused:
            raise SystemExit(f"the campaign settings allow no On-Demand in a region of {', '.join(refused)}; give "
                             "one of its regions an On-Demand allowance, or set its purchase to spot")
        regions = settings["regions"]
        if any(platform.vcpus > max(regions[region]["max_vcpus"] for region in launchable[platform.name])
               for platform in workers):
            raise SystemExit("the campaign settings' vCPU limits cannot admit every worker type")
        # A wave fills every allowed region's limit at once.
        fleet_vcpus = sum(regions[region]["max_vcpus"] for region in campaign_regions(arguments))
        required_seconds = unit_phase_seconds(arguments, items, settings["max_hosts"],
                                              settings["max_hosts_per_platform"], fleet_vcpus)
    explicit_deadline = getattr(arguments, "phase_timeout_seconds", None)
    if frozen_deadline is not None:
        arguments.topology_deadline_seconds = frozen_deadline
    else:
        arguments.topology_deadline_seconds = (explicit_deadline if explicit_deadline is not None else
                                              max(PHASE_TIMEOUT_SECONDS[arguments.phase], required_seconds + 3600))
    if frozen_deadline is None and arguments.topology_deadline_seconds < required_seconds:
        raise SystemExit("campaign topology cannot fit its phase deadline with one retry wave: "
                         f"requires {required_seconds}s, allows {arguments.topology_deadline_seconds}s")
    if getattr(arguments, "language_plans", None) and campaign_heap_size(arguments) != "8g":
        raise SystemExit("language protocol requires an 8g heap")


def unit_phase_seconds(arguments, items, host_limit, per_platform, fleet_vcpus):
    """The wall clock the phase's units need: worker lifetimes of work at the concurrency limits,
    plus one retry wave, each wave a worker's full planned span."""
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
    # The policy binds each manifest's SHA-256 and each host checks the archives against
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


def validate_aws(arguments, platforms, jobs=None):
    required_platforms = list({
        (platform.name, platform.instance_type): platform
        for platform in ([job.platform for job in jobs] if jobs is not None else platforms)
    }.values())
    if not required_platforms:
        required_platforms = platforms
    regions = list(dict.fromkeys(campaign_regions(arguments) + [
        region for platform in required_platforms for region in platform_regions(arguments, platform.name)]))
    aws = arguments.cloud
    settings = live_settings(arguments)
    # Every worker runs under the account's permanent worker profile, which the controller never creates.
    if not aws.instance_profile(regions[0], WORKER_PROFILE):
        raise SystemExit(f"the account has no {WORKER_PROFILE} instance profile; create it as aws/README.md "
                         "\"Worker profile\" describes")
    spot_quotas, on_demand_quotas = {}, {}
    # The controller launches into a public subnet of each region's default VPC. A check describes the account
    # afresh, and launches reuse what it found.
    for cache in ("networks", "images", "zone_offerings"):
        vars(arguments).setdefault(cache, {})
    public_zones = {}
    for region in regions:
        public_zones[region] = {zone for _, zone in region_network(arguments, region).subnets}
        if not public_zones[region]:
            raise SystemExit(f"allowed region {region} has no public default-VPC subnet")
        spot_quota, on_demand_quota = (vcpu_quota(arguments, region, market, fresh=True)
                                       for market in ("spot", "on-demand"))
        limits = settings["regions"][region]
        if limits["max_vcpus"] > spot_quota - arguments.spot_vcpu_reserve:
            raise SystemExit(f"the campaign settings' max_vcpus exceeds the Standard Spot quota after its reserve in "
                             f"{region}")
        # A region without an On-Demand allowance never launches On-Demand, whatever its quota.
        if ("on_demand_max_vcpus" in limits and
                limits["on_demand_max_vcpus"] > on_demand_quota - ON_DEMAND_VCPU_SAFETY_RESERVE):
            raise SystemExit(f"the campaign settings' on_demand_max_vcpus exceeds the Standard On-Demand quota after "
                             f"its reserve in {region}")
        spot_quotas[region], on_demand_quotas[region] = spot_quota, on_demand_quota
    # Each platform needs room for one host, after the safety reserves, in a region of its list in a market it buys.
    for platform in required_platforms:
        listed = platform_regions(arguments, platform.name)
        spot = any(spot_quotas[region] >= arguments.spot_vcpu_reserve + platform.vcpus for region in listed)
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
            image = region_image(arguments, PINNED_AMI_REGION, platform.ami_id)
            if (image is None or (image.architecture, image.state, image.owner) !=
                    (expected_architecture, "available", AMAZON_AMI_OWNER) or not image.name):
                raise SystemExit(f"unexpected AMI identity for {platform.name}: {image}")
            names[platform.name] = image.name
        offered = []
        for region in platform_regions(arguments, platform.name):
            if not zones_offering(arguments, region, platform.instance_type) & public_zones[region]:
                continue
            ami_id = platform.ami_id if amis is None else amis.get((platform.name, region))
            if ami_id is None:
                raise SystemExit(f"platform-regions.tsv has no {platform.name} AMI for {region}")
            if (platform.name, region) not in verified and (region, ami_id) != (PINNED_AMI_REGION, platform.ami_id):
                image = region_image(arguments, region, ami_id)
                if image is None or (image.architecture, image.state, image.owner, image.name) != (
                        expected_architecture, "available", AMAZON_AMI_OWNER, names[platform.name]):
                    raise SystemExit(f"{platform.name} AMI {ami_id} in {region} is not {names[platform.name]}: {image}")
            verified.add((platform.name, region))
            offered.append(region)
        if not offered:
            raise SystemExit(f"{platform.instance_type} is not offered in any region of {platform.name}'s list")
        offerings[platform.instance_type] = offered
    for region in regions:
        check_bucket(aws, region, region_bucket(arguments, region))
    arguments.ami_names = names
    arguments.region_offerings = offerings


def check_bucket(aws, region, bucket):
    info = aws.bucket(region, bucket)
    if info.region != region or any(info.tags.get(key) != value for key, value in SHARED_BUCKET_TAGS.items()):
        raise SystemExit(f"shared transfer bucket {bucket} is not a tagged Project=regulator-benchmark "
                         f"bucket in {region}: location={info.region}")


def check_regions(arguments, platforms):
    """Check AWS for the phase's workers in every region its settings list, and put what they read in each region
    that may launch one, as its region_offerings then name them. A phase launches in a region only once this passed
    for it: the controller checks again for a running phase whenever the settings' regions change, on a copy of its
    namespace, and adopts the regions only once the check passes."""
    workers = [unit_worker_job(platform, 0, 0, "spot") for _, platform in sorted(arguments.unit_platforms.items())]
    validate_aws(arguments, platforms, workers)
    stage_jdks(arguments, workers)
    upload_candidate_archive(arguments, workers)
    upload_engine_builds(arguments, workers)
    if arguments.language_plan:
        upload_language_bundles(arguments, workers)


def engine_archives(arguments, platform):
    """The archives of a platform's engine build that its measuring workers install, by name, for the phase's
    units: a baseline unit runs the candidate's classes, the Trino harness, and the native runners, and a language
    unit the classes and the native runners."""
    manifest = engine_build.load_manifest(arguments.engine_build_directories[platform],
                                          arguments.engine_build_policy[platform])
    kind = "language" if getattr(arguments, "language_plans", None) else "baseline"
    return {name: manifest["archives"][name] for name in engine_build.UNIT_ARCHIVES[kind]}


def upload_candidate_archive(arguments, jobs):
    """Put the frozen candidate archive, which every worker unpacks first, once in every region that may launch
    one, under its SHA-256."""
    sha256 = arguments.candidate_archive_sha256
    upload_shared_archives(arguments, {
        (region, f"{SHARED_PREFIX}/candidates/{sha256}.tar.gz"): (
            arguments.candidate_archive, sha256, "candidate archive")
        for job in jobs for region in launch_regions(arguments, job)})


def upload_engine_builds(arguments, jobs):
    """Put each engine archive its workers install once in every region that may launch one of them."""
    directories = getattr(arguments, "engine_build_directories", None)
    if not directories:
        return
    uploads = {}
    for job in jobs:
        for name, archive in engine_archives(arguments, job.platform.name).items():
            for region in launch_regions(arguments, job):
                uploads[region, f"{SHARED_PREFIX}/engine-builds/{archive['sha256']}.tar.zst"] = (
                    directories[job.platform.name] / archive["file"], archive["sha256"],
                    f"{job.platform.name} {name} archive")
    upload_shared_archives(arguments, uploads)


def upload_language_bundles(arguments, jobs):
    """Put each language plan's bundle once in every region that may launch a worker of a pool that measures it,
    and the candidate provenance their units measure, beside the candidate archive."""
    bundles = arguments.language_bundles
    provenance = candidate_provenance_key(arguments)
    uploads = {(region, provenance[0]): (arguments.candidate_provenance, provenance[1], "candidate provenance")
               for job in jobs for region in launch_regions(arguments, job)}
    upload_shared_archives(arguments, {**uploads, **{
        (region, f"{SHARED_PREFIX}/language-plans/{bundles[shard][1]}.tar.zst"): (
            bundles[shard][0], bundles[shard][1], f"{shard} language plan bundle")
        for job in jobs for shard in arguments.pool_shards[unit_worker_pool(job)]
        for region in launch_regions(arguments, job)}})


def candidate_provenance_key(arguments):
    """The shared key of the language candidate's provenance, and its SHA-256."""
    sha256 = engine_build.sha256_file(arguments.candidate_provenance)
    return f"{SHARED_PREFIX}/candidates/{sha256}.tsv", sha256


def jdk_archive(campaign_root, platform):
    return campaign_root / "jdks" / f"{platform.jdk_sha256}.tar.gz"


def stage_jdks(arguments, jobs):
    """Download each pinned JDK once for the campaign, verified against its pin, into the campaign root, where
    every phase's workers take it from, and put it in the shared transfer bucket of every region that may launch a
    worker installing it."""
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
            subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location", "--retry", "5",
                            "--connect-timeout", "60", "--speed-limit", str(JDK_DOWNLOAD_MINIMUM_RATE),
                            "--speed-time", "60", "--output", str(download), job.platform.jdk_url], check=True)
            if engine_build.sha256_file(download) != job.platform.jdk_sha256:
                download.unlink()
                raise SystemExit(f"the {job.platform.name} JDK at {job.platform.jdk_url} differs from its pin")
            os.replace(download, path)
    for job in jobs:
        path = jdk_archive(arguments.campaign_root, job.platform)
        for region in launch_regions(arguments, job):
            uploads[region, f"{SHARED_PREFIX}/jdks/{job.platform.jdk_sha256}.tar.gz"] = (
                path, job.platform.jdk_sha256, f"{job.platform.name} JDK")
    upload_shared_archives(arguments, uploads)


def upload_shared_archives(arguments, uploads):
    """Put each content-addressed archive, {(region, key): (path, sha256, label)}, in its region's transfer bucket,
    and record it among the campaign's shared archives."""
    path = arguments.campaign_root / SHARED_ARCHIVES
    recorded = {row["key"] for row in read_shared_archives(path)}
    for (region, key), (archive, sha256, label) in sorted(uploads.items()):
        put_shared_archive(arguments.cloud, region, region_bucket(arguments, region), key, archive, sha256, label)
        if key not in recorded:
            append_ledger(path, SHARED_ARCHIVE_FIELDS, {"key": key, "sha256": sha256, "path": archive, "label": label})
            recorded.add(key)


def read_shared_archives(path):
    if not path.is_file():
        return []
    return list(csv.DictReader(ledger_lines(path), delimiter="\t"))


def put_shared_archive(aws, region, bucket, key, path, sha256, label):
    """Put the archive in the bucket unless it holds it already, as its SHA-256 says, with days to go before its
    lifecycle rule expires it."""
    for attempt in range(1, SHARED_ARCHIVE_UPLOAD_ATTEMPTS + 1):
        try:
            existing = aws.head(region, bucket, key)
        except CloudError:
            existing = None
        if existing and existing.sha256 == sha256 and existing.age < SHARED_ARCHIVE_REFRESH_SECONDS:
            return
        print(f"uploading {label} to {region} (attempt {attempt})", flush=True)
        try:
            aws.upload(region, bucket, key, path, sha256)
            return
        except CloudError as error:
            if attempt == SHARED_ARCHIVE_UPLOAD_ATTEMPTS:
                raise SystemExit(f"could not upload the {label} to s3://{bucket}/{key}: {error}") from error


def spot_placement_scores(arguments, instance_type, regions):
    """Return each region's 1-10 Spot placement score, or None when AWS cannot say."""
    if not hasattr(arguments, "placement_scores"):
        arguments.placement_scores = {}
    # A target the limits set keeps every query the same configuration while they hold.
    settings = live_settings(arguments)
    target = min(PLACEMENT_SCORE_MAXIMUM_TARGET, settings["max_hosts"], settings["max_hosts_per_platform"])
    key = (instance_type, tuple(regions), target)
    cached = arguments.placement_scores.get(key)
    now = time.monotonic()
    if cached is not None and now - cached[0] < PLACEMENT_SCORE_CACHE_SECONDS:
        return cached[1]
    try:
        scores = {region: score for region, score in
                  arguments.cloud.placement_scores(instance_type, regions, target).items()
                  if region in regions and 1 <= score <= 10}
    except CloudError:
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
    # A launch skips the regions where its pool's launches in its market found no capacity within the cool-down.
    eligible = [region for region in regions if region not in job.capacity_failures]
    if job.market != "spot" or len(eligible) <= 1:
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


def is_standard_instance_type(instance_type):
    return bool(instance_type) and instance_type[0].lower() in STANDARD_INSTANCE_FAMILIES


def vcpu_quota(arguments, region, market, fresh=False):
    """The region's Standard vCPU quota for the market, asked again once it is VCPU_QUOTA_CACHE_SECONDS old, or when
    fresh, as a check of a phase's regions asks; the campaign's phases and their admissions share it."""
    quotas = vars(arguments).setdefault("vcpu_quotas", {})
    cached, now = quotas.get((region, market)), time.monotonic()
    if fresh or cached is None or now - cached[0] >= VCPU_QUOTA_CACHE_SECONDS:
        cached = quotas[region, market] = (now, arguments.cloud.vcpu_quota(region, market))
    return cached[1]


def region_network(arguments, region):
    """The region's default network, described once per controller."""
    networks = vars(arguments).setdefault("networks", {})
    if region not in networks:
        networks[region] = arguments.cloud.network(region)
    return networks[region]


def region_image(arguments, region, ami):
    """The region's image, described once per controller, or None where it has none."""
    images = vars(arguments).setdefault("images", {})
    if (region, ami) not in images:
        images[region, ami] = arguments.cloud.image(region, ami)
    return images[region, ami]


def zones_offering(arguments, region, instance_type):
    """The region's zones offering the instance type, described once per controller."""
    offerings = vars(arguments).setdefault("zone_offerings", {})
    if (region, instance_type) not in offerings:
        offerings[region, instance_type] = arguments.cloud.zones_offering(region, instance_type)
    return offerings[region, instance_type]


def described(arguments, observed, region, kind):
    """The region's instances or its open and active Spot requests, as kind names them, described once into observed.

    The dispatcher observes the fleet once a loop. A host launched later in the loop is covered by its fleet
    reservation until it appears, and one gone since is counted as still running, so a loop-old description only
    ever overstates the fleet."""
    if (region, kind) not in observed:
        observed[region, kind] = getattr(arguments.cloud, kind)(region)
    return observed[region, kind]


def shared_fleet_snapshot(arguments, region, market, observed):
    """The region's Standard quota for the market and the pending and running Standard instances, and for Spot the
    open and active requests, that use it, each with its vCPUs and the campaign and host epoch its tags name."""
    spot = market == "spot"
    instances = [instance for instance in described(arguments, observed, region, "instances")
                 if instance.state in ("pending", "running") and (instance.lifecycle == "spot") == spot]
    requests = described(arguments, observed, region, "spot_requests") if spot else []
    if any(not item.type for item in instances + requests):
        raise AccountMismatch(f"cannot account for {'a Spot instance or request' if spot else 'an On-Demand instance'} "
                              "with no instance type")
    instances = [item for item in instances if is_standard_instance_type(item.type)]
    requests = [item for item in requests if is_standard_instance_type(item.type)]
    # An instance type's vCPUs are the same in every region and never change.
    vcpus = vars(arguments).setdefault("instance_vcpus", {})
    if missing := {item.type for item in instances + requests} - set(vcpus):
        vcpus.update(arguments.cloud.vcpus(region, missing))
    if any(item.type not in vcpus for item in instances + requests):
        raise AccountMismatch("AWS omitted requested vCPU metadata")

    def resource(item, **fields):
        return {**fields, "type": item.type, "vcpus": vcpus[item.type],
                "campaign_id": item.tags.get("BaselineCampaign"), "host_epoch": item.tags.get("HostEpoch")}

    return {"quota": vcpu_quota(arguments, region, market),
            "instances": [resource(item, id=item.id) for item in instances],
            "requests": [resource(item, id=item.id, instance_id=item.instance_id or None) for item in requests]}


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


def worker_session(campaign_id, job):
    """A new worker's host session: its campaign, host epoch, and the second the controller chose it, so no two
    hosts, of any result root, share a prefix or a client token."""
    return f"{campaign_id}-e{job.host_epoch}-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime(time.time()))}"


def worker_metadata(arguments, job, session, prefix):
    """Where a worker's host exchanges leases and results: its session's prefix in its region's shared bucket."""
    return {"session": session, "aws_region": job.region, "bucket": region_bucket(arguments, job.region),
            "prefix": prefix, "input_prefix": f"{prefix}/input", "result_prefix": f"{prefix}/results",
            "placement_score": job.placement_score, "instance_id": "", "availability_zone": ""}


def candidate_content_sha256(arguments):
    """The SHA-256 of the candidate archive's uncompressed content, computed once per controller."""
    if "candidate_content_sha256" not in vars(arguments):
        with gzip.open(arguments.candidate_archive, "rb") as content:
            arguments.candidate_content_sha256 = hashlib.file_digest(content, "sha256").hexdigest()
    return arguments.candidate_content_sha256


def worker_user_data(arguments, job, metadata):
    """The worker's EC2 user data: aws/worker-user-data.sh with every setting it and the host scripts read exported
    after its first line."""
    platform = job.platform
    bucket = metadata["bucket"]
    archives = [("jdk", platform.jdk_sha256, f"{SHARED_PREFIX}/jdks/{platform.jdk_sha256}.tar.gz")]
    # Only a measuring phase's workers install engine builds.
    manifest = ""
    if getattr(arguments, "engine_build_directories", None):
        manifest = arguments.engine_build_policy[platform.name]
        archives += [(name, archive["sha256"], f"{SHARED_PREFIX}/engine-builds/{archive['sha256']}.tar.zst")
                     for name, archive in engine_archives(arguments, platform.name).items()]
    provenance = ("", "")
    if bundles := getattr(arguments, "language_bundles", None):
        # A language worker installs the bundles of its pool's plans and the candidate provenance their units measure.
        archives += [("language-plan", bundles[shard][1],
                      f"{SHARED_PREFIX}/language-plans/{bundles[shard][1]}.tar.zst")
                     for shard in arguments.pool_shards[unit_worker_pool(job)]]
        key, sha256 = candidate_provenance_key(arguments)
        provenance = (f"s3://{bucket}/{key}", sha256)
    amis = getattr(arguments, "regional_amis", None)
    settings = {
        "HOST_STOP_BY": job.stop_by,
        "HOST_ARCHIVE_URI": f"s3://{bucket}/{metadata['result_prefix']}/host.tar.gz",
        "CANDIDATE_URI": f"s3://{bucket}/{SHARED_PREFIX}/candidates/{arguments.candidate_archive_sha256}.tar.gz",
        "LANGUAGE_CANDIDATE_PROVENANCE_URI": provenance[0],
        "LANGUAGE_CANDIDATE_PROVENANCE_SHA256": provenance[1],
        "ENGINE_BUILD_MANIFEST_SHA256": manifest,
        # Each archive the host streams into place, one line each: its name, its SHA-256, and its S3 URI.
        "HOST_ARCHIVES": "".join(f"{name} {digest} s3://{bucket}/{key}\n" for name, digest, key in archives),
        "REGULATOR_SNAPSHOT_COMMIT": arguments.candidate_commit,
        "REGULATOR_SNAPSHOT_SHA256": arguments.candidate_archive_sha256,
        "REGULATOR_CONTENT_SHA256": candidate_content_sha256(arguments),
        "TRINO_SNAPSHOT_COMMIT": TRINO_REVISION,
        "CAMPAIGN_PROVENANCE": "qualification",
        "RE2_CAMPAIGN_ID": arguments.campaign_id,
        "RE2_CAMPAIGN_PLATFORM": platform.name,
        "RE2_CAMPAIGN_SHARD_ID": job.shard,
        "RE2_CAMPAIGN_REPLICA_ID": job.replica,
        "RE2_CAMPAIGN_HOST_EPOCH": job.host_epoch,
        "BASELINE_PROTOCOL": phase_protocol(arguments),
        "BASELINE_PROTOCOL_QUALIFICATION": "true",
        "BASELINE_EXPECTED_ENGINE_TREE": campaign_engine_tree(arguments),
        "NATIVE_COMPILER_PACKAGE": platform.native_compiler_package,
        "CMAKE_PACKAGE": platform.cmake_package,
        "GLIBC_PACKAGE": platform.glibc_package,
        "CARGO_PACKAGE": platform.cargo_package,
        "RUST_PACKAGE": platform.rust_package,
        "TIME_PACKAGE": platform.time_package,
        "ZSTD_PACKAGE": platform.zstd_package,
        "JONI_EVIDENCE_SCOPE": "unit-shard-comparison",
        "EXPECTED_AMI_ID": platform.ami_id if amis is None else amis[platform.name, job.region],
        "EXPECTED_AMI_NAME": getattr(arguments, "ami_names", {}).get(platform.name, ""),
        "RE2_ENGINEERING_ARCHITECTURE": platform.architecture,
        "RE2_BENCHMARK_MODE": job.workload,
        "INSTANCE_MARKET_TYPE": job.market,
        "REGULATOR_RELEASE_VERSION": getattr(arguments, "release_version", ""),
        "BENCHMARK_JAVA_ARCHIVE_URL": platform.jdk_url,
        "BENCHMARK_JAVA_ARCHIVE_SHA256": platform.jdk_sha256,
        "JONI_COMPARATOR_ORDER": "forward" if job.replica % 2 else "reverse",
        "BENCHMARK_HEAP_SIZE": campaign_heap_size(arguments),
        "BENCHMARK_CPU_LIST": "0",
        "BENCHMARK_EXPECTED_VCPUS": platform.vcpus,
        "RE2_CAMPAIGN_HOST_SESSION": metadata["session"],
        "BASELINE_REGION": metadata["aws_region"],
        "UNIT_WORKER_BUCKET": bucket,
        "UNIT_WORKER_LEASE_PREFIX": f"{metadata['input_prefix']}/leases",
        "UNIT_WORKER_RESULT_PREFIX": metadata["result_prefix"],
        "UNIT_WORKER_LIFETIME_SECONDS": UNIT_WORKER_LIFETIME_SECONDS,
        "UNIT_WORKER_IDLE_SECONDS": UNIT_WORKER_IDLE_SECONDS,
    }
    shebang, body = WORKER_USER_DATA.read_text().split("\n", 1)
    return "".join([shebang, "\n", *(f"export {name}={shlex.quote(str(value))}\n" for name, value in settings.items()),
                    body])


class LaunchFailed(Exception):
    """AWS launched no instance for the worker: its attempt's classification and detail."""

    def __init__(self, classification, detail):
        super().__init__(detail)
        self.classification = classification
        self.detail = detail


def launch_failure(market, rejection, elsewhere=False):
    """The classification and detail of a launch AWS refused: a Spot launch found no Spot capacity, quota or price,
    and an On-Demand one no capacity or vCPU quota. A request AWS will never grant fails as the infrastructure's,
    with AWS's explanation: as the region's refusal while its pool has another region elsewhere to try, and
    otherwise so its slot's replacements run out."""
    if rejection.kind == "request":
        prefix = REGION_REFUSAL if elsewhere else "launch-infrastructure:"
        return "retryable-infrastructure", f"{prefix}{rejection.code}: {rejection.message}"
    if market == "spot":
        return "retryable-spot", f"launch-capacity:{rejection.code}"
    if rejection.kind in ("capacity", "quota"):
        return "retryable-capacity", f"launch-capacity:{rejection.code}"
    return "retryable-infrastructure", f"launch-infrastructure:{rejection.code}"


def check_purchase(arguments, job):
    """Refuse a job in a market its platform does not buy."""
    purchase = purchase_policy(arguments, job.platform.name)
    if purchase == "spot" and job.market != "spot":
        raise UnsafeCampaignError(f"{job.platform.name} buys only Spot, so it may not launch an On-Demand job")
    if purchase == "on-demand" and job.market != "on-demand":
        raise UnsafeCampaignError(f"{job.platform.name} buys only On-Demand, so it may not launch a Spot job")


def launch_requests(arguments, job, metadata):
    """The worker's launch requests, as (zone, launch spec), one per public subnet of its region that offers its type,
    each zone in turn from the one the worker's identity picks. Its region's network, offerings and image are
    described once per controller, so only the launch itself is a round trip. Raises LaunchFailed when no subnet
    offers the type."""
    region, instance_type, session = job.region, job.platform.instance_type, metadata["session"]
    network = region_network(arguments, region)
    offered = zones_offering(arguments, region, instance_type)
    subnets = sorted((zone, subnet) for subnet, zone in network.subnets if zone in offered)
    if not subnets:
        raise LaunchFailed("retryable-infrastructure",
                           f"launch-preflight:no public default-VPC subnet offers {instance_type}")
    amis = getattr(arguments, "regional_amis", None)
    ami = job.platform.ami_id if amis is None else amis[job.platform.name, region]
    root_device = region_image(arguments, region, ami).root_device
    user_data = worker_user_data(arguments, job, metadata)
    tags = {"Name": f"re2-qualification-{job.platform.architecture}-{session}", "Project": "re2-port-benchmark",
            "BaselineCampaign": arguments.campaign_id, "Phase": arguments.phase, "Platform": job.platform.name,
            "Shard": job.shard, "Replica": str(job.replica), "HostEpoch": str(job.host_epoch), "Session": session}
    max_price = str(live_settings(arguments)["hourly_rates"][instance_type]["spot"]) if job.market == "spot" else ""
    first = int(hashlib.sha256(job.logical_identity.encode()).hexdigest(), 16) % len(subnets)
    # Each zone has its own token, since a token names one exact request.
    return [(zone, cloud.LaunchSpec(ami, instance_type, root_device, subnet, network.security_group, WORKER_PROFILE,
                                    job.market, max_price, user_data, tags,
                                    hashlib.sha256(f"{session}/{zone}".encode()).hexdigest()))
            for zone, subnet in subnets[first:] + subnets[:first]]


def launch_worker(aws, job, requests, elsewhere=False):
    """Launch the worker's instance by its requests in turn while AWS reports no capacity in their zone. Returns the
    instance ID, or "" when AWS's answer never came and only the session's tag can find the instance, and the zone;
    raises LaunchFailed when AWS launched none, its pool having another region elsewhere to try."""
    for offset, (zone, spec) in enumerate(requests):
        try:
            return aws.run_instance(job.region, spec), zone
        except cloud.LaunchUnknown:
            return "", zone
        except cloud.LaunchRejected as rejection:
            if rejection.kind != "capacity" or offset + 1 == len(requests):
                raise LaunchFailed(*launch_failure(job.market, rejection, elsewhere)) from rejection


def recover_uploads(aws, metadata, objects):
    """The second half of a worker's cleanup proof, once its instance has terminated: download every object left in
    its prefix, those of its result prefix into objects by their key below it, then delete the prefix and find it
    empty. Returns whether that held; a failed AWS call or write leaves the rest for another try, and a file already
    downloaded whole is kept."""
    region, bucket, prefix = metadata["aws_region"], metadata["bucket"], metadata["prefix"] + "/"
    results = metadata["result_prefix"] + "/"
    try:
        listed = aws.list(region, bucket, prefix)
        for key, (size, _) in sorted(listed.items()):
            if not key.startswith(results):
                continue
            relative = key[len(results):]
            path = objects / relative
            if not relative or any(part in ("", ".", "..") for part in relative.split("/")):
                raise UnsafeCampaignError(f"worker {metadata['session']} uploaded an unsafe key: {key}")
            if path.is_file() and path.stat().st_size == size:
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            download = path.with_name(path.name + ".download")
            aws.get(region, bucket, key, download)
            if download.stat().st_size != size:
                return False
            os.replace(download, path)
        if listed:
            aws.delete(region, bucket, list(listed))
        return not aws.list(region, bucket, prefix)
    except (CloudError, OSError):
        return False


def read_properties(path):
    values = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise RuntimeError(f"invalid property line in {path}: {line!r}")
        values[key] = value
    return values


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


def boot_to_first_unit(directory, job):
    """Seconds from a worker's boot to the start of its first unit, by the startup timeline its host uploaded, or
    None when the host uploaded none or never began a unit."""
    path = directory / job.platform.architecture / "re2-results" / "startup-timeline.tsv"
    try:
        with path.open(newline="") as input_file:
            steps = {row["step"]: row for row in csv.DictReader(input_file, delimiter="\t")}
        return float(steps["worker"]["finished"]) - float(steps["boot"]["started"])
    except (OSError, KeyError, TypeError, ValueError):
        return None


def write_attempt(path, job, outcome, detail="-"):
    append_ledger(path, JOB_ATTEMPT_FIELDS, dict(zip(JOB_ATTEMPT_FIELDS, (
        job.platform.name, job.shard, job.replica, job.host_epoch, job.attempt, job.market, outcome,
        detail.replace("\t", " ")))))


def write_event(path, event_type, campaign_id, job, worker, reason, details="", unit_id=None, unit_attempt_id="",
                failure_classification=""):
    """Append one campaign event about a worker; a unit campaign's events, given a unit_id, name the unit and
    attempt."""
    metadata = worker.metadata
    fields = (
        "event_type", "campaign_id", "platform", "shard_id", "replica_id", "instance_id",
        "host_epoch", "reason", "artifact_uri", "artifact_sha256", "region", "availability_zone",
        "placement_score", "failure_classification", "details",
    )
    if unit_id is not None:
        index = fields.index("host_epoch") + 1
        fields = (*fields[:index], "unit_id", "unit_attempt_id", *fields[index:])
    if path.exists():
        # Resumed campaigns keep the event columns their file started with.
        with path.open(newline="") as input_file:
            fields = tuple(csv.DictReader(input_file, delimiter="\t").fieldnames or fields)
    event = {
            "event_type": event_type,
            "campaign_id": campaign_id,
            "platform": job.platform.name,
            "shard_id": job.shard,
            "replica_id": str(job.replica),
            "instance_id": metadata["instance_id"],
            "host_epoch": str(job.host_epoch),
            "unit_id": unit_id or "",
            "unit_attempt_id": unit_attempt_id,
            "reason": reason,
            "artifact_uri": str(worker.directory),
            "artifact_sha256": "",
            "region": metadata["aws_region"],
            "availability_zone": metadata["availability_zone"],
            "placement_score": metadata["placement_score"],
            "failure_classification": failure_classification,
            "details": details.replace("\t", " "),
    }
    append_ledger(path, fields, {field: value for field, value in event.items() if field in fields})


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


@dataclass
class GroupEvidence:
    """What the acceptance threads found of one group's completion: its marker, or why the marker stops the campaign;
    and where the group needed its result, why the result was rejected, or its extracted directory with, for a failed
    group, its capacity record, and for a complete one its receipt or why its artifacts were not accepted."""
    marker: dict = None
    problem: str = None
    rejection: str = None
    directory: Path = None
    capacity: dict = None
    receipt: dict = None
    invalid: str = None


def gather_group(validation, result_root, staging, item, expected, needs_result, launched, fetch):
    """Download and check one group's completion, on an acceptance thread: its marker against expected, the fields
    its lease gives it, and unless the group needs no result or failed semantically, its result against the marker,
    extracted into staging, and its receipt against the lease, the region and market the worker launched in, and the
    campaign. Reads no controller state and writes only staging; raises CloudError or OSError for a download the next
    poll retries."""
    sequence, group, attempt = expected["sequence"], expected["group_id"], expected["unit_attempt_id"]
    context = f"{attempt} group {group}"
    marker_path = staging / f"lease-{sequence}-group-{group}-completion.json"
    fetch(completion_key(sequence, group), marker_path)
    try:
        marker = json.loads(marker_path.read_text())
    except ValueError:
        return GroupEvidence(problem=f"unit completion marker {context} is not JSON")
    absent = [key for key in UNIT_COMPLETION_FIELDS if key not in marker]
    if absent:
        return GroupEvidence(problem=f"unit completion marker {context} lacks {absent}")
    mismatches = [key for key, value in expected.items() if marker.get(key) != value]
    if mismatches:
        return GroupEvidence(problem=f"unit completion marker {context} does not match its lease: {mismatches}")
    evidence = GroupEvidence(marker)
    if not needs_result or marker.get("exit_status") == SEMANTIC_FAILURE_STATUS:
        return evidence
    archive = staging / f"lease-{sequence}-group-{group}.tar.gz"
    fetch(result_key(sequence, group), archive)
    if engine_build.sha256_file(archive) != marker.get("result_sha256"):
        evidence.rejection = "result archive does not match its completion marker"
        return evidence
    directory = staging / f"lease-{sequence}-group-{group}"
    try:
        extract_unit_result(archive, directory)
    except (OSError, tarfile.TarError) as error:
        evidence.rejection = f"result archive is unreadable: {error}"
        return evidence
    evidence.directory = directory
    if marker.get("unit_status") != "complete" or marker.get("exit_status") != 0:
        try:
            evidence.capacity = read_properties(directory / "unit-capacity.txt")
        except (OSError, RuntimeError):
            evidence.capacity = {}
        return evidence
    try:
        receipt = read_unit_receipt(directory / "unit-receipt.tsv")
        if (unit_logical_identity(receipt), receipt["group_id"], receipt["unit_attempt_id"]) != (
                item.identity, group, attempt):
            raise RuntimeError(f"unit receipt names {group_identity(receipt)} attempt {receipt['unit_attempt_id']}, "
                               f"expected {item.identity}/group-{group} attempt {attempt}")
        # The host's receipt names the region and market it ran in, which must be those the worker launched in.
        if (receipt["region"], receipt["market"]) != launched:
            raise RuntimeError(f"unit receipt names a {receipt['market']} host in {receipt['region']}, but the "
                               f"worker launched as {launched[1]} in {launched[0]}")
        validate_unit_artifacts(validation, result_root, receipt, directory)
    except (OSError, RuntimeError) as error:
        evidence.invalid = str(error)
        return evidence
    evidence.receipt = receipt
    return evidence


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
        persist_accepted_unit(accepted_units, receipt)
        groups[receipt["group_id"]] = receipt
    return accepted


def read_attempts(path):
    if not path.exists():
        return []
    reader = csv.DictReader(ledger_lines(path), delimiter="\t")
    if tuple(reader.fieldnames or ()) != JOB_ATTEMPT_FIELDS:
        raise UnsafeCampaignError(f"unexpected attempt schema in {path}")
    return list(reader)


def recover_budget_reservations(budget, arguments, result_root, jobs, attempts_path, live):
    """Release the reservations of host epochs no live worker holds: each was settled once its cleanup was proven,
    or never reached its launch, which its starting record precedes. A reservation with no attempt record at all
    gets one."""
    by_identity = {job.logical_identity: job for job in jobs}
    recorded = {(row["platform"], row["shard_id"], int(row["replica"]), int(row["host_epoch"]))
                for row in read_attempts(attempts_path)}
    for item in budget.reservations(result_root):
        if item["campaign_id"] != arguments.campaign_id or item["logical_identity"] not in by_identity:
            raise UnsafeCampaignError("shared fleet reservation identifies a different controller")
        if item["host_epoch"] in live:
            continue
        job = replace(by_identity[item["logical_identity"]], host_epoch=item["host_epoch"], attempt=item["attempt"])
        if (job.platform.name, job.shard, job.replica, job.host_epoch) not in recorded:
            write_attempt(attempts_path, job, "retryable-infrastructure", "controller stopped before launch")
        budget.release(arguments.campaign_id, job.host_epoch, cleanup_verified=True)


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


def record_pause(path, event, at):
    """Record in a phase's pauses ledger that a pause began, as "paused", or ended, as "resumed", at an epoch time."""
    append_ledger(path, PHASE_PAUSE_FIELDS, {"event": event, "at": f"{at:.3f}"})


def paused_seconds(path, started_at, now):
    """The seconds a phase's pauses ledger records it paused since it started, at started_at, with a pause still open
    counted until now, and whether one is open."""
    paused, since = 0.0, None
    if path.exists():
        for row in csv.DictReader(ledger_lines(path), delimiter="\t"):
            at = float(row["at"])
            if row["event"] == "paused":
                since = at if since is None else since
            elif since is not None:
                paused, since = paused + max(0.0, at - max(since, started_at)), None
    if since is not None:
        paused += max(0.0, now - max(since, started_at))
    return paused, since is not None


def admit_launch(arguments, result_root, attempts_path, capacity_waits, candidate, ready_at, now, observed):
    """Place a launch candidate and reserve its capacity in the shared fleet, from the fleet observed this loop.

    Returns the candidate as placed, when it may next be tried, why it was refused, or None once
    admitted, whether the launch scan may continue to later candidates, and the vCPU count a full
    fleet quota now blocks, if any.
    """
    budget = arguments.shared_budget
    placements = launch_placements(arguments, candidate)
    candidate = place(candidate, placements[0])
    for placement in placements:
        try:
            admitted, reason = budget.reserve(
                arguments.campaign_id, place(candidate, placement), result_root,
                lambda region=placement[0], market=candidate.market:
                shared_fleet_snapshot(arguments, region, market, observed),
                until=candidate.stop_by + UNIT_WORKER_TEARDOWN_SECONDS)
        except BudgetError:
            raise
        except (OSError, RuntimeError) as error:
            admitted, reason = False, f"shared capacity query failed: {error}"
        if admitted:
            candidate = place(candidate, placement)
            break
        # Fleet-wide host, spending and launch-rate limits, the boot window, and a spending stop, hold in every
        # region.
        if reason in ("fleet launch limit", "fleet spending or machine-hour ceiling", "boot window", "spending_stop"):
            break
    if not admitted:
        if (candidate.identity, reason) not in capacity_waits:
            capacity_waits.add((candidate.identity, reason))
            write_attempt(attempts_path, candidate, "queued-capacity", reason)
        if reason != "fleet quota" or candidate.platform.vcpus == 2:
            return candidate, now + CAPACITY_RETRY_SECONDS, reason, False, None
        return candidate, now + CAPACITY_RETRY_SECONDS, reason, True, candidate.platform.vcpus
    return candidate, ready_at, None, False, None


def launch_record(row):
    """The fields of an attempt row's detail: a starting row's placement, launch slot, session and prefix, a started
    row's instance, and a terminating row's request, with the classification of a controller's stop."""
    return dict(item.split("=", 1) for item in row["detail"].split(";") if "=" in item)


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
def controller_lock(campaign_root, campaign):
    """Own campaign_root as its only controller, and once it does, record this process's ID and start time in its
    controller record. While another controller owns it, refuse, and refuse a campaign that has ended, as its manifest
    shows, before the record its manifest lists changes, so a reboot or a replaced host neither runs an ended campaign
    again nor changes its root; Dain reopens one with control's reopen."""
    campaign_root.mkdir(parents=True, exist_ok=True)
    lock_path = campaign_root / CONTROLLER_LOCK
    with lock_path.open("w") as lock_file:
        try:
            fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            owner = controller_process(campaign_root)
            raise SystemExit(f"another controller{f' (process {owner})' if owner else ''} owns campaign root "
                             f"{campaign_root}; see its progress with {Path(__file__).name} status "
                             f"{campaign_root}") from None
        manifest = campaign_root / control_host.MANIFEST
        if manifest.is_file():
            raise SystemExit(f"campaign {campaign} has ended, as {manifest} shows; to run it again, reopen it with "
                             f"{Path(__file__).name} control {campaign_root} --campaign {campaign} --actor dain "
                             "reopen")
        temporary = campaign_root / (CONTROLLER_RECORD + ".new")
        temporary.write_text(f"pid={os.getpid()}\nstarted={process_start(os.getpid())}\n")
        os.replace(temporary, campaign_root / CONTROLLER_RECORD)
        yield


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


@dataclass
class UnitWorker:
    """A unit worker host: its session, its instance, and the leases it was given."""
    job: Job
    # Where its host exchanges leases and results, and its instance and zone once its launch names them, as
    # worker_metadata describes them.
    metadata: dict
    # The session's directory under the worker's job result root, which holds what its cleanup recovered.
    directory: Path
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
    # Whether its host has ended, so it takes no lease while its cleanup is proven.
    gone: bool = False
    # The launch slot this worker took over, as the host epoch of its first worker, and the slot's
    # consecutive failures before a start.
    slot: int = None
    slot_failures: int = 0
    # When this controller launched or adopted the worker, and when the lease it runs began: when it was
    # leased, the lease before it settled, or the worker started, whichever came last.
    launched_at: float = 0.0
    running_since: float = 0.0
    # When, in epoch seconds, this controller adopted the worker from an earlier one's ledgers, if it did.
    adopted_at: float = 0.0
    # The host's stop time, the latest of its admission's and its work leases', and each work lease's, in epoch
    # seconds.
    stop_by: int = 0
    lease_stop_by: dict = field(default_factory=dict)
    # A launch AWS refused, as its classification and detail; whether its host never reported by its boot deadline;
    # the instance as its cleanup last saw it; when the controller first asked EC2 to terminate it, in epoch seconds;
    # whether it leaked; and whether its prefix was found a leftover.
    launch_failure: tuple = None
    missed_boot: bool = False
    instance: cloud.Instance = None
    terminate_requested: float = 0.0
    leaked: bool = False
    leftover: bool = False
    # The group it runs, as its lease, the group, and when its latest heartbeat or group upload began the group, in
    # epoch seconds.
    group: tuple = None
    # Each work lease's steps so far, each by its name, in epoch seconds, for its trace: when it was leased, the host
    # started its first group, finished its latest, and uploaded it, and the controller accepted it.
    steps: dict = field(default_factory=dict)
    # The full keys of uploads and leases whose outcomes are durable, which the next deletion removes from S3.
    deletions: set = field(default_factory=set)

    @property
    def pool(self):
        return unit_worker_pool(self.job)

    @property
    def host_session(self):
        return self.metadata["session"]

    @property
    def instance_id(self):
        return self.metadata["instance_id"]

    def outstanding(self):
        done = self.settled | self.abandoned
        return sorted(sequence for sequence in self.leases if sequence not in done)

    def open(self):
        return not (self.stopping or self.lost or self.gone)

    def state(self, terminated=False):
        """What the worker is doing: stopping once lost, terminated or gone; otherwise booting until it starts, then
        running while it holds a work lease, even behind its stop lease, stopping once only its stop lease remains,
        and idle while it waits for a lease."""
        if self.lost or terminated or self.gone:
            return "stopping"
        if self.started and any(self.leases[sequence] is not None for sequence in self.outstanding()):
            return "running"
        if self.stopping:
            return "stopping"
        return "idle" if self.started else "booting"


class UnitDispatcher:
    """One phase's unit queue: the work items that remain, each worker's leases, and what each became.

    Workers are hosts the controller launches and cleans up, each within the fleet budget.
    unit-workers.tsv freezes each worker before its launch, job-attempts.tsv records its session before its launch,
    and unit-attempts.tsv records each lease before it is written and each outcome it reaches, so a restart
    rebuilds this state from the ledgers, the instances their sessions' tags find, the live workers' uploads, and
    the uploads recovered while cleaning up exited workers.
    """

    def __init__(self, arguments, root, result_root, items, platforms, validation):
        self.arguments = arguments
        self.root = root
        self.validation = validation
        self.result_root = result_root
        self.items = {item.identity: item for item in items}
        self.pools = sorted({item.pool for item in items})
        # Each pool's items with a group not yet accepted, by identity, and its items' estimated seconds.
        self.open = {pool: {} for pool in self.pools}
        self.pool_seconds = dict.fromkeys(self.pools, 0)
        for item in items:
            self.open[item.pool][item.identity] = item
            self.pool_seconds[item.pool] += sum(seconds for _, seconds in item.groups)
        self.platforms = pool_platforms(platforms, items)
        self.budget = getattr(arguments, "shared_budget", None)
        # Each region's fleet as this loop described it, once, when the loop first needed it.
        self.observed = {}
        self.attempts_path = result_root / "job-attempts.tsv"
        self.leases_path = result_root / "unit-attempts.tsv"
        self.workers_path = result_root / "unit-workers.tsv"
        self.events_path = result_root / "campaign-events.tsv"
        self.accepted_path = result_root / "accepted-units.tsv"
        self.rejections_path = result_root / unit_session.SEMANTIC_REJECTIONS
        self.items_path = result_root / "work-items.tsv"
        # The frozen inputs' fingerprint, taken when the phase runs; each loop that starts or settles a worker checks
        # it once.
        self.fingerprint = None
        self.inputs_checked = False
        # When, in epoch seconds, the phase started, once its dispatch has begun.
        self.started_at = None
        self.uploads_path = result_root / UNIT_UPLOADS
        # The seconds each group unit-uploads.tsv records took to measure, by its unit attempt and group.
        self.uploads = {}
        # Each work item's accepted receipts, by group, and the instances that measured each unit, by platform and
        # unit.
        self.accepted = {}
        self.measured = {}
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
        # When each pool's launches in each market first found no capacity since one of its workers in the market
        # last started.
        self.shortages = {}
        # Each pool's free launch slots, as (slot, consecutive failures), and the pools a slot gave up on.
        self.free_slots = {}
        self.exhausted = {}
        self.pool_ready = {}
        self.on_demand_ready = {}
        # Each pool's On-Demand workers admitted in its tail, and the Spot workers they retired.
        self.tail_on_demand = {}
        self.retired = {}
        # Each lease, as its worker's host epoch and sequence, that spent one of its item's attempts.
        self.counted = set()
        self.capacity_waits = set()
        # How many more hosts the fleet's boot window admits this loop.
        self.boot_room = 0
        # When the controller decided to stop each host it terminates, by host epoch.
        self.terminated = {}
        # The loop's round trips run on the request threads, and gathering group completions on the acceptance
        # threads, which a campaign's phases share; each group being gathered is kept by its worker's host epoch,
        # lease and group until it is applied.
        self.requests, self.acceptances = getattr(arguments, "pools", None) or (
            ThreadPoolExecutor(REQUEST_THREADS), ThreadPoolExecutor(ACCEPTANCE_THREADS))
        self.accepting = {}
        # The hosts of each platform the phase may hold, as its campaign shares them; a phase run alone holds up to
        # the limits. The hosts of each platform it gives up, and how its campaign steers it: running; paused, which
        # leases and launches nothing; draining, which leases its live workers but launches none; or stopping, as
        # its campaign stops, which stop-leases every worker.
        self.share = None
        self.shed = {}
        self.steering = "running"
        # Where the phase's telemetry goes; each pool's launch refusal that stands, as its reason and when the pool
        # first met it, in epoch seconds, until the pool launches or needs no launch; and the milestones it reached.
        self.telemetry = getattr(arguments, "telemetry", None) or telemetry.Telemetry()
        self.refused = {}
        self.reached = set()

    @property
    def key(self):
        """The phase's name in its telemetry: its root relative to the campaign root."""
        return self.result_root.relative_to(self.arguments.campaign_root).as_posix()

    def remaining(self, item):
        """The item's groups not yet accepted, in its run order, each as (group ID, estimated seconds)."""
        accepted = self.accepted.get(item.identity)
        if not accepted:
            return item.groups
        return [(group, seconds) for group, seconds in item.groups if group not in accepted]

    def remaining_seconds(self, item):
        return sum(seconds for _, seconds in self.remaining(item))

    def done(self, identity):
        return identity not in self.open[self.items[identity].pool]

    def queued(self, pool=None):
        return [item for open_items in (self.open.values() if pool is None else [self.open[pool]])
                for identity, item in open_items.items() if identity not in self.unresolved and identity not in self.held]

    def purchase(self, pool):
        """How the pool's workers buy capacity, as the settings say now."""
        return purchase_policy(self.arguments, self.platforms[pool].name)

    def on_demand_only(self, item):
        """Whether only On-Demand hosts may take the item, unless its pool's platform buys only Spot: a Spot
        interruption stopped it before any group of the lease was accepted, or its longest remaining group outlasts
        the 15-minute budget, which would risk more Spot interruptions than it can absorb."""
        return self.purchase(item.pool) != "spot" and (item.identity in self.interrupted or max(
            (seconds for _, seconds in self.remaining(item)), default=0) > work_units.UNIT_SECONDS)

    def on_demand(self, item):
        purchase = self.purchase(item.pool)
        return purchase == "on-demand" or self.on_demand_only(item) or (purchase != "spot" and item.pool in self.tail)

    def pool_workers(self, pool):
        return [worker for worker in self.workers.values() if worker.pool == pool]

    def gave_up(self, pool):
        """Whether the pool opens no new worker: it gave up with no free slot."""
        return pool in self.exhausted and not self.free_slots.get(pool)

    def recover(self):
        """Adopt the workers an earlier controller launched and did not settle; each loop then follows its instance,
        by its ID or its session's tag, until its cleanup is proven.

        Each launch slot is rebuilt from its latest worker's starting record: a worker not yet settled holds it, and
        one settled leaves it free with its count, or given up.

        A settled lease's uploads and an accepted group's are deleted from S3, so the ledgers alone show them: a
        worker with a settled work lease started, a lease's groups accepted for it are finished, and a settled lease
        is never written again.
        """
        attempts = read_unit_attempts(self.leases_path)
        ran = {int(row["host_epoch"]) for row in attempts
               if row["unit_id"] != "-" and row["outcome"] in SETTLED_UNIT_OUTCOMES}
        latest = {}
        placements = {}
        instances = {}
        terminating = {}
        aborted = set()
        for row in read_attempts(self.attempts_path):
            if row["shard_id"] == UNIT_WORKER_SHARD and row["outcome"] == "terminating":
                # A termination request settles nothing; the first starts the worker's leak clock, and one the
                # controller decided keeps it stopping the host as its own.
                request = launch_record(row)
                terminating.setdefault(int(row["host_epoch"]), float(request["requested_at"]))
                if request.get("classification") == "controller-abort":
                    aborted.add(int(row["host_epoch"]))
            elif row["shard_id"] == UNIT_WORKER_SHARD:
                latest[int(row["host_epoch"])] = row
                if row["outcome"] == "starting":
                    placements[int(row["host_epoch"])] = launch_record(row)
                elif row["outcome"] == "started":
                    instances[int(row["host_epoch"])] = launch_record(row)
        slots = {}
        for epoch, placement in placements.items():
            if "session" not in placement:
                raise UnsafeCampaignError(f"unit worker epoch {epoch} of {self.result_root} was launched by the "
                                          "per-host wrapper, which no longer exists; run the phase in a new root")
            placement["slot"] = int(placement["slot"])
            placement["slot_failures"] = int(placement["slot_failures"])
            slots[placement["slot"]] = max(slots.get(placement["slot"], epoch), epoch)
        workers = {}
        for job in self.planned:
            row = latest.get(job.host_epoch)
            placement = placements.get(job.host_epoch)
            if row is None or placement is None:
                continue
            slot, slot_failures = placement["slot"], placement["slot_failures"]
            directory = job_result_root(self.result_root, job) / placement["session"]
            if row["outcome"] not in ("starting", "started"):
                if slots[slot] == job.host_epoch:
                    # The earlier controller settled this worker; its slot is as that settlement left it.
                    self.release_slot(job, slot, slot_failures,
                                      job.host_epoch in ran or self.recovered_start(directory),
                                      0 if row["outcome"] == "stopped" else 1, row["outcome"], row["detail"],
                                      row["outcome"] == "controller-abort")
                continue
            # The starting record names the region the worker was placed in.
            job = replace(job, market=row["market"], region=placement["region"],
                          placement_score=placement["placement_score"],
                          boot_by=int(placement["boot_by"]), stop_by=int(placement["stop_by"]))
            metadata = worker_metadata(self.arguments, job, placement["session"], placement["prefix"])
            metadata.update({field: instances.get(job.host_epoch, {}).get(field, "")
                             for field in ("instance_id", "availability_zone")})
            workers[job.host_epoch] = self.workers[job.host_epoch] = UnitWorker(
                job, metadata, directory, slot=slot, slot_failures=slot_failures, launched_at=time.monotonic(),
                stop_by=job.stop_by, terminate_requested=terminating.get(job.host_epoch, 0.0),
                adopted_at=time.time())
            if job.host_epoch in aborted:
                self.terminated[job.host_epoch] = terminating[job.host_epoch]
        recover_budget_reservations(self.budget, self.arguments, self.result_root, self.planned, self.attempts_path,
                                    set(workers))
        for groups in recover_accepted_units(self.validation, self.result_root, self.accepted_path).values():
            for receipt in groups.values():
                self.accept(receipt)
        self.uploads = measured_group_seconds(self.uploads_path)
        for row in read_semantic_rejections(self.rejections_path):
            identity = unit_logical_identity(row)
            if identity not in self.items:
                raise UnsafeCampaignError(f"semantic-rejection ledger names an unexpected work item: {identity}")
            self.semantic.setdefault(identity, row["unit_attempt_id"])
        outcomes = {}
        for row in attempts:
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
            elif row["outcome"] in ("lost", "released", "claimed"):
                worker.abandoned.add(sequence)
                # Only a lost lease shows its host was judged lost. A host that claimed its idle stop gets nothing
                # more, nor any lease of the sequence it claimed or after it.
                worker.lost = worker.lost or row["outcome"] == "lost"
                if row["outcome"] == "claimed":
                    worker.stopping = True
                    worker.unsent = {unsent for unsent in worker.unsent if unsent < sequence}
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
            worker.started = epoch in ran
            for sequence, identity in worker.leases.items():
                if identity is None:
                    continue
                attempt = unit_session.format_unit_attempt(worker.host_session, sequence)
                accepted = [group for group in worker.lease_groups[sequence]
                            if self.accepted.get(identity, {}).get(group, {}).get("unit_attempt_id") == attempt]
                worker.finished[sequence] = dict.fromkeys(accepted)
                # A deletion the earlier controller had not made yet is made again.
                worker.deletions.update(f"{worker.metadata['result_prefix']}/{key}" for group in accepted
                                        for key in (completion_key(sequence, group), result_key(sequence, group)))
                if sequence in worker.settled:
                    worker.unsent.discard(sequence)
                    worker.deletions.update((f"{worker.metadata['result_prefix']}/{heartbeat_key(sequence)}",
                                             lease_key(worker.metadata, sequence)))
                elif sequence not in worker.abandoned:
                    # Its trace begins with what its host reports.
                    worker.steps[sequence] = {}
        # A restart reaches no milestone the phase had reached.
        self.reached = self.milestones()

    def milestones(self):
        """The milestones the phase has reached: its first host reporting, and every replica 1 accepted."""
        return {name for name, reached in (
            ("first host reporting", self.accepted or any(worker.started for worker in self.workers.values())),
            ("replica 1 accepted", all(self.done(identity) for identity, item in self.items.items()
                                       if item.replica == 1))) if reached}

    @staticmethod
    def recovered_start(directory):
        """Whether a settled worker's recovered uploads, in its session's directory, show it started, as its
        settlement judged."""
        return shows_worker_start(recovered_keys(directory / "recovered-uploads" / "objects"))

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
        self.trace(worker, sequence, outcome)
        worker.settled.add(sequence)
        # Its heartbeat and lease go from S3 once its outcome is durable; a restart needs neither.
        worker.deletions.update((f"{worker.metadata['result_prefix']}/{heartbeat_key(sequence)}",
                                 lease_key(worker.metadata, sequence)))
        # The worker goes on to its next lease.
        worker.running_since = time.monotonic()
        if self.held.get(identity) == (worker.job.host_epoch, sequence):
            del self.held[identity]

    def abandon(self, worker, sequence, outcome, detail):
        """Requeue a lease whose host was lost or exited; a completion that still lands is processed."""
        identity = worker.leases[sequence]
        write_unit_attempt(self.leases_path, worker, sequence, self.items[identity], outcome, detail,
                           worker.finished.get(sequence, {}))
        self.trace(worker, sequence, outcome)
        worker.abandoned.add(sequence)
        if self.held.get(identity) == (worker.job.host_epoch, sequence):
            del self.held[identity]
        if outcome == "lost":
            self.count(self.items[identity], detail, (worker.job.host_epoch, sequence))

    def trace(self, worker, sequence, outcome):
        """Trace a work lease's attempt as it reaches its outcome, once: from its lease, or from its host's first
        report of it, to now, with each step whose start and end are known."""
        steps = worker.steps.pop(sequence, None)
        if steps is None:
            return
        item = self.items[worker.leases[sequence]]
        groups = worker.lease_groups[sequence]
        attempt = unit_session.format_unit_attempt(worker.host_session, sequence)
        now = time.time()
        times = [steps.get(name) for name in ("leased", "started", "finished", "uploaded", "accepted")]
        self.telemetry.attempt(
            {"phase": self.key, "pool": "/".join(worker.pool), "outcome": outcome, "unit": item.unit_id,
             "replica": item.replica, "groups": ",".join(groups), "session": worker.host_session, "lease": sequence,
             "region": worker.job.region, "market": worker.job.market,
             "estimated_seconds": sum(seconds for group, seconds in item.groups if group in groups),
             "measured_seconds": sum(self.uploads.get((attempt, group)) or 0 for group in groups)},
            next((moment for moment in times if moment is not None), now), now,
            [(name, begun, ended) for name, begun, ended in zip(("leased", "started", "uploaded", "accepted"), times,
                                                                times[1:]) if begun is not None and ended is not None])

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
                    worker, "unit-rejected", detail, **self.event_unit(worker, sequence))

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
                    worker, "spot-interruption-notice",
                    f"replacement_market={'on-demand' if self.on_demand_only(item) else 'any'}",
                    **self.event_unit(worker, sequence))

    def complete_groups(self, worker, sequence, keys, fetch, wait=False):
        """Process the completion markers of the lease's groups in run order; returns whether the lease settled.

        The lease is done once its last group's marker is processed, or once a marker ends it: a group that
        failed, or failed its checks, settles the lease, and a semantic failure rejects the unit. A group is
        processed once the acceptance threads have gathered it, unless wait.
        """
        finished = worker.finished.setdefault(sequence, {})
        for group in worker.lease_groups[sequence]:
            if group in finished:
                continue
            if completion_key(sequence, group) not in keys:
                return sequence in worker.settled
            evidence = self.group_evidence(worker, sequence, group, fetch, wait)
            if evidence is None or not self.complete(worker, sequence, group, evidence):
                return sequence in worker.settled
        superseded = "; ".join(f"group {group}: {detail}" for group, detail in finished.items() if detail)
        if None in finished.values():
            self.settle(worker, sequence, "accepted", superseded or "-")
        else:
            self.settle(worker, sequence, "superseded", superseded)
        return True

    def group_evidence(self, worker, sequence, group, fetch, wait):
        """What the acceptance threads found of the group's completion, or None while they are still gathering it.
        A lease's groups are gathered one at a time, so they are processed in run order. A gathering that failed is
        retried, at once when wait."""
        key = (worker.job.host_epoch, sequence, group)
        future = self.accepting.get(key)
        if future is not None and wait and future.exception() is not None:
            future = None
        if future is None:
            item = self.items[worker.leases[sequence]]
            expected = {
                "schema_version": 2, "host_session": worker.host_session, "sequence": sequence,
                "campaign_id": self.arguments.campaign_id, "phase": self.arguments.phase, "platform": item.platform,
                "unit_id": item.unit_id, "replica_id": item.replica, "group_id": group,
                "unit_attempt_id": unit_session.format_unit_attempt(worker.host_session, sequence),
            }
            # A group already settled needs none of this lease's result.
            needs_result = (self.accepted.get(item.identity, {}).get(group) is None and
                            item.identity not in self.semantic)
            future = self.accepting[key] = self.acceptances.submit(
                gather_group, self.validation, self.result_root,
                self.result_root / "unit-results" / worker.host_session, item, expected, needs_result,
                (worker.metadata.get("aws_region"), worker.job.market), fetch)
        if not (wait or future.done()):
            return None
        del self.accepting[key]
        return future.result()

    def complete(self, worker, sequence, group, evidence):
        """Accept or supersede one group of a lease from what the acceptance threads found of its completion, or
        settle the lease; returns whether the group finished and the lease goes on."""
        if evidence.problem is not None:
            raise UnsafeCampaignError(evidence.problem)
        item = self.items[worker.leases[sequence]]
        attempt = unit_session.format_unit_attempt(worker.host_session, sequence)
        marker = evidence.marker
        if (attempt, group) not in self.uploads:
            persist_unit_upload(self.uploads_path, unit_upload_row(item, worker.host_session, sequence, marker))
            self.uploads[attempt, group] = marker_seconds(marker)
        # The lease's trace runs from its first group's start through its latest group's upload.
        steps = worker.steps.get(sequence, {})
        steps.setdefault("started", marker_time(marker, "started_at"))
        steps["finished"] = marker_time(marker, "finished_at")
        steps["uploaded"] = steps["finished"] + float(marker["pack_seconds"]) + float(marker["upload_seconds"])
        finished = worker.finished[sequence]
        if marker.get("exit_status") == SEMANTIC_FAILURE_STATUS:
            # A verdict that the semantics are wrong rejects the unit for good, before or after another attempt.
            # Its ledger row is durable before the lease's outcome is.
            persist_semantic_rejection(self.rejections_path, item, attempt)
            self.semantic.setdefault(item.identity, attempt)
            self.reject(worker, sequence, SEMANTIC_FAILURE_DETAIL)
            return False
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
        if evidence.rejection is not None:
            self.reject(worker, sequence, evidence.rejection)
            return False
        if marker.get("unit_status") != "complete" or marker.get("exit_status") != 0:
            if marker.get("exit_status") == PROTOCOL_REJECTION_STATUS:
                self.reject(worker, sequence, f"numerical protocol rejection: status {PROTOCOL_REJECTION_STATUS}")
                return False
            if evidence.capacity.get("capacity_status") == "rejected":
                self.reject(worker, sequence, "unit capacity gate rejected the host's resources")
                return False
            detail = f"unit failed with status {marker.get('exit_status')}"
            self.settle(worker, sequence, "failed", detail)
            self.count(item, detail, (worker.job.host_epoch, sequence))
            return False
        if evidence.invalid is not None:
            self.reject(worker, sequence, f"artifact gate: {evidence.invalid}")
            return False
        retain_unit_attempt(evidence.directory, unit_attempt_directory(self.result_root, evidence.receipt))
        persist_accepted_unit(self.accepted_path, evidence.receipt)
        self.accept(evidence.receipt)
        if self.done(item.identity):
            self.unresolved.pop(item.identity, None)
        finished[group] = None
        steps["accepted"] = time.time()
        # Once its acceptance is durable the group's uploads go from S3; the worker's state, or after a restart the
        # accepted ledger, keeps the group finished.
        worker.deletions.update(f"{worker.metadata['result_prefix']}/{key}"
                                for key in (completion_key(sequence, group), result_key(sequence, group)))
        return True

    def accept(self, receipt):
        """Record an accepted group, and the instance that measured its unit."""
        identity = unit_logical_identity(receipt)
        self.accepted.setdefault(identity, {})[receipt["group_id"]] = receipt
        item = self.items.get(identity)
        if item is not None and not self.remaining(item):
            self.open[item.pool].pop(identity, None)
        self.measured.setdefault((receipt["platform"], receipt["unit_id"]), set()).add(receipt["instance_id"])

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
        """Settle the worker's leases from its uploads; classification is that of an exited worker, whose leases are
        settled from its last uploads at once, as is a lease its interruption ended, whose completions are judged
        before its interruption."""
        for sequence in sorted(worker.leases):
            if sequence in worker.settled or worker.leases[sequence] is None:
                continue
            ended = classification is not None or interruption_key(sequence) in keys
            if self.complete_groups(worker, sequence, keys, fetch, wait=ended):
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

    def poll(self):
        """Follow every worker: list each live one's uploads and process them, and clean up each ended one, which
        takes no lease from then on, the workers' round trips on the request threads; then delete from S3 what
        accepted groups and settled leases no longer need."""
        live, ended = self.partition()
        for worker, objects in zip(live, self.concurrently(self.listing, live)):
            if objects is not None:
                self.follow(worker, objects)
        for worker in self.clean_up(ended):
            self.finish(worker)
        deleting = [(worker, sorted(worker.deletions)) for worker in self.workers.values() if worker.deletions]
        for (worker, keys), deleted in zip(deleting, self.concurrently(self.delete, deleting)):
            if deleted:
                worker.deletions.difference_update(keys)

    def partition(self):
        """The workers whose hosts are live, and those whose hosts have ended, each of which is marked gone."""
        live, ended = [], []
        for worker in list(self.workers.values()):
            if self.ended(worker):
                worker.gone = True
                ended.append(worker)
            else:
                live.append(worker)
        return live, ended

    def listing(self, worker):
        """The worker's uploads, or None while S3 cannot list them; the next poll, or cleanup's recovered uploads,
        retries."""
        try:
            return list_unit_worker_results(self.arguments, worker)
        except CloudError:
            return None

    def delete(self, entry):
        """Delete a worker's keys from S3; returns whether that held, or the next poll tries again."""
        worker, keys = entry
        try:
            self.arguments.cloud.delete(worker.metadata["aws_region"], worker.metadata["bucket"], keys)
        except CloudError:
            return False
        return True

    def follow(self, worker, objects):
        """Process a live worker's uploads and decide whether its host was lost."""
        try:
            keys = set(objects)
            self.note_start(worker, keys)

            def fetch(key, path):
                get_unit_worker_result(self.arguments, worker, key, path)

            self.reconcile(worker, keys, fetch)
            if not worker.started and time.time() >= worker.job.boot_by:
                # A host silent past its boot deadline is stopped, and the leases it was launched with go to other
                # workers without spending an attempt.
                worker.missed_boot = True
                for sequence in worker.outstanding():
                    if worker.leases[sequence] is not None:
                        self.abandon(worker, sequence, "released", BOOT_DEADLINE_MISSED)
                return
            current = next((sequence for sequence in worker.outstanding() if worker.leases[sequence] is not None),
                           None)
            if worker.lost or current is None:
                return
            # The worker heartbeats as a lease starts and as each of its groups completes, so its running instance
            # has stalled once the lease's unfinished groups outrun their lease timeout from its latest progress: its
            # latest heartbeat, the upload of a group S3 lists complete or this controller saw finish, any of whose
            # heartbeats may have been lost, or once the lease shows progress, when this controller adopted it, as
            # an accepted group's uploads are gone. A lease with no progress, or none left to run, is not judged. A
            # stalled worker is told to stop and terminated, and its cleanup still recovers a completion that lands.
            seconds = dict(self.items[worker.leases[current]].groups)
            finished = worker.finished.get(current, {})
            groups = worker.lease_groups[current]
            listed = [objects[key] for key in (heartbeat_key(current), *(completion_key(current, group)
                                                                       for group in groups)) if key in objects]
            unfinished = [group for group in groups
                          if group not in finished and completion_key(current, group) not in objects]
            if not (listed or finished) or not unfinished:
                return
            progress = max(*listed, worker.steps.get(current, {}).get("uploaded", 0), worker.adopted_at)
            # Its latest progress began the group it runs.
            worker.group = (current, unfinished[0], progress)
            if time.time() - progress <= lease_timeout(sum(map(seconds.get, unfinished))):
                return
            if not worker.stopping:
                self.issue(worker, None)
            worker.lost = True
            self.telemetry.count("regulator_stalled_hosts", phase=self.key)
            for sequence in worker.outstanding():
                if worker.leases[sequence] is not None:
                    self.abandon(worker, sequence, "lost" if sequence == current else "released", STALLED)
        except (CloudError, OSError, ValueError):
            # S3 may be unavailable for a moment; the next poll, or cleanup's recovered uploads, retries.
            return

    def note_start(self, worker, keys):
        """Record that the worker started once its uploads, keys, show worker evidence, even where its heartbeats
        never landed; its first lease runs from then."""
        if not worker.started and shows_worker_start(keys):
            worker.started, worker.running_since = True, time.monotonic()
            # Capacity in the worker's market has returned for the pool.
            self.shortages.pop((worker.pool, worker.job.market), None)

    def report_boots(self):
        """Report the workers that started to the fleet's boot window, and take the room it leaves."""
        self.boot_room = self.budget.report_boots(
            self.arguments.campaign_id, [epoch for epoch, worker in self.workers.items() if worker.started])

    def instance(self, worker):
        """The worker's instance in this loop's description of its region, by its ID, or by its session's tag while
        its launch's answer never came, or None while EC2 lists none; one found by its tag is recorded as started.
        Raises CloudError."""
        instances = described(self.arguments, self.observed, worker.metadata["aws_region"], "instances")
        if worker.instance_id:
            return next((instance for instance in instances if instance.id == worker.instance_id), None)
        instance = next((instance for instance in instances
                         if instance.tags.get("Session") == worker.host_session), None)
        if instance is not None:
            worker.metadata["instance_id"] = instance.id
            write_attempt(self.attempts_path, worker.job, "started",
                          f"instance_id={instance.id};availability_zone={worker.metadata['availability_zone']}")
        return instance

    def ended(self, worker):
        """Whether the worker's host is gone or going: AWS refused its launch, it never reported by its boot deadline,
        its instance is past running, or EC2 still lists none once its boot allowance is over."""
        if worker.launch_failure or worker.missed_boot:
            return True
        try:
            instance = self.instance(worker)
        except CloudError:
            return False
        if instance is None:
            return time.time() >= worker.job.boot_by
        return instance.state not in ("pending", "running")

    def stop_host(self, worker):
        """Ask EC2 to terminate the worker's instance until it lists it shutting down or terminated; one still not
        terminated TERMINATION_LEAK_SECONDS after the first request, which job-attempts.tsv records before it is
        made, has leaked. The record names a stop the controller decided, so a restart goes on stopping the host
        and settles it as the controller's."""
        try:
            instance = None if worker.launch_failure else self.instance(worker)
        except CloudError:
            # The next loop looks again.
            return
        if instance is None or instance.state == "terminated":
            return
        if not worker.terminate_requested:
            requested_at = time.time()
            # Recorded first, so a request whose record failed is recorded again.
            write_attempt(self.attempts_path, worker.job, "terminating", f"requested_at={requested_at:.3f}" + (
                ";classification=controller-abort" if worker.job.host_epoch in self.terminated else ""))
            worker.terminate_requested = requested_at
        elif time.time() - worker.terminate_requested >= TERMINATION_LEAK_SECONDS:
            self.leak(worker)
        if instance.state == "shutting-down":
            return
        try:
            self.arguments.cloud.terminate(worker.metadata["aws_region"], [instance.id])
        except CloudError as error:
            # The next loop asks again.
            print(f"terminating instance {instance.id} of {worker.host_session} failed: {error}; retrying",
                  flush=True)

    def leak(self, worker):
        """Stop the fleet's spending, once, for the worker's instance, which EC2 has not terminated; its reservation
        stays until its cleanup is proven, and other hosts' running leases finish."""
        if worker.leaked:
            return
        reason = f"instance {worker.instance_id} of {worker.host_session} did not terminate"
        self.telemetry.event("leak", f"leak: {reason}; no launch or lease extension is admitted until the spending "
                                     "stop is cleared", phase=self.key, session=worker.host_session)
        # Until the stop is recorded, the next loop tries again.
        worker.leaked = self.budget.stop_spending(reason)

    def clean_up(self, workers):
        """Prove ended workers' cleanup: each one's instance terminated, then its prefix recovered, deleted, and found
        empty, the prefixes on the request threads. Returns the workers whose cleanup was proven; the rest keep their
        reservations, and each loop tries again."""
        terminated = [worker for worker in workers if self.instance_terminated(worker)]
        proven = self.concurrently(lambda worker: recover_uploads(
            self.arguments.cloud, worker.metadata, worker.directory / "recovered-uploads" / "objects"), terminated)
        for worker, held in zip(terminated, proven):
            if not held:
                # A leftover: the host is gone, so it costs nothing, pauses nothing, and is retried. Its event is
                # logged once.
                text = (f"leftover: the prefix of {worker.host_session}, whose instance terminated, is not yet "
                        "recovered and empty; retrying")
                if worker.leftover:
                    print(text, flush=True)
                else:
                    self.telemetry.event("leftover", text, phase=self.key, session=worker.host_session)
                worker.leftover = True
        return [worker for worker, held in zip(terminated, proven) if held]

    def instance_terminated(self, worker):
        """The first half of an ended worker's cleanup proof: AWS launched no instance, or EC2 lists it terminated or
        no longer lists it. A host still running is asked to terminate."""
        if worker.launch_failure:
            return True
        try:
            instance = self.instance(worker)
        except CloudError:
            return False
        if instance is not None and instance.state != "terminated":
            self.stop_host(worker)
            return False
        worker.instance = instance
        return True

    def host_exit(self, worker):
        """How the worker's host ended, as its status, classification, and detail: its launch's refusal; the
        controller's termination; the exit status its host archive reports; a Spot reclaim; or no host found."""
        if worker.launch_failure:
            return 1, *worker.launch_failure
        archive = worker.directory / "recovered-uploads" / "objects" / "host.tar.gz"
        status = None
        if archive.is_file():
            try:
                extract_unit_result(archive, worker.directory / worker.job.platform.architecture)
                status = int((worker.directory / worker.job.platform.architecture / "re2-results" /
                              "exit-status").read_text())
            except (OSError, ValueError, tarfile.TarError):
                status = None
        if worker.job.host_epoch in self.terminated:
            return status or 1, "controller-abort", "terminated by the controller"
        if status == 0:
            return 0, "none", "-"
        if status == UNIT_WORKER_INTERRUPTED_STATUS:
            # The worker saw a Spot interruption notice and uploaded what it had.
            return status, "retryable-spot", "spot-interruption-notice"
        if status is not None:
            return status, "benchmark-failure", f"remote-exit-status:{status}"
        if worker.instance is not None and worker.instance.state_reason == "Server.SpotInstanceTermination":
            return 1, "retryable-spot", worker.instance.state_reason
        if worker.missed_boot:
            return 1, "retryable-infrastructure", BOOT_DEADLINE_MISSED
        if not worker.instance_id:
            return 1, "retryable-infrastructure", LAUNCH_NOT_FOUND
        reason = worker.instance.state_reason if worker.instance else "absent"
        return 1, "retryable-infrastructure", f"instance-terminated-without-results:state_reason={reason}"

    def check_frozen_inputs(self):
        """Refuse to start or settle a worker once the frozen inputs changed, hashing them at most once a loop."""
        if not self.inputs_checked:
            validate_frozen_inputs(self.root, self.arguments, self.items_path, self.fingerprint)
            self.inputs_checked = True

    def finish(self, worker):
        """Settle a worker whose cleanup was proven, from the uploads its cleanup recovered and its host's exit."""
        self.check_frozen_inputs()
        status, classification, detail = self.host_exit(worker)
        objects = worker.directory / "recovered-uploads" / "objects"
        keys = recovered_keys(objects)
        worker.started |= shows_worker_start(keys)
        if status == 0 and worker.started and None not in worker.leases.values():
            # With no stop lease, the host stopped itself once its idle time ran out.
            self.telemetry.count("regulator_idle_stops", phase=self.key,
                                 queued="true" if self.queued(worker.pool) else "false")
        elif classification == "retryable-spot" and not worker.launch_failure:
            self.telemetry.count("regulator_interruptions", phase=self.key)
        if not (worker.started or worker.launch_failure) and classification not in (
                "none", "retryable-spot", "controller-abort"):
            # Its host ended before it reported, for a reason other than a Spot reclaim or the controller's stop.
            self.budget.boot_failed(self.arguments.campaign_id, worker.job.host_epoch)
        self.budget.release(self.arguments.campaign_id, worker.job.host_epoch, cleanup_verified=True)
        self.workers.pop(worker.job.host_epoch, None)

        def fetch(key, path):
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(objects / key, path)

        self.reconcile(worker, keys, fetch, classification if status != 0 else "none")
        for key in [key for key in self.accepting if key[0] == worker.job.host_epoch]:
            del self.accepting[key]
        # The attempt's record reports how long its host took from boot to its first unit.
        startup = boot_to_first_unit(worker.directory, worker.job)
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
                        self.arguments.campaign_id, worker.job, worker, detail if retryable else "job-failure",
                        f"status={status}; classification={classification}; detail={detail}", unit_id="",
                        failure_classification=classification)
        if detail.startswith(("launch-capacity:", REGION_REFUSAL)):
            # The pool's launches in the market move past a region that found no capacity or refused the request.
            self.cool_down(pool, worker.job.market, worker.job.region, detail.startswith("launch-capacity:"))
        if classification == "retryable-capacity" or detail == FIRST_LEASES_UNWRITTEN:
            self.pool_ready[pool] = time.monotonic() + CAPACITY_RETRY_SECONDS

    def cool_down(self, pool, market, region, shortage):
        """Keep the pool's new workers in the market out of the region for its cool-down, as a shortage of its
        capacity there if shortage."""
        self.pool_regions.setdefault((pool, market), {})[region] = time.monotonic()
        if shortage:
            self.shortages.setdefault((pool, market), time.monotonic())

    def spot_waited(self, pool, now):
        """Whether a spot-then-on-demand pool has been short of Spot capacity for its whole bounded wait."""
        return (self.purchase(pool) == "spot-then-on-demand" and (pool, "spot") in self.shortages and
                now - self.shortages[pool, "spot"] >= SPOT_SHORTAGE_WAIT_SECONDS)

    def release_slot(self, job, slot, failures, started, status, classification, detail, terminated=False):
        """Free the launch slot of an exited worker, from its exit as the attempts ledger records it.

        A worker that started, by uploading worker evidence, clears its slot's count. One that failed before it
        started passes the count, one higher, to its replacement, and a slot past its replacements gives up, so
        its pool opens no new slot. A launch that found no capacity, that one region refused while its pool had
        another to try, whose first leases S3 did not take, that was never asked for, or whose instance was never
        found, booted nothing.
        """
        pool = unit_worker_pool(job)
        if started:
            failures = 0
        elif (status != 0 and not terminated and
              detail not in (LAUNCH_NOT_FOUND, FIRST_LEASES_UNWRITTEN, LAUNCH_NOT_ATTEMPTED) and
              not detail.startswith(("launch-capacity:", REGION_REFUSAL))):
            failures += 1
        if failures > MAX_REPLACEMENTS_PER_JOB:
            self.exhausted[pool] = (f"unit worker {job.identity} failed before it started: {detail}; "
                                    "the pool's workers exhausted their replacements")
        else:
            self.free_slots.setdefault(pool, []).append((slot, failures))

    def unresolved_items(self, ending=False):
        """The items left unresolved, each with its reason: a semantic failure, even where another attempt was
        accepted, or spent attempts, a rejection, the phase's launch limit, or no worker left to take it; once the
        phase ends, also every other item not accepted, as "not accepted"."""
        identities = self.items if ending else self.semantic.keys() | self.unresolved.keys()
        return [(self.items[identity], SEMANTIC_FAILURE_DETAIL if identity in self.semantic else
                 self.unresolved.get(identity, "not accepted"))
                for identity in sorted(identities) if identity in self.items and (
                    identity in self.semantic or ((ending or identity in self.unresolved) and not self.done(identity)))]

    def resolve_unlaunchable(self, deadline_reached):
        """Leave unresolved the queued items no worker can take: past the deadline, or in a pool that gave up."""
        for pool in self.pools:
            if deadline_reached:
                reason = f"{self.arguments.phase} campaign reached its wall-clock launch limit"
            elif self.gave_up(pool) and not self.pool_workers(pool):
                reason = self.exhausted[pool]
            else:
                continue
            for item in self.queued(pool):
                self.unresolved[item.identity] = reason

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
        if stop_by <= worker.stop_by:
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
            worker.steps[sequence] = {"leased": time.time()}
            self.held[item.identity] = (worker.job.host_epoch, sequence)

    def send(self, workers):
        """Write the workers' unsent leases, each worker's in order and the workers on the request threads, but none
        that was abandoned, whose item may already run elsewhere, and none to a host that has ended. A lease that
        could not be written is retried each loop."""
        pending = [(worker, [(sequence, self.lease_document(worker, sequence))
                             for sequence in sorted(worker.unsent - worker.abandoned)])
                   for worker in workers if not worker.gone]
        pending = [(worker, documents) for worker, documents in pending if documents]
        for (worker, _), written in zip(pending, self.concurrently(
                lambda entry: write_leases(self.arguments, *entry), pending)):
            self.sent(worker, written)

    def sent(self, worker, written):
        """Apply the leases written for a worker. One the host claimed first as its idle stop is `claimed`, and those
        behind it are released, all without spending an attempt; the host gets nothing more."""
        for sequence, held in written:
            worker.unsent.discard(sequence)
            if not held:
                worker.stopping = True
                worker.unsent.clear()
                for later in worker.outstanding():
                    if later >= sequence and worker.leases[later] is not None:
                        self.abandon(worker, later, "claimed" if later == sequence else "released",
                                     SELF_STOP_CLAIMED)
                return

    def concurrently(self, function, items):
        """function's result for each item, run on the request threads, so a loop's round trips overlap."""
        return [future.result() for future in [self.requests.submit(function, item) for item in items]]

    def next_item(self, worker, queued=None, units=None):
        """The eligible queued item to lease next: On-Demand work first, then the lowest replica, then the longest
        remaining. An item is never a unit this host already held, nor one on an instance that measured any group
        of any replica of its unit or that its confirmation excludes, and On-Demand-only items go to On-Demand hosts;
        On-Demand hosts also take the rest. A projection passes the items still queued in it and the units the
        worker would hold by then.

        Taking the lowest replica first gives a first reading of every unit as early as possible. On-Demand work
        still comes first: only an On-Demand host may take an On-Demand-only item, and the pool launched the host for
        it, so taking a later replica's Spot work first would buy another On-Demand host for the item. A Spot host's
        eligible items, and a tail pool's, are all On-Demand work or all not, so for them the replica decides."""
        on_demand_worker = worker.job.market == "on-demand"
        units = worker.units if units is None else units
        # A Spot worker keeps taking its pool's tail items until an On-Demand worker replaces it.
        candidates = [
            item for item in (self.queued(worker.pool) if queued is None else queued)
            if item.unit_id not in units and worker.instance_id not in item.excluded_instances and
            worker.instance_id not in self.measured.get((item.platform, item.unit_id), ()) and
            (on_demand_worker or not self.on_demand_only(item))]
        return min(candidates, key=lambda item: (not self.on_demand(item), item.replica,
                                                 -self.remaining_seconds(item), item.identity), default=None)

    def lease(self, worker, launching=False):
        """Keep the worker's next lease issued one ahead of its current one, or stop it; the loop then writes them. A
        worker is leased as it launches, before its instance exists, and again once it reports; until then it keeps
        the leases it was launched with."""
        if (worker.open() and worker.job.market == "spot" and self.purchase(worker.pool) != "spot" and
                worker.pool in self.tail and
                self.retired.get(worker.pool, 0) < self.tail_on_demand.get(worker.pool, 0)):
            # A tail Spot worker retires once an On-Demand worker admitted in the tail replaces it.
            self.retired[worker.pool] = self.retired.get(worker.pool, 0) + 1
            self.issue(worker, None)
        if worker.open() and (launching or worker.started):
            outstanding = worker.outstanding()
            while len(outstanding) < 2:
                # A worker leases ahead only while no waiting worker of its pool could take the item now. At a
                # launch, the workers the pool may still launch are waiting too, so a burst spreads its first
                # leases across its hosts and gives slack only to work beyond one lease each.
                waiting = sum(1 for other in self.pool_workers(worker.pool)
                              if other is not worker and other.open() and not other.outstanding())
                if launching:
                    waiting += self.launch_room(worker.pool)
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

    def holding(self, open_only=False):
        """The hosts the phase holds on each platform, or only those not stopping."""
        return Counter(worker.job.platform.name for worker in self.workers.values() if worker.open() or not open_only)

    def shed_hosts(self):
        """Stop-lease the hosts the phase gives up on each platform: idle workers first, then those holding the least
        work, each behind the leases it holds, so no lease is interrupted."""
        for platform, count in self.shed.items():
            workers = sorted((worker for worker in self.workers.values()
                              if worker.open() and worker.job.platform.name == platform),
                             key=lambda worker: (worker.state() != "idle", len(worker.outstanding()),
                                                 worker.job.host_epoch))
            for worker in workers[:count]:
                self.issue(worker, None)

    def demand(self):
        """The phase's work left on each platform, as its pools' corrected seconds and the number of its open items
        not left unresolved."""
        demand = {}
        for pool, left in progress(self.items.values(), self.accepted, self.uploads, self.unresolved).items():
            if left.items:
                seconds, count = demand.get(self.platforms[pool].name, (0, 0))
                demand[self.platforms[pool].name] = (seconds + left.corrected, count + left.items)
        return demand

    def finish_estimate(self):
        """The seconds until the phase's work left finishes at its open workers, or None while a pool with work left
        has none."""
        return finish_seconds(progress(self.items.values(), self.accepted, self.uploads, self.unresolved),
                              Counter(worker.pool for worker in self.workers.values() if worker.open()))

    def points(self, now):
        """The phase's part of the progress model and of the campaign's health, at now, in epoch seconds, as the
        campaign exports them each loop: its gauges' values by name."""
        phase = {"phase": self.key}

        def pool(name, **attributes):
            return {**phase, "pool": "/".join(name), **attributes}

        pools = progress(self.items.values(), self.accepted, self.uploads, self.unresolved)
        hosts = Counter(worker.pool for worker in self.workers.values() if worker.open())
        queued = Counter(item.pool for item in self.queued())
        states = {epoch: worker.state(epoch in self.terminated) for epoch, worker in self.workers.items()}
        running = [worker for epoch, worker in self.workers.items() if states[epoch] == "running"]
        # A running worker's slack is the work leases it holds beyond the one it runs.
        slack = {}
        for worker in running:
            slack.setdefault(worker.pool, []).append(
                sum(worker.leases[sequence] is not None for sequence in worker.outstanding()) - 1)
        groups = [(worker, self.items[worker.leases[worker.group[0]]], *worker.group) for worker in running
                  if worker.group and worker.group[0] in worker.outstanding()]
        refused = {}
        for reason, since in self.refused.values():
            refused[reason] = min(since, refused.get(reason, since))

        def group(worker, item, sequence, name):
            return pool(worker.pool, session=worker.host_session, region=worker.job.region,
                        market=worker.job.market, unit=item.unit_id, replica=item.replica, group=name, lease=sequence)

        return {
            "regulator_workers": [(pool(name, state=state), count) for (name, state), count in Counter(
                (self.workers[epoch].pool, state) for epoch, state in states.items()).items()],
            "regulator_queued_items": [(pool(name), queued[name]) for name in self.pools],
            "regulator_units": [(phase, len(self.items))],
            "regulator_accepted_units": [(phase, sum(map(self.done, self.items)))],
            "regulator_groups": [(phase, sum(len(item.groups) for item in self.items.values()))],
            "regulator_accepted_groups": [(phase, sum(len(groups) for identity, groups in self.accepted.items()
                                                       if identity in self.items))],
            "regulator_unresolved_units": [(phase, len(self.unresolved_items()))],
            "regulator_work_left_seconds": [(pool(name), left.seconds) for name, left in pools.items()],
            "regulator_correction": [(pool(name), left.correction) for name, left in pools.items()],
            "regulator_finish_time": [(pool(name), now + left.corrected / hosts[name]) for name, left in pools.items()
                                      if left.items and hosts[name]],
            "regulator_slack_min": [(pool(name), min(values)) for name, values in slack.items()],
            "regulator_slack_zero_workers": [(pool(name), sum(value <= 0 for value in values) if queued[name] else 0)
                                             for name, values in slack.items()],
            "regulator_launch_refused_seconds": [({**phase, "reason": reason}, now - since)
                                                 for reason, since in refused.items()],
            "regulator_leftovers": [(phase, sum(worker.leftover for worker in self.workers.values()))],
            "regulator_worker_group_estimated_seconds": [
                (group(worker, item, sequence, name), dict(item.groups)[name])
                for worker, item, sequence, name, _ in groups],
            "regulator_worker_group_elapsed_seconds": [
                (group(worker, item, sequence, name), max(0, now - started))
                for worker, item, sequence, name, started in groups],
        }

    def launch_room(self, pool):
        """How many more workers the pool may launch this loop beside the hosts the phase holds, within its share of
        its platform's hosts, or alone, the limits, and the fleet's boot window."""
        platform = self.platforms[pool].name
        holding = self.holding()
        if self.share is None:
            settings = live_settings(self.arguments)
            room = min(settings["max_hosts"] - sum(holding.values()),
                       settings["max_hosts_per_platform"] - holding[platform])
        else:
            room = self.share.get(platform, 0) - holding[platform]
        return max(0, min(room, self.boot_room))

    def unneeded(self, worker):
        """Whether a worker that has not started, nor missed its boot deadline, holds no lease of an open item and
        could take no queued one."""
        return not (worker.started or worker.missed_boot) and self.next_item(worker) is None and all(
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
            if self.purchase(pool) != "spot-then-on-demand":
                continue
            remaining = sum(self.remaining_seconds(item) for identity, item in self.open[pool].items()
                            if identity not in self.unresolved)
            if remaining <= self.pool_seconds[pool] * ON_DEMAND_TAIL_PERCENT / 100:
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

    def pool_demand(self, pool, now, late):
        """The market of the pool's next worker, if a new one would start some of its late items sooner."""
        if self.gave_up(pool):
            return None
        if self.on_demand_ready.get(pool, 0) <= now:
            if any(self.on_demand(item) for item in late):
                return "on-demand"
            return "spot" if late else None
        # While On-Demand admission is refused, Spot workers take every item Spot may still run.
        return "spot" if self.purchase(pool) != "on-demand" and any(
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

        def cooled(market):
            cooling = self.pool_regions[pool, market] = {
                region: failed_at for region, failed_at in self.pool_regions.get((pool, market), {}).items()
                if now - failed_at < REGION_COOL_DOWN_SECONDS}
            return replace(candidate, market=market, capacity_failures=tuple(cooling))

        candidate = cooled(market)
        if not launch_placements(self.arguments, candidate):
            # Each region of the platform's list has found no capacity in the market for this pool within its
            # cool-down. A pool short of Spot for its bounded wait launches On-Demand; otherwise it tries every
            # region again.
            if market == "spot" and self.spot_waited(pool, now):
                candidate = cooled("on-demand")
            if not launch_placements(self.arguments, candidate):
                self.pool_regions.pop((pool, candidate.market), None)
                candidate = replace(candidate, capacity_failures=())
        return candidate

    def launch(self, now):
        """Admit each pool's launches, then write each admitted worker's first leases and launch it, the workers on the
        request threads. A worker whose first leases S3 did not all take launches nothing, as a launch AWS refused
        does: it has ended, and is settled in the same loop once its cleanup is proven, so its reservation leaves the
        boot window before the campaign's next phase launches. An exception during admission still launches the
        workers admitted before it, so none stays recorded as starting with no launch, and an error launching them
        is noted on the admission's."""
        starting = []
        try:
            self.admit(now, starting)
        except BaseException as error:
            try:
                self.launch_admitted(starting)
            except Exception as failure:
                error.add_note(f"launching the workers admitted before it also failed: {failure}")
            raise
        self.launch_admitted(starting)
        failed = [worker for worker in starting if worker.launch_failure]
        for worker in failed:
            worker.gone = True
        for worker in self.clean_up(failed):
            self.finish(worker)

    def launch_admitted(self, starting):
        """Write the first leases of each worker in starting and launch it, as launch describes. A worker whose
        launch an error kept the controller from asking for has failed to launch."""
        requests, elsewhere, asked = {}, set(), set()

        def write_and_launch(entry):
            # The host finds its work waiting when its setup ends.
            worker, documents = entry
            written = write_leases(self.arguments, worker, documents)
            if not documents or len(written) < len(documents):
                return written, LaunchFailed("retryable-infrastructure", FIRST_LEASES_UNWRITTEN)
            asked.add(worker.job.host_epoch)
            try:
                return written, launch_worker(self.arguments.cloud, worker.job, requests[worker.job.host_epoch],
                                              worker.job.host_epoch in elsewhere)
            except LaunchFailed as failure:
                return written, failure

        try:
            for worker in starting:
                try:
                    requests[worker.job.host_epoch] = launch_requests(self.arguments, worker.job, worker.metadata)
                except LaunchFailed as failure:
                    worker.launch_failure = (failure.classification, failure.detail)
                # A region's refusal moves the pool to a region it has not cooled in the market.
                if set(launch_regions(self.arguments, worker.job)) - {worker.job.region,
                                                                      *worker.job.capacity_failures}:
                    elsewhere.add(worker.job.host_epoch)
            pending = [(worker, [(sequence, self.lease_document(worker, sequence))
                                 for sequence in sorted(worker.unsent)])
                       for worker in starting if worker.job.host_epoch in requests]
            for (worker, _), (written, launched) in zip(pending, self.concurrently(write_and_launch, pending)):
                self.sent(worker, written)
                if isinstance(launched, LaunchFailed):
                    worker.launch_failure = (launched.classification, launched.detail)
                    continue
                worker.metadata["instance_id"], worker.metadata["availability_zone"] = launched
                if worker.instance_id:
                    write_attempt(self.attempts_path, worker.job, "started",
                                  f"instance_id={worker.instance_id};availability_zone={launched[1]}")
        finally:
            # A launch asked for whose answer was lost is found by its session's tag.
            for worker in starting:
                if worker.job.host_epoch not in asked and not worker.launch_failure:
                    worker.launch_failure = ("retryable-infrastructure", LAUNCH_NOT_ATTEMPTED)

    def admit(self, now, starting):
        """Admit the launches the pools need now, recording each admitted worker, with its first leases issued, in
        starting. Each pool in turn first takes up to its even part of the boot window, and the pools that need more
        then share what is left, so every pool that needs hosts gets its part; a pool short of capacity in a market
        tries one launch a loop in it until one of its workers in the market starts. Each pool's late items are
        projected once, and each admitted worker's first leases take theirs off them; a first lease of an item not
        late projects them again."""
        blocked_vcpus = None
        late = {}

        def take_late(pool, worker):
            # No worker reached a late item before a new worker's boot, so leasing one changes no earlier start. A
            # lease of an item not late frees the worker that would have taken it for as many later items as their
            # estimates fit before then, which only the projection counts.
            leased = {worker.leases[sequence] for sequence in worker.outstanding()} - {None}
            items = [item for item in late[pool] if item.identity not in leased]
            late[pool] = items if len(late[pool]) - len(items) == len(leased) else self.late_items(pool, now)

        def admit_pool(pool, limit):
            # Whether the pool admitted its limit and may take more; None once a refusal holds for every pool.
            nonlocal blocked_vcpus
            if pool not in late:
                late[pool] = self.late_items(pool, now)
            # A pool with no room for another worker is not trying to launch, so no refusal stands for it.
            if not self.launch_room(pool):
                self.refused.pop(pool, None)
                return False
            # A worker is planned only once its platform has room for it.
            while self.launch_room(pool) and self.pool_ready.get(pool, 0) <= now:
                market = self.pool_demand(pool, now, late[pool])
                if market is None:
                    self.refused.pop(pool, None)
                    return False
                candidate = self.candidate(pool, market)
                if candidate.market == "on-demand" and self.on_demand_ready.get(pool, 0) > now:
                    return False
                # A launch reserves the worker's boot and a first lease of the pool's longest queued item.
                boot_by = math.ceil(time.time()) + UNIT_WORKER_BOOT_ALLOWANCE_SECONDS
                first_lease = lease_timeout(max(map(self.remaining_seconds, self.queued(pool)), default=0))
                candidate = replace(candidate, boot_by=boot_by, stop_by=boot_by + first_lease +
                                    UNIT_WORKER_IDLE_SECONDS + UNIT_WORKER_WIND_DOWN_SECONDS)
                if blocked_vcpus is not None and candidate.platform.vcpus >= blocked_vcpus:
                    return False
                # Checked before the reservation, which a worker owns only once it starts.
                self.check_frozen_inputs()
                check_purchase(self.arguments, candidate)
                candidate, ready_at, refusal, scan, blocked = admit_launch(
                    self.arguments, self.result_root, self.attempts_path, self.capacity_waits, candidate, now, now,
                    self.observed)
                self.candidates[pool] = candidate
                if not refusal:
                    self.refused.pop(pool, None)
                elif self.refused.get(pool, (None,))[0] != refusal:
                    self.refused[pool] = (refusal, time.time())
                if refusal and candidate.market == "on-demand" and market == "on-demand":
                    # The pool's Spot-eligible items need not wait for On-Demand admission.
                    self.on_demand_ready[pool] = ready_at
                    if self.pool_demand(pool, now, late[pool]) == "spot":
                        continue
                if refusal:
                    self.pool_ready[pool] = ready_at
                    blocked_vcpus = blocked or blocked_vcpus
                    return False if scan else None
                self.start(pool, candidate, now, starting)
                take_late(pool, starting[-1])
                if (pool, candidate.market) in self.shortages:
                    return False
                limit -= 1
                if not limit:
                    return True
            return False

        wanting = list(self.pools)
        for limit in (math.ceil(self.boot_room / len(self.pools)), math.inf):
            for pool in list(wanting):
                admitted = admit_pool(pool, limit)
                if admitted is None:
                    return
                if not admitted:
                    wanting.remove(pool)

    def start(self, pool, job, now, starting):
        """Record an admitted worker in the phase's workers and in starting, and issue its first leases, before its
        launch. Until its starting record is written no worker owns the admission's reservation, so one whose record
        fails releases it, noting any failure to."""
        del self.candidates[pool]
        # A launch takes over a free slot, and its failures, before it opens a new one named for it.
        slots = self.free_slots.get(pool)
        slot, slot_failures = slots[0] if slots else (job.host_epoch, 0)
        try:
            session = worker_session(self.arguments.campaign_id, job)
            metadata = worker_metadata(self.arguments, job, session, f"campaigns/{self.arguments.campaign}/{session}")
            # The starting record names the session before the launch, so a restart finds its instance by its tag.
            write_attempt(
                self.attempts_path, job, "starting",
                f"region={job.region};placement_score={job.placement_score}" +
                (f";capacity_failures={','.join(job.capacity_failures)}" if job.capacity_failures else "") +
                f";slot={slot};slot_failures={slot_failures};boot_by={job.boot_by};stop_by={job.stop_by}"
                f";session={session};prefix={metadata['prefix']}")
        except BaseException as error:
            # No host was launched for it.
            try:
                self.budget.release(self.arguments.campaign_id, job.host_epoch, cleanup_verified=True)
            except Exception as failure:
                error.add_note(f"releasing host epoch {job.host_epoch}'s reservation also failed: {failure}")
            raise
        if slots:
            slots.pop(0)
        self.boot_room -= 1
        # The worker boots from the poll that launched it, so a projection in that poll counts it as booting
        # no later than a further launch would.
        worker = self.workers[job.host_epoch] = UnitWorker(
            job, metadata, job_result_root(self.result_root, job) / session, slot=slot, slot_failures=slot_failures,
            launched_at=now, stop_by=job.stop_by)
        starting.append(worker)
        if job.market == "on-demand" and pool in self.tail:
            self.tail_on_demand[pool] = self.tail_on_demand.get(pool, 0) + 1
        self.lease(worker, launching=True)

    def sweep(self, observed):
        """One loop of a phase paused for a fault, which trusts nothing more its hosts report but that they booted:
        report each booting worker that started to the boot window, stop-lease every open worker once the campaign
        is stopping, prove each ended worker's cleanup, releasing its reservation, and go on stopping the hosts asked
        to terminate. Its other hosts finish the leases they hold and stop themselves once their idle time runs out.
        The phase's next dispatch, once it is resumed, settles each worker from its ledgers and recovered uploads, as
        a restart does."""
        self.observed = observed
        live, ended = self.partition()
        booting = [worker for worker in live if not worker.started]
        for worker, objects in zip(booting, self.concurrently(self.listing, booting)):
            if objects is not None:
                self.note_start(worker, set(objects))
        self.report_boots()
        if self.steering == "stopping":
            for worker in live:
                if worker.open():
                    self.issue(worker, None)
            self.send(live)
        for epoch, worker in self.workers.items():
            if epoch in self.terminated:
                self.stop_host(worker)
        for worker in self.clean_up(ended):
            self.budget.release(self.arguments.campaign_id, worker.job.host_epoch, cleanup_verified=True)
            del self.workers[worker.job.host_epoch]

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
        """Run the phase alone, as the campaign's controller would with no other phase."""
        deadline = self.begin()
        try:
            self.recover()
            while not self.step(deadline):
                time.sleep(UNIT_POLL_SECONDS)
        finally:
            self.requests.shutdown(cancel_futures=True)
            self.acceptances.shutdown(cancel_futures=True)
        self.end()

    def begin(self):
        """Check that the phase may run and record its start; returns its deadline as it stands now. The caller then
        recovers it."""
        validate_execution_policy(self.arguments, self.result_root)
        self.fingerprint = frozen_input_fingerprint(self.root, self.arguments, self.items_path)
        self.started_at = self.start_campaign()
        return self.deadline()

    def deadline(self):
        """When, on the monotonic clock, the begun phase's launches end: its timeout after its start, plus the time
        its pauses ledger records it paused, a pause still open lasting until now, as one the controller stopped
        during does until the campaign records when it ended."""
        now = time.time()
        paused, _ = paused_seconds(self.result_root / PHASE_PAUSES, self.started_at, now)
        return time.monotonic() + max(0, phase_timeout(self.arguments) + paused - max(0, now - self.started_at))

    def step(self, deadline, observed=None):
        """One loop of the phase, sharing what observed holds of each region's fleet with the campaign's other
        phases; returns whether it is over, with every worker settled and nothing queued."""
        self.observed = {} if observed is None else observed
        self.inputs_checked = False
        now = time.monotonic()
        deadline_reached = now >= deadline
        self.poll()
        self.report_boots()
        # Work handed back after the deadline, or to a pool that gave up, is not leased again.
        self.resolve_unlaunchable(deadline_reached)
        self.update_tail()
        if self.steering == "stopping":
            for worker in self.workers.values():
                if worker.open():
                    self.issue(worker, None)
        else:
            self.shed_hosts()
        if self.steering in ("running", "draining"):
            for worker in self.workers.values():
                self.lease(worker)
        self.send(self.workers.values())
        if not deadline_reached and self.steering == "running":
            self.launch(now)
        else:
            self.refused = {}
        for epoch, worker in self.workers.items():
            # A lost host is stopped at once; its cleanup still recovers a completion that lands.
            # So is a booting host once the pool has nothing it could take. Its stop lease first records
            # it stopping, so through its cleanup neither this controller nor a restarted one counts it
            # as open for work.
            if (worker.lost or self.unneeded(worker)) and epoch not in self.terminated:
                if not (worker.lost or worker.stopping):
                    self.issue(worker, None)
                self.terminated[epoch] = time.time()
            if epoch in self.terminated:
                self.stop_host(worker)
        for name in sorted(self.milestones() - self.reached):
            self.reached.add(name)
            self.telemetry.event("milestone", f"{self.key}: {name}", phase=self.key, milestone=name)
        return not self.workers and not self.queued()

    def end(self):
        """Record the phase's unresolved items, and with none, validate it and mark it complete."""
        unresolved = self.unresolved_items(ending=True)
        write_unresolved_units(self.result_root / "unresolved-units.tsv", unresolved)
        if unresolved:
            details = ", ".join(f"{item.identity} {detail}" for item, detail in unresolved)
            raise SystemExit(f"campaign completed with unresolved units: {details}")
        self.validate_phase()
        if self.validation.kind == "language" and self.arguments.phase == "primary":
            aggregate_language_plans(self.arguments, self.result_root)
        # Every worker has exited, each settled only once its cleanup was verified.
        write_phase_complete(self.result_root, self.arguments)

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
class Phase:
    """One step of a candidate's campaign, run by the campaign's controller once every step it comes after is
    complete: a unit phase, whose options its namespace sets over the campaign's, or with no options, the reduction
    of the candidate's primary. Its key is its root relative to the campaign root."""
    key: str
    root: Path
    after: tuple
    options: dict = None
    # Whether the step has anything to do once those it comes after completed; confirmation runs only for listed jobs.
    needed: object = lambda: True
    # pending; starting while its setup or reduction runs on its thread; running while its dispatch does; then
    # complete, or failed, with why.
    state: str = "pending"
    failure: str = ""
    future: object = None
    # A running phase's dispatcher.
    dispatcher: object = None
    # Why the phase is paused for a fault in its setup or dispatch, until it is resumed and its dispatch restarts.
    fault: str = ""
    # Whether the phase's pauses ledger records a pause open.
    paused: bool = False
    # The settings' regions as they stood when the phase's regions were last checked, and when, on the monotonic
    # clock, and a check of a running phase's regions on the setup thread, with the copy of its namespace it checks.
    regions: str = None
    checked_at: float = None
    checking: tuple = None

    @property
    def reduction(self):
        return self.options is None

    def complete(self):
        return (self.root / ("output-checksums.tsv" if self.reduction else PHASE_COMPLETE)).is_file()


def divide(hosts, weights, caps):
    """Split hosts among the keys of weights: each key with room first takes one host, in order, while hosts last,
    and the rest go in proportion to the keys' weights, evenly when none weighs anything, none past its cap, a capped
    key's part going to the others. Rounding leaves its hosts to the largest remainders."""
    shares = {key: 0 for key in weights}
    left = hosts
    for key in weights:
        if left and caps[key] > 0:
            shares[key], left = 1, left - 1
    open_keys = [key for key in weights if shares[key] < caps[key]]
    while left > 0 and open_keys:
        total = sum(weights[key] for key in open_keys)
        exact = {key: left * (weights[key] / total if total else 1 / len(open_keys)) for key in open_keys}
        capped = [key for key in open_keys if shares[key] + exact[key] >= caps[key]]
        if capped:
            for key in capped:
                left -= caps[key] - shares[key]
                shares[key] = caps[key]
            open_keys = [key for key in open_keys if key not in capped]
            continue
        whole = {key: math.floor(exact[key]) for key in open_keys}
        left -= sum(whole.values())
        for key in sorted(open_keys, key=lambda key: whole[key] - exact[key])[:left]:
            whole[key] += 1
        for key in open_keys:
            shares[key] += whole[key]
        break
    return shares


def allocate(demands, max_hosts, per_platform, pinned=None):
    """Each running phase's target hosts by platform. demands holds each phase's work left on each platform, as
    (estimated seconds, items); a host runs one item at a time, so a share never passes the items it holds. A phase
    pinned, in pinned, to a number of hosts takes them first, split across its platforms by their seconds left. The
    hosts the pins leave are split across platforms by their seconds left, each platform capped at what its limit
    leaves and its items, and then a platform's hosts among its phases by their seconds left, each capped at its
    items. Every platform, and every phase on it, with work left takes one host first while hosts last."""
    shares = {key: {} for key in demands}
    caps = {platform: per_platform for demand in demands.values() for platform in demand}
    for key, hosts in (pinned or {}).items():
        shares[key] = divide(min(hosts, max_hosts), {platform: seconds for platform, (seconds, _) in
                                                     demands[key].items()},
                             {platform: min(caps[platform], items) for platform, (_, items) in demands[key].items()})
        max_hosts -= sum(shares[key].values())
        for platform, share in shares[key].items():
            caps[platform] -= share
    automatic = {key: demand for key, demand in demands.items() if key not in (pinned or {})}
    platforms = sorted({platform for demand in automatic.values() for platform in demand})

    def left(platform, index):
        return sum(demand[platform][index] for demand in automatic.values() if platform in demand)

    hosts = divide(max_hosts, {platform: left(platform, 0) for platform in platforms},
                   {platform: min(caps[platform], left(platform, 1)) for platform in platforms})
    for platform in platforms:
        phases = [key for key, demand in automatic.items() if platform in demand]
        for key, share in divide(hosts[platform], {key: demands[key][platform][0] for key in phases},
                                 {key: demands[key][platform][1] for key in phases}).items():
            shares[key][platform] = share
    return shares


def confirmation_jobs_listed(path):
    with path.open(newline="") as input_file:
        return any(True for _ in csv.DictReader(input_file, delimiter="\t"))


def candidate_phases(arguments, candidates):
    """Each candidate's steps in the order they start: build, smoke, primary, the reduction of its primary, and a
    confirmation when the reduction lists jobs, and with language plans, the plans' smoke and primary on the same
    build. A phase takes the candidate's frozen commit and the options that decide what it measures."""
    phases = []
    for candidate in candidates:
        directory = arguments.campaign_root / candidate.name
        jobs = directory / "reduction" / "confirmation-jobs.tsv"

        def phase(key, after, candidate=candidate, directory=directory, **options):
            return Phase(f"{candidate.name}/{key}", directory / key,
                         tuple(f"{candidate.name}/{step}" for step in after), {
                             "phase": key.rsplit("/", 1)[-1], "candidates": [(candidate.name, candidate.commit)],
                             "selected_shards": arguments.selected_shards, "smoke_protocol": "smoke",
                             "language_plan": [], "confirmation_jobs": None, **options})

        phases += [
            phase("build", (), selected_shards=None),
            phase("smoke", ("build",), smoke_protocol=arguments.smoke_protocol),
            phase("primary", ("smoke",)),
            Phase(f"{candidate.name}/reduction", directory / "reduction", (f"{candidate.name}/primary",)),
            replace(phase("confirmation", ("reduction",), confirmation_jobs=jobs),
                    needed=lambda jobs=jobs: confirmation_jobs_listed(jobs))]
        if arguments.language_plan:
            plans = {"language_plan": arguments.language_plan, "selected_shards": None}
            phases += [phase("lang/smoke", ("build",), **plans), phase("lang/primary", ("lang/smoke",), **plans)]
    return phases


def reduce_primary(arguments, phase):
    """Reduce the candidate's primary into the reduction's root, as aggregate_results does, with the primary's events,
    which its first event writes. The reduction writes output-checksums.tsv last, so an interrupted one runs again."""
    primary = phase.root.parent / "primary"
    events = primary / "campaign-events.tsv"
    options = aggregate_results.parser().parse_args([
        f"--primary-receipts={primary / 'accepted-units.tsv'}", f"--artifacts-root={primary}",
        *([f"--events={events}"] if events.is_file() else []), f"--output-dir={phase.root}",
        *(f"--shard={shard}" for shard in arguments.selected_shards or ()),
        *([f"--release-version={arguments.release_version}"] if arguments.release_version else [])])
    try:
        aggregate_results.aggregate(options)
    except aggregate_results.ReductionError as error:
        raise SystemExit(f"cannot reduce {primary}: {error}") from error


def prepare_phase(arguments, root):
    """Plan one of the campaign's phases, check that it may run, stage what its hosts read, and begin its dispatch;
    returns its dispatcher and no fault. A check its launches need that fails once its dispatcher is built, as when
    its region's quota no longer holds its limit, comes back as the fault, with the dispatch not begun, so the phase
    still stops the hosts its ledgers name. This runs on the campaign's setup thread, so the controller's loop never
    waits for it."""
    items, kind, plans, platforms, result_root = plan_phase(arguments, root)
    validate_source(root, arguments)
    validate_dependencies(root, arguments)
    resolve_engine_builds(root, arguments)
    validation = unit_validation(root, arguments, kind, plans)
    dispatcher = UnitDispatcher(arguments, root, result_root, items, platforms, validation)
    try:
        validate_prerequisites(arguments, validation)
        if arguments.language_plan:
            write_language_bundles(arguments, result_root)
        check_regions(arguments, platforms)
        dispatcher.begin()
        return dispatcher, None
    except (Exception, SystemExit) as error:
        return dispatcher, error


class Campaign:
    """The campaign's controller: every candidate's phases in this one process, sharing the fleet's hosts.

    A phase starts once every step it comes after is complete. Its setup runs on the setup thread and a reduction on
    the reduction thread, so a loop never waits for either. Each loop steps every running phase's dispatch, the
    phases sharing the request and acceptance threads, the caches of what AWS offers, and one look at each region's
    fleet, after setting each phase's share of the hosts from the work every phase has left. A failed step stops
    only the steps after it, and a fault in a phase's setup or dispatch pauses only that phase."""

    def __init__(self, arguments, root, phases):
        self.arguments = arguments
        self.root = root
        self.phases = {phase.key: phase for phase in phases}
        self.setup = ThreadPoolExecutor(1)
        self.reductions = ThreadPoolExecutor(1)
        arguments.pools = (ThreadPoolExecutor(REQUEST_THREADS), ThreadPoolExecutor(ACCEPTANCE_THREADS))
        for cache in ("networks", "images", "zone_offerings", "placement_scores", "vcpu_quotas"):
            vars(arguments).setdefault(cache, {})
        # Each phase's state as the settings last recorded it, and how many settings changes were logged.
        self.recorded = {}
        self.changes = None
        self.terminated = False
        self.telemetry = getattr(arguments, "telemetry", None) or telemetry.Telemetry()

    def run(self):
        """Run every step that can run, or once the campaign is stopped, until every worker's cleanup is proven;
        returns the steps that failed. A paused phase, even one paused for a fault, is still running. SIGTERM stops
        only this process: its hosts go on with the leases they hold, which the next controller adopts, so it returns
        none."""
        previous = signal.signal(signal.SIGTERM, self.terminate)
        self.telemetry.event("controller-started", f"controller started as process {os.getpid()}")
        try:
            while not self.terminated:
                began = time.monotonic()
                self.reload()
                self.advance()
                self.record()
                self.log_changes()
                running = [phase for phase in self.phases.values() if phase.state == "running"]
                starting = any(phase.state == "starting" for phase in self.phases.values())
                if not running and not starting:
                    break
                stop = live_settings(self.arguments)["stop"]
                if stop and not starting and not any(self.holds_hosts(phase) for phase in running):
                    self.telemetry.event("campaign-stopped", f"campaign stopped by {stop['by']}: {stop['reason']}; "
                                         "every worker's cleanup is proven", actor=stop["by"])
                    break
                self.track_pauses(running)
                # A phase paused because its setup failed before it built its dispatch has none until it is resumed.
                running = [phase for phase in running if phase.dispatcher]
                self.follow_regions(running)
                self.steer(running)
                self.assign_shares(running)
                observed = {}
                for phase in running:
                    self.step(phase, observed)
                self.export(time.monotonic() - began)
                time.sleep(UNIT_POLL_SECONDS)
            else:
                self.telemetry.event("controller-stopped", "controller stopped by SIGTERM; the campaign's hosts keep "
                                     "their leases for the next controller")
                return []
        finally:
            signal.signal(signal.SIGTERM, previous)
            for pool in (self.setup, self.reductions, *self.arguments.pools):
                pool.shutdown(cancel_futures=True)
        return [phase for phase in self.phases.values() if phase.state == "failed"]

    def terminate(self, signal_number, frame):
        self.terminated = True

    def track_pauses(self, running):
        """Record in each running phase's pauses ledger when it is paused, for a fault or by its settings, and when it
        runs again; the phase's deadline moves out by each pause the ledger records, so a pause spends none of the
        phase's time. A change of its settings is recorded as of when the settings took it, so a pause or resume made
        while no controller ran counts from when it was made, and a fault as of when it is found. A phase whose
        setup never made its root records none: its time has not begun."""
        settings = live_settings(self.arguments)
        now = time.time()
        for phase in running:
            steering = settings["phases"].get(phase.key, {})
            paused = bool(phase.fault) or steering.get("state") == "paused"
            if paused != phase.paused and phase.root.is_dir():
                at = now if phase.fault and steering.get("state") != "paused" else steering.get("since", now)
                record_pause(phase.root / PHASE_PAUSES, "paused" if paused else "resumed", at)
                phase.paused = paused

    def holds_hosts(self, phase):
        """Whether the running phase has workers whose cleanup is not proven: those its dispatch follows, or with no
        dispatch, those the fleet still reserves for it."""
        if phase.dispatcher:
            return bool(phase.dispatcher.workers)
        return bool(self.arguments.shared_budget.reservations(phase.root))

    def reload(self):
        """Read the settings as they stand now, or keep the last good ones while the file does not validate."""
        settings = self.arguments.settings
        try:
            settings.reload()
        except SettingsError as error:
            print(f"keeping campaign settings version {settings.current['version']}: {error}", flush=True)

    def log_changes(self):
        """Log each settings change made since the last loop, with its actor; a controller logs none made before it
        started."""
        changes = list(csv.DictReader(ledger_lines(self.arguments.settings.events_path), delimiter="\t"))
        for change in changes[len(changes) if self.changes is None else self.changes:]:
            self.telemetry.event("settings-change", f"settings version {change['version']} by {change['actor']}: "
                                 f"{change['change']}", actor=change["actor"], version=change["version"],
                                 change=change["change"])
        self.changes = len(changes)

    def export(self, loop_seconds):
        """Export the campaign's health and each phase's state, progress and spend, as this loop, which took
        loop_seconds, left them."""
        settings = live_settings(self.arguments)
        now = time.time()
        fleet = read_fleet_state(self.arguments.shared_budget.state_path) or {"reservations": {}, "completed": {}}
        live = list(fleet["reservations"].values())
        window = settings["boot_window"]
        points = {
            "regulator_loop_seconds": [({}, loop_seconds)],
            "regulator_spending_stop": [({}, int(bool(settings["spending_stop"])))],
            "regulator_settings_version": [({}, settings["version"])],
            "regulator_boot_window_hosts": [({}, min(window["size"], window["max"]))],
            "regulator_max_usd": [({}, settings["max_usd"])],
            "regulator_region_vcpus": [
                ({"region": region, "market": market}, sum(item["vcpus"] for item in live
                                                           if (item["region"], item["market"]) == (region, market)))
                for region in settings["regions"] for market in ("spot", "on-demand")],
            "regulator_region_max_vcpus": [
                ({"region": region, "market": market}, limits[name]) for region, limits in settings["regions"].items()
                for market, name in (("spot", "max_vcpus"), ("on-demand", "on_demand_max_vcpus")) if name in limits],
            "regulator_region_booting_hosts": [
                ({"region": region}, sum(item["region"] == region and "reported_at" not in item for item in live))
                for region in settings["regions"]]}

        def point(name, labels, value):
            points.setdefault(name, []).append((labels, value))

        for phase in self.phases.values():
            labels = {"phase": phase.key}
            point("regulator_phase_state", {**labels, "state": "paused" if phase.fault else
                                            phase.dispatcher.steering if phase.state == "running" else phase.state}, 1)
            if phase.reduction:
                continue
            finish = None
            if phase.state == "running" and phase.dispatcher:
                for name, values in phase.dispatcher.points(now).items():
                    points.setdefault(name, []).extend(values)
                if phase.dispatcher.started_at is not None:
                    point("regulator_deadline_time", labels, now + phase.dispatcher.deadline() - time.monotonic())
                finish = phase.dispatcher.finish_estimate()
            held = owner_fleet(fleet, phase.key, now)
            rate = sum(item["hourly_rate"] for item in held.hosts)
            if held.ready:
                point("regulator_boot_to_ready_seconds", labels, statistics.median(held.ready))
            point("regulator_spend_usd", labels, held.spent)
            point("regulator_spend_rate_usd_per_hour", labels, rate)
            point("regulator_spend_projected_usd", labels, held.spent + (
                held.reserved if finish is None else rate * finish / 3600))
        self.telemetry.observe(points)

    def record(self):
        """Record in the settings each phase whose state changed since the settings last recorded it, one starting as
        running and one faulted as paused with its fault, or while the change cannot be recorded, try again next loop.
        A reduction holds no hosts, so the settings never name it. A phase that ends is recorded complete or failed;
        until then a phase paused or draining stays so, and a pending one only gains its entry, so a restart keeps
        how the phases were steered."""
        states = {phase.key: "paused" if phase.fault else "running" if phase.state == "starting" else phase.state
                  for phase in self.phases.values() if not phase.reduction}
        changed = {key: state for key, state in states.items() if self.recorded.get(key) != state}
        if not changed:
            return

        def update(settings):
            for key, state in changed.items():
                steering = settings["phases"].setdefault(key, {"state": "pending", "share": "auto"})
                if state == "paused":
                    steering.update(state="paused", reason=self.phases[key].fault)
                elif state in ("complete", "failed") or state == "running" and steering["state"] not in (
                        "paused", "draining"):
                    steering.pop("reason", None)
                    steering["state"] = state

        if self.arguments.settings.controller_change("; ".join(
                f"{key} {state}" + (f": {self.phases[key].fault}" if state == "paused" else "")
                for key, state in changed.items()), update):
            self.recorded.update(changed)

    def advance(self):
        """Adopt each phase whose setup finished, recovering its dispatch from its ledgers, settle each finished
        reduction, and start each step whose predecessors completed, unless it is complete already or has nothing
        to do. A setup that fails, as one whose region's quota no longer holds its limit, pauses its phase for that
        fault; once it built the phase's dispatch, the phase still sweeps the hosts it holds, which count against
        the limits, and with none, its hosts wait for it. A phase paused for a fault starts again once it is
        resumed, as it would under a restarted controller, which rebuilds its dispatch from its ledgers."""
        settings = live_settings(self.arguments)
        for phase in self.phases.values():
            if phase.state == "starting" and phase.future.done():
                try:
                    result = phase.future.result()
                except (Exception, SystemExit) as error:
                    if phase.reduction:
                        self.fail(phase, error)
                    else:
                        phase.state = "running"
                        self.fault(phase, error)
                    continue
                if phase.reduction:
                    self.finish(phase)
                    continue
                phase.dispatcher, fault = result
                phase.state = "running"
                phase.paused = paused_seconds(phase.root / PHASE_PAUSES, 0, time.time())[1]
                try:
                    phase.dispatcher.recover()
                except (Exception, SystemExit) as error:
                    fault = fault or error
                if fault is not None:
                    self.fault(phase, fault)
            elif phase.fault:
                # Resumed once its pause was recorded.
                if self.recorded.get(phase.key) == "paused" and settings["phases"][phase.key]["state"] != "paused":
                    phase.fault, phase.dispatcher = "", None
                    self.start(phase)
            elif phase.state == "pending" and all(self.phases[key].state == "complete" for key in phase.after):
                if phase.complete() or not phase.needed():
                    phase.state = "complete"
                    continue
                # Once the campaign is stopped, a phase starts only to stop the hosts an earlier controller left it.
                if (settings["stop"] and not phase.reduction and
                        not self.arguments.shared_budget.reservations(phase.root)):
                    continue
                self.start(phase)

    def start(self, phase):
        self.telemetry.event("phase-starting", f"{phase.key}: starting", phase=phase.key)
        phase.state = "starting"
        phase.regions, phase.checked_at = self.regions(), time.monotonic()
        if phase.reduction:
            phase.future = self.reductions.submit(reduce_primary, self.arguments, phase)
        else:
            arguments = SimpleNamespace(**{**vars(self.arguments), **phase.options})
            phase.future = self.setup.submit(prepare_phase, arguments, self.root)

    def regions(self):
        settings = live_settings(self.arguments)
        return json.dumps([settings["regions"], settings["platform_regions"]], sort_keys=True)

    def follow_regions(self, running):
        """Check each running phase's regions again once the settings' regions or a platform's list of them
        changed, and every REGION_RECHECK_SECONDS, which puts its archives again before they expire, as its setup
        did, on the setup thread; a phase launches in the regions that check finds only once it passes, and until
        then where it did. A check that fails is logged and runs again at the next change or the next day."""
        regions = self.regions()
        now = time.monotonic()
        for phase in running:
            if phase.checking is not None and phase.checking[0].done():
                future, checked, namespace = phase.checking
                phase.checking = None
                try:
                    future.result()
                except (Exception, SystemExit) as error:
                    print(f"{phase.key}: launches stay in the regions checked before: {error}", flush=True)
                else:
                    namespace.ami_names, namespace.region_offerings = checked.ami_names, checked.region_offerings
            if (phase.checking is None and not phase.fault and
                    (phase.regions != regions or now - phase.checked_at >= REGION_RECHECK_SECONDS)):
                phase.regions, phase.checked_at = regions, now
                namespace = phase.dispatcher.arguments
                checked = SimpleNamespace(**vars(namespace))
                phase.checking = (self.setup.submit(check_regions, checked, list(phase.dispatcher.platforms.values())),
                                  checked, namespace)

    def steer(self, running):
        """Steer each running phase as the settings say: stopping once the campaign is stopped, else as its state
        is, running, paused, or draining. A phase paused for a fault launches nothing whatever they say."""
        settings = live_settings(self.arguments)
        for phase in running:
            state = "paused" if phase.fault else settings["phases"].get(phase.key, {}).get("state")
            phase.dispatcher.steering = ("stopping" if settings["stop"] else
                                         state if state in ("paused", "draining") else "running")

    def step(self, phase, observed):
        """Run one loop of the phase's dispatch, or of a phase paused for a fault, its cleanup. A fault in its
        dispatch, such as a marker unlike its lease, changed frozen inputs, conflicting receipts, or a ledger error,
        pauses it alone. A resource AWS lists that the fleet cannot account for stops the fleet's spending."""
        if phase.fault:
            try:
                phase.dispatcher.sweep(observed)
            except (Exception, SystemExit) as error:
                print(f"{phase.key}: cleanup failed: {error}; retrying", flush=True)
            return
        try:
            finished = phase.dispatcher.step(phase.dispatcher.deadline(), observed)
        except AccountMismatch as error:
            print(f"spending stop: {error}; no launch or lease extension is admitted until it is cleared", flush=True)
            self.arguments.shared_budget.stop_spending(str(error))
            return
        except (Exception, SystemExit) as error:
            self.fault(phase, error)
            return
        if finished:
            self.end(phase)

    def assign_shares(self, running):
        """Give each running phase that launches its share of each platform's hosts: the hosts it holds, and whatever
        the limits leave free toward the target its work left, or its pinned share, gives it. The hosts a paused or
        draining phase holds count against the limits until they stop.

        A phase above its target gives up the hosts past it while its share is pinned, or while another phase is
        below its target on that platform, or anywhere once the fleet's limit leaves no host free, or while the hosts
        held pass the fleet's or the platform's limit, as once a limit is lowered. A draining phase's hosts go on
        taking its leases, so it gives up as many of its open hosts as the open hosts still pass a limit by once the
        launching phases have given up theirs. Otherwise hosts move between phases only when a phase is waiting for
        them, so the shares' changes as the work left is corrected move none on their own."""
        settings = live_settings(self.arguments)
        limits = settings["max_hosts"], settings["max_hosts_per_platform"]
        launching = [phase for phase in running if phase.dispatcher.steering == "running"]
        pinned = {phase.key: share for phase in launching
                  if (share := settings["phases"].get(phase.key, {}).get("share", "auto")) != "auto"}
        targets = allocate({phase.key: phase.dispatcher.demand() for phase in launching}, *limits, pinned)
        held = {phase.key: phase.dispatcher.holding() for phase in running}
        free = limits[0] - sum(sum(counts.values()) for counts in held.values())
        free_platform = {}
        for counts in held.values():
            for platform, hosts in counts.items():
                free_platform[platform] = free_platform.get(platform, limits[1]) - hosts
        waiting = set()
        for phase in launching:
            share = {}
            for platform, target in targets[phase.key].items():
                own = held[phase.key].get(platform, 0)
                room = max(0, min(target - own, free, free_platform.get(platform, limits[1])))
                free -= room
                free_platform[platform] = free_platform.get(platform, limits[1]) - room
                share[platform] = own + room
                if share[platform] < target:
                    waiting.add((phase.key, platform))
            phase.dispatcher.share = share
        open_hosts = {phase.key: phase.dispatcher.holding(open_only=True) for phase in running}
        over_platform = sum(open_hosts.values(), Counter())
        over = sum(over_platform.values()) - limits[0]
        over_platform = {platform: hosts - limits[1] for platform, hosts in over_platform.items()}
        for phase in launching + [phase for phase in running if phase not in launching]:
            shed = {}
            for platform, hosts in open_hosts[phase.key].items():
                excess = hosts - targets.get(phase.key, {}).get(platform, 0)
                if phase in launching and excess > 0 and (
                        phase.key in pinned or free < 0 or free_platform[platform] < 0 or any(
                            key != phase.key and (other == platform or free <= 0) for key, other in waiting)):
                    shed[platform] = excess
                elif phase.dispatcher.steering == "draining" and max(over, over_platform[platform]) > 0:
                    shed[platform] = min(hosts, max(over, over_platform[platform]))
                over -= shed.get(platform, 0)
                over_platform[platform] -= shed.get(platform, 0)
            phase.dispatcher.shed = shed

    def end(self, phase):
        try:
            phase.dispatcher.end()
        except (Exception, SystemExit) as error:
            self.fail(phase, error)
        else:
            self.finish(phase)

    def finish(self, phase):
        phase.state = "complete"
        self.telemetry.event("phase-complete", f"{phase.key}: complete", phase=phase.key)

    def fail(self, phase, error):
        phase.state, phase.failure = "failed", str(error) or type(error).__name__
        self.telemetry.event("phase-failed", f"{phase.key}: failed: {phase.failure}", phase=phase.key)

    def fault(self, phase, error):
        phase.fault = "; ".join([str(error) or type(error).__name__, *getattr(error, "__notes__", ())])
        self.telemetry.event("phase-paused", f"{phase.key}: paused: {phase.fault}", phase=phase.key)


def refuse_old_root(campaign_root):
    """Refuse a campaign root the earlier controllers started, which ran each phase as its own process with its
    share of the hosts frozen in its execution policy, or one whose fleet names its phases by their roots on the
    control host; a root is never migrated. status, --results, the reduction, and archiving still read its ledgers."""
    candidates = [campaign_root / OLD_DRIVER_RECORD]
    for pattern in ("*/*/", "*/lang/*/"):
        candidates += sorted(campaign_root.glob(pattern + OLD_PHASE_RECORD))
        candidates += [path for path in sorted(campaign_root.glob(pattern + "execution-policy.json"))
                       if set(OLD_EXECUTION_POLICY_FIELDS) & set(json.loads(path.read_text()))]
    found = next((path for path in candidates if path.is_file()), None)
    if found is not None:
        raise SystemExit(f"{campaign_root} was started by the earlier per-phase controllers, as {found} shows; this "
                         "controller cannot resume it, so start a new campaign root")
    fleet = read_fleet_state(campaign_root / STATE_FILE) or {"reservations": {}, "completed": {}}
    if any(item["owner"].startswith("/") for item in [*fleet["reservations"].values(), *fleet["completed"].values()]):
        raise SystemExit(f"{campaign_root} was started by a controller that named its phases by their roots on the "
                         f"control host, as {campaign_root / STATE_FILE} shows; this controller cannot resume it, so "
                         "start a new campaign root")


def load_settings(arguments):
    """The campaign's settings: those its root holds, or on its first execution, the ones --settings names as their
    version 1. A restart keeps the root's whatever --settings names, so the command that started the campaign restarts
    it."""
    path = arguments.campaign_root / CAMPAIGN_SETTINGS
    try:
        if path.is_file():
            if arguments.settings is not None:
                print(f"the campaign runs under its own settings, {path}; {arguments.settings} goes unread", flush=True)
            return CampaignSettings(arguments.campaign_root)
        try:
            given = json.loads(arguments.settings.read_text())
        except (OSError, ValueError) as error:
            raise SystemExit(f"cannot read the campaign settings {arguments.settings}: {error}") from error
        return CampaignSettings.seed(arguments.campaign_root, given)
    except SettingsError as error:
        raise SystemExit(str(error)) from error


def check_campaign_options(arguments):
    """Record the options that decide what the campaign's phases measure, and the AWS account its settings' buckets
    are in, or refuse a restart that changes them: the restart skips each completed phase, so it would measure
    nothing under the new options."""
    options = json.dumps({
        "campaign": arguments.campaign,
        "account": arguments.cloud.account(next(iter(live_settings(arguments)["regions"]))),
        "spot_vcpu_reserve": arguments.spot_vcpu_reserve,
        "phase_timeout_seconds": arguments.phase_timeout_seconds,
        "selected_shards": sorted(arguments.selected_shards) if arguments.selected_shards else None,
        "smoke_protocol": arguments.smoke_protocol,
        "release_version": arguments.release_version,
        "language_plans": {str(plan.resolve()): hashlib.sha256((plan / "plan.json").read_bytes()).hexdigest()
                           for plan in arguments.language_plan},
    }, indent=2, sort_keys=True) + "\n"
    path = arguments.campaign_root / CAMPAIGN_OPTIONS
    written = write_new_file(path, options.encode())
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
    """Freeze every candidate, then run every candidate's phases in this process, side by side."""
    if not arguments.candidates:
        raise SystemExit("a campaign requires at least one --candidate NAME=COMMITISH")
    given = [option for option, value in (("--smoke-results", arguments.smoke_results),
                                          ("--primary-results", arguments.primary_results),
                                          ("--confirmation-jobs", arguments.confirmation_jobs),
                                          ("--engine-builds", arguments.engine_builds)) if value is not None]
    if given:
        raise SystemExit(f"a campaign passes each phase its prerequisites itself; it takes no {', '.join(given)}")
    # Each candidate's phases, and its language phases, reserve the fleet and number their hosts under their own
    # campaign ID.
    names = {}
    for name, _ in arguments.candidates:
        for language in (False, True) if arguments.language_plan else (False,):
            campaign_id = candidate_campaign_id(arguments.campaign, name, language)
            validate_campaign_id(campaign_id, f"candidate {name}'s campaign ID")
            if campaign_id in names:
                raise SystemExit(f"candidates {names[campaign_id]} and {name} would both run as campaign "
                                 f"{campaign_id}; rename one")
            names[campaign_id] = name
    # Plans the language phases would refuse are refused here, before anything is frozen or launched.
    plans = validate_language_plans(arguments.language_plan)
    for phase in ("smoke", "primary") if plans else ():
        try:
            language_fleet.campaign_units(plans, phase)
        except (OSError, ValueError, KeyError) as error:
            raise SystemExit(f"cannot plan {phase} language units from {', '.join(map(str, plans))}: "
                             f"{error}") from error
    if not arguments.execute:
        for name, commitish in arguments.candidates:
            print(f"candidate {name}: {resolve_commit(root, commitish)}, campaign "
                  f"{candidate_campaign_id(arguments.campaign, name)}, results {arguments.campaign_root / name}")
        return
    if not arguments.controller:
        launch_control_host(arguments, root)
        return
    refuse_old_root(arguments.campaign_root)
    if arguments.settings is None and not (arguments.campaign_root / CAMPAIGN_SETTINGS).is_file():
        raise SystemExit("a campaign's first execution seeds its settings; name them with --settings FILE")
    with controller_lock(arguments.campaign_root, arguments.campaign):
        arguments.settings = load_settings(arguments)
        arguments.shared_budget = FleetBudget(arguments.campaign_root, arguments.settings, arguments.spot_vcpu_reserve)
        candidates = [freeze_candidate(root, arguments.campaign_root, arguments.campaign, name, commitish)
                      for name, commitish in arguments.candidates]
        check_campaign_candidates(arguments.campaign_root, [candidate.row for candidate in candidates])
        for candidate in candidates:
            record_candidate(arguments.campaign_root, candidate)
        check_campaign_options(arguments)
        arguments.telemetry = telemetry.start(arguments.campaign)
        campaign = Campaign(arguments, root, candidate_phases(arguments, candidates))
        try:
            failed = campaign.run()
        finally:
            arguments.telemetry.shutdown()
        # A controller that ended lists the root's files for finish to check its copy against, under the settings
        # lock, so no control command changes the root once it is listed; one SIGTERM stopped leaves the root to the
        # next.
        if not campaign.terminated:
            with settings_locked(arguments.settings.lock_path):
                control_host.write_manifest(arguments.campaign_root)
    if failed:
        message = "campaign finished with failed phases:\n" + "\n".join(
            f"{phase.key}: {phase.failure}" for phase in failed)
        # A stopped campaign ends as it was asked to, so nothing restarts it.
        if live_settings(arguments)["stop"]:
            print(message, flush=True)
            return
        # The control host's unit restarts a controller that fails, but not one whose campaign ended.
        print(message, file=sys.stderr, flush=True)
        raise SystemExit(control_host.ENDED_EXIT)


def controller_command(arguments, commits):
    """The command the control host's unit runs the campaign's controller with: this one's options, each candidate
    at its commit, and the settings and language plans as the host's launch inputs hold them."""
    command = ["campaign", arguments.campaign, "--execute", "--controller",
               "--campaign-root", control_host.CAMPAIGN_ROOT, "--settings", f"{control_host.INPUTS}/settings.json",
               "--spot-vcpu-reserve", str(arguments.spot_vcpu_reserve), "--smoke-protocol", arguments.smoke_protocol]
    for (name, _), commit in zip(arguments.candidates, commits):
        command += ["--candidate", f"{name}={commit}"]
    if arguments.release_version:
        command += ["--release-version", arguments.release_version]
    if arguments.phase_timeout_seconds is not None:
        command += ["--phase-timeout-seconds", str(arguments.phase_timeout_seconds)]
    for shard in arguments.selected_shards or ():
        command += ["--shard", shard]
    for index in range(len(arguments.language_plan)):
        command += ["--language-plan", f"{control_host.INPUTS}/language-plans/{index}"]
    return command


def launch_control_host(arguments, root):
    """Launch the control host that runs the campaign's controller, once its candidates, settings and AWS account
    pass the checks the controller would make first. The candidates must be on GitHub, where the host fetches them."""
    if arguments.ssh_public_key is None:
        raise SystemExit("launching a campaign's control host requires --ssh-public-key PATH, the key ssh reaches it "
                         "with")
    if arguments.settings is None:
        raise SystemExit("a campaign's first execution seeds its settings; name them with --settings FILE")
    ssh_public_key = control_host.read_ssh_public_key(arguments.ssh_public_key)
    commits = [resolve_commit(root, commitish) for _, commitish in arguments.candidates]
    for commit in commits:
        check_candidate_tooling(root, commit)
    try:
        given = arguments.settings.read_bytes()
        arguments.settings = SimpleNamespace(current=seeded_settings(json.loads(given)))
    except (OSError, ValueError) as error:
        raise SystemExit(f"cannot read the campaign settings {arguments.settings}: {error}") from error
    except SettingsError as error:
        raise SystemExit(str(error)) from error
    directory = Path(__file__).resolve().parent
    arguments.regional_amis = read_platform_regions(directory)
    validate_aws(arguments, read_platforms(directory))
    command = controller_command(arguments, commits)
    with tempfile.TemporaryDirectory(prefix="control-host-") as temporary:
        inputs = Path(temporary) / "launch-inputs.tar.gz"
        control_host.write_launch_inputs(inputs, command, commits, given, arguments.language_plan)
        control_host.launch(arguments.cloud, arguments.campaign, ssh_public_key, inputs)


def controller_process(campaign_root):
    """The process ID the campaign root's controller records, while the process with that ID started when the record
    says. The record outlives its controller, whose process ID may since name another process. The root's controller
    lock is left alone, as even a shared hold of it would stop a controller that is starting from taking it."""
    try:
        values = read_properties(campaign_root / CONTROLLER_RECORD)
        process_id, started = int(values["pid"]), values["started"]
    except (OSError, RuntimeError, KeyError, ValueError):
        return None
    return process_id if process_start(process_id) == started else None


def clock_time(epoch, now, zone=None):
    """A local clock time, such as "about 6:40 p.m. PDT", with its day when that is not today's."""
    moment, today = (datetime.fromtimestamp(value).astimezone(zone) for value in (epoch, now))
    text = (f"about {moment.hour % 12 or 12}:{moment.minute:02d} {'a.m.' if moment.hour < 12 else 'p.m.'} "
            f"{moment.tzname()}")
    return text if moment.date() == today.date() else f"{text} on {moment:%a %b} {moment.day}"


def ledger_unresolved(root, items, accepted):
    """The units a running phase has left unresolved so far, by identity, each with its detail, as its controller's
    recovery reads them from its ledgers: each unit a semantic failure rejected, even where another attempt was
    accepted, and each one not accepted that a rejection ended or that spent its attempts."""
    def done(identity):
        return len(accepted.get(identity, ())) == len(items[identity].groups)

    unresolved, counted = {}, {}
    for row in read_unit_attempts(root / "unit-attempts.tsv"):
        identity = f"{row['platform']}/{row['unit_id']}/replica-{row['replica']}"
        if identity not in items:
            continue
        if row["outcome"] == "rejected":
            unresolved[identity] = row["detail"]
        elif row["outcome"] in COUNTED_UNIT_OUTCOMES:
            counted.setdefault(identity, {})[row["host_epoch"], row["sequence"]] = row["detail"]
    for identity, leases in counted.items():
        if len(leases) >= MAX_UNIT_ATTEMPTS:
            unresolved.setdefault(identity, f"{list(leases.values())[-1]}; spent its {MAX_UNIT_ATTEMPTS} attempts")
    unresolved = {identity: detail for identity, detail in unresolved.items() if not done(identity)}
    if (root / unit_session.SEMANTIC_REJECTIONS).is_file():
        for row in read_semantic_rejections(root / unit_session.SEMANTIC_REJECTIONS):
            unresolved[unit_logical_identity(row)] = SEMANTIC_FAILURE_DETAIL
    return unresolved


def phase_unresolved(root, live):
    """A unit phase's unresolved units, each with its platform, unit_id, replica, and detail: those unresolved-units.tsv
    lists once the phase's controller ended it, and while it runs, or before its first end, those its ledgers show."""
    path = root / "unresolved-units.tsv"
    if path.is_file() and not live:
        with path.open(newline="") as input_file:
            return list(csv.DictReader(input_file, delimiter="\t"))
    items = {item.identity: item for item in read_work_items(root / "work-items.tsv")}
    accepted = {}
    for row in read_accepted_units(root / "accepted-units.tsv"):
        accepted.setdefault(unit_logical_identity(row), {})[row["group_id"]] = row
    return [{"platform": items[identity].platform, "unit_id": items[identity].unit_id,
             "replica": str(items[identity].replica), "detail": detail}
            for identity, detail in sorted(ledger_unresolved(root, items, accepted).items())]


def held_items(root):
    """The items of a phase's leases that unit-attempts.tsv records as leased and not yet settled or given up."""
    leases = {}
    for row in read_unit_attempts(root / "unit-attempts.tsv"):
        lease = leases.setdefault((row["host_epoch"], row["sequence"]), [row, False])
        lease[1] |= row["outcome"] in SETTLED_UNIT_OUTCOMES | {"lost", "released", "claimed"}
    return {f"{row['platform']}/{row['unit_id']}/replica-{row['replica']}" for row, ended in leases.values()
            if row["outcome"] == "leased" and not ended}


def phase_progress(root, fleet, live):
    """A unit phase's progress, from its ledgers: its work items, their accepted receipts by identity and group, the
    identities of its unresolved units, each pool's work left, and while its controller runs, the seconds until it
    finishes at the live hosts its fleet, as owner_fleet reads it, holds, or None while some pool with work left has
    none. A host holds its reservation until its cleanup is proven."""
    items = read_work_items(root / "work-items.tsv")
    accepted = {}
    for row in read_accepted_units(root / "accepted-units.tsv"):
        accepted.setdefault(unit_logical_identity(row), {})[row["group_id"]] = row
    unresolved = {f"{row['platform']}/{row['unit_id']}/replica-{row['replica']}"
                  for row in phase_unresolved(root, live)}
    pools = progress(items, accepted, measured_group_seconds(root / UNIT_UPLOADS), unresolved)
    hosts = Counter(host["instance_type"] for host in fleet.hosts) if fleet else Counter()
    return SimpleNamespace(items=items, accepted=accepted, unresolved=unresolved, pools=pools, finish=finish_seconds(
        pools, {pool: hosts[pool[1]] for pool in pools}) if live else None)


def phase_steering(root, settings, campaign_root):
    """What a unit phase's campaign settings, or None, record of it, by its key in its campaign root, if any."""
    return (settings or {}).get("phases", {}).get(root.relative_to(campaign_root).as_posix()
                                                  if campaign_root else None, {})


def phase_live(root, controller, steering):
    """Whether a unit phase is still running: its campaign's controller, by its process ID or None, is live, and the
    phase is neither complete nor, as its steering records, failed."""
    return controller is not None and not (root / PHASE_COMPLETE).is_file() and steering.get("state") != "failed"


def phase_status(label, root, now, fleets, controller, settings, zone=None, campaign_root=None):
    """A unit phase's lines for the status command, read from its ledgers alone, given the process ID of its
    campaign's live controller, or None, its campaign's settings, or None, and its campaign root, if any. fleets holds
    each fleet state, so the phases sharing a fleet read it once."""
    steering = phase_steering(root, settings, campaign_root)
    state = steering.get("state", "running")
    live = phase_live(root, controller, steering)
    if (root / PHASE_COMPLETE).is_file():
        state = "complete"
    elif live:
        state = f"{state} as process {controller}" + (f": {steering['reason']}" if steering.get("reason") else "")
    elif state != "failed":
        state = "no live controller" + (f", {state}: {steering['reason']}" if steering.get("reason") else "")
    lines = [f"  {label:<18}{state}"]
    if not (root / "work-items.tsv").is_file():
        return lines

    def line(name, text):
        lines.append(f"    {name:<10}{text}")

    # The fleet's state is at the campaign root, and names the phase by its key.
    state_path = campaign_root / STATE_FILE if campaign_root else None
    if state_path and state_path not in fleets:
        fleets[state_path] = read_fleet_state(state_path)
    fleet = (owner_fleet(fleets[state_path], root.relative_to(campaign_root).as_posix(), now)
             if state_path and fleets[state_path] else None)
    phase = phase_progress(root, fleet, live)
    groups = sum(len(item.groups) for item in phase.items)
    groups_accepted = sum(map(len, phase.accepted.values()))
    done = {item.identity for item in phase.items if len(phase.accepted.get(item.identity, ())) == len(item.groups)}
    queued = ""
    if live:
        held = held_items(root)
        queued = f", {sum(item.identity not in done | phase.unresolved | held for item in phase.items)} queued"
    line("units", f"{len(done)}/{len(phase.items)} accepted ({groups_accepted}/{groups} groups), "
                  f"{len(phase.unresolved)} unresolved{queued}")
    if fleet and live:
        # Each live host holds a reservation until its cleanup is proven; one not yet reporting has booted nothing.
        def counts(hosts):
            booting = sum("reported_at" not in host for host in hosts)
            return f"{len(hosts)} live" + (f", {booting} not yet reporting" if booting else "")

        line("hosts", counts(fleet.hosts))
        for key in sorted({(host["instance_type"], host["region"], host["market"]) for host in fleet.hosts}):
            line("", f"{' '.join(key)}: " + counts([host for host in fleet.hosts if (
                host["instance_type"], host["region"], host["market"]) == key]))
    if fleet:
        line("spend", f"${fleet.spent:.2f} so far, ${fleet.reserved:.2f} reserved")
    if live and any(left.items for left in phase.pools.values()):
        line("finish", "unknown; no host runs" if phase.finish is None else
             clock_time(now + phase.finish, now, zone))
    boot = [float(match.group(1)) for row in read_attempts(root / "job-attempts.tsv")
            if (match := re.search(r"boot_to_first_unit_seconds=([0-9.]+)", row["detail"]))]
    uploads = [float(row["upload_seconds"]) for row in read_unit_uploads(root / UNIT_UPLOADS)]
    measured = [f"{name} {statistics.median(values):.0f}s" for name, values in
                (("boot to ready", fleet.ready if fleet else ()), ("boot to first unit", boot), ("upload", uploads))
                if values]
    if measured:
        line("medians", ", ".join(measured))
    return lines


def phase_root(root):
    """The candidate, label, and campaign root of a phase's root, which holds the phase's work items, or None for any
    other root. A phase root inside a campaign takes its candidate and the campaign status's label; any other takes
    "-", its directory's name, and no campaign root."""
    if not (root / "work-items.tsv").is_file():
        return None
    for prefix, candidate in (("lang ", root.parent.parent), ("", root.parent)):
        if (not prefix or root.parent.name == "lang") and candidate.name in {
                row["name"] for row in read_candidates(candidate.parent)}:
            return candidate.name, prefix + root.name, candidate.parent
    return "-", root.name, None


def campaign_phases(root):
    """Each phase root a campaign's candidates have, as (candidate, label, directory), in the status command's order."""
    if not (root / "candidates.tsv").is_file():
        raise SystemExit(f"{root} holds neither a campaign's candidates.tsv nor a phase's work-items.tsv")
    return [(candidate["name"], prefix.replace("/", " ") + phase, root / candidate["name"] / prefix / phase)
            for candidate in read_candidates(root) for prefix in ("", "lang/")
            for phase in ("build", "smoke", "primary", "confirmation")
            if (prefix == "" or phase != "build") and (root / candidate["name"] / prefix / phase).is_dir()]


def campaign_lines(root):
    """The status command's lines for what a campaign root records of the whole campaign: where its Grafana serves
    everything live, and any spending stop or stop its settings hold; and the settings, or None for an old root's."""
    lines = []
    if (root / control_host.GRAFANA_URL_FILE).is_file():
        lines.append(f"Grafana   {(root / control_host.GRAFANA_URL_FILE).read_text().strip()}")
    if not (root / CAMPAIGN_SETTINGS).is_file():
        return lines, None
    try:
        settings = read_settings(root / CAMPAIGN_SETTINGS)
    except SettingsError as error:
        return [*lines, str(error)], None
    # A stopped campaign's controller lists the root's files as it ends; until then it is still stopping.
    ended = (root / control_host.MANIFEST).is_file()
    for name, stop in (("spending stop", settings["spending_stop"]), ("stopped" if ended else "stopping",
                                                                       settings["stop"])):
        if stop:
            lines.append(f"{name} by {stop['by']}: {stop['reason']}")
    return lines, settings


def campaign_status(root, now=None, zone=None):
    """The status command's lines for a campaign root, or for one phase's root, from the ledgers alone."""
    now = time.time() if now is None else now
    if (phase := phase_root(root)) is not None:
        controller = controller_process(phase[2]) if phase[2] else None
        lines, settings = campaign_lines(phase[2]) if phase[2] else ([], None)
        return [f"phase {root}", *lines, *phase_status(phase[1], root, now, {}, controller, settings, zone, phase[2])]
    phases = campaign_phases(root)
    controller = controller_process(root)
    lines, settings = campaign_lines(root)
    lines.insert(0, f"campaign {root}: " + (f"controller running as process {controller}" if controller else
                                            "no live controller"))
    fleets = {}
    for candidate in read_candidates(root):
        lines.append(f"candidate {candidate['name']} at {candidate['commit'][:12]}")
        for name, label, directory in phases:
            if name == candidate["name"]:
                lines.extend(phase_status(label, directory, now, fleets, controller, settings, zone, root))
    return lines


@functools.cache
def release_1_0_ratios():
    """The 1.0 release's Regulator-to-comparator ratios by platform and row: each baseline comparison's median
    over its primary hosts from the release's baseline tables, and each compared language row's published
    ratio."""
    with tarfile.open(REPOSITORY / RELEASE_1_0_BASELINE_TABLES) as archive:
        with io.TextIOWrapper(archive.extractfile("reduction/comparison-aggregates.tsv"), encoding="utf-8") as table:
            ratios = {(row["platform"], row["comparison_id"]): float(row["primary_median_ratio"])
                      for row in csv.DictReader(table, delimiter="\t")}
    with gzip.open(REPOSITORY / RELEASE_1_0_REPORT, "rt", encoding="utf-8") as report:
        for row in json.load(report)["rows"]:
            if row["source"] == "language" and "ratio" in row["result"]:
                ratios[row["platform"], f"{row['language']}/{row['memoryMode']}/{row['id']}"] = row["result"]["ratio"]
    return ratios


def phase_results(root, live=False):
    """A unit phase's results so far: each row's Regulator-to-comparator ratio on every host whose group the phase
    has accepted, above 1 when Regulator is slower, each unit it left unresolved, and how many accepted groups have
    no retained results. Baseline rows are paired by comparison and estimated by the median of their host ratios, as
    the baseline reduction estimates them; language rows are paired by language, memory mode, suite, case, and
    operation and estimated by the average Regulator host time divided by the average comparator host time, as the
    report estimates them. A row is slower past the baseline reduction's material limit, and a 1.0 win now a loss
    when 1.0 was also faster past it."""
    # The report's reduction lives outside the tooling a phase's digest freezes, so only this command loads it.
    scripts = str(REPOSITORY / "benchmark-report" / "scripts")
    if scripts not in sys.path:
        sys.path.insert(0, scripts)
    import language_data
    observed, measured, missing = [], defaultdict(list), 0
    for receipt in read_accepted_units(root / "accepted-units.tsv"):
        directory = unit_attempt_directory(root, receipt)
        if not directory.is_dir():
            missing += 1
            continue
        if (directory / "observed-rows.tsv").is_file():
            observed.extend({**receipt, **row, "confirmation": "false"}
                            for row in aggregate_results.read_observed_rows(directory / "observed-rows.tsv"))
        for path in sorted(directory.glob("partitions/*/language-results.json")):
            data = json.loads(path.read_text())
            for comparison in data["comparisons"]:
                row = "/".join((comparison["language"], comparison["mode"], data["manifest"]["suite"],
                                comparison["logical_case"], comparison["operation"]))
                measured[receipt["platform"], row].append({
                    "replica": int(receipt["replica_id"]), "instanceId": receipt["instance_id"],
                    **{side: data["observations"][comparison[side]] for side in ("candidate", "comparator")}})
    results = []
    limit = aggregate_results.MATERIAL_LIMIT

    # A result's rank orders its phase's table: unresolved units and missing results, then rows that cannot be
    # reduced, then timed rows, slowest first, then rows the comparison does not time. Its kind is what the
    # summary counts it as: a row, a slower row, a 1.0 win lost, an unresolved unit, or missing results. replicas
    # counts the hosts that landed, timed or not; a unit has none.
    def result(platform, row, replicas, host_ratios=(), ratio=None, flags=(), rank=1, kind="row"):
        ratio_1_0 = release_1_0_ratios().get((platform, row))
        if ratio is not None:
            rank = 2
            if ratio > 1 + limit:
                kind = "lost" if ratio_1_0 is not None and ratio_1_0 < 1 - limit else "slower"
                flags = ["slower", *(["1.0 win now a loss"] if kind == "lost" else []), *flags]
        results.append({"platform": platform, "row": row, "ratio": ratio, "replicas": replicas,
                        "host_ratios": sorted(host_ratios), "ratio_1_0": ratio_1_0, "flags": list(flags),
                        "rank": rank, "kind": kind})

    if missing:
        result("-", "-", None, flags=[f"accepted groups without retained results: {missing}"], rank=0,
               kind="missing")
    if observed:
        manifest = {row["row_id"]: row for row in aggregate_results.load_manifest(
            Path(__file__).resolve().parent / "rows.tsv", None)}
        # A row this checkout's rows.tsv does not list has no comparison to pair it in.
        unlisted = Counter((row["platform"], row["row_id"]) for row in observed if row["row_id"] not in manifest)
        for (platform, row), replicas in unlisted.items():
            result(platform, row, replicas, flags=["not in this checkout's rows.tsv"])
        observed = [row for row in observed if row["row_id"] in manifest]
        for row in observed:
            row["suite"] = manifest[row["row_id"]]["suite"]
        _, comparisons = aggregate_results.build_comparisons(observed, manifest.values())
        paired = defaultdict(list)
        for comparison in comparisons:
            paired[comparison["platform"], comparison["comparison_id"]].append(comparison)
        for (platform, row), pairs in paired.items():
            ratios = [(int(pair["replica_id"]), float(pair["ratio"])) for pair in pairs]
            result(platform, row, len(pairs), ratios, aggregate_results.median([ratio for _, ratio in ratios]),
                   ["precision rejected"] if any(pair["outcome"] != "accepted" for pair in pairs) else [])
    for (platform, row), hosts in measured.items():
        try:
            hosts = [{**host, **{side: language_data.outcome(host[side]) for side in ("candidate", "comparator")}}
                     for host in hosts]
            reduced = language_data.reduce_hosts(hosts)
        except ValueError as error:
            result(platform, row, len(hosts), flags=[str(error)])
            continue
        if reduced["state"] == "compared":
            ratios = [(host["replica"], host["candidate"]["meanNs"] / host["comparator"]["meanNs"]) for host in hosts]
            candidate, comparator = (statistics.mean(host[side]["meanNs"] for host in hosts)
                                     for side in ("candidate", "comparator"))
            result(platform, row, len(hosts), ratios, candidate / comparator)
        else:
            result(platform, row, len(hosts), flags=[reduced["state"], reduced["reason"]], rank=3)
    # The ledgers show the units unresolved so far; unresolved-units.tsv, written when the controller ends, lists every
    # item not accepted.
    for unit in phase_unresolved(root, live):
        result(unit["platform"], f"{unit['unit_id']} replica {unit['replica']}", None,
               flags=[f"unresolved: {unit['detail']}"], rank=0, kind="unit")
    return sorted(results, key=lambda item: (item["rank"], -item["ratio"] if item["ratio"] is not None else 0,
                                             item["platform"], item["row"]))


def campaign_results(root):
    """Each primary phase's, or the given phase's, results, as (candidate, phase, results, error), for a campaign
    root or for one phase's root. A phase whose results cannot be read has none, and the error that stopped them, so
    one phase's error does not hide the others' results."""
    phase = phase_root(root)
    phases = ([(*phase[:2], root)] if phase else
              [entry for entry in campaign_phases(root) if entry[1] in ("primary", "lang primary")])
    campaign_root = (phase[2] if phase else root)
    controller = controller_process(campaign_root) if campaign_root else None
    settings = campaign_lines(campaign_root)[1] if campaign_root else None
    results = []
    for candidate, label, directory in phases:
        try:
            live = phase_live(directory, controller, phase_steering(directory, settings, campaign_root))
            results.append((candidate, label, phase_results(directory, live), None))
        except (OSError, RuntimeError, ValueError, KeyError, TypeError, EOFError, tarfile.TarError) as error:
            results.append((candidate, label, [], f"{type(error).__name__}: {error}"))
    return results


def results_lines(phases, tsv=False):
    """The status command's results, worst first: a table per phase, or one tab-separated table."""
    def ratio(value):
        return "-" if value is None else f"{value:.3f}"

    table = io.StringIO()
    writer = csv.DictWriter(table, RESULT_FIELDS, restval="-", extrasaction="ignore", delimiter="\t",
                            lineterminator="\n")
    writer.writeheader()
    lines = []
    for candidate, phase, results, error in phases:
        if error is not None:
            writer.writerow({"candidate": candidate, "phase": phase, "flags": f"unreadable: {error}"})
            lines.append(f"candidate {candidate} {phase}: unreadable: {error}")
            continue
        kinds = Counter(item["kind"] for item in results)
        slower = kinds["slower"] + kinds["lost"]
        lines += [f"candidate {candidate} {phase}: rows {kinds['row'] + slower}, slower {slower}, "
                  f"1.0 wins lost {kinds['lost']}, unresolved {kinds['unit']}",
                  f"  {'ratio':>6}  n  {'host ratios':<26}  {'1.0':>6}  platform row  flags"]
        for item in results:
            fields = {**item, "candidate": candidate, "phase": phase, "ratio": ratio(item["ratio"]),
                      "replicas": "-" if item["replicas"] is None else item["replicas"],
                      "host_ratios": " ".join(f"r{replica}={ratio(value)}" for replica, value in item["host_ratios"]),
                      "ratio_1_0": ratio(item["ratio_1_0"]), "flags": ", ".join(item["flags"])}
            writer.writerow(fields)
            lines.append(f"  {fields['ratio']:>6}  {fields['replicas']}  {fields['host_ratios']:<26}  "
                         f"{fields['ratio_1_0']:>6}  {item['platform']:<8} {item['row']}  {fields['flags']}".rstrip())
    # A field may hold a Unicode line separator, which only a newline ends a record at.
    return table.getvalue().split("\n")[:-1] if tsv else lines


CONTROL_LIMITS = {"max-hosts": "max_hosts", "max-hosts-per-platform": "max_hosts_per_platform",
                  "max-usd": "max_usd", "max-machine-hours": "max_machine_hours"}
# The commands the monitor runs unattended, as its runbook's authority table grants them; every other command, a
# stop and a spending stop's clearing among them, is Dain's.
MONITOR_COMMANDS = ("set-limit", "add-region", "resume")


def control_parser():
    parser = argparse.ArgumentParser(
        prog=f"{Path(sys.argv[0]).name} control", allow_abbrev=False,
        description="change a running campaign's live settings, which its controller applies on its next loop")
    parser.add_argument("root", type=Path, help="the campaign root")
    parser.add_argument("--campaign", required=True,
                        help="the campaign the change is for, which the root's controller must have recorded")
    parser.add_argument("--actor", required=True, choices=("dain", "monitor"),
                        help="who makes the change, which the settings' events record")
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("add-region", help="create a region's shared bucket unless it has one, put every "
                                                     "archive the campaign shares in it, then add the region")
    command.add_argument("region")
    command.add_argument("--max-vcpus", type=int, required=True, help="the fleet's Spot vCPUs in the region")
    command.add_argument("--on-demand-max-vcpus", type=int, help="the fleet's On-Demand vCPUs in the region; "
                                                                 "without it, the region launches no On-Demand")
    command.add_argument("--platforms", nargs="+", help="the platforms whose lists gain the region; every platform's "
                                                        "by default")
    command = commands.add_parser("set-limit", help="set max-hosts, max-hosts-per-platform, max-usd, or "
                                                    "max-machine-hours N, or region REGION max-vcpus N")
    command.add_argument("limit", nargs="+")
    command = commands.add_parser("set-purchase", help="set how a platform buys hosts")
    command.add_argument("platform")
    command.add_argument("purchase", choices=PURCHASE_POLICIES)
    command = commands.add_parser("set-share", help="pin a phase's hosts to N, or give it its share by work left")
    command.add_argument("phase")
    command.add_argument("share")
    for name, description in (("pause", "lease and launch nothing for a phase; its running leases finish"),
                              ("resume", "run a paused or draining phase again"),
                              ("drain", "launch nothing for a phase; its live workers take its queued work")):
        command = commands.add_parser(name, help=description)
        command.add_argument("phase")
        if name == "pause":
            command.add_argument("--reason")
    commands.add_parser("clear-spending-stop", help="admit launches and lease extensions again")
    commands.add_parser("reopen", help="let an ended campaign's controller start again, clearing any stop")
    command = commands.add_parser("stop", help="stop-lease every worker, prove their cleanup, and end the campaign")
    command.add_argument("--reason")
    command = commands.add_parser("authorize", help="set the envelope the monitor raises limits within: max-hosts "
                                                    "N, max-usd N, or regions REGION...")
    command.add_argument("envelope", nargs="+")
    return parser


def control_number(text, integer):
    """A command's number, an integer where the setting takes one, and otherwise as JSON would write it."""
    try:
        return int(text) if integer or text.isdigit() else float(text)
    except ValueError:
        raise SettingsError(f"{text} is not a number") from None


def control_change(arguments, now):
    """The change a control command makes, as its description and the update that makes it."""
    command = arguments.command
    actor = arguments.actor

    def phase(settings, *states):
        steering = settings["phases"].get(arguments.phase)
        if steering is None:
            raise SettingsError(f"the campaign has no phase {arguments.phase}; its phases are "
                                f"{', '.join(settings['phases']) or 'not yet recorded'}")
        if states and steering["state"] not in states:
            raise SettingsError(f"{arguments.phase} is {steering['state']}; {command} takes a phase that is "
                                f"{' or '.join(states)}")
        return steering

    if command == "set-limit":
        limit = arguments.limit
        if len(limit) == 4 and limit[0] == "region" and limit[2] == "max-vcpus":
            region, vcpus = limit[1], control_number(limit[3], True)

            def update(settings):
                if region not in settings["regions"]:
                    raise SettingsError(f"the campaign has no region {region}; add it with add-region")
                settings["regions"][region]["max_vcpus"] = vcpus
            return f"{region} max_vcpus to {vcpus}", update
        if len(limit) != 2 or limit[0] not in CONTROL_LIMITS:
            raise SettingsError(f"set-limit takes {', '.join(CONTROL_LIMITS)} N, or region REGION max-vcpus N")
        name, value = CONTROL_LIMITS[limit[0]], control_number(limit[1], limit[0].startswith("max-hosts"))
        return f"{name} to {value:g}", lambda settings: settings.update({name: value})
    if command == "set-purchase":
        def update(settings):
            if arguments.platform not in settings["platform_regions"]:
                raise SettingsError(f"the campaign has no platform {arguments.platform}")
            settings["purchase"][arguments.platform] = arguments.purchase
        return f"{arguments.platform} purchase to {arguments.purchase}", update
    if command == "set-share":
        share = "auto" if arguments.share == "auto" else control_number(arguments.share, True)
        return (f"{arguments.phase} share to {share}",
                lambda settings: phase(settings).update(share=share))
    if command == "pause":
        reason = arguments.reason or f"paused by {actor}"

        def update(settings):
            phase(settings, "running", "draining", "paused").update(state="paused", reason=reason)
        return f"{arguments.phase} paused: {reason}", update
    if command in ("resume", "drain"):
        state, states = ("running", ("paused", "draining")) if command == "resume" else (
            "draining", ("running", "paused"))

        def update(settings):
            steering = phase(settings, *states)
            steering.pop("reason", None)
            steering["state"] = state
        return f"{arguments.phase} {state}", update
    if command == "clear-spending-stop":
        return "spending stop cleared", lambda settings: settings.update(spending_stop=None)
    if command == "reopen":
        return "campaign reopened", lambda settings: settings.update(stop=None)
    if command == "stop":
        stop = {"by": actor, "at": round(now), "reason": arguments.reason or f"stopped by {actor}"}
        return f"campaign stop: {stop['reason']}", lambda settings: settings.update(stop=settings["stop"] or stop)
    envelope = arguments.envelope
    if len(envelope) >= 2 and envelope[0] == "regions":
        regions = sorted(set(envelope[1:]))
        return (f"authorized regions to {', '.join(regions)}",
                lambda settings: settings["authorized"].update(regions=regions))
    if len(envelope) != 2 or envelope[0] not in ("max-hosts", "max-usd"):
        raise SettingsError("authorize takes max-hosts N, max-usd N, or regions REGION...")
    name, value = CONTROL_LIMITS[envelope[0]], control_number(envelope[1], envelope[0] == "max-hosts")
    return f"authorized {name} to {value:g}", lambda settings: settings["authorized"].update({name: value})


def check_region_limits(root, aws, before, after):
    """Refuse settings whose raised or new region vCPU limits exceed the region's Standard quota after its reserve, the
    check each phase's setup makes, so a limit a phase could not run under never reaches the controller. A lowered
    limit needs no quota."""
    spot_reserve = json.loads((root / CAMPAIGN_OPTIONS).read_text())["spot_vcpu_reserve"]
    for region, limits in after["regions"].items():
        for name, market, reserve in (("max_vcpus", "spot", spot_reserve),
                                      ("on_demand_max_vcpus", "on-demand", ON_DEMAND_VCPU_SAFETY_RESERVE)):
            if limits.get(name, 0) > before["regions"].get(region, {}).get(name, 0):
                quota = aws.vcpu_quota(region, market)
                if limits[name] > quota - reserve:
                    raise SettingsError(f"{region} {name} {limits[name]:g} exceeds the Standard {market} quota of "
                                        f"{quota:g} vCPUs after its reserve of {reserve:g}")


def add_region(arguments, store, aws):
    """Add a region to the campaign: create its shared bucket unless it has one, put every archive the campaign has
    shared in it, each verified by its SHA-256, and only then add the region to the settings, listed for the platforms
    --platforms names, or for every platform. A change the settings, the actor's bounds, the region's quotas or its
    images would refuse is refused before anything is created."""
    region = arguments.region
    bucket = f"regulator-benchmark-{aws.account(region)}-{region}"
    limits = {"bucket": bucket, "max_vcpus": arguments.max_vcpus, **(
        {} if arguments.on_demand_max_vcpus is None else {"on_demand_max_vcpus": arguments.on_demand_max_vcpus})}

    def update(settings):
        if region in settings["regions"]:
            raise SettingsError(f"the campaign has region {region} already; change its limit with set-limit")
        settings["regions"][region] = limits
        for platform in arguments.platforms or settings["platform_regions"]:
            if platform not in settings["platform_regions"]:
                raise SettingsError(f"the campaign has no platform {platform}")
            if (platform, region) not in images:
                raise SettingsError(f"platform-regions.tsv has no {platform} image in {region}; name the platforms "
                                    f"that have one with --platforms")
            settings["platform_regions"][platform].append(region)

    images = read_platform_regions(Path(__file__).resolve().parent)
    check_region_limits(arguments.root, aws, store.current,
                        changed_settings(store.current, arguments.actor, update, time.time()))
    try:
        check_bucket(aws, region, bucket)
    except CloudError:
        print(f"creating the shared bucket {bucket} in {region}", flush=True)
        aws.create_bucket(region, bucket, SHARED_BUCKET_TAGS, json.loads(SHARED_BUCKET_LIFECYCLE.read_text()))
        check_bucket(aws, region, bucket)
    for row in read_shared_archives(arguments.root / SHARED_ARCHIVES):
        put_shared_archive(aws, region, bucket, row["key"], Path(row["path"]), row["sha256"], row["label"])
    platforms = ", ".join(arguments.platforms or store.current["platform_regions"])
    return f"add region {region} ({bucket}) for {platforms}", update


def control(arguments, aws):
    """Change the campaign's live settings as the actor's control command; add-region reaches AWS through aws."""
    if arguments.actor == "monitor" and arguments.command not in MONITOR_COMMANDS:
        raise SystemExit(f"only dain runs {arguments.command}; the monitor runs {', '.join(MONITOR_COMMANDS)}")
    # The controller records its campaign as it starts, so a command for one campaign never steers another's root.
    options = arguments.root / CAMPAIGN_OPTIONS
    recorded = json.loads(options.read_text()).get("campaign") if options.is_file() else None
    if recorded is None:
        raise SystemExit(f"{arguments.root} records no campaign: its controller has not yet started, or an earlier "
                         "controller started it")
    if recorded != arguments.campaign:
        raise SystemExit(f"{arguments.root} is campaign {recorded}'s root, not campaign {arguments.campaign}'s")
    manifest = arguments.root / control_host.MANIFEST

    def check_ended():
        """Refuse a command but reopen on an ended campaign, whose manifest lists its settings and their events, and
        reopen on one that has not ended."""
        if manifest.is_file() != (arguments.command == "reopen"):
            raise SettingsError(f"campaign {arguments.campaign} has not ended: {arguments.root} has no "
                                f"{manifest.name}" if arguments.command == "reopen" else
                                f"campaign {arguments.campaign} has ended; only reopen changes its settings")

    def checked(settings):
        # The change checks again under the settings lock, which the controller holds as it writes the manifest.
        check_ended()
        update(settings)

    try:
        check_ended()
        store = CampaignSettings(arguments.root)
        if arguments.command == "add-region":
            description, update = add_region(arguments, store, aws)
        else:
            description, update = control_change(arguments, time.time())
            check_region_limits(arguments.root, aws, store.current,
                                changed_settings(store.current, arguments.actor, update, time.time()))
        before = store.current["version"]
        settings = store.change(arguments.actor, description, checked)
    except SettingsError as error:
        raise SystemExit(str(error)) from error
    if settings["version"] == before:
        print(f"campaign settings version {before} already hold {description}")
    else:
        print(f"campaign settings version {settings['version']}: {description}")
    if arguments.command == "reopen":
        # Without the manifest the controller starts again, and finish waits for the one it writes as it ends.
        manifest.unlink()
        print(f"campaign {arguments.campaign} reopened; start its controller again")


def main():
    if sys.argv[1:2] == ["control"]:
        control(control_parser().parse_args(sys.argv[2:]), cloud.AwsCloud())
        return
    if sys.argv[1:2] == ["status"]:
        parser = argparse.ArgumentParser(
            prog=f"{Path(sys.argv[0]).name} status",
            description="report a campaign's or phase's progress from its ledgers, or with --results each primary "
                        "phase's, or the given phase's, ratios so far")
        parser.add_argument("root", type=Path, help="the campaign root, or one phase's root")
        parser.add_argument("--results", action="store_true",
                            help="report each primary phase's, or the given phase's, Regulator-to-comparator ratios "
                                 "so far, worst first")
        parser.add_argument("--tsv", action="store_true", help="report the results as one tab-separated table")
        arguments = parser.parse_args(sys.argv[2:])
        if arguments.tsv and not arguments.results:
            parser.error("--tsv formats the results; it requires --results")
        root = arguments.root.absolute()
        if arguments.results:
            lines = results_lines(campaign_results(root), arguments.tsv)
        else:
            lines = campaign_status(root)
        print("\n".join(lines))
        return
    if sys.argv[1:3] == ["control-host", "replace"]:
        parser = argparse.ArgumentParser(prog=f"{Path(sys.argv[0]).name} control-host replace",
                                         description="terminate a campaign's control host, if it has one, and launch "
                                                     "another onto its volume")
        parser.add_argument("campaign")
        parser.add_argument("--ssh-public-key", type=Path, required=True,
                            help="the public key ssh reaches the new host with")
        arguments = parser.parse_args(sys.argv[3:])
        control_host.replace(cloud.AwsCloud(), arguments.campaign,
                             control_host.read_ssh_public_key(arguments.ssh_public_key))
        return
    if sys.argv[1:3] == ["control-host", "known-host"]:
        parser = argparse.ArgumentParser(prog=f"{Path(sys.argv[0]).name} control-host known-host",
                                         description="record the ssh host keys of a campaign's control host that its "
                                                     f"console lists in {control_host.KNOWN_HOSTS}, and print its "
                                                     "address")
        parser.add_argument("campaign")
        arguments = parser.parse_args(sys.argv[3:])
        print(control_host.known_host(cloud.AwsCloud(), arguments.campaign))
        return
    if sys.argv[1:2] == ["teardown"]:
        parser = argparse.ArgumentParser(prog=f"{Path(sys.argv[0]).name} teardown",
                                         description="terminate a campaign's control host and delete its volume, "
                                                     "keeping a snapshot; only Dain runs it")
        parser.add_argument("campaign")
        parser.add_argument("--confirm", action="store_true", help="tear the control host down")
        arguments = parser.parse_args(sys.argv[2:])
        control_host.teardown(cloud.AwsCloud(), arguments.campaign, arguments.confirm)
        return
    if sys.argv[1:2] == ["finish"]:
        parser = argparse.ArgumentParser(prog=f"{Path(sys.argv[0]).name} finish",
                                         description="pull an ended campaign's root from its control host and check "
                                                     "it against its controller's manifest")
        parser.add_argument("campaign")
        parser.add_argument("--campaign-root", type=Path,
                            help="where the root goes (default: benchmark-results/pre-review-baseline/<campaign>)")
        parser.add_argument("--ssh-identity", type=Path,
                            help="the private key of the --ssh-public-key the host was launched with, which ssh "
                                 "then offers alone (default: ssh's own keys and agent)")
        arguments = parser.parse_args(sys.argv[2:])
        control_host.finish(cloud.AwsCloud(), arguments.campaign, (
            arguments.campaign_root or REPOSITORY / "benchmark-results" / "pre-review-baseline" /
            arguments.campaign).absolute(), arguments.ssh_identity)
        return
    arguments = parse_args()
    if arguments.controller and not arguments.execute:
        raise SystemExit("--controller runs a campaign's controller; it requires --execute")
    arguments.cloud = cloud.AwsCloud()
    root = REPOSITORY
    arguments.candidates = parse_candidates(arguments.candidate_values)
    arguments.campaign_root = (arguments.campaign_root or
                               root / "benchmark-results" / "pre-review-baseline" / arguments.campaign).absolute()
    if arguments.phase == "campaign":
        try:
            run_campaign(arguments, root)
        except SystemExit as refusal:
            # A controller's refusal, unlike a crash, recurs on every start, so its unit leaves it ended.
            if not arguments.controller or not isinstance(refusal.code, str):
                raise
            print(refusal.code, file=sys.stderr, flush=True)
            raise SystemExit(control_host.ENDED_EXIT) from None
        return
    if arguments.execute:
        raise SystemExit(f"a phase runs only inside its campaign's controller; run {Path(__file__).name} campaign "
                         f"{arguments.campaign} --execute with the phase's candidate")
    # A phase planned alone plans under its campaign's settings once the campaign has some.
    arguments.settings = (CampaignSettings(arguments.campaign_root)
                          if (arguments.campaign_root / CAMPAIGN_SETTINGS).is_file() else None)
    items, *_ = plan_phase(arguments, root)
    print(f"planned {len(items)} work items")
    print(f"work item manifest: {arguments.result_root / 'work-items.tsv'}")


def plan_phase(arguments, root):
    """Plan a phase of one candidate: its work items, written to work-items.tsv in its result root, its kind, any
    language plans, the platforms, and its result root, kept on arguments with what its dispatch reads. A restarted
    phase keeps the deadline its execution policy froze."""
    directory = Path(__file__).resolve().parent
    platforms = read_platforms(directory)
    if len(arguments.candidates) != 1:
        raise SystemExit("a phase measures one --candidate NAME=COMMITISH")
    [(name, commitish)] = arguments.candidates
    # A candidate's phases are its campaign, laid out under <campaign root>/<name>; a language plan's phases are
    # another, under <name>/lang, measuring the same build.
    arguments.campaign_id = candidate_campaign_id(arguments.campaign, name, language=bool(arguments.language_plan))
    candidate_root = arguments.campaign_root / name
    phases = candidate_root / "lang" if arguments.language_plan else candidate_root
    result_root = arguments.result_root = phases / arguments.phase
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
    policy = result_root / "execution-policy.json"
    validate_configuration(arguments, items,
                           json.loads(policy.read_text())["phase_timeout_seconds"] if policy.is_file() else None)
    validate_durable_paths(root, arguments, result_root)
    write_work_items(result_root / "work-items.tsv", items)
    return items, kind, plans, platforms, result_root


if __name__ == "__main__":
    main()
