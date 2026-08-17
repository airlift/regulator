"""Optional source-bracket controls, separate from language/comparator report rows."""

import math
from pathlib import Path
import platform
import re

import collection


def selected(manifest):
    cases = {case["id"] for case in manifest["cases"]}
    languages = manifest.get("selected_languages", list(collection.ENGINES))
    controls = manifest.get("focused_controls", [])
    identifiers = [control["id"] for control in controls]
    if len(set(identifiers)) != len(identifiers):
        raise ValueError("duplicate focused control")
    for control in controls:
        if not re.fullmatch(r"[a-z0-9-]+", control["id"]):
            raise ValueError("invalid focused control id")
        if not re.fullmatch(r"io\.airlift\.regulator\.Benchmark[A-Za-z0-9]+\.[A-Za-z0-9]+", control["benchmark"]):
            raise ValueError("focused control must name one benchmark method")
        if any(not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", key) or not isinstance(value, str)
               for key, value in control.get("parameters", {}).items()):
            raise ValueError("invalid focused benchmark parameters")
    return [control for control in controls if control["case_id"] in cases and control["language"] in languages]


def validate_sources(manifest, candidate, control):
    selected(manifest)
    for descriptor in manifest.get("focused_controls", []):
        benchmark_class = descriptor["benchmark"].rsplit(".", 1)[0]
        path = Path("src/test/java") / (benchmark_class.replace(".", "/") + ".java")
        if (candidate / path).read_bytes() != (control / path).read_bytes():
            raise ValueError("focused benchmark source differs between candidate and control")


def lifecycle_payload(specification):
    pattern = bytes.fromhex(specification["pattern_hex"])
    pattern.decode("utf-8", errors="strict")
    if not specification["sources"]:
        raise ValueError("empty lifecycle rotation")
    lines = [pattern.hex()]
    for source in specification["sources"]:
        data = bytes.fromhex(source["hex"])
        data.decode("utf-8", errors="strict")
        if any(type(source[key]) is not int or source[key] < 0 for key in ("offset", "count")):
            raise ValueError("invalid lifecycle source")
        lines.append(f"{source['offset']}\t{source['count']}\t{data.hex()}")
    return ("\n".join(lines) + "\n").encode()


def validate_raw(raw, descriptor, parameters):
    rows = collection.load(raw)
    if len(rows) != 1 or rows[0]["benchmark"] != descriptor["benchmark"] or rows[0].get("params", {}) != parameters:
        raise ValueError("focused JMH result does not identify the exact requested operation")
    row = rows[0]
    metric = row["primaryMetric"]
    samples = metric["rawData"]
    if (row["mode"] != "avgt" or metric["scoreUnit"] != "ns/op" or
            row["threads"] != 1 or row["warmupTime"] != "1 s" or row["measurementTime"] != "1 s" or
            row["forks"] != 5 or row["warmupIterations"] != 10 or row["measurementIterations"] != 10 or
            len(samples) != 5 or any(len(fork) != 10 for fork in samples) or
            any(not math.isfinite(value) or value <= 0 for fork in samples for value in fork)):
        raise ValueError("incomplete focused JMH protocol or invalid samples")
    allocation = row["secondaryMetrics"]["gc.alloc.rate.norm"]
    if (allocation["scoreUnit"] != "B/op" or len(allocation["rawData"]) != 5 or any(
            len(fork) != 10 or any(not math.isfinite(value) or value < 0 for value in fork)
            for fork in allocation["rawData"])):
        raise ValueError("invalid focused allocation samples")
    return row


