#!/usr/bin/env python3
"""One work unit's route plan, exact JMH selectors, combined rows, and acceptance.

run-unit.sh plans a unit into the routes its rows need and runs run-shard.sh
once per route. Each route measures only its share of the unit: run-shard.sh
reads the unit's expected rows, and its JMH runners execute the selectors
planned here instead of a handler's whole-method invocation. The combined
unit is accepted on its own, by validate-unit-results.py.
"""
import argparse
import json
import math
import re
from pathlib import Path
from typing import NoReturn

from aggregate_results import parse_parameters

import host_session
import row_protocol
import topology
import unit_cost
import work_units
from acceptance import (
    EVIDENCE_FIELDS,
    FORMAL_UNIT_ARTIFACTS,
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
    exceeds_published_limit,
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
    REBAR_OUTCOME_FIELDS,
    combined_rows,
    exact_filter,
    load_manifest,
    load_observed,
    load_rebar_outcomes,
    write_observed_rows,
    write_tsv,
)


DIRECTORY = Path(__file__).resolve().parent
ROUTE_PLAN_FIELDS = ("route", "handler", "systems", "calibration")
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
UNIT_OBSERVED_FIELDS = OBSERVED_FIELDS + ("role",)
# A selector whose values JMH cannot express runs a whole method with its handler's arguments.
ROUTE_ARGUMENTS = "route"
SMOKE_SETTINGS = ("processes", "warmup_iterations", "measurement_iterations", "time")
UNIT_SCHEMA_VERSION = "1"
UNIT_SESSION_FIELDS = (
    "schema_version",
    "campaign_id",
    "platform",
    "unit_id",
    "replica_id",
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
    "copied_row_count",
    "row_protocol_sha256",
    "manifest_sha256",
    "observed_rows_sha256",
    "evidence_manifest_sha256",
    "candidate_commit",
    "candidate_archive_sha256",
    "engine_tree",
    "heap_size",
)
# A host session leases units in sequence; each lease is one attempt.
UNIT_ATTEMPT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]*/[1-9][0-9]*")
# Identity a unit's routes share with the host that measured them.
ROUTE_IDENTITY_FIELDS = (
    "campaign_id", "platform", "replica_id", "instance_id", "host_epoch", "architecture", "instance_type",
    "availability_zone")
UNIT_HOST_ARTIFACTS = (
    "environment-manifest.txt",
    "environment.txt",
    "engine-build.txt",
    "route-plan.tsv",
    "unit-rows.tsv",
    "expected-rows.tsv",
    "observed-rows.tsv",
    "observed-rows.tsv.sha256",
    "rebar-outcomes.tsv",
    "calibration.tsv",
    "unit-session.tsv",
)
MAXIMUM_CALIBRATION_DRIFT = 0.05


def load_unit(directory, unit_id):
    units = work_units.load(directory)
    if unit_id not in units:
        raise ValueError(f"Unknown work unit {unit_id!r}")
    return units[unit_id]


def unit_rows(directory, unit):
    held = set(unit["owned"]) | set(unit["copies"])
    rows = [row for row in load_manifest(directory / "rows.tsv") if row["row_id"] in held]
    if len(rows) != len(held):
        raise ValueError(f"Unit {unit['unit_id']} holds rows outside the manifest")
    return rows


def route_shares(rows, routes):
    """Each route's share of a unit's rows, as (route, handler, systems, rows) in dispatch order.

    A native bracket wraps the Java rows of its own route. A unit that holds only
    the other route's rows copies the bracket, so it measures the bracket there.
    """
    native = work_units.NATIVE_COMPARATOR_SYSTEMS
    shares = []
    for route, handler, systems in routes:
        share = [row for row in rows if row["system"] in systems]
        if any(row["system"] not in native for row in share):
            shares.append([route, handler, set(systems), share])
    brackets = [row for row in rows if row["system"] in native]
    if brackets and not any(row["system"] in native for _, _, _, share in shares for row in share):
        if len(shares) != 1:
            raise ValueError("A copied native bracket needs exactly one route to wrap")
        shares[0][2] |= native
        shares[0][3] = [row for row in rows if row["system"] in shares[0][2]]
    assigned = sorted(row["row_id"] for _, _, _, share in shares for row in share)
    if assigned != sorted(row["row_id"] for row in rows):
        raise ValueError("Unit rows are not each assigned to exactly one route")
    result = []
    for route, handler, systems, share in shares:
        present = {row["system"] for row in share}
        # A route handler measures every Java system of its route, and a whole bracket or none.
        if present - native != systems - native or present & native not in (set(), native):
            raise ValueError(
                f"Unit route {route} holds systems {sorted(present)}, not those of its handler: {sorted(systems)}")
        result.append((route, handler, frozenset(present), share))
    return result


