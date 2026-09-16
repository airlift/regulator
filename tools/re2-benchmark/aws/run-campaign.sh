#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REGULATOR_DIR=$(cd "${SCRIPT_DIR}/../../.." && pwd)
TRINO_DIR=${TRINO_DIR:-}
AWS_PROFILE=${AWS_PROFILE:-}
AWS_REGION=${AWS_REGION:-us-west-2}
TRANSFER_BUCKET=${TRANSFER_BUCKET:-}
TRANSFER_PREFIX=${TRANSFER_PREFIX:-}
INTEL_INSTANCE_TYPE=${INTEL_INSTANCE_TYPE:-r8i.large}
ARM_INSTANCE_TYPE=${ARM_INSTANCE_TYPE:-r8g.large}
INSTANCE_MARKET_TYPE=${INSTANCE_MARKET_TYPE:-on-demand}
CAMPAIGN_SPOT_ONLY=${CAMPAIGN_SPOT_ONLY:-0}
CAMPAIGN_SPOT_MAX_PRICE=${CAMPAIGN_SPOT_MAX_PRICE:-}
if [[ -n "${CAMPAIGN_SPOT_MAX_PRICE}" && ! "${CAMPAIGN_SPOT_MAX_PRICE}" =~ ^[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid Spot price ceiling" >&2
    exit 1
fi
REGULATOR_RELEASE_VERSION=${REGULATOR_RELEASE_VERSION:-}
if [[ -n "${REGULATOR_RELEASE_VERSION}" && ! "${REGULATOR_RELEASE_VERSION}" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
    echo "Invalid release version" >&2
    exit 1
fi
if [[ "${CAMPAIGN_SPOT_ONLY}" != 0 && "${CAMPAIGN_SPOT_ONLY}" != 1 ]]; then
    echo "CAMPAIGN_SPOT_ONLY must be 0 or 1" >&2
    exit 1
fi
if [[ "${CAMPAIGN_SPOT_ONLY}" == 1 && "${INSTANCE_MARKET_TYPE}" != spot ]]; then
    echo "Spot-only policy prohibits On-Demand launches" >&2
    exit 1
fi
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-5400}
CAMPAIGN_PROVENANCE=${CAMPAIGN_PROVENANCE:-qualification}
RESULT_ROOT=${RESULT_ROOT:-"${REGULATOR_DIR}/benchmark-results/re2-qualification"}
CAMPAIGN_MODE=${CAMPAIGN_MODE:-baseline-shard}
CAMPAIGN_ARCHITECTURES=${CAMPAIGN_ARCHITECTURES:-intel}
CAMPAIGN_ID=${CAMPAIGN_ID:-baseline}
CAMPAIGN_PLATFORM=${CAMPAIGN_PLATFORM:-r8i}
CAMPAIGN_SHARD_ID=${CAMPAIGN_SHARD_ID:-traditional-search}
CAMPAIGN_REPLICA_ID=${CAMPAIGN_REPLICA_ID:-1}
CAMPAIGN_HOST_EPOCH=${CAMPAIGN_HOST_EPOCH:-1}
CAMPAIGN_ATTEMPT=${CAMPAIGN_ATTEMPT:-1}
BASELINE_PROTOCOL=${BASELINE_PROTOCOL:-qualification}
BASELINE_PROTOCOL_QUALIFICATION=${BASELINE_PROTOCOL_QUALIFICATION:-true}
BASELINE_CANDIDATE_ARCHIVE=${BASELINE_CANDIDATE_ARCHIVE:-}
BASELINE_CANDIDATE_ARCHIVE_SHA256=${BASELINE_CANDIDATE_ARCHIVE_SHA256:-}
BASELINE_EXPECTED_ENGINE_TREE=${BASELINE_EXPECTED_ENGINE_TREE:-}
LANGUAGE_BATCH_ARCHIVE=${LANGUAGE_BATCH_ARCHIVE:-}
LANGUAGE_BATCH_ARCHIVE_SHA256=${LANGUAGE_BATCH_ARCHIVE_SHA256:-}
LANGUAGE_CANDIDATE_PROVENANCE=${LANGUAGE_CANDIDATE_PROVENANCE:-}
LANGUAGE_CANDIDATE_PROVENANCE_SHA256=${LANGUAGE_CANDIDATE_PROVENANCE_SHA256:-}
BASELINE_SELECTED_ROUTE=${BASELINE_SELECTED_ROUTE:-}
BASELINE_JONI_JMH_TIME=${BASELINE_JONI_JMH_TIME:-}
TRINO_REVISION=${TRINO_REVISION:-}
NATIVE_COMPILER_PACKAGE=${NATIVE_COMPILER_PACKAGE:-}
CMAKE_PACKAGE=${CMAKE_PACKAGE:-}
GLIBC_PACKAGE=${GLIBC_PACKAGE:-}
CARGO_PACKAGE=${CARGO_PACKAGE:-}
RUST_PACKAGE=${RUST_PACKAGE:-}
TIME_PACKAGE=${TIME_PACKAGE:-}
BENCHMARK_JAVA_ARCHIVE_URL=${BENCHMARK_JAVA_ARCHIVE_URL:-}
BENCHMARK_JAVA_ARCHIVE_SHA256=${BENCHMARK_JAVA_ARCHIVE_SHA256:-}
BENCHMARK_AMI_ID=${BENCHMARK_AMI_ID:-}
BENCHMARK_INTEL_JAVA_ARCHIVE_URL=${BENCHMARK_INTEL_JAVA_ARCHIVE_URL:-}
BENCHMARK_INTEL_JAVA_ARCHIVE_SHA256=${BENCHMARK_INTEL_JAVA_ARCHIVE_SHA256:-}
BENCHMARK_ARM_JAVA_ARCHIVE_URL=${BENCHMARK_ARM_JAVA_ARCHIVE_URL:-}
BENCHMARK_ARM_JAVA_ARCHIVE_SHA256=${BENCHMARK_ARM_JAVA_ARCHIVE_SHA256:-}
BENCHMARK_INTEL_AMI_ID=${BENCHMARK_INTEL_AMI_ID:-}
BENCHMARK_ARM_AMI_ID=${BENCHMARK_ARM_AMI_ID:-}
REBAR_OFFICIAL_DIR=${REBAR_OFFICIAL_DIR:-"${HOME}/.cache/regulator/rebar-463d00f31887e84c38467805b9e3122c314b9521"}
REBAR_COMPARATOR_ORDER=${REBAR_COMPARATOR_ORDER:-forward}
JONI_COMPARATOR_ORDER=${JONI_COMPARATOR_ORDER:-forward}

campaign_uses_trino()
{
    [[ "${CAMPAIGN_MODE}" == baseline-shard ]] || return 1
    case "${CAMPAIGN_SHARD_ID}" in
        trino-operations | trino-final-line | trino-like | lifecycle | lifecycle-shared-cold | rebar-*) return 0 ;;
        like-compile | like-single-use | like-dfa-single-use) return 0 ;;
        *) return 1 ;;
    esac
}

jdk_archive_url()
{
    case "$1" in
        intel) printf '%s\n' "${BENCHMARK_INTEL_JAVA_ARCHIVE_URL:-${BENCHMARK_JAVA_ARCHIVE_URL}}" ;;
        arm) printf '%s\n' "${BENCHMARK_ARM_JAVA_ARCHIVE_URL:-${BENCHMARK_JAVA_ARCHIVE_URL}}" ;;
        *) echo "Unsupported architecture: $1" >&2; exit 1 ;;
    esac
}

jdk_archive_sha256()
{
    case "$1" in
        intel) printf '%s\n' "${BENCHMARK_INTEL_JAVA_ARCHIVE_SHA256:-${BENCHMARK_JAVA_ARCHIVE_SHA256}}" ;;
        arm) printf '%s\n' "${BENCHMARK_ARM_JAVA_ARCHIVE_SHA256:-${BENCHMARK_JAVA_ARCHIVE_SHA256}}" ;;
        *) echo "Unsupported architecture: $1" >&2; exit 1 ;;
    esac
}

benchmark_ami_id()
{
    case "$1" in
        intel) printf '%s\n' "${BENCHMARK_INTEL_AMI_ID:-${BENCHMARK_AMI_ID}}" ;;
        arm) printf '%s\n' "${BENCHMARK_ARM_AMI_ID:-${BENCHMARK_AMI_ID}}" ;;
        *) echo "Unsupported architecture: $1" >&2; exit 1 ;;
    esac
}

if [[ "${CAMPAIGN_MODE}" != baseline-shard && "${CAMPAIGN_MODE}" != language-batch ]]; then
    echo "RE2 benchmark campaign mode must be baseline-shard or language-batch" >&2
    exit 1
fi
if [[ "${TIMEOUT_SECONDS}" != 5400 ]]; then
    echo "Formal baseline jobs require TIMEOUT_SECONDS=5400, found ${TIMEOUT_SECONDS}" >&2
    exit 1
fi
if [[ "${CAMPAIGN_PROVENANCE}" != qualification ]]; then
    echo "CAMPAIGN_PROVENANCE must be qualification" >&2
    exit 1
fi
CAMPAIGN_LABEL=Qualification
SOURCE_SNAPSHOT_KIND=exact-commit
if [[ -n "${TRANSFER_BUCKET}" && -z "${TRANSFER_PREFIX}" ]]; then
    echo "TRANSFER_PREFIX is required when TRANSFER_BUCKET is set" >&2
    exit 1
fi
if [[ -n "${TRANSFER_PREFIX}" ]] &&
        { [[ "${TRANSFER_PREFIX}" == /* ]] || [[ "${TRANSFER_PREFIX}" == */ ]] ||
          [[ "${TRANSFER_PREFIX}" == *..* ]] ||
          [[ ! "${TRANSFER_PREFIX}" =~ ^[A-Za-z0-9._/-]+$ ]]; }; then
    echo "TRANSFER_PREFIX must be a safe relative S3 prefix without '..' or a trailing slash: ${TRANSFER_PREFIX}" >&2
    exit 1
