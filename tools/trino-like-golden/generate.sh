#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
BUILD_DIR=${TRINO_LIKE_GOLDEN_BUILD_DIR:-"${ROOT_DIR}/target/trino-like-golden-build"}
CORPUS="${ROOT_DIR}/src/test/resources/io/airlift/regulator/trino-like-corpus.tsv"

if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--check" ) ]]; then
    echo "usage: $0 [--check]" >&2
    exit 1
fi

dependencies=$("${SCRIPT_DIR}/fetch-dependencies.sh")
dependency()
{
    printf '%s\n' "${dependencies}" | sed -n "s/^$1=//p"
}
TRINO_COMMIT=$(dependency TRINO_COMMIT)
LIKE_MATCHER_DIR=$(dependency TRINO_LIKE_MATCHER_DIR)
DEPENDENCY_CLASSPATH="$(dependency GUAVA_JAR):$(dependency FASTUTIL_JAR)"

CLASSES_DIR="${BUILD_DIR}/classes"
rm -rf "${CLASSES_DIR}"
mkdir -p "${CLASSES_DIR}"
javac -cp "${DEPENDENCY_CLASSPATH}" -d "${CLASSES_DIR}" \
    "${LIKE_MATCHER_DIR}"/*.java \
    "${SCRIPT_DIR}/TrinoLikeCorpusGenerator.java"

# Generate into a temporary file first so a failed generation never truncates
# the committed corpus.
temporary="${BUILD_DIR}/$(basename "${CORPUS}").tmp"
java -cp "${CLASSES_DIR}:${DEPENDENCY_CLASSPATH}" TrinoLikeCorpusGenerator < "${CORPUS}" > "${temporary}"

if [[ $# -eq 1 ]]; then
    if ! diff -u "${CORPUS}" "${temporary}"; then
        echo "${CORPUS} differs from Trino ${TRINO_COMMIT}; run $0 to regenerate it" >&2
        exit 1
    fi
    rm "${temporary}"
    printf 'Verified %s\n' "${CORPUS}"
else
    mv "${temporary}" "${CORPUS}"
    printf 'Wrote %s\n' "${CORPUS}"
fi
printf 'Trino commit: %s\n' "${TRINO_COMMIT}"
