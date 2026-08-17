#!/usr/bin/env python3

import csv
import hashlib
from pathlib import Path


FIELDS = ("benchmark", "model", "status", "reason")
STATUSES = {"compatible", "not-compatible"}


def applicability_paths(root: Path) -> tuple[Path, Path]:
    manifest_directory = root / "tools/re2-benchmark/manifests"
    return (
        manifest_directory / "rebar-joni-applicability.tsv",
        manifest_directory / "rebar-joni-applicability.sha256",
    )


def applicability_digest(root: Path) -> str:
    source, _ = applicability_paths(root)
    return hashlib.sha256(source.read_bytes()).hexdigest()


def read_workload_identities(root: Path) -> set[tuple[str, str]]:
    identities = set()
    sources = (
        (root / "tools/re2-benchmark/manifests/rebar-workloads.csv", ","),
        (root / "tools/re2-benchmark/manifests/rebar-extended-workloads.tsv", "\t"),
    )
    for path, delimiter in sources:
        with path.open(newline="", encoding="utf-8") as input_file:
            for row in csv.DictReader(input_file, delimiter=delimiter):
                identity = (row["name"], row["model"])
                if identity in identities:
                    raise ValueError(f"duplicate Rebar workload identity {identity!r}")
                identities.add(identity)
    return identities


def load_applicability(root: Path) -> dict[tuple[str, str], dict[str, str]]:
    source, checksum_file = applicability_paths(root)
    expected_checksum, checksum_name = checksum_file.read_text(encoding="utf-8").strip().split()
    if checksum_name != source.name:
        raise ValueError(f"unexpected Joni applicability checksum target: {checksum_name}")
    actual_checksum = hashlib.sha256(source.read_bytes()).hexdigest()
    if actual_checksum != expected_checksum:
        raise ValueError(
            f"Joni applicability checksum mismatch: expected {expected_checksum}, "
            f"actual {actual_checksum}")

    with source.open(newline="", encoding="utf-8") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != FIELDS:
            raise ValueError(f"unexpected Joni applicability fields: {reader.fieldnames}")
        rows = list(reader)

    applicability = {}
    for line_number, row in enumerate(rows, start=2):
        identity = (row["benchmark"], row["model"])
        if identity in applicability:
            raise ValueError(f"duplicate Joni applicability identity {identity!r}")
        if row["status"] not in STATUSES:
            raise ValueError(
                f"unsupported Joni applicability status at line {line_number}: {row['status']!r}")
        if row["status"] == "compatible" and row["reason"] != "-":
            raise ValueError(f"compatible Joni row has a reason at line {line_number}")
        if row["status"] != "compatible" and row["reason"] in {"", "-"}:
            raise ValueError(f"unavailable Joni row has no reason at line {line_number}")
        applicability[identity] = row

    workloads = read_workload_identities(root)
    if set(applicability) != workloads:
        raise ValueError(
            "Joni applicability differs from Rebar workloads; "
            f"missing={sorted(workloads - set(applicability))[:20]}, "
            f"unexpected={sorted(set(applicability) - workloads)[:20]}")
    return applicability


def main():
    root = Path(__file__).resolve().parents[3]
    applicability = load_applicability(root)
    counts = {
        status: sum(row["status"] == status for row in applicability.values())
        for status in sorted(STATUSES)
    }
    print(
        f"validated {len(applicability)} Rebar/Joni applicability rows: "
        + ", ".join(f"{status}={count}" for status, count in counts.items()))


if __name__ == "__main__":
    main()
