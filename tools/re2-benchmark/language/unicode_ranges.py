#!/usr/bin/env python3
"""Regenerate or check the two bulk-workload classes against pinned RE2 source."""

import argparse
import re
from pathlib import Path

import collection


RE2_COMMIT = "972a15cedd008d846f1a39b2e88ce48d7f166cbd"


def generate(source):
    payload = source.read_bytes()
    text = payload.decode("ascii")
    ranges = {}
    for name in ("L", "Greek"):
        entries = []
        for width in (16, 32):
            match = re.search(r"static const URange" + str(width) + " " + name +
                              r"_range" + str(width) + r"\[\] = \{(.*?)\};", text, re.DOTALL)
            if match is None:
                raise ValueError("missing RE2 Unicode range: " + name)
            part = [[int(low), int(high)] for low, high in re.findall(r"\{\s*(\d+),\s*(\d+)\s*\}", match[1])]
            if not part or any(not 0 <= low <= high <= 0x10FFFF for low, high in part):
                raise ValueError("invalid RE2 Unicode range: " + name)
            entries.extend(part)
        if any(left[1] >= right[0] for left, right in zip(entries, entries[1:])):
            raise ValueError("overlapping RE2 Unicode ranges")
        ranges[name] = entries
    return {"ranges": ranges, "re2_commit": RE2_COMMIT, "source": "re2/unicode_groups.cc",
            "source_sha256": collection.digest(payload)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("unicode-ranges.json"))
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    generated = generate(args.source)
    if args.check:
        if collection.load(args.output) != generated:
            raise ValueError("Unicode workload ranges differ from pinned source")
    else:
        collection.save(args.output, generated)


if __name__ == "__main__":
    main()
