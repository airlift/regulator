import csv
import hashlib
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
SCRIPT = BASELINE / "rebar_joni_applicability.py"
SPECIFICATION = importlib.util.spec_from_file_location("rebar_joni_applicability", SCRIPT)
APPLICABILITY = importlib.util.module_from_spec(SPECIFICATION)
sys.modules["rebar_joni_applicability"] = APPLICABILITY
SPECIFICATION.loader.exec_module(APPLICABILITY)


class TestRebarJoniApplicability(unittest.TestCase):
    def testCurrentApplicabilityCoversEveryWorkload(self):
        root = BASELINE.parents[2]

        rows = APPLICABILITY.load_applicability(root)

        self.assertEqual(238, len(rows))
        self.assertEqual(225, sum(row["status"] == "compatible" for row in rows.values()))
        self.assertEqual(13, sum(row["status"] == "not-compatible" for row in rows.values()))

    def testLoadsExhaustiveCheckedApplicability(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self.write_workloads(root)
            self.write_applicability(root, [
                ("curated/example", "count", "compatible", "-"),
                ("extended/unsupported", "grep", "not-compatible", "unsupported syntax"),
            ])

            rows = APPLICABILITY.load_applicability(root)

        self.assertEqual("compatible", rows[("curated/example", "count")]["status"])
        self.assertEqual(
            "not-compatible", rows[("extended/unsupported", "grep")]["status"])

    def testRejectsIncompleteApplicability(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self.write_workloads(root)
            self.write_applicability(root, [
                ("curated/example", "count", "compatible", "-"),
            ])

            with self.assertRaisesRegex(ValueError, "differs from Rebar workloads"):
                APPLICABILITY.load_applicability(root)

    def testRejectsInvalidStatusAndReasonCombinations(self):
        cases = (
            ("unknown", "-", "unsupported.*status"),
            ("compatible", "unnecessary", "compatible Joni row has a reason"),
            ("did-not-finish", "timeout", "unsupported.*status"),
        )
        for status, reason, message in cases:
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary_directory:
                root = Path(temporary_directory)
                self.write_workloads(root, include_extended=False)
                self.write_applicability(root, [
                    ("curated/example", "count", status, reason),
                ])
                with self.assertRaisesRegex(ValueError, message):
                    APPLICABILITY.load_applicability(root)

    def testRejectsChecksumMismatch(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self.write_workloads(root, include_extended=False)
            self.write_applicability(root, [
                ("curated/example", "count", "compatible", "-"),
            ])
            source = root / "tools/re2-benchmark/manifests/rebar-joni-applicability.tsv"
            source.write_text(source.read_text() + "changed")

            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                APPLICABILITY.load_applicability(root)

    @staticmethod
    def write_workloads(root, include_extended=True):
        manifests = root / "tools/re2-benchmark/manifests"
        manifests.mkdir(parents=True)
        with (manifests / "rebar-workloads.csv").open("w", newline="") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=("name", "model"))
            writer.writeheader()
            writer.writerow({"name": "curated/example", "model": "count"})
        with (manifests / "rebar-extended-workloads.tsv").open("w", newline="") as output_file:
            writer = csv.DictWriter(
                output_file, fieldnames=("name", "model"), delimiter="\t")
            writer.writeheader()
            if include_extended:
                writer.writerow({"name": "extended/unsupported", "model": "grep"})

    @staticmethod
    def write_applicability(root, rows):
        manifests = root / "tools/re2-benchmark/manifests"
        source = manifests / "rebar-joni-applicability.tsv"
        with source.open("w", newline="") as output_file:
            writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
            writer.writerow(APPLICABILITY.FIELDS)
            writer.writerows(rows)
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        (manifests / "rebar-joni-applicability.sha256").write_text(
            f"{digest}  {source.name}\n")


if __name__ == "__main__":
    unittest.main()
