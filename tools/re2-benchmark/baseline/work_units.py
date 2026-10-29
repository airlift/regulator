"""Deterministic partition of every shard's rows into self-contained work units.

A unit is the rows one host measures as one work item. Atoms are the row sets a
comparison or a scaling fit needs measured together; atoms are packed greedily,
largest first, adding a unit until every unit holding several atoms fits the
unit budget.

A unit runs as a sequence of measured groups, each a self-contained run-unit.sh
call whose result the host uploads as soon as it finishes, so an interrupted
unit keeps its finished groups. A group joins atoms that share a JMH launch, and
adjacent groups merge until each fills GROUP_SECONDS. The generator checks every
unit and group against the measurement requirements before it writes the tables.

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
    "group_seconds",
)
UNIT_ROW_FIELDS = ("unit_id", "row_id", "role", "replica_id", "group_id")
# The generated tables, written together by generate-units.py.
TABLES = ("row-protocol.tsv", "units.tsv", "unit-rows.tsv")
ROLES = ("owner", "representative")
# A unit is a 15-minute work item.
UNIT_SECONDS = 900
# A measured group is at least five minutes, unless its unit is smaller. Each group repeats its routes'
# correctness stage, which unit_cost does not charge, so smaller groups would spend a growing share of
# the unit on work no estimate accounts for.
GROUP_SECONDS = 300
# A unit runs its groups in order, odd replicas forward and even replicas mirrored, and sequences each
# group's rows as its route handler sequences a route, comparator order included.
ORDER_RULE = "group-route-handler-order-v2"
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


def launch_key(row, protocols):
    """A row's JMH method: its system, benchmark, and protocol, and the row itself when its suite runs
    each workload alone."""
    alone = row["row_id"] if unit_cost.runs_each_workload(row) else None
    return row["system"], row_protocol.jmh_benchmark(row), row_protocol.protocol_key(protocols[row["row_id"]]), alone


def parameter_block(rows):
    """The Cartesian -p block selecting exactly these rows, or None when JMH cannot express a value."""
    parsed = [row_protocol.parse_parameters(row["parameters"]) for row in rows]
    names = sorted(parsed[0])
    if any(sorted(parameters) != names for parameters in parsed):
        raise ValueError(f"Rows of {rows[0]['benchmark']} do not share parameter names")
    block = {name: list(dict.fromkeys(parameters[name] for parameters in parsed)) for name in names}
    if len({tuple(parameters[name] for name in names) for parameters in parsed}) != len(rows) or \
            len(rows) != math.prod(len(values) for values in block.values()):
        raise ValueError(f"Rows of {rows[0]['benchmark']} are not one Cartesian block")
    if not all(expressible(values) for values in block.values()):
        return None
    return block


def launches(rows, protocols):
    """Rows' JMH launches in row order, each as (system, protocol key, -p block, method, alone) and its
    methods' (launch key, rows).

    Methods sharing one -p block share a JVM. A method whose values JMH cannot select has no block and
    runs whole in its own.
    """
    methods = {}
    for row in rows:
        methods.setdefault(launch_key(row, protocols), []).append(row)
    result = {}
    for key, method_rows in methods.items():
        system, benchmark, protocol_key, alone = key
        block = parameter_block(method_rows)
        parameters = None if block is None else json.dumps(block, ensure_ascii=False, separators=(",", ":"))
        merge_key = (system, protocol_key, parameters, benchmark if block is None else None, alone)
        result.setdefault(merge_key, []).append((key, method_rows))
    return list(result.items())


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


def estimate(atom, by_id, protocols, bindings):
    """Seconds to measure an atom's or unit's rows and their representatives."""
    return unit_cost.estimate(
        [by_id[row_id] for row_id in atom.owned], protocols,
        representative_count=representative_count(atom.owned, bindings))


def shard_atoms(rows, protocols, scaling_groups):
    partition = Partition(row["row_id"] for row in rows)
    join_shared_requirements(partition, rows, protocols, scaling_groups, scaling_axes(scaling_groups))
    return [Atom(group) for group in partition.groups()]


def merge(atoms):
    return Atom([row_id for atom in atoms for row_id in atom.owned])


def measured_groups(unit, atoms, by_id, protocols, bindings):
    """A unit's measured groups, in run order by first row: its atoms joined by shared JMH launches, then
    adjacent groups merged until each is at least GROUP_SECONDS, unless the unit is smaller."""
    partition = Partition(unit.owned)
    for atom in atoms:
        partition.join(atom.owned)
    for _, methods in launches([by_id[row_id] for row_id in unit.owned], protocols):
        partition.join(row["row_id"] for _, method_rows in methods for row in method_rows)
    groups = []
    pending = []
    for rows in partition.groups():
        pending.extend(rows)
        if estimate(Atom(pending), by_id, protocols, bindings) >= GROUP_SECONDS:
            groups.append(Atom(pending))
            pending = []
    if pending:
        if groups:
            groups[-1] = merge([groups[-1], Atom(pending)])
        else:
            groups.append(Atom(pending))
    return groups


def pack(atoms, cost, budget=UNIT_SECONDS):
    """Largest atom first into the lightest unit, adding a unit until only lone atoms exceed the budget.

    cost gives the seconds to measure an atom or a merged unit. Packing is greedy, so it can use more
    units than an optimal packing would. Each unit returned keeps the count of atoms it holds.
    """
    costs = {id(atom): cost(atom) for atom in atoms}
    ordered = sorted(atoms, key=lambda atom: (-costs[id(atom)], atom.owned[0]))
    count = max(1, math.ceil(cost(merge(atoms)) / budget))
    while True:
        bins = [[] for _ in range(count)]
        seconds = [0.0] * count
        for atom in ordered:
            index = min(range(count), key=lambda candidate: (seconds[candidate], candidate))
            bins[index].append(atom)
            seconds[index] = cost(merge(bins[index]))
        if all(seconds[index] <= budget or len(bins[index]) == 1 for index in range(count)):
            return sorted(((merge(atom_bin), len(atom_bin)) for atom_bin in bins if atom_bin),
                          key=lambda packed: packed[0].owned[0])
        count += 1


def protocol_digest(row_ids, protocols):
    lines = ["\t".join(protocols[row_id][field] for field in row_protocol.FIELDS) for row_id in sorted(row_ids)]
    return hashlib.sha256("\n".join(lines).encode()).hexdigest()


def unit_id(shard_id, owned, representatives, row_protocol_sha256, order=ORDER_RULE):
    """A unit's identity: its shard, rows and representatives, the digest of its rows' protocols, and
    the order its rows run in."""
    identity = {
        "shard_id": shard_id,
        "rows": sorted(owned),
        "row_protocol": row_protocol_sha256,
        "representatives": sorted(representatives),
        "order": order,
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
        packed = pack(atoms, lambda atom: estimate(atom, by_id, protocols, bindings))
        for index, (unit, _) in enumerate(packed, 1):
            unit_representatives = sorted(
                (replica_id, row_id) for row_id in unit.owned for replica_id in bindings.get(row_id, ()))
            seconds = estimate(unit, by_id, protocols, bindings)
            held = set(unit.owned)
            groups = measured_groups(unit, [atom for atom in atoms if atom.owned[0] in held], by_id, protocols,
                                     bindings)
            units.append({
                "unit_id": unit_id(
                    shard_id, unit.owned,
                    [f"{replica_id}:{row_id}" for replica_id, row_id in unit_representatives],
                    protocol_digest(unit.owned, protocols)),
                "shard_id": shard_id,
                "unit_index": index,
                "owned": unit.owned,
                "representatives": unit_representatives,
                "estimated_seconds": seconds,
                "groups": [measured_group(group_id, group.owned, unit_representatives,
                                         estimate(group, by_id, protocols, bindings))
                           for group_id, group in enumerate(groups, 1)],
            })
    check(units, manifest_rows, protocols)
    return units


def measured_group(group_id, owned, unit_representatives, seconds):
    """A group's entry: its rows, the representatives among them, and its estimate."""
    held = set(owned)
    return {
        "group_id": str(group_id),
        "owned": sorted(owned),
        "representatives": [(replica_id, row_id) for replica_id, row_id in unit_representatives if row_id in held],
        "estimated_seconds": seconds,
    }


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


