# Trino regular-expression language

**Compiler:** `TrinoRegexp.compile(pattern)`

`TrinoRegexp` supports the regular subset of Trino's regular-expression
language. Trino uses a fork of Joni's Java-syntax language, so its syntax and
behavior differ from both stock Joni and Java `Pattern`. Where Trino's pinned
Joni, `io.airlift:joni:2.1.5.3`, and modern Joni disagree, each behavior is
judged a bug or intended Trino behavior. `TrinoRegexp` follows the rule modern
Joni and Oniguruma settled on in place of a bug, keeps intended behavior, and
this reference notes each call. A defect in how pinned Joni compiles or
searches a pattern is never copied, whether or not modern Joni fixed it.

Patterns, inputs, and text results are UTF-8 `Slice` values. Matcher `start` and
`end` return zero-based byte offsets relative to the logical Slice, with an
exclusive end. The SQL-style `position()` operation instead returns a one-based
code-point position, or `-1` when no match is found; its `start` argument also
uses one-based code-point positions. As in Trino, a search that starts past the
last code point, as every search of an empty source does, finds nothing, even
where the pattern matches empty text, while an earlier start can still find an
empty match at the end, so `$` on `a` from start 1 returns `2`.

As in Trino's Joni implementation, inputs are not validated as UTF-8, and
malformed input is garbage in, garbage out. A pattern must be valid UTF-8: one
that is not throws `RegexpParseException` with error code `BAD_UTF8` at its
first invalid byte. This differs from Trino, which accepts such a pattern and
compares its malformed bytes byte-for-byte.

```java
TrinoRegexp regexp = TrinoRegexp.compile(Slices.utf8Slice("([a-z]+)=([0-9]+)"));
Slice input = Slices.utf8Slice("key=42");
boolean containsMatch = regexp.contains(input);
Slice value = regexp.extract(input, 2);
```

For example, searching for `x` in `😀x` returns `2` from `position()`, while
the matcher's `start()` returns byte offset `4`.

