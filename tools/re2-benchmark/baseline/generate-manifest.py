#!/usr/bin/env python3

import argparse
import csv
import hashlib
import importlib.util
import json
import sys
from dataclasses import dataclass
from pathlib import Path


APPLICABILITY_SPECIFICATION = importlib.util.spec_from_file_location(
    "rebar_joni_applicability",
    Path(__file__).resolve().parent / "rebar_joni_applicability.py")
REBAR_JONI_APPLICABILITY = importlib.util.module_from_spec(APPLICABILITY_SPECIFICATION)
sys.modules["rebar_joni_applicability"] = REBAR_JONI_APPLICABILITY
APPLICABILITY_SPECIFICATION.loader.exec_module(REBAR_JONI_APPLICABILITY)
REBAR_SHARDS = tuple(f"rebar-{letter}" for letter in "abcdefghijklmnopqrst")


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
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--jmh-directory", type=Path)
    source.add_argument("--refresh-traditional", type=Path,
                        help="Replace only traditional rows in an existing manifest")
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def canonical_parameters(parameters):
    return ";".join(f"{key}={parameters[key]}" for key in sorted(parameters)) or "-"


def safe_id(value):
    return value.replace("io.airlift.regulator.", "").replace(";", "/").replace("=", "-")


def resolve_allocation_contract(contract, operation):
    if contract != "operation-dependent":
        return contract
    if operation in {
            "contains", "containsGeneral", "count", "lookingAt", "lookingAtGeneral",
            "position", "positionThird"}:
        return "allocation-free"
    if operation in {"compile", "extract", "extractAll", "replace", "replaceLambda", "split"}:
        return "recorded"
    raise ValueError(f"missing operation-specific allocation contract: {operation}")


def load_literal_corpus(root):
    source = root / "tools/re2-benchmark/manifests/literal-corpus.tsv"
    with source.open(newline="") as input_file:
        rows = list(csv.DictReader(input_file, delimiter="\t"))
    return {row["language"]: row for row in rows}


def add_jmh_rows(rows, root, jmh_directory):
    suites_file = root / "tools/re2-benchmark/baseline/jmh-suites.tsv"
    literal_corpus = load_literal_corpus(root)
    with suites_file.open(newline="") as input_file:
        for suite in csv.DictReader(input_file, delimiter="\t"):
            result_file = jmh_directory / f"{suite['shard_id']}-{suite['suite']}.json"
            results = json.loads(result_file.read_text())
            if not results:
                raise ValueError(f"declared JMH suite produced no rows: {suite['shard_id']}/{suite['suite']}")
            systems = suite["systems"].split(",")
            for result in results:
                benchmark = result["benchmark"]
                operation = benchmark.rsplit(".", 1)[1]
                allocation_contract = resolve_allocation_contract(
                    suite["allocation_contract"], operation)
                parameters = canonical_parameters(result.get("params", {}))
                logical_id = safe_id(f"{benchmark}/{parameters}")
                expected_result = "benchmark-self-check"
                workload_checksum = "candidate-source"
                if benchmark.startswith("io.airlift.regulator.BenchmarkLiteralCorpus."):
                    language = result.get("params", {}).get("language")
                    if language not in literal_corpus:
                        raise ValueError(f"unexpected literal corpus language: {language}")
                    corpus = literal_corpus[language]
                    method = benchmark.rsplit(".", 1)[1]
                    count_field = "case_insensitive_count" if method == "countCaseInsensitive" else "count"
                    if method not in {"count", "countCaseInsensitive"}:
                        raise ValueError(f"unexpected literal corpus benchmark: {benchmark}")
                    expected_result = f"count={corpus[count_field]}"
                    workload_checksum = f"rebar={corpus['rebar_revision']};corpus={corpus['sha256']}"
                for system in systems:
                    row_id = f"{suite['shard_id']}/{logical_id}/{system}"
                    rows.append(Row(
                        row_id,
                        suite["shard_id"],
                        suite["suite"],
                        system,
                        benchmark,
                        parameters,
                        suite["comparator"],
                        expected_result,
                        allocation_contract,
                        workload_checksum))
                if suite["suite"] == "trino-public-operations":
                    method = benchmark.rsplit(".", 1)[1]
                    joni_benchmark = f"io.trino.operator.scalar.BenchmarkTrinoJoniComparator.{method}Joni"
                    rows.append(Row(
                        f"{suite['shard_id']}/{logical_id}/joni",
                        suite["shard_id"],
                        suite["suite"],
                        "joni",
                        joni_benchmark,
                        parameters,
                        "joni",
                        "joni-differential",
                        allocation_contract,
                        "candidate-source"))


