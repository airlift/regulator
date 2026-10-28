#!/usr/bin/env python3
"""Plan language comparisons as work units, run a unit on a unit worker, and validate and export the results.

This module does not allocate AWS resources. A unit worker runs each unit leased
to it on an already-provisioned qualification host with its own clean build
receipts.
"""

import argparse
import copy
import csv
import hashlib
import os
import re
import shutil
import sys
import tarfile
import tempfile
from pathlib import Path

import collection
import batching
import jvm_build
import released_artifact

sys.path.append(str(Path(__file__).resolve().parents[1] / "baseline"))
import engine_build  # noqa: E402
import row_protocol  # noqa: E402
import unit_cost  # noqa: E402
import unit_dispatch  # noqa: E402
import unit_session  # noqa: E402
import work_units  # noqa: E402
from acceptance import EVIDENCE_FIELDS, load_platforms, read_properties, sha256_file  # noqa: E402
from shard_results import write_tsv  # noqa: E402
from unit_session import PROTOCOL_REJECTION_STATUS, SEMANTIC_FAILURE_STATUS  # noqa: E402


PLATFORMS = ("r9g", "r8i")
PARTITION_ID = rf"case-[0-9]{{4,}}-({'|'.join(collection.ENGINES)})"
BATCH_OPERATION_BUDGET = 1
DEFAULT_MAX_CONCURRENT_HOSTS = 64
# A language unit measures its comparisons in plan order, each comparison's observations in collection order.
UNIT_ORDER = "language-observation-order-v1"
SHARD_ID = r"[a-z0-9][a-z0-9-]*"
# The envelope files a language unit attempt binds in its evidence, beside its partitions' raw results.
UNIT_ARTIFACTS = ("environment-manifest.txt", "environment.txt", "engine-build.txt", "unit-rows.tsv",
                  "unit-session.tsv", "run-metadata.txt", "cpu-affinity.json")
# The provenance fields that identify the candidate and comparators a language result measured.
CANDIDATE_FIELDS = ("source_commit", "source_tree", "comparators_sha256", "jdk")


def platform_allocation(suite, platform):
    if suite not in {"language-bulk", "language-lifecycle"} or platform not in PLATFORMS:
        raise ValueError("unknown language suite or platform allocation")
    size = "2xlarge" if suite == "language-lifecycle" and platform == "r9g" else "large"
    return {"instance_type": f"{platform}.{size}", "vcpus": 8 if size == "2xlarge" else 2}


def platform_allocations(suite, platforms=PLATFORMS):
    return {platform: platform_allocation(suite, platform) for platform in platforms}


def host_batches(manifest, partitions, replicas, operation_budget=None, duration_policy=None, platforms=PLATFORMS):
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
    if collection.protocol_for(manifest) == collection.SLOW_BULK_PROTOCOL:
        groups = [[partition["id"]] for partition in partitions]
    elif duration_policy is not None:
        counts = {part["id"]: len(collection.case_operations(manifest, cases[part["case"]])) for part in partitions}
        groups = batching.groups(partitions, counts, operation_budget, duration_policy)
    return [{"id": f"{platform}/{manifest['suite']}-{index:04d}/replica-{replica}",
             "platform": platform, **platform_allocation(manifest["suite"], platform),
             "shard": f"{manifest['suite']}-{index:04d}", "replica": replica,
             "jobs": [f"{platform}/{partition}/replica-{replica}" for partition in group]}
            for index, group in enumerate(groups) for replica in range(1, replicas + 1) for platform in platforms]


def row_atom(row_id):
    """A unit row's case, language and operation; a row is an observation ID, case/language/engine/mode/operation."""
    case, language, _, _, operation = row_id.rsplit("/", 4)
    return case, language, operation


def unit_slices(rows):
    """The operations a unit measures of each (case, language) comparison, in collection order."""
    slices = {}
    for row in rows:
        case, language, operation = row_atom(row)
        slices.setdefault((case, language), set()).add(operation)
    order = {operation: index for index, operation in enumerate((*collection.OPERATIONS, "execute"))}
    return {key: sorted(operations, key=order.__getitem__) for key, operations in sorted(slices.items())}


def unit_digest(partitions, rows):
    """A unit's protocol digest: the manifest digest of each partition it measures, with the operations it measures."""
    digests = {(partition["case"], partition["language"]): partition["manifest_sha256"] for partition in partitions}
    lines = [f"{case}/{language}\t{digests[case, language]}\t{','.join(operations)}"
             for (case, language), operations in unit_slices(rows).items()]
    return hashlib.sha256("\n".join(sorted(lines)).encode()).hexdigest()


def is_pair(value):
    """Whether value is a [case, language] pair."""
    return isinstance(value, list) and len(value) == 2 and all(isinstance(item, str) for item in value)


def is_shard_id(value):
    """Whether value is a plan's shard ID."""
    return isinstance(value, str) and re.fullmatch(SHARD_ID, value) is not None


