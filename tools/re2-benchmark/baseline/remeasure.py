"""Collect exact baseline subsets with independent JMH forks after semantic verification.

Replacement measurements have their own frozen protocol and acceptance receipt.
The earlier baseline and diagnostic artifacts are never relabeled as replacements.
"""

import argparse
import csv
import hashlib
import importlib.util
import json
import math
import os
import re
from pathlib import Path
import sys
import subprocess
import tarfile

BASELINE = Path(__file__).resolve().parent
ROOT = BASELINE.parents[2]
sys.path.insert(0, str(BASELINE))
import acceptance
import retained_selection
import semantic_preparation

spec = importlib.util.spec_from_file_location("measurement_commands", ROOT / "tools/re2-benchmark/diagnostics/run.py")
commands = importlib.util.module_from_spec(spec)
spec.loader.exec_module(commands)

SYSTEMS = ("regulator-object-row", "regulator-native-access", "joni")
SHARDS = ("trino-operations", "trino-final-line")
QUALIFICATION_EVIDENCE = BASELINE / "qualification-evidence"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def load_qualification_evidence(sha256):
    if not isinstance(sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise ValueError("replacement protocol has invalid qualification evidence identity")
    path = QUALIFICATION_EVIDENCE / f"{sha256}.json"
    if not path.is_file():
        raise ValueError("replacement protocol qualification evidence is missing")
    payload = path.read_bytes()
    if hashlib.sha256(payload).hexdigest() != sha256:
        raise ValueError("replacement protocol qualification evidence hash differs")
    return json.loads(payload)


def validate_qualification(protocol):
    expected_allocations = {
        "r8i": {"instance_type": "r8i.large", "vcpus": 2},
        "r8g": {"instance_type": "r8g.large", "vcpus": 2},
        "r9g": {"instance_type": "r9g.2xlarge", "vcpus": 8},
    }
    if protocol.get("allocations") != expected_allocations:
        raise ValueError("replacement protocol has invalid worker allocations")
    if protocol.get("qualified_by_pilot") is not True:
        raise ValueError("replacement protocol has not passed the independent pilot")
    evidence = load_qualification_evidence(protocol.get("qualification_attestation_sha256"))
    if (evidence.get("schema_version") != 1 or
            evidence.get("source_decision_sha256") != protocol.get("pilot_qualification_sha256")):
        raise ValueError("replacement protocol qualification evidence identifies a different decision")
    if protocol.get("family") == "retained-baseline-v1":
        if (evidence.get("kind") != "retained-protocol-qualification" or
                evidence.get("qualified_for_production_integration") is not True or
                evidence.get("select_by_timing") is not False or
                evidence.get("allocations") != {
                    platform: allocation["instance_type"]
                    for platform, allocation in protocol["allocations"].items()
                }):
            raise ValueError("retained replacement protocol lacks a successful qualification decision")
        correction = load_qualification_evidence(protocol.get("easy2_correction_attestation_sha256"))
        if (correction.get("schema_version") != 1 or correction.get("kind") != "easy2-correction-decision" or
                correction.get("source_decision_sha256") != protocol.get("easy2_correction_decision_sha256") or
                correction.get("exact_identity") != "traditional-extra-easy2/failed-search/easy2/dfa/262144" or
                correction.get("jobs") != 9 or correction.get("readiness") !=
                "Review complete exact outputs before acceptance. No substitution of pilot or favorable windows."):
            raise ValueError("retained replacement protocol lacks the frozen easy2 correction decision")
    elif (evidence.get("kind") != "replacement-protocol-qualification" or
          evidence.get("qualified") is not True or evidence.get("failures") != []):
        raise ValueError("replacement protocol lacks a successful qualification decision")


def partition_plan(shard, partition, protocol):
    validate_qualification(protocol)
    if protocol.get("family") == "retained-baseline-v1":
        return retained_selection.partition_plan(BASELINE, shard, partition, protocol)
    if shard not in SHARDS or type(partition) is not int or partition < 0:
        raise ValueError("invalid replacement partition")
    if (type(protocol.get("schema_version")) is not int or protocol.get("schema_version") != 1 or
            type(protocol.get("qualified_by_pilot")) is not bool or
            type(protocol.get("forks")) is not int or protocol.get("forks") != 5 or
            type(protocol.get("warmup_iterations")) is not int or protocol.get("warmup_iterations") != 20 or
            type(protocol.get("measurement_iterations")) is not int or protocol.get("measurement_iterations") != 20 or
            type(protocol.get("iteration_seconds")) is not int or protocol.get("iteration_seconds") != 1 or
            protocol.get("gc") != "G1" or protocol.get("cpu_allowance") != "0,1" or protocol.get("heap") != "8g"):
        raise ValueError("unrecognized replacement protocol")
    manifest = BASELINE / "rows.tsv"
    if digest(manifest) != protocol["original_row_manifest_sha256"]:
        raise ValueError("original row manifest changed")
    with manifest.open() as source:
        rows = [row for row in csv.DictReader(source, delimiter="\t") if row["shard_id"] == shard]
    groups = {}
    for row in rows:
        identity = row["row_id"].rsplit("/", 1)[0]
        systems = groups.setdefault(identity, {})
        if row["system"] in systems:
            raise ValueError("duplicate original row")
        systems[row["system"]] = row
    if any(set(systems) != set(SYSTEMS) for systems in groups.values()):
        raise ValueError("incomplete original comparison")
    size = protocol["operations_per_partition"]
    if type(size) is not int or not 1 <= size <= 6:
        raise ValueError("invalid replacement partition size")
    selected = sorted(groups)[partition * size:(partition + 1) * size]
    if not selected:
        raise ValueError("replacement partition is outside the manifest")
    selections = []
    for identity in selected:
        for system in SYSTEMS:
            row = groups[identity][system]
            benchmark = row["benchmark"]
            if shard == "trino-final-line":
                benchmark = ("io.trino.operator.scalar.BenchmarkTrinoFinalLine." + benchmark +
                             ("Joni" if system == "joni" else "Regulator"))
            selections.append({"original_row": row, "benchmark": benchmark,
                               "parameters": dict(value.split("=", 1) for value in row["parameters"].split(";")),
                               "route": "object-row" if system == "regulator-object-row" else "native-access",
                               "gc": "g1"})
    return {"kind": "replacement-measurement", "schema_version": 1, "shard": shard,
            "partition": partition, "protocol": protocol, "selections": selections}


def validate_host_allocation(plan, receipt, session):
    identity = receipt.get("identity", receipt)
    allocation = plan["protocol"]["allocations"].get(identity.get("platform"))
    environment = acceptance.read_properties(session / "environment-manifest.txt")
    if (allocation is None or identity.get("instance_type") != allocation["instance_type"] or
            environment.get("logical_cpu_count") != str(allocation["vcpus"])):
        raise ValueError("replacement host differs from its frozen allocation")


def validate_timing(path, selection, protocol):
    commands.validate_result(path, selection, protocol)
    row = json.loads(path.read_text())[0]
    expected_flags = ["--add-modules=jdk.incubator.vector", "--illegal-native-access=deny",
                      "-Xms8g", "-Xmx8g", "-XX:+AlwaysPreTouch", "-XX:+UseG1GC"]
    if selection["route"] == "native-access":
        expected_flags.insert(0, "--enable-native-access=ALL-UNNAMED")
    if sorted(row["jvmArgs"]) != sorted(expected_flags):
        raise ValueError("replacement JVM arguments changed")
    if (row.get("jdkVersion") != "25.0.4" or row.get("vmVersion") != "25.0.4+7-LTS" or row.get("jmhVersion") != "1.37"):
        raise ValueError("replacement JVM or JMH version changed")
    if (row["mode"] != "avgt" or row["threads"] != 1 or row["forks"] != protocol["forks"] or
            row["warmupIterations"] != protocol["warmup_iterations"] or
            row["measurementIterations"] != protocol["measurement_iterations"] or
            row["warmupTime"] != "1 s" or row["measurementTime"] != "1 s" or
            row["primaryMetric"]["scoreUnit"] != "ns/op"):
        raise ValueError("replacement timing protocol changed")
    if any(type(value) not in (int, float) or not math.isfinite(value) or value <= 0
           for fork in row["primaryMetric"]["rawData"] for value in fork):
        raise ValueError("invalid replacement sample")
    if "gc.alloc.rate.norm" in row.get("secondaryMetrics", {}) and not protocol.get("allocation_only", False):
        raise ValueError("primary timing includes allocation instrumentation")
    return row


def classpath(session, output, shard):
    shared = Path(os.environ["BASELINE_SHARED_WORK_DIR"])
    jar, identity = commands.released_artifact.prepare(ROOT, "1.0", output / "release")
    like = shard in {"trino-like", "like-compile", "like-single-use", "like-dfa-single-use"}
    if shard in SHARDS or like:
        pinned = (shared / "pinned-trino/classpath.txt").read_text().strip().split(":")
        entries = [str(jar) if Path(entry).resolve() == ROOT / "target/classes" else entry for entry in pinned]
        if shard == "trino-final-line" or like:
            generated = "trino-like" if like else "trino-final-line"
            entries.insert(0, str(session / "routes/native-access/generated" / generated / "classes"))
    else:
        receipt = json.loads((session / "routes/native-access/release-artifact.json").read_text())
        entries = [str(jar) if entry["path"] == receipt["jar_path"] else entry["path"] for entry in receipt["classpath"]]
    value = ":".join(dict.fromkeys(entries))
    commands.released_artifact.attest(value, jar, identity, output / "release")
    return value



def timing_protocol(plan):
    protocol = plan["protocol"]
    if protocol.get("family") == "retained-baseline-v1":
        return retained_selection.timing_protocol(plan["shard"], protocol)
    return protocol


def native_control(output, phase, plan):
    target = output / ("native-" + phase)
    binary = ROOT / "target/re2-benchmark-build/regexp_benchmark"
    seconds = timing_protocol(plan).get("native_measurement_seconds", 1)
    warmup = 10 if "native_names" in plan else 1
    timeout = max(900, 120 + 2 * len(native_names(plan)) * (5 * seconds + warmup))
    commands.execute(["taskset", "--cpu-list", "0", str(binary),
                      "--benchmark_filter=^(" + "|".join(re.escape(name) for name in native_names(plan)) + ")$",
                      "--benchmark_repetitions=5", "--benchmark_min_time=" + str(seconds) + "s",
                      "--benchmark_min_warmup_time=" + str(warmup), "--benchmark_time_unit=ns",
                      "--benchmark_out_format=json", "--benchmark_out=" + str(target / "native.json")], target, timeout=timeout)
    validate_native(target / "native.json", native_names(plan))


def native_names(plan):
    return plan.get("native_names", [f"Search_Easy{case}_CachedDFA/262144/threads:1" for case in (0, 2)])


def validate_native(path, names=None):
    rows = [row for row in json.loads(path.read_text())["benchmarks"] if row.get("run_type") == "iteration"]
    expected = set(names) if names is not None else set(native_names({}))
    if {row["run_name"] for row in rows} != expected:
        raise ValueError("native control selection changed")
    for name in expected:
        samples = [row for row in rows if row["run_name"] == name]
        if len(samples) != 5 or any(row["time_unit"] != "ns" or any(type(row.get(clock)) not in (int, float) or
                                    not math.isfinite(row[clock]) or row[clock] <= 0
                                    for clock in ("cpu_time", "real_time")) for row in samples):
            raise ValueError("invalid native control samples")


def measure_allocation(output, index, selection, classpath_value, protocol, environment):
    target = output / f"allocation-{index:02d}"
    command = commands.jmh_command(
        classpath_value, selection, selection["route"], "0,1", "1s", protocol, target)
    commands.execute(command + ["-prof", "gc"], target, environment=environment)
    row = validate_timing(target / "jmh.json", selection, protocol)
    metric = row["secondaryMetrics"]["gc.alloc.rate.norm"]
    if metric["scoreUnit"] != "B/op" or not math.isfinite(metric["score"]) or metric["score"] < 0:
        raise ValueError("invalid replacement allocation")
    checkpoint(output)


def measure_allocations(output, selections, classpath_value, protocol, environment):
    for index, selection in selections:
        measure_allocation(output, index, selection, classpath_value, protocol, environment)


def validate_completed(output):
    marker = json.loads((output / "complete.json").read_text())
    if marker.get("kind") != "replacement-measurement":
        raise ValueError("diagnostics cannot qualify replacement measurements")
    if (marker["plan_sha256"] != digest(output / "plan.json") or
            marker["artifacts_sha256"] != digest(output / "artifacts.json")):
        raise ValueError("replacement receipt hash mismatch")
    hashes = json.loads((output / "artifacts.json").read_text())
    expected_files = {str(path.relative_to(output)) for path in output.rglob("*") if path.is_file()} - {"artifacts.json", "complete.json"}
    if set(hashes) != expected_files:
        raise ValueError("replacement artifact coverage differs")
    for name, checksum in hashes.items():
        path = (output / name).resolve()
        if not path.is_relative_to(output.resolve()) or digest(path) != checksum:
            raise ValueError("replacement artifact changed")
    plan = json.loads((output / "plan.json").read_text())
    session = output.parent
    smoke = preparation_receipt(session)
    if smoke != json.loads((output / "smoke-receipt.json").read_text()):
        raise ValueError("replacement semantic evidence changed")
    release = commands.released_artifact.manifest(ROOT, "1.0")
    commands.released_artifact.validate_baseline(session, release)
    attestation = json.loads((output / "release/release-artifact.json").read_text())
    commands.released_artifact.validate_saved(attestation)
    if attestation["manifest"] != release or digest(output / "release/regulator-1.0.jar") != release["jar_sha256"]:
        raise ValueError("replacement used a different release")
    if not plan["protocol"]["qualified_by_pilot"] or plan != partition_plan(plan["shard"], plan["partition"], plan["protocol"]):
        raise ValueError("replacement plan differs from frozen selection")
    if marker["primary_selections"] != len(plan["selections"]):
        raise ValueError("replacement selection count differs")
    for index, selection in enumerate(plan["selections"]):
        for phase in ("timing", "allocation"):
            protocol = timing_protocol(plan) if phase == "timing" else {
                **timing_protocol(plan), "forks": 1, "warmup_iterations": 3, "measurement_iterations": 3, "allocation_only": True}
            target = output / f"{phase}-{index:02d}"
            validate_timing(target / "jmh.json", selection, protocol)
            command = json.loads((target / "command.json").read_text())
            if command[:3] != ["taskset", "--cpu-list", "0,1"] or ("-prof" in command) != (phase == "allocation"):
                raise ValueError("replacement CPU or profiler contract changed")
            if json.loads((target / "execution.json").read_text())["returncode"] != 0:
                raise ValueError("replacement command failed")
        resources = dict(line.strip().rsplit(": ", 1) for line in
                         (output / f"timing-{index:02d}/resources.txt").read_text().splitlines() if ": " in line)
        if int(resources["Exit status"]) != 0 or not 0 < int(resources["Maximum resident set size (kbytes)"]) < 14 * 1024 * 1024:
            raise ValueError("replacement timing exceeded resource bounds")
        target = output / f"timing-{index:02d}"
        before = dict(line.split() for line in (target / "proc-vmstat-before.txt").read_text().splitlines())
        after = dict(line.split() for line in (target / "proc-vmstat-after.txt").read_text().splitlines())
        if any(int(after[key]) != int(before[key]) for key in ("oom_kill", "pswpin", "pswpout")):
            raise ValueError("replacement timing encountered OOM or swapping")
        memory = {line.split()[0].removesuffix(":"): int(line.split()[1])
                  for line in (target / "proc-meminfo-after.txt").read_text().splitlines()}
        if memory["MemAvailable"] < 2 * 1024 * 1024:
            raise ValueError("replacement timing left insufficient memory headroom")
    for phase in ("before", "after"):
        validate_native(output / ("native-" + phase) / "native.json", native_names(plan))
    return plan


def preparation_receipt(session):
    if (session / "semantic-preparation.json").exists():
        return semantic_preparation.validate(session)
    if any("verification_only=true" in path.read_text() for path in (session / "routes").glob("*/run-metadata.txt")):
        raise ValueError("missing semantic preparation receipt")
    receipt = acceptance.validate_host_results(BASELINE / "rows.tsv", session / "session.tsv", session / "observed-rows.tsv")
    stored = acceptance.read_one_tsv(session / "acceptance-receipt.tsv", acceptance.RECEIPT_FIELDS)
    if receipt != stored:
        raise ValueError("semantic smoke receipt changed")
    return receipt


def checkpoint(output):
    uri = os.environ.get("BENCHMARK_PARTIAL_RESULT_URI")
    if not uri:
        raise ValueError("replacement collection requires partial-result recovery")
    archive = output.parent / "remeasurement-checkpoint.tar.gz"
    with tarfile.open(archive, "w:gz") as target:
        target.add(output, arcname="re2-results/remeasurement")
        names = ["environment.txt"]
        if (output.parent / "semantic-preparation.json").exists():
            names += ["semantic-preparation.json", "routes", "environment-manifest.txt"]
        else:
            names += ["session.tsv", "acceptance-receipt.tsv"]
        for name in names:
            target.add(output.parent / name, arcname="re2-results/" + name)
    # Upload only between measurements. A Spot interruption can lose the active
    # command, but every completed command remains recoverable by the controller.
    subprocess.run(["aws", "s3", "cp", str(archive), uri, "--only-show-errors"], check=True, timeout=120)

def run(plan, session):
    validate_qualification(plan["protocol"])
    protocol = timing_protocol(plan)
    if os.environ.get("REGULATOR_RELEASE_VERSION") != "1.0":
        raise ValueError("replacement requires the pinned 1.0 release")
    receipt = preparation_receipt(session)
    if receipt["shard_id"] != plan["shard"]:
        raise ValueError("smoke shard differs from replacement")
    validate_host_allocation(plan, receipt, session)
    output = session / "remeasurement"
    output.mkdir(exist_ok=False)
    save(output / "plan.json", plan)
    save(output / "smoke-receipt.json", receipt)
    cp = classpath(session, output, plan["shard"])
    commands.execute([str(ROOT / "tools/re2-benchmark/build.sh")], output / "native-build", timeout=900,
                     environment={**os.environ, "RE2_NATIVE_TUNING": "host", "RE2_BENCHMARK_TARGETS": "regexp_benchmark"})
    native_control(output, "before", plan)
    checkpoint(output)
    selections = list(enumerate(plan["selections"]))
    if int(os.environ["RE2_CAMPAIGN_REPLICA_ID"]) % 2 == 0:
        selections.reverse()
    complete = []
    for index, selection in selections:
        target = output / f"timing-{index:02d}"
        command = commands.jmh_command(cp, selection, selection["route"], "0,1", "1s", protocol, target)
        environment = {key: value for key, value in os.environ.items()
                       if key not in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}}
        commands.execute(command, target, timeout=commands.timing_timeout(protocol, "1s"), environment=environment)
        validate_timing(target / "jmh.json", selection, protocol)
        for name in ("vmstat", "meminfo"):
            (target / ("proc-" + name + "-after.txt")).write_text(Path("/proc", name).read_text())
        complete.append(index)
        save(output / "progress.json", {"completed": complete, "total": len(selections)})
        checkpoint(output)
        print(f"Replacement: {len(complete)}/{len(selections)} primary selections complete", flush=True)
    # Allocation has its own fresh forks and is never included in timing scores.
    allocation_protocol = {**protocol, "forks": 1, "warmup_iterations": 3,
                           "measurement_iterations": 3, "allocation_only": True}
    measure_allocations(output, selections, cp, allocation_protocol, environment)
    native_control(output, "after", plan)
    hashes = {str(path.relative_to(output)): digest(path) for path in output.rglob("*") if path.is_file()}
    save(output / "artifacts.json", hashes)
    save(output / "complete.json", {"kind": "replacement-measurement", "plan_sha256": digest(output / "plan.json"),
                                    "artifacts_sha256": digest(output / "artifacts.json"), "primary_selections": len(complete)})
    validate_completed(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shard", required=True)
    parser.add_argument("--partition", type=int, required=True)
    parser.add_argument("--protocol", type=Path)
    parser.add_argument("--session", type=Path)
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--print-allocation", choices=("r8i", "r8g", "r9g"))
    args = parser.parse_args()
    protocol = args.protocol or BASELINE / ("remeasurement-protocol.json" if args.shard in SHARDS else "remeasurement-retained-protocol.json")
    plan = partition_plan(args.shard, args.partition, json.loads(protocol.read_text()))
    if args.print_allocation:
        allocation = plan["protocol"]["allocations"][args.print_allocation]
        print(allocation["instance_type"], allocation["vcpus"], sep="\t")
    elif args.check_only:
        validate_qualification(plan["protocol"])
    elif args.session is None:
        print(json.dumps(plan, indent=2))
    else:
        run(plan, args.session.resolve())


if __name__ == "__main__":
    main()
