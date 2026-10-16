# LIKE diagnostics

The formal LIKE comparison lives in the baseline campaign. These two opt-in
probes protect the short-literal and dispatch decisions in
[`REGULATOR_DECISIONS.md`](../../../REGULATOR_DECISIONS.md#short-like-literal-comparisons).
They are outside the headline corpus and routine CI timing runs.

- `BenchmarkLikeLiteralLength` varies exact, prefix, and suffix needle lengths,
  mismatch positions, backing-array offsets, and input sizes. Exact matching
  uses an input as long as the needle. Prefix and suffix cost should be
  normalized by compared needle bytes, not the entire input.
- `BenchmarkLikeDispatch` compares expression-specific generated callers with
  isolated, pre-polluted, or interleaved shared profiles, plus a dynamic caller
  that rotates patterns. This tests whether a dispatch change still works when
  the caller's pattern is constant but shared matching code sees many plans.

`BenchmarkTrinoLikeTpch` measures Regulator on standard workload text rather
than synthetic filler. It matches the LIKE predicates of TPC-H queries 2, 9,
13, 14, 16, and 20 over every row of a pinned TPC-H column sample and returns
the match count. Queries 13 and 16 negate some predicates with `NOT LIKE`; the
benchmark times the `LIKE` itself. It is not part of the baseline publication
inventory.

## Preparation and verification

Use Java 25 or newer. From the repository root, prepare the pinned Trino
comparator with the [comparator setup](../trino-joni/README.md):

```bash
tools/re2-benchmark/trino-joni/prepare.sh
```

Preparation fetches dependencies and builds Trino, so it is separate from the
offline tooling checks. An existing prepared comparator can be reused. The
commands below use its default directory; substitute its `classpath.txt` path
if `TRINO_COMPARATOR_WORK_DIR` was set during preparation.

```bash
./mvnw -q test-compile
like_classpath="target/test-classes:target/classes:$(< target/trino-joni-comparator/classpath.txt)"
like_diagnostics=$(mktemp -d /tmp/regulator-like-diagnostics.XXXXXX)
javac --add-modules jdk.incubator.vector \
  -cp "$like_classpath" -processorpath "$like_classpath" \
  -processor org.openjdk.jmh.generators.BenchmarkProcessor \
  -d "$like_diagnostics" -s "$like_diagnostics" \
  tools/re2-benchmark/trino-like/BenchmarkLikeDispatch.java \
  tools/re2-benchmark/trino-like/BenchmarkLikeLiteralLength.java \
  tools/re2-benchmark/trino-like/BenchmarkTrinoLikeTpch.java
like_classpath="$like_diagnostics:$like_classpath"
java --add-modules jdk.incubator.vector -cp "$like_classpath" \
  io.airlift.regulator.BenchmarkLikeDispatch
java --add-modules jdk.incubator.vector -cp "$like_classpath" \
  io.airlift.regulator.benchmark.BenchmarkLikeLiteralLength
java --add-modules jdk.incubator.vector -cp "$like_classpath" \
  io.airlift.regulator.BenchmarkTrinoLikeTpch
```

These main methods verify checksums and expected outcomes without collecting
timings. Dispatch verification checks every LIKE plan, both Trino settings,
generated callers, and dynamic input schedules. Length verification checks
1,320 cases with 16 inputs each against Regulator and both Trino settings.

TPC-H verification runs from the repository root. It checks each column
sample against the length and SHA-256 in
[`tpch-like-corpus.tsv`](../manifests/tpch-like-corpus.tsv), then checks the
match count of Regulator and both Trino settings against
[`tpch-like-workloads.tsv`](../manifests/tpch-like-workloads.tsv), and prints
each workload's match rate and Regulator LIKE plan.

### TPC-H column samples

The samples are the first 16,384 rows of `part.name`, `part.type`, and
`orders.comment` and all 10,000 rows of `supplier.comment` at scale factor 1,
produced by `io.trino.tpch:tpch:1.4`, the Apache-2.0 generator Trino's TPC-H
connector uses. The generator plants query 16's supplier complaints in only
about five rows per 10,000, so that column keeps the whole table. Each resource holds one
value per line, gzipped. Values are packed into one backing array for timing,
like a Trino variable-width block, so every row has a nonzero offset.

Regenerate or check the resources with:

```bash
tools/re2-benchmark/trino-like/provision-tpch-corpus.sh
tools/re2-benchmark/trino-like/provision-tpch-corpus.sh --check
```

The script copies the pinned generator jar and Guava through Maven, without
adding them to Regulator's dependencies. Provisioning fails unless every
generated column matches the manifest. Changing a sample's rows changes the
expected counts as well, so review both manifests together.

## Target-host measurements

After verification, use JMH on the qualified AWS hosts. For example, this
bounded selection exercises interleaved callers near the short-literal cutoff:

```bash
java --add-modules jdk.incubator.vector -cp "$like_classpath" \
  org.openjdk.jmh.Main 'io.airlift.regulator.BenchmarkLikeDispatch.generated' \
  -p engine=REGULATOR,TRINO,TRINO_DFA -p profile=INTERLEAVED \
  -p target=EXACT6,EXACT16,EXACT17 -p outcomes=MATCH,MIXED \
  -f 3 -wi 5 -i 5 -w 1s -r 1s -rf json -rff /durable/path/like-dispatch.json
```

For the TPC-H benchmark, select one or more workloads and compare
`candidate` with `trinoSql` and `trinoOptimized`:

```bash
java --add-modules jdk.incubator.vector -cp "$like_classpath" \
  org.openjdk.jmh.Main 'io.airlift.regulator.BenchmarkTrinoLikeTpch' \
  -p workload=Q09_NAME_GREEN,Q13_ORDER_COMMENT \
  -rf json -rff /durable/path/like-tpch.json
```

Each score is the time to match every row of the column, so compare rows of
the same workload only.

Use the `dynamic` method separately with `profile=DYNAMIC`; it rotates training
workloads rather than measuring only `target`. For the length probe, select
`io.airlift.regulator.benchmark.BenchmarkLikeLiteralLength.*` and explicitly
bound `shape`, `needleBytes`, `profile`, `byteOffset`, and `inputBytes`.
Compare identical parameters and consumed results across candidates. Record
source revisions, comparator provenance, JVM options, host identity, and raw
JMH results as required by the
[measurement methodology](../../../docs/benchmarks/METHODOLOGY.md).
