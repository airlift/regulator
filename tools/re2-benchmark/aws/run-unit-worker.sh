#!/usr/bin/env bash
set -euo pipefail

# A unit worker measures the work units its controller leases to this host, one
# at a time, until a stop lease, an idle wait, its lifetime, or a Spot notice.
# A lease names the unit's measured groups to run, in order; the worker uploads
# each group's result as soon as the group finishes, so a Spot notice loses only
# the group it interrupts. aws/README.md "Unit workers" specifies the S3 keys,
# schemas, and stop rules.

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <host-evidence-directory> <unit-directory>" >&2
    exit 1
fi

# The host's startup timeline ends with this worker's wait for its first unit.
WORKER_STARTED=${EPOCHREALTIME}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
RUN_UNIT="${SCRIPT_DIR}/../baseline/run-unit.sh"
UNIT_TOOL="${SCRIPT_DIR}/../baseline/unit_session.py"
HOST_DIR=$(mkdir -p "$1" && cd "$1" && pwd)
UNIT_ROOT=$(mkdir -p "$2" && cd "$2" && pwd)
CAMPAIGN_ID=${RE2_CAMPAIGN_ID:?RE2_CAMPAIGN_ID is required}
PLATFORM=${RE2_CAMPAIGN_PLATFORM:?RE2_CAMPAIGN_PLATFORM is required}
HOST_SESSION=${RE2_CAMPAIGN_HOST_SESSION:?RE2_CAMPAIGN_HOST_SESSION is required}
PROTOCOL=${BASELINE_PROTOCOL:?BASELINE_PROTOCOL is required}
REGION=${BASELINE_REGION:?BASELINE_REGION is required}
BUCKET=${UNIT_WORKER_BUCKET:?UNIT_WORKER_BUCKET is required}
LEASE_PREFIX=${UNIT_WORKER_LEASE_PREFIX:?UNIT_WORKER_LEASE_PREFIX is required}
RESULT_PREFIX=${UNIT_WORKER_RESULT_PREFIX:?UNIT_WORKER_RESULT_PREFIX is required}
LIFETIME_SECONDS=${UNIT_WORKER_LIFETIME_SECONDS:-21600}
IDLE_SECONDS=${UNIT_WORKER_IDLE_SECONDS:-600}
POLL_SECONDS=${UNIT_WORKER_POLL_SECONDS:-5}
HEARTBEAT_SECONDS=30
# The CLI gives up on a socket after its connect and read timeouts, but a
# request whose socket stays alive can outlive them, so coreutils timeout ends
# each S3 call after the two together.
AWS_CONNECT_TIMEOUT_SECONDS=5
AWS_READ_TIMEOUT_SECONDS=30
# An upload retries within this many seconds, so a Spot notice that arrives
# meanwhile still leaves time to upload the interruption bundle. An attempt
# starts only while its worst case still ends within them, and no S3 call
# outlives the budget it runs in. A stretched upload's budget grows by its
# stretch and covers at least two whole attempts.
UPLOAD_SECONDS=${UNIT_WORKER_UPLOAD_SECONDS:-90}
UPLOAD_ATTEMPT_SECONDS=$((AWS_CONNECT_TIMEOUT_SECONDS + AWS_READ_TIMEOUT_SECONDS))
UPLOAD_RETRY_SECONDS=5
# A unit result's upload attempts and budget grow by its size at this rate, so
# a large one, such as an engine build's archives, has time to land.
UPLOAD_MINIMUM_RATE=$((4 * 1024 * 1024))
# The worker gives up on this many consecutive failed lease reads.
MAXIMUM_LEASE_FAILURES=12
INTERRUPTED_STATUS=43

if [[ ! ${HOST_SESSION} =~ ^[A-Za-z0-9][A-Za-z0-9._:-]*$ ]]; then
    echo "RE2_CAMPAIGN_HOST_SESSION is not a unit attempt host session: ${HOST_SESSION}" >&2
    exit 1
