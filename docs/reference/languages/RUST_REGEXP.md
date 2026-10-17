# Rust regular-expression language

**Compiler:** `RustRegexp.compile(pattern)`

`RustRegexp` implements Rust's `regex::Regex` pattern language over UTF-8
`Slice` values, subject to the Unicode and resource limits below. Rust regex
1.13.1 is the syntax and matching reference. This frontend does not implement
`regex::bytes::Regex` or Rust replacement-string syntax.

```java
RustRegexp regexp = RustRegexp.compile(
        Slices.utf8Slice("(?<key>[a-z]+)=(?<value>\\w+)"));
RustRegexpMatcher matcher = regexp.matcher(Slices.utf8Slice("name=Renée"));
if (matcher.find()) {
    Slice value = matcher.group("value");
}
```

## Matching API

The compiled-pattern operations follow `JavaRegexp`: boolean `find`,
`lookingAt`, and `matches`; `count`; caller-owned capture arrays through
`findInto`, `lookingAtInto`, and `matchesInto`; immutable `MatchResult` values;
and reusable matchers with optional retained capture prefixes.

Positions are byte offsets relative to the logical input Slice. Captures are
zero-copy views. Compiled-pattern range overloads search `[start, end)` while
retaining the complete Slice as context for anchors and word boundaries. In
contrast, `RustRegexpMatcher.reset(input, start, end)` makes the region itself
the assertion context. Matcher regions therefore differ from Rust's `find_at`
API, which retains context preceding the start offset.

`RustRegexpMatcher.find()` suppresses an empty match at the end of the preceding
match, as Rust's `find_iter` does. For example, `a*` on `a` yields only `a`;
an empty pattern on `é` yields empty matches at byte offsets 0 and 2.
`count` uses the same iteration rule.

Inputs and regions must be valid UTF-8. The compiled-pattern methods validate
input before searching, and a reusable matcher validates on construction and
reset. This scan is part of the operation's cost. Keep matcher inputs valid
while a matcher retains them, including when mutating their backing storage.
Malformed patterns throw `RegexpParseException`; malformed inputs throw
`IllegalArgumentException`.

## Syntax

- Literals, concatenation, ordered alternation, and greedy/lazy repetitions.
- Numbered captures, `(?P<name>...)`, `(?<name>...)`, and noncapturing groups.
- Flags `i`, `m`, `s`, `R`, `U`, `u`, and `x`, including scoped and disabling forms.
- Nested character classes, union, intersection `&&`, difference `--`, and
  symmetric difference `~~`, with Rust's precedence and case-folding rules.
- Unicode `\d`, `\s`, and `\w` by default; ASCII forms when Unicode is disabled.
- ASCII/POSIX classes such as `[[:alpha:]]`.
- Strict end-of-input `$`, multiline anchors, and CRLF mode.
- Unicode and ASCII `\b`, `\B`, `\<`, `\>`, `\b{start}`, `\b{end}`,
  `\b{start-half}`, and `\b{end-half}`.
- Hexadecimal escapes `\x`, `\u`, and `\U`, including braced forms.
- Rust capture-name rules and duplicate-name rejection.

Disabling Unicode does not enable matching arbitrary invalid UTF-8 bytes.
Byte classes that could match non-ASCII bytes are rejected, following
`regex::Regex`. Unicode literals outside character classes remain UTF-8
literals. Lookaround, backreferences, `\Q...\E`, and Java-specific escapes are
not Rust syntax and are rejected.

## Options and limits

`RustRegexp.Options.defaults()` supplies mutable, chainable options:

- `setCaseInsensitive`
- `setMultiline`
- `setDotMatchesNewline`
- `setCrlf`
- `setSwapGreed`
- `setUnicode` (enabled by default)
- `setIgnoreWhitespace`
- `setOctal` (disabled by default)
- `setNestLimit` (0–250; default 250)
- `setMaxMemory` (Regulator's compilation and DFA-cache budget)

Compilation copies pattern bytes and snapshots options. Compiled patterns are
thread-safe; matchers and options are not.

Counted repetitions retain Regulator's limit of 1,000, including its nested
expansion check. Group/class nesting is limited to 250 and can be reduced.
These are Regulator resource limits, not Rust crate defaults for every resource.

## Unicode policy and deferred properties

Character membership and simple case folding come from the running JVM,
consistent with the other Regulator frontends. Results can therefore differ
from Rust's bundled Unicode version. Property aliases use Rust's spellings and
loose matching; the alias resource contains names only, not character tables.

Supported properties include general categories (except the nonscalar surrogate
category, which Rust rejects), scripts, `Any`, `ASCII`, `Assigned`, and the
JVM-backed binary properties listed below:

- `Alphabetic`, `Lowercase`, `Uppercase`, and `Cased`
- `White_Space`, `Join_Control`, `Hex_Digit`, and `ASCII_Hex_Digit`
- `Ideographic`, `Bidi_Mirrored`, and `Noncharacter_Code_Point`
- `Emoji`, `Emoji_Component`, `Emoji_Modifier`, `Emoji_Modifier_Base`,
  `Emoji_Presentation`, and `Extended_Pictographic`

Properties requiring additional Unicode data are deferred. This includes
`Age`, `Script_Extensions`, `Grapheme_Cluster_Break`, `Word_Break`,
`Sentence_Break`, and binary properties without a JVM-backed implementation.
Recognized deferred properties fail compilation with `UNSUPPORTED_CONSTRUCT`
and the property name. Unknown names remain `BAD_CHAR_CLASS` errors. There is
no fallback to another language or approximate interpretation.

Later support can accept those previously rejected patterns without changing
supported patterns or the public API. Applications that deliberately use
compilation failure to select a fallback may take a different path after that
upgrade. Unicode updates to the executing JVM can independently affect existing
patterns, as with the other frontends.
