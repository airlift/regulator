"""Frozen qualification protocol of every manifest row."""
import csv
import io


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
EXECUTIONS = ("jmh-processes", "jmh-forks", "native-bracket", "rebar", "memory-census")
ROUTES = (
    ("native-access", "native_access_handler", "native_access_systems", "regulator-native-access"),
    ("object-row", "object_row_handler", "object_row_systems", "regulator-object-row"),
)

# The rules below freeze what run-shard.sh selected when a host measured a whole route.
BOUNDED_PROTOCOL_ROW_THRESHOLD = 30
# The LIKE construction and specialized LIKE shards counted every row, including the SQL comparator.
ALL_ROWS_COUNTED_SHARDS = frozenset({"like-compile", "like-single-use", "like-dfa-single-use", "trino-like-specialized"})
JMH_PROCESS_COUNT = 5
JMH_WARMUP_ITERATIONS = 10
JMH_MEASUREMENT_ITERATIONS = 10
FULL_WINDOW = "1s"
BOUNDED_WINDOW = "50ms"
BOUNDED_MAXIMUM_RELATIVE_STANDARD_ERROR = "0.05"
EXTENDED_WARMUP_TIME = "300ms"
EXTENDED_WARMUP_SUITES = frozenset({"compile", "public-compile"})
EXTENDED_WARMUP_SHARD = "traditional-capture"
EXTENDED_WARMUP_BENCHMARK_PREFIXES = (
    "io.airlift.regulator.BenchmarkRe2Practical.compilePhase",
    "io.airlift.regulator.BenchmarkRe2Parse.searchPhoneRe2",
)
JONI_FORKS = 1
FULL_PROTOCOL_FORKS = 5
NATIVE_REPETITIONS = 5
NATIVE_MINIMUM_TIME = "1s"
REBAR_WARMUP_TIME = "5s"
REBAR_MAXIMUM_TIME = "5s"
DURATION_UNITS = {"ms": 0.001, "s": 1.0}


def read_tsv(path):
    with path.open(newline="", encoding="utf-8") as input_file:
        return list(csv.DictReader(input_file, delimiter="\t"))


def seconds(duration):
    for suffix in sorted(DURATION_UNITS, key=len, reverse=True):
        if duration.endswith(suffix):
            return float(duration[:-len(suffix)]) * DURATION_UNITS[suffix]
    raise ValueError(f"invalid protocol duration {duration!r}")


def manifest_benchmark(jmh_benchmark):
    prefix = "io.trino.operator.scalar.BenchmarkTrinoFinalLine."
    if jmh_benchmark.startswith(prefix) and jmh_benchmark.endswith("Regulator"):
        return jmh_benchmark[len(prefix):-len("Regulator")]
    return jmh_benchmark


def parameters_match_selection(selection, parameters):
    selected = {}
    for parameter in selection.split(";"):
        name, values = parameter.split("=", 1)
        selected[name] = set(values.split(","))
    target = dict(parameter.split("=", 1) for parameter in parameters.split(";"))
    return selected.keys() == target.keys() and all(target[name] in values for name, values in selected.items())


def is_jmh_suite(row):
    return row["suite"] != "retained-memory" and not row["suite"].startswith("rebar-")


def bounded(shard_id, handler, candidate_system, route_rows):
    if shard_id in ALL_ROWS_COUNTED_SHARDS:
        count = len(route_rows)
    elif handler == "trino-operations":
        # A trino-operations route can measure several Regulator variants, and counted them all.
        count = sum(1 for row in route_rows if is_jmh_suite(row))
    else:
        # Only the candidate and Joni were counted, so trino-like's Regulator and
        # Trino systems counted zero rows and kept the one-second protocol.
        count = sum(1 for row in route_rows if is_jmh_suite(row) and row["system"] in {candidate_system, "joni"})
    return count > BOUNDED_PROTOCOL_ROW_THRESHOLD