fi
for value_name in LIFETIME_SECONDS IDLE_SECONDS UPLOAD_SECONDS; do
    if [[ ! ${!value_name} =~ ^[1-9][0-9]*$ ]]; then
        echo "UNIT_WORKER_${value_name} must be a positive number of seconds: ${!value_name}" >&2
        exit 1
    fi
done
if [[ ! ${POLL_SECONDS} =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
    echo "UNIT_WORKER_POLL_SECONDS must be a number of seconds: ${POLL_SECONDS}" >&2
    exit 1
fi

INSTANCE_ID=$(awk -F= '$1 == "instance_id" {print substr($0, length($1) + 2); exit}' \
    "${HOST_DIR}/environment-manifest.txt" 2>/dev/null || true)
LEDGER="${HOST_DIR}/worker-leases.tsv"
printf 'sequence\tunit_id\treplica_id\tgroup_id\toutcome\texit_status\tunit_attempt_id\tenvironment_sha256\n' \
    > "${LEDGER}"
mkdir -p "${HOST_DIR}/leases"

started_at=$(date +%s)
sequence=1
state=waiting
unit_id=
replica_id=
# The lease's groups in run order, the one running, and those whose completion markers landed.
lease_groups=
group_id=
finished_groups=()
# The exit status of the group run_group last ran.
group_status=0
unit_pid=
unit_directory=
next_heartbeat=0
lease_failures=0
imds_token=
imds_token_time=0
# When the current upload budget ends, while one runs.
upload_deadline=
# The attempts the last put_once made.
put_attempts=0
# The last S3 call's output and errors.
S3_OUTPUT="${UNIT_ROOT}/s3-output"
S3_ERRORS="${UNIT_ROOT}/s3-errors"

now()
{
    date +%s
}

timestamp()
{
    date -u +%Y-%m-%dT%H:%M:%SZ
}

aws_cli()
{
    aws_cli_within "${UPLOAD_ATTEMPT_SECONDS}" "$@"
}

aws_cli_within()
{
    # Runs one S3 call within the given seconds, its output in S3_OUTPUT and its
    # errors in S3_ERRORS. Any call may stall until its timeout, so a Spot notice
    # is checked after every one, in the worker's own shell, where an
    # interruption can end the host.
    local seconds=$1
    local status=0
    shift
    if ((BASH_SUBSHELL != 0)); then
        echo "aws $1 $2 must run in the worker's own shell to act on a Spot notice" >&2
        return 1
    fi
    # Ends within its attempt's worst case, and within the upload budget it runs in.
    if [[ -n "${upload_deadline}" ]] && ((upload_deadline - $(now) < seconds)); then
        seconds=$((upload_deadline - $(now)))
    fi
    {
        if ((seconds > 0)); then
            timeout "${seconds}" aws --region "${REGION}" --cli-connect-timeout "${AWS_CONNECT_TIMEOUT_SECONDS}" \
                --cli-read-timeout "${AWS_READ_TIMEOUT_SECONDS}" "$@" || status=$?
        else
            echo "No upload time left for aws $1 $2" >&2
            status=124
        fi
    } >"${S3_OUTPUT}" 2>"${S3_ERRORS}"
    check_notices
    return "${status}"
}

sha256_of()
{
    sha256sum "$1" | awk '{print $1}'
}

put_once()
{
    # One PUT with a whole-object SHA-256 that S3 verifies; a key is never
    # overwritten. An optional stretch, in seconds, lengthens each attempt and
    # the budget, and raises the budget to at least two whole attempts.
    local file=$1
    local key=$2
    local stretch=${3:-0}
    local attempt_seconds=$((UPLOAD_ATTEMPT_SECONDS + stretch))
    local budget=$((UPLOAD_SECONDS + stretch))
    local encoded
    local errors
    local retry_at
    if ((stretch > 0 && budget < 2 * attempt_seconds + UPLOAD_RETRY_SECONDS)); then
        budget=$((2 * attempt_seconds + UPLOAD_RETRY_SECONDS))
    fi
    # The interruption bundle's upload shares the budget that interrupt starts.
    local upload_deadline=${upload_deadline:-$(($(now) + budget))}
    encoded=$(python3 -c 'import base64, sys; print(base64.b64encode(bytes.fromhex(sys.argv[1])).decode())' \
        "$(sha256_of "${file}")")
    put_attempts=0
    while true; do
        if (($(now) + attempt_seconds > upload_deadline)); then
            echo "Upload of s3://${BUCKET}/${key} has no time left for an attempt" >&2
            return 1
        fi
        put_attempts=$((put_attempts + 1))
        # The CLI makes one attempt, so only this loop's earlier attempts can have landed.
        if AWS_MAX_ATTEMPTS=1 aws_cli_within "${attempt_seconds}" s3api put-object --bucket "${BUCKET}" \
                --key "${key}" --body "${file}" --if-none-match '*' --checksum-algorithm SHA256 \
                --checksum-sha256 "${encoded}"; then
            return 0
        fi
        errors=$(<"${S3_ERRORS}")
        if [[ "${errors}" == *PreconditionFailed* ]]; then
            if ((put_attempts > 1)); then
                # An earlier attempt landed before its response was lost.
                return 0
            fi
            echo "Refusing to overwrite s3://${BUCKET}/${key}" >&2
            return 1
        fi
        echo "Upload of s3://${BUCKET}/${key} failed: ${errors}" >&2
        retry_at=$(($(now) + UPLOAD_RETRY_SECONDS))
        if ((retry_at + attempt_seconds > upload_deadline)); then
            return 1
        fi
        while (($(now) < retry_at)); do
            check_notices
            sleep "${POLL_SECONDS}"
        done
    done
}

put_result()
{
    # The same PUT, with each attempt stretched by the archive's size at the
    # minimum rate. A Spot notice during a long attempt is acted on when it
    # ends, and the interruption bundle's upload then has its own attempts.
    put_once "$1" "$2" "$(($(wc -c < "$1") / UPLOAD_MINIMUM_RATE))"
}

heartbeat()
{
    local stop_reason=${1:-}
    local file="${UNIT_ROOT}/heartbeat.json"
    jq -n \
        --arg host_session "${HOST_SESSION}" \
        --arg instance_id "${INSTANCE_ID}" \
        --argjson sequence "${sequence}" \
        --arg state "${state}" \
        --arg unit_id "${unit_id}" \
        --arg replica_id "${replica_id}" \
        --arg timestamp "$(timestamp)" \
        --arg stop_reason "${stop_reason}" \
        '{schema_version: 1, host_session: $host_session, instance_id: $instance_id, sequence: $sequence,
          state: $state, unit_id: (if $unit_id == "" then null else $unit_id end),
          replica_id: (if $replica_id == "" then null else ($replica_id | tonumber) end),
          timestamp: $timestamp, stop_reason: (if $stop_reason == "" then null else $stop_reason end)}' \
        > "${file}"
    if ! AWS_MAX_ATTEMPTS=1 aws_cli s3api put-object --bucket "${BUCKET}" \
            --key "${RESULT_PREFIX}/heartbeat/${sequence}.json" --body "${file}"; then
        echo "Heartbeat upload failed for lease ${sequence}: $(<"${S3_ERRORS}")" >&2
    fi
    next_heartbeat=$(($(now) + HEARTBEAT_SECONDS))
}

heartbeat_if_due()
{
    if (($(now) >= next_heartbeat)); then
        heartbeat
    fi
}

imds_get()
{
    local path=$1
    if [[ -z "${imds_token}" ]] || (($(now) - imds_token_time > 3600)); then
        imds_token=$(curl --silent --fail --max-time 2 --request PUT \
            --header 'X-aws-ec2-metadata-token-ttl-seconds: 21600' \
            http://169.254.169.254/latest/api/token || true)
        imds_token_time=$(now)
    fi
    curl --silent --fail --max-time 2 --header "X-aws-ec2-metadata-token: ${imds_token}" \
        "http://169.254.169.254/latest/meta-data/${path}"
}

check_notices()
{
    # An instance-action notice ends the host. A rebalance recommendation is
    # only a hint, so the worker keeps leasing until the notice comes. The
    # interruption's own uploads have no later notice to wait for.
    local notice
    if [[ "${state}" == interrupted ]]; then
        return 0
    fi
    if notice=$(imds_get spot/instance-action 2>/dev/null) && [[ -n "${notice}" ]]; then
        interrupt "${notice}"
    fi
}

kill_unit()
{
    local _
    if [[ -z "${unit_pid}" ]]; then
        return
    fi
    kill -TERM -- "-${unit_pid}" 2>/dev/null || kill -TERM "${unit_pid}" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        kill -0 "${unit_pid}" 2>/dev/null || break
        sleep 0.5
    done
    kill -KILL -- "-${unit_pid}" 2>/dev/null || true
    wait "${unit_pid}" 2>/dev/null || true
    unit_pid=
}

write_session()
{
    local stop_reason=$1
    {
        printf 'host_session=%s\n' "${HOST_SESSION}"
        printf 'stop_reason=%s\n' "${stop_reason}"
        printf 'last_sequence=%s\n' "${sequence}"
        printf 'stopped_at=%s\n' "$(timestamp)"
    } > "${HOST_DIR}/worker-session.txt"
    if [[ -n "${BASELINE_SHARED_WORK_DIR:-}" && -d "${BASELINE_SHARED_WORK_DIR}/semantic-receipts" ]]; then
        rm -rf "${HOST_DIR}/semantic-receipts"
        cp -a "${BASELINE_SHARED_WORK_DIR}/semantic-receipts" "${HOST_DIR}/semantic-receipts"
    fi
}

interrupt()
{
    local notice=$1
    local action
    local action_time
    local bundle="${UNIT_ROOT}/interrupted-${sequence}"
    local archive="${UNIT_ROOT}/interrupted-${sequence}.tar.gz"
    local contents=()
    local finished
    action=$(jq -r '.action // "unknown"' <<<"${notice}" 2>/dev/null || printf 'unknown')
    action_time=$(jq -r '.time // ""' <<<"${notice}" 2>/dev/null || true)
    echo "Spot instance action ${action} at ${action_time:-unknown}; interrupting lease ${sequence}" >&2
    kill_unit
    state=interrupted
    mkdir -p "${bundle}"
    finished=$(IFS=,; printf '%s' "${finished_groups[*]}")
    jq -n \
        --arg host_session "${HOST_SESSION}" \
        --argjson sequence "${sequence}" \
        --arg unit_id "${unit_id}" \
        --arg replica_id "${replica_id}" \
        --arg finished_groups "${finished}" \
        --arg running_group "${group_id}" \
        --arg notice_time "$(timestamp)" \
        --arg action "${action}" \
        --arg action_time "${action_time}" \
        '{schema_version: 2, host_session: $host_session, sequence: $sequence,
          unit_id: (if $unit_id == "" then null else $unit_id end),
          replica_id: (if $replica_id == "" then null else ($replica_id | tonumber) end),
          finished_groups: (if $finished_groups == "" then [] else ($finished_groups | split(",")) end),
          running_group: (if $running_group == "" then null else $running_group end),
          notice_time: $notice_time, action: $action, action_time: $action_time}' \
        > "${bundle}/interruption.json"
    cp "${bundle}/interruption.json" "${HOST_DIR}/worker-interruption.json"
    contents=(-C "${bundle}" interruption.json)
    # Finished groups are already uploaded; the bundle carries only the running group's log.
    if [[ -n "${unit_directory}" && -f "${unit_directory}/worker-unit.log" ]]; then
        contents+=(-C "${UNIT_ROOT}" "lease-${sequence}/group-${group_id}/worker-unit.log")
    fi
    # The bundle and the final heartbeat share one upload budget.
    local upload_deadline=$(($(now) + UPLOAD_SECONDS))
    if tar -czf "${archive}" "${contents[@]}"; then
        put_once "${archive}" "${RESULT_PREFIX}/events/${sequence}-interrupted.tar.gz" || true
    fi
    heartbeat interrupted
    write_session interrupted
    exit "${INTERRUPTED_STATUS}"
}

stop()
{
    # A Spot notice before or during the final heartbeat makes the stop an interruption.
    local reason=$1
    local status=${2:-0}
    check_notices
    echo "Unit worker stopping: ${reason}"
    state=stopped
    heartbeat "${reason}"
    write_session "${reason}"
    exit "${status}"
}

pause()
{
    # Sleep one poll interval, watching for Spot notices and keeping the heartbeat.
    check_notices
    heartbeat_if_due
    sleep "${POLL_SECONDS}"
}

stop_if_winding_down()
{
    # No lease starts once the worker's lifetime is spent.
    if (($(now) - started_at >= LIFETIME_SECONDS)); then
        stop lifetime
    fi
}

fetch_lease()
{
    # Sets lease_state to found or absent; the exact-key listing tells a lease not
    # yet written from one this host cannot read. It sets rather than prints
    # lease_state because its S3 calls run in the worker's own shell.
    local key="${LEASE_PREFIX}/${sequence}.json"
    local listing
    local count
    lease_state=
    if ! aws_cli s3api list-objects-v2 --bucket "${BUCKET}" --prefix "${key}" --output json; then
        cat "${S3_ERRORS}" >&2
        return 1
    fi
    listing=$(<"${S3_OUTPUT}")
    if [[ -z "${listing}" ]]; then
        listing='{}'
    fi
    count=$(jq -r --arg key "${key}" '[.Contents[]? | select(.Key == $key)] | length' <<<"${listing}")
    if [[ "${count}" != 1 ]]; then
        lease_state=absent
        return 0
    fi
    if ! aws_cli s3api get-object --bucket "${BUCKET}" --key "${key}" "${HOST_DIR}/leases/${sequence}.json"; then
        cat "${S3_ERRORS}" >&2
        return 1
    fi
    lease_state=found
}

read_lease()
{
    # Prints stop, or the unit, replica, phase, and comma-separated groups of a
    # work lease; rejects any lease that is not this host's next one.
    local file="${HOST_DIR}/leases/${sequence}.json"
    local fields
    local lease_schema lease_sequence lease_host lease_campaign lease_platform lease_stop
    local lease_phase lease_unit lease_replica lease_protocol lease_groups
    if ! fields=$(jq -r '[.schema_version, .sequence, .host_session, .campaign_id, .platform,
            (.stop // false), .phase, .unit_id, .replica_id, .protocol,
            (if (.groups | type) == "array" and (.groups | all(type == "string" and test("^[1-9][0-9]*$")))
             then .groups | join(",") else "-" end)]
            | map(tostring | if . == "" or . == "null" then "-" else . end) | @tsv' "${file}"); then
        echo "Lease ${sequence} is not JSON" >&2
        return 1
    fi
    IFS=$'\t' read -r lease_schema lease_sequence lease_host lease_campaign lease_platform lease_stop \
        lease_phase lease_unit lease_replica lease_protocol lease_groups <<<"${fields}"
    if [[ "${lease_schema}" != 2 || "${lease_sequence}" != "${sequence}" || "${lease_host}" != "${HOST_SESSION}" ||
            "${lease_campaign}" != "${CAMPAIGN_ID}" || "${lease_platform}" != "${PLATFORM}" ]]; then
        echo "Lease ${sequence} is not this host's lease ${sequence}: schema ${lease_schema}," \
            "sequence ${lease_sequence}, host ${lease_host}, campaign ${lease_campaign}," \
            "platform ${lease_platform}" >&2
        return 1
    fi
    if [[ "${lease_stop}" == true ]]; then
        printf 'stop\n'
        return 0
    fi
    # A measuring phase's unit is named by its 64-hex ID; the build phase leases
    # only engine_build.py's UNIT_ID, at replica 1.
    if [[ ! ( ( ${lease_unit} =~ ^[0-9a-f]{64}$ && ${lease_phase} != build ) ||
                ( ${lease_unit} == engine-build && ${lease_phase} == build && ${lease_replica} == 1 ) ) ||
            ! ${lease_replica} =~ ^[1-4]$ ||
            "${lease_protocol}" != "${PROTOCOL}" || ! ${lease_phase} =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ||
            ! ${lease_groups} =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ||
            $(tr , '\n' <<<"${lease_groups}" | sort | uniq -d) != "" ]]; then
        echo "Lease ${sequence} does not name a unit, replica, phase, distinct groups, and protocol ${PROTOCOL}:" \
            "unit ${lease_unit}, replica ${lease_replica}, phase ${lease_phase}, groups ${lease_groups}," \
            "protocol ${lease_protocol}" >&2
        return 1
    fi
    printf 'unit\t%s\t%s\t%s\t%s\n' "${lease_unit}" "${lease_replica}" "${lease_phase}" "${lease_groups}"
}

elapsed_since()
{
    # Seconds from an $EPOCHREALTIME reading until now, to the millisecond.
    awk -v start="$1" -v end="${EPOCHREALTIME}" 'BEGIN {printf "%.3f", end - start}'
}

run_group()
{
    # Runs one measured group of the lease, uploads its result, then puts its
    # completion marker once. Sets group_status to the group's exit status.
    local phase=$1
    local unit_started
    local unit_finished
    local unit_status=0
    local outcome
    local archive
    local result_sha256
    local result_bytes
    local pack_started
    local pack_seconds
    local upload_started
    local upload_seconds
    local upload_attempts
    local completion
    local environment
    local unit_attempt="${HOST_SESSION}/${sequence}"
    local prefix="${RESULT_PREFIX}/units/${sequence}/groups/${group_id}"

    unit_directory="${UNIT_ROOT}/lease-${sequence}/group-${group_id}"
    mkdir -p "${unit_directory}"
    # One digest of a group's environment files: the host, its JVM, and its engines.
    python3 "${UNIT_TOOL}" copy-environment --source "${HOST_DIR}" --target "${unit_directory}"
    environment=$(python3 "${UNIT_TOOL}" environment-sha256 --directory "${unit_directory}")

    state=running
    heartbeat
    unit_started=$(timestamp)
    # The unit leads its own process group, so an interruption stops all of it.
    (
        # The host's campaign-wide comparator order would conflict with the order
        # run-unit.sh derives from each replica.
        unset JONI_COMPARATOR_ORDER
        export RE2_CAMPAIGN_REPLICA_ID=${replica_id}
        export RE2_CAMPAIGN_LEASE_SEQUENCE=${sequence}
        exec python3 -c 'import os, sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' \
            "${RUN_UNIT}" "${unit_id}" "${PROTOCOL}" "${replica_id}" "${group_id}" "${unit_directory}"
    ) > "${unit_directory}/worker-unit.log" 2>&1 &
    unit_pid=$!
    while kill -0 "${unit_pid}" 2>/dev/null; do
        pause
    done
    wait "${unit_pid}" || unit_status=$?
    unit_pid=
    unit_finished=$(timestamp)
    outcome=complete
    if ((unit_status != 0)); then
        outcome=failed
    fi
    echo "Lease ${sequence} unit ${unit_id} replica ${replica_id} group ${group_id} ${outcome}" \
        "with status ${unit_status}"

    state=uploading
    heartbeat
    archive="${UNIT_ROOT}/lease-${sequence}-group-${group_id}.tar.gz"
    pack_started=${EPOCHREALTIME}
    tar -czf "${archive}" -C "${unit_directory}" .
    result_sha256=$(sha256_of "${archive}")
    pack_seconds=$(elapsed_since "${pack_started}")
    result_bytes=$(wc -c < "${archive}")
    # A notice while the group was packed interrupts it before its upload.
    check_notices
    upload_started=${EPOCHREALTIME}
    put_result "${archive}" "${prefix}/result.tar.gz" || stop upload-failure 1
    upload_seconds=$(elapsed_since "${upload_started}")
    upload_attempts=${put_attempts}
    completion="${UNIT_ROOT}/completion-${sequence}-${group_id}.json"
    jq -n \
        --arg host_session "${HOST_SESSION}" \
        --argjson sequence "${sequence}" \
        --arg campaign_id "${CAMPAIGN_ID}" \
        --arg phase "${phase}" \
        --arg platform "${PLATFORM}" \
        --arg unit_id "${unit_id}" \
        --argjson replica_id "${replica_id}" \
        --arg group_id "${group_id}" \
        --arg unit_attempt_id "${unit_attempt}" \
        --argjson exit_status "${unit_status}" \
        --arg unit_status "${outcome}" \
        --arg result_sha256 "${result_sha256}" \
        --argjson result_bytes "${result_bytes}" \
        --argjson pack_seconds "${pack_seconds}" \
        --argjson upload_seconds "${upload_seconds}" \
        --argjson upload_attempts "${upload_attempts}" \
        --arg started_at "${unit_started}" \
        --arg finished_at "${unit_finished}" \
        '{schema_version: 2, host_session: $host_session, sequence: $sequence, campaign_id: $campaign_id,
          phase: $phase, platform: $platform, unit_id: $unit_id, replica_id: $replica_id, group_id: $group_id,
          unit_attempt_id: $unit_attempt_id, exit_status: $exit_status, unit_status: $unit_status,
          result_sha256: $result_sha256, result_bytes: $result_bytes, pack_seconds: $pack_seconds,
          upload_seconds: $upload_seconds, upload_attempts: $upload_attempts, started_at: $started_at,
          finished_at: $finished_at}' \
        > "${completion}"
    # A notice before the marker lands leaves the group unfinished.
    check_notices
    put_once "${completion}" "${prefix}/completion.json" || stop upload-failure 1
    finished_groups+=("${group_id}")
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${sequence}" "${unit_id}" "${replica_id}" "${group_id}" \
        "${outcome}" "${unit_status}" "${unit_attempt}" "${environment}" >> "${LEDGER}"
    rm -rf "${unit_directory}" "${archive}" "${completion}"
    unit_directory=
    group_status=${unit_status}
}

run_lease()
{
    # Runs the lease's groups in order; a group that fails ends the lease, whose
    # last marker then reports the failure.
    local phase=$1
    local groups=()
    IFS=, read -r -a groups <<<"${lease_groups}"
    for group_id in "${groups[@]}"; do
        run_group "${phase}"
        if ((group_status != 0)); then
            break
        fi
    done
    group_id=
    rm -rf "${UNIT_ROOT}/lease-${sequence}"
}

idle_since=$(now)
heartbeat
while true; do
    check_notices
    stop_if_winding_down
    if ! fetch_lease; then
        lease_failures=$((lease_failures + 1))
        echo "Unable to read lease ${sequence} (${lease_failures} consecutive failures)" >&2
        if ((lease_failures >= MAXIMUM_LEASE_FAILURES)); then
            stop lease-unreadable 1
        fi
        pause
        continue
    fi
    lease_failures=0
    if [[ "${lease_state}" == absent ]]; then
        if (($(now) - idle_since >= IDLE_SECONDS)); then
            stop idle
        fi
        pause
        continue
    fi
    if ! lease=$(read_lease); then
        stop invalid-lease 1
    fi
    if [[ "${lease}" == stop ]]; then
        stop stop-lease
    fi
    # The lease read's S3 calls may have outlasted the lifetime.
    stop_if_winding_down
    IFS=$'\t' read -r _ unit_id replica_id phase lease_groups <<<"${lease}"
    if [[ -n "${WORKER_STARTED}" ]]; then
        printf 'worker\t%s\t%s\n' "${WORKER_STARTED}" "${EPOCHREALTIME}" >> "${HOST_DIR}/startup-timeline.tsv"
        WORKER_STARTED=
    fi
    run_lease "${phase}"
    unit_id=
    replica_id=
    lease_groups=
    finished_groups=()
    state=waiting
    sequence=$((sequence + 1))
    idle_since=$(now)
    heartbeat
done
