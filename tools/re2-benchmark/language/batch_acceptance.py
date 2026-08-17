#!/usr/bin/env python3
"""Regenerate language-batch acceptance from input, raw results, and host identity."""

import argparse
import csv
from pathlib import Path

import collection
import fleet
import focused_controls
import source_bracket


def validate_source_bracket(inputs, worker, batch, environment, candidate):
    manifest = collection.load(inputs / batch["packages"][0]["directory"] / "manifest.json")
    control = source_bracket.validate(manifest, inputs)
    if control is None:
        if manifest.get("focused_controls"):
            raise ValueError("focused controls require a source bracket")
        return None
    marker = collection.load(worker / "source-bracket.json")
    if (marker["order"] != ["candidate-before", "control", "candidate-after"] or
            marker["results"] != ["results", "control-results", "after-results"]):
        raise ValueError("source bracket is incomplete or has the wrong order")
    with (inputs / source_bracket.PROVENANCE).open() as file:
        receipts = list(csv.DictReader(file, delimiter="\t"))
    if len(receipts) != 1:
        raise ValueError("invalid source-control provenance")
    expected_control = {"source_commit": receipts[0]["candidate_commit"], "source_tree": receipts[0]["root_tree"]}
    expected_candidate = {key: candidate[key] for key in ("source_commit", "source_tree")}
    if marker["control"] != expected_control or marker["candidate"] != expected_candidate:
        raise ValueError("source bracket identifies different source revisions")
    hashes = {}
    candidate_runners = None
    for leg in marker["results"]:
        expected_source = expected_control if leg == "control-results" else expected_candidate
        directory = worker / leg
        if set(directory.rglob("job.json")) != {directory / job / "job.json" for job in batch["batch"]["jobs"]}:
            raise ValueError("source bracket leg has missing or unexpected jobs")
        runners = None
        hashes[leg] = {}
        for package in batch["packages"]:
            job = package["receipt"]["job"]
            result = directory / job["id"]
            if collection.load(result / "job.json") != {"job": job, "plan_sha256": batch["plan_sha256"]}:
                raise ValueError("source bracket job identity changed")
            partition = inputs / package["directory"]
            data = collection.export(partition, result, write=False)
            path = result / "language-results.json"
            if collection.load(path) != data:
                raise ValueError("source bracket export differs from raw evidence")
            provenance = data["provenance"]
            if (any(provenance[key] != value for key, value in expected_source.items()) or
                    provenance["platform"] != environment["platform"] or
                    provenance["instance_identity"]["instanceId"] != environment["instance_id"] or
                    provenance["instance_identity"]["instanceType"] != environment["instance_type"] or
                    provenance["jdk"] != candidate["jdk"] or
                    provenance["comparators_sha256"] != candidate["comparators_sha256"]):
                raise ValueError("source bracket changed host, source or comparator")
            if runners is not None and runners != data["runners"]:
                raise ValueError("source bracket changed runners within a leg")
            runners = data["runners"]
            hashes[leg][job["id"]] = collection.digest(path.read_bytes())
            hashes[leg][job["id"] + "/focused"] = focused_controls.validate_results(partition, directory / partition.name, runners)
        if leg == "results":
            candidate_runners = runners
        if leg == "after-results" and runners != candidate_runners:
            raise ValueError("candidate runners differ between bracket legs")
    return {"marker_sha256": collection.digest((worker / "source-bracket.json").read_bytes()), "legs": hashes}


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
            environment["replica"] != str(assignment["replica"])):
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
    bracket = validate_source_bracket(inputs, worker, batch, environment, candidate)
    if bracket is not None:
        receipt["source_bracket"] = bracket
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
