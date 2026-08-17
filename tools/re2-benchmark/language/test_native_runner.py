import os
from pathlib import Path
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.environ.get("LANGUAGE_NATIVE_RUNNER"), "build and set LANGUAGE_NATIVE_RUNNER for native checks")
class TestNativeRunner(unittest.TestCase):
    def run_case(self, pattern, source, count, offset=3, operation=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.tsv"
            path.write_text(pattern.encode().hex() + f"\n{offset}\t{count}\t" + source.encode().hex() + "\n")
            command = [os.environ["LANGUAGE_NATIVE_RUNNER"], "--verify", str(path)] if operation is None else [
                os.environ["LANGUAGE_NATIVE_RUNNER"], "--verify-operation", str(path), operation]
            return subprocess.run(command,
                                  capture_output=True, text=True, timeout=10)

    def test_empty_matches_advance_and_terminate(self):
        result = self.run_case("a*", "ab", 3)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "3:61,,\n")
        result = self.run_case("a*", "", 1)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "1:\n")

    def test_unicode_progress_is_not_byte_progress(self):
        result = self.run_case("a*", "💰é", 3)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "3:,,\n")

    def test_dotall_and_nonzero_offset_preserve_newlines(self):
        source = "a\n💰z"
        result = self.run_case("(?s)a.*z", source, 1)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "1:" + source.encode().hex() + "\n")

    def test_wrong_expected_result_fails(self):
        result = self.run_case("a", "a", 0)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("verification failed", result.stderr)

    def test_each_operation_has_its_own_checked_result(self):
        for operation in ("compile", "singleUseContains", "singleUseCount", "reusedContains", "reusedCount"):
            with self.subTest(operation=operation):
                result = self.run_case("a*", "ab", 3, operation=operation)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "compiled\n" if operation == "compile" else "1\n" if operation.endswith("Contains") else "3\n")

    def test_compile_does_not_execute_other_operations(self):
        result = self.run_case("a", "a", 0, operation="compile")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "compiled\n")
        result = self.run_case("a", "a", 0, operation="reusedCount")
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
