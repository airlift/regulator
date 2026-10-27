#!/usr/bin/env python3

import csv
import hashlib
import re
from collections import Counter
from pathlib import Path
import topology


EXPECTED_ROW_COUNT = 449
EXPECTED_SHARD_COUNT = 8
EXPECTED_PLATFORMS = {
    "r8i": ("intel", "r8i.large", "2"),
    "r9g": ("arm", "r9g.large", "2"),
}
EXPECTED_COMPARATORS = {
    "native-re2": "972a15cedd008d846f1a39b2e88ce48d7f166cbd",
    "rust-regex": "1.13.1",
    "rebar": "463d00f31887e84c38467805b9e3122c314b9521",
    "trino": "c7503d170344c4e266f03fdb39e53c82aa7e3594",
    "airlift-joni": "2.1.5.3",
    "temurin-x64": "25.0.4+7",
    "temurin-aarch64": "25.0.4+7",
}


def read_tsv(path):
    with path.open(newline="") as input_file:
        return list(csv.DictReader(input_file, delimiter="\t"))


def main():
    directory = Path(__file__).resolve().parent
    manifest = directory / "rows.tsv"
    expected_digest = (directory / "rows.sha256").read_text().split()[0]
    actual_digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
    if actual_digest != expected_digest:
        raise SystemExit(f"row manifest checksum mismatch: expected {expected_digest}, found {actual_digest}")

    shard_rows = read_tsv(directory / "shards.tsv")
    shards = {row["shard_id"] for row in shard_rows}
    if len(shard_rows) != len(shards) or len(shards) != EXPECTED_SHARD_COUNT:
        raise SystemExit(
            f"expected {EXPECTED_SHARD_COUNT} unique shards, "
            f"found {len(shard_rows)} rows and {len(shards)} identities")
    rows = read_tsv(manifest)
    if len(rows) != EXPECTED_ROW_COUNT:
        raise SystemExit(f"expected {EXPECTED_ROW_COUNT} rows, found {len(rows)}")

    identifiers = Counter(row["row_id"] for row in rows)
    duplicates = sorted(identifier for identifier, count in identifiers.items() if count != 1)
    if duplicates:
        raise SystemExit(f"duplicate row IDs: {duplicates[:10]}")
    observed_shards = {row["shard_id"] for row in rows}
    if observed_shards != shards:
        raise SystemExit(f"shard mismatch: missing={sorted(shards - observed_shards)}, unexpected={sorted(observed_shards - shards)}")
    required_fields = tuple(rows[0])
    for line_number, row in enumerate(rows, start=2):
        missing = [field for field in required_fields if not row[field]]
        if missing:
            raise SystemExit(f"row {line_number} has empty fields: {missing}")
        if not row["row_id"].startswith(f"{row['shard_id']}/"):
            raise SystemExit(f"row {line_number} ID does not begin with its shard")
        if "re2j" in "\t".join(row.values()).lower():
            raise SystemExit(f"row {line_number} contains the excluded historical RE2J comparator")
        if row["system"].startswith("regulator") and row["allocation_contract"] == "not-measured":
            raise SystemExit(f"row {line_number} omits available Regulator allocation evidence")
        if row["allocation_contract"] == "operation-dependent":
            raise SystemExit(f"row {line_number} has an unresolved allocation contract")

    platform_rows = read_tsv(directory / "platforms.tsv")
    platforms = {row["platform"]: row for row in platform_rows}
    if len(platform_rows) != len(platforms) or set(platforms) != set(EXPECTED_PLATFORMS):
        raise SystemExit(f"unexpected platform identities: {sorted(platforms)}")
    for platform, expected in EXPECTED_PLATFORMS.items():
        topology.validate(platforms[platform])
        actual = platforms[platform]
        if (actual["architecture"], actual["instance_type"], actual["vcpus"]) != expected:
            raise SystemExit(f"unexpected {platform} configuration")
        for field in ("jdk_sha256",):
            if len(actual[field]) != 64:
                raise SystemExit(f"{platform} has invalid {field}")
        for field in ("native_compiler_package", "cmake_package", "glibc_package", "cargo_package", "rust_package",
                      "time_package"):
            if not actual[field]:
                raise SystemExit(f"{platform} has no pinned {field}")

    # Other regions run the image whose name preflight resolves from the Oregon pin.
    with (directory / "platform-regions.tsv").open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != ("platform", "region", "ami_id"):
            raise SystemExit("platform-regions.tsv must have platform, region, and ami_id columns")
        region_rows = list(reader)
    placements = Counter((row["platform"], row["region"]) for row in region_rows)
    repeated = sorted(placement for placement, count in placements.items() if count != 1)
    if repeated:
        raise SystemExit(f"platform-regions.tsv repeats a platform and region: {repeated}")
    for row in region_rows:
        if (row["platform"] not in platforms or not re.fullmatch(r"[a-z]+(?:-[a-z]+)+-[0-9]+", row["region"]) or
                not re.fullmatch(r"ami-[0-9a-f]{8}(?:[0-9a-f]{9})?", row["ami_id"])):
            raise SystemExit(f"invalid platform-regions.tsv row: {row}")
    pinned = {row["platform"]: row["ami_id"] for row in region_rows if row["region"] == "us-west-2"}
    if pinned != {platform: row["ami_id"] for platform, row in platforms.items()}:
        raise SystemExit("platform-regions.tsv us-west-2 AMIs differ from platforms.tsv")

    comparator_rows = read_tsv(directory / "comparators.tsv")
    comparators = {row["component"]: row for row in comparator_rows}
    if len(comparator_rows) != len(comparators) or set(comparators) != set(EXPECTED_COMPARATORS):
        raise SystemExit(f"unexpected comparator identities: {sorted(comparators)}")
    for component, revision in EXPECTED_COMPARATORS.items():
        if comparators[component]["version_or_revision"] != revision:
            raise SystemExit(f"unexpected {component} revision")

    print(f"validated {len(rows)} unique rows across {len(shards)} shards and {len(platforms)} platforms")


if __name__ == "__main__":
    main()
