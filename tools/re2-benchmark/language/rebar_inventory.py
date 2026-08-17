#!/usr/bin/env python3
"""Expand the complete pinned Rebar inventory before writing language adapters.

This is preparation evidence, not a benchmark result or a compatibility ledger.
An unchecked mapping remains pending, even if the older Rebar Joni adapter ran it.
"""

import argparse
import csv
import gzip
import re
import subprocess
from collections import Counter
from pathlib import Path

import collection


PINNED_REBAR = "463d00f31887e84c38467805b9e3122c314b9521"
MANIFESTS = collection.ROOT / "tools/re2-benchmark/manifests"
MODELS = {"compile", "count", "count-spans", "count-captures", "grep", "grep-captures"}


def read_table(path, delimiter="\t"):
    with path.open() as source:
        return list(csv.DictReader(source, delimiter=delimiter, quoting=csv.QUOTE_NONE))


def decode_klv(payload):
    fields = {}
    position = 0
    while position < len(payload):
        key_end = payload.index(b":", position)
        length_end = payload.index(b":", key_end + 1)
        key = payload[position:key_end].decode("ascii")
        length_text = payload[key_end + 1:length_end]
        if not length_text.isdigit():
            raise ValueError("invalid KLV length")
        end = length_end + 1 + int(length_text)
        if end >= len(payload) or payload[end:end + 1] != b"\n":
            raise ValueError("truncated KLV value")
        if key in fields and key != "pattern":
            raise ValueError(f"duplicate KLV field: {key}")
        fields.setdefault(key, []).append(payload[length_end + 1:end])
        position = end + 1
    for required in ("name", "model", "pattern", "haystack", "unicode", "case-insensitive"):
        if required not in fields:
            raise ValueError(f"missing KLV field: {required}")
    for flag in ("unicode", "case-insensitive"):
        if fields[flag][0] not in (b"true", b"false"):
            raise ValueError(f"invalid KLV boolean: {flag}")
    return fields


def input_identity(data):
    try:
        data.decode("utf-8", errors="strict")
        valid_utf8 = True
    except UnicodeDecodeError:
        valid_utf8 = False
    return {"bytes": len(data), "sha256": collection.digest(data),
            "ascii": data.isascii(), "valid_utf8": valid_utf8}


def expected_native_result(definition):
    expected = definition["count"]
    if isinstance(expected, list):
        expected = next((entry["count"] for entry in expected if re.search(entry["engine"], "re2")), None)
    if type(expected) is not int or expected < 0:
        raise ValueError("missing native RE2 expected result")
    return expected


def registry():
    curated = read_table(MANIFESTS / "rebar-workloads.csv", ",")
    extended = read_table(MANIFESTS / "rebar-extended-workloads.tsv")
    rows = curated + extended
    identities = [(row["name"], row["model"]) for row in rows]
    if len(curated) != 41 or len(extended) != 197 or len(set(identities)) != len(rows):
        raise ValueError("incomplete or duplicate Rebar registry")
    applicability = read_table(MANIFESTS / "rebar-joni-applicability.tsv")
    by_identity = {(row["benchmark"], row["model"]): row for row in applicability}
    if len(by_identity) != len(applicability) or set(by_identity) != set(identities):
        raise ValueError("existing Joni ledger does not cover the same Rebar inventory")
    return rows, by_identity


def validate(directory):
    directory = directory.resolve()
    inventory = collection.load(directory / "inventory.json")
    if (inventory["schema_version"] != 1 or inventory["kind"] != "preparation-only" or
            inventory["rebar_commit"] != PINNED_REBAR):
        raise ValueError("unexpected preparation inventory")
    for name, checksum in inventory["manifests"].items():
        if not re.fullmatch(r"rebar-[a-z-]+\.(csv|tsv)", name) or collection.digest((MANIFESTS / name).read_bytes()) != checksum:
            raise ValueError("inventory registry changed")
    rows, _ = registry()
    expected = {(row["name"], row["model"]) for row in rows}
    identities = [(case["id"], case["model"]) for case in inventory["cases"]]
    if set(identities) != expected or len(identities) != len(expected):
        raise ValueError("incomplete or duplicate inventory cases")
    for case in inventory["cases"]:
        path = directory / case["klv_file"]
        if not path.resolve().is_relative_to(directory):
            raise ValueError("KLV path escapes inventory")
        payload = gzip.decompress(path.read_bytes())
        if collection.digest(payload) != case["klv_sha256"] or len(payload) != case["klv_bytes"]:
            raise ValueError("expanded KLV checksum mismatch")
        fields = decode_klv(payload)
        if (fields["name"] != [case["id"].encode()] or fields["model"] != [case["model"].encode()] or
                case["patterns"] != [input_identity(pattern) for pattern in fields["pattern"]] or
                case["haystack"] != input_identity(fields["haystack"][0]) or
                case["unicode"] != (fields["unicode"] == [b"true"]) or
                case["case_insensitive"] != (fields["case-insensitive"] == [b"true"])):
            raise ValueError("expanded KLV differs from inventory metadata")
        if (set(case["language_preparation"]) != set(collection.ENGINES) or
                any(entry["state"] != "pending" for entry in case["language_preparation"].values())):
            raise ValueError("preparation inventory cannot establish language compatibility")
    if inventory["model_counts"] != dict(Counter(case["model"] for case in inventory["cases"])):
        raise ValueError("inventory model counts changed")
    return inventory