def run(partition, results, java, classpath):
    manifest = collection.load(partition / "manifest.json")
    controls = selected(manifest)
    if controls and platform.system() != "Linux":
        raise ValueError("focused timing requires the qualification worker")
    for descriptor in controls:
        for mode in collection.MODES:
            output = results / "focused-controls" / descriptor["id"] / mode
            output.mkdir(parents=True, exist_ok=False)
            parameters = dict(descriptor.get("parameters", {}))
            verification = None
            if "lifecycle" in descriptor:
                workload = output / "inputs.tsv"
                workload.write_bytes(lifecycle_payload(descriptor["lifecycle"]))
                parameters["workloadFile"] = str(workload.resolve())
                engine = parameters["engine"]
                comparator = "jdk" if engine == "java" else "joni" if engine == "trino" else None
                command = collection.java_command(java, classpath, mode)
                verification = collection.verify_command(command + [collection.CLASS, "--verify", engine, str(workload)],
                                                         output / "verification", engine, mode)
                if verification["outcome"] != "completed":
                    raise ValueError("focused lifecycle did not verify")
                if comparator is not None:
                    expected = collection.verify_command(command + [collection.CLASS, "--verify", comparator, str(workload)],
                                                         output / "comparator-verification", comparator, mode)
                    if expected["outcome"] != "completed" or expected["trace"] != verification["trace"]:
                        raise ValueError("focused lifecycle comparator mismatch")
            raw = output / "raw.json"
            command = collection.java_command(java, classpath, mode) + [
                "org.openjdk.jmh.Main", "^" + re.escape(descriptor["benchmark"]) + "$",
                "-f", "5", "-wi", "10", "-i", "10", "-w", "1s", "-r", "1s",
                "-prof", "gc", "-rf", "json", "-rff", str(raw), "-foe", "true"]
            for key, value in parameters.items():
                command += ["-p", f"{key}={value}"]
            receipt = collection.run_process(command, output / "measurement", measuring=True)
            if receipt["outcome"] != "completed":
                raise ValueError("focused JMH measurement did not finish")
            validate_raw(raw, descriptor, parameters)
            collection.save(output / "receipt.json", {
                "kind": "same-source-contract-control", "descriptor": descriptor,
                "manifest_sha256": collection.digest((partition / "manifest.json").read_bytes()),
                "raw_sha256": collection.digest(raw.read_bytes()), "command": command,
                "mode": mode, "verification": verification})


def validate_results(partition, results, runners):
    manifest = collection.load(partition / "manifest.json")
    expected = {results / "focused-controls" / descriptor["id"] / mode / "receipt.json": (descriptor, mode)
                for descriptor in selected(manifest) for mode in collection.MODES}
    if set((results / "focused-controls").rglob("receipt.json")) != set(expected):
        raise ValueError("focused control coverage is incomplete or unexpected")
    hashes = {}
    for path, (descriptor, mode) in expected.items():
        receipt = collection.load(path)
        if (receipt["descriptor"] != descriptor or receipt["mode"] != mode or
                receipt["manifest_sha256"] != collection.digest((partition / "manifest.json").read_bytes())):
            raise ValueError("focused receipt differs from its frozen specification")
        raw = path.parent / "raw.json"
        if collection.digest(raw.read_bytes()) != receipt["raw_sha256"]:
            raise ValueError("focused raw timing changed")
        row = collection.load(raw)[0]
        parameters = dict(descriptor.get("parameters", {}))
        if "lifecycle" in descriptor:
            if (path.parent / "inputs.tsv").read_bytes() != lifecycle_payload(descriptor["lifecycle"]):
                raise ValueError("focused input differs from the specification")
            if receipt["verification"]["outcome"] != "completed":
                raise ValueError("focused lifecycle did not verify")
            parameters["workloadFile"] = row["params"]["workloadFile"]
            if Path(parameters["workloadFile"]).name != "inputs.tsv":
                raise ValueError("focused lifecycle used an unexpected workload file")
        validate_raw(raw, descriptor, parameters)
        command = receipt["command"]
        classpath = ":".join(entry["path"] for entry in runners["classpath"])
        if (command[command.index("-cp") + 1] != classpath or
                ("--enable-native-access=ALL-UNNAMED" in command) != (mode == "native")):
            raise ValueError("focused control uses a different source build or memory mode")
        hashes[str(path.relative_to(results))] = collection.digest(path.read_bytes())
    return hashes
