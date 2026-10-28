#!/usr/bin/env python3

import argparse
import csv
import hashlib
import json
import math
import re
import statistics
from fractions import Fraction
from pathlib import Path

import row_protocol


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
SEMANTIC_FIELDS = ("route", "tests", "outcome", "semantic_result_digest")
FROZEN_PROTOCOL_SECONDS = {"warmup_seconds": "warmup_time", "measurement_seconds": "measurement_time"}


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


def sample_stdev(samples):
    """statistics.stdev as Python 3.9, the hosts' Python, computes it, so any controller recomputes host
    statistics exactly.

    Later Pythons round an exact standard deviation once, which can differ in the last bit.
    """
    count = len(samples)
    mean = float(sum(map(Fraction, samples)) / count)
    squares = sum(Fraction((sample - mean) ** 2) for sample in samples)
    deviations = sum(Fraction(sample - mean) for sample in samples)
    return math.sqrt(float((squares - deviations ** 2 / count) / (count - 1)))


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
        sample_stdev(samples) / mean
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
    expected_process_groups = getattr(arguments, "expected_process_groups", None)
    if allow_mixed_process_sets:
        if expected_process_groups is None or expected_process_groups <= 0:
            raise ValueError("Mixed JMH process sets require a positive expected process-group count")
    elif expected_process_groups is not None:
        raise ValueError("Expected process groups require mixed JMH process sets")
    expected = selected_rows(
        load_manifest(arguments.manifest), arguments.shard, arguments.system)
    if not expected:
        raise ValueError(
            f"No expected JMH rows for shard {arguments.shard!r} and system {arguments.system!r}")
    expected_by_key = {(row["benchmark"], row["parameters"]): row for row in expected}
    if len(expected_by_key) != len(expected):
        raise ValueError("Expected JMH rows are not unique by benchmark and parameters")

    actual = jmh_results(
        arguments.input,
        require_one_process_group_per_file=not allow_mixed_process_sets,
        allow_missing_secondary_metrics=getattr(arguments, "allow_missing_secondary_metrics", False),
        allow_disjoint_process_sets=allow_mixed_process_sets,
        expected_process_groups=expected_process_groups)
    unexpected = set(actual) - set(expected_by_key)
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
    expected_process_groups = getattr(arguments, "expected_process_groups", None)
    if allow_mixed_process_sets:
        if expected_process_groups is None or expected_process_groups <= 0:
            raise ValueError("Mixed JMH process sets require a positive expected process-group count")
    elif expected_process_groups is not None:
        raise ValueError("Expected process groups require mixed JMH process sets")
    for (benchmark, parameters), result in jmh_results(
            arguments.input,
            require_one_process_group_per_file=not allow_mixed_process_sets,
            allow_missing_secondary_metrics=getattr(
                arguments, "allow_missing_secondary_metrics", False),
            allow_disjoint_process_sets=allow_mixed_process_sets,
            expected_process_groups=expected_process_groups).items():
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


# The runner records a row's parameters in its context as parameter.<name>=<value>.
NATIVE_PARAMETER_PREFIX = "parameter."


def native_results(paths, expected_repetitions):
    """Each Google Benchmark result file's median repetition, keyed by operation and row parameters.

    Every file must hold the planned selector's repetitions, so a dry run or a runner flag read
    from the environment cannot stand in for the frozen protocol.
    """
    results = {}
    for path in paths:
        document = json.loads(path.read_text(encoding="utf-8"))
        context = document.get("context") if isinstance(document, dict) else None
        benchmarks = document.get("benchmarks") if isinstance(document, dict) else None
        if not isinstance(context, dict) or not isinstance(benchmarks, list) or not benchmarks:
            raise ValueError(f"Native result is empty or malformed: {path}")
        if context.get("dry_run", "false") != "false":
            raise ValueError(f"Native result is a dry run: {path}")
        parameters = canonical_parameters({
            name[len(NATIVE_PARAMETER_PREFIX):]: value for name, value in context.items()
            if name.startswith(NATIVE_PARAMETER_PREFIX)})
        repetitions = {}
        for result in benchmarks:
            if result.get("error_occurred") or result.get("skipped"):
                raise ValueError(f"Native benchmark {result.get('name')} failed or was skipped in {path}")
            if result.get("run_type") != "iteration":
                continue
            if result.get("time_unit") != "ns":
                raise ValueError(
                    f"Unexpected native unit for {result.get('name')} in {path}: {result.get('time_unit')}")
            if int(result["repetitions"]) != expected_repetitions:
                raise ValueError(f"Native benchmark {result.get('name')} in {path} declares "
                                 f"{result['repetitions']} repetitions, not {expected_repetitions}")
            if int(result["iterations"]) < 1:
                raise ValueError(f"Native benchmark {result.get('name')} in {path} ran no iterations")
            repetitions.setdefault((result["run_name"], parameters), []).append(result)
        for key, samples in repetitions.items():
            if key in results:
                raise ValueError(f"Duplicate native result {key} in {path}")
            if len(samples) != expected_repetitions:
                raise ValueError(f"Native result {key} in {path} has {len(samples)} of "
                                 f"{expected_repetitions} repetitions")
            indices = sorted(int(sample["repetition_index"]) for sample in samples)
            if indices != list(range(expected_repetitions)):
                raise ValueError(f"Native result {key} in {path} has repetition indices {indices}, "
                                 f"not 0 to {expected_repetitions - 1}")
            real_times = [float(sample["real_time"]) for sample in samples]
            invalid = [value for value in real_times if not math.isfinite(value) or value <= 0]
            if invalid:
                raise ValueError(f"Native result {key} in {path} has non-finite or non-positive times {invalid}")
            results[key] = statistics.median(real_times)
    return results


