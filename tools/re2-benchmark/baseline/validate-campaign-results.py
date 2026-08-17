#!/usr/bin/env python3

import argparse
from pathlib import Path

from acceptance import ValidationError, validate_campaign


def parse_args():
    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Validate exact accepted-session coverage for a baseline campaign")
    parser.add_argument("--campaign-id", required=True)
    parser.add_argument("--manifest", type=Path, default=directory / "rows.tsv")
    parser.add_argument("--platforms", type=Path, default=directory / "platforms.tsv")
    parser.add_argument("--shards", type=Path, default=directory / "shards.tsv")
    parser.add_argument("--shard", action="append", dest="selected_shards")
    parser.add_argument("--accepted-sessions", type=Path, required=True)
    return parser.parse_args()


def main():
    arguments = parse_args()
    try:
        result = validate_campaign(
            arguments.manifest,
            arguments.platforms,
            arguments.shards,
            arguments.accepted_sessions,
            arguments.campaign_id,
            selected_shards=arguments.selected_shards)
    except ValidationError as error:
        raise SystemExit(f"campaign rejected: {error}") from error
    print(
        f"accepted campaign {result['campaign_id']}: {result['accepted_sessions']} host sessions, "
        f"{result['accepted_rows']} measured rows across {result['platform_count']} platforms and "
        f"{result['shard_count']} shards")


if __name__ == "__main__":
    main()
