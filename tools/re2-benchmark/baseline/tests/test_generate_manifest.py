import csv
import hashlib
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASELINE))
SPECIFICATION = importlib.util.spec_from_file_location("generate_manifest", BASELINE / "generate-manifest.py")
GENERATE_MANIFEST = importlib.util.module_from_spec(SPECIFICATION)
sys.modules["generate_manifest"] = GENERATE_MANIFEST
SPECIFICATION.loader.exec_module(GENERATE_MANIFEST)


class TestGenerateManifest(unittest.TestCase):
    def testSpecializedManifestFollowsTheCatalog(self):
        generated = []
        GENERATE_MANIFEST.add_specialized_rows(generated, BASELINE.parents[2])
        shards = {entry["shard_id"] for entry in GENERATE_MANIFEST.read_specialized_catalog(BASELINE.parents[2])}
        with (BASELINE / "rows.tsv").open() as source:
            recorded = [
                GENERATE_MANIFEST.Row(**row) for row in csv.DictReader(source, delimiter="\t")
                if row["shard_id"] in shards]
        self.assertEqual(sorted(generated), sorted(recorded))

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


if __name__ == "__main__":
    unittest.main()
