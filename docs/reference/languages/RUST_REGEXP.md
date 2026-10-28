# Rust regular-expression language

**Compiler:** `RustRegexp.compile(pattern)`

`RustRegexp` implements the pattern language of the Rust `regex` crate's
`Regex` type, and in [byte mode](#byte-mode) its `regex::bytes::Regex` type,
pinned at regex 1.13.1 with regex-syntax 0.8.11 and regex-automata 0.4.18.
Results match the crate on valid UTF-8 input, and in byte mode on any input,
for every pattern both accept, except where they depend on Unicode data:
properties, the Unicode `\d`, `\s`, and `\w` classes, word boundaries, and case
folding follow Regulator's Unicode 18.0 database rather than the crate's
Unicode 16.0 tables; see [Unicode properties](#unicode-properties).
Compilation rejects every pattern the crate rejects as invalid, plus the
[unsupported features](#unsupported-features) below. Regulator can accept a
pattern the crate refuses as too large; see
[Compilation options](#compilation-options). Patterns are UTF-8 `Slice`
values. Inputs are `Slice` values, which hold UTF-8 text outside byte mode.
Positions are byte offsets, as in the crate.

```java
RustRegexp regexp = RustRegexp.compile(
        Slices.utf8Slice("(?<key>[a-z]+)=(?<value>\\w+)"));
RegexpMatcher matcher = regexp.matcher(Slices.utf8Slice("name=Renée"));
if (matcher.find()) {
    Slice value = matcher.group("value");
}
```

## Pattern validation and capture metadata

Use `RustRegexp.parse(pattern)` to check a pattern and read its capture
metadata without building a matching engine; see
[pattern validation and capture metadata](../REGULATOR_API.md#pattern-validation-and-capture-metadata).
`parse(pattern, options)` applies every [compilation option](#compilation-options)
except `setMaxMemory`, `setByteFrequencies`, and `setGenerateBytecode`, so byte
mode and the nest limit can change the result.

## Matching

The compiled-pattern operations have the same signatures as `JavaRegexp`:
boolean `find`, `lookingAt`, and `matches`; `count`; caller-owned capture
arrays through `findInto`, `lookingAtInto`, and `matchesInto`; immutable
`MatchResult` values; and reusable `RegexpMatcher` instances with an optional
retained prefix of capturing groups. Matching is leftmost-first, as in the
crate. Captures are zero-copy Slice views.

`RustRegexp` returns the shared `RegexpMatcher`, which applies the crate's
search rules to Rust patterns:

- Outside byte mode, a search never reports an empty match that splits a UTF-8
  character; it searches again one byte later. An empty pattern on `é` matches
  at byte offsets 0 and 2.
- Successive `find()` calls skip an empty match that ends where the previous
  match ended, as the crate's `find_iter` does. `a*` on `a` yields only `a`.
  `count` follows the same rules.
- Outside byte mode, `lookingAt` and `matches` anchored at an offset that
  splits a character fail, because an anchored search cannot move past it.

A range overload or a matcher region searches `[start, end)` but keeps the
whole Slice as assertion context, as the crate's `Input::range` does. `^`, `$`,
`\b`, and the other assertions see the bytes outside the range, so `\bb` does
not match `b` in the range `[1, 2)` of `ab`.

Outside byte mode, inputs are not validated, because the crate's `Regex` only
accepts valid UTF-8. Matching malformed UTF-8 input produces unspecified
results; use byte mode, or validate the input first, if it can contain
malformed bytes. Patterns must be valid UTF-8 in both modes, because the crate
parses Rust strings; an invalid pattern fails with `BAD_UTF8`.

## Byte mode

`RustRegexp.Options.bytes()`, or `setBytes(true)`, compiles the pattern as the
crate's `regex::bytes::Regex`, which matches arbitrary bytes. Results match the
crate on every input, including bytes that are not UTF-8:

- Without Unicode mode, `.`, negated classes, `\W`, `\D`, `\S`, and the escapes
  `\x80` through `\xFF` match single bytes: `(?-u:.)` matches the byte `0xFF`,
  and `(?-u:\xFF)` matches only that byte. Outside a class, a non-ASCII
  literal, a braced or Unicode escape such as `\x{E9}`, and an enabled octal
  escape keep their UTF-8 encoding. A non-ASCII character in a class and a
  Unicode property still fail.
- In Unicode mode, constructs match only valid UTF-8: `.` and `[^a]` never
  match a malformed byte, and `\xFF` is the character U+00FF.
- An empty match may split a UTF-8 character, and `lookingAt` and `matches` may
  start inside one. An empty pattern on `é` matches at byte offsets 0, 1, and
  2. Iteration still skips an empty match that ends where the previous match
  ended.
- Unicode word boundaries read the character the crate decodes on each side,
  and a side that does not decode is not a word character. `\B` requires each
  side that is present to decode, so it never matches inside a character.
  `\b{start-half}` requires the side before the position to be non-word and
  `\b{end-half}` the side after it, and each requires that side to decode if
  it is present. At the start or end of the text, the missing side counts as
  non-word. The crate decodes the character before a position from the nearest
  byte that is not a continuation byte, within the four bytes before the
  position, ignoring any stray continuation bytes after it, so in `a\x80`, `\b`
  matches at offset 2, and in `a\x80b`, `\B` matches at offset 2.

## Supported constructs

| Category | Constructs |
|---|---|
| Atoms | Literals, `.`, and character classes |
| Composition | Concatenation and ordered alternation with `|` |
| Repetition | Greedy and lazy `*`, `+`, `?`, `{n}`, `{n,}`, and `{n,m}`, with counts up to 1,000 |
| Groups | Numbered captures, `(?P<name>...)`, `(?<name>...)`, and noncapturing groups, with the crate's capture-name rules |
| Anchors | `\A`, `\z`, `^`, and `$`, with multi-line and CRLF modes |
| Boundaries | `\b`, `\B`, `\<`, `\>`, `\b{start}`, `\b{end}`, `\b{start-half}`, and `\b{end-half}`, in Unicode and ASCII forms |
| Character classes | Nested classes, ranges, negation, union, intersection `&&`, difference `--`, symmetric difference `~~`, and `[[:alpha:]]`-style ASCII classes |
| Predefined classes | Unicode `\d`, `\s`, and `\w` by default; ASCII forms when Unicode is off |
| Unicode properties | `\pL`, `\p{Greek}`, `\p{sc=Greek}`, `\p{sc:Greek}`, and `\p{sc!=Greek}`, with the crate's names and loose matching |
| Escapes | `\x`, `\u`, and `\U` in fixed and braced forms, `\a`, `\f`, `\t`, `\n`, `\r`, `\v`, escaped metacharacters, and octal escapes when enabled |
| Inline flags | `i`, `m`, `s`, `R`, `U`, `u`, and `x`, including scoped and negated forms |

Without Unicode mode, a construct that could match part of a UTF-8 character
fails with `BAD_UTF8`, as `regex::Regex` does: `(?-u:.)`, `(?-u:\xFF)`, and
negated ASCII classes. [Byte mode](#byte-mode) accepts them. Unicode literals
stay UTF-8 literals, and Unicode properties fail with `BAD_CHAR_CLASS`.

Case-insensitive matching uses the crate's rule, simple case folding
(`CaseFolding.txt` statuses C and S), from Regulator's Unicode database, the
same rule as RE2 patterns. Unicode `\d` is `Decimal_Number`, `\s` is
`White_Space`, and `\w` and Unicode word boundaries use the UTS #18 word
characters, all read from the database.

## Compilation options

`RustRegexp.Options.defaults()` creates mutable, chainable options matching the
crate's `RegexBuilder` settings, and `RustRegexp.Options.bytes()` creates them
in byte mode. Names follow `JavaRegexp.Options` where the concept is the same:

| Option | Crate setting | Default |
|---|---|---|
| `setCaseInsensitive` | `case_insensitive` | off |
| `setMultiline` | `multi_line` | off |
| `setDotMatchesNewline` | `dot_matches_new_line` | off |
| `setCrlf` | `crlf` | off |
| `setSwapGreed` | `swap_greed` | off |
| `setComments` | `ignore_whitespace` | off |
| `setUnicode` | `unicode` | on |
| `setOctal` | `octal` | off |
| `setBytes` | `regex::bytes::RegexBuilder` in place of `regex::RegexBuilder` | off |
| `setNestLimit` | `nest_limit` | 250 |
| `setMaxMemory` | none; replaces `size_limit` and `dfa_size_limit` with Regulator's compilation and DFA-cache budget | 96 MiB |
| `setByteFrequencies` | none | `ByteFrequencies.uniform()` |
| `setGenerateBytecode` | none | off |

The crate rejects a pattern whose compiled program exceeds its 10 MiB
`size_limit`, such as `\w{1000}`. Regulator does not reproduce that estimate.
It accepts such a pattern unless compiling it exceeds `maxMemory`, which throws
`RegexpCompileMemoryLimitException` rather than a parse error. A pattern the
crate refuses as too large has no crate result to differ from.

Without octal syntax, any escaped digit is a backreference and fails with
`UNSUPPORTED_CONSTRUCT`. With it, `\0` through `\777` are code points of up to
three octal digits, and `\8` and `\9` are unrecognized escapes.

The nest limit bounds the depth of groups, repetitions, alternations,
concatenations, and classes, counted the way the crate counts it, so even `ab`
needs a limit of 1. Any non-negative limit is accepted, as in the crate.

`setByteFrequencies` chooses which literal byte an unanchored search scans for;
it affects speed, not results. See
[Byte frequencies](../REGULATOR_API.md#byte-frequencies).
`setGenerateBytecode` is experimental and compiles an eligible pattern's
case-sensitive scan plan into its own class; it also affects speed, not
results. Byte-mode patterns have no scan plan, so it leaves them unchanged. See
[Generated bytecode](../REGULATOR_API.md#generated-bytecode).

Compilation copies the pattern and snapshots the option values. Compiled
patterns are thread-safe; options and matchers are not.

## Unicode properties

Every property the crate supports is supported:

- general categories, including `Any`, `ASCII`, and `Assigned`
- scripts and `Script_Extensions`
- `Age`, which, as in the crate, matches the characters assigned in the given
  version or earlier
- `Grapheme_Cluster_Break`, `Word_Break`, and `Sentence_Break`
- the binary properties of `PropList.txt`, `DerivedCoreProperties.txt`, and
  `emoji-data.txt`, and `Bidi_Mirrored`

Property and value names follow the crate's lookup order, its loose matching,
and its errors for unknown names and values. `RustUnicodePropertyTables` builds
them from the property and value aliases in Regulator's Unicode 18.0 database,
by the naming rules of the crate's table generator. Character membership comes
from the same database, as in the other languages, so it is the same on every
JVM. A value the crate names but has no characters for, such as `Surrogate` or
`Unknown`, fails with the crate's value-not-found error.

Regulator's database is Unicode 18.0 and the crate's tables are Unicode 16.0,
so where Unicode changed after 16.0, results follow Unicode 18.0, as the
crate's will once it moves to 18.0. Names Unicode added after 16.0, such as
`\p{Age=17.0}` and the script `Jurchen`, compile here and fail in the crate.
Characters Unicode assigned after 16.0, and older characters whose properties
Unicode changed, such as U+0295, which Unicode 17.0 changed from `Ll` to `Lo`,
match by their 18.0 properties, including in case-insensitive matching.

## Unsupported features

The following crate features are not offered:

- the crate's replacement syntax
- the `line_terminator` setting; `^`, `$`, and `.` use `\n`, or `\r` and `\n`
  in CRLF mode, as with the crate's default
- the `size_limit` estimate; see [Compilation options](#compilation-options)
- repetition counts above 1,000, and nested repetitions whose counts multiply
  past 1,000

See [Unsupported Regular-Expression Features](UNSUPPORTED_FEATURES.md) for the
design reasons and future-support policy.

## Errors

Compilation throws `RegexpParseException` for every pattern the crate rejects
as invalid, but not for one it refuses only as too large; see
[Compilation options](#compilation-options). Its message names the crate's
error kind, such as `UnsupportedLookAround`, its `byteOffset()` is the start of
the crate's error span, and `errorCode()` maps the kind to the closest
Regulator category: `BAD_ESCAPE` for escape errors, `MISSING_PAREN` for an
unclosed group, `UNSUPPORTED_CONSTRUCT` for lookaround and backreferences,
`PATTERN_TOO_LARGE` for the nest limit, and so on.

Regulator adds rejections of its own: repetition counts above 1,000 fail with
`REPEAT_SIZE`, as do nested repetitions whose counts multiply past 1,000, such
as `(a{40}){40}`. They are reported only after the whole pattern
translates, so any error the crate would report wins. Exceeding `maxMemory`
throws `RegexpCompileMemoryLimitException`. There is no fallback to another
language.

## Performance

Unicode `\b` and `\B`, every start, end, and half word boundary, and CRLF
multi-line `^` and `$` read the text around a position. Patterns that use them
run on the NFA and BitState engines rather than the DFA and OnePass engines.
ASCII `\b` and `\B` and `\n` multi-line anchors keep the faster engines.
