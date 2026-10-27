#!/usr/bin/env python3

import argparse
import base64
import contextlib
import csv
import fcntl
import hashlib
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
from datetime import datetime
from pathlib import Path

import engine_build
import unit_session
import work_units
from acceptance import ValidationError, normalize_heap_size
from fleet_budget import (
    ON_DEMAND_VCPU_SAFETY_RESERVE, REGION_PATTERN, BudgetError, FleetBudget, on_demand_regions, region_limits)
from campaign_io import (
    JSON_LEDGER_WORKLOADS, UnsafeCampaignError, aws_output, check_output, fsync_directory, write_ledger)
from unit_dispatch import (
    UNIT_COMPLETION_FIELDS, accepted_row, calibration_key, completion_key, extract_unit_result,
    get_unit_worker_result, heartbeat_key, heartbeat_sequence, interruption_key, list_unit_worker_results,
    persist_accepted_unit, persist_semantic_rejection, put_unit_lease, read_accepted_units, read_semantic_rejections,
    read_unit_attempts, read_unit_receipt, recovered_keys, result_key, retain_unit_attempt, shows_worker_start,
    unit_attempt_directory, unit_logical_identity, unit_worker_instance_state, write_unit_attempt, write_unresolved,
    write_unresolved_units)

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "language"))
import batch_acceptance
import collection as language_collection
import fleet as language_fleet
import released_artifact
from campaign import LanguageCampaign

PROACTIVE_SPOT_CAPACITY_THRESHOLD = 32
DEFAULT_MAX_CONCURRENT = 64
STANDARD_ON_DEMAND_QUOTA_CODE = "L-1216C47A"
STANDARD_SPOT_QUOTA_CODE = "L-34B43A08"
STANDARD_INSTANCE_FAMILIES = frozenset("acdhimrtz")
SPOT_VCPU_SAFETY_RESERVE = 32
CAPACITY_RETRY_SECONDS = 30
JOB_TIMEOUT_SECONDS = 90 * 60
MAX_REPLACEMENTS_PER_JOB = 2
# Once this share of a phase's jobs, rounded up, is all that remains unaccepted, new
# launches use On-Demand so the phase's tail does not wait on Spot capacity or a reclaim.
ON_DEMAND_TAIL_PERCENT = 10
WRAPPER_NOT_CREATED = "wrapper process was not created"
UNIT_WORKER_SHARD = "unit-worker"
UNIT_WORKER_LIFETIME_SECONDS = 6 * 60 * 60
# The wrapper waits for a unit worker's host evidence through its lifetime, its last unit and upload.
UNIT_WORKER_TIMEOUT_SECONDS = UNIT_WORKER_LIFETIME_SECONDS + 90 * 60
# A worker whose heartbeat is this stale has lost its lease once EC2 no longer runs its instance,
# and after the longer bound whatever EC2 reports.
UNIT_HEARTBEAT_STALE_SECONDS = 10 * 60
UNIT_HEARTBEAT_LOST_SECONDS = 30 * 60
UNIT_POLL_SECONDS = 15
# A unit pool's new Spot workers skip a region this long after it found no Spot capacity, and
# while every region cools they launch On-Demand.
SPOT_REGION_COOL_DOWN_SECONDS = 15 * 60
# A Spot worker that stops for a rebalance recommendation this soon after launch, or before it
# completes a unit, found its region short of Spot capacity, and the region cools as above.
EARLY_REBALANCE_SECONDS = 15 * 60
# A work item stays unresolved once this many of its leases failed, were interrupted, or were lost,
# as a host job does; a lease released before it started does not count.
MAX_UNIT_ATTEMPTS = MAX_REPLACEMENTS_PER_JOB + 1
# run-shard.sh exits with this status when the numerical protocol rejects a row's measurements.
PROTOCOL_REJECTION_STATUS = 42
# run-shard.sh exits with this status when any check of a route's correctness stage fails.
SEMANTIC_FAILURE_STATUS = 44
SEMANTIC_FAILURE_DETAIL = f"semantic failure: status {SEMANTIC_FAILURE_STATUS}"
# A unit campaign phase's digest of the tooling that started it, beside its campaign state.
TOOLING_DIGEST = "tooling-digest.txt"
WORK_ITEM_FIELDS = ("platform", "shard_id", "unit_id", "replica", "estimated_seconds", "excluded_instances")
# Lease outcomes that spend one of the item's attempts.
COUNTED_UNIT_OUTCOMES = frozenset(("failed", "interrupted", "lost"))
# Lease outcomes after which the lease's host can no longer change what it became.
SETTLED_UNIT_OUTCOMES = frozenset(("stop", "accepted", "superseded", "failed", "rejected", "interrupted"))
# platforms.tsv pins each AMI by its ID in this region; other regions run the image of the same name.
PINNED_AMI_REGION = "us-west-2"
AMAZON_AMI_OWNER = "137112412989"
# Spot placement scores are rate-limited per configuration, so reuse each answer briefly.
PLACEMENT_SCORE_CACHE_SECONDS = 5 * 60
# Host environment fields the controller checkout chooses, each with the fields an
# original receipt's environment must share to have been given the same value.
REFILL_ENVIRONMENT_SCOPES = {
    **dict.fromkeys((
        "row_manifest_sha256", "platform_manifest_sha256", "comparator_manifest_sha256",
        "protocol_representatives_sha256", "baseline_protocol", "baseline_selected_route",
        "benchmark_heap_size", "regulator_release_version", "java_home"), ()),
    **dict.fromkeys((
        "ami", "jdk_archive_url", "jdk_archive_sha256", "java_runtime_version",
        "native_compiler_package", "cmake_package", "glibc_package", "time_package"), ("platform",)),
    **dict.fromkeys(("trino_commit", "trino_archive_sha256"), ("trino_used",)),
    **dict.fromkeys(("benchmark_mode", "trino_used", "joni_evidence_scope", "cargo_package", "rust_package"),
                    ("shard",)),
}
# A shard needs the pinned Trino checkout when shard-dispatch.tsv gives it one of these handlers.
TRINO_HANDLERS = frozenset({"trino-operations", "trino-like"})
# The build phase's one job per platform; it prepares the pinned Trino harness.
ENGINE_BUILD_SHARD = "engine-build"
TRINO_SHARDS = frozenset(
    row["shard_id"] for row in csv.DictReader(
        (Path(__file__).resolve().parent / "shard-dispatch.tsv").read_text().splitlines(), delimiter="\t")
    if row["native_access_handler"] in TRINO_HANDLERS) | {ENGINE_BUILD_SHARD}
# An engine archive upload slower than this has stalled; it is retried rather than left to
# hold every launch behind it.
ENGINE_BUILD_UPLOAD_MINIMUM_RATE = 256 * 1024
ENGINE_BUILD_UPLOAD_ATTEMPTS = 3
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
RECEIPT_NAMES = {
    "language-batch": "language-acceptance.json",
    "engine-build": engine_build.RECEIPT,
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
    concurrency_instance_type: str = ""
    concurrency_vcpus: int = 0


@dataclass(frozen=True)
class WorkItem:
    """One replica of a work unit on one platform, measured by any unit worker of its platform's pool."""
    platform: str
    shard: str
    unit_id: str
    replica: int
    estimated_seconds: float
    # Instances a confirmation must avoid: those that measured the unit's primary replicas.
    excluded_instances: frozenset = frozenset()

    @property
    def identity(self):
        return f"{self.platform}/{self.unit_id}/replica-{self.replica}"

    @property
    def pool(self):
        return self.platform


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
    # Regions this job's earlier Spot launches found no capacity in, each moved past once.
    spot_capacity_failures: tuple = ()

    @property
    def identity(self):
        return f"{self.platform.name}/{self.shard}/replica-{self.replica}/epoch-{self.host_epoch}"

    @property
    def logical_identity(self):
        return f"{self.platform.name}/{self.shard}/replica-{self.replica}"


def campaign_engine_tree(arguments):
    return arguments.engine_tree


def candidate_revision(arguments):
    # Hosts measure the candidate archive, so a refill may run a newer controller.
    if getattr(arguments, "refill_unresolved", False):
        return f"{arguments.candidate_ref}^{{commit}}"
    return "HEAD"


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=PHASE_TIMEOUT_SECONDS)
    parser.add_argument("campaign_id")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirmation-jobs", type=Path)
    parser.add_argument("--smoke-results", type=Path)
    parser.add_argument("--primary-results", type=Path)
    parser.add_argument("--result-root", type=Path)
    parser.add_argument("--max-concurrent", type=int, default=DEFAULT_MAX_CONCURRENT)
    parser.add_argument("--max-concurrent-per-platform", type=int, default=DEFAULT_MAX_CONCURRENT)
    parser.add_argument("--spot-only", action="store_true",
                        help="wait and retry on Spot; prohibit On-Demand launches and fallback")
    parser.add_argument("--refill-unresolved", action="store_true",
                        help="language and build campaigns only: resume an existing result root on On-Demand, "
                             "giving each job that exhausted its replacements one more attempt")
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
    parser.add_argument("--candidate-ref")
    parser.add_argument("--candidate-archive", type=Path)
    parser.add_argument("--candidate-provenance", type=Path)
    parser.add_argument("--language-plan", action="append", type=Path, default=[],
                        help="expanded language fleet plan; repeat for lifecycle and bulk")
    parser.add_argument("--region", action="append", dest="regions",
                        help="AWS region a host may launch in; repeat to let Spot placement scores choose, "
                             "ties going to the earlier region (default: AWS_REGION or us-west-2)")
    parser.add_argument("--engine-builds", type=Path,
                        help="accepted build-phase result root whose per-platform engines hosts install "
                             "when their native target matches")
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


def campaign_regions(arguments):
    return getattr(arguments, "regions", None) or [os.environ.get("AWS_REGION", "us-west-2")]


def shared_transfer_buckets(arguments):
    """Return each allowed region's shared transfer bucket; a single-region campaign may name its own."""
    buckets = getattr(arguments, "transfer_buckets", None)
    if buckets:
        return buckets
    bucket = os.environ.get("TRANSFER_BUCKET")
    return {campaign_regions(arguments)[0]: bucket} if bucket else {}


def language_campaign(arguments):
    return getattr(arguments, "language_campaign", None)


def execution_policy(arguments):
    return {"spot_only": getattr(arguments, "spot_only", False),
            "spot_vcpu_reserve": getattr(arguments, "spot_vcpu_reserve", SPOT_VCPU_SAFETY_RESERVE),
            "release_version": getattr(arguments, "release_version", ""),
            "smoke_protocol": getattr(arguments, "smoke_protocol", "smoke"),
            "max_concurrent": getattr(arguments, "max_concurrent", DEFAULT_MAX_CONCURRENT),
            "max_concurrent_per_platform": getattr(arguments, "max_concurrent_per_platform", DEFAULT_MAX_CONCURRENT),
            "phase_timeout_seconds": getattr(arguments, "topology_deadline_seconds", None),
            "fleet_budget": str(arguments.shared_budget.policy_path) if getattr(arguments, "shared_budget", None) else None,
            "fleet_policy": arguments.shared_budget.policy if getattr(arguments, "shared_budget", None) else None,
            # Each platform's engine-build manifest SHA-256, or "local" for phases that install no
            # prebuilt engines.
            "engine_builds": getattr(arguments, "engine_build_policy", "local")}


def validate_execution_policy(arguments, result_root):
    expected = execution_policy(arguments)
    path = result_root / "execution-policy.json"
    if getattr(arguments, "refill_unresolved", False):
        # A refill keeps the campaign's policy and records its own beside it.
        original = json.loads(path.read_text())
        if any(original.get(field) != expected[field] for field in ("release_version", "smoke_protocol",
                                                                     "engine_builds")):
            raise UnsafeCampaignError("refill must measure the campaign's release and protocol with its engine builds")
        path = result_root / "refill-execution-policy.json"
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
    language = language_campaign(arguments)
    if language:
        return language.deadline_seconds(arguments.max_concurrent, JOB_TIMEOUT_SECONDS,
                                         getattr(arguments, 'max_concurrent_per_platform', DEFAULT_MAX_CONCURRENT))
    return PHASE_TIMEOUT_SECONDS[arguments.phase]


def build_language_jobs(language, platforms):
    by_name = {platform.name: platform for platform in platforms}
    return [Job(allocated_platform(by_name[batch["platform"]], batch["instance_type"], batch["vcpus"]),
                batch["shard"], batch["replica"], epoch, 1, "spot", "language-batch")
            for epoch, (_, batch) in enumerate(language.batches.values(),
                                               start=PHASE_HOST_EPOCH_START[language.phase])]


def accepted_sessions_path(result_root, arguments):
    if arguments.phase == "build":
        return result_root / "accepted-engine-builds.json"
    return result_root / "accepted-language-sessions.json"


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
    """Replica 4 of each unstable unit, excluding the instances that measured its replicas 1 to 3.

    Each platform and unit has one confirmation allowance, so a unit is listed at most once.
    """
    platform_names = {platform.name for platform in platforms}
    used = {}
    for receipt in primary_receipts:
        used.setdefault((receipt["platform"], receipt["unit_id"]), set()).add(receipt["instance_id"])
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
            if len(used.get(key, ())) != 3:
                raise SystemExit(f"confirmation unit needs accepted primary replicas on three instances, "
                                 f"found {len(used.get(key, ()))}: {key}")
            selected.append((row["platform"], row["unit_id"], 4, frozenset(used[key])))
    return selected


def build_engine_jobs(platforms):
    return [Job(platform, ENGINE_BUILD_SHARD, 1, host_epoch, 1, "spot", "engine-build")
            for host_epoch, platform in enumerate(platforms, start=PHASE_HOST_EPOCH_START["build"])]


def build_work_items(phase, platforms, units, shards, confirmation_units=()):
    """Each platform's replicas of the phase's units: replica 1 in smoke, 1 to 3 in primary, and
    the listed replica 4 confirmations."""
    if phase == "confirmation":
        selected = confirmation_units
    else:
        replicas = (1,) if phase == "smoke" else (1, 2, 3)
        ordered = [unit_id for unit_id, unit in sorted(units.items(), key=lambda entry: (
            entry[1]["shard_id"], int(entry[1]["unit_index"]))) if unit["shard_id"] in shards]
        selected = [(platform.name, unit_id, replica, frozenset())
                    for platform in platforms
                    for unit_id in ordered
                    for replica in replicas]
    return [WorkItem(platform, units[unit_id]["shard_id"], unit_id, replica,
                     float(units[unit_id]["estimated_seconds"]), instances)
            for platform, unit_id, replica, instances in selected]


