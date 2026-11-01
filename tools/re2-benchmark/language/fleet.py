#!/usr/bin/env python3
"""Plan language comparisons as work units, run a unit on a unit worker, and validate and export the results.

This module does not allocate AWS resources. A unit worker runs each unit leased
to it on an already-provisioned qualification host, with the candidate classes
and native runners of its platform's engine build.
"""

import argparse
import copy
import csv
import hashlib
import math
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path

import collection
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
# A language unit measures its comparisons in case-ID and language order, each comparison's observations in
# collection order. Each comparison is one of the unit's measured groups, which every replica runs in this order.
UNIT_ORDER = "language-observation-order-v1"
SHARD_ID = r"[a-z0-9][a-z0-9-]*"
# The envelope files a language unit attempt binds in its evidence, beside its partitions' raw results.
UNIT_ARTIFACTS = ("environment-manifest.txt", "environment.txt", "engine-build.txt", "unit-rows.tsv",
                  "unit-session.tsv", "run-metadata.txt", "cpu-affinity.json")
# The provenance fields that identify the candidate and comparators a language result measured.
CANDIDATE_FIELDS = ("source_commit", "source_tree", "comparators_sha256", "jdk")
# A partial export's name ends in this, so it is never taken for a complete one.
PARTIAL_SUFFIX = "-partial-results.json"
# The fields of a partial export's missing groups, which it records and lists beside it.
MISSING_FIELDS = ("case_id", "language", "platform", "replica_id", "unit_id", "group_id")


def platform_allocation(suite, platform):
    if suite not in {"language-bulk", "language-lifecycle"} or platform not in PLATFORMS:
        raise ValueError("unknown language suite or platform allocation")
    size = "2xlarge" if suite == "language-lifecycle" and platform == "r9g" else "large"
    return {"instance_type": f"{platform}.{size}", "vcpus": 8 if size == "2xlarge" else 2}


def platform_allocations(suite, platforms=PLATFORMS):
    return {platform: platform_allocation(suite, platform) for platform in platforms}


def validate_duration_policy(policy, selected):
    """A frozen duration policy names its evidence and estimates every selected comparison."""
    keys = {f"{case}/{language}" for case, language in selected}
    if (policy.get("schema_version") != 1 or
            not re.fullmatch(r"[0-9a-f]{64}", policy.get("source_sha256", "")) or
            set(policy.get("partition_seconds", {})) != keys):
        raise ValueError("duration policy must identify evidence and every selected partition")
    for value in policy["partition_seconds"].values():
        if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
            raise ValueError("invalid partition duration estimate")
    isolated = policy.get("isolated_pairs", [])
    if (not all(is_pair(pair) for pair in isolated) or len({tuple(pair) for pair in isolated}) != len(isolated) or
            not {"/".join(pair) for pair in isolated} <= keys):
        raise ValueError("invalid isolated comparison selection")


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


def comparison_rows(rows):
    """A unit's rows by (case, language) comparison, in the order the unit measures them; each is a group."""
    result = {}
    for row in rows:
        result.setdefault(row_atom(row)[:2], []).append(row)
    return dict(sorted(result.items()))


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
        "groups": [work_units.measured_group(group_id, rows, [], cost(work_units.Atom(rows)))
                   for group_id, rows in enumerate(comparison_rows(unit.owned).values(), 1)],
    } for index, (unit, count) in enumerate(sorted(
        [(atom, 1) for atom in lone] + packed, key=lambda packed_unit: packed_unit[0].owned[0]), 1)]
    work_units.check_budget(units, allow_lone_oversize=True)
    return units


def write_units(directory, units):
    for name, table in zip(("units.tsv", "unit-rows.tsv"), work_units.render(units)):
        (directory / name).write_text(table)


def smoke_units(directory, plan, units):
    """A validated plan's smoke units: those measuring the first comparison of each language and model, each
    isolated or required comparison, and the longest unit that packs several atoms."""
    manifest = collection.load(directory / "source/manifest.json")
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


