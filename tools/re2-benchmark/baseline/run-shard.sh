#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "Usage: $0 <shard-id> <smoke|qualification> <native-access|object-row> <result-directory>" >&2
    exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
SHARD_ID=$1
PROTOCOL=$2
ROUTE=$3
RESULT_DIR=$4
MANIFEST="${SCRIPT_DIR}/rows.tsv"
DISPATCH="${SCRIPT_DIR}/shard-dispatch.tsv"
RESULT_TOOL="${SCRIPT_DIR}/shard_results.py"
PROTOCOL_REPRESENTATIVES="${SCRIPT_DIR}/protocol-representatives.tsv"
FULL_PROTOCOL_BENCHMARKS="${SCRIPT_DIR}/full-protocol-benchmarks.tsv"
PLAN_ONLY=${BASELINE_PLAN_ONLY:-false}
DEFER_ACCEPTANCE=${BASELINE_DEFER_ACCEPTANCE:-false}
PROTOCOL_QUALIFICATION=${BASELINE_PROTOCOL_QUALIFICATION:-true}
VERIFICATION_ONLY=${BASELINE_VERIFICATION_ONLY:-false}
case "${VERIFICATION_ONLY}" in true | false) ;; *) exit 1 ;; esac
if [[ "${VERIFICATION_ONLY}" == true && ( "${PROTOCOL}" != smoke || "${PROTOCOL_QUALIFICATION}" != false ) ]]; then
    echo "Semantic preparation requires smoke settings with timing qualification disabled" >&2
    exit 1
fi
SHARED_WORK_DIR=${BASELINE_SHARED_WORK_DIR:-${ROOT}/target/baseline-shared}
JONI_COMPARATOR_ORDER=${JONI_COMPARATOR_ORDER:-forward}
CAMPAIGN_REPLICA_ID=${RE2_CAMPAIGN_REPLICA_ID:-1}
CPU_LIST=${BASELINE_CPU_LIST:-0}
HEAP_SIZE=${BASELINE_HEAP_SIZE:-8g}
MAVEN_ARGS=(-Dmaven.gitcommitid.skip=true)
maximum_route_seconds=5400
bounded_protocol_row_threshold=30
PROTOCOL_QUALIFICATION_PROCESS_COUNT=5
JONI_JMH_FORKS=1
protocol_qualification_estimate_seconds=0
full_protocol_row_count=0
full_protocol_estimate_seconds=0
REBAR_MEASUREMENT_TIMEOUT=120s
REBAR_PRIMARY_SEMANTIC_TIMEOUT=30s
REBAR_PRIMARY_SEMANTIC_TIMEOUT_SECONDS=30
REBAR_JONI_SEMANTIC_TIMEOUT=30s
REBAR_JONI_SEMANTIC_TIMEOUT_SECONDS=30

case "${PROTOCOL}" in
    smoke)
        FORKS=1
        WARMUP_ITERATIONS=1
        MEASUREMENT_ITERATIONS=1
        WARMUP_TIME=200ms
        MEASUREMENT_TIME=200ms
        NATIVE_REPETITIONS=3
        NATIVE_MINIMUM_TIME=0.2s
        NATIVE_MINIMUM_SECONDS=0.2
        REBAR_MAXIMUM_TIME=5s
        REBAR_WARMUP_TIME=5s
        REBAR_MAXIMUM_SECONDS=5
        REBAR_WARMUP_SECONDS=5
        CALIBRATION_FORKS=1
        CALIBRATION_WARMUP_ITERATIONS=3
        CALIBRATION_MEASUREMENT_ITERATIONS=3
        CALIBRATION_WARMUP_TIME=1s
        CALIBRATION_MEASUREMENT_TIME=1s
        CALIBRATION_PRIME_INVOCATIONS=1
        ;;
    qualification)
        FORKS=5
        WARMUP_ITERATIONS=10
        MEASUREMENT_ITERATIONS=10
        WARMUP_TIME=1s
        MEASUREMENT_TIME=1s
        NATIVE_REPETITIONS=5
        NATIVE_MINIMUM_TIME=1s
        NATIVE_MINIMUM_SECONDS=1
        REBAR_MAXIMUM_TIME=5s
        REBAR_WARMUP_TIME=5s
        REBAR_MAXIMUM_SECONDS=5
        REBAR_WARMUP_SECONDS=5
        CALIBRATION_FORKS=5
        CALIBRATION_WARMUP_ITERATIONS=10
        CALIBRATION_MEASUREMENT_ITERATIONS=10
        CALIBRATION_WARMUP_TIME=1s
        CALIBRATION_MEASUREMENT_TIME=1s
        CALIBRATION_PRIME_INVOCATIONS=0
        ;;
    *)
        echo "Protocol must be smoke or qualification: ${PROTOCOL}" >&2
        exit 1
        ;;
esac

case "${ROUTE}" in
    native-access)
        handler_column=2
        systems_column=3
        candidate_system=regulator-native-access
        ;;
    object-row)
        handler_column=4
        systems_column=5
        candidate_system=regulator-object-row
        ;;
    *)
        echo "Route must be native-access or object-row: ${ROUTE}" >&2
        exit 1
        ;;
esac

dispatch_row=$(awk -F '\t' -v shard="${SHARD_ID}" 'NR > 1 && $1 == shard {print; found=1} END {if (!found) exit 1}' "${DISPATCH}") || {
    echo "Unknown baseline shard: ${SHARD_ID}" >&2
    exit 1
}
IFS=$'\t' read -r _ native_handler native_systems object_handler object_systems semantic_tests blocker <<<"${dispatch_row}"
handler=$(cut -f "${handler_column}" <<<"${dispatch_row}")
systems=$(cut -f "${systems_column}" <<<"${dispatch_row}")
if [[ "${handler}" == blocked ]]; then
    echo "Baseline shard ${SHARD_ID} route ${ROUTE} is not soundly dispatchable: ${blocker}" >&2
    exit 2
fi
if [[ "${handler}" == - || "${systems}" == - ]]; then
    echo "Baseline shard ${SHARD_ID} does not define route ${ROUTE}" >&2
    exit 2
fi

if [[ "${PLAN_ONLY}" != true && "${PLAN_ONLY}" != false ]]; then
    echo "BASELINE_PLAN_ONLY must be true or false" >&2
    exit 1
fi
if [[ "${DEFER_ACCEPTANCE}" != true && "${DEFER_ACCEPTANCE}" != false ]]; then
    echo "BASELINE_DEFER_ACCEPTANCE must be true or false" >&2
    exit 1
fi
if [[ "${PROTOCOL_QUALIFICATION}" != true && "${PROTOCOL_QUALIFICATION}" != false ]]; then
    echo "BASELINE_PROTOCOL_QUALIFICATION must be true or false" >&2
    exit 1
fi
case "${JONI_COMPARATOR_ORDER}" in
    forward | reverse) ;;
    *)
        echo "JONI_COMPARATOR_ORDER must be forward or reverse: ${JONI_COMPARATOR_ORDER}" >&2
        exit 1
        ;;
esac
if [[ ! ${CAMPAIGN_REPLICA_ID} =~ ^[1-4]$ ]]; then
    echo "RE2_CAMPAIGN_REPLICA_ID must be 1, 2, 3, or 4: ${CAMPAIGN_REPLICA_ID}" >&2
    exit 1
fi
JMH_SUITE_ORDER=forward
if ((CAMPAIGN_REPLICA_ID % 2 == 0)); then
    JMH_SUITE_ORDER=reverse
fi

mkdir -p "${RESULT_DIR}"
RESULT_DIR=$(cd "${RESULT_DIR}" && pwd)
unexpected_existing=$(find "${RESULT_DIR}" -mindepth 1 -maxdepth 1 \
    ! -name environment.txt \
    ! -name environment-manifest.txt \
    -print -quit)
if [[ -n "${unexpected_existing}" ]]; then
    echo "Result directory must be empty: ${RESULT_DIR}" >&2
    exit 1
fi
mkdir -p "${RESULT_DIR}/raw" "${RESULT_DIR}/logs" "${RESULT_DIR}/normalized"

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

python3 "${SCRIPT_DIR}/validate-manifest.py"
python3 "${SCRIPT_DIR}/validate-protocol-representatives.py"
python3 "${RESULT_TOOL}" expected \
    --manifest "${MANIFEST}" \
    --shard "${SHARD_ID}" \
    --systems "${systems}" \
    --output "${RESULT_DIR}/expected-rows.tsv"

full_protocol_benchmarks()
{
    awk -F '\t' -v shard="${SHARD_ID}" -v route="${ROUTE}" \
        'NR > 1 && $1 == shard && $2 == route {print $3 "\t" $4 "\t" $5}' \
        "${FULL_PROTOCOL_BENCHMARKS}"
}

manifest_benchmark_for_jmh_benchmark()
{
    local jmh_benchmark=$1
    case "${jmh_benchmark}" in
        io.trino.operator.scalar.BenchmarkTrinoFinalLine.*Regulator)
            local method=${jmh_benchmark##*.}
            printf '%s\n' "${method%Regulator}"
            ;;
        *) printf '%s\n' "${jmh_benchmark}" ;;
    esac
}

parameters_match_selection()
{
    local full_parameters=$1
    local target_parameters=$2
    python3 - "${full_parameters}" "${target_parameters}" <<'PY'
import sys

selection = {}
for parameter in sys.argv[1].split(";"):
    name, values = parameter.split("=", 1)
    selection[name] = set(values.split(","))
target = dict(parameter.split("=", 1) for parameter in sys.argv[2].split(";"))
if selection.keys() != target.keys():
    raise SystemExit(1)
raise SystemExit(0 if all(target[name] in values for name, values in selection.items()) else 1)
PY
}

is_full_protocol_benchmark()
{
    local target_benchmark=$1
    full_protocol_benchmarks | cut -f 1 | grep -Fqx "${target_benchmark}"
}

is_full_protocol_row()
{
    local target_benchmark=$1
    local target_parameters=$2
    local full_protocol_benchmark
    local full_parameters
    while IFS=$'\t' read -r full_protocol_benchmark full_parameters _; do
        [[ "${full_protocol_benchmark}" == "${target_benchmark}" ]] || continue
        if parameters_match_selection "${full_parameters}" "${target_parameters}"; then
            return 0
        fi
    done < <(full_protocol_benchmarks)
    return 1
}

while IFS=$'\t' read -r full_protocol_benchmark full_parameters _; do
    manifest_benchmark=$(manifest_benchmark_for_jmh_benchmark "${full_protocol_benchmark}")
    matching_row_count=0
    while IFS=$'\t' read -r expected_benchmark expected_parameters; do
        [[ "${expected_benchmark}" == "${manifest_benchmark}" ]] || continue
        if parameters_match_selection "${full_parameters}" "${expected_parameters}"; then
            matching_row_count=$((matching_row_count + 1))
        fi
    done < <(awk -F '\t' -v target_system="${candidate_system}" \
        'NR > 1 && $4 == target_system {print $5 "\t" $6}' \
        "${RESULT_DIR}/expected-rows.tsv")
    if [[ ${matching_row_count} -eq 0 ]]; then
        echo "Full-protocol row is not expected: ${full_protocol_benchmark}/${full_parameters}" >&2
        exit 1
    fi
    full_protocol_row_count=$((full_protocol_row_count + matching_row_count))
done < <(full_protocol_benchmarks)
if [[ "${PROTOCOL}" == qualification ]]; then
    # Five forks, each with ten one-second warmups and measurements, plus process startup.
    full_protocol_estimate_seconds=$((full_protocol_row_count * 110))
fi

candidate_jmh_row_count=$(awk -F '\t' -v target_system="${candidate_system}" '
    NR > 1 &&
    $3 != "retained-memory" &&
    $3 !~ /^rebar-/ &&
    $4 == target_system {count++}
    END {print count + 0}
' "${RESULT_DIR}/expected-rows.tsv")
joni_jmh_row_count=$(awk -F '\t' '
    NR > 1 &&
    $3 != "retained-memory" &&
    $3 !~ /^rebar-/ &&
    $4 == "joni" {count++}
    END {print count + 0}
' "${RESULT_DIR}/expected-rows.tsv")
jmh_row_count=$((candidate_jmh_row_count + joni_jmh_row_count))
if [[ "${SHARD_ID}" == like-compile || "${SHARD_ID}" == like-single-use || "${SHARD_ID}" == like-dfa-single-use ]]; then
    # Both implementations run through the same JMH runner. Include the SQL
    # comparator in the full-protocol duration budget, not just Regulator.
    candidate_jmh_row_count=$(awk 'END {print NR - 1}' "${RESULT_DIR}/expected-rows.tsv")
    jmh_row_count=${candidate_jmh_row_count}
fi
case "${handler}" in
    jmh)
        jmh_invocation_count=$(awk -F '\t' 'NR > 1 {suite[$3]=1} END {print length(suite)}' \
            "${RESULT_DIR}/expected-rows.tsv")
        ;;
    traditional) jmh_invocation_count=1 ;;
    trino-operations | trino-final-line)
        if [[ "${ROUTE}" == native-access ]]; then
            jmh_invocation_count=2
        else
            jmh_invocation_count=1
        fi
        ;;
    trino-like)
        jmh_invocation_count=3
        if [[ "${SHARD_ID}" == like-compile || "${SHARD_ID}" == like-single-use ]]; then
            jmh_invocation_count=2
        fi
        ;;
    lifecycle) jmh_invocation_count=1 ;;
    memory-census) jmh_invocation_count=0 ;;
    rebar) jmh_invocation_count=0 ;;
    *)
        echo "No static JMH invocation model for handler ${handler}" >&2
        exit 1
        ;;
