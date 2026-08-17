import csv
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
SPECIFICATION = importlib.util.spec_from_file_location("generate_manifest", BASELINE / "generate-manifest.py")
GENERATE_MANIFEST = importlib.util.module_from_spec(SPECIFICATION)
sys.modules["generate_manifest"] = GENERATE_MANIFEST
SPECIFICATION.loader.exec_module(GENERATE_MANIFEST)


class TestGenerateManifest(unittest.TestCase):
    def testTraditionalManifestUsesCurrentPairContracts(self):
        generated = []
        GENERATE_MANIFEST.add_traditional_rows(generated, BASELINE.parents[2])
        with (BASELINE / "rows.tsv").open() as source:
            recorded = [GENERATE_MANIFEST.Row(**row) for row in csv.DictReader(source, delimiter="\t") if row["suite"] == "traditional"]
        self.assertEqual(sorted(generated), sorted(recorded))

    def testDeclaredSuiteMustProduceRows(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root, discovery = self.create_fixture(Path(temporary_directory), [])
            with self.assertRaisesRegex(ValueError, "declared JMH suite produced no rows"):
                GENERATE_MANIFEST.add_jmh_rows([], root, discovery)

    def testLiteralCorpusRowsAreBoundToPinnedInput(self):
        result = {
            "benchmark": "io.airlift.regulator.BenchmarkLiteralCorpus.countCaseInsensitive",
            "params": {"language": "ENGLISH"},
        }
        with tempfile.TemporaryDirectory() as temporary_directory:
            root, discovery = self.create_fixture(Path(temporary_directory), [result])
            rows = []
            GENERATE_MANIFEST.add_jmh_rows(rows, root, discovery)

        self.assertEqual(2, len(rows))
        self.assertEqual(
            {"regulator-native-access", "regulator-object-row"},
            {row.system for row in rows})
        for row in rows:
            self.assertEqual("count=522", row.expected_result)
            self.assertEqual(
                "rebar=463d00f31887e84c38467805b9e3122c314b9521;"
                "corpus=0d40805f6d02c8fe02bd75945b98911891f707e8ecb939e018446858065d76ea",
                row.workload_checksum)

    def testRebarComparatorRowsStayTogetherInDeterministicPartitions(self):
        workloads = [
            {
                "name": "compile-a",
                "model": "compile",
                "expected_result": "1",
                "pattern_sha256": "a",
                "haystack_sha256": "b",
            },
            {
                "name": "capture-a",
                "model": "grep-captures",
                "expected_result": "2",
                "pattern_sha256": "c",
                "haystack_sha256": "d",
            },
        ]
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifests = root / "tools/re2-benchmark/manifests"
            manifests.mkdir(parents=True)
            with (manifests / "rebar-workloads.csv").open("w", newline="") as output_file:
                writer = csv.DictWriter(output_file, fieldnames=tuple(workloads[0]))
                writer.writeheader()
                writer.writerows(workloads)
            with (manifests / "rebar-extended-workloads.tsv").open("w", newline="") as output_file:
                writer = csv.DictWriter(
                    output_file,
                    fieldnames=("name", "model", "definition_sha256"),
                    delimiter="\t")
                writer.writeheader()
            applicability = manifests / "rebar-joni-applicability.tsv"
            with applicability.open("w", newline="") as output_file:
                writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
                writer.writerow(("benchmark", "model", "status", "reason"))
                for workload in workloads:
                    writer.writerow((workload["name"], workload["model"], "compatible", "-"))
            applicability_digest = hashlib.sha256(applicability.read_bytes()).hexdigest()
            (manifests / "rebar-joni-applicability.sha256").write_text(
                f"{applicability_digest}  {applicability.name}\n")

            rows = []
            GENERATE_MANIFEST.add_rebar_rows(rows, root)

        for workload in workloads:
            matching = [row for row in rows if row.benchmark == workload["name"]]
            self.assertEqual(5, len(matching))
            self.assertEqual({
                "joni",
                "native-re2-after",
                "native-re2-before",
                "regulator-native-access",
                "regulator-object-row",
            }, {row.system for row in matching})
            self.assertEqual(1, len({row.shard_id for row in matching}))
            self.assertIn(matching[0].shard_id, GENERATE_MANIFEST.REBAR_SHARDS)

    def testEverydayTrinoRowsUseRotatingWorkloadManifest(self):
        header = "workload_id\tfamily\torigin\tsource_id\tslice_offset\texpected_match_count\tpattern\tsource\n"
        manifest = header + "".join(
            f'literal\tliteral\tdesigned-control\tsource-{index}\t{index % 2 * 3}\t{index % 2}\t"([^\"]*)"\tvalue-{index}\n'
            for index in range(8))
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            resources = root / "src/test/resources/io/airlift/regulator"
            resources.mkdir(parents=True)
            source = resources / "everyday-trino-workloads.tsv"
            source.write_text(manifest)
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            (resources / "everyday-trino-workloads.sha256").write_text(
                f"{digest}  {source.name}\n")

            rows = []
            GENERATE_MANIFEST.add_everyday_trino_rows(rows, root)

        self.assertEqual(24, len(rows))
        self.assertEqual(
            {"joni", "regulator-native-access", "regulator-object-row"},
            {row.system for row in rows})
        self.assertEqual({"trino-everyday-operations"}, {row.suite for row in rows})
        self.assertEqual({f"workload=literal"}, {row.parameters for row in rows})
        self.assertEqual({f"everyday={digest}"}, {row.workload_checksum for row in rows})

    def testEverydayTrinoRowsRejectWrongSourceCount(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            resources = root / "src/test/resources/io/airlift/regulator"
            resources.mkdir(parents=True)
            source = resources / "everyday-trino-workloads.tsv"
            source.write_text(
                "workload_id\tfamily\torigin\tsource_id\tslice_offset\texpected_match_count\tpattern\tsource\n"
                "literal\tliteral\tdesigned-control\tonly\t0\t1\tliteral\tliteral\n")
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            (resources / "everyday-trino-workloads.sha256").write_text(
                f"{digest}  {source.name}\n")

            with self.assertRaisesRegex(ValueError, "has 1 sources, expected 8"):
                GENERATE_MANIFEST.add_everyday_trino_rows([], root)

    def testEverydayTrinoRowsRejectChecksumMismatch(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            resources = root / "src/test/resources/io/airlift/regulator"
            resources.mkdir(parents=True)
            (resources / "everyday-trino-workloads.tsv").write_text("changed")
            (resources / "everyday-trino-workloads.sha256").write_text(
                "0" * 64 + "  everyday-trino-workloads.tsv\n")

            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                GENERATE_MANIFEST.add_everyday_trino_rows([], root)

    @staticmethod
    def create_fixture(root, results):
        baseline = root / "tools/re2-benchmark/baseline"
        manifests = root / "tools/re2-benchmark/manifests"
        discovery = root / "discovery"
        baseline.mkdir(parents=True)
        manifests.mkdir(parents=True)
        discovery.mkdir()

        with (baseline / "jmh-suites.tsv").open("w", newline="") as output_file:
            writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
            writer.writerow(("shard_id", "suite", "filter", "parameters", "systems", "comparator", "allocation_contract"))
            writer.writerow((
                "accepted-routes",
                "accepted-routes",
                "BenchmarkLiteralCorpus",
                "-",
                "regulator-native-access,regulator-object-row",
                "pinned-native-where-equivalent",
                "recorded"))

        with (manifests / "literal-corpus.tsv").open("w", newline="") as output_file:
            writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
            writer.writerow((
                "language",
                "rebar_revision",
                "source_path",
                "resource_path",
                "uncompressed_bytes",
                "sha256",
                "count",
                "case_insensitive_count"))
            writer.writerow((
                "ENGLISH",
                "463d00f31887e84c38467805b9e3122c314b9521",
                "en-sampled.txt",
                "en-sampled.txt.gzip",
                "899232",
                "0d40805f6d02c8fe02bd75945b98911891f707e8ecb939e018446858065d76ea",
                "513",
                "522"))

        (discovery / "accepted-routes-accepted-routes.json").write_text(json.dumps(results))
        return root, discovery


if __name__ == "__main__":
    unittest.main()
