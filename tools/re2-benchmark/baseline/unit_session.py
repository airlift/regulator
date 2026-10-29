#!/usr/bin/env python3
"""One work unit's route plan, exact JMH selectors, combined rows, and acceptance.

run-unit.sh plans one measured group of a unit into the routes its rows need and
runs run-shard.sh once per route. Each route measures only its share of the
group: run-shard.sh reads the group's expected rows, and its JMH runners execute
the selectors planned here instead of a handler's whole-method invocation. The
combined group is accepted on its own, by validate-unit-results.py.
"""
import argparse
import csv
import hashlib
import json
import re
import shutil
from pathlib import Path
from typing import NoReturn

import row_protocol
import work_units
from acceptance import (
    EVIDENCE_FIELDS,
    GIT_OBJECT_ID,
    SESSION_FIELDS,
    SHA256,
    UNIT_ROUTE_REQUIRED_ARTIFACTS,
    ValidationError,
    campaign_shards,
    canonical_systems,
    capacity_prefix_sha256,
    duplicate_values,
    evidence_candidate_identity,
    format_values,
    load_platforms,
    load_rows_manifest,
    load_shards,
    normalize_heap_size,
    parse_confirmation_replica,
    parse_primary_replica,
    parse_systems,
    read_one_tsv,
    read_properties,
    read_tsv,
    sha256_file,
    verify_evidence_manifest,
    verify_observed_rows_checksum,
)
from shard_results import (
    MANIFEST_FIELDS,
    OBSERVED_FIELDS,
    combined_rows,
    exact_filter,
    load_manifest,
    load_observed,
    write_observed_rows,
    write_tsv,
)


DIRECTORY = Path(__file__).resolve().parent
ROUTE_PLAN_FIELDS = ("route", "handler", "systems")
SELECTOR_FIELDS = (
    "selector",
    "system",
    "execution",
    "kind",
    "processes",
    "warmup_iterations",
    "warmup_time",
    "measurement_iterations",
    "measurement_time",
    "benchmarks",
    "parameters",
    "rows",
)
SELECTION_FIELDS = SELECTOR_FIELDS + ("filter",)
# How run-shard.sh launches a selector of each frozen execution; JMH processes are the default.
EXECUTION_KINDS = {"jmh-forks": "forks", "native-repetitions": "native"}
# A selector whose values JMH cannot express runs a whole method with its handler's arguments.
ROUTE_ARGUMENTS = "route"
SMOKE_SETTINGS = ("processes", "warmup_iterations", "measurement_iterations", "time")
UNIT_SCHEMA_VERSION = "2"
UNIT_SESSION_FIELDS = (
    "schema_version",
    "campaign_id",
    "platform",
    "unit_id",
    "replica_id",
    "group_id",
    "unit_attempt_id",
    "instance_id",
    "host_epoch",
    "architecture",
    "instance_type",
    "availability_zone",
    "region",
)
UNIT_RECEIPT_FIELDS = UNIT_SESSION_FIELDS + (
    "shard_id",
    "owned_row_count",
    "row_protocol_sha256",
    "manifest_sha256",
    "observed_rows_sha256",
    "evidence_manifest_sha256",
    "candidate_commit",
    "candidate_archive_sha256",
    "engine_tree",
    "heap_size",
)
# A host session leases units in sequence; each lease is one attempt, which measures one or more groups.
UNIT_ATTEMPT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]*/[1-9][0-9]*")
GROUP_ID = re.compile(r"[1-9][0-9]*")
# Identity a unit's routes share with the host that measured them.
ROUTE_IDENTITY_FIELDS = (
    "campaign_id", "platform", "replica_id", "instance_id", "host_epoch", "architecture", "instance_type",
    "availability_zone")
# Each field of a unit worker's session that its host records, by its environment manifest key.
WORKER_SESSION_FIELDS = (
    ("campaign_id", "baseline_campaign"), ("platform", "platform"), ("instance_id", "instance_id"),
    ("host_epoch", "host_epoch"), ("architecture", "campaign_architecture"), ("instance_type", "instance_type"),
    ("availability_zone", "availability_zone"))
UNIT_HOST_ARTIFACTS = (
    "environment-manifest.txt",
    "environment.txt",
    "engine-build.txt",
    "route-plan.tsv",
    "unit-rows.tsv",
    "expected-rows.tsv",
    "observed-rows.tsv",
    "observed-rows.tsv.sha256",
    "unit-session.tsv",
)
# The files naming the host, JVM, and engines a unit ran with.
UNIT_ENVIRONMENT_FILES = ("environment-manifest.txt", "environment.txt", "engine-build.txt")
# Each unit attempt a phase's correctness stage rejected, beside its accepted-units.tsv. The controller writes
# its header when the phase starts, so a unit campaign phase without it is incomplete.
SEMANTIC_REJECTIONS = "semantic-rejections.tsv"
SEMANTIC_REJECTION_FIELDS = ("platform", "unit_id", "replica_id", "unit_attempt_id")
# A unit's exit status when any check of its correctness stage fails, which no retry clears, and when the
# numerical protocol rejects its measurements. run-shard.sh and run-unit.sh exit with the same values.
SEMANTIC_FAILURE_STATUS = 44
PROTOCOL_REJECTION_STATUS = 42


DISPATCH_FIELDS = (
    "shard_id",
    "native_access_handler",
    "native_access_systems",
    "object_row_handler",
    "object_row_systems",
    "semantic_tests",
    "blocker",
)
ROUTES = (
    ("native-access", "native_access_handler", "native_access_systems"),
    ("object-row", "object_row_handler", "object_row_systems"),
)


