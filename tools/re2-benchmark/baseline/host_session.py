#!/usr/bin/env python3
"""A shard's measurement routes, as shard-dispatch.tsv defines them, and each route's evidence."""

import argparse
import csv
from pathlib import Path

from acceptance import canonical_systems, parse_systems, read_properties


DISPATCH_FIELDS = (
    "shard_id",
    "native_access_handler",
    "native_access_systems",
    "object_row_handler",
    "object_row_systems",
    "semantic_tests",
    "blocker",
)
ROUTES = (
    ("native-access", "native_access_handler", "native_access_systems"),
    ("object-row", "object_row_handler", "object_row_systems"),
)


def load_dispatch(path, shard):
    with path.open(newline="", encoding="utf-8") as input_file:
        reader = csv.DictReader(input_file, delimiter="\t")
        if tuple(reader.fieldnames or ()) != DISPATCH_FIELDS:
            raise ValueError(f"Unexpected dispatch header in {path}")
        rows = [row for row in reader if row["shard_id"] == shard]
    if len(rows) != 1:
        raise ValueError(f"Expected one dispatch row for shard {shard!r}, found {len(rows)}")
    return rows[0]


def applicable_routes(dispatch):
    routes = []
    for route, handler_field, systems_field in ROUTES:
        handler = dispatch[handler_field]
        systems = dispatch[systems_field]
        if handler == "blocked":
            raise ValueError(
                f"Shard {dispatch['shard_id']!r} route {route!r} is blocked: {dispatch['blocker']}")
        if handler == "-" or systems == "-":
            if handler != systems:
                raise ValueError(f"Shard {dispatch['shard_id']!r} has an incomplete {route!r} route")
            continue
        parsed_systems = parse_systems(systems, f"{dispatch['shard_id']} {route}")
        routes.append((route, handler, parsed_systems))
    if not routes:
        raise ValueError(f"Shard {dispatch['shard_id']!r} has no applicable routes")
    return routes


def print_routes(arguments):
    for route, _, systems in applicable_routes(load_dispatch(arguments.dispatch, arguments.shard)):
        print(f"{route}\t{canonical_systems(systems)}")


def require_complete_route(route_directory):
    status_path = route_directory / "status.txt"
    if not status_path.is_file() or status_path.read_text(encoding="utf-8") != "status=complete\n":
        raise ValueError(f"Route did not complete successfully: {route_directory}")


def route_evidence(route_directory, needs_native_bracket, required_artifacts):
    required = set(required_artifacts)
    if needs_native_bracket:
        required.add("native-bracket.tsv")
    metadata = read_properties(route_directory / "run-metadata.txt")
    qualification_required = metadata.get("protocol_qualification_required")
    if qualification_required not in {"true", "false"}:
        raise ValueError(f"Invalid protocol_qualification_required in {route_directory / 'run-metadata.txt'}")
    if qualification_required == "true":
        required.add("protocol-qualification.tsv")
    missing = sorted(
        artifact for artifact in required
        if not (route_directory / artifact).is_file() or (route_directory / artifact).stat().st_size == 0)
    if missing:
        raise ValueError(f"Required route evidence is missing or empty in {route_directory}: {missing}")

    artifacts = []
    for path in route_directory.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(route_directory)
        if relative.parts[0] in {"raw", "logs"}:
            continue
        artifacts.append((relative.as_posix(), path))
    evidence = []
    for artifact, path in sorted(artifacts):
        evidence.append((artifact, path))
    return evidence


def parser():
    directory = Path(__file__).resolve().parent
    argument_parser = argparse.ArgumentParser()
    subparsers = argument_parser.add_subparsers(dest="command", required=True)

    routes_parser = subparsers.add_parser("routes")
    routes_parser.add_argument("--dispatch", type=Path, default=directory / "shard-dispatch.tsv")
    routes_parser.add_argument("--shard", required=True)
    routes_parser.set_defaults(function=print_routes)
    return argument_parser


def main():
    argument_parser = parser()
    arguments = argument_parser.parse_args()
    try:
        arguments.function(arguments)
    except (OSError, ValueError) as error:
        argument_parser.error(str(error))


if __name__ == "__main__":
    main()
