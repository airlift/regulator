#!/usr/bin/env python3

import argparse
from pathlib import Path

import row_protocol
import work_units


def main():
    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Write the frozen row protocols and work units")
    parser.add_argument("--output-directory", type=Path, default=directory)
    arguments = parser.parse_args()
    protocols = row_protocol.generate_from(directory)
    units = work_units.generate_from(directory)
    unit_table, unit_rows = work_units.render(units)
    tables = dict(zip(work_units.TABLES, (row_protocol.render(protocols), unit_table, unit_rows)))
    for name, content in tables.items():
        (arguments.output_directory / name).write_text(content, encoding="utf-8")
    print(work_units.summary(units, row_protocol.read_tsv(directory / "platforms.tsv")))
    print(f"wrote {len(protocols)} frozen row protocols and {len(units)} work units")


if __name__ == "__main__":
    main()
