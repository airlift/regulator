import copy
import shutil
import unittest
from unittest.mock import patch

import batch_acceptance
import collection
import fleet
import released_artifact
import test_fleet


class TestBatchAcceptance(unittest.TestCase):
    def setUp(self):
        self.fixture = test_fleet.TestFleet()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
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
            "verification_status": "verified", "benchmark_mode": "language-batch", "platform": "r9g",
            "shard": self.batch["shard"], "replica": "1", "benchmark_heap_size": "8g",
            "instance_id": "i-0", "instance_type": self.batch["instance_type"],
            "logical_cpu_count": str(self.batch["vcpus"]), "regulator_commit": "commit",
            "baseline_campaign": "test", "host_epoch": "1", "campaign_architecture": "arm",
            "regulator_archive_sha256": "archive", "comparator_manifest_sha256": "pins",
            "java_runtime_version": "25.0.4+7-LTS"}
        self.write_environment()

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
        for field in ("instance_id", "instance_type", "logical_cpu_count", "regulator_commit", "platform", "shard", "replica",
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

    def test_released_batch_requires_the_frozen_release_manifest(self):
        expected = released_artifact.manifest(collection.ROOT, "1.0")
        exported_path = self.job_result / "language-results.json"
        exported = collection.load(exported_path)
        exported["provenance"]["jvm_build"]["released_artifact"] = {"manifest": expected}
        collection.save(exported_path, exported)
        self.environment["regulator_release_version"] = "1.0"
        self.write_environment()
        with patch.object(batch_acceptance.collection, "export",
                          side_effect=lambda _inputs, result, write=False: collection.load(result / "language-results.json")):
            batch_acceptance.validate(self.result)
            for field, value in (("source_commit", "0" * 40), ("engine_tree", "0" * 40),
                                 ("jar_sha256", "0" * 64)):
                changed = copy.deepcopy(exported)
                changed["provenance"]["jvm_build"]["released_artifact"]["manifest"] = {
                    **expected, field: value}
                collection.save(exported_path, changed)
                with self.subTest(field=field), self.assertRaisesRegex(ValueError, "selected released artifact"):
                    batch_acceptance.validate(self.result)


if __name__ == "__main__":
    unittest.main()
