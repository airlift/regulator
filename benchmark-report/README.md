# Benchmark report

This directory contains the source and versioned data for Regulator's static
benchmark report. The build writes a Pages-ready site to
`target/benchmark-report/benchmarks/`.

Each language page compares its Regulator API with the corresponding library:
native RE2, JDK `Pattern`, Joni, or Trino's SQL LIKE route. The default uses
native memory on R9g for released-artifact captures or C9g for historical
captures. The URL fragment stores the language, CPU, and memory
selection, even when opening a local file.

Local checks require Node.js 22 or newer and Python 3.11 or newer on `PATH`.

The page loads a small `data/manifest.json`, then fetches one immutable JSON
file for the selected language, CPU, and memory setting. There are 24 page
files. Each includes the tables and ordinary row details, so expanding a row
needs no further download. Previously visited selections are cached in memory.
After a page request fails, the next attempt revalidates the manifest so an open
tab can recover when a deployment replaces the page files. Cached pages are
identified by selection and immutable filename, not selection alone.
To import an already curated result without changing page code:

```bash
npm ci --prefix benchmark-report
python3 benchmark-report/scripts/report_data.py import path/to/benchmark-data.json
python3 benchmark-report/scripts/report_data.py update-readme
cd benchmark-report
npm run check
```

README generation invokes the report's TypeScript summary through Node.js, so
install the report dependencies before running `update-readme` or `check`.
The summary uses the same workload classification and equivalent-work checks
as the dashboard. It has separate everyday and text-processing columns, each
using R9g native-access measurements for released-artifact captures or C9g for
historical captures. It takes a geometric mean of the eligible row time ratios,
with each row weighted equally. Each row retains its existing
aggregation of paired-host measurements; raw repetitions are not pooled.
Everyday regex results use reused contains/count operations. LIKE uses warmed
matches, excludes `ORDERED_DENSE_FALSE`, and includes both measured DFA settings
where applicable. Text processing follows the visible text-processing section;
LIKE has no separate text-processing summary. Non-comparable, incompatible,
unfinished, compilation, and synthetic-stress results do not enter either column.
All valid numeric rows contribute to the summary, including uncertain near
ties. Current results use mean operation costs and approximate 95% intervals
from paired hosts and whole processes. Historical captures retain their
original estimators and warning labels. See
[measurement quality](../docs/benchmarks/MEASUREMENT_QUALITY.md) and the
[statistical replay](analysis/README.md) for definitions and reproduction.

Import checks platform and memory-mode coverage, publication scope, and the
pinned workload-source checksum. It stores the data as deterministic gzip with
a hash of the uncompressed JSON and updates the manifest. Both JSON and
`.json.gz` inputs are supported. It preserves previous publication entries;
choosing which reports to publish is a separate decision.

Compression keeps the checked-in data below GitHub's large-file warning. Import
and CI reject stored files of 50 MiB or larger. The site build derives compact
page files from that evidence without changing the input data. GitHub Pages
compresses JSON responses over HTTP; browsers decompress them automatically.
No client-side compression library is needed.

The footer links directly to a public `.json.gz` download containing the frozen
publication inventory with every configuration, supplied pattern, per-host
measurement, provenance, report classification, and commentary. Browsing never
fetches that file. Original publication captures are also retained as gzip in
the site artifact. Generated page files are not substitutes for the public
evidence download.

For a released artifact, `scripts/build_capture_data.py` first builds the
complete accepted campaign for the private archive. `scripts/publication_data.py`
then matches the frozen public workload source by case, operation, language,
CPU family, and memory mode. The projection preserves the source order, workload
content, and published explanations while taking measurements, uncertainty, and
provenance from the final campaign. It records the source candidate and exact
file checksum in `publicationScope`. Import validation rejects missing, extra,
reordered, or changed public workloads.

Campaign-only observations do not enter the release manifest, Pages artifact,
standalone report, or public download. Preserve them through
`docs/benchmarks/results-1.0/raw-archives.json` with raw diagnostics, failed and
superseded attempts, and the complete captured campaign. The compact measurement
summary and applicable timing flags are added to the public layout. The all-page
checks compare the frozen workload order and details, render every fold-down,
and verify that public measurement evidence is unchanged.

Patterns larger than 4,096 UTF-8 bytes have a 240-codepoint preview in the UI,
their full byte size, and a pinned benchmark-source link where available.
Mapping-only captures retain their existing preview and declared byte size.
The download preserves full patterns when supplied by the capture; a preview-only
capture links to its benchmark source where available. Row detail contents are
rendered only while expanded. The largest example is `dictionary/search/english-10` in the
text processing table.

