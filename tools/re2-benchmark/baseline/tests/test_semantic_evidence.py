import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


SCRIPT = Path(__file__).parents[1] / "semantic_evidence.py"
SPEC = importlib.util.spec_from_file_location("baseline_semantic_evidence", SCRIPT)
SEMANTIC_EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SEMANTIC_EVIDENCE)


class TestSemanticEvidence(unittest.TestCase):
    def test_builds_canonical_evidence_from_actual_test_reports(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            reports = root / "reports"
            reports.mkdir()
            (reports / "TEST-example.xml").write_text(
                '<testsuite><testcase classname="io.airlift.TestExample" name="testTwo"/>'
                '<testcase classname="io.airlift.TestExample" name="testOne"/></testsuite>')
            log = root / "semantic.log"
            log.write_text("noise\nVerified 80 exact comparisons\n")
            rebar = root / "rebar.csv"
            rebar.write_text("b,count,engine,1,OK\na,count,engine,1,OK\n")
            arguments = SimpleNamespace(
                reports=reports,
                expected_tests="TestExample",
                route="native-access",
                handler="jmh",
                native_access="true",
                semantic_log=log,
                rebar_verification=[rebar])

            rows = SEMANTIC_EVIDENCE.build_evidence(arguments)

            self.assertIn(("junit", "io.airlift.TestExample#testOne", "passed"), rows)
            self.assertIn(("verifier", "Verified 80 exact comparisons", "passed"), rows)
            self.assertEqual([row for row in rows if row[0] == "rebar"][0][1], "exact-system-verification")
            self.assertEqual(rows, sorted(rows))

    def testCombinesIndependentRebarVerificationInvocations(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            primary = root / "primary.csv"
            primary.write_text("a,count,regulator/re2,1,OK\n")
            joni = root / "joni.csv"
            joni.write_text("a,count,joni/trino,2,OK\n")

            evidence = SEMANTIC_EVIDENCE.rebar_evidence([primary, joni])

            self.assertEqual(1, len(evidence))
            self.assertEqual("exact-system-verification", evidence[0][1])

    def test_rejects_missing_requested_test_class(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            reports = root / "reports"
            reports.mkdir()
            (reports / "TEST-example.xml").write_text(
                '<testsuite><testcase classname="io.airlift.TestOther" name="test"/></testsuite>')
            with self.assertRaisesRegex(ValueError, "missing requested classes"):
                SEMANTIC_EVIDENCE.junit_evidence(reports, ["TestExample"])

    def test_rejects_failed_test_case(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            reports = Path(temporary_directory)
            (reports / "TEST-example.xml").write_text(
                '<testsuite><testcase classname="io.airlift.TestExample" name="test">'
                '<failure/></testcase></testsuite>')
            with self.assertRaisesRegex(ValueError, "was not successful"):
                SEMANTIC_EVIDENCE.junit_evidence(reports, ["TestExample"])


if __name__ == "__main__":
    unittest.main()
