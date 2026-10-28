# Specialized scan plan

The scan plan recognizes a bounded family of Trino, RE2, Java, and Rust
patterns and executes literals, deterministic character runs, and captures over
Slice bytes. It targets `contains`, match boundaries, captures, extraction, and
replacement through the public APIs. RE2, Java, and Rust plans also admit runs
of sets of some non-ASCII characters and report the ordinary engine's results
on malformed input too, as described in [RE2 patterns](#re2-patterns).

## Coverage

The comparison target is ClickHouse `v26.8.2.7-lts`, specifically
[`RegexpProgram.cpp`](https://github.com/ClickHouse/ClickHouse/blob/v26.8.2.7-lts/src/Common/RegexpJIT/RegexpProgram.cpp)
and [`CompileRegexp.cpp`](https://github.com/ClickHouse/ClickHouse/blob/v26.8.2.7-lts/src/Interpreters/JIT/CompileRegexp.cpp).
Coverage means accepting an equivalent expression under the target language's
contract, not importing RE2 syntax or changing Trino semantics.

| Feature | Coverage |
|---|---|
| Case-sensitive UTF-8 literals | 1–256 bytes per literal |
| Greedy optional fixed literals | Up to four, sharing one budget with optional forks and literal-repeat copies |
| Character runs | ASCII sets and their complements, and in RE2, Java, and Rust plans sets of some non-ASCII characters; variable counts require disjoint continuation through captures and optional branches |
| Captures | Capture-free plans plus mandatory, nested, and optional captures; named groups also work; up to 16 subject to the operation limit |
| Optional bodies | Literals, runs, captures, and nested optionals; at most four forks, sharing the optional budget |
| Repetitions | Greedy single-character `?`, `*`, `+`, exact and bounded counts up to 1000; unbounded minimum counts; exact and finite capture-free literal-sequence repeats |
| Start behavior | Logical input start, or captured unanchored search with a variable run and a leading literal, exact one-byte ASCII set, or unbounded run of every byte except the one-byte literal after it; in RE2, Java, and Rust plans also one character of a set of some non-ASCII characters, or an unbounded run of such a set holding every ASCII byte except that literal |
| End assertions | Optional; strict end, final-LF `$` (Trino's, or Java's under `UNIX_LINES`), and Java's final-terminator `$` are supported when present |
| Terminal behavior | Partial-match success, dot-all `.*`, LF-excluding `.*`, and supported character-set runs |
| Case folding | Restricted ASCII literal and run folds proven safe against the Unicode fold tables, and ASCII letter pairs such as `[Kk]`, which match only their two letters; separate folded executors; in RE2, Java, and Rust plans, folds that add non-ASCII members, such as `k` with the Kelvin sign, as sets of some non-ASCII characters; other Unicode folding uses the ordinary engine |
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

Unanchored coverage includes the URL family, a delimiter-complement run family
such as `([^/]+)/`, and ClickHouse's `[?&]([^=]+)=` query-key shape.
Fixed literal-sequence repeats are also admitted within the bounded limits
below. Any extension must preserve each language's contract. Unsupported
optimization shapes use the ordinary Regulator engine; this is not fallback to
another regex language or to Joni.

## Integration

`ScanPlan.analyze` reads the parsed expression before required-prefix
stripping. It requires proof that a successful match consumes input. An
unanchored plan must begin, after capture saves, with a literal, an exact
one-byte ASCII set, or an unbounded run of every byte except the one-byte
case-sensitive literal that follows it, such as `([^/]+)/`; an RE2, Java, or
Rust plan may also begin with a set of some non-ASCII characters, as
[RE2 patterns](#re2-patterns) describes. It must also contain
a capture and a variable run so the capture-free and fixed-width routes
retain precedence. Other leading runs, such as `(\d+)zz`, `([0-9]+)a`, and
`(\w+)@(\w+)`, keep the ordinary engine: each failed candidate inside a run
rescans the rest of it, so their cost depends on the input, and every measured
search strategy for them made some workloads slower than the ordinary engine. A
delimiter-complement run stops only at its delimiter, which the literal then
matches, and it measured no loss. End assertions and captures are optional for
anchored plans. Capture-free plans must contain a character run or a variable
literal repeat, so literal-only expressions retain the direct prefix and
equality routes. The single-delimiter operation consumes its delimiter directly.
General runs leave the following literal to its own operation. One builder
handles both linear and richer optional and capture structure. After
continuation verification, a peephole pass compacts linear anchored-start plans
into delimiter-capture and terminal-tail operations. Unanchored plans and plans
with general forks, nested capture boundaries, or more than three captures keep
the general representation with explicit capture saves.

`ScanPlanRun` admits ASCII sets and sets containing all non-ASCII
characters plus any subset of ASCII. These sets have uniform membership for
non-ASCII UTF-8 bytes. In a Trino plan, other Unicode classes retain ordinary
engine execution; an RE2, Java, or Rust plan also admits them, reading their
non-ASCII members by exact encodings, as [RE2 patterns](#re2-patterns)
describes. Small byte sets or their complements, up to four bytes, use vector
equality scans. Larger sets use a scalar membership table.

A variable count must be disjoint from every possible first byte of its
continuation. The richer builder propagates these sets backward through capture
boundaries and both arms of optional groups. Analysis stores each
possible-first-byte set in four 64-bit words; run scanning at execution time
uses the run's own membership table. This admits a hostname followed
by an optional port or a required slash when the hostname set excludes both
colon and slash. It excludes runs that might need to give characters back.
An exact count has only one endpoint and can precede overlapping text. Exact
and finite repetitions of a fixed literal sequence lower to a literal holding
the minimum number of copies, omitted when the minimum is zero, followed by
greedy optional copies. Optional copies draw on the same four-checkpoint budget
as optional literals and forks, and the mandatory literal is limited to 256
bytes. Capture-free variable literal repetitions may select the plan, while
exact literal-only expressions retain their direct routes. Repeated captures,
unbounded literal sequences, nested repetitions, and other repeated
multi-operation bodies remain unsupported.

Finite bounds count characters. ASCII runs can count bytes; runs admitting
non-ASCII characters in a Trino plan use the shared UTF-8 decoder. An unbounded
minimum such as `{2,}` decodes only enough characters to establish the minimum,
then scans the remainder by bytes. In a Trino plan, malformed input still has
no promised exact result; the decoder is a bounded character-advancement
mechanism, not a validation pass.

`Re2` retains the immutable plan in its plan bundle and charges the
complete plan and literal storage against the forward DFA budget. If the budget
does not fit, compilation retains the ordinary route. The semantic program is
kept for range-limited calls outside the scan plan's supported context.

Plan execution uses its own strategy byte, `scanPlanStrategy`, separate from
the boolean `find` strategy; both fit in the 96-byte `Re2` layout.
Shared capture matching checks the plan strategy byte before loading the plan,
avoiding an extra dependent plan lookup for unrelated patterns. It invokes the
plan before required-prefix stripping. Full-Slice boolean `find` keeps the
literal `CONTAINS`, `STARTS_WITH`, `EQUALS`, and `EQUALS_FINAL_LINE` kernels
when a plan also exists, because they answer without reading past the literal.
Any other boolean strategy is replaced by the plan. Boundary, capture, range,
and count calls reach the plan through its own strategy either way. Unanchored
plans count with a loop over the plan's search that resumes at each match end,
not the DFA count. For a Trino plan this keeps `count` in agreement with
`extract`, `position`, and `extractAll` on malformed bytes; an RE2, Java, or
Rust plan agrees with the ordinary engine anyway, and its count takes the plan
route as `find` does.

A start-anchored plan, end-anchored or partial, matches only at the region
start, and no `Re2Matcher.find` check runs ahead of it (except a Rust matcher
over a partial region; see [RE2 patterns](#re2-patterns), and a Java matcher
region whose `$` reads a CR before it, which runs on the NFA). Its matchers
therefore call the plan entry directly, as does `extract` of a capture group,
which also skips building a matcher. This removes the shared path's out-of-line
calls from the capture path. Matchers given a single-byte matcher, `matches`,
`lookingAt`, and unanchored plans keep shared capture matching.

Boolean `find` and `count` of a start-anchored plan run on the ordinary engine's
DFA instead of the plan when the input is at most 48 bytes and the plan has an
optional fork and no boolean tail, a run whose continuation succeeds on every
suffix. A fork retries its continuation, and each retry dispatches the
continuation's operations again, even in the boolean plan without capture saves
described below. Without a boolean tail the attempt reads as far as the DFA, so
on a short input the plan only adds dispatch. Longer inputs, and calls that
return boundaries or captures, run the plan. Because the input length picks the
engine, both engines must agree on every input, as for the density handoff
below: an RE2, Java, or Rust plan always does, and a Trino plan does when every
consuming operation accepts only ASCII bytes. The pattern's boolean route
without a plan must also be the DFA, so a Trino final-line end or a Java
final-terminator end, which the DFA cannot evaluate, keeps the plan. In
`BenchmarkRe2ScanPlan`, `OPTIONALS`, `OPTIONAL_RETRY`, and the forked
`OPTIONAL_MIXED` plans take this route on every row; no `BenchmarkTrinoScanPlan`
or ClickBench plan does. On R9g, R8g, and R8i the routed `OPTIONALS` and
`OPTIONAL_RETRY` rows take 0.99 to 1.03 times the `scanPlan=false` time.
`RETRY_64`, the first row above the limit, is unchanged, and on R8i it breaks
even with `scanPlan=false`.

Boolean unanchored search may continue on the ordinary engine when the plan
does not begin with a literal and every consuming operation accepts only ASCII
bytes. No match of such a plan in either engine can include a byte at or above
`0x80`, so those bytes, valid or malformed, only separate the ASCII segments in
which both engines search. The two engines therefore make the same existence
decision on any byte sequence. Their agreement on valid UTF-8 is covered by the
Joni differential tests. Full-Slice `find` starts on the plan, which answers
sparse and early matches without DFA setup. After each group of eight failed
attempts, it hands off once if those failures average fewer than 32 bytes apart
and at least 256 bytes remain. The ordinary engine then continues from the
position where the plan would resume, with the complete Slice as context,
reaching a match without trying each dense candidate. No match starts before
that position, because the plan rejected every earlier candidate. Ranged boolean
`find` uses the ordinary engine directly. Calls that return boundaries or
captures, and `count`, do not use this density handoff. Literal-leading plans
also skip it, because their candidate scan already skips to the literal.

Every unanchored search of any plan also has a work budget. A failed attempt
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
search positions, and the density handoff and ranged boolean `find` apply only
to plans on which both engines agree, so all calls still report one match
sequence. As with the ordinary engine and upstream RE2, iterating over every
match can still take quadratic time when each successful search reads far past
the start of its match.

A run-leading unanchored plan records the longest case-sensitive literal that
every match must consume: a literal after the leading run and after the skip
target of every optional fork. ASCII-folded literals are not recorded. After the
first failed attempt, the search checks once whether that literal occurs between
the resume position and the logical end, and stops when it does not. Every later
match lies in that range and consumes the literal, so the check never changes a
result, including in regions. Checking after the first failure keeps the scan
off calls whose first candidate matches, such as each step of a dense match
loop. Literal-leading plans record no required literal. The check filters on the
first and last literal bytes with 128-bit vectors before verifying the literal.
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
unnecessary. Optional literals, forks, and literal-repeat copies share one
budget of four retry checkpoints, so an attempt explores at most sixteen
combinations, independent of input length. The builder also limits nesting depth
to sixteen and operations to thirty-two plus one terminal operation. Failed
calls clear caller-provided capture offsets. A compile-time nullability check
excludes whole-pattern empty matching.

A start-anchored plan with a fork also keeps a boolean plan, the same operations
without the saves, with fork targets and boolean-tail marks recomputed, which
the pattern resolves when it compiles. Full-Slice boolean `find` and the count
of a start-anchored plan, which makes one attempt, run it through the same
executors, so no method that reports positions changes. On the four-optional
retry rows that still run the plan, Trino ones and RE2 ones over 48 bytes, an
attempt dispatches 28 operations instead of 50 on a retried row and 12 instead
of 24 on a direct one. On R9g, R8g, and R8i, RE2 `RETRY_64` `contains` takes
0.75 to 0.94 times its time with saves, where R8i's 0.938 is within noise and a
rebuild of the same source measured 0.913, and Trino `OPTIONALS` `contains`
0.80 to 0.84 times. Trino `OPTIONAL_MIXED` `contains` takes 0.59 times on R8i
and 0.77 to 0.85 times, within noise, on Graviton. On R8i,
`BenchmarkTrinoScanProfiles` `contains` takes 0.53 to 0.66 times its time,
which turns a 1.55 to 1.75 times loss against the same rows without the plan
into 0.93 to 1.05 times; `GROUPED`, at 1.047 times, remains a loss. No row that
reports positions changed on Graviton; on R8i, RE2 `LONG_RETRY` extraction took
1.05 times its time on bytecode-identical code. Plans that are their own
boolean plan are within noise: on R9g, R8g, and R8i, RE2 `ANCHORED_HOST`
`contains` takes 0.998, 0.998, and 1.013 times its time without the boolean
plan, ClickBench `HTTP` 0.990 to 1.000 times, and Trino `URL` 0.997 to 1.000
times; ClickBench `WWW` is bimodal and within noise. Running the count's attempt
as a boolean `find` keeps the RE2 `RETRY_64` `count` win, 0.77, 0.76, and 0.93
times, and makes RE2 `ANCHORED_HOST` and `ANCHORED_QUERY` `count` take 0.95 to
0.97 times.

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
malformed bytes is promised. RE2, Java, and Rust plans agree with the ordinary
engine on malformed input, as described in [RE2 patterns](#re2-patterns).

For unanchored plans that begin with a literal or with a run whose minimum is at
least one, the executor scans for a byte that can start the leading operation
and verifies the complete attempt only at those positions. An attempt at any
skipped byte would fail on its first character. For a case-sensitive leading
literal of two or more bytes, the first-byte scan is followed by a scalar check
of the remaining literal bytes before each attempt. An occurrence of the first
byte alone starts no attempt, the scan resumes after it, and a literal that does
not fit before the logical end is never a candidate. ASCII-folded leading
literals use the first-byte scan alone. A literal or ASCII-only set
cannot match a valid UTF-8 continuation byte. A leading run set that admits
every non-ASCII character, as any Trino set that admits one does, admits every
non-ASCII byte, so from a character boundary its first candidate is an ASCII
byte or a lead byte. A set of only some non-ASCII characters, in an RE2, Java,
or Rust plan, has as member bytes its ASCII members and the lead bytes of its
members' encodings, never a continuation byte, so its candidate scan finds every
byte that can start one of its characters, and the attempt reads the character
there by the set's exact encodings; `(?i)key=` finds `K`, `k`, and `E2`, the
lead of the Kelvin sign. On malformed input the scan makes the same attempts as
trying each position, because a failed position advances only over bytes that
cannot start the run. Candidate scans first test one byte, or
sixteen bytes for sets scanned with nibble tables, because dense inputs and
large sets such as word characters usually have a candidate within a few bytes
of the cursor. They then reuse the DFA's start-byte scanners when the Vector API
is available. Sets of one to three bytes compare each byte for equality.
Contiguous ranges of four to 64 bytes use a range comparison. Other sets of at
most 64 bytes use nibble lookup tables, which are charged to the plan's retained
size. Larger sets and sets of every non-ASCII character use the plan's own scan:
complements of small sets use vector equality, and other sets use the byte
table. A leading run whose minimum is zero can match anywhere, so every position
is attempted. For a leading unbounded run, a failed attempt resumes at the run
end. Every interior start would reach the same greedy endpoint and has a subset
of the already rejected continuation choices. When the run fails or consumes
nothing, the next candidate advances by one decoded code point so a valid
multibyte character is never retried from a continuation byte. A leading single
character, such as `[A-Za-z_]` in `([A-Za-z_][A-Za-z0-9_]*)=`, resumes instead
at the end of a following unbounded run whose minimum is zero, whose set
contains the leading set, and which only capture saves separate from the leading
character. Runs never give characters back, and each byte that run consumed is a
member of its set, so an attempt from any of those bytes either fails on its
first character or consumes one leading character and then reaches the same run
end in the same state. A Trino run set holds every non-ASCII byte if it holds
one, and a decoded character never spans an ASCII byte, so this also holds on
malformed input. An RE2, Java, or Rust run reads a non-ASCII character only from
a lead byte, whose value fixes its width, so a start inside a character that
either run read is a continuation byte, on which the leading character fails,
and from any other start both runs read the same characters to the same end.
Other operations between the runs, a bounded or positive-minimum following run,
or a following set that omits a leading member keep the one-character resume.
Optional-leading plans, and plans led by any run other than a single character
or a delimiter complement, remain on the ordinary engine. A lowered final-line
plan retains precedence for a run-leading expression; a literal-leading scan
keeps its candidate-byte route.

## RE2 patterns

The frontend chooses a plan's language: Trino, RE2, Java, Rust, or none.
`Re2.compile` analyzes every pattern with the same builder, proofs, budgets,
and candidate scans as `TrinoRegexp`, so the same shapes select a plan,
ASCII-folded ones included, and every `Re2` operation reaches the plan through
the same strategies with the same memory charge. Unlike Trino, RE2, Java, and
Rust also admit runs of sets of some non-ASCII characters, described below.
`Re2.compileParsed` builds no plan. RE2 also excludes longest-match patterns,
whose matches a leftmost-first plan cannot report, and, as Trino does, Latin-1
patterns. RE2's `$` is the strict end, so the final-LF forms never arise, and
RE2's simple case folding has no multi-character folds for the ASCII-fold proof
to exclude.
`Re2.Options.setGenerateBytecode(true)` compiles a case-sensitive RE2 plan to
its own class, as [RE2 executor classes](#re2-executor-classes) describes.

`JavaRegexp.compile` analyzes every pattern the same way, and a Java plan reads
input by the RE2 rule below, so it reports what the ordinary engine reports on
every input, which on valid UTF-8 is the JDK's result. A plan's end is the end
of input for `\z`, also the position before a final LF for `UNIX_LINES` `$`
and `\Z`, and for Java's default `$` and `\Z` also the position before one
final LF, CR, CR LF, U+0085, U+2028, or U+2029, but not between a final CR and
LF. The plan compares the terminator's exact bytes, which the ordinary engine
decodes strictly, so the two agree on malformed input. The JDK gives back run
characters to reach that position, as `^x([^\n]+)$` gives back the CR of a
final CR LF, and a run never gives back. A variable run that can reach a Java
end without consuming more input must therefore admit no line terminator, or
admit every one and be unbounded, so `^x([^/]+)$`, `^\w+$`, and `(?s)^x(.*)$`
select a plan while `^x([^\n]+)$`, `^x(\S+)$`, and `(?s)^x(.{1,3})$` keep the
ordinary engine. A literal or fixed-count run before the end needs no such rule,
and neither does an optional's fork, though a variable run inside the optional
keeps it. Java's default dot admits no line terminator, so it reaches a plan,
before `$` too, as a set of some non-ASCII characters, described below, as do
folds with a non-ASCII member, such as `(?iu)k`. As in Trino, multiline anchors,
word boundaries, and reluctant runs keep the ordinary engine; Java's `(?i)`
without Unicode case folds each letter as its ASCII pair, so `(?i)s` and `(?i)k`
reach a plan.
`JavaRegexp.Options.setGenerateBytecode(true)` compiles a case-sensitive Java
plan to an RE2 executor class, as [RE2 executor classes](#re2-executor-classes)
describes.

`RustRegexp.compile` analyzes every pattern the same way, and a Rust plan reads
input by the RE2 rule below, so it reports what the ordinary engine reports on
every input, which on valid UTF-8 is the crate's result. Without multi-line mode
Rust's `$` is the strict end, and Rust's dot and `(?i)` match as RE2's do, so
those reach a plan unchanged, as do runs that `(?U)` makes greedy and the
CRLF-mode dot. Byte mode builds no plan. Only byte constructs carry the Latin-1
flag the analysis checks, so the analysis alone would still admit an ASCII-only
byte-mode pattern. Multiline anchors, CRLF ones included, word boundaries,
including Rust's start, end, and half word boundaries, and reluctant runs keep
the ordinary engine, while Unicode Perl classes such as `\w` and folds with a
non-ASCII member, such as `(?i)k`, reach a plan as sets of some non-ASCII
characters. A Rust range or matcher region keeps the whole input
as assertion context, so a matcher runs its start-anchored plan directly only
over the whole input; a partial region reaches the plan, as a range does, only
when it ends at the input end.
`RustRegexp.Options.setGenerateBytecode(true)` compiles a case-sensitive Rust
plan to an RE2 executor class, as [RE2 executor classes](#re2-executor-classes)
describes.

An RE2 plan reports exactly what the ordinary engine reports on every input,
including malformed UTF-8. A class holding every character from U+0080 through
U+10FFFF compiles to byte ranges that consume a lead byte `C2`-`DF` followed by
one continuation byte, `E0`-`EF` followed by two, or `F0`-`F4` followed by
three. They accept overlong three- and four-byte forms, encoded surrogates, and
values above U+10FFFF, which the strict decoder behind Trino plans rejects, and
they reject bare continuation bytes, `C0`, `C1`, `F5`-`FF`, and truncated
sequences. Every other class compiles its non-ASCII ranges to exact encodings:
the compiler splits each range into pieces of one encoded length, each with one
inclusive byte range per position. The pieces accept the encoding of each
member, an encoded surrogate included when the class holds it, and reject every
other sequence.

Only operations that can consume a byte at or above `0x80` read input
differently. Literals, folded literals, ASCII-only runs, capture saves, forks,
and the end check keep their behavior and are shared. The builder then
replaces:

- Each run whose set admits every non-ASCII character with an `RE2_RUN`. It
  scans ASCII members, with the vector scanner when the run has at most four
  ASCII non-members, the Vector API is available, and at least a vector of input
  remains, and otherwise by byte as Trino's run scan finishes its tail,
  stopping at any non-ASCII byte or an ASCII non-member. It validates each
  non-ASCII sequence by the rule above and counts bounded repetitions and
  minimums by the same rule.
- Each run whose set admits only some non-ASCII characters, such as Java's
  default dot, Rust's Unicode `\w`, or `(?i)k`, which holds the Kelvin sign,
  with an `RE2_SET_RUN`. Its member bytes are its ASCII members and the lead
  bytes of its non-ASCII members' encodings, never a continuation byte. A table
  built from the compiler's own split of the set's ranges reads each non-ASCII
  character, so it accepts exactly the sequences the ordinary engine's byte
  ranges do. A lead byte whose every encoding is a member, as most leads of
  Java's default dot are, has no trie nodes. Its entry holds the range of its
  second byte, which, as in the split, excludes overlong forms, values above
  U+10FFFF, and surrogates the set does not hold; the reader then checks only
  that the remaining bytes are continuation bytes. Other lead bytes walk a byte
  trie, one table read per byte; identical trie nodes are stored once, which
  keeps Rust's `\w` to about 12 KB, charged to the plan's retained size. The
  trie is built in place, so the shape of UTF-8 bounds its storage however
  many pieces the compiler splits the set into. The reader takes a character's
  width from its lead byte rather than from the table, so finding the next
  character does not wait on the table read. It scans
  ASCII bytes as an `RE2_RUN` does, with the vector scanner when the set has at
  most four ASCII non-members, so ASCII input never reads the table, and counts
  bounded repetitions and minimums with the same reader. A captured run of such
  a set stays a run, never a delimited capture.
- Each delimited capture with an `RE2_DELIMITED_CAPTURE`, which scans its run by
  that rule and then requires the delimiter, rather than searching for the
  delimiter byte, which could pass over a rejected sequence.
- Each terminal tail with its run followed by the end check, so a tail stops at
  a rejected sequence rather than jumping to the end. The operation count is the
  one a Trino plan has.

The folded executors run the same operations. The leading complement scan, such
as that of `([^/]+)/`, still finds candidates by byte; its run may now stop at a
rejected sequence before the delimiter, and the attempt then fails and resumes
at the run end. Trino plans contain none of these operations; a generated RE2
class calls copies of the RE2 run reader and the member reader for them.

Every skip proof holds under this rule:

- A literal-leading candidate scan skips only positions where the literal does
  not start. A run-leading one whose set holds every non-ASCII character skips
  only ASCII bytes outside the run, since it admits every byte at or above
  `0x80`. A set of some non-ASCII characters skips only bytes that start none of
  its characters: its member bytes above `0x7F` are the lead bytes of its
  members' encodings, and a character of it starts at one of them or at an ASCII
  member. Each candidate scan visits a superset of the possible starts, in
  increasing order, and the first start that matches is the ordinary engine's
  leftmost one.
- A set of some non-ASCII characters leads an unanchored plan only as a single
  character, such as `(?i)s([a-z]+)=`, `[éè]([a-z]+)=`, or Rust's
  `\d([0-9]+)=`, or as an unbounded run of every ASCII byte except the one-byte
  literal after it, such as `([^/\x{85}]+)/`. That run stops at the literal,
  or at a non-ASCII sequence it does not read, where the literal fails. The
  search resumes at the run end, where that sequence's lead byte can also start
  members and is attempted once more, so with a minimum of at most one, each
  stop costs at most two failed attempts and none rescans the run. A larger
  minimum adds failed attempts only inside a run shorter than it, each reading
  fewer member characters than the minimum, so for a given pattern the cost
  stays linear in the input. Other variable leading runs of such sets, such as
  Rust's `(\d+)zz`, keep the ordinary engine, as ASCII ones do.
- A failed or empty attempt advances by one strictly decoded character. A
  strictly valid sequence is one RE2 character of the same width, and a
  continuation byte inside it starts no RE2 match, while an invalid sequence
  advances one byte, so no start is skipped. A leading set of some non-ASCII
  characters that fails at a lead byte whose sequence it does not read, as
  `(?i)k` does at the first byte of `E2 E2 84 AA`, therefore retries at the
  next byte, where the Kelvin sign starts.
- Resuming at a leading run's end, and after a leading single character, holds
  because RE2's reading is deterministic from a character start: an attempt
  from any byte the run consumed either starts no match at a continuation byte
  or reaches the same run end in the same state. Every RE2 run, whether it reads
  a set's exact encodings or every non-ASCII character, takes a character's
  width from its lead byte, so a leading ASCII character and a following set of
  some non-ASCII characters that contains it read the same characters from any
  character start, as do a leading character of some non-ASCII characters and a
  following run of every non-ASCII character. A leading single character of such
  a set reads only exact encodings, so the bytes after its start that a failed
  attempt skips are that character's continuation bytes.
- A variable run stops where a backtracking match would because its member
  bytes are disjoint from the first bytes of its continuation. Every run reads
  a character only from one of its member bytes, and a set of some non-ASCII
  characters lists the lead bytes of its members' encodings, so its member
  bytes are the first bytes of every character it reads. A Java end checks the
  run's membership of LF, CR, U+0085, U+2028, and U+2029 as characters.
- The nullable-tail boolean exit, the required-literal check, and both handoffs
  to the ordinary engine keep their conditions. Because the plan and the
  ordinary engine agree on every input, a handoff never changes an RE2 result.

`TestRe2ScanPlan` checks route selection and which upstream golden patterns
select a plan, whose goldens then run on the plan. It compares the public
matching operations of plan, forced-handoff, and no-plan compiles, and of plan
and forced-handoff compiles with generated bytecode, including ranged calls,
short and null group buffers, and matchers retaining a prefix of the groups,
with malformed sequences and the characters at the edges of Java's default dot
inserted into each witness. Seeded generated patterns cover end-anchored,
partial, unanchored, run-led, and trailing-run shapes and delimited captures,
plain and ASCII-folded, and unanchored patterns led by a multi-byte literal
under distributions that move its probe. Fixed patterns run under the default,
case-insensitive, never-newline, and POSIX one-line options and add a minimum
that one multibyte character does not meet, forked plans, sets of some non-ASCII
characters, folded ones included, and long inputs that hand off to the ordinary
engine by work budget and by failure density. `TestScanPlanRun` compares how a set of some
non-ASCII characters reads one character with the ordinary engine on every one-
and two-byte sequence and on sampled three- and four-byte ones, for Java, Rust,
and RE2 sets. `BenchmarkRe2ScanPlan` runs the `BenchmarkTrinoScanPlan` patterns
and rows through public RE2 operations, plus non-ASCII and malformed rows for
the URL, unanchored URL, long-host, and unanchored-tail shapes. `FOLDED_HOST`
and `FOLDED_KEY` fold an `s` and a `k`, whose folds include the long s and the
Kelvin sign. Each plans with that letter as a set of some non-ASCII characters,
`FOLDED_KEY` as an unanchored search led by its `k`, and both are interpreted as
every folded plan is; their rows are mostly ASCII, and one in eight matches
through that member. In `DEEP_REJECTIONS`, the folded `K` that rejects the first
pattern in Trino is such a set, so in RE2 that pattern alone selects a plan.
`PERL_ANCHORED` and `PERL_UNANCHORED` capture with `\d` and `\w`, which RE2
plans as ASCII classes; their rows are mostly ASCII, and a few hold a non-ASCII
letter or Unicode digits. With `scanPlan=false` `BenchmarkRe2ScanPlan` compiles
each pattern without its plan, so the same rows measure the ordinary engine.
That compile is resolved reflectively, so the source also runs on a build
without RE2 plans with `-p scanPlan=true`. With `generateBytecode=true` it
compiles each pattern with generated bytecode, set reflectively and only then.

`TestJavaScanPlan` checks which Java constructs select a plan and compares the
planned and forced-handoff compiles of each plan shape, with and without
generated bytecode, against the JDK on valid UTF-8, including each line
terminator and two terminators appended after the witness, repeated finds,
regions, and counts, and against the no-plan compile on malformed input,
including truncated, lone, and overlong terminator bytes at the end. Seeded
random plans of the RE2 shapes, with Java's ends and a Java end right after a
final run, are compared, interpreted and generated, with the no-plan compile.
`BenchmarkJavaScanPlan` runs the `BenchmarkRe2ScanPlan` workloads and rows
through public Java operations, each pattern written in Java syntax for the same
match, with and without the scan plan and with generated bytecode; with
`javaDollar=true` each `$` stays Java's `$`. Without dotAll the patterns set
`UNIX_LINES`, so `$` uses the end before a final LF, unless
`javaLineTerminators=true` keeps Java's line terminators; then the dot is Java's
default dot, a set of some non-ASCII characters. The Java end is therefore
timed by the `javaDollar=true` rows with dotAll, and without dotAll under
`javaLineTerminators=true`. The rows end at most in LF, so the CR, CR LF,
U+0085, U+2028, and U+2029 checks are not timed.
`TestBenchmarkJavaScanPlan` confirms that each Java pattern selects a plan
and a generated class exactly when its RE2 pattern does, under either end and
with Java's default dot. The decisions record holds the results for
[plans](../../REGULATOR_DECISIONS.md#re2-scan-plans) and
[generated classes](../../REGULATOR_DECISIONS.md#generated-re2-scan-kernels).

`TestRustScanPlan` checks which Rust constructs select a plan, that byte mode
selects none, and that public calls reach the plan. It compares the public
operations of the planned and forced-handoff compiles of each plan shape, with
and without generated bytecode, against the no-plan compile, on valid UTF-8 with
line terminators appended after the witness and on malformed input, including
ranges, regions, repeated finds, and counts. Seeded random plans of the RE2
shapes, with Rust's Perl classes among their atoms so that the Unicode ones
become set runs, are compared, interpreted and generated, with the no-plan
compile. `BenchmarkRustScanPlan` runs the `BenchmarkRe2ScanPlan` workloads and
rows through public Rust operations, each pattern written in Rust syntax for the
same match, with and without the scan plan and with generated bytecode; with
`unicodePerlClasses=true`, `\d` and `\w` stay Rust's Unicode classes, sets of
some non-ASCII characters.
`TestBenchmarkRustScanPlan` confirms that each Rust pattern selects a plan and
a generated class exactly when its RE2 pattern does, Unicode `\d` and `\w`
included, and that the Perl-class rows reach those classes' non-ASCII members.
The decisions record holds the results for
[plans](../../REGULATOR_DECISIONS.md#re2-scan-plans) and
[generated classes](../../REGULATOR_DECISIONS.md#generated-re2-scan-kernels).

## Correctness and measurement

`TestScanPlan` verifies selection, Joni match/capture agreement, optional
retry, final LF, strict end, replacement versus extraction, multibyte input,
NUL, bounded reads on malformed input, regions, memory fallback, and concurrent
use of one compiled plan. Seeded unanchored cases also cover repeated `find`,
valid UTF-8 candidate boundaries, and leading-run skips. An attempt-count
diagnostic confirms that run-leading searches, including the ASCII-folded
executor, attempt only at candidate bytes, that literal-leading searches
attempt only at complete occurrences of the leading literal, and that a failed
single-character attempt resumes after an eligible following run and nowhere
else. Trino function and matcher tests also apply.

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
exercises a small complemented set and a larger ASCII table. `LITERAL_REPEATS`
covers exact, intermediate, and maximum bounded literal-sequence counts, plus
too few copies, an excess copy, and an empty following capture.
`LITERAL_CONTROLS` protects the direct `^foo` prefix and `^foo$`
equality routes. `DEEP_REJECTIONS` compiles three patterns that pass the shallow
start check and are rejected only after the builder has emitted a prefix: a
folded `K`, an over-limit literal repeat, and a run whose continuation overlaps
the following literal. Its compile row measures that analysis before the
ordinary route is kept. `FOLDED_ANCHORED`, `FOLDED_OPTIONAL`,
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
engine on the shapes the plan excludes. `UNANCHORED_SET`
covers ClickHouse's `[?&]([^=]+)=` query-key shape with varied candidate
distances, matches, and misses. `UNANCHORED_MIXED` interleaves twelve
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
the common dispatch loop. By default the plan generates no bytecode; the
experimental option described in [Generated kernels](#generated-kernels)
compiles an eligible pattern's plan into its own class.

`BenchmarkTrinoScanProfiles` derives every pattern/input pair from the
`OPTIONAL_MIXED` workload. `INTERLEAVED` preserves its row order; `GROUPED`
visits the same 504 pairs grouped by pattern. `PLAN_0` through `PLAN_11` each
repeat one pattern's 42 inputs twelve times, preserving the same timed array
length. Every configuration uses the same per-row input, pattern, and reusable
matcher arrays. Setup does no matching that could pretrain the scan executor.
The [generated-kernel benchmarks](#benchmarks) describe its `generateBytecode`
and `generatedNeighbors` parameters.

The `containsWithoutScanPlan`, `capturesWithoutScanPlan`, and
`extractWithoutScanPlan` methods run the same rows without the scan plan. Each
pattern is parsed as Trino parses it and compiled with the RE2 compiler dialect
through `Re2.compileParsed`, which builds no plan, and each method calls the
`Re2` route the base `TrinoRegexp` takes for its operation when a pattern has no
plan. For these patterns, which all have capturing groups and no nullable loops,
both dialects compile the same program, so the methods measure the current
ordinary engine on the plan's workload, without `TrinoRegexp`'s own dispatch.
They do not measure the engine from before the plan existed, which lacked every
later engine change.

Compare an equal-weight mean of all twelve individual-pattern timings with the
interleaved and grouped timings. Grouping changes input order while retaining
the same pattern frequencies; it does not guarantee identical JIT decisions.
Each parameter runs in its own JVM. Public boolean, reused capture matching,
and extraction consume their results separately. `BenchmarkTrinoScanPlan`'s
`OPTIONAL_MIXED` workload must remain in the comparison because changing the
benchmark caller can change the compiled code and the size of the observed
regression.

Forked workloads such as `OPTIONAL_MIXED` and `OPTIONAL_RETRY` are often
bimodal across forks. Forcing the interpreter's run and byte scans out of line
with `-XX:CompileCommand=dontinline` made Trino `OPTIONAL_MIXED` unimodal and up
to 20% faster on Graviton, but no forced shape won every RE2 workload: out of
line suited `OPTIONAL_MIXED` and slowed `OPTIONAL_RETRY` on R8i. HotSpot's
logged inline tree does not differ between fast and slow forks, and plain Java
cannot pin an inlining decision, so call boundaries are controlled in
[generated kernels](#generated-kernels) rather than in the interpreter.

Development measurements, rejected candidates, and raw evidence for this
design are in the private archive; see the
[archive index](trino-scan-plan-archives.json). Accepted trade-offs and rejected
alternatives are recorded in
[`REGULATOR_DECISIONS.md`](../../REGULATOR_DECISIONS.md#trino-specialized-byte-scans).

## Execution width and safeguards

The plan's own scanners use 128-bit vectors on all hosts. This matches the
Graviton vector width and uses the same scan width on Intel. Candidate scans
that reuse the DFA's start-byte scanners use those scanners' preferred species.
Wider Intel vectors may help long scans, but have not been selected or shown to
improve these integrated workloads. Treat preferred-width selection as a
separate measured change.

Named operation factories define each operation's step layout. Retry-stack
limits are checked at class initialization against the two position longs and
six-bit operation indices. Fixed instance sizes are cached, and compilation
computes each plan's retained size once before budget charging. Seeded
generated-pattern tests include successful witnesses and mutations, so capture
participation and retry behavior receive coverage alongside rejection.

## Generated kernels

`TrinoRegexp.Options.setGenerateBytecode(true)` is an experimental opt-in. It
compiles an eligible pattern to a private `TrinoRegexp` subclass backed by a
hidden class that `ScanKernelGenerator` emits for the pattern's
case-sensitive plan. `TrinoRegexp.usesGeneratedBytecode()` reports whether a
pattern has one. The accepted trade-offs are recorded in
[`REGULATOR_DECISIONS.md`](../../REGULATOR_DECISIONS.md#generated-trino-scan-kernels).

### Eligibility and routing

A pattern is eligible when it would compile to the base `TrinoRegexp` class
with a case-sensitive plan that every operation but `contains` reaches.
ASCII-folded plans, every other `TrinoRegexp` subclass, and a pattern whose
class cannot be defined compile as they do without the option.

Each operation keeps the route it has without the option, decided once at
compile time. `contains` runs the generated class when full-Slice `find` runs
the plan, and otherwise keeps its retained literal kernel. Every other
operation and every matcher runs the generated class. Short boolean calls that
the interpreter hands to the ordinary engine also run the generated class,
which has no operation dispatch for that route to avoid. A pattern with a
single-byte matcher, which group-zero operations ask before the plan, or with
a count that skips the plan, is not eligible. Such a pattern has no scan plan
today, so the check only keeps a future change from routing it to generated
code.

### Generated class

The class holds the plan's attempt method (`matchEndAnchored`,
`matchPartial`, or `matchUnanchoredAt`), which keeps the interpreter's
signature and encoded results. Each operation becomes straight-line code, with
literals, their lengths, and branch targets as constants. Literals of at most
16 bytes compare with one or two native-order loads, longer literals use
`Arrays.mismatch` against a class constant, a run step passes its run to a
copied run scanner that reads the run's bounds and members, and delimited
captures search for their delimiter byte. Optional literals and forks push
the same retry entries as the interpreter, and a failed attempt pops them and
resumes through a switch on the saved operation, clearing the same capture
slots.

Those scanners, and every candidate scan below, are copies of the
interpreter's helpers in `ScanKernelSupport` and
`VectorScanKernelSupport`, not calls to the originals, for the reason
recorded in
[`REGULATOR_DECISIONS.md`](../../REGULATOR_DECISIONS.md#generated-trino-scan-kernels).
The search handoff to the ordinary engine and result assembly stay shared;
they are not scan-loop helpers.

An unanchored plan also gets `search` and, when full-Slice `find` would hand
off to the ordinary boolean search, `searchBeforeHandoff`. Both mirror the
interpreter's candidate search with its work budget and density limits as
constants: the same candidates in the same order, the same required-literal
check, and the same handoff offsets, after which the ordinary engine continues
from the same position. A leading literal's candidate scan is chosen when the
class is generated; a leading run passes its run to a copied candidate scanner. A whole-literal candidate scan probes
the literal byte that the pattern's byte distribution chose, emitted as a
constant, so a supplied distribution or preset moves the generated probe
exactly as it moves the interpreter's.

Each routed operation has a public static entry named for it with an `Entry`
suffix, such as `containsEntry`; the suffix keeps the entries apart from the
`CompiledTrinoScan` interface methods, which delegate to them. The subclass
calls the interface once per operation, and the `generated*` accessors return
the entries as method handles. The class is stateless and holds the pattern's
`Re2`, plan, literals, and runs as class data.

`TrinoRegexpMatcher` is sealed, permitting one nested subclass that runs the
generated `find` into its own offset array. Without the option it is never
loaded, so the existing matcher keeps its single implementation.

Set the system property `io.airlift.regulator.scan-kernel.dump-directory` to a
directory to write each generated class file there before it is defined.

### RE2 executor classes

`Re2.Options.setGenerateBytecode(true)` compiles a pattern with a
case-sensitive plan to a private `Re2` subclass backed by a hidden class from
the same emitter, and `Re2.usesGeneratedBytecode()` reports whether it has
one. `JavaRegexp.Options.setGenerateBytecode(true)` and
`RustRegexp.Options.setGenerateBytecode(true)` compile a Java or Rust pattern to
the same subclass. ASCII-folded plans and a pattern whose class cannot be
defined compile as they do without the option. The accepted trade-offs are
recorded in
[`REGULATOR_DECISIONS.md`](../../REGULATOR_DECISIONS.md#generated-re2-scan-kernels).

The class implements `CompiledRe2Scan` with the plan methods it replaces
rather than operation entries. A start-anchored plan's `match` is the attempt
method itself. An unanchored plan gets `search`, the interpreter's region
search with its context, start, anchoring, and full-match arguments, and,
when full-Slice `find` would hand off to the ordinary boolean search,
`searchBeforeHandoff`. The class emits the same attempt and candidate loops as
the [generated class](#generated-class) above; `RE2_RUN`, `RE2_SET_RUN`, and
`RE2_DELIMITED_CAPTURE`, which RE2, Java, and Rust plans use, call
`ScanKernelSupport` copies of the RE2 run reader and, for
an `RE2_SET_RUN`, of the member reader, and an RE2 run's stop scan calls a copy
in `VectorScanKernelSupport`. A Java end calls the
`ScanKernelSupport` copy of the final-terminator check. The class never
calls back into the pattern.

The subclass overrides the four `Re2` calls that reach a plan: full-Slice
boolean `find`, the unanchored count loop, a matcher's start-anchored first
search, and the plan route of the match dispatch. Each runs the generated
class where the interpreter would run the plan, and the match dispatch passes
every other call to the base route. A search that hands off continues on the
ordinary engine as an interpreted one does. Short boolean calls that the
interpreter sends to the DFA, and boolean calls that the interpreter runs on a
start-anchored plan's save-free boolean plan, run the generated full plan.

### Benchmarks

Before-and-after qualification uses three shapes, each measuring the option
against a build without it on the same hosts:

- Patterns compiled without the option through the public API:
  `BenchmarkTrinoScanPlan` with `generateBytecode=false`, and
  `BenchmarkTrinoScanProfiles` with `generatedNeighbors` 0 and 4, described
  below.
- A constant pattern bound as a handle: `BenchmarkTrinoScanKernelDispatch`
  defines a hidden caller per pattern that invokes an operation handle as a
  class-data constant. `INTERPRETER` binds the public method to the pattern,
  and `GENERATED` binds its generated handle, for `contains`, `count`,
  `extract`, and `position` on four `OPTIONAL_MIXED` patterns, an
  `UNANCHORED_MIXED` pattern, and the short `URL_SHORT` rows. `ISOLATED` runs
  the target alone; `INTERLEAVED` first trains every pattern of the target's
  workload. The generated API is resolved reflectively, so the same source runs
  on a build without the option with `-p executor=INTERPRETER`.
- Compiled patterns through the public API at shared call sites:
  `BenchmarkTrinoScanPlan` and `BenchmarkTrinoScanProfiles` with
  `generateBytecode=true`, where twelve compiled patterns share each call site.
  `BenchmarkRe2ScanPlan`, `BenchmarkJavaScanPlan`, and `BenchmarkRustScanPlan`
  with `generateBytecode` true and false measure RE2, Java, and Rust executor
  classes the same way, with `scanPlan=false` as the ordinary-engine control.
  The decisions record holds
  [the RE2, Java, and Rust results](../../REGULATOR_DECISIONS.md#generated-re2-scan-kernels).

With `generatedNeighbors=4`, `BenchmarkTrinoScanProfiles` setup also compiles
two anchored and two unanchored patterns from other workloads with generated
bytecode and runs them through their own hidden callers, before the trial and
before each iteration, so the twelve measured patterns, compiled without the
option, run in a JVM that has loaded the compiled subclasses and generated
classes. Its `WithoutScanPlan` methods, described under
[Correctness and measurement](#correctness-and-measurement), then compare the
interpreter beside generated neighbors with the ordinary engine. Generated
bytecode is requested reflectively through
`BenchmarkTrinoScanKernelDispatch`, so with `generateBytecode=false` and
`generatedNeighbors=0`, the two sources also run on a build without the option.

`BenchmarkTrinoScanKernelCost` is a plain main that reports, for 1, 100, and
10,000 distinct patterns, the time per class to generate, define, and
instantiate it (`buildMicrosPerClass`), method bytecode and classfile size per
class, and metaspace, compressed class space, and code cache growth; run each
count in its own JVM on an AWS host. The figures are cold first-use costs that
include loading the generator and the classfile library, and the code cache
figures are snapshots taken immediately after defining and after exercising the
classes, without waiting for background compilation.

`BenchmarkSpecializedTrinoRegexp` measures the specialized-pattern workloads,
whose variants the published language report sets beside Joni. Each workload
has one Trino operation: the ClickBench q29 host replacement, a URL host
extract, the extract-alls of query parameter names and of key-value values, and
a quoted-field extract. The URL workloads share eight rotating URLs in
`specialized-trino-workloads.tsv`, and the others reuse their everyday
rotation.

Each workload runs with three compiles of its pattern. The specialized engine,
the default compile, interprets the scan plan. The regular engine comes from
`compileWithoutScanPlanForTesting`, which compiles the same pattern without a
plan, so the operation takes the regular engine route through `TrinoRegexp`'s
own dispatch. Both make the public `TrinoRegexp` call. The compiled pattern
comes from `setGenerateBytecode(true)` and is const-bound the way an engine
like Trino binds it: a per-pattern hidden class invokes the operation's
generated handle, such as `generatedReplace()`, held as a class-data constant.
The handle returns the public method's result and accepts its arguments,
except that extract and extract-all bind the group when the handle is created
and take only the source when called. Only the `*Generated` benchmark methods
take the states that compile generated bytecode, so the other variants'
processes load no generated class. The `*RegularEngine` methods likewise take
their own state, which holds only the regexp compiled without a scan plan.

The `trino-specialized-patterns-a` and `-b` baseline shards divide the
workloads between two shards, so each host runs part of them, with each
workload and variant in separate JVMs. Every call site sees one pattern, as in
a Trino query, on both memory routes beside their Joni twin, this repository's
`BenchmarkSpecializedTrinoJoni`, on pinned Joni. The `trino-like-specialized`
shard runs `BenchmarkTrinoLikeTpch`'s `PROMO%`, `%BRASS`, `%green%`, and
`%special%requests%` workloads on the specialized engine and compiled, beside
Trino's SQL LIKE matcher, again one pattern per JVM; its compiled LIKE is
const-bound the same way. These shards time warmed steady-state operations and
exclude compilation.