esac
joni_jmh_invocation_count=0
if [[ ${joni_jmh_row_count} -gt 0 ]]; then
    joni_jmh_invocation_count=1
fi
candidate_jmh_invocation_count=$((jmh_invocation_count - joni_jmh_invocation_count))

rebar_system_row_count=$(awk -F '\t' 'NR > 1 && $3 ~ /^rebar-/ {count++} END {print count + 0}' \
    "${RESULT_DIR}/expected-rows.tsv")
rebar_joni_row_count=$(awk -F '\t' 'NR > 1 && $3 ~ /^rebar-/ && $4 == "joni" {count++} END {print count + 0}' \
    "${RESULT_DIR}/expected-rows.tsv")
retained_memory_census_count=$(awk -F '\t' 'NR > 1 && $3 == "retained-memory" {count++} END {print count + 0}' \
    "${RESULT_DIR}/expected-rows.tsv")
native_system_row_count=$(awk -F '\t' 'NR > 1 && $4 ~ /^native-re2-/ {count++} END {print count + 0}' \
    "${RESULT_DIR}/expected-rows.tsv")
native_measurement_estimate_seconds=0
native_build_startup_allowance_seconds=0
rebar_measurement_window_seconds_per_row=0
rebar_measurement_estimate_seconds=0
rebar_build_startup_allowance_seconds=0
rebar_primary_verification_estimate_seconds=0
rebar_joni_verification_estimate_seconds=0
pinned_trino_preparation_allowance_seconds=0
retained_memory_census_allowance_seconds_per_process=0
retained_memory_census_estimate_seconds=0
case "${handler}" in
    traditional)
        if [[ "${ROUTE}" == native-access && ${native_system_row_count} -gt 0 ]]; then
            native_measurement_estimate_seconds=$(awk \
                -v rows="${native_system_row_count}" \
                -v repetitions="${NATIVE_REPETITIONS}" \
                -v minimum="${NATIVE_MINIMUM_SECONDS}" \
                'BEGIN {printf "%.17g", rows * repetitions * minimum}')
            # Covers the cold pinned native RE2 build and benchmark process startup.
            native_build_startup_allowance_seconds=900
        fi
        ;;
    trino-operations)
        if [[ "${ROUTE}" == native-access ]]; then
            pinned_trino_preparation_allowance_seconds=900
        fi
        ;;
    trino-final-line | trino-like | memory-census)
        # Includes a cold pinned archive preparation/build and route-local harness setup.
        pinned_trino_preparation_allowance_seconds=900
        ;;
    rebar)
        rebar_measurement_window_seconds_per_row=$(awk \
            -v warmup="${REBAR_WARMUP_SECONDS}" \
            -v measurement="${REBAR_MAXIMUM_SECONDS}" \
            'BEGIN {printf "%.17g", warmup + measurement}')
        rebar_measurement_estimate_seconds=$(awk \
            -v rows="${rebar_system_row_count}" \
            -v window="${rebar_measurement_window_seconds_per_row}" \
            'BEGIN {printf "%.17g", rows * window}')
        # Covers cold Rust/Java/native engine builds and Rebar process startup.
        rebar_build_startup_allowance_seconds=900
        rebar_primary_verification_estimate_seconds=$((
            (rebar_system_row_count - rebar_joni_row_count) *
                REBAR_PRIMARY_SEMANTIC_TIMEOUT_SECONDS))
        if [[ ${rebar_joni_row_count} -gt 0 ]]; then
            pinned_trino_preparation_allowance_seconds=900
            rebar_joni_verification_estimate_seconds=$((
                rebar_joni_row_count * REBAR_JONI_SEMANTIC_TIMEOUT_SECONDS))
        fi
        ;;
esac
if [[ "${handler}" == memory-census ]]; then
    retained_memory_census_allowance_seconds_per_process=900
    retained_memory_census_estimate_seconds=$(awk \
        -v count="${retained_memory_census_count}" \
        -v allowance="${retained_memory_census_allowance_seconds_per_process}" \
        'BEGIN {printf "%.17g", count * allowance}')
fi
protocol_qualification_required=false
protocol_representative_count=0
if [[ ${jmh_row_count} -gt ${bounded_protocol_row_threshold} && "${PROTOCOL_QUALIFICATION}" == true ]]; then
    protocol_replica_id=${CAMPAIGN_REPLICA_ID}
    if [[ "${protocol_replica_id}" == 4 ]]; then
        protocol_replica_id=1
    fi
    while IFS=$'\t' read -r representative_shard representative_replica representative_route \
            _ _ representative_benchmark representative_parameters _; do
        [[ "${representative_shard}" == "${SHARD_ID}" ]] || continue
        [[ "${representative_replica}" == "${protocol_replica_id}" ]] || continue
        [[ "${representative_route}" == "${ROUTE}" ]] || continue
        if ! is_full_protocol_row "${representative_benchmark}" "${representative_parameters}"; then
            protocol_representative_count=$((protocol_representative_count + 1))
        fi
    done < <(tail -n +2 "${PROTOCOL_REPRESENTATIVES}")
    if [[ ${protocol_representative_count} -gt 0 ]]; then
        protocol_qualification_required=true
    fi
    # Five independent JVMs expose code-generation variation without making
    # the protocol-equivalence gate dominate the bounded shard runtime.
    protocol_qualification_estimate_seconds=$((protocol_representative_count * 132))
fi
additional_duration_allowance_seconds=$(awk \
    -v native_measurement="${native_measurement_estimate_seconds}" \
    -v native_cold="${native_build_startup_allowance_seconds}" \
    -v rebar_measurement="${rebar_measurement_estimate_seconds}" \
    -v rebar_cold="${rebar_build_startup_allowance_seconds}" \
    -v rebar_primary_verification="${rebar_primary_verification_estimate_seconds}" \
    -v rebar_joni_verification="${rebar_joni_verification_estimate_seconds}" \
    -v trino_cold="${pinned_trino_preparation_allowance_seconds}" \
    -v census="${retained_memory_census_estimate_seconds}" \
    -v protocol="${protocol_qualification_estimate_seconds}" \
    -v full_protocol="${full_protocol_estimate_seconds}" \
    'BEGIN {printf "%.17g", native_measurement + native_cold + rebar_measurement + rebar_cold + rebar_primary_verification + rebar_joni_verification + trino_cold + census + protocol + full_protocol}')
if [[ "${PROTOCOL}" == smoke ]]; then
    JMH_PROCESS_COUNT=1
    JMH_PROCESS_WARMUP_ITERATIONS=1
    JMH_PROCESS_MEASUREMENT_ITERATIONS=3
    JMH_PROCESS_TIME=20ms
    jmh_iteration_seconds=0.02
    JMH_EXECUTION_PROTOCOL=multi-row-process-smoke
else
    JMH_PROCESS_COUNT=5
    JMH_PROCESS_WARMUP_ITERATIONS=10
    JMH_PROCESS_MEASUREMENT_ITERATIONS=10
    if [[ ${jmh_row_count} -le ${bounded_protocol_row_threshold} ]]; then
        JMH_PROCESS_TIME=1s
        jmh_iteration_seconds=1
        JMH_EXECUTION_PROTOCOL=multi-row-process-full
    else
        JMH_PROCESS_TIME=50ms
        jmh_iteration_seconds=0.05
        JMH_EXECUTION_PROTOCOL=multi-row-process-bounded
    fi
