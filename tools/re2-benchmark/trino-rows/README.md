# Normative Trino row workloads

`trino_rows.py` generates the workloads of `BenchmarkTrinoRows`, which calls each
Trino function once per row over 4,096 real corpus rows, and of
`BenchmarkTrinoLikeRows`, which matches a Trino LIKE pattern once per row over
the same rows. The
[methodology](../../../docs/benchmarks/METHODOLOGY.md#normative-trino-rows)
explains what the suite is for, the
[maintenance guide](../../../docs/MAINTAINING_REGULATOR.md#normative-trino-rows-gate)
defines how its results gate a change, and this file defines the rows and
cells.

The generator writes two checked-in manifests that the benchmark reads from
its classpath:

- `src/test/resources/io/airlift/regulator/trino-row-workloads.tsv`: one line
  per workload with its corpora, sample sizes, the SHA-256 of the sampled rows,
  their total bytes, and their p50, p90, and p99 lengths.
- `src/test/resources/io/airlift/regulator/trino-row-literals.tsv`: one line
  per workload and cell, with the cell's ID, group, tercile, and band, its
  number of candidates, the literal, an ordered cell's second literal, the
  escaped Trino pattern, the first literal's first byte, that byte's count and
  frequency in the sampled rows, and the exact number of sampled rows the
  pattern matches (`hit_rows`) and of non-overlapping matches. An empty cell
  keeps its line with no literal.

Setup recomputes the sample, checks its checksum, and checks every cell's
hit rows before timing; `BenchmarkTrinoRows` also checks its matches.

## Corpora

`../manifests/trino-row-corpus.tsv` pins every corpus by location, revision,
size, SHA-256, and license. The OpenSubtitles English, Russian, and Chinese
samples are the repository's `literal-corpus` test resources. The access logs
keep the pins of `literal-probe-corpus.tsv`, so one corpus directory serves
both. The six loghub files and one GH Archive hour are fetched at run time and
never committed; loghub's license allows research and academic use with
attribution, and redistribution is unconfirmed. The GH Archive hour is
truncated at the last line feed within 32 MiB, 8,200 events, because the 8 MiB
literal-probe prefix holds only 2,242.

Provision the corpus directory, verify it, and regenerate or check the
manifests from the repository root:

```bash
python3 tools/re2-benchmark/trino-rows/trino_rows.py fetch \
  --corpus-directory /path/to/trino-row-corpus
python3 tools/re2-benchmark/trino-rows/trino_rows.py generate \
  --corpus-directory /path/to/trino-row-corpus --check
```

`fetch` downloads only missing files and verifies those already present.
`generate` without `--check` rewrites both manifests. Generation takes about 35
seconds.

Tests:

```sh
python3 -m unittest discover -s tools/re2-benchmark/trino-rows
```

## Rows

A corpus row is a nonempty line without its line feed or CR LF terminator.
Each workload samples 4,096 rows by stride across the whole corpus, not its
first lines, so logs are not clustered in time: from a corpus of `N` rows,
sample `k` takes row `floor(k * N / 4096)`.

`loghub` pools the hdfs, linux, openssh, spark, windows, and android files, in
that order. 4,096 rows do not divide by six, so the first four files contribute
683 rows each and the last two 682; each file is stride-sampled for its share.

The benchmark shuffles the sampled rows with `java.util.Random(20261015)` and
lays them out back to back in one buffer, as a Trino block holds them. Each
call takes the next row, so one cycle visits every row once.

## Grid literals

Every workload gets up to nine literals, one per cell of a grid of first-byte
frequency and row hit rate. The rule is exact; no literal is chosen by hand.

1. Candidates are the distinct maximal runs of token characters, 4 to 16 bytes
   long, in the corpus rows outside the sample. Token characters are ASCII
   letters and digits, `_.-/:"=`, and the code points U+00C0-U+00D6,
   U+00D8-U+00F6, U+00F8-U+024F, U+0370-U+04FF, U+3040-U+30FF, U+3400-U+4DBF,
   U+4E00-U+9FFF, and U+AC00-U+D7A3. The fixed table keeps tokens independent
   of the Python Unicode database.
2. Byte frequencies are counted over the bytes of the 4,096 sampled rows. The
   distinct first bytes of all candidates are ordered by that count, least
   frequent first, ties by byte value, and their counts are summed in that
   order. A byte whose running sum reaches `c` of the total `t` is in tercile
   `ceil(3c / t) - 1`, or `low` when `c` is 0: `low`, `mid`, or `high`. The
   thirds therefore split the first bytes' occurrences, not their number, and
   `high` is the fewest most frequent first bytes that together make up at
   least a third of the occurrences.
3. A candidate's hit rows are the sampled rows containing it. Its band is
   `none` for 0 rows, `selective` for 41 to 204 rows (1% to 5%, rounded
   inward), and `common` for 820 to 2,048 rows (20% to 50%). Candidates in
   other ranges belong to no cell.
4. Each cell takes the candidate with the smallest SHA-256 of
   `trino-rows`, `20261015`, the workload name, and the candidate bytes,
   joined by NUL bytes. A cell without candidates is recorded as empty.

Literal IDs are `<tercile>-<band>`, such as `mid-selective`. The pattern escapes
`.^$*+?()[]{}|\`, so it matches only the literal.

## Ordered pairs

Every workload also gets up to three ordered cells, which search one literal
and then another, as `%special%requests%` does in TPC-H Q13. The rule is exact.

1. Candidates are the grid candidates whose hit rows number at least 41, the
   floor of the `selective` band, which excludes absent and very rare
   literals.
2. Candidate pairs are the ordered pairs `(a, b)` of distinct candidates. A
   pair's hit rows are the sampled rows holding `a`, and then `b` starting at
   or after the end of that occurrence of `a`. Its band is `none`,
   `selective`, or `common` by the ranges of grid rule 3; pairs in other ranges
   belong to no cell.
3. Each cell takes the pair with the smallest SHA-256 of `trino-rows`,
   `20261015`, the workload name, `a`, and `b`, joined by NUL bytes. A cell
   without pairs is recorded as empty.

Literal IDs are `ordered-<band>`, group `ordered`; the manifest records `a` as
the literal, `b` as the second literal, and the number of pairs in the band as
`candidates`. The pattern is `a(?s:.*)b`, each literal escaped as a grid
literal is. Trino's `.` excludes the line feed, so only a dot-all gap reaches
Regulator's ordered-literal matcher; rows hold no line feeds, so the pattern
matches the same rows as `a.*b`. The greedy gap runs to the last `b`, so a
matching row holds one match. The
[maintenance guide](../../../docs/MAINTAINING_REGULATOR.md#normative-trino-rows-gate)
says how the gate reports them.

## Hand-picked literals

Some workloads also carry query-like literals chosen by hand, IDs `picked-1`
and `picked-2`, group `picked`. The
[maintenance guide](../../../docs/MAINTAINING_REGULATOR.md#normative-trino-rows-gate)
says how the gate reports them:

| Workload | Literals |
|---|---|
| `english` | `exactly` |
| `nginx` | `Mozilla/5.0`, `" 404 ` (quote, space, 404, space) |
| `apache` | `Googlebot` |
| `loghub` | `Failed password` |
| `gharchive` | `"PullRequestEvent"` |

## Dot-star cells

`BenchmarkTrinoRows` also searches every grid and hand-picked literal with the
pattern `.*literal.*`, the literal escaped as before. A plain literal takes
Regulator's exact-literal route; `.*literal.*` takes its dot-star literal
route, which answers `contains` with a boolean contains search and the other
functions with a span around the literal. The cell's ID is `dotstar-<id>`,
such as `dotstar-mid-selective` or `dotstar-picked-1`, group `dotstar`, and it
copies source cell `<id>`'s literal, `candidates`, and hit rows; a dot-star
cell is empty when its source cell is. The greedy `.*` on each side spans the
whole row, which holds no line feed, so a hit row holds one match and `matches`
equals `hit_rows`. The
[maintenance guide](../../../docs/MAINTAINING_REGULATOR.md#normative-trino-rows-gate)
says how the gate reports them.

## Running

Compile the test classes and resolve the classpath as the
[testing guide](../../../docs/testing.md#benchmark-setup) describes. Time only
on the designated AWS hosts. The class has one method per Trino function
(`contains`, `count`, `position`, `extract`, `split`, and `replace`) and three
parameters: `workload`, `literal`, and `corpusDirectory`. The OpenSubtitles
workloads ignore `corpusDirectory`.

The core tier, 64 cells for a quick A/B, takes the English, nginx, loghub, and
GH Archive workloads, the three `none` cells and `mid-selective`, and four
functions:

```bash
java --add-modules jdk.incubator.vector -cp "$benchmark_classpath" \
  org.openjdk.jmh.Main 'BenchmarkTrinoRows\.(contains|count|position|replace)$' \
  -foe true \
  -p workload=english,nginx,loghub,gharchive \
  -p literal=low-none,mid-none,high-none,mid-selective \
  -p corpusDirectory=/path/to/trino-row-corpus
```

The full suite selects all six functions with `'BenchmarkTrinoRows\.'` and
runs every cell of every workload. A workload's empty cell fails setup, so
run each workload with its own `-p literal` list:

```bash
awk -F'\t' '
  NR == 1 { for (i = 1; i <= NF; i++) column[$i] = i; next }
  $column["pattern"] != "" {
    workload = $column["workload"]
    ids[workload] = ids[workload] (ids[workload] ? "," : "") $column["literal_id"]
  }
  END { for (workload in ids) print workload, ids[workload] }' \
  src/test/resources/io/airlift/regulator/trino-row-literals.tsv
```

`BenchmarkTrinoLikeRows` takes the same parameters and cells except the
dot-star cells, whose `%literal%` would repeat their grid or hand-picked cell.
It matches each literal with the LIKE pattern `%literal%`, and each ordered
pair with `%a%b%`, without an escape character; setup rejects a literal
containing `_`, `%`, or `\`. Its `matches` method runs the interpreted pattern
and `matchesGenerated` the pattern's generated bytecode. Its core tier, 32
cells, takes the same workloads and literals and both methods:

```bash
java --add-modules jdk.incubator.vector -cp "$benchmark_classpath" \
  org.openjdk.jmh.Main 'BenchmarkTrinoLikeRows\.' \
  -foe true \
  -p workload=english,nginx,loghub,gharchive \
  -p literal=low-none,mid-none,high-none,mid-selective \
  -p corpusDirectory=/path/to/trino-row-corpus
```

Its full suite runs every cell of every workload with the same per-workload
`-p literal` lists without their `dotstar-` IDs, which the `awk` command above
prints when `&& $column["group"] != "dotstar"` is added to its condition.

JMH reports ns per call, which is ns per row. Divide by the workload's mean
row length, `row_bytes / rows`, for ns per byte.

The [Trino/Joni comparator guide](../trino-joni/README.md) describes and runs
the benchmarks' twins: `BenchmarkTrinoRowsJoni` for Joni and
`BenchmarkTrinoLikeRowsTrino` for Trino's LIKE.

## Changing the workloads

A new corpus pin, token rule, band, pair rule, seed, hand-picked literal, or
cell pattern changes the measured workloads. Update the generator or
`trino-row-corpus.tsv`, regenerate, review every changed literal, and keep the
`@Param` lists of `BenchmarkTrinoRows`, `BenchmarkTrinoLikeRows`, and their
twins in step; `TestBenchmarkTrinoRows` checks the first against the manifests,
`TestBenchmarkTrinoLikeRows` checks the second against the first, and each
twin's verifier checks the twin against its Regulator benchmark. Results from
different manifests are not comparable.
