#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1

# EC2 user data invokes this script without guaranteeing HOME. Maven's wrapper
# requires the invoking account's home for distribution and repository caches.
if [[ -z ${HOME:-} ]]; then
    HOME=$(awk -F: -v user_id="$(id -u)" '$3 == user_id {print $6; exit}' /etc/passwd)
    HOME=${HOME:-/tmp/regulator-benchmark-home}
    export HOME
    mkdir -p "${HOME}"
fi

if [[ $# -ne 3 ]]; then
    echo "Usage: $0 <regulator-directory> <trino-directory-or-empty> <result-directory>" >&2
    exit 1
fi

BENCHMARK_MODE=${RE2_BENCHMARK_MODE:-}
if [[ "${BENCHMARK_MODE}" != baseline-shard && "${BENCHMARK_MODE}" != language-batch ]]; then
    echo "RE2_BENCHMARK_MODE must be baseline-shard or language-batch" >&2
    exit 1
fi
CAMPAIGN_PROVENANCE=${CAMPAIGN_PROVENANCE:-}
if [[ "${CAMPAIGN_PROVENANCE}" != qualification ]]; then
    echo "CAMPAIGN_PROVENANCE must be qualification" >&2
    exit 1
fi
BASELINE_PROTOCOL=${BASELINE_PROTOCOL:-qualification}
case "${BASELINE_PROTOCOL}" in
    smoke | qualification) ;;
    *) echo "BASELINE_PROTOCOL must be smoke or qualification" >&2; exit 1 ;;
esac
BASELINE_SELECTED_ROUTE=${BASELINE_SELECTED_ROUTE:-}
BASELINE_JONI_JMH_TIME=${BASELINE_JONI_JMH_TIME:-}
NATIVE_COMPILER_PACKAGE=${NATIVE_COMPILER_PACKAGE:?NATIVE_COMPILER_PACKAGE is required}
CMAKE_PACKAGE=${CMAKE_PACKAGE:?CMAKE_PACKAGE is required}
GLIBC_PACKAGE=${GLIBC_PACKAGE:?GLIBC_PACKAGE is required}
CARGO_PACKAGE=${CARGO_PACKAGE:?CARGO_PACKAGE is required}
RUST_PACKAGE=${RUST_PACKAGE:?RUST_PACKAGE is required}
TIME_PACKAGE=${TIME_PACKAGE:?TIME_PACKAGE is required}
REGULATOR_DIR=$(cd "$1" && pwd)
TRINO_DIR=
case "${RE2_CAMPAIGN_SHARD_ID:-}" in
    like-compile | like-single-use | like-dfa-single-use | trino-operations | trino-final-line | trino-like | lifecycle | lifecycle-shared-cold | rebar-*)
        if [[ -z "$2" || ! -d "$2" ]]; then
            echo "Baseline shard ${RE2_CAMPAIGN_SHARD_ID} requires a Trino checkout" >&2
            exit 1
        fi
        TRINO_DIR=$(cd "$2" && pwd)
        ;;
    *)
        if [[ -n "$2" ]]; then
            echo "Baseline shard ${RE2_CAMPAIGN_SHARD_ID:-unset} does not use a Trino checkout" >&2
            exit 1
        fi
        ;;
esac
RESULT_DIR=$(mkdir -p "$3" && cd "$3" && pwd)
PHYSICAL_CPU_LIST=$(lscpu -p=CPU,CORE,SOCKET | awk -F, '!/^#/ {key=$2 ":" $3; if (!seen[key]++) {cpus=(cpus == "" ? $1 : cpus "," $1)}} END {print cpus}')
PHYSICAL_CORE_COUNT=$(awk -F, '{print NF}' <<<"${PHYSICAL_CPU_LIST}")
LOGICAL_CPU_COUNT=$(nproc --all)
if [[ -n "${BENCHMARK_EXPECTED_VCPUS:-}" && "${LOGICAL_CPU_COUNT}" != "${BENCHMARK_EXPECTED_VCPUS}" ]]; then
    echo "Logical CPU count differs from the frozen worker topology" >&2
    exit 1
fi
RE2_CAMPAIGN_ID=${RE2_CAMPAIGN_ID:?RE2_CAMPAIGN_ID is required}
RE2_CAMPAIGN_PLATFORM=${RE2_CAMPAIGN_PLATFORM:?RE2_CAMPAIGN_PLATFORM is required}
RE2_CAMPAIGN_SHARD_ID=${RE2_CAMPAIGN_SHARD_ID:?RE2_CAMPAIGN_SHARD_ID is required}
RE2_CAMPAIGN_REPLICA_ID=${RE2_CAMPAIGN_REPLICA_ID:?RE2_CAMPAIGN_REPLICA_ID is required}
RE2_CAMPAIGN_HOST_EPOCH=${RE2_CAMPAIGN_HOST_EPOCH:?RE2_CAMPAIGN_HOST_EPOCH is required}

