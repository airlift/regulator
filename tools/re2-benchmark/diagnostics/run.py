"""Run isolated measurement diagnostics after a release-bound baseline smoke.

These outputs are separate from acceptance receipts and cannot replace formal
results. Every selected case runs every variant, including failed attempts.
"""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import tarfile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools/re2-benchmark/language"))
import released_artifact


def validate_plan(plan):
    if plan.get("schema_version") != 1:
        raise ValueError("unsupported diagnostic schema")
    for key, maximum in (("forks", 20), ("warmup_iterations", 300), ("measurement_iterations", 300)):
        if type(plan.get(key)) is not int or not 1 <= plan[key] <= maximum:
            raise ValueError("invalid " + key)
    native_seconds = plan.get("native_measurement_seconds", 1)
    if type(native_seconds) is not int or not 1 <= native_seconds <= 60:
        raise ValueError("invalid native measurement duration")
    if not 1 <= len(plan["cases"]) <= 12:
        raise ValueError("expected 1-12 diagnostic cases")
    if plan.get("classpath", "trino") not in {"trino", "regulator"}:
        raise ValueError("invalid diagnostic classpath")
    durations = plan.get("durations", ["50ms", "1s"])
    if not durations or len(set(durations)) != len(durations) or not set(durations) <= {"50ms", "1s"}:
        raise ValueError("invalid diagnostic durations")
    primary_allowances = plan.get("primary_cpu_allowances", ["0", "0,1"])
    if not primary_allowances or len(set(primary_allowances)) != len(primary_allowances) or not set(primary_allowances) <= {"0", "0,1"}:
        raise ValueError("invalid primary CPU allowances")
    allowances = plan.get("instrumented_cpu_allowances", [plan.get("instrumented_cpus", "0,1")])
    if not allowances or len(set(allowances)) != len(allowances) or not set(allowances) <= {"0", "0,1"}:
        raise ValueError("invalid instrumented CPU allowances")
    ids = set()
    for case in plan["cases"]:
        language_format = case.get("input_format", "bulk")
        if language_format not in {"bulk", "lifecycle"}:
            raise ValueError("invalid diagnostic input format")
        if "input_format" in case and "input_sha256" not in case:
            raise ValueError("input format requires a checksummed input")
        if language_format == "lifecycle":
            prefix = "io.airlift.regulator.BenchmarkLanguageComparison."
            if not case["benchmark"].startswith(prefix) or case["benchmark"][len(prefix):] not in {
                    "compile", "singleUseContains", "singleUseCount", "reusedContains", "reusedCount"}:
                raise ValueError("invalid lifecycle diagnostic operation")
    for case in plan["cases"]:
        if not re.fullmatch(r"[a-z0-9-]+", case["id"]) or case["id"] in ids:
            raise ValueError("invalid or duplicate case id")
        ids.add(case["id"])
        if not re.fullmatch(r"(?:io\.)[A-Za-z0-9_.]+", case["benchmark"]):
            raise ValueError("expected exact benchmark name")
        if not case["routes"] or not set(case["routes"]) <= {"native-access", "object-row"}:
            raise ValueError("invalid routes")
        if len(set(case["routes"])) != len(case["routes"]):
            raise ValueError("duplicate route")
        if "active_processors" in case and (type(case["active_processors"]) is not int or case["active_processors"] not in (1, 2)):
            raise ValueError("invalid active processor count")
        if case.get("gc", "default") not in {"default", "g1", "serial"}:
            raise ValueError("invalid collector selection")
        if "input_sha256" in case and not re.fullmatch(r"[0-9a-f]{64}", case["input_sha256"]):
            raise ValueError("invalid input checksum")
        for name, value in case["parameters"].items():
            if not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", name) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
                raise ValueError("parameters must select one value")
    if not isinstance(plan.get("native_filter"), str) or not plan["native_filter"].startswith("^"):
        raise ValueError("expected anchored native control filter")
    if "native_names" in plan:
        names = plan["native_names"]
        if (not isinstance(names, list) or not names or len(set(names)) != len(names) or
                any(not isinstance(name, str) or re.fullmatch(plan["native_filter"], name) is None for name in names)):
            raise ValueError("native controls must name unique selected benchmarks")
    if plan.get("instrumented_cpus", "0,1") not in {"0", "0,1"}:
        raise ValueError("invalid instrumented CPU allowance")
    if "instrumentation_protocol" in plan:
        protocol = plan["instrumentation_protocol"]
        if set(protocol) != {"forks", "warmup_iterations", "measurement_iterations"}:
            raise ValueError("invalid instrumentation protocol")
        for key, maximum in (("forks", 10), ("warmup_iterations", 300), ("measurement_iterations", 300)):
            if type(protocol[key]) is not int or not 1 <= protocol[key] <= maximum:
                raise ValueError("invalid instrumentation " + key)
    if not set(plan.get("instrumented_cases", ["capture-split", "easy2-dfa"])) <= ids:
        raise ValueError("unknown instrumented case")
    return plan


