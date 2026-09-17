#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "Usage: $0 <shard-id> <smoke|qualification> <result-directory>" >&2
    exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
SHARD_ID=$1
PROTOCOL=$2
RESULT_DIR=$3
RUN_SHARD="${SCRIPT_DIR}/run-shard.sh"
HOST_TOOL="${SCRIPT_DIR}/host_session.py"
MANIFEST="${SCRIPT_DIR}/rows.tsv"
DISPATCH="${SCRIPT_DIR}/shard-dispatch.tsv"
SHARED_WORK_DIR=${BASELINE_SHARED_WORK_DIR:-${ROOT}/target/baseline-shared}
HOST_PLAN_ONLY=${BASELINE_HOST_PLAN_ONLY:-false}
SELECTED_ROUTE=${BASELINE_SELECTED_ROUTE:-}
PROTOCOL_QUALIFICATION=${BASELINE_PROTOCOL_QUALIFICATION:-true}
VERIFICATION_ONLY=${BASELINE_VERIFICATION_ONLY:-false}
case "${VERIFICATION_ONLY}" in true | false) ;; *) exit 1 ;; esac
if [[ "${VERIFICATION_ONLY}" == true && ( "${PROTOCOL}" != smoke || "${PROTOCOL_QUALIFICATION}" != false || -n "${SELECTED_ROUTE}" || "${HOST_PLAN_ONLY}" == true ) ]]; then
    echo "Semantic preparation requires every route and no timing qualification" >&2
    exit 1
fi

case "${PROTOCOL}" in
    smoke | qualification) ;;
    *)
        echo "Protocol must be smoke or qualification: ${PROTOCOL}" >&2
        exit 1
        ;;
esac
case "${HOST_PLAN_ONLY}" in
    true | false) ;;
    *)
        echo "BASELINE_HOST_PLAN_ONLY must be true or false: ${HOST_PLAN_ONLY}" >&2
        exit 1
        ;;
esac
case "${SELECTED_ROUTE}" in
    "" | native-access | object-row) ;;
    *)
        echo "BASELINE_SELECTED_ROUTE must be native-access or object-row: ${SELECTED_ROUTE}" >&2
        exit 1
        ;;
esac
case "${PROTOCOL_QUALIFICATION}" in
    true | false) ;;
    *)
        echo "BASELINE_PROTOCOL_QUALIFICATION must be true or false: ${PROTOCOL_QUALIFICATION}" >&2
        exit 1
        ;;
esac

mkdir -p "${RESULT_DIR}"
RESULT_DIR=$(cd "${RESULT_DIR}" && pwd)
for output in routes route-plans route-plan.tsv host-plan.tsv host-plan.properties \
        expected-rows.tsv observed-rows.tsv observed-rows.tsv.sha256 \
        route-evidence.tsv session.tsv acceptance-receipt.tsv semantic-preparation.json status.txt; do
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
if [[ -n "${SELECTED_ROUTE}" ]]; then
    awk -F '\t' -v route="${SELECTED_ROUTE}" '$1 == route' \
        "${RESULT_DIR}/route-plan.tsv" > "${RESULT_DIR}/route-plan.selected.tsv"
    if [[ ! -s "${RESULT_DIR}/route-plan.selected.tsv" ]]; then
        echo "Shard ${SHARD_ID} does not define selected route ${SELECTED_ROUTE}" >&2
        exit 1
    fi
    mv "${RESULT_DIR}/route-plan.selected.tsv" "${RESULT_DIR}/route-plan.tsv"
fi
if ((RE2_CAMPAIGN_REPLICA_ID % 2 == 0)); then
    awk -F '\t' '$1 == "object-row" {print} $1 == "native-access" {native=$0} END {if (native) print native}' \
        "${RESULT_DIR}/route-plan.tsv" > "${RESULT_DIR}/route-plan.ordered.tsv"
    mv "${RESULT_DIR}/route-plan.ordered.tsv" "${RESULT_DIR}/route-plan.tsv"
fi

