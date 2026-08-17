import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import collection
import fleet
import source_bracket


class TestSourceBracket(unittest.TestCase):
    def test_disabled_by_default(self):
        self.assertIsNone(source_bracket.validate({}, Path("unused")))
        with patch.object(source_bracket.jvm_build, "build") as build:
            self.assertIsNone(source_bracket.prepare({}, Path("unused"), Path("unused"), "java"))
            build.assert_not_called()

    def test_checksums_are_preserved_and_checked(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original = root / "original"
            (original / "source-control").mkdir(parents=True)
            (original / source_bracket.ARCHIVE).write_bytes(b"archive")
            (original / source_bracket.PROVENANCE).write_bytes(b"receipt")
            manifest = {"source_control": {"archive_sha256": collection.digest(b"archive"),
                                           "provenance_sha256": collection.digest(b"receipt")}}
            destination = root / "copied"
            source_bracket.copy(manifest, original, destination)
            self.assertEqual(source_bracket.validate(manifest, destination), manifest["source_control"])
            (destination / source_bracket.ARCHIVE).write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                source_bracket.validate(manifest, destination)

    def test_bracket_keeps_roots_binaries_and_outputs_separate(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)
            candidate = collection.ROOT
            control = {"root": destination / "control", "classpath": "control-classes",
                       "receipt": "control-receipt", "archive": "control-archive", "provenance": "control-provenance"}
            calls = []
            def run_package(*args):
                calls.append((collection.ROOT, args))
            with patch.object(source_bracket.jvm_build, "source_identity", side_effect=lambda root, *_: {"root": str(root)}), \
                    patch.object(source_bracket.focused_controls, "run") as focused:
                source_bracket.run(control, [Path("one"), Path("two")], destination, "java", "candidate-classes",
                                   "native", "candidate-receipt", "candidate-archive", "candidate-provenance", run_package)
                self.assertEqual(focused.call_count, 6)
            self.assertEqual([root for root, _ in calls], [candidate, candidate, control["root"], control["root"], candidate, candidate])
            self.assertEqual([args[3] for _, args in calls], ["candidate-classes"] * 2 + ["control-classes"] * 2 + ["candidate-classes"] * 2)
            self.assertEqual([args[1].name for _, args in calls], ["results"] * 2 + ["control-results"] * 2 + ["after-results"] * 2)
            self.assertEqual(collection.ROOT, candidate)
            self.assertEqual(collection.load(destination / "source-bracket.json")["order"], ["candidate-before", "control", "candidate-after"])

    def test_batch_carries_one_checked_control_archive(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "inputs"
            collection.prepare(source)
            manifest = collection.load(source / "manifest.json")
            manifest["cases"] = manifest["cases"][:1]
            manifest["source_control"] = {"archive_sha256": collection.digest(b"archive"),
                                          "provenance_sha256": collection.digest(b"receipt")}
            (source / "source-control").mkdir()
            (source / source_bracket.ARCHIVE).write_bytes(b"archive")
            (source / source_bracket.PROVENANCE).write_bytes(b"receipt")
            collection.save(source / "manifest.json", manifest)
            plan = fleet.prepare(source, root / "plan", replicas=1, operation_budget=15)
            batch = root / "batch"
            fleet.package_batch(root / "plan", plan["host_batches"][0]["id"], batch)
            self.assertEqual(len(list(batch.rglob("source.tar.gz"))), 1)
            fleet.validate_batch(batch)
            (batch / source_bracket.PROVENANCE).write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                fleet.validate_batch(batch)

    def test_failure_restores_candidate_root_and_does_not_claim_completion(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)
            candidate = collection.ROOT
            control = {"root": destination / "control", "classpath": "control-classes",
                       "receipt": "control-receipt", "archive": "control-archive", "provenance": "control-provenance"}
            def run_package(*args):
                if collection.ROOT != candidate:
                    raise ValueError("control failed")
            with self.assertRaisesRegex(ValueError, "control failed"), patch.object(source_bracket.focused_controls, "run"):
                source_bracket.run(control, [Path("one")], destination, "java", "candidate-classes",
                                   "native", "candidate-receipt", "candidate-archive", "candidate-provenance", run_package)
            self.assertEqual(collection.ROOT, candidate)
            self.assertFalse((destination / "source-bracket.json").exists())


if __name__ == "__main__":
    unittest.main()
