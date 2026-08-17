# RE2 benchmarking methodology

Use this guide to compare engines and investigate performance gaps. Commands
and comparison rules belong here; measured results belong in the report data.

For opt-in LIKE dispatch and literal-length probes, see the
[LIKE diagnostic instructions](../../tools/re2-benchmark/trino-like/README.md).
Their local verification is untimed; performance collection follows the
target-host rules below.

## Analysis principles

A benchmark difference must be explained at the algorithm, allocation, memory,
or generated-code level. "JVM overhead" is not an explanation.

1. Verify semantic and input equivalence before timing.
2. Convert elapsed time to an approximate cycle budget.
3. Account for scaling: O(1), O(n), and state-growth differences matter more
   than a single point ratio.
4. Confirm C2 compilation and sufficient warmup.
5. Consume every result and guard against constant folding.
6. Read the pinned C++ implementation before claiming a Java-specific limit.
7. Measure the complete public operation before retaining an isolated win.
8. Include source-identical controls and important unaffected paths.
9. Run all performance measurements on the designated AWS C8i, C8g, and C9g hosts.
   Do not use developer Macs for screening, directionality, profiling, generated-code
   conclusions, or retained evidence. Local execution is limited to build and correctness
   checks because Apple Silicon, macOS, and its JVM code generation are not representative
   of either target architecture.
10. When JMH runs with `-f 0`, install JMH's generated compiler-command file on
    the host JVM before it starts. Otherwise benchmark inlining, stub exclusion,
    and compiler-blackhole controls differ from forked JMH even when all visible
    heap, warmup, and measurement arguments match.
11. Evaluate relative and absolute changes together. Always report the baseline,
    candidate, and absolute delta alongside a ratio or percentage. A large ratio
    on a single-digit-nanosecond operation can represent only a few cycles and
    does not by itself justify a specialized implementation, additional dispatch,
    or a more complicated execution plan.
12. Treat branch profiles from tiny isolated benchmarks cautiously. A fixed input
    can train branches differently from a production stream containing different
    data sources, lengths, and outcomes. When choosing between otherwise suitable
    implementations, account for the ability of simple branch-free code to inline
    and combine with its callers; retain extra branches only when an absolute
    end-to-end benefit justifies them.
13. Treat 2% as an investigation threshold, not an automatic veto. Determine
    whether a difference is fixed overhead or a scaling-slope change, normalize
    scalable effects per byte, match, capture, or state, and let the integrated
    public operation decide whether an isolated kernel change is useful.
14. Record an accepted trade-off's measured mechanism and counterfactual. Future
    maintainers should be able to identify the extra instruction, dependent
    load, allocation, code-size effect, or algorithmic work and should not need
    to repeat a rejected experiment merely to understand the decision.

## Comparison contracts

Getting the same answer does not prove that both engines did comparable work.
Before timing, record each side's input representation, pattern and flags,
anchor, requested outputs, setup, reuse, and output construction. Different
algorithms for the same operation are fair. Asking only one engine to recover
captures, construct values, or find a boundary is not a fair engine comparison.

The traditional pair registry in `tools/re2-benchmark/traditional/summarize.py`
selects exact Java and native benchmark names. Corrected call contracts use new
names so old raw results cannot satisfy a new comparison. The corresponding
baseline row manifest must be regenerated with those names. Archived manifests
and captured results stay unchanged.

| Benchmark | Requested work on both sides |
|---|---|
| `programPossibleMatchRange*` / `PossibleMatchRange_Prog_*` | Internal program range analysis, complete UTF-8 pattern, cached 2 GiB program budget, 16-byte bounds |
| `dotMatchCaptures` / `asciiMatchCaptures` | Unanchored search with one capture, in addition to the whole match |
| `searchPhoneRe2Captures` | Unanchored search with two captures, in addition to the whole match |
| `search*DfaBoolean` | Internal boolean DFA search, no match boundary, explicit resource-failure check |
| `search{Success,Success1,AltMatch}Re2FullMatch` | Public full-string match, no requested captures |

