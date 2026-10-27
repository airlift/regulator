import argparse
import csv
import dataclasses
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

# The row count the checked-in manifest is reduced against.
MANIFEST_ROW_COUNT = aggregate_results.parser().get_default("expected_manifest_rows")

from test_unit_session import UNIT_SESSION, UnitCampaign  # noqa: E402


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


def write_campaign_artifact(source_directory, artifact, campaign_id, unbound=lambda path: False):
    """Bind every retained file under the campaign source, except those unbound, as its immutable archive would."""
    source_manifest = artifact.with_name(artifact.name + ".source-manifest.tsv")
    source_rows = []
    for path in sorted(
            (item for item in source_directory.rglob("*") if item.is_file() and not unbound(item)),
            key=lambda item: str(item.relative_to(source_directory))):
        source_rows.append({
            "path": "campaign-results/" + str(path.relative_to(source_directory)),
            "size": str(path.stat().st_size),
            "sha256": digest(path),
        })
    write_tsv(source_manifest, aggregate_results.SOURCE_MANIFEST_FIELDS, source_rows)
    write_tsv(artifact, aggregate_results.CAMPAIGN_ARTIFACT_FIELDS, ({
        "s3_uri": "s3://baseline/campaign.tar.gz", "version_id": "immutable-version",
        "archive_sha256": "9" * 64, "candidate_commit": "a" * 40,
        "candidate_ref": "refs/benchmarks/test", "campaign_id": campaign_id,
        "source_directory": str(source_directory.resolve()), "file_count": str(len(source_rows)),
        "source_manifest_sha256": digest(source_manifest),
    },))


class TestLikeComparisonCoverage(unittest.TestCase):
    def test_like_candidates_pair_with_each_measured_comparator(self):
        manifest = read_tsv(BASELINE / 'rows.tsv')
        for shard in ('like-compile', 'like-single-use', 'trino-like', 'like-dfa-single-use', 'trino-like-specialized'):
            with self.subTest(shard=shard):
                rows = [row for row in manifest if row['shard_id'] == shard]
                # The generated LIKE variant pairs with the comparators as the interpreted one does.
                expected = {row['row_id'] for row in rows if row['system'] in {'regulator', 'regulator-generated'}}
                specs = aggregate_results.comparison_specs(rows)
                self.assertEqual(expected, {candidate['row_id'] for _, candidate, comparator, _ in specs
                                            if comparator == 'trino-sql'})
                dfa_expected = expected if any(row['system'] == 'trino-optimized' for row in rows) else set()
                self.assertEqual(dfa_expected, {candidate['row_id'] for _, candidate, comparator, _ in specs
                                                if comparator == 'trino-optimized'})
                self.assertEqual(len(expected) + len(dfa_expected), len(specs))


def manifest_row(logical, suite, system, benchmark, parameters, comparator,
                 allocation="recorded", expected="benchmark-self-check"):
    return {
        "row_id": f"only/{logical}/{system}", "shard_id": "only", "suite": suite,
        "system": system, "benchmark": benchmark, "parameters": parameters,
        "comparator": comparator, "expected_result": expected,
        "allocation_contract": allocation, "workload_checksum": "fixture-workload",
    }


def synthetic_manifest():
    rows = []
    for system in ("native-re2-before", "native-re2-after", "regulator-native-access", "regulator-object-row"):
        rows.append(manifest_row("native/search", "traditional", system, "search", "textSize=100",
                                 "NativeSearch", "allocation-free", "paired-native-result"))
    for system in ("joni", "regulator-native-access", "regulator-object-row"):
        rows.append(manifest_row("joni/count", "trino-public-operations", system, "count", "sourceLength=100",
                                 "joni", "operation-dependent", "joni-differential"))
    for system in ("regulator", "trino-optimized", "trino-sql"):
        rows.append(manifest_row("like/contains", "trino-like", system, "matches", "scenario=contains",
                                 "trino-like", "allocation-free", "trino-differential"))
    for size in (10, 100, 1000):
        for system in ("regulator-native-access", "regulator-object-row"):
            rows.append(manifest_row(f"scale/{size}", "accepted-routes", system, "scan",
                                     f"textSize={size};workload=ascii", "general-route-control", "allocation-free"))
    for corpus, suite in (("curated", "rebar-curated"), ("extended", "rebar-extended")):
        for system in ("native-re2-before", "native-re2-after", "regulator-native-access", "regulator-object-row"):
            rows.append(manifest_row(f"{corpus}/work/count", suite, system, f"{corpus}/work",
                                     f"model=count;workload={corpus}/work", "pinned-native-re2",
                                     "recorded", "native-differential"))
    return sorted(rows, key=lambda row: row["row_id"])