def check_together(owner, row_ids, requirement):
    groups = {owner[row_id] for row_id in row_ids}
    if len(groups) != 1:
        raise ValueError(f"{requirement} spans {len(groups)} groups: {sorted(row_ids)[0]}")


def check_requirements(manifest_rows, owner):
    """Comparators, scaling groups, and each logical row's systems are measured in one group."""
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
    """Each group's share of a method is one Cartesian -p block, and a method is split along at most one axis."""
    for (_, system, benchmark, _), rows in selector_groups(manifest_rows, protocols).items():
        full = defaultdict(set)
        portions = defaultdict(lambda: defaultdict(set))
        counts = defaultdict(int)
        for row in rows:
            group = owner[row["row_id"]]
            counts[group] += 1
            for key, value in row_protocol.parse_parameters(row["parameters"]).items():
                full[key].add(value)
                portions[group][key].add(value)
        split = set()
        for group, values in portions.items():
            if counts[group] != math.prod(len(value_set) for value_set in values.values()):
                raise ValueError(f"{system} {benchmark} is not a Cartesian block in group {group}")
            # JMH cannot select a value holding a comma, so such a method runs whole.
            if counts[group] != len(rows) and not all(expressible(value_set) for value_set in values.values()):
                raise ValueError(f"{system} {benchmark} has a value JMH cannot select and is split")
            split.update(key for key, value_set in values.items() if value_set != full[key])
        if len(split) > 1:
            raise ValueError(f"{system} {benchmark} is split along {sorted(split)}")


