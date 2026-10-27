"""Deterministic partition of every shard's rows into self-contained work units.

A unit is the rows one host measures between two chained calibrations. Atoms
are the row sets a comparison or a scaling fit needs measured together; atoms
are packed greedily, largest first, adding a unit until every unit holding
several atoms fits the unit budget. run-unit.sh measures one unit. The
generator checks every unit against the measurement requirements before it
writes the tables.

The tables are not checked in: freeze-candidate.sh generates them into the
candidate archive and beside these modules, where hosts and the controller read
them, and the offline gate generates them before its tests.
"""
import csv
import hashlib
import io
import json
import math
import statistics
from collections import defaultdict
from dataclasses import dataclass

import row_protocol
import unit_cost


UNIT_FIELDS = (
    "unit_id",
    "shard_id",
    "unit_index",
    "owned_rows",
    "representatives",
    "estimated_seconds",
)
UNIT_ROW_FIELDS = ("unit_id", "row_id", "role", "replica_id")
# The generated tables, written together by generate-units.py.
TABLES = ("row-protocol.tsv", "units.tsv", "unit-rows.tsv")
ROLES = ("owner", "representative")
# A unit is a 15-minute work item. Chained units share their boundary calibrations, so each unit is
# charged one calibration invocation.
UNIT_SECONDS = 900
UNIT_CALIBRATION_INVOCATIONS = 1
UNIT_BUDGET_SECONDS = UNIT_SECONDS - unit_cost.calibration_seconds(UNIT_CALIBRATION_INVOCATIONS)
# A unit sequences its rows as its route handler sequences a route, comparator order included.
ORDER_RULE = "route-handler-order-v1"
REPRESENTATIVE_REPLICA_IDS = ("1", "2", "3")


class Partition:
    def __init__(self, row_ids):
        self.parent = {row_id: row_id for row_id in row_ids}

    def find(self, row_id):
        while self.parent[row_id] != row_id:
            self.parent[row_id] = self.parent[self.parent[row_id]]
            row_id = self.parent[row_id]
        return row_id

    def join(self, row_ids):
        row_ids = list(row_ids)
        for row_id in row_ids[1:]:
            first, other = self.find(row_ids[0]), self.find(row_id)
            if first != other:
                self.parent[max(first, other)] = min(first, other)

    def groups(self):
        result = defaultdict(list)
        for row_id in self.parent:
            result[self.find(row_id)].append(row_id)
        return sorted((sorted(rows) for rows in result.values()), key=lambda rows: rows[0])


def selector_groups(rows, protocols):
    """JMH rows grouped by shard, system, method, and protocol.

    The unit planner merges methods that share one Cartesian -p block into one
    selector. Grouping here stays per method, as unit_cost.selectors does, a
    deliberately conservative allowance.
    """
    groups = defaultdict(list)
    for row in rows:
        protocol = protocols[row["row_id"]]
        key = (row["shard_id"], row["system"], row["benchmark"], row_protocol.protocol_key(protocol))
        groups[key].append(row)
    return groups


def scaling_axes(scaling_groups):
    """The size parameters each (shard, system, method) fits a scaling curve over."""
    result = defaultdict(set)
    for (shard_id, _, system, benchmark, key, _), _ in scaling_groups.values():
        result[(shard_id, system, benchmark)].add(key)
    return result


def expressible(values):
    """JMH splits a -p value list at every comma, so a value holding one cannot be selected alone."""
    return not any("," in value for value in values)


def split_axis(rows, excluded):
    """The one parameter a selector may be split along: the most varied expressible non-scaling axis.

    A method with any value JMH cannot select runs whole, so it is never split.
    """
    values = defaultdict(set)
    for row in rows:
        for key, value in row_protocol.parse_parameters(row["parameters"]).items():
            values[key].add(value)
    if not all(expressible(parameter_values) for parameter_values in values.values()):
        return None
    candidates = [key for key in values if key not in excluded and len(values[key]) > 1]
    if not candidates:
        return None
    return min(candidates, key=lambda key: (-len(values[key]), key))


def join_shared_requirements(partition, rows, protocols, scaling_groups, axes):
    """Join rows that must share a unit: each logical row's systems, scaling groups, and selector slices."""
    logical = defaultdict(list)
    for row in rows:
        logical[row_protocol.logical_row_id(row)].append(row["row_id"])
    for logical_ids in logical.values():
        partition.join(logical_ids)
    for _, points in scaling_groups.values():
        partition.join(row["row_id"] for row, _ in points)
    for (shard_id, system, benchmark, _), selector_rows in selector_groups(rows, protocols).items():
        axis = split_axis(selector_rows, axes[(shard_id, system, benchmark)])
        slices = defaultdict(list)
        for row in selector_rows:
            slices[row_protocol.parse_parameters(row["parameters"])[axis] if axis else None].append(row["row_id"])
        for slice_ids in slices.values():
            partition.join(slice_ids)


@dataclass
class Atom:
    owned: list

    def __post_init__(self):
        self.owned = sorted(self.owned)


