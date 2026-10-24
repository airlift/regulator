#!/usr/bin/env python3
"""One work unit's route plan, exact JMH selectors, and combined rows.

run-unit.sh plans a unit into the routes its rows need and runs run-shard.sh
once per route. Each route measures only its share of the unit: run-shard.sh
reads the unit's expected rows, and its JMH runners execute the selectors
planned here instead of a handler's whole-method invocation.
"""
import argparse
import json
import math
import re
from pathlib import Path

from aggregate_results import parse_parameters

import host_session
import row_protocol
import unit_cost
import work_units
from acceptance import SESSION_FIELDS, ValidationError, canonical_systems, parse_systems, read_one_tsv
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
    print(f"Combined {len(rows)} unit rows")


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
