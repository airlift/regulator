#!/usr/bin/env python3

import csv
import hashlib
import math
import re
from collections import Counter
from pathlib import Path
import topology


SCHEMA_VERSION = "2"
EXPECTED_PLATFORMS = {
    "r8i": ("intel", "r8i."),
    "r8g": ("arm", "r8g."),
    "r9g": ("arm", "r9g."),
}
SESSION_FIELDS = (
    "schema_version",
    "campaign_id",
    "platform",
    "shard_id",
    "replica_id",
    "instance_id",
    "host_epoch",
    "architecture",
    "instance_type",
    "availability_zone",
    "systems",
)
RECEIPT_FIELDS = SESSION_FIELDS + (
    "row_count",
    "manifest_sha256",
    "expected_rows_sha256",
    "observed_rows_sha256",
    "evidence_manifest_sha256",
    "candidate_commit",
    "candidate_archive_sha256",
    "engine_tree",
    "heap_size",
)
EVIDENCE_FIELDS = ("route", "artifact", "verification", "sha256")
SHA256 = re.compile(r"[0-9a-f]{64}")
GIT_OBJECT_ID = re.compile(r"[0-9a-f]{40}|[0-9a-f]{64}")
CAPACITY_PREFIX_FIELDS = (
    "memory_total_bytes",
    "memory_available_before_bytes",
)
ROUTE_REQUIRED_ARTIFACTS = {
    "route-session.tsv",
    "semantic-gate.tsv",
    "semantic-evidence.tsv",
    "calibration.tsv",
    "observed-rows.tsv",
    "observed-rows.tsv.sha256",
    "raw-artifacts.sha256",
    "run-metadata.txt",
    "status.txt",
}
PROTOCOL_QUALIFICATION_FIELDS = (
    "manifest_row_id",
    "system",
    "route",
    "benchmark",
    "parameters",
    "reference_protocol",
    "specialized_protocol",
    "reference_score",
    "specialized_score",
    "score_unit",
    "reference_sample_count",
    "specialized_sample_count",
    "reference_cv",
    "specialized_cv",
    "relative_difference",
    "maximum_relative_difference",
    "outcome",
)


class ValidationError(ValueError):
    pass


def exceeds_published_limit(value, limit):
    return float(f"{value:.12g}") > limit


def sha256_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_properties(path):
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ValidationError(f"cannot read {path}: {error}") from error
    values = {}
    for line_number, line in enumerate(lines, 1):
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise ValidationError(f"invalid property line {path}:{line_number}")
        values[key] = value
    return values


def capacity_prefix_sha256(path):
    values = read_properties(path)
    missing = [field for field in CAPACITY_PREFIX_FIELDS if field not in values]
    if missing:
        raise ValidationError(f"{path} is missing capacity identity fields: {missing}")
    canonical = "".join(f"{field}={values[field]}\n" for field in CAPACITY_PREFIX_FIELDS)
    return hashlib.sha256(canonical.encode()).hexdigest()


def safe_evidence_path(root, value, context):
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise ValidationError(f"{context} has unsafe artifact path {value!r}")
    resolved = (root / path).resolve()
    try:
        resolved.relative_to(root.resolve())
    except ValueError as error:
        raise ValidationError(f"{context} escapes the host evidence directory: {value!r}") from error
    return resolved


def verify_sha256_manifest(path, root):
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ValidationError(f"cannot read {path}: {error}") from error
    listed = set()
    for line_number, line in enumerate(lines, 1):
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if match is None:
            raise ValidationError(f"invalid SHA-256 manifest line {path}:{line_number}")
        digest, relative = match.groups()
        artifact = safe_evidence_path(root, relative, f"{path}:{line_number}")
        if relative in listed:
            raise ValidationError(f"{path} lists duplicate artifact {relative!r}")
        listed.add(relative)
        if not artifact.is_file() or sha256_file(artifact) != digest:
            raise ValidationError(f"raw artifact checksum mismatch: {artifact}")
    actual = {
        str(artifact.relative_to(root))
        for directory in (root / "raw", root / "logs")
        if directory.is_dir()
        for artifact in directory.rglob("*")
        if artifact.is_file()
    }
    if listed != actual:
        raise ValidationError(
            f"{path} does not exactly cover raw and log artifacts: "
            f"missing={format_values(sorted(actual - listed))}; "
            f"unexpected={format_values(sorted(listed - actual))}")


