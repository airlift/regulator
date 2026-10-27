#!/usr/bin/env python3
"""Check that every work unit plans exactly its rows, under their frozen protocols, in route order.

Every unit is planned in process for both protocols and replicas 1 to 4. A
deterministic sample, one unit per handler and any whole-method unit, is also planned through run-unit.sh to pin the shell wiring.
"""
import itertools
import json
import os
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import host_session
import row_protocol
import unit_session
import work_units
from shard_results import load_manifest


DIRECTORY = Path(__file__).resolve().parent
PROTOCOLS = ("smoke", "qualification")
REPLICAS = ("1", "2", "3", "4")
# run-shard.sh smoke windows: processes, warmup and measurement iterations, iteration time.
SMOKE = dict(zip(unit_session.SMOKE_SETTINGS, ("1", "1", "3", "20ms")))
SHELL_RUNS = (("qualification", "1"), ("qualification", "2"), ("smoke", "4"))


def expected_calibrations(route_count):
    """A unit calibrates before its first route and after its last; a lone route does both."""
    if route_count == 1:
        return ["both"]
    return ["before", *["none"] * (route_count - 2), "after"]


def row_key(system, benchmark, parameters):
    return system, benchmark, tuple(sorted(parameters.items()))


def check_selectors(label, share, shard_rows, protocols, protocol_name, selectors):
    """Each selector expands to rows of the share, and together they measure every row once."""
    jmh = {
        row_key(row["system"], row_protocol.jmh_benchmark(row),
                row_protocol.parse_parameters(row["parameters"])): row
        for row in share}
    measured = []
    for selector in selectors:
        benchmarks = selector["benchmarks"].split(",")
        if selector["parameters"] == unit_session.ROUTE_ARGUMENTS:
            if len(benchmarks) != 1:
                raise SystemExit(f"{label} selector {selector['selector']} runs several whole methods")
            expanded = [key for key, row in jmh.items()
                        if key[:2] == (selector["system"], benchmarks[0]) and
                        protocols[row["row_id"]]["execution"] == selector["execution"]]
            whole = [row for row in shard_rows
                     if (row["system"], row_protocol.jmh_benchmark(row)) == (selector["system"], benchmarks[0])]
            method = f"{selector['system']} {benchmarks[0]}"
            if len(expanded) != len(whole):
                raise SystemExit(f"{label} selector {selector['selector']} runs {method} whole with "
                                 f"{len(expanded)} of its {len(whole)} rows")
            values = {name: sorted({row_protocol.parse_parameters(row["parameters"])[name] for row in whole})
                      for name in row_protocol.parse_parameters(whole[0]["parameters"])}
            if all(work_units.expressible(parameter_values) for parameter_values in values.values()):
                raise SystemExit(f"{label} selector {selector['selector']} runs {method} whole, though JMH could "
                                 f"select each of its values: {values}")
        else:
            block = json.loads(selector["parameters"])
            names = sorted(block)
            expanded = [
                row_key(selector["system"], benchmark, dict(zip(names, values)))
                for benchmark in benchmarks
                for values in itertools.product(*(block[name] for name in names))]
        if len(expanded) != int(selector["rows"]):
            raise SystemExit(f"{label} selector {selector['selector']} counts {selector['rows']} rows")
        for key in expanded:
            if key not in jmh:
                raise SystemExit(f"{label} selector {selector['selector']} selects {key}, a row outside the unit")
            check_protocol(label, selector, protocols[jmh[key]["row_id"]], protocol_name)
        measured.extend(expanded)
    if sorted(measured) != sorted(jmh):
        raise SystemExit(f"{label} selectors do not measure each row of the unit exactly once")


def check_protocol(label, selector, protocol, protocol_name):
    if selector["execution"] != protocol["execution"]:
        raise SystemExit(f"{label} selector {selector['selector']} mixes executions")
    fields = ("processes", "warmup_iterations", "warmup_time", "measurement_iterations", "measurement_time")
    if protocol_name == "qualification":
        expected = {field: protocol[field] for field in fields}
        expected["kind"] = "forks" if protocol["execution"] == "jmh-forks" else "processes"
    else:
        kind = "forks" if selector["system"] == "joni" else "processes"
        expected = {
            "kind": kind,
            "processes": protocol["processes"] if kind == "forks" else SMOKE["processes"],
            "warmup_iterations": SMOKE["warmup_iterations"],
            "warmup_time": SMOKE["time"],
            "measurement_iterations": SMOKE["measurement_iterations"],
            "measurement_time": SMOKE["time"],
        }
    actual = {field: selector[field] for field in expected}
    if actual != expected:
        raise SystemExit(f"{label} selector {selector['selector']} runs {actual}, not {expected}")


