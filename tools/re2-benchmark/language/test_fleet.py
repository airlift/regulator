import contextlib
import copy
import gzip
import io
import itertools
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


sys.path.insert(0, str(Path(__file__).parent))
import collection
import fleet


# Workers record the comparator pins of the checkout they run, as collection.py does.
COMPARATORS_SHA256 = collection.digest((collection.ROOT / "tools/re2-benchmark/baseline/comparators.tsv").read_bytes())


class TestFleet(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root / "manifest"
        collection.prepare(self.source)
        self.directory = self.root / "fleet"

    def prepare_small_plan(self):
        manifest = collection.load(self.source / "manifest.json")
        manifest["cases"] = manifest["cases"][:1]
        collection.save(self.source / "manifest.json", manifest)
        return fleet.prepare(self.source, self.directory)

    def test_complete_matrix_keeps_comparator_with_both_memory_modes(self):
        (self.source / "unrelated.txt").write_text("not part of the workload")
        plan = fleet.prepare(self.source, self.directory)
        self.assertEqual(len(plan["partitions"]), 52)
        self.assertEqual(plan["platform_allocations"], {
            "r9g": {"instance_type": "r9g.2xlarge", "vcpus": 8},
            "r8i": {"instance_type": "r8i.large", "vcpus": 2},
        })
        self.assertEqual(fleet.validate(self.directory), plan)
        self.assertNotIn("unrelated.txt", {path.name for path in self.directory.rglob("*")})
        for partition in plan["partitions"]:
            manifest = collection.load(self.directory / "partitions" / partition["id"] / "manifest.json")
            observations = list(collection.observations(manifest))
            self.assertEqual(len(observations), 3)
            self.assertEqual({row[2] for row in observations}, {partition["language"]})
            self.assertEqual([row[4] for row in observations], ["native", "safe", "safe"])
        with self.assertRaises(FileExistsError):
            fleet.prepare(self.source, self.directory)

    def assert_units_keep_comparisons_whole(self, partitions):
        units = fleet.work_units.load(self.directory)
        owner = {row: unit_id for unit_id, unit in units.items() for row in unit["owned"]}
        self.assertEqual(len(owner), sum(len(unit["owned"]) for unit in units.values()))
        atoms = {}
        for row, unit_id in owner.items():
            atoms.setdefault(fleet.row_atom(row), set()).add(unit_id)
        # Every operation of every comparison is one atom: its three observations share one unit.
        self.assertEqual(len(atoms), partitions * len(collection.OPERATIONS))
        self.assertEqual(len(owner), 3 * len(atoms))
        self.assertTrue(all(len(unit_ids) == 1 for unit_ids in atoms.values()))
        return units

    def test_units_pack_comparisons_within_fifteen_minutes(self):
        fleet.prepare(self.source, self.directory)
        units = self.assert_units_keep_comparisons_whole(52)
        self.assertTrue(all(float(unit["estimated_seconds"]) <= 900 for unit in units.values()))
        self.assertTrue(any(len(unit["owned"]) > 3 for unit in units.values()))
        table = self.directory / "unit-rows.tsv"
        table.write_text(table.read_text().replace("\towner\t", "\trepresentative\t", 1))
        with self.assertRaisesRegex(ValueError, "work units differ"):
            fleet.validate(self.directory)

    def test_smoke_covers_each_language_and_model_and_the_longest_packed_unit(self):
        manifest = collection.load(self.source / "manifest.json")
        manifest["smoke_required_pairs"] = [[manifest["cases"][-1]["id"], "trino"]]
        collection.save(self.source / "manifest.json", manifest)
        plan = fleet.prepare(self.source, self.directory)
        units = fleet.work_units.load(self.directory)
        smoke = fleet.smoke_units(self.directory, plan, units)
        measured = {fleet.row_atom(row)[:2] for unit_id in smoke for row in units[unit_id]["owned"]}
        self.assertEqual({language for _, language in measured}, set(collection.ENGINES))
        self.assertIn((manifest["cases"][-1]["id"], "trino"), measured)
        longest = max(float(unit["estimated_seconds"]) for unit in units.values() if len(unit["owned"]) > 3)
        self.assertIn(longest, {float(units[unit_id]["estimated_seconds"]) for unit_id in smoke
                                if len(units[unit_id]["owned"]) > 3})
        self.assertLess(len(smoke), len(units))

    def test_partition_keeps_the_steady_profile_and_its_full_comparison(self):
        manifest = collection.load(self.source / "manifest.json")
        manifest["protocol"] = collection.STEADY_PROTOCOLS["language-lifecycle"]
        manifest["selected_operations"] = ["reusedContains", "reusedCount"]
        collection.save(self.source / "manifest.json", manifest)
        plan = fleet.prepare(self.source, self.directory, selection=[("everyday/logLevel", "java")])
        [partition] = plan["partitions"]
        actual = collection.load(self.directory / "partitions" / partition["id"] / "manifest.json")
        self.assertEqual(collection.protocol_for(actual), manifest["protocol"])
        self.assertEqual(collection.case_operations(actual, actual["cases"][0]), ("reusedContains", "reusedCount"))
        observations = list(collection.observations(actual))
        self.assertEqual([(row[3], row[4]) for row in observations],
                         [("java", "native"), ("java", "safe"), ("jdk", "safe")])

    def test_units_charge_verification_of_every_operation_under_a_narrow_selection(self):
        manifest = collection.load(self.source / "manifest.json")
        manifest["selected_operations"] = ["reusedContains"]
        collection.save(self.source / "manifest.json", manifest)
        compatible = [case["id"] for case in manifest["cases"] if case["mappings"]["re2"]["status"] != "not-compatible"]
        fleet.prepare(self.source, self.directory, selection=[(case, "re2") for case in compatible[:3]])
        units = fleet.work_units.load(self.directory)
        # Verification still checks all five operations of each observation, so three comparisons
        # measuring one operation no longer fit one fifteen-minute unit.
        self.assertEqual(2, len(units))
        self.assertTrue(all(float(unit["estimated_seconds"]) <= fleet.work_units.UNIT_SECONDS
                            for unit in units.values()))

    def test_partitions_read_each_input_from_the_plan_store(self):
        plan = self.prepare_small_plan()
        source = collection.load(self.directory / "source/manifest.json")
        # The store holds each input once; a partition holds only its manifest.
        self.assertEqual(sorted(path.relative_to(self.directory).as_posix()
                                for path in (self.directory / "inputs").iterdir()),
                         sorted({mapping["input_file"] for mapping in source["cases"][0]["mappings"].values()}))
        for partition in plan["partitions"]:
            self.assertEqual(["manifest.json"],
                             [path.name for path in (self.directory / "partitions" / partition["id"]).iterdir()])
        first = plan["partitions"][0]
        workload = self.directory / source["cases"][0]["mappings"][first["language"]]["input_file"]

        class Started(Exception):
            pass

        with patch.object(collection, "runner_identity", return_value={}), \
                patch.object(collection, "verify_command", side_effect=Started) as command, \
                self.assertRaises(Started):
            fleet.verify(self.directory, self.root / "preflight", "java", "classpath",
                         {"native-re2": "native", "native-rust": "rust"})
        self.assertEqual(str(workload), command.call_args.args[0][-1])

    def test_reject_incomplete_duplicate_or_changed_plan(self):
        original = self.prepare_small_plan()
        for mutation in (lambda plan: plan["partitions"].pop(),
                         lambda plan: plan["partitions"].append(plan["partitions"][0]),
                         lambda plan: plan["platform_allocations"]["r9g"].update(vcpus=2),
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
        plan = fleet.prepare(self.source, self.directory, selection=selection)
        self.assertEqual(len(plan['partitions']), 2)
        self.assertEqual(fleet.validate(self.directory), plan)
        plan['selection'].pop()
        collection.save(self.directory / 'plan.json', plan)
        with self.assertRaisesRegex(ValueError, 'partition'):
            fleet.validate(self.directory)

    def test_invalid_scope_does_not_create_a_plan(self):
        for selection in [[('missing', 'trino')], []]:
            with self.subTest(selection=selection), self.assertRaises(ValueError):
                fleet.prepare(self.source, self.directory, selection=selection)
            self.assertFalse(self.directory.exists())

    def test_duration_evidence_charges_each_observation_at_its_operation_seconds(self):
        case = collection.load(self.source / "manifest.json")["cases"][0]["id"]
        row = f"{case}/java/jdk/safe/reusedCount"

        def report(comparator_ns, platforms=("r9g", "r8i", "r8g")):
            # A release report's language rows: the JDK's slowest plan platform, a slower platform the plan does
            # not measure, and a candidate faster than a tenth of an iteration.
            path = self.root / "report.json.gz"
            path.write_bytes(gzip.compress(json.dumps({"rows": [
                {"source": "language", "caseId": case, "language": "java", "platform": platform,
                 "memoryMode": "native", "operation": "reusedCount",
                 "result": {"candidateNs": 1000, "comparatorNs": comparator_ns * scale}}
                for platform, scale in (("r9g", 0.8), ("r8i", 1), ("r8g", 2)) if platform in platforms]}).encode()))
            return path

        evidence = report(17.1e9)
        plan = fleet.prepare(self.source, self.directory, duration_evidence=evidence)
        self.assertEqual({"schema_version": 2, "source_sha256": collection.digest(evidence.read_bytes()),
                          "operation_seconds": {row: 17.1}}, plan["duration_policy"])
        units = self.assert_units_keep_comparisons_whole(52)
        # Each JMH iteration of the slow comparator lasts one operation, its processes verifying the case and each
        # operation first; the candidate's observations keep their one-second iterations.
        verification = (1 + len(collection.OPERATIONS)) * fleet.unit_cost.JMH_STARTUP_SECONDS
        jmh = (1 + 5) * fleet.unit_cost.JMH_STARTUP_SECONDS
        slow, ordinary = verification + jmh + 5 * 20 * 17.1, verification + jmh + 5 * 20
        [alone] = [unit for unit in units.values() if row in unit["owned"]]
        self.assertEqual(3, len(alone["owned"]))
        self.assertAlmostEqual(2 * ordinary + slow, float(alone["estimated_seconds"]))
        unchanged = self.root / "unchanged"
        fleet.prepare(self.source, unchanged)
        self.assertAlmostEqual(
            slow - ordinary, sum(float(unit["estimated_seconds"]) for unit in units.values()) -
            sum(float(unit["estimated_seconds"]) for unit in fleet.work_units.load(unchanged).values()))
        # The unit's measurement takes the plan's frozen operation seconds, which set its process budgets.
        self.run_unit(alone["unit_id"], self.root / "attempt")
        self.assertEqual({"case-0000-java": {row: 17.1}}, self.operation_seconds)
        self.assertEqual(fleet.validate(self.directory), plan)
        # Beside its units, prepare prints how many of each language's observations the evidence covers.
        self.assertEqual("duration evidence covers java 1 of 195 rows, re2 0 of 195 rows, rust 0 of 195 rows, "
                         "trino 0 of 195 rows", fleet.duration_coverage(plan, units.values()))
        with self.assertRaisesRegex(ValueError, "invalid operation duration"):
            fleet.prepare(self.source, self.root / "nan", duration_evidence=report(float("nan")))
        # Evidence that gives none of the plan's observations an operation time, here a report of only a platform
        # the plan does not measure, is refused.
        with self.assertRaisesRegex(ValueError, "gives none of the plan's observations an operation time"):
            fleet.prepare(self.source, self.root / "other", duration_evidence=report(17.1e9, platforms=("r8g",)))
        self.assertFalse((self.root / "other").exists())
        for edit, refusal in (({row: 1.0}, "work units differ"), ({"missing/java/jdk/safe/compile": 17.1}, "duration")):
            with self.subTest(edit=edit):
                collection.save(self.directory / "plan.json", {**plan, "duration_policy": {
                    **plan["duration_policy"], "operation_seconds": edit}})
                with self.assertRaisesRegex(ValueError, refusal):
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

    def write_results(self, plan):
        """Synthetic evidence exercises the real raw-data reducer, without any timing process."""
        results = self.root / "results"
        for index, partition in enumerate(plan["partitions"]):
            self.write_partition_results(self.directory / "partitions" / partition["id"], results / partition["id"],
                                         self.directory, "r9g", "r9g.2xlarge", f"i-{index}")
        return results

    @staticmethod
    def write_partition_results(partition, result, inputs, platform, instance_type, instance_id, jdk="JDK25",
                                source_commit="commit", traced="61", classes_archive_sha256="c" * 64):
        """Write one partition's synthetic verification and measurements, and export them with the inputs of the
        plan's store."""
        result.mkdir(parents=True, exist_ok=True)
        manifest = collection.load(partition / "manifest.json")
        runners = {"fixture": "JVM"}
        runners_hash = collection.digest(collection.encode(runners))
        manifest_hash = collection.digest((partition / "manifest.json").read_bytes())
        receipts, observations = {}, {}
        for identity, case, language, engine, mode in collection.observations(manifest):
            trace = "".join(str(source["expected_count"]) + ":" +
                            ",".join([traced] * source["expected_count"]) + "\n" for source in case["sources"])
            receipt = {"outcome": "completed", "trace": trace, "trace_sha256": collection.digest(trace.encode()),
                       "command": ["verify", "workload.tsv"], "operations": {}}
            for operation in collection.OPERATIONS:
                operation_trace = collection.expected_operation_trace(case, operation)
                receipt["operations"][operation] = {"outcome": "completed", "trace": operation_trace,
                                                     "trace_sha256": collection.digest(operation_trace.encode())}
                if operation not in collection.case_operations(manifest, case):
                    continue
                row_id = identity + "/" + operation
                raw_path = result / row_id / "raw.json"
                raw_path.parent.mkdir(parents=True)
                if engine in collection.NATIVE_RUNNERS:
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
            "source_commit": source_commit, "source_tree": "tree", "comparators_sha256": COMPARATORS_SHA256, "jdk": jdk,
            "platform": platform, "instance_identity": {"instanceType": instance_type, "instanceId": instance_id},
            "jvm_build": {"source_tree": "tree", "classes_archive_sha256": classes_archive_sha256, "jvm": runners}})
        collection.save(result / "observations.json", observations)
        collection.export(partition, result, inputs=inputs)

    def run_unit(self, unit_id, root, verify=None, environment=None, replica="1", unit_attempt="host-1/1",
                 measured=lambda partition: {}, directory=None, group="1", classes="c" * 64):
        """Run one group of a unit attempt of a plan, by default this test's, through fleet.run_unit with synthetic
        verification and measurements. classes is the SHA-256 of the classes archive the host installed, and
        measured names each partition's changes to its synthetic results."""
        root.mkdir(parents=True)
        # As run-host.sh does, record the engine build archives the host installed.
        (root / "engine-build.txt").write_text(
            f"engine_build_manifest_sha256={'e' * 64}\nclasses_archive_sha256={classes}\n")
        self.unit_environment = {
            "verification_status": "verified", "benchmark_mode": "unit-worker", "baseline_campaign": "language",
            "platform": "r9g", "host_epoch": "2", "campaign_architecture": "arm", "instance_id": "i-unit",
            "instance_type": "r9g.2xlarge", "availability_zone": "us-west-2a", "instance_market_type": "spot",
            "logical_cpu_count": "8", "benchmark_heap_size": "8g", "regulator_release_version": "",
            "regulator_archive_sha256": "5" * 64,
            "comparator_manifest_sha256": COMPARATORS_SHA256, "java_runtime_version": "25.0.4+7-LTS",
            **(environment or {})}
        self.write_unit_environment(root)
        # As run-unit.sh does, record the capacity prefix the unit's evidence binds before the unit runs.
        (root / "unit-capacity.txt").write_text(
            "memory_total_bytes=34359738368\nmemory_available_before_bytes=30000000000\n"
            "swap_total_bytes=0\nswap_free_before_bytes=0\noom_kills_before=0\n")
        provenance = self.root / "candidate-provenance.tsv"
        provenance.write_text("candidate_commit\tengine_tree\n" + "a" * 40 + "\t" + "b" * 40 + "\n")
        affinity = {0, 1}

        def set_affinity(_, cpus):
            affinity.clear()
            affinity.update(cpus)

        # The unit measures the engine build's installed classes and native runners, compiling nothing.
        shared = self.root / "shared"
        receipt = {"classes_archive_sha256": classes}

        def prepare(directory, java, source, archive, candidate):
            self.assertEqual((shared, collection.ROOT, self.root / "regulator.tar.gz", provenance),
                             (directory, source, archive, candidate))
            return "installed classpath", receipt

        def measure(path, results, java, classpath, native_runners, platform, build_receipt, inputs,
                    operation_seconds):
            self.assertEqual(("installed classpath", receipt), (classpath, build_receipt))
            self.operation_seconds[results.name] = operation_seconds
            self.assertEqual(shared / "language-native", Path(native_runners["native-re2"]).parent)
            # Even a copy of the partition selecting some operations reads its inputs from the plan's store.
            self.assertEqual(directory or self.directory, inputs)
            host = self.unit_environment
            self.write_partition_results(
                path, results, inputs, host["platform"], host["instance_type"], host["instance_id"],
                **{"jdk": "Temurin 25.0.4+7", "source_commit": "a" * 40,
                   "classes_archive_sha256": build_receipt["classes_archive_sha256"], **measured(results.name)})

        # The operation seconds each partition's measurement was given, by partition.
        self.operation_seconds = {}
        reads = itertools.count()

        def cpu_ticks():
            # Each read of /proc/stat sees 1 more steal tick in 100 more ticks.
            read = next(reads)
            return read, 100 * read

        with patch.object(collection, "host_identity"), \
                patch.object(fleet, "cpu_ticks", cpu_ticks), \
                patch.object(fleet.os, "sched_getaffinity", lambda _: set(affinity), create=True), \
                patch.object(fleet.os, "sched_setaffinity", set_affinity, create=True), \
                patch.object(fleet.jvm_build, "prepare", side_effect=prepare), \
                patch.object(collection, "verify", side_effect=verify or (
                    lambda path, results, *args, inputs: results.mkdir(parents=True))), \
                patch.object(collection, "measure", side_effect=measure):
            try:
                fleet.run_unit(directory or self.directory, unit_id, replica, group, root, shared, unit_attempt,
                               "us-west-2", archive=self.root / "regulator.tar.gz", provenance=provenance)
            finally:
                # The worker's CPU affinity is restored whether or not the unit was accepted.
                self.assertEqual(affinity, {0, 1})

    def write_unit_environment(self, root):
        (root / "environment-manifest.txt").write_text(
            "".join(f"{key}={value}\n" for key, value in self.unit_environment.items()))
        if (root / "unit-evidence.tsv").exists():
            fleet.write_unit_evidence(root)

    def test_group_attempt_measures_its_partition_and_regenerates_its_receipt(self):
        plan = self.prepare_small_plan()
        units = fleet.work_units.load(self.directory)
        unit_id = next(unit_id for unit_id, unit in units.items()
                       if any(len(operations) < len(collection.OPERATIONS)
                              for operations in fleet.unit_slices(unit["owned"]).values()))
        group = fleet.work_units.unit_group(units[unit_id], "2")
        root = self.root / "attempt"
        self.run_unit(unit_id, root, group="2")
        receipt = fleet.row_protocol.read_tsv(root / "unit-receipt.tsv")[0]
        self.assertEqual(receipt, fleet.validate_unit_attempt(root, self.directory))
        self.assertEqual(
            (receipt["group_id"], receipt["owned_row_count"], receipt["candidate_commit"], receipt["engine_tree"]),
            ("2", str(len(group["owned"])), "a" * 40, "b" * 40))
        self.assertEqual(collection.load(root / "cpu-affinity.json"), [0])
        # The group is one comparison, measured in its own partition.
        [(partition, _)] = fleet.unit_partitions(plan, group)
        self.assertEqual([partition["id"]], [path.name for path in (root / "partitions").iterdir()])
        # The host's CPU steal and total ticks span only the partition's measurement.
        metadata = fleet.read_properties(root / "run-metadata.txt")
        self.assertEqual((metadata["cpu_steal_ticks"], metadata["cpu_total_ticks"]), ("1", "100"))

        original = dict(self.unit_environment)
        for field in ("instance_id", "instance_type", "logical_cpu_count", "platform", "benchmark_heap_size",
                      "comparator_manifest_sha256", "java_runtime_version", "benchmark_mode"):
            with self.subTest(field=field):
                self.unit_environment = {**original, field: "changed"}
                self.write_unit_environment(root)
                with self.assertRaises(ValueError):
                    fleet.validate_unit_attempt(root, self.directory)
        self.unit_environment = original
        self.write_unit_environment(root)
        for affinity in ([], [0, 1], [True]):
            with self.subTest(affinity=affinity):
                collection.save(root / "cpu-affinity.json", affinity)
                fleet.write_unit_evidence(root)
                with self.assertRaisesRegex(ValueError, "affinity"):
                    fleet.validate_unit_attempt(root, self.directory)
        collection.save(root / "cpu-affinity.json", [0])
        raw = next((root / "partitions").rglob("raw.json"))
        raw.write_text("[]\n")
        with self.assertRaisesRegex(ValueError, "unit evidence differs"):
            fleet.validate_unit_attempt(root, self.directory)
        fleet.write_unit_evidence(root)
        with self.assertRaises((ValueError, TypeError)):
            fleet.validate_unit_attempt(root, self.directory)

    def assert_rewritten_exports_validate(self, root, rewrite, rejection):
        """Rewrite each partition's export of the attempt at root, then validate the attempt as exported:
        accepted when rejection is None, otherwise rejected with that message."""
        for path in sorted((root / "partitions").glob("*/language-results.json")):
            exported = collection.load(path)
            rewrite(exported)
            collection.save(path, exported)
        fleet.write_unit_evidence(root)
        with patch.object(collection, "export", side_effect=lambda _slice, result, write, inputs: collection.load(
                result / "language-results.json")):
            if rejection is None:
                fleet.validate_unit_attempt(root, self.directory)
            else:
                with self.assertRaisesRegex(ValueError, rejection):
                    fleet.validate_unit_attempt(root, self.directory)

    def test_unit_attempt_requires_release_builds_of_its_native_runners(self):
        self.prepare_small_plan()
        unit_id = next(iter(fleet.work_units.load(self.directory)))
        root = self.root / "attempt"
        self.run_unit(unit_id, root)
        for change, rejection in (
                ({}, None),
                ({"binary_sha256": "2" * 64}, "native binary does not match recorded build"),
                ({"source_sha256": "0" * 64}, "built from a different source"),
                ({"runner_compile_commands": [{"command": "c++ -O0 runner.cc"}]}, "requires a release build"),
                (None, "lacks its native build receipt")):
            def rewrite(exported):
                for engine, (*_, key) in collection.NATIVE_RUNNERS.items():
                    exported["runners"][f"{key}_binary_sha256"] = "1" * 64
                    source = collection.native_build.source_digest(collection.native_target(exported["manifest"], engine))
                    exported["provenance"][f"{key}_build"] = {
                        "binary_sha256": "1" * 64, "source_sha256": source,
                        "runner_compile_commands": [{"command": "c++ -O3 -DNDEBUG runner.cc"}], **(change or {})}
                    if change is None:
                        del exported["provenance"][f"{key}_build"]
            with self.subTest(rejection=rejection):
                self.assert_rewritten_exports_validate(root, rewrite, rejection)

    def test_unit_attempt_requires_the_selected_released_artifact(self):
        self.prepare_small_plan()
        unit_id = next(iter(fleet.work_units.load(self.directory)))
        root = self.root / "attempt"
        self.run_unit(unit_id, root)
        expected = fleet.released_artifact.manifest(collection.ROOT, "1.0")
        self.unit_environment["regulator_release_version"] = "1.0"
        self.write_unit_environment(root)
        for manifest, rejection in ((expected, None),
                                    ({**expected, "jar_sha256": "0" * 64}, "selected released artifact")):
            def rewrite(exported):
                exported["provenance"]["jvm_build"]["released_artifact"] = {"manifest": manifest}
            with self.subTest(rejection=rejection):
                self.assert_rewritten_exports_validate(root, rewrite, rejection)

    def test_unit_attempt_requires_the_classes_of_its_engine_build(self):
        self.prepare_small_plan()
        unit_id = next(iter(fleet.work_units.load(self.directory)))
        root = self.root / "attempt"
        self.run_unit(unit_id, root)

        def rewrite(exported):
            exported["provenance"]["jvm_build"]["classes_archive_sha256"] = "0" * 64
        self.assert_rewritten_exports_validate(root, rewrite, "classes other than its engine build's")

    def test_unit_rejects_a_host_off_its_allocation_before_verifying(self):
        self.prepare_small_plan()
        unit_id = next(iter(fleet.work_units.load(self.directory)))
        verify = Mock()
        with self.assertRaisesRegex(ValueError, "platform allocation"):
            self.run_unit(unit_id, self.root / "attempt", verify=verify, environment={"instance_type": "r9g.large"})
        verify.assert_not_called()

    def test_unit_verdicts_choose_their_controller_status(self):
        self.prepare_small_plan()
        unit_id = next(iter(fleet.work_units.load(self.directory)))

        def disagreeing_trace(path, results, *args, inputs):
            # Every engine reports one more match per source than the case expects.
            manifest = collection.load(path / "manifest.json")
            trace = "".join(f"{source['expected_count'] + 1}:\n" for source in manifest["cases"][0]["sources"])
            receipt = {"outcome": "completed", "trace": trace, "trace_sha256": collection.digest(trace.encode())}
            collection.validate_receipts(manifest, {identity: receipt for identity, *_ in collection.observations(manifest)})

        def attempts(*phases):
            # A verifier whose attempts time out in these phases, then complete.
            outcomes = [{"outcome": "did-not-finish", "phase": phase} for phase in phases] + [{"outcome": "completed"}]
            def verify(path, results, *args, inputs):
                with patch.object(collection, "run_process", side_effect=outcomes):
                    collection.verify_command(["verifier"], results / "verifier", "native-re2", "safe")
            return verify

        def runner(script):
            return lambda path, results, *args, inputs: collection.run_process(["sh", "-c", script], results / "runner")

        def over_budget(partition):
            raise collection.BudgetOverrun(f"measurement process outran its budget: {partition}")

        semantic, protocol = fleet.SEMANTIC_FAILURE_STATUS, fleet.PROTOCOL_REJECTION_STATUS
        for index, (name, unit, status, message) in enumerate((
                ("trace mismatch", {"verify": disagreeing_trace}, semantic, "disagrees with expected count"),
                ("nonreproducible timeout", {"verify": attempts("execution")}, semantic, "nonreproducible timeout"),
                ("timeout phase changed", {"verify": attempts("compile", "execution")}, semantic, "phase changed"),
                ("runner never started", {"verify": attempts("startup")}, None, "startup timed out"),
                ("runner killed", {"verify": runner("kill -9 $$")}, None, r"runner failed \(-9\)"),
                ("launcher not found", {"verify": runner("exit 127")}, None, r"runner failed \(127\)"),
                ("measurement over budget", {"measured": over_budget}, protocol, "outran its budget"))):
            with self.subTest(verdict=name):
                if status is None:
                    with self.assertRaisesRegex(ValueError, message):
                        self.run_unit(unit_id, self.root / f"attempt-{index}", **unit)
                else:
                    with self.assertRaisesRegex(fleet.UnitRejection, message) as rejection:
                        self.run_unit(unit_id, self.root / f"attempt-{index}", **unit)
                    # The run-unit command exits with the rejection's status for the controller.
                    arguments = ["fleet.py", "run-unit", "--unit", unit_id, "--replica", "1", "--group", "1",
                                 "--unit-attempt", "1", "--region", "us-west-2"]
                    for name in ("plan-directory", "unit-directory", "shared",
                                 "source-archive", "candidate-provenance"):
                        arguments += [f"--{name}", str(self.root / name)]
                    with patch.object(fleet, "run_unit", side_effect=rejection.exception), \
                            patch.object(sys, "argv", arguments), contextlib.redirect_stderr(io.StringIO()), \
                            self.assertRaises(SystemExit) as exited:
                        fleet.main()
                    self.assertEqual(exited.exception.code, status)

    def test_plan_bundle_unpacks_as_its_shard_and_a_restart_reuses_it(self):
        self.prepare_small_plan()
        bundles = self.root / "language-plans"
        bundles.mkdir()
        bundle, sha256 = fleet.reuse_bundle(self.directory, bundles)
        self.assertEqual(sha256, fleet.write_bundle(self.directory, self.root / "renamed.tar.zst"))
        # A worker unpacks the bundle into its plans directory, as run-host.sh streams it.
        stream = subprocess.run(["zstd", "-dcq", str(bundle)], capture_output=True, check=True).stdout
        fleet.engine_build.unpack(io.BytesIO(stream), self.root / "plans")
        self.assertEqual(fleet.validate(self.root / "plans/language-lifecycle"), fleet.validate(self.directory))
        # A later start of the same plan reuses its bundle; a changed plan gets its own.
        with patch.object(fleet.engine_build, "write_archive") as write:
            self.assertEqual((bundle, sha256), fleet.reuse_bundle(self.directory, bundles))
        write.assert_not_called()
        (self.directory / "notes.txt").write_text("archived with the plan\n")
        changed, _ = fleet.reuse_bundle(self.directory, bundles)
        self.assertEqual(sorted([bundle, changed]), sorted(bundles.iterdir()))

    def run_campaign(self, measured=lambda platform, partition: {}, directories=None,
                     classes=lambda platform, replica: "c" * 64 if platform == "r9g" else "d" * 64):
        """Accept every unit of the plans of one suite, by default a two-language plan, at replicas 1 to 3 on both
        platforms, as a primary campaign does, with one synthetic worker per replica and platform running all
        its units, each unit's groups in one lease. classes names the SHA-256 of the classes archive each host
        installed, by default one per platform."""
        if directories is None:
            manifest = collection.load(self.source / "manifest.json")
            fleet.prepare(self.source, self.directory, selection=[(manifest["cases"][0]["id"], "java"),
                                                                  (manifest["cases"][0]["id"], "trino")])
            directories = [self.directory]
        plan = collection.load(directories[0] / "plan.json")
        units = sorted((unit_id, directory)
                       for directory in directories for unit_id in fleet.work_units.load(directory))
        result_root = self.root / "campaign"
        receipts = []
        for platform, architecture in (("r9g", "arm"), ("r8i", "intel")):
            allocation = plan["platform_allocations"][platform]
            for replica in ("1", "2", "3"):
                host = f"{platform}-{replica}"
                environment = {"platform": platform, "campaign_architecture": architecture,
                               "instance_type": allocation["instance_type"],
                               "logical_cpu_count": str(allocation["vcpus"]), "instance_id": f"i-{host}",
                               "host_epoch": str(len(receipts) + 1)}
                for sequence, (unit_id, directory) in enumerate(units, 1):
                    for group in fleet.work_units.load(directory)[unit_id]["groups"]:
                        session = {"platform": platform, "unit_id": unit_id, "replica_id": replica,
                                   "group_id": group["group_id"], "unit_attempt_id": f"{host}/{sequence}"}
                        attempt = fleet.unit_dispatch.unit_attempt_directory(result_root, session)
                        self.run_unit(unit_id, attempt, environment=environment, replica=replica,
                                      unit_attempt=session["unit_attempt_id"],
                                      measured=lambda partition: measured(platform, partition), directory=directory,
                                      group=group["group_id"], classes=classes(platform, replica))
                        receipts.append(fleet.row_protocol.read_tsv(attempt / "unit-receipt.tsv")[0])
        fleet.write_tsv(result_root / "accepted-units.tsv", fleet.unit_session.UNIT_RECEIPT_FIELDS, receipts)
        fleet.write_tsv(result_root / fleet.unit_session.SEMANTIC_REJECTIONS,
                        fleet.unit_session.SEMANTIC_REJECTION_FIELDS, [])
        return result_root, receipts

    def test_aggregate_exports_the_accepted_campaign_without_rewriting_evidence(self):
        result_root, receipts = self.run_campaign()

        def evidence():
            return {path: (path.read_bytes(), path.stat().st_mtime_ns)
                    for path in result_root.rglob("*") if path.is_file()}

        before = evidence()
        output = self.root / "export.json"
        fleet.aggregate(self.directory, result_root, output)
        data = collection.load(output)
        # Two comparisons of five operations, three observations and two memory-mode comparisons each,
        # on three hosts of each platform.
        self.assertEqual(len(data["observations"]), 2 * 5 * 3 * 6)
        self.assertEqual(len(data["comparisons"]), 2 * 5 * 2 * 6)
        self.assertEqual([host["receipt"] for host in data["hosts"]], receipts)
        self.assertEqual(before, evidence())
        with self.assertRaisesRegex(ValueError, "overwrite"):
            fleet.aggregate(self.directory, result_root, output)
        partial, missing = (self.root / "language-lifecycle-partial-results.json",
                            self.root / "language-lifecycle-partial-missing.tsv")
        # A complete campaign has no partial export, and a partial export never takes a complete export's name.
        with self.assertRaisesRegex(ValueError, "export is complete, not partial"):
            fleet.aggregate_partial(self.directory, result_root, partial)
        with self.assertRaisesRegex(ValueError, "must end in -partial-results.json"):
            fleet.aggregate_partial(self.directory, result_root, self.root / "partial.json")
        fleet.write_tsv(result_root / "accepted-units.tsv", fleet.unit_session.UNIT_RECEIPT_FIELDS, receipts[1:])
        with self.assertRaisesRegex(ValueError, "unit coverage failed"):
            fleet.aggregate(self.directory, result_root, self.root / "partial.json")
        # Missing one group, the campaign exports partially only the comparison it accepted completely, and lists
        # what the other lacks. A missing list that cannot be written leaves no export, so a retry succeeds.
        missing.mkdir()
        with self.assertRaises(IsADirectoryError):
            fleet.aggregate_partial(self.directory, result_root, partial)
        self.assertFalse(partial.exists())
        missing.rmdir()
        fleet.aggregate_partial(self.directory, result_root, partial)
        units = fleet.work_units.load(self.directory)

        def comparison(receipt):
            return fleet.row_atom(fleet.work_units.unit_group(
                units[receipt["unit_id"]], receipt["group_id"])["owned"][0])[:2]

        data = collection.load(partial)
        dropped = comparison(receipts[0])
        self.assertEqual(len(data["observations"]), 5 * 3 * 6)
        self.assertEqual([host["receipt"] for host in data["hosts"]],
                         [receipt for receipt in receipts if comparison(receipt) != dropped])
        row = {"case_id": dropped[0], "language": dropped[1], "platform": receipts[0]["platform"],
               "replica_id": receipts[0]["replica_id"], "unit_id": receipts[0]["unit_id"],
               "group_id": receipts[0]["group_id"]}
        self.assertEqual(("partial", [row], [row]),
                         (data["coverage"], data["missing"], fleet.row_protocol.read_tsv(missing)))
        # A semantic rejection fails the phase, though another attempt of its unit was accepted, and exports
        # nothing, completely or partially.
        fleet.write_tsv(result_root / fleet.unit_session.SEMANTIC_REJECTIONS,
                        fleet.unit_session.SEMANTIC_REJECTION_FIELDS,
                        [{key: receipts[0][key] for key in fleet.unit_session.SEMANTIC_REJECTION_FIELDS}])
        with self.assertRaisesRegex(ValueError, "semantic failure"):
            fleet.aggregate_partial(self.directory, result_root, self.root / "rejected-partial-results.json")
        fleet.write_tsv(result_root / "accepted-units.tsv", fleet.unit_session.UNIT_RECEIPT_FIELDS, receipts)
        with self.assertRaisesRegex(ValueError, "semantic failure"):
            fleet.aggregate(self.directory, result_root, self.root / "rejected.json")

    def test_aggregate_requires_agreeing_languages(self):
        result_root, _ = self.run_campaign(
            lambda platform, partition: {"traced": "62"} if partition.endswith("java") else {})
        with self.assertRaisesRegex(ValueError, "partitions disagree"):
            fleet.aggregate(self.directory, result_root, self.root / "export.json")

    def test_aggregate_requires_one_candidate(self):
        result_root, _ = self.run_campaign(
            lambda platform, partition: {"jdk": "Temurin 25.0.4+7.1"} if platform == "r8i" else {})
        with self.assertRaisesRegex(ValueError, "mixed candidates"):
            fleet.aggregate(self.directory, result_root, self.root / "export.json")

    def test_aggregate_requires_one_classes_archive_per_platform(self):
        result_root, _ = self.run_campaign(classes=lambda platform, replica: "d" * 64 if replica == "2" else "c" * 64)
        with self.assertRaisesRegex(ValueError, "mixed candidates, candidate classes"):
            fleet.aggregate(self.directory, result_root, self.root / "export.json")

    def test_local_preflight_checks_all_partitions_without_measurement(self):
        plan = self.prepare_small_plan()
        measured = self.write_results(plan)
        evidence = {partition["id"]: collection.load(measured / partition["id"] / "verification.json")
                    for partition in plan["partitions"]}

        def verify_partition(partition, output, *args, inputs):
            return evidence[partition.name]

        output = self.root / "preflight"
        runners = {"native-re2": "native", "native-rust": "rust"}
        with patch.object(collection, "verify", side_effect=verify_partition) as verify, \
                self.assertRaisesRegex(ValueError, "missing native runner: --native-rust-runner"):
            fleet.verify(self.directory, output, "java", "classpath", {"native-re2": "native"})
        verify.assert_not_called()
        self.assertFalse(output.exists())
        with patch.object(collection, "verify", side_effect=verify_partition) as verify, \
                patch.object(collection, "measure") as measure:
            fleet.verify(self.directory, output, "java", "classpath", runners)
            self.assertEqual(verify.call_count, 4)
            measure.assert_not_called()
        combined = collection.load(output / "verification.json")
        self.assertEqual(len(combined["receipts"]), 12)
        collection.validate_receipts(collection.load(self.source / "manifest.json"), combined["receipts"])

        evidence[next(iter(evidence))]["runners_sha256"] = "changed"
        with patch.object(collection, "verify", side_effect=verify_partition), \
                self.assertRaisesRegex(ValueError, "runners changed"):
            fleet.verify(self.directory, self.root / "changed-preflight", "java", "classpath", runners)

    def test_scoped_preflight_does_not_require_unselected_languages(self):
        manifest = collection.load(self.source / 'manifest.json')
        plan = fleet.prepare(self.source, self.directory, selection=[(manifest['cases'][0]['id'], 'trino')])
        measured = self.write_results(plan)
        evidence = collection.load(measured / plan['partitions'][0]['id'] / 'verification.json')
        with patch.object(collection, 'verify', return_value=evidence), patch.object(collection, 'measure') as measure:
            fleet.verify(self.directory, self.root / 'preflight', 'java', 'classpath', {'native-re2': 'native'})
            measure.assert_not_called()
        actual = collection.load(self.root / 'preflight/verification.json')
        self.assertEqual(len(actual['receipts']), 3)


if __name__ == "__main__":
    unittest.main()