fi
EXTENDED_WARMUP_TIME=300ms
EXTENDED_WARMUP_SECONDS=0.3
extended_warmup_row_count=0
extended_warmup_additional_seconds=0
if [[ "${PROTOCOL}" != smoke && "${JMH_EXECUTION_PROTOCOL}" == multi-row-process-bounded ]]; then
    extended_warmup_row_count=$(awk -F '\t' '
        NR > 1 && ($3 == "compile" || $3 == "public-compile" ||
            $5 ~ /^io[.]airlift[.]regulator[.]BenchmarkRe2Practical[.]compilePhase/ ||
            $5 ~ /^io[.]airlift[.]regulator[.]BenchmarkRe2Parse[.]searchPhoneRe2/) {count++}
        END {print count + 0}
    ' "${RESULT_DIR}/expected-rows.tsv")
    extended_warmup_additional_seconds=$(awk \
        -v rows="${extended_warmup_row_count}" \
        -v processes="${JMH_PROCESS_COUNT}" \
        -v iterations="${JMH_PROCESS_WARMUP_ITERATIONS}" \
        -v extended_warmup="${EXTENDED_WARMUP_SECONDS}" \
        -v default_warmup="${jmh_iteration_seconds}" \
        'BEGIN {printf "%.17g", rows * processes * iterations * (extended_warmup - default_warmup)}')
    additional_duration_allowance_seconds=$(awk \
        -v base="${additional_duration_allowance_seconds}" \
        -v extended="${extended_warmup_additional_seconds}" \
        'BEGIN {printf "%.17g", base + extended}')
fi
precision_arguments=()
if [[ "${JMH_EXECUTION_PROTOCOL}" == multi-row-process-bounded ]]; then
    precision_arguments=(--maximum-relative-standard-error 0.05)
fi
jmh_startup_seconds=2
calibration_iteration_seconds=1
jmh_runtime_arguments=(
    --rows "${candidate_jmh_row_count}" \
    --invocations "${candidate_jmh_invocation_count}" \
    --processes "${JMH_PROCESS_COUNT}" \
    --forked-rows "${joni_jmh_row_count}" \
    --forked-processes "$((joni_jmh_row_count > 0 ? JONI_JMH_FORKS : 0))" \
    --warmup-iterations "${JMH_PROCESS_WARMUP_ITERATIONS}" \
    --measurement-iterations "${JMH_PROCESS_MEASUREMENT_ITERATIONS}" \
    --iteration-seconds "${jmh_iteration_seconds}" \
    --startup-seconds "${jmh_startup_seconds}" \
    --calibration-invocations "$((2 + CALIBRATION_PRIME_INVOCATIONS))" \
    --calibration-rows 1 \
    --calibration-processes "${CALIBRATION_FORKS}" \
    --calibration-warmup-iterations "${CALIBRATION_WARMUP_ITERATIONS}" \
    --calibration-measurement-iterations "${CALIBRATION_MEASUREMENT_ITERATIONS}" \
    --calibration-iteration-seconds "${calibration_iteration_seconds}"
)
jmh_duration_estimate_seconds=$(python3 "${RESULT_TOOL}" estimate-jmh-runtime \
    "${jmh_runtime_arguments[@]}" \
    --maximum-seconds "${maximum_route_seconds}")
route_duration_estimate_seconds=$(python3 "${RESULT_TOOL}" estimate-jmh-runtime \
    "${jmh_runtime_arguments[@]}" \
    --additional-seconds "${additional_duration_allowance_seconds}" \
    --maximum-seconds "${maximum_route_seconds}")

if [[ "${PLAN_ONLY}" == true ]]; then
    {
        printf 'shard_id=%s\n' "${SHARD_ID}"
        printf 'protocol=%s\n' "${PROTOCOL}"
        printf 'route=%s\n' "${ROUTE}"
        printf 'handler=%s\n' "${handler}"
        printf 'systems=%s\n' "${systems}"
        printf 'forks=%s\n' "${FORKS}"
        printf 'warmup=%sx%s\n' "${WARMUP_ITERATIONS}" "${WARMUP_TIME}"
        printf 'measurement=%sx%s\n' "${MEASUREMENT_ITERATIONS}" "${MEASUREMENT_TIME}"
        printf 'calibration_forks=%s\n' "${CALIBRATION_FORKS}"
        printf 'calibration_warmup=%sx%s\n' \
            "${CALIBRATION_WARMUP_ITERATIONS}" "${CALIBRATION_WARMUP_TIME}"
        printf 'calibration_measurement=%sx%s\n' \
            "${CALIBRATION_MEASUREMENT_ITERATIONS}" "${CALIBRATION_MEASUREMENT_TIME}"
        printf 'calibration_prime_invocations=%s\n' "${CALIBRATION_PRIME_INVOCATIONS}"
        printf 'native_repetitions=%s\n' "${NATIVE_REPETITIONS}"
        printf 'native_minimum_time=%s\n' "${NATIVE_MINIMUM_TIME}"
        printf 'plan_only=true\n'
        printf 'defer_acceptance=%s\n' "${DEFER_ACCEPTANCE}"
        printf 'joni_comparator_order=%s\n' "${JONI_COMPARATOR_ORDER}"
        printf 'jmh_execution_protocol=%s\n' "${JMH_EXECUTION_PROTOCOL}"
        printf 'jmh_suite_order=%s\n' "${JMH_SUITE_ORDER}"
        printf 'jmh_process_count=%s\n' "${JMH_PROCESS_COUNT}"
        printf 'joni_jmh_forks=%s\n' "${JONI_JMH_FORKS}"
        printf 'candidate_jmh_row_count=%s\n' "${candidate_jmh_row_count}"
        printf 'joni_jmh_row_count=%s\n' "${joni_jmh_row_count}"
        printf 'jmh_invocation_count=%s\n' "${jmh_invocation_count}"
        printf 'jmh_process_warmup_iterations=%s\n' "${JMH_PROCESS_WARMUP_ITERATIONS}"
        printf 'jmh_process_measurement_iterations=%s\n' "${JMH_PROCESS_MEASUREMENT_ITERATIONS}"
        printf 'jmh_iteration_time=%s\n' "${JMH_PROCESS_TIME}"
        printf 'extended_warmup_time=%s\n' "${EXTENDED_WARMUP_TIME}"
        printf 'extended_warmup_row_count=%s\n' "${extended_warmup_row_count}"
        printf 'extended_warmup_additional_seconds=%s\n' "${extended_warmup_additional_seconds}"
        printf 'jmh_static_duration_estimate_seconds=%s\n' "${jmh_duration_estimate_seconds}"
        printf 'protocol_qualification_required=%s\n' "${protocol_qualification_required}"
        printf 'protocol_representative_count=%s\n' "${protocol_representative_count}"
        printf 'protocol_qualification_estimate_seconds=%s\n' "${protocol_qualification_estimate_seconds}"
        printf 'full_protocol_row_count=%s\n' "${full_protocol_row_count}"
        printf 'full_protocol_estimate_seconds=%s\n' "${full_protocol_estimate_seconds}"
        printf 'native_system_row_count=%s\n' "${native_system_row_count}"
        printf 'native_measurement_estimate_seconds=%s\n' "${native_measurement_estimate_seconds}"
        printf 'native_build_startup_allowance_seconds=%s\n' "${native_build_startup_allowance_seconds}"
        printf 'rebar_system_row_count=%s\n' "${rebar_system_row_count}"
        printf 'rebar_joni_row_count=%s\n' "${rebar_joni_row_count}"
        printf 'rebar_primary_semantic_timeout=%s\n' "${REBAR_PRIMARY_SEMANTIC_TIMEOUT}"
        printf 'rebar_verification_heap_pretouch=false\n'
        printf 'rebar_measurement_heap_pretouch=true\n'
        printf 'rebar_primary_verification_estimate_seconds=%s\n' \
            "${rebar_primary_verification_estimate_seconds}"
        printf 'rebar_joni_semantic_timeout=%s\n' "${REBAR_JONI_SEMANTIC_TIMEOUT}"
        printf 'rebar_joni_verification_estimate_seconds=%s\n' \
            "${rebar_joni_verification_estimate_seconds}"
        printf 'rebar_measurement_window_seconds_per_row=%s\n' "${rebar_measurement_window_seconds_per_row}"
        printf 'rebar_measurement_estimate_seconds=%s\n' "${rebar_measurement_estimate_seconds}"
        printf 'rebar_build_startup_allowance_seconds=%s\n' "${rebar_build_startup_allowance_seconds}"
        printf 'pinned_trino_preparation_allowance_seconds=%s\n' "${pinned_trino_preparation_allowance_seconds}"
        printf 'retained_memory_census_count=%s\n' "${retained_memory_census_count}"
        printf 'retained_memory_census_allowance_seconds_per_process=%s\n' \
            "${retained_memory_census_allowance_seconds_per_process}"
        printf 'retained_memory_census_estimate_seconds=%s\n' "${retained_memory_census_estimate_seconds}"
        printf 'additional_duration_allowance_seconds=%s\n' "${additional_duration_allowance_seconds}"
        printf 'route_static_duration_estimate_seconds=%s\n' "${route_duration_estimate_seconds}"
        printf 'route_static_duration_limit_seconds=%s\n' "${maximum_route_seconds}"
    } > "${RESULT_DIR}/run-metadata.txt"
    printf 'status=planned\n' > "${status_file}"
    trap - EXIT
    echo "Planned ${SHARD_ID} ${PROTOCOL} ${ROUTE} ($(($(wc -l < "${RESULT_DIR}/expected-rows.tsv") - 1)) rows)"
    exit 0
fi

command -v taskset >/dev/null || {
    echo "taskset is required for baseline execution" >&2
    exit 1
}

candidate_commit=
engine_tree=
if git -C "${ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    dirty_paths=$(git -C "${ROOT}" status --porcelain --untracked-files=all -- src/main src/test tools/re2-benchmark)
    if [[ -n "${dirty_paths}" ]]; then
        echo "Candidate source and benchmark tooling must be committed before execution:" >&2
        printf '%s\n' "${dirty_paths}" >&2
        exit 1
    fi
    candidate_commit=$(git -C "${ROOT}" rev-parse HEAD)
    engine_tree=$(git -C "${ROOT}" rev-parse HEAD:src/main)
else
    candidate_commit=${BASELINE_CANDIDATE_COMMIT:-${REGULATOR_SNAPSHOT_COMMIT:-}}
    engine_tree=${BASELINE_ENGINE_TREE:-${BASELINE_EXPECTED_ENGINE_TREE:-}}
    if [[ -z "${candidate_commit}" || -z "${engine_tree}" || -z ${REGULATOR_SNAPSHOT_SHA256:-} ]]; then
        echo "Archived execution requires BASELINE_CANDIDATE_COMMIT, BASELINE_ENGINE_TREE, and REGULATOR_SNAPSHOT_SHA256 provenance" >&2
        exit 1
    fi
fi
if [[ -n ${BASELINE_EXPECTED_ENGINE_TREE:-} && "${engine_tree}" != "${BASELINE_EXPECTED_ENGINE_TREE}" ]]; then
    echo "Expected src/main tree ${BASELINE_EXPECTED_ENGINE_TREE}, found ${engine_tree}" >&2
    exit 1
fi

source_hash()
{
    (
        cd "${ROOT}"
        find src/main -type f -print0 |
            sort -z |
            xargs -0 sha256sum
    ) | sha256sum | awk '{print $1}'
}

source_hash_before=$(source_hash)
manifest_sha256=$(sha256sum "${MANIFEST}" | awk '{print $1}')
{
    printf 'candidate_commit=%s\n' "${candidate_commit}"
    printf 'engine_tree=%s\n' "${engine_tree}"
    printf 'engine_content_sha256=%s\n' "${source_hash_before}"
    printf 'manifest_sha256=%s\n' "${manifest_sha256}"
    printf 'shard_id=%s\n' "${SHARD_ID}"
    printf 'protocol=%s\n' "${PROTOCOL}"
    printf 'route=%s\n' "${ROUTE}"
    printf 'handler=%s\n' "${handler}"
    printf 'systems=%s\n' "${systems}"
    printf 'trino_output_contract=joni-slice-output-v1\n'
    printf 'forks=%s\n' "${FORKS}"
    printf 'warmup=%sx%s\n' "${WARMUP_ITERATIONS}" "${WARMUP_TIME}"
    printf 'measurement=%sx%s\n' "${MEASUREMENT_ITERATIONS}" "${MEASUREMENT_TIME}"
    printf 'calibration_forks=%s\n' "${CALIBRATION_FORKS}"
    printf 'calibration_warmup=%sx%s\n' \
        "${CALIBRATION_WARMUP_ITERATIONS}" "${CALIBRATION_WARMUP_TIME}"
    printf 'calibration_measurement=%sx%s\n' \
        "${CALIBRATION_MEASUREMENT_ITERATIONS}" "${CALIBRATION_MEASUREMENT_TIME}"
    printf 'calibration_prime_invocations=%s\n' "${CALIBRATION_PRIME_INVOCATIONS}"
    printf 'native_repetitions=%s\n' "${NATIVE_REPETITIONS}"
    printf 'native_minimum_time=%s\n' "${NATIVE_MINIMUM_TIME}"
    printf 'cpu_list=%s\n' "${CPU_LIST}"
    printf 'heap_size=%s\n' "${HEAP_SIZE}"
    printf 'rebar_verification_heap_pretouch=false\n'
    printf 'rebar_measurement_heap_pretouch=true\n'
    printf 'shared_work_dir=%s\n' "${SHARED_WORK_DIR}"
    printf 'defer_acceptance=%s\n' "${DEFER_ACCEPTANCE}"
    printf 'joni_comparator_order=%s\n' "${JONI_COMPARATOR_ORDER}"
    printf 'jmh_execution_protocol=%s\n' "${JMH_EXECUTION_PROTOCOL}"
    printf 'jmh_suite_order=%s\n' "${JMH_SUITE_ORDER}"
    printf 'jmh_row_count=%s\n' "${jmh_row_count}"
    printf 'jmh_process_count=%s\n' "${JMH_PROCESS_COUNT}"
    printf 'joni_jmh_forks=%s\n' "${JONI_JMH_FORKS}"
    printf 'candidate_jmh_row_count=%s\n' "${candidate_jmh_row_count}"
    printf 'joni_jmh_row_count=%s\n' "${joni_jmh_row_count}"
    printf 'jmh_invocation_count=%s\n' "${jmh_invocation_count}"
    printf 'jmh_process_warmup_iterations=%s\n' "${JMH_PROCESS_WARMUP_ITERATIONS}"
    printf 'jmh_process_measurement_iterations=%s\n' "${JMH_PROCESS_MEASUREMENT_ITERATIONS}"
    printf 'jmh_iteration_time=%s\n' "${JMH_PROCESS_TIME}"
    printf 'extended_warmup_time=%s\n' "${EXTENDED_WARMUP_TIME}"
    printf 'extended_warmup_row_count=%s\n' "${extended_warmup_row_count}"
    printf 'extended_warmup_additional_seconds=%s\n' "${extended_warmup_additional_seconds}"
    printf 'jmh_static_duration_estimate_seconds=%s\n' "${jmh_duration_estimate_seconds}"
    printf 'protocol_qualification_required=%s\n' "${protocol_qualification_required}"
    printf 'protocol_representative_count=%s\n' "${protocol_representative_count}"
    printf 'protocol_qualification_estimate_seconds=%s\n' "${protocol_qualification_estimate_seconds}"
    printf 'full_protocol_row_count=%s\n' "${full_protocol_row_count}"
    printf 'full_protocol_estimate_seconds=%s\n' "${full_protocol_estimate_seconds}"
    printf 'native_system_row_count=%s\n' "${native_system_row_count}"
    printf 'native_measurement_estimate_seconds=%s\n' "${native_measurement_estimate_seconds}"
    printf 'native_build_startup_allowance_seconds=%s\n' "${native_build_startup_allowance_seconds}"
    printf 'rebar_system_row_count=%s\n' "${rebar_system_row_count}"
    printf 'rebar_joni_row_count=%s\n' "${rebar_joni_row_count}"
    printf 'rebar_joni_semantic_timeout=%s\n' "${REBAR_JONI_SEMANTIC_TIMEOUT}"
    printf 'rebar_joni_verification_estimate_seconds=%s\n' \
        "${rebar_joni_verification_estimate_seconds}"
    printf 'rebar_measurement_window_seconds_per_row=%s\n' "${rebar_measurement_window_seconds_per_row}"
    printf 'rebar_measurement_estimate_seconds=%s\n' "${rebar_measurement_estimate_seconds}"
    printf 'rebar_build_startup_allowance_seconds=%s\n' "${rebar_build_startup_allowance_seconds}"
    printf 'pinned_trino_preparation_allowance_seconds=%s\n' "${pinned_trino_preparation_allowance_seconds}"
    printf 'retained_memory_census_count=%s\n' "${retained_memory_census_count}"
    printf 'retained_memory_census_allowance_seconds_per_process=%s\n' \
        "${retained_memory_census_allowance_seconds_per_process}"
    printf 'retained_memory_census_estimate_seconds=%s\n' "${retained_memory_census_estimate_seconds}"
    printf 'additional_duration_allowance_seconds=%s\n' "${additional_duration_allowance_seconds}"
    printf 'route_static_duration_estimate_seconds=%s\n' "${route_duration_estimate_seconds}"
    printf 'route_static_duration_limit_seconds=%s\n' "${maximum_route_seconds}"
    printf 'jmh_row_order=jmh-deterministic-no-cli-randomization\n'
    printf 'jmh_host_compiler_hints=jmh-generated-and-installed\n'
} > "${RESULT_DIR}/run-metadata.txt"

(cd "${ROOT}" && ./mvnw "${MAVEN_ARGS[@]}" -q -DskipTests test-compile) | tee "${RESULT_DIR}/logs/maven-test-compile.log"
(cd "${ROOT}" && ./mvnw "${MAVEN_ARGS[@]}" -q dependency:build-classpath \
    -DincludeScope=test \
    -Dmdep.outputFile="${RESULT_DIR}/classpath.txt")
CLASSPATH="${ROOT}/target/test-classes:${ROOT}/target/classes:$(cat "${RESULT_DIR}/classpath.txt")"
if [[ -n "${REGULATOR_RELEASE_VERSION:-}" ]]; then
    CLASSPATH=$(python3 "${ROOT}/tools/re2-benchmark/language/released_artifact.py" \
        --root "${ROOT}" --version "${REGULATOR_RELEASE_VERSION}" \
        --classpath "${CLASSPATH}" --production-classes "${ROOT}/target/classes" \
        --destination "${RESULT_DIR}/release")
    cp "${RESULT_DIR}/release/release-artifact.json" "${RESULT_DIR}/release-artifact.json"
    printf 'release_version=%s\nrelease_artifact_sha256=%s\n' \
        "${REGULATOR_RELEASE_VERSION}" \
        "$(sha256sum "${RESULT_DIR}/release-artifact.json" | awk '{print $1}')" >> "${RESULT_DIR}/run-metadata.txt"
fi

route_jvm_arguments=(--illegal-native-access=deny --add-modules=jdk.incubator.vector)
if [[ "${ROUTE}" == native-access ]]; then
    route_jvm_arguments=(--enable-native-access=ALL-UNNAMED "${route_jvm_arguments[@]}")
fi
jmh_process_jvm_arguments=("${route_jvm_arguments[@]}" -Xms"${HEAP_SIZE}" -Xmx"${HEAP_SIZE}" -XX:+AlwaysPreTouch)
jmh_fork_arguments="${jmh_process_jvm_arguments[*]}"
jmh_launcher_jvm_arguments=("${route_jvm_arguments[@]}" -Xms64m -Xmx256m)
jmh_host_compiler_arguments=()

prepare_jmh_host_compiler_arguments()
{
    local benchmark_classpath=$1
    local identifier=$2
    local compiler_hints_path="${RESULT_DIR}/logs/${identifier}-compiler-hints.txt"
    local jvm_arguments_path="${RESULT_DIR}/logs/${identifier}-compiler-arguments.txt"

    java "${route_jvm_arguments[@]}" \
        -cp "${CLASSPATH}:${benchmark_classpath}" \
        io.airlift.regulator.BenchmarkCompilerHints \
        "${compiler_hints_path}" \
        "${jvm_arguments_path}"
    jmh_host_compiler_arguments=()
    while IFS= read -r compiler_argument; do
        jmh_host_compiler_arguments+=("${compiler_argument}")
    done < "${jvm_arguments_path}"
    if [[ ${#jmh_host_compiler_arguments[@]} -eq 0 ]]; then
        echo "JMH produced no host compiler arguments for ${identifier}" >&2
        exit 1
    fi
}

semantic_log="${RESULT_DIR}/logs/semantic-tests.log"
semantic_report_dir="${RESULT_DIR}/semantic-reports"
default_semantic_report_dir="${ROOT}/target/surefire-reports"
mkdir -p "${semantic_report_dir}"
mkdir -p "${default_semantic_report_dir}"
find "${default_semantic_report_dir}" -mindepth 1 -delete
expected_native_access=false
semantic_java_options='--illegal-native-access=deny'
if [[ "${ROUTE}" == native-access ]]; then
    expected_native_access=true
    semantic_java_options='--enable-native-access=ALL-UNNAMED --illegal-native-access=deny'
fi
taskset --cpu-list "${CPU_LIST}" \
    java "${route_jvm_arguments[@]}" \
    -Dio.airlift.regulator.dfa.native-reader-probe="${expected_native_access}" \
    -cp "${CLASSPATH}" \
    io.airlift.regulator.DfaAbsolutePointerProbe "${expected_native_access}" \
    2>&1 | tee "${semantic_log}"
(
    cd "${ROOT}"
    JDK_JAVA_OPTIONS="${semantic_java_options}" \
        ./mvnw "${MAVEN_ARGS[@]}" -q \
        -Dtest="${semantic_tests}" test
) 2>&1 | tee -a "${semantic_log}"
if ! compgen -G "${default_semantic_report_dir}/TEST-*.xml" >/dev/null; then
    echo "Semantic tests produced no Surefire XML reports" >&2
    exit 1
fi
cp "${default_semantic_report_dir}"/TEST-*.xml "${semantic_report_dir}/"

prepare_pinned_trino()
{
    PINNED_TRINO_WORK_DIR="${SHARED_WORK_DIR}/pinned-trino"
    mkdir -p "${PINNED_TRINO_WORK_DIR}"
    if ! TRINO_COMPARATOR_NATIVE_ACCESS="${expected_native_access}" \
            TRINO_COMPARATOR_WORK_DIR="${PINNED_TRINO_WORK_DIR}" \
            "${ROOT}/tools/re2-benchmark/trino-joni/verify.sh" \
            >> "${semantic_log}" 2>&1; then
        TRINO_COMPARATOR_WORK_DIR="${PINNED_TRINO_WORK_DIR}" \
            "${ROOT}/tools/re2-benchmark/trino-joni/prepare.sh" 2>&1 | tee -a "${semantic_log}"
    fi
    TRINO_COMPARATOR_NATIVE_ACCESS="${expected_native_access}" \
        TRINO_COMPARATOR_WORK_DIR="${PINNED_TRINO_WORK_DIR}" \
        "${ROOT}/tools/re2-benchmark/trino-joni/verify.sh" 2>&1 | tee -a "${semantic_log}"
    PINNED_TRINO_CLASSPATH=$(cat "${PINNED_TRINO_WORK_DIR}/classpath.txt")
    if tr ':' '\n' <<<"${PINNED_TRINO_CLASSPATH}" | grep -Eq '/(com/google/re2j/re2j|io/trino/trino-re2j)/'; then
        echo "Pinned Trino classpath contains a historical RE2J artifact" >&2
        exit 1
    fi
}

prepare_final_line()
{
    prepare_pinned_trino
    local classes="${RESULT_DIR}/generated/trino-final-line/classes"
    local generated="${RESULT_DIR}/generated/trino-final-line/sources"
    mkdir -p "${classes}" "${generated}"
    javac \
        -cp "${PINNED_TRINO_CLASSPATH}" \
        -processorpath "${PINNED_TRINO_CLASSPATH}" \
        -processor org.openjdk.jmh.generators.BenchmarkProcessor \
        -d "${classes}" \
        -s "${generated}" \
        "${ROOT}/tools/re2-benchmark/trino-final-line/BenchmarkTrinoFinalLine.java"
    javac \
        -proc:none \
        -cp "${classes}:${PINNED_TRINO_CLASSPATH}" \
        -d "${classes}" \
        "${ROOT}/tools/re2-benchmark/trino-final-line/TrinoFinalLineDifferentialVerifier.java"
    SPECIAL_CLASSPATH="${classes}:${PINNED_TRINO_CLASSPATH}"
    java "${route_jvm_arguments[@]}" -cp "${SPECIAL_CLASSPATH}" \
        io.trino.operator.scalar.TrinoFinalLineDifferentialVerifier 2>&1 | tee -a "${semantic_log}"
    grep -Fx 'Verified 176 Trino final-line operation comparisons' "${semantic_log}" >/dev/null
}

prepare_trino_like()
{
    prepare_pinned_trino
    local classes="${RESULT_DIR}/generated/trino-like/classes"
    local generated="${RESULT_DIR}/generated/trino-like/sources"
    mkdir -p "${classes}" "${generated}"
    javac \
        -cp "${PINNED_TRINO_CLASSPATH}" \
        -processorpath "${PINNED_TRINO_CLASSPATH}" \
        -processor org.openjdk.jmh.generators.BenchmarkProcessor \
        -d "${classes}" \
        -s "${generated}" \
        "${ROOT}/tools/re2-benchmark/trino-like/BenchmarkTrinoLike.java"
    javac \
        -proc:none \
        -cp "${classes}:${PINNED_TRINO_CLASSPATH}" \
        -d "${classes}" \
        "${ROOT}/tools/re2-benchmark/trino-like/TrinoLikeDifferentialVerifier.java"
    SPECIAL_CLASSPATH="${classes}:${PINNED_TRINO_CLASSPATH}"
    java "${route_jvm_arguments[@]}" -cp "${SPECIAL_CLASSPATH}" \
        io.airlift.regulator.benchmark.BenchmarkTrinoLike | tee -a "${semantic_log}"
    grep -Fx 'Verified 60 Trino LIKE lifecycle comparisons' "${semantic_log}" >/dev/null
    java "${route_jvm_arguments[@]}" -cp "${SPECIAL_CLASSPATH}" \
        TrinoLikeDifferentialVerifier 2>&1 | tee -a "${semantic_log}"
    grep -Fx 'Verified 530255 Trino LIKE comparisons' "${semantic_log}" >/dev/null
    grep -Fx 'Verified 810155 escaped Trino LIKE comparisons' "${semantic_log}" >/dev/null
    grep -Fx 'Verified 21770 encoded Trino LIKE comparisons' "${semantic_log}" >/dev/null
}

prepare_rebar()
{
    # Rebar's verification deadline includes JVM startup. Pre-touch can spend
    # that entire allowance faulting unused heap pages before the runner starts.
    # Keep each engine's heap size and correctness checks, but defer page faults
    # in this untimed phase. Timed invocations explicitly restore pre-touch.
    local REBAR_HEAP_PRETOUCH=false
    export REBAR_HEAP_PRETOUCH
    REBAR_ROOT=${REBAR_ROOT:?REBAR_ROOT is required for Rebar baseline shards}
    export TRINO_COMPARATOR_WORK_DIR="${SHARED_WORK_DIR}/pinned-trino"
    if tr ',' '\n' <<<"${systems}" | grep -Fqx joni; then
        prepare_pinned_trino
        cp "${PINNED_TRINO_WORK_DIR}/provenance.properties" \
            "${RESULT_DIR}/raw/rebar-joni-provenance.properties"
    fi
    REBAR_EXECUTABLE="${REBAR_ROOT}/target/release/rebar"
    local rebar_shared_root="${SHARED_WORK_DIR}/rebar/${SHARD_ID}"
    local prepared_receipt="${rebar_shared_root}/prepared.properties"
    REBAR_BENCHMARK_DIRECTORY="${rebar_shared_root}/benchmarks"
    REBAR_PORTABLE_NATIVE="${SHARED_WORK_DIR}/rebar/native-portable"
    REBAR_TUNED_NATIVE="${SHARED_WORK_DIR}/rebar/native-tuned"
    REBAR_MODEL_FILTER='^(?:compile|count|count-spans|count-captures|grep|grep-captures)$'
    REBAR_PRIMARY_BENCHMARK_FILTER=$(python3 "${RESULT_TOOL}" jmh-filter \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --system "${candidate_system}")
    REBAR_JONI_BENCHMARK_FILTER=
    if tr ',' '\n' <<<"${systems}" | grep -Fqx joni; then
        REBAR_JONI_BENCHMARK_FILTER=$(python3 "${RESULT_TOOL}" jmh-filter \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --system joni)
    fi

    mkdir -p "${rebar_shared_root}"
    "${ROOT}/tools/re2-benchmark/manifests/validate-extended-rebar.sh" "${REBAR_ROOT}" \
        2>&1 | tee -a "${semantic_log}"
    local rebar_commit
    rebar_commit=$("${ROOT}/tools/re2-benchmark/manifests/rebar-revision.sh" "${REBAR_ROOT}")
    local shared_signature
    shared_signature=$(
        printf '%s\n' \
            "candidate_commit=${candidate_commit}" \
            "engine_tree=${engine_tree}" \
            "manifest_sha256=${manifest_sha256}" \
            "rebar_commit=${rebar_commit}" \
            "comparator_order=${REBAR_COMPARATOR_ORDER:-forward}" \
            "prepare_sha256=$(sha256sum "${ROOT}/tools/re2-benchmark/rebar/prepare.sh" | awk '{print $1}')" \
            "extended_manifest_sha256=$(sha256sum "${ROOT}/tools/re2-benchmark/manifests/rebar-extended-workloads.tsv" | awk '{print $1}')" |
            sha256sum |
            awk '{print $1}')

    rebar_shared_preparation_is_valid()
    {
        [[ -x "${REBAR_EXECUTABLE}" && -s "${prepared_receipt}" && \
                -s "${REBAR_BENCHMARK_DIRECTORY}/engines.toml" && \
                -d "${REBAR_BENCHMARK_DIRECTORY}/definitions" && \
                -s "${ROOT}/target/rebar-classpath.txt" ]] || return 1
        grep -Fqx "signature=${shared_signature}" "${prepared_receipt}" || return 1
        local definitions_sha256
        definitions_sha256=$(
            find "${REBAR_BENCHMARK_DIRECTORY}/definitions" -type f -print0 |
                sort -z |
                xargs -0 sha256sum |
                sha256sum |
                awk '{print $1}')
        grep -Fqx "definitions_sha256=${definitions_sha256}" "${prepared_receipt}" || return 1
        grep -Fqx "engines_sha256=$(sha256sum "${REBAR_BENCHMARK_DIRECTORY}/engines.toml" | awk '{print $1}')" \
            "${prepared_receipt}" || return 1
        grep -Fqx "runner_sha256=$(sha256sum "${ROOT}/tools/re2-benchmark/rebar/run-regulator.sh" | awk '{print $1}')" \
            "${prepared_receipt}" || return 1
    }

    if ! rebar_shared_preparation_is_valid; then
        command -v cargo >/dev/null || {
            echo "cargo is required to prepare Rebar baseline shards" >&2
            exit 1
        }
        cargo build --release --locked --manifest-path "${REBAR_ROOT}/Cargo.toml" \
            2>&1 | tee "${RESULT_DIR}/logs/rebar-build.log"

        "${ROOT}/tools/re2-benchmark/rebar/prepare.sh" \
            "${REBAR_ROOT}" \
            "${REBAR_BENCHMARK_DIRECTORY}" \
            "${REBAR_PORTABLE_NATIVE}" \
            "${REBAR_TUNED_NATIVE}"
        python3 - "${REBAR_BENCHMARK_DIRECTORY}" "${REBAR_COMPARATOR_ORDER:-forward}" <<'PY'
import pathlib
import sys

root = pathlib.Path(sys.argv[1]) / "definitions"
order = sys.argv[2]
if order == "forward":
    engines = ("re2/pinned-host-tuned-before", "regulator/re2", "joni/trino", "re2/pinned-host-tuned-after", "regulator/re2-object")
elif order == "reverse":
    engines = ("regulator/re2-object", "re2/pinned-host-tuned-after", "joni/trino", "regulator/re2", "re2/pinned-host-tuned-before")
else:
    raise SystemExit(f"invalid REBAR_COMPARATOR_ORDER: {order}")

for path in sorted(root.rglob("*.toml")):
    if "curated" in path.relative_to(root).parts:
        continue
    lines = path.read_text().splitlines(keepends=True)
    output = []
    for line in lines:
        if line.strip() in {"'re2',", '"re2",'}:
            indentation = line[:len(line) - len(line.lstrip())]
            output.extend(f"{indentation}'{engine}',\n" for engine in engines)
        else:
            output.append(line)
    path.write_text("".join(output))
PY
        "${ROOT}/tools/re2-benchmark/rebar/build-regulator.sh"
        local definitions_sha256
        definitions_sha256=$(
            find "${REBAR_BENCHMARK_DIRECTORY}/definitions" -type f -print0 |
                sort -z |
                xargs -0 sha256sum |
                sha256sum |
                awk '{print $1}')
        {
            printf 'signature=%s\n' "${shared_signature}"
            printf 'definitions_sha256=%s\n' "${definitions_sha256}"
            printf 'engines_sha256=%s\n' "$(sha256sum "${REBAR_BENCHMARK_DIRECTORY}/engines.toml" | awk '{print $1}')"
            printf 'runner_sha256=%s\n' "$(sha256sum "${ROOT}/tools/re2-benchmark/rebar/run-regulator.sh" | awk '{print $1}')"
        } > "${prepared_receipt}"
    fi

    if tr ',' '\n' <<<"${systems}" | grep -Eq '^native-re2-(before|after)$' && \
            [[ ! -x "${REBAR_PORTABLE_NATIVE}/target/release/main" || \
            ! -x "${REBAR_TUNED_NATIVE}/target/release/main" ]]; then
        "${ROOT}/tools/re2-golden/fetch-dependencies.sh" \
            2>&1 | tee "${RESULT_DIR}/logs/rebar-native-fetch.log"
        "${ROOT}/tools/re2-benchmark/rebar/build-native.sh" \
            "${REBAR_ROOT}" \
            "${REBAR_PORTABLE_NATIVE}" \
            "${REBAR_TUNED_NATIVE}" \
            "${ROOT}/target/re2-golden-dependencies/re2" \
            2>&1 | tee "${RESULT_DIR}/logs/rebar-native-build.log"
    fi

    if [[ "${ROUTE}" == native-access ]]; then
        REBAR_PRIMARY_ENGINE_FILTER='^(?:re2/pinned-host-tuned-before|regulator/re2|re2/pinned-host-tuned-after)$'
    else
        REBAR_PRIMARY_ENGINE_FILTER='^regulator/re2-object$'
    fi
    REBAR_BUILD_ENGINE_FILTER=${REBAR_PRIMARY_ENGINE_FILTER}
    if [[ -n "${REBAR_JONI_BENCHMARK_FILTER}" ]]; then
        REBAR_BUILD_ENGINE_FILTER='^(?:re2/pinned-host-tuned-before|regulator/re2|re2/pinned-host-tuned-after|joni/trino)$'
    fi
    "${REBAR_EXECUTABLE}" build \
        -d "${REBAR_BENCHMARK_DIRECTORY}" \
        -e "${REBAR_BUILD_ENGINE_FILTER}" 2>&1 | tee "${RESULT_DIR}/logs/rebar-engine-build.log"

    local primary_systems
    primary_systems=$(tr ',' '\n' <<<"${systems}" | grep -Fvx joni | paste -sd, -)
    local primary_verification_status=0
    "${REBAR_EXECUTABLE}" measure \
        -d "${REBAR_BENCHMARK_DIRECTORY}" \
        -e "${REBAR_PRIMARY_ENGINE_FILTER}" \
        -f "${REBAR_PRIMARY_BENCHMARK_FILTER}" \
        -m "${REBAR_MODEL_FILTER}" \
        --list > "${RESULT_DIR}/raw/rebar-primary-engine-manifest.csv"
    taskset --cpu-list "${CPU_LIST}" \
        "${REBAR_EXECUTABLE}" measure \
        -d "${REBAR_BENCHMARK_DIRECTORY}" \
        -e "${REBAR_PRIMARY_ENGINE_FILTER}" \
        -f "${REBAR_PRIMARY_BENCHMARK_FILTER}" \
        -m "${REBAR_MODEL_FILTER}" \
        --max-time "${REBAR_MAXIMUM_TIME}" \
        --max-warmup-time "${REBAR_WARMUP_TIME}" \
        --timeout "${REBAR_PRIMARY_SEMANTIC_TIMEOUT}" \
        --test > "${RESULT_DIR}/raw/rebar-primary-verification.csv" \
        2> "${RESULT_DIR}/logs/rebar-primary-verification.log" || primary_verification_status=$?
    python3 "${SCRIPT_DIR}/validate-rebar-shard-verification.py" \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --systems "${primary_systems}" \
        --verification "${RESULT_DIR}/raw/rebar-primary-verification.csv" \
        --outcomes "${RESULT_DIR}/raw/rebar-outcomes.tsv" \
        --command-status "${primary_verification_status}"
    cat "${RESULT_DIR}/raw/rebar-primary-verification.csv" >> "${semantic_log}"

    if [[ -n "${REBAR_JONI_BENCHMARK_FILTER}" ]]; then
        local joni_verification_status=0
        "${REBAR_EXECUTABLE}" measure \
            -d "${REBAR_BENCHMARK_DIRECTORY}" \
            -e '^joni/trino$' \
            -f "${REBAR_JONI_BENCHMARK_FILTER}" \
            -m "${REBAR_MODEL_FILTER}" \
            --list > "${RESULT_DIR}/raw/rebar-joni-engine-manifest.csv"
        taskset --cpu-list "${CPU_LIST}" \
            "${REBAR_EXECUTABLE}" measure \
            -d "${REBAR_BENCHMARK_DIRECTORY}" \
            -e '^joni/trino$' \
            -f "${REBAR_JONI_BENCHMARK_FILTER}" \
            -m "${REBAR_MODEL_FILTER}" \
            --max-time "${REBAR_MAXIMUM_TIME}" \
            --max-warmup-time "${REBAR_WARMUP_TIME}" \
            --timeout "${REBAR_JONI_SEMANTIC_TIMEOUT}" \
            --test > "${RESULT_DIR}/raw/rebar-joni-verification.csv" \
            2> "${RESULT_DIR}/logs/rebar-joni-verification.log" || joni_verification_status=$?
        python3 "${SCRIPT_DIR}/validate-rebar-shard-verification.py" \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --systems joni \
            --verification "${RESULT_DIR}/raw/rebar-joni-verification.csv" \
            --outcomes "${RESULT_DIR}/raw/rebar-outcomes.tsv" \
            --command-status "${joni_verification_status}" \
            --append
        cat "${RESULT_DIR}/raw/rebar-joni-verification.csv" >> "${semantic_log}"
        REBAR_JONI_BENCHMARK_FILTER=$(python3 "${RESULT_TOOL}" jmh-filter \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --system joni \
            --rebar-outcomes "${RESULT_DIR}/raw/rebar-outcomes.tsv")
    fi
}

case "${handler}" in
    trino-operations)
        if [[ "${ROUTE}" == native-access ]]; then
            prepare_pinned_trino
        fi
        ;;
    trino-final-line) prepare_final_line ;;
    trino-like) prepare_trino_like ;;
    rebar) prepare_rebar ;;
    memory-census) prepare_pinned_trino ;;