def normalize_native(arguments):
    expected = selected_rows(load_manifest(arguments.manifest), arguments.shard, arguments.system)
    expected_by_key = {}
    for row in expected:
        key = (row["benchmark"], row["parameters"])
        if key in expected_by_key:
            raise ValueError(f"Expected native rows {expected_by_key[key]['row_id']} and {row['row_id']} "
                             f"share operation and parameters {key}")
        expected_by_key[key] = row
    actual = native_results(arguments.input, arguments.expected_repetitions)
    missing = set(expected_by_key) - set(actual)
    unexpected = set(actual) - set(expected_by_key)
    if missing or unexpected:
        raise ValueError(
            f"Native rows differ from the manifest; missing={sorted(missing)[:20]}, "
            f"unexpected={sorted(unexpected)[:20]}")
    semantic_receipt(arguments.semantic_receipt)
    rows = [{
        "row_id": expected_by_key[key]["row_id"],
        "shard_id": expected_by_key[key]["shard_id"],
        "system": expected_by_key[key]["system"],
        "score": format_number(actual[key]),
        "score_unit": "ns/op",
        "allocation_bytes": "not-measured",
        "result_checksum": result_checksum(expected_by_key[key]),
        "outcome": "accepted",
    } for key in sorted(expected_by_key)]
    write_tsv(arguments.output, OBSERVED_FIELDS, rows)


def exact_filter(values):
    return "^(" + "|".join(re.escape(value) for value in sorted(set(values))) + ")$"


def print_jmh_filter(arguments):
    rows = selected_rows(load_manifest(arguments.manifest), arguments.shard, arguments.system)
    if not rows:
        raise ValueError("Selected manifest subset has no JMH rows")
    print(exact_filter(row["benchmark"] for row in rows))


def write_expected(arguments):
    rows = selected_rows(load_manifest(arguments.manifest), arguments.shard, arguments.systems)
    write_tsv(arguments.output, MANIFEST_FIELDS, rows)


def combined_rows(expected, observed):
    """Observed rows, sorted, after validating that they are exactly the expected rows, each once."""
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
    for identifier, expected_row in expected_by_id.items():
        for field in ("row_id", "shard_id", "system"):
            if observed_by_id[identifier][field] != expected_row[field]:
                raise ValueError(f"Observed row {identifier!r} changed manifest field {field!r}")
        observed_row = observed_by_id[identifier]
        if observed_row["outcome"] not in {"accepted", "precision-rejected"}:
            raise ValueError(
                f"Observed row {identifier!r} has outcome {observed_row['outcome']!r}, "
                "expected accepted or precision-rejected")
        if re.fullmatch(r"[0-9a-f]{64}", observed_row["result_checksum"]) is None:
            raise ValueError(f"Observed row {identifier!r} has an invalid result checksum")

    return [observed_by_id[identifier] for identifier in sorted(observed_by_id)]


def write_observed_rows(path, fields, rows):
    write_tsv(path, fields, rows)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    path.with_suffix(path.suffix + ".sha256").write_text(f"{digest}  {path.name}\n", encoding="utf-8")


def combine(arguments):
    observed = []
    for path in arguments.inputs:
        observed.extend(load_observed(path))
    rows = combined_rows(load_manifest(arguments.expected), observed)
    write_observed_rows(arguments.output, OBSERVED_FIELDS, rows)
    print(f"Validated {len(rows)} observed rows")


def calibration(arguments):
    rows = calibration_rows(arguments.before, arguments.after)
    write_tsv(arguments.output, tuple(rows[0]), rows)


def calibration_rows(before_path, after_path):
    """The before/after calibration table of two calibration JMH results."""
    rows = []
    for phase, path in (("before", before_path), ("after", after_path)):
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
    return rows


class ProtocolQualificationError(ValueError):
    """Valid timing evidence failed the numerical protocol gate."""


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
        raise ProtocolQualificationError(
            f"Specialized JMH protocol failed the median or relative standard error gate "
            f"(maximum median difference {format_number(arguments.maximum)}, maximum RSE 0.05): "
            f"{', '.join(failures)}")


def validate_drift(arguments):
    failures = []
    path = arguments.calibration
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
        if drift > arguments.maximum:
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


def frozen_selection(protocols, rows, execution):
    selected = [protocols[row["row_id"]] for row in rows]
    return [protocol for protocol in selected if protocol["execution"] == execution]


