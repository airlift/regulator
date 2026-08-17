# Pinned Rebar qualification

`RebarRunner` implements Rebar's KLV protocol over Slice byte inputs. It
supports `compile`, `count`, `count-spans`, `count-captures`, `grep`, and
`grep-captures`. Count and span operations request only group-zero boundaries;
capture operations request every explicit group. `regex-redux` is excluded
because it is a composite application rather than a single compiled-regexp
operation.

The corpus is pinned to Rebar commit
`463d00f31887e84c38467805b9e3122c314b9521`. The formal AWS controller expects
that checkout at
`~/.cache/regulator/rebar-463d00f31887e84c38467805b9e3122c314b9521`
unless `REBAR_OFFICIAL_DIR` names another verified location. Keep it outside
Maven's `target` directory so a candidate build cannot remove it.

## Prepare the runners

The preparation scripts verify the checked-in workload definitions and add:

- Regulator with native access
- Regulator's object-row fallback
- pinned Trino/Airlift Joni through the Regulator-owned Rebar protocol adapter
- pinned portable native RE2
- pinned host-tuned native RE2 before and after Regulator

Joni preparation is owned by `tools/re2-benchmark/trino-joni/prepare.sh`; it
pins both the Trino source and the resolved Airlift Joni artifact. After that
preparation, `tools/re2-benchmark/trino-joni/run-rebar.sh` is the Rebar engine
command used by the formal shard manifest.

Only definitions that declare native RE2 compatibility enter the comparator
matrix. The extended corpus may contain recognized semantic mismatches; they
are recorded and excluded from timing comparisons. Curated mismatches,
timeouts, process failures, and incomplete verifier output are fatal.

```bash
tools/re2-benchmark/rebar/prepare.sh \
  "$REBAR_OFFICIAL_DIR" \
  target/rebar-regulator-benchmarks \
  target/rebar-native-portable \
  target/rebar-native-tuned

tools/re2-benchmark/rebar/build-regulator.sh
tools/re2-benchmark/rebar/build-native.sh \
  "$REBAR_OFFICIAL_DIR" \
  target/rebar-native-portable \
  target/rebar-native-tuned
```

`REBAR_COMPARATOR_ORDER=forward|reverse` counterbalances the Java/native order
across host replicas. Both orders retain the host-tuned native before/after
bracket.

## Manifest and semantic verification

`generate-manifest.sh` expands all 41 selected workloads and records input
sizes, hashes, flags, models, expected results, result demand, and route
metadata:

```bash
tools/re2-benchmark/rebar/generate-manifest.sh \
  target/rebar-corpus/target/release/rebar \
  target/rebar-regulator-benchmarks \
  target/rebar-workloads.tsv
```

The checked-in validators reject missing workloads, changed inputs, unexpected
engines, version drift, and semantic-result mismatches:

```bash
python3 tools/re2-benchmark/rebar/validate_verification.py \
  target/rebar-verification.csv \
  --manifest target/rebar-engine-manifest.csv
```

The diagnostic route replay used to generate manifest metadata is outside
Rebar's timed process and does not instrument matching loops.

## Reduction

`summarize.py` validates the complete selected comparator matrix and emits
per-row, per-model, and ratio-bucket CSV files:

```bash
python3 tools/re2-benchmark/rebar/summarize.py \
  target/rebar-measurements.csv \
  --workload-manifest target/rebar-workloads.tsv \
  --engine-manifest target/rebar-engine-manifest.csv \
  --output-directory target/rebar-summary
```

Native before/after drift above 2% is rejected by default. Formal reduction may
use `--exclude-native-drift` to retain independently bracketed stable workloads
while writing every excluded row to `native-drift.csv`. `--allow-native-drift`
is diagnostic only and does not produce qualification evidence.

The baseline shard runner owns formal execution, partitioning, exact row
coverage, semantic receipts, and same-host timing. Do not use local performance
measurements for qualification or optimization decisions.
