#!/usr/bin/env python3

import csv
import math
import os
import subprocess
import tempfile
from pathlib import Path

import host_duration
import host_session
import row_protocol
import unit_cost
from shard_results import load_manifest


MAXIMUM_SHARDS = 47
MAXIMUM_HOST_SECONDS = 90 * 60
HOST_BOOTSTRAP_ALLOWANCE_SECONDS = 10 * 60
# Route allowances that the unit cost model leaves out.
COLD_ALLOWANCES = (
    "native_build_startup_allowance_seconds",
    "rebar_build_startup_allowance_seconds",
    "pinned_trino_preparation_allowance_seconds",
    "rebar_primary_verification_estimate_seconds",
    "rebar_joni_verification_estimate_seconds",
)
QUALIFICATION_CALIBRATION_INVOCATIONS = 2
# Where the route estimate departs from the unit cost model, in model-minus-route seconds.
ROUTE_ESTIMATE_DIFFERENCES = {
    # The route count includes none of trino-like's systems, so it charges none of their windows.
    ("trino-like", "native-access"): 3600,
    # The route charges the extended warmup to native rows sharing the compiler benchmark names.
    ("traditional-capture", "native-access"): -325,
    # The route also charges every full-protocol row the bounded windows it does not run.
    ("accepted-routes", "native-access"): -15,
    ("accepted-routes", "object-row"): -15,
    ("expression-plans-b", "native-access"): -5,
    ("expression-plans-b", "object-row"): -5,
    ("traditional-search", "native-access"): -5,
    ("traditional-search", "object-row"): -5,
    ("trino-final-line", "native-access"): -10,
}


def check_cost_model(result_directory, shard, route, replica, protocols, representatives):
    """Pin the unit cost model to the route estimate run-shard.sh planned."""
    metadata = host_duration.read_properties(result_directory / "run-metadata.txt")
    rows = load_manifest(result_directory / "expected-rows.tsv")
    protocol_replica = "1" if replica == 4 else str(replica)
    representative_count = sum(
        1 for representative in representatives
        if (representative["shard_id"], representative["replica_id"], representative["route"]) ==
        (shard, protocol_replica, route) and
        unit_cost.compares_with_reference(protocols[representative["manifest_row_id"]]))
    if representative_count != int(metadata["protocol_representative_count"]):
        raise SystemExit(
            f"{shard} {route} replica {replica} plans {metadata['protocol_representative_count']} "
            f"representatives, but {representative_count} rows compare with the reference")
    forked_invocations = int(metadata["joni_jmh_invocation_count"])
    invocations = int(metadata["jmh_invocation_count"]) - forked_invocations
    startup = 0
    for execution, count in (("jmh-processes", invocations), ("jmh-forks", forked_invocations)):
        if count:
            protocol = next(protocols[row["row_id"]] for row in rows
                            if protocols[row["row_id"]]["execution"] == execution)
            startup += count * unit_cost.selector_startup_seconds(protocol)
    modelled = (
        unit_cost.measurement_seconds(rows, protocols) +
        startup +
        unit_cost.calibration_seconds(QUALIFICATION_CALIBRATION_INVOCATIONS) +
        unit_cost.representative_seconds(representative_count))
    planned = host_duration.number(metadata, "route_static_duration_estimate_seconds", result_directory) - sum(
        host_duration.number(metadata, key, result_directory) for key in COLD_ALLOWANCES)
    expected = ROUTE_ESTIMATE_DIFFERENCES.get((shard, route), 0)
    if not math.isclose(modelled - planned, expected, abs_tol=1e-6):
        raise SystemExit(
            f"{shard} {route} replica {replica} unit cost model gives {modelled:.17g}s, "
            f"route estimate gives {planned:.17g}s; expected difference {expected}s")


def main():
    directory = Path(__file__).resolve().parent
    shards_path = directory / "shards.tsv"
    dispatch_path = directory / "shard-dispatch.tsv"
    with shards_path.open(newline="", encoding="utf-8") as input_file:
        shards = [row["shard_id"] for row in csv.DictReader(input_file, delimiter="\t")]
    if len(shards) > MAXIMUM_SHARDS:
        raise SystemExit(f"Shard count {len(shards)} exceeds hard maximum {MAXIMUM_SHARDS}")

    protocols = row_protocol.load(directory / "row-protocol.tsv")
    representatives = row_protocol.read_tsv(directory / "protocol-representatives.tsv")
    summaries = []
    with tempfile.TemporaryDirectory() as temporary_directory:
        root = Path(temporary_directory)
        subprocess.run(
            [str(directory / "validate-protocol-representatives.py")],
            check=True,
            capture_output=True,
            text=True)
        for replica in range(1, 5):
            for shard in shards:
                dispatch = host_session.load_dispatch(dispatch_path, shard)
                routes = host_session.applicable_routes(dispatch)
                metadata_paths = []
                for route, _, _ in routes:
                    result_directory = root / f"replica-{replica}" / shard / route
                    result_directory.mkdir(parents=True)
                    environment = {
                        **os.environ,
                        "BASELINE_PLAN_ONLY": "true",
                        "BASELINE_DEFER_ACCEPTANCE": "true",
                        "BASELINE_PROTOCOL_QUALIFICATION": "true",
                        "RE2_CAMPAIGN_REPLICA_ID": str(replica),
                    }
                    subprocess.run(
                        [
                            str(directory / "run-shard.sh"),
                            shard,
                            "qualification",
                            route,
                            str(result_directory),
                        ],
                        check=True,
                        capture_output=True,
                        text=True,
                        env=environment)
                    check_cost_model(result_directory, shard, route, replica, protocols, representatives)
                    metadata_paths.append(result_directory / "run-metadata.txt")
                _, _, _, host_seconds = host_duration.estimate(
                    metadata_paths, HOST_BOOTSTRAP_ALLOWANCE_SECONDS)
                if host_seconds > MAXIMUM_HOST_SECONDS:
                    raise SystemExit(
                        f"{shard} replica {replica} static host duration {host_seconds:.17g}s exceeds "
                        f"{MAXIMUM_HOST_SECONDS}s")
                summaries.append((replica, shard, len(routes), host_seconds))

    for replica, shard, route_count, host_seconds in summaries:
        print(
            f"replica={replica}\t{shard}\troutes={route_count}"
            f"\thost_seconds={host_seconds:.17g}")
    print(
        f"validated {len(summaries)} replica/shard plans below "
        f"{MAXIMUM_HOST_SECONDS}s and against the unit cost model")


if __name__ == "__main__":
    main()
