"""Frozen qualification protocol of every manifest row, and the row identities, comparisons, and scaling
groups that the reducer and the work-unit planner share."""
import csv
import hashlib
import io
import re
from collections import defaultdict


FIELDS = (
    "row_id",
    "execution",
    "processes",
    "warmup_iterations",
    "warmup_time",
    "measurement_iterations",
    "measurement_time",
    "maximum_relative_standard_error",
)
EXECUTIONS = ("jmh-processes", "jmh-forks", "native-repetitions")
ROUTES = (
    ("native_access_handler", "native_access_systems"),
    ("object_row_handler", "object_row_systems"),
)

# The rules below freeze what run-shard.sh selected when a host measured a whole route.
BOUNDED_PROTOCOL_ROW_THRESHOLD = 30
# A route counted every row it measured, except that trino-like counted none of its systems and kept the
# one-second protocol.
FULL_WINDOW_SHARDS = frozenset({"trino-like"})
JMH_PROCESS_COUNT = 5
JMH_WARMUP_ITERATIONS = 10
JMH_MEASUREMENT_ITERATIONS = 10
FULL_WINDOW = "1s"
BOUNDED_WINDOW = "50ms"
BOUNDED_MAXIMUM_RELATIVE_STANDARD_ERROR = "0.05"
JMH_FORK_COUNT = 1
# A JVM comparator runs in JMH forks of one launcher, as Joni does.
FORKED_SYSTEMS = frozenset({"joni", "jdk"})
# A native comparator runs in one process: one warmup as long as the JMH warmup iterations together, then
# one Google Benchmark repetition per JMH measurement iteration, ten samples as a Joni fork has.
# SHARD_DISPATCH.md describes how Google Benchmark sizes them.
NATIVE_SYSTEMS = frozenset({"native-re2", "rust-regex"})
# The systems a logical row's Regulator variants are compared with, measured in the same unit.
COMPARATOR_SYSTEMS = ("joni", "trino-optimized", "trino-sql", "native-re2", "jdk", "rust-regex")
DURATION_UNITS = {"ms": 0.001, "s": 1.0}
# LIKE rows name a logical operation; one JMH method per system measures it.
TRINO_LIKE_CLASS = "io.airlift.regulator.benchmark.BenchmarkTrinoLike"
TRINO_LIKE_SHARDS = frozenset({"trino-like", "like-compile", "like-single-use", "like-dfa-single-use"})
TRINO_LIKE_METHODS = {
    "regulator": "candidate",
    "trino-optimized": "trinoOptimized",
    "trino-sql": "trinoSql",
}
# The specialized LIKE shard measures the TPC-H workloads with its own methods.
TRINO_LIKE_SPECIALIZED_SHARD = "trino-like-specialized"
TRINO_LIKE_SPECIALIZED_CLASS = "io.airlift.regulator.BenchmarkTrinoLikeTpch"
TRINO_LIKE_SPECIALIZED_METHODS = {
    "regulator": "candidate",
    "regulator-generated": "candidateGenerated",
    "trino-sql": "trinoSql",
}
SIZE_KEY = re.compile(r"(?:size|length|count|captures?|groups?|repetitions?)$", re.IGNORECASE)


def read_tsv(path):
    with path.open(newline="", encoding="utf-8") as input_file:
        return list(csv.DictReader(input_file, delimiter="\t"))


def seconds(duration):
    for suffix in sorted(DURATION_UNITS, key=len, reverse=True):
        if duration.endswith(suffix):
            return float(duration[:-len(suffix)]) * DURATION_UNITS[suffix]
    raise ValueError(f"invalid protocol duration {duration!r}")


def jmh_benchmark(row):
    """The JMH method that measures a manifest row."""
    if row["shard_id"] in TRINO_LIKE_SHARDS:
        try:
            method = TRINO_LIKE_METHODS[row["system"]]
        except KeyError as error:
            raise ValueError(f"manifest row {row['row_id']} has an unknown Trino LIKE system") from error
        suffix = {"matches": "", "compile": "Compile", "singleUse": "SingleUse"}[row["benchmark"]]
        return f"{TRINO_LIKE_CLASS}.{method}{suffix}"
    if row["shard_id"] == TRINO_LIKE_SPECIALIZED_SHARD:
        try:
            method = TRINO_LIKE_SPECIALIZED_METHODS[row["system"]]
        except KeyError as error:
            raise ValueError(f"manifest row {row['row_id']} has an unknown specialized LIKE system") from error
        if row["benchmark"] != "matches":
            raise ValueError(f"manifest row {row['row_id']} has an unknown specialized LIKE operation")
        return f"{TRINO_LIKE_SPECIALIZED_CLASS}.{method}"
    return row["benchmark"]


