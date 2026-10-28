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
ROW_PROTOCOL="${SCRIPT_DIR}/row-protocol.tsv"
UNIT_TOOL="${SCRIPT_DIR}/unit_session.py"
# run-unit.sh names a planned work unit; the route measures only the unit's rows
# and leaves acceptance to the unit.
UNIT_DIRECTORY=${BASELINE_UNIT_DIRECTORY:?A route runs only inside a work unit; set BASELINE_UNIT_DIRECTORY}
UNIT_CALIBRATION=${BASELINE_UNIT_CALIBRATION:-both}
# On a worker host a unit's calibrations become host evidence, so they are
# written outside the route's raw artifacts.
UNIT_CALIBRATION_DIRECTORY=${BASELINE_UNIT_CALIBRATION_DIRECTORY:-}
PLAN_ONLY=${BASELINE_PLAN_ONLY:-false}
PROTOCOL_QUALIFICATION=${BASELINE_PROTOCOL_QUALIFICATION:-true}
SHARED_WORK_DIR=${BASELINE_SHARED_WORK_DIR:-${ROOT}/target/baseline-shared}
JONI_COMPARATOR_ORDER=${JONI_COMPARATOR_ORDER:-forward}
CAMPAIGN_REPLICA_ID=${RE2_CAMPAIGN_REPLICA_ID:-1}
CPU_LIST=${BASELINE_CPU_LIST:-0}
HEAP_SIZE=${BASELINE_HEAP_SIZE:-8g}
MAVEN_ARGS=(-Dmaven.gitcommitid.skip=true)
PROTOCOL_QUALIFICATION_PROCESS_COUNT=5
JONI_JMH_FORKS=1

case "${PROTOCOL}" in
    smoke)
        CALIBRATION_FORKS=1
        CALIBRATION_WARMUP_ITERATIONS=3
        CALIBRATION_MEASUREMENT_ITERATIONS=3
        CALIBRATION_WARMUP_TIME=1s
        CALIBRATION_MEASUREMENT_TIME=1s
        CALIBRATION_PRIME_INVOCATIONS=1
        ;;
    qualification)
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
        ;;
    object-row)
        handler_column=4
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
IFS=$'\t' read -r _ _ _ _ _ semantic_tests blocker <<<"${dispatch_row}"
handler=$(cut -f "${handler_column}" <<<"${dispatch_row}")
if [[ "${handler}" == blocked ]]; then
    echo "Baseline shard ${SHARD_ID} route ${ROUTE} is not soundly dispatchable: ${blocker}" >&2
    exit 2
fi
if [[ "${handler}" == - ]]; then
    echo "Baseline shard ${SHARD_ID} does not define route ${ROUTE}" >&2
    exit 2
fi
# The unit's share of the route: the systems its rows need here.
systems=${BASELINE_UNIT_SYSTEMS:?A work unit route requires BASELINE_UNIT_SYSTEMS}

if [[ "${PLAN_ONLY}" != true && "${PLAN_ONLY}" != false ]]; then
    echo "BASELINE_PLAN_ONLY must be true or false" >&2
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
case "${UNIT_CALIBRATION}" in
    before | after | both | none) ;;
    *)
        echo "BASELINE_UNIT_CALIBRATION must be before, after, both, or none: ${UNIT_CALIBRATION}" >&2
        exit 1
        ;;
esac
JMH_SUITE_ORDER=forward
if ((CAMPAIGN_REPLICA_ID % 2 == 0)); then
    JMH_SUITE_ORDER=reverse
fi
UNIT_DIRECTORY=$(cd "${UNIT_DIRECTORY}" && pwd)
UNIT_ID=$(awk -F '\t' 'NR == 2 {print $1}' "${UNIT_DIRECTORY}/unit-rows.tsv")
SELECTION_MANIFEST="${UNIT_DIRECTORY}/expected-rows.tsv"

mkdir -p "${RESULT_DIR}"
RESULT_DIR=$(cd "${RESULT_DIR}" && pwd)
unexpected_existing=$(find "${RESULT_DIR}" -mindepth 1 -maxdepth 1 \
    ! -name environment.txt \
    ! -name environment-manifest.txt \
    ! -name engine-build.txt \
    -print -quit)
if [[ -n "${unexpected_existing}" ]]; then
    echo "Result directory must be empty: ${RESULT_DIR}" >&2
    exit 1
fi
mkdir -p "${RESULT_DIR}/raw" "${RESULT_DIR}/logs" "${RESULT_DIR}/normalized"
calibration_output_directory=${UNIT_CALIBRATION_DIRECTORY:-${RESULT_DIR}/raw}
mkdir -p "${calibration_output_directory}"

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
    --manifest "${SELECTION_MANIFEST}" \
    --shard "${SHARD_ID}" \
    --systems "${systems}" \
    --output "${RESULT_DIR}/expected-rows.tsv"

# The plan's frozen-protocol answers, from one load of the row protocol.
frozen_route_protocol=$(python3 "${RESULT_TOOL}" frozen-route-protocol \
    --row-protocol "${ROW_PROTOCOL}" \
    --expected "${RESULT_DIR}/expected-rows.tsv" \
    --representatives "${PROTOCOL_REPRESENTATIVES}")

# Prints the plan's frozen fields for a key, and for a row ID when the key is per row.
frozen_route_value()
{
    awk -F '\t' -v key="$1" -v row_id="${2-}" '
        $1 == key && (row_id == "" || $2 == row_id) {
            sub(/^[^\t]*\t/, "")
            if (row_id != "") sub(/^[^\t]*\t/, "")
            print
            exit
        }' <<<"${frozen_route_protocol}"
}