esac

semantic_evidence_arguments=(
    --reports "${semantic_report_dir}"
    --expected-tests "${semantic_tests}"
    --route "${ROUTE}"
    --handler "${handler}"
    --native-access "${expected_native_access}"
    --semantic-log "${semantic_log}"
    --output "${RESULT_DIR}/semantic-evidence.tsv"
)
if [[ -s "${RESULT_DIR}/raw/rebar-primary-verification.csv" ]]; then
    semantic_evidence_arguments+=(
        --rebar-verification "${RESULT_DIR}/raw/rebar-primary-verification.csv")
fi
if [[ -s "${RESULT_DIR}/raw/rebar-joni-verification.csv" ]]; then
    semantic_evidence_arguments+=(
        --rebar-verification "${RESULT_DIR}/raw/rebar-joni-verification.csv")
fi
python3 "${SCRIPT_DIR}/semantic_evidence.py" "${semantic_evidence_arguments[@]}"
semantic_result_digest=$(sha256sum "${RESULT_DIR}/semantic-evidence.tsv" | awk '{print $1}')
{
    printf 'route\ttests\toutcome\tsemantic_result_digest\n'
    printf '%s\t%s\taccepted\t%s\n' "${ROUTE}" "${semantic_tests}" "${semantic_result_digest}"
} > "${RESULT_DIR}/semantic-gate.tsv"