def bundle_files(directory):
    """The plan files a bundle archives, by their paths in the plan."""
    return sorted(path.relative_to(directory).as_posix() for path in directory.rglob("*") if path.is_file())


def write_bundle(directory, output):
    """Write a plan's deterministic bundle and return its SHA-256. The bundle holds the plan's files under its
    shard ID, so a unit worker unpacks every bundle into one directory, where run-unit.sh finds each plan by its
    shard."""
    directory = directory.resolve()
    plan = validate(directory)
    return engine_build.write_archive(directory, bundle_files(directory), output,
                                      prefix=f"{plan['shard_id']}/")["sha256"]


def bundle_fingerprint(directory):
    """What determines a plan's bundle: each file it archives, by path and SHA-256, and the code that writes it."""
    digest = hashlib.sha256()
    for name in bundle_files(directory):
        digest.update(f"{sha256_file(directory / name)}  {name}\n".encode())
    for path in (Path(__file__), Path(engine_build.__file__)):
        digest.update(f"{sha256_file(path)}  {path.name}\n".encode())
    return digest.hexdigest()


def reuse_bundle(directory, bundles):
    """A plan's bundle under bundles, and its SHA-256: the bundle an earlier start wrote for the same plan files and
    tooling, or a new one. A bundle appears under its fingerprint only once complete, so a restart reuses it."""
    directory = directory.resolve()
    shard = collection.load(directory / "plan.json")["shard_id"]
    bundle = bundles / f"{shard}-{bundle_fingerprint(directory)}.tar.zst"
    if bundle.exists():
        return bundle, sha256_file(bundle)
    partial = bundle.with_name(bundle.name + ".partial")
    sha256 = write_bundle(directory, partial)
    os.replace(partial, bundle)
    return bundle, sha256


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
    """The manifest directory that measures a unit's operations of one comparison: the partition itself when it
    measures them all, otherwise a copy of its manifest selecting those operations. Either way, its input files
    resolve against the plan directory, which holds each input once."""
    path = directory / "partitions" / partition["id"]
    manifest = collection.load(path / "manifest.json")
    if operations == list(collection.case_operations(manifest, manifest["cases"][0])):
        return path
    destination = work / partition["id"]
    destination.mkdir(parents=True)
    collection.save(destination / "manifest.json", {**manifest, "selected_operations": operations})
    return destination


def frozen_unit_rows(directory, unit_id):
    """A unit's rows in its plan's frozen unit-rows.tsv."""
    return [row for row in row_protocol.read_tsv(directory / "unit-rows.tsv") if row["unit_id"] == unit_id]


def unit_partitions(plan, unit):
    """Each partition a measured group measures, with the operations it measures there."""
    partitions = {(partition["case"], partition["language"]): partition for partition in plan["partitions"]}
    return [(partitions[key], operations) for key, operations in unit_slices(unit["owned"]).items()]


def cpu_ticks():
    """The steal and total ticks of /proc/stat's aggregate cpu line; steal is its eighth value. The total sums the
    first eight values, because the guest values that follow are already counted in user and nice."""
    for line in Path("/proc/stat").read_text().splitlines():
        fields = line.split()
        if fields and fields[0] == "cpu":
            values = [int(value) for value in fields[1:]]
            return values[7], sum(values[:8])
    raise ValueError("/proc/stat has no aggregate cpu line")


