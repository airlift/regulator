#!/usr/bin/env python3
"""Prepare synthetic log workloads for the shared bulk collector.

Each workload counts ERROR|WARN|FATAL over a generated 1 MiB haystack: a
realistic log, a log whose lines mostly carry those levels, and near misses
that pass a start-literal filter but never match. They are ordinary
language-bulk cases, so the existing collector, fleet, and report code measure
them unchanged. The haystacks are generated here from a fixed seed and checked
against pinned checksums; they are never committed.
"""

import argparse
from datetime import datetime, timedelta, UTC
import re
from pathlib import Path

import bulk
import collection
import literal_probe


POPULATION = "synthetic-log"
PATTERN = b"ERROR|WARN|FATAL"
SEED = 20261101
HAYSTACK_BYTES = 1 << 20
DEFAULT_LANGUAGES = ("rust", "trino")
START = datetime(2026, 11, 1, tzinfo=UTC)
WORDS = ("request", "served", "for", "user", "session", "cache", "miss", "hit", "query", "planned", "in",
         "ms", "connection", "opened", "closed", "from", "peer", "retry", "scheduled", "after", "timeout",
         "worker", "started", "task", "split", "finished", "bytes", "read", "written", "to", "disk")
COMPONENTS = ("http", "scheduler", "storage", "auth", "planner", "exchange")
# Level weights out of 100.
REALISTIC_LEVELS = (("INFO", 80), ("DEBUG", 10), ("WARN", 6), ("ERROR", 3), ("FATAL", 1))
MATCH_DENSE_LEVELS = (("INFO", 10), ("WARN", 40), ("ERROR", 35), ("FATAL", 15))
# Each near miss shares bytes 0, 1, and 3 with a literal, the bytes a start literal filter checks, but not byte 2.
NEAR_MISSES = b"ERxO WAxN FAxA "
# Checked against the generated haystacks: (haystack sha256, ERROR|WARN|FATAL count).
PINS = {
    "realistic": ("b757933101d0348cd9dc7ebc3f56a5e98011e4b46edbc8d64808c25012cae2af", 1169),
    "match-dense": ("1f9399e2d32b136a849d71f3ed49000544d544f61dec8a582f0f3c70c994f50e", 10254),
    "near-miss": ("68a1f5e0fae0e0f6c85878e81a19b57f139479c4391ebd8f212b27c73ddf2e28", 0),
}


def log_lines(levels, random):
    """Whole log lines, each with a timestamp, a weighted level, a component, and words from WORDS."""
    total = sum(weight for _, weight in levels)
    milliseconds = 0
    while True:
        milliseconds += random.next_int(250)
        draw = random.next_int(total)
        for level, weight in levels:
            if draw < weight:
                break
            draw -= weight
        timestamp = START + timedelta(milliseconds=milliseconds)
        words = " ".join(WORDS[random.next_int(len(WORDS))] for _ in range(4 + random.next_int(8)))
        yield (f"{timestamp:%Y-%m-%dT%H:%M:%S}.{milliseconds % 1000:03d}Z {level:<5} "
               f"[{COMPONENTS[random.next_int(len(COMPONENTS))]}] {words} {random.next_int(10000)}\n").encode()


def log_haystack(levels):
    """The log truncated at the last line feed within HAYSTACK_BYTES."""
    lines = []
    size = 0
    # The literal probe's java.util.Random port, so the seed draws the probe's sequence.
    for line in log_lines(levels, literal_probe.JavaRandom(SEED)):
        if size + len(line) > HAYSTACK_BYTES:
            return b"".join(lines)
        lines.append(line)
        size += len(line)


def haystacks():
    return {"realistic": log_haystack(REALISTIC_LEVELS), "match-dense": log_haystack(MATCH_DENSE_LEVELS),
            "near-miss": (NEAR_MISSES * (HAYSTACK_BYTES // len(NEAR_MISSES) + 1))[:HAYSTACK_BYTES]}


def valid_languages(languages):
    """True when `languages` names at least one language, each a distinct engine."""
    return bool(languages) and len(set(languages)) == len(languages) and set(languages) <= set(collection.ENGINES)


def selection(cases, languages):
    """The fleet selection of each case in each requested language."""
    return [[case["id"], language] for case in cases for language in languages]


def prepare(destination, languages=DEFAULT_LANGUAGES):
    if not valid_languages(languages):
        raise ValueError(f"invalid synthetic-log languages: {languages}")
    generated = haystacks()
    for name, haystack in generated.items():
        if (collection.digest(haystack), len(re.findall(PATTERN, haystack))) != PINS[name]:
            raise ValueError(f"synthetic-log haystack differs from its pin: {name}")
    destination.mkdir(parents=True, exist_ok=False)
    (destination / "inputs").mkdir()
    cases = []
    for name, haystack in generated.items():
        identity = f"{POPULATION}/{name}"
        payload = bulk.encode_klv({"name": [identity.encode()], "model": [b"count"], "pattern": [PATTERN],
                                   "haystack": [haystack], "unicode": [b"true"], "case-insensitive": [b"false"]})
        sha256 = collection.digest(payload)
        path = f"inputs/{sha256}.klv"
        (destination / path).write_bytes(payload)
        cases.append({
            "id": identity, "model": "count", "population": POPULATION, "family": name,
            "source_file": path, "source_sha256": sha256, "source_pattern_sha256": collection.digest(PATTERN),
            "source_haystack_sha256": collection.digest(haystack), "input_bytes_per_operation": len(haystack),
            "pattern_bytes": len(PATTERN), "expected_result": PINS[name][1],
            "mappings": {language: {
                "status": "identical", "verifier": "bulk-result-boundaries-and-bytes-v2",
                "reason": "Unchanged literal alternation and flags",
                "pattern_hex": PATTERN.hex(), "input_file": path, "input_file_sha256": sha256}
                for language in collection.ENGINES}})
    selected = selection(cases, languages)
    manifest = {"schema_version": 3, "suite": "language-bulk", "protocol": collection.STEADY_PROTOCOLS["language-bulk"],
                "languages": collection.ENGINES, "memory_modes": collection.MODES,
                "operations": ["compile", "execute"], "cases": cases,
                # Smoke also exercises the near-miss workload in each selected language.
                "smoke_required_pairs": [pair for pair in selected if pair[0] == f"{POPULATION}/near-miss"],
                "generator": {"seed": SEED, "haystack_byte_limit": HAYSTACK_BYTES},
                "representation": "Original byte inputs; JDK text decoded before timing",
                "regulator_operation_policy": "Public count",
                "scope": "Synthetic log workloads; Rebar bulk results remain separate"}
    bulk.validate_manifest(manifest, destination)
    collection.save(destination / "manifest.json", manifest)
    collection.save(destination / "selection.json", selected)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare",))
    parser.add_argument("--manifest-directory", type=Path, required=True)
    parser.add_argument("--languages", default=",".join(DEFAULT_LANGUAGES),
                        help="comma-separated languages the fleet selection measures")
    args = parser.parse_args()
    languages = tuple(args.languages.split(","))
    if not valid_languages(languages):
        parser.error(f"--languages must name distinct languages from {', '.join(collection.ENGINES)}")
    manifest = prepare(args.manifest_directory, languages)
    print(f"Prepared {len(manifest['cases'])} synthetic-log workload definitions and their selection for "
          f"{args.languages}")


if __name__ == "__main__":
    main()
