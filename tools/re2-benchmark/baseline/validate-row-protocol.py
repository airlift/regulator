#!/usr/bin/env python3

from pathlib import Path

import row_protocol


def main():
    directory = Path(__file__).resolve().parent
    path = directory / "row-protocol.tsv"
    try:
        protocols = row_protocol.generate_from(directory)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    if path.read_text(encoding="utf-8") != row_protocol.render(protocols):
        raise SystemExit(f"{path.name} does not match the frozen protocol rules; run generate-row-protocol.py")
    print(f"validated {len(protocols)} frozen row protocols")


if __name__ == "__main__":
    main()
