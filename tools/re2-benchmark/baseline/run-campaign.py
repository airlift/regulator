#!/usr/bin/env python3

import argparse
import csv
import fcntl
import hashlib
import json
import math
import os
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
from dataclasses import dataclass, replace
from pathlib import Path

from acceptance import RECEIPT_FIELDS, ValidationError, normalize_heap_size, validate_campaign, validate_host_results

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "language"))
import batch_acceptance
import collection as language_collection
import fleet as language_fleet
from campaign import LanguageCampaign
from topology import CONCURRENCY_SHARDS
import released_artifact

PROACTIVE_SPOT_CAPACITY_THRESHOLD = 32
TOTAL_INSTANCE_LIMIT = 64
STANDARD_ON_DEMAND_QUOTA_CODE = "L-1216C47A"
STANDARD_SPOT_QUOTA_CODE = "L-34B43A08"
STANDARD_INSTANCE_FAMILIES = frozenset("acdhimrtz")
ON_DEMAND_VCPU_SAFETY_RESERVE = 32
SPOT_VCPU_SAFETY_RESERVE = 32
CAPACITY_RETRY_SECONDS = 30
JOB_TIMEOUT_SECONDS = 90 * 60
MAX_REPLACEMENTS_PER_JOB = 2
PINNED_REBAR_COMMIT = "463d00f31887e84c38467805b9e3122c314b9521"
TRINO_SHARDS = frozenset((
    "trino-operations",
    "trino-final-line",
    "trino-like",
    "like-compile",
    "like-single-use",
    "like-dfa-single-use",
    "lifecycle",
    "lifecycle-shared-cold",
    *(f"rebar-{letter}" for letter in "abcdefghijklmnopqrst"),
))
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
PHASE_TIMEOUT_SECONDS = {
    "smoke": 6 * 60 * 60,
    "primary": 10 * 60 * 60,
    "confirmation": 2 * 60 * 60,
}
PHASE_HOST_EPOCH_START = {
    "smoke": 1,
    "primary": 100_001,
    "confirmation": 200_001,
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
class Job:
    platform: Platform
    shard: str
    replica: int
    host_epoch: int
    attempt: int
    market: str
    workload: str = "baseline-shard"

    @property
    def identity(self):
        return f"{self.platform.name}/{self.shard}/replica-{self.replica}/epoch-{self.host_epoch}"

    @property
    def logical_identity(self):
        return f"{self.platform.name}/{self.shard}/replica-{self.replica}"


class UnsafeCampaignError(RuntimeError):
    """The campaign cannot continue without risking invalid evidence or leaked resources."""


def campaign_engine_tree(arguments):
    return arguments.engine_tree


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=PHASE_TIMEOUT_SECONDS)
    parser.add_argument("campaign_id")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirmation-jobs", type=Path)
    parser.add_argument("--smoke-results", type=Path)
    parser.add_argument("--primary-results", type=Path)
    parser.add_argument("--result-root", type=Path)
    parser.add_argument("--max-concurrent", type=int, default=TOTAL_INSTANCE_LIMIT)
    parser.add_argument("--max-concurrent-per-platform", type=int, default=TOTAL_INSTANCE_LIMIT)
    parser.add_argument("--shard", action="append", dest="selected_shards",
                        help="baseline shard to recollect; repeat to select a subset")
    parser.add_argument("--release-version", default="",
                        help="measure the pinned published JAR instead of source-built classes")
    parser.add_argument("--smoke-protocol", choices=("smoke", "qualification"), default="smoke",
                        help="use qualification timing in baseline smoke")
    parser.add_argument("--phase-timeout-seconds", type=int,
                        help="frozen phase wall-clock limit; otherwise derive from topology plus capacity waits")
    parser.add_argument("--candidate-ref")
    parser.add_argument("--candidate-archive", type=Path)
    parser.add_argument("--candidate-provenance", type=Path)
    parser.add_argument("--language-plan", action="append", type=Path, default=[],
                        help="expanded language fleet plan; repeat for lifecycle and bulk")
    return parser.parse_args()


def language_campaign(arguments):
    return getattr(arguments, "language_campaign", None)


def execution_policy(arguments):
    return {"release_version": getattr(arguments, "release_version", ""),
            "smoke_protocol": getattr(arguments, "smoke_protocol", "smoke"),
            "phase_timeout_seconds": getattr(arguments, "topology_deadline_seconds", None)}


def validate_execution_policy(arguments, result_root):
    expected = execution_policy(arguments)
    path = result_root / "execution-policy.json"
    if path.exists():
        if json.loads(path.read_text()) != expected:
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
                                         getattr(arguments, 'max_concurrent_per_platform', TOTAL_INSTANCE_LIMIT))
    return PHASE_TIMEOUT_SECONDS[arguments.phase]


def build_language_jobs(language, platforms):
    by_name = {platform.name: platform for platform in platforms}
    return [Job(by_name[batch["platform"]], batch["shard"], batch["replica"], epoch, 1, "spot", "language-batch")
            for epoch, (_, batch) in enumerate(language.batches.values(),
                                               start=PHASE_HOST_EPOCH_START[language.phase])]


def accepted_sessions_path(result_root, arguments):
    return result_root / ("accepted-language-sessions.json" if language_campaign(arguments) else "accepted-sessions.tsv")


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


def campaign_manifest(root, arguments):
    return root / "tools/re2-benchmark/baseline/rows.tsv"


