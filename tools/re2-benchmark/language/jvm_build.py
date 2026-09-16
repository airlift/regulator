#!/usr/bin/env python3
"""Build a clean candidate in isolation and bind its JVM artifacts to its source."""

import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import zipfile

import source_archive
import released_artifact


ROOT = Path(__file__).resolve().parents[3]
JONI_CLASS = "io/airlift/joni/Regex.class"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def source_identity(root, archive=None, provenance=None):
    if archive is not None or provenance is not None:
        if archive is None or provenance is None:
            raise ValueError("archive mode requires both source archive and candidate provenance")
        return source_archive.read(root, archive, provenance)[0]
    return {"source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root).decode().strip(),
            "source_tree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=root).decode().strip()}


def require_clean_source(root, archive=None, provenance=None):
    if archive is not None or provenance is not None:
        source_identity(root, archive, provenance)
        return
    if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=root):
        raise ValueError("JVM qualification build requires a clean candidate checkout")


def java_executable(java):
    # /usr/bin/java on macOS is a launcher, not the selected JDK's executable.
    settings = subprocess.check_output([java, "-XshowSettings:properties", "-version"], stderr=subprocess.STDOUT).decode()
    for line in settings.splitlines():
        name, separator, value = line.strip().partition(" = ")
        if separator and name == "java.home":
            return (Path(value) / "bin/java").resolve()
    raise ValueError("Java did not identify its runtime home")


def jvm_identity(java, classpath):
    artifacts = released_artifact.classpath_identity(classpath)
    java_path = java_executable(java)
    return {"java_path": str(java_path), "java_binary_sha256": digest(java_path.read_bytes()),
            "jdk": subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT).decode(),
            "classpath": artifacts}


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


def validate_receipt(receipt, root, java, classpath, archive=None, provenance=None):
    if receipt["schema_version"] != 1 or receipt["source_tree"] != source_identity(root, archive, provenance)["source_tree"]:
        raise ValueError("JVM build receipt identifies a different candidate tree")
    if receipt["jvm"] != jvm_identity(java, classpath):
        raise ValueError("JVM artifacts differ from the clean build receipt")
    pins = root / "tools/re2-benchmark/baseline/comparators.tsv"
    if receipt["comparators_sha256"] != digest(pins.read_bytes()):
        raise ValueError("JVM build comparator pins changed")
    validate_joni(classpath, pins)
    release = receipt.get("released_artifact")
    version = os.environ.get("REGULATOR_RELEASE_VERSION")
    if version and (release is None or release["manifest"] != released_artifact.manifest(root, version)):
        raise ValueError("JVM build does not identify the selected released artifact")
    if release is not None:
        released_artifact.validate_saved(release, receipt["jvm"])
        released_artifact.validate_classpath(classpath, Path(release["jar_path"]), release["manifest"])


def build(destination, java="java", root=ROOT, archive=None, provenance=None):
    root = root.resolve()
    destination = destination.resolve()
    require_clean_source(root, archive, provenance)
    source = source_identity(root, archive, provenance)
    destination.mkdir(parents=True, exist_ok=False)
    checkout = destination / "source"
    checkout.mkdir()
    archive_bytes = (source_archive.read(root, archive, provenance)[1] if archive is not None else
                     subprocess.check_output(["git", "archive", "--format=tar", source["source_commit"]], cwd=root))
    with tarfile.open(fileobj=io.BytesIO(archive_bytes)) as files:
        files.extractall(checkout, filter="data")
    java_path = java_executable(java)
    environment = dict(os.environ)
    environment["JAVA_HOME"] = str(java_path.parent.parent)
    dependencies = destination / "dependencies.classpath"
    command = [str(checkout / "mvnw"), "-q", "clean", "test-compile", "dependency:build-classpath",
               "-Dmaven.gitcommitid.skip=true", "-DincludeScope=test", f"-Dmdep.outputFile={dependencies}"]
    with (destination / "build.log").open("wb") as log:
        subprocess.run(command, cwd=checkout, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
    version = os.environ.get("REGULATOR_RELEASE_VERSION")
    production = checkout / "target/classes"
    if version:
        production, release_identity = released_artifact.prepare(checkout, version, destination / "release")
    classpath = os.pathsep.join([str(checkout / "target/test-classes"), str(production),
                                dependencies.read_text().strip()])
    require_clean_source(root, archive, provenance)
    if source_identity(root, archive, provenance) != source:
        raise ValueError("candidate changed during JVM build")
    pins = root / "tools/re2-benchmark/baseline/comparators.tsv"
    validate_joni(classpath, pins)
    receipt = {"schema_version": 1, **source, "archive_sha256": digest(archive_bytes),
               "command": command, "comparators_sha256": digest(pins.read_bytes()),
               "jvm": jvm_identity(str(java_path), classpath)}
    if version:
        receipt["released_artifact"] = released_artifact.attest(
            classpath, production, release_identity, destination / "release", str(java_path))
    (destination / "classpath.txt").write_text(classpath + "\n")
    (destination / "jvm-build.json").write_text(json.dumps(receipt, sort_keys=True) + "\n")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-directory", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--source-archive", type=Path)
    parser.add_argument("--candidate-provenance", type=Path)
    args = parser.parse_args()
    build(args.output_directory, args.java, archive=args.source_archive, provenance=args.candidate_provenance)
