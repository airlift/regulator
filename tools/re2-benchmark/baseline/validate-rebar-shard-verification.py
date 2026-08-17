#!/usr/bin/env python3

import argparse
import csv
import re
from pathlib import Path


OUTCOME_FIELDS = ("row_id", "suite", "system", "benchmark", "model", "outcome", "detail")

ENGINE_SYSTEMS = {
    "joni/trino": "joni",
    "regulator/re2": "regulator-native-access",
    "regulator/re2-object": "regulator-object-row",
    "re2/pinned-host-tuned-before": "native-re2-before",
    "re2/pinned-host-tuned-after": "native-re2-after",
}
SEMANTIC_MISMATCH = re.compile(r"count mismatch, expected [0-9]+, got [0-9]+")
# Pinned Rebar measure.rs formats a Rust Duration with {:?} in this error.
TIMEOUT = re.compile(r"timeout: exceeded [0-9]+(?:\.[0-9]+)?(?:ns|µs|ms|s)")


def read_verification(path, measurement):
    with path.open(newline="", encoding="utf-8") as source:
        if measurement:
            reader = csv.DictReader(source)
            required = {"name", "model", "engine", "engine_version", "err", "median"}
            if not required.issubset(reader.fieldnames or ()):
                raise ValueError("Rebar measurement CSV is missing columns")
            for row in reader:
                if None in row or any(row[field] is None for field in required):
                    raise ValueError("Malformed Rebar measurement row")
                yield row["name"], row["model"], row["engine"], row["engine_version"], row["err"] or "OK"
        else:
            for line_number, row in enumerate(csv.reader(source), start=1):
                if len(row) != 5:
                    raise ValueError(f"Verification line {line_number} has {len(row)} fields")
                yield row


def validate(
        manifest_path,
        shard,
        systems,
        verification_path,
        outcomes_path,
        command_status=0,
        append=False,
        measurement=False):
    previous_outcomes = []
    if append and outcomes_path.exists():
        with outcomes_path.open(newline="", encoding="utf-8") as source:
            reader = csv.DictReader(source, delimiter="\t")
            if tuple(reader.fieldnames or ()) != OUTCOME_FIELDS:
                raise ValueError(f"Unexpected Rebar outcome fields: {reader.fieldnames}")
            previous_outcomes = list(reader)
    excluded_ids = {row["row_id"] for row in previous_outcomes} if measurement else set()
    if measurement and (systems != {"joni"} or not append):
        raise ValueError("Measurement classification requires an appended Joni invocation")
    expected = {}
    with manifest_path.open(newline="", encoding="utf-8") as source:
        for row in csv.DictReader(source, delimiter="\t"):
            if row["shard_id"] != shard or row["system"] not in systems:
                continue
            if row["row_id"] in excluded_ids:
                continue
            model = row["parameters"].split(";", 1)[0].split("=", 1)[1]
            key = (row["benchmark"], model, row["system"])
            if key in expected:
                raise ValueError(f"Duplicate Rebar manifest row {key!r}")
            expected[key] = row

    observed = set()
    current_outcomes = []
    for verification in read_verification(verification_path, measurement):
        benchmark, model, engine, version, status = verification
        system = ENGINE_SYSTEMS.get(engine)
        if system is None or system not in systems:
            raise ValueError(f"Unexpected Rebar engine {engine!r}")
        key = (benchmark, model, system)
        row = expected.get(key)
        if row is None:
            raise ValueError(f"Unexpected Rebar verification row {key!r}")
        if key in observed:
            raise ValueError(f"Duplicate Rebar verification row {key!r}")
        if not version:
            raise ValueError(f"Rebar verification has no version for {benchmark!r}/{engine}")
        observed.add(key)
        if status == "OK":
            continue
        if system == "joni" and TIMEOUT.fullmatch(status):
            current_outcomes.append({
                "row_id": row["row_id"],
                "suite": row["suite"],
                "system": system,
                "benchmark": benchmark,
                "model": model,
                "outcome": "did-not-finish",
                "detail": status,
            })
            continue
        if row["suite"] == "rebar-curated" or measurement:
            raise ValueError(
                f"Curated Rebar verification failed for {benchmark!r}/{engine}: {status}")
        if SEMANTIC_MISMATCH.fullmatch(status) is None:
            raise ValueError(
                f"Unclassified extended Rebar verification failure for "
                f"{benchmark!r}/{engine}: {status}")
        current_outcomes.append({
            "row_id": row["row_id"],
            "suite": row["suite"],
            "system": system,
            "benchmark": benchmark,
            "model": model,
            "outcome": "semantic-mismatch",
            "detail": status,
        })

    if observed != set(expected):
        raise ValueError(
            f"Rebar verification differs from rows.tsv; missing={sorted(set(expected) - observed)[:20]}, "
            f"unexpected={sorted(observed - set(expected))[:20]}")
    if command_status != 0 and (measurement or command_status != 1 or not current_outcomes):
        raise ValueError(
            f"Rebar verification exited with status {command_status} without a classified outcome")
    if command_status == 0 and current_outcomes and not measurement:
        raise ValueError("Rebar verification reported classified outcomes but exited successfully")

    outcomes = list(current_outcomes)
    if append:
        outcomes.extend(previous_outcomes)
        outcome_ids = [outcome["row_id"] for outcome in outcomes]
        if len(outcome_ids) != len(set(outcome_ids)):
            raise ValueError("Duplicate Rebar outcome row IDs")

    outcomes_path.parent.mkdir(parents=True, exist_ok=True)
    with outcomes_path.open("w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(
            output_file,
            fieldnames=OUTCOME_FIELDS,
            delimiter="\t",
            lineterminator="\n")
        writer.writeheader()
        writer.writerows(sorted(outcomes, key=lambda outcome: outcome["row_id"]))
    print(f"Verified {len(observed)} exact Rebar system rows; recorded {len(outcomes)} classified outcomes")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--shard", required=True)
    parser.add_argument("--systems", required=True)
    parser.add_argument("--verification", type=Path, required=True)
    parser.add_argument("--outcomes", type=Path, required=True)
    parser.add_argument("--command-status", type=int, default=0)
    parser.add_argument("--append", action="store_true")
    parser.add_argument("--measurement", action="store_true")
    arguments = parser.parse_args()
    try:
        validate(
            arguments.manifest,
            arguments.shard,
            set(arguments.systems.split(",")),
            arguments.verification,
            arguments.outcomes,
            arguments.command_status,
            arguments.append,
            arguments.measurement)
    except (OSError, ValueError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
