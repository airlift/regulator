# Language-organized benchmark plan

The report groups results by pattern language, comparing each Regulator API
with the library a developer would normally use for that language.

Collection and reporting are implemented. The
[qualification plan](QUALIFICATION_PLAN.md) defines the full released-artifact
campaign; [the release results](RESULTS_1.0.md) record its coverage and findings.
This document does not authorize a run.

Follow [workload organization](WORKLOAD_ORGANIZATION.md) for classification
and weighting. Within each language, keep ordinary execution, lifecycle,
language-specific operations, and adversarial or compiler-stress workloads
separate.

## Comparator matrix

| Language | Regulator frontend | Headline comparator |
|---|---|---|
| RE2 | `Re2` | Pinned native RE2 |
| Java regular expressions | `JavaRegexp` | The selected JDK's `java.util.regex.Pattern` |
| Trino regular expressions | `TrinoRegexp` | Pinned Airlift Joni from the selected Trino revision |
| Trino SQL LIKE | `TrinoLikePattern` | Trino's SQL LIKE execution route |

LIKE compares directly with Trino's Slice-based matcher. For patterns eligible
for its DFA optimization, the report includes both enabled and disabled
settings, including single-use and reused measurements. SQL wrapper overhead
and input conversion are excluded.

Every report identifies the exact JDK build because `Pattern` implementation,
Unicode data, and generated code are properties of that JDK. Native RE2, Trino,
and Joni revisions remain pinned as required by the qualification protocol.

## Shared regex workload

The RE2, Java, and Trino-regexp sections use one shared logical workload
registry. A logical case has a stable identifier, input corpus, operation,
result demand, and normalized expected result. Each language entry records one
of these mappings:

- **identical:** the same pattern has the same meaning in both engines
- **translated:** a recorded language-specific pattern has equivalent meaning
- **not compatible:** an equivalent pattern or operation cannot be expressed

Translations are data in the manifest, not ad hoc changes in benchmark code.
Each records the source pattern, translated pattern, reason, and verifier
identity. A translation must preserve match existence, iteration, boundaries,
captures, and output semantics required by that operation.

The shared registry begins with the ordinary application-shaped and bulk-text
families that have equivalent contracts. Each language may then add a small
extension for language-specific constructs. Trino additionally retains its
contains, count, position, extraction, replacement, and split operations. LIKE
uses its existing dedicated workload because it is a separate language rather
than part of the regex intersection.

Stable logical identifiers allow two report tabs to show the same case under
different languages without relying on row position or display text.

## Representation boundary

Each engine receives the representation its public API accepts:

- Regulator receives precomputed UTF-8 `Slice` patterns and inputs.
- Native RE2 receives the identical pattern and input bytes where semantics
  permit.
- JDK `Pattern` receives precomputed `String` patterns and inputs containing the
  same logical Unicode text.
- Joni receives the precomputed byte representation used by the pinned Trino
  comparator.

The default JDK timing excludes UTF-8-to-`String` conversion. If an integration
needs to measure that cost, add a separate conversion-inclusive diagnostic.
Do not silently include it in an engine comparison.

Malformed UTF-8 and other byte-oriented cases that cannot be represented by a
Java `String` are `not compatible` with the JDK comparator. They are not
silently decoded, replaced, or removed from the report.

## Lifecycle measurements

Every regex language has a representative lifecycle matrix:

- **single use:** compile the pattern and execute one complete public operation
- **reused:** reuse the compiled pattern and the fastest realistic reusable
  matching state for that public contract
- **break-even:** when measurable, the number of reused operations needed to
  recover a single-use deficit

The harness records matcher construction and reset policy for each engine so a
fresh matcher is not accidentally compared with reusable state. Result demand,
capture demand, and input rotation remain equivalent.

The interactive report exposes both lifecycle views. The README and other
headline summaries assume reused patterns and leave single-use explanation to
the detailed report.

## Result states

Every planned row has exactly one final comparison state for each engine pair:

| State | Meaning | Published treatment |
|---|---|---|
| `compared` | Semantics were verified and measurement completed | Show score, ratio, and absolute or normalized delta |
| `not-compatible` | Equivalent semantics cannot be expressed for this engine | Show a neutral reason; do not time or aggregate |
| `did-not-finish` | The row is compatible, but compile or execution exceeded the fixed wall-clock limit | Show phase and limit; do not invent a ratio or aggregate |