def comparison_seconds(manifest, case, language, duration_policy=None):
    """Seconds to verify and measure one comparison under one operation.

    A frozen duration estimate for the whole comparison, shared by its operations, raises the estimate.
    Under the slow bulk protocol the estimate covers every measurement process running to its deadline,
    so the worker outlives a comparison that does not finish.
    """
    _, comparator = collection.ENGINES[language]
    native = comparator in collection.NATIVE_RUNNERS
    measured = case["mappings"][language]["status"] != "not-compatible"
    protocol = collection.protocol_for(manifest)
    jvm_observations = (3 - native) if measured else 0
    operations = len(collection.case_operations(manifest, case))
    seconds = unit_cost.language_seconds(protocol, jvm_observations, int(native and measured),
                                         collection.verification_processes(manifest))
    if protocol == collection.SLOW_BULK_PROTOCOL:
        separate = protocol["measurement_profile"] in collection.SEPARATE_ALLOCATION_PROFILES
        processes = jvm_observations * (1 + separate) + int(native and measured)
        seconds = max(seconds, processes * protocol["measurement_timeout_seconds"])
    if duration_policy is not None:
        seconds = max(seconds, duration_policy["partition_seconds"][f"{case['id']}/{language}"] / operations)
    return seconds


def plan_units(manifest, partitions, shard_id, duration_policy=None):
    """Pack the plan's comparisons, one atom per (case, language, operation), into work units.

    An atom keeps a comparison's three observations on one host. Atoms pack into units of at most 15
    minutes; an atom longer than that, or any atom of a comparison the duration policy isolates, is a unit alone.
    """
    cases = {case["id"]: case for case in manifest["cases"]}
    seconds = {(partition["case"], partition["language"]): comparison_seconds(
        manifest, cases[partition["case"]], partition["language"], duration_policy) for partition in partitions}
    isolated = {tuple(pair) for pair in (duration_policy or {}).get("isolated_pairs", [])}

    def cost(atom):
        return sum(seconds[key] * len(operations) for key, operations in unit_slices(atom.owned).items())

    atoms = []
    for partition in partitions:
        case = cases[partition["case"]]
        subset = {**manifest, "cases": [case], "selected_languages": [partition["language"]]}
        for operation in collection.case_operations(manifest, case):
            atoms.append(work_units.Atom([f"{identity}/{operation}" for identity, *_ in collection.observations(subset)]))
    lone = [atom for atom in atoms
            if cost(atom) > work_units.UNIT_SECONDS or row_atom(atom.owned[0])[:2] in isolated]
    lone_ids = {id(atom) for atom in lone}
    packable = [atom for atom in atoms if id(atom) not in lone_ids]
    packed = work_units.pack(packable, cost, work_units.UNIT_SECONDS)
    units = [{
        "unit_id": work_units.unit_id(shard_id, unit.owned, [], unit_digest(partitions, unit.owned), UNIT_ORDER),
        "shard_id": shard_id,
        "unit_index": index,
        "owned": unit.owned,
        "representatives": [],
        "estimated_seconds": cost(unit),
        "atoms": count,
    } for index, (unit, count) in enumerate(sorted(
        [(atom, 1) for atom in lone] + packed, key=lambda packed_unit: packed_unit[0].owned[0]), 1)]
    work_units.check_budget(units, allow_lone_oversize=True)
    return units


def write_units(directory, units):
    for name, table in zip(("units.tsv", "unit-rows.tsv"), work_units.render(units)):
        (directory / name).write_text(table)


def smoke_units(directory):
    """The plan's smoke units: those measuring the first comparison of each language and model, each
    isolated or required comparison, and the longest unit of several comparisons."""
    plan = validate(directory)
    manifest = collection.load(directory / "source/manifest.json")
    units = work_units.load(directory)
    cases = {case["id"]: case for case in manifest["cases"]}
    representatives = {}
    for partition in plan["partitions"]:
        model = cases[partition["case"]].get("model", "lifecycle")
        representatives.setdefault((partition["language"], model), (partition["case"], partition["language"]))
    selected = set(representatives.values())
    selected.update(tuple(pair) for pair in plan.get("duration_policy", {}).get("isolated_pairs", []))
    comparisons = {(partition["case"], partition["language"]) for partition in plan["partitions"]}
    for pair in manifest.get("smoke_required_pairs", []):
        if not is_pair(pair):
            raise ValueError("invalid required smoke case/language pair")
        if tuple(pair) not in comparisons:
            raise ValueError("required smoke pair is absent from the selected plan")
        selected.add(tuple(pair))
    result = {unit_id for unit_id, unit in units.items()
              if any(row_atom(row)[:2] in selected for row in unit["owned"])}
    packed = [unit for unit in units.values() if len(unit["owned"]) > 3]
    if packed:
        result.add(max(packed, key=lambda unit: (float(unit["estimated_seconds"]), unit["unit_id"]))["unit_id"])
    return sorted(result)


