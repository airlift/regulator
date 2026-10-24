# Rust regex golden generator

This maintenance tool builds against the pinned Rust `regex` crate. It is
separate from the Maven build, CI, published JAR, and Java runtime. The Java
build never runs Cargo and production code never loads Rust code.

`rust-regex-golden` generates expected results for Regulator's Rust regex front
end from the crate itself. Commit those fixtures under `src/test/resources` so
ordinary Java tests can check against them.

## Pinned dependencies

`Cargo.toml` pins exact crate versions and `Cargo.lock` pins the whole
dependency graph with crates.io checksums:

- `regex` 1.13.1, tag `1.13.1` at commit
  `2b527599eb9eea0dcc288c704584f242f26a5c61`
- `regex-syntax` 0.8.11
- `regex-automata` 0.4.18
- `regex-test` 0.1.1, the loader the crate's own test suite uses for its TOML
  corpus

`regex` 1.13.1 requires `regex-automata` `^0.4.16` and `regex-syntax`
`^0.8.11`. The pins are the releases those requirements resolved to when this
tool was written. `regex-automata` 0.4.17 and 0.4.18 change only the cache pool
used for parallel searches, not matching semantics. The direct `regex-automata`
dependency disables default features and enables only `std`, `syntax`, and
`meta`, so feature unification gives the generator the same engine set as a
plain `regex` build.

`rust-toolchain.toml` pins rustc 1.99.0 (`b940084d7 2026-09-28`).

`fetch-dependencies.sh` fetches the `regex` tag into `target/`, verifies that it
resolves to the pinned commit, and checks it out. The upstream fixture reads
the TOML corpus in its `testdata/` directory. Override
`RUST_REGEX_GOLDEN_DEPENDENCY_DIR` to fetch into another directory, or set
`RUST_REGEX_DIR` to use an existing checkout, which is never fetched or
changed. Either way, generation fails unless the checkout is at the pinned
commit with no modified or untracked files under `testdata/`.

### Unicode versions

The crate's Unicode tables are Unicode 16.0.0, and Regulator's Unicode database
is Unicode 18.0.0. The two versions disagree on names Unicode added after 16.0,
on characters assigned after 16.0, and on older characters whose properties
Unicode changed after 16.0, such as U+0295, so the hand-written cases avoid
those characters. If either side changes Unicode version, review the cases for
characters that the new version adds or changes, and replace any case that
uses one. A case that names a property or value added after 16.0, such as
`property_age_future`, is an expected difference in `TestRustRegexpGolden`,
which requires the crate to keep rejecting it and Regulator to keep compiling
it; add each new name there.

## Prerequisites

- Git
- rustup, which installs the pinned toolchain on first use

Generate in a clean environment: the pinned toolchain, dependencies built with
`--locked`, the verified crate checkout, and no Cargo configuration or
`RUSTFLAGS` from the user's environment. The committed fixtures were generated
on an AWS Graviton (`c7g`) host running Amazon Linux 2023.

## Usage

Regeneration has two steps, both run from the repository root. First write the
case tables from their readable source:

```bash
python3 -I tools/rust-regex-golden/cases/emit.py
```

This step needs only Python 3. Then generate the fixtures:

```bash
tools/rust-regex-golden/generate.sh
```

The script fetches the pinned `regex` source, builds
`target/rust-regex-golden-build/release/rust-regex-golden` with `--locked`, and
writes all five fixtures. Each fixture is written to a temporary file first,
so a failed generation never truncates a committed fixture.

The generator has four modes. Run them from the repository root, with
`RUST_REGEX_DIR` set to the crate checkout that `fetch-dependencies.sh` prints:

```bash
GENERATOR=target/rust-regex-golden-build/release/rust-regex-golden

"${GENERATOR}" --mode=syntax < tools/rust-regex-golden/syntax-cases.tsv > syntax.jsonl
"${GENERATOR}" --mode=match < tools/rust-regex-golden/match-cases.tsv > match.jsonl
"${GENERATOR}" --mode=match < tools/rust-regex-golden/random-cases.tsv > random.jsonl
"${GENERATOR}" --mode=upstream --testdata="${RUST_REGEX_DIR}/testdata" > upstream.jsonl
"${GENERATOR}" --mode=upstream-bytes --testdata="${RUST_REGEX_DIR}/testdata" > upstream-bytes.jsonl
```

### Case tables

