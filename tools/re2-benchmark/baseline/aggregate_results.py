#!/usr/bin/env python3

"""Validate and reduce the frozen pre-review benchmark campaign."""

from __future__ import annotations

import argparse
import csv
import topology
import hashlib
import json
import math
import re
import statistics
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from acceptance import EVIDENCE_FIELDS, EXPECTED_PLATFORMS, ValidationError
import unit_dispatch
import unit_session
from row_protocol import comparison_specs, identify_scaling_groups, logical_row_id, parse_parameters

BASELINE = Path(__file__).resolve().parent
sys.path.insert(0, str(BASELINE.parent / "language"))
import released_artifact  # noqa: E402

MANIFEST_FIELDS = (
    "row_id", "shard_id", "suite", "system", "benchmark", "parameters",
    "comparator", "expected_result", "allocation_contract", "workload_checksum",
)
EVENT_FIELDS = (
    "event_type", "campaign_id", "platform", "shard_id", "replica_id",
    "instance_id", "host_epoch", "reason", "artifact_uri", "artifact_sha256", "region",
    "availability_zone", "placement_score", "failure_classification", "details",
)
CAMPAIGN_ARTIFACT_FIELDS = (
    "s3_uri", "version_id", "archive_sha256", "candidate_commit", "candidate_ref",
    "campaign_id", "source_directory", "file_count", "source_manifest_sha256",
)
SOURCE_MANIFEST_FIELDS = ("path", "size", "sha256")
DRIFT_LIMIT = 0.05
MATERIAL_LIMIT = 0.05
SERIOUS_RATIO_LOW = 0.5
SERIOUS_RATIO_HIGH = 2.0
# Each sample names its unit attempt beside its host.
UNIT_ATTEMPT_FIELDS = ("unit_id", "unit_attempt_id")
RECEIPT_NAME = "unit-receipt.tsv"
CAPACITY_NAME = "unit-capacity.txt"
SHA256 = re.compile(r"[0-9a-f]{64}")


class ReductionError(ValueError):
    pass


@dataclass(frozen=True)
class Session:
    """One accepted unit attempt; attempts are independent samples only across the replicas of one row."""
    receipt: dict[str, str]
    directory: Path
    confirmation: bool
    calibration_drift: float
    artifact_set_sha256: str
    evidence_rows: tuple[dict[str, str], ...]
    capacity: dict[str, str]

    @property
    def key(self):
        return session_key(self.receipt)

def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stable_digest(values) -> str:
    return hashlib.sha256("".join(f"{value}\n" for value in sorted(values)).encode()).hexdigest()


def session_key(row):
    return tuple(row[field] for field in ("campaign_id", "platform", "unit_id", "replica_id", "unit_attempt_id"))


def attempt_key(row):
    """The unit attempt a sample belongs to."""
    return row["unit_attempt_id"]


def unit_identity(row):
    return {field: row[field] for field in UNIT_ATTEMPT_FIELDS}


def unit_columns(fields, *extra):
    """A table names the unit and attempt after the host epoch."""
    index = fields.index("host_epoch") + 1
    return (*fields[:index], *UNIT_ATTEMPT_FIELDS, *extra, *fields[index:])


def read_tsv(path: Path, required=(), exact=None):
    try:
        input_file = path.open(newline="", encoding="utf-8")
    except OSError as error:
        raise ReductionError(f"cannot read {path}: {error}") from error
    with input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        fields = tuple(reader.fieldnames or ())
        if not fields:
            raise ReductionError(f"{path} is empty")
        duplicates = sorted(field for field, count in Counter(fields).items() if count > 1)
        if duplicates:
            raise ReductionError(f"{path} has duplicate columns: {duplicates}")
        if exact is not None and fields != tuple(exact):
            raise ReductionError(f"{path} columns must be {list(exact)}, found {list(fields)}")
        missing = sorted(set(required) - set(fields))
        if missing:
            raise ReductionError(f"{path} is missing columns: {missing}")
        rows = []
        for line_number, row in enumerate(reader, 2):
            if None in row:
                raise ReductionError(f"{path}:{line_number} has more values than columns")
            if any(row[field] == "" for field in required):
                raise ReductionError(f"{path}:{line_number} has an empty required value")
            row["_source"] = str(path)
            row["_line"] = str(line_number)
            rows.append(row)
        return rows


def read_observed_rows(path):
    return read_tsv(
        path,
        ("row_id", "shard_id", "system", "allocation_bytes", "result_checksum", "outcome"),
        unit_session.OBSERVED_FIELDS)


