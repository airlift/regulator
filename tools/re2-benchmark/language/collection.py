#!/usr/bin/env python3
"""Collect language/lifecycle evidence without coupling it to report layout.

This is an additional suite, not a replacement for the existing baseline shards.
Verification is deliberately usable without running a local timing experiment.
"""

import argparse
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import signal
import subprocess
import time
import urllib.request

import jvm_build


ROOT = Path(__file__).resolve().parents[3]
CORPUS = ROOT / "src/test/resources/io/airlift/regulator/everyday-trino-workloads.tsv"
ENGINES = {"re2": ("re2", "native-re2"), "java": ("java", "jdk"), "trino": ("trino", "joni")}
OPERATIONS = ("compile", "singleUseContains", "singleUseCount", "reusedContains", "reusedCount")
MODES = ("native", "safe")
PROTOCOL = {
    "forks": 5, "warmup_iterations": 10, "measurement_iterations": 10,
    "iteration_seconds": 1, "heap": "8g", "startup_timeout_seconds": 60,
    "compile_timeout_seconds": 30, "execution_timeout_seconds": 30,
    # Whole-process budget includes all forks, setup, and slow verified corpus passes.
    "measurement_timeout_seconds": 3600, "timeout_confirmation_attempts": 2,
    "native_repetitions": 5, "native_min_time_seconds": 1,
    "native_warmup_seconds": 10,
}
CLASS = "io.airlift.regulator.BenchmarkLanguageComparison"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, allow_nan=False).encode()


def save(path, value):
    path.write_bytes(encode(value) + b"\n")


