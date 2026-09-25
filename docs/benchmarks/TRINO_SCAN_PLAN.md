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
| Start behavior | Logical input start, or captured unanchored search with a variable run and a leading literal or unbounded run of every byte except the one-byte literal after it |
| End assertions | Optional; strict end and Trino final-LF `$` are supported when present |
| Terminal behavior | Partial-match success, dot-all `.*`, LF-excluding `.*`, and supported character-set runs |
| Case folding | Restricted ASCII literal and run folds proven safe against the Unicode fold tables; separate folded executors; general Unicode folding uses the ordinary engine |
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

Unanchored coverage includes the URL family and a delimiter-complement run
family such as `([^/]+)/`. Any extension must preserve the Trino language
contract. Unsupported optimization shapes use the ordinary Regulator engine;
this is not fallback to another regex language or to Joni.

## Integration

`TrinoScanPlan.analyze` reads the parsed expression before required-prefix
stripping. It requires proof that a successful match consumes input. An
unanchored plan must begin, after capture saves, with a literal or an unbounded
run of every byte except the one-byte
case-sensitive literal that follows it, such as `([^/]+)/`. It must also contain
a capture and a variable run so the capture-free and fixed-width routes
retain precedence. Other leading runs, such as `(\d+)zz`, `([0-9]+)a`, and
`(\w+)@(\w+)`, keep the ordinary engine: each failed candidate inside a run
rescans the rest of it, so their cost depends on the input, and every measured
search strategy for them made some workloads slower than the ordinary engine. A
delimiter-complement run stops only at its delimiter, which the literal then
matches, and it measured no loss. End assertions and captures are optional for
anchored plans. Capture-free plans must contain a character run, so literal-only
expressions retain the direct prefix and
equality routes. The single-delimiter operation consumes its delimiter directly.
General runs leave the following literal to its own operation. One builder
handles both linear and richer optional and capture structure. After
continuation verification, a peephole pass compacts linear anchored-start plans
into delimiter-capture and terminal-tail operations. Unanchored plans and plans
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
and count calls reach the plan through its own strategy either way. Unanchored
plans count with a loop over the plan's search that resumes at each match end,
not the DFA count, so `count` agrees with `extract`, `position`, and
`extractAll` on malformed bytes.

Every unanchored plan search has a work budget. A failed attempt
reports the furthest byte any of its unbounded scans reached, and the search
adds that byte's distance from the attempt's start; literal and bounded checks,
whose length the pattern limits, are not counted. Once the sum exceeds 2048
bytes plus four times the distance the search has advanced, the search stops
where the plan would resume and continues on the ordinary engine with the same
logical context, anchor, and capture offsets. No match starts before that
position. This bounds inputs where every candidate rescans the same long run,
such as `id=([^&]+)&x` over repeated `id=a`, so the work of each search is
linear in its input. Anchored searches make one attempt and never hand off. Each
search, including each step of a find loop and of `count`, starts with a fresh
budget. The engines agree on valid UTF-8. On malformed input, a search that
hands off may report the ordinary engine's match rather than the plan's.
Boolean, boundary, capture, and count calls apply the same budget from the same
search positions, so all calls still report one match
sequence. As with the ordinary engine and upstream RE2, iterating over every
match can still take quadratic time when each successful search reads far past
the start of its match.

A shallow leading-operation check rejects clearly unsupported unanchored shapes
before allocating the builder, and known nullable expressions do not enter scan
analysis. Matcher and function code own reset, retained groups, failure state,
Slice-relative offsets, zero-copy extraction, replacement parsing, and output
construction.

End-anchored, anchored partial, and unanchored plans use separate Java executor
methods over the same operations. This keeps their completion and retry profiles
out of the end-anchored loop. Literals of up to 16 bytes use precomputed
first/last word comparisons, masked for folded literals. Longer case-sensitive
literals use `Arrays.mismatch`, and longer folded literals use a scalar loop.
Delimiter/newline scans use 128-bit vectors with a scalar tail. Four optional
checkpoints fit in local primitive values; no retry array or boolean workspace
is allocated.

The richer builder emits capture-boundary saves and forward optional forks. The
linear-only compaction pass runs only for anchored-start plans. It folds a
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
`matches` accepts it only at the logical input end. Partial and unanchored plans
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

For unanchored literal-leading plans, the executor scans for the literal's first
byte and verifies the complete attempt only at those candidates. A valid UTF-8
continuation byte cannot equal the first byte of a UTF-8 literal. For a
case-sensitive leading literal of two or more bytes, the first-byte scan is
followed by a scalar check of the remaining literal bytes before each attempt.
An occurrence of the first byte alone starts no attempt, the scan resumes after
it, and a literal that does not fit before the logical end is never a candidate.
ASCII-folded leading literals use the first-byte scan alone. For a leading
unbounded run, a failed attempt resumes at the run end. Every interior start
would reach the same greedy endpoint and has a subset of the already rejected
continuation choices. When the run consumes nothing, the next candidate advances
by one decoded code point so a valid multibyte character is never retried from a
continuation byte. Optional-leading plans, and plans led by any run other than a
delimiter complement, remain on the ordinary engine. A lowered final-line plan
retains precedence for a run-leading expression; a literal-leading scan keeps
its candidate-byte route.

## Correctness and measurement

`TestTrinoScanPlan` verifies selection, Joni match/capture agreement, optional
retry, final LF, strict end, replacement versus extraction, multibyte input,
NUL, bounded reads on malformed input, regions, memory fallback, and concurrent
use of one compiled plan. Seeded unanchored cases also cover repeated `find`,
valid UTF-8 candidate boundaries, and leading-run skips. An attempt-count
diagnostic confirms that literal-leading searches attempt only at complete
occurrences of the leading literal. Trino function and matcher tests also apply.

A deterministic Java 25 allocation guard measures optional-leading and
run-leading final-line rejections before the builder is entered. Deeper
candidates still pay for continuation analysis before rejection; target-host
compile rows remain the evidence for complete compilation cost.

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
after the run's minimum. `UNANCHORED_TAIL_4096` runs `abc(.*)` over 4 KiB inputs
with no newline. Its `contains` measures the kept literal kernel, and its
`count` measures the plan's own count, which needs match boundaries. `SETS`
exercises a small complemented set and a larger ASCII table.
`LITERAL_CONTROLS` protects the direct `^foo` prefix and `^foo$`
equality routes. `FOLDED_ANCHORED`, `FOLDED_OPTIONAL`,
`FOLDED_UNANCHORED`, and `FOLDED_MIXED` select the ASCII-folded executors with
folded literals, a folded optional literal, an unanchored folded header name,
and a twelve-plan mix. `FOLDED_RUN` folds only a character class, which becomes
an ordinary set, so it selects the case-sensitive executor. `BOUNDED` includes
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
`UNANCHORED_URL` and `UNANCHORED_END` vary prefix length, URL outcomes, Unicode,
newline, and end behavior. `UNANCHORED_RUN` covers leading-run matches and
failures, `LONG_UNANCHORED_RUN` uses 4 KiB digit runs, and
`UNANCHORED_RUN_LEADING` interleaves `(\d+)zz`, `([0-9]+)a`, and `(\w+)@(\w+)`
over prose with scattered short digit runs where one row in eight matches.
These three leading-run workloads select no plan and measure the ordinary
engine on the shapes the plan excludes. `UNANCHORED_MIXED` interleaves twelve
literal-leading plans. Compilation is separate from warm operation methods;
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
