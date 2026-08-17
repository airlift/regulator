#!/usr/bin/env python3
"""Build and verify a packaged language job; timing requires an explicit qualification host."""

import argparse
import os
from pathlib import Path
import subprocess

import collection
import fleet
import jvm_build
import source_bracket


def profile_partition(partition, results, java, classpath):
    """Run optional diagnostics in fresh JVMs after timing; never export their elapsed time."""
    manifest = collection.load(partition / "manifest.json")
    if not manifest.get("diagnostic_profiles", False):
        return
    if manifest["suite"] != "language-bulk":
        raise ValueError("diagnostic profiles require bulk workloads")
    for identity, case, language, engine, mode in collection.observations(manifest):
        if engine == "native-re2" or case["model"] == "compile":
            continue
        if case["mappings"][language]["status"] == "not-compatible":
            continue
        output = results / "diagnostic-profiles" / identity
        output.mkdir(parents=True, exist_ok=False)
        workload = partition / case["mappings"][language]["input_file"]
        command = collection.java_command(java, classpath, mode) + [
            "io.airlift.regulator.LanguageBulkProfile", engine, str(workload.resolve()),
            str(case["expected_result"]), str((output / "profile.jfr").resolve())]
        with (output / "profile.log").open("wb") as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
        collection.save(output / "profile.json", {"kind": "diagnostic-only", "command": command,
                                                  "manifest_sha256": collection.digest((partition / "manifest.json").read_bytes())})


def run(package_directory, destination, java="java", *, measure=False, archive=None, provenance=None, batch=False):
    package_directory, destination = package_directory.resolve(), destination.resolve()
    package = fleet.validate_batch(package_directory) if batch else fleet.validate_package(package_directory)
    partitions = ([package_directory / entry["directory"] for entry in package["packages"]] if batch else [package_directory])
    manifest = collection.load(partitions[0] / "manifest.json")
    job = package["batch"] if batch else package["job"]
    if measure:
        collection.host_identity(job["platform"])
        if not hasattr(os, "sched_setaffinity"):
            raise ValueError("qualification requires Linux CPU affinity")
    jvm_build.require_clean_source(collection.ROOT, archive, provenance)
    destination.mkdir(parents=True, exist_ok=False)
    build_directory = destination / "jvm"
    jvm_build.build(build_directory, java, collection.ROOT, archive, provenance)
    classpath = (build_directory / "classpath.txt").read_text().strip()
    native_target = "language_bulk_benchmark" if manifest["suite"] == "language-bulk" else "language_benchmark"
    native_directory = destination / "native"
    environment = dict(os.environ, RE2_BENCHMARK_TARGETS=native_target,
                       RE2_BENCHMARK_BUILD_DIR=str(native_directory))
    with (destination / "native-build.log").open("wb") as log:
        subprocess.run([str(collection.ROOT / "tools/re2-benchmark/build.sh")], cwd=collection.ROOT,
                       env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
    native = str(native_directory / native_target)
    control = source_bracket.prepare(manifest, package_directory, destination, java)
    results = destination / "results"
    if measure:
        # Children inherit affinity. Build first, then pin all verification and timing to one CPU.
        affinity = os.sched_getaffinity(0)
        try:
            os.sched_setaffinity(0, {min(affinity)})
            if len(os.sched_getaffinity(0)) != 1:
                raise ValueError("worker CPU affinity was not applied")
            collection.save(destination / "cpu-affinity.json", sorted(os.sched_getaffinity(0)))
            source_bracket.run(control, partitions, destination, java, classpath, native,
                               build_directory / "jvm-build.json", archive, provenance, fleet.run_package)
            # No profiled process overlaps a timing process, including subsequent partitions.
            for partition in partitions:
                profile_partition(partition, results, java, classpath)
        finally:
            os.sched_setaffinity(0, affinity)
    else:
        for partition in partitions:
            output = results / partition.name if batch else results
            collection.verify(partition, output, java, classpath, native)
            if control is not None:
                with source_bracket.source_root(control["root"]):
                    collection.verify(partition, destination / "control-results" / partition.name,
                                      java, control["classpath"], native)
    actual = fleet.validate_batch(package_directory) if batch else fleet.validate_package(package_directory)
    if actual != package:
        raise ValueError("worker input changed during execution")
    collection.save(destination / "worker.json", {
        "kind": "qualification" if measure else "verification-only", "package": package,
        "native_target": native_target})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--package-directory", type=Path)
    inputs.add_argument("--batch-directory", type=Path)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--measure", action="store_true")
    parser.add_argument("--source-archive", type=Path)
    parser.add_argument("--candidate-provenance", type=Path)
    args = parser.parse_args()
    run(args.package_directory or args.batch_directory, args.output_directory, args.java, measure=args.measure,
        archive=args.source_archive, provenance=args.candidate_provenance, batch=args.batch_directory is not None)


if __name__ == "__main__":
    main()
