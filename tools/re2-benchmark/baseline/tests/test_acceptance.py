#!/usr/bin/env python3

import csv
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE_DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASELINE_DIRECTORY))

from acceptance import ValidationError, load_platforms  # noqa: E402


class TestAcceptance(unittest.TestCase):
    def test_platform_file_requires_and_validates_both_worker_topologies(self):
        with tempfile.TemporaryDirectory() as directory:
            platforms = Path(directory) / "platforms.tsv"
            rows = [
                {"platform": platform, "architecture": architecture, "instance_type": f"{platform}.large",
                 "vcpus": "2", "concurrency_instance_type": f"{platform}.2xlarge", "concurrency_vcpus": "8"}
                for platform, architecture in (("r8i", "intel"), ("r9g", "arm"))]
            write_dict_tsv(platforms, rows)
            load_platforms(platforms)
            for field, value in (
                    ("concurrency_vcpus", ""),
                    ("instance_type", "r8i.2xlarge"),
                    ("concurrency_instance_type", "r8i.large"),
                    ("architecture", "arm"),
                    ("vcpus", "8")):
                changed = [dict(row) for row in rows]
                changed[0][field] = value
                write_dict_tsv(platforms, changed)
                with self.subTest(field=field), self.assertRaises(ValidationError):
                    load_platforms(platforms)
            # The 1.0 release's R8g workers are no longer a campaign platform.
            write_dict_tsv(platforms, [*rows, {**rows[1], "platform": "r8g", "instance_type": "r8g.large",
                                               "concurrency_instance_type": "r8g.2xlarge"}])
            with self.assertRaisesRegex(ValidationError, "platform identities"):
                load_platforms(platforms)


def write_dict_tsv(path, rows):
    with path.open("w", newline="") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=rows[0].keys(), delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    unittest.main()
