#!/usr/bin/env python3

import math
from collections import defaultdict
from pathlib import Path

import row_protocol
import work_units


# Units whose rows cannot fit one 15-minute work item, each a single atom measured alone.
ALLOWED_OVERSIZED_UNITS = {}


def check_ownership(units, manifest_rows):
    owners = {}
    for unit in units.values():
        for row_id in unit["owned"]:
            owners.setdefault(row_id, []).append(unit["unit_id"])
    for row in manifest_rows:
        count = len(owners.get(row["row_id"], ()))
        if count != 1:
            raise ValueError(f"row {row['row_id']} is owned by {count} units")
    by_id = {row["row_id"]: row for row in manifest_rows}
    unknown = sorted(set(owners) - set(by_id))
    if unknown:
        raise ValueError(f"units own rows outside the manifest: {unknown[:3]}")
    for unit in units.values():
        for row_id in unit["owned"]:
            if row_id not in by_id:
                raise ValueError(f"unit {unit['unit_id']} holds row {row_id}, which is not in the manifest")
            if by_id[row_id]["shard_id"] != unit["shard_id"]:
                raise ValueError(f"unit {unit['unit_id']} holds row {row_id} from another shard")
    return {row_id: unit_ids[0] for row_id, unit_ids in owners.items()}


def check_together(owner, row_ids, requirement):
    unit_ids = {owner[row_id] for row_id in row_ids}
    if len(unit_ids) != 1:
        raise ValueError(f"{requirement} spans {len(unit_ids)} units: {sorted(row_ids)[0]}")


def check_requirements(units, manifest_rows, owner):
    by_unit = {unit["unit_id"]: set(unit["owned"]) for unit in units.values()}
    for _, points in row_protocol.identify_scaling_groups(manifest_rows).values():
        check_together(owner, [row["row_id"] for row, _ in points], "scaling group")
    for _, candidate, comparator_system, comparators in row_protocol.comparison_specs(manifest_rows):
        held = by_unit[owner[candidate["row_id"]]]
        if any(row["row_id"] not in held for row in comparators):
            raise ValueError(f"{candidate['row_id']} is not measured beside its {comparator_system} comparator")
    logical = defaultdict(list)
    for row in manifest_rows:
        logical[row_protocol.logical_row_id(row)].append(row)
    for rows in logical.values():
        check_together(owner, [row["row_id"] for row in rows], "logical row")


def check_selectors(manifest_rows, protocols, owner):
    """Each unit's share of a method is one Cartesian -p block, and a method is split along at most one axis."""
    groups = work_units.selector_groups(manifest_rows, protocols)
    for (_, system, benchmark, _), rows in groups.items():
        full = defaultdict(set)
        portions = defaultdict(lambda: defaultdict(set))
        counts = defaultdict(int)
        for row in rows:
            unit_id = owner[row["row_id"]]
            counts[unit_id] += 1
            for key, value in row_protocol.parse_parameters(row["parameters"]).items():
                full[key].add(value)
                portions[unit_id][key].add(value)
        split = set()
        for unit_id, values in portions.items():
            if counts[unit_id] != math.prod(len(value_set) for value_set in values.values()):
                raise ValueError(f"{system} {benchmark} is not a Cartesian block in unit {unit_id}")
            # JMH cannot select a value holding a comma, so such a method runs whole.
            if counts[unit_id] != len(rows) and not all(
                    work_units.expressible(value_set) for value_set in values.values()):
                raise ValueError(f"{system} {benchmark} has a value JMH cannot select and is split")
            split.update(key for key, value_set in values.items() if value_set != full[key])
        if len(split) > 1:
            raise ValueError(f"{system} {benchmark} is split along {sorted(split)}")


def check_representatives(units, representatives, protocols, owner):
    expected = {
        (replica_id, row_id)
        for row_id, replica_ids in work_units.representative_bindings(representatives, protocols).items()
        for replica_id in replica_ids}
    attached = set()
    for unit in units.values():
        for replica_id, row_id in unit["representatives"]:
            if owner.get(row_id) != unit["unit_id"]:
                raise ValueError(f"representative {replica_id}:{row_id} is attached outside its row's unit")
            attached.add((replica_id, row_id))
    if attached != expected:
        raise ValueError(f"representatives missing {sorted(expected - attached)[:3]}, "
                         f"unexpected {sorted(attached - expected)[:3]}")


def check_identities(units, protocols):
    indexes = defaultdict(list)
    for unit in units.values():
        identity = work_units.unit_id(
            unit["shard_id"], unit["owned"],
            [f"{replica_id}:{row_id}" for replica_id, row_id in unit["representatives"]], protocols)
        if identity != unit["unit_id"]:
            raise ValueError(f"unit {unit['unit_id']} does not match its identity {identity}")
        indexes[unit["shard_id"]].append(int(unit["unit_index"]))
    for shard_id, values in indexes.items():
        if sorted(values) != list(range(1, len(values) + 1)):
            raise ValueError(f"shard {shard_id} unit indexes are not 1..{len(values)}")


def check_oversized(units):
    oversized = set()
    for unit in units.values():
        seconds = float(unit["estimated_seconds"])
        if unit["oversized"] != str(seconds > work_units.UNIT_SECONDS).lower():
            raise ValueError(f"unit {unit['unit_id']} misreports its oversize")
        if seconds > work_units.UNIT_SECONDS:
            oversized.add(unit["unit_id"])
    allowed = set(ALLOWED_OVERSIZED_UNITS)
    if oversized != allowed:
        raise ValueError(f"oversized units not allowed: {sorted(oversized - allowed)}; "
                         f"allowed units no longer oversized: {sorted(allowed - oversized)}")
    return oversized


def check(units, manifest_rows, representatives, protocols):
    owner = check_ownership(units, manifest_rows)
    check_requirements(units, manifest_rows, owner)
    check_selectors(manifest_rows, protocols, owner)
    check_representatives(units, representatives, protocols, owner)
    check_identities(units, protocols)
    return check_oversized(units)


def validate(directory):
    """Check the generated units against the measurement requirements they must meet."""
    units = work_units.load(directory)
    oversized = check(
        units,
        row_protocol.read_tsv(directory / "rows.tsv"),
        row_protocol.read_tsv(directory / "protocol-representatives.tsv"),
        row_protocol.load(directory / "row-protocol.tsv"))
    return len(units), len(oversized)


def main():
    try:
        unit_count, oversized_count = validate(Path(__file__).resolve().parent)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    print(f"validated {unit_count} work units, {oversized_count} allowed oversized")


if __name__ == "__main__":
    main()
