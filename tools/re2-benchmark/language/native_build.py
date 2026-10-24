#!/usr/bin/env python3
"""Record the inputs to the just-built native language benchmark, and check the Cargo
environment before CMake runs Cargo."""

import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


PINS = {
    "re2": "972a15cedd008d846f1a39b2e88ce48d7f166cbd",
    "abseil-cpp": "d38452e1ee03523a208362186fd42248ff2609f6",
    "benchmark": "192ef10025eb2c4cdd392bc502f0c852196baa48",
}
RUST_PINS = {"rustc": "1.97.0", "cargo": "1.97.0"}
RUST_CRATE_DIRECTORY = Path(__file__).resolve().with_name("rust-regex")
# The crate and the CMake rule that runs Cargo on it.
RUST_BUILD_INPUTS = ("rust-regex/language_rust_regex.h", "rust-regex/Cargo.toml", "rust-regex/Cargo.lock",
                     "rust-regex/rust-toolchain.toml", "rust-regex/src/lib.rs", "../CMakeLists.txt")
# Every source that defines a runner's timed work, relative to this directory.
SOURCES = {
    "language_benchmark": ("language_benchmark.cc", "language_harness.h"),
    "language_bulk_benchmark": ("language_bulk_benchmark.cc", "language_bulk_harness.h"),
    "language_rust_benchmark": ("language_rust_benchmark.cc", "language_harness.h", *RUST_BUILD_INPUTS),
    "language_rust_bulk_benchmark": ("language_rust_bulk_benchmark.cc", "language_bulk_harness.h", *RUST_BUILD_INPUTS),
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources(target):
    directory = Path(__file__).resolve().parent
    return {name: digest(directory / name) for name in SOURCES[target]}


def source_digest(target):
    """Identify a runner by all of its sources, so a shared harness change is a different runner."""
    return hashlib.sha256(json.dumps(sources(target), sort_keys=True).encode()).hexdigest()


def lock_packages(text):
    """Read Cargo.lock's registry packages without a TOML parser, which workers lack."""
    packages = {}
    for block in text.split("[[package]]")[1:]:
        fields = dict(line.split(" = ", 1) for line in block.strip().splitlines() if " = " in line)
        if "checksum" in fields:
            packages[json.loads(fields["name"])] = {"version": json.loads(fields["version"]),
                                                    "checksum": json.loads(fields["checksum"])}
    return packages


def check_cargo_environment(crate=RUST_CRATE_DIRECTORY):
    """Reject configuration that would change the comparator's release build; the build runs this before Cargo."""
    # A Cargo configuration file could change the release profile or flags; the build allows none.
    cargo_home = Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo"))
    configurations = [directory / ".cargo" / name for directory in (crate, *crate.parents) for name in ("config", "config.toml")]
    configurations += [cargo_home / "config", cargo_home / "config.toml"]
    if any(path.exists() for path in configurations):
        raise ValueError("Cargo configuration would change the comparator build")
    overrides = sorted(name for name in os.environ if name.startswith(("CARGO_PROFILE_", "CARGO_BUILD_", "RUSTC_", "RUSTFLAGS"))
                       or name in {"CARGO_ENCODED_RUSTFLAGS", "RUSTDOCFLAGS"})
    if overrides:
        raise ValueError(f"environment would change the comparator build: {', '.join(overrides)}")


def rust_evidence(build_directory, cache):
    """Identify the toolchain, the locked crate graph, and the expected Cargo invocation of the comparator
    (the CMake rule, whose hash is in `sources`)."""
    check_cargo_environment()
    rustc = subprocess.check_output([cache["RUSTC_EXECUTABLE"], "-vV"], cwd=RUST_CRATE_DIRECTORY).decode()
    cargo = subprocess.check_output([cache["CARGO_EXECUTABLE"], "-V"], cwd=RUST_CRATE_DIRECTORY).decode()
    # Cargo may follow its version with a commit hash and date.
    if f"release: {RUST_PINS['rustc']}\n" not in rustc or cargo.split()[:2] != ["cargo", RUST_PINS["cargo"]]:
        raise ValueError("wrong pinned Rust toolchain")
    packages = lock_packages((RUST_CRATE_DIRECTORY / "Cargo.lock").read_text())
    with (RUST_CRATE_DIRECTORY.parents[1] / "baseline/comparators.tsv").open() as file:
        pin = next(row for row in csv.DictReader(file, delimiter="\t") if row["component"] == "rust-regex")
    if packages["regex"] != {"version": pin["version_or_revision"], "checksum": pin["artifact_sha256"]}:
        raise ValueError("Cargo.lock does not resolve the pinned regex crate")
    manifest = (RUST_CRATE_DIRECTORY / "Cargo.toml").read_text()
    profile = manifest.split("[profile.release]\n", 1)[1].split("\n[", 1)[0]
    staticlib = build_directory / "rust-regex/release/liblanguage_rust_regex.a"
    return {
        "rustc_version": rustc, "cargo_version": cargo,
        "cargo_lock_sha256": digest(RUST_CRATE_DIRECTORY / "Cargo.lock"), "crates": packages,
        "cargo_command": ["cargo", "build", "--release", "--locked"],
        "cargo_environment": {"CARGO_TARGET_DIR": str(build_directory / "rust-regex"),
                              "RUSTC": cache["RUSTC_EXECUTABLE"], "CARGO_ENCODED_RUSTFLAGS": ""},
        "release_profile": profile, "rustflags": [], "staticlib_sha256": digest(staticlib),
    }


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
    if target.startswith("language_rust_"):
        evidence["rust"] = rust_evidence(build_directory, cache)
    binary.with_suffix(".build.json").write_text(json.dumps(evidence, sort_keys=True) + "\n")


if __name__ == "__main__":
    if sys.argv[1:] == ["--check-cargo-environment"]:
        check_cargo_environment()
    else:
        record(Path(sys.argv[1]).resolve(), sys.argv[2] if len(sys.argv) > 2 else "language_benchmark")
