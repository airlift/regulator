import importlib.util
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "engine_build.py"
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location("baseline_engine_build", SCRIPT)
ENGINE_BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ENGINE_BUILD)
NATIVE_BUILD_SPEC = importlib.util.spec_from_file_location(
    "language_native_build", SCRIPT.parents[1] / "language" / "native_build.py")
NATIVE_BUILD = importlib.util.module_from_spec(NATIVE_BUILD_SPEC)
NATIVE_BUILD_SPEC.loader.exec_module(NATIVE_BUILD)
ROOT = SCRIPT.parents[3]
PLATFORMS = tuple((name, row["architecture"], row["instance_type"])
                  for name, row in ENGINE_BUILD.load_platforms(SCRIPT.parent / "platforms.tsv").items())


class TestEngineBuild(unittest.TestCase):
    def package(self, root, platform=PLATFORMS[0], candidate="a" * 40, trino_commit="c7503d17", checkout=ROOT):
        """Build and accept one platform's engine build lease, as run-unit.sh does on a unit worker, from the
        checkout's build scripts."""
        name, architecture, instance_type = platform
        shared = root / f"builder-{name}"
        trino = shared / ENGINE_BUILD.TRINO_DIRECTORY
        (trino / "classes/io/trino").mkdir(parents=True)
        (trino / "classes/io/trino/Benchmark.class").write_text("class\n")
        # Regulator's own classes stay out of the archive; each host builds its candidate.
        (trino / "classpath.txt").write_text(f"{trino / 'classes'}:{root / 'regulator/target/classes'}")
        (trino / "joni-jar.path").write_text("joni.jar\n")
        (trino / "provenance.properties").write_text(f"trino_commit={trino_commit}\n")
        (shared / ENGINE_BUILD.LANGUAGE_NATIVE_DIRECTORY).mkdir()
        for runner in NATIVE_BUILD.SOURCES:
            for file in (runner, f"{runner}.build.json"):
                (shared / ENGINE_BUILD.LANGUAGE_NATIVE_DIRECTORY / file).write_text(file + "\n")
        result = root / name
        (result / "logs").mkdir(parents=True)
        (result / "logs/trino-build.log").write_text("built\n")
        (result / "unit-capacity.txt").write_text(
            "memory_total_bytes=34359738368\nmemory_available_before_bytes=30000000000\n"
            "swap_total_bytes=0\nswap_free_before_bytes=0\noom_kills_before=0\n")
        (result / "environment-manifest.txt").write_text(
            "verification_status=verified\nbenchmark_mode=unit-worker\nbaseline_campaign=baseline\n"
            f"platform={name}\nhost_epoch={PLATFORMS.index(platform) + 1}\ninstance_id=i-{name}\n"
            f"instance_type={instance_type}\ncampaign_architecture={architecture}\navailability_zone=us-west-2a\n"
            f"benchmark_heap_size=8g\nregulator_commit={candidate}\nregulator_archive_sha256=" + "5" * 64 + "\n")
        ENGINE_BUILD.package(shared, checkout, result)
        ENGINE_BUILD.accept(result, "1", f"session-{name}/1", "us-west-2", "e" * 40)
        with (result / "unit-capacity.txt").open("a") as capacity:
            capacity.write("wall_seconds=600\nmaximum_resident_kibibytes=1048576\nexit_status=0\n"
                           "memory_available_after_bytes=29000000000\nminimum_available_memory_bytes=3435973837\n"
                           "swap_free_after_bytes=0\noom_kills_after=0\ncapacity_status=accepted\n")
        (result / "status.txt").write_text("status=complete\n")
        receipt = ENGINE_BUILD.unit_session.read_one_tsv(
            result / "unit-receipt.tsv", ENGINE_BUILD.unit_session.UNIT_RECEIPT_FIELDS)
        self.assertEqual(ENGINE_BUILD.validate_unit_attempt(result), receipt)
        self.assertEqual(ENGINE_BUILD.sha256_file(result / "engine-build/engine-build.json"),
                         receipt["observed_rows_sha256"])
        self.assertEqual(sorted(json.loads((result / "engine-build/engine-build.json").read_text())
                                ["archives"]["trino"]["files"]),
                         ["pinned-trino/classes/io/trino/Benchmark.class", "pinned-trino/classpath.txt",
                          "pinned-trino/joni-jar.path", "pinned-trino/provenance.properties"])
        environment = root / "host-environment.txt"
        environment.write_text(f"platform={name}\nregulator_commit={candidate}\n"
                               "regulator_archive_sha256=" + "5" * 64 + "\n")
        return result / ENGINE_BUILD.DIRECTORY, receipt, environment

    def test_build_phase_accepts_one_build_per_platform_from_the_checkout_scripts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            receipts = [self.package(root, platform)[1] for platform in PLATFORMS]
            ENGINE_BUILD.validate_unit_campaign(ROOT, receipts, "baseline")
            with self.assertRaisesRegex(ENGINE_BUILD.unit_session.ValidationError, "coverage"):
                ENGINE_BUILD.validate_unit_campaign(ROOT, receipts[:1], "baseline")

    def test_build_attempt_refuses_a_worker_that_installed_prebuilt_engines(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.package(root)
            (root / "r8i/engine-build.txt").write_text("trino_harness=prebuilt\n")
            with self.assertRaisesRegex(ENGINE_BUILD.EngineBuildError, "built its own engines"):
                ENGINE_BUILD.validate_unit_attempt(root / "r8i")

    def test_host_installs_the_prebuilt_trino_harness_and_native_runners(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build, receipt, environment = self.package(root)
            manifest_sha256 = receipt["observed_rows_sha256"]
            shared = root / "host-shared"
            record = ENGINE_BUILD.install(build, manifest_sha256, shared, environment, root / "engine-build.txt")

            self.assertEqual("prebuilt", record["trino_harness"])
            self.assertTrue((shared / "pinned-trino/classes/io/trino/Benchmark.class").is_file())
            self.assertTrue((shared / "engine-build/trino.sha256").is_file())
            for runner in NATIVE_BUILD.SOURCES:
                self.assertTrue((shared / "language-native" / runner).is_file())
                self.assertTrue((shared / "language-native" / f"{runner}.build.json").is_file())
            self.assertTrue((shared / "engine-build/native.sha256").is_file())
            self.assertIn(f"engine_build_manifest_sha256={manifest_sha256}\n",
                          (root / "engine-build.txt").read_text())

    def test_build_script_builds_every_native_runner(self):
        script = (SCRIPT.parent / "build-engines.sh").read_text()
        targets = re.search(r'RE2_BENCHMARK_TARGETS="([^"]*)"', script)[1].split()
        self.assertEqual(sorted(NATIVE_BUILD.SOURCES), sorted(targets))

    def test_host_refuses_archive_that_differs_from_its_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build, receipt, environment = self.package(root)
            manifest_sha256 = receipt["observed_rows_sha256"]
            with (build / "trino.tar.gz").open("ab") as archive:
                archive.write(b"tampered")
            with self.assertRaisesRegex(ENGINE_BUILD.EngineBuildError, "checksum mismatch"):
                ENGINE_BUILD.install(build, manifest_sha256, root / "host-shared", environment,
                                     root / "engine-build.txt")
            self.assertFalse((root / "host-shared/engine-build/trino.sha256").exists())


if __name__ == "__main__":
    unittest.main()
