import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "engine_build.py"
SPEC = importlib.util.spec_from_file_location("baseline_engine_build", SCRIPT)
ENGINE_BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ENGINE_BUILD)
ROOT = SCRIPT.parents[3]


class TestEngineBuild(unittest.TestCase):
    def package(self, root):
        shared = root / "builder-shared"
        trino = shared / ENGINE_BUILD.TRINO_DIRECTORY
        (trino / "classes/io/trino").mkdir(parents=True)
        (trino / "classes/io/trino/Benchmark.class").write_text("class\n")
        # Regulator's own classes stay out of the archive; each host builds its candidate.
        (trino / "classpath.txt").write_text(f"{trino / 'classes'}:{root / 'regulator/target/classes'}")
        (trino / "joni-jar.path").write_text("joni.jar\n")
        (trino / "provenance.properties").write_text("trino_commit=c7503d17\n")
        result = root / "result"
        result.mkdir()
        (result / "environment-manifest.txt").write_text(
            "verification_status=verified\nbenchmark_mode=engine-build\nbaseline_campaign=baseline\n"
            "platform=r8i\nshard=engine-build\nreplica=1\nhost_epoch=300001\ninstance_id=i-1\n"
            "instance_type=r8i.2xlarge\ncampaign_architecture=intel\nregulator_commit=" + "a" * 40 + "\n"
            "regulator_archive_sha256=" + "5" * 64 + "\n")
        ENGINE_BUILD.package(shared, ROOT, result)
        receipt = json.loads((result / ENGINE_BUILD.RECEIPT).read_text())
        self.assertEqual(ENGINE_BUILD.validate(result), receipt)
        self.assertEqual(sorted(json.loads((result / "engine-build/engine-build.json").read_text())
                                ["archives"]["trino"]["files"]),
                         ["pinned-trino/classes/io/trino/Benchmark.class", "pinned-trino/classpath.txt",
                          "pinned-trino/joni-jar.path", "pinned-trino/provenance.properties"])
        environment = root / "host-environment.txt"
        environment.write_text("platform=r8i\nregulator_commit=" + "a" * 40 + "\n"
                               "regulator_archive_sha256=" + "5" * 64 + "\n")
        return result / ENGINE_BUILD.DIRECTORY, receipt["manifest_sha256"], environment

    def test_host_installs_the_prebuilt_trino_harness(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build, manifest_sha256, environment = self.package(root)
            shared = root / "host-shared"
            record = ENGINE_BUILD.install(build, manifest_sha256, shared, environment, root / "engine-build.txt")

            self.assertEqual("prebuilt", record["trino_harness"])
            self.assertTrue((shared / "pinned-trino/classes/io/trino/Benchmark.class").is_file())
            self.assertTrue((shared / "engine-build/trino.sha256").is_file())
            self.assertIn(f"engine_build_manifest_sha256={manifest_sha256}\n",
                          (root / "engine-build.txt").read_text())

    def test_host_refuses_archive_that_differs_from_its_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build, manifest_sha256, environment = self.package(root)
            with (build / "trino.tar.gz").open("ab") as archive:
                archive.write(b"tampered")
            with self.assertRaisesRegex(ENGINE_BUILD.EngineBuildError, "checksum mismatch"):
                ENGINE_BUILD.install(build, manifest_sha256, root / "host-shared", environment,
                                     root / "engine-build.txt")
            self.assertFalse((root / "host-shared/engine-build/trino.sha256").exists())


if __name__ == "__main__":
    unittest.main()
