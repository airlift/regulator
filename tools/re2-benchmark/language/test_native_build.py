import csv
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))
import native_build


ROOT = Path(__file__).resolve().parents[3]


class TestNativeBuild(unittest.TestCase):
    def test_rust_comparator_locks_the_golden_crate_graph(self):
        # The comparator must run the exact crates the Rust goldens were generated with.
        comparator = native_build.lock_packages((Path(__file__).parent / "rust-regex/Cargo.lock").read_text())
        golden = native_build.lock_packages((ROOT / "tools/rust-regex-golden/Cargo.lock").read_text())
        self.assertEqual(set(comparator), {"aho-corasick", "memchr", "regex", "regex-automata", "regex-syntax"})
        self.assertEqual(comparator, {name: golden[name] for name in comparator})
        with (ROOT / "tools/re2-benchmark/baseline/comparators.tsv").open() as file:
            pin = next(row for row in csv.DictReader(file, delimiter="\t") if row["component"] == "rust-regex")
        self.assertEqual(comparator["regex"], {"version": pin["version_or_revision"], "checksum": pin["artifact_sha256"]})

    def test_rust_build_rejects_unpinned_toolchain(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "rust-regex/release").mkdir(parents=True)
            (root / "rust-regex/release/liblanguage_rust_regex.a").write_bytes(b"library")

            def tool(name, output):
                path = root / name
                path.write_text(f"#!/bin/sh\nprintf '{output}\\n'\n")
                path.chmod(0o755)
                return str(path)

            rustc = tool("rustc", "rustc 1.97.0\\nrelease: 1.97.0")
            # The Cargo environment has its own tests; its real check would read the checkout's parent directories.
            with patch.object(native_build, "check_cargo_environment") as check_cargo_environment:
                # Cargo may or may not follow its version with a commit hash and date.
                for version in ("cargo 1.97.0", "cargo 1.97.0 (0123abcd 2026-08-01)"):
                    cache = {"RUSTC_EXECUTABLE": rustc, "CARGO_EXECUTABLE": tool("cargo", version)}
                    self.assertEqual(native_build.rust_evidence(root, cache)["cargo_version"], version + "\n")
                check_cargo_environment.assert_called_with()
                for rustc_version, cargo_version in (("release: 1.96.0", "cargo 1.97.0"), ("release: 1.97.0", "cargo 1.97.1"),
                                                     ("release: 1.97.0", "cargo 1.97.0-nightly")):
                    cache = {"RUSTC_EXECUTABLE": tool("rustc", rustc_version), "CARGO_EXECUTABLE": tool("cargo", cargo_version)}
                    with self.subTest(rustc=rustc_version, cargo=cargo_version), \
                            self.assertRaisesRegex(ValueError, "wrong pinned Rust toolchain"):
                        native_build.rust_evidence(root, cache)

    def test_rust_build_rejects_environment_overrides(self):
        with tempfile.TemporaryDirectory() as directory:
            crate = Path(directory) / "rust-regex"
            environment = {"PATH": os.environ["PATH"], "CARGO_HOME": str(Path(directory) / "cargo-home")}
            with patch.dict(os.environ, environment, clear=True):
                native_build.check_cargo_environment(crate)
            for name, value in (("CARGO_PROFILE_RELEASE_OPT_LEVEL", "0"), ("RUSTFLAGS", "-Copt-level=0")):
                with self.subTest(variable=name), patch.dict(os.environ, {**environment, name: value}, clear=True), \
                        self.assertRaisesRegex(ValueError, f"environment would change the comparator build: {name}"):
                    native_build.check_cargo_environment(crate)

    def test_rust_build_rejects_cargo_configuration(self):
        for location in ("cargo-home", "workspace/.cargo"):
            with self.subTest(location=location), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                crate = root / "workspace/rust-regex"
                (root / location).mkdir(parents=True)
                (root / location / "config.toml").write_text("[profile.release]\nopt-level = 0\n")
                with patch.dict(os.environ, {"PATH": os.environ["PATH"], "CARGO_HOME": str(root / "cargo-home")}, clear=True), \
                        self.assertRaisesRegex(ValueError, "Cargo configuration would change"):
                    native_build.check_cargo_environment(crate)


if __name__ == "__main__":
    unittest.main()
