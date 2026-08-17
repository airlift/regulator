#!/usr/bin/env python3

import argparse
import csv
import hashlib
from pathlib import Path

from acceptance import (
    EVIDENCE_FIELDS,
    SESSION_FIELDS,
    ValidationError,
    canonical_systems,
    capacity_prefix_sha256,
    evidence_candidate_identity,
    parse_systems,
    read_properties,
    read_one_tsv,
    verify_evidence_manifest,
)
from shard_results import (
    MANIFEST_FIELDS,
    REBAR_OUTCOME_FIELDS,
    combine,
    load_manifest,
    load_rebar_outcomes,
    selected_rows,
    write_tsv,
)


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
def sha256_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


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


def route_evidence(route_directory, needs_native_bracket):
    required = {
        "route-session.tsv",
        "semantic-gate.tsv",
        "semantic-evidence.tsv",
        "calibration.tsv",
        "observed-rows.tsv",
        "observed-rows.tsv.sha256",
        "raw-artifacts.sha256",
        "run-metadata.txt",
        "status.txt",
    }
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


def combine_routes(arguments):
    root = arguments.session.parent
    manifest = load_manifest(arguments.manifest)
    dispatch = load_dispatch(arguments.dispatch, arguments.shard)
    routes = applicable_routes(dispatch)
    selected_route = getattr(arguments, "route", None)
    if selected_route is not None:
        routes = [route for route in routes if route[0] == selected_route]
        if not routes:
            raise ValueError(f"Shard {arguments.shard!r} does not define selected route {selected_route!r}")
    manifest_systems = {
        row["system"] for row in manifest if row["shard_id"] == arguments.shard
    }
    route_systems = set().union(*(systems for _, _, systems in routes))
    if selected_route is None and route_systems != manifest_systems:
        raise ValueError(
            f"Dispatch systems do not cover shard {arguments.shard!r}; "
            f"missing={sorted(manifest_systems - route_systems)}, "
            f"unexpected={sorted(route_systems - manifest_systems)}")

    expected = selected_rows(manifest, arguments.shard, ",".join(sorted(route_systems)))
    write_tsv(arguments.expected, MANIFEST_FIELDS, expected)

    route_sessions = []
    observed_paths = []
    rebar_outcomes = {}
    evidence_rows = []
    for route, _, systems in routes:
        route_directory = arguments.routes_directory / route
        require_complete_route(route_directory)
        session = read_one_tsv(route_directory / "route-session.tsv", SESSION_FIELDS)
        session_systems = parse_systems(session["systems"], str(route_directory / "route-session.tsv"))
        if session_systems != systems:
            raise ValueError(
                f"Route {route!r} session systems differ from dispatch; "
                f"expected={sorted(systems)}, actual={sorted(session_systems)}")
        route_sessions.append(session)
        observed_paths.append(route_directory / "observed-rows.tsv")
        route_outcomes_path = route_directory / "raw" / "rebar-outcomes.tsv"
        if route_outcomes_path.is_file():
            route_outcomes = load_rebar_outcomes(route_outcomes_path)
            duplicates = sorted(rebar_outcomes.keys() & route_outcomes.keys())
            if duplicates:
                raise ValueError(f"Duplicate Rebar outcomes across routes: {duplicates}")
            rebar_outcomes.update(route_outcomes)
        for artifact, path in route_evidence(
                route_directory, {"native-re2-before", "native-re2-after"}.issubset(systems)):
            evidence_rows.append({
                "route": route,
                "artifact": f"routes/{route}/{artifact}",
                "verification": "sha256",
                "sha256": sha256_file(path),
            })

    identity_fields = tuple(field for field in SESSION_FIELDS if field != "systems")
    first_session = route_sessions[0]
    for route_index, session in enumerate(route_sessions[1:], start=1):
        changed = [field for field in identity_fields if session[field] != first_session[field]]
        if changed:
            raise ValueError(f"Route session {route_index + 1} changed host identity fields: {changed}")
    if first_session["shard_id"] != arguments.shard:
        raise ValueError(
            f"Route session shard {first_session['shard_id']!r} does not match {arguments.shard!r}")

    rebar_outcomes_path = root / "rebar-outcomes.tsv"
    if rebar_outcomes:
        write_tsv(
            rebar_outcomes_path,
            REBAR_OUTCOME_FIELDS,
            [rebar_outcomes[row_id] for row_id in sorted(rebar_outcomes)])
    else:
        rebar_outcomes_path.unlink(missing_ok=True)

    combine(type("Arguments", (), {
        "expected": arguments.expected,
        "inputs": observed_paths,
        "output": arguments.observed,
        "rebar_outcomes": rebar_outcomes_path if rebar_outcomes else None,
    })())

    session = dict(first_session)
    session["systems"] = canonical_systems(route_systems)
    write_tsv(arguments.session, SESSION_FIELDS, [session])

    for artifact in (
            "environment-manifest.txt",
            "environment.txt",
            "route-plan.tsv",
            rebar_outcomes_path.name,
            arguments.expected.name,
            arguments.observed.name,
            arguments.session.name):
        path = root / artifact
        if path.is_file():
            evidence_rows.append({
                "route": "host",
                "artifact": artifact,
                "verification": "sha256",
                "sha256": sha256_file(path),
            })
    capacity_path = root / "host-capacity.txt"
    if capacity_path.is_file():
        evidence_rows.append({
            "route": "host",
            "artifact": "host-capacity.txt",
            "verification": "capacity-prefix-sha256",
            "sha256": capacity_prefix_sha256(capacity_path),
        })
    write_tsv(arguments.evidence, EVIDENCE_FIELDS, sorted(
        evidence_rows, key=lambda row: (row["route"], row["artifact"])))
    evidence_rows = verify_evidence_manifest(arguments.evidence, root)
    evidence_candidate_identity(evidence_rows, root)


def parser():
    directory = Path(__file__).resolve().parent
    argument_parser = argparse.ArgumentParser()
    subparsers = argument_parser.add_subparsers(dest="command", required=True)

    routes_parser = subparsers.add_parser("routes")
    routes_parser.add_argument("--dispatch", type=Path, default=directory / "shard-dispatch.tsv")
    routes_parser.add_argument("--shard", required=True)
    routes_parser.set_defaults(function=print_routes)

    combine_parser = subparsers.add_parser("combine")
    combine_parser.add_argument("--manifest", type=Path, default=directory / "rows.tsv")
    combine_parser.add_argument("--dispatch", type=Path, default=directory / "shard-dispatch.tsv")
    combine_parser.add_argument("--shard", required=True)
    combine_parser.add_argument("--routes-directory", type=Path, required=True)
    combine_parser.add_argument("--expected", type=Path, required=True)
    combine_parser.add_argument("--observed", type=Path, required=True)
    combine_parser.add_argument("--session", type=Path, required=True)
    combine_parser.add_argument("--evidence", type=Path, required=True)
    combine_parser.add_argument("--route", choices=("native-access", "object-row"))
    combine_parser.set_defaults(function=combine_routes)
    return argument_parser


def main():
    argument_parser = parser()
    arguments = argument_parser.parse_args()
    try:
        arguments.function(arguments)
    except (OSError, ValueError, ValidationError) as error:
        argument_parser.error(str(error))


if __name__ == "__main__":
    main()