JDK `Pattern` and Joni are backtracking engines. A compatible adversarial case
may therefore become `did-not-finish`; this is performance evidence, not a
language incompatibility. Each such row runs in an isolated process with a
predeclared timeout for its workload class and phase. The limits are frozen
before the campaign rather than adjusted after results are observed. The result
records whether compilation or execution exceeded the limit and reports, for
example, `execution exceeded 30 s` rather than a blank value.

`verification-failed` is never a publishable result state. It stops the
campaign for that row and requires correction of the implementation,
translation, expected result, or harness. Timing begins only after every
compatible row either passes deterministic verification or produces a
reproducible `did-not-finish` receipt. A final report containing a verification
failure or an unexplained missing row is incomplete.

## Verification gate

Before timing:

1. discover every manifest row and reject duplicate or missing logical IDs
2. classify every engine mapping as identical, translated, or not compatible
3. run each compatible pattern and operation through both engines, recording a
   reproducible `did-not-finish` receipt when the predeclared limit is exceeded
4. normalize boolean results, match counts, boundaries, captures, replacements,
   splits, and errors into a comparator-neutral receipt
5. verify input identity or logical-Unicode identity at the declared
   representation boundary
6. verify result consumption and route parameters for every timed method
7. run timeout calibration separately from the measured campaign

The final manifest and verification receipts are immutable campaign inputs.
Changing a translation, expected result, timeout, operation contract, or
comparator version creates a new campaign identity.

## Report structure

The interactive report provides a language selector with the selected language,
platform, and memory mode encoded in the URL. C9g and native-memory Regulator
remain the defaults. Each language view contains:

1. ordinary shared execution workloads
2. representative single-use and reused lifecycle results
3. Trino-specific operations where available
4. text processing workloads
5. folded adversarial and synthetic stress results

LIKE uses its integrated lifecycle table, with separate DFA-enabled and disabled
rows for patterns that can use Trino's DFA optimization. Compile-only results
remain in the raw download. Internal diagnostics are excluded from the
developer-facing preliminary report.

Rows retain stable logical IDs and sorting. `Not compatible` uses a neutral
visual treatment. `Did not finish` is visually distinct from incompatibility
and includes its timeout evidence. Neither state is displayed as a regression
or included in numeric summaries.

The README summary contains one reused-pattern directional row per
language:

| Language | Comparator |
|---|---|
| RE2 | Native RE2 |
| Java regex | JDK `Pattern` |
| Trino regexp | Joni |
| LIKE | Trino |

Exact lifecycle, operation, architecture, memory-mode, incompatible, and
timeout details remain in the interactive report.

## Collection implementation

The collection tools are implemented in
[`tools/re2-benchmark/language`](../../tools/re2-benchmark/language/README.md).
It covers the existing 13 everyday workloads, all three Regulator frontends and
their natural comparators, compilation, and single-use/reused contains and
count. It keeps immutable inputs, raw samples, allocation, phase-specific
verification receipts, normalization sizes, and runner provenance in a
layout-independent export. Verification is available separately from timing.

The complete 238-case Rebar registry has checksummed inputs, explicit
translations and exclusions, and bulk public-operation adapters. Verification
compares aggregate results and ordered match/capture bytes against the original
native source. Phase-specific timeout receipts require two attempts. A result
disagreement blocks collection; it is not classified as incompatibility.

The tools support same-host batches, archive-based worker builds, AWS dispatch,
validated restarts, and aggregation from raw evidence. Language suites run
separately from the baseline shards, Trino operations, and LIKE coverage; all
are required. Final qualification requires the complete released-artifact
campaign. Historical preliminary reports keep each measurement's actual source
identity. Page-layout changes must preserve raw samples and workload IDs.

## Completion criteria

The collection and report must preserve these requirements:

- all four language views have their natural comparator
- the three regex views share stable logical cases where semantics overlap
- the JDK comparator has pinned provenance and verified representation rules
- every regex language has representative single-use and reused measurements
- not-compatible and did-not-finish states are explicit and non-aggregated
- no verification failure or unexplained missing row reaches published data
- the complete target-architecture and memory-mode report can be regenerated
  from immutable manifest, receipt, and result data

The measurement and acceptance rules in
[`METHODOLOGY.md`](METHODOLOGY.md) and
[`QUALIFICATION_PLAN.md`](QUALIFICATION_PLAN.md) continue to apply.
