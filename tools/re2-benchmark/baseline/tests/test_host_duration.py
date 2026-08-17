import csv
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import sys


BASELINE = Path(__file__).parents[1]
sys.path.insert(0, str(BASELINE))
import host_duration  # noqa: E402


class TestHostDuration(unittest.TestCase):
    def test_lifecycle_shards_fit_both_memory_modes_on_one_host(self):
        with (BASELINE / "shards.tsv").open() as source:
            shards = [row["shard_id"] for row in csv.DictReader(source, delimiter="\t")
                      if row["shard_id"].startswith("lifecycle")]
        self.assertTrue(shards)
        for shard in shards:
            with self.subTest(shard=shard), tempfile.TemporaryDirectory() as directory:
                metadata = []
                for route in ("native-access", "object-row"):
                    destination = Path(directory) / route
                    subprocess.run([str(BASELINE / "run-shard.sh"), shard, "qualification", route, str(destination)],
                                   env={**os.environ, "BASELINE_PLAN_ONLY": "true", "BASELINE_DEFER_ACCEPTANCE": "true"},
                                   check=True, capture_output=True, text=True, timeout=30)
                    metadata.append(destination / "run-metadata.txt")
                _, _, _, host_seconds = host_duration.estimate(metadata, 600)
                self.assertLessEqual(host_seconds, 5400)

    def write_metadata(self, root, route, seconds, shared):
        path = root / f"{route}.txt"
        path.write_text(
            f"route={route}\n"
            f"route_static_duration_estimate_seconds={seconds}\n"
            f"native_build_startup_allowance_seconds=0\n"
            f"rebar_build_startup_allowance_seconds={shared}\n"
            f"pinned_trino_preparation_allowance_seconds=0\n")
        return path

    def test_shared_cold_allowance_is_charged_once(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            native = self.write_metadata(root, "native-access", 3000, 900)
            object_row = self.write_metadata(root, "object-row", 2000, 900)
            routes, route_total, shared_allowance, host_total = host_duration.estimate(
                [native, object_row], 600)
            self.assertEqual([route for route, _, _, _ in routes], ["native-access", "object-row"])
            self.assertEqual(route_total, 5000)
            self.assertEqual(shared_allowance, 900)
            self.assertEqual(host_total, 4700)

    def test_independent_allowances_are_each_retained(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            native = self.write_metadata(root, "native-access", 1000, 900)
            object_row = self.write_metadata(root, "object-row", 800, 0)
            _, _, shared_allowance, host_total = host_duration.estimate([native, object_row], 600)
            self.assertEqual(shared_allowance, 900)
            self.assertEqual(host_total, 2400)


if __name__ == "__main__":
    unittest.main()
