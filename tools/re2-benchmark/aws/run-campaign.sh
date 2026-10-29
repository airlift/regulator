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
ARM_INSTANCE_TYPE=${ARM_INSTANCE_TYPE:-r9g.large}
INSTANCE_MARKET_TYPE=${INSTANCE_MARKET_TYPE:-on-demand}
CAMPAIGN_SPOT_ONLY=${CAMPAIGN_SPOT_ONLY:-0}
CAMPAIGN_SPOT_MAX_PRICE=${CAMPAIGN_SPOT_MAX_PRICE:-}
if [[ -n "${CAMPAIGN_SPOT_MAX_PRICE}" && ! "${CAMPAIGN_SPOT_MAX_PRICE}" =~ ^[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid Spot price ceiling" >&2
    exit 1
fi
CAMPAIGN_PLACEMENT_SCORE=${CAMPAIGN_PLACEMENT_SCORE:-}
if [[ -n "${CAMPAIGN_PLACEMENT_SCORE}" && ! "${CAMPAIGN_PLACEMENT_SCORE}" =~ ^([1-9]|10)$ ]]; then
    echo "Invalid Spot placement score" >&2
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
# The controller always passes a unit worker its wrapper timeout.
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-}
CAMPAIGN_PROVENANCE=${CAMPAIGN_PROVENANCE:-qualification}
RESULT_ROOT=${RESULT_ROOT:-"${REGULATOR_DIR}/benchmark-results/re2-qualification"}
CAMPAIGN_MODE=${CAMPAIGN_MODE:-}
CAMPAIGN_ARCHITECTURES=${CAMPAIGN_ARCHITECTURES:-intel}
CAMPAIGN_ID=${CAMPAIGN_ID:-baseline}
CAMPAIGN_PLATFORM=${CAMPAIGN_PLATFORM:-r8i}
CAMPAIGN_SHARD_ID=${CAMPAIGN_SHARD_ID:-}
CAMPAIGN_REPLICA_ID=${CAMPAIGN_REPLICA_ID:-1}
CAMPAIGN_HOST_EPOCH=${CAMPAIGN_HOST_EPOCH:-1}
CAMPAIGN_ATTEMPT=${CAMPAIGN_ATTEMPT:-1}
BASELINE_PROTOCOL=${BASELINE_PROTOCOL:-qualification}
BASELINE_PROTOCOL_QUALIFICATION=${BASELINE_PROTOCOL_QUALIFICATION:-true}
BASELINE_CANDIDATE_ARCHIVE=${BASELINE_CANDIDATE_ARCHIVE:-}
BASELINE_CANDIDATE_ARCHIVE_SHA256=${BASELINE_CANDIDATE_ARCHIVE_SHA256:-}
BASELINE_EXPECTED_ENGINE_TREE=${BASELINE_EXPECTED_ENGINE_TREE:-}
LANGUAGE_CANDIDATE_PROVENANCE=${LANGUAGE_CANDIDATE_PROVENANCE:-}
LANGUAGE_CANDIDATE_PROVENANCE_SHA256=${LANGUAGE_CANDIDATE_PROVENANCE_SHA256:-}
ENGINE_BUILD_DIRECTORY=${ENGINE_BUILD_DIRECTORY:-}
ENGINE_BUILD_MANIFEST_SHA256=${ENGINE_BUILD_MANIFEST_SHA256:-}
# A language unit worker installs each language plan's bundle, named by its SHA-256 in the shared transfer bucket.
LANGUAGE_PLAN_BUNDLE_SHA256S=${LANGUAGE_PLAN_BUNDLE_SHA256S:-}
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
BENCHMARK_INTEL_AMI_NAME=${BENCHMARK_INTEL_AMI_NAME:-}
BENCHMARK_ARM_AMI_NAME=${BENCHMARK_ARM_AMI_NAME:-}
JONI_COMPARATOR_ORDER=${JONI_COMPARATOR_ORDER:-forward}

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

benchmark_ami_name()
{
    case "$1" in
        intel) printf '%s\n' "${BENCHMARK_INTEL_AMI_NAME}" ;;
        arm) printf '%s\n' "${BENCHMARK_ARM_AMI_NAME}" ;;
        *) echo "Unsupported architecture: $1" >&2; exit 1 ;;
    esac
}

if [[ "${CAMPAIGN_MODE}" != unit-worker ]]; then
    echo "RE2 benchmark campaign mode must be unit-worker" >&2
    exit 1
