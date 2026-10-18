# Unicode database generator

`generate.py` writes the Unicode data that Regulator reads at runtime from
pinned Unicode Character Database (UCD) files. It is a maintenance tool,
separate from the Maven build and the published JAR. The JAR carries only its
output, `src/main/resources/io/airlift/regulator/unicode/unicode-18.0.0.bin`.

No UCD file is committed. [`manifest.txt`](manifest.txt) lists the unicode.org
URL and SHA-256 of each file the generator reads, and the version in those URLs
is the version of the database. The comment at the top of `generate.py`
documents the format. The database holds:

- the simple case folding of `CaseFolding.txt`, statuses C and S, as orbits
- the multi-character full case folding of `CaseFolding.txt`, status F
- the simple uppercase and lowercase mappings of `UnicodeData.txt`

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
   mkdir -p target && mkdir "target/ucd-${version}"
   for file in CaseFolding.txt UnicodeData.txt; do
       curl -sSfL -o "target/ucd-${version}/${file}" \
           "https://www.unicode.org/Public/${version}/ucd/${file}"
   done
   shasum -a 256 "target/ucd-${version}"/*
   ```

2. Replace each URL and SHA-256 in `manifest.txt`.
3. Run the generator. A row that no longer parses, a new status, or data that
   breaks an assumption the comment at the top of `generate.py` lists fails the
   generator. Change the parser only for a documented change in the UCD file
   format.
4. Delete the previous `unicode-<version>.bin`, and set
   `UnicodeDatabase.UNICODE_VERSION` to the new version.
5. Set the version stated in both copies of `LICENSE-unicode.txt`.
6. Run the complete test suite. Explain every changed result, and record the
   accepted differences in `REGULATOR_DECISIONS.md` and the language
   references.

## License

The UCD files and the data derived from them are covered by the Unicode License
v3 in [`LICENSE-unicode.txt`](../../LICENSE-unicode.txt).
