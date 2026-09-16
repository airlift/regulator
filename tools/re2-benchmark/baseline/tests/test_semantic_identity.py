import contextlib
import csv
import hashlib
import importlib.util
import io
import shlex
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


BASE = Path(__file__).parents[1]
sys.path.insert(0, str(BASE))
import aggregate_results
import semantic_evidence
import shard_results

SPEC = importlib.util.spec_from_file_location("rebar_identity_verifier", BASE / "validate-rebar-shard-verification.py")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class TestSemanticIdentity(unittest.TestCase):
    def test_native_probe_options_do_not_reach_measurements(self):
        source = (BASE / "run-shard.sh").read_text()
        options = source[source.index("route_jvm_arguments=("):source.index("prepare_jmh_host_compiler_arguments()")]
        probe = source[source.index("expected_native_access=false\n"):source.index('(\n    cd "${ROOT}"\n    JDK_JAVA_OPTIONS=')]
        for route in ("native-access", "object-row"):
            with self.subTest(route=route), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                script = "set -euo pipefail\n"
                script += f"ROUTE={shlex.quote(route)}\nHEAP_SIZE=8g\nCPU_LIST=0\nCLASSPATH=fixture\n"
                script += f"semantic_log={shlex.quote(str(root / 'probe.log'))}\n"
                script += 'taskset() { shift 2; "$@"; }\njava() { printf "%s\\n" "$@"; }\n'
                script += options + probe
                script += 'printf "%s\\n" "${route_jvm_arguments[@]}" "${jmh_process_jvm_arguments[@]}" "${jmh_fork_arguments}"\n'
                result = subprocess.run(["bash"], input=script, text=True, capture_output=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                probe_arguments = (root / "probe.log").read_text().splitlines()
                expected = "true" if route == "native-access" else "false"
                flag = f"-Dio.airlift.regulator.dfa.native-reader-probe={expected}"
                self.assertIn(flag, probe_arguments)
                self.assertEqual(["io.airlift.regulator.DfaAbsolutePointerProbe", expected], probe_arguments[-2:])
                measurement_arguments = result.stdout.splitlines()[len(probe_arguments):]
                self.assertTrue(measurement_arguments)
                self.assertFalse(any("native-reader-probe" in argument for argument in measurement_arguments))

    def test_real_shard_timeout_measurement_and_empty_selection(self):
        source = (BASE / "run-shard.sh").read_text()
        ordered = source[source.index("run_ordered_joni_comparison()\n"):source.index("run_trino_operations_shard()\n")]
        run_shard = source[source.index("run_rebar_shard()\n"):source.index('case "${handler}" in', source.index("run_rebar_shard()\n"))]
        manifest = BASE / "rows.tsv"
        systems = "joni,native-re2-before,native-re2-after,regulator-native-access"
        expected = shard_results.selected_rows(shard_results.load_manifest(manifest), "rebar-g", systems)
        engines = {system: engine for engine, system in VERIFIER.ENGINE_SYSTEMS.items()}
        joni_ids = [row["row_id"] for row in expected if row["system"] == "joni"]
        for order in ("forward", "reverse"):
            for scenario in ("mixed-verification", "all-verification", "mixed-measurement", "all-measurement"):
                with self.subTest(order=order, scenario=scenario), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    for name in ("raw", "logs", "normalized"):
                        (root / name).mkdir()
                    timeout_ids = set(joni_ids if scenario.startswith("all") else joni_ids[:1])
                    semantic_timeouts = timeout_ids if scenario.endswith("verification") else set()
                    outcomes = root / "raw/rebar-outcomes.tsv"
                    # Use the actual verifier, then the actual shell filter and measurement path.
                    verification = root / "verification.csv"
                    with verification.open("w", newline="") as output:
                        writer = csv.writer(output)
                        for row in expected:
                            if row["system"] == "joni":
                                model = dict(item.split("=", 1) for item in row["parameters"].split(";"))["model"]
                                writer.writerow([row["benchmark"], model, "joni/trino", "pinned", "timeout: exceeded 30s" if row["row_id"] in semantic_timeouts else "OK"])
                    VERIFIER.validate(manifest, "rebar-g", {"joni"}, verification, outcomes, 1 if semantic_timeouts else 0)
                    for phase in ("primary", "joni"):
                        with (root / f"{phase}.csv").open("w", newline="") as output:
                            writer = csv.writer(output)
                            writer.writerow(["name", "model", "engine", "engine_version", "err", "median"])
                            for row in expected:
                                if (row["system"] == "joni") != (phase == "joni") or row["row_id"] in semantic_timeouts:
                                    continue
                                model = dict(item.split("=", 1) for item in row["parameters"].split(";"))["model"]
                                timeout = row["row_id"] in timeout_ids
                                writer.writerow([row["benchmark"], model, engines[row["system"]], "pinned", "timeout: exceeded 120s" if timeout else "", "" if timeout else "100ns"])
                    shard_results.write_tsv(root / "semantic-gate.tsv", shard_results.SEMANTIC_FIELDS, [{
                        "route": "native-access", "tests": "TestFixture", "outcome": "accepted", "semantic_result_digest": "a" * 64,
                    }])
                    variables = {
                        "RESULT_DIR": str(root), "SCRIPT_DIR": str(BASE), "RESULT_TOOL": str(BASE / "shard_results.py"),
                        "MANIFEST": str(manifest), "SHARD_ID": "rebar-g", "systems": systems,
                        "REBAR_EXECUTABLE": "fake_rebar", "CPU_LIST": "0", "REBAR_BENCHMARK_DIRECTORY": str(root),
                        "REBAR_PRIMARY_ENGINE_FILTER": "primary", "REBAR_PRIMARY_BENCHMARK_FILTER": ".*",
                        "REBAR_MODEL_FILTER": "count", "REBAR_MAXIMUM_TIME": "1s", "REBAR_WARMUP_TIME": "1s",
                        "REBAR_MEASUREMENT_TIMEOUT": "120s", "JONI_COMPARATOR_ORDER": order,
                    }
                    script = "set -euo pipefail\n" + "\n".join(f"{key}={shlex.quote(value)}" for key, value in variables.items()) + "\n"
                    script += '''
normalized_files=()
export REBAR_HEAP_PRETOUCH=false
taskset() { shift 2; "$@"; }
fake_rebar() {
    [[ ${REBAR_HEAP_PRETOUCH} == true ]] || return 99
    local engine=primary
    while [[ $# -gt 0 ]]; do
        if [[ "$1" == -e ]]; then engine=$2; fi
        shift
    done
    if [[ "${engine}" == '^joni/trino$' ]]; then
        echo joni >> "${RESULT_DIR}/calls"
        cat "${RESULT_DIR}/joni.csv"
    else
        echo primary >> "${RESULT_DIR}/calls"
        cat "${RESULT_DIR}/primary.csv"
    fi
}
REBAR_JONI_BENCHMARK_FILTER=$(python3 "${RESULT_TOOL}" jmh-filter --manifest "${MANIFEST}" --shard "${SHARD_ID}" --system joni --rebar-outcomes "${RESULT_DIR}/raw/rebar-outcomes.tsv")
'''
                    result = subprocess.run(["bash"], input=script + ordered + run_shard + "run_rebar_shard\n", text=True, capture_output=True)
                    self.assertEqual(0, result.returncode, result.stderr)
                    calls = (root / "calls").read_text().splitlines()
                    self.assertEqual(["primary"] if scenario == "all-verification" else (["primary", "joni"] if order == "forward" else ["joni", "primary"]), calls)
                    self.assertTrue((root / "native-bracket.tsv").is_file())
                    if scenario == "all-verification":
                        self.assertEqual(0, (root / "raw/rebar-joni-measurements.csv").stat().st_size)
                    with (root / "normalized/rebar-joni.tsv").open() as input_file:
                        observed = list(csv.DictReader(input_file, delimiter="\t"))
                    self.assertEqual(set(joni_ids), {row["row_id"] for row in observed})
                    for row in observed:
                        timeout = row["row_id"] in timeout_ids
                        self.assertEqual("did-not-finish" if timeout else "accepted", row["outcome"])
                        self.assertEqual("" if timeout else "100", row["score"])

    def test_sibling_timeout_does_not_change_successful_contracts(self):
        manifest = BASE / "rows.tsv"
        systems = {"joni", "native-re2-before", "native-re2-after", "regulator-native-access"}
        expected = shard_results.selected_rows(shard_results.load_manifest(manifest), "rebar-g", ",".join(sorted(systems)))
        by_id = {row["row_id"]: row for row in expected}
        timeout_id = "rebar-g/curated/14-quadratic/10x/count/joni"
        control_prefix = "rebar-g/curated/02-literal-alternate/sherlock-casei-ru/count/"
        engines = {system: engine for engine, system in VERIFIER.ENGINE_SYSTEMS.items()}
        executions = []
        digests = []
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            for replica, timeout in enumerate((False, True, False, False)):
                root = Path(directory) / str(replica)
                root.mkdir()
                evidence_files = []
                outcomes = root / "outcomes.tsv"
                for filename, selected in (("primary.csv", systems - {"joni"}), ("joni.csv", {"joni"})):
                    verification = root / filename
                    with verification.open("w", newline="") as output:
                        writer = csv.writer(output)
                        for row in expected:
                            if row["system"] in selected:
                                model = dict(item.split("=", 1) for item in row["parameters"].split(";"))["model"]
                                status = "timeout: exceeded 30s" if timeout and row["row_id"] == timeout_id else "OK"
                                writer.writerow((row["benchmark"], model, engines[row["system"]], "pinned-fixture-version", status))
                    VERIFIER.validate(manifest, "rebar-g", selected, verification, outcomes,
                                      1 if timeout and selected == {"joni"} else 0, selected == {"joni"})
                    evidence_files.append(verification)

                reports = root / "reports"
                reports.mkdir()
                (reports / "TEST-fixture.xml").write_text('<testsuite><testcase classname="TestFixture" name="verified"/></testsuite>')
                log = root / "semantic.log"
                log.write_text("")
                evidence = semantic_evidence.build_evidence(SimpleNamespace(
                    reports=reports, expected_tests="TestFixture", route="native-access", handler="rebar",
                    native_access="true", semantic_log=log, rebar_verification=evidence_files))
                evidence_path = root / "semantic-evidence.tsv"
                shard_results.write_tsv(evidence_path, semantic_evidence.FIELDS,
                                        [dict(zip(semantic_evidence.FIELDS, row)) for row in evidence])
                digest = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
                digests.append(digest)
                receipt = root / "semantic-gate.tsv"
                shard_results.write_tsv(receipt, shard_results.SEMANTIC_FIELDS, [{
                    "route": "native-access", "tests": "TestFixture", "outcome": "accepted", "semantic_result_digest": digest,
                }])

                measurements = root / "measurements.csv"
                with measurements.open("w", newline="") as output:
                    writer = csv.writer(output)
                    writer.writerow(("name", "model", "engine", "engine_version", "err", "median"))
                    for row in expected:
                        if timeout and row["row_id"] == timeout_id:
                            continue
                        model = dict(item.split("=", 1) for item in row["parameters"].split(";"))["model"]
                        writer.writerow((row["benchmark"], model, engines[row["system"]], "pinned-fixture-version", "", "100ns"))
                observed = {}
                for system in sorted(systems):
                    output = root / f"{system}.tsv"
                    arguments = SimpleNamespace(manifest=manifest, shard="rebar-g", system=system,
                                                input=measurements, semantic_receipt=receipt,
                                                rebar_outcomes=outcomes, output=output)
                    shard_results.normalize_rebar(arguments)
                    with output.open() as input_file:
                        observed.update({row["row_id"]: row for row in csv.DictReader(input_file, delimiter="\t")})
                executions.append(observed)
                self.assertEqual("did-not-finish" if timeout else "accepted", observed[timeout_id]["outcome"])
                if timeout:
                    self.assertEqual("", observed[timeout_id]["score"])
                    self.assertIn("timeout: exceeded 30s", outcomes.read_text())

                for invalid, message in (("failed", "not accepted"), ("bad-digest", "invalid result digest")):
                    shard_results.write_tsv(receipt, shard_results.SEMANTIC_FIELDS, [{
                        "route": "native-access", "tests": "TestFixture",
                        "outcome": "failed" if invalid == "failed" else "accepted",
                        "semantic_result_digest": "invalid" if invalid == "bad-digest" else digest,
                    }])
                    arguments.output = root / f"rejected-{invalid}.tsv"
                    with self.assertRaisesRegex(ValueError, message):
                        shard_results.normalize_rebar(arguments)
                    self.assertFalse(arguments.output.exists())

        self.assertNotEqual(digests[0], digests[1])
        self.assertEqual(digests[0], digests[2])
        for system in systems:
            row_id = control_prefix + system
            hosts = []
            for replica, observed in enumerate(executions):
                hosts.append({
                    **by_id[row_id], **observed[row_id], "platform": "r8i", "rebar_corpus": "curated",
                    "confirmation": "true" if replica == 3 else "false", "semantic_outcome": "verified",
                    "calibration_drift": "0", "native_bracket_drift": "0",
                    "contract_identity": observed[row_id]["result_checksum"],
                })
            for selected in (hosts[:3], hosts):
                _, reduced, _ = aggregate_results.build_row_aggregates(selected, {}, set(), {})
                self.assertEqual("consistent", reduced[0]["contract_identity_status"], system)
                self.assertEqual("", reduced[0]["unresolved_reasons"], system)
                _, jobs = aggregate_results.build_confirmation_jobs(reduced, selected, [], [], [])
                self.assertEqual([], jobs)
            changed = [*hosts[:2], {**hosts[2], "contract_identity": "0" * 64}]
            _, reduced, _ = aggregate_results.build_row_aggregates(changed, {}, set(), {})
            self.assertEqual("mismatch", reduced[0]["contract_identity_status"])


if __name__ == "__main__":
    unittest.main()