exec > >(tee "${RESULT_DIR}/run.log") 2>&1

install_packages()
{
    sudo dnf install -y "${CMAKE_PACKAGE}" "${NATIVE_COMPILER_PACKAGE}" "${GLIBC_PACKAGE}" \
        "${TIME_PACKAGE}" git jq make ninja-build perf tar gzip
    if [[ "${RE2_CAMPAIGN_SHARD_ID}" == rebar-* ]]; then
        sudo dnf install -y abseil-cpp-devel "${CARGO_PACKAGE}" "${RUST_PACKAGE}"
    fi
}

install_java()
{
    if [[ -n ${BENCHMARK_JAVA_ARCHIVE_URL:-} ]]; then
        curl --fail --location --retry 5 \
            "${BENCHMARK_JAVA_ARCHIVE_URL}" \
            --output /tmp/benchmark-jdk.tar.gz
        printf '%s  %s\n' "${BENCHMARK_JAVA_ARCHIVE_SHA256}" /tmp/benchmark-jdk.tar.gz | sha256sum --check -
        INSTALLED_JAVA_ARCHIVE_SHA256=$(sha256sum /tmp/benchmark-jdk.tar.gz | awk '{print $1}')
        sudo mkdir -p /opt/benchmark-jdk
        sudo tar -xzf /tmp/benchmark-jdk.tar.gz -C /opt/benchmark-jdk --strip-components=1
        export JAVA_HOME=/opt/benchmark-jdk
        export PATH="${JAVA_HOME}/bin:${PATH}"
        return
    fi

    echo "Qualification requires a controller-provided JDK archive and SHA-256" >&2
    exit 1
}

package_version()
{
    local package=$1
    local version
    if version=$(rpm -q --qf '%{NAME}-%{EVR}\n' "${package}" 2>/dev/null); then
        printf '%s\n' "${version}" | head -n 1
    else
        printf 'not-installed\n'
    fi
}