`npm run check` compares all 24 compact tables with the original data, verifies
the public download and Pages file inventory, and checks selection caching and
retry behavior. Current
page files must stay below 1,100 KB uncompressed and 210 KB gzipped. These limits
apply to each page's data, not the shared JavaScript and stylesheet.

Keep released-version reports when historical comparisons are useful.
Development snapshots belong in an external evidence archive, not the release
manifest. Before removing a snapshot, archive its exact bytes, manifest entry,
and SHA-256 so it can be recovered. Leave the current dataset and underlying
raw evidence unchanged. Schema tests use small synthetic fixtures instead of
old measurement datasets.

## Local standalone report

For language-organized data, the build also produces
`target/benchmark-report/benchmarks/regulator-benchmarks.html`. Open that file
directly in a browser. Its script, styles and public report data are embedded;
it does not need a local server or network access. This offline artifact remains
large by design, and its download button exports the public JSON locally. Normal
site browsing uses the small per-selection files instead.

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

## Historical preliminary 1.0 results

The preliminary snapshot combined existing campaign results with targeted
follow-ups. Its page header and README label used a `publication` object
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
says so rather than assigning a faster/slower claim. Released-artifact results
form a separate versioned capture of the same library release. Keep the
preliminary capture's original identity and label.

## Capture requirements

`scripts/build_capture_data.py` builds the language report from the complete
verified language export, baseline reduction and LIKE lifecycle supplement.
The legacy input path reproduces preliminary captures. For a released campaign,
provide both the frozen publication workload source and a separate private
complete-capture destination:

```bash
python3 benchmark-report/scripts/build_capture_data.py \
  --released-campaign campaign-paths.json \
  --publication-workloads benchmark-report/data/frozen-publication.json.gz \
  --measurement-followup /private/archive/replacement-report.json.gz \
  --analysis-inputs /private/archive/publication-analysis-inputs.jsonl.gz \
  --publication-analysis-output /private/archive/publication-analysis-inputs-public.jsonl.gz \
  --complete-output /private/archive/complete-campaign.json \
  --output /tmp/publication.json
python3 benchmark-report/scripts/report_data.py import /tmp/publication.json
```

The public and complete outputs must differ. The complete output is evidence to
archive and index; it is never an input to `report_data.py import`. The release
build first reconstructs the complete original campaign, then applies the saved
complete-campaign measurement follow-up. It verifies unchanged row identity and
workload structure, exact released-artifact identity, complete normalized-input
coverage, and the recorded analysis-input SHA-256 before copying the approved
mean estimates, uncertainty, predecessor results, hardware, and provenance.
The analysis output is a deterministic projection onto the published numeric
rows. The report records both its hash and the source complete-campaign input
hash, so the public replay rejects missing, extra, or substituted comparisons.
The paths file identifies `release_version`, `candidate_provenance`, both
`language` plan/export pairs, and `baseline` primary, optional confirmation,
reduction, and campaign ID. LIKE lifecycle comes from accepted baseline shards.
Each language entry and the baseline entry may specify its own
`candidate_provenance`, overriding the common collector pin. This permits a
baseline-only protocol repair to retain unaffected language collection. Every
component still requires one exact collector commit and archive, the same
released production tree and JAR, and its complete independent-host matrix.
The report records collector identities by suite.
The importer checks published-JAR receipts separately from collector identity,
R-family topology, manifest and receipt checksums, independent-host coverage,
and lifecycle pairing. Historical C-family datasets retain their labels.
It rejects missing rows and verification failures. Timeouts and incompatibilities remain
distinct nonnumeric results.

The report preserves host ranges and timing-precision warnings. A range is not
a confidence interval. Single-use and multi-use results remain separate, and
crossover estimates are suppressed when either phase has unresolved timing
variation. Internal compiler phases, allocation and memory-census results
remain in the separately preserved complete baseline evidence.

Every API page combines single-use and multi-use timings in its pattern
lifecycle table. The page order is everyday operations, pattern lifecycle,
Trino-specific operations where available, text processing, then collapsed
adversarial and synthetic stress groups. Compile-only measurements retained by
the frozen publication inventory remain in the public JSON download, without a
separate construction or compiler-stress table.

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
  whole-input captures and the experimental I787 rewrite are public-download-only.

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

The released 1.0 import uses `src/release-1.0-workload-notes.json`, pinned to
the release source commit, and embeds its explanations in each applicable row.
These describe the released engine paths without carrying forward timing claims
from older machines. Historical captures keep their original commentary. Review
the final ratios, absolute differences and uncertainty alongside these source
explanations; an implementation difference alone does not prove a timing cause.

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
