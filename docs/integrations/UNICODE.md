# Unicode data policy

Every language reads its Unicode properties, case folding, word boundaries,
and character names from Regulator's pinned Unicode 18.0 database, described in
[`tools/unicode-database/README.md`](../../tools/unicode-database/README.md),
so they are the same on every JVM. No Unicode data comes from the running JVM.
This differs from pinned upstream RE2, whose generated tables are Unicode 15.1
data, as `REGULATOR_DECISIONS.md` records.

## Implemented direction

The database holds Unicode data only. Each language layers its own names on
it:

- RE2 property groups are the database's General_Category and Script values,
  named and grouped by the rule of upstream's generator.
- Java and Trino properties follow `java.util.regex.Pattern` and
  `java.lang.Character`. Their categories, scripts, blocks, binary properties,
  and `java...` properties are the database's values combined by the formulas
  of the `Character` javadoc, under the JDK's names and aliases.
- Java's `\N{name}` accepts the names of `Character.codePointOf`, built from
  the database's character names and blocks by the JDK's rule.

Regulator does not keep a copy of upstream RE2's generated category, script,
or case-fold tables. Case folding follows each pattern's language: RE2 folds
with the database's simple case folding, Java with its simple case mappings,
and Trino with its full case folding.

## Motivation

Unicode data read from the running JVM changes with each JDK upgrade, which
changes the Unicode version. One pinned database gives every language,
every JVM, and every node of a distributed Trino installation the same Unicode
results, and moves to a new Unicode version only through an explicit,
reviewed regeneration.

Trino uses Joni with `Syntax.Java`, but Joni/jcodings bundles fixed Unicode
tables. Regulator follows the JDK's property names and the pinned database's
data, not that table version.

## Differences from the JDK

Java and Trino property membership uses the JDK's formulas over the pinned
database. Property membership, Java's character names, and the case
conversions that normalize property and character names therefore agree with
`Character`, `String`, and `Pattern` on the JDK's own Unicode version, which
the `Character` javadoc names: Unicode 16.0 for Java 25 and 17.0 for Java 26.
They differ for:

- characters Unicode assigned after the JDK's version, and names it added,
  such as the Unicode 18.0 scripts Jurchen and Seal and their blocks, and the
  names of the characters themselves
- U+0295 LATIN LETTER PHARYNGEAL VOICED FRICATIVE, which Unicode 17.0 changed
  from `Ll` to `Lo`, so Regulator does not classify it as lowercase, even when
  running on Java 25
- 689 code points that are not emoji, such as U+2388 HELM SYMBOL and U+2605
  BLACK STAR, from which Unicode 17.0 removed Extended_Pictographic
- case mappings Unicode added from older characters to newer ones, such as
  U+0277 uppercasing to U+A7DD; neither is ASCII, so no name resolves
  differently

Regulator's supported property names and case-insensitive matching can differ
from `Pattern` independently of Unicode version.

Reading the pinned database rather than the JDK's data is an intentional
Java-platform adaptation. It changes only Unicode-version-sensitive results; it
does not justify changing RE2's matching algorithm, resource guarantees,
byte-oriented execution, or syntax semantics.

## Unicode data versus regex semantics

The database supplies facts about code points. Each regex language still
defines how its syntax uses those facts.

Examples of database data include:

- general category, script, block, and age
- binary properties such as Extended_Pictographic, Hex_Digit, and
  Other_Alphabetic
- simple and full case mappings and case folding
- character names

Examples of regex-language decisions include:

- whether `\d`, `\s`, and `\w` use ASCII or Unicode definitions in a given
  syntax mode
- which property spellings and aliases are accepted
- how a JDK property such as `Alphabetic` or `javaWhitespace` combines the
  data
- the meaning of `(?U)` in RE2 syntax and Java-compatible syntax
- malformed UTF-8 behavior
- which unsupported Java constructs are rejected

Keeping RE2's ASCII definition of a shorthand class is not a competing Unicode
database. It is a syntax rule. An explicit Java Unicode-character-class mode
can map the same shorthand to Unicode ranges.

## Production design

The package-private `UnicodeGroups` registry in `io.airlift.regulator` exposes
immutable `CharClass` values to the parser and character-class builder. It
holds the Unicode lookups every language shares, such as General_Category
values by their Unicode names; each language resolves its own property names
to them in its own class, such as `JavaUnicodeProperties` for Java and Trino.

