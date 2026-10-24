#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "Usage: $0 <unit-id> <smoke|qualification> <replica-id> <result-directory>" >&2
    exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
UNIT_ID=$1
PROTOCOL=$2
REPLICA_ID=$3
RESULT_DIR=$4
RUN_SHARD="${SCRIPT_DIR}/run-shard.sh"
UNIT_TOOL="${SCRIPT_DIR}/unit_session.py"
RESULT_TOOL="${SCRIPT_DIR}/shard_results.py"
SHARED_WORK_DIR=${BASELINE_SHARED_WORK_DIR:-${ROOT}/target/baseline-shared}
PLAN_ONLY=${BASELINE_PLAN_ONLY:-false}
PROTOCOL_QUALIFICATION=${BASELINE_PROTOCOL_QUALIFICATION:-true}

case "${PROTOCOL}" in
    smoke | qualification) ;;
    *)
        echo "Protocol must be smoke or qualification: ${PROTOCOL}" >&2
        exit 1
        ;;
esac
if [[ ! ${REPLICA_ID} =~ ^[1-4]$ ]]; then
    echo "Replica must be 1, 2, 3, or 4: ${REPLICA_ID}" >&2
    exit 1
fi
case "${PLAN_ONLY}" in
    true | false) ;;
    *)
        echo "BASELINE_PLAN_ONLY must be true or false: ${PLAN_ONLY}" >&2
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

# The unit ID binds route-handler-order-v1: odd replicas run native access and
# the candidate first, even replicas reverse both, so the order is not a knob.
unit_order=forward
if ((REPLICA_ID % 2 == 0)); then
    unit_order=reverse
fi
for order_name in JONI_COMPARATOR_ORDER REBAR_COMPARATOR_ORDER; do
    if [[ -n ${!order_name:-} && ${!order_name} != "${unit_order}" ]]; then
        echo "${order_name}=${!order_name} conflicts with replica ${REPLICA_ID}'s ${unit_order} unit order" >&2
        exit 1
    fi
done
export JONI_COMPARATOR_ORDER=${unit_order}
export REBAR_COMPARATOR_ORDER=${unit_order}

mkdir -p "${RESULT_DIR}"
RESULT_DIR=$(cd "${RESULT_DIR}" && pwd)
for output in routes route-plan.tsv expected-rows.tsv unit-rows.tsv calibration.tsv \
        observed-rows.tsv observed-rows.tsv.sha256 rebar-outcomes.tsv status.txt; do
    if [[ -e "${RESULT_DIR}/${output}" ]]; then
        echo "Unit output already exists: ${RESULT_DIR}/${output}" >&2
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

SHARD_ID=$(python3 "${UNIT_TOOL}" plan \
    --unit "${UNIT_ID}" \
    --replica "${REPLICA_ID}" \
    --output-directory "${RESULT_DIR}")

# A host measures units of many shards, so the unit names the shard and the
# caller names the replica.
environment_replica=${RE2_CAMPAIGN_REPLICA_ID:-$(environment_value replica)}
if [[ -n "${environment_replica}" && "${environment_replica}" != "${REPLICA_ID}" ]]; then
    echo "Host identity replica ${environment_replica} does not match requested replica ${REPLICA_ID}" >&2
    exit 1
