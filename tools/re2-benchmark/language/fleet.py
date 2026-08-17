#!/usr/bin/env python3
"""Partition same-host language comparisons and validate their collected results.

This module does not allocate AWS resources. A worker runs one job on an
already-provisioned qualification host with its own clean build receipts.
"""

import argparse
import copy
import re
import shutil
from pathlib import Path

import collection
import source_bracket


PLATFORMS = ("c9g", "c8g", "c8i")
BATCH_OPERATION_BUDGET = 1


def host_batches(manifest, partitions, replicas, operation_budget=None):
    """Group whole language partitions, never split a native/safe/comparator comparison."""
    if operation_budget is None:
        operation_budget = BATCH_OPERATION_BUDGET
    cases = {case["id"]: case for case in manifest["cases"]}
    groups = []
    group = []
    operations = 0
    for partition in partitions:
        count = len(collection.case_operations(manifest, cases[partition["case"]]))
        if group and operations + count > operation_budget:
            groups.append(group)
            group, operations = [], 0
        group.append(partition["id"])
        operations += count
    if group:
        groups.append(group)
    return [{"id": f"{platform}/{manifest['suite']}-{index:04d}/replica-{replica}",
             "platform": platform, "shard": f"{manifest['suite']}-{index:04d}", "replica": replica,
             "jobs": [f"{platform}/{partition}/replica-{replica}" for partition in group]}
            for index, group in enumerate(groups) for replica in range(1, replicas + 1) for platform in PLATFORMS]


def copy_inputs(manifest, source, destination):
    paths = {mapping["input_file"] for case in manifest["cases"]
             for mapping in case["mappings"].values() if mapping["status"] != "not-compatible"}
    if manifest.get("suite") == "language-bulk":
        paths.update(case["source_file"] for case in manifest["cases"])
        paths.update(mapping["input_file"] for case in manifest["cases"] for mapping in case["mappings"].values())
    for name in paths:
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target)


def prepare(manifest_directory, destination, replicas=3, selection=None, operation_budget=None):
    manifest_directory, destination = manifest_directory.resolve(), destination.resolve()
    if operation_budget is None:
        operation_budget = BATCH_OPERATION_BUDGET
    if type(replicas) is not int or replicas < 1:
        raise ValueError("replicas must be positive")
    manifest = collection.load(manifest_directory / "manifest.json")
    collection.validate_manifest(manifest, manifest_directory)
    if "parent_manifest_sha256" in manifest or "selected_languages" in manifest:
        raise ValueError("fleet requires an unpartitioned source manifest")
    complete = {(case['id'], language) for case in manifest['cases'] for language in collection.ENGINES}
    selected = set(map(tuple, selection)) if selection is not None else complete
    if not selected or not selected <= complete or (selection is not None and len(selected) != len(selection)):
        raise ValueError('invalid or duplicate fleet selection')
    if type(operation_budget) is not int or not 1 <= operation_budget <= 16:
        raise ValueError('operation budget must be between 1 and 16')
    destination.mkdir(parents=True, exist_ok=False)
    source = destination / "source"
    source.mkdir()
    shutil.copyfile(manifest_directory / "manifest.json", source / "manifest.json")
    copy_inputs(manifest, manifest_directory, source)
    source_bracket.copy(manifest, manifest_directory, source)
    parent_hash = collection.digest((manifest_directory / "manifest.json").read_bytes())
    partitions = []
    for case_index, case in enumerate(manifest["cases"]):
        for language in collection.ENGINES:
            if (case['id'], language) not in selected:
                continue
            partition_id = f"case-{case_index:04d}-{language}"
            directory = destination / "partitions" / partition_id
            directory.mkdir(parents=True)
            subset = copy.deepcopy(manifest)
            subset.update(cases=[case], selected_languages=[language], parent_manifest_sha256=parent_hash)
            copy_inputs(subset, manifest_directory, directory)
            collection.validate_manifest(subset, directory)
            collection.save(directory / "manifest.json", subset)
            partitions.append({"id": partition_id, "case": case["id"], "language": language,
                               "manifest_sha256": collection.digest((directory / "manifest.json").read_bytes())})
    jobs = [{"id": f"{platform}/{partition['id']}/replica-{replica}", "platform": platform,
             "partition": partition["id"], "replica": replica}
            for partition in partitions for replica in range(1, replicas + 1) for platform in PLATFORMS]
    plan = {"schema_version": 2, "parent_manifest_sha256": parent_hash, "replicas": replicas,
            "platforms": list(PLATFORMS), "max_concurrent_hosts": 64,
            "capacity_policy": "up to the limit; continue with available hosts",
            "partitions": partitions, "jobs": jobs,
            "host_batches": host_batches(manifest, partitions, replicas, operation_budget),
            "batch_operation_budget": operation_budget}
    if selection is not None:
        plan['selection'] = [list(identity) for identity in sorted(selected)]
    collection.save(destination / "plan.json", plan)
    validate(destination)
    return plan