def unit_worker_job(platform, ordinal, host_epoch, market, spot_capacity_failures=()):
    """A unit worker host of a platform's pool; its replica is the worker's ordinal within the phase."""
    return Job(platform, UNIT_WORKER_SHARD, ordinal, host_epoch, 1, market, "unit-worker",
               spot_capacity_failures=tuple(spot_capacity_failures))


def unit_worker_pool(job):
    return job.platform.name


def allocated_platform(platform, instance_type, vcpus):
    allowed = {
        (platform.instance_type, platform.vcpus),
        (platform.concurrency_instance_type, platform.concurrency_vcpus),
    }
    if (instance_type, vcpus) not in allowed:
        raise ValueError("planned worker allocation is not available for the platform")
    return replace(platform, instance_type=instance_type, vcpus=vcpus)


def platform_has_capacity(candidate, running, arguments):
    limit = getattr(arguments, 'max_concurrent_per_platform', DEFAULT_MAX_CONCURRENT)
    return sum(job.platform.name == candidate.platform.name for _, job, _ in running.values()) < limit


def validate_configuration(arguments, jobs, launched_jobs=None, items=None):
    """Check the campaign's limits and derive its phase deadline; items are a unit campaign's work."""
    campaign_heap_size(arguments)
    if getattr(arguments, "spot_vcpu_reserve", SPOT_VCPU_SAFETY_RESERVE) < 0:
        raise SystemExit("Spot vCPU reserve must not be negative")
    if getattr(arguments, "spot_only", False) and any(job.market != "spot" for job in jobs):
        raise SystemExit("Spot-only campaigns cannot contain On-Demand jobs")
    regions = campaign_regions(arguments)
    if len(regions) != len(set(regions)) or not all(re.fullmatch(REGION_PATTERN, region) for region in regions):
        raise SystemExit(f"allowed regions must be distinct AWS region names: {regions}")
    amis = getattr(arguments, "regional_amis", None)
    if amis is not None:
        missing = [region for region in regions if not any(row_region == region for _, row_region in amis)]
        if missing:
            raise SystemExit(f"platform-regions.tsv has no AMIs for allowed regions: {missing}")
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
    if getattr(arguments, "refill_unresolved", False) and (getattr(arguments, "spot_only", False) or budget):
        raise SystemExit("refill runs On-Demand without --spot-only or a shared fleet budget")
    # Each worker type launches only in the allowed regions that hold its image.
    workers = campaign_worker_platforms(arguments, jobs, items)
    launchable = {platform.name: [region for region in regions if amis is None or (platform.name, region) in amis]
                  for platform in workers}
    fleet_limits, fleet_vcpus = {}, None
    if budget:
        limits = region_limits(budget.policy)
        fleet_limits = {region: limits[region] for region in regions if region in limits}
        # A wave fills every allowed region's limit at once.
        fleet_vcpus = max(1, sum(fleet_limits[region]["max_vcpus"] for region in fleet_limits))
    on_demand = set(on_demand_regions(budget.policy)) if budget else set()
    if budget and (getattr(arguments, "spot_vcpu_reserve", None) != budget.policy["spot_vcpu_reserve"] or
                   not (getattr(arguments, "spot_only", False) or all(
                       on_demand & set(launchable[platform.name]) for platform in workers))):
        raise SystemExit("shared fleet requires a reserve matching its policy, and --spot-only unless "
                         "the policy allows On-Demand in an allowed region holding each worker type's image")
    if getattr(arguments, "execute", False) and arguments.max_concurrent > DEFAULT_MAX_CONCURRENT and budget is None:
        raise SystemExit("more than 64 workers requires a shared fleet budget")
    if items is not None and budget and budget.policy["max_attempt_seconds"] < UNIT_WORKER_TIMEOUT_SECONDS + 15 * 60:
        raise SystemExit("unit workers require a shared fleet max_attempt_seconds of at least "
                         f"{UNIT_WORKER_TIMEOUT_SECONDS + 15 * 60}, a worker's lifetime, last unit and cleanup")
    if not arguments.campaign_id or len(arguments.campaign_id) > 63:
        raise SystemExit("campaign_id must contain 1-63 characters")
    if type(arguments.max_concurrent) is not int or arguments.max_concurrent < 1:
        raise SystemExit("max concurrency must be a positive integer")
    per_platform = getattr(arguments, 'max_concurrent_per_platform', DEFAULT_MAX_CONCURRENT)
    if type(per_platform) is not int or per_platform < 1:
        raise SystemExit("per-platform concurrency must be a positive integer")
    if arguments.phase == "primary" and len(jobs if items is None else items) == 0:
        raise SystemExit("primary campaign has no jobs")
    # A refill's deadline only needs to fit the attempts it launches.
    launched_jobs = jobs if launched_jobs is None else launched_jobs
    counts = {}
    for job in launched_jobs:
        counts[job.platform.name] = counts.get(job.platform.name, 0) + 1
    host_limit = min(arguments.max_concurrent, budget.policy["max_hosts"]) if budget else arguments.max_concurrent
    vcpu_waves = 0
    if budget:
        if any(platform.vcpus > max((fleet_limits[region]["max_vcpus"] for region in launchable[platform.name]
                                     if region in fleet_limits), default=0) for platform in workers):
            raise SystemExit("shared fleet vCPU limit cannot admit every worker type")
        vcpu_waves = math.ceil(sum(job.platform.vcpus for job in jobs) / fleet_vcpus)
    if items is None:
        bounded_waves = max(math.ceil(len(launched_jobs) / host_limit),
                            max((math.ceil(count / per_platform) for count in counts.values()), default=0),
                            vcpu_waves)
        required_seconds = (bounded_waves + 1) * JOB_TIMEOUT_SECONDS
    else:
        required_seconds = unit_phase_seconds(arguments, items, host_limit, per_platform, fleet_vcpus)
    explicit_deadline = getattr(arguments, "phase_timeout_seconds", None)
    arguments.topology_deadline_seconds = (explicit_deadline if explicit_deadline is not None else
                                          max(PHASE_TIMEOUT_SECONDS[arguments.phase], required_seconds + 3600))
    if arguments.topology_deadline_seconds < required_seconds:
        raise SystemExit("campaign topology cannot fit its phase deadline with one retry wave: "
                         f"requires {required_seconds}s, allows {arguments.topology_deadline_seconds}s")
    if language_campaign(arguments) and campaign_heap_size(arguments) != "8g":
        raise SystemExit("language protocol requires an 8g heap")
    identities = [job.identity for job in jobs]
    if len(identities) != len(set(identities)):
        raise SystemExit("campaign contains duplicate job identities")
    epochs = [job.host_epoch for job in jobs]
    if len(epochs) != len(set(epochs)):
        raise SystemExit("campaign contains reused host epochs")


def campaign_worker_platforms(arguments, jobs, items):
    """The worker types a campaign launches: its host jobs' platforms, or its unit pools' hosts."""
    if items is None:
        return [job.platform for job in jobs]
    platforms = getattr(arguments, "unit_platforms", {})
    return [platforms[name] for name in sorted({item.pool for item in items}) if name in platforms]


def unit_phase_seconds(arguments, items, host_limit, per_platform, fleet_vcpus):
    """The wall clock the phase's units need: worker lifetimes of work at the concurrency limits,
    plus one retry wave, each wave the wrapper's full worker timeout."""
    platforms = getattr(arguments, "unit_platforms", {})
    work = {}
    vcpu_seconds = 0
    for item in items:
        work[item.platform] = work.get(item.platform, 0) + item.estimated_seconds
        platform = platforms.get(item.platform)
        if platform is not None:
            vcpu_seconds += platform.vcpus * item.estimated_seconds
    lifetime = UNIT_WORKER_LIFETIME_SECONDS
    waves = max([math.ceil(sum(work.values()) / (host_limit * lifetime))] +
                [math.ceil(seconds / (min(per_platform, host_limit) * lifetime)) for seconds in work.values()] +
                ([math.ceil(vcpu_seconds / (fleet_vcpus * lifetime))] if fleet_vcpus else []))
    return (waves + 1) * UNIT_WORKER_TIMEOUT_SECONDS


