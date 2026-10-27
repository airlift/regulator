import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


MANIFESTS = Path(__file__).resolve().parent


class TestExtendedRebar(unittest.TestCase):
    def testExtendedManifestCheckIsConcurrencySafe(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            definitions = root / "rebar" / "benchmarks" / "definitions" / "suite"
            definitions.mkdir(parents=True)
            (definitions / "example.toml").write_text(
                "[[bench]]\nname = 'example'\nmodel = 'grep'\nengines = ['re2']\n")
            output = root / "rows.tsv"
            exclusions = root / "exclusions.tsv"
            command = [
                sys.executable,
                str(MANIFESTS / "generate-extended-rebar.py"),
                str(root / "rebar"),
                str(output),
                "--exclusions-output",
                str(exclusions),
            ]
            subprocess.run(command, check=True, capture_output=True, text=True)

            processes = [
                subprocess.Popen(command + ["--check"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                for _ in range(16)
            ]
            scratch_files_during_checks = list(root.glob("*.generated"))
            results = [process.communicate() + (process.returncode,) for process in processes]

            self.assertEqual([result for result in results if result[2] != 0], [])
            self.assertEqual(scratch_files_during_checks, [])
            self.assertEqual(list(root.glob("*.generated")), [])

    def testArchivedRevisionReceiptRejectsWrongCommit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".regulator-rebar-commit").write_text(f"{'0' * 40}\n")

            result = subprocess.run(
                [str(MANIFESTS / "rebar-revision.sh"), str(root)],
                capture_output=True,
                text=True,
                check=False)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Extended Rebar validation requires", result.stderr)


if __name__ == "__main__":
    unittest.main()
