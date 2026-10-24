#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <result-directory>" >&2
    exit 1
fi

# Build the native RE2 benchmark, the Rebar engines, and the pinned Trino
# harness once for this platform, exactly as a baseline shard builds them, and
# package them for every baseline host of the platform.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
RESULT_DIR=$1
SHARED_WORK_DIR=${BASELINE_SHARED_WORK_DIR:-${ROOT}/target/baseline-shared}
REBAR_ROOT=${REBAR_ROOT:?REBAR_ROOT is required to build the Rebar engines}
mkdir -p "${RESULT_DIR}/logs" "${SHARED_WORK_DIR}/engine-build"

rebar_commit=$("${ROOT}/tools/re2-benchmark/manifests/rebar-revision.sh" "${REBAR_ROOT}")
cargo build --release --locked --manifest-path "${REBAR_ROOT}/Cargo.toml" \
    2>&1 | tee "${RESULT_DIR}/logs/rebar-build.log"
"${ROOT}/tools/re2-golden/fetch-dependencies.sh" \
    2>&1 | tee "${RESULT_DIR}/logs/rebar-native-fetch.log"
"${ROOT}/tools/re2-benchmark/rebar/build-native.sh" \
    "${REBAR_ROOT}" \
    "${SHARED_WORK_DIR}/rebar/native-portable" \
    "${SHARED_WORK_DIR}/rebar/native-tuned" \
    "${ROOT}/target/re2-golden-dependencies/re2" \
    2>&1 | tee "${RESULT_DIR}/logs/rebar-native-build.log"
# The native benchmark patches the pinned RE2 checkout the Rebar runners require clean.
RE2_NATIVE_TUNING=host "${ROOT}/tools/re2-benchmark/build.sh" \
    2>&1 | tee "${RESULT_DIR}/logs/native-build.log"
TRINO_COMPARATOR_WORK_DIR="${SHARED_WORK_DIR}/pinned-trino" \
    "${ROOT}/tools/re2-benchmark/trino-joni/prepare.sh" \
    2>&1 | tee "${RESULT_DIR}/logs/trino-build.log"

cp "${ROOT}/target/re2-benchmark-build/regexp_benchmark" "${SHARED_WORK_DIR}/engine-build/regexp_benchmark"
cp "${REBAR_ROOT}/target/release/rebar" "${SHARED_WORK_DIR}/engine-build/rebar"
python3 "${SCRIPT_DIR}/engine_build.py" package \
    --shared "${SHARED_WORK_DIR}" \
    --root "${ROOT}" \
    --rebar-commit "${rebar_commit}" \
    --result-directory "${RESULT_DIR}"
echo "Packaged engine builds in ${RESULT_DIR}/engine-build"
