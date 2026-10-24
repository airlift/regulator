#!/usr/bin/env python3

import math
from collections import defaultdict
from pathlib import Path

from aggregate_results import comparison_specs, identify_scaling_groups, logical_row_id, parse_parameters

import row_protocol
import work_units


# Units whose rows cannot fit one 15-minute work item, each a single atom measured alone.
ALLOWED_OVERSIZED_UNITS = {
    # The retained-memory shard is measured whole: three 900-second censuses.
    "839119c43db2d9af56ab68dd7db751085b80435135f6f9d75487c964be4bfd2e": "retained-memory 1",
    # One text size of the shared-cold wave: both routes over five worker counts at one-second windows.
    "64c6a4f74addf6314ad0de161fae687fb1f3f3bc6029366c5478dadda19c5cd8": "lifecycle-shared-cold 1",
    "8469e95d321d12bb3ed24283da136d2a0afca6d48579510655712a147567c3c0": "lifecycle-shared-cold 2",
    # One route's eight-size scaling group of one-second windows with its native bracket.
    "8e65ead52586c283d4c8b7027870c05cc909eec367033f4a3bd788604a66ecb5": "traditional-extra-alt-engines 1",
    "e7590e8d16cf04fcaa57220e4b62299ccde76385779c7be5c4c8806674758aef": "traditional-extra-alt-engines 2",
    "b78ebcff48400c4e9dd43b0452003a8873dcacf8363d293a585ba8c890b02165": "traditional-extra-alt-match 1",
    "af6b4a8ec412a694c540579f96ba35f5e95be2ef9c577609ffc594e652c965cf": "traditional-extra-alt-match 2",
    "2e269a16189287bff4f131d84696c2284fcb697f8995712a1373206783219ade": "traditional-extra-alt-match 3",
    "60aaa7a393b817a6531af296aed673aeff808e8c97614d4ddeb6e5a6abb14704": "traditional-extra-alt-match 4",
    "2feac5f3620b2157db5f5e3740a2129349217282e7bcab495f51b22c476ef50e": "traditional-extra-easy2 1",
    "9f53d2e9ee4c1fb7a21c72372bbd74b3fa8c745ff24951443e32e89787c1c185": "traditional-extra-easy2 2",
    "b5a4104840c61fa9e16eb9f3ea33a68941477ede0bd3ebe88c36366e3d0ec654": "traditional-extra-easy2 3",
    "5725ce564c8dc95bdf93e80eb6546503ea981987b0915da0bdf5980efeb5f655": "traditional-extra-easy2 4",
    "74430674636585c2b75d7fb4342b31ab71857360624acce6191714ac5617b9ce": "traditional-extra-fanout 1",
    "26dbc1e4abb565f1bfa8d3ca0ea8f04d964c2928ed369079fc98d49c7251ea09": "traditional-extra-fanout 2",
    "0cbcd8536823080c34f841540eb909f96094a44e77d3ab27a319f2893dd0cf80": "traditional-extra-fanout 3",
    "2da3b89eced21cf653d240267b6e195b1b91d3844360f2426b661a7c40861e2b": "traditional-extra-fanout 4",
    "702c2bd9c9c9dc1d084204730e88e2db6913d11fb3a592afcbba83f075e75ea7": "traditional-extra-success 1",
    "70ee28c63e4c27818801aeb8e35a20123a5fbb07da4e13f91358c3731482ce31": "traditional-extra-success 2",
    "0bcdcdc0a380b881f217eafb531203e603411738647ce2f673a0372c4d791199": "traditional-extra-success 3",
    "751d7535c53f32186648882e06736e461d6008b0dcdd8a50df53d839bdf70564": "traditional-extra-success 4",
    "e673a9561fa9034bc72c3f95d57c935f2bc8e5d78b7ba7bac6943dad1f5a1491": "traditional-extra-success1 1",
    "4ebc0bff51efa127d164317b9191605294f1c86cd808446a62409835dd4b7893": "traditional-extra-success1 2",
    "7e5289887a232042cab82cc5ad3ba17371b5ce7b38a61082b99b1eccd900e545": "traditional-extra-success1 3",
    "24065faaab7a93d171a6a07885bdbf48f0aff13861f6f4bff5982f00b6499c63": "traditional-extra-success1 4",
}


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
        for row_id in unit["owned"] + unit["copies"]:
            if row_id not in by_id:
                raise ValueError(f"unit {unit['unit_id']} holds row {row_id}, which is not in the manifest")
            if by_id[row_id]["shard_id"] != unit["shard_id"]:
                raise ValueError(f"unit {unit['unit_id']} holds row {row_id} from another shard")
        for row_id in unit["copies"]:
            if by_id[row_id]["system"] not in work_units.NATIVE_COMPARATOR_SYSTEMS:
                raise ValueError(f"unit {unit['unit_id']} copies {row_id}, which is not a native bracket row")
            if owners[row_id] == [unit["unit_id"]]:
                raise ValueError(f"unit {unit['unit_id']} both owns and copies {row_id}")
    return {row_id: unit_ids[0] for row_id, unit_ids in owners.items()}


def check_together(owner, row_ids, requirement):
    unit_ids = {owner[row_id] for row_id in row_ids}
    if len(unit_ids) != 1:
        raise ValueError(f"{requirement} spans {len(unit_ids)} units: {sorted(row_ids)[0]}")


def check_requirements(units, manifest_rows, owner):
    by_unit = {unit["unit_id"]: set(unit["owned"]) | set(unit["copies"]) for unit in units.values()}
    for _, points in identify_scaling_groups(manifest_rows).values():
        check_together(owner, [row["row_id"] for row, _ in points], "scaling group")
    for _, candidate, comparator_system, comparators in comparison_specs(manifest_rows):
        held = by_unit[owner[candidate["row_id"]]]
        if any(row["row_id"] not in held for row in comparators):
            raise ValueError(f"{candidate['row_id']} is not measured beside its {comparator_system} comparator")
    logical = defaultdict(list)
    for row in manifest_rows:
        logical[logical_row_id(row)].append(row)
        if row["suite"] in work_units.WHOLE_SHARD_SUITES:
            logical[("whole shard", row["shard_id"])].append(row)
    for rows in logical.values():
        if not work_units.splittable(rows):
            check_together(owner, [row["row_id"] for row in rows], "logical row")
            continue
        measured = [row["row_id"] for row in rows if row["system"] not in work_units.NATIVE_COMPARATOR_SYSTEMS]
        native = [row["row_id"] for row in rows if row["system"] in work_units.NATIVE_COMPARATOR_SYSTEMS]
        # A logical row split by route carries its whole native bracket into each part.
        for row_id in measured:
            if not by_unit[owner[row_id]] >= set(native):
                raise ValueError(f"{row_id} is split from its logical row without its native bracket")


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
            for key, value in parse_parameters(row["parameters"]).items():
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
            unit["shard_id"], unit["owned"], unit["copies"],
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
    unit_table, unit_rows = work_units.render(work_units.generate_from(directory))
    for name, expected in (("units.tsv", unit_table), ("unit-rows.tsv", unit_rows)):
        if (directory / name).read_text(encoding="utf-8") != expected:
            raise ValueError(f"{name} does not match the unit rules; run generate-units.py")
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