fi
if campaign_uses_trino "${CAMPAIGN_MODE}"; then
    if [[ -z "${TRINO_DIR}" ]] ||
            ! git -C "${TRINO_DIR}" rev-parse --git-dir >/dev/null 2>&1; then
        echo "TRINO_DIR must name a Trino checkout for ${CAMPAIGN_MODE}" >&2
        exit 1
    fi
    if [[ ! "${TRINO_REVISION}" =~ ^[0-9a-f]{40}$ ]]; then
        echo "TRINO_REVISION must pin a full commit ID for ${CAMPAIGN_SHARD_ID}" >&2
        exit 1
    fi
    CAMPAIGN_USES_TRINO=true
else
    CAMPAIGN_USES_TRINO=false
fi
case "${CAMPAIGN_SHARD_ID}" in
    trino-operations | trino-final-line) JONI_EVIDENCE_SCOPE=trino-operation-comparison ;;
    lifecycle | lifecycle-shared-cold) JONI_EVIDENCE_SCOPE=retained-memory-comparison ;;
    rebar-*) JONI_EVIDENCE_SCOPE=rebar-bulk-text-comparison ;;
    *) JONI_EVIDENCE_SCOPE=not-used ;;
esac
case "${CAMPAIGN_ARCHITECTURES}" in
    intel | arm) ;;
    *) echo "CAMPAIGN_ARCHITECTURES must be intel or arm: ${CAMPAIGN_ARCHITECTURES}" >&2; exit 1 ;;
esac
if [[ ! ${CAMPAIGN_ID} =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}$ ]]; then
    echo "CAMPAIGN_ID must be a 1-63 character identifier: ${CAMPAIGN_ID}" >&2
    exit 1
fi
if [[ ! ${CAMPAIGN_SHARD_ID} =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}$ ]]; then
    echo "CAMPAIGN_SHARD_ID must be a 1-63 character identifier: ${CAMPAIGN_SHARD_ID}" >&2
    exit 1
fi
if [[ "${CAMPAIGN_MODE}" == baseline-shard ]] &&
        ! awk -F '\t' -v shard="${CAMPAIGN_SHARD_ID}" 'NR > 1 && $1 == shard {found = 1} END {exit !found}' \
        "${REGULATOR_DIR}/tools/re2-benchmark/baseline/shards.tsv"; then
    echo "CAMPAIGN_SHARD_ID is not in the baseline shard manifest: ${CAMPAIGN_SHARD_ID}" >&2
    exit 1
fi
if [[ ! ${CAMPAIGN_REPLICA_ID} =~ ^[1-9][0-9]*$ || ! ${CAMPAIGN_HOST_EPOCH} =~ ^[1-9][0-9]*$ ||
        ! ${CAMPAIGN_ATTEMPT} =~ ^[1-9][0-9]*$ ]]; then
    echo "CAMPAIGN_REPLICA_ID, CAMPAIGN_HOST_EPOCH, and CAMPAIGN_ATTEMPT must be positive integers" >&2
    exit 1
fi
case "${CAMPAIGN_PLATFORM}" in
    r8i)
        if [[ "${CAMPAIGN_ARCHITECTURES}" != intel || "${INTEL_INSTANCE_TYPE}" != r8i.* ]]; then
            echo "Platform r8i requires CAMPAIGN_ARCHITECTURES=intel and a r8i instance" >&2
            exit 1
        fi
        ;;
    r8g | r9g)
        required_instance_type="${CAMPAIGN_PLATFORM}.*"
        if [[ "${CAMPAIGN_ARCHITECTURES}" != arm || "${ARM_INSTANCE_TYPE}" != ${required_instance_type} ]]; then
            echo "Platform ${CAMPAIGN_PLATFORM} requires CAMPAIGN_ARCHITECTURES=arm and a ${CAMPAIGN_PLATFORM} instance" >&2
            exit 1
        fi
        ;;
    *) echo "CAMPAIGN_PLATFORM must be r8i, r8g, or r9g: ${CAMPAIGN_PLATFORM}" >&2; exit 1 ;;
esac
case "${INSTANCE_MARKET_TYPE}" in
    on-demand | spot) ;;
    *) echo "INSTANCE_MARKET_TYPE must be on-demand or spot: ${INSTANCE_MARKET_TYPE}" >&2; exit 1 ;;
esac
case "${BASELINE_PROTOCOL}" in
    smoke | qualification) ;;
    *) echo "BASELINE_PROTOCOL must be smoke or qualification: ${BASELINE_PROTOCOL}" >&2; exit 1 ;;
esac
case "${REBAR_COMPARATOR_ORDER}" in
    forward | reverse) ;;
    *) echo "REBAR_COMPARATOR_ORDER must be forward or reverse: ${REBAR_COMPARATOR_ORDER}" >&2; exit 1 ;;
esac
case "${JONI_COMPARATOR_ORDER}" in
    forward | reverse) ;;
    *) echo "JONI_COMPARATOR_ORDER must be forward or reverse: ${JONI_COMPARATOR_ORDER}" >&2; exit 1 ;;
esac
includes_architecture()
{
    [[ ",${CAMPAIGN_ARCHITECTURES}," == *",$1,"* ]]
}

case "${BASELINE_PROTOCOL_QUALIFICATION}" in
    true | false) ;;
    *) echo "BASELINE_PROTOCOL_QUALIFICATION must be true or false" >&2; exit 1 ;;
esac
case "${BASELINE_SELECTED_ROUTE}" in
    "" | native-access | object-row) ;;
    *) echo "BASELINE_SELECTED_ROUTE must be native-access or object-row" >&2; exit 1 ;;
esac
if [[ ! "${BASELINE_EXPECTED_ENGINE_TREE}" =~ ^[0-9a-f]{40,64}$ ]]; then
    echo "BASELINE_EXPECTED_ENGINE_TREE must be a Git object ID" >&2
    exit 1
fi
if [[ -z "${NATIVE_COMPILER_PACKAGE}" || -z "${CMAKE_PACKAGE}" || -z "${GLIBC_PACKAGE}" ||
        -z "${CARGO_PACKAGE}" || -z "${RUST_PACKAGE}" || -z "${TIME_PACKAGE}" ]]; then
    echo "Baseline execution requires pinned native compiler, CMake, glibc, Cargo, Rust, and time packages" >&2
    exit 1
fi

benchmark_architecture=${CAMPAIGN_ARCHITECTURES}
java_url=$(jdk_archive_url "${benchmark_architecture}")
java_sha256=$(jdk_archive_sha256 "${benchmark_architecture}")
ami_id=$(benchmark_ami_id "${benchmark_architecture}")
if [[ -z "${ami_id}" || ! "${ami_id}" =~ ^ami-[0-9a-f]+$ ]]; then
    echo "Qualification requires a pinned ${benchmark_architecture} AMI ID" >&2
    exit 1
fi
if [[ ! "${java_url}" =~ ^https:// || ! "${java_sha256}" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Qualification requires a pinned ${benchmark_architecture} JDK HTTPS URL and SHA-256" >&2
    exit 1
fi

RESOLVED_INTEL_JAVA_ARCHIVE_URL=$(jdk_archive_url intel)
RESOLVED_INTEL_JAVA_ARCHIVE_SHA256=$(jdk_archive_sha256 intel)
RESOLVED_ARM_JAVA_ARCHIVE_URL=$(jdk_archive_url arm)
RESOLVED_ARM_JAVA_ARCHIVE_SHA256=$(jdk_archive_sha256 arm)
RESOLVED_INTEL_AMI_ID=$(benchmark_ami_id intel)
RESOLVED_ARM_AMI_ID=$(benchmark_ami_id arm)

"${REGULATOR_DIR}/tools/re2-benchmark/manifests/validate-rebar-workloads.sh"
REBAR_WORKLOAD_DEFINITIONS_SHA256=$(awk '{print $1}' \
    "${REGULATOR_DIR}/tools/re2-benchmark/manifests/rebar-workloads.sha256")
REBAR_SELECTED_DEFINITIONS_SHA256=$(awk '{print $1}' \
    "${REGULATOR_DIR}/tools/re2-benchmark/manifests/rebar-selected-workloads.sha256")
python3 "${REGULATOR_DIR}/tools/re2-benchmark/baseline/validate-manifest.py"
BASELINE_ROW_MANIFEST_SHA256=$(shasum -a 256 "${REGULATOR_DIR}/tools/re2-benchmark/baseline/rows.tsv" | awk '{print $1}')
BASELINE_PLATFORM_MANIFEST_SHA256=$(shasum -a 256 "${REGULATOR_DIR}/tools/re2-benchmark/baseline/platforms.tsv" | awk '{print $1}')
BASELINE_COMPARATOR_MANIFEST_SHA256=$(shasum -a 256 "${REGULATOR_DIR}/tools/re2-benchmark/baseline/comparators.tsv" | awk '{print $1}')
BASELINE_PROTOCOL_REPRESENTATIVES_SHA256=$(shasum -a 256 "${REGULATOR_DIR}/tools/re2-benchmark/baseline/protocol-representatives.tsv" | awk '{print $1}')
SESSION_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
SESSION_DIR="${RESULT_ROOT}/${SESSION_ID}"
mkdir -p "${SESSION_DIR}"

ROOT_BASHPID=${BASHPID}
CLEANUP_CAUSE=exit
INSTANCE_IDS=()
LAUNCH_ATTEMPTED=0
DOWNLOADED_RESULT_COUNT=0
FAILURE_CLASSIFICATION=none
FAILURE_DETAIL=none
BUCKET=
BUCKET_OWNED=0
OBJECT_PREFIX=
INPUT_PREFIX=input
RESULT_PREFIX=results
REBAR_OFFICIAL_SHA256=
INSTANCE_ROLE_NAME=
INSTANCE_PROFILE_NAME=

aws_cli()
{
    local arguments=(--region "${AWS_REGION}")
    if [[ -n "${AWS_PROFILE}" ]]; then
        arguments=(--profile "${AWS_PROFILE}" "${arguments[@]}")
    fi
    aws "${arguments[@]}" "$@"
}

record_failure_classification()
{
    local classification=$1
    local detail=$2

    if [[ "${FAILURE_CLASSIFICATION}" != none && "${FAILURE_CLASSIFICATION}" != "${classification}" ]]; then
        echo "Refusing to replace failure classification ${FAILURE_CLASSIFICATION} with ${classification}" >&2
        return 1
    fi
    FAILURE_CLASSIFICATION=${classification}
    FAILURE_DETAIL=${detail}
    {
        printf 'classification=%s\n' "${FAILURE_CLASSIFICATION}"
        printf 'detail=%s\n' "${FAILURE_DETAIL}"
    } > "${SESSION_DIR}/failure-classification.txt"
}

spot_request_status()
{
    local instance_id=$1
    local request_id

    request_id=$(aws_cli ec2 describe-instances \
        --instance-ids "${instance_id}" \
        --query 'Reservations[0].Instances[0].SpotInstanceRequestId' \
        --output text 2>/dev/null) || return 1
    [[ -n "${request_id}" && "${request_id}" != None ]] || return 1
    aws_cli ec2 describe-spot-instance-requests \
        --spot-instance-request-ids "${request_id}" \
        --query 'SpotInstanceRequests[0].Status.Code' \
        --output text 2>/dev/null
}

is_retryable_spot_status()
{
    case "$1" in
        instance-terminated-by-price | instance-terminated-no-capacity | \
                instance-terminated-capacity-oversubscribed | \
                instance-stopped-by-price | marked-for-stop | marked-for-termination)
            return 0
            ;;
        *) return 1 ;;
    esac
}