Property construction follows this flow:

1. Parse and normalize the property name according to the selected regex
   syntax.
2. Resolve the name to the database's values, or to a combination of them.
3. Build the class from the stored ranges, without visiting each code point.
4. Cache the immutable result by canonical property identity.
5. Compile the result through the existing character-class path.

Character-class membership and case-fold expansions are compiled into engine
instructions and ranges. Matching those classes performs no property lookup.
Language-specific word-boundary assertions are different: Java and Trino
boundary checks classify input code points during matching. They read one
table of flags, built from the database's classes by the same formulas as the
properties: Java's word characters, the class of `\p{IsWord}`; Trino's, which
are Java's without Join_Control plus the six superscript and fraction numbers
of Latin-1 that Joni's Java syntax includes; nonspacing marks; and
`Character.isLetterOrDigit`, which gives a Java boundary's nonspacing mark its
base.

### Java properties

A JDK property that Unicode does not define as one property is the formula of
its `Character` javadoc over the database's values. For example,
`Character.isAlphabetic` is `Lu`, `Ll`, `Lt`, `Lm`, `Lo`, `Nl`, or
Other_Alphabetic, and `Character.isWhitespace` is a space, line, or paragraph
separator other than the three no-break spaces, or one of U+0009 through
U+000D and U+001C through U+001F. A choice of the JDK that is not in the
Unicode data, such as those explicit code points or the earlier names of three
renamed blocks, is a constant in `JavaUnicodeProperties` with a comment citing
the javadoc.

### Property names and aliases

Property-name parsing is language metadata rather than Unicode data. Java and
Trino spellings resolve through `JavaUnicodeProperties.lookup`;
`RegexpParser.lookupUnicodeGroup` applies RE2's exact category and long script
name rules using the registry's raw values.

The parser distinguishes:

- general-category names and aliases
- script names, their ISO 15924 codes, and the `Is` form
- block names and the `In` form
- Java binary properties
- Trino's documented name-normalization rules

Alias handling is tested independently from range membership and does not
silently reinterpret a recognized Java spelling as a different RE2 construct.

### Name normalization

Java and Trino resolve property names, and Java resolves character names, with
the JDK's case conversions, such as uppercasing a script name or comparing it
ignoring case. A name in a pattern can hold any text, and these conversions
take some non-ASCII characters to ASCII, such as the long s to S, the Kelvin
sign to k, and sharp s to SS, so they read the database rather than `String`:
its simple case mappings and the unconditional mappings of `SpecialCasing.txt`.
Lowercasing omits the one condition that applies without a locale,
Final_Sigma, which chooses between two non-ASCII sigmas that no name holds.
Trino's capture names cannot start with a decimal digit, the database's `Nd`.

### Character names

Java's `\N{name}` resolves names as `Character.codePointOf` does. It trims
characters up to U+0020 and uppercases the name, then accepts the name of a
code point by the JDK's rule:

- the Name of `UnicodeData.txt`, such as LATIN SMALL LETTER A
- for a control, its Unicode_1_Name, such as LINE FEED (LF), except that
  U+0007 is BEL, because U+1F514 is BELL, and the three controls without one,
  U+0080, U+0081, and U+0099, have the figment names of `NameAliases.txt`
- for any other assigned code point, its block's identifier with spaces for
  underscores, a space, and the code point in hexadecimal, such as CJK UNIFIED
  IDEOGRAPHS 4E00

The two JDK exceptions are constants citing the JDK's build tool. Aliases,
such as LINE FEED, and the names that Unicode derives for ranges, such as CJK
UNIFIED IDEOGRAPH-4E00, are rejected, as they are by the JDK.