fi
# A unit worker leases units until its lifetime ends.
UNIT_WORKER_LIFETIME_SECONDS=${UNIT_WORKER_LIFETIME_SECONDS:-21600}
if [[ ! "${UNIT_WORKER_LIFETIME_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
    echo "UNIT_WORKER_LIFETIME_SECONDS must be a positive number of seconds" >&2
    exit 1
fi
# run-unit-worker.sh exits with its INTERRUPTED_STATUS after a Spot interruption notice.
UNIT_WORKER_INTERRUPTED_STATUS=43
# The controller adds host setup, final uploads and the pool's last unit, at least 5400 seconds together,
# to the worker's lifetime.
if [[ ! "${TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ || "${TIMEOUT_SECONDS}" -lt $((UNIT_WORKER_LIFETIME_SECONDS + 5400)) ||
        "${CAMPAIGN_SHARD_ID}" != unit-worker ]]; then
    echo "A unit worker requires CAMPAIGN_SHARD_ID=unit-worker and TIMEOUT_SECONDS of at least its lifetime plus 5400," \
        "found ${CAMPAIGN_SHARD_ID} and ${TIMEOUT_SECONDS}" >&2
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
# A unit worker measures units of every shard, or builds the pinned Trino harness.
if [[ -z "${TRINO_DIR}" ]] ||
        ! git -C "${TRINO_DIR}" rev-parse --git-dir >/dev/null 2>&1; then
    echo "TRINO_DIR must name a Trino checkout" >&2
    exit 1
fi
if [[ ! "${TRINO_REVISION}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "TRINO_REVISION must pin a full commit ID" >&2
    exit 1
fi
# Each leased unit's shard decides how it compares Joni.
JONI_EVIDENCE_SCOPE=unit-shard-comparison
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
# A measuring worker installs its platform's prebuilt engines; a build phase's worker has none, and builds them.
if [[ -n "${ENGINE_BUILD_DIRECTORY}${ENGINE_BUILD_MANIFEST_SHA256}" ]] &&
        [[ ! -d "${ENGINE_BUILD_DIRECTORY}" || ! "${ENGINE_BUILD_MANIFEST_SHA256}" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Prebuilt engines require an engine build directory and its manifest SHA-256" >&2
    exit 1
fi
for bundle_sha256 in ${LANGUAGE_PLAN_BUNDLE_SHA256S}; do
    if [[ ! ${bundle_sha256} =~ ^[0-9a-f]{64}$ ]]; then
        echo "LANGUAGE_PLAN_BUNDLE_SHA256S must list SHA-256 digests: ${bundle_sha256}" >&2
        exit 1
    fi
done
if [[ -n "${LANGUAGE_PLAN_BUNDLE_SHA256S}" && ( ! -f "${LANGUAGE_CANDIDATE_PROVENANCE}" ||
        ! "${LANGUAGE_CANDIDATE_PROVENANCE_SHA256}" =~ ^[0-9a-f]{64}$ ) ]]; then
    echo "Language plans require checksummed candidate provenance" >&2
    exit 1
fi
if [[ -n "${ENGINE_BUILD_DIRECTORY}" && -z "${TRANSFER_BUCKET}" ]]; then
    echo "Prebuilt engines require TRANSFER_BUCKET, the shared bucket that holds their archives" >&2
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
    r9g)
        if [[ "${CAMPAIGN_ARCHITECTURES}" != arm || "${ARM_INSTANCE_TYPE}" != r9g.* ]]; then
            echo "Platform r9g requires CAMPAIGN_ARCHITECTURES=arm and a r9g instance" >&2
            exit 1
        fi
        ;;
    *) echo "CAMPAIGN_PLATFORM must be r8i or r9g: ${CAMPAIGN_PLATFORM}" >&2; exit 1 ;;
esac
case "${INSTANCE_MARKET_TYPE}" in
    on-demand | spot) ;;
    *) echo "INSTANCE_MARKET_TYPE must be on-demand or spot: ${INSTANCE_MARKET_TYPE}" >&2; exit 1 ;;
esac
case "${BASELINE_PROTOCOL}" in
    smoke | qualification) ;;
    *) echo "BASELINE_PROTOCOL must be smoke or qualification: ${BASELINE_PROTOCOL}" >&2; exit 1 ;;
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
if [[ ! "$(benchmark_ami_name "${benchmark_architecture}")" =~ ^[A-Za-z0-9._/@()-]*$ ]]; then
    echo "Invalid ${benchmark_architecture} AMI name" >&2
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
# A launch is attempted once RunInstances is submitted.
LAUNCH_ATTEMPTED=0
# Once any RunInstances attempt of the launch failed without AWS proving it rejected the request, such as
# a timeout, an instance may exist that discovery does not see yet, whatever the later attempts returned.
LAUNCH_AMBIGUOUS=0
LAUNCHED_INSTANCE_ID=
INTEL_AVAILABILITY_ZONE=none
ARM_AVAILABILITY_ZONE=none
DOWNLOADED_RESULT_COUNT=0
FAILURE_CLASSIFICATION=none
FAILURE_DETAIL=none
BUCKET=
BUCKET_OWNED=0
OBJECT_PREFIX=
INPUT_PREFIX=input
RESULT_PREFIX=results
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

