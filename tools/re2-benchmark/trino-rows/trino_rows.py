#!/usr/bin/env python3
"""Generate the normative Trino row workloads from pinned corpora.

Every workload is 4,096 real rows, a stride sample of one corpus, or of six pooled
loghub files, and a grid of literals chosen by a seeded rule from the corpus rows
outside that sample. The generator writes the two manifests BenchmarkTrinoRows reads,
or with --check compares them with the checked-in files. See README.md for the rules.
"""

import argparse
import csv
import gzip
import hashlib
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "language"))
import literal_probe

ROOT = Path(__file__).resolve().parents[3]
CORPUS = ROOT / "tools" / "re2-benchmark" / "manifests" / "trino-row-corpus.tsv"
RESOURCES = ROOT / "src" / "test" / "resources" / "io" / "airlift" / "regulator"
WORKLOADS_OUTPUT = RESOURCES / "trino-row-workloads.tsv"
LITERALS_OUTPUT = RESOURCES / "trino-row-literals.tsv"
CLASSPATH_PREFIX = "src/test/resources"

ROWS = 4096
SEED = "20261015"
TOKEN_BYTES = (4, 16)
TERCILES = ("low", "mid", "high")
# ASCII letters, digits and _.-/:"= plus the code points of the Latin, Greek, Cyrillic, kana,
# CJK, and Hangul blocks. A fixed table keeps tokens independent of the Unicode database
# of the Python running the generator.
TOKEN = re.compile("[A-Za-z0-9_.\\-/:\"="
                   "\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u024F\u0370-\u04FF"
                   "\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF\uAC00-\uD7A3]+")
# Workload, its corpora in pooling order, and its hand-picked query-like literals.
WORKLOADS = (
    ("english", ("opensubtitles-en.txt",), ("exactly",)),
    ("russian", ("opensubtitles-ru.txt",), ()),
    ("chinese", ("opensubtitles-zh.txt",), ()),
    ("nginx", ("elastic-nginx-access.log",), ("Mozilla/5.0", "\" 404 ")),
    ("apache", ("elastic-apache-access.log",), ("Googlebot",)),
    ("loghub", ("loghub-hdfs.log", "loghub-linux.log", "loghub-openssh.log",
                "loghub-spark.log", "loghub-windows.log", "loghub-android.log"), ("Failed password",)),
    ("gharchive", ("gharchive-2025-03-10-14-32MiB.json",), ("\"PullRequestEvent\"",)),
)
WORKLOAD_COLUMNS = ("workload", "corpora", "sources", "population_rows", "sampled_rows", "rows", "row_bytes",
                    "rows_sha256", "p50_bytes", "p90_bytes", "p99_bytes")
LITERAL_COLUMNS = ("workload", "literal_id", "group", "tercile", "band", "candidates", "literal", "pattern",
                   "first_byte", "first_byte_count", "first_byte_frequency", "hit_rows", "matches")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_table(path):
    with path.open(newline="", encoding="utf-8") as source:
        return list(csv.DictReader(source, delimiter="\t", quoting=csv.QUOTE_NONE))


def corpus_rows(path=None):
    rows = read_table(path or CORPUS)
    if len({row["name"] for row in rows}) != len(rows):
        raise ValueError("duplicate Trino row corpus")
    return {row["name"]: row for row in rows}


def verify(row, data):
    if len(data) != int(row["bytes"]) or sha256(data) != row["sha256"]:
        raise ValueError(f"Trino row corpus differs from its pin: {row['name']} "
                         f"({len(data)} bytes, sha256 {sha256(data)})")
    return data


def fetch(directory, corpora):
    """Download every url corpus missing from the directory and verify every present one."""
    directory.mkdir(parents=True, exist_ok=True)
    for name, row in corpora.items():
        if row["kind"] == "resource":
            continue
        target = directory / name
        if target.exists():
            verify(row, target.read_bytes())
            continue
        partial = target.with_name(name + ".partial")
        partial.write_bytes(verify(row, literal_probe.download(row)))
        partial.rename(target)


def load(directory, row):
    if row["kind"] == "resource":
        return verify(row, gzip.decompress((ROOT / row["location"]).read_bytes()))
    return verify(row, (directory / row["name"]).read_bytes())