if [[ "${VERIFICATION_ONLY}" == true ]]; then
    [[ "$(source_hash)" == "${source_hash_before}" ]] || exit 1
    printf 'verification_only=true\n' >> "${RESULT_DIR}/run-metadata.txt"
    echo "Completed semantic preparation ${SHARD_ID} ${ROUTE}"
    exit 0
fi

run_calibration_jmh_with_classpath()
{
    local classpath=$1
    local filter=$2
    local output=$3
    shift 3
    taskset --cpu-list "${CPU_LIST}" \
        java "${jmh_launcher_jvm_arguments[@]}" \
        -cp "${classpath}" \
        org.openjdk.jmh.Main "${filter}" \
        -f "${CALIBRATION_FORKS}" \
        -wi "${CALIBRATION_WARMUP_ITERATIONS}" \
        -i "${CALIBRATION_MEASUREMENT_ITERATIONS}" \
        -w "${CALIBRATION_WARMUP_TIME}" \
        -r "${CALIBRATION_MEASUREMENT_TIME}" \
        -foe true \
        -prof gc \
        -jvmArgs "${jmh_fork_arguments}" \
        -rf json \
        -rff "${output}" \
        "$@"
}

run_calibration_jmh()
{
    run_calibration_jmh_with_classpath "${CLASSPATH}" "$@"
}

run_protocol_qualification()
{
    [[ "${protocol_qualification_required}" == true ]] || return 0

    local protocol_replica_id=${CAMPAIGN_REPLICA_ID}
    if [[ "${protocol_replica_id}" == 4 ]]; then
        protocol_replica_id=1
    fi
    local qualification_output="${RESULT_DIR}/protocol-qualification.tsv"
    local representative_index=0
    while IFS=$'\t' read -r representative_shard representative_replica representative_route \
            representative_system manifest_row_id benchmark parameters classpath_kind; do
        [[ "${representative_shard}" == "${SHARD_ID}" ]] || continue
        [[ "${representative_replica}" == "${protocol_replica_id}" ]] || continue
        [[ "${representative_route}" == "${ROUTE}" ]] || continue
        is_full_protocol_row "${benchmark}" "${parameters}" && continue
        representative_index=$((representative_index + 1))

        if ! awk -F '\t' -v row_id="${manifest_row_id}" -v target_system="${representative_system}" \
                'NR > 1 && $1 == row_id && $4 == target_system {found=1} END {exit !found}' \
                "${RESULT_DIR}/expected-rows.tsv"; then
            echo "Protocol representative is not an expected route row: ${manifest_row_id}/${representative_system}" >&2
            exit 1
        fi

        local classpath
        case "${classpath_kind}" in
            regulator) classpath=${CLASSPATH} ;;
            pinned-trino) classpath=${PINNED_TRINO_CLASSPATH:?Pinned Trino classpath is not prepared} ;;
            special) classpath=${SPECIAL_CLASSPATH:?Special benchmark classpath is not prepared} ;;
            *)
                echo "Unknown protocol representative classpath: ${classpath_kind}" >&2
                exit 1
                ;;
        esac

        local filter
        filter=$(python3 - "${benchmark}" <<'PY'
import re
import sys

print(f"^{re.escape(sys.argv[1])}$")
PY
)
        local parameter_arguments=()
        if [[ "${parameters}" != - ]]; then
            local parameter
            while IFS= read -r parameter; do
                parameter_arguments+=(-p "${parameter}")
            done < <(tr ';' '\n' <<<"${parameters}")
        fi

        local output_prefix="${RESULT_DIR}/raw/protocol-${representative_index}"
        local reference_output="${output_prefix}-reference.json"
        taskset --cpu-list "${CPU_LIST}" \
            java "${jmh_launcher_jvm_arguments[@]}" \
            -cp "${classpath}" \
            org.openjdk.jmh.Main "${filter}" \
            -f "${PROTOCOL_QUALIFICATION_PROCESS_COUNT}" \
            -wi 10 \
            -i 10 \
            -w 1s \
            -r 1s \
            -foe true \
            -prof gc \
            -jvmArgs "${jmh_fork_arguments}" \
            -rf json \
            -rff "${reference_output}" \
            "${parameter_arguments[@]}" |
            tee "${RESULT_DIR}/logs/protocol-${representative_index}-reference.log"

        local specialized_warmup_time=50ms
        if [[ "${benchmark}" == io.airlift.regulator.BenchmarkRe2Compile* ]]; then
            specialized_warmup_time=${EXTENDED_WARMUP_TIME}
        fi
        prepare_jmh_host_compiler_arguments "${classpath}" "protocol-${representative_index}"
        local specialized_arguments=()
        local process_index
        for ((process_index = 1; process_index <= PROTOCOL_QUALIFICATION_PROCESS_COUNT; process_index++)); do
            local process_output="${output_prefix}-specialized-process-${process_index}.json"
            local profiler_arguments=()
            if [[ ${process_index} -eq 1 ]]; then
                profiler_arguments=(-prof gc)
            fi
            specialized_arguments+=(--specialized "${process_output}")
            taskset --cpu-list "${CPU_LIST}" \
                java "${jmh_process_jvm_arguments[@]}" \
                "${jmh_host_compiler_arguments[@]}" \
                -cp "${classpath}" \
                org.openjdk.jmh.Main "${filter}" \
                -f 0 \
                -wi 10 \
                -i 10 \
                -w "${specialized_warmup_time}" \
                -r 50ms \
                -foe true \
                "${profiler_arguments[@]}" \
                -rf json \
                -rff "${process_output}" \
                "${parameter_arguments[@]}" |
                tee "${RESULT_DIR}/logs/protocol-${representative_index}-specialized-${process_index}.log"
        done

        local representative_output="${RESULT_DIR}/raw/protocol-qualification-${representative_index}.tsv"
        python3 "${RESULT_TOOL}" protocol-equivalence \
            --reference "${reference_output}" \
            "${specialized_arguments[@]}" \
            --manifest-row-id "${manifest_row_id}" \
            --system "${representative_system}" \
            --route "${ROUTE}" \
            --specialized-protocol "${PROTOCOL_QUALIFICATION_PROCESS_COUNT}-independent-jvms-10x${specialized_warmup_time}-warmup-10x50ms-measurement" \
            --maximum 0.05 \
            --output "${representative_output}"
        if [[ ${representative_index} -eq 1 ]]; then
            cat "${representative_output}" > "${qualification_output}"
        else
            tail -n +2 "${representative_output}" >> "${qualification_output}"
        fi
    done < <(tail -n +2 "${PROTOCOL_REPRESENTATIVES}")

    if [[ ${representative_index} -ne ${protocol_representative_count} ]]; then
        echo "Protocol representative count changed during execution: expected ${protocol_representative_count}, found ${representative_index}" >&2
        exit 1
    fi
}

