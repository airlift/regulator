#!/usr/bin/env python3
"""Run offline benchmark-tool tests and manifest checks without collecting timings."""

import os
from pathlib import Path
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parent
NATIVE_TEST_ENVIRONMENT = ("LANGUAGE_NATIVE_RUNNER", "LANGUAGE_NATIVE_BULK_RUNNER")


def main():
    if sys.version_info < (3, 11):
        raise SystemExit("offline tooling validation requires Python 3.11 or newer")
    environment = dict(os.environ)
    # Native integration tests are opt-in outside this offline CI gate.
    for name in NATIVE_TEST_ENVIRONMENT:
        environment.pop(name, None)
    started = time.monotonic()
    directories = sorted({path.parent for path in ROOT.rglob("test_*.py")})
    if not directories:
        raise ValueError("no benchmark tooling tests found")
    for directory in directories:
        print(f"Testing {directory.relative_to(ROOT)}", flush=True)
        # Separate discovery processes avoid collisions between sibling helper modules.
        subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", str(directory),
                        "-p", "test_*.py"], cwd=ROOT.parents[1], env=environment,
                       check=True, timeout=300)
    for validator in ("validate-manifest.py", "validate-mode-inventory.py",
                      "validate-protocol-representatives.py", "validate-shard-plans.py"):
        print(f"Validating {validator}", flush=True)
        subprocess.run([sys.executable, str(ROOT / "baseline" / validator)],
                       cwd=ROOT.parents[1], env=environment, check=True, timeout=300)
    print(f"Offline tooling validation passed in {time.monotonic() - started:.1f}s", flush=True)


if __name__ == "__main__":
    main()
