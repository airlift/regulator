# Byte-frequency preset generator

`generate-byte-frequencies.py` regenerates the preset tables of
`io.airlift.regulator.ByteFrequencies` from pinned public sources. It is a
maintenance tool, separate from the Maven build, CI, and published JAR.

Only the derived tables are committed. The script downloads each source into a
local cache, and no source text is redistributed with Regulator.

## Prerequisites

- Python 3 with its standard library
- Network access to GitHub and the Leipzig Corpora Collection

## Usage

Run from the repository root:

```bash
python3 tools/byte-frequencies/generate-byte-frequencies.py
```

The script fetches every source in `sources.tsv`, verifies its length and
SHA-256, and prints the `double[]` initializer of each preset table in the
layout of `ByteFrequencies.java`, ready to replace the existing table verbatim.
To check that the committed tables match their sources instead:

```bash
python3 tools/byte-frequencies/generate-byte-frequencies.py --check
```

Sources are cached in `target/byte-frequencies/`, about 60 MB, and are reused
after verification. `./mvnw clean` deletes that directory; pass
`--cache <directory>` to keep the cache elsewhere.

## Sources

`sources.tsv` lists every source with its preset, URL, pinned revision, length,
SHA-256, and license. Length and SHA-256 describe the bytes used, after
extraction.

| Preset | Sources | Revision | License |
|---|---|---|---|
| `englishText()` | [Leipzig Corpora Collection](https://wortschatz.uni-leipzig.de/en/download/English) `eng_news_2025_100K`, `eng_wikipedia_2016_100K`, `eng-com_web-public_2018_100K` | corpus name | CC BY |
| `json()` | 17 top-level files of [simdjson](https://github.com/simdjson/simdjson) `jsonexamples/`, one per document (see below) | `be9d5d4e` | Apache-2.0 |

The Leipzig Corpora Collection licenses its downloadable corpora under CC BY:
© Universität Leipzig / Sächsische Akademie der Wissenschaften / InfAI. Cite
D. Goldhahn, T. Eckart, and U. Quasthoff, "Building Large Monolingual
Dictionaries at the Leipzig Corpora Collection: From 100 to 200 Languages,"
LREC 2012.

simdjson moved most example files to its `simdjson-data` repository in June
2021, so the manifest pins `be9d5d4e`, a commit before simdjson moved the
examples, which carries them under the repository's Apache-2.0 license.
`json()` uses one file per distinct document, so a document simdjson ships in
several formats counts once. It keeps the original form and leaves out these
files:

- `mesh.pretty.json`, a pretty-printed copy of `mesh.json`
- `twitterescaped.json`, a minified copy of `twitter.json` with non-ASCII text
  escaped
- `twitter_api_compact_response.json` and
  `google_maps_api_compact_response.json`, minified copies of
  `twitter_api_response.json` and `google_maps_api_response.json`
- `numbers.json`, a flat array of random numbers, which is not a realistic
  document shape
- `github_events.json`, which is GitHub event data like the GH Archive corpora
  that score the preset

`twitter_timeline.json` is a different Twitter document and stays.
`tree-pretty.json` stays because it is the only form of its document at the
pinned commit. The generated `random.json` and `repeat.json` stay because their
user records are realistic API response shapes.

The presets are scored against corpora that their measured tables must not be
built from. `englishText()` therefore excludes OpenSubtitles and rebar's
`sherlock.txt` and `leipzig-3200.txt`; the latter is Project Gutenberg's Mark
Twain collection, eBook 3200, and is unrelated to the Leipzig Corpora
Collection. `json()` excludes GH Archive and simdjson's `github_events.json`.

## Method

Each source contributes its bytes after these steps:

- `url`: the downloaded bytes.
- `leipzig`: the `<name>-sentences.txt` file of the corpus archive, each
  `id<TAB>sentence` line reduced to the sentence and its line feed.

Every sum below runs over sources in manifest order, so the floating-point
results, and therefore the printed tables, are deterministic.

Each preset is built in three steps:

1. Smooth each source's byte counts as `ByteFrequencies.fromCounts` does:
   `(count + 1) / (total + 256)`.
2. Take the unweighted mean of the sources' distributions, weight one per
   source.
3. Give every byte at or above `0x80` the largest probability of a byte below
   `0x80`, so the preset never probes a UTF-8 byte.

Each table prints probabilities with `%.6e`, eight per line, as committed.

## Changing a preset

A new or changed source changes which byte a literal probes. Update
`sources.tsv`, regenerate, and replace the table in `ByteFrequencies.java`.
Score the new table on held-out corpora of the preset's format and on other
formats, and update the evidence in `REGULATOR_DECISIONS.md` ("Literal Probe
Byte Selection"). Never build a measured preset from a corpus used to score it
or to benchmark literal scans. Then run `TestByteFrequencies`,
`TestPrefixProbeAcceleration`, and `TestScanPlan`, and check that
`--check` passes.