JMH_INPUT_FILES=()
set_jmh_input_files()
{
    local output_base=${1%.json}
    local full_protocol_index=0
    local full_protocol_benchmark
    local process_index
    JMH_INPUT_FILES=()
    for ((process_index = 1; process_index <= JMH_PROCESS_COUNT; process_index++)); do
        JMH_INPUT_FILES+=("${output_base}-process-${process_index}.json")
    done
    if [[ "${PROTOCOL}" == qualification ]]; then
        while IFS=$'\t' read -r full_protocol_benchmark _ _; do
            full_protocol_index=$((full_protocol_index + 1))
            for ((process_index = 1; process_index <= JMH_PROCESS_COUNT; process_index++)); do
                JMH_INPUT_FILES+=("${output_base}-bounded-${full_protocol_index}-process-${process_index}.json")
            done
            JMH_INPUT_FILES+=("${output_base}-full-${full_protocol_index}.json")
        done < <(full_protocol_benchmarks)
    fi
}

run_jmh_with_classpath()
{
    local classpath=$1
    local filter=$2
    local output=$3
    shift 3
    local output_base=${output%.json}
    local warmup_time=${JMH_PROCESS_WARMUP_TIME_OVERRIDE:-${JMH_PROCESS_TIME}}
    prepare_jmh_host_compiler_arguments "${classpath}" "$(basename "${output_base}")"
    local exclusion_arguments=()
    local full_protocol_benchmark
    local full_parameters
    local bounded_parameters
    local full_protocol_index=0
    if [[ "${PROTOCOL}" == qualification ]]; then
        while IFS=$'\t' read -r full_protocol_benchmark _ _; do
            exclusion_arguments+=(-e "^${full_protocol_benchmark//./\\.}$")
        done < <(full_protocol_benchmarks)
    fi
    local process_index
    JMH_INPUT_FILES=()
    for ((process_index = 1; process_index <= JMH_PROCESS_COUNT; process_index++)); do
        local process_output="${output_base}-process-${process_index}.json"
        local profiler_arguments=()
        if [[ ${process_index} -eq 1 ]]; then
            profiler_arguments=(-prof gc)
        fi
        JMH_INPUT_FILES+=("${process_output}")
        taskset --cpu-list "${CPU_LIST}" \
            java "${jmh_process_jvm_arguments[@]}" \
            "${jmh_host_compiler_arguments[@]}" \
            -cp "${classpath}" \
            org.openjdk.jmh.Main "${filter}" \
            -f 0 \
            -wi "${JMH_PROCESS_WARMUP_ITERATIONS}" \
            -i "${JMH_PROCESS_MEASUREMENT_ITERATIONS}" \
            -w "${warmup_time}" \
            -r "${JMH_PROCESS_TIME}" \
            -foe true \
            "${profiler_arguments[@]}" \
            "${exclusion_arguments[@]}" \
            -rf json \
            -rff "${process_output}" \
            "$@"
    done

    if [[ "${PROTOCOL}" == qualification ]]; then
        while IFS=$'\t' read -r full_protocol_benchmark full_parameters bounded_parameters; do
            full_protocol_index=$((full_protocol_index + 1))
            local bounded_output_base="${output_base}-bounded-${full_protocol_index}"
            local bounded_parameter_arguments=()
            local parameter
            while IFS= read -r parameter; do
                bounded_parameter_arguments+=(-p "${parameter}")
            done < <(tr ';' '\n' <<<"${bounded_parameters}")
            for ((process_index = 1; process_index <= JMH_PROCESS_COUNT; process_index++)); do
                local bounded_process_output="${bounded_output_base}-process-${process_index}.json"
                local bounded_profiler_arguments=()
                if [[ ${process_index} -eq 1 ]]; then
                    bounded_profiler_arguments=(-prof gc)
                fi
                JMH_INPUT_FILES+=("${bounded_process_output}")
                taskset --cpu-list "${CPU_LIST}" \
                    java "${jmh_process_jvm_arguments[@]}" \
                    "${jmh_host_compiler_arguments[@]}" \
                    -cp "${classpath}" \
                    org.openjdk.jmh.Main "^${full_protocol_benchmark//./\\.}$" \
                    -f 0 \
                    -wi "${JMH_PROCESS_WARMUP_ITERATIONS}" \
                    -i "${JMH_PROCESS_MEASUREMENT_ITERATIONS}" \
                    -w "${warmup_time}" \
                    -r "${JMH_PROCESS_TIME}" \
                    -foe true \
                    "${bounded_profiler_arguments[@]}" \
                    -rf json \
                    -rff "${bounded_process_output}" \
                    "${bounded_parameter_arguments[@]}"
            done

            local full_protocol_output="${output_base}-full-${full_protocol_index}.json"
            local full_parameter_arguments=()
            while IFS= read -r parameter; do
                full_parameter_arguments+=(-p "${parameter}")
            done < <(tr ';' '\n' <<<"${full_parameters}")
            JMH_INPUT_FILES+=("${full_protocol_output}")
            taskset --cpu-list "${CPU_LIST}" \
                java "${jmh_launcher_jvm_arguments[@]}" \
                -cp "${classpath}" \
                org.openjdk.jmh.Main "^${full_protocol_benchmark//./\\.}$" \
                -f 5 \
                -wi 10 \
                -i 10 \
                -w 1s \
                -r 1s \
                -foe true \
                -prof gc \
                -jvmArgs "${jmh_fork_arguments}" \
                -rf json \
                -rff "${full_protocol_output}" \
                "${full_parameter_arguments[@]}"
        done < <(full_protocol_benchmarks)
    fi
}

run_jmh()
{
    run_jmh_with_classpath "${CLASSPATH}" "$@"
}

run_joni_jmh_with_classpath()
{
    local classpath=$1
    local filter=$2
    local output=$3
    shift 3
    local joni_time=${BASELINE_JONI_JMH_TIME:-${JMH_PROCESS_TIME}}
    taskset --cpu-list "${CPU_LIST}" \
        java "${jmh_launcher_jvm_arguments[@]}" \
        -cp "${classpath}" \
        org.openjdk.jmh.Main "${filter}" \
        -f "${JONI_JMH_FORKS}" \
        -wi "${JMH_PROCESS_WARMUP_ITERATIONS}" \
        -i "${JMH_PROCESS_MEASUREMENT_ITERATIONS}" \
        -w "${joni_time}" \
        -r "${joni_time}" \
        -foe true \
        -prof gc \
        -jvmArgs "${jmh_fork_arguments}" \
        -rf json \
        -rff "${output}" \
        "$@"
}

joni_process_arguments=()

secondary_metric_arguments=()
if [[ ${JMH_PROCESS_COUNT} -gt 1 ]]; then
    secondary_metric_arguments=(--allow-missing-secondary-metrics)
fi
mixed_process_arguments=()
if [[ "${PROTOCOL}" == qualification && ${full_protocol_row_count} -gt 0 ]]; then
    mixed_process_arguments=(
        --allow-mixed-process-sets
        --expected-process-groups "${JMH_PROCESS_COUNT}")
fi

calibration_filter='^io\.airlift\.regulator\.BenchmarkExpressionPlans\.findLiteral$'
calibration_parameters=(-p workload=KIB_LATE)
run_protocol_qualification
if [[ ${CALIBRATION_PRIME_INVOCATIONS} -gt 0 ]]; then
    run_calibration_jmh "${calibration_filter}" "${RESULT_DIR}/raw/calibration-prime.json" \
        "${calibration_parameters[@]}" |
        tee "${RESULT_DIR}/logs/calibration-prime.log"
fi
run_calibration_jmh "${calibration_filter}" "${RESULT_DIR}/raw/calibration-before.json" \
    "${calibration_parameters[@]}" |
    tee "${RESULT_DIR}/logs/calibration-before.log"

normalized_files=()

run_generic_jmh_shard()
{
    jmh_suite_rows()
    {
        if [[ "${JMH_SUITE_ORDER}" == reverse ]]; then
            awk 'NR > 1 {rows[++count]=$0} END {for (row_number=count; row_number >= 1; row_number--) print rows[row_number]}' \
                "${SCRIPT_DIR}/jmh-suites.tsv"
        else
            tail -n +2 "${SCRIPT_DIR}/jmh-suites.tsv"
        fi
    }
    while IFS=$'\t' read -r configured_shard suite _ parameters _ _ _; do
        [[ "${configured_shard}" == "${SHARD_ID}" ]] || continue
        if ! awk -F '\t' -v suite="${suite}" 'NR > 1 && $3 == suite {found=1} END {exit !found}' \
                "${RESULT_DIR}/expected-rows.tsv"; then
            continue
        fi
        filter=$(python3 "${RESULT_TOOL}" jmh-filter \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --suite "${suite}" \
            --system "${candidate_system}")
        output="${RESULT_DIR}/raw/${suite}-${ROUTE}.json"
        extra_arguments=()
        if [[ "${parameters}" != - ]]; then
            extra_arguments+=(-p "${parameters}")
        fi
        if [[ ("${suite}" == compile || "${suite}" == public-compile) && "${PROTOCOL}" != smoke ]]; then
            JMH_PROCESS_WARMUP_TIME_OVERRIDE=${EXTENDED_WARMUP_TIME} \
                run_jmh "${filter}" "${output}" "${extra_arguments[@]}" |
                tee "${RESULT_DIR}/logs/${suite}-${ROUTE}.log"
        else
            run_jmh "${filter}" "${output}" "${extra_arguments[@]}" |
                tee "${RESULT_DIR}/logs/${suite}-${ROUTE}.log"
        fi
        set_jmh_input_files "${output}"
        normalized="${RESULT_DIR}/normalized/${suite}-${candidate_system}.tsv"
        python3 "${RESULT_TOOL}" normalize-jmh \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --suite "${suite}" \
            --system "${candidate_system}" \
            --input "${JMH_INPUT_FILES[@]}" \
            "${secondary_metric_arguments[@]}" \
            "${mixed_process_arguments[@]}" \
            --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
            "${precision_arguments[@]}" \
            --output "${normalized}"
        normalized_files+=("${normalized}")
    done < <(jmh_suite_rows)
}

