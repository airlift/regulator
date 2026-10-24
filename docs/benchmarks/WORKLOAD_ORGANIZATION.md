# Benchmark workload organization

Classify workloads by what they measure, not by whether Regulator wins. Keep
ordinary application work separate from stress tests and engine diagnostics.

The report uses language as its top-level navigation, as defined in
[`LANGUAGE_COMPARISON_PLAN.md`](LANGUAGE_COMPARISON_PLAN.md). Each regex page
compares Regulator with the corresponding library: native RE2, JDK `Pattern`,
Trino's Joni, or the Rust `regex` crate. LIKE has its own page against Trino
SQL LIKE. The workload groups below have separate weighting and summary rules
within each language.

## Questions answered separately

The benchmark data has six workload populations. They are never combined
into a row-count-weighted overall score.

| Population | Purpose | Comparators | Headline use |
|---|---|---|---|
| Application-shaped scalar operations | Compiled patterns applied to changing, short and medium inputs | Native RE2, JDK Pattern, Joni, or the `regex` crate for the selected language; Joni for additional Trino operations | Primary application view |
| Specialized engine and compiler | Representative patterns on the regular engine, the specialized engine, and compiled, as warmed steady-state operations; Trino regex and LIKE today | The page comparator: Joni for Trino regex, Trino SQL LIKE for LIKE | Separate report section; excluded from headlines |
| Bulk text search | Whole files, books, source trees, dictionaries, and large logs | Native RE2, JDK Pattern, Joni, or the `regex` crate for the selected language, where supported | Separate throughput view |
| Compilation | Cold and repeated pattern compilation | Comparator for the pattern language | Integrated lifecycle view; compile-only data in raw downloads |
| Literal-probe distributions | One literal counted over a pinned corpus with Regulator's default and a corpus-matched byte-frequency distribution | Native RE2, JDK Pattern, Joni, or the `regex` crate for the selected regex language | Separate report section; excluded from headlines |
| User-relevant adversarial and stress | Pathological inputs, reported failures, quadratic cases, and resource pressure that users can encounter | Applicable public APIs | Folded report section; excluded from headlines |

Internal route probes, compiler experiments, synthetic scaling sweeps, memory
censuses, and capacity checks are diagnostic evidence rather than a seventh
publication population. Run them only when a qualification result or an
explicit investigation requires them. Give that work its own frozen inventory,
budget, and archive; do not make it part of every release campaign.

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

### Specialized engine and compiler

