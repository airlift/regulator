"""Optional same-host candidate/control/candidate qualification, using exact source archives."""

from contextlib import contextmanager
import shutil
import tarfile

import collection
import focused_controls
import jvm_build


ARCHIVE = "source-control/source.tar.gz"
PROVENANCE = "source-control/provenance.tsv"


def validate(manifest, directory):
    control = manifest.get("source_control")
    if control is None:
        return None
    if set(control) != {"archive_sha256", "provenance_sha256"}:
        raise ValueError("invalid source control descriptor")
    for name, key in ((ARCHIVE, "archive_sha256"), (PROVENANCE, "provenance_sha256")):
        path = directory / name
        if (not path.resolve().is_relative_to(directory.resolve()) or
                collection.digest(path.read_bytes()) != control[key]):
            raise ValueError("source control checksum mismatch")
    return control


def copy(manifest, source, destination):
    if validate(manifest, source) is None:
        return
    for name in (ARCHIVE, PROVENANCE):
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target)
    validate(manifest, destination)


def prepare(manifest, package, destination, java):
    if validate(manifest, package) is None:
        return None
    archive = package / ARCHIVE
    provenance = package / PROVENANCE
    root = destination / "control-source"
    root.mkdir(parents=True, exist_ok=False)
    with tarfile.open(archive) as files:
        files.extractall(root, filter="data")
    # Check the archive tree, exact commit, and extracted contents before executing its build.
    jvm_build.require_clean_source(root, archive, provenance)
    candidate = collection.ROOT
    shared = ["src/test/java/io/airlift/regulator/BenchmarkLanguageBulk.java",
              "src/test/java/io/airlift/regulator/BenchmarkLanguageComparison.java",
              "src/test/java/io/airlift/regulator/RebarRunner.java",
              "src/test/java/io/airlift/regulator/TestingLanguageBenchmarkInputs.java",
              "pom.xml",
              "tools/re2-benchmark/language/collection.py",
              "tools/re2-benchmark/language/bulk.py",
              "tools/re2-benchmark/language/language_bulk_benchmark.cc",
              "tools/re2-benchmark/language/language_benchmark.cc",
              "tools/re2-benchmark/baseline/comparators.tsv"]
    if any((root / name).read_bytes() != (candidate / name).read_bytes() for name in shared):
        raise ValueError("candidate and control measurement contracts differ")
    focused_controls.validate_sources(manifest, candidate, root)
    build = destination / "control-jvm"
    jvm_build.build(build, java, root, archive, provenance)
    return {"root": root, "archive": archive, "provenance": provenance,
            "classpath": (build / "classpath.txt").read_text().strip(),
            "receipt": build / "jvm-build.json"}


@contextmanager
def source_root(root):
    original = collection.ROOT
    collection.ROOT = root
    try:
        yield
    finally:
        collection.ROOT = original


def run(control, partitions, destination, java, classpath, native, receipt, archive, provenance, run_package):
    """Builds are already finished. Every leg uses fresh JVMs and complete verification."""
    candidate_root = collection.ROOT
    legs = [("candidate-before", candidate_root, destination / "results", classpath, receipt, archive, provenance)]
    if control is not None:
        legs.extend([
            ("control", control["root"], destination / "control-results", control["classpath"],
             control["receipt"], control["archive"], control["provenance"]),
            ("candidate-after", candidate_root, destination / "after-results", classpath, receipt, archive, provenance)])
    for _, root, results, leg_classpath, leg_receipt, leg_archive, leg_provenance in legs:
        with source_root(root):
            for partition in partitions:
                run_package(partition, results, java, leg_classpath, native, leg_receipt, leg_archive, leg_provenance)
                focused_controls.run(partition, results / partition.name, java, leg_classpath)
    if control is not None:
        collection.save(destination / "source-bracket.json", {
            "order": [leg[0] for leg in legs],
            "candidate": jvm_build.source_identity(candidate_root, archive, provenance),
            "control": jvm_build.source_identity(control["root"], control["archive"], control["provenance"]),
            "results": [str(leg[2].relative_to(destination)) for leg in legs]})
