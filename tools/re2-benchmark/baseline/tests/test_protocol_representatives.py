import csv
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASELINE))
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
                ("joni", "io.trino.operator.scalar.BenchmarkEverydayTrinoJoni.searchJoni")):
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
            benchmark = "io.trino.operator.scalar.BenchmarkEverydayTrinoJoni.searchJoni"
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
        trino_like = {
            "row_id": "trino-like/example/trino-optimized",
            "shard_id": "trino-like",
            "system": "trino-optimized",
            "benchmark": "matches",
        }
        self.assertEqual(
            "io.airlift.regulator.benchmark.BenchmarkTrinoLike.trinoOptimized",
            VALIDATOR.actual_jmh_benchmark(trino_like))

    @staticmethod
    def write_tsv(path, rows, fieldnames=None):
        if fieldnames is None:
            fieldnames = tuple(rows[0])
        with path.open("w", newline="") as output_file:
            writer = csv.DictWriter(output_file, fieldnames=fieldnames, delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)


if __name__ == "__main__":
    unittest.main()