run_traditional_shard()
{
    local filter
    filter=$(python3 "${RESULT_TOOL}" jmh-filter \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --system "${candidate_system}")
    local java_output="${RESULT_DIR}/raw/traditional-${ROUTE}.json"
    local java_normalized="${RESULT_DIR}/normalized/traditional-${candidate_system}.tsv"

    if [[ "${ROUTE}" == native-access && ${native_system_row_count} -gt 0 ]]; then
        RE2_NATIVE_TUNING=host "${ROOT}/tools/re2-benchmark/build.sh" |
            tee "${RESULT_DIR}/logs/native-build.log"
        local native_filter
        native_filter=$(python3 "${RESULT_TOOL}" native-filter \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --system native-re2-before)
        taskset --cpu-list "${CPU_LIST}" \
            "${ROOT}/target/re2-benchmark-build/regexp_benchmark" \
            --benchmark_filter="${native_filter}" \
            --benchmark_min_time="${NATIVE_MINIMUM_TIME}" \
            --benchmark_repetitions="${NATIVE_REPETITIONS}" \
            --benchmark_report_aggregates_only=true \
            --benchmark_out_format=json \
            --benchmark_out="${RESULT_DIR}/raw/traditional-native-before.json" |
            tee "${RESULT_DIR}/logs/traditional-native-before.log"
        python3 "${RESULT_TOOL}" normalize-native \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --system native-re2-before \
            --input "${RESULT_DIR}/raw/traditional-native-before.json" \
            --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
            --output "${RESULT_DIR}/normalized/traditional-native-before.tsv"
        normalized_files+=("${RESULT_DIR}/normalized/traditional-native-before.tsv")
    fi

    if [[ "${SHARD_ID}" == traditional-capture ]]; then
        local extended_prefix='(?:io\.airlift\.regulator\.BenchmarkRe2Practical\.compilePhase|io\.airlift\.regulator\.BenchmarkRe2Parse\.searchPhoneRe2)'
        local extended_benchmark_prefixes=(
            io.airlift.regulator.BenchmarkRe2Practical.compilePhase
            io.airlift.regulator.BenchmarkRe2Parse.searchPhoneRe2)
        local regular_filter="^(?!${extended_prefix})${filter#^}"
        local extended_filter="^(?=${extended_prefix})${filter#^}"
        local regular_output="${RESULT_DIR}/raw/traditional-regular-${ROUTE}.json"
        local extended_output="${RESULT_DIR}/raw/traditional-extended-${ROUTE}.json"
        local regular_normalized="${RESULT_DIR}/normalized/traditional-regular-${candidate_system}.tsv"
        local extended_normalized="${RESULT_DIR}/normalized/traditional-extended-${candidate_system}.tsv"
        local regular_selection=()
        local extended_selection=()
        local benchmark_prefix
        for benchmark_prefix in "${extended_benchmark_prefixes[@]}"; do
            regular_selection+=(--exclude-benchmark-prefix "${benchmark_prefix}")
            extended_selection+=(--include-benchmark-prefix "${benchmark_prefix}")
        done

        run_jmh "${regular_filter}" "${regular_output}" |
            tee "${RESULT_DIR}/logs/traditional-regular-${ROUTE}.log"
        set_jmh_input_files "${regular_output}"
        python3 "${RESULT_TOOL}" normalize-jmh \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --suite traditional \
            --system "${candidate_system}" \
            "${regular_selection[@]}" \
            --input "${JMH_INPUT_FILES[@]}" \
            "${secondary_metric_arguments[@]}" \
            "${mixed_process_arguments[@]}" \
            --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
            "${precision_arguments[@]}" \
            --allow-traditional-unpaired \
            --output "${regular_normalized}"
        normalized_files+=("${regular_normalized}")

        JMH_PROCESS_WARMUP_TIME_OVERRIDE=${EXTENDED_WARMUP_TIME} \
            run_jmh "${extended_filter}" "${extended_output}" |
            tee "${RESULT_DIR}/logs/traditional-extended-${ROUTE}.log"
        set_jmh_input_files "${extended_output}"
        python3 "${RESULT_TOOL}" normalize-jmh \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --suite traditional \
            --system "${candidate_system}" \
            "${extended_selection[@]}" \
            --input "${JMH_INPUT_FILES[@]}" \
            "${secondary_metric_arguments[@]}" \
            "${mixed_process_arguments[@]}" \
            --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
            "${precision_arguments[@]}" \
            --output "${extended_normalized}"
        normalized_files+=("${extended_normalized}")
    else
        run_jmh "${filter}" "${java_output}" |
            tee "${RESULT_DIR}/logs/traditional-${ROUTE}.log"
        set_jmh_input_files "${java_output}"
        python3 "${RESULT_TOOL}" normalize-jmh \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --suite traditional \
            --system "${candidate_system}" \
            --input "${JMH_INPUT_FILES[@]}" \
            "${secondary_metric_arguments[@]}" \
            "${mixed_process_arguments[@]}" \
            --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
            "${precision_arguments[@]}" \
            --allow-traditional-unpaired \
            --output "${java_normalized}"
        normalized_files+=("${java_normalized}")
    fi

    if [[ "${ROUTE}" == native-access && ${native_system_row_count} -gt 0 ]]; then
        taskset --cpu-list "${CPU_LIST}" \
            "${ROOT}/target/re2-benchmark-build/regexp_benchmark" \
            --benchmark_filter="${native_filter}" \
            --benchmark_min_time="${NATIVE_MINIMUM_TIME}" \
            --benchmark_repetitions="${NATIVE_REPETITIONS}" \
            --benchmark_report_aggregates_only=true \
            --benchmark_out_format=json \
            --benchmark_out="${RESULT_DIR}/raw/traditional-native-after.json" |
            tee "${RESULT_DIR}/logs/traditional-native-after.log"
        python3 "${RESULT_TOOL}" normalize-native \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --system native-re2-after \
            --input "${RESULT_DIR}/raw/traditional-native-after.json" \
            --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
            --output "${RESULT_DIR}/normalized/traditional-native-after.tsv"
        normalized_files+=("${RESULT_DIR}/normalized/traditional-native-after.tsv")
        python3 "${RESULT_TOOL}" bracket \
            --manifest "${MANIFEST}" \
            --before "${RESULT_DIR}/normalized/traditional-native-before.tsv" \
            --after "${RESULT_DIR}/normalized/traditional-native-after.tsv" \
            --output "${RESULT_DIR}/native-bracket.tsv"
    fi
}

run_trino_operations_candidate()
{
    local filter
    local output="${RESULT_DIR}/raw/trino-operations-${candidate_system}.json"
    local normalized="${RESULT_DIR}/normalized/trino-operations-${candidate_system}.tsv"
    filter=$(python3 "${RESULT_TOOL}" jmh-filter \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --system "${candidate_system}")
    run_jmh "${filter}" "${output}" |
        tee "${RESULT_DIR}/logs/trino-operations-${candidate_system}.log"
    set_jmh_input_files "${output}"
    python3 "${RESULT_TOOL}" normalize-jmh \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --system "${candidate_system}" \
        --input "${JMH_INPUT_FILES[@]}" \
        "${secondary_metric_arguments[@]}" \
        "${mixed_process_arguments[@]}" \
        --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
        "${precision_arguments[@]}" \
        --output "${normalized}"
    normalized_files+=("${normalized}")
}

run_trino_operations_joni()
{
    local filter
    local output="${RESULT_DIR}/raw/trino-operations-joni.json"
    local normalized="${RESULT_DIR}/normalized/trino-operations-joni.tsv"
    filter=$(python3 "${RESULT_TOOL}" jmh-filter \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --system joni)
    run_joni_jmh_with_classpath "${PINNED_TRINO_CLASSPATH}" "${filter}" "${output}" |
        tee "${RESULT_DIR}/logs/trino-operations-joni.log"
    JMH_INPUT_FILES=("${output}")
    python3 "${RESULT_TOOL}" normalize-jmh \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --system joni \
        --input "${JMH_INPUT_FILES[@]}" \
        "${joni_process_arguments[@]}" \
        "${secondary_metric_arguments[@]}" \
        "${mixed_process_arguments[@]}" \
        --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
        "${precision_arguments[@]}" \
        --output "${normalized}"
    normalized_files+=("${normalized}")
}

run_ordered_joni_comparison()
{
    local regulator_function=$1
    local joni_function=$2
    if [[ "${JONI_COMPARATOR_ORDER}" == forward ]]; then
        "${regulator_function}"
        "${joni_function}"
    else
        "${joni_function}"
        "${regulator_function}"
    fi
}

run_trino_operations_shard()
{
    if [[ "${ROUTE}" == native-access ]]; then
        run_ordered_joni_comparison run_trino_operations_candidate run_trino_operations_joni
    else
        run_trino_operations_candidate
    fi
}

final_line_mappings=(
    --method-map containsRegulator=contains
    --method-map countRegulator=count
    --method-map positionRegulator=position
    --method-map extractRegulator=extract
    --method-map extractAllRegulator=extractAll
    --method-map splitRegulator=split
    --method-map replaceRegulator=replace
    --method-map replaceLambdaRegulator=replaceLambda
)
final_line_joni_mappings=(
    --method-map containsJoni=contains
    --method-map countJoni=count
    --method-map positionJoni=position
    --method-map extractJoni=extract
    --method-map extractAllJoni=extractAll
    --method-map splitJoni=split
    --method-map replaceJoni=replace
    --method-map replaceLambdaJoni=replaceLambda
)

run_final_line_candidate()
{
    local filter='^io\.trino\.operator\.scalar\.BenchmarkTrinoFinalLine\.(containsRegulator|countRegulator|positionRegulator|extractRegulator|extractAllRegulator|splitRegulator|replaceRegulator|replaceLambdaRegulator)$'
    local output="${RESULT_DIR}/raw/trino-final-line-${candidate_system}.json"
    local normalized="${RESULT_DIR}/normalized/trino-final-line-${candidate_system}.tsv"
    run_jmh_with_classpath "${SPECIAL_CLASSPATH}" "${filter}" "${output}" |
        tee "${RESULT_DIR}/logs/trino-final-line-${candidate_system}.log"
    set_jmh_input_files "${output}"
    python3 "${RESULT_TOOL}" normalize-mapped-jmh \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --suite trino-final-line \
        --system "${candidate_system}" \
        --input "${JMH_INPUT_FILES[@]}" \
        "${secondary_metric_arguments[@]}" \
        "${mixed_process_arguments[@]}" \
        --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
        "${precision_arguments[@]}" \
        --benchmark-class io.trino.operator.scalar.BenchmarkTrinoFinalLine \
        "${final_line_mappings[@]}" \
        --output "${normalized}"
    normalized_files+=("${normalized}")
}

run_final_line_joni()
{
    local filter='^io\.trino\.operator\.scalar\.BenchmarkTrinoFinalLine\.(containsJoni|countJoni|positionJoni|extractJoni|extractAllJoni|splitJoni|replaceJoni|replaceLambdaJoni)$'
    local output="${RESULT_DIR}/raw/trino-final-line-joni.json"
    local normalized="${RESULT_DIR}/normalized/trino-final-line-joni.tsv"
    run_joni_jmh_with_classpath "${SPECIAL_CLASSPATH}" "${filter}" "${output}" |
        tee "${RESULT_DIR}/logs/trino-final-line-joni.log"
    JMH_INPUT_FILES=("${output}")
    python3 "${RESULT_TOOL}" normalize-mapped-jmh \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --suite trino-final-line \
        --system joni \
        --input "${JMH_INPUT_FILES[@]}" \
        "${joni_process_arguments[@]}" \
        "${secondary_metric_arguments[@]}" \
        --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
        "${precision_arguments[@]}" \
        --benchmark-class io.trino.operator.scalar.BenchmarkTrinoFinalLine \
        "${final_line_joni_mappings[@]}" \
        --output "${normalized}"
    normalized_files+=("${normalized}")
}

run_final_line_shard()
{
    if [[ "${ROUTE}" == native-access ]]; then
        run_ordered_joni_comparison run_final_line_candidate run_final_line_joni
    else
        run_final_line_candidate
    fi
}

run_trino_like_system()
{
    local system=$1
    local method=$2
    local operation=${3:-matches}
    local scenario_arguments=()
    if [[ "${SHARD_ID}" == like-dfa-single-use ]]; then
        scenario_arguments=(-p scenario=ANY_ASCII,ANY_MULTIBYTE)
    fi
    local filter="^io\\.airlift\\.regulator\\.benchmark\\.BenchmarkTrinoLike\\.${method}$"
    local output="${RESULT_DIR}/raw/trino-like-${system}.json"
    local normalized="${RESULT_DIR}/normalized/trino-like-${system}.tsv"
    run_jmh_with_classpath "${SPECIAL_CLASSPATH}" "${filter}" "${output}" "${scenario_arguments[@]}" |
        tee "${RESULT_DIR}/logs/trino-like-${system}.log"
    set_jmh_input_files "${output}"
    python3 "${RESULT_TOOL}" normalize-mapped-jmh \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --suite trino-like \
        --system "${system}" \
        --input "${JMH_INPUT_FILES[@]}" \
        "${secondary_metric_arguments[@]}" \
        --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
        "${precision_arguments[@]}" \
        --benchmark-class io.airlift.regulator.benchmark.BenchmarkTrinoLike \
        --method-map "${method}=${operation}" \
        --output "${normalized}"
    normalized_files+=("${normalized}")
}

run_trino_like_shard()
{
    case "${SHARD_ID}" in
        like-dfa-single-use)
            run_trino_like_system regulator candidateSingleUse singleUse
            run_trino_like_system trino-sql trinoSqlSingleUse singleUse
            run_trino_like_system trino-optimized trinoOptimizedSingleUse singleUse
            ;;
        like-compile)
            run_trino_like_system regulator candidateCompile compile
            run_trino_like_system trino-sql trinoSqlCompile compile
            ;;
        like-single-use)
            run_trino_like_system regulator candidateSingleUse singleUse
            run_trino_like_system trino-sql trinoSqlSingleUse singleUse
            ;;
        *)
            run_trino_like_system regulator candidate
            run_trino_like_system trino-optimized trinoOptimized
            run_trino_like_system trino-sql trinoSql
            ;;
    esac
}

run_lifecycle_candidate()
{
    local filter
    local output="${RESULT_DIR}/raw/lifecycle-${candidate_system}.json"
    local normalized="${RESULT_DIR}/normalized/lifecycle-${candidate_system}.tsv"
    filter=$(python3 "${RESULT_TOOL}" jmh-filter \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --suite lifecycle \
        --system "${candidate_system}")
    run_jmh "${filter}" "${output}" |
        tee "${RESULT_DIR}/logs/lifecycle-${candidate_system}.log"
    set_jmh_input_files "${output}"
    python3 "${RESULT_TOOL}" normalize-jmh \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --suite lifecycle \
        --system "${candidate_system}" \
        --input "${JMH_INPUT_FILES[@]}" \
        "${secondary_metric_arguments[@]}" \
        --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
        "${precision_arguments[@]}" \
        --output "${normalized}"
    normalized_files+=("${normalized}")
}