class UnitRejection(Exception):
    """A unit attempt ended with a verdict its controller must not retry."""

    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def write_bundle(directory, output):
    """Write a plan's deterministic bundle, which a unit worker installs, and return its SHA-256."""
    directory = directory.resolve()
    validate(directory)
    names = sorted(path.relative_to(directory).as_posix() for path in directory.rglob("*") if path.is_file())
    return engine_build.write_archive(directory, names, output)["sha256"]


def install_bundle(bundle, sha256, plans):
    """Install a checksummed plan bundle as plans/<shard ID>, where run-unit.sh finds its units."""
    if engine_build.sha256_file(bundle) != sha256:
        raise ValueError("language plan bundle checksum mismatch")
    engine_build.archive_contents(bundle)
    plans.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix="plan-", dir=plans))
    try:
        with tarfile.open(bundle, "r:gz") as files:
            files.extractall(staging, filter="data")
        plan = validate(staging)
        staging.rename(plans / plan["shard_id"])
    except Exception:
        # A failed install leaves no units where run-unit.sh looks for plans.
        shutil.rmtree(staging)
        raise


def check_host(environment, suite):
    """A language unit runs on its platform's allocation, with every vCPU visible and the protocol heap."""
    allocation = platform_allocation(suite, environment["platform"])
    differing = [key for key, value in (
        ("verification_status", "verified"), ("benchmark_mode", "unit-worker"),
        ("instance_type", allocation["instance_type"]), ("logical_cpu_count", str(allocation["vcpus"])))
        if environment[key] != value]
    if differing:
        raise ValueError(f"language unit host does not match its platform allocation in {differing}")
    if environment["benchmark_heap_size"] != collection.PROTOCOL["heap"]:
        raise ValueError("language host heap differs from the measurement protocol")


def slice_directory(directory, partition, operations, work):
    """The directory that measures a unit's operations of one comparison: the partition itself when it
    measures them all, otherwise a copy of the partition selecting those operations."""
    path = directory / "partitions" / partition["id"]
    manifest = collection.load(path / "manifest.json")
    if operations == list(collection.case_operations(manifest, manifest["cases"][0])):
        return path
    destination = work / partition["id"]
    destination.mkdir(parents=True)
    copy_inputs(manifest, path, destination)
    collection.save(destination / "manifest.json", {**manifest, "selected_operations": operations})
    return destination


def frozen_unit_rows(directory, unit_id):
    """A unit's rows in its plan's frozen unit-rows.tsv."""
    return [row for row in row_protocol.read_tsv(directory / "unit-rows.tsv") if row["unit_id"] == unit_id]


def unit_partitions(plan, unit):
    """Each partition the unit measures, with the operations it measures there."""
    partitions = {(partition["case"], partition["language"]): partition for partition in plan["partitions"]}
    return [(partitions[key], operations) for key, operations in unit_slices(unit["owned"]).items()]


def jvm_build_once(destination, java, archive, provenance):
    """Build the candidate once per host; a build cut short is rebuilt."""
    if not (destination / "jvm-build.json").is_file():
        shutil.rmtree(destination, ignore_errors=True)
        jvm_build.build(destination, java, collection.ROOT, archive, provenance)
    return destination / "jvm-build.json"


def run_unit(directory, unit_id, replica, root, native_directory, jvm_directory, unit_attempt, region, archive,
             provenance, java="java"):
    """Verify and measure one unit's comparisons on this host, pinned to one CPU, and accept the attempt."""
    directory, root = directory.resolve(), root.resolve()
    plan = collection.load(directory / "plan.json")
    unit = work_units.load(directory)[unit_id]
    suite = collection.load(directory / "source/manifest.json")["suite"]
    environment = read_properties(root / "environment-manifest.txt")
    check_host(environment, suite)
    collection.host_identity(environment["platform"])
    if not hasattr(os, "sched_setaffinity"):
        raise ValueError("qualification requires Linux CPU affinity")
    with provenance.open() as file:
        [candidate] = csv.DictReader(file, delimiter="\t")
    write_tsv(root / "unit-rows.tsv", work_units.UNIT_ROW_FIELDS, frozen_unit_rows(directory, unit_id))
    unit_session.write_worker_unit_session(root, environment, unit_id, replica, unit_attempt, region)
    (root / "run-metadata.txt").write_text("".join(f"{key}={value}\n" for key, value in (
        ("unit_id", unit_id), ("candidate_commit", candidate["candidate_commit"]),
        ("engine_tree", candidate["engine_tree"]), ("heap_size", environment["benchmark_heap_size"]),
        ("regulator_release_version", environment["regulator_release_version"]))))
    receipt = jvm_build_once(jvm_directory, java, archive, provenance)
    classpath = (jvm_directory / "classpath.txt").read_text().strip()
    # Every unit of the plan runs the same binaries, whichever comparators its comparisons need.
    native_runners = {engine: str(native_directory / collection.native_target({"suite": suite}, engine))
                      for engine in collection.NATIVE_RUNNERS}
    affinity = os.sched_getaffinity(0)
    try:
        # Children inherit affinity: all verification and timing run on one CPU.
        os.sched_setaffinity(0, {min(affinity)})
        if len(os.sched_getaffinity(0)) != 1:
            raise ValueError("worker CPU affinity was not applied")
        collection.save(root / "cpu-affinity.json", sorted(os.sched_getaffinity(0)))
        with tempfile.TemporaryDirectory() as work:
            for partition, operations in unit_partitions(plan, unit):
                path = slice_directory(directory, partition, operations, Path(work))
                results = root / "partitions" / partition["id"]
                try:
                    collection.verify(path, results, java, classpath, native_runners)
                except collection.SemanticFailure as error:
                    # Only a verdict on the regex semantics is final; a runner that failed or never
                    # started says nothing about them.
                    raise UnitRejection(SEMANTIC_FAILURE_STATUS, str(error)) from error
                try:
                    collection.measure(path, results, java, classpath, native_runners, environment["platform"],
                                       receipt, archive, provenance)
                except collection.BudgetOverrun as error:
                    raise UnitRejection(PROTOCOL_REJECTION_STATUS, str(error)) from error
    finally:
        os.sched_setaffinity(0, affinity)
    write_unit_evidence(root)
    unit_session.write_unit_receipt(root / "unit-receipt.tsv", validate_unit_attempt(root, directory))


