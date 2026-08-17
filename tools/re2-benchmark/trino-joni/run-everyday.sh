#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REGULATOR_DIR=$(cd "${SCRIPT_DIR}/../../.." && pwd)
WORK_DIR=${TRINO_COMPARATOR_WORK_DIR:-${REGULATOR_DIR}/target/trino-joni-comparator}
OUTPUT=${1:-${WORK_DIR}/everyday-results.json}
shift $(( $# > 0 ? 1 : 0 ))

if [[ ! -f "${WORK_DIR}/classpath.txt" ]]; then
    echo "Comparator is not prepared; run tools/re2-benchmark/trino-joni/prepare.sh" >&2
    exit 1
fi

java \
    --add-modules jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED \
    --illegal-native-access=deny \
    -cp "$(cat "${WORK_DIR}/classpath.txt")" \
    org.openjdk.jmh.Main \
    '^io\.trino\.operator\.scalar\.BenchmarkEverydayTrinoJoni\..*$' \
    -rf json \
    -rff "${OUTPUT}" \
    "$@"
