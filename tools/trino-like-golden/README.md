# Trino LIKE golden generator

This maintenance tool records the expected results in Regulator's Trino LIKE
corpus, `src/test/resources/io/airlift/regulator/trino-like-corpus.tsv`, from
Trino's own `io.trino.likematcher.LikeMatcher`, the authority for LIKE. It is
separate from the Maven build, CI, and published JAR; ordinary Java tests read
only the committed corpus.

## Pinned dependencies

`fetch-dependencies.sh` reads the Trino repository, commit, and tree from the
benchmark comparator's [`pins.env`](../re2-benchmark/trino-joni/pins.env), so
the corpus and the comparator follow the same `LikeMatcher`.

The tool does not build Trino. It fetches the pinned commit into a partial
clone under `target/` without file contents, verifies the commit and tree
IDs, and extracts only the eight sources of the `io.trino.likematcher`
package, from `core/trino-main/src/main/java/io/trino/likematcher/`. The
package needs only Guava and fastutil, which the script resolves at the
versions Trino uses at that commit: Guava `33.6.0-jre` from its airbase 399
parent and fastutil `8.5.19`. After changing the Trino pin, update those two
versions in `fetch-dependencies.sh` and here to the ones Trino uses at the new
commit, then run the check below.

## Prerequisites

- Git
- JDK 25 or newer, with `java` and `javac` on `PATH`

## Usage

Run from the repository root. To check that the committed corpus matches
Trino without changing it:

```bash
tools/trino-like-golden/generate.sh --check
```

The check prints a diff and fails if any row differs. Run it before changing
the corpus and after changing the Trino pin. To regenerate the corpus:

```bash
tools/trino-like-golden/generate.sh
```

The script compiles the Trino sources and `TrinoLikeCorpusGenerator.java` into
`target/trino-like-golden-build`, then runs the generator on the corpus. The
output goes to a temporary file first, so a failed generation never truncates
the committed corpus.

These variables override the defaults:

- `TRINO_LIKE_GOLDEN_DEPENDENCY_DIR`: where the Trino sources and jars go,
  `target/trino-like-golden-dependencies` by default
- `TRINO_LIKE_GOLDEN_BUILD_DIR`: where the classes go,
  `target/trino-like-golden-build` by default
- `TRINO_LIKE_GOLDEN_TRINO_GIT_SOURCE`: a Git URL or local Trino repository
  path to fetch the pinned commit from, which the script never modifies; the
  repository in `pins.env` by default

## Corpus format

Lines starting with `#` and blank lines are copied unchanged, and row order is
kept. Each row has six tab-separated columns, where an empty hexadecimal
column is an empty byte string:

1. unique case identifier
2. pattern bytes as hexadecimal
3. escape code point as hexadecimal, or `-` for none; Trino takes the escape
   as one `char`, so it must be at most `ffff` and not a surrogate
4. input bytes as hexadecimal
5. `LikeMatcher`'s result with optimization disabled, the result Regulator
   follows
6. `LikeMatcher`'s result with optimization enabled

Trino SQL can produce either result: it compiles a LIKE pattern with
optimization disabled, but recompiles a constant pattern with it enabled when
the plan reaches a worker.

The generator copies the first four columns and replaces the last two with
`true`, `false`, or `error`, where `error` means `LikeMatcher.compile` rejects
the pattern. It decodes the pattern bytes to a string as UTF-8, as Trino SQL
does. It also matches each input at a nonzero offset and fails if that result
differs, since the corpus records one result per row.

## Adding a case

1. Append a row with any values in the two result columns.
2. Run `tools/trino-like-golden/generate.sh`.
3. Review the corpus diff: the new row's results, and no change to any other
   row.
4. Update the row count in `TestTrinoLikeCorpus.testCorpusShape`. If
   Regulator deliberately differs from column 5 on the row, as
   [Malformed LIKE Input](../../REGULATOR_DECISIONS.md#malformed-like-input)
   records, add Regulator's result to the test's `SQL_DEVIATIONS` table. A row
   whose pattern is an `error` or is not valid UTF-8 needs no entry, since
   Regulator rejects that pattern.
5. Run `./mvnw test -Dtest=TestTrinoLikeCorpus`.
