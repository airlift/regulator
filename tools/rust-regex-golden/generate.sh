#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
DEPENDENCY_DIR=${RUST_REGEX_GOLDEN_DEPENDENCY_DIR:-"${ROOT_DIR}/target/rust-regex-golden-dependencies"}
BUILD_DIR=${RUST_REGEX_GOLDEN_BUILD_DIR:-"${ROOT_DIR}/target/rust-regex-golden-build"}
# Cargo builds from the tool directory, but the generator runs here, so both
# must see the same build directory.
if [[ "${BUILD_DIR}" != /* ]]; then
    BUILD_DIR="${PWD}/${BUILD_DIR}"
fi
RESOURCE_DIR="${ROOT_DIR}/src/test/resources/io/airlift/regulator"

# Fetches the pinned checkout, or verifies the one supplied in RUST_REGEX_DIR.
"${SCRIPT_DIR}/fetch-dependencies.sh"
RUST_REGEX_DIR=${RUST_REGEX_DIR:-"${DEPENDENCY_DIR}/regex"}

# rustup selects the pinned toolchain from rust-toolchain.toml in the current
# directory, so build from the tool directory rather than with --manifest-path.
(
    cd "${SCRIPT_DIR}"
    rustc --version
    cargo build --release --locked --target-dir "${BUILD_DIR}"
)
GENERATOR="${BUILD_DIR}/release/rust-regex-golden"

# regex-test filters its runner by these variables, so the generator refuses to
# run while either is set; clear them so the caller's environment cannot fail
# the run.
unset REGEX_TEST REGEX_TEST_VERBOSE

# Write each fixture to a temporary file first so a failed generation never
# truncates a committed fixture.
generate()
{
    local output=$1
    shift
    local temporary="${BUILD_DIR}/$(basename "${output}").tmp"
    "${GENERATOR}" "$@" > "${temporary}"
    mv "${temporary}" "${output}"
    printf 'Wrote %s\n' "${output}"
}

generate "${RESOURCE_DIR}/rust-regex-syntax.jsonl" --mode=syntax < "${SCRIPT_DIR}/syntax-cases.tsv"
generate "${RESOURCE_DIR}/rust-regex-match.jsonl" --mode=match < "${SCRIPT_DIR}/match-cases.tsv"
generate "${RESOURCE_DIR}/rust-regex-random.jsonl" --mode=match < "${SCRIPT_DIR}/random-cases.tsv"
generate "${RESOURCE_DIR}/rust-regex-upstream.jsonl" --mode=upstream "--testdata=${RUST_REGEX_DIR}/testdata" < /dev/null
