# Trino SQL LIKE language

**Compiler:** `TrinoLikePattern.compile(pattern)`

`TrinoLikePattern` supports the complete Trino SQL LIKE language over UTF-8
`Slice` values. It parses LIKE directly, without translating it to RE2 syntax.

## Supported constructs

| Construct | Meaning |
|---|---|
| Literal code point | Matches itself |
| `%` | Matches zero or more characters |
| `_` | Matches exactly one character |
| Escape character | Quotes `%`, `_`, or the escape character itself |

`matches(input)` always matches the complete input. There are no partial-match,
capture, or replacement operations.

Compile without an escape character:

```java
TrinoLikePattern pattern =
        TrinoLikePattern.compile(Slices.utf8Slice("%error_%"));
boolean matches = pattern.matches(Slices.utf8Slice("fatal error 1"));
```

Or supply one Unicode code point as the escape character:

```java
TrinoLikePattern pattern =
        TrinoLikePattern.compile(Slices.utf8Slice("100\\%"), '\\');
boolean matches = pattern.matches(Slices.utf8Slice("100%"));
```

An escape character must be followed by `%`, `_`, or itself. A trailing escape
and any other escaped code point throw `TrinoLikePatternSyntaxException`; its
`byteOffset()` is relative to the logical pattern Slice.

A pattern must be valid UTF-8. One that is not throws
`TrinoLikePatternSyntaxException` at its first invalid byte, as `TrinoRegexp`,
`JavaRegexp`, `RustRegexp`, and UTF-8 `Re2` patterns do. This differs from
Trino, which accepts such a pattern and replaces its malformed bytes.

The compiled pattern is immutable and safe to share across threads.

## UTF-8 and malformed input

A character is one Unicode code point of valid UTF-8 input. On valid input,
matching equals Trino's SQL LIKE with one exception: supplementary characters
in literals always match themselves. `%😀_%` matches `😀x`, but Trino's SQL
path compares a literal between wildcards of a pattern containing `_` as UTF-16
units, so its surrogate pair never matches a decoded code point.

Malformed input matches as if every invalid sequence had been replaced by
U+FFFD, one replacement per maximal invalid subpart as defined by the Unicode
Standard (chapter 3) and the WHATWG Encoding Standard. Each such subpart is one
character: a byte that starts no valid sequence (`80`-`C1`, `F5`-`FF`), or the
longest start of a valid sequence that a bad byte or the end of input cuts off.
`_` consumes it and `%` spans it, but it never equals a literal. For example,
`_` matches `FF` and the truncated `E2 82`, but not the overlong `C0 AF`, the
encoded surrogate `ED A0 80`, or `F4 90 80 80` above U+10FFFF, each of which is
several characters. `C2 41` is a malformed character followed by `A`.

A U+FFFD literal in a pattern matches only the bytes `EF BF BD` in the input,
not a malformed sequence.

Trino reads malformed input differently. For a pattern containing `_`, it
takes a character's width from its lead byte without checking continuation
bytes, and a byte it cannot decode fails the whole match, even inside `%`.
Regulator does not reproduce that behavior; Trino treats malformed input as
garbage in, garbage out.

## Unsupported features

LIKE has no regular-expression operators. Character classes, groups,
alternation, anchors, quantifiers, and regular-expression escapes are literal
text unless they contain `%`, `_`, or the configured escape character.

For regular expressions, use `Re2`, `TrinoRegexp`, `JavaRegexp`, or `RustRegexp`
instead.