class SampleFixture:
    """The accepted attempts of one synthetic unit that owns every row of its shard, as the reducer loads them.

    Each platform runs replicas 1 to 3, and a confirmation replica 4, on hosts of their own."""
    PLATFORMS = {"r8i": ("intel", "r8i.large"), "r9g": ("arm", "r9g.large")}
    UNIT = "only-unit"
    CONFIRMATION_JOB = {"platform": "r8i", "shard_id": "only", "unit_id": UNIT}

    def __init__(self):
        self.rows = synthetic_manifest()
        self.sessions = {}
        self.observed = {}
        for platform in self.PLATFORMS:
            for replica in (1, 2, 3):
                self.add(platform, replica)

    @staticmethod
    def score(row, replica):
        multiplier = {1: 0.99, 2: 1.0, 3: 1.01, 4: 1.0}[replica]
        system = row["system"]
        if "/scale/" in row["row_id"]:
            size = int(aggregate_results.parse_parameters(row["parameters"])["textSize"])
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

    def add(self, platform, replica):
        architecture, instance_type = self.PLATFORMS[platform]
        receipt = {
            "campaign_id": "campaign", "platform": platform, "architecture": architecture,
            "instance_type": instance_type, "availability_zone": "us-west-2a", "shard_id": "only",
            "unit_id": self.UNIT, "replica_id": str(replica), "unit_attempt_id": f"{platform}-session-{replica}/1",
            "instance_id": f"i-{platform}-{replica}", "host_epoch": f"{platform}-epoch-{replica}",
            "candidate_commit": "a" * 40, "engine_tree": "b" * 40, "evidence_manifest_sha256": "c" * 64,
        }
        # The shard's comparators repeat, so its native brackets are keyed by benchmark and parameters.
        brackets = {f"{row['benchmark']}[{row['parameters']}]": 0.02
                    for row in self.rows if row["system"] == "native-re2-before"}
        self.sessions[(platform, replica)] = aggregate_results.Session(
            receipt, Path(platform, f"replica-{replica}"), replica == 4, 0.01, 0.02, brackets, "d" * 64, (), {}, ())
        self.observed[(platform, replica)] = {row["row_id"]: {
            "row_id": row["row_id"], "role": "owner", "score": f"{self.score(row, replica):.12g}",
            "score_unit": "ns/op", "allocation_bytes": "0" if row["allocation_contract"] == "allocation-free" else "16",
            "result_checksum": hashlib.sha256((row["row_id"] + "\0contract").encode()).hexdigest(),
            "outcome": "accepted",
        } for row in self.rows}

    def set(self, platform, replica, row_id, field, value):
        self.observed[(platform, replica)][row_id][field] = str(value)

    def bracket(self, platform, replica, benchmark, drift):
        session = self.sessions[(platform, replica)]
        self.sessions[(platform, replica)] = dataclasses.replace(
            session, native_drift_by_benchmark={**session.native_drift_by_benchmark, benchmark: drift})

    def reduce(self):
        sessions = list(self.sessions.values())
        observed = {session.key: list(self.observed[key].values()) for key, session in self.sessions.items()}
        _, host_rows = aggregate_results.build_host_rows(
            sessions, observed, {row["row_id"]: row for row in self.rows}, {}, {})
        _, comparisons = aggregate_results.build_comparisons(host_rows, self.rows)
        _, scaling, membership, mismatches, classes = aggregate_results.build_scaling(host_rows, self.rows)
        _, rows, _ = aggregate_results.build_row_aggregates(host_rows, membership, mismatches, classes)
        _, comparison_rows, _ = aggregate_results.build_comparison_aggregates(comparisons)
        _, jobs = aggregate_results.build_confirmation_jobs(rows, host_rows, comparisons, scaling, sessions)
        return argparse.Namespace(rows=rows, comparisons=comparison_rows, confirmation_jobs=jobs)


def find(rows, platform, field, value):
    return next(row for row in rows if row["platform"] == platform and row[field] == value)