def prepare(rebar_root, destination):
    # Only the preparation machine parses upstream TOML. Archive workers use
    # the expanded KLV inputs and may run the qualification AMI's Python 3.9.
    import tomllib

    rebar_root, destination = rebar_root.resolve(), destination.resolve()
    revision = subprocess.check_output(["git", "-C", str(rebar_root), "rev-parse", "HEAD"], text=True).strip()
    dirty = subprocess.check_output(["git", "-C", str(rebar_root), "status", "--porcelain", "--untracked-files=no"], text=True)
    if revision != PINNED_REBAR or dirty:
        raise ValueError("Rebar expansion requires the clean pinned source")
    executable = rebar_root / "target/release/rebar"
    if not executable.is_file():
        raise ValueError("build the pinned Rebar executable first")
    rows, applicability = registry()
    taxonomy = read_table(MANIFESTS / "rebar-workload-taxonomy.tsv")
    destination.mkdir(parents=True, exist_ok=False)
    (destination / "inputs").mkdir()
    cases = []
    for row in rows:
        name, model = row["name"], row["model"]
        if model not in MODELS:
            raise ValueError(f"unhandled model: {model}")
        definition_path = row.get("definition", name.rsplit("/", 1)[0] + ".toml")
        definition_file = rebar_root / "benchmarks/definitions" / definition_path
        definition_bytes = definition_file.read_bytes()
        if "definition_sha256" in row and collection.digest(definition_bytes) != row["definition_sha256"]:
            raise ValueError(f"changed definition: {name}")
        definition_id = definition_path.removesuffix(".toml") + "/"
        matches = [bench for bench in tomllib.loads(definition_bytes.decode())["bench"]
                   if definition_id + bench["name"] == name]
        if len(matches) != 1 or matches[0]["model"] != model:
            raise ValueError(f"missing or changed definition: {name}")
        expected = expected_native_result(matches[0])
        payload = subprocess.check_output([str(executable), "klv", "-d", str(rebar_root / "benchmarks"), name], timeout=60)
        fields = decode_klv(payload)
        if fields["name"] != [name.encode()] or fields["model"] != [model.encode()]:
            raise ValueError(f"expanded the wrong workload: {name}")
        if any(fields[key] != [b"0"] for key in ("max-iters", "max-warmup-iters", "max-time", "max-warmup-time")):
            raise ValueError("inventory expansion must not request timing iterations")
        patterns = fields["pattern"]
        haystack = fields["haystack"][0]
        if "pattern_sha256" in row:
            if (len(patterns) != 1 or collection.digest(patterns[0]) != row["pattern_sha256"] or
                    len(patterns[0]) != int(row["pattern_length"]) or
                    collection.digest(haystack) != row["haystack_sha256"] or len(haystack) != int(row["haystack_length"]) or
                    expected != int(row["expected_result"]) or
                    fields["unicode"] != [row["unicode"].encode()] or
                    fields["case-insensitive"] != [row["case_insensitive"].encode()]):
                raise ValueError(f"expanded curated workload differs from frozen identity: {name}")
        category = [entry for entry in taxonomy if re.search(entry["name_regex"], name) and re.fullmatch(entry["model_regex"], model)]
        if len(category) != 1:
            raise ValueError(f"missing or ambiguous workload taxonomy: {name}")
        identity = collection.digest(payload)
        input_file = f"inputs/{identity}.klv.gz"
        (destination / input_file).write_bytes(gzip.compress(payload, mtime=0))
        pending = ["public-operation adapter", "language mapping", "normalized semantic verification"]
        cases.append({"id": name, "model": model, "population": category[0]["population"],
                      "family": category[0]["family"], "definition": definition_path,
                      "definition_sha256": collection.digest(definition_bytes),
                      "klv_file": input_file, "klv_sha256": identity, "klv_bytes": len(payload),
                      "patterns": [input_identity(pattern) for pattern in patterns], "haystack": input_identity(haystack),
                      "unicode": fields["unicode"] == [b"true"],
                      "case_insensitive": fields["case-insensitive"] == [b"true"],
                      "native_expected_result": expected,
                      "existing_joni_adapter": applicability[(name, model)],
                      "language_preparation": {language: {"state": "pending", "checks": pending.copy()}
                                               for language in collection.ENGINES}})
        if len(cases) % 25 == 0:
            print(f"Expanded {len(cases)}/{len(rows)} Rebar definitions", flush=True)
    inventory = {"schema_version": 1, "kind": "preparation-only", "rebar_commit": revision,
                 "rebar_binary_sha256": collection.digest(executable.read_bytes()),
                 "manifests": {name: collection.digest((MANIFESTS / name).read_bytes()) for name in (
                     "rebar-workloads.csv", "rebar-extended-workloads.tsv", "rebar-joni-applicability.tsv", "rebar-workload-taxonomy.tsv")},
                 "model_counts": dict(Counter(case["model"] for case in cases)), "cases": cases}
    collection.save(destination / "inventory.json", inventory)
    validate(destination)
    return inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rebar-root", required=True, type=Path)
    parser.add_argument("--output-directory", required=True, type=Path)
    args = parser.parse_args()
    inventory = prepare(args.rebar_root, args.output_directory)
    print(f"Expanded {len(inventory['cases'])} cases; language mappings remain pending")
    print(inventory["model_counts"])


if __name__ == "__main__":
    main()
