#!/usr/bin/env python3

import argparse
from pathlib import Path

from unit_session import validate_unit_results, write_unit_receipt


def parse_args():
    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Validate one work unit attempt's exact observed benchmark rows")
    parser.add_argument("--manifest", type=Path, default=directory / "rows.tsv")
    parser.add_argument("--row-protocol", type=Path, default=directory / "row-protocol.tsv")
    parser.add_argument("--unit-directory", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--host-evidence", type=Path)
    return parser.parse_args()


def main():
    arguments = parse_args()
    try:
        receipt = validate_unit_results(
            arguments.unit_directory, arguments.manifest, arguments.row_protocol, arguments.host_evidence)
        write_unit_receipt(arguments.receipt, receipt)
    except (OSError, ValueError) as error:
        raise SystemExit(f"unit result rejected: {error}") from error
    print(
        f"accepted unit {receipt['unit_id']} with {receipt['owned_row_count']} owned rows for "
        f"{receipt['platform']}/{receipt['shard_id']}/replica-{receipt['replica_id']}")


if __name__ == "__main__":
    main()
