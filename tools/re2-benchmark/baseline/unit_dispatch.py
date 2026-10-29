"""The campaign controller's unit ledgers, its S3 exchange with unit workers, and accepted attempts' layout.

A unit campaign's controller leases work units to worker hosts through S3, retains each accepted attempt
under units/<platform>/<unit>/replica-<n>/<host session>/lease-<n>, and records it in accepted-units.tsv.
Recovery and the reducer find retained attempts by the same layout.
"""

import csv
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
from datetime import datetime
from pathlib import Path

import unit_session
from campaign_io import UnsafeCampaignError, aws_output, check_output, fsync_directory, write_ledger

UNIT_ATTEMPT_FIELDS = (
    "platform", "unit_id", "replica", "host_epoch", "host_session", "sequence", "market", "outcome", "detail")
# The uploads only a running worker makes: a heartbeat, a lease's interruption bundle, and a unit completion.
WORKER_EVIDENCE_PATTERN = r"heartbeat/[0-9]+\.json|events/[0-9]+-interrupted\.tar\.gz|units/[0-9]+/completion\.json"
# The completion marker fields the controller reads; a unit worker writes each of them.
UNIT_COMPLETION_FIELDS = (
    "schema_version", "host_session", "sequence", "campaign_id", "phase", "platform", "unit_id", "replica_id",
    "unit_attempt_id", "exit_status", "unit_status", "result_sha256")


def accepted_row(rows, receipt, identity, attempt_field, attempts):
    """The ledger row already accepted for the receipt's logical identity, or None; it must be the receipt.

    attempt_field names the field, and attempts the plural, that tell two attempts of one identity apart.
    """
    logical_identity = identity(receipt)
    existing = next((row for row in rows if identity(row) == logical_identity), None)
    if existing is not None and existing != receipt:
        raise UnsafeCampaignError(
            f"conflicting accepted receipts for {logical_identity}: "
            f"{attempts} {existing[attempt_field]} and {receipt[attempt_field]}")
    return existing


def unit_logical_identity(receipt):
    return f"{receipt['platform']}/{receipt['unit_id']}/replica-{receipt['replica_id']}"


def unit_attempt_directory(result_root, receipt):
    """A unit attempt's retained directory, named by its platform, unit, replica, host session and lease."""
    host_session, lease = unit_session.split_unit_attempt(receipt["unit_attempt_id"])
    return (result_root / "units" / receipt["platform"] / receipt["unit_id"] / f"replica-{receipt['replica_id']}" /
            host_session / f"lease-{lease}")


def read_unit_receipt(path):
    with path.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != unit_session.UNIT_RECEIPT_FIELDS:
            raise RuntimeError(f"unexpected unit receipt schema in {path}")
        rows = list(reader)
    if len(rows) != 1:
        raise RuntimeError(f"{path} must contain exactly one receipt")
    return rows[0]


def read_accepted_units(path):
    if not path.exists():
        return []
    with path.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != unit_session.UNIT_RECEIPT_FIELDS:
            raise UnsafeCampaignError(f"unexpected unit receipt schema in {path}")
        rows = list(reader)
    for identities in ([unit_logical_identity(row) for row in rows], [row["unit_attempt_id"] for row in rows]):
        if len(identities) != len(set(identities)):
            raise UnsafeCampaignError(f"accepted-unit ledger contains duplicate units or attempts: {path}")
    return rows


def persist_accepted_unit(path, receipt_path):
    """Append one unit attempt's receipt to accepted-units.tsv atomically."""
    receipt = read_unit_receipt(receipt_path)
    rows = read_accepted_units(path)
    if accepted_row(rows, receipt, unit_logical_identity, "unit_attempt_id", "attempts") is not None:
        return receipt
    if any(row["unit_attempt_id"] == receipt["unit_attempt_id"] for row in rows):
        raise UnsafeCampaignError(f"unit attempt {receipt['unit_attempt_id']} is accepted for another unit")
    write_ledger(path, [*rows, receipt], unit_session.UNIT_RECEIPT_FIELDS, lambda row: (
        row["platform"], row["shard_id"], row["unit_id"], int(row["replica_id"])))
    return receipt


def read_unit_attempts(path):
    if not path.exists():
        return []
    with path.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != UNIT_ATTEMPT_FIELDS:
            raise UnsafeCampaignError(f"unexpected unit attempt schema in {path}")
        return list(reader)


def write_unit_attempt(path, worker, sequence, item, outcome, detail="-"):
    """Append one lease or lease outcome to unit-attempts.tsv. A stop lease names no unit, and a lease to a
    worker that has not yet published its host session names no session."""
    write_header = not path.exists()
    with path.open("a", newline="") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        if write_header:
            writer.writerow(UNIT_ATTEMPT_FIELDS)
        writer.writerow((worker.job.platform.name, item.unit_id if item else "-", item.replica if item else "-",
                         worker.job.host_epoch, worker.host_session if worker.metadata else "-", sequence,
                         worker.job.market, outcome, detail.replace("\t", " ").replace("\n", " ")))
        output_file.flush()
        os.fsync(output_file.fileno())


def write_unresolved(path, fields, rows):
    """Replace an unresolved-work ledger atomically, each row ending in its detail; with no rows, remove it."""
    if not rows:
        if path.exists():
            path.unlink()
        return
    temporary_path = path.with_name(path.name + ".new")
    with temporary_path.open("w", newline="") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        writer.writerow(fields)
        for *row, detail in rows:
            writer.writerow((*row, detail.replace("\t", " ").replace("\n", " ")))
        output_file.flush()
        os.fsync(output_file.fileno())
    os.replace(temporary_path, path)
    fsync_directory(path.parent)