New language collectors record `benchmark_contract_version` before timing and
export a `work_contract` for each comparison. Report assembly rejects mixed
contracts across hosts. Exports without that provenance remain historical;
running a new exporter must not upgrade old measurements to a new API contract.

Current language comparisons use these work contracts:

- Trino bulk span sums and capture counts visit match boundaries and capture
  participation through matchers on both sides. Each side creates one matcher
  per source inside timing and reuses it for that source's matches; neither
  constructs extraction lists or replacement output for these operations.
- Trino extraction, split, and callback suites use Slice-based outputs on both
  sides. The comparator's `JoniSliceOperations` retains pinned Trino's Joni
  operation loops but returns Slice lists and passes Slice-list captures to
  callbacks instead of constructing SQL Blocks. Separate correctness checks
  compare this adapter with Trino's SQL functions.
- LIKE construction uses prepared Slice patterns on Regulator and prepared String
  patterns on Trino LikeMatcher. This is a compiler-API comparison, not complete
  Slice-to-Trino-SQL conversion. JDK regex similarly receives prepared Strings.

Retain results from unequal-work or obsolete API comparisons as historical
evidence, without speedups, deltas, win/loss colors, or crossover claims. Do not
add artificial comparator work or a production API just to make a chart
symmetric. First decide whether the comparison measures matching, extraction,
replacement, or full SQL integration. Preserve the raw measurements regardless
of the outcome. For timing variability, show the status and measured times
directly rather than a footnote symbol or an unsupported winner.

## Java benchmarks

Representative JMH classes include:

| Class | Purpose |
|---|---|
| `BenchmarkRe2Search` | Public and DFA search scaling |
| `BenchmarkRe2SearchNfa` | NFA search scaling |
| `BenchmarkRe2SearchExtra` | Successful and failed searches across engines |
| `BenchmarkRe2Parse` | Parsing and capture extraction |
| `BenchmarkRe2FullMatch` | Full-match scaling |
| `BenchmarkRe2Practical` | Practical patterns and compile cost |
| `BenchmarkRe2CompileFocused` | Parser, compiler, and cold-DFA costs |
| `BenchmarkDfaCache` | Cache sharing, construction, and reset behavior |
| `BenchmarkDfaSelfLoopExitScan` | Small exit-byte candidate scanning |
| `BenchmarkBoundedCharacterClassCounter` | Specialized counting versus repeated matching |
| `BenchmarkRe2PublicApi` | Boolean, caller-buffer, result, and matcher APIs |
| `BenchmarkExpressionPlans` | Direct expression plans and general controls |
| `BenchmarkEverydayTrinoRegexp` | Application-shaped Trino operations over rotating Slice values |
| `BenchmarkTrinoRegexp` | Fixed-haystack Trino operation diagnostics |

Compile the test classes and resolve their classpath with Maven:

```bash
./mvnw test-compile -q
CP="target/test-classes:target/classes:$(./mvnw -q dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/dev/stdout 2>/dev/null)"
```

On a developer machine, list available methods without running timings:

```bash
java --add-modules jdk.incubator.vector -cp "$CP" \
  org.openjdk.jmh.Main -l 'io.airlift.regulator.BenchmarkRe2Search.*'
```

On the designated AWS hosts, benchmark classes using the common runner accept:

```text
[filter] [forks] [warmupIterations] [measurementIterations] [iterationTimeMillis]
```

Use the AWS execution path below for all timing runs. The bounded Regulator
protocol uses independent top-level JVMs with JMH compiler controls installed
on each host process. Pinned Joni uses a proper fork per primary host; the three
primary replicas provide three isolated Joni JVMs per row. Functional smoke
uses one short process and is not retained timing evidence.

## Native RE2 baseline

Build the pinned native comparator with:

```bash
tools/re2-benchmark/build.sh
target/re2-benchmark-build/regexp_benchmark \
  --benchmark_filter='threads:1$' \
  --benchmark_repetitions=5 \
  --benchmark_min_time=0.5s
```

