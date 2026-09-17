"""Verify replacement prerequisites without selecting hosts by timing noise."""

import argparse
import csv
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import sys

from acceptance import read_properties
from host_session import applicable_routes, load_dispatch, require_complete_route
import semantic_evidence

BASELINE = Path(__file__).resolve().parent
RECEIPT = "semantic-preparation.json"
sys.path.insert(0, str(BASELINE.parent / "language"))
import released_artifact


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(session, shard):
    dispatch = load_dispatch(BASELINE / "shard-dispatch.tsv", shard)
    routes = applicable_routes(dispatch)
    if {path.name for path in (session / "routes").iterdir()} != {route for route, _, _ in routes}:
        raise ValueError("semantic preparation must cover every required route")
    environment = dict(line.split("=", 1) for line in (session / "environment.txt").read_text().splitlines() if "=" in line)
    identity = {name: environment.get(name) for name in (
        "baseline_campaign", "platform", "shard", "replica", "host_epoch", "instance_id", "instance_type",
        "availability_zone", "regulator_snapshot_commit", "regulator_snapshot_sha256")}
    if any(value in (None, "", "unknown", "standalone") for value in identity.values()):
        raise ValueError("missing semantic preparation host identity")
    if identity["shard"] != shard or identity["replica"] not in ("1", "2", "3", "4"):
        raise ValueError("semantic preparation host assignment differs")
    for route, handler, systems in routes:
        directory = session / "routes" / route
        require_complete_route(directory)
        metadata = read_properties(directory / "run-metadata.txt")
        expected = {"verification_only": "true", "protocol": "smoke", "shard_id": shard,
                    "route": route, "handler": handler, "candidate_commit": identity["regulator_snapshot_commit"],
                    "manifest_sha256": digest(BASELINE / "rows.tsv")}
        if any(metadata.get(key) != value for key, value in expected.items()) or set(metadata["systems"].split(",")) != systems:
            raise ValueError("semantic preparation metadata differs")
        arguments = SimpleNamespace(reports=directory / "semantic-reports", expected_tests=dispatch["semantic_tests"],
                                    route=route, handler=handler, native_access=str(route == "native-access").lower(),
                                    semantic_log=directory / "logs/semantic-tests.log", rebar_verification=[])
        if handler not in {"traditional", "jmh", "trino-operations", "trino-final-line", "trino-like"}:
            raise ValueError("unsupported replacement semantic handler")
        rows = semantic_evidence.build_evidence(arguments)
        verified = {identifier for kind, identifier, result in rows if kind == "verifier" and result == "passed"}
        mode_line = "OK enabled" if route == "native-access" else "OK disabled"
        if mode_line not in arguments.semantic_log.read_text().splitlines():
            raise ValueError("missing semantic mode or differential verification")
        required = set()
        if handler in {"trino-final-line", "trino-like"} or (handler == "trino-operations" and route == "native-access"):
            required.add("Verified Slice adapter outputs and callback traces against pinned Trino")
            if not any(value.startswith("Verified ") and " paired Trino/Joni operation results (SHA-256 " in value for value in verified):
                raise ValueError("missing pinned Trino differential verification")
            if not any(value.startswith("Verified ") and " everyday Regulator/Joni operation results (SHA-256 " in value for value in verified):
                raise ValueError("missing everyday Trino differential verification")
        if handler == "trino-final-line":
            required.add("Verified 176 Trino final-line operation comparisons")
        if handler == "trino-like":
            required.update({"Verified 60 Trino LIKE lifecycle comparisons", "Verified 530255 Trino LIKE comparisons",
                             "Verified 810155 escaped Trino LIKE comparisons", "Verified 21770 encoded Trino LIKE comparisons"})
        if not required <= verified:
            raise ValueError("missing semantic mode or differential verification")
        with (directory / "semantic-evidence.tsv").open() as source:
            reader = csv.reader(source, delimiter="\t")
            if tuple(next(reader)) != semantic_evidence.FIELDS or [tuple(row) for row in reader] != rows:
                raise ValueError("semantic evidence differs from original test reports")
        with (directory / "semantic-gate.tsv").open() as source:
            gates = list(csv.DictReader(source, delimiter="\t"))
        if gates != [{"route": route, "tests": dispatch["semantic_tests"], "outcome": "accepted",
                      "semantic_result_digest": digest(directory / "semantic-evidence.tsv")}]:
            raise ValueError("semantic gate differs")
    released_artifact.validate_baseline(session, released_artifact.manifest(BASELINE.parents[2], "1.0"))
    files = [session / "environment.txt", session / "environment-manifest.txt"]
    files += [path for path in (session / "routes").rglob("*") if path.is_file()]
    return {"kind": "semantic-preparation", "schema_version": 1, "shard_id": shard,
            "identity": identity, "dispatch_sha256": digest(BASELINE / "shard-dispatch.tsv"),
            "artifacts": {str(path.relative_to(session)): digest(path) for path in sorted(files)}}


def validate(session):
    stored = json.loads((session / RECEIPT).read_text())
    actual = build(session, stored["shard_id"])
    if actual != stored:
        raise ValueError("semantic preparation evidence changed")
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", type=Path, required=True)
    parser.add_argument("--shard", required=True)
    args = parser.parse_args()
    receipt = build(args.session, args.shard)
    with (args.session / RECEIPT).open("x") as output:
        output.write(json.dumps(receipt, indent=2) + "\n")
    validate(args.session)


if __name__ == "__main__":
    main()
