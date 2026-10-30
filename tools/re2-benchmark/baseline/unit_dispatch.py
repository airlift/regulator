"""The campaign controller's unit ledgers, its S3 exchange with unit workers, and accepted attempts' layout.

A unit campaign's controller leases work units to worker hosts through S3, retains each accepted measured
group of an attempt under units/<platform>/<unit>/replica-<n>/<host session>/lease-<n>/group-<g>, and records
it in accepted-units.tsv. Recovery and the reducer find retained attempts by the same layout.
"""

import csv
import json
import os
import re
import shutil
import tarfile
import tempfile
from datetime import datetime
from pathlib import Path

import unit_session
from cloud import CloudError
from campaign_io import UnsafeCampaignError, append_ledger, fsync_directory, write_ledger

# A lease row's groups are those it leased, in run order; an outcome row's are those whose markers it processed.
UNIT_ATTEMPT_FIELDS = (
    "platform", "unit_id", "replica", "groups", "host_epoch", "host_session", "sequence", "market", "outcome",
    "detail")
# The uploads only a running worker makes: a heartbeat, a lease's interruption bundle, and a group completion.
WORKER_EVIDENCE_PATTERN = (r"heartbeat/[0-9]+\.json|events/[0-9]+-interrupted\.tar\.gz|"
                           r"units/[0-9]+/groups/[0-9]+/completion\.json")
# The completion marker fields the controller reads; a unit worker writes each of them.
UNIT_COMPLETION_FIELDS = (
    "schema_version", "host_session", "sequence", "campaign_id", "phase", "platform", "unit_id", "replica_id",
    "group_id", "unit_attempt_id", "exit_status", "unit_status", "result_sha256", "result_bytes", "pack_seconds",
    "upload_seconds", "upload_attempts", "started_at", "finished_at")
# How long each group took to measure, and its result to pack and upload, as its completion marker reports it, one
# row per marker. A ledger an earlier controller wrote lacks the measured seconds.
UNIT_UPLOADS = "unit-uploads.tsv"
UPLOAD_TIMING_FIELDS = ("result_bytes", "pack_seconds", "upload_seconds", "upload_attempts")
UNIT_UPLOAD_FIELDS = ("platform", "unit_id", "replica", "group_id", "host_session", "sequence", *UPLOAD_TIMING_FIELDS,
                      "group_seconds")
UNRESOLVED_UNIT_FIELDS = ("platform", "unit_id", "replica", "detail")


def unit_logical_identity(receipt):
    """The unit replica a receipt measured part of: its work item's identity."""
    return f"{receipt['platform']}/{receipt['unit_id']}/replica-{receipt['replica_id']}"


def group_identity(receipt):
    """The one measured group of a unit replica a receipt accepts."""
    return f"{unit_logical_identity(receipt)}/group-{receipt['group_id']}"


def group_attempt(receipt):
    return f"{receipt['unit_attempt_id']} group {receipt['group_id']}"


def unit_attempt_directory(result_root, receipt):
    """An accepted group's retained directory, named by its platform, unit, replica, host session, lease and
    group."""
    host_session, lease = unit_session.split_unit_attempt(receipt["unit_attempt_id"])
    return (result_root / "units" / receipt["platform"] / receipt["unit_id"] / f"replica-{receipt['replica_id']}" /
            host_session / f"lease-{lease}" / f"group-{receipt['group_id']}")


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
    for identities in ([group_identity(row) for row in rows], [group_attempt(row) for row in rows]):
        if len(identities) != len(set(identities)):
            raise UnsafeCampaignError(f"accepted-unit ledger contains duplicate groups or attempts: {path}")
    return rows


def persist_accepted_unit(path, receipt):
    """Append one accepted group's receipt to accepted-units.tsv durably. The controller accepts each group once,
    so the ledger grows by a row per acceptance rather than being rewritten."""
    append_ledger(path, unit_session.UNIT_RECEIPT_FIELDS, receipt)


def read_unit_uploads(path):
    if not path.exists():
        return []
    with path.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) not in (UNIT_UPLOAD_FIELDS, UNIT_UPLOAD_FIELDS[:-1]):
            raise UnsafeCampaignError(f"unexpected unit upload schema in {path}")
        return list(reader)


def measured_group_seconds(path):
    """The seconds each group took to measure, by its unit attempt and group, as unit-uploads.tsv records them; None
    for a group an earlier controller recorded."""
    return {(unit_session.format_unit_attempt(row["host_session"], row["sequence"]), row["group_id"]):
            None if row.get("group_seconds") is None else float(row["group_seconds"])
            for row in read_unit_uploads(path)}


