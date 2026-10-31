"""Durable ledger writes, command output and the unsafe-campaign error that a unit campaign's modules share."""

import csv
import io
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


def append_ledger(path, fields, row):
    """Append one row to a TSV ledger durably. A new ledger gets its whole header before any row, and a row a crash
    tore before its line end is cut off before the next is appended, so each line a ledger ends is a whole row."""
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        temporary_path = path.with_name(path.name + ".new")
        with temporary_path.open("w", newline="") as output_file:
            csv.DictWriter(output_file, fieldnames=fields, delimiter="\t", lineterminator="\n").writeheader()
            output_file.flush()
            os.fsync(output_file.fileno())
        os.replace(temporary_path, path)
        fsync_directory(path.parent)
    line = io.StringIO()
    csv.DictWriter(line, fieldnames=fields, delimiter="\t", lineterminator="\n").writerow(row)
    with path.open("rb+") as output_file:
        end = output_file.seek(-1, os.SEEK_END) + 1
        if output_file.read(1) != b"\n":
            output_file.seek(0)
            end = output_file.read().rfind(b"\n") + 1
            output_file.truncate(end)
        output_file.seek(end)
        output_file.write(line.getvalue().encode())
        output_file.flush()
        os.fsync(output_file.fileno())


def ledger_lines(path):
    """A TSV ledger's lines, to read with csv: those its appends ended, without a row a crash tore before its line
    end, which the next append cuts off."""
    with path.open(newline="") as input_file:
        text = input_file.read()
    return io.StringIO(text[:text.rfind("\n") + 1], newline="")


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
