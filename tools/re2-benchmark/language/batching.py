"""Pack complete comparisons using frozen planning estimates."""

import math
import re


def partition_key(partition):
    return partition["case"] + "/" + partition["language"]


def validate(policy, partitions):
    keys = {partition_key(partition) for partition in partitions}
    if (policy.get("schema_version") != 1 or
            not re.fullmatch(r"[0-9a-f]{64}", policy.get("source_sha256", "")) or
            set(policy.get("partition_seconds", {})) != keys):
        raise ValueError("duration policy must identify evidence and every selected partition")
    for value in policy["partition_seconds"].values():
        if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
            raise ValueError("invalid partition duration estimate")
    isolated = policy.get("isolated_pairs", [])
    if (any(not isinstance(pair, list) or len(pair) != 2 or not all(isinstance(value, str) for value in pair)
            for pair in isolated) or len({tuple(pair) for pair in isolated}) != len(isolated) or
            not {"/".join(pair) for pair in isolated} <= keys):
        raise ValueError("invalid isolated comparison selection")
    maximum = policy.get("max_batch_seconds")
    bootstrap = policy.get("bootstrap_seconds")
    if (type(maximum) is not int or type(bootstrap) is not int or maximum <= 0 or bootstrap < 0 or
            maximum + bootstrap > 5400):
        raise ValueError("batch and bootstrap allowance exceed the host deadline")
    if any(seconds > maximum for seconds in policy["partition_seconds"].values()):
        raise ValueError("a single comparison exceeds the batch duration allowance")


def groups(partitions, operation_counts, operation_budget, policy):
    validate(policy, partitions)
    seconds = policy["partition_seconds"]
    isolated = {"/".join(pair) for pair in policy.get("isolated_pairs", [])}
    ordered = sorted(partitions, key=lambda part: (-seconds[partition_key(part)], part["id"]))
    single = [[part["id"]] for part in ordered if partition_key(part) in isolated]
    packed = []
    for part in ordered:
        key = partition_key(part)
        if key in isolated:
            continue
        count = operation_counts[part["id"]]
        selected = next((group for group in packed if group["seconds"] + seconds[key] <= policy["max_batch_seconds"] and
                         group["operations"] + count <= operation_budget), None)
        if selected is None:
            selected = {"ids": [], "seconds": 0, "operations": 0}
            packed.append(selected)
        selected["ids"].append(part["id"])
        selected["seconds"] += seconds[key]
        selected["operations"] += count
    by_id = {part["id"]: part for part in partitions}
    return sorted(single + [group["ids"] for group in packed],
                  key=lambda ids: (-sum(seconds[partition_key(by_id[identity])] for identity in ids), ids))
