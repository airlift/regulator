import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))
SPEC = importlib.util.spec_from_file_location("collection", Path(__file__).with_name("collection.py"))
collection = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collection)


class TestCollection(unittest.TestCase):
    def test_jmh_launcher_heap_is_separate_from_measured_forks(self):
        for mode in collection.MODES:
            command = collection.jmh_command("java", "fixture.classpath", mode)
            launcher = command[:command.index("org.openjdk.jmh.Main")]
            fork = command[command.index("-jvmArgs") + 1].split()
            self.assertIn("-Xmx256m", launcher)
            self.assertNotIn("-Xmx8g", launcher)
            self.assertIn("-Xmx8g", fork)
            self.assertIn("-Xms8g", fork)
            self.assertIn("-XX:+AlwaysPreTouch", fork)
            self.assertEqual("--enable-native-access=ALL-UNNAMED" in fork, mode == "native")
            self.assertIn("-Xmx8g", collection.java_command("java", "fixture.classpath", mode))

    def test_work_contract_distinguishes_output_assisted_counts(self):
        manifest = {"suite": "language-bulk"}
        for language in ("re2", "java"):
            for model in ("grep", "count"):
                self.assertEqual(collection.work_contract(manifest, {"model": model}, language, "execute"), "public-find-count-v2")
        for model in ("count-spans", "count-captures", "grep-captures"):
            self.assertEqual(collection.work_contract(manifest, {"model": model}, "trino", "execute", version=2), "trino-output-assisted-count-v1")
            self.assertEqual(collection.work_contract(manifest, {"model": model}, "trino", "execute"), "trino-matcher-count-v3")
            self.assertEqual(collection.work_contract(manifest, {"model": model}, "re2", "execute"), "bulk-matched-outputs-v1")
        self.assertEqual(collection.work_contract(manifest, {"model": "count"}, "trino", "execute"), "bulk-matched-outputs-v1")
        self.assertEqual(collection.work_contract(manifest, {"model": "compile"}, "java", "compile"), "public-pattern-lifecycle-v1")

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.directory = self.root / "manifest"
        self.manifest = collection.prepare(self.directory)

    def test_shared_corpus_has_all_languages_modes_and_lifecycle_operations(self):
        self.assertEqual(len(self.manifest["cases"]), 13)
        self.assertEqual(len(list(collection.observations(self.manifest))), 13 * 9)
        case = self.manifest["cases"][0]
        self.assertEqual(case["id"], "everyday/logLevel")
        self.assertEqual(len(case["sources"]), 8)
        self.assertEqual(set(case["mappings"]), {"re2", "java", "trino"})
        self.assertEqual(case["input_bytes_per_operation"],
                         sum(source["input_bytes"] for source in case["sources"]) / 8)

    def test_remeasurement_cannot_restamp_existing_cohort(self):
        results = self.root / "old-results"
        results.mkdir()
        provenance = results / "provenance.json"
        provenance.write_text('{"source_commit":"old"}')
        with self.assertRaisesRegex(ValueError, "will not overwrite.*call contract"):
            collection.measure(self.directory, results, "java", "classes", "native", "r9g", "unused")
        self.assertEqual(provenance.read_text(), '{"source_commit":"old"}')

    def test_new_measurement_rejects_historical_bulk_verifier(self):
        manifest = {'suite': 'language-bulk', 'cases': [{'mappings': {
            'trino': {'verifier': 'bulk-result-and-matched-bytes-v1'}}}]}
        with patch.object(collection.platform, 'system', return_value='Linux'), \
                patch.object(collection, 'load', return_value=manifest), \
                patch.object(collection, 'validate_manifest'):
            with self.assertRaisesRegex(ValueError, 'boundary-and-byte verification'):
                collection.measure(self.directory, self.root / 'results', 'java', 'classes', 'native', 'r9g', 'unused')

    def test_reject_missing_duplicate_and_unrecorded_translations(self):
        broken = copy.deepcopy(self.manifest)
        broken["cases"].append(broken["cases"][0])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            collection.validate_manifest(broken, self.directory)

        broken = copy.deepcopy(self.manifest)
        del broken["cases"][0]["mappings"]["java"]
        with self.assertRaisesRegex(ValueError, "missing language"):
            collection.validate_manifest(broken, self.directory)
        broken = copy.deepcopy(self.manifest)
        broken["cases"][0]["mappings"]["java"]["pattern"] = "wrong"
        with self.assertRaisesRegex(ValueError, "does not match"):
            collection.validate_manifest(broken, self.directory)

    def test_tsv_quotes_are_literal_pattern_and_input_bytes(self):
        case = next(case for case in self.manifest["cases"] if case["id"] == "everyday/quotedField")
        self.assertEqual(case["source_pattern"], '"([^"]*)"')
        source = next(source for source in case["sources"] if source["id"] == "exact")
        self.assertEqual(bytes.fromhex(source["hex"]), b'"value"')

    def test_input_tampering_is_not_an_incompatibility(self):
        case = self.manifest["cases"][0]
        path = self.directory / case["mappings"]["re2"]["input_file"]
        path.write_text(path.read_text() + "0\t0\t\n")
        with self.assertRaisesRegex(ValueError, "checksum"):
            collection.validate_manifest(self.manifest, self.directory)

    def receipts(self):
        receipts = {}
        for identity, case, *_ in collection.observations(self.manifest):
            trace = "".join(str(source["expected_count"]) + ":" + ",".join(["61"] * source["expected_count"]) + "\n"
                            for source in case["sources"])
            operations = {}
            for operation in collection.OPERATIONS:
                operation_trace = collection.expected_operation_trace(case, operation)
                operations[operation] = {"outcome": "completed", "trace": operation_trace,
                                         "trace_sha256": collection.digest(operation_trace.encode())}
            receipts[identity] = {"outcome": "completed", "trace": trace, "trace_sha256": collection.digest(trace.encode()),
                                  "operations": operations}
        return receipts

    def test_verification_fails_closed_on_mismatch_missing_or_error(self):
        receipts = self.receipts()
        collection.validate_receipts(self.manifest, receipts)
        first = next(iter(receipts))
        broken = copy.deepcopy(receipts)
        trace = broken[first]["trace"].replace("61", "62")
        broken[first].update(trace=trace, trace_sha256=collection.digest(trace.encode()))
        with self.assertRaisesRegex(ValueError, "engines disagree"):
            collection.validate_receipts(self.manifest, broken)
        broken[first]["outcome"] = "verification-failed"
        with self.assertRaisesRegex(ValueError, "not publishable"):
            collection.validate_receipts(self.manifest, broken)
        del broken[first]
        with self.assertRaisesRegex(ValueError, "missing"):
            collection.validate_receipts(self.manifest, broken)

    def test_timeout_is_not_missing_or_incompatible(self):
        receipts = self.receipts()
        first = next(iter(receipts))
        attempt = {"outcome": "did-not-finish", "phase": "execution", "limit_seconds": 30}
        receipts[first] = {**attempt, "attempts": [attempt, attempt], "operations": receipts[first]["operations"]}
        collection.validate_receipts(self.manifest, receipts)
        receipts[first]["attempts"] = [attempt]
        with self.assertRaisesRegex(ValueError, "not reproduced"):
            collection.validate_receipts(self.manifest, receipts)

    def test_process_errors_do_not_become_timeouts(self):
        prefix = self.root / "process"
        with self.assertRaisesRegex(ValueError, "runner failed"):
            collection.run_process([sys.executable, "-c", "raise RuntimeError('wrong result')"], prefix)
        self.assertIn("wrong result", prefix.with_suffix(".stderr").read_text())

    def test_timeout_kills_isolated_child(self):
        previous = collection.PROTOCOL["execution_timeout_seconds"]
        self.addCleanup(collection.PROTOCOL.__setitem__, "execution_timeout_seconds", previous)
        collection.PROTOCOL["execution_timeout_seconds"] = 0.1
        receipt = collection.run_process(
            [sys.executable, "-c", "import sys,time; print('phase=execution', file=sys.stderr, flush=True); time.sleep(10)"],
            self.root / "timeout")
        self.assertEqual(receipt["outcome"], "did-not-finish")
        self.assertEqual(receipt["phase"], "execution")
        self.assertEqual(receipt["limit_seconds"], 0.1)

    def test_measurement_budget_covers_all_forks_of_a_slow_verified_operation(self):
        protocol = collection.PROTOCOL
        per_fork_budget = (protocol["startup_timeout_seconds"] + protocol["compile_timeout_seconds"] +
                           (1 + protocol["warmup_iterations"] + protocol["measurement_iterations"]) *
                           protocol["execution_timeout_seconds"])
        self.assertGreaterEqual(protocol["measurement_timeout_seconds"], protocol["forks"] * per_fork_budget)

    def test_measurement_is_not_killed_at_the_old_aggregate_deadline(self):
        with patch.object(collection.subprocess, "Popen") as launch, \
                patch.object(collection.time, "monotonic", side_effect=[0, 200, 500]), \
                patch.object(collection.time, "sleep"), \
                patch.object(collection, "terminate_process_group") as terminate:
            launch.return_value.poll.side_effect = [None, None, 0, 0]
            launch.return_value.returncode = 0
            receipt = collection.run_process(["java", "jmh"], self.root / "slow-measurement", measuring=True)
        self.assertEqual(receipt["outcome"], "completed")
        terminate.assert_not_called()

    def test_measurement_still_kills_the_process_group_at_its_frozen_limit(self):
        limit = collection.PROTOCOL["measurement_timeout_seconds"]
        with patch.object(collection.subprocess, "Popen") as launch, \
                patch.object(collection.time, "monotonic", side_effect=[0, limit + 1]), \
                patch.object(collection, "terminate_process_group") as terminate:
            launch.return_value.poll.side_effect = [None, 0]
            receipt = collection.run_process(["java", "jmh"], self.root / "measurement-timeout", measuring=True)
        self.assertEqual(receipt["outcome"], "did-not-finish")
        self.assertEqual(receipt["phase"], "measurement")
        self.assertEqual(receipt["limit_seconds"], limit)
        terminate.assert_called_once_with(launch.return_value)

    def test_jmh_samples_and_allocation_retained_without_flattening(self):
        data = [{"benchmark": collection.CLASS + ".compile",
                 "params": {"engine": "jdk", "workloadFile": "input.tsv"},
                 "forks": 5, "warmupIterations": 10, "measurementIterations": 10,
                 "warmupTime": "1 s", "measurementTime": "1 s", "threads": 1, "mode": "avgt",
                 "primaryMetric": {"scoreUnit": "ns/op", "rawData": [[1, 2] * 5 for _ in range(5)]},
                 "secondaryMetrics": {"gc.alloc.rate.norm": {"scoreUnit": "B/op", "score": 100, "rawData": [[99, 101] * 5 for _ in range(5)]}}}]
        path = self.root / "jmh.json"
        path.write_text(json.dumps(data))
        observed = collection.raw_samples(path, "jdk", "compile")
        with self.assertRaisesRegex(ValueError, "JVM arguments"):
            collection.raw_samples(path, "jdk", "compile", expected_jvm_arguments=collection.measured_jvm_arguments("safe"))
        data[0]["jvmArgs"] = collection.measured_jvm_arguments("safe")
        path.write_text(json.dumps(data))
        collection.raw_samples(path, "jdk", "compile", expected_jvm_arguments=collection.measured_jvm_arguments("safe"))
        self.assertEqual(observed["samples_ns"], data[0]["primaryMetric"]["rawData"])
        self.assertEqual(observed["allocation"]["rawData"], [[99, 101] * 5 for _ in range(5)])
        with self.assertRaisesRegex(ValueError, "different engine"):
            collection.raw_samples(path, "joni", "compile")
        with self.assertRaisesRegex(ValueError, "different engine or workload"):
            collection.raw_samples(path, "jdk", "compile", "different.tsv")
        data[0]["primaryMetric"]["rawData"].pop()
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "missing per-fork"):
            collection.raw_samples(path, "jdk", "compile")

    def test_native_aggregates_are_not_additional_repetitions(self):
        rows = [{"run_type": "iteration", "run_name": "compile", "time_unit": "ns", "real_time": index + 1}
                for index in range(5)]
        rows.append({"run_type": "aggregate", "real_time": 100})
        path = self.root / "native.json"
        path.write_text(json.dumps({"benchmarks": rows}))
        self.assertEqual(collection.raw_samples(path, "native-re2", "compile")["samples_ns"], [[1], [2], [3], [4], [5]])

    def test_nonfinite_samples_rejected(self):
        path = self.root / "invalid.json"
        path.write_text('{"x": NaN}')
        with self.assertRaises(ValueError):
            collection.load(path)

    def test_export_preserves_complete_matrix_and_rejects_missing_or_changed_results(self):
        results = self.root / "results"
        results.mkdir()
        receipts = self.receipts()
        for receipt in receipts.values():
            receipt["command"] = ["verify", "workload.tsv"]
        runners = {"fixture": "runner identities"}
        runners_hash = collection.digest(collection.encode(runners))
        manifest_hash = collection.digest((self.directory / "manifest.json").read_bytes())
        collection.save(results / "runners.json", runners)
        collection.save(results / "verification.json", {
            "manifest_sha256": manifest_hash, "runners_sha256": runners_hash, "receipts": receipts})
        collection.save(results / "provenance.json", {
            "manifest_sha256": manifest_hash, "runners_sha256": runners_hash, "source_tree": "tree",
            "jvm_build": {"source_tree": "tree", "jvm": runners}})
        observations = {}
        for identity, case, language, engine, mode in collection.observations(self.manifest):
            for operation in collection.OPERATIONS:
                row_id = identity + "/" + operation
                path = results / row_id / "raw.json"
                path.parent.mkdir(parents=True)
                if engine == "native-re2":
                    raw = {"benchmarks": [
                        {"run_type": "iteration", "run_name": operation, "time_unit": "ns", "real_time": 100}
                        for _ in range(5)]}
                else:
                    raw = [{"benchmark": collection.CLASS + "." + operation,
                            "params": {"engine": engine, "workloadFile": "workload.tsv"},
                            "forks": 5, "warmupIterations": 10, "measurementIterations": 10,
                            "warmupTime": "1 s", "measurementTime": "1 s", "threads": 1, "mode": "avgt",
                            "primaryMetric": {"scoreUnit": "ns/op", "rawData": [[100] * 10 for _ in range(5)]},
                            "secondaryMetrics": {"gc.alloc.rate.norm": {
                                "scoreUnit": "B/op", "rawData": [[0] * 10 for _ in range(5)]}}}]
                collection.save(path, raw)
                observations[row_id] = {"outcome": "compared", **collection.raw_samples(path, engine, operation),
                                        "raw_file": str(path.relative_to(results))}
        collection.save(results / "observations.json", observations)
        collection.export(self.directory, results)
        exported = collection.load(results / "language-results.json")
        self.assertEqual(len(exported["comparisons"]), 13 * 3 * 2 * 5)
        self.assertEqual(len(exported["observations"]), 13 * 9 * 5)
        compilation = next(row for row in exported["comparisons"] if row["operation"] == "compile")
        self.assertIsNone(compilation["input_bytes_per_operation"])
        self.assertEqual(exported["manifest"], collection.load(self.directory / "manifest.json"))
        self.assertEqual(exported["verification"]["receipts"], receipts)
        self.assertTrue(all("work_contract" not in row for row in exported["comparisons"]))
        provenance = collection.load(results / "provenance.json")
        collection.save(results / "provenance.json", {**provenance, "benchmark_contract_version": 2})
        current = collection.export(self.directory, results, write=False)
        self.assertTrue(all(row["work_contract"] == "public-pattern-lifecycle-v1" for row in current["comparisons"]))
        collection.save(results / "provenance.json", {**provenance, "benchmark_contract_version": 3})
        current = collection.export(self.directory, results, write=False)
        self.assertTrue(all(row["work_contract"] == "public-pattern-lifecycle-v1" for row in current["comparisons"]))
        collection.save(results / "provenance.json", {**provenance, "benchmark_contract_version": 4})
        with self.assertRaisesRegex(ValueError, "unsupported benchmark contract version"):
            collection.export(self.directory, results, write=False)
        collection.save(results / "provenance.json", provenance)
        first = next(iter(observations))
        observations[first]["samples_ns"][0][0] = 200
        collection.save(results / "observations.json", observations)
        with self.assertRaisesRegex(ValueError, "samples changed"):
            collection.export(self.directory, results)
        del observations[first]
        collection.save(results / "observations.json", observations)
        with self.assertRaisesRegex(ValueError, "missing"):
            collection.export(self.directory, results)

    def test_operation_evidence_rejects_missing_wrong_results_and_wrong_timeout_phase(self):
        receipts = self.receipts()
        first = next(iter(receipts))
        original = copy.deepcopy(receipts)
        del receipts[first]["operations"]["compile"]
        with self.assertRaisesRegex(ValueError, "missing operation"):
            collection.validate_receipts(self.manifest, receipts)
        receipts = copy.deepcopy(original)
        receipts[first]["operations"]["reusedContains"]["trace"] = "wrong\n"
        with self.assertRaisesRegex(ValueError, "operation verification failed"):
            collection.validate_receipts(self.manifest, receipts)
        receipts = copy.deepcopy(original)
        attempt = {"outcome": "did-not-finish", "phase": "execution", "limit_seconds": 30}
        receipts[first]["operations"]["compile"] = {**attempt, "attempts": [attempt, attempt]}
        with self.assertRaisesRegex(ValueError, "invalid operation timeout phase"):
            collection.validate_receipts(self.manifest, receipts)

    def test_relative_measurement_exports_only_operation_specific_timeouts(self):
        results = self.root / "results"
        results.mkdir()
        receipts = self.receipts()
        for identity, case, language, *_ in collection.observations(self.manifest):
            workload = str((self.directory / case["mappings"][language]["input_file"]).resolve())
            receipts[identity]["command"] = ["verify", workload]
        first = next(iter(receipts))
        attempt = {"outcome": "did-not-finish", "phase": "execution", "limit_seconds": 30}
        timeout = {**attempt, "attempts": [attempt.copy(), attempt.copy()]}
        # Full iteration times out, while compile and contains have their own successful evidence.
        receipts[first] = {**timeout, "command": receipts[first]["command"], "operations": receipts[first]["operations"]}
        for operation in ("singleUseCount", "reusedCount"):
            receipts[first]["operations"][operation] = copy.deepcopy(timeout)
        manifest_hash = collection.digest((self.directory / "manifest.json").read_bytes())
        native = self.root / "native"
        native.write_bytes(b"fixture executable")
        runners = {"fixture": "JVM", "native_binary_sha256": collection.digest(native.read_bytes())}
        runners_hash = collection.digest(collection.encode(runners))
        collection.save(results / "runners.json", runners)
        collection.save(results / "verification.json", {
            "manifest_sha256": manifest_hash, "runners_sha256": runners_hash, "receipts": receipts})
        jvm_receipt = self.root / "jvm-build.json"
        collection.save(jvm_receipt, {"jvm": {"fixture": "JVM"}, "source_tree": "tree"})
        collection.save(native.with_suffix(".build.json"), {
            "binary_sha256": runners["native_binary_sha256"],
            "source_sha256": collection.digest(Path(collection.__file__).with_name("language_benchmark.cc").read_bytes()),
            "runner_compile_commands": [{"command": "c++ -O3 -DNDEBUG"}]})

        def host_command(command, **kwargs):
            if command[:2] == ["git", "status"]:
                return b""
            if command == ["java", "-version"]:
                return b"25.0.4+7"
            if command == ["git", "rev-parse", "HEAD^{tree}"]:
                return b"tree"
            return b"fixture"

        measured = []

        def measurement_process(command, prefix, **kwargs):
            measured.append(command)
            if "org.openjdk.jmh.Main" in command:
                operation = next(value for value in command if value.startswith("^")).split(r"\.")[-1].removesuffix("$")
                engine = next(value.removeprefix("engine=") for value in command if value.startswith("engine="))
                workload = next(value.removeprefix("workloadFile=") for value in command if value.startswith("workloadFile="))
                raw = [{"benchmark": collection.CLASS + "." + operation,
                        "jvmArgs": command[command.index("-jvmArgs") + 1].split(),
                        "params": {"engine": engine, "workloadFile": workload},
                        "forks": 5, "warmupIterations": 10, "measurementIterations": 10,
                        "warmupTime": "1 s", "measurementTime": "1 s", "threads": 1, "mode": "avgt",
                        "primaryMetric": {"scoreUnit": "ns/op", "rawData": [[100] * 10 for _ in range(5)]},
                        "secondaryMetrics": {"gc.alloc.rate.norm": {"scoreUnit": "B/op", "rawData": [[0] * 10 for _ in range(5)]}}}]
                path = Path(command[command.index("-rff") + 1])
            else:
                operation = command[2]
                path = Path(next(value.split("=", 1)[1] for value in command if value.startswith("--benchmark_out=")))
                raw = {"benchmarks": [{"run_type": "iteration", "run_name": operation, "time_unit": "ns", "real_time": 100}
                                      for _ in range(5)]}
            collection.save(path, raw)
            return {"outcome": "completed", "command": command}

        # Keep the callable's arguments relative. Mock only host admission and actual benchmark execution.
        with patch.object(collection.platform, "system", return_value="Linux"), \
                patch.object(collection, "runner_identity", return_value=runners), \
                patch.object(collection.subprocess, "check_output", side_effect=host_command), \
                patch.object(collection, "host_identity", return_value={"instanceType": "r9g.xlarge"}), \
                patch.object(collection.jvm_build, "validate_receipt"), \
                patch.object(collection, "run_process", side_effect=measurement_process):
            collection.measure(Path(os.path.relpath(self.directory)), Path(os.path.relpath(results)), "java", "classpath",
                               str(native), "r9g", jvm_receipt)
        exported = collection.load(results / "language-results.json")
        self.assertEqual(len(measured), 585 - 2)
        for operation in ("compile", "singleUseContains", "reusedContains"):
            row = exported["observations"][first + "/" + operation]
            self.assertEqual(row["outcome"], "compared")
            self.assertFalse(Path(row["raw_file"]).is_absolute())
        for operation in ("singleUseCount", "reusedCount"):
            self.assertEqual(exported["observations"][first + "/" + operation], timeout)
        data = collection.load(results / "observations.json")
        data[first + "/reusedCount"]["limit_seconds"] = 999
        collection.save(results / "observations.json", data)
        with self.assertRaisesRegex(ValueError, "changed operation timeout evidence"):
            collection.export(self.directory, results)


if __name__ == "__main__":
    unittest.main()