def load_traditional_module(root):
    source = root / "tools/re2-benchmark/traditional/summarize.py"
    specification = importlib.util.spec_from_file_location("traditional_summary", source)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def traditional_shard(pair):
    class_name = pair.java_benchmark.rsplit(".", 1)[0].rsplit(".", 1)[1]
    if class_name in {"BenchmarkRe2Search", "BenchmarkRe2SearchNfa"}:
        return "traditional-search"
    if class_name == "BenchmarkRe2SearchExtra":
        # Keep this family on full timing after bounded-protocol disagreement.
        # Separate case groups keep both memory routes within one host deadline.
        case = pair.identifier.split("/")[1]
        if case == "alternate-match" and pair.engine not in {"dfa", "re2"}:
            return "traditional-extra-alt-engines"
        if case in {"success", "success-one-byte"} and pair.engine not in {"dfa", "re2"}:
            return "traditional-extra-success-engines"
        groups = {"easy2": "easy2", "fanout": "fanout", "success": "success",
                  "success-one-byte": "success1", "alternate-match": "alt-match", "big-fixed": "big-fixed"}
        if case not in groups:
            raise ValueError(f"unassigned traditional extra case: {pair.identifier}")
        return f"traditional-extra-{groups[case]}"
    return "traditional-capture"


def add_traditional_rows(rows, root):
    module = load_traditional_module(root)
    for pair in module.build_pairs():
        shard_id = traditional_shard(pair)
        parameters = canonical_parameters(dict(pair.java_parameters))
        systems = ["regulator-native-access", "native-re2-before", "native-re2-after"]
        if pair.engine in {"dfa", "re2"}:
            systems.insert(1, "regulator-object-row")
        for system in systems:
            if system.startswith("native-re2-"):
                allocation_contract = "not-measured"
            else:
                allocation_contract = "allocation-free" if pair.family != "capture" else "recorded"
            rows.append(Row(
                f"{shard_id}/{pair.identifier}/{system}",
                shard_id,
                "traditional",
                system,
                pair.java_benchmark,
                parameters,
                pair.native_benchmark,
                "paired-native-result",
                allocation_contract,
                "traditional-pair-manifest"))


def add_rebar_rows(rows, root):
    applicability = REBAR_JONI_APPLICABILITY.load_applicability(root)
    applicability_digest = REBAR_JONI_APPLICABILITY.applicability_digest(root)
    workloads = []
    source = root / "tools/re2-benchmark/manifests/rebar-workloads.csv"
    with source.open(newline="") as input_file:
        for workload in csv.DictReader(input_file):
            workloads.append((
                workload,
                "rebar-curated",
                "",
                workload["expected_result"],
                f"pattern={workload['pattern_sha256']};haystack={workload['haystack_sha256']}",
            ))

    extended = root / "tools/re2-benchmark/manifests/rebar-extended-workloads.tsv"
    with extended.open(newline="") as input_file:
        for workload in csv.DictReader(input_file, delimiter="\t"):
            workloads.append((
                workload,
                "rebar-extended",
                "extended/",
                "native-differential",
                workload["definition_sha256"],
            ))

    identities = [(workload["name"], workload["model"]) for workload, *_ in workloads]
    ordered_identities = sorted(
        identities,
        key=lambda identity: (
            hashlib.sha256(f"{identity[0]}\0{identity[1]}".encode()).hexdigest(),
            identity))
    shard_by_identity = {
        identity: REBAR_SHARDS[index % len(REBAR_SHARDS)]
        for index, identity in enumerate(ordered_identities)
    }

    for workload, suite, row_prefix, expected_result, workload_checksum in workloads:
        identity = (workload["name"], workload["model"])
        shard_id = shard_by_identity[identity]
        parameters = f"model={workload['model']};workload={workload['name']}"
        systems = [
            "regulator-native-access",
            "regulator-object-row",
            "native-re2-before",
            "native-re2-after",
        ]
        if applicability[identity]["status"] == "compatible":
            systems.append("joni")
        for system in systems:
            system_workload_checksum = workload_checksum
            if system == "joni":
                system_workload_checksum += f";joni-applicability={applicability_digest}"
            rows.append(Row(
                f"{shard_id}/{row_prefix}{workload['name']}/{workload['model']}/{system}",
                shard_id,
                suite,
                system,
                workload["name"],
                parameters,
                "pinned-native-re2",
                expected_result,
                "not-measured",
                system_workload_checksum))