def run_unit(directory, unit_id, replica, group_id, root, shared, unit_attempt, region, archive, provenance,
             java="java"):
    """Verify and measure one measured group of a unit, its one comparison, on this host, pinned to one CPU, and
    accept the attempt's group."""
    directory, root = directory.resolve(), root.resolve()
    plan = collection.load(directory / "plan.json")
    group = work_units.unit_group(work_units.load(directory)[unit_id], group_id)
    suite = collection.load(directory / "source/manifest.json")["suite"]
    environment = read_properties(root / "environment-manifest.txt")
    check_host(environment, suite)
    collection.host_identity(environment["platform"])
    if not hasattr(os, "sched_setaffinity"):
        raise ValueError("qualification requires Linux CPU affinity")
    with provenance.open() as file:
        [candidate] = csv.DictReader(file, delimiter="\t")
    write_tsv(root / "unit-rows.tsv", work_units.UNIT_ROW_FIELDS, frozen_unit_rows(directory, unit_id))
    unit_session.write_worker_unit_session(root, environment, unit_id, replica, group["group_id"], unit_attempt, region)
    (root / "run-metadata.txt").write_text("".join(f"{key}={value}\n" for key, value in (
        ("unit_id", unit_id), ("candidate_commit", candidate["candidate_commit"]),
        ("engine_tree", candidate["engine_tree"]), ("heap_size", environment["benchmark_heap_size"]),
        ("regulator_release_version", environment["regulator_release_version"]))))
    classpath, receipt = jvm_build.prepare(shared, java, collection.ROOT, archive, provenance)
    # Every unit of the plan runs the same binaries, whichever comparators its comparisons need.
    native_runners = {engine: str(shared / engine_build.LANGUAGE_NATIVE_DIRECTORY /
                                  collection.native_target({"suite": suite}, engine))
                      for engine in collection.NATIVE_RUNNERS}
    affinity = os.sched_getaffinity(0)
    # The host's CPU steal while the group's partitions measure. The counters are diagnostic evidence: the receipt
    # check validates them, but no host check or report reads them.
    steal = total = 0
    try:
        # Children inherit affinity: all verification and timing run on one CPU.
        os.sched_setaffinity(0, {min(affinity)})
        if len(os.sched_getaffinity(0)) != 1:
            raise ValueError("worker CPU affinity was not applied")
        collection.save(root / "cpu-affinity.json", sorted(os.sched_getaffinity(0)))
        with tempfile.TemporaryDirectory() as work:
            for partition, operations in unit_partitions(plan, group):
                path = slice_directory(directory, partition, operations, Path(work))
                results = root / "partitions" / partition["id"]
                try:
                    collection.verify(path, results, java, classpath, native_runners, inputs=directory)
                except collection.SemanticFailure as error:
                    # Only a verdict on the regex semantics is final; a runner that failed or never
                    # started says nothing about them.
                    raise UnitRejection(SEMANTIC_FAILURE_STATUS, str(error)) from error
                steal_before, total_before = cpu_ticks()
                try:
                    collection.measure(path, results, java, classpath, native_runners, environment["platform"],
                                       receipt, inputs=directory)
                except collection.BudgetOverrun as error:
                    raise UnitRejection(PROTOCOL_REJECTION_STATUS, str(error)) from error
                steal_after, total_after = cpu_ticks()
                steal += steal_after - steal_before
                total += total_after - total_before
    finally:
        os.sched_setaffinity(0, affinity)
    with (root / "run-metadata.txt").open("a") as file:
        file.write(f"cpu_steal_ticks={steal}\ncpu_total_ticks={total}\n")
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
    """Regenerate the receipt of a language unit attempt's group from its raw results, host, and frozen plan."""
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
    group = work_units.unit_group(unit, session["group_id"])
    environment = read_properties(root / "environment-manifest.txt")
    check_host(environment, collection.load(directory / "source/manifest.json")["suite"])
    unit_session.check_worker_unit_session(session, environment)
    metadata = read_properties(root / "run-metadata.txt")
    if metadata["unit_id"] != unit["unit_id"] or metadata["heap_size"] != environment["benchmark_heap_size"]:
        raise ValueError("unit metadata differs from its unit or host")
    unit_session.route_cpu_ticks(root / "run-metadata.txt", metadata)
    affinity = collection.load(root / "cpu-affinity.json")
    if len(affinity) != 1 or type(affinity[0]) is not int or affinity[0] < 0:
        raise ValueError("language unit lacks single-CPU affinity evidence")
    partitions = unit_partitions(plan, group)
    if sorted(path.name for path in (root / "partitions").iterdir()) != sorted(partition["id"] for partition, _ in partitions):
        raise ValueError("language unit has missing or unexpected partitions")
    version = environment["regulator_release_version"]
    expected_release = released_artifact.manifest(collection.ROOT, version) if version else None
    # The classes archive the host installed from its platform's engine build, under the archive's pinned SHA-256.
    classes_digest = read_properties(root / "engine-build.txt").get("classes_archive_sha256")
    candidate = runners = None
    observed, exports = set(), []
    with tempfile.TemporaryDirectory() as work:
        for partition, operations in partitions:
            results = root / "partitions" / partition["id"]
            data = collection.export(slice_directory(directory, partition, operations, Path(work)), results,
                                     write=False, inputs=directory)
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
            if provenance["jvm_build"]["classes_archive_sha256"] != classes_digest:
                raise ValueError("language result ran classes other than its engine build's")
            release = provenance["jvm_build"].get("released_artifact")
            if expected_release is not None and (release is None or release["manifest"] != expected_release):
                raise ValueError("language partition lacks the selected released artifact")
            current = {key: provenance[key] for key in CANDIDATE_FIELDS}
            if candidate not in (None, current) or runners not in (None, data["runners"]):
                raise ValueError("language unit mixes candidates, comparator revisions, or binaries")
            candidate, runners = current, data["runners"]
            observed.update(data["observations"])
            exports.append((partition["id"], sha256_file(results / "language-results.json")))
    if observed != set(group["owned"]):
        raise ValueError("language unit observations differ from its group's rows")
    return {
        **session,
        "shard_id": unit["shard_id"],
        "owned_row_count": str(len(group["owned"])),
        "row_protocol_sha256": unit_digest(plan["partitions"], group["owned"]),
        "manifest_sha256": sha256_file(directory / "plan.json"),
        "observed_rows_sha256": exports_digest(exports),
        "evidence_manifest_sha256": sha256_file(root / "unit-evidence.tsv"),
        "candidate_commit": metadata["candidate_commit"],
        "candidate_archive_sha256": environment["regulator_archive_sha256"],
        "engine_tree": metadata["engine_tree"],
        "heap_size": metadata["heap_size"],
    }


