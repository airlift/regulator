# JVM-sourced Unicode policy

Regulator derives Java and Trino Unicode properties from the JVM running the
application. This is an intentional difference from pinned upstream RE2,
recorded in `REGULATOR_DECISIONS.md`. RE2 property groups and case folding in
every language instead come from Regulator's pinned Unicode 18.0 database,
described in
[`tools/unicode-database/README.md`](../../tools/unicode-database/README.md).

## Implemented direction

Java and Trino use the same JVM-backed Unicode properties. Regulator derives
their character classes from public JVM APIs, converts them to immutable
ranges, and caches them outside the matching hot path. RE2 property groups are
the database's General_Category and Script values, named and grouped by the
rule of upstream's generator, as `REGULATOR_DECISIONS.md` records. Regulator
does not keep a copy of upstream RE2's generated category, script, or
case-fold tables. Case folding follows each pattern's language, and every rule
reads the pinned database: RE2 folds with its simple case folding, Java with
its simple case mappings, and Trino with its full case folding.

## Motivation

A private Unicode snapshot can disagree with `java.lang.Character`,
`java.util.regex.Pattern`, and other Trino operations when RE2 or the JDK
updates its Unicode version. Using the running JVM for properties avoids that
version skew.

This gives Regulator:

- one Unicode version for properties and other Java operations
- automatic support for Unicode updates delivered with the JDK
- no checked-in generated Unicode range tables; the only checked-in Unicode
  data is the pinned database
- a natural source for Java Unicode blocks, scripts, categories, binary
  properties, and character names
- no matching-loop cost after JVM data has been converted to normal character
  classes

Trino uses Joni with `Syntax.Java`, but Joni/jcodings bundles fixed Unicode
tables. Regulator follows the Unicode properties of the JVM running Trino, and
the case folding of its pinned database, not that pinned table version.

## Intentional upstream difference

Pinned upstream RE2 carries Unicode data generated from a particular Unicode
release. JVM-derived data can differ for code points that were added or
reclassified in another release. Case folding uses the pinned database
instead, whose Unicode version is chosen by Regulator rather than by the JDK.

This is an intentional Java-platform adaptation. It changes only
Unicode-version-sensitive results; it does not justify changing RE2's matching
algorithm, resource guarantees, byte-oriented execution, or syntax semantics.
The implemented decision is recorded in `REGULATOR_DECISIONS.md`.

The same library version may consequently produce different property results
after a JDK upgrade; case folding does not change with the JDK. That is
acceptable and consistent with other JVM Unicode APIs.
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

Each compiled pattern carries the `CaseFolding` rule of its language, and the
parser, character-class builder, required-match analysis, and single-byte
optimizations use an efficient immutable cycle for each equivalence class of
that rule. The rules are:

- RE2: Unicode 18.0 simple case folding, `CaseFolding.txt` statuses C and S,
  from the pinned database. Upstream RE2's generated tables use the same rule
  with Unicode 15.1 data. `FilteredRe2` canonicalizes atoms and text to the
  code point each class folds to, as upstream's prefilter does.
- Java: the case classes of `java.util.regex.Pattern`. The canonical relation
  is the simple upper-case mapping followed by the simple lower-case mapping,
  read from the database's `UnicodeData.txt` mappings, rather than assuming
  that one lower-case mapping is complete. Exhaustive checks against the JVM's
  case classes and focused comparisons with case-insensitive `Pattern`
  validate the resulting equivalence classes. Case-insensitive matching built
  on these classes is not identical to `Pattern`'s.
- Trino: Unicode 18.0 full case folding, `CaseFolding.txt` statuses C and F,
  the rule Joni folds with. Its simple classes are the RE2 rule's, whose tables
  it shares, so dotted capital I and dotless small i stay out of the class of
  I.

The Trino rule's multi-code-point folds are the database's status F mappings,
which the Trino frontend uses for Joni-compatible case-insensitive literals
and positive character classes. Dotted capital I keeps its fold to i followed
by a combining dot above. Full-fold literals compile to a shared-suffix program
graph rather than enumerating every expanded pattern. The table covers every
multi-code-point mapping in pinned Joni and adds two folds to `ss` that Joni
lacks because its fold tables are Unicode 4.1 data: U+1E9E capital sharp S,
from Unicode 5.1, and U+1DF95, from Unicode 18.0.

### Initialization and caching

Unicode scans happen on first use, not on every compilation:

- Each property is built lazily and cached by canonical identity.
- Published ranges are immutable and safe for concurrent compilation.
- Each folding rule's simple case equivalence is initialized once on first
  use: the first compile that folds case with the rule, including a
  letter-pair class such as `[Aa]`, which parse-time simplification turns into
  a case-folded literal. The RE2 rule is also initialized by the first
  `FilteredRe2.Builder.add` of a UTF-8 pattern that contains a literal or a
  character class of at most ten code points, and by the first `FilteredRe2`
  canonicalization of UTF-8 text or of a code point. Each rule derives its
  tables from the database's sparse lists of mappings: RE2 and Trino share the
  tables of its simple case folding, and Java derives its own from its case
  mappings. A rule's simple folds and orbit successors are two-stage tables
  that hold only the blocks of 256 code points with a case partner, about
  100 KB in all, so a process that folds case in Java and in RE2 or Trino holds
  two sets of tables.
- Trino's multi-code-point folds are initialized once on first use by a
  frontend that enables full folding.
- No production table is generated at build time, which would bind
  JVM-sourced properties to the build JDK instead of the executing JDK. The
  pinned database is generated ahead of time from pinned UCD files and depends
  on neither JDK.

## Consumers

These components use the Unicode properties of each language and its case
folding from the pinned database:

- `RegexpParser`
- `CharClassBuilder`
- required-match analysis
- single-byte matcher optimizations

## Correctness contract

1. Java and Trino categories, scripts, every JDK block, supported binary
   properties, and Java properties are checked over the complete Unicode
   code-point domain against their corresponding `Character` APIs. RE2's
   accepted group names and syntax are checked against upstream's rule.
2. Canonical aliases are checked for identity, and unsupported names are
   rejected.
3. Every rule's simple folds are checked exhaustively to form closed orbits
   that hold each code point's case mappings, apart from dotted and dotless I
   in RE2 and Trino. The Java folds are checked against the JVM's case classes
   wherever the JVM assigns every member of a class. Focused `Pattern`
   comparisons cover dotted and dotless I, long S, Kelvin sign, and Greek
   sigma.
4. Every multi-code-point fold in pinned Joni is checked against the Trino
   full-fold table, with the additional U+1E9E and U+1DF95 mappings recorded
   explicitly. On Java 26 and newer, the Trino rule is checked exhaustively
   against the JDK's full case folding comparison for every character the JDK
   assigns.
5. Unicode-version-only upstream differences have explicit expected results
   rather than unexplained golden-test exceptions.
6. Complete pinned-native differential, exhaustive, randomized, UTF-8,
   Latin-1, parser, and compatibility suites pass with each language's
   properties and case folding.

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
3. Case-equivalence classes and full case folding from the pinned database.
4. JVM-backed parser, analysis, and specialized-matcher consumers.
5. Removal of the generated upstream Unicode range sources.
6. The intentional platform adaptation recorded in `REGULATOR_DECISIONS.md`.
