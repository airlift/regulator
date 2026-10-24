#!/usr/bin/env python3

import csv
import contextlib
import importlib.util
import io
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


SOURCE = Path(__file__).with_name("shard_results.py")
MANIFEST = Path(__file__).with_name("rows.tsv")
sys.path.insert(0, str(SOURCE.parent))
SPECIFICATION = importlib.util.spec_from_file_location("shard_results", SOURCE)
SHARD_RESULTS = importlib.util.module_from_spec(SPECIFICATION)
SPECIFICATION.loader.exec_module(SHARD_RESULTS)
import row_protocol as ROW_PROTOCOL  # noqa: E402
import unit_session as UNIT_SESSION  # noqa: E402
import work_units as WORK_UNITS  # noqa: E402
# The qualification process count the handler harness gives run-shard.sh.
JMH_PROCESS_COUNT = 5
with SOURCE.with_name("shard-dispatch.tsv").open(newline="", encoding="utf-8") as dispatch_file:
    DISPATCH = {row["shard_id"]: row for row in csv.DictReader(dispatch_file, delimiter="\t")}
HANDLER_FUNCTIONS = {"trino-final-line": "run_final_line_shard", "jmh": "run_generic_jmh_shard"}


def shell_function(source, name):
    """Returns one function definition from a shell script's source."""
    start = source.index(f"\n{name}()\n") + 1
    return source[start:source.index("\n}\n", start) + 3]


