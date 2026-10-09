#!/usr/bin/env python3
"""Regenerate the ByteFrequencies preset tables from pinned public sources.

Every row of sources.tsv names one pinned source of one preset. The script fetches each
source into a cache directory, verifies its length and sha256, and prints the Java
initializer of every preset table in the layout of ByteFrequencies.java. With --check it
compares the generated tables with ByteFrequencies.java instead. See README.md for the
sources and the method.
"""

import argparse
import csv
import hashlib
import io
import tarfile
import urllib.request
from pathlib import Path

# Some hosts reject the default Python user agent.
USER_AGENT = "regulator-byte-frequencies"
JAVA_SOURCE = Path("src/main/java/io/airlift/regulator/ByteFrequencies.java")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path(__file__).with_name("sources.tsv"))
    parser.add_argument("--cache", type=Path, help="source cache directory (default target/byte-frequencies)")
    parser.add_argument("--check", action="store_true", help="compare the generated tables with ByteFrequencies.java")
    return parser.parse_args()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def open_url(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=300)


def read_url(url):
    with open_url(url) as response:
        return response.read()


def read_leipzig(url, name):
    # A Leipzig corpus archive holds <name>/<name>-sentences.txt with one "id<TAB>sentence"
    # line per sentence. Keep the sentence and its line feed.
    with tarfile.open(fileobj=io.BytesIO(read_url(url)), mode="r:gz") as archive:
        member = archive.extractfile(f"{name}/{name}-sentences.txt")
        if member is None:
            raise SystemExit(f"Leipzig archive has no sentences file: {url}")
        lines = member.read().split(b"\n")
    if lines.pop() != b"":
        raise SystemExit(f"Leipzig sentences file does not end with a line feed: {url}")
    sentences = bytearray()
    for line in lines:
        identifier, separator, sentence = line.partition(b"\t")
        if not separator or not identifier.isdigit():
            raise SystemExit(f"unexpected Leipzig sentence line in {url}: {line[:80]!r}")
        sentences += sentence + b"\n"
    return bytes(sentences)


def read_source(row):
    kind = row["kind"]
    if kind == "url":
        return read_url(row["url"])
    if kind == "leipzig":
        return read_leipzig(row["url"], row["name"])
    raise SystemExit(f"unknown source kind for {row['name']}: {kind}")


def verify(row, data):
    if len(data) != int(row["bytes"]) or sha256(data) != row["sha256"]:
        raise SystemExit(
            f"byte-frequency source differs from manifest: {row['preset']} {row['name']} "
            f"(found {len(data)} bytes, sha256 {sha256(data)})")


def load_source(cache, row):
    cached = cache / row["preset"] / row["name"]
    if cached.is_file():
        data = cached.read_bytes()
        verify(row, data)
        return data
    data = read_source(row)
    verify(row, data)
    cached.parent.mkdir(parents=True, exist_ok=True)
    cached.write_bytes(data)
    return data


def byte_counts(data):
    counts = [0] * 256
    for value in data:
        counts[value] += 1
    return counts


def mean(distributions):
    # Sum in manifest order, as the published tables were computed.
    total = [0.0] * 256
    for distribution in distributions:
        for value in range(256):
            total[value] += distribution[value]
    return [value / len(distributions) for value in total]


def smoothed(data):
    # ByteFrequencies.fromCounts: (count + 1) / (total + 256).
    counts = byte_counts(data)
    total = float(sum(counts))
    return [(count + 1.0) / (total + 256) for count in counts]


def pin_non_ascii_common(probabilities):
    return probabilities[:0x80] + [max(probabilities[:0x80])] * 0x80


def measured_table(sources):
    return pin_non_ascii_common(mean([smoothed(data) for data in sources]))


def table_source(preset, probabilities):
    lines = [f"    private static final double[] {preset}_PROBABILITIES = {{"]
    for line_start in range(0, 256, 8):
        values = ", ".join(f"{probabilities[value]:.6e}" for value in range(line_start, line_start + 8))
        lines.append(f"            {values}, // 0x{line_start:02X}")
    lines.append("    };")
    return "\n".join(lines) + "\n"


def main():
    arguments = parse_args()
    root = Path(__file__).resolve().parents[2]
    cache = arguments.cache or root / "target" / "byte-frequencies"
    with arguments.manifest.open(newline="") as input_file:
        rows = list(csv.DictReader(input_file, delimiter="\t"))

    presets = {}
    for row in rows:
        presets.setdefault(row["preset"], []).append(load_source(cache, row))

    tables = []
    for preset, sources in presets.items():
        tables.append(table_source(preset, measured_table(sources)))

    if not arguments.check:
        print("\n".join(tables), end="")
        return
    java = (root / JAVA_SOURCE).read_text(encoding="utf-8")
    for preset, table in zip(presets, tables):
        if table not in java:
            raise SystemExit(f"{JAVA_SOURCE} does not contain the generated {preset}_PROBABILITIES table")
    print(f"verified {len(tables)} preset tables in {JAVA_SOURCE}")


if __name__ == "__main__":
    main()
