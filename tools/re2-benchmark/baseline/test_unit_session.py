#!/usr/bin/env python3

import contextlib
import csv
import hashlib
import io
import importlib.util
import re
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parent
sys.path.insert(0, str(BASELINE))
from acceptance import PROTOCOL_QUALIFICATION_FIELDS  # noqa: E402


def load_module(name, path):
    specification = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


HOST_SESSION = load_module("host_session", BASELINE / "host_session.py")
SHARD_RESULTS = load_module("host_session_shard_results", BASELINE / "shard_results.py")
UNIT_SESSION = load_module("unit_session", BASELINE / "unit_session.py")
# A one-route object-row unit that copies the native bracket it is compared against.
COPY_UNIT = ("traditional-extra-big-fixed", "2")
# A unit owning protocol representatives.
REPRESENTATIVE_UNIT = ("compile-public", "2")


class TestUnitSession(unittest.TestCase):
    def testUnitAttemptIsAcceptedWithCopiesMarkedPairingOnly(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = UnitFixture(Path(temporary_directory), *COPY_UNIT)
            fixture.combine()

            receipt = UNIT_SESSION.validate_unit_results(fixture.root)

            unit = fixture.unit
            self.assertEqual((unit["unit_id"], "host-session-1/3", "us-west-2"),
                             (receipt["unit_id"], receipt["unit_attempt_id"], receipt["region"]))
            self.assertEqual(("7", "14"), (receipt["owned_row_count"], receipt["copied_row_count"]))
            self.assertEqual(
                UNIT_SESSION.work_units.protocol_digest([*unit["owned"], *unit["copies"]], fixture.protocols),
                receipt["row_protocol_sha256"])
            roles = {row["row_id"]: row["role"] for row in fixture.read("observed-rows.tsv")}
            self.assertEqual({row_id: "copy" for row_id in unit["copies"]},
                             {row_id: role for row_id, role in roles.items() if role == "copy"})
            evidence = {row["artifact"]: row for row in fixture.read("unit-evidence.tsv")}
            self.assertEqual("capacity-prefix-sha256", evidence["unit-capacity.txt"]["verification"])
            self.assertEqual("host", evidence["calibration.tsv"]["route"])

    def testUnitAcceptanceRejectsAnAttemptThatBreaksItsPromises(self):
        def swap_copy_role(fixture):
            rows = fixture.read("observed-rows.tsv")
            next(row for row in rows if row["role"] == "copy")["role"] = "owner"
            fixture.write_observed(rows)

        def drift_calibration(fixture):
            fixture.write_calibration(100, 106)

        def drop_unit_copy(fixture):
            rows = fixture.read("unit-rows.tsv")
            rows.remove(next(row for row in rows if row["role"] == "copy"))
            SHARD_RESULTS.write_tsv(fixture.root / "unit-rows.tsv", UNIT_SESSION.work_units.UNIT_ROW_FIELDS, rows)

        def add_unknown_role(fixture):
            rows = fixture.read("unit-rows.tsv")
            rows.append({**rows[0], "row_id": "bogus", "role": "spare"})
            SHARD_RESULTS.write_tsv(fixture.root / "unit-rows.tsv", UNIT_SESSION.work_units.UNIT_ROW_FIELDS, rows)

        def override_joni_window(fixture):
            metadata = fixture.route_directory() / "run-metadata.txt"
            metadata.write_text(
                metadata.read_text().replace("baseline_joni_jmh_time=default", "baseline_joni_jmh_time=7ms"))

        def shorten_selector_window(fixture):
            path = fixture.route_directory() / "jmh-selectors.tsv"
            rows = UNIT_SESSION.row_protocol.read_tsv(path)
            rows[0]["measurement_time"] = "7ms"
            SHARD_RESULTS.write_tsv(path, UNIT_SESSION.SELECTOR_FIELDS, rows)

        def tamper_route_artifact(fixture):
            (fixture.route_directory() / "semantic-gate.tsv").write_text("changed\n")

        def empty_route_plan(fixture):
            SHARD_RESULTS.write_tsv(fixture.root / "route-plan.tsv", UNIT_SESSION.ROUTE_PLAN_FIELDS, [])

        def misname_attempt(fixture):
            rows = fixture.read("unit-session.tsv")
            rows[0]["unit_attempt_id"] = "host-session-1"
            SHARD_RESULTS.write_tsv(fixture.root / "unit-session.tsv", UNIT_SESSION.UNIT_SESSION_FIELDS, rows)

        cases = (
            (COPY_UNIT, None, swap_copy_role, "role is 'owner', expected 'copy'"),
            (COPY_UNIT, None, drift_calibration, "drifted 0.0600"),
            (COPY_UNIT, None, drop_unit_copy, "differs from the checked-in rows"),
            (COPY_UNIT, add_unknown_role, None, "differs from the checked-in rows"),
            (COPY_UNIT, override_joni_window, None, "overrides the frozen Joni JMH window: 7ms"),
            (COPY_UNIT, shorten_selector_window, None, "differs from the selectors of the frozen protocol"),
            (COPY_UNIT, None, tamper_route_artifact, "checksum mismatch"),
            (COPY_UNIT, None, empty_route_plan, "route-plan.tsv is not the unit's plan for replica"),
            (COPY_UNIT, None, misname_attempt, "unit-session.tsv unit_attempt_id must be <host_session>/<lease_seq>"),
            (COPY_UNIT, lambda fixture: fixture.replace_semantic_report(), None,
             "are not the files of its semantic receipt"),
            (REPRESENTATIVE_UNIT, lambda fixture: fixture.drop_representative(), None, "ran representatives"),
        )
        for unit, before_combine, after_combine, message in cases:
            with self.subTest(message=message), tempfile.TemporaryDirectory() as temporary_directory:
                fixture = UnitFixture(Path(temporary_directory), *unit)
                if before_combine:
                    before_combine(fixture)
                fixture.combine()
                if after_combine:
                    after_combine(fixture)
                with self.assertRaisesRegex(ValueError, re.escape(message)):
                    UNIT_SESSION.validate_unit_results(fixture.root)


class UnitFixture:
    """A unit attempt's directory as run-unit.sh leaves it, with fixture measurements."""

    # The route's JMH process settings, as run-shard.sh records them.
    SMOKE = ("5", "10", "10", "1s")
    SMOKE_METADATA = ("jmh_process_count", "jmh_process_warmup_iterations", "jmh_process_measurement_iterations",
                      "jmh_iteration_time")

    def __init__(self, root, shard_id, unit_index):
        self.root = root
        units = UNIT_SESSION.work_units.load(BASELINE)
        self.unit = next(unit for unit in units.values()
                         if (unit["shard_id"], unit["unit_index"]) == (shard_id, unit_index))
        self.protocols = UNIT_SESSION.row_protocol.load(BASELINE / "row-protocol.tsv")
        self.replica = min([replica for replica, _ in self.unit["representatives"]] or ["1"])
        with contextlib.redirect_stdout(io.StringIO()):
            UNIT_SESSION.plan(type("Arguments", (), {
                "unit": self.unit["unit_id"], "replica": self.replica, "output_directory": root})())
        representatives = UNIT_SESSION.row_protocol.read_tsv(BASELINE / "protocol-representatives.tsv")
        held = UNIT_SESSION.unit_representatives(self.unit, self.replica)
        (root / "unit-capacity.txt").write_text(
            "memory_total_bytes=34359738368\nmemory_available_before_bytes=30000000000\n")
        self.write_calibration(100, 101)
        for route, _, systems, share, calibration in UNIT_SESSION.plan_routes(BASELINE, self.unit, self.replica):
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
            "phase\tscore\tbefore_after_drift\n"
            f"before\t{before}\t{drift}\nafter\t{after}\t{drift}\n")

    def create_route(self, route, systems, share, calibration, representatives):
        directory = self.root / "routes" / route
        (directory / "semantic-reports").mkdir(parents=True)
        SHARD_RESULTS.write_tsv(directory / "route-session.tsv", HOST_SESSION.SESSION_FIELDS, [{
            "schema_version": "2", "campaign_id": "campaign", "platform": "r8i",
            "shard_id": self.unit["shard_id"], "replica_id": self.replica, "instance_id": "i-1",
            "host_epoch": "epoch-1", "architecture": "intel", "instance_type": "r8i.2xlarge",
            "availability_zone": "us-west-2a", "systems": ",".join(sorted(systems)),
        }])
        observed = directory / "observed-rows.tsv"
        SHARD_RESULTS.write_tsv(observed, SHARD_RESULTS.OBSERVED_FIELDS, [{
            "row_id": row["row_id"], "shard_id": row["shard_id"], "system": row["system"], "score": "1",
            "score_unit": "ns/op", "allocation_bytes": "0", "result_checksum": "0" * 64, "outcome": "accepted",
        } for row in sorted(share, key=lambda row: row["row_id"])])
        (directory / "observed-rows.tsv.sha256").write_text(
            hashlib.sha256(observed.read_bytes()).hexdigest() + "  observed-rows.tsv\n")
        for name in ("semantic-gate.tsv", "semantic-evidence.tsv", "native-bracket.tsv"):
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
            "protocol=qualification\nbaseline_joni_jmh_time=default\n" +
            "".join(f"{name}={value}\n" for name, value in zip(self.SMOKE_METADATA, self.SMOKE)) +
            f"semantic_receipt=measured\nsemantic_receipt_sha256={hashlib.sha256(receipt.read_bytes()).hexdigest()}\n")

    def replace_semantic_report(self):
        (self.route_directory() / "semantic-reports" / "TEST-Fixture.xml").write_text("<testsuite>other</testsuite>\n")

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

    def write_observed(self, rows):
        SHARD_RESULTS.write_tsv(self.root / "observed-rows.tsv", UNIT_SESSION.UNIT_OBSERVED_FIELDS, rows)

    def combine(self):
        with contextlib.redirect_stdout(io.StringIO()):
            UNIT_SESSION.combine(type("Arguments", (), {
                "unit_directory": self.root, "unit_attempt": "host-session-1/3", "region": "us-west-2"})())


if __name__ == "__main__":
    unittest.main()