def marker_seconds(marker):
    """The seconds a group took to measure, from its completion marker's start and finish, each a UTC time to the
    second."""
    start, finish = (datetime.strptime(marker[field], "%Y-%m-%dT%H:%M:%SZ") for field in ("started_at", "finished_at"))
    return (finish - start).total_seconds()


def unit_upload_row(item, host_session, sequence, marker):
    """A group's measured seconds and upload timings from its completion marker, as a row of unit-uploads.tsv."""
    return {"platform": item.platform, "unit_id": item.unit_id, "replica": str(item.replica),
            "group_id": marker["group_id"], "host_session": host_session, "sequence": str(sequence),
            **{field: str(marker[field]) for field in UPLOAD_TIMING_FIELDS},
            "group_seconds": f"{marker_seconds(marker):g}"}


def persist_unit_upload(path, row):
    """Append a group's upload timings to unit-uploads.tsv durably; the controller records each marker once."""
    append_ledger(path, UNIT_UPLOAD_FIELDS, row)


def read_unit_attempts(path):
    if not path.exists():
        return []
    with path.open(newline="") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != UNIT_ATTEMPT_FIELDS:
            raise UnsafeCampaignError(f"unexpected unit attempt schema in {path}")
        return list(reader)


def write_unit_attempt(path, worker, sequence, item, outcome, detail="-", groups=()):
    """Append one lease or lease outcome to unit-attempts.tsv. A stop lease names no unit."""
    write_header = not path.exists()
    with path.open("a", newline="") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        if write_header:
            writer.writerow(UNIT_ATTEMPT_FIELDS)
        writer.writerow((worker.job.platform.name, item.unit_id if item else "-", item.replica if item else "-",
                         ",".join(groups) or "-", worker.job.host_epoch,
                         worker.host_session, sequence,
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


def unresolved_unit_rows(unresolved):
    """Each unresolved item and its detail as a row of unresolved-units.tsv."""
    return [(item.platform, item.unit_id, item.replica, detail) for item, detail in unresolved]


def write_unresolved_units(path, unresolved):
    write_unresolved(path, UNRESOLVED_UNIT_FIELDS, unresolved_unit_rows(unresolved))


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


def completion_key(sequence, group):
    return f"units/{sequence}/groups/{group}/completion.json"


def result_key(sequence, group):
    return f"units/{sequence}/groups/{group}/result.tar.gz"


def lease_key(metadata, sequence):
    """A worker's lease's full key in its bucket."""
    return f"{metadata['input_prefix']}/leases/{sequence}.json"


def recovered_keys(objects):
    """The keys of a worker's uploads that its cleanup recovered into objects."""
    return {path.relative_to(objects).as_posix() for path in objects.rglob("*") if path.is_file()}


def list_unit_worker_results(arguments, worker):
    """Each object under a worker's result prefix, by its key below the prefix, with its modification time."""
    prefix = worker.metadata["result_prefix"] + "/"
    objects = arguments.cloud.list(worker.metadata["aws_region"], worker.metadata["bucket"], prefix)
    return {key[len(prefix):]: modified for key, (_, modified) in objects.items()}


def get_unit_worker_result(arguments, worker, key, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    arguments.cloud.get(worker.metadata["aws_region"], worker.metadata["bucket"],
                        f"{worker.metadata['result_prefix']}/{key}", path)


def put_unit_lease(arguments, worker, sequence, document):
    """Write a worker's lease once, returning whether its host holds it: False when the idle host already claimed
    the sequence as its own stop. Any other lease already written, before a restart or a lost response, must hold
    this same document."""
    region, bucket = worker.metadata["aws_region"], worker.metadata["bucket"]
    key = lease_key(worker.metadata, sequence)
    body = json.dumps(document, sort_keys=True) + "\n"
    if arguments.cloud.put(region, bucket, key, body.encode(), if_none_match=True):
        return True
    with tempfile.TemporaryDirectory(prefix="unit-lease-") as temporary:
        existing = Path(temporary) / "existing.json"
        arguments.cloud.get(region, bucket, key, existing)
        text = existing.read_text()
    if text == body:
        return True
    try:
        claim = json.loads(text)
    except ValueError:
        claim = None
    if isinstance(claim, dict) and claim.get("by") == "worker" and claim.get("stop") is True:
        return False
    raise UnsafeCampaignError(f"unit worker {worker.host_session} lease {sequence} differs from its ledger")


def write_leases(arguments, worker, documents):
    """Write a worker's leases, each a (sequence, document), in order with put_unit_lease, until one cannot be written
    or the host holds no more; returns each written sequence with whether the host holds it."""
    written = []
    for sequence, document in documents:
        try:
            held = put_unit_lease(arguments, worker, sequence, document)
        except (CloudError, OSError):
            break
        written.append((sequence, held))
        if not held:
            break
    return written


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
