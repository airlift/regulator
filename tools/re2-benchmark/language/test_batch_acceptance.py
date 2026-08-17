import copy
import shutil
import unittest

import batch_acceptance
import collection
import fleet
import source_bracket
import test_fleet


class TestBatchAcceptance(unittest.TestCase):
    def setUp(self, bracket=False):
        self.fixture = test_fleet.TestFleet()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        if bracket:
            source = self.fixture.source
            (source / "source-control").mkdir()
            (source / source_bracket.ARCHIVE).write_bytes(b"control archive")
            (source / source_bracket.PROVENANCE).write_text("candidate_commit\troot_tree\ncontrol\tcontrol-tree\n")
            manifest = collection.load(source / "manifest.json")
            manifest["source_control"] = {
                "archive_sha256": collection.digest((source / source_bracket.ARCHIVE).read_bytes()),
                "provenance_sha256": collection.digest((source / source_bracket.PROVENANCE).read_bytes())}
            collection.save(source / "manifest.json", manifest)
        self.plan = self.fixture.prepare_small_plan()
        self.raw = self.fixture.write_results(self.plan)
        self.batch = self.plan["host_batches"][0]
        self.result = self.fixture.root / "host"
        self.inputs = self.result / "language-inputs"
        self.package = fleet.package_batch(self.fixture.directory, self.batch["id"], self.inputs)
        worker = self.result / "language-worker"
        self.job_result = worker / "results" / self.batch["jobs"][0]
        shutil.copytree(self.raw / self.batch["jobs"][0], self.job_result)
        provenance = collection.load(self.job_result / "provenance.json")
        provenance["jdk"] = "Temurin 25.0.4+7"
        collection.save(self.job_result / "provenance.json", provenance)
        collection.export(self.inputs / self.package["packages"][0]["directory"], self.job_result)
        collection.save(worker / "worker.json", {"kind": "qualification", "package": self.package})
        collection.save(worker / "cpu-affinity.json", [0])
        self.environment = {
            "verification_status": "verified", "benchmark_mode": "language-batch", "platform": "c9g",
            "shard": self.batch["shard"], "replica": "1", "benchmark_heap_size": "8g",
            "instance_id": "i-0", "instance_type": "c9g.xlarge", "regulator_commit": "commit",
            "baseline_campaign": "test", "host_epoch": "1", "campaign_architecture": "arm",
            "regulator_archive_sha256": "archive", "comparator_manifest_sha256": "pins",
            "java_runtime_version": "25.0.4+7-LTS"}
        self.write_environment()
        if bracket:
            for leg in ("control-results", "after-results"):
                result = worker / leg / self.batch["jobs"][0]
                shutil.copytree(self.job_result, result)
                if leg == "control-results":
                    provenance = collection.load(result / "provenance.json")
                    provenance.update(source_commit="control", source_tree="control-tree")
                    provenance["jvm_build"]["source_tree"] = "control-tree"
                    collection.save(result / "provenance.json", provenance)
                    collection.export(self.inputs / self.package["packages"][0]["directory"], result)
            collection.save(worker / "source-bracket.json", {
                "order": ["candidate-before", "control", "candidate-after"],
                "results": ["results", "control-results", "after-results"],
                "candidate": {"source_commit": "commit", "source_tree": "tree"},
                "control": {"source_commit": "control", "source_tree": "control-tree"}})

    def write_environment(self):
        (self.result / "environment-manifest.txt").write_text(
            "".join(f"{key}={value}\n" for key, value in self.environment.items()))

    def test_regenerates_export_and_binds_batch(self):
        before = {path: path.read_bytes() for path in self.result.rglob("*") if path.is_file()}
        receipt = batch_acceptance.validate(self.result)
        self.assertEqual(receipt["exports"], {
            self.batch["jobs"][0]: collection.digest((self.job_result / "language-results.json").read_bytes())})
        self.assertEqual(receipt["batch_sha256"], collection.digest(collection.encode(self.package)))
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_rejects_changed_raw_measurements(self):
        raw = next(self.job_result.rglob("raw.json"))
        raw.write_text("[]\n")
        with self.assertRaises((ValueError, TypeError)):
            batch_acceptance.validate(self.result)

    def test_rejects_host_candidate_protocol_and_assignment_changes(self):
        original = copy.deepcopy(self.environment)
        for field in ("instance_id", "instance_type", "regulator_commit", "platform", "shard", "replica",
                      "benchmark_heap_size", "comparator_manifest_sha256", "java_runtime_version"):
            with self.subTest(field=field):
                self.environment = {**original, field: "changed"}
                self.write_environment()
                with self.assertRaises(ValueError):
                    batch_acceptance.validate(self.result)

    def test_rejects_incomplete_diagnostic_extra_results_and_affinity(self):
        path = self.result / "language-worker/worker.json"
        completion = collection.load(path)
        collection.save(path, {**completion, "kind": "verification-only"})
        with self.assertRaisesRegex(ValueError, "qualification"):
            batch_acceptance.validate(self.result)
        collection.save(path, completion)
        for affinity in ([], [0, 1], [True]):
            collection.save(self.result / "language-worker/cpu-affinity.json", affinity)
            with self.assertRaisesRegex(ValueError, "affinity"):
                batch_acceptance.validate(self.result)
        collection.save(self.result / "language-worker/cpu-affinity.json", [0])
        extra = self.result / "language-worker/results/extra/job.json"
        extra.parent.mkdir()
        collection.save(extra, {})
        with self.assertRaisesRegex(ValueError, "unexpected result"):
            batch_acceptance.validate(self.result)


