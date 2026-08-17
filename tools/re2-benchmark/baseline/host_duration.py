#!/usr/bin/env python3

import argparse
import csv
import math
from pathlib import Path


SHARED_ALLOWANCES = (
    "native_build_startup_allowance_seconds",
    "rebar_build_startup_allowance_seconds",
    "pinned_trino_preparation_allowance_seconds",
)


def read_properties(path):
    values = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise ValueError(f"Invalid property line in {path}:{line_number}: {line!r}")
        values[key] = value
    return values


def number(values, key, path):
    try:
        value = float(values[key])
    except (KeyError, ValueError) as error:
        raise ValueError(f"{path} has no valid {key}") from error
    if not math.isfinite(value) or value < 0:
        raise ValueError(f"{path} has invalid {key}: {value}")
    return value


def format_number(value):
    return f"{value:.17g}"


def estimate(metadata_paths, bootstrap_allowance):
    if not metadata_paths:
        raise ValueError("At least one route metadata file is required")
    if not math.isfinite(bootstrap_allowance) or bootstrap_allowance < 0:
        raise ValueError("Bootstrap allowance must be finite and nonnegative")
    routes = []
    for path in metadata_paths:
        values = read_properties(path)
        routes.append((values["route"], number(values, "route_static_duration_estimate_seconds", path), values, path))
    route_names = [route for route, _, _, _ in routes]
    if len(route_names) != len(set(route_names)):
        raise ValueError(f"Duplicate route metadata: {route_names}")

    route_total = sum(seconds for _, seconds, _, _ in routes)
    duplicated_shared_allowance = 0
    retained_shared_allowance = 0
    for key in SHARED_ALLOWANCES:
        allowances = [number(values, key, path) for _, _, values, path in routes]
        duplicated_shared_allowance += sum(allowances)
        retained_shared_allowance += max(allowances)
    host_total = route_total - duplicated_shared_allowance + retained_shared_allowance + bootstrap_allowance
    return routes, route_total, retained_shared_allowance, host_total


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", type=Path, action="append", required=True)
    parser.add_argument("--bootstrap-allowance-seconds", type=float, default=600)
    parser.add_argument("--maximum-seconds", type=float, default=5400)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()

    routes, route_total, shared_allowance, host_total = estimate(
        arguments.metadata, arguments.bootstrap_allowance_seconds)
    rows = [{
        "route": route,
        "route_static_duration_estimate_seconds": format_number(seconds),
        "host_route_order": str(index),
    } for index, (route, seconds, _, _) in enumerate(routes, start=1)]
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    with arguments.output.open("w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=tuple(rows[0]), delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    with arguments.output.with_suffix(".properties").open("w", encoding="utf-8") as output_file:
        output_file.write(f"route_count={len(routes)}\n")
        output_file.write(f"route_duration_sum_seconds={format_number(route_total)}\n")
        output_file.write(f"shared_cold_allowance_seconds={format_number(shared_allowance)}\n")
        output_file.write(
            f"host_bootstrap_allowance_seconds={format_number(arguments.bootstrap_allowance_seconds)}\n")
        output_file.write(f"host_static_duration_estimate_seconds={format_number(host_total)}\n")
        output_file.write(f"host_static_duration_limit_seconds={format_number(arguments.maximum_seconds)}\n")
    if host_total > arguments.maximum_seconds:
        raise ValueError(
            f"Static host duration estimate {format_number(host_total)}s exceeds "
            f"{format_number(arguments.maximum_seconds)}s")


if __name__ == "__main__":
    main()
