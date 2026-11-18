#!/usr/bin/env python3

import argparse
import csv
import gzip
import hashlib
import subprocess
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rebar-root", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    return parser.parse_args()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    arguments = parse_args()
    root = Path(__file__).resolve().parents[3]
    manifest = Path(__file__).with_name("literal-corpus.tsv")
    with manifest.open(newline="") as input_file:
        rows = list(csv.DictReader(input_file, delimiter="\t"))

    revisions = {row["rebar_revision"] for row in rows}
    if len(revisions) != 1:
        raise SystemExit(f"literal corpus has inconsistent Rebar revisions: {sorted(revisions)}")
    expected_revision = revisions.pop()
    actual_revision = subprocess.run(
        ["git", "-C", str(arguments.rebar_root), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True).stdout.strip()
    if actual_revision != expected_revision:
        raise SystemExit(f"expected Rebar {expected_revision}, found {actual_revision}")

    # The hand-written notice beside the resources must name the revision they come from, as a
    # code span, so a longer or different hash that merely contains it does not pass.
    directories = {(root / row["resource_path"]).parent for row in rows}
    if len(directories) != 1:
        raise SystemExit(f"literal corpus resources span several directories: {sorted(directories)}")
    notice = directories.pop() / "README.md"
    if not notice.is_file():
        raise SystemExit(f"literal corpus notice does not exist: {notice}")
    if f"`{expected_revision}`" not in notice.read_text(encoding="utf-8"):
        raise SystemExit(f"literal corpus notice does not name Rebar {expected_revision}: {notice}")

    for row in rows:
        source = arguments.rebar_root / row["source_path"]
        source_data = source.read_bytes()
        if len(source_data) != int(row["uncompressed_bytes"]) or sha256(source_data) != row["sha256"]:
            raise SystemExit(f"pinned Rebar corpus differs from manifest: {row['language']}")

        resource = root / row["resource_path"]
        if arguments.check:
            if not resource.is_file():
                raise SystemExit(f"literal corpus resource does not exist: {resource}")
            if gzip.decompress(resource.read_bytes()) != source_data:
                raise SystemExit(f"literal corpus resource differs from pinned Rebar: {row['language']}")
        else:
            resource.parent.mkdir(parents=True, exist_ok=True)
            resource.write_bytes(gzip.compress(source_data, compresslevel=9, mtime=0))

    action = "verified" if arguments.check else "provisioned"
    print(f"{action} {len(rows)} literal corpus resources from Rebar {expected_revision}")


if __name__ == "__main__":
    main()