def campaign_shards(root, arguments):
    return root / "tools/re2-benchmark/baseline/shards.tsv"


def read_shards(directory):
    with (directory / "shards.tsv").open(newline="") as input_file:
        return [row["shard_id"] for row in csv.DictReader(input_file, delimiter="\t")]


def read_confirmation_jobs(path, platforms, shards):
    if path is None:
        raise SystemExit("confirmation requires --confirmation-jobs")
    platform_by_name = {platform.name: platform for platform in platforms}
    selected = []
    seen = set()
    with path.open(newline="") as input_file:
        for row in csv.DictReader(input_file, delimiter="\t"):
            key = (row["platform"], row["shard_id"])
            if key in seen:
                raise SystemExit(f"duplicate confirmation job: {key}")
            if row["platform"] not in platform_by_name:
                raise SystemExit(f"unknown confirmation platform: {row['platform']}")
            if row["shard_id"] not in shards:
                raise SystemExit(f"unknown confirmation shard: {row['shard_id']}")
            seen.add(key)
            selected.append((platform_by_name[row["platform"]], row["shard_id"], 4))
    return selected


def build_jobs(phase, platforms, shards, confirmation_jobs):
    if phase == "confirmation":
        matrix = read_confirmation_jobs(confirmation_jobs, platforms, shards)
    else:
        replicas = range(1, 2) if phase == "smoke" else range(1, 4)
        ordered_shards = sorted(shards, key=lambda shard: (not shard.startswith("rebar-"), shard))
        matrix = [
            (platform, shard, replica)
            for shard in ordered_shards
            for replica in replicas
            for platform in platforms
        ]
    return [
        Job(concurrency_platform(platform, shard), shard, replica, host_epoch, 1, "spot")
        for host_epoch, (platform, shard, replica) in enumerate(
            matrix, start=PHASE_HOST_EPOCH_START[phase])
    ]


def concurrency_platform(platform, shard):
    if shard in CONCURRENCY_SHARDS and platform.concurrency_instance_type:
        return replace(platform, instance_type=platform.concurrency_instance_type, vcpus=platform.concurrency_vcpus)
    return platform



def platform_has_capacity(candidate, running, arguments):
    limit = getattr(arguments, 'max_concurrent_per_platform', TOTAL_INSTANCE_LIMIT)
    return sum(job.platform.name == candidate.platform.name for _, job, _ in running.values()) < limit


def validate_configuration(arguments, jobs):
    campaign_heap_size(arguments)
    if not arguments.campaign_id or len(arguments.campaign_id) > 63:
        raise SystemExit("campaign_id must contain 1-63 characters")
    if arguments.max_concurrent < 1 or arguments.max_concurrent > TOTAL_INSTANCE_LIMIT:
        raise SystemExit(f"max concurrency must be between 1 and {TOTAL_INSTANCE_LIMIT}")
    per_platform = getattr(arguments, 'max_concurrent_per_platform', TOTAL_INSTANCE_LIMIT)
    if not 1 <= per_platform <= TOTAL_INSTANCE_LIMIT:
        raise SystemExit(f"per-platform concurrency must be between 1 and {TOTAL_INSTANCE_LIMIT}")
    if arguments.phase == "primary" and len(jobs) == 0:
        raise SystemExit("primary campaign has no jobs")
    counts = {}
    for job in jobs:
        counts[job.platform.name] = counts.get(job.platform.name, 0) + 1
    bounded_waves = max(math.ceil(len(jobs) / arguments.max_concurrent),
                        max((math.ceil(count / per_platform) for count in counts.values()), default=0))
    required_seconds = (bounded_waves + 1) * JOB_TIMEOUT_SECONDS
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