def check_precision(label, share, protocols, protocol_name):
    """The precision ceiling a route passes is the frozen ceiling of every row of its share; returns it.

    run-shard.sh takes the ceiling from the route's JMH process rows under qualification, so a forked row
    gets the ceiling only through them. Smoke routes pass none."""
    if protocol_name != "qualification":
        return "-"
    ceilings = {
        execution: {protocols[row["row_id"]]["maximum_relative_standard_error"]
                    for row in share if protocols[row["row_id"]]["execution"] == execution}
        for execution in row_protocol.EXECUTIONS}
    ceiling = min(ceilings["jmh-processes"], default="-")
    frozen = set().union(*ceilings.values())
    if frozen - {ceiling}:
        raise SystemExit(f"{label} passes the precision ceiling {ceiling} to rows frozen at {sorted(frozen)}")
    return ceiling


def plan_unit(unit, manifest, protocols, representatives):
    """Every protocol and replica's route plans of one unit, checked; returns them by run."""
    shard = unit["shard_id"]
    held = sorted(unit["owned"])
    dispatch = host_session.load_dispatch(DIRECTORY / "shard-dispatch.tsv", shard)
    routes = [route for route, _, _ in host_session.applicable_routes(dispatch)]
    shard_rows = [row for row in manifest if row["shard_id"] == shard]
    plans = {}
    for replica in REPLICAS:
        planned = unit_session.plan_routes(DIRECTORY, unit, replica)
        label = f"unit {unit['unit_id']} replica {replica}"
        order = [route for route, *_ in planned]
        dispatch_order = [route for route in routes if route in order]
        if order != (dispatch_order[::-1] if int(replica) % 2 == 0 else dispatch_order):
            raise SystemExit(f"{label} runs routes {order} out of replica order")
        if sorted(row["row_id"] for *_, share, _ in planned for row in share) != held:
            raise SystemExit(f"{label} routes do not hold exactly the unit's rows")
        calibrations = [calibration for *_, calibration in planned]
        if calibrations != expected_calibrations(len(planned)):
            raise SystemExit(f"{label} calibrates {calibrations}")
        target = "1" if replica == "4" else replica
        expected_representatives = sorted(
            row_id for representative_replica, row_id in unit["representatives"] if representative_replica == target)
        if unit_session.unit_representatives(unit, replica) != expected_representatives:
            raise SystemExit(f"{label} runs the wrong representatives")
        for protocol_name in PROTOCOLS:
            routes_plan = []
            for route, handler, systems, share, calibration in planned:
                selectors = unit_session.selectors(share, shard_rows, protocols, protocol_name, SMOKE)
                check_selectors(f"{label} {protocol_name} {route}", share, shard_rows, protocols,
                                protocol_name, selectors)
                route_representatives = sum(
                    1 for representative in representatives
                    if (representative["shard_id"], representative["replica_id"], representative["route"]) ==
                    (shard, target, route) and representative["manifest_row_id"] in expected_representatives)
                ceiling = check_precision(f"{label} {protocol_name} {route}", share, protocols, protocol_name)
                routes_plan.append((route, share, selectors, route_representatives, ceiling))
            plans[(protocol_name, replica)] = routes_plan
    return plans


def sample(units, plans):
    """One unit per handler pairing, and a whole-method unit if any unit has one."""
    chosen = {}
    for unit in sorted(units.values(), key=lambda unit: (unit["shard_id"], int(unit["unit_index"]))):
        dispatch = host_session.load_dispatch(DIRECTORY / "shard-dispatch.tsv", unit["shard_id"])
        chosen.setdefault((dispatch["native_access_handler"], dispatch["object_row_handler"]), unit["unit_id"])
        if any(selector["parameters"] == unit_session.ROUTE_ARGUMENTS
               for _, _, selectors, _, _ in plans[unit["unit_id"]][("qualification", "1")]
               for selector in selectors):
            chosen.setdefault("whole-method", unit["unit_id"])
    return sorted(set(chosen.values()))