The database stores the names as a dictionary of their words and, per name, a
code point and word indexes, each sharing its leading words with the previous
name. A name that ends in its own code point, such as CJK COMPATIBILITY
IDEOGRAPH-F900, stores that suffix as one token. The 127 most used words have
one-byte indexes. This takes the 41,232 names of Unicode 18.0 from 1.1 MB of
text to 247 KB, where front coding the names alone would take 437 KB, because
most names share words, such as LETTER, with names that do not precede them.

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
  on these classes is not identical to `Pattern`'s, as
  [Differences from the JDK](#differences-from-the-jdk) says.
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

Unicode data is read on first use, not on every compilation:

- Each section of the database is read once, on first use by a property or
  folding rule that needs it.
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
- The character names are read on the first `\N{name}`, from the resource
  again, because the first read of the resource keeps every section but the
  names, which are most of it. They are decoded into arrays of words, code
  points, and word indexes, with a hash table of the names, about 1 MB in
  all.
- The word-boundary flags are built once, when the first Java or Trino pattern
  with a word boundary compiles, not per pattern. They are a two-stage table
  of one flag byte per code point: each block of 256 code points points to its
  flags, and blocks with equal flags are stored once, so the 4,352 blocks share
  135 stored ones, about 52 KB in all. Latin-1 is the first stored block, so a
  Latin-1 code point needs no block lookup. RE2's ASCII word boundary reads no
  table.
- No production table is generated at build time. The pinned database is
  generated ahead of time from pinned UCD files and depends on neither the
  build JDK nor the executing JDK.

## Consumers

These components use the Unicode properties of each language and its case
folding from the pinned database:

- `RegexpParser`, `TrinoRegexpParser`, and `JavaRegexpParser`
- `CharClassBuilder`
- required-match analysis
- single-byte matcher optimizations
- the Java and Trino word boundaries of `EmptyOp`
- the Java and Trino property-name normalization, Java's `\N{name}`, and
  Trino's capture names
- the canonicalization of `FilteredRe2` and `Prefilter`

## Correctness contract

1. Java and Trino categories, scripts, every JDK block, supported binary
   properties, Java properties, and the word classes of match-time boundaries
   are checked against their corresponding `Character` APIs at every code
   point that the running JDK's Unicode version assigns, allowing only the
   cited differences above. Every script and block spelling the JDK accepts
   must name the same class, and a name it rejects, such as a script Unicode
   added since, must hold no character the JDK assigns. RE2's accepted group
   names and syntax are checked against upstream's rule.
2. Canonical aliases are checked for identity, and unsupported names are
   rejected. Every character name is checked against `Character.getName` and
   `Character.codePointOf` at every code point the running JDK's Unicode
   version assigns, and focused spellings against `Character.codePointOf`.
   The case conversions of name normalization are checked against `String`
   and `Character` for every conversion whose input and output contain only
   characters assigned in the JDK's Unicode version.
3. Rust property and value names, their lookup order, and their errors are
   checked against fixtures from the pinned regex crate. A name that the
   database holds and the crate's older Unicode tables lack is an expected
   difference that must keep compiling.
4. Every rule's simple folds are checked exhaustively to form closed orbits
   that hold each code point's case mappings, apart from dotted and dotless I
   in RE2 and Trino. Java's Unicode `\w` and `\b` and Trino's `\b` are checked
   to give every member of an orbit the same word-ness. The Java folds are
   checked against the JVM's case classes wherever the JVM assigns every
   member of a class. Focused `Pattern` comparisons cover dotted and dotless I,
   long S, Kelvin sign, and Greek sigma.
5. Every multi-code-point fold in pinned Joni is checked against the Trino
   full-fold table, with the additional U+1E9E and U+1DF95 mappings recorded
   explicitly. On Java 26 and newer, the Trino rule is checked exhaustively
   against the JDK's full case folding comparison for every character the JDK
   assigns.
6. Unicode-version-only upstream differences have explicit expected results
   rather than unexplained golden-test exceptions.
7. Complete pinned-native differential, exhaustive, randomized, UTF-8,
   Latin-1, parser, and compatibility suites pass with each language's
   properties and case folding.

Test failures name the property or case-equivalence class that changed, so a
JDK or Unicode upgrade can be diagnosed without deciphering a range-table diff.

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

Unicode data alone does not make syntax valid in a language. Each compiler
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

1. JDK agreement and case-equivalence tests.
2. A cached property registry for categories, scripts, blocks, binary
   properties, aliases, and Java properties, read from the pinned database.
3. Case-equivalence classes and full case folding from the pinned database.
4. Database-backed parser, analysis, and specialized-matcher consumers.
5. Removal of the generated upstream Unicode range sources.
6. The intentional platform adaptation recorded in `REGULATOR_DECISIONS.md`.