def representative_bindings(representatives, protocols):
    """Representatives that run: those whose row's frozen windows differ from the reference."""
    result = defaultdict(set)
    for representative in representatives:
        row_id = representative["manifest_row_id"]
        if unit_cost.compares_with_reference(protocols[row_id]):
            result[row_id].add(representative["replica_id"])
    return result


def representative_count(row_ids, bindings):
    return max(
        (sum(replica_id in bindings.get(row_id, ()) for row_id in row_ids)
         for replica_id in REPRESENTATIVE_REPLICA_IDS),
        default=0)


def estimate(atom, by_id, protocols, bindings, calibration_invocations=0):
    """Seconds to measure an atom's or unit's rows and their representatives."""
    return unit_cost.estimate(
        [by_id[row_id] for row_id in atom.owned], protocols,
        representative_count=representative_count(atom.owned, bindings),
        calibration_invocations=calibration_invocations)


def shard_atoms(rows, protocols, scaling_groups):
    partition = Partition(row["row_id"] for row in rows)
    join_shared_requirements(partition, rows, protocols, scaling_groups, scaling_axes(scaling_groups))
    return [Atom(group) for group in partition.groups()]


def merge(atoms):
    return Atom([row_id for atom in atoms for row_id in atom.owned])


def pack(atoms, by_id, protocols, bindings):
    """Largest atom first into the lightest unit, adding a unit until only lone atoms exceed the budget.

    Packing is greedy, so it can use more units than an optimal packing would. `check` refuses any unit
    over the 15-minute budget, a lone atom included.
    """
    costs = {id(atom): estimate(atom, by_id, protocols, bindings) for atom in atoms}
    ordered = sorted(atoms, key=lambda atom: (-costs[id(atom)], atom.owned[0]))
    count = max(1, math.ceil(estimate(merge(atoms), by_id, protocols, bindings) / UNIT_BUDGET_SECONDS))
    while True:
        bins = [[] for _ in range(count)]
        seconds = [0.0] * count
        for atom in ordered:
            index = min(range(count), key=lambda candidate: (seconds[candidate], candidate))
            bins[index].append(atom)
            seconds[index] = estimate(merge(bins[index]), by_id, protocols, bindings)
        if all(seconds[index] <= UNIT_BUDGET_SECONDS or len(bins[index]) == 1 for index in range(count)):
            return sorted((merge(atom_bin) for atom_bin in bins if atom_bin), key=lambda unit: unit.owned[0])
        count += 1


def protocol_digest(row_ids, protocols):
    lines = ["\t".join(protocols[row_id][field] for field in row_protocol.FIELDS) for row_id in sorted(row_ids)]
    return hashlib.sha256("\n".join(lines).encode()).hexdigest()


def unit_id(shard_id, owned, representatives, protocols):
    identity = {
        "shard_id": shard_id,
        "rows": sorted(owned),
        "row_protocol": protocol_digest(owned, protocols),
        "representatives": sorted(representatives),
        "order": ORDER_RULE,
    }
    return hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def generate(manifest_rows, representatives, protocols):
    scaling_groups = row_protocol.identify_scaling_groups(manifest_rows)
    bindings = representative_bindings(representatives, protocols)
    by_id = {row["row_id"]: row for row in manifest_rows}
    shard_rows = defaultdict(list)
    for row in manifest_rows:
        shard_rows[row["shard_id"]].append(row)
    units = []
    for shard_id, rows in shard_rows.items():
        shard_scaling = {group_id: group for group_id, group in scaling_groups.items() if group[0][0] == shard_id}
        atoms = shard_atoms(rows, protocols, shard_scaling)
        for index, unit in enumerate(pack(atoms, by_id, protocols, bindings), 1):
            unit_representatives = sorted(
                (replica_id, row_id) for row_id in unit.owned for replica_id in bindings.get(row_id, ()))
            seconds = estimate(unit, by_id, protocols, bindings, UNIT_CALIBRATION_INVOCATIONS)
            units.append({
                "unit_id": unit_id(
                    shard_id, unit.owned,
                    [f"{replica_id}:{row_id}" for replica_id, row_id in unit_representatives], protocols),
                "shard_id": shard_id,
                "unit_index": index,
                "owned": unit.owned,
                "representatives": unit_representatives,
                "estimated_seconds": seconds,
            })
    check(units, manifest_rows, protocols)
    return units


def check_ownership(units, manifest_rows):
    """Each manifest row is owned by exactly one unit of its shard; returns each row's unit."""
    owners = defaultdict(list)
    for unit in units:
        for row_id in unit["owned"]:
            owners[row_id].append(unit)
    by_id = {row["row_id"]: row for row in manifest_rows}
    unknown = sorted(set(owners) - set(by_id))
    if unknown:
        raise ValueError(f"units own rows outside the manifest: {unknown[:3]}")
    for row in manifest_rows:
        holders = owners.get(row["row_id"], [])
        if len(holders) != 1:
            raise ValueError(f"row {row['row_id']} is owned by {len(holders)} units")
        if holders[0]["shard_id"] != row["shard_id"]:
            raise ValueError(f"unit {holders[0]['unit_id']} holds row {row['row_id']} from another shard")
    return {row_id: holders[0]["unit_id"] for row_id, holders in owners.items()}


