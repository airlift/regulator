#!/usr/bin/env python3

import argparse
from pathlib import Path

import row_protocol


def main():
    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=directory / "row-protocol.tsv")
    arguments = parser.parse_args()
    protocols = row_protocol.generate_from(directory)
    arguments.output.write_text(row_protocol.render(protocols), encoding="utf-8")
    print(f"wrote {len(protocols)} frozen row protocols")


if __name__ == "__main__":
    main()
