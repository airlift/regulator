# Testing

This is a single-module Maven project. Use the checked-in wrapper:

```bash
./mvnw clean install
./mvnw test -Dtest=TestRegexpMatcher
./mvnw test '-Dtest=TestRegexpMatcher#testMatchModesAndReset'
./mvnw clean package -DskipTests
```

Maven Daemon is optional for local iteration; the wrapper pins the Maven
version used by CI.

## Tooling

Use JUnit Jupiter for unit and integration tests, with AssertJ assertions.

## Best practices

- Add tests with every feature and bug fix. Cover both ordinary use and the
  boundary or failure cases the change affects.
- Name test classes `Test*.java`, abstract tests `Abstract*Test.java`, and
  helpers `Testing*.java`.
- Use descriptive camelCase method names without underscores, such as
  `testMatchModesAndReset` or `testEmptyMatchesAdvanceByUtf8CodePoint`.

See [coding-standards.md](coding-standards.md) for implementation conventions
and [git.md](git.md) for commit structure.

## Regulator verification

The [test map](porting/TEST_MAP.md) names each language's oracle and maps RE2
coverage to the upstream RE2 tests. Run the complete Regulator suite with:

```bash
./mvnw "-Dtest=**/regulator/**/Test*" test
```

This suite protects semantics, supported engine agreement, native differential
results, malformed input, resource limits, reset behavior, and concurrency. It
does not establish performance. Changes to engine routing, retained data shape,
or protected matching loops require the benchmark evidence defined in
[`MAINTAINING_REGULATOR.md`](MAINTAINING_REGULATOR.md).

`TestDfaCopyOnResetStress` runs for `-Dregulator.stress.seconds=N` seconds (default 5).

`-Dregulator.vectorJvmArgument=-Dio.airlift.regulator.dfa.flat-paired-rows=false`
(or `true`) runs the suite with DFA paired rows in that form instead of the
host's. It takes the place of any vector-width argument CI passes in the same
property, so to set both, put them in one quoted value.
`TestDfaAbsolutePointerTransitions` already runs `TestDfaPairedTransitions`'
cases in both forms.

`TestJavaRegexpFuzz`, `TestTrinoRegexpFuzz`, `TestRe2Fuzz`,
`TestRustRegexpFuzz`, and `TestTrinoLikeFuzz` compare random patterns and inputs
across each language's compile variants and against its oracle, and by default
run 600 patterns from a fixed seed and fail on the first disagreement. With
`-Dregulator.fuzz.seconds=N` they run for `N` seconds from
`-Dregulator.fuzz.seed=S` (default random), write each distinct shrunk
disagreement with a replay line and a summary to `-Dregulator.fuzz.output=DIR`
(default `target/fuzz`), and fail at the end if they found any. A run shrinks at
most eight disagreements of each signature and writes at most 100 reports, so a
long run's reports sample what it found rather than list it all.

The JDK and Joni oracles are always on the classpath. The other three are
built outside Maven and named by a property; without it, the test compares the
compiles with one another only:

- `TestRe2Fuzz`: `-Dregulator.fuzz.re2Oracle=PATH` names the `re2_golden`
  binary, which `tools/re2-golden/build.sh` builds at
  `target/re2-golden-build/re2_golden`.
- `TestRustRegexpFuzz`: `-Dregulator.fuzz.rustOracle=PATH` names the
  `rust-regex-golden` binary. `tools/rust-regex-golden/generate.sh` builds it
  at `target/rust-regex-golden-build/release/rust-regex-golden`, or, without
  regenerating the fixtures, run
  `cargo build --release --locked --target-dir ../../target/rust-regex-golden-build`
  in `tools/rust-regex-golden`.
- `TestTrinoLikeFuzz`: `-Dregulator.fuzz.likeMatcherClasspath=PATHS` lists,
  separated by the platform path separator, the classes that
  `tools/trino-like-golden/generate.sh --check` compiles into
  `target/trino-like-golden-build/classes` and the Guava and fastutil jars it
  fetches into `target/trino-like-golden-dependencies/lib`.

Each tool's README names the variables that move its dependencies and build
output elsewhere.

