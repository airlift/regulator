# Regulator benchmarks

Start with [the methodology](METHODOLOGY.md) for comparison rules, AWS
measurements, profiling, and performance diagnosis. Local work is limited to
builds and untimed verification.

[Workload organization](WORKLOAD_ORGANIZATION.md) defines ordinary operations,
bulk text, compilation, and diagnostic or stress workloads, and explains how
to summarize them without letting row counts skew the results.

[Language comparisons](LANGUAGE_COMPARISON_PLAN.md) defines the shared regex
workload, comparators, pattern lifecycles, and the difference between
incompatibility and timeout.

[`BENCHMARK_INVENTORY.md`](../../tools/re2-benchmark/baseline/BENCHMARK_INVENTORY.md)
classifies every executable benchmark mode and identifies the sole formal
baseline path.

[The qualification plan](QUALIFICATION_PLAN.md) covers the full campaign for
the released 1.0 artifact. Version 1.0 ships with labeled preliminary results
from existing measurements and targeted updates. The postrelease campaign
replaces them.

The [interactive benchmark report](https://airlift.github.io/regulator/benchmarks/)
loads the committed versioned result data and supports platform, memory-mode,
and sortable workload comparisons. Its source and update instructions are in
[`../../benchmark-report/`](../../benchmark-report/).

Intermediate measurements, rejected experiments, raw profiles, and campaign
notebooks are local working data and are not committed. The preliminary 1.0
publication snapshot is an explicit exception: it preserves each row's actual
source revision, host coverage, and uncertainty. After the released-artifact
campaign, commit the final machine-readable data set with the provenance
required to reproduce its conclusions.