def validate_shard(plan, shard):
    expected = "traditional-extra-easy2" if plan.get("classpath", "trino") == "regulator" else "trino-final-line"
    if shard != expected:
        raise ValueError("diagnostic plan does not match smoke shard")


def input_name(case):
    return case["input_sha256"] + (".tsv" if case.get("input_format") == "lifecycle" else ".klv")


def language_verification_commands(case, java, classpath, native, workload):
    if case.get("input_format") == "lifecycle":
        operation = case["benchmark"].rsplit(".", 1)[1]
        return ([*java, "-cp", classpath, "io.airlift.regulator.BenchmarkLanguageComparison",
                 "--verify-operation", case["parameters"]["engine"], workload, operation],
                [native, "--verify-operation", workload, operation])
    return ([*java, "-cp", classpath, "io.airlift.regulator.BenchmarkLanguageBulk",
             "--trace", case["parameters"]["engine"], workload], [native, "--trace", workload])


def unpack_inputs(plan, archive, destination):
    expected = {input_name(case) for case in plan["cases"] if "input_sha256" in case}
    destination.mkdir(exist_ok=False)
    seen = set()
    with tarfile.open(archive, "r:gz") as files:
        for member in files:
            if not member.isfile() or member.name not in expected or member.name in seen:
                raise ValueError("unexpected diagnostic input entry")
            payload = files.extractfile(member).read()
            if hashlib.sha256(payload).hexdigest() != member.name.rsplit(".", 1)[0]:
                raise ValueError("diagnostic input checksum mismatch")
            (destination / member.name).write_bytes(payload)
            seen.add(member.name)
    if seen != expected:
        raise ValueError("missing diagnostic inputs")
    for case in plan["cases"]:
        if "input_sha256" in case:
            case["parameters"]["workloadFile"] = str(destination / input_name(case))


def jmh_command(classpath, case, route, cpus, duration, plan, output, diagnostic=False):
    flags = ["--illegal-native-access=deny", "--add-modules=jdk.incubator.vector",
             "-Xms8g", "-Xmx8g", "-XX:+AlwaysPreTouch"]
    if route == "native-access":
        flags.insert(0, "--enable-native-access=ALL-UNNAMED")
    if case.get("gc") in {"g1", "serial"}:
        flags.append({"g1": "-XX:+UseG1GC", "serial": "-XX:+UseSerialGC"}[case["gc"]])
    if "active_processors" in case:
        flags.append("-XX:ActiveProcessorCount=" + str(case["active_processors"]))
    if diagnostic:
        flags += ["-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation",
                  "-XX:LogFile=" + str(output / "hotspot-%p.xml"),
                  "-Xlog:gc*,safepoint:file=" + str(output / "gc-%p.log") + ":uptime,level,tags"]
    command = ["taskset", "--cpu-list", cpus, "java", "-Xms64m", "-Xmx256m",
               "--add-modules=jdk.incubator.vector", "-cp", classpath,
               "org.openjdk.jmh.Main", "^" + re.escape(case["benchmark"]) + "$",
               "-f", str(plan["forks"]), "-wi", str(plan["warmup_iterations"]),
               "-i", str(plan["measurement_iterations"]), "-w", duration, "-r", duration,
               "-t", "1", "-bm", "avgt", "-tu", "ns", "-foe", "true",
               "-rf", "json", "-rff", str(output / "jmh.json"), "-jvmArgs", " ".join(flags)]
    for name, value in sorted(case["parameters"].items()):
        command += ["-p", name + "=" + value]
    if diagnostic:
        command += ["-prof", "gc"]
    return command


