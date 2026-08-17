#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <pinned-rebar-checkout-or-archive>" >&2
    exit 1
fi

readonly pinned_commit=463d00f31887e84c38467805b9e3122c314b9521
readonly revision_receipt=.regulator-rebar-commit
rebar_root=$(cd "$1" && pwd)

if [[ -e "${rebar_root}/.git" ]]; then
    actual_commit=$(git -C "${rebar_root}" rev-parse HEAD)
elif [[ -f "${rebar_root}/${revision_receipt}" ]]; then
    actual_commit=$(cat "${rebar_root}/${revision_receipt}")
else
    echo "Rebar source has neither Git metadata nor a revision receipt: ${rebar_root}" >&2
    exit 1
fi

if [[ "${actual_commit}" != "${pinned_commit}" ]]; then
    echo "Extended Rebar validation requires ${pinned_commit}, found ${actual_commit}" >&2
    exit 1
fi

printf '%s\n' "${actual_commit}"
