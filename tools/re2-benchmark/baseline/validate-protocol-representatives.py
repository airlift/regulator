#!/usr/bin/env python3

import argparse
import csv
from collections import Counter
from pathlib import Path


BOUNDED_PROTOCOL_ROW_THRESHOLD = 30
PRIMARY_REPLICA_IDS = {"1", "2", "3"}
ROUTES = {
    "native-access": ("native_access_handler", "native_access_systems"),
    "object-row": ("object_row_handler", "object_row_systems"),
}
CLASSPATHS = {"regulator", "pinned-trino", "special"}
REPRESENTATIVE_FIELDS = (
    "shard_id",
    "replica_id",
    "route",
    "system",
    "manifest_row_id",
    "jmh_benchmark",
    "parameters",
    "classpath",
)

FINAL_LINE_CLASS = "io.trino.operator.scalar.BenchmarkTrinoFinalLine"
TRINO_LIKE_CLASS = "io.airlift.regulator.benchmark.BenchmarkTrinoLike"
TRINO_LIKE_METHODS = {
    "regulator": "candidate",
    "trino-optimized": "trinoOptimized",
    "trino-sql": "trinoSql",
}


def parse_args():
    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--representatives", type=Path, default=directory / "protocol-representatives.tsv")
    parser.add_argument("--manifest", type=Path, default=directory / "rows.tsv")
    parser.add_argument("--dispatch", type=Path, default=directory / "shard-dispatch.tsv")
    return parser.parse_args()


def read_tsv(path, expected_fields=None):
    with path.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if reader.fieldnames is None:
            raise ValueError(f"{path} has no header")
        if expected_fields is not None and tuple(reader.fieldnames) != expected_fields:
            raise ValueError(
                f"{path} has fields {tuple(reader.fieldnames)}, expected {expected_fields}")
        rows = list(reader)
    for line_number, row in enumerate(rows, start=2):
        if None in row:
            raise ValueError(f"{path}:{line_number} has unexpected extra fields")
        empty = [field for field, value in row.items() if not value]
        if empty:
            raise ValueError(f"{path}:{line_number} has empty fields: {empty}")
    return rows


def is_jmh_row(row):
    return (
        row["suite"] != "retained-memory" and
        not row["suite"].startswith("rebar-") and
        not row["system"].startswith("native-re2-"))


def benchmark_family(row):
    if row["shard_id"] == "trino-final-line":
        return FINAL_LINE_CLASS
    if row["shard_id"] in {"trino-like", "like-compile", "like-single-use", "like-dfa-single-use"}:
        return TRINO_LIKE_CLASS
    benchmark_class, separator, _ = row["benchmark"].rpartition(".")
    if not separator:
        raise ValueError(f"manifest row {row['row_id']} has no benchmark class")
    return benchmark_class


def actual_jmh_benchmark(row):
    if row["shard_id"] == "trino-final-line":
        suffix = "Joni" if row["system"] == "joni" else "Regulator"
        return f"{FINAL_LINE_CLASS}.{row['benchmark']}{suffix}"
    if row["shard_id"] in {"trino-like", "like-compile", "like-single-use", "like-dfa-single-use"}:
        try:
            method = TRINO_LIKE_METHODS[row["system"]]
        except KeyError as error:
            raise ValueError(f"manifest row {row['row_id']} has an unknown Trino LIKE system") from error
        suffix = {"matches": "", "compile": "Compile", "singleUse": "SingleUse"}[row["benchmark"]]
        return f"{TRINO_LIKE_CLASS}.{method}{suffix}"
    return row["benchmark"]


def required_classpath(row):
    if row["shard_id"] in {"trino-final-line", "trino-like", "like-compile", "like-single-use", "like-dfa-single-use"}:
        return "special"
    if row["system"] == "joni":
        return "pinned-trino"
    return "regulator"


def load_dispatch(path):
    rows = read_tsv(path)
    by_shard = {}
    for row in rows:
        shard_id = row["shard_id"]
        if shard_id in by_shard:
            raise ValueError(f"duplicate dispatch shard {shard_id}")
        by_shard[shard_id] = row
    return by_shard


def bounded_routes(manifest_rows, dispatch_rows):
    bounded = {}
    for shard_id, dispatch in dispatch_rows.items():
        for route, (handler_field, systems_field) in ROUTES.items():
            handler = dispatch[handler_field]
            systems_text = dispatch[systems_field]
            if handler in {"-", "blocked"} or systems_text == "-":
                continue
            systems = set(systems_text.split(","))
            route_rows = [
                row for row in manifest_rows
                if row["shard_id"] == shard_id and row["system"] in systems and is_jmh_row(row)
            ]
            if len(route_rows) > BOUNDED_PROTOCOL_ROW_THRESHOLD:
                bounded[(shard_id, route)] = (systems, route_rows)
    return bounded


