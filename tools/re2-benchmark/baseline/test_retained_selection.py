import csv
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import remeasure


class TestRetainedSelection(unittest.TestCase):
    def setUp(self):
        self.protocol = json.loads(Path(__file__).with_name("remeasurement-retained-protocol.json").read_text())

    def test_exact_original_work_is_preserved_across_partitions(self):
        with (remeasure.BASELINE / "rows.tsv").open() as source:
            rows = list(csv.DictReader(source, delimiter="\t"))
        original = {row["row_id"]: row for row in rows}
        selected = set(self.protocol["selected_logical_rows"])
        expected = {(row["row_id"], route) for row in rows
                    if row["row_id"].rsplit("/", 1)[0] in selected
                    and not row["system"].startswith("native-re2-")
                    for route in (("object-row", "native-access") if row["system"] == "regulator"
                                  else ("object-row",) if row["system"] == "regulator-object-row"
                                  else ("native-access",))}
        observed = set()
        partitions = 0
        for shard in sorted({row["shard_id"] for row in rows if row["row_id"].rsplit("/", 1)[0] in selected}):
            count = len({row["row_id"].rsplit("/", 1)[0] for row in rows
                         if row["shard_id"] == shard and row["row_id"].rsplit("/", 1)[0] in selected})
            size = remeasure.retained_selection.timing_protocol(shard, self.protocol)["operations_per_partition"]
            for partition in range((count + size - 1) // size):
                plan = remeasure.partition_plan(shard, partition, self.protocol)
                partitions += 1
                for choice in plan["selections"]:
                    row = choice["original_row"]
                    self.assertEqual(row, original[row["row_id"]])
                    key = row["row_id"], choice["route"]
                    self.assertNotIn(key, observed)
                    observed.add(key)
                    if row["suite"] == "traditional":
                        self.assertEqual(choice["benchmark"], row["benchmark"])
                        self.assertIn(row["comparator"], plan["native_names"])
            with self.assertRaises(ValueError):
                remeasure.partition_plan(shard, (count + size - 1) // size, self.protocol)
        self.assertEqual(expected, observed)
        self.assertEqual(len(observed), 88)
        self.assertEqual(partitions, 20)

    def test_long_windows_are_frozen_and_partitioned_below_the_host_deadline(self):
        for partition in range(4):
            plan = remeasure.partition_plan("traditional-extra-alt-engines", partition, self.protocol)
            protocol = remeasure.timing_protocol(plan)
            self.assertEqual(len(plan["selections"]), 1)
            self.assertEqual(protocol["warmup_iterations"], 300)
            self.assertEqual(protocol["measurement_iterations"], 300)
            self.assertEqual(protocol["native_measurement_seconds"], 20)
            self.assertEqual(remeasure.commands.timing_timeout(protocol, "1s"), 3195)
        for shard in ("traditional-extra-big-fixed", "traditional-extra-fanout"):
            protocol = remeasure.timing_protocol(remeasure.partition_plan(shard, 0, self.protocol))
            self.assertEqual(protocol["warmup_iterations"], 60)
            self.assertEqual(protocol["measurement_iterations"], 60)
        ordinary = remeasure.timing_protocol(remeasure.partition_plan("traditional-capture", 0, self.protocol))
        self.assertEqual(ordinary["measurement_iterations"], 20)
        for override in ({"unknown": {}}, {"traditional-extra-alt-engines": {"measurement_iterations": 299}}):
            with self.subTest(override=override), self.assertRaises(ValueError):
                remeasure.partition_plan("traditional-search", 0, {**self.protocol, "shard_overrides": override})

    def test_like_lifecycle_and_optimized_comparators_are_distinct(self):
        for shard, suffix, count in (("like-compile", "Compile", 3), ("like-single-use", "SingleUse", 3), ("trino-like", "", 4)):
            plan = remeasure.partition_plan(shard, 0, self.protocol)
            self.assertEqual(len(plan["selections"]), count)
            self.assertEqual([s["benchmark"].split(".")[-1] for s in plan["selections"]],
                             ["candidate" + suffix, "candidate" + suffix, "trinoSql" + suffix] +
                             (["trinoOptimized"] if count == 4 else []))

    def test_changed_source_and_invalid_selection_fail_closed(self):
        selected = self.protocol["selected_logical_rows"]
        for key, value in (("selected_logical_rows", []), ("selected_logical_rows", selected + [selected[-1]]),
                           ("selected_logical_rows", ["unknown/row"]), ("operations_per_partition", 5),
                           ("forks", 1), ("warmup_iterations", 10), ("original_row_manifest_sha256", "0" * 64)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                remeasure.partition_plan("traditional-search", 0, {**self.protocol, key: value})
        for key in ("schema_version", "forks", "warmup_iterations", "measurement_iterations", "iteration_seconds"):
            for value in (True, float(self.protocol[key])):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    remeasure.partition_plan("traditional-search", 0, {**self.protocol, key: value})
        protocol = {**self.protocol, "qualified_by_pilot": False}
        with self.assertRaisesRegex(ValueError, "independent pilot"):
            remeasure.partition_plan("traditional-search", 0, protocol)

    def test_retained_qualification_and_easy2_decision_are_required(self):
        remeasure.partition_plan("traditional-search", 0, self.protocol)
        for key in ("pilot_qualification_sha256", "qualification_attestation_sha256",
                    "easy2_correction_decision_sha256", "easy2_correction_attestation_sha256"):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "qualification|easy2 correction"):
                remeasure.partition_plan("traditional-search", 0, {**self.protocol, key: "0" * 64})

    def test_traditional_classpath_loads_the_published_jar(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            receipt = directory / "routes/native-access/release-artifact.json"
            receipt.parent.mkdir(parents=True)
            receipt.write_text(json.dumps({"jar_path": "/old/release.jar", "classpath":
                [{"path": "/tests"}, {"path": "/old/release.jar"}, {"path": "/dependency.jar"}]}))
            with patch.dict("os.environ", {"BASELINE_SHARED_WORK_DIR": str(directory)}), \
                    patch.object(remeasure.commands.released_artifact, "prepare", return_value=(Path("/new/release.jar"), {})), \
                    patch.object(remeasure.commands.released_artifact, "attest") as attest:
                result = remeasure.classpath(directory, directory / "output", "traditional-search")
            self.assertEqual(result, "/tests:/new/release.jar:/dependency.jar")
            attest.assert_called_once()


if __name__ == "__main__":
    unittest.main()
