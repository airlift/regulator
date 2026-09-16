import csv
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "shard_results.py"
sys.path.insert(0, str(SCRIPT.parent))

from acceptance import verify_protocol_qualification  # noqa: E402


class TestProtocolEquivalence(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.reference = self.root / "reference.json"
        self.specialized = [self.root / f"specialized-{index}.json" for index in range(5)]
        self.output = self.root / "protocol.tsv"

    def tearDown(self):
        self.directory.cleanup()

    @staticmethod
    def result(raw_data, benchmarks=("searchEasy0Dfa", "searchHardRe2")):
        return [
            {
                "benchmark": f"io.airlift.regulator.BenchmarkRe2Search.{benchmark}",
                "params": {"textSize": "32768"},
                "primaryMetric": {
                    "score": 100,
                    "scoreUnit": "ns/op",
                    "rawData": raw_data,
                },
                "secondaryMetrics": {},
            }
            for benchmark in benchmarks
        ]

    def write_inputs(self, specialized_score, benchmarks=("searchEasy0Dfa", "searchHardRe2")):
        self.reference.write_text(json.dumps(self.result(
            [[100] * 10 for _ in range(5)], benchmarks)))
        for path in self.specialized:
            path.write_text(json.dumps(self.result([[specialized_score] * 10], benchmarks)))

    def write_raw_inputs(
            self,
            reference_groups,
            specialized_groups=None,
            benchmarks=("searchEasy0Dfa", "searchHardRe2")):
        specialized_groups = specialized_groups or reference_groups
        self.reference.write_text(json.dumps(self.result(reference_groups, benchmarks)))
        for path, group in zip(self.specialized, specialized_groups, strict=True):
            path.write_text(json.dumps(self.result([group], benchmarks)))

    def run_tool(self):
        command = [
            sys.executable,
            str(SCRIPT),
            "protocol-equivalence",
            "--reference",
            str(self.reference),
            "--manifest-row-id",
            "representative/row",
            "--system",
            "regulator-native-access",
            "--route",
            "native-access",
            "--specialized-protocol",
            "5-independent-jvms-10x300ms-warmup-10x50ms-measurement",
        ]
        for path in self.specialized:
            command.extend(("--specialized", str(path)))
        command.extend(("--maximum", "0.05", "--output", str(self.output)))
        return subprocess.run(command, text=True, capture_output=True)

    def test_accepts_equivalent_specialized_protocol(self):
        self.write_inputs(104)
        result = self.run_tool()
        self.assertEqual(result.returncode, 0, result.stderr)
        with self.output.open(newline="") as input_file:
            rows = list(csv.DictReader(input_file, delimiter="\t"))
        self.assertEqual(2, len(rows))
        self.assertEqual({"accepted"}, {row["outcome"] for row in rows})
        for row in rows:
            self.assertEqual("representative/row", row["manifest_row_id"])
            self.assertEqual("regulator-native-access", row["system"])
            self.assertEqual("native-access", row["route"])
            self.assertEqual(
                "5-independent-jvms-10x300ms-warmup-10x50ms-measurement",
                row["specialized_protocol"])
            self.assertAlmostEqual(float(row["relative_difference"]), 0.04)

    def test_rejects_divergent_specialized_protocol(self):
        self.write_inputs(106)
        result = self.run_tool()
        self.assertEqual(result.returncode, 42)
        self.assertIn("maximum median difference 0.050000000000000003", result.stderr)
        with self.output.open(newline="") as input_file:
            rows = list(csv.DictReader(input_file, delimiter="\t"))
        self.assertEqual(2, len(rows))
        self.assertEqual({"rejected"}, {row["outcome"] for row in rows})

    def test_malformed_evidence_does_not_use_statistical_rejection_status(self):
        self.write_inputs(100)
        self.reference.write_text("invalid json")
        result = self.run_tool()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotEqual(result.returncode, 42)

    def test_median_difference_boundary(self):
        for specialized_score, accepted in (
                (104.9999999999, True),
                (105.0, True),
                (105.0000000001, False)):
            with self.subTest(specialized_score=specialized_score):
                self.write_inputs(specialized_score)

                result = self.run_tool()

                self.assertEqual(result.returncode == 0, accepted, result.stderr)
                with self.output.open(newline="") as input_file:
                    rows = list(csv.DictReader(input_file, delimiter="\t"))
                self.assertEqual(
                    {"accepted" if accepted else "rejected"},
                    {row["outcome"] for row in rows})

    def test_ignores_optional_secondary_metric_differences(self):
        self.write_inputs(100)
        document = self.result([[100] * 10])
        for result in document:
            result["secondaryMetrics"]["gc.time"] = {
                "score": 1,
                "scoreUnit": "ms/op",
                "rawData": [[1] * 10],
            }
        self.specialized[0].write_text(json.dumps(document))

        result = self.run_tool()

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_noisy_protocols_with_equal_medians(self):
        noisy_group = [1, 199] * 5
        self.write_raw_inputs([noisy_group] * 5)

        result = self.run_tool()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("relative standard error", result.stderr)
        with self.output.open(newline="") as input_file:
            rows = list(csv.DictReader(input_file, delimiter="\t"))
        self.assertEqual({"rejected"}, {row["outcome"] for row in rows})

    def test_accepts_exact_relative_standard_error_limit(self):
        scale = 105939450471589
        samples = ([13 * scale] * 25) + ([27 * scale] * 25)
        groups = [samples[index:index + 10] for index in range(0, len(samples), 10)]
        self.write_raw_inputs(groups)

        result = self.run_tool()

        self.assertEqual(result.returncode, 0, result.stderr)
        with self.output.open(newline="") as input_file:
            rows = list(csv.DictReader(input_file, delimiter="\t"))
        self.assertEqual({"accepted"}, {row["outcome"] for row in rows})

    def test_relative_standard_error_boundary_for_both_protocols(self):
        scale = 2_118_789_009
        for changed_protocol in ("reference", "specialized"):
            for high_delta, accepted in ((-1, True), (0, True), (1, False)):
                with self.subTest(changed_protocol=changed_protocol, high_delta=high_delta):
                    low_sample = 13 * scale
                    high_sample = 27 * scale + high_delta
                    stable_sample = (low_sample + high_sample) / 2
                    stable_groups = [[stable_sample] * 10 for _ in range(5)]
                    boundary_samples = ([low_sample] * 25) + ([high_sample] * 25)
                    boundary_groups = [
                        boundary_samples[index:index + 10]
                        for index in range(0, len(boundary_samples), 10)
                    ]
                    reference_groups = boundary_groups if changed_protocol == "reference" else stable_groups
                    specialized_groups = boundary_groups if changed_protocol == "specialized" else stable_groups
                    self.write_raw_inputs(reference_groups, specialized_groups)

                    result = self.run_tool()

                    self.assertEqual(result.returncode == 0, accepted, result.stderr)
                    with self.output.open(newline="") as input_file:
                        rows = list(csv.DictReader(input_file, delimiter="\t"))
                    self.assertEqual(
                        {"accepted" if accepted else "rejected"},
                        {row["outcome"] for row in rows})

    def test_rejects_malformed_measurement_group(self):
        groups = [[100] * 10 for _ in range(5)]
        groups[-1] = [100] * 9
        self.write_raw_inputs(groups)

        result = self.run_tool()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exactly 10 measurements", result.stderr)

    def test_rejects_wrong_reference_process_count(self):
        reference_groups = [[100] * 10 for _ in range(4)]
        specialized_groups = [[100] * 10 for _ in range(5)]
        self.write_raw_inputs(reference_groups, specialized_groups)

        result = self.run_tool()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reference requires exactly 5 fork results", result.stderr)

    def test_rejects_specialized_file_with_multiple_process_groups(self):
        self.write_inputs(100)
        self.specialized[0].write_text(json.dumps(self.result([[
            100,
        ] * 10, [
            100,
        ] * 10])))

        result = self.run_tool()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("has 2 process sample groups, expected one", result.stderr)

    def test_rejects_nonfinite_raw_measurement(self):
        groups = [[100] * 10 for _ in range(5)]
        groups[0][0] = float("nan")
        self.write_raw_inputs(groups)

        result = self.run_tool()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("finite", result.stderr)

    def test_persisted_output_passes_acceptance_verifier(self):
        self.write_inputs(104, benchmarks=("searchEasy0Dfa",))

        result = self.run_tool()

        self.assertEqual(result.returncode, 0, result.stderr)
        verify_protocol_qualification(
            self.output,
            "native-access",
            {"regulator-native-access"},
            1)


if __name__ == "__main__":
    unittest.main()
