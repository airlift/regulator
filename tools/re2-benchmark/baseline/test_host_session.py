#!/usr/bin/env python3

import csv
import hashlib
import importlib.util
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parent
sys.path.insert(0, str(BASELINE))


def load_module(name, path):
    specification = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


HOST_SESSION = load_module("host_session", BASELINE / "host_session.py")
SHARD_RESULTS = load_module("host_session_shard_results", BASELINE / "shard_results.py")


class TestHostSession(unittest.TestCase):
    def testHostManifestHeapReachesAcceptance(self):
        for heap in (None, "2g", "12G"):
            with self.subTest(heap=heap), tempfile.TemporaryDirectory() as temporary_directory:
                fixture = HostFixture(Path(temporary_directory))
                effective_heap = heap or "8g"
                for route in ("native-access", "object-row"):
                    metadata = fixture.routes / route / "run-metadata.txt"
                    metadata.write_text(metadata.read_text().replace("heap_size=8g", f"heap_size={effective_heap}"))
                fixture.write_host_manifest(heap)
                fixture.combine()

                for route in ("native-access", "object-row"):
                    metadata = fixture.routes / route / "run-metadata.txt"
                    metadata.write_text(metadata.read_text().replace(f"heap_size={effective_heap}", "heap_size=4g"))
                with self.assertRaisesRegex(ValueError, "environment heap_size differs"):
                    fixture.combine()

    def testCurrentDispatchExactlyCoversEveryShardAndKeepsNativeFirst(self):
        manifest = SHARD_RESULTS.load_manifest(BASELINE / "rows.tsv")
        shards = sorted({row["shard_id"] for row in manifest})
        for shard in shards:
            dispatch = HOST_SESSION.load_dispatch(BASELINE / "shard-dispatch.tsv", shard)
            routes = HOST_SESSION.applicable_routes(dispatch)
            self.assertEqual("native-access", routes[0][0])
            route_systems = set().union(*(systems for _, _, systems in routes))
            manifest_systems = {row["system"] for row in manifest if row["shard_id"] == shard}
            self.assertEqual(manifest_systems, route_systems, shard)

    def testCombineProducesOneCanonicalSessionAndExactRows(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = HostFixture(Path(temporary_directory))

            fixture.combine()

            with fixture.session.open(newline="", encoding="utf-8") as input_file:
                session = next(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual("engine-native,engine-object,native-after,native-before", session["systems"])
            with fixture.observed.open(newline="", encoding="utf-8") as input_file:
                observed = list(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual(4, len(observed))
            with fixture.evidence.open(newline="", encoding="utf-8") as input_file:
                evidence = list(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual(24, len(evidence))
            self.assertEqual(
                {"route", "artifact", "verification", "sha256"},
                set(evidence[0]))
            capacity = next(row for row in evidence if row["artifact"] == "host-capacity.txt")
            self.assertEqual("capacity-prefix-sha256", capacity["verification"])
            self.assertTrue(any(
                row["artifact"] == "routes/native-access/normalized-output.tsv"
                for row in evidence))

    def testCombineRejectsDuplicateRowsAcrossRoutes(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = HostFixture(Path(temporary_directory))
            duplicate = fixture.read_observed("native-access")[0]
            fixture.append_observed("object-row", duplicate)

            with self.assertRaisesRegex(ValueError, "duplicates"):
                fixture.combine()

    def testCombineRejectsMissingRows(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = HostFixture(Path(temporary_directory))
            rows = fixture.read_observed("object-row")
            fixture.write_observed("object-row", rows[:-1])

            with self.assertRaisesRegex(ValueError, "missing"):
                fixture.combine()

    def testCombinePreservesExpectedRebarSemanticMismatch(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = HostFixture(Path(temporary_directory))
            fixture.add_rebar_semantic_mismatch("native-access")

            fixture.combine()

            outcomes_path = fixture.root / "rebar-outcomes.tsv"
            with outcomes_path.open(newline="", encoding="utf-8") as input_file:
                outcomes = list(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual(1, len(outcomes))
            self.assertEqual("semantic-mismatch", outcomes[0]["outcome"])

            observed = fixture.read_host_observed()
            self.assertEqual("semantic-mismatch", observed[0]["outcome"])
            with fixture.evidence.open(newline="", encoding="utf-8") as input_file:
                evidence = list(csv.DictReader(input_file, delimiter="\t"))
            self.assertTrue(any(
                row["artifact"] == "rebar-outcomes.tsv"
                for row in evidence))

    def testCombineCanSelectOneRouteAndOnlyItsSystems(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = HostFixture(Path(temporary_directory))

            fixture.combine("object-row")

            with fixture.session.open(newline="", encoding="utf-8") as input_file:
                session = next(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual("engine-object", session["systems"])
            observed = fixture.read_host_observed()
            self.assertEqual(["engine-object"], [row["system"] for row in observed])


class HostFixture:
    def write_host_manifest(self, heap):
        source = (BASELINE.parent / "aws" / "run-host.sh").read_text()
        start = source.index("    {\n        printf 'manifest_version=1")
        end_marker = '} > "${RESULT_DIR}/environment-manifest.txt"'
        end = source.index(end_marker, start) + len(end_marker)
        manifest_writer = source[start:end]
        # Execute the production writer, stubbing only unrelated machine probes.
        environment = os.environ.copy()
        for name in re.findall(r"\$\{([A-Za-z_][A-Za-z_0-9]*)", manifest_writer):
            environment[name] = "fixture"
        environment.update({
            "RESULT_DIR": str(self.root),
            "verification_status": "unverified",
            "REGULATOR_SNAPSHOT_COMMIT": "a" * 40,
            "REGULATOR_SNAPSHOT_SHA256": "c" * 64,
        })
        environment.pop("BENCHMARK_HEAP_SIZE", None)
        if heap is not None:
            environment["BENCHMARK_HEAP_SIZE"] = heap
        subprocess.run(
            ["bash", "-eu", "-c", "package_version() { echo fixture; }\njava() { echo fixture; }\n" + manifest_writer],
            env=environment, check=True, capture_output=True, text=True)

    def __init__(self, root):
        self.root = root
        self.routes = root / "routes"
        self.manifest = root / "rows.tsv"
        self.dispatch = root / "dispatch.tsv"
        self.expected = root / "expected.tsv"
        self.observed = root / "observed.tsv"
        self.session = root / "session.tsv"
        self.evidence = root / "route-evidence.tsv"
        rows = []
        systems = ("engine-native", "native-before", "native-after", "engine-object")
        for system in systems:
            rows.append({
                "row_id": f"shard/row/{system}",
                "shard_id": "shard",
                "suite": "suite",
                "system": system,
                "benchmark": "benchmark",
                "parameters": "-",
                "comparator": "comparator",
                "expected_result": "result",
                "allocation_contract": "recorded",
                "workload_checksum": "workload",
            })
        SHARD_RESULTS.write_tsv(self.manifest, SHARD_RESULTS.MANIFEST_FIELDS, rows)
        SHARD_RESULTS.write_tsv(self.dispatch, HOST_SESSION.DISPATCH_FIELDS, [{
            "shard_id": "shard",
            "native_access_handler": "jmh",
            "native_access_systems": "engine-native,native-before,native-after",
            "object_row_handler": "jmh",
            "object_row_systems": "engine-object",
            "semantic_tests": "TestExample",
            "blocker": "-",
        }])
        (self.root / "host-capacity.txt").write_text(
            "memory_total_bytes=34359738368\n"
            "memory_available_before_bytes=30000000000\n")
        self.create_route("native-access", rows[:3], "engine-native,native-after,native-before", True)
        self.create_route("object-row", rows[3:], "engine-object", False)

    def create_route(self, route, manifest_rows, systems, native_bracket):
        directory = self.routes / route
        directory.mkdir(parents=True)
        session = {
            "schema_version": "2",
            "campaign_id": "campaign",
            "platform": "c8i",
            "shard_id": "shard",
            "replica_id": "1",
            "instance_id": "i-1",
            "host_epoch": "epoch-1",
            "architecture": "intel",
            "instance_type": "c8i.2xlarge",
            "availability_zone": "us-west-2a",
            "systems": systems,
        }
        SHARD_RESULTS.write_tsv(directory / "route-session.tsv", HOST_SESSION.SESSION_FIELDS, [session])
        observed = [{
            "row_id": row["row_id"],
            "shard_id": row["shard_id"],
            "system": row["system"],
            "score": "1",
            "score_unit": "ns/op",
            "allocation_bytes": "0",
            "result_checksum": "0" * 64,
            "outcome": "accepted",
        } for row in manifest_rows]
        SHARD_RESULTS.write_tsv(directory / "observed-rows.tsv", SHARD_RESULTS.OBSERVED_FIELDS, observed)
        observed_path = directory / "observed-rows.tsv"
        (directory / "observed-rows.tsv.sha256").write_text(
            hashlib.sha256(observed_path.read_bytes()).hexdigest() + "  observed-rows.tsv\n")
        (directory / "semantic-gate.tsv").write_text("evidence\n")
        (directory / "semantic-evidence.tsv").write_text("test\tstatus\nfixture\tpassed\n")
        (directory / "calibration.tsv").write_text("evidence\n")
        if route == "native-access":
            (directory / "normalized-output.tsv").write_text("normalized\n")
        (directory / "run-metadata.txt").write_text(
            f"candidate_commit={'a' * 40}\n"
            f"engine_tree={'b' * 40}\n"
            "heap_size=8g\n"
            "protocol_qualification_required=false\n"
            "protocol_representative_count=0\n")
        (directory / "raw").mkdir()
        (directory / "logs").mkdir()
        raw = directory / "raw" / "result.json"
        log = directory / "logs" / "benchmark.log"
        raw.write_text("[]\n")
        log.write_text("complete\n")
        (directory / "raw-artifacts.sha256").write_text(
            f"{hashlib.sha256(log.read_bytes()).hexdigest()}  logs/benchmark.log\n"
            f"{hashlib.sha256(raw.read_bytes()).hexdigest()}  raw/result.json\n")
        if native_bracket:
            (directory / "native-bracket.tsv").write_text("evidence\n")
        (directory / "status.txt").write_text("status=complete\n")

    def read_observed(self, route):
        with (self.routes / route / "observed-rows.tsv").open(newline="", encoding="utf-8") as input_file:
            return list(csv.DictReader(input_file, delimiter="\t"))

    def read_host_observed(self):
        with self.observed.open(newline="", encoding="utf-8") as input_file:
            return list(csv.DictReader(input_file, delimiter="\t"))

    def add_rebar_semantic_mismatch(self, route):
        observed = self.read_observed(route)
        observed[0]["score"] = ""
        observed[0]["score_unit"] = ""
        observed[0]["allocation_bytes"] = "not-measured"
        observed[0]["outcome"] = "semantic-mismatch"
        self.write_observed(route, observed)
        outcomes_path = self.routes / route / "raw" / "rebar-outcomes.tsv"
        SHARD_RESULTS.write_tsv(
            outcomes_path,
            SHARD_RESULTS.REBAR_OUTCOME_FIELDS,
            [{
                "row_id": observed[0]["row_id"],
                "suite": "suite",
                "system": observed[0]["system"],
                "benchmark": "benchmark",
                "model": "model",
                "outcome": "semantic-mismatch",
                "detail": "expected fixture mismatch",
            }])
        with (self.routes / route / "raw-artifacts.sha256").open("a", encoding="utf-8") as output_file:
            output_file.write(
                f"{hashlib.sha256(outcomes_path.read_bytes()).hexdigest()}  raw/rebar-outcomes.tsv\n")

    def write_observed(self, route, rows):
        observed_path = self.routes / route / "observed-rows.tsv"
        SHARD_RESULTS.write_tsv(observed_path, SHARD_RESULTS.OBSERVED_FIELDS, rows)
        (self.routes / route / "observed-rows.tsv.sha256").write_text(
            hashlib.sha256(observed_path.read_bytes()).hexdigest() + "  observed-rows.tsv\n")

    def append_observed(self, route, row):
        rows = self.read_observed(route)
        rows.append(row)
        self.write_observed(route, rows)

    def combine(self, route=None):
        arguments = type("Arguments", (), {
            "manifest": self.manifest,
            "dispatch": self.dispatch,
            "shard": "shard",
            "routes_directory": self.routes,
            "expected": self.expected,
            "observed": self.observed,
            "session": self.session,
            "evidence": self.evidence,
            "route": route,
        })()
        HOST_SESSION.combine_routes(arguments)


if __name__ == "__main__":
    unittest.main()
