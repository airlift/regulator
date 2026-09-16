"""Bind public measurements to a checksummed release, independently of collector source."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
import zipfile

import source_archive


PUBLIC_CLASSES = ("Re2", "JavaRegexp", "TrinoRegexp", "TrinoLikePattern")
PACKAGE = "io/airlift/regulator/"
ORIGIN_PROBE = '''
import java.nio.file.Path;
class ReleaseArtifactOrigin {
    public static void main(String[] args) throws Exception {
        Path expected = Path.of(args[0]).toRealPath();
        for (String name : new String[]{"Re2", "JavaRegexp", "TrinoRegexp", "TrinoLikePattern"}) {
            Class<?> type = Class.forName("io.airlift.regulator." + name, false,
                    ClassLoader.getSystemClassLoader());
            Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!actual.equals(expected)) {
                throw new AssertionError(type.getName() + " loaded from " + actual);
            }
            System.out.println(type.getName() + "\\t" + actual);
        }
    }
}
'''


def digest(data):
    return hashlib.sha256(data).hexdigest()


def manifest(root, version):
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", version):
        raise ValueError("invalid release version")
    path = root / "tools/re2-benchmark/baseline/releases" / (version + ".json")
    value = json.loads(path.read_text())
    validate_manifest(value)
    if value["version"] != version:
        raise ValueError("release manifest has a different version")
    return value


def validate_manifest(value):
    if (value.get("schema_version") != 1 or value.get("group_id") != "io.airlift" or
            value.get("artifact_id") != "regulator" or
            not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", value.get("version", ""))):
        raise ValueError("invalid release manifest")
    for name, length in (("source_commit", 40), ("engine_tree", 40), ("jar_sha256", 64)):
        if not re.fullmatch(r"[0-9a-f]{" + str(length) + r"}", value.get(name, "")):
            raise ValueError("invalid release identity: " + name)


def production_tree(root):
    directory = root / "src/main"
    files = {}
    for path in directory.rglob("*"):
        if path.is_symlink():
            files[str(path.relative_to(directory))] = ("120000", str(path.readlink()).encode())
        elif path.is_file():
            files[str(path.relative_to(directory))] = (
                "100755" if path.stat().st_mode & 0o111 else "100644", path.read_bytes())
    return source_archive.git_tree(files)


def prepare(root, version, destination):
    identity = manifest(root, version)
    if production_tree(root) != identity["engine_tree"]:
        raise ValueError("diagnostic production source differs from the release tag")
    destination.mkdir(parents=True, exist_ok=True)
    jar = destination / ("regulator-" + version + ".jar")
    if not jar.exists():
        url = f"https://repo.maven.apache.org/maven2/io/airlift/regulator/{version}/{jar.name}"
        with urllib.request.urlopen(url, timeout=60) as response:
            payload = response.read()
        if digest(payload) != identity["jar_sha256"]:
            raise ValueError("downloaded release JAR checksum mismatch")
        with jar.open("xb") as output:
            output.write(payload)
    if digest(jar.read_bytes()) != identity["jar_sha256"]:
        raise ValueError("release JAR checksum mismatch")
    return jar.resolve(), identity


def validate_classpath(classpath, jar, identity):
    jar = jar.resolve()
    if digest(jar.read_bytes()) != identity["jar_sha256"]:
        raise ValueError("release JAR checksum mismatch")
    with zipfile.ZipFile(jar) as archive:
        production = {name for name in archive.namelist() if name.startswith(PACKAGE) and name.endswith(".class")}
    if not all(PACKAGE + name + ".class" in production for name in PUBLIC_CLASSES):
        raise ValueError("release JAR lacks public classes")
    if any(not entry for entry in classpath.split(os.pathsep)):
        raise ValueError("empty classpath entry")
    entries = [Path(entry).resolve() for entry in classpath.split(os.pathsep)]
    if entries.count(jar) != 1:
        raise ValueError("classpath must contain the released JAR exactly once")
    for entry in entries:
        if entry == jar:
            continue
        if entry.is_dir():
            shadowed = {name for name in production if (entry / name).exists()}
        else:
            with zipfile.ZipFile(entry) as archive:
                shadowed = production.intersection(archive.namelist())
        if shadowed:
            raise ValueError(f"production classes shadow the released JAR in {entry}: {sorted(shadowed)[:3]}")
    return sorted(production)


def classpath_identity(classpath):
    artifacts = []
    for entry in classpath.split(os.pathsep):
        if not entry:
            raise ValueError("empty classpath entry")
        path = Path(entry).resolve()
        if not path.exists():
            raise ValueError(f"missing classpath entry: {path}")
        files = sorted(path.rglob("*")) if path.is_dir() else [path]
        artifacts.append({"path": str(path), "files": {
            str(file.relative_to(path) if path.is_dir() else file.name): digest(file.read_bytes())
            for file in files if file.is_file()}})
    return artifacts


def attest(classpath, jar, identity, destination, java="java"):
    jar = jar.resolve()
    production = validate_classpath(classpath, jar, identity)
    probe = destination / "ReleaseArtifactOrigin.java"
    probe.write_text(ORIGIN_PROBE)
    command = [java, "--add-modules=jdk.incubator.vector", "-Xms64m", "-Xmx256m", "-cp", classpath,
               str(probe), str(jar)]
    output = subprocess.check_output(command, stderr=subprocess.STDOUT, text=True)
    (destination / "artifact-origin.log").write_text(output)
    origins = dict(line.split("\t", 1) for line in output.splitlines() if line.startswith("io.airlift.regulator."))
    expected = {"io.airlift.regulator." + name: str(jar) for name in PUBLIC_CLASSES}
    if origins != expected:
        raise ValueError("runtime class-origin assertion is incomplete")
    receipt = {"manifest": identity, "jar_path": str(jar), "code_sources": origins,
               "production_classes": production, "classpath": classpath_identity(classpath)}
    (destination / "release-artifact.json").write_text(json.dumps(receipt, sort_keys=True) + "\n")
    return receipt


def validate_saved(receipt, jvm=None):
    """Validate recovered provenance without needing the worker's original absolute paths."""
    identity = receipt["manifest"]
    validate_manifest(identity)
    jar = receipt["jar_path"]
    classpath = receipt["classpath"]
    if jvm is not None and classpath != jvm["classpath"]:
        raise ValueError("release attestation differs from the measured classpath")
    if receipt["code_sources"] != {"io.airlift.regulator." + name: jar for name in PUBLIC_CLASSES}:
        raise ValueError("release code sources differ from the expected JAR")
    entries = [entry for entry in classpath if entry["path"] == jar]
    if len(entries) != 1 or entries[0]["files"] != {Path(jar).name: identity["jar_sha256"]}:
        raise ValueError("released JAR differs from the recorded JVM classpath")
    production = set(receipt["production_classes"])
    if not {PACKAGE + name + ".class" for name in PUBLIC_CLASSES} <= production:
        raise ValueError("release attestation lacks public classes")
    if any(production.intersection(entry["files"]) for entry in classpath if entry["path"] != jar):
        raise ValueError("recorded source classes shadow the release")


