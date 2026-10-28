#!/usr/bin/env python3

import csv
import importlib.util
import json
import re
import shlex
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
with SOURCE.with_name("shard-dispatch.tsv").open(newline="", encoding="utf-8") as dispatch_file:
    DISPATCH = {row["shard_id"]: row for row in csv.DictReader(dispatch_file, delimiter="\t")}


def shell_function(source, name):
    """Returns one function definition from a shell script's source."""
    start = source.index(f"\n{name}()\n") + 1
    return source[start:source.index("\n}\n", start) + 3]


def run_shard_handler(shard, route, replica, joni_order="forward"):
    """Runs a route's measurement handler with its JMH calls and normalization intercepted.

    Returns each handler JMH call as (runner, filter, parameters, time unit, output), where the
    runner is processes or forks, and each normalization call as its argument list.
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
            "run_jmh", "run_jmh_per_workload", "run_ordered_joni_comparison", "run_trino_operations_candidate",
            "run_trino_operations_joni", "run_trino_operations_candidates", "run_trino_operations_shard",
            "run_trino_like_system", "run_trino_like_shard")]
        recorder = (
            "    local runner=$1 filter=$3 output=$4 parameters=() unit=-\n"
            "    shift 4\n"
            "    while (($#)); do\n"
            "        case $1 in\n"
            "            -p) parameters+=(\"$2\"); shift ;;\n"
            "            -tu) unit=$2; shift ;;\n"
            "        esac\n"
            "        shift\n"
            "    done\n"
            "    printf '%s\\t%s\\t%s\\t%s\\t%s\\n' \"${runner}\" \"${filter}\" \"${parameters[*]}\" \"${unit}\" \"${output##*/}\" \\\n"
            "        >> \"${RESULT_DIR}/launches.tsv\"\n")
        script = "\n".join([
            "set -euo pipefail",
            f"SHARD_ID={shard} ROUTE={route} CAMPAIGN_REPLICA_ID={replica} RESULT_DIR={shlex.quote(str(root))}",
            f"systems={dispatch[f'{route_column}_systems']} SELECTION_MANIFEST={shlex.quote(str(MANIFEST))}",
            f"RESULT_TOOL={shlex.quote(str(SOURCE))} JONI_COMPARATOR_ORDER={joni_order}",
            "CLASSPATH=regulator SPECIAL_CLASSPATH=special PINNED_TRINO_CLASSPATH=pinned",
            "secondary_metric_arguments=() mixed_process_arguments=() joni_process_arguments=()",
            "precision_arguments=() normalized_files=()",
            block("JMH_SUITE_ORDER=forward\n"), block("jmh_invocation_per_workload=false\n"),
            "record_jmh_call() {", recorder, "}",
            "run_jmh_with_classpath() { record_jmh_call processes \"$@\"; }",
            "run_joni_jmh_with_classpath() { record_jmh_call forks \"$@\"; }",
            "set_jmh_input_files() { JMH_INPUT_FILES=(\"$1\"); }",
            "python3() {",
            "    if [[ $2 == normalize-* ]]; then",
            "        printf '%s\\x1f' \"$@\" >> \"${RESULT_DIR}/normalizations.txt\"",
            "        printf '\\n' >> \"${RESULT_DIR}/normalizations.txt\"",
            "    else",
            "        command python3 \"$@\"",
            "    fi",
            "}",
            *functions,
            f"run_{handler.replace('-', '_')}_shard",
        ])
        subprocess.run(["bash"], input=script, text=True, capture_output=True, check=True)
        launches = [tuple(line.split("\t")) for line in (root / "launches.tsv").read_text().splitlines()]
        normalizations = [line.split("\x1f")[:-1] for line in (root / "normalizations.txt").read_text().splitlines()]
    return launches, normalizations


def argument_values(arguments, name):
    """Returns the values following one argument of a recorded command line."""
    start = arguments.index(name) + 1
    end = next((index for index in range(start, len(arguments)) if arguments[index].startswith("--")), len(arguments))
    return arguments[start:end]


class TestShardResults(unittest.TestCase):
    def assertEachWorkloadLaunches(self, expected, launches, normalizations):
        """Checks the launches against expected (system, launch) pairs, and that each system's
        normalization reads exactly its own launches' results."""
        self.assertEqual([launch for _, launch in expected], [launch[:4] for launch in launches])
        self.assertEqual(len(launches), len({launch[4] for launch in launches}))
        outputs = {}
        for (system, _), launch in zip(expected, launches):
            outputs.setdefault(system, []).append(launch[4])
        self.assertEqual(list(outputs), [argument_values(arguments, "--system")[0] for arguments in normalizations])
        for arguments in normalizations:
            system = argument_values(arguments, "--system")[0]
            self.assertEqual(sorted(outputs[system]), sorted(Path(path).name for path in argument_values(arguments, "--input")))

    def testSpecializedPatternsRunEachWorkloadInItsOwnJvms(self):
        candidates = ("regulator-native-access", "regulator-regular-engine-native-access", "regulator-generated-native-access")
        manifest = SHARD_RESULTS.load_manifest(MANIFEST)

        def launches_of(shard, system, reverse):
            rows = [row for row in manifest if row["shard_id"] == shard and row["system"] == system]
            runner = "forks" if system == "joni" else "processes"
            return [
                (system, (runner, "^" + row["benchmark"].replace(".", "\\.") + "$",
                          row["parameters"].replace(";", " "), "-"))
                for row in (rows[::-1] if reverse else rows)]

        # Even replicas reverse the Regulator variants and their workloads. Joni keeps its workload
        # order, and JONI_COMPARATOR_ORDER places it after or before every variant.
        for shard in ("trino-specialized-patterns-a", "trino-specialized-patterns-b"):
            for replica, joni_order in ((1, "forward"), (2, "forward"), (1, "reverse")):
                with self.subTest(shard=shard, replica=replica, joni_order=joni_order):
                    launches, normalizations = run_shard_handler(shard, "native-access", replica, joni_order)

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
                launches, normalizations = run_shard_handler("trino-operations", "native-access", replica)

                self.assertEqual(
                    ["trino-operations-regulator-native-access.json", "trino-operations-joni.json"],
                    [launch[4] for launch in launches])
                self.assertEqual({""}, {launch[2] for launch in launches})
                self.assertEqual(
                    ["regulator-native-access", "joni"],
                    [argument_values(arguments, "--system")[0] for arguments in normalizations])

    def testEveryLikeShardNormalizesItsOwnManifestRows(self):
        manifest = SHARD_RESULTS.load_manifest(MANIFEST)
        shards = [shard for shard, row in DISPATCH.items() if row["native_access_handler"] == "trino-like"]
        self.assertIn("trino-like-specialized", shards)
        for shard in shards:
            with self.subTest(shard=shard):
                _, normalizations = run_shard_handler(shard, "native-access", 1)

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
            (system, ("processes", f"^io\\.airlift\\.regulator\\.BenchmarkTrinoLikeTpch\\.{method}$",
                      row["parameters"], "ns"))
            for system, method in methods.items()
            for row in manifest if row["system"] == system]

        # Even replicas reverse the whole sequence, Trino SQL included.
        for replica, expected in ((1, forward), (2, forward[::-1])):
            with self.subTest(replica=replica):
                launches, normalizations = run_shard_handler("trino-like-specialized", "native-access", replica)

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
                    launches, _ = run_shard_handler(*key, 1)
                    filters[key] = [re.compile(launch[1]) for launch in launches]
                for selector in route_selectors:
                    for benchmark in selector["benchmarks"].split(","):
                        with self.subTest(unit=unit["unit_id"], route=route, benchmark=benchmark):
                            self.assertTrue(any(pattern.search(benchmark) for pattern in filters[key]))
        self.assertEqual({"trino-like", "trino-operations"}, handlers)

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
                expected_process_groups=1,
                maximum_relative_standard_error=None))

            self.assertEqual(
                ["row/first", "row/second"],
                [row["row_id"] for row in SHARD_RESULTS.load_observed(output)])

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
                "allow_mixed_process_sets": True,
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

    def testStatisticsMatchTheHostPythonExactly(self):
        # An Arm host's calibration samples; Python 3.9 there recorded this coefficient of variation,
        # which later Pythons' statistics.stdev misses in the last bit.
        metric = {"rawData": [[68.47069138523524], [68.7279953499649], [68.34972041129137]]}

        _, _, coefficient_of_variation = SHARD_RESULTS.metric_statistics(metric)

        self.assertEqual("0.0028196087686909693", SHARD_RESULTS.format_number(coefficient_of_variation))

    def testValidateDriftRejectsValuesAboveFivePercent(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            calibration = root / "calibration.tsv"
            SHARD_RESULTS.write_tsv(calibration, ("before_after_drift",), [
                {"before_after_drift": "0.0500001"},
            ])
            arguments = type("Arguments", (), {
                "calibration": calibration,
                "maximum": 0.05,
            })()

            with self.assertRaisesRegex(ValueError, "exceeded"):
                SHARD_RESULTS.validate_drift(arguments)

    def testCurrentManifestUsesExactTrinoSystemSets(self):
        rows = SHARD_RESULTS.load_manifest(MANIFEST)
        shards = {"trino-operations", "trino-specialized-patterns-a", "trino-specialized-patterns-b"}
        counts = {}
        for row in rows:
            if row["shard_id"] in shards:
                key = (row["suite"], row["system"])
                counts[key] = counts.get(key, 0) + 1

        self.assertEqual({
            ("trino-everyday-operations", "joni"): 104,
            ("trino-everyday-operations", "regulator-native-access"): 104,
            ("trino-everyday-operations", "regulator-object-row"): 104,
            **{("trino-specialized-patterns", system): 5 for system in (
                "joni",
                "regulator-generated-native-access",
                "regulator-generated-object-row",
                "regulator-native-access",
                "regulator-object-row",
                "regulator-regular-engine-native-access",
                "regulator-regular-engine-object-row")},
        }, counts)

    def testCurrentManifestUsesAccurateAllocationContracts(self):
        rows = SHARD_RESULTS.load_manifest(MANIFEST)

        measured_regulator_rows = [row for row in rows if row["system"].startswith("regulator")]
        self.assertTrue(measured_regulator_rows)
        self.assertNotIn(
            "not-measured",
            {row["allocation_contract"] for row in measured_regulator_rows})

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
            })()

            with self.assertRaisesRegex(ValueError, "differ from the manifest"):
                SHARD_RESULTS.normalize_jmh(arguments)

    def testNormalizeNativeTakesEachWorkloadsMedianRepetition(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest = root / "rows.tsv"
            expected = [{
                "row_id": f"shard/{workload}/extract/native-re2",
                "shard_id": "shard",
                "suite": "re2-specialized-patterns",
                "system": "native-re2",
                "benchmark": "extract",
                "parameters": f"workload={workload}",
                "comparator": "native-re2",
                "expected_result": "native-re2-differential",
                "allocation_contract": "not-measured",
                "workload_checksum": "specialized=0",
            } for workload in ("keyValue", "quotedField")]
            SHARD_RESULTS.write_tsv(manifest, SHARD_RESULTS.MANIFEST_FIELDS, expected)
            semantic_receipt = root / "semantic.tsv"
            SHARD_RESULTS.write_tsv(semantic_receipt, SHARD_RESULTS.SEMANTIC_FIELDS, [{
                "route": "native-access", "tests": "TestExample", "outcome": "accepted",
                "semantic_result_digest": "0" * 64}])

            def result(workload, times, repetitions=3, indices=None, context=(), error=False):
                path = root / f"{workload}.json"
                failure = {"error_occurred": True, "error_message": "runner failed"} if error else {}
                # The score is the median wall time, so the CPU time differs from it.
                path.write_text(json.dumps({
                    "context": {"host_name": "host", "parameter.workload": workload, **dict(context)},
                    "benchmarks": [
                        *({"name": "extract", "run_name": "extract", "run_type": "iteration",
                           "repetitions": repetitions, "repetition_index": index, "iterations": 1000,
                           "real_time": time, "cpu_time": time * 2, "time_unit": "ns", **failure}
                          for index, time in zip(indices or range(len(times)), times)),
                        {"name": "extract_median", "run_name": "extract", "run_type": "aggregate",
                         "aggregate_name": "median", "repetitions": repetitions, "real_time": 1,
                         "time_unit": "ns"}]}))
                return path

            output = root / "observed.tsv"
            arguments = SimpleNamespace(manifest=manifest, shard="shard", system="native-re2", output=output,
                                        semantic_receipt=semantic_receipt, expected_repetitions=3,
                                        input=[result("keyValue", (300, 10, 20)), result("quotedField", (5, 7, 6))])
            SHARD_RESULTS.normalize_native(arguments)
            rows = SHARD_RESULTS.load_observed(output)
            self.assertEqual([(row["row_id"], row["score"], row["score_unit"], row["allocation_bytes"])
                              for row in rows], [
                (expected[0]["row_id"], "20", "ns/op", "not-measured"),
                (expected[1]["row_id"], "6", "ns/op", "not-measured")])
            # A run that stopped early has fewer repetitions than it planned.
            arguments.input = [result("keyValue", (30, 10)), result("quotedField", (5, 7, 6))]
            with self.assertRaisesRegex(ValueError, "has 2 of 3 repetitions"):
                SHARD_RESULTS.normalize_native(arguments)
            # Setting BENCHMARK_DRY_RUN in the environment makes a dry run, which times one repetition.
            arguments.input = [result("keyValue", (30,), repetitions=1, context={"dry_run": "true"}),
                               result("quotedField", (5, 7, 6))]
            with self.assertRaisesRegex(ValueError, "is a dry run"):
                SHARD_RESULTS.normalize_native(arguments)
            arguments.input = [result("keyValue", (30, 10, 20), indices=(0, 0, 1)), result("quotedField", (5, 7, 6))]
            with self.assertRaisesRegex(ValueError, "repetition indices"):
                SHARD_RESULTS.normalize_native(arguments)
            arguments.input = [result("keyValue", (30, 10, 20), error=True), result("quotedField", (5, 7, 6))]
            with self.assertRaisesRegex(ValueError, "failed or was skipped"):
                SHARD_RESULTS.normalize_native(arguments)
            arguments.input = arguments.input[1:]
            with self.assertRaisesRegex(ValueError, "differ from the manifest"):
                SHARD_RESULTS.normalize_native(arguments)

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



if __name__ == "__main__":
    unittest.main()