def validate(directory):
    directory = directory.resolve()
    plan = collection.load(directory / "plan.json")
    source = directory / "source"
    manifest = collection.load(source / "manifest.json")
    collection.validate_manifest(manifest, source)
    source_bracket.validate(manifest, source)
    if "selected_languages" in manifest or "parent_manifest_sha256" in manifest:
        raise ValueError("fleet source must be unpartitioned")
    parent_hash = collection.digest((source / "manifest.json").read_bytes())
    if (plan["schema_version"] != 2 or plan["parent_manifest_sha256"] != parent_hash or
            plan["platforms"] != list(PLATFORMS) or type(plan["replicas"]) is not int or plan["replicas"] < 1 or
            plan["max_concurrent_hosts"] != 64):
        raise ValueError("invalid fleet protocol or source identity")
    expected = {(case["id"], language) for case in manifest["cases"] for language in collection.ENGINES}
    if 'selection' in plan:
        selected = {tuple(identity) for identity in plan['selection']}
        if (not selected or not selected <= expected or
                plan['selection'] != [list(identity) for identity in sorted(selected)]):
            raise ValueError('invalid or duplicate fleet selection')
        expected = selected
    found = set()
    partition_ids = set()
    cases = {case["id"]: case for case in manifest["cases"]}
    for partition in plan["partitions"]:
        if not re.fullmatch(r"case-[0-9]{4,}-(re2|java|trino)", partition["id"]):
            raise ValueError("invalid partition ID")
        identity = (partition["case"], partition["language"])
        if identity not in expected or identity in found or partition["id"] in partition_ids:
            raise ValueError("missing, duplicate, or unexpected partition")
        found.add(identity)
        partition_ids.add(partition["id"])
        path = directory / "partitions" / partition["id"]
        if not path.resolve().is_relative_to((directory / "partitions").resolve()):
            raise ValueError("partition path escapes plan")
        subset = copy.deepcopy(manifest)
        subset.update(cases=[cases[partition["case"]]], selected_languages=[partition["language"]],
                      parent_manifest_sha256=parent_hash)
        actual = collection.load(path / "manifest.json")
        if actual != subset or collection.digest((path / "manifest.json").read_bytes()) != partition["manifest_sha256"]:
            raise ValueError("partition differs from its parent manifest")
        collection.validate_manifest(actual, path)
    if found != expected:
        raise ValueError("incomplete fleet coverage")
    expected_jobs = [{"id": f"{platform}/{partition['id']}/replica-{replica}", "platform": platform,
                      "partition": partition["id"], "replica": replica}
                     for partition in plan["partitions"] for replica in range(1, plan["replicas"] + 1)
                     for platform in PLATFORMS]
    if plan["jobs"] != expected_jobs:
        raise ValueError("incomplete or changed job matrix")
    budget = plan.get('batch_operation_budget')
    if type(budget) is not int or not 1 <= budget <= 16:
        raise ValueError('invalid batch operation budget')
    if plan.get("host_batches") != host_batches(manifest, plan["partitions"], plan["replicas"], budget):
        raise ValueError("incomplete or changed host batch assignments")
    return plan


def run_job(directory, job_id, results, java, classpath, native, receipt, archive=None, candidate_provenance=None):
    plan = validate(directory)
    job = next((job for job in plan["jobs"] if job["id"] == job_id), None)
    if job is None:
        raise ValueError("unknown job")
    # Reject the wrong host before verification or any timing. measure repeats
    # this admission alongside source, JDK, and build-receipt validation.
    collection.host_identity(job["platform"])
    partition = directory.resolve() / "partitions" / job["partition"]
    output = results.resolve() / job["id"]
    collection.verify(partition, output, java, classpath, native)
    collection.measure(partition, output, java, classpath, native, job["platform"], receipt, archive, candidate_provenance)
    collection.save(output / "job.json", {"job": job,
                    "plan_sha256": collection.digest((directory / "plan.json").read_bytes())})


