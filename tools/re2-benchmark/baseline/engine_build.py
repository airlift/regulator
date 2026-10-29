#!/usr/bin/env python3
"""Package one platform's pinned Trino harness, native comparison runners, and compiled candidate, and unpack them on
its unit workers."""

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path, PurePosixPath

import row_protocol
import unit_session
from acceptance import EVIDENCE_FIELDS, load_platforms
from shard_results import write_tsv


DIRECTORY = "engine-build"
MANIFEST = "engine-build.json"
# The build phase's one work unit, and its shard: one replica per platform, on a unit worker without prebuilt engines.
UNIT_ID = SHARD = "engine-build"
# A build is one measured group.
GROUP_ID = "1"
# A build took about ten minutes on r8i, so it fits a Spot lease.
BUILD_SECONDS = 900
# The envelope files a build attempt binds in its evidence, beside its logs and archives.
UNIT_ARTIFACTS = ("environment-manifest.txt", "environment.txt", "unit-session.tsv", "run-metadata.txt")
# Archive members are relative to the shared work directory, where a route looks for the harness.
TRINO_DIRECTORY = "pinned-trino"
TRINO_METADATA = ("classpath.txt", "joni-jar.path", "provenance.properties")
# The native comparison runners, native RE2 and the Rust regex crate, each beside its build receipt, as build.sh
# writes them from the candidate's sources: the lifecycle runners, and the bulk runners a language unit measures.
LANGUAGE_NATIVE_DIRECTORY = "language-native"
LANGUAGE_NATIVE_TARGETS = ("language_benchmark", "language_bulk_benchmark", "language_rust_benchmark",
                           "language_rust_bulk_benchmark")
# The candidate's classes, test classes, and test-scope dependency jars, compiled once per platform, with their
# classpath relative to the directory, and the JUnit console launcher that runs a route's semantic tests from them.
CLASSES_DIRECTORY = "regulator-classes"
CLASSES_CLASSPATH = "classpath.txt"
CONSOLE_LAUNCHER = "lib/junit-platform-console-standalone.jar"
# build-engines.sh writes the builder's absolute dependency classpath here, beside the directory.
CLASSES_DEPENDENCIES = "regulator-dependencies.txt"
ARCHIVES = ("trino", "native", "classes")
# A measuring worker installs only the archives its phase's units run: a baseline unit runs the candidate classes,
# the Trino harness, and the native runners its specialized-pattern comparators need, and a language unit the
# candidate classes and the native runners.
UNIT_ARCHIVES = {"baseline": ("classes", "trino", "native"), "language": ("classes", "native")}
# Archives are mostly jars, binaries, and plans that compress little further, and hosts read them from S3 in their
# own region, where a higher level saves less download time than it costs a 2-vCPU builder to compress.
ZSTD_LEVEL = 9
# Beyond the candidate commit, these choose what the harness is built from and how.
BUILD_INPUTS = (
    "tools/re2-benchmark/trino-joni/pins.env",
    "tools/re2-benchmark/trino-joni/prepare.sh",
    "tools/re2-benchmark/baseline/build-engines.sh",
    "tools/re2-benchmark/baseline/engine_build.py",
    "tools/re2-benchmark/build.sh",
)
# A build manifest records its builder host's environment fields under these names.
BUILDER_FIELDS = {"platform": "platform", "architecture": "campaign_architecture", "instance_type": "instance_type",
                  "candidate_commit": "regulator_commit", "candidate_archive_sha256": "regulator_archive_sha256"}


class EngineBuildError(ValueError):
    """An engine build is not the frozen campaign input it claims to be."""


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as file:
        while chunk := file.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def properties(path):
    values = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise EngineBuildError(f"invalid property line in {path}: {line!r}")
        values[key] = value
    return values


