#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
OUTPUT_DIR=${1:-"${ROOT}/target/pre-review-baseline-discovery"}

mkdir -p "${OUTPUT_DIR}"
"${ROOT}/mvnw" -q -DskipTests test-compile
classpath="${ROOT}/target/test-classes:${ROOT}/target/classes:$("${ROOT}/mvnw" -q dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/dev/stdout 2>/dev/null)"

tail -n +2 "${SCRIPT_DIR}/jmh-suites.tsv" |
while IFS=$'\t' read -r shard_id suite filter parameters systems comparator allocation_contract; do
    output="${OUTPUT_DIR}/${shard_id}-${suite}.json"
    arguments=(
        --add-modules jdk.incubator.vector
        -cp "${classpath}"
        org.openjdk.jmh.Main "${filter}"
        -f 0
        -wi 0
        -i 1
        -r 1ms
        -v SILENT
        -rf json
        -rff "${output}")
    if [[ "${parameters}" != - ]]; then
        arguments+=(-p "${parameters}")
    fi
    java "${arguments[@]}" >/dev/null
    test -s "${output}"
    python3 - "${output}" "${shard_id}/${suite}" <<'PY'
import json
import sys

results = json.loads(open(sys.argv[1]).read())
if not results:
    raise SystemExit(f"declared JMH suite produced no rows: {sys.argv[2]}")
PY
done

python3 "${SCRIPT_DIR}/generate-manifest.py" \
    --jmh-directory "${OUTPUT_DIR}" \
    --output "${SCRIPT_DIR}/rows.tsv"
