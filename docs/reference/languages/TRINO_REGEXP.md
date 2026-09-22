# Trino regular-expression language

**Compiler:** `TrinoRegexp.compile(pattern)`

`TrinoRegexp` supports the regular subset of Trino's regular-expression
language. Trino uses a fork of Joni's Java-syntax language, so its syntax and
behavior differ from both stock Joni and Java `Pattern`.

Patterns, inputs, and text results are UTF-8 `Slice` values. Matcher `start` and
`end` return zero-based byte offsets relative to the logical Slice, with an
exclusive end. The SQL-style `position()` operation instead returns a one-based
code-point position, or `-1` when no match is found; its `start` argument also
uses one-based code-point positions.

As in Trino's Joni implementation, neither patterns nor inputs are validated
as UTF-8. A malformed byte in literal pattern text is retained as a one-byte
literal and compared byte-for-byte.
Malformed bytes used where the parser requires syntax or Unicode text, such as
inside a capture name, remain syntax errors.

```java
TrinoRegexp regexp = TrinoRegexp.compile(Slices.utf8Slice("([a-z]+)=([0-9]+)"));
Slice input = Slices.utf8Slice("key=42");
boolean containsMatch = regexp.contains(input);
Slice value = regexp.extract(input, 2);
```

For example, searching for `x` in `😀x` returns `2` from `position()`, while
the matcher's `start()` returns byte offset `4`.

## Supported constructs

| Category | Constructs |
|---|---|
| Atoms | Literals, `.`, quoted literals, and character classes |
| Composition | Concatenation and ordered alternation with `|` |
| Repetition | Greedy and reluctant `*`, `+`, `?`, `{n}`, `{n,}`, and `{n,m}` |
| Groups | Numbered captures, noncapturing groups, `(?<name>...)`, and `(?'name'...)` |
| Anchors | `^`, `$`, `\A`, and `\z` |
| Boundaries | Trino-compatible Unicode `\b` and `\B` |
| Character classes | Ranges, negation, union, intersection, subtraction, ASCII predefined classes, and Unicode POSIX classes |
| Unicode properties | JVM-derived categories, scripts, blocks, binary properties, and `java...` properties using Trino's accepted spellings |
| Escapes | Trino quoting and escape forms that identify Unicode code points |
| Inline flags | `i`, `m`, `s`, and `x`, including scoped forms |

Repetition counts are limited to 1,000. The default `$` assertion matches at
the end of input or before one final LF, and multiline mode recognizes LF as
the line terminator. Multiline `^` matches at the start of input and after an
internal LF, but not after a terminal LF; it also matches at the start of empty
input. Case-insensitive matching includes JVM-derived multi-code-point folds
such as `ß` and `SS`.

### Unicode data and case folding

Regulator builds case-folding tables on first use from the running JVM's
character mappings and locale-independent String case conversions. The tables
follow that JVM's Unicode data, not the JDK used to compile Regulator.
Upgrading the runtime can therefore change the mappings without rebuilding
the library.

Trino's pinned Joni dependency, `io.airlift:joni:2.1.5.3`, instead bundles fixed
Unicode tables through jcodings. Those tables do not change when the JVM is
upgraded. For example, Regulator's `(?i:ss)` matches capital `ẞ`, while that
Joni version does not. The `ẞ` to `ss` full fold is defined in
[Unicode 5.1](https://www.unicode.org/Public/5.1.0/ucd/CaseFolding.txt), released
in 2008; it is not a Regulator-specific equivalence.

Regex case folding is not SQL `upper` or `lower`. Those Trino functions use
Slice case conversion, which does not perform the same multi-character
expansions. For example, Slice uppercasing leaves `ß` unchanged, while Java
String uppercasing with the root locale produces `SS`.

### Other matching differences from Joni

Regulator can merge noncapturing literal groups and apply full case folding to
the combined sequence. Joni preserves some of those boundaries. As a result,
Regulator's `(?i:s(?:s))` matches `ß`, and `(?i:sß)` matches `ßs`, while pinned
Joni does not. This difference comes from literal normalization, not Unicode
versions. These patterns remain supported with the behavior described here.
Exact Joni source-boundary behavior is deferred unless real workloads need it.

For greedy unbounded repetition of a nullable expression, matching stops when
the selected iteration succeeds without consuming input. Directly nested common
quantifiers follow Joni's reductions, including its handling of mixed greedy
and reluctant repetition.

Loops whose bodies can match empty input can also differ when captures affect
Joni's backtracking decisions. Joni retains capture histories and can revisit
an earlier iteration to extend the match. Regulator tracks input progress with
bounded state instead of retaining that history. For example,
`(?:(a??)|b)*a` on `baa` produces successive group-zero matches `ba` and `a` in
Regulator, while Joni produces one `baa` match. The syntax is supported; this is
a documented Joni incompatibility, not a compile-time rejection. A specialized
capture-aware engine is intentionally deferred unless practical Trino workloads
require exact Joni behavior for this form.

`\d`, `\s`, and `\w` use their ASCII definitions. As in Trino, unknown
alphabetic escapes are interpreted as literals. For example, `\C` matches the
literal `C`; it is not RE2's one-byte atom.

## Operations

`TrinoRegexp` provides Trino-shaped contains, count, position, extraction,
split, and replacement operations. Replacement strings use Trino's Java-style
`$1` and `${name}` references rather than RE2 rewrite syntax.

Use `matcher` to iterate matches or captures without constructing extraction
lists or replacement output:

```java
TrinoRegexpMatcher matcher = regexp.matcher(input);
while (matcher.find()) {
    int startByte = matcher.start();
    int endByte = matcher.end();
    Slice value = matcher.group(2);
}
matcher.reset(nextInput);
```

`matcher(input, 0)` retains only whole-match boundaries. A positive second
argument retains that prefix of explicit capturing groups; `groupCount()`
reports the retained count, excluding group zero. Unmatched captures have
boundaries `-1` and value `null`, distinct from an empty participating capture.
End offsets are exclusive and relative to the logical Slice. Group access
requires a successful current match and becomes invalid after reset or a failed
find. Empty matches advance by a UTF-8 code point, including one terminal empty
match where the pattern permits it.

Matchers are mutable and not thread-safe. Separate matchers can share a
compiled expression. Returned groups are zero-copy views that retain their
original input storage after reset. Do not mutate input while matching it.
Match iteration uses the existing regex execution machinery and the
literal-alternation matcher where applicable; it does not require the same
specialized route as each higher-level Trino operation.

## Unsupported features

The following features available in Java or Trino's Joni implementation are
currently rejected:

- lookahead and lookbehind
- numeric and named backreferences
- atomic groups and possessive quantifiers
- the previous-match boundary `\G`
- the final-terminator boundary `\Z`
- Java-only flags `d`, `u`, and `U`
- surrogate escapes and non-ASCII byte escapes
- empty character-class intersection operands
- character classes used as range endpoints
- repetitions above 1,000

Duplicate or digit-leading capture names are invalid patterns rather than
unsupported features.

Compilation throws `RegexpParseException` with an error code, a feature-specific
message when the construct is recognized, and a byte offset when known. There is
no fallback to `Re2.compile` or another language.

Supported capture-sensitive nullable loops have the bounded behavior described
above; they are not included in this unsupported-feature list.

See [Unsupported Regular-Expression Features](UNSUPPORTED_FEATURES.md) for the
design reasons and future-support policy.