def timing_timeout(plan, duration):
    seconds = 0.05 if duration == "50ms" else 1
    return max(900, int(120 + plan["forks"] *
                        ((plan["warmup_iterations"] + plan["measurement_iterations"]) * seconds + 15)))


def execute(command, output, timeout=900, environment=None, separate_stderr=False):
    output.mkdir(parents=True, exist_ok=False)
    (output / "command.json").write_text(json.dumps(command, indent=2) + "\n")
    record = {"start_unix": time.time(), "command": command}
    if sys.platform == "linux":
        for name in ("stat", "meminfo", "vmstat"):
            (output / ("proc-" + name + "-before.txt")).write_text(Path("/proc", name).read_text())
        command = ["/usr/bin/time", "--verbose", "--output=" + str(output / "resources.txt"), *command]
    try:
        with (output / "console.log").open("w") as log, (output / "stderr.log").open("w") as errors:
            process = subprocess.Popen(command, stdout=log, stderr=errors if separate_stderr else subprocess.STDOUT,
                                       start_new_session=True, env=environment)
            try:
                record["returncode"] = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                record["returncode"] = 124
    except OSError as error:
        record["returncode"] = 127
        record["error"] = str(error)
    record["end_unix"] = time.time()
    (output / "execution.json").write_text(json.dumps(record, indent=2) + "\n")
    if record["returncode"]:
        raise RuntimeError(f"diagnostic command failed: {output}")


def validate_result(path, case, plan):
    rows = json.loads(path.read_text())
    if len(rows) != 1 or rows[0]["benchmark"] != case["benchmark"] or rows[0].get("params", {}) != case["parameters"]:
        raise ValueError("diagnostic selection differs from plan")
    row = rows[0]
    raw = row["primaryMetric"]["rawData"]
    if len(raw) != plan["forks"] or any(len(fork) != plan["measurement_iterations"] for fork in raw):
        raise ValueError("missing diagnostic forks or iterations")
    if any(not isinstance(value, (int, float)) or not 0 < value < float("inf") for fork in raw for value in fork):
        raise ValueError("nonfinite diagnostic sample")


