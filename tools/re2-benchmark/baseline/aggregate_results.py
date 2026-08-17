#!/usr/bin/env python3

"""Validate and reduce the frozen pre-review benchmark campaign."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import re
import statistics
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from acceptance import (
    EVIDENCE_FIELDS,
    RECEIPT_FIELDS,
    SCHEMA_VERSION,
    SESSION_FIELDS,
    ValidationError,
    evidence_candidate_identity,
    normalize_heap_size,
    verify_evidence_manifest,
)
import rebar_joni_applicability

MANIFEST_FIELDS = (
    "row_id", "shard_id", "suite", "system", "benchmark", "parameters",
    "comparator", "expected_result", "allocation_contract", "workload_checksum",
)
OBSERVED_FIELDS = (
    "row_id", "shard_id", "system", "score", "score_unit", "allocation_bytes",
    "result_checksum", "outcome",
)
REBAR_OUTCOME_FIELDS = (
    "row_id", "suite", "system", "benchmark", "model", "outcome", "detail",
)
EVENT_FIELDS = (
    "event_type", "campaign_id", "platform", "shard_id", "replica_id",
    "instance_id", "host_epoch", "reason", "artifact_uri", "artifact_sha256", "details",
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
SIZE_KEY = re.compile(r"(?:size|length|count|captures?|groups?|repetitions?)$", re.IGNORECASE)
SHA256 = re.compile(r"[0-9a-f]{64}")


class ReductionError(ValueError):
    pass


@dataclass(frozen=True)
class Session:
    receipt: dict[str, str]
    directory: Path
    confirmation: bool
    calibration_drift: float
    native_drift: float | None
    native_drift_by_benchmark: dict[str, float]
    artifact_set_sha256: str
    evidence_rows: tuple[dict[str, str], ...]
    capacity: dict[str, str]
    rebar_outcomes: tuple[dict[str, str], ...]

    @property
    def key(self):
        return session_key(self.receipt)


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stable_digest(values) -> str:
    return hashlib.sha256("".join(f"{value}\n" for value in sorted(values)).encode()).hexdigest()


def session_key(row):
    return tuple(row[field] for field in (
        "campaign_id", "platform", "shard_id", "replica_id", "instance_id", "host_epoch"))


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
        OBSERVED_FIELDS)


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


def parse_parameters(value):
    if value == "-":
        return {}
    result = {}
    entries = re.split(r";(?=[A-Za-z_][A-Za-z0-9_.-]*=)", value)
    for entry in entries:
        key, separator, item = entry.partition("=")
        if not separator or not key or key in result:
            raise ReductionError(f"invalid manifest parameters: {value!r}")
        result[key] = item
    return result


def canonical_parameters(parameters):
    return ";".join(f"{key}={parameters[key]}" for key in sorted(parameters)) or "-"


def logical_row_id(row):
    suffix = "/" + row["system"]
    if not row["row_id"].endswith(suffix):
        raise ReductionError(f"row ID does not end with its system: {row['row_id']}")
    return row["row_id"][:-len(suffix)]


def load_manifest(path, expected_count):
    rows = read_tsv(path, MANIFEST_FIELDS, MANIFEST_FIELDS)
    if expected_count is not None and len(rows) != expected_count:
        raise ReductionError(f"manifest must contain {expected_count} rows, found {len(rows)}")
    identifiers = [row["row_id"] for row in rows]
    if len(identifiers) != len(set(identifiers)):
        raise ReductionError("manifest contains duplicate row IDs")
    for row in rows:
        logical_row_id(row)
        parse_parameters(row["parameters"])
    return rows


def load_platforms(path):
    rows = read_tsv(path, ("platform", "architecture", "instance_type"))
    platforms = {row["platform"]: row for row in rows}
    if len(platforms) != len(rows):
        raise ReductionError("platform file contains duplicate platform identities")
    if set(platforms) != {"c8i", "c8g", "c9g"}:
        raise ReductionError(f"platforms must be c8i, c8g, and c9g; found {sorted(platforms)}")
    return platforms


def load_shards(path):
    rows = read_tsv(path, ("shard_id",))
    shards = {row["shard_id"] for row in rows}
    if len(shards) != len(rows) or not shards:
        raise ReductionError("shard file contains duplicate or no shard identities")
    return shards


def load_receipt_ledger(paths, confirmation):
    rows = []
    for path in paths:
        rows.extend(read_tsv(path, RECEIPT_FIELDS, RECEIPT_FIELDS))
    for row in rows:
        expected = "4" if confirmation else {"1", "2", "3"}
        if (confirmation and row["replica_id"] != expected) or (
                not confirmation and row["replica_id"] not in expected):
            raise ReductionError(f"invalid {'confirmation' if confirmation else 'primary'} replica in {row['_source']}")
    keys = [session_key(row) for row in rows]
    if len(keys) != len(set(keys)):
        raise ReductionError("receipt ledgers contain duplicate session identities")
    return rows


def verify_receipt_coverage(primary, confirmation, platforms, shards, manifest_rows, manifest_digest):
    manifest_shards = {row["shard_id"] for row in manifest_rows}
    if manifest_shards != shards:
        raise ReductionError(
            f"manifest/shard mismatch: missing={sorted(shards - manifest_shards)}, "
            f"unexpected={sorted(manifest_shards - shards)}")
    expected = {(platform, shard, str(replica)) for platform in platforms for shard in shards for replica in (1, 2, 3)}
    actual = {(row["platform"], row["shard_id"], row["replica_id"]) for row in primary}
    if actual != expected or len(primary) != len(expected):
        raise ReductionError(
            f"primary coverage mismatch: missing={sorted(expected - actual)[:20]}, "
            f"unexpected={sorted(actual - expected)[:20]}")
    confirmation_keys = [(row["platform"], row["shard_id"]) for row in confirmation]
    if len(confirmation_keys) != len(set(confirmation_keys)):
        raise ReductionError("more than one confirmation host exists for a platform and shard")
    all_rows = primary + confirmation
    campaign_ids = {row["campaign_id"] for row in all_rows}
    if len(campaign_ids) != 1:
        raise ReductionError(f"accepted receipts contain multiple campaign IDs: {sorted(campaign_ids)}")
    instances = [row["instance_id"] for row in all_rows]
    epochs = [row["host_epoch"] for row in all_rows]
    if len(instances) != len(set(instances)):
        raise ReductionError("accepted receipts reuse an instance ID")
    if len(epochs) != len(set(epochs)):
        raise ReductionError("accepted receipts reuse a host epoch")
    candidate_identities = {
        (row["candidate_commit"], row["candidate_archive_sha256"], row["engine_tree"], normalize_heap_size(row["heap_size"]))
        for row in all_rows
    }
    if len(candidate_identities) != 1:
        raise ReductionError(f"accepted receipts contain multiple candidate identities: {sorted(candidate_identities)}")
    systems_by_shard = defaultdict(set)
    rows_by_shard = defaultdict(list)
    for row in manifest_rows:
        systems_by_shard[row["shard_id"]].add(row["system"])
        rows_by_shard[row["shard_id"]].append(row)
    for row in all_rows:
        context = f"{row['_source']}:{row['_line']}"
        if row["schema_version"] != SCHEMA_VERSION:
            raise ReductionError(f"{context} has unsupported receipt schema {row['schema_version']!r}")
        for field in ("observed_rows_sha256", "evidence_manifest_sha256"):
            if not SHA256.fullmatch(row[field]):
                raise ReductionError(f"{context} has an invalid {field}")
        if re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", row["candidate_commit"]) is None:
            raise ReductionError(f"{context} has an invalid candidate commit")
        if row["candidate_archive_sha256"] != "not-recorded" and not SHA256.fullmatch(
                row["candidate_archive_sha256"]):
            raise ReductionError(f"{context} has an invalid candidate archive checksum")
        if re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", row["engine_tree"]) is None:
            raise ReductionError(f"{context} has an invalid engine tree")
        platform = platforms.get(row["platform"])
        if platform is None or row["shard_id"] not in shards:
            raise ReductionError(f"{context} has an unknown platform or shard")
        if row["architecture"] != platform["architecture"] or row["instance_type"] != platform["instance_type"]:
            raise ReductionError(f"{context} does not match the frozen platform")
        systems = row["systems"].split(",")
        if systems != sorted(set(systems)) or set(systems) != systems_by_shard[row["shard_id"]]:
            raise ReductionError(f"{context} does not cover the shard's exact systems")
        expected_rows = rows_by_shard[row["shard_id"]]
        if row["row_count"] != str(len(expected_rows)):
            raise ReductionError(f"{context} has the wrong row count")
        if row["manifest_sha256"] != manifest_digest:
            raise ReductionError(f"{context} has the wrong manifest checksum")
        if row["expected_rows_sha256"] != stable_digest(item["row_id"] for item in expected_rows):
            raise ReductionError(f"{context} has the wrong expected-row checksum")


def discover_session_directories(artifact_roots, session_directories):
    directories = {path.resolve() for path in session_directories}
    for root in artifact_roots:
        if not root.is_dir():
            raise ReductionError(f"artifact root does not exist: {root}")
        directories.update(path.parent.resolve() for path in root.rglob("acceptance-receipt.tsv"))
    return sorted(directories, key=str)


def read_one(path, fields, exact=None):
    rows = read_tsv(path, fields, exact)
    if len(rows) != 1:
        raise ReductionError(f"{path} must contain one data row, found {len(rows)}")
    return rows[0]


def file_set_digest(directory):
    values = []
    files = (item for item in directory.rglob("*") if item.is_file())
    for path in sorted(files, key=lambda item: str(item.relative_to(directory))):
        relative = str(path.relative_to(directory))
        values.append(f"{relative}\0{path.stat().st_size}\0{sha256_file(path)}")
    return stable_digest(values)


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


def read_native_bracket(path):
    if not path.exists():
        return None, {}
    rows = read_tsv(path, ("benchmark", "before_score_ns", "after_score_ns", "before_after_drift"))
    if not rows:
        raise ReductionError(f"{path} is empty")
    values = {}
    for row in rows:
        drift = parse_float(row["before_after_drift"], f"{path} native drift", nonnegative=True)
        before = parse_float(row["before_score_ns"], f"{path} before score", nonnegative=True)
        after = parse_float(row["after_score_ns"], f"{path} after score", nonnegative=True)
        if min(before, after) <= 0:
            raise ReductionError(f"{path} contains an invalid native bracket")
        expected_drift = abs(after - before) / min(before, after)
        if not math.isclose(drift, expected_drift, rel_tol=1e-9, abs_tol=1e-12):
            raise ReductionError(f"{path} contains an invalid native bracket")
        if row["benchmark"] in values:
            raise ReductionError(f"{path} contains duplicate native benchmark {row['benchmark']!r}")
        values[row["benchmark"]] = drift
    return max(values.values()), values


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
        raise ReductionError(f"{path} reports nonzero host-session exit status")
    return properties


def evidence_paths(directory, evidence_rows, name):
    suffix = "/" + name
    return [
        directory / row["artifact"]
        for row in evidence_rows
        if row["artifact"].endswith(suffix)
    ]


def verify_session(directory, ledger_receipt, confirmation, manifest_by_id, manifest_digest):
    status_path = directory / "status.txt"
    if not status_path.is_file() or status_path.read_text(encoding="utf-8") != "status=complete\n":
        raise ReductionError(f"host session does not have a complete final status: {directory}")
    artifact_receipt = read_one(directory / "acceptance-receipt.tsv", RECEIPT_FIELDS, RECEIPT_FIELDS)
    clean_artifact = {field: artifact_receipt[field] for field in RECEIPT_FIELDS}
    clean_ledger = {field: ledger_receipt[field] for field in RECEIPT_FIELDS}
    if clean_artifact != clean_ledger:
        raise ReductionError(f"artifact receipt does not match its ledger row: {directory}")
    session = read_one(directory / "session.tsv", SESSION_FIELDS, SESSION_FIELDS)
    if any(session[field] != artifact_receipt[field] for field in SESSION_FIELDS):
        raise ReductionError(f"session identity does not match its receipt: {directory}")
    observed_path = directory / "observed-rows.tsv"
    if sha256_file(observed_path) != artifact_receipt["observed_rows_sha256"]:
        raise ReductionError(f"observed-row checksum does not match receipt: {directory}")
    evidence_path = directory / "route-evidence.tsv"
    if sha256_file(evidence_path) != artifact_receipt["evidence_manifest_sha256"]:
        raise ReductionError(f"host evidence manifest checksum does not match receipt: {directory}")
    try:
        evidence_rows = verify_evidence_manifest(evidence_path, directory)
        candidate_commit, candidate_archive_sha256, engine_tree, heap_size = evidence_candidate_identity(
            evidence_rows, directory)
    except ValidationError as error:
        raise ReductionError(f"invalid host evidence in {directory}: {error}") from error
    candidate_identity = {
        "candidate_commit": candidate_commit,
        "candidate_archive_sha256": candidate_archive_sha256,
        "engine_tree": engine_tree,
        "heap_size": heap_size,
    }
    mismatches = [
        field for field, value in candidate_identity.items()
        if artifact_receipt[field] != value
    ]
    if mismatches:
        raise ReductionError(f"host evidence candidate identity differs from receipt: {mismatches}")

    route_sessions = evidence_paths(directory, evidence_rows, "route-session.tsv")
    route_systems = set()
    identity_fields = tuple(field for field in SESSION_FIELDS if field != "systems")
    for route_session_path in route_sessions:
        route_session = read_one(route_session_path, SESSION_FIELDS, SESSION_FIELDS)
        changed = [field for field in identity_fields if route_session[field] != session[field]]
        if changed:
            raise ReductionError(f"route session identity differs from host session: {route_session_path}: {changed}")
        route_systems.update(route_session["systems"].split(","))
    if route_systems != set(session["systems"].split(",")):
        raise ReductionError(f"route systems do not exactly cover host session systems: {directory}")
    observed = read_observed_rows(observed_path)
    expected = {identifier: row for identifier, row in manifest_by_id.items()
                if row["shard_id"] == artifact_receipt["shard_id"]}
    identifiers = [row["row_id"] for row in observed]
    if len(identifiers) != len(set(identifiers)) or set(identifiers) != set(expected):
        raise ReductionError(f"artifact does not contain the exact shard rows: {directory}")
    rebar_outcomes = []
    outcomes_by_row_id = {}
    for outcome_path in sorted(directory.glob("routes/*/raw/rebar-outcomes.tsv")):
        for outcome in read_tsv(outcome_path, REBAR_OUTCOME_FIELDS, REBAR_OUTCOME_FIELDS):
            row_id = outcome["row_id"]
            if row_id in outcomes_by_row_id:
                raise ReductionError(f"duplicate Rebar outcome for {row_id}: {directory}")
            manifest_row = expected.get(row_id)
            if manifest_row is None:
                raise ReductionError(f"Rebar outcome is not an expected shard row: {row_id}")
            if any(outcome[field] != manifest_row[field] for field in ("suite", "system", "benchmark")):
                raise ReductionError(f"Rebar outcome relabels manifest data: {row_id}")
            parameters = parse_parameters(manifest_row["parameters"])
            if outcome["model"] != parameters.get("model"):
                raise ReductionError(f"Rebar outcome changes the manifest model: {row_id}")
            if outcome["outcome"] == "semantic-mismatch":
                if manifest_row["suite"] != "rebar-extended":
                    raise ReductionError(f"semantic mismatch is not in the extended corpus: {row_id}")
            elif outcome["outcome"] == "did-not-finish":
                if manifest_row["system"] != "joni":
                    raise ReductionError(f"did-not-finish outcome is not a Joni row: {row_id}")
            else:
                raise ReductionError(f"Rebar outcome is not classified: {row_id}")
            if not outcome["detail"]:
                raise ReductionError(f"Rebar outcome has no detail: {row_id}")
            outcomes_by_row_id[row_id] = outcome
            rebar_outcomes.append({field: outcome[field] for field in REBAR_OUTCOME_FIELDS})
    for row in observed:
        manifest_row = expected[row["row_id"]]
        if row["shard_id"] != manifest_row["shard_id"] or row["system"] != manifest_row["system"]:
            raise ReductionError(f"observed row relabels manifest data: {row['row_id']}")
        expected_outcome = outcomes_by_row_id.get(row["row_id"], {}).get("outcome", "accepted")
        allowed_outcomes = (
            {"accepted", "precision-rejected"}
            if expected_outcome == "accepted"
            else {expected_outcome}
        )
        if row["outcome"] not in allowed_outcomes:
            raise ReductionError(
                f"observed row outcome differs from its outcome ledger: {row['row_id']}")
        if not SHA256.fullmatch(row["result_checksum"]):
            raise ReductionError(f"observed row has invalid contract identity: {row['row_id']}")
        if expected_outcome in {"semantic-mismatch", "did-not-finish"}:
            if row["score"] or row["score_unit"] or row["allocation_bytes"] != "not-measured":
                raise ReductionError(
                    f"classified-outcome row contains performance data: {row['row_id']}")
            continue
        parse_float(row["score"], f"{observed_path}:{row['_line']} score", nonnegative=True)
        allocation = row["allocation_bytes"]
        if allocation not in {"not-measured", "-", ""}:
            parse_float(allocation, f"{observed_path}:{row['_line']} allocation", nonnegative=True)
    calibration_paths = evidence_paths(directory, evidence_rows, "calibration.tsv")
    if not calibration_paths:
        raise ReductionError(f"host evidence has no routed calibration: {directory}")
    calibration_drift = max(read_calibration(path) for path in calibration_paths)
    native_paths = evidence_paths(directory, evidence_rows, "native-bracket.tsv")
    native_by_benchmark = {}
    native_drifts = []
    for native_path in native_paths:
        route_drift, route_values = read_native_bracket(native_path)
        if route_drift is not None:
            native_drifts.append(route_drift)
        duplicates = set(native_by_benchmark) & set(route_values)
        if duplicates:
            raise ReductionError(f"native brackets contain duplicate benchmarks: {sorted(duplicates)}")
        native_by_benchmark.update(route_values)
    native_drift = max(native_drifts) if native_drifts else None
    capacity = read_final_capacity(directory / "host-capacity.txt")
    if exceeds_drift_limit(calibration_drift):
        raise ReductionError(
            f"accepted session exceeds the 5% calibration drift gate: {directory}")
    return Session(
        artifact_receipt, directory, confirmation, calibration_drift, native_drift,
        native_by_benchmark, file_set_digest(directory), tuple(evidence_rows), capacity,
        tuple(rebar_outcomes)), observed


def load_sessions(primary_receipts, confirmation_receipts, artifact_roots, session_directories,
                  manifest_rows, manifest_digest):
    manifest_by_id = {row["row_id"]: row for row in manifest_rows}
    ledgers = {session_key(row): (row, False) for row in primary_receipts}
    for row in confirmation_receipts:
        key = session_key(row)
        if key in ledgers:
            raise ReductionError("primary and confirmation ledgers overlap")
        ledgers[key] = (row, True)
    discovered = discover_session_directories(artifact_roots, session_directories)
    artifacts = {}
    for directory in discovered:
        receipt = read_one(directory / "acceptance-receipt.tsv", RECEIPT_FIELDS, RECEIPT_FIELDS)
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
        session, observed = verify_session(
            artifacts[key], receipt, confirmation, manifest_by_id, manifest_digest)
        sessions.append(session)
        observed_by_session[key] = observed
    return sessions, observed_by_session


def load_artifact_indexes(paths):
    indexes = {}
    for path in paths:
        rows = read_tsv(path, SESSION_FIELDS)
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
        for path in (item for item in session.directory.rglob("*") if item.is_file()):
            relative = relative_directory / path.relative_to(session.directory)
            archive_path = "campaign-results/" + relative.as_posix()
            expected = source_manifest.get(archive_path)
            actual = (path.stat().st_size, sha256_file(path))
            if expected != actual:
                raise ReductionError(
                    f"accepted host evidence is not bound by the campaign archive: {path}")


def load_events(paths):
    events = []
    for path in paths:
        rows = read_tsv(path, ("event_type", "reason"))
        for row in rows:
            if row["event_type"] not in {"interruption", "rejected-session", "exclusion"}:
                raise ReductionError(f"unknown campaign event type {row['event_type']!r} in {path}")
            known = {field: row.get(field, "") for field in EVENT_FIELDS[:-1]}
            extra = {field: value for field, value in row.items()
                     if not field.startswith("_") and field not in EVENT_FIELDS and value}
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
            if row["event_type"] == "exclusion" and details:
                try:
                    exclusion_details = json.loads(details)
                except json.JSONDecodeError:
                    exclusion_details = None
                if isinstance(exclusion_details, dict) and exclusion_details.get("scope") == "Joni":
                    raise ReductionError(
                        "Joni exclusions must come from the authoritative applicability ledger")
            known["details"] = details
            events.append(known)
    return sorted(events, key=lambda row: tuple(row[field] for field in EVENT_FIELDS))


def load_rebar_exclusions(paths, campaign_id):
    events = []
    seen = set()
    for path in paths:
        with path.open(newline="", encoding="utf-8") as input_file:
            fields = tuple(csv.DictReader(input_file, delimiter="\t").fieldnames or ())
        if fields == ("name", "model", "definition", "reason"):
            scope = "Rebar"
            identifier_field = "name"
        else:
            raise ReductionError(f"unsupported exclusion columns in {path}: {list(fields)}")
        for row in read_tsv(path, fields, fields):
            key = (scope, row[identifier_field], row["model"])
            if key in seen:
                raise ReductionError(f"duplicate Rebar exclusion {key} in {path}")
            seen.add(key)
            details = {
                "model": row["model"],
                identifier_field: row[identifier_field],
                "scope": scope,
            }
            if "definition" in row:
                details["definition"] = row["definition"]
            events.append({
                "event_type": "exclusion", "campaign_id": campaign_id, "platform": "",
                "shard_id": "", "replica_id": "", "instance_id": "", "host_epoch": "",
                "reason": row["reason"], "artifact_uri": str(path),
                "artifact_sha256": sha256_file(path),
                "details": json.dumps(details, sort_keys=True, separators=(",", ":")),
            })
    return events


def load_rebar_joni_applicability(path, manifest_rows, campaign_id):
    if path is None:
        if any(row["suite"].startswith("rebar-") and row["system"] == "joni" for row in manifest_rows):
            raise ReductionError("Rebar/Joni manifest rows require --rebar-joni-applicability")
        return []

    source = path.resolve()
    try:
        root = source.parents[3]
    except IndexError as error:
        raise ReductionError(f"Joni applicability path is not inside a repository: {path}") from error
    expected_source, _ = rebar_joni_applicability.applicability_paths(root)
    if source != expected_source.resolve():
        raise ReductionError(
            "Joni applicability must use the authoritative repository path: "
            f"expected {expected_source}, found {path}")
    try:
        applicability = rebar_joni_applicability.load_applicability(root)
    except (OSError, ValueError) as error:
        raise ReductionError(f"invalid Rebar/Joni applicability ledger: {error}") from error

    rebar_rows = [row for row in manifest_rows if row["suite"].startswith("rebar-")]
    manifest_identities = {
        (row["benchmark"], parse_parameters(row["parameters"])["model"])
        for row in rebar_rows
    }
    if manifest_identities != set(applicability):
        raise ReductionError(
            "Rebar/Joni applicability differs from frozen manifest identities; "
            f"missing={sorted(set(applicability) - manifest_identities)[:20]}, "
            f"unexpected={sorted(manifest_identities - set(applicability))[:20]}")

    expected_joni = {
        identity for identity, row in applicability.items()
        if row["status"] == "compatible"
    }
    actual_joni = {
        (row["benchmark"], parse_parameters(row["parameters"])["model"])
        for row in rebar_rows
        if row["system"] == "joni"
    }
    if actual_joni != expected_joni:
        raise ReductionError(
            "frozen Rebar/Joni rows differ from applicability; "
            f"missing={sorted(expected_joni - actual_joni)[:20]}, "
            f"unexpected={sorted(actual_joni - expected_joni)[:20]}")

    ledger_digest = rebar_joni_applicability.applicability_digest(root)
    bound_digests = set()
    for row in rebar_rows:
        if row["system"] != "joni":
            continue
        match = re.search(r"(?:^|;)joni-applicability=([0-9a-f]{64})(?:;|$)", row["workload_checksum"])
        if match is None:
            raise ReductionError(f"frozen Rebar/Joni row is not bound to applicability: {row['row_id']}")
        bound_digests.add(match.group(1))
    if bound_digests != {ledger_digest}:
        raise ReductionError(
            "Rebar/Joni applicability digest differs from frozen manifest binding: "
            f"ledger={ledger_digest}, manifest={sorted(bound_digests)}")

    events = []
    for (benchmark, model), row in sorted(applicability.items()):
        if row["status"] == "compatible":
            continue
        details = {
            "benchmark": benchmark,
            "model": model,
            "scope": "Joni",
            "status": row["status"],
        }
        events.append({
            "event_type": "exclusion", "campaign_id": campaign_id, "platform": "",
            "shard_id": "", "replica_id": "", "instance_id": "", "host_epoch": "",
            "reason": row["reason"], "artifact_uri": str(source),
            "artifact_sha256": ledger_digest,
            "details": json.dumps(details, sort_keys=True, separators=(",", ":")),
        })
    return events


def rebar_corpus(row):
    if row["suite"] == "rebar-curated":
        return "curated"
    if row["suite"] == "rebar-extended":
        return "extended"
    return "not-rebar"


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


def native_bracket_drift(manifest, drift_by_benchmark):
    keys = (manifest["comparator"], f"{manifest['benchmark']}[{manifest['parameters']}]")
    values = {drift_by_benchmark[key] for key in keys if key in drift_by_benchmark}
    if len(values) > 1:
        raise ReductionError(f"native bracket has conflicting identities for {manifest['row_id']}")
    return next(iter(values)) if values else None


def build_host_rows(sessions, observed_by_session, manifest_by_id, artifact_indexes, campaign_artifact):
    rows = []
    host_fields = (
        "campaign_id", "platform", "architecture", "instance_type", "availability_zone", "shard_id",
        "replica_id", "instance_id", "host_epoch", "confirmation", "candidate_commit",
        "engine_tree", "evidence_manifest_sha256", "row_id", "suite",
        "system", "benchmark", "parameters", "comparator", "expected_result",
        "allocation_contract", "workload_checksum", "rebar_corpus", "score", "score_unit",
        "allocation_bytes", "contract_identity", "outcome", "semantic_outcome", "semantic_detail",
        "calibration_drift", "native_bracket_drift", "artifact_set_sha256", "artifact_uri",
        "artifact_sha256", "retrieved_at", "campaign_s3_uri", "campaign_version_id",
        "campaign_archive_sha256", "session_directory",
    )
    for session in sessions:
        receipt = session.receipt
        index = artifact_indexes.get(session.key, {})
        rebar_outcomes = {row["row_id"]: row for row in session.rebar_outcomes}
        for observed in observed_by_session[session.key]:
            manifest = manifest_by_id[observed["row_id"]]
            native_drift = native_bracket_drift(manifest, session.native_drift_by_benchmark)
            row = {
                **{field: receipt[field] for field in (
                    "campaign_id", "platform", "architecture", "instance_type", "availability_zone", "shard_id",
                    "replica_id", "instance_id", "host_epoch", "candidate_commit", "engine_tree",
                    "evidence_manifest_sha256")},
                "confirmation": "true" if session.confirmation else "false",
                **{field: manifest[field] for field in (
                    "row_id", "suite", "system", "benchmark", "parameters", "comparator",
                    "expected_result", "allocation_contract", "workload_checksum")},
                "rebar_corpus": rebar_corpus(manifest),
                **{field: observed.get(field, "") for field in (
                    "score", "score_unit", "allocation_bytes", "outcome")},
                "contract_identity": observed["result_checksum"],
                "semantic_outcome": rebar_outcomes.get(observed["row_id"], {}).get("outcome", "verified"),
                "semantic_detail": rebar_outcomes.get(observed["row_id"], {}).get("detail", ""),
                "calibration_drift": format_number(session.calibration_drift),
                "native_bracket_drift": format_number(native_drift),
                "artifact_set_sha256": session.artifact_set_sha256,
                "artifact_uri": index.get("artifact_uri", index.get("retrieval_uri", "")),
                "artifact_sha256": index.get("artifact_sha256", index.get("archive_sha256", "")),
                "retrieved_at": index.get("retrieved_at", index.get("retrieval_timestamp", "")),
                "campaign_s3_uri": campaign_artifact.get("s3_uri", ""),
                "campaign_version_id": campaign_artifact.get("version_id", ""),
                "campaign_archive_sha256": campaign_artifact.get("archive_sha256", ""),
                "session_directory": str(session.directory),
            }
            rows.append(row)
    rows.sort(key=lambda row: (
        row["platform"], row["row_id"], int(row["replica_id"]), row["host_epoch"]))
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


def comparison_specs(manifest_rows):
    groups = defaultdict(list)
    for row in manifest_rows:
        groups[logical_row_id(row)].append(row)
    specs = []
    for logical_id, rows in groups.items():
        by_system = {row["system"]: row for row in rows}
        candidate_systems = [system for system in by_system if system.startswith("regulator")]
        if "native-re2-before" in by_system and "native-re2-after" in by_system:
            for system in candidate_systems:
                specs.append((logical_id, by_system[system], "native-re2", (
                    by_system["native-re2-before"], by_system["native-re2-after"])))
        if "joni" in by_system:
            for system in candidate_systems:
                specs.append((logical_id, by_system[system], "joni", (by_system["joni"],)))
        if by_system.keys() >= {"regulator", "trino-optimized", "trino-sql"}:
            specs.append((logical_id, by_system["regulator"], "trino-optimized", (by_system["trino-optimized"],)))
            specs.append((logical_id, by_system["regulator"], "trino-sql", (by_system["trino-sql"],)))
        descriptors = {row["comparator"] for row in rows}
        if "general-route-control" in descriptors and by_system.keys() >= {
                "regulator-native-access", "regulator-object-row"}:
            specs.append((logical_id, by_system["regulator-native-access"], "regulator-object-row", (
                by_system["regulator-object-row"],)))
    return sorted(specs, key=lambda item: (item[1]["row_id"], item[2]))


def build_comparisons(host_rows, manifest_rows):
    by_host_row = {(row["platform"], row["host_epoch"], row["row_id"]): row for row in host_rows}
    hosts_by_row_id = defaultdict(list)
    for row in host_rows:
        hosts_by_row_id[row["row_id"]].append(row)
    fields = (
        "platform", "shard_id", "suite", "rebar_corpus", "replica_id", "host_epoch",
        "confirmation", "comparison_id", "logical_row_id", "candidate_row_id",
        "candidate_system", "comparator_system", "comparator_row_ids", "candidate_score",
        "comparator_score", "score_unit", "ratio", "direction", "material",
        "native_bracket_drift", "serious_outlier", "outcome",
    )
    rows = []
    for logical_id, candidate, comparator_system, comparators in comparison_specs(manifest_rows):
        for host in hosts_by_row_id[candidate["row_id"]]:
            comparator_hosts = [
                by_host_row.get((host["platform"], host["host_epoch"], row["row_id"]))
                for row in comparators
            ]
            if any(row is None for row in comparator_hosts):
                raise ReductionError(
                    f"same-host comparator is missing for {candidate['row_id']} "
                    f"on {host['host_epoch']}")
            if host.get("semantic_outcome", "verified") != "verified" or any(
                    row.get("semantic_outcome", "verified") != "verified" for row in comparator_hosts):
                continue
            outcome = (
                "accepted"
                if host.get("outcome", "accepted") == "accepted" and
                all(row.get("outcome", "accepted") == "accepted" for row in comparator_hosts)
                else "precision-rejected"
            )
            units = {row["score_unit"] for row in comparator_hosts}
            if len(units) != 1:
                raise ReductionError(f"native bracket changes score units for {candidate['row_id']}")
            comparator_score = median([float(row["score"]) for row in comparator_hosts])
            native_drifts = [
                float(row["native_bracket_drift"])
                for row in (host, *comparator_hosts) if row["native_bracket_drift"]
            ]
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
                "rebar_corpus": host["rebar_corpus"], "replica_id": host["replica_id"],
                "host_epoch": host["host_epoch"], "confirmation": host["confirmation"],
                "comparison_id": f"{candidate['row_id']}::{comparator_system}",
                "logical_row_id": logical_id, "candidate_row_id": candidate["row_id"],
                "candidate_system": candidate["system"], "comparator_system": comparator_system,
                "comparator_row_ids": ",".join(row["row_id"] for row in comparators),
                "candidate_score": host["score"], "comparator_score": format_number(comparator_score),
                "score_unit": host["score_unit"], "ratio": format_number(ratio),
                "direction": direction, "material": str(material).lower(),
                "native_bracket_drift": format_number(max(native_drifts) if native_drifts else None),
                "serious_outlier": str(ratio <= SERIOUS_RATIO_LOW or ratio >= SERIOUS_RATIO_HIGH).lower(),
                "outcome": outcome,
            })
    rows.sort(key=lambda row: (row["platform"], row["comparison_id"], int(row["replica_id"]), row["host_epoch"]))
    return fields, rows


def identify_scaling_groups(manifest_rows):
    candidate_groups = defaultdict(list)
    for row in manifest_rows:
        parameters = parse_parameters(row["parameters"])
        for key, value in parameters.items():
            if SIZE_KEY.search(key) and re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", value):
                others = dict(parameters)
                del others[key]
                identity = (row["shard_id"], row["suite"], row["system"], row["benchmark"], key,
                            canonical_parameters(others))
                candidate_groups[identity].append((row, float(value)))
    result = {}
    for identity, points in candidate_groups.items():
        sizes = {size for _, size in points}
        if len(sizes) >= 3:
            group_id = hashlib.sha256("\0".join(identity).encode()).hexdigest()[:16]
            result[group_id] = (identity, sorted(points, key=lambda point: point[1]))
    return result


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
    by_host_row = {(row["platform"], row["host_epoch"], row["row_id"]): row for row in host_rows}
    host_identity = {(row["platform"], row["host_epoch"]): row for row in host_rows}
    fields = (
        "platform", "shard_id", "suite", "system", "benchmark", "scaling_group",
        "size_parameter", "other_parameters", "replica_id", "host_epoch", "confirmation",
        "point_count", "minimum_size", "maximum_size", "slope", "classification", "row_ids",
    )
    rows = []
    membership = defaultdict(list)
    for group_id, (identity, points) in identify_scaling_groups(manifest_rows).items():
        shard, suite, system, benchmark, size_parameter, other_parameters = identity
        point_ids = [row["row_id"] for row, _ in points]
        for platform, epoch in sorted(host_identity):
            host_points = [by_host_row.get((platform, epoch, row["row_id"])) for row, _ in points]
            if all(row is None for row in host_points):
                continue
            if any(row is None for row in host_points):
                raise ReductionError(f"host {epoch} has incomplete scaling group {group_id}")
            if any(row["semantic_outcome"] != "verified" or
                   row.get("outcome", "accepted") != "accepted"
                   for row in host_points):
                continue
            size_scores = [(size, float(host_row["score"])) for (_, size), host_row in zip(points, host_points)]
            slope = scaling_slope(size_scores)
            host = host_points[0]
            row = {
                "platform": platform, "shard_id": shard, "suite": suite, "system": system,
                "benchmark": benchmark, "scaling_group": group_id, "size_parameter": size_parameter,
                "other_parameters": other_parameters, "replica_id": host["replica_id"],
                "host_epoch": epoch, "confirmation": host["confirmation"], "point_count": str(len(points)),
                "minimum_size": format_number(min(size for _, size in points)),
                "maximum_size": format_number(max(size for _, size in points)),
                "slope": format_number(slope), "classification": scaling_class(slope),
                "row_ids": ",".join(point_ids),
            }
            rows.append(row)
            for row_id in point_ids:
                membership[(platform, row_id)].append((group_id, row["classification"]))
    rows.sort(key=lambda row: (row["platform"], row["scaling_group"], int(row["replica_id"]), row["host_epoch"]))
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
        "platform", "row_id", "shard_id", "suite", "rebar_corpus", "system", "benchmark",
        "parameters", "comparator", "allocation_contract", "primary_host_count",
        "confirmation_host_count", "host_count", "score_unit", "primary_median_score",
        "median_score", "minimum_score", "maximum_score", "primary_host_cv", "host_cv",
        "confidence_interval_status",
        "median_allocation_bytes", "maximum_calibration_drift", "maximum_native_bracket_drift",
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
        semantic_outcomes = {row["semantic_outcome"] for row in hosts}
        if "did-not-finish" in semantic_outcomes:
            continue
        if len(semantic_outcomes) != 1:
            raise ReductionError(f"row {row_id} on {platform} changes semantic outcome across hosts")
        if semantic_outcomes == {"semantic-mismatch"}:
            continue
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
        native_values = [float(row["native_bracket_drift"]) for row in hosts if row["native_bracket_drift"]]
        native_drift = max(native_values) if native_values else None
        if native_drift is not None and exceeds_drift_limit(native_drift):
            reasons.append("native-bracket-drift-above-5-percent")
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
            "suite": first["suite"], "rebar_corpus": first["rebar_corpus"], "system": first["system"],
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
            "maximum_native_bracket_drift": format_number(native_drift),
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
        "platform", "comparison_id", "shard_id", "suite", "rebar_corpus", "candidate_row_id",
        "candidate_system", "comparator_system", "primary_host_count", "confirmation_host_count",
        "host_count", "primary_median_ratio", "median_ratio", "minimum_ratio", "maximum_ratio",
        "primary_ratio_host_cv", "ratio_host_cv", "confidence_interval_status",
        "faster_hosts", "parity_hosts", "slower_hosts", "direction",
        "maximum_native_bracket_drift", "serious_outlier", "unresolved", "unresolved_reasons",
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
        native_values = [float(row["native_bracket_drift"]) for row in hosts if row["native_bracket_drift"]]
        native_drift = max(native_values) if native_values else None
        if native_drift is not None and exceeds_drift_limit(native_drift):
            reasons.append("native-bracket-drift-above-5-percent")
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
            "suite": first["suite"], "rebar_corpus": first["rebar_corpus"],
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
            "maximum_native_bracket_drift": format_number(native_drift),
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
        if "native-bracket-drift-above-5-percent" in reasons:
            gaps.append(gap_row(
                "unresolved", "comparison", platform, first["shard_id"],
                first["candidate_row_id"], comparison_id, "", "native-bracket-drift-above-5-percent",
                f"drift={format_number(native_drift)}", "drift <= 0.05",
                "Native before/after evidence is unstable for this comparison"))
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
        "platform", "suite", "rebar_corpus", "candidate_system", "comparator_system",
        "comparison_count", "median_ratio", "p10_ratio", "p90_ratio", "faster_count",
        "parity_count", "slower_count", "unresolved_count",
    )
    general = [row for row in comparison_aggregates if row["serious_outlier"] == "false"]
    rows = []
    for key, values in _group(general, "platform", "suite", "rebar_corpus", "candidate_system", "comparator_system"):
        ratios = [float(row["median_ratio"]) for row in values]
        counts = Counter(row["direction"] for row in values)
        rows.append({
            "platform": key[0], "suite": key[1], "rebar_corpus": key[2], "candidate_system": key[3],
            "comparator_system": key[4], "comparison_count": str(len(values)),
            "median_ratio": format_number(median(ratios)), "p10_ratio": format_number(percentile(ratios, 0.10)),
            "p90_ratio": format_number(percentile(ratios, 0.90)), "faster_count": str(counts["faster"]),
            "parity_count": str(counts["parity"]), "slower_count": str(counts["slower"]),
            "unresolved_count": str(sum(row["unresolved"] == "true" for row in values)),
        })
    return fields, rows


def build_confirmation_jobs(row_aggregates, host_rows, comparisons, scaling, sessions):
    eligible = set()
    for row in row_aggregates:
        if "precision-rejected" in row["unresolved_reasons"].split(","):
            eligible.add((row["platform"], row["shard_id"]))
        if exceeds_drift_limit(float(row["primary_host_cv"])):
            eligible.add((row["platform"], row["shard_id"]))
        native_drift = row["maximum_native_bracket_drift"]
        if native_drift and exceeds_drift_limit(float(native_drift)):
            eligible.add((row["platform"], row["shard_id"]))

    for (platform, row_id), rows in _group(host_rows, "platform", "row_id"):
        primary = [row for row in rows if row["confirmation"] == "false"]
        if any(row["semantic_outcome"] != "verified" for row in primary):
            continue
        if len({row["contract_identity"] for row in primary}) > 1:
            eligible.add((platform, primary[0]["shard_id"]))

    for (platform, comparison_id), rows in _group(comparisons, "platform", "comparison_id"):
        primary_ratios = [
            float(row["ratio"])
            for row in rows
            if row["confirmation"] == "false"
        ]
        if exceeds_drift_limit(coefficient_of_variation(primary_ratios)):
            eligible.add((platform, rows[0]["shard_id"]))
        primary_directions = {
            row["direction"] for row in rows
            if row["confirmation"] == "false" and row["material"] == "true"
        }
        if primary_directions >= {"faster", "slower"}:
            eligible.add((platform, rows[0]["shard_id"]))

    for (platform, scaling_group), rows in _group(scaling, "platform", "scaling_group"):
        primary_classes = {
            row["classification"] for row in rows
            if row["confirmation"] == "false" and row["classification"] != "unclassified"
        }
        if len(primary_classes) > 1:
            eligible.add((platform, rows[0]["shard_id"]))

    already_confirmed = {
        (session.receipt["platform"], session.receipt["shard_id"])
        for session in sessions if session.confirmation
    }
    eligible -= already_confirmed
    platform_order = {platform: index for index, platform in enumerate(("c8i", "c8g", "c9g"))}
    rows = [
        {"platform": platform, "shard_id": shard_id}
        for platform, shard_id in sorted(
            eligible, key=lambda value: (platform_order.get(value[0], 99), value[1]))
    ]
    return ("platform", "shard_id"), rows


def gap_observed(reason, row):
    mapping = {
        "precision-rejected": "at least one host exceeded the precision gate",
        "host-cv-above-5-percent": f"CV={row['host_cv']}",
        "native-bracket-drift-above-5-percent": f"drift={row['maximum_native_bracket_drift']}",
        "allocation-free-row-allocated": f"median allocation={row['median_allocation_bytes']} B/op",
        "contract-identity-mismatch": "multiple contract identities",
        "scaling-class-mismatch": f"classes={row['scaling_class']}",
    }
    return mapping[reason]


def gap_threshold(reason):
    return {
        "precision-rejected": "all host rows accepted",
        "host-cv-above-5-percent": "CV <= 0.05",
        "native-bracket-drift-above-5-percent": "drift <= 0.05",
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
        row_id = ""
        if event["details"]:
            try:
                details = json.loads(event["details"])
            except json.JSONDecodeError:
                details = None
            if isinstance(details, dict) and details.get("scope") in {"Rebar", "Joni"}:
                scope = details["scope"]
                row_id = details.get("name", details.get("benchmark", ""))
        rows.append(gap_row(
            severity, scope, event["platform"], event["shard_id"], row_id, "",
            event["host_epoch"], event["event_type"], event["reason"], "n/a", event["details"], status))
    return rows


def build_rebar_outcomes(sessions):
    fields = (
        "campaign_id", "platform", "shard_id", "replica_id", "instance_id", "host_epoch",
        *REBAR_OUTCOME_FIELDS,
    )
    rows = []
    gaps = []
    for session in sessions:
        receipt = session.receipt
        for outcome in session.rebar_outcomes:
            rows.append({
                **{field: receipt[field] for field in (
                    "campaign_id", "platform", "shard_id", "replica_id", "instance_id", "host_epoch")},
                **outcome,
            })
            if outcome["outcome"] == "semantic-mismatch":
                classification = "semantic-mismatch"
                baseline = "informational corpus"
                conclusion = "Extended Rebar row is recorded without performance comparison"
            else:
                classification = "did-not-finish"
                baseline = "Joni"
                conclusion = "Compatible Joni row exceeded the fixed execution limit"
            gaps.append(gap_row(
                "recorded", "Rebar", receipt["platform"], receipt["shard_id"], outcome["row_id"], "",
                receipt["host_epoch"], classification, outcome["detail"], baseline,
                conclusion, "recorded"))
    rows.sort(key=lambda row: (
        row["platform"], row["row_id"], int(row["replica_id"]), row["host_epoch"]))
    return fields, rows, gaps


def build_inventory(sessions, artifact_indexes, campaign_artifact):
    fields = (
        "campaign_id", "platform", "shard_id", "replica_id", "host_epoch", "confirmation",
        "logical_name", "local_path", "size_bytes", "sha256", "artifact_set_sha256",
        "artifact_uri", "artifact_sha256", "retrieved_at", "campaign_s3_uri",
        "campaign_version_id", "campaign_archive_sha256", "candidate_commit", "candidate_ref",
        "candidate_archive_sha256", "engine_tree", "evidence_manifest_sha256",
        "campaign_source_directory", "campaign_file_count", "campaign_source_manifest_sha256",
    )
    rows = []
    for session in sessions:
        index = artifact_indexes.get(session.key, {})
        for path in sorted(
                (item for item in session.directory.rglob("*") if item.is_file()),
                key=lambda item: str(item.relative_to(session.directory))):
            name = str(path.relative_to(session.directory))
            rows.append({
                **{field: session.receipt[field] for field in (
                    "campaign_id", "platform", "shard_id", "replica_id", "host_epoch")},
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
        row["host_epoch"], row["logical_name"]))
    return fields, rows


def build_metadata(sessions, artifact_indexes, campaign_artifact):
    fields = ("platform", "shard_id", "replica_id", "host_epoch", "source", "key", "value")
    rows = []
    for session in sessions:
        prefix = {field: session.receipt[field] for field in ("platform", "shard_id", "replica_id", "host_epoch")}
        for key, value in read_properties(session.directory / "environment-manifest.txt"):
            rows.append({**prefix, "source": "environment-manifest.txt", "key": key, "value": value})
        metadata_artifacts = sorted(
            row["artifact"] for row in session.evidence_rows
            if row["artifact"].endswith("/run-metadata.txt"))
        for artifact in metadata_artifacts:
            for key, value in read_properties(session.directory / artifact):
                rows.append({**prefix, "source": artifact, "key": key, "value": value})
        for key in ("candidate_commit", "candidate_archive_sha256", "engine_tree", "evidence_manifest_sha256"):
            rows.append({**prefix, "source": "acceptance-receipt.tsv", "key": key,
                         "value": session.receipt[key]})
        for key, value in sorted(artifact_indexes.get(session.key, {}).items()):
            if not key.startswith("_"):
                rows.append({**prefix, "source": "artifact-index", "key": key, "value": value})
        for key, value in sorted(campaign_artifact.items()):
            rows.append({**prefix, "source": "campaign-artifact", "key": key, "value": value})
    rows.sort(key=lambda row: tuple(row[field] for field in fields))
    return fields, rows


def build_capacity(sessions):
    fields = (
        "campaign_id", "platform", "shard_id", "replica_id", "host_epoch", "confirmation",
        "wall_seconds", "maximum_resident_kibibytes", "memory_total_bytes",
        "memory_available_before_bytes", "memory_available_after_bytes", "exit_status",
        "host_capacity_sha256",
    )
    rows = []
    for session in sessions:
        rows.append({
            **{field: session.receipt[field] for field in (
                "campaign_id", "platform", "shard_id", "replica_id", "host_epoch")},
            "confirmation": str(session.confirmation).lower(),
            **{field: session.capacity[field] for field in (
                "wall_seconds", "maximum_resident_kibibytes", "memory_total_bytes",
                "memory_available_before_bytes", "memory_available_after_bytes", "exit_status")},
            "host_capacity_sha256": sha256_file(session.directory / "host-capacity.txt"),
        })
    rows.sort(key=lambda row: (
        row["platform"], row["shard_id"], int(row["replica_id"]), row["host_epoch"]))
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
    verify_receipt_coverage(primary, confirmation, platforms, shards, manifest_rows, manifest_digest)
    sessions, observed = load_sessions(
        primary, confirmation, arguments.artifacts_root, arguments.session_dir,
        manifest_rows, manifest_digest)
    indexes = load_artifact_indexes(arguments.artifact_index)
    unknown_indexes = sorted(set(indexes) - {session.key for session in sessions})
    if unknown_indexes:
        raise ReductionError(f"artifact index contains unknown accepted sessions: {unknown_indexes[:10]}")
    for session in sessions:
        index = indexes.get(session.key)
        if index and any(index[field] != session.receipt[field] for field in SESSION_FIELDS):
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
    events = load_events(arguments.events)
    events.extend(load_rebar_exclusions(arguments.rebar_exclusions, primary[0]["campaign_id"]))
    events.extend(load_rebar_joni_applicability(
        arguments.rebar_joni_applicability, manifest_rows, primary[0]["campaign_id"]))
    events.sort(key=lambda row: tuple(row[field] for field in EVENT_FIELDS))
    manifest_by_id = {row["row_id"]: row for row in manifest_rows}
    host_fields, host_rows = build_host_rows(
        sessions, observed, manifest_by_id, indexes, campaign_artifact)
    comparison_fields, comparisons = build_comparisons(host_rows, manifest_rows)
    scaling_fields, scaling, membership, scaling_mismatches, scaling_classes = build_scaling(host_rows, manifest_rows)
    aggregate_fields, row_aggregates, row_gaps = build_row_aggregates(
        host_rows, membership, scaling_mismatches, scaling_classes)
    comparison_aggregate_fields, comparison_aggregates, comparison_gaps = build_comparison_aggregates(comparisons)
    class_fields, classes = build_classes(comparison_aggregates)
    confirmation_fields, confirmation_jobs = build_confirmation_jobs(
        row_aggregates, host_rows, comparisons, scaling, sessions)
    inventory_fields, inventory = build_inventory(sessions, indexes, campaign_artifact)
    metadata_fields, metadata = build_metadata(sessions, indexes, campaign_artifact)
    capacity_fields, capacity = build_capacity(sessions)
    rebar_outcome_fields, rebar_outcomes, rebar_outcome_gaps = build_rebar_outcomes(sessions)
    gaps = row_gaps + comparison_gaps + rebar_outcome_gaps + event_gaps(events)
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
        "events.tsv": (EVENT_FIELDS, events),
        "artifact-inventory.tsv": (inventory_fields, inventory),
        "metadata.tsv": (metadata_fields, metadata),
        "capacity.tsv": (capacity_fields, capacity),
        "rebar-outcomes.tsv": (rebar_outcome_fields, rebar_outcomes),
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
        "platforms": [name for name in ("c8i", "c8g", "c9g") if name in platforms],
        "shards": sorted(shards),
        "primary_sessions": len(primary),
        "rebar_outcomes": len(rebar_outcomes),
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
    argument_parser.add_argument("--rebar-exclusions", type=Path, action="append", default=[])
    argument_parser.add_argument("--rebar-joni-applicability", type=Path)
    argument_parser.add_argument("--artifact-index", type=Path, action="append", default=[])
    argument_parser.add_argument("--campaign-artifact", type=Path)
    argument_parser.add_argument("--output-dir", type=Path, required=True)
    argument_parser.add_argument("--expected-manifest-rows", type=int, default=5475)
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
