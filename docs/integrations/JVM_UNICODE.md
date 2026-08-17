# JVM-sourced Unicode policy

Regulator derives Unicode data from the JVM running the application. This is
an intentional difference from pinned upstream RE2, recorded in
`REGULATOR_DECISIONS.md`.

## Implemented direction

All regex languages use the same JVM-backed Unicode data. Regulator derives
character classes and case relationships from public JVM APIs, converts them
to immutable ranges, and caches them outside the matching hot path. It does
not keep a separate copy of upstream RE2's category, script, or case-fold tables.

## Motivation

A private Unicode snapshot can disagree with `java.lang.Character`,
`java.util.regex.Pattern`, and other Trino operations when RE2 or the JDK
updates its Unicode version. Using the running JVM avoids that version skew.

This gives Regulator:

- one Unicode version for regex and other Java operations
- automatic support for Unicode updates delivered with the JDK
- no checked-in generated Unicode range tables
- a natural source for Java Unicode blocks, scripts, categories, binary
  properties, character names, and case behavior
- no matching-loop cost after JVM data has been converted to normal character
  classes

Trino uses Joni with `Syntax.Java`, but Joni/jcodings bundles fixed Unicode
tables. Regulator follows the Unicode data of the JVM running Trino, not that
pinned table version.

## Intentional upstream difference

Pinned upstream RE2 carries Unicode data generated from a particular Unicode
release. JVM-derived data can differ for code points that were added or
reclassified in another release.

This is an intentional Java-platform adaptation. It changes only
Unicode-version-sensitive results; it does not justify changing RE2's matching
algorithm, resource guarantees, byte-oriented execution, or syntax semantics.
The implemented decision is recorded in `REGULATOR_DECISIONS.md`.

The same library version may consequently produce different results after a
JDK upgrade. That is acceptable and consistent with other JVM Unicode APIs.
All nodes in a distributed Trino installation should use equivalent supported
JDK versions.

## Unicode data versus regex semantics

The JVM supplies facts about code points. Each regex language still defines
how its syntax uses those facts.

Examples of JVM-owned data include:

- general category
- script and block membership
- alphabetic, ideographic, whitespace, case, and related binary properties
- character names
- upper-case, lower-case, and title-case mappings

Examples of regex-language decisions include:

- whether `\d`, `\s`, and `\w` use ASCII or Unicode definitions in a given
  syntax mode
- which property spellings and aliases are accepted
- the meaning of `(?U)` in RE2 syntax and Java-compatible syntax
- malformed UTF-8 behavior
- which unsupported Java constructs are rejected

Keeping RE2's ASCII definition of a shorthand class is not a competing Unicode
database. It is a syntax rule. An explicit Java Unicode-character-class mode
can map the same shorthand to JVM-derived ranges.

## Production design

The package-private JVM Unicode registry in `io.airlift.regulator` exposes
immutable `CharClass` values to the parser and character-class builder.

Property construction follows this flow:

1. Parse and normalize the property name according to the selected regex
   syntax.
2. Resolve the name to a JVM-backed code-point predicate.
3. Iterate through Unicode code points using the engine's existing scalar-value
   policy.
4. Coalesce adjacent matches into `RuneRange` values.
5. Cache the immutable result by canonical property identity.
6. Compile the result through the existing character-class path.

Character-class membership and case-fold expansions are compiled into engine
instructions and ranges. Matching those classes does not invoke JVM property
predicates per input code point. Language-specific word-boundary assertions are
different: Java and Trino boundary checks can inspect input code points with
`Character` and Unicode property predicates during matching.

### JVM sources

The registry uses public JVM APIs wherever they directly express the property:

- `Character.getType(int)` for general categories
- `Character.UnicodeScript.of(int)` and `forName(String)` for scripts
- `Character.UnicodeBlock.of(int)` and `forName(String)` for blocks
- `Character.isAlphabetic(int)`, `isIdeographic(int)`, `isLetter(int)`, and
  related predicates for binary properties
- `Character.toUpperCase(int)` and `toLowerCase(int)` as the source for simple
  case relationships

The registry contains small definitions for properties that the JVM defines but
does not expose as one public predicate. For example,
`Noncharacter_Code_Point` is:

```java
(codePoint & 0xfffe) == 0xfffe ||
        (codePoint >= 0xfdd0 && codePoint <= 0xfdef)
```

Properties such as join control, hexadecimal digit, Unicode word, and some
whitespace classes are similarly composed from public JVM predicates and small
explicit sets. These definitions follow Java `Pattern` semantics and do not
introduce copied Unicode range tables.

