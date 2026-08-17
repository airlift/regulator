import copy
import gzip
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile


sys.path.insert(0, str(Path(__file__).parent))
import jvm_build


class TestJvmBuild(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.root = self.directory / "repo"
        self.root.mkdir()
        self.jar = self.directory / "joni.jar"
        with zipfile.ZipFile(self.jar, "w") as jar:
            jar.writestr(jvm_build.JONI_CLASS, b"pinned comparator fixture")
        pins = self.root / "tools/re2-benchmark/baseline/comparators.tsv"
        pins.parent.mkdir(parents=True)
        pins.write_text("component\tartifact_sha256\nairlift-joni\t" + jvm_build.digest(self.jar.read_bytes()) + "\n")
        (self.root / "mvnw").write_text("fixture Maven entry point\n")
        (self.root / "source.java").write_text("candidate fixture\n")
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "Fixture")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.STDOUT)

    def build_fixture(self, archive=None, provenance=None):
        destination = self.directory / "build"
        real_run = subprocess.run

        def compile_fixture(command, **kwargs):
            if not command[0].endswith("/mvnw"):
                return real_run(command, **kwargs)
            self.assertIn("clean", command)
            self.assertIn("test-compile", command)
            self.assertIn("-Dmaven.gitcommitid.skip=true", command)
            checkout = Path(kwargs["cwd"])
            self.assertEqual((checkout / "source.java").read_text(), "candidate fixture\n")
            self.assertNotEqual(checkout, self.root)
            for name in ("classes", "test-classes"):
                classes = checkout / "target" / name
                classes.mkdir(parents=True)
                (classes / "Fixture.class").write_bytes(b"compiled fixture")
            dependencies = Path(next(value.split("=", 1)[1] for value in command if value.startswith("-Dmdep.outputFile=")))
            dependencies.write_text(str(self.jar))

        with patch.object(jvm_build.subprocess, "run", side_effect=compile_fixture):
            receipt = jvm_build.build(destination, root=self.root, archive=archive, provenance=provenance)
        classpath = (destination / "classpath.txt").read_text().strip()
        self.assertEqual(json.loads((destination / "jvm-build.json").read_text()), receipt)
        return receipt, classpath

    def test_archive_build_receipt_and_original_outputs_are_preserved(self):
        # An ignored original target must never be the destination of Maven clean.
        (self.root / ".git/info/exclude").write_text("/target/\n")
        target = self.root / "target"
        target.mkdir()
        (target / "keep-result.json").write_text("important existing result")
        receipt, classpath = self.build_fixture()
        jvm_build.validate_receipt(receipt, self.root, "java", classpath)
        self.assertEqual((target / "keep-result.json").read_text(), "important existing result")
        self.assertEqual(receipt["source_tree"], self.git("rev-parse", "HEAD^{tree}").decode().strip())

    def test_dirty_source_is_rejected_before_build(self):
        (self.root / "source.java").write_text("uncommitted change")
        with self.assertRaisesRegex(ValueError, "clean candidate"):
            jvm_build.build(self.directory / "build", root=self.root)
        self.assertFalse((self.directory / "build").exists())

    def extracted_fixture(self):
        identity = jvm_build.source_identity(self.root)
        payload = self.git("archive", "--format=tar", "HEAD")
        archive = self.directory / "candidate.tar.gz"
        archive.write_bytes(gzip.compress(payload, mtime=0))
        provenance = self.directory / "candidate.tsv"
        provenance.write_text("candidate_commit\troot_tree\tarchive_sha256\n" +
                              identity["source_commit"] + "\t" + identity["source_tree"] + "\t" +
                              jvm_build.digest(archive.read_bytes()) + "\n")
        self.root = self.directory / "worker"
        self.root.mkdir()
        with tarfile.open(fileobj=io.BytesIO(payload)) as files:
            files.extractall(self.root, filter="data")
        return archive, provenance, identity

    def test_extracted_aws_source_builds_without_synthetic_git_history(self):
        # Include file modes, symlinks, and directory/file ordering in the Git-tree check.
        (self.root / "a.b").write_bytes(b"file before directory in Git tree ordering")
        (self.root / "a").mkdir()
        (self.root / "a/executable").write_bytes(b"executable")
        (self.root / "a/executable").chmod(0o755)
        (self.root / "link").symlink_to("source.java")
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "Archive fixture")
        archive, provenance, identity = self.extracted_fixture()
        self.assertFalse((self.root / ".git").exists())
        (self.root / "target").mkdir()
        (self.root / "target/keep.json").write_text("retained result")
        receipt, classpath = self.build_fixture(archive, provenance)
        jvm_build.validate_receipt(receipt, self.root, "java", classpath, archive, provenance)
        self.assertEqual(receipt["source_commit"], identity["source_commit"])
        self.assertEqual(receipt["source_tree"], identity["source_tree"])
        self.assertEqual((self.root / "target/keep.json").read_text(), "retained result")
        self.assertFalse((self.root / ".git").exists())

    def test_extracted_source_rejects_changes_before_build(self):
        archive, provenance, identity = self.extracted_fixture()
        original = provenance.read_text()
        for field in (identity["source_commit"], identity["source_tree"], jvm_build.digest(archive.read_bytes())):
            provenance.write_text(original.replace(field, "0" * len(field)))
            with self.assertRaises(ValueError):
                jvm_build.require_clean_source(self.root, archive, provenance)
        provenance.write_text(original)
        (self.root / "unexpected.java").write_text("not from the archive")
        with self.assertRaisesRegex(ValueError, "unrecorded file"):
            jvm_build.require_clean_source(self.root, archive, provenance)
        (self.root / "unexpected.java").unlink()
        (self.root / "source.java").write_text("changed source")
        with self.assertRaisesRegex(ValueError, "differs from archive"):
            jvm_build.build(self.directory / "build", root=self.root, archive=archive, provenance=provenance)
        self.assertFalse((self.directory / "build").exists())

    def test_archive_arguments_must_be_paired(self):
        with self.assertRaisesRegex(ValueError, "both source archive"):
            jvm_build.require_clean_source(self.root, self.directory / "missing.tar.gz")

    def test_stale_source_classes_classpath_order_and_jdk_are_rejected(self):
        receipt, classpath = self.build_fixture()
        broken = copy.deepcopy(receipt)
        broken["source_tree"] = "older-tree"
        with self.assertRaisesRegex(ValueError, "different candidate tree"):
            jvm_build.validate_receipt(broken, self.root, "java", classpath)
        reordered = os.pathsep.join(reversed(classpath.split(os.pathsep)))
        with self.assertRaisesRegex(ValueError, "artifacts differ"):
            jvm_build.validate_receipt(receipt, self.root, "java", reordered)
        broken = copy.deepcopy(receipt)
        broken["jvm"]["jdk"] = "another JDK"
        with self.assertRaisesRegex(ValueError, "artifacts differ"):
            jvm_build.validate_receipt(broken, self.root, "java", classpath)
        (Path(classpath.split(os.pathsep)[0]) / "Fixture.class").write_bytes(b"stale or changed class")
        with self.assertRaisesRegex(ValueError, "artifacts differ"):
            jvm_build.validate_receipt(receipt, self.root, "java", classpath)

    def test_unpinned_joni_and_shadowing_are_rejected(self):
        receipt, classpath = self.build_fixture()
        with zipfile.ZipFile(self.jar, "w") as jar:
            jar.writestr(jvm_build.JONI_CLASS, b"different comparator")
        # Even a self-consistent classpath receipt cannot substitute an unpinned comparator.
        receipt["jvm"] = jvm_build.jvm_identity("java", classpath)
        with self.assertRaisesRegex(ValueError, "pinned Joni"):
            jvm_build.validate_receipt(receipt, self.root, "java", classpath)
        shadow = Path(classpath.split(os.pathsep)[0]) / jvm_build.JONI_CLASS
        shadow.parent.mkdir(parents=True)
        shadow.write_bytes(b"classpath shadow")
        receipt["jvm"] = jvm_build.jvm_identity("java", classpath)
        with self.assertRaisesRegex(ValueError, "pinned jar"):
            jvm_build.validate_receipt(receipt, self.root, "java", classpath)


if __name__ == "__main__":
    unittest.main()
