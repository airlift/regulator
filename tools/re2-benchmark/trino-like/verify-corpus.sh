#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
TRINO_SOURCE=${1:-${TRINO_SOURCE:-}}
EXPECTED_LIKE_MATCHER_BLOB=e5164ba39471168b7db3bbdc5fe1a5e2cf445683
EXPECTED_SOURCE_MANIFEST_SHA256=2afe0c23729588497ab550fca18bd270fd01ffcb80a54a9e532431670d842ff6

if [[ -z "${TRINO_SOURCE}" ]]; then
    echo "usage: $0 <trino-source-directory>" >&2
    exit 1
fi

ACTUAL_LIKE_MATCHER_BLOB=$(git -C "${TRINO_SOURCE}" rev-parse HEAD:core/trino-main/src/main/java/io/trino/likematcher/LikeMatcher.java)
if [[ "${ACTUAL_LIKE_MATCHER_BLOB}" != "${EXPECTED_LIKE_MATCHER_BLOB}" ]]; then
    echo "Trino LikeMatcher does not match the pinned comparator" >&2
    echo "expected ${EXPECTED_LIKE_MATCHER_BLOB}" >&2
    echo "actual   ${ACTUAL_LIKE_MATCHER_BLOB}" >&2
    exit 1
fi

SOURCE_DIRECTORY="${TRINO_SOURCE}/core/trino-main/src/main/java/io/trino/likematcher"
ACTUAL_SOURCE_MANIFEST_SHA256=$(
    cd "${SOURCE_DIRECTORY}"
    find . -type f -name '*.java' -print0 |
        sort -z |
        xargs -0 sha256sum |
        sha256sum |
        awk '{print $1}'
)
if [[ "${ACTUAL_SOURCE_MANIFEST_SHA256}" != "${EXPECTED_SOURCE_MANIFEST_SHA256}" ]]; then
    echo "Trino LIKE sources do not match the pinned comparator" >&2
    echo "expected ${EXPECTED_SOURCE_MANIFEST_SHA256}" >&2
    echo "actual   ${ACTUAL_SOURCE_MANIFEST_SHA256}" >&2
    exit 1
fi

"${TRINO_SOURCE}/mvnw" -q -f "${TRINO_SOURCE}/pom.xml" -pl core/trino-main -DskipTests test-compile
CLASSPATH_FILE="${ROOT}/target/trino-like-comparator.classpath"
"${TRINO_SOURCE}/mvnw" -q -f "${TRINO_SOURCE}/pom.xml" -pl core/trino-main \
    dependency:build-classpath -DincludeScope=test -Dmdep.outputFile="${CLASSPATH_FILE}"

OUTPUT="${ROOT}/target/trino-like-comparator"
rm -rf "${OUTPUT}"
mkdir -p "${OUTPUT}"
javac \
    -cp "${TRINO_SOURCE}/core/trino-main/target/classes:$(cat "${CLASSPATH_FILE}")" \
    -d "${OUTPUT}" \
    "${ROOT}/tools/re2-benchmark/trino-like/TrinoLikeCorpusVerifier.java"

java \
    -cp "${OUTPUT}:${TRINO_SOURCE}/core/trino-main/target/classes:$(cat "${CLASSPATH_FILE}")" \
    TrinoLikeCorpusVerifier \
    "${ROOT}/src/test/resources/io/airlift/regulator/trino-like-corpus.tsv"

printf 'Trino source: %s\n' "$(git -C "${TRINO_SOURCE}" rev-parse HEAD)"
printf 'LikeMatcher blob: %s\n' "${ACTUAL_LIKE_MATCHER_BLOB}"
printf 'LIKE source manifest: %s\n' "${ACTUAL_SOURCE_MANIFEST_SHA256}"