def package_job(directory, job_id, destination):
    """Copy only one partition; workers must not receive the entire bulk corpus."""
    plan = validate(directory)
    job = next((job for job in plan["jobs"] if job["id"] == job_id), None)
    if job is None:
        raise ValueError("unknown job")
    return package_partition(directory, job, destination)


def package_partition(directory, job, destination):
    source = directory.resolve() / "partitions" / job["partition"]
    manifest = collection.load(source / "manifest.json")
    destination.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(source / "manifest.json", destination / "manifest.json")
    copy_inputs(manifest, source, destination)
    receipt = {"job": job, "plan_sha256": collection.digest((directory / "plan.json").read_bytes()),
               "manifest_sha256": collection.digest((source / "manifest.json").read_bytes())}
    collection.save(destination / "package.json", receipt)
    validate_package(destination)
    return receipt


def package_batch(directory, batch_id, destination):
    plan = validate(directory)
    return package_validated_batch(directory, plan, batch_id, destination)


def package_validated_batch(directory, plan, batch_id, destination):
    """Package an already-validated plan; recheck each selected partition while copying."""
    if collection.load(directory / "plan.json") != plan:
        raise ValueError("fleet plan changed before packaging")
    batch = next((batch for batch in plan["host_batches"] if batch["id"] == batch_id), None)
    if batch is None:
        raise ValueError("unknown host batch")
    destination.mkdir(parents=True, exist_ok=False)
    jobs = {job["id"]: job for job in plan["jobs"]}
    packages = []
    partitions = {partition["id"]: partition for partition in plan["partitions"]}
    for identity in batch["jobs"]:
        job = jobs[identity]
        path = Path("partitions") / job["partition"]
        receipt = package_partition(directory, job, destination / path)
        if receipt["manifest_sha256"] != partitions[job["partition"]]["manifest_sha256"]:
            raise ValueError("partition changed before packaging")
        packages.append({"directory": str(path), "receipt": receipt})
    receipt = {"batch": batch, "plan_sha256": collection.digest((directory / "plan.json").read_bytes()),
               "packages": packages}
    manifest = collection.load(directory / "source/manifest.json")
    source_bracket.copy(manifest, directory / "source", destination)
    collection.save(destination / "batch.json", receipt)
    validate_batch(destination)
    return receipt


def validate_batch(directory):
    receipt = collection.load(directory / "batch.json")
    batch = receipt["batch"]
    if (batch["platform"] not in PLATFORMS or type(batch["replica"]) is not int or batch["replica"] < 1 or
            not re.fullmatch(r"language-(bulk|lifecycle)-[0-9]{4,}", batch["shard"]) or
            batch["id"] != f"{batch['platform']}/{batch['shard']}/replica-{batch['replica']}"):
        raise ValueError("invalid host batch identity")
    found = []
    for package in receipt["packages"]:
        path = (directory / package["directory"]).resolve()
        if not path.is_relative_to(directory.resolve()):
            raise ValueError("batch package path escapes directory")
        actual = validate_package(path)
        job = actual["job"]
        manifest = collection.load(path / "manifest.json")
        source_bracket.validate(manifest, directory)
        if (actual != package["receipt"] or actual["plan_sha256"] != receipt["plan_sha256"] or
                job["platform"] != batch["platform"] or job["replica"] != batch["replica"] or
                not batch["shard"].startswith(manifest["suite"] + "-")):
            raise ValueError("batch package differs from its assignment")
        found.append(job["id"])
    if not found or len(found) != len(set(found)) or found != batch["jobs"]:
        raise ValueError("missing or duplicate batch jobs")
    return receipt


def validate_package(directory):
    receipt = collection.load(directory / "package.json")
    manifest = collection.load(directory / "manifest.json")
    collection.validate_manifest(manifest, directory)
    job = receipt["job"]
    if (job["platform"] not in PLATFORMS or type(job["replica"]) is not int or job["replica"] < 1 or
            not re.fullmatch(r"case-[0-9]{4,}-(re2|java|trino)", job["partition"]) or
            job["id"] != f"{job['platform']}/{job['partition']}/replica-{job['replica']}" or
            not re.fullmatch(r"[0-9a-f]{64}", receipt["plan_sha256"]) or
            receipt["manifest_sha256"] != collection.digest((directory / "manifest.json").read_bytes()) or
            len(manifest["cases"]) != 1 or manifest.get("selected_languages") != [job["partition"].rsplit("-", 1)[1]]):
        raise ValueError("invalid packaged job identity")
    return receipt