def load_dispatch(path, shard):
    with path.open(newline="", encoding="utf-8") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != DISPATCH_FIELDS:
            raise ValueError(f"Unexpected dispatch header in {path}")
        rows = [row for row in reader if row["shard_id"] == shard]
    if len(rows) != 1:
        raise ValueError(f"Expected one dispatch row for shard {shard!r}, found {len(rows)}")
    return rows[0]


def applicable_routes(dispatch):
    routes = []
    for route, handler_field, systems_field in ROUTES:
        handler = dispatch[handler_field]
        systems = dispatch[systems_field]
        if handler == "blocked":
            raise ValueError(
                f"Shard {dispatch['shard_id']!r} route {route!r} is blocked: {dispatch['blocker']}")
        if handler == "-" or systems == "-":
            if handler != systems:
                raise ValueError(f"Shard {dispatch['shard_id']!r} has an incomplete {route!r} route")
            continue
        parsed_systems = parse_systems(systems, f"{dispatch['shard_id']} {route}")
        routes.append((route, handler, parsed_systems))
    if not routes:
        raise ValueError(f"Shard {dispatch['shard_id']!r} has no applicable routes")
    return routes


def require_complete_route(route_directory):
    status_path = route_directory / "status.txt"
    if not status_path.is_file() or status_path.read_text(encoding="utf-8") != "status=complete\n":
        raise ValueError(f"Route did not complete successfully: {route_directory}")


def route_evidence(route_directory):
    required = set(UNIT_ROUTE_REQUIRED_ARTIFACTS)
    metadata = read_properties(route_directory / "run-metadata.txt")
    qualification_required = metadata.get("protocol_qualification_required")
    if qualification_required not in {"true", "false"}:
        raise ValueError(f"Invalid protocol_qualification_required in {route_directory / 'run-metadata.txt'}")
    if qualification_required == "true":
        required.add("protocol-qualification.tsv")
    missing = sorted(
        artifact for artifact in required
        if not (route_directory / artifact).is_file() or (route_directory / artifact).stat().st_size == 0)
    if missing:
        raise ValueError(f"Required route evidence is missing or empty in {route_directory}: {missing}")

    artifacts = []
    for path in route_directory.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(route_directory)
        if relative.parts[0] in {"raw", "logs"}:
            continue
        artifacts.append((relative.as_posix(), path))
    return sorted(artifacts)


def load_unit(directory, unit_id):
    units = work_units.load(directory)
    if unit_id not in units:
        raise ValueError(f"Unknown work unit {unit_id!r}")
    return units[unit_id]


def unit_rows(directory, unit):
    held = set(unit["owned"])
    rows = [row for row in load_manifest(directory / "rows.tsv") if row["row_id"] in held]
    if len(rows) != len(held):
        raise ValueError(f"Unit {unit['unit_id']} holds rows outside the manifest")
    return rows


def route_shares(rows, routes):
    """Each route's share of a unit's rows, as (route, handler, systems, rows) in dispatch order."""
    shares = []
    for route, handler, systems in routes:
        share = [row for row in rows if row["system"] in systems]
        if share:
            shares.append((route, handler, set(systems), share))
    assigned = sorted(row["row_id"] for _, _, _, share in shares for row in share)
    if assigned != sorted(row["row_id"] for row in rows):
        raise ValueError("Unit rows are not each assigned to exactly one route")
    result = []
    for route, handler, systems, share in shares:
        present = {row["system"] for row in share}
        # A route handler measures every system of its route.
        if present != systems:
            raise ValueError(
                f"Unit route {route} holds systems {sorted(present)}, not those of its handler: {sorted(systems)}")
        result.append((route, handler, frozenset(present), share))
    return result


def ordered_shares(shares, replica_id):
    """Odd replicas run native access first; even replicas reverse it."""
    return list(reversed(shares)) if int(replica_id) % 2 == 0 else list(shares)


def protocol_replica(replica_id):
    """Replica 4 confirms replica 1, so it runs replica 1's representatives."""
    return "1" if str(replica_id) == "4" else str(replica_id)


def unit_representatives(unit, replica_id):
    target = protocol_replica(replica_id)
    return sorted(row_id for replica, row_id in unit["representatives"] if replica == target)


def plan_routes(directory, unit, replica_id):
    dispatch = load_dispatch(directory / "shard-dispatch.tsv", unit["shard_id"])
    return ordered_shares(route_shares(unit_rows(directory, unit), applicable_routes(dispatch)), replica_id)


def measured_group(unit, group_id):
    """One measured group of a unit, as a unit holding only the group's rows and representatives."""
    group = work_units.unit_group(unit, group_id)
    return {**unit, "group_id": group["group_id"], "owned": group["owned"],
            "representatives": group["representatives"]}


def plan(arguments):
    unit = load_unit(DIRECTORY, arguments.unit)
    if arguments.replica not in {"1", "2", "3", "4"}:
        raise ValueError(f"Replica must be 1, 2, 3, or 4: {arguments.replica}")
    group = measured_group(unit, arguments.group)
    output = arguments.output_directory
    write_tsv(output / "expected-rows.tsv", MANIFEST_FIELDS, unit_rows(DIRECTORY, group))
    write_tsv(output / "unit-rows.tsv", work_units.UNIT_ROW_FIELDS, [
        row for row in row_protocol.read_tsv(DIRECTORY / "unit-rows.tsv") if row["unit_id"] == unit["unit_id"]])
    write_tsv(output / "route-plan.tsv", ROUTE_PLAN_FIELDS, [
        {"route": route, "handler": handler, "systems": canonical_systems(systems)}
        for route, handler, systems, _ in plan_routes(DIRECTORY, group, arguments.replica)])
    print(unit["shard_id"])


