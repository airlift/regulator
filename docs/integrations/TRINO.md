# Trino integration

Regulator provides the matching APIs needed for Trino's regex and LIKE
functions. This guide explains what the library handles and what a Trino
adoption proposal must provide. Integration into Trino is a separate project.

## Why Trino needs a language frontend

Trino's regular-expression functions use Airlift's fork of Joni with Java-like
syntax and documented exceptions. That language is neither RE2 syntax nor the
complete `java.util.regex.Pattern` language. Passing the same pattern text to a
different parser can silently change anchors, flags, classes, escapes, or
capture behavior.

`TrinoRegexp.compile(pattern)` parses the supported Trino language directly.
It produces the shared regular-expression tree without passing pattern text
through the RE2 or Java parser, and never silently falls back to another engine.

See [`reference/languages/TRINO_REGEXP.md`](../reference/languages/TRINO_REGEXP.md)
for the syntax contract and
[`reference/languages/UNSUPPORTED_FEATURES.md`](../reference/languages/UNSUPPORTED_FEATURES.md)
for unsupported constructs.

## Current library surface

Use these two APIs for Trino operations:

- `TrinoRegexp` implements Trino-shaped contains, count, position, extraction,
  replacement, and split operations over the supported regular subset.
- `TrinoLikePattern` compiles SQL LIKE patterns directly and selects exact,
  prefix, suffix, contains, ordered-literal, literal-gap, or general wildcard
  execution.

Both operate on `Slice` values. Regexp matcher boundaries are zero-based byte
offsets relative to the logical Slice, with an exclusive end, and captures are
zero-copy Slice views where possible. The SQL-style `TrinoRegexp.position()`
operation instead returns a one-based code-point position, or `-1` when absent.
Its `start` argument also uses one-based code-point positions.

Separate parsing does not require duplicate execution code. Exact, prefix,
suffix, contains, and ordered-literal plans use shared kernels whenever the two
frontends prove equivalent semantics. An eligible `TrinoRegexp.contains` call
uses the same ordered-literal search as LIKE, while the compiled RE2 program
remains available for count, position, extraction, replacement, split, and
fallback behavior.

## Why Slice is explicit

Trino already stores variable-width values as UTF-8 Slice data. A String API
would add conversion, use UTF-16 indexes instead of byte offsets, and make
malformed UTF-8 behavior depend on Java decoding. Taking Slice directly avoids
those changes and conversion costs.

## Compatibility boundary

The Trino frontend implements these SQL-visible behaviors:

- Joni-compatible final-LF `$`
- Unicode word boundaries
- JVM-derived multi-code-point case folding
- Trino replacement references such as `$1` and `${name}`
- Slice-relative group offsets
- non-strict malformed literal pattern bytes

Some loops that can match empty input produce different boundaries when Joni
revisits earlier capture history. Regulator accepts these patterns but tracks
input progress with bounded state instead of retaining that history. This is
a documented incompatibility, not an unsupported pattern. See the
[Trino language reference](../reference/languages/TRINO_REGEXP.md#supported-constructs)
for the exact behavior and example. A specialized capture-aware engine is
deferred unless practical Trino workloads require exact compatibility.

Trino's non-strict path assumes UTF-8 data is valid but does not validate it.
Malformed bytes are preserved and may match byte-for-byte. This is deliberately
garbage-in, garbage-out behavior rather than text validation.

Backreferences, lookaround, atomic groups, possessive quantifiers, and other
backtracking-dependent constructs are rejected during compilation with a
specific error. Supporting them would require a separate execution model and an
explicit API decision.

## Trino LIKE

Compiling LIKE directly makes its common pattern shapes easy to recognize:

| Pattern shape | Execution shape |
|---|---|
| no wildcard | exact equality |
| `literal%` | prefix |
| `%literal` | suffix |
| `%literal%` | contains |
| literals separated by `%` | ordered literal search |
| eligible literals separated by `_` and `%` | literal-gap search |
| remaining wildcard composition | general LIKE matcher |

The compiler canonicalizes redundant `%` operators. Dynamic patterns should be
compiled once and reused whenever the caller can cache them.

Ordered LIKE patterns and eligible Trino regular expressions share a fused
front-and-back byte scanner with a bounded KMP fallback. This is an execution
kernel, not a shared parser or a translation between languages. Eligible patterns
with `_` use literal-gap search; the remaining shapes use the dedicated LIKE
wildcard matcher. Regexp operations also select specialized paths when the
pattern and requested operation permit them. For example, `count` can use
single-byte, repeated-byte, or literal counting paths. The compiled RE2 program
remains available as the fallback; specialization is specific to each operation.

See [`reference/languages/TRINO_LIKE.md`](../reference/languages/TRINO_LIKE.md).

## Public API expectations

A Trino integration should:

- compile constant patterns during specialization
- reuse compiled patterns and matcher workspaces across rows
- pass logical Slice regions without copying
- request captures only for operations that need them
- preserve SQL null and error behavior in Trino-side adapters
- translate compile and replacement exceptions into Trino errors
- make any unsupported-language fallback explicit rather than automatic

The shared compiled object is safe to reuse. Mutable matching state belongs to a
`TrinoRegexpMatcher`, `Re2Matcher`, or operation-local workspace.

## Correctness evidence

Before adoption, run differential coverage against the exact Joni fork and
Trino revision selected for the proposal. Cover:

- every Trino regexp function
- every Trino LIKE function and escape mode
- compile success and failure for the published language
- find, looking-at, and full-match behavior
- numbered and named captures
- empty matches and zero-length progress
- replacement and split corner cases
- non-zero Slice offsets
- malformed UTF-8 patterns and inputs
- concurrency and memory-budget failures

Joni is the compatibility oracle for supported Trino regexp behavior except for
explicitly documented incompatibilities. Pinned native RE2 remains the oracle
for the shared execution engine where semantics overlap.

## Performance evidence

The final comparison must use the frozen library candidate and the exact Trino
Joni fork. Measure:

- compile cost separately from repeated execution
- contains, count, position, extract, extract-all, replace, and split
- matching with and without captures
- short, medium, page-sized, and large inputs
- match absent, early, middle, late, and dense-candidate inputs
- ASCII and representative UTF-8 data
- constant and dynamic pattern lifecycles
- retained memory and DFA reset behavior

Run on current Intel and AWS Graviton systems. Report normal workload classes
separately from extreme outliers.

The authoritative execution and acceptance protocol is
[`benchmarks/QUALIFICATION_PLAN.md`](../benchmarks/QUALIFICATION_PLAN.md).

## Adoption package

A Trino proposal should contain:

1. the exact library commit and dependency coordinates
2. the supported-language document
3. the complete differential result matrix
4. final Intel and Graviton performance tables
5. memory and concurrency behavior
6. unsupported-pattern and documented-incompatibility migration policy
7. the Trino-side adapter and function-test changes

The proposal must explain compatibility, resource limits, and diagnostics as
well as performance.
