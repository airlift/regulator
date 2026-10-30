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

CI runs the complete suite on Java 25 and the current feature release with
native access both disabled and enabled. To reproduce the native-access mode
locally:

```bash
./mvnw clean install -Dregulator.nativeAccessJvmArgument=--enable-native-access=ALL-UNNAMED
```

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
It needs Python 3.11 or newer with those packages, Git, Bash, zstd, and standard
Unix tools, but no Java build, external comparator checkout, AWS credentials, or
benchmark collection.
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
