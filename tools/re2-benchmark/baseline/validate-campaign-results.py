#!/usr/bin/env python3

import argparse
import sys
from pathlib import Path

from acceptance import ValidationError
from unit_dispatch import read_accepted_units, unit_attempt_directory
from unit_session import check_semantic_rejections, validate_unit_campaign

BASELINE = Path(__file__).resolve().parent
sys.path.insert(0, str(BASELINE.parent / "language"))
import released_artifact  # noqa: E402


def parse_args():
    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(
        description="Validate exact accepted unit coverage for a baseline campaign")
    parser.add_argument("--campaign-id", required=True)
    parser.add_argument("--manifest", type=Path, default=directory / "rows.tsv")
    parser.add_argument("--platforms", type=Path, default=directory / "platforms.tsv")
    parser.add_argument("--shards", type=Path, default=directory / "shards.tsv")
    parser.add_argument("--shard", action="append", dest="selected_shards")
    parser.add_argument("--accepted-units", type=Path, required=True)
    parser.add_argument("--release-version", default="",
                        help="require every accepted attempt, retained beside the ledger, to prove it measured "
                             "this release's JAR")
    return parser.parse_args()


def check_release(accepted_units, version):
    """Each accepted attempt must prove that every route it ran measured the release's JAR."""
    release = released_artifact.manifest(BASELINE.parents[2], version)
    for receipt in read_accepted_units(accepted_units):
        try:
            released_artifact.validate_baseline(unit_attempt_directory(accepted_units.parent, receipt), release)
        except (OSError, ValueError, KeyError) as error:
            raise ValidationError(
                f"unit attempt {receipt['unit_attempt_id']} does not prove release {version}: {error}") from error


def main():
    arguments = parse_args()
    try:
        # A phase whose correctness stage rejected any attempt is refused, as the controller refuses it.
        check_semantic_rejections(arguments.accepted_units.parent)
        result = validate_unit_campaign(
            arguments.manifest,
            arguments.platforms,
            arguments.shards,
            arguments.accepted_units,
            arguments.campaign_id,
            selected_shards=arguments.selected_shards)
        if arguments.release_version:
            check_release(arguments.accepted_units, arguments.release_version)
    except ValidationError as error:
        raise SystemExit(f"campaign rejected: {error}") from error
    print(
        f"accepted campaign {result['campaign_id']}: {result['accepted_attempts']} unit attempts of "
        f"{result['unit_count']} units, {result['accepted_rows']} owned rows across "
        f"{result['platform_count']} platforms and {result['shard_count']} shards")


if __name__ == "__main__":
    main()
