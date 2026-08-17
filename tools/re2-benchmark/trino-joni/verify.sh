#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REGULATOR_DIR=$(cd "${SCRIPT_DIR}/../../.." && pwd)
# shellcheck source=pins.env
source "${SCRIPT_DIR}/pins.env"

WORK_DIR=${TRINO_COMPARATOR_WORK_DIR:-${REGULATOR_DIR}/target/trino-joni-comparator}
NATIVE_ACCESS=${TRINO_COMPARATOR_NATIVE_ACCESS:-true}
CLASSPATH_FILE=${WORK_DIR}/classpath.txt
PROVENANCE_FILE=${WORK_DIR}/provenance.properties
JONI_JAR_PATH_FILE=${WORK_DIR}/joni-jar.path

case "${NATIVE_ACCESS}" in
    true)
        java_access_arguments=(--enable-native-access=ALL-UNNAMED --illegal-native-access=deny)
        ;;
    false)
        java_access_arguments=(--illegal-native-access=deny)
        ;;
    *)
        echo "TRINO_COMPARATOR_NATIVE_ACCESS must be true or false: ${NATIVE_ACCESS}" >&2
        exit 1
        ;;
esac

sha256()
{
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

for required_file in "${CLASSPATH_FILE}" "${PROVENANCE_FILE}" "${JONI_JAR_PATH_FILE}"; do
    if [[ ! -f "${required_file}" ]]; then
        echo "Comparator is not prepared; missing ${required_file}" >&2
        exit 1
    fi
done

grep -Fqx "trino_commit=${TRINO_COMPARATOR_TRINO_COMMIT}" "${PROVENANCE_FILE}"
grep -Fqx "trino_tree=${TRINO_COMPARATOR_TRINO_TREE}" "${PROVENANCE_FILE}"
grep -Fqx "joni_version=${TRINO_COMPARATOR_JONI_VERSION}" "${PROVENANCE_FILE}"
grep -Fqx "joni_jar_sha256=${TRINO_COMPARATOR_JONI_SHA256}" "${PROVENANCE_FILE}"
grep -Fqx "everyday_benchmark_source_sha256=$(sha256 "${SCRIPT_DIR}/src/BenchmarkEverydayTrinoJoni.java")" "${PROVENANCE_FILE}"
grep -Fqx "everyday_verifier_source_sha256=$(sha256 "${SCRIPT_DIR}/src/EverydayTrinoJoniVerifier.java")" "${PROVENANCE_FILE}"
grep -Fqx "rebar_runner_source_sha256=$(sha256 "${SCRIPT_DIR}/src/RebarJoniRunner.java")" "${PROVENANCE_FILE}"
grep -Fqx "benchmark_source_sha256=$(sha256 "${SCRIPT_DIR}/src/BenchmarkTrinoJoniComparator.java")" "${PROVENANCE_FILE}"
grep -Fqx "verifier_source_sha256=$(sha256 "${SCRIPT_DIR}/src/TrinoJoniComparatorVerifier.java")" "${PROVENANCE_FILE}"
grep -Fqx "slice_operations_source_sha256=$(sha256 "${SCRIPT_DIR}/src/JoniSliceOperations.java")" "${PROVENANCE_FILE}"
grep -Fqx "slice_operations_verifier_sha256=$(sha256 "${SCRIPT_DIR}/src/JoniSliceOperationsVerifier.java")" "${PROVENANCE_FILE}"

joni_jar=$(cat "${JONI_JAR_PATH_FILE}")
if [[ $(sha256 "${joni_jar}") != "${TRINO_COMPARATOR_JONI_SHA256}" ]]; then
    echo "Joni jar no longer matches the pinned SHA-256" >&2
    exit 1
fi

classpath=$(cat "${CLASSPATH_FILE}")
classpath_joni_jar=$(tr ':' '\n' < "${CLASSPATH_FILE}" |
    grep "/io/airlift/joni/${TRINO_COMPARATOR_JONI_VERSION}/joni-${TRINO_COMPARATOR_JONI_VERSION}\.jar$")
if [[ $(printf '%s\n' "${classpath_joni_jar}" | grep -c .) -ne 1 || "${classpath_joni_jar}" != "${joni_jar}" ]]; then
    echo "Comparator classpath does not contain exactly the verified Joni jar" >&2
    exit 1
fi
if tr ':' '\n' < "${CLASSPATH_FILE}" | grep -Eq '/(com/google/re2j/re2j|io/trino/trino-re2j)/'; then
    echo "Comparator classpath contains a historical RE2J artifact" >&2
    exit 1
fi

actual_benchmarks=$(mktemp)
expected_benchmarks=$(mktemp)
trap 'rm -f "${actual_benchmarks}" "${expected_benchmarks}"' EXIT

java "${java_access_arguments[@]}" -cp "${classpath}" \
    org.openjdk.jmh.Main -l '^io\.trino\.operator\.scalar\.(BenchmarkEverydayTrinoJoni|BenchmarkTrinoJoniComparator)\..*$' |
    grep -E '^io\.trino\.operator\.scalar\.(BenchmarkEverydayTrinoJoni|BenchmarkTrinoJoniComparator)\.' |
    sort > "${actual_benchmarks}"
cat > "${expected_benchmarks}" <<'EOF'
io.trino.operator.scalar.BenchmarkEverydayTrinoJoni.contains
io.trino.operator.scalar.BenchmarkEverydayTrinoJoni.count
io.trino.operator.scalar.BenchmarkEverydayTrinoJoni.extract
io.trino.operator.scalar.BenchmarkEverydayTrinoJoni.extractAll
io.trino.operator.scalar.BenchmarkEverydayTrinoJoni.positionThird
io.trino.operator.scalar.BenchmarkEverydayTrinoJoni.replace
io.trino.operator.scalar.BenchmarkEverydayTrinoJoni.replaceLambda
io.trino.operator.scalar.BenchmarkEverydayTrinoJoni.split
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.containsJoni
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.containsRegulator
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.countJoni
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.countRegulator
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.extractAllJoni
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.extractAllRegulator
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.extractJoni
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.extractRegulator
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.positionThirdJoni
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.positionThirdRegulator
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.replaceJoni
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.replaceLambdaJoni
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.replaceLambdaRegulator
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.replaceRegulator
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.splitJoni
io.trino.operator.scalar.BenchmarkTrinoJoniComparator.splitRegulator
EOF
diff -u "${expected_benchmarks}" "${actual_benchmarks}"

java \
    --add-modules jdk.incubator.vector \
    "${java_access_arguments[@]}" \
    -cp "${classpath}" \
    io.trino.operator.scalar.TrinoJoniComparatorVerifier

java \
    --add-modules jdk.incubator.vector \
    "${java_access_arguments[@]}" \
    -cp "${classpath}" \
    io.trino.operator.scalar.EverydayTrinoJoniVerifier

"${SCRIPT_DIR}/run-rebar.sh" --version | grep -F 'Trino Joni 2.1.5.3' >/dev/null

java --add-modules jdk.incubator.vector "${java_access_arguments[@]}" -cp "${classpath}" \
    io.trino.operator.scalar.JoniSliceOperationsVerifier
