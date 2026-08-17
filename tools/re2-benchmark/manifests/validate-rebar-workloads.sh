#!/usr/bin/env bash

set -euo pipefail

script_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
workload_manifest="${script_directory}/rebar-workloads.csv"
selected_manifest="${script_directory}/rebar-selected-workloads.tsv"
extended_manifest="${script_directory}/rebar-extended-workloads.tsv"

sha256()
{
    if command -v sha256sum >/dev/null; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

verify_checksum()
{
    local file=$1
    local checksum_file=$2
    local expected
    expected=$(awk '{print $1}' "${checksum_file}")
    if [[ ! "${expected}" =~ ^[0-9a-f]{64}$ ]]; then
        echo "Invalid checksum in ${checksum_file}" >&2
        exit 1
    fi
    if [[ "$(sha256 "${file}")" != "${expected}" ]]; then
        echo "Checksum mismatch for ${file}" >&2
        exit 1
    fi
}

verify_checksum "${workload_manifest}" "${script_directory}/rebar-workloads.sha256"
verify_checksum "${selected_manifest}" "${script_directory}/rebar-selected-workloads.sha256"
verify_checksum "${extended_manifest}" "${script_directory}/rebar-extended-workloads.sha256"

if [[ $(wc -l < "${workload_manifest}") -ne 42 ]]; then
    echo "Expected 41 Rebar workloads in ${workload_manifest}" >&2
    exit 1
fi
if [[ $(wc -l < "${selected_manifest}") -ne 11 ]]; then
    echo "Expected 10 selected Rebar workloads in ${selected_manifest}" >&2
    exit 1
fi
if [[ $(wc -l < "${extended_manifest}") -ne 198 ]]; then
    echo "Expected 197 extended Rebar workloads in ${extended_manifest}" >&2
    exit 1
fi

expected_workload_header='name,model,case_insensitive,unicode,pattern_length,pattern_sha256,haystack_length,haystack_sha256,expected_result,result_demand'
if [[ "$(head -n 1 "${workload_manifest}")" != "${expected_workload_header}" ]]; then
    echo "Unexpected Rebar workload manifest header" >&2
    exit 1
fi

expected_selected_header=$'benchmark_name\toutput_file\tuncompressed_length\tuncompressed_sha256'
if [[ "$(head -n 1 "${selected_manifest}")" != "${expected_selected_header}" ]]; then
    echo "Unexpected selected Rebar workload manifest header" >&2
    exit 1
fi

expected_extended_header=$'name\tmodel\tdefinition\tdefinition_sha256'
if [[ "$(head -n 1 "${extended_manifest}")" != "${expected_extended_header}" ]]; then
    echo "Unexpected extended Rebar workload manifest header" >&2
    exit 1
fi

awk -F '\t' '
    NR == 1 { next }
    NF != 4 || $1 == "" ||
            $2 !~ /^(compile|count|count-spans|count-captures|grep|grep-captures)$/ ||
            $3 !~ /^[A-Za-z0-9_.\/-]+\.toml$/ || $4 !~ /^[0-9a-f]{64}$/ {
        printf "Invalid extended Rebar workload at line %d\n", NR > "/dev/stderr"
        exit 1
    }
' "${extended_manifest}"

if [[ $(tail -n +2 "${extended_manifest}" | cut -f1 | sort | uniq -d | wc -l | tr -d ' ') -ne 0 ]]; then
    echo "Extended Rebar workload names must be unique" >&2
    exit 1
fi

awk -F '\t' '
    NR == 1 { next }
    NF != 4 || $1 == "" || $2 !~ /^[A-Za-z0-9_.-]+\.klv\.gz$/ ||
            $3 !~ /^[1-9][0-9]*$/ || $4 !~ /^[0-9a-f]{64}$/ {
        printf "Invalid selected Rebar workload at line %d\n", NR > "/dev/stderr"
        exit 1
    }
' "${selected_manifest}"

if [[ $# -gt 1 ]]; then
    echo "Usage: $0 [generated-workload-manifest]" >&2
    exit 1
fi
if [[ $# -eq 0 ]]; then
    exit 0
fi

generated_manifest=$1
if [[ ! -f "${generated_manifest}" ]]; then
    echo "Generated Rebar workload manifest does not exist: ${generated_manifest}" >&2
    exit 1
fi

generated_sources=$(mktemp)
trap 'rm -f "${generated_sources}"' EXIT
cut -d, -f1-10 "${generated_manifest}" > "${generated_sources}"
if ! cmp -s "${workload_manifest}" "${generated_sources}"; then
    echo "Generated Rebar workloads differ from the checked-in manifest" >&2
    diff -u "${workload_manifest}" "${generated_sources}" >&2 || true
    exit 1
fi
