#!/usr/bin/env python3
"""Regenerate language-batch acceptance from input, raw results, and host identity."""

import argparse
from pathlib import Path

import collection
import fleet
import released_artifact


def properties(path):
    result = {}
    for line in path.read_text().splitlines():
        key, value = line.split("=", 1)
        if key in result:
            raise ValueError("duplicate host identity field")
        result[key] = value
    return result


def validate(directory):
    inputs = directory / "language-inputs"
    worker = directory / "language-worker"
    batch = fleet.validate_batch(inputs)
    completion = collection.load(worker / "worker.json")
    if completion.get("kind") != "qualification" or completion.get("package") != batch:
        raise ValueError("language batch lacks qualification completion")
    environment = properties(directory / "environment-manifest.txt")
    assignment = batch["batch"]
    if (environment["verification_status"] != "verified" or
            environment["benchmark_mode"] != "language-batch" or
            environment["platform"] != assignment["platform"] or
            environment["shard"] != assignment["shard"] or
            environment["replica"] != str(assignment["replica"]) or
            environment["instance_type"] != assignment["instance_type"] or
            environment["logical_cpu_count"] != str(assignment["vcpus"])):
        raise ValueError("language batch does not match its host assignment")
    if environment["benchmark_heap_size"] != collection.PROTOCOL["heap"]:
        raise ValueError("language host heap differs from the measurement protocol")
    affinity = collection.load(worker / "cpu-affinity.json")
    if len(affinity) != 1 or type(affinity[0]) is not int or affinity[0] < 0:
        raise ValueError("language batch lacks single-CPU affinity evidence")
    exports = {}
    expected_results = {worker / "results" / identity / "job.json" for identity in assignment["jobs"]}
    if set((worker / "results").rglob("job.json")) != expected_results:
        raise ValueError("language batch has missing or unexpected result jobs")
    candidate = None
    runners = None
    for package in batch["packages"]:
        job = package["receipt"]["job"]
        result = worker / "results" / job["id"]
        if collection.load(result / "job.json") != {"job": job, "plan_sha256": batch["plan_sha256"]}:
            raise ValueError("language job receipt changed")
        data = collection.export(inputs / package["directory"], result, write=False)
        path = result / "language-results.json"
        if collection.load(path) != data:
            raise ValueError("language export differs from raw evidence")
        provenance = data["provenance"]
        identity = provenance["instance_identity"]
        if (provenance["platform"] != assignment["platform"] or
                identity["instanceId"] != environment["instance_id"] or
                identity["instanceType"] != environment["instance_type"] or
                provenance["source_commit"] != environment["regulator_commit"]):
            raise ValueError("language result uses a different host or candidate")
        if (provenance["comparators_sha256"] != environment["comparator_manifest_sha256"] or
                "25.0.4+7" not in provenance["jdk"] or
                "25.0.4+7" not in environment["java_runtime_version"]):
            raise ValueError("language comparator or JDK identity differs from the host")
        current = {key: provenance[key] for key in ("source_commit", "source_tree", "comparators_sha256", "jdk")}
        release = provenance["jvm_build"].get("released_artifact")
        version = environment.get("regulator_release_version")
        if version:
            expected_release = released_artifact.manifest(collection.ROOT, version)
            if release is None or release["manifest"] != expected_release:
                raise ValueError("language partition lacks the selected released artifact")
        current["released_artifact"] = release["manifest"] if release else None
        if candidate is not None and current != candidate:
            raise ValueError("language batch mixes candidates or comparator revisions")
        candidate = current
        if runners is not None and data["runners"] != runners:
            raise ValueError("language batch changed binaries between partitions")
        runners = data["runners"]
        exports[job["id"]] = collection.digest(path.read_bytes())
    receipt = {
        "schema_version": 1, "workload": "language-batch",
        "campaign_id": environment["baseline_campaign"], "platform": assignment["platform"],
        "shard_id": assignment["shard"], "replica_id": str(assignment["replica"]),
        "host_epoch": environment["host_epoch"], "instance_id": environment["instance_id"],
        "instance_type": environment["instance_type"], "architecture": environment["campaign_architecture"],
        "candidate_commit": candidate["source_commit"], "source_tree": candidate["source_tree"],
        "candidate_archive_sha256": environment["regulator_archive_sha256"],
        "comparators_sha256": candidate["comparators_sha256"], "jdk": candidate["jdk"],
        "heap_size": environment["benchmark_heap_size"],
        "environment_sha256": collection.digest((directory / "environment-manifest.txt").read_bytes()),
        "plan_sha256": batch["plan_sha256"], "batch_sha256": collection.digest(collection.encode(batch)),
        "exports": exports,
    }
    if candidate["released_artifact"] is not None:
        receipt["released_artifact"] = candidate["released_artifact"]
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result_directory", type=Path)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    receipt = validate(args.result_directory)
    path = args.result_directory / "language-acceptance.json"
    if args.write:
        if path.exists():
            raise ValueError("will not overwrite language acceptance")
        collection.save(path, receipt)
    elif collection.load(path) != receipt:
        raise ValueError("language acceptance differs from raw evidence")


if __name__ == "__main__":
    main()