def ordered_shares(shares, replica_id):
    """Odd replicas run native access first; even replicas reverse it, as host sessions do."""
    return list(reversed(shares)) if int(replica_id) % 2 == 0 else list(shares)


def calibrations(count):
    if count == 1:
        return ["both"]
    return ["before"] + ["none"] * (count - 2) + ["after"]


def protocol_replica(replica_id):
    """Replica 4 confirms replica 1, so it runs replica 1's representatives."""
    return "1" if str(replica_id) == "4" else str(replica_id)


def unit_representatives(unit, replica_id):
    target = protocol_replica(replica_id)
    return sorted(row_id for replica, row_id in unit["representatives"] if replica == target)


def plan_routes(directory, unit, replica_id):
    dispatch = host_session.load_dispatch(directory / "shard-dispatch.tsv", unit["shard_id"])
    shares = ordered_shares(
        route_shares(unit_rows(directory, unit), host_session.applicable_routes(dispatch)), replica_id)
    return [
        (route, handler, systems, share, calibration)
        for (route, handler, systems, share), calibration in zip(shares, calibrations(len(shares)))]


def plan(arguments):
    unit = load_unit(DIRECTORY, arguments.unit)
    if arguments.replica not in {"1", "2", "3", "4"}:
        raise ValueError(f"Replica must be 1, 2, 3, or 4: {arguments.replica}")
    output = arguments.output_directory
    write_tsv(output / "expected-rows.tsv", MANIFEST_FIELDS, unit_rows(DIRECTORY, unit))
    write_tsv(output / "unit-rows.tsv", work_units.UNIT_ROW_FIELDS, [
        row for row in row_protocol.read_tsv(DIRECTORY / "unit-rows.tsv") if row["unit_id"] == unit["unit_id"]])
    write_tsv(output / "route-plan.tsv", ROUTE_PLAN_FIELDS, [
        {"route": route, "handler": handler, "systems": canonical_systems(systems), "calibration": calibration}
        for route, handler, systems, _, calibration in plan_routes(DIRECTORY, unit, arguments.replica)])
    print(unit["shard_id"])


def parameter_block(rows):
    """The Cartesian -p block selecting exactly these rows, or None when JMH cannot express a value."""
    parsed = [parse_parameters(row["parameters"]) for row in rows]
    names = sorted(parsed[0])
    if any(sorted(parameters) != names for parameters in parsed):
        raise ValueError(f"Rows of {rows[0]['benchmark']} do not share parameter names")
    block = {name: list(dict.fromkeys(parameters[name] for parameters in parsed)) for name in names}
    if len({tuple(parameters[name] for name in names) for parameters in parsed}) != len(rows) or \
            len(rows) != math.prod(len(values) for values in block.values()):
        raise ValueError(f"Rows of {rows[0]['benchmark']} are not one Cartesian block")
    if not all(work_units.expressible(values) for values in block.values()):
        return None
    return block


def execution_settings(system, protocol, protocol_name, smoke):
    """How a selector runs: qualification keeps its frozen protocol; smoke keeps the route's smoke windows."""
    if protocol_name == "qualification":
        kind = "forks" if protocol["execution"] == "jmh-forks" else "processes"
        if kind == "forks" and protocol["warmup_time"] != protocol["measurement_time"]:
            raise ValueError(f"Forked protocol {protocol['row_id']} has distinct warmup and measurement windows")
        return {
            "kind": kind,
            **{field: protocol[field] for field in (
                "processes", "warmup_iterations", "warmup_time", "measurement_iterations", "measurement_time")},
        }
    # Smoke forks only Joni, as many times as its frozen protocol, and runs every
    # candidate row in the route's smoke processes.
    kind = "forks" if system == "joni" else "processes"
    return {
        "kind": kind,
        "processes": protocol["processes"] if kind == "forks" else smoke["processes"],
        "warmup_iterations": smoke["warmup_iterations"],
        "warmup_time": smoke["time"],
        "measurement_iterations": smoke["measurement_iterations"],
        "measurement_time": smoke["time"],
    }