def copy_inputs(manifest, source, destination):
    """Copy each input file the manifest's validation reads, once, to its path under destination: a bulk manifest
    checks every mapping against its source, including a not-compatible one, which reads the source itself."""
    paths = {mapping["input_file"] for case in manifest["cases"]
             for mapping in case["mappings"].values() if mapping["status"] != "not-compatible"}
    if manifest.get("suite") == "language-bulk":
        paths.update(case["source_file"] for case in manifest["cases"])
        paths.update(mapping["input_file"] for case in manifest["cases"] for mapping in case["mappings"].values())
    for name in paths:
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target)


def prepare(manifest_directory, destination, selection=None, duration_policy=None, shard_id=None):
    manifest_directory, destination = manifest_directory.resolve(), destination.resolve()
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
    if duration_policy is not None:
        validate_duration_policy(duration_policy, selected)
    destination.mkdir(parents=True, exist_ok=False)
    source = destination / "source"
    source.mkdir()
    shutil.copyfile(manifest_directory / "manifest.json", source / "manifest.json")
    # The plan's input store holds each input once, at its manifest path under the plan; the source and every
    # partition read their inputs from it.
    copy_inputs(manifest, manifest_directory, destination)
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
            collection.save(directory / "manifest.json", subset)
            partitions.append({"id": partition_id, "case": case["id"], "language": language,
                               "manifest_sha256": collection.digest((directory / "manifest.json").read_bytes())})
    plan = {"schema_version": 4, "shard_id": shard_id, "parent_manifest_sha256": parent_hash,
            "platforms": list(PLATFORMS), "platform_allocations": platform_allocations(manifest["suite"], PLATFORMS),
            "partitions": partitions}
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
    collection.validate_manifest(manifest, directory)
    if "selected_languages" in manifest or "parent_manifest_sha256" in manifest:
        raise ValueError("fleet source must be unpartitioned")
    parent_hash = collection.digest((source / "manifest.json").read_bytes())
    if (plan.get("schema_version") != 4 or plan["parent_manifest_sha256"] != parent_hash or
            not is_shard_id(plan.get("shard_id")) or
            plan["platforms"] != list(PLATFORMS) or
            plan.get("platform_allocations") != platform_allocations(manifest["suite"], PLATFORMS)):
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
    if found != expected:
        raise ValueError("incomplete fleet coverage")
    if "duration_policy" in plan:
        validate_duration_policy(plan["duration_policy"], expected)
    units = plan_units(manifest, plan["partitions"], plan["shard_id"], plan.get("duration_policy"))
    if tuple((directory / name).read_text() for name in ("units.tsv", "unit-rows.tsv")) != work_units.render(units):
        raise ValueError("work units differ from the plan's comparisons")
    return plan


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
                                     results / partition["id"], java, classpath, native_runners, inputs=directory)
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


