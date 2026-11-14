import os
from pathlib import Path
import subprocess
import tempfile
import unittest


def run_case(runner, pattern, source, count, offset=3, operation=None, mode="--verify-operation"):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "input.tsv"
        path.write_text(pattern.encode().hex() + f"\n{offset}\t{count}\t" + source.encode().hex() + "\n")
        command = [runner, "--verify", str(path)] if operation is None else [
            runner, mode, str(path), *operation.split()]
        return subprocess.run(command,
                              capture_output=True, text=True, timeout=10)


class NativeRunnerChecks:
    """Each native comparator runs the same lifecycle checks through the shared harness."""

    RUNNER = None
    # Matches of a* in "ab": RE2 also reports the empty match where "a" ended; Rust skips it.
    EMPTY_AFTER_MATCH = None
    # Whether the runner has the specialized replace operation.
    REPLACES = None

    def run_case(self, *arguments, **options):
        return run_case(os.environ[self.RUNNER], *arguments, **options)

    def test_empty_matches_advance_and_terminate(self):
        count, trace = self.EMPTY_AFTER_MATCH
        result = self.run_case("a*", "ab", count)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, trace)
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

    def test_no_match_is_not_contained(self):
        result = self.run_case("a", "b", 0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "0:\n")

    def test_wrong_expected_result_fails(self):
        result = self.run_case("a", "a", 0)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("verification failed", result.stderr)

    def test_each_operation_has_its_own_checked_result(self):
        count, _ = self.EMPTY_AFTER_MATCH
        # The first match spans bytes 0 to 1, packed as 0 << 32 | 1.
        expected = {"compile": "compiled\n", "singleUseContains": "1\n", "singleUseCount": f"{count}\n",
                    "reusedContains": "1\n", "reusedCount": f"{count}\n", "reusedFind": "1\n"}
        for operation, trace in expected.items():
            with self.subTest(operation=operation):
                result = self.run_case("a*", "ab", count, operation=operation)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, trace)

    def test_first_match_bounds_are_utf8_offsets(self):
        result = self.run_case("é|💰", "x💰é", 2, operation="reusedFind")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, f"{1 << 32 | 5}\n")
        result = self.run_case("a", "b", 0, operation="reusedFind")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "-1\n")

    def test_compile_does_not_execute_other_operations(self):
        result = self.run_case("a", "a", 0, operation="compile")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "compiled\n")
        result = self.run_case("a", "a", 0, operation="reusedCount")
        self.assertNotEqual(result.returncode, 0)

    def test_specialized_operations_print_each_value(self):
        # The second match has no "a", so its first group does not participate.
        expected = {"extract 1": "1:61\n", "extract 2": "1:62\n", "extractAll 1": "2:61,-\n",
                    "extractAll 2": "2:62,62\n", "replace 2": "1:" + "b b q".encode().hex() + "\n"}
        for operation, values in expected.items():
            with self.subTest(operation=operation):
                result = self.run_case("x(a)?(b)", "xab xb q", 0, operation=operation, mode="--verify-specialized")
                if operation.startswith("replace") and not self.REPLACES:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("unknown specialized operation", result.stderr)
                    continue
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, values)
        result = self.run_case("x(a)?(b)", "q", 0, operation="extract 1", mode="--verify-specialized")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "0:\n")


@unittest.skipUnless(os.environ.get("LANGUAGE_NATIVE_RUNNER"), "build and set LANGUAGE_NATIVE_RUNNER for native checks")
class TestNativeRunner(NativeRunnerChecks, unittest.TestCase):
    RUNNER = "LANGUAGE_NATIVE_RUNNER"
    EMPTY_AFTER_MATCH = (3, "3:61,,\n")
    REPLACES = True


@unittest.skipUnless(os.environ.get("LANGUAGE_NATIVE_RUST_RUNNER"),
                     "build and set LANGUAGE_NATIVE_RUST_RUNNER for native Rust checks")
class TestNativeRustRunner(NativeRunnerChecks, unittest.TestCase):
    RUNNER = "LANGUAGE_NATIVE_RUST_RUNNER"
    EMPTY_AFTER_MATCH = (2, "2:61,\n")
    REPLACES = False


@unittest.skipUnless(os.environ.get("LANGUAGE_NATIVE_HARNESS_TEST_RUNNER"),
                     "build and set LANGUAGE_NATIVE_HARNESS_TEST_RUNNER for the harness's own checks")
class TestNativeHarness(unittest.TestCase):
    """The harness over a stand-in engine whose find moves a match's end once an input comes round again."""

    def test_find_must_repeat_its_bounds_after_the_rotation_wraps(self):
        runner = os.environ["LANGUAGE_NATIVE_HARNESS_TEST_RUNNER"]
        result = run_case(runner, "a", "ba", 1, operation="reusedCount")
        self.assertEqual(result.returncode, 0, result.stderr)
        # The first pass is right and still matches on the second, so only comparing the passes rejects it.
        result = run_case(runner, "a", "ba", 1, operation="reusedFind")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("verification failed", result.stderr)


if __name__ == "__main__":
    unittest.main()
