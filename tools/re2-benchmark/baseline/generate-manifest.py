#!/usr/bin/env python3

import argparse
import csv
import hashlib
from dataclasses import dataclass
from pathlib import Path

import row_protocol


@dataclass(frozen=True, order=True)
class Row:
    row_id: str
    shard_id: str
    suite: str
    system: str
    benchmark: str
    parameters: str
    comparator: str
    expected_result: str
    allocation_contract: str
    workload_checksum: str


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def allocation_contract(system, operation):
    """Return the allocation contract of a row measuring the system's operation.

    Rows of Regulator's contains, count, and LIKE matching are held allocation-free, so
    allocation there is investigated. These operations allocate nothing once warm on a pattern
    that runs on a scan plan or a specialized matcher, including a contains that hands off to the
    ordinary engine. A count whose search hands off allocates one small array per call, a count
    without a specialized path creates a matcher, and other patterns may allocate per call, such
    as ordinary-engine search state for a word boundary, though a warm DFA search does not.
    Regulator's position and extraction create a matcher per call on a pattern without a
    specialized path, as release 1.0 did, and a JVM comparator promises nothing, so those rows
    record allocation. A native runner records none.
    """
    if system in row_protocol.NATIVE_SYSTEMS:
        return "not-measured"
    if operation in {"contains", "count", "matches"}:
        return "recorded" if system in row_protocol.COMPARATOR_SYSTEMS else "allocation-free"
    if operation in {"positionThird", "extract", "extractAll", "replace", "replaceLambda", "split", "compile", "singleUse"}:
        return "recorded"
    raise ValueError(f"missing operation-specific allocation contract: {operation}")


def read_trino_workloads(root, name):
    """Return the verified checksum and rows of the named checksummed Trino workload resource."""
    source = root / "src/test/resources/io/airlift/regulator" / f"{name}-trino-workloads.tsv"
    expected_checksum, checksum_name = source.with_suffix(".sha256").read_text().strip().split()
    if checksum_name != source.name:
        raise ValueError(f"unexpected {name} workload checksum target: {checksum_name}")
    actual_checksum = hashlib.sha256(source.read_bytes()).hexdigest()
    if actual_checksum != expected_checksum:
        raise ValueError(
            f"{name} workload checksum mismatch: expected {expected_checksum}, actual {actual_checksum}")

    lines = source.read_text().splitlines()
    expected_fields = (
        "workload_id", "family", "origin", "source_id", "slice_offset",
        "expected_match_count", "pattern", "source")
    if not lines or tuple(lines[0].split("\t")) != expected_fields:
        raise ValueError(f"unexpected {name} workload header")
    input_rows = []
    for line_number, line in enumerate(lines[1:], start=2):
        values = line.split("\t")
        if len(values) != len(expected_fields):
            raise ValueError(
                f"{name} workload line {line_number} has {len(values)} fields, expected {len(expected_fields)}")
        input_rows.append(dict(zip(expected_fields, values)))
    return actual_checksum, input_rows


def add_everyday_trino_rows(rows, root):
    actual_checksum, input_rows = read_trino_workloads(root, "everyday")
    workload_ids = list(dict.fromkeys(row["workload_id"] for row in input_rows))
    if not workload_ids:
        raise ValueError("everyday workload manifest is empty")
    for workload_id in workload_ids:
        source_count = sum(row["workload_id"] == workload_id for row in input_rows)
        if source_count != 8:
            raise ValueError(f"everyday workload {workload_id} has {source_count} sources, expected 8")

    operations = ("contains", "count", "positionThird", "extract", "extractAll", "split", "replace", "replaceLambda")
    for workload_id in workload_ids:
        parameters = f"workload={workload_id}"
        for operation in operations:
            logical_id = f"everyday/{workload_id}/{operation}"
            for system, benchmark in (
                    ("regulator-native-access", f"io.airlift.regulator.BenchmarkEverydayTrinoRegexp.{operation}"),
                    ("regulator-object-row", f"io.airlift.regulator.BenchmarkEverydayTrinoRegexp.{operation}"),
                    ("joni", f"io.trino.operator.scalar.BenchmarkEverydayTrinoJoni.{operation}")):
                rows.append(Row(
                    f"trino-operations/{logical_id}/{system}",
                    "trino-operations",
                    "trino-everyday-operations",
                    system,
                    benchmark,
                    parameters,
                    "joni",
                    "joni-differential",
                    allocation_contract(system, operation),
                    f"everyday={actual_checksum}"))


