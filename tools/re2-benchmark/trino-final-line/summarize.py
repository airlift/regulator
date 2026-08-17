#!/usr/bin/env python3

import csv
import json
import math
import pathlib
import statistics
import sys


def load(path):
    with path.open() as input_file:
        entries = json.load(input_file)
    return {
        (
            entry["benchmark"].rsplit(".", 1)[1].removesuffix("Regulator").removesuffix("Joni"),
            entry["params"]["workload"],
            int(entry["params"]["sourceLength"]),
        ): entry["primaryMetric"]["score"]
        for entry in entries
    }


def main():
    if len(sys.argv) != 5:
        raise SystemExit("usage: summarize.py REGULATOR_BEFORE JONI REGULATOR_AFTER OUTPUT")

    regulator_before = load(pathlib.Path(sys.argv[1]))
    joni = load(pathlib.Path(sys.argv[2]))
    regulator_after = load(pathlib.Path(sys.argv[3]))
    output_path = pathlib.Path(sys.argv[4])

    if regulator_before.keys() != joni.keys() or regulator_before.keys() != regulator_after.keys():
        raise SystemExit("benchmark result keys do not agree")

    rows = []
    for operation, workload, source_length in sorted(regulator_before):
        before = regulator_before[(operation, workload, source_length)]
        after = regulator_after[(operation, workload, source_length)]
        joni_score = joni[(operation, workload, source_length)]
        regulator_score = math.sqrt(before * after)
        bracket = max(before, after) / min(before, after)
        rows.append({
            "operation": operation,
            "workload": workload,
            "source_length": source_length,
            "regulator_before_ns": before,
            "regulator_after_ns": after,
            "regulator_ns": regulator_score,
            "joni_ns": joni_score,
            "regulator_joni_ratio": regulator_score / joni_score,
            "regulator_bracket": bracket,
        })

    with output_path.open("w", newline="") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)

    ratios = [row["regulator_joni_ratio"] for row in rows]
    stable = [row for row in rows if row["regulator_bracket"] <= 1.02]
    stable_ratios = [row["regulator_joni_ratio"] for row in stable]
    print(f"rows={len(rows)} stable={len(stable)}")
    print(f"geometric_ratio={math.exp(statistics.fmean(math.log(value) for value in ratios)):.6f}")
    print(f"median_ratio={statistics.median(ratios):.6f}")
    print(f"worst_ratio={max(ratios):.6f}")
    if stable_ratios:
        print(f"stable_geometric_ratio={math.exp(statistics.fmean(math.log(value) for value in stable_ratios)):.6f}")
        print(f"stable_worst_ratio={max(stable_ratios):.6f}")


if __name__ == "__main__":
    main()
