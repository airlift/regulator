#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

import summarize


class TestSummarize(unittest.TestCase):
    def test_ratios(self):
        with tempfile.TemporaryDirectory() as directory:
            result = Path(directory) / "result.json"
            scores = complete_scores()
            scores["candidate", "EXACT_MATCH"] = 5.0
            scores["trinoSql", "EXACT_MATCH"] = 10.0
            scores["trinoOptimized", "EXACT_MATCH"] = 4.0
            write_results(result, scores)

            rows = summarize.summarize(result)
            self.assertEqual(len(rows), len(summarize.SCENARIOS))
            exact = next(row for row in rows if row["scenario"] == "EXACT_MATCH")
            self.assertEqual(exact["candidate_to_sql"], 0.5)
            self.assertEqual(exact["candidate_to_optimized"], 1.25)

    def test_rejects_incompleteMatrix(self):
        with tempfile.TemporaryDirectory() as directory:
            result = Path(directory) / "result.json"
            scores = complete_scores()
            scores.pop(("trinoOptimized", "EXACT_MATCH"))
            write_results(result, scores)

            with self.assertRaisesRegex(ValueError, "incomplete"):
                summarize.summarize(result)


def complete_scores():
    return {
        (method, scenario): 10.0
        for method in summarize.METHODS
        for scenario in summarize.SCENARIOS
    }


def write_results(path, scores):
    rows = []
    for (method, scenario), score in scores.items():
        rows.append({
            "benchmark": f"io.airlift.regulator.benchmark.BenchmarkTrinoLike.{method}",
            "mode": "avgt",
            "params": {"scenario": scenario},
            "primaryMetric": {"score": score, "scoreUnit": "ns/op"},
        })
    path.write_text(json.dumps(rows), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
