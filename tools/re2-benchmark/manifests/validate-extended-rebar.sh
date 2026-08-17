#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <pinned-rebar-checkout>" >&2
    exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REBAR_ROOT=$(cd "$1" && pwd)
MANIFEST="${SCRIPT_DIR}/rebar-extended-workloads.tsv"
EXCLUSIONS="${SCRIPT_DIR}/rebar-exclusions.tsv"

"${SCRIPT_DIR}/rebar-revision.sh" "${REBAR_ROOT}" >/dev/null

python3 "${SCRIPT_DIR}/generate-extended-rebar.py" \
    "${REBAR_ROOT}" \
    "${MANIFEST}" \
    --exclusions-output "${EXCLUSIONS}" \
    --check

sha256()
{
    if command -v sha256sum >/dev/null; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

for protected_file in "${MANIFEST}" "${EXCLUSIONS}"; do
    checksum_file="${protected_file%.*}.sha256"
    expected_checksum=$(awk '{print $1}' "${checksum_file}")
    actual_checksum=$(sha256 "${protected_file}")
    if [[ "${actual_checksum}" != "${expected_checksum}" ]]; then
        echo "Rebar manifest checksum mismatch: ${protected_file}" >&2
        exit 1
    fi
done

definition_manifest=$(mktemp)
trap 'rm -f "${definition_manifest}"' EXIT
tail -n +2 "${MANIFEST}" | cut -f3-4 | sort -u > "${definition_manifest}"
if [[ $(cut -f1 "${definition_manifest}" | sort | uniq -d | wc -l | tr -d ' ') -ne 0 ]]; then
    echo "Extended Rebar definitions contain conflicting checksums" >&2
    exit 1
fi

definition_count=0
while IFS=$'\t' read -r definition expected_sha256; do
    source_file="${REBAR_ROOT}/benchmarks/definitions/${definition}"
    if [[ ! -f "${source_file}" ]]; then
        echo "Extended Rebar definition does not exist: ${definition}" >&2
        exit 1
    fi
    actual_sha256=$(sha256 "${source_file}")
    if [[ "${actual_sha256}" != "${expected_sha256}" ]]; then
        echo "Extended Rebar definition changed: ${definition}" >&2
        exit 1
    fi
    definition_count=$((definition_count + 1))
done < "${definition_manifest}"

echo "Validated 197 extended Rebar rows and 61 explicit exclusions against ${definition_count} pinned definition files"
