#!/usr/bin/env python3
"""Package one platform's pinned Trino harness, and install it on that platform's unit workers."""

import argparse
import gzip
import hashlib
import json
import tarfile
from pathlib import Path, PurePosixPath


DIRECTORY = "engine-build"
MANIFEST = "engine-build.json"
RECEIPT = "engine-build-receipt.json"
# Archive members are relative to the shared work directory, where a route looks for the harness.
TRINO_DIRECTORY = "pinned-trino"
TRINO_METADATA = ("classpath.txt", "joni-jar.path", "provenance.properties")
# Beyond the candidate commit, these choose what the harness is built from and how.
BUILD_INPUTS = (
    "tools/re2-benchmark/trino-joni/pins.env",
    "tools/re2-benchmark/trino-joni/prepare.sh",
    "tools/re2-benchmark/baseline/build-engines.sh",
    "tools/re2-benchmark/baseline/engine_build.py",
)
# A build manifest records its builder host's environment fields under these names.
BUILDER_FIELDS = {"platform": "platform", "architecture": "campaign_architecture", "instance_type": "instance_type",
                  "candidate_commit": "regulator_commit", "candidate_archive_sha256": "regulator_archive_sha256"}


class EngineBuildError(ValueError):
    """An engine build is not the frozen campaign input it claims to be."""


def stream_sha256(file):
    digest = hashlib.sha256()
    while chunk := file.read(1024 * 1024):
        digest.update(chunk)
    return digest.hexdigest()


def sha256_file(path):
    with path.open("rb") as file:
        return stream_sha256(file)


def properties(path):
    values = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise EngineBuildError(f"invalid property line in {path}: {line!r}")
        values[key] = value
    return values


def trino_files(shared):
    work = shared / TRINO_DIRECTORY
    files = {f"{TRINO_DIRECTORY}/{name}" for name in TRINO_METADATA}
    for entry in (work / "classpath.txt").read_text().split(":"):
        path = Path(entry)
        if work != path and work not in path.parents:
            # Regulator's classes and dependencies come from each host's own candidate build.
            continue
        if path.is_dir():
            files.update(file.relative_to(shared).as_posix() for file in path.rglob("*") if file.is_file())
        elif path.is_file():
            files.add(path.relative_to(shared).as_posix())
        else:
            raise EngineBuildError(f"pinned Trino classpath entry is missing: {path}")
    return sorted(files)


def write_archive(shared, names, path):
    with gzip.GzipFile(path, "wb", mtime=0) as compressed, tarfile.open(fileobj=compressed, mode="w") as archive:
        for name in names:
            member = archive.gettarinfo(shared / name, arcname=name)
            member.mtime = 0
            member.uid = member.gid = 0
            member.uname = member.gname = ""
            with (shared / name).open("rb") as file:
                archive.addfile(member, file)
    return {"file": path.name, "sha256": sha256_file(path),
            "files": {name: sha256_file(shared / name) for name in names}}


def archive_contents(path):
    files = {}
    with tarfile.open(path, "r:gz") as archive:
        for member in archive:
            name = PurePosixPath(member.name)
            if (not name.parts or name.is_absolute() or ".." in name.parts or
                    member.name in files or not (member.isfile() or member.isdir())):
                raise EngineBuildError(f"unsafe engine build archive entry in {path}: {member.name!r}")
            if member.isfile():
                with archive.extractfile(member) as content:
                    files[member.name] = stream_sha256(content)
    return files