class TestReductionRules(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.fixture = SampleFixture()

    def tearDown(self):
        self.temporary.cleanup()

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
            manifest_row("absolute/scan", "accepted-routes", system, "scan", "textSize=10", "absolute")
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
                "unit_id": "unit",
                "unit_attempt_id": "session/1",
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
            manifest_row(
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
                "unit_attempt_id": "session/1",
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

    def test_stable_samples_request_no_confirmation(self):
        reduction = self.fixture.reduce()
        native = find(reduction.comparisons, "r8i", "comparison_id",
                      "only/native/search/regulator-native-access::native-re2")
        self.assertEqual(("faster", "false"), (native["direction"], native["unresolved"]))
        self.assertEqual([], reduction.confirmation_jobs)

    def test_contract_identity_disagreement_is_unresolved(self):
        row_id = "only/native/search/regulator-native-access"
        self.fixture.set("r8i", 3, row_id, "result_checksum", "f" * 64)
        reduction = self.fixture.reduce()
        row = find(reduction.rows, "r8i", "row_id", row_id)
        self.assertEqual(row["contract_identity_status"], "mismatch")
        self.assertIn("contract-identity-mismatch", row["unresolved_reasons"])
        self.assertEqual(reduction.confirmation_jobs, [SampleFixture.CONFIRMATION_JOB])

    def test_host_cv_and_allocation_are_unresolved(self):
        row_id = "only/native/search/regulator-native-access"
        self.fixture.set("r8i", 3, row_id, "score", "200")
        self.fixture.set("r8i", 2, row_id, "allocation_bytes", "8")
        reduction = self.fixture.reduce()
        row = find(reduction.rows, "r8i", "row_id", row_id)
        self.assertIn("host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertIn("allocation-free-row-allocated", row["unresolved_reasons"])
        self.assertEqual(reduction.confirmation_jobs, [SampleFixture.CONFIRMATION_JOB])

    def test_precision_rejected_replica_is_preserved_as_unresolved(self):
        row_id = "only/joni/count/regulator-native-access"
        self.fixture.set("r8i", 2, row_id, "outcome", "precision-rejected")

        reduction = self.fixture.reduce()

        self.assertIn("precision-rejected", find(reduction.rows, "r8i", "row_id", row_id)["unresolved_reasons"])
        comparison = find(reduction.comparisons, "r8i", "candidate_row_id", row_id)
        self.assertEqual("true", comparison["unresolved"])
        self.assertIn("precision-rejected", comparison["unresolved_reasons"])
        self.assertEqual(reduction.confirmation_jobs, [SampleFixture.CONFIRMATION_JOB])

    def test_allocation_violation_alone_does_not_request_confirmation(self):
        self.fixture.set("r8i", 2, "only/native/search/regulator-native-access", "allocation_bytes", "8")
        self.assertEqual(self.fixture.reduce().confirmation_jobs, [])

    def test_material_host_direction_disagreement_is_unresolved(self):
        row_id = "only/joni/count/regulator-native-access"
        self.fixture.set("r8i", 1, row_id, "score", "80")
        self.fixture.set("r8i", 2, row_id, "score", "120")
        reduction = self.fixture.reduce()
        row = find(reduction.comparisons, "r8i", "candidate_row_id", row_id)
        self.assertEqual(row["unresolved"], "true")
        self.assertIn("host-direction-disagreement", row["unresolved_reasons"])
        self.assertEqual(reduction.confirmation_jobs, [SampleFixture.CONFIRMATION_JOB])

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
            self.fixture.set("r8i", replica, candidate, "score", candidate_score)
            self.fixture.set("r8i", replica, comparator, "score", comparator_score)

        reduction = self.fixture.reduce()

        row = find(reduction.comparisons, "r8i", "candidate_row_id", candidate)
        self.assertGreater(float(row["primary_ratio_host_cv"]), 0.05)
        self.assertIn("ratio-host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertEqual(reduction.confirmation_jobs, [SampleFixture.CONFIRMATION_JOB])

    def test_exact_ratio_variability_limit_does_not_request_confirmation(self):
        candidate = "only/joni/count/regulator-native-access"
        comparator = "only/joni/count/joni"
        for replica, ratio in enumerate((0.95, 1.0, 1.05), 1):
            self.fixture.set("r8i", replica, candidate, "score", ratio * 100)
            self.fixture.set("r8i", replica, comparator, "score", "100")

        reduction = self.fixture.reduce()

        row = find(reduction.comparisons, "r8i", "candidate_row_id", candidate)
        self.assertEqual(row["primary_ratio_host_cv"], "0.05")
        self.assertNotIn("ratio-host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertEqual(reduction.confirmation_jobs, [])

    def test_confirmation_can_resolve_primary_ratio_variability(self):
        candidate = "only/joni/count/regulator-native-access"
        comparator = "only/joni/count/joni"
        self.fixture.add("r8i", 4)
        for replica, ratio in enumerate((0.94, 1.0, 1.06, 1.0), 1):
            candidate_score = (97, 100, 103, 100)[replica - 1]
            self.fixture.set("r8i", replica, candidate, "score", candidate_score)
            self.fixture.set("r8i", replica, comparator, "score", candidate_score / ratio)

        reduction = self.fixture.reduce()

        row = find(reduction.comparisons, "r8i", "candidate_row_id", candidate)
        self.assertGreater(float(row["primary_ratio_host_cv"]), 0.05)
        self.assertLessEqual(float(row["ratio_host_cv"]), 0.05)
        self.assertNotIn("ratio-host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertEqual(reduction.confirmation_jobs, [])

    def test_confirmation_at_exact_ratio_variability_limit_is_resolved(self):
        candidate = "only/joni/count/regulator-native-access"
        comparator = "only/joni/count/joni"
        self.fixture.add("r8i", 4)
        for replica, ratio in enumerate((0.925, 1.025, 1.025, 1.025), 1):
            self.fixture.set("r8i", replica, candidate, "score", ratio * 100)
            self.fixture.set("r8i", replica, comparator, "score", "100")

        reduction = self.fixture.reduce()

        row = find(reduction.comparisons, "r8i", "candidate_row_id", candidate)
        self.assertEqual(row["ratio_host_cv"], "0.05")
        self.assertNotIn("ratio-host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertEqual(reduction.confirmation_jobs, [])

    def test_drift_limit_uses_published_precision(self):
        self.assertFalse(aggregate_results.exceeds_drift_limit(0.049999999999))
        self.assertFalse(aggregate_results.exceeds_drift_limit(0.050000000000000044))
        self.assertTrue(aggregate_results.exceeds_drift_limit(0.050000000001))

    def test_persistent_ratio_variability_remains_unresolved_after_confirmation(self):
        candidate = "only/joni/count/regulator-native-access"
        comparator = "only/joni/count/joni"
        self.fixture.add("r8i", 4)
        for replica, ratio in enumerate((0.92, 1.0, 1.08, 1.0), 1):
            candidate_score = (96, 100, 104, 100)[replica - 1]
            self.fixture.set("r8i", replica, candidate, "score", candidate_score)
            self.fixture.set("r8i", replica, comparator, "score", candidate_score / ratio)

        reduction = self.fixture.reduce()

        row = find(reduction.comparisons, "r8i", "candidate_row_id", candidate)
        self.assertGreater(float(row["ratio_host_cv"]), 0.05)
        self.assertIn("ratio-host-cv-above-5-percent", row["unresolved_reasons"])
        self.assertEqual(reduction.confirmation_jobs, [])

    def test_scaling_class_disagreement_is_unresolved(self):
        for size in (10, 100, 1000):
            self.fixture.set("r8i", 3, f"only/scale/{size}/regulator-native-access", "score", "100")
        reduction = self.fixture.reduce()
        rows = [row for row in reduction.rows
                if row["platform"] == "r8i" and "/scale/" in row["row_id"]
                and row["system"] == "regulator-native-access"]
        self.assertTrue(rows)
        self.assertTrue(all("scaling-class-mismatch" in row["unresolved_reasons"] for row in rows))
        self.assertEqual(reduction.confirmation_jobs, [SampleFixture.CONFIRMATION_JOB])

    def test_existing_confirmation_consumes_platform_unit_allowance(self):
        self.fixture.add("r8i", 4)
        self.fixture.set("r8i", 3, "only/native/search/regulator-native-access", "score", "200")
        self.assertEqual(self.fixture.reduce().confirmation_jobs, [])

    def test_native_bracket_drift_above_gate_marks_only_affected_rows_unresolved(self):
        self.fixture.bracket("r8i", 1, "curated/work[model=count;workload=curated/work]", 0.06)
        reduction = self.fixture.reduce()

        affected_rows = [
            row for row in reduction.rows
            if row["platform"] == "r8i" and row["benchmark"] == "curated/work"
        ]
        self.assertTrue(affected_rows)
        self.assertTrue(all(row["unresolved"] == "true" for row in affected_rows))
        self.assertTrue(all(
            "native-bracket-drift-above-5-percent" in row["unresolved_reasons"]
            for row in affected_rows))

        unaffected_rows = [
            row for row in reduction.rows
            if row["platform"] == "r8i" and row["benchmark"] == "extended/work"
        ]
        self.assertTrue(unaffected_rows)
        self.assertTrue(all(
            "native-bracket-drift-above-5-percent" not in row["unresolved_reasons"]
            for row in unaffected_rows))

        affected_comparisons = [
            row for row in reduction.comparisons
            if row["platform"] == "r8i" and "curated/work/count" in row["candidate_row_id"]
        ]
        self.assertTrue(affected_comparisons)
        self.assertTrue(all(row["unresolved"] == "true" for row in affected_comparisons))
        self.assertEqual(reduction.confirmation_jobs, [SampleFixture.CONFIRMATION_JOB])

    def test_throughput_ratio_is_inverted_to_cost(self):
        candidate = "only/joni/count/regulator-native-access"
        object_candidate = "only/joni/count/regulator-object-row"
        comparator = "only/joni/count/joni"
        for platform in SampleFixture.PLATFORMS:
            for replica in (1, 2, 3):
                for row_id, score in ((candidate, 200), (object_candidate, 200), (comparator, 100)):
                    self.fixture.set(platform, replica, row_id, "score_unit", "ops/s")
                    self.fixture.set(platform, replica, row_id, "score", score)
        row = find(self.fixture.reduce().comparisons, "r8i", "candidate_row_id", candidate)
        self.assertAlmostEqual(float(row["median_ratio"]), 0.5)

    def test_checked_in_rebar_exclusions_are_all_ingested(self):
        path = BASELINE.parent / "manifests" / "rebar-exclusions.tsv"
        events = aggregate_results.load_rebar_exclusions([path], "test-campaign")
        self.assertEqual(len(events), 61)
        self.assertTrue(all(json.loads(event["details"])["scope"] == "Rebar" for event in events))

    def test_legacy_joni_exclusion_is_rejected(self):
        path = self.root / "joni-exclusions.tsv"
        write_tsv(path, ("benchmark", "model", "reason"), ({
            "benchmark": "curated/01-literal/sherlock-en",
            "model": "count",
            "reason": "manual exclusion",
        },))

        with self.assertRaisesRegex(aggregate_results.ReductionError, "unsupported exclusion columns"):
            aggregate_results.load_rebar_exclusions([path], "test-campaign")

    def test_checked_in_joni_applicability_records_only_unavailable_rows(self):
        path = BASELINE.parent / "manifests" / "rebar-joni-applicability.tsv"
        manifest = aggregate_results.load_manifest(BASELINE / "rows.tsv", MANIFEST_ROW_COUNT)
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
        manifest = aggregate_results.load_manifest(BASELINE / "rows.tsv", MANIFEST_ROW_COUNT)
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
        manifest = aggregate_results.load_manifest(BASELINE / "rows.tsv", MANIFEST_ROW_COUNT)
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

    def write_events(self, fields, rows):
        path = self.root / "campaign-events.tsv"
        write_tsv(path, fields, rows)
        return path

    def archived_event(self, **values):
        # Archived campaigns recorded neither details nor launch placement.
        return {
            "event_type": "interruption", "campaign_id": "campaign", "platform": "r9g",
            "shard_id": "only", "replica_id": "2", "instance_id": "i-interrupted",
            "host_epoch": "interrupted-epoch", "reason": "Spot interruption",
            "artifact_uri": "s3://bucket/rejected/interrupted.tar.zst", "artifact_sha256": "e" * 64, **values,
        }

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
        events[0].update({
            "region": "us-east-2", "availability_zone": "us-east-2b", "placement_score": "9",
            "failure_classification": "retryable-spot",
        })
        actual = aggregate_results.load_events([self.write_events(fields, events)])
        self.assertEqual({row["event_type"] for row in actual}, {
            "interruption", "rejected-session", "exclusion"})
        interruption = next(row for row in actual if row["event_type"] == "interruption")
        self.assertEqual(
            [interruption[field] for field in (
                "region", "availability_zone", "placement_score", "failure_classification")],
            ["us-east-2", "us-east-2b", "9", "retryable-spot"])
        self.assertEqual(json.loads(interruption["details"]), {"attempt": "1", "log_sha256": "c" * 64})
        rejected = next(row for row in actual if row["event_type"] == "rejected-session")
        self.assertEqual(json.loads(rejected["details"])["log_sha256"], "c" * 64)
        self.assertEqual(json.loads(rejected["details"])["attempt"], "1")
        rejected_gap = next(row for row in aggregate_results.event_gaps(actual)
                            if row["condition"] == "rejected-session")
        self.assertEqual(rejected_gap["severity"], "recorded")
        self.assertEqual(rejected_gap["status"], "recorded")

    def test_campaign_event_cannot_inject_joni_exclusion(self):
        path = self.write_events(aggregate_results.EVENT_FIELDS, (self.archived_event(
            event_type="exclusion", details='{"benchmark":"curated/work","model":"count","scope":"Joni"}'),))

        with self.assertRaisesRegex(
                aggregate_results.ReductionError,
                "authoritative applicability ledger"):
            aggregate_results.load_events([path])

    def test_preformatted_event_details_are_preserved(self):
        path = self.write_events(aggregate_results.EVENT_FIELDS, (self.archived_event(
            details="controller classification: spot interruption"),))
        event = aggregate_results.load_events([path])[0]
        self.assertEqual(event["details"], "controller classification: spot interruption")

    def test_plain_event_details_merge_additional_fields_without_nesting(self):
        path = self.write_events(aggregate_results.EVENT_FIELDS + ("log_sha256",), (self.archived_event(
            details="controller classification", log_sha256="b" * 64),))
        details = aggregate_results.load_events([path])[0]["details"]
        self.assertEqual(
            details,
            'controller classification | additional={"log_sha256":"' + "b" * 64 + '"}')


class TestUnitCampaignReduction(unittest.TestCase):
    # Units 1 and 3 own native brackets and native-access rows; units 2 and 4 own object rows and copy brackets.
    SHARD = "traditional-extra-big-fixed"
    SCORES = {"native-re2-before": 100, "native-re2-after": 102, "regulator-native-access": 80,
              "regulator-object-row": 300}

    def reduction(self, root):
        """A chained unit campaign of the shard whose first unit is unstable on r8i, and a reducer over it."""
        unstable = next(unit for unit in UNIT_SESSION.work_units.load(BASELINE).values()
                        if (unit["shard_id"], unit["unit_index"]) == (self.SHARD, "1"))
        unstable_rows = {row_id for row_id in unstable["owned"] if row_id.endswith("/regulator-native-access")}

        def score(row, role, platform, replica):
            if platform == "r8i" and row["row_id"] in unstable_rows:
                return {1: 60, 2: 80, 3: 100, 4: 80}[replica]
            # A copy measures its bracket again, here at another cost, beside the unit's own rows.
            return self.SCORES[row["system"]] * (2 if role == "copy" else 1)

        campaign = UnitCampaign(root / "campaign-results", self.SHARD, score=score, host_calibrations=True,
                                chained=True)
        # The controller stages each lease's result under unit-results/ before it retains the accepted copy.
        for receipt in campaign.receipts:
            host_session, _, lease = receipt["unit_attempt_id"].rpartition("/")
            shutil.copytree(campaign.directory(receipt),
                            campaign.root / "unit-results" / host_session / f"lease-{lease}")
        # The controller starts each phase with an empty semantic-rejection ledger beside its accepted units.
        (root / UNIT_SESSION.SEMANTIC_REJECTIONS).write_text(
            "\t".join(UNIT_SESSION.SEMANTIC_REJECTION_FIELDS) + "\n")

        def aggregate(confirmations, unbound=lambda path: False, archive_changes=None, **overrides):
            artifact = root / "campaign-artifact.tsv"
            write_campaign_artifact(campaign.root, artifact, "campaign", unbound)
            if archive_changes:
                write_tsv(artifact, aggregate_results.CAMPAIGN_ARTIFACT_FIELDS,
                          [{**read_tsv(artifact)[0], **archive_changes}])
            return aggregate_results.aggregate(argparse.Namespace(**{
                "manifest": BASELINE / "rows.tsv", "platforms": BASELINE / "platforms.tsv",
                "shards": BASELINE / "shards.tsv", "selected_shards": [self.SHARD],
                "primary_receipts": [campaign.write_ledger(root / "accepted-units.tsv", campaign.receipts)],
                "confirmation_receipts": [campaign.write_ledger(root / "confirmation-units.tsv", confirmations)],
                "artifacts_root": [campaign.root], "session_dir": [], "events": [], "rebar_exclusions": [],
                "rebar_joni_applicability": None, "artifact_index": [], "output_dir": root / "output",
                "campaign_artifact": artifact, "expected_manifest_rows": MANIFEST_ROW_COUNT, **overrides}))

        return campaign, unstable, unstable_rows, aggregate

    def test_unit_attempts_reduce_owned_samples_paired_within_their_attempt(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            _, unstable, unstable_rows, aggregate = self.reduction(root)
            summary = aggregate([])
            output = root / "output"
            host_rows = read_tsv(output / "host-rows.tsv")
            self.assertEqual((24, 2 * 3 * 2 * 14), (
                summary["primary_sessions"], sum(row["role"] == "copy" for row in host_rows)))
            aggregates = {(row["platform"], row["row_id"]): row for row in read_tsv(output / "row-aggregates.tsv")}
            bracket = next(row_id for row_id in unstable["owned"] if row_id.endswith("/native-re2-before"))
            self.assertEqual(("3", "100"), (
                aggregates[("r9g", bracket)]["host_count"], aggregates[("r9g", bracket)]["primary_median_score"]))
            # A candidate pairs with the bracket measured in its own attempt: owned beside native access,
            # copied beside object rows, though both attempts ran on one host.
            ratios = [row for row in read_tsv(output / "host-comparator-ratios.tsv") if row["platform"] == "r9g"]
            self.assertEqual(
                {"regulator-native-access": {aggregate_results.format_number(80 / 101)},
                 "regulator-object-row": {aggregate_results.format_number(300 / 202)}},
                {system: {row["ratio"] for row in ratios if row["candidate_system"] == system}
                 for system in ("regulator-native-access", "regulator-object-row")})
            owners = {(row["unit_attempt_id"], row["row_id"]) for row in host_rows if row["role"] == "owner"}
            self.assertTrue(all((row["unit_attempt_id"], row["candidate_row_id"]) in owners for row in ratios))
            # Scaling groups, too, take only owned samples.
            scaling = read_tsv(output / "scaling.tsv")
            self.assertTrue(scaling)
            self.assertTrue(all(
                (row["unit_attempt_id"], row_id) in owners for row in scaling for row_id in row["row_ids"].split(",")))
            self.assertEqual(
                [{"platform": "r8i", "shard_id": self.SHARD, "unit_id": unstable["unit_id"]}],
                read_tsv(output / "confirmation-jobs.tsv"))

    def test_reduction_tables_render_a_deterministic_report(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            campaign, _, _, aggregate = self.reduction(root)
            # Archived campaigns recorded neither details nor launch placement.
            events = root / "campaign-events.tsv"
            write_tsv(events, ("event_type", "campaign_id", "platform", "shard_id", "replica_id", "instance_id",
                               "host_epoch", "reason", "artifact_uri", "artifact_sha256"), ({
                "event_type": "interruption", "campaign_id": "campaign", "platform": "r9g",
                "shard_id": self.SHARD, "replica_id": "2", "instance_id": "i-interrupted",
                "host_epoch": "interrupted-epoch", "reason": "Spot interruption",
                "artifact_uri": "s3://bucket/rejected/interrupted.tar.zst", "artifact_sha256": "e" * 64,
            },))
            exclusions = root / "rebar-exclusions.tsv"
            write_tsv(exclusions, ("name", "model", "definition", "reason"), ({
                "name": "unicode/unsupported", "model": "grep",
                "definition": "unicode/unsupported.toml", "reason": "no-native-re2-semantics",
            },))
            index = root / "artifact-index.tsv"
            write_tsv(index, UNIT_SESSION.UNIT_SESSION_FIELDS + ("artifact_uri", "artifact_sha256", "retrieved_at"), [{
                **{field: receipt[field] for field in UNIT_SESSION.UNIT_SESSION_FIELDS},
                "artifact_uri": f"s3://bucket/{receipt['unit_attempt_id']}.tar.zst",
                "artifact_sha256": hashlib.sha256(receipt["unit_attempt_id"].encode()).hexdigest(),
                "retrieved_at": "2026-08-02T00:00:00Z",
            } for receipt in campaign.receipts])
            aggregate([], events=[events], rebar_exclusions=[exclusions], artifact_index=[index])
            output = root / "output"
            inventory = read_tsv(output / "artifact-inventory.tsv")
            self.assertTrue(all(row["artifact_uri"].startswith("s3://") for row in inventory))
            self.assertTrue(all(row["campaign_version_id"] == "immutable-version" for row in inventory))
            metadata = read_tsv(output / "metadata.tsv")
            self.assertTrue(any(row["source"] == "campaign-artifact" and row["key"] == "s3_uri" for row in metadata))
            self.assertTrue(any(row["source"].endswith("/run-metadata.txt") and row["key"] == "candidate_commit"
                                for row in metadata))
            capacity = read_tsv(output / "capacity.tsv")
            self.assertEqual(24, len(capacity))
            self.assertTrue(all(row["exit_status"] == "0" for row in capacity))
            events = read_tsv(output / "events.tsv")
            self.assertEqual({row["event_type"] for row in events}, {"interruption", "exclusion"})
            rebar_event = next(row for row in events if '"scope":"Rebar"' in row["details"])
            self.assertEqual(json.loads(rebar_event["details"])["model"], "grep")
            rebar_gap = next(row for row in read_tsv(output / "gap-ledger.tsv") if row["scope"] == "Rebar")
            self.assertEqual(rebar_gap["row_id"], "unicode/unsupported")

            report = root / "report.md"
            arguments = argparse.Namespace(input_dir=output, output=report, title="Synthetic Baseline")
            render_report.render(arguments)
            first = report.read_bytes()
            render_report.render(arguments)
            self.assertEqual(report.read_bytes(), first)
            text = first.decode()
            self.assertIn("Below 1.0 is faster", text)
            self.assertLess(text.index("### R8I"), text.index("### R9G"))
            self.assertIn("Spot interruption", text)
            self.assertIn("immutable-version", text)
            with (output / "general-classes.tsv").open("a", encoding="utf-8") as output_file:
                output_file.write("corrupt\n")
            with self.assertRaisesRegex(render_report.ReportError, "checksum mismatch"):
                render_report.render(arguments)

    def test_reduction_refuses_a_campaign_it_cannot_bind(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            campaign, _, _, aggregate = self.reduction(root)
            # A reduction without its campaign archive stands alone.
            self.assertEqual({}, aggregate([], campaign_artifact=None)["campaign_artifact"])
            index = root / "artifact-index.tsv"
            fields = UNIT_SESSION.UNIT_SESSION_FIELDS + ("artifact_uri",)
            write_tsv(index, fields, [{**{field: campaign.receipts[0][field] for field in fields[:-1]},
                                       "architecture": "wrong", "artifact_uri": "s3://bucket/attempt.tar.zst"}])
            for overrides, message in (
                    ({"selected_shards": []}, "selected shards"),
                    ({"selected_shards": ["unknown"]}, "selected shards"),
                    ({"selected_shards": [self.SHARD, self.SHARD]}, "selected shards"),
                    ({"expected_manifest_rows": MANIFEST_ROW_COUNT + 1}, "manifest must contain"),
                    ({"artifact_index": [index]}, "artifact index identity"),
                    ({"archive_changes": {"campaign_id": "another-campaign"}}, "campaign artifact ID"),
                    ({"archive_changes": {"candidate_commit": "f" * 40}}, "candidate identity")):
                with self.subTest(overrides=overrides):
                    with self.assertRaisesRegex(aggregate_results.ReductionError, message):
                        aggregate([], **overrides)
            shutil.rmtree(campaign.directory(campaign.receipts[-1]))
            with self.assertRaisesRegex(aggregate_results.ReductionError, "accepted artifact coverage mismatch"):
                aggregate([])

    def test_command_line_reducer_and_renderer(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            campaign, _, _, aggregate = self.reduction(root)
            # The function reduction writes the ledgers and campaign archive metadata the command line reads.
            aggregate([])
            output = root / "command-line-output"
            subprocess.run([
                sys.executable, str(BASELINE / "aggregate_results.py"),
                "--shard", self.SHARD,
                "--primary-receipts", str(root / "accepted-units.tsv"),
                "--confirmation-receipts", str(root / "confirmation-units.tsv"),
                "--artifacts-root", str(campaign.root),
                "--campaign-artifact", str(root / "campaign-artifact.tsv"),
                "--output-dir", str(output),
            ], check=True, capture_output=True, text=True)
            report = root / "command-line-report.md"
            subprocess.run([
                sys.executable, str(BASELINE / "render_report.py"),
                "--input-dir", str(output), "--output", str(report),
                "--title", "Command-Line Baseline",
            ], check=True, capture_output=True, text=True)
            self.assertIn("# Command-Line Baseline", report.read_text())

    def test_semantic_rejection_or_a_missing_rejection_ledger_refuses_the_phase(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            _, unstable, _, aggregate = self.reduction(root)
            rejections = root / UNIT_SESSION.SEMANTIC_REJECTIONS
            # The phase is refused though another attempt of the unit was accepted.
            with rejections.open("a") as ledger:
                ledger.write(f"r8i\t{unstable['unit_id']}\t1\tr8i-session-9/1\n")
            with self.assertRaisesRegex(aggregate_results.ReductionError,
                                        r"semantic failure: \['r8i-session-9/1'\]"):
                aggregate([])
            rejections.unlink()
            with self.assertRaisesRegex(aggregate_results.ReductionError, "no semantic-rejection ledger"):
                aggregate([])

    def test_campaign_archive_binds_each_attempts_calibrations_and_previous_completion(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            _, _, _, aggregate = self.reduction(root)
            aggregate([])
            inventory = read_tsv(root / "output/artifact-inventory.tsv")
            # An attempt's artifact includes the host calibrations it references, and each chained attempt the
            # previous lease's completion marker it was checked against.
            self.assertEqual(24 * 2, sum(row["logical_name"].startswith("../calibrations/") for row in inventory))
            self.assertEqual(2 * 3 * 3, sum(
                re.fullmatch(r"\.\./lease-[0-9]+-completion\.json", row["logical_name"]) is not None
                for row in inventory))
            for unbound in (lambda path: path.parent.name == "calibrations",
                            lambda path: path.name.endswith("-completion.json")):
                with self.assertRaisesRegex(aggregate_results.ReductionError, "not bound by the campaign archive"):
                    aggregate([], unbound=unbound)

    def test_confirmation_replica_settles_its_unit_only_on_another_instance(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            campaign, unstable, unstable_rows, aggregate = self.reduction(root)
            output = root / "output"
            confirmation = campaign.add("r8i", unstable, 4, host=4)
            aggregate([confirmation])
            confirmed = next(row for row in read_tsv(output / "row-aggregates.tsv")
                             if (row["platform"], row["row_id"]) == ("r8i", min(unstable_rows)))
            self.assertEqual("1", confirmed["confirmation_host_count"])
            self.assertEqual([], read_tsv(output / "confirmation-jobs.tsv"))
            reused = campaign.add("r8i", unstable, 4, host=1)
            with self.assertRaisesRegex(aggregate_results.ReductionError, "replicas of a unit share an EC2 instance"):
                aggregate([reused])

    def test_chain_claim_is_checked_against_the_previous_leases_retained_completion(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            campaign, unstable, _, aggregate = self.reduction(root)
            # An attempt is refused when it claims to chain from a before calibration that its previous lease
            # never took, even though its own evidence agrees with the claim.
            honest = next(receipt for receipt in campaign.receipts if (
                receipt["platform"], receipt["unit_id"], receipt["replica_id"]) == ("r8i", unstable["unit_id"], "1"))
            shutil.rmtree(campaign.directory(honest))
            campaign.receipts.remove(honest)
            # Each chained lease of the host closed one point higher; its fourth closed at 104.
            campaign.add("r8i", unstable, 1, host=1, chained_before=103.5)
            with self.assertRaisesRegex(aggregate_results.ReductionError,
                                        "does not chain from the completion of r8i-session-1/4: calibration_after"):
                aggregate([])


if __name__ == "__main__":
    unittest.main()