def unit_evidence(root):
    """Every artifact of a unit attempt: its envelope files and its partitions' raw results."""
    paths = [*(root / name for name in UNIT_ARTIFACTS), *(root / "partitions").rglob("*")]
    return sorted(unit_session.host_evidence_rows(root, paths), key=lambda row: row["artifact"])


def write_unit_evidence(root):
    write_tsv(root / "unit-evidence.tsv", EVIDENCE_FIELDS, unit_evidence(root))


def exports_digest(exports):
    """A unit attempt's observed digest: the SHA-256 of its sorted (partition, export SHA-256) pairs."""
    lines = sorted(f"{partition}  {sha256}\n" for partition, sha256 in exports)
    return hashlib.sha256("".join(lines).encode()).hexdigest()


def validate_unit_attempt(root, directory):
    """Regenerate a language unit attempt's receipt from its raw results, host, and frozen plan."""
    root, directory = root.resolve(), directory.resolve()
    session = unit_session.read_unit_session(root)
    if unit_evidence(root) != row_protocol.read_tsv(root / "unit-evidence.tsv"):
        raise ValueError("unit evidence differs from the attempt's artifacts")
    plan = collection.load(directory / "plan.json")
    unit = work_units.load(directory).get(session["unit_id"])
    if unit is None:
        raise ValueError(f"unit {session['unit_id']} is not a frozen unit of the language plan")
    frozen = frozen_unit_rows(directory, unit["unit_id"])
    digest = unit_digest(plan["partitions"], unit["owned"])
    if (row_protocol.read_tsv(root / "unit-rows.tsv") != frozen or
            work_units.unit_id(plan["shard_id"], unit["owned"], [], digest, UNIT_ORDER) != unit["unit_id"]):
        raise ValueError("unit rows differ from the frozen unit")
    environment = read_properties(root / "environment-manifest.txt")
    check_host(environment, collection.load(directory / "source/manifest.json")["suite"])
    unit_session.check_worker_unit_session(session, environment)
    metadata = read_properties(root / "run-metadata.txt")
    if metadata["unit_id"] != unit["unit_id"] or metadata["heap_size"] != environment["benchmark_heap_size"]:
        raise ValueError("unit metadata differs from its unit or host")
    affinity = collection.load(root / "cpu-affinity.json")
    if len(affinity) != 1 or type(affinity[0]) is not int or affinity[0] < 0:
        raise ValueError("language unit lacks single-CPU affinity evidence")
    partitions = unit_partitions(plan, unit)
    if sorted(path.name for path in (root / "partitions").iterdir()) != sorted(partition["id"] for partition, _ in partitions):
        raise ValueError("language unit has missing or unexpected partitions")
    version = environment["regulator_release_version"]
    expected_release = released_artifact.manifest(collection.ROOT, version) if version else None
    candidate = runners = None
    observed, exports = set(), []
    with tempfile.TemporaryDirectory() as work:
        for partition, operations in partitions:
            results = root / "partitions" / partition["id"]
            data = collection.export(slice_directory(directory, partition, operations, Path(work)), results, write=False)
            if collection.load(results / "language-results.json") != data:
                raise ValueError("language export differs from raw evidence")
            provenance = data["provenance"]
            identity = provenance["instance_identity"]
            if (provenance["platform"] != session["platform"] or identity["instanceId"] != session["instance_id"] or
                    identity["instanceType"] != session["instance_type"] or
                    provenance["source_commit"] != metadata["candidate_commit"]):
                raise ValueError("language result uses a different host or candidate")
            if (provenance["comparators_sha256"] != environment["comparator_manifest_sha256"] or
                    collection.JDK_VERSION not in provenance["jdk"] or
                    collection.JDK_VERSION not in environment["java_runtime_version"]):
                raise ValueError(f"language comparator or JDK {collection.JDK_VERSION} identity differs from the host")
            # Each native runner the attempt verified with, checked as collection.measure checked it on the host.
            for engine, (*_, key) in collection.NATIVE_RUNNERS.items():
                if f"{key}_binary_sha256" in data["runners"]:
                    if f"{key}_build" not in provenance:
                        raise ValueError(f"{engine}: language result lacks its native build receipt")
                    collection.validate_native_build(data["manifest"], engine, provenance[f"{key}_build"],
                                                     data["runners"][f"{key}_binary_sha256"])
            release = provenance["jvm_build"].get("released_artifact")
            if expected_release is not None and (release is None or release["manifest"] != expected_release):
                raise ValueError("language partition lacks the selected released artifact")
            current = {key: provenance[key] for key in CANDIDATE_FIELDS}
            if candidate not in (None, current) or runners not in (None, data["runners"]):
                raise ValueError("language unit mixes candidates, comparator revisions, or binaries")
            candidate, runners = current, data["runners"]
            observed.update(data["observations"])
            exports.append((partition["id"], sha256_file(results / "language-results.json")))
    if observed != set(unit["owned"]):
        raise ValueError("language unit observations differ from its rows")
    return {
        **session,
        "shard_id": unit["shard_id"],
        "owned_row_count": str(len(unit["owned"])),
        "row_protocol_sha256": digest,
        "manifest_sha256": sha256_file(directory / "plan.json"),
        "observed_rows_sha256": exports_digest(exports),
        "evidence_manifest_sha256": sha256_file(root / "unit-evidence.tsv"),
        "candidate_commit": metadata["candidate_commit"],
        "candidate_archive_sha256": environment["regulator_archive_sha256"],
        "engine_tree": metadata["engine_tree"],
        "heap_size": metadata["heap_size"],
    }


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