def validate_baseline(directory, expected):
    routes = list((directory / "routes").glob("*/run-metadata.txt"))
    if not routes:
        raise ValueError("release baseline lacks route metadata")
    for metadata in routes:
        values = dict(line.split("=", 1) for line in metadata.read_text().splitlines())
        path = metadata.parent / "release-artifact.json"
        receipt = json.loads(path.read_text())
        if (values.get("release_version") != expected["version"] or receipt["manifest"] != expected or
                values.get("release_artifact_sha256") != digest(path.read_bytes())):
            raise ValueError("baseline route does not identify the selected release")
        validate_saved(receipt)
        jar = metadata.parent / "release" / Path(receipt["jar_path"]).name
        if digest(jar.read_bytes()) != expected["jar_sha256"]:
            raise ValueError("baseline recovered release JAR checksum mismatch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--production-classes", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    arguments = parser.parse_args()
    jar, identity = prepare(arguments.root, arguments.version, arguments.destination)
    entries = arguments.classpath.split(os.pathsep)
    source = arguments.production_classes.resolve()
    if sum(Path(entry).resolve() == source for entry in entries) != 1:
        raise ValueError("expected exactly one source production classpath entry")
    classpath = os.pathsep.join(str(jar) if Path(entry).resolve() == source else entry for entry in entries)
    attest(classpath, jar, identity, arguments.destination)
    print(classpath)


if __name__ == "__main__":
    main()
