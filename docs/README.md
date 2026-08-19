# Regulator documentation

Start with the API and language guides to use Regulator. For changes to the
library, read the maintenance guide and design decisions. Development notebooks
and intermediate benchmark reports are not kept here.

## Start here

- [API guide](reference/REGULATOR_API.md): matching, captures, ownership, and lifecycle.
- [Pattern languages](integrations/REGEXP_LANGUAGES.md): choose the right compiler.
- [Maintenance guide](MAINTAINING_REGULATOR.md): validate changes, measure performance, and prepare a release.
- [Design decisions](../REGULATOR_DECISIONS.md): accepted trade-offs and differences from upstream RE2.

## Language reference

| Document | Purpose |
|---|---|
| [`reference/languages/RE2.md`](reference/languages/RE2.md) | RE2 syntax, modes, and options |
| [`reference/languages/TRINO_REGEXP.md`](reference/languages/TRINO_REGEXP.md) | Trino's Joni-derived regular-expression language |
| [`reference/languages/JAVA_REGEXP.md`](reference/languages/JAVA_REGEXP.md) | Supported regular subset of Java 25 Pattern syntax |
| [`reference/languages/TRINO_LIKE.md`](reference/languages/TRINO_LIKE.md) | Trino SQL LIKE syntax and semantics |
| [`reference/languages/UNSUPPORTED_FEATURES.md`](reference/languages/UNSUPPORTED_FEATURES.md) | Unsupported constructs and future-support policy |

## API and integration

| Document | Purpose |
|---|---|
| [`reference/REGULATOR_API.md`](reference/REGULATOR_API.md) | Public API, ownership, matching, captures, and resources |
| [`integrations/TRINO.md`](integrations/TRINO.md) | Trino compatibility boundary and adoption requirements |
| [`integrations/JVM_UNICODE.md`](integrations/JVM_UNICODE.md) | JVM-sourced Unicode policy and regeneration |
| [`reference/REVERSE_DFA.md`](reference/REVERSE_DFA.md) | Reverse-DFA design reference |

## Correctness and development

| Document | Purpose |
|---|---|
| [`porting/WORKFLOW.md`](porting/WORKFLOW.md) | Upstream-grounded implementation workflow |
| [`porting/TEST_MAP.md`](porting/TEST_MAP.md) | Upstream C++ test coverage map |
| [`coding-standards.md`](coding-standards.md) | Implementation, naming, and comment conventions |
| [`testing.md`](testing.md) | Test conventions |
| [`git.md`](git.md) | Commit and history conventions |

## Performance

| Document | Purpose |
|---|---|
| [`benchmarks/METHODOLOGY.md`](benchmarks/METHODOLOGY.md) | Benchmark execution and performance-analysis method |
| [`benchmarks/WORKLOAD_ORGANIZATION.md`](benchmarks/WORKLOAD_ORGANIZATION.md) | Workload populations, comparator coverage, and aggregation rules |
| [`benchmarks/QUALIFICATION_PLAN.md`](benchmarks/QUALIFICATION_PLAN.md) | Final Intel and Graviton qualification plan |
| [AWS collection guide](../tools/re2-benchmark/aws/README.md) | Freeze, plan, run, recover, and budget machine-hours and cost |
| [Interactive benchmark report](https://airlift.github.io/regulator/benchmarks/) | Sortable C9g, C8g, and C8i results for native-memory and pure-Java routes |
| [`reference/OPTIMIZATION_REGISTRY.md`](reference/OPTIMIZATION_REGISTRY.md) | Important upstream and Java-specific optimization inventory |
| [`MAINTAINING_REGULATOR.md`](MAINTAINING_REGULATOR.md) | Proportional performance gates for future changes |

## Documentation rules

- Record only implemented, intentional design decisions in `REGULATOR_DECISIONS.md`.
- Describe current behavior in language and API docs, not the history of its implementation.
- Do not commit intermediate benchmark results, campaign notebooks, raw profiles, or rejected experiments.
- Commit one reproducible final benchmark data set for a frozen review or release candidate.
- Version 1.0 may first publish a labeled preliminary snapshot of development measurements and targeted updates. Preserve per-row source identities and replace it with the complete released-artifact campaign afterward.
- Generate the README summary and interactive report from the same versioned data.

## Upstream source

- Repository: <https://github.com/google/re2>
- Pinned commit: `972a15cedd008d846f1a39b2e88ce48d7f166cbd`
- Reproducible fetch/build: `../tools/re2-golden/build.sh`
- Fetched source: `../target/re2-golden-dependencies/re2/`
