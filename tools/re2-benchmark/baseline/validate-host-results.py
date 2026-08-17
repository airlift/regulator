#!/usr/bin/env python3

import argparse
from pathlib import Path

from acceptance import ValidationError, validate_host_results, write_receipt


def parse_args():
    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Validate one host's exact observed benchmark rows")
    parser.add_argument("--manifest", type=Path, default=directory / "rows.tsv")
    parser.add_argument("--session", type=Path, required=True)
    parser.add_argument("--observed-rows", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    return parser.parse_args()


def main():
    arguments = parse_args()
    try:
        receipt = validate_host_results(arguments.manifest, arguments.session, arguments.observed_rows)
        write_receipt(arguments.receipt, receipt)
    except ValidationError as error:
        raise SystemExit(f"host result rejected: {error}") from error
    print(
        f"accepted {receipt['row_count']} rows for "
        f"{receipt['platform']}/{receipt['shard_id']}/replica-{receipt['replica_id']}")


if __name__ == "__main__":
    main()
