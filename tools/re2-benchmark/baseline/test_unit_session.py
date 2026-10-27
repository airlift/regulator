#!/usr/bin/env python3

import contextlib
import csv
import hashlib
import io
import importlib.util
import itertools
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


BASELINE = Path(__file__).resolve().parent
sys.path.insert(0, str(BASELINE))
from acceptance import PROTOCOL_QUALIFICATION_FIELDS, SESSION_FIELDS  # noqa: E402
import row_protocol  # noqa: E402
import unit_dispatch  # noqa: E402
import work_units  # noqa: E402


def load_module(name, path):
    specification = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


SHARD_RESULTS = load_module("host_session_shard_results", BASELINE / "shard_results.py")
UNIT_SESSION = load_module("unit_session", BASELINE / "unit_session.py")
# A unit whose rows span both memory routes.
UNIT = ("trino-operations", "1")
# A unit owning protocol representatives.
REPRESENTATIVE_UNIT = ("trino-operations", "4")


class TestUnitSession(unittest.TestCase):
    def testCurrentDispatchExactlyCoversEveryShardAndKeepsNativeFirst(self):
        manifest = SHARD_RESULTS.load_manifest(BASELINE / "rows.tsv")
        shards = sorted({row["shard_id"] for row in manifest})
        for shard in shards:
            dispatch = UNIT_SESSION.load_dispatch(BASELINE / "shard-dispatch.tsv", shard)
            routes = UNIT_SESSION.applicable_routes(dispatch)
            self.assertEqual("native-access", routes[0][0])
            route_systems = set().union(*(systems for _, _, systems in routes))
            manifest_systems = {row["system"] for row in manifest if row["shard_id"] == shard}
            self.assertEqual(manifest_systems, route_systems, shard)

    def testEveryUnitPlansExactlyItsRowsUnderItsFrozenProtocolsInRouteOrder(self):
        # Every unit is planned in process for both protocols and replicas 1 to 4. One unit per handler
        # pairing, and any whole-method unit, is also planned through run-unit.sh to pin the shell wiring.
        units = work_units.load(BASELINE)
        manifest = SHARD_RESULTS.load_manifest(BASELINE / "rows.tsv")
        protocols = row_protocol.load(BASELINE / "row-protocol.tsv")
        representatives = row_protocol.read_tsv(BASELINE / "protocol-representatives.tsv")
        plans = {unit_id: plan_unit(unit, manifest, protocols, representatives) for unit_id, unit in units.items()}
        runs = [(unit_id, protocol_name, replica)
                for unit_id in sample(units, plans) for protocol_name, replica in SHELL_RUNS]
        with tempfile.TemporaryDirectory() as temporary_directory, ThreadPoolExecutor(os.cpu_count()) as executor:
            root = Path(temporary_directory)
            directories = list(executor.map(lambda run: run_shell(root, *run), runs))
            for (unit_id, protocol_name, replica), result_directory in zip(runs, directories):
                check_shell(result_directory, unit_id, plans[unit_id][(protocol_name, replica)])

    def testUnitAttemptIsAccepted(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = UnitFixture(Path(temporary_directory), *UNIT)
            fixture.combine()

            receipt = UNIT_SESSION.validate_unit_results(fixture.root)

            unit = fixture.unit
            self.assertEqual((unit["unit_id"], "host-session-1/3", "us-west-2"),
                             (receipt["unit_id"], receipt["unit_attempt_id"], receipt["region"]))
            self.assertEqual(str(len(unit["owned"])), receipt["owned_row_count"])
            self.assertEqual(
                UNIT_SESSION.work_units.protocol_digest(unit["owned"], fixture.protocols),
                receipt["row_protocol_sha256"])
            evidence = {row["artifact"]: row for row in fixture.read("unit-evidence.tsv")}
            self.assertEqual("capacity-prefix-sha256", evidence["unit-capacity.txt"]["verification"])
            self.assertEqual("host", evidence["calibration.tsv"]["route"])

    def testUnitAcceptanceRejectsAnAttemptThatBreaksItsPromises(self):
        def drift_calibration(fixture):
            fixture.write_calibration(100, 106)

        def drop_unit_row(fixture):
            rows = fixture.read("unit-rows.tsv")
            rows.pop()
            SHARD_RESULTS.write_tsv(fixture.root / "unit-rows.tsv", UNIT_SESSION.work_units.UNIT_ROW_FIELDS, rows)

        def add_unknown_role(fixture):
            rows = fixture.read("unit-rows.tsv")
            rows.append({**rows[0], "row_id": "bogus", "role": "spare"})
            SHARD_RESULTS.write_tsv(fixture.root / "unit-rows.tsv", UNIT_SESSION.work_units.UNIT_ROW_FIELDS, rows)

        def shorten_selector_window(fixture):
            path = fixture.route_directory() / "jmh-selectors.tsv"
            rows = UNIT_SESSION.row_protocol.read_tsv(path)
            rows[0]["measurement_time"] = "7ms"
            SHARD_RESULTS.write_tsv(path, UNIT_SESSION.SELECTOR_FIELDS, rows)

        def tamper_route_artifact(fixture):
            (fixture.route_directory() / "semantic-gate.tsv").write_text("changed\n")

        def tamper_raw_log(fixture):
            (fixture.route_directory() / "logs" / "benchmark.log").write_text("tampered\n")

        def add_unlisted_route_artifact(fixture):
            (fixture.route_directory() / "normalized-output.tsv").write_text("normalized\n")

        def forge_protocol(**changes):
            return lambda fixture: fixture.forge_protocol(**changes)

        def empty_route_plan(fixture):
            SHARD_RESULTS.write_tsv(fixture.root / "route-plan.tsv", UNIT_SESSION.ROUTE_PLAN_FIELDS, [])

        def misname_attempt(fixture):
            rows = fixture.read("unit-session.tsv")
            rows[0]["unit_attempt_id"] = "host-session-1"
            SHARD_RESULTS.write_tsv(fixture.root / "unit-session.tsv", UNIT_SESSION.UNIT_SESSION_FIELDS, rows)

        cases = (
            (UNIT, None, drift_calibration, "drifted 0.0600"),
            (UNIT, None, drop_unit_row, "differs from the frozen rows"),
            (UNIT, add_unknown_role, None, "differs from the frozen rows"),
            (UNIT, shorten_selector_window, None, "differs from the selectors of the frozen protocol"),
            (UNIT, None, tamper_route_artifact, "checksum mismatch"),
            (UNIT, None, tamper_raw_log, "raw artifact checksum mismatch"),
            (UNIT, None, add_unlisted_route_artifact, "evidence is incomplete"),
            (UNIT, None, empty_route_plan, "route-plan.tsv is not the unit's plan for replica"),
            (UNIT, None, misname_attempt, "unit-session.tsv unit_attempt_id must be <host_session>/<lease_seq>"),
            (UNIT, lambda fixture: fixture.replace_semantic_report(), None,
             "are not the files of its semantic receipt"),
            (REPRESENTATIVE_UNIT, lambda fixture: fixture.drop_representative(), None, "ran representatives"),
            (REPRESENTATIVE_UNIT, forge_protocol(specialized_score="110"), None,
             "relative difference does not match the protocol scores"),
            (REPRESENTATIVE_UNIT, forge_protocol(specialized_score="106", relative_difference="0.06"), None,
             "exceeds the protocol threshold"),
            (REPRESENTATIVE_UNIT, forge_protocol(reference_sample_count="49"), None,
             "fewer than 50 protocol samples"),
            (REPRESENTATIVE_UNIT, forge_protocol(reference_sample_count="51", specialized_sample_count="51"), None,
             "exactly 50 protocol samples"),
            (REPRESENTATIVE_UNIT, forge_protocol(reference_cv="0.36"), None, "relative standard error"),
            (REPRESENTATIVE_UNIT, forge_protocol(reference_cv="nan"), None, "invalid protocol statistics"),
        )
        for unit, before_combine, after_combine, message in cases:
            with self.subTest(message=message), tempfile.TemporaryDirectory() as temporary_directory:
                fixture = UnitFixture(Path(temporary_directory), *unit)
                if before_combine:
                    before_combine(fixture)
                # Combining binds the attempt's evidence, so it rejects forged evidence before validation does.
                with self.assertRaisesRegex(ValueError, re.escape(message)):
                    fixture.combine()
                    if after_combine:
                        after_combine(fixture)
                    UNIT_SESSION.validate_unit_results(fixture.root)

    def testWorkerUnitsShareTheCalibrationBetweenThem(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            host = Path(temporary_directory) / "host"
            first_before, shared, second_after, drifted = (
                store_host_calibration(host, score) for score in (100, 101, 102, 107.1))
            first = UnitFixture(Path(temporary_directory) / "first", *UNIT)
            first.calibrate_on_host(host, first_before, shared)
            second = UnitFixture(Path(temporary_directory) / "second", *UNIT, chained=True)
            second.chain(shared)
            second.calibrate_on_host(host, shared, second_after)

            for fixture in (first, second):
                UNIT_SESSION.validate_unit_results(fixture.root, host_evidence=host)
                evidence = {row["artifact"]: row for row in fixture.read("unit-evidence.tsv")}
                self.assertEqual("host-sha256", evidence[f"calibrations/{shared.name}"]["verification"])
            self.assertEqual(["none", "after"], [route["calibration"] for route in second.read("route-plan.tsv")])

            with self.assertRaisesRegex(ValueError, "without a host evidence directory"):
                UNIT_SESSION.validate_unit_results(second.root)
            # The link must name the lease before it on the host, within the chain window, in the same environment.
            for link, problem in (({"previous_attempt": "host-session-1/1"}, "previous_attempt"),
                                  ({"gap_seconds": "61"}, "gap_seconds"),
                                  ({"previous_environment_sha256": "0" * 64}, "previous_environment_sha256")):
                with self.subTest(problem=problem):
                    second.chain(shared, **link)
                    second.calibrate_on_host(host, shared, second_after)
                    with self.assertRaisesRegex(ValueError, f"does not chain from the lease before it.*{problem}"):
                        UNIT_SESSION.validate_unit_results(second.root, host_evidence=host)
            second.chain(shared)
            second.calibrate_on_host(host, shared, second_after)
            second_after.unlink()
            with self.assertRaisesRegex(ValueError, "referenced host calibration is missing"):
                UNIT_SESSION.validate_unit_results(second.root, host_evidence=host)
            second.calibrate_on_host(host, shared, drifted)
            with self.assertRaisesRegex(ValueError, re.escape("drifted 0.0604")):
                UNIT_SESSION.validate_unit_results(second.root, host_evidence=host)

    def testCampaignAcceptsEveryUnitReplicaOnItsOwnInstance(self):
        def validate(campaign, receipts, replicas=(1, 2, 3)):
            return UNIT_SESSION.validate_unit_campaign(
                BASELINE / "rows.tsv", BASELINE / "platforms.tsv", BASELINE / "shards.tsv",
                campaign.write_ledger(campaign.root / "accepted-units.tsv", receipts), "campaign", replicas,
                list(campaign.shard_ids))

        with tempfile.TemporaryDirectory() as temporary_directory:
            smoke = UnitCampaign(Path(temporary_directory) / "smoke", "like-dfa-single-use", replicas=(1,))
            self.assertEqual(2, validate(smoke, smoke.receipts, (1,))["accepted_attempts"])

            campaign = UnitCampaign(Path(temporary_directory) / "campaign", "like-dfa-single-use")
            summary = validate(campaign, campaign.receipts)
            self.assertEqual(
                (1, 6, 36), (summary["unit_count"], summary["accepted_attempts"], summary["accepted_rows"]))
            missing = f"missing=[('r8i', '{campaign.units[0]['unit_id']}', '2')]"
            with self.assertRaisesRegex(ValueError, re.escape(missing)):
                validate(campaign, [receipt for receipt in campaign.receipts
                                    if (receipt["platform"], receipt["replica_id"]) != ("r8i", "2")])
            # Replica 2 measured again on the host that measured replica 1.
            repeated = campaign.add("r8i", campaign.units[0], 2, host=1)
            with self.assertRaisesRegex(ValueError, "replicas of a unit share an EC2 instance"):
                validate(campaign, [
                    repeated if (receipt["platform"], receipt["replica_id"]) == ("r8i", "2") else receipt
                    for receipt in campaign.receipts[:-1]])


PROTOCOLS = ("smoke", "qualification")
REPLICAS = ("1", "2", "3", "4")
# run-shard.sh smoke windows: processes, warmup and measurement iterations, iteration time.
SMOKE_WINDOWS = dict(zip(UNIT_SESSION.SMOKE_SETTINGS, ("1", "1", "3", "20ms")))
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
        if selector["parameters"] == UNIT_SESSION.ROUTE_ARGUMENTS:
            if len(benchmarks) != 1:
                raise AssertionError(f"{label} selector {selector['selector']} runs several whole methods")
            expanded = [key for key, row in jmh.items()
                        if key[:2] == (selector["system"], benchmarks[0]) and
                        protocols[row["row_id"]]["execution"] == selector["execution"]]
            whole = [row for row in shard_rows
                     if (row["system"], row_protocol.jmh_benchmark(row)) == (selector["system"], benchmarks[0])]
            method = f"{selector['system']} {benchmarks[0]}"
            if len(expanded) != len(whole):
                raise AssertionError(f"{label} selector {selector['selector']} runs {method} whole with "
                                     f"{len(expanded)} of its {len(whole)} rows")
            values = {name: sorted({row_protocol.parse_parameters(row["parameters"])[name] for row in whole})
                      for name in row_protocol.parse_parameters(whole[0]["parameters"])}
            if all(work_units.expressible(parameter_values) for parameter_values in values.values()):
                raise AssertionError(f"{label} selector {selector['selector']} runs {method} whole, though JMH could "
                                     f"select each of its values: {values}")
        else:
            block = json.loads(selector["parameters"])
            names = sorted(block)
            expanded = [
                row_key(selector["system"], benchmark, dict(zip(names, values)))
                for benchmark in benchmarks
                for values in itertools.product(*(block[name] for name in names))]
        if len(expanded) != int(selector["rows"]):
            raise AssertionError(f"{label} selector {selector['selector']} counts {selector['rows']} rows")
        for key in expanded:
            if key not in jmh:
                raise AssertionError(f"{label} selector {selector['selector']} selects {key}, a row outside the unit")
            check_protocol(label, selector, protocols[jmh[key]["row_id"]], protocol_name)
        measured.extend(expanded)
    if sorted(measured) != sorted(jmh):
        raise AssertionError(f"{label} selectors do not measure each row of the unit exactly once")


def check_protocol(label, selector, protocol, protocol_name):
    if selector["execution"] != protocol["execution"]:
        raise AssertionError(f"{label} selector {selector['selector']} mixes executions")
    fields = ("processes", "warmup_iterations", "warmup_time", "measurement_iterations", "measurement_time")
    if protocol_name == "qualification":
        expected = {field: protocol[field] for field in fields}
        expected["kind"] = "forks" if protocol["execution"] == "jmh-forks" else "processes"
    else:
        kind = "forks" if selector["system"] == "joni" else "processes"
        expected = {
            "kind": kind,
            "processes": protocol["processes"] if kind == "forks" else SMOKE_WINDOWS["processes"],
            "warmup_iterations": SMOKE_WINDOWS["warmup_iterations"],
            "warmup_time": SMOKE_WINDOWS["time"],
            "measurement_iterations": SMOKE_WINDOWS["measurement_iterations"],
            "measurement_time": SMOKE_WINDOWS["time"],
        }
    actual = {field: selector[field] for field in expected}
    if actual != expected:
        raise AssertionError(f"{label} selector {selector['selector']} runs {actual}, not {expected}")


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
        raise AssertionError(f"{label} passes the precision ceiling {ceiling} to rows frozen at {sorted(frozen)}")
    return ceiling


def plan_unit(unit, manifest, protocols, representatives):
    """Every protocol and replica's route plans of one unit, checked; returns them by run."""
    shard = unit["shard_id"]
    held = sorted(unit["owned"])
    dispatch = UNIT_SESSION.load_dispatch(BASELINE / "shard-dispatch.tsv", shard)
    routes = [route for route, _, _ in UNIT_SESSION.applicable_routes(dispatch)]
    shard_rows = [row for row in manifest if row["shard_id"] == shard]
    plans = {}
    for replica in REPLICAS:
        planned = UNIT_SESSION.plan_routes(BASELINE, unit, replica)
        label = f"unit {unit['unit_id']} replica {replica}"
        order = [route for route, *_ in planned]
        dispatch_order = [route for route in routes if route in order]
        if order != (dispatch_order[::-1] if int(replica) % 2 == 0 else dispatch_order):
            raise AssertionError(f"{label} runs routes {order} out of replica order")
        if sorted(row["row_id"] for *_, share, _ in planned for row in share) != held:
            raise AssertionError(f"{label} routes do not hold exactly the unit's rows")
        calibrations = [calibration for *_, calibration in planned]
        if calibrations != expected_calibrations(len(planned)):
            raise AssertionError(f"{label} calibrates {calibrations}")
        target = "1" if replica == "4" else replica
        expected_representatives = sorted(
            row_id for representative_replica, row_id in unit["representatives"] if representative_replica == target)
        if UNIT_SESSION.unit_representatives(unit, replica) != expected_representatives:
            raise AssertionError(f"{label} runs the wrong representatives")
        for protocol_name in PROTOCOLS:
            routes_plan = []
            for route, handler, systems, share, calibration in planned:
                selectors = UNIT_SESSION.selectors(share, shard_rows, protocols, protocol_name, SMOKE_WINDOWS)
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
        dispatch = UNIT_SESSION.load_dispatch(BASELINE / "shard-dispatch.tsv", unit["shard_id"])
        chosen.setdefault((dispatch["native_access_handler"], dispatch["object_row_handler"]), unit["unit_id"])
        if any(selector["parameters"] == UNIT_SESSION.ROUTE_ARGUMENTS
               for _, _, selectors, _, _ in plans[unit["unit_id"]][("qualification", "1")]
               for selector in selectors):
            chosen.setdefault("whole-method", unit["unit_id"])
    return sorted(set(chosen.values()))


def run_shell(root, unit_id, protocol_name, replica):
    result_directory = root / unit_id[:12] / f"{protocol_name}-{replica}"
    subprocess.run(
        [str(BASELINE / "run-unit.sh"), unit_id, protocol_name, replica, str(result_directory)],
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
        raise AssertionError(f"run-unit.sh did not plan {result_directory}")
    route_plan = row_protocol.read_tsv(result_directory / "route-plan.tsv")
    if [route["route"] for route in route_plan] != [route for route, *_ in plan]:
        raise AssertionError(f"run-unit.sh plans routes out of order in {result_directory}")
    calibrations = expected_calibrations(len(plan))
    if [route["calibration"] for route in route_plan] != calibrations:
        raise AssertionError(f"run-unit.sh plans calibrations {[route['calibration'] for route in route_plan]} "
                             f"in {result_directory}, expected {calibrations}")
    for (route, share, selectors, representative_count, ceiling), calibration in zip(plan, calibrations):
        route_directory = result_directory / "routes" / route
        label = f"run-unit.sh {unit_id} {route_directory}"
        expected = sorted(row["row_id"] for row in SHARD_RESULTS.load_manifest(route_directory / "expected-rows.tsv"))
        if expected != sorted(row["row_id"] for row in share):
            raise AssertionError(f"{label} expects rows other than its share")
        if row_protocol.read_tsv(route_directory / "jmh-selectors.tsv") != selectors:
            raise AssertionError(f"{label} plans selectors other than the unit's")
        metadata = dict(
            line.split("=", 1) for line in
            (route_directory / "run-metadata.txt").read_text(encoding="utf-8").splitlines())
        if metadata["unit_calibration"] != calibration:
            raise AssertionError(f"{label} calibrates {metadata['unit_calibration']}, expected {calibration}")
        if metadata["unit_id"] != unit_id:
            raise AssertionError(f"{label} plans unit {metadata['unit_id']}")
        if int(metadata["protocol_representative_count"]) != representative_count:
            raise AssertionError(f"{label} plans {metadata['protocol_representative_count']} representatives, "
                                 f"expected {representative_count}")
        if metadata["maximum_relative_standard_error"] != ceiling:
            raise AssertionError(f"{label} bounds precision at {metadata['maximum_relative_standard_error']}, "
                                 f"expected {ceiling}")


def store_host_calibration(host, score):
    """Store one calibration JMH result in a worker host's evidence, named by its digest."""
    document = json.dumps([{
        "benchmark": "io.airlift.regulator.BenchmarkExpressionPlans.findLiteral",
        "params": {"workload": "KIB_LATE"},
        "primaryMetric": {"score": score, "scoreUnit": "ns/op", "rawData": [[score]]},
    }]).encode()
    path = host / "calibrations" / f"{hashlib.sha256(document).hexdigest()}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(document)
    return path


class UnitFixture:
    """A unit attempt's directory as run-unit.sh leaves it, with fixture measurements."""

    HOST = {
        "campaign_id": "campaign", "platform": "r8i", "instance_id": "i-1", "host_epoch": "epoch-1",
        "architecture": "intel", "instance_type": "r8i.2xlarge", "availability_zone": "us-west-2a",
    }
    # The route's JMH process settings, as run-shard.sh records them.
    SMOKE = ("5", "10", "10", "1s")
    SMOKE_METADATA = ("jmh_process_count", "jmh_process_warmup_iterations", "jmh_process_measurement_iterations",
                      "jmh_iteration_time")

    def __init__(self, root, shard_id, unit_index, replica=None, host=None, score=lambda row: 1, chained=False):
        self.root = root
        self.host_evidence = None
        units = UNIT_SESSION.work_units.load(BASELINE)
        self.unit = next(unit for unit in units.values()
                         if (unit["shard_id"], unit["unit_index"]) == (shard_id, unit_index))
        self.protocols = UNIT_SESSION.row_protocol.load(BASELINE / "row-protocol.tsv")
        self.replica = replica or min([replica for replica, _ in self.unit["representatives"]] or ["1"])
        self.host = host or self.HOST
        self.score = score
        with contextlib.redirect_stdout(io.StringIO()):
            UNIT_SESSION.plan(type("Arguments", (), {
                "unit": self.unit["unit_id"], "replica": self.replica, "output_directory": root,
                "chained_calibration": chained})())
        representatives = UNIT_SESSION.row_protocol.read_tsv(BASELINE / "protocol-representatives.tsv")
        held = UNIT_SESSION.unit_representatives(self.unit, self.replica)
        (root / "unit-capacity.txt").write_text(
            "memory_total_bytes=34359738368\nmemory_available_before_bytes=30000000000\n"
            "swap_total_bytes=0\nswap_free_before_bytes=0\noom_kills_before=0\n")
        self.write_calibration(100, 101)
        for route, _, systems, share, calibration in UNIT_SESSION.plan_routes(
                BASELINE, self.unit, self.replica, chained):
            route_representatives = [
                row for row in representatives
                if (row["shard_id"], row["replica_id"], row["route"]) == (shard_id, self.replica, route)
                and row["manifest_row_id"] in held]
            self.create_route(route, systems, share, calibration, route_representatives)

    def route_directory(self):
        return self.root / "routes" / UNIT_SESSION.read_route_plan(self.root)[0]["route"]

    def write_calibration(self, before, after):
        drift = abs(after - before) / min(before, after)
        (self.root / "calibration.tsv").write_text(
            "phase\tscore\tscore_unit\tbefore_after_drift\n"
            f"before\t{before}\tns/op\t{drift}\nafter\t{after}\tns/op\t{drift}\n")

    def chain(self, calibration, **link):
        """Record the chained calibration link as the worker writes it for the attempt's lease."""
        (self.root / "unit-chain.txt").write_text("".join(f"{name}={value}\n" for name, value in {
            "previous_attempt": "host-session-1/2", "calibration": calibration.stem, "gap_seconds": "30",
            "previous_environment_sha256": UNIT_SESSION.environment_sha256(self.root), **link}.items()))

    def calibrate_on_host(self, host, before, after):
        """Calibrate from a worker host's stored calibrations and combine, as run-unit.sh does there."""
        (self.root / "calibration.tsv").unlink(missing_ok=True)
        SHARD_RESULTS.calibration(type("Arguments", (), {
            "before": before, "after": after, "output": self.root / "calibration.tsv"})())
        self.host_evidence = host
        self.combine()

    def create_route(self, route, systems, share, calibration, representatives):
        directory = self.root / "routes" / route
        (directory / "semantic-reports").mkdir(parents=True)
        SHARD_RESULTS.write_tsv(directory / "route-session.tsv", SESSION_FIELDS, [{
            "schema_version": "2", **self.host, "shard_id": self.unit["shard_id"], "replica_id": self.replica,
            "systems": ",".join(sorted(systems)),
        }])
        observed = directory / "observed-rows.tsv"
        SHARD_RESULTS.write_tsv(observed, SHARD_RESULTS.OBSERVED_FIELDS, [{
            "row_id": row["row_id"], "shard_id": row["shard_id"], "system": row["system"],
            "score": str(self.score(row)),
            "score_unit": "ns/op", "allocation_bytes": "0", "result_checksum": "0" * 64, "outcome": "accepted",
        } for row in sorted(share, key=lambda row: row["row_id"])])
        (directory / "observed-rows.tsv.sha256").write_text(
            hashlib.sha256(observed.read_bytes()).hexdigest() + "  observed-rows.tsv\n")
        for name in ("semantic-gate.tsv", "semantic-evidence.tsv"):
            (directory / name).write_text("evidence\n")
        shard_rows = [row for row in UNIT_SESSION.load_manifest(BASELINE / "rows.tsv")
                      if row["shard_id"] == self.unit["shard_id"]]
        SHARD_RESULTS.write_tsv(directory / "jmh-selectors.tsv", UNIT_SESSION.SELECTOR_FIELDS, UNIT_SESSION.selectors(
            share, shard_rows, self.protocols, "qualification", dict(zip(UNIT_SESSION.SMOKE_SETTINGS, self.SMOKE))))
        report = directory / "semantic-reports" / "TEST-Fixture.xml"
        report.write_text("<testsuite/>\n")
        (directory / "semantic-receipt.sha256").write_text(
            f"{hashlib.sha256(report.read_bytes()).hexdigest()}  reports/TEST-Fixture.xml\n"
            f"{'d' * 64}  surefire.log\n")
        if representatives:
            SHARD_RESULTS.write_tsv(
                directory / "protocol-qualification.tsv", PROTOCOL_QUALIFICATION_FIELDS, [{
                    "manifest_row_id": row["manifest_row_id"], "system": row["system"], "route": route,
                    "benchmark": row["jmh_benchmark"], "parameters": row["parameters"],
                    "reference_protocol": "5-forks-10x1s",
                    "specialized_protocol": "5-independent-jvms-10x50ms-warmup-10x50ms-measurement",
                    "reference_score": "100", "specialized_score": "101", "score_unit": "ns/op",
                    "reference_sample_count": "50", "specialized_sample_count": "50", "reference_cv": "0.01",
                    "specialized_cv": "0.01", "relative_difference": "0.01", "maximum_relative_difference": "0.05",
                    "outcome": "accepted",
                } for row in representatives])
        self.write_metadata(directory, calibration, len(representatives))
        (directory / "raw").mkdir()
        (directory / "logs").mkdir()
        log = directory / "logs" / "benchmark.log"
        log.write_text("complete\n")
        (directory / "raw-artifacts.sha256").write_text(
            f"{hashlib.sha256(log.read_bytes()).hexdigest()}  logs/benchmark.log\n")
        (directory / "status.txt").write_text("status=complete\n")

    def write_metadata(self, directory, calibration, representative_count):
        receipt = directory / "semantic-receipt.sha256"
        (directory / "run-metadata.txt").write_text(
            f"candidate_commit={'a' * 40}\nengine_tree={'b' * 40}\nheap_size=8g\n"
            f"protocol_qualification_required={'true' if representative_count else 'false'}\n"
            f"protocol_representative_count={representative_count}\n"
            f"unit_id={self.unit['unit_id']}\nunit_calibration={calibration}\n"
            "protocol=qualification\n" +
            "".join(f"{name}={value}\n" for name, value in zip(self.SMOKE_METADATA, self.SMOKE)) +
            f"semantic_receipt=measured\nsemantic_receipt_sha256={hashlib.sha256(receipt.read_bytes()).hexdigest()}\n")

    def replace_semantic_report(self):
        (self.route_directory() / "semantic-reports" / "TEST-Fixture.xml").write_text("<testsuite>other</testsuite>\n")

    def forge_protocol(self, **changes):
        """Rewrite the first representative's qualification statistics before the attempt binds them."""
        path = next(self.root.glob("routes/*/protocol-qualification.tsv"))
        rows = self.read(path.relative_to(self.root))
        rows[0].update(changes)
        SHARD_RESULTS.write_tsv(path, PROTOCOL_QUALIFICATION_FIELDS, rows)

    def drop_representative(self):
        directory = next(path.parent for path in self.root.glob("routes/*/protocol-qualification.tsv"))
        rows = self.read(directory.relative_to(self.root) / "protocol-qualification.tsv")
        # A route that ran no representative writes no qualification table.
        (directory / "protocol-qualification.tsv").unlink()
        if rows[1:]:
            SHARD_RESULTS.write_tsv(directory / "protocol-qualification.tsv", PROTOCOL_QUALIFICATION_FIELDS, rows[1:])
        metadata = directory / "run-metadata.txt"
        calibration = UNIT_SESSION.read_properties(metadata)["unit_calibration"]
        self.write_metadata(directory, calibration, len(rows) - 1)

    def read(self, name):
        with (self.root / name).open(newline="", encoding="utf-8") as input_file:
            return list(csv.DictReader(input_file, delimiter="\t"))

    def combine(self, attempt="host-session-1/3"):
        with contextlib.redirect_stdout(io.StringIO()):
            UNIT_SESSION.combine(type("Arguments", (), {
                "unit_directory": self.root, "unit_attempt": attempt,
                "region": self.host["availability_zone"][:-1],
                "host_evidence": self.host_evidence})())

    def accept(self, attempt):
        """Accept the attempt as run-unit.sh does, then finalize its capacity and status."""
        self.combine(attempt)
        receipt = UNIT_SESSION.validate_unit_results(self.root, host_evidence=self.host_evidence)
        UNIT_SESSION.write_unit_receipt(self.root / "unit-receipt.tsv", receipt)
        with (self.root / "unit-capacity.txt").open("a") as capacity:
            capacity.write("wall_seconds=600\nmaximum_resident_kibibytes=1048576\nexit_status=0\n"
                           "memory_available_after_bytes=29000000000\nminimum_available_memory_bytes=3435973837\n"
                           "swap_free_after_bytes=0\noom_kills_after=0\ncapacity_status=accepted\n")
        (self.root / "status.txt").write_text("status=complete\n")
        return receipt


class UnitCampaign:
    """A unit campaign's retained attempts: one host per platform and replica runs each unit of its shards."""

    PLATFORMS = {"r8i": ("intel", "r8i.large"), "r9g": ("arm", "r9g.large")}

    def __init__(self, root, *shard_ids, replicas=(1, 2, 3), score=lambda row, platform, replica: 1,
                 host_calibrations=False, chained=False):
        self.root = root
        self.shard_ids = shard_ids
        self.score = score
        self.host_calibrations = host_calibrations
        # With chained host calibrations, each lease after a host's first takes its before from the lease before it.
        self.chained = chained
        self.completions = {}
        self.units = sorted(
            (unit for unit in UNIT_SESSION.work_units.load(BASELINE).values() if unit["shard_id"] in shard_ids),
            key=lambda unit: (shard_ids.index(unit["shard_id"]), int(unit["unit_index"])))
        self.leases = {}
        self.receipts = []
        for platform in self.PLATFORMS:
            for replica in replicas:
                for unit in self.units:
                    self.add(platform, unit, replica, host=replica)

    def directory(self, receipt):
        return unit_dispatch.unit_attempt_directory(self.root, receipt)

    def add(self, platform, unit, replica, host, chained_before=None):
        """Run one unit attempt as the next lease of the platform's host number host.

        A chained lease's before is its previous lease's after, unless chained_before names another score."""
        host_session = f"{platform}-session-{host}"
        lease = self.leases[host_session] = self.leases.get(host_session, 0) + 1
        directory = (self.root / "units" / platform / unit["unit_id"] / f"replica-{replica}" / host_session /
                     f"lease-{lease}")
        directory.mkdir(parents=True)
        architecture, instance_type = self.PLATFORMS[platform]
        previous = self.completions.get(host_session) if self.chained else None
        fixture = UnitFixture(
            directory, unit["shard_id"], unit["unit_index"], str(replica), {
                "campaign_id": "campaign", "platform": platform, "instance_id": f"i-{platform}-{host}",
                "host_epoch": f"{platform}-epoch-{host}", "architecture": architecture,
                "instance_type": instance_type, "availability_zone": "us-west-2a",
            }, lambda row: self.score(row, platform, replica), chained=previous is not None)
        scores = (100, 101)
        if self.host_calibrations:
            if previous is not None:
                marker, after = previous
                scores = (after if chained_before is None else chained_before, after + 1)
                # The controller retains the previous lease's own completion marker beside a chained attempt.
                (directory.parent / f"lease-{lease - 1}-completion.json").write_text(json.dumps(marker))
            # A worker host stores its calibrations once, beside its attempts.
            calibrations = [store_host_calibration(directory.parent, score) for score in scores]
            if previous is not None:
                fixture.chain(calibrations[0], previous_attempt=f"{host_session}/{lease - 1}")
            fixture.calibrate_on_host(directory.parent, *calibrations)
            self.completions[host_session] = ({
                "schema_version": 1, "host_session": host_session, "sequence": lease, "campaign_id": "campaign",
                "platform": platform, "unit_id": unit["unit_id"], "replica_id": replica,
                "unit_attempt_id": f"{host_session}/{lease}", "exit_status": 0, "unit_status": "complete",
                "calibration_before": calibrations[0].stem, "calibration_after": calibrations[1].stem,
                "calibration_chained": previous is not None}, scores[1])
        receipt = fixture.accept(f"{host_session}/{lease}")
        # A confirmation replica's receipt stays out of the campaign's primary receipts.
        if replica != 4:
            self.receipts.append(receipt)
        return receipt

    def write_ledger(self, path, receipts):
        SHARD_RESULTS.write_tsv(path, UNIT_SESSION.UNIT_RECEIPT_FIELDS, receipts)
        return path


if __name__ == "__main__":
    unittest.main()