def check_groups(units, manifest_rows, protocols):
    """Each unit's groups partition its rows, each meets the requirements and holds its JMH launches whole,
    and each fills GROUP_SECONDS unless it is its unit's only group. A group is named <unit>/<group>."""
    owner = {}
    for unit in units:
        if sorted(row_id for group in unit["groups"] for row_id in group["owned"]) != sorted(unit["owned"]):
            raise ValueError(f"unit {unit['unit_id']} groups do not partition its rows")
        for group in unit["groups"]:
            owner.update((row_id, f"{unit['unit_id']}/{group['group_id']}") for row_id in group["owned"])
            if group["estimated_seconds"] < GROUP_SECONDS and len(unit["groups"]) > 1:
                raise ValueError(f"unit {unit['unit_id']} group {group['group_id']} is under {GROUP_SECONDS} seconds")
    check_requirements(manifest_rows, owner)
    check_selectors(manifest_rows, protocols, owner)
    by_id = {row["row_id"]: row for row in manifest_rows}
    for unit in units:
        for _, methods in launches([by_id[row_id] for row_id in unit["owned"]], protocols):
            check_together(owner, [row["row_id"] for _, method_rows in methods for row in method_rows], "JMH launch")


def check(units, manifest_rows, protocols):
    """The measurement requirements every unit and group meets, within the 15-minute budget.

    A unit's groups partition its rows, so the requirements its groups meet hold for the unit as well.
    """
    check_ownership(units, manifest_rows)
    check_groups(units, manifest_rows, protocols)
    check_budget(units)


def check_budget(units, allow_lone_oversize=False):
    """Every unit fits the 15-minute budget; with allow_lone_oversize, a unit of one atom may exceed it."""
    for unit in units:
        if unit["estimated_seconds"] > UNIT_SECONDS and not (allow_lone_oversize and unit["atoms"] == 1):
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
    """units.tsv and unit-rows.tsv; one row ID contains a comma, so row lists live in the mapping file.

    units.tsv lists each unit's group estimates in group order; the mapping names each row's group.
    """
    unit_rows = [{
        "unit_id": unit["unit_id"],
        "shard_id": unit["shard_id"],
        "unit_index": unit["unit_index"],
        "owned_rows": len(unit["owned"]),
        "representatives": len(unit["representatives"]),
        "estimated_seconds": f"{unit['estimated_seconds']:.1f}",
        "group_seconds": ",".join(f"{group['estimated_seconds']:.1f}" for group in unit["groups"]),
    } for unit in units]
    mapping = []
    for unit in units:
        for group in unit["groups"]:
            mapping.extend({"unit_id": unit["unit_id"], "row_id": row_id, "role": "owner", "replica_id": "-",
                            "group_id": group["group_id"]}
                           for row_id in group["owned"])
            mapping.extend({"unit_id": unit["unit_id"], "row_id": row_id, "role": "representative",
                            "replica_id": replica_id, "group_id": group["group_id"]}
                           for replica_id, row_id in group["representatives"])
    return render_table(UNIT_FIELDS, unit_rows), render_table(UNIT_ROW_FIELDS, mapping)


def load(directory):
    """Units with their owned rows, representatives, and measured groups, read from the generated tables."""
    units = {}
    for row in row_protocol.read_tsv(directory / "units.tsv"):
        if row["unit_id"] in units:
            raise ValueError(f"duplicate unit {row['unit_id']}")
        units[row["unit_id"]] = {**row, "owned": [], "representatives": [], "groups": [
            {"group_id": str(index), "owned": [], "representatives": [], "estimated_seconds": float(seconds)}
            for index, seconds in enumerate(row["group_seconds"].split(","), 1)]}
    for row in row_protocol.read_tsv(directory / "unit-rows.tsv"):
        if row["unit_id"] not in units or row["role"] not in ROLES:
            raise ValueError(f"invalid unit row {row}")
        unit = units[row["unit_id"]]
        group = unit_group(unit, row["group_id"])
        if row["role"] == "representative":
            unit["representatives"].append((row["replica_id"], row["row_id"]))
            group["representatives"].append((row["replica_id"], row["row_id"]))
        else:
            unit["owned"].append(row["row_id"])
            group["owned"].append(row["row_id"])
    for unit in units.values():
        if not all(group["owned"] for group in unit["groups"]):
            raise ValueError(f"unit {unit['unit_id']} has a group without rows")
    return units


def unit_group(unit, group_id):
    """A unit's measured group by its ID."""
    for group in unit["groups"]:
        if group["group_id"] == str(group_id):
            return group
    raise ValueError(f"unit {unit['unit_id']} has no group {group_id!r}")


def summary(units):
    """Unit count, estimated minutes, and measured group count per shard."""
    by_shard = defaultdict(list)
    groups = defaultdict(int)
    for unit in units:
        by_shard[unit["shard_id"]].append(unit["estimated_seconds"] / 60)
        groups[unit["shard_id"]] += len(unit["groups"])
    lines = ["shard\tunits\tmin\tmedian\tmax\ttotal minutes\tgroups"]
    for shard_id, minutes in by_shard.items():
        lines.append("\t".join([
            shard_id, str(len(minutes)), f"{min(minutes):.1f}", f"{statistics.median(minutes):.1f}",
            f"{max(minutes):.1f}", f"{sum(minutes):.1f}", str(groups[shard_id])]))
    return "\n".join(lines)