def check_together(owner, row_ids, requirement):
    unit_ids = {owner[row_id] for row_id in row_ids}
    if len(unit_ids) != 1:
        raise ValueError(f"{requirement} spans {len(unit_ids)} units: {sorted(row_ids)[0]}")


def check_requirements(manifest_rows, owner):
    """Comparators, scaling groups, and each logical row's systems are measured in one unit."""
    for _, points in row_protocol.identify_scaling_groups(manifest_rows).values():
        check_together(owner, [row["row_id"] for row, _ in points], "scaling group")
    for _, candidate, comparator_system, comparators in row_protocol.comparison_specs(manifest_rows):
        if any(owner[row["row_id"]] != owner[candidate["row_id"]] for row in comparators):
            raise ValueError(f"{candidate['row_id']} is not measured beside its {comparator_system} comparator")
    logical = defaultdict(list)
    for row in manifest_rows:
        logical[row_protocol.logical_row_id(row)].append(row["row_id"])
    for row_ids in logical.values():
        check_together(owner, row_ids, "logical row")


def check_selectors(manifest_rows, protocols, owner):
    """Each unit's share of a method is one Cartesian -p block, and a method is split along at most one axis."""
    for (_, system, benchmark, _), rows in selector_groups(manifest_rows, protocols).items():
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
            if counts[unit_id] != len(rows) and not all(expressible(value_set) for value_set in values.values()):
                raise ValueError(f"{system} {benchmark} has a value JMH cannot select and is split")
            split.update(key for key, value_set in values.items() if value_set != full[key])
        if len(split) > 1:
            raise ValueError(f"{system} {benchmark} is split along {sorted(split)}")


def check(units, manifest_rows, protocols):
    """The measurement requirements every unit meets, within the 15-minute budget."""
    owner = check_ownership(units, manifest_rows)
    check_requirements(manifest_rows, owner)
    check_selectors(manifest_rows, protocols, owner)
    for unit in units:
        if unit["estimated_seconds"] > UNIT_SECONDS:
            raise ValueError(f"unit {unit['unit_id']} exceeds {UNIT_SECONDS} seconds")


def generate_from(directory):
    return generate(
        row_protocol.read_tsv(directory / "rows.tsv"),
        row_protocol.read_tsv(directory / "protocol-representatives.tsv"),
        {protocol["row_id"]: protocol for protocol in row_protocol.generate_from(directory)})


def render_table(fields, rows):
    output = io.StringIO()
    writer = csv.DictWriter(output, fieldnames=fields, delimiter="\t", lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return output.getvalue()


def render(units):
    """units.tsv and unit-rows.tsv; one row ID contains a comma, so row lists live in the mapping file."""
    unit_rows = [{
        "unit_id": unit["unit_id"],
        "shard_id": unit["shard_id"],
        "unit_index": unit["unit_index"],
        "owned_rows": len(unit["owned"]),
        "representatives": len(unit["representatives"]),
        "estimated_seconds": f"{unit['estimated_seconds']:.1f}",
    } for unit in units]
    mapping = []
    for unit in units:
        mapping.extend({"unit_id": unit["unit_id"], "row_id": row_id, "role": "owner", "replica_id": "-"}
                       for row_id in unit["owned"])
        mapping.extend({"unit_id": unit["unit_id"], "row_id": row_id, "role": "representative",
                        "replica_id": replica_id}
                       for replica_id, row_id in unit["representatives"])
    return render_table(UNIT_FIELDS, unit_rows), render_table(UNIT_ROW_FIELDS, mapping)


def load(directory):
    """Units with their owned rows and representatives, read from the generated tables."""
    units = {}
    for row in row_protocol.read_tsv(directory / "units.tsv"):
        if row["unit_id"] in units:
            raise ValueError(f"duplicate unit {row['unit_id']}")
        units[row["unit_id"]] = {**row, "owned": [], "representatives": []}
    for row in row_protocol.read_tsv(directory / "unit-rows.tsv"):
        if row["unit_id"] not in units or row["role"] not in ROLES:
            raise ValueError(f"invalid unit row {row}")
        unit = units[row["unit_id"]]
        if row["role"] == "representative":
            unit["representatives"].append((row["replica_id"], row["row_id"]))
        else:
            unit["owned"].append(row["row_id"])
    return units


def summary(units):
    """Unit count and estimated minutes per shard."""
    by_shard = defaultdict(list)
    for unit in units:
        by_shard[unit["shard_id"]].append(unit["estimated_seconds"] / 60)
    lines = ["shard\tunits\tmin\tmedian\tmax\ttotal minutes"]
    for shard_id, minutes in by_shard.items():
        lines.append("\t".join([
            shard_id, str(len(minutes)), f"{min(minutes):.1f}", f"{statistics.median(minutes):.1f}",
            f"{max(minutes):.1f}", f"{sum(minutes):.1f}"]))
    return "\n".join(lines)
