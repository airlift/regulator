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
  offsets, a `find()` after a failure resumes at the last match end, and
  malformed input has no promised result, all recorded under
  [Java Regular-Subset Frontend](#java-regular-subset-frontend), and
  [Differences from the JDK](docs/integrations/UNICODE.md#differences-from-the-jdk)
  lists the other exceptions. Where `Pattern`'s javadoc and the JDK's code
  disagree, the code wins.
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
- `RegexpMatcher` owns one reusable capture buffer for repeated matching.
  Callers may retain only group zero and a requested prefix of explicit captures
  when complete capture extraction is unnecessary. It advances empty UTF-8
  matches by one code point, permits one terminal empty match, and exposes
  zero-copy Slice group views. A Rust pattern follows the crate instead and
  skips only an empty match that ends where the previous match ended or, outside
  byte mode, inside a character; see [Rust Frontend](#rust-frontend). All public
  offsets are relative to the complete logical input Slice, including matchers
  restricted to a region. Matchers are mutable and not thread-safe; compiled
  `Re2` instances remain thread-safe.
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
  RE2 Unicode Property Groups describes. Java layers the JDK's names on it,
  resolved by `Pattern`'s rules: category short names and `LC`, `LD`, and
  `L1`, scripts with their ISO 15924 codes, blocks with the JDK's identifiers,
  the `In`, `Is`, `gc=`, `general_category=`, `sc=`, `script=`, `blk=`, and
  `block=` forms, binary properties, `java...` properties,
  and its POSIX classes. Each JDK property is the JDK's own computation over
  the database, as Language Authorities describes, and a JDK choice that is
  not Unicode data is a constant citing the JDK. Rust layers the regex crate's
  names on it, as Rust Frontend describes.
- Trino layers pinned jcodings' names on the database, built by the rule of
  jcodings' table generator: each Unicode name or alias of a category,
  script, or binary property other than Bidi_Mirrored, which jcodings never
  reads, or block name (`In` form), lowercased without spaces, hyphens, or
  underscores, plus each cumulative Age (`age=6.0` form), matched in any
  ASCII casing. So `InLatin1Supplement`, `WSpace`, and `age=1.1` resolve and
  `InLatin-1Supplement` and the JDK's `Titlecase` do not. Names Unicode added
  after pinned jcodings' 6.1 tables follow the same rule, and the 40-character
  limit of Trino's fork is not copied. The names Trino's fork adds in
  [airlift/jcodings e81c0a3](https://github.com/airlift/jcodings/commit/e81c0a3cc942bee1c8e4c07127b6b7f50d6c3b05)
  are kept; its `java...` names are Java's computation.
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
`String` APIs, so their results changed with each JDK upgrade. Java's
properties, name normalization, and character names, Trino's `java...` names,
and both languages' word boundaries now agree with the JDK at every code point
its own Unicode version assigns, 16.0 on Java 25 and 17.0 on Java 26, apart
from two changes Unicode 17.0 made to older characters: U+0295 moved from `Ll`
to `Lo`, and 689 code points that are not emoji, such as U+2605 BLACK STAR,
lost Extended_Pictographic.
Name case conversions agree where their input and output contain only
characters assigned in the JDK's Unicode version; Unicode 18.0 also uppercases
some older characters to newer ones, such as U+0277 to U+A7DD, but neither is
ASCII, so no name resolves differently. Characters and names Unicode added
after the JDK's version, such as the Unicode 18.0 scripts Jurchen and Seal and
the names of their characters, resolve only in Regulator. This agreement is of
property membership, which is the JDK's computation over the pinned database
for Java and for Trino's `java...` names, and the database's values for
Trino's other names apart from its POSIX names. Java accepts the property
spellings `Pattern` accepts, and Trino's are pinned jcodings' names.

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
dotless small i out of the class of I, as Joni does. Moving to another Unicode
version follows the Unicode database generator's
[version procedure](tools/unicode-database/README.md#moving-to-a-new-unicode-version).

Java case-insensitive ranges also match their closing characters, as the
[Java reference](docs/reference/languages/JAVA_REGEXP.md) describes. They come
from the database's simple case folding by the rule of the JDK's
`GenerateCaseFolding`, and the generator's two entries that the database cannot
supply, dotted capital I to i and dotless small i to I, are constants citing it.

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

## RE2 Never-Capture Option

`Re2.Options.setNeverCapture(true)` makes every group non-capturing, named
groups included, so `(?P<name>a)(b)` has no capturing groups and no names.
This follows upstream's documented option, "parse all parens as
non-capturing", rather than its parser: upstream's `parse.cc` checks the flag
only for a bare `(`, and its `(?P<name>` and `(?<name>` path calls
`DoLeftParen(name)` without it, so upstream reports one group named `name`.
Upstream's own never-capture test uses only unnamed groups.

## Java Regular-Subset Frontend

- `JavaRegexp` is a separate compiler for the documented regular subset of
  `java.util.regex.Pattern`. It does not reinterpret rejected Java syntax as
  RE2 syntax and does not add a dialect selector to `Re2.compile`.
- A pattern that is not valid UTF-8 throws a `BAD_UTF8` parse error at its
  first invalid byte. Inputs are not validated as UTF-8. The JDK assigns no
  meaning to malformed UTF-8, so Java follows Trino's garbage-in/garbage-out
  contract: Regulator makes no promise about matching malformed input. A
  pattern that runs on a scan plan still reports what the ordinary engine
  reports on malformed input; see [RE2 Scan Plans](#re2-scan-plans).
- Java's `$`, `\Z`, multiline anchors, and combining-aware word boundaries use
  frontend-specific empty-width predicates, and a scan plan checks the same
  final-terminator rule for `$` and `\Z`. In the ordinary engine, programs
  containing `$`, `\Z`, or a multiline anchor stay off the DFA; protected DFA
  transition loops do not contain Java-language branches. OnePass evaluates `$`
  and `\Z`, as
  [Text-Dependent Assertions In OnePass](#text-dependent-assertions-in-onepass)
  describes; a multiline anchor keeps a program on BitState or NFA. The word
  boundaries run on the DFA as Rust's Unicode word assertions do (see
  [Rust Frontend](#rust-frontend)): between ASCII bytes and region edges each
  is its ASCII form, because the JDK's word characters there are
  `[0-9A-Za-z_]` in both character-class modes and a nonspacing mark is never
  ASCII. A search that reaches one next to any other byte quits to the
  fallback engines. OnePass evaluates them exactly, as
  [Text-Dependent Assertions In OnePass](#text-dependent-assertions-in-onepass)
  describes.
- Java patterns use the shared `RegexpMatcher`. A matcher region is the
  assertion context, as a JDK region with default bounds is, except that, as in
  the JDK, `$` and `\Z` do not match at the start of a nonempty region that
  splits a CR LF (without `(?d)`). A range overload behaves like a JDK region
  with transparent, non-anchoring bounds. As in the JDK, `find(int)` resets the
  region to the whole input before it searches. After a failed `find`,
  `matches`, or `lookingAt`, the next `find()` searches again from the end of
  the last match, or from the region start when nothing has matched since the
  last reset. The JDK's resume point after a failure can move instead, because
  its backtracker reuses that position inside `?`, `{n,m}`, lazy quantifiers,
  and repeated groups. Regulator does not follow that, so `\d{3}` and `\d\d\d`
  behave the same: on `123 45 x`, after `find(4)` fails, the next `find()`
  finds `123`, which the JDK finds only for `\d\d\d`. After an empty match
  at the region end, later `find()` calls keep failing without searching, as
  in the JDK. The JDK's `start()` and `end()` then still report that empty
  match while `group()` returns null; Regulator throws, as after any failed
  `find()`.
- A capture inside a repeated group reports the last iteration the match
  used. The JDK differs where it runs a repetition of a group whose body has
  no alternation, optional part, or variable count as a group loop, and
  Regulator does not follow it. That loop never restores the captures inside
  the group, so they can keep values from abandoned iterations, back-offs, or
  failed attempts: on `aab`, `(?:(a))*ab` reports group 1 at 1:2 where
  Regulator reports 0:1. A greedy variable count of a capturing group inside
  another repetition writes the group again as it backs off, over a later
  iteration: on `aaa`, `(?:(a){1,2})*` reports 1:2 where Regulator reports
  2:3. A linear engine keeps no record of abandoned iterations, and the JDK's
  own answer changes under equivalent rewrites such as `(?:(a|a))*ab`, which
  reports 0:1. The differential fuzzer leaves those groups uncompared.
- Multiline `^` excludes end-of-input even with `UNIX_LINES`. In that mode
  the compiler combines LF-only beginning-of-line with Java's non-EOF
  assertion, using the same context-sensitive fallback. When every path from
  the caret to a match consumes a byte, the caret is never at the end of the
  input, so it compiles to RE2's beginning-of-line alone and no longer keeps
  the program off the DFA and OnePass. UNIX multiline `$` retains its LF-only
  rule, including a boundary between CR and LF.
- Unicode character classes and properties use the pinned database, as
  described in Pinned Unicode Properties, and case folding uses its case
  mappings, as described in Pinned Unicode Case Folding. Word boundaries read
  the database's word characters, nonspacing marks, letters, and digits.
  `UNICODE_CHARACTER_CLASS` implies `UNICODE_CASE`, matching the JDK flag
  contract.
- `JavaRegexp.Options` exposes the supported compile flags as mutable chainable
  options. Compilation snapshots their values and copies the pattern bytes.
- A repetition of a body that can match the empty string follows the JDK's
  loop rule, which is JDK parity rather than a new difference: an iteration
  that consumes nothing ends the repetition, even below its minimum count. The
  JDK's Loop and LazyLoop nodes also start each iteration afresh, so a loop
  nested in the body starts over on every iteration instead of continuing
  where the previous one left it. Joni's unbounded loops end and start their
  iterations the same way, so Java and Trino lower every unbounded loop over a
  body that can match empty alike, greedy or lazy, with an input-progress
  lowering: each iteration enters the body through the loop's own entry, an
  iteration that consumes nothing goes to the exit, and only one that consumes
  reaches the choice between another iteration and the exit, where a lazy loop
  tries the exit first. The lowering represents pre-consumption and
  post-consumption execution with ordinary program counters, clones each
  epsilon-reachable instruction at most once per loop, charges every retained
  instruction to the existing program budget, and leaves the shared execution
  engines unchanged. Each entry is a copy of the body's start, which holds the
  entries of the loops nested in it, so in both languages a nest of such loops
  grows with the square of its depth: the default memory budget rejects a nest
  several hundred loops deep, which the JDK and Joni run. In Java, the
  simplifier drops a repeated empty group and cuts any other body that
  matches only the empty string, such as an assertion or `\b+$`, to one copy,
  since the JDK never runs its second iteration. A counted repetition of two
  or more copies of any other nullable body stays counted, and the compiler
  gives each copy an exit from the repetition that it takes when it consumes
  nothing. When that body matches empty only through an assertion and the
  minimum count is two or more, what the pattern matches can depend on the
  reading direction, as in `(?:^a?){2}`, so the pattern compiles no reverse
  program.
- `\G`, `\R`, nullable repeated captures, canonical equivalence, lookaround,
  backreferences, atomic groups, and possessive quantifiers remain deterministic
  compile-time rejections because their observable semantics do not map to the
  current bounded linear-time execution contract.
- The grapheme cluster `\X` and the grapheme cluster boundary `\b{g}` are
  rejected as unsupported, because Unicode's grapheme cluster segmentation is a
  separate algorithm that no Regulator engine implements.
- Escapes that name a lone surrogate, which the JDK accepts, are rejected as
  unsupported, because no UTF-8 text contains a lone surrogate. The rule is
  about how a pattern spells a character, not what a set contains:
  `[\x{D800}-\x{DFFF}]` is rejected because its endpoints are such escapes,
  while `\p{Cs}`, the same set, compiles as in the JDK, as does a range such as
  `[\x{D7FF}-\x{E000}]` whose endpoints are not surrogates. A set's surrogate
  members match no valid UTF-8 text, and on malformed input they may match an
  encoded surrogate, which the garbage-in/garbage-out contract allows.

Java match positions remain UTF-8 byte offsets over `Slice`, not the JDK's
UTF-16 code-unit offsets. Java parse-error offsets count UTF-8 bytes in the
original pattern, including when quoting rewrites it before parsing.

## Rust Frontend

- `RustRegexp` is a separate compiler for the pattern language of the pinned
  Rust `regex` crate. Its results must match the crate's on valid UTF-8 input,
  and in byte mode on any input, except for Unicode property names and
  characters that differ between the crate's Unicode 16.0 tables and the
  pinned Unicode 18.0 database (see the
  [Rust language reference](docs/reference/languages/RUST_REGEXP.md#unicode-properties)),
  and for the alternation rule recorded below.
  The pinned crate is the authority, recorded in committed fixtures; the Maven
  build never builds the crate's golden tool, and runs it only when
  `-Dregulator.fuzz.rustOracle` names it.
- Alternation is leftmost-first as the crate documents it: in `x|y`, `x` is
  preferred, so `a*a|a*b` on `ab` matches `a`. The pinned crate's HIR
  constructors lift a prefix common to every alternative out of the
  alternation, so the crate matches `a*a|a*b` as `a*(?:a|b)` and reports
  `ab`. That differs from its own rule whenever the lifted prefix can match
  more than one length. Upstream tracks it as
  [rust-lang/regex#1400](https://github.com/rust-lang/regex/issues/1400), and
  its fix, [#1401](https://github.com/rust-lang/regex/pull/1401), is
  unreleased. Regulator departs from the crate here and follows the
  documented rule, as it does for RE2's `[Kk]|x`, so an alternation means
  the same thing whatever its alternatives share.
- `RustRegexpParser` builds a private syntax tree shaped like the crate's and
  checks the nest limit before translating, so the crate's error precedence is
  preserved. Each crate error kind maps to a `RegexpParseErrorCode`, and the
  message names the kind.
- Rust patterns use the shared `RegexpMatcher`. A pattern-level search flag
  makes it skip empty matches that end where the previous match ended and,
  outside byte mode, empty matches that split a UTF-8 character. It also makes
  ranges and regions keep the whole Slice as assertion context, as the crate's
  `Input::range` does. There is no Rust-specific matcher type.
- Byte mode, the crate's `regex::bytes::Regex`, is an option on
  `RustRegexp.Options` rather than a separate compiled type, following the
  encoding option on `Re2.Options`. It is named after the crate's bytes API
  rather than Latin-1, because only constructs without Unicode mode match
  single bytes; Unicode-mode constructs still match UTF-8. The parser marks the
  byte classes and literals that can match a byte above 0x7F as Latin-1 nodes,
  and the compiler and expression analyses read the encoding from each node; an
  ASCII byte construct reads alike in both encodings, and the any-byte dot
  (`ANY_BYTE`) carries no such flag and is recognized by its kind. Separately,
  byte mode sets the pattern-level Latin-1 flag, so empty matches advance one
  byte, as in Latin-1 mode, and the matcher lets an empty match split a
  character.
- Outside byte mode, inputs are not validated. The crate's `Regex` only
  accepts valid UTF-8, so results on malformed input are unspecified rather
  than an error, and valid input pays no validation scan. A pattern that runs
  on a scan plan still reports what the ordinary engine reports on malformed
  input; see [RE2 Scan Plans](#re2-scan-plans). Byte mode needs no validation
  either: its results match the crate on any input.
- CRLF multi-line anchors, Unicode `\b` and `\B`, and the start, end, and
  half word boundaries are Rust-only empty-width predicates, evaluated only
  when a program contains one. ASCII `\b` and `\B` reuse RE2's boundaries.
  Unicode `\b` and `\B` are Rust predicates because the crate decodes the
  character on each side and treats a side that does not decode as a non-word
  character, and `\B` also requires each side that is present to decode; byte
  mode exposes both rules.
- The forward and reverse DFAs evaluate these predicates. A state records
  whether the byte before its position is an ASCII word byte, a CR (an LF in
  reverse), or a byte outside ASCII. The DFA evaluates a Unicode word
  assertion only between ASCII bytes and text edges, where it is its ASCII
  form. In a program with a Unicode word assertion, a transition from a
  state that needs any assertion quits when the byte before or after it is
  outside ASCII, and the search reruns on OnePass, BitState, or the NFA. A
  state that needs only a line anchor quits too, because a line anchor that
  holds can lead to a word assertion the state does not need yet. The crate's
  lazy DFA makes the same ASCII assumption but quits on every byte outside
  ASCII; this DFA quits only at those transitions, so text with non-ASCII bytes
  away from the assertions keeps it. A quit depends on the text, so it is never
  cached and, unlike cache exhaustion, marks nothing on the program. Only the
  matcher that saw it reacts: after four consecutive unanchored searches whose
  DFA fails, its later unanchored searches of that input skip the DFA until it
  is reset, because text that quits one search usually quits the next, and
  each quit costs a DFA start before the fallback. A search whose DFA
  completes ends the streak. The transition loops contain no Rust-language
  branches; the predicates live where states are built.
- OnePass evaluates these predicates too, as
  [Text-Dependent Assertions In OnePass](#text-dependent-assertions-in-onepass)
  describes.
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
- Repetition counts above 1,000 are accepted, as in the crate, which takes
  any `u32` count its size limit allows, and so are nested counts whose
  product exceeds 1,000. A count past the `int` range reads as the `int`
  maximum. The expansion is bounded as in every language: compilation fails
  with `RegexpCompileMemoryLimitException` when the expansion does not fit
  `maxMemory`, and the shared expansion budget rejects most oversized
  expansions before building them, as
  [Memory Budgets And DFA Caching](#memory-budgets-and-dfa-caching)
  describes.
- Any non-negative nest limit is accepted, because parsing, the nest-limit
  check, and translation all walk the syntax tree with explicit stacks.
  Above the default limit, classes keep two parse costs that `RuneRangeSet`
  accepts: n log² n for nested unions whose operand weights stay just past its
  balanced-merge ratio, and the class size per `~~` toggle of wide ranges.
- A repetition of a body that can match the empty string keeps the crate's
  priority, which is crate parity rather than a new difference. The crate
  compiles `x*` as `(?:x+)?`, as RE2 does, but searches that program
  unflattened. Upstream RE2's flattening enters the loop's list as if fresh
  from inside an iteration, so a lower-priority branch of the iteration runs
  ahead of the loop's exit: C++ RE2 matches `ab` with `a(?:|b+)*` where the
  crate matches `a`. A forward Rust program with such a loop, greedy or lazy,
  gives each alternation on a cycle of instructions that consume no input a
  list of its own, which a search visits once, and so flattens to about its
  own size. RE2 programs keep upstream's flattening. Trino ends a loop, greedy
  or lazy, on an empty iteration, as Joni does (see the
  [Trino language reference](docs/reference/languages/TRINO_REGEXP.md#other-matching-differences-from-joni)),
  so its program has no such cycle, and Java's loops follow the JDK's
  own rule instead (see
  [Java Regular-Subset Frontend](#java-regular-subset-frontend)). For the
  same reason the parser keeps a lazy repetition of a different lazy
  repetition, such as `(?:a+?)??`, rather than squashing it to `a*?` as RE2
  does; the squashes that keep priority stay.
- The crate's 10 MiB compiled-size limit is not reproduced, because an
  approximation of the crate's size estimate would disagree near the limit.
  Compilation is bounded by `maxMemory` instead; the
  [Rust language reference](docs/reference/languages/RUST_REGEXP.md#compilation-options)
  describes the patterns this accepts. The two limits disagree in both
  directions: Regulator accepts some patterns the crate rejects as too large,
  and, under a memory budget smaller than the default, rejects some the crate
  accepts.

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
- Unbounded repetition of a nullable body, greedy or lazy, uses the
  input-progress lowering that
  [Java Regular-Subset Frontend](#java-regular-subset-frontend) describes.
  Directly nested `?`, `*`, and `+` quantifiers, greedy or lazy, first use
  modern Joni's parser reduction table in the Trino parser before counted
  repetitions are expanded. Trino's pinned Joni also reduced `(?:x+?)+` to `x+?` and
  `(?:x+?)*` to `(?:x+?)?`, which change what the pattern matches; Joni
  [f757501](https://github.com/jruby/joni/commit/f7575010f3ca4deff55642f34062a9ea09783091)
  and Oniguruma
  [7dde69a](https://github.com/kkos/oniguruma/commit/7dde69aef839ffa7ab12f5c14133f8c7dd28d237)
  removed them as bugs ([Ruby #17341](https://bugs.ruby-lang.org/issues/17341)),
  so `(?:a+?)+` matches all of `aa`, where Trino matches each `a`.
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
  dot-star gaps. The compiled `TrinoCompiledPattern` remains authoritative for
  every other operation, including captures and boundaries. The matcher retained
  size is deducted from the forward-DFA budget; compilation keeps the ordinary
  RE2 route when that optional allocation does not fit.
- A pattern that is not valid UTF-8 throws a `BAD_UTF8` parse error at its
  first invalid byte, as `JavaRegexp`, `RustRegexp`, and UTF-8 `Re2` patterns
  do. This deliberately differs from Trino, whose non-strict Joni path accepts
  such a pattern and compares its malformed bytes byte-for-byte; that reading
  is unspecified, so Regulator treats the pattern as a syntax error. Inputs are
  not validated as UTF-8, matching Trino's garbage-in/garbage-out contract.
  So a range, property, or complement that includes surrogates, such as
  `[\x{D7FF}-\x{E000}]` or `\p{Cs}`, compiles as in Joni, and its surrogate
  members match no valid UTF-8 text. On malformed input such a class may match
  an encoded surrogate as one character, where Joni reads its three bytes
  separately and does not match them.
- Behavior of pinned Joni that depends on how it compiles or searches a
  pattern, rather than on what the pattern says, is an engine defect and is not
  copied, whether or not modern Joni or Oniguruma fixed it. The seeded
  differential test against pinned Joni found most of these, and its grammar
  leaves out each one:
  - a braced property in a class read as brace-less after a greedy quantifier
    or a brace-less `\pL`, through a lexer field that Trino's fork added for
    brace-less `\pL`
    ([airlift/joni 807c625](https://github.com/airlift/joni/commit/807c62538b6b3d4b18d3f7b7edcae509cd109fc6)),
    which Trino did not intend
  - an internal error for an empty group repeated exactly 2 through 100 times,
    such as `(?:){2}`
  - a reluctant `{0}?` that runs its body, and a reluctant counted repeat of an
    empty capture that ends the loop around it (Oniguruma
    [2b725c1](https://github.com/kkos/oniguruma/commit/2b725c132854cd2e253321bd1bfc3dbac5647589),
    [ca64663](https://github.com/kkos/oniguruma/commit/ca64663ca8bb34ca7dc219d18ec6e475cca9dec8))
  - counted repeats of two or more over a body that can match empty, whose
    captures and empty-iteration stops depend on the compiled repeat
  - a stale previous character for `\b` and `\B` after a greedy `.*` or `.+`
    (Joni
    [e97e5bf](https://github.com/jruby/joni/commit/e97e5bf0bac9664d93fc13ac6c0d382e84cfab9c))
  - a class holding a negated class that tests a non-ASCII character by its
    first byte, after set operations leave the class with no multibyte ranges
    (Oniguruma
    [ce189d1](https://github.com/kkos/oniguruma/commit/ce189d190417b783ba0e249eca7cc1875c012861)
    fixed the matching case)
  - the missed empty match at the end of a multibyte input, recorded under
    [Trino Text-Dependent Assertions And Full Case Folding](#trino-text-dependent-assertions-and-full-case-folding)
  - a search tried only at the start when a dotall `.*` follows only anchors and
    boundaries, so `(?s)$.*` does not match `ab`
  - the scripts `Olck`, `Sundanese`, and `Saurashtra`, which share the internal
    numbers pinned Joni uses for ASCII `\s`, `\d`, and `\w` and so match those
    instead of their scripts' characters (Joni
    [416f5f9](https://github.com/jruby/joni/commit/416f5f9bcd8ce9482648702fbe428aa5ae0b51e6))

  The [Trino language reference](docs/reference/languages/TRINO_REGEXP.md)
  gives an example of each.
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

### Trino Regexp Literal Searches

Boolean `contains` of a pattern planned as a contained literal, such as
`.*lit.*` or `lit.*`, dot-star literal spans, and every literal of the
ordered-literal matcher, such as `a(?s:.*)b`, search a literal of two or more
bytes with the LIKE frontend's call-free first-and-last-byte scan and
out-of-line verification; see [Trino LIKE Frontend](#trino-like-frontend).
`Slice.indexOf` stopped at every occurrence of the literal's first byte, which
lost time where that byte is common and lost cells to Joni, and the ordered
matcher verified candidates inside its scan loop, which C2 did not unroll. The
contains search is built once at compilation, charged to the forward-DFA
budget, and shared with dot-star spans; a one-byte contains or dot-star literal,
or a contains search that does not fit, keeps `Slice.indexOf`. A plain literal
pattern such as `lit` keeps its exact-literal route and probe-byte scan for
boolean find. On the normative rows, against the previous routes, dot-star
contains took 0.459x the time on r9g and 0.413x on r8i, dot-star count 0.527x
and 0.472x, and ordered contains 0.710x and 0.791x. No win against Joni became a
loss, and 2 r9g and 11 r8i dot-star contains cells and 12 r8i dot-star count
cells went from a loss to a win. r8i accepts Russian ordered rows at 1.12x, 4 to
5 ns per row more, and `russian/dotstar-low-selective` (`-то`) contains at
1.34x, 22.4 to 30.1 ns per row, still over twice as fast as Joni.
`english/dotstar-low-none` (`waffles`) is slower on both platforms: contains
1.35x on r9g (8.8 to 11.9 ns per row), count 1.45x on r9g (8.0 to 11.6 ns) and
1.48x on r8i (10.1 to 14.9 ns), still 1.9x to 3.4x as fast as Joni; why is not
established. Pooled over dot-star contains, dot-star count, and ordered
contains, the previous routes' forks had a 90th-percentile spread of 42% to 51%
against 8% to 10% for the pair scan, landing in a fast mode or one about 1.5x
slower, so their per-cell noise is wide; the cause is not established. The r8i
`english/ordered-none` (`Yeah`, `there`) loss in a confirmation measurement,
1.17x, came from that fast mode: in the initial comparison the pair scan matched
the previous route's slower mode, 22.7 against 22.9 ns per row.

Candidates are verified in 8-byte words for literals of 8 or more bytes, in two
4-byte reads for literals of 4 to 7 bytes, and a byte at a time below 4 bytes.
Against verifying a byte at a time, everyday Trino dot-star rows took 0.83x the
time on r9g and 0.82x on r8i, recovering about 81% and 64% of the short-row
slowdown against release 1.0 that the pair scan opened; `.*coolfunctionname.*`
contains went from 18.2 to 13.8 ns per row on r9g. The nginx IP-literal row
(`173.203.157.20`) is accepted at 1.08x to 1.13x on both platforms, 3 to 4 ns
per row: 94% of its candidates differ at the second byte, where a word compare
costs more than a byte compare. r8i also accepts gharchive `"PullRequestEvent"`
at up to 1.07x and LIKE `WILDCARD_CHAIN` at 1.07x. An early exit after the
second byte removed the r9g loss but cost 1.33x on r8i gharchive, so it was not
taken.

Exact-literal spans on short rows, which count, position, and extract a plain
literal, keep their first-byte search. The measurements above cover only Trino
rows. Under the one engine path of release 2.1, RE2, Java, and Rust boolean
`contains` of a contained literal of two or more bytes uses the same search, as
Trino does. The 2.1 full run's real short rows decide whether it stays. A
preliminary dispatch A/B on synthetic rows measured their short `find` at 0.69x
to 0.71x the time on r9g and 0.62x to 0.71x on r8i, with `matches` and
`lookingAt` flat. If the full run shows a loss on real short rows, a follow-up
removes the contains search for that language and restores `Slice.indexOf`.
Their dot-star spans use the same search, and the same run decides them.
Alternatives rejected:

- The pair scan for short-row exact-literal spans won only 3% to 4% on r9g and
  8% to 12% on r8i, and lost 1.53x to 1.78x on short r8i Russian rows (14.0 to
  24.9 ns for position), 1.31x on an r9g log-row count (39.7 to 52.0 ns), and
  up to 1.54x on r9g tiny rows (20.9 to 32.2 ns for `extractAll`).
- The same change on r8i alone adds a platform branch for that marginal gain
  and keeps its unexplained Russian losses.

Why the pair scan loses on short Russian rows on r8i is not established; the
inference is that Cyrillic literals begin and end with bytes that fill the
text, so the pair scan rejects little that a first-byte scan would not.

## Memory Budgets And DFA Caching

- Public compilation defaults to a 96 MiB planning budget. Unlike upstream's
  8 MiB default, this retains representative large Java DFA graphs without
  reset thrashing under both compressed and uncompressed ordinary references.
- Two thirds of the configured budget is assigned to the forward program and one
  third to reverse search: a lazily compiled reverse program, less what an
  eagerly compiled inner literal search retains from that third.
  The reverse program's input, the simplified expression kept until that
  program is compiled, is kept only when it fits the reverse third by itself,
  and is released once the program is built; when it does not fit, the pattern
  has no reverse program. A compiled pattern does not keep its parse tree.
- A program is charged in two steps after a fixed 512 bytes, with references
  counted at eight bytes. Before flattening, each instruction is charged 120
  bytes: a 56-byte instruction object, 16 bytes of instruction array that
  grows by doubling, and 48 bytes of the flattening pass's scratch. The
  instruction limit is the budget past the fixed bytes divided by 120, up to
  2^24 instructions. Flattening then charges each instruction it emits 113
  bytes, up to 2^28 - 1 instructions, since a packed capture instruction word
  keeps a 28-bit output. The 113 bytes are the object, its array slot and the
  block slot flattening emits it into, a 2-byte output list id, the 31 bytes of
  no-submatch NFA tables that the first no-submatch NFA search builds, and the
  8-byte capture word that compilation builds. The program keeps all of them
  but the block slot, freed after flattening. The 56-byte instruction assumes
  compressed class pointers, which a heap of 32 GB or more keeps; only with
  them explicitly turned off, deprecated in JDK 25 and obsolete in JDK 27, is
  an instruction 64 bytes and the charges 1 to 3 percent short.
- Simplification expands counted repetition into copies before the compiler
  counts anything. It counts the expansion as it builds it and fails with the
  memory-limit exception once the count passes the compiler's limits for the
  same budget: twice the instruction limit in node visits, and the instruction
  limit in instructions. The count never exceeds what the compiler counts for
  the result, so the check rejects no pattern the compiler would accept, and a
  repetition whose count passes those limits fails before its copies take the
  heap. Copies in a branch that the simplifier removes because it never
  matches are never built or counted, although upstream's compiler emits and
  counts that branch. The count charges each copy of a class one instruction,
  so an expansion under the count can still be built and then fail in the
  compiler, as `\p{L}{1000}x` does at the default budget. The expression
  analysis reads every range of the class at each copy, so the program is
  compiled before the expression is analyzed, and the compiler's limits bound
  that reading.
- A long leading literal keeps a prefix shift table, configured after
  flattening from what the flatten charge left. A forward program whose table
  does not fit its share fails to compile.
- Upstream charges 8 bytes per instruction, plus 2 for list heads in a program
  of at most 512 instructions, and checks nothing after flattening. Flattening
  copies each list's epsilon closure, so a program can grow with the square of
  the instructions its limit admitted: nested lazy loops over nullable bodies
  flattened to tens of millions of instructions and gigabytes of heap.
  Regulator stops flattening when the charge of the emitted instructions
  exceeds the program's share. A forward program then fails to compile, and a
  reverse program is dropped, as any reverse program that does not fit is.
- Java and Trino loops over a body that can match empty end on an iteration
  that consumes nothing, so flattening keeps their closures apart and does not
  multiply them. Their nests grow before flattening instead, as
  [Java Regular-Subset Frontend](#java-regular-subset-frontend) describes.
- The DFA caches receive what the flatten charge leaves: the program's share
  less the fixed bytes, 113 bytes per flattened instruction, and what one-pass
  and prefix tables retain. Internal compilation with a non-positive budget
  uses upstream's 1 MiB default DFA allowance.
- A forward program divides its DFA allowance between first-match and
  longest-match caches. A reverse longest-match DFA and a set's many-match DFA use
  their program's complete allowance. As upstream, a DFA whose allowance is
  below its minimum is never built, and its searches use the NFA. The minimum
  leaves out a forward DFA's start-byte tables, so a DFA can still fail to
  initialize; its program remembers that allowance and does not build it again.
- A plan that yields to the forward DFAs is left out when its reservation would
  leave the forward program's first-match or longest-match DFA too little to
  initialize where that DFA initializes without it, start-byte tables included.
  Plans that release 2.0 did not build yield where their record says so, so
  they never change which forward DFAs a `maxMemory` initializes.
- DFA construction derives retained array and object sizes from the running VM.
  A reset publishes Java state and transition arrays at their initial capacity,
  so expanded tables and per-state objects return to the budget; the state hash table keeps its
  capacity and its charge. A full cache resets, and repeated reset thrashing
  falls back to the NFA.
- A capture NFA search has its own allowance, the forward share, beside the DFA
  caches'. A search tracks K slots, the caller's, up to the slots through the
  highest surviving CAPTURE, and reports the slots past that unset: a trailing
  group the simplifier removes, as in `(a){0}`, has no CAPTURE and costs
  nothing, while a removed group below a surviving one keeps its slots. A search
  holds at most T = byte ranges + alternate matches + matches + CAPTUREs + 1
  threads. A bounded pass takes `(T + 1) × int[K]`, a slot array for each thread
  and the best match, plus `6 × int[P] + 2 × int[S] + 2 × int[T + 1] +
  Object[T + 1]` and 448 bytes of objects, including a region's context view,
  for a program of P instructions and a capture stack of S. The single pass
  sizes its thread tables by doubling and may keep group-zero queues; a pass
  over a window also keeps a `long[P]` of instruction words. The allowance
  bounds what a search holds at once: each bounded pass also allocates a small
  search object, which is transient. A caller buffer longer than the pattern's
  slots is searched through a copy of the program's slots when there are at most
  64, OnePass's limit, so OnePass still runs; that copy of at most 64 slots,
  272 bytes with compressed class pointers, is held outside the allowance, and a
  wider program searches the caller's buffer in place.
- Compilation plans each pattern's capture searches. A search that fits keeps
  the ordinary single pass. A wider one runs passes that each track group zero
  and the widest window of other slots that fits, over a copy of the instruction
  words that renumbers the window's slots and moves every other group's slots
  past the pass's count. Only slots 0 and 1 and the queue order decide the
  winning thread, so every pass finds the same match and the windows together
  report exactly what one pass would. At a maxMemory of 2,651 bytes or more, a
  window of one group fits every program the flatten charge admits, and a pass
  of group zero alone is smaller, so planning rejects nothing compilation
  admits. Below that, a program of at most 11 instructions can in principle fail
  to compile with a memory-limit exception; none has in over 100,000 swept
  patterns, and the tightest, which tracks only group zero, fit with 8 bytes to
  spare.
  Upstream RE2 and the Rust crate bound neither the capture NFA's threads nor
  their slots.
- A BitState capture search also stays within the forward share: compilation
  lowers the program's BitState text limit until the search fits.
  See [Bounded Direct BitState Capture](#bounded-direct-bitstate-capture).
- An optional plan or fact, which only saves work, reserves memory only when the
  allowance covers it; otherwise the pattern compiles without it, with the same
  results, and never throws.

`maxMemory` is a planning budget, not an eager allocation or a byte-exact JVM
heap limit. The default is demand-driven, so simple patterns retain only the
storage they use. Object headers, collector metadata, and allocator behavior are
VM-specific; the cache model accounts for retained Java objects but does not
promise exact process-memory equality.

## Shared DFA Concurrency

Compiled `Re2` instances are safe to share across threads. Each DFA publishes
its cache as one generation of tables, one root object whose fields are final,
through one volatile field:

- a reader search loads the root once when it starts and reads only through it;
  it takes no lock and never waits for a writer; its only stores are racy
  performance hints that need no ordering, such as the counters and flags that
  select paired rows or the pointer table
- writers hold the DFA's cache mutation lock: an exclusive search fills empty
  slots of the current generation in place, and growth, paired rows, and the
  pointer table publish a new root that shares the arrays they leave unchanged
- a reset publishes a fresh generation whose Java tables restart at the cache's
  initial capacity and never writes the superseded one, so a search that began
  before the reset can continue reading unchanged tables, whose states are
  still correct states of the same program; a missing entry follows the normal
  exclusive retry
- writers never wait for readers, and a reader never retries because a writer
  ran: it retries as an exclusive search only when a cache entry it needs is
  missing, to request paired rows or the pointer table, which only an
  exclusive search selects, or, as a count, to judge other searches' resets
  where the pointer table can be given back
- the inner literal search runs its reverse prefix search and forward
  confirmation in one pass over one captured root per DFA; when the pass cannot
  finish, for example at a missing transition, the two searches run separately
- cache versions prevent queued builders from repeating work already published
  by another thread

Upstream holds a shared cache lock for each search and takes it exclusively to
reset. Regulator keeps transition reads lock-free in the per-byte loop and
replaces the shared lock with publication, because any per-search reader
registration, upstream's shared lock included, costs a store-load fence, about
10 ns per search on Graviton.

Readers need no cache-generation validation, for these reasons:

- The root's final fields make every array, and every slot written before the
  root was published, visible with the root.
- Each slot of a generation is written at most once: for example, a transition
  goes from uncomputed to its value, a state, a state's object row (its
  reference array), or a start state from null to its value, and a pointer
  table entry from zero to a row's address or offset. A racy read sees the slot
  empty or final, and references and ints do not tear. Pointer table entries
  are 64-bit and use plain access; see
  [Bounded DFA Pointer Transition Table](#bounded-dfa-pointer-transition-table)
  for why they do not tear either.
- Every racy reference read on a reader path is null- and bounds-checked, and an
  empty or out-of-range slot means "not computed": the reader retries as an
  exclusive search. Object rows are shared between a root and its grown
  successor, so a row reached through an older root can name a state beyond
  that root's arrays; the reader checks the row's state offset against its own
  root.
- An exclusive search reloads the root after each step that can publish one, so
  its writes land in the current generation.
- Every new pointer table is zeroed and backfilled before it is published, so
  an entry is zero, a dead or full-match tag, or a possibly tagged address or
  offset of a row in the same table. The pointer loop therefore follows entries
  without per-edge capacity checks; a heap table's segment access still checks
  its bounds. Its caller checks the start row against the table of the same
  root, and the row where the loop exits is checked against the reader's root
  like any other state offset. A native table comes from an automatic arena
  (`Arena.ofAuto`) that the reader's root keeps reachable, and a reachability
  fence holds it for the pointer loop; a heap table's array is reachable from
  the same root.

A reset or a growth leaves the superseded generation reachable only from the
roots of searches still in flight, and that generation is not charged against
the DFA's allowance. A reader holds the one root it began with. An exclusive
search reloads the root after each step that can publish one, but each frame of
its call chain keeps the root it passed down until that step returns, and a
fused count keeps the root it began with for the whole current attempt.

- The call depth is fixed: the roots one search holds are those of its active
  frames, and the code fixes the depth of the search call chain.
- The root count is fixed: each frame holds at most one root of each DFA it
  reads, so a search holds roots of one DFA, or of two when the inner literal
  pass reads the reverse prefix DFA and the forward confirmation DFA, never a
  number proportional to its matches or resets.

Each root reaches at most one allowance of Java memory plus the paired rows it
carried. Releasing paired rows refunds their charge, and states built afterward
fill arrays an older root still shares, so a root that carried paired rows can
reach one allowance plus those rows, at most 64 KiB. A superseded root becomes
eligible for collection once no search retains it. A charge would require
knowing when the last reader of a generation finishes, which is the reader
registration this design removes; as with the member table under
[Trino Specialized Byte Scans](#trino-specialized-byte-scans), bounded memory
the DFA does not retain needs no charge of its own.

A heap pointer table that a reset or growth supersedes is ordinary garbage, like
the superseded Java tables, and is neither counted nor capped. Native pointer
segments that a reset or growth supersedes are likewise no longer published,
though in-flight searches may still hold them. The DFA counts each
one until the collector finds it unreachable, and the JDK frees it later on its
common cleaner. The DFA allocates a new segment only while the counted
superseded segments, with the one it replaces, total at most twice the new one.
Its counted native memory, live and superseded, therefore stays within three
times its newest segment, as
[Bounded DFA Pointer Transition Table](#bounded-dfa-pointer-transition-table)
describes; the memory actually held exceeds that only by segments the common
cleaner has not yet freed. The superseded part is native memory no longer
published and awaiting collection, not memory the DFA retains beyond its share.
Allocating a segment can stall only when direct memory as a whole, other users
included, fills `MaxDirectMemorySize`; the JDK then forces a collection and
retries before failing. A reset restarts the Java tables at their initial
capacity, while the pointer table keeps its size, so a busy DFA grows its Java
tables again after each reset.

Decided (Dain, 2026-11-06): copy-on-reset applies in every memory mode, and
the counted bound above is the native-memory bound. Live native memory can run
ahead of the count while the JDK's common cleaner catches up, measured at 5.1
times the newest segment under a reset storm and 5.9 times in a probe that
starves the collector; that lag is accepted. A shared arena that bounds live
native memory strictly remains the fallback design.

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

After a match transition enters a matching self-loop, the continuation reads
the authoritative primitive transition row directly until a byte changes the
state or stops matching: it steps the first 32 bytes, then may finish the run
with a [vector scan for the state's exit bytes](#self-loop-exit-byte-skips-outside-counting).
It records the match boundary the consumed bytes imply and leaves the first
exceptional byte to the existing paired decoder. This avoids repeatedly
decoding a deliberately null paired reference without adding another
transition representation.

The reverse search and the inner literal search's anchored confirmation scan a
matching self-loop the same way, since their object rows hold no match
transitions either. A reverse search probes for one after a match transition
only when its program has a repetition loop or can match empty. A program
without a loop that cannot match empty, such as one built only from counted
repetitions like the reversed `\s[a-zA-Z]{0,12}` of `\s[a-zA-Z]{0,12}ing\s`,
has none to find. Probing it after each match transition cost
`ing-suffix-limited-space` about 2.7%, measured on the exploration build.

Some eligible expressions produce repeated matches before pairing can amortize
its entry and terminal handling. After 16 paired matches that end fewer than
16 bytes from where their search started, with no paired match ending 16 or
more bytes from its start between them, the DFA rejects the paired table. The
search that observes the threshold keeps its result; the next search that
requests paired transitions or a pointer table releases the paired table's
complete memory charge under the existing exclusive cache protocol, and paired
transitions stay disabled until the next cache reset. The observation counter
is a best-effort performance hint shared by concurrent readers; races may
change when rejection occurs but cannot change matching behavior. A cache
reset clears the rejection and the counter, and enables paired transitions for
the new cache generation only when the DFA holds no pointer table, has none
withheld, and has not given one back.

## DFA Row Index Without A Divide

A DFA finds a state's row from its offset with a shift and a multiply. Each DFA
precomputes the shift of the row width's power-of-two factor and the inverse of
its odd part, which is exact because every state offset is a multiple of the row
width. The signed divide this replaces ran on every byte of a dense match on
object rows, where each match transition takes the sidecar step: `.*`
count-spans took about 634k such steps per operation.

The change trades an object-row gain for a small pointer-table cost. The
original comparison predates the heap pointer table, so safe memory mode,
without `--enable-native-access`, measured object rows, and native mode measured
the native pointer table. On AWS r9g and r8i with JDK 25, against main:

- Safe mode is faster: everything-greedy count-spans by 10–11% on r9g and 20% on
  r8i, and any-one count by 2–3% on r9g and 7–8% on r8i.
- Native mode is 4–6% slower on six rows: Rust twain-insensitive (1.049) and
  Rust the-lower (1.038) on r9g, and Java quotes (1.038), RE2 quotes (1.041),
  Rust quotes (1.062) and Rust capitals (1.037) on r8i. Measured on the same
  rows, the divide removal alone accounts for 1.023–1.053, and the inner literal
  pair registration alone stayed within 0.977–1.016.
- No added work explains those costs. With a pointer table the row index runs
  only at DFA entry and match restarts, under 16k times per operation, and no
  changed method crosses an inline limit. The only differences between the
  builds are layout: the DFA instance grows 8 bytes, which shifts field offsets,
  and one cold-call stub grows from 192 to 256 bytes. Rust the-lower moves the
  other way on r8i (0.932).

The trade-off is decided: the divide removal is kept (Dain, 2026-11-06).

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

This count route is limited to the count variant of the forward search.
Counting needs only each match end, and that variant is already kept separate
from the group-zero cursor because extra result handling in the cursor's
generated code measurably regresses short repeated boundary operations. The
route runs only for searches of at least 256 bytes, the same gate that requests
paired transitions, and it runs before that request so a qualifying search does
not build a paired table it will not use. Shorter searches cannot amortize this
route's exit-set construction and Vector setup; the skips in
[Self-Loop Exit-Byte Skips Outside Counting](#self-loop-exit-byte-skips-outside-counting)
use shorter thresholds for the reasons given there. Unanchored-end,
non-matching start states and an available Vector API are also required; every
other search keeps its existing route.

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
pointer loop when a pointer table exists or can be allocated, and through the
paired loop when the DFA already holds or has requested paired rows or no
pointer table can be allocated, such as for an unlimited DFA. With a pending
first match it chooses the same way between the pointer loop and the paired
after-match loop. Paired rows prevent the pointer table from being allocated.
The scan computes every transition of the states it visits, which adds
self-loops the text never exercised. Previously, a hand-off without a pending
match requested paired rows, which then blocked the pointer table, and Russian
literal alternations, whose letters all start with one of two lead bytes,
stopped the scan after a few short skips and ran the paired or compact loop,
about 1.35 times slower on r9g than the pointer loop the ordinary route used
before the scan existed. A requested pointer table is allocated when the
exclusive search that requests it ends, not during that search, which continues
through the compact loop. The compact loop leaves its fast path on every match
transition, and every transition out of a matching loop is one, so a matching
dot loop over text with a multibyte character every few bytes ran the rest of
the text byte by byte; on r9g, 32 KiB of `xaaaaaaaa` followed by repeated `éa`
took 215 us against 58.6 us before the route. A pending longest match keeps the
compact loop, as it does after a paired search's first match. A paired search
reached without a pending match reports the whole search length to the
short-paired-search hint, as every other paired search does, so a short tail
after a long scan does not disable paired transitions. The forward search's
object-row self-loop scan still rejects exit sets with the matching or high-bit
flags, through its `selfLoopExitByteSet` wrapper, so its behavior is unchanged;
the skips outside counting use both flags.

Searches whose start state never reaches a qualifying loop pay the initial walk
on every match. After eight consecutive searches in which the route does not
apply, the route stops attempting for the rest of the count call. A search that
retries is counted once. The counter is reset at the start of each count, so a
later text that does reach a qualifying loop still uses the route. As with the
paired-transition hint, the counter is a best-effort performance hint shared by
concurrent readers; races may change when the route engages but not the match
result, because the ordinary route computes the same match end.

## Self-Loop Exit-Byte Skips Outside Counting

Matching and reverse searches also skip self-loop runs with the exit sets the
[count route](#count-self-loop-exit-byte-scan) computes. Forward, the paired
search's matching continuation and the
[candidate-start cursor](#bounded-candidate-start-cursor)'s check step a
matching self-loop run for 32 bytes. When the run continues past them, the
Vector API is available, and the state's exit set is accepted, one to three
bytes or every byte from 0x80 through 0xFF plus up to three ASCII bytes, a
vector scan finishes the run at its first exit byte. Otherwise the run keeps
stepping. Either way the run ends where stepping it would.

Backward, a reverse search of at least 64 bytes, with no byte limit, not asking
for the earliest match, and whose start state is not a match, probes up to five
cached transitions back from its end. When the probe reaches a non-matching
state that loops on itself, `findLast` or `findLastHighBitOr` skips back to the
loop's last exit byte, and the absolute-pointer and object loops both start
there in that state, as if they had stepped every skipped byte. An uncomputed
transition, a match, a dead or full-match state, a rejected exit set, or no
self-loop within five steps leaves the search as it was.

Both thresholds sit below the count route's 256 bytes. The stepped prefix
bounds the cost on short runs: a run that ends within its 32 bytes, twice the
length below which a scan's setup costs more than it skips, costs what stepping
costs. The exit set is computed once and cached with the state, so later
searches pay only the lookup. Computing it can add transitions that grow the
cache tables, even when the set is rejected, so each caller reads the current
tables afterward.

JMH `BenchmarkLanguageBulk` average time on Temurin 25.0.4+7, one r9g.xlarge
and one r8i.xlarge, three alternating rounds against the search without the
skips:

- rebar `curated/06-cloud-flare-redos/simplified-long`, `.*.*=.*` over 10,000
  bytes, fell from 22.5 us to 0.67 us on r9g and from 21.7 us to 0.27 us on
  r8i.
- `simplified-short` fell from 376 ns to 234 ns on r9g and from 308 ns to
  205 ns on r8i.
- The cloud-flare `original` fell from 120 ns to 100 ns on r9g and from 141 ns
  to 92 ns on r8i.
- Whole-line, non-DNA, email, URI, and a literal control stayed within 2% on
  r9g and 3% on r8i.

## Once-Per-Count Start-Byte Scan Selection

Fused DFA counting selects the start-byte scan primitive per count call,
sampling candidate density from the beginning of the text. The selection only
moves from the scalar scan to the vector scan. When the first sample selects
the vector scan, the count keeps it for every match. When it selects the scalar
scan, a later search that reaches the start-byte scan samples again from its
start while at least 4 KiB remain, and the count keeps the vector scan from the
first sample that selects it; on a text of at least 64 KiB, a later scalar
choice holds for the next 64 KiB past its sample. Searches served by the
fixed-distance scan do not sample, so dense input such as `[a-z]:[0-9]+` over
repeated `a:1.` does not pay a start-byte density sample per match. Below 4 KiB
the check reports the span as productive without sampling, so resampling there
would promote the vector scan for uniformly dense input. The per-search
productivity check that can reject candidate scanning altogether runs for each
match, except in the shared counts and the counts of at least 64 KiB described
below, which hold some of its results. Retries after a cache reset keep the
current selection. A count under 64 KiB with no sampling carried across calls
discards a vector scan promotion made by the search that retried, and the retry
samples again; a longer count, or one that carries sampling, keeps what its
sampling recorded. The selected primitive changes only how candidates are found,
never which candidates the DFA processes. Fixed-distance candidate selection
still samples at each match.

Sampling at each match boundary made the choice depend on where the previous
match ended rather than on the input. On sparse capture input with a match
every 8 KiB, the sample taken just after one match landed on the literals of
later matches and fell back to the scalar candidate scan for the following
span. On r9g, counting the three matches in 32 KiB fell from 4.1 us to 1.9 us
per call, with r8g at 0.43x and r8i at 0.17x of the previous time. A vector
choice is therefore never demoted. A single sample from the start of the text
misjudges the opposite shape, where a dense prefix precedes a long sparse
span, so a scalar choice is checked again when a later search reaches the
start-byte scan, at most once per 64 KiB on a text of at least 64 KiB. Dense
input rejects every such sample and keeps the scalar scan.

A count of every match settles a borderline first sample, 4 to 7 candidates in
64 bytes, with a second sample of 256 bytes in 16 windows at the same density
threshold. Start bytes such as digits cluster: on the mariomka ip row the
64-byte sample landed on a timestamp and kept the scalar scan for all 6.8 MB,
although only 1.6% of its bytes are digits. A clearly dense first sample skips
the wider one, since dense text pays for every sampled byte, and a count that
stops at a match end keeps the 64-byte decision, because it samples at every
call.

The inner-literal count hands each span whose next literal sits near the
previous match end over to a fused count that stops at the first match end whose
next literal is not near, so one text can take thousands of short fused counts.
Each sample spreads its windows over the whole remaining text, so every one of
those counts read the same far, cold bytes again. These counts therefore share
their sampling outcomes: a vector selection holds for the rest of the
inner-literal count, and a scalar selection and each start-byte and
fixed-distance productivity result, productive or not, hold for the next 64 KiB
past the position sampled, as the group-zero search cursor holds a rejection. A
vector selection recorded by a search that then retries is kept, and the shared
counts keep the 64-byte start decision.

A single count call over at least 64 KiB of text, the productivity check's
minimum, also shares one sampling among its own searches when the caller
carries no sampling across calls. Each search starts at a match end, so
sampling at every one read windows spread over the rest of the text once per
match, which was 36-55% of the time of the case-insensitive literal count rows.
Each later search of a call starts just past a match near the position
sampled, so the call holds only the outcomes that stay valid past those
matches: a fixed-distance rejection, a productive start-byte result, and the
scalar scan choice hold for the next 64 KiB, and a productive fixed-distance
result and a start-byte rejection are sampled again at each search. A held
start-byte rejection taken among a cluster of matches sent the search after the
cluster walking the plain DFA to a far match, and on the bounded-repeat context
row a held productive fixed-distance result sent a search into a 670 KB gap
where the fixed-distance scan stopped densely. The bounded-repeat context row
counts `[A-Za-z]{10}\s+[\s\S]{0,100}Result[\s\S]{0,100}\s+[A-Za-z]{10}`. Ratios
are to sampling at every match end, on r8i / r9g (lower is faster).

Alternatives rejected:

- Holding every outcome took 6.65x / 6.56x on the `\p{Sm}` count, 1.85x /
  1.55x on the IPv4 count, and 1.064x / 1.047x on the bounded-repeat context
  row. It ran the case-insensitive literal counts at 0.47x-0.67x /
  0.58x-0.74x and rebar's leipzig `ing` count at 0.90x / 0.92x, a win the
  shipped rule gives up because it needs held start-byte rejections.
- Holding only productive outcomes took 1.10x / 1.07x on the bounded-repeat
  context row, 1.005x / 0.999x on the `\p{Sm}` count, and 1.00x / 0.90x on the
  IPv4 count, and gave up part of the case-insensitive win, with
  `02-literal-alternate sherlock-casei-en` at 0.52x / 0.60x.
- Holding fixed-distance results either way, the scalar choice, and productive
  start-byte results took 1.065x / 1.052x on the bounded-repeat context row and
  1.010x / 1.013x on the `\p{Sm}` count, and 1.00x on r8i on the IPv4 count,
  unchanged on r9g, while keeping the case-insensitive literal count win.

The shipped rule takes 0.994x / 0.998x on the `\p{Sm}` count, 0.995x / 0.995x
on the bounded-repeat context row, and 0.994x on r8i on the IPv4 count,
unchanged on r9g, where that row is bimodal across forks. It keeps the
case-insensitive literal count win: `02-literal-alternate sherlock-casei-en`
takes 0.468x / 0.575x, `01-literal sherlock-casei-ru` 0.532x / 0.736x, and
`tom-sawyer-huckle-fin-insensitive` 0.481x / 0.656x.

## Bounded DFA Pointer Transition Table

Eligible one-byte DFA searches, forward and longest-match reverse, may use a
private table of 64-bit pointer transition entries after the existing
paired-table selection does not produce a usable route. With native access the
table is native memory whose entries are the absolute addresses of rows;
without it the table is a Java `long[]` whose entries are the byte offsets of
rows in the array. Neither form executes native code, loads a JNI library,
creates a file, or exposes a pointer through the public API. Long searches
request it immediately when pairing is unavailable. Short searches request it
after 16 repeated calls, so one isolated short search does not prevent the
same DFA instance from later selecting a paired table. The observation count
is only a performance hint; races can change promotion timing but not matching
behavior. A requested table is allocated when the exclusive search that
requests it ends. The first allocation waits once while the DFA could still
build paired rows and has computed no transition, so a search that ends before
computing one, such as a word boundary at a byte-order mark, does not decide
between paired rows and the table. Waiting only once keeps a DFA that never
computes a transition from retrying every search exclusively. A finite-budget
DFA initially allocates a small row-rounded table and geometrically enlarges
it as the graph grows. Each allocation is limited so that the remaining budget
can still hold the minimum Java state behind every additional pointer row. The
integer and object transition tables remain authoritative, and every zero
entry delegates to the unchanged object continuation.

One process-wide choice, fixed when the DFA class initializes, selects the
form: native memory when the containing module has native access enabled, the
Java heap otherwise. A DFA never mixes forms, so each pointer loop reads one
kind of segment in a process, and a failed native allocation falls back to
object rows, never to a heap table. The restricted everything segment that
native entries are read through is initialized only with native access;
otherwise no native allocation, warning, or initialization failure occurs. A
heap table is read through its array's heap segment, whose base address is
zero, so the same loops and tags serve both forms; offset zero is a reserved
row before the first state, so zero still means uncomputed. A heap segment
access checks its bounds, so a wrong entry would throw rather than read outside
the array. A native table's automatic arena is reachable through each raw
traversal, and a heap table's array through the root that holds it.
Allocation, backfill, incremental writes, publication, and reset use the
existing exclusive DFA cache mutation protocol.

The current allocation is charged to the `DfaInstance` budget: a native table
its bytes, a heap table the real size of its array, header included. Growth
copies and rebases the table into a new one and publishes it with a new cache
generation, as [Shared DFA Concurrency](#shared-dfa-concurrency) describes; a
reader still using the old generation keeps the superseded table reachable
until it returns. A reset publishes an empty table of the same size and keeps
its charge, unless the cap below or a failed allocation withholds it. A
superseded heap table is ordinary garbage, like the superseded Java tables, and
is neither counted nor capped. The DFA counts each superseded native segment's
bytes until the collector finds it unreachable; the JDK frees the segment later,
on its common cleaner. The count is released when the cap check next runs, so it
may briefly over-count. A growth at most doubles the segment, and the budget can
clamp it to less; a reset adds a whole segment; so without a cap, superseded
segments could accumulate without bound within one collection cycle. A reset
or growth therefore allocates a new native segment only while the counted
superseded segments, including the one it replaces, total at most twice the new
one. This cap, not the growth factor, keeps a DFA's counted native memory, live
and superseded, within three times its newest segment, which its allowance
bounds; the native memory actually held exceeds that only by segments the
common cleaner has not yet freed. A growth allocates its table after its Java
arrays and state, and a reset after its Java tables, so only small objects
follow the table. If allocating one of those objects throws
`OutOfMemoryError`, the new table is abandoned uncounted; the collection that
precedes that error makes any earlier abandoned segment collectable.

When a new table cannot be allocated, because superseded native segments fill
the cap or the allocation fails, the tables are published without the pointer
table, any charge it held is returned, and searches use object rows. This
applies to a reset, a growth, and the table's first allocation, in both forms.
The table stays requested (it is not given up as it is for a DFA that cannot
hold it), a warm cache may stay on object rows until its next exclusive search
or reset, and paired transitions stay disabled, because the condition is
transient. After a cap withhold, a later exclusive search allocates the table
again, sized for the current graph, once collection brings the superseded
segments under the cap. After a failed allocation, none is attempted again until
the next reset, so a memory shortage costs at most one failed allocation, and
the JDK's forced collection and retries, per reset. Failure to fund the next
geometric expansion from the budget remains ordinary DFA budget exhaustion and
follows the existing reset and matcher-fallback policy. Apart from a withheld
table, once an instance selects this pointer table, it does not switch to object
rows as the DFA grows, with one exception.

The table's per-state charge leaves room for fewer states per reset than object
rows, and whether a reset keeps or withholds a native table depends on whether
a collection has already found the superseded segments, so the same search
could reset often enough to fall back with the table and keep going without it.
Two rules give up for resetting too often: a search whose reset follows the
previous one too closely, and a count that resets twice during a rejected byte
scan, which then leaves later counts and group-zero finds to the other engines.
While the DFA holds the table or has it withheld, either rule gives the table
back instead and the search or count goes on. The next reset publishes tables
without it, and the count's two resets are counted after that reset. So the
table's charge never causes a fallback by itself: a DFA gives up for resetting
too often only by counting resets that ran on object rows.

Giving the table back records the release on the instance under the cache
mutation lock and changes no published generation or table. A search reads the
table only through the generation it captured, checks that generation's table
before any flag, and leaves the table for object rows of the same generation,
so a reader still on an older generation keeps using that generation's table,
and a stale flag costs at most one exclusive retry. A count counts its two
resets from the start of its current attempt or, once the table is given back,
from the reset that drops it; a retry for any other reason starts a new
attempt, as before. A count that does not hold the lock cannot reset or give
the table back, so the resets it sees are other searches'. When two of them
come, a count on a DFA that has held the table and not given it back, or gave
it back after the count judged those resets and before two resets followed the
one that drops it, hands the decision to the lock, keeping the matches it
counted and its start: under the lock it judges the same resets, gives the
table back if it still holds it, and searches again from the match where it
stopped. A count without the lock on a DFA that never held the table, or gave
it back before those resets, gives up as before.

The table stays given back for the instance's life, which is the compiled
pattern's: each program has one DFA instance per match kind, shared by every
matcher and thread that uses the pattern. Later searches on that DFA run on
object rows with paired transitions disabled, including searches the table
would have made faster, and the table stays given back even when the search
that gave it back falls back anyway. That later slowdown is accepted in place
of an NFA fallback now.

Where those object-row resets begin still follows the history before the
release. A heap table is never capped, so unless a heap allocation fails, that
history, and the release, are the same every run. With a native table that
history depends on collection timing: the cap can withhold the table at one
reset and keep it at the next, so the release, and the resets after it, land at
different input positions. A search or count right at a threshold can therefore
fall back in one run and not in another. Results are identical either way; the
residual changes only which engine runs at a threshold. The
`([a-q][^u-z]{23}x)` sequence at a 16 MiB budget is the known example: whether
the collector has run decides whether the count's next two-reset check finds
more or less than the byte scan's 64 KiB minimum remaining, so later counts and
finds run on the NFA and BitState in one run and on the DFA in the other.
Removing the residual means making states per reset independent of collection
timing, which changes the superseded cap that copy-on-reset's native memory
bound rests on; that is left to a separate change.

Explicitly unlimited DFAs remain object-based because they have no finite
allocation budget. Paired transitions remain disabled across later
resets because pairing was evaluated first.

Every table row and entry is eight-byte aligned. Computed match, dead, and
full-match transitions use the otherwise-zero low bits of a row's address or
offset as tags; zero continues to mean uncomputed. This lets the pointer loop
preserve a match boundary and finish computed terminal edges without converting
the entry back to an integer row or entering the generic continuation. Normal
entries remain aligned, and uncomputed or out-of-capacity entries still
delegate to the authoritative Java transition table. The tags add no
allocation or memory-budget charge and are written and backfilled under the
same cache mutation protocol as normal pointer entries.

Entries of both forms are read and written with plain access. Java lets a plain
`long` access tear, but each entry is written once, from zero to its final
value, every entry is eight-byte aligned, and 64-bit HotSpot reads and writes
an aligned `long` in one access, on the heap as in native memory; the table
relies on that. Opaque access would rule out tearing by specification, but with
JDK 25 C2 on AArch64 it kept the heap segment's field loads, liveness checks,
and alignment check inside the per-byte loop, about eight extra loads per byte.
With plain access C2 hoists them, and the bounds check is one predicted compare
off the load chain.

The JDK 25 C2/AArch64 native loop emits an extra dependent address operation
and retains an everything-segment range comparison that x86 C2 eliminates. The
native table remains the form when native access is available, because native
memory can hold absolute addresses, which put no base add on the per-byte
chain. The final report must characterize this architecture-specific difference
explicitly.

Without native access the DFA previously ran on object rows only. Each byte
loaded a compressed reference, decoded it, and checked the array's type, and
every match byte left the loop for the generic continuation. Flat tables had
lost to object rows before 1.0. A flat `int[]` table indexed by state and
class, and later a generic flat `int[]` path, were about 20–30% slower on the
Hard and Parens rows; pre-scaled byte offsets read through a var handle were
10–14% slower; and on AWS Intel and Graviton hosts, exact-stride `int[]` rows,
power-of-two state numbers, pre-shifted indexes, and 32- and 64-bit relative
offsets in native memory or direct buffers were neutral to slower. Each put a
row-index multiply, sign extension, or base add on the per-byte dependency
chain, while an object row's loaded reference is already the next row. Only
absolute addresses beat object rows: the native table ran at 0.71–0.78 times
object-row time on both hosts, while pre-scaled byte offsets in native memory
took 1.196 times as long as absolute addresses, which is why native entries are
addresses. After 1.0, walking the `int[]` transitions directly in three loops
was rejected because one row took 1.27 times as long on both platforms. The heap
table is the one form not measured then. Its entries are offsets because heap
arrays move and have no stable address, so it still adds a base, which JDK 25 C2
on AArch64 folds with the class scale into one extended add before the load. Its
tags keep match bytes in the loop, where object rows leave it. The Hard and
Parens rows barely exercised match bytes.

Decided (Dain, 2026-11-06): without native access, the DFA uses the same
tagged pointer table on the Java heap, reversing the earlier decision that kept
the table native-only. The AWS r9g and r8i comparison against object rows, in
both memory modes, passed the project's performance gate with the one accepted
loss below. With a table in both modes, a pattern counted by the compact
character-class counter may also keep its optional count DFA without native
access, which it previously kept only with it.

A/B result (AWS r9g and r8i, JDK 25, two hosts per platform and two rounds, JMH
average time; each figure is the time with this change divided by the time
without it, given as r9g / r8i): in safe mode, against object rows, the heap
table ran at 0.32 / 0.33 on `everything-greedy`, 0.65 / 0.74–0.75 on `any-one`,
0.74–0.75 / 0.77–0.78 on `03-date`, 0.81 / 0.91 on RE2 email `reusedContains`,
0.86 / 1.00 on `tom-sawyer-huckle-fin-prefix-short`, and 0.95–0.96 / 0.96–1.00
on `word-ending-n`. In native mode every row stayed within 0.97–1.01, except
Trino and Java `word-ending-n` on r9g at 0.82, the gain from the first
allocation's wait described above. The heap table took 0.99–1.15 / 0.99–1.40
times as long as the native table, the most on
`tom-sawyer-huckle-fin-prefix-short`. One row lost: RE2 ipv4 `reusedContains`
ran at 0.99 / 1.07 in safe mode, about 2 ns more per call on r8i on all four
host-rounds. Its cause was not found: the first allocation's wait alone leaves
the row unchanged, and an earlier build of the same table ran 0.995 there. It is
accepted as a marginal cost on a row that already lost to RE2 C++ on r8i in safe
mode, so it changes no comparison. An earlier run of the same table, before the
wait was added, projected each row's 2.0 comparator ratio through its measured
change: of 328 row, platform and mode pairs, none went from winning to losing,
and 34, across 28 rows and all in safe mode, went from losing to winning.

## Boolean Partial-Match Specializations

Full-Slice boolean `find`, `lookingAt`, and `matches` calls may use
compile-time plans for exact literal, nullable-start, contains, equality,
prefix, and suffix expressions. Compilation selects a pattern's full-Slice
routes once, and the pattern's class fixes them. Each language class is the
engine shape. Its package-private shape classes (exact literal, literal kernel,
literal alternation, fixed width, class run, byte table, and scan plan)
override `find`. They override the count, `matches`, and `lookingAt` where
their routes differ from the engine's. Each override makes one call to a final
route method of `CompiledPattern`, so a call site that sees one class can
inline one route. The class selects the routes; within them, the class-run
shape holds one immutable per-instance fact, whether it counts on the retained
count DFA, and the scan-plan shape three: whether `find` keeps its literal
kernel, whether a start-anchored plan counts with one attempt, and whether
short boolean calls run on the ordinary engine. The literal-kernel routes each
switch on their own plan kind, so the kind one operation runs stays out of
another's branch profile. Only a pattern that cannot match empty takes a shape
other than the engine. With compressed references, the `Re2` layout is 104
bytes, including references to the shared single-byte table, the
[matcher counter](#bounded-character-class-counting), and the
[required bytes](#short-window-required-bytes-reject), plus the base pattern's
per-pattern ordinary-engine flags. The scan-plan shape's three facts do not fit
in the base pattern's padding, so its layout is 112 bytes. A pattern with a
scan plan takes the scan-plan shape. Its full-Slice `find` keeps the literal contains,
prefix, equality, and final-line equality kernels, and its `matches` and
`lookingAt` keep their literal kernels, which answer without reading past the
literal. Its other boolean calls run the plan, apart from the short-input and
count routes that leave it for the ordinary engine. Scan-plan boolean calls
also stop once a match is certain, as described in the
[scan-plan guide](docs/benchmarks/SCAN_PLAN.md#integration).

These plans apply only when normalized expression analysis proves equivalent
existence semantics. APIs that accept a range, return boundaries or captures,
or produce a `MatchResult` retain the ordinary DFA, OnePass, BitState, and NFA
cascade when no scan plan applies. A group-zero matcher of a one-byte pattern
finds on the single-byte table in every language, Trino's included, while
fewer than 64 bytes are left; a longer search runs the group-zero forward
cursor, whose vector byte scan the table replaced, because the table, which
reads every byte, lost on 1024-byte rows. A pattern with a scan plan takes no
table, so its matchers keep the plan. Unsupported shapes, position-dependent
assertions, and other unproven forms retain the ordinary path. Compilation
builds the single-byte table; direct boolean calls allocate no matching storage.

When the general engine would otherwise answer, full-Slice boolean `find` and
count in RE2, Java, and Rust use the literal-alternation and fixed-width span
matchers Trino uses. Trino's subtypes call these matchers directly because
routing through the compiled pattern's dispatch measured about 9% more per
call on Graviton. A group-zero `RegexpMatcher.find()` of such a pattern, or of
a [large literal alternation](#large-literal-alternations), searches the same
matcher in every language, while Trino's span subtypes iterate theirs directly.
A literal alternation's matcher searches the trie only while fewer than 64
bytes are left, and a longer search takes the program's search, whose
start-byte scan the trie replaced: the DFA's candidate cursor, which the matcher
creates on its first long search, where the literals differ in length, and the
group-zero forward cursor where they share one. On 1 KiB rows of
`ERROR|WARN|FATAL`, a matcher's finds without that hand-off took 2.0x the time
on r9g and 4.4–4.6x on r8i, while 16 to 64 byte rows measured flat. The search
reports the leftmost-first span lying entirely in the matcher's region, as the
general engine does: a region end that cuts `abc` of `abc|x|ab` leaves `ab`.
Compilation resolves the matcher; a pattern with a single-byte table or a class
counter finds on that instead.

The count of a `.*literal.*` pattern whose dots match any character but a line
feed, and a group-zero `RegexpMatcher.find()` of it within the matcher's
region, run the dot-star literal matcher in RE2, Java, and Rust, as Trino's
dot-star subtype does for its span operations: the literal's first occurrence,
found with the pattern's contains search, widened over the characters the dots
match on each side. A count needs only each match's end, so it walks only the
dots after each literal, and resumes there. Both walks step over ASCII other
than a line feed without the width table, and a run with at least 64 bytes
left with the vector scan the DFA count uses to leave a `[^\n]` loop. The dots
read characters as the compiled byte ranges of `[^\n]` do, which depend only on
the pattern's encoding, so compilation picks a table of character widths and the
walk has no language branch. In UTF-8 the class holds all of U+0080 through
U+10FFFF, whose byte ranges accept overlong three- and four-byte forms, encoded
surrogates, and values above U+10FFFF, and reject bare continuation bytes, C0,
C1, F5–FF, and cut-short sequences; RE2's Latin-1 option and Rust's bytes API
with `(?-u)` read each byte as a character. Java's default dot also stops at
`\r`, U+0085, U+2028, and U+2029, and Rust's Unicode dot in bytes mode at the
surrogates, so the analysis takes neither for such a pattern; Java's `(?d)` dot
is `[^\n]` and takes the matcher. The matcher reserves its own size from the
forward-DFA budget after the contains search and, in every language, [yields to
the forward DFAs](#memory-budgets-and-dfa-caching). Release 2.0's Trino dot-star
subtype reserved nothing and was always built. So at the budgets where it would
cost a DFA, Trino now runs `.*literal.*` on that DFA rather than the matcher.
Trino's dot-star spans previously ended at any byte sequence that is not
well-formed UTF-8; they now read malformed input as Trino's ordinary engine
does, which its garbage-in, garbage-out contract leaves unspecified either way.

Full-Slice boolean `find` of literals in order with any bytes between them, such
as RE2's `a\C*b\C*c`, `a(?s:.*)b(?s:.*)c` under RE2's Latin-1 option, or
`(?s-u)a.*b.*c` in Rust's bytes API, searches for each literal in turn after the
previous one's match with the ordered-literal matcher of Trino's contains, in
place of the general engine. The matcher reads nothing between the literals, so
it takes only gaps that match every byte sequence. A UTF-8 `(?s:.)` gap keeps
the general engine in every language: the general engines of RE2, Java, and Rust
all reject malformed UTF-8 in it, so `(?s:a.*b)` matches none of `61 ff 62`,
`61 80 62`, `61 c0 80 62`, and `61 e2 82 62`, and Java, whose contract leaves
malformed input unspecified, keeps its engine's answer as its scan plans do. The
plan does not check a gap's bytes. A leading or trailing gap can match empty, so
it does not decide whether a match exists. The matcher's memory comes from the
forward DFA budget, as Trino's ordered-literal subtype's does, so a pattern with
a lowered boolean or tagged alternation program, or without room for the
matcher, keeps the engine. Release 2.0 had no such plan outside Trino, and the
matcher [yields to the forward DFAs](#memory-budgets-and-dfa-caching). Trino's
contains keeps its own ordered-literal subtype, which reads any bytes in its
`(?s:.)` gaps under its garbage-in, garbage-out rule; no Trino pattern takes the
plan, since Trino reads only UTF-8 and has no dot that matches any byte.

The literal-alternation matcher's root-table route restarts its trie at every
input byte, and a filter stop compares the literals that begin with its byte,
so it takes only alternatives of at most 64 bytes; a longer literal keeps the
general engine, whose DFA scans each byte once. The longest literal among the
measured rows is 35 bytes, in rebar's Russian Sherlock names. When its literals
admit a [start literal filter](#start-literal-filter), inputs of at least 64
bytes may skip to the positions the filter accepts, unless the filter's stops
are dense.

The fixed-width matcher checks every occurrence of one byte, such as the `s` of
`[a-z]shing`, which English text holds far more often than `shing`. Every
language, Trino included, therefore keeps the DFA's inner literal search when
its literal, or every literal of its set, has two or more bytes. A find whose
input lacks the literal reports no match without running the DFA, and one whose
literal first appears within the input's first 64 bytes runs the plain DFA. A
count searches for the literal only in inputs of 64 bytes or more. Counts of
fewer bytes, and matcher finds with fewer than 64 bytes left, run a second
fixed-width matcher that answers only those short searches, since there the
plain DFA measured Trino count 1.72-1.79x slower than the matcher on r9g and
1.36-1.40x on r8i, and iteration 1.12-1.16x on r9g. The pattern keeps that
matcher when it fits the forward DFA budget beside the search; full-Slice find
stays on the DFA, whose check for the missing literal wins on short rows too. A
Trino fixed-width pattern otherwise compiles no program, and the search needs
one, so a pattern whose `maxMemory` leaves no room for its program keeps the
matcher and compiles wherever the matcher fits. Where the program fits but
neither the search nor the matcher fits beside it, as for `[a-z]shing` from 2567
to 2797 bytes, the pattern also keeps the matcher on the route without a
program.

Nonempty exact-literal counting directly reuses `Prog.prefixAccel`, the existing
whole-literal prefix scanner, and advances by the matched literal length.
Empty-pattern advancement is unchanged. The new core single-byte boolean/count
route applies only to inputs of at most 64 bytes; larger inputs retain the DFA
and its ordinary failure fallback. Full-Slice boolean `find` of a capture-free
one-byte member run (`x+`) tests bytes with the single-byte table under the same
64-byte limit, and `find` of a compact class run stops at the first run reaching
its minimum. Anchored `matches` and `lookingAt` of a compact class run fail at
once when no run begins at the start; otherwise they run the same search, which
goes on to scan the non-members after the start only when the run there is
shorter than the minimum. Trino's existing byte count and explicit matcher
routes share the same table without changing their input-size policy.
Capture-retaining iteration remains generic. The table's retained size is
reserved before a DFA can snapshot its memory budget. If it does not fit, the
pattern keeps its generic execution route. Trino delegates to this table instead
of retaining a second one.

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
0.44 ns, and neither Graviton CPU regresses. At that time this pattern selected
the general DFA route and bypassed the new byte scanner; a one-byte member run
now takes the byte scanner on inputs of at most 64 bytes. Dispatch code size and
compiled layout change, making JIT/inlining sensitivity plausible without
establishing a specific instruction-level cause. Generic iteration stays
within one percent on all three CPUs, and key/value contains adds at most
0.97 ns. The small-operation losses and the eight-byte object growth are
accepted for the larger measured literal/delimiter gains, not treated as noise
or hidden in an aggregate. These are targeted qualification results, not a
complete release benchmark refresh.

Each ordinary call dispatches on its pattern's class rather than on a strategy
byte. A call site that sees more than two pattern classes, counting each
generated class, makes a virtual call. Future changes must keep each route's
fixed cost independent of input size, allocation-free, and within the
protected-path gate on Intel and Graviton.

Every case-sensitive `Prog.prefixAccel` scan of a prefix of two or more bytes
(the fused vector and SWAR pair scans, the first-byte scan with its last-byte
check, and the moved-probe scan) charges its failed candidates to the contains
search's comparison budget and continues with KMP once they pass it. A prefix
with long partial matches, such as `a`×500 `b` `a`×500 in a run of `a`, then
costs time linear in the input instead of the input times the prefix. The KMP
shifts are built when the prefix is configured and charged to the program's DFA
allowance, and a compile whose shift table does not fit what remains fails; a
prefix under 64 bytes whose first byte does not recur shares the contains
search's read-only table and is charged nothing.
Case-insensitive prefixes stop at nine bytes and keep their scan.
Because `Slice.indexOf` puts no limit on the bytes its failed candidates
compare, every language's contains of a literal of two or more bytes uses the
contains search when it fits the forward-DFA allowance. Where release 2.0 built
the search, in Trino and for a literal of more than 64 bytes elsewhere, it
reserves its memory whenever it fits, as it did; any other search [yields to
the forward DFAs](#memory-budgets-and-dfa-caching). Scan-plan leading and
required literals, capped at 256 bytes, still check every candidate; bounding
them is a separate change.

## Short-Window Inner Literal Check

A line-by-line search calls the matcher once per line, and most lines are
shorter than 64 bytes, too short for the inner literal search to skip enough
text to repay its reverse search and anchored confirmation. The search still
checks each such window for its literal: a window without it has no match, and
one whose first occurrence lies within 64 bytes of its start runs the ordinary
engine. A single literal's scan over at least 1024 bytes, where vector support
is available, uses the prefix scans' kernels and cutoff: the vector byte-set
scan for a one-byte literal and the fused pair scan for a longer one. A shorter
scan, including that check, uses the word-at-a-time scan. A case-insensitive
literal, such as `# noqa` in `\s*# (?i:noqa)`, keeps the word-at-a-time scan
for its exact head, `# `, and checks the rest of each hit with ASCII case
folding, at every length. Whole-text counts keep their 64-byte minimum.

The accepted costs were measured before RE2, Java, and Rust shared the
fixed-width matchers, 3 hosts a side. The Rust `reallyreallyhard0-32` row checks
a 32-byte window per call and takes 1.067x the base's time native and 1.062x
safe on r9g, and 1.095x and 1.107x on r8i; it stays ahead of the Rust crate, at
0.70x of its time on r9g and 0.77x on r8i. Everyday email reused contains takes
1.049x on r8i native, because every email holds `@` and the check never rules
one out; r9g and r8i safe tie. Holmes, `around-holmes-english` in Rust, takes
1.216x native and 1.126x safe on r8i, a row that already lost to the crate.

A separate scan for short windows cost more on short lines. Email reused
contains took 1.9 ns more than the base on r9g native and 4.0 ns more on r8i
native, against 0.4 ns and 1.9 ns with the shared scan, and lh3lh3's short date
lines gave back 6 to 7 points on r9g safe. Holmes tied the base on Graviton,
1.012x native, only because of how C2 then compiled the shared scan: the same
check on an earlier base ran it 1.66x slower. A later change to the shared scans
or their callers must therefore measure Holmes on r9g again.

Since long inner literal searches moved to the fused pair scan, Holmes runs
through that scan. Inlined, C2 copied the scan's vector loop into the callers it
compiled, and which copy each language ran followed its profiles and compile
order: on r9g, `\b\w+\s+Holmes\s+\w+\b` over the whole text took 33 us in some
languages and 59 us in others, where the copy sat inside the ordinary engine's
large match compile and spilled registers on every iteration. The scan now runs
out of line, as [Literal Prefix Vector Scan](#literal-prefix-vector-scan)
records. Measured with JDK 25 on a form of that loop that compared and ANDed
on every platform, the row took 31 us on r9g in all four languages and in every
fork, and on r8i, which keeps that form, 0.70x the inlined scan's time. On
r9g's XOR form, the row takes 17.6 us in all four languages. A form that kept
the earlier loop and moved its scalar tail into the method only to keep it out
of line ran at 42 us on r9g.

## Short-Window Required Bytes Reject

A line-by-line search whose pattern needs a rare literal or byte, such as
ruff's `# noqa` comment pattern or an alternation of URIs and email addresses,
ran the inner literal search or the forward DFA over every line, although only
the few lines that hold that literal or byte can match. Each compiled pattern
therefore keeps at most one fact about the bytes every match holds, and a
search window shorter than 256 bytes that lacks them reports no match before
any engine runs.

The fact takes one of two shapes:

- A literal of at least two bytes that every match consumes. It starts at the
  first element of the normalized top-level concatenation, groups looked
  through as for the inner literal search, where one begins, or at a pattern
  that is a single literal: an ASCII literal element, or a run of ASCII
  literal and case-pair elements whose exact bytes before its first
  case-insensitive letter number at least two.
  Case-insensitive letters compare through a mask. A literal that starts with a
  case-insensitive letter, as in `\s+(?i:noqa)`, is not used; whether its
  masked scan repays itself is unmeasured.
- Otherwise, unless the pattern has an inner literal search (which already
  rejects such a window before any engine setup), up to three ASCII bytes of
  which every match holds one, such as `/` and `@` for
  `[a-z]+://\S+|[^ @]+@[^ @]+`. An alternation takes the union of its branches'
  bytes, and a concatenation the bytes of its element that is rarest in English
  text. A case-insensitive letter gives no byte: one byte cannot cover both
  cases, and some letters, such as `k` with the Kelvin sign, also match a
  non-ASCII form. A prefix scan does not count as a reject: it runs inside the
  DFA, after the find and engine setup the check saves. So
  `(?:ASIA|AKIA|AROA|AIDA)[A-Z0-7]{16}`, whose prefix scan looks for `A`,
  keeps `A`.

A pattern with a single-byte table, such as `,`, keeps neither. Its group-zero
matchers find on the table once fewer than 64 bytes are left, and a check ahead
of the table would scan such a window twice. Windows of 64 to 255 bytes, and
matchers that keep capture groups, which never use the table, go without the
check. With the check, a matcher iterating `,` over 16- to 64-byte rows took 10 to 12%
longer on r9g and 12 to 19% longer on r8i (`BenchmarkPatternDispatch`, two Spot
hosts each, JDK 25).

The literal scan tests the literal's first and last bytes on each 8-byte word,
the last word overlapping the one before it, and verifies each candidate whole;
fewer than 8 candidate starts are compared byte by byte. When the first byte's
English-text probability is under 0.5%, the last byte's word is loaded only for
a word that holds the first byte. A common first byte keeps both tests on every
word: the same gate on the `c` of `coding` mispredicted and cost 14 to 15% on
that row.
The one-of-bytes scan loads each word once and tests it against all three
bytes.

Windows of 256 bytes or more skip the check. Most lines of a line search are
shorter, and there the scan costs less than the find and engine setup it saves.
A window that holds the bytes is scanned twice, once by the check and once by
the engine, and on a long window the second scan is no longer small beside the
setup. A pattern whose every match spans at least 256 bytes, counting a
required prefix, keeps no fact: no window the check would read can hold a
match.

Two hooks run the check, both before any engine setup: the matcher's general
find route, on the rest of its region right after the minimum-length check,
and the engine shape's full-Slice `find`, after the one-load reject of a
start-anchored program's impossible first byte, which costs less. Shapes whose
full-Slice `find` takes its own route, such as literal kernels and scan plans,
do not check there. A
hook inside the ordinary engine would still pay the find and engine prologue:
on r8i the uri-or-email row took 0.445 of the base's time with the early hooks
and 0.551 with the check in the engine.

The [short-window inner literal check](#short-window-inner-literal-check)
found that a separate short-window literal scan cost more on short lines than
sharing the inner literal search's own. This check is not a second route for
that search: it rejects a window before the engine starts, and a window that
holds the literal still runs the engine, which scans for it again. That second
scan is the cost to watch, on the rows that decision names: Holmes
`around-holmes-english` on r9g, Rust `reallyreallyhard0-32`, Everyday email
reused contains on r8i, and lh3lh3 date.

The fact only saves work, so its memory gives way. It is reserved after
compilation's routes and after the OnePass tables of a program with a
final-line end anchor (see
[Text-Dependent Assertions In OnePass](#text-dependent-assertions-in-onepass)),
from the program that holds the forward DFA allowance (the lowered boolean
program where there is one). It is kept only if every first-match and
longest-match DFA that could initialize before the charge, start-byte tables
included, still can. Trino's ordered-literal matcher, which
its frontend reserves later, takes its memory first, with the fact's memory
handed back, and the fact stays only where the same rule holds after both
charges. The charge follows the
[rule for optional plans and facts](#memory-budgets-and-dfa-caching): when the
allowance cannot cover the fact, the pattern compiles without the check, with
the same results.

Measured against the base without the check (bulk rows, time as a fraction of
the base's, r9g / r8i, two hosts each):

| Row | RE2 | Java | Trino | Rust |
|---|---|---|---|---|
| `curated/04-ruff-noqa/real` | 0.57 / 0.56 | 0.56 / 0.57 | 0.68 / 0.68 | 0.55 / 0.55 |
| `curated/04-ruff-noqa/tweaked` | 0.45 / 0.43 | 0.45 / 0.41 | 0.64 / 0.54 | 0.44 / 0.43 |
| `wild/ruff/noqa` | 0.55 / 0.55 | | | 0.56 / 0.54 |
| `wild/ruff/unnecessary-coding-comment` | 0.65 / 0.59 | | | 0.64 / 0.64 |
| `lh3lh3-reb/uri-or-email` | 0.34 / 0.43 | 0.35 / 0.42 | 0.38 / 0.47 | 0.36 / 0.45 |
| `lh3lh3-reb/uri` | 0.61 / 0.68 | 1.00 / 1.00 | 1.00 / 1.01 | 0.61 / 0.63 |
| `wild/ruff/space-around-operator`, no fact | 0.99 / 0.98 | | | 1.01 / 1.02 |
| `rsc/reallyreallyhard0-32` | 1.12 / 1.12 | 1.10 / 1.12 | 1.07 / 1.11 | 1.10 / 1.12 |

`reallyreallyhard0-32` is a 47-byte row that holds the literal, so it pays the
double scan. `around-holmes-english` counts one match in 613,357 bytes of text:
the full-text find skips the check, and only the find after the match runs it,
over the 35-byte tail, which it rejects before the engine. On the base before
long inner literal searches moved to the fused pair scan, the row went from 70
to 117 µs on r9g in Java, and in Trino on one of two hosts, and was flat or
faster on r8i: the matcher's larger find could stop HotSpot from inlining the
inner literal search and its word-at-a-time loop into it, the mode RE2 and Rust
already ran in at 117 µs. That mode is gone: where vector support is available,
a search that long now takes the pair scan, which runs out of line in every
language, as the
[short-window inner literal check](#short-window-inner-literal-check) records.
The row has not been remeasured with this check since.

## Inner Literal Class Pairs

The Rust crate's reverse inner strategy searches only for a literal or a set of
literals. The inner literal search also takes two adjacent elements that each
consume exactly one ASCII byte from at most three, at least one of them matching
more than one byte, when English text rarely holds the pair. The product of the
two sides' English text byte probabilities, whatever frequencies the pattern was
compiled with, must be under 0.002: assuming independent bytes, the pair is
estimated below once in 500 bytes. Each hit may pay a reverse search and an
anchored confirmation, so a commoner pair could stop the scan about as often as
the start-byte scan it replaces. In `["'][^"']{0,30}[?!.]["']` the pair is
`[?!.]["']`, searched as the set of its six two-byte members with a counted
vector loop that tests each side against its bytes, in the mask form
[Candidate Masks on SVE](#candidate-masks-on-sve) selects.

None of the crate's proofs admits such a pattern, so the pair has its own: the
prefix's first element that does not match only the empty string consumes
exactly one ASCII byte, no later prefix element can consume a member's last
byte, and no member holds one of those bytes before its end. An earlier match
that spanned a later candidate would then need its prefix to consume that
candidate's last byte, which only the one-byte opener could, and the opener lies
before the candidate. A pair no proof admits is skipped, so a later literal can
still decide. This is a performance-only deviation: the reverse prefix DFA and
the anchored forward DFA still decide every candidate, and randomized tests
compare the results with the ordinary engine.

Measured 3 hosts a side with compare and AND on both platforms,
`quotes-bounded` takes 0.54x the time of the start-byte scan on r8i and 0.74x on
r9g, and `sherlock/quotes` 0.71x and 0.98x.

## Inner Literal Byte Gates

The inner literal search skips a hit before either DFA runs when a byte beside
it cannot be one of a match. The byte before the hit must be one that can end
the prefix: one the reverse prefix program can consume first. Taking them from
the program gates any prefix that cannot match the empty string and has no
assertion at its end, such as the digits that end the day and month of
`([0-9][0-9]?)/([0-9][0-9]?)/([0-9][0-9]([0-9][0-9])?)`, or Rust's Unicode
`\w`, which ends a character of more than one byte with a continuation byte.
The byte after a literal, or a set whose members share one length, must lie in
the element after it when that element is a single ASCII byte class, alone or
repeated at least once; elements that match only the empty string, such as
`\b`, are looked past. The `\s` after `ing` in `\s[a-zA-Z]{0,12}ing\s` rules
out the `ing` of `kingdom`, as the crate's prefilter does by searching `ing\s`.

A skipped hit is no candidate. It leaves the bound on how far back the next
reverse search may read where it was, since no reverse search ran; raising it,
as a DFA rejection does, would make the second `ing` of `singing` fall back to
the ordinary engine. Nor does it make the search fall back: the gates are
checked before the rule that a first candidate near the search start falls
back, and before the one that a candidate inside text a confirmation read
does. The near-start check a find loop or count makes skips the same hits, so
it agrees with the search. Checked after the near-start rule, a slash after
a letter near the start of a short line sent the line to the ordinary engine
although no date could end there.

A gate that holds the literal's own byte at that edge, of either case for a
case-insensitive letter, is dropped. It borders runs of its own bytes, as
`[a-z]` does before `shing` inside a word, so it passes nearly every hit and
only adds its test. The gates are held as words in the search itself, so they
take no memory beyond the instance size the search's budget already charges.

A pattern without a gate keeps the search loop without the check, and a gated
pattern runs a copy of the loop with it. With the check in one shared loop,
patterns without a gate measured up to about 4% slower, on lh3lh3 uri
(Graviton) and lh3lh3 email (Intel) before their prefixes were gated. The gated
copy finds the first hit before it enters the loop, so a window without one, as
most lines of a grep are, returns without paying for the loop's entry: entered
for every line, it made lh3lh3 email 3–7% slower on Graviton and 5–7% on Intel
than with no gate. Measured
3 hosts a side, the gates take `ing-suffix-limited-space` to 0.78x on r8i and
0.83x on r9g, `ing-whitespace` to 0.78x and 0.77x, `before-after-holmes` to
0.71x and 0.76x, and `mariomka/email` to 0.91x and 0.90x.

## Trino LIKE Frontend

`TrinoLikePattern` is a separate language frontend rather than an RE2 dialect.
It compiles Slice pattern bytes and matches complete Slice inputs. On valid
UTF-8 input, `%`, `_`, and escape semantics follow Trino's SQL execution path,
using Trino `LikeMatcher` with optimization disabled as the semantic authority.
Malformed input and supplementary literals deliberately differ, as described
below.

A pattern that is not valid UTF-8 throws `TrinoLikePatternSyntaxException` at
its first invalid byte, as `TrinoRegexp`, `JavaRegexp`, `RustRegexp`, and UTF-8
`Re2` patterns do. This deliberately differs from Trino, which decodes such a
pattern with the JVM's replacement behavior; a malformed pattern has no
specified meaning, so it is a syntax error. Syntax-error byte offsets are
relative to the pattern Slice.

Wildcard runs are normalized during parsing. Exact, prefix, and suffix forms
use direct byte operations. Ordered literals use the shared
`OrderedLiteralMatcher`, which finds each literal of two or more bytes in turn
with the contains search below, whose KMP fallback preserves linear progress
once failed candidates compare more bytes than the search has covered, plus a
small budget, and a one-byte literal with a word scan for that byte. Patterns
containing `_` use a dedicated LIKE wildcard automaton. This frontend does not
translate LIKE into a regex string and does not fall back to the RE2 engine.

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
- Checking candidates inside the word loop kept C2 from keeping that loop
  innermost or unrolling it; on x86-64 it took 1.97x the time of
  `Slice.indexOf` on long GH Archive rows whose first byte is rare.
- An adaptive scan, by first byte until failed candidates grow dense and then
  by both ends, lost Chinese short rows on r9g by up to 1.47x and won less
  overall, 0.61x on r8i and 0.56x on r9g.

Interpreted ordered literals, and the first literal of a literal-gap plan, use
the same split search, as do the Trino regexp routes in [Trino regexp literal
searches](#trino-regexp-literal-searches). On the normative ordered rows,
interpreted ordered LIKE took 0.767x the time of verifying in line on r9g and
0.776x on r8i, and turned no win against Trino's LIKE into a loss; one r8i
Chinese cell went from a loss to a win. The gap-matcher cells time
`BenchmarkLiteralGapMatcher.sharedMatcher` and `.likePublic` on the same ten
scenarios each: sparse input with no match or one late match, a late match after
a failed candidate, input dense with the first literal for a long and a short
gap, and a late three-literal chain. The gap matcher took 0.77x to 0.99x the
time on 15 of the 20 gap-matcher cells on r9g, and 0.74x to 0.90x on the r8i
sparse-absent and retry cells. These costs are accepted:

- TPC-H Q13, 1.23x on r9g (498 to 611 ns per row, against Trino's 994) and
  1.15x on r8i (641 to 735 ns, against 951). Its short comments have frequent
  candidates, where splitting verification costs.
- r8i Russian ordered rows, 1.13x, 4 to 5 ns per row more, still faster than
  Trino's LIKE.
- Edge controls with separated fork ranges: `MIXED_LATE` 1.08x on r8i (2,780.0
  to 3,000.1 ns) and `ORDERED_LATE` 1.05x on r9g (2,790.6 to 2,923.4 ns), both
  still faster than Trino's LIKE.
- Nine r8i 32 KiB gap-matcher cells, which have no Trino comparison: three
  dense cells, 1.05x to 1.09x, and six late cells over `x` filler with one
  injected match, 1.03x to 1.09x. The late cells have the shape of
  `CONTAINS_ABSENT` above: the first literal's first byte is absent from the
  `x` filler, so the pair scan does more work per word than a first-byte scan.

Splitting only an ordered search's first literal cost less on Q13, 1.19x and
1.13x, but won less on every ordered geomean, 0.789x on r9g and 0.805x on r8i
for LIKE and 0.729x and 0.813x for regexp, and was rejected. Generated LIKE
emits the same split search for contained and ordered literals and accepts
1.06x to 1.07x on Q13; see [generated Trino LIKE
kernels](#generated-trino-like-kernels).

The LIKE and Trino-regexp parsers remain separate language frontends, but they
share the ordered-literal matcher when analysis proves equivalent semantics.
Trino regexp uses that matcher only for boolean `contains`; count,
position, extraction, replacement, split, and unsupported shapes retain the
complete RE2 execution route.

There is deliberately no literal-count cutoff between ordered scanning and the
DFA. The evidence comes from the earlier cutoff study, measured before ordered
searches verified candidates out of line. On both target architectures,
candidate distribution—not counts of 2, 8, or 32—determined the winner. A C8i
32-literal dense-at-start match was the one protected loss: 335.7 ns with
ordered scanning versus 225.7 ns with the DFA. The same ordered route improved
the other 23 C8i protected rows, every C9g protected row, and large sparse or
late-miss rows by up to tens of times. A runtime near-cursor counterfactual
reduced the dense loss by 51 ns, but expanded C2 main code by 61% on C8i and 46%
on C9g and caused stable 6-16% protected regressions. That hybrid is rejected;
the study's 110 ns absolute dense cost is retained rather than discarding the
general wins or adding an input-distribution heuristic.

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
switch. The short comparator reads the input without the slice's bounds
checks, so the length check must come first: the minimum size includes the
literal. Complex plans keep their interface call in a separate helper. Measured
with the comparison words in a separate short-literal object, this shape
reduced `matches()` from 270 to 187 bytecode bytes, with a 78-byte helper; it
changes compilation boundaries, not complex matching algorithms. The literal's
words now live in the pattern's own fields, and `matches()` is 203 bytecode
bytes.

Focused Java 25 qualification on C8i, C8g, and C9g compared isolated callers,
constant-pattern generated callers with mixed shared profiles, and callers
that rotate patterns. Against the short-kernel prototype, the selected shape
saves 1.08-1.45 ns, or 20-24%, on six-byte suffixes in the interleaved matching
profile. Exact costs 0.01-0.06 ns and prefix costs 0.11-0.24 ns. In that
qualification these short cases remained faster than Trino. The interpreted
TPC-H part-type rows, such as `PROMO%` and `%BRASS`, beat Trino on Graviton
only once the comparator read the input without per-read bounds checks. The
original Intel literal-gap benchmark recovers from 3044 to 2801 ns, close to
the 2776 ns pre-kernel control.

Accepted costs versus the prototype include C8g ordered matching, 4158 to
4331 ns, and interleaved gap matching, 4220 to 4352 ns. These are +4.2% and
+3.1%, or about +0.0053 and +0.0040 ns per input byte. The latter gives up a
prototype gain and returns close to the pre-kernel control. Long C8g literal
comparisons also lose several nanoseconds in some caller profiles; the
interleaved 512-byte suffix changes from 30.43 to 33.82 ns with overlapping
fork ranges. Normalize that comparison against its 512 compared bytes, not
the full input. With the separate short-literal object, construction took 144
bytes per pattern, including that object's 40 bytes. C9g exact compilation
plus first use remains slower than the pre-kernel control, 15.71 to 16.84 ns.

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
but replaces ShiftDFA with fused first-byte and last-byte candidate masks,
evaluated a SWAR word or a vector of candidate starts at a time. Only positions
surviving both folded boundary-byte masks run the complete folded-prefix
comparison; the few starts left after the last word or vector are compared
directly. Multi-byte prefix searches of at least 1 KiB use the platform's
preferred Vector API species when the application resolves
`jdk.incubator.vector` and that species holds 16 to 64 bytes. There, when a
boundary byte is an ASCII letter, each input byte is ORed with `0x20` before it
is compared with the lowercase letter; other boundary bytes compare exactly.
Shorter searches, and longer ones without vector scanning, use eight-byte SWAR
words, so short scans pay no vector setup. A single folded byte keeps its SWAR
byte search. Vector linkage stays in `VectorPrefixScanner`.

This is a performance-only deviation. Upstream ShiftDFA carries eight serial
table-dependent shifts through each unrolled block. The fused scan uses two
independent loads and parallel masks, which fits HotSpot code generation better
on the supported Intel and Graviton hosts. Randomized equivalence tests compare
the accelerated result with a scalar folded-prefix search.

## Literal Prefix Vector Scan

Case-sensitive multi-byte prefixes that probe their first byte use fused
candidate masks of two bytes, by default the first and the last, for searches of
at least 1 KiB, in the platform's preferred Vector API species when the
application resolves `jdk.incubator.vector` and that species holds 16 to 64
bytes. A candidate needs both bytes at their offsets, so a common first byte no
longer restarts the scan at every occurrence: over English prose, `aqj` stopped
at each of 35,301 `a` bytes in rebar's sherlock text and now stops at 12
candidates, each an `a` with a `j` two bytes later. Shorter searches, and longer
ones without the Vector API, keep repeated first-byte scanning for an ASCII
first byte and use fused SWAR masks for a non-ASCII one. Single-byte prefixes
and probes moved by byte frequencies keep their own scans.

A prefix that starts with a multibyte UTF-8 character ending before the
prefix's last byte pairs that character's last byte, not its lead byte, with the
prefix's last byte, in the vector and SWAR pair scans and in the vector search
for a literal behind a leading assertion. A lead byte names little more than the
script, which most letters of a text share: `0xD0` is 30% of the bytes of
rebar's Russian sherlock text, and `Шерлок Холмс` stopped at 10,385 candidates
for its 724 matches with the lead byte and stops at 726 with the last byte of
`Ш`. Each candidate is still compared with the complete prefix from its start,
so the choice of byte never changes what matches. The contains search of
`LiteralSearchKernel` and the vector filter of the scan plan's required-literal
check still pair the first and last bytes; the continuation byte has not been
measured there.

Vector linkage is isolated in `VectorPrefixScanner`, so applications can load
and use the engine without resolving the incubating module. Every surviving
candidate is compared with the complete prefix until failed candidates spend
the contains search's comparison budget, after which KMP finishes the search.
Pair byte selection keeps preprocessing bounded and the integrated scanner
compact.

Each pass of the vector scan counts the whole vectors left, runs a loop that
only tests each one for a candidate, and verifies candidates after it breaks, so
C2 compiles the test loop as an innermost counted loop; on r9g it is unrolled
four times with one safepoint poll per unrolled block. The earlier scan verified
inside its vector loop. With JDK 25 the counted loop took 0.50x to 0.60x the
earlier scan's time for literal rows on r9g, and about 0.8x on r8i and on r9g
with `-XX:UseSVE=0`; RE2 counting `The` over rebar's sherlock text went from
1.39x to 0.75x native RE2's time. The larger scan no longer fits C2's 325-byte
inline limit and runs out of line from Prog; these figures measure the whole
out-of-line scan, and `TestHotMethodSize` caps its size. The counted loop is
accepted at 1.13x to 1.16x for Cyrillic sherlock on r8i in all four languages,
1.03x to 1.05x for RE2's `the-lower` row on r9g, and 1.03x to 1.04x for
`no-match-uncommon` with the Rust and RE2 front ends on r8i. The likely cause,
inferred but not measured, is that the dense candidates of Cyrillic text make
the scan leave and re-enter its test loop at nearly every vector.

Running out of line is also intended: every caller calls the scan's compiled
method rather than an inlined copy shaped by the caller's profiles and compile
order, which ran Holmes up to 1.8x apart, as
[Short-Window Inner Literal Check](#short-window-inner-literal-check) records, so
`TestHotMethodSize` also keeps the method above the inline limit.

## Candidate Masks on SVE

Several vector scans build a candidate mask from more than one byte compare.
Four need every byte to match: the literal prefix scan's pair bytes, the scan
plan's contains search, the two three-byte groups of the
[start literal filter](#start-literal-filter)'s two-literal check, and the two
sides of an [inner literal class pair](#inner-literal-class-pairs); a later
paragraph covers scans that stop at any of several bytes. Where C2 compiles
vector code with SVE and a 16-byte preferred byte vector, as on r9g (Graviton5),
each loaded vector is XORed with its probe byte, the differences are ORed, and
one compare with zero gives the mask; the start literal filter first takes the
unsigned minimum of its two groups' differences, and the class-pair scan the
unsigned minimum of each side's differences from its up to three bytes.
Everywhere else these four compare each byte and AND the masks. The JVM's
`UseSVE` flag is read once, through the HotSpot diagnostic MXBean, on aarch64
only; if the flag cannot be read, the scans compare and AND.

This is a measured platform difference. SVE compares, and the AND that joins
their masks, are predicate operations, and on r9g they compete for execution
pipes with other scalar work, which on JDK 26 and later includes two
extended-register address adds per vector (JDK-8354282). The XOR form leaves
one predicate operation per vector. On r9g with JDK 25 it took 0.82x the
compare-and-AND form's time for `aqj` over rebar's sherlock text, 0.58x for
`zqj`, 0.45x for `∞|✓` over the Leipzig text, 0.52x for
`Tom.{10,25}river|river.{10,25}Tom`, and 0.55x to 0.60x for 64 KiB prefix
probes. On JDK 27 the same rows took 0.65x, 0.56x, 0.45x, 0.51x, and 0.57x to
0.62x. The class-pair scan, measured later on JDK 25, took 0.84x to 0.85x for
`sherlock/quotes` and 0.71x to 0.73x for `quotes-bounded` on r9g in all four
languages.

NEON has no predicates. With `-XX:UseSVE=0` the XOR form took 1.07x for `aqj`,
1.12x for `zqj`, and 1.05x for the Tom and river alternation, so NEON hosts keep
compare and AND. On r8i, AVX-512 compares into mask registers and ANDs them in
one `kandq`. A second compare masked by the first, the other form the Vector API
offers, was mixed there: 0.85x for sherlock but 1.09x for 1 KiB `USER` probes.
So x86 keeps compare and AND, written in line in each scan: the same compare
and AND moved into a helper that C2 inlined took 1.07x for `aqj` on r8i with
JDK 25.

The literal prefix scan's counted loop, described under
[Literal Prefix Vector Scan](#literal-prefix-vector-scan), keeps the platform
choice. With JDK 25 its XOR form took 1.10x to 1.13x the compare-and-AND form's
time on r9g with `-XX:UseSVE=0` (`aqj`, `zqj`, sherlock, and
`no-match-common`) and 1.06x for Cyrillic sherlock on r8i. On r9g the XOR
difference stays a vector until the scan compares it with zero. A form that
returned the mask from a helper had that helper left out of line at one of its
two call sites, boxed a mask on every search, and took 1.62x for `zqj` on r9g
with JDK 25. A vector returned from a helper left out of line is boxed the same
way, so the scan's helper keeps a single call site.

The folded prefix scan compares and ANDs on every platform. Its loaded bytes
are ORed with `0x20` first at letter probes, and the XOR form took 1.06x for
case-insensitive sherlock on r9g with JDK 25. SVE with other preferred widths,
such as 32 bytes on Graviton3, is unmeasured and keeps compare and AND; the
scans with fixed 16-byte vectors follow the same preferred-width key.

The two- and three-byte scans of the
[small candidate-byte vector scan](#small-candidate-byte-vector-scan), and the
scan plans' byte run stop over one to four bytes, take the same form under the
same key: each block is XORed with each byte, and the unsigned minimum of the
differences is compared once with zero, where the other form compares each byte
and ORs the masks. Callers choose between the two forms, so each compiles on its
own within C2's inlining limit. On r9g the XOR form took 0.91x to 0.92x the
compare-and-OR form's time for rebar's `imported/leipzig/huck-saw`, 0.76x for
`imported/sherlock/name-alt5`, and 0.46x for `BenchmarkTrinoScanPlan`
`LONG_SET`, on JDK 25 and 27. With `-XX:UseSVE=0` it took 1.05x for `huck-saw`
and 1.12x for `LONG_SET`, so NEON keeps compare and OR. On r8i it was mixed:
1.04x to 1.05x for `huck-saw` and 1.06x to 1.09x for `BenchmarkTrinoScanPlan`
`LONG_RETRY`, but 0.85x to 0.87x for `LONG_SET`, so x86 keeps compare and OR.
RE2 run stops differ: their one compare also replaces the compare for non-ASCII
bytes, and that form wins on every platform, as
[RE2 Scan Plans](#re2-scan-plans) records.

In each scan both forms find the same candidates. The x86 CI builds run the
compare forms, and the arm64 CI build runs the suite with the XOR forms and
fails if they are not selected.

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
first byte, and multi-byte leading literals in scan plans (Trino, RE2, Java,
and Rust), scan for one probe byte. A prefix whose probe stays at its first byte
instead takes the [pair scan](#literal-prefix-vector-scan) on inputs of at least
1 KiB when the Vector API is available. Every distribution
follows one rule: the probe is the rarest byte after the first, earliest on
ties, used only when its estimated probability is less than a quarter of the
first byte's; otherwise the probe remains offset 0. The factor keeps the first
byte when the estimate is close, because an estimate is not the searched text.
Each probe hit is shifted back to its candidate start and confirmed with the
complete literal. The RE2 prefix scan checks the farther literal end before the
full comparison. The Trino scan returns the same first whole-literal occurrence
as the first-byte scan, so candidate attempts, failed work, and the work budget
are unchanged. Density hand-off never applies to literal-led plans.

Probing is opt-in because a distribution that does not match the searched data
moves probes onto bytes that are common there. A mismatched distribution can
make literal scans several times slower per byte than the default, because the
probe byte occurs more often than the first byte; it never changes what matches,
and the cost stays linear in the input. On inputs of at least 1 KiB with the
Vector API, a moved probe also gives up the pair scan, so a chosen distribution
can be slower than the default there even when it matches the data. On r9g,
probing `/` for `https://` and `http://` ran 6.7-8.5x slower than the first-byte
scan over access-log text, which carries about twelve slashes and one `h` per
line.

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
value, so no preset probes them. Outside scan plans, `RustRegexp`, `Re2`, and
`JavaRegexp` scans move the probe only for multi-byte, case-sensitive literals
with an ASCII first byte, so a literal led by encoded text keeps first-byte
scanning there. Scan plans (Trino, RE2, Java, and Rust) move a plan's leading
literal, including one led by encoded text, to a rarer ASCII byte.
`tools/byte-frequencies` pins every source of both preset tables and regenerates
them exactly; only the derived numbers are committed.

Counts and learned distributions are add-one smoothed:
`(count + 1) / (total + 256)`. They measure the data they were built from, so
they may probe any byte after offset 0, including punctuation, digits, and
bytes at or above `0x80`.

Under every distribution, fused pair strategies, paired candidate scans,
case-folded prefixes and literals, required-literal checks, and single-byte
literals keep their default bytes: the first byte, or, for a pair scan of a
prefix led by a multibyte UTF-8 character that ends before the prefix's last
byte, that character's last byte. This is a performance-only deviation. Randomized tests
compare the RE2 site with scalar literal searches in UTF-8 and Latin-1, and the
Trino site with Joni, under a distribution learned from a short JSON sample and
under a supplied distribution that probes punctuation, digits, and UTF-8 bytes;
the same comparisons under default options cover first-byte scanning, and the
prefix reference comparisons on Russian and Chinese literals cover the
continuation byte.

## Small Candidate-Byte Vector Scan

Forward DFA start-byte and fixed-distance candidate sets containing exactly two
or three bytes use a packed, allocation-free Vector scanner for searches of at
least 1 KiB when `jdk.incubator.vector` is resolved and the preferred byte
vector holds 16 to 64 bytes. Other candidate counts, short searches, and JVMs
without vector scanning retain the branch-free scalar membership-table scanner.
A start set that gets a start literal filter scans with the filter instead.

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

## Start Literal Filter

When a forward DFA's start bytes are common in text, such as the few lead bytes
shared by many Cyrillic or Chinese characters or the cases of an English letter,
scanning for them stops often. If every match begins with one of at most eight
literals of at least three bytes, read six bytes deep with case pairs that
differ in one bit kept as one literal, the DFA instead checks three byte
positions of those literals together. It picks the three positions with the
lowest estimated acceptance, from English ASCII frequencies and fixed estimates
for UTF-8 lead and continuation bytes, and it is used only when that is
estimated to stop at least four times less often than the start bytes. Vector
code checks a vector of positions at a time on searches of at least 1 KiB,
comparing the bytes of up to two literal groups directly and, for more, looking
up one bit per group from the low and high nibbles of each of the three bytes;
shorter searches use the same check one position at a time, except for the rare
start sets below.

The nibble lookup loads one vector per step, at the third position, as the Rust
crate's Teddy does. It looks up all three positions' bits from that vector and
shifts the first two positions' bits into place from the previous step's. The
first step takes the previous step's bits from one load at the first position,
moved into the lanes the previous step's load would give them. The scan calls
no helper: with 64-byte vectors the slices' inlined code uses up C2's inlining
budget, and a helper that C2 then leaves out of line boxes its vectors.

The figures in this section come from two A/B rounds against the three-load
nibble scan of #135, on two r9g and two r8i hosts with JDK 25. A form whose
first step called helpers allocated 36 to 2,136 KB per operation on r8i, such as
1,673 KB for `curated/02-literal-alternate` `sherlock-casei-ru` and 2,136 KB for
`imported/leipzig/tom-sawyer-huckle-fin-insensitive`. With the first step
written out the scan allocates nothing, and it took 0.84x to 0.96x that form's
time on the nibble-scanned rows on r8i in RE2, Java, Rust, and Trino (1.00x for
Trino's `imported/sherlock/name-alt3-casei`), including 0.89x to 0.94x for
`sherlock-casei-ru`, which the helper form had lost, and within 2% on r9g.
Against the three-load scan, the one-load scan took 0.55x to 0.59x the time on
r8i for `sherlock-en`, 0.44x to 0.47x for `sherlock-zh`, 0.44x to 0.45x for
`imported/leipzig/tom-sawyer-huckle-finn`, and 0.54x for Trino's
`imported/sherlock/name-alt3`, where the helper form took 0.62x, 0.53x, 0.52x,
and 0.63x. On r9g it took 0.95x to 0.98x on the nibble-scanned rows. With NEON
(`-XX:UseSVE=0`) it took up to 1.07x in Rust, 1.03x to 1.06x for
`tom-sawyer-huckle-finn` and 1.02x to 1.07x for `sherlock-casei-ru` over the two
rounds, and was within 2% in Trino. These NEON figures stand for aarch64 hosts
without SVE, such as Graviton 2; r9g runs the scan with SVE enabled.

On x86 the high nibbles come from a 16-bit lane shift and an AND with `0x0F`.
x86 has no byte lane shift, so C2 widens, shifts, and narrows a byte shift, six
more uops per shift in r8i's assembly. The 16-bit form took 0.59x the byte
shift's time on r8i for `tom-sawyer-huckle-finn`, 0.58x to 0.61x for
`sherlock-zh`, 0.66x to 0.68x for `sherlock-en`, 0.75x for `sherlock-ru`, 0.87x
for `sherlock-casei-ru`, 0.90x for `sherlock-casei-en`, and 0.67x to 0.69x for
Trino's `imported/sherlock/name-alt3`. Other platforms keep the byte shift.
aarch64 shifts bytes in one instruction, so there the AND only adds work: the
16-bit form took 1.09x to 1.16x for `tom-sawyer-huckle-finn` and `sherlock-en`
on r9g. Platforms other than x86 and aarch64 were not measured.

The filter is built only when the Vector API is available, and only for start
sets of at most 16 bytes. Sets less than 5% probable in English text keep their
single-byte scanners, with two exceptions. The exceptions take the filter only
on vector searches of at least 1 KiB; shorter searches stop on the start bytes
through the byte table rather than check three bytes at every position.

A rare set of more than three bytes that is not one range would take the mixed
nibble-table scan. The filter's three-position scan costs about as much per
vector and stops far less often, so such a set takes the filter: for
`imported/sherlock/name-alt3` (`Sherlock|Holmes|Watson|Irene|Adler|John|Baker`),
whose start bytes are about 1.2% of English text, it took 0.26x to 0.31x the
mixed scan's time on r8i and 0.45x to 0.53x on r9g in RE2, Java, and Rust. Much
rarer mixed sets were not measured.

Rare small sets and ranges keep their single-byte scanners, which cost less per
vector, except with 64-byte vectors, as with AVX-512, where a set whose filter
holds two literals takes it. In the same A/Bs, on r8i, that took 0.56x to 0.60x
the time for `imported/leipzig/huck-saw` (`Huck[a-zA-Z]+|Saw[a-zA-Z]+`), 0.85x
to 0.96x for `name-alt2` (1.00x in Trino), and 0.87x to 0.94x for `name-alt4`.
On r9g's 16-byte vectors, with the SVE XOR two-literal scan, taking the filter
took 0.84x to 0.91x the time for `huck-saw` but 1.17x to 1.19x for `name-alt2`
and 1.19x for `name-alt4` in all four languages. With NEON (`-XX:UseSVE=0`) it
took 0.89x to 0.91x for `huck-saw` but 1.22x for `name-alt2` (1.00x in Trino)
and `name-alt4`. An earlier measurement of the same gate change on r9g, with the
XOR arithmetic written inline in the loop rather than in helpers, likewise took
0.72x for `huck-saw` but 1.03x for `name-alt4`. So 16-byte vectors keep their
scanners. 32-byte vectors, as with AVX2 or 256-bit SVE, were not measured and
keep theirs too. A rare small set or range whose filter would hold more
literals, and so need the nibble lookup, keeps its scanner on every platform.

The filter uses English frequencies even when the pattern was compiled with
other byte frequencies. Fixed estimates are enough for the common sets, which
need only to stop often; the rare exceptions rest on the scanner comparisons
measured above rather than on the estimate. On long inputs the start-scan
productivity check still samples the filter's own stops and falls back when
they are dense.

The literal-alternation matcher builds the same filter from its own literals,
reading each literal's bytes and treating positions past its end as accepting
every byte, so they are never chosen. It keeps the filter only when the Vector
API is available and the estimated density is at most 1/64. Searches with at
least 64 bytes left skip to the filter's next candidate and there compare, in
pattern order, the literals that begin with its byte, which picks the
alternative the trie walk would; shorter searches, such as short everyday
values, keep reading every byte through the trie's root table. Walking the
trie at each stop was 34-38% of the time of the Sherlock-style literal
alternation counts, which the direct compare runs at 0.82 of before on Intel
and 0.77 on Graviton. The compare at a stop is linear in the literals that
begin with the stop's byte, at most 64, where the trie walked a shared prefix
once; the measured rows have at most two literals per leading byte. The matcher
retains its literals for the compare, grouped in a 256-entry table by leading
byte.

A search with at least 64 KiB left first samples the filter's stops with the
DFA's start-scan productivity check. When they are dense, as on a log of near
misses such as `ERxO` under `ERROR|WARN|FATAL`, which the filter passes and the
literal compare rejects, the search reads the root table for the next 64 KiB
and then samples again; otherwise it keeps the filter for those 64 KiB. A
filter route whose stops stayed below one per 16 bytes, half the density the
sample calls dense, keeps the filter for the next 64 KiB without sampling
again: each sample reads windows spread over the rest of the input, often cold,
and sampling at every route was 3-7% of the time of the Tom Sawyer and
Huckleberry Finn row. A count in every language, a Trino matcher's repeated
finds, and each call of Trino's position, extract-all, split, and replace carry
the route from one match to the next, so they sample at most once per 64 KiB
rather than once per match, and only once when the first sample keeps the
filter and every route's stops stay below one per 16 bytes. A Trino matcher
carries the route in a route cursor of its own, reset with the matcher, since
the compiled pattern and its span matcher are shared between threads. An RE2,
Java, or Rust matcher never takes the filter: a find with at least 64 bytes
left hands off to the program's search, as the [literal-alternation
matcher](#boolean-partial-match-specializations) records, and a shorter one
reads the root table, so it carries no route cursor. The DFA candidate cursor
such a matcher creates for its long finds is a different cursor.

When the first sample of an input finds the stops dense, RE2, Java, and Rust
boolean find and count do not read the root table: they hand the input to the
general engine, whose DFA reads each byte once, as it did before the matcher.
Reading the root table on such near misses measured 1.30 to 2.05 times the
DFA's time in Rust. The decline reuses the compiled program and may build the
DFA within what remains of the budget, which the matcher's size has already
reduced. Only the first sample hands off: the general engine searches the whole
input, so a later hand-off would repeat what the matcher has read, and a later
dense sample keeps the root table. Trino's literal-alternation pattern holds no
general engine, so it reads the root table on a dense first sample as well.

This follows the Rust regex crate's packed Teddy prefilter, which the crate
treats as fast only when every literal has at least three bytes. It is a
performance-only deviation. The filter accepts every position where a match
can start, the DFA, the trie, or the literal compare at a stop still decides
each one, and randomized tests compare the results with the NFA or the general
engine.

## Bounded Character-Class Counting

Counting a complete, unanchored, non-nullable greedy repetition of one character
class scans the input once instead of running a complete matcher for every
result. Each maximal matching run is partitioned according to the repetition's
finite minimum and maximum, preserving ordinary leftmost-first match counts.

A Unicode class's exact membership bitset ends at its last member or at U+07FF,
whichever is later, up to 136 KiB for a class that reaches U+10FFFF; an
ASCII-only class in any language, or a Latin-1 or Rust byte-mode class, uses 32
bytes. A class of every non-ASCII character, such as a negated ASCII class or a
dot that matches newline, keeps 256 bytes through U+07FF and holds everything
past them. The general counter is built lazily by a single writer and retained
by the compiled pattern; a class repeat instead reuses its matcher counter,
described below, when one was retained. The lazily built counter's size is
independent of `maxMemory`, the planning budget that charges the program 120
and then 113 bytes per instruction and leaves the DFA caches the rest, and it
does not scale with the expression or configured budget.
Unsupported shapes continue through the ordinary DFA or matcher path. A
Unicode class reads malformed UTF-8 as the ordinary engine's byte ranges for
that class do, under the rule in [RE2 Scan Plans](#re2-scan-plans). A class of
every non-ASCII character reads leniently. Any other class accepts only the
encodings of its members, including any encoded surrogates it holds. Any other
invalid byte matches nothing. A valid encoded U+FFFD remains an ordinary code
point; a byte class reads bytes undecoded, so it counts malformed bytes like any
others. An ASCII-only class also reads bytes undecoded, since every byte of a
non-ASCII character, malformed or not, is at least 0x80 and matches none of its
members.

The count reads runs without branching on membership, which mispredicts at
nearly every word boundary of ordinary text. A byte class updates its run
arithmetically, byte by byte; on Graviton that beat reading memberships into a
block mask. UTF-8 text of ASCII and two-byte characters checks 32 bytes at a
time and reads a 32- or 64-byte block's memberships into a mask, which is why
such a bitset reaches U+07FF. Other text decodes character by character with
the branching run partition, in stretches that double from 32 bytes to 1 KiB
while checks fail, and then in a loop of its own. Checking before every 32
decoded bytes made CJK counts 1.33x slower on r9g and 1.64x on r8i, and on Intel
a decode loop inside the block loop and a branch-free partition each cost
another 10 to 15%. Letter-run counts take 0.55 to 0.62 of the previous time on
r9g and 0.47 to 0.49 on r8i.

Two counters are instead built at compile time and charged against the pattern's
memory budget. The compact counter serves a large Unicode class repeated many
times, or repeated without a fixed count; it is charged against the forward
budget, and a budget too small for it fails compilation. Compact count and
boolean find can use it. The matcher counter serves a pattern that is one large
Unicode class, such as `\pL` or `\p{Greek}`, or a greedy repeat of any class
with a minimum of at least one, such as `\w+` or `[0-9]{2,3}`, except a repeat
that RE2 or Trino case-folds; Java and Rust fold a class while parsing it, so
theirs stay eligible. A pattern with a capture keeps the DFA, as on the compact
route. The matcher counter is charged against the DFA budget before any DFA can
snapshot it, and a budget too small for it keeps the DFA. Both counters find
group-zero matches for a matcher, which would otherwise restart the DFA at every
match; the matcher counter cuts each run at the repeat's maximum, which gives
the leftmost-first matches, and a repeat compiled for longest match keeps the
DFA. Count keeps its counting algorithm and reuses the matcher counter when one
is retained for a class repeat; boolean find keeps its separate route. Planes 4
through 13 hold no characters, so only a Unicode class with members at or past
U+40000, such as `\PL`, `\p{Mn}`, or Unicode `\w`, needs a bitset past them;
such a class keeps the DFA on the matcher route rather than retain up to
136 KiB, unless it holds every non-ASCII character, as a negated ASCII class
does. A counter's span search skips bytes that cannot begin a member before
decoding, with one table load per byte or, for a sparse class, a vector scan
after a short scalar prefix. A Latin-1 or byte-mode class, or a UTF-8 class of
only ASCII members, marks exactly its members as start bytes, so the search
reads its whole run on that table, one load per byte, cut at the repeat's
maximum. In an unbounded repeat of a UTF-8 class of every character, such as
`(?s:.)+`, only a byte that begins no character under the lenient read ends a
run, so once the run reaches the minimum the search skips to that byte without
decoding. A dot that matches newline counts as a class of every character, so
count and Trino's class-span functions also take its repeats.

The span search stays small enough for C2 to inline into its callers, so the
byte run and the region check are out of line and `TestHotMethodSize` pins its
size. Reading class runs byte by byte first grew it past the 325 bytes C2
inlines into a hot caller, and the out-of-line call made Trino `\p{L}+` extract
up to 11% slower on Graviton. Inlining it again restores Graviton but costs
Intel about 1 ns on some of these 17 to 24 ns rows: Trino `\p{L}+` position
goes from 23.1 to 23.8 ns and extract from 17.0 to 18.1 ns. Graviton is weighted
over Intel, so this is accepted. Splitting the search into a byte method and a
decoding method, the decoding method being the search as it was before the byte
runs, cleared Intel position but made Intel Trino replace 12% slower; do not
repeat it without an Intel profile of replace.

The matcher counter is built eagerly by design, even for a pattern used only for
count or boolean find, such as Trino `regexp_like`. Setup work is never deferred
to the first match: Trino pays compile cost up front so the match path stays
lean. Each such pattern retains about 33 KB for `\pL`, and about 16 KB each for
`\p{Lu}` and `\p{Greek}`; an ASCII or byte class retains a few hundred bytes.

`Re2.count`, `JavaRegexp.count`, and `RustRegexp.count` expose this counter
and the existing DFA counting path. Unsupported shapes use a fresh zero-capture
matcher per call; callers can retain a matcher and iterate when workspace reuse
matters.
Qualification on C9g, C8g, and C8i confirms the bulk-counting benefit. A
single-use count that builds the lazy general counter pays its table
initialization: roughly 1.5 to 2.2 microseconds extra on ARM and 10
microseconds on Intel. A greedy class repeat such as `\s+` builds its matcher
counter at compile and count reuses it, so its first count pays no
initialization. Keep the public counting APIs with these trade-offs and retain
the existing matcher paths. A future change to counting initialization or
fallback routing requires its own qualification.

## Prefilter Source Tree

`FilteredRe2` builds each pattern's prefilter from the parse tree it compiles
that pattern from, so the prefilter and the program see the same tree. A
compiled `Re2` does not keep its tree; see
[Memory Budgets And DFA Caching](#memory-budgets-and-dfa-caching) for what
it keeps for its reverse program.

## Match-Every-Byte Shortcut

The full-match shortcut is enabled only for patterns that accept every possible
byte sequence: `\C*` in UTF-8 mode and dot-all `.*` in Latin-1 mode. UTF-8 dot-all
`.*` is deliberately excluded because malformed UTF-8 must still be rejected.

## Bounded Direct BitState Capture

A leftmost-first unanchored search through a reusable matcher that asks for
groups beyond group zero runs BitState directly when the program is not
one-pass and the search window is at least 4 KiB and within the program's
unanchored BitState text limit. This single pass replaces forward and reverse
DFA boundary searches followed by an anchored capture pass. Windows outside
those bounds, programs that cannot use BitState, and stateless caller-buffer
operations retain the ordinary engine cascade.

Upstream caps the visited bitmap at 256 Kibit (32 KiB). Unanchored searches (the
direct route above, and the capture fallback over the whole window when the DFA
cannot find the match's span) keep that cap. Each unanchored search clears its
bitmap over its whole window, and a find-all, count, or replace searches the
rest of the text once per match, so its work is quadratic in the window.
Anchored searches allow 256 KiB. The capture pass reads only the span the DFA
found, and an explicitly anchored operation may read its whole bounded window,
but a find-all never repeats an anchored search from each match the way it
repeats an unanchored one. The Java port targets server workloads, where a
larger bitmap is small compared with input pages and lets BitState rather than
the NFA capture a longer span. With one bit for each list at each position, a
program of L lists gets an anchored text limit of 2 Mibit / L − 1 bytes (the
256 KiB bitmap), and an unanchored limit of 256 Kibit / L − 1 bytes, never more
than the anchored limit after the lowering below. So the direct route exists
only for programs of at most 63 lists, whose unanchored limit reaches 4 KiB.

The bitmap is not the whole bound: a search's job stack grows with the input and
the program's captures, to 67 MB for `(?:(a)|a)*` on the 419,429 bytes the
anchored bitmap alone allows. The visited bitmap admits each list at each
position once, so a search over n bytes holds at most `1 + (n + 1) × J` jobs on
its stack at once, where J counts a job for each BYTE_RANGE with a hint and each
CAPTURE, EMPTY_WIDTH, or NOP that is not last, and one more for each CAPTURE's
undo. Compilation lowers the anchored text limit until the visited bitmap,
capture slots and job stack, at the capacity its doubling reaches, and the
search's objects fit the forward share, the allowance the capture NFA plans
against. A search's stack starts at sixteen jobs, or at its maximum when that is
no more than the modeled first capacity, and grows within that count, taking its
maximum at once near the end, so a deep search can hold its maximum where the
modeled doubling would have stopped short. A longer input takes the engine it
would take without BitState. As in the capture NFA, a search tracks only the
slots through the program's highest surviving CAPTURE, so a trailing group the
simplifier removes costs it nothing.

Upstream gives list heads, and so BitState, only to programs of at most 512
instructions. Regulator gives output list ids to every program with at most
32,767 lists, so BitState also runs on a larger program whenever the span fits
within its text limit. A large program, such as a Unicode letter class with a
dozen captures, otherwise extracted every short match's groups with the NFA,
where the Rust crate runs its bounded backtracker. The bitmap grows with the
list count, not the instruction count, and the job stack with the instructions
that push jobs, so these bounds alone limit the span, as the crate bounds its
backtracker.

## Matcher-Owned Capture Workspaces

`RegexpMatcher` owns reusable BitState and NFA scratch storage. BitState reuses
its visited bitmap, capture scratch, and bounded initial traversal jobs. NFA
reuses its sparse queues, traversal stacks, best-match storage, and primitive
capture thread arena. These workspaces are private to the non-thread-safe
matcher and never retain input bytes or caller group arrays.

An exceptional NFA exit invalidates its workspace before propagating the
failure. This prevents a later search from observing partially live capture
threads or queue state.

The low-level `Re2.findInto`, `lookingAtInto`, and `matchesInto` APIs remain
stateless: they acquire no hidden shared state or reusable workspace, and
allocate their search scratch per call. This separates the ordinary Java matcher
lifecycle from the caller-managed low-level contract.

## Allocation-Free Matcher Regions

`RegexpMatcher.reset(input, start, end)` binds a logical region of an existing
Slice without constructing a Slice view. Public matcher and snapshot offsets
remain relative to the complete logical Slice, consistent with Java Matcher and
the one-shot APIs. DFA start-state analysis and end-of-text transitions treat
the region boundaries exactly like the boundaries of an actual Slice view. This
is required for anchors, empty matches, word boundaries, and malformed or split
UTF-8 input. Rust patterns are one exception; see
[Rust Frontend](#rust-frontend). Java patterns are another: a Java region is the
context, except that, as in the JDK, `$` and `\Z` do not match at the start of a
nonempty region that splits a CR LF (without `(?d)`). Such a rare region runs
the pattern on the NFA, which is told the CR precedes it; see
[Java Regular-Subset Frontend](#java-regular-subset-frontend).

The region is represented only in matcher and DFA setup state, plus an NFA flag
for the Java CR case; protected DFA transition loops remain unchanged. A real
Slice view is constructed lazily only when execution reaches a capture engine; a
line rejected by DFA constructs no view. `MatchResult` retains the caller's
original Slice and stores absolute logical offsets.

## Immutable Multi-Pattern APIs

`Re2Set` and `FilteredRe2` use builders for pattern collection and return
immutable compiled objects. This removes public add/compile/match state machines
and makes empty collections fully usable. High-level match results use primitive
pattern-ID arrays instead of caller-mutated boxed lists. A `FilteredRe2`
collection uses one encoding and exposes its immutable canonical atom list.
Callers scan `canonicalizeText(input)` and pass primitive atom IDs from the
external scanner; mixed UTF-8 and Latin-1 patterns are rejected by the builder.
Building a pattern's prefilter stops after upstream's 100,000 visits of the
simplified regexp, and such a pattern is left unfiltered, always a candidate, as
upstream does.

## Bounded Candidate-Start Cursor

Eligible reusable matchers use a private candidate-start cursor for repeated
group-zero searches. The cursor scans the forward DFA's start-byte candidates,
its start literal filter's candidates, or the occurrences of a literal behind a
leading assertion, and verifies each candidate with an anchored first-match traversal. This avoids
the ordinary forward match followed by reverse boundary recovery for dense,
variable-length matches such as the Date Rebar workload. A search the inner
literal search takes, a window of at least 64 bytes whose first literal hit lies
at least 64 bytes past its start, skips the cursor.

Eligibility is deliberately narrow: first-match semantics, no required prefix,
no anchors or empty-width instructions, a non-nullable variable-length program,
an available start-byte accelerator, and no class counter that finds the
matches. The route has a linear reset-scoped work budget and a 128-transition
limit per candidate. A candidate's traversal reads, in one scan, a run of bytes
on which a matching state loops to itself, and charges one work unit and one
transition per byte. Exhausting either limit, encountering an unsupported match
shape, or failing DFA cache mutation falls back to the existing
forward-plus-reverse matcher path for the rest of that matcher reset. So does a
traversal of 16 or more bytes that reaches the end of the text with the DFA
still live and no match: every later candidate lies in text that traversal read,
so later checks would reread text the forward search reads once. The cursor is
re-enabled by the next reset.

Against main on r8i, sherlock name-alt4 for RE2 runs about 1.05 times as long,
and its case-insensitive form about 1.06 times as long as main's fast runs
(1.02 over all runs; every build runs that row in two speeds there). Both are
flat on r9g, at 0.997 and 0.993. On x86 the check loop's frame grows from 0x80
to 0xb0 bytes and its DFA row and position spill to the stack on every step.
The [divide-free row lookup](#dfa-row-index-without-a-divide) did not cure the
spill, and neither did detecting the self-loop by row identity, a one-byte peek
before each scan, moving the scan exit out of line, or both of the last two
together, so none of those variants was kept. The scan is kept because
cloud-flare original ReDoS runs at 0.13–0.16 of main for RE2 and Rust, and rsc
no-exponential at 0.57–0.63, and neither name-alt4 row flips against its
comparators.

The cursor can also search for a literal behind a leading assertion, as the Rust
crate does with its prefilter. A program whose assertions the DFA runs, with an
assertion before its first byte and a case-sensitive literal of 2 to 64 bytes
that every path consumes first, qualifies whatever its length. Assertions take
no width, so every match starts at an occurrence of that transparent start
literal. The cursor verifies each occurrence with the same anchored traversal,
whose start state comes from the byte before the occurrence, so a leading line
or word assertion is judged as a search that read every byte would judge it.
This route skips the start-scan productivity check, leaves windows shorter than
64 bytes to the matcher's other routes, and shares the general route's limits
and fallbacks. It follows the matcher's DFA quit streak for
word assertions that quit outside ASCII: a quit counts once and falls back
without starting the DFA again, a verified match or a proven no-match ends the
streak, and a matcher at the limit skips the cursor too. Assertions the DFA does
not run, such as Java's multiline `^` outside `UNIX_LINES` mode and Trino's
final-line `$`, use the same literal in the group-zero NFA's skip when no
thread is live. Compilation reserves the literal's retained memory from the
forward DFA budget and drops the literal if the reservation would prevent DFA
initialization.

The implementation uses the existing DFA reader and exclusive-cache mutation
protocol. Cached object transitions still require recovering the authoritative
state offset before the end-of-text transition; tests cover this warm-cache
case directly. The cursor adds no public API and does not change stateless,
capturing, longest-match, anchored, or nullable searches, or fixed-length
searches without a transparent start literal.

The final campaign must compare this route with the ordinary
forward-plus-reverse path and native RE2, including dense candidates and
protected non-capture controls.

## Reusable Group-Zero Forward Cursor

Eligible group-zero matchers lazily retain the forward first-match DFA and its
fixed-distance byte candidates after the bounded candidate-start route declines
the search. Repeated `find()` calls then use the existing count-search traversal
to return one forward boundary without rediscovering the DFA, match mode, and
acceleration state for every match. Fixed-length expressions derive the start
from that boundary. Expressions that match empty at every position, with no
assertion that could refuse one, start where the search did. One-character
expressions such as `\pL` start at the lead byte of a valid UTF-8 character
that ends at the boundary. Other matches retain the existing reverse DFA
boundary recovery. A search the inner literal search takes, a window of at least
64 bytes whose first literal hit lies at least 64 bytes past its start, skips the
cursor.

The one-character rule takes 0.55x to 0.61x the base's time on the dense letter
rows. Its first shape, measured on 97dd595b with 2 hosts a side, made r8i
`letters-upper` (Java `\p{Lu}`, where matches are sparse) take about 1.15x the
base's time; against the empty-match rule alone, 3 hosts a side, that cost was
the one-character rule's, 1.125x native and 1.093x safe. The final shape, which
shares the UTF-8 step-back helper and its ASCII early return and flattens the
start chain, took 0.86x the first shape's time on r8i `letters-upper` and 0.91x
to 0.94x on the letter rows, 3 hosts a side, so `letters-upper` is about 1.00x
the base. r9g `letters-upper` is flat throughout.

Eligibility is limited to first-match programs without a required prefix,
anchors, text-dependent assertions, a class counter that finds the matches, or a
word-run plan, which matcher iteration reaches directly. Empty matches are still
handled by the existing empty-at-start check before the cursor runs. DFA
allocation failure or cache-search failure falls back to the ordinary matcher
path, and the cursor uses the same reader and exclusive-cache mutation protocol
as other DFA searches. It adds no public API and leaves longest-match execution
unchanged. It also serves an alternation whose every branch is one capture
group: the tagged program picks the winning branch over the cursor's match span,
using the matcher region as assertion context like the group-zero search, and a
declined pick falls back to the ordinary matcher path.

## Word-Run Plan

A pattern that is exactly a word boundary, a greedy run of one ASCII
word-character class, and the same boundary, such as `\b\w+\b`, skips the
general engines. A pre-engine plan scans each maximal class run and checks the
boundary at its two ends. Java, Trino, and Unicode Rust boundaries read the
surrounding text through the shared context check. The ASCII boundary of RE2 and
of Rust with Unicode off reads only the byte across the run's end. In UTF-8, a
case-insensitive RE2 class such as `(?i)[a-z]` stays ineligible, because the
fold adds the Kelvin sign and the long s. When the plan's memory charge does not
fit the forward DFA budget, the pattern compiles without the plan instead of
failing.

Taking the ASCII boundary costs RE2 `\b\w+\b` about 1.06x the base's compile
time on r9g and 1.10x on r8i, and about 1.04x and 1.02x for a single-use count.
In return, iterating over dense matches takes 0.37x to 0.49x the base's time in
RE2 and Unicode-off Rust.

Bounded runs such as `\b\w{25,}\b` take the plan too. Only a maximal run can
match, so the plan checks its length, and with a minimum it probes that far
ahead to step over short words. On r8i, a short row with no qualifying word
costs about 2.5 ns more, 1.03x to 1.04x the base for Java and Trino. Java and
Trino long-word rows take 0.02x to 0.17x the base's time.

## Large Literal Alternations

A capture-free alternation of more than 64 case-sensitive, non-empty literals,
such as a dictionary of words, searches a leftmost-first Aho-Corasick automaton
ported from the `aho-corasick` crate's noncontiguous NFA, the route the `regex`
crate takes for such patterns. The DFA routes did not scale to them: rebar's
`dictionary/search/english-10`, 43,029 words, failed the reverse DFA's
admission check, so finds past the candidate-start cursor's budget fell to the
NFA, which re-added a root alternation of about 4,000 branches at every byte.

Trino compiles no program for such a pattern; every operation runs the
automaton, as every operation of an eligible alternation of 64 or fewer runs the
trie. RE2, Java, and Rust search the automaton for a group-zero
`RegexpMatcher.find()`, within the matcher's region; `matches`, `lookingAt`,
anchored, and capture operations keep the program. Full-Slice boolean `find` and
count keep the forward DFA wherever it initializes beside the automaton: on text
where most bytes lead a literal, the automaton's walk takes several steps per
byte, its root lookup, edge scan, and failure steps, to the DFA's one, and
measured 1.94x to 2.47x slower on r9g and 3.16x to 3.72x on r8i. Where the DFA
cannot initialize, the automaton answers in its place. A boolean `find` runs the
longest-match DFA and a count the first-match DFA, which needs less memory, so
`english-10` under the default budget finds on the automaton and counts on the
DFA. A DFA count that gives up returns no count, and the matcher counts on the
automaton. Trino checks the automaton against the forward-DFA budget before
compiling a program, while the other languages charge it to that budget. The
compile applies the same rule in every language, but a Trino pattern never
reaches it with an automaton that fits: the program's DFA budget is smaller than
the forward budget Trino checked.

At the root, with the Vector API and at least 64 bytes left, the automaton skips
the bytes that lead no literal with a vector scan for its leading bytes; walking
the root's row over every byte lost on 1024-byte rows. The scan uses the forward
DFA's kernel selection, two or three bytes or a contiguous range of 4 to 64, and
otherwise its nibble tables, which take 4 to 64 bytes with at most 8 high
nibbles. It is not the DFA's start-byte skip, which prefers its start literal
filter for up to 16 common start bytes, covers a single start byte with its
prefix scan, and vector-scans only with at least 1024 bytes left. The skip is
exact, since a byte that leads no literal leaves the automaton at the root and
records nothing. It starts only at a second root miss in a row, and, as in the
DFA's count self-loop scan, stops for the rest of the search once 4 skips in a
row each moved fewer than 16 bytes, as on prose, where a skip crosses only the
punctuation and space between words. The skip, with its tables where it needs
them, is built only where the automaton still fits the memory it may retain with
it; otherwise the automaton walks every byte, so no pattern that compiled
without the skip fails to compile. A skip that would cost either forward DFA its
initialization is dropped too, since the skip changes no result, so a set the
DFA cannot hold searches without it.

At a state past the root, the walk rejects a byte above the state's last edge
first, so its scan over the sorted edges needs no bound test. Against the
bounded scan, `english-15` search took 0.62x to 0.66x the time on r9g and 0.85x
to 0.86x on r8i. `english-10` is accepted at +4.6% on r9g and +3.6% on r8i, from
the sentinel edge scan on its dense sets, on top of about 1.03x to 1.11x on r9g
after the root skip, and the gd sparse Trino contains row at +1.5% on r9g.

The literal set is what the parsers' factoring leaves of a dictionary: literal
strings, empty alternatives as in `absentmindedness(?:|'s)`, and classes of at
most 16 code points merged from single-character alternatives, in the
alternation's priority order. A choice of several literals must end its
concatenation, as the factoring always places it, so every literal extends a
prefix written once in the pattern and the automaton stays bounded by the
pattern's size. A choice before more text, such as `(?:a|b)c` or `[ab][cd]`,
would expand to every combination, so it keeps the program. Extraction also
stops past a fixed amount of work, which a long run of separately flagged
literals or deeply nested choices would otherwise make quadratic in the pattern.
The expansion also stops once what it builds passes 8 times the forward share,
since building literal lists costs a few times the set they make; past it, the
set is built literal by literal, so it is admitted exactly when the finished set
fits. Where a choice ends the pattern, the expansion skips each literal that
equals or extends an earlier one, which can never match, but still counts it
toward the route's 64-literal threshold. The set also goes to the
literal-by-literal build when colliding hashes make that check's lookups step
past more than 8 slots per lookup in all, plus a small allowance. The trie's
extraction has the same work bound, so a pattern past it with 64 or fewer
alternatives keeps the program too, but no memory bound: it stops at the first
literal longer than the 64 bytes the trie takes, so it holds at most 64 such
literals per nesting level. Deliberate limits: RE2's longest-match mode keeps
the program, because the automaton reports leftmost-first matches, and literals
or classes carrying a case-folding flag keep the program. A class the parser has
already case-folded, such as Rust's `[Kk\u{212A}]` for `(?i)k`, is an ordinary
class.

## Extended OnePass Captures

Pinned native RE2 stores OnePass capture actions in spare bits of a 32-bit
transition word, which limits OnePass execution to group zero plus four explicit
capture groups. Java retains that action encoding and its small-capture loop for
requests within the native limit.

For one-pass programs that contain higher capture slots, compilation additionally
builds parallel 64-bit capture masks for match and transition actions. The
separate extended loop iterates only the set capture bits and supports up to 32
groups including group zero. The sidecar is charged to the OnePass share of the
compiled memory budget. Requests beyond 32 groups retain the ordinary BitState
or NFA fallback.

This representation is a performance deviation, not a matching-semantics
deviation. It avoids sending deterministic high-capture expressions through
BitState solely because of native RE2's packed-word limit, while the more
common requests within the native limit stay on the small-capture loop rather
than the extended one.

## OnePass Next-State Offsets

Upstream turns each OnePass action's next state into the next state's row offset
with a multiply on every byte, a serial chain that bounds one-pass speed.
Compilation packs each action with its next state's row offset into one 64-bit
step: the low half holds the action in upstream's encoding and the high half the
offset, so one load gives both and the loops chain a load, a shift and an add per
byte; the multiply leaves the chain.

Upstream's limit still decides which programs are one-pass: tables of upstream's
size, with four-byte actions, must fit in a quarter of the DFA budget. The tables
are charged at their real size, eight bytes per action, which is at most twice
upstream's charge. The extra comes out of the DFA's share, so the same programs
are one-pass as under upstream, compilation never fails for it, and the DFA keeps
at least half its budget instead of three quarters.

## Text-Dependent Assertions In OnePass

A OnePass condition holds RE2's six empty-width bits and a match-priority bit,
and the rest of the 32-bit word holds the capture actions and the next node, so
Rust's assertions, Java's and Trino's word boundaries, and the Java `$` and
`\Z` and Trino `$` that also match before a final line terminator do not fit.
OnePass evaluates them with the predicates BitState and the NFA use, on the
same context, and a one-pass program has a single path, so Java's priority
rules cannot select a different match. Java's multiline anchors and Trino's
multiline `^` and `$` keep a program off OnePass. Release 2.0 built no OnePass
tables for a program with one of these end anchors, so such a program's tables
[yield to the forward DFAs](#memory-budgets-and-dfa-caching) of the lowered
boolean program when there is one; the program's own DFAs never run, since they
cannot evaluate those anchors. The tables take their memory after every plan and
route has reserved its own, so they never change which plans, routes, and lowered
programs a maxMemory builds, and ahead of the
[short-window required bytes](#short-window-required-bytes-reject), which give
way to them. A program that drops them runs on BitState or the NFA. A program
release 2.0 also made one-pass keeps its charge.
One-pass programs whose only
text-dependent assertions are those it evaluates additionally build
parallel EmptyOp masks for match and transition actions. The masks are charged
to the OnePass share of the compiled memory budget. A condition that needs one
of those assertions carries one marker bit among the six, so the loops call the
empty-width check for it, and in these programs that check evaluates the full
mask against the text around the position. A match condition also carries its
node index in the bits an action uses for its next node, which locates its mask
without another loop variable. Building compares the full masks, so two actions
that differ only in such an assertion still make the program not one-pass.
Other programs allocate no masks, and their empty-width check reads only the
six bits.

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
boundaries from a terminal LF, including nonzero Slice offsets. Unicode `\b`
and `\B` run on the DFA as Rust's Unicode word assertions do (see
[Rust Frontend](#rust-frontend)): between ASCII bytes and text edges each is
its ASCII form, because Joni's word characters there are `[0-9A-Za-z_]` and its
six extra Latin-1 numbers are outside ASCII. A search that reaches one next to
any other byte quits to the fallback engines. Other text-dependent assertions,
except Rust's and the Java word boundaries, remain ineligible for DFA
execution. OnePass evaluates the final-line `$` (see
[Text-Dependent Assertions In OnePass](#text-dependent-assertions-in-onepass)),
while multiline `^` and `$` keep a program off OnePass.

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
references use the direct boundary route when eligible. A matcher reads the
eligibility once, when it is created, and the pattern stores no field for it.
The two forward programs and the lowered program's DFA share the original
forward memory budget. No warm DFA search loop contains a frontend-specific
branch; the Trino check runs only while an uncached transition is constructed.

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
whitespace and comments, comment groups, an exact repeat of one, such as
`{1}`, `{1,1}`, or `{1}?`, and flag changes other than `i` do not end a run, so
`(?i:\x66f)` and `(?i:f(?s)f)` match `ﬀ`, and `(?i:sß)` matches `ßs`. Nor does
a noncapturing group without a quantifier; its contents act as if written in
its place, so `(?i:s(?:s))` matches `ß` and `(?i)(?:[a]s)s` matches `aß`.
Captures, alternation, every other quantifier, assertions, and classes end a
run, even when they match only the empty string or cannot match at all, so
`(?i)ff{0}f` does not match `ﬀ`. Any change of `i` also ends a run, so
`(?i)s(?-i)(?i)s` does not match `ß`. Simplification respects the runs the
parser builds: repetition expansion, coalescing, alternation factoring, and the
removal of empty or impossible nodes never split a run, and join two runs only
when no fold can cross their boundary.

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

Pinned Joni also misses the empty match of a nullable expression before `$` or
`\z` at the end of an input when the expression's maximum length is at least
one byte and shorter than the input's last UTF-8 character, so `q?$` does not
match `Ω`, while Java `Pattern` finds it; plain `$` is unaffected, and modern
Joni 2.1.31 keeps the defect. Its search optimizer moves the first search
position to the expression's maximum length in bytes before the end, rounds a
position inside that character up to the end, and then tries no position at
all. This is treated as a Joni search-optimizer defect rather than a Trino
language rule; Oniguruma
[b77e5f3](https://github.com/kkos/oniguruma/commit/b77e5f34d3640ae46cc63b71f08aafe5362c7092)
fixed it ([Ruby #8210](https://bugs.ruby-lang.org/issues/8210)).
`TrinoRegexp` follows the Java semantics and has a direct regression test for
this case. The final benchmark matrix must cover direct, lowered, short
OnePass, allocation, and ordinary-route final-line cases.

## Trino Specialized Byte Scans

The Trino frontend may select a bounded scan plan for literals, deterministic
character runs, optional bodies, nested captures, and terminal tails. Preserve
specified matching and capture behavior on valid UTF-8. Malformed Trino input
has a garbage-in, garbage-out contract, while bounded input access and normal
matcher lifecycle behavior remain required. This does not authorize a public
malformed-input opt-in. RE2, Java, and Rust patterns select the same plans,
plus runs of sets of some non-ASCII characters, and report the ordinary engine's
results on malformed input too, as described in
[RE2 Scan Plans](#re2-scan-plans). By default the plan performs no runtime
code generation; an option may compile the plan into its own class, as described
in [Generated Trino Scan Kernels](#generated-trino-scan-kernels). The ordinary
semantic program remains the fallback. The
[scan-plan guide](docs/benchmarks/SCAN_PLAN.md) describes candidate
selection, resume rules, the executors, and optional-capture execution.

Plans may require the logical input start or perform unanchored search. An
unanchored plan must begin, after capture saves, with a literal, an exact
one-byte ASCII set, or an unbounded run of every byte except the one-byte
case-sensitive literal that follows it, and must contain a capture and a
variable run. A capture-free plan must contain a character run or a variable
literal repeat. These rules keep capture-free, fixed-width, and literal-only
patterns on their direct routes. A lowered final-line plan takes precedence
over a run-leading scan. RE2, Java, and Rust plans may also be led by a set of
some non-ASCII characters, as [RE2 Scan Plans](#re2-scan-plans) records.

Other leading runs, such as `(\d+)zz`, `([0-9]+)a`, `(\w+)@(\w+)`, and
`([a-z]+)-([0-9]+)`, keep the ordinary engine, plain or ASCII-folded, as do
optional-leading shapes. Each candidate inside a run that fails to complete a
match rescans the rest of that run, so the work depends on the input, and every
measured search strategy for this family made some workloads slower than the
ordinary engine. A run of every byte except the one-byte literal that follows
it, such as `([^/]+)/`, `([^:]+):([0-9]+)`, or `([^,]{2,}),`, is admitted. Its
scan stops only at the delimiter, which the literal then matches, and this
shape measured faster than the ordinary engine with no loss.

Character sets must be ASCII-only or have uniform non-ASCII membership, except
in RE2, Java, and Rust plans, as [RE2 Scan Plans](#re2-scan-plans) describes.
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
- The member table of a set of some non-ASCII characters, which only RE2, Java,
  and Rust plans admit, is built in place as a trie, and identical nodes are
  merged after it is complete. The shape of UTF-8 bounds that trie at 17,406
  masks and 277 blocks, however many pieces the compiler splits the set into,
  so the trie needs no charge of its own, even for a set of hundreds of
  thousands of ranges.
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
- Boolean `find` and `count` of a start-anchored plan with an optional fork and
  no boolean tail run on the ordinary engine's DFA over inputs of at most 48
  bytes, when both engines agree on every input and the pattern's boolean route
  without a plan is the DFA. A boolean tail is a run whose continuation succeeds
  on every suffix, so a boolean call succeeds once the run's minimum is
  present. A fork retries its continuation, dispatching the continuation's
  operations again even in the boolean plan without capture saves, and without
  a boolean tail the attempt reads as far as the DFA, so on short rows the plan
  only adds dispatch. On the plan, `OPTIONALS` and `OPTIONAL_RETRY` rows of up
  to 40 bytes took 1.05 to 1.96 times the DFA's time, while 64-byte `RETRY_64`
  rows were faster on Graviton and broke even on R8i. The 48-byte limit sits
  between them. `OPTIONAL_MIXED` depended on the host: it took 1.84 to 2.37
  times the DFA's time on R8i and 0.96 to 1.06 times on R8g, and was already
  faster on R9g, at 0.88 to 0.98 times. On R9g, R8g, and R8i the routed
  `OPTIONALS` and `OPTIONAL_RETRY` rows now take 0.99 to 1.03 times the DFA's
  time, and against the plan they replace, `OPTIONAL_RETRY` takes 0.51 to 0.87
  times its time, `OPTIONALS` 0.64 to 0.94, and `OPTIONAL_MIXED` 0.39 to 0.98.
  No row of `BenchmarkRe2ScanPlan`, `BenchmarkTrinoScanPlan`,
  `BenchmarkTrinoScanProfiles`, or `BenchmarkClickBenchRegexp` became slower on
  any host, and `RETRY_64` and the longer retry rows, which keep the plan, are
  unchanged. A Trino plan qualifies only when it consumes only ASCII bytes and
  has no final-line end, which the DFA cannot evaluate, so no Trino benchmark
  plan takes this route. Calls that report boundaries or captures run the plan.
- A start-anchored plan with a fork and capture saves keeps a boolean plan, the
  same operations without the saves, which the pattern resolves when it
  compiles. Only full-Slice boolean `find` and the count of a start-anchored
  plan, which makes one attempt, run it, through the same executors, so no
  method that reports positions changes. On the four-optional retry rows an
  attempt dispatches 28 operations instead of 50. On R9g, R8g, and R8i, RE2
  `RETRY_64` `contains` takes 0.75 to 0.94 times its time with saves, where
  R8i's 0.938 is within noise and a rebuild of the same source measured 0.913,
  and Trino `OPTIONALS` `contains` 0.80 to 0.84 times. Trino `OPTIONAL_MIXED`
  `contains` takes 0.59 times on R8i and 0.77 to 0.85 times, within noise, on
  Graviton, and on R8i `BenchmarkTrinoScanProfiles` `contains` takes 0.53 to
  0.66 times, which turns a 1.55 to 1.75 times loss against the rows without a
  plan into 0.93 to 1.05 times; `GROUPED`, at 1.047 times, remains a loss.
  Plans that are their own boolean plan are within noise: RE2 `ANCHORED_HOST`
  `contains` takes 0.998, 0.998, and 1.013 times its time without the boolean
  plan, ClickBench `HTTP` 0.990 to 1.000 times, and Trino `URL` 0.997 to 1.000
  times; ClickBench `WWW` is bimodal and within noise. Running the count's
  attempt as a boolean `find` keeps the RE2 `RETRY_64` `count` win, 0.77, 0.76,
  and 0.93 times, and makes RE2 `ANCHORED_HOST` and `ANCHORED_QUERY` `count`
  take 0.95 to 0.97 times.
- An unanchored plan with capture saves keeps a boolean plan too, which only
  full-Slice boolean `find` runs; the unanchored count loop keeps the full
  plan. Its attempts start from the same candidates and reach the same run
  ends, so resume positions after failures and the work budget are unchanged.
  When an unanchored plan starts with a run of exactly one character that
  writes no group and is not a boolean tail, the interpreter's case-sensitive
  executor starts an attempt at an ASCII candidate after that character,
  because the candidate scan finds exactly the run's member bytes; a lead-byte
  candidate still runs the run, and the ASCII-folded executor and generated
  kernels run every operation. In `BenchmarkLanguageComparison`, `keyValue`
  `reusedContains` takes 0.715 times its earlier time on R9g and 0.634 times on
  R8i. The one control that moved, `quotedField` `reusedCount` on R8i, took
  1.025 times, an accepted marginal cost of about 0.75 ns per call.

On malformed input, a search that hands off may report the ordinary engine's
match instead of the plan's. Every operation applies the same budget from the
same search positions, and the density handoff, the short boolean route, and
ranged boolean `find` use the ordinary engine only where both engines agree, so
`find`, `count`, `extract`, `position`, `extractAll`, replacement, and split
report one match sequence.

Retain restricted ASCII case folding when every Unicode fold cycle remains in
ASCII and requires no full multi-character fold. An ASCII letter pair, such as
`[Kk]` or a Java letter folded without `UNICODE_CASE`, matches only its two
letters, as [Pinned Unicode Case Folding](#pinned-unicode-case-folding) states,
so a plan folds it as that pair even for `k` and `s`, whose cycles leave ASCII.
The pair is the same tree in every language, so this holds for each of them.
Folded plans use separate executors. On R9g, folded anchored, optional,
unanchored, run, and mixed workloads take 0.20 to 1.10 times the ordinary
engine's time for `contains`, extraction, and replacement, with a geometric mean
of 0.38. The accepted costs are folded optional boolean matching, at about 1.10
times the ordinary engine's time, and unanchored folded compilation, at about
1.19 times. Ordinary URL and mixed operations remain faster than the ordinary
engine on R8i and R9g.

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
the ordinary engine's time on R9g and R8i. This was accepted because those calls
remain about ten times faster than Joni. The boolean plan for unanchored plans
with capture saves, recorded in the bullets above, supersedes this cost.

Rejected development alternatives are recorded to prevent repeating them
without new evidence:

- Separate boolean operation arrays regressed the URL and mixed controls. A
  save-free boolean array for forked start-anchored plans, chosen inside the
  shared executors, was re-measured in 2026-10. Its boolean wins were real,
  but it again slowed calls that report positions: RE2 `OPTIONAL_MIXED`
  extraction took 1.37 to 1.38 times its time on Graviton, Trino URL
  extraction 1.13 times on R8g, and Trino `OPTIONAL_RETRY` `count` 1.42 times
  on R8i. With the separate boolean plan adopted above, measured on a build
  that looked it up on every call, those rows take 0.96 to 1.02, 0.985 to
  1.01, and 1.015 times their time, within noise.
- Looking the boolean plan up on every call made short `contains` rows of
  start-anchored plans that are their own boolean plan, RE2 `ANCHORED_HOST`,
  ClickBench `HTTP` and `WWW`, and Trino `URL`, take 1 to 2.5 ns longer, so
  the pattern resolves it when it compiles.
- Selecting the ordinary boolean route by optional count and short input length
  regressed the targeted retry workload, the four-optional `OPTIONAL_RETRY` and
  `RETRY_*` rows. The narrower form above, selected by forks without a boolean
  tail and limited to 48 bytes, is adopted instead: on all three target hosts
  it made the routed rows as fast as the DFA, slowed no row, and left
  `RETRY_64` and the longer retry rows unchanged.
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
same memory charge, and on by default. They also admit runs of sets of some
non-ASCII characters, which Trino plans decline. Trino captures and extraction
run about eight times faster on a plan than on the ordinary engine, and nothing
in those shapes is specific to Trino. The frontend chooses a plan's language
explicitly, so `Re2.compileParsed` builds none. Java and Rust patterns select
the same plans under these decisions; the last bullets record the rules and
measurements specific to each language.

- An RE2 plan reports exactly what the ordinary engine reports on every input,
  including malformed UTF-8; RE2 has no garbage-in, garbage-out contract. Only
  operations that can consume a byte at or above `0x80` read input differently:
  runs admitting non-ASCII characters, with their bounded counts and minimums,
  delimited captures, and terminal tails. They consume a non-ASCII character as
  the ordinary engine's byte ranges do. A set of every non-ASCII character
  reads leniently, accepting overlong three- and four-byte forms, encoded
  surrogates, and values above U+10FFFF, and rejecting bare continuation bytes,
  `C0`, `C1`, `F5`-`FF`, and truncated sequences. A set of only some non-ASCII
  characters reads strictly, because the compiler splits its ranges into exact
  encodings: it accepts the encoding of each member, an encoded surrogate
  included when the set holds it, and rejects every other sequence. Its reader
  is built from that same split, so the two cannot drift, and it runs only at
  a non-ASCII byte, so ASCII input keeps the byte and vector scans. Trino plans
  are unchanged, and a Trino run still declines such a set, since Trino reads
  every non-ASCII byte alike.
- The analysis, builder, proofs, budgets, candidate scans, and routing stay
  shared, so a coverage change reaches the Trino, RE2, Java, and Rust
  frontends, except a change for sets of some non-ASCII characters, which Trino
  plans decline. Every skip proof holds under the RE2 rule, as the
  [scan-plan guide](docs/benchmarks/SCAN_PLAN.md#re2-patterns) restates.
  An unanchored search may also be led by one character of a set of some
  non-ASCII characters, such as `(?i)key=([^;]+);` led by the `k` that folds to
  the Kelvin sign, or by an unbounded run of such a set that holds every ASCII
  byte except the one-byte literal after it, such as `([^/\x{85}]+)/`. The set
  never holds a continuation byte, so its candidate scan finds exactly the
  bytes that can start one of its characters, and each attempt reads the
  character there by the set's exact encodings. Other variable leading runs of
  such sets, such as Rust's `(\d+)zz`, keep the ordinary engine, as ASCII ones
  do.
- Longest-match patterns build no plan, because a plan reports leftmost-first
  matches, and Latin-1 patterns build none, as in Trino.
- An option compiles a case-sensitive RE2 plan into its own class, as described
  in [Generated RE2 Scan Kernels](#generated-re2-scan-kernels).
- A plan must not be slower than the same pattern without it. On R9g, R8g, and
  R8i, RE2 `BenchmarkClickBenchRegexp` `contains` takes 0.09 to 0.84 times the
  time of a build without RE2 plans on every `HTTP`, `WWW`, `LONG_HOST`, and
  `NEWLINE_PATH` row and on Graviton's `OPTIONAL_FALLBACK` rows. On R8i,
  `OPTIONAL_FALLBACK` with dotAll is within noise, and without dotAll it takes
  1.08 times, 44.7 ns against 41.4 ns. That row is accepted as a known
  exception: each losing row retries the optional `www.`, which the DFA never
  does, and removing that retry would change Trino plans too. A failed `www.`
  retry occurs on 0.07% of Apache access-log referrers and 0.02% of GH Archive
  URL strings.
- A run scan with less than a vector of input left scans by byte, as Trino's
  run scan finishes its tail, and reads the member table only when the run
  excludes an ASCII byte. On R8i this takes ClickBench `NEWLINE_PATH` without
  dotAll from 1.22 to 0.84 times the build without RE2 plans and
  `OPTIONAL_FALLBACK` from 1.25 to 1.08. On Graviton, `BenchmarkRe2ScanPlan`
  `URL_MALFORMED` `contains`, whose runs stop every few bytes at a malformed
  sequence, takes longer than with a vector scan of the tail: with bytecode
  generation off, 1.06 to 1.14 times before the vector run scans broadcast
  their bytes once, as the next bullet describes, and 1.04 times on R9g after,
  within noise; with generation on, 1.18 to 1.23 times on R9g, before and
  after. The small scan for an RE2 run that excludes at most one ASCII byte,
  also described in the next bullet, was not compared with the vector-tail
  build directly. These slowdowns on malformed rows are accepted.
- The vector run scans compare each block with up to four ASCII bytes: the
  run's members or, for a run that matches most bytes, the bytes it excludes.
  They broadcast those bytes once before the block loop, because C2 does not
  hoist per-block broadcasts on aarch64 or x86. Against the previous build,
  this takes `LONG_HOST` rows of `BenchmarkClickBenchRegexp` and
  `BenchmarkRe2ScanPlan` 0.45 to 0.72 times their time, ClickBench `WWW` and
  `BenchmarkRe2ScanPlan` `URL` rows 0.89 to 0.96, `LONG_SET` rows of
  `BenchmarkTrinoScanPlan` and `BenchmarkRe2ScanPlan` 0.56 to 0.80, and
  `BenchmarkTrinoScanPlan` `LONG_OPTIONAL` without dotAll 0.68 to 0.76. An RE2
  run that excludes at most one ASCII byte, the common case, keeps a small
  scan of its own, because a larger one stopped generated RE2 kernels from
  inlining their run scan. These losses are accepted:
  - On R8i, ClickBench `HTTP` with dotAll takes about 1.11 times its time,
    35 ns against 31.5 ns. The loss tracks how C2 compiles the plan's matching
    loop, but its cause is only partly explained, and moving the broadcasts
    before the loop may still contribute. The row is still well under the
    build without RE2 plans.
  - Runs that end within a block pay for broadcasts they do not use:
    `BenchmarkTrinoScanPlan` `ANCHORED_PREFIX` on R9g with generation off
    takes 1.7 ns longer, 5.6%, and generated kernels 0.2 to 0.8 ns longer.
    Skipping the broadcasts when fewer than 16 bytes remain traded these
    losses rather than removing them.
  - On R8i with generation off, `BenchmarkTrinoScanPlan` `RETRY_256`,
    `RETRY_128`, and `OPTIONAL_MIXED` took 2.4 to 5.8 ns longer in some
    measurements and were within noise in others.
- An RE2 run's vector stop scan finds a stop with one signed compare per block,
  on every platform. It stops at non-ASCII bytes and at up to four ASCII bytes
  the run excludes, so a byte XORed with an excluded byte is zero where the two
  are equal and negative where the byte is non-ASCII. The unsigned minimum of
  those XORs keeps both properties, and a stop is a lane below 1, or below 0
  for a run that excludes no ASCII byte. This replaces a compare for non-ASCII
  bytes and one per excluded byte, joined by ORs. Against that form, on R9g
  `BenchmarkRe2ScanPlan` `LONG_SET` takes 0.39 times the time and `LONG_PATH`
  0.67 to 0.68, with `-XX:UseSVE=0` 0.95 and 0.90, and on R8i 0.76 to 0.78 and
  0.87 to 0.88. Generated kernels move the same way, and the `URL`,
  `URL_MALFORMED`, and `ANCHORED_HOST` rows stay within 0.95 to 1.01.
- The short boolean route to the DFA described in
  [Trino Specialized Byte Scans](#trino-specialized-byte-scans) applies to an
  RE2 plan whatever bytes it consumes, because an RE2 plan agrees with the
  ordinary engine on every input. `OPTIONALS`, `OPTIONAL_RETRY`, and the forked
  `OPTIONAL_MIXED` plans take it.
- Java patterns select the same plans, with the same routing and memory
  charge. The Java frontend compiles to the same byte ranges as RE2, so a Java
  plan reads input by the RE2 rule and reports what the ordinary engine reports
  on every input, which on valid UTF-8 is the JDK's result. A plan's end is the
  end of input, or with `UNIX_LINES` `$` and `\Z` also the position before a
  final LF. Java's default `$` and `\Z` end before one final LF, CR, CR LF,
  U+0085, U+2028, or U+2029, never between a final CR and LF, and the plan
  compares those exact bytes, which agrees with the ordinary engine's strict
  decoding on malformed input. The JDK gives back run characters to reach that
  position and a run does not, so a variable run that can reach a Java end
  without consuming more input must admit none of LF, CR, U+0085, U+2028, and
  U+2029, or every one and be unbounded; `^x([^\n]+)$`, `^x(\S+)$`, and
  `(?s)^x(.{1,3})$` keep the ordinary engine. Java's default dot admits none of
  them, so it reaches a plan, before `$` too, as do folds with a non-ASCII
  member, such as `(?iu)k`, each as a set of some non-ASCII characters.
  Multiline anchors, Java word boundaries, and reluctant runs keep the ordinary
  engine, as they do in Trino. Java's `(?i)` without `UNICODE_CASE` folds each
  letter as its ASCII pair, so `(?i)s` and `(?i)k` reach a plan.
- A Java plan must not be slower than the same pattern without it. On R9g and
  R8i, the 95 rows of `BenchmarkJavaScanPlan` without dotAll, five matching
  methods over the 19 workloads `URL`, `URL_SHORT`, `ANCHORED_HOST`,
  `ANCHORED_QUERY`, `ANCHORED_WWW_HOST`, `UNANCHORED_URL`, `UNANCHORED_END`,
  `UNANCHORED_TAIL_4096`, `OPTIONALS`, `OPTIONAL_RETRY`, `OPTIONAL_MIXED`,
  `LONG_OPTIONAL`, `RETRY_64`, `LONG_RETRY`, `LONG_HOST`, `MIXED`,
  `URL_NON_ASCII`, `URL_MALFORMED`, and `FOLDED_MIXED`, take a geometric mean of
  0.217 and 0.218 times the ordinary engine's time, and a build without Java
  plans takes the ordinary engine's time. Two workloads fail the bar, and both
  are accepted as known exceptions because the RE2 plan loses on the same rows
  and the other host gains on them. On R9g, `MIXED` `contains` and `count` take
  1.055 to 1.064 times the ordinary engine's time, 60.3 ns against 57.1 ns and
  62.8 ns against 59.4 ns; the RE2 plan takes 1.03 to 1.05 times there, and on
  R8i the same rows take 0.90 times. On R8i, `UNANCHORED_END` `count` takes 1.06
  times, 81.7 ns against 76.8 ns, in one build, while its identical rebuild is
  within noise; the RE2 plan takes 1.095 times there, and on R9g the row takes
  0.73 times. These Java rows end at `\z`. Adding Java plans left
  `BenchmarkRe2ScanPlan` rows unchanged. The Java end step, which changed the
  end check that Trino and RE2 plans share, moved no row beyond noise: against
  plans without the Java end, 61 rows of the scan-plan benchmarks take a
  geometric mean of 1.009 and 0.996 times their earlier time, and none is
  slower beyond the spread between identical builds. Those rows are the
  `BenchmarkTrinoScanPlan` workloads `URL`, `OPTIONALS`, `OPTIONAL_RETRY`,
  `OPTIONAL_MIXED`, `RETRY_64`, `MIXED`, and `LONG_RETRY`; the
  `BenchmarkRe2ScanPlan` workloads `URL`, `OPTIONALS`, `OPTIONAL_RETRY`,
  `RETRY_64`, `MIXED`, `LONG_HOST`, and `ANCHORED_HOST`; the `\z` patterns of
  the `BenchmarkJavaScanPlan` workloads `URL`, `OPTIONALS`, `MIXED`, and
  `LONG_HOST`; and `BenchmarkTrinoScanProfiles` rows.
- The Java end is timed on 10 workloads of `BenchmarkJavaScanPlan` with
  `javaDollar=true` and dotAll: `URL`, `URL_SHORT`, `UNANCHORED_END`,
  `OPTIONALS`, `OPTIONAL_RETRY`, `OPTIONAL_MIXED`, `RETRY_64`, `LONG_HOST`,
  `MIXED`, and `URL_MALFORMED`. Without dotAll the patterns set `UNIX_LINES`,
  so `$` ends only before a final LF, and every row ends at most in LF, so the
  other terminators are not timed. On R9g and R8i their 50 matching rows take
  a geometric mean of 0.131 and 0.114 times the ordinary engine's time, and
  every row is faster than the ordinary engine. The plan costs about what the
  `\z` plan costs, 0.997 and 1.072 times over the same rows; most of the larger
  gain is the ordinary engine taking 2.5 to 3.1 times longer with `$` than with
  `\z`. On R8i, `OPTIONAL_RETRY` and `OPTIONALS` `contains` are slower than
  with `\z`, 87 ns against 52 ns and 77 ns against 66 ns, because the `\z`
  pattern takes the short boolean route to the DFA described in
  [Trino Specialized Byte Scans](#trino-specialized-byte-scans), and a Java end
  cannot, since the DFA cannot evaluate it; they still take 0.14 to 0.16 times
  the ordinary engine's time.
- Rust patterns select the same plans, with the same routing and memory charge.
  The Rust frontend compiles to the same byte ranges as RE2, so a Rust plan
  reads input by the RE2 rule and reports what the ordinary engine reports on
  every input, which on valid UTF-8 is the pinned crate's result, as it is on
  any input in byte mode. Without multi-line mode Rust's `$` is the strict end,
  and Rust's dot and `(?i)` match as RE2's do, so those reach a plan unchanged.
  Byte mode keeps its plans, unlike RE2's Latin-1 patterns: a plan never matches
  empty, so byte mode's one-byte advance past an empty match never applies, and
  the analysis rejects every byte construct that can match a byte above 0x7F,
  byte classes and literals by their Latin-1 node flag and the any-byte dot by
  its kind, so a plan reads only constructs that byte mode reads as UTF-8 does.
  Multiline anchors, CRLF ones included, word boundaries, including Rust's
  start, end, and half word boundaries, and reluctant runs keep the ordinary
  engine. Unicode Perl classes and folds with a non-ASCII member, such as `\w`
  and `(?i)k`, reach a plan as sets of some non-ASCII characters.
- A Rust range or matcher region keeps the whole input as assertion context, as
  the crate's `Input::range` does, while a matcher's start-anchored plan reads
  its region as the whole input. A Rust matcher therefore runs that plan
  directly only over the whole input; a partial region takes the Rust search
  route, where the plan answers only a region that ends at the input end, as for
  a range.
- A Rust plan must not be slower than the same pattern without it. On R9g and
  R8i, the 125 rows of `BenchmarkRustScanPlan` without dotAll, five matching
  methods over the 19 workloads of the Java measurement and the `NON_ASCII` and
  `MALFORMED` variants of `UNANCHORED_URL`, `LONG_HOST`, and
  `UNANCHORED_TAIL_4096`, take a geometric mean of 0.215 and 0.221 times the
  ordinary engine's time. Over the Java measurement's 95 rows they take 0.215
  and 0.219 times, and Java plans took 0.217 and 0.218. One row fails the bar
  and is accepted as a known exception: on R8i, `OPTIONAL_RETRY` `contains`
  takes 1.115 times the ordinary engine's time, 58.2 ns against 52.2 ns, in one
  of two identical builds, and 1.031 times, 53.8 ns, in the other. It exceeds
  R8i's A/A spread over rows with one mode, 8.2%, but not its spread over all
  rows, 17.8%. The Java plan also takes 1.027 times there, and on R9g the row
  takes 0.934 times. Adding Rust plans, with the matcher check that sends a
  Rust partial region to the Rust search, moved no other language's row beyond
  noise: against the build before Rust plans, 71 rows of the scan-plan
  benchmarks take a geometric mean of 1.002 and 0.997 times their earlier
  time, and none is slower beyond the spread between identical builds. Those
  rows are the `BenchmarkTrinoScanPlan` workloads `URL`, `ANCHORED_HOST`,
  `OPTIONALS`, `OPTIONAL_RETRY`, `RETRY_64`, and `MIXED`; the
  `BenchmarkRe2ScanPlan` workloads `URL`, `ANCHORED_HOST`, `OPTIONALS`,
  `OPTIONAL_RETRY`, `RETRY_64`, `LONG_HOST`, `MIXED`, and `URL_MALFORMED`; and
  the `BenchmarkJavaScanPlan` workloads `URL`, `ANCHORED_HOST`, `OPTIONALS`,
  `LONG_HOST`, and `URL_MALFORMED`.
- A set of some non-ASCII characters reads a character by its lead byte's table
  entry. The entry of a lead byte whose every encoding is a member, as most
  leads of Java's default dot are, holds the range of its second byte, and the
  remaining bytes need only be continuation bytes; other leads walk the trie.
  The reader takes the width from the lead byte, so finding the next character
  does not wait on the table read. Walking the trie for every lead made Java's
  default dot slower than the ordinary engine: on R9g and R8i,
  `BenchmarkJavaScanPlan` `URL_NON_ASCII` with `javaLineTerminators=true` took
  1.31 and 1.23 times the ordinary engine's time for `contains` and 1.32 and
  1.24 for `count`. With full lead entries and the lead-byte width, those rows
  take 0.85 and 0.98 for `contains` and 0.91 and 1.08 for `count`, where 1.08 is
  within noise, its forks bimodal. Over the measurement's 43 other rows, on
  Java, Rust, and RE2 workloads with these sets, the reader takes a geometric
  mean of 0.907 and 0.946 times the trie walk's time. With the trie walk, the
  255 rows that plan only with these sets took a geometric mean of 0.161 and
  0.138 times the ordinary engine's time. Compiling such a plan takes 1.6 to 1.9
  times the ordinary engine's compilation, against 1.7 to 2.4 with the trie walk
  in the same measurement; this cost is accepted. Two R8i rows, measured only
  with the trie walk, took slightly longer than the ordinary engine, and both
  are accepted: Java `OPTIONALS` `count` with `javaLineTerminators=true` took
  1.046 times the ordinary engine's time, 68.3 ns against 65.3 ns, and RE2
  `DEEP_REJECTIONS` `extractAll` 1.08 times, 300 ns against 278 ns, within the
  error of either measurement.
- Taking the width from the lead byte rather than the table slows two R8i
  workloads relative to full lead entries alone; these losses are accepted
  because both remain faster than the ordinary engine. Java
  `UNANCHORED_TAIL_4096_NON_ASCII` `boundaries`, `count`, `extract`, and
  `extractAll` take 1.31 to 1.37 times their time with full lead entries alone,
  5.1 to 5.3 us against 3.8 to 4.0 us, where the trie walk took 6.0 to 6.1 us
  and the ordinary engine takes 19 to 39 us. Rust `PERL_UNANCHORED`
  `boundaries` with Unicode `\d` takes 43.6 ns, against 40.9 ns with full lead
  entries alone, 41.3 ns with the trie walk, and 84 ns with the ordinary
  engine.
- The lead-byte width pays off where a run reads many multibyte members whose
  lead byte is not a full entry. In a development probe of Rust's `\d(\d*)zz`,
  an unanchored search led by one Unicode digit, over runs of two-byte
  Arabic-Indic digits, the plan took 1.03 to 1.20 times the ordinary engine's
  time at 1 and 4 KiB and 1.22 to 1.80 at 64 bytes on R9g and R8i, with the
  trie walk for every lead or with full lead entries alone. With the lead-byte
  width it takes 0.32 to 0.45 and 0.57 to 0.99.
- Alternatives rejected for this reader:
  - A reader for sets of every character except a few code points still took
    1.08 to 1.10 times the ordinary engine's time on R9g `URL_NON_ASCII`
    `contains` and `count` and 1.21 to 1.22 on R8i, and made R9g Java
    `LONG_HOST_NON_ASCII` `extract` and `extractAll` take 1.53 to 1.54 times
    the trie walk's time.
  - Merging the interpreter's `RE2_RUN` and `RE2_SET_RUN` arms turned R9g Java
    `URL_MALFORMED` `contains` from 0.94 to 1.03 times the ordinary engine's
    time. It targeted R9g Java `URL_NON_ASCII` `extract` without
    `javaLineTerminators`, an existing plan that seemed 1.13 to 1.19 times
    slower with the second arm; over 8 forks per build the means are equal,
    both builds spreading across several C2 register-allocation modes.

## Generated Trino Scan Kernels

`TrinoRegexp.Options.setGenerateBytecode` is an opt-in, disabled
by default, that compiles the case-sensitive scan plan of an eligible pattern
into its own hidden class. The class holds the plan's attempt loop as
straight-line code, with literals, their lengths, and branch targets as
constants; a run step passes its run to a copied run scanner that reads the
run's bounds and members. The class also holds the candidate search of an
unanchored plan, with its limits as constants, and a public entry for each
Trino operation that reaches the plan. A hot pattern therefore shares neither
the interpreter's operation dispatch nor its profiles with other plans. Its
results, retries, captures, and search handoffs are those of the interpreter,
which differential tests check operation by operation.

- An eligible pattern compiles to its generated class, which extends a
  package-private non-sealed `TrinoRegexp` subclass, as the RE2 class extends
  `CompiledScanRe2`, a subclass of its
  [scan-plan shape](#generated-re2-scan-kernels). Each operation keeps the
  route it has without the option, fixed when the class is generated: an
  operation that would run the case-sensitive plan runs the generated class,
  and one that would run other Java, such as a retained literal kernel for
  `contains`, still runs that Java. Short boolean calls that the interpreter
  hands to the ordinary engine still run the generated class, which has no
  operation dispatch for that route to avoid.
- A pattern is eligible when it would compile to the base `TrinoRegexp` class
  with a case-sensitive plan that every operation but `contains` reaches, and
  cannot match empty, which no pattern with a plan can: each generated match
  loop resumes its search at the previous match end. Any other pattern,
  including one with an ASCII-folded plan, compiles exactly as it does without
  the option.
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
- The pattern a caller holds is the generated class, and each operation that
  reaches the plan is the class's own public entry, with the argument checks of
  the public method. A caller holding a constant pattern binds the public
  method to the pattern, and the call reaches that pattern's entry directly,
  with no dispatch, where C2 can inline it. An earlier design kept a private
  subclass that called the generated class through an interface held in a
  field; HotSpot does not fold final fields of ordinary classes, so that call
  shared one profile across patterns, went megamorphic with more than two of
  them, and never inlined, and public `generated*` accessors returned the
  entries as method handles to work around it.
- Per-pattern state an entry reads on its hot path is a class-data constant of
  the generated class, never a field of the hand-written base, for the same
  reason. Base fields are read only on cold paths, such as the ordinary-engine
  handoff, and for the replacement-template cache, which is mutable.
- Each public entry stays within HotSpot's 325-byte inline size, calling the
  class's own static attempt and search methods, which may be too large to
  inline but are direct calls.
- `TrinoRegexpMatcher` is sealed rather than final, so the matcher of a
  compiled pattern can run its generated search through a package-private
  hook of the generated class. Callers still cannot extend it.
- The class is defined in Regulator's package through a hidden-class lookup,
  so it reaches package-private helpers without widening the public API, and
  becomes unreachable with its pattern.
- The class is not charged to the pattern's memory budget. That budget bounds
  retained heap, and the class lives in metaspace and the code cache, which the
  heap estimate cannot measure. A caller compiling many distinct patterns with
  the option bounds that cost by bounding the patterns it retains.
- There is no size guard. A method longer than HotSpot's 8,000-byte
  huge-method limit is never JIT-compiled, but the plan's operation and
  optional limits hold the attempt, the only generated method that grows with
  the plan, far below it, in this class and in the RE2, Java, and Rust classes.
  `TestScanKernel` pins the largest plans of each operation kind below the
  limit.

## Generated RE2 Scan Kernels

`Re2.Options.setGenerateBytecode` is an opt-in, disabled by
default, that compiles the case-sensitive scan plan of an RE2 pattern with the
generator of [Generated Trino Scan Kernels](#generated-trino-scan-kernels),
under its rules: generation happens once at compilation, a pattern whose class
cannot be generated or defined is silently interpreted, nothing generated
loads without the option, generated code calls its own copies of the
interpreter's helpers, per-pattern state on an entry's hot path is class data,
each public entry stays within HotSpot's inline size, and the class is defined
in Regulator's package and is not charged to the memory budget. RE2 runs and delimited captures call copies
of the RE2 run reader and its vector stop scan, and runs of some non-ASCII
characters also call a copy of their member reader, so a generated plan
reports what the ordinary engine reports on every input, malformed UTF-8
included.
`JavaRegexp.Options.setGenerateBytecode` and
`RustRegexp.Options.setGenerateBytecode` apply these decisions to Java and Rust
plans; the last bullets record the rules and measurements specific to each.

- The class declares the plan's attempt method, an unanchored plan's search
  methods, and full-Slice `find`, `count`, `matches`, and `lookingAt` entries.
  The attempt and search methods return the plan's results. Each entry exists
  only when that operation reaches the plan, and its route is fixed when the
  class is generated: the attempt, the search, or the capture-free search before
  the ordinary-engine handoff, and for count the search loop or the single
  attempt. An operation answered without the plan, such as a `find` or `matches`
  on a retained literal kernel, keeps the entry of the scan-plan shape the class
  extends. Other operations that reach the plan go through the overridden
  `matchInternal` hook, whose anchored or unanchored arm is also fixed at
  generation, so `Re2`'s many public operations keep their routes without a
  generated entry each; a matcher's start-anchored first search uses the
  overridden `findWithStartAnchoredScanPlan` hook instead. A shared entry method
  carries the branch profiles of every pattern that calls it; an entry per
  generated class carries one pattern's.
- `Re2`, `JavaRegexp`, and `RustRegexp` are sealed classes over a
  package-private engine. Each permits its package-private [shape
  classes](#boolean-partial-match-specializations). The scan-plan shape is
  sealed too and permits only its package-private `CompiledScan…` class, which
  is non-sealed so that the hidden class extends it. The pattern a caller holds
  is the generated class, and a caller's call site that sees one pattern binds
  its entries directly; callers still cannot extend the pattern classes. The
  generated class adds no fields, so it keeps the scan-plan shape's 112-byte
  layout.
- Any pattern with a case-sensitive plan is eligible; an ASCII-folded plan
  stays interpreted, as in Trino. Unlike Trino, a count that skips the plan
  does not exclude a pattern, because only the calls that reach the plan
  change. Route selection gives no pattern with a plan a single-byte table.
- Short boolean calls that the interpreter hands to the DFA stay on the
  generated class, as in Trino. Boolean calls of a plan run its full plan
  rather than the save-free boolean plan: a capture save without groups is one
  null check in generated code, not an operation dispatch.
- No language exposes generated method handles; only
  `usesGeneratedBytecode()` is public. A caller holding a constant pattern
  binds the public method to the pattern, which reaches the generated entry.
- With other generated patterns in the process, a generated class takes 0.81
  times the time of the earlier executor design, in which a private `Re2`
  subclass held the generated executor in a field and Java and Rust patterns
  wrapped an `Re2`. That is the geometric mean of `find`, `count`, and
  `matches` over the three scan-plan targets of `BenchmarkPatternDispatch` in
  RE2, Java, and Rust on R9g short rows, with the other 13 patterns trained
  first; R8i takes 0.83. Part of the gain is the generated `matches` entry,
  which also speeds up a single pattern. With generation off, the geometric
  means of the same benchmark and of the Trino and public API controls stay
  within 0.97 to 1.01 of the earlier design.
- The pooled measurements below are of the earlier executor design. On the
  two exception workloads, the per-pattern classes changed no row: they take
  0.99 to 1.01 times the earlier design's time, generated or not.
- A generated plan must not be slower than the interpreted plan. Measured on
  a build that looked the boolean plan up on every call, the matching rows of
  `BenchmarkRe2ScanPlan` with `generateBytecode=true` on R9g, R8g, and R8i take
  a geometric mean of 0.60, 0.58, and 0.60 times the interpreted plan's time
  and 0.12, 0.12, and 0.13 times the ordinary engine's. The geometric means
  pool 114 rows on R9g and 96 on R8g and R8i, whose runs dropped most
  non-ASCII and malformed workloads and the `RETRY_128` and `RETRY_256`
  workloads, and exclude compilation and the ASCII-folded `FOLDED_MIXED`
  control. No matching row is slower than the ordinary engine.
- One workload fails that bar and is accepted as a known exception. On R9g,
  `UNANCHORED_TAIL_4096_NON_ASCII` `replace` takes 1.03 to 1.04 times the
  interpreted time, about 3.0 us against 2.9 us, in both of two replicas, but
  within the error of either; the earlier design took 1.04 to 1.10 times. On
  R8i it takes 0.93 to 0.96 times. R8i `LONG_HOST`, the other recorded
  exception, no longer reproduces: every method, with and without dotAll,
  takes 0.89 to 0.97 times the interpreted time, on the earlier design and on
  the per-pattern classes.
- Compilation is reported separately from matching. With generation it takes
  0.44 to 0.70 ms per pattern on R9g and 0.69 to 1.55 ms on R8g and R8i,
  against 3.6 to 16 us for the interpreted plan, and up to about 353 times the
  ordinary engine's compilation, on R8i `UNANCHORED_TAIL_4096`. With the option
  off, sealing `Re2` changed no row on any host.
- `JavaRegexp.Options.setGenerateBytecode` gives Java patterns the same
  generated classes under the same rules. The Java end adds a generated end
  check, and a Java-only rule on which runs may precede it decides whether a
  plan exists; once a plan exists, the decision whether to generate it and the
  routes are RE2's, and the generated class extends `CompiledScanJavaRegexp`,
  which extends `JavaRegexp`'s scan-plan shape. On R9g and R8i, the 65 matching
  rows of `BenchmarkJavaScanPlan` with `generateBytecode=true`, on the `URL`,
  `URL_SHORT`, `ANCHORED_HOST`, `ANCHORED_QUERY`, `UNANCHORED_URL`,
  `UNANCHORED_TAIL_4096`, `OPTIONALS`, `OPTIONAL_RETRY`, `OPTIONAL_MIXED`,
  `RETRY_64`, `LONG_HOST`, `MIXED`, and `URL_MALFORMED` workloads, take a
  geometric mean of 0.51 and 0.56 times the interpreted plan's time and 0.12 and
  0.13 times the ordinary engine's, excluding compilation and the `FOLDED_MIXED`
  control. No generated row is slower than the interpreted plan or the ordinary
  engine, and R8i `LONG_HOST`, RE2's exception, is within noise for Java.
  Compilation with generation takes 0.44 to 0.71 ms per pattern on R9g and 1.0
  to 1.6 ms on R8i, against 3.9 to 15.1 us for the interpreted plan. Over the 50
  Java end rows, generated classes take 0.489 and 0.507 times the interpreted
  plan's time and 0.064 and 0.057 times the ordinary engine's; no row is slower
  than the interpreted plan, and R8i `LONG_HOST` is within noise. Their
  compilation takes 0.47 to 0.69 ms per pattern on R9g and 1.11 to 1.52 ms on
  R8i.
- `RustRegexp.Options.setGenerateBytecode` gives Rust patterns the same
  generated classes under the same rules. A Rust plan is an RE2 plan, so the
  emitter, eligibility, and routes are RE2's, and the generated class extends
  `CompiledScanRustRegexp`, which extends `RustRegexp`'s scan-plan shape. A
  generated `find` skips the Rust search's rule for empty matches, so generation
  refuses a pattern that can match empty, which no plan accepts either.
  Byte-mode patterns build plans and generated classes under the same rules. On
  R9g and R8i, the 45 matching rows of `BenchmarkRustScanPlan` with
  `generateBytecode=true`, on the `URL`, `ANCHORED_HOST`, `UNANCHORED_URL`,
  `UNANCHORED_TAIL_4096`, `OPTIONALS`, `OPTIONAL_RETRY`, `LONG_HOST`,
  `URL_MALFORMED`, and `UNANCHORED_URL_MALFORMED` workloads, take a geometric
  mean of 0.536 and 0.597 times the interpreted plan's time and 0.113 and 0.123
  times the ordinary engine's, excluding compilation. Over the 40 of those rows
  that the Java measurement shares, Rust classes take 0.535 and 0.604 times and
  Java classes took 0.530 and 0.606. No generated row is slower than the
  interpreted plan or the ordinary engine beyond noise, and R8i `LONG_HOST`,
  RE2's exception, is within noise for Rust. Compilation with generation takes
  453 to 715 us per pattern on R9g and 1024 to 1602 us on R8i, against 4 to 13
  us and 5 to 16 us for the interpreted plan and 3 to 11 us and 4 to 12 us for
  the ordinary engine.
- Generated classes read a set of some non-ASCII characters with a copy of the
  interpreter's reader, full lead entries and lead-byte width included. With
  the trie walk for every lead, generated Java `URL_NON_ASCII` `contains` and
  `count` with `javaLineTerminators=true` took 0.93 to 0.94 times the ordinary
  engine's time on R9g and 1.08 to 1.09 on R8i; with full lead entries and the
  lead-byte width they take 0.60 and 0.85 to 0.87. With the trie walk, the
  generated classes of the plans that need these sets took a geometric mean of
  0.648 and 0.687 times the interpreted plan's time over 205 rows, and over the
  43 other rows of the reader measurement in [RE2 Scan Plans](#re2-scan-plans),
  the reader takes 0.892 and 0.949 times the trie walk's generated time. On
  R8i, Rust `PERL_UNANCHORED` `contains` and `count` with Unicode `\d` take
  1.06 to 1.07 times their time with full lead entries alone, 22.7 ns against
  21.4 ns for `contains`, still 0.42 to 0.51 times the ordinary engine's; this
  loss is accepted. The reader does not change compilation with generation.

## Generated Trino LIKE Kernels

`TrinoLikePattern.Options.setGenerateBytecode` is an opt-in,
disabled by default, that compiles a LIKE pattern into its own hidden class
with the same rules as generated Trino scan kernels: generation happens once
at compilation, a pattern whose class cannot be generated or defined is
silently interpreted, the interpreter never references generated code,
generated code calls its own copies of the interpreter's helpers, per-pattern
state on the entry's hot path belongs to the generated class rather than the
hand-written base, and the entry stays within HotSpot's inline size. Every plan
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
- Each search of a literal of two or more bytes splits that loop as the
  interpreter's searches do: a call-free word scan finds the first candidate
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
- `TrinoLikePattern` is sealed rather than final, and permits a
  package-private non-sealed subclass that the generated class extends, so the
  pattern a caller holds is the generated class and its public `matches` is
  the class's own entry, as for [Trino scan kernels](#generated-trino-scan-kernels).
  A caller holding a constant pattern binds `matches` to it. Callers still
  cannot extend it.
- Unlike the regular-expression classes, a LIKE class has no empty-match
  refusal: its only entry is a full match, so `%`, `%%`, and the empty pattern
  have classes.
