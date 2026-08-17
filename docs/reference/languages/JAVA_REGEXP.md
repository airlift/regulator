# Java regular-expression language

**Compiler:** `JavaRegexp.compile(pattern)`

`JavaRegexp` supports the regular subset of Java 25's
`java.util.regex.Pattern` language. It is not a drop-in replacement for
`Pattern`. Patterns and inputs are UTF-8 `Slice` values, and positions are byte
offsets rather than UTF-16 indexes.

```java
JavaRegexp regexp = JavaRegexp.compile(
        Slices.utf8Slice("error: ([a-z]+)"),
        JavaRegexp.Options.defaults().setCaseInsensitive(true));
Re2Matcher matcher = regexp.matcher(Slices.utf8Slice("ERROR: timeout"));
if (matcher.find()) {
    Slice reason = matcher.group(1);
}
```

Use `regexp.find(input)` to check for a match, and `regexp.count(input)` to
count non-overlapping matches. Counting includes empty matches and
uses the same UTF-8 code-point advancement as this library's `matcher.find()`.
These operations do not retrieve capture values. Use a matcher when boundaries
or captures are required.

Counting is not always allocation-free. The first call may initialize cached
tables, and patterns without a specialized counting path create a matcher and
workspace on each call. Iterating with a matcher can cost less for short inputs
or one-off counts; reusing that matcher can also avoid workspace allocations.
Compare the complete operation on your workload.

## Supported constructs

| Category | Constructs |
|---|---|
| Atoms | Literals, `.`, quoted literals, and character classes |
| Composition | Concatenation and ordered alternation with `|` |
| Repetition | Greedy and reluctant `*`, `+`, `?`, `{n}`, `{n,}`, and `{n,m}` |
| Groups | Numbered captures, `(?<name>...)`, and noncapturing groups |
| Anchors | `^`, `$`, `\A`, `\Z`, and `\z`, including Java final-terminator and multiline behavior |
| Boundaries | Combining-aware `\b` and `\B` in ASCII and Unicode-character-class modes |
| Character classes | Union, nested intersection, subtraction, negation, quoted atoms, ranges, and Java's empty intersection operands |
| Predefined classes | ASCII and Unicode forms of digit, whitespace, word, horizontal-whitespace, vertical-whitespace, and POSIX classes |
| Unicode properties | JVM-derived categories, scripts, blocks, binary properties, named code points, and `java...` properties |
| Escapes | Quoting, hexadecimal, Unicode, octal, control, named-code-point, horizontal-whitespace, and vertical-whitespace escapes |
| Inline flags | `d`, `i`, `m`, `s`, `u`, `U`, and `x`, including scoped forms |

Repetition counts are limited to 1,000. The implementation preserves Java's
final-terminator behavior, multiline CRLF handling, Unix-lines mode, Unicode
case folding, and Unicode character classes.

`find`, `lookingAt`, and `matches` have the same high-level meaning as their JDK
counterparts. Captures are returned as zero-copy Slice views.

## Compilation options

`JavaRegexp.Options.defaults()` creates chainable options corresponding to the
supported Java compile flags:

- Unix lines
- case-insensitive matching
- comments
- multiline matching
- literal patterns
- dot-all
- Unicode case
- Unicode character classes

Compilation copies the pattern and snapshots the option values.

## Unsupported features

The following valid Java constructs are currently rejected or have no compile
option:

- lookahead and lookbehind
- numeric and named backreferences
- atomic groups and possessive quantifiers
- the previous-match boundary `\G`
- the linebreak escape `\R`
- canonical equivalence
- repeated captures whose body can match the empty string
- repetitions above 1,000

`\C` is not Java syntax and is reported as an invalid escape rather than an
unsupported Java feature. Invalid or duplicate group names are also syntax
errors.

Compilation throws `RegexpParseException` with an error code, a feature-specific
message when the construct is recognized, and a byte offset when known. There is
no fallback to `Re2.compile`, `TrinoRegexp`, or the JDK engine.

See [Unsupported Regular-Expression Features](UNSUPPORTED_FEATURES.md) for the
design reasons and future-support policy.