def source_reference(row):
    """Where BenchmarkTrinoRows reads a corpus: a gzip classpath resource or a corpus-directory file."""
    if row["kind"] == "resource":
        if not row["location"].startswith(CLASSPATH_PREFIX + "/"):
            raise ValueError(f"resource corpus is not a test resource: {row['name']}")
        return row["location"][len(CLASSPATH_PREFIX):]
    return row["name"]


def lines(data):
    """Nonempty lines without their line feed or CR LF terminator."""
    rows = []
    for line in data.split(b"\n"):
        if line.endswith(b"\r"):
            line = line[:-1]
        if line:
            rows.append(line)
    return rows


def sample_counts(populations):
    """Split the 4,096 rows across pooled corpora as evenly as possible, earlier corpora first."""
    counts = [ROWS // len(populations) + (index < ROWS % len(populations)) for index in range(len(populations))]
    for count, population in zip(counts, populations):
        if count > population:
            raise ValueError(f"cannot sample {count} rows from {population}")
    return counts


def stride_indexes(population, count):
    return [index * population // count for index in range(count)]


def percentile(sorted_values, percent):
    """Nearest-rank percentile."""
    return sorted_values[-(-percent * len(sorted_values) // 100) - 1]


def token_runs(row):
    """Maximal runs of token characters, encoded as UTF-8."""
    for match in TOKEN.finditer(row.decode("utf-8", "surrogateescape")):
        yield match.group().encode("utf-8")


def tokens(row):
    """Maximal runs of token characters that are 4 to 16 bytes long."""
    return (token for token in token_runs(row) if TOKEN_BYTES[0] <= len(token) <= TOKEN_BYTES[1])


def run_substrings(row):
    """Every byte substring of 4 to 16 bytes within one token run.

    A candidate consists only of token characters, so every occurrence lies inside one run. A
    substring that starts or ends inside a character never equals a candidate, so the caller can
    keep only the substrings that are candidates.
    """
    found = set()
    low, high = TOKEN_BYTES
    for data in token_runs(row):
        for start in range(len(data) - low + 1):
            for end in range(start + low, min(len(data), start + high) + 1):
                found.add(data[start:end])
    return found


def rank(workload, token):
    return sha256(b"\0".join((b"trino-rows", SEED.encode(), workload.encode(), token)))


def bands():
    """Inclusive bounds on the sampled rows that contain a literal: none 0%, selective 1-5%, common 20-50%."""
    return (("none", 0, 0), ("selective", -(-ROWS // 100), ROWS * 5 // 100), ("common", -(-ROWS // 5), ROWS // 2))


BAND_NAMES = tuple(name for name, _, _ in bands())


def band(hit_rows):
    return next((name for name, low, high in bands() if low <= hit_rows <= high), None)


def starter_terciles(starters, byte_counts):
    """Map each first byte to the third of the starters' cumulative byte count in which its own count ends.

    Ordered from the rarest byte, ties by byte value, so the high tercile is the fewest most frequent
    bytes that together hold at least a third of the occurrences.
    """
    ordered = sorted(starters, key=lambda value: (byte_counts[value], value))
    total = sum(byte_counts[value] for value in ordered)
    terciles = {}
    cumulative = 0
    for value in ordered:
        cumulative += byte_counts[value]
        terciles[value] = TERCILES[max(0, (3 * cumulative - 1) // total)]
    return terciles


def describe(workload, literal_id, group, tercile, band_name, candidates, literal, rows, byte_counts, row_bytes):
    if literal is None:
        return dict(workload=workload, literal_id=literal_id, group=group, tercile=tercile, band=band_name,
                    candidates=str(candidates), literal="", pattern="", first_byte="-", first_byte_count="-",
                    first_byte_frequency="-", hit_rows="-", matches="-")
    first = literal[0]
    return dict(workload=workload, literal_id=literal_id, group=group, tercile=tercile, band=band_name,
                candidates=str(candidates), literal=literal.decode("utf-8"),
                pattern=literal_probe.literal_pattern(literal).decode("utf-8"), first_byte=f"0x{first:02X}",
                first_byte_count=str(byte_counts[first]), first_byte_frequency=f"{byte_counts[first] / row_bytes:.6f}",
                hit_rows=str(sum(literal in row for row in rows)), matches=str(sum(row.count(literal) for row in rows)))


def build_workload(workload, names, picked, corpora, directory):
    loaded = [lines(load(directory, corpora[name])) for name in names]
    populations = [len(rows) for rows in loaded]
    counts = sample_counts(populations)
    sampled, others = [], []
    for rows, count in zip(loaded, counts):
        selected = set(stride_indexes(len(rows), count))
        sampled += [rows[index] for index in sorted(selected)]
        others += [row for index, row in enumerate(rows) if index not in selected]

    lengths = sorted(len(row) for row in sampled)
    row_bytes = sum(lengths)
    byte_counts = [0] * 256
    for row in sampled:
        for value in row:
            byte_counts[value] += 1
    workload_row = dict(
        workload=workload, corpora=",".join(names), sources=",".join(source_reference(corpora[name]) for name in names),
        population_rows=",".join(map(str, populations)), sampled_rows=",".join(map(str, counts)), rows=str(len(sampled)),
        row_bytes=str(row_bytes), rows_sha256=sha256(b"".join(row + b"\n" for row in sampled)),
        p50_bytes=str(percentile(lengths, 50)), p90_bytes=str(percentile(lengths, 90)), p99_bytes=str(percentile(lengths, 99)))

    candidates = set()
    for row in others:
        candidates.update(tokens(row))
    hits = dict.fromkeys(candidates, 0)
    for row in sampled:
        for token in run_substrings(row) & candidates:
            hits[token] += 1
    tercile_of = starter_terciles({token[0] for token in candidates}, byte_counts)
    cells = {}
    for token, hit_rows in hits.items():
        band_name = band(hit_rows)
        if band_name is not None:
            cells.setdefault((tercile_of[token[0]], band_name), []).append(token)

    literal_rows = []
    for tercile in TERCILES:
        for band_name in BAND_NAMES:
            members = cells.get((tercile, band_name), [])
            chosen = min(members, key=lambda token: rank(workload, token)) if members else None
            described = describe(workload, f"{tercile}-{band_name}", "grid", tercile, band_name, len(members), chosen,
                                 sampled, byte_counts, row_bytes)
            if chosen is not None and int(described["hit_rows"]) != hits[chosen]:
                raise ValueError(f"token run count disagrees with substring search: {workload} {chosen!r}")
            literal_rows.append(described)
    for index, literal in enumerate(picked, 1):
        literal_rows.append(describe(workload, f"picked-{index}", "picked", "-", "-", "-", literal.encode("utf-8"),
                                     sampled, byte_counts, row_bytes))
    return workload_row, literal_rows


def render(columns, rows):
    for row in rows:
        for column in columns:
            if "\t" in row[column] or "\n" in row[column]:
                raise ValueError(f"manifest field contains a tab or line feed: {row[column]!r}")
    return "".join("\t".join(row[column] for column in columns) + "\n" for row in [dict(zip(columns, columns)), *rows])


def generate(directory, corpora=None, workloads=WORKLOADS):
    corpora = corpora or corpus_rows()
    workload_rows, literal_rows = [], []
    for workload, names, picked in workloads:
        workload_row, literals = build_workload(workload, names, picked, corpora, directory)
        workload_rows.append(workload_row)
        literal_rows += literals
    return render(WORKLOAD_COLUMNS, workload_rows), render(LITERAL_COLUMNS, literal_rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("fetch", "generate"))
    parser.add_argument("--corpus-directory", type=Path, required=True)
    parser.add_argument("--check", action="store_true", help="compare the generated manifests with the checked-in files")
    arguments = parser.parse_args()
    corpora = corpus_rows()
    if arguments.command == "fetch":
        fetch(arguments.corpus_directory, corpora)
        print(f"verified {sum(row['kind'] != 'resource' for row in corpora.values())} Trino row corpora "
              f"in {arguments.corpus_directory}")
        return
    outputs = dict(zip((WORKLOADS_OUTPUT, LITERALS_OUTPUT), generate(arguments.corpus_directory, corpora)))
    if not arguments.check:
        for path, text in outputs.items():
            path.write_text(text, encoding="utf-8")
        print(f"wrote {', '.join(path.name for path in outputs)}")
        return
    for path, text in outputs.items():
        if not path.is_file() or path.read_text(encoding="utf-8") != text:
            raise SystemExit(f"{path.relative_to(ROOT)} differs from the generated manifest")
    print(f"verified {', '.join(path.name for path in outputs)}")


if __name__ == "__main__":
    main()