def load(path):
    return json.loads(path.read_text(), parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def prepare(destination):
    """Keep input rotation, source identities, and per-language mappings in the frozen manifest."""
    destination.mkdir(parents=True, exist_ok=False)
    (destination / "inputs").mkdir()
    cases = {}
    source_ids = set()
    with CORPUS.open() as file:
        for row in csv.DictReader(file, delimiter="\t", quoting=csv.QUOTE_NONE):
            identity = (row["workload_id"], row["source_id"])
            if identity in source_ids:
                raise ValueError(f"duplicate source: {identity}")
            source_ids.add(identity)
            case = cases.setdefault(row["workload_id"], {
                "id": "everyday/" + row["workload_id"], "population": "ordinary-scalar",
                "family": row["family"], "origin": row["origin"],
                "source_pattern": row["pattern"], "sources": [],
            })
            if case["source_pattern"] != row["pattern"]:
                raise ValueError("inconsistent pattern in corpus")
            data = row["source"].encode("utf-8")
            case["sources"].append({
                "id": row["source_id"], "hex": data.hex(), "sha256": digest(data),
                "input_bytes": len(data), "slice_offset": int(row["slice_offset"]),
                "expected_count": int(row["expected_match_count"]),
            })
    for short_id, case in cases.items():
        pattern = case["source_pattern"].encode("utf-8")
        case["pattern_sha256"] = digest(pattern)
        case["pattern_bytes"] = len(pattern)
        case["rotation"] = "round-robin, one input per invocation"
        case["input_bytes_per_operation"] = sum(source["input_bytes"] for source in case["sources"]) / len(case["sources"])
        case["matches_per_count_operation"] = sum(source["expected_count"] for source in case["sources"]) / len(case["sources"])
        payload = pattern.hex() + "\n" + "".join(
            f"{source['slice_offset']}\t{source['expected_count']}\t{source['hex']}\n" for source in case["sources"])
        path = Path("inputs") / f"{short_id}.tsv"
        (destination / path).write_text(payload)
        case["mappings"] = {language: {
            "status": "identical", "pattern": case["source_pattern"],
            "input_file": str(path), "input_file_sha256": digest(payload.encode()),
            "verifier": "ordered-group-zero-bytes-and-count-v1",
            "reason": "Shared ordinary corpus; verification required before timing",
        } for language in ENGINES}
    manifest = {
        "schema_version": 2, "suite": "language-lifecycle", "protocol": PROTOCOL,
        "corpus_sha256": digest(CORPUS.read_bytes()), "languages": ENGINES,
        "memory_modes": MODES, "operations": OPERATIONS, "cases": list(cases.values()),
        "scope": "Additional shared ordinary/lifecycle suite; baseline shards remain required",
        "representation": {"regulator": "precomputed UTF-8 Slice", "jdk": "precomputed String",
                           "joni": "precomputed UTF-8 bytes", "native-re2": "identical UTF-8 bytes"},
        "matcher_policy": {"re2": "public count; public find for contains",
                           "java": "public count; public find for contains",
                           "trino": "public contains/count", "jdk": "reuse Matcher.reset(input)",
                           "joni": "new Matcher for each input, as in the Trino byte-array route",
                           "native-re2": "reuse RE2; Match with zero or one submatch"},
    }
    validate_manifest(manifest, destination)
    save(destination / "manifest.json", manifest)
    return manifest


def validate_manifest(manifest, directory):
    if manifest.get("suite") == "language-bulk":
        import bulk
        return bulk.validate_manifest(manifest, directory)
    if manifest["schema_version"] != 2 or manifest["protocol"] != PROTOCOL:
        raise ValueError("unrecognized schema or unfrozen protocol")
    if encode(manifest["languages"]) != encode(ENGINES) or encode(manifest["memory_modes"]) != encode(MODES):
        raise ValueError("incomplete language or memory-mode coverage")
    if manifest["operations"] != list(OPERATIONS) and manifest["operations"] != OPERATIONS:
        raise ValueError("unexpected operation coverage")
    selected = manifest.get("selected_languages", list(ENGINES))
    if not selected or len(selected) != len(set(selected)) or not set(selected) <= set(ENGINES):
        raise ValueError("invalid selected languages")
    if "selected_languages" in manifest and not manifest.get("parent_manifest_sha256"):
        raise ValueError("partition must identify its parent manifest")
    ids = [case["id"] for case in manifest["cases"]]
    if not ids or len(ids) != len(set(ids)):
        raise ValueError("missing or duplicate logical IDs")
    for case in manifest["cases"]:
        if set(case["mappings"]) != set(ENGINES):
            raise ValueError(f"missing language mapping: {case['id']}")
        if not case["sources"]:
            raise ValueError("empty source rotation")
        if len({source["id"] for source in case["sources"]}) != len(case["sources"]):
            raise ValueError("duplicate source IDs")
        pattern = case["source_pattern"].encode("utf-8")
        if case["pattern_sha256"] != digest(pattern) or case["pattern_bytes"] != len(pattern):
            raise ValueError("source pattern identity mismatch")
        if case["input_bytes_per_operation"] != sum(source["input_bytes"] for source in case["sources"]) / len(case["sources"]):
            raise ValueError("incorrect input normalization denominator")
        if case["matches_per_count_operation"] != sum(source["expected_count"] for source in case["sources"]) / len(case["sources"]):
            raise ValueError("incorrect match normalization denominator")
        for source in case["sources"]:
            if any(type(source[key]) is not int or source[key] < 0 for key in ("slice_offset", "expected_count", "input_bytes")):
                raise ValueError("invalid source offset, count, or size")
        for language, mapping in case["mappings"].items():
            if mapping["status"] not in {"identical", "translated", "not-compatible"}:
                raise ValueError("unclassified language mapping")
            if not mapping["reason"]:
                raise ValueError("mapping must explain compatibility or translation")
            if mapping["status"] == "not-compatible":
                continue
            if mapping["verifier"] != "ordered-group-zero-bytes-and-count-v1":
                raise ValueError("unsupported semantic verifier")
            path = (directory / mapping["input_file"]).resolve()
            if not path.is_relative_to(directory.resolve()):
                raise ValueError("input file escapes manifest directory")
            payload = path.read_bytes()
            if digest(payload) != mapping["input_file_sha256"]:
                raise ValueError("input file checksum mismatch")
            expected = mapping["pattern"].encode().hex() + "\n" + "".join(
                f"{source['slice_offset']}\t{source['expected_count']}\t{source['hex']}\n"
                for source in case["sources"])
            if payload != expected.encode():
                raise ValueError("input file does not match recorded patterns/sources")
            for source in case["sources"]:
                data = bytes.fromhex(source["hex"])
                data.decode("utf-8", errors="strict")
                if digest(data) != source["sha256"] or len(data) != source["input_bytes"]:
                    raise ValueError("input identity mismatch")
            if mapping["status"] == "identical" and mapping["pattern"] != case["source_pattern"]:
                raise ValueError(f"unrecorded translation: {case['id']}/{language}")


def run_process(command, prefix, *, measuring=False):
    """Kill the whole process group, including JMH forks, on a frozen deadline."""
    started = time.monotonic()
    phase = "measurement" if measuring else "startup"
    phase_started = started
    prefix.parent.mkdir(parents=True, exist_ok=True)
    environment = {key: value for key, value in os.environ.items()
                   if key not in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}}
    with prefix.with_suffix(".stdout").open("wb") as stdout, prefix.with_suffix(".stderr").open("wb") as stderr:
        process = subprocess.Popen(command, stdout=stdout, stderr=stderr, env=environment, start_new_session=True)
        try:
            while process.poll() is None:
                if not measuring:
                    lines = prefix.with_suffix(".stderr").read_text(errors="replace").splitlines()
                    markers = [line.removeprefix("phase=") for line in lines if line in {"phase=compile", "phase=execution"}]
                    if markers and markers[-1] != phase:
                        phase = markers[-1]
                        phase_started = time.monotonic()
                limit = PROTOCOL[f"{phase}_timeout_seconds"]
                if time.monotonic() - phase_started > limit:
                    terminate_process_group(process)
                    return {"outcome": "did-not-finish", "phase": phase, "limit_seconds": limit,
                            "command": command}
                time.sleep(0.05)
        finally:
            if process.poll() is None:
                terminate_process_group(process)
    if process.returncode != 0:
        raise ValueError(f"runner failed ({process.returncode}); inspect {prefix.with_suffix('.stderr')}")
    return {"outcome": "completed", "command": command}


