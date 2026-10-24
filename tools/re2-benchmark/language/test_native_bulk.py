import os
from pathlib import Path
import subprocess
import tempfile
import unittest


def run_bulk(runner, action, model, pattern, source, unicode=True, case_insensitive=False):
    fields = {"name": "test", "model": model, "pattern": pattern, "haystack": source,
              "unicode": str(unicode).lower(), "case-insensitive": str(case_insensitive).lower()}
    payload = b""
    for key, value in fields.items():
        value = value.encode() if isinstance(value, str) else value
        payload += key.encode() + b":" + str(len(value)).encode() + b":" + value + b"\n"
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "input.klv"
        path.write_bytes(payload)
        return subprocess.run([runner, "--" + action, str(path)], capture_output=True, text=True, timeout=10)


class NativeBulkChecks:
    """Each native comparator runs the same bulk checks through the shared harness."""

    RUNNER = None
    # Matches of a* in "ab": RE2 also reports the empty match where "a" ended; Rust skips it.
    EMPTY_AFTER_MATCH = None

    def check(self, model, pattern, source, expected, trace, unicode=True, case_insensitive=False):
        for action, output in (("execute", str(expected) + "\n"), ("trace", trace)):
            result = run_bulk(os.environ[self.RUNNER], action, model, pattern, source, unicode, case_insensitive)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, output)

    def test_all_models(self):
        self.check("count", "a+", "a baaa", 2, "source\n0:1:61;\n3:6:616161;\n")
        self.check("count-spans", "é|💰", "x💰é", 6, "source\n1:5:f09f92b0;\n5:7:c3a9;\n")
        self.check("count-captures", "(a)(b)?()", "a ab", 7, "source\n0:1:61;0:1:61;-;1:1:;\n2:4:6162;2:3:61;3:4:62;4:4:;\n")
        self.check("grep", "^a$", "a\r\nb\na\r", 2, "source\n1\nsource\n0\nsource\n1\n")
        self.check("grep-captures", "(a)(b)?()", "a\r\nab\n", 7,
                   "source\n0:1:61;0:1:61;-;1:1:;\nsource\n0:2:6162;0:1:61;1:2:62;2:2:;\n")
        self.check("compile", "a+", "a", "compiled", "compiled\n")

    def test_empty_match_advancement(self):
        self.check("count", "a*", "ab", *self.EMPTY_AFTER_MATCH)
        self.check("count", "a*", "💰é", 3, "source\n0:0:;\n4:4:;\n6:6:;\n")
        self.check("count", "a*", "", 1, "source\n0:0:;\n")

    def test_ascii_classes_and_empty_lines(self):
        self.check("count", r"\w+", "éa b", 2, "source\n2:3:61;\n4:5:62;\n", unicode=False)
        self.check("grep", ".", "", 0, "")
        self.check("grep", "^$", "\n\n", 2, "source\n1\nsource\n1\n")

    def test_case_insensitive_flag(self):
        self.check("count", "a", "aA", 1, "source\n0:1:61;\n")
        self.check("count", "a", "aA", 2, "source\n0:1:61;\n1:2:41;\n", case_insensitive=True)


@unittest.skipUnless(os.environ.get("LANGUAGE_NATIVE_BULK_RUNNER"),
                     "build and set LANGUAGE_NATIVE_BULK_RUNNER for native bulk checks")
class TestNativeBulk(NativeBulkChecks, unittest.TestCase):
    RUNNER = "LANGUAGE_NATIVE_BULK_RUNNER"
    EMPTY_AFTER_MATCH = (3, "source\n0:1:61;\n1:1:;\n2:2:;\n")

    def test_latin1_bytes(self):
        self.check("count", ".", b"\xff\xfe", 2, "source\n0:1:ff;\n1:2:fe;\n", unicode=False)


@unittest.skipUnless(os.environ.get("LANGUAGE_NATIVE_RUST_BULK_RUNNER"),
                     "build and set LANGUAGE_NATIVE_RUST_BULK_RUNNER for native Rust bulk checks")
class TestNativeRustBulk(NativeBulkChecks, unittest.TestCase):
    RUNNER = "LANGUAGE_NATIVE_RUST_BULK_RUNNER"
    EMPTY_AFTER_MATCH = (2, "source\n0:1:61;\n2:2:;\n")

    def test_capture_counting_skips_the_empty_match_where_a_match_ended(self):
        # RE2 also reports the empty match at offset 1, so this case is not shared.
        self.check("count-captures", "(a*)", "ab", 4, "source\n0:1:61;0:1:61;\n2:2:;2:2:;\n")

    def test_rejects_input_outside_the_str_api(self):
        for pattern, source, unicode, diagnostic in ((".", b"\xff\xfe", True, "haystack is not UTF-8"),
                                                     (".", "a", False, "pattern can match invalid UTF-8")):
            with self.subTest(source=source, unicode=unicode):
                result = run_bulk(os.environ[self.RUNNER], "execute", "count", pattern, source, unicode)
                self.assertEqual(result.returncode, 1)
                self.assertIn(diagnostic, result.stderr)


if __name__ == "__main__":
    unittest.main()
