# Regulator 1.0 baseline evidence

`baseline-tables.tar.gz` contains the original completed campaign's normalized baseline
tables, host and workload manifests, accepted-session ledgers, and collector
provenance. `baseline-evidence.json` records the archive checksum and every
member's size and SHA-256. Extract it with `tar -xzf baseline-tables.tar.gz`.

These historical tables retain the original protocol and estimator. The current
public capture applies the measurement-quality follow-up described in the
[release results](../RESULTS_1.0.md) and [quality assessment](../MEASUREMENT_QUALITY.md).
Do not interpret the original warning counts as current failed-measurement counts.

The tables retain adverse results, unresolved comparisons, precision flags,
allocation-contract flags, empirical scaling fits, exclusions, and bounded
non-completion. `general-classes.tsv` excludes serious outliers and is not a
whole-corpus headline. Use the complete row and comparison tables alongside
the gap ledger. A scaling fit describes its recorded size parameter and
observed range; it does not prove asymptotic complexity.

See [the final report](../RESULTS_1.0.md) for interpretation, release identity,
and permanent raw-evidence retrieval. The interactive report's public download
contains exactly the 5,004-row publication inventory with full measurements and
per-host provenance. It does not contain campaign-only diagnostics.

The [archive index](raw-archives.json) identifies each archive by exact S3 object
version and SHA-256. It retains the two original campaigns and lists the
follow-up diagnostic, failed/superseded, formal-measurement, collector-history,
and final-analysis segments separately. Storage is private; retrieval requires
credentials authorized for the evidence bucket.

Use this private index for the complete 9,462-row campaign capture, internal
route and scaling probes, raw quality diagnostics, failed attempts, and
superseded cohorts. Those artifacts support audit and future investigation;
they are not an expanded set of release benchmarks.

For a follow-up segment, download the indexed object version, verify its outer
SHA-256, and extract it. The extracted content store includes its restore tool. It requires Python 3.11
or newer and the `zstd` command:

```sh
python3 campaign-results/evidence_store.py restore \
  --store campaign-results --destination restored-segment
```

The restore tool verifies content hashes and restores independent files. Use a
separate empty destination for each segment. The final-analysis segment contains
`publication-analysis-inputs.jsonl.gz`; use the
[analysis replay](../../../benchmark-report/analysis/README.md) to check every
numeric cost, ratio, interval, and comparison identity against the current
report download without allocating cloud hosts.
