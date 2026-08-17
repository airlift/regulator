#!/usr/bin/env python3

import argparse
import csv
import hashlib
import json
import math
import re
import statistics
from pathlib import Path


MANIFEST_FIELDS = (
    "row_id",
    "shard_id",
    "suite",
    "system",
    "benchmark",
    "parameters",
    "comparator",
    "expected_result",
    "allocation_contract",
    "workload_checksum",
)
OBSERVED_FIELDS = (
    "row_id",
    "shard_id",
    "system",
    "score",
    "score_unit",
    "allocation_bytes",
    "result_checksum",
    "outcome",
)
REBAR_OUTCOME_FIELDS = (
    "row_id", "suite", "system", "benchmark", "model", "outcome", "detail",
)
SEMANTIC_FIELDS = ("route", "tests", "outcome", "semantic_result_digest")
DURATION_PATTERN = re.compile(r"^([0-9]+(?:\.[0-9]+)?)(s|ms|us|ns)$")
DURATION_TO_NS = {"s": 1_000_000_000, "ms": 1_000_000, "us": 1_000, "ns": 1}


def canonical_parameters(parameters):
    return ";".join(f"{key}={parameters[key]}" for key in sorted(parameters)) or "-"


def load_manifest(path):
    with path.open(newline="", encoding="utf-8") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != MANIFEST_FIELDS:
            raise ValueError(f"Unexpected manifest header in {path}")
        rows = list(reader)
    identifiers = [row["row_id"] for row in rows]
    if len(identifiers) != len(set(identifiers)):
        raise ValueError(f"Manifest contains duplicate row IDs: {path}")
    return rows


def load_observed(path):
    with path.open(newline="", encoding="utf-8") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != OBSERVED_FIELDS:
            raise ValueError(f"Unexpected observed-row header in {path}")
        return list(reader)


def load_rebar_outcomes(path):
    if path is None:
        return {}
    with path.open(newline="", encoding="utf-8") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != REBAR_OUTCOME_FIELDS:
            raise ValueError(f"Unexpected Rebar outcome header in {path}")
        rows = list(reader)
    outcomes = {}
    for row in rows:
        row_id = row["row_id"]
        if row_id in outcomes:
            raise ValueError(f"Duplicate Rebar outcome for {row_id!r}")
        if row["outcome"] not in {"semantic-mismatch", "did-not-finish"} or not row["detail"]:
            raise ValueError(f"Invalid Rebar outcome for {row_id!r}")
        outcomes[row_id] = row
    return outcomes