if [[ "${VERIFICATION_ONLY}" != true ]]; then
    mkdir -p "${RESULT_DIR}/route-plans"
    host_plan_arguments=()
    while IFS=$'\t' read -r route _; do
        plan_directory="${RESULT_DIR}/route-plans/${route}"
        mkdir -p "${plan_directory}"
        BASELINE_PLAN_ONLY=true \
        BASELINE_DEFER_ACCEPTANCE=true \
        BASELINE_PROTOCOL_QUALIFICATION="${PROTOCOL_QUALIFICATION}" \
        BASELINE_SHARED_WORK_DIR="${SHARED_WORK_DIR}" \
            "${RUN_SHARD}" "${SHARD_ID}" qualification "${route}" "${plan_directory}" >/dev/null
        host_plan_arguments+=(--metadata "${plan_directory}/run-metadata.txt")
    done < "${RESULT_DIR}/route-plan.tsv"
    python3 "${SCRIPT_DIR}/host_duration.py" \
        "${host_plan_arguments[@]}" \
        --bootstrap-allowance-seconds 600 \
        --maximum-seconds 5400 \
        --output "${RESULT_DIR}/host-plan.tsv"
    if [[ "${HOST_PLAN_ONLY}" == true ]]; then
        printf 'status=planned\n' > "${status_file}"
        trap - EXIT
        echo "Planned host session ${SHARD_ID} ${PROTOCOL}"
        exit 0
    fi
fi
mkdir -p "${RESULT_DIR}/routes"

# Odd replicas run native access first; even replicas reverse the order so
# route startup and thermal effects do not systematically favor one layout.
while IFS=$'\t' read -r route _; do
    route_directory="${RESULT_DIR}/routes/${route}"
    mkdir -p "${route_directory}"
    for environment_file in environment-manifest.txt environment.txt; do
        if [[ -f "${RESULT_DIR}/${environment_file}" ]]; then
            cp "${RESULT_DIR}/${environment_file}" "${route_directory}/${environment_file}"
        fi
    done
    BASELINE_DEFER_ACCEPTANCE=true \
    BASELINE_PROTOCOL_QUALIFICATION="${PROTOCOL_QUALIFICATION}" \
    BASELINE_SHARED_WORK_DIR="${SHARED_WORK_DIR}" \
        "${RUN_SHARD}" "${SHARD_ID}" "${PROTOCOL}" "${route}" "${route_directory}"
done < "${RESULT_DIR}/route-plan.tsv"

if [[ "${VERIFICATION_ONLY}" == true ]]; then
    python3 "${SCRIPT_DIR}/semantic_preparation.py" --session "${RESULT_DIR}" --shard "${SHARD_ID}"
    echo "Completed semantic preparation ${SHARD_ID}"
    exit 0
fi

protocol_required_route_count=$(awk -F= \
    '$1 == "protocol_qualification_required" && $2 == "true" {count++} END {print count + 0}' \
    "${RESULT_DIR}"/routes/*/run-metadata.txt)
protocol_qualification_count=$(find "${RESULT_DIR}/routes" -mindepth 2 -maxdepth 2 \
    -name protocol-qualification.tsv -type f | wc -l | tr -d ' ')
if ((protocol_qualification_count != protocol_required_route_count)); then
    echo "Every required route must have protocol qualification; expected ${protocol_required_route_count}, found ${protocol_qualification_count}" >&2
    exit 1
fi

combine_route_arguments=()
if [[ -n "${SELECTED_ROUTE}" ]]; then
    combine_route_arguments+=(--route "${SELECTED_ROUTE}")
fi
python3 "${HOST_TOOL}" combine \
    --manifest "${MANIFEST}" \
    --dispatch "${DISPATCH}" \
    --shard "${SHARD_ID}" \
    --routes-directory "${RESULT_DIR}/routes" \
    --expected "${RESULT_DIR}/expected-rows.tsv" \
    --observed "${RESULT_DIR}/observed-rows.tsv" \
    --session "${RESULT_DIR}/session.tsv" \
    --evidence "${RESULT_DIR}/route-evidence.tsv" \
    "${combine_route_arguments[@]}"

if [[ "${RE2_CAMPAIGN_REPLICA_ID}" == 4 ]]; then
    acceptance_validator="${SCRIPT_DIR}/validate-confirmation-host-results.py"
else
    acceptance_validator="${SCRIPT_DIR}/validate-host-results.py"
fi
python3 "${acceptance_validator}" \
    --manifest "${MANIFEST}" \
    --session "${RESULT_DIR}/session.tsv" \
    --observed-rows "${RESULT_DIR}/observed-rows.tsv" \
    --receipt "${RESULT_DIR}/acceptance-receipt.tsv"

echo "Completed host session ${SHARD_ID} ${PROTOCOL}"