def campaign_units(plans, phase):
    """The units a language campaign phase measures across its validated plans, each by its directory: the smoke
    units in smoke, every unit otherwise. Returns them by unit ID with each group's protocol digest by (unit, group),
    and each plan's allocation and plan digest by its shard."""
    campaign = {"units": {}, "digests": {}, "allocations": {}, "manifests": {}}
    for directory, plan in plans.items():
        if plan["shard_id"] in campaign["manifests"]:
            raise ValueError(f"language plans share shard {plan['shard_id']}")
        units = work_units.load(directory)
        selected = set(smoke_units(directory, plan, units)) if phase == "smoke" else None
        for unit_id, unit in units.items():
            if selected is None or unit_id in selected:
                campaign["units"][unit_id] = unit
                campaign["digests"].update(
                    ((unit_id, group["group_id"]), unit_digest(plan["partitions"], group["owned"]))
                    for group in unit["groups"])
        campaign["allocations"][plan["shard_id"]] = {
            platform: allocation["instance_type"] for platform, allocation in plan["platform_allocations"].items()}
        campaign["manifests"][plan["shard_id"]] = sha256_file(directory / "plan.json")
    return campaign


def validate_unit_campaign(plans, receipts, campaign_id, phase="primary", campaign=None):
    """Accept a language campaign phase's unit receipts as a whole, by the baseline campaign rule: every unit of
    the phase's validated plans on every platform for each replica, its replicas on distinct instances, and one
    candidate. campaign, by default the phase's campaign_units, may be restricted to the groups a partial export
    keeps."""
    campaign = campaign_units(plans, phase) if campaign is None else campaign
    unit_session.check_unit_campaign(
        receipts, load_platforms(unit_session.DIRECTORY / "platforms.tsv"), campaign["units"], campaign["digests"],
        campaign["allocations"], campaign["manifests"], campaign_id, (1,) if phase == "smoke" else (1, 2, 3))


def shard_receipts(plan, result_root):
    """The receipts a campaign result root accepted for the plan's shard."""
    return [receipt for receipt in unit_dispatch.read_accepted_units(result_root / "accepted-units.tsv")
            if receipt["shard_id"] == plan["shard_id"]]


def aggregate(directory, result_root, output):
    """Export a plan's accepted primary units from a campaign result root, each attempt revalidated from its
    retained evidence, without rewriting it. The phase must have rejected no attempt for its semantics, a case's
    traces must agree across its partitions, and every attempt must have measured one candidate."""
    directory, result_root, output = directory.resolve(), result_root.resolve(), output.resolve()
    plan = validate(directory)
    if output.exists():
        raise ValueError("will not overwrite an existing fleet export")
    receipts = shard_receipts(plan, result_root)
    validate_unit_campaign({directory: plan}, receipts, receipts[0]["campaign_id"] if receipts else "")
    unit_session.check_semantic_rejections(result_root)
    export(directory, plan, result_root, receipts, output, {})


