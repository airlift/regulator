# Regulator design decisions

This file records accepted design decisions, each language's authority, and
intentional differences from those authorities. Describe the current design,
not the sequence of experiments that led to it. Keep measurements or
generated-code evidence when they explain a performance trade-off or why a
tested alternative was rejected.

For RE2 and the shared matching engines, the default rule is to preserve
upstream RE2 behavior; the other languages follow their own authorities, as
Language Authorities describes. A Java adaptation must not
change which byte strings match, capture boundaries, parse behavior, or resource
fallback semantics.

## Language Authorities

Each language has one authority, and they differ in how much they leave to
judgment:

- Java: the code of the current JDK release is the specification. A
  supported Java pattern gives that release's results, computed with the JDK's
  own code, such as `Pattern`'s `CharPredicates` and the `Character`
  predicates, applied to the pinned Unicode database. Match positions are byte
  offsets, as Java Regular-Subset Frontend records, and
  [Differences from the JDK](docs/integrations/UNICODE.md#differences-from-the-jdk)
  lists the other exceptions. Where `Pattern`'s javadoc and the JDK's code disagree, the code wins.
  A Java property is computed as the JDK computes it even where the database
  stores a Unicode property of the same name with the same members today,
  such as White_Space or Alphabetic.
- RE2: pinned upstream RE2, through its code and the goldens generated from
  it. Regulator departs from it only where this file records a decision.
- Rust: the pinned `regex` crate, through the goldens generated from it.
  Regulator departs from it only where Rust Frontend records a decision.
- Trino: Trino's Joni fork. Unlike the others, it is old and has known bugs,
  so deciding whether a fork behavior is a bug or Trino's intent is a
  judgment, made per case and recorded in this file and
  [`TRINO_REGEXP.md`](docs/reference/languages/TRINO_REGEXP.md). That judgment
  does not extend to the other languages.
- Trino LIKE: Trino's `LikeMatcher` with optimization disabled. Regulator
  departs from it only where Trino LIKE Frontend records a decision.

Every language reads the pinned Unicode 18.0 database instead of its
authority's Unicode data, as Pinned Unicode Properties describes.

## Byte-Oriented Java API

- Public patterns, inputs, captures, and rewrite results use `Slice`.
  Convenience overloads that silently convert `byte[]` or `String` are
  intentionally absent. Parser, compiler, AST metadata, and execution engines
  also use `Slice` directly.
- Execution engines represent a search window as the original context `Slice`
  plus explicit start and end offsets. This avoids wrapper allocation and
  preserves surrounding context for zero-length windows, which must not be
  collapsed to `Slices.EMPTY_SLICE` when evaluating boundary assertions.
- Compiling copies the usually small pattern bytes. This prevents later mutation
  of the caller's Slice from changing the compiled pattern or `pattern()` result.
- `replaceFirst`, `replaceAll`, and `extract` return new byte-backed values instead
  of resizing an input string in place.
- `findInto`, `lookingAtInto`, and `matchesInto` accept caller-owned
  `[start,end,...]` capture storage for the allocation-sensitive API.
  `MatchResult` provides the ordinary Java result API, including named captures
  and numeric conversion.
- `Re2Matcher` owns one reusable capture buffer for repeated matching. Callers
  may retain only group zero and a requested prefix of explicit captures when
  complete capture extraction is unnecessary. It advances empty UTF-8 matches by
  one code point, permits one terminal empty match, and exposes zero-copy Slice
  group views. A Rust pattern follows the crate instead and skips only an empty
  match that ends where the previous match ended or inside a character; see
  [Rust Frontend](#rust-frontend). All public offsets are relative to the
  complete logical input Slice, including matchers restricted to a region.
  Matchers are mutable and not thread-safe; compiled `Re2` instances remain
  thread-safe.
- No-match is represented by `false` or `null`, depending on the method. Numeric
  conversion failures use `NumberFormatException`.

These choices replace C++ pointer and out-parameter APIs without changing match
semantics.

## Exceptions At API Boundaries

- Invalid patterns throw `RegexpParseException` with a typed parse error code,
  invalid pattern segment, and byte offset when known.
- Program compilation failures throw `RegexpCompileException`;
  `RegexpCompileMemoryLimitException` identifies an insufficient compile budget.
- `Re2Set` matching throws `RegexpMatchMemoryLimitException` when its many-match
  DFA exhausts the configured memory budget. Exhaustion is not reported as no
  match, and `matchingPatternIds` never returns partial IDs. Unlike ordinary
  `Re2` matching, the set path cannot fall back to the NFA because that engine
  does not retain the many-match pattern IDs.
  The collecting path continues through cache thrashing rather than using the
  boolean search's early-bail policy. Its initialization gate retains ample
  headroom for reset recovery, which needs at most the start state, current
  state, and next transition state. Tests cover default and 64 KiB budgets,
  repeated reset/restore, end-text transitions, and a 512-ID match payload.
- Lazy reverse-program compilation still treats an insufficient reverse budget as
  an execution fallback and uses the NFA, matching upstream behavior.
- Invalid rewrites throw `RegexpRewriteException`. No-match is no longer
  conflated with invalid rewrite syntax through a C++-style boolean result.

Regulator does not retain invalid `Re2` instances or C++-style success flags after
construction fails.

## Pinned Unicode Properties

- Unicode properties in every language come from the database that
  `tools/unicode-database` generates from pinned Unicode 18.0 UCD files, so
  they do not change with the JDK. Pinned upstream RE2's generated Unicode
  tables are intentionally not retained.
- The database holds only Unicode data. RE2 names it by upstream's rule, as
  RE2 Unicode Property Groups describes. Java and Trino layer the JDK's names
  on it: categories with their long names and `LC`, `LD`, and `Cn`, scripts
  with their ISO 15924 codes, blocks with the JDK's identifiers, the `In`
  form, binary properties, and `java...` properties; Java also layers the
  `Is`, `gc=`, `sc=`, and `blk=` forms and its POSIX classes. Each JDK
  property is the JDK's own computation over the database, as Language
  Authorities describes, and a JDK choice that is not Unicode data is a
  constant citing the JDK. Rust layers the regex crate's names on it, as Rust
  Frontend describes.
- Trino's POSIX names, such as `\p{Alpha}` and `[[:alpha:]]`, use the
  definitions of Trino's pinned Joni over the database, such as Alphabetic
  for alpha. Pinned Joni's bracket punct is Unicode punctuation alone, so
  `[[:punct:]]` rejects ASCII symbols such as `$` that POSIX `punct` includes
  and a user expects it to match. That omission is a bug, as Trino
  Compatibility Adapter describes, so the Trino language adds the nine ASCII
  symbols ``$+<=>^`|~``, as modern Joni does from 2.1.14; non-ASCII symbols
  stay out, as in both Jonis. `\p{Word}` and `[[:word:]]` keep pinned Joni's
  definition as intended behavior, without the Join_Control that modern Joni
  adds from 2.2.7; Oniguruma's `doc/RE` gives the same definition.
- Properties are built lazily from the database's ranges into immutable
  `CharClass` values and cached by canonical identity. Matching uses only the
  resulting ranges and compiled instructions.
- Java, Trino, and Rust match-time word boundaries read a table of the
  database's word characters, nonspacing marks, and letters and digits, built
  once, when the first pattern with such a word boundary compiles.
- Java's `\N{name}` accepts the names `Character.codePointOf` accepts, built
  from the database's character names, the earlier Unicode names of controls,
  and blocks by the JDK's rule. The JDK's two choices for controls that are not
  Unicode data are constants citing the JDK's build tool. The names are read on
  the first `\N{name}`.
- Java normalizes property and character names with the database's case
  mappings, including the unconditional mappings of `SpecialCasing.txt`, as
  the JDK's `String` and `Character` conversions do. Trino, as Joni does,
  accepts only ASCII property names, so they fold by ASCII case alone, and
  Trino's capture names cannot start with the database's decimal digits.
- RE2's ASCII definitions of shorthand classes remain regex-language semantics,
  not Unicode data. Java-specific shorthand modes and other syntax extensions
  require separate explicit decisions.

Java and Trino properties, word boundaries, and name normalization, and Java's
character names, previously came from the running JVM's `Character` and
`String` APIs, so their results changed with each JDK upgrade. Except for
Trino's POSIX names, they now agree with the JDK at every code point its own
Unicode version assigns, 16.0 on Java 25 and 17.0 on Java 26, apart from two
changes Unicode 17.0 made to older characters: U+0295 moved from `Ll` to `Lo`,
and 689 code points that are not emoji, such as U+2605 BLACK STAR, lost
Extended_Pictographic.
Name case conversions agree where their input and output contain only
characters assigned in the JDK's Unicode version; Unicode 18.0 also uppercases
some older characters to newer ones, such as U+0277 to U+A7DD, but neither is
ASCII, so no name resolves differently. Characters and names Unicode added
after the JDK's version, such as the Unicode 18.0 scripts Jurchen and Seal and
the names of their characters, resolve only in Regulator. This agreement is of
property membership, which, outside Trino's POSIX names, is the JDK's
computation over the pinned database. Java's accepted property spellings keep
Regulator's existing rules, which are not identical to `Pattern`'s, and
Trino's are Joni's ASCII-only names.

This intentionally differs from pinned upstream's Unicode snapshot while
preserving its matching algorithms, byte semantics, and resource guarantees.

## Pinned Unicode Case Folding

Every language folds case with data from the database that
`tools/unicode-database` generates from pinned Unicode 18.0 UCD files, so case
folding does not change with the JDK. Each language keeps the rule of its
reference engine.

RE2 patterns and `FilteredRe2` canonicalization fold with Unicode 18.0 simple
case folding (`CaseFolding.txt` statuses C and S). This is the rule of
upstream RE2's generated tables, so upstream tests that expect it, such as
`(?i)i` not matching dotted capital `İ` or dotless small `ı`, apply unchanged.
Rust patterns fold with the same rule, which is the regex crate's.

Pinned upstream's tables are Unicode 15.1 data. From 15.1 to 18.0, simple case
folding only gained folds for characters added after 15.1. Most form new
orbits; nine join the orbit of an existing letter, such as U+A7CB, which folds
to U+0264, and U+1DF95, which folds to `ß`. One upstream expectation changes
for this reason: `(?i)[\x00-\x{FFFD}]` also matches U+1DF95.

Java patterns fold with the rule of `java.util.regex` `UNICODE_CASE`: code
points with the same simple lower-case mapping of their simple upper-case
mapping share a class, computed from the database's `UnicodeData.txt`
mappings. Trino patterns fold with Unicode 18.0 full case folding
(`CaseFolding.txt` statuses C and F), Joni's rule; its single-code-point
classes are the RE2 orbits, and status F supplies its multi-code-point folds.

Both rules previously read the running JVM's case mappings. On the JVM's own
Unicode version they agree with the database: on JDK 26, whose data is Unicode
17.0, the previous Trino rule differed from Unicode 17.0 full case folding only
by putting dotted capital I and dotless small i in the class of I, and the Java
rule equals the JVM's case classes wherever the JVM assigns every member of a
class. Java case classes therefore differ from the JVM's only for characters
Unicode added after the JVM's version, and their case partners. Trino case
classes differ for the same characters, and also keep dotted capital I and
dotless small i out of the class of I, as Joni does.
Case-insensitive matching built on those classes can still differ from
`Pattern` independently of Unicode version. Moving to
another Unicode version follows the generator's
[version procedure](tools/unicode-database/README.md#moving-to-a-new-unicode-version).

An ASCII letter pair, such as `[Kk]` or a Java letter folded without
`UNICODE_CASE`, matches only its two letters, and every language merges it as
written into a class with neighboring alternatives. For Java that is the JDK's
result. Upstream RE2's alternation factoring folds the merged pair again by its
whole orbit, so there `[Kk]|x` matches the Kelvin sign though `[Kk]` alone does
not. Regulator departs from upstream here so that an explicit pair means the
same thing whatever its neighbors. Unicode case-insensitive letters, such as
RE2's `(?i)k|x` or Java's `(?iu)k|x`, keep their whole orbit.

## RE2 Unicode Property Groups

RE2 `\p` and `\P` groups come from the pinned Unicode 18.0 database by the
rule of upstream's generator, `make_unicode_groups.py`: one group for every
General_Category value that appears in `UnicodeData.txt`, one for the union of
each major class, such as `L` for `Lu`, `Ll`, `Lt`, `Lm`, and `Lo`, and one for
every script in `Scripts.txt` under its long name, plus `Any`, which `parse.cc`
adds. Lookup is an exact, case-sensitive name match, so RE2 accepts `\pL`,
`\p{Greek}`, `\p{^Greek}`, `\PL`, and `\P{Greek}`, and rejects `\p{greek}`,
`\p{IsGreek}`, `\p{InGreek}`, `\p{Letter}`, `\p{Cn}`, `\p{LC}`, and
`\p{Unknown}` with upstream's bad character range error. Unassigned code
points are in no category, so `\p{C}` is `Cc`, `Cf`, `Co`, and `Cs`.

RE2 previously resolved these names through the JVM registry that serves Java
and Trino, so it accepted their aliases, blocks, and binary properties, and its
results followed the JDK's Unicode version. Upstream's tables are Unicode 15.1
data; groups now differ from upstream only where Unicode 18.0 assigned or
reclassified code points, such as the scripts added since 15.1, including
Garay, Jurchen, Proto_Cuneiform, and Seal.

## Java Regular-Subset Frontend

- `JavaRegexp` is a separate compiler for the documented regular subset of
  `java.util.regex.Pattern`. It does not reinterpret rejected Java syntax as
  RE2 syntax and does not add a dialect selector to `Re2.compile`.
- Java's `$`, `\Z`, multiline anchors, and combining-aware word boundaries use
  frontend-specific empty-width predicates. Programs containing those
  text-dependent assertions use BitState or NFA; protected DFA transition loops
  do not contain Java-language branches.
- Multiline `^` excludes end-of-input even with `UNIX_LINES`. In that mode
  the compiler combines LF-only beginning-of-line with Java's non-EOF
  assertion, using the same context-sensitive fallback. UNIX multiline `$`
  retains its LF-only rule, including a boundary between CR and LF.
- Unicode character classes and properties use the pinned database, as
  described in Pinned Unicode Properties, and case folding uses its case
  mappings, as described in Pinned Unicode Case Folding. Word boundaries read
  the database's word characters, nonspacing marks, letters, and digits.
  `UNICODE_CHARACTER_CLASS` implies `UNICODE_CASE`, matching the JDK flag
  contract.
- `JavaRegexp.Options` exposes the supported compile flags as mutable chainable
  options. Compilation snapshots their values and copies the pattern bytes.
- `\G`, `\R`, nullable repeated captures, canonical equivalence, lookaround,
  backreferences, atomic groups, and possessive quantifiers remain deterministic
  compile-time rejections because their observable semantics do not map to the
  current bounded linear-time execution contract.

Java match positions remain UTF-8 byte offsets over `Slice`, not the JDK's
UTF-16 code-unit offsets.

## Rust Frontend

- `RustRegexp` is a separate compiler for the pattern language of the pinned
  Rust `regex` crate. Its results must match the crate's on valid UTF-8 input,
  except for Unicode property names and characters that differ between the
  crate's Unicode 16.0 tables and the pinned Unicode 18.0 database; see the
  [Rust language reference](docs/reference/languages/RUST_REGEXP.md#unicode-properties).
  The pinned crate is the authority, recorded in committed fixtures; the crate
  never runs in the Maven build.
- `RustRegexpParser` builds a private syntax tree shaped like the crate's and
  checks the nest limit before translating, so the crate's error precedence is
  preserved. Each crate error kind maps to a `RegexpParseErrorCode`, and the
  message names the kind.
- Rust patterns use the shared `Re2Matcher`. A pattern-level search flag makes
  it skip empty matches that split a UTF-8 character or end where the previous
  match ended, and makes ranges and regions keep the whole Slice as assertion
  context, as the crate's `Input::range` does. There is no Rust-specific
  matcher type.
- Inputs are not validated. The crate's `Regex` only accepts valid UTF-8, so
  results on malformed input are unspecified rather than an error, and valid
  input pays no validation scan.
- CRLF multi-line anchors and the start, end, and half word boundaries are
  Rust-only empty-width predicates, evaluated only when a program contains
  one. Like Java's text-dependent assertions, they keep a program on BitState
  or NFA; DFA transition loops do not contain Rust-language branches. Plain
  `\b` and `\B` reuse the RE2 ASCII and Java Unicode boundaries.
- Unicode properties, `\d`, `\s`, `\w`, and Unicode word boundaries read the
  pinned database, as Pinned Unicode Properties describes. `\w` and the word
  boundaries use the database's word characters, the UTS #18 class that Java's
  `IsWord` also is. Case-insensitive patterns use the database's simple case
  folding, the RE2 rule, as Pinned Unicode Case Folding describes.
- Property names follow the crate's rules rather than the names the other
  languages layer on the database. They are built from the database's property
  and value aliases by the naming rules of the crate's table generator, so a
  name Unicode added after the crate's tables, such as `Age=17.0`, is accepted
  ahead of the crate. The database holds every property the crate supports,
  so none is rejected or approximated.
- Repetition counts above 1,000, including nested repetitions whose counts
  multiply past 1,000, are rejected, keeping the existing program-size bound.
  Any non-negative nest limit is accepted, because parsing, the nest-limit
  check, and translation all walk the syntax tree with explicit stacks.
- The crate's 10 MiB compiled-size limit is not reproduced, because an
  approximation of the crate's size estimate would disagree near the limit.
  Compilation is bounded by `maxMemory` instead; the
  [Rust language reference](docs/reference/languages/RUST_REGEXP.md#compilation-options)
  describes the patterns this accepts.

## Trino Compatibility Adapter

- `TrinoRegexp` implements Trino's contains, count, position, extraction, split,
  and replacement behavior over the public Slice API. It does not call execution
  engines directly.
- Trino's Java-style `$g` and `${name}` replacement grammar and adjacent empty
  replacement behavior remain separate from native RE2 rewrite syntax.
  Malformed references throw `TrinoRegexpReplacementException` with the failing
  replacement byte offset.
- String replacement parses its template at the first match, so a nonmatch
  returns the original `Slice` even when the replacement is malformed. It
  retains only referenced captures, bounds match-record storage, sizes output
  to the result, and caches each pattern's last template of at most 256 bytes.
  Callback replacement streams because its results are unknown before matching.
- `TrinoRegexpParser` compiles the supported Trino/Joni regular subset directly
  rather than routing syntax through the RE2 parser or a dialect enum.
  Unsupported constructs and same-text/different-meaning collisions are
  rejected explicitly, apart from the documented differences below.
- Greedy unbounded repetition of a nullable body uses a Trino-only input-progress
  lowering: once an iteration succeeds without consuming input, the repetition
  stops instead of reconsidering lower-priority alternatives. The lowering
  represents pre-consumption and post-consumption execution with ordinary
  program counters. It clones each epsilon-reachable instruction at most once
  per loop, charges every retained instruction to the existing program budget,
  and leaves the shared execution engines unchanged. Directly nested `?`, `*`,
  and `+` quantifiers, in greedy or reluctant form, first use Joni's parser
  reduction table in the Trino parser before counted repetitions are expanded.
- Capture-sensitive nullable loops can differ from Joni after that no-progress
  point. Joni's backtracking engine retains capture histories and may reconsider
  a different iteration path to extend the match; Regulator's bounded Thompson
  state does not retain that backtracking history. For `(?:(a??)|b)*a` on
  `baa`, Regulator returns successive group-zero matches `ba` and `a`, while
  Joni returns one `baa` match. The pattern remains supported with Regulator's
  bounded input-progress semantics. This is a documented Joni incompatibility,
  not an unsupported pattern. A separate capture-aware engine is deferred until
  there is concrete user demand for exact Joni behavior in this case.
- `contains` may select the shared ordered-literal matcher when expression
  analysis proves two or more case-sensitive literals separated by unrestricted
  dot-star gaps. The complete compiled `Re2` remains authoritative for every
  other operation, including captures and boundaries. The matcher retained size
  is deducted from the forward-DFA budget; compilation keeps the ordinary RE2
  route when that optional allocation does not fit.
- A pattern that is not valid UTF-8 throws a `BAD_UTF8` parse error at its
  first invalid byte, as in the RE2 and Java frontends. This deliberately
  differs from Trino, whose non-strict Joni path accepts such a pattern and
  compares its malformed bytes byte-for-byte; that reading is unspecified, so
  Regulator treats the pattern as a syntax error. Inputs are not validated as
  UTF-8, matching Trino's garbage-in/garbage-out contract.
- Where Trino's pinned Joni, `io.airlift:joni:2.1.5.3`, and modern Joni
  disagree, the behavior is judged a bug or intended Trino behavior, as
  [Language Authorities](#language-authorities) describes. A bug is not copied;
  the Trino language follows the rule modern Joni and Oniguruma settled on
  instead, so `[a-zA-Z0-9-_]` matches `-`, which pinned Joni drops. Intended
  behavior stays even where modern Joni changed it, as with the ASCII `\d`,
  `\s`, and `\w`. The
  [Trino language reference](docs/reference/languages/TRINO_REGEXP.md) records
  each call.

Trino SQL registration, `CHAR` padding, block construction, and error-code
translation belong in the Trino repository rather than the Regulator library.

## Memory Budgets And DFA Caching

- Public compilation defaults to a 96 MiB planning budget. Unlike upstream's
  8 MiB default, this retains representative large Java DFA graphs without
  reset thrashing under both compressed and uncompressed ordinary references.
- Two thirds of the configured budget is assigned to the forward program and one
  third to a lazily compiled reverse program.
- Compilation accounts for the program and list-head storage before assigning the
  remaining budget to DFA caches. Internal compilation with a non-positive budget
  uses upstream's 1 MiB default DFA allowance.
- A forward program divides its DFA allowance between first-match and
  longest-match caches. A reverse longest-match DFA and a set's many-match DFA use
  their program's complete allowance.
- DFA construction derives retained array and object sizes from the running VM.
  Expanded backing arrays and hash-table capacity remain charged after reset;
  only unreachable per-state objects return to the budget. A full cache resets,
  and repeated reset thrashing falls back to the NFA.

`maxMemory` is a planning budget, not an eager allocation or a byte-exact JVM
heap limit. The default is demand-driven, so simple patterns retain only the
storage they use. Object headers, collector metadata, and allocator behavior are
VM-specific; the cache model accounts for reachable Java objects but does not
promise exact process-memory equality.

## Shared DFA Concurrency

Compiled `Re2` instances are safe to share across threads:

- searches register in padded thread-local reader slots before accessing a DFA
  cache and unregister when the search ends
- the global weak reader registry is locked only when a thread first registers
  or stale references are drained; stale-heavy drains replace the backing set so
  a short-lived thread wave does not retain its peak hash table
- warm transition reads are plain loads with no lock or acquire operation in the
  per-byte loop
- cold construction and reset block new readers, wait for active readers to
  quiesce, and replace cache storage under an exclusive mutation lock
- cache generations prevent queued builders from repeating work already
  published by another thread

This preserves upstream's separation between transition access and cache
lifetime synchronization without placing a read lock in Java's hot loop.

## Bounded Paired DFA Transitions

Long generic forward searches may use an immutable depth-two transition table
for small DFA graphs. The table is demand-built only after a search of at least
256 bytes, is published under the existing exclusive cache mutation protocol,
and is charged against the DFA state budget. Normal state allocation discards
the table before reporting budget exhaustion.

Eligibility uses a 64 KiB retained-size estimate with eight-byte references and
includes row overhead. This conservative absolute cap is independent of the
configured memory budget: larger budgets must not enable cache-hostile global
transition expansion. When the complete graph does not fit, partial pairing is
limited to exactly two selected rows and 16 KiB. Selection considers cached
forward entry states before states with observed self-loops. Broader and larger
partial tables remain on the compact path to bound retained memory and avoid
cache-hostile global transition expansion.

The paired loop decodes already-computed normal, dead, full-match, and match
transitions directly. After a first-match candidate, it preserves the candidate
while continuing through paired rows and switches to the compact loop at the
same state and byte boundary when a row or transition is unavailable. Cache
exhaustion and every uncomputed transition retain the compact path's reset and
fallback behavior.

After a match transition enters a matching self-loop, the continuation scans
the authoritative primitive transition row directly until a byte changes the
state or stops matching. It updates the match boundary for every consumed byte
and leaves the first exceptional byte to the existing paired decoder. This
avoids repeatedly decoding a deliberately null paired reference without adding
another transition representation.

Some eligible expressions produce repeated matches before pairing can amortize
its entry and terminal handling. After 16 paired searches return within 16
bytes, the DFA rejects the paired table. The search that observes the
threshold keeps its result; the next search that requests paired transitions
or a pointer table releases the paired table's complete memory charge under
the existing exclusive cache protocol, and paired transitions stay disabled
until the next cache reset. The observation counter is a best-effort
performance hint shared by concurrent readers; races may change when rejection
occurs but cannot change matching behavior. A cache reset clears the hint and
allows the new cache generation to be evaluated again.

## Count Self-Loop Exit-Byte Scan

Fused DFA counting may skip the interior of a self-loop state with a Vector
byte-set scan instead of stepping the DFA one byte at a time. A state
qualifies when every byte either returns to the same state, carrying the match
bit when the state matches, or belongs to a small exit set: at most three
bytes, optionally combined with every byte from 0x80 through 0xFF. The exit set
is computed from the complete 256-byte transition row under the existing
exclusive cache mutation protocol and cached with the state. The scan resumes
DFA stepping at the first exit byte, so every byte that can change the state or
its match status is still processed by the DFA in input order. In a matching
loop, the scan records the match boundary implied by the skipped bytes. Trino
`regexp_count` with `.*(x|y).*` over long lines is the motivating shape:
on r9g, counting over 32 KiB of filler fell from 25.8 us to 3.6 us per
call (0.79 to 0.11 ns per byte) against 21.3 us for Joni, and over 1 KiB
from 911 ns to 148 ns against 699 ns for Joni; r8g and r8i moved by the
same or larger factors, and no other benchmark changed outside noise.

The route is limited to the count variant of the forward search. Counting needs
only each match end, and that variant is already kept separate from the
group-zero cursor because extra result handling in the cursor's generated code
measurably regresses short repeated boundary operations. The route runs only
for searches of at least 256 bytes, the same gate that requests paired
transitions, and it runs before that request so a qualifying search does not
build a paired table it will not use. Shorter searches cannot amortize the
exit-set construction and Vector setup. Unanchored-end, non-matching start
states and an available Vector API are also required; every other search keeps
its existing route.

In UTF-8, a dot loop leaves its state on every byte from 0x80 through 0xFF
because each multibyte character passes through intermediate states. A
dedicated high-bit flag marks these loops, and `findHighBitOr` finds the first
byte with its high bit set or equal to one of the low exit bytes. The UTF-8
start state is not the loop itself, so the route first walks up to five
ordinary DFA steps to reach a qualifying state. After each exit byte it walks
up to five steps to return to one. Five steps cover a four-byte character in a
matching loop, which first lands in an unflagged copy of the loop state because
match bits are delayed by one byte. When the first walk from the start state
does not reach a qualifying state, the route does not apply and the ordinary
search runs from the search start. When a later walk does not reach one, or
four consecutive scans skip fewer than 16 bytes, the search continues at the
current state, position, and match boundary; a later walk that reaches a dead
or full-match state ends the search instead. The paired and pointer hand-offs
need at least 256 bytes remaining, and a shorter remainder continues through
the compact loop. Without a pending match the search continues through the
ordinary paired or compact loop. With a pending first match it continues
through the absolute-pointer loop when a pointer table exists or can be
allocated, and through the paired after-match loop when the DFA already holds
or has requested paired rows or no pointer table can be allocated, such as
without native access. Paired rows prevent the pointer table from being
allocated. A requested pointer table is allocated when the exclusive search
that requests it ends, not during that search, which continues through the
compact loop. The compact loop leaves its fast path on every match transition,
and every transition out of a matching loop is one, so a matching dot loop over
text with a multibyte character every few bytes ran the rest of the text byte
by byte; on r9g, 32 KiB of `xaaaaaaaa` followed by repeated `éa` took 215 us
against 58.6 us before the route. Longest match searches keep the compact loop,
as they do after a paired search's first match. A paired search reached without
a pending match reports the whole search length to the short-paired-search
hint, as every other paired search does, so a short tail after a long scan does
not disable paired transitions. The existing self-loop consumer ignores exit
sets with the matching or high-bit flags, so its behavior is unchanged.

Searches whose start state never reaches a qualifying loop pay the initial walk
on every match. After eight consecutive searches in which the route does not
apply, the route stops attempting for the rest of the count call. A search that
retries is counted once. The counter is reset at the start of each count, so a
later text that does reach a qualifying loop still uses the route. As with the
paired-transition hint, the counter is a best-effort performance hint shared by
concurrent readers; races may change when the route engages but not the match
result, because the ordinary route computes the same match end.

## Once-Per-Count Start-Byte Scan Selection

Fused DFA counting selects the start-byte scan primitive per count call,
sampling candidate density from the beginning of the text. The selection only
moves from the scalar scan to the vector scan. When the first sample selects
the vector scan, the count keeps it for every match. When it selects the scalar
scan, a later search that reaches the start-byte scan samples again from its
start while at least 4 KiB remain, and the count keeps the vector scan from the
first sample that selects it. Searches served by the fixed-distance scan do not
sample, so dense input such as `[a-z]:[0-9]+` over repeated `a:1.` does not pay
a start-byte density sample per match. Below 4 KiB the check reports the span
as productive without sampling, so resampling there would promote the vector
scan for uniformly dense input. The per-search productivity check that can
reject candidate scanning altogether still runs for each match. Retries after
a cache reset keep the current selection; a promotion made by the search that
retried is discarded, and the retry samples again. The selected primitive
changes only how candidates are found, never which candidates the DFA
processes. Fixed-distance candidate selection still samples at each match.

Sampling at each match boundary made the choice depend on where the previous
match ended rather than on the input. On sparse capture input with a match
every 8 KiB, the sample taken just after one match landed on the literals of
later matches and fell back to the scalar candidate scan for the following
span. On r9g, counting the three matches in 32 KiB fell from 4.1 us to 1.9 us
per call, with r8g at 0.43x and r8i at 0.17x of the previous time. A vector
choice is therefore never demoted. A single sample from the start of the text
misjudges the opposite shape, where a dense prefix precedes a long sparse
span, so a scalar choice is checked again when a later search reaches the
start-byte scan. Dense input rejects every such sample and keeps the scalar
scan.

## Bounded Native-Memory DFA Transition Table

Eligible forward one-byte DFA searches may use a private native-memory table of
64-bit absolute-pointer transition entries after the existing paired-table
selection does not produce a usable route. This is internal memory only: it
does not execute native code, load a JNI library, create a file, or expose a
native pointer through the public API. Long searches request it immediately
when pairing is unavailable.
Short searches request it after 16 repeated calls, so one isolated short search
does not prevent the same DFA instance from later selecting a paired table. The
observation count is only a performance hint; races can change promotion timing
but not matching behavior. A finite-budget DFA initially allocates a small
row-rounded table and geometrically enlarges it as the graph grows. Each
allocation is limited so that the remaining budget can still hold the minimum
Java state behind every additional native row. The integer and object
transition tables remain authoritative, and every zero native entry delegates
to the unchanged object continuation.

Native access is optional. The restricted everything segment is initialized
only when the containing module has native access enabled; otherwise no native
allocation, warning, or initialization failure occurs. The owning automatic
arena is reachable through each raw traversal. Allocation, backfill,
incremental writes, publication, and reset use the existing exclusive DFA cache
mutation protocol.

The current allocation is permanently charged to the `DfaInstance` budget.
Growth occurs only under the existing exclusive cache-mutation protocol, so the
table may be copied and rebased without an active reader retaining the old
address. The retired automatic arena then becomes unreachable, and geometric
growth bounds the sum of unreclaimed prior allocations below twice the current
allocation. Reset clears and reuses the current segment without returning its
charge. Once an instance selects this native table, it does not switch to
object rows as the DFA grows. Failure to fund the next geometric expansion is
ordinary DFA budget exhaustion and follows the existing reset and
matcher-fallback policy.
Explicitly unlimited DFAs remain object-based because they have no finite
native-allocation budget. Paired transitions remain disabled across later
resets because pairing was evaluated first.

Every native-table row and entry is eight-byte aligned. Computed match, dead, and
full-match transitions use the otherwise-zero low address bits as tags; zero
continues to mean uncomputed. This lets the pointer loop preserve a match
boundary and finish computed terminal edges without converting the address back
to an integer row or entering the generic continuation. Normal pointers remain
aligned, and uncomputed or out-of-capacity entries still delegate to the
authoritative Java transition table. The tags add no allocation or memory-budget
charge and are cleared and backfilled under the same cache mutation protocol as
normal pointer entries.

The JDK 25 C2/AArch64 loop emits an extra dependent address operation and
retains an everything-segment range comparison that x86 C2 eliminates. The
native table remains the selected one-byte DFA representation when native access is
available because it removes Java object-row traversal while preserving bounded
memory accounting. The final report must characterize this architecture-specific
difference explicitly.

## Boolean Partial-Match Specializations

Full-Slice boolean `find`, `lookingAt`, and `matches` calls may use
compile-time plans for exact literal, nullable-start, contains, equality,
prefix, and suffix expressions. `Re2.find` caches its strategy in a primitive
field to avoid a dependent metadata load in tiny direct calls. The direct
strategy uses one byte. The shared single-byte matcher cache adds one reference,
bringing the `Re2` layout to 96 bytes with compressed references. A Trino scan
plan dispatches on a second strategy byte that fits in the same 96-byte layout.
When a pattern also has a scan plan, full-Slice `find` keeps the literal
contains, prefix, equality, and final-line equality kernels, which answer
without reading past the literal; other boolean strategies yield to the plan.
Scan-plan boolean calls also stop once a match is certain, as described in
the [scan-plan guide](docs/benchmarks/TRINO_SCAN_PLAN.md#integration).

These plans apply only when normalized expression analysis proves equivalent
existence semantics. APIs that accept a range, return boundaries or captures,
or produce a `MatchResult` retain the ordinary DFA, OnePass, BitState, and NFA
cascade when no Trino scan plan applies. Trino's explicit single-byte matcher
also shares the byte scanner; RE2 and Java matcher construction retain their
existing routes. Unsupported shapes, position-dependent assertions, and other
unproven forms retain the ordinary path. The single-byte table is initialized
lazily; subsequent direct boolean calls allocate no matching storage.

Nonempty exact-literal counting directly reuses `Prog.prefixAccel`, the existing
whole-literal prefix scanner, and advances by the matched literal length.
Empty-pattern advancement is unchanged. The new core single-byte boolean/count
route applies only to inputs of at most 64 bytes; larger inputs retain the DFA
and its ordinary failure fallback. Trino's existing byte count and explicit
matcher routes share the same table without changing their input-size policy.
Capture-retaining iteration remains generic.
The table's retained size is reserved before a DFA can snapshot its memory
budget. If it does not fit, the pattern keeps its generic execution route.
Trino delegates to this cache instead of retaining a second table.

Targeted Java 25 measurements on C9g, C8g, and C8i retain this bounded sharing.
Unconditional scalar literal and byte-table counting were rejected because
they bypassed the DFA's accelerated scans on long sparse inputs. The 64-byte
limit is conservative, not a claim of a universal scanner crossover. Direct
prefix-scanner reuse improves dense literal counting about fivefold on all
three CPUs while preserving sparse scanning on Graviton. The retained C8i
sparse-literal trade-off is 1519 to 1685 ns over 32 KiB, about +166 ns,
+10.9%, or +0.0051 ns/byte, with effectively zero allocation. Both versions
use the same scanner; surrounding count-loop code and compilation differ.
Instruction-level attribution has not been established.

The focused C8i native whitespace-contains control changes from 27.49 to
36.29 ns, +8.80 ns or 32%, with effectively zero allocation. Safe mode adds
0.44 ns, and neither Graviton CPU regresses. This pattern still selects the
general DFA route and bypasses the new byte scanner. Dispatch code size and
compiled layout change, making JIT/inlining sensitivity plausible without
establishing a specific instruction-level cause. Generic iteration stays
within one percent on all three CPUs, and key/value contains adds at most
0.97 ns. The small-operation losses and the eight-byte object growth are
accepted for the larger measured literal/delimiter gains, not treated as noise
or hidden in an aggregate. These are targeted qualification results, not a
complete release benchmark refresh.

The strategy byte adds one fixed dispatch operation to ordinary calls. Future
changes must keep that cost independent of input size, allocation-free, and
within the protected-path gate on Intel and Graviton.

## Trino LIKE Frontend

`TrinoLikePattern` is a separate language frontend rather than an RE2 dialect.
It compiles Slice pattern bytes and matches complete Slice inputs. On valid
UTF-8 input, `%`, `_`, and escape semantics follow Trino's SQL execution path,
using Trino `LikeMatcher` with optimization disabled as the semantic authority.
Malformed input and supplementary literals deliberately differ, as described
below.

A pattern that is not valid UTF-8 throws `TrinoLikePatternSyntaxException` at
its first invalid byte, as RE2 and Java regex patterns do. This deliberately
differs from Trino, which decodes such a pattern with the JVM's replacement
behavior; a malformed pattern has no specified meaning, so it is a syntax
error. Syntax-error byte offsets are relative to the pattern Slice.

Wildcard runs are normalized during parsing. Exact, prefix, and suffix forms
use direct byte operations. Ordered literals use the shared
`OrderedLiteralMatcher`: fused first-byte and last-byte SWAR candidate masks
find sparse candidates, and a KMP fallback preserves linear progress once
failed candidates compare more bytes than the search has covered, plus a small
budget. Patterns containing `_` use a dedicated LIKE wildcard automaton. This
frontend does not translate LIKE into a regex string and does not fall back to
the RE2 engine.

Contains of a literal of two or more bytes scans for the literal's first and
last bytes together, in a call-free word loop that stops at the first possible
candidate, then verifies candidates out of line with the shared kernel's KMP
handoff, counted from where the search started; a one-byte literal uses
`Slice.indexOf`. The pair scan beats a first-byte scan when the literal's
first byte is common and costs a little when it is rare. On aarch64 C2 cannot
encode the SWAR constant `-0x0101010101010101` as an immediate and rebuilt it
every word, so both loops read the positive `0x0101010101010101` from a field,
which C2 keeps in a register. On the normative rows gate it took 0.40x the
time of `Slice.indexOf` on r8i and 0.43x on r9g, and beat Trino's LIKE in 51
and 48 of 55 grid cells, against 16 and 18 for `Slice.indexOf`, with no major
regression; r8i accepts English `low-selective`, 17.82 to 21.49 ns per row
(1.21x).

r8i also accepts two edge losses on 32 KiB synthetic inputs filled with one
byte, both still over 5x faster than Trino's LIKE, as exceptions to the gate's
edge rule. On `CONTAINS_ABSENT`, 1.12x, the input is all `x`, so the literal's
first byte never appears and the pair scan does more work per word than a
first-byte scan. The rest of the 1.31x on `CONTAINS_LATE` appeared with the
verification loop reading the SWAR constant from a field, and only when the
scan reaches a candidate; its mechanism is not established. Alternatives
measured:

- `Slice.indexOf` stops at every occurrence of the literal's first byte, so it
  lost to Trino's LIKE when that byte was common.
- The shared kernel's ordered loop checks candidates inside its word loop,
  which C2 then neither keeps innermost nor unrolls; on x86-64 it took 1.97x
  the time of `Slice.indexOf` on long GH Archive rows whose first byte is
  rare.
- An adaptive scan, by first byte until failed candidates grow dense and then
  by both ends, lost Chinese short rows on r9g by up to 1.47x and won less
  overall, 0.61x on r8i and 0.56x on r9g.

Interpreted ordered literals and Trino regexp `contains` search with the
ordered loop, which verifies in line. The same call-free loop with field
constants took 0.34x the time of the ordered loop on r9g regexp rows, but
splitting verification from the scan costs on short rows with frequent
candidates, such as TPC-H Q13, so these callers keep verifying in line.
Generated LIKE emits one search for contained and ordered literals alike, so
its ordered searches split too and accept 1.06x to 1.07x on Q13 for the
split's gain on the normative rows; see [generated Trino LIKE
kernels](#generated-trino-like-kernels).

The LIKE and Trino-regexp parsers remain separate language frontends, but they
share the ordered-literal execution kernel when analysis proves equivalent
semantics. Trino regexp uses that kernel only for boolean `contains`; count,
position, extraction, replacement, split, and unsupported shapes retain the
complete RE2 execution route.

There is deliberately no literal-count cutoff between ordered scanning and the
DFA. On both target architectures, candidate distribution—not counts of 2, 8,
or 32—determined the winner. A C8i 32-literal dense-at-start match is the one
protected loss: 335.7 ns with ordered scanning versus 225.7 ns with the DFA.
The same ordered route improves the other 23 C8i protected rows, every C9g
protected row, and large sparse or late-miss rows by up to tens of times. A
runtime near-cursor counterfactual reduced the dense loss by 51 ns, but expanded
C2 main code by 61% on C8i and 46% on C9g and caused stable 6-16% protected
regressions. That hybrid is rejected; the 110 ns absolute dense cost is retained
rather than discarding the general wins or adding an input-distribution
heuristic.

Compiled patterns are immutable and thread-safe. General wildcard matching uses
one growable workspace per thread shared across all compiled LIKE patterns, so
the first qualifying match on a thread may allocate while warmed repeated
matching does not. There are no capture, boundary-result, rewrite, or partial
input operations in the LIKE API.

The final benchmark campaign must compare every route with Trino's semantic and
optimized LIKE implementations. Exact, prefix, and suffix operations are
expected to be dominated by fixed call overhead, so report absolute time as
well as ratios for those tiny operations.

### Malformed LIKE Input

Every LIKE plan matches as if each invalid UTF-8 sequence in the input had been
replaced by U+FFFD, one replacement per maximal invalid subpart (Unicode
chapter 3, as in the WHATWG Encoding Standard). Such a subpart is one
character: `_` consumes it, `%` spans it, and it never equals a literal. The
JVM's decoder is not the definition; it replaces an encoded surrogate such as
`ED A0 80` with one U+FFFD rather than three.

One decoding serves every plan. Pattern literals are valid UTF-8 after parsing,
and under this decoding every ASCII byte and every lead byte starts a new
character, so a raw byte occurrence of a literal always starts and ends on
character boundaries. Exact, prefix, suffix, contains, ordered-literal, and
short-literal plans therefore keep comparing raw bytes without decoding, while
the literal-gap and wildcard matchers step over characters with the same
shared width rule. Because `%` and `_` count the same characters everywhere,
normalizing `%_` and `_%` stays sound.

On valid UTF-8 this equals Trino's SQL LIKE except for one case. Trino's NFA
stores literal characters as UTF-16 units, so a supplementary literal between
wildcards of a pattern containing `_` never matches; Trino returns false for
`%😀_%` against `😀x`. That is a Trino defect; Regulator returns true.

On malformed input Regulator deliberately differs from Trino. Trino's NFA takes
a character's width from its lead byte without checking continuation bytes,
so an overlong `C0 AF` is one character, `C1 A3` decodes as `c`, and an `F0`
swallows the next three bytes, including ASCII. A byte it cannot decode fails
the whole match, even inside `%`, while its raw-byte plans ignore the same
byte, so its result can depend on the plan a pattern selects. Trino treats
malformed `VARCHAR` as garbage in, garbage out; the maximal-subpart rule is
the understandable one, and the pinned corpus test lists every row where it
differs from Trino.

A U+FFFD literal in a pattern matches only the bytes `EF BF BD` in the input,
not an invalid sequence that the rule reads as U+FFFD.

### Short LIKE Literal Comparisons

Exact, prefix, and suffix literals of 1-16 UTF-8 bytes use precomputed
overlapping word comparisons. Longer literals retain Slice equality. Check
input length first, then the short comparator, then the ordinary strategy
switch. Complex plans keep their interface call in a separate helper. This
shape reduces `matches()` from 270 to 187 bytecode bytes, with a 78-byte helper;
it changes compilation boundaries, not complex matching algorithms.

Focused Java 25 qualification on C8i, C8g, and C9g compared isolated callers,
constant-pattern generated callers with mixed shared profiles, and callers
that rotate patterns. Against the short-kernel prototype, the selected shape
saves 1.08-1.45 ns, or 20-24%, on six-byte suffixes in the interleaved matching
profile. Exact costs 0.01-0.06 ns and prefix costs 0.11-0.24 ns. These short
cases remain faster than Trino. The original Intel literal-gap benchmark
recovers from 3044 to 2801 ns, close to the 2776 ns pre-kernel control.

Accepted costs versus the prototype include C8g ordered matching, 4158 to
4331 ns, and interleaved gap matching, 4220 to 4352 ns. These are +4.2% and
+3.1%, or about +0.0053 and +0.0040 ns per input byte. The latter gives up a
prototype gain and returns close to the pre-kernel control. Long C8g literal
comparisons also lose several nanoseconds in some caller profiles; the
interleaved 512-byte suffix changes from 30.43 to 33.82 ns with overlapping
fork ranges. Normalize that comparison against its 512 compared bytes, not
the full input. Construction remains 144 bytes per pattern, retaining the
short kernel's 40-byte increase. C9g exact compilation plus first use remains
slower than the pre-kernel control, 15.71 to 16.84 ns.

A merged comparison branch sometimes wins long ARM literals but costs about
0.4-0.5 ns on short ARM exact/prefix cases and fails to recover the original
Intel gap regression. An explicit concrete-matcher switch enlarged the method
and hit HotSpot's hot-method-size inlining limit. Neither was retained.
Generated-code inspection confirms different inlining and compilation layouts,
but does not establish the causal instruction for every timing change. The
selected structure is the best tested compromise, not a universal optimum or
a full Trino SQL benchmark. Preserve its shape unless new qualification
supports changing it.

## Folded Prefix Candidate Scan

Case-insensitive prefix acceleration keeps upstream's maximum nine-byte prefix
but replaces ShiftDFA with fused first-byte and last-byte SWAR candidate masks.
Each iteration evaluates eight candidate starts. Only positions where both
folded boundary bytes match run the complete folded-prefix comparison. This
route does not use the Vector API, keeping the candidate loop compact and
avoiding architecture-dependent vector setup on short prefix scans.

This is a performance-only deviation. Upstream ShiftDFA carries eight serial
table-dependent shifts through each unrolled block. The fused scan uses two
independent loads and parallel masks, which fits HotSpot code generation better
on the supported Intel and Graviton hosts. Randomized equivalence tests compare
the accelerated result with a scalar folded-prefix search.

## Non-ASCII Literal Vector Scan

Case-sensitive multi-byte prefixes whose first byte is non-ASCII use fused
first-byte and last-byte candidate masks. Searches shorter than 1 KiB use SWAR.
Longer searches use the platform's preferred Vector API species when the
application resolves `jdk.incubator.vector`; otherwise they retain the SWAR
route. ASCII and single-byte prefixes retain repeated-byte scanning.

Vector linkage is isolated in `VectorPrefixScanner`, so applications can load
and use the engine without resolving the incubating module. Every surviving
candidate is compared with the complete prefix. Front/back byte selection
keeps preprocessing bounded and the integrated scanner compact.

## Literal Probe Byte Selection

Like upstream, a case-sensitive literal prefix scan searches for the literal's
first byte by default. Probing a rarer byte is an opt-in deviation. The `Re2`,
`TrinoRegexp`, `JavaRegexp`, and `RustRegexp` compile options take a
`ByteFrequencies` distribution, which defaults to `ByteFrequencies.uniform()`;
it gives every byte the same probability and so keeps first-byte scanning. A
caller can instead choose the `englishText()` or `json()` preset, a
distribution built from byte counts with `fromCounts`, or one learned from
sample data with `builder()`. A compiled pattern keeps only the chosen offsets.

Under a chosen distribution, case-sensitive multi-byte prefixes with an ASCII
first byte, and multi-byte leading literals in scan plans (Trino and RE2), scan
for one probe byte. Every distribution follows one rule: the probe is the rarest
byte after the first, earliest on ties, used only when its estimated probability
is less than a quarter of the first byte's; otherwise the probe remains
offset 0. The factor keeps the first byte when the estimate is close, because an
estimate is not the searched text. Each probe hit is shifted back to its
candidate start and confirmed with the complete literal. The RE2 prefix scan
checks the farther literal end before the full comparison. The Trino scan
returns the same first whole-literal occurrence as the first-byte scan, so
candidate attempts, failed work, and the work budget are unchanged. Density
hand-off never applies to literal-led plans.

Probing is opt-in because a distribution that does not match the searched data
moves probes onto bytes that are common there. A mismatched distribution can
make literal scans several times slower per byte than the default, because the
probe byte occurs more often than the first byte; it never changes what matches,
and the cost stays linear in the input. On r9g, probing `/` for `https://` and
`http://` ran 6.7-8.5x slower than the first-byte scan over access-log text,
which carries about twelve slashes and one `h` per line.

The presets were scored by modeled candidate counts, not timings, over 33
literals, against the probe each scored corpus's own distribution chooses, on
corpora the presets were not built from. On English prose (rebar's sherlock,
OpenSubtitles English, and rebar's leipzig-3200, Project Gutenberg's Mark Twain
collection), `englishText()` agreed with that probe on 86.9% of rows and made
no row worse than the first byte. On two GH Archive event hours, `json()`
agreed on 87.9%, also with no row worse. On each other's format, `englishText()`
made 6 of 66 JSON rows worse, the worst probing `/` for `GET /` with 82 times
the first byte's stops, and `json()` made 4 of 99 English rows worse, by at
most 7.9 times. Over log, access-log, source-code, CSV, Russian, and Chinese
corpora, `englishText()` made 6.9% of rows worse and `json()` 5.9%. Every
preset must therefore match the data it searches, and no preset covers mixed
or log data.

A distribution learned from a few hundred kilobytes of whole lines sampled
across the searched data is the most reliable choice. JMH qualification uses
`BenchmarkLiteralProbe` with the existing prefix and scan-plan benchmarks.

Each preset is the unweighted mean of the add-one smoothed distributions of its
sources, with weight one per source. The English sources are three Leipzig
Corpora Collection corpora of 100,000 sentences (CC BY): news from 2025,
Wikipedia from 2016, and web text from 2018. Their sentence cleaning leaves out
`#`, `=`, brackets, braces, `_`, `|`, `\`, `^`, and almost all `@`, so
`englishText()` rates those bytes very rare. The JSON sources are the example
documents of simdjson's `jsonexamples` (Apache-2.0), one file per document. Its
pretty-printed, compact, and escaped copies of a document are left out, so a
reformatted document does not count twice, and so are `github_events.json` and
an array of random numbers. A JSON preset that pooled all bytes, so that large
files dominate, agreed on only 83.3% of GH Archive rows. The presets may probe
any ASCII byte. Bytes at or above `0x80` are pinned to the most common ASCII
value, so no preset probes them. `JavaRegexp` and `RustRegexp` scans, and `Re2`
scans outside a scan plan, move the probe only for multi-byte, case-sensitive
literals with an ASCII first byte, so a literal led by encoded text keeps
first-byte scanning there. Scan plans (Trino and RE2) move a plan's leading
literal, including one led by encoded text, to a rarer ASCII byte.
`tools/byte-frequencies` pins every source of both preset tables and regenerates
them exactly; only the derived numbers are committed.

Counts and learned distributions are add-one smoothed:
`(count + 1) / (total + 256)`. They measure the data they were built from, so
they may probe any byte after offset 0, including punctuation, digits, and
bytes at or above `0x80`.

Under every distribution, fused first-byte/last-byte strategies, paired
candidate scans, case-folded prefixes and literals, required-literal checks,
and single-byte literals keep first-byte scanning. This is a performance-only
deviation. Randomized tests compare the RE2 site with scalar literal searches
in UTF-8 and Latin-1, and the Trino site with Joni, under a distribution learned
from a short JSON sample and under a supplied distribution that probes
punctuation, digits, and UTF-8 bytes; the same comparisons under default
options cover first-byte scanning.

## Small Candidate-Byte Vector Scan

Forward DFA start-byte and fixed-distance candidate sets containing exactly two
or three bytes use a packed, allocation-free Vector scanner for searches of at
least 1 KiB when `jdk.incubator.vector` is resolved. Other candidate counts,
short searches, and applications without the optional module retain the
branch-free scalar membership-table scanner.

Preferred species wider than 128 bits sample four 16-byte windows before using
Vector scanning on inputs of at least 4 KiB. Four sampled candidates demote
only the Vector primitive; the existing candidate-scan route remains active.
Preferred 128-bit species do not sample because the sample would duplicate most
of the vector work. Scalar and Vector scanning remain separate call targets so
a fixed-distance fallback cannot add Vector eligibility work to the scalar hot
loop.

This is a performance-only deviation. Every candidate is still processed in
input order by the existing DFA, and randomized tests compare the scanner with
the scalar first-position result.

## Bounded Character-Class Counting

Counting a complete, unanchored, non-nullable greedy repetition of one character
class scans decoded input once instead of running a complete matcher for every
result. Each maximal matching run is partitioned according to the repetition's
finite minimum and maximum, preserving ordinary leftmost-first match counts.

Eligible UTF-8 patterns lazily build an exact Unicode membership bitset of 136
KiB; Latin-1 patterns use 32 bytes. Construction is single-writer and the table
is retained by the compiled pattern. This fixed bound is independent of
`maxMemory`, which remains the upstream-compatible program and DFA planning
budget, and it does not scale with the expression or configured budget.
Unsupported shapes continue through the ordinary DFA or matcher path. Invalid
UTF-8 bytes never match the bitset, while a valid encoded U+FFFD remains an
ordinary code point.

`Re2.count` and `JavaRegexp.count` expose this counter and the existing DFA
counting path. Unsupported shapes use a fresh zero-capture matcher per call;
callers can retain a matcher and iterate when workspace reuse matters.
Qualification on C9g, C8g, and C8i confirms the bulk-counting benefit, while
whitespace single-use counting pays for the existing table initialization:
roughly 1.5 to 2.2 microseconds extra on ARM and 10 microseconds on Intel.
Keep the public counting APIs with this documented first-use trade-off and
retain the existing matcher paths. A future change to counting initialization
or fallback routing requires its own qualification.

## Retained Regexp Tree

`Re2` retains the immutable parsed `Regexp` tree and exposes it only within the
package. `Prefilter` consumes that exact tree, and lazy reverse compilation reuses
the required-prefix suffix instead of parsing the pattern again. The retained
reference is an intentional memory-for-consistency tradeoff.

## Match-Every-Byte Shortcut

The full-match shortcut is enabled only for patterns that accept every possible
byte sequence: `\C*` in UTF-8 mode and dot-all `.*` in Latin-1 mode. UTF-8 dot-all
`.*` is deliberately excluded because malformed UTF-8 must still be rejected.

## Bounded Direct BitState Capture

Eligible unanchored leftmost-first searches through a reusable matcher that
request subgroup captures run BitState directly when its complete visited
bitmap fits within 256 KiB. This single pass replaces forward and reverse DFA
boundary searches followed by an anchored NFA capture pass. Inputs above the
fixed bound, programs that cannot use BitState, and stateless caller-buffer
operations retain the ordinary engine cascade.

Upstream caps this bitmap at 256 Kibit (32 KiB). The Java port deliberately uses
more temporary memory because it targets server workloads where a bounded
256 KiB workspace is small compared with input pages and can replace multiple
boundary and capture passes.

## Matcher-Owned Capture Workspaces

`Re2Matcher` owns reusable BitState and NFA scratch storage. BitState reuses its
visited bitmap, capture scratch, and bounded initial traversal jobs. NFA reuses
its sparse queues, traversal stacks, best-match storage, and primitive capture
thread arena. These workspaces are private to the non-thread-safe matcher and
never retain input bytes or caller group arrays.

An exceptional NFA exit invalidates its workspace before propagating the
failure. This prevents a later search from observing partially live capture
threads or queue state.

The low-level `Re2.findInto`, `lookingAtInto`, and `matchesInto` APIs remain
stateless and allocation-free. They do not acquire hidden shared state or a
reusable workspace. This separates the ordinary Java matcher lifecycle from the
caller-managed low-level contract.

## Allocation-Free Matcher Regions

`Re2Matcher.reset(input, start, end)` binds a logical region of an existing
Slice without constructing a Slice view. Public matcher and snapshot offsets
remain relative to the complete logical Slice, consistent with Java Matcher and
the one-shot APIs. DFA start-state analysis treats the region boundaries exactly
like the boundaries of an actual Slice view. This is required for anchors, empty
matches, word boundaries, and malformed or split UTF-8 input. Rust patterns are
the exception; see [Rust Frontend](#rust-frontend).

The region is represented only in matcher and DFA setup state; protected DFA
transition loops remain unchanged. A real Slice view is constructed lazily only
when execution reaches a capture engine; a line rejected by DFA constructs no
view. `MatchResult` retains the caller's original Slice and stores absolute
logical offsets.

## Immutable Multi-Pattern APIs

`Re2Set` and `FilteredRe2` use builders for pattern collection and return
immutable compiled objects. This removes public add/compile/match state machines
and makes empty collections fully usable. High-level match results use primitive
pattern-ID arrays instead of caller-mutated boxed lists. A `FilteredRe2`
collection uses one encoding and exposes its immutable canonical atom list.
Callers scan `canonicalizeText(input)` and pass primitive atom IDs from the
external scanner; mixed UTF-8 and Latin-1 patterns are rejected by the builder.

## Bounded Candidate-Start Cursor

Eligible reusable matchers use a private candidate-start cursor for repeated
group-zero searches. The cursor scans the forward DFA's start-byte candidates
and verifies each candidate with an anchored first-match traversal. This avoids
the ordinary forward match followed by reverse boundary recovery for dense,
variable-length matches such as the Date Rebar workload.

Eligibility is deliberately narrow: first-match semantics, no required prefix,
no anchors or empty-width instructions, a non-nullable variable-length program,
and an available start-byte accelerator. The route has a linear reset-scoped
work budget and a 128-transition limit per candidate. Exhausting either limit,
encountering an unsupported match shape, or failing DFA cache mutation falls
back to the existing forward-plus-reverse matcher path for the rest of that
matcher reset. The cursor is re-enabled by the next reset.

The implementation uses the existing DFA reader and exclusive-cache mutation
protocol. Cached object transitions still require recovering the authoritative
state offset before the end-of-text transition; tests cover this warm-cache
case directly. The cursor adds no public API and does not change stateless,
capturing, longest-match, anchored, nullable, or fixed-length searches.

The final campaign must compare this route with the ordinary
forward-plus-reverse path and native RE2, including dense candidates and
protected non-capture controls.

## Reusable Group-Zero Forward Cursor

Eligible group-zero matchers lazily retain the forward first-match DFA and its
fixed-distance byte candidates after the bounded candidate-start route declines
the search. Repeated `find()` calls then use the existing count-search traversal
to return one forward boundary without rediscovering the DFA, match mode, and
acceleration state for every match. Fixed-length expressions derive the start
from that boundary; variable-length expressions retain the existing reverse DFA
boundary recovery.

Eligibility is limited to first-match programs without a required prefix,
anchors, or text-dependent assertions. Empty matches are still handled by the
existing empty-at-start check before the cursor runs. DFA allocation failure or
cache-search failure falls back to the ordinary matcher path, and the cursor
uses the same reader and exclusive-cache mutation protocol as other DFA
searches. It adds no public API and does not change capturing or longest-match
execution.

## Extended OnePass Captures

Pinned native RE2 stores OnePass capture actions in spare bits of a 32-bit
transition word, which limits OnePass execution to group zero plus four explicit
capture groups. Java retains that exact transition representation and hot loop
for requests within the native limit.

For one-pass programs that contain higher capture slots, compilation additionally
builds parallel 64-bit capture masks for match and transition actions. The
separate extended loop iterates only the set capture bits and supports up to 32
groups including group zero. The sidecar is charged to the OnePass share of the
compiled memory budget. Requests beyond 32 groups retain the ordinary BitState
or NFA fallback.

This representation is a performance deviation, not a matching-semantics
deviation. It avoids sending deterministic high-capture expressions through
BitState solely because of native RE2's packed-word limit, while keeping the
more common small-capture loop unchanged.

## Java Representation Adaptations

The implementation uses garbage collection, Java collections and arrays, integer
sentinels where C++ uses distinguished pointers, and unsigned-byte masking with
`& 0xFF`. C++ logging and debug-only assertions are not part of the public Java
contract. These representation changes are acceptable only when tests establish
the same externally observable behavior as pinned upstream RE2.

## Trino Text-Dependent Assertions And Full Case Folding

`TrinoRegexp` implements Joni's final-LF `$`, multiline `^`, Unicode `\b` and
`\B`, and multi-code-point case folding. These are frontend semantics and do
not change the RE2 language accepted by `Re2.compile`. Joni's multiline `^`
matches at the start of empty input and after an internal LF, but not at the
position after a terminal LF.

Final-line, multiline begin-line, and Unicode-boundary assertions require
surrounding input context that is not represented by the ordinary RE2
empty-width flags. The semantic program retains these assertions and remains
authoritative whenever explicit captures are observable or the lowered form
cannot preserve group-zero boundaries.

Trino multiline begin-line uses the two spare DFA empty-width bits. A forward
state after LF keeps the ordinary begin-line bit pending; transition
construction supplies the Trino bit only when the next symbol is not
end-of-text. The reversed program supplies the corresponding end-line bit when
it crosses an internal LF or reaches the original input beginning, while
suppressing it before a terminal LF. Start-state caches distinguish internal LF
boundaries from a terminal LF, including nonzero Slice offsets. Other
text-dependent assertions remain ineligible for DFA execution, and OnePass
remains disabled for all of them.

Non-nullable counts containing only the Trino line assertions use the same DFA
route and do not allocate a matcher. The exact multiline begin-line expression
uses a count-only plan that returns one for the input start plus the number of
LF bytes before the final byte. Other operations retain the semantic program,
and other nullable expressions retain ordinary matcher iteration.

Boolean `find` and `lookingAt` operations use operation-specific plans:

- exact terminal-literal shapes such as `literal$` and `\Aliteral$` compare the
  literal at the absolute end or immediately before one final LF
- eligible compound terminal expressions compile a second program in which a
  terminal final-line assertion is lowered to `(?:\n)?\z`
- expressions that retain another text-dependent assertion or full case folding
  after lowering remain on the semantic program

The consuming lowering generally changes match boundaries. For group-zero-only
matching, the reverse lowered result is also boundary-equivalent when analysis
proves that the semantic expression is nonempty and cannot consume LF. The
semantic end is then the region end, excluding one final LF when present.
Nullable expressions, LF-consuming expressions, stripped required prefixes,
start-anchored expressions, and explicit capture requests retain the semantic
program. Boolean `matches` never uses the lowered boundary route, although exact
shapes may retain their pre-existing direct complete-match plan.

Count, group-zero extraction, position, split, and replacement without capture
references use the direct boundary route when eligible. The eligibility value
shares the existing boolean-strategy byte. Ordinary boolean `find` retains its
direct strategy load; the separate single-byte matcher cache accounts for the
current 96-byte `Re2` layout. The two forward programs and the lowered
program's DFA share the original forward memory budget. No warm DFA search loop
contains a frontend-specific branch; the Trino check runs only while an
uncached transition is constructed.

Boolean OnePass execution uses a dedicated primitive loop without capture
scratch arrays. Keeping it separate prevents mixed capture and non-capture call
profiles from making allocation depend on HotSpot scalar replacement. Capture
execution retains its existing loop and finalization behavior; this is a shared
engine specialization rather than a Trino-specific branch.

Full case folding is Unicode 18.0 full case folding from the pinned database,
as described in Pinned Unicode Case Folding. Trino's pinned
`io.airlift:joni:2.1.5.3` dependency instead carries fixed jcodings fold tables
built from Unicode 4.1 data.

Unicode full case folding keeps dotted capital `İ` and dotless small `ı` apart
from `I` and `i` outside its Turkic mappings, and so does Joni: `İ` folds only
to `i` followed by a combining dot above, and `ı` has no fold. An exhaustive
test checks the Trino rule against the JDK's full case folding comparison, for
every character the JDK assigns, on Java 26 and newer.

Keep the Unicode 18.0 mappings rather than reproducing omissions in Joni's
Unicode 4.1 data. Mappings added or changed since then differ in both
directions: capital `ẞ`, added in
[Unicode 5.1's case-folding data](https://www.unicode.org/Public/5.1.0/ucd/CaseFolding.txt),
folds to `ss` in Regulator but not in Joni, as does U+1DF95 from Unicode 18.0,
and capital glottal stop `Ɂ`, which Unicode 5.0 paired with the new small `ɂ`,
still folds with `ʔ` in Joni. Review changed expectations when moving the
database to a new Unicode version; do not remove newly supported folds merely
to retain Joni's old table contents.

Literals that require full folding compile to a shared-suffix graph whose size
is linear in the folded literal length. Patterns
that need only simple folding retain the ordinary compiler and reverse-program
routes.

Case-insensitive character classes fold in Joni's order. A bracket class's
members, nested classes, and set operations are evaluated without folding, and
the finished class is folded once; a negated class is folded before its
complement. Class and property escapes outside brackets are not folded. So
`(?i)\p{Lu}` does not match `a`, while `(?i)[\p{Lu}]` does, and
`(?i)[a-z&&[B]]` matches neither `b` nor `B`.

A positive case-insensitive class also matches every whole-character spelling
of a member's multi-character fold, as Joni's class string alternatives do:
`(?i)[ﬃ]` matches `ffi`, `ﬀi`, and `fﬁ`. The spelling is matched inside the
class and never merges with adjacent pattern literals, so `(?i)[ß]s` does not
match `sß`. A negated class consumes one character. Each spelling is built
from plain classes rather than a full-fold literal, so a class such as
`(?i)[ß]` keeps the reverse program and lowered program that full folding
would disable.

A run of literal characters written next to each other under the same
case-insensitivity folds as one sequence: its Unicode 18.0 full case folding
is compared with the text's. Escapes, `\Q...\E` quotes, extended-mode
whitespace and comments, an exact repeat of one, such as `{1}`, `{1,1}`, or
`{1}?`, and flag changes other than `i` do not end a run, so `(?i:\x66f)` and
`(?i:f(?s)f)` match `ﬀ`, and `(?i:sß)` matches `ßs`. Nor does a noncapturing
group without a quantifier; its contents act as if written in its place, so
`(?i:s(?:s))` matches `ß` and `(?i)(?:[a]s)s` matches `aß`. Captures,
alternation, every other quantifier, assertions, and classes end a run, even
when they match only the empty string or cannot match at all, so `(?i)ff{0}f`
does not match `ﬀ`. Any change of `i` also ends a run, so `(?i)s(?-i)(?i)s`
does not match `ß`. Simplification respects the runs the parser builds:
repetition expansion, coalescing, alternation factoring, and the removal of
empty or impossible nodes never split a run, and join two runs only when no
fold can cross their boundary.

Trino's
[regexp documentation](https://github.com/trinodb/trino/blob/c7503d170344c4e266f03fdb39e53c82aa7e3594/docs/src/main/sphinx/functions/regexp.md)
does not describe case folding, so for it Regulator reads
[Oniguruma's `doc/RE`](https://github.com/kkos/oniguruma/blob/4ef89209a239c1aea328cf13c05a2807e5c146d1/doc/RE),
whose rules Joni implements, together with what a user can predict from the
pattern. The run rule meets that test. The results below depend instead on how
pinned Joni tokenizes the pattern, so Regulator treats them as bugs of the fork
rather than Trino's intent, the judgment Language Authorities reserves for
Trino. Regulator does not follow these results of pinned Joni's tokenization:

- separate string nodes for escapes and quotes
- a limit on case-fold expansion: `(?i:sß)` rejects `ßs`, but `(?i)xyzsß`
  accepts `xyzßs`
- the rewrite of an exact repeat such as `(?i)ß{2}`, which then matches `sßs`
- the split of strings of mixed UTF-8 width: `(?i)aaİ` does not match its own
  text

These differences are separate from the Unicode-table difference above.

Trino's Joni regex functions pass the original Slice bytes to Joni; they do
not apply SQL `upper`/`lower`. Those functions use Slice case conversion and
are not regex-folding oracles. No input or pattern is converted through Java
String to implement matching.

Trino inputs are not validated as UTF-8. Trino patterns must be valid UTF-8;
unlike Trino's non-strict Joni path, which compares malformed literal pattern
bytes byte-for-byte, Regulator rejects such a pattern. Neither rule exposes a
separate Latin-1 fallback language.

Pinned Joni also misses a valid empty match for a nullable expression before
`$` at the end of some multibyte inputs, while Java `Pattern` finds it. This is
treated as a Joni search-optimizer defect rather than a Trino language rule.
`TrinoRegexp` follows the Java semantics and has a direct regression test for
this case. The final benchmark matrix must cover direct, lowered, short
OnePass, allocation, and ordinary-route final-line cases.

## Trino Specialized Byte Scans

The Trino frontend may select a bounded scan plan for literals, deterministic
character runs, optional bodies, nested captures, and terminal tails. Preserve
specified matching and capture behavior on valid UTF-8. Malformed Trino input
has a garbage-in, garbage-out contract, while bounded input access and normal
matcher lifecycle behavior remain required. This does not relax Java semantics
or authorize a public malformed-input opt-in. RE2 patterns select the same
plans, which report the ordinary engine's results on malformed input too, as
described in [RE2 Scan Plans](#re2-scan-plans). By default the plan performs no
runtime code generation; an experimental option may compile the plan into its
own class, as described in
[Generated Trino Scan Kernels](#generated-trino-scan-kernels). The ordinary
semantic program remains the fallback. The
[scan-plan guide](docs/benchmarks/TRINO_SCAN_PLAN.md) describes candidate
selection, resume rules, the executors, and optional-capture execution.

Plans may require the logical input start or perform unanchored search. An
unanchored plan must begin, after capture saves, with a literal, an exact
one-byte ASCII set, or an unbounded run of every byte except the one-byte
case-sensitive literal that follows it, and must contain a capture and a
variable run. A capture-free plan must contain a character run or a variable
literal repeat. These rules keep capture-free, fixed-width, and literal-only
patterns on their direct routes. A lowered final-line plan takes precedence
over a run-leading scan.

Other leading runs, such as `(\d+)zz`, `([0-9]+)a`, `(\w+)@(\w+)`, and
`([a-z]+)-([0-9]+)`, keep the ordinary engine, plain or ASCII-folded, as do
optional-leading shapes. Each candidate inside a run that fails to complete a
match rescans the rest of that run, so the work depends on the input, and every
measured search strategy for this family made some workloads slower than the
ordinary engine. A run of every byte except the one-byte literal that follows
it, such as `([^/]+)/`, `([^:]+):([0-9]+)`, or `([^,]{2,}),`, is admitted. Its
scan stops only at the delimiter, which the literal then matches, and this
shape measured faster than the ordinary engine with no loss.

Character sets must be ASCII-only or have uniform non-ASCII membership.
Variable repetition requires a continuation disjoint from the repeated set
across both arms of optional groups, so a run never gives characters back;
exact counts need no such proof. Finite bounds count characters, not bytes.
Exact and finite repetitions of a fixed literal sequence are admitted.
Repeated captures, unbounded literal sequences, nested repetitions, other
repeated multi-operation bodies, and whole-pattern nullable expressions keep
the ordinary engine.

Fixed budgets bound each plan's retained memory and each attempt's retries,
and keep each search linear in its input:

- The complete plan and its literal storage are charged against the forward
  DFA memory budget. A plan that does not fit keeps the ordinary route, so a
  pattern's retained memory stays within the bound already applied to its DFA.
- Optional literals, optional forks, and literal-repeat copies share four retry
  checkpoints, as in ClickHouse, so an attempt explores at most sixteen
  combinations and its retry state fits in two longs and an int without
  allocation.
- A plan has at most 32 operations plus one terminal operation, so operation
  indices fit the retry stack's six-bit fields. Nesting depth is limited to
  sixteen and explicit repeat counts to 1000, matching ClickHouse. A literal,
  including a repeated literal sequence's mandatory copies, is limited to 256
  bytes.
- Every unanchored search continues on the ordinary engine, from the plan's
  resume position and with the same context, once the bytes its failed
  attempts examined exceed 2048 plus four times the distance the search has
  advanced. Without this budget, inputs such as `x([^z]+)zq` over repeated `xa`
  rescan the same run from every candidate, which is quadratic in one search.
  The budget belongs to one search, so plans stay immutable.
- Full-Slice boolean `find` on a run-leading plan whose consuming operations
  accept only ASCII bytes hands off once to the ordinary engine after a group
  of eight failed attempts that average fewer than 32 bytes apart, when at
  least 256 bytes remain. Both engines make the same existence decision for
  such a plan on any input. The plan answers sparse and early matches without
  DFA setup, and the ordinary engine avoids trying each dense candidate.

On malformed input, a search that hands off may report the ordinary engine's
match instead of the plan's. Every operation applies the same budget from the
same search positions, and the density handoff and ranged boolean `find` use
the ordinary engine only where both engines agree, so `find`, `count`,
`extract`, `position`, `extractAll`, replacement, and split report one match
sequence.

Retain restricted ASCII case folding when every Unicode fold cycle remains in
ASCII and requires no full multi-character fold. Folded plans use separate
executors and strategy values. On R9g, folded anchored, optional, unanchored,
run, and mixed workloads take 0.20 to 1.10 times the ordinary engine's time for
`contains`, extraction, and replacement, with a geometric mean of 0.38. The
accepted costs are folded optional boolean matching, at about 1.10 times the
ordinary engine's time, and unanchored folded compilation, at about 1.19 times.
Ordinary URL and mixed operations remain faster than the ordinary engine on R8i
and R9g.

Eligible scan plans intentionally add bounded cold compilation work and retained
state. Accept that cost only while warm public operations materially improve on
all target architectures and protected routes remain stable. Short-lived pattern
workloads must keep compilation visible when deciding whether to extend coverage.
Bounded literal repeats and unanchored byte-set scans meet this bar. On all three
target hosts, focused before/after measurements improved every `LITERAL_REPEATS`
operation, and every `UNANCHORED_SET` operation except `count`, which was
unchanged. The protected controls stayed stable except for one accepted cost:
unanchored byte-set plans make short key-value `contains` calls, such as
`([A-Za-z_][A-Za-z0-9_]*)=([^ ]+)` over `status=200`, take 1.08 to 1.16 times
the ordinary engine's time on R9g and R8i. This is accepted because those calls
remain about ten times faster than Joni.

Rejected development alternatives are recorded to prevent repeating them
without new evidence:

- Separate boolean operation arrays regressed the URL and mixed controls.
- Selecting the ordinary boolean route by optional count and short input length
  regressed the targeted retry workload.
- Fusing captured runs in richer plans improved isolated cases but regressed
  the representative interleaved mix on all three target hosts. Keep explicit
  capture saves outside compacted linear plans.
- A distinct captured-run opcode regressed individual and grouped workloads.
- Saving capture start before scanning regressed mixed capture and extraction.
- Compacting tails and delimiter captures in richer builder plans improved
  dot-all mixes but more than doubled normal-dot mixed boolean time on Arm.
  Keep those representations unchanged until measured as a separate optimization.
- A byte opcode removed a native dependent load but regressed core URL operations;
  retain enum dispatch until a measured alternative justifies changing it.
- Routing every eligible unanchored boolean call to the ordinary engine
  regressed dense inputs whose first candidates match; the plan answers those
  without DFA setup.
- Handing off after the first failed attempt regressed sparse and miss-heavy
  inputs, and handing off with little input remaining regressed short inputs.
- Counting eligible plans with the DFA regressed dense counts, and the
  required-literal check already ends counts over inputs without the literal.
- Checking a required literal for literal-leading plans regressed sparse URL
  and header workloads, because the candidate scan already finds the literal.

Other protected controls retain unresolved differences. These are not accepted
release trade-offs and require qualification before broad adoption. The
development campaign evidence is in the private archive; see the
[archive index](docs/benchmarks/trino-scan-plan-archives.json).

## RE2 Scan Plans

RE2 patterns select the same scan plans as Trino patterns: the same eligible
shapes, ASCII-folded ones included, reached by the same operations with the
same memory charge, and on by default. Trino captures and extraction run about
eight times faster on a plan than on the ordinary engine, and nothing in those
shapes is specific to Trino. The frontend chooses a plan's language explicitly,
so Java patterns and `Re2.compileParsed` build none.

- An RE2 plan reports exactly what the ordinary engine reports on every input,
  including malformed UTF-8; RE2 has no garbage-in, garbage-out contract. Only
  operations that can consume a byte at or above `0x80` read input differently:
  runs admitting non-ASCII characters, with their bounded counts and minimums,
  delimited captures, and terminal tails. They consume a non-ASCII character as
  the ordinary engine's byte ranges do, accepting overlong three- and four-byte
  forms, encoded surrogates, and values above U+10FFFF, and rejecting bare
  continuation bytes, `C0`, `C1`, `F5`-`FF`, and truncated sequences. Trino plans
  are unchanged.
- The analysis, builder, proofs, budgets, candidate scans, and routing stay
  shared, so a coverage change reaches both frontends. Every skip proof holds
  under the RE2 rule, as the
  [scan-plan guide](docs/benchmarks/TRINO_SCAN_PLAN.md#re2-patterns) restates.
- Longest-match patterns build no plan, because a plan reports leftmost-first
  matches, and Latin-1 patterns build none, as in Trino.
- RE2 plans never generate bytecode.
- A plan must not be slower than the same pattern without it. On R9g, R8g, and
  R8i, RE2 `BenchmarkClickBenchRegexp` `contains` takes 0.09 to 0.84 times the
  time of a build without RE2 plans on every `HTTP`, `WWW`, `LONG_HOST`, and
  `NEWLINE_PATH` row and on Graviton's `OPTIONAL_FALLBACK` rows. On R8i,
  `OPTIONAL_FALLBACK` with dotAll is within noise, and without dotAll it takes
  1.08 times, 44.7 ns against 41.4 ns. That row is accepted as a known
  exception: each losing row retries the optional `www.`, which the DFA never
  does, and removing that retry would change Trino plans too.
- A run scan with less than a vector of input left scans by byte, as Trino's
  run scan finishes its tail, and reads the member table only when the run
  excludes an ASCII byte. On R8i this takes ClickBench `NEWLINE_PATH` without
  dotAll from 1.22 to 0.84 times the build without RE2 plans and
  `OPTIONAL_FALLBACK` from 1.25 to 1.08. On Graviton it makes
  `BenchmarkRe2ScanPlan` `URL_MALFORMED` `contains`, whose runs stop every few
  bytes at a malformed sequence, take 1.06 to 1.14 times its time with the
  vector scan, up to 11 ns per row; that slowdown on malformed rows is
  accepted.

## Generated Trino Scan Kernels

`TrinoRegexp.Options.setGenerateBytecode` is an experimental opt-in, disabled
by default, that compiles the case-sensitive scan plan of an eligible pattern
into its own hidden class. The class holds the plan's attempt loop as
straight-line code, with literals, their lengths, and branch targets as
constants; a run step passes its run to a copied run scanner that reads the
run's bounds and members. The class also holds the candidate search of an
unanchored plan, with its limits as constants, and a static entry for each
Trino operation. A hot pattern therefore shares neither the interpreter's
operation dispatch nor its profiles with other plans. Its results, retries, captures, and search
handoffs are those of the interpreter, which differential tests check
operation by operation.

- An eligible pattern compiles to a private `TrinoRegexp` subclass. Each
  operation keeps the route it has without the option, decided once when the
  pattern compiles: an operation that would run the case-sensitive plan runs
  the generated class, and one that would run other Java, such as a retained
  literal kernel for `contains`, still runs that Java.
- A pattern is eligible when it would compile to the base `TrinoRegexp` class
  with a case-sensitive plan that every operation but `contains` reaches. Any
  other pattern, including one with an ASCII-folded plan, compiles exactly as
  it does without the option.
- Generation happens once, when the pattern is compiled, never on first match.
  A pattern whose class cannot be generated or defined is silently
  interpreted; generation never makes compilation fail and has no mandatory
  mode.
- The interpreter never references generated code. Without the option no
  scan-plan, matcher, or `TrinoRegexp` path checks for it, and neither the
  subclass nor any generated class is loaded.
- An interpreted plan, with or without generated neighbors, must not be slower
  than the same pattern without its scan plan.
- Generated code calls its own copies of the interpreter's scan helpers, such
  as the run, byte, literal, and candidate scans and UTF-8 decoding, rather than
  sharing them. A shared helper is compiled from every caller's profile: with
  generated neighbors running first, the interpreter's run scanners compiled
  larger and stopped inlining into its attempt methods, which slowed
  interpreted patterns. Code outside the scan loops, such as the
  ordinary-engine handoff and result assembly, and trivial accessors stay
  shared.
- A caller holding a constant pattern can bind an operation's generated entry
  directly through the `generated*` accessors, each an `Optional` of a
  `MethodHandle` that is empty when the operation does not reach the plan.
- `TrinoRegexpMatcher` is sealed rather than final, so the matcher of a
  compiled pattern can run its generated search. Callers still cannot extend
  it.
- The class is defined in Regulator's package through a hidden-class lookup,
  so it reaches package-private helpers without widening the public API, and
  becomes unreachable with its pattern.
- The class is not charged to the pattern's memory budget. That budget bounds
  retained heap, and the class lives in metaspace and the code cache, which the
  heap estimate cannot measure. A caller compiling many distinct patterns with
  the option bounds that cost by bounding the patterns it retains.

## Generated Trino LIKE Kernels

`TrinoLikePattern.Options.setGenerateBytecode` is an experimental opt-in,
disabled by default, that compiles a LIKE pattern into its own hidden class
with the same rules as generated Trino scan kernels: generation happens once
at compilation, a pattern whose class cannot be generated or defined is
silently interpreted, the interpreter never references generated code, and
generated code calls its own copies of the interpreter's helpers. Every plan
has a class, because each is one call per row and the interpreter dispatches
every plan through the same `matches`, so a hot pattern of any plan otherwise
shares that dispatch and its profile with every other pattern. The generated
`matches` gives the interpreter's result on every input, including malformed
UTF-8, which differential tests check over the corpus and seeded patterns.

- Each class checks the plan's length bounds and compares its anchored prefix
  and suffix as constants: a literal of up to sixteen bytes in at most two
  overlapping little-endian reads, a longer one with one array comparison.
  The empty pattern and the exact, prefix, and suffix plans need nothing more.
- Contained and ordered literals search with a copy of the literal search for
  each literal: the first-and-last-byte word scan, or the two-word scan of a
  single byte, with the literal's bytes and KMP shifts as constants, and the
  same KMP handoff rule. Ordered
  literals search one after another, as the interpreter does. Generated
  contains specializes its literal with this search, as interpreted contains
  does with the kernel's contains search for a literal of two or more bytes.
- Each search of a literal of two or more bytes splits that loop as
  interpreted contains does: a call-free word scan finds the first candidate
  and calls a separate generated method that verifies candidates from there,
  with the KMP handoff counted from where the search started. Verifying in
  line kept the word loop from being innermost, because C2 never inlined the
  rarely run check, so the loop was never unrolled. Both loops read the SWAR
  constant from a static field that is not final, so on aarch64 C2 keeps it in
  a register. On aarch64 only, they also read the literal's front and back
  broadcasts from such fields; elsewhere the broadcasts are constants. On the
  normative rows gate the split took 0.775x the time of verifying in line on
  r8i and 0.799x on r9g, and turned no win against Trino's LIKE into a loss.
  r8i accepts 1.06x on TPC-H Q13 and 1.19x on Russian `mid-selective`, 22.41
  to 26.76 ns per row; r9g accepts 1.07x on Q13, 1.06x on `WILDCARD_CHAIN`,
  and 1.13x on Apache `high-none`, 40.81 to 46.06 ns per row. The
  `WILDCARD_CHAIN` loss is the residue left after the broadcast fields, and
  its cause is not established; like the interpreted contains edge losses, it
  is an accepted exception on a synthetic input filled with one byte.
- The broadcast fields are a measured platform trade. With constant
  broadcasts, r9g lost on synthetic inputs filled with `x` except for one late
  match: `CONTAINS_LATE` 1.46x (2,570 to 3,762 ns per 32 KiB),
  `WILDCARD_CHAIN` 1.24x, `ORDERED_LATE` 1.20x, and `MIXED_LATE` 1.21x. The
  fields bring these to 0.99x, 1.06x, 0.99x, and 1.01x, and the r9g grid from
  0.882x to 0.799x. On r8i the fields left the grid at 0.775x but lost 10
  cells to constants and won 7, and took GH Archive `"PullRequestEvent"` from
  549 ns per row with constant broadcasts to 726 ns (1.32x), 1.27x the 573 ns
  of verifying in line, so x86-64 keeps constants. The reason is inferred, not
  seen in assembly: aarch64 cannot encode most of these 64-bit constants as
  immediates and has 31 registers to hold them, while x86-64 has 16, so
  holding the broadcasts in registers costs it more than the `movabs`
  constants they replace.
- A literal-gap plan searches for its first literal with the same generated
  search, steps each gap a character at a time by the interpreter's character
  width, inline for ASCII, compares each later literal as a constant, and
  retries from the next candidate exactly where the interpreter does.
- Any other wildcard plan runs a bit-parallel shift-and automaton over the
  interpreter's states. A switch over the pattern's distinct code points
  selects which positions the character advances, with `_` positions in every
  mask and in the default, so a malformed character, which has no code point,
  advances only `_` positions. States that loop on `%` stay set. The match
  fails once no state remains and succeeds as soon as the accepting state is
  reached, or at the end of the middle for an exact pattern, which is the
  interpreter's state-set automaton step for step. Patterns of sixty-four or
  more positions use several state words with a carry between them, so there
  is no position limit.
- The size guard is HotSpot's: a method longer than its 8,000-byte
  huge-method limit is never JIT-compiled, so a pattern any of whose generated
  methods would exceed it, such as a wildcard of hundreds of distinct code
  points, is interpreted instead. Building a class costs time and memory that
  grow with the pattern, so a lower bound on the method size, from the counts
  of literals, gaps, state words, and distinct code points, rejects a pattern
  certain to exceed the limit before its class is built.
- The copied helpers, character width, code-point decoding, candidate prefix
  comparison, and KMP search, live in one LIKE kernel support class, separate
  from the scan-kernel copies.
- A caller holding a constant pattern can bind the generated entry directly
  through `generatedMatches`, an `Optional` of a `(Slice)boolean`
  `MethodHandle` that is empty for an interpreted pattern.
- `TrinoLikePattern` is sealed rather than final, so a pattern with a
  generated class can run it through a private subclass. Callers still cannot
  extend it.