def prepare(manifest_directory, destination, replicas=3, selection=None, operation_budget=None, duration_policy=None,
            max_concurrent_hosts=DEFAULT_MAX_CONCURRENT_HOSTS, shard_id=None):
    manifest_directory, destination = manifest_directory.resolve(), destination.resolve()
    if operation_budget is None:
        operation_budget = BATCH_OPERATION_BUDGET
    if type(replicas) is not int or replicas < 1:
        raise ValueError("replicas must be positive")
    if type(max_concurrent_hosts) is not int or max_concurrent_hosts < 1:
        raise ValueError("max concurrent hosts must be a positive integer")
    manifest = collection.load(manifest_directory / "manifest.json")
    collection.validate_manifest(manifest, manifest_directory)
    if "parent_manifest_sha256" in manifest or "selected_languages" in manifest:
        raise ValueError("fleet requires an unpartitioned source manifest")
    shard_id = manifest["suite"] if shard_id is None else shard_id
    if not is_shard_id(shard_id):
        raise ValueError("the plan's shard ID must be lowercase letters, digits and hyphens, starting with a letter "
                         "or digit")
    complete = {(case['id'], language) for case in manifest['cases'] for language in collection.ENGINES}
    selected = set(map(tuple, selection)) if selection is not None else complete
    if not selected or not selected <= complete or (selection is not None and len(selected) != len(selection)):
        raise ValueError('invalid or duplicate fleet selection')
    if type(operation_budget) is not int or not 1 <= operation_budget <= 16:
        raise ValueError('operation budget must be between 1 and 16')
    if duration_policy is not None:
        batching.validate(duration_policy, [{"case": case, "language": language} for case, language in selected])
    destination.mkdir(parents=True, exist_ok=False)
    source = destination / "source"
    source.mkdir()
    shutil.copyfile(manifest_directory / "manifest.json", source / "manifest.json")
    copy_inputs(manifest, manifest_directory, source)
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
             **platform_allocation(manifest["suite"], platform),
             "partition": partition["id"], "replica": replica}
            for partition in partitions for replica in range(1, replicas + 1) for platform in PLATFORMS]
    plan = {"schema_version": 4, "shard_id": shard_id, "parent_manifest_sha256": parent_hash, "replicas": replicas,
            "platforms": list(PLATFORMS), "max_concurrent_hosts": max_concurrent_hosts,
            "platform_allocations": platform_allocations(manifest["suite"], PLATFORMS),
            "capacity_policy": "up to the limit; continue with available hosts",
            "partitions": partitions, "jobs": jobs,
            "host_batches": host_batches(manifest, partitions, replicas, operation_budget, duration_policy, PLATFORMS),
            "batch_operation_budget": operation_budget}
    if duration_policy is not None:
        plan["duration_policy"] = duration_policy
    if selection is not None:
        plan['selection'] = [list(identity) for identity in sorted(selected)]
    write_units(destination, plan_units(manifest, partitions, shard_id, duration_policy))
    collection.save(destination / "plan.json", plan)
    validate(destination)
    return plan


