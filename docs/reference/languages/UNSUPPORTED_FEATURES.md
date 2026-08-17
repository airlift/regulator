# Unsupported regular-expression features

Some constructs are valid in Java, Trino, PCRE, or other regex languages but
are not supported by Regulator. This page explains the limits and what adding
support would require.

## Current execution contract

Regulator's shared engine matches in linear time with bounded memory. It avoids
the catastrophic worst cases of general ordered-backtracking engines.

`Re2`, `TrinoRegexp`, and `JavaRegexp` reject recognized unsupported constructs
at compilation. They do not retry with another language or silently switch to
an engine with different resource guarantees.

## Why features are unsupported

| Feature | Current reason |
|---|---|
| Backreferences | A match depends on text captured earlier, not only on finite automaton state. General backreferences do not fit the current linear-time engine contract. |
| Lookaround | Some restricted assertions can be supported without backtracking, but complete Java or Trino semantics require additional assertion, capture, and context handling. |
| Atomic groups and possessive quantifiers | Their behavior controls where backtracking may occur, while the current engine does not backtrack. |
| `\G` | The assertion depends on mutable state from the preceding match operation, not only on the current input position. |
| `\R` | Java requires CRLF to behave as one atomic linebreak under repetition. The current character-class representation is insufficient. |
| Nullable repeated captures | Java resets captures within repeated nullable groups according to rules not represented by the current capture program. |
| Canonical equivalence | Supporting canonical normalization equivalence requires a defined expansion, matching, and resource policy. |
| Raw byte and surrogate escapes | Java patterns represent Unicode code points, and Trino does not assign portable character semantics to these escape forms. Trino still preserves malformed bytes that occur as ordinary literal pattern text. |
| Repetition counts above 1,000 | The compiler limit bounds program expansion and follows RE2's implementation restriction. |

The limits have different causes. General backreferences conflict with the
engine model. Features such as `\R`, `\G`, restricted lookaround, and Java's
capture rules could be implemented, but require substantial implementation and
compatibility work for less commonly used cases.

## Supported syntax with a documented incompatibility

Some accepted Trino patterns have a documented result difference from Joni
rather than being rejected. In a capture-sensitive nullable loop, Joni's
backtracking engine can retain several capture histories and later reconsider a
different iteration path. Regulator stops an unbounded repetition after the
selected iteration makes no input progress and does not retain that backtracking
history.

For example, `(?:(a??)|b)*a` on `baa` produces successive group-zero matches
`ba` and `a` in Regulator, while Joni produces one `baa` match. This form remains
supported because Regulator's result is deterministic, bounded, and consistent
across its execution engines. This is a documented Joni incompatibility, not an
unsupported pattern. If real workloads require exact Joni behavior, a separate
capture-aware engine can be considered without weakening the shared linear-time
engine contract.

## Invalid syntax is different

Syntax that does not belong to a selected language is invalid, not unsupported.
For example, `\C` is an RE2 byte atom but is not a Java escape. Likewise, SQL
LIKE operators do not become regular-expression operators when passed to
`TrinoLikePattern`.

`RegexpParseException.errorCode()` distinguishes invalid syntax, invalid UTF-8,
unsupported constructs, unsupported flags, and resource limits. Recognized
unsupported constructs include a feature-specific message and a byte offset
when the parser knows it.

## How support could be added

Unsupported today does not mean ruled out permanently. A future proposal could:

1. Lower a regular subset of a construct into the existing tree and engine.
2. Add bounded assertion or capture machinery while preserving the current
   execution guarantees.
3. Introduce a separate, explicitly selected engine for patterns that require
   backtracking or different resource guarantees.

These options need a practical use case and clear correctness and performance
guarantees.

## Language references

- [RE2 Pattern Language](RE2.md)
- [Trino Regular-Expression Language](TRINO_REGEXP.md)
- [Java Regular-Expression Language](JAVA_REGEXP.md)
- [Trino SQL LIKE Language](TRINO_LIKE.md)
