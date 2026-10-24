#!/usr/bin/env python3
"""Record the inputs to the just-built native language benchmark."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys


PINS = {
    "re2": "972a15cedd008d846f1a39b2e88ce48d7f166cbd",
    "abseil-cpp": "d38452e1ee03523a208362186fd42248ff2609f6",
    "benchmark": "192ef10025eb2c4cdd392bc502f0c852196baa48",
}
# Every source that defines a runner's timed work, relative to this directory.
SOURCES = {
    "language_benchmark": ("language_benchmark.cc", "language_harness.h"),
    "language_bulk_benchmark": ("language_bulk_benchmark.cc", "language_bulk_harness.h"),
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources(target):
    directory = Path(__file__).resolve().parent
    return {name: digest(directory / name) for name in SOURCES[target]}


def source_digest(target):
    """Identify a runner by all of its sources, so a shared harness change is a different runner."""
    return hashlib.sha256(json.dumps(sources(target), sort_keys=True).encode()).hexdigest()


def record(build_directory, target="language_benchmark"):
    if target not in SOURCES:
        raise ValueError("unknown native language target")
    binary = build_directory / target
    commands_path = build_directory / "compile_commands.json"
    commands = json.loads(commands_path.read_text())
    runner_commands = [command for command in commands if command["file"].endswith(f"/language/{target}.cc")]
    if len(runner_commands) != 1:
        raise ValueError("missing or ambiguous native compilation command")
    if Path(runner_commands[0]["file"]).resolve() != Path(__file__).resolve().with_name(SOURCES[target][0]):
        raise ValueError("native runner was compiled from another checkout")
    cache_text = (build_directory / "CMakeCache.txt").read_text()
    cache = {}
    for line in cache_text.splitlines():
        if not line.startswith(("#", "//")) and "=" in line:
            key, value = line.split("=", 1)
            cache[key.split(":", 1)[0]] = value
    dependency_keys = {"re2": "RE2_DIR", "abseil-cpp": "ABSL_DIR", "benchmark": "BENCHMARK_DIR"}
    dependencies = {}
    for name, expected in PINS.items():
        directory = Path(cache[dependency_keys[name]])
        actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=directory).decode().strip()
        if actual != expected:
            raise ValueError(f"wrong pinned dependency: {name}")
        # The existing build applies only its benchmark-input patch to the RE2 checkout.
        modified = subprocess.check_output(["git", "diff", "HEAD", "--name-only"], cwd=directory).decode().splitlines()
        if modified and not (name == "re2" and modified == ["re2/testing/regexp_benchmark.cc"]):
            raise ValueError(f"modified native dependency: {name}")
        dependencies[name] = actual
    evidence = {
        "schema_version": 2, "binary_sha256": digest(binary), "source_sha256": source_digest(target),
        "sources": sources(target),
        "dependencies": dependencies, "runner_compile_commands": runner_commands,
        "compile_commands_sha256": digest(commands_path),
        "cmake_cache": cache_text,
        "compiler_version": subprocess.check_output([cache["CMAKE_CXX_COMPILER"], "--version"]).decode(),
    }
    binary.with_suffix(".build.json").write_text(json.dumps(evidence, sort_keys=True) + "\n")


if __name__ == "__main__":
    record(Path(sys.argv[1]).resolve(), sys.argv[2] if len(sys.argv) > 2 else "language_benchmark")
