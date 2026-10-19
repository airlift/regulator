# RE2 pattern language

**Compiler:** `Re2.compile(pattern)`

Use `Re2` for patterns written in the RE2 language. A Java or Trino pattern may
also compile, but that does not guarantee the same matching behavior.

Patterns and inputs are `Slice` values. UTF-8 mode reports byte offsets relative
to the logical Slice. Latin-1 mode treats each input byte as one character.

```java
Slice input = Slices.utf8Slice("key=42");
Re2Matcher matcher = Re2.compile(Slices.utf8Slice("(?<key>[a-z]+)=([0-9]+)"))
        .matcher(input);
while (matcher.find()) {
    Slice key = matcher.group("key");
    Slice value = matcher.group(2);
}
```

Use `pattern.find(input)` to check for a match, and `pattern.count(input)` to
count non-overlapping matches. Counting includes empty matches and
advances exactly as `matcher.find()` does: one UTF-8 code point after an empty
match, or one byte in Latin-1 mode. These operations avoid retrieving captures
and can use more specialized paths than matcher iteration.

Counting is not always allocation-free. The first call may initialize cached
tables, and patterns without a specialized counting path create a matcher and
workspace on each call. Iterating with a matcher can cost less for short inputs
or one-off counts; reusing that matcher can also avoid workspace allocations.
Compare the complete operation on your workload.

## Supported constructs

| Category | Constructs |
|---|---|
| Atoms | Literals, `.`, character classes, negated classes, and `\C` for one byte |
| Composition | Concatenation and ordered alternation with `|` |
| Repetition | `*`, `+`, `?`, `{n}`, `{n,}`, and `{n,m}`, including reluctant forms |
| Groups | Numbered captures, `(?P<name>...)`, `(?<name>...)`, and noncapturing `(?:...)` groups |
| Anchors | `^`, `$`, `\A`, `\z`, ASCII word boundary `\b`, and non-boundary `\B` |
| Character classes | Ranges, negation, ASCII POSIX classes, ASCII Perl classes, and Unicode properties from the pinned database |
| Escapes | C-style escapes, octal and hexadecimal escapes, Unicode escapes, and `\Q...\E` quoting |
| Inline flags | `i`, `m`, `s`, and `U`, including scoped forms and flag removal |

Counted repetitions reject minimum or maximum counts above 1,000. Unbounded
forms such as `x*` and `x{3,}` remain supported.

Unicode property groups are upstream's: `\pL`, `\p{Name}`, `\p{^Name}`,
`\PL`, and `\P{Name}` name a General_Category value, the union of a major
class such as `L`, a script under its long name such as `Greek`, or `Any`, with
an exact, case-sensitive match. Unassigned code points are in no group except
`Any`, so `\p{C}` is `Cc`, `Cf`, `Co`, and `Cs`. The groups hold Unicode 18.0
data from Regulator's pinned database on every JVM, where upstream's tables are
Unicode 15.1 data, so scripts and characters added since then, such as Garay,
match only in Regulator, and code points Unicode has reclassified since then,
such as U+0295 from `Ll` to `Lo` and U+1171E from `Mn` to `Mc`, are in
different groups. Case-insensitive matching uses Unicode 18.0 simple case
folding, upstream RE2's rule, on every JVM:
`(?i)k` matches the Kelvin sign, but `(?i)i` matches neither dotted capital `İ`
nor dotless small `ı`. Upstream's tables are Unicode 15.1 data, so characters
added since then fold only in Regulator. See
[`UNICODE.md`](../../integrations/UNICODE.md).

An explicit letter pair such as `[Kk]` matches only its two letters, including
inside an alternation. Regulator's `[Kk]|x` does not match the Kelvin sign;
pinned upstream RE2's alternation factoring widens the pair to include it.

## Compilation options

`Re2.Options.defaults()` selects UTF-8, Perl-like RE2 syntax, case-sensitive
leftmost-first matching, and the default memory budget.

`Re2.Options.posix()` selects POSIX syntax and leftmost-longest matching.
`Re2.Options.latin1()` selects Perl-like syntax over Latin-1 bytes.

The chainable options can also control the memory budget, syntax mode, encoding,
literal parsing, newline behavior, captures, case sensitivity, Perl classes,
word boundaries, one-line mode, and leftmost-longest matching.
`setByteFrequencies` chooses which literal byte an unanchored search scans for;
it affects speed, not results. See
[Byte frequencies](../REGULATOR_API.md#byte-frequencies). Compilation copies the
pattern and snapshots the option values.

## Unsupported features

RE2 intentionally excludes constructs such as backreferences, lookaround,
atomic groups, possessive quantifiers, recursive patterns, conditionals, and
embedded code. `\G`, `\Z`, and `\R` are also not part of the RE2 language.

See the [RE2 syntax reference](https://github.com/google/re2/wiki/Syntax) for a
complete syntax table. See
[Unsupported Regular-Expression Features](UNSUPPORTED_FEATURES.md) for why
these features are absent today and how future support would be evaluated.
