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
| Character classes | Ranges, negation, ASCII POSIX classes, ASCII Perl classes, and JVM-derived Unicode properties |
| Escapes | C-style escapes, octal and hexadecimal escapes, Unicode escapes, and `\Q...\E` quoting |
| Inline flags | `i`, `m`, `s`, and `U`, including scoped forms and flag removal |

Counted repetitions reject minimum or maximum counts above 1,000. Unbounded
forms such as `x*` and `x{3,}` remain supported.

Unicode categories and scripts follow the running JVM's Unicode version,
rather than a frozen copy of upstream RE2's tables. See
[`JVM_UNICODE.md`](../../integrations/JVM_UNICODE.md).

## Compilation options

`Re2.Options.defaults()` selects UTF-8, Perl-like RE2 syntax, case-sensitive
leftmost-first matching, and the default memory budget.

`Re2.Options.posix()` selects POSIX syntax and leftmost-longest matching.
`Re2.Options.latin1()` selects Perl-like syntax over Latin-1 bytes.

The chainable options can also control the memory budget, syntax mode, encoding,
literal parsing, newline behavior, captures, case sensitivity, Perl classes,
word boundaries, one-line mode, and leftmost-longest matching. Compilation
copies the pattern and snapshots the option values.

## Unsupported features

RE2 intentionally excludes constructs such as backreferences, lookaround,
atomic groups, possessive quantifiers, recursive patterns, conditionals, and
embedded code. `\G`, `\Z`, and `\R` are also not part of the RE2 language.

See the [RE2 syntax reference](https://github.com/google/re2/wiki/Syntax) for a
complete syntax table. See
[Unsupported Regular-Expression Features](UNSUPPORTED_FEATURES.md) for why
these features are absent today and how future support would be evaluated.