CI runs the complete suite on Java 25 and the current feature release with
native access both disabled and enabled. To reproduce the native-access mode
locally:

```bash
./mvnw clean install -Dregulator.nativeAccessJvmArgument=--enable-native-access=ALL-UNNAMED
```

CI also runs the current feature release with native access enabled at 8-byte
and 16-byte vectors, whatever the runner's hardware width. At 8 bytes the
vector scanners are off and searches use the scalar or SWAR scanners; 16 bytes
is the narrowest width the vector scanners use, as on our Graviton benchmark
hosts. To reproduce one:

```bash
./mvnw clean install \
    -Dregulator.nativeAccessJvmArgument=--enable-native-access=ALL-UNNAMED \
    -Dregulator.vectorJvmArgument=-XX:MaxVectorSize=8
```

A third build runs the current feature release with native access enabled on an
arm64 runner, which pairs DFA rows on the flat table and whose SVE with 16-byte
vectors selects the XOR candidate masks described in "Candidate Masks on SVE" in
`REGULATOR_DECISIONS.md`. It sets `-Dregulator.expectSve128=true`, so a test
fails if `VectorSupport` stops selecting them on that runner.

Before changing the Unicode database generator, its manifest, or the
generated database, run the same check CI runs. It downloads the pinned UCD
files from unicode.org:

```bash
python3 -I tools/unicode-database/generate.py --check
```

Before changing the Trino LIKE corpus, or after changing the benchmark
comparator's Trino pin, check the corpus against Trino's `LikeMatcher` with the
[Trino LIKE golden generator](../tools/trino-like-golden/README.md). It fetches
the pinned Trino sources from GitHub and Guava and fastutil through Maven:

```bash
tools/trino-like-golden/generate.sh --check
```

## TeaVM pattern parsing

After `./mvnw install`, run `tools/teavm/verify.sh` to compile every regex
language's public pattern-validation API to JavaScript and Wasm GC and execute
both in Node. For each language's parser, the check covers Unicode classes and
case folding, capture metadata, options, Slice views, and UTF-8 error spans. It
does not compile or run the matching engines.

## Benchmark setup

Before changing collectors, campaign orchestration, manifests, or reducers, run
the same offline gate used by CI, after installing the tooling's pinned Python
packages:

```bash
python3 -m pip install -r tools/re2-benchmark/requirements.txt
python3 tools/re2-benchmark/validate-tooling.py
```

It first generates the row protocols and work units, which are not checked in,
with `tools/re2-benchmark/baseline/generate-units.py`; run that script before
running one of the suites alone. It then discovers the tooling's Python unit
suites in separate processes and checks the frozen manifests, modes, protocol
representatives, work units, and unit plans. Cloud operations and build
commands in these unit tests use fixtures or mocks.
The gate clears `LANGUAGE_NATIVE_RUNNER`, `LANGUAGE_NATIVE_BULK_RUNNER`,
`LANGUAGE_NATIVE_RUST_RUNNER`, and `LANGUAGE_NATIVE_RUST_BULK_RUNNER`, so native
integration tests remain explicitly skipped even in a prepared shell.
It needs Python 3.14 or newer with those packages, Git, Bash, zstd, and standard
Unix tools, but no Java build, external comparator checkout, AWS credentials, or
benchmark collection. The tests and `fetch` read zstd archives with `tarfile`,
which Python 3.14 added; CI runs 3.14.
See the [language guide](../tools/re2-benchmark/language/README.md) for opt-in
native integration checks and the comparator guides for source preparation.

Compile the test classes and resolve their classpath before running an opt-in
benchmark. Matching benchmarks require the Vector API module:

```bash
./mvnw test-compile -q
./mvnw -q dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=target/test-classpath.txt
benchmark_classpath="target/test-classes:target/classes:$(< target/test-classpath.txt)"
java --add-modules jdk.incubator.vector -cp "$benchmark_classpath" \
  org.openjdk.jmh.Main -l 'io.airlift.regulator.BenchmarkRe2Search.*'
```

This lists benchmarks without timing them. Collect timings on the designated
AWS hosts, following the measurement rules in
[`benchmarks/METHODOLOGY.md`](benchmarks/METHODOLOGY.md).