# Specialized-pattern suites run each workload of each system in its own JMH invocation, so
# each benchmark call site sees one pattern, as in a Trino query.
jmh_invocation_per_workload=false
if awk -F '\t' 'NR > 1 {n = split($3, part, "-"); for (i = 1; i <= n; i++) if (part[i] == "specialized") found = 1}
        END {exit !found}' "${RESULT_DIR}/expected-rows.tsv"; then
    jmh_invocation_per_workload=true
fi

# Every row's qualification protocol is frozen in row-protocol.tsv; a unit runs
# one selector per frozen protocol.
frozen_process_protocol=$(frozen_route_value process)
bounded_jmh_protocol=false
if [[ -n "${frozen_process_protocol}" ]]; then
    IFS=$'\t' read -r frozen_process_count frozen_warmup_iterations frozen_measurement_iterations \
        frozen_process_time _ frozen_maximum_relative_standard_error \
        <<<"${frozen_process_protocol}"
    if [[ "${frozen_maximum_relative_standard_error}" != - ]]; then
        bounded_jmh_protocol=true
    fi
fi
frozen_joni_protocol=$(frozen_route_value joni)
if [[ -n "${frozen_joni_protocol}" ]]; then
    IFS=$'\t' read -r JONI_JMH_FORKS _ <<<"${frozen_joni_protocol}"
fi
protocol_qualification_required=false
protocol_representative_count=0
# A unit runs only the representatives attached to its rows for this replica.
unit_holds_representative()
{
    awk -F '\t' -v replica="$1" -v row_id="$2" \
        'NR > 1 && $3 == "representative" && $4 == replica && $2 == row_id {found=1} END {exit !found}' \
        "${UNIT_DIRECTORY}/unit-rows.tsv"
}
if [[ "${bounded_jmh_protocol}" == true && "${PROTOCOL_QUALIFICATION}" == true ]]; then
    protocol_replica_id=${CAMPAIGN_REPLICA_ID}
    if [[ "${protocol_replica_id}" == 4 ]]; then
        protocol_replica_id=1
    fi
    while IFS=$'\t' read -r representative_shard representative_replica representative_route \
            _ representative_row_id _; do
        [[ "${representative_shard}" == "${SHARD_ID}" ]] || continue
        [[ "${representative_replica}" == "${protocol_replica_id}" ]] || continue
        [[ "${representative_route}" == "${ROUTE}" ]] || continue
        unit_holds_representative "${protocol_replica_id}" "${representative_row_id}" || continue
        # The comparison is with a ten-by-one-second reference, so rows frozen
        # at one-second windows have nothing to compare.
        representative_time=$(frozen_route_value representative "${representative_row_id}")
        if [[ -z "${representative_time}" ]]; then
            echo "Protocol representative has no frozen route protocol: ${representative_row_id}" >&2
            exit 1
        fi
        if [[ "${representative_time}" != 1s ]]; then
            protocol_representative_count=$((protocol_representative_count + 1))
        fi
    done < <(tail -n +2 "${PROTOCOL_REPRESENTATIVES}")
    if [[ ${protocol_representative_count} -gt 0 ]]; then
        protocol_qualification_required=true
    fi
fi
if [[ "${PROTOCOL}" == smoke ]]; then
    JMH_PROCESS_COUNT=1
    JMH_PROCESS_WARMUP_ITERATIONS=1
    JMH_PROCESS_MEASUREMENT_ITERATIONS=3
    JMH_PROCESS_TIME=20ms
    JMH_EXECUTION_PROTOCOL=multi-row-process-smoke
else
    # Routes without JMH process rows keep the inert one-second labels.
    JMH_PROCESS_COUNT=5
    JMH_PROCESS_WARMUP_ITERATIONS=10
    JMH_PROCESS_MEASUREMENT_ITERATIONS=10
    JMH_PROCESS_TIME=1s
    JMH_EXECUTION_PROTOCOL=multi-row-process-full
    if [[ -n "${frozen_process_protocol}" ]]; then
        JMH_PROCESS_COUNT=${frozen_process_count}
        JMH_PROCESS_WARMUP_ITERATIONS=${frozen_warmup_iterations}
        JMH_PROCESS_MEASUREMENT_ITERATIONS=${frozen_measurement_iterations}
        JMH_PROCESS_TIME=${frozen_process_time}
    fi
    if [[ "${bounded_jmh_protocol}" == true ]]; then
        JMH_EXECUTION_PROTOCOL=multi-row-process-bounded
    fi
fi
# Smoke keeps the route's smoke windows; qualification runs each selector at its frozen protocol.
python3 "${UNIT_TOOL}" selectors \
    --manifest "${MANIFEST}" \
    --row-protocol "${ROW_PROTOCOL}" \
    --expected "${RESULT_DIR}/expected-rows.tsv" \
    --shard "${SHARD_ID}" \
    --protocol "${PROTOCOL}" \
    --smoke "${JMH_PROCESS_COUNT}" "${JMH_PROCESS_WARMUP_ITERATIONS}" \
    "${JMH_PROCESS_MEASUREMENT_ITERATIONS}" "${JMH_PROCESS_TIME}" \
    --output "${RESULT_DIR}/jmh-selectors.tsv"
maximum_relative_standard_error=-
precision_arguments=()
if [[ "${JMH_EXECUTION_PROTOCOL}" == multi-row-process-bounded ]]; then
    maximum_relative_standard_error=${frozen_maximum_relative_standard_error}
    precision_arguments=(--maximum-relative-standard-error "${maximum_relative_standard_error}")
fi