def validate(directory):
    directory = directory.resolve()
    plan = collection.load(directory / "plan.json")
    source = directory / "source"
    manifest = collection.load(source / "manifest.json")
    collection.validate_manifest(manifest, source)
    if "selected_languages" in manifest or "parent_manifest_sha256" in manifest:
        raise ValueError("fleet source must be unpartitioned")
    parent_hash = collection.digest((source / "manifest.json").read_bytes())
    platforms = PLATFORMS
    if (plan.get("schema_version") != 4 or plan["parent_manifest_sha256"] != parent_hash or
            not isinstance(plan.get("shard_id"), str) or not re.fullmatch(SHARD_ID, plan["shard_id"]) or
            plan["platforms"] != list(platforms) or type(plan["replicas"]) is not int or plan["replicas"] < 1 or
            type(plan["max_concurrent_hosts"]) is not int or plan["max_concurrent_hosts"] < 1 or
            plan.get("platform_allocations") != platform_allocations(manifest["suite"], platforms)):
        raise ValueError("invalid fleet protocol or source identity")
    expected = {(case["id"], language) for case in manifest["cases"] for language in manifest["languages"]}
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
        if not re.fullmatch(PARTITION_ID, partition["id"]):
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
                      **platform_allocation(manifest["suite"], platform),
                      "partition": partition["id"], "replica": replica}
                     for partition in plan["partitions"] for replica in range(1, plan["replicas"] + 1)
                     for platform in platforms]
    if plan["jobs"] != expected_jobs:
        raise ValueError("incomplete or changed job matrix")
    budget = plan.get('batch_operation_budget')
    if type(budget) is not int or not 1 <= budget <= 16:
        raise ValueError('invalid batch operation budget')
    expected_batches = host_batches(
        manifest, plan["partitions"], plan["replicas"], budget, plan.get("duration_policy"), platforms)
    if plan.get("host_batches") != expected_batches:
        raise ValueError("incomplete or changed host batch assignments")
    units = plan_units(manifest, plan["partitions"], plan["shard_id"], plan.get("duration_policy"))
    if tuple((directory / name).read_text() for name in ("units.tsv", "unit-rows.tsv")) != work_units.render(units):
        raise ValueError("work units differ from the plan's comparisons")
    return plan


def run_job(directory, job_id, results, java, classpath, native_runners, receipt, archive=None, candidate_provenance=None):
    plan = validate(directory)
    job = next((job for job in plan["jobs"] if job["id"] == job_id), None)
    if job is None:
        raise ValueError("unknown job")
    # Reject the wrong host before verification or any timing. measure repeats
    # this admission alongside source, JDK, and build-receipt validation.
    collection.host_identity(job["platform"])
    partition = directory.resolve() / "partitions" / job["partition"]
    output = results.resolve() / job["id"]
    collection.verify(partition, output, java, classpath, native_runners)
    collection.measure(partition, output, java, classpath, native_runners, job["platform"], receipt, archive, candidate_provenance)
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
    collection.save(destination / "batch.json", receipt)
    validate_batch(destination)
    return receipt


def validate_batch(directory):
    receipt = collection.load(directory / "batch.json")
    batch = receipt["batch"]
    suite = batch["shard"].rsplit("-", 1)[0]
    if (batch["platform"] not in PLATFORMS or type(batch["replica"]) is not int or batch["replica"] < 1 or
            not re.fullmatch(r"language-(bulk|lifecycle)-[0-9]{4,}", batch["shard"]) or
            batch["id"] != f"{batch['platform']}/{batch['shard']}/replica-{batch['replica']}" or
            {key: batch.get(key) for key in ("instance_type", "vcpus")} != platform_allocation(suite, batch["platform"])):
        raise ValueError("invalid host batch identity")
    found = []
    for package in receipt["packages"]:
        path = (directory / package["directory"]).resolve()
        if not path.is_relative_to(directory.resolve()):
            raise ValueError("batch package path escapes directory")
        actual = validate_package(path)
        job = actual["job"]
        manifest = collection.load(path / "manifest.json")
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
            not re.fullmatch(PARTITION_ID, job["partition"]) or
            job["id"] != f"{job['platform']}/{job['partition']}/replica-{job['replica']}" or
            {key: job.get(key) for key in ("instance_type", "vcpus")} != platform_allocation(manifest["suite"], job["platform"]) or
            not re.fullmatch(r"[0-9a-f]{64}", receipt["plan_sha256"]) or
            receipt["manifest_sha256"] != collection.digest((directory / "manifest.json").read_bytes()) or
            len(manifest["cases"]) != 1 or manifest.get("selected_languages") != [job["partition"].rsplit("-", 1)[1]]):
        raise ValueError("invalid packaged job identity")
    return receipt


def run_package(directory, results, java, classpath, native_runners, receipt, archive=None, candidate_provenance=None):
    package = validate_package(directory)
    job = package["job"]
    collection.host_identity(job["platform"])
    output = results.resolve() / job["id"]
    collection.verify(directory, output, java, classpath, native_runners)
    collection.measure(directory, output, java, classpath, native_runners, job["platform"], receipt, archive, candidate_provenance)
    if validate_package(directory) != package:
        raise ValueError("job package changed during execution")
    collection.save(output / "job.json", {"job": job, "plan_sha256": package["plan_sha256"]})