def add_trino_final_line_rows(rows):
    workloads = (
        "literalNoMatch", "literalLateMatch", "characterClassNoMatch", "characterClassLateMatch",
        "characterClassLongMatch", "captureNoMatch", "captureLateMatch", "captureLongMatch",
        "alternationNoMatch", "alternationLateMatch", "alternationLongMatch")
    operations = ("contains", "count", "position", "extract", "extractAll", "split", "replace", "replaceLambda")
    for workload in workloads:
        for source_length in (1024, 32768):
            for operation in operations:
                parameters = f"sourceLength={source_length};workload={workload}"
                for system in ("regulator-native-access", "regulator-object-row", "joni"):
                    rows.append(Row(
                        f"trino-final-line/{workload}/{source_length}/{operation}/{system}",
                        "trino-final-line",
                        "trino-final-line",
                        system,
                        operation,
                        parameters,
                        "joni",
                        "joni-differential",
                        resolve_allocation_contract("operation-dependent", operation),
                        "candidate-source"))


def add_everyday_trino_rows(rows, root):
    source = root / "src/test/resources/io/airlift/regulator/everyday-trino-workloads.tsv"
    checksum_file = root / "src/test/resources/io/airlift/regulator/everyday-trino-workloads.sha256"
    expected_checksum, checksum_name = checksum_file.read_text().strip().split()
    if checksum_name != source.name:
        raise ValueError(f"unexpected everyday workload checksum target: {checksum_name}")
    actual_checksum = hashlib.sha256(source.read_bytes()).hexdigest()
    if actual_checksum != expected_checksum:
        raise ValueError(
            f"everyday workload checksum mismatch: expected {expected_checksum}, actual {actual_checksum}")

    lines = source.read_text().splitlines()
    expected_fields = (
        "workload_id", "family", "origin", "source_id", "slice_offset",
        "expected_match_count", "pattern", "source")
    if not lines or tuple(lines[0].split("\t")) != expected_fields:
        raise ValueError("unexpected everyday workload header")
    input_rows = []
    for line_number, line in enumerate(lines[1:], start=2):
        values = line.split("\t")
        if len(values) != len(expected_fields):
            raise ValueError(
                f"everyday workload line {line_number} has {len(values)} fields, expected {len(expected_fields)}")
        input_rows.append(dict(zip(expected_fields, values)))
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
                    resolve_allocation_contract("operation-dependent", operation),
                    f"everyday={actual_checksum}"))


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
                "allocation-free",
                "candidate-source"))

    for shard, operation in (("like-compile", "compile"), ("like-single-use", "singleUse")):
        for scenario in scenarios:
            for system in ("regulator", "trino-sql"):
                rows.append(Row(
                    f"{shard}/{scenario}/{system}", shard, "trino-like", system,
                    operation, f"scenario={scenario}", "trino-like",
                    "trino-differential", "recorded", "candidate-source"))

    for scenario in ("ANY_ASCII", "ANY_MULTIBYTE"):
        for system in ("regulator", "trino-sql", "trino-optimized"):
            rows.append(Row(
                f"like-dfa-single-use/{scenario}/{system}", "like-dfa-single-use", "trino-like", system,
                "singleUse", f"scenario={scenario}", "trino-like",
                "trino-differential", "recorded", "candidate-source"))


def add_resource_rows(rows):
    for system in ("regulator-native-access", "regulator-object-row", "joni"):
        rows.append(Row(
            f"retained-memory/retained-memory/{system}",
            "retained-memory",
            "retained-memory",
            system,
            "retained-memory-census",
            "-",
            "joni-and-sizeof",
            "measurement",
            "retained-bytes",
            "candidate-source"))


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

    if arguments.refresh_traditional:
        with arguments.refresh_traditional.open(newline="") as source:
            rows.extend(Row(**row) for row in csv.DictReader(source, delimiter="\t") if row["suite"] != "traditional")
        add_traditional_rows(rows, root)
    else:
        add_jmh_rows(rows, root, arguments.jmh_directory)
        add_traditional_rows(rows, root)
        add_rebar_rows(rows, root)
        add_everyday_trino_rows(rows, root)
        add_trino_final_line_rows(rows)
        add_trino_like_rows(rows)
        add_resource_rows(rows)
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
