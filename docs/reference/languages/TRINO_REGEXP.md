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

As in Trino's Joni implementation, inputs are not validated as UTF-8. A
pattern must be valid UTF-8: one that is not throws `RegexpParseException` with
error code `BAD_UTF8` at its first invalid byte. This differs from Trino, which
accepts such a pattern and compares its malformed bytes byte-for-byte.

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
| Unicode properties | Categories, scripts, blocks, binary properties, and `java...` properties from the pinned Unicode 18.0 database, using Trino's accepted spellings |
| Escapes | Trino quoting and escape forms that identify Unicode code points |
| Inline flags | `i`, `m`, `s`, and `x`, including scoped forms |

Repetition counts are limited to 1,000. The default `$` assertion matches at
the end of input or before one final LF, and multiline mode recognizes LF as
the line terminator. Multiline `^` matches at the start of input and after an
internal LF, but not after a terminal LF; it also matches at the start of empty
input. Case-insensitive matching includes Unicode multi-code-point folds such
as `ß` and `SS`.

### Unicode data and case folding

Unicode property membership uses the JDK's formulas over Regulator's pinned
Unicode 18.0 database, case-insensitive matching uses its full case folding,
`CaseFolding.txt` statuses C and F, and word boundaries use its word
characters, so all three are the same on every JVM. Property membership agrees
with Java's `Pattern` except where Unicode changed since the JDK's Unicode
version, as
[`UNICODE.md`](../../integrations/UNICODE.md#differences-from-the-jdk) lists;
the accepted property spellings keep Regulator's existing rules, which are not
identical to `Pattern`'s.

Full case folding keeps dotted capital `İ` and dotless small `ı` apart from
`I` and `i` outside its Turkic mappings, and so does Joni: `(?i)i` matches
neither `İ` nor `ı`, `(?i)ı` matches only `ı`, and `(?i)İ` matches `i` followed
by a combining dot above.

As in Joni, a case-insensitive bracket class is folded once, after its set
operations, and class and property escapes outside brackets are not folded:
`(?i)\p{Lu}` does not match `a`, while `(?i)[\p{Lu}]` does, and
`(?i)[a-z&&[B]]` matches neither `b` nor `B`. A negated class is folded before
its complement, so `(?i)[^\P{Lu}]` matches neither `a` nor `A`.

A positive case-insensitive class also matches every whole-character spelling
of a member's multi-character fold: `(?i)[ﬃ]` matches `ffi`, `ﬀi`, and `fﬁ`.
The spelling is matched inside the class and never merges with adjacent pattern
literals, so `(?i)[ß]s` does not match `sß`. A negated class consumes one
character.

Folds that Unicode 18.0 adds include U+1DF95, which folds to `ss` with `ß` and
`ẞ`, and new case pairs such as U+0277 and U+A7DD.

Trino's pinned Joni dependency, `io.airlift:joni:2.1.5.3`, instead bundles fixed
fold tables built from Unicode 4.1 data through jcodings, so mappings added or
changed in Unicode since then differ between Regulator and Joni, in both
directions. For example,
Regulator's `(?i:ss)` matches capital `ẞ`, while that Joni version does not. The
`ẞ` to `ss` full fold is defined in
[Unicode 5.1](https://www.unicode.org/Public/5.1.0/ucd/CaseFolding.txt), released
in 2008; it is not a Regulator-specific equivalence. In the other direction,
Joni's `(?i)ʔ` matches capital glottal stop `Ɂ` and Regulator's does not,
because Unicode 5.0 paired `Ɂ` with the then-new small `ɂ` instead of `ʔ`.

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
literal `C`; it is not RE2's one-byte atom. Inside a character class, `\p` or
`\P` without braces is the literal letter, so `[\pL]` matches `p` or `L`; use
`[\p{L}]` for letters.

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
Match iteration uses the existing regex execution machinery, the Trino
[scan plan](../../benchmarks/TRINO_SCAN_PLAN.md) when the pattern selects one,
and the literal-alternation matcher where applicable; it does not require the
same specialized route as each higher-level Trino operation.

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