def partial_missing(output):
    """The list of a partial export's missing groups, <shard>-partial-missing.tsv beside its
    <shard>-partial-results.json."""
    return output.with_name(output.name.removesuffix(PARTIAL_SUFFIX) + "-partial-missing.tsv")


def aggregate_partial(directory, result_root, output):
    """Export the comparisons a failed primary measured completely, as aggregate does: those whose every group was
    accepted on every platform at every replica, leaving out the accepted groups of every other comparison. The
    export's name ends in PARTIAL_SUFFIX and it records its coverage as partial with its missing groups, which the
    partial_missing list beside it holds too, so neither is taken for a complete export; a campaign missing nothing
    is refused. The missing list is written first, so a retry after it failed finds no export to refuse."""
    directory, result_root, output = directory.resolve(), result_root.resolve(), output.resolve()
    if not output.name.endswith(PARTIAL_SUFFIX):
        raise ValueError(f"a partial fleet export's name must end in {PARTIAL_SUFFIX}")
    if output.exists():
        raise ValueError("will not overwrite an existing fleet export")
    plan = validate(directory)
    receipts = shard_receipts(plan, result_root)
    unit_session.check_semantic_rejections(result_root)
    campaign = campaign_units({directory: plan}, "primary")
    accepted = {(receipt["platform"], receipt["unit_id"], receipt["replica_id"], receipt["group_id"])
                for receipt in receipts}
    groups, missing = {}, []
    for unit_id, unit in campaign["units"].items():
        for group in unit["groups"]:
            # A unit's groups are its comparisons, so one row names a group's comparison.
            case, language = row_atom(group["owned"][0])[:2]
            groups.setdefault((case, language), []).append((unit_id, group["group_id"]))
            missing += [dict(zip(MISSING_FIELDS, (case, language, platform, replica, unit_id, group["group_id"])))
                        for platform in plan["platforms"] for replica in ("1", "2", "3")
                        if (platform, unit_id, replica, group["group_id"]) not in accepted]
    if not missing:
        raise ValueError("the campaign accepted every comparison, so its export is complete, not partial")
    dropped = {(row["case_id"], row["language"]) for row in missing}
    kept = {group for comparison, owned in groups.items() if comparison not in dropped for group in owned}
    if not kept:
        raise ValueError("the campaign accepted no comparison completely")
    kept_units = {unit_id for unit_id, _ in kept}
    restricted = {**campaign, "units": {
        unit_id: {**unit, "groups": [group for group in unit["groups"] if (unit_id, group["group_id"]) in kept]}
        for unit_id, unit in campaign["units"].items() if unit_id in kept_units}}
    receipts = [receipt for receipt in receipts if (receipt["unit_id"], receipt["group_id"]) in kept]
    validate_unit_campaign({directory: plan}, receipts, receipts[0]["campaign_id"], campaign=restricted)
    missing.sort(key=lambda row: tuple(row.values()))
    write_tsv(partial_missing(output), MISSING_FIELDS, missing)
    export(directory, plan, result_root, receipts, output, {"coverage": "partial", "missing": missing})


