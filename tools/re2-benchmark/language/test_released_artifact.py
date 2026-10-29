import contextlib
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import released_artifact as release


class TestReleasedArtifact(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.jar = self.root / "regulator-1.0.jar"
        self.classes = [release.PACKAGE + name + ".class" for name in release.PUBLIC_CLASSES]
        self.classes.append(release.PACKAGE + "Dfa$State.class")
        with zipfile.ZipFile(self.jar, "w") as jar:
            for name in self.classes:
                jar.writestr(name, b"fixture class")
        self.identity = {"schema_version": 1, "group_id": "io.airlift", "artifact_id": "regulator",
                         "version": "1.0", "source_commit": "a" * 40, "engine_tree": "b" * 40,
                         "jar_sha256": release.digest(self.jar.read_bytes())}
        self.harness = self.root / "test-classes"
        self.harness.mkdir()
        self.classpath = os.pathsep.join(map(str, (self.harness, self.jar)))

    def receipt(self):
        return {"manifest": self.identity, "jar_path": str(self.jar),
                "code_sources": {"io.airlift.regulator." + name: str(self.jar) for name in release.PUBLIC_CLASSES},
                "production_classes": sorted(self.classes), "classpath": release.classpath_identity(self.classpath)}

    def test_rejects_duplicate_missing_and_empty_production_entries(self):
        release.validate_classpath(self.classpath, self.jar, self.identity)
        for classpath in (str(self.harness), self.classpath + os.pathsep + str(self.jar),
                          self.classpath + os.pathsep):
            with self.subTest(classpath=classpath), self.assertRaises(ValueError):
                release.validate_classpath(classpath, self.jar, self.identity)

    def test_rejects_internal_classes_shadowed_by_directory_or_jar(self):
        name = self.classes[-1]
        shadow = self.harness / name
        shadow.parent.mkdir(parents=True)
        shadow.write_bytes(b"source build")
        with self.assertRaisesRegex(ValueError, "shadow"):
            release.validate_classpath(self.classpath, self.jar, self.identity)
        shadow.unlink()
        duplicate = self.root / "duplicate.jar"
        with zipfile.ZipFile(duplicate, "w") as jar:
            jar.writestr(name, b"source build")
        with self.assertRaisesRegex(ValueError, "shadow"):
            release.validate_classpath(self.classpath + os.pathsep + str(duplicate), self.jar, self.identity)

    def test_attestation_records_runtime_origins_and_all_classpath_hashes(self):
        output = "warning: vector module\n" + "".join(
            "io.airlift.regulator." + name + "\t" + str(self.jar) + "\n" for name in release.PUBLIC_CLASSES)
        with patch.object(release.subprocess, "check_output", return_value=output):
            receipt = release.attest(self.classpath, self.jar, self.identity, self.root)
        self.assertEqual(receipt, self.receipt())
        release.validate_saved(receipt, {"classpath": [entry["path"] for entry in receipt["classpath"]]})
        with patch.object(release.subprocess, "check_output", return_value=output.splitlines()[1] + "\n"):
            with self.assertRaisesRegex(ValueError, "incomplete"):
                release.attest(self.classpath, self.jar, self.identity, self.root)

    def test_recovered_receipt_rejects_altered_jar_origins_and_shadowing(self):
        original = self.receipt()
        for change in ("digest", "origins", "shadow", "measured"):
            receipt = copy.deepcopy(original)
            jvm = {"classpath": [entry["path"] for entry in receipt["classpath"]]}
            if change == "digest":
                receipt["manifest"]["jar_sha256"] = "c" * 64
            elif change == "origins":
                receipt["code_sources"]["io.airlift.regulator.Re2"] = "/source/classes"
            elif change == "shadow":
                receipt["classpath"][0]["files"][self.classes[-1]] = "d" * 64
            else:
                jvm["classpath"].pop()
            with self.subTest(change=change), self.assertRaises(ValueError):
                release.validate_saved(receipt, jvm)

    def test_production_tree_matches_git_and_detects_changed_source(self):
        source = self.root / "src/main/java/A.java"
        source.parent.mkdir(parents=True)
        source.write_text("fixture")
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "add", "src"], cwd=self.root, check=True)
        tree = subprocess.check_output(["git", "write-tree"], cwd=self.root, text=True).strip()
        expected = subprocess.check_output(["git", "rev-parse", tree + ":src/main"], cwd=self.root, text=True).strip()
        self.assertEqual(release.production_tree(self.root), expected)
        source.write_text("changed")
        self.assertNotEqual(release.production_tree(self.root), expected)

    def test_prepare_refuses_mismatched_source_or_cached_bytes_without_download(self):
        with patch.object(release, "manifest", return_value=self.identity), \
                patch.object(release, "production_tree", return_value="bad"), \
                patch.object(release.urllib.request, "urlopen") as download:
            with self.assertRaisesRegex(ValueError, "source differs"):
                release.prepare(self.root, "1.0", self.root)
            download.assert_not_called()
        self.jar.write_bytes(b"wrong")
        with patch.object(release, "manifest", return_value=self.identity), \
                patch.object(release, "production_tree", return_value=self.identity["engine_tree"]), \
                patch.object(release.urllib.request, "urlopen") as download:
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                release.prepare(self.root, "1.0", self.root)
            download.assert_not_called()

    def test_record_writes_a_new_release_manifest_from_its_tag_and_central_jar(self):
        revisions = {"refs/tags/2.0^{commit}": "a" * 40 + "\n", "refs/tags/2.0:src/main": "b" * 40 + "\n"}
        path = self.root / "tools/re2-benchmark/baseline/releases/2.0.json"
        path.parent.mkdir(parents=True)
        command = ["released_artifact.py", "--root", str(self.root), "--record", "2.0"]
        expected = json.dumps({**self.identity, "version": "2.0"}, indent=2) + "\n"
        with patch.object(release.subprocess, "check_output",
                          side_effect=lambda arguments, text: revisions[arguments[-1]]), \
                patch.object(release.urllib.request, "urlopen") as download, patch("sys.argv", command), \
                contextlib.redirect_stdout(io.StringIO()):
            download.return_value.__enter__.return_value.read.return_value = self.jar.read_bytes()
            release.main()
            # The same format as the committed manifests.
            committed = Path(__file__).resolve().parents[1] / "baseline/releases/1.0.json"
            self.assertEqual(committed.read_text(), json.dumps(json.loads(committed.read_text()), indent=2) + "\n")
            self.assertEqual(path.read_text(), expected)
            # Recording again, or with attest options, is refused and leaves the manifest as it was.
            for options, refusal in (([], "release manifest already exists"),
                                     (["--version", "2.0", "--destination", str(self.root)],
                                      "--record takes no --version, --destination")):
                errors = io.StringIO()
                with self.subTest(refusal=refusal), patch("sys.argv", command + options), \
                        contextlib.redirect_stderr(errors), self.assertRaises(SystemExit):
                    release.main()
                self.assertIn(refusal, errors.getvalue())
                self.assertEqual(path.read_text(), expected)
            with patch("sys.argv", command[:-1] + [""]), self.assertRaisesRegex(ValueError, "invalid release version"):
                release.main()
            download.assert_called_once_with(
                "https://repo.maven.apache.org/maven2/io/airlift/regulator/2.0/regulator-2.0.jar", timeout=60)

    def test_baseline_recovery_requires_release_proof_for_every_route(self):
        for name in ("safe", "native-access"):
            directory = self.root / "routes" / name
            (directory / "release").mkdir(parents=True)
            (directory / "release" / self.jar.name).write_bytes(self.jar.read_bytes())
            path = directory / "release-artifact.json"
            path.write_text(json.dumps(self.receipt()))
            (directory / "run-metadata.txt").write_text(
                "release_version=1.0\nrelease_artifact_sha256=" + release.digest(path.read_bytes()) + "\n")
        release.validate_baseline(self.root, self.identity)
        (self.root / "routes/safe/run-metadata.txt").write_text("release_version=\n")
        with self.assertRaisesRegex(ValueError, "selected release"):
            release.validate_baseline(self.root, self.identity)


if __name__ == "__main__":
    unittest.main()
