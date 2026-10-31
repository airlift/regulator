"""Durable ledger writes, command output and the unsafe-campaign error that a unit campaign's modules share."""

import contextlib
import csv
import fcntl
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


@contextlib.contextmanager
def locked(lock_path):
    """Hold an exclusive lock on the file at lock_path, creating it if need be."""
    with lock_path.open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def append_ledger(path, fields, row):
    """Append one row to a TSV ledger durably. A new ledger gets its whole header before any row. A line break in a
    value is written as a space, so each row is one line. A row a crash tore before its line end is cut off before
    the next row is appended, so every complete line of a ledger is a whole row."""
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        write_ledger(path, (), fields)
    line = io.StringIO()
    csv.DictWriter(line, fieldnames=fields, delimiter="\t", lineterminator="\n").writerow({
        name: value.replace("\r", " ").replace("\n", " ") if isinstance(value, str) else value
        for name, value in row.items()})
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
    """A TSV ledger's complete lines, for csv to read. A row a crash cut short before its line end, even inside a
    multibyte character, is left out; the next append cuts it off."""
    data = path.read_bytes()
    return io.StringIO(data[:data.rfind(b"\n") + 1].decode(), newline="")


def ledger_rows(path):
    """A TSV ledger's rows, as a csv reader over its ledger lines."""
    return csv.DictReader(ledger_lines(path), delimiter="\t")


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
