import copy
import gzip
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import bulk
import collection
import fleet
import mappings
import worker


class TestBulkSummary(unittest.TestCase):
    def fixture(self):
        manifest = {"languages": collection.ENGINES, "cases": [{"id": "test", "model": "count", "expected_result": 53,
                                "mappings": {language: {"status": "identical"} for language in collection.ENGINES}}]}
        evidence = {f"test/{language}/{engine}": {
            "execute": {"outcome": "completed", "result": "53"},
            "trace": {"outcome": "completed", "stdout_sha256": "abc"}}
                    for language, engines in collection.ENGINES.items() for engine in engines}
        return manifest, evidence

    def test_clean_probe_does_not_claim_qualification(self):
        manifest, evidence = self.fixture()
        summary = bulk.summarize(manifest, evidence)
        self.assertEqual(summary["issues"], [])
        self.assertEqual(summary["observations"], 8)
        self.assertFalse(summary["qualification_ready"])

    def test_partition_probe_expects_only_its_selected_language(self):
        manifest, evidence = self.fixture()
        manifest["selected_languages"] = ["java"]
        evidence = {key: value for key, value in evidence.items() if key.startswith("test/java/")}
        summary = bulk.summarize(manifest, evidence)
        self.assertEqual(summary["issues"], [])
        self.assertEqual(summary["expected_observations"], 2)

    def test_scalar_and_trace_failures_stay_failures(self):
        manifest, evidence = self.fixture()
        evidence["test/java/java"]["execute"]["result"] = "5"
        evidence["test/java/java"]["trace"]["stdout_sha256"] = "different"
        self.assertEqual({issue["kind"] for issue in bulk.summarize(manifest, evidence)["issues"]},
                         {"expected-result-mismatch", "engine-trace-mismatch", "cross-language-trace-mismatch"})

    def test_missing_extra_timeout_and_process_error_are_not_compatibility(self):
        manifest, evidence = self.fixture()
        evidence["unexpected"] = evidence.pop("test/java/java")
        for outcome in ("did-not-finish", "runner-error"):
            observations = copy.deepcopy(evidence)
            observations["test/java/jdk"]["execute"] = {"outcome": outcome}
            kinds = {issue["kind"] for issue in bulk.summarize(manifest, observations)["issues"]}
            self.assertEqual(kinds, {"missing-observation", "unexpected-observation", "incomplete-preflight"})

    def test_compile_does_not_compare_haystack_count(self):
        manifest, evidence = self.fixture()
        manifest["cases"][0]["model"] = "compile"
        for observation in evidence.values():
            observation["execute"]["result"] = "compiled"
        self.assertEqual(bulk.summarize(manifest, evidence)["issues"], [])

    def test_incompatibility_must_be_declared(self):
        manifest, evidence = self.fixture()
        evidence["test/java/jdk"] = {"outcome": "not-compatible"}
        self.assertEqual(bulk.summarize(manifest, evidence)["issues"][0]["kind"], "unrecorded-incompatibility")
        manifest["cases"][0]["mappings"]["java"]["status"] = "not-compatible"
        self.assertEqual(bulk.summarize(manifest, evidence)["issues"], [])


def prepare_manifest(identity, pattern, destination, haystack=b"aab"):
    inventory = destination.with_name(f"{destination.name}-inventory")
    inventory.mkdir()
    fields = {"name": [identity.encode()], "model": [b"count"], "pattern": [pattern],
              "haystack": [haystack], "unicode": [b"true"], "case-insensitive": [b"false"]}
    payload = bulk.encode_klv(fields)
    (inventory / "input.gz").write_bytes(gzip.compress(payload))
    source = {"id": identity, "model": "count", "population": "curated", "family": "example",
              "klv_file": "input.gz", "klv_sha256": collection.digest(payload),
              "patterns": [bulk.rebar_inventory.input_identity(pattern)],
              "haystack": bulk.rebar_inventory.input_identity(haystack),
              "native_expected_result": 2}
    data = {"cases": [source], "rebar_commit": "fixture"}
    collection.save(inventory / "inventory.json", data)
    with patch.object(bulk.rebar_inventory, "validate", return_value=data):
        return bulk.prepare(inventory, destination)


