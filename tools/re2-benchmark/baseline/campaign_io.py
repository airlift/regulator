"""Durable ledger writes, command output and the unsafe-campaign error that a unit campaign's modules share."""

import csv
import os
import subprocess


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


def write_ledger(path, rows, fields,
                 key=lambda row: (row["platform"], row["shard_id"], int(row["replica_id"]))):
    """Replace a TSV ledger with its rows, durably, in key order."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_name(path.name + ".new")
    rows = sorted(rows, key=key)
    with temporary_path.open("w", newline="") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
        output_file.flush()
        os.fsync(output_file.fileno())
    os.replace(temporary_path, path)
    fsync_directory(path.parent)
