import argparse
import csv
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASELINE))


def load_module(name, path):
    specification = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(specification)
    sys.modules[name] = module
    specification.loader.exec_module(module)
    return module


aggregate_results = load_module("aggregate_results", BASELINE / "aggregate_results.py")
render_report = load_module("render_report", BASELINE / "render_report.py")
host_session = load_module("aggregate_host_session", BASELINE / "host_session.py")

from acceptance import validate_confirmation_host_results, validate_host_results  # noqa: E402


def write_tsv(path, fields, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def read_tsv(path):
    with path.open(newline="", encoding="utf-8") as input_file:
        return list(csv.DictReader(input_file, delimiter="\t"))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class TestLikeComparisonCoverage(unittest.TestCase):
    def test_lifecycle_sql_comparison_does_not_require_optional_dfa(self):
        manifest = read_tsv(BASELINE / 'rows.tsv')
        for shard in ('like-compile', 'like-single-use', 'trino-like', 'like-dfa-single-use'):
            with self.subTest(shard=shard):
                rows = [row for row in manifest if row['shard_id'] == shard]
                expected = {row['row_id'] for row in rows if row['system'] == 'regulator'}
                specs = aggregate_results.comparison_specs(rows)
                self.assertEqual(expected, {candidate['row_id'] for _, candidate, comparator, _ in specs
                                            if comparator == 'trino-sql'})
                dfa_expected = expected if any(row['system'] == 'trino-optimized' for row in rows) else set()
                self.assertEqual(dfa_expected, {candidate['row_id'] for _, candidate, comparator, _ in specs
                                                if comparator == 'trino-optimized'})
                self.assertEqual(len(expected) + len(dfa_expected), len(specs))


class CampaignFixture:
    platforms = {
        "r8i": ("intel", "r8i.large"),
        "r8g": ("arm", "r8g.large"),
        "r9g": ("arm", "r9g.large"),
    }

    def __init__(self, root):
        self.root = root
        self.manifest = root / "rows.tsv"
        self.platform_file = root / "platforms.tsv"
        self.shard_file = root / "shards.tsv"
        self.dispatch = root / "dispatch.tsv"
        self.primary_receipts = root / "accepted-sessions.tsv"
        self.confirmation_receipts = root / "confirmation-sessions.tsv"
        self.artifacts = root / "artifacts"
        self.output = root / "output"
        self.events = root / "campaign-events.tsv"
        self.artifact_index = root / "artifact-index.tsv"
        self.rebar_exclusions = root / "rebar-exclusions.tsv"
        self.campaign_artifact = root / "campaign-artifact.tsv"
        self.campaign_source_manifest = root / "campaign-artifact.tsv.source-manifest.tsv"
        self.rows = self._manifest_rows()
        write_tsv(self.manifest, aggregate_results.MANIFEST_FIELDS, self.rows)
        write_tsv(self.platform_file, ("platform", "architecture", "instance_type", "vcpus",
                                       "concurrency_instance_type", "concurrency_vcpus"), (
            {"platform": platform, "architecture": values[0], "instance_type": values[1],
             "vcpus": "2", "concurrency_instance_type": platform + ".2xlarge", "concurrency_vcpus": "8"}
            for platform, values in self.platforms.items()))
        write_tsv(self.shard_file, ("shard_id", "description"), ({"shard_id": "only", "description": "test"},))
        native_systems = sorted({row["system"] for row in self.rows if row["system"] != "regulator-object-row"})
        write_tsv(self.dispatch, host_session.DISPATCH_FIELDS, ({
            "shard_id": "only",
            "native_access_handler": "fixture",
            "native_access_systems": ",".join(native_systems),
            "object_row_handler": "fixture",
            "object_row_systems": "regulator-object-row",
            "semantic_tests": "fixture",
            "blocker": "-",
        },))
        self.receipts = []
        self.confirmations = []
        self.session_directories = {}
        for platform in self.platforms:
            for replica in (1, 2, 3):
                self.add_session(platform, replica)
        self.write_ledgers()
        write_tsv(self.events, aggregate_results.EVENT_FIELDS[:-1], ({
            "event_type": "interruption", "campaign_id": "test-campaign", "platform": "r8g",
            "shard_id": "only", "replica_id": "2", "instance_id": "i-interrupted",
            "host_epoch": "interrupted-epoch", "reason": "Spot interruption",
            "artifact_uri": "s3://bucket/rejected/interrupted.tar.zst", "artifact_sha256": "e" * 64,
        },))
        self.write_artifact_index()
        write_tsv(self.rebar_exclusions, ("name", "model", "definition", "reason"), ({
            "name": "unicode/unsupported", "model": "grep",
            "definition": "unicode/unsupported.toml", "reason": "no-native-re2-semantics",
        },))
        self.write_campaign_artifact()

    def write_campaign_artifact(self):
        source_rows = []
        for path in sorted(
                (item for item in self.artifacts.rglob("*") if item.is_file()),
                key=lambda item: str(item.relative_to(self.artifacts))):
            source_rows.append({
                "path": "campaign-results/" + str(path.relative_to(self.artifacts)),
                "size": str(path.stat().st_size),
                "sha256": digest(path),
            })
        write_tsv(self.campaign_source_manifest, aggregate_results.SOURCE_MANIFEST_FIELDS, source_rows)
        write_tsv(self.campaign_artifact, aggregate_results.CAMPAIGN_ARTIFACT_FIELDS, ({
            "s3_uri": "s3://baseline/campaign.tar.gz", "version_id": "immutable-version",
            "archive_sha256": "9" * 64, "candidate_commit": "a" * 40,
            "candidate_ref": "refs/benchmarks/test", "campaign_id": "test-campaign",
            "source_directory": str(self.artifacts.resolve()), "file_count": str(len(source_rows)),
            "source_manifest_sha256": digest(self.campaign_source_manifest),
        },))

    def _row(self, logical, suite, system, benchmark, parameters, comparator,
             allocation="recorded", expected="benchmark-self-check"):
        return {
            "row_id": f"only/{logical}/{system}", "shard_id": "only", "suite": suite,
            "system": system, "benchmark": benchmark, "parameters": parameters,
            "comparator": comparator, "expected_result": expected,
            "allocation_contract": allocation, "workload_checksum": "fixture-workload",
        }

    def _manifest_rows(self):
        rows = []
        for system in ("native-re2-before", "native-re2-after", "regulator-native-access", "regulator-object-row"):
            rows.append(self._row("native/search", "traditional", system, "search", "textSize=100",
                                  "NativeSearch", "allocation-free", "paired-native-result"))
        for system in ("joni", "regulator-native-access", "regulator-object-row"):
            rows.append(self._row("joni/count", "trino-public-operations", system, "count", "sourceLength=100",
                                  "joni", "operation-dependent", "joni-differential"))
        for system in ("regulator", "trino-optimized", "trino-sql"):
            rows.append(self._row("like/contains", "trino-like", system, "matches", "scenario=contains",
                                  "trino-like", "allocation-free", "trino-differential"))
        for size in (10, 100, 1000):
            for system in ("regulator-native-access", "regulator-object-row"):
                rows.append(self._row(f"scale/{size}", "accepted-routes", system, "scan",
                                      f"textSize={size};workload=ascii", "general-route-control", "allocation-free"))
        for corpus, suite in (("curated", "rebar-curated"), ("extended", "rebar-extended")):
            for system in ("native-re2-before", "native-re2-after", "regulator-native-access", "regulator-object-row"):
                rows.append(self._row(f"{corpus}/work/count", suite, system, f"{corpus}/work",
                                      f"model=count;workload={corpus}/work", "pinned-native-re2",
                                      "recorded", "native-differential"))
        return sorted(rows, key=lambda row: row["row_id"])

    def score(self, row, platform, replica):
        multiplier = {1: 0.99, 2: 1.0, 3: 1.01, 4: 1.0}[replica]
        system = row["system"]
        logical = row["row_id"].rsplit("/", 1)[0]
        if "/scale/" in logical:
            size = int(dict(item.split("=", 1) for item in row["parameters"].split(";"))["textSize"])
            base = size if system == "regulator-native-access" else size * 1.25
        elif "/native/search/" in row["row_id"]:
            base = {"native-re2-before": 100, "native-re2-after": 102,
                    "regulator-native-access": 80, "regulator-object-row": 120}[system]
        elif "/joni/count/" in row["row_id"]:
            base = {"joni": 100, "regulator-native-access": 90, "regulator-object-row": 110}[system]
        elif "/like/contains/" in row["row_id"]:
            base = {"regulator": 50, "trino-optimized": 100, "trino-sql": 25}[system]
        else:
            base = {"native-re2-before": 100, "native-re2-after": 102,
                    "regulator-native-access": 95, "regulator-object-row": 125}[system]
        return base * multiplier

    def contract_identity(self, row):
        return hashlib.sha256((row["row_id"] + "\0contract").encode()).hexdigest()

    def create_route(self, directory, route, session, systems, platform, replica,
                     calibration_drift, native_drift):
        route_directory = directory / "routes" / route
        route_directory.mkdir(parents=True)
        route_session = {**session, "systems": ",".join(sorted(systems))}
        write_tsv(route_directory / "route-session.tsv", aggregate_results.SESSION_FIELDS, (route_session,))
        route_rows = [row for row in self.rows if row["system"] in systems]
        observed = []
        for row in route_rows:
            allocation = "0" if row["allocation_contract"] == "allocation-free" else "16"
            observed.append({
                "row_id": row["row_id"], "shard_id": row["shard_id"], "system": row["system"],
                "score": f"{self.score(row, platform, replica):.12g}", "score_unit": "ns/op",
                "allocation_bytes": allocation, "result_checksum": self.contract_identity(row),
                "outcome": "accepted",
            })
        observed_path = route_directory / "observed-rows.tsv"
        write_tsv(observed_path, aggregate_results.OBSERVED_FIELDS, observed)
        (route_directory / "observed-rows.tsv.sha256").write_text(
            f"{digest(observed_path)}  observed-rows.tsv\n", encoding="utf-8")
        before = 100.0
        after = before * (1 + calibration_drift)
        calibration = ({
            "phase": "before", "benchmark": "calibration", "parameters": "textSize=32768",
            "score": str(before), "score_unit": "ns/op", "sample_count": "5",
            "coefficient_of_variation": "0.01", "raw_result": "before.json",
            "before_after_drift": str(calibration_drift),
        }, {
            "phase": "after", "benchmark": "calibration", "parameters": "textSize=32768",
            "score": str(after), "score_unit": "ns/op", "sample_count": "5",
            "coefficient_of_variation": "0.01", "raw_result": "after.json",
            "before_after_drift": str(calibration_drift),
        })
        write_tsv(route_directory / "calibration.tsv", tuple(calibration[0]), calibration)
        if {"native-re2-before", "native-re2-after"}.issubset(systems):
            native_before = [row for row in route_rows if row["system"] == "native-re2-before"]
            comparators_are_unique = len({row["comparator"] for row in native_before}) == len(native_before)
            bracket = []
            for row in native_before:
                benchmark = (row["comparator"] if comparators_are_unique else
                             f"{row['benchmark']}[{row['parameters']}]")
                bracket.append({
                    "benchmark": benchmark, "before_score_ns": "100",
                    "after_score_ns": str(100 * (1 + native_drift)),
                    "before_after_drift": str(native_drift),
                })
            write_tsv(route_directory / "native-bracket.tsv", tuple(bracket[0]), bracket)
        (route_directory / "semantic-gate.tsv").write_text("contract=accepted\n", encoding="utf-8")
        (route_directory / "semantic-evidence.tsv").write_text(
            "test\tstatus\nfixture\tpassed\n", encoding="utf-8")
        (route_directory / "run-metadata.txt").write_text(
            f"candidate_commit={'a' * 40}\n"
            f"engine_tree={'b' * 40}\n"
            "heap_size=8g\n"
            f"manifest_sha256={digest(self.manifest)}\n"
            f"route={route}\n"
            "protocol_qualification_required=false\n"
            "protocol_representative_count=0\n"
            "joni_comparator_order=forward\n",
            encoding="utf-8")
        (route_directory / "raw").mkdir()
        (route_directory / "logs").mkdir()
        raw = route_directory / "raw" / "measurement.json"
        log = route_directory / "logs" / "measurement.log"
        raw.write_text("[]\n", encoding="utf-8")
        log.write_text("complete\n", encoding="utf-8")
        (route_directory / "raw-artifacts.sha256").write_text(
            f"{digest(log)}  logs/measurement.log\n{digest(raw)}  raw/measurement.json\n",
            encoding="utf-8")
        (route_directory / "status.txt").write_text("status=complete\n", encoding="utf-8")

    def add_session(self, platform, replica, *, calibration_drift=0.01, native_drift=0.02):
        architecture, instance_type = self.platforms[platform]
        epoch = f"{platform}-epoch-{replica}"
        instance = f"i-{platform}-{replica}"
        directory = self.artifacts / platform / f"replica-{replica}"
        directory.mkdir(parents=True, exist_ok=True)
        session = {
            "schema_version": "2", "campaign_id": "test-campaign", "platform": platform,
            "shard_id": "only", "replica_id": str(replica), "instance_id": instance,
            "host_epoch": epoch, "architecture": architecture, "instance_type": instance_type,
            "availability_zone": f"us-west-2{chr(ord('a') + replica - 1)}",
            "systems": ",".join(sorted({row["system"] for row in self.rows})),
        }
        (directory / "environment-manifest.txt").write_text(
            "verification_status=engineering-snapshot\n"
            f"regulator_commit={'a' * 40}\n"
            "benchmark_heap_size=8g\n"
            f"regulator_archive_sha256={'c' * 64}\n"
            f"comparator_manifest_sha256={'d' * 64}\n"
            "joni_evidence_scope=pinned\n",
            encoding="utf-8")
        (directory / "environment.txt").write_text("fixture environment\n", encoding="utf-8")
        (directory / "route-plan.tsv").write_text(
            "native-access\tfixture\nobject-row\tfixture\n", encoding="utf-8")
        (directory / "host-capacity.txt").write_text(
            "memory_total_bytes=34359738368\n"
            "memory_available_before_bytes=30000000000\n",
            encoding="utf-8")
        native_systems = {row["system"] for row in self.rows if row["system"] != "regulator-object-row"}
        self.create_route(
            directory, "native-access", session, native_systems, platform, replica,
            calibration_drift, native_drift)
        self.create_route(
            directory, "object-row", session, {"regulator-object-row"}, platform, replica,
            calibration_drift, native_drift)
        host_session.combine_routes(argparse.Namespace(
            manifest=self.manifest,
            dispatch=self.dispatch,
            shard="only",
            routes_directory=directory / "routes",
            expected=directory / "expected-rows.tsv",
            observed=directory / "observed-rows.tsv",
            session=directory / "session.tsv",
            evidence=directory / "route-evidence.tsv"))
        with (directory / "host-capacity.txt").open("a", encoding="utf-8") as capacity:
            capacity.write(
                "wall_seconds=120\n"
                "maximum_resident_kibibytes=1048576\n"
                "exit_status=0\n"
                "memory_available_after_bytes=29000000000\n")
        validator = validate_confirmation_host_results if replica == 4 else validate_host_results
        receipt = validator(self.manifest, directory / "session.tsv", directory / "observed-rows.tsv")
        write_tsv(directory / "acceptance-receipt.tsv", aggregate_results.RECEIPT_FIELDS, (receipt,))
        (directory / "status.txt").write_text("status=complete\n", encoding="utf-8")
        self.session_directories[(platform, replica)] = directory
        (self.confirmations if replica == 4 else self.receipts).append(receipt)
        if self.campaign_artifact.exists():
            self.write_campaign_artifact()

    def rewrite_receipt(self, platform, replica):
        directory = self.session_directories[(platform, replica)]
        observed_path = directory / "observed-rows.tsv"
        evidence_path = directory / "route-evidence.tsv"
        evidence = read_tsv(evidence_path)
        next(row for row in evidence if row["artifact"] == "observed-rows.tsv")["sha256"] = digest(observed_path)
        write_tsv(evidence_path, host_session.EVIDENCE_FIELDS, evidence)
        collection = self.confirmations if replica == 4 else self.receipts
        receipt = next(row for row in collection if row["platform"] == platform and row["replica_id"] == str(replica))
        receipt["observed_rows_sha256"] = digest(observed_path)
        receipt["evidence_manifest_sha256"] = digest(evidence_path)
        write_tsv(directory / "acceptance-receipt.tsv", aggregate_results.RECEIPT_FIELDS, (receipt,))
        if self.campaign_artifact.exists():
            self.write_campaign_artifact()

    def rewrite_evidence_artifact(self, platform, replica, artifact):
        directory = self.session_directories[(platform, replica)]
        evidence_path = directory / "route-evidence.tsv"
        evidence = read_tsv(evidence_path)
        next(row for row in evidence if row["artifact"] == artifact)["sha256"] = digest(directory / artifact)
        write_tsv(evidence_path, host_session.EVIDENCE_FIELDS, evidence)
        collection = self.confirmations if replica == 4 else self.receipts
        receipt = next(row for row in collection if row["platform"] == platform and row["replica_id"] == str(replica))
        receipt["evidence_manifest_sha256"] = digest(evidence_path)
        write_tsv(directory / "acceptance-receipt.tsv", aggregate_results.RECEIPT_FIELDS, (receipt,))
        self.write_ledgers()
        if self.campaign_artifact.exists():
            self.write_campaign_artifact()

    def write_ledgers(self):
        write_tsv(self.primary_receipts, aggregate_results.RECEIPT_FIELDS, self.receipts)
        write_tsv(self.confirmation_receipts, aggregate_results.RECEIPT_FIELDS, self.confirmations)

    def write_artifact_index(self):
        fields = aggregate_results.SESSION_FIELDS + ("artifact_uri", "artifact_sha256", "retrieved_at")
        rows = []
        for receipt in self.receipts + self.confirmations:
            rows.append({
                **{field: receipt[field] for field in aggregate_results.SESSION_FIELDS},
                "artifact_uri": f"s3://bucket/{receipt['host_epoch']}.tar.zst",
                "artifact_sha256": hashlib.sha256(receipt["host_epoch"].encode()).hexdigest(),
                "retrieved_at": "2026-08-02T00:00:00Z",
            })
        write_tsv(self.artifact_index, fields, rows)

    def arguments(self):
        return argparse.Namespace(
            manifest=self.manifest, platforms=self.platform_file, shards=self.shard_file,
            primary_receipts=[self.primary_receipts], confirmation_receipts=[self.confirmation_receipts],
            artifacts_root=[self.artifacts], session_dir=[], events=[self.events],
            rebar_exclusions=[self.rebar_exclusions],
            rebar_joni_applicability=None,
            artifact_index=[self.artifact_index], output_dir=self.output,
            campaign_artifact=self.campaign_artifact,
            expected_manifest_rows=len(self.rows))

    def mutate_observed(self, platform, replica, row_id, field, value):
        directory = self.session_directories[(platform, replica)]
        path = directory / "observed-rows.tsv"
        rows = read_tsv(path)
        next(row for row in rows if row["row_id"] == row_id)[field] = str(value)
        write_tsv(path, aggregate_results.OBSERVED_FIELDS, rows)
        self.rewrite_receipt(platform, replica)
        self.write_ledgers()


class TestAggregateResults(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.fixture = CampaignFixture(self.root)

    def tearDown(self):
        self.temporary.cleanup()

    def aggregate(self):
        return aggregate_results.aggregate(self.fixture.arguments())

    def test_scoped_reduction_keeps_manifest_identity_and_complete_hosts(self):
        arguments = self.fixture.arguments()
        arguments.selected_shards = ['only']
        summary = aggregate_results.aggregate(arguments)
        self.assertEqual(summary['primary_sessions'], 9)
        self.assertEqual(summary['host_rows'], len(self.fixture.rows) * 9)
        for selected in ([], ['unknown'], ['only', 'only']):
            arguments.selected_shards = selected
            with self.assertRaisesRegex(aggregate_results.ReductionError, 'selected shards'):
                aggregate_results.aggregate(arguments)

    def test_rejects_mixed_heap_receipts(self):
        self.fixture.receipts[0]["heap_size"] = "2g"
        self.fixture.write_ledgers()
        with self.assertRaisesRegex(aggregate_results.ReductionError, "multiple candidate identities"):
            self.aggregate()

    def test_rejects_forged_heap_even_when_all_receipts_agree(self):
        for receipt in self.fixture.receipts:
            receipt["heap_size"] = "2g"
            self.fixture.rewrite_receipt(receipt["platform"], int(receipt["replica_id"]))
        self.fixture.write_ledgers()
        with self.assertRaisesRegex(aggregate_results.ReductionError, "heap_size"):
            self.aggregate()

    def test_rejects_environment_route_heap_disagreement(self):
        directory = self.fixture.session_directories[("r8i", 1)]
        environment = directory / "environment-manifest.txt"
        environment.write_text(environment.read_text().replace("benchmark_heap_size=8g", "benchmark_heap_size=2g"))
        self.fixture.rewrite_evidence_artifact("r8i", 1, "environment-manifest.txt")
        with self.assertRaisesRegex(aggregate_results.ReductionError, "heap_size"):
            self.aggregate()

    def test_rejects_route_heap_disagreement_and_missing_heap(self):
        directory = self.fixture.session_directories[("r8i", 1)]
        artifact = "routes/object-row/run-metadata.txt"
        metadata = directory / artifact
        original = metadata.read_text()
        for replacement, message in (("heap_size=2g\n", "route candidate identities differ"),
                                     ("", "missing candidate identity fields")):
            with self.subTest(replacement=replacement):
                metadata.write_text(original.replace("heap_size=8g\n", replacement))
                self.fixture.rewrite_evidence_artifact("r8i", 1, artifact)
                with self.assertRaisesRegex(aggregate_results.ReductionError, message):
                    self.aggregate()

    def test_rejects_honest_confirmation_with_different_heap(self):
        self.fixture.add_session("r8i", 4)
        directory = self.fixture.session_directories[("r8i", 4)]
        for artifact in ("environment-manifest.txt", "routes/native-access/run-metadata.txt", "routes/object-row/run-metadata.txt"):
            path = directory / artifact
            path.write_text(path.read_text().replace("heap_size=8g", "heap_size=2g"))
            self.fixture.rewrite_evidence_artifact("r8i", 4, artifact)
        receipt = validate_confirmation_host_results(self.fixture.manifest, directory / "session.tsv", directory / "observed-rows.tsv")
        self.assertEqual("2g", receipt["heap_size"])
        self.fixture.confirmations[0] = receipt
        self.fixture.rewrite_receipt("r8i", 4)
        self.fixture.write_ledgers()
        with self.assertRaisesRegex(aggregate_results.ReductionError, "multiple candidate identities"):
            self.aggregate()

    def test_manifest_parameter_with_trailing_delimiter_is_accepted(self):
        self.assertEqual(aggregate_results.parse_parameters("pattern=;"), {"pattern": ";"})
        self.assertEqual(aggregate_results.parse_parameters("pattern=[,;]"), {"pattern": "[,;]"})
        self.assertEqual(
            aggregate_results.parse_parameters("sourceLength=10;workload=ascii"),
            {"sourceLength": "10", "workload": "ascii"})

    def test_semantic_mismatch_observed_row_may_omit_performance_values(self):
        observed = self.root / "semantic-mismatch-observed.tsv"
        write_tsv(observed, aggregate_results.OBSERVED_FIELDS, ({
            "row_id": "only/extended/work/count/regulator-native-access",
            "shard_id": "only",
            "system": "regulator-native-access",
            "score": "",
            "score_unit": "",
            "allocation_bytes": "not-measured",
            "result_checksum": "f" * 64,
            "outcome": "semantic-mismatch",
        },))

        self.assertEqual("semantic-mismatch", aggregate_results.read_observed_rows(observed)[0]["outcome"])

    def test_comparator_relationship_is_not_invented_from_two_routes(self):
        rows = [
            self.fixture._row("absolute/scan", "accepted-routes", system, "scan", "textSize=10", "absolute")
            for system in ("regulator-native-access", "regulator-object-row")
        ]
        self.assertEqual(aggregate_results.comparison_specs(rows), [])

    def test_retained_bytes_are_compared_as_a_cost(self):
        self.assertEqual(0.5, aggregate_results.cost_ratio(512, "bytes", 1024, "bytes"))

    def test_extended_rebar_mismatch_is_not_compared(self):
        manifest = [
            row for row in self.fixture.rows
            if "/extended/work/count/" in row["row_id"]
            and row["system"] in {"native-re2-before", "native-re2-after", "regulator-native-access"}
        ]
        host_rows = []
        for row in manifest:
            host_rows.append({
                "platform": "r8i",
                "host_epoch": "epoch",
                "replica_id": "1",
                "confirmation": "false",
                "row_id": row["row_id"],
                "system": row["system"],
                "shard_id": row["shard_id"],
                "suite": row["suite"],
                "rebar_corpus": "extended",
                "score": "100",
                "score_unit": "ns/op",
                "native_bracket_drift": "0",
                "semantic_outcome": (
                    "semantic-mismatch" if row["system"] == "regulator-native-access" else "verified"),
            })

        _, comparisons = aggregate_results.build_comparisons(host_rows, manifest)
        self.assertEqual([], comparisons)

        next(row for row in host_rows if row["system"] == "regulator-native-access")[
            "semantic_outcome"] = "verified"
        _, comparisons = aggregate_results.build_comparisons(host_rows, manifest)
        self.assertEqual(1, len(comparisons))

    def test_extended_rebar_mismatch_is_not_numerically_aggregated(self):
        host_rows = [
            {
                "platform": "r8i",
                "row_id": "extended/regulator-native-access",
                "shard_id": "rebar-a",
                "confirmation": "false",
                "semantic_outcome": "semantic-mismatch",
            }
            for _ in range(3)
        ]

        _, rows, gaps = aggregate_results.build_row_aggregates(host_rows, {}, set(), {})

        self.assertEqual([], rows)
        self.assertEqual([], gaps)

        host_rows[0]["semantic_outcome"] = "verified"
        with self.assertRaisesRegex(aggregate_results.ReductionError, "changes semantic outcome"):
            aggregate_results.build_row_aggregates(host_rows, {}, set(), {})

    def test_joni_did_not_finish_is_not_numerically_aggregated(self):
        host_rows = [
            {
                "platform": "r8i",
                "row_id": "curated/joni",
                "shard_id": "rebar-a",
                "confirmation": "false",
                "semantic_outcome": outcome,
            }
            for outcome in ("did-not-finish", "verified", "verified")
        ]

        _, rows, gaps = aggregate_results.build_row_aggregates(host_rows, {}, set(), {})

        self.assertEqual([], rows)
        self.assertEqual([], gaps)

    def test_scaling_group_with_semantic_mismatch_is_not_aggregated(self):
        manifest = [
            self.fixture._row(
                f"extended/scale/{size}",
                "rebar-extended",
                "regulator-native-access",
                "extended/scale",
                f"sourceLength={size}",
                "pinned-native-re2")
            for size in (10, 100, 1000)
        ]
        host_rows = [
            {
                "platform": "r8i",
                "host_epoch": "epoch",
                "row_id": row["row_id"],
                "semantic_outcome": (
                    "semantic-mismatch" if "100/" in row["row_id"] else "verified"),
            }
            for row in manifest
        ]

        _, rows, membership, mismatches, classes = aggregate_results.build_scaling(host_rows, manifest)

        self.assertEqual([], rows)
        self.assertEqual({}, membership)
        self.assertEqual(set(), mismatches)
        self.assertEqual({}, classes)

    def test_manifest_row_count_is_frozen(self):
        arguments = self.fixture.arguments()
        arguments.expected_manifest_rows += 1
        with self.assertRaisesRegex(aggregate_results.ReductionError, "manifest must contain"):
            aggregate_results.aggregate(arguments)

    def test_complete_campaign_produces_deterministic_tables_and_report(self):
        summary = self.aggregate()
        self.assertEqual(summary["primary_sessions"], 9)
        self.assertEqual(summary["host_rows"], len(self.fixture.rows) * 9)
        ratios = read_tsv(self.fixture.output / "comparison-aggregates.tsv")
        native = next(row for row in ratios
                      if row["platform"] == "r8i" and
                      "/native/search/regulator-native-access::native-re2" in row["comparison_id"])
        self.assertLess(float(native["median_ratio"]), 1.0)
        self.assertEqual(native["direction"], "faster")
        classes = read_tsv(self.fixture.output / "general-classes.tsv")
        self.assertTrue(any(row["rebar_corpus"] == "curated" for row in classes))
        self.assertTrue(any(row["rebar_corpus"] == "extended" for row in classes))
        outliers = read_tsv(self.fixture.output / "serious-outliers.tsv")
        self.assertTrue(any(row["comparator_system"] == "trino-optimized" for row in outliers))
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [])
        self.assertEqual(
            (self.fixture.output / "confirmation-jobs.tsv").read_text().splitlines()[0],
            "platform\tshard_id")
        inventory = read_tsv(self.fixture.output / "artifact-inventory.tsv")
        self.assertTrue(all(row["artifact_uri"].startswith("s3://") for row in inventory))
        self.assertTrue(all(row["campaign_version_id"] == "immutable-version" for row in inventory))
        metadata = read_tsv(self.fixture.output / "metadata.tsv")
        self.assertTrue(any(row["source"] == "campaign-artifact" and row["key"] == "s3_uri"
                            for row in metadata))
        self.assertTrue(any(
            row["source"] == "routes/native-access/run-metadata.txt" and
            row["key"] == "candidate_commit"
            for row in metadata))
        capacity = read_tsv(self.fixture.output / "capacity.tsv")
        self.assertEqual(len(capacity), 9)
        self.assertTrue(all(row["exit_status"] == "0" for row in capacity))
        events = read_tsv(self.fixture.output / "events.tsv")
        self.assertEqual({row["event_type"] for row in events}, {"interruption", "exclusion"})
        rebar_event = next(row for row in events if '"scope":"Rebar"' in row["details"])
        self.assertEqual(json.loads(rebar_event["details"])["model"], "grep")
        rebar_gap = next(row for row in read_tsv(self.fixture.output / "gap-ledger.tsv")
                         if row["scope"] == "Rebar")
        self.assertEqual(rebar_gap["row_id"], "unicode/unsupported")

        report = self.root / "report.md"
        arguments = argparse.Namespace(input_dir=self.fixture.output, output=report, title="Synthetic Baseline")
        render_report.render(arguments)
        first = report.read_bytes()
        render_report.render(arguments)
        self.assertEqual(report.read_bytes(), first)
        text = first.decode()
        self.assertIn("Below 1.0 is faster", text)
        self.assertLess(text.index("### R8I"), text.index("### R8G"))
        self.assertIn("Curated Rebar", text)
        self.assertIn("Extended Rebar (Informational)", text)
        self.assertIn("Spot interruption", text)
        self.assertIn("immutable-version", text)
        self.assertIn("_None._", text)

    def test_confirmation_host_is_preserved(self):
        self.fixture.add_session("r8i", 4)
        self.fixture.write_ledgers()
        self.fixture.write_artifact_index()
        self.aggregate()
        aggregate = next(row for row in read_tsv(self.fixture.output / "row-aggregates.tsv")
                         if row["platform"] == "r8i")
        self.assertEqual(aggregate["host_count"], "4")
        self.assertEqual(aggregate["confirmation_host_count"], "1")
        host_rows = read_tsv(self.fixture.output / "host-rows.tsv")
        self.assertTrue(any(row["confirmation"] == "true" for row in host_rows))

    def test_missing_primary_session_is_rejected(self):
        shutil.rmtree(self.fixture.session_directories[("r9g", 3)])
        with self.assertRaisesRegex(aggregate_results.ReductionError, "accepted artifact coverage mismatch"):
            self.aggregate()

    def test_unexpected_accepted_session_is_rejected(self):
        directory = self.fixture.session_directories[("r9g", 3)]
        clone = self.fixture.artifacts / "unexpected"
        shutil.copytree(directory, clone)
        receipt = read_tsv(clone / "acceptance-receipt.tsv")[0]
        receipt["host_epoch"] = "unexpected"
        receipt["instance_id"] = "i-unexpected"
        write_tsv(clone / "acceptance-receipt.tsv", aggregate_results.RECEIPT_FIELDS, (receipt,))
        with self.assertRaisesRegex(aggregate_results.ReductionError, "unexpected"):
            self.aggregate()

    def test_artifact_index_identity_mismatch_is_rejected(self):
        rows = read_tsv(self.fixture.artifact_index)
        rows[0]["architecture"] = "wrong"
        write_tsv(self.fixture.artifact_index, tuple(rows[0]), rows)
        with self.assertRaisesRegex(aggregate_results.ReductionError, "artifact index identity"):
            self.aggregate()

    def test_campaign_artifact_must_match_campaign(self):
        rows = read_tsv(self.fixture.campaign_artifact)
        rows[0]["campaign_id"] = "another-campaign"
        write_tsv(self.fixture.campaign_artifact, aggregate_results.CAMPAIGN_ARTIFACT_FIELDS, rows)
        with self.assertRaisesRegex(aggregate_results.ReductionError, "campaign artifact ID"):
            self.aggregate()

    def test_campaign_archive_candidate_must_match_accepted_sessions(self):
        rows = read_tsv(self.fixture.campaign_artifact)
        rows[0]["candidate_commit"] = "f" * 40
        write_tsv(self.fixture.campaign_artifact, aggregate_results.CAMPAIGN_ARTIFACT_FIELDS, rows)
        with self.assertRaisesRegex(aggregate_results.ReductionError, "candidate identity"):
            self.aggregate()

    def test_nested_route_artifact_tampering_is_rejected(self):
        directory = self.fixture.session_directories[("r8i", 1)]
        (directory / "routes/native-access/raw/measurement.json").write_text("tampered\n")
        with self.assertRaisesRegex(aggregate_results.ReductionError, "raw artifact checksum mismatch"):
            self.aggregate()

    def test_route_evidence_manifest_tampering_is_rejected(self):
        directory = self.fixture.session_directories[("r8i", 1)]
        with (directory / "route-evidence.tsv").open("a", encoding="utf-8") as output_file:
            output_file.write("host\tmissing\tsha256\t" + "0" * 64 + "\n")
        with self.assertRaisesRegex(aggregate_results.ReductionError, "evidence manifest checksum"):
            self.aggregate()

    def test_capacity_identity_tampering_is_rejected(self):
        directory = self.fixture.session_directories[("r8i", 1)]
        path = directory / "host-capacity.txt"
        path.write_text(path.read_text().replace(
            "memory_total_bytes=34359738368", "memory_total_bytes=34359738367"))
        with self.assertRaisesRegex(aggregate_results.ReductionError, "host evidence checksum mismatch"):
            self.aggregate()

    def test_incomplete_final_capacity_is_rejected(self):
        directory = self.fixture.session_directories[("r8i", 1)]
        path = directory / "host-capacity.txt"
        path.write_text("\n".join(
            line for line in path.read_text().splitlines()
            if not line.startswith("memory_available_after_bytes=")) + "\n")
        with self.assertRaisesRegex(aggregate_results.ReductionError, "finalized capacity fields"):
            self.aggregate()

    def test_valid_looking_final_capacity_tampering_is_rejected_by_archive_binding(self):
        directory = self.fixture.session_directories[("r8i", 1)]
        path = directory / "host-capacity.txt"
        path.write_text(path.read_text().replace("wall_seconds=120", "wall_seconds=121"))
        with self.assertRaisesRegex(aggregate_results.ReductionError, "not bound by the campaign archive"):
            self.aggregate()

    def test_campaign_artifact_is_optional(self):
        arguments = self.fixture.arguments()
        arguments.campaign_artifact = None
        aggregate_results.aggregate(arguments)
        summary = json.loads((self.fixture.output / "summary.json").read_text())
        self.assertEqual(summary["campaign_artifact"], {})
        inventory = read_tsv(self.fixture.output / "artifact-inventory.tsv")
        self.assertTrue(all(not row["campaign_s3_uri"] for row in inventory))

    def test_checked_in_rebar_exclusions_are_all_ingested(self):
        path = BASELINE.parent / "manifests" / "rebar-exclusions.tsv"
        events = aggregate_results.load_rebar_exclusions([path], "test-campaign")
        self.assertEqual(len(events), 61)
        self.assertTrue(all(json.loads(event["details"])["scope"] == "Rebar" for event in events))

    def test_legacy_joni_exclusion_is_rejected(self):
        path = self.fixture.root / "joni-exclusions.tsv"
        write_tsv(path, ("benchmark", "model", "reason"), ({
            "benchmark": "curated/01-literal/sherlock-en",
            "model": "count",
            "reason": "manual exclusion",
        },))

        with self.assertRaisesRegex(aggregate_results.ReductionError, "unsupported exclusion columns"):
            aggregate_results.load_rebar_exclusions([path], "test-campaign")

    def test_checked_in_joni_applicability_records_only_unavailable_rows(self):
        path = BASELINE.parent / "manifests" / "rebar-joni-applicability.tsv"
        manifest = aggregate_results.load_manifest(BASELINE / "rows.tsv", 5475)
        events = aggregate_results.load_rebar_joni_applicability(
            path, manifest, "test-campaign")

        self.assertEqual(13, len(events))
        details = [json.loads(event["details"]) for event in events]
        self.assertEqual({"Joni"}, {detail["scope"] for detail in details})
        self.assertEqual(
            {"not-compatible"},
            {detail["status"] for detail in details})

    def test_joni_applicability_must_match_frozen_rows(self):
        path = BASELINE.parent / "manifests" / "rebar-joni-applicability.tsv"
        manifest = aggregate_results.load_manifest(BASELINE / "rows.tsv", 5475)
        manifest = [
            row for row in manifest
            if not (row["suite"].startswith("rebar-") and row["system"] == "joni")
        ]

        with self.assertRaisesRegex(
                aggregate_results.ReductionError,
                "frozen Rebar/Joni rows differ from applicability"):
            aggregate_results.load_rebar_joni_applicability(path, manifest, "test-campaign")

    def test_joni_applicability_must_match_frozen_digest(self):
        path = BASELINE.parent / "manifests" / "rebar-joni-applicability.tsv"
        manifest = aggregate_results.load_manifest(BASELINE / "rows.tsv", 5475)
        changed = next(
            row.copy() for row in manifest
            if row["suite"].startswith("rebar-") and row["system"] == "joni")
        changed["workload_checksum"] = re.sub(
            r"joni-applicability=[0-9a-f]{64}",
            "joni-applicability=" + ("0" * 64),
            changed["workload_checksum"])
        manifest = [changed if row["row_id"] == changed["row_id"] else row for row in manifest]

        with self.assertRaisesRegex(
                aggregate_results.ReductionError,
                "digest differs from frozen manifest binding"):
            aggregate_results.load_rebar_joni_applicability(path, manifest, "test-campaign")

    def test_contract_identity_disagreement_is_unresolved(self):
        row_id = "only/native/search/regulator-native-access"
        self.fixture.mutate_observed("r8i", 3, row_id, "result_checksum", "f" * 64)
        self.aggregate()
        row = next(row for row in read_tsv(self.fixture.output / "row-aggregates.tsv")
                   if row["platform"] == "r8i" and row["row_id"] == row_id)
        self.assertEqual(row["contract_identity_status"], "mismatch")
        self.assertIn("contract-identity-mismatch", row["unresolved_reasons"])
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [
            {"platform": "r8i", "shard_id": "only"}])

    def test_host_cv_and_allocation_are_unresolved(self):
        row_id = "only/native/search/regulator-native-access"
        self.fixture.mutate_observed("r8i", 3, row_id, "score", "200")
        self.fixture.mutate_observed("r8i", 2, row_id, "allocation_bytes", "8")
        self.aggregate()
        row = next(row for row in read_tsv(self.fixture.output / "row-aggregates.tsv")
                   if row["platform"] == "r8i" and row["row_id"] == row_id)
        self.assertIn("host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertIn("allocation-free-row-allocated", row["unresolved_reasons"])
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [
            {"platform": "r8i", "shard_id": "only"}])

    def test_precision_rejected_replica_is_preserved_as_unresolved(self):
        row_id = "only/joni/count/regulator-native-access"
        self.fixture.mutate_observed("r8i", 2, row_id, "outcome", "precision-rejected")

        self.aggregate()

        row = next(row for row in read_tsv(self.fixture.output / "row-aggregates.tsv")
                   if row["platform"] == "r8i" and row["row_id"] == row_id)
        self.assertIn("precision-rejected", row["unresolved_reasons"])
        comparison = next(
            row for row in read_tsv(self.fixture.output / "comparison-aggregates.tsv")
            if row["platform"] == "r8i" and row["candidate_row_id"] == row_id)
        self.assertEqual("true", comparison["unresolved"])
        self.assertIn("precision-rejected", comparison["unresolved_reasons"])
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [
            {"platform": "r8i", "shard_id": "only"}])

    def test_allocation_violation_alone_does_not_request_confirmation(self):
        row_id = "only/native/search/regulator-native-access"
        self.fixture.mutate_observed("r8i", 2, row_id, "allocation_bytes", "8")
        self.aggregate()
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [])

    def test_material_host_direction_disagreement_is_unresolved(self):
        row_id = "only/joni/count/regulator-native-access"
        self.fixture.mutate_observed("r8i", 1, row_id, "score", "80")
        self.fixture.mutate_observed("r8i", 2, row_id, "score", "120")
        self.aggregate()
        row = next(row for row in read_tsv(self.fixture.output / "comparison-aggregates.tsv")
                   if row["platform"] == "r8i" and row["candidate_row_id"] == row_id)
        self.assertEqual(row["unresolved"], "true")
        self.assertIn("host-direction-disagreement", row["unresolved_reasons"])
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [
            {"platform": "r8i", "shard_id": "only"}])

    def test_primary_ratio_variability_requests_confirmation(self):
        candidate = "only/joni/count/regulator-native-access"
        comparator = "only/joni/count/joni"
        candidate_scores = (97, 100, 103)
        comparator_scores = tuple(
            candidate_score / ratio
            for candidate_score, ratio in zip(candidate_scores, (0.94, 1.0, 1.06), strict=True)
        )
        for replica, (candidate_score, comparator_score) in enumerate(
                zip(candidate_scores, comparator_scores, strict=True), 1):
            self.fixture.mutate_observed("r8i", replica, candidate, "score", str(candidate_score))
            self.fixture.mutate_observed("r8i", replica, comparator, "score", str(comparator_score))

        self.aggregate()

        row = next(row for row in read_tsv(self.fixture.output / "comparison-aggregates.tsv")
                   if row["platform"] == "r8i" and row["candidate_row_id"] == candidate)
        self.assertGreater(float(row["primary_ratio_host_cv"]), 0.05)
        self.assertIn("ratio-host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [
            {"platform": "r8i", "shard_id": "only"}])

    def test_exact_ratio_variability_limit_does_not_request_confirmation(self):
        candidate = "only/joni/count/regulator-native-access"
        comparator = "only/joni/count/joni"
        for replica, ratio in enumerate((0.95, 1.0, 1.05), 1):
            self.fixture.mutate_observed("r8i", replica, candidate, "score", str(ratio * 100))
            self.fixture.mutate_observed("r8i", replica, comparator, "score", "100")

        self.aggregate()

        row = next(row for row in read_tsv(self.fixture.output / "comparison-aggregates.tsv")
                   if row["platform"] == "r8i" and row["candidate_row_id"] == candidate)
        self.assertEqual(row["primary_ratio_host_cv"], "0.05")
        self.assertNotIn("ratio-host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [])

    def test_confirmation_can_resolve_primary_ratio_variability(self):
        candidate = "only/joni/count/regulator-native-access"
        comparator = "only/joni/count/joni"
        self.fixture.add_session("r8i", 4)
        for replica, ratio in enumerate((0.94, 1.0, 1.06, 1.0), 1):
            candidate_score = (97, 100, 103, 100)[replica - 1]
            self.fixture.mutate_observed("r8i", replica, candidate, "score", str(candidate_score))
            self.fixture.mutate_observed("r8i", replica, comparator, "score", str(candidate_score / ratio))
        self.fixture.write_ledgers()
        self.fixture.write_artifact_index()

        self.aggregate()

        row = next(row for row in read_tsv(self.fixture.output / "comparison-aggregates.tsv")
                   if row["platform"] == "r8i" and row["candidate_row_id"] == candidate)
        self.assertGreater(float(row["primary_ratio_host_cv"]), 0.05)
        self.assertLessEqual(float(row["ratio_host_cv"]), 0.05)
        self.assertNotIn("ratio-host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [])

    def test_confirmation_at_exact_ratio_variability_limit_is_resolved(self):
        candidate = "only/joni/count/regulator-native-access"
        comparator = "only/joni/count/joni"
        self.fixture.add_session("r8i", 4)
        for replica, ratio in enumerate((0.925, 1.025, 1.025, 1.025), 1):
            self.fixture.mutate_observed("r8i", replica, candidate, "score", str(ratio * 100))
            self.fixture.mutate_observed("r8i", replica, comparator, "score", "100")
        self.fixture.write_ledgers()
        self.fixture.write_artifact_index()

        self.aggregate()

        row = next(row for row in read_tsv(self.fixture.output / "comparison-aggregates.tsv")
                   if row["platform"] == "r8i" and row["candidate_row_id"] == candidate)
        self.assertEqual(row["ratio_host_cv"], "0.05")
        self.assertNotIn("ratio-host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [])

    def test_drift_limit_uses_published_precision(self):
        self.assertFalse(aggregate_results.exceeds_drift_limit(0.049999999999))
        self.assertFalse(aggregate_results.exceeds_drift_limit(0.050000000000000044))
        self.assertTrue(aggregate_results.exceeds_drift_limit(0.050000000001))

    def test_persistent_ratio_variability_remains_unresolved_after_confirmation(self):
        candidate = "only/joni/count/regulator-native-access"
        comparator = "only/joni/count/joni"
        self.fixture.add_session("r8i", 4)
        for replica, ratio in enumerate((0.92, 1.0, 1.08, 1.0), 1):
            candidate_score = (96, 100, 104, 100)[replica - 1]
            self.fixture.mutate_observed("r8i", replica, candidate, "score", str(candidate_score))
            self.fixture.mutate_observed("r8i", replica, comparator, "score", str(candidate_score / ratio))
        self.fixture.write_ledgers()
        self.fixture.write_artifact_index()

        self.aggregate()

        row = next(row for row in read_tsv(self.fixture.output / "comparison-aggregates.tsv")
                   if row["platform"] == "r8i" and row["candidate_row_id"] == candidate)
        self.assertGreater(float(row["ratio_host_cv"]), 0.05)
        self.assertIn("ratio-host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [])

    def test_scaling_class_disagreement_is_unresolved(self):
        for size in (10, 100, 1000):
            self.fixture.mutate_observed(
                "r8i", 3, f"only/scale/{size}/regulator-native-access", "score", "100")
        self.aggregate()
        rows = [row for row in read_tsv(self.fixture.output / "row-aggregates.tsv")
                if row["platform"] == "r8i" and "/scale/" in row["row_id"]
                and row["system"] == "regulator-native-access"]
        self.assertTrue(rows)
        self.assertTrue(all("scaling-class-mismatch" in row["unresolved_reasons"] for row in rows))
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [
            {"platform": "r8i", "shard_id": "only"}])

    def test_existing_confirmation_consumes_platform_shard_allowance(self):
        self.fixture.add_session("r8i", 4)
        self.fixture.write_ledgers()
        self.fixture.write_artifact_index()
        row_id = "only/native/search/regulator-native-access"
        self.fixture.mutate_observed("r8i", 3, row_id, "score", "200")
        self.aggregate()
        self.assertEqual(read_tsv(self.fixture.output / "confirmation-jobs.tsv"), [])

    def test_accepted_calibration_drift_above_gate_is_rejected(self):
        directory = self.fixture.session_directories[("r8i", 1)]
        artifact = "routes/native-access/calibration.tsv"
        rows = read_tsv(directory / artifact)
        rows[1]["score"] = "106"
        rows[0]["before_after_drift"] = "0.06"
        rows[1]["before_after_drift"] = "0.06"
        write_tsv(directory / artifact, tuple(rows[0]), rows)
        self.fixture.rewrite_evidence_artifact("r8i", 1, artifact)
        with self.assertRaisesRegex(aggregate_results.ReductionError, "calibration drift gate"):
            self.aggregate()

    def test_native_bracket_drift_above_gate_marks_only_affected_rows_unresolved(self):
        directory = self.fixture.session_directories[("r8i", 1)]
        artifact = "routes/native-access/native-bracket.tsv"
        rows = read_tsv(directory / artifact)
        affected_benchmark = "curated/work[model=count;workload=curated/work]"
        affected = next(row for row in rows if row["benchmark"] == affected_benchmark)
        affected["after_score_ns"] = "106"
        affected["before_after_drift"] = "0.06"
        write_tsv(directory / artifact, tuple(rows[0]), rows)
        self.fixture.rewrite_evidence_artifact("r8i", 1, artifact)
        self.aggregate()

        row_aggregates = read_tsv(self.fixture.output / "row-aggregates.tsv")
        affected_rows = [
            row for row in row_aggregates
            if row["platform"] == "r8i" and row["benchmark"] == "curated/work"
        ]
        self.assertTrue(affected_rows)
        self.assertTrue(all(row["unresolved"] == "true" for row in affected_rows))
        self.assertTrue(all(
            "native-bracket-drift-above-5-percent" in row["unresolved_reasons"]
            for row in affected_rows))

        unaffected_rows = [
            row for row in row_aggregates
            if row["platform"] == "r8i" and row["benchmark"] == "extended/work"
        ]
        self.assertTrue(unaffected_rows)
        self.assertTrue(all(
            "native-bracket-drift-above-5-percent" not in row["unresolved_reasons"]
            for row in unaffected_rows))

        comparisons = read_tsv(self.fixture.output / "comparison-aggregates.tsv")
        affected_comparisons = [
            row for row in comparisons
            if row["platform"] == "r8i" and "curated/work/count" in row["candidate_row_id"]
        ]
        self.assertTrue(affected_comparisons)
        self.assertTrue(all(row["unresolved"] == "true" for row in affected_comparisons))
        self.assertEqual(
            read_tsv(self.fixture.output / "confirmation-jobs.tsv"),
            [{"platform": "r8i", "shard_id": "only"}],
        )

    def test_throughput_ratio_is_inverted_to_cost(self):
        candidate = "only/joni/count/regulator-native-access"
        object_candidate = "only/joni/count/regulator-object-row"
        comparator = "only/joni/count/joni"
        for platform in self.fixture.platforms:
            for replica in (1, 2, 3):
                for row_id in (candidate, object_candidate):
                    self.fixture.mutate_observed(platform, replica, row_id, "score_unit", "ops/s")
                self.fixture.mutate_observed(platform, replica, comparator, "score_unit", "ops/s")
                self.fixture.mutate_observed(platform, replica, candidate, "score", "200")
                self.fixture.mutate_observed(platform, replica, object_candidate, "score", "200")
                self.fixture.mutate_observed(platform, replica, comparator, "score", "100")
        self.aggregate()
        row = next(row for row in read_tsv(self.fixture.output / "comparison-aggregates.tsv")
                   if row["candidate_row_id"] == candidate)
        self.assertAlmostEqual(float(row["median_ratio"]), 0.5)

    def test_all_campaign_event_types_are_preserved_separately(self):
        fields = aggregate_results.EVENT_FIELDS + ("log_sha256",)
        events = []
        for event_type in ("interruption", "rejected-session", "exclusion"):
            events.append({
                "event_type": event_type, "campaign_id": "test-campaign", "platform": "r8i",
                "shard_id": "only", "replica_id": "1", "instance_id": "i-event",
                "host_epoch": f"{event_type}-epoch", "reason": f"{event_type} reason",
                "artifact_uri": f"s3://bucket/{event_type}.log", "artifact_sha256": "d" * 64,
                "details": '{"attempt":"1"}',
                "log_sha256": "c" * 64,
            })
        write_tsv(self.fixture.events, fields, events)
        self.aggregate()
        actual = read_tsv(self.fixture.output / "events.tsv")
        self.assertEqual({row["event_type"] for row in actual}, {
            "interruption", "rejected-session", "exclusion"})
        rejected = next(row for row in actual if row["event_type"] == "rejected-session")
        self.assertEqual(json.loads(rejected["details"])["log_sha256"], "c" * 64)
        self.assertEqual(json.loads(rejected["details"])["attempt"], "1")
        rejected_gap = next(row for row in read_tsv(self.fixture.output / "gap-ledger.tsv")
                            if row["condition"] == "rejected-session")
        self.assertEqual(rejected_gap["severity"], "recorded")
        self.assertEqual(rejected_gap["status"], "recorded")

    def test_campaign_event_cannot_inject_joni_exclusion(self):
        rows = read_tsv(self.fixture.events)
        rows[0].update({
            "event_type": "exclusion",
            "details": '{"benchmark":"curated/work","model":"count","scope":"Joni"}',
        })
        write_tsv(self.fixture.events, aggregate_results.EVENT_FIELDS, rows)

        with self.assertRaisesRegex(
                aggregate_results.ReductionError,
                "authoritative applicability ledger"):
            aggregate_results.load_events([self.fixture.events])

    def test_preformatted_event_details_are_preserved(self):
        rows = read_tsv(self.fixture.events)
        rows[0]["details"] = "controller classification: spot interruption"
        write_tsv(self.fixture.events, aggregate_results.EVENT_FIELDS, rows)
        self.aggregate()
        event = next(row for row in read_tsv(self.fixture.output / "events.tsv")
                     if row["event_type"] == "interruption")
        self.assertEqual(event["details"], "controller classification: spot interruption")

    def test_plain_event_details_merge_additional_fields_without_nesting(self):
        rows = read_tsv(self.fixture.events)
        rows[0]["details"] = "controller classification"
        rows[0]["log_sha256"] = "b" * 64
        write_tsv(self.fixture.events, aggregate_results.EVENT_FIELDS + ("log_sha256",), rows)
        self.aggregate()
        details = next(row for row in read_tsv(self.fixture.output / "events.tsv")
                       if row["event_type"] == "interruption")["details"]
        self.assertEqual(
            details,
            'controller classification | additional={"log_sha256":"' + "b" * 64 + '"}')

    def test_report_refuses_modified_reducer_table(self):
        self.aggregate()
        with (self.fixture.output / "general-classes.tsv").open("a", encoding="utf-8") as output_file:
            output_file.write("corrupt\n")
        with self.assertRaisesRegex(render_report.ReportError, "checksum mismatch"):
            render_report.render(argparse.Namespace(
                input_dir=self.fixture.output, output=self.root / "report.md", title="Baseline"))

    def test_command_line_reducer_and_renderer(self):
        output = self.root / "command-line-output"
        subprocess.run([
            sys.executable, str(BASELINE / "aggregate_results.py"),
            "--manifest", str(self.fixture.manifest),
            "--platforms", str(self.fixture.platform_file),
            "--shards", str(self.fixture.shard_file),
            "--primary-receipts", str(self.fixture.primary_receipts),
            "--confirmation-receipts", str(self.fixture.confirmation_receipts),
            "--artifacts-root", str(self.fixture.artifacts),
            "--events", str(self.fixture.events),
            "--rebar-exclusions", str(self.fixture.rebar_exclusions),
            "--artifact-index", str(self.fixture.artifact_index),
            "--campaign-artifact", str(self.fixture.campaign_artifact),
            "--output-dir", str(output),
            "--expected-manifest-rows", str(len(self.fixture.rows)),
        ], check=True, capture_output=True, text=True)
        report = self.root / "command-line-report.md"
        subprocess.run([
            sys.executable, str(BASELINE / "render_report.py"),
            "--input-dir", str(output), "--output", str(report),
            "--title", "Command-Line Baseline",
        ], check=True, capture_output=True, text=True)
        self.assertIn("# Command-Line Baseline", report.read_text())


if __name__ == "__main__":
    unittest.main()
