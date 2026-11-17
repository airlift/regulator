#!/usr/bin/env python3
"""Converts the pinned uap-core rules and tests into the `BenchmarkUapCore` resources.

Reads `regexes.yaml`, `tests/test_ua.yaml`, and `tests/test_os.yaml` from a checkout of the
revision `uap-core-corpus.tsv` pins, and writes each resource the manifest lists as gzip JSON
lines: the `user_agent_parsers` and `os_parsers` rules, one object per rule in file order, and the
browser and OS test cases, one object per case. The device rules and tests are left out. Every
string, regular expressions included, is the YAML value unchanged. uap-core's `LICENSE` is copied
beside them unchanged, as Apache-2.0 requires of redistributed files. With `--check`, verifies the
committed resources instead of writing them.

Requires PyYAML.
"""

import argparse
import csv
import gzip
import hashlib
import json
import subprocess
from pathlib import Path

import yaml

RULE_KEYS = {
    "user_agent_parsers": ("regex", "family_replacement", "v1_replacement", "v2_replacement", "v3_replacement"),
    "os_parsers": ("regex", "os_replacement", "os_v1_replacement", "os_v2_replacement", "os_v3_replacement", "os_v4_replacement"),
}
TEST_KEYS = ("user_agent_string", "family", "major", "minor", "patch", "patch_minor")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--uap-core-root", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    return parser.parse_args()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def json_lines(objects, allowed_keys, name):
    lines = []
    for item in objects:
        for key, value in item.items():
            if key not in allowed_keys:
                raise SystemExit(f"{name} has an unexpected field: {key}")
            if value is not None and not isinstance(value, str):
                raise SystemExit(f"{name} field {key} is not a string: {value!r}")
            # The Java reader decodes only the escapes json.dumps writes for these characters.
            if value is not None and any(ord(character) < 0x20 and character not in "\n\r\t" for character in value):
                raise SystemExit(f"{name} field {key} holds a control character: {value!r}")
        lines.append(json.dumps(item, ensure_ascii=True) + "\n")
    return "".join(lines).encode("ascii")


def resource_data(content, kind):
    if kind in RULE_KEYS:
        return json_lines(content[kind], RULE_KEYS[kind], kind)
    if kind == "test_cases":
        return json_lines(content[kind], TEST_KEYS, kind)
    raise SystemExit(f"unknown uap-core resource kind: {kind}")


def main():
    arguments = parse_args()
    root = Path(__file__).resolve().parents[3]
    manifest = Path(__file__).with_name("uap-core-corpus.tsv")
    with manifest.open(newline="") as input_file:
        rows = list(csv.DictReader(input_file, delimiter="\t"))

    revisions = {row["revision"] for row in rows}
    if len(revisions) != 1:
        raise SystemExit(f"uap-core corpus has inconsistent revisions: {sorted(revisions)}")
    expected_revision = revisions.pop()
    actual_revision = subprocess.run(
        ["git", "-C", str(arguments.uap_core_root), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True).stdout.strip()
    if actual_revision != expected_revision:
        raise SystemExit(f"expected uap-core {expected_revision}, found {actual_revision}")

    for row in rows:
        source_data = (arguments.uap_core_root / row["source_path"]).read_bytes()
        if len(source_data) != int(row["source_bytes"]) or sha256(source_data) != row["source_sha256"]:
            raise SystemExit(f"pinned uap-core file differs from manifest: {row['source_path']}")

        verbatim = row["kind"] == "license"
        data = source_data if verbatim else resource_data(yaml.safe_load(source_data.decode("utf-8")), row["kind"])
        if len(data) != int(row["uncompressed_bytes"]) or sha256(data) != row["sha256"]:
            raise SystemExit(f"generated uap-core resource differs from manifest: {row['resource_path']}")

        resource = root / row["resource_path"]
        if arguments.check:
            if not resource.is_file():
                raise SystemExit(f"uap-core resource does not exist: {resource}")
            committed = resource.read_bytes()
            if (committed if verbatim else gzip.decompress(committed)) != data:
                raise SystemExit(f"uap-core resource differs from pinned uap-core: {row['resource_path']}")
        else:
            resource.parent.mkdir(parents=True, exist_ok=True)
            resource.write_bytes(data if verbatim else gzip.compress(data, compresslevel=9, mtime=0))

    action = "verified" if arguments.check else "provisioned"
    print(f"{action} {len(rows)} uap-core resources from uap-core {expected_revision}")


if __name__ == "__main__":
    main()