run_memory_census_for_system()
{
    local system=$1
    local engine=$2
    local output_directory="${RESULT_DIR}/raw/memory-census-${system}"
    local normalized="${RESULT_DIR}/normalized/retained-memory-${system}.tsv"
    local java_arguments="-Xms${HEAP_SIZE} -Xmx${HEAP_SIZE} -XX:+AlwaysPreTouch ${route_jvm_arguments[*]}"
    JAVA_CENSUS_ARGUMENTS="${java_arguments}" \
        taskset --cpu-list "${CPU_LIST}" \
        "${ROOT}/tools/re2-benchmark/memory/run-census.sh" "${output_directory}" |
        tee "${RESULT_DIR}/logs/memory-census-${system}.log"
    # The census launcher resolves Joni through Maven, so independently bind
    # that artifact to the same pin used by the Trino comparator.
    source "${ROOT}/tools/re2-benchmark/trino-joni/pins.env"
    local census_joni_jar="${HOME}/.m2/repository/io/airlift/joni/${TRINO_COMPARATOR_JONI_VERSION}/joni-${TRINO_COMPARATOR_JONI_VERSION}.jar"
    if [[ ! -f "${census_joni_jar}" ]] || \
            [[ $(sha256sum "${census_joni_jar}" | awk '{print $1}') != "${TRINO_COMPARATOR_JONI_SHA256}" ]]; then
        echo "Lifecycle census did not use the pinned Joni artifact" >&2
        exit 1
    fi
    python3 "${ROOT}/tools/re2-benchmark/memory/summarize.py" \
        "${output_directory}/memory-census.csv" \
        "${output_directory}/summary"
    python3 "${RESULT_TOOL}" normalize-memory-census \
        --manifest "${MANIFEST}" \
        --shard "${SHARD_ID}" \
        --system "${system}" \
        --engine "${engine}" \
        --input "${output_directory}/memory-census.csv" \
        --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
        --output "${normalized}"
    normalized_files+=("${normalized}")
}

run_lifecycle_regulator_phase()
{
    run_lifecycle_candidate
}

run_lifecycle_joni_phase()
{
    run_memory_census_for_system joni Joni
}

run_lifecycle_shard()
{
    run_lifecycle_regulator_phase
}

run_memory_census_candidate_phase()
{
    run_memory_census_for_system "${candidate_system}" RE2
}

run_memory_census_shard()
{
    if [[ "${ROUTE}" == native-access ]]; then
        run_ordered_joni_comparison run_memory_census_candidate_phase run_lifecycle_joni_phase
    else
        run_memory_census_candidate_phase
    fi
}

run_rebar_shard()
{
    local REBAR_HEAP_PRETOUCH=true
    export REBAR_HEAP_PRETOUCH
    run_rebar_primary_phase()
    {
        taskset --cpu-list "${CPU_LIST}" \
            "${REBAR_EXECUTABLE}" measure \
            -d "${REBAR_BENCHMARK_DIRECTORY}" \
            -e "${REBAR_PRIMARY_ENGINE_FILTER}" \
            -f "${REBAR_PRIMARY_BENCHMARK_FILTER}" \
            -m "${REBAR_MODEL_FILTER}" \
            --max-time "${REBAR_MAXIMUM_TIME}" \
            --max-warmup-time "${REBAR_WARMUP_TIME}" \
            --timeout "${REBAR_MEASUREMENT_TIMEOUT}" \
            > "${RESULT_DIR}/raw/rebar-primary-measurements.csv" \
            2> "${RESULT_DIR}/logs/rebar-primary-measurements.log"
    }

    run_rebar_joni_phase()
    {
        local measurement_status=0
        taskset --cpu-list "${CPU_LIST}" \
            "${REBAR_EXECUTABLE}" measure \
            -d "${REBAR_BENCHMARK_DIRECTORY}" \
            -e '^joni/trino$' \
            -f "${REBAR_JONI_BENCHMARK_FILTER}" \
            -m "${REBAR_MODEL_FILTER}" \
            --max-time "${REBAR_MAXIMUM_TIME}" \
            --max-warmup-time "${REBAR_WARMUP_TIME}" \
            --timeout "${REBAR_MEASUREMENT_TIMEOUT}" \
            > "${RESULT_DIR}/raw/rebar-joni-measurements.csv" \
            2> "${RESULT_DIR}/logs/rebar-joni-measurements.log" || measurement_status=$?
        # Rebar measurement mode reports per-row errors in CSV and exits zero.
        # A process failure remains fatal, even when an earlier row timed out.
        python3 "${SCRIPT_DIR}/validate-rebar-shard-verification.py" \
            --manifest "${MANIFEST}" --shard "${SHARD_ID}" --systems joni \
            --verification "${RESULT_DIR}/raw/rebar-joni-measurements.csv" \
            --outcomes "${RESULT_DIR}/raw/rebar-outcomes.tsv" \
            --command-status "${measurement_status}" --append --measurement
    }

    if [[ -n "${REBAR_JONI_BENCHMARK_FILTER}" ]]; then
        run_ordered_joni_comparison run_rebar_primary_phase run_rebar_joni_phase
    else
        : > "${RESULT_DIR}/raw/rebar-joni-measurements.csv"
        run_rebar_primary_phase
    fi

    local system
    while IFS= read -r system; do
        local measurements="${RESULT_DIR}/raw/rebar-primary-measurements.csv"
        if [[ "${system}" == joni ]]; then
            measurements="${RESULT_DIR}/raw/rebar-joni-measurements.csv"
        fi
        local normalized="${RESULT_DIR}/normalized/rebar-${system}.tsv"
        python3 "${RESULT_TOOL}" normalize-rebar \
            --manifest "${MANIFEST}" \
            --shard "${SHARD_ID}" \
            --system "${system}" \
            --input "${measurements}" \
            --rebar-outcomes "${RESULT_DIR}/raw/rebar-outcomes.tsv" \
            --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
            --output "${normalized}"
        normalized_files+=("${normalized}")
    done < <(tr ',' '\n' <<<"${systems}")

    if tr ',' '\n' <<<"${systems}" | grep -Fqx native-re2-before &&
            tr ',' '\n' <<<"${systems}" | grep -Fqx native-re2-after; then
        python3 "${RESULT_TOOL}" bracket \
            --manifest "${MANIFEST}" \
            --before "${RESULT_DIR}/normalized/rebar-native-re2-before.tsv" \
            --after "${RESULT_DIR}/normalized/rebar-native-re2-after.tsv" \
            --output "${RESULT_DIR}/native-bracket.tsv"
    fi
}

case "${handler}" in
    jmh) run_generic_jmh_shard ;;
    traditional) run_traditional_shard ;;
    trino-operations) run_trino_operations_shard ;;
    trino-final-line) run_final_line_shard ;;
    trino-like) run_trino_like_shard ;;
    lifecycle) run_lifecycle_shard ;;
    memory-census) run_memory_census_shard ;;
    rebar) run_rebar_shard ;;
    *)
        echo "Internal error: unsupported handler ${handler}" >&2
        exit 1
        ;;
esac

run_calibration_jmh "${calibration_filter}" "${RESULT_DIR}/raw/calibration-after.json" \
    "${calibration_parameters[@]}" |
    tee "${RESULT_DIR}/logs/calibration-after.log"
python3 "${RESULT_TOOL}" calibration \
    --before "${RESULT_DIR}/raw/calibration-before.json" \
    --after "${RESULT_DIR}/raw/calibration-after.json" \
    --output "${RESULT_DIR}/calibration.tsv"

if [[ ${#normalized_files[@]} -eq 0 ]]; then
    echo "Shard produced no normalized result files" >&2
    exit 1
fi
combine_arguments=()
if [[ -f "${RESULT_DIR}/raw/rebar-outcomes.tsv" ]]; then
    combine_arguments+=(--rebar-outcomes "${RESULT_DIR}/raw/rebar-outcomes.tsv")
fi
python3 "${RESULT_TOOL}" combine \
    --expected "${RESULT_DIR}/expected-rows.tsv" \
    --output "${RESULT_DIR}/observed-rows.tsv" \
    "${combine_arguments[@]}" \
    "${normalized_files[@]}"

source_hash_after=$(source_hash)
if [[ "${source_hash_after}" != "${source_hash_before}" ]]; then
    echo "src/main changed during baseline shard execution" >&2
    exit 1
fi
printf 'engine_content_sha256_after=%s\n' "${source_hash_after}" >> "${RESULT_DIR}/run-metadata.txt"
printf 'observed_row_count=%s\n' "$(($(wc -l < "${RESULT_DIR}/observed-rows.tsv") - 1))" >> "${RESULT_DIR}/run-metadata.txt"

(
    cd "${RESULT_DIR}"
    find raw logs -type f -print0 |
        sort -z |
        xargs -0 sha256sum
) > "${RESULT_DIR}/raw-artifacts.sha256"

drift_arguments=(
    --calibration "${RESULT_DIR}/calibration.tsv"
    --maximum 0.05
)
if [[ -s "${RESULT_DIR}/native-bracket.tsv" ]]; then
    drift_arguments+=(--native-bracket "${RESULT_DIR}/native-bracket.tsv")
fi
python3 "${RESULT_TOOL}" validate-drift "${drift_arguments[@]}"

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

campaign_id=${RE2_CAMPAIGN_ID:-$(environment_value baseline_campaign)}
platform=${RE2_CAMPAIGN_PLATFORM:-$(environment_value platform)}
replica_id=${RE2_CAMPAIGN_REPLICA_ID:-$(environment_value replica)}
host_epoch=${RE2_CAMPAIGN_HOST_EPOCH:-$(environment_value host_epoch)}
architecture=${BASELINE_ARCHITECTURE:-${RE2_ENGINEERING_ARCHITECTURE:-$(environment_value campaign_architecture)}}
instance_id=${BASELINE_INSTANCE_ID:-$(environment_value instance_id)}
instance_type=${BASELINE_INSTANCE_TYPE:-$(environment_value instance_type)}
availability_zone=${BASELINE_AVAILABILITY_ZONE:-$(environment_value availability_zone)}
session_shard=${RE2_CAMPAIGN_SHARD_ID:-$(environment_value shard)}
if [[ "${session_shard}" != "${SHARD_ID}" ]]; then
    echo "Session shard ${session_shard:-unset} does not match requested shard ${SHARD_ID}" >&2
    exit 1
fi
for value_name in campaign_id platform replica_id host_epoch architecture instance_id instance_type availability_zone; do
    if [[ -z ${!value_name} || ${!value_name} == unknown || ${!value_name} == standalone ]]; then
        echo "Missing formal session identity: ${value_name}" >&2
        exit 1
    fi
done
canonical_systems=$(tr ',' '\n' <<<"${systems}" | sort | paste -sd, -)
session_file="${RESULT_DIR}/session.tsv"
if [[ "${DEFER_ACCEPTANCE}" == true ]]; then
    session_file="${RESULT_DIR}/route-session.tsv"
fi
{
    printf 'schema_version\tcampaign_id\tplatform\tshard_id\treplica_id\tinstance_id\thost_epoch\tarchitecture\tinstance_type\tavailability_zone\tsystems\n'
    printf '2\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "${campaign_id}" "${platform}" "${SHARD_ID}" "${replica_id}" "${instance_id}" \
        "${host_epoch}" "${architecture}" "${instance_type}" "${availability_zone}" "${canonical_systems}"
} > "${session_file}"

if [[ "${DEFER_ACCEPTANCE}" == true ]]; then
    echo "Completed deferred route ${SHARD_ID} ${PROTOCOL} ${ROUTE}"
    exit 0
fi

if [[ "${replica_id}" == 4 ]]; then
    acceptance_validator="${SCRIPT_DIR}/validate-confirmation-host-results.py"
else
    acceptance_validator="${SCRIPT_DIR}/validate-host-results.py"
fi
for required_evidence in \
        "${RESULT_DIR}/semantic-gate.tsv" \
        "${RESULT_DIR}/semantic-evidence.tsv" \
        "${RESULT_DIR}/calibration.tsv" \
        "${RESULT_DIR}/raw-artifacts.sha256" \
        "${RESULT_DIR}/session.tsv" \
        "${RESULT_DIR}/observed-rows.tsv"; do
    if [[ ! -s "${required_evidence}" ]]; then
        echo "Required acceptance evidence is missing or empty: ${required_evidence}" >&2
        exit 1
    fi
done
if [[ "${systems}" == *native-re2-before* && ! -s "${RESULT_DIR}/native-bracket.tsv" ]]; then
    echo "Native RE2 rows require native-bracket.tsv before acceptance" >&2
    exit 1
fi
if [[ "${protocol_qualification_required}" == true && ! -s "${RESULT_DIR}/protocol-qualification.tsv" ]]; then
    echo "Bounded JMH execution requires protocol-qualification.tsv before acceptance" >&2
    exit 1
fi
python3 "${acceptance_validator}" \
    --manifest "${MANIFEST}" \
    --session "${RESULT_DIR}/session.tsv" \
    --observed-rows "${RESULT_DIR}/observed-rows.tsv" \
    --receipt "${RESULT_DIR}/acceptance-receipt.tsv"
echo "Completed ${SHARD_ID} ${PROTOCOL} ${ROUTE}"