is_retryable_failure_classification()
{
    case "$1" in
        retryable-spot | retryable-capacity | retryable-infrastructure) return 0 ;;
        *) return 1 ;;
    esac
}

classify_launch_error()
{
    local market=$1
    local launch_error=$2

    if grep -Eq 'VcpuLimitExceeded' <<<"${launch_error}"; then
        if [[ "${market}" == spot ]]; then
            record_failure_classification retryable-spot "launch-capacity:${launch_error}"
        else
            record_failure_classification retryable-capacity "vcpu-limit:${launch_error}"
        fi
        return 0
    fi
    if [[ "${market}" == spot ]] &&
            grep -Eq 'InsufficientInstanceCapacity|UnfulfillableCapacity|MaxSpotInstanceCountExceeded' \
                <<<"${launch_error}"; then
        record_failure_classification retryable-spot "launch-capacity:${launch_error}"
        return 0
    fi
    if grep -Eq 'InsufficientInstanceCapacity|UnfulfillableCapacity|RequestLimitExceeded' \
            <<<"${launch_error}"; then
        record_failure_classification retryable-infrastructure "launch-infrastructure:${launch_error}"
        return 0
    fi
    return 1
}

retry_cleanup_command()
{
    local attempt
    for attempt in {1..5}; do
        if aws_cli "$@" >/dev/null 2>&1; then
            return 0
        fi
        sleep "${attempt}"
    done
    return 1
}

verify_aws_resource_absent()
{
    local attempt
    local description=$1
    local absent_pattern=$2
    local output
    shift 2

    for attempt in {1..5}; do
        if ! output=$(aws_cli "$@" 2>&1); then
            if grep -Eq "${absent_pattern}" <<<"${output}"; then
                return 0
            fi
        fi
        if [[ ${attempt} -lt 5 ]]; then
            sleep "${attempt}"
        fi
    done
    if [[ -z "${output}" ]]; then
        echo "Cleanup failed: ${description} still exists" >&2
    else
        echo "Unable to verify cleanup of ${description}: ${output}" >&2
    fi
    return 1
}

verify_bucket_prefix_absent()
{
    local attempt
    local key_count

    for attempt in {1..5}; do
        if key_count=$(aws_cli s3api list-objects-v2 \
                --bucket "${BUCKET}" \
                --prefix "${OBJECT_PREFIX}/" \
                --max-keys 1 \
                --query KeyCount \
                --output text 2>/dev/null) && [[ "${key_count}" == 0 ]]; then
            return 0
        fi
        sleep "${attempt}"
    done
    echo "Cleanup failed: s3://${BUCKET}/${OBJECT_PREFIX}/ is not empty or could not be verified" >&2
    return 1
}

instance_state()
{
    local attempt
    local instance_id=$1
    local should_treat_absent_as_terminated=${2:-false}
    local output
    local state
    for attempt in {1..5}; do
        if output=$(aws_cli ec2 describe-instances \
                --instance-ids "${instance_id}" \
                --query 'Reservations[0].Instances[0].State.Name' \
                --output text 2>&1); then
            state=${output}
            printf '%s\n' "${state}"
            return 0
        fi
        if grep -q 'InvalidInstanceID.NotFound' <<<"${output}"; then
            if [[ ${attempt} -eq 5 ]]; then
                if [[ "${should_treat_absent_as_terminated}" == true ]]; then
                    printf 'terminated\n'
                else
                    printf 'pending\n'
                fi
                return 0
            fi
            sleep "${attempt}"
            continue
        fi
        sleep "${attempt}"
    done
    return 1
}

