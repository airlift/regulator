# Regulator Java API

Regulator has a separate compiler for each pattern language:

- `Re2` compiles RE2 syntax.
- `TrinoRegexp` compiles the documented Trino regular-expression language and
  provides Trino's count, position, extraction, split, and replacement
  operations.
- `JavaRegexp` compiles the documented regular subset of Java `Pattern`.
- `TrinoLikePattern` compiles SQL LIKE patterns.

For multi-pattern matching, use `Re2Set` or `FilteredRe2`.

Choose the compiler for your pattern's language. Compiling successfully in two
languages does not mean a pattern behaves the same way in both. The
[language overview](../integrations/REGEXP_LANGUAGES.md) links to each compiler's
compatibility contract.

The three regex compilers share Regulator's bounded matching engines. LIKE has
its own wildcard matcher. Their compiled plans can also share specialized
literal and literal-gap kernels where the languages behave the same way.

Patterns, input, captures, and transformed text use `Slice`. Boolean, count,
position, and pattern-ID operations return primitive values or arrays. Decoding
bytes into strings is always the caller's choice.

Regulator requires Java 25 or newer. Releases are tested on Java 25 and the
current feature release used by Trino, including short-term-support releases.
Unicode properties and case behavior follow the supported JVM executing the
application.

## Input model

There are no `String` or `CharSequence` overloads. Encoding a Java string as
UTF-8 can allocate and scan the entire value. Byte offsets also differ from
Java's UTF-16 indexes. Keeping conversion explicit makes those costs and units
visible, especially in row-processing loops.

Callers that start with Java strings must perform the conversion explicitly,
preferably once at the boundary where the data becomes byte-oriented:

```java
Slice patternBytes = Slices.utf8Slice(patternString);
Slice inputBytes = Slices.utf8Slice(inputString);
Re2 pattern = Re2.compile(patternBytes);
```

Callers that already store UTF-8 in `Slice` can match without converting or
copying the input. Compilation copies pattern bytes so later caller mutation
cannot change the compiled pattern. Match groups are zero-copy Slice views and
therefore retain the input's backing storage.

The Trino and Java compilers take the same Slice inputs:

```java
TrinoRegexp trinoPattern = TrinoRegexp.compile(patternBytes);
JavaRegexp javaPattern = JavaRegexp.compile(patternBytes);
```

## Compiled patterns

Compile a pattern once and share the resulting `Re2` across threads:

```java
Re2 pattern = Re2.compile(Slices.utf8Slice("(?<key>[a-z]+)=(?<value>[0-9]+)"));
```

Compilation copies the pattern bytes and snapshots the mutable `Re2.Options`.
The default options use UTF-8, Perl-like RE2 syntax, leftmost-first matching, and
a 96 MiB compiled-program and DFA-cache budget. A non-positive public memory
budget is rejected. The budget does not include caller-owned input, result
objects, matcher capture buffers, replacement output, or collections returned
by aggregate APIs.

Invalid syntax throws `RegexpParseException`. Compilation resource failures
throw `RegexpCompileException`; `RegexpCompileMemoryLimitException` identifies a
configured memory-budget failure rather than JVM heap exhaustion.

Compiled patterns are immutable and safe to share across threads. They do not
need to be closed. Lazily built DFA caches and optional native transition
storage belong to the compiled pattern and are reclaimed when it is no longer
reachable. Native access is optional; when it is unavailable, the ordinary
on-heap DFA remains fully functional without a warning or initialization
failure.

## Runtime acceleration

Regulator works without special JVM flags. Two optional flags enable faster
paths without changing results:

```text
--add-modules jdk.incubator.vector
--enable-native-access=ALL-UNNAMED
```

Resolving `jdk.incubator.vector` enables vectorized candidate scanning for
eligible long searches. Without it, the same searches use scalar or SWAR
scanners.

Native access lets eligible forward DFA searches use a small internal
native-memory allocation containing DFA transition entries. Each entry is eight
bytes. Its initial row-rounded capacity is based on the DFA graph already built;
it grows geometrically when useful, remains charged to the existing DFA budget,
and is reclaimed with the compiled pattern.

This uses native memory, not native code, a JNI library, or a file. The API
never exposes native pointers. Patterns, inputs, captures, and results remain
Java and Slice data. Without native access, the DFA uses its pure-Java
transition table without a warning or initialization failure.