def write_tsv(path: Path, fields, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({field: row.get(field, "") for field in fields})


def parse_float(value, context, *, nonnegative=False):
    try:
        result = float(value)
    except ValueError as error:
        raise ReductionError(f"{context} is not numeric: {value!r}") from error
    if not math.isfinite(result) or (nonnegative and result < 0):
        raise ReductionError(f"{context} is invalid: {value!r}")
    return result


def format_number(value):
    if value is None:
        return ""
    return f"{value:.12g}"


def exceeds_drift_limit(value):
    """Compare at the same precision published in the campaign tables."""
    return float(format_number(value)) > DRIFT_LIMIT


def coefficient_of_variation(values):
    if len(values) < 2:
        return 0.0
    mean = statistics.fmean(values)
    return statistics.stdev(values) / abs(mean) if mean else math.inf


def median(values):
    return statistics.median(values) if values else None


def load_manifest(path, expected_count):
    rows = read_tsv(path, MANIFEST_FIELDS, MANIFEST_FIELDS)
    if expected_count is not None and len(rows) != expected_count:
        raise ReductionError(f"manifest must contain {expected_count} rows, found {len(rows)}")
    identifiers = [row["row_id"] for row in rows]
    if len(identifiers) != len(set(identifiers)):
        raise ReductionError("manifest contains duplicate row IDs")
    for row in rows:
        try:
            logical_row_id(row)
            parse_parameters(row["parameters"])
        except ValueError as error:
            raise ReductionError(str(error)) from error
    return rows


def load_platforms(path):
    fields = ("platform", "architecture", "instance_type", "vcpus",
              "concurrency_instance_type", "concurrency_vcpus")
    rows = read_tsv(path, fields)
    platforms = {row["platform"]: row for row in rows}
    if len(platforms) != len(rows):
        raise ReductionError("platform file contains duplicate platform identities")
    if set(platforms) != EXPECTED_PLATFORMS:
        raise ReductionError(f"platforms must be r8i and r9g; found {sorted(platforms)}")
    for name, platform in platforms.items():
        try:
            topology.validate(platform)
        except ValueError as error:
            raise ReductionError(f"platform {name}: {error}") from error
    return platforms


def load_shards(path):
    rows = read_tsv(path, ("shard_id",))
    shards = {row["shard_id"] for row in rows}
    if len(shards) != len(rows) or not shards:
        raise ReductionError("shard file contains duplicate or no shard identities")
    return shards


def load_receipt_ledger(paths, confirmation):
    fields = unit_session.UNIT_RECEIPT_FIELDS
    rows = []
    for path in paths:
        rows.extend(read_tsv(path, fields, fields))
    for row in rows:
        expected = "4" if confirmation else {"1", "2", "3"}
        if (confirmation and row["replica_id"] != expected) or (
                not confirmation and row["replica_id"] not in expected):
            raise ReductionError(f"invalid {'confirmation' if confirmation else 'primary'} replica in {row['_source']}")
    keys = [session_key(row) for row in rows]
    if len(keys) != len(set(keys)):
        raise ReductionError("receipt ledgers contain duplicate session identities")
    return rows


def verify_unit_receipt_coverage(primary, confirmation, platforms, shards, manifest_rows, manifest_digest):
    """A campaign covers its shards by the campaign acceptance rule; returns the units in scope."""
    campaign_ids = {row["campaign_id"] for row in primary + confirmation}
    if not campaign_ids:
        raise ReductionError("accepted unit ledgers contain no receipts")
    if len(campaign_ids) != 1:
        raise ReductionError(f"accepted receipts contain multiple campaign IDs: {sorted(campaign_ids)}")
    units = {
        unit_id: unit for unit_id, unit in unit_session.work_units.load(unit_session.DIRECTORY).items()
        if unit["shard_id"] in shards}
    try:
        unit_session.campaign_shards(manifest_rows, shards)
        unit_session.check_unit_campaign(
            primary, platforms, units, unit_session.row_protocol.load(unit_session.DIRECTORY / "row-protocol.tsv"),
            manifest_digest, next(iter(campaign_ids)), confirmations=confirmation)
    except ValidationError as error:
        raise ReductionError(str(error)) from error
    return units


def retained_unit_attempt(directory):
    """Whether a unit attempt sits where the controller retains it, the directory its own receipt names:
    units/<platform>/<unit>/replica-<n>/<host session>/lease-<n>."""
    parents = directory.resolve().parents
    if len(parents) <= 5 or parents[4].name != "units":
        return False
    fields = unit_session.UNIT_RECEIPT_FIELDS
    receipt = read_one(directory / RECEIPT_NAME, fields, fields)
    return unit_dispatch.unit_attempt_directory(parents[5], receipt) == directory.resolve()


def discover_session_directories(artifact_roots, session_directories):
    directories = {path.resolve() for path in session_directories}
    for root in artifact_roots:
        if not root.is_dir():
            raise ReductionError(f"artifact root does not exist: {root}")
        # A phase's unit-results/ holds the controller's staged copies of the same attempts.
        directories.update(
            path.parent.resolve() for path in root.rglob(RECEIPT_NAME) if retained_unit_attempt(path.parent))
    return sorted(directories, key=str)


def read_one(path, fields, exact=None):
    rows = read_tsv(path, fields, exact)
    if len(rows) != 1:
        raise ReductionError(f"{path} must contain one data row, found {len(rows)}")
    return rows[0]


def artifact_files(directory, evidence_rows):
    """An accepted artifact's files by logical name: its own directory, then the host files it references.

    A unit attempt references calibrations its host recorded once beside its attempts, and a chained attempt
    also references the completion marker of the lease its before calibration came from, which its controller
    retained there.
    Both are named here from that host session's directory, the attempt's parent."""
    files = sorted(
        ((str(item.relative_to(directory)), item) for item in directory.rglob("*") if item.is_file()),
        key=lambda item: item[0])
    host_artifacts = sorted(row["artifact"] for row in evidence_rows if row["verification"] == "host-sha256")
    if (directory / "unit-chain.txt").is_file():
        host_artifacts.append(unit_session.chain_predecessor_marker(directory))
    return [*files, *((f"../{artifact}", directory.parent / artifact) for artifact in host_artifacts)]


def file_set_digest(files):
    return stable_digest(f"{name}\0{path.stat().st_size}\0{sha256_file(path)}" for name, path in files)


def read_calibration(path):
    rows = read_tsv(path, ("phase", "score", "score_unit"))
    by_phase = {row["phase"]: row for row in rows}
    if len(rows) != 2 or set(by_phase) != {"before", "after"}:
        raise ReductionError(f"{path} must contain exactly before and after calibration rows")
    if by_phase["before"]["score_unit"] != by_phase["after"]["score_unit"]:
        raise ReductionError(f"{path} changes calibration units")
    before = parse_float(by_phase["before"]["score"], f"{path} before score")
    after = parse_float(by_phase["after"]["score"], f"{path} after score")
    if min(before, after) <= 0:
        raise ReductionError(f"{path} calibration scores must be positive")
    drift = abs(after - before) / min(before, after)
    for row in rows:
        if row.get("before_after_drift"):
            recorded = parse_float(row["before_after_drift"], f"{path} recorded drift", nonnegative=True)
            if not math.isclose(recorded, drift, rel_tol=1e-9, abs_tol=1e-12):
                raise ReductionError(f"{path} recorded calibration drift is incorrect")
    return drift


def read_final_capacity(path):
    properties = dict(read_properties(path))
    required = (
        "wall_seconds",
        "maximum_resident_kibibytes",
        "exit_status",
        "memory_total_bytes",
        "memory_available_before_bytes",
        "memory_available_after_bytes",
    )
    missing = [field for field in required if field not in properties]
    if missing:
        raise ReductionError(f"{path} is missing finalized capacity fields: {missing}")
    wall_seconds = parse_float(properties["wall_seconds"], f"{path} wall_seconds", nonnegative=True)
    maximum_resident = parse_float(
        properties["maximum_resident_kibibytes"],
        f"{path} maximum_resident_kibibytes",
        nonnegative=True)
    memory_total = parse_float(properties["memory_total_bytes"], f"{path} memory_total_bytes")
    memory_before = parse_float(
        properties["memory_available_before_bytes"],
        f"{path} memory_available_before_bytes")
    memory_after = parse_float(
        properties["memory_available_after_bytes"],
        f"{path} memory_available_after_bytes")
    if wall_seconds <= 0 or maximum_resident <= 0 or memory_total <= 0:
        raise ReductionError(f"{path} has non-positive capacity measurements")
    if not (0 < memory_before <= memory_total) or not (0 < memory_after <= memory_total):
        raise ReductionError(f"{path} has invalid available-memory measurements")
    if maximum_resident * 1024 > memory_total:
        raise ReductionError(f"{path} peak RSS exceeds total physical memory")
    if properties["exit_status"] != "0":
        raise ReductionError(f"{path} reports a nonzero exit status")
    return properties


def verify_unit_attempt(directory, ledger_receipt, confirmation, unit, manifest_path, manifest_by_id, release=None):
    """Reverify a unit attempt as unit acceptance does; its owned rows are its measurements.

    A release campaign's attempt must also prove each route measured the release's JAR.
    """
    fields = unit_session.UNIT_RECEIPT_FIELDS
    status_path = directory / "status.txt"
    if not status_path.is_file() or status_path.read_text(encoding="utf-8") != "status=complete\n":
        raise ReductionError(f"unit attempt does not have a complete final status: {directory}")
    artifact_receipt = read_one(directory / RECEIPT_NAME, fields, fields)
    clean_artifact = {field: artifact_receipt[field] for field in fields}
    if clean_artifact != {field: ledger_receipt[field] for field in fields}:
        raise ReductionError(f"artifact receipt does not match its ledger row: {directory}")
    try:
        # A worker host's calibrations live once in its host session's directory, the attempt's parent.
        regenerated = unit_session.validate_unit_results(directory, manifest_path, host_evidence=directory.parent)
        unit_session.check_chain_predecessor(directory, directory.parent)
        if release:
            released_artifact.validate_baseline(directory, release)
    except (OSError, ValueError, KeyError) as error:
        raise ReductionError(f"invalid unit evidence in {directory}: {error}") from error
    if regenerated != clean_artifact:
        raise ReductionError(f"unit receipt cannot be reproduced from its evidence: {directory}")
    evidence_rows = read_tsv(directory / "unit-evidence.tsv", EVIDENCE_FIELDS, EVIDENCE_FIELDS)
    observed_path = directory / "observed-rows.tsv"
    observed = read_observed_rows(observed_path)
    expected = {row_id: manifest_by_id[row_id] for row_id in unit["owned"]}
    identifiers = [row["row_id"] for row in observed]
    if len(identifiers) != len(set(identifiers)) or set(identifiers) != set(expected):
        raise ReductionError(f"artifact does not contain the exact unit rows: {directory}")
    return verify_measurements(
        directory, artifact_receipt, confirmation, evidence_rows, observed_path, observed, expected,
        [directory / "calibration.tsv"], directory / CAPACITY_NAME)


def verify_measurements(directory, artifact_receipt, confirmation, evidence_rows, observed_path, observed, expected,
                        calibration_paths, capacity_path):
    """Check one session's rows, outcomes, calibration, and finalized capacity."""
    for row in observed:
        manifest_row = expected[row["row_id"]]
        if row["shard_id"] != manifest_row["shard_id"] or row["system"] != manifest_row["system"]:
            raise ReductionError(f"observed row relabels manifest data: {row['row_id']}")
        if row["outcome"] not in {"accepted", "precision-rejected"}:
            raise ReductionError(f"observed row has an unknown outcome: {row['row_id']}")
        if not SHA256.fullmatch(row["result_checksum"]):
            raise ReductionError(f"observed row has invalid contract identity: {row['row_id']}")
        parse_float(row["score"], f"{observed_path}:{row['_line']} score", nonnegative=True)
        allocation = row["allocation_bytes"]
        if allocation not in {"not-measured", "-", ""}:
            parse_float(allocation, f"{observed_path}:{row['_line']} allocation", nonnegative=True)
    if not calibration_paths:
        raise ReductionError(f"host evidence has no routed calibration: {directory}")
    calibration_drift = max(read_calibration(path) for path in calibration_paths)
    capacity = read_final_capacity(capacity_path)
    if exceeds_drift_limit(calibration_drift):
        raise ReductionError(
            f"accepted session exceeds the 5% calibration drift gate: {directory}")
    return Session(
        artifact_receipt, directory, confirmation, calibration_drift,
        file_set_digest(artifact_files(directory, evidence_rows)), tuple(evidence_rows), capacity), observed


def load_sessions(primary_receipts, confirmation_receipts, artifact_roots, session_directories,
                  manifest_rows, campaign_units, manifest_path, release=None):
    """Load and reverify every accepted unit attempt of the campaign's units."""
    manifest_by_id = {row["row_id"]: row for row in manifest_rows}
    receipt_fields = unit_session.UNIT_RECEIPT_FIELDS
    ledgers = {session_key(row): (row, False) for row in primary_receipts}
    for row in confirmation_receipts:
        key = session_key(row)
        if key in ledgers:
            raise ReductionError("primary and confirmation ledgers overlap")
        ledgers[key] = (row, True)
    discovered = discover_session_directories(artifact_roots, session_directories)
    artifacts = {}
    for directory in discovered:
        receipt = read_one(directory / RECEIPT_NAME, receipt_fields, receipt_fields)
        key = session_key(receipt)
        if key in artifacts:
            raise ReductionError(f"duplicate accepted artifact for session {key}")
        artifacts[key] = directory
    missing = sorted(set(ledgers) - set(artifacts))
    unexpected = sorted(set(artifacts) - set(ledgers))
    if missing or unexpected:
        raise ReductionError(
            f"accepted artifact coverage mismatch: missing={missing[:10]}, "
            f"unexpected={unexpected[:10]}")
    sessions = []
    observed_by_session = {}
    for key in sorted(ledgers):
        receipt, confirmation = ledgers[key]
        session, observed = verify_unit_attempt(
            artifacts[key], receipt, confirmation, campaign_units[receipt["unit_id"]], manifest_path, manifest_by_id,
            release)
        sessions.append(session)
        observed_by_session[key] = observed
    return sessions, observed_by_session


def load_artifact_indexes(paths):
    indexes = {}
    for path in paths:
        rows = read_tsv(path, unit_session.UNIT_SESSION_FIELDS)
        for row in rows:
            key = session_key(row)
            if key in indexes:
                raise ReductionError(f"duplicate artifact index row for session {key}")
            indexes[key] = {field: value for field, value in row.items() if not field.startswith("_")}
    return indexes


def load_campaign_artifact(path, campaign_id):
    if path is None:
        return {}, {}
    row = read_one(path, CAMPAIGN_ARTIFACT_FIELDS, CAMPAIGN_ARTIFACT_FIELDS)
    if row["campaign_id"] != campaign_id:
        raise ReductionError(
            f"campaign artifact ID is {row['campaign_id']!r}, expected {campaign_id!r}")
    if not row["s3_uri"].startswith("s3://") or not row["version_id"]:
        raise ReductionError("campaign artifact must identify an immutable S3 object version")
    if not SHA256.fullmatch(row["archive_sha256"]):
        raise ReductionError("campaign artifact has an invalid archive SHA-256")
    if re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", row["candidate_commit"]) is None:
        raise ReductionError("campaign artifact has an invalid candidate commit")
    try:
        file_count = int(row["file_count"])
    except ValueError as error:
        raise ReductionError("campaign artifact file_count is not an integer") from error
    if file_count < 1:
        raise ReductionError("campaign artifact file_count must be positive")
    source_manifest_path = path.with_name(path.name + ".source-manifest.tsv")
    if sha256_file(source_manifest_path) != row["source_manifest_sha256"]:
        raise ReductionError("campaign source manifest checksum does not match archive metadata")
    source_rows = read_tsv(source_manifest_path, SOURCE_MANIFEST_FIELDS, SOURCE_MANIFEST_FIELDS)
    if len(source_rows) != file_count:
        raise ReductionError("campaign source manifest file count does not match archive metadata")
    source_manifest = {}
    for source_row in source_rows:
        artifact = source_row["path"]
        if not artifact.startswith("campaign-results/") or artifact in source_manifest:
            raise ReductionError(f"campaign source manifest has invalid or duplicate path {artifact!r}")
        if not SHA256.fullmatch(source_row["sha256"]):
            raise ReductionError(f"campaign source manifest has invalid checksum for {artifact!r}")
        try:
            size = int(source_row["size"])
        except ValueError as error:
            raise ReductionError(f"campaign source manifest has invalid size for {artifact!r}") from error
        if size < 0:
            raise ReductionError(f"campaign source manifest has invalid size for {artifact!r}")
        source_manifest[artifact] = (size, source_row["sha256"])
    return {field: row[field] for field in CAMPAIGN_ARTIFACT_FIELDS}, source_manifest


def verify_campaign_source_manifest(campaign_artifact, source_manifest, sessions):
    if not campaign_artifact:
        return
    source_directory = Path(campaign_artifact["source_directory"]).resolve()
    for session in sessions:
        try:
            relative_directory = session.directory.resolve().relative_to(source_directory)
        except ValueError as error:
            raise ReductionError(
                f"accepted session is outside the archived campaign source: {session.directory}") from error
        for name, path in artifact_files(session.directory, session.evidence_rows):
            relative = relative_directory.parent / name[3:] if name.startswith("../") else relative_directory / name
            archive_path = "campaign-results/" + relative.as_posix()
            expected = source_manifest.get(archive_path)
            actual = (path.stat().st_size, sha256_file(path))
            if expected != actual:
                raise ReductionError(
                    f"accepted host evidence is not bound by the campaign archive: {path}")


def load_events(paths, fields=EVENT_FIELDS):
    events = []
    for path in paths:
        rows = read_tsv(path, ("event_type", "reason"))
        for row in rows:
            if row["event_type"] not in {"interruption", "rejected-session", "exclusion"}:
                raise ReductionError(f"unknown campaign event type {row['event_type']!r} in {path}")
            known = {field: row.get(field, "") for field in fields[:-1]}
            extra = {field: value for field, value in row.items()
                     if not field.startswith("_") and field not in fields and value}
            details = row.get("details", "")
            if extra:
                try:
                    structured_details = json.loads(details) if details else {}
                except json.JSONDecodeError:
                    structured_details = None
                if isinstance(structured_details, dict):
                    collisions = sorted(set(structured_details) & set(extra))
                    if collisions:
                        raise ReductionError(
                            f"event details and additional columns overlap in {path}: {collisions}")
                    structured_details.update(extra)
                    details = json.dumps(structured_details, sort_keys=True, separators=(",", ":"))
                else:
                    suffix = json.dumps(extra, sort_keys=True, separators=(",", ":"))
                    details = f"{details} | additional={suffix}" if details else suffix
            known["details"] = details
            events.append(known)
    return sorted(events, key=lambda row: tuple(row[field] for field in fields))


def read_properties(path):
    if not path.exists():
        return []
    rows = []
    seen = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        key, separator, value = line.partition("=")
        if not separator or not key or key in seen:
            raise ReductionError(f"invalid metadata line {path}:{line_number}")
        seen.add(key)
        rows.append((key, value))
    return rows


def build_host_rows(sessions, observed_by_session, manifest_by_id, artifact_indexes, campaign_artifact):
    rows = []
    host_fields = (
        "campaign_id", "platform", "architecture", "instance_type", "availability_zone", "shard_id",
        "replica_id", "instance_id", "host_epoch", "confirmation", "candidate_commit",
        "engine_tree", "evidence_manifest_sha256", "row_id", "suite",
        "system", "benchmark", "parameters", "comparator", "expected_result",
        "allocation_contract", "workload_checksum", "score", "score_unit",
        "allocation_bytes", "contract_identity", "outcome",
        "calibration_drift", "artifact_set_sha256", "artifact_uri",
        "artifact_sha256", "retrieved_at", "campaign_s3_uri", "campaign_version_id",
        "campaign_archive_sha256", "session_directory",
    )
    host_fields = unit_columns(host_fields)
    for session in sessions:
        receipt = session.receipt
        index = artifact_indexes.get(session.key, {})
        for observed in observed_by_session[session.key]:
            manifest = manifest_by_id[observed["row_id"]]
            row = {
                **{field: receipt[field] for field in (
                    "campaign_id", "platform", "architecture", "instance_type", "availability_zone", "shard_id",
                    "replica_id", "instance_id", "host_epoch", "candidate_commit", "engine_tree",
                    "evidence_manifest_sha256")},
                "confirmation": "true" if session.confirmation else "false",
                **{field: manifest[field] for field in (
                    "row_id", "suite", "system", "benchmark", "parameters", "comparator",
                    "expected_result", "allocation_contract", "workload_checksum")},
                **{field: observed.get(field, "") for field in (
                    "score", "score_unit", "allocation_bytes", "outcome")},
                "contract_identity": observed["result_checksum"],
                "calibration_drift": format_number(session.calibration_drift),
                "artifact_set_sha256": session.artifact_set_sha256,
                "artifact_uri": index.get("artifact_uri", index.get("retrieval_uri", "")),
                "artifact_sha256": index.get("artifact_sha256", index.get("archive_sha256", "")),
                "retrieved_at": index.get("retrieved_at", index.get("retrieval_timestamp", "")),
                "campaign_s3_uri": campaign_artifact.get("s3_uri", ""),
                "campaign_version_id": campaign_artifact.get("version_id", ""),
                "campaign_archive_sha256": campaign_artifact.get("archive_sha256", ""),
                "session_directory": str(session.directory),
                **unit_identity(receipt),
            }
            rows.append(row)
    rows.sort(key=lambda row: (
        row["platform"], row["row_id"], int(row["replica_id"]), row["host_epoch"], attempt_key(row)))
    return host_fields, rows


def score_kind(unit):
    lower = unit.lower().replace(" ", "")
    if "/s" in lower or lower.endswith("ps") or "ops/s" in lower:
        return "throughput"
    if "/op" in lower or lower in {"ns", "us", "ms", "s", "bytes"}:
        return "cost"
    return "unknown"


def unit_scale(unit):
    lower = unit.lower().replace(" ", "")
    prefix = lower.split("/", 1)[0]
    return {"ns": 1e-9, "us": 1e-6, "ms": 1e-3, "s": 1.0}.get(prefix, 1.0)


def cost_ratio(candidate_score, candidate_unit, comparator_score, comparator_unit):
    candidate_kind = score_kind(candidate_unit)
    comparator_kind = score_kind(comparator_unit)
    if candidate_kind == "unknown" or comparator_kind == "unknown" or candidate_kind != comparator_kind:
        raise ReductionError(f"cannot compare score units {candidate_unit!r} and {comparator_unit!r}")
    candidate = candidate_score * unit_scale(candidate_unit)
    comparator = comparator_score * unit_scale(comparator_unit)
    if candidate <= 0 or comparator <= 0:
        raise ReductionError("comparator scores must be positive")
    return candidate / comparator if candidate_kind == "cost" else comparator / candidate


def build_comparisons(host_rows, manifest_rows):
    """Pair each candidate sample with the comparators measured in its own unit attempt."""
    by_host_row = {(row["platform"], attempt_key(row), row["row_id"]): row for row in host_rows}
    hosts_by_row_id = defaultdict(list)
    for row in host_rows:
        hosts_by_row_id[row["row_id"]].append(row)
    fields = (
        "platform", "shard_id", "suite", "replica_id", "host_epoch",
        "confirmation", "comparison_id", "logical_row_id", "candidate_row_id",
        "candidate_system", "comparator_system", "comparator_row_ids", "candidate_score",
        "comparator_score", "score_unit", "ratio", "direction", "material",
        "serious_outlier", "outcome",
    )
    fields = unit_columns(fields)
    rows = []
    for logical_id, candidate, comparator_system, comparators in comparison_specs(manifest_rows):
        for host in hosts_by_row_id[candidate["row_id"]]:
            comparator_hosts = [
                by_host_row.get((host["platform"], attempt_key(host), row["row_id"]))
                for row in comparators
            ]
            if any(row is None for row in comparator_hosts):
                raise ReductionError(
                    f"comparator is missing for {candidate['row_id']} "
                    f"on the same unit attempt {attempt_key(host)}")
            outcome = (
                "accepted"
                if host.get("outcome", "accepted") == "accepted" and
                all(row.get("outcome", "accepted") == "accepted" for row in comparator_hosts)
                else "precision-rejected"
            )
            score_units = {row["score_unit"] for row in comparator_hosts}
            if len(score_units) != 1:
                raise ReductionError(f"comparators change score units for {candidate['row_id']}")
            comparator_score = median([float(row["score"]) for row in comparator_hosts])
            ratio = cost_ratio(
                float(host["score"]), host["score_unit"], comparator_score,
                comparator_hosts[0]["score_unit"])
            material = abs(ratio - 1.0) > MATERIAL_LIMIT
            direction = (
                "faster" if ratio < 1.0 - MATERIAL_LIMIT else
                "slower" if ratio > 1.0 + MATERIAL_LIMIT else
                "parity")
            rows.append({
                "platform": host["platform"], "shard_id": host["shard_id"], "suite": host["suite"],
                "replica_id": host["replica_id"],
                "host_epoch": host["host_epoch"], **unit_identity(host), "confirmation": host["confirmation"],
                "comparison_id": f"{candidate['row_id']}::{comparator_system}",
                "logical_row_id": logical_id, "candidate_row_id": candidate["row_id"],
                "candidate_system": candidate["system"], "comparator_system": comparator_system,
                "comparator_row_ids": ",".join(row["row_id"] for row in comparators),
                "candidate_score": host["score"], "comparator_score": format_number(comparator_score),
                "score_unit": host["score_unit"], "ratio": format_number(ratio),
                "direction": direction, "material": str(material).lower(),
                "serious_outlier": str(ratio <= SERIOUS_RATIO_LOW or ratio >= SERIOUS_RATIO_HIGH).lower(),
                "outcome": outcome,
            })
    rows.sort(key=lambda row: (
        row["platform"], row["comparison_id"], int(row["replica_id"]), row["host_epoch"], attempt_key(row)))
    return fields, rows


def scaling_slope(points):
    values = [(math.log(size), math.log(score)) for size, score in points if size > 0 and score > 0]
    if len(values) < 3 or len({value[0] for value in values}) < 3:
        return None
    mean_x = statistics.fmean(value[0] for value in values)
    mean_y = statistics.fmean(value[1] for value in values)
    denominator = sum((value[0] - mean_x) ** 2 for value in values)
    return sum((value[0] - mean_x) * (value[1] - mean_y) for value in values) / denominator


def scaling_class(slope):
    if slope is None:
        return "unclassified"
    if slope < -0.25:
        return "decreasing"
    if slope < 0.25:
        return "constant"
    if slope < 0.75:
        return "sublinear"
    if slope <= 1.25:
        return "linear"
    return "superlinear"


def build_scaling(host_rows, manifest_rows):
    """One signature per scaling group and unit attempt; units hold whole groups."""
    by_host_row = {(row["platform"], attempt_key(row), row["row_id"]): row for row in host_rows}
    host_identity = {(row["platform"], attempt_key(row)): row for row in host_rows}
    fields = (
        "platform", "shard_id", "suite", "system", "benchmark", "scaling_group",
        "size_parameter", "other_parameters", "replica_id", "host_epoch", "confirmation",
        "point_count", "minimum_size", "maximum_size", "slope", "classification", "row_ids",
    )
    fields = unit_columns(fields)
    rows = []
    membership = defaultdict(list)
    for group_id, (identity, points) in identify_scaling_groups(manifest_rows).items():
        shard, suite, system, benchmark, size_parameter, other_parameters = identity
        point_ids = [row["row_id"] for row, _ in points]
        for platform, attempt in sorted(host_identity):
            host_points = [by_host_row.get((platform, attempt, row["row_id"])) for row, _ in points]
            if all(row is None for row in host_points):
                continue
            if any(row is None for row in host_points):
                raise ReductionError(
                    f"unit attempt {attempt} has incomplete scaling group {group_id}")
            if any(row.get("outcome", "accepted") != "accepted" for row in host_points):
                continue
            size_scores = [(size, float(host_row["score"])) for (_, size), host_row in zip(points, host_points)]
            slope = scaling_slope(size_scores)
            host = host_points[0]
            row = {
                "platform": platform, "shard_id": shard, "suite": suite, "system": system,
                "benchmark": benchmark, "scaling_group": group_id, "size_parameter": size_parameter,
                "other_parameters": other_parameters, "replica_id": host["replica_id"],
                "host_epoch": host["host_epoch"], **unit_identity(host), "confirmation": host["confirmation"],
                "point_count": str(len(points)),
                "minimum_size": format_number(min(size for _, size in points)),
                "maximum_size": format_number(max(size for _, size in points)),
                "slope": format_number(slope), "classification": scaling_class(slope),
                "row_ids": ",".join(point_ids),
            }
            rows.append(row)
            for row_id in point_ids:
                membership[(platform, row_id)].append((group_id, row["classification"]))
    rows.sort(key=lambda row: (
        row["platform"], row["scaling_group"], int(row["replica_id"]), row["host_epoch"], attempt_key(row)))
    mismatches = set()
    aggregate_classes = {}
    for (platform, group_id), group_rows in _group(rows, "platform", "scaling_group"):
        classes = {row["classification"] for row in group_rows if row["classification"] != "unclassified"}
        aggregate_classes[(platform, group_id)] = next(iter(classes)) if len(classes) == 1 else "mixed"
        if len(classes) > 1:
            mismatches.add((platform, group_id))
    return fields, rows, membership, mismatches, aggregate_classes


def _group(rows, *fields):
    grouped = defaultdict(list)
    for row in rows:
        grouped[tuple(row[field] for field in fields)].append(row)
    return sorted(grouped.items())


def allocation_value(value):
    return None if value in {"", "-", "not-measured"} else float(value)


def build_row_aggregates(host_rows, scaling_membership, scaling_mismatches, scaling_classes):
    fields = (
        "platform", "row_id", "shard_id", "suite", "system", "benchmark",
        "parameters", "comparator", "allocation_contract", "primary_host_count",
        "confirmation_host_count", "host_count", "score_unit", "primary_median_score",
        "median_score", "minimum_score", "maximum_score", "primary_host_cv", "host_cv",
        "confidence_interval_status",
        "median_allocation_bytes", "maximum_calibration_drift",
        "contract_identity_status", "scaling_groups", "scaling_class", "unresolved",
        "unresolved_reasons",
    )
    rows = []
    gaps = []
    for (platform, row_id), hosts in _group(host_rows, "platform", "row_id"):
        primary = [row for row in hosts if row["confirmation"] == "false"]
        confirmation = [row for row in hosts if row["confirmation"] == "true"]
        if len(primary) != 3 or len(confirmation) > 1:
            raise ReductionError(f"row {row_id} on {platform} has invalid host counts")
        units = {row["score_unit"] for row in hosts}
        if len(units) != 1:
            raise ReductionError(f"row {row_id} on {platform} changes score units")
        scores = [float(row["score"]) for row in hosts]
        primary_scores = [float(row["score"]) for row in primary]
        allocations = [allocation_value(row["allocation_bytes"]) for row in hosts]
        measured_allocations = [value for value in allocations if value is not None]
        reasons = []
        if any(row["outcome"] != "accepted" for row in hosts):
            reasons.append("precision-rejected")
        host_cv = coefficient_of_variation(scores)
        if exceeds_drift_limit(host_cv):
            reasons.append("host-cv-above-5-percent")
        calibration = max(float(row["calibration_drift"]) for row in hosts)
        first = hosts[0]
        if first["allocation_contract"] == "allocation-free" and any(value > 0 for value in measured_allocations):
            reasons.append("allocation-free-row-allocated")
        contract_identities = {row["contract_identity"] for row in hosts}
        identity_status = "consistent" if len(contract_identities) == 1 else "mismatch"
        if identity_status == "mismatch":
            reasons.append("contract-identity-mismatch")
        memberships = scaling_membership.get((platform, row_id), [])
        if any((platform, group_id) in scaling_mismatches for group_id, _ in memberships):
            reasons.append("scaling-class-mismatch")
        group_ids = sorted({group_id for group_id, _ in memberships})
        classes = sorted({scaling_classes[(platform, group_id)] for group_id in group_ids})
        row = {
            "platform": platform, "row_id": row_id, "shard_id": first["shard_id"],
            "suite": first["suite"], "system": first["system"],
            "benchmark": first["benchmark"], "parameters": first["parameters"], "comparator": first["comparator"],
            "allocation_contract": first["allocation_contract"], "primary_host_count": str(len(primary)),
            "confirmation_host_count": str(len(confirmation)), "host_count": str(len(hosts)),
            "score_unit": next(iter(units)), "primary_median_score": format_number(median(primary_scores)),
            "median_score": format_number(median(scores)), "minimum_score": format_number(min(scores)),
            "maximum_score": format_number(max(scores)),
            "primary_host_cv": format_number(coefficient_of_variation(primary_scores)),
            "host_cv": format_number(host_cv),
            "confidence_interval_status": "not-supported-with-3-or-4-independent-hosts",
            "median_allocation_bytes": format_number(median(measured_allocations)),
            "maximum_calibration_drift": format_number(calibration),
            "contract_identity_status": identity_status, "scaling_groups": ",".join(group_ids),
            "scaling_class": ",".join(classes) if classes else "not-identifiable",
            "unresolved": str(bool(reasons)).lower(), "unresolved_reasons": ",".join(sorted(set(reasons))),
        }
        rows.append(row)
        for reason in sorted(set(reasons)):
            gaps.append(gap_row("unresolved", "row", platform, first["shard_id"], row_id, "", "", reason,
                                gap_observed(reason, row), gap_threshold(reason), "Unresolved by Phase 5 rules"))
    rows.sort(key=lambda row: (row["platform"], row["row_id"]))
    return fields, rows, gaps


def build_comparison_aggregates(comparisons):
    fields = (
        "platform", "comparison_id", "shard_id", "suite", "candidate_row_id",
        "candidate_system", "comparator_system", "primary_host_count", "confirmation_host_count",
        "host_count", "primary_median_ratio", "median_ratio", "minimum_ratio", "maximum_ratio",
        "primary_ratio_host_cv", "ratio_host_cv", "confidence_interval_status",
        "faster_hosts", "parity_hosts", "slower_hosts", "direction",
        "serious_outlier", "unresolved", "unresolved_reasons",
    )
    rows = []
    gaps = []
    for (platform, comparison_id), hosts in _group(comparisons, "platform", "comparison_id"):
        primary = [row for row in hosts if row["confirmation"] == "false"]
        confirmation = [row for row in hosts if row["confirmation"] == "true"]
        if len(primary) != 3 or len(confirmation) > 1:
            raise ReductionError(f"comparison {comparison_id} on {platform} has invalid host counts")
        ratios = [float(row["ratio"]) for row in hosts]
        primary_ratios = [float(row["ratio"]) for row in primary]
        primary_ratio_cv = coefficient_of_variation(primary_ratios)
        ratio_cv = coefficient_of_variation(ratios)
        material_directions = {row["direction"] for row in hosts if row["material"] == "true"}
        reasons = []
        if any(row["outcome"] != "accepted" for row in hosts):
            reasons.append("precision-rejected")
        if material_directions >= {"faster", "slower"}:
            reasons.append("host-direction-disagreement")
        effective_ratio_cv = ratio_cv if confirmation else primary_ratio_cv
        if exceeds_drift_limit(effective_ratio_cv):
            reasons.append("ratio-host-cv-above-5-percent")
        value = median(ratios)
        serious = value <= SERIOUS_RATIO_LOW or value >= SERIOUS_RATIO_HIGH
        first = hosts[0]
        counts = Counter(row["direction"] for row in hosts)
        direction = "faster" if value < 1.0 - MATERIAL_LIMIT else "slower" if value > 1.0 + MATERIAL_LIMIT else "parity"
        row = {
            "platform": platform, "comparison_id": comparison_id, "shard_id": first["shard_id"],
            "suite": first["suite"],
            "candidate_row_id": first["candidate_row_id"], "candidate_system": first["candidate_system"],
            "comparator_system": first["comparator_system"], "primary_host_count": str(len(primary)),
            "confirmation_host_count": str(len(confirmation)), "host_count": str(len(hosts)),
            "primary_median_ratio": format_number(median(primary_ratios)), "median_ratio": format_number(value),
            "minimum_ratio": format_number(min(ratios)), "maximum_ratio": format_number(max(ratios)),
            "primary_ratio_host_cv": format_number(primary_ratio_cv),
            "ratio_host_cv": format_number(ratio_cv),
            "confidence_interval_status": "not-supported-with-3-or-4-independent-hosts",
            "faster_hosts": str(counts["faster"]), "parity_hosts": str(counts["parity"]),
            "slower_hosts": str(counts["slower"]), "direction": direction,
            "serious_outlier": str(serious).lower(), "unresolved": str(bool(reasons)).lower(),
            "unresolved_reasons": ",".join(reasons),
        }
        rows.append(row)
        if "host-direction-disagreement" in reasons:
            gaps.append(gap_row("unresolved", "comparison", platform, first["shard_id"],
                                first["candidate_row_id"], comparison_id, "", "host-direction-disagreement",
                                f"host directions={dict(sorted(counts.items()))}", "material ratio outside 0.95-1.05",
                                "Hosts disagree on comparison direction"))
        if "precision-rejected" in reasons:
            gaps.append(gap_row(
                "unresolved", "comparison", platform, first["shard_id"],
                first["candidate_row_id"], comparison_id, "", "precision-rejected",
                "at least one exact same-host row exceeded the precision gate",
                "all exact same-host rows accepted",
                "A candidate or comparator measurement exceeded the 5% relative standard-error ceiling"))
        if "ratio-host-cv-above-5-percent" in reasons:
            gaps.append(gap_row(
                "unresolved", "comparison", platform, first["shard_id"],
                first["candidate_row_id"], comparison_id, "", "ratio-host-cv-above-5-percent",
                f"comparison ratio CV={format_number(effective_ratio_cv)}",
                "comparison ratio CV <= 0.05",
                "Same-host candidate/comparator ratios remain unstable across hosts"))
        if serious:
            gaps.append(gap_row("serious", "comparison", platform, first["shard_id"],
                                first["candidate_row_id"], comparison_id, "", "serious-performance-outlier",
                                f"median Java/comparator={format_number(value)}", "ratio <= 0.5 or >= 2.0",
                                "Preserved separately from general classes"))
    rows.sort(key=lambda row: (row["platform"], row["comparison_id"]))
    return fields, rows, gaps


def percentile(values, fraction):
    values = sorted(values)
    if not values:
        return None
    position = (len(values) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return values[lower]
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def build_classes(comparison_aggregates):
    fields = (
        "platform", "suite", "candidate_system", "comparator_system",
        "comparison_count", "median_ratio", "p10_ratio", "p90_ratio", "faster_count",
        "parity_count", "slower_count", "unresolved_count",
    )
    general = [row for row in comparison_aggregates if row["serious_outlier"] == "false"]
    rows = []
    for key, values in _group(general, "platform", "suite", "candidate_system", "comparator_system"):
        ratios = [float(row["median_ratio"]) for row in values]
        counts = Counter(row["direction"] for row in values)
        rows.append({
            "platform": key[0], "suite": key[1], "candidate_system": key[2],
            "comparator_system": key[3], "comparison_count": str(len(values)),
            "median_ratio": format_number(median(ratios)), "p10_ratio": format_number(percentile(ratios, 0.10)),
            "p90_ratio": format_number(percentile(ratios, 0.90)), "faster_count": str(counts["faster"]),
            "parity_count": str(counts["parity"]), "slower_count": str(counts["slower"]),
            "unresolved_count": str(sum(row["unresolved"] == "true" for row in values)),
        })
    return fields, rows


def build_confirmation_jobs(row_aggregates, host_rows, comparisons, scaling, sessions):
    """Select each unstable platform/unit not yet confirmed."""
    def scope(row):
        return row["platform"], row["shard_id"], row["unit_id"]

    scope_by_row = {(row["platform"], row["row_id"]): scope(row) for row in host_rows}
    eligible = set()
    for row in row_aggregates:
        row_scope = scope_by_row[(row["platform"], row["row_id"])]
        if "precision-rejected" in row["unresolved_reasons"].split(","):
            eligible.add(row_scope)
        if exceeds_drift_limit(float(row["primary_host_cv"])):
            eligible.add(row_scope)

    for (platform, row_id), rows in _group(host_rows, "platform", "row_id"):
        primary = [row for row in rows if row["confirmation"] == "false"]
        if len({row["contract_identity"] for row in primary}) > 1:
            eligible.add(scope(primary[0]))

    for (platform, comparison_id), rows in _group(comparisons, "platform", "comparison_id"):
        primary_ratios = [
            float(row["ratio"])
            for row in rows
            if row["confirmation"] == "false"
        ]
        if exceeds_drift_limit(coefficient_of_variation(primary_ratios)):
            eligible.add(scope(rows[0]))
        primary_directions = {
            row["direction"] for row in rows
            if row["confirmation"] == "false" and row["material"] == "true"
        }
        if primary_directions >= {"faster", "slower"}:
            eligible.add(scope(rows[0]))

    for (platform, scaling_group), rows in _group(scaling, "platform", "scaling_group"):
        primary_classes = {
            row["classification"] for row in rows
            if row["confirmation"] == "false" and row["classification"] != "unclassified"
        }
        if len(primary_classes) > 1:
            eligible.add(scope(rows[0]))

    already_confirmed = {scope(session.receipt) for session in sessions if session.confirmation}
    eligible -= already_confirmed
    platform_order = {platform: index for index, platform in enumerate(("r8i", "r9g"))}
    fields = ("platform", "shard_id", "unit_id")
    rows = [
        dict(zip(fields, value))
        for value in sorted(eligible, key=lambda value: (platform_order.get(value[0], 99), *value[1:]))
    ]
    return fields, rows


def gap_observed(reason, row):
    mapping = {
        "precision-rejected": "at least one host exceeded the precision gate",
        "host-cv-above-5-percent": f"CV={row['host_cv']}",
        "allocation-free-row-allocated": f"median allocation={row['median_allocation_bytes']} B/op",
        "contract-identity-mismatch": "multiple contract identities",
        "scaling-class-mismatch": f"classes={row['scaling_class']}",
    }
    return mapping[reason]


def gap_threshold(reason):
    return {
        "precision-rejected": "all host rows accepted",
        "host-cv-above-5-percent": "CV <= 0.05",
        "allocation-free-row-allocated": "0 B/op",
        "contract-identity-mismatch": "one contract identity",
        "scaling-class-mismatch": "one class across hosts",
    }[reason]


def gap_row(severity, scope, platform, shard, row_id, comparison_id, host_epoch,
            condition, observed, threshold, details, status="open"):
    identity = "\0".join((scope, platform, shard, row_id, comparison_id, host_epoch, condition))
    return {
        "gap_id": hashlib.sha256(identity.encode()).hexdigest()[:16], "severity": severity,
        "scope": scope, "platform": platform, "shard_id": shard, "row_id": row_id,
        "comparison_id": comparison_id, "host_epoch": host_epoch, "condition": condition,
        "observed": observed, "threshold": threshold, "details": details, "status": status,
    }


def event_gaps(events):
    rows = []
    for event in events:
        severity = "recorded"
        status = "recorded"
        scope = event["event_type"]
        rows.append(gap_row(
            severity, scope, event["platform"], event["shard_id"], "", "",
            event["host_epoch"], event["event_type"], event["reason"], "n/a", event["details"], status))
    return rows


def build_inventory(sessions, artifact_indexes, campaign_artifact):
    fields = (
        "campaign_id", "platform", "shard_id", "replica_id", "host_epoch", "confirmation",
        "logical_name", "local_path", "size_bytes", "sha256", "artifact_set_sha256",
        "artifact_uri", "artifact_sha256", "retrieved_at", "campaign_s3_uri",
        "campaign_version_id", "campaign_archive_sha256", "candidate_commit", "candidate_ref",
        "candidate_archive_sha256", "engine_tree", "evidence_manifest_sha256",
        "campaign_source_directory", "campaign_file_count", "campaign_source_manifest_sha256",
    )
    fields = unit_columns(fields)
    rows = []
    for session in sessions:
        index = artifact_indexes.get(session.key, {})
        for name, path in artifact_files(session.directory, session.evidence_rows):
            rows.append({
                **{field: session.receipt[field] for field in (
                    "campaign_id", "platform", "shard_id", "replica_id", "host_epoch")},
                **unit_identity(session.receipt),
                "confirmation": str(session.confirmation).lower(), "logical_name": name,
                "local_path": str(path), "size_bytes": str(path.stat().st_size), "sha256": sha256_file(path),
                "artifact_set_sha256": session.artifact_set_sha256,
                "artifact_uri": index.get("artifact_uri", index.get("retrieval_uri", "")),
                "artifact_sha256": index.get("artifact_sha256", index.get("archive_sha256", "")),
                "retrieved_at": index.get("retrieved_at", index.get("retrieval_timestamp", "")),
                "campaign_s3_uri": campaign_artifact.get("s3_uri", ""),
                "campaign_version_id": campaign_artifact.get("version_id", ""),
                "campaign_archive_sha256": campaign_artifact.get("archive_sha256", ""),
                "candidate_commit": session.receipt["candidate_commit"],
                "candidate_ref": campaign_artifact.get("candidate_ref", ""),
                "candidate_archive_sha256": session.receipt["candidate_archive_sha256"],
                "engine_tree": session.receipt["engine_tree"],
                "evidence_manifest_sha256": session.receipt["evidence_manifest_sha256"],
                "campaign_source_directory": campaign_artifact.get("source_directory", ""),
                "campaign_file_count": campaign_artifact.get("file_count", ""),
                "campaign_source_manifest_sha256": campaign_artifact.get("source_manifest_sha256", ""),
            })
    rows.sort(key=lambda row: (
        row["platform"], row["shard_id"], int(row["replica_id"]),
        row["host_epoch"], attempt_key(row), row["logical_name"]))
    return fields, rows


def build_metadata(sessions, artifact_indexes, campaign_artifact):
    fields = unit_columns(("platform", "shard_id", "replica_id", "host_epoch", "source", "key", "value"))
    rows = []
    for session in sessions:
        prefix = {
            **{field: session.receipt[field] for field in ("platform", "shard_id", "replica_id", "host_epoch")},
            **unit_identity(session.receipt)}
        for key, value in read_properties(session.directory / "environment-manifest.txt"):
            rows.append({**prefix, "source": "environment-manifest.txt", "key": key, "value": value})
        metadata_artifacts = sorted(
            row["artifact"] for row in session.evidence_rows
            if row["artifact"].endswith("/run-metadata.txt"))
        for artifact in metadata_artifacts:
            for key, value in read_properties(session.directory / artifact):
                rows.append({**prefix, "source": artifact, "key": key, "value": value})
        for key in ("candidate_commit", "candidate_archive_sha256", "engine_tree", "evidence_manifest_sha256"):
            rows.append({**prefix, "source": RECEIPT_NAME, "key": key,
                         "value": session.receipt[key]})
        for key, value in sorted(artifact_indexes.get(session.key, {}).items()):
            if not key.startswith("_"):
                rows.append({**prefix, "source": "artifact-index", "key": key, "value": value})
        for key, value in sorted(campaign_artifact.items()):
            rows.append({**prefix, "source": "campaign-artifact", "key": key, "value": value})
    rows.sort(key=lambda row: tuple(row[field] for field in fields))
    return fields, rows


def build_capacity(sessions):
    # A unit attempt's capacity is that of its own unit-capacity.txt.
    fields = (*unit_columns((
        "campaign_id", "platform", "shard_id", "replica_id", "host_epoch", "confirmation",
        "wall_seconds", "maximum_resident_kibibytes", "memory_total_bytes",
        "memory_available_before_bytes", "memory_available_after_bytes", "exit_status",
    )), "unit_capacity_sha256")
    rows = []
    for session in sessions:
        rows.append({
            **{field: session.receipt[field] for field in (
                "campaign_id", "platform", "shard_id", "replica_id", "host_epoch")},
            **unit_identity(session.receipt),
            "confirmation": str(session.confirmation).lower(),
            **{field: session.capacity[field] for field in (
                "wall_seconds", "maximum_resident_kibibytes", "memory_total_bytes",
                "memory_available_before_bytes", "memory_available_after_bytes", "exit_status")},
            "unit_capacity_sha256": sha256_file(session.directory / CAPACITY_NAME),
        })
    rows.sort(key=lambda row: (
        row["platform"], row["shard_id"], int(row["replica_id"]), row["host_epoch"], attempt_key(row)))
    return fields, rows


def write_output_checksums(output_dir, names):
    fields = ("file", "size_bytes", "sha256")
    rows = []
    for name in sorted(names):
        path = output_dir / name
        rows.append({"file": name, "size_bytes": str(path.stat().st_size), "sha256": sha256_file(path)})
    write_tsv(output_dir / "output-checksums.tsv", fields, rows)


def aggregate(arguments):
    manifest_rows = load_manifest(arguments.manifest, arguments.expected_manifest_rows)
    manifest_digest = sha256_file(arguments.manifest)
    platforms = load_platforms(arguments.platforms)
    shards = load_shards(arguments.shards)
    if getattr(arguments, 'selected_shards', None) is not None:
        if {row['shard_id'] for row in manifest_rows} != shards:
            raise ReductionError('manifest/shard mismatch before selection')
        selected = set(arguments.selected_shards)
        if (not selected or not selected <= shards or
                len(selected) != len(arguments.selected_shards)):
            raise ReductionError('invalid or duplicate selected shards')
        shards = selected
        manifest_rows = [row for row in manifest_rows if row['shard_id'] in shards]
    primary = load_receipt_ledger(arguments.primary_receipts, False)
    confirmation = load_receipt_ledger(arguments.confirmation_receipts, True)
    # A phase whose correctness stage rejected any attempt is not reduced.
    for path in arguments.primary_receipts + arguments.confirmation_receipts:
        try:
            unit_session.check_semantic_rejections(path.parent)
        except ValidationError as error:
            raise ReductionError(str(error)) from error
    campaign_units = verify_unit_receipt_coverage(
        primary, confirmation, platforms, shards, manifest_rows, manifest_digest)
    session_fields = unit_session.UNIT_SESSION_FIELDS
    event_fields = unit_columns(EVENT_FIELDS)
    version = getattr(arguments, "release_version", "")
    sessions, observed = load_sessions(
        primary, confirmation, arguments.artifacts_root, arguments.session_dir,
        manifest_rows, campaign_units, arguments.manifest,
        released_artifact.manifest(BASELINE.parents[2], version) if version else None)
    indexes = load_artifact_indexes(arguments.artifact_index)
    unknown_indexes = sorted(set(indexes) - {session.key for session in sessions})
    if unknown_indexes:
        raise ReductionError(f"artifact index contains unknown accepted sessions: {unknown_indexes[:10]}")
    for session in sessions:
        index = indexes.get(session.key)
        if index and any(index[field] != session.receipt[field] for field in session_fields):
            raise ReductionError(f"artifact index identity does not match accepted session {session.key}")
    campaign_artifact, campaign_source_manifest = load_campaign_artifact(
        arguments.campaign_artifact, primary[0]["campaign_id"])
    if campaign_artifact:
        candidate_commits = {session.receipt["candidate_commit"] for session in sessions}
        if candidate_commits != {campaign_artifact["candidate_commit"]}:
            raise ReductionError(
                "accepted session candidate identity does not match campaign archive metadata: "
                f"sessions={sorted(candidate_commits)}, archive={campaign_artifact['candidate_commit']!r}")
        verify_campaign_source_manifest(campaign_artifact, campaign_source_manifest, sessions)
    events = load_events(arguments.events, event_fields)
    manifest_by_id = {row["row_id"]: row for row in manifest_rows}
    host_fields, host_rows = build_host_rows(
        sessions, observed, manifest_by_id, indexes, campaign_artifact)
    comparison_fields, comparisons = build_comparisons(host_rows, manifest_rows)
    scaling_fields, scaling, membership, scaling_mismatches, scaling_classes = build_scaling(
        host_rows, manifest_rows)
    aggregate_fields, row_aggregates, row_gaps = build_row_aggregates(
        host_rows, membership, scaling_mismatches, scaling_classes)
    comparison_aggregate_fields, comparison_aggregates, comparison_gaps = build_comparison_aggregates(comparisons)
    class_fields, classes = build_classes(comparison_aggregates)
    confirmation_fields, confirmation_jobs = build_confirmation_jobs(
        row_aggregates, host_rows, comparisons, scaling, sessions)
    inventory_fields, inventory = build_inventory(sessions, indexes, campaign_artifact)
    metadata_fields, metadata = build_metadata(sessions, indexes, campaign_artifact)
    capacity_fields, capacity = build_capacity(sessions)
    gaps = row_gaps + comparison_gaps + event_gaps(events)
    gaps.sort(key=lambda row: (row["severity"], row["platform"], row["shard_id"], row["row_id"], row["gap_id"]))
    gap_fields = (
        "gap_id", "severity", "scope", "platform", "shard_id", "row_id", "comparison_id",
        "host_epoch", "condition", "observed", "threshold", "details", "status",
    )
    serious = [row for row in comparison_aggregates if row["serious_outlier"] == "true"]
    outputs = {
        "host-rows.tsv": (host_fields, host_rows),
        "row-aggregates.tsv": (aggregate_fields, row_aggregates),
        "host-comparator-ratios.tsv": (comparison_fields, comparisons),
        "comparison-aggregates.tsv": (comparison_aggregate_fields, comparison_aggregates),
        "general-classes.tsv": (class_fields, classes),
        "serious-outliers.tsv": (comparison_aggregate_fields, serious),
        "scaling.tsv": (scaling_fields, scaling),
        "gap-ledger.tsv": (gap_fields, gaps),
        "events.tsv": (event_fields, events),
        "artifact-inventory.tsv": (inventory_fields, inventory),
        "metadata.tsv": (metadata_fields, metadata),
        "capacity.tsv": (capacity_fields, capacity),
        "confirmation-jobs.tsv": (confirmation_fields, confirmation_jobs),
    }
    arguments.output_dir.mkdir(parents=True, exist_ok=True)
    for name, (fields, rows) in outputs.items():
        write_tsv(arguments.output_dir / name, fields, rows)
    summary = {
        "schema_version": 1,
        "campaign_id": primary[0]["campaign_id"],
        "manifest_rows": len(manifest_rows),
        "manifest_sha256": manifest_digest,
        "platforms": [name for name in ("r8i", "r9g") if name in platforms],
        "shards": sorted(shards),
        "primary_sessions": len(primary),
        "confirmation_sessions": len(confirmation),
        "host_rows": len(host_rows),
        "same_host_ratios": len(comparisons),
        "comparison_aggregates": len(comparison_aggregates),
        "serious_outliers": len(serious),
        "unresolved_rows": sum(row["unresolved"] == "true" for row in row_aggregates),
        "unresolved_comparisons": sum(row["unresolved"] == "true" for row in comparison_aggregates),
        "events": len(events),
        "confirmation_jobs": len(confirmation_jobs),
        "campaign_artifact": campaign_artifact,
        # The release whose JAR every attempt proved it measured; empty when none was required.
        "release_version": version,
        "ratio_definition": "normalized Java cost divided by comparator cost; below 1 is faster",
    }
    (arguments.output_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    write_output_checksums(arguments.output_dir, list(outputs) + ["summary.json"])
    return summary


def parser():
    directory = Path(__file__).resolve().parent
    argument_parser = argparse.ArgumentParser(description=__doc__)
    argument_parser.add_argument("--manifest", type=Path, default=directory / "rows.tsv")
    argument_parser.add_argument("--platforms", type=Path, default=directory / "platforms.tsv")
    argument_parser.add_argument("--shards", type=Path, default=directory / "shards.tsv")
    argument_parser.add_argument("--shard", action="append", dest="selected_shards",
                                 help="reduce only these baseline shards; retain the full manifest identity")
    argument_parser.add_argument("--primary-receipts", type=Path, action="append", required=True)
    argument_parser.add_argument("--confirmation-receipts", type=Path, action="append", default=[])
    argument_parser.add_argument("--artifacts-root", type=Path, action="append", default=[])
    argument_parser.add_argument("--session-dir", type=Path, action="append", default=[])
    argument_parser.add_argument("--events", type=Path, action="append", default=[])
    argument_parser.add_argument("--artifact-index", type=Path, action="append", default=[])
    argument_parser.add_argument("--campaign-artifact", type=Path)
    argument_parser.add_argument("--output-dir", type=Path, required=True)
    argument_parser.add_argument("--expected-manifest-rows", type=int, default=449)
    argument_parser.add_argument("--release-version", default="",
                                 help="require every attempt to prove it measured this release's JAR")
    return argument_parser


def main():
    argument_parser = parser()
    arguments = argument_parser.parse_args()
    if not arguments.artifacts_root and not arguments.session_dir:
        argument_parser.error("at least one --artifacts-root or --session-dir is required")
    try:
        summary = aggregate(arguments)
    except (OSError, ReductionError) as error:
        argument_parser.error(str(error))
    print(json.dumps(summary, sort_keys=True))


if __name__ == "__main__":
    main()