`TrinoRegexp.parse(pattern)` checks a pattern and reads its capture metadata
without compiling it; see
[pattern validation and capture metadata](../REGULATOR_API.md#pattern-validation-and-capture-metadata).

## Supported constructs

| Category | Constructs |
|---|---|
| Atoms | Literals, `.`, quoted literals, and character classes |
| Composition | Concatenation and ordered alternation with `|` |
| Repetition | Greedy and reluctant `*`, `+`, `?`, `{n}`, `{n,}`, and `{n,m}`, including stacked quantifiers such as `a{2}{3}` |
| Groups | Numbered captures, noncapturing groups, `(?<name>...)`, and `(?'name'...)` |
| Anchors | `^`, `$`, `\A`, and `\z` |
| Boundaries | Trino-compatible Unicode `\b` and `\B` |
| Character classes | Ranges, negation, union, intersection, subtraction, ASCII predefined classes, and Unicode POSIX classes |
| Unicode properties | Categories, scripts, blocks, binary properties, ages, and `java...` properties from the pinned Unicode 18.0 database, using pinned jcodings' names |
| Escapes | Trino quoting and escape forms that identify Unicode code points |
| Inline flags | `i`, `m`, `s`, and `x`, including scoped forms |
| Comments | `(?#...)` comment groups, and whitespace and `#` comments in extended mode |

As in Joni, a flag written alone, such as `(?i)`, which Oniguruma calls an
isolated option, opens a group that runs to the end of its enclosing group or of
the pattern. A later `|` separates alternatives inside that group, so the text
before the flag is required in each: `ab(?i)c|def` means `ab(?i:c|def)` and
matches `abDEF` but not `def`. Java's `Pattern` instead reads it as
`ab(?i:c)|(?i:def)`.

As in Joni, a quantifier can follow another and repeats the repetition before
it, so `a{2}{3}` matches six `a`s; directly nested `?`, `*`, and `+` are then
reduced as described under
[Other matching differences from Joni](#other-matching-differences-from-joni).
A `+` written directly after a quantifier that is not reluctant makes it
possessive, which is unsupported, so `a*+` and `a{2}+` are rejected; after a
reluctant quantifier it is another quantifier, so `a*?+` is `(?:a*?)+`.

A comment group, `(?#...)`, ends at the first `)` not escaped by a backslash.
As in Joni, it is ignored wherever it appears outside a class or a quote, like
whitespace in extended mode, so a quantifier after it applies to the atom
before it: `a(?#note)*` is `a*`.

Repetition counts are limited to 1,000, and nested or stacked counts multiply
toward the limit, so `a{50}{50}` and `(?:a{50}){50}` are rejected though pinned
Joni accepts them. The default `$` assertion matches at the end of input or
before one final LF, and multiline mode recognizes LF as the line terminator.
Multiline `^` matches at the start of input and after an internal LF, but not
after a terminal LF; it also matches at the start of empty input.
Case-insensitive matching includes Unicode multi-code-point folds such as `ß`
and `SS`.

### Unicode data and case folding

Unicode property membership comes from Regulator's pinned Unicode 18.0
database, case-insensitive matching uses its full case folding,
`CaseFolding.txt` statuses C and F, and word boundaries use its word
characters, so all three are the same on every JVM. A property is the
database's values, apart from the POSIX names described below and the
`java...` names; a `java...` name is the JDK's computation over the database.
Word boundaries also count `²`, `³`, `¹`, `¼`, `½`, and `¾` as word characters,
though `\p{Word}` does not contain them, as every Joni and Oniguruma does
through a shared Latin-1 character table.

Property names are pinned jcodings' names, built from the database by the
rule of jcodings' table generator: each Unicode category, script, or binary
property name or alias, or block name, lowercased without spaces, hyphens, or
underscores, such as `UppercaseLetter`, `Qaac`, `WSpace`, and `AHex`. The
binary property `Bidi_Mirrored`, which jcodings never reads, has no name. A
block takes the `In` prefix, such as `InLatin1Supplement`, and `InNoBlock`
holds the code points of no block. `age=1.1` holds the code points assigned in
Unicode 1.1 or earlier; `Any`, `Assigned`, and `Newline` complete upstream
jcodings' names. Trino's fork of jcodings adds `all`, `L1`, `LD`,
`perlHorizontalWhiteSpace`, `perlVerticalWhiteSpace`, the earlier block names
`InGreek`, `InCyrillicSupplementary`, and `InCombiningMarksForSymbols`, and the
18 `java...` names
([airlift/jcodings e81c0a3](https://github.com/airlift/jcodings/commit/e81c0a3cc942bee1c8e4c07127b6b7f50d6c3b05)).
A name matches in any ASCII casing; a name with a character outside ASCII,
or any other spelling, such as `InLatin-1Supplement`, `Old_Italic`, `IsGreek`,
or `script=Greek`, names no property. Names Unicode added after pinned
jcodings' 6.1 tables, such as `Emoji`, `Adlam`, and `age=7.0`, follow the same
rule, and `TrinoRegexp` does not copy the 40-character name limit of Trino's
fork of jcodings, which rejects `InUnifiedCanadianAboriginalSyllabicsExtended`.
Modern Joni also ignores spaces, `-`, and `_` in a name; `TrinoRegexp` keeps
pinned Joni's spelling as intended behavior.

The POSIX names, such as `\p{Alpha}` and `[[:alpha:]]`, use the Unicode
definitions of Trino's pinned Joni over the same database. `\p{...}` takes these
names in any ASCII casing, and a bracket only lowercase names. Trino's pinned
Joni omits the nine ASCII symbols ``$+<=>^`|~`` from `[[:punct:]]`, while modern
Joni, from 2.1.14, includes them. That omission is a bug, so `[[:punct:]]`
matches `$`, unlike Trino. Modern Joni, from 2.2.7, adds Join_Control to
`\p{Word}` and `[[:word:]]`, while Trino's pinned Joni and Oniguruma's `doc/RE`
leave it out. `TrinoRegexp` keeps the pinned definition as intended behavior,
so they do not match U+200C. `\p{HexDigit}` is Unicode's Hex_Digit, without the
decimal digits Java's `\p{HexDigit}` adds, so it does not match `٠` U+0660, and
xdigit is ASCII `[0-9A-Fa-f]`.

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

Trino's pinned Joni instead bundles fixed fold tables built from Unicode 4.1
data through jcodings, so mappings added or changed in Unicode since then differ
between Regulator and Joni, in both directions. For example,
Regulator's `(?i:ss)` matches capital `ẞ`, while pinned Joni does not. The
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

Literal characters written next to each other under the same
case-insensitivity form one run, which full case folding matches as one
sequence, including through escapes, quotes, extended-mode whitespace and
comments, comment groups, an exact repeat of one, such as `{1}`, `{1,1}`, or
`{1}?`, and flag changes other than `i`. A noncapturing group without a
quantifier does not end a run; its contents act as if written in its place.
Any other construct ends the run, as does any change of `i`. For example,
Regulator's `(?i:s(?:s))` matches `ß` and `(?i:f(?s)f)` matches `ﬀ`, while
pinned Joni does not, and `(?i)ff{0}f` does not match `ﬀ` in either.

Regulator does not follow these results of pinned Joni's tokenization:

- separate strings for escapes and quotes
- a limit on case-fold expansion, under which `(?i:sß)` rejects `ßs` but
  `(?i)xyzsß` accepts `xyzßs`
- the rewrite of exact repeats, under which `(?i)ß{2}` matches `sßs`
- the split of strings of mixed UTF-8 width, under which `(?i)aaİ` does not
  match `aaİ`

These differences do not come from Unicode versions.

For greedy unbounded repetition of a nullable expression, matching stops when
the selected iteration succeeds without consuming input. Directly nested common
quantifiers follow modern Joni's reductions, including its handling of mixed
greedy and reluctant repetition. Trino's pinned Joni also reduces `(?:x+?)+` to
`x+?` and `(?:x+?)*` to `(?:x+?)?`, which change what the pattern matches, and
modern Joni and Oniguruma removed both as bugs, so `(?:a+?)+` matches all of
`aa`, while in Trino it matches each `a` separately.

Loops whose bodies can match empty input can also differ when captures affect
Joni's backtracking decisions. Joni retains capture histories and can revisit
an earlier iteration to extend the match. Regulator tracks input progress with
bounded state instead of retaining that history. For example,
`(?:(a??)|b)*a` on `baa` produces successive group-zero matches `ba` and `a` in
Regulator, while Joni produces one `baa` match. The syntax is supported; this is
a documented Joni incompatibility, not a compile-time rejection. A specialized
capture-aware engine is intentionally deferred unless practical Trino workloads
require exact Joni behavior for this form.

Modern Joni's Java syntax changed three behaviors that Trino's pinned Joni
and Java's `Pattern` share. They are intended Trino behavior, so `TrinoRegexp`
keeps them: `\d`, `\s`, and `\w` are ASCII, while modern Joni makes them
Unicode; braced `\x{41}` is `A`, while modern Joni no longer reads the braced
form; and outside a class `\pL` is the letter property, while modern Joni
reads it as `p` followed by `L`.

As in Trino, unknown alphabetic escapes are interpreted as literals. For
example, `\C` matches the literal `C`; it is not RE2's one-byte atom. A `\x`
with neither braces nor hex digits matches U+0000, as in Joni, unless it ends
the pattern, where it is the literal letter; a malformed braced `\x{...}` is
rejected. A bare `\u` at the end of the pattern is the letter `u`; any other
`\u` needs four hex digits, as in modern Joni and Oniguruma, so `\u12` and
`[\u]` are rejected. Trino's pinned Joni differs: it reads `\u12` as U+0012 and
`[\u]` as U+0000. `\c` keeps bits 7 and 0-4 (mask `0x9F`) of the code point
after it, as in Joni, so `\ca` matches U+0001, `\cé` matches U+0089, and `\c?`
matches DEL; an escaped character such as `\n` after it is decoded first.
Outside a character class, a digit escape is a backreference, which is
unsupported, when its number is at most 9 or at most the number of groups before
it, as in Joni. A number too large for Joni's integer arithmetic is not a
backreference, as in Oniguruma; both Jonis wrap it, so in Trino `(a)\8589934593`
matches `aa`. Otherwise, and always inside a class, it is an octal escape or the
digit `8` or `9`, so `\18` matches U+0001 then `8`, `(a)\10` matches U+0008, and
`[\8]` matches `8`. An octal escape above `\377` is rejected, as in modern Joni;
Trino's pinned Joni wraps it, so `\400` matches U+0000. Inside a class, `\p` or
`\P` without braces is the literal letter, so `[\pL]` matches `p` or `L`; use
`[\p{L}]` for letters.

Inside a character class, a dash after a range is a literal, as in modern Joni
and Oniguruma, even when nested classes come between them, so `[a-zA-Z0-9-_]`
and `[a-c[x]-z]` match `-`. A second dash, unless it ends the class or an
intersection operand, makes that literal the start of a range, so `[a-c--x]`
matches `-` through `x`. A dash after a class escape, property, or POSIX class,
or after a nested class that follows a single character or one of those, must
end the class or an intersection operand, as in `[\d-]`. Trino's pinned Joni
differs: it drops a dash after a range unless the dash ends the class or an
intersection operand, or another dash follows, so in Trino `[a-zA-Z0-9-_]` does
not match `-`. It also reads the `[` in `[a-c-[x]]` as a literal, so that class
matches `[`, and it fails on `[a-c-\d]`.

Regulator does not copy these defects of how pinned Joni compiles and searches a
pattern. Each departs from what the pattern says. A link names the commit that
introduced the defect, a modern Joni or Oniguruma commit that fixed it, or a
report of it:

- After a greedy quantifier or a brace-less `\pL`, a braced property in a class
  is misread as brace-less, so `a?[\p{Greek}]` is rejected. The lexer keeps the
  quantifier's flag in the field that Trino's fork added for brace-less `\pL`
  ([airlift/joni 807c625](https://github.com/airlift/joni/commit/807c62538b6b3d4b18d3f7b7edcae509cd109fc6));
  the misreading is accidental, not Trino's intent.
- An empty group repeated exactly 2 through 100 times, such as `(?:){2}`,
  fails with an internal error.
- A reluctant `{0}?` runs its body when what follows fails, so `a{0}?$` matches
  `a`, and a reluctant counted repeat of an empty capture ends the loop around
  it, so `(?:(){2}?.)*` matches `ab` one character at a time (Oniguruma
  [2b725c1](https://github.com/kkos/oniguruma/commit/2b725c132854cd2e253321bd1bfc3dbac5647589)
  and
  [ca64663](https://github.com/kkos/oniguruma/commit/ca64663ca8bb34ca7dc219d18ec6e475cca9dec8)).
- A body that can match empty, repeated with a count of two or more, gives
  results that depend on how Joni compiles the repeat: `(\p{L}?){2}.` on `a`
  reports group 1 ending before it starts, `(?:\P{Sm}+x^|\S|\A){3}` matches only
  empty text in `0`, and `(?:(?:\As*){2,}){2,}s` matches `s` in `ss`, though
  the same pattern with the outer repeat written out twice matches `ss`.
- Directly after a greedy `.*` or `.+`, `\b` and `\B` read a stale previous
  character, so `.*\b` matches all of `a!` and `.+\B` matches `a b` (Joni
  [e97e5bf](https://github.com/jruby/joni/commit/e97e5bf0bac9664d93fc13ac6c0d382e84cfab9c),
  [Onigmo #96](https://github.com/k-takata/Onigmo/issues/96)).
- A class that holds a negated class can test a non-ASCII character by its
  first byte alone, so `[[^\P{ASCII}]]` matches `é`, `[,-ω&&[^c-ß²-𐐀]]`
  matches the Ohm sign U+2126, and `[^[^[:^ascii:]]]` rejects `é` (Oniguruma
  [ce189d1](https://github.com/kkos/oniguruma/commit/ce189d190417b783ba0e249eca7cc1875c012861)
  fixed the first two).
- An expression that can match empty text misses its empty match before `$` or
  `\z` at the end of an input whose last character is multibyte and longer in
  bytes than the expression can match, so `q?$` does not match `Ω` (Oniguruma
  [b77e5f3](https://github.com/kkos/oniguruma/commit/b77e5f34d3640ae46cc63b71f08aafe5362c7092),
  [Ruby #8210](https://bugs.ruby-lang.org/issues/8210)).
- A dotall `.*` that only anchors and boundaries precede makes Joni try only the
  search start, so `(?s)$.*` does not match `ab`.
- In pinned jcodings, `Olck`, `Sundanese`, and `Saurashtra` have the internal
  numbers pinned Joni uses for ASCII `\s`, `\d`, and `\w`, so `\p{Olck}`
  matches only ASCII whitespace, `\p{Sundanese}` only ASCII digits, and
  `\p{Saurashtra}` only ASCII word characters, none of their script's
  characters. The other spellings `OlChiki`, `Sund`, and `Saur` do not collide
  (Joni
  [416f5f9](https://github.com/jruby/joni/commit/416f5f9bcd8ce9482648702fbe428aa5ae0b51e6)).

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
[scan plan](../../benchmarks/SCAN_PLAN.md) when the pattern selects one,
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
- malformed POSIX classes such as `[[:a:b:]]`
- repetitions above 1,000, and nested or stacked repetitions whose counts
  multiply past 1,000

Duplicate or digit-leading capture names are invalid patterns rather than
unsupported features.

Compilation throws `RegexpParseException` with an error code, a feature-specific
message when the construct is recognized, and a byte offset when known. There is
no fallback to `Re2.compile` or another language.

Supported capture-sensitive nullable loops have the bounded behavior described
above; they are not included in this unsupported-feature list.

See [Unsupported Regular-Expression Features](UNSUPPORTED_FEATURES.md) for the
design reasons and future-support policy.
