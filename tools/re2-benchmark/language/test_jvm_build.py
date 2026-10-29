import gzip
import io
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile


sys.path.insert(0, str(Path(__file__).parent))
import jvm_build


class TestJvmBuild(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name).resolve()
        self.root = self.directory / "repo"
        self.root.mkdir()
        # The engine build's installed candidate classes, with the pinned Joni jar among their dependencies.
        self.shared = self.directory / "shared"
        self.classes = self.shared / "regulator-classes"
        (self.classes / "classes").mkdir(parents=True)
        (self.classes / "lib").mkdir()
        (self.classes / "classpath.txt").write_text("classes:lib/joni.jar\n")
        self.jar = self.classes / "lib/joni.jar"
        with zipfile.ZipFile(self.jar, "w") as jar:
            jar.writestr(jvm_build.JONI_CLASS, b"pinned comparator fixture")
        (self.shared / "engine-build").mkdir()
        (self.shared / "engine-build/classes.sha256").write_text("c" * 64 + "\n")
        pins = self.root / "tools/re2-benchmark/baseline/comparators.tsv"
        pins.parent.mkdir(parents=True)
        pins.write_text("component\tartifact_sha256\nairlift-joni\t" + jvm_build.digest(self.jar.read_bytes()) + "\n")
        (self.root / "source.java").write_text("candidate fixture\n")
        # Include file modes, symlinks, and directory/file ordering in the Git-tree check.
        (self.root / "a.b").write_bytes(b"file before directory in Git tree ordering")
        (self.root / "a").mkdir()
        (self.root / "a/executable").write_bytes(b"executable")
        (self.root / "a/executable").chmod(0o755)
        (self.root / "link").symlink_to("source.java")
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "Fixture")
        self.archive, self.provenance, self.identity = self.extracted_fixture()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.STDOUT).decode().strip()

    def extracted_fixture(self):
        identity = {"source_commit": self.git("rev-parse", "HEAD"), "source_tree": self.git("rev-parse", "HEAD^{tree}")}
        # Freezing a candidate adds the generated benchmark tables to the Git archive.
        table = self.directory / "units.tsv"
        table.write_text("generated\n")
        payload = subprocess.check_output(["git", "archive", "--format=tar", "--prefix=tools/re2-benchmark/baseline/",
                                           f"--add-file={table}", "--prefix=", "HEAD"], cwd=self.root)
        archive = self.directory / "candidate.tar.gz"
        archive.write_bytes(gzip.compress(payload, mtime=0))
        provenance = self.directory / "candidate.tsv"
        provenance.write_text("candidate_commit\troot_tree\tarchive_sha256\n" + identity["source_commit"] + "\t" +
                              identity["source_tree"] + "\t" + jvm_build.digest(archive.read_bytes()) + "\n")
        self.root = self.directory / "worker"
        self.root.mkdir()
        with tarfile.open(fileobj=io.BytesIO(payload)) as files:
            files.extractall(self.root, filter="data")
        return archive, provenance, identity

    def prepare(self):
        return jvm_build.prepare(self.shared, "java", self.root, self.archive, self.provenance)

    def test_installed_classes_are_bound_to_their_archive_and_the_extracted_source(self):
        # Tool output under target is not part of the source.
        (self.root / "target").mkdir()
        (self.root / "target/keep.json").write_text("retained result")
        classpath, receipt = self.prepare()
        self.assertEqual(os.pathsep.join([str(self.classes / "classes"), str(self.jar)]), classpath)
        self.assertEqual({**self.identity, "classes_archive_sha256": "c" * 64}, {
            key: receipt[key] for key in ("source_commit", "source_tree", "classes_archive_sha256")})
        self.assertEqual(classpath.split(os.pathsep), receipt["jvm"]["classpath"])
        self.assertFalse((self.root / ".git").exists())

    def test_extracted_source_must_be_the_frozen_candidate(self):
        original = self.provenance.read_text()
        for field in (self.identity["source_commit"], self.identity["source_tree"],
                      jvm_build.digest(self.archive.read_bytes())):
            self.provenance.write_text(original.replace(field, "0" * len(field)))
            with self.assertRaises(ValueError):
                self.prepare()
        self.provenance.write_text(original)
        (self.root / "unexpected.java").write_text("not from the archive")
        with self.assertRaisesRegex(ValueError, "unrecorded file"):
            self.prepare()
        (self.root / "unexpected.java").unlink()
        (self.root / "source.java").write_text("changed source")
        with self.assertRaisesRegex(ValueError, "differs from archive"):
            self.prepare()

    def test_classes_require_an_installed_archive_and_the_pinned_joni(self):
        with zipfile.ZipFile(self.jar, "w") as jar:
            jar.writestr(jvm_build.JONI_CLASS, b"different comparator")
        with self.assertRaisesRegex(ValueError, "pinned Joni"):
            self.prepare()
        shadow = self.classes / "classes" / jvm_build.JONI_CLASS
        shadow.parent.mkdir(parents=True)
        shadow.write_bytes(b"classpath shadow")
        with self.assertRaisesRegex(ValueError, "pinned jar"):
            self.prepare()
        (self.shared / "engine-build/classes.sha256").unlink()
        with self.assertRaisesRegex(ValueError, "no prebuilt candidate classes"):
            self.prepare()


if __name__ == "__main__":
    unittest.main()