class TestBracketAcceptance(unittest.TestCase):
    def setUp(self):
        self.fixture = TestBatchAcceptance()
        self.fixture.setUp(bracket=True)
        self.addCleanup(self.fixture.doCleanups)
        self.result = self.fixture.result
        self.worker = self.result / "language-worker"

    def test_accepts_complete_bracket_without_rewriting_evidence(self):
        before = {path: path.read_bytes() for path in self.result.rglob("*") if path.is_file()}
        receipt = batch_acceptance.validate(self.result)
        self.assertEqual(set(receipt["source_bracket"]["legs"]), {"results", "control-results", "after-results"})
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_missing_leg_or_marker_is_rejected(self):
        for name in ("control-results", "after-results", "source-bracket.json"):
            with self.subTest(name=name):
                path = self.worker / name
                saved = self.worker / (name + ".saved")
                path.rename(saved)
                try:
                    with self.assertRaises((ValueError, FileNotFoundError)):
                        batch_acceptance.validate(self.result)
                finally:
                    saved.rename(path)

    def test_wrong_order_or_source_marker_is_rejected(self):
        path = self.worker / "source-bracket.json"
        original = collection.load(path)
        for field, value in (("order", list(reversed(original["order"]))),
                             ("candidate", original["control"]), ("control", original["candidate"])):
            collection.save(path, {**original, field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                batch_acceptance.validate(self.result)
        collection.save(path, original)

    def test_wrong_host_or_source_in_reexported_leg_is_rejected(self):
        result = self.worker / "control-results" / self.fixture.batch["jobs"][0]
        path = result / "provenance.json"
        original = collection.load(path)
        for field, value in (("source_commit", "wrong"), ("source_tree", "wrong"),
                             ("instance_identity", {"instanceId": "other", "instanceType": "c9g.xlarge"})):
            collection.save(path, {**original, field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                collection.export(self.fixture.inputs / self.fixture.package["packages"][0]["directory"], result)
                batch_acceptance.validate(self.result)
        collection.save(path, original)

    def test_corrupt_raw_control_or_after_leg_is_rejected(self):
        for leg in ("control-results", "after-results"):
            raw = next((self.worker / leg).rglob("raw.json"))
            original = raw.read_bytes()
            raw.write_text("[]\n")
            try:
                with self.subTest(leg=leg), self.assertRaises((ValueError, TypeError)):
                    batch_acceptance.validate(self.result)
            finally:
                raw.write_bytes(original)


if __name__ == "__main__":
    unittest.main()