def protocol(row_id, execution, processes="-", warmup_iterations="-", warmup_time="-",
             measurement_iterations="-", measurement_time="-", maximum_relative_standard_error="-"):
    return dict(zip(FIELDS, (row_id, execution, str(processes), str(warmup_iterations), warmup_time,
                             str(measurement_iterations), measurement_time, maximum_relative_standard_error)))


def protocol_key(protocol):
    return tuple(protocol[field] for field in FIELDS[1:])


def row_protocol(row, handler, candidate_system, window, precision, full_protocol_selections):
    row_id = row["row_id"]
    if row["suite"] == "retained-memory":
        return protocol(row_id, "memory-census")
    if row["suite"].startswith("rebar-"):
        return protocol(row_id, "rebar", warmup_time=REBAR_WARMUP_TIME, measurement_time=REBAR_MAXIMUM_TIME)
    if row["system"].startswith("native-re2-"):
        return protocol(row_id, "native-bracket", processes=1, measurement_iterations=NATIVE_REPETITIONS,
                        measurement_time=NATIVE_MINIMUM_TIME)
    if row["system"] == "joni":
        return protocol(row_id, "jmh-forks", JONI_FORKS, JMH_WARMUP_ITERATIONS, window,
                        JMH_MEASUREMENT_ITERATIONS, window, precision)
    if row["system"] == candidate_system and any(
            row["benchmark"] == manifest_benchmark(benchmark) and
            parameters_match_selection(parameters, row["parameters"])
            for benchmark, parameters in full_protocol_selections):
        return protocol(row_id, "jmh-forks", FULL_PROTOCOL_FORKS, JMH_WARMUP_ITERATIONS, FULL_WINDOW,
                        JMH_MEASUREMENT_ITERATIONS, FULL_WINDOW, precision)
    warmup = window
    if ((handler == "jmh" and row["suite"] in EXTENDED_WARMUP_SUITES) or
            (handler == "traditional" and row["shard_id"] == EXTENDED_WARMUP_SHARD and
             row["benchmark"].startswith(EXTENDED_WARMUP_BENCHMARK_PREFIXES))):
        warmup = EXTENDED_WARMUP_TIME
    return protocol(row_id, "jmh-processes", JMH_PROCESS_COUNT, JMH_WARMUP_ITERATIONS, warmup,
                    JMH_MEASUREMENT_ITERATIONS, window, precision)


def generate(manifest_rows, dispatch_rows, full_protocol_rows):
    protocols = {}
    for dispatch in dispatch_rows:
        shard_id = dispatch["shard_id"]
        shard_rows = [row for row in manifest_rows if row["shard_id"] == shard_id]
        for route, handler_field, systems_field, candidate_system in ROUTES:
            handler = dispatch[handler_field]
            if handler in {"-", "blocked"}:
                continue
            systems = set(dispatch[systems_field].split(","))
            route_rows = [row for row in shard_rows if row["system"] in systems]
            is_bounded = bounded(shard_id, handler, candidate_system, route_rows)
            window = BOUNDED_WINDOW if is_bounded else FULL_WINDOW
            precision = BOUNDED_MAXIMUM_RELATIVE_STANDARD_ERROR if is_bounded else "-"
            full_protocol_selections = [
                (row["jmh_benchmark"], row["full_parameters"])
                for row in full_protocol_rows
                if row["shard_id"] == shard_id and row["route"] == route
            ]
            for row in route_rows:
                if row["row_id"] in protocols:
                    raise ValueError(f"row {row['row_id']} is dispatched on more than one route")
                protocols[row["row_id"]] = row_protocol(
                    row, handler, candidate_system, window, precision, full_protocol_selections)
    missing = [row["row_id"] for row in manifest_rows if row["row_id"] not in protocols]
    if missing:
        raise ValueError(f"rows without a dispatched route: {missing[:10]}")
    return [protocols[row["row_id"]] for row in manifest_rows]


def generate_from(directory):
    return generate(
        read_tsv(directory / "rows.tsv"),
        read_tsv(directory / "shard-dispatch.tsv"),
        read_tsv(directory / "full-protocol-benchmarks.tsv"))


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