def validate_durable_paths(root, arguments, result_root):
    build_output = (root / "target").resolve()
    paths = (
        ("result root", result_root),
        ("candidate archive", arguments.candidate_archive),
        ("candidate provenance", arguments.candidate_provenance),
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


def check_output(command, **kwargs):
    return subprocess.check_output(command, text=True, **kwargs).strip()


def fsync_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def deterministic_archive_sha256(root, candidate):
    archive = subprocess.Popen(
        ["git", "archive", "--format=tar", candidate],
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
    if head != candidate:
        raise SystemExit(f"candidate ref {arguments.candidate_ref} is {candidate}, but HEAD is {head}")
    engine_tree = check_output(["git", "rev-parse", "HEAD:src/main"], cwd=root)
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
        "root_tree": check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=root),
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


def frozen_input_fingerprint(root, arguments, jobs_path):
    if not (root / ".git").exists():
        return None
    tool_paths = check_output(
        ["git", "ls-files", "tools/re2-benchmark"], cwd=root).splitlines()
    tracked_inputs = [root / path for path in tool_paths]
    tracked_inputs.extend((
        arguments.candidate_archive,
        arguments.candidate_provenance,
        jobs_path))
    if language_campaign(arguments):
        tracked_inputs.extend(directory / "plan.json" for directory, _ in language_campaign(arguments).plans.values())
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
    if path.suffix == ".json":
        receipt = json.loads(path.read_text())
        if receipt.get("schema_version") != 1 or receipt.get("workload") != "language-batch":
            raise RuntimeError(f"unexpected language receipt schema in {path}")
        return receipt
    with path.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != RECEIPT_FIELDS:
            raise RuntimeError(f"unexpected receipt schema in {path}")
        rows = list(reader)
    if len(rows) != 1:
        raise RuntimeError(f"{path} must contain exactly one receipt")
    return rows[0]


def validate_phase_prerequisite(root, arguments, platforms, result_root, expected_replicas, phase):
    if result_root is None:
        raise SystemExit(f"{arguments.phase} execution requires --{phase}-results")
    accepted_sessions = result_root / "accepted-sessions.tsv"
    try:
        validate_campaign(
            campaign_manifest(root, arguments),
            root / "tools/re2-benchmark/baseline/platforms.tsv",
            campaign_shards(root, arguments),
            accepted_sessions,
            arguments.campaign_id,
            expected_replicas,
            selected_shards=getattr(arguments, 'selected_shards', None))
    except (OSError, ValidationError) as error:
        raise SystemExit(f"{phase} prerequisite campaign is not accepted: {error}") from error

    with accepted_sessions.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != RECEIPT_FIELDS:
            raise SystemExit(f"unexpected receipt schema in {accepted_sessions}")
        ledger = list(reader)
    platform_by_name = {platform.name: platform for platform in platforms}
    manifest = campaign_manifest(root, arguments)
    for ledger_receipt in ledger:
        platform = concurrency_platform(platform_by_name[ledger_receipt["platform"]], ledger_receipt["shard_id"])
        job = Job(
            platform,
            ledger_receipt["shard_id"],
            int(ledger_receipt["replica_id"]),
            int(ledger_receipt["host_epoch"]),
            1,
            "spot")
        try:
            artifact_receipt_path = validate_job_artifacts(result_root, job)
            artifact_receipt = read_receipt(artifact_receipt_path)
            result_directory = artifact_receipt_path.parent
            regenerated_receipt = validate_host_results(
                manifest,
                result_directory / "session.tsv",
                result_directory / "observed-rows.tsv")
            version = getattr(arguments, "release_version", "")
            if version:
                released_artifact.validate_baseline(result_directory, released_artifact.manifest(root, version))
        except (OSError, RuntimeError, ValidationError, ValueError) as error:
            raise SystemExit(
                f"{phase} prerequisite artifacts are not accepted for {job.identity}: {error}") from error
        if artifact_receipt != ledger_receipt:
            raise SystemExit(f"{phase} prerequisite artifact receipt differs from its ledger: {job.identity}")
        if regenerated_receipt != ledger_receipt:
            raise SystemExit(f"{phase} prerequisite receipt cannot be reproduced: {job.identity}")
        expected_candidate = {
            "candidate_commit": check_output(["git", "rev-parse", "HEAD"], cwd=root),
            "candidate_archive_sha256": arguments.candidate_archive_sha256,
            "engine_tree": campaign_engine_tree(arguments),
            "heap_size": campaign_heap_size(arguments),
        }
        mismatches = [
            field for field, value in expected_candidate.items()
            if ledger_receipt[field] != value
        ]
        if mismatches:
            raise SystemExit(
                f"{phase} prerequisite uses a different candidate for {job.identity}: {mismatches}")


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
        return
    if arguments.phase == "primary":
        validate_phase_prerequisite(
            root, arguments, platforms, arguments.smoke_results, (1,), "smoke")
    elif arguments.phase == "confirmation":
        validate_phase_prerequisite(
            root, arguments, platforms, arguments.primary_results, (1, 2, 3), "primary")


def validate_dependencies(root, arguments, jobs):
    if language_campaign(arguments):
        # Expanded plans already contain checksummed source workloads. The JVM
        # worker resolves and verifies the pinned Joni jar during its clean build.
        for directory, _ in language_campaign(arguments).plans.values():
            language_fleet.validate(directory)
        return
    subprocess.run([str(root / "tools/re2-benchmark/manifests/validate-rebar-workloads.sh")], check=True)
    subprocess.run([str(root / "tools/re2-benchmark/baseline/validate-manifest.py")], check=True)
    subprocess.run([str(root / "tools/re2-benchmark/baseline/validate-shard-plans.py")], check=True)
    rebar_root = Path(os.environ.get(
        "REBAR_OFFICIAL_DIR",
        Path.home() / ".cache/regulator" / f"rebar-{PINNED_REBAR_COMMIT}"))
    subprocess.run([
        str(root / "tools/re2-benchmark/manifests/validate-extended-rebar.sh"),
        str(rebar_root),
    ], check=True)
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


def aws_output(arguments):
    environment = os.environ.copy()
    region = environment.get("AWS_REGION", "us-west-2")
    command = ["aws", "--region", region]
    profile = environment.get("AWS_PROFILE")
    if profile:
        command[1:1] = ["--profile", profile]
    return command


def validate_aws(arguments, platforms):
    prefix = aws_output(arguments)
    check_output(prefix + ["sts", "get-caller-identity", "--query", "Account", "--output", "text"])
    spot_quota = float(check_output(prefix + [
        "service-quotas", "get-service-quota", "--service-code", "ec2",
        "--quota-code", STANDARD_SPOT_QUOTA_CODE, "--query", "Quota.Value", "--output", "text",
    ]))
    on_demand_quota = float(check_output(prefix + [
        "service-quotas", "get-service-quota", "--service-code", "ec2",
        "--quota-code", STANDARD_ON_DEMAND_QUOTA_CODE, "--query", "Quota.Value", "--output", "text",
    ]))
    minimum_vcpus = min(platform.vcpus for platform in platforms)
    has_spot_capacity = spot_quota >= SPOT_VCPU_SAFETY_RESERVE + minimum_vcpus
    has_on_demand_capacity = on_demand_quota >= ON_DEMAND_VCPU_SAFETY_RESERVE + minimum_vcpus
    if not has_spot_capacity and not has_on_demand_capacity:
        raise SystemExit(
            f"Neither Standard Spot nor On-Demand quota can admit one host after safety reserves: "
            f"spot={spot_quota:g}, on-demand={on_demand_quota:g}")
    for platform in platforms:
        expected_architecture = "x86_64" if platform.architecture == "intel" else "arm64"
        image = check_output(prefix + [
            "ec2", "describe-images", "--image-ids", platform.ami_id,
            "--query", "Images[0].[Architecture,State,OwnerId]", "--output", "text",
        ]).split()
        if image != [expected_architecture, "available", "137112412989"]:
            raise SystemExit(f"unexpected AMI identity for {platform.name}: {image}")
        zones = check_output(prefix + [
            "ec2", "describe-instance-type-offerings", "--location-type", "availability-zone",
            "--filters", f"Name=instance-type,Values={platform.instance_type}",
            "--query", "InstanceTypeOfferings[].Location", "--output", "text",
        ]).split()
        if not zones:
            raise SystemExit(f"{platform.instance_type} is not offered in the selected region")


def is_standard_instance_type(instance_type):
    return bool(instance_type) and instance_type[0].lower() in STANDARD_INSTANCE_FAMILIES


def current_on_demand_capacity(arguments, campaign_id, running_jobs):
    prefix = aws_output(arguments)
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


def current_spot_capacity(arguments, campaign_id, running_jobs):
    prefix = aws_output(arguments)
    quota = int(float(check_output(prefix + [
        "service-quotas", "get-service-quota", "--service-code", "ec2",
        "--quota-code", STANDARD_SPOT_QUOTA_CODE,
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
        if instance.get("lifecycle") == "spot" and is_standard_instance_type(instance.get("instance_type"))
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

    reserved_vcpus = sum(
        job.platform.vcpus
        for job in running_jobs
        if job.market == "spot"
    )
    available_vcpus = max(
        0,
        quota - used_vcpus - reserved_vcpus - SPOT_VCPU_SAFETY_RESERVE)
    return {
        "quota": quota,
        "used": used_vcpus,
        "reserved": reserved_vcpus,
        "safety_reserve": SPOT_VCPU_SAFETY_RESERVE,
        "available": available_vcpus,
    }


def write_jobs(path, jobs):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_name(path.name + ".new")
    with temporary_path.open("w", newline="") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        writer.writerow(("platform", "shard_id", "replica", "host_epoch", "attempt", "market",
                         "instance_type", "architecture"))
        for job in jobs:
            writer.writerow((job.platform.name, job.shard, job.replica, job.host_epoch, job.attempt, job.market,
                             job.platform.instance_type, job.platform.architecture))
        output_file.flush()
        os.fsync(output_file.fileno())
    if path.exists() and path.read_bytes() != temporary_path.read_bytes():
        temporary_path.unlink()
        raise UnsafeCampaignError(f"existing job manifest changed: {path}")
    os.replace(temporary_path, path)
    fsync_directory(path.parent)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    path.with_suffix(".sha256").write_text(f"{digest}  {path.name}\n")


def job_result_root(result_root, job):
    return (result_root / "jobs" / job.platform.name / job.shard /
            f"replica-{job.replica}" / f"epoch-{job.host_epoch}")


def job_environment(job, arguments, root, result_root):
    environment = os.environ.copy()
    architecture = job.platform.architecture
    benchmark_protocol = getattr(arguments, "smoke_protocol", "smoke") if arguments.phase == "smoke" else "qualification"
    environment.update({
        "AWS_REGION": environment.get("AWS_REGION", "us-west-2"),
        "CAMPAIGN_PROVENANCE": "qualification",
        "CAMPAIGN_MODE": job.workload,
        "BASELINE_PROTOCOL": benchmark_protocol,
        "CAMPAIGN_ID": arguments.campaign_id,
        "CAMPAIGN_PLATFORM": job.platform.name,
        "CAMPAIGN_SHARD_ID": job.shard,
        "CAMPAIGN_REPLICA_ID": str(job.replica),
        "CAMPAIGN_HOST_EPOCH": str(job.host_epoch),
        "CAMPAIGN_ARCHITECTURES": architecture,
        "INSTANCE_MARKET_TYPE": job.market,
        "REGULATOR_RELEASE_VERSION": getattr(arguments, "release_version", ""),
        "BENCHMARK_CPU_LIST": "0-7" if job.shard in CONCURRENCY_SHARDS else "0",
        "BENCHMARK_EXPECTED_VCPUS": str(job.platform.vcpus),
        "TIMEOUT_SECONDS": str(JOB_TIMEOUT_SECONDS),
        "RESULT_ROOT": str(job_result_root(result_root, job)),
        "TRINO_REVISION": "c7503d170344c4e266f03fdb39e53c82aa7e3594",
        "REBAR_COMPARATOR_ORDER": "forward" if job.replica % 2 else "reverse",
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
            "BENCHMARK_INTEL_AMI_ID": job.platform.ami_id,
            "BENCHMARK_INTEL_JAVA_ARCHIVE_URL": job.platform.jdk_url,
            "BENCHMARK_INTEL_JAVA_ARCHIVE_SHA256": job.platform.jdk_sha256,
        })
    else:
        environment.update({
            "ARM_INSTANCE_TYPE": job.platform.instance_type,
            "BENCHMARK_ARM_AMI_ID": job.platform.ami_id,
            "BENCHMARK_ARM_JAVA_ARCHIVE_URL": job.platform.jdk_url,
            "BENCHMARK_ARM_JAVA_ARCHIVE_SHA256": job.platform.jdk_sha256,
        })
    environment.setdefault(
        "REBAR_OFFICIAL_DIR",
        str(Path.home() / ".cache/regulator" / f"rebar-{PINNED_REBAR_COMMIT}"))
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
    receipt_name = "language-acceptance.json" if job.workload == "language-batch" else "acceptance-receipt.tsv"
    receipt = session / label / "re2-results" / receipt_name
    if not receipt.is_file():
        raise RuntimeError(f"{job.identity} has no acceptance receipt")
    validate_capacity(session / label / "re2-results" / "host-capacity.txt")
    if job.platform.concurrency_instance_type:
        environment = read_properties(receipt.parent / "environment-manifest.txt")
        if environment.get("logical_cpu_count") != str(job.platform.vcpus):
            raise UnsafeCampaignError("host logical CPU count differs from the frozen topology")
        if job.workload == "baseline-shard":
            expected_affinity = "0-7" if job.shard in CONCURRENCY_SHARDS else "0"
            routes = list((receipt.parent / "routes").glob("*/run-metadata.txt"))
            if not routes or any(read_properties(path).get("cpu_list") != expected_affinity for path in routes):
                raise UnsafeCampaignError("route CPU affinity differs from the frozen topology")
    if job.workload == "language-batch":
        try:
            if batch_acceptance.validate(receipt.parent) != read_receipt(receipt):
                raise ValueError("saved language receipt differs from raw evidence")
        except (ValueError, KeyError, TypeError) as error:
            raise RuntimeError(f"invalid language evidence for {job.identity}: {error}") from error
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


def replacement_job(job, host_epoch, failure_classification, failure_detail):
    if (failure_classification != "retryable-capacity" and
            job.attempt >= MAX_REPLACEMENTS_PER_JOB + 1):
        raise RuntimeError(f"{job.logical_identity} exhausted its bounded replacement allowance")
    if failure_classification == "retryable-capacity":
        market = "on-demand"
    elif job.market == "spot":
        # One interrupted Spot replacement is allowed. Explicit unavailability
        # moves directly to the same On-Demand instance type.
        market = (
            "on-demand"
            if job.attempt >= 2 or failure_detail.startswith("launch-capacity:")
            else "spot")
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


def write_event(path, event_type, campaign_id, job, session, reason, details=""):
    metadata = {}
    session_file = session / "session.txt"
    if session_file.is_file():
        metadata = read_properties(session_file)
    instance_field = "intel_instance_id" if job.platform.architecture == "intel" else "arm_instance_id"
    instance_id = metadata.get(instance_field, "")
    fields = (
        "event_type", "campaign_id", "platform", "shard_id", "replica_id", "instance_id",
        "host_epoch", "reason", "artifact_uri", "artifact_sha256", "details",
    )
    write_header = not path.exists()
    with path.open("a", newline="") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=fields, delimiter="\t", lineterminator="\n")
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
            "reason": reason,
            "artifact_uri": str(session),
            "artifact_sha256": "",
            "details": details.replace("\t", " ").replace("\n", " "),
        })
        output_file.flush()
        os.fsync(output_file.fileno())


def receipt_logical_identity(receipt):
    return f"{receipt['platform']}/{receipt['shard_id']}/replica-{receipt['replica_id']}"


def read_accepted_sessions(path):
    if not path.exists():
        return []
    if path.suffix == ".json":
        data = json.loads(path.read_text())
        if data.get("schema_version") != 1 or data.get("workload") != "language-batch":
            raise UnsafeCampaignError(f"unexpected language ledger schema in {path}")
        rows = data["receipts"]
    else:
        with path.open(newline="") as input_file:
            reader = csv.DictReader(input_file, delimiter="\t")
            if tuple(reader.fieldnames or ()) != RECEIPT_FIELDS:
                raise UnsafeCampaignError(f"unexpected receipt schema in {path}")
            rows = list(reader)
    identities = [receipt_logical_identity(row) for row in rows]
    if len(identities) != len(set(identities)):
        raise UnsafeCampaignError(f"accepted-session ledger contains duplicate logical jobs: {path}")
    return rows


def write_accepted_sessions(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_name(path.name + ".new")
    rows = sorted(rows, key=lambda row: (row["platform"], row["shard_id"], int(row["replica_id"])))
    with temporary_path.open("w", newline="") as output_file:
        if path.suffix == ".json":
            json.dump({"schema_version": 1, "workload": "language-batch", "receipts": rows}, output_file, sort_keys=True)
            output_file.write("\n")
        else:
            writer = csv.DictWriter(output_file, fieldnames=RECEIPT_FIELDS, delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
        output_file.flush()
        os.fsync(output_file.fileno())
    os.replace(temporary_path, path)
    fsync_directory(path.parent)


def persist_accepted_receipt(path, receipt_path):
    receipt = read_receipt(receipt_path)
    rows = read_accepted_sessions(path)
    logical_identity = receipt_logical_identity(receipt)
    existing = next(
        (row for row in rows if receipt_logical_identity(row) == logical_identity),
        None)
    if existing is not None:
        if existing != receipt:
            raise UnsafeCampaignError(
                f"conflicting accepted receipts for {logical_identity}: "
                f"epochs {existing['host_epoch']} and {receipt['host_epoch']}")
        return receipt
    write_accepted_sessions(path, [*rows, receipt])
    return receipt


def validate_receipt_candidate(root, arguments, job, receipt, receipt_path=None):
    version = getattr(arguments, "release_version", "")
    if version:
        identity = released_artifact.manifest(root, version)
        if job.workload == "language-batch":
            if receipt.get("released_artifact") != identity:
                raise UnsafeCampaignError("language receipt does not identify the selected release")
        else:
            if receipt_path is None:
                raise UnsafeCampaignError("released baseline requires recovered artifact evidence")
            released_artifact.validate_baseline(receipt_path.parent, identity)
    expected = {
        "campaign_id": arguments.campaign_id,
        "platform": job.platform.name,
        "shard_id": job.shard,
        "replica_id": str(job.replica),
        "host_epoch": str(job.host_epoch),
        "architecture": job.platform.architecture,
        "instance_type": job.platform.instance_type,
        "candidate_commit": check_output(["git", "rev-parse", "HEAD"], cwd=root),
        "candidate_archive_sha256": arguments.candidate_archive_sha256,
        "engine_tree": campaign_engine_tree(arguments),
        "heap_size": campaign_heap_size(arguments),
    }
    if language_campaign(arguments):
        del expected["engine_tree"]
        expected["source_tree"] = check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=root)
        expected["comparators_sha256"] = hashlib.sha256(
            (root / "tools/re2-benchmark/baseline/comparators.tsv").read_bytes()).hexdigest()
        language_campaign(arguments).validate_receipt(job.logical_identity, receipt)
    mismatches = [field for field, value in expected.items() if receipt.get(field) != value]
    if mismatches:
        raise UnsafeCampaignError(
            f"accepted receipt does not match the frozen campaign for {job.identity}: {mismatches}")


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
        validate_receipt_candidate(root, arguments, job, receipt, receipt_path)
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
            receipt_name = "language-acceptance.json" if job.workload == "language-batch" else "acceptance-receipt.tsv"
            receipt_paths = list(epoch_root.glob(f"*/{job.platform.architecture}/re2-results/{receipt_name}"))
            if not receipt_paths:
                continue
            if len(receipt_paths) != 1:
                raise UnsafeCampaignError(f"{job.identity} contains multiple acceptance receipts")
            receipt_path = validate_job_artifacts(result_root, job)
            receipt = read_receipt(receipt_path)
            validate_receipt_candidate(root, arguments, job, receipt, receipt_path)
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


def job_from_attempt(row, jobs_by_logical_identity):
    logical_identity = f"{row['platform']}/{row['shard_id']}/replica-{row['replica']}"
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
                        "wrapper process was not created")
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


def prepare_resumed_jobs(result_root, jobs, attempts_path, accepted):
    attempts = read_attempts(attempts_path)
    attempts_by_epoch = {
        (row["platform"], row["shard_id"], int(row["replica"]), int(row["host_epoch"])): row
        for row in attempts
    }
    attempts_by_logical_identity = {}
    for row in attempts:
        logical_identity = f"{row['platform']}/{row['shard_id']}/replica-{row['replica']}"
        attempts_by_logical_identity[logical_identity] = row
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
            if classification not in {
                    "retryable-spot", "retryable-capacity", "retryable-infrastructure"}:
                unresolved.append((
                    previous_job,
                    f"attempt without artifacts cannot be resumed: outcome={classification}"))
                continue
            try:
                replacement = replacement_job(
                    previous_job,
                    maximum_epoch + 1,
                    classification,
                    attempt_row["detail"])
            except RuntimeError as error:
                unresolved.append((previous_job, str(error)))
            else:
                maximum_epoch += 1
                ready_at = CAPACITY_RETRY_SECONDS if classification == "retryable-capacity" else 0
                pending.append((replacement, ready_at))
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
        previous_job = replace(
            base_job,
            host_epoch=latest_epoch,
            attempt=int(attempt_row["attempt"]),
            market=attempt_row["market"])
        try:
            session, cleanup = validate_cleanup_manifest(result_root, previous_job)
        except (OSError, RuntimeError) as error:
            raise UnsafeCampaignError(
                f"cannot safely resume {previous_job.identity}: {error}") from error
        classification = cleanup.get("failure_classification", "")
        detail = cleanup.get("failure_detail", "unknown")
        if classification == "controller-abort":
            classification = "retryable-infrastructure"
        if classification in {"retryable-spot", "retryable-capacity", "retryable-infrastructure"}:
            try:
                replacement = replacement_job(
                    previous_job, maximum_epoch + 1, classification, detail)
            except RuntimeError as error:
                unresolved.append((previous_job, str(error)))
            else:
                maximum_epoch += 1
                ready_at = CAPACITY_RETRY_SECONDS if classification == "retryable-capacity" else 0
                pending.append((replacement, ready_at))
            continue
        unresolved.append((
            previous_job,
            f"previous attempt was not accepted: classification={classification or 'none'}; detail={detail}"))
    return pending, unresolved, maximum_epoch + 1


def write_unresolved_jobs(path, unresolved):
    if not unresolved:
        if path.exists():
            path.unlink()
        return
    temporary_path = path.with_name(path.name + ".new")
    with temporary_path.open("w", newline="") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        writer.writerow(("platform", "shard_id", "replica", "host_epoch", "attempt", "market", "detail"))
        for job, detail in unresolved:
            writer.writerow((
                job.platform.name,
                job.shard,
                job.replica,
                job.host_epoch,
                job.attempt,
                job.market,
                detail.replace("\t", " ").replace("\n", " ")))
        output_file.flush()
        os.fsync(output_file.fileno())
    os.replace(temporary_path, path)
    fsync_directory(path.parent)


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
        "max_concurrent_per_platform": getattr(arguments, 'max_concurrent_per_platform', TOTAL_INSTANCE_LIMIT),
        "phase_timeout_seconds": phase_timeout(arguments), "job_timeout_seconds": JOB_TIMEOUT_SECONDS,
        "heap_size": campaign_heap_size(arguments),
        "candidate_commit": check_output(["git", "rev-parse", "HEAD"], cwd=root),
        "candidate_archive_sha256": arguments.candidate_archive_sha256,
        "candidate_provenance_sha256": hashlib.sha256(arguments.candidate_provenance.read_bytes()).hexdigest(),
        "batches": {identity: hashlib.sha256(language_collection.encode(
            language.expected_package(identity))).hexdigest() for identity in language.batches},
    }
    # Canonicalize tuples before comparing to a JSON reload on restart.
    expected = json.loads(json.dumps(expected))
    path = result_root / "language-state.json"
    if path.exists():
        if json.loads(path.read_text()) != expected:
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


def execute_jobs(arguments, root, result_root, jobs):
    result_root.mkdir(parents=True, exist_ok=True)
    lock_path = result_root / "controller.lock"
    with lock_path.open("w") as lock_file:
        try:
            fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise UnsafeCampaignError(
                f"another controller owns campaign result root {result_root}") from error
        return execute_jobs_locked(arguments, root, result_root, jobs)


def execute_jobs_locked(arguments, root, result_root, jobs):
    validate_execution_policy(arguments, result_root)
    runner = root / "tools/re2-benchmark/aws/run-campaign.sh"
    jobs_path = result_root / "jobs.tsv"
    expected_fingerprint = frozen_input_fingerprint(root, arguments, jobs_path)
    running = {}
    attempts_path = result_root / "job-attempts.tsv"
    events_path = result_root / "campaign-events.tsv"
    accepted_sessions = accepted_sessions_path(result_root, arguments)
    validate_language_state(root, arguments, result_root)
    started_at = campaign_started_at(result_root / "campaign-state.tsv", arguments)
    settle_detached_attempts(result_root, jobs, attempts_path)
    accepted = recover_accepted_sessions(
        root, arguments, result_root, jobs, accepted_sessions)
    pending, unresolved, next_host_epoch = prepare_resumed_jobs(
        result_root, jobs, attempts_path, accepted)
    pending = [(job, time.monotonic() + delay) for job, delay in pending]
    capacity_waits = set()
    elapsed_seconds = max(0, time.time() - started_at)
    remaining_seconds = max(0, phase_timeout(arguments) - elapsed_seconds)
    deadline = time.monotonic() + remaining_seconds
    deadline_reached = False
    try:
        while pending or running:
            now = time.monotonic()
            if now >= deadline and not deadline_reached:
                deadline_reached = True
                for job, _ in pending:
                    unresolved.append((job, f"{arguments.phase} campaign reached its wall-clock launch limit"))
                pending.clear()

            launched = False
            while pending and len(running) < arguments.max_concurrent:
                selected_index = None
                for index, (candidate, ready_at) in enumerate(pending):
                    if ready_at > now:
                        continue
                    if not platform_has_capacity(candidate, running, arguments):
                        continue
                    if candidate.market == "spot" and arguments.max_concurrent > PROACTIVE_SPOT_CAPACITY_THRESHOLD:
                        try:
                            spot_capacity = current_spot_capacity(
                                arguments,
                                arguments.campaign_id,
                                [record[1] for record in running.values()])
                        except UnsafeCampaignError:
                            raise
                        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                            pending[index] = (candidate, now + CAPACITY_RETRY_SECONDS)
                            wait_key = (candidate.identity, "spot-capacity-query")
                            if wait_key not in capacity_waits:
                                capacity_waits.add(wait_key)
                                write_attempt(
                                    attempts_path,
                                    candidate,
                                    "queued-capacity",
                                    f"Spot capacity query failed: {error}")
                            continue
                        if spot_capacity["available"] < candidate.platform.vcpus:
                            candidate = replace(candidate, market="on-demand")
                            pending[index] = (candidate, ready_at)
                            wait_key = (candidate.identity, "spot-to-on-demand")
                            if wait_key not in capacity_waits:
                                capacity_waits.add(wait_key)
                                write_attempt(
                                    attempts_path,
                                    candidate,
                                    "queued-capacity",
                                    "Spot capacity exhausted; " +
                                    ";".join(f"{key}={value}" for key, value in spot_capacity.items()))
                    if candidate.market == "on-demand":
                        try:
                            capacity = current_on_demand_capacity(
                                arguments,
                                arguments.campaign_id,
                                [record[1] for record in running.values()])
                        except UnsafeCampaignError:
                            raise
                        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                            pending[index] = (candidate, now + CAPACITY_RETRY_SECONDS)
                            wait_key = (candidate.identity, "capacity-query")
                            if wait_key not in capacity_waits:
                                capacity_waits.add(wait_key)
                                write_attempt(attempts_path, candidate, "queued-capacity", f"capacity query failed: {error}")
                            continue
                        if capacity["available"] < candidate.platform.vcpus:
                            pending[index] = (candidate, now + CAPACITY_RETRY_SECONDS)
                            wait_key = (candidate.identity, "capacity")
                            if wait_key not in capacity_waits:
                                capacity_waits.add(wait_key)
                                write_attempt(
                                    attempts_path,
                                    candidate,
                                    "queued-capacity",
                                    ";".join(f"{key}={value}" for key, value in capacity.items()))
                            continue
                    selected_index = index
                    break
                if selected_index is None:
                    break
                job, _ = pending.pop(selected_index)
                validate_frozen_inputs(root, arguments, jobs_path, expected_fingerprint)
                log_path = result_root / "controller-logs" / (job.identity.replace("/", "-") + ".log")
                log_path.parent.mkdir(parents=True, exist_ok=True)
                log = log_path.open("w")
                write_attempt(attempts_path, job, "starting")
                try:
                    process = subprocess.Popen(
                        [str(runner), job.identity],
                        cwd=root,
                        env=job_environment(job, arguments, root, result_root),
                        stdout=log,
                        stderr=subprocess.STDOUT,
                        start_new_session=True)
                except OSError as error:
                    log.close()
                    detail = f"controller could not start wrapper: {error}"
                    write_attempt(attempts_path, job, "retryable-infrastructure", detail)
                    try:
                        replacement = replacement_job(
                            job, next_host_epoch, "retryable-infrastructure", detail)
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
                                job, next_host_epoch, classification, detail)
                        except RuntimeError as error:
                            failure_detail = f"status={status}; {error}"
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
                    else:
                        receipt = read_receipt(receipt_path)
                        validate_receipt_candidate(root, arguments, job, receipt, receipt_path)
                        persist_accepted_receipt(accepted_sessions, receipt_path)
                        write_attempt(attempts_path, job, "accepted")
                        accepted[job.logical_identity] = receipt
            for process_id in completed:
                del running[process_id]
            if running or (pending and not launched):
                time.sleep(5)
    except (Exception, KeyboardInterrupt) as error:
        try:
            terminate_and_validate_cleanup(running, result_root)
        except RuntimeError as cleanup_error:
            reason = str(error) or type(error).__name__
            raise UnsafeCampaignError(f"{reason}; {cleanup_error}") from error
        raise
    write_unresolved_jobs(result_root / "unresolved-jobs.tsv", unresolved)
    if unresolved:
        details = ", ".join(f"{job.identity} {detail}" for job, detail in unresolved)
        raise SystemExit(f"campaign completed with unresolved jobs: {details}")
    if language_campaign(arguments):
        aggregate_language_results(arguments, result_root, jobs, accepted)
    elif arguments.phase in {"smoke", "primary"}:
        validate_campaign(
            campaign_manifest(root, arguments),
            root / "tools/re2-benchmark/baseline/platforms.tsv",
            campaign_shards(root, arguments),
            accepted_sessions,
            arguments.campaign_id,
            (1,) if arguments.phase == "smoke" else (1, 2, 3),
            selected_shards=getattr(arguments, 'selected_shards', None))


