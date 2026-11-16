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

[The qualification plan](QUALIFICATION_PLAN.md) defines the full campaign for
a released artifact.

The [measurement-quality assessment](MEASUREMENT_QUALITY.md) explains the
follow-up collection, within-process and across-host variation, native controls,
and how to read the mean-cost intervals. Historical gap-ledger flags stay
separate from current measurement uncertainty.

The [interactive benchmark report](https://airlift.github.io/regulator/benchmarks/)
loads the committed versioned result data and supports platform, memory-mode,
and sortable workload comparisons. Its source and update instructions are in
[`../../benchmark-report/`](../../benchmark-report/). The site shows only the
current report; earlier reports' data files stay in
[`benchmark-report/data/`](../../benchmark-report/data/), with the 1.0
campaign's write-up in [`RESULTS_1.0.md`](RESULTS_1.0.md).

The report and its download publish every campaign row except those the
[publication boundary](METHODOLOGY.md#publication-boundary) hides.
Measurement-quality studies are separate supporting evidence. Internal route
probes, scaling diagnostics, failed attempts, and the complete captured campaign
remain in the indexed private archive and are not additional release benchmarks.

Intermediate measurements, rejected experiments, raw profiles, and campaign
notebooks are local working data and are not committed. Preliminary reports are
the exception: each preserves every published row's actual source revision,
host coverage, and uncertainty. Commit the final public
machine-readable release data with the provenance required to reproduce its
conclusions, and index the separate private evidence needed to audit the wider
campaign.
