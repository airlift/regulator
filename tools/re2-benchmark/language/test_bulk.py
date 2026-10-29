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


def prepare_manifest(identity, pattern, destination, haystack=b"aab", models=("count",)):
    """A bulk manifest of one case per model; several cases are named <identity>/<index>."""
    inventory = destination.with_name(f"{destination.name}-inventory")
    inventory.mkdir()
    sources = []
    for index, model in enumerate(models, 1):
        name = identity if len(models) == 1 else f"{identity}/{index}"
        fields = {"name": [name.encode()], "model": [model.encode()], "pattern": [pattern],
                  "haystack": [haystack], "unicode": [b"true"], "case-insensitive": [b"false"]}
        payload = bulk.encode_klv(fields)
        (inventory / f"input-{index}.gz").write_bytes(gzip.compress(payload))
        sources.append({"id": name, "model": model, "population": "curated", "family": "example",
                        "klv_file": f"input-{index}.gz", "klv_sha256": collection.digest(payload),
                        "patterns": [bulk.rebar_inventory.input_identity(pattern)],
                        "haystack": bulk.rebar_inventory.input_identity(haystack),
                        "native_expected_result": 2})
    data = {"cases": sources, "rebar_commit": "fixture"}
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

    def test_fleet_partitions_keep_bulk_operation_and_read_one_input_store(self):
        destination = self.root / "fleet"
        plan = fleet.prepare(self.directory, destination)
        self.assertEqual(len(plan["partitions"]), 4)
        self.assertEqual(plan["platform_allocations"]["r9g"], {"instance_type": "r9g.large", "vcpus": 2})
        for partition in plan["partitions"]:
            manifest = collection.load(destination / "partitions" / partition["id"] / "manifest.json")
            bulk.validate_manifest(manifest, destination)
            self.assertEqual(collection.case_operations(manifest, manifest["cases"][0]), ("execute",))
            self.assertEqual(len(list(collection.observations(manifest))), 3)
        # The source and its four language mappings share one stored input, which verification reads.
        [case] = self.manifest["cases"]
        self.assertEqual([case["source_file"]], [path.relative_to(destination).as_posix()
                                                 for path in destination.rglob("*.klv")])

        class Started(Exception):
            pass

        with patch.object(collection, "runner_identity", return_value={}), \
                patch.object(collection, "verify_command", side_effect=Started) as command, \
                self.assertRaises(Started):
            fleet.verify(destination, self.root / "preflight", "java", "classes",
                         {"native-re2": "native", "native-rust": "native-rust"})
        self.assertEqual(str(destination / case["source_file"]), command.call_args.args[0][-1])

    def test_smoke_covers_each_language_and_model_and_each_isolated_comparison(self):
        source = self.root / "models"
        manifest = prepare_manifest("smoke", b"a", source, models=("count", "count", "grep"))
        first_count, second_count, grep = (case["id"] for case in manifest["cases"])
        # Each comparison fills a unit, so the smoke units measure exactly the selected comparisons.
        estimates = {case["id"] + "/" + language: fleet.work_units.UNIT_SECONDS
                     for case in manifest["cases"] for language in collection.ENGINES}
        policy = {"schema_version": 1, "source_sha256": "a" * 64, "partition_seconds": estimates,
                  "isolated_pairs": [[second_count, "java"]]}
        directory = self.root / "models-fleet"
        plan = fleet.prepare(source, directory, duration_policy=policy)
        units = fleet.work_units.load(directory)
        measured = {fleet.row_atom(row)[:2] for unit_id in fleet.smoke_units(directory, plan, units)
                    for row in units[unit_id]["owned"]}
        self.assertEqual(measured, {(case, language) for case in (first_count, grep) for language in collection.ENGINES}
                         | {(second_count, "java")})

    def test_slow_bulk_units_outlast_every_measurement_deadline(self):
        manifest = collection.load(self.directory / "manifest.json")
        manifest["protocol"] = collection.SLOW_BULK_PROTOCOL
        collection.save(self.directory / "manifest.json", manifest)
        directory = self.root / "slow-fleet"
        fleet.prepare(self.directory, directory)

        # Each JVM observation measures in two processes and the native comparator in one, all to the deadline.
        seconds = {fleet.row_atom(unit["owned"][0])[1]: float(unit["estimated_seconds"])
                   for unit in fleet.work_units.load(directory).values()}
        self.assertEqual(seconds, {"java": 75600, "trino": 75600, "re2": 63000, "rust": 63000})

    def test_bulk_worker_imports_do_not_require_controller_toml_parser(self):
        script = (
            "import sys\n"
            "sys.modules['tomllib'] = None\n"
            "import collection, fleet\n"
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
