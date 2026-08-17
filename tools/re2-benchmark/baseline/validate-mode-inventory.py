#!/usr/bin/env python3

import re
from pathlib import Path


EXPECTED = {"baseline-shard", "language-batch"}


def shell_modes(path, variable):
    source = path.read_text(encoding="utf-8")
    guard = rf'"\$\{{{variable}\}}" != [a-z0-9-]+'
    match = re.search(rf'if \[\[ ({guard}(?: && {guard})*) \]\]; then', source)
    if match is None:
        raise SystemExit(f"Could not locate {variable} mode parser in {path}")
    return set(re.findall(r'!= ([a-z0-9-]+)', match.group(1)))


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
