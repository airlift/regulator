# Regular expression languages

Choose a compiler based on the language your pattern was written for.
Regulator does not guess the language or retry rejected syntax with another
parser.

| Compiler | Language reference |
|---|---|
| `Re2.compile(pattern)` | [RE2 Pattern Language](../reference/languages/RE2.md) |
| `TrinoRegexp.compile(pattern)` | [Trino Regular-Expression Language](../reference/languages/TRINO_REGEXP.md) |
| `JavaRegexp.compile(pattern)` | [Java Regular-Expression Language](../reference/languages/JAVA_REGEXP.md) |
| `TrinoLikePattern.compile(pattern, escape)` | [Trino SQL LIKE Language](../reference/languages/TRINO_LIKE.md) |

See [unsupported features](../reference/languages/UNSUPPORTED_FEATURES.md) for
the reasons behind the limits and what adding support would require.

## Slice input model

All patterns and inputs are `Slice` values. There are no `String` overloads.
Converting a string scans and allocates, and Java's UTF-16 indexes differ from
Regulator's UTF-8 byte offsets. Convert explicitly if your data starts as strings:

```java
Slice patternBytes = Slices.utf8Slice(patternString);
Slice inputBytes = Slices.utf8Slice(inputString);

JavaRegexp pattern = JavaRegexp.compile(patternBytes);
MatchResult result = pattern.findResult(inputBytes);
```

## Compilation and execution

Each compiler handles its own language's syntax and semantics. `Re2`,
`TrinoRegexp`, and `JavaRegexp` produce shared Regulator programs that run on
the same bounded DFA, NFA, OnePass, and BitState engines. `TrinoRegexp` can also
choose a specialized matcher for an operation when pattern analysis proves it
will produce the same result.

`TrinoLikePattern` parses LIKE directly and has its own general wildcard
matcher. It does not translate LIKE into regex text. LIKE and regex plans can
still share literal, ordered-literal, and literal-plus-gap kernels where their
semantics agree. Sharing those kernels does not mix the languages. See
[Trino integration](TRINO.md) for the division of responsibilities.

## Error contract

Invalid or unsupported regular-expression patterns throw
`RegexpParseException`. Its `byteOffset()` is relative to the logical pattern
Slice when the parser knows the location. `errorCode()` distinguishes invalid
syntax, invalid UTF-8, unsupported constructs, unsupported flags, and resource
limits.

When a valid language feature is outside that compiler's documented language,
the exception message names the feature, for example `lookahead`,
`backreferences`, or `possessive quantifiers`. Invalid syntax continues to use
the corresponding syntax error instead of being mislabeled as unsupported.

There is no automatic fallback between `JavaRegexp`, `TrinoRegexp`, and `Re2`.
