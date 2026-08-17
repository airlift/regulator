import os
from pathlib import Path
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.environ.get("LANGUAGE_NATIVE_BULK_RUNNER"),
                     "build and set LANGUAGE_NATIVE_BULK_RUNNER for native bulk checks")
class TestNativeBulk(unittest.TestCase):
    def check(self, model, pattern, source, expected, trace, unicode=True):
        fields = {"name": "test", "model": model, "pattern": pattern, "haystack": source,
                  "unicode": str(unicode).lower(), "case-insensitive": "false"}
        payload = b""
        for key, value in fields.items():
            value = value.encode() if isinstance(value, str) else value
            payload += key.encode() + b":" + str(len(value)).encode() + b":" + value + b"\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.klv"
            path.write_bytes(payload)
            for action, output in (("execute", str(expected) + "\n"), ("trace", trace)):
                result = subprocess.run([os.environ["LANGUAGE_NATIVE_BULK_RUNNER"], "--" + action, str(path)],
                                        capture_output=True, text=True, timeout=10)
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
        self.check("count", "a*", "ab", 3, "source\n0:1:61;\n1:1:;\n2:2:;\n")
        self.check("count", "a*", "💰é", 3, "source\n0:0:;\n4:4:;\n6:6:;\n")
        self.check("count", "a*", "", 1, "source\n0:0:;\n")

    def test_latin1_and_empty_lines(self):
        self.check("count", ".", b"\xff\xfe", 2, "source\n0:1:ff;\n1:2:fe;\n", unicode=False)
        self.check("grep", ".", "", 0, "")
        self.check("grep", "^$", "\n\n", 2, "source\n1\nsource\n1\n")


if __name__ == "__main__":
    unittest.main()