def copy_classes(shared, root):
    """Copy the candidate's compiled classes and dependency jars into the classes directory, write their classpath
    relative to it, and return each copy by the builder path it came from."""
    directory = shared / CLASSES_DIRECTORY
    copies = {}
    for name in ("test-classes", "classes"):
        copies[root / "target" / name] = directory / name
        shutil.copytree(root / "target" / name, directory / name)
    for entry in (shared / CLASSES_DEPENDENCIES).read_text().strip().split(":"):
        jar = directory / "lib" / Path(entry).name
        if jar.exists():
            raise EngineBuildError(f"two dependencies share a file name: {jar.name}")
        copies[Path(entry)] = jar
        shutil.copyfile(entry, jar)
    (directory / CLASSES_CLASSPATH).write_text(
        ":".join(path.relative_to(directory).as_posix() for path in copies.values()) + "\n")
    if not (directory / CONSOLE_LAUNCHER).is_file():
        raise EngineBuildError(f"the classes lack the JUnit console launcher: {CONSOLE_LAUNCHER}")
    return copies


def relocate_trino_classpath(shared, copies):
    """Point the harness's classpath at the installed candidate classes rather than the builder's build of them."""
    path = shared / TRINO_DIRECTORY / "classpath.txt"
    path.write_text(":".join(str(copies.get(Path(entry), entry)) for entry in path.read_text().split(":")))


def trino_files(shared):
    work = shared / TRINO_DIRECTORY
    classes = shared / CLASSES_DIRECTORY
    files = {f"{TRINO_DIRECTORY}/{name}" for name in TRINO_METADATA}
    for entry in (work / "classpath.txt").read_text().split(":"):
        path = Path(entry)
        if classes in path.parents:
            # The classes archive holds Regulator's classes and dependencies.
            continue
        if work != path and work not in path.parents:
            raise EngineBuildError(f"pinned Trino classpath entry is in neither archive: {path}")
        if path.is_dir():
            files.update(file.relative_to(shared).as_posix() for file in path.rglob("*") if file.is_file())
        elif path.is_file():
            files.add(path.relative_to(shared).as_posix())
        else:
            raise EngineBuildError(f"pinned Trino classpath entry is missing: {path}")
    return sorted(files)


def language_native_files():
    return sorted(f"{LANGUAGE_NATIVE_DIRECTORY}/{target}{suffix}"
                  for target in LANGUAGE_NATIVE_TARGETS for suffix in ("", ".build.json"))


def write_archive(shared, names, path, prefix=""):
    """Write the named files, each under the prefix, as a tar compressed by the zstd CLI, and return its file name
    and SHA-256. Members carry no times or owners, and zstd's output does not depend on its thread count, so the
    checksum depends only on the member names, modes, and contents, the compression level, and the zstd version."""
    try:
        with subprocess.Popen(["zstd", f"-{ZSTD_LEVEL}", "-T0", "-q", "-f", "-o", str(path)],
                              stdin=subprocess.PIPE) as compressor:
            with tarfile.open(fileobj=compressor.stdin, mode="w|") as archive:
                for name in names:
                    member = archive.gettarinfo(shared / name, arcname=prefix + name)
                    member.mtime = 0
                    member.uid = member.gid = 0
                    member.uname = member.gname = ""
                    with (shared / name).open("rb") as file:
                        archive.addfile(member, file)
    except BrokenPipeError as error:
        # zstd exited before it read the whole tar.
        raise EngineBuildError(f"zstd could not write {path}") from error
    if compressor.returncode != 0:
        raise EngineBuildError(f"zstd could not write {path}")
    return {"file": path.name, "sha256": sha256_file(path)}


def unpack(source, destination):
    """Extract a tar stream into a directory, refusing any entry that is not a plain file or directory beneath it."""
    names = set()
    with tarfile.open(fileobj=source, mode="r|") as archive:
        for member in archive:
            name = PurePosixPath(member.name)
            # Two spellings of one path, such as a and ./a, would extract over each other.
            if (not name.parts or name.is_absolute() or ".." in name.parts or
                    name.parts in names or not (member.isfile() or member.isdir())):
                raise EngineBuildError(f"unsafe archive entry: {member.name!r}")
            names.add(name.parts)
            archive.extract(member, destination, filter="data")
    # Read to the end, so the decompressor feeding the stream never writes to a closed pipe.
    while source.read(1024 * 1024):
        pass