def verify_observed_rows_checksum(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    if len(lines) != 1:
        raise ValidationError(f"{path} must contain exactly one checksum")
    match = re.fullmatch(r"([0-9a-f]{64})  observed-rows.tsv", lines[0])
    if match is None:
        raise ValidationError(f"{path} has an invalid observed-row checksum record")
    observed = path.parent / "observed-rows.tsv"
    if not observed.is_file() or sha256_file(observed) != match.group(1):
        raise ValidationError(f"observed-row checksum mismatch: {observed}")


def verify_protocol_qualification(path, route, systems, expected_count):
    rows = read_tsv(path, PROTOCOL_QUALIFICATION_FIELDS, PROTOCOL_QUALIFICATION_FIELDS)
    if len(rows) != expected_count:
        raise ValidationError(
            f"{path} has {len(rows)} protocol representatives, expected {expected_count}")
    identifiers = [row["manifest_row_id"] for row in rows]
    duplicates = duplicate_values(identifiers)
    if duplicates:
        raise ValidationError(f"{path} has duplicate protocol representatives: {duplicates}")
    for line_number, row in enumerate(rows, 2):
        context = f"{path}:{line_number}"
        if row["route"] != route:
            raise ValidationError(f"{context} route does not match {route!r}")
        if row["system"] not in systems:
            raise ValidationError(f"{context} system is not part of the route")
        if row["reference_protocol"] != "5-forks-10x1s":
            raise ValidationError(f"{context} has an unexpected reference protocol")
        if not re.fullmatch(
                r"5-independent-jvms-10x(?:50|300)ms-warmup-10x50ms-measurement",
                row["specialized_protocol"]):
            raise ValidationError(f"{context} has an unexpected specialized protocol")
        try:
            reference_samples = int(row["reference_sample_count"])
            specialized_samples = int(row["specialized_sample_count"])
            reference_score = float(row["reference_score"])
            specialized_score = float(row["specialized_score"])
            reference_cv = float(row["reference_cv"])
            specialized_cv = float(row["specialized_cv"])
            relative_difference = float(row["relative_difference"])
            maximum_difference = float(row["maximum_relative_difference"])
        except ValueError as error:
            raise ValidationError(f"{context} has invalid protocol statistics") from error
        statistics = (
            reference_score,
            specialized_score,
            reference_cv,
            specialized_cv,
            relative_difference,
            maximum_difference,
        )
        if (any(not math.isfinite(value) for value in statistics) or
                reference_score <= 0 or specialized_score <= 0 or
                reference_cv < 0 or specialized_cv < 0 or
                relative_difference < 0 or maximum_difference < 0):
            raise ValidationError(f"{context} has invalid protocol statistics")
        if reference_samples < 50 or specialized_samples < 50:
            raise ValidationError(f"{context} has fewer than 50 protocol samples")
        if reference_samples != 50 or specialized_samples != 50:
            raise ValidationError(f"{context} must contain exactly 50 protocol samples")
        if maximum_difference != 0.05:
            raise ValidationError(f"{context} protocol threshold must be 0.05")
        expected_difference = abs(specialized_score - reference_score) / min(reference_score, specialized_score)
        if not math.isclose(relative_difference, expected_difference, rel_tol=1e-12, abs_tol=1e-15):
            raise ValidationError(f"{context} relative difference does not match the protocol scores")
        if exceeds_published_limit(relative_difference, maximum_difference):
            raise ValidationError(f"{context} exceeds the protocol threshold")
        reference_rse = reference_cv / math.sqrt(reference_samples)
        specialized_rse = specialized_cv / math.sqrt(specialized_samples)
        if (exceeds_published_limit(reference_rse, 0.05) or
                exceeds_published_limit(specialized_rse, 0.05)):
            raise ValidationError(f"{context} exceeds the 5% relative standard error threshold")
        if row["outcome"] != "accepted":
            raise ValidationError(f"{context} protocol outcome is not accepted")


def verify_evidence_manifest(path, root):
    rows = read_tsv(path, EVIDENCE_FIELDS, EVIDENCE_FIELDS)
    if not rows:
        raise ValidationError(f"{path} has no evidence rows")
    artifacts = [row["artifact"] for row in rows]
    duplicates = duplicate_values(artifacts)
    if duplicates:
        raise ValidationError(f"{path} has duplicate artifacts: {format_values(duplicates)}")
    for line_number, row in enumerate(rows, 2):
        context = f"{path}:{line_number}"
        artifact = safe_evidence_path(root, row["artifact"], context)
        if not artifact.is_file() or artifact.stat().st_size == 0:
            raise ValidationError(f"required host evidence is missing or empty: {artifact}")
        if not SHA256.fullmatch(row["sha256"]):
            raise ValidationError(f"{context} has an invalid SHA-256 digest")
        if row["verification"] == "sha256":
            digest = sha256_file(artifact)
        elif row["verification"] == "capacity-prefix-sha256":
            if row["artifact"] != "host-capacity.txt":
                raise ValidationError(f"{context} uses capacity-prefix verification for another artifact")
            digest = capacity_prefix_sha256(artifact)
        else:
            raise ValidationError(f"{context} has unknown verification mode {row['verification']!r}")
        if digest != row["sha256"]:
            raise ValidationError(f"host evidence checksum mismatch: {artifact}")
        if artifact.name == "raw-artifacts.sha256":
            verify_sha256_manifest(artifact, artifact.parent)
        elif artifact.name == "observed-rows.tsv.sha256":
            verify_observed_rows_checksum(artifact)

    listed = set(artifacts)
    if (root / "host-capacity.txt").is_file() and "host-capacity.txt" not in listed:
        raise ValidationError("host-capacity.txt exists but is not bound by host evidence")
    for line_number, row in enumerate(rows, 2):
        route = row["route"]
        artifact = row["artifact"]
        if route == "host":
            if artifact.startswith("routes/"):
                raise ValidationError(
                    f"{path}:{line_number} labels route evidence as host evidence")
        elif not artifact.startswith(f"routes/{route}/"):
            raise ValidationError(
                f"{path}:{line_number} route label does not match artifact path")
    route_names = {row["route"] for row in rows if row["route"] != "host"}
    if not route_names:
        raise ValidationError(f"{path} contains no route evidence")
    for route in route_names:
        prefix = f"routes/{route}/"
        route_artifacts = {
            artifact.removeprefix(prefix)
            for artifact in listed
            if artifact.startswith(prefix)
        }
        missing = ROUTE_REQUIRED_ARTIFACTS - route_artifacts
        if missing:
            raise ValidationError(f"{path} route {route!r} is missing evidence: {sorted(missing)}")
        route_directory = root / "routes" / route
        actual_route_artifacts = {
            artifact.relative_to(route_directory).as_posix()
            for artifact in route_directory.rglob("*")
            if artifact.is_file()
            and artifact.relative_to(route_directory).parts[0] not in {"raw", "logs"}
        }
        if route_artifacts != actual_route_artifacts:
            raise ValidationError(
                f"{path} route {route!r} evidence is incomplete: "
                f"missing={format_values(sorted(actual_route_artifacts - route_artifacts))}; "
                f"unexpected={format_values(sorted(route_artifacts - actual_route_artifacts))}")
        session = read_one_tsv(root / prefix / "route-session.tsv", SESSION_FIELDS)
        systems = parse_systems(session["systems"], str(root / prefix / "route-session.tsv"))
        if {"native-re2-before", "native-re2-after"}.issubset(systems) and "native-bracket.tsv" not in route_artifacts:
            raise ValidationError(f"{path} route {route!r} is missing native-bracket.tsv")
        metadata = read_properties(route_directory / "run-metadata.txt")
        qualification_required = metadata.get("protocol_qualification_required")
        if qualification_required not in {"true", "false"}:
            raise ValidationError(
                f"{route_directory / 'run-metadata.txt'} has invalid protocol_qualification_required")
        qualification_artifact = "protocol-qualification.tsv"
        if qualification_required == "true":
            if qualification_artifact not in route_artifacts:
                raise ValidationError(f"{path} route {route!r} is missing {qualification_artifact}")
            try:
                expected_count = int(metadata["protocol_representative_count"])
            except (KeyError, ValueError) as error:
                raise ValidationError(
                    f"{route_directory / 'run-metadata.txt'} has invalid protocol_representative_count") from error
            if expected_count <= 0:
                raise ValidationError(
                    f"{route_directory / 'run-metadata.txt'} has no protocol representatives")
            verify_protocol_qualification(
                route_directory / qualification_artifact, route, systems, expected_count)
        elif qualification_artifact in route_artifacts:
            raise ValidationError(
                f"{path} route {route!r} has unexpected {qualification_artifact}")

    environment_path = root / "environment-manifest.txt"
    if environment_path.is_file():
        environment = read_properties(environment_path)
        if environment.get("verification_status") == "verified":
            required_host = {
                "environment-manifest.txt",
                "environment.txt",
                "route-plan.tsv",
                "expected-rows.tsv",
                "observed-rows.tsv",
                "session.tsv",
                "host-capacity.txt",
            }
            missing = required_host - listed
            if missing:
                raise ValidationError(f"{path} is missing formal host evidence: {sorted(missing)}")
            for key in (
                    "row_manifest_sha256",
                    "platform_manifest_sha256",
                    "comparator_manifest_sha256",
                    "protocol_representatives_sha256",
                    "regulator_archive_sha256"):
                if SHA256.fullmatch(environment.get(key, "")) is None:
                    raise ValidationError(f"{environment_path} has invalid {key}")
            joni_scope = environment.get("joni_evidence_scope", "")
            if joni_scope != "not-used":
                if environment.get("trino_used") != "true":
                    raise ValidationError(f"{environment_path} uses Joni without pinned Trino provenance")
                if GIT_OBJECT_ID.fullmatch(environment.get("trino_commit", "")) is None:
                    raise ValidationError(f"{environment_path} has invalid trino_commit")
                if SHA256.fullmatch(environment.get("trino_archive_sha256", "")) is None:
                    raise ValidationError(f"{environment_path} has invalid trino_archive_sha256")
    return rows


def normalize_heap_size(value):
    if not isinstance(value, str) or re.fullmatch(r"[1-9][0-9]*[kKmMgG]?", value) is None:
        raise ValidationError(f"invalid heap_size: {value!r}")
    return value.lower()


def evidence_candidate_identity(rows, root):
    metadata_paths = [
        safe_evidence_path(root, row["artifact"], "candidate evidence")
        for row in rows
        if row["artifact"].endswith("/run-metadata.txt") or row["artifact"] == "run-metadata.txt"
    ]
    if not metadata_paths:
        raise ValidationError("host evidence contains no route run metadata")
    identities = []
    for metadata_path in metadata_paths:
        values = read_properties(metadata_path)
        missing = [field for field in ("candidate_commit", "engine_tree", "heap_size") if not values.get(field)]
        if missing:
            raise ValidationError(f"{metadata_path} is missing candidate identity fields: {missing}")
        if GIT_OBJECT_ID.fullmatch(values["candidate_commit"]) is None:
            raise ValidationError(f"{metadata_path} has invalid candidate_commit")
        if GIT_OBJECT_ID.fullmatch(values["engine_tree"]) is None:
            raise ValidationError(f"{metadata_path} has invalid engine_tree")
        identities.append((values["candidate_commit"], values["engine_tree"], normalize_heap_size(values["heap_size"])))
    if len(set(identities)) != 1:
        raise ValidationError(f"route candidate identities differ: {sorted(set(identities))}")

    environment_path = root / "environment-manifest.txt"
    candidate_archive_sha256 = "not-recorded"
    if environment_path.is_file():
        if "environment-manifest.txt" not in {row["artifact"] for row in rows}:
            raise ValidationError("environment-manifest.txt exists but is not bound by host evidence")
        environment = read_properties(environment_path)
        if normalize_heap_size(environment.get("benchmark_heap_size")) != identities[0][2]:
            raise ValidationError("environment heap_size differs from route metadata")
        recorded_commit = environment.get("regulator_commit")
        if recorded_commit not in {None, "unknown"} and recorded_commit != identities[0][0]:
            raise ValidationError("environment candidate commit differs from route metadata")
        recorded_archive = environment.get("regulator_archive_sha256")
        if recorded_archive not in {None, "unknown"}:
            if SHA256.fullmatch(recorded_archive) is None:
                raise ValidationError("environment has invalid candidate archive SHA-256")
            candidate_archive_sha256 = recorded_archive
    return identities[0][0], candidate_archive_sha256, identities[0][1], identities[0][2]


def row_id_digest(row_ids):
    value = "".join(f"{row_id}\n" for row_id in sorted(row_ids))
    return hashlib.sha256(value.encode()).hexdigest()


def read_tsv(path, required_fields, exact_fields=None):
    try:
        input_file = path.open(newline="")
    except OSError as error:
        raise ValidationError(f"cannot read {path}: {error}") from error

    with input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        fields = reader.fieldnames
        if fields is None:
            raise ValidationError(f"{path} is empty")
        duplicates = sorted(field for field, count in Counter(fields).items() if count > 1)
        if duplicates:
            raise ValidationError(f"{path} has duplicate columns: {duplicates}")
        if exact_fields is not None and tuple(fields) != tuple(exact_fields):
            raise ValidationError(f"{path} columns must be {list(exact_fields)}, found {fields}")
        missing_fields = sorted(set(required_fields) - set(fields))
        if missing_fields:
            raise ValidationError(f"{path} is missing columns: {missing_fields}")

        rows = []
        for line_number, row in enumerate(reader, start=2):
            if None in row:
                raise ValidationError(f"{path}:{line_number} has more values than columns")
            empty_fields = [field for field in required_fields if not row[field]]
            if empty_fields:
                raise ValidationError(f"{path}:{line_number} has empty fields: {empty_fields}")
            rows.append(row)
        return rows


def read_one_tsv(path, fields):
    rows = read_tsv(path, fields, fields)
    if len(rows) != 1:
        raise ValidationError(f"{path} must contain exactly one data row, found {len(rows)}")
    return rows[0]


def load_rows_manifest(path):
    rows = read_tsv(path, ("row_id", "shard_id", "system"))
    duplicate_ids = duplicate_values(row["row_id"] for row in rows)
    if duplicate_ids:
        raise ValidationError(f"{path} has duplicate row IDs: {format_values(duplicate_ids)}")
    if not rows:
        raise ValidationError(f"{path} has no benchmark rows")
    return rows


def duplicate_values(values):
    return sorted(value for value, count in Counter(values).items() if count > 1)


def format_values(values, limit=10):
    values = list(values)
    suffix = "" if len(values) <= limit else f" ... ({len(values)} total)"
    return f"{values[:limit]}{suffix}"


def parse_systems(value, context):
    systems = value.split(",")
    if any(not system for system in systems):
        raise ValidationError(f"{context} has an empty system in {value!r}")
    duplicates = duplicate_values(systems)
    if duplicates:
        raise ValidationError(f"{context} has duplicate systems: {duplicates}")
    return frozenset(systems)


def canonical_systems(systems):
    return ",".join(sorted(systems))


def parse_primary_replica(value, context):
    if value not in {"1", "2", "3"}:
        raise ValidationError(f"{context} replica_id must be 1, 2, or 3, found {value!r}")
    return int(value)


def parse_confirmation_replica(value, context):
    if value != "4":
        raise ValidationError(f"{context} confirmation replica_id must be 4, found {value!r}")
    return 4


def expected_rows(manifest_rows, shard_id, systems):
    shard_rows = [row for row in manifest_rows if row["shard_id"] == shard_id]
    if not shard_rows:
        raise ValidationError(f"unknown or empty shard {shard_id!r}")
    shard_systems = {row["system"] for row in shard_rows}
    unknown_systems = sorted(systems - shard_systems)
    if unknown_systems:
        raise ValidationError(f"shard {shard_id!r} does not contain systems: {unknown_systems}")
    return {row["row_id"]: row for row in shard_rows if row["system"] in systems}


def validate_host_results(manifest_path, session_path, observed_path):
    return _validate_host_results(manifest_path, session_path, observed_path, parse_primary_replica)


def validate_confirmation_host_results(manifest_path, session_path, observed_path):
    return _validate_host_results(manifest_path, session_path, observed_path, parse_confirmation_replica)


def _validate_host_results(manifest_path, session_path, observed_path, replica_parser):
    manifest_rows = load_rows_manifest(manifest_path)
    session = read_one_tsv(session_path, SESSION_FIELDS)
    if session["schema_version"] != SCHEMA_VERSION:
        raise ValidationError(
            f"{session_path} schema_version must be {SCHEMA_VERSION}, found {session['schema_version']!r}")
    replica_parser(session["replica_id"], str(session_path))

    systems = parse_systems(session["systems"], str(session_path))
    expected = expected_rows(manifest_rows, session["shard_id"], systems)
    observed_rows = read_tsv(observed_path, ("row_id", "shard_id", "system"))
    observed_ids = [row["row_id"] for row in observed_rows]
    duplicates = duplicate_values(observed_ids)
    missing = sorted(set(expected) - set(observed_ids))
    unexpected = sorted(set(observed_ids) - set(expected))

    problems = []
    if missing:
        problems.append(f"missing rows={format_values(missing)}")
    if duplicates:
        problems.append(f"duplicate rows={format_values(duplicates)}")
    if unexpected:
        problems.append(f"unexpected rows={format_values(unexpected)}")

    for line_number, row in enumerate(observed_rows, start=2):
        expected_row = expected.get(row["row_id"])
        if expected_row is None:
            continue
        if row["shard_id"] != expected_row["shard_id"]:
            problems.append(
                f"{observed_path}:{line_number} shard_id is {row['shard_id']!r}, "
                f"expected {expected_row['shard_id']!r}")
        if row["system"] != expected_row["system"]:
            problems.append(
                f"{observed_path}:{line_number} system is {row['system']!r}, "
                f"expected {expected_row['system']!r}")

    if problems:
        raise ValidationError("observed-row acceptance failed: " + "; ".join(problems))

    evidence_path = session_path.parent / "route-evidence.tsv"
    evidence_rows = verify_evidence_manifest(evidence_path, session_path.parent)
    candidate_commit, candidate_archive_sha256, engine_tree, heap_size = evidence_candidate_identity(
        evidence_rows, session_path.parent)

    receipt = {field: session[field] for field in SESSION_FIELDS}
    receipt["systems"] = canonical_systems(systems)
    receipt.update({
        "row_count": str(len(expected)),
        "manifest_sha256": sha256_file(manifest_path),
        "expected_rows_sha256": row_id_digest(expected),
        "observed_rows_sha256": sha256_file(observed_path),
        "evidence_manifest_sha256": sha256_file(evidence_path),
        "candidate_commit": candidate_commit,
        "candidate_archive_sha256": candidate_archive_sha256,
        "engine_tree": engine_tree,
        "heap_size": heap_size,
    })
    return receipt


def write_receipt(path, receipt):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=RECEIPT_FIELDS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerow(receipt)


def load_platforms(path):
    fields = ("platform", "architecture", "instance_type", "vcpus",
              "concurrency_instance_type", "concurrency_vcpus")
    rows = read_tsv(path, fields)
    duplicates = duplicate_values(row["platform"] for row in rows)
    if duplicates:
        raise ValidationError(f"{path} has duplicate platforms: {duplicates}")
    platforms = {row["platform"]: row for row in rows}
    if set(platforms) != set(EXPECTED_PLATFORMS):
        raise ValidationError(
            f"platform identities must be {sorted(EXPECTED_PLATFORMS)}, found {sorted(platforms)}")
    for platform, (architecture, instance_prefix) in EXPECTED_PLATFORMS.items():
        row = platforms[platform]
        try:
            topology.validate(row)
        except ValueError as error:
            raise ValidationError(f"platform {platform}: {error}") from error
    return platforms


def load_shards(path):
    rows = read_tsv(path, ("shard_id",))
    duplicates = duplicate_values(row["shard_id"] for row in rows)
    if duplicates:
        raise ValidationError(f"{path} has duplicate shards: {duplicates}")
    shards = {row["shard_id"] for row in rows}
    if not shards:
        raise ValidationError(f"{path} has no shards")
    return shards


def validate_campaign(
        manifest_path,
        platforms_path,
        shards_path,
        receipts_path,
        campaign_id,
        expected_replica_ids=(1, 2, 3),
        selected_shards=None):
    manifest_rows = load_rows_manifest(manifest_path)
    platforms = load_platforms(platforms_path)
    shards = load_shards(shards_path)
    receipts = read_tsv(receipts_path, RECEIPT_FIELDS, RECEIPT_FIELDS)
    manifest_digest = sha256_file(manifest_path)

    manifest_shards = {row["shard_id"] for row in manifest_rows}
    if manifest_shards != shards:
        raise ValidationError(
            f"manifest/shard mismatch: missing={sorted(shards - manifest_shards)}, "
            f"unexpected={sorted(manifest_shards - shards)}")
    if selected_shards is not None:
        selected = set(selected_shards)
        if not selected or not selected <= shards or len(selected) != len(selected_shards):
            raise ValidationError('invalid or duplicate selected shards')
        shards = selected

    expected_keys = {
        (platform, shard_id, replica_id)
        for platform in platforms
        for shard_id in shards
        for replica_id in expected_replica_ids
    }
    observed_keys = []
    instance_ids = []
    host_epochs = []
    candidate_identities = []

    systems_by_shard = {
        shard_id: {row["system"] for row in manifest_rows if row["shard_id"] == shard_id}
        for shard_id in shards
    }
    expected_by_shard = {
        shard_id: expected_rows(manifest_rows, shard_id, systems_by_shard[shard_id])
        for shard_id in shards
    }

    for line_number, receipt in enumerate(receipts, start=2):
        context = f"{receipts_path}:{line_number}"
        if receipt["schema_version"] != SCHEMA_VERSION:
            raise ValidationError(
                f"{context} schema_version must be {SCHEMA_VERSION}, found {receipt['schema_version']!r}")
        if receipt["campaign_id"] != campaign_id:
            raise ValidationError(
                f"{context} campaign_id is {receipt['campaign_id']!r}, expected {campaign_id!r}")
        platform = receipt["platform"]
        if platform not in platforms:
            raise ValidationError(f"{context} has unexpected platform {platform!r}")
        shard_id = receipt["shard_id"]
        if shard_id not in shards:
            raise ValidationError(f"{context} has unexpected shard {shard_id!r}")
        replica_id = parse_primary_replica(receipt["replica_id"], context)
        observed_keys.append((platform, shard_id, replica_id))
        instance_ids.append(receipt["instance_id"])
        host_epochs.append(receipt["host_epoch"])
        candidate_identities.append((
            receipt["candidate_commit"],
            receipt["candidate_archive_sha256"],
            receipt["engine_tree"],
            normalize_heap_size(receipt["heap_size"]),
        ))

        platform_row = platforms[platform]
        if receipt["architecture"] != platform_row["architecture"]:
            raise ValidationError(
                f"{context} architecture is {receipt['architecture']!r}, "
                f"expected {platform_row['architecture']!r}")
        expected_instance = topology.instance_type(platform_row, shard_id)
        if receipt["instance_type"] != expected_instance:
            raise ValidationError(
                f"{context} instance_type is {receipt['instance_type']!r}, "
                f"expected {expected_instance!r}")

        systems = parse_systems(receipt["systems"], context)
        if receipt["systems"] != canonical_systems(systems):
            raise ValidationError(
                f"{context} systems must be sorted canonically as {canonical_systems(systems)!r}")
        if systems != systems_by_shard[shard_id]:
            raise ValidationError(
                f"{context} systems do not cover shard {shard_id!r}: "
                f"missing={sorted(systems_by_shard[shard_id] - systems)}, "
                f"unexpected={sorted(systems - systems_by_shard[shard_id])}")
        expected = expected_by_shard[shard_id]
        if receipt["row_count"] != str(len(expected)):
            raise ValidationError(
                f"{context} row_count is {receipt['row_count']!r}, expected {len(expected)}")
        if receipt["manifest_sha256"] != manifest_digest:
            raise ValidationError(f"{context} manifest_sha256 does not match {manifest_path}")
        if receipt["expected_rows_sha256"] != row_id_digest(expected):
            raise ValidationError(f"{context} expected_rows_sha256 does not match shard {shard_id!r}")
        observed_digest = receipt["observed_rows_sha256"]
        if len(observed_digest) != 64 or any(character not in "0123456789abcdef" for character in observed_digest):
            raise ValidationError(f"{context} observed_rows_sha256 is not a lowercase SHA-256 digest")
        if not SHA256.fullmatch(receipt["evidence_manifest_sha256"]):
            raise ValidationError(f"{context} evidence_manifest_sha256 is not a lowercase SHA-256 digest")
        if GIT_OBJECT_ID.fullmatch(receipt["candidate_commit"]) is None:
            raise ValidationError(f"{context} candidate_commit is not a Git object ID")
        if receipt["candidate_archive_sha256"] != "not-recorded" and not SHA256.fullmatch(
                receipt["candidate_archive_sha256"]):
            raise ValidationError(f"{context} candidate_archive_sha256 is invalid")
        if GIT_OBJECT_ID.fullmatch(receipt["engine_tree"]) is None:
            raise ValidationError(f"{context} engine_tree is not a Git object ID")

    duplicate_keys = duplicate_values(observed_keys)
    missing_keys = sorted(expected_keys - set(observed_keys))
    unexpected_keys = sorted(set(observed_keys) - expected_keys)
    if duplicate_keys or missing_keys or unexpected_keys:
        raise ValidationError(
            "campaign session coverage failed: "
            f"missing={format_values(missing_keys)}; "
            f"duplicate={format_values(duplicate_keys)}; "
            f"unexpected={format_values(unexpected_keys)}")

    duplicate_instances = duplicate_values(instance_ids)
    if duplicate_instances:
        raise ValidationError(f"campaign reuses instance IDs: {format_values(duplicate_instances)}")
    duplicate_epochs = duplicate_values(host_epochs)
    if duplicate_epochs:
        raise ValidationError(f"campaign reuses host epochs: {format_values(duplicate_epochs)}")
    if len(set(candidate_identities)) != 1:
        raise ValidationError(
            f"campaign contains multiple candidate identities: {format_values(sorted(set(candidate_identities)))}")

    return {
        "campaign_id": campaign_id,
        "platform_count": len(platforms),
        "shard_count": len(shards),
        "accepted_sessions": len(receipts),
        "accepted_rows": sum(int(receipt["row_count"]) for receipt in receipts),
    }