def selectors(rows, shard_rows, protocols, protocol_name, smoke):
    """A unit route's JMH rows as exact selectors, in row order.

    A selector is one system and frozen protocol over the methods that share one
    Cartesian -p block, so it measures exactly its rows and nothing else. A row
    whose suite runs each workload in its own JMH invocation is its own selector.
    """
    def key(row):
        alone = row["row_id"] if unit_cost.runs_each_workload(row) else None
        return (row["system"], row_protocol.jmh_benchmark(row), row_protocol.protocol_key(protocols[row["row_id"]]),
                alone)

    groups = {}
    for row in rows:
        if protocols[row["row_id"]]["execution"] in work_units.JMH_EXECUTIONS:
            groups.setdefault(key(row), []).append(row)
    whole = {}
    for row in shard_rows:
        if protocols[row["row_id"]]["execution"] in work_units.JMH_EXECUTIONS:
            row_key = key(row)
            whole[row_key] = whole.get(row_key, 0) + 1
    merged = {}
    for (system, benchmark, protocol_key, alone), group in groups.items():
        block = parameter_block(group)
        if block is None:
            if len(group) != whole[(system, benchmark, protocol_key, alone)]:
                raise ValueError(f"{system} {benchmark} has a value JMH cannot select and is split")
            parameters = ROUTE_ARGUMENTS
        else:
            parameters = json.dumps(block, ensure_ascii=False, separators=(",", ":"))
        # Methods sharing a block share a JVM; a whole method keeps its own.
        merge_key = (system, protocol_key, parameters, benchmark if block is None else None, alone)
        selector = merged.setdefault(merge_key, {
            "protocol": protocols[group[0]["row_id"]], "benchmarks": [], "rows": 0})
        selector["benchmarks"].append(benchmark)
        selector["rows"] += len(group)
    result = []
    for index, ((system, _, parameters, _, _), selector) in enumerate(merged.items(), 1):
        protocol = selector["protocol"]
        result.append({
            "selector": str(index),
            "system": system,
            "execution": protocol["execution"],
            **execution_settings(system, protocol, protocol_name, smoke),
            "benchmarks": ",".join(selector["benchmarks"]),
            "parameters": parameters,
            "rows": str(selector["rows"]),
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
        if arguments.warmup_time and selector["kind"] == "processes":
            selector["warmup_time"] = arguments.warmup_time
        selector["filter"] = exact_filter(selector["benchmarks"].split(","))
        if selector["parameters"] != ROUTE_ARGUMENTS:
            Path(f"{output_base}-selector-{selector['selector']}-parameters.txt").write_text(
                block_arguments(selector["parameters"]), encoding="utf-8")
    write_tsv(Path(f"{output_base}-selectors.tsv"), SELECTION_FIELDS, chosen)


def read_route_plan(root):
    return row_protocol.read_tsv(root / "route-plan.tsv")


def unit_roles(mapping):
    return {row["row_id"]: row["role"] for row in mapping if row["role"] in {"owner", "copy"}}


def combine(arguments):
    """Combine a unit's routes into its observed rows, each marked owner or pairing-only copy."""
    root = arguments.unit_directory
    route_plan = read_route_plan(root)
    observed = []
    rebar_outcomes = {}
    sessions = []
    for route in route_plan:
        route_directory = root / "routes" / route["route"]
        host_session.require_complete_route(route_directory)
        session = read_one_tsv(route_directory / "route-session.tsv", SESSION_FIELDS)
        if parse_systems(session["systems"], str(route_directory)) != parse_systems(route["systems"], "route plan"):
            raise ValueError(f"Route {route['route']} session systems differ from the unit route plan")
        sessions.append(session)
        observed.extend(load_observed(route_directory / "observed-rows.tsv"))
        outcomes_path = route_directory / "raw" / "rebar-outcomes.tsv"
        if outcomes_path.is_file():
            route_outcomes = load_rebar_outcomes(outcomes_path)
            if rebar_outcomes.keys() & route_outcomes.keys():
                raise ValueError("Duplicate Rebar outcomes across unit routes")
            rebar_outcomes.update(route_outcomes)
    identity_fields = [field for field in SESSION_FIELDS if field != "systems"]
    for session in sessions[1:]:
        changed = [field for field in identity_fields if session[field] != sessions[0][field]]
        if changed:
            raise ValueError(f"Unit route sessions changed host identity fields: {changed}")
    rows = combined_rows(load_manifest(root / "expected-rows.tsv"), observed, rebar_outcomes)
    mapping = row_protocol.read_tsv(root / "unit-rows.tsv")
    roles = unit_roles(mapping)
    if rebar_outcomes:
        write_tsv(root / "rebar-outcomes.tsv", REBAR_OUTCOME_FIELDS,
                  [rebar_outcomes[row_id] for row_id in sorted(rebar_outcomes)])
    write_observed_rows(root / "observed-rows.tsv", UNIT_OBSERVED_FIELDS,
                        [{**row, "role": roles[row["row_id"]]} for row in rows])
    unit_id = mapping[0]["unit_id"]
    write_tsv(root / "unit-session.tsv", UNIT_SESSION_FIELDS, [{
        "schema_version": UNIT_SCHEMA_VERSION,
        **{field: sessions[0][field] for field in ROUTE_IDENTITY_FIELDS},
        "unit_id": unit_id,
        "unit_attempt_id": arguments.unit_attempt,
        "region": arguments.region,
    }])
    write_unit_evidence(root, route_plan)
    print(f"Combined {len(rows)} unit rows")


def write_unit_evidence(root, route_plan):
    """Bind each route's artifacts and the unit's own, in the host-session evidence shape."""
    evidence = []
    for route in route_plan:
        systems = parse_systems(route["systems"], "route plan")
        for artifact, path in host_session.route_evidence(
                root / "routes" / route["route"],
                work_units.NATIVE_COMPARATOR_SYSTEMS <= systems,
                UNIT_ROUTE_REQUIRED_ARTIFACTS):
            evidence.append({
                "route": route["route"],
                "artifact": f"routes/{route['route']}/{artifact}",
                "verification": "sha256",
                "sha256": sha256_file(path),
            })
    for artifact in UNIT_HOST_ARTIFACTS:
        if (root / artifact).is_file():
            evidence.append({
                "route": "host", "artifact": artifact, "verification": "sha256", "sha256": sha256_file(root / artifact)})
    if (root / "unit-capacity.txt").is_file():
        evidence.append({
            "route": "host",
            "artifact": "unit-capacity.txt",
            "verification": "capacity-prefix-sha256",
            "sha256": capacity_prefix_sha256(root / "unit-capacity.txt"),
        })
    write_tsv(root / "unit-evidence.tsv", EVIDENCE_FIELDS, sorted(
        evidence, key=lambda row: (row["route"], row["artifact"])))
    verify_unit_evidence(root)


def verify_unit_evidence(root):
    rows = verify_evidence_manifest(
        root / "unit-evidence.tsv", root,
        route_required=UNIT_ROUTE_REQUIRED_ARTIFACTS,
        formal_required=FORMAL_UNIT_ARTIFACTS,
        capacity_artifact="unit-capacity.txt")
    return evidence_candidate_identity(rows, root)


def reject(message) -> NoReturn:
    raise ValidationError(message)


def check_unit_identity(root, session, units, protocols):
    """The unit recomputes from its rows, protocols, and representatives, and is a checked-in unit."""
    unit_rows_file = read_tsv(root / "unit-rows.tsv", work_units.UNIT_ROW_FIELDS, work_units.UNIT_ROW_FIELDS)
    if {row["unit_id"] for row in unit_rows_file} != {session["unit_id"]}:
        reject(f"{root / 'unit-rows.tsv'} does not hold only unit {session['unit_id']}")
    unit = units.get(session["unit_id"])
    if unit is None:
        reject(f"unit {session['unit_id']} is not a checked-in work unit")
    # The attempt's mapping is the checked-in one, row for row, so no row of another role slips past.
    checked_in = [row for row in row_protocol.read_tsv(DIRECTORY / "unit-rows.tsv")
                  if row["unit_id"] == session["unit_id"]]
    if unit_rows_file != checked_in:
        reject(f"{root / 'unit-rows.tsv'} differs from the checked-in rows of unit {session['unit_id']}")
    owned = sorted(unit["owned"])
    copies = sorted(unit["copies"])
    representatives = sorted(unit["representatives"])
    identity = work_units.unit_id(
        unit["shard_id"], owned, copies, [f"{replica}:{row_id}" for replica, row_id in representatives], protocols)
    if identity != session["unit_id"]:
        reject(f"unit {session['unit_id']} recomputes as {identity} under the frozen row protocols")
    return unit


def check_unit_rows(root, unit, manifest):
    """Expected and observed rows are exactly the unit's owned and copied rows, each with its role."""
    held = {row_id: "owner" for row_id in unit["owned"]} | {row_id: "copy" for row_id in unit["copies"]}
    by_id = {row["row_id"]: row for row in manifest}
    expected = [row["row_id"] for row in load_manifest(root / "expected-rows.tsv")]
    if sorted(expected) != sorted(held):
        reject(f"{root / 'expected-rows.tsv'} does not list exactly the unit's rows")
    observed_path = root / "observed-rows.tsv"
    observed = read_tsv(observed_path, ("row_id", "shard_id", "system", "role"), UNIT_OBSERVED_FIELDS)
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
        if row["role"] != held[row["row_id"]]:
            problems.append(f"{observed_path}:{line_number} role is {row['role']!r}, expected {held[row['row_id']]!r}")
    if problems:
        reject("unit observed-row acceptance failed: " + "; ".join(problems))
    verify_observed_rows_checksum(root / "observed-rows.tsv.sha256")


def check_unit_routes(root, unit, session, protocols, manifest):
    """Routes ran in the unit's order with its calibrations, on the unit session's host."""
    routes = plan_routes(DIRECTORY, unit, session["replica_id"])
    shares = {route: share for route, _, _, share, _ in routes}
    planned = [
        {"route": route, "handler": handler, "systems": canonical_systems(systems), "calibration": calibration}
        for route, handler, systems, _, calibration in routes]
    if read_tsv(root / "route-plan.tsv", ROUTE_PLAN_FIELDS, ROUTE_PLAN_FIELDS) != planned:
        reject(f"{root / 'route-plan.tsv'} is not the unit's plan for replica {session['replica_id']}")
    shard_rows = [row for row in manifest if row["shard_id"] == unit["shard_id"]]
    for route in planned:
        directory = root / "routes" / route["route"]
        route_session = read_one_tsv(directory / "route-session.tsv", SESSION_FIELDS)
        changed = [field for field in ROUTE_IDENTITY_FIELDS if route_session[field] != session[field]]
        changed += [field for field, value in (("shard_id", unit["shard_id"]), ("systems", route["systems"]))
                    if route_session[field] != value]
        if changed:
            reject(f"{directory / 'route-session.tsv'} differs from the unit session and route plan: {changed}")
        metadata = read_properties(directory / "run-metadata.txt")
        if (metadata.get("unit_id"), metadata.get("unit_calibration")) != (unit["unit_id"], route["calibration"]):
            reject(f"{directory / 'run-metadata.txt'} ran unit {metadata.get('unit_id')} with calibration "
                   f"{metadata.get('unit_calibration')}, expected unit {unit['unit_id']} with calibration "
                   f"{route['calibration']}")
        check_semantic_receipt(directory, metadata)
        check_route_selectors(directory, metadata, shares[route["route"]], shard_rows, protocols)
    return planned


def check_route_selectors(directory, metadata, share, shard_rows, protocols):
    """The route ran its JMH selectors at the frozen protocol of their rows, with no window override."""
    path = directory / "run-metadata.txt"
    if metadata.get("baseline_joni_jmh_time") != "default":
        reject(f"{path} overrides the frozen Joni JMH window: {metadata.get('baseline_joni_jmh_time')}")
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
    """A cached Surefire receipt is used only when the route's reports are the receipt's files."""
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
            or set(listed) - set(reports) != {"surefire.log"}:
        reject(f"{directory / 'semantic-reports'} are not the files of its semantic receipt")


def check_unit_calibration(root):
    path = root / "calibration.tsv"
    rows = read_tsv(path, ("phase", "score", "before_after_drift"))
    if [row["phase"] for row in rows] != ["before", "after"]:
        reject(f"{path} must hold one before and one after calibration")
    before, after = (float(row["score"]) for row in rows)
    if not (math.isfinite(before) and math.isfinite(after)) or min(before, after) <= 0:
        reject(f"{path} has invalid calibration scores")
    drift = abs(after - before) / min(before, after)
    if exceeds_published_limit(drift, MAXIMUM_CALIBRATION_DRIFT):
        reject(f"{path} drifted {drift:.4f}, above {MAXIMUM_CALIBRATION_DRIFT}")


def check_unit_representatives(root, unit, session, planned):
    """The unit's representatives for this replica ran, each on its own route, and passed."""
    ran = []
    for route in planned:
        path = root / "routes" / route["route"] / "protocol-qualification.tsv"
        if path.is_file():
            ran.extend(row["manifest_row_id"] for row in read_tsv(path, ("manifest_row_id",)))
    expected = unit_representatives(unit, session["replica_id"])
    if sorted(ran) != expected:
        reject(f"unit {unit['unit_id']} ran representatives {format_values(sorted(ran))}, expected "
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


def validate_unit_results(root, manifest_path=DIRECTORY / "rows.tsv", protocol_path=DIRECTORY / "row-protocol.tsv",
                          tables=None):
    """Accept one unit attempt on its own and return its receipt.

    tables, from load_unit_tables, replaces loading the manifest and protocol paths.
    """
    session = read_one_tsv(root / "unit-session.tsv", UNIT_SESSION_FIELDS)
    if session["schema_version"] != UNIT_SCHEMA_VERSION:
        reject(f"{root / 'unit-session.tsv'} schema_version must be {UNIT_SCHEMA_VERSION}")
    if session["replica_id"] not in {"1", "2", "3", "4"}:
        reject(f"{root / 'unit-session.tsv'} replica_id must be 1 to 4")
    if UNIT_ATTEMPT.fullmatch(session["unit_attempt_id"]) is None:
        reject(f"{root / 'unit-session.tsv'} unit_attempt_id must be <host_session>/<lease_seq>")
    if not session["availability_zone"].startswith(session["region"]):
        reject(f"{root / 'unit-session.tsv'} availability zone is outside region {session['region']}")
    tables = tables or load_unit_tables(manifest_path, protocol_path)
    protocols = tables["protocols"]
    manifest = tables["manifest"]
    unit = check_unit_identity(root, session, tables["units"], protocols)
    check_unit_rows(root, unit, manifest)
    planned = check_unit_routes(root, unit, session, protocols, manifest)
    check_unit_calibration(root)
    candidate_commit, candidate_archive_sha256, engine_tree, heap_size = verify_unit_evidence(root)
    check_unit_representatives(root, unit, session, planned)
    return {
        **session,
        "shard_id": unit["shard_id"],
        "owned_row_count": str(len(unit["owned"])),
        "copied_row_count": str(len(unit["copies"])),
        "row_protocol_sha256": work_units.protocol_digest([*unit["owned"], *unit["copies"]], protocols),
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


def split_unit_attempt(unit_attempt_id):
    """The host session and lease sequence of a unit attempt's ID; the sequence stays text."""
    host_session_id, _, sequence = unit_attempt_id.rpartition("/")
    return host_session_id, sequence


def check_unit_campaign(receipts, platforms, units, protocols, manifest_digest, campaign_id,
                        expected_replica_ids=(1, 2, 3), confirmations=()):
    """Accept a campaign's unit receipts as a whole.

    Every unit runs on every platform for each expected replica, the replicas of a unit run on
    distinct EC2 instances, and every attempt measured the same candidate. Different units may
    share a host. A confirmation is replica 4, at most one per platform and unit, on an instance
    none of that unit's replicas used.
    """
    expected = {
        (platform, unit_id, str(replica))
        for platform in platforms for unit_id in units for replica in expected_replica_ids}
    observed = []
    confirmed = []
    instances_by_unit = {}
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
                receipt["instance_type"] != topology.instance_type(platform, unit["shard_id"]):
            reject(f"{context} does not match the frozen platform {receipt['platform']}")
        if confirmation:
            parse_confirmation_replica(receipt["replica_id"], context)
            confirmed.append((receipt["platform"], receipt["unit_id"]))
        else:
            parse_primary_replica(receipt["replica_id"], context)
            observed.append((receipt["platform"], receipt["unit_id"], receipt["replica_id"]))
        if (receipt["owned_row_count"], receipt["copied_row_count"]) != (
                str(len(unit["owned"])), str(len(unit["copies"]))):
            reject(f"{context} row counts differ from unit {receipt['unit_id']}")
        if receipt["row_protocol_sha256"] != work_units.protocol_digest([*unit["owned"], *unit["copies"]], protocols):
            reject(f"{context} row_protocol_sha256 differs from the frozen row protocols")
        if receipt["manifest_sha256"] != manifest_digest:
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
        instances_by_unit.setdefault((receipt["platform"], receipt["unit_id"]), []).append(receipt["instance_id"])
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
    unknown = sorted(set(confirmed) - {(platform, unit_id) for platform, unit_id, _ in expected})
    repeated = duplicate_values(confirmed)
    if repeated or unknown:
        reject(f"confirmation must hold at most one attempt per platform and unit of the campaign: "
               f"duplicate={format_values(repeated)}; unexpected={format_values(unknown)}")
    attempts = duplicate_values(receipt["unit_attempt_id"] for receipt in [*receipts, *confirmations])
    if attempts:
        reject(f"campaign reuses unit attempt IDs: {format_values(attempts)}")
    shared = sorted(key for key, instances in instances_by_unit.items() if duplicate_values(instances))
    if shared:
        reject(f"replicas of a unit share an EC2 instance: {format_values(shared)}")
    ambiguous = sorted(f"{kind} {value}" for (kind, value), names in hosts.items() if len(names) > 1)
    if ambiguous:
        reject(f"one host identity names several hosts: {format_values(ambiguous)}")
    if len(candidate_identities) != 1:
        reject(f"campaign contains multiple candidate identities: {format_values(sorted(candidate_identities))}")


def validate_unit_campaign(manifest_path, platforms_path, shards_path, receipts_path, campaign_id,
                           expected_replica_ids=(1, 2, 3), selected_shards=None,
                           protocol_path=DIRECTORY / "row-protocol.tsv"):
    """Validate an accepted-units.tsv ledger against the checked-in units of the campaign's shards."""
    manifest_rows = load_rows_manifest(manifest_path)
    shards = campaign_shards(manifest_rows, load_shards(shards_path), selected_shards)
    platforms = load_platforms(platforms_path)
    units = {unit_id: unit for unit_id, unit in work_units.load(DIRECTORY).items() if unit["shard_id"] in shards}
    receipts = read_tsv(receipts_path, UNIT_RECEIPT_FIELDS, UNIT_RECEIPT_FIELDS)
    check_unit_campaign(receipts, platforms, units, row_protocol.load(protocol_path), sha256_file(manifest_path),
                        campaign_id, expected_replica_ids)
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
    select_parser.add_argument("--warmup-time")
    select_parser.add_argument("--parameter", action="append", default=[], metavar="NAME=VALUES")
    select_parser.add_argument("--output-base", required=True)
    select_parser.set_defaults(function=select)

    combine_parser = subparsers.add_parser("combine")
    combine_parser.add_argument("--unit-directory", type=Path, required=True)
    combine_parser.add_argument("--unit-attempt", required=True)
    combine_parser.add_argument("--region", required=True)
    combine_parser.set_defaults(function=combine)
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
