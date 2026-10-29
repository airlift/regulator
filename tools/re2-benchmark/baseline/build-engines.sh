#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <result-directory>" >&2
    exit 1
fi

# Build the candidate's classes, the pinned Trino harness, and the native RE2
# and Rust regex runners once for this platform, and package them for every
# unit worker of the platform to install.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
RESULT_DIR=$1
SHARED_WORK_DIR=${BASELINE_SHARED_WORK_DIR:-${ROOT}/target/baseline-shared}
CLASSES_DIR="${SHARED_WORK_DIR}/regulator-classes"
mkdir -p "${RESULT_DIR}/logs" "${SHARED_WORK_DIR}"
rm -rf "${CLASSES_DIR}"
mkdir -p "${CLASSES_DIR}/lib"

# The candidate's classes and test classes, and its test-scope dependencies,
# which engine_build.py copies beside them. Measuring hosts run semantic tests
# with the JUnit console launcher of the project's JUnit Platform version.
(cd "${ROOT}" && ./mvnw -q -Dmaven.gitcommitid.skip=true -DskipTests test-compile dependency:build-classpath \
    -DincludeScope=test \
    -Dmdep.outputFile="${SHARED_WORK_DIR}/regulator-dependencies.txt") \
    2>&1 | tee "${RESULT_DIR}/logs/classes-build.log"
junit_platform_version=$(tr ':' '\n' < "${SHARED_WORK_DIR}/regulator-dependencies.txt" |
    sed -n 's|.*/junit-platform-engine-\([^/]*\)\.jar$|\1|p')
if [[ $(grep -c . <<<"${junit_platform_version}") -ne 1 ]]; then
    echo "Expected exactly one JUnit Platform engine in the candidate's test classpath" >&2
    exit 1
fi
(cd "${ROOT}" && ./mvnw -q -Dmaven.gitcommitid.skip=true dependency:copy \
    -Dartifact="org.junit.platform:junit-platform-console-standalone:${junit_platform_version}" \
    -DoutputDirectory="${CLASSES_DIR}/lib" \
    -Dmdep.stripVersion=true) \
    2>&1 | tee -a "${RESULT_DIR}/logs/classes-build.log"

# Only the harness's identity and provenance: every measuring route runs the
# semantic verifiers, whose verdict is final there, while a build that failed
# one would only be retried. engine_build.py points its classpath at the
# packaged candidate classes.
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