# A frozen input that fails its check before launch fails it again on any retry, so the session is
# refused for good rather than left to cleanup's retryable pre-launch classification.
reject_input()
{
    local message=$1

    echo "${message}" >&2
    record_failure_classification input-rejection "${message}" || true
    exit 1
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
    # SpotMaxPriceTooLow rejects the request outright: the region's Spot price is above the ceiling.
    if [[ "${market}" == spot ]] &&
            grep -Eq 'InsufficientInstanceCapacity|UnfulfillableCapacity|MaxSpotInstanceCountExceeded|SpotMaxPriceTooLow' \
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

# Whether AWS rejected the RunInstances request outright, so it launched nothing: the errors
# classify_launch_error classifies.
launch_error_is_rejection()
{
    (record_failure_classification() { :; }; classify_launch_error "$@")
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
            { [[ ${LAUNCH_AMBIGUOUS} -ne 0 ]] || ! is_retryable_failure_classification "${FAILURE_CLASSIFICATION}"; }; then
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
    if [[ ${status} -ne 0 && ${LAUNCH_ATTEMPTED} -eq 0 &&
            ( -z "${FAILURE_CLASSIFICATION}" || "${FAILURE_CLASSIFICATION}" == none ) ]]; then
        # Setup or the launch failed before any RunInstances request, so no instance can exist.
        FAILURE_CLASSIFICATION=none
        record_failure_classification retryable-infrastructure "launch-preflight:failed before RunInstances" || true
    fi

    if [[ ${LAUNCH_ATTEMPTED} -ne 0 ]] && ! discover_campaign_instances; then
        echo "Cleanup refused: unable to discover all instances for campaign ${SESSION_ID}" >&2
        cleanup_failed=1
        preserve_resources=1
    elif [[ ${LAUNCH_ATTEMPTED} -ne 0 && ${#INSTANCE_IDS[@]} -eq 0 && ${LAUNCH_AMBIGUOUS} -eq 0 ]] &&
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
            printf 'aws_region=%s\n' "${AWS_REGION}"
            printf 'intel_availability_zone=%s\n' "${INTEL_AVAILABILITY_ZONE}"
            printf 'arm_availability_zone=%s\n' "${ARM_AVAILABILITY_ZONE}"
            printf 'placement_score=%s\n' "${CAMPAIGN_PLACEMENT_SCORE}"
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

candidate_commit()
{
    local reference
    # Git archives identify their commit in the global PAX header. Resolve that
    # immutable object so another local commit cannot change a frozen campaign.
    reference=$(python3 - "${BASELINE_CANDIDATE_ARCHIVE}" <<'PYTHON'
import sys
import tarfile
with tarfile.open(sys.argv[1], "r:gz") as archive:
    print(archive.pax_headers.get("comment", ""))
PYTHON
    ) || return 1
    if [[ ! "${reference}" =~ ^[0-9a-f]{40}$ ]]; then
        echo "Frozen candidate archive must identify a full Git commit" >&2
        return 1
    fi
    git -C "${REGULATOR_DIR}" rev-parse --verify "${reference}^{commit}"
}

require_clean_worktree()
{
    local worktree=$1
    local label=$2

    if [[ -n $(git -C "${worktree}" status --porcelain) ]]; then
        reject_input "Qualification requires a clean ${label} worktree: ${worktree}"
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

verify_shared_archive()
{
    local checksum=$1
    local key=$2
    local encoded
    local existing
    encoded=$(python3 -c 'import base64, sys; print(base64.b64encode(bytes.fromhex(sys.argv[1])).decode())' "${checksum}")
    existing=$(aws_cli s3api head-object --bucket "${BUCKET}" --key "${key}" --checksum-mode ENABLED \
        --query ChecksumSHA256 --output text 2>/dev/null || true)
    if [[ "${existing}" != "${encoded}" ]]; then
        # The controller uploads each archive before it launches any host in this region.
        echo "Shared archive in the transfer bucket is missing, unreadable, or fails its checksum:" \
            "s3://${BUCKET}/${key}" >&2
        record_failure_classification retryable-infrastructure "shared-archive-unavailable:s3://${BUCKET}/${key}"
        return 1
    fi
}

create_instance_profile()
{
    INSTANCE_ROLE_NAME="re2-benchmark-${LOWER_SESSION_ID}"
    INSTANCE_PROFILE_NAME="${INSTANCE_ROLE_NAME}"

    cat > "${SESSION_DIR}/instance-trust-policy.json" <<'EOF'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
EOF
    local shared_archive_statement=
    if [[ -n "${SHARED_ARCHIVE_RESOURCES}" ]]; then
        # Read only the exact shared archives this session's host installs.
        shared_archive_statement=",
  {\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":[${SHARED_ARCHIVE_RESOURCES}]}"
    fi
    # A unit worker lists only its own lease keys, to tell a lease not yet
    # written from one it cannot read.
    cat > "${SESSION_DIR}/instance-transfer-policy.json" <<EOF
{"Version":"2012-10-17","Statement":[
  {"Effect":"Allow","Action":"s3:GetObject","Resource":"arn:aws:s3:::${BUCKET}/${INPUT_PREFIX}/*"},
  {"Effect":"Allow","Action":"s3:PutObject","Resource":"arn:aws:s3:::${BUCKET}/${RESULT_PREFIX}/*"}${shared_archive_statement},
  {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::${BUCKET}","Condition":{"StringLike":{"s3:prefix":"${INPUT_PREFIX}/leases/*"}}}
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

subnet_rotation()
{
    # Prints the public subnets that offer the type, one subnet and zone per line, in this
    # attempt's rotation order.
    local instance_type=$1
    local available_zones
    local offset
    local replica_index
    local placement_seed
    local subnet_count
    local subnet_zones
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
        --query 'sort_by(Subnets[?MapPublicIpOnLaunch], &AvailabilityZone)[].[SubnetId,AvailabilityZone]' \
        --output text)
    read -r -a subnet_zones <<<"$(tr '\n\t' '  ' <<<"${subnets}")"
    subnet_count=$((${#subnet_zones[@]} / 2))
    # Keep the initial placement stable for this job, then visit a different
    # pool on each retry regardless of other jobs' allocated host epochs.
    placement_seed=$(printf '%s' "${CAMPAIGN_PLATFORM}/${CAMPAIGN_SHARD_ID}/replica-${CAMPAIGN_REPLICA_ID}" | cksum | awk '{print $1}')
    for ((offset = 0; offset < subnet_count; offset++)); do
        replica_index=$(((placement_seed + CAMPAIGN_ATTEMPT - 1 + offset) % subnet_count))
        printf '%s %s\n' "${subnet_zones[$((2 * replica_index))]}" "${subnet_zones[$((2 * replica_index + 1))]}"
    done
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
    local expected_ami_name=${8:-}
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
if [[ '${LANGUAGE_PLAN_BUNDLE_SHA256S}' != '' ]]; then
    download_input 's3://${BUCKET}/${INPUT_PREFIX}/candidate-provenance.tsv' /tmp/candidate-provenance.tsv
    printf '%s  %s\n' '${LANGUAGE_CANDIDATE_PROVENANCE_SHA256}' /tmp/candidate-provenance.tsv | sha256sum --check -
    export LANGUAGE_SOURCE_ARCHIVE=/tmp/regulator.tar.gz
    export LANGUAGE_CANDIDATE_PROVENANCE=/tmp/candidate-provenance.tsv
fi
mkdir -p /opt/re2-work/trino
download_input 's3://${BUCKET}/${INPUT_PREFIX}/trino.tar.gz' /tmp/trino.tar.gz
printf '%s  %s\n' '${TRINO_SHA256}' /tmp/trino.tar.gz | sha256sum --check -
tar -xzf /tmp/trino.tar.gz -C /opt/re2-work/trino
export LANGUAGE_PLAN_INPUT_DIR=/opt/re2-work/language-plans
export ENGINE_BUILD_INPUT_DIR=
export ENGINE_BUILD_MANIFEST_SHA256='${ENGINE_BUILD_MANIFEST_SHA256}'
if [[ '${ENGINE_BUILD_MANIFEST_SHA256}' != '' ]]; then
    ENGINE_BUILD_INPUT_DIR=/opt/re2-work/engine-build
    mkdir -p "\${ENGINE_BUILD_INPUT_DIR}"
    download_input 's3://${BUCKET}/${INPUT_PREFIX}/engine-build.json' "\${ENGINE_BUILD_INPUT_DIR}/engine-build.json"
    printf '%s  %s\n' '${ENGINE_BUILD_MANIFEST_SHA256}' "\${ENGINE_BUILD_INPUT_DIR}/engine-build.json" | sha256sum --check -
fi
while read -r -u 3 shared_file shared_sha256 shared_key; do
    if [[ -n "\${shared_file}" ]]; then
        mkdir -p "/opt/re2-work/\$(dirname "\${shared_file}")"
        download_input "s3://${BUCKET}/\${shared_key}" "/opt/re2-work/\${shared_file}"
        printf '%s  %s\n' "\${shared_sha256}" "/opt/re2-work/\${shared_file}" | sha256sum --check -
    fi
done 3<<'SHARED_ARCHIVES'
${SHARED_ARCHIVES}
SHARED_ARCHIVES

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
export BENCHMARK_PARTIAL_RESULT_URI='s3://${BUCKET}/${RESULT_PREFIX}/partial-${label}.tar.gz'
export BASELINE_PROTOCOL='${BASELINE_PROTOCOL}'
export BASELINE_PROTOCOL_QUALIFICATION='${BASELINE_PROTOCOL_QUALIFICATION}'
export BASELINE_EXPECTED_ENGINE_TREE='${BASELINE_EXPECTED_ENGINE_TREE}'
export NATIVE_COMPILER_PACKAGE='${NATIVE_COMPILER_PACKAGE}'
export CMAKE_PACKAGE='${CMAKE_PACKAGE}'
export GLIBC_PACKAGE='${GLIBC_PACKAGE}'
export CARGO_PACKAGE='${CARGO_PACKAGE}'
export RUST_PACKAGE='${RUST_PACKAGE}'
export TIME_PACKAGE='${TIME_PACKAGE}'
export JONI_EVIDENCE_SCOPE='${JONI_EVIDENCE_SCOPE}'
export EXPECTED_AMI_ID='${expected_ami_id}'
export EXPECTED_AMI_NAME='${expected_ami_name}'
export RE2_ENGINEERING_ARCHITECTURE='${campaign_architecture}'
export RE2_BENCHMARK_MODE='${CAMPAIGN_MODE}'
export INSTANCE_MARKET_TYPE='${INSTANCE_MARKET_TYPE}'
export REGULATOR_RELEASE_VERSION='${REGULATOR_RELEASE_VERSION}'
export BENCHMARK_JAVA_ARCHIVE_URL='${java_archive_url}'
export BENCHMARK_JAVA_ARCHIVE_SHA256='${java_archive_sha256}'
export JONI_COMPARATOR_ORDER='${JONI_COMPARATOR_ORDER}'
export BENCHMARK_HEAP_SIZE='${BENCHMARK_HEAP_SIZE:-8g}'
export BENCHMARK_CPU_LIST='${BENCHMARK_CPU_LIST:-0}'
export BENCHMARK_EXPECTED_VCPUS='${BENCHMARK_EXPECTED_VCPUS:-}'
export RE2_CAMPAIGN_HOST_SESSION='${SESSION_ID}'
export BASELINE_REGION='${AWS_REGION}'
export UNIT_WORKER_BUCKET='${BUCKET}'
export UNIT_WORKER_LEASE_PREFIX='${INPUT_PREFIX}/leases'
export UNIT_WORKER_RESULT_PREFIX='${RESULT_PREFIX}'
export UNIT_WORKER_LIFETIME_SECONDS='${UNIT_WORKER_LIFETIME_SECONDS}'

/opt/re2-work/regulator/tools/re2-benchmark/aws/run-host.sh \
    /opt/re2-work/regulator \
    /opt/re2-work/trino \
    "\${RESULT_DIR}"
EOF
}

record_launch_zone()
{
    case "$1" in
        intel) INTEL_AVAILABILITY_ZONE=$2 ;;
        arm) ARM_AVAILABILITY_ZONE=$2 ;;
        *) echo "Unsupported architecture: $1" >&2; exit 1 ;;
    esac
}

launch_instance()
{
    # Runs in the root shell, which records each zone as it is chosen, so a failed launch still
    # records its zone; sets LAUNCHED_INSTANCE_ID.
    local label=$1
    local architecture=$2
    local instance_type=$3
    local ami_parameter=$4
    local result_key=$5
    local user_data="${SESSION_DIR}/user-data-${label}.sh"
    local ami
    local attempt
    local instance_id
    local launch_error=
    local root_device
    local launch_arguments
    local placements
    local rotation
    local subnet
    local zone
    local zone_offset=0

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
    # One snapshot of the subnets serves every zone this launch tries; a subnet added
    # meanwhile waits for the next launch.
    rotation=$(subnet_rotation "${instance_type}")
    read -r -a placements <<<"${rotation//$'\n'/ }"
    subnet=${placements[0]:-}
    zone=${placements[1]:-}
    if [[ -z "${subnet}" || "${subnet}" == "None" ]]; then
        echo "No public default-VPC subnet offers ${instance_type}" >&2
        record_failure_classification retryable-infrastructure \
            "launch-preflight:no public default-VPC subnet offers ${instance_type}"
        exit 1
    fi
    record_launch_zone "${label}" "${zone}"

    create_user_data \
        "${label}" \
        "${architecture}" \
        "${result_key}" \
        "${user_data}" \
        "${ami}" \
        "${java_url}" \
        "${java_sha256}" \
        "$(benchmark_ami_name "${label}")"

    launch_arguments=(
        --image-id "${ami}" \
        --instance-type "${instance_type}" \
        --count 1 \
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

    LAUNCH_ATTEMPTED=1
    attempt=1
    while ((attempt <= 5)); do
        # Each zone has its own token, since a token names one exact request. The CLI's own
        # retries stay off, so every submission's outcome, a timeout among them, reaches this
        # loop, which resubmits with the same token.
        if instance_id=$(AWS_MAX_ATTEMPTS=1 aws_cli ec2 run-instances \
                "${launch_arguments[@]}" \
                --network-interfaces "DeviceIndex=0,SubnetId=${subnet},Groups=${SECURITY_GROUP_ID},AssociatePublicIpAddress=true" \
                --client-token "re2-${LOWER_SESSION_ID}-${label}-${zone}" \
                --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=re2-${CAMPAIGN_PROVENANCE}-${label}-${SESSION_ID}},{Key=Project,Value=re2-port-benchmark},{Key=Campaign,Value=${SESSION_ID}},{Key=BaselineCampaign,Value=${CAMPAIGN_ID}},{Key=Platform,Value=${CAMPAIGN_PLATFORM}},{Key=Shard,Value=${CAMPAIGN_SHARD_ID}},{Key=Replica,Value=${CAMPAIGN_REPLICA_ID}},{Key=HostEpoch,Value=${CAMPAIGN_HOST_EPOCH}}]" \
                --user-data "file://${user_data}" \
                --query 'Instances[0].InstanceId' \
                --output text 2>"${SESSION_DIR}/launch-${label}.error"); then
            LAUNCHED_INSTANCE_ID=${instance_id}
            return
        fi
        launch_error=$(tr '\n\t' '  ' < "${SESSION_DIR}/launch-${label}.error")
        if ! launch_error_is_rejection "${INSTANCE_MARKET_TYPE}" "${launch_error}"; then
            LAUNCH_AMBIGUOUS=1
        fi
        if [[ "${INSTANCE_MARKET_TYPE}" == spot ]] &&
                grep -Eq 'InsufficientInstanceCapacity|UnfulfillableCapacity' <<<"${launch_error}"; then
            # Spot short in one zone may have room in another, so the launch fails for the
            # region's capacity only once every zone of the region that offers the type has.
            zone_offset=$((zone_offset + 1))
            if ((2 * zone_offset >= ${#placements[@]})); then
                break
            fi
            subnet=${placements[$((2 * zone_offset))]}
            zone=${placements[$((2 * zone_offset + 1))]}
            record_launch_zone "${label}" "${zone}"
            attempt=1
            continue
        fi
        sleep "${attempt}"
        attempt=$((attempt + 1))
    done
    classify_launch_error "${INSTANCE_MARKET_TYPE}" "${launch_error}" || true
    echo "Unable to launch or recover the ${label} instance for campaign ${SESSION_ID}" >&2
    return 1
}

REGULATOR_COMMIT=$(candidate_commit) ||
    reject_input "Frozen candidate archive does not identify a local Regulator commit"
TRINO_COMMIT=$(git -C "${TRINO_DIR}" rev-parse "${TRINO_REVISION}^{commit}") ||
    reject_input "Trino revision is not a commit of the Trino checkout"

require_clean_worktree "${REGULATOR_DIR}" Regulator
if [[ ! -f "${BASELINE_CANDIDATE_ARCHIVE}" ||
        ! "${BASELINE_CANDIDATE_ARCHIVE_SHA256}" =~ ^[0-9a-f]{64}$ ]]; then
    reject_input "Formal baseline execution requires the frozen candidate archive and SHA-256"
fi
cp "${BASELINE_CANDIDATE_ARCHIVE}" "${SESSION_DIR}/regulator.tar.gz"
if [[ $(sha256 "${SESSION_DIR}/regulator.tar.gz") != "${BASELINE_CANDIDATE_ARCHIVE_SHA256}" ]]; then
    reject_input "Frozen candidate archive SHA-256 mismatch"
fi
if [[ $(git -C "${REGULATOR_DIR}" rev-parse "${REGULATOR_COMMIT}:src/main") != "${BASELINE_EXPECTED_ENGINE_TREE}" ]]; then
    reject_input "Selected Regulator production tree does not match BASELINE_EXPECTED_ENGINE_TREE"
fi
if [[ -n "${LANGUAGE_PLAN_BUNDLE_SHA256S}" ]]; then
    cp "${LANGUAGE_CANDIDATE_PROVENANCE}" "${SESSION_DIR}/candidate-provenance.tsv"
    if [[ $(sha256 "${SESSION_DIR}/candidate-provenance.tsv") != "${LANGUAGE_CANDIDATE_PROVENANCE_SHA256}" ]]; then
        reject_input "Language candidate provenance checksum mismatch"
    fi
fi
package_commit "${TRINO_DIR}" "${TRINO_COMMIT}" "${SESSION_DIR}/trino.tar.gz"

# Each content-addressed archive a host installs from the shared transfer bucket, one line each:
# its path under /opt/re2-work on the host, its SHA-256, and its key.
SHARED_ARCHIVES=
if [[ -n "${ENGINE_BUILD_DIRECTORY}" ]]; then
    engine_archives=$(
        PYTHONDONTWRITEBYTECODE=1 python3 "${REGULATOR_DIR}/tools/re2-benchmark/baseline/engine_build.py" checksum \
            --build "${ENGINE_BUILD_DIRECTORY}" --manifest-sha256 "${ENGINE_BUILD_MANIFEST_SHA256}") ||
        reject_input "Engine build archives do not match their manifest"
    while read -r archive_file archive_sha256; do
        SHARED_ARCHIVES+="engine-build/${archive_file} ${archive_sha256} ${TRANSFER_PREFIX}/engine-builds/${archive_sha256}.tar.gz"$'\n'
    done <<<"${engine_archives}"
fi
for bundle_sha256 in ${LANGUAGE_PLAN_BUNDLE_SHA256S}; do
    SHARED_ARCHIVES+="language-plans/${bundle_sha256}.tar.gz ${bundle_sha256} ${TRANSFER_PREFIX}/language-plans/${bundle_sha256}.tar.gz"$'\n'
done

REGULATOR_SHA256=$(sha256 "${SESSION_DIR}/regulator.tar.gz")
REGULATOR_CONTENT_SHA256=$(archive_content_sha256 "${SESSION_DIR}/regulator.tar.gz")
TRINO_SHA256=$(sha256 "${SESSION_DIR}/trino.tar.gz")
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
    # us-east-1 is S3's default location, which S3 rejects as an explicit constraint.
    if [[ "${AWS_REGION}" == us-east-1 ]]; then
        aws_cli s3api create-bucket --bucket "${BUCKET}" >/dev/null
    else
        aws_cli s3api create-bucket \
            --bucket "${BUCKET}" \
            --create-bucket-configuration "LocationConstraint=${AWS_REGION}" >/dev/null
    fi
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
if [[ -n "${LANGUAGE_PLAN_BUNDLE_SHA256S}" ]]; then
    aws_cli s3 cp "${SESSION_DIR}/candidate-provenance.tsv" "s3://${BUCKET}/${INPUT_PREFIX}/candidate-provenance.tsv" --only-show-errors
fi
aws_cli s3 cp "${SESSION_DIR}/trino.tar.gz" "s3://${BUCKET}/${INPUT_PREFIX}/trino.tar.gz" --only-show-errors
if [[ -n "${ENGINE_BUILD_DIRECTORY}" ]]; then
    aws_cli s3 cp "${ENGINE_BUILD_DIRECTORY}/engine-build.json" "s3://${BUCKET}/${INPUT_PREFIX}/engine-build.json" --only-show-errors
fi
# Every session in the bucket's region shares one copy of each content-addressed
# archive; it is a campaign input, not a session resource, so cleanup keeps it.
SHARED_ARCHIVE_RESOURCES=
while read -r -u 3 _ archive_sha256 archive_key; do
    if [[ -n "${archive_key:-}" ]]; then
        verify_shared_archive "${archive_sha256}" "${archive_key}"
        SHARED_ARCHIVE_RESOURCES+="${SHARED_ARCHIVE_RESOURCES:+,}\"arn:aws:s3:::${BUCKET}/${archive_key}\""
    fi
done 3<<<"${SHARED_ARCHIVES}"

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
    launch_instance \
        intel x86_64 "${INTEL_INSTANCE_TYPE}" \
        /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
        "${INTEL_RESULT_KEY}"
    INTEL_INSTANCE_ID=${LAUNCHED_INSTANCE_ID}
    INSTANCE_IDS+=("${INTEL_INSTANCE_ID}")
    pending+=(intel)
fi
if includes_architecture arm; then
    launch_instance \
        arm aarch64 "${ARM_INSTANCE_TYPE}" \
        /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
        "${ARM_RESULT_KEY}"
    ARM_INSTANCE_ID=${LAUNCHED_INSTANCE_ID}
    INSTANCE_IDS+=("${ARM_INSTANCE_ID}")
    pending+=(arm)
fi

# The controller reads session.txt while the wrapper runs, so it appears only whole.
cat > "${SESSION_DIR}/session.txt.new" <<EOF
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
placement_score=${CAMPAIGN_PLACEMENT_SCORE}
bucket=${BUCKET}
bucket_owned=${BUCKET_OWNED}
object_prefix=${OBJECT_PREFIX}
input_prefix=${INPUT_PREFIX}
result_prefix=${RESULT_PREFIX}
regulator_commit=${REGULATOR_COMMIT}
regulator_snapshot_sha256=${REGULATOR_SHA256}
regulator_content_sha256=${REGULATOR_CONTENT_SHA256}
baseline_candidate_archive_sha256=${BASELINE_CANDIDATE_ARCHIVE_SHA256:-not-used}
baseline_protocol=${BASELINE_PROTOCOL}
baseline_expected_engine_tree=${BASELINE_EXPECTED_ENGINE_TREE}
baseline_protocol_qualification=${BASELINE_PROTOCOL_QUALIFICATION}
trino_commit=${TRINO_COMMIT}
trino_snapshot_sha256=${TRINO_SHA256}
engine_build_manifest_sha256=${ENGINE_BUILD_MANIFEST_SHA256:-none}
language_plan_bundle_sha256s=${LANGUAGE_PLAN_BUNDLE_SHA256S:-none}
joni_evidence_scope=${JONI_EVIDENCE_SCOPE}
campaign_mode=${CAMPAIGN_MODE}
campaign_architectures=${CAMPAIGN_ARCHITECTURES}
instance_market_type=${INSTANCE_MARKET_TYPE}
joni_comparator_order=${JONI_COMPARATOR_ORDER}
benchmark_intel_java_archive_url=${RESOLVED_INTEL_JAVA_ARCHIVE_URL:-default-temurin-25}
benchmark_intel_java_archive_sha256=${RESOLVED_INTEL_JAVA_ARCHIVE_SHA256:-none}
benchmark_arm_java_archive_url=${RESOLVED_ARM_JAVA_ARCHIVE_URL:-default-temurin-25}
benchmark_arm_java_archive_sha256=${RESOLVED_ARM_JAVA_ARCHIVE_SHA256:-none}
benchmark_intel_ami_id=${RESOLVED_INTEL_AMI_ID:-latest}
benchmark_arm_ami_id=${RESOLVED_ARM_AMI_ID:-latest}
benchmark_intel_ami_name=${BENCHMARK_INTEL_AMI_NAME:-unverified}
benchmark_arm_ami_name=${BENCHMARK_ARM_AMI_NAME:-unverified}
benchmark_heap_size=${BENCHMARK_HEAP_SIZE:-8g}
intel_instance_type=${INTEL_INSTANCE_TYPE}
intel_instance_id=${INTEL_INSTANCE_ID}
intel_availability_zone=${INTEL_AVAILABILITY_ZONE}
arm_instance_type=${ARM_INSTANCE_TYPE}
arm_instance_id=${ARM_INSTANCE_ID}
arm_availability_zone=${ARM_AVAILABILITY_ZONE}
EOF
mv "${SESSION_DIR}/session.txt.new" "${SESSION_DIR}/session.txt"

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
                if [[ "${result_status}" == "${UNIT_WORKER_INTERRUPTED_STATUS}" ]]; then
                    # The worker saw a Spot interruption notice and uploaded what it had.
                    record_failure_classification retryable-spot spot-interruption-notice
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
                # A worker without prebuilt engines builds them, native Rust runners included.
                expected_cargo_package=${CARGO_PACKAGE}
                expected_rust_package=${RUST_PACKAGE}
                if [[ -n "${ENGINE_BUILD_MANIFEST_SHA256}" ]]; then
                    expected_cargo_package=not-installed
                    expected_rust_package=not-installed
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
                        ! grep -Fqx "trino_used=true" "${environment_manifest}" ||
                        ! grep -Fqx "joni_evidence_scope=${JONI_EVIDENCE_SCOPE}" "${environment_manifest}"; then
                    echo "${label} qualification environment manifest is missing or inconsistent" >&2
                    campaign_failed=1
                    continue
                fi
                if ! grep -Fqx "row_manifest_sha256=${BASELINE_ROW_MANIFEST_SHA256}" "${environment_manifest}" ||
                        ! grep -Fqx "platform_manifest_sha256=${BASELINE_PLATFORM_MANIFEST_SHA256}" "${environment_manifest}" ||
                        ! grep -Fqx "comparator_manifest_sha256=${BASELINE_COMPARATOR_MANIFEST_SHA256}" "${environment_manifest}" ||
                        ! grep -Fqx "protocol_representatives_sha256=${BASELINE_PROTOCOL_REPRESENTATIVES_SHA256}" "${environment_manifest}" ||
                        ! grep -Fqx "time_package=${TIME_PACKAGE}" "${environment_manifest}"; then
                    echo "${label} baseline manifest identity is inconsistent" >&2
                    campaign_failed=1
                    continue
                fi
                if [[ -n "${ENGINE_BUILD_MANIFEST_SHA256}" ]] &&
                        ! grep -Fqx "engine_build_manifest_sha256=${ENGINE_BUILD_MANIFEST_SHA256}" \
                            "${SESSION_DIR}/${label}/re2-results/engine-build.txt"; then
                    echo "${label} did not record the engine build it was sent" >&2
                    campaign_failed=1
                    continue
                fi
                if ! grep -Fqx "trino_commit=${TRINO_COMMIT}" "${environment_manifest}" ||
                        ! grep -Fqx "trino_archive_sha256=${TRINO_SHA256}" "${environment_manifest}"; then
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
