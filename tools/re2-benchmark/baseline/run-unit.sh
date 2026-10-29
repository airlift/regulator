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
SHARED_WORK_DIR=${BASELINE_SHARED_WORK_DIR:-${ROOT}/target/baseline-shared}
# run-host.sh installs each language plan's bundle here, by its shard.
LANGUAGE_PLANS=${ROOT}/target/language-plans
PLAN_ONLY=${BASELINE_PLAN_ONLY:-false}
PROTOCOL_QUALIFICATION=${BASELINE_PROTOCOL_QUALIFICATION:-true}
# A measured unit runs itself as its measurement stage under GNU time.
UNIT_STAGE=${BASELINE_UNIT_STAGE:-unit}
# unit_session.SEMANTIC_FAILURE_STATUS: run-shard.sh's status for a failed correctness stage, which a unit
# controller rejects rather than retries.
SEMANTIC_FAILURE_STATUS=44

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
if [[ -n ${JONI_COMPARATOR_ORDER:-} && ${JONI_COMPARATOR_ORDER} != "${unit_order}" ]]; then
    echo "JONI_COMPARATOR_ORDER=${JONI_COMPARATOR_ORDER} conflicts with replica ${REPLICA_ID}'s ${unit_order} unit order" >&2
    exit 1
fi
export JONI_COMPARATOR_ORDER=${unit_order}

mkdir -p "${RESULT_DIR}"
RESULT_DIR=$(cd "${RESULT_DIR}" && pwd)

# Like a host session, a unit records its capacity: memory, swap, and OOM
# state around the unit, and GNU time's wall time, peak resident memory, and
# status. Unit evidence binds the prefix written before the unit runs.
if [[ "${PLAN_ONLY}" != true && "${UNIT_STAGE}" == unit ]]; then
    capacity_file="${RESULT_DIR}/unit-capacity.txt"
    if [[ -e "${capacity_file}" ]]; then
        echo "Unit output already exists: ${capacity_file}" >&2
        exit 1
    fi
    memory_total_bytes=$(awk '/^MemTotal:/ {print $2 * 1024; exit}' /proc/meminfo)
    memory_available_before_bytes=$(awk '/^MemAvailable:/ {print $2 * 1024; exit}' /proc/meminfo)
    swap_total_bytes=$(awk '/^SwapTotal:/ {print $2 * 1024; exit}' /proc/meminfo)
    swap_free_before_bytes=$(awk '/^SwapFree:/ {print $2 * 1024; exit}' /proc/meminfo)
    oom_kills_before=$(awk '$1 == "oom_kill" {print $2; exit}' /proc/vmstat)
    disk_available_before_bytes=$(df -B1 --output=avail "${ROOT}" | awk 'NR == 2 {print $1}')
    {
        printf 'memory_total_bytes=%.0f\n' "${memory_total_bytes}"
        printf 'memory_available_before_bytes=%.0f\n' "${memory_available_before_bytes}"
        printf 'swap_total_bytes=%.0f\n' "${swap_total_bytes}"
        printf 'swap_free_before_bytes=%.0f\n' "${swap_free_before_bytes}"
        printf 'oom_kills_before=%s\n' "${oom_kills_before}"
        printf 'disk_available_before_bytes=%s\n' "${disk_available_before_bytes}"
    } > "${capacity_file}"
    set +e
    /usr/bin/time --append --output="${capacity_file}" \
        --format='wall_seconds=%e\nmaximum_resident_kibibytes=%M\nexit_status=%x' \
        env BASELINE_UNIT_STAGE=measure "${BASH_SOURCE[0]}" "$@"
    unit_status=$?
    set -e
    memory_available_after_bytes=$(awk '/^MemAvailable:/ {print $2 * 1024; exit}' /proc/meminfo)
    swap_free_after_bytes=$(awk '/^SwapFree:/ {print $2 * 1024; exit}' /proc/meminfo)
    oom_kills_after=$(awk '$1 == "oom_kill" {print $2; exit}' /proc/vmstat)
    disk_available_after_bytes=$(df -B1 --output=avail "${ROOT}" | awk 'NR == 2 {print $1}')
    minimum_available_disk_bytes=$((2 * 1024 * 1024 * 1024))
    minimum_available_memory_bytes=$((2 * 1024 * 1024 * 1024))
    minimum_fractional_memory_bytes=$(awk -v total="${memory_total_bytes}" 'BEGIN {printf "%.0f", total / 10}')
    if ((minimum_fractional_memory_bytes > minimum_available_memory_bytes)); then
        minimum_available_memory_bytes=${minimum_fractional_memory_bytes}
    fi
    capacity_status=accepted
    if ((disk_available_before_bytes < minimum_available_disk_bytes ||
            disk_available_after_bytes < minimum_available_disk_bytes ||
            memory_available_after_bytes < minimum_available_memory_bytes ||
            swap_free_before_bytes < swap_total_bytes ||
            swap_free_after_bytes < swap_total_bytes ||
            oom_kills_after != oom_kills_before)); then
        capacity_status=rejected
    fi
    {
        printf 'memory_available_after_bytes=%.0f\n' "${memory_available_after_bytes}"
        printf 'minimum_available_memory_bytes=%s\n' "${minimum_available_memory_bytes}"
        printf 'swap_free_after_bytes=%.0f\n' "${swap_free_after_bytes}"
        printf 'oom_kills_after=%s\n' "${oom_kills_after}"
        printf 'disk_available_after_bytes=%s\n' "${disk_available_after_bytes}"
        printf 'minimum_available_disk_bytes=%s\n' "${minimum_available_disk_bytes}"
        printf 'capacity_status=%s\n' "${capacity_status}"
    } >> "${capacity_file}"
    if [[ "${capacity_status}" != accepted ]]; then
        echo "Unit capacity gate rejected unit ${UNIT_ID}; see ${capacity_file}" >&2
        # A semantic failure is final whatever the unit's capacity, so its own status reaches the controller.
        if ((unit_status == SEMANTIC_FAILURE_STATUS)); then
            exit "${unit_status}"
        fi
        exit 1
    fi
    # Preserve the representative rejection status only after resource checks.
    exit "${unit_status}"
