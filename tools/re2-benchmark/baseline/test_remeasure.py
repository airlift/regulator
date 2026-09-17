import copy
import csv
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import remeasure


class TestRemeasurement(unittest.TestCase):
    def setUp(self):
        self.protocol = json.loads((remeasure.BASELINE / "remeasurement-protocol.json").read_text())

    def test_partition_coverage_preserves_every_original_row(self):
        with (remeasure.BASELINE / "rows.tsv").open() as source:
            original = {row["row_id"]: row for row in csv.DictReader(source, delimiter="\t")
                        if row["shard_id"] in remeasure.SHARDS}
        selected = {}
        for shard, partitions in (("trino-operations", 46), ("trino-final-line", 44)):
            for partition in range(partitions):
                plan = remeasure.partition_plan(shard, partition, self.protocol)
                self.assertLessEqual(len(plan["selections"]), 12)
                for selection in plan["selections"]:
                    row = selection["original_row"]
                    self.assertNotIn(row["row_id"], selected)
                    selected[row["row_id"]] = row
                    if shard == "trino-final-line":
                        self.assertTrue(selection["benchmark"].endswith("Joni" if row["system"] == "joni" else "Regulator"))
            with self.assertRaises(ValueError):
                remeasure.partition_plan(shard, partitions, self.protocol)
        self.assertEqual(selected, original)
        self.assertEqual(len(selected), 1080)

    def test_changed_manifest_or_protocol_cannot_be_reused(self):
        for key, value in (("original_row_manifest_sha256", "0" * 64), ("forks", 1),
                           ("cpu_allowance", "0"), ("gc", "Serial"), ("operations_per_partition", 40)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                remeasure.partition_plan("trino-final-line", 0, {**self.protocol, key: value})

    def test_protocol_freezes_the_qualified_worker_allocations(self):
        expected = {
            "r8i": {"instance_type": "r8i.large", "vcpus": 2},
            "r8g": {"instance_type": "r8g.large", "vcpus": 2},
            "r9g": {"instance_type": "r9g.2xlarge", "vcpus": 8},
        }
        self.assertEqual(self.protocol["allocations"], expected)
        for platform in expected:
            changed = copy.deepcopy(self.protocol)
            changed["allocations"][platform]["vcpus"] = 4
            with self.subTest(platform=platform), self.assertRaisesRegex(ValueError, "allocations"):
                remeasure.partition_plan("trino-final-line", 0, changed)

    def test_host_allocation_must_match_the_frozen_plan(self):
        plan = remeasure.partition_plan("trino-final-line", 0, self.protocol)
        receipt = {"identity": {"platform": "r9g", "instance_type": "r9g.2xlarge"}}
        with tempfile.TemporaryDirectory() as temporary:
            session = Path(temporary)
            environment = session / "environment-manifest.txt"
            environment.write_text("logical_cpu_count=8\n")
            remeasure.validate_host_allocation(plan, receipt, session)
            for instance_type, vcpus in (("r9g.large", 8), ("r9g.2xlarge", 2)):
                receipt["identity"]["instance_type"] = instance_type
                environment.write_text(f"logical_cpu_count={vcpus}\n")
                with self.subTest(instance_type=instance_type, vcpus=vcpus), \
                        self.assertRaisesRegex(ValueError, "frozen allocation"):
                    remeasure.validate_host_allocation(plan, receipt, session)

    def test_non_integer_protocol_values_are_rejected(self):
        for key in ("schema_version", "forks", "warmup_iterations", "measurement_iterations", "iteration_seconds"):
            for value in (True, float(self.protocol[key])):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    remeasure.partition_plan("trino-final-line", 0, {**self.protocol, key: value})

    def test_qualification_evidence_is_content_addressed_and_required(self):
        remeasure.partition_plan("trino-final-line", 0, self.protocol)
        for key, value in (("qualified_by_pilot", False), ("pilot_qualification_sha256", "0" * 64),
                           ("qualification_attestation_sha256", "0" * 64)):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "qualification|independent pilot"):
                remeasure.partition_plan("trino-final-line", 0, {**self.protocol, key: value})
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / f'{self.protocol["qualification_attestation_sha256"]}.json'
            path.write_bytes(b'{}\n')
            with patch.object(remeasure, "QUALIFICATION_EVIDENCE", Path(temporary)), \
                    self.assertRaisesRegex(ValueError, "hash differs"):
                remeasure.partition_plan("trino-final-line", 0, self.protocol)

    def test_worker_rechecks_qualification_evidence(self):
        plan = remeasure.partition_plan("trino-final-line", 0, self.protocol)
        plan["protocol"] = {**plan["protocol"], "qualification_attestation_sha256": "0" * 64}
        with self.assertRaisesRegex(ValueError, "qualification evidence"):
            remeasure.run(plan, Path("/missing-session"))

    def test_allocation_checkpoints_only_valid_completed_measurements(self):
        plan = remeasure.partition_plan("trino-final-line", 0, self.protocol)
        selection = plan["selections"][0]
        allocation_protocol = {**self.protocol, "forks": 1, "warmup_iterations": 3,
                               "measurement_iterations": 3, "allocation_only": True}
        valid = {"secondaryMetrics": {"gc.alloc.rate.norm": {"scoreUnit": "B/op", "score": 0.0}}}
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with patch.object(remeasure.commands, "jmh_command", return_value=["jmh"]), \
                    patch.object(remeasure.commands, "execute"), \
                    patch.object(remeasure, "validate_timing", return_value=valid), \
                    patch.object(remeasure, "checkpoint") as checkpoint:
                remeasure.measure_allocation(
                    output, 0, selection, "classpath", allocation_protocol, {})
                checkpoint.assert_called_once_with(output)
            invalid = {"secondaryMetrics": {"gc.alloc.rate.norm": {"scoreUnit": "ops/s", "score": 1.0}}}
            with patch.object(remeasure.commands, "jmh_command", return_value=["jmh"]), \
                    patch.object(remeasure.commands, "execute"), \
                    patch.object(remeasure, "validate_timing", return_value=invalid), \
                    patch.object(remeasure, "checkpoint") as checkpoint:
                with self.assertRaises(ValueError):
                    remeasure.measure_allocation(
                        output, 0, selection, "classpath", allocation_protocol, {})
                checkpoint.assert_not_called()

    def test_completed_allocation_is_checkpointed_before_later_interruption(self):
        plan = remeasure.partition_plan("trino-final-line", 0, self.protocol)
        selections = list(enumerate(plan["selections"][:2]))
        allocation_protocol = {**self.protocol, "forks": 1, "warmup_iterations": 3,
                               "measurement_iterations": 3, "allocation_only": True}
        valid = {"secondaryMetrics": {"gc.alloc.rate.norm": {"scoreUnit": "B/op", "score": 1.0}}}
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(remeasure.commands, "jmh_command", return_value=["jmh"]), \
                patch.object(remeasure.commands, "execute", side_effect=[None, InterruptedError]), \
                patch.object(remeasure, "validate_timing", return_value=valid), \
                patch.object(remeasure, "checkpoint") as checkpoint:
            output = Path(temporary)
            with self.assertRaises(InterruptedError):
                remeasure.measure_allocations(
                    output, selections, "classpath", allocation_protocol, {})
            checkpoint.assert_called_once_with(output)

    def test_unvalidated_protocol_cannot_launch_collection(self):
        with self.assertRaisesRegex(ValueError, "independent pilot"):
            remeasure.partition_plan("trino-final-line", 0, {**self.protocol, "qualified_by_pilot": False})

    def test_exact_timing_contract_and_all_forks_are_required(self):
        plan = remeasure.partition_plan("trino-final-line", 0, self.protocol)
        selection = plan["selections"][0]
        command = remeasure.commands.jmh_command("classes", selection, selection["route"], "0,1", "1s",
                                                self.protocol, Path("/result"))
        row = {"jdkVersion": "25.0.4", "vmVersion": "25.0.4+7-LTS", "jmhVersion": "1.37", "benchmark": selection["benchmark"], "params": selection["parameters"],
               "jvmArgs": command[command.index("-jvmArgs") + 1].split(), "mode": "avgt", "threads": 1,
               "forks": 5, "warmupIterations": 20, "measurementIterations": 20,
               "warmupTime": "1 s", "measurementTime": "1 s",
               "primaryMetric": {"scoreUnit": "ns/op", "rawData": [[1.0] * 20 for _ in range(5)]}}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "jmh.json"
            path.write_text(json.dumps([row]))
            remeasure.validate_timing(path, selection, self.protocol)
            for key, value in (("threads", 2), ("warmupIterations", 1), ("jvmArgs", []),
                               ("secondaryMetrics", {"gc.alloc.rate.norm": {}})):
                invalid = copy.deepcopy(row)
                invalid[key] = value
                path.write_text(json.dumps([invalid]))
                with self.subTest(key=key), self.assertRaises(ValueError):
                    remeasure.validate_timing(path, selection, self.protocol)
            row["primaryMetric"]["rawData"].pop()
            path.write_text(json.dumps([row]))
            with self.assertRaises(ValueError):
                remeasure.validate_timing(path, selection, self.protocol)

    def test_native_control_requires_both_cases_and_all_repetitions(self):
        rows = [{"run_type": "iteration", "run_name": f"Search_Easy{case}_CachedDFA/262144/threads:1",
                 "time_unit": "ns", "cpu_time": 100.0, "real_time": 101.0} for case in (0, 2) for _ in range(5)]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "native.json"
            path.write_text(json.dumps({"benchmarks": rows}))
            remeasure.validate_native(path)
            for invalid_time in (None, 0, -1, float("nan"), float("inf")):
                invalid = copy.deepcopy(rows)
                invalid[0]["real_time"] = invalid_time
                path.write_text(json.dumps({"benchmarks": invalid}))
                with self.subTest(real_time=invalid_time), self.assertRaises(ValueError):
                    remeasure.validate_native(path)
            rows.pop()
            path.write_text(json.dumps({"benchmarks": rows}))
            with self.assertRaises(ValueError):
                remeasure.validate_native(path)


if __name__ == "__main__":
    unittest.main()