# The route's plan: what it measures, in which order, at which protocol.
write_route_plan()
{
    printf 'shard_id=%s\n' "${SHARD_ID}"
    printf 'protocol=%s\n' "${PROTOCOL}"
    printf 'route=%s\n' "${ROUTE}"
    printf 'handler=%s\n' "${handler}"
    printf 'systems=%s\n' "${systems}"
    printf 'unit_id=%s\n' "${UNIT_ID}"
    printf 'unit_calibration=%s\n' "${UNIT_CALIBRATION}"
    printf 'calibration_forks=%s\n' "${CALIBRATION_FORKS}"
    printf 'calibration_warmup=%sx%s\n' \
        "${CALIBRATION_WARMUP_ITERATIONS}" "${CALIBRATION_WARMUP_TIME}"
    printf 'calibration_measurement=%sx%s\n' \
        "${CALIBRATION_MEASUREMENT_ITERATIONS}" "${CALIBRATION_MEASUREMENT_TIME}"
    printf 'calibration_prime_invocations=%s\n' "${CALIBRATION_PRIME_INVOCATIONS}"
    printf 'joni_comparator_order=%s\n' "${JONI_COMPARATOR_ORDER}"
    printf 'jmh_execution_protocol=%s\n' "${JMH_EXECUTION_PROTOCOL}"
    printf 'jmh_suite_order=%s\n' "${JMH_SUITE_ORDER}"
    printf 'jmh_process_count=%s\n' "${JMH_PROCESS_COUNT}"
    printf 'jmh_process_warmup_iterations=%s\n' "${JMH_PROCESS_WARMUP_ITERATIONS}"
    printf 'jmh_process_measurement_iterations=%s\n' "${JMH_PROCESS_MEASUREMENT_ITERATIONS}"
    printf 'jmh_iteration_time=%s\n' "${JMH_PROCESS_TIME}"
    printf 'jmh_selector_count=%s\n' "$(($(wc -l < "${RESULT_DIR}/jmh-selectors.tsv") - 1))"
    printf 'maximum_relative_standard_error=%s\n' "${maximum_relative_standard_error}"
    printf 'protocol_qualification_required=%s\n' "${protocol_qualification_required}"
    printf 'protocol_representative_count=%s\n' "${protocol_representative_count}"
}

if [[ "${PLAN_ONLY}" == true ]]; then
    {
        write_route_plan
        printf 'plan_only=true\n'
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
    write_route_plan
    printf 'trino_output_contract=joni-slice-output-v1\n'
    printf 'cpu_list=%s\n' "${CPU_LIST}"
    printf 'heap_size=%s\n' "${HEAP_SIZE}"
    printf 'shared_work_dir=%s\n' "${SHARED_WORK_DIR}"
    printf 'jmh_row_order=jmh-deterministic-no-cli-randomization\n'
    printf 'jmh_host_compiler_hints=jmh-generated-and-installed\n'
} > "${RESULT_DIR}/run-metadata.txt"

# Where the Trino harness this route used came from: prebuilt:<archive SHA-256>, local, or not-used.
trino_harness_build=not-used

prebuilt_engine()
{
    # The host installs an archive, and writes its marker, only after verifying it against the manifest.
    local marker="${SHARED_WORK_DIR}/engine-build/$1.sha256"
    [[ -f "${marker}" ]] && printf 'prebuilt:%s\n' "$(cat "${marker}")"
}

record_engine_builds()
{
    printf 'trino_harness_build=%s\n' "${trino_harness_build}" >> "${RESULT_DIR}/run-metadata.txt"
}

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
# A unit's calibrations compare across its routes, and later with the next
# unit's, so every unit calibrates in the native-access JVM.
calibration_jvm_arguments=(--enable-native-access=ALL-UNNAMED --illegal-native-access=deny --add-modules=jdk.incubator.vector)
calibration_launcher_jvm_arguments=("${calibration_jvm_arguments[@]}" -Xms64m -Xmx256m)
calibration_fork_arguments="${calibration_jvm_arguments[*]} -Xms${HEAP_SIZE} -Xmx${HEAP_SIZE} -XX:+AlwaysPreTouch"

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

# Any failure of the correctness stage is final: a semantic test, the DFA probe,
# a differential or lifecycle verifier, its count of verified cases, or the
# pinned Trino comparator's verification. Its own status,
# unit_session.SEMANTIC_FAILURE_STATUS, tells a unit controller to reject the
# unit rather than retry it, even where the check could not run at all.
SEMANTIC_FAILURE_STATUS=44
semantic_failure()
{
    echo "$1" >&2
    exit "${SEMANTIC_FAILURE_STATUS}"
}

semantic_log="${RESULT_DIR}/logs/semantic-tests.log"

# Runs one semantic check, appending its output to the semantic log.
run_semantic_verifier()
{
    local description=$1
    shift
    "$@" 2>&1 | tee -a "${semantic_log}" || semantic_failure "${description} failed"
}
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
: > "${semantic_log}"
run_semantic_verifier "DFA absolute-pointer probe" \
    taskset --cpu-list "${CPU_LIST}" \
    java "${route_jvm_arguments[@]}" \
    -Dio.airlift.regulator.dfa.native-reader-probe="${expected_native_access}" \
    -cp "${CLASSPATH}" \
    io.airlift.regulator.DfaAbsolutePointerProbe "${expected_native_access}"
# Units of one shard on one host share a route's Surefire semantic tests. The
# receipt is keyed by the host epoch and candidate identity and verified by
# hash; each unit still runs the native probe above and its handler's own
# preparation and verifiers below.
semantic_receipt_directory=
semantic_receipt=measured
unit_host_epoch=${RE2_CAMPAIGN_HOST_EPOCH:-}
if [[ -n "${unit_host_epoch}" ]]; then
    semantic_receipt_key=$(
        printf '%s\n' \
            "host_epoch=${unit_host_epoch}" \
            "shard_id=${SHARD_ID}" \
            "route=${ROUTE}" \
            "handler=${handler}" \
            "semantic_tests=${semantic_tests}" \
            "candidate_commit=${candidate_commit}" \
            "engine_tree=${engine_tree}" \
            "engine_content_sha256=${source_hash_before}" \
            "manifest_sha256=${manifest_sha256}" \
            "regulator_snapshot_sha256=${REGULATOR_SNAPSHOT_SHA256:-}" \
            "release_version=${REGULATOR_RELEASE_VERSION:-}" |
            sha256sum |
            awk '{print $1}')
    semantic_receipt_directory="${SHARED_WORK_DIR}/semantic-receipts/${semantic_receipt_key}"