def export(directory, plan, result_root, receipts, output, coverage):
    """Write the export of a plan's accepted receipts, once they passed the campaign and semantic checks, with
    coverage's fields."""
    observations, comparisons, hosts = [], [], []
    candidate = None
    # Each platform runs the classes of its own engine build.
    classes_digests = {}
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
            digest = provenance["jvm_build"]["classes_archive_sha256"]
            if candidate not in (None, identity) or classes_digests.setdefault(receipt["platform"], digest) != digest:
                raise ValueError("mixed candidates, candidate classes, or comparator versions")
            candidate = identity
            partitions.append({"id": results.name, "provenance": provenance,
                               "export_sha256": sha256_file(results / "language-results.json")})
            attempt_group = {"unit_attempt_id": receipt["unit_attempt_id"], "group_id": receipt["group_id"],
                             "partition": results.name}
            for row_id, row in data["observations"].items():
                observations.append({**row, **attempt_group, "id": row_id})
            for row in data["comparisons"]:
                comparisons.append({**row, **attempt_group})
        hosts.append({"receipt": receipt, "partitions": partitions})
    collection.save(output, {"schema_version": 2, "plan": plan, "candidate": candidate, **coverage,
                             "hosts": hosts, "observations": observations, "comparisons": comparisons})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--manifest-directory", type=Path, required=True)
    prepare_parser.add_argument("--output-directory", type=Path, required=True)
    prepare_parser.add_argument("--selection", type=Path, help="JSON array of [case ID, language] pairs")
    prepare_parser.add_argument("--duration-policy", type=Path, help="frozen partition duration estimates and isolated slow comparisons")
    prepare_parser.add_argument("--shard-id", help="the plan's shard of work units; defaults to the suite")
    bundle_parser = commands.add_parser("bundle", help="write a plan's bundle for unit workers and print its SHA-256")
    bundle_parser.add_argument("--plan-directory", type=Path, required=True)
    bundle_parser.add_argument("--output", type=Path, required=True)
    unit_parser = commands.add_parser("run-unit", help="measure one work unit on this unit worker")
    unit_parser.add_argument("--plan-directory", type=Path, required=True)
    unit_parser.add_argument("--unit", required=True)
    unit_parser.add_argument("--replica", required=True)
    unit_parser.add_argument("--group", required=True)
    unit_parser.add_argument("--unit-directory", type=Path, required=True)
    unit_parser.add_argument("--shared", type=Path, required=True,
                             help="the shared work directory where the engine build is installed")
    unit_parser.add_argument("--unit-attempt", required=True)
    unit_parser.add_argument("--region", required=True)
    unit_parser.add_argument("--source-archive", type=Path, required=True)
    unit_parser.add_argument("--candidate-provenance", type=Path, required=True)
    for name in ("validate", "verify", "aggregate", "aggregate-partial"):
        command = commands.add_parser(name)
        command.add_argument("--plan-directory", type=Path, required=True)
        if name != "validate":
            command.add_argument("--results", type=Path, required=True)
        if name.startswith("aggregate"):
            command.add_argument("--output", type=Path, required=True)
        if name == "verify":
            command.add_argument("--java", default="java")
            command.add_argument("--classpath", required=True)
            collection.add_native_runner_arguments(command, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        selection = collection.load(args.selection) if args.selection else None
        prepare(args.manifest_directory, args.output_directory, selection,
                collection.load(args.duration_policy) if args.duration_policy else None, shard_id=args.shard_id)
        print(work_units.summary([{**unit, "estimated_seconds": float(unit["estimated_seconds"])}
                                  for unit in work_units.load(args.output_directory).values()]))
    elif args.command == "bundle":
        print(write_bundle(args.plan_directory, args.output))
    elif args.command == "run-unit":
        try:
            run_unit(args.plan_directory, args.unit, args.replica, args.group, args.unit_directory, args.shared,
                     args.unit_attempt, args.region, archive=args.source_archive, provenance=args.candidate_provenance)
        except UnitRejection as rejection:
            print(f"unit {args.unit} rejected: {rejection}", file=sys.stderr)
            sys.exit(rejection.status)
    elif args.command == "validate":
        validate(args.plan_directory)
        print(f"Validated {len(work_units.load(args.plan_directory))} work units")
    elif args.command == "verify":
        verify(args.plan_directory, args.results, args.java, args.classpath, collection.native_runner_arguments(args))
    elif args.command == "aggregate":
        aggregate(args.plan_directory, args.results, args.output)
    else:
        # A campaign root's manifest, written when it ends, lists every file it holds, so the export goes elsewhere.
        if args.output.resolve().is_relative_to(args.results.resolve()):
            parser.error("a partial export is written outside the campaign result root")
        aggregate_partial(args.plan_directory, args.results, args.output)


if __name__ == "__main__":
    main()