# Each specialized regex suite: its Regulator benchmark class, its comparator, and the comparator's
# benchmark for an operation. A native comparator's benchmark is the runner operation itself.
SPECIALIZED_REGEX_SUITES = {
    "trino-specialized-patterns": (
        "io.airlift.regulator.BenchmarkSpecializedTrinoRegexp", "joni",
        "io.trino.operator.scalar.BenchmarkSpecializedTrinoJoni.{operation}"),
    "re2-specialized-patterns": ("io.airlift.regulator.BenchmarkSpecializedRe2", "native-re2", "{operation}"),
    "java-specialized-patterns": (
        "io.airlift.regulator.BenchmarkSpecializedJavaRegexp", "jdk",
        "io.airlift.regulator.BenchmarkSpecializedJavaRegexp.{operation}Jdk"),
    "rust-specialized-patterns": ("io.airlift.regulator.BenchmarkSpecializedRustRegexp", "rust-regex", "{operation}"),
}


def read_specialized_catalog(root):
    lines = (root / "tools/re2-benchmark/baseline/specialized-patterns.tsv").read_text().splitlines()
    fields = ("shard_id", "workload", "operation", "pattern", "name", "description", "comparator", "case_prefix")
    if not lines or tuple(lines[0].split("\t")) != fields:
        raise ValueError("unexpected specialized-pattern catalog header")
    catalog = []
    for line_number, line in enumerate(lines[1:], start=2):
        values = line.split("\t")
        if len(values) != len(fields):
            raise ValueError(f"specialized-pattern catalog line {line_number} has {len(values)} fields, expected {len(fields)}")
        catalog.append(dict(zip(fields, values)))
    return catalog


def add_specialized_rows(rows, root):
    """Add the specialized-pattern rows.

    A regex workload runs on the scan-plan interpreter, the regular engine and generated bytecode
    beside its language's comparator: Joni for Trino, native RE2 for RE2, the JDK for Java, and the regex crate for Rust. A LIKE workload runs
    on the interpreter and generated bytecode beside Trino SQL.
    """
    catalog = read_specialized_catalog(root)
    sources = {}
    for name in ("specialized", "everyday"):
        checksum, input_rows = read_trino_workloads(root, name)
        for input_row in input_rows:
            sources.setdefault(input_row["workload_id"], (f"{name}={checksum}", input_row["pattern"]))
    with (root / "tools/re2-benchmark/manifests/tpch-like-workloads.tsv").open(newline="") as source:
        like_patterns = {row["id"]: row["pattern"] for row in csv.DictReader(source, delimiter="\t", quoting=csv.QUOTE_NONE)}

    variants = (("regulator", ""), ("regulator-regular-engine", "RegularEngine"), ("regulator-generated", "Generated"))
    for entry in catalog:
        workload, operation = entry["workload"], entry["operation"]
        shard = entry["shard_id"]
        suite = shard.rsplit("-", 1)[0]
        if suite in SPECIALIZED_REGEX_SUITES and shard in {f"{suite}-a", f"{suite}-b"}:
            benchmark_class, comparator, comparator_benchmark = SPECIALIZED_REGEX_SUITES[suite]
            if operation not in {"replace", "extract", "extractAll"}:
                raise ValueError(f"{suite} workload {workload} has unsupported operation {operation}")
            if entry["comparator"] != comparator:
                raise ValueError(
                    f"{suite} workload {workload} names comparator {entry['comparator']}, not {comparator}")
            if workload not in sources:
                raise ValueError(f"{suite} workload {workload} has no inputs")
            checksum, pattern = sources[workload]
            if pattern != entry["pattern"]:
                raise ValueError(
                    f"{suite} workload {workload} pattern {entry['pattern']!r} differs from its inputs' {pattern!r}")
            benchmarks = [
                (system + route, f"{benchmark_class}.{operation}{suffix}")
                for system, suffix in variants
                for route in ("-native-access", "-object-row")]
            benchmarks.append((comparator, comparator_benchmark.format(operation=operation)))
            for system, benchmark in benchmarks:
                rows.append(Row(
                    f"{shard}/{workload}/{operation}/{system}",
                    shard,
                    suite,
                    system,
                    benchmark,
                    f"workload={workload}",
                    comparator,
                    f"{comparator}-differential",
                    allocation_contract(system, operation),
                    checksum))
        elif shard == "trino-like-specialized":
            if operation != "matches":
                raise ValueError(f"specialized LIKE workload {workload} has unsupported operation {operation}")
            if like_patterns.get(workload.lower().replace("_", "-")) != entry["pattern"]:
                raise ValueError(f"specialized LIKE workload {workload} pattern differs from the TPC-H manifest")
            for system in ("regulator", "regulator-generated", "trino-sql"):
                rows.append(Row(
                    f"trino-like-specialized/{workload}/{system}", "trino-like-specialized", "trino-like-specialized",
                    system, operation, f"workload={workload}", "trino-like", "trino-differential",
                    allocation_contract(system, operation), "candidate-source"))
        else:
            raise ValueError(f"unknown specialized-pattern shard: {shard}")


