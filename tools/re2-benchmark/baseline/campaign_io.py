"""Durable ledger writes, command output and the unsafe-campaign error of the campaign controller."""

import csv
import json
import os
import subprocess

from acceptance import RECEIPT_FIELDS


class UnsafeCampaignError(RuntimeError):
    """The campaign cannot continue without risking invalid evidence or leaked resources."""


def check_output(command, **kwargs):
    return subprocess.check_output(command, text=True, **kwargs).strip()


def fsync_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def aws_output(arguments, region=None):
    environment = os.environ.copy()
    region = region or environment.get("AWS_REGION", "us-west-2")
    command = ["aws", "--region", region]
    profile = environment.get("AWS_PROFILE")
    if profile:
        command[1:1] = ["--profile", profile]
    return command


def write_accepted_sessions(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_name(path.name + ".new")
    rows = sorted(rows, key=lambda row: (row["platform"], row["shard_id"], int(row["replica_id"])))
    with temporary_path.open("w", newline="") as output_file:
        if path.suffix == ".json":
            json.dump({"schema_version": 1, "workload": "language-batch", "receipts": rows}, output_file, sort_keys=True)
            output_file.write("\n")
        else:
            writer = csv.DictWriter(output_file, fieldnames=RECEIPT_FIELDS, delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
        output_file.flush()
        os.fsync(output_file.fileno())
    os.replace(temporary_path, path)
    fsync_directory(path.parent)