def bounded(shard_id, route_rows):
    return shard_id not in FULL_WINDOW_SHARDS and len(route_rows) > BOUNDED_PROTOCOL_ROW_THRESHOLD


def protocol(row_id, execution, processes="-", warmup_iterations="-", warmup_time="-",
             measurement_iterations="-", measurement_time="-", maximum_relative_standard_error="-"):
    return dict(zip(FIELDS, (row_id, execution, str(processes), str(warmup_iterations), warmup_time,
                             str(measurement_iterations), measurement_time, maximum_relative_standard_error)))


def protocol_key(protocol):
    return tuple(protocol[field] for field in FIELDS[1:])


def row_protocol(row, window, precision):
    if row["system"] in FORKED_SYSTEMS:
        return protocol(row["row_id"], "jmh-forks", JMH_FORK_COUNT, JMH_WARMUP_ITERATIONS, window,
                        JMH_MEASUREMENT_ITERATIONS, window, precision)
    if row["system"] in NATIVE_SYSTEMS:
        return protocol(row["row_id"], "native-repetitions", 1, JMH_WARMUP_ITERATIONS, window,
                        JMH_MEASUREMENT_ITERATIONS, window, precision)
    return protocol(row["row_id"], "jmh-processes", JMH_PROCESS_COUNT, JMH_WARMUP_ITERATIONS, window,
                    JMH_MEASUREMENT_ITERATIONS, window, precision)


def generate(manifest_rows, dispatch_rows):
    protocols = {}
    for dispatch in dispatch_rows:
        shard_id = dispatch["shard_id"]
        shard_rows = [row for row in manifest_rows if row["shard_id"] == shard_id]
        for handler_field, systems_field in ROUTES:
            if dispatch[handler_field] in {"-", "blocked"}:
                continue
            systems = set(dispatch[systems_field].split(","))
            route_rows = [row for row in shard_rows if row["system"] in systems]
            is_bounded = bounded(shard_id, route_rows)
            window = BOUNDED_WINDOW if is_bounded else FULL_WINDOW
            precision = BOUNDED_MAXIMUM_RELATIVE_STANDARD_ERROR if is_bounded else "-"
            for row in route_rows:
                if row["row_id"] in protocols:
                    raise ValueError(f"row {row['row_id']} is dispatched on more than one route")
                protocols[row["row_id"]] = row_protocol(row, window, precision)
    missing = [row["row_id"] for row in manifest_rows if row["row_id"] not in protocols]
    if missing:
        raise ValueError(f"rows without a dispatched route: {missing[:10]}")
    return [protocols[row["row_id"]] for row in manifest_rows]


def generate_from(directory):
    return generate(
        read_tsv(directory / "rows.tsv"),
        read_tsv(directory / "shard-dispatch.tsv"))


def render(protocols):
    output = io.StringIO()
    writer = csv.DictWriter(output, fieldnames=FIELDS, delimiter="\t", lineterminator="\n")
    writer.writeheader()
    writer.writerows(protocols)
    return output.getvalue()


def load(path):
    with path.open(newline="", encoding="utf-8") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != FIELDS:
            raise ValueError(f"unexpected row protocol header in {path}")
        protocols = {}
        for row in reader:
            if row["execution"] not in EXECUTIONS:
                raise ValueError(f"unknown execution for {row['row_id']}: {row['execution']}")
            if row["row_id"] in protocols:
                raise ValueError(f"duplicate row protocol for {row['row_id']}")
            protocols[row["row_id"]] = row
    return protocols


def parse_parameters(value):
    if value == "-":
        return {}
    result = {}
    entries = re.split(r";(?=[A-Za-z_][A-Za-z0-9_.-]*=)", value)
    for entry in entries:
        key, separator, item = entry.partition("=")
        if not separator or not key or key in result:
            raise ValueError(f"invalid manifest parameters: {value!r}")
        result[key] = item
    return result


def canonical_parameters(parameters):
    return ";".join(f"{key}={parameters[key]}" for key in sorted(parameters)) or "-"


def logical_row_id(row):
    suffix = "/" + row["system"]
    if not row["row_id"].endswith(suffix):
        raise ValueError(f"row ID does not end with its system: {row['row_id']}")
    return row["row_id"][:-len(suffix)]


def comparison_specs(manifest_rows):
    groups = defaultdict(list)
    for row in manifest_rows:
        groups[logical_row_id(row)].append(row)
    specs = []
    for logical_id, rows in groups.items():
        by_system = {row["system"]: row for row in rows}
        candidate_systems = [system for system in by_system if system.startswith("regulator")]
        for comparator in COMPARATOR_SYSTEMS:
            if comparator in by_system:
                for system in candidate_systems:
                    specs.append((logical_id, by_system[system], comparator, (by_system[comparator],)))
    return sorted(specs, key=lambda item: (item[1]["row_id"], item[2]))


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