### Property names and aliases

Property-name parsing is language metadata rather than Unicode data. The
registry maintains a small explicit mapping from accepted RE2, Java, and Trino
spellings to canonical property identities.

The parser distinguishes:

- general-category names and aliases
- script names and the `Is` form
- block names and the `In` form
- Java binary properties
- Trino's documented name-normalization rules

Alias handling is tested independently from range membership and does not
silently reinterpret a recognized Java spelling as a different RE2 construct.

### Case folding

`UnicodeCaseFold` builds JVM-derived simple case-equivalence classes instead of
using an upstream-generated table. The parser, character-class builder,
required-match analysis, and single-byte optimizations continue to use an
efficient immutable cycle for each equivalence class.

The canonical relation uses the JVM's upper-case mapping followed by its
lower-case mapping rather than assuming that one `toLowerCase` call is complete.
Exhaustive cycle checks and focused comparisons with case-insensitive
`java.util.regex.Pattern` validate the resulting equivalence classes.

`UnicodeFullCaseFold` separately derives locale-independent multi-code-point
mappings from the JVM's string upper-case and lower-case transformations. The
Trino frontend uses these mappings for Joni-compatible case-insensitive
literals and positive character classes. Full-fold literals compile to a
shared-suffix program graph rather than enumerating every expanded pattern.
The derived table covers all 103 multi-code-point mappings in pinned Joni and
adds the U+1E9E capital sharp-S fold missing from that pinned comparator. This
fold dates to Unicode 5.1, released in 2008; it is not a recent Unicode addition.

### Initialization and caching

Unicode scans happen on first use, not on every compilation:

- Each property is built lazily and cached by canonical identity.
- Published ranges are immutable and safe for concurrent compilation.
- Simple case equivalence is initialized once on first use.
- Full case mappings are initialized once on first use by a frontend that
  enables full folding.
- No production table is generated at build time, which would bind behavior to
  the build JDK instead of the executing JDK.

## Consumers

These components use the JVM-derived data:

- `RegexpParser`
- `CharClassBuilder`
- required-match analysis
- single-byte matcher optimizations

## Correctness contract

1. Categories, scripts, every JDK block, supported binary properties, and Java
   properties are checked over the complete Unicode code-point domain against
   their corresponding `Character` APIs.
2. Canonical aliases are checked for identity, and unsupported names are
   rejected.
3. The derived case-equivalence relation is validated exhaustively. Focused
   `Pattern` comparisons cover dotted and dotless I, long S, Kelvin sign, and
   Greek sigma.
4. Every multi-code-point fold in pinned Joni is checked against the
   JVM-derived full-fold table, with the additional U+1E9E mapping recorded
   explicitly.
5. Unicode-version-only upstream differences have explicit expected results
   rather than unexplained golden-test exceptions.
6. Complete pinned-native differential, exhaustive, randomized, UTF-8,
   Latin-1, parser, and compatibility suites pass with the JVM-backed data.

Test failures name the property or case-equivalence class that changed, so a
JDK upgrade can be diagnosed without deciphering a range-table diff.

## Performance requirements

This is primarily compilation-path behavior and must not regress match
execution.

- Compiled instructions and hot matching loops must retain their current shape.
- Existing specialized matchers must consume precomputed case and class data.
- Common pattern compilation after cache warmup should perform only property
  lookup and ordinary character-class construction.
- First-use property construction and case-table initialization require focused
  benchmarks, but no target-host performance campaign is required merely to
  establish the design.

## Related feature scope

JVM-sourced data alone does not make syntax valid in a language. Each compiler
decides whether to support constructs such as:

- Unicode blocks and binary properties
- Java property aliases
- Unicode-aware `\d`, `\s`, `\w`, and boundaries
- horizontal and vertical whitespace classes
- named Unicode characters
- character-class intersection and subtraction

Each extension requires a separate syntax and compatibility decision. General
lookaround remains a separate, potentially major engine project. General
backreferences are explicitly out of scope because they are incompatible with
the library's linear-time guarantee.

## Implemented components

1. Exhaustive JVM-property authority and case-equivalence tests.
2. A cached JVM property registry for categories, scripts, blocks, binary
   properties, aliases, and Java properties.
3. JVM-derived simple case-equivalence classes.
4. JVM-backed parser, analysis, and specialized-matcher consumers.
5. Removal of the generated upstream Unicode range sources.
6. The intentional platform adaptation recorded in `REGULATOR_DECISIONS.md`.
