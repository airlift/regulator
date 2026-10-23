# Unicode database generator

`generate.py` writes the Unicode data that Regulator reads at runtime from
pinned Unicode Character Database (UCD) files. It is a maintenance tool,
separate from the Maven build and the published JAR. The JAR carries only its
output, `src/main/resources/io/airlift/regulator/unicode/unicode-18.0.0.bin`.

No UCD file is committed. [`manifest.txt`](manifest.txt) lists the unicode.org
URL and SHA-256 of each file the generator reads, and the version in those URLs
is the version of the database. The comment at the top of `generate.py`
documents the format. The database holds:

- the simple case folding of `CaseFolding.txt`, statuses C and S, as orbits,
  which RE2 patterns and `FilteredRe2` fold with, and which give Trino
  patterns their single-code-point folds
- the multi-character full case folding of `CaseFolding.txt`, status F, which
  gives Trino patterns their multi-code-point folds
- the simple uppercase and lowercase mappings of `UnicodeData.txt`, from which
  Java patterns fold, and the unconditional multi-character mappings of
  `SpecialCasing.txt`, which with them normalize Java property and character
  names
- the General_Category of every assigned code point, from `UnicodeData.txt`,
  and the Script of every code point `Scripts.txt` lists, which give RE2
  patterns their Unicode property groups
- the short name of each script, from `PropertyValueAliases.txt`, the Block of
  every code point `Blocks.txt` lists, Bidi_Mirrored from `UnicodeData.txt`,
  and the binary properties of `emoji/emoji-data.txt` and the subset of
  `PropList.txt` that Java properties read, which with the categories and
  scripts give Java and Trino patterns their Unicode properties and word
  boundaries
- the Name of every code point `UnicodeData.txt` names, and the
  Unicode_1_Name of every control, which give Java's `\N{name}` its names
- the Age of every assigned code point, from `DerivedAge.txt`, which gives Rust
  patterns their Age property and tells the tests which code points the running
  JDK's Unicode version assigns
- every binary property of `PropList.txt` and `DerivedCoreProperties.txt`, the
  Script_Extensions of `ScriptExtensions.txt`, the Grapheme_Cluster_Break,
  Word_Break, and Sentence_Break of the `auxiliary/` break property files, the
  names of every property, from `PropertyAliases.txt`, and the names of the
  General_Category, Script, Age, and break property values, from
  `PropertyValueAliases.txt`, which give Rust patterns their Unicode properties
  and the names they resolve them by

## Usage

The generator needs Python 3.9 or newer and network access to unicode.org.
From the repository root, run:

```bash
python3 -I tools/unicode-database/generate.py
```

It downloads each file into `target/unicode-database/<version>/`, which Git
ignores, and refuses a file whose SHA-256 differs from the manifest before
parsing it. Inputs are only parsed, never evaluated. The output depends only on
the manifest, the files it pins, and `generate.py`, so rerunning the generator
changes nothing.

The generator also writes [`generated.txt`](generated.txt): the generation key,
which is a SHA-256 of `generate.py` and `manifest.txt`, and the SHA-256 of the
database. The database embeds the same key. `TestUnicodeDatabase` runs in
every build without a network. It recomputes the key from the committed
generator and manifest and hashes the committed database, and fails if either
differs from `generated.txt`. Rerun the generator after changing either file.

`--check` writes nothing in the source tree. It downloads the files again,
generates in memory, and fails unless the committed database and
`generated.txt` are byte-identical to the new ones:

```bash
python3 -I tools/unicode-database/generate.py --check
```

The [Unicode database workflow](../../.github/workflows/unicode-database.yml)
runs `--check` only when the generator, its manifest, or the database changes.
Ordinary builds never contact unicode.org.

## Moving to a new Unicode version

Moving to a new version changes every result that depends on the database.

1. Download each file the manifest lists, from the new version's `ucd/`
   directory, into a fresh empty directory under `target/`, never into the
   source tree:

   ```bash
   version=19.0.0
   mkdir -p target && mkdir "target/ucd-${version}" \
           "target/ucd-${version}/auxiliary" "target/ucd-${version}/emoji"
   for file in Blocks.txt CaseFolding.txt DerivedAge.txt \
           DerivedCoreProperties.txt PropList.txt PropertyAliases.txt \
           PropertyValueAliases.txt ScriptExtensions.txt Scripts.txt \
           SpecialCasing.txt UnicodeData.txt \
           auxiliary/GraphemeBreakProperty.txt \
           auxiliary/SentenceBreakProperty.txt \
           auxiliary/WordBreakProperty.txt emoji/emoji-data.txt; do
       curl -sSfL -o "target/ucd-${version}/${file}" \
           "https://www.unicode.org/Public/${version}/ucd/${file}"
   done
   shasum -a 256 "target/ucd-${version}"/*.txt \
           "target/ucd-${version}"/auxiliary/* "target/ucd-${version}"/emoji/*
   ```

2. Replace each URL and SHA-256 in `manifest.txt`.
3. Run the generator. A row that no longer parses, a new status, or data that
   breaks an assumption the comment at the top of `generate.py` lists fails the
   generator, as does a binary property file that adds or drops a property;
   review a new property before adding it to the generator's list. Change the
   parser only for a documented change in the UCD file format.
4. Delete the previous `unicode-<version>.bin`, and set
   `UnicodeDatabase.UNICODE_VERSION` to the new version.
5. Set the version stated in both copies of `LICENSE-unicode.txt`.
6. Update the version named in the docs, the `FilteredRe2` Javadoc, and this
   README; `git grep -n '18\.0'` lists them.
7. Run the complete test suite. Explain every changed result, and record the
   accepted differences in `REGULATOR_DECISIONS.md` and the language
   references. A change the new version makes to an older character, which
   the JDK keeps until its own Unicode version catches up, is an expected
   difference in `TestJdkUnicodeAgreement`.
8. Check the Rust golden fixtures, which the regex crate records from its own
   Unicode tables. A property or value name the new version adds is an
   expected difference in `TestRustRegexpGolden`, and a hand-written case
   whose characters the new version changes is replaced, as the
   [golden tool's Unicode versions](../rust-regex-golden/README.md#unicode-versions)
   describe.

## License

The UCD files and the data derived from them are covered by the Unicode License
v3 in [`LICENSE-unicode.txt`](../../LICENSE-unicode.txt).
