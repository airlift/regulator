#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REGULATOR_DIR=$(cd "${SCRIPT_DIR}/../../.." && pwd)
# shellcheck source=pins.env
source "${SCRIPT_DIR}/pins.env"

WORK_DIR=${TRINO_COMPARATOR_WORK_DIR:-${REGULATOR_DIR}/target/trino-joni-comparator}
mkdir -p "${WORK_DIR}"
WORK_DIR=$(cd "${WORK_DIR}" && pwd)
TRINO_GIT_SOURCE=${TRINO_COMPARATOR_TRINO_GIT_SOURCE:-${TRINO_COMPARATOR_TRINO_REPOSITORY}}
GIT_DIR=${WORK_DIR}/trino.git
SOURCE_ARCHIVE=${WORK_DIR}/trino-source.tar
TRINO_DIR=${WORK_DIR}/trino
MAVEN_REPOSITORY=${WORK_DIR}/m2
CLASSES_DIR=${WORK_DIR}/classes
GENERATED_SOURCES_DIR=${WORK_DIR}/generated-sources

sha256()
{
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

if [[ ! -d "${GIT_DIR}" ]]; then
    git init --bare -q "${GIT_DIR}"
    git --git-dir="${GIT_DIR}" remote add origin "${TRINO_GIT_SOURCE}"
fi
if ! git --git-dir="${GIT_DIR}" cat-file -e "${TRINO_COMPARATOR_TRINO_COMMIT}^{commit}" 2>/dev/null; then
    git --git-dir="${GIT_DIR}" fetch --depth=1 origin "${TRINO_COMPARATOR_TRINO_COMMIT}"
fi

actual_commit=$(git --git-dir="${GIT_DIR}" rev-parse "${TRINO_COMPARATOR_TRINO_COMMIT}^{commit}")
actual_tree=$(git --git-dir="${GIT_DIR}" rev-parse "${TRINO_COMPARATOR_TRINO_COMMIT}^{tree}")
if [[ "${actual_commit}" != "${TRINO_COMPARATOR_TRINO_COMMIT}" ]]; then
    echo "Unexpected Trino commit: ${actual_commit}" >&2
    exit 1
fi
if [[ "${actual_tree}" != "${TRINO_COMPARATOR_TRINO_TREE}" ]]; then
    echo "Unexpected Trino tree: ${actual_tree}" >&2
    exit 1
fi

git --git-dir="${GIT_DIR}" archive --format=tar "${TRINO_COMPARATOR_TRINO_COMMIT}" > "${SOURCE_ARCHIVE}.tmp"
mv "${SOURCE_ARCHIVE}.tmp" "${SOURCE_ARCHIVE}"
rm -rf "${TRINO_DIR}"
mkdir -p "${TRINO_DIR}"
tar -xf "${SOURCE_ARCHIVE}" -C "${TRINO_DIR}"

if ! grep -Fq '<version>2.1.5.3</version>' "${TRINO_DIR}/pom.xml"; then
    echo "Pinned Trino source does not declare Joni ${TRINO_COMPARATOR_JONI_VERSION}" >&2
    exit 1
fi

(cd "${REGULATOR_DIR}" && ./mvnw -q -Dmaven.gitcommitid.skip=true -DskipTests test-compile)
REGULATOR_DEPENDENCIES=${WORK_DIR}/regulator-classpath.txt
(cd "${REGULATOR_DIR}" && ./mvnw -q -Dmaven.gitcommitid.skip=true dependency:build-classpath \
    -DincludeScope=test \
    -Dmdep.outputFile="${REGULATOR_DEPENDENCIES}")

(cd "${TRINO_DIR}" && ./mvnw \
    -Dmaven.repo.local="${MAVEN_REPOSITORY}" \
    -Dmaven.gitcommitid.skip=true \
    -Dair.check.skip-all \
    -DskipTests \
    -Dskip.bun=true \
    -Dskip.installbun=true \
    -Dmaven.javadoc.skip=true \
    -Dmaven.source.skip=true \
    -pl core/trino-main -am install)

TRINO_DEPENDENCIES=${WORK_DIR}/trino-classpath.txt
(cd "${TRINO_DIR}" && ./mvnw -q \
    -Dmaven.repo.local="${MAVEN_REPOSITORY}" \
    -Dmaven.gitcommitid.skip=true \
    -pl core/trino-main \
    dependency:build-classpath \
    -DincludeScope=test \
    -Dmdep.outputFile="${TRINO_DEPENDENCIES}")

joni_jar=$(tr ':' '\n' < "${TRINO_DEPENDENCIES}" | grep "/io/airlift/joni/${TRINO_COMPARATOR_JONI_VERSION}/joni-${TRINO_COMPARATOR_JONI_VERSION}\.jar$")
if [[ $(printf '%s\n' "${joni_jar}" | grep -c .) -ne 1 ]]; then
    echo "Expected exactly one Joni ${TRINO_COMPARATOR_JONI_VERSION} jar in the Trino classpath" >&2
    exit 1
fi
actual_joni_sha256=$(sha256 "${joni_jar}")
if [[ "${actual_joni_sha256}" != "${TRINO_COMPARATOR_JONI_SHA256}" ]]; then
    echo "Unexpected Joni jar SHA-256: ${actual_joni_sha256}" >&2
    exit 1
fi

filtered_regulator_dependencies=$(cat "${REGULATOR_DEPENDENCIES}" | tr ':' '\n' | grep -v '/io/airlift/joni/' | paste -sd: -)
filtered_trino_dependencies=$(tr ':' '\n' < "${TRINO_DEPENDENCIES}" |
    grep -Ev '/(com/google/re2j/re2j|io/trino/trino-re2j)/' |
    paste -sd: -)
COMPARATOR_CLASSPATH="${CLASSES_DIR}:${REGULATOR_DIR}/target/test-classes:${REGULATOR_DIR}/target/classes:${TRINO_DIR}/core/trino-main/target/test-classes:${TRINO_DIR}/core/trino-main/target/classes:${filtered_regulator_dependencies}:${filtered_trino_dependencies}"

rm -rf "${CLASSES_DIR}" "${GENERATED_SOURCES_DIR}"
mkdir -p "${CLASSES_DIR}" "${GENERATED_SOURCES_DIR}"
javac \
    -cp "${COMPARATOR_CLASSPATH}" \
    -processorpath "${COMPARATOR_CLASSPATH}" \
    -processor org.openjdk.jmh.generators.BenchmarkProcessor \
    -d "${CLASSES_DIR}" \
    -s "${GENERATED_SOURCES_DIR}" \
    "${SCRIPT_DIR}/src/BenchmarkEverydayTrinoJoni.java" \
    "${SCRIPT_DIR}/src/BenchmarkTrinoJoniComparator.java" \
    "${SCRIPT_DIR}/src/EverydayTrinoJoniVerifier.java" \
    "${SCRIPT_DIR}/src/JoniSliceOperations.java" \
    "${SCRIPT_DIR}/src/JoniSliceOperationsVerifier.java" \
    "${SCRIPT_DIR}/src/RebarJoniRunner.java" \
    "${SCRIPT_DIR}/src/TrinoJoniComparatorVerifier.java"

printf '%s' "${COMPARATOR_CLASSPATH}" > "${WORK_DIR}/classpath.txt"
printf '%s\n' "${joni_jar}" > "${WORK_DIR}/joni-jar.path"
{
    printf 'trino_repository=%s\n' "${TRINO_COMPARATOR_TRINO_REPOSITORY}"
    printf 'trino_commit=%s\n' "${actual_commit}"
    printf 'trino_tree=%s\n' "${actual_tree}"
    printf 'trino_source_archive_sha256=%s\n' "$(sha256 "${SOURCE_ARCHIVE}")"
    printf 'joni_version=%s\n' "${TRINO_COMPARATOR_JONI_VERSION}"
    printf 'joni_jar_sha256=%s\n' "${actual_joni_sha256}"
    printf 'everyday_benchmark_source_sha256=%s\n' "$(sha256 "${SCRIPT_DIR}/src/BenchmarkEverydayTrinoJoni.java")"
    printf 'everyday_verifier_source_sha256=%s\n' "$(sha256 "${SCRIPT_DIR}/src/EverydayTrinoJoniVerifier.java")"
    printf 'rebar_runner_source_sha256=%s\n' "$(sha256 "${SCRIPT_DIR}/src/RebarJoniRunner.java")"
    printf 'benchmark_source_sha256=%s\n' "$(sha256 "${SCRIPT_DIR}/src/BenchmarkTrinoJoniComparator.java")"
    printf 'verifier_source_sha256=%s\n' "$(sha256 "${SCRIPT_DIR}/src/TrinoJoniComparatorVerifier.java")"
    printf 'slice_operations_source_sha256=%s\n' "$(sha256 "${SCRIPT_DIR}/src/JoniSliceOperations.java")"
    printf 'slice_operations_verifier_sha256=%s\n' "$(sha256 "${SCRIPT_DIR}/src/JoniSliceOperationsVerifier.java")"
} > "${WORK_DIR}/provenance.properties"

"${SCRIPT_DIR}/verify.sh"
