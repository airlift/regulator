#!/usr/bin/env python3

import re
from pathlib import Path


EXPECTED = {"baseline-shard", "language-batch", "engine-build", "unit-worker"}


def shell_modes(path, variable):
    """The modes a wrapper's mode case accepts: its empty branch, before the branch that rejects the rest."""
    source = path.read_text(encoding="utf-8")
    match = re.search(rf'case "\$\{{{variable}\}}" in\n\s+([a-z0-9-]+(?: \| [a-z0-9-]+)*)\) ;;\n\s+\*\)', source)
    if match is None:
        raise SystemExit(f"Could not locate {variable} mode parser in {path}")
    return set(match.group(1).split(" | "))


def main():
    root = Path(__file__).resolve().parents[3]
    campaign_modes = shell_modes(root / "tools/re2-benchmark/aws/run-campaign.sh", "CAMPAIGN_MODE")
    host_modes = shell_modes(root / "tools/re2-benchmark/aws/run-host.sh", "BENCHMARK_MODE")
    for name, actual in (("campaign", campaign_modes), ("host", host_modes)):
        if actual != EXPECTED:
            raise SystemExit(
                f"{name} mode inventory mismatch: missing={sorted(EXPECTED - actual)}, "
                f"unexpected={sorted(actual - EXPECTED)}")
    print(f"validated {len(EXPECTED)} classified benchmark modes")


if __name__ == "__main__":
    main()
