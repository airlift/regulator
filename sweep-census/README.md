# Route census harness

An untimed route census of every published 2.1 report row (4,864 rows, 2,424 units over both memory modes) on
the host's architecture. Each unit runs its row's benchmark once cold and again in the steady state, in its own
JVM, with a probe agent that counts entries to every Regulator method. Method anchors then credit inventory
components: which components *ran* on each row, and which were *admitted* (built at compile time) without
running. Nothing is timed. The simplification sweep ran the census on aarch64 (dain-1); this directory lets
an x86 host (r8i) repeat it, because some components are chosen only on x86 or with 64-byte vectors.

## Run

Prerequisites: JDK 25 (`java`, `javac`, `jar` on the path), Python 3.12 or later, Maven access (the wrapper
downloads it), and git with tag `2.1` in this checkout. You also need Rebar at the pinned commit:

```
git clone https://github.com/BurntSushi/rebar.git
git -C rebar checkout 463d00f31887e84c38467805b9e3122c314b9521
```

Then, from this checkout's root:

```
sweep-census/run.sh --rebar-root <rebar> --parallelism 6 --out <out> \
    --compare sweep-census/expected/component-rows.tsv [-Dmaven.repo.local=<repo>]
```

Any `-D` argument goes to Maven. Rerunning with the same `--out` resumes: finished inputs and units are kept.
Use a new `--out` for a fresh run.

What it does:

1. Builds the checkout (`./mvnw -DskipTests -Dair.check.skip-all=true test-compile`) and writes its test
   classpath (`dependency:build-classpath`).
2. Builds the probe jar, compiles the census drivers against the checkout's classes, writes the method table
   (`methods.tsv`), and checks the anchors against it (`anchor-check.md`).
3. Regenerates the inputs:
   - extracts the 2.1 report data and benchmark tools with `git archive 2.1` into `<out>/tag-2.1`, because
     main's report data may differ;
   - expands the Rebar registry into per-language workloads (`expand_rebar.py`), checking each against its
     frozen pattern and haystack digests;
   - prepares the lifecycle corpus with the repository's own `collection.py prepare` at tag 2.1;
   - maps every report row to a unit (`build_units.py`), and stops unless there are 2,424 units.
4. Runs every unit with the probe (`run_census.py`).
5. Analyzes the census (`analyze.py`, `component_rows.py`, `summarize.py`).
6. With `--compare`, writes `DIFF.md` (`compare.py`).

Resources: each unit is one JVM with `-Xmx6g`. On dain-1 the largest resident size seen (sampled every
5 seconds) was 1.2 GiB, so 2 GiB per unit is ample and 6.5 GiB is the bound (39 GiB at parallelism 6). The
out directory takes 1.3 GB, of which the Rebar workloads are 1.1 GB. On dain-1 (16 cores, shared with other
work at a load average near 90) the whole run took 21 minutes at parallelism 6, 20 of them in the census; the
same census took 8 minutes on a quieter host.

## Output

In `<out>`, with `<arch>` from `uname -m`:

- `component-rows-<arch>.tsv`: every (component, row identity) pair, marked ran or admitted. A row identity is
  (language, case, operation). Same columns as the sweep's component-rows.tsv. Tiers and ratios are from the
  report rows of the matching platform: r9g on aarch64, r8i on x86_64.
- `routes-<arch>.tsv`: one line per report row, in the sweep's ROUTES.tsv schema. Both platforms' rows carry
  this host's census.
- `SUMMARY.md`: per component, the identities it ran on and was admitted on; the unit failures; how many units
  entered each platform-specific method; the anchor check.
- `DIFF.md` (with `--compare`): every component whose ran identities differ from the comparison file, with the
  differing rows, then admitted-only changes and units whose census status differs. It stays under about
  30 KB by cutting the row lists, keeping the counts. This is the file to send back from an x86 run.
- `census/`: raw unit output (`M` method entries and `R` DFA route counts per phase, `S` static route state of
  each compiled pattern); `RUN.txt`: host, versions, commits and this invocation's start and finish (a resumed run rewrites it).

## Expected result

`expected/` holds the aarch64 result on main `eef7c0659` from dain-1, with the Nov 16 anchor revision:

- `ROUTES.tsv` is the sweep's ROUTES-main-anchors.tsv.
- `component-rows.tsv` is that file folded by `component_rows.py`. The sweep's own component-rows.tsv predates
  the anchor revision; it differs only in S8 (+13 ran, +4 admitted), Q2 (8 ran moved to Q4B), Q4B (+8 ran) and
  K1 admitted (-110). `component_rows.py` reproduces the sweep's file byte for byte from its ROUTES.tsv.

On aarch64, `DIFF.md` against `expected/component-rows.tsv` reports no differences, and the routes table is
identical to `expected/ROUTES.tsv`. SUMMARY.md lists 18 unit failures, the same at 2.1 and on main, each in
both memory modes:

- 14 exit at setup because the pattern does not parse in that language: Java and Trino
  `wild/ruff/{noqa,shebang,string-quote-prefix}`, and Trino `reported/i13-subset-regex/huge-ascii-nosuffixlit`.
- 4 finish but fail the benchmark's check of its answer against Rebar's RE2 count, which the report marks
  not-compatible: Trino `curated/08-words/all-english` and `unicode/word/boundary-any-english`. The census still
  records their route.

## Anchors

`py/anchors.py` maps each component to three regular expressions over `Class.method`: run (entered in the
steady state), build (entered at setup or compile), and size. `analyze.py` adds admission from the static dump
and these rules:

- S8 is admitted where the DFA built usable fixed-distance candidates (`isDenserThanStartFilter`), in any phase.
- Q4B is split from Q2 where the dump marks the long-window search as `longWindowLiteral=other`.
- K1 admission skips the `unsupported()` placeholder.
- L5X is admitted where the window literal search stepped aside (see below).

### x86-only paths

These anchors cover paths that only x86 (or 64-byte vectors) takes, so aarch64 credits none of them:

| component | anchor | x86 path |
|---|---|---|
| E3 | `Dfa.searchForwardPairs(AfterMiss\|AfterMatch\|FromState)?` | object paired rows; every non-aarch64 `os.arch`. aarch64 runs the flat form, E3F, instead |
| S1 | `VectorPrefixScanner.findPairMasked` | masked pair compare of the literal prefix scan, with 64-byte vectors |
| S9 | `VectorByteSetScanner.findLiteralNibblesOneLoad` (in `findLiteralNibbles\w*`) | one-load nibble filter, 512-bit with AVX-512 VBMI |
| S9 | `VectorByteSetScanner.findTwoExactLiteralsXor` (in `findTwoExactLiterals\w*`) | XOR two-literal filter: 64-byte vectors on x86, also 128-bit SVE |
| L5X | build `WindowLiteralSearch.keepsOneOrTwoLiteralStartFilter`, admitted where it was entered more often than `WindowLiteralSearch.<init>` | the window literal search steps aside for a one- or two-literal start literal filter at 64 lanes (L5 then shows fewer rows) |

Some 64-lane choices are made at compile time, have no method of their own, and show up only as different rows
for an existing component:

- S4: a folded start run that every match starts with is left out at 64 lanes. The DFA keeps its start bytes,
  so S4 shows fewer admitted and ran rows.
- S9: the start literal filter takes two literals for rare start bytes only at 64 lanes, so S9 shows more rows.
- K1: class-counter runs step by 64 bytes rather than 128, with the same methods.

aarch64's platform forms (the SVE kernels and the flat paired rows) each have an x86 counterpart credited to the
same component. The exception is E3F against E3, which is split by design. `check_anchors.py` checks this
against the built classes; `PLATFORM_FORMS` in `anchors.py` lists the pairs.

## Files

- `probe/`: the census agent (`censusprobe.Probe`), counting method entries of classes loaded from
  `target/classes/` and, with `-Dio.airlift.regulator.dfa.route-counts=true`, DFA route counts.
- `drivers/`: `CensusRun` (runs one JMH benchmark method untimed, set up as JMH would), `LikeCensus` (the LIKE
  benchmarks), `RouteState` (the static route state dump).
- `tools/MethodTable.java`: class, method, source file, line span and bytecode size of every compiled method.
- `py/`: the scripts named above.
