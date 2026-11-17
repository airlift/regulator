#!/usr/bin/env bash
# Untimed route census of every published 2.1 report row, both memory modes, on this host's architecture.
# Usage: sweep-census/run.sh --rebar-root <dir> --parallelism <n> --out <dir> [--compare <component-rows.tsv>] [-D<maven property>...]
# See sweep-census/README.md. Rerunning with the same --out resumes: finished steps and units are kept.
set -euo pipefail

usage() {
    sed -n '3p' "$0" | sed 's/^# //' >&2
    exit 2
}

REBAR=
PARALLELISM=
OUT=
COMPARE=
MAVEN_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --rebar-root) REBAR=$2; shift 2 ;;
        --parallelism) PARALLELISM=$2; shift 2 ;;
        --out) OUT=$2; shift 2 ;;
        --compare) COMPARE=$2; shift 2 ;;
        -D*) MAVEN_ARGS+=("$1"); shift ;;
        *) usage ;;
    esac
done
[ -n "$REBAR" ] && [ -n "$PARALLELISM" ] && [ -n "$OUT" ] || usage

HERE=$(cd "$(dirname "$0")" && pwd)
CHECKOUT=$(cd "$HERE/.." && pwd)
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
REBAR=$(cd "$REBAR" && pwd)
if [ -n "$COMPARE" ]; then
    COMPARE=$(cd "$(dirname "$COMPARE")" && pwd)/$(basename "$COMPARE")
    [ -f "$COMPARE" ] || { echo "no comparison file $COMPARE" >&2; exit 2; }
fi
ARCH=$(uname -m)
case "$ARCH" in
    aarch64|arm64) PLATFORM=r9g ;;
    x86_64|amd64) PLATFORM=r8i ;;
    *) echo "unknown architecture $ARCH" >&2; exit 2 ;;
esac
PINNED_REBAR=463d00f31887e84c38467805b9e3122c314b9521
TAG_COMMIT=cbcf04d11b6b991260d921e8cebbdbf2ece7901c
EXPECTED_UNITS=2424
BUILD=$OUT/build
TAG=$OUT/tag-2.1
INPUTS=$OUT/inputs
mkdir -p "$BUILD" "$OUT/logs"

step() {
    echo "== $(date -u +%Y-%m-%dT%H:%M:%SZ) $*"
}

# Prerequisites.
JAVA_FEATURE=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
[ "$JAVA_FEATURE" = 25 ] || { echo "JDK 25 required, found $JAVA_FEATURE" >&2; exit 2; }
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 12) else 1)' || { echo "Python 3.12 or later required" >&2; exit 2; }
if git -C "$REBAR" rev-parse HEAD >/dev/null 2>&1; then
    REBAR_COMMIT=$(git -C "$REBAR" rev-parse HEAD)
    [ "$REBAR_COMMIT" = "$PINNED_REBAR" ] || { echo "rebar root is at $REBAR_COMMIT, not the pinned $PINNED_REBAR" >&2; exit 2; }
else
    REBAR_COMMIT="unknown (not a git checkout; expand_rebar.py checks the frozen pattern and haystack digests)"
fi
[ "$(git -C "$CHECKOUT" rev-parse '2.1^{commit}' 2>/dev/null)" = "$TAG_COMMIT" ] ||
    { echo "tag 2.1 ($TAG_COMMIT) is missing; run: git -C $CHECKOUT fetch origin tag 2.1" >&2; exit 2; }

cat > "$OUT/RUN.txt" <<EOF
checkout: $(git -C "$CHECKOUT" rev-parse HEAD) ($(git -C "$CHECKOUT" rev-parse --abbrev-ref HEAD))
src/ and pom.xml last changed in: $(git -C "$CHECKOUT" log -1 --format=%h -- src pom.xml)
architecture: $ARCH (report platform for tiers: $PLATFORM)
host: $(uname -srn); $(nproc) CPUs
java: $(java -version 2>&1 | head -1)
python: $(python3 --version)
rebar: $REBAR_COMMIT
report data: tag 2.1 ($TAG_COMMIT)
parallelism: $PARALLELISM
invocation started: $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

