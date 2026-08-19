# Benchmark report

This directory contains the source and versioned data for Regulator's static
benchmark report. The build writes a Pages-ready site to
`target/benchmark-report/benchmarks/`.

Each language page compares its Regulator API with the corresponding library:
native RE2, JDK `Pattern`, Joni, or Trino's SQL LIKE route. The default is C9g
with native memory. The URL fragment stores the language, CPU, and memory
selection, even when opening a local file.

Local checks require Node.js 22 or newer and Python 3.11 or newer on `PATH`.

The page loads `data/manifest.json`, then fetches the selected immutable JSON
file. To update results without changing page code:

```bash
python3 benchmark-report/scripts/report_data.py import path/to/benchmark-data.json
python3 benchmark-report/scripts/report_data.py update-readme
cd benchmark-report
npm ci
npm run check
```

Import checks platform and memory-mode coverage, stores the data as deterministic
gzip with a hash of the uncompressed JSON, and updates the manifest. Both JSON
and `.json.gz` inputs are supported. It preserves previous entries; choosing
which reports to publish is a separate decision.

Compression keeps the checked-in data below GitHub's large-file warning. Import
and CI reject stored files of 50 MiB or larger. The site build decompresses the
data and rewrites its output manifest to point to ordinary JSON files. Browser
loading, raw downloads, and the standalone report need no compression support.

Keep released-version reports when historical comparisons are useful.
Development snapshots belong in an external evidence archive, not the release
manifest. Before removing a snapshot, archive its exact bytes, manifest entry,
and SHA-256 so it can be recovered. Leave the current dataset and underlying
raw evidence unchanged. Schema tests use small synthetic fixtures instead of
old measurement datasets.

## Local standalone report

For language-organized data, the build also produces
`target/benchmark-report/benchmarks/regulator-benchmarks.html`. Open that file
directly in a browser. Its script, styles and report data are embedded; it does
not need a local server or network access. The normal site build still keeps
the data separate so updates do not require page-code changes.

`npm run check` validates the language-organized data and retained releases,
the README summary, table rendering and numeric sorting. It also builds a
clearly labeled test-fixture standalone file and checks embedded-data escaping.
Fixture results are not benchmark evidence.

Report data uses schema version 2. Earlier development formats are not supported.

For private report-design work, `scripts/build_review_preview.py` combines an
existing report with saved outlier measurements. Run it with `--help` for the
input paths, then pass its output directory to `scripts/build.mjs` using
`--data-directory` and a separate `--output-name`. It does not update the
versioned data or release manifest.

The preview uses matching same-host comparisons, keeps untouched results, and
excludes internal diagnostics while retaining adversarial workloads. Its tables
show point estimates without precision annotations; the raw download preserves
warnings, predecessor results, and mixed-source provenance. This preview is not
a new full run or release qualification.

## Preliminary 1.0 results

The preliminary snapshot combines existing campaign results with targeted
follow-ups. The page header and README label it using a `publication` object
with `status: "preliminary"`, `sourcePolicy: "mixed-development-revisions"`, and
a short `note`. It does not use the private `reviewPreview` presentation.

Keep the base capture identity in `sources` and each replaced row's actual
source and predecessor results. Do not relabel mixed measurements as a run
against one final commit.

Only explicitly preliminary reports may contain targeted rows with fewer than
three independent hosts, and those rows must identify their measurement source.
Final-report validation still requires at least three independent hosts. Missing
data, incompatible work, and verification failures do not become comparisons.

Preliminary tables show median estimates with variation in the expandable row
details and raw download. When hosts disagree about the winner, the comparison
says so rather than assigning a faster/slower claim. The full released-artifact
campaign replaces this snapshot without changing the library release.

## Capture requirements