The build fetches pinned RE2, Abseil, Google Benchmark, and GoogleTest sources.
Benchmark-only workload definitions make Java and C++ consume identical pattern
and input bytes; native RE2 engine code is unchanged.

| Dependency | Commit |
|---|---|
| RE2 | `972a15cedd008d846f1a39b2e88ce48d7f166cbd` |
| Abseil | `d38452e1ee03523a208362186fd42248ff2609f6` |
| Google Benchmark | `192ef10025eb2c4cdd392bc502f0c852196baa48` |
| GoogleTest | `52eb8108c5bdec04579160ae17225d66034bd723` |

Native and Java pairs must agree on operation, input, engine route, caching,
captures, encoding, memory budget, and expected result. Run native immediately
before and after Java on the same host. A Java/native elapsed-time ratio below
`1.00` means Java is faster; above `1.00` means Java is slower.

## Trino comparators

`BenchmarkEverydayTrinoRegexp` measures Regulator's scalar operations over a
fixed sequence of changing Slice values. Its Trino counterpart measures Airlift
Joni with the same patterns, Slice offsets, input order, operation arguments,
and expected outputs. `BenchmarkTrinoRegexp` and the final-line matrices use
unchanging inputs. They are diagnostics, not the source of the application
headline.

[`WORKLOAD_ORGANIZATION.md`](WORKLOAD_ORGANIZATION.md) defines the population,
family balancing, comparator applicability, and reporting rules. Rebar bulk
text, compilation, and diagnostics are reported independently rather than
combined by row count.

Rebar measures native RE2 and Regulator in one bracketed invocation and runs
applicable pinned Joni rows through a separate exact invocation on the same
host. A checksummed exhaustive ledger records incompatible syntax or semantics.
A compatible workload that exceeds the fixed verification timeout remains a
runtime `did-not-finish` outcome rather than being reclassified as incompatible.

Run semantic smoke tests before timing either side. A comparator mismatch is a
correctness problem, not a performance result.

## AWS execution

`tools/re2-benchmark/baseline/run-campaign.py` schedules the frozen shard
manifest. Its `aws/run-campaign.sh` worker provisions one isolated Intel or
Graviton host, uploads the clean source archive, records environment metadata,
pins benchmark processes to physical cores, downloads artifacts, and terminates
all cloud resources.

Final runs require:

- a clean committed candidate
- qualification provenance
- pinned JDK and comparator revisions
- workload and source checksums
- no overlapping process on a sibling hardware thread
- candidate and control measurements on the same host

Exploratory measurements and comparisons of alternative implementations must
also run on C8i, C8g, and C9g. Do not use local timings to accept or reject a
candidate, or to decide whether it deserves a target-host run.

Independent shards may use separate host pairs in parallel. Results from
different hosts are independent sessions, not interchangeable brackets.

See the [AWS guide](../../tools/re2-benchmark/aws/README.md) for commands,
environment variables, machine-hour planning, and Spot/On-Demand costs.

## Profiling

Profile after reproducing a stable gap in the complete operation.

```bash
# JIT compilation
java -XX:+PrintCompilation --add-modules jdk.incubator.vector -cp "$CP" \
  io.airlift.regulator.BenchmarkRe2Search

# Assembly, with hsdis installed
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly \
  -XX:CompileCommand=print,*Dfa.searchLoop \
  --add-modules jdk.incubator.vector -cp "$CP" \
  io.airlift.regulator.BenchmarkRe2Search
```

JFR, async-profiler, Linux `perf`, C2 ideal graphs, and native disassembly are
appropriate when ordinary profiles do not explain the cycles.

When a bounded JMH protocol disagrees with the full protocol, compare the
generated benchmark stub as well as the production methods it inlines. Record
the compiled-code size and normalized instruction stream, and count memory
operand instructions before attributing a difference to extra loads. If those
are identical, inspect allocation per operation and the iteration-by-iteration
GC cadence. Short iterations can report mutator-only speed when periodic
collection cost lands in one outlier that the row median discards; move that
measured parameter group to the full protocol instead of weakening the
equivalence gate.