fi

for output in routes route-plan.tsv expected-rows.tsv unit-rows.tsv observed-rows.tsv observed-rows.tsv.sha256 \
        unit-session.tsv unit-evidence.tsv unit-receipt.tsv status.txt; do
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

# A host measures units of many shards, so the unit names the shard and the
# caller names the replica.
environment_replica=${RE2_CAMPAIGN_REPLICA_ID:-$(environment_value replica)}
if [[ -n "${environment_replica}" && "${environment_replica}" != "${REPLICA_ID}" ]]; then
    echo "Host identity replica ${environment_replica} does not match requested replica ${REPLICA_ID}" >&2
    exit 1
fi
export RE2_CAMPAIGN_REPLICA_ID=${REPLICA_ID}
if [[ "${PLAN_ONLY}" != true ]]; then
    export RE2_CAMPAIGN_ID=${RE2_CAMPAIGN_ID:-$(environment_value baseline_campaign)}
    export RE2_CAMPAIGN_PLATFORM=${RE2_CAMPAIGN_PLATFORM:-$(environment_value platform)}
    export RE2_CAMPAIGN_HOST_EPOCH=${RE2_CAMPAIGN_HOST_EPOCH:-$(environment_value host_epoch)}
    export RE2_ENGINEERING_ARCHITECTURE=${RE2_ENGINEERING_ARCHITECTURE:-$(environment_value campaign_architecture)}
    export BASELINE_INSTANCE_ID=${BASELINE_INSTANCE_ID:-$(environment_value instance_id)}
    export BASELINE_INSTANCE_TYPE=${BASELINE_INSTANCE_TYPE:-$(environment_value instance_type)}
    export BASELINE_AVAILABILITY_ZONE=${BASELINE_AVAILABILITY_ZONE:-$(environment_value availability_zone)}
    # The worker names the attempt: its host session and the lease sequence there.
    RE2_CAMPAIGN_HOST_SESSION=${RE2_CAMPAIGN_HOST_SESSION:-}
    RE2_CAMPAIGN_LEASE_SEQUENCE=${RE2_CAMPAIGN_LEASE_SEQUENCE:-}
    BASELINE_REGION=${BASELINE_REGION:-${BASELINE_AVAILABILITY_ZONE%[a-z]}}
    for value_name in RE2_CAMPAIGN_ID RE2_CAMPAIGN_PLATFORM RE2_CAMPAIGN_HOST_EPOCH \
            RE2_ENGINEERING_ARCHITECTURE BASELINE_INSTANCE_ID BASELINE_INSTANCE_TYPE BASELINE_AVAILABILITY_ZONE \
            RE2_CAMPAIGN_HOST_SESSION RE2_CAMPAIGN_LEASE_SEQUENCE BASELINE_REGION; do
        if [[ -z ${!value_name} || ${!value_name} == unknown || ${!value_name} == standalone ]]; then
            echo "Missing formal host identity: ${value_name}" >&2
            exit 1
        fi
    done