fi

semantic_receipt_is_valid()
{
    local directory=$1
    [[ -s "${directory}/receipt.sha256" && -s "${directory}/surefire.log" ]] || return 1
    compgen -G "${directory}/reports/TEST-*.xml" >/dev/null || return 1
    # The receipt must list exactly the files it holds, each with its hash.
    [[ "$(cd "${directory}" && find . -type f ! -name receipt.sha256 | sed 's|^[.]/||' | sort)" == \
        "$(awk '{print $2}' "${directory}/receipt.sha256" | sort)" ]] || return 1
    (cd "${directory}" && sha256sum --check --quiet --strict receipt.sha256) >/dev/null 2>&1
}

store_semantic_receipt()
{
    local directory=$1
    local surefire_log=$2
    local staging="${directory}.staging"
    rm -rf "${staging}" "${directory}"
    mkdir -p "${staging}/reports"
    cp "${surefire_log}" "${staging}/surefire.log"
    cp "${semantic_report_dir}"/TEST-*.xml "${staging}/reports/"
    (cd "${staging}" && find . -type f | sed 's|^[.]/||' | sort | xargs sha256sum) > "${RESULT_DIR}/semantic-receipt.sha256"
    cp "${RESULT_DIR}/semantic-receipt.sha256" "${staging}/receipt.sha256"
    mv "${staging}" "${directory}"
}

if [[ -n "${semantic_receipt_directory}" ]] && semantic_receipt_is_valid "${semantic_receipt_directory}"; then
    cat "${semantic_receipt_directory}/surefire.log" >> "${semantic_log}"
    cp "${semantic_receipt_directory}"/reports/TEST-*.xml "${semantic_report_dir}/"
    cp "${semantic_receipt_directory}/receipt.sha256" "${RESULT_DIR}/semantic-receipt.sha256"
    semantic_receipt=cached
else
    surefire_logs=("${semantic_log}")
    if [[ -n "${semantic_receipt_directory}" ]]; then
        surefire_logs+=("${RESULT_DIR}/logs/semantic-surefire.log")
    fi
    (
        cd "${ROOT}" &&
            JDK_JAVA_OPTIONS="${semantic_java_options}" \
                ./mvnw "${MAVEN_ARGS[@]}" -q \
                -Dtest="${semantic_tests}" test
    ) 2>&1 | tee -a "${surefire_logs[@]}" || semantic_failure "Semantic tests failed"
    compgen -G "${default_semantic_report_dir}/TEST-*.xml" >/dev/null ||
        semantic_failure "Semantic tests produced no Surefire XML reports"
    cp "${default_semantic_report_dir}"/TEST-*.xml "${semantic_report_dir}/"
    if [[ -n "${semantic_receipt_directory}" ]]; then
        store_semantic_receipt "${semantic_receipt_directory}" "${RESULT_DIR}/logs/semantic-surefire.log"
    fi
fi
if [[ -n "${semantic_receipt_directory}" ]]; then
    printf 'semantic_receipt=%s\nsemantic_receipt_sha256=%s\n' "${semantic_receipt}" \
        "$(sha256sum "${RESULT_DIR}/semantic-receipt.sha256" | awk '{print $1}')" >> "${RESULT_DIR}/run-metadata.txt"
fi

prepare_pinned_trino()
{
    PINNED_TRINO_WORK_DIR="${SHARED_WORK_DIR}/pinned-trino"
    mkdir -p "${PINNED_TRINO_WORK_DIR}"
    # First check only the harness's identity and provenance; a harness that
    # fails, before any semantic check runs against it, is replaced by a local
    # build. Once its semantic verifiers have run, their verdict below is final.
    if ! TRINO_COMPARATOR_IDENTITY_ONLY=true \
            TRINO_COMPARATOR_NATIVE_ACCESS="${expected_native_access}" \
            TRINO_COMPARATOR_WORK_DIR="${PINNED_TRINO_WORK_DIR}" \
            "${ROOT}/tools/re2-benchmark/trino-joni/verify.sh" \
            >> "${semantic_log}" 2>&1; then
        rm -f "${SHARED_WORK_DIR}/engine-build/trino.sha256"
        TRINO_COMPARATOR_IDENTITY_ONLY=true TRINO_COMPARATOR_WORK_DIR="${PINNED_TRINO_WORK_DIR}" \
            "${ROOT}/tools/re2-benchmark/trino-joni/prepare.sh" 2>&1 | tee -a "${semantic_log}"
    fi
    trino_harness_build=$(prebuilt_engine trino || echo local)
    run_semantic_verifier "Pinned Trino comparator verifier" \
        env TRINO_COMPARATOR_IDENTITY_ONLY=false TRINO_COMPARATOR_NATIVE_ACCESS="${expected_native_access}" \
        TRINO_COMPARATOR_WORK_DIR="${PINNED_TRINO_WORK_DIR}" \
        "${ROOT}/tools/re2-benchmark/trino-joni/verify.sh"
    PINNED_TRINO_CLASSPATH=$(cat "${PINNED_TRINO_WORK_DIR}/classpath.txt")
    if tr ':' '\n' <<<"${PINNED_TRINO_CLASSPATH}" | grep -Eq '/(com/google/re2j/re2j|io/trino/trino-re2j)/'; then
        echo "Pinned Trino classpath contains a historical RE2J artifact" >&2
        exit 1
    fi
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
    run_semantic_verifier "Trino LIKE lifecycle verifier" \
        java "${route_jvm_arguments[@]}" -cp "${SPECIAL_CLASSPATH}" \
        io.airlift.regulator.benchmark.BenchmarkTrinoLike
    grep -Fx 'Verified 60 Trino LIKE lifecycle comparisons' "${semantic_log}" >/dev/null ||
        semantic_failure "Trino LIKE lifecycle verifier did not verify every comparison"
    run_semantic_verifier "Trino LIKE differential verifier" \
        java "${route_jvm_arguments[@]}" -cp "${SPECIAL_CLASSPATH}" \
        TrinoLikeDifferentialVerifier
    grep -Fx 'Verified 530255 Trino LIKE comparisons' "${semantic_log}" >/dev/null &&
        grep -Fx 'Verified 810155 escaped Trino LIKE comparisons' "${semantic_log}" >/dev/null &&
        grep -Fx 'Verified 9330 encoded Trino LIKE comparisons' "${semantic_log}" >/dev/null ||
        semantic_failure "Trino LIKE differential verifier did not verify every comparison"
    if [[ "${SHARD_ID}" == trino-like-specialized ]]; then
        javac \
            -cp "${PINNED_TRINO_CLASSPATH}" \
            -processorpath "${PINNED_TRINO_CLASSPATH}" \
            -processor org.openjdk.jmh.generators.BenchmarkProcessor \
            -d "${classes}" \
            -s "${generated}" \
            "${ROOT}/tools/re2-benchmark/trino-like/BenchmarkTrinoLikeTpch.java"
        run_semantic_verifier "TPC-H LIKE verifier" \
            java "${route_jvm_arguments[@]}" -cp "${SPECIAL_CLASSPATH}" \
            io.airlift.regulator.BenchmarkTrinoLikeTpch "${ROOT}"
        grep -Fx 'Verified 4 TPC-H LIKE columns and 7 workloads in three engines' "${semantic_log}" >/dev/null &&
            grep -Fx 'Verified 7 TPC-H LIKE workloads with generated bytecode' "${semantic_log}" >/dev/null ||
            semantic_failure "TPC-H LIKE verifier did not verify every workload"
    fi
}

