import copy
import json
import hashlib
import io
import tarfile
from pathlib import Path
import tempfile
import os
import subprocess
import unittest

import run


class TestDiagnostics(unittest.TestCase):
    def setUp(self):
        self.plan = json.loads((Path(__file__).parent / "pilot-core.json").read_text())

    def test_long_native_controls_and_fixed_cpu_memory_diagnostics(self):
        for size in ("small", "large"):
            plan = json.loads((Path(__file__).parent / ("pilot-memory-" + size + ".json")).read_text())
            run.validate_plan(plan)
            self.assertEqual(plan["native_measurement_seconds"], 20)
            self.assertEqual(plan["measurement_iterations"], 60)
            self.assertEqual(plan["primary_cpu_allowances"], ["0,1"])
            for case in plan["cases"]:
                command = run.jmh_command("classes", case, "native-access", "0,1", "1s", plan, Path("/result"))
                self.assertIn("-XX:ActiveProcessorCount=2", command[command.index("-jvmArgs") + 1])
                self.assertEqual(command[command.index("-i") + 1], "60")
        for duration in (0, 61, True, "20"):
            with self.subTest(duration=duration), self.assertRaisesRegex(ValueError, "native measurement"):
                run.validate_plan({**self.plan, "native_measurement_seconds": duration})

    def test_extended_windows_keep_bounded_command_deadlines(self):
        self.assertEqual(run.timing_timeout(self.plan, "1s"), 900)
        protocol = {**self.plan, "forks": 3, "warmup_iterations": 300, "measurement_iterations": 300}
        run.validate_plan(protocol)
        self.assertEqual(run.timing_timeout(protocol, "1s"), 1965)
        self.assertEqual(run.timing_timeout(protocol, "50ms"), 900)
        for key in ("warmup_iterations", "measurement_iterations"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                run.validate_plan({**protocol, key: 301})
        case = protocol["cases"][0]
        command = run.jmh_command("classes", case, "native-access", "0,1", "1s", protocol, Path("/result"))
        self.assertEqual(command[command.index("-wi") + 1], "300")
        self.assertEqual(command[command.index("-i") + 1], "300")

    def test_native_controls_use_exact_named_coverage(self):
        names = ["Search_Easy0_CachedDFA/262144/threads:1", "Search_AltMatch_CachedBitState/16777216/threads:1"]
        rows = [{"run_type": "iteration", "run_name": name, "time_unit": "ns", "cpu_time": 100.0}
                for name in names for _ in range(5)]
        run.validate_native_controls(rows, names)
        with self.assertRaises(ValueError):
            run.validate_native_controls(rows[:-1], names)
        with self.assertRaises(ValueError):
            run.validate_native_controls(rows, names + ["missing"])
        rows[-1]["run_name"] = names[0]
        with self.assertRaises(ValueError):
            run.validate_native_controls(rows, names)

    def test_lifecycle_inputs_verify_the_exact_operation(self):
        payload = b"the exact frozen lifecycle TSV bytes"
        checksum = hashlib.sha256(payload).hexdigest()
        case = {"id": "literal", "input_format": "lifecycle", "input_sha256": checksum,
                "benchmark": "io.airlift.regulator.BenchmarkLanguageComparison.singleUseContains",
                "parameters": {"engine": "re2"}, "routes": ["native-access"], "gc": "g1"}
        plan = {**self.plan, "cases": [case], "instrumented_cases": ["literal"]}
        run.validate_plan(plan)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "inputs.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                member = tarfile.TarInfo(checksum + ".tsv")
                member.size = len(payload)
                output.addfile(member, io.BytesIO(payload))
            run.unpack_inputs(plan, archive, root / "inputs")
            workload = case["parameters"]["workloadFile"]
            self.assertEqual(Path(workload).read_bytes(), payload)
            java, native = run.language_verification_commands(case, ["java", "-Xmx256m"], "classes", "native", workload)
            self.assertEqual(java[-4:], ["--verify-operation", "re2", workload, "singleUseContains"])
            self.assertEqual(native, ["native", "--verify-operation", workload, "singleUseContains"])
        for mutation in ({"input_format": "unknown"}, {"benchmark": "io.airlift.regulator.BenchmarkLanguageComparison.unknown"}):
            invalid = {**case, **mutation, "parameters": {"engine": "re2"}}
            with self.assertRaises(ValueError):
                run.validate_plan({**plan, "cases": [invalid]})
        missing = {k: v for k, v in case.items() if k != "input_sha256"}
        with self.assertRaisesRegex(ValueError, "checksummed input"):
            run.validate_plan({**plan, "cases": [missing]})
        bulk = {"parameters": {"engine": "jdk"}}
        java, native = run.language_verification_commands(bulk, ["java"], "classes", "native", "workload.klv")
        self.assertEqual(java[-3:], ["--trace", "jdk", "workload.klv"])
        self.assertEqual(native, ["native", "--trace", "workload.klv"])

    def test_reject_ambiguous_or_duplicate_selections(self):
        run.validate_plan(self.plan)
        for key, value in (("benchmark", ".*"), ("routes", ["object-row", "object-row"]),
                           ("parameters", {"workload": "a,b"})):
            plan = copy.deepcopy(self.plan)
            plan["cases"][0][key] = value
            with self.assertRaises(ValueError):
                run.validate_plan(plan)
        self.plan["cases"].append(self.plan["cases"][0])
        with self.assertRaises(ValueError):
            run.validate_plan(self.plan)

    def test_real_forks_receive_heap_mode_and_instrumentation(self):
        case = self.plan["cases"][0]
        command = run.jmh_command("classes", case, "object-row", "0,1", "1s", self.plan, Path("/tmp/result"))
        self.assertEqual(command[:3], ["taskset", "--cpu-list", "0,1"])
        self.assertEqual(command[command.index("-f") + 1], "5")
        flags = command[command.index("-jvmArgs") + 1]
        self.assertIn("-Xmx8g", flags)
        self.assertNotIn("--enable-native-access", flags)
        self.assertNotIn("-prof", command)
        self.assertNotIn("LogCompilation", flags)
        diagnostic = run.jmh_command("classes", case, "native-access", "0", "1s", self.plan, Path("/tmp/result"), True)
        flags = diagnostic[diagnostic.index("-jvmArgs") + 1]
        self.assertIn("--enable-native-access=ALL-UNNAMED", flags)
        self.assertIn("-XX:+LogCompilation", flags)
        self.assertIn("-prof", diagnostic)

    def test_reject_incomplete_wrong_or_invalid_results(self):
        case = self.plan["cases"][0]
        result = {"benchmark": case["benchmark"], "params": case["parameters"],
                  "primaryMetric": {"rawData": [[1.0] * 10 for _ in range(5)]}}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "jmh.json"
            path.write_text(json.dumps([result]))
            run.validate_result(path, case, self.plan)
            for mutation in ("missing_fork", "wrong_case", "nonfinite"):
                invalid = copy.deepcopy(result)
                if mutation == "missing_fork":
                    invalid["primaryMetric"]["rawData"].pop()
                elif mutation == "wrong_case":
                    invalid["benchmark"] = "wrong"
                else:
                    invalid["primaryMetric"]["rawData"][0][0] = float("nan")
                path.write_text(json.dumps([invalid]))
                with self.assertRaises(ValueError):
                    run.validate_result(path, case, self.plan)

    def test_aws_hook_rejects_qualification_and_partial_routes(self):
        wrapper = run.ROOT / "tools/re2-benchmark/aws/run-campaign.sh"
        base = {**os.environ, "BENCHMARK_DIAGNOSTIC_PLAN": "pilot-core",
                "CAMPAIGN_MODE": "baseline-shard", "CAMPAIGN_SHARD_ID": "trino-final-line",
                "REGULATOR_RELEASE_VERSION": "1.0", "BASELINE_PROTOCOL": "smoke"}
        for override in ({"BASELINE_PROTOCOL": "qualification"}, {"BASELINE_SELECTED_ROUTE": "object-row"},
                         {"BENCHMARK_DIAGNOSTIC_PLAN": "../../bad"}):
            result = subprocess.run(["bash", str(wrapper)], env={**base, **override},
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Diagnostics require", result.stderr)

    def test_checked_input_transport_rejects_mutation_and_traversal(self):
        payload = b"example workload"
        checksum = hashlib.sha256(payload).hexdigest()
        plan = {"cases": [{"input_sha256": checksum, "parameters": {}}]}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for index, (name, value) in enumerate(((checksum + ".klv", payload),
                                                 (checksum + ".klv", b"changed"),
                                                 ("../escape", payload))):
                archive = root / (str(index) + ".tar.gz")
                with tarfile.open(archive, "w:gz") as files:
                    info = tarfile.TarInfo(name)
                    info.size = len(value)
                    files.addfile(info, io.BytesIO(value))
                if index == 0:
                    run.unpack_inputs(plan, archive, root / "valid")
                    self.assertEqual(Path(plan["cases"][0]["parameters"]["workloadFile"]).read_bytes(), payload)
                else:
                    with self.assertRaises(ValueError):
                        run.unpack_inputs(plan, archive, root / str(index))

    def test_extension_plan_and_instrumented_selection(self):
        plan = json.loads((Path(__file__).parent / "pilot-extended.json").read_text())
        run.validate_plan(plan)
        plan["instrumented_cases"] = ["missing"]
        with self.assertRaises(ValueError):
            run.validate_plan(plan)

    def test_language_controls_preserve_explicit_g1(self):
        plan = json.loads((Path(__file__).parent / "pilot-language-g1.json").read_text())
        run.validate_plan(plan)
        for case in plan["cases"]:
            for cpus in ("0", "0,1"):
                command = run.jmh_command("classes", case, "object-row", cpus, "1s", plan, Path("/tmp/result"))
                self.assertIn("-XX:+UseG1GC", command[command.index("-jvmArgs") + 1])

    def test_factorial_controls_hold_gc_and_processor_count(self):
        plan = json.loads((Path(__file__).parent / "pilot-jvm-controls.json").read_text())
        run.validate_plan(plan)
        run.validate_shard(plan, "traditional-extra-easy2")
        with self.assertRaises(ValueError):
            run.validate_shard(plan, "trino-final-line")
        case = next(case for case in plan["cases"] if case.get("active_processors") == 1)
        for cpus in ("0", "0,1"):
            command = run.jmh_command("classes", case, "native-access", cpus, "1s", plan, Path("/tmp/result"))
            flags = command[command.index("-jvmArgs") + 1]
            self.assertIn("-XX:+UseG1GC", flags)
            self.assertIn("-XX:ActiveProcessorCount=1", flags)
        case["active_processors"] = 0
        with self.assertRaises(ValueError):
            run.validate_plan(plan)

    def test_long_warmup_is_frozen_and_bounded(self):
        plan = json.loads((Path(__file__).parent / "pilot-warmup.json").read_text())
        run.validate_plan(plan)
        command = run.jmh_command("classes", plan["cases"][0], "object-row", "0", "1s", plan, Path("/tmp/result"))
        self.assertEqual(command[command.index("-wi") + 1], "60")
        self.assertEqual(command[command.index("-i") + 1], "20")
        self.assertNotIn("-prof", command)
        plan["warmup_iterations"] = 301
        with self.assertRaises(ValueError):
            run.validate_plan(plan)

    def test_baseline_validation_selects_the_exact_replacement_protocol(self):
        plan = json.loads((Path(__file__).parent / "pilot-baseline-validation.json").read_text())
        run.validate_plan(plan)
        self.assertEqual(sum(len(case["routes"]) for case in plan["cases"]), 6)
        self.assertEqual(plan["primary_cpu_allowances"], ["0,1"])
        self.assertEqual((plan["forks"], plan["warmup_iterations"], plan["measurement_iterations"]), (5, 20, 20))
        plan["primary_cpu_allowances"] = ["0,1", "0,1"]
        with self.assertRaises(ValueError):
            run.validate_plan(plan)

    def test_command_failures_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "failed"
            with self.assertRaises(RuntimeError):
                run.execute(["/bin/sh", "-c", "exit 4"], output)
            self.assertEqual(json.loads((output / "execution.json").read_text())["returncode"], 4)
            with self.assertRaises(FileExistsError):
                run.execute(["/bin/true"], output)


if __name__ == "__main__":
    unittest.main()