def execution_settings(protocol, protocol_name, smoke):
    """How a selector runs: qualification keeps its frozen protocol; smoke keeps the route's smoke windows."""
    kind = EXECUTION_KINDS.get(protocol["execution"], "processes")
    if protocol_name == "qualification":
        if kind == "forks" and protocol["warmup_time"] != protocol["measurement_time"]:
            raise ValueError(f"Forked protocol {protocol['row_id']} has distinct warmup and measurement windows")
        return {
            "kind": kind,
            **{field: protocol[field] for field in (
                "processes", "warmup_iterations", "warmup_time", "measurement_iterations", "measurement_time")},
        }
    # Smoke forks only a forked comparator, as many times as its frozen protocol,
    # runs a native comparator in its one process, and runs every candidate row in
    # the route's smoke processes.
    return {
        "kind": kind,
        "processes": smoke["processes"] if kind == "processes" else protocol["processes"],
        "warmup_iterations": smoke["warmup_iterations"],
        "warmup_time": smoke["time"],
        "measurement_iterations": smoke["measurement_iterations"],
        "measurement_time": smoke["time"],
    }


def selectors(rows, shard_rows, protocols, protocol_name, smoke):
    """A unit route's rows as exact JMH selectors, in row order.

    A selector is one system and frozen protocol over the methods that share one
    Cartesian -p block, so it measures exactly its rows and nothing else. A row
    whose suite runs each workload in its own JMH invocation is its own selector.
    """
    whole = {}
    for row in shard_rows:
        row_key = work_units.launch_key(row, protocols)
        whole[row_key] = whole.get(row_key, 0) + 1
    result = []
    for index, (launch, methods) in enumerate(work_units.launches(rows, protocols), 1):
        system, _, parameters, _, _ = launch
        for key, method_rows in methods:
            if parameters is None and len(method_rows) != whole[key]:
                raise ValueError(f"{system} {key[1]} has a value JMH cannot select and is split")
        protocol = protocols[methods[0][1][0]["row_id"]]
        result.append({
            "selector": str(index),
            "system": system,
            "execution": protocol["execution"],
            **execution_settings(protocol, protocol_name, smoke),
            "benchmarks": ",".join(key[1] for key, _ in methods),
            "parameters": ROUTE_ARGUMENTS if parameters is None else parameters,
            "rows": str(sum(len(method_rows) for _, method_rows in methods)),
        })
    return result


def plan_selectors(arguments):
    """Write the selectors of the route share in an expected-rows file."""
    rows = load_manifest(arguments.expected)
    shard_rows = [row for row in load_manifest(arguments.manifest) if row["shard_id"] == arguments.shard]
    smoke = dict(zip(SMOKE_SETTINGS, arguments.smoke))
    write_tsv(arguments.output, SELECTOR_FIELDS, selectors(
        rows, shard_rows, row_protocol.load(arguments.row_protocol), arguments.protocol, smoke))


def block_arguments(parameters):
    """The -p values of a selector, one name=values per line."""
    return "".join(f"{name}={','.join(values)}\n" for name, values in json.loads(parameters).items())


def holds_block(selector, parameters):
    """Whether a handler call's -p values hold every value of a selector's explicit block."""
    if selector["parameters"] == ROUTE_ARGUMENTS:
        return True
    block = json.loads(selector["parameters"])
    return all(set(block[name]) <= set(values) for name, values in parameters.items() if name in block)