The case tables `syntax-cases.tsv`, `match-cases.tsv`, and `random-cases.tsv`
are generated; edit the case lists in `cases/syntax_cases.py`,
`cases/match_cases.py`, and `cases/random_cases.py` instead. `cases/emit.py`
writes all three tables deterministically: it rejects duplicate identifiers,
writes ASCII with `\n` line endings, and keeps the order and section titles of
the case lists. Commit the case lists and tables together.

Lines starting with `#` and blank lines are ignored. Identifiers must be
unique. Pattern and text bytes are lowercase hexadecimal, or `-` for empty, so
the tables can hold any bytes without depending on a platform string encoding.
Patterns must be valid UTF-8 because the crate parses Rust strings. Texts must
be valid UTF-8 unless the case uses the bytes API, whose texts may be any
bytes.

`syntax-cases.tsv` has three tab-separated fields:

1. stable case identifier
2. pattern bytes as hexadecimal
3. options

`match-cases.tsv` has six tab-separated fields:

1. stable case identifier
2. pattern bytes as hexadecimal
3. options
4. text bytes as hexadecimal
5. inclusive window start byte offset, or `-` for the whole text
6. exclusive window end byte offset, or `-` for the whole text

A window in `match-cases.tsv` must lie on character boundaries unless the case
uses the bytes API.

`random-cases.tsv` has the `match-cases.tsv` fields. `cases/random_cases.py`
draws its cases from `random.Random(790431)`, in this order: 800 random
expressions, nested up to three levels over a small set of atoms, on random
texts over `a`, `b`, `é`, space, CR, and LF; then 600 malformed token streams
of regex metacharacters, flag letters, and digits on a fixed list of texts;
then 154 property-name regression rows, such as `\p{IsL}` spelled with a dotted
capital I. It ends with 400 random expressions over byte and Unicode atoms with
the bytes API, on random texts that split and break UTF-8: `a`, `é`, either of
its bytes alone, `\xFF`, space, CR, and LF. Python's seeded `random` sequence
is stable across Python 3 releases, so the table is reproducible, but editing
an earlier section changes every later draw. A case whose pattern, text, and
options repeat an earlier case is skipped, leaving 1926 cases, and identifiers keep the draw
number, such as `random_042`, `malformed_147`, or `bytes_042`, so they stay
stable. Every case searches the whole text, and every case before the bytes API
section uses the default options. David Phillips wrote the corpus for the front
end's first golden generator, in pull request 11.

### Options

Options select the API and configure its builder, written as flag letters in
this order:

- `i` case insensitive
- `m` multi-line
- `s` dot matches new line
- `R` CRLF
- `U` swap greed
- `x` ignore whitespace
- `o` octal

Unicode mode is on by default. `-u` turns it off. The nest limit defaults to
the crate's 250; `n` followed by a decimal limit, such as `n0` or `n251`, sets
any other limit. `bytes` builds the pattern with `regex::bytes::RegexBuilder`
instead of `regex::RegexBuilder`, and configures the meta regex as that builder
does, with the crate's `utf8` syntax setting and `utf8_empty` matching setting
off. Groups are separated by
commas in the order flags, `-u`, nest limit, `bytes`. Defaults are written `-`.
Examples: `imR`, `-u`, `x,-u`, `o`, `n1`, `o,-u,n1`, `-u,bytes`. The generator
rejects any other spelling, including a nest limit with leading zeros or equal
to the default, so each option set has exactly one encoding. All other builder
settings keep the crate's defaults: line terminator `\n` and the default size
limits.

### Fixtures

All fixtures are JSONL readable by `GoldenJsonl`. Every value is a string,
boolean, or null. Numbers are written as strings. The first line is a
generated-file header comment.

Error fields record only structured facts, never message text:

- `error`: `syntax` or `size_limit`
- `errorStage`: `ast` for a parse error or `hir` for a translation error
- `errorKind`: the `regex-syntax` error kind variant name, without its payload
- `errorStart`, `errorEnd`: the error span as byte offsets into the pattern

A size-limit error has null stage, kind, and span. The generator asserts that
`regex-syntax` rejects a pattern exactly when `RegexBuilder` reports a syntax
error, and accepts it whenever `RegexBuilder` reports a size-limit error.

`groupCount` includes group zero. `groupNames` lists every group, including
group zero, separated by commas, with `-` for an unnamed group. Group ranges are
`start:end` byte offsets separated by commas, with `-1:-1` for a group that did
not participate.

#### `rust-regex-syntax.jsonl`

