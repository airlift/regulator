import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import collection
import worker


class TestWorkerProfiles(unittest.TestCase):
    def test_disabled_by_default(self):
        with patch.object(collection, "load", return_value={}), patch.object(worker.subprocess, "run") as run:
            worker.profile_partition(Path("unused"), Path("unused"), "java", "classes")
            run.assert_not_called()

    def test_separate_diagnostic_process_and_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            partition = root / "partition"
            partition.mkdir()
            collection.save(partition / "manifest.json", {"suite": "language-bulk", "diagnostic_profiles": True})
            case = {"model": "count-captures", "expected_result": 17,
                    "mappings": {"java": {"status": "identical", "input_file": "inputs/a.klv"}}}
            observations = [("case/java/native", case, "java", "java", "native")]
            with patch.object(collection, "observations", return_value=observations), patch.object(worker.subprocess, "run") as run:
                worker.profile_partition(partition, root / "results", "java", "classes")
                command = run.call_args.args[0]
                self.assertIn("--enable-native-access=ALL-UNNAMED", command)
                self.assertIn("io.airlift.regulator.LanguageBulkProfile", command)
                self.assertNotIn("org.openjdk.jmh.Main", command)
                self.assertEqual(run.call_args.kwargs["timeout"], 180)
            receipt = collection.load(root / "results/diagnostic-profiles/case/java/native/profile.json")
            self.assertEqual(receipt["kind"], "diagnostic-only")
            self.assertEqual(receipt["command"], command)
            self.assertFalse((root / "results/observations.json").exists())

    def test_skips_native_and_compile(self):
        manifest = {"suite": "language-bulk", "diagnostic_profiles": True}
        cases = [("native", {"model": "count"}, "re2", "native-re2", "safe"),
                 ("compile", {"model": "compile"}, "java", "java", "safe")]
        with patch.object(collection, "load", return_value=manifest), \
                patch.object(collection, "observations", return_value=cases), \
                patch.object(worker.subprocess, "run") as run:
            worker.profile_partition(Path("unused"), Path("unused"), "java", "classes")
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