def add_trino_like_rows(rows):
    scenarios = (
        "EXACT_MATCH", "PREFIX_LARGE", "SUFFIX_LARGE", "CONTAINS_ABSENT", "CONTAINS_LATE",
        "ORDERED_LATE", "ORDERED_DENSE_FALSE", "ANY_ASCII", "ANY_MULTIBYTE", "MIXED_LATE",
        "MIXED_ABSENT", "WILDCARD_CHAIN")
    for scenario in scenarios:
        for system in ("regulator", "trino-sql", "trino-optimized"):
            rows.append(Row(
                f"trino-like/{scenario}/{system}",
                "trino-like",
                "trino-like",
                system,
                "matches",
                f"scenario={scenario}",
                "trino-like",
                "trino-differential",
                allocation_contract(system, "matches"),
                "candidate-source"))

    for shard, operation in (("like-compile", "compile"), ("like-single-use", "singleUse")):
        for scenario in scenarios:
            for system in ("regulator", "trino-sql"):
                rows.append(Row(
                    f"{shard}/{scenario}/{system}", shard, "trino-like", system,
                    operation, f"scenario={scenario}", "trino-like",
                    "trino-differential", allocation_contract(system, operation), "candidate-source"))

    for scenario in ("ANY_ASCII", "ANY_MULTIBYTE"):
        for system in ("regulator", "trino-sql", "trino-optimized"):
            rows.append(Row(
                f"like-dfa-single-use/{scenario}/{system}", "like-dfa-single-use", "trino-like", system,
                "singleUse", f"scenario={scenario}", "trino-like",
                "trino-differential", allocation_contract(system, "singleUse"), "candidate-source"))


def validate(rows, shards):
    row_ids = [row.row_id for row in rows]
    if len(row_ids) != len(set(row_ids)):
        duplicates = sorted(identifier for identifier in set(row_ids) if row_ids.count(identifier) > 1)
        raise ValueError(f"duplicate row IDs: {duplicates[:10]}")
    unknown_shards = sorted({row.shard_id for row in rows} - shards)
    if unknown_shards:
        raise ValueError(f"unknown shards: {unknown_shards}")
    empty_shards = sorted(shards - {row.shard_id for row in rows})
    if empty_shards:
        raise ValueError(f"empty shards: {empty_shards}")


def main():
    arguments = parse_args()
    root = Path(__file__).resolve().parents[3]
    rows = []
    with (root / "tools/re2-benchmark/baseline/shards.tsv").open(newline="") as input_file:
        shards = {row["shard_id"] for row in csv.DictReader(input_file, delimiter="\t")}

    add_everyday_trino_rows(rows, root)
    add_trino_like_rows(rows)
    add_specialized_rows(rows, root)
    validate(rows, shards)

    rows.sort()
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    with arguments.output.open("w", newline="") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        writer.writerow(Row.__dataclass_fields__)
        for row in rows:
            writer.writerow((row.row_id, row.shard_id, row.suite, row.system, row.benchmark,
                             row.parameters, row.comparator, row.expected_result,
                             row.allocation_contract, row.workload_checksum))

    digest = hashlib.sha256(arguments.output.read_bytes()).hexdigest()
    arguments.output.with_suffix(".sha256").write_text(f"{digest}  {arguments.output.name}\n")
    print(f"wrote {len(rows)} rows across {len(shards)} shards")


if __name__ == "__main__":
    main()