One record per `syntax-cases.tsv` row: `id`, `patternHex`, `options`, `ok`,
the error fields, `groupCount`, and `groupNames`.

#### `rust-regex-match.jsonl`

One record per `match-cases.tsv` row: the input fields, `ok`, the error fields,
`groupCount`, `groupNames`, and:

- `isMatch`: whether any match exists
- `captures`: the first match's group ranges, or null
- `matchCount`: the true number of successive non-overlapping matches
- `matches`: group ranges of each successive match, separated by `;`, capped
  at the first 64; empty when there is no match
- `anchoredCaptures`: the first match anchored at the search start, or null
- `fullDefined`: present only on windowed records; whether `fullCaptures` is
  defined for the window, or null when the pattern is rejected
- `fullCaptures`: the match that spans exactly the searched range, the whole
  text or the window; null when there is none or when `fullDefined` is false

Successive matches follow the crate's iterator: after a match, the next search
starts at its end, and an empty match adjacent to the previous match is
skipped. Without the bytes API, empty matches occur only at character
boundaries.

Unwindowed cases use the public `Regex` API, or `bytes::Regex` with the `bytes`
option. The generator also runs the same search through a `regex-automata` meta
regex configured exactly as the public type configures it and asserts the two
agree. Anchored searches use that meta regex with `Anchored::Yes`.

Windowed cases search the meta regex with `Input::range`. Look-around
assertions still see the whole text: `^` does not match at a window start that
is not the text start unless multi-line mode sees a preceding line terminator,
and `\b` considers the characters outside the window.

A full match spans exactly the searched range while assertions keep the whole
text as context, the contract of `Re2.matches(Slice, int, int)`. The crate has
no such operation, so the generator compiles the pattern's HIR followed by
`\z`, with no start assertion, and runs it anchored at the range start on the
text truncated at the range end. The anchored start pins the match start, and
`\z` on the truncated text pins the match end. Truncation keeps everything
left of the range end exact, but it changes what an assertion sees at or after
the range end. The full match is therefore undefined, and `fullDefined` is
false, when the range ends before the text end and either:

- the pattern contains an assertion that reads the text at or after its
  position, or
- the case does not use the bytes API and the range end splits a character,
  which makes the truncated text end look like a character boundary.

The right-reading assertions follow `regex-automata` 0.4.18
`util::look::LookMatcher`: `$` and `\z` in every mode, `^` in CRLF multi-line
mode (which must check that `\r` is not followed by `\n`), `\b`, `\B`,
`\b{start}`, `\b{end}`, `\<`, `\>`, and `\b{end-half}`, both ASCII and Unicode.
Only `\A`, `^` in `\n` multi-line mode, and `\b{start-half}` read solely to
the left. The generator matches every `Look` variant exhaustively, so a new
assertion fails compilation until it is classified.

#### `rust-regex-random.jsonl`

One record per `random-cases.tsv` row, in the `rust-regex-match.jsonl` format
and with the same recorded operations.

#### `rust-regex-upstream.jsonl`

The crate's own TOML test corpus at the pinned tag, recorded in the
`rust-regex-match.jsonl` format. A test with search bounds that do not cover
the whole text is recorded as a windowed record with the bounds as `start` and
`end`; other tests have `start` and `end` set to `-`. Upstream bounds may split
a character, unlike a string-mode window. Identifiers are the upstream test
name prefixed by its file path under `testdata/` without the extension, for
example `fowler/basic/basic3`.

The generator keeps a test only when the front end can express it: a single
pattern, leftmost-first match kind, leftmost search kind, UTF-8 mode, a valid
UTF-8 text, `\n` as the line terminator, and options within the flag set
above. Bounded tests search with `Input::range`. The crate's suite runs an
anchored test as an iteration of anchored searches, while the fixture records
only the first anchored match as `anchoredCaptures`. An anchored test is
therefore kept when it requests a single match or its anchored iteration finds
at most one match. The second header line counts kept tests and skipped tests
by reason:

- `regex-lite-only`: `regex-lite.toml`, which encodes regex-lite's ASCII
  semantics and is not loaded by the crate's own test suite
- `regex-set`: more than one pattern
- `match-kind`: not leftmost-first
- `search-kind`: not a leftmost search
- `utf8-disabled`: UTF-8 mode off
- `line-terminator`: a line terminator other than `\n`
- `anchored-iteration`: an anchored test whose anchored iteration finds more
  than one match
