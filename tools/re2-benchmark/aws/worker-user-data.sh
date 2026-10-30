#!/usr/bin/env bash
# A unit worker's EC2 user data. The controller sends this script with the worker's settings exported after its
# first line, as run-campaign.py's worker_user_data names them. It starts the host's stop-time watcher and startup
# timeline, installs the frozen candidate, and runs run-host.sh; on exit it uploads the host archive and powers
# off, which terminates the instance.
set -Eeuo pipefail

RESULT_DIR=/var/tmp/re2-results
mkdir -p "${RESULT_DIR}"
exec > >(tee /var/log/re2-engineering-user-data.log) 2>&1

# The host powers off at its stop time, whatever it is doing, and only a lease moves the time later. The
# controller covered each stop time in its budget, so the host stops within its reservation even when the
# controller has died or a step hangs. Its instance terminates on shutdown.
HOST_STOP_BY_FILE=/var/tmp/re2-stop-by
printf '%s\n' "${HOST_STOP_BY}" > "${HOST_STOP_BY_FILE}"
(
    while (($(date +%s) < $(<"${HOST_STOP_BY_FILE}"))); do
        sleep 15
    done
    echo "Host reached its stop time; shutting down" >&2
    shutdown -h now
) &

timeline()
{
    # One startup step: its name, the $EPOCHREALTIME it started at, and now. The kernel's uptime dates the boot.
    printf '%s\t%s\t%s\n' "$1" "$2" "${EPOCHREALTIME}" >> "${RESULT_DIR}/startup-timeline.tsv"
}
step_started=${EPOCHREALTIME}
printf 'step\tstarted\tfinished\n' > "${RESULT_DIR}/startup-timeline.tsv"
timeline boot "$(awk -v now="${step_started}" '{printf "%.3f", now - $1}' /proc/uptime)"

finish()
{
    status=$?
    trap - EXIT
    set +e
    printf '%s\n' "${status}" > "${RESULT_DIR}/exit-status"
    cp /var/log/re2-engineering-user-data.log "${RESULT_DIR}/user-data.log" || true
    archive_status=0
    tar -czf /var/tmp/re2-results.tar.gz -C /var/tmp re2-results || archive_status=$?
    upload_status=1
    if [[ ${archive_status} -eq 0 ]]; then
        for upload_attempt in {1..12}; do
            if aws s3 cp /var/tmp/re2-results.tar.gz "${HOST_ARCHIVE_URI}" \
                    --region "${BASELINE_REGION}" --only-show-errors; then
                upload_status=0
                break
            fi
            sleep 5
        done
    fi
    if [[ ${archive_status} -ne 0 || ${upload_status} -ne 0 ]]; then
        echo "Result artifact transfer failed: archive_status=${archive_status}, upload_status=${upload_status}" >&2
        status=1
    fi
    shutdown -h now
    exit "${status}"
}
trap finish EXIT

wait_for_aws_credentials()
{
    local credential_attempt
    for credential_attempt in {1..60}; do
        if aws sts get-caller-identity --region "${BASELINE_REGION}" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    echo "Instance-profile credentials did not become ready" >&2
    return 1
}

download_input()
{
    local source=$1
    local destination=$2
    local download_attempt
    for download_attempt in {1..24}; do
        if aws s3 cp "${source}" "${destination}" --region "${BASELINE_REGION}" --only-show-errors; then
            return 0
        fi
        sleep 5
    done
    echo "Unable to download campaign input ${source}" >&2
    return 1
}

step_started=${EPOCHREALTIME}
wait_for_aws_credentials
timeline credentials "${step_started}"

step_started=${EPOCHREALTIME}
mkdir -p /opt/re2-work/regulator
download_input "${CANDIDATE_URI}" /tmp/regulator.tar.gz
printf '%s  %s\n' "${REGULATOR_SNAPSHOT_SHA256}" /tmp/regulator.tar.gz | sha256sum --check -
tar -xzf /tmp/regulator.tar.gz -C /opt/re2-work/regulator
export PYTHONDONTWRITEBYTECODE=1
if [[ -n "${LANGUAGE_CANDIDATE_PROVENANCE_URI}" ]]; then
    download_input "${LANGUAGE_CANDIDATE_PROVENANCE_URI}" /tmp/candidate-provenance.tsv
    printf '%s  %s\n' "${LANGUAGE_CANDIDATE_PROVENANCE_SHA256}" /tmp/candidate-provenance.tsv | sha256sum --check -
    export LANGUAGE_SOURCE_ARCHIVE=/tmp/regulator.tar.gz
    export LANGUAGE_CANDIDATE_PROVENANCE=/tmp/candidate-provenance.tsv
fi
timeline candidate "${step_started}"
export UNIT_WORKER_STOP_BY_FILE="${HOST_STOP_BY_FILE}"

/opt/re2-work/regulator/tools/re2-benchmark/aws/run-host.sh \
    /opt/re2-work/regulator \
    "${RESULT_DIR}"
