# Java regular-expression language

**Compiler:** `JavaRegexp.compile(pattern)`

`JavaRegexp` supports the regular subset of the current JDK release's
`java.util.regex.Pattern` language. It is not a drop-in replacement for
`Pattern`. Patterns and inputs are UTF-8 `Slice` values, and positions are byte
offsets rather than UTF-16 indexes.

Inputs are not validated as UTF-8. The JDK assigns no meaning to malformed
UTF-8, so malformed input is garbage in, garbage out: Regulator makes no promise
about how a Java pattern matches it. A pattern must be valid UTF-8: one that is
not throws `RegexpParseException` with error code `BAD_UTF8` at its first
invalid byte.

```java
JavaRegexp regexp = JavaRegexp.compile(
        Slices.utf8Slice("error: ([a-z]+)"),
        JavaRegexp.Options.defaults().setCaseInsensitive(true));
RegexpMatcher matcher = regexp.matcher(Slices.utf8Slice("ERROR: timeout"));
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

## Matching

A matcher region set with `matcher.reset(input, start, end)` behaves like a JDK
`Matcher.region` with default bounds: anchors and boundary assertions see only
the region. As in the JDK, `$` and `\Z` still read the CR before the region,
so outside Unix-lines mode (`(?d)`) they do not match at the start of a
nonempty region that starts on the LF of a CR LF.

A range overload such as `regexp.find(input, start, end)` searches
`[start, end)` but keeps the whole Slice as assertion context, so it behaves
like a JDK region with transparent, non-anchoring bounds. `^` does not match at
the range start unless it is the input start, and `\bb` does not match `b` in
the range `[1, 2)` of `ab`.

As in the JDK, `matcher.find(start)` resets the region to the whole input
before it searches from `start`. After a failed `find`, `matches`, or
`lookingAt`, the next `find()` searches again from the end of the last match,
or from the region start when nothing has matched since the last reset. The
JDK's backtracker can move that resume point inside `?`, `{n,m}`, lazy
quantifiers, and repeated groups; Regulator does not, so on `123 45 x`, after
`find(4)` fails, the next `find()` finds `123` for both `\d{3}` and `\d\d\d`,
while the JDK finds it only for `\d\d\d`. After an empty match at the region
end, `find()` and every later `find()` fail without searching, as in the JDK.
The JDK's `start()` and `end()` then still report that empty match while
`group()` returns null; Regulator throws, as after any failed `find()`.

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
| Unicode properties | Categories, scripts, blocks, binary properties, and `java...` properties from the pinned Unicode 18.0 database |
| Escapes | Quoting, hexadecimal, Unicode, octal, control, named-code-point, horizontal-whitespace, and vertical-whitespace escapes |
| Inline flags | `d`, `i`, `m`, `s`, `u`, `U`, and `x`, including scoped forms |

As in the JDK, `\Q...\E` quotes are rewritten into escapes before the pattern
is parsed, without regard to character classes or comments. An unfinished
escape before an empty quote therefore reads on past it, so `\x4\Q\E1` matches
`A`, and a quote opened inside a comment escapes the text after the comment
ends. Error offsets refer to the pattern as written.

Repetition counts are limited to 1,000. The implementation preserves Java's
final-terminator behavior, multiline CRLF handling, Unix-lines mode, Unicode
case folding, and Unicode character classes.

Unicode property membership is the JDK's computation over Regulator's pinned
Unicode 18.0 database, so it is the same on every JVM. Unicode case folding
uses Java's rule, which puts code points whose upper case has the same lower
case in one class, applied to the database's simple case mappings. Word
boundaries read the database's word characters, nonspacing marks, letters, and
digits by the same computations. Named code points `\N{name}` accept the names
of `Character.codePointOf`, built from the database's character names and
blocks.

On a JVM with an older Unicode version, characters that Unicode added since
then, and the case partners they joined, have properties, folds, and word
boundaries in Regulator but not in `Pattern`, and names that Unicode added
since then, such as the script `Seal` and the names of new characters in
`\N{name}`, are accepted only by Regulator. On Java 25 three more results
differ from `Pattern`. Unicode 17.0 changed two for older characters: U+0295 is
`Lo` rather than lowercase, and 689 code points that are not emoji, such as
U+2605 BLACK STAR, are not `\p{IsExtended_Pictographic}`. And JDK 25's
`Pattern` does not match `ß` against `ẞ` case-insensitively, which JDK 26 does.

Regulator's supported property names and case-insensitive matching can differ
from `Pattern` independently of Unicode version, as
[`UNICODE.md`](../../integrations/UNICODE.md#differences-from-the-jdk) says.

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

`setByteFrequencies` chooses which literal byte an unanchored search scans for;
it affects speed, not results. See
[Byte frequencies](../REGULATOR_API.md#byte-frequencies).
`setGenerateBytecode` is experimental and compiles an eligible pattern's
case-sensitive scan plan into its own class; it also affects speed, not
results. See [Generated bytecode](../REGULATOR_API.md#generated-bytecode).
Compilation copies the pattern and snapshots the option values.

`JavaRegexp.parse(pattern, options)` applies the same compile flags to check a
pattern and read its capture metadata without compiling it; see
[pattern validation and capture metadata](../REGULATOR_API.md#pattern-validation-and-capture-metadata).

## Unsupported features

The following valid Java constructs are currently rejected or have no compile
option:

- lookahead and lookbehind
- numeric and named backreferences
- atomic groups and possessive quantifiers
- the previous-match boundary `\G`
- the linebreak escape `\R`
- lone surrogate escapes such as `\uD800`, `\x{DC00}`, or `\N{HIGH SURROGATES D800}`
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
