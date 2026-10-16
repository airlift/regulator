#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
MANIFEST="${ROOT}/tools/re2-benchmark/manifests/tpch-like-corpus.tsv"
TPCH_ARTIFACT=io.trino.tpch:tpch:1.4
EXPECTED_TPCH_SHA256=498dc93188cd5e9050644679fd7655c06543ef824710bac6c26aefdd43ec37de
# The guava version tpch 1.4 was built against, from its airbase 153 parent.
GUAVA_ARTIFACT=com.google.guava:guava:33.1.0-jre

if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--check" ) ]]; then
    echo "usage: $0 [--check]" >&2
    exit 1
fi

sha256()
{
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

WORK_DIR="${ROOT}/target/tpch-like-corpus"
rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}/lib" "${WORK_DIR}/classes"
for artifact in "${TPCH_ARTIFACT}" "${GUAVA_ARTIFACT}"; do
    (cd "${ROOT}" && ./mvnw -q dependency:copy -Dartifact="${artifact}" -DoutputDirectory="${WORK_DIR}/lib")
done

actual_tpch_sha256=$(sha256 "${WORK_DIR}/lib/tpch-1.4.jar")
if [[ "${actual_tpch_sha256}" != "${EXPECTED_TPCH_SHA256}" ]]; then
    echo "Unexpected ${TPCH_ARTIFACT} SHA-256: ${actual_tpch_sha256}" >&2
    exit 1
fi

CLASSPATH="${WORK_DIR}/lib/tpch-1.4.jar:${WORK_DIR}/lib/guava-33.1.0-jre.jar"
javac -cp "${CLASSPATH}" -d "${WORK_DIR}/classes" \
    "${ROOT}/tools/re2-benchmark/trino-like/TpchLikeCorpusProvisioner.java"
java -cp "${WORK_DIR}/classes:${CLASSPATH}" TpchLikeCorpusProvisioner "${ROOT}" "${MANIFEST}" "$@"
