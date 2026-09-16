import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import collection
import focused_controls


class TestFocusedControls(unittest.TestCase):
    def descriptor(self):
        return {"id": "direct-nfa", "case_id": "case", "language": "java",
                "benchmark": "io.airlift.regulator.BenchmarkRe2SearchNfa.searchHardNfa",
                "parameters": {"textSize": "64"}}

    def test_scope_is_per_partition_and_language(self):
        descriptor = self.descriptor()
        manifest = {"cases": [{"id": "case"}], "selected_languages": ["java"], "focused_controls": [descriptor]}
        self.assertEqual(focused_controls.selected(manifest), [descriptor])
        manifest["selected_languages"] = ["re2"]
        self.assertEqual(focused_controls.selected(manifest), [])
        manifest["focused_controls"].append(descriptor)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            focused_controls.selected(manifest)

    def test_lifecycle_records_original_bytes_offsets_and_counts(self):
        specification = {"pattern_hex": "5c62785c62", "sources": [{"hex": "7878", "offset": 3, "count": 0}]}
        self.assertEqual(focused_controls.lifecycle_payload(specification), b"5c62785c62\n3\t0\t7878\n")
        specification["sources"][0]["hex"] = "ff"
        with self.assertRaises(UnicodeDecodeError):
            focused_controls.lifecycle_payload(specification)

    def test_complete_protocol_required(self):
        descriptor = self.descriptor()
        row = {"benchmark": descriptor["benchmark"], "params": descriptor["parameters"],
               "mode": "avgt", "forks": 5, "warmupIterations": 10, "measurementIterations": 10,
               "threads": 1, "warmupTime": "1 s", "measurementTime": "1 s",
               "primaryMetric": {"scoreUnit": "ns/op", "rawData": [[1.0] * 10 for _ in range(5)]},
               "secondaryMetrics": {"gc.alloc.rate.norm": {"scoreUnit": "B/op", "rawData": [[0.0] * 10 for _ in range(5)]}}}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "raw.json"
            collection.save(path, [row])
            self.assertEqual(focused_controls.validate_raw(path, descriptor, descriptor["parameters"]), row)
            invalid = copy.deepcopy(row)
            invalid["primaryMetric"]["rawData"].pop()
            collection.save(path, [invalid])
            with self.assertRaisesRegex(ValueError, "protocol"):
                focused_controls.validate_raw(path, descriptor, descriptor["parameters"])
            collection.save(path, [row, row])
            with self.assertRaisesRegex(ValueError, "exact"):
                focused_controls.validate_raw(path, descriptor, descriptor["parameters"])

    def test_no_controls_does_not_measure(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            collection.save(directory / "manifest.json", {"cases": [{"id": "case"}]})
            with patch.object(collection, "run_process") as run:
                focused_controls.run(directory, directory / "results", "java", "classes")
                run.assert_not_called()

    def test_downloaded_results_require_both_modes_and_unchanged_raw_data(self):
        descriptor = self.descriptor()
        with tempfile.TemporaryDirectory() as temporary:
            partition = Path(temporary) / "partition"
            results = Path(temporary) / "results"
            partition.mkdir()
            collection.save(partition / "manifest.json", {
                "cases": [{"id": "case"}], "selected_languages": ["java"], "focused_controls": [descriptor]})
            row = {"benchmark": descriptor["benchmark"], "params": descriptor["parameters"],
                   "mode": "avgt", "forks": 5, "warmupIterations": 10, "measurementIterations": 10,
                   "threads": 1, "warmupTime": "1 s", "measurementTime": "1 s",
                   "primaryMetric": {"scoreUnit": "ns/op", "rawData": [[1.0] * 10 for _ in range(5)]},
                   "secondaryMetrics": {"gc.alloc.rate.norm": {"scoreUnit": "B/op", "rawData": [[0.0] * 10 for _ in range(5)]}}}
            runners = {"classpath": [{"path": "classes"}]}
            for mode in collection.MODES:
                output = results / "focused-controls" / descriptor["id"] / mode
                output.mkdir(parents=True)
                collection.save(output / "raw.json", [row])
                collection.save(output / "receipt.json", {
                    "descriptor": descriptor, "mode": mode,
                    "manifest_sha256": collection.digest((partition / "manifest.json").read_bytes()),
                    "raw_sha256": collection.digest((output / "raw.json").read_bytes()),
                    "command": collection.jmh_command("java", "classes", mode)})
            self.assertEqual(len(focused_controls.validate_results(partition, results, runners)), 2)
            receipt = output / "receipt.json"
            original = receipt.read_bytes()
            receipt.unlink()
            with self.assertRaisesRegex(ValueError, "coverage"):
                focused_controls.validate_results(partition, results, runners)
            receipt.write_bytes(original)
            with self.assertRaisesRegex(ValueError, "source build"):
                focused_controls.validate_results(partition, results, {"classpath": [{"path": "other"}]})
            data = collection.load(receipt)
            jvm_arguments = data["command"].index("-jvmArgs") + 1
            data["command"][jvm_arguments] += " --enable-native-access=ALL-UNNAMED"
            collection.save(receipt, data)
            with self.assertRaisesRegex(ValueError, "memory mode"):
                focused_controls.validate_results(partition, results, runners)
            receipt.write_bytes(original)
            collection.save(output / "raw.json", [])
            with self.assertRaisesRegex(ValueError, "raw timing"):
                focused_controls.validate_results(partition, results, runners)


if __name__ == "__main__":
    unittest.main()
