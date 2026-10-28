#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <result-directory>" >&2
    exit 1
fi

# Build the pinned Trino harness once for this platform, exactly as a route
# builds it, and the native RE2 and Rust regex runners from the candidate's
# sources, and package them for every unit worker of the platform.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
RESULT_DIR=$1
SHARED_WORK_DIR=${BASELINE_SHARED_WORK_DIR:-${ROOT}/target/baseline-shared}
mkdir -p "${RESULT_DIR}/logs" "${SHARED_WORK_DIR}"

# Only the harness's identity and provenance, as run-shard.sh rebuilds it: every
# measuring route runs the semantic verifiers, whose verdict is final there, while
# a build that failed one would only be retried.
TRINO_COMPARATOR_IDENTITY_ONLY=true TRINO_COMPARATOR_WORK_DIR="${SHARED_WORK_DIR}/pinned-trino" \
    "${ROOT}/tools/re2-benchmark/trino-joni/prepare.sh" \
    2>&1 | tee "${RESULT_DIR}/logs/trino-build.log"

# Generic tuning runs on every host of the platform, not only the builder.
RE2_NATIVE_TUNING=generic \
RE2_BENCHMARK_TARGETS="language_benchmark language_bulk_benchmark language_rust_benchmark language_rust_bulk_benchmark" \
RE2_BENCHMARK_BUILD_DIR="${SHARED_WORK_DIR}/language-native" \
    "${ROOT}/tools/re2-benchmark/build.sh" \
    2>&1 | tee "${RESULT_DIR}/logs/native-build.log"

python3 "${SCRIPT_DIR}/engine_build.py" package \
    --shared "${SHARED_WORK_DIR}" \
    --root "${ROOT}" \
    --result-directory "${RESULT_DIR}"
echo "Packaged engine builds in ${RESULT_DIR}/engine-build"