def package(shared, root, directory):
    environment = properties(directory / "environment-manifest.txt")
    output = directory / DIRECTORY
    output.mkdir()
    manifest = {
        "schema_version": 1,
        **{field: environment[key] for field, key in BUILDER_FIELDS.items()},
        "inputs": {
            "trino_commit": properties(shared / TRINO_DIRECTORY / "provenance.properties")["trino_commit"],
            "build_scripts": {path: sha256_file(root / path) for path in BUILD_INPUTS},
        },
        "archives": {
            "trino": write_archive(shared, trino_files(shared), output / "trino.tar.gz"),
        },
    }
    (output / MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    (directory / RECEIPT).write_text(json.dumps(validate(directory), sort_keys=True) + "\n")


def validate(directory):
    """Regenerate an engine build's receipt from its archives and the builder's host identity."""
    environment = properties(directory / "environment-manifest.txt")
    manifest_path = directory / DIRECTORY / MANIFEST
    manifest = json.loads(manifest_path.read_text())
    if environment["verification_status"] != "verified" or environment["benchmark_mode"] != "engine-build":
        raise EngineBuildError("engine build lacks a verified builder host")
    if manifest.get("schema_version") != 1 or any(
            manifest[field] != environment[key] for field, key in BUILDER_FIELDS.items()):
        raise EngineBuildError("engine build manifest differs from its builder host")
    if set(manifest["archives"]) != {"trino"}:
        raise EngineBuildError("engine build has missing or unexpected archives")
    for archive in manifest["archives"].values():
        path = check_archive(directory / DIRECTORY, archive)
        if archive_contents(path) != archive["files"]:
            raise EngineBuildError(f"engine build archive differs from its manifest: {archive['file']}")
    return {
        "schema_version": 1, "workload": "engine-build",
        "campaign_id": environment["baseline_campaign"], "platform": environment["platform"],
        "shard_id": environment["shard"], "replica_id": environment["replica"],
        "host_epoch": environment["host_epoch"], "instance_id": environment["instance_id"],
        "instance_type": environment["instance_type"], "architecture": environment["campaign_architecture"],
        "candidate_commit": environment["regulator_commit"],
        "candidate_archive_sha256": environment["regulator_archive_sha256"],
        "manifest_sha256": sha256_file(manifest_path),
        "archives": {name: archive["sha256"] for name, archive in sorted(manifest["archives"].items())},
    }


def load_manifest(build, manifest_sha256):
    path = build / MANIFEST
    if sha256_file(path) != manifest_sha256:
        raise EngineBuildError("engine build manifest checksum mismatch")
    return json.loads(path.read_text())


def check_archive(build, archive):
    """The path of a build's archive, after checking it against its manifest checksum."""
    path = build / archive["file"]
    if sha256_file(path) != archive["sha256"]:
        raise EngineBuildError(f"engine build archive checksum mismatch: {archive['file']}")
    return path


def unpack(build, archive, shared):
    path = check_archive(build, archive)
    if archive_contents(path) != archive["files"]:
        raise EngineBuildError(f"engine build archive differs from its manifest: {archive['file']}")
    shared.mkdir(parents=True, exist_ok=True)
    with tarfile.open(path, "r:gz") as files:
        files.extractall(shared, filter="data")
    for name, checksum in archive["files"].items():
        if sha256_file(shared / name) != checksum:
            raise EngineBuildError(f"installed engine file differs from its manifest: {name}")
    # Shards read this marker to record that the harness came prebuilt, and from which archive.
    marker = shared / DIRECTORY / (archive["file"].removesuffix(".tar.gz") + ".sha256")
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text(archive["sha256"] + "\n")


def install(build, manifest_sha256, shared, environment_path, output):
    manifest = load_manifest(build, manifest_sha256)
    environment = properties(environment_path)
    if (manifest["platform"] != environment["platform"] or
            manifest["candidate_commit"] != environment["regulator_commit"] or
            manifest["candidate_archive_sha256"] != environment["regulator_archive_sha256"]):
        raise EngineBuildError("engine build was made for a different platform or candidate")
    trino = manifest["archives"]["trino"]
    unpack(build, trino, shared)
    record = {"engine_build_manifest_sha256": manifest_sha256, "trino_harness": "prebuilt",
              "trino_archive_sha256": trino["sha256"]}
    output.write_text("".join(f"{key}={value}\n" for key, value in record.items()))
    return record


def checksum(build, manifest_sha256):
    manifest = load_manifest(build, manifest_sha256)
    check_archive(build, manifest["archives"]["trino"])
    return manifest["archives"]["trino"]["sha256"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    package_command = commands.add_parser("package", help="package the harness this builder host built")
    package_command.add_argument("--shared", type=Path, required=True)
    package_command.add_argument("--root", type=Path, required=True)
    package_command.add_argument("--result-directory", type=Path, required=True)
    install_command = commands.add_parser("install", help="install the prebuilt harness")
    install_command.add_argument("--shared", type=Path, required=True)
    install_command.add_argument("--environment", type=Path, required=True)
    install_command.add_argument("--output", type=Path, required=True)
    install_command.add_argument("--build", type=Path, required=True)
    install_command.add_argument("--manifest-sha256", required=True)
    checksum_command = commands.add_parser("checksum", help="print the verified Trino archive SHA-256")
    checksum_command.add_argument("--build", type=Path, required=True)
    checksum_command.add_argument("--manifest-sha256", required=True)
    arguments = parser.parse_args()
    if arguments.command == "package":
        package(arguments.shared, arguments.root, arguments.result_directory)
    elif arguments.command == "install":
        install(arguments.build, arguments.manifest_sha256, arguments.shared, arguments.environment,
                arguments.output)
    else:
        print(checksum(arguments.build, arguments.manifest_sha256))


if __name__ == "__main__":
    main()
