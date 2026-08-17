#!/usr/bin/env python3

import argparse
import csv
import hashlib
import re
import sys
from pathlib import Path


DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(DIRECTORY))

from acceptance import RECEIPT_FIELDS, validate_host_results  # noqa: E402


RECOVERY_FIELDS = (
    "schema_version",
    "status",
    "reason",
    "session_directory",
    "controller_log",
    "controller_log_sha256",
    "cleanup_manifest_sha256",
    "acceptance_receipt_sha256",
    "observed_rows_sha256",
)
PARSER_RACE = re.compile(
    r"run-campaign\.sh: line \d+: syntax error near unexpected token .done.")


def sha256_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_properties(path):
    values = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise ValueError(f"invalid property line {path}:{line_number}")
        values[key] = value
    return values


def read_one_tsv(path, fields):
    with path.open(newline="", encoding="utf-8") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != tuple(fields):
            raise ValueError(f"unexpected columns in {path}")
        rows = list(reader)
    if len(rows) != 1:
        raise ValueError(f"expected one row in {path}, found {len(rows)}")
    return rows[0]


def verify_recovery_context(cleanup, controller_log):
    required_cleanup = {
        "instances_terminated": "verified",
        "iam_removed": "verified",
        "bucket_removed": "verified",
        "cleanup_status": "verified",
        "run_status": "failed",
        "failure_classification": "none",
    }
    changed = [field for field, value in required_cleanup.items() if cleanup.get(field) != value]
    if changed:
        raise ValueError(f"cleanup is not verified for recovery: {changed}")
    downloaded = re.search(r"^Downloaded (?:intel|arm) results$", controller_log, re.MULTILINE)
    parser_race = PARSER_RACE.search(controller_log)
    if downloaded is None or parser_race is None or downloaded.start() > parser_race.start():
        raise ValueError("controller log does not contain the post-download parser-race signature")


def recover_session(session_directory, controller_log_path, manifest_path):
    if list(session_directory.glob("*/re2-results/language-acceptance.json")):
        raise ValueError("parser-race recovery is baseline-only; resume language jobs through the controller")
    if (session_directory / "campaign-success").exists():
        raise ValueError(f"{session_directory} is already a successful campaign session")
    cleanup_path = session_directory / "cleanup-manifest.txt"
    cleanup = read_properties(cleanup_path)
    controller_log = controller_log_path.read_text(encoding="utf-8")
    verify_recovery_context(cleanup, controller_log)

    receipts = list(session_directory.glob("*/re2-results/acceptance-receipt.tsv"))
    if len(receipts) != 1:
        raise ValueError(f"expected one host acceptance receipt, found {len(receipts)}")
    acceptance_path = receipts[0]
    result_directory = acceptance_path.parent
    acceptance = read_one_tsv(acceptance_path, RECEIPT_FIELDS)
    regenerated = validate_host_results(
        manifest_path,
        result_directory / "session.tsv",
        result_directory / "observed-rows.tsv")
    changed = [field for field in RECEIPT_FIELDS if acceptance[field] != regenerated[field]]
    if changed:
        raise ValueError(f"host acceptance receipt cannot be reproduced: {changed}")

    recovery = {
        "schema_version": "1",
        "status": "accepted",
        "reason": "local-post-download-script-edit-parser-race",
        "session_directory": str(session_directory.resolve()),
        "controller_log": str(controller_log_path.resolve()),
        "controller_log_sha256": sha256_file(controller_log_path),
        "cleanup_manifest_sha256": sha256_file(cleanup_path),
        "acceptance_receipt_sha256": sha256_file(acceptance_path),
        "observed_rows_sha256": sha256_file(result_directory / "observed-rows.tsv"),
    }
    output_path = session_directory / "recovery-receipt.tsv"
    with output_path.open("w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(
            output_file, fieldnames=RECOVERY_FIELDS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerow(recovery)
    return output_path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--session-directory", type=Path, required=True)
    parser.add_argument("--controller-log", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=DIRECTORY / "rows.tsv")
    arguments = parser.parse_args()
    print(recover_session(
        arguments.session_directory,
        arguments.controller_log,
        arguments.manifest))


if __name__ == "__main__":
    main()
