"""Bind the candidate classes a language unit measures to their engine build and source."""

import csv
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import zipfile

import source_archive
import released_artifact

sys.path.append(str(Path(__file__).resolve().parents[1] / "baseline"))
import engine_build  # noqa: E402


JONI_CLASS = "io/airlift/joni/Regex.class"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def java_executable(java):
    # /usr/bin/java on macOS is a launcher, not the selected JDK's executable.
    settings = subprocess.check_output([java, "-XshowSettings:properties", "-version"], stderr=subprocess.STDOUT).decode()
    for line in settings.splitlines():
        name, separator, value = line.strip().partition(" = ")
        if separator and name == "java.home":
            return (Path(value) / "bin/java").resolve()
    raise ValueError("Java did not identify its runtime home")


def jvm_identity(java, classpath):
    """The JDK and the ordered classpath a JVM runs; the classes' content is the engine build's archive."""
    java_path = java_executable(java)
    return {"java_path": str(java_path), "java_binary_sha256": digest(java_path.read_bytes()),
            "jdk": subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT).decode(),
            "classpath": [str(Path(entry).resolve()) for entry in classpath.split(os.pathsep)]}


def validate_joni(classpath, comparator_manifest):
    with comparator_manifest.open() as file:
        pin = next(row for row in csv.DictReader(file, delimiter="\t") if row["component"] == "airlift-joni")
    providers = []
    for entry in classpath.split(os.pathsep):
        path = Path(entry).resolve()
        if path.is_dir():
            if (path / JONI_CLASS).exists():
                raise ValueError("Joni classes must come from the pinned jar")
        else:
            with zipfile.ZipFile(path) as jar:
                if JONI_CLASS in jar.namelist():
                    providers.append(path)
    if len(providers) != 1 or digest(providers[0].read_bytes()) != pin["artifact_sha256"]:
        raise ValueError("classpath does not contain exactly the pinned Joni artifact")


def prepare(shared, java, root, archive, provenance):
    """The classpath of the installed candidate classes, and its receipt: the extracted source's identity, the
    classes archive's SHA-256, the JDK, and, when a release is selected, the release JAR that replaces the
    candidate's production classes."""
    source = source_archive.read(root, archive, provenance)
    classpath, classes_archive_sha256 = engine_build.installed_classes(shared)
    version = os.environ.get("REGULATOR_RELEASE_VERSION")
    if version:
        release_directory = shared / "language-release"
        production = str(shared / engine_build.CLASSES_DIRECTORY / "classes")
        jar, identity = released_artifact.prepare(root, version, release_directory)
        classpath = os.pathsep.join(str(jar) if entry == production else entry for entry in classpath.split(os.pathsep))
    pins = root / "tools/re2-benchmark/baseline/comparators.tsv"
    validate_joni(classpath, pins)
    receipt = {"schema_version": 2, **source, "classes_archive_sha256": classes_archive_sha256,
               "comparators_sha256": digest(pins.read_bytes()), "jvm": jvm_identity(java, classpath)}
    if version:
        receipt["released_artifact"] = released_artifact.attest(
            classpath, jar, identity, release_directory, receipt["jvm"]["java_path"])
    return classpath, receipt