def run_package(directory, results, java, classpath, native, receipt, archive=None, candidate_provenance=None):
    package = validate_package(directory)
    job = package["job"]
    collection.host_identity(job["platform"])
    output = results.resolve() / job["id"]
    collection.verify(directory, output, java, classpath, native)
    collection.measure(directory, output, java, classpath, native, job["platform"], receipt, archive, candidate_provenance)
    if validate_package(directory) != package:
        raise ValueError("job package changed during execution")
    collection.save(output / "job.json", {"job": job, "plan_sha256": package["plan_sha256"]})


def verify(directory, results, java, classpath, native):
    """Verify every partition locally, including agreement across language boundaries."""
    directory, results = directory.resolve(), results.resolve()
    plan = validate(directory)
    results.mkdir(parents=True, exist_ok=False)
    receipts = {}
    runner_hash = None
    for index, partition in enumerate(plan["partitions"], start=1):
        evidence = collection.verify(directory / "partitions" / partition["id"],
                                     results / partition["id"], java, classpath, native)
        if runner_hash is not None and runner_hash != evidence["runners_sha256"]:
            raise ValueError("runners changed between partition verifications")
        runner_hash = evidence["runners_sha256"]
        if receipts.keys() & evidence["receipts"].keys():
            raise ValueError("duplicate partition verification identities")
        receipts.update(evidence["receipts"])
        print(f"Verified {index}/{len(plan['partitions'])} partitions", flush=True)
    source = collection.load(directory / "source/manifest.json")
    expected_receipts = set()
    for case in source['cases']:
        languages = [partition['language'] for partition in plan['partitions'] if partition['case'] == case['id']]
        if not languages:
            continue
        subset = {**source, 'cases': [case], 'selected_languages': languages,
                  'parent_manifest_sha256': plan['parent_manifest_sha256']}
        identities = {identity for identity, *_ in collection.observations(subset)}
        collection.validate_receipts(subset, {identity: receipts[identity] for identity in identities})
        expected_receipts.update(identities)
    if receipts.keys() != expected_receipts:
        raise ValueError('verification receipts differ from selected coverage')
    if validate(directory) != plan:
        raise ValueError("fleet plan changed during verification")
    collection.save(results / "verification.json", {
        "plan_sha256": collection.digest((directory / "plan.json").read_bytes()),
        "runners_sha256": runner_hash, "receipts": receipts})