`scripts/build_capture_data.py` builds the language report from the complete
verified language export, baseline reduction and LIKE lifecycle supplement.
It checks immutable source identities, manifest and receipt checksums, raw
LIKE timings, independent-host coverage and lifecycle pairing. It rejects
missing rows and verification failures. Timeouts and incompatibilities remain
distinct nonnumeric results.

The report preserves host ranges and timing-precision warnings. A range is not
a confidence interval. Single-use and multi-use results remain separate, and
crossover estimates are suppressed when either phase has unresolved timing
variation. Internal compiler phases, allocation and memory-census results
remain in the separately preserved complete baseline evidence.

Every API page combines single-use and multi-use timings in its pattern
lifecycle table. The page order is everyday operations, pattern lifecycle,
Trino-specific operations where available, text processing, then collapsed
adversarial and synthetic stress groups. Compile-only measurements remain in
the raw JSON download, without a separate construction or compiler-stress table.

LIKE's default row order starts with prefix, suffix, exact and contains checks,
then ordered literals and fixed-character gaps, then one-character wildcards.
The dense-false stress case is last. DFA variants stay adjacent, and clicking a
column heading overrides this reading order. Timing results do not determine
the default order.

Display grouping follows the workload's purpose, not its timing or source
directory. `workloadSection` in `src/language-report.tsx` applies the reviewed
exceptions without changing the recorded population or measurements:

- Original I787 keyword search belongs in text processing.
- Unicode codepoint and overlapping-word probes belong in synthetic stress.
- Imported Leipzig/Sherlock I13 variants belong with reported regressions.
- Contiguous-letter captures, empty-pattern line iteration, redundant
  whole-input captures and the experimental I787 rewrite are download-only.

The download includes each row's `reportSection` alongside its original
classification. Production-derived tokenization, log parsing, dictionary,
capture and word-search workloads remain visible regardless of performance.
Detailed methodology and source provenance are available in that download,
not as an expandable block in the page footer.

Expanded rows use `src/workload-notes.json` to explain non-obvious workloads and
notable performance results. Workload descriptions come from pinned Rebar
definitions; performance notes come from inspected engine paths and existing
investigations. Qualify plausible explanations rather than presenting them as
proven instruction-level causes. Select notes by workload, language, and
operation, not by whether Regulator wins.

Notes about outlier repairs require measurements of the repair and must not
appear on older results. Lifecycle rows label runtime explanations as multi-use
context. Unsupported cases get descriptions but no performance claims. The raw
download includes the selected notes. Review them whenever the measured
implementation or workload mappings change.

`scripts/qualify_capture_data.py` applies the separately collected Trino/Joni
precision follow-up. It requires three distinct paired hosts per CPU, suite
and memory mode, rechecks raw artifacts against accepted receipts, and
preserves the earlier result in each replaced row. Repeat `--pilot` or
`--confirmation` when recovery was recorded in a separate directory. A split
memory-mode recovery must include Joni on each host. Its collector-only source
change requires both source archives and the recorded recovery protocol; the
import checks that all engine and benchmark files are byte-identical.
Failed host collection is not an engine timeout and supplies no numerical
result. The original shorter-interval cohort is never blended into the new
median.

`scripts/public_api_report.py` applies a qualified public-operation follow-up.
It requires a complete primary raw-validation index and the original and
selected workload manifests. It checks source and workload identities, keeps
three independent paired hosts per case and CPU, and retains predecessor
results with their earlier API-path labels. It replaces only the selected
rows; untouched rows keep their original measurements and source provenance.
Pilot measurements and failed infrastructure attempts never enter its medians.

## GitHub Pages

The GitHub Pages workflow publishes the build artifact under `benchmarks/`, so
the repository site URL is:

<https://airlift.github.io/regulator/benchmarks/>

GitHub's ordinary source-file viewer does not execute the report. A repository
administrator must select **GitHub Actions** as the Pages source once. After
that, pushes to `main` that change the report, report data, README summary, or
workflow rebuild and deploy the static artifact. Pull requests build and check
the report without deploying it.
