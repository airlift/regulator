"""The campaign controller's unit ledger and the layout of its accepted attempts.

A unit campaign's controller retains each accepted attempt under
units/<platform>/<unit>/replica-<n>/<host session>/lease-<n> and records it in accepted-units.tsv. Recovery
finds retained attempts by the same layout.
"""

import csv

import unit_session
from campaign_io import UnsafeCampaignError, write_ledger


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
    """Append one unit attempt's receipt to accepted-units.tsv atomically, as accepted sessions are."""
    receipt = read_unit_receipt(receipt_path)
    rows = read_accepted_units(path)
    if accepted_row(rows, receipt, unit_logical_identity, "unit_attempt_id", "attempts") is not None:
        return receipt
    if any(row["unit_attempt_id"] == receipt["unit_attempt_id"] for row in rows):
        raise UnsafeCampaignError(f"unit attempt {receipt['unit_attempt_id']} is accepted for another unit")
    write_ledger(path, [*rows, receipt], unit_session.UNIT_RECEIPT_FIELDS, lambda row: (
        row["platform"], row["shard_id"], row["unit_id"], int(row["replica_id"])))
    return receipt