## Retained memory

Production accounting uses `SizeOf` and charges all reachable DFA storage to
the owning budget. Tests cover construction, growth, reset, and compressed and
uncompressed reference layouts.

JOL may be used as a temporary diagnostic to check `SizeOf`; it must not become
a production or test dependency because its exact measurements require JVM
instrumentation permissions. Compare complete retained graphs, not only shallow
objects.

## Measurement protocol

### Java

The [baseline dispatch protocol](../../tools/re2-benchmark/baseline/SHARD_DISPATCH.md#jmh-protocol)
is authoritative for baseline shards. It defines the bounded Regulator process
sets, Joni fork policy, full-protocol exceptions, allocation collection, and
per-host equivalence checks. Do not replace it with a blanket five-fork or
one-second setting.

The [language collector](../../tools/re2-benchmark/language/README.md#target-host-measurement-and-saved-data)
uses its own frozen protocol and receipts, including for source brackets.
Record process/fork boundaries, warmup, iteration duration, heap, suite order,
and allocation settings. Shorter measurements are valid only under the relevant
protocol's qualification gates, not as an ad hoc way to reduce cost.

Keep cold compilation and steady-state matching in separate results.

### Native

Use Google Benchmark with the same manifest, checksums, thread count, minimum
duration, and at least five repetitions. Record JSON and exact release compiler
flags. Consume match ranges or output checksums to prevent elimination.

### Statistics

Report medians, observed host ranges, coefficients of variation, allocation,
and throughput or elapsed time as appropriate. The baseline policy does not
report confidence intervals from only three or four hosts. Do not label a range
as a confidence interval or count iterations as independent hosts.

Show baseline and candidate times and their absolute difference alongside each
ratio. Compare how costs grow with input size. A large percentage on a
few-nanosecond operation may be a negligible fixed cost; a small percentage on
a large input may reveal an important scaling regression. Account for the
complete operation, generated-code complexity, and whether the benchmark's
branch distribution represents the workload. Leave a result unresolved when
host variation overlaps the claimed difference.

Investigate when:

- either implementation appears more than five times faster
- an expected constant-time path scales with input
- an expected linear path appears constant
- the ratio changes materially with input size
- coefficient of variation exceeds 5%
- allocation appears in an allocation-free contract

## Bounded investigations

Set a round limit and stopping conditions before changing code. Each round tests
one measured hypothesis with candidate-before, exact source control, and
candidate-after on the same host.

Stop when:

- representative paths reach parity
- the round ceiling is exhausted
- no measured mechanism can plausibly recover the gap
- correctness, lifecycle, or memory accounting fails
- an important protected regression has been measured, explained, and rejected
  under the investigation's stated acceptance rule

Do not open another round merely because a rejected candidate helped a different
workload.

## Capture decomposition

For slow capture operations, measure the actual public route in stages:

1. control operation
2. matcher setup
3. forward boundary search
4. reverse boundary search
5. capture engine
6. composed operation
7. result consumption

Account for the public operation's time before naming a root cause.
Reusable matchers may own scratch arrays, but tests must prove reuse, reset,
absence of retained input references, and caller-buffer allocation contracts.

## Investigation workflow

1. Reproduce the exact semantic operation and expected result.
2. Confirm input bytes, sizes, seeds, options, anchors, and memory budgets.
3. Verify warmup and tier-4 compilation.
4. Compare scaling across sizes.
5. Read the corresponding pinned C++ path.
6. Profile the complete operation.
7. Isolate the responsible engine or scanner.
8. Inspect generated code when profiles do not explain the cycles.
9. Add a deterministic path-selection or representation test.
10. Measure the candidate and protected controls on Intel and Graviton.

Optimize in this order:

1. algorithm and route
2. data representation and allocation
3. memory access and cache behavior
4. low-level SWAR, Vector API, and branch shape

A faster component is useful only if it improves the complete operation.