fi

# The build phase's unit builds and packages the platform's engines for its measuring unit workers.
if [[ "${UNIT_ID}" == engine-build ]]; then
    if [[ "${PLAN_ONLY}" == true ]]; then
        echo "The engine build unit has no plan-only run" >&2
        exit 1
    fi
    "${SCRIPT_DIR}/build-engines.sh" "${RESULT_DIR}"
    python3 "${SCRIPT_DIR}/engine_build.py" accept \
        --unit-directory "${RESULT_DIR}" \
        --replica "${REPLICA_ID}" \
        --unit-attempt "${RE2_CAMPAIGN_HOST_SESSION}/${RE2_CAMPAIGN_LEASE_SEQUENCE}" \
        --region "${BASELINE_REGION}" \
        --engine-tree "${BASELINE_EXPECTED_ENGINE_TREE:?The engine build requires BASELINE_EXPECTED_ENGINE_TREE}"
    echo "Completed engine build unit replica ${REPLICA_ID}"
    exit 0
fi

# A unit of an installed language plan measures its comparisons with the language collector.
language_plan=
for units_file in "${LANGUAGE_PLANS}"/*/units.tsv; do
    if [[ -f "${units_file}" ]] &&
            awk -F '\t' -v unit="${UNIT_ID}" 'NR > 1 && $1 == unit {found = 1} END {exit !found}' "${units_file}"; then
        language_plan=$(dirname "${units_file}")
        break
    fi
done
if [[ -n "${language_plan}" ]]; then
    if [[ "${PLAN_ONLY}" == true || "${PROTOCOL}" != qualification ]]; then
        echo "Language unit ${UNIT_ID} requires PLAN_ONLY=false and PROTOCOL=qualification" >&2
        exit 1
    fi
    python3 "${ROOT}/tools/re2-benchmark/language/fleet.py" run-unit \
        --plan-directory "${language_plan}" \
        --unit "${UNIT_ID}" \
        --replica "${REPLICA_ID}" \
        --unit-directory "${RESULT_DIR}" \
        --native-directory "${SHARED_WORK_DIR}/language-native" \
        --jvm-directory "${SHARED_WORK_DIR}/language-jvm" \
        --unit-attempt "${RE2_CAMPAIGN_HOST_SESSION}/${RE2_CAMPAIGN_LEASE_SEQUENCE}" \
        --region "${BASELINE_REGION}" \
        --source-archive "${LANGUAGE_SOURCE_ARCHIVE:?A language unit requires LANGUAGE_SOURCE_ARCHIVE}" \
        --candidate-provenance "${LANGUAGE_CANDIDATE_PROVENANCE:?A language unit requires LANGUAGE_CANDIDATE_PROVENANCE}"
    echo "Completed language unit ${UNIT_ID} replica ${REPLICA_ID}"
    exit 0
fi

SHARD_ID=$(python3 "${UNIT_TOOL}" plan \
    --unit "${UNIT_ID}" \
    --replica "${REPLICA_ID}" \
    --output-directory "${RESULT_DIR}")

export RE2_CAMPAIGN_SHARD_ID=${SHARD_ID}

mkdir -p "${RESULT_DIR}/routes"
route_count=0
while IFS=$'\t' read -r -u 3 route _ systems; do
    route_count=$((route_count + 1))
    route_directory="${RESULT_DIR}/routes/${route}"
    mkdir -p "${route_directory}"
    python3 "${UNIT_TOOL}" copy-environment --source "${RESULT_DIR}" --target "${route_directory}"
    BASELINE_UNIT_DIRECTORY="${RESULT_DIR}" \
    BASELINE_UNIT_SYSTEMS="${systems}" \
    BASELINE_PLAN_ONLY="${PLAN_ONLY}" \
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

python3 "${UNIT_TOOL}" combine \
    --unit-directory "${RESULT_DIR}" \
    --unit-attempt "${RE2_CAMPAIGN_HOST_SESSION}/${RE2_CAMPAIGN_LEASE_SEQUENCE}" \
    --region "${BASELINE_REGION}"
python3 "${SCRIPT_DIR}/validate-unit-results.py" \
    --unit-directory "${RESULT_DIR}" \
    --receipt "${RESULT_DIR}/unit-receipt.tsv"

echo "Completed unit ${UNIT_ID} ${PROTOCOL} replica ${REPLICA_ID}"