def write_tsv(path, fields, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def selected_rows(manifest, shard, systems):
    systems = set(systems.split(","))
    rows = [row for row in manifest if row["shard_id"] == shard and row["system"] in systems]
    if not rows:
        raise ValueError(f"No manifest rows for shard {shard!r} and systems {sorted(systems)}")
    return rows


def metric_statistics(metric):
    raw_data = metric.get("rawData") or []
    samples = [float(sample) for fork in raw_data for sample in fork]
    if any(not math.isfinite(sample) for sample in samples):
        raise ValueError("JMH raw measurements must be finite")
    if not samples:
        score = float(metric["score"])
        samples = [score]
    mean = statistics.fmean(samples)
    coefficient_of_variation = (
        statistics.stdev(samples) / mean
        if len(samples) > 1 and mean != 0
        else 0.0
    )
    return statistics.median(samples), len(samples), coefficient_of_variation


def format_number(value):
    if value is None:
        return "-"
    value = float(value)
    if not math.isfinite(value):
        return "-"
    return f"{value:.17g}"


def exceeds_published_limit(value, limit):
    return float(f"{value:.12g}") > limit


def allocation(result, contract):
    metric = result.get("secondaryMetrics", {}).get("gc.alloc.rate.norm")
    if metric is None:
        if contract in {"recorded", "allocation-free"}:
            raise ValueError(
                f"JMH result is missing gc.alloc.rate.norm for allocation contract {contract!r}")
        return "not-measured"
    if metric.get("scoreUnit") != "B/op":
        raise ValueError(f"Unexpected allocation unit: {metric.get('scoreUnit')}")
    score, _, _ = metric_statistics(metric)
    return format_number(score)


def semantic_receipt(path):
    with path.open(newline="", encoding="utf-8") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != SEMANTIC_FIELDS:
            raise ValueError(f"Unexpected semantic receipt header in {path}")
        rows = list(reader)
    if len(rows) != 1 or rows[0]["outcome"] != "accepted":
        raise ValueError(f"Semantic checks were not accepted: {path}")
    digest = rows[0]["semantic_result_digest"]
    if re.fullmatch(r"[0-9a-f]{64}", digest) is None:
        raise ValueError(f"Semantic receipt has an invalid result digest: {path}")
    return digest


def result_checksum(expected):
    # Route receipts preserve execution evidence, including sibling timeouts.
    # Only the row's declared contract belongs in cross-host identity.
    values = [expected[field] for field in MANIFEST_FIELDS]
    return hashlib.sha256(json.dumps(values, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def jmh_results(
        paths,
        require_one_process_group_per_file=True,
        merge_secondary_metrics=True,
        allow_missing_secondary_metrics=False,
        allow_disjoint_process_sets=False,
        expected_process_groups=None):
    results = {}
    process_count = len(paths)
    if process_count == 0:
        raise ValueError("No JMH result files were provided")
    for path in paths:
        document = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(document, list) or not document:
            raise ValueError(f"JMH result is empty or malformed: {path}")
        process_results = {}
        for result in document:
            key = (result["benchmark"], canonical_parameters(result.get("params", {})))
            if key in process_results:
                raise ValueError(f"Duplicate JMH result {key} in {path}")
            process_results[key] = result
        if results and not allow_disjoint_process_sets and set(process_results) != set(results):
            raise ValueError(
                f"JMH process rows differ; missing={sorted(set(results) - set(process_results))[:20]}, "
                f"unexpected={sorted(set(process_results) - set(results))[:20]} in {path}")
        for key, result in process_results.items():
            primary_raw_data = result["primaryMetric"].get("rawData") or [[result["primaryMetric"]["score"]]]
            if require_one_process_group_per_file and len(primary_raw_data) != 1:
                raise ValueError(
                    f"JMH process result {key} in {path} has {len(primary_raw_data)} process sample groups, "
                    "expected one")
            result["primaryMetric"]["rawData"] = primary_raw_data
            if merge_secondary_metrics:
                for metric_name, metric in result.get("secondaryMetrics", {}).items():
                    raw_data = metric.get("rawData") or [[metric["score"]]]
                    if require_one_process_group_per_file and len(raw_data) != 1:
                        raise ValueError(
                            f"JMH process secondary metric {metric_name!r} for {key} in {path} has "
                            f"{len(raw_data)} process sample groups, expected one")
                    metric["rawData"] = raw_data
            else:
                result["secondaryMetrics"] = {}
            if key not in results:
                results[key] = result
                continue
            combined = results[key]
            combined["primaryMetric"]["rawData"].extend(primary_raw_data)
            combined_secondary = combined.get("secondaryMetrics", {})
            result_secondary = result.get("secondaryMetrics", {})
            if (not allow_missing_secondary_metrics and
                    set(combined_secondary) != set(result_secondary)):
                raise ValueError(f"JMH process secondary metrics differ for {key} in {path}")
            for metric_name, metric in result_secondary.items():
                if metric_name in combined_secondary:
                    combined_secondary[metric_name]["rawData"].extend(metric["rawData"])
                else:
                    combined_secondary[metric_name] = metric
    if expected_process_groups is not None:
        for key, result in results.items():
            raw_data = result["primaryMetric"]["rawData"]
            if len(raw_data) != expected_process_groups:
                raise ValueError(
                    f"JMH result {key} has {len(raw_data)} process sample groups, "
                    f"expected {expected_process_groups}")
    elif require_one_process_group_per_file:
        for key, result in results.items():
            raw_data = result["primaryMetric"]["rawData"]
            if len(raw_data) != process_count:
                raise ValueError(
                    f"JMH result {key} has {len(raw_data)} process sample groups, expected {process_count}")
    return results


def observed_jmh_row(expected, result, maximum_relative_standard_error=None):
    metric = result["primaryMetric"]
    score, sample_count, coefficient_of_variation = metric_statistics(metric)
    relative_standard_error = coefficient_of_variation / math.sqrt(sample_count)
    outcome = (
        "precision-rejected"
        if maximum_relative_standard_error is not None and
        relative_standard_error > maximum_relative_standard_error
        else "accepted"
    )
    return {
        "row_id": expected["row_id"],
        "shard_id": expected["shard_id"],
        "system": expected["system"],
        "score": format_number(score),
        "score_unit": metric["scoreUnit"],
        "allocation_bytes": allocation(result, expected["allocation_contract"]),
        "result_checksum": result_checksum(expected),
        "outcome": outcome,
    }


def normalize_jmh(arguments):
    allow_mixed_process_sets = getattr(arguments, "allow_mixed_process_sets", False)
    allow_multiple_process_groups = getattr(arguments, "allow_multiple_process_groups", False)
    expected_process_groups = getattr(arguments, "expected_process_groups", None)
    if allow_mixed_process_sets:
        if expected_process_groups is None or expected_process_groups <= 0:
            raise ValueError("Mixed JMH process sets require a positive expected process-group count")
    elif allow_multiple_process_groups:
        if expected_process_groups is None or expected_process_groups <= 0:
            raise ValueError("Multiple JMH process groups require a positive expected process-group count")
    elif expected_process_groups is not None:
        raise ValueError("Expected process groups require mixed or multiple JMH process groups")
    expected = selected_rows(
        load_manifest(arguments.manifest), arguments.shard, arguments.system)
    if arguments.suite:
        expected = [row for row in expected if row["suite"] == arguments.suite]
    include_benchmark_prefix = getattr(arguments, "include_benchmark_prefix", ())
    if include_benchmark_prefix:
        expected = [
            row for row in expected
            if any(row["benchmark"].startswith(prefix) for prefix in include_benchmark_prefix)
        ]
    exclude_benchmark_prefix = getattr(arguments, "exclude_benchmark_prefix", ())
    if exclude_benchmark_prefix:
        expected = [
            row for row in expected
            if not any(row["benchmark"].startswith(prefix) for prefix in exclude_benchmark_prefix)
        ]
    if not expected:
        raise ValueError(
            f"No expected JMH rows for shard {arguments.shard!r}, suite {arguments.suite!r}, "
            f"and system {arguments.system!r}")
    expected_by_key = {(row["benchmark"], row["parameters"]): row for row in expected}
    if len(expected_by_key) != len(expected):
        raise ValueError("Expected JMH rows are not unique by benchmark and parameters")

    actual = jmh_results(
        arguments.input,
        require_one_process_group_per_file=not (allow_mixed_process_sets or allow_multiple_process_groups),
        allow_missing_secondary_metrics=getattr(arguments, "allow_missing_secondary_metrics", False),
        allow_disjoint_process_sets=allow_mixed_process_sets,
        expected_process_groups=(
            expected_process_groups
            if allow_mixed_process_sets or allow_multiple_process_groups
            else None))
    unexpected = set(actual) - set(expected_by_key)
    if arguments.allow_traditional_unpaired:
        unexpected -= {
            ("io.airlift.regulator.BenchmarkRe2SearchExtra.searchSuccess1BitState", "textSize=16777216"),
            ("io.airlift.regulator.BenchmarkRe2SearchExtra.searchSuccessOnePass", "textSize=16777216"),
        }
    missing = set(expected_by_key) - set(actual)
    if missing or unexpected:
        raise ValueError(
            f"JMH rows differ from the manifest; missing={sorted(missing)[:20]}, "
            f"unexpected={sorted(unexpected)[:20]}")

    semantic_receipt(arguments.semantic_receipt)
    maximum_relative_standard_error = getattr(arguments, "maximum_relative_standard_error", None)
    rows = [
        observed_jmh_row(
            expected_by_key[key], actual[key],
            maximum_relative_standard_error)
        for key in sorted(expected_by_key)
    ]
    write_tsv(arguments.output, OBSERVED_FIELDS, rows)


def normalize_mapped_jmh(arguments):
    expected = selected_rows(
        load_manifest(arguments.manifest), arguments.shard, arguments.system)
    if arguments.suite:
        expected = [row for row in expected if row["suite"] == arguments.suite]
    if not expected:
        raise ValueError(
            f"No expected mapped JMH rows for shard {arguments.shard!r}, "
            f"suite {arguments.suite!r}, and system {arguments.system!r}")
    expected_by_key = {(row["benchmark"], row["parameters"]): row for row in expected}
    if len(expected_by_key) != len(expected):
        raise ValueError("Expected mapped JMH rows are not unique by benchmark and parameters")

    method_map = {}
    for mapping in arguments.method_map:
        actual, separator, logical = mapping.partition("=")
        if not separator or not actual or not logical:
            raise ValueError(f"Invalid method mapping {mapping!r}; expected actual=logical")
        if actual in method_map:
            raise ValueError(f"Duplicate actual method mapping {actual!r}")
        method_map[actual] = logical

    actual = {}
    allow_mixed_process_sets = getattr(arguments, "allow_mixed_process_sets", False)
    allow_multiple_process_groups = getattr(arguments, "allow_multiple_process_groups", False)
    expected_process_groups = getattr(arguments, "expected_process_groups", None)
    if allow_mixed_process_sets:
        if expected_process_groups is None or expected_process_groups <= 0:
            raise ValueError("Mixed JMH process sets require a positive expected process-group count")
    elif allow_multiple_process_groups:
        if expected_process_groups is None or expected_process_groups <= 0:
            raise ValueError("Multiple JMH process groups require a positive expected process-group count")
    elif expected_process_groups is not None:
        raise ValueError("Expected process groups require mixed or multiple JMH process groups")
    for (benchmark, parameters), result in jmh_results(
            arguments.input,
            require_one_process_group_per_file=not (
                allow_mixed_process_sets or allow_multiple_process_groups),
            allow_missing_secondary_metrics=getattr(
                arguments, "allow_missing_secondary_metrics", False),
            allow_disjoint_process_sets=allow_mixed_process_sets,
            expected_process_groups=(
                expected_process_groups
                if allow_mixed_process_sets or allow_multiple_process_groups
                else None)).items():
        benchmark_class, separator, method = benchmark.rpartition(".")
        if not separator or benchmark_class != arguments.benchmark_class:
            raise ValueError(f"Unexpected mapped JMH benchmark {benchmark!r}")
        if method not in method_map:
            raise ValueError(f"No logical mapping for JMH method {method!r}")
        key = (method_map[method], parameters)
        if key in actual:
            raise ValueError(f"Duplicate mapped JMH result {key}")
        actual[key] = result

    missing = set(expected_by_key) - set(actual)
    unexpected = set(actual) - set(expected_by_key)
    if missing or unexpected:
        raise ValueError(
            f"Mapped JMH rows differ from the manifest; missing={sorted(missing)[:20]}, "
            f"unexpected={sorted(unexpected)[:20]}")

    semantic_receipt(arguments.semantic_receipt)
    maximum_relative_standard_error = getattr(arguments, "maximum_relative_standard_error", None)
    rows = [
        observed_jmh_row(
            expected_by_key[key], actual[key],
            maximum_relative_standard_error)
        for key in sorted(expected_by_key)
    ]
    write_tsv(arguments.output, OBSERVED_FIELDS, rows)


def native_medians(path):
    document = json.loads(path.read_text(encoding="utf-8"))
    benchmarks = document.get("benchmarks")
    if not isinstance(benchmarks, list) or not benchmarks:
        raise ValueError(f"Native result is empty or malformed: {path}")

    medians = {}
    iterations = {}
    for row in benchmarks:
        name = row.get("run_name", row.get("name"))
        if row.get("run_type") == "aggregate" and row.get("aggregate_name") == "median":
            medians[name] = row
        elif row.get("run_type") == "iteration":
            iterations.setdefault(name, []).append(row)

    results = {}
    for name in set(medians) | set(iterations):
        if name in medians:
            row = medians[name]
            sample_count = int(row.get("repetitions", len(iterations.get(name, ()))))
        else:
            samples = iterations[name]
            row = dict(samples[0])
            row["real_time"] = statistics.median(float(sample["real_time"]) for sample in samples)
            sample_count = len(samples)
        if row["time_unit"] != "ns":
            raise ValueError(f"Unexpected native unit for {name}: {row['time_unit']}")
        results[name] = (float(row["real_time"]), sample_count)
    return results


def normalize_native(arguments):
    expected = selected_rows(load_manifest(arguments.manifest), arguments.shard, arguments.system)
    expected_by_name = {row["comparator"]: row for row in expected}
    if len(expected_by_name) != len(expected):
        raise ValueError("Expected native rows are not unique by comparator benchmark")
    actual = native_medians(arguments.input)
    missing = set(expected_by_name) - set(actual)
    unexpected = set(actual) - set(expected_by_name)
    if missing or unexpected:
        raise ValueError(
            f"Native rows differ from the manifest; missing={sorted(missing)[:20]}, "
            f"unexpected={sorted(unexpected)[:20]}")

    semantic_receipt(arguments.semantic_receipt)
    rows = []
    for name in sorted(expected_by_name):
        score, _ = actual[name]
        rows.append({
            "row_id": expected_by_name[name]["row_id"],
            "shard_id": expected_by_name[name]["shard_id"],
            "system": expected_by_name[name]["system"],
            "score": format_number(score),
            "score_unit": "ns/op",
            "allocation_bytes": "not-measured",
            "result_checksum": result_checksum(expected_by_name[name]),
            "outcome": "accepted",
        })
    write_tsv(arguments.output, OBSERVED_FIELDS, rows)


def normalize_memory_census(arguments):
    expected = selected_rows(load_manifest(arguments.manifest), arguments.shard, arguments.system)
    expected = [row for row in expected if row["suite"] == "retained-memory"]
    if len(expected) != 1:
        raise ValueError(
            f"Expected one retained-memory row for {arguments.shard!r}/{arguments.system!r}, "
            f"found {len(expected)}")

    with arguments.input.open(newline="", encoding="utf-8") as input_file:
        rows = list(csv.DictReader(input_file))
    required_fields = {
        "engine", "workload", "source_length", "lifecycle", "operation", "root_count",
        "total_heap_bytes", "average_heap_bytes", "input_heap_bytes", "off_heap_bytes",
    }
    if not rows or not required_fields.issubset(rows[0]):
        raise ValueError(f"Memory census is empty or malformed: {arguments.input}")

    key_fields = ("workload", "source_length", "lifecycle", "operation", "root_count")
    by_key = {}
    for row in rows:
        key = tuple(row[field] for field in key_fields)
        engine_rows = by_key.setdefault(key, {})
        if row["engine"] in engine_rows:
            raise ValueError(f"Duplicate memory census row for {row['engine']!r}/{key}")
        engine_rows[row["engine"]] = row
    for key, engine_rows in by_key.items():
        if set(engine_rows) != {"RE2", "Joni"}:
            raise ValueError(f"Incomplete memory census pair for {key}: {sorted(engine_rows)}")

    engine = arguments.engine
    retained_bytes = 0.0
    for engine_rows in by_key.values():
        row = engine_rows[engine]
        if row["lifecycle"] == "compiled":
            heap_bytes = float(row["average_heap_bytes"])
        else:
            heap_bytes = int(row["total_heap_bytes"]) - int(row["input_heap_bytes"])
        retained_bytes += heap_bytes + int(row["off_heap_bytes"])
    if not math.isfinite(retained_bytes) or retained_bytes < 0:
        raise ValueError(f"Invalid aggregate retained memory for {engine}: {retained_bytes}")

    semantic_receipt(arguments.semantic_receipt)
    row = expected[0]
    write_tsv(arguments.output, OBSERVED_FIELDS, [{
        "row_id": row["row_id"],
        "shard_id": row["shard_id"],
        "system": row["system"],
        "score": format_number(retained_bytes),
        "score_unit": "bytes",
        "allocation_bytes": "not-measured",
        "result_checksum": result_checksum(row),
        "outcome": "accepted",
    }])


def parse_duration_ns(value):
    match = DURATION_PATTERN.fullmatch(value.strip())
    if match is None:
        raise ValueError(f"Invalid Rebar duration {value!r}")
    number, unit = match.groups()
    duration = float(number) * DURATION_TO_NS[unit]
    if not math.isfinite(duration) or duration <= 0:
        raise ValueError(f"Invalid Rebar duration {value!r}")
    return duration


def normalize_rebar(arguments):
    expected = selected_rows(load_manifest(arguments.manifest), arguments.shard, arguments.system)
    expected_by_id = {row["row_id"]: row for row in expected}
    outcomes = {
        row_id: outcome
        for row_id, outcome in load_rebar_outcomes(getattr(arguments, "rebar_outcomes", None)).items()
        if outcome["system"] == arguments.system
    }
    for row_id, outcome in outcomes.items():
        expected_row = expected_by_id.get(row_id)
        if expected_row is None:
            raise ValueError(f"Rebar outcome is not expected for {arguments.system}: {row_id!r}")
        parameters = dict(item.split("=", 1) for item in expected_row["parameters"].split(";"))
        if any(outcome[field] != expected_row[field] for field in ("suite", "system", "benchmark")):
            raise ValueError(f"Rebar outcome relabels manifest row {row_id!r}")
        if outcome["model"] != parameters["model"]:
            raise ValueError(f"Rebar outcome changes the model for {row_id!r}")
    expected_by_key = {}
    for row in expected:
        parameters = dict(item.split("=", 1) for item in row["parameters"].split(";"))
        key = (row["benchmark"], parameters["model"])
        if key in expected_by_key:
            raise ValueError(f"Duplicate expected Rebar row {key}")
        expected_by_key[key] = row

    engine_systems = {
        "joni/trino": "joni",
        "regulator/re2": "regulator-native-access",
        "regulator/re2-object": "regulator-object-row",
        "re2/pinned-host-tuned-before": "native-re2-before",
        "re2/pinned-host-tuned-after": "native-re2-after",
    }
    actual = {}
    with arguments.input.open(newline="", encoding="utf-8") as input_file:
        reader = csv.DictReader(input_file)
        required = {"name", "model", "engine", "engine_version", "err", "median"}
        # No process ran when every selected row was already classified. A truly
        # empty artifact is valid only for that complete no-score selection.
        all_classified = bool(expected) and set(expected_by_id) == set(outcomes)
        empty_selection = reader.fieldnames is None and arguments.input.stat().st_size == 0 and all_classified
        if not empty_selection and not required.issubset(reader.fieldnames or ()):
            raise ValueError(f"Rebar CSV is missing columns: {sorted(required - set(reader.fieldnames or ())) }")
        has_allocation = "allocation_bytes" in (reader.fieldnames or ())
        for line_number, measurement in enumerate(reader, start=2):
            system = engine_systems.get(measurement["engine"])
            if system != arguments.system:
                continue
            key = (measurement["name"], measurement["model"])
            if key in actual:
                raise ValueError(f"Duplicate Rebar measurement {key} for {arguments.system}")
            if not measurement["engine_version"]:
                raise ValueError(f"Rebar measurement has no engine version at {arguments.input}:{line_number}")
            allocation_bytes = measurement.get("allocation_bytes", "") if has_allocation else ""
            expected_row = expected_by_key.get(key)
            outcome = outcomes.get(expected_row["row_id"]) if expected_row is not None else None
            if measurement["err"]:
                if outcome is None or measurement["err"] != outcome["detail"]:
                    raise ValueError(
                        f"Unclassified Rebar measurement failure at "
                        f"{arguments.input}:{line_number}: {measurement['err']}")
                actual[key] = (None, "not-measured")
            else:
                if outcome is not None:
                    raise ValueError(
                        f"Rebar measurement succeeded after a classified mismatch for {outcome['row_id']!r}")
                actual[key] = (parse_duration_ns(measurement["median"]), allocation_bytes)

    missing = set(expected_by_key) - set(actual)
    for key in list(missing):
        expected_row = expected_by_key[key]
        if expected_row["row_id"] in outcomes:
            actual[key] = (None, "not-measured")
    missing = set(expected_by_key) - set(actual)
    unexpected = set(actual) - set(expected_by_key)
    if missing or unexpected:
        raise ValueError(
            f"Rebar rows differ from the manifest; missing={sorted(missing)[:20]}, "
            f"unexpected={sorted(unexpected)[:20]}")

    semantic_receipt(arguments.semantic_receipt)
    rows = []
    for key in sorted(expected_by_key):
        expected_row = expected_by_key[key]
        score, allocation_bytes = actual[key]
        if expected_row["row_id"] in outcomes:
            rows.append({
                "row_id": expected_row["row_id"],
                "shard_id": expected_row["shard_id"],
                "system": expected_row["system"],
                "score": "",
                "score_unit": "",
                "allocation_bytes": "not-measured",
                "result_checksum": result_checksum(expected_row),
                "outcome": outcomes[expected_row["row_id"]]["outcome"],
            })
            continue
        if expected_row["allocation_contract"] in {"recorded", "allocation-free"}:
            if not allocation_bytes:
                raise ValueError(
                    f"Rebar does not report allocation_bytes required by row {expected_row['row_id']!r}")
            allocation_bytes = format_number(float(allocation_bytes))
        else:
            allocation_bytes = format_number(float(allocation_bytes)) if allocation_bytes else "not-measured"
        rows.append({
            "row_id": expected_row["row_id"],
            "shard_id": expected_row["shard_id"],
            "system": expected_row["system"],
            "score": format_number(score),
            "score_unit": "ns/op",
            "allocation_bytes": allocation_bytes,
            "result_checksum": result_checksum(expected_row),
            "outcome": "accepted",
        })
    write_tsv(arguments.output, OBSERVED_FIELDS, rows)


def exact_filter(values):
    return "^(" + "|".join(re.escape(value) for value in sorted(set(values))) + ")$"


def print_jmh_filter(arguments):
    rows = selected_rows(load_manifest(arguments.manifest), arguments.shard, arguments.system)
    outcomes = load_rebar_outcomes(getattr(arguments, "rebar_outcomes", None))
    rows = [row for row in rows if row["row_id"] not in outcomes]
    if arguments.suite:
        rows = [row for row in rows if row["suite"] == arguments.suite]
    if not rows and not outcomes:
        raise ValueError("Selected manifest subset has no JMH rows")
    print(exact_filter(row["benchmark"] for row in rows) if rows else "")


def print_rebar_filter(arguments):
    rows = selected_rows(load_manifest(arguments.manifest), arguments.shard, arguments.system)
    rows = [
        row for row in rows
        if dict(item.split("=", 1) for item in row["parameters"].split(";"))["model"] == arguments.model
    ]
    if not rows:
        raise ValueError("Selected manifest subset has no Rebar rows")
    print(exact_filter(row["benchmark"] for row in rows))


def print_native_filter(arguments):
    rows = selected_rows(load_manifest(arguments.manifest), arguments.shard, arguments.system)
    print(exact_filter(row["comparator"] for row in rows))


def write_expected(arguments):
    rows = selected_rows(load_manifest(arguments.manifest), arguments.shard, arguments.systems)
    write_tsv(arguments.output, MANIFEST_FIELDS, rows)


def combine(arguments):
    expected = load_manifest(arguments.expected)
    outcomes = load_rebar_outcomes(getattr(arguments, "rebar_outcomes", None))
    observed = []
    for path in arguments.inputs:
        observed.extend(load_observed(path))

    identifiers = [row["row_id"] for row in observed]
    duplicate_ids = sorted(identifier for identifier in set(identifiers) if identifiers.count(identifier) > 1)
    expected_by_id = {row["row_id"]: row for row in expected}
    observed_by_id = {row["row_id"]: row for row in observed}
    missing = sorted(set(expected_by_id) - set(observed_by_id))
    unexpected = sorted(set(observed_by_id) - set(expected_by_id))
    if duplicate_ids or missing or unexpected:
        raise ValueError(
            f"Observed rows differ from expected rows; duplicates={duplicate_ids[:20]}, "
            f"missing={missing[:20]}, unexpected={unexpected[:20]}")
    unexpected_outcomes = sorted(set(outcomes) - set(expected_by_id))
    if unexpected_outcomes:
        raise ValueError(f"Rebar outcomes are not expected rows: {unexpected_outcomes[:20]}")
    for identifier, expected_row in expected_by_id.items():
        for field in ("row_id", "shard_id", "system"):
            if observed_by_id[identifier][field] != expected_row[field]:
                raise ValueError(f"Observed row {identifier!r} changed manifest field {field!r}")
        observed_row = observed_by_id[identifier]
        expected_outcome = outcomes.get(identifier, {}).get("outcome", "accepted")
        allowed_outcomes = (
            {"accepted", "precision-rejected"}
            if expected_outcome == "accepted"
            else {expected_outcome}
        )
        if observed_row["outcome"] not in allowed_outcomes:
            raise ValueError(
                f"Observed row {identifier!r} has outcome {observed_row['outcome']!r}, "
                f"expected one of {sorted(allowed_outcomes)!r}")
        if (expected_outcome in {"semantic-mismatch", "did-not-finish"} and
                (observed_row["score"] or
                 observed_row["score_unit"] or
                 observed_row["allocation_bytes"] != "not-measured")):
            raise ValueError(f"Classified-outcome row {identifier!r} contains performance data")
        if re.fullmatch(r"[0-9a-f]{64}", observed_row["result_checksum"]) is None:
            raise ValueError(f"Observed row {identifier!r} has an invalid result checksum")

    rows = [observed_by_id[identifier] for identifier in sorted(observed_by_id)]
    write_tsv(arguments.output, OBSERVED_FIELDS, rows)
    digest = hashlib.sha256(arguments.output.read_bytes()).hexdigest()
    arguments.output.with_suffix(arguments.output.suffix + ".sha256").write_text(
        f"{digest}  {arguments.output.name}\n", encoding="utf-8")
    print(f"Validated {len(rows)} observed rows")


def calibration(arguments):
    rows = []
    for phase, path in (("before", arguments.before), ("after", arguments.after)):
        actual = jmh_results([path], require_one_process_group_per_file=False)
        if len(actual) != 1:
            raise ValueError(f"Expected one calibration row in {path}, found {len(actual)}")
        (benchmark, parameters), result = next(iter(actual.items()))
        metric = result["primaryMetric"]
        score, sample_count, coefficient_of_variation = metric_statistics(metric)
        rows.append({
            "phase": phase,
            "benchmark": benchmark,
            "parameters": parameters,
            "score": format_number(score),
            "score_unit": metric["scoreUnit"],
            "sample_count": str(sample_count),
            "coefficient_of_variation": format_number(coefficient_of_variation),
            "raw_result": path.name,
        })
    if rows[0]["benchmark"] != rows[1]["benchmark"] or rows[0]["parameters"] != rows[1]["parameters"]:
        raise ValueError("Calibration before and after rows do not describe the same workload")
    before = float(rows[0]["score"])
    after = float(rows[1]["score"])
    drift = abs(after - before) / min(before, after)
    for row in rows:
        row["before_after_drift"] = format_number(drift)
    fields = tuple(rows[0])
    write_tsv(arguments.output, fields, rows)


def protocol_equivalence(arguments):
    process_count = 5
    if len(arguments.specialized) != process_count:
        raise ValueError(f"Protocol qualification requires exactly {process_count} specialized process results")
    if not math.isfinite(arguments.maximum) or arguments.maximum < 0:
        raise ValueError("Protocol qualification maximum must be finite and nonnegative")
    reference = jmh_results(
        [arguments.reference],
        require_one_process_group_per_file=False,
        merge_secondary_metrics=False)
    specialized = jmh_results(arguments.specialized, merge_secondary_metrics=False)
    if not reference or set(reference) != set(specialized):
        raise ValueError(
            "Protocol qualification inputs contain different benchmark rows: "
            f"missing={sorted(set(reference) - set(specialized))}, "
            f"unexpected={sorted(set(specialized) - set(reference))}")
    if any(len(result["primaryMetric"]["rawData"]) != process_count for result in reference.values()):
        raise ValueError(f"Protocol qualification reference requires exactly {process_count} fork results")
    rows = []
    failures = []
    for key in sorted(reference):
        reference_metric = reference[key]["primaryMetric"]
        specialized_metric = specialized[key]["primaryMetric"]
        for label, metric in (("reference", reference_metric), ("specialized", specialized_metric)):
            raw_data = metric["rawData"]
            if len(raw_data) != process_count:
                raise ValueError(
                    f"Protocol qualification {label} requires exactly {process_count} process groups for {key}")
            if any(len(group) != 10 for group in raw_data):
                raise ValueError(
                    f"Protocol qualification {label} requires exactly 10 measurements per process for {key}")
        if reference_metric["scoreUnit"] != specialized_metric["scoreUnit"]:
            raise ValueError(f"Protocol qualification inputs use different score units for {key}")
        reference_score, reference_samples, reference_cv = metric_statistics(reference_metric)
        specialized_score, specialized_samples, specialized_cv = metric_statistics(specialized_metric)
        if (not math.isfinite(reference_score) or not math.isfinite(specialized_score) or
                reference_score <= 0 or specialized_score <= 0):
            raise ValueError(f"Protocol qualification scores must be positive for {key}")
        if (not math.isfinite(reference_cv) or reference_cv < 0 or
                not math.isfinite(specialized_cv) or specialized_cv < 0):
            raise ValueError(f"Protocol qualification coefficients of variation must be finite and nonnegative for {key}")
        reference_rse = reference_cv / math.sqrt(reference_samples)
        specialized_rse = specialized_cv / math.sqrt(specialized_samples)
        relative_difference = abs(specialized_score - reference_score) / min(reference_score, specialized_score)
        accepted = (
            not exceeds_published_limit(relative_difference, arguments.maximum) and
            not exceeds_published_limit(reference_rse, 0.05) and
            not exceeds_published_limit(specialized_rse, 0.05))
        rows.append({
            "manifest_row_id": arguments.manifest_row_id,
            "system": arguments.system,
            "route": arguments.route,
            "benchmark": key[0],
            "parameters": key[1],
            "reference_protocol": f"{process_count}-forks-10x1s",
            "specialized_protocol": arguments.specialized_protocol,
            "reference_score": format_number(reference_score),
            "specialized_score": format_number(specialized_score),
            "score_unit": reference_metric["scoreUnit"],
            "reference_sample_count": str(reference_samples),
            "specialized_sample_count": str(specialized_samples),
            "reference_cv": format_number(reference_cv),
            "specialized_cv": format_number(specialized_cv),
            "relative_difference": format_number(relative_difference),
            "maximum_relative_difference": format_number(arguments.maximum),
            "outcome": "accepted" if accepted else "rejected",
        })
        if exceeds_published_limit(relative_difference, arguments.maximum):
            failures.append(f"{key}={format_number(relative_difference)}")
        if exceeds_published_limit(reference_rse, 0.05):
            failures.append(f"{key} reference relative standard error={format_number(reference_rse)}")
        if exceeds_published_limit(specialized_rse, 0.05):
            failures.append(f"{key} specialized relative standard error={format_number(specialized_rse)}")
    write_tsv(arguments.output, tuple(rows[0]), rows)
    if failures:
        raise ValueError(
            f"Specialized JMH protocol failed the median or relative standard error gate "
            f"(maximum median difference {format_number(arguments.maximum)}, maximum RSE 0.05): "
            f"{', '.join(failures)}")


def bracket(arguments):
    manifest_by_id = {row["row_id"]: row for row in load_manifest(arguments.manifest)}
    before = load_observed(arguments.before)
    after = load_observed(arguments.after)
    before_manifest = [manifest_by_id[row["row_id"]] for row in before]
    comparators_are_unique = len({row["comparator"] for row in before_manifest}) == len(before_manifest)

    def identity(row):
        manifest_row = manifest_by_id[row["row_id"]]
        if comparators_are_unique:
            return manifest_row["comparator"]
        return f"{manifest_row['benchmark']}[{manifest_row['parameters']}]"

    before_by_benchmark = {identity(row): row for row in before if row["outcome"] == "accepted"}
    after_by_benchmark = {identity(row): row for row in after if row["outcome"] == "accepted"}
    if set(before_by_benchmark) != set(after_by_benchmark):
        raise ValueError("Native before and after results contain different benchmarks")
    rows = []
    for benchmark in sorted(before_by_benchmark):
        before_score = float(before_by_benchmark[benchmark]["score"])
        after_score = float(after_by_benchmark[benchmark]["score"])
        rows.append({
            "benchmark": benchmark,
            "before_score_ns": format_number(before_score),
            "after_score_ns": format_number(after_score),
            "before_after_drift": format_number(abs(after_score - before_score) / min(before_score, after_score)),
        })
    write_tsv(arguments.output, tuple(rows[0]), rows)


def validate_drift(arguments):
    failures = []
    for path in [arguments.calibration, *arguments.native_bracket]:
        with path.open(newline="", encoding="utf-8") as input_file:
            reader = csv.DictReader(input_file, delimiter="\t")
            if "before_after_drift" not in (reader.fieldnames or ()):
                raise ValueError(f"Drift evidence has no before_after_drift column: {path}")
            rows = list(reader)
        if not rows:
            raise ValueError(f"Drift evidence is empty: {path}")
        for line_number, row in enumerate(rows, start=2):
            drift = float(row["before_after_drift"])
            if not math.isfinite(drift) or drift < 0:
                raise ValueError(f"Invalid drift in {path}:{line_number}: {row['before_after_drift']!r}")
            if path == arguments.calibration and drift > arguments.maximum:
                failures.append(f"{path}:{line_number}={format_number(drift)}")
    if failures:
        raise ValueError(
            f"Before/after drift exceeded {format_number(arguments.maximum)}: "
            + ", ".join(failures))


def jmh_runtime_seconds(row_count, process_count, warmup_iterations, measurement_iterations, iteration_seconds):
    if min(row_count, process_count, warmup_iterations, measurement_iterations) < 0:
        raise ValueError("JMH runtime inputs must not be negative")
    if iteration_seconds <= 0 or not math.isfinite(iteration_seconds):
        raise ValueError("JMH iteration time must be finite and positive")
    return row_count * process_count * (warmup_iterations + measurement_iterations) * iteration_seconds


def estimate_jmh_runtime(arguments):
    measured_seconds = jmh_runtime_seconds(
        arguments.rows,
        arguments.processes,
        arguments.warmup_iterations,
        arguments.measurement_iterations,
        arguments.iteration_seconds)
    forked_rows = getattr(arguments, "forked_rows", 0)
    forked_processes = getattr(arguments, "forked_processes", 0)
    if min(forked_rows, forked_processes) < 0:
        raise ValueError("Forked JMH runtime inputs must not be negative")
    if (forked_rows == 0) != (forked_processes == 0):
        raise ValueError("Forked JMH rows and processes must either both be zero or both be positive")
    measured_seconds += jmh_runtime_seconds(
        forked_rows,
        forked_processes,
        arguments.warmup_iterations,
        arguments.measurement_iterations,
        arguments.iteration_seconds)
    measured_startup_seconds = arguments.invocations * arguments.processes * arguments.startup_seconds
    measured_startup_seconds += forked_rows * forked_processes * arguments.startup_seconds
    calibration_seconds = jmh_runtime_seconds(
        arguments.calibration_rows,
        arguments.calibration_processes,
        arguments.calibration_warmup_iterations,
        arguments.calibration_measurement_iterations,
        arguments.calibration_iteration_seconds) * arguments.calibration_invocations
    calibration_startup_seconds = (
        arguments.calibration_invocations *
        (arguments.calibration_processes + 1) *
        arguments.startup_seconds)
    additional_seconds = getattr(arguments, "additional_seconds", 0)
    if additional_seconds < 0 or not math.isfinite(additional_seconds):
        raise ValueError("Additional runtime allowance must be finite and nonnegative")
    seconds = (
        measured_seconds +
        measured_startup_seconds +
        calibration_seconds +
        calibration_startup_seconds +
        additional_seconds)
    if seconds > arguments.maximum_seconds:
        raise ValueError(
            f"Static route duration estimate {format_number(seconds)}s exceeds "
            f"{format_number(arguments.maximum_seconds)}s")
    print(format_number(seconds))


def parser():
    argument_parser = argparse.ArgumentParser()
    subparsers = argument_parser.add_subparsers(dest="command", required=True)

    expected_parser = subparsers.add_parser("expected")
    expected_parser.add_argument("--manifest", type=Path, required=True)
    expected_parser.add_argument("--shard", required=True)
    expected_parser.add_argument("--systems", required=True)
    expected_parser.add_argument("--output", type=Path, required=True)
    expected_parser.set_defaults(function=write_expected)

    for command, function in (("jmh-filter", print_jmh_filter), ("native-filter", print_native_filter)):
        filter_parser = subparsers.add_parser(command)
        filter_parser.add_argument("--manifest", type=Path, required=True)
        filter_parser.add_argument("--shard", required=True)
        filter_parser.add_argument("--system", required=True)
        filter_parser.add_argument("--suite")
        filter_parser.add_argument("--rebar-outcomes", type=Path)
        filter_parser.set_defaults(function=function)

    rebar_filter_parser = subparsers.add_parser("rebar-filter")
    rebar_filter_parser.add_argument("--manifest", type=Path, required=True)
    rebar_filter_parser.add_argument("--shard", required=True)
    rebar_filter_parser.add_argument("--system", required=True)
    rebar_filter_parser.add_argument("--model", required=True)
    rebar_filter_parser.set_defaults(function=print_rebar_filter)

    jmh_parser = subparsers.add_parser("normalize-jmh")
    jmh_parser.add_argument("--manifest", type=Path, required=True)
    jmh_parser.add_argument("--shard", required=True)
    jmh_parser.add_argument("--suite")
    jmh_parser.add_argument("--system", required=True)
    benchmark_selection = jmh_parser.add_mutually_exclusive_group()
    benchmark_selection.add_argument("--include-benchmark-prefix", action="append")
    benchmark_selection.add_argument("--exclude-benchmark-prefix", action="append")
    jmh_parser.add_argument("--input", type=Path, nargs="+", required=True)
    jmh_parser.add_argument("--output", type=Path, required=True)
    jmh_parser.add_argument("--semantic-receipt", type=Path, required=True)
    jmh_parser.add_argument("--allow-traditional-unpaired", action="store_true")
    jmh_parser.add_argument("--allow-missing-secondary-metrics", action="store_true")
    jmh_parser.add_argument("--allow-mixed-process-sets", action="store_true")
    jmh_parser.add_argument("--allow-multiple-process-groups", action="store_true")
    jmh_parser.add_argument("--expected-process-groups", type=int)
    jmh_parser.add_argument("--maximum-relative-standard-error", type=float)
    jmh_parser.set_defaults(function=normalize_jmh)

    mapped_jmh_parser = subparsers.add_parser("normalize-mapped-jmh")
    mapped_jmh_parser.add_argument("--manifest", type=Path, required=True)
    mapped_jmh_parser.add_argument("--shard", required=True)
    mapped_jmh_parser.add_argument("--suite")
    mapped_jmh_parser.add_argument("--system", required=True)
    mapped_jmh_parser.add_argument("--input", type=Path, nargs="+", required=True)
    mapped_jmh_parser.add_argument("--output", type=Path, required=True)
    mapped_jmh_parser.add_argument("--semantic-receipt", type=Path, required=True)
    mapped_jmh_parser.add_argument("--benchmark-class", required=True)
    mapped_jmh_parser.add_argument("--method-map", action="append", required=True)
    mapped_jmh_parser.add_argument("--allow-missing-secondary-metrics", action="store_true")
    mapped_jmh_parser.add_argument("--allow-mixed-process-sets", action="store_true")
    mapped_jmh_parser.add_argument("--allow-multiple-process-groups", action="store_true")
    mapped_jmh_parser.add_argument("--expected-process-groups", type=int)
    mapped_jmh_parser.add_argument("--maximum-relative-standard-error", type=float)
    mapped_jmh_parser.set_defaults(function=normalize_mapped_jmh)

    native_parser = subparsers.add_parser("normalize-native")
    native_parser.add_argument("--manifest", type=Path, required=True)
    native_parser.add_argument("--shard", required=True)
    native_parser.add_argument("--system", required=True)
    native_parser.add_argument("--input", type=Path, required=True)
    native_parser.add_argument("--output", type=Path, required=True)
    native_parser.add_argument("--semantic-receipt", type=Path, required=True)
    native_parser.set_defaults(function=normalize_native)

    memory_parser = subparsers.add_parser("normalize-memory-census")
    memory_parser.add_argument("--manifest", type=Path, required=True)
    memory_parser.add_argument("--shard", required=True)
    memory_parser.add_argument("--system", required=True)
    memory_parser.add_argument("--engine", choices=("RE2", "Joni"), required=True)
    memory_parser.add_argument("--input", type=Path, required=True)
    memory_parser.add_argument("--output", type=Path, required=True)
    memory_parser.add_argument("--semantic-receipt", type=Path, required=True)
    memory_parser.set_defaults(function=normalize_memory_census)

    rebar_parser = subparsers.add_parser("normalize-rebar")
    rebar_parser.add_argument("--manifest", type=Path, required=True)
    rebar_parser.add_argument("--shard", required=True)
    rebar_parser.add_argument("--system", required=True)
    rebar_parser.add_argument("--input", type=Path, required=True)
    rebar_parser.add_argument("--output", type=Path, required=True)
    rebar_parser.add_argument("--semantic-receipt", type=Path, required=True)
    rebar_parser.add_argument("--rebar-outcomes", type=Path)
    rebar_parser.set_defaults(function=normalize_rebar)

    combine_parser = subparsers.add_parser("combine")
    combine_parser.add_argument("--expected", type=Path, required=True)
    combine_parser.add_argument("--output", type=Path, required=True)
    combine_parser.add_argument("--rebar-outcomes", type=Path)
    combine_parser.add_argument("inputs", type=Path, nargs="+")
    combine_parser.set_defaults(function=combine)

    calibration_parser = subparsers.add_parser("calibration")
    calibration_parser.add_argument("--before", type=Path, required=True)
    calibration_parser.add_argument("--after", type=Path, required=True)
    calibration_parser.add_argument("--output", type=Path, required=True)
    calibration_parser.set_defaults(function=calibration)

    protocol_parser = subparsers.add_parser("protocol-equivalence")
    protocol_parser.add_argument("--reference", type=Path, required=True)
    protocol_parser.add_argument("--specialized", type=Path, action="append", required=True)
    protocol_parser.add_argument("--manifest-row-id", required=True)
    protocol_parser.add_argument("--system", required=True)
    protocol_parser.add_argument("--route", choices=("native-access", "object-row"), required=True)
    protocol_parser.add_argument("--specialized-protocol", required=True)
    protocol_parser.add_argument("--maximum", type=float, required=True)
    protocol_parser.add_argument("--output", type=Path, required=True)
    protocol_parser.set_defaults(function=protocol_equivalence)

    bracket_parser = subparsers.add_parser("bracket")
    bracket_parser.add_argument("--manifest", type=Path, required=True)
    bracket_parser.add_argument("--before", type=Path, required=True)
    bracket_parser.add_argument("--after", type=Path, required=True)
    bracket_parser.add_argument("--output", type=Path, required=True)
    bracket_parser.set_defaults(function=bracket)

    drift_parser = subparsers.add_parser("validate-drift")
    drift_parser.add_argument("--calibration", type=Path, required=True)
    drift_parser.add_argument("--native-bracket", type=Path, action="append", default=[])
    drift_parser.add_argument("--maximum", type=float, required=True)
    drift_parser.set_defaults(function=validate_drift)

    runtime_parser = subparsers.add_parser("estimate-jmh-runtime")
    runtime_parser.add_argument("--rows", type=int, required=True)
    runtime_parser.add_argument("--invocations", type=int, required=True)
    runtime_parser.add_argument("--processes", type=int, required=True)
    runtime_parser.add_argument("--forked-rows", type=int, default=0)
    runtime_parser.add_argument("--forked-processes", type=int, default=0)
    runtime_parser.add_argument("--warmup-iterations", type=int, required=True)
    runtime_parser.add_argument("--measurement-iterations", type=int, required=True)
    runtime_parser.add_argument("--iteration-seconds", type=float, required=True)
    runtime_parser.add_argument("--startup-seconds", type=float, required=True)
    runtime_parser.add_argument("--calibration-invocations", type=int, required=True)
    runtime_parser.add_argument("--calibration-rows", type=int, required=True)
    runtime_parser.add_argument("--calibration-processes", type=int, required=True)
    runtime_parser.add_argument("--calibration-warmup-iterations", type=int, required=True)
    runtime_parser.add_argument("--calibration-measurement-iterations", type=int, required=True)
    runtime_parser.add_argument("--calibration-iteration-seconds", type=float, required=True)
    runtime_parser.add_argument("--additional-seconds", type=float, default=0)
    runtime_parser.add_argument("--maximum-seconds", type=float, required=True)
    runtime_parser.set_defaults(function=estimate_jmh_runtime)
    return argument_parser


def main():
    argument_parser = parser()
    arguments = argument_parser.parse_args()
    try:
        arguments.function(arguments)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        argument_parser.error(str(error))


if __name__ == "__main__":
    main()
