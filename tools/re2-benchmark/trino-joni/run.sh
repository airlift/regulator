#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REGULATOR_DIR=$(cd "${SCRIPT_DIR}/../../.." && pwd)
WORK_DIR=${TRINO_COMPARATOR_WORK_DIR:-${REGULATOR_DIR}/target/trino-joni-comparator}

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <result.json> [JMH arguments...]" >&2
    exit 1
fi

RESULT_FILE=$1
shift

"${SCRIPT_DIR}/verify.sh"
classpath=$(cat "${WORK_DIR}/classpath.txt")
mkdir -p "$(dirname "${RESULT_FILE}")"
java \
    --add-modules jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED \
    --illegal-native-access=deny \
    -cp "${classpath}" \
    org.openjdk.jmh.Main \
    '^io\.trino\.operator\.scalar\.BenchmarkTrinoJoniComparator\..*$' \
    -rf json \
    -rff "${RESULT_FILE}" \
    "$@"
