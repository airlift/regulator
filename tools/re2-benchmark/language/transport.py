#!/usr/bin/env python3
"""Validate and unpack one checksummed language batch, before building or timing."""

import argparse
from pathlib import Path, PurePosixPath
import tarfile
import tempfile

import collection
import fleet


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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--shard", required=True)
    parser.add_argument("--replica", required=True, type=int)
    parser.add_argument("--destination", type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="language-transport-") as temporary:
        unpack(args.archive, args.destination or Path(temporary) / "inputs", args.sha256,
               args.platform, args.shard, args.replica)


if __name__ == "__main__":
    main()
