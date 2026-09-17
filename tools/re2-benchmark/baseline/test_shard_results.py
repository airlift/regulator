#!/usr/bin/env python3

import csv
import contextlib
import importlib.util
import io
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


SOURCE = Path(__file__).with_name("shard_results.py")
MANIFEST = Path(__file__).with_name("rows.tsv")
SPECIFICATION = importlib.util.spec_from_file_location("shard_results", SOURCE)
SHARD_RESULTS = importlib.util.module_from_spec(SPECIFICATION)
SPECIFICATION.loader.exec_module(SHARD_RESULTS)


class TestShardResults(unittest.TestCase):
    def testRebarFilterSelectsOneModel(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            manifest = Path(temporary_directory) / "rows.tsv"
            common = {
                "shard_id": "rebar-a",
                "suite": "rebar-curated",
                "system": "joni",
                "comparator": "joni-rebar-differential",
                "expected_result": "native-differential",
                "allocation_contract": "not-measured",
                "workload_checksum": "corpus",
            }
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, [
                {
                    **common,
                    "row_id": "count/joni",
                    "benchmark": "curated/count-only",
                    "parameters": "model=count;workload=count",
                },
                {
                    **common,
                    "row_id": "spans/joni",
                    "benchmark": "curated/spans-only",
                    "parameters": "model=count-spans;workload=spans",
                },
            ])

            completed = subprocess.run(
                [
                    str(SOURCE),
                    "rebar-filter",
                    "--manifest", str(manifest),
                    "--shard", "rebar-a",
                    "--system", "joni",
                    "--model", "count",
                ],
                check=True,
                capture_output=True,
                text=True)

            self.assertIn("curated/count", completed.stdout)
            self.assertNotIn("spans-only", completed.stdout)

    def testJoniUsesParameterIsolatedProperForksOnComparatorClasspaths(self):
        script = SOURCE.with_name("run-shard.sh").read_text(encoding="utf-8")

        self.assertIn("run_joni_jmh_with_classpath()", script)
        self.assertIn('run_joni_jmh_with_classpath "${PINNED_TRINO_CLASSPATH}"', script)
        self.assertIn('run_joni_jmh_with_classpath "${SPECIAL_CLASSPATH}"', script)
        self.assertEqual(2, script.count('JMH_INPUT_FILES=("${output}")'))
        joni_runner = script.split("run_joni_jmh_with_classpath()", 1)[1].split("\n}\n", 1)[0]
        self.assertIn('-f "${JONI_JMH_FORKS}"', joni_runner)
        self.assertNotIn("-f 0", joni_runner)
        self.assertIn("JONI_JMH_FORKS=1", script)
        self.assertNotIn("JONI_JMH_FORKS=5", joni_runner)

    def testRebarJoniUsesIndependentOrderedInvocation(self):
        script = SOURCE.with_name("run-shard.sh").read_text(encoding="utf-8")
        dispatch = SOURCE.with_name("shard-dispatch.tsv").read_text(encoding="utf-8")

        self.assertIn("run_rebar_primary_phase()", script)
        self.assertIn("run_rebar_joni_phase()", script)
        self.assertIn(
            "run_ordered_joni_comparison run_rebar_primary_phase run_rebar_joni_phase",
            script)
        self.assertIn("rebar-primary-measurements.csv", script)
        self.assertIn("rebar-joni-measurements.csv", script)
        self.assertNotIn("regulator/re2|joni/trino", script)
        for shard in (f"rebar-{letter}" for letter in "abcdefghijklmnopqrst"):
            row = next(line for line in dispatch.splitlines() if line.startswith(f"{shard}\t"))
            self.assertIn(
                "regulator-native-access,native-re2-before,native-re2-after,joni", row)

    def testSpecialClasspathRunsAndRegistersFullProtocolOutputs(self):
        source = SOURCE.with_name("run-shard.sh").read_text(encoding="utf-8")
        runner_start = source.index("run_jmh_with_classpath()")
        runner_end = source.index("\n}\n", runner_start) + 3
        runner = source[runner_start:runner_end]
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "result.json"
            script = (
                "set -euo pipefail\n"
                "PROTOCOL=qualification\n"
                "JMH_PROCESS_COUNT=2\n"
                "JMH_PROCESS_TIME=50ms\n"
                "JMH_PROCESS_WARMUP_ITERATIONS=1\n"
                "JMH_PROCESS_MEASUREMENT_ITERATIONS=1\n"
                "CLASSPATH=ordinary\n"
                "CPU_LIST=0\n"
                "jmh_process_jvm_arguments=()\n"
                "jmh_launcher_jvm_arguments=(-Xms64m -Xmx256m)\n"
                "jmh_host_compiler_arguments=()\n"
                "route_jvm_arguments=()\n"
                "jmh_fork_arguments=-Xmx1g\n"
                "prepare_jmh_host_compiler_arguments() { jmh_host_compiler_arguments=(); }\n"
                "full_protocol_benchmarks() { printf 'example.Benchmark.measure\\tsize=2\\tsize=1\\n'; }\n"
                "taskset() { shift 2; \"$@\"; }\n"
                "java() { while (($#)); do if [[ $1 == -rff ]]; then printf '[]' > \"$2\"; fi; shift; done; }\n"
                f"{runner}\n"
                f"run_jmh_with_classpath special '^example\\.Benchmark\\.measure$' {output}\n"
                "printf '%s\\n' \"${JMH_INPUT_FILES[@]}\"\n")

            result = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=True)

            output_base = output.with_suffix("")
            self.assertEqual(
                [
                    f"{output_base}-process-1.json",
                    f"{output_base}-process-2.json",
                    f"{output_base}-bounded-1-process-1.json",
                    f"{output_base}-bounded-1-process-2.json",
                    f"{output_base}-full-1.json",
                ],
                result.stdout.splitlines())

    def testReverseSuiteOrderingUsesPortableAwkVariable(self):
        script = SOURCE.with_name("run-shard.sh").read_text(encoding="utf-8")

        self.assertNotIn("for (index=count", script)
        completed = subprocess.run(
            ["awk", "NR > 1 {rows[++count]=$0} END {for (row_number=count; row_number >= 1; row_number--) print rows[row_number]}"],
            input="header\nfirst\nsecond\n",
            text=True,
            capture_output=True,
            check=True)
        self.assertEqual("second\nfirst\n", completed.stdout)

    def testAwsWrapperPropagatesProtocolQualificationSelection(self):
        script = (SOURCE.parents[1] / "aws" / "run-campaign.sh").read_text(encoding="utf-8")

        self.assertIn("BASELINE_PROTOCOL_QUALIFICATION=${BASELINE_PROTOCOL_QUALIFICATION:-true}", script)
        self.assertIn("export BASELINE_PROTOCOL_QUALIFICATION='${BASELINE_PROTOCOL_QUALIFICATION}'", script)

    def testAwsWrapperDerivesReplacementAllocationFromTheFrozenProtocol(self):
        script = (SOURCE.parents[1] / "aws" / "run-campaign.sh").read_text(encoding="utf-8")

        self.assertIn('--print-allocation "${CAMPAIGN_PLATFORM}"', script)
        self.assertIn("BENCHMARK_EXPECTED_VCPUS=${replacement_vcpus}", script)

    def testAwsHostDisablesProtocolQualificationForVerificationOnlyPreparation(self):
        script = (SOURCE.parents[1] / "aws" / "run-host.sh").read_text(encoding="utf-8")
        start = script.index('if [[ -n "${BENCHMARK_REMEASUREMENT_PARTITION:-}${BENCHMARK_DIAGNOSTIC_PLAN:-}" ]]')
        block = script[start:script.index("    fi", start)]

        self.assertIn("export BASELINE_VERIFICATION_ONLY=true", block)
        self.assertIn("export BASELINE_PROTOCOL_QUALIFICATION=false", block)

    def testRebarJoniRoutesRequirePinnedTrinoProvenance(self):
        campaign = (SOURCE.parents[1] / "aws" / "run-campaign.sh").read_text(encoding="utf-8")
        host = (SOURCE.parents[1] / "aws" / "run-host.sh").read_text(encoding="utf-8")

        self.assertIn("trino-like | lifecycle | lifecycle-shared-cold | rebar-*", campaign)
        self.assertIn("rebar-*) JONI_EVIDENCE_SCOPE=rebar-bulk-text-comparison", campaign)
        self.assertIn("trino-like | lifecycle | lifecycle-shared-cold | rebar-*)", host)

    def testPlanOnlyIncludesSpecializedAllowances(self):
        expected_metadata = {
            "trino-operations": {
                "candidate_jmh_row_count": "184",
                "joni_jmh_row_count": "184",
                "joni_jmh_forks": "1",
                "jmh_static_duration_estimate_seconds": "1706",
                "route_static_duration_estimate_seconds": "2870",
            },
            "traditional-extra-easy2": {
                "protocol_qualification_estimate_seconds": "0",
                "native_system_row_count": "32",
                "native_measurement_estimate_seconds": "160",
                "native_build_startup_allowance_seconds": "900",
                "jmh_execution_protocol": "multi-row-process-full",
            },
            "rebar-a": {
                "rebar_system_row_count": "48",
                "rebar_joni_row_count": "12",
                "rebar_primary_verification_estimate_seconds": "1080",
                "rebar_measurement_window_seconds_per_row": "10",
                "rebar_measurement_estimate_seconds": "480",
                "rebar_build_startup_allowance_seconds": "900",
                "rebar_joni_verification_estimate_seconds": "360",
                "pinned_trino_preparation_allowance_seconds": "900",
                "route_static_duration_estimate_seconds": "3944",
            },
            "retained-memory": {
                "pinned_trino_preparation_allowance_seconds": "900",
                "retained_memory_census_count": "2",
                "retained_memory_census_estimate_seconds": "1800",
                "additional_duration_allowance_seconds": "2700",
                "route_static_duration_estimate_seconds": "2924",
            },
        }
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            for shard, expected in expected_metadata.items():
                result_directory = root / shard
                subprocess.run(
                    [str(SOURCE.with_name("run-shard.sh")), shard, "qualification", "native-access",
                     str(result_directory)],
                    check=True,
                    capture_output=True,
                    text=True,
                    env={**os.environ, "BASELINE_PLAN_ONLY": "true"})
                metadata = dict(
                    line.split("=", 1)
                    for line in (result_directory / "run-metadata.txt").read_text().splitlines())
                for key, value in expected.items():
                    self.assertEqual(value, metadata[key], f"{shard} {key}")

    def testEveryFormalHostPlanFitsNinetyMinutes(self):
        dispatch_path = SOURCE.with_name("shard-dispatch.tsv")
        with dispatch_path.open(newline="", encoding="utf-8") as input_file:
            dispatch = list(csv.DictReader(input_file, delimiter="\t"))

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            for row in dispatch:
                for route, handler in (
                        ("native-access", row["native_access_handler"]),
                        ("object-row", row["object_row_handler"])):
                    if handler == "-":
                        continue
                    result_directory = root / f"{row['shard_id']}-{route}"
                    subprocess.run(
                        [str(SOURCE.with_name("run-shard.sh")), row["shard_id"],
                         "qualification", route, str(result_directory)],
                        check=True,
                        capture_output=True,
                        text=True,
                        env={**os.environ, "BASELINE_PLAN_ONLY": "true"})
                    metadata = dict(
                        line.split("=", 1)
                        for line in (result_directory / "run-metadata.txt").read_text().splitlines())
                    if row["shard_id"].startswith("traditional-extra-"):
                        self.assertEqual("multi-row-process-full", metadata["jmh_execution_protocol"])
                        self.assertEqual("false", metadata["protocol_qualification_required"])
                    self.assertLessEqual(
                        float(metadata["route_static_duration_estimate_seconds"]),
                        float(metadata["route_static_duration_limit_seconds"]),
                        f"{row['shard_id']} {route}")

    def testSmokeUsesGoogleBenchmarkDurationSyntax(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_directory = Path(temporary_directory) / "traditional-search"
            subprocess.run(
                [str(SOURCE.with_name("run-shard.sh")), "traditional-search", "smoke", "native-access",
                 str(result_directory)],
                check=True,
                capture_output=True,
                text=True,
                env={**os.environ, "BASELINE_PLAN_ONLY": "true"})
            metadata = dict(
                line.split("=", 1)
                for line in (result_directory / "run-metadata.txt").read_text().splitlines())

            self.assertEqual("3", metadata["native_repetitions"])
            self.assertEqual("0.2s", metadata["native_minimum_time"])
            self.assertEqual("3", metadata["jmh_process_measurement_iterations"])
            self.assertEqual("1", metadata["calibration_prime_invocations"])

    def testObjectRowTrinoOperationsUsesCompleteSpecializedHandler(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_directory = Path(temporary_directory) / "trino-operations"
            subprocess.run(
                [str(SOURCE.with_name("run-shard.sh")), "trino-operations", "qualification", "object-row",
                 str(result_directory)],
                check=True,
                capture_output=True,
                text=True,
                env={**os.environ, "BASELINE_PLAN_ONLY": "true"})
            metadata = dict(
                line.split("=", 1)
                for line in (result_directory / "run-metadata.txt").read_text().splitlines())

            self.assertEqual("trino-operations", metadata["handler"])
            self.assertEqual("184", metadata["candidate_jmh_row_count"])
            self.assertEqual("0", metadata["joni_jmh_row_count"])
            self.assertEqual("1", metadata["jmh_invocation_count"])
            self.assertEqual("0", metadata["pinned_trino_preparation_allowance_seconds"])

    def testResultChecksumIgnoresRawTimingButBindsSemanticContract(self):
        expected = {field: field for field in SHARD_RESULTS.MANIFEST_FIELDS}
        first = SHARD_RESULTS.result_checksum(expected)
        self.assertEqual(first, SHARD_RESULTS.result_checksum({**expected, "score": "different timing"}))
        for field in SHARD_RESULTS.MANIFEST_FIELDS:
            with self.subTest(field=field):
                self.assertNotEqual(first, SHARD_RESULTS.result_checksum({**expected, field: "different"}))

    def testBoundedProtocolRetainsImpreciseExactRowAsRejected(self):
        expected = {
            **{field: field for field in SHARD_RESULTS.MANIFEST_FIELDS},
            "row_id": "shard/benchmark/system",
            "shard_id": "shard",
            "system": "regulator-native-access",
            "allocation_contract": "recorded",
            "expected_result": "benchmark-self-check",
            "workload_checksum": "candidate-source",
        }
        result = {
            "primaryMetric": {
                "score": 10,
                "scoreUnit": "ns/op",
                "rawData": [[1, 20, 1, 20, 1]],
            },
            "secondaryMetrics": {
                "gc.alloc.rate.norm": {"score": 0, "scoreUnit": "B/op"},
            },
        }

        row = SHARD_RESULTS.observed_jmh_row(expected, result, 0.05)

        self.assertEqual("precision-rejected", row["outcome"])

    def testJmhResultsCanMergeOneProfiledProcess(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            profiled = root / "profiled.json"
            unprofiled = root / "unprofiled.json"
            common = {
                "benchmark": "benchmark",
                "primaryMetric": {"score": 1, "scoreUnit": "ns/op", "rawData": [[1]]},
            }
            profiled.write_text(json.dumps([{
                **common,
                "secondaryMetrics": {
                    "gc.alloc.rate.norm": {
                        "score": 2,
                        "scoreUnit": "B/op",
                        "rawData": [[2]],
                    },
                },
            }]))
            unprofiled.write_text(json.dumps([common]))

            with self.assertRaisesRegex(ValueError, "secondary metrics differ"):
                SHARD_RESULTS.jmh_results([profiled, unprofiled])

            result = SHARD_RESULTS.jmh_results(
                [profiled, unprofiled], allow_missing_secondary_metrics=True)

            self.assertEqual([[1], [1]], result[("benchmark", "-")]["primaryMetric"]["rawData"])
            self.assertEqual(
                [[2]],
                result[("benchmark", "-")]["secondaryMetrics"]["gc.alloc.rate.norm"]["rawData"])

    def testJmhResultsCanMergeDisjointProcessSets(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)

            def write(path, benchmark, raw_data):
                path.write_text(json.dumps([{
                    "benchmark": benchmark,
                    "primaryMetric": {"score": 1, "scoreUnit": "ns/op", "rawData": raw_data},
                }]))
                return path

            paths = [
                write(root / "bounded-1.json", "bounded", [[1]]),
                write(root / "bounded-2.json", "bounded", [[2]]),
                write(root / "full.json", "full", [[3], [4]]),
            ]

            results = SHARD_RESULTS.jmh_results(
                paths,
                require_one_process_group_per_file=False,
                allow_disjoint_process_sets=True,
                expected_process_groups=2)

            self.assertEqual({("bounded", "-"), ("full", "-")}, set(results))
            self.assertEqual([[1], [2]], results[("bounded", "-")]["primaryMetric"]["rawData"])
            self.assertEqual([[3], [4]], results[("full", "-")]["primaryMetric"]["rawData"])

            with self.assertRaisesRegex(ValueError, "expected 3"):
                SHARD_RESULTS.jmh_results(
                    paths,
                    require_one_process_group_per_file=False,
                    allow_disjoint_process_sets=True,
                    expected_process_groups=3)

    def testMappedJmhAcceptsDisjointFullProtocolProcessSets(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "manifest.tsv"
            semantic_receipt = root / "semantic.tsv"
            output = root / "observed.tsv"
            inputs = [root / "first.json", root / "second.json"]

            common = {
                "shard_id": "mapped",
                "suite": "mapped-suite",
                "system": "candidate",
                "benchmark": "logical",
                "comparator": "absolute",
                "expected_result": "self-check",
                "allocation_contract": "not-measured",
                "workload_checksum": "test",
            }
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, [
                {**common, "row_id": "row/first", "parameters": "value=first"},
                {**common, "row_id": "row/second", "parameters": "value=second"},
            ])
            SHARD_RESULTS.write_tsv(semantic_receipt, SHARD_RESULTS.SEMANTIC_FIELDS, [{
                "route": "native-access",
                "tests": "test",
                "outcome": "accepted",
                "semantic_result_digest": "0" * 64,
            }])
            for path, value in zip(inputs, ("first", "second"), strict=True):
                path.write_text(json.dumps([{
                    "benchmark": "example.Benchmark.actual",
                    "params": {"value": value},
                    "primaryMetric": {
                        "score": 1,
                        "scoreUnit": "ns/op",
                        "rawData": [[1]],
                    },
                    "secondaryMetrics": {},
                }]))

            SHARD_RESULTS.normalize_mapped_jmh(SimpleNamespace(
                manifest=manifest,
                shard="mapped",
                suite="mapped-suite",
                system="candidate",
                input=inputs,
                output=output,
                semantic_receipt=semantic_receipt,
                benchmark_class="example.Benchmark",
                method_map=["actual=logical"],
                allow_missing_secondary_metrics=False,
                allow_mixed_process_sets=True,
                allow_multiple_process_groups=False,
                expected_process_groups=1,
                maximum_relative_standard_error=None))

            self.assertEqual(
                ["row/first", "row/second"],
                [row["row_id"] for row in SHARD_RESULTS.load_observed(output)])

    def testJmhRuntimeChargesForkedComparatorStartupPerRow(self):
        arguments = SimpleNamespace(
            rows=10,
            invocations=1,
            processes=5,
            forked_rows=4,
            forked_processes=1,
            warmup_iterations=1,
            measurement_iterations=1,
            iteration_seconds=1,
            startup_seconds=2,
            calibration_invocations=0,
            calibration_rows=0,
            calibration_processes=0,
            calibration_warmup_iterations=0,
            calibration_measurement_iterations=0,
            calibration_iteration_seconds=1,
            additional_seconds=0,
            maximum_seconds=1_000)

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            SHARD_RESULTS.estimate_jmh_runtime(arguments)
        self.assertEqual("126", output.getvalue().strip())

    def testNormalizeJmhChecksumIsStableAcrossTimingFiles(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rows.tsv"
            expected = {
                "row_id": "shard/benchmark/system",
                "shard_id": "shard",
                "suite": "suite",
                "system": "regulator-native-access",
                "benchmark": "io.airlift.regulator.BenchmarkExample.run",
                "parameters": "-",
                "comparator": "absolute",
                "expected_result": "benchmark-self-check",
                "allocation_contract": "recorded",
                "workload_checksum": "candidate-source",
            }
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, [expected])
            semantic_receipt = root / "semantic.tsv"
            SHARD_RESULTS.write_tsv(semantic_receipt, SHARD_RESULTS.SEMANTIC_FIELDS, [{
                "route": "native-access",
                "tests": "TestExample",
                "outcome": "accepted",
                "semantic_result_digest": "3" * 64,
            }])

            checksums = []
            for index, score in enumerate((10, 20)):
                result = root / f"result-{index}.json"
                result.write_text(json.dumps([{
                    "benchmark": expected["benchmark"],
                    "primaryMetric": {"score": score, "scoreUnit": "ns/op", "rawData": [[score]]},
                    "secondaryMetrics": {
                        "gc.alloc.rate.norm": {"score": index, "scoreUnit": "B/op"},
                    },
                }]))
                output = root / f"observed-{index}.tsv"
                arguments = type("Arguments", (), {
                    "manifest": manifest,
                    "shard": "shard",
                    "suite": "suite",
                    "system": "regulator-native-access",
                    "input": [result],
                    "output": output,
                    "semantic_receipt": semantic_receipt,
                    "allow_traditional_unpaired": False,
                })()
                SHARD_RESULTS.normalize_jmh(arguments)
                with output.open(newline="", encoding="utf-8") as input_file:
                    checksums.append(next(csv.DictReader(input_file, delimiter="\t"))["result_checksum"])

            self.assertEqual(checksums[0], checksums[1])

    def testNormalizeJmhRequiresAllocationMetricForRecordedContract(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rows.tsv"
            expected = {
                "row_id": "row",
                "shard_id": "shard",
                "suite": "suite",
                "system": "regulator-native-access",
                "benchmark": "benchmark",
                "parameters": "-",
                "comparator": "absolute",
                "expected_result": "result",
                "allocation_contract": "recorded",
                "workload_checksum": "workload",
            }
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, [expected])
            result = root / "result.json"
            result.write_text(json.dumps([{
                "benchmark": "benchmark",
                "primaryMetric": {"score": 1, "scoreUnit": "ns/op", "rawData": [[1]]},
            }]))
            semantic_receipt = root / "semantic.tsv"
            SHARD_RESULTS.write_tsv(semantic_receipt, SHARD_RESULTS.SEMANTIC_FIELDS, [{
                "route": "native-access",
                "tests": "TestExample",
                "outcome": "accepted",
                "semantic_result_digest": "4" * 64,
            }])
            arguments = type("Arguments", (), {
                "manifest": manifest,
                "shard": "shard",
                "suite": "suite",
                "system": "regulator-native-access",
                "input": [result],
                "output": root / "observed.tsv",
                "semantic_receipt": semantic_receipt,
                "allow_traditional_unpaired": False,
            })()

            with self.assertRaisesRegex(ValueError, "missing gc.alloc.rate.norm"):
                SHARD_RESULTS.normalize_jmh(arguments)

    def testNormalizeJmhCombinesFiveIndependentProcessFiles(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rows.tsv"
            expected = {
                "row_id": "row",
                "shard_id": "shard",
                "suite": "suite",
                "system": "regulator-native-access",
                "benchmark": "benchmark",
                "parameters": "size=8",
                "comparator": "absolute",
                "expected_result": "result",
                "allocation_contract": "recorded",
                "workload_checksum": "workload",
            }
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, [expected])
            semantic_receipt = root / "semantic.tsv"
            SHARD_RESULTS.write_tsv(semantic_receipt, SHARD_RESULTS.SEMANTIC_FIELDS, [{
                "route": "native-access",
                "tests": "TestExample",
                "outcome": "accepted",
                "semantic_result_digest": "5" * 64,
            }])
            inputs = []
            for process in range(1, 6):
                path = root / f"process-{process}.json"
                path.write_text(json.dumps([{
                    "benchmark": "benchmark",
                    "params": {"size": "8"},
                    "primaryMetric": {
                        "score": process,
                        "scoreUnit": "ns/op",
                        "rawData": [[process]],
                    },
                    "secondaryMetrics": {
                        "gc.alloc.rate.norm": {
                            "score": process * 10,
                            "scoreUnit": "B/op",
                            "rawData": [[process * 10]],
                        },
                    },
                }]))
                inputs.append(path)
            output = root / "observed.tsv"
            arguments = type("Arguments", (), {
                "manifest": manifest,
                "shard": "shard",
                "suite": "suite",
                "system": "regulator-native-access",
                "input": inputs,
                "output": output,
                "semantic_receipt": semantic_receipt,
                "allow_traditional_unpaired": False,
            })()

            SHARD_RESULTS.normalize_jmh(arguments)

            with output.open(newline="", encoding="utf-8") as input_file:
                row = next(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual("3", row["score"])
            self.assertEqual("30", row["allocation_bytes"])

    def testNormalizeJmhRejectsMultipleProcessGroupsInOneFile(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            result = Path(temporary_directory) / "result.json"
            result.write_text(json.dumps([{
                "benchmark": "benchmark",
                "primaryMetric": {
                    "score": 3,
                    "scoreUnit": "ns/op",
                    "rawData": [[1], [2], [3], [4], [5]],
                },
            }]))

            with self.assertRaisesRegex(ValueError, "expected one"):
                SHARD_RESULTS.jmh_results([result])

    def testNormalizeMappedJmhAcceptsFiveProperForkGroupsInOneFile(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rows.tsv"
            expected = {
                "row_id": "row",
                "shard_id": "shard",
                "suite": "suite",
                "system": "joni",
                "benchmark": "contains",
                "parameters": "size=8",
                "comparator": "regulator",
                "expected_result": "result",
                "allocation_contract": "recorded",
                "workload_checksum": "workload",
            }
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, [expected])
            result = root / "result.json"
            result.write_text(json.dumps([{
                "benchmark": "example.Benchmark.containsJoni",
                "params": {"size": "8"},
                "primaryMetric": {
                    "score": 3,
                    "scoreUnit": "ns/op",
                    "rawData": [[1], [2], [3], [4], [5]],
                },
                "secondaryMetrics": {
                    "gc.alloc.rate.norm": {
                        "score": 30,
                        "scoreUnit": "B/op",
                        "rawData": [[10], [20], [30], [40], [50]],
                    },
                },
            }]))
            semantic_receipt = root / "semantic.tsv"
            SHARD_RESULTS.write_tsv(semantic_receipt, SHARD_RESULTS.SEMANTIC_FIELDS, [{
                "route": "native-access",
                "tests": "TestExample",
                "outcome": "accepted",
                "semantic_result_digest": "6" * 64,
            }])
            output = root / "observed.tsv"
            arguments = type("Arguments", (), {
                "manifest": manifest,
                "shard": "shard",
                "suite": "suite",
                "system": "joni",
                "input": [result],
                "output": output,
                "semantic_receipt": semantic_receipt,
                "benchmark_class": "example.Benchmark",
                "method_map": ["containsJoni=contains"],
                "allow_missing_secondary_metrics": False,
                "allow_multiple_process_groups": True,
                "expected_process_groups": 5,
            })()

            SHARD_RESULTS.normalize_mapped_jmh(arguments)

            with output.open(newline="", encoding="utf-8") as input_file:
                row = next(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual("3", row["score"])
            self.assertEqual("30", row["allocation_bytes"])

    def testPrecisionFailureRejectsOnlyTheMeasuredRow(self):
        expected = {
            **{field: field for field in SHARD_RESULTS.MANIFEST_FIELDS},
            "row_id": "row",
            "shard_id": "shard",
            "system": "joni",
            "allocation_contract": "not-measured",
            "expected_result": "result",
            "workload_checksum": "workload",
        }
        result = {
            "primaryMetric": {
                "score": 100,
                "scoreUnit": "ns/op",
                "rawData": [[50], [150]],
            },
        }

        row = SHARD_RESULTS.observed_jmh_row(expected, result, 0.05)

        self.assertEqual("precision-rejected", row["outcome"])
        self.assertEqual("100", row["score"])

    def testCalibrationAcceptsFiveForkGroupsInOneFile(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            paths = {}
            for phase, samples in (("before", (10, 11, 12, 13, 14)), ("after", (11, 12, 13, 14, 15))):
                path = root / f"{phase}.json"
                path.write_text(json.dumps([{
                    "benchmark": "io.airlift.regulator.BenchmarkCalibration.run",
                    "params": {"size": "1024"},
                    "primaryMetric": {
                        "score": 12,
                        "scoreUnit": "ns/op",
                        "rawData": [[sample] for sample in samples],
                    },
                }]))
                paths[phase] = path
            output = root / "calibration.tsv"
            arguments = type("Arguments", (), {
                "before": paths["before"],
                "after": paths["after"],
                "output": output,
            })()

            SHARD_RESULTS.calibration(arguments)

            with output.open(newline="", encoding="utf-8") as input_file:
                rows = list(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual(["5", "5"], [row["sample_count"] for row in rows])
            self.assertEqual(["12", "13"], [row["score"] for row in rows])

    def testRuntimeEstimateIncludesInvocationsAndProcessStartup(self):
        arguments = type("Arguments", (), {
            "rows": 100,
            "invocations": 2,
            "processes": 5,
            "warmup_iterations": 10,
            "measurement_iterations": 10,
            "iteration_seconds": 0.05,
            "startup_seconds": 2,
            "calibration_invocations": 2,
            "calibration_rows": 1,
            "calibration_processes": 5,
            "calibration_warmup_iterations": 10,
            "calibration_measurement_iterations": 10,
            "calibration_iteration_seconds": 1,
            "additional_seconds": 50,
            "maximum_seconds": 800,
        })()

        self.assertEqual(744, SHARD_RESULTS.jmh_runtime_seconds(100, 5, 10, 10, 0.05) + 244)
        SHARD_RESULTS.estimate_jmh_runtime(arguments)
        arguments.maximum_seconds = 780
        with self.assertRaisesRegex(ValueError, "exceeds"):
            SHARD_RESULTS.estimate_jmh_runtime(arguments)

    def testValidateDriftRejectsValuesAboveFivePercent(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            calibration = root / "calibration.tsv"
            SHARD_RESULTS.write_tsv(calibration, ("before_after_drift",), [
                {"before_after_drift": "0.0500001"},
            ])
            arguments = type("Arguments", (), {
                "calibration": calibration,
                "native_bracket": [],
                "maximum": 0.05,
            })()

            with self.assertRaisesRegex(ValueError, "exceeded"):
                SHARD_RESULTS.validate_drift(arguments)

    def testValidateDriftRetainsLargeNativeBracketValues(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            calibration = root / "calibration.tsv"
            native_bracket = root / "native-bracket.tsv"
            SHARD_RESULTS.write_tsv(calibration, ("before_after_drift",), [
                {"before_after_drift": "0.01"},
            ])
            SHARD_RESULTS.write_tsv(native_bracket, ("before_after_drift",), [
                {"before_after_drift": "0.06"},
            ])
            arguments = type("Arguments", (), {
                "calibration": calibration,
                "native_bracket": [native_bracket],
                "maximum": 0.05,
            })()

            SHARD_RESULTS.validate_drift(arguments)

    def testValidateDriftRejectsMalformedNativeBracketValues(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            calibration = root / "calibration.tsv"
            native_bracket = root / "native-bracket.tsv"
            SHARD_RESULTS.write_tsv(calibration, ("before_after_drift",), [
                {"before_after_drift": "0.01"},
            ])
            SHARD_RESULTS.write_tsv(native_bracket, ("before_after_drift",), [
                {"before_after_drift": "nan"},
            ])
            arguments = type("Arguments", (), {
                "calibration": calibration,
                "native_bracket": [native_bracket],
                "maximum": 0.05,
            })()

            with self.assertRaisesRegex(ValueError, "Invalid drift"):
                SHARD_RESULTS.validate_drift(arguments)

    def testCurrentManifestUsesExactTrinoSystemSets(self):
        rows = SHARD_RESULTS.load_manifest(MANIFEST)
        counts = {}
        for row in rows:
            if row["shard_id"] in {"trino-operations", "trino-search-edges"}:
                key = (row["suite"], row["system"])
                counts[key] = counts.get(key, 0) + 1

        self.assertEqual({
            ("trino-everyday-operations", "joni"): 104,
            ("trino-everyday-operations", "regulator-native-access"): 104,
            ("trino-everyday-operations", "regulator-object-row"): 104,
            ("trino-public-operations", "joni"): 80,
            ("trino-public-operations", "regulator-native-access"): 80,
            ("trino-public-operations", "regulator-object-row"): 80,
            ("trino-search-edges", "regulator-native-access"): 168,
            ("trino-search-edges", "regulator-object-row"): 168,
        }, counts)

    def testCurrentManifestUsesExactRebarShardPartitions(self):
        rows = SHARD_RESULTS.load_manifest(MANIFEST)
        systems = (
            "native-re2-after",
            "native-re2-before",
            "regulator-native-access",
            "regulator-object-row",
        )
        expected_rows_per_system = {
            **{f"rebar-{letter}": 12 for letter in "abcdefghijklmnopqr"},
            "rebar-s": 11,
            "rebar-t": 11,
        }
        expected_joni_rows = {
            "rebar-a": 12,
            "rebar-b": 9,
            "rebar-c": 12,
            "rebar-d": 11,
            "rebar-e": 12,
            "rebar-f": 10,
            "rebar-g": 12,
            "rebar-h": 12,
            "rebar-i": 11,
            "rebar-j": 11,
            "rebar-k": 12,
            "rebar-l": 11,
            "rebar-m": 12,
            "rebar-n": 11,
            "rebar-o": 11,
            "rebar-p": 12,
            "rebar-q": 11,
            "rebar-r": 12,
            "rebar-s": 10,
            "rebar-t": 11,
        }

        for shard, expected_count in expected_rows_per_system.items():
            selected = [row for row in rows if row["shard_id"] == shard]
            self.assertEqual(
                expected_count * len(systems) + expected_joni_rows[shard], len(selected))
            for system in systems:
                self.assertEqual(
                    expected_count,
                    sum(row["system"] == system for row in selected),
                    f"{shard} {system}")
            self.assertEqual(
                expected_joni_rows[shard],
                sum(row["system"] == "joni" for row in selected))

            native_route = SHARD_RESULTS.selected_rows(
                rows,
                shard,
                "regulator-native-access,native-re2-before,native-re2-after,joni")
            object_route = SHARD_RESULTS.selected_rows(rows, shard, "regulator-object-row")
            self.assertEqual(expected_count * 3 + expected_joni_rows[shard], len(native_route))
            self.assertEqual(expected_count, len(object_route))

    def testCurrentManifestUsesAccurateAllocationContracts(self):
        rows = SHARD_RESULTS.load_manifest(MANIFEST)

        rebar_rows = [row for row in rows if row["suite"].startswith("rebar-")]
        self.assertEqual(1177, len(rebar_rows))
        self.assertEqual({"not-measured"}, {row["allocation_contract"] for row in rebar_rows})

        traditional_native_rows = [
            row for row in rows
            if row["suite"] == "traditional" and row["system"].startswith("native-re2-")]
        self.assertEqual(596, len(traditional_native_rows))
        self.assertEqual(
            {"not-measured"},
            {row["allocation_contract"] for row in traditional_native_rows})

        measured_regulator_rows = [
            row for row in rows
            if row["system"].startswith("regulator") and not row["suite"].startswith("rebar-")]
        self.assertTrue(measured_regulator_rows)
        self.assertNotIn(
            "not-measured",
            {row["allocation_contract"] for row in measured_regulator_rows})

    def testNormalizeRebarRecordsClassifiedMismatchWithoutTiming(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rows.tsv"
            expected = {
                "row_id": "rebar-a/extended/example/count/regulator-native-access",
                "shard_id": "rebar-a",
                "suite": "rebar-extended",
                "system": "regulator-native-access",
                "benchmark": "extended/example",
                "parameters": "model=count;workload=extended/example",
                "comparator": "pinned-native-re2",
                "expected_result": "native-differential",
                "allocation_contract": "not-measured",
                "workload_checksum": "corpus",
            }
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, [expected])
            semantic_receipt = root / "semantic.tsv"
            SHARD_RESULTS.write_tsv(semantic_receipt, SHARD_RESULTS.SEMANTIC_FIELDS, [{
                "route": "native-access",
                "tests": "TestExample",
                "outcome": "accepted",
                "semantic_result_digest": "0" * 64,
            }])
            detail = "count mismatch, expected 55, got 0"
            outcomes = root / "rebar-outcomes.tsv"
            SHARD_RESULTS.write_tsv(outcomes, SHARD_RESULTS.REBAR_OUTCOME_FIELDS, [{
                "row_id": expected["row_id"],
                "suite": expected["suite"],
                "system": expected["system"],
                "benchmark": expected["benchmark"],
                "model": "count",
                "outcome": "semantic-mismatch",
                "detail": detail,
            }])
            measurements = root / "measurements.csv"
            with measurements.open("w", newline="", encoding="utf-8") as output_file:
                writer = csv.DictWriter(
                    output_file,
                    fieldnames=("name", "model", "engine", "engine_version", "err", "median"))
                writer.writeheader()
                writer.writerow({
                    "name": expected["benchmark"],
                    "model": "count",
                    "engine": "regulator/re2",
                    "engine_version": "candidate",
                    "err": detail,
                    "median": "",
                })
            normalized = root / "normalized.tsv"
            arguments = type("Arguments", (), {
                "manifest": manifest,
                "shard": "rebar-a",
                "system": "regulator-native-access",
                "input": measurements,
                "output": normalized,
                "semantic_receipt": semantic_receipt,
                "rebar_outcomes": outcomes,
            })()

            SHARD_RESULTS.normalize_rebar(arguments)

            observed = SHARD_RESULTS.load_observed(normalized)
            self.assertEqual("semantic-mismatch", observed[0]["outcome"])
            self.assertEqual("", observed[0]["score"])
            self.assertEqual("", observed[0]["score_unit"])
            self.assertEqual("not-measured", observed[0]["allocation_bytes"])

            combined = root / "combined.tsv"
            SHARD_RESULTS.combine(type("Arguments", (), {
                "expected": manifest,
                "output": combined,
                "inputs": [normalized],
                "rebar_outcomes": outcomes,
            })())
            self.assertEqual("semantic-mismatch", SHARD_RESULTS.load_observed(combined)[0]["outcome"])

            arguments.rebar_outcomes = None
            with self.assertRaisesRegex(ValueError, "Unclassified Rebar measurement failure"):
                SHARD_RESULTS.normalize_rebar(arguments)

    def testNormalizeRebarMapsJoniEngine(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rows.tsv"
            expected = {
                "row_id": "rebar-a/curated/example/count/joni",
                "shard_id": "rebar-a",
                "suite": "rebar-curated",
                "system": "joni",
                "benchmark": "curated/example",
                "parameters": "model=count;workload=curated/example",
                "comparator": "joni-rebar-differential",
                "expected_result": "native-differential",
                "allocation_contract": "not-measured",
                "workload_checksum": "corpus",
            }
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, [expected])
            semantic_receipt = root / "semantic.tsv"
            SHARD_RESULTS.write_tsv(semantic_receipt, SHARD_RESULTS.SEMANTIC_FIELDS, [{
                "route": "native-access",
                "tests": "TestExample",
                "outcome": "accepted",
                "semantic_result_digest": "0" * 64,
            }])
            outcomes = root / "rebar-outcomes.tsv"
            SHARD_RESULTS.write_tsv(outcomes, SHARD_RESULTS.REBAR_OUTCOME_FIELDS, [])
            measurements = root / "measurements.csv"
            with measurements.open("w", newline="", encoding="utf-8") as output_file:
                writer = csv.DictWriter(
                    output_file,
                    fieldnames=("name", "model", "engine", "engine_version", "err", "median"))
                writer.writeheader()
                writer.writerow({
                    "name": expected["benchmark"],
                    "model": "count",
                    "engine": "joni/trino",
                    "engine_version": "Trino Joni 2.1.5.3",
                    "err": "",
                    "median": "123ns",
                })

            normalized = root / "normalized.tsv"
            SHARD_RESULTS.normalize_rebar(type("Arguments", (), {
                "manifest": manifest,
                "shard": "rebar-a",
                "system": "joni",
                "input": measurements,
                "output": normalized,
                "semantic_receipt": semantic_receipt,
                "rebar_outcomes": outcomes,
            })())

            observed = SHARD_RESULTS.load_observed(normalized)
            self.assertEqual("123", observed[0]["score"])
            self.assertEqual("accepted", observed[0]["outcome"])

    def testNormalizeRebarRetainsUnmeasuredJoniTimeout(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rows.tsv"
            expected = {
                "row_id": "rebar-a/curated/example/count/joni",
                "shard_id": "rebar-a",
                "suite": "rebar-curated",
                "system": "joni",
                "benchmark": "curated/example",
                "parameters": "model=count;workload=curated/example",
                "comparator": "joni-rebar-differential",
                "expected_result": "native-differential",
                "allocation_contract": "not-measured",
                "workload_checksum": "corpus",
            }
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, [expected])
            semantic_receipt = root / "semantic.tsv"
            SHARD_RESULTS.write_tsv(semantic_receipt, SHARD_RESULTS.SEMANTIC_FIELDS, [{
                "route": "native-access",
                "tests": "TestExample",
                "outcome": "accepted",
                "semantic_result_digest": "0" * 64,
            }])
            outcomes = root / "rebar-outcomes.tsv"
            SHARD_RESULTS.write_tsv(outcomes, SHARD_RESULTS.REBAR_OUTCOME_FIELDS, [{
                "row_id": expected["row_id"],
                "suite": expected["suite"],
                "system": "joni",
                "benchmark": expected["benchmark"],
                "model": "count",
                "outcome": "did-not-finish",
                "detail": "timeout: exceeded 30s",
            }])
            measurements = root / "measurements.csv"
            measurements.write_text("")

            normalized = root / "normalized.tsv"
            SHARD_RESULTS.normalize_rebar(type("Arguments", (), {
                "manifest": manifest,
                "shard": "rebar-a",
                "system": "joni",
                "input": measurements,
                "output": normalized,
                "semantic_receipt": semantic_receipt,
                "rebar_outcomes": outcomes,
            })())

            observed = SHARD_RESULTS.load_observed(normalized)
            self.assertEqual("", observed[0]["score"])
            self.assertEqual("did-not-finish", observed[0]["outcome"])

    def testNormalizeJmhUsesManifestIdentifier(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rows.tsv"
            expected = {
                "row_id": "shard/BenchmarkExample.run/size-8/regulator-native-access",
                "shard_id": "shard",
                "suite": "suite",
                "system": "regulator-native-access",
                "benchmark": "io.airlift.regulator.BenchmarkExample.run",
                "parameters": "size=8",
                "comparator": "absolute",
                "expected_result": "benchmark-self-check",
                "allocation_contract": "allocation-free",
                "workload_checksum": "candidate-source",
            }
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, [expected])
            result = root / "result.json"
            result.write_text(json.dumps([{
                "benchmark": expected["benchmark"],
                "params": {"size": "8"},
                "primaryMetric": {
                    "score": 12.5,
                    "scoreError": 0.5,
                    "scoreUnit": "ns/op",
                    "rawData": [[12.0, 13.0]],
                },
                "secondaryMetrics": {
                    "gc.alloc.rate.norm": {"score": 0.0, "scoreUnit": "B/op"},
                },
            }]))
            output = root / "observed.tsv"
            semantic_receipt = root / "semantic.tsv"
            SHARD_RESULTS.write_tsv(semantic_receipt, SHARD_RESULTS.SEMANTIC_FIELDS, [{
                "route": "native-access",
                "tests": "TestExample",
                "outcome": "accepted",
                "semantic_result_digest": "0" * 64,
            }])
            arguments = type("Arguments", (), {
                "manifest": manifest,
                "shard": "shard",
                "suite": "suite",
                "system": "regulator-native-access",
                "input": [result],
                "output": output,
                "semantic_receipt": semantic_receipt,
                "allow_traditional_unpaired": False,
            })()

            SHARD_RESULTS.normalize_jmh(arguments)

            with output.open(newline="", encoding="utf-8") as input_file:
                rows = list(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual([expected["row_id"]], [row["row_id"] for row in rows])
            self.assertEqual("12.5", rows[0]["score"])
            self.assertEqual("0", rows[0]["allocation_bytes"])
            self.assertEqual("accepted", rows[0]["outcome"])
            self.assertRegex(rows[0]["result_checksum"], "^[0-9a-f]{64}$")

    def testNormalizeJmhRejectsUnexpectedRows(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rows.tsv"
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, [{
                "row_id": "expected",
                "shard_id": "shard",
                "suite": "suite",
                "system": "regulator-native-access",
                "benchmark": "io.airlift.regulator.BenchmarkExample.run",
                "parameters": "-",
                "comparator": "absolute",
                "expected_result": "benchmark-self-check",
                "allocation_contract": "recorded",
                "workload_checksum": "candidate-source",
            }])
            result = root / "result.json"
            result.write_text(json.dumps([{
                "benchmark": "io.airlift.regulator.BenchmarkExample.unexpected",
                "primaryMetric": {"score": 1, "scoreUnit": "ns/op", "rawData": [[1]]},
            }]))
            output = root / "observed.tsv"
            semantic_receipt = root / "semantic.tsv"
            SHARD_RESULTS.write_tsv(semantic_receipt, SHARD_RESULTS.SEMANTIC_FIELDS, [{
                "route": "native-access",
                "tests": "TestExample",
                "outcome": "accepted",
                "semantic_result_digest": "0" * 64,
            }])
            arguments = type("Arguments", (), {
                "manifest": manifest,
                "shard": "shard",
                "suite": "suite",
                "system": "regulator-native-access",
                "input": [result],
                "output": output,
                "semantic_receipt": semantic_receipt,
                "allow_traditional_unpaired": False,
            })()

            with self.assertRaisesRegex(ValueError, "differ from the manifest"):
                SHARD_RESULTS.normalize_jmh(arguments)

    def testCombineRequiresExactRowSet(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            expected = root / "expected.tsv"
            row = {
                "row_id": "row",
                "shard_id": "shard",
                "suite": "suite",
                "system": "regulator-native-access",
                "benchmark": "benchmark",
                "parameters": "-",
                "comparator": "absolute",
                "expected_result": "benchmark-self-check",
                "allocation_contract": "recorded",
                "workload_checksum": "candidate-source",
            }
            SHARD_RESULTS.write_tsv(expected, SHARD_RESULTS.MANIFEST_FIELDS, [row])
            fragment = root / "fragment.tsv"
            observed_row = {
                "row_id": row["row_id"],
                "shard_id": row["shard_id"],
                "system": row["system"],
                "score": "1",
                "score_unit": "ns/op",
                "allocation_bytes": "0",
                "result_checksum": "0" * 64,
                "outcome": "accepted",
            }
            SHARD_RESULTS.write_tsv(fragment, SHARD_RESULTS.OBSERVED_FIELDS, [observed_row])
            output = root / "output.tsv"
            arguments = type("Arguments", (), {
                "expected": expected,
                "inputs": [fragment],
                "output": output,
            })()

            SHARD_RESULTS.combine(arguments)

            self.assertTrue(output.is_file())
            self.assertTrue(output.with_suffix(".tsv.sha256").is_file())

    def testNativeBracketResolvesComparatorThroughManifest(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rows.tsv"
            expected_rows = []
            observed_rows = []
            for system, score in (("native-re2-before", "100"), ("native-re2-after", "102")):
                expected_rows.append({
                    "row_id": f"shard/row/{system}",
                    "shard_id": "shard",
                    "suite": "traditional",
                    "system": system,
                    "benchmark": "benchmark",
                    "parameters": "-",
                    "comparator": "NativeBenchmark/threads:1",
                    "expected_result": "paired-native-result",
                    "allocation_contract": "allocation-free",
                    "workload_checksum": "traditional-pair-manifest",
                })
                observed_rows.append({
                    "row_id": f"shard/row/{system}",
                    "shard_id": "shard",
                    "system": system,
                    "score": score,
                    "score_unit": "ns/op",
                    "allocation_bytes": "not-measured",
                    "result_checksum": "0" * 64,
                    "outcome": "accepted",
                })
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, expected_rows)
            before = root / "before.tsv"
            after = root / "after.tsv"
            SHARD_RESULTS.write_tsv(before, SHARD_RESULTS.OBSERVED_FIELDS, [observed_rows[0]])
            SHARD_RESULTS.write_tsv(after, SHARD_RESULTS.OBSERVED_FIELDS, [observed_rows[1]])
            output = root / "bracket.tsv"
            arguments = type("Arguments", (), {
                "manifest": manifest,
                "before": before,
                "after": after,
                "output": output,
            })()

            SHARD_RESULTS.bracket(arguments)

            with output.open(newline="", encoding="utf-8") as input_file:
                rows = list(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual("NativeBenchmark/threads:1", rows[0]["benchmark"])
            self.assertEqual("0.02", rows[0]["before_after_drift"])


if __name__ == "__main__":
    unittest.main()