def aggregate(directory, results, output):
    directory, results, output = directory.resolve(), results.resolve(), output.resolve()
    plan = validate(directory)
    if output.exists():
        raise ValueError("will not overwrite an existing fleet export")
    plan_hash = collection.digest((directory / "plan.json").read_bytes())
    observations, comparisons, hosts = [], [], []
    candidate = None
    replica_instances = {}
    semantic_traces = {}
    for job in plan["jobs"]:
        result = results / job["id"]
        if collection.load(result / "job.json") != {"job": job, "plan_sha256": plan_hash}:
            raise ValueError("job receipt does not identify this plan")
        partition = directory / "partitions" / job["partition"]
        data = collection.export(partition, result, write=False)
        saved_export = result / "language-results.json"
        if collection.load(saved_export) != data:
            raise ValueError("saved export differs from its raw evidence")
        case = data["manifest"]["cases"][0]
        for receipt in data["verification"]["receipts"].values():
            if receipt["outcome"] == "completed":
                previous = semantic_traces.setdefault(case["id"], receipt["trace"])
                if previous != receipt["trace"]:
                    raise ValueError("verification-failed: language partitions disagree")
        provenance = data["provenance"]
        identity = {key: provenance[key] for key in ("source_commit", "source_tree", "comparators_sha256", "jdk")}
        if candidate is None:
            candidate = identity
        if identity != candidate or provenance["platform"] != job["platform"]:
            raise ValueError("mixed candidates, comparator versions, or platforms")
        instance = provenance["instance_identity"]
        if not instance["instanceId"] or not instance["instanceType"].startswith(job["platform"] + "."):
            raise ValueError("job host does not match platform")
        key = (job["platform"], job["partition"])
        if instance["instanceId"] in replica_instances.setdefault(key, set()):
            raise ValueError("replicas must use independent hosts")
        replica_instances[key].add(instance["instanceId"])
        hosts.append({"job": job, "provenance": provenance,
                      "export_sha256": collection.digest((result / "language-results.json").read_bytes())})
        for row_id, row in data["observations"].items():
            observations.append({**row, "job": job["id"], "id": row_id})
        for row in data["comparisons"]:
            comparisons.append({**row, "job": job["id"]})
    collection.save(output, {"schema_version": 1, "plan": plan, "candidate": candidate,
                             "hosts": hosts, "observations": observations, "comparisons": comparisons})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--manifest-directory", type=Path, required=True)
    prepare_parser.add_argument("--output-directory", type=Path, required=True)
    prepare_parser.add_argument("--replicas", type=int, default=3)
    prepare_parser.add_argument("--selection", type=Path, help="JSON array of [case ID, language] pairs")
    prepare_parser.add_argument("--batch-operation-budget", type=int, default=BATCH_OPERATION_BUDGET)
    package_parser = commands.add_parser("package-job")
    package_parser.add_argument("--plan-directory", type=Path, required=True)
    package_parser.add_argument("--job", required=True)
    package_parser.add_argument("--output-directory", type=Path, required=True)
    batch_parser = commands.add_parser("package-batch")
    batch_parser.add_argument("--plan-directory", type=Path, required=True)
    batch_parser.add_argument("--batch", required=True)
    batch_parser.add_argument("--output-directory", type=Path, required=True)
    package_runner = commands.add_parser("run-package")
    package_runner.add_argument("--package-directory", type=Path, required=True)
    package_runner.add_argument("--results", type=Path, required=True)
    package_runner.add_argument("--java", default="java")
    package_runner.add_argument("--classpath", required=True)
    package_runner.add_argument("--native-runner", required=True)
    package_runner.add_argument("--jvm-build-receipt", type=Path, required=True)
    package_runner.add_argument("--source-archive", type=Path)
    package_runner.add_argument("--candidate-provenance", type=Path)
    for name in ("validate", "verify", "run-job", "aggregate"):
        command = commands.add_parser(name)
        command.add_argument("--plan-directory", type=Path, required=True)
        if name != "validate":
            command.add_argument("--results", type=Path, required=True)
        if name == "aggregate":
            command.add_argument("--output", type=Path, required=True)
        if name in ("verify", "run-job"):
            command.add_argument("--java", default="java")
            command.add_argument("--classpath", required=True)
            command.add_argument("--native-runner", required=True)
        if name == "run-job":
            command.add_argument("--job", required=True)
            command.add_argument("--jvm-build-receipt", type=Path, required=True)
            command.add_argument("--source-archive", type=Path)
            command.add_argument("--candidate-provenance", type=Path)
    args = parser.parse_args()
    if args.command == "prepare":
        selection = collection.load(args.selection) if args.selection else None
        plan = prepare(args.manifest_directory, args.output_directory, args.replicas,
                       selection, args.batch_operation_budget)
        print(f"Prepared {len(plan['jobs'])} same-host jobs; no hosts allocated")
    elif args.command == "package-job":
        package_job(args.plan_directory, args.job, args.output_directory)
    elif args.command == "package-batch":
        package_batch(args.plan_directory, args.batch, args.output_directory)
    elif args.command == "run-package":
        run_package(args.package_directory, args.results, args.java, args.classpath, args.native_runner,
                    args.jvm_build_receipt, args.source_archive, args.candidate_provenance)
    elif args.command == "validate":
        plan = validate(args.plan_directory)
        print(f"Validated {len(plan['jobs'])} same-host jobs")
    elif args.command == "run-job":
        run_job(args.plan_directory, args.job, args.results, args.java, args.classpath,
                args.native_runner, args.jvm_build_receipt, args.source_archive, args.candidate_provenance)
    elif args.command == "verify":
        verify(args.plan_directory, args.results, args.java, args.classpath, args.native_runner)
    else:
        aggregate(args.plan_directory, args.results, args.output)


if __name__ == "__main__":
    main()
