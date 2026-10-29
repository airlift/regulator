import importlib.util
import io
import re
import shutil
import subprocess
import sys
import tarfile
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


def members(path):
    """An archive's member names, as a host's zstd reads them."""
    stream = subprocess.run(["zstd", "-dcq", str(path)], capture_output=True, check=True).stdout
    with tarfile.open(fileobj=io.BytesIO(stream)) as archive:
        return sorted(archive.getnames())


class TestEngineBuild(unittest.TestCase):
    def package(self, root, platform=PLATFORMS[0], candidate="a" * 40, trino_commit="c7503d17", scripts=ROOT):
        """Build and accept one platform's engine build lease, as run-unit.sh does on a unit worker, from a
        candidate checkout holding copies of the build scripts in scripts."""
        name, architecture, instance_type = platform
        checkout = root / f"candidate-{name}"
        for path in ENGINE_BUILD.BUILD_INPUTS:
            (checkout / path).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(scripts / path, checkout / path)
        # build-engines.sh compiles the candidate, resolves its dependencies, and adds the console launcher.
        for directory in ("classes", "test-classes"):
            (checkout / "target" / directory / "io/airlift").mkdir(parents=True)
            (checkout / "target" / directory / "io/airlift/Fixture.class").write_text(directory + "\n")
        repository = root / f"repository-{name}"
        repository.mkdir()
        dependencies = [repository / "slice-2.8.jar", repository / "jmh-core-1.37.jar"]
        for jar in dependencies:
            jar.write_text(jar.name + "\n")
        shared = root / f"builder-{name}"
        (shared / ENGINE_BUILD.CLASSES_DIRECTORY / "lib").mkdir(parents=True)
        (shared / ENGINE_BUILD.CLASSES_DIRECTORY / ENGINE_BUILD.CONSOLE_LAUNCHER).write_text("launcher\n")
        (shared / ENGINE_BUILD.CLASSES_DEPENDENCIES).write_text(":".join(map(str, dependencies)) + "\n")
        trino = shared / ENGINE_BUILD.TRINO_DIRECTORY
        (trino / "classes/io/trino").mkdir(parents=True)
        (trino / "classes/io/trino/Benchmark.class").write_text("class\n")
        # prepare.sh compiles the harness against the builder's candidate classes and dependencies.
        (trino / "classpath.txt").write_text(f"{trino / 'classes'}:{checkout / 'target/classes'}:{dependencies[0]}")
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
            "instance_market_type=spot\n"
            f"benchmark_heap_size=8g\nregulator_commit={candidate}\nregulator_archive_sha256=" + "5" * 64 + "\n")
        ENGINE_BUILD.package(shared, checkout, result)
        ENGINE_BUILD.accept(result, "1", ENGINE_BUILD.GROUP_ID, f"session-{name}/1", "us-west-2", "e" * 40)
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
        self.assertEqual(members(result / "engine-build/trino.tar.zst"),
                         ["pinned-trino/classes/io/trino/Benchmark.class", "pinned-trino/classpath.txt",
                          "pinned-trino/joni-jar.path", "pinned-trino/provenance.properties"])
        # The archive carries exactly the runners the language build produces, each beside its build receipt.
        self.assertEqual(members(result / "engine-build/native.tar.zst"),
                         sorted(f"{ENGINE_BUILD.LANGUAGE_NATIVE_DIRECTORY}/{runner}{suffix}"
                                for runner in NATIVE_BUILD.SOURCES for suffix in ("", ".build.json")))
        classes = shared / ENGINE_BUILD.CLASSES_DIRECTORY
        self.assertEqual(members(result / "engine-build/classes.tar.zst"), [
            "regulator-classes/classes/io/airlift/Fixture.class", "regulator-classes/classpath.txt",
            "regulator-classes/lib/jmh-core-1.37.jar", "regulator-classes/lib/junit-platform-console-standalone.jar",
            "regulator-classes/lib/slice-2.8.jar", "regulator-classes/test-classes/io/airlift/Fixture.class"])
        self.assertEqual("test-classes:classes:lib/slice-2.8.jar:lib/jmh-core-1.37.jar\n",
                         (classes / "classpath.txt").read_text())
        # The harness runs the packaged candidate classes, not the builder's.
        self.assertEqual(f"{trino / 'classes'}:{classes / 'classes'}:{classes / 'lib/slice-2.8.jar'}",
                         (trino / "classpath.txt").read_text())
        return result / ENGINE_BUILD.DIRECTORY, receipt

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
            (root / "r8i/engine-build.txt").write_text("engine_build_manifest_sha256=" + "a" * 64 + "\n")
            with self.assertRaisesRegex(ENGINE_BUILD.EngineBuildError, "built its own engines"):
                ENGINE_BUILD.validate_unit_attempt(root / "r8i")

    def test_unpack_refuses_a_second_spelling_of_a_path_and_links(self):
        def entry(name, kind=tarfile.REGTYPE):
            member = tarfile.TarInfo(name)
            member.type = kind
            member.linkname = "" if kind == tarfile.REGTYPE else "classes/a"
            return member

        for refused in (entry("./classes/a"), entry("classes//a"), entry("classes/b", tarfile.SYMTYPE),
                        entry("classes/b", tarfile.LNKTYPE)):
            stream = io.BytesIO()
            with tarfile.open(fileobj=stream, mode="w") as archive:
                for member in (entry("classes/a"), refused):
                    archive.addfile(member)
            stream.seek(0)
            with self.subTest(name=refused.name, type=refused.type), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(ENGINE_BUILD.EngineBuildError,
                                            f"unsafe archive entry: '{re.escape(refused.name)}'"):
                    ENGINE_BUILD.unpack(stream, Path(directory))

    def test_build_script_builds_every_native_runner(self):
        script = (SCRIPT.parent / "build-engines.sh").read_text()
        targets = re.search(r'RE2_BENCHMARK_TARGETS="([^"]*)"', script)[1].split()
        self.assertEqual(sorted(NATIVE_BUILD.SOURCES), sorted(targets))


if __name__ == "__main__":
    unittest.main()