def select(arguments):
    """Write the planned selectors a handler's JMH filter selects, and each one's -p values, beside its output.

    A handler call may select some methods of a selector; every method of an
    explicit block holds the same rows, so the call runs the block over those.
    A call's own -p values, such as one workload of a per-workload loop, keep
    only the selectors whose blocks they hold.
    """
    planned = row_protocol.read_tsv(arguments.selectors)
    pattern = re.compile(arguments.filter)
    parameters = {}
    for parameter in arguments.parameter:
        name, values = parameter.split("=", 1)
        parameters[name] = values.split(",")
    chosen = []
    for selector in planned:
        if not holds_block(selector, parameters):
            continue
        benchmarks = selector["benchmarks"].split(",")
        selected = [benchmark for benchmark in benchmarks if pattern.search(benchmark)]
        if not selected:
            continue
        if len(selected) != len(benchmarks):
            selector = {
                **selector,
                "benchmarks": ",".join(selected),
                "rows": str(int(selector["rows"]) // len(benchmarks) * len(selected)),
            }
        chosen.append(selector)
    if not chosen:
        raise ValueError(f"JMH filter {arguments.filter!r} selects no planned selector")
    output_base = arguments.output_base
    for selector in chosen:
        selector["filter"] = exact_filter(selector["benchmarks"].split(","))
        if selector["parameters"] != ROUTE_ARGUMENTS:
            Path(f"{output_base}-selector-{selector['selector']}-parameters.txt").write_text(
                block_arguments(selector["parameters"]), encoding="utf-8")
    write_tsv(Path(f"{output_base}-selectors.tsv"), SELECTION_FIELDS, chosen)


def read_route_plan(root):
    return row_protocol.read_tsv(root / "route-plan.tsv")


def combine(arguments):
    """Combine a measured group's routes into its observed rows."""
    root = arguments.unit_directory
    route_plan = read_route_plan(root)
    observed = []
    sessions = []
    for route in route_plan:
        route_directory = root / "routes" / route["route"]
        require_complete_route(route_directory)
        session = read_one_tsv(route_directory / "route-session.tsv", SESSION_FIELDS)
        if parse_systems(session["systems"], str(route_directory)) != parse_systems(route["systems"], "route plan"):
            raise ValueError(f"Route {route['route']} session systems differ from the unit route plan")
        sessions.append(session)
        observed.extend(load_observed(route_directory / "observed-rows.tsv"))
    identity_fields = [field for field in SESSION_FIELDS if field != "systems"]
    for session in sessions[1:]:
        changed = [field for field in identity_fields if session[field] != sessions[0][field]]
        if changed:
            raise ValueError(f"Unit route sessions changed host identity fields: {changed}")
    rows = combined_rows(load_manifest(root / "expected-rows.tsv"), observed)
    mapping = row_protocol.read_tsv(root / "unit-rows.tsv")
    write_observed_rows(root / "observed-rows.tsv", OBSERVED_FIELDS, rows)
    unit_id = mapping[0]["unit_id"]
    write_tsv(root / "unit-session.tsv", UNIT_SESSION_FIELDS, [{
        "schema_version": UNIT_SCHEMA_VERSION,
        **{field: sessions[0][field] for field in ROUTE_IDENTITY_FIELDS},
        "unit_id": unit_id,
        "group_id": arguments.group,
        "unit_attempt_id": arguments.unit_attempt,
        "region": arguments.region,
    }])
    write_unit_evidence(root, route_plan)
    print(f"Combined {len(rows)} unit rows")


def host_evidence_rows(root, paths):
    """A unit attempt's host evidence: each of paths under root that is a file, then its capacity gate's prefix."""
    rows = [{"route": "host", "artifact": path.relative_to(root).as_posix(), "verification": "sha256",
             "sha256": sha256_file(path)} for path in paths if path.is_file()]
    if (root / "unit-capacity.txt").is_file():
        rows.append({"route": "host", "artifact": "unit-capacity.txt", "verification": "capacity-prefix-sha256",
                     "sha256": capacity_prefix_sha256(root / "unit-capacity.txt")})
    return rows


def write_unit_evidence(root, route_plan):
    """Bind each route's artifacts and the unit's own, in the host-session evidence shape."""
    evidence = []
    for route in route_plan:
        for artifact, path in route_evidence(root / "routes" / route["route"]):
            evidence.append({
                "route": route["route"],
                "artifact": f"routes/{route['route']}/{artifact}",
                "verification": "sha256",
                "sha256": sha256_file(path),
            })
    evidence.extend(host_evidence_rows(root, [root / artifact for artifact in UNIT_HOST_ARTIFACTS]))
    write_tsv(root / "unit-evidence.tsv", EVIDENCE_FIELDS, sorted(
        evidence, key=lambda row: (row["route"], row["artifact"])))
    verify_unit_evidence(root)


def verify_unit_evidence(root):
    rows = verify_evidence_manifest(root / "unit-evidence.tsv", root)
    return evidence_candidate_identity(rows, root)


def reject(message) -> NoReturn:
    raise ValidationError(message)


def check_unit_identity(root, session, units, protocols):
    """The unit recomputes from its rows, protocols, and representatives, and is a frozen unit."""
    unit_rows_file = read_tsv(root / "unit-rows.tsv", work_units.UNIT_ROW_FIELDS, work_units.UNIT_ROW_FIELDS)
    if {row["unit_id"] for row in unit_rows_file} != {session["unit_id"]}:
        reject(f"{root / 'unit-rows.tsv'} does not hold only unit {session['unit_id']}")
    unit = units.get(session["unit_id"])
    if unit is None:
        reject(f"unit {session['unit_id']} is not a frozen work unit")
    # The attempt's mapping is the frozen one, row for row, so no row of another role slips past.
    frozen = [row for row in row_protocol.read_tsv(DIRECTORY / "unit-rows.tsv")
              if row["unit_id"] == session["unit_id"]]
    if unit_rows_file != frozen:
        reject(f"{root / 'unit-rows.tsv'} differs from the frozen rows of unit {session['unit_id']}")
    owned = sorted(unit["owned"])
    representatives = sorted(unit["representatives"])
    identity = work_units.unit_id(
        unit["shard_id"], owned, [f"{replica}:{row_id}" for replica, row_id in representatives],
        work_units.protocol_digest(owned, protocols))
    if identity != session["unit_id"]:
        reject(f"unit {session['unit_id']} recomputes as {identity} under the frozen row protocols")
    return unit


def check_unit_rows(root, group, manifest):
    """Expected and observed rows are exactly the measured group's rows."""
    held = set(group["owned"])
    by_id = {row["row_id"]: row for row in manifest}
    expected = [row["row_id"] for row in load_manifest(root / "expected-rows.tsv")]
    if sorted(expected) != sorted(held):
        reject(f"{root / 'expected-rows.tsv'} does not list exactly the rows of group {group['group_id']}")
    observed_path = root / "observed-rows.tsv"
    observed = read_tsv(observed_path, ("row_id", "shard_id", "system"), OBSERVED_FIELDS)
    identifiers = [row["row_id"] for row in observed]
    problems = []
    duplicates = duplicate_values(identifiers)
    missing = sorted(set(held) - set(identifiers))
    unexpected = sorted(set(identifiers) - set(held))
    if missing:
        problems.append(f"missing rows={format_values(missing)}")
    if duplicates:
        problems.append(f"duplicate rows={format_values(duplicates)}")
    if unexpected:
        problems.append(f"unexpected rows={format_values(unexpected)}")
    for line_number, row in enumerate(observed, 2):
        if row["row_id"] not in held:
            continue
        manifest_row = by_id[row["row_id"]]
        if (row["shard_id"], row["system"]) != (manifest_row["shard_id"], manifest_row["system"]):
            problems.append(f"{observed_path}:{line_number} does not match the manifest shard and system")
    if problems:
        reject("unit observed-row acceptance failed: " + "; ".join(problems))
    verify_observed_rows_checksum(root / "observed-rows.tsv.sha256")


def check_unit_routes(root, group, session, protocols, manifest):
    """The group's routes ran in the unit's order on the unit session's host, and each recorded its CPU steal."""
    recorded = read_tsv(root / "route-plan.tsv", ROUTE_PLAN_FIELDS, ROUTE_PLAN_FIELDS)
    routes = plan_routes(DIRECTORY, group, session["replica_id"])
    shares = {route: share for route, _, _, share in routes}
    planned = [
        {"route": route, "handler": handler, "systems": canonical_systems(systems)}
        for route, handler, systems, _ in routes]
    if recorded != planned:
        reject(f"{root / 'route-plan.tsv'} is not the plan of group {group['group_id']} for replica "
               f"{session['replica_id']}")
    shard_rows = [row for row in manifest if row["shard_id"] == group["shard_id"]]
    for route in planned:
        directory = root / "routes" / route["route"]
        route_session = read_one_tsv(directory / "route-session.tsv", SESSION_FIELDS)
        changed = [field for field in ROUTE_IDENTITY_FIELDS if route_session[field] != session[field]]
        changed += [field for field, value in (("shard_id", group["shard_id"]), ("systems", route["systems"]))
                    if route_session[field] != value]
        if changed:
            reject(f"{directory / 'route-session.tsv'} differs from the unit session and route plan: {changed}")
        metadata = read_properties(directory / "run-metadata.txt")
        if metadata.get("unit_id") != group["unit_id"]:
            reject(f"{directory / 'run-metadata.txt'} ran unit {metadata.get('unit_id')}, expected unit "
                   f"{group['unit_id']}")
        route_cpu_ticks(directory / "run-metadata.txt", metadata)
        check_semantic_receipt(directory, metadata)
        check_route_selectors(directory, metadata, shares[route["route"]], shard_rows, protocols)
    return planned


def route_cpu_ticks(path, metadata):
    """The route's CPU steal and total ticks from /proc/stat, read before and after it measured."""
    steal = metadata.get("cpu_steal_ticks", "")
    total = metadata.get("cpu_total_ticks", "")
    if not steal.isdigit() or not total.isdigit() or int(total) == 0 or int(steal) > int(total):
        reject(f"{path} records CPU steal {steal!r} of {total!r} ticks, expected 0 <= steal <= total and total > 0")
    return int(steal), int(total)


def unit_cpu_ticks(root):
    """The unit's CPU steal and total ticks, summed over its routes."""
    steal = total = 0
    for route in read_route_plan(root):
        path = root / "routes" / route["route"] / "run-metadata.txt"
        route_steal, route_total = route_cpu_ticks(path, read_properties(path))
        steal += route_steal
        total += route_total
    return steal, total


def check_route_selectors(directory, metadata, share, shard_rows, protocols):
    """The route ran its JMH selectors at the frozen protocol of their rows, with no window override."""
    path = directory / "run-metadata.txt"
    if metadata.get("protocol") not in {"smoke", "qualification"}:
        reject(f"{path} records unit protocol {metadata.get('protocol')!r}, expected smoke or qualification")
    try:
        smoke = {setting: metadata[name] for setting, name in zip(SMOKE_SETTINGS, (
            "jmh_process_count", "jmh_process_warmup_iterations", "jmh_process_measurement_iterations",
            "jmh_iteration_time"))}
        expected = selectors(share, shard_rows, protocols, metadata["protocol"], smoke)
    except (KeyError, ValueError) as error:
        reject(f"{path} does not plan the route's selectors: {error}")

    def settings(rows):
        return sorted((tuple((field, row[field]) for field in SELECTOR_FIELDS if field != "selector")
                       for row in rows))

    recorded = read_tsv(directory / "jmh-selectors.tsv", SELECTOR_FIELDS, SELECTOR_FIELDS)
    if settings(recorded) != settings(expected):
        reject(f"{directory / 'jmh-selectors.tsv'} differs from the selectors of the frozen protocol")


def check_semantic_receipt(directory, metadata):
    """A cached semantic test receipt is used only when the route's reports are the receipt's files."""
    if metadata.get("semantic_receipt") not in {"cached", "measured"}:
        reject(f"{directory / 'run-metadata.txt'} records semantic receipt {metadata.get('semantic_receipt')!r}, "
               "expected cached or measured")
    receipt_path = directory / "semantic-receipt.sha256"
    if not receipt_path.is_file() or sha256_file(receipt_path) != metadata.get("semantic_receipt_sha256"):
        reject(f"{receipt_path} does not match the route's recorded semantic receipt")
    listed = {}
    for line in receipt_path.read_text(encoding="utf-8").splitlines():
        digest, _, artifact = line.partition("  ")
        if SHA256.fullmatch(digest) is None or not artifact:
            reject(f"{receipt_path} has an invalid line {line!r}")
        listed[artifact] = digest
    reports = {
        f"reports/{path.name}": sha256_file(path) for path in (directory / "semantic-reports").glob("TEST-*.xml")}
    if not reports or {key: value for key, value in listed.items() if key.startswith("reports/")} != reports \
            or set(listed) - set(reports) != {"launcher.log"}:
        reject(f"{directory / 'semantic-reports'} are not the files of its semantic receipt")


def environment_sha256(root):
    """One digest of a unit's environment files, as its worker records it for each lease."""
    return hashlib.sha256("".join(
        f"{name}  {sha256_file(root / name)}\n" for name in UNIT_ENVIRONMENT_FILES
        if (root / name).is_file()).encode()).hexdigest()


def print_environment_sha256(arguments):
    print(environment_sha256(arguments.directory))


def copy_environment(arguments):
    """Copy the host's environment files into a unit or route directory, as environment_sha256 reads them."""
    for name in UNIT_ENVIRONMENT_FILES:
        if (arguments.source / name).is_file():
            shutil.copy(arguments.source / name, arguments.target / name)


def check_unit_representatives(root, group, session, planned):
    """The group's representatives for this replica ran, each on its own route, and passed."""
    ran = []
    for route in planned:
        path = root / "routes" / route["route"] / "protocol-qualification.tsv"
        if path.is_file():
            ran.extend(row["manifest_row_id"] for row in read_tsv(path, ("manifest_row_id",)))
    expected = unit_representatives(group, session["replica_id"])
    if sorted(ran) != expected:
        reject(f"unit {group['unit_id']} ran representatives {format_values(sorted(ran))}, expected "
               f"{format_values(expected)}")


def load_unit_tables(manifest_path=DIRECTORY / "rows.tsv", protocol_path=DIRECTORY / "row-protocol.tsv"):
    """The manifest, frozen protocols, and work units a unit attempt is validated against.

    A pass over several attempts loads them once and passes them to each.
    """
    return {
        "manifest": load_manifest(manifest_path),
        "manifest_sha256": sha256_file(manifest_path),
        "protocols": row_protocol.load(protocol_path),
        "units": work_units.load(DIRECTORY),
    }


def write_worker_unit_session(root, environment, unit_id, replica, group, unit_attempt, region):
    """Write the session of a unit's group a worker ran on the host its environment manifest describes."""
    write_tsv(root / "unit-session.tsv", UNIT_SESSION_FIELDS, [{
        "schema_version": UNIT_SCHEMA_VERSION, "unit_id": unit_id, "replica_id": replica, "group_id": group,
        "unit_attempt_id": unit_attempt, "region": region,
        **{field: environment[key] for field, key in WORKER_SESSION_FIELDS}}])


def check_worker_unit_session(session, environment):
    """A worker's unit session names the host its environment manifest describes."""
    differing = [field for field, key in WORKER_SESSION_FIELDS if session[field] != environment[key]]
    if differing:
        raise ValueError(f"unit session differs from its host in {differing}")


def read_unit_session(root):
    """A unit attempt's session: the host, replica, lease, and group that measured it."""
    session = read_one_tsv(root / "unit-session.tsv", UNIT_SESSION_FIELDS)
    if session["schema_version"] != UNIT_SCHEMA_VERSION:
        reject(f"{root / 'unit-session.tsv'} schema_version must be {UNIT_SCHEMA_VERSION}")
    if session["replica_id"] not in {"1", "2", "3", "4"}:
        reject(f"{root / 'unit-session.tsv'} replica_id must be 1 to 4")
    if GROUP_ID.fullmatch(session["group_id"]) is None:
        reject(f"{root / 'unit-session.tsv'} group_id must be a positive integer")
    if UNIT_ATTEMPT.fullmatch(session["unit_attempt_id"]) is None:
        reject(f"{root / 'unit-session.tsv'} unit_attempt_id must be <host_session>/<lease_seq>")
    if not session["availability_zone"].startswith(session["region"]):
        reject(f"{root / 'unit-session.tsv'} availability zone is outside region {session['region']}")
    return session


def validate_unit_results(root, manifest_path=DIRECTORY / "rows.tsv", protocol_path=DIRECTORY / "row-protocol.tsv",
                          tables=None):
    """Accept one measured group of a unit attempt on its own and return its receipt.

    tables, from load_unit_tables, replaces loading the manifest and protocol paths.
    """
    session = read_unit_session(root)
    tables = tables or load_unit_tables(manifest_path, protocol_path)
    protocols = tables["protocols"]
    manifest = tables["manifest"]
    unit = check_unit_identity(root, session, tables["units"], protocols)
    try:
        group = measured_group(unit, session["group_id"])
    except ValueError as error:
        reject(str(error))
    check_unit_rows(root, group, manifest)
    planned = check_unit_routes(root, group, session, protocols, manifest)
    candidate_commit, candidate_archive_sha256, engine_tree, heap_size = verify_unit_evidence(root)
    check_unit_representatives(root, group, session, planned)
    return {
        **session,
        "shard_id": unit["shard_id"],
        "owned_row_count": str(len(group["owned"])),
        "row_protocol_sha256": work_units.protocol_digest(group["owned"], protocols),
        "manifest_sha256": tables["manifest_sha256"],
        "observed_rows_sha256": sha256_file(root / "observed-rows.tsv"),
        "evidence_manifest_sha256": sha256_file(root / "unit-evidence.tsv"),
        "candidate_commit": candidate_commit,
        "candidate_archive_sha256": candidate_archive_sha256,
        "engine_tree": engine_tree,
        "heap_size": heap_size,
    }


def write_unit_receipt(path, receipt):
    write_tsv(path, UNIT_RECEIPT_FIELDS, [receipt])


def format_unit_attempt(host_session_id, sequence):
    """A unit attempt's ID: the host session that leased the unit and the lease's sequence there."""
    return f"{host_session_id}/{sequence}"


def split_unit_attempt(unit_attempt_id):
    """The host session and lease sequence of a unit attempt's ID; the sequence stays text."""
    host_session_id, _, sequence = unit_attempt_id.rpartition("/")
    return host_session_id, sequence


def check_semantic_rejections(result_root):
    """A unit campaign phase is accepted only when its semantic-rejection ledger exists and lists no attempt.

    A semantic rejection is final: no attempt of the unit accepted before or after it clears it.
    """
    path = result_root / SEMANTIC_REJECTIONS
    if not path.is_file():
        reject(f"unit campaign phase has no semantic-rejection ledger: {path}")
    rows = read_tsv(path, SEMANTIC_REJECTION_FIELDS, SEMANTIC_REJECTION_FIELDS)
    if rows:
        reject(f"unit campaign phase rejected unit attempts for a semantic failure: "
               f"{format_values(row['unit_attempt_id'] for row in rows)}")


def protocol_digests(units, protocols):
    """Each baseline unit group's digest of its rows' frozen protocols, by (unit, group)."""
    return {(unit_id, group["group_id"]): work_units.protocol_digest(group["owned"], protocols)
            for unit_id, unit in units.items() for group in unit["groups"]}


def platform_allocations(platforms, units):
    """Each shard's instance type per platform: a baseline unit or engine build runs on its platform's frozen type."""
    return {unit["shard_id"]: {name: platform["instance_type"] for name, platform in platforms.items()}
            for unit in units.values()}


def check_unit_campaign(receipts, platforms, units, unit_digests, allocations, manifest_digests, campaign_id,
                        expected_replica_ids=(1, 2, 3), confirmations=()):
    """Accept a campaign's unit receipts as a whole.

    Every group of every unit runs once on every platform for each expected replica, on the
    instance type its shard is allocated there, and every attempt measured the same candidate. A
    replica may gather its groups from several attempts, but no instance that measured one replica
    of a unit measured another. Different units may share a host. A confirmation is replica 4, at
    most one receipt per platform, unit, and group, covering every group of a unit it confirms.
    unit_digests holds each group's row_protocol_sha256 by (unit, group), allocations each
    shard's {platform: instance type}, and manifest_digests each shard's manifest_sha256.
    """
    expected = {
        (platform, unit_id, str(replica), group["group_id"])
        for platform in platforms for unit_id, unit in units.items() for replica in expected_replica_ids
        for group in unit["groups"]}
    observed = []
    confirmed = []
    instances_by_replica = {}
    hosts = {}
    candidate_identities = set()
    for receipt, confirmation in [*((row, False) for row in receipts), *((row, True) for row in confirmations)]:
        context = f"unit attempt {receipt['unit_attempt_id']!r}"
        if receipt["schema_version"] != UNIT_SCHEMA_VERSION:
            reject(f"{context} schema_version must be {UNIT_SCHEMA_VERSION}")
        if receipt["campaign_id"] != campaign_id:
            reject(f"{context} campaign_id is {receipt['campaign_id']!r}, expected {campaign_id!r}")
        if UNIT_ATTEMPT.fullmatch(receipt["unit_attempt_id"]) is None:
            reject(f"{context} unit_attempt_id must be <host_session>/<lease_seq>")
        platform = platforms.get(receipt["platform"])
        unit = units.get(receipt["unit_id"])
        if platform is None or unit is None:
            reject(f"{context} has an unexpected platform {receipt['platform']!r} or unit {receipt['unit_id']!r}")
        if receipt["shard_id"] != unit["shard_id"]:
            reject(f"{context} names shard {receipt['shard_id']!r}, not its unit's {unit['shard_id']!r}")
        if receipt["architecture"] != platform["architecture"] or \
                receipt["instance_type"] != allocations[unit["shard_id"]][receipt["platform"]]:
            reject(f"{context} does not match its shard's frozen allocation on {receipt['platform']}")
        try:
            group = work_units.unit_group(unit, receipt["group_id"])
        except ValueError:
            reject(f"{context} names group {receipt['group_id']!r}, not a group of unit {receipt['unit_id']}")
        if confirmation:
            parse_confirmation_replica(receipt["replica_id"], context)
            confirmed.append((receipt["platform"], receipt["unit_id"], receipt["group_id"]))
        else:
            parse_primary_replica(receipt["replica_id"], context)
            observed.append((receipt["platform"], receipt["unit_id"], receipt["replica_id"], receipt["group_id"]))
        if receipt["owned_row_count"] != str(len(group["owned"])):
            reject(f"{context} row counts differ from unit {receipt['unit_id']} group {receipt['group_id']}")
        if receipt["row_protocol_sha256"] != unit_digests[(receipt["unit_id"], receipt["group_id"])]:
            reject(f"{context} row_protocol_sha256 differs from the frozen row protocols")
        if receipt["manifest_sha256"] != manifest_digests[unit["shard_id"]]:
            reject(f"{context} manifest_sha256 does not match the frozen manifest")
        for field in ("observed_rows_sha256", "evidence_manifest_sha256"):
            if SHA256.fullmatch(receipt[field]) is None:
                reject(f"{context} {field} is not a lowercase SHA-256 digest")
        for field in ("candidate_commit", "engine_tree"):
            if GIT_OBJECT_ID.fullmatch(receipt[field]) is None:
                reject(f"{context} {field} is not a Git object ID")
        if receipt["candidate_archive_sha256"] != "not-recorded" and \
                SHA256.fullmatch(receipt["candidate_archive_sha256"]) is None:
            reject(f"{context} candidate_archive_sha256 is invalid")
        candidate_identities.add((
            receipt["candidate_commit"], receipt["candidate_archive_sha256"], receipt["engine_tree"],
            normalize_heap_size(receipt["heap_size"])))
        instances_by_replica.setdefault((receipt["platform"], receipt["unit_id"]), {}).setdefault(
            receipt["replica_id"], set()).add(receipt["instance_id"])
        for identity, host in (
                (("instance", receipt["instance_id"]), receipt["host_epoch"]),
                (("host epoch", receipt["host_epoch"]), receipt["instance_id"]),
                (("host session", split_unit_attempt(receipt["unit_attempt_id"])[0]), receipt["host_epoch"])):
            hosts.setdefault(identity, set()).add((host, receipt["platform"], receipt["instance_type"]))

    duplicates = duplicate_values(observed)
    missing = sorted(expected - set(observed))
    unexpected = sorted(set(observed) - expected)
    if duplicates or missing or unexpected:
        reject(
            "campaign unit coverage failed: "
            f"missing={format_values(missing)}; "
            f"duplicate={format_values(duplicates)}; "
            f"unexpected={format_values(unexpected)}")
    groups = {(platform, unit_id, group) for platform, unit_id, _, group in expected}
    confirmed_groups = set(confirmed)
    unknown = sorted(confirmed_groups - groups)
    repeated = duplicate_values(confirmed)
    partial = sorted({(platform, unit_id) for platform, unit_id, _ in confirmed
                      if any((platform, unit_id, group["group_id"]) not in confirmed_groups
                             for group in units[unit_id]["groups"])})
    if repeated or unknown or partial:
        reject(f"confirmation must hold one attempt per group of each platform and unit it confirms: "
               f"duplicate={format_values(repeated)}; unexpected={format_values(unknown)}; "
               f"partial={format_values(partial)}")
    attempts = duplicate_values(
        (receipt["unit_attempt_id"], receipt["group_id"]) for receipt in [*receipts, *confirmations])
    if attempts:
        reject(f"campaign reuses unit attempt and group pairs: {format_values(attempts)}")
    shared = sorted(key for key, replicas in instances_by_replica.items()
                    if duplicate_values(instance for instances in replicas.values() for instance in instances))
    if shared:
        reject(f"replicas of a unit share an EC2 instance: {format_values(shared)}")
    ambiguous = sorted(f"{kind} {value}" for (kind, value), names in hosts.items() if len(names) > 1)
    if ambiguous:
        reject(f"one host identity names several hosts: {format_values(ambiguous)}")
    if len(candidate_identities) != 1:
        reject(f"campaign contains multiple candidate identities: {format_values(sorted(candidate_identities))}")


def validate_unit_campaign(manifest_path, platforms_path, shards_path, receipts_path, campaign_id,
                           expected_replica_ids=(1, 2, 3), selected_shards=None,
                           protocol_path=DIRECTORY / "row-protocol.tsv", confirmations=()):
    """Validate an accepted-units.tsv ledger against the frozen units of the campaign's shards.

    confirmations are the accepted replica 4 receipts of the campaign's confirmation phase.
    """
    manifest_rows = load_rows_manifest(manifest_path)
    shards = campaign_shards(manifest_rows, load_shards(shards_path), selected_shards)
    platforms = load_platforms(platforms_path)
    units = {unit_id: unit for unit_id, unit in work_units.load(DIRECTORY).items() if unit["shard_id"] in shards}
    receipts = read_tsv(receipts_path, UNIT_RECEIPT_FIELDS, UNIT_RECEIPT_FIELDS)
    check_unit_campaign(receipts, platforms, units, protocol_digests(units, row_protocol.load(protocol_path)),
                        platform_allocations(platforms, units), dict.fromkeys(shards, sha256_file(manifest_path)),
                        campaign_id, expected_replica_ids, confirmations)
    return {
        "campaign_id": campaign_id,
        "platform_count": len(platforms),
        "shard_count": len(shards),
        "unit_count": len(units),
        "accepted_attempts": len(receipts),
        "accepted_rows": sum(int(receipt["owned_row_count"]) for receipt in receipts),
    }


def parser():
    argument_parser = argparse.ArgumentParser()
    subparsers = argument_parser.add_subparsers(dest="command", required=True)

    plan_parser = subparsers.add_parser("plan")
    plan_parser.add_argument("--unit", required=True)
    plan_parser.add_argument("--replica", required=True)
    plan_parser.add_argument("--group", required=True)
    plan_parser.add_argument("--output-directory", type=Path, required=True)
    plan_parser.set_defaults(function=plan)

    selectors_parser = subparsers.add_parser("selectors")
    selectors_parser.add_argument("--manifest", type=Path, default=DIRECTORY / "rows.tsv")
    selectors_parser.add_argument("--row-protocol", type=Path, default=DIRECTORY / "row-protocol.tsv")
    selectors_parser.add_argument("--expected", type=Path, required=True)
    selectors_parser.add_argument("--shard", required=True)
    selectors_parser.add_argument("--protocol", choices=("smoke", "qualification"), required=True)
    selectors_parser.add_argument("--smoke", nargs=len(SMOKE_SETTINGS), metavar=SMOKE_SETTINGS, required=True)
    selectors_parser.add_argument("--output", type=Path, required=True)
    selectors_parser.set_defaults(function=plan_selectors)

    select_parser = subparsers.add_parser("select")
    select_parser.add_argument("--selectors", type=Path, required=True)
    select_parser.add_argument("--filter", required=True)
    select_parser.add_argument("--parameter", action="append", default=[], metavar="NAME=VALUES")
    select_parser.add_argument("--output-base", required=True)
    select_parser.set_defaults(function=select)

    combine_parser = subparsers.add_parser("combine")
    combine_parser.add_argument("--unit-directory", type=Path, required=True)
    combine_parser.add_argument("--unit-attempt", required=True)
    combine_parser.add_argument("--group", required=True)
    combine_parser.add_argument("--region", required=True)
    combine_parser.set_defaults(function=combine)

    environment_parser = subparsers.add_parser("environment-sha256")
    environment_parser.add_argument("--directory", type=Path, required=True)
    environment_parser.set_defaults(function=print_environment_sha256)

    copy_parser = subparsers.add_parser("copy-environment")
    copy_parser.add_argument("--source", type=Path, required=True)
    copy_parser.add_argument("--target", type=Path, required=True)
    copy_parser.set_defaults(function=copy_environment)
    return argument_parser


def main():
    argument_parser = parser()
    arguments = argument_parser.parse_args()
    try:
        arguments.function(arguments)
    except (OSError, ValueError) as error:
        argument_parser.error(str(error))


if __name__ == "__main__":
    main()
