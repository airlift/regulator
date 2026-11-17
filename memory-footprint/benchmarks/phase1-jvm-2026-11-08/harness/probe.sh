#!/bin/bash
# Usage: probe.sh <safe|native> <jobs.tsv> <results.jsonl> [heap]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
MODE="$1"; JOBS="$2"; OUT="$3"; HEAP="${4:-6g}"
FLAGS=(-Xms1g "-Xmx$HEAP" -XX:+UseG1GC --add-modules=jdk.incubator.vector
  -Djol.skipHotspotSAAttach=true -Djol.magicFieldOffset=true
  --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED --add-opens=java.base/java.util.regex=ALL-UNNAMED
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.lang.ref=ALL-UNNAMED
  --add-opens=java.base/jdk.internal.foreign=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED
  --add-opens=java.base/java.math=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED)
if [ "$MODE" = native ]; then FLAGS+=(--enable-native-access=ALL-UNNAMED); fi
exec java "${FLAGS[@]}" -cp "$HERE/classes:$(cat "$HERE/classpath.txt")" io.airlift.regulator.MemoryProbe "$JOBS" "$OUT"
