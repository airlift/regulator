#!/usr/bin/env python3
"""Prepare literal-probe distribution workloads for the shared bulk collector.

Each workload counts one literal over a pinned corpus twice: once with the
default uniform byte-frequency distribution and once with a distribution that
matches the corpus. Both are ordinary language-bulk cases, so the existing
collector, fleet, and report code measure them unchanged. The corpora are
downloaded and checksum-verified here; they are never committed.
"""

import argparse
import re
import urllib.request
import zlib
from pathlib import Path

import bulk
import collection
import rebar_inventory


CORPUS = rebar_inventory.MANIFESTS / "literal-probe-corpus.tsv"
WORKLOADS = rebar_inventory.MANIFESTS / "literal-probe-workloads.tsv"
POPULATION = "literal-probe"
PRESETS = {"english-text", "json"}
# The learned distribution counts up to 256 KiB of whole lines from the first
# half of each access log, drawn in java.util.Random order with SAMPLE_SEED. The
# second half of the nginx log is the searched haystack.
SAMPLED_LOG_LINES = "sampled-log-lines"
SAMPLE_CORPORA = ("elastic-apache-access.log", "elastic-nginx-access.log")
SAMPLE_SEED = 20261009
SAMPLE_BYTES = 256 * 1024
# Checked against java.util.Random and Collections.shuffle over the pinned logs.
SAMPLE_SHA256 = "93720b52c37a7da31da907627eafbdda753e445dc783d33f5d4ab0e77fc6a8a8"
READ_BLOCK_BYTES = 1 << 16
# Some hosts reject the default Python user agent.
USER_AGENT = "regulator-literal-probe-provisioner"


def literal_pattern(literal):
    """Escape regex metacharacters; RE2, Java and Joni all read an escaped punctuation byte as itself."""
    return re.sub(rb"([.^$*+?()\[\]{}|\\])", rb"\\\1", literal)


def corpus_rows():
    rows = rebar_inventory.read_table(CORPUS)
    if len({row["name"] for row in rows}) != len(rows):
        raise ValueError("duplicate literal-probe corpus")
    return {row["name"]: row for row in rows}


def workload_rows(corpora):
    rows = rebar_inventory.read_table(WORKLOADS)
    if len({row["id"] for row in rows}) != len(rows):
        raise ValueError("duplicate literal-probe workload")
    for row in rows:
        if (row["corpus"] not in corpora or row["haystack"] not in {"whole", "second-half"} or
                row["distribution"] not in PRESETS | {SAMPLED_LOG_LINES}):
            raise ValueError(f"invalid literal-probe workload: {row['id']}")
    return rows


class JavaRandom:
    """java.util.Random, whose shuffle orders the sampled log lines."""

    def __init__(self, seed):
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def next(self, bits):
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        value = self.seed >> (48 - bits)
        return value - (1 << 32) if value >= 1 << 31 else value

    def next_int(self, bound):
        value = self.next(31)
        limit = bound - 1
        if bound & limit == 0:
            return (bound * value) >> 31
        result = value % bound
        # Java rejects values whose int arithmetic overflows here.
        while value - result + limit >= 1 << 31:
            value = self.next(31)
            result = value % bound
        return result


def shuffle(values, random):
    """Collections.shuffle for a random-access list."""
    for index in range(len(values), 1, -1):
        other = random.next_int(index)
        values[index - 1], values[other] = values[other], values[index - 1]