def verify(directory, results, java, classpath, native_runners):
    """Verify every partition locally, including agreement across language boundaries."""
    directory, results = directory.resolve(), results.resolve()
    plan = validate(directory)
    # A runner missing for a later partition would otherwise strand a partial results directory.
    for partition in plan["partitions"]:
        collection.require_native_runners(
            collection.load(directory / "partitions" / partition["id"] / "manifest.json"), native_runners)
    results.mkdir(parents=True, exist_ok=False)
    receipts = {}
    runner_hash = None
    for index, partition in enumerate(plan["partitions"], start=1):
        evidence = collection.verify(directory / "partitions" / partition["id"],
                                     results / partition["id"], java, classpath, native_runners)
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


def campaign_units(directories, phase):
    """The units a language campaign phase measures across its plans: the smoke units in smoke, every unit
    otherwise. Returns them by unit ID with each unit's protocol digest, and each plan's allocation and
    plan digest by its shard."""
    campaign = {"units": {}, "digests": {}, "allocations": {}, "manifests": {}}
    for directory in directories:
        plan = validate(directory)
        if plan["shard_id"] in campaign["manifests"]:
            raise ValueError(f"language plans share shard {plan['shard_id']}")
        selected = set(smoke_units(directory)) if phase == "smoke" else None
        for unit_id, unit in work_units.load(directory).items():
            if selected is None or unit_id in selected:
                campaign["units"][unit_id] = unit
                campaign["digests"][unit_id] = unit_digest(plan["partitions"], unit["owned"])
        campaign["allocations"][plan["shard_id"]] = {
            platform: allocation["instance_type"] for platform, allocation in plan["platform_allocations"].items()}
        campaign["manifests"][plan["shard_id"]] = sha256_file(directory / "plan.json")
    return campaign


def validate_unit_campaign(directories, receipts, campaign_id, phase="primary"):
    """Accept a language campaign phase's unit receipts as a whole, by the baseline campaign rule: every unit of
    the phase on every platform for each replica, its replicas on distinct instances, and one candidate."""
    campaign = campaign_units(directories, phase)
    unit_session.check_unit_campaign(
        receipts, load_platforms(unit_session.DIRECTORY / "platforms.tsv"), campaign["units"], campaign["digests"],
        campaign["allocations"], campaign["manifests"], campaign_id, (1,) if phase == "smoke" else (1, 2, 3))


