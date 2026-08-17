#!/usr/bin/env python3

import argparse
import csv
import json
import math
from pathlib import Path


METHODS = ("candidate", "trinoSql", "trinoOptimized")
SCENARIOS = {
    "ANY_ASCII",
    "ANY_MULTIBYTE",
    "CONTAINS_ABSENT",
    "CONTAINS_LATE",
    "EXACT_MATCH",
    "MIXED_ABSENT",
    "MIXED_LATE",
    "ORDERED_DENSE_FALSE",
    "ORDERED_LATE",
    "PREFIX_LARGE",
    "SUFFIX_LARGE",
    "WILDCARD_CHAIN",
}


def load(path):
    results = json.loads(path.read_text(encoding="utf-8"))
    rows = {}
    for result in results:
        method = result["benchmark"].rsplit(".", 1)[-1]
        scenario = result["params"]["scenario"]
        if method not in METHODS:
            raise ValueError(f"unexpected benchmark method: {method}")
        if scenario not in SCENARIOS:
            raise ValueError(f"unexpected benchmark scenario: {scenario}")
        if result.get("mode") != "avgt":
            raise ValueError(f"unexpected benchmark mode for {(method, scenario)}: {result.get('mode')}")
        if result["primaryMetric"].get("scoreUnit") != "ns/op":
            raise ValueError(
                f"unexpected benchmark score unit for {(method, scenario)}: "
                f"{result['primaryMetric'].get('scoreUnit')}")
        key = (method, scenario)
        if key in rows:
            raise ValueError(f"duplicate benchmark row: {key}")
        score = result["primaryMetric"]["score"]
        if not math.isfinite(score) or score <= 0:
            raise ValueError(f"invalid score for {key}: {score}")
        rows[key] = score
    scenarios = {scenario for _, scenario in rows}
    if scenarios != SCENARIOS:
        raise ValueError(f"incomplete Trino LIKE scenario set: {sorted(scenarios)}")
    expected = {(method, scenario) for method in METHODS for scenario in SCENARIOS}
    if set(rows) != expected:
        raise ValueError("incomplete Trino LIKE benchmark matrix")
    return rows, sorted(scenarios)


def summarize(path):
    scores, scenarios = load(path)
    rows = []
    for scenario in scenarios:
        candidate = scores["candidate", scenario]
        sql = scores["trinoSql", scenario]
        optimized = scores["trinoOptimized", scenario]
        rows.append({
            "scenario": scenario,
            "candidate_ns": candidate,
            "trino_sql_ns": sql,
            "trino_optimized_ns": optimized,
            "candidate_to_sql": candidate / sql,
            "candidate_to_optimized": candidate / optimized,
        })
    return rows


def write_csv(path, rows):
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)


def write_report(path, rows):
    with path.open("w", encoding="utf-8") as output:
        output.write("# Trino LIKE Comparison\n\n")
        output.write("Lower candidate/Trino ratios are better.\n\n")
        output.write("| Scenario | Candidate | Trino SQL | Trino optimized | Candidate/SQL | Candidate/optimized |\n")
        output.write("|---|---:|---:|---:|---:|---:|\n")
        for row in rows:
            output.write(
                f"| {row['scenario']} | {row['candidate_ns']:.3f} ns | "
                f"{row['trino_sql_ns']:.3f} ns | {row['trino_optimized_ns']:.3f} ns | "
                f"{row['candidate_to_sql']:.3f}x | {row['candidate_to_optimized']:.3f}x |\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("result", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    rows = summarize(args.result)
    args.output.mkdir(parents=True, exist_ok=True)
    write_csv(args.output / "results.csv", rows)
    write_report(args.output / "report.md", rows)


if __name__ == "__main__":
    main()
