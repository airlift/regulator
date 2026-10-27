#!/usr/bin/env python3
"""Package one platform's engine builds, and install them on a unit worker whose native target matches."""

import argparse
import gzip
import hashlib
import json
import os
import platform
import shlex
import subprocess
import tarfile
from pathlib import Path, PurePosixPath


DIRECTORY = "engine-build"
MANIFEST = "engine-build.json"
RECEIPT = "engine-build-receipt.json"
# Archive members are relative to the shared work directory, where a route looks for each engine.
NATIVE_FILES = (
    "engine-build/regexp_benchmark",
    "engine-build/rebar",
    "rebar/native-portable/target/release/main",
    "rebar/native-tuned/target/release/main",
)
TRINO_DIRECTORY = "pinned-trino"
TRINO_METADATA = ("classpath.txt", "joni-jar.path", "provenance.properties")
# Beyond the candidate commit, these choose what the engines are built from and how.
BUILD_INPUTS = (
    "tools/re2-benchmark/CMakeLists.txt",
    "tools/re2-benchmark/build.sh",
    "tools/re2-benchmark/fetch-dependencies.sh",
    "tools/re2-benchmark/program-range.patch",
    "tools/re2-benchmark/random-text.patch",
    "tools/re2-golden/fetch-dependencies.sh",
    "tools/re2-benchmark/rebar/build-native.sh",
    "tools/re2-benchmark/trino-joni/pins.env",
    "tools/re2-benchmark/trino-joni/prepare.sh",
    "tools/re2-benchmark/baseline/build-engines.sh",
    "tools/re2-benchmark/baseline/engine_build.py",
)
TOOLCHAIN_FIELDS = ("native_compiler_package", "cmake_package", "glibc_package", "cargo_package", "rust_package")
CPU_FIELDS = ("Architecture", "Vendor ID", "Model name", "CPU family", "Model", "Stepping")
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


def native_target(compiler_output):
    # GCC prints the cc1plus invocation with -march=native or -mcpu=native already expanded.
    line = next((line for line in compiler_output.splitlines() if "cc1plus" in line), None)
    if line is None:
        raise EngineBuildError("compiler did not report its resolved native target")
    tokens = shlex.split(line)
    return " ".join(token for index, token in enumerate(tokens)
                    if token.startswith(("-m", "--param")) or (index and tokens[index - 1] == "--param"))


def cpu_identity(lscpu_output):
    fields = {}
    for line in lscpu_output.splitlines():
        key, separator, value = line.partition(":")
        if separator:
            fields[key.strip()] = " ".join(value.split())
    identity = [f"{field}={fields.get(field, 'unknown')}" for field in CPU_FIELDS]
    identity.append("Flags sha256=" + hashlib.sha256(fields.get("Flags", "").encode()).hexdigest())
    return ";".join(identity)


def host_identity():
    flag = "-march=native" if platform.machine() == "x86_64" else "-mcpu=native"
    compiler = subprocess.run(["c++", "-###", "-E", "-x", "c++", flag, "-"], stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, check=True)
    lscpu = subprocess.run(["lscpu"], env={**os.environ, "LC_ALL": "C"}, capture_output=True, text=True, check=True)
    return {"native_target": native_target(compiler.stderr), "cpu_identity": cpu_identity(lscpu.stdout)}


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


def package(shared, root, rebar_commit, directory):
    environment = properties(directory / "environment-manifest.txt")
    output = directory / DIRECTORY
    output.mkdir()
    manifest = {
        "schema_version": 1,
        **{field: environment[key] for field, key in BUILDER_FIELDS.items()},
        **host_identity(),
        "inputs": {
            "rebar_commit": rebar_commit,
            "trino_commit": properties(shared / TRINO_DIRECTORY / "provenance.properties")["trino_commit"],
            "toolchain": {field: environment[field] for field in TOOLCHAIN_FIELDS},
            "build_scripts": {path: sha256_file(root / path) for path in BUILD_INPUTS},
        },
        "archives": {
            "native": write_archive(shared, NATIVE_FILES, output / "native.tar.gz"),
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
    if set(manifest["archives"]) != {"native", "trino"}:
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
    # Shards read these markers to record which engines came prebuilt, and from which archive.
    marker = shared / DIRECTORY / (archive["file"].removesuffix(".tar.gz") + ".sha256")
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text(archive["sha256"] + "\n")


def install(build, manifest_sha256, shared, environment_path, output, identity):
    manifest = load_manifest(build, manifest_sha256)
    environment = properties(environment_path)
    if (manifest["platform"] != environment["platform"] or
            manifest["candidate_commit"] != environment["regulator_commit"] or
            manifest["candidate_archive_sha256"] != environment["regulator_archive_sha256"]):
        raise EngineBuildError("engine build was made for a different platform or candidate")
    record = {
        "engine_build_manifest_sha256": manifest_sha256,
        "expected_native_target": manifest["native_target"],
        "host_native_target": identity["native_target"],
        "expected_cpu_identity": manifest["cpu_identity"],
        "host_cpu_identity": identity["cpu_identity"],
        "native_engines": "local",
        "native_engines_reason": "",
        "native_archive_sha256": "none",
    }
    if manifest["native_target"] != identity["native_target"]:
        record["native_engines_reason"] = "native-target-mismatch"
    elif manifest["cpu_identity"] != identity["cpu_identity"]:
        record["native_engines_reason"] = "cpu-identity-mismatch"
    else:
        unpack(build, manifest["archives"]["native"], shared)
        record.update(native_engines="prebuilt", native_engines_reason="host-matches-builder",
                      native_archive_sha256=manifest["archives"]["native"]["sha256"])
    # Java classes need no native target.
    trino = manifest["archives"]["trino"]
    unpack(build, trino, shared)
    record.update(trino_harness="prebuilt", trino_archive_sha256=trino["sha256"])
    output.write_text("".join(f"{key}={value}\n" for key, value in record.items()))
    return record


def checksums(build, manifest_sha256):
    manifest = load_manifest(build, manifest_sha256)
    for name in ("native", "trino"):
        check_archive(build, manifest["archives"][name])
    return manifest["archives"]["native"]["sha256"], manifest["archives"]["trino"]["sha256"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    package_command = commands.add_parser("package", help="package the engines this builder host built")
    package_command.add_argument("--shared", type=Path, required=True)
    package_command.add_argument("--root", type=Path, required=True)
    package_command.add_argument("--rebar-commit", required=True)
    package_command.add_argument("--result-directory", type=Path, required=True)
    install_command = commands.add_parser("install", help="install prebuilt engines this host can use")
    install_command.add_argument("--shared", type=Path, required=True)
    install_command.add_argument("--environment", type=Path, required=True)
    install_command.add_argument("--output", type=Path, required=True)
    install_command.add_argument("--build", type=Path, required=True)
    install_command.add_argument("--manifest-sha256", required=True)
    checksum_command = commands.add_parser("checksums", help="print the verified native and Trino archive SHA-256")
    checksum_command.add_argument("--build", type=Path, required=True)
    checksum_command.add_argument("--manifest-sha256", required=True)
    arguments = parser.parse_args()
    if arguments.command == "package":
        package(arguments.shared, arguments.root, arguments.rebar_commit, arguments.result_directory)
    elif arguments.command == "install":
        install(arguments.build, arguments.manifest_sha256, arguments.shared, arguments.environment,
                arguments.output, host_identity())
    else:
        print(*checksums(arguments.build, arguments.manifest_sha256))


if __name__ == "__main__":
    main()