def run_shard_handler(shard, route, replica, joni_order="forward"):
    """Runs the shard runner's measurement handler with java and normalization intercepted.

    Returns each JMH launch as (forks, filter, parameters, time unit, output), each
    normalization call as its argument list, and the identifier of each compiler-hints JVM.
    """
    source = SOURCE.with_name("run-shard.sh").read_text(encoding="utf-8")

    def block(marker):
        start = source.index(marker)
        return source[start:source.index("\nfi\n", start) + 4]

    dispatch = DISPATCH[shard]
    route_column = route.replace("-", "_")
    handler = dispatch[f"{route_column}_handler"]
    with tempfile.TemporaryDirectory() as temporary_directory:
        root = Path(temporary_directory)
        for directory in ("raw", "logs", "normalized"):
            (root / directory).mkdir()
        subprocess.run([str(SOURCE), "expected", "--manifest", str(MANIFEST), "--shard", shard, "--systems",
                        dispatch[f"{route_column}_systems"], "--output", str(root / "expected-rows.tsv")], check=True)
        functions = [shell_function(source, name) for name in (
            "full_protocol_benchmarks", "set_jmh_input_files", "prepare_jmh_host_compiler_arguments",
            "run_jmh_with_classpath", "run_jmh", "run_joni_jmh_with_classpath", "run_jmh_per_workload",
            "run_ordered_joni_comparison", "run_trino_operations_candidate", "run_trino_operations_joni",
            "run_trino_operations_candidates", "run_trino_operations_shard", "run_trino_like_system",
            "run_trino_like_shard", "run_final_line_candidate", "run_final_line_joni", "run_final_line_shard",
            "frozen_protocol", "jmh_warmup_time", "set_forked_jmh_input_files", "run_generic_jmh_shard",
            "unit_holds_rows", "run_traditional_shard", "run_lifecycle_candidate", "run_lifecycle_regulator_phase",
            "run_lifecycle_shard")]
        script = "\n".join([
            "set -euo pipefail",
            f"SHARD_ID={shard} ROUTE={route} CAMPAIGN_REPLICA_ID={replica} RESULT_DIR={shlex.quote(str(root))}",
            f"systems={dispatch[f'{route_column}_systems']} MANIFEST={shlex.quote(str(MANIFEST))}",
            f"SELECTION_MANIFEST={shlex.quote(str(MANIFEST))} UNIT_DIRECTORY=",
            f"RESULT_TOOL={shlex.quote(str(SOURCE))} SCRIPT_DIR={shlex.quote(str(SOURCE.parent))}",
            # The native RE2 comparator is not a JMH launch.
            f"native_system_row_count=0 EXTENDED_WARMUP_TIME=300ms ROOT={shlex.quote(str(SOURCE.parents[2]))}",
            f"FULL_PROTOCOL_BENCHMARKS={shlex.quote(str(SOURCE.with_name('full-protocol-benchmarks.tsv')))}",
            f"ROW_PROTOCOL={shlex.quote(str(SOURCE.with_name('row-protocol.tsv')))}",
            f"PROTOCOL=qualification JMH_PROCESS_COUNT={JMH_PROCESS_COUNT} JMH_PROCESS_TIME=1s JONI_JMH_FORKS=1 JONI_JMH_TIME=1s",
            f"JMH_PROCESS_WARMUP_ITERATIONS=10 JMH_PROCESS_MEASUREMENT_ITERATIONS=10 JONI_COMPARATOR_ORDER={joni_order}",
            "CLASSPATH=regulator SPECIAL_CLASSPATH=special PINNED_TRINO_CLASSPATH=pinned CPU_LIST=0 jmh_fork_arguments=-Xmx1g",
            "route_jvm_arguments=()",
            "jmh_process_jvm_arguments=() jmh_launcher_jvm_arguments=() jmh_host_compiler_arguments=()",
            "secondary_metric_arguments=(--allow-missing-secondary-metrics) mixed_process_arguments=()",
            "precision_arguments=() joni_process_arguments=() normalized_files=() joni_jmh_row_count=0",
            f"candidate_system=regulator-{route} final_line_mappings=() final_line_joni_mappings=()",
            block("JMH_SUITE_ORDER=forward\n"), block("jmh_invocation_per_workload=false\n"),
            "taskset() { shift 2; \"$@\"; }",
            "java() {",
            "    if [[ \" $* \" == *' io.airlift.regulator.BenchmarkCompilerHints '* ]]; then",
            "        printf '%s\\n' \"${@: -2:1}\" >> \"${RESULT_DIR}/compiler-hints.txt\"",
            "        printf -- '-XX:+UnlockDiagnosticVMOptions\\n' > \"${@: -1}\"",
            "        return",
            "    fi",
            "    local forks= filter= parameters=() unit=- output=",
            "    while (($#)); do",
            "        case $1 in",
            "            org.openjdk.jmh.Main) filter=$2; shift ;;",
            "            -f) forks=$2; shift ;;",
            "            -p) parameters+=(\"$2\"); shift ;;",
            "            -tu) unit=$2; shift ;;",
            "            -rff) output=$2; shift ;;",
            "        esac",
            "        shift",
            "    done",
            "    printf '%s\\t%s\\t%s\\t%s\\t%s\\n' \"${forks}\" \"${filter}\" \"${parameters[*]}\" \"${unit}\" \"${output##*/}\" \\",
            "        >> \"${RESULT_DIR}/launches.tsv\"",
            "}",
            "python3() {",
            "    if [[ $2 == normalize-* ]]; then",
            "        printf '%s\\x1f' \"$@\" >> \"${RESULT_DIR}/normalizations.txt\"",
            "        printf '\\n' >> \"${RESULT_DIR}/normalizations.txt\"",
            "    else",
            "        command python3 \"$@\"",
            "    fi",
            "}",
            *functions,
            HANDLER_FUNCTIONS.get(handler, f"run_{handler.replace('-', '_')}_shard"),
        ])
        subprocess.run(["bash"], input=script, text=True, capture_output=True, check=True)
        launches = [tuple(line.split("\t")) for line in (root / "launches.tsv").read_text().splitlines()]
        normalizations = [line.split("\x1f")[:-1] for line in (root / "normalizations.txt").read_text().splitlines()]
        hints = root / "compiler-hints.txt"
        compiler_hints = [Path(line).name for line in hints.read_text().splitlines()] if hints.exists() else []
    return launches, normalizations, compiler_hints


