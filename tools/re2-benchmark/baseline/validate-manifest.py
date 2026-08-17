#!/usr/bin/env python3

import csv
import hashlib
import importlib.util
import sys
from collections import Counter
from pathlib import Path


EXPECTED_ROW_COUNT = 5475
EXPECTED_SHARD_COUNT = 37
EXPECTED_PLATFORMS = {
    "c8i": ("intel", "c8i.2xlarge", "8"),
    "c8g": ("arm", "c8g.2xlarge", "8"),
    "c9g": ("arm", "c9g.2xlarge", "8"),
}
EXPECTED_COMPARATORS = {
    "native-re2": "972a15cedd008d846f1a39b2e88ce48d7f166cbd",
    "rebar": "463d00f31887e84c38467805b9e3122c314b9521",
    "trino": "c7503d170344c4e266f03fdb39e53c82aa7e3594",
    "airlift-joni": "2.1.5.3",
    "temurin-x64": "25.0.4+7",
    "temurin-aarch64": "25.0.4+7",
}


def read_tsv(path):
    with path.open(newline="") as input_file:
        return list(csv.DictReader(input_file, delimiter="\t"))


def validate_jmh_route_coverage(rows, suite_rows, dispatch_rows):
    declared_systems = {
        (row["shard_id"], row["suite"]): set(row["systems"].split(","))
        for row in suite_rows
    }
    missing = []
    for dispatch in dispatch_rows:
        shard_id = dispatch["shard_id"]
        for handler_field, systems_field in (
                ("native_access_handler", "native_access_systems"),
                ("object_row_handler", "object_row_systems")):
            if dispatch[handler_field] != "jmh":
                continue
            for system in dispatch[systems_field].split(","):
                suites = {
                    row["suite"]
                    for row in rows
                    if row["shard_id"] == shard_id and row["system"] == system
                }
                for suite in suites:
                    if system not in declared_systems.get((shard_id, suite), set()):
                        missing.append(f"{shard_id}/{suite}/{system}")
    if missing:
        raise SystemExit(f"generic JMH route has undeclared suites: {sorted(missing)}")