def terminate_process_group(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        # The process can exit between the deadline check and group cancellation.
        pass
    process.wait()


def java_command(java, classpath, mode):
    command = [java, "--add-modules=jdk.incubator.vector", "-Xms8g", "-Xmx8g", "-XX:+UseG1GC"]
    if mode == "native":
        command.append("--enable-native-access=ALL-UNNAMED")
    return command + ["-cp", classpath]


def measured_jvm_arguments(mode):
    arguments = ["--add-modules=jdk.incubator.vector", "-Xms8g", "-Xmx8g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch"]
    if mode == "native":
        arguments.append("--enable-native-access=ALL-UNNAMED")
    return arguments


def jmh_command(java, classpath, mode):
    return [java, "--add-modules=jdk.incubator.vector", "-Xms64m", "-Xmx256m", "-cp", classpath,
            "org.openjdk.jmh.Main", "-jvmArgs", " ".join(measured_jvm_arguments(mode))]


def runner_identity(java, classpath, native):
    return {**jvm_build.jvm_identity(java, classpath), "native_binary_sha256": digest(Path(native).read_bytes())}


def host_identity(platform_id):
    """Read only the EC2 identity document, never instance-role credentials."""
    endpoint = "http://169.254.169.254/latest/"
    request = urllib.request.Request(endpoint + "api/token", method="PUT",
                                     headers={"X-aws-ec2-metadata-token-ttl-seconds": "60"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=5) as response:
        token = response.read().decode()
    request = urllib.request.Request(endpoint + "dynamic/instance-identity/document",
                                     headers={"X-aws-ec2-metadata-token": token})
    with opener.open(request, timeout=5) as response:
        identity = json.load(response)
    if not identity["instanceType"].startswith(platform_id + "."):
        raise ValueError("EC2 instance does not match requested platform")
    expected_architecture = "x86_64" if platform_id == "r8i" else "aarch64"
    if platform.machine() != expected_architecture:
        raise ValueError("CPU architecture does not match requested platform")
    return identity


def observations(manifest):
    for case in manifest["cases"]:
        for language, (candidate, comparator) in ENGINES.items():
            if language not in manifest.get("selected_languages", ENGINES):
                continue
            for engine, mode in [(candidate, "native"), (candidate, "safe"), (comparator, "safe")]:
                identity = f"{case['id']}/{language}/{engine}/{mode}"
                yield identity, case, language, engine, mode


def verify_command(command, prefix, engine, mode, *, hash_trace=False):
    attempts = []
    for attempt in range(PROTOCOL["timeout_confirmation_attempts"]):
        attempt_prefix = prefix / f"verification-{attempt}"
        receipt = run_process(command, attempt_prefix)
        attempts.append(receipt.copy())
        if receipt["outcome"] == "completed":
            if attempt != 0:
                raise ValueError(f"nonreproducible timeout: {prefix}")
            output = attempt_prefix.with_suffix(".stdout").read_bytes()
            receipt["trace"] = "sha256:" + digest(output) + "\n" if hash_trace else output.decode()
            receipt["trace_sha256"] = digest(receipt["trace"].encode())
            if engine in {"re2", "java", "trino"}:
                marker = f"native-access={str(mode == 'native').lower()}"
                if marker not in attempt_prefix.with_suffix(".stderr").read_text().splitlines():
                    raise ValueError(f"wrong native access mode: {prefix}")
            break
        if receipt["phase"] == "startup":
            raise ValueError(f"runner startup timed out; not regex evidence: {prefix}")
    if len(attempts) == 2 and attempts[0] != attempts[1]:
        raise ValueError(f"timeout phase changed: {prefix}")
    return {**receipt, "attempts": attempts}


def verify(directory, results, java, classpath, native):
    directory, results = directory.resolve(), results.resolve()
    manifest = load(directory / "manifest.json")
    if manifest.get("suite") == "language-bulk":
        import bulk
        return bulk.verify(directory, results, java, classpath, native)
    validate_manifest(manifest, directory)
    results.mkdir(parents=True, exist_ok=False)
    runners = runner_identity(java, classpath, native)
    save(results / "runners.json", runners)
    receipts = {}
    for identity, case, language, engine, mode in observations(manifest):
        mapping = case["mappings"][language]
        if mapping["status"] == "not-compatible":
            excluded = {"outcome": "not-compatible", "reason": mapping["reason"]}
            receipts[identity] = {**excluded, "operations": {operation: excluded.copy() for operation in OPERATIONS}}
            continue
        workload = str((directory / mapping["input_file"]).resolve())
        command = ([native, "--verify", workload] if engine == "native-re2" else
                   java_command(java, classpath, mode) + [CLASS, "--verify", engine, workload])
        prefix = results / identity
        receipt = verify_command(command, prefix, engine, mode)
        receipt["operations"] = {}
        for operation in OPERATIONS:
            operation_command = ([native, "--verify-operation", workload, operation] if engine == "native-re2" else
                                 java_command(java, classpath, mode) + [CLASS, "--verify-operation", engine, workload, operation])
            receipt["operations"][operation] = verify_command(operation_command, prefix / operation, engine, mode)
        receipts[identity] = receipt
        save(results / "verification.partial.json", receipts)
    validate_receipts(manifest, receipts)
    if runners != runner_identity(java, classpath, native):
        raise ValueError("runners changed during verification")
    evidence = {"manifest_sha256": digest((directory / "manifest.json").read_bytes()),
                "runners_sha256": digest(encode(runners)), "receipts": receipts}
    save(results / "verification.json", evidence)
    return evidence


def validate_receipts(manifest, receipts):
    if manifest.get("suite") == "language-bulk":
        import bulk
        return bulk.validate_receipts(manifest, receipts)
    expected = {identity for identity, *_ in observations(manifest)}
    if set(receipts) != expected:
        raise ValueError("missing or unexpected verification receipts")
    # Compare each language's own candidate/comparator, then compare completed language mappings.
    # The shared registry promises equivalent semantics, including recorded translations.
    for case in manifest["cases"]:
        traces = []
        for identity, observed_case, language, engine, mode in observations(manifest):
            if observed_case["id"] != case["id"]:
                continue
            receipt = receipts[identity]
            if receipt["outcome"] == "completed":
                if not receipt.get("trace") or digest(receipt["trace"].encode()) != receipt["trace_sha256"]:
                    raise ValueError("missing or corrupt semantic trace")
                lines = receipt["trace"].splitlines()
                if len(lines) != len(case["sources"]):
                    raise ValueError("semantic trace has wrong input count")
                for line, source in zip(lines, case["sources"]):
                    count_text, separator, matches = line.partition(":")
                    count = int(count_text)
                    if not separator or count != source["expected_count"]:
                        raise ValueError("semantic trace disagrees with expected count")
                    values = matches.split(",") if count else []
                    if len(values) != count or (not count and matches):
                        raise ValueError("semantic trace has wrong match count")
                    for value in values:
                        bytes.fromhex(value).decode("utf-8", errors="strict")
                traces.append(receipt["trace"])
            elif receipt["outcome"] == "did-not-finish":
                attempts = receipt["attempts"]
                if len(attempts) != 2 or any(attempt["outcome"] != "did-not-finish" for attempt in attempts):
                    raise ValueError("timeout was not reproduced")
                if receipt["phase"] not in {"compile", "execution"}:
                    raise ValueError("invalid verification timeout phase")
                if any(attempt["phase"] != receipt["phase"] or
                       attempt["limit_seconds"] != PROTOCOL[f"{receipt['phase']}_timeout_seconds"] for attempt in attempts):
                    raise ValueError("timeout evidence contradicts frozen limit")
            elif receipt["outcome"] != "not-compatible":
                raise ValueError("verification failure is not publishable")
            if (receipt["outcome"] == "not-compatible") != (case["mappings"][language]["status"] == "not-compatible"):
                raise ValueError("receipt contradicts compatibility mapping")
            if set(receipt.get("operations", {})) != set(OPERATIONS):
                raise ValueError("missing operation verification receipts")
            for operation, operation_receipt in receipt["operations"].items():
                validate_operation_receipt(case, language, operation, operation_receipt)
        if len(set(traces)) > 1:
            raise ValueError(f"verification-failed: engines disagree for {case['id']}")


def expected_operation_trace(case, operation):
    if operation == "compile":
        return "compiled\n"
    return "".join(str(int(source["expected_count"] != 0) if operation.endswith("Contains") else source["expected_count"]) + "\n"
                   for source in case["sources"])


def validate_operation_receipt(case, language, operation, receipt):
    outcome = receipt["outcome"]
    if (outcome == "not-compatible") != (case["mappings"][language]["status"] == "not-compatible"):
        raise ValueError("operation receipt contradicts compatibility mapping")
    if outcome == "not-compatible":
        return
    if outcome == "completed":
        expected = expected_operation_trace(case, operation)
        if receipt.get("trace") != expected or receipt.get("trace_sha256") != digest(expected.encode()):
            raise ValueError(f"operation verification failed: {case['id']}/{operation}")
        return
    if outcome != "did-not-finish":
        raise ValueError("unpublishable operation verification outcome")
    phase = receipt["phase"]
    if phase not in {"compile", "execution"} or (operation == "compile" and phase != "compile"):
        raise ValueError("invalid operation timeout phase")
    attempts = receipt["attempts"]
    if len(attempts) != PROTOCOL["timeout_confirmation_attempts"] or any(
            attempt["outcome"] != outcome or attempt["phase"] != phase or
            attempt["limit_seconds"] != PROTOCOL[f"{phase}_timeout_seconds"] for attempt in attempts):
        raise ValueError("operation timeout was not reproduced at the frozen limit")


def raw_samples(path, engine, operation, workload=None, benchmark_class=CLASS, expected_result=None,
                expected_jvm_arguments=None):
    data = load(path)
    if engine == "native-re2":
        rows = [row for row in data["benchmarks"] if row.get("run_type") == "iteration"]
        if len(rows) != PROTOCOL["native_repetitions"] or any(row.get("error_occurred") for row in rows):
            raise ValueError("incomplete native repetitions")
        if any(row["time_unit"] != "ns" or row["run_name"].split("/")[0] != operation for row in rows):
            raise ValueError("unexpected native measurement")
        samples = [[row["real_time"]] for row in rows]
        allocation = None
    else:
        if len(data) != 1 or data[0]["benchmark"] != benchmark_class + "." + operation:
            raise ValueError("unexpected JMH row")
        row = data[0]
        if expected_jvm_arguments is not None and row.get("jvmArgs") != expected_jvm_arguments:
            raise ValueError("measured JVM arguments differ from the frozen protocol")
        if row["params"]["engine"] != engine or (workload is not None and row["params"]["workloadFile"] != workload):
            raise ValueError("JMH measured a different engine or workload")
        if benchmark_class.endswith("BenchmarkLanguageBulk") and (
                expected_result is None or row["params"].get("expectedResult") != str(expected_result)):
            raise ValueError("JMH bulk expected result differs from verified workload")
        if (row["forks"] != PROTOCOL["forks"] or row["warmupIterations"] != PROTOCOL["warmup_iterations"] or
                row["measurementIterations"] != PROTOCOL["measurement_iterations"] or
                row["warmupTime"] != "1 s" or row["measurementTime"] != "1 s" or
                row["threads"] != 1 or row["mode"] != "avgt"):
            raise ValueError("JMH measurement protocol changed")
        if row["primaryMetric"]["scoreUnit"] != "ns/op":
            raise ValueError("unexpected JMH units")
        samples = row["primaryMetric"]["rawData"]
        if len(samples) != PROTOCOL["forks"] or any(len(fork) != PROTOCOL["measurement_iterations"] for fork in samples):
            raise ValueError("missing per-fork/per-iteration samples")
        allocation = row["secondaryMetrics"]["gc.alloc.rate.norm"]
        if allocation["scoreUnit"] != "B/op" or len(allocation["rawData"]) != PROTOCOL["forks"] or any(
                len(fork) != PROTOCOL["measurement_iterations"] or any(
                    type(value) not in (float, int) or not math.isfinite(value) or value < 0 for value in fork)
                for fork in allocation["rawData"]):
            raise ValueError("missing or invalid allocation samples")
    if any(type(value) not in (int, float) or not math.isfinite(value) or value <= 0
           for fork in samples for value in fork):
        raise ValueError("invalid raw measurement")
    return {"samples_ns": samples, "allocation": allocation, "raw_sha256": digest(path.read_bytes())}


def case_operations(manifest, case):
    if manifest.get("suite") == "language-bulk":
        return ("compile",) if case["model"] == "compile" else ("execute",)
    return OPERATIONS


def benchmark_class(manifest):
    return "io.airlift.regulator.BenchmarkLanguageBulk" if manifest.get("suite") == "language-bulk" else CLASS


def work_contract(manifest, case, language, operation, version=3):
    """Record timed work independently of successful output verification."""
    if manifest.get("suite") == "language-bulk" and operation == "execute":
        model = case["model"]
        if language == "trino" and model in {"count-spans", "count-captures", "grep-captures"}:
            return "trino-output-assisted-count-v1" if version == 2 else "trino-matcher-count-v3"
        if language in {"re2", "java"} and model in {"grep", "count"}:
            return "public-find-count-v2"
        return "bulk-matched-outputs-v1"
    return "public-pattern-lifecycle-v1"


def measure(directory, results, java, classpath, native, platform_id, jvm_receipt, archive=None, candidate_provenance=None):
    directory, results = directory.resolve(), results.resolve()
    if (results / "provenance.json").exists() or (results / "observations.json").exists():
        raise ValueError("will not overwrite an existing measurement cohort or its call contract")
    if platform.system() != "Linux":
        raise ValueError("timing is restricted to qualification hosts; use verify on a developer machine")
    manifest = load(directory / "manifest.json")
    validate_manifest(manifest, directory)
    if manifest.get("suite") == "language-bulk" and any(
            mapping["verifier"] != "bulk-result-boundaries-and-bytes-v2"
            for case in manifest["cases"] for mapping in case["mappings"].values()):
        raise ValueError("new bulk measurements require boundary-and-byte verification")
    evidence = load(results / "verification.json")
    if evidence["manifest_sha256"] != digest((directory / "manifest.json").read_bytes()):
        raise ValueError("manifest changed after verification")
    validate_receipts(manifest, evidence["receipts"])
    if manifest.get("suite") == "language-bulk":
        import bulk
        bulk.validate_evidence(manifest, evidence, results)
    runners = runner_identity(java, classpath, native)
    if digest(encode(runners)) != evidence["runners_sha256"]:
        raise ValueError("runners differ from verified binaries")
    jvm_build.require_clean_source(ROOT, archive, candidate_provenance)
    build_receipt = load(jvm_receipt)
    jvm_build.validate_receipt(build_receipt, ROOT, java, classpath, archive, candidate_provenance)
    version = subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT).decode()
    if "25.0.4" not in version or "25.0.4+7" not in version:
        raise ValueError("JDK differs from baseline/comparators.tsv")
    native_build = load(Path(native).with_suffix(".build.json"))
    if native_build["binary_sha256"] != runners["native_binary_sha256"]:
        raise ValueError("native binary does not match recorded build")
    native_source = "language_bulk_benchmark.cc" if manifest.get("suite") == "language-bulk" else "language_benchmark.cc"
    if native_build["source_sha256"] != digest(Path(__file__).with_name(native_source).read_bytes()):
        raise ValueError("native runner was built from a different source")
    if any(flag not in native_build["runner_compile_commands"][0]["command"] for flag in ("-O3", "-DNDEBUG")):
        raise ValueError("native comparator requires a release build")
    provenance = {
        **jvm_build.source_identity(ROOT, archive, candidate_provenance),
        "jdk": version, "platform": platform_id, "machine": platform.machine(),
        "instance_identity": host_identity(platform_id),
        "lscpu": subprocess.check_output(["lscpu"]).decode(),
        "comparators_sha256": digest((ROOT / "tools/re2-benchmark/baseline/comparators.tsv").read_bytes()),
        "native_binary_sha256": digest(Path(native).read_bytes()),
        "runners_sha256": digest(encode(runners)),
        "native_build": native_build,
        "jvm_build": build_receipt,
        "benchmark_contract_version": 3,
        "measured_jvm_arguments": {mode: measured_jvm_arguments(mode) for mode in MODES},
        "manifest_sha256": evidence["manifest_sha256"],
    }
    save(results / "provenance.json", provenance)
    observations_data = {}
    for identity, case, language, engine, mode in observations(manifest):
        for operation in case_operations(manifest, case):
            verification = evidence["receipts"][identity]["operations"][operation]
            row_id = identity + "/" + operation
            if verification["outcome"] != "completed":
                observations_data[row_id] = verification
                continue
            raw = (results / row_id / "raw.json").resolve()
            if raw.exists():
                raise ValueError(f"will not overwrite existing measurements: {raw}")
            raw.parent.mkdir(parents=True, exist_ok=True)
            workload = str((directory / case["mappings"][language]["input_file"]).resolve())
            if engine == "native-re2":
                command = [native, workload, operation, "--benchmark_repetitions=5", "--benchmark_min_time=1s",
                           "--benchmark_min_warmup_time=10", "--benchmark_time_unit=ns",
                           "--benchmark_out_format=json", f"--benchmark_out={raw}"]
            else:
                command = jmh_command(java, classpath, mode) + ["^" + benchmark_class(manifest) + r"\." + operation + "$",
                           "-p", f"engine={engine}", "-p", f"workloadFile={workload}",
                           "-f", "5", "-wi", "10", "-i", "10", "-w", "1s", "-r", "1s",
                           "-prof", "gc", "-rf", "json", "-rff", str(raw), "-foe", "true"]
                if manifest.get("suite") == "language-bulk":
                    command += ["-p", f"expectedResult={case['expected_result']}"]
            receipt = run_process(command, raw.parent / "measurement", measuring=True)
            if receipt["outcome"] != "completed":
                # Aggregate fork timeout is a collection failure, not proof that one regex call timed out.
                raise ValueError(f"measurement process exceeded protocol budget: {row_id}")
            observations_data[row_id] = {"outcome": "compared", **raw_samples(raw, engine, operation, workload, benchmark_class(manifest), case.get("expected_result"), measured_jvm_arguments(mode)),
                                         "raw_file": str(raw.relative_to(results)), "command": command}
            save(results / "observations.partial.json", observations_data)
    save(results / "observations.json", observations_data)
    export(directory, results)


def export(directory, results, *, write=True):
    directory, results = directory.resolve(), results.resolve()
    provenance = load(results / "provenance.json")
    if provenance.get("jvm_build", {}).get("released_artifact") is not None and provenance.get("measured_jvm_arguments") != {
            mode: measured_jvm_arguments(mode) for mode in MODES}:
        raise ValueError("release measurement lacks the frozen JVM memory protocol")
    manifest = load(directory / "manifest.json")
    validate_manifest(manifest, directory)
    evidence = load(results / "verification.json")
    validate_receipts(manifest, evidence["receipts"])
    if manifest.get("suite") == "language-bulk":
        import bulk
        bulk.validate_evidence(manifest, evidence, results)
    data = load(results / "observations.json")
    expected = {identity + "/" + operation for identity, case, *_ in observations(manifest)
                for operation in case_operations(manifest, case)}
    if set(data) != expected:
        raise ValueError("missing or unexpected observations")
    rows = []
    for case in manifest["cases"]:
        for language, (candidate, comparator) in ENGINES.items():
            if language not in manifest.get("selected_languages", ENGINES):
                continue
            for mode in MODES:
                for operation in case_operations(manifest, case):
                    candidate_id = f"{case['id']}/{language}/{candidate}/{mode}/{operation}"
                    comparator_id = f"{case['id']}/{language}/{comparator}/safe/{operation}"
                    pair = [data[candidate_id], data[comparator_id]]
                    for identity, row, engine in zip((candidate_id, comparator_id), pair, (candidate, comparator)):
                        if row["outcome"] == "compared":
                            verified_workload = evidence["receipts"][identity.rsplit("/", 1)[0]]["command"][-1]
                            measured_mode = mode if engine == candidate else "safe"
                            expected_arguments = provenance.get("measured_jvm_arguments", {}).get(measured_mode)
                            observed = raw_samples(results / row["raw_file"], engine, operation, verified_workload, benchmark_class(manifest), case.get("expected_result"), expected_arguments)
                            if any(row[key] != observed[key] for key in observed):
                                raise ValueError("raw samples changed since reduction")
                        elif row["outcome"] not in {"not-compatible", "did-not-finish"}:
                            raise ValueError("unpublishable observation")
                        operation_receipt = evidence["receipts"][identity.rsplit("/", 1)[0]]["operations"][operation]
                        if operation_receipt["outcome"] != (
                                "completed" if row["outcome"] == "compared" else row["outcome"]):
                            raise ValueError("observation contradicts verification")
                        if row["outcome"] != "compared" and row != operation_receipt:
                            raise ValueError("observation changed operation timeout evidence")
                    rows.append({"id": f"{case['id']}/{language}/{mode}/{operation}",
                                 "logical_case": case["id"], "language": language, "mode": mode,
                                 "operation": operation, "candidate": candidate_id, "comparator": comparator_id,
                                 "input_bytes_per_operation": None if operation == "compile" else case["input_bytes_per_operation"],
                                 "matches_per_count_operation": case["matches_per_count_operation"] if operation.endswith("Count") else None})
    # Never stamp a current call contract onto an archived measurement. Only a
    # collector that recorded this version before timing can emit these claims.
    if "benchmark_contract_version" in provenance:
        if provenance["benchmark_contract_version"] not in {2, 3}:
            raise ValueError("unsupported benchmark contract version")
        cases = {case["id"]: case for case in manifest["cases"]}
        for row in rows:
            row["work_contract"] = work_contract(manifest, cases[row["logical_case"]], row["language"], row["operation"], provenance["benchmark_contract_version"])
    if evidence["manifest_sha256"] != digest((directory / "manifest.json").read_bytes()) or provenance["manifest_sha256"] != evidence["manifest_sha256"]:
        raise ValueError("provenance does not identify the verified manifest")
    runners = load(results / "runners.json")
    if digest(encode(runners)) != evidence["runners_sha256"] or provenance["runners_sha256"] != evidence["runners_sha256"]:
        raise ValueError("runner provenance changed after verification")
    if provenance["jvm_build"]["jvm"] != {key: value for key, value in runners.items() if key != "native_binary_sha256"}:
        raise ValueError("exported JVM artifacts differ from build receipt")
    if provenance["jvm_build"]["source_tree"] != provenance["source_tree"]:
        raise ValueError("exported JVM build identifies a different source tree")
    if "released_artifact" in provenance["jvm_build"]:
        jvm_build.released_artifact.validate_saved(
            provenance["jvm_build"]["released_artifact"], provenance["jvm_build"]["jvm"])
    exported = {"schema_version": 2, "manifest": manifest, "verification": evidence,
                "provenance": provenance, "runners": runners,
                "observations": data, "comparisons": rows}
    if write:
        save(results / "language-results.json", exported)
    return exported


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "verify", "measure", "export"))
    parser.add_argument("--manifest-directory", required=True, type=Path)
    parser.add_argument("--results", type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--classpath")
    parser.add_argument("--native-runner")
    parser.add_argument("--platform", choices=("r9g", "r8g", "r8i"))
    parser.add_argument("--jvm-build-receipt", type=Path)
    parser.add_argument("--source-archive", type=Path)
    parser.add_argument("--candidate-provenance", type=Path)
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.manifest_directory)
    elif args.action == "export":
        if not args.results:
            parser.error("export requires --results")
        export(args.manifest_directory, args.results)
    else:
        if not args.results or not args.classpath or not args.native_runner:
            parser.error("verification/measurement requires --results, --classpath, and --native-runner")
        if args.action == "verify":
            verify(args.manifest_directory, args.results, args.java, args.classpath, args.native_runner)
        else:
            if not args.platform or not args.jvm_build_receipt:
                parser.error("measurement requires --platform and --jvm-build-receipt")
            measure(args.manifest_directory, args.results, args.java, args.classpath, args.native_runner, args.platform,
                    args.jvm_build_receipt, args.source_archive, args.candidate_provenance)


if __name__ == "__main__":
    main()