def frozen_fields(selected, fields):
    values = []
    for field in fields:
        source = FROZEN_PROTOCOL_SECONDS.get(field, field)
        distinct = {protocol[source] for protocol in selected}
        if len(distinct) != 1:
            raise ValueError(f"Selected rows do not share one frozen {source}: {sorted(distinct)}")
        value = distinct.pop()
        values.append(f"{row_protocol.seconds(value):g}" if field in FROZEN_PROTOCOL_SECONDS else value)
    return values


FROZEN_PROCESS_FIELDS = (
    "processes", "warmup_iterations", "measurement_iterations",
    "measurement_time", "measurement_seconds", "maximum_relative_standard_error")


def print_frozen_route_protocol(arguments):
    """Answers every frozen-protocol question a route plan asks, from one load of the protocol."""
    protocols = row_protocol.load(arguments.row_protocol)
    rows = load_manifest(arguments.expected)
    processes = frozen_selection(protocols, rows, "jmh-processes")
    if processes:
        print("\t".join(["process", *frozen_fields(processes, FROZEN_PROCESS_FIELDS)]))
    forks = frozen_selection(protocols, rows, "jmh-forks")
    if forks:
        print("\t".join(["forks", *frozen_fields(forks, ("processes", "measurement_time"))]))
    with arguments.representatives.open(newline="", encoding="utf-8") as input_file:
        representatives = {row["manifest_row_id"] for row in csv.DictReader(input_file, delimiter="\t")}
    for row in rows:
        if row["row_id"] in representatives:
            print(f"representative\t{row['row_id']}\t{protocols[row['row_id']]['measurement_time']}")


def parser():
    argument_parser = argparse.ArgumentParser()
    subparsers = argument_parser.add_subparsers(dest="command", required=True)

    expected_parser = subparsers.add_parser("expected")
    expected_parser.add_argument("--manifest", type=Path, required=True)
    expected_parser.add_argument("--shard", required=True)
    expected_parser.add_argument("--systems", required=True)
    expected_parser.add_argument("--output", type=Path, required=True)
    expected_parser.set_defaults(function=write_expected)

    filter_parser = subparsers.add_parser("jmh-filter")
    filter_parser.add_argument("--manifest", type=Path, required=True)
    filter_parser.add_argument("--shard", required=True)
    filter_parser.add_argument("--system", required=True)
    filter_parser.set_defaults(function=print_jmh_filter)

    frozen_route_parser = subparsers.add_parser("frozen-route-protocol")
    frozen_route_parser.add_argument("--row-protocol", type=Path, required=True)
    frozen_route_parser.add_argument("--expected", type=Path, required=True)
    frozen_route_parser.add_argument("--representatives", type=Path, required=True)
    frozen_route_parser.set_defaults(function=print_frozen_route_protocol)

    jmh_parser = subparsers.add_parser("normalize-jmh")
    jmh_parser.add_argument("--manifest", type=Path, required=True)
    jmh_parser.add_argument("--shard", required=True)
    jmh_parser.add_argument("--system", required=True)
    jmh_parser.add_argument("--input", type=Path, nargs="+", required=True)
    jmh_parser.add_argument("--output", type=Path, required=True)
    jmh_parser.add_argument("--semantic-receipt", type=Path, required=True)
    jmh_parser.add_argument("--allow-missing-secondary-metrics", action="store_true")
    jmh_parser.add_argument("--allow-mixed-process-sets", action="store_true")
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
    mapped_jmh_parser.add_argument("--expected-process-groups", type=int)
    mapped_jmh_parser.add_argument("--maximum-relative-standard-error", type=float)
    mapped_jmh_parser.set_defaults(function=normalize_mapped_jmh)

    native_parser = subparsers.add_parser("normalize-native")
    native_parser.add_argument("--manifest", type=Path, required=True)
    native_parser.add_argument("--shard", required=True)
    native_parser.add_argument("--system", required=True)
    native_parser.add_argument("--input", type=Path, nargs="+", required=True)
    native_parser.add_argument("--expected-repetitions", type=int, required=True)
    native_parser.add_argument("--output", type=Path, required=True)
    native_parser.add_argument("--semantic-receipt", type=Path, required=True)
    native_parser.set_defaults(function=normalize_native)

    combine_parser = subparsers.add_parser("combine")
    combine_parser.add_argument("--expected", type=Path, required=True)
    combine_parser.add_argument("--output", type=Path, required=True)
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

    drift_parser = subparsers.add_parser("validate-drift")
    drift_parser.add_argument("--calibration", type=Path, required=True)
    drift_parser.add_argument("--maximum", type=float, required=True)
    drift_parser.set_defaults(function=validate_drift)

    return argument_parser


def main():
    argument_parser = parser()
    arguments = argument_parser.parse_args()
    try:
        arguments.function(arguments)
    except ProtocolQualificationError as error:
        argument_parser.exit(42, str(error) + "\n")
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        argument_parser.error(str(error))


if __name__ == "__main__":
    main()