case "${handler}" in
    trino-operations)
        if [[ "${ROUTE}" == native-access ]]; then
            prepare_pinned_trino
        fi
        ;;
    trino-like) prepare_trino_like ;;
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
python3 "${SCRIPT_DIR}/semantic_evidence.py" "${semantic_evidence_arguments[@]}"
semantic_result_digest=$(sha256sum "${RESULT_DIR}/semantic-evidence.tsv" | awk '{print $1}')
{
    printf 'route\ttests\toutcome\tsemantic_result_digest\n'
    printf '%s\t%s\taccepted\t%s\n' "${ROUTE}" "${semantic_tests}" "${semantic_result_digest}"
} > "${RESULT_DIR}/semantic-gate.tsv"

run_calibration_jmh_with_classpath()
{
    local classpath=$1
    local filter=$2
    local output=$3
    shift 3
    taskset --cpu-list "${CPU_LIST}" \
        java "${calibration_launcher_jvm_arguments[@]}" \
        -cp "${classpath}" \
        org.openjdk.jmh.Main "${filter}" \
        -f "${CALIBRATION_FORKS}" \
        -wi "${CALIBRATION_WARMUP_ITERATIONS}" \
        -i "${CALIBRATION_MEASUREMENT_ITERATIONS}" \
        -w "${CALIBRATION_WARMUP_TIME}" \
        -r "${CALIBRATION_MEASUREMENT_TIME}" \
        -foe true \
        -prof gc \
        -jvmArgs "${calibration_fork_arguments}" \
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
        unit_holds_representative "${protocol_replica_id}" "${manifest_row_id}" || continue
        local representative_time
        representative_time=$(frozen_route_value representative "${manifest_row_id}")
        [[ "${representative_time}" != 1s ]] || continue
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

# The result files of one unit handler call, one per selector process.
JMH_INPUT_FILES=()
set_jmh_input_files()
{
    local output_base=${1%.json}
    local selector kind processes process_index
    JMH_INPUT_FILES=()
    while IFS=$'\t' read -r selector _ _ kind processes _; do
        if [[ "${kind}" == processes ]]; then
            for ((process_index = 1; process_index <= processes; process_index++)); do
                JMH_INPUT_FILES+=("${output_base}-selector-${selector}-process-${process_index}.json")
            done
        else
            JMH_INPUT_FILES+=("${output_base}-selector-${selector}.json")
        fi
    done < <(tail -n +2 "${output_base}-selectors.tsv")
}

# Runs a handler call's selectors or, from run_unit_jmh_selectors, one process selector.
run_jmh_with_classpath()
{
    local classpath=$1
    local filter=$2
    local output=$3
    shift 3
    if [[ -z "${JMH_UNIT_SELECTOR:-}" ]]; then
        run_unit_jmh_selectors "${classpath}" "${filter}" "${output}" "$@"
        return
    fi
    local output_base=${output%.json}
    prepare_jmh_host_compiler_arguments "${classpath}" "$(basename "${output_base}")"
    local process_index
    for ((process_index = 1; process_index <= JMH_PROCESS_COUNT; process_index++)); do
        local process_output="${output_base}-process-${process_index}.json"
        local profiler_arguments=()
        if [[ ${process_index} -eq 1 ]]; then
            profiler_arguments=(-prof gc)
        fi
        taskset --cpu-list "${CPU_LIST}" \
            java "${jmh_process_jvm_arguments[@]}" \
            "${jmh_host_compiler_arguments[@]}" \
            -cp "${classpath}" \
            org.openjdk.jmh.Main "${filter}" \
            -f 0 \
            -wi "${JMH_PROCESS_WARMUP_ITERATIONS}" \
            -i "${JMH_PROCESS_MEASUREMENT_ITERATIONS}" \
            -w "${JMH_PROCESS_WARMUP_TIME}" \
            -r "${JMH_PROCESS_TIME}" \
            -foe true \
            "${profiler_arguments[@]}" \
            -rf json \
            -rff "${process_output}" \
            "$@"
    done
}

run_jmh()
{
    run_jmh_with_classpath "${CLASSPATH}" "$@"
}

# Runs a handler call's selectors or, from run_unit_jmh_selectors, one forked selector.
run_joni_jmh_with_classpath()
{
    local classpath=$1
    local filter=$2
    local output=$3
    shift 3
    if [[ -z "${JMH_UNIT_SELECTOR:-}" ]]; then
        run_unit_jmh_selectors "${classpath}" "${filter}" "${output}" "$@"
        return
    fi
    taskset --cpu-list "${CPU_LIST}" \
        java "${jmh_launcher_jvm_arguments[@]}" \
        -cp "${classpath}" \
        org.openjdk.jmh.Main "${filter}" \
        -f "${JONI_JMH_FORKS}" \
        -wi "${JMH_PROCESS_WARMUP_ITERATIONS}" \
        -i "${JMH_PROCESS_MEASUREMENT_ITERATIONS}" \
        -w "${JONI_JMH_TIME}" \
        -r "${JONI_JMH_TIME}" \
        -foe true \
        -prof gc \
        -jvmArgs "${jmh_fork_arguments}" \
        -rf json \
        -rff "${output}" \
        "$@"
}

# A unit handler call runs its selected selectors one after another: process
# selectors through the process runner, forked selectors through the forked
# runner, each at its own protocol, with explicit -p values or, for a whole
# method JMH cannot select by value, the handler's own arguments. The handler's
# -p values narrow the selectors it runs; its other arguments reach every one.
run_unit_jmh_selectors()
{
    local classpath=$1
    local filter=$2
    local output=$3
    shift 3
    local output_base=${output%.json}
    local selection_arguments=()
    local handler_arguments=()
    local arguments=("$@")
    local index
    for ((index = 0; index < ${#arguments[@]}; index++)); do
        if [[ "${arguments[index]}" == -p ]]; then
            selection_arguments+=(--parameter "${arguments[index + 1]}")
            index=$((index + 1))
        else
            handler_arguments+=("${arguments[index]}")
        fi
    done
    python3 "${UNIT_TOOL}" select \
        --selectors "${RESULT_DIR}/jmh-selectors.tsv" \
        --filter "${filter}" \
        "${selection_arguments[@]}" \
        --output-base "${output_base}"
    local selector kind processes warmup_iterations warmup_time measurement_iterations measurement_time
    local selector_filter parameters
    while IFS=$'\t' read -r -u 3 selector _ _ kind processes warmup_iterations warmup_time \
            measurement_iterations measurement_time _ parameters _ selector_filter; do
        local parameter_arguments=("$@")
        if [[ "${parameters}" != route ]]; then
            parameter_arguments=()
            local parameter
            while IFS= read -r parameter; do
                parameter_arguments+=(-p "${parameter}")
            done < "${output_base}-selector-${selector}-parameters.txt"
            parameter_arguments+=("${handler_arguments[@]}")
        fi
        if [[ "${kind}" == processes ]]; then
            JMH_UNIT_SELECTOR=true \
                JMH_PROCESS_COUNT=${processes} \
                JMH_PROCESS_WARMUP_ITERATIONS=${warmup_iterations} \
                JMH_PROCESS_MEASUREMENT_ITERATIONS=${measurement_iterations} \
                JMH_PROCESS_TIME=${measurement_time} \
                JMH_PROCESS_WARMUP_TIME=${warmup_time} \
                run_jmh_with_classpath "${classpath}" "${selector_filter}" \
                "${output_base}-selector-${selector}.json" "${parameter_arguments[@]}"
        else
            JMH_UNIT_SELECTOR=true \
                JONI_JMH_FORKS=${processes} \
                JMH_PROCESS_WARMUP_ITERATIONS=${warmup_iterations} \
                JMH_PROCESS_MEASUREMENT_ITERATIONS=${measurement_iterations} \
                JONI_JMH_TIME=${measurement_time} \
                run_joni_jmh_with_classpath "${classpath}" "${selector_filter}" \
                "${output_base}-selector-${selector}.json" "${parameter_arguments[@]}"
        fi
    done 3< <(tail -n +2 "${output_base}-selectors.tsv")
}

secondary_metric_arguments=()
if [[ ${JMH_PROCESS_COUNT} -gt 1 ]]; then
    secondary_metric_arguments=(--allow-missing-secondary-metrics)
fi
# Each selector of a unit call writes its own results.
mixed_process_arguments=(
    --allow-mixed-process-sets
    --expected-process-groups "${JMH_PROCESS_COUNT}")
joni_process_arguments=(
    --allow-mixed-process-sets
    --expected-process-groups "${JONI_JMH_FORKS}")

calibration_filter='^io\.airlift\.regulator\.BenchmarkExpressionPlans\.findLiteral$'
calibration_parameters=(-p workload=KIB_LATE)
run_protocol_qualification
# A unit calibrates before its first route and after its last.
if [[ "${UNIT_CALIBRATION}" == before || "${UNIT_CALIBRATION}" == both ]]; then
    if [[ ${CALIBRATION_PRIME_INVOCATIONS} -gt 0 ]]; then
        run_calibration_jmh "${calibration_filter}" "${RESULT_DIR}/raw/calibration-prime.json" \
            "${calibration_parameters[@]}" |
            tee "${RESULT_DIR}/logs/calibration-prime.log"
    fi
    run_calibration_jmh "${calibration_filter}" "${calibration_output_directory}/calibration-before.json" \
        "${calibration_parameters[@]}" |
        tee "${RESULT_DIR}/logs/calibration-before.log"
fi

normalized_files=()

run_jmh_per_workload()
{
    # Runs each expected row of one system in its own JMH invocation, so each benchmark call site
    # sees one pattern. The arguments are the row order (forward or reverse); the launch mode,
    # in-process for Regulator's host JVM processes or forked for Joni's proper forks; the
    # classpath; the result prefix; the system; and jmh_benchmark, the JMH method to filter on or
    # - for the manifest benchmark. Any remaining arguments go to every invocation. Each
    # invocation's raw results and log are named for the system, the manifest benchmark and its
    # parameters. Afterwards JMH_INPUT_FILES holds every invocation's result files.
    local order=$1
    local mode=$2
    local classpath=$3
    local prefix=$4
    local system=$5
    local jmh_benchmark=$6
    shift 6
    local launcher
    case "${mode}" in
        in-process) launcher=run_jmh_with_classpath ;;
        forked) launcher=run_joni_jmh_with_classpath ;;
        *) echo "Unknown per-workload launch mode: ${mode}" >&2; exit 1 ;;
    esac
    # Each parameter value becomes part of a file name and of one JMH -p value.
    local parameters_pattern='^[A-Za-z0-9_]+=[A-Za-z0-9_.-]+(;[A-Za-z0-9_]+=[A-Za-z0-9_.-]+)*$'
    local rows=()
    local row
    while IFS= read -r row; do
        rows+=("${row}")
    done < <(awk -F '\t' -v target_system="${system}" -v order="${order}" '
        NR > 1 && $4 == target_system {rows[++count] = $5 "\t" $6}
        END {
            for (row_number = 1; row_number <= count; row_number++) {
                print rows[order == "reverse" ? count + 1 - row_number : row_number]
            }
        }' "${RESULT_DIR}/expected-rows.tsv")
    if [[ ${#rows[@]} -eq 0 ]]; then
        echo "No expected ${prefix} rows for ${system}" >&2
        exit 1
    fi
    local workload_input_files=()
    local benchmark
    local parameters
    for row in "${rows[@]}"; do
        IFS=$'\t' read -r benchmark parameters <<<"${row}"
        if [[ ! "${parameters}" =~ ${parameters_pattern} ]]; then
            echo "Cannot name a ${prefix} result file after ${system} parameters: ${parameters}" >&2
            exit 1
        fi
        local label="${prefix}-${system}-${benchmark##*.}-${parameters//[=;]/-}"
        local output="${RESULT_DIR}/raw/${label}.json"
        local filter_benchmark=${jmh_benchmark}
        if [[ "${filter_benchmark}" == - ]]; then
            filter_benchmark=${benchmark}
        fi
        local parameter_arguments=()
        local parameter
        while IFS= read -r parameter; do
            parameter_arguments+=(-p "${parameter}")
        done < <(tr ';' '\n' <<<"${parameters}")
        "${launcher}" "${classpath}" "^${filter_benchmark//./\\.}$" "${output}" "${parameter_arguments[@]}" "$@" |
            tee "${RESULT_DIR}/logs/${label}.log"
        set_jmh_input_files "${output}"
        workload_input_files+=("${JMH_INPUT_FILES[@]}")
    done
    JMH_INPUT_FILES=("${workload_input_files[@]}")
}

run_trino_operations_candidate()
{
    local system=$1
    local normalized="${RESULT_DIR}/normalized/trino-operations-${system}.tsv"
    if [[ "${jmh_invocation_per_workload}" == true ]]; then
        run_jmh_per_workload "${JMH_SUITE_ORDER}" in-process "${CLASSPATH}" trino-operations "${system}" -
    else
        local filter
        local output="${RESULT_DIR}/raw/trino-operations-${system}.json"
        filter=$(python3 "${RESULT_TOOL}" jmh-filter \
            --manifest "${SELECTION_MANIFEST}" \
            --shard "${SHARD_ID}" \
            --system "${system}")
        run_jmh "${filter}" "${output}" |
            tee "${RESULT_DIR}/logs/trino-operations-${system}.log"
        set_jmh_input_files "${output}"
    fi
    python3 "${RESULT_TOOL}" normalize-jmh \
        --manifest "${SELECTION_MANIFEST}" \
        --shard "${SHARD_ID}" \
        --system "${system}" \
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
    local normalized="${RESULT_DIR}/normalized/trino-operations-joni.tsv"
    if [[ "${jmh_invocation_per_workload}" == true ]]; then
        # Joni keeps its forward workload order; only the Regulator variants reverse.
        run_jmh_per_workload forward forked "${PINNED_TRINO_CLASSPATH}" trino-operations joni -
    else
        local filter
        local output="${RESULT_DIR}/raw/trino-operations-joni.json"
        filter=$(python3 "${RESULT_TOOL}" jmh-filter \
            --manifest "${SELECTION_MANIFEST}" \
            --shard "${SHARD_ID}" \
            --system joni)
        run_joni_jmh_with_classpath "${PINNED_TRINO_CLASSPATH}" "${filter}" "${output}" |
            tee "${RESULT_DIR}/logs/trino-operations-joni.log"
        set_jmh_input_files "${output}"
    fi
    python3 "${RESULT_TOOL}" normalize-jmh \
        --manifest "${SELECTION_MANIFEST}" \
        --shard "${SHARD_ID}" \
        --system joni \
        --input "${JMH_INPUT_FILES[@]}" \
        "${secondary_metric_arguments[@]}" \
        "${joni_process_arguments[@]}" \
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

run_trino_operations_candidates()
{
    # Each Regulator variant runs in its own JVMs, so one variant's classes cannot change how
    # C2 compiles another's. Even replicas run the variants in reverse order.
    local candidates=()
    local system
    while IFS= read -r system; do
        if [[ "${system}" == joni ]]; then
            continue
        elif [[ "${JMH_SUITE_ORDER}" == reverse ]]; then
            candidates=("${system}" "${candidates[@]}")
        else
            candidates+=("${system}")
        fi
    done < <(tr ',' '\n' <<<"${systems}")
    for system in "${candidates[@]}"; do
        run_trino_operations_candidate "${system}"
    done
}

run_trino_operations_shard()
{
    if [[ "${ROUTE}" == native-access ]]; then
        run_ordered_joni_comparison run_trino_operations_candidates run_trino_operations_joni
    else
        run_trino_operations_candidates
    fi
}

run_trino_like_system()
{
    local system=$1
    local method=$2
    local operation=${3:-matches}
    local benchmark_class=io.airlift.regulator.benchmark.BenchmarkTrinoLike
    local suite=trino-like
    if [[ "${SHARD_ID}" == trino-like-specialized ]]; then
        benchmark_class=io.airlift.regulator.BenchmarkTrinoLikeTpch
        suite=trino-like-specialized
    fi
    local normalized="${RESULT_DIR}/normalized/trino-like-${system}.tsv"
    if [[ "${jmh_invocation_per_workload}" == true ]]; then
        # The benchmark declares microseconds; the report keeps nanoseconds.
        run_jmh_per_workload "${JMH_SUITE_ORDER}" in-process "${SPECIAL_CLASSPATH}" trino-like "${system}" \
            "${benchmark_class}.${method}" -tu ns
    else
        local scenario_arguments=()
        if [[ "${SHARD_ID}" == like-dfa-single-use ]]; then
            scenario_arguments=(-p scenario=ANY_ASCII,ANY_MULTIBYTE)
        fi
        local filter="^${benchmark_class//./\\.}\\.${method}$"
        local output="${RESULT_DIR}/raw/trino-like-${system}.json"
        run_jmh_with_classpath "${SPECIAL_CLASSPATH}" "${filter}" "${output}" "${scenario_arguments[@]}" |
            tee "${RESULT_DIR}/logs/trino-like-${system}.log"
        set_jmh_input_files "${output}"
    fi
    python3 "${RESULT_TOOL}" normalize-mapped-jmh \
        --manifest "${SELECTION_MANIFEST}" \
        --shard "${SHARD_ID}" \
        --suite "${suite}" \
        --system "${system}" \
        --input "${JMH_INPUT_FILES[@]}" \
        "${secondary_metric_arguments[@]}" \
        "${mixed_process_arguments[@]}" \
        --semantic-receipt "${RESULT_DIR}/semantic-gate.tsv" \
        "${precision_arguments[@]}" \
        --benchmark-class "${benchmark_class}" \
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
        trino-like-specialized)
            # Even replicas reverse the whole sequence, Trino SQL included.
            if [[ "${JMH_SUITE_ORDER}" == reverse ]]; then
                run_trino_like_system trino-sql trinoSql
                run_trino_like_system regulator-generated candidateGenerated
                run_trino_like_system regulator candidate
            else
                run_trino_like_system regulator candidate
                run_trino_like_system regulator-generated candidateGenerated
                run_trino_like_system trino-sql trinoSql
            fi
            ;;
        *)
            run_trino_like_system regulator candidate
            run_trino_like_system trino-optimized trinoOptimized
            run_trino_like_system trino-sql trinoSql
            ;;
    esac
}

case "${handler}" in
    trino-operations) run_trino_operations_shard ;;
    trino-like) run_trino_like_shard ;;
    *)
        echo "Internal error: unsupported handler ${handler}" >&2
        exit 1
        ;;
esac

if [[ "${UNIT_CALIBRATION}" == after || "${UNIT_CALIBRATION}" == both ]]; then
    run_calibration_jmh "${calibration_filter}" "${calibration_output_directory}/calibration-after.json" \
        "${calibration_parameters[@]}" |
        tee "${RESULT_DIR}/logs/calibration-after.log"
fi

if [[ ${#normalized_files[@]} -eq 0 ]]; then
    echo "Shard produced no normalized result files" >&2
    exit 1
fi
python3 "${RESULT_TOOL}" combine \
    --expected "${RESULT_DIR}/expected-rows.tsv" \
    --output "${RESULT_DIR}/observed-rows.tsv" \
    "${normalized_files[@]}"

source_hash_after=$(source_hash)
if [[ "${source_hash_after}" != "${source_hash_before}" ]]; then
    echo "src/main changed during baseline shard execution" >&2
    exit 1
fi
printf 'engine_content_sha256_after=%s\n' "${source_hash_after}" >> "${RESULT_DIR}/run-metadata.txt"
printf 'observed_row_count=%s\n' "$(($(wc -l < "${RESULT_DIR}/observed-rows.tsv") - 1))" >> "${RESULT_DIR}/run-metadata.txt"
record_engine_builds

(
    cd "${RESULT_DIR}"
    find raw logs -type f -print0 |
        sort -z |
        xargs -0 sha256sum
) > "${RESULT_DIR}/raw-artifacts.sha256"

# run-unit.sh resolves and checks the host identity and exports it to each route.
canonical_systems=$(tr ',' '\n' <<<"${systems}" | sort | paste -sd, -)
session_file="${RESULT_DIR}/route-session.tsv"
{
    printf 'schema_version\tcampaign_id\tplatform\tshard_id\treplica_id\tinstance_id\thost_epoch\tarchitecture\tinstance_type\tavailability_zone\tsystems\n'
    printf '2\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "${RE2_CAMPAIGN_ID}" "${RE2_CAMPAIGN_PLATFORM}" "${SHARD_ID}" "${RE2_CAMPAIGN_REPLICA_ID}" \
        "${BASELINE_INSTANCE_ID}" "${RE2_CAMPAIGN_HOST_EPOCH}" "${RE2_ENGINEERING_ARCHITECTURE}" \
        "${BASELINE_INSTANCE_TYPE}" "${BASELINE_AVAILABILITY_ZONE}" "${canonical_systems}"
} > "${session_file}"
echo "Completed deferred route ${SHARD_ID} ${PROTOCOL} ${ROUTE}"