record_environment()
{
    local metadata_token
    local actual_ami_id
    local actual_instance_id
    local availability_zone
    local identity_document
    local instance_type
    local pending_time
    local row_manifest_sha256=not-used
    local platform_manifest_sha256=not-used
    local comparator_manifest_sha256=not-used
    local protocol_representatives_sha256=not-used
    local verification_status=engineering-snapshot
    metadata_token=$(curl --silent --fail --max-time 2 \
        --request PUT \
        --header 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
        http://169.254.169.254/latest/api/token || true)
    instance_type=$(curl --silent --fail --max-time 2 \
        --header "X-aws-ec2-metadata-token: ${metadata_token}" \
        http://169.254.169.254/latest/meta-data/instance-type || true)
    actual_ami_id=$(curl --silent --fail --max-time 2 \
        --header "X-aws-ec2-metadata-token: ${metadata_token}" \
        http://169.254.169.254/latest/meta-data/ami-id || true)
    actual_instance_id=$(curl --silent --fail --max-time 2 \
        --header "X-aws-ec2-metadata-token: ${metadata_token}" \
        http://169.254.169.254/latest/meta-data/instance-id || true)
    availability_zone=$(curl --silent --fail --max-time 2 \
        --header "X-aws-ec2-metadata-token: ${metadata_token}" \
        http://169.254.169.254/latest/meta-data/placement/availability-zone || true)
    identity_document=$(curl --silent --fail --max-time 2 \
        --header "X-aws-ec2-metadata-token: ${metadata_token}" \
        http://169.254.169.254/latest/dynamic/instance-identity/document || true)
    pending_time=$(jq -r '.pendingTime // "unknown"' <<<"${identity_document:-{}}" 2>/dev/null || true)

    python3 "${REGULATOR_DIR}/tools/re2-benchmark/baseline/validate-manifest.py"
    row_manifest_sha256=$(sha256sum "${REGULATOR_DIR}/tools/re2-benchmark/baseline/rows.tsv" | awk '{print $1}')
    platform_manifest_sha256=$(sha256sum "${REGULATOR_DIR}/tools/re2-benchmark/baseline/platforms.tsv" | awk '{print $1}')
    comparator_manifest_sha256=$(sha256sum "${REGULATOR_DIR}/tools/re2-benchmark/baseline/comparators.tsv" | awk '{print $1}')
    protocol_representatives_sha256=$(sha256sum "${REGULATOR_DIR}/tools/re2-benchmark/baseline/protocol-representatives.tsv" | awk '{print $1}')

    if [[ "${CAMPAIGN_PROVENANCE}" == qualification ]]; then
        verification_status=verified
        if [[ ! ${REGULATOR_SNAPSHOT_COMMIT:-} =~ ^[0-9a-f]{40}$ ||
                ! ${REGULATOR_SNAPSHOT_SHA256:-} =~ ^[0-9a-f]{64}$ ]]; then
            echo "Qualification source identity is incomplete" >&2
            exit 1
        fi
        if [[ -z ${EXPECTED_AMI_ID:-} || "${actual_ami_id}" != "${EXPECTED_AMI_ID}" ]]; then
            echo "Qualification AMI mismatch: expected ${EXPECTED_AMI_ID:-unset}, found ${actual_ami_id:-unknown}" >&2
            exit 1
        fi
        if [[ ! ${BENCHMARK_JAVA_ARCHIVE_SHA256:-} =~ ^[0-9a-f]{64}$ ||
                "${INSTALLED_JAVA_ARCHIVE_SHA256}" != "${BENCHMARK_JAVA_ARCHIVE_SHA256}" ]]; then
            echo "Qualification JDK archive identity is incomplete" >&2
            exit 1
        fi
        if [[ "${CAMPAIGN_USES_TRINO:-false}" == true &&
                (! ${TRINO_SNAPSHOT_COMMIT:-} =~ ^[0-9a-f]{40}$ ||
                ! ${TRINO_SNAPSHOT_SHA256:-} =~ ^[0-9a-f]{64}$) ]]; then
            echo "Qualification Trino source identity is incomplete" >&2
            exit 1
        fi
    fi

    {
        printf 'manifest_version=1\n'
        printf 'verification_status=%s\n' "${verification_status}"
        printf 'campaign_provenance=%s\n' "${CAMPAIGN_PROVENANCE}"
        printf 'baseline_campaign=%s\n' "${RE2_CAMPAIGN_ID}"
        printf 'platform=%s\n' "${RE2_CAMPAIGN_PLATFORM}"
        printf 'shard=%s\n' "${RE2_CAMPAIGN_SHARD_ID}"
        printf 'replica=%s\n' "${RE2_CAMPAIGN_REPLICA_ID}"
        printf 'host_epoch=%s\n' "${RE2_CAMPAIGN_HOST_EPOCH}"
        printf 'campaign_architecture=%s\n' "${RE2_ENGINEERING_ARCHITECTURE:-unknown}"
        printf 'benchmark_mode=%s\n' "${BENCHMARK_MODE}"
        printf 'benchmark_heap_size=%s\n' "${BENCHMARK_HEAP_SIZE:-8g}"
        printf 'regulator_release_version=%s\n' "${REGULATOR_RELEASE_VERSION:-}"
        printf 'logical_cpu_count=%s\n' "${LOGICAL_CPU_COUNT}"
        printf 'baseline_protocol=%s\n' "${BASELINE_PROTOCOL}"
        printf 'baseline_selected_route=%s\n' "${BASELINE_SELECTED_ROUTE:-all}"
        printf 'baseline_joni_jmh_time=%s\n' "${BASELINE_JONI_JMH_TIME:-default}"
        printf 'row_manifest_sha256=%s\n' "${row_manifest_sha256}"
        printf 'platform_manifest_sha256=%s\n' "${platform_manifest_sha256}"
        printf 'comparator_manifest_sha256=%s\n' "${comparator_manifest_sha256}"
        printf 'protocol_representatives_sha256=%s\n' "${protocol_representatives_sha256}"
        printf 'native_compiler_package=%s\n' "$(package_version gcc-c++)"
        printf 'cmake_package=%s\n' "$(package_version cmake)"
        printf 'glibc_package=%s\n' "$(package_version glibc)"
        printf 'cargo_package=%s\n' "$(package_version cargo)"
        printf 'rust_package=%s\n' "$(package_version rust)"
        printf 'time_package=%s\n' "$(package_version time)"
        printf 'regulator_commit=%s\n' "${REGULATOR_SNAPSHOT_COMMIT:-unknown}"
        printf 'regulator_archive_sha256=%s\n' "${REGULATOR_SNAPSHOT_SHA256:-unknown}"
        printf 'regulator_content_sha256=%s\n' "${REGULATOR_CONTENT_SHA256:-unknown}"
        printf 'trino_used=%s\n' "${CAMPAIGN_USES_TRINO:-false}"
        printf 'trino_commit=%s\n' "${TRINO_SNAPSHOT_COMMIT:-none}"
        printf 'trino_archive_sha256=%s\n' "${TRINO_SNAPSHOT_SHA256:-none}"
        printf 'expected_ami_id=%s\n' "${EXPECTED_AMI_ID:-unverified}"
        printf 'actual_ami_id=%s\n' "${actual_ami_id:-unknown}"
        printf 'instance_id=%s\n' "${actual_instance_id:-unknown}"
        printf 'instance_type=%s\n' "${instance_type:-unknown}"
        printf 'availability_zone=%s\n' "${availability_zone:-unknown}"
        printf 'instance_pending_time=%s\n' "${pending_time:-unknown}"
        printf 'machine_architecture=%s\n' "$(uname -m)"
        printf 'jdk_archive_url=%s\n' "${BENCHMARK_JAVA_ARCHIVE_URL:-unverified-latest}"
        printf 'jdk_archive_sha256=%s\n' "${INSTALLED_JAVA_ARCHIVE_SHA256}"
        printf 'java_home=%s\n' "${JAVA_HOME}"
        printf 'java_runtime_version=%s\n' "$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.runtime.version =/ {print $2; exit}')"
        printf 'rebar_workload_definitions_sha256=%s\n' "${REBAR_WORKLOAD_DEFINITIONS_SHA256:-unknown}"
        printf 'rebar_selected_definitions_sha256=%s\n' "${REBAR_SELECTED_DEFINITIONS_SHA256:-unknown}"
        printf 'joni_evidence_scope=%s\n' "${JONI_EVIDENCE_SCOPE:-not-used}"
    } > "${RESULT_DIR}/environment-manifest.txt"

    {
        date --iso-8601=seconds
        uname -a
        cat /etc/os-release
        lscpu
        free -h
        java -version
        java \
            -Xms"${BENCHMARK_HEAP_SIZE:-8g}" \
            -Xmx"${BENCHMARK_HEAP_SIZE:-8g}" \
            -Xlog:gc+heap+coops=debug \
            -XX:+PrintFlagsFinal \
            -version 2>&1 | grep -E 'Compressed Oops mode|ObjectAlignmentInBytes|UseCompressedClassPointers|UseCompressedOops'
        c++ --version
        cmake --version
        git --version
        if command -v cargo >/dev/null; then cargo --version; fi
        printf 'regulator_snapshot_commit=%s\n' "${REGULATOR_SNAPSHOT_COMMIT:-unknown}"
        printf 'regulator_snapshot_sha256=%s\n' "${REGULATOR_SNAPSHOT_SHA256:-unknown}"
        printf 'regulator_content_sha256=%s\n' "${REGULATOR_CONTENT_SHA256:-unknown}"
        printf 'trino_snapshot_commit=%s\n' "${TRINO_SNAPSHOT_COMMIT:-unknown}"
        printf 'trino_snapshot_sha256=%s\n' "${TRINO_SNAPSHOT_SHA256:-unknown}"
        printf 'campaign_provenance=%s\n' "${CAMPAIGN_PROVENANCE}"
        printf 'baseline_campaign=%s\n' "${RE2_CAMPAIGN_ID}"
        printf 'platform=%s\n' "${RE2_CAMPAIGN_PLATFORM}"
        printf 'shard=%s\n' "${RE2_CAMPAIGN_SHARD_ID}"
        printf 'replica=%s\n' "${RE2_CAMPAIGN_REPLICA_ID}"
        printf 'host_epoch=%s\n' "${RE2_CAMPAIGN_HOST_EPOCH}"
        printf 'expected_ami_id=%s\n' "${EXPECTED_AMI_ID:-unverified}"
        printf 'actual_ami_id=%s\n' "${actual_ami_id:-unknown}"
        printf 'instance_id=%s\n' "${actual_instance_id:-unknown}"
        printf 'availability_zone=%s\n' "${availability_zone:-unknown}"
        printf 'instance_pending_time=%s\n' "${pending_time:-unknown}"
        printf 'jdk_archive_url=%s\n' "${BENCHMARK_JAVA_ARCHIVE_URL:-unverified-latest}"
        printf 'jdk_archive_sha256=%s\n' "${INSTALLED_JAVA_ARCHIVE_SHA256}"
        printf 'joni_evidence_scope=%s\n' "${JONI_EVIDENCE_SCOPE:-not-used}"
        printf 'instance_type=%s\n' "${instance_type}"
        printf 'physical_cpu_list=%s\n' "${PHYSICAL_CPU_LIST}"
        printf 'physical_core_count=%s\n' "${PHYSICAL_CORE_COUNT}"
        printf 'benchmark_mode=%s\n' "${BENCHMARK_MODE}"
        printf 'baseline_protocol=%s\n' "${BASELINE_PROTOCOL}"
        printf 'row_manifest_sha256=%s\n' "${row_manifest_sha256}"
        printf 'platform_manifest_sha256=%s\n' "${platform_manifest_sha256}"
        printf 'comparator_manifest_sha256=%s\n' "${comparator_manifest_sha256}"
        printf 'protocol_representatives_sha256=%s\n' "${protocol_representatives_sha256}"
        printf 'instance_market_type=%s\n' "${INSTANCE_MARKET_TYPE:-unknown}"
        printf 'baseline_jmh_protocol=independent-top-level-processes\n'
        printf 'baseline_jmh_process_independence=one-nonforked-jmh-run-per-process-epoch\n'
        printf 'baseline_jmh_exact_parameters=route-run-metadata\n'
        printf 'qualification_native_repetitions=5\n'
    } > "${RESULT_DIR}/environment.txt" 2>&1
}

install_packages
install_java
record_environment

export BASELINE_CPU_LIST=${BENCHMARK_CPU_LIST:-0}
    export BASELINE_HEAP_SIZE=${BENCHMARK_HEAP_SIZE:-8g}
    export BASELINE_SHARED_WORK_DIR=${BASELINE_SHARED_WORK_DIR:-${REGULATOR_DIR}/target/baseline-shared}
    capacity_file="${RESULT_DIR}/host-capacity.txt"
    memory_total_bytes=$(awk '/^MemTotal:/ {print $2 * 1024; exit}' /proc/meminfo)
    memory_available_before_bytes=$(awk '/^MemAvailable:/ {print $2 * 1024; exit}' /proc/meminfo)
    swap_total_bytes=$(awk '/^SwapTotal:/ {print $2 * 1024; exit}' /proc/meminfo)
    swap_free_before_bytes=$(awk '/^SwapFree:/ {print $2 * 1024; exit}' /proc/meminfo)
    oom_kills_before=$(awk '$1 == "oom_kill" {print $2; exit}' /proc/vmstat)
    disk_available_before_bytes=$(df -B1 --output=avail "${REGULATOR_DIR}" | awk 'NR == 2 {print $1}')
    {
        printf 'memory_total_bytes=%.0f\n' "${memory_total_bytes}"
        printf 'memory_available_before_bytes=%.0f\n' "${memory_available_before_bytes}"
        printf 'swap_total_bytes=%.0f\n' "${swap_total_bytes}"
        printf 'swap_free_before_bytes=%.0f\n' "${swap_free_before_bytes}"
        printf 'oom_kills_before=%s\n' "${oom_kills_before}"
        printf 'disk_available_before_bytes=%s\n' "${disk_available_before_bytes}"
    } > "${capacity_file}"
    if [[ "${BENCHMARK_MODE}" == language-batch ]]; then
        session_command=(bash "${REGULATOR_DIR}/tools/re2-benchmark/language/run-host-session.sh"
            "${REGULATOR_DIR}" "${RESULT_DIR}")
    else
        session_command=("${REGULATOR_DIR}/tools/re2-benchmark/baseline/run-host-session.sh"
            "${RE2_CAMPAIGN_SHARD_ID}" "${BASELINE_PROTOCOL}" "${RESULT_DIR}")
    fi
    set +e
    /usr/bin/time --append --output="${capacity_file}" \
        --format='wall_seconds=%e\nmaximum_resident_kibibytes=%M\nexit_status=%x' \
        "${session_command[@]}"
    host_session_status=$?
    set -e
    memory_available_after_bytes=$(awk '/^MemAvailable:/ {print $2 * 1024; exit}' /proc/meminfo)
    swap_free_after_bytes=$(awk '/^SwapFree:/ {print $2 * 1024; exit}' /proc/meminfo)
    oom_kills_after=$(awk '$1 == "oom_kill" {print $2; exit}' /proc/vmstat)
    disk_available_after_bytes=$(df -B1 --output=avail "${REGULATOR_DIR}" | awk 'NR == 2 {print $1}')
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
        echo "Host capacity gate rejected the baseline session; see ${capacity_file}" >&2
        exit 1
    fi
# Preserve the dedicated protocol-rejection status only after resource checks.
exit "${host_session_status}"
