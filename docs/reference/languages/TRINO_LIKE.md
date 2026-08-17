# Trino SQL LIKE language

**Compiler:** `TrinoLikePattern.compile(pattern)`

`TrinoLikePattern` supports the complete Trino SQL LIKE language over UTF-8
`Slice` values. It parses LIKE directly, without translating it to RE2 syntax.

## Supported constructs

| Construct | Meaning |
|---|---|
| Literal code point | Matches itself |
| `%` | Matches zero or more Unicode code points |
| `_` | Matches exactly one Unicode code point |
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

The compiled pattern is immutable and safe to share across threads. Patterns
and inputs are interpreted as UTF-8 using Trino's LIKE semantics.

## Unsupported features

LIKE has no regular-expression operators. Character classes, groups,
alternation, anchors, quantifiers, and regular-expression escapes are literal
text unless they contain `%`, `_`, or the configured escape character.

For regular expressions, use `TrinoRegexp`, `JavaRegexp`, or `Re2` instead.
