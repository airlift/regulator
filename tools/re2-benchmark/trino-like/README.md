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
  tools/re2-benchmark/trino-like/BenchmarkLikeLiteralLength.java
like_classpath="$like_diagnostics:$like_classpath"
java --add-modules jdk.incubator.vector -cp "$like_classpath" \
  io.airlift.regulator.BenchmarkLikeDispatch
java --add-modules jdk.incubator.vector -cp "$like_classpath" \
  io.airlift.regulator.benchmark.BenchmarkLikeLiteralLength
```

These main methods verify checksums and expected outcomes without collecting
timings. Dispatch verification checks every LIKE plan, both Trino settings,
generated callers, and dynamic input schedules. Length verification checks
1,320 cases with 16 inputs each against Regulator and both Trino settings.

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

Use the `dynamic` method separately with `profile=DYNAMIC`; it rotates training
workloads rather than measuring only `target`. For the length probe, select
`io.airlift.regulator.benchmark.BenchmarkLikeLiteralLength.*` and explicitly
bound `shape`, `needleBytes`, `profile`, `byteOffset`, and `inputBytes`.
Compare identical parameters and consumed results across candidates. Record
source revisions, comparator provenance, JVM options, host identity, and raw
JMH results as required by the
[measurement methodology](../../../docs/benchmarks/METHODOLOGY.md).