- `invalid-utf8-haystack`: a text that is not valid UTF-8

Before writing, the generator runs every kept test and every
`anchored-iteration` test through `regex-test`'s runner against the pinned
crate and fails if any result disagrees with the upstream expectation.

#### `rust-regex-upstream-bytes.jsonl`

The same corpus recorded for the bytes API, in the same format, with the
`bytes` option on every record. The filter is the one above with UTF-8 mode
reversed: a test with UTF-8 mode on is skipped as `utf8-enabled`, because the
bytes API has no UTF-8 mode, and a text that is not valid UTF-8 is kept. Unlike
`suite_bytes.rs`, bounded tests are kept and searched with `Input::range`.

## Committed fixtures

`generate.sh` owns all five fixtures:

- `src/test/resources/io/airlift/regulator/rust-regex-syntax.jsonl`
- `src/test/resources/io/airlift/regulator/rust-regex-match.jsonl`
- `src/test/resources/io/airlift/regulator/rust-regex-random.jsonl`
- `src/test/resources/io/airlift/regulator/rust-regex-upstream.jsonl`
- `src/test/resources/io/airlift/regulator/rust-regex-upstream-bytes.jsonl`

Regenerate them together from the repository root, case tables first:

```bash
python3 -I tools/rust-regex-golden/cases/emit.py
tools/rust-regex-golden/generate.sh
```

## Unicode property names

`RustUnicodePropertyTables` builds the crate's property and value names when it
loads, from the property and value aliases in Regulator's Unicode database. It
follows the naming rules of the crate's table generator, `ucd-generate` 0.3.1,
which parses the UCD with `ucd-parse` 0.1.13 and normalizes names with
`ucd-util` 0.2.2, and each rule names the function it follows. When the crate
pin moves, check those tools for changed naming rules and make the same change
in `RustUnicodePropertyTables`.

## Maintenance triggers

Regenerate the fixtures when:

- the pinned crate versions, `Cargo.lock`, or toolchain change;
- a case list or case table changes;
- a correctness investigation needs a new expected result from the crate; or
- the generator's recorded operations, output format, or filtering change.

Do not regenerate fixtures merely because the JDK, Java implementation, or
Maven build changes. Never rewrite a golden result to agree with the Java
implementation without first establishing that the pinned crate produces the
new result.

## Updating the pin

Treat a pin change as a deliberate compatibility update, not routine fixture
churn:

1. Review the `regex`, `regex-syntax`, and `regex-automata` changes between the
   old and new releases for syntax, Unicode table, and matching changes.
2. Update the versions in `Cargo.toml`, the tag and commit in
   `fetch-dependencies.sh`, and every pin documented here. If the crate's
   Unicode version changes, compare it with the version of Regulator's Unicode
   database, as [Unicode versions](#unicode-versions) describes.
3. Regenerate `Cargo.lock` with `cargo update --precise` or
   `cargo generate-lockfile`, then build with `--locked`.
4. Regenerate every fixture and run `generate.sh` a second time to confirm the
   output is byte-identical.
5. Inspect the complete fixture diff. Explain every changed result from an
   upstream change or an intentional case change; do not accept unexplained
   drift.
6. Run the fixture consumers and the repository build.

Never commit fetched sources or build output.

## Review checklist

For any generator or fixture change, verify that:

- case lists, case tables, and generated fixtures change together, and
  `cases/emit.py` reproduces the committed case tables byte for byte;
- a second generation run produces byte-identical fixtures;
- pattern and text bytes remain hexadecimal in the case tables;
- the upstream kept and skipped counts change only for an explained reason;
- no recorded field derives from an error message;
- every Java and crate disagreement is investigated rather than normalized into
  a fixture; and
- `./mvnw clean install` passes.

## License

The upstream fixtures are derived from the `regex` crate's test data, which is
distributed under the crate's MIT or Apache-2.0 license. Its `fowler/*`
records derive from the AT&T testregex data. The crate no longer ships that
data's license, although `testdata/fowler/dat/README` still refers to it, so
the license is reproduced from the crate's history. See
[`LICENSE-rust-regex.txt`](../../LICENSE-rust-regex.txt).

`RustUnicodePropertyTables` reproduces the naming rules of `ucd-generate` and
its `ucd-parse` and `ucd-util` crates, which are distributed under the MIT or
Apache-2.0 license; their MIT notices are in
[`LICENSE-rust-regex.txt`](../../LICENSE-rust-regex.txt).
