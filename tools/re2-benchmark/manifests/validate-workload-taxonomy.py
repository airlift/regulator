#!/usr/bin/env python3

import csv
import hashlib
import re
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MANIFESTS = ROOT / "tools/re2-benchmark/manifests"
TAXONOMY_FIELDS = ("name_regex", "model_regex", "population", "family", "reason")


def load_tsv(path):
    with path.open(newline="", encoding="utf-8") as input_file:
        return list(csv.DictReader(input_file, delimiter="\t"))


def load_workloads(root=MANIFESTS):
    with (root / "rebar-workloads.csv").open(newline="", encoding="utf-8") as input_file:
        curated = list(csv.DictReader(input_file))
    return curated + load_tsv(root / "rebar-extended-workloads.tsv")


def load_rules(root=MANIFESTS):
    taxonomy_path = root / "rebar-workload-taxonomy.tsv"
    checksum_path = root / "rebar-workload-taxonomy.sha256"
    if checksum_path.exists():
        expected_checksum, recorded_name = checksum_path.read_text(encoding="utf-8").split()
        if Path(recorded_name).name != taxonomy_path.name:
            raise ValueError("workload taxonomy checksum names an unexpected file")
        actual_checksum = hashlib.sha256(taxonomy_path.read_bytes()).hexdigest()
        if actual_checksum != expected_checksum:
            raise ValueError("workload taxonomy checksum mismatch")
    rules = load_tsv(taxonomy_path)
    if not rules or tuple(rules[0]) != TAXONOMY_FIELDS:
        raise ValueError("unexpected workload taxonomy schema")
    for rule in rules:
        re.compile(rule["name_regex"])
        re.compile(rule["model_regex"])
        if not all(rule.values()):
            raise ValueError(f"empty workload taxonomy field: {rule}")
    return rules


def classify(workloads, rules):
    classified = []
    for workload in workloads:
        matches = [
            rule
            for rule in rules
            if re.fullmatch(rule["name_regex"], workload["name"])
            and re.fullmatch(rule["model_regex"], workload["model"])
        ]
        if len(matches) != 1:
            raise ValueError(
                f"{workload['name']}/{workload['model']} matched {len(matches)} taxonomy rules")
        classified.append((workload, matches[0]))
    return classified


def validate(root=MANIFESTS):
    workloads = load_workloads(root)
    identities = [(row["name"], row["model"]) for row in workloads]
    if len(identities) != len(set(identities)):
        raise ValueError("duplicate Rebar workload/model identity")
    classified = classify(workloads, load_rules(root))
    return Counter(rule["population"] for _, rule in classified), classified


def main():
    counts, _ = validate()
    print(
        "validated "
        f"{sum(counts.values())} Rebar workload/model rows: "
        + ", ".join(f"{population}={counts[population]}" for population in sorted(counts)))


if __name__ == "__main__":
    main()