def write_unresolved_units(path, unresolved):
    write_unresolved(path, ("platform", "unit_id", "replica", "detail"),
                     [(item.platform, item.unit_id, item.replica, detail) for item, detail in unresolved])


def read_semantic_rejections(path):
    """The phase's semantic-rejection ledger; the controller writes it when the phase starts."""
    try:
        input_file = path.open(newline="")
    except FileNotFoundError as error:
        raise UnsafeCampaignError(f"unit campaign phase has no semantic-rejection ledger: {path}") from error
    with input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != unit_session.SEMANTIC_REJECTION_FIELDS:
            raise UnsafeCampaignError(f"unexpected semantic-rejection schema in {path}")
        return list(reader)


def persist_semantic_rejection(path, item, attempt):
    """Add one unit attempt the correctness stage rejected to the ledger atomically, once."""
    rows = read_semantic_rejections(path)
    if any(row["unit_attempt_id"] == attempt for row in rows):
        return
    write_ledger(path, [*rows, {"platform": item.platform, "unit_id": item.unit_id,
                                "replica_id": str(item.replica), "unit_attempt_id": attempt}],
                 unit_session.SEMANTIC_REJECTION_FIELDS, lambda row: row["unit_attempt_id"])


def shows_worker_start(keys):
    """Whether a unit worker's uploads show its worker started. The host archive that the user data's exit
    trap uploads, even after a failed bootstrap, does not."""
    return any(re.fullmatch(WORKER_EVIDENCE_PATTERN, key) for key in keys)


def heartbeat_key(sequence):
    return f"heartbeat/{sequence}.json"


def heartbeat_sequence(key):
    """The lease sequence a heartbeat's key names, or None for any other key."""
    match = re.fullmatch(r"heartbeat/([0-9]+)\.json", key)
    return int(match[1]) if match else None


def interruption_key(sequence):
    return f"events/{sequence}-interrupted.tar.gz"


def completion_key(sequence):
    return f"units/{sequence}/completion.json"


def result_key(sequence):
    return f"units/{sequence}/result.tar.gz"


def recovered_keys(objects):
    """The keys of a worker's uploads that its cleanup recovered into objects."""
    return {path.relative_to(objects).as_posix() for path in objects.rglob("*") if path.is_file()}


def unit_worker_aws(arguments, worker):
    return aws_output(arguments, worker.metadata["aws_region"])


def list_unit_worker_results(arguments, worker):
    """Each object under a worker's result prefix, by its key below the prefix, with its modification time."""
    prefix = worker.metadata["result_prefix"] + "/"
    listing = json.loads(check_output(unit_worker_aws(arguments, worker) + [
        "s3api", "list-objects-v2", "--bucket", worker.metadata["bucket"], "--prefix", prefix,
        "--output", "json"]) or "{}")
    return {item["Key"][len(prefix):]: datetime.fromisoformat(item["LastModified"]).timestamp()
            for item in listing.get("Contents") or [] if item["Key"].startswith(prefix)}


def get_unit_worker_result(arguments, worker, key, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    check_output(unit_worker_aws(arguments, worker) + [
        "s3api", "get-object", "--bucket", worker.metadata["bucket"],
        "--key", f"{worker.metadata['result_prefix']}/{key}", str(path)])


def put_unit_lease(arguments, worker, sequence, document):
    """Write a worker's lease once; a lease already written, before a restart or a lost response, must
    hold this same document."""
    bucket = worker.metadata["bucket"]
    key = f"{worker.metadata['input_prefix']}/leases/{sequence}.json"
    body = json.dumps(document, sort_keys=True) + "\n"
    command = unit_worker_aws(arguments, worker) + ["s3api"]
    with tempfile.TemporaryDirectory(prefix="unit-lease-") as temporary:
        path = Path(temporary) / "lease.json"
        path.write_text(body)
        try:
            check_output(command + ["put-object", "--bucket", bucket, "--key", key, "--body", str(path),
                                    "--if-none-match", "*"])
        except subprocess.CalledProcessError as error:
            existing = Path(temporary) / "existing.json"
            check_output(command + ["get-object", "--bucket", bucket, "--key", key, str(existing)])
            if existing.read_text() != body:
                raise UnsafeCampaignError(
                    f"unit worker {worker.host_session} lease {sequence} differs from its ledger") from error


def unit_worker_instance_state(arguments, worker):
    if not worker.instance_id:
        return "unknown"
    try:
        return check_output(unit_worker_aws(arguments, worker) + [
            "ec2", "describe-instances", "--instance-ids", worker.instance_id,
            "--query", "Reservations[0].Instances[0].State.Name", "--output", "text"])
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def extract_unit_result(archive, directory):
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    with tarfile.open(archive, "r:gz") as result:
        result.extractall(directory, filter="data")


def retain_unit_attempt(directory, final):
    """Copy an accepted attempt to its retained directory."""
    if final.exists():
        raise UnsafeCampaignError(f"retained unit attempt already exists: {final}")
    temporary = final.with_name(f".{final.name}.new")
    if temporary.exists():
        shutil.rmtree(temporary)
    shutil.copytree(directory, temporary)
    os.replace(temporary, final)
    fsync_directory(final.parent)
