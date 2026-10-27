"""Durable ledger writes, command output and the unsafe-campaign error that host and unit campaigns share."""

import csv
import json
import os
import subprocess

JSON_LEDGER_WORKLOADS = {
    "accepted-language-sessions.json": "language-batch",
    "accepted-engine-builds.json": "engine-build",
}


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


def write_ledger(path, rows, fields=None,
                 key=lambda row: (row["platform"], row["shard_id"], int(row["replica_id"]))):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_name(path.name + ".new")
    rows = sorted(rows, key=key)
    with temporary_path.open("w", newline="") as output_file:
        if path.suffix == ".json":
            json.dump({"schema_version": 1, "workload": JSON_LEDGER_WORKLOADS[path.name], "receipts": rows},
                      output_file, sort_keys=True)
            output_file.write("\n")
        else:
            # A TSV ledger, such as a unit campaign's, names its fields.
            writer = csv.DictWriter(output_file, fieldnames=fields, delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
        output_file.flush()
        os.fsync(output_file.fileno())
    os.replace(temporary_path, path)
    fsync_directory(path.parent)