def package(shared, root, directory):
    environment = properties(directory / "environment-manifest.txt")
    output = directory / DIRECTORY
    output.mkdir()
    relocate_trino_classpath(shared, copy_classes(shared, root))
    classes = sorted(path.relative_to(shared).as_posix() for path in (shared / CLASSES_DIRECTORY).rglob("*")
                     if path.is_file())
    manifest = {
        "schema_version": 2,
        **{field: environment[key] for field, key in BUILDER_FIELDS.items()},
        "inputs": {
            "trino_commit": properties(shared / TRINO_DIRECTORY / "provenance.properties")["trino_commit"],
            "build_scripts": {path: sha256_file(root / path) for path in BUILD_INPUTS},
        },
        "archives": {
            "trino": write_archive(shared, trino_files(shared), output / "trino.tar.zst"),
            "native": write_archive(shared, language_native_files(), output / "native.tar.zst"),
            "classes": write_archive(shared, classes, output / "classes.tar.zst"),
        },
    }
    (output / MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")


def units():
    """The build phase's work units: the one engine build each platform needs."""
    return {UNIT_ID: {"unit_id": UNIT_ID, "shard_id": SHARD, "unit_index": "1",
                      "estimated_seconds": str(BUILD_SECONDS), "owned": [UNIT_ID],
                      "groups": [{"group_id": GROUP_ID, "owned": [UNIT_ID], "representatives": [],
                                  "estimated_seconds": float(BUILD_SECONDS)}]}}


def inputs_sha256(build_scripts):
    """One digest of the build scripts an engine build is made by, from {path: SHA-256}."""
    return hashlib.sha256("".join(f"{path}  {build_scripts[path]}\n" for path in BUILD_INPUTS).encode()).hexdigest()


def unit_evidence(root):
    """Every artifact of a build attempt: its envelope files, its build logs, and its archives."""
    paths = [*(root / name for name in UNIT_ARTIFACTS), *(root / "logs").rglob("*"), *(root / DIRECTORY).rglob("*")]
    return sorted(unit_session.host_evidence_rows(root, paths), key=lambda row: row["artifact"])


def accept(root, replica, group, unit_attempt, region, engine_tree):
    """Record a build lease's session and evidence beside its packaged build, and write its receipt."""
    environment = properties(root / "environment-manifest.txt")
    unit_session.write_worker_unit_session(root, environment, UNIT_ID, replica, group, unit_attempt, region)
    (root / "run-metadata.txt").write_text(f"unit_id={UNIT_ID}\nengine_tree={engine_tree}\n")
    write_tsv(root / "unit-evidence.tsv", EVIDENCE_FIELDS, unit_evidence(root))
    unit_session.write_unit_receipt(root / "unit-receipt.tsv", validate_unit_attempt(root))


def validate_unit_attempt(root):
    """Regenerate a build attempt's receipt from its archives, its evidence, and the builder's host identity.

    Its observed digest is the build manifest's SHA-256, by which measurement phases name the build they install.
    """
    session = unit_session.read_unit_session(root)
    if unit_evidence(root) != row_protocol.read_tsv(root / "unit-evidence.tsv"):
        raise EngineBuildError("build evidence differs from the attempt's artifacts")
    environment = properties(root / "environment-manifest.txt")
    if (session["unit_id"], session["group_id"]) != (UNIT_ID, GROUP_ID):
        raise EngineBuildError(f"build session names unit {session['unit_id']} group {session['group_id']}, not "
                               f"the engine build {UNIT_ID} group {GROUP_ID}")
    unit_session.check_worker_unit_session(session, environment)
    # A worker that installed prebuilt engines would package them rather than build its own.
    if (environment["verification_status"] != "verified" or environment["benchmark_mode"] != "unit-worker" or
            (root / "engine-build.txt").exists()):
        raise EngineBuildError("engine build lacks a verified unit worker that built its own engines")
    manifest_path = root / DIRECTORY / MANIFEST
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("schema_version") != 2 or any(
            manifest[field] != environment[key] for field, key in BUILDER_FIELDS.items()):
        raise EngineBuildError("engine build manifest differs from its builder host")
    if set(manifest["archives"]) != set(ARCHIVES):
        raise EngineBuildError("engine build has missing or unexpected archives")
    for archive in manifest["archives"].values():
        check_archive(root / DIRECTORY, archive)
    metadata = properties(root / "run-metadata.txt")
    if metadata["unit_id"] != UNIT_ID:
        raise EngineBuildError("build metadata differs from its unit")
    # The build's inputs are both its manifest and its protocol: the scripts it was made by.
    inputs = inputs_sha256(manifest["inputs"]["build_scripts"])
    return {
        **session,
        "shard_id": SHARD,
        "owned_row_count": "1",
        "row_protocol_sha256": inputs,
        "manifest_sha256": inputs,
        "observed_rows_sha256": sha256_file(manifest_path),
        "evidence_manifest_sha256": sha256_file(root / "unit-evidence.tsv"),
        "candidate_commit": environment["regulator_commit"],
        "candidate_archive_sha256": environment["regulator_archive_sha256"],
        "engine_tree": metadata["engine_tree"],
        "heap_size": environment["benchmark_heap_size"],
    }


def validate_unit_campaign(checkout, receipts, campaign_id):
    """Accept a build phase's receipts by the unit campaign rule: one build on every platform, made by the
    checkout's build scripts, and one candidate."""
    platforms = load_platforms(unit_session.DIRECTORY / "platforms.tsv")
    inputs = inputs_sha256({path: sha256_file(checkout / path) for path in BUILD_INPUTS})
    build_units = units()
    unit_session.check_unit_campaign(
        receipts, platforms, build_units, {(UNIT_ID, GROUP_ID): inputs},
        unit_session.platform_allocations(platforms, build_units), {SHARD: inputs}, campaign_id, (1,))


def validate_trino_pin(checkout, build):
    """Refuse a build whose harness was made from a Trino commit other than the checkout's pin."""
    pinned = properties(checkout / BUILD_INPUTS[0])["TRINO_COMPARATOR_TRINO_COMMIT"]
    built = json.loads((build / MANIFEST).read_text())["inputs"]["trino_commit"]
    if built != pinned:
        raise EngineBuildError(f"engine build's Trino commit {built} is not the checkout's pin {pinned}")


def load_manifest(build, manifest_sha256):
    path = build / MANIFEST
    if sha256_file(path) != manifest_sha256:
        raise EngineBuildError("engine build manifest checksum mismatch")
    return json.loads(path.read_text())


def check_archive(build, archive):
    """Check a build's archive against its manifest checksum."""
    if sha256_file(build / archive["file"]) != archive["sha256"]:
        raise EngineBuildError(f"engine build archive checksum mismatch: {archive['file']}")


def installed_classes(shared):
    """The installed candidate classes' absolute classpath, and the SHA-256 of the archive they came from."""
    directory = shared / CLASSES_DIRECTORY
    marker = shared / DIRECTORY / "classes.sha256"
    if not marker.is_file():
        raise EngineBuildError(f"no prebuilt candidate classes are installed in {shared}")
    entries = (directory / CLASSES_CLASSPATH).read_text().strip().split(":")
    return ":".join(str(directory / entry) for entry in entries), marker.read_text().strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    package_command = commands.add_parser(
        "package", help="package the Trino harness, native runners, and candidate classes this builder host built")
    package_command.add_argument("--shared", type=Path, required=True)
    package_command.add_argument("--root", type=Path, required=True)
    package_command.add_argument("--result-directory", type=Path, required=True)
    accept_command = commands.add_parser("accept", help="accept the build this unit lease packaged")
    accept_command.add_argument("--unit-directory", type=Path, required=True)
    accept_command.add_argument("--replica", required=True)
    accept_command.add_argument("--group", required=True)
    accept_command.add_argument("--unit-attempt", required=True)
    accept_command.add_argument("--region", required=True)
    accept_command.add_argument("--engine-tree", required=True)
    unpack_command = commands.add_parser(
        "unpack", help="extract a tar stream from standard input into a directory, refusing unsafe entries")
    unpack_command.add_argument("--destination", type=Path, required=True)
    arguments = parser.parse_args()
    if arguments.command == "package":
        package(arguments.shared, arguments.root, arguments.result_directory)
    elif arguments.command == "accept":
        accept(arguments.unit_directory, arguments.replica, arguments.group, arguments.unit_attempt, arguments.region,
               arguments.engine_tree)
    else:
        try:
            unpack(sys.stdin.buffer, arguments.destination)
        except (EngineBuildError, tarfile.TarError) as error:
            raise SystemExit(f"cannot unpack into {arguments.destination}: {error}") from error


if __name__ == "__main__":
    main()