def run_shell(root, unit_id, protocol_name, replica):
    result_directory = root / unit_id[:12] / f"{protocol_name}-{replica}"
    subprocess.run(
        [str(DIRECTORY / "run-unit.sh"), unit_id, protocol_name, replica, str(result_directory)],
        check=True,
        capture_output=True,
        text=True,
        env={
            **os.environ,
            "BASELINE_PLAN_ONLY": "true",
            "BASELINE_PROTOCOL_QUALIFICATION": "true",
        })
    return result_directory


def check_shell(result_directory, unit_id, plan):
    if (result_directory / "status.txt").read_text(encoding="utf-8") != "status=planned\n":
        raise SystemExit(f"run-unit.sh did not plan {result_directory}")
    route_plan = row_protocol.read_tsv(result_directory / "route-plan.tsv")
    if [route["route"] for route in route_plan] != [route for route, *_ in plan]:
        raise SystemExit(f"run-unit.sh plans routes out of order in {result_directory}")
    calibrations = expected_calibrations(len(plan))
    if [route["calibration"] for route in route_plan] != calibrations:
        raise SystemExit(f"run-unit.sh plans calibrations {[route['calibration'] for route in route_plan]} "
                         f"in {result_directory}, expected {calibrations}")
    for (route, share, selectors, representative_count, ceiling), calibration in zip(plan, calibrations):
        route_directory = result_directory / "routes" / route
        label = f"run-unit.sh {unit_id} {route_directory}"
        expected = sorted(row["row_id"] for row in load_manifest(route_directory / "expected-rows.tsv"))
        if expected != sorted(row["row_id"] for row in share):
            raise SystemExit(f"{label} expects rows other than its share")
        if row_protocol.read_tsv(route_directory / "jmh-selectors.tsv") != selectors:
            raise SystemExit(f"{label} plans selectors other than the unit's")
        metadata = dict(
            line.split("=", 1) for line in
            (route_directory / "run-metadata.txt").read_text(encoding="utf-8").splitlines())
        if metadata["unit_calibration"] != calibration:
            raise SystemExit(f"{label} calibrates {metadata['unit_calibration']}, expected {calibration}")
        if metadata["unit_id"] != unit_id:
            raise SystemExit(f"{label} plans unit {metadata['unit_id']}")
        if int(metadata["protocol_representative_count"]) != representative_count:
            raise SystemExit(f"{label} plans {metadata['protocol_representative_count']} representatives, "
                             f"expected {representative_count}")
        if metadata["maximum_relative_standard_error"] != ceiling:
            raise SystemExit(f"{label} bounds precision at {metadata['maximum_relative_standard_error']}, "
                             f"expected {ceiling}")


def main():
    units = work_units.load(DIRECTORY)
    manifest = load_manifest(DIRECTORY / "rows.tsv")
    protocols = row_protocol.load(DIRECTORY / "row-protocol.tsv")
    representatives = row_protocol.read_tsv(DIRECTORY / "protocol-representatives.tsv")
    plans = {unit_id: plan_unit(unit, manifest, protocols, representatives) for unit_id, unit in units.items()}
    sampled = sample(units, plans)
    runs = [(unit_id, protocol_name, replica) for unit_id in sampled for protocol_name, replica in SHELL_RUNS]
    with tempfile.TemporaryDirectory() as temporary_directory, ThreadPoolExecutor(os.cpu_count()) as executor:
        root = Path(temporary_directory)
        directories = list(executor.map(lambda run: run_shell(root, *run), runs))
        for (unit_id, protocol_name, replica), result_directory in zip(runs, directories):
            check_shell(result_directory, unit_id, plans[unit_id][(protocol_name, replica)])
    print(
        f"validated {len(units)} work units x {len(PROTOCOLS)} protocols x {len(REPLICAS)} replicas in process "
        f"and {len(runs)} run-unit.sh plans of {len(sampled)} sampled units")


if __name__ == "__main__":
    main()