def validate(representatives_path, manifest_path, dispatch_path):
    manifest_rows = read_tsv(manifest_path)
    manifest_by_id = {}
    for row in manifest_rows:
        row_id = row["row_id"]
        if row_id in manifest_by_id:
            raise ValueError(f"duplicate manifest row ID {row_id}")
        manifest_by_id[row_id] = row

    dispatch_rows = load_dispatch(dispatch_path)
    required_routes = bounded_routes(manifest_rows, dispatch_rows)
    representatives = read_tsv(representatives_path, REPRESENTATIVE_FIELDS)

    counts = Counter()
    observed_route_replicas = set()
    observed_coverage = set()
    observed_bindings = set()
    for line_number, representative in enumerate(representatives, start=2):
        replica_id = representative["replica_id"]
        if replica_id not in PRIMARY_REPLICA_IDS:
            raise ValueError(f"representative line {line_number} has invalid replica {replica_id}")
        route = representative["route"]
        if route not in ROUTES:
            raise ValueError(f"representative line {line_number} has invalid route {route}")
        if representative["classpath"] not in CLASSPATHS:
            raise ValueError(
                f"representative line {line_number} has invalid classpath {representative['classpath']}")

        shard_route = (representative["shard_id"], route)
        if shard_route not in required_routes:
            raise ValueError(f"representative line {line_number} does not identify a bounded JMH route")
        route_systems, _ = required_routes[shard_route]
        if representative["system"] not in route_systems:
            raise ValueError(
                f"representative line {line_number} system {representative['system']} is not dispatched "
                f"on {representative['shard_id']} {route}")

        try:
            manifest_row = manifest_by_id[representative["manifest_row_id"]]
        except KeyError as error:
            raise ValueError(
                f"representative line {line_number} has unknown manifest row "
                f"{representative['manifest_row_id']}") from error
        for field in ("shard_id", "system", "parameters"):
            if representative[field] != manifest_row[field]:
                raise ValueError(
                    f"representative line {line_number} {field} does not match manifest row "
                    f"{manifest_row['row_id']}")
        if not is_jmh_row(manifest_row):
            raise ValueError(f"representative line {line_number} identifies an excluded non-JMH row")

        expected_benchmark = actual_jmh_benchmark(manifest_row)
        if representative["jmh_benchmark"] != expected_benchmark:
            raise ValueError(
                f"representative line {line_number} JMH benchmark does not match {expected_benchmark}")
        expected_classpath = required_classpath(manifest_row)
        if representative["classpath"] != expected_classpath:
            raise ValueError(
                f"representative line {line_number} classpath does not match {expected_classpath}")

        binding = (representative["shard_id"], replica_id, route, representative["manifest_row_id"])
        if binding in observed_bindings:
            raise ValueError(f"representative line {line_number} duplicates an exact row binding")
        observed_bindings.add(binding)
        counts[(representative["shard_id"], route, replica_id)] += 1
        observed_route_replicas.add((representative["shard_id"], route, replica_id))
        observed_coverage.add((
            representative["shard_id"],
            benchmark_family(manifest_row),
            representative["system"],
            route,
        ))

    excessive = sorted(key for key, count in counts.items() if count > 3)
    if excessive:
        raise ValueError(f"more than three representatives for route/replica: {excessive}")

    expected_route_replicas = {
        (shard_id, route, replica_id)
        for shard_id, route in required_routes
        for replica_id in PRIMARY_REPLICA_IDS
    }
    missing_route_replicas = sorted(expected_route_replicas - observed_route_replicas)
    if missing_route_replicas:
        raise ValueError(f"missing bounded route/replica representatives: {missing_route_replicas}")

    expected_coverage = {
        (shard_id, benchmark_family(row), row["system"], route)
        for (shard_id, route), (_, rows) in required_routes.items()
        for row in rows
    }
    missing_coverage = sorted(expected_coverage - observed_coverage)
    if missing_coverage:
        raise ValueError(f"missing benchmark family/system/route coverage: {missing_coverage}")

    return len(representatives), len(required_routes), len(expected_coverage)


def main():
    arguments = parse_args()
    try:
        representative_count, route_count, coverage_count = validate(
            arguments.representatives, arguments.manifest, arguments.dispatch)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    print(
        f"validated {representative_count} protocol representatives across "
        f"{route_count} bounded routes and {coverage_count} family/system/route combinations")


if __name__ == "__main__":
    main()
