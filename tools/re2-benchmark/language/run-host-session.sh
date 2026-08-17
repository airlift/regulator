#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <regulator-directory> <result-directory>" >&2
    exit 1
fi
regulator_directory=$1
result_directory=$2
worker_directory="${regulator_directory}/target/language-worker"
export PYTHONDONTWRITEBYTECODE=1

collect_artifacts()
{
    local status=$?
    trap - EXIT
    mkdir -p "${result_directory}/language-worker"
    # Return raw evidence and build receipts, not the isolated source and object trees.
    for name in results control-results after-results source-bracket.json worker.json cpu-affinity.json native-build.log; do
        if [[ -e "${worker_directory}/${name}" ]]; then
            cp -a "${worker_directory}/${name}" "${result_directory}/language-worker/"
        fi
    done
    if [[ -d "${worker_directory}/control-jvm" ]]; then
        mkdir -p "${result_directory}/language-worker/control-jvm"
        for name in build.log jvm-build.json; do
            if [[ -f "${worker_directory}/control-jvm/${name}" ]]; then
                cp "${worker_directory}/control-jvm/${name}" "${result_directory}/language-worker/control-jvm/"
            fi
        done
    fi
    for name in build.log jvm-build.json; do
        if [[ -f "${worker_directory}/jvm/${name}" ]]; then
            cp "${worker_directory}/jvm/${name}" "${result_directory}/language-worker/"
        fi
    done
    if ((status == 0)); then
        python3 "${regulator_directory}/tools/re2-benchmark/language/batch_acceptance.py" \
            "${result_directory}" --write
    fi
    exit "${status}"
}
trap collect_artifacts EXIT

python3 "${regulator_directory}/tools/re2-benchmark/language/worker.py" \
    --batch-directory "${result_directory}/language-inputs" \
    --output-directory "${worker_directory}" --measure \
    --source-archive "${LANGUAGE_SOURCE_ARCHIVE:?LANGUAGE_SOURCE_ARCHIVE is required}" \
    --candidate-provenance "${LANGUAGE_CANDIDATE_PROVENANCE:?LANGUAGE_CANDIDATE_PROVENANCE is required}"
