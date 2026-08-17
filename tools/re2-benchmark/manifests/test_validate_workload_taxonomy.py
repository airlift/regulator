import csv
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MANIFESTS = Path(__file__).resolve().parent
SPECIFICATION = importlib.util.spec_from_file_location(
    "validate_workload_taxonomy",
    MANIFESTS / "validate-workload-taxonomy.py")
VALIDATOR = importlib.util.module_from_spec(SPECIFICATION)
sys.modules["validate_workload_taxonomy"] = VALIDATOR
SPECIFICATION.loader.exec_module(VALIDATOR)


class TestValidateWorkloadTaxonomy(unittest.TestCase):
    def testCurrentCorpusIsClassifiedExactlyOnce(self):
        counts, classified = VALIDATOR.validate()

        self.assertEqual(238, len(classified))
        self.assertEqual({
            "bulk-text": 117,
            "compilation": 25,
            "diagnostics-and-stress": 96,
        }, dict(counts))
        populations = {
            (workload["name"], workload["model"]): rule["population"]
            for workload, rule in classified
        }
        self.assertEqual(
            "diagnostics-and-stress",
            populations[("curated/06-cloud-flare-redos/original", "count-spans")])
        self.assertEqual(
            "diagnostics-and-stress",
            populations[("curated/14-quadratic/10x", "count")])
        self.assertEqual(
            "bulk-text",
            populations[("curated/01-literal/sherlock-en", "count")])
        self.assertEqual(
            "compilation",
            populations[("curated/03-date/compile-ascii", "compile")])

    def testRejectsUnclassifiedWorkload(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self.write_csv(root / "rebar-workloads.csv", [{"name": "new/source", "model": "count"}])
            self.write_tsv(root / "rebar-extended-workloads.tsv", [], ("name", "model"))
            self.write_tsv(
                root / "rebar-workload-taxonomy.tsv",
                [{
                    "name_regex": "^known/",
                    "model_regex": ".*",
                    "population": "bulk-text",
                    "family": "known",
                    "reason": "known source",
                }],
                VALIDATOR.TAXONOMY_FIELDS)

            with self.assertRaisesRegex(ValueError, "matched 0 taxonomy rules"):
                VALIDATOR.validate(root)

    def testRejectsOverlappingRules(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self.write_csv(root / "rebar-workloads.csv", [{"name": "known/source", "model": "count"}])
            self.write_tsv(root / "rebar-extended-workloads.tsv", [], ("name", "model"))
            rules = [
                {
                    "name_regex": ".*",
                    "model_regex": ".*",
                    "population": "bulk-text",
                    "family": "all",
                    "reason": "catch all",
                },
                {
                    "name_regex": "^known/.*",
                    "model_regex": ".*",
                    "population": "diagnostics-and-stress",
                    "family": "known",
                    "reason": "specific",
                },
            ]
            self.write_tsv(root / "rebar-workload-taxonomy.tsv", rules, VALIDATOR.TAXONOMY_FIELDS)

            with self.assertRaisesRegex(ValueError, "matched 2 taxonomy rules"):
                VALIDATOR.validate(root)

    def testRejectsChecksumMismatch(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self.write_csv(root / "rebar-workloads.csv", [{"name": "known/source", "model": "count"}])
            self.write_tsv(root / "rebar-extended-workloads.tsv", [], ("name", "model"))
            self.write_tsv(
                root / "rebar-workload-taxonomy.tsv",
                [{
                    "name_regex": ".*",
                    "model_regex": ".*",
                    "population": "bulk-text",
                    "family": "all",
                    "reason": "catch all",
                }],
                VALIDATOR.TAXONOMY_FIELDS)
            (root / "rebar-workload-taxonomy.sha256").write_text(
                "0" * 64 + "  rebar-workload-taxonomy.tsv\n",
                encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                VALIDATOR.validate(root)

    @staticmethod
    def write_csv(path, rows):
        with path.open("w", newline="", encoding="utf-8") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=tuple(rows[0]))
            writer.writeheader()
            writer.writerows(rows)

    @staticmethod
    def write_tsv(path, rows, fieldnames):
        with path.open("w", newline="", encoding="utf-8") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=fieldnames, delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)


if __name__ == "__main__":
    unittest.main()