def validate_native_controls(rows, names):
    samples = [row for row in rows if row.get("run_type") == "iteration"]
    if {row["run_name"] for row in samples} != set(names):
        raise ValueError("native control selection changed")
    for name in names:
        selected = [row for row in samples if row["run_name"] == name]
        if len(selected) != 5 or any(row.get("error_occurred") or row["time_unit"] != "ns" or
                                     not math.isfinite(row["cpu_time"]) or row["cpu_time"] <= 0 for row in selected):
            raise ValueError("expected five valid repetitions of every native control")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--session", type=Path)
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--shard")
    args = parser.parse_args()
    plan = validate_plan(json.loads(args.plan.read_text()))
    validate_shard(plan, args.shard or os.environ["RE2_CAMPAIGN_SHARD_ID"])
    if args.check_only:
        return
    if args.session is None:
        parser.error("--session is required for execution")
    session = args.session.resolve()
    output = session / "diagnostics"
    output.mkdir(exist_ok=False)
    (output / "plan.json").write_bytes(args.plan.read_bytes())
    (output / "scope.txt").write_text("diagnostic-only; not formal replacement measurements\n")
    if any("input_sha256" in case for case in plan["cases"]):
        archive = Path(os.environ["BENCHMARK_DIAGNOSTIC_INPUT_ARCHIVE"])
        if hashlib.sha256(archive.read_bytes()).hexdigest() != os.environ["BENCHMARK_DIAGNOSTIC_INPUT_SHA256"]:
            raise ValueError("diagnostic input archive checksum mismatch")
        unpack_inputs(plan, archive, output / "inputs")
    (output / "resolved-plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    # Use the already verified special classes, replacing source production
    # classes in the comparator classpath with the checksum-pinned release.
    shared = Path(os.environ["BASELINE_SHARED_WORK_DIR"])
    jar, identity = released_artifact.prepare(ROOT, os.environ["REGULATOR_RELEASE_VERSION"], output / "release")
    if plan.get("classpath", "trino") == "regulator":
        receipt = json.loads((session / "routes/native-access/release-artifact.json").read_text())
        entries = [str(jar) if entry["path"] == receipt["jar_path"] else entry["path"]
                   for entry in receipt["classpath"]]
    else:
        pinned = (shared / "pinned-trino/classpath.txt").read_text().strip().split(":")
        special = session / "routes/native-access/generated/trino-final-line/classes"
        entries = [str(special)] + [str(jar) if Path(entry).resolve() == ROOT / "target/classes" else entry for entry in pinned]
    classpath = ":".join(dict.fromkeys(entries))
    released_artifact.attest(classpath, jar, identity, output / "release")
    for cpus in ("0", "0,1"):
        execute(["taskset", "--cpu-list", cpus, "java", "-Xms8g", "-Xmx8g",
                 "-XX:+PrintFlagsFinal", "-Xlog:gc+init=info", "-version"],
                output / ("jvm-ergonomics-cpu" + cpus.replace(",", "_")))
    formats = {case.get("input_format", "bulk") for case in plan["cases"] if "input_sha256" in case}
    targets = ["regexp_benchmark"] + ["language_benchmark" if name == "lifecycle" else "language_bulk_benchmark"
                                      for name in sorted(formats)]
    execute([str(ROOT / "tools/re2-benchmark/build.sh")], output / "native-build", timeout=900,
            environment={**os.environ, "RE2_NATIVE_TUNING": "host", "RE2_BENCHMARK_TARGETS": " ".join(targets)})
    native = str(ROOT / "target/re2-benchmark-build/regexp_benchmark")
    language_inputs = {}
    for case in plan["cases"]:
        if "input_sha256" not in case:
            continue
        language_format = case.get("input_format", "bulk")
        workload = case["parameters"]["workloadFile"]
        operation = case["benchmark"].rsplit(".", 1)[1] if language_format == "lifecycle" else "execute"
        binary = str(ROOT / "target/re2-benchmark-build" /
                     ("language_benchmark" if language_format == "lifecycle" else "language_bulk_benchmark"))
        language_inputs[language_format, case["input_sha256"], operation] = (binary, workload)
        for route in case["routes"]:
            directory = output / (case["id"] + "-" + route + "-verification")
            flags = ["java", "--add-modules=jdk.incubator.vector", "--illegal-native-access=deny", "-Xms64m", "-Xmx256m"]
            if route == "native-access":
                flags += ["--enable-native-access=ALL-UNNAMED"]
            java_command, native_command = language_verification_commands(case, flags, classpath, binary, workload)
            execute(java_command, directory, separate_stderr=True)
            suffix = "-" + operation if language_format == "lifecycle" else ""
            reference = output / (case["input_sha256"] + suffix + "-native-verification")
            if not reference.exists():
                execute(native_command, reference, separate_stderr=True)
            if (directory / "console.log").read_bytes() != (reference / "console.log").read_bytes():
                raise ValueError("language trace differs from native reference")
        (output / ("native-" + language_format + "-build.json")).write_bytes(Path(binary + ".build.json").read_bytes())

    def bulk_controls(phase):
        for (language_format, checksum, operation), (binary, workload) in language_inputs.items():
            suffix = "-" + operation if language_format == "lifecycle" else ""
            directory = output / ("native-" + language_format + "-" + phase + "-" + checksum + suffix)
            execute(["taskset", "--cpu-list", "0", binary, workload, operation,
                     "--benchmark_repetitions=5", "--benchmark_min_time=1s", "--benchmark_min_warmup_time=10",
                     "--benchmark_time_unit=ns", "--benchmark_out_format=json",
                     "--benchmark_out=" + str(directory / "native.json")], directory)
            rows = json.loads((directory / "native.json").read_text())["benchmarks"]
            if len([row for row in rows if row.get("run_type") == "iteration"]) != 5:
                raise ValueError("expected five native language repetitions")

    def control(label):
        directory = output / label
        execute(["taskset", "--cpu-list", "0", native,
                 "--benchmark_filter=" + plan["native_filter"],
                 "--benchmark_min_time=" + str(plan.get("native_measurement_seconds", 1)) + "s",
                 "--benchmark_repetitions=5", "--benchmark_out_format=json",
                 "--benchmark_out=" + str(directory / "native.json")], directory)
        rows = json.loads((directory / "native.json").read_text())["benchmarks"]
        validate_native_controls(rows, plan.get("native_names", [
            "Search_Easy0_CachedDFA/262144/threads:1", "Search_Easy2_CachedDFA/262144/threads:1"]))

    control("native-before")
    bulk_controls("before")
    jobs = [(case, route, cpus, duration) for case in plan["cases"] for route in case["routes"]
            for cpus in plan.get("primary_cpu_allowances", ["0", "0,1"]) for duration in plan.get("durations", ["50ms", "1s"])]
    # Reverse the entire deterministic order on even hosts to expose drift.
    if int(os.environ["RE2_CAMPAIGN_REPLICA_ID"]) % 2 == 0:
        jobs.reverse()
    completed = []
    for case, route, cpus, duration in jobs:
        name = f"{case['id']}-{route}-cpu{cpus.replace(',', '_')}-{duration}"
        directory = output / name
        execute(jmh_command(classpath, case, route, cpus, duration, plan, directory), directory,
                timeout=timing_timeout(plan, duration))
        validate_result(directory / "jmh.json", case, plan)
        completed.append(name)
        print(f"Diagnostics: {len(completed)}/{len(jobs)} primary commands complete: {name}", flush=True)
        (output / "progress.json").write_text(json.dumps({"total": len(jobs), "completed": completed}) + "\n")
    # Instrumentation is separate from all primary timing variants.
    instrumented_count = 0
    instrumented_plan = {**plan, **plan.get("instrumentation_protocol", {})}
    for case in plan["cases"]:
        if case["id"] not in plan.get("instrumented_cases", ["capture-split", "easy2-dfa"]):
            continue
        route = case["routes"][0]
        allowances = plan.get("instrumented_cpu_allowances", [plan.get("instrumented_cpus", "0,1")])
        for cpus in allowances:
            suffix = "-cpu" + cpus.replace(",", "_") if len(allowances) > 1 else ""
            directory = output / (case["id"] + "-instrumented" + suffix)
            execute(jmh_command(classpath, case, route, cpus, "1s", instrumented_plan, directory, True), directory,
                    timeout=timing_timeout(instrumented_plan, "1s"))
            validate_result(directory / "jmh.json", case, instrumented_plan)
            instrumented_count += 1
    bulk_controls("after")
    control("native-after")
    hashes = {str(path.relative_to(output)): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in sorted(output.rglob("*")) if path.is_file()}
    (output / "artifacts.json").write_text(json.dumps(hashes, indent=2) + "\n")
    (output / "complete.json").write_text(json.dumps({"primary_jobs": len(jobs), "instrumented_jobs": instrumented_count,
                                                   "release": identity, "completed_unix": time.time()}) + "\n")


if __name__ == "__main__":
    main()