def aggregate(directory, result_root, output):
    """Export a plan's accepted primary units from a campaign result root, each attempt revalidated from its
    retained evidence, without rewriting it. The phase must have rejected no attempt for its semantics, a case's
    traces must agree across its partitions, and every attempt must have measured one candidate."""
    directory, result_root, output = directory.resolve(), result_root.resolve(), output.resolve()
    plan = validate(directory)
    if output.exists():
        raise ValueError("will not overwrite an existing fleet export")
    receipts = [receipt for receipt in unit_dispatch.read_accepted_units(result_root / "accepted-units.tsv")
                if receipt["shard_id"] == plan["shard_id"]]
    validate_unit_campaign([directory], receipts, receipts[0]["campaign_id"] if receipts else "")
    unit_session.check_semantic_rejections(result_root)
    observations, comparisons, hosts = [], [], []
    candidate = None
    semantic_traces = {}
    for receipt in receipts:
        attempt = unit_dispatch.unit_attempt_directory(result_root, receipt)
        if validate_unit_attempt(attempt, directory) != receipt:
            raise ValueError(f"accepted unit attempt {receipt['unit_attempt_id']} differs from its evidence")
        partitions = []
        for results in sorted((attempt / "partitions").iterdir()):
            data = collection.load(results / "language-results.json")
            case = data["manifest"]["cases"][0]
            for verification in data["verification"]["receipts"].values():
                if verification["outcome"] == "completed":
                    previous = semantic_traces.setdefault(case["id"], verification["trace"])
                    if previous != verification["trace"]:
                        raise ValueError("verification-failed: language partitions disagree")
            provenance = data["provenance"]
            identity = {key: provenance[key] for key in CANDIDATE_FIELDS}
            if candidate not in (None, identity):
                raise ValueError("mixed candidates or comparator versions")
            candidate = identity
            partitions.append({"id": results.name, "provenance": provenance,
                               "export_sha256": sha256_file(results / "language-results.json")})
            for row_id, row in data["observations"].items():
                observations.append({**row, "unit_attempt_id": receipt["unit_attempt_id"], "partition": results.name,
                                     "id": row_id})
            for row in data["comparisons"]:
                comparisons.append({**row, "unit_attempt_id": receipt["unit_attempt_id"], "partition": results.name})
        hosts.append({"receipt": receipt, "partitions": partitions})
    collection.save(output, {"schema_version": 2, "plan": plan, "candidate": candidate,
                             "hosts": hosts, "observations": observations, "comparisons": comparisons})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--manifest-directory", type=Path, required=True)
    prepare_parser.add_argument("--output-directory", type=Path, required=True)
    prepare_parser.add_argument("--replicas", type=int, default=3)
    prepare_parser.add_argument("--max-concurrent-hosts", type=int, default=DEFAULT_MAX_CONCURRENT_HOSTS)
    prepare_parser.add_argument("--selection", type=Path, help="JSON array of [case ID, language] pairs")
    prepare_parser.add_argument("--batch-operation-budget", type=int, default=BATCH_OPERATION_BUDGET)
    prepare_parser.add_argument("--duration-policy", type=Path, help="frozen partition duration estimates and isolated slow comparisons")
    prepare_parser.add_argument("--shard-id", help="the plan's shard of work units; defaults to the suite")
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
    collection.add_native_runner_arguments(package_runner, required=True)
    package_runner.add_argument("--jvm-build-receipt", type=Path, required=True)
    package_runner.add_argument("--source-archive", type=Path)
    package_runner.add_argument("--candidate-provenance", type=Path)
    bundle_parser = commands.add_parser("bundle", help="write a plan's bundle for unit workers and print its SHA-256")
    bundle_parser.add_argument("--plan-directory", type=Path, required=True)
    bundle_parser.add_argument("--output", type=Path, required=True)
    install_parser = commands.add_parser("install-bundle")
    install_parser.add_argument("--bundle", type=Path, required=True)
    install_parser.add_argument("--sha256", required=True)
    install_parser.add_argument("--plans", type=Path, required=True)
    unit_parser = commands.add_parser("run-unit", help="measure one work unit on this unit worker")
    unit_parser.add_argument("--plan-directory", type=Path, required=True)
    unit_parser.add_argument("--unit", required=True)
    unit_parser.add_argument("--replica", required=True)
    unit_parser.add_argument("--unit-directory", type=Path, required=True)
    unit_parser.add_argument("--native-directory", type=Path, required=True)
    unit_parser.add_argument("--jvm-directory", type=Path, required=True)
    unit_parser.add_argument("--unit-attempt", required=True)
    unit_parser.add_argument("--region", required=True)
    unit_parser.add_argument("--source-archive", type=Path, required=True)
    unit_parser.add_argument("--candidate-provenance", type=Path, required=True)
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
            collection.add_native_runner_arguments(command, required=True)
        if name == "run-job":
            command.add_argument("--job", required=True)
            command.add_argument("--jvm-build-receipt", type=Path, required=True)
            command.add_argument("--source-archive", type=Path)
            command.add_argument("--candidate-provenance", type=Path)
    args = parser.parse_args()
    if args.command == "prepare":
        selection = collection.load(args.selection) if args.selection else None
        plan = prepare(args.manifest_directory, args.output_directory, args.replicas,
                       selection, args.batch_operation_budget, collection.load(args.duration_policy) if args.duration_policy else None,
                       max_concurrent_hosts=args.max_concurrent_hosts, shard_id=args.shard_id)
        print(f"Prepared {len(plan['jobs'])} same-host jobs; no hosts allocated")
        print(work_units.summary([{**unit, "estimated_seconds": float(unit["estimated_seconds"])}
                                  for unit in work_units.load(args.output_directory).values()]))
    elif args.command == "bundle":
        print(write_bundle(args.plan_directory, args.output))
    elif args.command == "install-bundle":
        install_bundle(args.bundle, args.sha256, args.plans)
    elif args.command == "run-unit":
        try:
            run_unit(args.plan_directory, args.unit, args.replica, args.unit_directory, args.native_directory,
                     args.jvm_directory, args.unit_attempt, args.region, archive=args.source_archive,
                     provenance=args.candidate_provenance)
        except UnitRejection as rejection:
            print(f"unit {args.unit} rejected: {rejection}", file=sys.stderr)
            sys.exit(rejection.status)
    elif args.command == "package-job":
        package_job(args.plan_directory, args.job, args.output_directory)
    elif args.command == "package-batch":
        package_batch(args.plan_directory, args.batch, args.output_directory)
    elif args.command == "run-package":
        run_package(args.package_directory, args.results, args.java, args.classpath, collection.native_runner_arguments(args),
                    args.jvm_build_receipt, args.source_archive, args.candidate_provenance)
    elif args.command == "validate":
        plan = validate(args.plan_directory)
        print(f"Validated {len(plan['jobs'])} same-host jobs")
    elif args.command == "run-job":
        run_job(args.plan_directory, args.job, args.results, args.java, args.classpath,
                collection.native_runner_arguments(args), args.jvm_build_receipt, args.source_archive, args.candidate_provenance)
    elif args.command == "verify":
        verify(args.plan_directory, args.results, args.java, args.classpath, collection.native_runner_arguments(args))
    else:
        aggregate(args.plan_directory, args.results, args.output)


if __name__ == "__main__":
    main()