fi
export RE2_CAMPAIGN_SHARD_ID=${SHARD_ID}
export RE2_CAMPAIGN_REPLICA_ID=${REPLICA_ID}
if [[ "${PLAN_ONLY}" != true ]]; then
    export RE2_CAMPAIGN_ID=${RE2_CAMPAIGN_ID:-$(environment_value baseline_campaign)}
    export RE2_CAMPAIGN_PLATFORM=${RE2_CAMPAIGN_PLATFORM:-$(environment_value platform)}
    export RE2_CAMPAIGN_HOST_EPOCH=${RE2_CAMPAIGN_HOST_EPOCH:-$(environment_value host_epoch)}
    export RE2_ENGINEERING_ARCHITECTURE=${RE2_ENGINEERING_ARCHITECTURE:-$(environment_value campaign_architecture)}
    export BASELINE_INSTANCE_ID=${BASELINE_INSTANCE_ID:-$(environment_value instance_id)}
    export BASELINE_INSTANCE_TYPE=${BASELINE_INSTANCE_TYPE:-$(environment_value instance_type)}
    export BASELINE_AVAILABILITY_ZONE=${BASELINE_AVAILABILITY_ZONE:-$(environment_value availability_zone)}
    for value_name in RE2_CAMPAIGN_ID RE2_CAMPAIGN_PLATFORM RE2_CAMPAIGN_HOST_EPOCH \
            RE2_ENGINEERING_ARCHITECTURE BASELINE_INSTANCE_ID BASELINE_INSTANCE_TYPE BASELINE_AVAILABILITY_ZONE; do
        if [[ -z ${!value_name} || ${!value_name} == unknown || ${!value_name} == standalone ]]; then
            echo "Missing formal host identity: ${value_name}" >&2
            exit 1
        fi
    done
fi

mkdir -p "${RESULT_DIR}/routes"
route_count=0
while IFS=$'\t' read -r -u 3 route _ systems calibration; do
    route_count=$((route_count + 1))
    route_directory="${RESULT_DIR}/routes/${route}"
    mkdir -p "${route_directory}"
    for environment_file in environment-manifest.txt environment.txt engine-build.txt; do
        if [[ -f "${RESULT_DIR}/${environment_file}" ]]; then
            cp "${RESULT_DIR}/${environment_file}" "${route_directory}/${environment_file}"
        fi
    done
    BASELINE_UNIT_DIRECTORY="${RESULT_DIR}" \
    BASELINE_UNIT_SYSTEMS="${systems}" \
    BASELINE_UNIT_CALIBRATION="${calibration}" \
    BASELINE_PLAN_ONLY="${PLAN_ONLY}" \
    BASELINE_DEFER_ACCEPTANCE=true \
    BASELINE_PROTOCOL_QUALIFICATION="${PROTOCOL_QUALIFICATION}" \
    BASELINE_SHARED_WORK_DIR="${SHARED_WORK_DIR}" \
        "${RUN_SHARD}" "${SHARD_ID}" "${PROTOCOL}" "${route}" "${route_directory}"
done 3< <(tail -n +2 "${RESULT_DIR}/route-plan.tsv")
if ((route_count == 0)); then
    echo "Unit ${UNIT_ID} plans no routes" >&2
    exit 1
fi

if [[ "${PLAN_ONLY}" == true ]]; then
    printf 'status=planned\n' > "${status_file}"
    trap - EXIT
    echo "Planned unit ${UNIT_ID} ${PROTOCOL} replica ${REPLICA_ID}"
    exit 0
fi

# The unit calibrates before its first route and after its last. Chaining
# replaces the before side with the previous unit's after on this host.
first_route=$(awk -F '\t' 'NR == 2 {print $1}' "${RESULT_DIR}/route-plan.tsv")
last_route=$(awk -F '\t' 'END {print $1}' "${RESULT_DIR}/route-plan.tsv")
calibration_before="${RESULT_DIR}/routes/${first_route}/raw/calibration-before.json"
calibration_after="${RESULT_DIR}/routes/${last_route}/raw/calibration-after.json"
python3 "${RESULT_TOOL}" calibration \
    --before "${calibration_before}" \
    --after "${calibration_after}" \
    --output "${RESULT_DIR}/calibration.tsv"
drift_arguments=(
    --calibration "${RESULT_DIR}/calibration.tsv"
    --maximum 0.05
)
for native_bracket in "${RESULT_DIR}"/routes/*/native-bracket.tsv; do
    if [[ -s "${native_bracket}" ]]; then
        drift_arguments+=(--native-bracket "${native_bracket}")
    fi
done
python3 "${RESULT_TOOL}" validate-drift "${drift_arguments[@]}"

python3 "${UNIT_TOOL}" combine --unit-directory "${RESULT_DIR}"

echo "Completed unit ${UNIT_ID} ${PROTOCOL} replica ${REPLICA_ID}"
