#!/usr/bin/env python3
"""Validate and unpack one checksummed language batch, before building or timing."""

import argparse
from pathlib import Path, PurePosixPath
import tarfile
import tempfile

import collection
import fleet


ORDINARY_HOST_BUDGET_SECONDS = 5400
SLOW_SETUP_BUDGET_SECONDS = 5400


def unpack(archive, destination, checksum, platform, shard, replica):
    if collection.digest(archive.read_bytes()) != checksum:
        raise ValueError("language batch archive checksum mismatch")
    destination.mkdir(parents=True, exist_ok=False)
    with tarfile.open(archive, "r:gz") as files:
        names = set()
        for member in files:
            name = PurePosixPath(member.name)
            if (not name.parts or name.is_absolute() or ".." in name.parts or
                    str(name) in names or not (member.isfile() or member.isdir())):
                raise ValueError("unsafe language batch archive entry")
            names.add(str(name))
        files.extractall(destination, filter="data")
    package = fleet.validate_batch(destination)
    batch = package["batch"]
    if (batch["platform"], batch["shard"], batch["replica"]) != (platform, shard, replica):
        raise ValueError("language archive differs from host assignment")
    return package


def validate_host_budget(package, directory, seconds):
    manifests = [collection.load(directory / part["directory"] / "manifest.json")
                 for part in package["packages"]]
    protocols = [collection.protocol_for(manifest) for manifest in manifests]
    slow = any(protocol == collection.SLOW_BULK_PROTOCOL for protocol in protocols)
    if slow:
        if len(protocols) != 1:
            raise ValueError("slow bulk requires one comparison per host")
        manifest = manifests[0]
        forbidden = {'source_control', 'focused_controls', 'diagnostic_profiles'}
        if forbidden.intersection(manifest):
            raise ValueError("slow bulk host budget excludes auxiliary diagnostic work")
        protocol = protocols[0]
        processes = 0
        for _, case, _, engine, _ in collection.observations(manifest):
            for _ in collection.case_operations(manifest, case):
                processes += 1
                if (engine != 'native-re2' and
                        protocol.get('measurement_profile') in collection.SEPARATE_ALLOCATION_PROFILES):
                    processes += 1
        required = SLOW_SETUP_BUDGET_SECONDS + processes * protocol['measurement_timeout_seconds']
        if seconds != required:
            raise ValueError(
                f"slow bulk requires a {required}-second host budget for {processes} measurement processes")
    elif seconds != ORDINARY_HOST_BUDGET_SECONDS:
        raise ValueError("ordinary language batches require the 5400-second host budget")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--shard", required=True)
    parser.add_argument("--replica", required=True, type=int)
    parser.add_argument("--destination", type=Path)
    parser.add_argument("--host-timeout", type=int)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="language-transport-") as temporary:
        destination = args.destination or Path(temporary) / "inputs"
        package = unpack(args.archive, destination, args.sha256, args.platform, args.shard, args.replica)
        if args.host_timeout is not None:
            validate_host_budget(package, destination, args.host_timeout)


if __name__ == "__main__":
    main()