def argument_values(arguments, name):
    """Returns the values following one argument of a recorded command line."""
    start = arguments.index(name) + 1
    end = next((index for index in range(start, len(arguments)) if arguments[index].startswith("--")), len(arguments))
    return arguments[start:end]


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
        joni_runner = script.split("run_joni_jmh_with_classpath()", 1)[1].split("\n}\n", 1)[0]
        self.assertIn('-f "${JONI_JMH_FORKS}"', joni_runner)
        self.assertNotIn("-f 0", joni_runner)
        self.assertIn("JONI_JMH_FORKS=1", script)
        self.assertNotIn("JONI_JMH_FORKS=5", joni_runner)

    def testRebarJoniUsesIndependentOrderedInvocation(self):
        script = SOURCE.with_name("run-shard.sh").read_text(encoding="utf-8")

        self.assertIn("run_rebar_primary_phase()", script)
        self.assertIn("run_rebar_joni_phase()", script)
        self.assertIn(
            "run_ordered_joni_comparison run_rebar_primary_phase run_rebar_joni_phase",
            script)
        self.assertIn("rebar-primary-measurements.csv", script)
        self.assertIn("rebar-joni-measurements.csv", script)
        self.assertNotIn("regulator/re2|joni/trino", script)
        for shard in (f"rebar-{letter}" for letter in "abcdefghijklmnopqrst"):
            self.assertEqual(
                "regulator-native-access,native-re2-before,native-re2-after,joni",
                DISPATCH[shard]["native_access_systems"])

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
                "UNIT_DIRECTORY=\n"
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

    def assertEachWorkloadLaunches(self, expected, launches, normalizations):
        """Checks the launches against expected (system, launch) pairs, and that each system's
        normalization reads exactly its own launches' results as separate process sets."""
        self.assertEqual([launch for _, launch in expected], [launch[:4] for launch in launches])
        self.assertEqual(len(launches), len({launch[4] for launch in launches}))
        outputs = {}
        for (system, _), launch in zip(expected, launches):
            outputs.setdefault(system, []).append(launch[4])
        self.assertEqual(list(outputs), [argument_values(arguments, "--system")[0] for arguments in normalizations])
        for arguments in normalizations:
            system = argument_values(arguments, "--system")[0]
            self.assertIn("--allow-mixed-process-sets", arguments)
            process_groups = "1" if system == "joni" else str(JMH_PROCESS_COUNT)
            self.assertEqual([process_groups], argument_values(arguments, "--expected-process-groups"))
            self.assertEqual(sorted(outputs[system]), sorted(Path(path).name for path in argument_values(arguments, "--input")))

    def testSpecializedPatternsRunEachWorkloadInItsOwnJvms(self):
        candidates = ("regulator-native-access", "regulator-regular-engine-native-access", "regulator-generated-native-access")
        manifest = SHARD_RESULTS.load_manifest(MANIFEST)

        def launches_of(shard, system, reverse):
            rows = [row for row in manifest if row["shard_id"] == shard and row["system"] == system]
            forks, processes = ("1", 1) if system == "joni" else ("0", JMH_PROCESS_COUNT)
            return [
                (system, (forks, "^" + row["benchmark"].replace(".", "\\.") + "$", row["parameters"], "-"))
                for row in (rows[::-1] if reverse else rows)
                for _ in range(processes)]

        # Even replicas reverse the Regulator variants and their workloads. Joni keeps its workload
        # order, and JONI_COMPARATOR_ORDER places it after or before every variant.
        for shard in ("trino-specialized-patterns-a", "trino-specialized-patterns-b"):
            for replica, joni_order in ((1, "forward"), (2, "forward"), (1, "reverse")):
                with self.subTest(shard=shard, replica=replica, joni_order=joni_order):
                    launches, normalizations, _ = run_shard_handler(shard, "native-access", replica, joni_order)

                    reverse = replica % 2 == 0
                    variants = [
                        launch for system in (candidates[::-1] if reverse else candidates)
                        for launch in launches_of(shard, system, reverse)]
                    joni = launches_of(shard, "joni", False)
                    expected = joni + variants if joni_order == "reverse" else variants + joni
                    self.assertEachWorkloadLaunches(expected, launches, normalizations)

    def testTrinoOperationsKeepsOneInvocationPerSystemOnEveryReplica(self):
        for replica in (1, 2):
            with self.subTest(replica=replica):
                launches, normalizations, _ = run_shard_handler("trino-operations", "native-access", replica)

                self.assertEqual(
                    [f"trino-operations-regulator-native-access-process-{index}.json"
                     for index in range(1, JMH_PROCESS_COUNT + 1)] +
                    ["trino-operations-joni.json"],
                    [launch[4] for launch in launches])
                self.assertEqual({""}, {launch[2] for launch in launches})
                self.assertEqual(
                    ["regulator-native-access", "joni"],
                    [argument_values(arguments, "--system")[0] for arguments in normalizations])
                self.assertFalse(any("--allow-mixed-process-sets" in arguments for arguments in normalizations))

    def testEveryLikeShardNormalizesItsOwnManifestRows(self):
        manifest = SHARD_RESULTS.load_manifest(MANIFEST)
        shards = [shard for shard, row in DISPATCH.items() if row["native_access_handler"] == "trino-like"]
        self.assertIn("trino-like-specialized", shards)
        for shard in shards:
            with self.subTest(shard=shard):
                _, normalizations, _ = run_shard_handler(shard, "native-access", 1)

                self.assertEqual(
                    {(row["system"], row["suite"]) for row in manifest if row["shard_id"] == shard},
                    {(argument_values(arguments, "--system")[0], argument_values(arguments, "--suite")[0])
                     for arguments in normalizations})
                benchmark_class = (
                    "io.airlift.regulator.BenchmarkTrinoLikeTpch" if shard == "trino-like-specialized"
                    else "io.airlift.regulator.benchmark.BenchmarkTrinoLike")
                for arguments in normalizations:
                    self.assertEqual([shard], argument_values(arguments, "--shard"))
                    self.assertEqual([benchmark_class], argument_values(arguments, "--benchmark-class"))

    def testSpecializedLikeRunsEachWorkloadInItsOwnJvms(self):
        manifest = [row for row in SHARD_RESULTS.load_manifest(MANIFEST) if row["shard_id"] == "trino-like-specialized"]
        methods = {"regulator": "candidate", "regulator-generated": "candidateGenerated", "trino-sql": "trinoSql"}
        self.assertEqual(set(methods), {row["system"] for row in manifest})
        # The TPC-H benchmark declares microseconds, so every launch requests nanoseconds.
        forward = [
            (system, ("0", f"^io\\.airlift\\.regulator\\.BenchmarkTrinoLikeTpch\\.{method}$", row["parameters"], "ns"))
            for system, method in methods.items()
            for row in manifest if row["system"] == system
            for _ in range(JMH_PROCESS_COUNT)]

        # Even replicas reverse the whole sequence, Trino SQL included.
        for replica, expected in ((1, forward), (2, forward[::-1])):
            with self.subTest(replica=replica):
                launches, normalizations, _ = run_shard_handler("trino-like-specialized", "native-access", replica)

                self.assertEachWorkloadLaunches(expected, launches, normalizations)
                for arguments in normalizations:
                    self.assertEqual(["trino-like-specialized"], argument_values(arguments, "--shard"))
                    self.assertEqual(
                        ["io.airlift.regulator.BenchmarkTrinoLikeTpch"], argument_values(arguments, "--benchmark-class"))

    def testEveryUnitSelectorMatchesItsHandlersJmhFilters(self):
        protocols = ROW_PROTOCOL.load(MANIFEST.with_name("row-protocol.tsv"))
        manifest = SHARD_RESULTS.load_manifest(MANIFEST)
        units = WORK_UNITS.load(MANIFEST.parent)
        filters = {}
        handlers = set()
        for unit in units.values():
            shard_rows = [row for row in manifest if row["shard_id"] == unit["shard_id"]]
            for route, handler, _, share, _ in UNIT_SESSION.plan_routes(MANIFEST.parent, unit, "1"):
                route_selectors = UNIT_SESSION.selectors(share, shard_rows, protocols, "qualification", {})
                if not route_selectors:
                    continue
                handlers.add(handler)
                key = (unit["shard_id"], route)
                if key not in filters:
                    launches, _, _ = run_shard_handler(*key, 1)
                    filters[key] = [re.compile(launch[1]) for launch in launches]
                for selector in route_selectors:
                    for benchmark in selector["benchmarks"].split(","):
                        with self.subTest(unit=unit["unit_id"], route=route, benchmark=benchmark):
                            self.assertTrue(any(pattern.search(benchmark) for pattern in filters[key]))
        self.assertLessEqual({"jmh", "traditional", "lifecycle", "trino-like", "trino-operations"}, handlers)

    def testPlannedStartupsMatchTheLaunchedJvms(self):
        dispatch = [
            row for shard, row in DISPATCH.items()
            if shard.startswith("trino-specialized-patterns-") or shard == "trino-like-specialized"]
        with tempfile.TemporaryDirectory() as temporary_directory:
            for row in dispatch:
                for route in ("native-access", "object-row"):
                    if row[f"{route.replace('-', '_')}_handler"] == "-":
                        continue
                    with self.subTest(shard=row["shard_id"], route=route):
                        result_directory = Path(temporary_directory) / f"{row['shard_id']}-{route}"
                        subprocess.run(
                            [str(SOURCE.with_name("run-shard.sh")), row["shard_id"], "qualification", route,
                             str(result_directory)],
                            check=True, capture_output=True, text=True, env={**os.environ, "BASELINE_PLAN_ONLY": "true"})
                        metadata = dict(
                            line.split("=", 1) for line in (result_directory / "run-metadata.txt").read_text().splitlines())
                        launches, _, compiler_hints = run_shard_handler(row["shard_id"], route, 1)

                        joni_rows = int(metadata["joni_jmh_row_count"])
                        joni_invocations = int(metadata["joni_jmh_invocation_count"])
                        planned = SHARD_RESULTS.jmh_startup_count(
                            int(metadata["jmh_invocation_count"]) - joni_invocations,
                            int(metadata["jmh_process_count"]),
                            joni_invocations,
                            joni_rows,
                            int(metadata["joni_jmh_forks"]) if joni_rows else 0)
                        # Each launch measures one workload, so a forked launch starts its launcher and its forks.
                        self.assertEqual(planned, len(compiler_hints) + sum(1 + int(launch[0]) for launch in launches))

    def testRunsValidateTheFrozenRowProtocolBeforeAnyJvmAndPlansDoNot(self):
        source = SOURCE.with_name("run-shard.sh").read_text(encoding="utf-8")
        validation = 'python3 "${SCRIPT_DIR}/validate-row-protocol.py"\n'
        plan_only = source.index('if [[ "${PLAN_ONLY}" == true ]]; then\n')
        plan_exit = source.index("    exit 0\nfi\n", plan_only) + len("    exit 0\nfi\n")
        # Once per run, after the plan-only exit, as the first command of execution.
        self.assertEqual(1, source.count(validation))
        self.assertEqual(plan_exit, source.rindex("fi\n", 0, source.index(validation)) + len("fi\n"))
        self.assertNotRegex(source[plan_exit:source.index(validation)], r"(?m)^[^#\n]")
        # The validator refuses an altered table.
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            for name in ("validate-row-protocol.py", "row_protocol.py", "row-protocol.tsv", "rows.tsv",
                         "shard-dispatch.tsv", "full-protocol-benchmarks.tsv"):
                shutil.copy(SOURCE.with_name(name), directory / name)
            protocol = directory / "row-protocol.tsv"
            protocol.write_text(protocol.read_text(encoding="utf-8").replace("\t0.05\n", "\t0.5\n", 1),
                                encoding="utf-8")
            result = subprocess.run([sys.executable, str(directory / "validate-row-protocol.py")],
                                    capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("does not match the frozen protocol rules", result.stderr)

    def testUnitHandlerCallRunsEachSelectorAtItsProtocol(self):
        source = SOURCE.with_name("run-shard.sh").read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            (root / "jmh-selectors.tsv").write_text(
                "\t".join(UNIT_SESSION.SELECTOR_FIELDS) + "\n"
                "1\tregulator\tjmh-processes\tprocesses\t2\t3\t300ms\t4\t50ms\t"
                'example.A.first,example.A.second\t{"size":["1","2"]}\t4\n'
                "2\tregulator\tjmh-processes\tprocesses\t1\t1\t1s\t1\t1s\texample.B.whole\troute\t3\n"
                "3\tjoni\tjmh-forks\tforks\t5\t10\t1s\t10\t1s\texample.A.joni\t{}\t1\n"
                '4\tregulator\tjmh-processes\tprocesses\t1\t1\t1s\t1\t1s\texample.C.each\t{"workload":["a"]}\t1\n'
                '5\tregulator\tjmh-processes\tprocesses\t1\t1\t1s\t1\t1s\texample.C.each\t{"workload":["b"]}\t1\n',
                encoding="utf-8")
            output = root / "call.json"
            workload_output = root / "workload.json"
            script = (
                "set -euo pipefail\n"
                "PROTOCOL=qualification\n"
                f"UNIT_DIRECTORY={shlex.quote(str(root))}\n"
                f"RESULT_DIR={shlex.quote(str(root))}\n"
                f"UNIT_TOOL={shlex.quote(str(SOURCE.with_name('unit_session.py')))}\n"
                "JMH_PROCESS_TIME=20ms\nJONI_JMH_TIME=20ms\nCPU_LIST=0\n"
                "jmh_process_jvm_arguments=()\njmh_launcher_jvm_arguments=()\njmh_fork_arguments=-Xmx1g\n"
                "prepare_jmh_host_compiler_arguments() { jmh_host_compiler_arguments=(); }\n"
                "full_protocol_benchmarks() { :; }\n"
                "taskset() { shift 2; \"$@\"; }\n"
                "java() { printf '%s\\n' \"$*\"; }\n"
                + "".join(shell_function(source, name) for name in (
                    "run_jmh_with_classpath", "run_joni_jmh_with_classpath", "run_unit_jmh_selectors",
                    "set_jmh_input_files"))
                + f"run_jmh_with_classpath cp '^example\\.(A\\.(first|joni)|B\\..*)$' {shlex.quote(str(output))} "
                "-p scenario=x,y\n"
                f"set_jmh_input_files {shlex.quote(str(output))}\n"
                "printf 'input %s\\n' \"${JMH_INPUT_FILES[@]}\"\n"
                # A per-workload call's -p value narrows its selectors; its other arguments pass through.
                f"run_jmh_with_classpath cp '^example\\.C\\.each$' {shlex.quote(str(workload_output))} "
                "-p workload=b -tu ns\n"
                f"set_jmh_input_files {shlex.quote(str(workload_output))}\n"
                "printf 'input %s\\n' \"${JMH_INPUT_FILES[@]}\"\n")

            completed = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=True)

            base = output.with_suffix("")
            lines = completed.stdout.splitlines()
            self.assertEqual(5, sum(" -f " in line for line in lines))
            self.assertEqual(
                f"-cp cp org.openjdk.jmh.Main ^(example\\.A\\.first)$ -f 0 -wi 3 -i 4 -w 300ms -r 50ms -foe true "
                f"-prof gc -rf json -rff {base}-selector-1-process-1.json -p size=1,2",
                lines[0])
            self.assertTrue(lines[1].endswith(f"-rff {base}-selector-1-process-2.json -p size=1,2"))
            self.assertIn("^(example\\.B\\.whole)$ -f 0 -wi 1 -i 1 -w 1s -r 1s", lines[2])
            self.assertTrue(lines[2].endswith("-p scenario=x,y"))
            self.assertIn("^(example\\.A\\.joni)$ -f 5 -wi 10 -i 10 -w 1s -r 1s", lines[3])
            self.assertTrue(lines[3].endswith(f"-rff {base}-selector-3.json"))
            self.assertEqual(
                [f"input {base}-selector-1-process-1.json", f"input {base}-selector-1-process-2.json",
                 f"input {base}-selector-2-process-1.json", f"input {base}-selector-3.json"],
                lines[4:8])
            workload_base = workload_output.with_suffix("")
            self.assertTrue(lines[8].endswith(f"-rff {workload_base}-selector-5-process-1.json -p workload=b -tu ns"))
            self.assertEqual([f"input {workload_base}-selector-5-process-1.json"], lines[9:])

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

    def testAwsWrappersTakeTrinoUseFromTheShardHandler(self):
        campaign = (SOURCE.parents[1] / "aws" / "run-campaign.sh").read_text(encoding="utf-8")
        host = (SOURCE.parents[1] / "aws" / "run-host.sh").read_text(encoding="utf-8")
        scope_start = campaign.index('case "$(campaign_shard_handler)" in\n    trino-operations | trino-final-line)')
        # The host takes a shard's handler from dispatch, then its Trino use from that handler.
        host_start = host.index('case "${BENCHMARK_MODE}:${RE2_CAMPAIGN_SHARD_ID:-}" in')
        host_end = host.index("\nesac\n", host.index("\nesac\n", host_start) + 1) + 6
        wrapper_code = "\n".join([
            shell_function(campaign, "campaign_shard_handler"),
            shell_function(campaign, "campaign_uses_trino"),
            campaign[scope_start:campaign.index("\nesac\n", scope_start) + 6],
            "campaign_uses_trino && echo campaign=true || echo campaign=false",
            'echo "scope=${JONI_EVIDENCE_SCOPE}"',
            "(set -- regulator ''",
            host[host_start:host_end],
            ") 2>/dev/null && echo host=false || echo host=true"])
        joni_scopes = {
            "trino-operations": "trino-operation-comparison",
            "trino-final-line": "trino-operation-comparison",
            "lifecycle": "retained-memory-comparison",
            "rebar": "rebar-bulk-text-comparison",
        }

        for row in DISPATCH.values():
            handler = row["native_access_handler"]
            with self.subTest(shard=row["shard_id"]):
                completed = subprocess.run(
                    ["bash"],
                    input=f"set -euo pipefail\nREGULATOR_DIR={shlex.quote(str(SOURCE.parents[3]))} CAMPAIGN_MODE=baseline-shard "
                          f"BENCHMARK_MODE=baseline-shard CAMPAIGN_SHARD_ID={row['shard_id']} "
                          f"RE2_CAMPAIGN_SHARD_ID={row['shard_id']}\n{wrapper_code}\n",
                    text=True, capture_output=True, check=True)

                # Pinned Trino serves these handlers' Joni and Trino comparators, or their LIKE corpus.
                uses_trino = str(handler in {"trino-operations", "trino-final-line", "trino-like", "lifecycle", "rebar"}).lower()
                self.assertEqual(
                    [f"campaign={uses_trino}", f"scope={joni_scopes.get(handler, 'not-used')}", f"host={uses_trino}"],
                    completed.stdout.splitlines())

    def testPlanOnlyIncludesSpecializedAllowances(self):
        expected_metadata = {
            "trino-operations": {
                "candidate_jmh_row_count": "184",
                "joni_jmh_row_count": "184",
                "joni_jmh_forks": "1",
                "jmh_static_duration_estimate_seconds": "1710",
                "route_static_duration_estimate_seconds": "2874",
            },
            "trino-specialized-patterns-a": {
                "jmh_static_duration_estimate_seconds": "1304",
                "route_static_duration_estimate_seconds": "2204",
            },
            "trino-like-specialized": {
                "jmh_static_duration_estimate_seconds": "1568",
                "route_static_duration_estimate_seconds": "2468",
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
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            for row in DISPATCH.values():
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
            forked_invocations=1,
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
        # 108 s measured, then 2 s for each of 11 JVMs: the compiler-hints JVM and five
        # processes, and the comparator's launcher and its four row forks.
        self.assertEqual("130", output.getvalue().strip())

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

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            SHARD_RESULTS.estimate_jmh_runtime(arguments)
        self.assertEqual("798", output.getvalue().strip())
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
        shards = {"trino-operations", "trino-search-edges", "trino-specialized-patterns-a", "trino-specialized-patterns-b"}
        counts = {}
        for row in rows:
            if row["shard_id"] in shards:
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
            **{("trino-specialized-patterns", system): 5 for system in (
                "joni",
                "regulator-generated-native-access",
                "regulator-generated-object-row",
                "regulator-native-access",
                "regulator-object-row",
                "regulator-regular-engine-native-access",
                "regulator-regular-engine-object-row")},
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
