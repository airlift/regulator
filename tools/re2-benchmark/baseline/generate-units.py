#!/usr/bin/env python3

import argparse
from pathlib import Path

import row_protocol
import work_units


def main():
    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-directory", type=Path, default=directory)
    arguments = parser.parse_args()
    units = work_units.generate_from(directory)
    unit_table, unit_rows = work_units.render(units)
    (arguments.output_directory / "units.tsv").write_text(unit_table, encoding="utf-8")
    (arguments.output_directory / "unit-rows.tsv").write_text(unit_rows, encoding="utf-8")
    print(work_units.summary(units, row_protocol.read_tsv(directory / "platforms.tsv")))
    print(f"wrote {len(units)} work units")


if __name__ == "__main__":
    main()