Specialized-pattern workloads show what happens to representative patterns
when Regulator's specialized engine and compiler apply. The page names three
variants. The regular engine is Regulator with scan plans off. The specialized
engine compiles an eligible pattern to a scan plan and interprets it; code calls
it the interpreter. Compiled is the opt-in compiler, enabled with
`setGenerateBytecode`, which turns that plan into bytecode generated for the
pattern; code and benchmark names call it generated. Each workload measures one
ordinary operation with each variant beside the page's comparator. The regular
engine and the specialized engine make the public call; the compiled pattern is
[const-bound](SCAN_PLAN.md#benchmarks) the way an engine like Trino binds
it, the best case such an engine sees. Each pattern runs in its own JVM, so no
call site sees another pattern. LIKE has no regular-engine variant.

Case `trino-specialized/<workload>` is the specialized engine for Trino regex,
and `trino-like-specialized/<workload>` for LIKE. Appending `/generated` gives
the compiled pattern, and `/regular-engine`, where measured, the regular
engine.

This population answers what a pattern gains from the specialized engine and
from the opt-in compiler. It reports warmed steady-state operations only;
compile and generation costs are not part of it. These rows carry their own
`specialized-patterns` population and never enter a headline summary. Trino
regex and LIKE have them today. Another language, against a comparator the
baseline already reduces, needs no report change: it adds its catalog rows to
`specialized-patterns.tsv`, with that comparator and case prefix, and the
campaign code that measures them, which is a benchmark class, the branch of
`generate-manifest.py` that writes its manifest rows, and its shard's handler in
`run-shard.sh`. It then regenerates `row-protocol.tsv` with
`generate-row-protocol.py` and `units.tsv` and `unit-rows.tsv` with
`generate-units.py`.

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

### Literal-probe distributions

Literal-probe workloads show what a caller gains by describing its input's
byte frequencies. Each counts every match of one literal over a pinned,
checksummed corpus twice: once with Regulator's default uniform distribution
and once with a distribution matched to the corpus, either a preset or one
learned from separate sample lines of the same kind of log. Native RE2, JDK
Pattern, Joni, and the `regex` crate have no such option and run unchanged
against both. The learned distribution is built during benchmark setup, outside
timing.

These rows carry their own `literal-probe` population. They are a small,
deliberately chosen set rather than a sample of bulk text, so they never enter
the text-processing summary. LIKE has no byte-frequency option and no
literal-probe rows.

## Adversarial workloads and internal diagnostics

The public adversarial and stress population contains cases that help users
understand behavior they may encounter, even when the input is deliberately
pathological. Examples include reported regressions, ReDoS-shaped inputs,
quadratic replacement cases, and bounded cache pressure exercised through a
public API.

The internal diagnostic inventory includes:

- optimizer and engine-route probes
- synthetic RSC and Folly cases
- fixed-size and parameterized scaling sweeps
- compiler, cache, memory, concurrency, and fallback probes
- generated-code investigations

Public adversarial and stress results remain visible in folded sections.
Internal diagnostics stay in the private evidence archive and are absent from
the public report and its download. Neither population's count, median, nor
direction changes an application or bulk-text summary.

Classify by purpose before collection. A diagnostic result does not become a
release benchmark because it is favorable, complete, or useful to a later
analysis. Promote a workload only through a reviewed change to the frozen
publication inventory, based on a user-facing question and independently of
its measured outcome.

## Reporting and weighting

Rows retain their workload identity, operation, source, and comparator. The
report identifies which engine is incompatible, or which did not finish, and
places the explanation in the row details. Neither status is a numeric loss.

Within each regex language page, report in this order:

1. application-shaped scalar operations
2. specialized engine and compiler where measured
3. integrated single-use and multi-use pattern lifecycle
4. Trino-specific operations where available
5. text processing workloads
6. literal-probe distributions
7. folded adversarial and synthetic stress workloads

LIKE has its own integrated lifecycle table, preceded by the specialized engine
and compiler where measured. Put ordinary prefix, suffix, exact, and contains
shapes first and dense-false stress last, keeping DFA variants adjacent.
Compile-only rows retained by the frozen publication inventory remain in the
public download rather than a separate table.

Each literal-probe row pairs the default and tuned measurements of one
workload. It shows both ratios against the comparator and the
tuned-versus-default ratio, computed by dividing those two comparator-relative
ratios so that differences between the hosts that measured them cancel. Its
interval divides the far ends of the two ratio intervals, so a gain that
neither interval clearly supports reads as no clear difference.

Each specialized-pattern row pairs the variants of one pattern and operation.
It shows each variant's time and each later variant's gain over the variant
first in column order, computed and bounded like a literal-probe gain, then the
last variant against the comparator. The page's measured variants, not its
language, decide the variant columns.

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

## Release evidence tiers

A release keeps three deliberately separate artifacts:

1. The public report and public download use one frozen publication inventory.
   The download has the same rows as that inventory, including rows intentionally
   hidden from the page layout; it does not contain campaign-only diagnostics.
2. Measurement-quality evidence supports statements about uncertainty,
   repeatability, and platform controls. Keep it narrowly scoped to the claims
   it tests and link the resulting assessment from the report.
3. The private campaign archive preserves internal diagnostics, failed and
   superseded attempts, raw process data, profiles, receipts, and the complete
   capture needed for later audit.

The publication generator projects final measurements onto the frozen public
inventory before import. Validation rejects extra rows, missing rows, reordered
identities, or changed workload content. This makes the public download a
reproducible user-facing data product while the private archive preserves the
broader investigation record.