class TestBulkEvidence(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.directory = self.root / "manifest"
        self.manifest = prepare_manifest("example", b"a", self.directory)

    def evidence(self):
        raw = self.root / "matched.txt"
        raw.write_bytes(b"2:61,61\n")
        trace = "sha256:" + collection.digest(raw.read_bytes()) + "\n"
        execution = {"outcome": "completed", "trace": "2\n", "trace_sha256": collection.digest(b"2\n")}
        receipt = {"outcome": "completed", "trace": trace, "trace_sha256": collection.digest(trace.encode()),
                   "raw_trace_file": raw.name, "operations": {"execute": execution}}
        return {"receipts": {identity: copy.deepcopy(receipt)
                             for identity, *_ in collection.observations(self.manifest)},
                "references": {"example": {**receipt, "execution": execution}}}

    def test_full_memory_matrix_and_original_source_evidence(self):
        evidence = self.evidence()
        self.assertEqual(len(evidence["receipts"]), 12)
        bulk.validate_evidence(self.manifest, evidence, self.root)
        del evidence["receipts"]["example/java/java/native"]
        with self.assertRaisesRegex(ValueError, "missing"):
            bulk.validate_evidence(self.manifest, evidence, self.root)

    def test_raw_trace_and_reference_count_tampering_rejected(self):
        evidence = self.evidence()
        evidence["references"]["example"]["execution"]["trace"] = "3\n"
        with self.assertRaisesRegex(collection.SemanticFailure, "original native"):
            bulk.validate_evidence(self.manifest, evidence, self.root)
        evidence = self.evidence()
        evidence["receipts"]["example/java/jdk/safe"]["operations"]["execute"].update(
            trace="3\n", trace_sha256=collection.digest(b"3\n"))
        with self.assertRaisesRegex(collection.SemanticFailure, "differs from expected result"):
            bulk.validate_evidence(self.manifest, evidence, self.root)
        evidence = self.evidence()
        trace = "sha256:" + "0" * 64 + "\n"
        receipt = evidence["receipts"]["example/java/jdk/safe"]
        receipt.update(trace=trace, trace_sha256=collection.digest(trace.encode()))
        with self.assertRaisesRegex(collection.SemanticFailure, "disagree"):
            bulk.validate_evidence(self.manifest, evidence, self.root)
        evidence = self.evidence()
        (self.root / "matched.txt").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "raw trace changed"):
            bulk.validate_evidence(self.manifest, evidence, self.root)

    def test_operation_timeout_requires_two_attempts_and_never_a_trace_timeout(self):
        evidence = self.evidence()
        identity = "example/java/jdk/safe"
        attempt = {"outcome": "did-not-finish", "phase": "execution", "limit_seconds": 30}
        timeout = {**attempt, "attempts": [attempt, attempt]}
        evidence["receipts"][identity] = {**timeout, "operations": {"execute": timeout}}
        bulk.validate_evidence(self.manifest, evidence, self.root)
        timeout["attempts"] = [attempt]
        with self.assertRaisesRegex(ValueError, "not reproduced"):
            bulk.validate_evidence(self.manifest, evidence, self.root)
        evidence = self.evidence()
        evidence["receipts"][identity]["outcome"] = "did-not-finish"
        with self.assertRaisesRegex(ValueError, "trace outcome"):
            bulk.validate_evidence(self.manifest, evidence, self.root)

    def test_recorded_checksums_cannot_approve_changed_flags(self):
        case = self.manifest["cases"][0]
        mapping = case["mappings"]["java"]
        fields = bulk.rebar_inventory.decode_klv((self.directory / mapping["input_file"]).read_bytes())
        fields["case-insensitive"] = [b"true"]
        payload = bulk.encode_klv(fields)
        mapping.update(input_file="inputs/changed.klv", input_file_sha256=collection.digest(payload), status="translated")
        (self.directory / mapping["input_file"]).write_bytes(payload)
        with self.assertRaisesRegex(ValueError, "translation"):
            bulk.validate_manifest(self.manifest, self.directory)

    def test_only_rust_marks_a_malformed_haystack_compatible(self):
        manifest = prepare_manifest("example", b"a", self.root / "malformed", haystack=b"a\xffa")
        self.assertEqual({language: mapping["status"] for language, mapping in manifest["cases"][0]["mappings"].items()},
                         {"re2": "identical", "java": "not-compatible", "trino": "not-compatible", "rust": "identical"})

    def test_archived_bibleref_keeps_its_1_0_java_translation(self):
        # The 1.0 tool measured three languages and gave Java bibleref only the syntax translation.
        translate = mappings.translate
        directory = self.root / "release-1.0"
        with patch.object(collection, "ENGINES", collection.RELEASE_1_0_ENGINES), \
                patch.object(mappings, "translate",
                             lambda *args, archived=False: translate(*args, archived=True)):
            manifest = prepare_manifest("wild/bibleref/long", mappings.BIBLEREF_PATTERN, directory)
        bulk.validate_manifest(manifest, directory, archived=True)

    def test_fleet_partitions_keep_bulk_operation_and_all_source_inputs(self):
        destination = self.root / "fleet"
        plan = fleet.prepare(self.directory, destination, replicas=1)
        self.assertEqual(len(plan["jobs"]), 8)
        for partition in plan["partitions"]:
            directory = destination / "partitions" / partition["id"]
            manifest = collection.load(directory / "manifest.json")
            bulk.validate_manifest(manifest, directory)
            self.assertEqual(collection.case_operations(manifest, manifest["cases"][0]), ("execute",))
            self.assertEqual(len(list(collection.observations(manifest))), 3)

    def test_packaged_worker_needs_only_its_partition(self):
        directory = self.root / "fleet"
        plan = fleet.prepare(self.directory, directory, replicas=1)
        job = plan["jobs"][0]
        package = self.root / "package"
        expected = fleet.package_job(directory, job["id"], package)
        self.assertEqual(fleet.validate_package(package), expected)
        results = self.root / "results"
        output = results / job["id"]

        def verification(*args):
            output.mkdir(parents=True)

        with patch.object(collection, "host_identity") as host, \
                patch.object(collection, "verify", side_effect=verification) as verify, \
                patch.object(collection, "measure") as measure:
            fleet.run_package(package, results, "java", "classes", "native", self.root / "jvm.json")
            host.assert_called_once_with(job["platform"])
            verify.assert_called_once_with(package, output, "java", "classes", "native")
            self.assertEqual(measure.call_args.args[0], package)
        self.assertEqual(collection.load(output / "job.json"),
                         {"job": job, "plan_sha256": expected["plan_sha256"]})
        expected["job"]["id"] = "../unexpected"
        collection.save(package / "package.json", expected)
        with self.assertRaisesRegex(ValueError, "invalid packaged"):
            fleet.validate_package(package)

    def test_worker_builds_bulk_runner_and_restores_affinity_after_failure(self):
        directory = self.root / "fleet"
        plan = fleet.prepare(self.directory, directory, replicas=1)
        package = self.root / "package"
        fleet.package_job(directory, plan["jobs"][0]["id"], package)

        def build(destination, *args):
            destination.mkdir()
            (destination / "classpath.txt").write_text("compiled-classes\n")

        with patch.object(worker.jvm_build, "require_clean_source"), \
                patch.object(worker.jvm_build, "build", side_effect=build), \
                patch.object(worker.subprocess, "run") as native_build, \
                patch.object(collection, "verify") as verify:
            output = self.root / "worker"
            worker.run(package, output)
            self.assertEqual(native_build.call_args.kwargs["env"]["RE2_BENCHMARK_TARGETS"], "language_bulk_benchmark")
            self.assertEqual(verify.call_args.args[3], "compiled-classes")
            self.assertEqual(collection.load(output / "worker.json")["kind"], "verification-only")

        with patch.object(worker.jvm_build, "require_clean_source"), \
                patch.object(worker.jvm_build, "build", side_effect=build), \
                patch.object(worker.subprocess, "run"), \
                patch.object(collection, "host_identity"), \
                patch.object(worker.os, "sched_getaffinity", side_effect=[{3, 4}, {3}, {3}], create=True), \
                patch.object(worker.os, "sched_setaffinity", create=True) as affinity, \
                patch.object(fleet, "run_package", side_effect=ValueError("measurement failed")):
            output = self.root / "failed-worker"
            with self.assertRaisesRegex(ValueError, "measurement failed"):
                worker.run(package, output, measure=True)
            self.assertEqual([call.args for call in affinity.call_args_list], [(0, {3}), (0, {3, 4})])
            self.assertFalse((output / "worker.json").exists())

    def test_host_batches_preserve_jobs_and_independent_replicas(self):
        directory = self.root / "fleet"
        plan = fleet.prepare(self.directory, directory, replicas=2)
        assigned = [identity for batch in plan["host_batches"] for identity in batch["jobs"]]
        self.assertCountEqual(assigned, [job["id"] for job in plan["jobs"]])
        self.assertEqual(len(assigned), len(set(assigned)))
        for batch in plan["host_batches"]:
            for identity in batch["jobs"]:
                self.assertTrue(identity.startswith(batch["platform"] + "/"))
                self.assertTrue(identity.endswith(f"/replica-{batch['replica']}"))
        plan["host_batches"][0]["jobs"].pop()
        collection.save(directory / "plan.json", plan)
        with self.assertRaisesRegex(ValueError, "batch assignments"):
            fleet.validate(directory)

    def test_bulk_hosts_isolate_complete_language_comparisons(self):
        directory = self.root / "isolated-fleet"
        plan = fleet.prepare(self.directory, directory, replicas=2)
        self.assertEqual(plan["platform_allocations"]["r9g"], {
            "instance_type": "r9g.large", "vcpus": 2,
        })
        self.assertEqual(len(plan["host_batches"]), len(plan["jobs"]))
        for index, batch in enumerate(plan["host_batches"]):
            self.assertEqual(len(batch["jobs"]), 1)
            package_directory = self.root / f"isolated-package-{index}"
            package = fleet.package_batch(directory, batch["id"], package_directory)
            self.assertEqual(len(package["packages"]), 1)
            manifest = collection.load(package_directory / package["packages"][0]["directory"] / "manifest.json")
            self.assertEqual(manifest["memory_modes"], ["native", "safe"])
            self.assertEqual(manifest["protocol"], collection.PROTOCOL)
            self.assertEqual(manifest["cases"], collection.load(self.directory / "manifest.json")["cases"])

    def test_slow_bulk_duration_plan_keeps_each_comparison_on_its_own_host(self):
        manifest = collection.load(self.directory / "manifest.json")
        manifest["protocol"] = collection.SLOW_BULK_PROTOCOL
        collection.save(self.directory / "manifest.json", manifest)
        estimates = {case["id"] + "/" + language: 300
                     for case in manifest["cases"] for language in collection.ENGINES}
        policy = {"schema_version": 1, "source_sha256": "a" * 64, "partition_seconds": estimates,
                  "isolated_pairs": [], "max_batch_seconds": 4200, "bootstrap_seconds": 600}

        directory = self.root / "slow-fleet"
        plan = fleet.prepare(self.directory, directory, replicas=1, operation_budget=10, duration_policy=policy)

        self.assertEqual(len(plan["host_batches"]), len(plan["jobs"]))
        self.assertTrue(all(len(batch["jobs"]) == 1 for batch in plan["host_batches"]))
        # Each JVM observation measures in two processes and the native comparator in one, all to the deadline.
        seconds = {fleet.row_atom(unit["owned"][0])[1]: float(unit["estimated_seconds"])
                   for unit in fleet.work_units.load(directory).values()}
        self.assertEqual(seconds, {"java": 75600, "trino": 75600, "re2": 63000, "rust": 63000})

    @patch.object(fleet, "BATCH_OPERATION_BUDGET", 4)
    def test_batch_worker_builds_once_and_runs_each_language_partition(self):
        directory = self.root / "fleet"
        plan = fleet.prepare(self.directory, directory, replicas=1)
        package = self.root / "batch"
        batch = fleet.package_batch(directory, plan["host_batches"][0]["id"], package)
        self.assertEqual(len(batch["packages"]), 4)

        def build(destination, *args):
            destination.mkdir()
            (destination / "classpath.txt").write_text("compiled-classes\n")

        with patch.object(worker.jvm_build, "require_clean_source"), \
                patch.object(worker.jvm_build, "build", side_effect=build) as java_build, \
                patch.object(worker.subprocess, "run") as native_build, \
                patch.object(collection, "verify") as verify:
            worker.run(package, self.root / "worker", batch=True)
            self.assertEqual(java_build.call_count, 1)
            self.assertEqual(native_build.call_count, 1)
            self.assertEqual(native_build.call_args.kwargs["env"]["RE2_BENCHMARK_TARGETS"],
                             "language_bulk_benchmark language_rust_bulk_benchmark")
            self.assertEqual(verify.call_count, 4)
            self.assertCountEqual([call.args[0].name for call in verify.call_args_list],
                                  ["case-0000-re2", "case-0000-java", "case-0000-trino", "case-0000-rust"])
        batch["packages"].pop()
        collection.save(package / "batch.json", batch)
        with self.assertRaisesRegex(ValueError, "missing or duplicate"):
            fleet.validate_batch(package)

    def test_bulk_worker_imports_do_not_require_controller_toml_parser(self):
        script = (
            "import sys\n"
            "sys.modules['tomllib'] = None\n"
            "import collection, worker, transport, batch_acceptance\n"
            "from pathlib import Path\n"
            "directory = Path(sys.argv[1])\n"
            "collection.validate_manifest(collection.load(directory / 'manifest.json'), directory)\n")
        subprocess.run([sys.executable, "-c", script, str(self.directory)], check=True,
                       env=dict(os.environ, PYTHONPATH=str(Path(collection.__file__).parent),
                                PYTHONDONTWRITEBYTECODE="1"))

    def test_jmh_bulk_expected_result_is_verified(self):
        data = [{"benchmark": bulk.CLASS + ".execute", "params": {
                    "engine": "java", "workloadFile": "workload.klv", "expectedResult": "2"},
                 "forks": 5, "warmupIterations": 10, "measurementIterations": 10,
                 "warmupTime": "1 s", "measurementTime": "1 s", "threads": 1, "mode": "avgt",
                 "primaryMetric": {"scoreUnit": "ns/op", "rawData": [[100] * 10 for _ in range(5)]},
                 "secondaryMetrics": {"gc.alloc.rate.norm": {
                     "scoreUnit": "B/op", "rawData": [[0] * 10 for _ in range(5)]}}}]
        path = self.root / "raw.json"
        collection.save(path, data)
        collection.raw_samples(path, "java", "execute", "workload.klv", bulk.CLASS, 2)
        for expected in (None, 3):
            with self.assertRaisesRegex(ValueError, "expected result"):
                collection.raw_samples(path, "java", "execute", "workload.klv", bulk.CLASS, expected)
        with self.assertRaisesRegex(ValueError, "unexpected JMH"):
            collection.raw_samples(path, "java", "compile", "workload.klv", bulk.CLASS, 2)

    def test_bulk_verification_and_export_keep_operation_specific_rows(self):
        results = self.root / "verified"
        runners = {"fixture": "runners"}

        def verified(command, prefix, engine, mode, *, hash_trace=False):
            output = b"2:61,61\n" if hash_trace else b"2\n"
            prefix.mkdir(parents=True, exist_ok=True)
            (prefix / "verification-0.stdout").write_bytes(output)
            trace = "sha256:" + collection.digest(output) + "\n" if hash_trace else output.decode()
            return {"outcome": "completed", "trace": trace, "trace_sha256": collection.digest(trace.encode()),
                    "command": command}

        with patch.object(collection, "runner_identity", return_value=runners), \
                patch.object(collection, "verify_command", side_effect=verified):
            evidence = collection.verify(self.directory, results, "java", "classes", {"native-re2": "native", "native-rust": "native-rust"})
        bulk.validate_evidence(self.manifest, evidence, results)
        observations = {}
        for identity, case, language, engine, mode in collection.observations(self.manifest):
            row_id = identity + "/execute"
            path = results / row_id / "raw.json"
            workload = evidence["receipts"][identity]["command"][-1]
            if engine in collection.NATIVE_RUNNERS:
                raw = {"benchmarks": [{"run_type": "iteration", "run_name": "execute", "time_unit": "ns",
                                       "real_time": 100} for _ in range(5)]}
            else:
                raw = [{"benchmark": bulk.CLASS + ".execute", "params": {
                    "engine": engine, "workloadFile": workload, "expectedResult": "2"},
                    "forks": 5, "warmupIterations": 10, "measurementIterations": 10,
                    "warmupTime": "1 s", "measurementTime": "1 s", "threads": 1, "mode": "avgt",
                    "primaryMetric": {"scoreUnit": "ns/op", "rawData": [[100] * 10 for _ in range(5)]},
                    "secondaryMetrics": {"gc.alloc.rate.norm": {
                        "scoreUnit": "B/op", "rawData": [[0] * 10 for _ in range(5)]}}}]
            collection.save(path, raw)
            observations[row_id] = {"outcome": "compared", "raw_file": str(path.relative_to(results)),
                                   **collection.raw_samples(path, engine, "execute", workload, bulk.CLASS, 2)}
        collection.save(results / "observations.json", observations)
        collection.save(results / "provenance.json", {
            "manifest_sha256": evidence["manifest_sha256"], "runners_sha256": evidence["runners_sha256"],
            "source_tree": "tree", "jvm_build": {"source_tree": "tree", "jvm": runners}})
        exported = collection.export(self.directory, results)
        self.assertEqual(len(exported["observations"]), 12)
        self.assertEqual(len(exported["comparisons"]), 8)
        for row in exported["comparisons"]:
            self.assertEqual(row["operation"], "execute")
            self.assertEqual(row["input_bytes_per_operation"], 3)
            self.assertIsNone(row["matches_per_count_operation"])
        first = next(iter(observations))
        del observations[first]
        collection.save(results / "observations.json", observations)
        with self.assertRaisesRegex(ValueError, "missing"):
            collection.export(self.directory, results, write=False)


if __name__ == "__main__":
    unittest.main()
