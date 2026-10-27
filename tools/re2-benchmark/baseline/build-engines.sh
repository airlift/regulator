#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <result-directory>" >&2
    exit 1
fi

# Build the pinned Trino harness once for this platform, exactly as a route
# builds it, and package it for every unit worker of the platform.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
RESULT_DIR=$1
SHARED_WORK_DIR=${BASELINE_SHARED_WORK_DIR:-${ROOT}/target/baseline-shared}
mkdir -p "${RESULT_DIR}/logs" "${SHARED_WORK_DIR}"

TRINO_COMPARATOR_WORK_DIR="${SHARED_WORK_DIR}/pinned-trino" \
    "${ROOT}/tools/re2-benchmark/trino-joni/prepare.sh" \
    2>&1 | tee "${RESULT_DIR}/logs/trino-build.log"

python3 "${SCRIPT_DIR}/engine_build.py" package \
    --shared "${SHARED_WORK_DIR}" \
    --root "${ROOT}" \
    --result-directory "${RESULT_DIR}"
echo "Packaged engine builds in ${RESULT_DIR}/engine-build"
