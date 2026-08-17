#!/usr/bin/env bash

set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../../.." && pwd)
manifest_directory="$repo_root/tools/re2-benchmark/manifests"

"$manifest_directory/validate-rebar-workloads.sh"

sha256()
{
    if command -v sha256sum >/dev/null; then
        sha256sum "${1:--}" | awk '{print $1}'
    elif [[ $# -eq 0 ]]; then
        shasum -a 256 | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

verify_selected_corpus()
{
    local output=$1
    local expected_count=0
    local actual_count
    local benchmark_name
    local output_file
    local expected_length
    local expected_sha256

    while IFS=$'\t' read -r benchmark_name output_file expected_length expected_sha256; do
        [[ "$benchmark_name" == benchmark_name ]] && continue
        expected_count=$((expected_count + 1))
        compressed_file="$output/$output_file"
        if [[ ! -f "$compressed_file" ]]; then
            echo "Selected Rebar workload does not exist: $compressed_file" >&2
            exit 1
        fi
        actual_length=$(gzip -dc "$compressed_file" | wc -c | tr -d ' ')
        actual_sha256=$(gzip -dc "$compressed_file" | sha256)
        if [[ "$actual_length" != "$expected_length" || "$actual_sha256" != "$expected_sha256" ]]; then
            echo "Selected Rebar workload differs from the checked-in manifest: $benchmark_name" >&2
            exit 1
        fi
    done < "$manifest_directory/rebar-selected-workloads.tsv"
    actual_count=$(find "$output" -maxdepth 1 -type f -name '*.klv.gz' | wc -l | tr -d ' ')
    if [[ "$actual_count" != "$expected_count" ]]; then
        echo "Expected $expected_count selected Rebar workloads but found $actual_count" >&2
        exit 1
    fi
}

if [[ ${1:-} == --verify-selected ]]; then
    output=${2:-"$repo_root/target/rebar-selected"}
    verify_selected_corpus "$output"
    exit 0
fi

if [[ ${1:-} == --selected ]]; then
    rebar_root=${2:-"$repo_root/target/rebar-corpus"}
    output=${3:-"$repo_root/target/rebar-selected"}
    rebar="$rebar_root/target/release/rebar"

    if [[ ! -x "$rebar" ]]; then
        echo "Rebar executable does not exist: $rebar" >&2
        exit 1
    fi
    "$manifest_directory/rebar-revision.sh" "$rebar_root" >/dev/null

    mkdir -p "$output"
    while IFS=$'\t' read -r benchmark_name output_file expected_length expected_sha256; do
        [[ "$benchmark_name" == benchmark_name ]] && continue
        raw_output="$output/${output_file%.gz}"
        (
            cd "$rebar_root"
            "$rebar" klv "$benchmark_name"
        ) > "$raw_output"
        actual_length=$(wc -c < "$raw_output" | tr -d ' ')
        actual_sha256=$(sha256 "$raw_output")
        if [[ "$actual_length" != "$expected_length" || "$actual_sha256" != "$expected_sha256" ]]; then
            echo "Selected Rebar workload differs from the checked-in manifest: $benchmark_name" >&2
            exit 1
        fi
        gzip -n -9 < "$raw_output" > "$output/${output_file}.tmp"
        mv "$output/${output_file}.tmp" "$output/$output_file"
        rm "$raw_output"
    done < "$manifest_directory/rebar-selected-workloads.tsv"
    verify_selected_corpus "$output"
    echo "$output"
    exit 0
fi

rebar_root=${1:-"$repo_root/target/rebar-corpus"}
output=${2:-"$repo_root/target/rebar-regulator-benchmarks"}
portable_native_root=${3:-"$repo_root/target/rebar-native-portable"}
tuned_native_root=${4:-"$repo_root/target/rebar-native-tuned"}
comparator_order=${REBAR_COMPARATOR_ORDER:-forward}

case "$comparator_order" in
    forward | reverse) ;;
    *)
        echo "REBAR_COMPARATOR_ORDER must be forward or reverse: $comparator_order" >&2
        exit 1
        ;;
esac

"$manifest_directory/rebar-revision.sh" "$rebar_root" >/dev/null

rm -rf "$output"
mkdir -p "$output"
cp "$rebar_root/benchmarks/engines.toml" "$output/engines.toml"
cp -R "$rebar_root/benchmarks/definitions" "$output/definitions"
ln -s "$rebar_root/benchmarks/haystacks" "$output/haystacks"
ln -s "$rebar_root/benchmarks/regexes" "$output/regexes"

REBAR_NATIVE_CWD="$rebar_root/engines/re2" perl -pi -e '
    $is_re2 = 0 if /^\[\[engine\]\]/;
    $is_re2 = 1 if /^\s+name = "re2"$/;
    if ($is_re2 && /^\s+cwd = /) {
        $_ = qq{  cwd = "$ENV{REBAR_NATIVE_CWD}"\n};
        $is_re2 = 0;
    }
' "$output/engines.toml"

# Preserve only the curated native RE2 intersection and order the duplicate
# host-tuned controls around the primary Regulator runner. The confirmation run
# reverses every comparator while retaining the native bracket.
if [[ "$comparator_order" == forward ]]; then
    find "$output/definitions/curated" -type f -name '*.toml' -exec \
        perl -0pi -e "s/^([ \\t]*)'re2',\\n/\$1're2\\/pinned-host-tuned-before',\\n\$1'regulator\\/re2',\\n\$1'joni\\/trino',\\n\$1're2\\/pinned-host-tuned-after',\\n\$1're2',\\n\$1're2\\/pinned-portable',\\n\$1'regulator\\/re2-object',\\n/gm" {} +
else
    find "$output/definitions/curated" -type f -name '*.toml' -exec \
        perl -0pi -e "s/^([ \\t]*)'re2',\\n/\$1'regulator\\/re2-object',\\n\$1're2\\/pinned-portable',\\n\$1're2',\\n\$1're2\\/pinned-host-tuned-after',\\n\$1'joni\\/trino',\\n\$1'regulator\\/re2',\\n\$1're2\\/pinned-host-tuned-before',\\n/gm" {} +
fi

cat >> "$output/engines.toml" <<EOF

# Regulator's byte-oriented Java port of RE2.
[[engine]]
  name = "regulator/re2"
  cwd = "$repo_root"
  [engine.version]
    bin = "tools/re2-benchmark/rebar/run-regulator.sh"
    args = ["--version"]
    envs = [{ name = "REBAR_NATIVE_ACCESS", value = "enabled" }]
  [engine.run]
    bin = "tools/re2-benchmark/rebar/run-regulator.sh"
    envs = [
      { name = "REBAR_HEAP_SIZE", value = "8g" },
      { name = "REBAR_NATIVE_ACCESS", value = "enabled" },
    ]
  [[engine.dependency]]
    bin = "java"
    args = ["--version"]
  [[engine.build]]
    bin = "tools/re2-benchmark/rebar/build-regulator.sh"

# Regulator's portable object-row control.
[[engine]]
  name = "regulator/re2-object"
  cwd = "$repo_root"
  [engine.version]
    bin = "tools/re2-benchmark/rebar/run-regulator.sh"
    args = ["--version"]
    envs = [{ name = "REBAR_NATIVE_ACCESS", value = "disabled" }]
  [engine.run]
    bin = "tools/re2-benchmark/rebar/run-regulator.sh"
    envs = [
      { name = "REBAR_HEAP_SIZE", value = "8g" },
      { name = "REBAR_NATIVE_ACCESS", value = "disabled" },
    ]

# Airlift Joni as used by the pinned Trino revision.
[[engine]]
  name = "joni/trino"
  cwd = "$repo_root"
  [engine.version]
    bin = "tools/re2-benchmark/trino-joni/run-rebar.sh"
    args = ["--version"]
  [engine.run]
    bin = "tools/re2-benchmark/trino-joni/run-rebar.sh"
    envs = [
      { name = "REBAR_HEAP_SIZE", value = "2g" },
      { name = "TRINO_COMPARATOR_WORK_DIR", value = "${TRINO_COMPARATOR_WORK_DIR:-$repo_root/target/trino-joni-comparator}" },
    ]

# Exact pinned-upstream native control built with portable release flags.
[[engine]]
  name = "re2/pinned-portable"
  cwd = "$portable_native_root"
  [engine.version]
    bin = "./target/release/main"
    args = ["--version"]
  [engine.run]
    bin = "./target/release/main"

# Identical host-tuned binaries bracket Regulator to expose host drift.
[[engine]]
  name = "re2/pinned-host-tuned-before"
  cwd = "$tuned_native_root"
  [engine.version]
    bin = "./target/release/main"
    args = ["--version"]
  [engine.run]
    bin = "./target/release/main"

[[engine]]
  name = "re2/pinned-host-tuned-after"
  cwd = "$tuned_native_root"
  [engine.version]
    bin = "./target/release/main"
    args = ["--version"]
  [engine.run]
    bin = "./target/release/main"
EOF

echo "$output"