def main():
    directory = Path(__file__).resolve().parent
    manifest = directory / "rows.tsv"
    expected_digest = (directory / "rows.sha256").read_text().split()[0]
    actual_digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
    if actual_digest != expected_digest:
        raise SystemExit(f"row manifest checksum mismatch: expected {expected_digest}, found {actual_digest}")

    shard_rows = read_tsv(directory / "shards.tsv")
    shards = {row["shard_id"] for row in shard_rows}
    if len(shard_rows) != len(shards) or len(shards) != EXPECTED_SHARD_COUNT:
        raise SystemExit(
            f"expected {EXPECTED_SHARD_COUNT} unique shards, "
            f"found {len(shard_rows)} rows and {len(shards)} identities")
    rows = read_tsv(manifest)
    if len(rows) != EXPECTED_ROW_COUNT:
        raise SystemExit(f"expected {EXPECTED_ROW_COUNT} rows, found {len(rows)}")

    identifiers = Counter(row["row_id"] for row in rows)
    duplicates = sorted(identifier for identifier, count in identifiers.items() if count != 1)
    if duplicates:
        raise SystemExit(f"duplicate row IDs: {duplicates[:10]}")
    observed_shards = {row["shard_id"] for row in rows}
    if observed_shards != shards:
        raise SystemExit(f"shard mismatch: missing={sorted(shards - observed_shards)}, unexpected={sorted(observed_shards - shards)}")
    required_fields = tuple(rows[0])
    for line_number, row in enumerate(rows, start=2):
        missing = [field for field in required_fields if not row[field]]
        if missing:
            raise SystemExit(f"row {line_number} has empty fields: {missing}")
        if not row["row_id"].startswith(f"{row['shard_id']}/"):
            raise SystemExit(f"row {line_number} ID does not begin with its shard")
        if "re2j" in "\t".join(row.values()).lower():
            raise SystemExit(f"row {line_number} contains the excluded historical RE2J comparator")
        if row["suite"].startswith("rebar-") and row["allocation_contract"] != "not-measured":
            raise SystemExit(f"row {line_number} claims unavailable Rebar allocation evidence")
        if (row["suite"] == "traditional" and row["system"].startswith("native-re2-") and
                row["allocation_contract"] != "not-measured"):
            raise SystemExit(f"row {line_number} claims unavailable native RE2 allocation evidence")
        if (row["system"].startswith("regulator") and not row["suite"].startswith("rebar-") and
                row["allocation_contract"] == "not-measured"):
            raise SystemExit(f"row {line_number} omits available Regulator allocation evidence")
        if row["allocation_contract"] == "operation-dependent":
            raise SystemExit(f"row {line_number} has an unresolved allocation contract")

    suite_rows = read_tsv(directory / "jmh-suites.tsv")
    declared_suites = {row["suite"] for row in suite_rows}
    observed_suites = {row["suite"] for row in rows}
    empty_suites = sorted(declared_suites - observed_suites)
    if empty_suites:
        raise SystemExit(f"declared JMH suites have no manifest rows: {empty_suites}")
    validate_jmh_route_coverage(rows, suite_rows, read_tsv(directory / "shard-dispatch.tsv"))

    specification = importlib.util.spec_from_file_location(
        "rebar_joni_applicability", directory / "rebar_joni_applicability.py")
    applicability_module = importlib.util.module_from_spec(specification)
    sys.modules["rebar_joni_applicability"] = applicability_module
    specification.loader.exec_module(applicability_module)
    applicability = applicability_module.load_applicability(directory.parents[2])
    expected_joni = {
        identity for identity, row in applicability.items()
        if row["status"] == "compatible"
    }
    actual_joni = {
        (row["benchmark"], row["parameters"].split(";", 1)[0].split("=", 1)[1])
        for row in rows
        if row["suite"].startswith("rebar-") and row["system"] == "joni"
    }
    if actual_joni != expected_joni:
        raise SystemExit(
            "Rebar Joni rows differ from applicability; "
            f"missing={sorted(expected_joni - actual_joni)[:20]}, "
            f"unexpected={sorted(actual_joni - expected_joni)[:20]}")

    public_compile_rows = [
        row for row in rows
        if row["benchmark"] == "io.airlift.regulator.BenchmarkJavaRegexpPublicApi.compile"
    ]
    if (len(public_compile_rows) != 1 or
            {row["suite"] for row in public_compile_rows} != {"public-compile"} or
            {row["system"] for row in public_compile_rows} != {"regulator-native-access"}):
        raise SystemExit("public compile rows must use the route-independent compiler suite")

    corpus_rows = read_tsv(directory.parent / "manifests/literal-corpus.tsv")
    expected_literal_rows = set()
    for corpus in corpus_rows:
        for method, count_field in (("count", "count"), ("countCaseInsensitive", "case_insensitive_count")):
            for system in ("regulator-native-access", "regulator-object-row"):
                expected_literal_rows.add((
                    f"io.airlift.regulator.BenchmarkLiteralCorpus.{method}",
                    f"language={corpus['language']}",
                    system,
                    f"count={corpus[count_field]}",
                    f"rebar={corpus['rebar_revision']};corpus={corpus['sha256']}"))
    actual_literal_rows = {
        (row["benchmark"], row["parameters"], row["system"], row["expected_result"], row["workload_checksum"])
        for row in rows
        if row["benchmark"].startswith("io.airlift.regulator.BenchmarkLiteralCorpus.")
    }
    if actual_literal_rows != expected_literal_rows:
        raise SystemExit(
            "literal corpus manifest mismatch: "
            f"missing={sorted(expected_literal_rows - actual_literal_rows)}, "
            f"unexpected={sorted(actual_literal_rows - expected_literal_rows)}")

    platform_rows = read_tsv(directory / "platforms.tsv")
    platforms = {row["platform"]: row for row in platform_rows}
    if len(platform_rows) != len(platforms) or set(platforms) != set(EXPECTED_PLATFORMS):
        raise SystemExit(f"unexpected platform identities: {sorted(platforms)}")
    for platform, expected in EXPECTED_PLATFORMS.items():
        actual = platforms[platform]
        if (actual["architecture"], actual["instance_type"], actual["vcpus"]) != expected:
            raise SystemExit(f"unexpected {platform} configuration")
        for field in ("jdk_sha256",):
            if len(actual[field]) != 64:
                raise SystemExit(f"{platform} has invalid {field}")
        for field in ("native_compiler_package", "cmake_package", "glibc_package", "cargo_package", "rust_package",
                      "time_package"):
            if not actual[field]:
                raise SystemExit(f"{platform} has no pinned {field}")

    comparator_rows = read_tsv(directory / "comparators.tsv")
    comparators = {row["component"]: row for row in comparator_rows}
    if len(comparator_rows) != len(comparators) or set(comparators) != set(EXPECTED_COMPARATORS):
        raise SystemExit(f"unexpected comparator identities: {sorted(comparators)}")
    for component, revision in EXPECTED_COMPARATORS.items():
        if comparators[component]["version_or_revision"] != revision:
            raise SystemExit(f"unexpected {component} revision")

    print(f"validated {len(rows)} unique rows across {len(shards)} shards and {len(platforms)} platforms")


if __name__ == "__main__":
    main()
