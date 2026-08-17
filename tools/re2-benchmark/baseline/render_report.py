#!/usr/bin/env python3

"""Render a deterministic Markdown report from Phase 5 reducer tables."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path


class ReportError(ValueError):
    pass


def sha256_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_tsv(path, required=()):
    try:
        input_file = path.open(newline="", encoding="utf-8")
    except OSError as error:
        raise ReportError(f"cannot read {path}: {error}") from error
    with input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        fields = tuple(reader.fieldnames or ())
        if not fields:
            raise ReportError(f"{path} is empty")
        missing = sorted(set(required) - set(fields))
        if missing:
            raise ReportError(f"{path} is missing columns: {missing}")
        return list(reader)


def validate_checksums(directory):
    rows = read_tsv(directory / "output-checksums.tsv", ("file", "size_bytes", "sha256"))
    for row in rows:
        path = directory / row["file"]
        if not path.is_file():
            raise ReportError(f"reducer output is missing: {path}")
        if path.stat().st_size != int(row["size_bytes"]) or sha256_file(path) != row["sha256"]:
            raise ReportError(f"reducer output checksum mismatch: {path}")


def escape(value):
    return str(value).replace("|", "\\|").replace("\n", " ")


def table(headers, rows):
    rows = list(rows)
    if not rows:
        return ["_None._", ""]
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    lines.extend("| " + " | ".join(escape(value) for value in row) + " |" for row in rows)
    lines.append("")
    return lines


def ratio(value):
    return f"{float(value):.3f}x"


def percent(value):
    return f"{float(value) * 100:.2f}%"


def short_row_id(value, limit=88):
    return value if len(value) <= limit else "..." + value[-(limit - 3):]


def render(arguments):
    validate_checksums(arguments.input_dir)
    summary = json.loads((arguments.input_dir / "summary.json").read_text(encoding="utf-8"))
    classes = read_tsv(arguments.input_dir / "general-classes.tsv")
    outliers = read_tsv(arguments.input_dir / "serious-outliers.tsv")
    gaps = read_tsv(arguments.input_dir / "gap-ledger.tsv")
    events = read_tsv(arguments.input_dir / "events.tsv")
    rows = read_tsv(arguments.input_dir / "row-aggregates.tsv")
    comparisons = read_tsv(arguments.input_dir / "comparison-aggregates.tsv")
    inventory = read_tsv(arguments.input_dir / "artifact-inventory.tsv")
    metadata = read_tsv(arguments.input_dir / "metadata.tsv")
    capacity = read_tsv(arguments.input_dir / "capacity.tsv")

    lines = [
        f"# {arguments.title}", "",
        "This report is generated deterministically from exact-row accepted host artifacts. "
        "Every accepted primary and confirmation measurement remains available in the machine-readable tables.", "",
        "Ratios are normalized Java cost divided by comparator cost. **Below 1.0 is faster; above 1.0 is slower.** "
        "Throughput scores are inverted before calculating this cost ratio.", "",
        "## Campaign", "",
    ]
    lines += table(("Field", "Value"), (
        ("Campaign", summary["campaign_id"]),
        ("Manifest rows", f"{summary['manifest_rows']:,}"),
        ("Manifest SHA-256", f"`{summary['manifest_sha256']}`"),
        ("Platforms", ", ".join(summary["platforms"])),
        ("Shards", str(len(summary["shards"]))),
        ("Primary sessions", str(summary["primary_sessions"])),
        ("Confirmation sessions", str(summary["confirmation_sessions"])),
        ("Confirmation jobs requested", str(summary["confirmation_jobs"])),
        ("Accepted host rows", f"{summary['host_rows']:,}"),
        ("Same-host ratios", f"{summary['same_host_ratios']:,}"),
    ))

    lines.extend(["## Result Status", ""])
    status_rows = []
    for platform in summary["platforms"]:
        platform_rows = [row for row in rows if row["platform"] == platform]
        platform_comparisons = [row for row in comparisons if row["platform"] == platform]
        status_rows.append((
            platform,
            f"{len(platform_rows):,}",
            f"{len(platform_comparisons):,}",
            str(sum(row["unresolved"] == "true" for row in platform_rows)),
            str(sum(row["unresolved"] == "true" for row in platform_comparisons)),
            str(sum(row["serious_outlier"] == "true" for row in platform_comparisons)),
        ))
    lines += table(
        ("Platform", "Rows", "Comparisons", "Unresolved rows", "Unresolved comparisons", "Serious outliers"),
        status_rows)

    lines.extend(["## General Performance Classes", ""])
    lines.append("Serious outliers are excluded from these class summaries and listed separately below.")
    lines.append("")
    for platform in summary["platforms"]:
        lines.extend([f"### {platform.upper()}", ""])
        platform_classes = [row for row in classes if row["platform"] == platform]
        platform_classes.sort(key=lambda row: (
            row["rebar_corpus"], row["suite"], row["candidate_system"], row["comparator_system"]))
        lines += table(
            ("Corpus", "Suite", "Java", "Comparator", "Rows", "Median", "P10", "P90", "F/P/S", "Unresolved"),
            ((row["rebar_corpus"], row["suite"], row["candidate_system"], row["comparator_system"],
              row["comparison_count"], ratio(row["median_ratio"]), ratio(row["p10_ratio"]), ratio(row["p90_ratio"]),
              f"{row['faster_count']}/{row['parity_count']}/{row['slower_count']}", row["unresolved_count"])
             for row in platform_classes))

    lines.extend(["## Serious Outliers", ""])
    lines.append("A serious outlier has a median Java/comparator cost ratio at or beyond 0.5x or 2.0x. "
                 "These rows are preserved but excluded from the general class summaries.")
    lines.append("")
    outliers.sort(key=lambda row: (row["platform"], -abs(float(row["median_ratio"]) - 1), row["comparison_id"]))
    lines += table(
        ("Platform", "Corpus", "Suite", "Java", "Comparator", "Median", "Range", "Row"),
        ((row["platform"], row["rebar_corpus"], row["suite"], row["candidate_system"],
          row["comparator_system"], ratio(row["median_ratio"]),
          f"{ratio(row['minimum_ratio'])} to {ratio(row['maximum_ratio'])}",
          f"`{short_row_id(row['candidate_row_id'])}`") for row in outliers))

    for corpus, title in (("curated", "Curated Rebar"), ("extended", "Extended Rebar (Informational)")):
        lines.extend([f"## {title}", ""])
        corpus_classes = [row for row in classes if row["rebar_corpus"] == corpus]
        corpus_outliers = [row for row in outliers if row["rebar_corpus"] == corpus]
        lines.append(
            f"{len(corpus_classes)} general platform/class summaries; "
            f"{len(corpus_outliers)} serious platform/row outliers. Extended results are informational "
            "and are not combined with the curated corpus." if corpus == "extended" else
            f"{len(corpus_classes)} general platform/class summaries; "
            f"{len(corpus_outliers)} serious platform/row outliers.")
        lines.append("")
        lines += table(
            ("Platform", "Suite", "Java", "Comparator", "Rows", "Median", "P10", "P90"),
            ((row["platform"], row["suite"], row["candidate_system"], row["comparator_system"],
              row["comparison_count"], ratio(row["median_ratio"]), ratio(row["p10_ratio"]), ratio(row["p90_ratio"]))
             for row in sorted(corpus_classes, key=lambda row: (
                 row["platform"], row["suite"], row["candidate_system"], row["comparator_system"]))))

    lines.extend(["## Stability And Allocation", ""])
    condition_counts = Counter(gap["condition"] for gap in gaps if gap["severity"] == "unresolved")
    lines += table(
        ("Unresolved condition", "Count"),
        ((condition, str(count)) for condition, count in sorted(condition_counts.items())))
    lines.append(
        "The row table contains host CV, allocation, maximum calibration drift, maximum native-bracket drift, "
        "contract-identity consistency, and scaling classification. Contract identity describes the benchmark "
        "setup and expected-result contract; it is not a checksum of a measured output. The host table preserves "
        "every underlying score and allocation result.")
    lines.append("")

    lines.extend(["## Host Capacity", ""])
    capacity_rows = []
    for platform in summary["platforms"]:
        values = [row for row in capacity if row["platform"] == platform]
        maximum_rss = max(float(row["maximum_resident_kibibytes"]) * 1024 for row in values)
        memory_total = min(float(row["memory_total_bytes"]) for row in values)
        capacity_rows.append((
            platform,
            str(len(values)),
            f"{max(float(row['wall_seconds']) for row in values):.1f}",
            f"{maximum_rss / (1024 ** 3):.2f}",
            percent(maximum_rss / memory_total),
        ))
    lines += table(
        ("Platform", "Sessions", "Maximum wall seconds", "Maximum RSS GiB", "Maximum RSS / memory"),
        capacity_rows)

    lines.extend(["## Gap Ledger", ""])
    lines += table(
        ("Severity", "Platform", "Shard", "Condition", "Observed", "Row or scope"),
        ((gap["severity"], gap["platform"] or "-", gap["shard_id"] or "-", gap["condition"],
          gap["observed"], f"`{short_row_id(gap['row_id'] or gap['scope'])}`")
         for gap in gaps))

    lines.extend(["## Interruptions, Rejections, And Exclusions", ""])
    lines += table(
        ("Type", "Platform", "Shard", "Replica", "Epoch", "Reason", "Details", "Artifact"),
        ((event["event_type"], event["platform"] or "-", event["shard_id"] or "-",
          event["replica_id"] or "-", event["host_epoch"] or "-", event["reason"],
          event["details"] or "-", event["artifact_uri"] or "-") for event in events))

    lines.extend(["## Artifact Provenance", ""])
    campaign_artifact = summary.get("campaign_artifact", {})
    if campaign_artifact:
        lines += table(("Campaign archive", "Value"), (
            ("S3 URI", campaign_artifact["s3_uri"]),
            ("Version ID", campaign_artifact["version_id"]),
            ("Archive SHA-256", f"`{campaign_artifact['archive_sha256']}`"),
            ("Source manifest SHA-256", f"`{campaign_artifact['source_manifest_sha256']}`"),
            ("Candidate commit", f"`{campaign_artifact['candidate_commit']}`"),
            ("Candidate ref", f"`{campaign_artifact['candidate_ref']}`"),
            ("Archived source directory", campaign_artifact["source_directory"]),
            ("Archived files", campaign_artifact["file_count"]),
        ))
    retrieval = Counter(
        "per-session" if row["artifact_uri"] else
        "campaign-archive" if row.get("campaign_s3_uri") else "local-only"
        for row in inventory)
    lines += table(("Inventory category", "Files"), ((key, str(value)) for key, value in sorted(retrieval.items())))
    lines.append(
        f"The artifact inventory contains {len(inventory):,} checksummed files and the metadata table contains "
        f"{len(metadata):,} source or retrieval properties. `output-checksums.tsv` binds every reducer input table "
        "used by this report; the report checksum is stored beside this file.")
    lines.append("")

    lines.extend(["## Machine-Readable Outputs", ""])
    for name, description in (
        ("host-rows.tsv", "Every accepted primary and confirmation host row"),
        ("row-aggregates.tsv", "Per-platform row medians, CV, allocation, drift, scaling, and resolution state"),
        ("host-comparator-ratios.tsv", "Same-host normalized Java/comparator ratios"),
        ("comparison-aggregates.tsv", "Per-platform comparison medians and direction agreement"),
        ("general-classes.tsv", "General comparison classes with serious outliers excluded"),
        ("serious-outliers.tsv", "Rows with at least a twofold median difference"),
        ("scaling.tsv", "Identifiable per-host input-size scaling signatures"),
        ("gap-ledger.tsv", "Unresolved conditions, serious outliers, and campaign events"),
        ("events.tsv", "Interruptions, rejected sessions, and exclusions"),
        ("artifact-inventory.tsv", "Local and external artifact checksums and retrieval data"),
        ("metadata.tsv", "Run and retrieval metadata passthrough"),
        ("capacity.tsv", "Finalized wall-time, peak-RSS, and memory evidence for every host session"),
        ("confirmation-jobs.tsv", "Unique primary-unstable platform/shard pairs eligible for replica 4"),
    ):
        lines.append(f"- `{name}`: {description}.")
    lines.append("")

    content = "\n".join(lines)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(content, encoding="utf-8")
    digest_path = arguments.output.with_suffix(arguments.output.suffix + ".sha256")
    digest_path.write_text(f"{sha256_file(arguments.output)}  {arguments.output.name}\n", encoding="utf-8")


def parser():
    argument_parser = argparse.ArgumentParser(description=__doc__)
    argument_parser.add_argument("--input-dir", type=Path, required=True)
    argument_parser.add_argument("--output", type=Path, required=True)
    argument_parser.add_argument("--title", default="Pre-Review Performance Baseline")
    return argument_parser


def main():
    argument_parser = parser()
    arguments = argument_parser.parse_args()
    try:
        render(arguments)
    except (OSError, KeyError, ValueError, json.JSONDecodeError, ReportError) as error:
        argument_parser.error(str(error))


if __name__ == "__main__":
    main()