The native table avoids following Java object references for rows and decoding
compressed references on each transition. This mainly helps DFA-heavy
operations. The
[benchmark report](https://airlift.github.io/regulator/benchmarks/) compares
native and pure-Java execution on every target architecture.

These options may be enabled independently. They change execution strategy, not
pattern syntax, matching semantics, capture boundaries, or failure behavior.

## Matching APIs

Call matching methods on the compiled pattern, passing the input to search:

```java
Slice input = Slices.utf8Slice("user=alice key=42");

if (pattern.find(input)) {
    // The compiled pattern matched somewhere in input.
}
```

Choose a method based on the result you need. These are instance methods on
the compiled `pattern`:

| Requirement | API | Capture allocation |
|---|---|---|
| Match anywhere | `pattern.find(input)` | None |
| Match at the beginning | `pattern.lookingAt(input)` | None |
| Match complete input | `pattern.matches(input)` | None |
| Repeated matching | `pattern.matcher(input)` | One reusable buffer and workspaces |
| Caller-owned captures | `pattern.findInto(input, groups)` | None |
| Immutable result | `pattern.findResult(input)` | One capture array |

The `lookingAtInto`, `matchesInto`, `lookingAtResult`, and `matchesResult`
methods provide the corresponding beginning-of-input and complete-input forms.
The same boolean, caller-buffer, result, range, and reusable-matcher forms are
available from `JavaRegexp`.

Use `MatchResult` when capture values are needed without repeated matching:

```java
MatchResult result = pattern.findResult(input);
if (result != null) {
    Slice key = result.groupSlice("key");
    Slice value = result.groupSlice("value");
}
```

Use `Re2Matcher` to iterate over non-overlapping matches while reusing its
capture storage and execution workspaces:

```java
Re2Matcher matcher = pattern.matcher(input);
while (matcher.find()) {
    Slice key = matcher.group("key");
    Slice value = matcher.group("value");
}
```

Caller-owned capture arrays contain byte-offset pairs:

```java
int[] groups = new int[2 * (pattern.capturingGroupCount() + 1)];
if (pattern.findInto(input, groups)) {
    int matchStart = groups[0];
    int matchEnd = groups[1];
}
```

```text
[group0Start, group0End, group1Start, group1End, ...]
```

The array length must be even and include group zero. A `null` array requests a
boolean-only match. Groups that did not participate, and captures beyond the
pattern's group count, are written as `-1, -1`.

Range overloads such as `pattern.find(input, start, end)` search `[start, end)`
but retain the complete Slice as context for anchors and boundary assertions.
Their offsets remain relative to the complete logical Slice. Named range
overloads are available for boolean, caller-buffer, and immutable-result
matching. In contrast,
`matcher.reset(input, start, end)` makes the region itself the assertion
context, matching a logical Slice view without allocating one on DFA-only paths.

`Re2Matcher` is mutable and not thread-safe. Its capture buffer and execution
workspaces are reused across `find`, `matches`, `lookingAt`, and `reset` calls.
`reset(input, start, end)` restricts matching without constructing a Slice view.
All exposed offsets remain relative to the complete logical input Slice, not the
region and never the backing array.

`MatchResult` is an immutable snapshot. Group Slices are zero-copy views and
therefore retain the input's backing storage. An unmatched group returns `null`
from group access and `-1` from boundary and length access. Numeric group parsing
throws `NumberFormatException` for unmatched, malformed, or out-of-range values.

## Relationship to Java regex

Like `java.util.regex`, Regulator separates the compiled pattern from mutable
matcher state. `find`, `lookingAt`, and `matches` have the same high-level
meanings. Share a compiled `Re2` across threads, but keep a separate
`Re2Matcher` for each thread.

The APIs are familiar, but their representations and capabilities differ:

| Concern | `java.util.regex` | This API |
|---|---|---|
| Pattern and input representation | `String` pattern and `CharSequence` input | `Slice` pattern and input |
| Position unit | UTF-16 code-unit offset | Byte offset relative to the logical Slice |
| Captures | `String` values from a mutable `Matcher` or `MatchResult` | Zero-copy Slice views, mutable matcher state, immutable result, or caller-owned `int[]` bounds |
| One-shot matching | Create a matcher, then call `find`, `lookingAt`, or `matches` | The same matcher form plus direct methods on the compiled `Re2` |
| Repeated matching | Mutable `Matcher` | Mutable `Re2Matcher` with reusable captures and execution workspaces |
| Replacement grammar | `$1`, `${name}`, and backslash escaping | Native RE2 grammar in `Re2`; Trino's Java-style grammar in `TrinoRegexp`; no replacement API in `JavaRegexp` |
| Convenience adapters | Splits, streams, predicates, and `CharSequence` regions | Allocation-conscious Slice matching, caller-owned capture buffers, multi-pattern matching, and Trino operations |
| Worst-case execution | Ordered backtracking can take exponential time | RE2 execution uses bounded memory and linear-time algorithms |

### Pattern languages

`Re2`, `TrinoRegexp`, and `JavaRegexp` are separate compilers for different
pattern languages. They use the same matching engine after compilation.

Java `(?U)` and character-class intersection are examples of syntax whose
meaning depends on the language. Consult the
[RE2](languages/RE2.md), [Trino regexp](languages/TRINO_REGEXP.md),
[Java regexp](languages/JAVA_REGEXP.md), and
[Trino LIKE](languages/TRINO_LIKE.md) references for exact boundaries.

`JavaRegexp.Options` exposes the supported Java compile flags without requiring
an integer flag mask:

```java
JavaRegexp javaPattern = JavaRegexp.compile(
        patternBytes,
        JavaRegexp.Options.defaults()
                .setCaseInsensitive(true)
                .setUnicodeCase(true)
                .setMultiline(true));
```

The options also cover Unix lines, comments, literal patterns, dot-all, and
Unicode character classes. Compilation copies the pattern and snapshots the
mutable options. Inline Java flags can still enable or disable the corresponding
mode for the complete pattern or a scoped group.

## Rewrites

Call `pattern.replaceFirst(input, rewrite)`, `pattern.replaceAll(input, rewrite)`,
or `pattern.extract(input, rewrite)` to apply native RE2 rewrite syntax:
`\0` through `\9` reference groups and `\\` writes a backslash. Invalid syntax
or an unavailable capture reference throws `RegexpRewriteException`. No match is
not an error: `replaceFirst` returns the original Slice, `replaceAll` reports zero
replacements, and `extract` returns `null`.

This grammar is intentionally separate from `TrinoRegexp`, which implements
Trino's Java-style `$1` and `${name}` replacement syntax.

## Multi-pattern matching

Multi-pattern matching is useful when the same input must be checked against a
collection of rules, signatures, or filters. `Re2Set` compiles the patterns into
one program and reports which pattern IDs matched, avoiding a separate regular
expression search for every pattern.

`Re2Set.Builder` assigns stable integer pattern IDs and produces an immutable
many-match DFA:

```java
Re2Set.Builder builder = Re2Set.builder(Re2.Options.defaults(), Re2Set.MatchMode.FIND);
int errorId = builder.add(Slices.utf8Slice("error"));
int warningId = builder.add(Slices.utf8Slice("warning"));
Re2Set patterns = builder.build();

int[] matches = patterns.matchingPatternIds(input);
```

`matchesAny` is allocation-free after the DFA is warm. `matchingPatternIds`
allocates a primitive result array whose ordering is unspecified. If the
bounded many-match DFA exhausts the configured memory budget, either operation
throws `RegexpMatchMemoryLimitException` instead of reporting no match;
`matchingPatternIds` does not expose partial IDs.

`FilteredRe2` is for much larger pattern collections paired with an external
atom scanner. A collection has one encoding; its builder rejects patterns with
mixed UTF-8 and Latin-1 options. The builder returns the distinct canonical
atoms through `atoms()`. The caller must scan `filtered.canonicalizeText(input)`
for those atoms, then pass the found atom IDs to `potentialPatternIds`,
`matchingPatternIds`, or `firstMatchingPatternId`. RE2 verifies every candidate
accepted by the prefilter.

## Trino SQL LIKE

`TrinoLikePattern` compiles Trino SQL LIKE syntax independently from RE2
regular-expression syntax. See the
[Trino SQL LIKE language reference](languages/TRINO_LIKE.md) for the complete
contract.

```java
TrinoLikePattern pattern = TrinoLikePattern.compile(Slices.utf8Slice("%error_%"));
boolean matched = pattern.matches(input);

TrinoLikePattern escaped = TrinoLikePattern.compile(
        Slices.utf8Slice("100\\%"),
        '\\');
```

`matches` always applies to the complete input. `%` matches zero or more
Unicode code points, `_` matches exactly one code point, and the optional escape
may quote `%`, `_`, or itself. Redundant wildcard runs are normalized during
compilation. Invalid escape syntax throws `TrinoLikePatternSyntaxException`,
whose `byteOffset()` is relative to the logical pattern Slice.

The compiled pattern is immutable and safe to share across threads. This API
has no captures, partial-input matching, match-result object, replacement
grammar, or conversion to the RE2 language.

## Trino adapter

Use `TrinoRegexp` for Trino-style contains, count, position, extraction, split,
and replacement operations. These follow Trino's semantics, not the general
`Re2` matching and rewrite methods. Integration into Trino still requires SQL
function registration, block construction, cancellation, and error translation
in the Trino repository.

`TrinoRegexp.matcher(input)` provides reusable match and capture iteration
without building extraction lists or replacement output. The returned
`TrinoRegexpMatcher` uses zero-based Slice-relative byte offsets, exclusive end
offsets, and zero-copy groups.
An overload accepts the number of explicit groups to retain, with zero meaning
whole-match boundaries only. See the language reference for reset, ownership,
and empty-match behavior. The iterator has its own execution path; higher-level
operations still use their operation-specific plans.

`TrinoRegexp.position(input)` returns the one-based code-point position of the
first match, or `-1` when no match is found. Its overloads take a one-based
code-point `start` and a one-based match `occurrence`. These are not matcher byte
offsets: searching for `x` in `😀x` returns `2` from `position()`, while the
matcher's `start()` returns `4`.

Trino's pattern language is a fork of Joni's Java-syntax language, with
Trino-specific syntax and behavior. It is not identical to stock Joni or the
JDK language, and SQL string-literal escaping is not Java source-string
escaping. `TrinoRegexp` implements Trino's SQL operation semantics and
regular-expression language. The exact accepted and rejected language is
documented in the
[Trino regular-expression language reference](languages/TRINO_REGEXP.md).

Trino replacement strings use `$1` and `${name}` references. Invalid
replacement syntax or an unknown capture throws
`TrinoRegexpReplacementException`, whose `byteOffset()` identifies the failing
byte in the replacement Slice.
