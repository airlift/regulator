import csv
import importlib.util
import itertools
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
SPECIFICATION = importlib.util.spec_from_file_location(
    "validate_protocol_representatives",
    BASELINE / "validate-protocol-representatives.py")
VALIDATOR = importlib.util.module_from_spec(SPECIFICATION)
sys.modules["validate_protocol_representatives"] = VALIDATOR
SPECIFICATION.loader.exec_module(VALIDATOR)


class TestProtocolRepresentatives(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.manifest = self.root / "rows.tsv"
        self.dispatch = self.root / "shard-dispatch.tsv"
        self.representatives = self.root / "protocol-representatives.tsv"

        manifest_rows = []
        for system, benchmark in (
                ("regulator-native-access", "example.RegulatorBenchmark.search"),
                ("joni", "io.trino.operator.scalar.BenchmarkTrinoJoniComparator.searchJoni")):
            for index in range(31):
                manifest_rows.append({
                    "row_id": f"bounded/{system}/{index}",
                    "shard_id": "bounded",
                    "suite": "bounded",
                    "system": system,
                    "benchmark": benchmark,
                    "parameters": f"size={index}",
                    "comparator": "test",
                    "expected_result": "test",
                    "allocation_contract": "recorded",
                    "workload_checksum": "test",
                })
        self.write_tsv(self.manifest, manifest_rows)
        self.write_tsv(self.dispatch, [{
            "shard_id": "bounded",
            "native_access_handler": "jmh",
            "native_access_systems": "regulator-native-access,joni",
            "object_row_handler": "-",
            "object_row_systems": "-",
            "semantic_tests": "TestSemantic",
            "blocker": "-",
        }])
        self.valid_representatives = [
            self.representative("1", "regulator-native-access", 0),
            self.representative("1", "joni", 0),
            self.representative("2", "regulator-native-access", 1),
            self.representative("3", "joni", 1),
        ]
        self.write_tsv(
            self.representatives,
            self.valid_representatives,
            VALIDATOR.REPRESENTATIVE_FIELDS)

    def tearDown(self):
        self.temporary_directory.cleanup()

    def representative(self, replica_id, system, index):
        if system == "joni":
            benchmark = "io.trino.operator.scalar.BenchmarkTrinoJoniComparator.searchJoni"
            classpath = "pinned-trino"
        else:
            benchmark = "example.RegulatorBenchmark.search"
            classpath = "regulator"
        return {
            "shard_id": "bounded",
            "replica_id": replica_id,
            "route": "native-access",
            "system": system,
            "manifest_row_id": f"bounded/{system}/{index}",
            "jmh_benchmark": benchmark,
            "parameters": f"size={index}",
            "classpath": classpath,
        }

    def validate(self):
        return VALIDATOR.validate(self.representatives, self.manifest, self.dispatch)

    def testAcceptsCompleteRepresentativeManifest(self):
        self.assertEqual((4, 1, 2), self.validate())

    def testRejectsMissingRouteReplica(self):
        rows = [row for row in self.valid_representatives if row["replica_id"] != "2"]
        self.write_tsv(self.representatives, rows, VALIDATOR.REPRESENTATIVE_FIELDS)
        with self.assertRaisesRegex(ValueError, "missing bounded route/replica representatives"):
            self.validate()

    def testRejectsMissingFamilySystemCoverage(self):
        rows = [
            self.representative("1", "regulator-native-access", 0),
            self.representative("2", "regulator-native-access", 1),
            self.representative("3", "regulator-native-access", 2),
        ]
        self.write_tsv(self.representatives, rows, VALIDATOR.REPRESENTATIVE_FIELDS)
        with self.assertRaisesRegex(ValueError, "missing benchmark family/system/route coverage"):
            self.validate()

    def testRejectsBadExactRowBinding(self):
        rows = [dict(row) for row in self.valid_representatives]
        rows[0]["parameters"] = "size=999"
        self.write_tsv(self.representatives, rows, VALIDATOR.REPRESENTATIVE_FIELDS)
        with self.assertRaisesRegex(ValueError, "parameters does not match manifest row"):
            self.validate()

    def testRejectsBadClasspath(self):
        rows = [dict(row) for row in self.valid_representatives]
        rows[0]["classpath"] = "special"
        self.write_tsv(self.representatives, rows, VALIDATOR.REPRESENTATIVE_FIELDS)
        with self.assertRaisesRegex(ValueError, "classpath does not match regulator"):
            self.validate()

    def testRejectsUnknownClasspath(self):
        rows = [dict(row) for row in self.valid_representatives]
        rows[0]["classpath"] = "unknown"
        self.write_tsv(self.representatives, rows, VALIDATOR.REPRESENTATIVE_FIELDS)
        with self.assertRaisesRegex(ValueError, "invalid classpath unknown"):
            self.validate()

    def testMapsGeneratedBenchmarkMethods(self):
        final_line = {
            "row_id": "trino-final-line/example/count/joni",
            "shard_id": "trino-final-line",
            "system": "joni",
            "benchmark": "count",
        }
        trino_like = {
            "row_id": "trino-like/example/trino-optimized",
            "shard_id": "trino-like",
            "system": "trino-optimized",
            "benchmark": "matches",
        }
        self.assertEqual(
            "io.trino.operator.scalar.BenchmarkTrinoFinalLine.countJoni",
            VALIDATOR.actual_jmh_benchmark(final_line))
        self.assertEqual(
            "io.airlift.regulator.benchmark.BenchmarkTrinoLike.trinoOptimized",
            VALIDATOR.actual_jmh_benchmark(trino_like))

    def testFullProtocolBenchmarksAreExactManifestMethods(self):
        manifest = self.read_tsv(BASELINE / "rows.tsv")
        manifest_methods = {
            (row["shard_id"], route, VALIDATOR.actual_jmh_benchmark(row))
            for row in manifest
            for route, system in (
                ("native-access", "regulator-native-access"),
                ("object-row", "regulator-object-row"))
            if row["system"] == system
        }
        overrides = self.read_tsv(BASELINE / "full-protocol-benchmarks.tsv")

        self.assertEqual(len(overrides), len({tuple(row.values()) for row in overrides}))
        for row in overrides:
            self.assertIn(
                (row["shard_id"], row["route"], row["jmh_benchmark"]),
                manifest_methods)
            system = {
                "native-access": "regulator-native-access",
                "object-row": "regulator-object-row",
            }[row["route"]]
            full_parameters = self.expand_parameters(row["full_parameters"])
            exact_rows = {
                manifest_row["parameters"]
                for manifest_row in manifest
                if manifest_row["shard_id"] == row["shard_id"]
                and manifest_row["system"] == system
                and VALIDATOR.actual_jmh_benchmark(manifest_row) == row["jmh_benchmark"]
                and manifest_row["parameters"] in full_parameters
            }
            self.assertEqual(full_parameters, exact_rows, row)
            manifest_parameters = {
                manifest_row["parameters"]
                for manifest_row in manifest
                if manifest_row["shard_id"] == row["shard_id"]
                and manifest_row["system"] == system
                and VALIDATOR.actual_jmh_benchmark(manifest_row) == row["jmh_benchmark"]
            }
            bounded_parameters = self.expand_parameters(row["bounded_parameters"])
            self.assertFalse(full_parameters & bounded_parameters, row)
            self.assertEqual(manifest_parameters, full_parameters | bounded_parameters, row)

    def testExtraSearchFamilyUsesOnlyFullProtocolShards(self):
        manifest = self.read_tsv(BASELINE / "rows.tsv")
        shards = {row["shard_id"] for row in manifest
                  if row["benchmark"].startswith("io.airlift.regulator.BenchmarkRe2SearchExtra.")}
        self.assertEqual(8, len(shards))
        bounded = VALIDATOR.bounded_routes(manifest, VALIDATOR.load_dispatch(BASELINE / "shard-dispatch.tsv"))
        self.assertFalse(shards & {shard for shard, _ in bounded})
        for filename in ("full-protocol-benchmarks.tsv", "protocol-representatives.tsv"):
            self.assertFalse(shards & {row["shard_id"] for row in self.read_tsv(BASELINE / filename)})

    def testLiteralCorpusEnglishCountUsesFullProtocolForBothMemoryRoutes(self):
        overrides = self.read_tsv(BASELINE / "full-protocol-benchmarks.tsv")
        literal_corpus_overrides = {
            (row["route"], row["full_parameters"], row["bounded_parameters"])
            for row in overrides
            if row["shard_id"] == "accepted-routes"
            and row["jmh_benchmark"] == "io.airlift.regulator.BenchmarkLiteralCorpus.count"
        }

        self.assertEqual({
            ("native-access", "language=ENGLISH", "language=CHINESE,RUSSIAN"),
            ("object-row", "language=ENGLISH", "language=CHINESE,RUSSIAN"),
        }, literal_corpus_overrides)

    def testBoundedContextCountUsesFullProtocolForBothMemoryRoutes(self):
        overrides = self.read_tsv(BASELINE / "full-protocol-benchmarks.tsv")
        bounded_count_overrides = {
            (row["route"], row["full_parameters"], row["bounded_parameters"])
            for row in overrides
            if row["shard_id"] == "accepted-routes"
            and row["jmh_benchmark"] == "io.airlift.regulator.BenchmarkDfaCountMatches.batched"
        }

        self.assertEqual({
            (
                "native-access",
                "sourceLength=32768;workload=boundedContext",
                "sourceLength=32768;workload=denseWords,captureShape",
            ),
            (
                "object-row",
                "sourceLength=32768;workload=boundedContext",
                "sourceLength=32768;workload=denseWords,captureShape",
            ),
        }, bounded_count_overrides)

    def testRunShardMatchesGroupedFullProtocolParameters(self):
        source = (BASELINE / "run-shard.sh").read_text()

        self.assertIn("parameters_match_selection()", source)
        self.assertIn(
            'parameters_match_selection "${full_parameters}" "${target_parameters}"',
            source)

    @staticmethod
    def write_tsv(path, rows, fieldnames=None):
        if fieldnames is None:
            fieldnames = tuple(rows[0])
        with path.open("w", newline="") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=fieldnames, delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)

    @staticmethod
    def read_tsv(path):
        with path.open(newline="", encoding="utf-8") as input_file:
            return list(csv.DictReader(input_file, delimiter="\t"))

    @staticmethod
    def expand_parameters(parameters):
        names = []
        values = []
        for parameter in parameters.split(";"):
            name, configured_values = parameter.split("=", 1)
            names.append(name)
            values.append(configured_values.split(","))
        return {
            ";".join(f"{name}={value}" for name, value in zip(names, combination))
            for combination in itertools.product(*values)
        }


if __name__ == "__main__":
    unittest.main()