def main():
    arguments = parse_args()
    directory = Path(__file__).resolve().parent
    root = directory.parents[2]
    if arguments.release_version:
        release = released_artifact.manifest(root, arguments.release_version)
        if released_artifact.production_tree(root) != release["engine_tree"]:
            raise SystemExit("production source differs from the selected release")
    result_root = arguments.result_root or root / "benchmark-results" / "pre-review-baseline" / arguments.campaign_id / arguments.phase
    platforms = read_platforms(directory)
    shards = read_shards(directory)
    if arguments.selected_shards is not None:
        if (arguments.language_plan or arguments.phase == 'confirmation' or
                not set(arguments.selected_shards) <= set(shards) or
                len(arguments.selected_shards) != len(set(arguments.selected_shards))):
            raise SystemExit('invalid baseline shard selection')
        shards = arguments.selected_shards
    if arguments.language_plan:
        if arguments.confirmation_jobs is not None:
            raise SystemExit("language plans cannot be combined with baseline confirmation jobs")
        arguments.language_campaign = LanguageCampaign(arguments.language_plan, arguments.phase)
        jobs = build_language_jobs(arguments.language_campaign, platforms)
    else:
        jobs = build_jobs(arguments.phase, platforms, shards, arguments.confirmation_jobs)
    validate_configuration(arguments, jobs)
    validate_durable_paths(root, arguments, result_root)
    jobs_path = result_root / "jobs.tsv"
    write_jobs(jobs_path, jobs)
    print(f"planned {len(jobs)} jobs with maximum concurrency {arguments.max_concurrent}")
    print(f"job manifest: {jobs_path}")
    if arguments.execute:
        validate_source(root, arguments)
        validate_dependencies(root, arguments, jobs)
        validate_prerequisites(root, arguments, platforms)
        validate_aws(arguments, platforms)
        execute_jobs(arguments, root, result_root, jobs)


if __name__ == "__main__":
    main()