terminate_campaign_instances()
{
    local deadline=$((SECONDS + 600))
    local instance_id
    local pending_instance_ids=("${INSTANCE_IDS[@]}")
    local remaining_instance_ids
    local state
    local terminate_failed=0

    if ! retry_cleanup_command ec2 terminate-instances --instance-ids "${INSTANCE_IDS[@]}"; then
        echo "Cleanup failed: unable to request termination for benchmark instances: ${INSTANCE_IDS[*]}" >&2
        terminate_failed=1
    fi

    while [[ ${#pending_instance_ids[@]} -gt 0 && ${SECONDS} -lt ${deadline} ]]; do
        remaining_instance_ids=()
        for instance_id in "${pending_instance_ids[@]}"; do
            if state=$(instance_state "${instance_id}" true); then
                if [[ "${state}" != terminated ]]; then
                    remaining_instance_ids+=("${instance_id}")
                fi
            else
                echo "Cleanup could not query benchmark instance ${instance_id}; retrying until the cleanup deadline" >&2
                remaining_instance_ids+=("${instance_id}")
            fi
        done
        if [[ ${#remaining_instance_ids[@]} -eq 0 ]]; then
            pending_instance_ids=()
        else
            pending_instance_ids=("${remaining_instance_ids[@]}")
        fi
        if [[ ${#pending_instance_ids[@]} -gt 0 ]]; then
            sleep 15
        fi
    done

    if [[ ${#pending_instance_ids[@]} -gt 0 ]]; then
        echo "Cleanup failed: benchmark instances did not reach terminated state: ${pending_instance_ids[*]}" >&2
        return 1
    fi
    [[ ${terminate_failed} -eq 0 ]]
}

verify_campaign_instance_ownership()
{
    local campaign
    local instance_id
    local project
    local tags

    for instance_id in "${INSTANCE_IDS[@]}"; do
        if ! tags=$(aws_cli ec2 describe-instances \
                --instance-ids "${instance_id}" \
                --query 'Reservations[0].Instances[0].Tags' \
                --output json 2>/dev/null); then
            echo "Cleanup refused: unable to verify ownership of ${instance_id}" >&2
            return 1
        fi
        project=$(jq -r '.[] | select(.Key == "Project") | .Value' <<<"${tags}")
        campaign=$(jq -r '.[] | select(.Key == "Campaign") | .Value' <<<"${tags}")
        if [[ "${project}" != re2-port-benchmark || "${campaign}" != "${SESSION_ID}" ]]; then
            echo "Cleanup refused: ${instance_id} is not owned by campaign ${SESSION_ID}" >&2
            return 1
        fi
    done
}

remember_instance_id()
{
    local existing_instance_id
    local instance_id=$1

    [[ -n "${instance_id}" && "${instance_id}" != None ]] || return
    for existing_instance_id in "${INSTANCE_IDS[@]}"; do
        if [[ "${existing_instance_id}" == "${instance_id}" ]]; then
            return
        fi
    done
    INSTANCE_IDS+=("${instance_id}")
}

discover_campaign_instances()
{
    local attempt
    local discovered_instance_ids
    local instance_id
    local maximum_attempts=1
    local query_succeeded=0

    if [[ ${LAUNCH_ATTEMPTED} -ne 0 && ${#INSTANCE_IDS[@]} -eq 0 ]] &&
            ! is_retryable_failure_classification "${FAILURE_CLASSIFICATION}"; then
        maximum_attempts=20
    fi
    for ((attempt = 1; attempt <= maximum_attempts; attempt++)); do
        if discovered_instance_ids=$(aws_cli ec2 describe-instances \
                --filters \
                    Name=tag:Project,Values=re2-port-benchmark \
                    "Name=tag:Campaign,Values=${SESSION_ID}" \
                --query 'Reservations[].Instances[].InstanceId' \
                --output text 2>/dev/null); then
            query_succeeded=1
            for instance_id in ${discovered_instance_ids}; do
                remember_instance_id "${instance_id}"
            done
            if [[ ${#INSTANCE_IDS[@]} -gt 0 || ${maximum_attempts} -eq 1 ]]; then
                return 0
            fi
        fi
        sleep 3
    done
    if [[ ${LAUNCH_ATTEMPTED} -ne 0 && ${#INSTANCE_IDS[@]} -eq 0 ]]; then
        return 1
    fi
    [[ ${query_succeeded} -ne 0 ]]
}

recover_uploaded_results()
{
    # Instances have terminated, so the inventory cannot race a late upload.
    # Keep these bytes separate from accepted/extracted results on abort.
    local recovery_dir="${SESSION_DIR}/recovered-uploads"
    local prefix="${RESULT_PREFIX}/"
    mkdir -p "${recovery_dir}"
    aws_cli s3api list-objects-v2 --bucket "${BUCKET}" --prefix "${prefix}" \
        --output json > "${recovery_dir}/inventory.json" || return 1
    aws_cli s3 cp "s3://${BUCKET}/${prefix}" "${recovery_dir}/objects/" \
        --recursive --only-show-errors || return 1
    python3 "${SCRIPT_DIR}/verify-recovered-results.py" \
        "${recovery_dir}" "${prefix}"
}

cleanup()
{
    local status=$?
    local cleanup_bash_pid=${BASHPID}
    local cleanup_subshell=${BASH_SUBSHELL}
    local association_id
    local cleanup_failed=0
    local bucket_cleanup_status=not-created
    local iam_cleanup_status=not-created
    local instance_cleanup_status=not-created
    local preserve_resources=0
    local result_recovery_status=not-required
    trap - EXIT
    # A controller abort can arrive after cleanup has already started.
    trap '' INT TERM

    {
        printf 'timestamp=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'root_bash_pid=%s\n' "${ROOT_BASHPID:-${BASHPID}}"
        printf 'cleanup_bash_pid=%s\n' "${cleanup_bash_pid}"
        printf 'parent_pid=%s\n' "${PPID}"
        printf 'cleanup_subshell=%s\n' "${cleanup_subshell}"
        printf 'cleanup_cause=%s\n' "${CLEANUP_CAUSE:-exit}"
        printf 'exit_status=%s\n' "${status}"
    } > "${SESSION_DIR}/cleanup-invocation-${cleanup_bash_pid}.txt"
    if [[ -n ${ROOT_BASHPID:-} && ${BASHPID} -ne ${ROOT_BASHPID} ]]; then
        echo "Ignoring AWS cleanup from non-root benchmark shell ${BASHPID}" >&2
        return "${status}"
    fi

    if [[ ${LAUNCH_ATTEMPTED} -eq 0 ]]; then
        instance_cleanup_status=verified
    fi
    if [[ -z "${INSTANCE_PROFILE_NAME}" && -z "${INSTANCE_ROLE_NAME}" ]]; then
        iam_cleanup_status=verified
    fi
    if [[ -z "${BUCKET}" ]]; then
        bucket_cleanup_status=verified
    fi

    if [[ -f "${SESSION_DIR}/failure-classification.txt" ]]; then
        FAILURE_CLASSIFICATION=$(awk -F= '$1 == "classification" {print substr($0, length($1) + 2)}' \
            "${SESSION_DIR}/failure-classification.txt")
        FAILURE_DETAIL=$(awk -F= '$1 == "detail" {print substr($0, length($1) + 2)}' \
            "${SESSION_DIR}/failure-classification.txt")
    fi

    if [[ ${LAUNCH_ATTEMPTED} -ne 0 ]] && ! discover_campaign_instances; then
        echo "Cleanup refused: unable to discover all instances for campaign ${SESSION_ID}" >&2
        cleanup_failed=1
        preserve_resources=1
    elif [[ ${LAUNCH_ATTEMPTED} -ne 0 && ${#INSTANCE_IDS[@]} -eq 0 ]] &&
            is_retryable_failure_classification "${FAILURE_CLASSIFICATION}"; then
        instance_cleanup_status=verified
    fi
    if [[ ${preserve_resources} -eq 0 && ${#INSTANCE_IDS[@]} -gt 0 ]]; then
        if verify_campaign_instance_ownership; then
            if terminate_campaign_instances; then
                instance_cleanup_status=verified
                for instance_id in "${INSTANCE_IDS[@]}"; do
                    association_id=$(aws_cli ec2 describe-iam-instance-profile-associations \
                        --filters "Name=instance-id,Values=${instance_id}" \
                        --query 'IamInstanceProfileAssociations[0].AssociationId' \
                        --output text 2>/dev/null || true)
                    if [[ -n "${association_id}" && "${association_id}" != None ]]; then
                        retry_cleanup_command ec2 disassociate-iam-instance-profile --association-id "${association_id}" || true
                    fi
                done
            else
                instance_cleanup_status=failed
                cleanup_failed=1
                preserve_resources=1
            fi
        else
            instance_cleanup_status=unverified
            cleanup_failed=1
            preserve_resources=1
        fi
    fi
    if [[ ${preserve_resources} -eq 0 && -n "${INSTANCE_PROFILE_NAME}" && -n "${INSTANCE_ROLE_NAME}" ]]; then
        iam_cleanup_status=failed
        retry_cleanup_command iam remove-role-from-instance-profile \
            --instance-profile-name "${INSTANCE_PROFILE_NAME}" \
            --role-name "${INSTANCE_ROLE_NAME}" || true
        retry_cleanup_command iam delete-instance-profile --instance-profile-name "${INSTANCE_PROFILE_NAME}" || true
        retry_cleanup_command iam delete-role-policy --role-name "${INSTANCE_ROLE_NAME}" --policy-name CampaignTransfer || true
        retry_cleanup_command iam delete-role --role-name "${INSTANCE_ROLE_NAME}" || true
        verify_aws_resource_absent \
            "instance profile ${INSTANCE_PROFILE_NAME}" \
            'NoSuchEntity' \
            iam get-instance-profile --instance-profile-name "${INSTANCE_PROFILE_NAME}" || cleanup_failed=1
        verify_aws_resource_absent \
            "role ${INSTANCE_ROLE_NAME}" \
            'NoSuchEntity' \
            iam get-role --role-name "${INSTANCE_ROLE_NAME}" || cleanup_failed=1
        if [[ ${cleanup_failed} -eq 0 ]]; then
            iam_cleanup_status=verified
        fi
    fi
    if [[ ${preserve_resources} -eq 0 && -n "${BUCKET}" && ${#INSTANCE_IDS[@]} -gt 0 ]]; then
        result_recovery_status=failed
        if recover_uploaded_results; then
            result_recovery_status=verified
        else
            echo "Result recovery failed; retaining the transfer bucket or prefix" >&2
            cleanup_failed=1
        fi
    fi
    if [[ ${preserve_resources} -eq 0 && -n "${BUCKET}" && "${result_recovery_status}" != failed ]]; then
        if [[ ${BUCKET_OWNED} -ne 0 ]]; then
            retry_cleanup_command s3 rm "s3://${BUCKET}" --recursive || true
            retry_cleanup_command s3api delete-bucket --bucket "${BUCKET}" || true
            verify_aws_resource_absent \
                "bucket ${BUCKET}" \
                '404|NoSuchBucket|Not Found' \
                s3api head-bucket --bucket "${BUCKET}" || cleanup_failed=1
        else
            retry_cleanup_command s3 rm "s3://${BUCKET}/${OBJECT_PREFIX}/" --recursive || true
            verify_bucket_prefix_absent || cleanup_failed=1
        fi
        if [[ ${cleanup_failed} -eq 0 ]]; then
            bucket_cleanup_status=verified
        else
            bucket_cleanup_status=failed
        fi
    fi
    if [[ ${preserve_resources} -ne 0 ]]; then
        [[ ${#INSTANCE_IDS[@]} -eq 0 ]] || instance_cleanup_status=unverified
        [[ -z "${INSTANCE_PROFILE_NAME}" ]] || iam_cleanup_status=unverified
        [[ -z "${BUCKET}" ]] || bucket_cleanup_status=unverified
        echo "Campaign ownership could not be verified; preserving all AWS resources" >&2
    fi
    if [[ ${status} -eq 0 ]] &&
            { [[ ${LAUNCH_ATTEMPTED} -ne 0 && "${instance_cleanup_status}" != verified ]] ||
              [[ -n "${INSTANCE_PROFILE_NAME}" && "${iam_cleanup_status}" != verified ]] ||
              [[ -n "${BUCKET}" && "${bucket_cleanup_status}" != verified ]]; }; then
        cleanup_failed=1
    fi
    if [[ ${status} -eq 0 && ${cleanup_failed} -ne 0 ]]; then
        status=1
    fi
    if [[ -n ${SESSION_DIR:-} ]]; then
        {
            printf 'manifest_version=1\n'
            printf 'baseline_campaign=%s\n' "${CAMPAIGN_ID}"
            printf 'platform=%s\n' "${CAMPAIGN_PLATFORM}"
            printf 'shard=%s\n' "${CAMPAIGN_SHARD_ID}"
            printf 'replica=%s\n' "${CAMPAIGN_REPLICA_ID}"
            printf 'host_epoch=%s\n' "${CAMPAIGN_HOST_EPOCH}"
            printf 'instances_terminated=%s\n' "${instance_cleanup_status}"
            printf 'iam_removed=%s\n' "${iam_cleanup_status}"
            printf 'bucket_removed=%s\n' "${bucket_cleanup_status}"
            printf 'uploaded_result_recovery=%s\n' "${result_recovery_status}"
            printf 'bucket_owned=%s\n' "${BUCKET_OWNED}"
            printf 'object_prefix=%s\n' "${OBJECT_PREFIX}"
            printf 'network_resources=default-vpc-reused\n'
            printf 'cleanup_status=%s\n' "$([[ ${cleanup_failed} -eq 0 && ${preserve_resources} -eq 0 ]] && echo verified || echo failed)"
            printf 'run_status=%s\n' "$([[ ${status} -eq 0 ]] && echo complete || echo failed)"
            printf 'root_bash_pid=%s\n' "${ROOT_BASHPID:-${BASHPID}}"
            printf 'cleanup_bash_pid=%s\n' "${cleanup_bash_pid}"
            printf 'cleanup_subshell=%s\n' "${cleanup_subshell}"
            printf 'cleanup_cause=%s\n' "${CLEANUP_CAUSE:-exit}"
            printf 'failure_classification=%s\n' "${FAILURE_CLASSIFICATION}"
            printf 'failure_detail=%s\n' "${FAILURE_DETAIL}"
        } > "${SESSION_DIR}/cleanup-manifest.txt"
        if [[ ${status} -eq 0 ]]; then
            printf 'complete\n' > "${SESSION_DIR}/campaign-success"
            echo "${CAMPAIGN_LABEL} run and cleanup complete: ${SESSION_DIR}"
        fi
    fi
    exit "${status}"
}

abort_campaign()
{
    CLEANUP_CAUSE=$1
    FAILURE_CLASSIFICATION=controller-abort
    FAILURE_DETAIL="received $1"
    exit "$2"
}

trap cleanup EXIT
trap 'abort_campaign INT 130' INT
trap 'abort_campaign TERM 143' TERM

package_commit()
{
    local worktree=$1
    local commit=$2
    local output=$3
    local prefix=${4:-}

    if [[ -n "${prefix}" ]]; then
        git -C "${worktree}" archive --format=tar --prefix="${prefix}" "${commit}" \
            | gzip -n > "${output}"
        return
    fi
    git -C "${worktree}" archive --format=tar "${commit}" \
        | gzip -n > "${output}"
}

package_rebar_commit()
{
    local worktree=$1
    local commit=$2
    local output=$3

    git -C "${worktree}" archive \
        --format=tar \
        --prefix=rebar-corpus/ \
        --add-virtual-file="rebar-corpus/.regulator-rebar-commit:${commit}" \
        "${commit}" \
        | gzip -n > "${output}"
}

require_clean_worktree()
{
    local worktree=$1
    local label=$2

    if [[ -n $(git -C "${worktree}" status --porcelain) ]]; then
        echo "Qualification requires a clean ${label} worktree: ${worktree}" >&2
        exit 1
    fi
}

sha256()
{
    shasum -a 256 "$1" | awk '{print $1}'
}

archive_content_sha256()
{
    gzip -dc "$1" | shasum -a 256 | awk '{print $1}'
}

commit_archive_sha256()
{
    local worktree=$1
    local commit=$2

    git -C "${worktree}" archive --format=tar "${commit}" \
        | gzip -n \
        | shasum -a 256 \
        | awk '{print $1}'
}

create_instance_profile()
{
    INSTANCE_ROLE_NAME="re2-benchmark-${LOWER_SESSION_ID}"
    INSTANCE_PROFILE_NAME="${INSTANCE_ROLE_NAME}"

    cat > "${SESSION_DIR}/instance-trust-policy.json" <<'EOF'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
EOF
    cat > "${SESSION_DIR}/instance-transfer-policy.json" <<EOF
{"Version":"2012-10-17","Statement":[
  {"Effect":"Allow","Action":"s3:GetObject","Resource":"arn:aws:s3:::${BUCKET}/${INPUT_PREFIX}/*"},
  {"Effect":"Allow","Action":"s3:PutObject","Resource":"arn:aws:s3:::${BUCKET}/${RESULT_PREFIX}/*"}
]}
EOF

    aws_cli iam create-role \
        --role-name "${INSTANCE_ROLE_NAME}" \
        --assume-role-policy-document "file://${SESSION_DIR}/instance-trust-policy.json" >/dev/null
    aws_cli iam put-role-policy \
        --role-name "${INSTANCE_ROLE_NAME}" \
        --policy-name CampaignTransfer \
        --policy-document "file://${SESSION_DIR}/instance-transfer-policy.json"
    aws_cli iam create-instance-profile --instance-profile-name "${INSTANCE_PROFILE_NAME}" >/dev/null
    aws_cli iam add-role-to-instance-profile \
        --instance-profile-name "${INSTANCE_PROFILE_NAME}" \
        --role-name "${INSTANCE_ROLE_NAME}"

    # IAM instance profiles are eventually consistent with EC2 launches.
    sleep 10
}

select_subnet()
{
    local instance_type=$1
    local available_zones
    local replica_index
    local placement_seed
    local subnet_count
    local subnets
    local zone_values
    available_zones=$(aws_cli ec2 describe-instance-type-offerings \
        --location-type availability-zone \
        --filters "Name=instance-type,Values=${instance_type}" \
        --query 'InstanceTypeOfferings[].Location' \
        --output text)
    zone_values=$(tr '\t ' '\n' <<<"${available_zones}" | sed '/^$/d' | paste -sd, -)

    subnets=$(aws_cli ec2 describe-subnets \
        --filters \
            "Name=vpc-id,Values=${VPC_ID}" \
            "Name=state,Values=available" \
            "Name=availability-zone,Values=${zone_values}" \
        --query 'sort_by(Subnets[?MapPublicIpOnLaunch], &AvailabilityZone)[].SubnetId' \
        --output text)
    read -r -a subnet_array <<<"${subnets}"
    subnet_count=${#subnet_array[@]}
    if [[ ${subnet_count} -eq 0 ]]; then
        return
    fi
    # Keep the initial placement stable for this job, then visit a different
    # pool on each retry regardless of other jobs' allocated host epochs.
    placement_seed=$(printf '%s' "${CAMPAIGN_PLATFORM}/${CAMPAIGN_SHARD_ID}/replica-${CAMPAIGN_REPLICA_ID}" | cksum | awk '{print $1}')
    replica_index=$(((placement_seed + CAMPAIGN_ATTEMPT - 1) % subnet_count))
    printf '%s\n' "${subnet_array[${replica_index}]}"
}

create_user_data()
{
    local campaign_architecture=$1
    local architecture=$2
    local result_key=$3
    local output=$4
    local expected_ami_id=$5
    local java_archive_url=$6
    local java_archive_sha256=$7
    cat > "${output}" <<EOF
#!/usr/bin/env bash
set -Eeuo pipefail

RESULT_DIR=/var/tmp/re2-results
mkdir -p "\${RESULT_DIR}"
exec > >(tee /var/log/re2-engineering-user-data.log) 2>&1

finish()
{
    status=\$?
    trap - EXIT
    set +e
    printf '%s\n' "\${status}" > "\${RESULT_DIR}/exit-status"
    if [[ -d /opt/re2-work/regulator/target/surefire-reports ]]; then
        cp -a /opt/re2-work/regulator/target/surefire-reports "\${RESULT_DIR}/regulator-surefire-reports"
    fi
    if [[ -d /opt/re2-work/trino/core/trino-main/target/surefire-reports ]]; then
        cp -a /opt/re2-work/trino/core/trino-main/target/surefire-reports "\${RESULT_DIR}/trino-surefire-reports"
    fi
    cp /var/log/re2-engineering-user-data.log "\${RESULT_DIR}/user-data.log" || true
    archive_status=0
    tar -czf /var/tmp/re2-results.tar.gz -C /var/tmp re2-results || archive_status=\$?
    upload_status=1
    if [[ \${archive_status} -eq 0 ]]; then
        for upload_attempt in {1..12}; do
            if aws s3 cp /var/tmp/re2-results.tar.gz 's3://${BUCKET}/${result_key}' \
                    --region '${AWS_REGION}' --only-show-errors; then
                upload_status=0
                break
            fi
            sleep 5
        done
    fi
    if [[ \${archive_status} -ne 0 || \${upload_status} -ne 0 ]]; then
        echo "Result artifact transfer failed: archive_status=\${archive_status}, upload_status=\${upload_status}" >&2
        status=1
    fi
    shutdown -h now
    exit "\${status}"
}
trap finish EXIT

wait_for_aws_credentials()
{
    local credential_attempt
    for credential_attempt in {1..60}; do
        if aws sts get-caller-identity --region '${AWS_REGION}' >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    echo "Instance-profile credentials did not become ready" >&2
    return 1
}

download_input()
{
    local source=\$1
    local destination=\$2
    local download_attempt
    for download_attempt in {1..24}; do
        if aws s3 cp "\${source}" "\${destination}" \
                --region '${AWS_REGION}' --only-show-errors; then
            return 0
        fi
        sleep 5
    done
    echo "Unable to download campaign input \${source}" >&2
    return 1
}

wait_for_aws_credentials

mkdir -p /opt/re2-work/regulator
download_input 's3://${BUCKET}/${INPUT_PREFIX}/regulator.tar.gz' /tmp/regulator.tar.gz
printf '%s  %s\n' '${REGULATOR_SHA256}' /tmp/regulator.tar.gz | sha256sum --check -
tar -xzf /tmp/regulator.tar.gz -C /opt/re2-work/regulator
export PYTHONDONTWRITEBYTECODE=1
if [[ '${CAMPAIGN_MODE}' == language-batch ]]; then
    download_input 's3://${BUCKET}/${INPUT_PREFIX}/language-batch.tar.gz' /tmp/language-batch.tar.gz
    download_input 's3://${BUCKET}/${INPUT_PREFIX}/candidate-provenance.tsv' /tmp/candidate-provenance.tsv
    printf '%s  %s\n' '${LANGUAGE_CANDIDATE_PROVENANCE_SHA256}' /tmp/candidate-provenance.tsv | sha256sum --check -
    python3 /opt/re2-work/regulator/tools/re2-benchmark/language/transport.py \\
        /tmp/language-batch.tar.gz --sha256 '${LANGUAGE_BATCH_ARCHIVE_SHA256}' \\
        --platform '${CAMPAIGN_PLATFORM}' --shard '${CAMPAIGN_SHARD_ID}' --replica '${CAMPAIGN_REPLICA_ID}' \\
        --destination "\${RESULT_DIR}/language-inputs"
    export LANGUAGE_SOURCE_ARCHIVE=/tmp/regulator.tar.gz
    export LANGUAGE_CANDIDATE_PROVENANCE=/tmp/candidate-provenance.tsv
fi
if [[ '${CAMPAIGN_USES_TRINO}' == true ]]; then
    mkdir -p /opt/re2-work/trino
    download_input 's3://${BUCKET}/${INPUT_PREFIX}/trino.tar.gz' /tmp/trino.tar.gz
    printf '%s  %s\n' '${TRINO_SHA256}' /tmp/trino.tar.gz | sha256sum --check -
    tar -xzf /tmp/trino.tar.gz -C /opt/re2-work/trino
fi
if [[ '${REBAR_OFFICIAL_SHA256}' != '' ]]; then
    mkdir -p /opt/re2-work/rebar
    download_input 's3://${BUCKET}/${INPUT_PREFIX}/rebar-official.tar.gz' /tmp/rebar-official.tar.gz
    printf '%s  %s\n' '${REBAR_OFFICIAL_SHA256}' /tmp/rebar-official.tar.gz | sha256sum --check -
    tar --no-same-owner -xzf /tmp/rebar-official.tar.gz -C /opt/re2-work/rebar --strip-components=1
fi

export REGULATOR_SNAPSHOT_COMMIT='${REGULATOR_COMMIT}'
export REGULATOR_SNAPSHOT_SHA256='${REGULATOR_SHA256}'
export REGULATOR_CONTENT_SHA256='${REGULATOR_CONTENT_SHA256}'
export TRINO_SNAPSHOT_COMMIT='${TRINO_COMMIT}'
export TRINO_SNAPSHOT_SHA256='${TRINO_SHA256}'
export CAMPAIGN_PROVENANCE='${CAMPAIGN_PROVENANCE}'
export RE2_CAMPAIGN_ID='${CAMPAIGN_ID}'
export RE2_CAMPAIGN_PLATFORM='${CAMPAIGN_PLATFORM}'
export RE2_CAMPAIGN_SHARD_ID='${CAMPAIGN_SHARD_ID}'
export RE2_CAMPAIGN_REPLICA_ID='${CAMPAIGN_REPLICA_ID}'
export RE2_CAMPAIGN_HOST_EPOCH='${CAMPAIGN_HOST_EPOCH}'
export BASELINE_PROTOCOL='${BASELINE_PROTOCOL}'
export BASELINE_PROTOCOL_QUALIFICATION='${BASELINE_PROTOCOL_QUALIFICATION}'
export BASELINE_EXPECTED_ENGINE_TREE='${BASELINE_EXPECTED_ENGINE_TREE}'
export BASELINE_SELECTED_ROUTE='${BASELINE_SELECTED_ROUTE}'
export BASELINE_JONI_JMH_TIME='${BASELINE_JONI_JMH_TIME}'
export NATIVE_COMPILER_PACKAGE='${NATIVE_COMPILER_PACKAGE}'
export CMAKE_PACKAGE='${CMAKE_PACKAGE}'
export GLIBC_PACKAGE='${GLIBC_PACKAGE}'
export CARGO_PACKAGE='${CARGO_PACKAGE}'
export RUST_PACKAGE='${RUST_PACKAGE}'
export TIME_PACKAGE='${TIME_PACKAGE}'
export CAMPAIGN_USES_TRINO='${CAMPAIGN_USES_TRINO}'
export JONI_EVIDENCE_SCOPE='${JONI_EVIDENCE_SCOPE}'
export EXPECTED_AMI_ID='${expected_ami_id}'
export RE2_ENGINEERING_ARCHITECTURE='${campaign_architecture}'
export RE2_BENCHMARK_MODE='${CAMPAIGN_MODE}'
export INSTANCE_MARKET_TYPE='${INSTANCE_MARKET_TYPE}'
export REGULATOR_RELEASE_VERSION='${REGULATOR_RELEASE_VERSION}'
export BENCHMARK_JAVA_ARCHIVE_URL='${java_archive_url}'
export BENCHMARK_JAVA_ARCHIVE_SHA256='${java_archive_sha256}'
export REBAR_ROOT='/opt/re2-work/rebar'
export REBAR_COMPARATOR_ORDER='${REBAR_COMPARATOR_ORDER}'
export JONI_COMPARATOR_ORDER='${JONI_COMPARATOR_ORDER}'
export REBAR_WORKLOAD_DEFINITIONS_SHA256='${REBAR_WORKLOAD_DEFINITIONS_SHA256}'
export REBAR_SELECTED_DEFINITIONS_SHA256='${REBAR_SELECTED_DEFINITIONS_SHA256}'
export BENCHMARK_HEAP_SIZE='${BENCHMARK_HEAP_SIZE:-8g}'
export BENCHMARK_CPU_LIST='${BENCHMARK_CPU_LIST:-0}'
export BENCHMARK_EXPECTED_VCPUS='${BENCHMARK_EXPECTED_VCPUS:-}'

trino_directory=
if [[ '${CAMPAIGN_USES_TRINO}' == true ]]; then
    trino_directory=/opt/re2-work/trino
fi
/opt/re2-work/regulator/tools/re2-benchmark/aws/run-host.sh \
    /opt/re2-work/regulator \
    "\${trino_directory}" \
    "\${RESULT_DIR}"
EOF
}

launch_instance()
{
    local label=$1
    local architecture=$2
    local instance_type=$3
    local ami_parameter=$4
    local result_key=$5
    local user_data="${SESSION_DIR}/user-data-${label}.sh"
    local ami
    local attempt
    local client_token="re2-${LOWER_SESSION_ID}-${label}"
    local instance_id
    local launch_error=
    local root_device
    local subnet
    local launch_arguments

    local java_url
    local java_sha256
    ami=$(benchmark_ami_id "${label}")
    java_url=$(jdk_archive_url "${label}")
    java_sha256=$(jdk_archive_sha256 "${label}")
    if [[ -n "${ami}" ]]; then
        :
    else
        ami=$(aws_cli ssm get-parameter --name "${ami_parameter}" --query Parameter.Value --output text)
    fi
    root_device=$(aws_cli ec2 describe-images --image-ids "${ami}" --query 'Images[0].RootDeviceName' --output text)
    subnet=$(select_subnet "${instance_type}")
    if [[ -z "${subnet}" || "${subnet}" == "None" ]]; then
        echo "No public default-VPC subnet offers ${instance_type}" >&2
        exit 1
    fi

    create_user_data \
        "${label}" \
        "${architecture}" \
        "${result_key}" \
        "${user_data}" \
        "${ami}" \
        "${java_url}" \
        "${java_sha256}"

    launch_arguments=(
        --image-id "${ami}" \
        --instance-type "${instance_type}" \
        --count 1 \
        --network-interfaces "DeviceIndex=0,SubnetId=${subnet},Groups=${SECURITY_GROUP_ID},AssociatePublicIpAddress=true" \
        --block-device-mappings "DeviceName=${root_device},Ebs={VolumeSize=40,VolumeType=gp3,DeleteOnTermination=true,Encrypted=true}" \
        --metadata-options HttpTokens=required,HttpEndpoint=enabled \
        --iam-instance-profile "Name=${INSTANCE_PROFILE_NAME}" \
        --instance-initiated-shutdown-behavior terminate)
    if [[ "${INSTANCE_MARKET_TYPE}" == spot ]]; then
        spot_options='SpotInstanceType=one-time,InstanceInterruptionBehavior=terminate'
        if [[ -n "${CAMPAIGN_SPOT_MAX_PRICE}" ]]; then
            spot_options+=",MaxPrice=${CAMPAIGN_SPOT_MAX_PRICE}"
        fi
        launch_arguments+=(
            --instance-market-options
            "MarketType=spot,SpotOptions={${spot_options}}")
    fi

    for attempt in {1..5}; do
        if instance_id=$(aws_cli ec2 run-instances \
                "${launch_arguments[@]}" \
                --client-token "${client_token}" \
                --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=re2-${CAMPAIGN_PROVENANCE}-${label}-${SESSION_ID}},{Key=Project,Value=re2-port-benchmark},{Key=Campaign,Value=${SESSION_ID}},{Key=BaselineCampaign,Value=${CAMPAIGN_ID}},{Key=Platform,Value=${CAMPAIGN_PLATFORM}},{Key=Shard,Value=${CAMPAIGN_SHARD_ID}},{Key=Replica,Value=${CAMPAIGN_REPLICA_ID}},{Key=HostEpoch,Value=${CAMPAIGN_HOST_EPOCH}}]" \
                --user-data "file://${user_data}" \
                --query 'Instances[0].InstanceId' \
                --output text 2>"${SESSION_DIR}/launch-${label}.error"); then
            printf '%s\n' "${instance_id}"
            return
        fi
        launch_error=$(tr '\n\t' '  ' < "${SESSION_DIR}/launch-${label}.error")
        sleep "${attempt}"
    done
    classify_launch_error "${INSTANCE_MARKET_TYPE}" "${launch_error}" || true
    echo "Unable to launch or recover the ${label} instance for campaign ${SESSION_ID}" >&2
    return 1
}

REGULATOR_COMMIT=$(git -C "${REGULATOR_DIR}" rev-parse HEAD)
TRINO_COMMIT=none
if [[ "${CAMPAIGN_USES_TRINO}" == true ]]; then
    TRINO_COMMIT=$(git -C "${TRINO_DIR}" rev-parse "${TRINO_REVISION:-HEAD}^{commit}")
fi

require_clean_worktree "${REGULATOR_DIR}" Regulator
if [[ ! -f "${BASELINE_CANDIDATE_ARCHIVE}" ||
        ! "${BASELINE_CANDIDATE_ARCHIVE_SHA256}" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Formal baseline execution requires the frozen candidate archive and SHA-256" >&2
    exit 1
fi
cp "${BASELINE_CANDIDATE_ARCHIVE}" "${SESSION_DIR}/regulator.tar.gz"
if [[ $(sha256 "${SESSION_DIR}/regulator.tar.gz") != "${BASELINE_CANDIDATE_ARCHIVE_SHA256}" ]]; then
    echo "Frozen candidate archive SHA-256 mismatch" >&2
    exit 1
fi
if [[ $(commit_archive_sha256 "${REGULATOR_DIR}" "${REGULATOR_COMMIT}") != "${BASELINE_CANDIDATE_ARCHIVE_SHA256}" ]]; then
    echo "Frozen candidate archive does not reproduce the current Regulator commit" >&2
    exit 1
fi
if [[ $(git -C "${REGULATOR_DIR}" rev-parse HEAD:src/main) != "${BASELINE_EXPECTED_ENGINE_TREE}" ]]; then
    echo "Current Regulator production tree does not match BASELINE_EXPECTED_ENGINE_TREE" >&2
    exit 1
fi
if [[ "${CAMPAIGN_MODE}" == language-batch ]]; then
    if [[ ! -f "${LANGUAGE_BATCH_ARCHIVE}" || ! -f "${LANGUAGE_CANDIDATE_PROVENANCE}" ||
            ! "${LANGUAGE_BATCH_ARCHIVE_SHA256}" =~ ^[0-9a-f]{64}$ ||
            ! "${LANGUAGE_CANDIDATE_PROVENANCE_SHA256}" =~ ^[0-9a-f]{64}$ ]]; then
        echo "Language execution requires checksummed batch inputs and candidate provenance" >&2
        exit 1
    fi
    cp "${LANGUAGE_BATCH_ARCHIVE}" "${SESSION_DIR}/language-batch.tar.gz"
    cp "${LANGUAGE_CANDIDATE_PROVENANCE}" "${SESSION_DIR}/candidate-provenance.tsv"
    if [[ $(sha256 "${SESSION_DIR}/candidate-provenance.tsv") != "${LANGUAGE_CANDIDATE_PROVENANCE_SHA256}" ]]; then
        echo "Language candidate provenance checksum mismatch" >&2
        exit 1
    fi
    PYTHONDONTWRITEBYTECODE=1 python3 "${REGULATOR_DIR}/tools/re2-benchmark/language/transport.py" \
        "${SESSION_DIR}/language-batch.tar.gz" --sha256 "${LANGUAGE_BATCH_ARCHIVE_SHA256}" \
        --platform "${CAMPAIGN_PLATFORM}" --shard "${CAMPAIGN_SHARD_ID}" --replica "${CAMPAIGN_REPLICA_ID}"
fi
if [[ "${CAMPAIGN_USES_TRINO}" == true ]]; then
    package_commit "${TRINO_DIR}" "${TRINO_COMMIT}" "${SESSION_DIR}/trino.tar.gz"
fi

if [[ "${CAMPAIGN_SHARD_ID}" == rebar-* ]]; then
    if [[ ! -d "${REBAR_OFFICIAL_DIR}/.git" ]]; then
        echo "Pinned Rebar checkout does not exist: ${REBAR_OFFICIAL_DIR}" >&2
        exit 1
    fi
    if [[ "$(git -C "${REBAR_OFFICIAL_DIR}" rev-parse HEAD)" != 463d00f31887e84c38467805b9e3122c314b9521 ]]; then
        echo "Unexpected Rebar revision in ${REBAR_OFFICIAL_DIR}" >&2
        exit 1
    fi
    "${REGULATOR_DIR}/tools/re2-benchmark/manifests/validate-extended-rebar.sh" "${REBAR_OFFICIAL_DIR}"
    rebar_commit=$(git -C "${REBAR_OFFICIAL_DIR}" rev-parse HEAD)
    package_rebar_commit \
        "${REBAR_OFFICIAL_DIR}" \
        "${rebar_commit}" \
        "${SESSION_DIR}/rebar-official.tar.gz"
    REBAR_OFFICIAL_SHA256=$(sha256 "${SESSION_DIR}/rebar-official.tar.gz")
fi

REGULATOR_SHA256=$(sha256 "${SESSION_DIR}/regulator.tar.gz")
REGULATOR_CONTENT_SHA256=$(archive_content_sha256 "${SESSION_DIR}/regulator.tar.gz")
TRINO_SHA256=none
if [[ "${CAMPAIGN_USES_TRINO}" == true ]]; then
    TRINO_SHA256=$(sha256 "${SESSION_DIR}/trino.tar.gz")
fi
ACCOUNT_ID=$(aws_cli sts get-caller-identity --query Account --output text)
LOWER_SESSION_ID=$(printf '%s' "${SESSION_ID}" | tr '[:upper:]' '[:lower:]')
if [[ -n "${TRANSFER_BUCKET}" ]]; then
    BUCKET=${TRANSFER_BUCKET}
    OBJECT_PREFIX="${TRANSFER_PREFIX}/${LOWER_SESSION_ID}"
    INPUT_PREFIX="${OBJECT_PREFIX}/input"
    RESULT_PREFIX="${OBJECT_PREFIX}/results"
    aws_cli s3api head-bucket --bucket "${BUCKET}" >/dev/null
    if [[ $(aws_cli s3api get-bucket-tagging \
            --bucket "${BUCKET}" \
            --query 'length(TagSet[?Key==`Project` && Value==`regulator-benchmark`])' \
            --output text) != 1 ]]; then
        echo "Shared transfer bucket is missing Project=regulator-benchmark ownership tag: ${BUCKET}" >&2
        exit 1
    fi
else
    BUCKET="re2-${CAMPAIGN_PROVENANCE}-${ACCOUNT_ID}-${LOWER_SESSION_ID}-$RANDOM"
    BUCKET_OWNED=1
    aws_cli s3api create-bucket \
        --bucket "${BUCKET}" \
        --create-bucket-configuration "LocationConstraint=${AWS_REGION}" >/dev/null
    aws_cli s3api put-public-access-block \
        --bucket "${BUCKET}" \
        --public-access-block-configuration \
            BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
    aws_cli s3api put-bucket-encryption \
        --bucket "${BUCKET}" \
        --server-side-encryption-configuration \
            'Rules=[{ApplyServerSideEncryptionByDefault={SSEAlgorithm=AES256},BucketKeyEnabled=true}]'
    aws_cli s3api put-bucket-lifecycle-configuration \
        --bucket "${BUCKET}" \
        --lifecycle-configuration \
            '{"Rules":[{"ID":"ExpireCampaignArtifacts","Status":"Enabled","Filter":{"Prefix":""},"Expiration":{"Days":1}}]}'
fi

aws_cli s3 cp "${SESSION_DIR}/regulator.tar.gz" "s3://${BUCKET}/${INPUT_PREFIX}/regulator.tar.gz" --only-show-errors
if [[ "${CAMPAIGN_MODE}" == language-batch ]]; then
    aws_cli s3 cp "${SESSION_DIR}/language-batch.tar.gz" "s3://${BUCKET}/${INPUT_PREFIX}/language-batch.tar.gz" --only-show-errors
    aws_cli s3 cp "${SESSION_DIR}/candidate-provenance.tsv" "s3://${BUCKET}/${INPUT_PREFIX}/candidate-provenance.tsv" --only-show-errors
fi
if [[ "${CAMPAIGN_USES_TRINO}" == true ]]; then
    aws_cli s3 cp "${SESSION_DIR}/trino.tar.gz" "s3://${BUCKET}/${INPUT_PREFIX}/trino.tar.gz" --only-show-errors
fi
if [[ -f "${SESSION_DIR}/rebar-official.tar.gz" ]]; then
    aws_cli s3 cp "${SESSION_DIR}/rebar-official.tar.gz" "s3://${BUCKET}/${INPUT_PREFIX}/rebar-official.tar.gz" --only-show-errors
fi

create_instance_profile

VPC_ID=$(aws_cli ec2 describe-vpcs \
    --filters Name=is-default,Values=true \
    --query 'Vpcs[0].VpcId' \
    --output text)
SECURITY_GROUP_ID=$(aws_cli ec2 describe-security-groups \
    --filters "Name=vpc-id,Values=${VPC_ID}" Name=group-name,Values=default \
    --query 'SecurityGroups[0].GroupId' \
    --output text)

result_prefix="${RESULT_PREFIX}/${CAMPAIGN_PLATFORM}/${CAMPAIGN_SHARD_ID}/replica-${CAMPAIGN_REPLICA_ID}/epoch-${CAMPAIGN_HOST_EPOCH}"
INTEL_RESULT_KEY="${result_prefix}/intel.tar.gz"
ARM_RESULT_KEY="${result_prefix}/arm.tar.gz"
INTEL_INSTANCE_ID=none
ARM_INSTANCE_ID=none
pending=()
if includes_architecture intel; then
    LAUNCH_ATTEMPTED=1
    INTEL_INSTANCE_ID=$(launch_instance \
        intel x86_64 "${INTEL_INSTANCE_TYPE}" \
        /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
        "${INTEL_RESULT_KEY}")
    INSTANCE_IDS+=("${INTEL_INSTANCE_ID}")
    pending+=(intel)
fi
if includes_architecture arm; then
    LAUNCH_ATTEMPTED=1
    ARM_INSTANCE_ID=$(launch_instance \
        arm aarch64 "${ARM_INSTANCE_TYPE}" \
        /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
        "${ARM_RESULT_KEY}")
    INSTANCE_IDS+=("${ARM_INSTANCE_ID}")
    pending+=(arm)
fi

cat > "${SESSION_DIR}/session.txt" <<EOF
session=${SESSION_ID}
baseline_campaign=${CAMPAIGN_ID}
platform=${CAMPAIGN_PLATFORM}
shard=${CAMPAIGN_SHARD_ID}
replica=${CAMPAIGN_REPLICA_ID}
host_epoch=${CAMPAIGN_HOST_EPOCH}
attempt=${CAMPAIGN_ATTEMPT}
campaign_provenance=${CAMPAIGN_PROVENANCE}
source_snapshot_kind=${SOURCE_SNAPSHOT_KIND}
aws_profile=${AWS_PROFILE:-default-credential-chain}
aws_region=${AWS_REGION}
bucket=${BUCKET}
bucket_owned=${BUCKET_OWNED}
object_prefix=${OBJECT_PREFIX}
regulator_commit=${REGULATOR_COMMIT}
regulator_snapshot_sha256=${REGULATOR_SHA256}
regulator_content_sha256=${REGULATOR_CONTENT_SHA256}
baseline_candidate_archive_sha256=${BASELINE_CANDIDATE_ARCHIVE_SHA256:-not-used}
baseline_protocol=${BASELINE_PROTOCOL}
baseline_expected_engine_tree=${BASELINE_EXPECTED_ENGINE_TREE}
baseline_selected_route=${BASELINE_SELECTED_ROUTE:-all}
baseline_joni_jmh_time=${BASELINE_JONI_JMH_TIME:-default}
baseline_protocol_qualification=${BASELINE_PROTOCOL_QUALIFICATION}
trino_commit=${TRINO_COMMIT}
trino_snapshot_sha256=${TRINO_SHA256}
rebar_official_sha256=${REBAR_OFFICIAL_SHA256:-none}
rebar_workload_definitions_sha256=${REBAR_WORKLOAD_DEFINITIONS_SHA256}
rebar_selected_definitions_sha256=${REBAR_SELECTED_DEFINITIONS_SHA256}
joni_evidence_scope=${JONI_EVIDENCE_SCOPE}
campaign_mode=${CAMPAIGN_MODE}
campaign_architectures=${CAMPAIGN_ARCHITECTURES}
instance_market_type=${INSTANCE_MARKET_TYPE}
rebar_comparator_order=${REBAR_COMPARATOR_ORDER}
joni_comparator_order=${JONI_COMPARATOR_ORDER}
benchmark_intel_java_archive_url=${RESOLVED_INTEL_JAVA_ARCHIVE_URL:-default-temurin-25}
benchmark_intel_java_archive_sha256=${RESOLVED_INTEL_JAVA_ARCHIVE_SHA256:-none}
benchmark_arm_java_archive_url=${RESOLVED_ARM_JAVA_ARCHIVE_URL:-default-temurin-25}
benchmark_arm_java_archive_sha256=${RESOLVED_ARM_JAVA_ARCHIVE_SHA256:-none}
benchmark_intel_ami_id=${RESOLVED_INTEL_AMI_ID:-latest}
benchmark_arm_ami_id=${RESOLVED_ARM_AMI_ID:-latest}
benchmark_heap_size=${BENCHMARK_HEAP_SIZE:-8g}
intel_instance_type=${INTEL_INSTANCE_TYPE}
intel_instance_id=${INTEL_INSTANCE_ID}
arm_instance_type=${ARM_INSTANCE_TYPE}
arm_instance_id=${ARM_INSTANCE_ID}
EOF

echo "Started ${CAMPAIGN_ARCHITECTURES}: Intel ${INTEL_INSTANCE_ID}, Arm ${ARM_INSTANCE_ID}"
echo "Artifacts: ${SESSION_DIR}"

deadline=$((SECONDS + TIMEOUT_SECONDS))
campaign_failed=0
while [[ ${#pending[@]} -gt 0 && ${SECONDS} -lt ${deadline} ]]; do
    next_pending=()
    for label in "${pending[@]}"; do
        if [[ "${label}" == intel ]]; then
            key=${INTEL_RESULT_KEY}
            instance_id=${INTEL_INSTANCE_ID}
        else
            key=${ARM_RESULT_KEY}
            instance_id=${ARM_INSTANCE_ID}
        fi

        if aws_cli s3api head-object --bucket "${BUCKET}" --key "${key}" >/dev/null 2>&1; then
            aws_cli s3 cp "s3://${BUCKET}/${key}" "${SESSION_DIR}/${label}.tar.gz" --only-show-errors
            mkdir -p "${SESSION_DIR}/${label}"
            tar -xzf "${SESSION_DIR}/${label}.tar.gz" -C "${SESSION_DIR}/${label}"
            DOWNLOADED_RESULT_COUNT=$((DOWNLOADED_RESULT_COUNT + 1))
            result_status=$(cat "${SESSION_DIR}/${label}/re2-results/exit-status")
            if [[ "${result_status}" != 0 ]]; then
                echo "${label} run failed with status ${result_status}" >&2
                if [[ "${result_status}" == 42 && "${CAMPAIGN_MODE}" == baseline-shard ]]; then
                    record_failure_classification protocol-qualification "remote-exit-status:${result_status}"
                else
                    record_failure_classification benchmark-failure "remote-exit-status:${result_status}"
                fi
                campaign_failed=1
                continue
            fi
            if [[ "${CAMPAIGN_PROVENANCE}" == qualification ]]; then
                environment_manifest="${SESSION_DIR}/${label}/re2-results/environment-manifest.txt"
                expected_ami=$(benchmark_ami_id "${label}")
                expected_java_sha256=$(jdk_archive_sha256 "${label}")
                if [[ "${label}" == intel ]]; then
                    expected_instance_type=${INTEL_INSTANCE_TYPE}
                else
                    expected_instance_type=${ARM_INSTANCE_TYPE}
                fi
                expected_cargo_package=not-installed
                expected_rust_package=not-installed
                if [[ "${CAMPAIGN_SHARD_ID}" == rebar-* ]]; then
                    expected_cargo_package=${CARGO_PACKAGE}
                    expected_rust_package=${RUST_PACKAGE}
                fi
                if [[ ! -f "${environment_manifest}" ]] ||
                        ! grep -Fqx 'verification_status=verified' "${environment_manifest}" ||
                        ! grep -Fqx "baseline_campaign=${CAMPAIGN_ID}" "${environment_manifest}" ||
                        ! grep -Fqx "platform=${CAMPAIGN_PLATFORM}" "${environment_manifest}" ||
                        ! grep -Fqx "shard=${CAMPAIGN_SHARD_ID}" "${environment_manifest}" ||
                        ! grep -Fqx "replica=${CAMPAIGN_REPLICA_ID}" "${environment_manifest}" ||
                        ! grep -Fqx "host_epoch=${CAMPAIGN_HOST_EPOCH}" "${environment_manifest}" ||
                        ! grep -Fqx "baseline_protocol=${BASELINE_PROTOCOL}" "${environment_manifest}" ||
                        ! grep -Fqx "native_compiler_package=${NATIVE_COMPILER_PACKAGE}" "${environment_manifest}" ||
                        ! grep -Fqx "cmake_package=${CMAKE_PACKAGE}" "${environment_manifest}" ||
                        ! grep -Fqx "glibc_package=${GLIBC_PACKAGE}" "${environment_manifest}" ||
                        ! grep -Fqx "cargo_package=${expected_cargo_package}" "${environment_manifest}" ||
                        ! grep -Fqx "rust_package=${expected_rust_package}" "${environment_manifest}" ||
                        ! grep -Fqx "campaign_architecture=${label}" "${environment_manifest}" ||
                        ! grep -Fqx "regulator_commit=${REGULATOR_COMMIT}" "${environment_manifest}" ||
                        ! grep -Fqx "regulator_archive_sha256=${REGULATOR_SHA256}" "${environment_manifest}" ||
                        ! grep -Fqx "expected_ami_id=${expected_ami}" "${environment_manifest}" ||
                        ! grep -Fqx "actual_ami_id=${expected_ami}" "${environment_manifest}" ||
                        ! grep -Fqx "instance_id=${instance_id}" "${environment_manifest}" ||
                        ! grep -Fqx "instance_type=${expected_instance_type}" "${environment_manifest}" ||
                        ! grep -Fqx "jdk_archive_sha256=${expected_java_sha256}" "${environment_manifest}" ||
                        ! grep -Fqx "trino_used=${CAMPAIGN_USES_TRINO}" "${environment_manifest}" ||
                        ! grep -Fqx "rebar_workload_definitions_sha256=${REBAR_WORKLOAD_DEFINITIONS_SHA256}" "${environment_manifest}" ||
                        ! grep -Fqx "rebar_selected_definitions_sha256=${REBAR_SELECTED_DEFINITIONS_SHA256}" "${environment_manifest}" ||
                        ! grep -Fqx "joni_evidence_scope=${JONI_EVIDENCE_SCOPE}" "${environment_manifest}"; then
                    echo "${label} qualification environment manifest is missing or inconsistent" >&2
                    campaign_failed=1
                    continue
                fi
                if [[ "${CAMPAIGN_MODE}" == baseline-shard ]] &&
                        (! grep -Fqx "row_manifest_sha256=${BASELINE_ROW_MANIFEST_SHA256}" "${environment_manifest}" ||
                        ! grep -Fqx "platform_manifest_sha256=${BASELINE_PLATFORM_MANIFEST_SHA256}" "${environment_manifest}" ||
                        ! grep -Fqx "comparator_manifest_sha256=${BASELINE_COMPARATOR_MANIFEST_SHA256}" "${environment_manifest}" ||
                        ! grep -Fqx "protocol_representatives_sha256=${BASELINE_PROTOCOL_REPRESENTATIVES_SHA256}" "${environment_manifest}" ||
                        ! grep -Fqx "time_package=${TIME_PACKAGE}" "${environment_manifest}"); then
                    echo "${label} baseline manifest identity is inconsistent" >&2
                    campaign_failed=1
                    continue
                fi
                if [[ "${CAMPAIGN_USES_TRINO}" == true ]] &&
                        (! grep -Fqx "trino_commit=${TRINO_COMMIT}" "${environment_manifest}" ||
                        ! grep -Fqx "trino_archive_sha256=${TRINO_SHA256}" "${environment_manifest}"); then
                    echo "${label} qualification Trino identity is inconsistent" >&2
                    campaign_failed=1
                    continue
                fi
            fi
            echo "Downloaded ${label} results"
            continue
        fi

        if ! state=$(instance_state "${instance_id}"); then
            echo "Unable to query AWS while waiting for ${label}; results can be recovered from s3://${BUCKET}/${key}" >&2
            exit 1
        fi
        if [[ "${state}" == terminated || "${state}" == shutting-down ]]; then
            spot_status=$(spot_request_status "${instance_id}" || true)
            aws_cli ec2 get-console-output \
                --instance-id "${instance_id}" \
                --latest \
                --query Output \
                --output text > "${SESSION_DIR}/${label}-console.log" 2>&1 || true
            echo "${label} terminated without uploading results" >&2
            if [[ "${INSTANCE_MARKET_TYPE}" == spot ]] && is_retryable_spot_status "${spot_status}"; then
                record_failure_classification retryable-spot "${spot_status}"
            else
                record_failure_classification \
                    retryable-infrastructure \
                    "instance-terminated-without-results:state=${state};spot_status=${spot_status:-none}"
            fi
            campaign_failed=1
            continue
        fi
        next_pending+=("${label}")
    done
    pending=()
    if [[ ${#next_pending[@]} -gt 0 ]]; then
        pending=("${next_pending[@]}")
    fi
    if [[ ${#pending[@]} -gt 0 ]]; then
        sleep 60
    fi
done

if [[ ${#pending[@]} -gt 0 ]]; then
    echo "Timed out waiting for: ${pending[*]}" >&2
    exit 1
fi

if [[ ${campaign_failed} -ne 0 ]]; then
    echo "One or more ${CAMPAIGN_PROVENANCE} runs failed; inspect ${SESSION_DIR}" >&2
    exit 1
fi

echo "${CAMPAIGN_LABEL} benchmark execution complete; AWS cleanup pending: ${SESSION_DIR}"
