import copy
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))
import collection
import fleet


class TestFleet(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root / "manifest"
        collection.prepare(self.source)
        self.directory = self.root / "fleet"

    def prepare_small_plan(self, replicas=1):
        manifest = collection.load(self.source / "manifest.json")
        manifest["cases"] = manifest["cases"][:1]
        collection.save(self.source / "manifest.json", manifest)
        return fleet.prepare(self.source, self.directory, replicas)

    def test_complete_matrix_keeps_comparator_with_both_memory_modes(self):
        (self.source / "unrelated.txt").write_text("not part of the workload")
        plan = fleet.prepare(self.source, self.directory)
        self.assertEqual(len(plan["partitions"]), 39)
        self.assertEqual(len(plan["jobs"]), 351)
        self.assertEqual(plan["platform_allocations"], {
            "r9g": {"instance_type": "r9g.2xlarge", "vcpus": 8},
            "r8g": {"instance_type": "r8g.large", "vcpus": 2},
            "r8i": {"instance_type": "r8i.large", "vcpus": 2},
        })
        self.assertEqual(fleet.validate(self.directory), plan)
        self.assertFalse((self.directory / "source/unrelated.txt").exists())
        for partition in plan["partitions"]:
            manifest = collection.load(self.directory / "partitions" / partition["id"] / "manifest.json")
            observations = list(collection.observations(manifest))
            self.assertEqual(len(observations), 3)
            self.assertEqual({row[2] for row in observations}, {partition["language"]})
            self.assertEqual([row[4] for row in observations], ["native", "safe", "safe"])
        with self.assertRaises(FileExistsError):
            fleet.prepare(self.source, self.directory)

    def test_steady_profile_survives_partition_and_batch_transport(self):
        import campaign
        import transport
        manifest = collection.load(self.source / "manifest.json")
        manifest["protocol"] = collection.STEADY_PROTOCOLS["language-lifecycle"]
        collection.save(self.source / "manifest.json", manifest)
        plan = fleet.prepare(self.source, self.directory, selection=[("everyday/logLevel", "java")])
        controller = campaign.LanguageCampaign([self.directory], "primary")
        batch = plan["host_batches"][0]
        archive = self.root / "batch.tar.gz"
        checksum = controller.package(batch["id"], archive)
        destination = self.root / "unpacked"
        package = transport.unpack(archive, destination, checksum, batch["platform"], batch["shard"], batch["replica"])
        for entry in package["packages"]:
            actual = collection.load(destination / entry["directory"] / "manifest.json")
            self.assertEqual(collection.protocol_for(actual), manifest["protocol"])

    def test_operation_partition_keeps_full_comparison_on_one_host(self):
        import campaign
        import transport
        manifest = collection.load(self.source / "manifest.json")
        manifest["protocol"] = collection.STEADY_PROTOCOLS["language-lifecycle"]
        manifest["selected_operations"] = ["reusedContains", "reusedCount"]
        collection.save(self.source / "manifest.json", manifest)
        plan = fleet.prepare(self.source, self.directory, selection=[("everyday/logLevel", "java")])
        controller = campaign.LanguageCampaign([self.directory], "primary")
        batch = plan["host_batches"][0]
        archive = self.root / "batch.tar.gz"
        checksum = controller.package(batch["id"], archive)
        destination = self.root / "unpacked"
        package = transport.unpack(archive, destination, checksum, batch["platform"], batch["shard"], batch["replica"])
        self.assertEqual(len(package["packages"]), 1)
        actual = collection.load(destination / package["packages"][0]["directory"] / "manifest.json")
        self.assertEqual(collection.case_operations(actual, actual["cases"][0]), ("reusedContains", "reusedCount"))
        observations = list(collection.observations(actual))
        self.assertEqual([(row[3], row[4]) for row in observations],
                         [("java", "native"), ("java", "safe"), ("jdk", "safe")])

    def test_plan_concurrency_is_configurable(self):
        with patch.object(sys, "argv", ["fleet.py", "prepare", "--manifest-directory", str(self.source),
                                       "--output-directory", str(self.directory), "--max-concurrent-hosts", "512"]):
            fleet.main()
        self.assertEqual(fleet.validate(self.directory)["max_concurrent_hosts"], 512)
        for value in (0, -1, True, 1.5):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "positive integer"):
                fleet.prepare(self.source, self.root / "invalid", max_concurrent_hosts=value)

    def test_archive_validation_preserves_schema_two_evidence(self):
        plan = self.prepare_small_plan()
        plan["schema_version"] = 2
        del plan["platform_allocations"]
        for assignment in plan["jobs"] + plan["host_batches"]:
            del assignment["instance_type"]
            del assignment["vcpus"]
        collection.save(self.directory / "plan.json", plan)

        with self.assertRaisesRegex(ValueError, "fleet protocol"):
            fleet.validate(self.directory)
        self.assertEqual(fleet.validate_archive(self.directory), plan)

    def test_reject_incomplete_duplicate_or_changed_plan(self):
        original = self.prepare_small_plan()
        for mutation in (lambda plan: plan["partitions"].pop(),
                         lambda plan: plan["partitions"].append(plan["partitions"][0]),
                         lambda plan: plan["jobs"].pop(),
                         lambda plan: plan["jobs"][0].update(platform="r8i"),
                         lambda plan: plan.update(replicas=True),
                         lambda plan: plan.update(parent_manifest_sha256="changed"),
                         lambda plan: plan["partitions"][0].update(id="../../escape")):
            plan = copy.deepcopy(original)
            mutation(plan)
            collection.save(self.directory / "plan.json", plan)
            with self.assertRaises(ValueError):
                fleet.validate(self.directory)

    def test_scoped_collection_keeps_whole_comparisons_and_explicit_coverage(self):
        manifest = collection.load(self.source / 'manifest.json')
        selection = [(case['id'], 'trino') for case in manifest['cases'][:2]]
        plan = fleet.prepare(self.source, self.directory, selection=selection, operation_budget=10)
        self.assertEqual(len(plan['partitions']), 2)
        self.assertEqual(len(plan['jobs']), 18)
        self.assertEqual(len(plan['host_batches']), 9)
        self.assertTrue(all(len(batch['jobs']) == 2 for batch in plan['host_batches']))
        self.assertEqual(fleet.validate(self.directory), plan)
        plan['selection'].pop()
        collection.save(self.directory / 'plan.json', plan)
        with self.assertRaisesRegex(ValueError, 'partition'):
            fleet.validate(self.directory)

    def test_invalid_scope_or_budget_does_not_create_a_plan(self):
        for selection, budget in [([('missing', 'trino')], 1), ([], 1), (None, 0), (None, True), (None, 17)]:
            with self.subTest(selection=selection, budget=budget), self.assertRaises(ValueError):
                fleet.prepare(self.source, self.directory, selection=selection, operation_budget=budget)
            self.assertFalse(self.directory.exists())

    def test_duration_plan_preserves_the_full_replica_matrix(self):
        manifest = collection.load(self.source / "manifest.json")
        estimates = {case["id"] + "/" + language: 300 for case in manifest["cases"] for language in collection.ENGINES}
        policy = {"schema_version": 1, "source_sha256": "a" * 64, "partition_seconds": estimates,
                  "isolated_pairs": [[manifest["cases"][0]["id"], "java"]], "max_batch_seconds": 4200,
                  "bootstrap_seconds": 600}
        plan = fleet.prepare(self.source, self.directory, operation_budget=10, duration_policy=policy)
        self.assertEqual(len(plan["jobs"]), 351)
        self.assertEqual(fleet.validate(self.directory), plan)
        self.assertEqual(sorted(job for batch in plan["host_batches"] for job in batch["jobs"]),
                         sorted(job["id"] for job in plan["jobs"]))
        for batch in plan["host_batches"]:
            if any("case-0000-java" in job for job in batch["jobs"]):
                self.assertEqual(len(batch["jobs"]), 1)
        plan["host_batches"][0]["jobs"].append(plan["host_batches"][1]["jobs"][0])
        collection.save(self.directory / "plan.json", plan)
        with self.assertRaisesRegex(ValueError, "batch assignments"):
            fleet.validate(self.directory)

    def test_reject_changed_partition_even_with_updated_checksum(self):
        plan = self.prepare_small_plan()
        partition = plan["partitions"][0]
        path = self.directory / "partitions" / partition["id"] / "manifest.json"
        manifest = collection.load(path)
        manifest["selected_languages"] = ["trino"]
        collection.save(path, manifest)
        partition["manifest_sha256"] = collection.digest(path.read_bytes())
        collection.save(self.directory / "plan.json", plan)
        with self.assertRaisesRegex(ValueError, "differs from its parent"):
            fleet.validate(self.directory)

    def test_worker_rejects_wrong_host_before_running_and_passes_partition(self):
        plan = self.prepare_small_plan()
        job = plan["jobs"][0]
        results = self.root / "results"
        receipt = self.root / "build.json"
        with patch.object(collection, "host_identity", side_effect=ValueError("wrong host")), \
                patch.object(collection, "verify") as verify:
            with self.assertRaisesRegex(ValueError, "wrong host"):
                fleet.run_job(self.directory, job["id"], results, "java", "classpath", "native", receipt)
            verify.assert_not_called()

        def make_results(directory, output, *args):
            output.mkdir(parents=True)

        with patch.object(collection, "host_identity") as host, \
                patch.object(collection, "verify", side_effect=make_results) as verify, \
                patch.object(collection, "measure") as measure:
            fleet.run_job(self.directory, job["id"], results, "java", "classpath", "native", receipt)
            host.assert_called_once_with("r9g")
            partition = self.directory / "partitions" / job["partition"]
            output = results / job["id"]
            verify.assert_called_once_with(partition, output, "java", "classpath", "native")
            measure.assert_called_once_with(partition, output, "java", "classpath", "native", "r9g", receipt, None, None)
            self.assertEqual(collection.load(output / "job.json")["job"], job)

    def write_results(self, plan):
        """Synthetic evidence exercises the real raw-data reducer, without any timing process."""
        results = self.root / "results"
        for index, job in enumerate(plan["jobs"]):
            result = results / job["id"]
            result.mkdir(parents=True)
            partition = self.directory / "partitions" / job["partition"]
            manifest = collection.load(partition / "manifest.json")
            runners = {"fixture": "JVM"}
            runners_hash = collection.digest(collection.encode(runners))
            manifest_hash = collection.digest((partition / "manifest.json").read_bytes())
            receipts, observations = {}, {}
            for identity, case, language, engine, mode in collection.observations(manifest):
                trace = "".join(str(source["expected_count"]) + ":" +
                                ",".join(["61"] * source["expected_count"]) + "\n" for source in case["sources"])
                receipt = {"outcome": "completed", "trace": trace, "trace_sha256": collection.digest(trace.encode()),
                           "command": ["verify", "workload.tsv"], "operations": {}}
                for operation in collection.OPERATIONS:
                    operation_trace = collection.expected_operation_trace(case, operation)
                    receipt["operations"][operation] = {"outcome": "completed", "trace": operation_trace,
                                                         "trace_sha256": collection.digest(operation_trace.encode())}
                    row_id = identity + "/" + operation
                    raw_path = result / row_id / "raw.json"
                    raw_path.parent.mkdir(parents=True)
                    if engine == "native-re2":
                        raw = {"benchmarks": [{"run_type": "iteration", "run_name": operation,
                                               "time_unit": "ns", "real_time": 100} for _ in range(5)]}
                    else:
                        raw = [{"benchmark": collection.CLASS + "." + operation,
                                "params": {"engine": engine, "workloadFile": "workload.tsv"},
                                "forks": 5, "warmupIterations": 10, "measurementIterations": 10,
                                "warmupTime": "1 s", "measurementTime": "1 s", "threads": 1, "mode": "avgt",
                                "primaryMetric": {"scoreUnit": "ns/op", "rawData": [[100] * 10 for _ in range(5)]},
                                "secondaryMetrics": {"gc.alloc.rate.norm": {
                                    "scoreUnit": "B/op", "rawData": [[0] * 10 for _ in range(5)]}}}]
                    collection.save(raw_path, raw)
                    observations[row_id] = {"outcome": "compared", **collection.raw_samples(raw_path, engine, operation),
                                            "raw_file": str(raw_path.relative_to(result))}
                receipts[identity] = receipt
            collection.save(result / "runners.json", runners)
            collection.save(result / "verification.json", {
                "manifest_sha256": manifest_hash, "runners_sha256": runners_hash, "receipts": receipts})
            collection.save(result / "provenance.json", {
                "manifest_sha256": manifest_hash, "runners_sha256": runners_hash,
                "source_commit": "commit", "source_tree": "tree", "comparators_sha256": "pins", "jdk": "JDK25",
                "platform": job["platform"], "instance_identity": {
                    "instanceType": job["instance_type"], "instanceId": f"i-{index}"},
                "jvm_build": {"source_tree": "tree", "jvm": runners}})
            collection.save(result / "observations.json", observations)
            collection.export(partition, result)
            collection.save(result / "job.json", {
                "job": job, "plan_sha256": collection.digest((self.directory / "plan.json").read_bytes())})
        return results

    def test_aggregate_retains_every_sample_and_does_not_rewrite_evidence(self):
        plan = self.prepare_small_plan()
        results = self.write_results(plan)
        before = {path: (path.read_bytes(), path.stat().st_mtime_ns) for path in results.rglob("*.json")}
        output = self.root / "export.json"
        fleet.aggregate(self.directory, results, output)
        data = collection.load(output)
        self.assertEqual(len(data["observations"]), 9 * 15)
        self.assertEqual(len(data["comparisons"]), 9 * 10)
        self.assertEqual(len(data["hosts"]), 9)
        self.assertEqual(before, {path: (path.read_bytes(), path.stat().st_mtime_ns) for path in before})
        with self.assertRaisesRegex(ValueError, "overwrite"):
            fleet.aggregate(self.directory, results, output)

    def test_local_preflight_checks_all_partitions_without_measurement(self):
        plan = self.prepare_small_plan()
        measured = self.write_results(plan)
        evidence = {job["partition"]: collection.load(measured / job["id"] / "verification.json")
                    for job in plan["jobs"]}

        def verify_partition(partition, output, *args):
            return evidence[partition.name]

        output = self.root / "preflight"
        with patch.object(collection, "verify", side_effect=verify_partition) as verify, \
                patch.object(collection, "measure") as measure:
            fleet.verify(self.directory, output, "java", "classpath", "native")
            self.assertEqual(verify.call_count, 3)
            measure.assert_not_called()
        combined = collection.load(output / "verification.json")
        self.assertEqual(len(combined["receipts"]), 9)
        collection.validate_receipts(collection.load(self.source / "manifest.json"), combined["receipts"])

        evidence[next(iter(evidence))]["runners_sha256"] = "changed"
        with patch.object(collection, "verify", side_effect=verify_partition), \
                self.assertRaisesRegex(ValueError, "runners changed"):
            fleet.verify(self.directory, self.root / "changed-preflight", "java", "classpath", "native")

    def test_scoped_preflight_does_not_require_unselected_languages(self):
        manifest = collection.load(self.source / 'manifest.json')
        plan = fleet.prepare(self.source, self.directory, replicas=1,
                             selection=[(manifest['cases'][0]['id'], 'trino')])
        measured = self.write_results(plan)
        evidence = collection.load(measured / plan['jobs'][0]['id'] / 'verification.json')
        with patch.object(collection, 'verify', return_value=evidence), patch.object(collection, 'measure') as measure:
            fleet.verify(self.directory, self.root / 'preflight', 'java', 'classpath', 'native')
            measure.assert_not_called()
        actual = collection.load(self.root / 'preflight/verification.json')
        self.assertEqual(len(actual['receipts']), 3)

    def test_aggregate_rejects_missing_receipts_and_mixed_candidates(self):
        plan = self.prepare_small_plan()
        results = self.write_results(plan)
        result = results / plan["jobs"][0]["id"]
        job_receipt = collection.load(result / "job.json")
        collection.save(result / "job.json", {})
        with self.assertRaisesRegex(ValueError, "job receipt"):
            fleet.aggregate(self.directory, results, self.root / "export.json")
        collection.save(result / "job.json", job_receipt)
        provenance = collection.load(result / "provenance.json")
        provenance["source_commit"] = "another candidate"
        collection.save(result / "provenance.json", provenance)
        collection.export(self.directory / "partitions" / plan["jobs"][0]["partition"], result)
        with self.assertRaisesRegex(ValueError, "mixed candidates"):
            fleet.aggregate(self.directory, results, self.root / "export.json")

    def test_aggregate_rejects_cross_language_disagreement(self):
        plan = self.prepare_small_plan()
        results = self.write_results(plan)
        job = next(job for job in plan["jobs"] if job["partition"].endswith("java"))
        result = results / job["id"]
        evidence = collection.load(result / "verification.json")
        # All three engines in this partition agree, but disagree with another language.
        for receipt in evidence["receipts"].values():
            receipt["trace"] = receipt["trace"].replace("61", "62")
            receipt["trace_sha256"] = collection.digest(receipt["trace"].encode())
        collection.save(result / "verification.json", evidence)
        collection.export(self.directory / "partitions" / job["partition"], result)
        with self.assertRaisesRegex(ValueError, "partitions disagree"):
            fleet.aggregate(self.directory, results, self.root / "export.json")

    def test_aggregate_rejects_same_host_replicas(self):
        plan = self.prepare_small_plan(replicas=2)
        results = self.write_results(plan)
        for job in plan["jobs"]:
            result = results / job["id"]
            provenance = collection.load(result / "provenance.json")
            provenance["instance_identity"]["instanceId"] = "i-reused"
            collection.save(result / "provenance.json", provenance)
            collection.export(self.directory / "partitions" / job["partition"], result)
        with self.assertRaisesRegex(ValueError, "independent hosts"):
            fleet.aggregate(self.directory, results, self.root / "export.json")


if __name__ == "__main__":
    unittest.main()
