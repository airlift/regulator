#!/usr/bin/env bash
set -euo pipefail

# Prepare and verify every semantic route of one shard on a diagnostic or
# remeasurement host, which then measures its own plan; no timing is collected here.
if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <shard-id> <result-directory>" >&2
    exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
SHARD_ID=$1
RESULT_DIR=$2
RUN_SHARD="${SCRIPT_DIR}/run-shard.sh"
HOST_TOOL="${SCRIPT_DIR}/host_session.py"
DISPATCH="${SCRIPT_DIR}/shard-dispatch.tsv"
SHARED_WORK_DIR=${BASELINE_SHARED_WORK_DIR:-${ROOT}/target/baseline-shared}

mkdir -p "${RESULT_DIR}"
RESULT_DIR=$(cd "${RESULT_DIR}" && pwd)
for output in routes route-plan.tsv semantic-preparation.json status.txt; do
    if [[ -e "${RESULT_DIR}/${output}" ]]; then
        echo "Host-session output already exists: ${RESULT_DIR}/${output}" >&2
        exit 1
    fi
done

status_file="${RESULT_DIR}/status.txt"
printf 'status=running\n' > "${status_file}"
finish()
{
    local exit_code=$?
    if [[ ${exit_code} -eq 0 ]]; then
        printf 'status=complete\n' > "${status_file}"
    else
        printf 'status=failed\nexit_code=%s\n' "${exit_code}" > "${status_file}"
    fi
}
trap finish EXIT

environment_value()
{
    local key=$1
    local file
    for file in "${RESULT_DIR}/environment-manifest.txt" "${RESULT_DIR}/environment.txt"; do
        if [[ -f "${file}" ]]; then
            awk -F= -v key="${key}" '$1 == key {print substr($0, length($1) + 2); exit}' "${file}"
        fi
    done | head -1
}

export RE2_CAMPAIGN_ID=${RE2_CAMPAIGN_ID:-$(environment_value baseline_campaign)}
export RE2_CAMPAIGN_PLATFORM=${RE2_CAMPAIGN_PLATFORM:-$(environment_value platform)}
export RE2_CAMPAIGN_SHARD_ID=${RE2_CAMPAIGN_SHARD_ID:-$(environment_value shard)}
export RE2_CAMPAIGN_REPLICA_ID=${RE2_CAMPAIGN_REPLICA_ID:-$(environment_value replica)}
export RE2_CAMPAIGN_HOST_EPOCH=${RE2_CAMPAIGN_HOST_EPOCH:-$(environment_value host_epoch)}
export RE2_ENGINEERING_ARCHITECTURE=${RE2_ENGINEERING_ARCHITECTURE:-$(environment_value campaign_architecture)}
export BASELINE_INSTANCE_ID=${BASELINE_INSTANCE_ID:-$(environment_value instance_id)}
export BASELINE_INSTANCE_TYPE=${BASELINE_INSTANCE_TYPE:-$(environment_value instance_type)}
export BASELINE_AVAILABILITY_ZONE=${BASELINE_AVAILABILITY_ZONE:-$(environment_value availability_zone)}
if [[ "${RE2_CAMPAIGN_SHARD_ID}" != "${SHARD_ID}" ]]; then
    echo "Host identity shard ${RE2_CAMPAIGN_SHARD_ID:-unset} does not match requested shard ${SHARD_ID}" >&2
    exit 1
fi
for value_name in RE2_CAMPAIGN_ID RE2_CAMPAIGN_PLATFORM RE2_CAMPAIGN_REPLICA_ID \
        RE2_CAMPAIGN_HOST_EPOCH RE2_ENGINEERING_ARCHITECTURE BASELINE_INSTANCE_ID BASELINE_INSTANCE_TYPE; do
    if [[ -z ${!value_name} || ${!value_name} == unknown || ${!value_name} == standalone ]]; then
        echo "Missing formal host identity: ${value_name}" >&2
        exit 1
    fi
done
if [[ -z ${BASELINE_AVAILABILITY_ZONE} || ${BASELINE_AVAILABILITY_ZONE} == unknown || \
        ${BASELINE_AVAILABILITY_ZONE} == standalone ]]; then
    echo "Missing formal host identity: BASELINE_AVAILABILITY_ZONE" >&2
    exit 1
fi
if [[ ! ${RE2_CAMPAIGN_REPLICA_ID} =~ ^[1-4]$ ]]; then
    echo "Invalid formal replica identity: ${RE2_CAMPAIGN_REPLICA_ID}" >&2
    exit 1
fi

python3 "${HOST_TOOL}" routes \
    --dispatch "${DISPATCH}" \
    --shard "${SHARD_ID}" > "${RESULT_DIR}/route-plan.tsv"
if ((RE2_CAMPAIGN_REPLICA_ID % 2 == 0)); then
    awk -F '\t' '$1 == "object-row" {print} $1 == "native-access" {native=$0} END {if (native) print native}' \
        "${RESULT_DIR}/route-plan.tsv" > "${RESULT_DIR}/route-plan.ordered.tsv"
    mv "${RESULT_DIR}/route-plan.ordered.tsv" "${RESULT_DIR}/route-plan.tsv"
fi

mkdir -p "${RESULT_DIR}/routes"

# Odd replicas run native access first; even replicas reverse the order so
# route startup and thermal effects do not systematically favor one layout.
while IFS=$'\t' read -r route _; do
    route_directory="${RESULT_DIR}/routes/${route}"
    mkdir -p "${route_directory}"
    for environment_file in environment-manifest.txt environment.txt engine-build.txt; do
        if [[ -f "${RESULT_DIR}/${environment_file}" ]]; then
            cp "${RESULT_DIR}/${environment_file}" "${route_directory}/${environment_file}"
        fi
    done
    BASELINE_DEFER_ACCEPTANCE=true \
    BASELINE_VERIFICATION_ONLY=true \
    BASELINE_PROTOCOL_QUALIFICATION=false \
    BASELINE_SHARED_WORK_DIR="${SHARED_WORK_DIR}" \
        "${RUN_SHARD}" "${SHARD_ID}" smoke "${route}" "${route_directory}"
done < "${RESULT_DIR}/route-plan.tsv"

python3 "${SCRIPT_DIR}/semantic_preparation.py" --session "${RESULT_DIR}" --shard "${SHARD_ID}"
echo "Completed semantic preparation ${SHARD_ID}"
