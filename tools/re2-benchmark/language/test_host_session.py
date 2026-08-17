import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest

import collection
import test_batch_acceptance


class TestLanguageHostSession(unittest.TestCase):
    def setUp(self, bracket=False):
        self.fixture = test_batch_acceptance.TestBatchAcceptance()
        self.fixture.setUp(bracket=bracket)
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.fixture.root
        self.regulator = self.root / "regulator"
        scripts = self.regulator / "tools/re2-benchmark/language"
        scripts.mkdir(parents=True)
        (scripts / "worker.py").write_text(FAKE_WORKER)
        (scripts / "batch_acceptance.py").symlink_to(Path(collection.__file__).with_name("batch_acceptance.py"))
        self.result = self.root / "returned"
        shutil.copytree(self.fixture.inputs, self.result / "language-inputs")
        shutil.copyfile(self.fixture.result / "environment-manifest.txt", self.result / "environment-manifest.txt")

    def run_session(self, failure=False):
        return subprocess.run([
            "bash", str(Path(collection.__file__).with_name("run-host-session.sh")), str(self.regulator), str(self.result)],
            env=dict(os.environ, PYTHONPATH=str(Path(collection.__file__).parent), PYTHONDONTWRITEBYTECODE="1",
                     LANGUAGE_SOURCE_ARCHIVE="source.tar.gz", LANGUAGE_CANDIDATE_PROVENANCE="candidate.tsv",
                     FAKE_WORKER_RESULTS=str(self.fixture.result / "language-worker"),
                     FAKE_WORKER_FAILURE="1" if failure else "0"),
            text=True, capture_output=True)

    def test_shell_transports_raw_evidence_and_regenerates_acceptance(self):
        result = self.run_session()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.result / "language-acceptance.json").is_file())
        self.assertTrue((self.result / "language-worker/build.log").is_file())
        self.assertTrue((self.result / "language-worker/results").is_dir())
        self.assertFalse((self.result / "language-worker/jvm").exists())
        self.assertFalse((self.result / "language-worker/native").exists())

    def test_shell_preserves_failure_logs_without_accepting_partial_results(self):
        result = self.run_session(failure=True)
        self.assertEqual(result.returncode, 3, result.stdout + result.stderr)
        self.assertTrue((self.result / "language-worker/build.log").is_file())
        self.assertFalse((self.result / "language-acceptance.json").exists())

    def test_shell_transports_all_bracket_legs_and_control_build_receipts(self):
        self.doCleanups()
        self.setUp(bracket=True)
        result = self.run_session()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        worker = self.result / "language-worker"
        receipt = collection.load(self.result / "language-acceptance.json")
        self.assertEqual(set(receipt["source_bracket"]["legs"]), {"results", "control-results", "after-results"})
        self.assertTrue((worker / "source-bracket.json").is_file())
        self.assertEqual({path.name for path in (worker / "control-jvm").iterdir()}, {"build.log", "jvm-build.json"})
        self.assertFalse((worker / "control-source").exists())


# Stand in only for the expensive build/timing process. The shell dispatch,
# artifact copying, raw reducer and separate acceptance process run unchanged.
FAKE_WORKER = r'''
import os
from pathlib import Path
import shutil
import sys
assert "--batch-directory" in sys.argv and "--measure" in sys.argv
assert "--source-archive" in sys.argv and "--candidate-provenance" in sys.argv
assert os.environ["PYTHONDONTWRITEBYTECODE"] == "1"
destination = Path(sys.argv[sys.argv.index("--output-directory") + 1])
shutil.copytree(os.environ["FAKE_WORKER_RESULTS"], destination)
(destination / "jvm/source").mkdir(parents=True)
(destination / "jvm/build.log").write_text("synthetic build log\n")
(destination / "jvm/jvm-build.json").write_text("{}\n")
(destination / "native").mkdir()
(destination / "control-source").mkdir()
(destination / "control-jvm/source").mkdir(parents=True)
(destination / "control-jvm/build.log").write_text("control build log\n")
(destination / "control-jvm/jvm-build.json").write_text("{}\n")
if os.environ["FAKE_WORKER_FAILURE"] == "1":
    (destination / "worker.json").unlink()
    sys.exit(3)
'''


if __name__ == "__main__":
    unittest.main()
