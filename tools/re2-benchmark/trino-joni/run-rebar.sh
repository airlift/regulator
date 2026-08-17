#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REGULATOR_DIR=$(cd "${SCRIPT_DIR}/../../.." && pwd)
WORK_DIR=${TRINO_COMPARATOR_WORK_DIR:-${REGULATOR_DIR}/target/trino-joni-comparator}
CLASSPATH_FILE=${WORK_DIR}/classpath.txt

if [[ ! -f "${CLASSPATH_FILE}" ]]; then
    echo "Pinned Trino/Joni comparator is not prepared: ${CLASSPATH_FILE}" >&2
    exit 1
fi

unset JDK_JAVA_OPTIONS JAVA_TOOL_OPTIONS _JAVA_OPTIONS
java_options=(--add-modules jdk.incubator.vector --illegal-native-access=deny)
if [[ -n ${REBAR_JONI_EXTRA_JAVA_OPTIONS:-} ]]; then
    read -r -a extra_java_options <<< "${REBAR_JONI_EXTRA_JAVA_OPTIONS}"
    java_options+=("${extra_java_options[@]}")
fi
if [[ -n ${REBAR_HEAP_SIZE:-} ]]; then
    java_options+=("-Xms${REBAR_HEAP_SIZE}" "-Xmx${REBAR_HEAP_SIZE}" -XX:+AlwaysPreTouch)
fi

exec java "${java_options[@]}" -cp "$(cat "${CLASSPATH_FILE}")" io.airlift.regulator.RebarJoniRunner "$@"