def split_half(data):
    """Split after the first line feed at or past the middle."""
    end = data.find(b"\n", len(data) // 2)
    half = len(data) if end < 0 else end + 1
    return data[:half], data[half:]


def sample_lines(first_half):
    lines = []
    start = 0
    while (end := first_half.find(b"\n", start)) >= 0:
        lines.append(first_half[start:end + 1])
        start = end + 1
    shuffle(lines, JavaRandom(SAMPLE_SEED))
    sample = bytearray()
    for line in lines:
        # Keep looking for shorter lines once a long one no longer fits.
        if len(sample) + len(line) <= SAMPLE_BYTES:
            sample += line
    return bytes(sample)


def learned_sample(corpora):
    """Concatenate both log samples; the distribution only counts bytes."""
    return b"".join(sample_lines(split_half(corpora[name])[0]) for name in SAMPLE_CORPORA)


def open_url(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=120)


def download(row):
    # Read one block past the limit so truncation can tell a short source from a long one.
    max_bytes = int(row["max_bytes"])
    decompressor = zlib.decompressobj(zlib.MAX_WBITS | 16) if row["kind"] == "gzip-url" else None
    if decompressor is None and row["kind"] != "url":
        raise ValueError(f"unknown literal-probe corpus kind: {row['kind']}")
    data = bytearray()
    with open_url(row["location"]) as response:
        while len(data) <= max_bytes:
            block = response.read(READ_BLOCK_BYTES)
            if not block:
                if decompressor is not None:
                    data += decompressor.flush()
                break
            data += decompressor.decompress(block) if decompressor is not None else block
    if len(data) > max_bytes:
        end = data.rfind(b"\n", 0, max_bytes)
        if end < 0:
            raise ValueError(f"no line feed within the first {max_bytes} bytes: {row['name']}")
        del data[end + 1:]
    return bytes(data)


def verify(row, data):
    if len(data) != int(row["bytes"]) or collection.digest(data) != row["sha256"]:
        raise ValueError(f"literal-probe corpus differs from its pin: {row['name']} "
                         f"({len(data)} bytes, sha256 {collection.digest(data)})")
    return data


def fetch(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, row in corpus_rows().items():
        target = directory / name
        if target.exists():
            verify(row, target.read_bytes())
            continue
        partial = target.with_name(name + ".partial")
        partial.write_bytes(verify(row, download(row)))
        partial.rename(target)


def load_corpora(directory, sources):
    return {name: verify(row, (directory / name).read_bytes()) for name, row in sources.items()}


def prepare(corpus_directory, destination):
    sources = corpus_rows()
    corpora = load_corpora(corpus_directory, sources)
    sample = learned_sample(corpora)
    if collection.digest(sample) != SAMPLE_SHA256:
        raise ValueError("learned log sample differs from its pinned checksum")
    destination.mkdir(parents=True, exist_ok=False)
    (destination / "inputs").mkdir()
    cases = []
    for row in workload_rows(sources):
        source = sources[row["corpus"]]
        literal = row["pattern"].encode()
        pattern = literal_pattern(literal)
        corpus = corpora[row["corpus"]]
        haystack = corpus if row["haystack"] == "whole" else split_half(corpus)[1]
        # The escaped pattern matches only the literal, so its leftmost non-overlapping count is a byte count.
        if haystack.count(literal) != int(row["expected_count"]):
            raise ValueError(f"literal-probe expected count changed: {row['id']}")
        tuned = ({"byte-frequency-sample": [sample]} if row["distribution"] == SAMPLED_LOG_LINES else
                 {"byte-frequencies": [row["distribution"].encode()]})
        for variant, family, extra, distribution in (("", "uniform", {}, "default uniform"),
                                                     ("/tuned", row["distribution"], tuned, row["distribution"])):
            identity = f"{POPULATION}/{row['id']}{variant}"
            payload = bulk.encode_klv({"name": [identity.encode()], "model": [b"count"], "pattern": [pattern],
                                       "haystack": [haystack], "unicode": [b"true"], "case-insensitive": [b"false"],
                                       **extra})
            sha256 = collection.digest(payload)
            path = f"inputs/{sha256}.klv"
            (destination / path).write_bytes(payload)
            cases.append({
                "id": identity, "model": "count", "population": POPULATION, "family": family,
                "corpus": {key: source[key] for key in ("name", "location", "revision", "sha256", "license")},
                "haystack": row["haystack"], "source_file": path, "source_sha256": sha256,
                "source_pattern_sha256": collection.digest(pattern),
                "source_haystack_sha256": collection.digest(haystack),
                "input_bytes_per_operation": len(haystack), "pattern_bytes": len(pattern),
                "expected_result": int(row["expected_count"]),
                "mappings": {language: {
                    "status": "identical", "verifier": "bulk-result-boundaries-and-bytes-v2",
                    "reason": f"Unchanged literal and flags. Regulator compiles with the {distribution} "
                              "byte-frequency distribution; the comparator has no such option",
                    "pattern_hex": pattern.hex(), "input_file": path, "input_file_sha256": sha256}
                    for language in collection.ENGINES}})
    learned = next(case["id"] for case in cases if case["family"] == SAMPLED_LOG_LINES)
    manifest = {"schema_version": 3, "suite": "language-bulk", "protocol": collection.STEADY_PROTOCOLS["language-bulk"],
                "languages": collection.ENGINES, "memory_modes": collection.MODES,
                "operations": ["compile", "execute"], "cases": cases,
                # Smoke also exercises a workload file that carries the learned sample.
                "smoke_required_pairs": [[learned, language] for language in collection.ENGINES],
                "corpus_sha256": collection.digest(CORPUS.read_bytes()),
                "workloads_sha256": collection.digest(WORKLOADS.read_bytes()),
                "learned_sample": {"corpora": list(SAMPLE_CORPORA), "seed": SAMPLE_SEED, "byte_limit_per_corpus": SAMPLE_BYTES,
                                   "bytes": len(sample), "sha256": collection.digest(sample)},
                "representation": "Original byte inputs; JDK text decoded before timing",
                "regulator_operation_policy": "Public count with the workload's byte-frequency distribution; "
                                              "the distribution is built during setup, outside timing",
                "scope": "Literal-probe distribution workloads; Rebar bulk results remain separate"}
    bulk.validate_manifest(manifest, destination)
    collection.save(destination / "manifest.json", manifest)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("fetch", "prepare"))
    parser.add_argument("--corpus-directory", type=Path, required=True)
    parser.add_argument("--manifest-directory", type=Path)
    args = parser.parse_args()
    if args.command == "fetch":
        fetch(args.corpus_directory)
        print(f"Verified {len(corpus_rows())} literal-probe corpora in {args.corpus_directory}")
    else:
        if args.manifest_directory is None:
            parser.error("prepare requires --manifest-directory")
        manifest = prepare(args.corpus_directory, args.manifest_directory)
        print(f"Prepared {len(manifest['cases'])} literal-probe workload definitions")


if __name__ == "__main__":
    main()
