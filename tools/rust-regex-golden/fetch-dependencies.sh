#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
DEPENDENCY_DIR=${RUST_REGEX_GOLDEN_DEPENDENCY_DIR:-"${ROOT_DIR}/target/rust-regex-golden-dependencies"}

REGEX_REPOSITORY=https://github.com/rust-lang/regex.git
REGEX_TAG=1.13.1
REGEX_COMMIT=2b527599eb9eea0dcc288c704584f242f26a5c61

# A checkout supplied in RUST_REGEX_DIR is verified, but never fetched or changed.
directory=${RUST_REGEX_DIR:-"${DEPENDENCY_DIR}/regex"}
if [[ -z "${RUST_REGEX_DIR:-}" ]]; then
    if [[ ! -d "${directory}/.git" ]]; then
        rm -rf "${directory}"
        git init -q "${directory}"
        git -C "${directory}" remote add origin "${REGEX_REPOSITORY}"
    fi

    git -C "${directory}" fetch -q --depth=1 origin "+refs/tags/${REGEX_TAG}:refs/tags/${REGEX_TAG}"
    tag_commit=$(git -C "${directory}" rev-parse "refs/tags/${REGEX_TAG}^{commit}")
    if [[ "${tag_commit}" != "${REGEX_COMMIT}" ]]; then
        echo "Expected tag ${REGEX_TAG} to resolve to ${REGEX_COMMIT}, found ${tag_commit}" >&2
        exit 1
    fi
    git -C "${directory}" checkout -q --detach "${REGEX_COMMIT}"
fi

actual_commit=$(git -C "${directory}" rev-parse HEAD)
if [[ "${actual_commit}" != "${REGEX_COMMIT}" ]]; then
    echo "Expected ${REGEX_COMMIT}, found ${actual_commit} in ${directory}" >&2
    exit 1
fi

# The upstream fixture reads every TOML file under testdata, so a local edit or
# an extra file there would change it.
changed_testdata=$(git -C "${directory}" status --porcelain --untracked-files=all --ignored -- testdata)
if [[ -n "${changed_testdata}" ]]; then
    echo "Modified or untracked test data in ${directory}:" >&2
    printf '%s\n' "${changed_testdata}" >&2
    exit 1
fi

printf 'RUST_REGEX_DIR=%s\n' "${directory}"
