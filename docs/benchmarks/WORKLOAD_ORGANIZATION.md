# Benchmark workload organization

Classify workloads by what they measure, not by whether Regulator wins. Keep
ordinary application work separate from stress tests and engine diagnostics.

The report uses language as its top-level navigation, as defined in
[`LANGUAGE_COMPARISON_PLAN.md`](LANGUAGE_COMPARISON_PLAN.md). Each regex page
compares Regulator with the corresponding library: native RE2, JDK `Pattern`,
or Trino's Joni. LIKE has its own page against Trino `LikeMatcher`. The workload
groups below have separate weighting and summary rules within each language.

## Questions answered separately

The benchmark data has four workload populations. They are never combined
into a row-count-weighted overall score.

| Population | Purpose | Comparators | Headline use |
|---|---|---|---|
| Application-shaped scalar operations | Compiled patterns applied to changing, short and medium inputs | Native RE2, JDK Pattern, or Joni for the selected language; Joni for additional Trino operations | Primary application view |
| Bulk text search | Whole files, books, source trees, dictionaries, and large logs | Native RE2, JDK Pattern, or Joni for the selected language, where supported | Separate throughput view |
| Compilation | Cold and repeated pattern compilation | Comparator for the pattern language | Integrated lifecycle view; compile-only data in raw downloads |
| Diagnostics and stress | Algorithm probes, reported bugs, adversarial inputs, quadratic cases, cache pressure, and scaling | Applicable engines | Appendix and causal investigation only |

Trino SQL LIKE remains a separate Trino-specific population. It compares
Regulator with Trino's optimized and reference LIKE implementations and does
not participate in regular-expression summaries.

The LIKE lifecycle view compares against Trino `LikeMatcher`. Its `optimize`
argument permits DFA execution for eligible patterns; it is not a lifecycle
selector. Those patterns have adjacent DFA-enabled and disabled rows. Each row
uses the same setting for single use, which measures construction plus first
match, and multi-use, which measures warmed reuse. Patterns that cannot use the
DFA optimization are not duplicated.

## Application-shaped scalar operations

An application-shaped workload compiles one pattern and applies it to a frozen
rotation of independent Slice values. It must not repeatedly benchmark one
unchanging haystack. Each workload records:

- a stable identifier and workload family
- the source and license or an explicit `designed-control` origin
- exact pattern and input bytes
- input byte-length distribution and nonzero-Slice-offset coverage
- observed no-match, one-match, and repeated-match counts
- ASCII, Latin-1, or multi-byte UTF-8 content
- the operations and engines with equivalent semantics
- a checksum over inputs and normalized operation results

The suite models application operations, not a measured production distribution.
Its match rates, value sizes, and pattern mix are design choices, not claims
about Trino deployments. Production telemetry may later supply weights without
changing the underlying cases.

The shared everyday suite measures contains and count against each regex
language's comparator, using a common logical workload and an explicit input
and output-consumption contract. Regulator receives Slice inputs; JDK Pattern
receives prepared Java strings with input conversion outside timing.

The additional Trino operation intersection covers contains, count, first or
Nth position, extract, extract-all, split, template replacement, and lambda
replacement.
Trino benchmark cases may be translated into this suite when Regulator and
Joni implement equivalent semantics. The manifest records the exact Trino
commit and source case. A case that cannot be translated without changing its
meaning is excluded with a reason; it is not converted into a performance
failure.

The additional Trino operations compare only against Joni. Native RE2 has no
identical Slice-list, SQL-position, split, or lambda-replacement API. The shared
everyday suite and RE2 bulk workloads provide native RE2 comparisons without
inventing a C++ facade for Trino-specific operations.

## Bulk text search

Rebar workloads over books, source code, dictionaries, and production-derived
corpora answer whole-corpus throughput questions, not scalar SQL-value latency
questions. Ordinary document searches, tokenization, and log parsing appear in
the report's text processing section. Synthetic inputs such as exhaustive
Unicode code-point sequences belong in adversarial and stress results.

The pinned Rebar definitions and expected results remain unchanged. The
checked-in taxonomy rules classify every curated and extended workload before
measurement. Exhaustiveness and uniqueness are tested so a new Rebar case
cannot silently inherit a headline role.

A separate checksummed Joni applicability ledger classifies every Rebar
workload/model identity as compatible or not compatible. Compatible rows
receive same-host measurements; a row that exceeds the semantic timeout is
reported as a runtime `did-not-finish` outcome. Neither incompatibility nor a
timeout manufactures a numeric comparison.

## Diagnostics and stress

This population includes:

- optimizer and engine-route probes
- synthetic RSC and Folly cases
- reported regressions and historical bugs
- explicitly slow, quadratic, and ReDoS inputs
- cache, memory, concurrency, and fallback tests
- fixed-size scaling and generated-code investigations

Adversarial and stress results remain visible in folded sections. Internal
diagnostics remain in the preserved raw evidence and are excluded from the
developer-facing preliminary report. Neither population's count, median, nor
direction changes an application or bulk-text summary.

## Reporting and weighting

Rows retain their workload identity, operation, source, and comparator. The
report identifies which engine is incompatible, or which did not finish, and
places the explanation in the row details. Neither status is a numeric loss.

Within each regex language page, report in this order:

1. application-shaped scalar operations
2. integrated single-use and multi-use pattern lifecycle
3. Trino-specific operations where available
4. text processing workloads
5. folded adversarial and synthetic stress workloads

LIKE has its own integrated lifecycle table. Put ordinary prefix, suffix,
exact, and contains shapes first and dense-false stress last, keeping DFA
variants adjacent. Compile-only results remain in raw downloads rather than a
separate table.

Display grouping follows the workload's purpose, not its timing or source
directory. Reviewed display exceptions do not alter recorded populations or
measurements; the renderer and its tests define those exceptions.

The README shows separate everyday-expression and text-processing summaries
for C9g with native access enabled. It uses the dashboard's classification and
equivalent-work checks, then computes the geometric mean of the eligible row
candidate/comparator time ratios. Each row has equal weight. Family and
collection labels do not change that weight; families with more eligible rows
contribute more to the summary. This describes the benchmark collection, not
an application workload distribution or total elapsed time saved.

Each row keeps its existing aggregation of paired-host measurements. Do not
pool raw timing repetitions across workloads. Compute the geometric mean in
log space to avoid overflow and underflow. Only completed, comparable results
contribute; a timeout never becomes a numeric speedup. Timing variation and
host disagreement do not invalidate otherwise comparable measurements, so
those row ratios remain eligible. Individual report rows retain their warnings;
the aggregate is not a claim that every workload wins on every host.

Everyday regex summaries use reused contains and count. The LIKE summary uses
warmed matches, including both measured DFA settings where applicable, but
excludes the dense-false stress case. DFA-enabled and disabled rows each
contribute one ratio, just like other eligible rows.
LIKE has no separate text-processing suite. Its text-processing cell shows a dash.
Compilation and diagnostics have no headline aggregate.

## Evidence gate

Before an AWS measurement campaign:

1. validate taxonomy exhaustiveness and manifest checksums
2. run every workload and operation as a correctness test
3. compare every Regulator/Joni result through the pinned Trino implementation
4. compare the separate common RE2 engine intersections with pinned native behavior
5. verify changing input identity, result consumption, and tier-4 compilation
6. calibrate the bounded protocol on one representative from every distinct
   JIT or engine family

AWS remains the only performance authority. Local execution may compile and
verify semantics, manifests, row discovery, and report logic, but local timing
is not retained or used for decisions.
