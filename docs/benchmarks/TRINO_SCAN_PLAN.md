# Trino specialized scan plan

The scan plan recognizes a bounded family of Trino patterns and executes
literals, deterministic character runs, and captures over Slice bytes. It
targets `contains`, match boundaries, captures, extraction, and replacement
through the public APIs. It does not change RE2 or Java frontend execution.

## Coverage

The comparison target is ClickHouse `v26.8.2.7-lts`, specifically
[`RegexpProgram.cpp`](https://github.com/ClickHouse/ClickHouse/blob/v26.8.2.7-lts/src/Common/RegexpJIT/RegexpProgram.cpp)
and [`CompileRegexp.cpp`](https://github.com/ClickHouse/ClickHouse/blob/v26.8.2.7-lts/src/Interpreters/JIT/CompileRegexp.cpp).
Coverage means accepting an equivalent expression under the target language's
contract, not importing RE2 syntax or changing Trino semantics.

| Feature | Coverage |
|---|---|
| Case-sensitive UTF-8 literals | 1–256 bytes per literal |
| Greedy optional fixed literals | Up to four, sharing one budget with optional forks |
| Character runs | ASCII sets and their complements; variable counts require disjoint continuation through captures and optional branches |
| Captures | Capture-free plans plus mandatory, nested, and optional captures; named groups also work; up to 16 subject to the operation limit |
| Optional bodies | Literals, runs, captures, and nested optionals; at most four forks, sharing the optional budget |
| Repetitions | Greedy single-character `?`, `*`, `+`, exact and bounded counts up to 1000; unbounded minimum counts |
| Start behavior | Logical input start |
| End assertions | Optional; strict end and Trino final-LF `$` are supported when present |
| Terminal behavior | Partial-match success, dot-all `.*`, LF-excluding `.*`, and supported character-set runs |
| Case folding | Ordinary engine |
| Empty overall matches | Ordinary engine |
| Program size | At most 32 operations plus one terminal operation |

ClickHouse bounds optional forks at four, parsing depth at sixteen, expanded
program size at 256, captures at 1024, and explicit repeat counts at 1000. Its
current `MAX_NONDET_QUANTIFIERS` is zero, despite a nearby comment describing
one. The executable eligibility check is authoritative: variable repetitions
must have an unambiguous greedy stop. The emitter contains a give-back loop,
but that does not establish current eligibility for ambiguous repetitions.

Anchored partial-match coverage includes these ClickHouse performance cases:

- `^https?://[^/]+/`
- `^https?://[^/]+/[^?]*\?`
- `^https?://([^/]+)`
- `^https?://(?:www\.)?([^/]+)/`

Any extension must preserve the Trino language
contract. Unsupported optimization shapes use the ordinary Regulator engine;
this is not fallback to another regex language or to Joni.

## Integration

`TrinoScanPlan.analyze` reads the parsed expression before required-prefix
stripping. It requires an input-start assertion and proof that a successful
match consumes input. End assertions and captures are optional. Capture-free
plans must contain a character run, so literal-only
expressions retain the direct prefix and
equality routes. The single-delimiter operation consumes its delimiter directly.
General runs leave the following literal to its own operation. One builder
handles both linear and richer optional and capture structure. After
continuation verification, a peephole pass compacts linear plans
into delimiter-capture and terminal-tail operations. Plans
with general forks, nested capture boundaries, or more than three captures keep
the general representation with explicit capture saves.

`TrinoScanPlanRun` admits ASCII sets and sets containing all non-ASCII
characters plus any subset of ASCII. These sets have uniform membership for
non-ASCII UTF-8 bytes. Other Unicode classes retain ordinary engine execution.
Small byte sets or their complements, up to four bytes, use vector equality
scans. Larger sets use a scalar membership table.

A variable count must be disjoint from every possible first byte of its
continuation. The richer builder propagates these sets backward through capture
boundaries and both arms of optional groups. Analysis stores each
possible-first-byte set in four 64-bit words; run scanning at execution time
uses the run's own membership table. This admits a hostname followed
by an optional port or a required slash when the hostname set excludes both
colon and slash. It excludes runs that might need to give characters back.
An exact count has only one endpoint and can precede overlapping text. Nested
repetitions and repeated multi-operation bodies remain unsupported.

Finite bounds count characters. ASCII runs can count bytes; runs admitting
non-ASCII characters use the shared UTF-8 decoder. An unbounded minimum such
as `{2,}` decodes only enough characters to establish the minimum, then scans
the remainder by bytes. Malformed input still has no promised exact result;
the decoder is a bounded character-advancement mechanism, not a validation pass.

`Re2` retains the immutable plan in its plan bundle and charges the
complete plan and literal storage against the forward DFA budget. If the budget
does not fit, compilation retains the ordinary route. The semantic program is
kept for range-limited calls outside the scan plan's supported context.

Plan execution uses its own strategy byte, `trinoScanStrategy`, separate from
the boolean `find` strategy; both fit in the 96-byte `Re2` layout.
Shared capture matching checks the plan strategy byte before loading the plan,
avoiding an extra dependent plan lookup for unrelated patterns. It invokes the
plan before required-prefix stripping. Full-Slice boolean `find` keeps the
literal `CONTAINS`, `STARTS_WITH`, `EQUALS`, and `EQUALS_FINAL_LINE` kernels
when a plan also exists, because they answer without reading past the literal.
Any other boolean strategy is replaced by the plan. Boundary, capture, range,
and count calls reach the plan through its own strategy either way.

Compiler selection reuses the start-anchor proof to reject unanchored
expressions before allocating temporary analysis storage. Matcher and function
code own reset, retained groups, failure state,
Slice-relative offsets, zero-copy extraction, replacement parsing, and output
construction.

End-anchored and partial plans use separate Java executor
methods over the same operations. This keeps their completion and retry profiles
out of the end-anchored loop. Literals of up to 16 bytes use precomputed
first/last word comparisons. Longer literals use `Arrays.mismatch`.
Delimiter/newline scans use 128-bit vectors with a scalar tail. Four optional
checkpoints fit in local primitive values; no retry array or boolean workspace
is allocated.

The richer builder emits capture-boundary saves and forward optional forks. The
linear-only compaction pass folds a
supported top-level captured run into one capture-aware operation, preserving
the compact URL plan. Richer plans keep their explicit capture saves. Optional
character bodies keep their forks so later failures can retry without the
optional character. Each fork records its skip target and a mask of captures in
its body. Skipping the body clears those captures. Every later capture on a
successful path is visited again, and enclosing captures keep their start.
Because captures cannot appear inside repeated bodies, capture snapshots are
unnecessary. Optional literals and forks share one
budget of four retry checkpoints, so an attempt explores at most sixteen
combinations, independent of input length. The builder also limits nesting depth
to sixteen and operations to thirty-two plus one terminal operation. Failed
calls clear caller-provided capture offsets. A compile-time nullability check
excludes whole-pattern empty matching.

An expression without an end assertion omits the end operation. The partial
executor reports its cursor after the last operation for `find` and `lookingAt`;
`matches` accepts it only at the logical input end. Partial plans
mark each run whose continuation is nullable, meaning some path through the
remaining operations succeeds on every suffix. A boolean call that requests
neither boundaries nor captures, and is not a full match, returns success once
such a run has matched its minimum characters. It never scans the rest of the
input. End-anchored plans and `matches` never take this exit, because their
end check can still fail. The dot-all tail advances directly to the logical
input end. Ordinary dot scans for LF; a single terminal LF is left outside the
match for `$`, but is rejected by strict end or a full-match operation.
Replacement preserves any unmatched suffix. Malformed input follows Trino's
garbage-in, garbage-out contract; no UTF-8 validation pass or exact agreement on
malformed bytes is promised. RE2's UTF-8 contract and Java's current execution
remain unchanged.

## Correctness and measurement

`TestTrinoScanPlan` verifies selection, Joni match/capture agreement, optional
retry, final LF, strict end, replacement versus extraction, multibyte input,
NUL, bounded reads on malformed input, regions, memory fallback, and concurrent
use of one compiled plan. Trino function and matcher tests also apply.

`BenchmarkTrinoScanPlan` rotates 504 Slice inputs. Each nonempty input is a view
at a nonzero array offset; the 63 empty `LITERAL_CONTROLS` inputs are the shared
empty Slice at offset zero. The URL, mixed, long-path, and long-host workloads
use the exact [ClickBench q29](CLICKBENCH_REGEXP.md) pattern. `URL_SHORT`,
`URL_128`, and `URL_4096` pad the same URL inputs to 48, 128, and 4,096 bytes,
separating fixed per-call costs from input-length scaling. Family and diverse
workloads interleave twelve plans through the same executor. Fallback uses an
unsupported unanchored captured alternation. All results are returned or reduced
to capture-offset checksums. `ANCHORED_PREFIX` and `ANCHORED_QUERY` cover
ClickHouse's capture-free URL prefixes and use group zero for extraction and
replacement. `ANCHORED_HOST` and `ANCHORED_WWW_HOST` cover its partial hostname
captures. Their input mixes include misses, optional `www.`, missing slashes,
Unicode, query markers, and newlines. `ANCHORED_HOST_4096` runs the
`ANCHORED_HOST` pattern over 4 KiB hosts with no path separator. Its trailing
run can reach the input end, so `contains` and `count` measure the boolean exit
after the run's minimum. `SETS`
exercises a small complemented set and a larger ASCII table.
`LITERAL_CONTROLS` protects the direct `^foo` prefix and `^foo$`
equality routes. `BOUNDED` includes
character-counted multibyte matches and rejections. `LONG_SET` and `LONG_ASCII`
separate the vector and table scaling paths. `SET_MIXED` interleaves twelve
plans across the single-delimiter operation and three general-run shapes.
`OPTIONALS` adds captured optional `www.` and port components. `OPTIONAL_RETRY`
forces late failures through four optional captures; `OPTIONAL_MIXED`
interleaves twelve plans across simple and richer bodies. `LONG_OPTIONAL` adds a
4 KiB path to distinguish ordinary dot scanning from dot-all end advancement.
`RETRY_64`, `RETRY_127`, `RETRY_128`, `RETRY_256`, and `LONG_RETRY` use the
four-optional expression with exact input byte lengths, including a 4 KiB case.
They cover short-input routing and long-hostname scaling.
`LOWERED_FALLBACK` protects the lowered unanchored final-LF boolean route.
Compilation is separate from warm operation methods;
there is no cache lookup or SQL grouping/aggregation in this benchmark.

Compare the unchanged revision with the candidate on the same AWS host using
identical benchmark classes, inputs, operation arguments, JVM settings, and
result checks. Measure dot-all and ordinary-dot separately. Include compilation
and fallback costs, allocation, and both native-access modes. Short development
runs are directional, not final qualification. Required architecture evidence
and final qualification remain governed by [METHODOLOGY.md](METHODOLOGY.md).

Do not expand coverage based solely on aggregate speedups. Preserve the URL and
mixed-plan controls at every stage. If a richer operation increases ordinary
execution cost, compare a separate executor route before adding complexity to
the common dispatch loop. Runtime bytecode generation is not part of this plan.

`BenchmarkTrinoScanProfiles` derives every pattern/input pair from the
`OPTIONAL_MIXED` workload. `INTERLEAVED` preserves its row order; `GROUPED`
visits the same 504 pairs grouped by pattern. `PLAN_0` through `PLAN_11` each
repeat one pattern's 42 inputs twelve times, preserving the same timed array
length. Every configuration uses the same per-row input, pattern, and reusable
matcher arrays. Setup does no matching that could pretrain the scan executor.

Compare an equal-weight mean of all twelve individual-pattern timings with the
interleaved and grouped timings. Grouping changes input order while retaining
the same pattern frequencies; it does not guarantee identical JIT decisions.
Each parameter runs in its own JVM. Public boolean, reused capture matching,
and extraction consume their results separately. `BenchmarkTrinoScanPlan`'s
`OPTIONAL_MIXED` workload must remain in the comparison because changing the
benchmark caller can change the compiled code and the size of the observed
regression.

Development measurements, rejected candidates, and raw evidence for this
design are in the private archive; see the
[archive index](trino-scan-plan-archives.json). Accepted trade-offs and rejected
alternatives are recorded in
[`REGULATOR_DECISIONS.md`](../../REGULATOR_DECISIONS.md#trino-specialized-byte-scans).

## Execution width and safeguards

The scanners use 128-bit vectors on all hosts. This matches the Graviton vector
width and uses the same scan width on Intel. Wider Intel vectors may help long
scans, but have not been selected or shown to improve these integrated
workloads. Treat preferred-width selection as a separate measured change.

Named operation factories define each operation's step layout. Retry-stack
limits are checked at class initialization against the two position longs and
six-bit operation indices. Fixed instance sizes are cached, and compilation
computes each plan's retained size once before budget charging. Seeded
generated-pattern tests include successful witnesses and mutations, so capture
participation and retry behavior receive coverage alongside rejection.