# 1. Build the checkout and write its test classpath.
step "build checkout"
(cd "$CHECKOUT" && ./mvnw -B -q -DskipTests -Dair.check.skip-all=true ${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"} test-compile)
(cd "$CHECKOUT" && ./mvnw -B -q ${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"} dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$BUILD/classpath.txt")

# 2. Probe agent, census drivers, method table.
step "build probe, drivers and method table"
javac -d "$BUILD/probe-classes" "$HERE/probe/censusprobe/Probe.java"
jar cfm "$BUILD/censusprobe.jar" "$HERE/probe/manifest.txt" -C "$BUILD/probe-classes" .
javac -nowarn -d "$BUILD/drivers" -cp "$CHECKOUT/target/classes:$CHECKOUT/target/test-classes:$BUILD/censusprobe.jar:$(cat "$BUILD/classpath.txt")" \
    "$HERE"/drivers/io/airlift/regulator/*.java
java "$HERE/tools/MethodTable.java" "$CHECKOUT/target/classes" > "$OUT/methods.tsv"
python3 -I -B "$HERE/py/check_anchors.py" "$OUT/methods.tsv" "$OUT/anchor-check.md"

# 3. Inputs: the 2.1 report data and benchmark tools from tag 2.1, the Rebar workloads, the lifecycle corpus, the units.
step "inputs"
if [ ! -f "$TAG/.complete" ]; then
    mkdir -p "$TAG"
    git -C "$CHECKOUT" archive 2.1 benchmark-report/data tools/re2-benchmark \
        src/test/resources/io/airlift/regulator/everyday-trino-workloads.tsv \
        src/test/resources/io/airlift/regulator/benchmark-memory-budgets.tsv | tar -x -C "$TAG"
    touch "$TAG/.complete"
fi
mkdir -p "$INPUTS"
if [ ! -f "$INPUTS/bulk/cases.json" ]; then
    python3 -I -B "$HERE/py/expand_rebar.py" "$TAG" "$REBAR" "$INPUTS/bulk" | tee "$OUT/logs/expand_rebar.log"
    grep -q ' 0 frozen-identity mismatches' "$OUT/logs/expand_rebar.log" || { echo "Rebar workloads differ from the pinned registry" >&2; exit 1; }
fi
if [ ! -f "$INPUTS/lifecycle/manifest.json" ]; then
    [ ! -e "$INPUTS/lifecycle" ] || { echo "remove the incomplete $INPUTS/lifecycle and rerun" >&2; exit 1; }
    # The repository's own tool at tag 2.1; it imports its sibling modules, so it runs without -I.
    python3 -E -s "$TAG/tools/re2-benchmark/language/collection.py" prepare --manifest-directory "$INPUTS/lifecycle.partial"
    mv "$INPUTS/lifecycle.partial" "$INPUTS/lifecycle"
fi
python3 -I -B "$HERE/py/build_units.py" "$TAG" "$INPUTS"
UNITS=$(wc -l < "$INPUTS/units.tsv" | tr -d ' ')
[ "$UNITS" = "$EXPECTED_UNITS" ] || { echo "expected $EXPECTED_UNITS units, found $UNITS" >&2; exit 1; }

# 4. Census: every unit, both memory modes, with the probe.
step "census of $UNITS units at parallelism $PARALLELISM"
python3 -I -B "$HERE/py/run_census.py" "$CHECKOUT" "$BUILD" "$INPUTS/units.tsv" "$PARALLELISM" "$OUT/census" 2>&1 | tee "$OUT/logs/census.log"

# 5. Analysis.
step "analysis"
python3 -I -B "$HERE/py/analyze.py" "$OUT/census" "$TAG" "$INPUTS/rowmap.tsv" "$OUT/routes-$ARCH.tsv" "$OUT/beside-$ARCH.tsv"
python3 -I -B "$HERE/py/component_rows.py" "$OUT/routes-$ARCH.tsv" "$TAG" "$PLATFORM" "$OUT/component-rows-$ARCH.tsv"
echo "invocation finished: $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$OUT/RUN.txt"
python3 -I -B "$HERE/py/summarize.py" "$OUT" "$ARCH"

# 6. Comparison.
if [ -n "$COMPARE" ]; then
    step "compare with $COMPARE"
    python3 -I -B "$HERE/py/compare.py" "$OUT/component-rows-$ARCH.tsv" "$COMPARE" "$ARCH" "$OUT/DIFF.md" \
        "$OUT/routes-$ARCH.tsv" "$(dirname "$COMPARE")/ROUTES.tsv"
fi
step "done: $OUT"
