import csv
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
SCRIPT = BASELINE / "validate-rebar-shard-verification.py"
SPECIFICATION = importlib.util.spec_from_file_location("validate_rebar_shard_verification", SCRIPT)
VALIDATOR = importlib.util.module_from_spec(SPECIFICATION)
sys.modules["validate_rebar_shard_verification"] = VALIDATOR
SPECIFICATION.loader.exec_module(VALIDATOR)


class TestRebarShardVerification(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.manifest = self.root / "rows.tsv"
        self.verification = self.root / "verification.csv"
        self.outcomes = self.root / "outcomes.tsv"

    def tearDown(self):
        self.temporary_directory.cleanup()

    def testAcceptsSuccessfulCuratedAndExtendedRows(self):
        self.write_manifest()
        self.write_verification()

        VALIDATOR.validate(
            self.manifest,
            "rebar-a",
            {"regulator-native-access", "native-re2-before"},
            self.verification,
            self.outcomes)

        self.assertEqual([], self.read_outcomes())

    def testAcceptsJoniEngineRows(self):
        rows = [self.manifest_row(
            "curated/joni",
            "rebar-curated",
            "joni",
            "curated/workload")]
        with self.manifest.open("w", newline="", encoding="utf-8") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=tuple(rows[0]), delimiter="\t")
            writer.writeheader()
            writer.writerows(rows)
        with self.verification.open("w", newline="", encoding="utf-8") as output_file:
            csv.writer(output_file).writerow([
                "curated/workload",
                "count",
                "joni/trino",
                "Trino Joni 2.1.5.3",
                "OK",
            ])

        VALIDATOR.validate(
            self.manifest,
            "rebar-a",
            {"joni"},
            self.verification,
            self.outcomes)

        self.assertEqual([], self.read_outcomes())

    def testAppendsIndependentJoniOutcomes(self):
        rows = [
            self.manifest_row(
                "extended/regulator-native-access",
                "rebar-extended",
                "regulator-native-access",
                "extended/workload"),
            self.manifest_row(
                "extended/joni",
                "rebar-extended",
                "joni",
                "extended/workload"),
        ]
        with self.manifest.open("w", newline="", encoding="utf-8") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=tuple(rows[0]), delimiter="\t")
            writer.writeheader()
            writer.writerows(rows)
        with self.verification.open("w", newline="", encoding="utf-8") as output_file:
            csv.writer(output_file).writerow([
                "extended/workload", "count", "regulator/re2", "candidate",
                "count mismatch, expected 1, got 0",
            ])
        VALIDATOR.validate(
            self.manifest,
            "rebar-a",
            {"regulator-native-access"},
            self.verification,
            self.outcomes,
            command_status=1)

        with self.verification.open("w", newline="", encoding="utf-8") as output_file:
            csv.writer(output_file).writerow([
                "extended/workload", "count", "joni/trino", "Joni",
                "count mismatch, expected 1, got 0",
            ])
        VALIDATOR.validate(
            self.manifest,
            "rebar-a",
            {"joni"},
            self.verification,
            self.outcomes,
            command_status=1,
            append=True)

        self.assertEqual(
            {"extended/regulator-native-access", "extended/joni"},
            {row["row_id"] for row in self.read_outcomes()})

    def testRejectsCuratedMismatch(self):
        self.write_manifest()
        self.write_verification(curated_status="count mismatch, expected 1, got 0")

        with self.assertRaisesRegex(ValueError, "Curated Rebar verification failed"):
            VALIDATOR.validate(
                self.manifest,
                "rebar-a",
                {"regulator-native-access", "native-re2-before"},
                self.verification,
                self.outcomes)

    def testRejectsUnclassifiedExtendedFailure(self):
        self.write_manifest()
        self.write_verification(extended_status="benchmark timed out")

        with self.assertRaisesRegex(ValueError, "Unclassified extended Rebar verification failure"):
            VALIDATOR.validate(
                self.manifest,
                "rebar-a",
                {"regulator-native-access", "native-re2-before"},
                self.verification,
                self.outcomes,
                command_status=1)

    def testRecordsJoniTimeoutAsExecutionOutcome(self):
        rows = [self.manifest_row(
            "curated/joni",
            "rebar-curated",
            "joni",
            "curated/workload")]
        with self.manifest.open("w", newline="", encoding="utf-8") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=tuple(rows[0]), delimiter="\t")
            writer.writeheader()
            writer.writerows(rows)
        with self.verification.open("w", newline="", encoding="utf-8") as output_file:
            csv.writer(output_file).writerow([
                "curated/workload", "count", "joni/trino", "Joni",
                "timeout: exceeded 30s",
            ])

        VALIDATOR.validate(
            self.manifest,
            "rebar-a",
            {"joni"},
            self.verification,
            self.outcomes,
            command_status=1)

        self.assertEqual("did-not-finish", self.read_outcomes()[0]["outcome"])

    def testAppendChecksOnlyCurrentInvocationStatus(self):
        rows = [
            self.manifest_row(
                "extended/regulator-native-access",
                "rebar-extended",
                "regulator-native-access",
                "extended/workload"),
            self.manifest_row(
                "extended/joni",
                "rebar-extended",
                "joni",
                "extended/workload"),
        ]
        with self.manifest.open("w", newline="", encoding="utf-8") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=tuple(rows[0]), delimiter="\t")
            writer.writeheader()
            writer.writerows(rows)
        with self.verification.open("w", newline="", encoding="utf-8") as output_file:
            csv.writer(output_file).writerow([
                "extended/workload", "count", "regulator/re2", "candidate",
                "count mismatch, expected 1, got 0",
            ])
        VALIDATOR.validate(
            self.manifest,
            "rebar-a",
            {"regulator-native-access"},
            self.verification,
            self.outcomes,
            command_status=1)

        with self.verification.open("w", newline="", encoding="utf-8") as output_file:
            csv.writer(output_file).writerow([
                "extended/workload", "count", "joni/trino", "Joni", "OK",
            ])
        VALIDATOR.validate(
            self.manifest,
            "rebar-a",
            {"joni"},
            self.verification,
            self.outcomes,
            append=True)

        self.assertEqual(
            ["extended/regulator-native-access"],
            [row["row_id"] for row in self.read_outcomes()])

    def testSemanticTimeoutDoesNotExcuseProcessFailure(self):
        self.testRecordsJoniTimeoutAsExecutionOutcome()
        original = self.outcomes.read_bytes()
        for command_status in (2, 137, 143, -9):
            with self.subTest(command_status=command_status):
                with self.assertRaisesRegex(ValueError, "without a classified outcome"):
                    VALIDATOR.validate(self.manifest, "rebar-a", {"joni"}, self.verification,
                                       self.outcomes, command_status=command_status)
                self.assertEqual(original, self.outcomes.read_bytes())

    def testRecordsExtendedMismatch(self):
        self.write_manifest()
        self.write_verification(extended_status="count mismatch, expected 55, got 0")

        VALIDATOR.validate(
            self.manifest,
            "rebar-a",
            {"regulator-native-access", "native-re2-before"},
            self.verification,
            self.outcomes,
            command_status=1)

        self.assertEqual([
            {
                "row_id": "extended/regulator-native-access",
                "suite": "rebar-extended",
                "system": "regulator-native-access",
                "benchmark": "extended/workload",
                "model": "count",
                "outcome": "semantic-mismatch",
                "detail": "count mismatch, expected 55, got 0",
            },
        ], self.read_outcomes())

    def testRejectsUnexpectedNonzeroCommandStatus(self):
        self.write_manifest()
        self.write_verification()

        with self.assertRaisesRegex(ValueError, "without a classified outcome"):
            VALIDATOR.validate(
                self.manifest,
                "rebar-a",
                {"regulator-native-access", "native-re2-before"},
                self.verification,
                self.outcomes,
                command_status=1)

    def testRejectsMismatchWithSuccessfulCommandStatus(self):
        self.write_manifest()
        self.write_verification(extended_status="count mismatch, expected 55, got 0")

        with self.assertRaisesRegex(ValueError, "exited successfully"):
            VALIDATOR.validate(
                self.manifest,
                "rebar-a",
                {"regulator-native-access", "native-re2-before"},
                self.verification,
                self.outcomes)

    def testRejectsMissingVerificationRow(self):
        self.write_manifest()
        self.write_verification(include_extended_native=False)

        with self.assertRaisesRegex(ValueError, "differs from rows.tsv"):
            VALIDATOR.validate(
                self.manifest,
                "rebar-a",
                {"regulator-native-access", "native-re2-before"},
                self.verification,
                self.outcomes)

    def testMeasurementTimeoutAfterSuccessfulVerification(self):
        self.testAcceptsJoniEngineRows()
        self.write_measurements(["timeout: exceeded 120s"])
        self.validate_measurements()
        self.assertEqual("did-not-finish", self.read_outcomes()[0]["outcome"])

    def testMeasurementRejectsIncompleteUnknownAndFailedInvocations(self):
        for statuses, command_status, message in (
                ([], 0, "differs from rows.tsv"),
                (["", ""], 0, "Duplicate"),
                (["count mismatch, expected 1, got 0"], 0, "verification failed"),
                (["process crashed"], 0, "verification failed"),
                (["timeout: exceeded 120s"], 1, "without a classified outcome"),
                ([""], 1, "without a classified outcome")):
            with self.subTest(statuses=statuses, command_status=command_status):
                self.testAcceptsJoniEngineRows()
                self.write_measurements(statuses)
                with self.assertRaisesRegex(ValueError, message):
                    self.validate_measurements(command_status)
                self.assertEqual([], self.read_outcomes())

    def write_measurements(self, statuses):
        with self.verification.open("w", newline="", encoding="utf-8") as output_file:
            writer = csv.writer(output_file)
            writer.writerow(["name", "model", "engine", "engine_version", "err", "median"])
            for status in statuses:
                writer.writerow(["curated/workload", "count", "joni/trino", "Joni", status, "" if status else "100ns"])

    def validate_measurements(self, command_status=0):
        VALIDATOR.validate(self.manifest, "rebar-a", {"joni"}, self.verification,
                           self.outcomes, command_status, append=True, measurement=True)

    def write_manifest(self):
        rows = [
            self.manifest_row(
                "curated/regulator-native-access",
                "rebar-curated",
                "regulator-native-access",
                "curated/workload"),
            self.manifest_row(
                "curated/native-re2-before",
                "rebar-curated",
                "native-re2-before",
                "curated/workload"),
            self.manifest_row(
                "extended/regulator-native-access",
                "rebar-extended",
                "regulator-native-access",
                "extended/workload"),
            self.manifest_row(
                "extended/native-re2-before",
                "rebar-extended",
                "native-re2-before",
                "extended/workload"),
        ]
        with self.manifest.open("w", newline="", encoding="utf-8") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=tuple(rows[0]), delimiter="\t")
            writer.writeheader()
            writer.writerows(rows)

    @staticmethod
    def manifest_row(row_id, suite, system, benchmark):
        return {
            "row_id": row_id,
            "shard_id": "rebar-a",
            "suite": suite,
            "system": system,
            "benchmark": benchmark,
            "parameters": "model=count;workload=example",
            "comparator": "native",
            "expected_result": "differential",
            "allocation_contract": "not-measured",
            "workload_checksum": "checksum",
        }

    def write_verification(
            self,
            curated_status="OK",
            extended_status="OK",
            include_extended_native=True):
        rows = [
            ["curated/workload", "count", "regulator/re2", "candidate", curated_status],
            ["curated/workload", "count", "re2/pinned-host-tuned-before", "native", "OK"],
            ["extended/workload", "count", "regulator/re2", "candidate", extended_status],
        ]
        if include_extended_native:
            rows.append([
                "extended/workload",
                "count",
                "re2/pinned-host-tuned-before",
                "native",
                "OK",
            ])
        with self.verification.open("w", newline="", encoding="utf-8") as output_file:
            csv.writer(output_file).writerows(rows)

    def read_outcomes(self):
        with self.outcomes.open(newline="", encoding="utf-8") as input_file:
            return list(csv.DictReader(input_file, delimiter="\t"))


if __name__ == "__main__":
    unittest.main()
