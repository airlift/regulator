#!/usr/bin/env python3

import csv
import os
import subprocess
import tempfile
from pathlib import Path

import host_duration
import host_session


MAXIMUM_SHARDS = 37
MAXIMUM_HOST_SECONDS = 90 * 60
HOST_BOOTSTRAP_ALLOWANCE_SECONDS = 10 * 60


def main():
    directory = Path(__file__).resolve().parent
    shards_path = directory / "shards.tsv"
    dispatch_path = directory / "shard-dispatch.tsv"
    with shards_path.open(newline="", encoding="utf-8") as input_file:
        shards = [row["shard_id"] for row in csv.DictReader(input_file, delimiter="\t")]
    if len(shards) > MAXIMUM_SHARDS:
        raise SystemExit(f"Shard count {len(shards)} exceeds hard maximum {MAXIMUM_SHARDS}")

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
        f"{MAXIMUM_HOST_SECONDS}s")


if __name__ == "__main__":
    main()