def validate_durable_paths(root, arguments, result_root):
    build_output = (root / "target").resolve()
    paths = (
        ("result root", result_root),
        ("candidate archive", arguments.candidate_archive),
        ("candidate provenance", arguments.candidate_provenance),
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


def deterministic_archive_sha256(root, candidate):
    # freeze-candidate.sh adds the tables it generated beside the baseline tooling to the archive.
    tables = [root / "tools/re2-benchmark/baseline" / table for table in work_units.TABLES]
    missing = [table.name for table in tables if not table.is_file()]
    if missing:
        raise SystemExit(f"the candidate's generated tables {missing} are missing; freeze-candidate.sh writes them")
    archive = subprocess.Popen(
        ["git", "archive", "--format=tar", "--prefix=tools/re2-benchmark/baseline/",
         *(f"--add-file={table}" for table in tables), "--prefix=", candidate],
        cwd=root,
        stdout=subprocess.PIPE)
    compressor = subprocess.Popen(
        ["gzip", "-n"],
        stdin=archive.stdout,
        stdout=subprocess.PIPE)
    archive.stdout.close()
    digest = hashlib.sha256()
    while chunk := compressor.stdout.read(1024 * 1024):
        digest.update(chunk)
    compressor.stdout.close()
    compressor_status = compressor.wait()
    archive_status = archive.wait()
    if archive_status != 0 or compressor_status != 0:
        raise SystemExit(
            f"could not reproduce candidate archive: git={archive_status}, gzip={compressor_status}")
    return digest.hexdigest()


def archive_file_list_sha256(path):
    with tarfile.open(path, "r:gz") as archive:
        # tar -tf, used by freeze-candidate.sh, preserves directory slashes;
        # Python's TarInfo removes them while reading the same archive.
        names = [member.name.rstrip("/") + "/" if member.isdir() else member.name for member in archive]
        file_list = "".join(name + "\n" for name in sorted(names))
    return hashlib.sha256(file_list.encode()).hexdigest()


def validate_source(root, arguments):
    if not arguments.candidate_ref or arguments.candidate_archive is None or arguments.candidate_provenance is None:
        raise SystemExit(
            "execution requires --candidate-ref, --candidate-archive, and --candidate-provenance")
    if check_output(["git", "status", "--porcelain"], cwd=root):
        raise SystemExit("campaign execution requires a clean Regulator worktree")
    head = check_output(["git", "rev-parse", "HEAD"], cwd=root)
    candidate = check_output(["git", "rev-parse", f"{arguments.candidate_ref}^{{commit}}"], cwd=root)
    if head != candidate and not getattr(arguments, "refill_unresolved", False):
        raise SystemExit(f"candidate ref {arguments.candidate_ref} is {candidate}, but HEAD is {head}")
    engine_tree = check_output(["git", "rev-parse", f"{candidate_revision(arguments)}:src/main"], cwd=root)
    with arguments.candidate_provenance.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != CANDIDATE_PROVENANCE_FIELDS:
            raise SystemExit(
                f"candidate provenance fields are {tuple(reader.fieldnames or ())}, "
                f"expected {CANDIDATE_PROVENANCE_FIELDS}")
        rows = list(reader)
    if len(rows) != 1:
        raise SystemExit("candidate provenance must contain exactly one row")
    provenance = rows[0]
    archive_sha256 = hashlib.sha256(arguments.candidate_archive.read_bytes()).hexdigest()
    expected = {
        "candidate_ref": arguments.candidate_ref,
        "candidate_commit": candidate,
        "root_tree": check_output(["git", "rev-parse", f"{candidate_revision(arguments)}^{{tree}}"], cwd=root),
        "engine_tree": engine_tree,
        "archive_sha256": archive_sha256,
        "archive_file_list_sha256": archive_file_list_sha256(arguments.candidate_archive),
        "row_manifest_sha256": hashlib.sha256(
            campaign_manifest(root, arguments).read_bytes()).hexdigest(),
        "platform_manifest_sha256": hashlib.sha256(
            (root / "tools/re2-benchmark/baseline/platforms.tsv").read_bytes()).hexdigest(),
        "comparator_manifest_sha256": hashlib.sha256(
            (root / "tools/re2-benchmark/baseline/comparators.tsv").read_bytes()).hexdigest(),
        "shard_manifest_sha256": hashlib.sha256(
            campaign_shards(root, arguments).read_bytes()).hexdigest(),
        "protocol_representatives_sha256": hashlib.sha256(
            (root / "tools/re2-benchmark/baseline/protocol-representatives.tsv").read_bytes()).hexdigest(),
    }
    for field, value in expected.items():
        if provenance.get(field) != value:
            raise SystemExit(
                f"candidate provenance {field} is {provenance.get(field)!r}, expected {value!r}")
    reproducible_archive_sha256 = deterministic_archive_sha256(root, candidate)
    if archive_sha256 != reproducible_archive_sha256:
        raise SystemExit(
            "candidate archive contents do not match the deterministic archive of the candidate ref")
    arguments.candidate_archive_sha256 = archive_sha256
    arguments.engine_tree = engine_tree


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


def frozen_input_fingerprint(root, arguments, jobs_path):
    if not (root / ".git").exists():
        return None
    tracked_inputs = tracked_tooling(root)
    tracked_inputs.extend((
        arguments.candidate_archive,
        arguments.candidate_provenance,
        jobs_path))
    if language_campaign(arguments):
        tracked_inputs.extend(directory / "plan.json" for directory, _ in language_campaign(arguments).plans.values())
    # The policy binds each manifest's SHA-256 and each wrapper checks the archives against
    # it, so large archives are not rehashed at every launch.
    tracked_inputs.extend(directory / engine_build.MANIFEST for _, directory in
                          sorted(getattr(arguments, "engine_build_directories", {}).items()))
    return (
        execution_policy(arguments),
        check_output(["git", "rev-parse", f"{arguments.candidate_ref}^{{commit}}"], cwd=root),
        tuple((str(path), hashlib.sha256(path.read_bytes()).hexdigest()) for path in tracked_inputs),
    )


def validate_frozen_inputs(root, arguments, jobs_path, expected_fingerprint):
    if expected_fingerprint is None:
        return
    if frozen_input_fingerprint(root, arguments, jobs_path) != expected_fingerprint:
        raise UnsafeCampaignError("the frozen candidate or campaign manifest changed during execution")


def read_receipt(path):
    receipt = json.loads(path.read_text())
    if receipt.get("schema_version") != 1 or receipt.get("workload") not in JSON_LEDGER_WORKLOADS.values():
        raise RuntimeError(f"unexpected JSON receipt schema in {path}")
    return receipt


def validate_unit_phase_prerequisite(root, arguments, result_root, expected_replicas, phase):
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
        unit_session.validate_unit_campaign(
            campaign_manifest(root, arguments),
            root / "tools/re2-benchmark/baseline/platforms.tsv",
            campaign_shards(root, arguments),
            accepted_units,
            arguments.campaign_id,
            expected_replicas,
            selected_shards=getattr(arguments, "selected_shards", None))
        context = None
        for receipt in read_accepted_units(accepted_units):
            context = context or unit_validation_context(root, arguments)
            validate_unit_artifacts(root, arguments, result_root, receipt, context=context)
    except (OSError, ValidationError, UnsafeCampaignError) as error:
        raise SystemExit(f"{phase} prerequisite campaign is not accepted: {error}") from error


def validate_prerequisites(root, arguments, platforms):
    if language_campaign(arguments):
        if arguments.phase == "primary":
            if arguments.smoke_results is None:
                raise SystemExit("primary execution requires --smoke-results")
            from copy import copy
            smoke_arguments = copy(arguments)
            smoke_arguments.phase = "smoke"
            smoke_arguments.language_campaign = LanguageCampaign(arguments.language_plan, "smoke")
            smoke_jobs = build_language_jobs(smoke_arguments.language_campaign, platforms)
            ledger = accepted_sessions_path(arguments.smoke_results, smoke_arguments)
            accepted = recover_accepted_sessions(root, smoke_arguments, arguments.smoke_results, smoke_jobs, ledger)
            validate_language_coverage(smoke_arguments, smoke_jobs, accepted)
    elif arguments.phase == "primary":
        validate_unit_phase_prerequisite(root, arguments, arguments.smoke_results, (1,), "smoke")
    elif arguments.phase == "confirmation":
        validate_unit_phase_prerequisite(root, arguments, arguments.primary_results, (1, 2, 3), "primary")


def resolve_engine_builds(root, arguments, platforms):
    builds = getattr(arguments, "engine_builds", None)
    if arguments.phase == "build" or language_campaign(arguments):
        if builds is not None:
            raise SystemExit("engine builds apply only to baseline smoke, primary, and confirmation")
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
    from copy import copy
    build_arguments = copy(arguments)
    build_arguments.phase = "build"
    jobs = build_engine_jobs(platforms)
    try:
        accepted = recover_accepted_sessions(
            root, build_arguments, builds, jobs, accepted_sessions_path(builds, build_arguments))
    except (OSError, RuntimeError, ValueError) as error:
        raise SystemExit(f"engine builds are not accepted: {error}") from error
    missing = [job.platform.name for job in jobs if job.logical_identity not in accepted]
    if missing:
        raise SystemExit(f"engine builds have no accepted build for platforms {missing}")
    arguments.engine_build_policy = {}
    arguments.engine_build_directories = {}
    for job in jobs:
        receipt = accepted[job.logical_identity]
        session = single_session(builds, replace(job, host_epoch=int(receipt["host_epoch"])))
        arguments.engine_build_policy[job.platform.name] = receipt["manifest_sha256"]
        arguments.engine_build_directories[job.platform.name] = (
            session / job.platform.architecture / "re2-results" / engine_build.DIRECTORY)


def validate_dependencies(root, arguments, jobs):
    if language_campaign(arguments):
        # Expanded plans already contain checksummed source workloads. The JVM
        # worker resolves and verifies the pinned Joni jar during its clean build.
        for directory, _ in language_campaign(arguments).plans.values():
            language_fleet.validate(directory)
        return
    subprocess.run([str(root / "tools/re2-benchmark/baseline/validate-manifest.py")], check=True)
    if not any(job.shard in TRINO_SHARDS for job in jobs):
        return
    trino_directory = os.environ.get("TRINO_DIR")
    if not trino_directory:
        raise SystemExit("campaign execution requires TRINO_DIR to name the pinned Trino checkout")
    trino_root = Path(trino_directory)
    expected_trino = "c7503d170344c4e266f03fdb39e53c82aa7e3594"
    actual_trino = check_output(["git", "rev-parse", f"{expected_trino}^{{commit}}"], cwd=trino_root)
    if actual_trino != expected_trino:
        raise SystemExit(f"Trino checkout does not contain {expected_trino}")


def describe_image(arguments, region, ami_id):
    return check_output(aws_output(arguments, region) + [
        "ec2", "describe-images", "--image-ids", ami_id,
        "--query", "Images[0].[Architecture,State,OwnerId,Name]", "--output", "text",
    ]).split("\t")


def validate_aws(arguments, platforms, jobs=None):
    regions = campaign_regions(arguments)
    account = check_output(aws_output(arguments, regions[0]) + [
        "sts", "get-caller-identity", "--query", "Account", "--output", "text"])
    budget = getattr(arguments, "shared_budget", None)
    if budget and (account != budget.policy["account"] or
                   not set(regions) <= set(region_limits(budget.policy))):
        raise SystemExit("AWS account or region differs from the shared fleet policy")
    required_platforms = list({
        (platform.name, platform.instance_type): platform
        for platform in ([job.platform for job in jobs] if jobs is not None else platforms)
    }.values())
    if not required_platforms:
        required_platforms = platforms
    maximum_vcpus = max(platform.vcpus for platform in required_platforms)
    has_spot_capacity = has_on_demand_capacity = False
    quotas = []
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
        has_spot_capacity |= spot_quota >= getattr(arguments, "spot_vcpu_reserve", SPOT_VCPU_SAFETY_RESERVE) + maximum_vcpus
        has_on_demand_capacity |= on_demand_quota >= ON_DEMAND_VCPU_SAFETY_RESERVE + maximum_vcpus
        quotas.append(("" if len(regions) == 1 else region + ": ") +
                      f"spot={spot_quota:g}, on-demand={on_demand_quota:g}")
    if getattr(arguments, "spot_only", False) and not has_spot_capacity:
        raise SystemExit("Standard Spot quota cannot admit one host after the configured reserve")
    if getattr(arguments, "refill_unresolved", False) and not has_on_demand_capacity:
        raise SystemExit("Standard On-Demand quota cannot admit one host after its safety reserve")
    if not has_spot_capacity and not has_on_demand_capacity:
        raise SystemExit(
            f"Neither Standard Spot nor On-Demand quota can admit one host after safety reserves: "
            + "; ".join(quotas))
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
        for region in regions:
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
            raise SystemExit(f"{platform.instance_type} is not offered in any allowed region")
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


def upload_engine_builds(arguments, jobs):
    """Put each engine archive once in every region that may launch a host installing it."""
    directories = getattr(arguments, "engine_build_directories", None)
    if not directories:
        return
    buckets = shared_transfer_buckets(arguments)
    uploads = {(job.platform.name, region) for job in jobs for region in launch_regions(arguments, job)}
    for platform, region in sorted(uploads):
        build = directories[platform]
        manifest = engine_build.load_manifest(build, arguments.engine_build_policy[platform])
        archive = manifest["archives"]["trino"]
        path = build / archive["file"]
        key = f"{os.environ['TRANSFER_PREFIX']}/engine-builds/{archive['sha256']}.tar.gz"
        checksum = base64.b64encode(bytes.fromhex(archive["sha256"])).decode()
        prefix = aws_output(arguments, region) + ["s3api"]
        for attempt in range(1, ENGINE_BUILD_UPLOAD_ATTEMPTS + 1):
            try:
                existing = check_output(prefix + [
                    "head-object", "--bucket", buckets[region], "--key", key, "--checksum-mode", "ENABLED",
                    "--query", "ChecksumSHA256", "--output", "text"], stderr=subprocess.DEVNULL, timeout=60)
            except subprocess.SubprocessError:
                existing = None
            if existing == checksum:
                break
            print(f"uploading {platform} engine archive to {region} (attempt {attempt})", flush=True)
            try:
                # A single PUT keeps S3's checksum the whole-object SHA-256, which S3 verifies on upload.
                check_output(prefix + [
                    "put-object", "--bucket", buckets[region], "--key", key, "--body", str(path),
                    "--checksum-algorithm", "SHA256", "--checksum-sha256", checksum],
                    timeout=60 + path.stat().st_size / ENGINE_BUILD_UPLOAD_MINIMUM_RATE)
                break
            except subprocess.SubprocessError as error:
                if attempt == ENGINE_BUILD_UPLOAD_ATTEMPTS:
                    raise SystemExit(f"could not upload the {platform} engine archive to "
                                     f"s3://{buckets[region]}/{key}: {error}") from error


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
    """Return the allowed regions that offer the job's instance type and image."""
    offered = getattr(arguments, "region_offerings", {}).get(job.platform.instance_type)
    amis = getattr(arguments, "regional_amis", None)
    regions = [region for region in campaign_regions(arguments)
               if (offered is None or region in offered) and (amis is None or (job.platform.name, region) in amis)]
    if not regions:
        raise UnsafeCampaignError(f"no allowed region can launch {job.platform.name} {job.platform.instance_type}")
    return regions


def launch_placements(arguments, job):
    """Order the allowed regions that can launch the job, best Spot placement score first."""
    regions = launch_regions(arguments, job)
    if job.market != "spot":
        return [(region, "") for region in regions]
    # A Spot replacement skips the regions where this job's Spot launches found no capacity.
    eligible = [region for region in regions if region not in job.spot_capacity_failures]
    if len(eligible) <= 1:
        return [(region, "") for region in eligible]
    scores = spot_placement_scores(arguments, job.platform.instance_type, regions)
    if scores is None:
        # Without scores, keep the allowed-region order; launch admission picks the first region
        # with headroom only where it queries quota.
        return [(region, "") for region in eligible]
    # The sort is stable, so equal scores keep the allowed-region order.
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
    default = campaign_regions(arguments)[0]
    return [job for job in running_jobs if (job.region or default) == region]


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
        (item.platform, item.shard, item.unit_id, item.replica, f"{item.estimated_seconds:g}",
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
    if getattr(arguments, "spot_only", False) and job.market != "spot":
        raise UnsafeCampaignError("Spot-only policy prohibits launching an On-Demand job")
    environment = os.environ.copy()
    if getattr(arguments, "shared_budget", None):
        environment["CAMPAIGN_SPOT_MAX_PRICE"] = str(arguments.shared_budget.policy["hourly_rates"][job.platform.instance_type]["spot"])
    architecture = job.platform.architecture
    benchmark_protocol = phase_protocol(arguments)
    unit_worker = job.workload == "unit-worker"
    region = job.region or campaign_regions(arguments)[0]
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
    if len(campaign_regions(arguments)) > 1:
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
        "CAMPAIGN_SPOT_ONLY": "1" if getattr(arguments, "spot_only", False) else "0",
        "REGULATOR_RELEASE_VERSION": getattr(arguments, "release_version", ""),
        "BENCHMARK_CPU_LIST": "0",
        "BENCHMARK_EXPECTED_VCPUS": str(job.platform.vcpus),
        "TIMEOUT_SECONDS": str(UNIT_WORKER_TIMEOUT_SECONDS if unit_worker else JOB_TIMEOUT_SECONDS),
        "RESULT_ROOT": str(job_result_root(result_root, job)),
        "TRINO_REVISION": "c7503d170344c4e266f03fdb39e53c82aa7e3594",
        "JONI_COMPARATOR_ORDER": "forward" if job.replica % 2 else "reverse",
        "NATIVE_COMPILER_PACKAGE": job.platform.native_compiler_package,
        "CMAKE_PACKAGE": job.platform.cmake_package,
        "GLIBC_PACKAGE": job.platform.glibc_package,
        "CARGO_PACKAGE": job.platform.cargo_package,
        "RUST_PACKAGE": job.platform.rust_package,
        "TIME_PACKAGE": job.platform.time_package,
        "BASELINE_CANDIDATE_ARCHIVE": str(arguments.candidate_archive.resolve()),
        "BASELINE_CANDIDATE_ARCHIVE_SHA256": arguments.candidate_archive_sha256,
        "BASELINE_EXPECTED_ENGINE_TREE": campaign_engine_tree(arguments),
        "BENCHMARK_HEAP_SIZE": campaign_heap_size(arguments),
    })
    if unit_worker:
        environment["UNIT_WORKER_LIFETIME_SECONDS"] = str(UNIT_WORKER_LIFETIME_SECONDS)
    # Only unit workers install engine builds; never pass the controller's own environment through.
    environment.pop("ENGINE_BUILD_DIRECTORY", None)
    environment.pop("ENGINE_BUILD_MANIFEST_SHA256", None)
    directories = getattr(arguments, "engine_build_directories", None)
    if directories and unit_worker:
        environment["ENGINE_BUILD_DIRECTORY"] = str(directories[job.platform.name].resolve())
        environment["ENGINE_BUILD_MANIFEST_SHA256"] = arguments.engine_build_policy[job.platform.name]
    if language_campaign(arguments):
        package = result_root / "language-packages" / (job.logical_identity.replace("/", "-") + ".tar.gz")
        checksum = language_campaign(arguments).package(job.logical_identity, package)
        environment.update({
            "LANGUAGE_BATCH_ARCHIVE": str(package.resolve()),
            "LANGUAGE_BATCH_ARCHIVE_SHA256": checksum,
            "LANGUAGE_CANDIDATE_PROVENANCE": str(arguments.candidate_provenance.resolve()),
            "LANGUAGE_CANDIDATE_PROVENANCE_SHA256": hashlib.sha256(arguments.candidate_provenance.read_bytes()).hexdigest(),
            "PYTHONDONTWRITEBYTECODE": "1",
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


def validate_job_artifacts(result_root, job):
    session, _ = validate_cleanup_manifest(result_root, job)
    if (session / "campaign-success").read_text() != "complete\n":
        raise RuntimeError(f"{job.identity} has no campaign success marker")
    label = job.platform.architecture
    receipt = session / label / "re2-results" / RECEIPT_NAMES[job.workload]
    if not receipt.is_file():
        raise RuntimeError(f"{job.identity} has no acceptance receipt")
    validate_capacity(session / label / "re2-results" / "host-capacity.txt")
    if job.platform.concurrency_instance_type:
        environment = read_properties(receipt.parent / "environment-manifest.txt")
        if environment.get("logical_cpu_count") != str(job.platform.vcpus):
            raise UnsafeCampaignError("host logical CPU count differs from the frozen topology")
    if job.workload == "language-batch":
        try:
            if batch_acceptance.validate(receipt.parent) != read_receipt(receipt):
                raise ValueError("saved language receipt differs from raw evidence")
        except (ValueError, KeyError, TypeError) as error:
            raise RuntimeError(f"invalid language evidence for {job.identity}: {error}") from error
    if job.workload == "engine-build":
        try:
            if engine_build.validate(receipt.parent) != read_receipt(receipt):
                raise ValueError("saved engine build receipt differs from its archives")
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise RuntimeError(f"invalid engine build for {job.identity}: {error}") from error
    return receipt


def single_session(result_root, job):
    job_root = job_result_root(result_root, job)
    if not job_root.is_dir():
        raise RuntimeError(f"{job.identity} produced no result directory")
    sessions = [path for path in job_root.iterdir() if path.is_dir()]
    if len(sessions) != 1:
        raise RuntimeError(f"{job.identity} produced {len(sessions)} session directories")
    return sessions[0]


def validate_failed_job(result_root, job):
    session, cleanup = validate_cleanup_manifest(result_root, job)
    if cleanup.get("run_status") != "failed":
        raise RuntimeError(f"{job.identity} retry classification does not describe a failed run")
    return session, cleanup


def replacement_job(job, host_epoch, failure_classification, failure_detail="", *, regions=(), spot_only=False):
    tried = (*job.spot_capacity_failures, job.region)
    if (job.market == "spot" and job.region and
            failure_classification == "retryable-spot" and failure_detail.startswith("launch-capacity:") and
            any(region not in tried for region in regions)):
        # A Spot launch that found no capacity tries Spot in the next allowed region first. Each
        # region is tried once, so these moves do not spend the replacement allowance.
        return replace(job, host_epoch=host_epoch, attempt=job.attempt + 1, spot_capacity_failures=tried)
    counted_attempt = job.attempt - len(job.spot_capacity_failures)
    if (failure_classification != "retryable-capacity" and
            counted_attempt >= MAX_REPLACEMENTS_PER_JOB + 1):
        raise RuntimeError(f"{job.logical_identity} exhausted its bounded replacement allowance")
    if spot_only:
        if job.market != "spot":
            raise UnsafeCampaignError("Spot-only policy cannot resume an On-Demand attempt")
        market = "spot"
    elif job.market == "spot" and failure_classification == "retryable-infrastructure" and counted_attempt < 2:
        # A failure Spot did not cause gets one more Spot attempt. A reclaim or Spot
        # unavailability moves directly to the same On-Demand instance type.
        market = "spot"
    else:
        market = "on-demand"
    return replace(job, host_epoch=host_epoch, attempt=job.attempt + 1, market=market)


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


def receipt_logical_identity(receipt):
    return f"{receipt['platform']}/{receipt['shard_id']}/replica-{receipt['replica_id']}"


def attempt_logical_identity(row):
    return f"{row['platform']}/{row['shard_id']}/replica-{row['replica']}"


def read_accepted_sessions(path):
    if not path.exists():
        return []
    data = json.loads(path.read_text())
    if data.get("schema_version") != 1 or data.get("workload") != JSON_LEDGER_WORKLOADS.get(path.name):
        raise UnsafeCampaignError(f"unexpected JSON ledger schema in {path}")
    rows = data["receipts"]
    identities = [receipt_logical_identity(row) for row in rows]
    if len(identities) != len(set(identities)):
        raise UnsafeCampaignError(f"accepted-session ledger contains duplicate logical jobs: {path}")
    return rows


def persist_accepted_receipt(path, receipt_path):
    receipt = read_receipt(receipt_path)
    rows = read_accepted_sessions(path)
    if accepted_row(rows, receipt, receipt_logical_identity, "host_epoch", "epochs") is None:
        write_ledger(path, [*rows, receipt])
    return receipt


def unit_validation_context(root, arguments):
    """The unit tables, frozen campaign, and selected release that a pass over unit attempts checks against."""
    version = getattr(arguments, "release_version", "")
    return unit_session.load_unit_tables(campaign_manifest(root, arguments)), {
        "campaign_id": arguments.campaign_id,
        "candidate_commit": check_output(["git", "rev-parse", candidate_revision(arguments)], cwd=root),
        "candidate_archive_sha256": arguments.candidate_archive_sha256,
        "engine_tree": campaign_engine_tree(arguments),
        "heap_size": campaign_heap_size(arguments),
    }, released_artifact.manifest(root, version) if version else None


def validate_unit_artifacts(root, arguments, result_root, receipt, directory=None, context=None):
    """Revalidate a unit attempt from its evidence; the receipt must regenerate exactly.

    The attempt is the retained one unless directory names another copy. Its host session's
    directory, the attempt's parent, holds the worker host's calibrations it references and, for
    a chained attempt, the completion marker of the lease its before calibration came from. A release
    campaign's attempt must also prove each route measured the selected release's JAR. A pass over
    several attempts passes the unit_validation_context it resolved once.
    """
    tables, expected, release = context or unit_validation_context(root, arguments)
    directory = directory or unit_attempt_directory(result_root, receipt)
    identity = f"{unit_logical_identity(receipt)} attempt {receipt['unit_attempt_id']}"
    try:
        if (directory / "status.txt").read_text() != "status=complete\n":
            raise RuntimeError("unit attempt did not complete")
        validate_capacity(directory / "unit-capacity.txt")
        artifact_receipt = read_unit_receipt(directory / "unit-receipt.tsv")
        regenerated = unit_session.validate_unit_results(directory, host_evidence=directory.parent, tables=tables)
        unit_session.check_chain_predecessor(directory, directory.parent)
        if release:
            released_artifact.validate_baseline(directory, release)
    except (OSError, RuntimeError, ValueError, KeyError) as error:
        raise UnsafeCampaignError(f"unit artifacts are not accepted for {identity}: {error}") from error
    if artifact_receipt != receipt or regenerated != receipt:
        raise UnsafeCampaignError(f"accepted unit receipt cannot be reproduced from its artifacts: {identity}")
    mismatches = [field for field, value in expected.items() if receipt[field] != value]
    if mismatches:
        raise UnsafeCampaignError(f"accepted unit receipt does not match the frozen campaign for {identity}: "
                                  f"{mismatches}")


def recover_accepted_units(root, arguments, result_root, accepted_units):
    """Revalidate accepted-units.tsv against retained attempts and add a completed attempt it lacks.

    The first accepted attempt of a unit replica wins; a later completed one is superseded.
    """
    accepted = {}
    # The tables and frozen campaign are resolved once, for the first attempt the pass validates.
    context = None
    for receipt in read_accepted_units(accepted_units):
        context = context or unit_validation_context(root, arguments)
        validate_unit_artifacts(root, arguments, result_root, receipt, context=context)
        accepted[unit_logical_identity(receipt)] = receipt
    for receipt_path in sorted((result_root / "units").glob("*/*/replica-*/*/lease-*/unit-receipt.tsv")):
        try:
            receipt = read_unit_receipt(receipt_path)
        except (OSError, RuntimeError) as error:
            raise UnsafeCampaignError(f"invalid retained unit receipt {receipt_path}: {error}") from error
        if unit_attempt_directory(result_root, receipt) != receipt_path.parent:
            raise UnsafeCampaignError(f"unit receipt is not in its attempt's directory: {receipt_path}")
        identity = unit_logical_identity(receipt)
        if identity in accepted:
            continue
        context = context or unit_validation_context(root, arguments)
        validate_unit_artifacts(root, arguments, result_root, receipt, context=context)
        accepted[identity] = persist_accepted_unit(accepted_units, receipt_path)
    return accepted


def validate_receipt_candidate(root, arguments, result_root, job, receipt, receipt_path=None):
    version = getattr(arguments, "release_version", "")
    # Engine builds come from the candidate's tooling; the release is measured by the batches.
    if version and job.workload == "language-batch":
        if receipt.get("released_artifact") != released_artifact.manifest(root, version):
            raise UnsafeCampaignError("language receipt does not identify the selected release")
    expected = {
        "campaign_id": arguments.campaign_id,
        "platform": job.platform.name,
        "shard_id": job.shard,
        "replica_id": str(job.replica),
        "host_epoch": str(job.host_epoch),
        "architecture": job.platform.architecture,
        "instance_type": job.platform.instance_type,
        "candidate_commit": check_output(["git", "rev-parse", candidate_revision(arguments)], cwd=root),
        "candidate_archive_sha256": arguments.candidate_archive_sha256,
        "engine_tree": campaign_engine_tree(arguments),
        "heap_size": campaign_heap_size(arguments),
    }
    if language_campaign(arguments):
        del expected["engine_tree"]
        expected["source_tree"] = check_output(
            ["git", "rev-parse", f"{candidate_revision(arguments)}^{{tree}}"], cwd=root)
        expected["comparators_sha256"] = hashlib.sha256(
            (root / "tools/re2-benchmark/baseline/comparators.tsv").read_bytes()).hexdigest()
        language_campaign(arguments).validate_receipt(job.logical_identity, receipt)
    elif job.workload == "engine-build":
        # The candidate commit and archive bind the tree; the build does not depend on the heap.
        del expected["engine_tree"], expected["heap_size"]
    mismatches = [field for field, value in expected.items() if receipt.get(field) != value]
    if mismatches:
        raise UnsafeCampaignError(
            f"accepted receipt does not match the frozen campaign for {job.identity}: {mismatches}")
    validate_refill_environment(result_root, arguments, job, receipt_path)


def refill_environment_references(result_root, arguments, granted):
    originals = sorted(
        (receipt for receipt in read_accepted_sessions(accepted_sessions_path(result_root, arguments))
         if receipt_logical_identity(receipt) not in granted),
        key=lambda receipt: (receipt["platform"], receipt["shard_id"], int(receipt["replica_id"]),
                             int(receipt["host_epoch"])))
    references = []
    for receipt in originals:
        paths = list((result_root / "jobs" / receipt["platform"] / receipt["shard_id"] /
                      f"replica-{receipt['replica_id']}" / f"epoch-{receipt['host_epoch']}").glob(
            f"*/{receipt['architecture']}/re2-results/environment-manifest.txt"))
        if len(paths) != 1:
            raise UnsafeCampaignError(
                f"accepted receipt {receipt_logical_identity(receipt)} has no single environment manifest")
        references.append(read_properties(paths[0]))
    return references


def refill_field_references(references, environment):
    # Each field maps to the first original sharing its scope, or None when none does.
    return {
        field: next((reference for reference in references
                     if all(reference.get(key) == environment.get(key) for key in scope)), None)
        for field, scope in REFILL_ENVIRONMENT_SCOPES.items()}


def refill_field_differs(field, reference, environment):
    if field != "ami":
        return reference.get(field) != environment.get(field)
    # Regional copies of one image share its name but not its ID. Hosts that predate
    # recorded names can only be compared by ID.
    names = [manifest.get("expected_ami_name", "unverified") for manifest in (reference, environment)]
    if "unverified" not in names:
        return names[0] != names[1]
    return any(reference.get(name) != environment.get(name) for name in ("expected_ami_id", "actual_ami_id"))


def validate_refill_references(result_root, arguments, granted_jobs):
    # A refilled host must match an original sharing its platform, shard, or Trino use,
    # so refuse before launching a job for which no such original exists.
    references = refill_environment_references(
        result_root, arguments, {job.logical_identity for job in granted_jobs})
    missing = {}
    for job in granted_jobs:
        scope = {"platform": job.platform.name, "shard": job.shard,
                 "trino_used": "true" if job.shard in TRINO_SHARDS else "false"}
        fields = [field for field, reference in refill_field_references(references, scope).items()
                  if reference is None]
        if fields:
            missing[job.logical_identity] = fields
    if missing:
        raise UnsafeCampaignError(f"refill has no accepted original host environment to compare: {missing}")


def validate_refill_environment(result_root, arguments, job, receipt_path):
    # A refill may run a newer controller checkout, which chooses the AMI, JDK, packages,
    # and Trino inputs, so a refilled host must match the accepted originals.
    # Ordinary restarts check too, so they never accept a receipt the refill rejected.
    granted = refill_granted_identities(result_root) or set()
    if job.logical_identity not in granted:
        return
    environment = read_properties(receipt_path.parent / "environment-manifest.txt")
    matched = refill_field_references(refill_environment_references(result_root, arguments, granted), environment)
    missing = [field for field, reference in matched.items() if reference is None]
    if missing:
        raise UnsafeCampaignError(
            f"refill host environment for {job.identity} has no accepted original to compare: {missing}")
    mismatches = [field for field, reference in matched.items() if refill_field_differs(field, reference, environment)]
    if mismatches:
        raise UnsafeCampaignError(
            f"refill host environment for {job.identity} differs from the campaign's: {mismatches}")


def recover_accepted_sessions(root, arguments, result_root, jobs, accepted_sessions):
    jobs_by_logical_identity = {job.logical_identity: job for job in jobs}
    accepted = {}
    for receipt in read_accepted_sessions(accepted_sessions):
        logical_identity = receipt_logical_identity(receipt)
        base_job = jobs_by_logical_identity.get(logical_identity)
        if base_job is None:
            raise UnsafeCampaignError(f"accepted ledger contains an unexpected job: {logical_identity}")
        job = replace(base_job, host_epoch=int(receipt["host_epoch"]))
        receipt_path = validate_job_artifacts(result_root, job)
        artifact_receipt = read_receipt(receipt_path)
        if artifact_receipt != receipt:
            raise UnsafeCampaignError(f"accepted ledger differs from artifacts for {job.identity}")
        validate_receipt_candidate(root, arguments, result_root, job, receipt, receipt_path)
        accepted[logical_identity] = receipt

    for base_job in jobs:
        if base_job.logical_identity in accepted:
            continue
        replica_root = (result_root / "jobs" / base_job.platform.name / base_job.shard /
                        f"replica-{base_job.replica}")
        if not replica_root.is_dir():
            continue
        accepted_candidates = []
        for epoch_root in sorted(replica_root.glob("epoch-*")):
            try:
                host_epoch = int(epoch_root.name.removeprefix("epoch-"))
            except ValueError:
                raise UnsafeCampaignError(f"invalid host epoch directory: {epoch_root}")
            job = replace(base_job, host_epoch=host_epoch)
            receipt_name = RECEIPT_NAMES[job.workload]
            receipt_paths = list(epoch_root.glob(f"*/{job.platform.architecture}/re2-results/{receipt_name}"))
            if not receipt_paths:
                continue
            if len(receipt_paths) != 1:
                raise UnsafeCampaignError(f"{job.identity} contains multiple acceptance receipts")
            receipt_path = validate_job_artifacts(result_root, job)
            receipt = read_receipt(receipt_path)
            validate_receipt_candidate(root, arguments, result_root, job, receipt, receipt_path)
            accepted_candidates.append((job, receipt_path, receipt))
        if len(accepted_candidates) > 1:
            epochs = [job.host_epoch for job, _, _ in accepted_candidates]
            raise UnsafeCampaignError(
                f"multiple completed sessions exist for {base_job.logical_identity}: {epochs}")
        if accepted_candidates:
            job, receipt_path, receipt = accepted_candidates[0]
            persist_accepted_receipt(accepted_sessions, receipt_path)
            accepted[base_job.logical_identity] = receipt
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


def job_from_attempt(row, jobs_by_logical_identity):
    logical_identity = attempt_logical_identity(row)
    base_job = jobs_by_logical_identity.get(logical_identity)
    if base_job is None:
        raise UnsafeCampaignError(f"attempt ledger contains an unexpected job: {logical_identity}")
    return replace(
        base_job,
        host_epoch=int(row["host_epoch"]),
        attempt=int(row["attempt"]),
        market=row["market"])


def wrapper_process_id(job, detail):
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
        if "run-campaign.sh" in command and job.identity in command:
            return process_id
        return None
    processes = check_output(["ps", "ax", "-o", "pid=,command="])
    matches = []
    for line in processes.splitlines():
        process_id, separator, command = line.strip().partition(" ")
        if separator and "run-campaign.sh" in command and job.identity in command:
            matches.append(int(process_id))
    if len(matches) > 1:
        raise UnsafeCampaignError(f"multiple detached wrappers match {job.identity}: {matches}")
    return matches[0] if matches else None


def settle_detached_attempts(result_root, jobs, attempts_path):
    attempts = read_attempts(attempts_path)
    latest_by_epoch = {}
    for row in attempts:
        key = (row["platform"], row["shard_id"], row["replica"], row["host_epoch"])
        latest_by_epoch[key] = row
    jobs_by_logical_identity = {job.logical_identity: job for job in jobs}
    detached = [
        (job_from_attempt(row, jobs_by_logical_identity), row)
        for row in latest_by_epoch.values()
        if row["outcome"] in {"starting", "started"}
    ]
    deadline = time.monotonic() + JOB_TIMEOUT_SECONDS + 15 * 60
    while detached:
        remaining = []
        for job, row in detached:
            try:
                session = single_session(result_root, job)
            except (OSError, RuntimeError):
                session = None
            if session is not None and (session / "cleanup-manifest.txt").is_file():
                continue
            process_id = wrapper_process_id(job, row["detail"])
            if process_id is None:
                time.sleep(2)
                try:
                    session = single_session(result_root, job)
                except (OSError, RuntimeError):
                    session = None
                if session is None and row["outcome"] == "starting":
                    write_attempt(
                        attempts_path,
                        job,
                        "retryable-infrastructure",
                        WRAPPER_NOT_CREATED)
                    continue
                if session is None or not (session / "cleanup-manifest.txt").is_file():
                    raise UnsafeCampaignError(
                        f"detached wrapper for {job.identity} exited without cleanup evidence")
                continue
            remaining.append((job, row))
        if not remaining:
            return
        if time.monotonic() >= deadline:
            identities = [job.identity for job, _ in remaining]
            raise UnsafeCampaignError(f"detached wrappers exceeded their completion deadline: {identities}")
        detached = remaining
        time.sleep(15)


def prepare_resumed_jobs(result_root, jobs, attempts_path, accepted, *, spot_only=False, job_regions=None, refill=None,
                         superseded=None, settled=True):
    attempts = read_attempts(attempts_path)
    attempts_by_epoch = {
        (row["platform"], row["shard_id"], int(row["replica"]), int(row["host_epoch"])): row
        for row in attempts
    }
    starts_by_epoch = {
        (row["platform"], row["shard_id"], int(row["replica"]), int(row["host_epoch"])): row
        for row in attempts if row["outcome"] == "starting"
    }
    attempts_by_logical_identity = {}
    for row in attempts:
        attempts_by_logical_identity[attempt_logical_identity(row)] = row
    all_epoch_roots = list((result_root / "jobs").glob("*/*/replica-*/epoch-*"))
    directory_epochs = []
    for epoch_root in all_epoch_roots:
        try:
            directory_epochs.append(int(epoch_root.name.removeprefix("epoch-")))
        except ValueError as error:
            raise UnsafeCampaignError(f"invalid host epoch directory: {epoch_root}") from error
    maximum_epoch = max(
        [job.host_epoch for job in jobs] +
        [int(row["host_epoch"]) for row in attempts] +
        directory_epochs)
    pending = []
    unresolved = []

    def replace_or_resolve(previous_job, classification, detail, outcome):
        nonlocal maximum_epoch
        later_epoch = superseded(previous_job) if superseded else None
        if later_epoch:
            unresolved.append((previous_job, f"later attempt at epoch {later_epoch} failed before its host started"))
            return
        try:
            replacement = replacement_job(
                previous_job, maximum_epoch + 1, classification, detail,
                regions=job_regions(previous_job) if job_regions else (), spot_only=spot_only)
            ready_at = CAPACITY_RETRY_SECONDS if classification == "retryable-capacity" else 0
        except RuntimeError as error:
            if refill is None or not refill(previous_job, outcome):
                unresolved.append((previous_job, str(error)))
                return
            # A granted refill is one On-Demand attempt beyond the exhausted allowance.
            replacement = replace(
                previous_job, host_epoch=maximum_epoch + 1, attempt=previous_job.attempt + 1, market="on-demand")
            ready_at = 0
        maximum_epoch += 1
        pending.append((replacement, ready_at))

    for base_job in jobs:
        if base_job.logical_identity in accepted:
            continue
        replica_root = (result_root / "jobs" / base_job.platform.name / base_job.shard /
                        f"replica-{base_job.replica}")
        epoch_roots = list(replica_root.glob("epoch-*")) if replica_root.is_dir() else []
        if not epoch_roots:
            attempt_row = attempts_by_logical_identity.get(base_job.logical_identity)
            if attempt_row is None:
                pending.append((base_job, 0))
                continue
            previous_job = job_from_attempt(
                attempt_row,
                {base_job.logical_identity: base_job})
            classification = attempt_row["outcome"]
            if classification == "queued-capacity" and not any(
                    int(row["host_epoch"]) == previous_job.host_epoch and row["outcome"] in {"starting", "started"}
                    for row in attempts):
                pending.append((previous_job, 0))
                continue
            if classification not in {
                    "retryable-spot", "retryable-capacity", "retryable-infrastructure"}:
                unresolved.append((
                    previous_job,
                    f"attempt without artifacts cannot be resumed: outcome={classification}"))
                continue
            replace_or_resolve(previous_job, classification, attempt_row["detail"], classification)
            continue
        epochs = []
        for epoch_root in epoch_roots:
            try:
                epochs.append(int(epoch_root.name.removeprefix("epoch-")))
            except ValueError as error:
                raise UnsafeCampaignError(f"invalid host epoch directory: {epoch_root}") from error
        maximum_epoch = max(maximum_epoch, *epochs)
        latest_epoch = max(epochs)
        attempt_row = attempts_by_epoch.get((
            base_job.platform.name, base_job.shard, base_job.replica, latest_epoch))
        if attempt_row is None:
            raise UnsafeCampaignError(
                f"{base_job.logical_identity} has artifacts for epoch {latest_epoch} without an attempt record")
        # The attempt's launch record names its region and the regions its chain already moved past.
        start = starts_by_epoch.get((base_job.platform.name, base_job.shard, base_job.replica, latest_epoch))
        launch = launch_record(start) if start else {}
        previous_job = replace(
            base_job,
            host_epoch=latest_epoch,
            attempt=int(attempt_row["attempt"]),
            market=attempt_row["market"],
            region=launch.get("region", ""),
            spot_capacity_failures=tuple(filter(None, launch.get("spot_capacity_failures", "").split(","))))
        try:
            session, cleanup = validate_cleanup_manifest(result_root, previous_job)
        except (OSError, RuntimeError) as error:
            if not settled:
                # Execution waits for a detached attempt before it plans.
                unresolved.append((previous_job, f"attempt has not settled: {error}"))
                continue
            raise UnsafeCampaignError(
                f"cannot safely resume {previous_job.identity}: {error}") from error
        outcome = cleanup.get("failure_classification", "")
        detail = cleanup.get("failure_detail", "unknown")
        classification = "retryable-infrastructure" if outcome == "controller-abort" else outcome
        if classification in {"retryable-spot", "retryable-capacity", "retryable-infrastructure"}:
            replace_or_resolve(previous_job, classification, detail, outcome)
            continue
        unresolved.append((
            previous_job,
            f"previous attempt was not accepted: classification={classification or 'none'}; detail={detail}"))
    return pending, unresolved, maximum_epoch + 1


def read_refill_grants(result_root):
    path = result_root / "refill-jobs.tsv"
    if not path.exists():
        return None
    with path.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != JOB_MANIFEST_FIELDS:
            raise UnsafeCampaignError(f"unexpected refill grant schema in {path}")
        return list(reader)


def refill_granted_identities(result_root):
    # None until a refill records its grants.
    rows = read_refill_grants(result_root)
    if rows is None:
        return None
    return {attempt_logical_identity(row) for row in rows}


def refill_granted_jobs(result_root, jobs):
    granted = refill_granted_identities(result_root)
    if granted is None:
        return None
    return [job for job in jobs if job.logical_identity in granted]


def latest_launched_epochs(result_root, attempts_path):
    final_rows = {}
    for row in read_attempts(attempts_path):
        final_rows[(attempt_logical_identity(row), int(row["host_epoch"]))] = row
    latest = {}
    for (identity, epoch), row in final_rows.items():
        # A capacity wait, or a controller stopped before it started the wrapper, tried nothing.
        if row["outcome"] != "queued-capacity" and row["detail"] != WRAPPER_NOT_CREATED:
            latest[identity] = max(latest.get(identity, 0), epoch)
    for epoch_root in (result_root / "jobs").glob("*/*/replica-*/epoch-*"):
        epoch = epoch_root.name.removeprefix("epoch-")
        if epoch.isdigit():
            identity = "/".join(epoch_root.parts[-4:-1])
            latest[identity] = max(latest.get(identity, 0), int(epoch))
    return latest


def prepare_refill(result_root, jobs, attempts_path, accepted, *, record=True, settled=True):
    # The first refill records the exhausted attempts it replaces, and restarts grant only
    # those. A grant passes only to a job's latest launched attempt, so a refill attempt that
    # failed, even before its host started, stays unresolved. An operator interrupt never
    # consumes a grant: an aborted refill attempt passes it to one new attempt, recorded the same way.
    rows = read_refill_grants(result_root)
    recorded = None if rows is None else {
        f"{attempt_logical_identity(row)}/epoch-{row['host_epoch']}" for row in rows}
    recorded_jobs = {identity.rsplit("/", 1)[0] for identity in recorded or ()}
    latest = latest_launched_epochs(result_root, attempts_path)
    granted = []

    def superseded(job):
        # Artifacts mark a granted job's last attempt only if no later one failed before creating any.
        if job.logical_identity in recorded_jobs and latest.get(job.logical_identity, 0) > job.host_epoch:
            return latest[job.logical_identity]
        return None

    def grant(job, outcome):
        if latest.get(job.logical_identity, job.host_epoch) > job.host_epoch:
            return False
        if recorded is not None and job.identity not in recorded and (
                outcome != "controller-abort" or job.logical_identity not in recorded_jobs):
            return False
        if recorded is None or job.identity not in recorded:
            granted.append(job)
        return True

    pending, unresolved, next_host_epoch = prepare_resumed_jobs(
        result_root, jobs, attempts_path, accepted, refill=grant, superseded=superseded, settled=settled)
    if recorded is None and (not granted or len(pending) != len(granted)):
        raise SystemExit("refill requires a campaign whose only remaining retryable jobs "
                         "exhausted their replacements; resume it normally first")
    if record and granted:
        jobs_by_logical_identity = {job.logical_identity: job for job in jobs}
        previous = [job_from_attempt(row, jobs_by_logical_identity) for row in rows or ()]
        write_jobs(result_root / "refill-jobs.tsv", previous + granted, extend=True)
    return pending, unresolved, next_host_epoch


def refill_launch_jobs(arguments, result_root, jobs):
    # The jobs that size a refill's deadline. Recorded grants fix it across restarts. Before
    # the first refill records them, plan without waiting for detached attempts; execution
    # settles them and records the grants under the controller lock.
    if not (result_root / "execution-policy.json").exists():
        raise SystemExit("refill requires an existing campaign result root")
    granted = refill_granted_jobs(result_root, jobs)
    if granted is not None:
        return granted
    accepted = {receipt_logical_identity(receipt): receipt for receipt in
                read_accepted_sessions(accepted_sessions_path(result_root, arguments))}
    pending, _, _ = prepare_refill(
        result_root, jobs, result_root / "job-attempts.tsv", accepted, record=False, settled=False)
    return [job for job, _ in pending]


def write_unresolved_jobs(path, unresolved):
    write_unresolved(path, ("platform", "shard_id", "replica", "host_epoch", "attempt", "market", "detail"), [
        (job.platform.name, job.shard, job.replica, job.host_epoch, job.attempt, job.market, detail)
        for job, detail in unresolved])


def campaign_started_at(path, arguments):
    fields = ("schema_version", "campaign_id", "phase", "started_at_epoch_seconds", "heap_size")
    if path.exists():
        with path.open(newline="") as input_file:
            reader = csv.DictReader(input_file, delimiter="\t")
            if tuple(reader.fieldnames or ()) != fields:
                raise UnsafeCampaignError(f"unexpected campaign-state schema in {path}")
            rows = list(reader)
        if len(rows) != 1:
            raise UnsafeCampaignError(f"campaign state must contain exactly one row: {path}")
        row = rows[0]
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
        writer = csv.DictWriter(output_file, fieldnames=fields, delimiter="\t", lineterminator="\n")
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


def validate_language_state(root, arguments, result_root):
    language = language_campaign(arguments)
    if not language:
        return
    expected = {
        "schema_version": 1, "campaign_id": arguments.campaign_id, "phase": arguments.phase,
        "plans": language.fingerprint(), "max_concurrent": arguments.max_concurrent,
        "max_concurrent_per_platform": getattr(arguments, 'max_concurrent_per_platform', DEFAULT_MAX_CONCURRENT),
        "phase_timeout_seconds": phase_timeout(arguments), "job_timeout_seconds": JOB_TIMEOUT_SECONDS,
        "heap_size": campaign_heap_size(arguments),
        "candidate_commit": check_output(["git", "rev-parse", candidate_revision(arguments)], cwd=root),
        "candidate_archive_sha256": arguments.candidate_archive_sha256,
        "candidate_provenance_sha256": hashlib.sha256(arguments.candidate_provenance.read_bytes()).hexdigest(),
        "batches": {identity: hashlib.sha256(language_collection.encode(
            language.expected_package(identity))).hexdigest() for identity in language.batches},
    }
    # Canonicalize tuples before comparing to a JSON reload on restart.
    expected = json.loads(json.dumps(expected))
    path = result_root / "language-state.json"
    refill = getattr(arguments, "refill_unresolved", False)
    if refill and not path.exists():
        raise UnsafeCampaignError("refill requires the campaign's recorded language state")
    if path.exists():
        recorded = json.loads(path.read_text())
        if refill:
            # The refill execution policy records the refill's own concurrency and deadline.
            for key in ("max_concurrent", "max_concurrent_per_platform", "phase_timeout_seconds"):
                recorded.pop(key, None)
                expected.pop(key, None)
        if recorded != expected:
            raise UnsafeCampaignError("language candidate, plans, batches, or deadlines changed on restart")
        return
    temporary = path.with_name(path.name + ".new")
    with temporary.open("w") as file:
        json.dump(expected, file, sort_keys=True)
        file.write("\n")
        file.flush()
        os.fsync(file.fileno())
    os.replace(temporary, path)
    fsync_directory(path.parent)


def validate_language_coverage(arguments, jobs, accepted):
    if set(accepted) != {job.logical_identity for job in jobs}:
        raise UnsafeCampaignError("language campaign has missing or unexpected accepted batches")
    instances = [receipt["instance_id"] for receipt in accepted.values()]
    if any(not instance for instance in instances) or len(instances) != len(set(instances)):
        raise UnsafeCampaignError("language batches must use independent hosts")
    for identity, receipt in accepted.items():
        language_campaign(arguments).validate_receipt(identity, receipt)


def aggregate_language_results(arguments, result_root, jobs, accepted):
    validate_language_coverage(arguments, jobs, accepted)
    if arguments.phase == "smoke":
        return
    # Link accepted raw evidence only. Rejected attempts never enter the reducer.
    with tempfile.TemporaryDirectory(prefix="language-aggregate-", dir=result_root) as temporary:
        staging = Path(temporary)
        by_identity = {job.logical_identity: job for job in jobs}
        for identity, receipt in accepted.items():
            job = replace(by_identity[identity], host_epoch=int(receipt["host_epoch"]))
            artifact = validate_job_artifacts(result_root, job).parent
            suite, batch = language_campaign(arguments).batches[identity]
            for logical_job in batch["jobs"]:
                link = staging / suite / logical_job
                link.parent.mkdir(parents=True, exist_ok=True)
                link.symlink_to((artifact / "language-worker/results" / logical_job).resolve(), target_is_directory=True)
        for suite, (directory, _) in language_campaign(arguments).plans.items():
            temporary_export = staging / (suite + ".json")
            language_fleet.aggregate(directory, staging / suite, temporary_export)
            output = result_root / (suite + "-results.json")
            if output.exists():
                if output.read_bytes() != temporary_export.read_bytes():
                    raise UnsafeCampaignError("language aggregate changed on restart")
            else:
                os.replace(temporary_export, output)
        fsync_directory(result_root)


def admit_launch(arguments, result_root, attempts_path, capacity_waits, candidate, ready_at, now, running_jobs):
    """Place a launch candidate and reserve its capacity.

    Returns the candidate as placed, when it may next be tried, whether it was admitted, whether
    the launch scan may continue to later candidates, and the vCPU count a full fleet quota now
    blocks, if any.
    """
    budget = getattr(arguments, "shared_budget", None)
    spot_only = getattr(arguments, "spot_only", False)
    placements = launch_placements(arguments, candidate)
    candidate = place(candidate, placements[0])
    # An unbudgeted Spot launch queries quota headroom only when Spot-only or above the proactive
    # threshold; otherwise it takes the first placement and learns of a full region from EC2.
    if candidate.market == "spot" and (
            spot_only or arguments.max_concurrent > PROACTIVE_SPOT_CAPACITY_THRESHOLD) and budget is None:
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
        elif spot_only:
            wait_key = (candidate.identity, "spot-capacity")
            if wait_key not in capacity_waits:
                capacity_waits.add(wait_key)
                write_attempt(attempts_path, candidate, "queued-capacity",
                              "Spot-only policy waiting for quota; " + capacity_detail(spot_capacities))
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
                    shared_fleet_snapshot(arguments, region, market))
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
            (f";slot={slot};slot_failures={slot_failures}" if slot is not None else ""))
        try:
            process = subprocess.Popen(
                [str(runner), job.identity],
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
    """Stop a controller that failed with error: halt the fleet budget, and terminate the running hosts,
    as process ID to (process, job, log), and prove their cleanup. The caller then re-raises error."""
    if budget:
        # Stop the other collector's admissions and ask it to drain too.
        # Cleanup must still run if persisting the stop marker fails.
        try:
            budget.halt(str(error) or type(error).__name__)
        except (OSError, RuntimeError):
            pass
    try:
        terminate_and_validate_cleanup(running, result_root)
        if budget:
            for _, job, _ in running.values():
                budget.release(arguments.campaign_id, job.host_epoch, cleanup_verified=True)
    except RuntimeError as cleanup_error:
        reason = str(error) or type(error).__name__
        raise UnsafeCampaignError(f"{reason}; {cleanup_error}") from error


@contextlib.contextmanager
def controller_lock(result_root):
    result_root.mkdir(parents=True, exist_ok=True)
    lock_path = result_root / "controller.lock"
    with lock_path.open("w") as lock_file:
        try:
            fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise UnsafeCampaignError(
                f"another controller owns campaign result root {result_root}") from error
        yield


def execute_jobs(arguments, root, result_root, jobs):
    with controller_lock(result_root):
        return execute_jobs_locked(arguments, root, result_root, jobs)


def execute_jobs_locked(arguments, root, result_root, jobs):
    refill = getattr(arguments, "refill_unresolved", False)
    attempts_path = result_root / "job-attempts.tsv"
    accepted_sessions = accepted_sessions_path(result_root, arguments)
    if refill:
        # Settled attempts and recovered receipts fix the grants, which size the refill's
        # deadline before its policy is recorded.
        settle_detached_attempts(result_root, jobs, attempts_path)
        accepted = recover_accepted_sessions(root, arguments, result_root, jobs, accepted_sessions)
        pending, unresolved, next_host_epoch = prepare_refill(result_root, jobs, attempts_path, accepted)
        granted_jobs = refill_granted_jobs(result_root, jobs)
        validate_refill_references(result_root, arguments, granted_jobs)
        validate_configuration(arguments, jobs, granted_jobs)
    validate_execution_policy(arguments, result_root)
    runner = root / "tools/re2-benchmark/aws/run-campaign.sh"
    jobs_path = result_root / "jobs.tsv"
    expected_fingerprint = frozen_input_fingerprint(root, arguments, jobs_path)
    running = {}
    events_path = result_root / "campaign-events.tsv"
    validate_language_state(root, arguments, result_root)
    # A refill's deadline runs from its own start, not the original campaign's.
    started_at = campaign_started_at(result_root / ("refill-state.tsv" if refill else "campaign-state.tsv"), arguments)
    budget = getattr(arguments, "shared_budget", None)
    if not refill:
        settle_detached_attempts(result_root, jobs, attempts_path)
        if budget:
            recover_budget_reservations(budget, arguments, result_root, jobs, attempts_path)
        accepted = recover_accepted_sessions(
            root, arguments, result_root, jobs, accepted_sessions)
        pending, unresolved, next_host_epoch = prepare_resumed_jobs(
            result_root, jobs, attempts_path, accepted, spot_only=getattr(arguments, "spot_only", False),
            job_regions=lambda job: launch_regions(arguments, job))
    pending = [(job, time.monotonic() + delay) for job, delay in pending]
    tail_jobs = math.ceil(len(jobs) * ON_DEMAND_TAIL_PERCENT / 100)
    capacity_waits = set()
    elapsed_seconds = max(0, time.time() - started_at)
    remaining_seconds = max(0, phase_timeout(arguments) - elapsed_seconds)
    deadline = time.monotonic() + remaining_seconds
    deadline_reached = False
    try:
        while pending or running:
            if budget:
                budget.check()
            now = time.monotonic()
            if now >= deadline and not deadline_reached:
                deadline_reached = True
                for job, _ in pending:
                    unresolved.append((job, f"{arguments.phase} campaign reached its wall-clock launch limit"))
                pending.clear()

            launched = False
            while pending and len(running) < arguments.max_concurrent:
                selected_index = None
                blocked_vcpus = None
                for index, (candidate, ready_at) in enumerate(pending):
                    if ready_at > now:
                        continue
                    if not platform_has_capacity(candidate, running, arguments):
                        continue
                    if budget and blocked_vcpus is not None and candidate.platform.vcpus >= blocked_vcpus:
                        continue
                    if (candidate.market == "spot" and not getattr(arguments, "spot_only", False) and
                            len(pending) + len(running) <= tail_jobs):
                        candidate = replace(candidate, market="on-demand")
                    candidate, ready_at, admitted, scan, blocked = admit_launch(
                        arguments, result_root, attempts_path, capacity_waits, candidate, ready_at, now,
                        [record[1] for record in running.values()])
                    pending[index] = (candidate, ready_at)
                    if admitted:
                        selected_index = index
                        break
                    blocked_vcpus = blocked or blocked_vcpus
                    if not scan:
                        break
                if selected_index is None:
                    break
                job, _ = pending.pop(selected_index)
                validate_frozen_inputs(root, arguments, jobs_path, expected_fingerprint)
                try:
                    process, log = start_wrapper(arguments, root, result_root, runner, attempts_path, job)
                except WrapperStartError as error:
                    if budget:
                        budget.release(arguments.campaign_id, job.host_epoch, cleanup_verified=True)
                    detail = f"controller could not start wrapper: {error}"
                    write_attempt(attempts_path, job, "retryable-infrastructure", detail)
                    try:
                        replacement = replacement_job(
                            job, next_host_epoch, "retryable-infrastructure", detail,
                            regions=launch_regions(arguments, job),
                            spot_only=getattr(arguments, "spot_only", False))
                    except RuntimeError as replacement_error:
                        unresolved.append((job, str(replacement_error)))
                    else:
                        next_host_epoch += 1
                        pending.append((replacement, now + CAPACITY_RETRY_SECONDS))
                    continue
                running[process.pid] = (process, job, log)
                write_attempt(attempts_path, job, "started", f"wrapper_pid={process.pid}")
                launched = True
                now = time.monotonic()
            completed = []
            for process_id, (process, job, log) in list(running.items()):
                status = process.poll()
                if status is None:
                    continue
                validate_frozen_inputs(root, arguments, jobs_path, expected_fingerprint)
                log.close()
                completed.append(process_id)
                if status != 0:
                    try:
                        failed_session, cleanup = validate_failed_job(result_root, job)
                    except (OSError, RuntimeError) as error:
                        raise UnsafeCampaignError(
                            f"cleanup could not be proven for failed job {job.identity}: {error}") from error
                    classification = cleanup.get("failure_classification", "")
                    detail = cleanup.get("failure_detail", "unknown")
                    if classification in {"retryable-spot", "retryable-capacity", "retryable-infrastructure"}:
                        try:
                            replacement = replacement_job(
                                job, next_host_epoch, classification, detail,
                                regions=launch_regions(arguments, job),
                                spot_only=getattr(arguments, "spot_only", False))
                        except RuntimeError as error:
                            failure_detail = f"status={status}; {error}; detail={detail}"
                            unresolved.append((job, failure_detail))
                            write_attempt(attempts_path, job, "unresolved", failure_detail)
                            write_event(
                                events_path, "rejected-session", arguments.campaign_id, job,
                                failed_session, "replacement-exhausted", failure_detail)
                        else:
                            next_host_epoch += 1
                            delay = CAPACITY_RETRY_SECONDS if classification == "retryable-capacity" else 0
                            pending.append((replacement, time.monotonic() + delay))
                            write_attempt(attempts_path, job, classification, detail)
                            write_event(
                                events_path, "interruption", arguments.campaign_id, job,
                                failed_session, detail,
                                f"replacement_epoch={replacement.host_epoch};replacement_market={replacement.market}")
                    else:
                        failure_detail = f"status={status}; classification={classification or 'none'}; detail={detail}"
                        write_attempt(attempts_path, job, "rejected", failure_detail)
                        write_event(
                            events_path, "rejected-session", arguments.campaign_id, job,
                            failed_session, "job-failure", failure_detail)
                        unresolved.append((job, failure_detail))
                        # Cleanup is proven. Preserve the rejection and let independent jobs finish.
                else:
                    try:
                        receipt_path = validate_job_artifacts(result_root, job)
                    except (OSError, RuntimeError) as error:
                        failure_detail = f"artifact gate: {error}"
                        try:
                            validate_cleanup_manifest(result_root, job)
                        except (OSError, RuntimeError) as cleanup_error:
                            raise UnsafeCampaignError(
                                f"{failure_detail}; cleanup gate: {cleanup_error}") from cleanup_error
                        write_attempt(attempts_path, job, "rejected", failure_detail)
                        try:
                            failed_session = single_session(result_root, job)
                            write_event(
                                events_path, "rejected-session", arguments.campaign_id, job,
                                failed_session, "artifact-gate", failure_detail)
                        except (OSError, RuntimeError):
                            pass
                        unresolved.append((job, failure_detail))
                        # Cleanup is proven. Preserve the rejection and let independent jobs finish.
                    else:
                        receipt = read_receipt(receipt_path)
                        validate_receipt_candidate(root, arguments, result_root, job, receipt, receipt_path)
                        persist_accepted_receipt(accepted_sessions, receipt_path)
                        write_attempt(attempts_path, job, "accepted")
                        accepted[job.logical_identity] = receipt
            for process_id in completed:
                if budget:
                    budget.release(arguments.campaign_id, running[process_id][1].host_epoch, cleanup_verified=True)
                del running[process_id]
            if running or (pending and not launched):
                time.sleep(1 if budget else 5)
    except (Exception, KeyboardInterrupt) as error:
        halt_and_clean_up(arguments, result_root, budget, running, error)
        raise
    write_unresolved_jobs(result_root / "unresolved-jobs.tsv", unresolved)
    if unresolved:
        details = ", ".join(f"{job.identity} {detail}" for job, detail in unresolved)
        raise SystemExit(f"campaign completed with unresolved jobs: {details}")
    if language_campaign(arguments):
        aggregate_language_results(arguments, result_root, jobs, accepted)


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
        job = unit_worker_job(platform, int(row["replica"]), int(row["host_epoch"]), row["market"])
        if job.platform.instance_type != row["instance_type"]:
            raise UnsafeCampaignError(f"unit worker manifest names an unexpected instance type: {row}")
        jobs.append(job)
    return jobs


def holds_marker(path, worker, sequence):
    """Whether path already holds the worker's completion marker for the lease, as completing that lease fetched it."""
    try:
        marker = json.loads(path.read_text())
    except (OSError, ValueError):
        return False
    return marker.get("host_session") == worker.host_session and marker.get("sequence") == sequence


class DetachedWrapper:
    """A worker wrapper an earlier controller started, followed through its process ID and session."""

    def __init__(self, process_id, job, detail, result_root):
        self.pid = process_id
        self.job = job
        self.detail = detail
        self.result_root = result_root

    def poll(self):
        if wrapper_process_id(self.job, self.detail) is not None:
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
    # Each lease's work item identity, or None for a stop lease.
    leases: dict = field(default_factory=dict)
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

    Workers are host jobs in unit-worker mode, launched, budgeted and cleaned up as other hosts
    are. unit-workers.tsv freezes each worker before its launch, and unit-attempts.tsv records
    each lease before it is written and each outcome it reaches, so a restart rebuilds this state
    from the ledgers, the live workers' uploads, and the uploads recovered while cleaning up exited workers.
    """

    def __init__(self, arguments, root, result_root, items, platforms):
        self.arguments = arguments
        self.root = root
        self.result_root = result_root
        self.items = {item.identity: item for item in items}
        self.pools = sorted({item.pool for item in items})
        self.platforms = {platform.name: platform for platform in platforms}
        self.budget = getattr(arguments, "shared_budget", None)
        self.spot_only = getattr(arguments, "spot_only", False)
        self.attempts_path = result_root / "job-attempts.tsv"
        self.leases_path = result_root / "unit-attempts.tsv"
        self.workers_path = result_root / "unit-workers.tsv"
        self.events_path = result_root / "campaign-events.tsv"
        self.accepted_path = result_root / "accepted-units.tsv"
        self.rejections_path = result_root / unit_session.SEMANTIC_REJECTIONS
        self.jobs_path = result_root / "work-items.tsv"
        self.runner = root / "tools/re2-benchmark/aws/run-campaign.sh"
        # The frozen inputs' fingerprint, taken when the phase runs; each launch checks it.
        self.fingerprint = None
        self.accepted = {}
        # Each leased item's live lease, as its worker's host epoch and lease sequence.
        self.held = {}
        self.failures = {}
        self.on_demand_only = set()
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
        self.terminated = set()

    def queued(self, pool=None):
        return [item for identity, item in self.items.items()
                if identity not in self.accepted and identity not in self.unresolved and identity not in self.held
                and (pool is None or item.pool == pool)]

    def on_demand(self, item):
        return not self.spot_only and (item.identity in self.on_demand_only or item.pool in self.tail)

    def pool_workers(self, pool):
        return [worker for worker in self.workers.values() if worker.pool == pool]

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
                          placement_score=placement.get("placement_score", ""))
            process_id = None
            for probe in range(2):
                try:
                    session = single_session(self.result_root, job)
                except (OSError, RuntimeError):
                    session = None
                if session is not None and (session / "cleanup-manifest.txt").is_file():
                    break
                process_id = wrapper_process_id(job, row["detail"])
                if process_id is not None:
                    break
                if probe == 0:
                    time.sleep(2)
            if process_id is not None:
                log = (self.result_root / "controller-logs" / (job.identity.replace("/", "-") + ".log")).open("a")
                # A live worker is registered at once, so a recovery step that fails still stops it.
                workers[job.host_epoch] = self.workers[job.host_epoch] = UnitWorker(
                    job, DetachedWrapper(process_id, job, row["detail"], self.result_root), log,
                    slot=slot, slot_failures=slot_failures)
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
        self.accepted = recover_accepted_units(self.root, self.arguments, self.result_root, self.accepted_path)
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
            if row["outcome"] == "interrupted" and not self.spot_only:
                self.on_demand_only.add(identity)
            worker = workers.get(epoch)
            if worker is None:
                continue
            self.worker_session(worker)
            if row["outcome"] in ("leased", "stop"):
                worker.leases[sequence] = identity
                worker.unsent.add(sequence)
                if identity is not None:
                    worker.units.add(self.items[identity].unit_id)
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
        if self.failures[item.identity] >= MAX_UNIT_ATTEMPTS and item.identity not in self.accepted:
            self.unresolved[item.identity] = f"{detail}; spent its {MAX_UNIT_ATTEMPTS} attempts"

    def settle(self, worker, sequence, outcome, detail="-"):
        identity = worker.leases[sequence]
        write_unit_attempt(self.leases_path, worker, sequence, self.items[identity], outcome, detail)
        worker.settled.add(sequence)
        if self.held.get(identity) == (worker.job.host_epoch, sequence):
            del self.held[identity]

    def abandon(self, worker, sequence, outcome, detail):
        """Requeue a lease whose host was lost or exited; a completion that still lands is processed."""
        identity = worker.leases[sequence]
        write_unit_attempt(self.leases_path, worker, sequence, self.items[identity], outcome, detail)
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
        """Leave the item unresolved without retrying it, as a rejected host job is, and let other work go on."""
        item = self.items[worker.leases[sequence]]
        self.settle(worker, sequence, "rejected", detail)
        if item.identity not in self.accepted:
            self.unresolved[item.identity] = detail
        write_event(self.events_path, "rejected-session", self.arguments.campaign_id, self.event_job(worker, item),
                    worker.session, "unit-rejected", detail, **self.event_unit(worker, sequence))

    def interrupt(self, worker, sequence):
        item = self.items[worker.leases[sequence]]
        self.settle(worker, sequence, "interrupted", "spot-interruption-notice")
        worker.stopping = True
        if not self.spot_only:
            self.on_demand_only.add(item.identity)
        self.count(item, "Spot interruption", (worker.job.host_epoch, sequence))
        write_event(self.events_path, "interruption", self.arguments.campaign_id, self.event_job(worker, item),
                    worker.session, "spot-interruption-notice",
                    f"replacement_market={'spot' if self.spot_only else 'on-demand'}",
                    **self.event_unit(worker, sequence))

    def complete(self, worker, sequence, keys, fetch):
        """Accept, supersede, retry or reject one lease from its completion marker and result."""
        item = self.items[worker.leases[sequence]]
        attempt = unit_session.format_unit_attempt(worker.host_session, sequence)
        staging = self.result_root / "unit-results" / worker.host_session
        marker_path = staging / f"lease-{sequence}-completion.json"
        fetch(completion_key(sequence), marker_path)
        try:
            marker = json.loads(marker_path.read_text())
        except ValueError as error:
            raise UnsafeCampaignError(f"unit completion marker {attempt} is not JSON") from error
        absent = [key for key in UNIT_COMPLETION_FIELDS if key not in marker]
        if absent:
            raise UnsafeCampaignError(f"unit completion marker {attempt} lacks {absent}")
        expected = {
            "schema_version": 1, "host_session": worker.host_session, "sequence": sequence,
            "campaign_id": self.arguments.campaign_id, "phase": self.arguments.phase, "platform": item.platform,
            "unit_id": item.unit_id, "replica_id": item.replica, "unit_attempt_id": attempt,
        }
        mismatches = [key for key, value in expected.items() if marker.get(key) != value]
        if mismatches:
            raise UnsafeCampaignError(f"unit completion marker {attempt} does not match its lease: {mismatches}")
        if marker.get("exit_status") == SEMANTIC_FAILURE_STATUS:
            # A verdict that the semantics are wrong rejects the unit for good, before or after another attempt.
            # Its ledger row is durable before the lease's outcome is.
            persist_semantic_rejection(self.rejections_path, item, attempt)
            self.semantic.setdefault(item.identity, attempt)
            return self.reject(worker, sequence, SEMANTIC_FAILURE_DETAIL)
        # A unit already settled needs none of this lease's evidence.
        accepted = self.accepted.get(item.identity)
        if accepted is not None:
            if accepted["unit_attempt_id"] == attempt:
                # The controller stopped after accepting this attempt, before recording its outcome.
                self.settle(worker, sequence, "accepted")
            else:
                self.settle(worker, sequence, "superseded", f"{accepted['unit_attempt_id']} was accepted first")
            return
        if item.identity in self.semantic:
            return self.settle(worker, sequence, "superseded",
                               f"{self.semantic[item.identity]} was rejected for a semantic failure")
        calibrations = [f"{digest}.json" for digest in (marker.get("calibration_before"), marker.get("calibration_after"))
                        if digest]
        if any(re.fullmatch(r"[0-9a-f]{64}\.json", name) is None for name in calibrations):
            raise UnsafeCampaignError(f"unit completion marker {attempt} names an invalid calibration")
        # The worker uploads a lease's calibrations before its completion marker.
        missing = [name for name in calibrations if calibration_key(name) not in keys]
        if missing:
            return self.reject(worker, sequence, f"named host calibration is missing: {', '.join(missing)}")
        archive = staging / f"lease-{sequence}.tar.gz"
        fetch(result_key(sequence), archive)
        for name in calibrations:
            path = staging / "calibrations" / name
            # A chained lease's before calibration is the after of the lease before it, already fetched.
            if not path.is_file() or f"{engine_build.sha256_file(path)}.json" != name:
                fetch(calibration_key(name), path)
        if engine_build.sha256_file(archive) != marker.get("result_sha256"):
            return self.reject(worker, sequence, "result archive does not match its completion marker")
        directory = staging / f"lease-{sequence}"
        try:
            extract_unit_result(archive, directory)
        except (OSError, tarfile.TarError) as error:
            return self.reject(worker, sequence, f"result archive is unreadable: {error}")
        predecessor = None
        if (directory / "unit-chain.txt").is_file():
            # A chained before must be the after calibration of the lease before it, by that lease's own marker.
            predecessor = f"lease-{sequence - 1}-completion.json"
            if completion_key(sequence - 1) in keys and not holds_marker(staging / predecessor, worker, sequence - 1):
                fetch(completion_key(sequence - 1), staging / predecessor)
        if marker.get("unit_status") != "complete" or marker.get("exit_status") != 0:
            if marker.get("exit_status") == PROTOCOL_REJECTION_STATUS:
                return self.reject(worker, sequence, f"numerical protocol rejection: status {PROTOCOL_REJECTION_STATUS}")
            try:
                capacity = read_properties(directory / "unit-capacity.txt")
            except (OSError, RuntimeError):
                capacity = {}
            if capacity.get("capacity_status") == "rejected":
                return self.reject(worker, sequence, "unit capacity gate rejected the host's resources")
            detail = f"unit failed with status {marker.get('exit_status')}"
            self.settle(worker, sequence, "failed", detail)
            return self.count(item, detail, (worker.job.host_epoch, sequence))
        try:
            receipt = read_unit_receipt(directory / "unit-receipt.tsv")
            if unit_logical_identity(receipt) != item.identity or receipt["unit_attempt_id"] != attempt:
                raise RuntimeError(f"unit receipt names {unit_logical_identity(receipt)} attempt "
                                   f"{receipt['unit_attempt_id']}, expected {item.identity} attempt {attempt}")
            validate_unit_artifacts(self.root, self.arguments, self.result_root, receipt, directory)
        except (OSError, RuntimeError) as error:
            return self.reject(worker, sequence, f"artifact gate: {error}")
        final = unit_attempt_directory(self.result_root, receipt)
        retain_unit_attempt(directory, final, calibrations, predecessor)
        self.accepted[item.identity] = persist_accepted_unit(self.accepted_path, final / "unit-receipt.tsv")
        self.unresolved.pop(item.identity, None)
        self.settle(worker, sequence, "accepted")

    def lease_started(self, worker, sequence, keys, fetch):
        """Whether the host began the lease, from its heartbeats."""
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
            if completion_key(sequence) in keys:
                self.complete(worker, sequence, keys, fetch)
            elif interruption_key(sequence) in keys:
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
            # Worker evidence shows the worker started, even one whose heartbeats never landed.
            worker.started |= shows_worker_start(keys)

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
        validate_frozen_inputs(self.root, self.arguments, self.jobs_path, self.fingerprint)
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
        write_attempt(self.attempts_path, worker.job, "stopped" if status == 0 else classification,
                      "-" if status == 0 else detail)
        pool = worker.pool
        self.release_slot(worker.job, worker.slot, worker.slot_failures, worker.started, status,
                          classification, detail, worker.job.host_epoch in self.terminated)
        if worker.started:
            rebalance = self.early_rebalance(worker, session, keys)
            if rebalance is not None:
                self.cool_down(pool, cleanup.get("aws_region") or worker.job.region)
                write_event(self.events_path, "interruption", self.arguments.campaign_id, worker.job, session,
                            "rebalance-recommendation", rebalance, unit_id="")
            return
        if status != 0 and worker.job.host_epoch not in self.terminated:
            # Each launch that failed before its worker started is recorded as a host job's failure is.
            retryable = classification in ("retryable-spot", "retryable-capacity", "retryable-infrastructure")
            write_event(self.events_path, "interruption" if retryable else "rejected-session",
                        self.arguments.campaign_id, worker.job, session, detail if retryable else "job-failure",
                        f"status={status}; classification={classification}; detail={detail}", unit_id="")
        if classification == "retryable-spot" and detail.startswith("launch-capacity:"):
            # Spot moves past a region whose launch found no capacity, as a host job's replacement does.
            self.cool_down(pool, cleanup.get("aws_region") or worker.job.region)
        elif classification == "retryable-capacity":
            self.pool_ready[pool] = time.monotonic() + CAPACITY_RETRY_SECONDS

    def cool_down(self, pool, region):
        """Keep the pool's new Spot workers out of the region for its cool-down."""
        self.pool_regions.setdefault(pool, {})[region] = time.monotonic()

    def early_rebalance(self, worker, session, keys):
        """Describe a Spot worker's stop for a rebalance recommendation that came early, or return None."""
        evidence = session / worker.job.platform.architecture / "re2-results"
        try:
            stop = read_properties(evidence / "worker-session.txt")
        except (OSError, RuntimeError):
            return None
        if worker.job.market != "spot" or stop.get("stop_reason") != "rebalance":
            return None
        completed = sum(key.startswith("units/") and key.endswith("/completion.json") for key in keys)
        try:
            launched = read_properties(evidence / "environment-manifest.txt")["instance_pending_time"]
            seconds = (datetime.fromisoformat(stop["stopped_at"]) - datetime.fromisoformat(launched)).total_seconds()
        except (OSError, RuntimeError, KeyError, ValueError):
            seconds = None
        if completed and (seconds is None or seconds > EARLY_REBALANCE_SECONDS):
            return None
        return f"units_completed={completed};seconds_after_launch={'unknown' if seconds is None else int(seconds)}"

    def release_slot(self, job, slot, failures, started, status, classification, detail, terminated=False):
        """Free the launch slot of an exited worker, from its exit as the attempts ledger records it.

        A worker that started, by uploading worker evidence, clears its slot's count. One that failed before it
        started passes the count, one higher, to its replacement, and a slot past its replacements gives up, as a
        host job does, so its pool opens no new slot. A launch that found no capacity, or a wrapper never created,
        booted nothing.
        """
        pool = unit_worker_pool(job)
        if started:
            failures = 0
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
            elif (item.pool in self.exhausted and not self.free_slots.get(item.pool) and
                  not self.pool_workers(item.pool)):
                self.unresolved[item.identity] = self.exhausted[item.pool]

    def lease_document(self, worker, sequence):
        document = {"schema_version": 1, "campaign_id": self.arguments.campaign_id,
                    "platform": worker.job.platform.name, "host_session": worker.host_session, "sequence": sequence}
        identity = worker.leases[sequence]
        if identity is None:
            return {**document, "stop": True}
        item = self.items[identity]
        return {**document, "phase": self.arguments.phase, "unit_id": item.unit_id, "replica_id": item.replica,
                "protocol": phase_protocol(self.arguments)}

    def issue(self, worker, item):
        """Record a lease, or a stop lease without an item, before it is written."""
        sequence = max(worker.leases, default=0) + 1
        write_unit_attempt(self.leases_path, worker, sequence, item, "leased" if item else "stop")
        worker.leases[sequence] = item.identity if item else None
        worker.unsent.add(sequence)
        if item is None:
            worker.settled.add(sequence)
            worker.stopping = True
        else:
            worker.units.add(item.unit_id)
            self.held[item.identity] = (worker.job.host_epoch, sequence)

    def send(self, worker):
        for sequence in sorted(worker.unsent):
            try:
                put_unit_lease(self.arguments, worker, sequence, self.lease_document(worker, sequence))
            except (OSError, subprocess.SubprocessError):
                return
            worker.unsent.discard(sequence)

    def next_item(self, worker):
        """The eligible queued item to lease next: On-Demand work first, then the longest. An item is
        never a unit this host already held, nor one on an instance its replicas or confirmation
        exclude, and On-Demand-only items go to On-Demand hosts; On-Demand hosts also take the rest."""
        used = {}
        for receipt in self.accepted.values():
            used.setdefault((receipt["platform"], receipt["unit_id"]), set()).add(receipt["instance_id"])
        on_demand_worker = worker.job.market == "on-demand"
        # A Spot worker keeps taking its pool's tail items until an On-Demand worker replaces it.
        candidates = [
            item for item in self.queued(worker.pool)
            if item.unit_id not in worker.units and worker.instance_id not in item.excluded_instances and
            worker.instance_id not in used.get((item.platform, item.unit_id), ()) and
            (on_demand_worker or self.spot_only or item.identity not in self.on_demand_only)]
        return min(candidates, key=lambda item: (not self.on_demand(item), -item.estimated_seconds, item.identity),
                   default=None)

    def lease(self, worker):
        """Keep the worker's next lease written one ahead of its current one, or stop it."""
        if worker.metadata is None:
            return
        if (worker.open() and worker.job.market == "spot" and not self.spot_only and worker.pool in self.tail and
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
                self.issue(worker, item)
                outstanding = worker.outstanding()
        self.send(worker)

    def final(self, worker):
        """Whether the worker's pool can give it no further lease."""
        others = {self.held[identity][0] for identity in self.held} - {worker.job.host_epoch}
        return not self.queued(worker.pool) and not any(
            self.workers[epoch].pool == worker.pool for epoch in others if epoch in self.workers)

    def update_tail(self):
        """Mark a pool for On-Demand replacement once its remaining estimated work falls to its tail share.

        Its Spot workers keep taking tail items until an On-Demand worker admitted in the tail replaces them.
        """
        if self.spot_only:
            return
        for pool in self.pools:
            items = [item for item in self.items.values() if item.pool == pool]
            remaining = sum(item.estimated_seconds for item in items
                            if item.identity not in self.accepted and item.identity not in self.unresolved)
            if remaining <= sum(item.estimated_seconds for item in items) * ON_DEMAND_TAIL_PERCENT / 100:
                self.tail.add(pool)

    def pool_demand(self, pool, now):
        """The market of the pool's next worker, if its queued items could use one more."""
        if pool in self.exhausted and not self.free_slots.get(pool):
            return None
        queued = self.queued(pool)
        waiting = [worker for worker in self.pool_workers(pool) if worker.open() and not worker.outstanding()]
        on_demand = [item for item in queued if self.on_demand(item)]
        if self.on_demand_ready.get(pool, 0) <= now:
            if len(on_demand) > sum(worker.job.market == "on-demand" for worker in waiting):
                return "on-demand"
            spot = len(queued) - len(on_demand)
        else:
            # While On-Demand admission is refused, Spot workers take every item Spot may still run.
            spot = sum(item.identity not in self.on_demand_only for item in queued)
        if spot > 0 and len(queued) > len(waiting):
            return "spot"
        return None

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
            # Each allowed region has found no Spot capacity for this pool within its cool-down.
            if self.spot_only:
                self.pool_regions.pop(pool, None)
                candidate = replace(candidate, spot_capacity_failures=())
            else:
                candidate = replace(candidate, market="on-demand")
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
                if self.budget and blocked_vcpus is not None and candidate.platform.vcpus >= blocked_vcpus:
                    break
                candidate, ready_at, admitted, scan, blocked = admit_launch(
                    self.arguments, self.result_root, self.attempts_path, self.capacity_waits, candidate, now, now,
                    [record[1] for record in running.values()])
                self.candidates[pool] = candidate
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
                worker = self.start(pool, candidate)
                if worker is not None:
                    running[candidate.host_epoch] = (worker.process, worker.job, worker.log)

    def start(self, pool, job):
        del self.candidates[pool]
        # A launch takes over a free slot, and its failures, before it opens a new one named for it.
        slots = self.free_slots.get(pool)
        slot, slot_failures = slots.pop(0) if slots else (job.host_epoch, 0)
        validate_frozen_inputs(self.root, self.arguments, self.jobs_path, self.fingerprint)
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
        worker = self.workers[job.host_epoch] = UnitWorker(job, process, log, slot=slot, slot_failures=slot_failures)
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
        self.fingerprint = frozen_input_fingerprint(self.root, arguments, self.jobs_path)
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
                drained = all(identity in self.accepted or identity in self.unresolved for identity in self.items)
                for epoch, worker in self.workers.items():
                    # A lost host is stopped at once; its cleanup still recovers a completion that lands.
                    # Once nothing remains to lease, so is a host that never started.
                    if (worker.lost or (drained and not worker.started)) and epoch not in self.terminated:
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
                      if identity not in self.accepted or identity in self.semantic]
        write_unresolved_units(self.result_root / "unresolved-units.tsv", unresolved)
        if unresolved:
            details = ", ".join(f"{item.identity} {detail}" for item, detail in unresolved)
            raise SystemExit(f"campaign completed with unresolved units: {details}")
        self.validate_phase()

    def validate_phase(self):
        """The phase gate: every unit replica of the phase accepted as one campaign."""
        arguments = self.arguments
        receipts, confirmations = self.accepted_path, ()
        if arguments.phase == "confirmation":
            receipts = arguments.primary_results / "accepted-units.tsv"
            confirmations = read_accepted_units(self.accepted_path)
        try:
            unit_session.validate_unit_campaign(
                campaign_manifest(self.root, arguments),
                self.root / "tools/re2-benchmark/baseline/platforms.tsv",
                campaign_shards(self.root, arguments),
                receipts,
                arguments.campaign_id,
                (1,) if arguments.phase == "smoke" else (1, 2, 3),
                selected_shards=getattr(arguments, "selected_shards", None),
                confirmations=confirmations)
        except (OSError, ValidationError) as error:
            raise SystemExit(f"{arguments.phase} unit campaign is not accepted: {error}") from error


def execute_units(arguments, root, result_root, items, platforms):
    with controller_lock(result_root):
        UnitDispatcher(arguments, root, result_root, items, platforms).run()


def main():
    arguments = parse_args()
    if arguments.fleet_budget:
        arguments.shared_budget = FleetBudget(arguments.fleet_budget)
    directory = Path(__file__).resolve().parent
    root = directory.parents[2]
    if arguments.release_version:
        release = released_artifact.manifest(root, arguments.release_version)
        if arguments.refill_unresolved:
            production_tree = check_output(["git", "rev-parse", f"{candidate_revision(arguments)}:src/main"], cwd=root)
        else:
            production_tree = released_artifact.production_tree(root)
        if production_tree != release["engine_tree"]:
            raise SystemExit("production source differs from the selected release")
    result_root = arguments.result_root or root / "benchmark-results" / "pre-review-baseline" / arguments.campaign_id / arguments.phase
    platforms = read_platforms(directory)
    arguments.regions = campaign_regions(arguments)
    arguments.transfer_buckets = parse_transfer_buckets(arguments.transfer_bucket)
    arguments.regional_amis = read_platform_regions(directory)
    shards = read_shards(directory)
    if arguments.selected_shards is not None:
        if (arguments.language_plan or arguments.phase in ('confirmation', 'build') or
                not set(arguments.selected_shards) <= set(shards) or
                len(arguments.selected_shards) != len(set(arguments.selected_shards))):
            raise SystemExit('invalid baseline shard selection')
        shards = arguments.selected_shards
    if arguments.language_plan or arguments.phase == "build":
        if arguments.confirmation_jobs is not None:
            raise SystemExit("confirmation jobs select baseline work units")
        if arguments.language_plan and arguments.phase == "build":
            raise SystemExit("language plans cannot be combined with engine builds")
        execute_host_jobs(arguments, root, result_root, platforms)
        return
    if arguments.refill_unresolved:
        raise SystemExit("refill resumes language and build campaigns; a baseline phase retries its own work units")
    units = work_units.load(directory)
    arguments.unit_platforms = {platform.name: platform for platform in platforms}
    confirmation_units = ()
    if arguments.phase == "confirmation":
        if arguments.primary_results is None or arguments.confirmation_jobs is None:
            raise SystemExit("confirmation requires --primary-results and --confirmation-jobs")
        confirmation_units = read_confirmation_units(
            arguments.confirmation_jobs, platforms, units,
            read_accepted_units(arguments.primary_results / "accepted-units.tsv"))
    items = build_work_items(arguments.phase, platforms, units, shards, confirmation_units)
    validate_configuration(arguments, [], items=items)
    validate_durable_paths(root, arguments, result_root)
    items_path = result_root / "work-items.tsv"
    write_work_items(items_path, items)
    print(f"planned {len(items)} work items with maximum concurrency {arguments.max_concurrent}")
    print(f"work item manifest: {items_path}")
    if arguments.execute:
        validate_source(root, arguments)
        validate_dependencies(root, arguments, items)
        resolve_engine_builds(root, arguments, platforms)
        validate_prerequisites(root, arguments, platforms)
        workers = [unit_worker_job(arguments.unit_platforms[name], 0, 0, "spot")
                   for name in sorted({item.pool for item in items})]
        validate_aws(arguments, platforms, workers)
        upload_engine_builds(arguments, workers)
        execute_units(arguments, root, result_root, items, platforms)


def execute_host_jobs(arguments, root, result_root, platforms):
    """Plan and run a language campaign's batches or the build phase's engine builds, one host each."""
    if arguments.language_plan:
        arguments.language_campaign = LanguageCampaign(arguments.language_plan, arguments.phase)
        jobs = build_language_jobs(arguments.language_campaign, platforms)
    else:
        jobs = build_engine_jobs(platforms)
    launched_jobs = refill_launch_jobs(arguments, result_root, jobs) if arguments.refill_unresolved else None
    validate_configuration(arguments, jobs, launched_jobs)
    validate_durable_paths(root, arguments, result_root)
    jobs_path = result_root / "jobs.tsv"
    write_jobs(jobs_path, jobs)
    print(f"planned {len(jobs)} jobs with maximum concurrency {arguments.max_concurrent}")
    print(f"job manifest: {jobs_path}")
    if arguments.execute:
        validate_source(root, arguments)
        validate_dependencies(root, arguments, jobs)
        resolve_engine_builds(root, arguments, platforms)
        validate_prerequisites(root, arguments, platforms)
        validate_aws(arguments, platforms, jobs)
        execute_jobs(arguments, root, result_root, jobs)


if __name__ == "__main__":
    main()
