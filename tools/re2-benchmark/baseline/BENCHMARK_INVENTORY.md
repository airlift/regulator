# Benchmark mode inventory

The AWS wrappers, `aws/run-campaign.sh` and `aws/run-host.sh`, accept one
mode, `unit-worker`, and reject any other.

## Accepted modes

- `unit-worker` runs no shard of its own. It leases the work units that
  `baseline/run-campaign.py` dispatches, from baseline shards or from the
  public-language plans of `language/fleet.py`, and measures them one at a
  time. See [work units](SHARD_DISPATCH.md#work-units) and the
  [language guide](../language/README.md). The build phase's `engine-build`
  unit measures nothing: it builds the pinned Trino harness and the native RE2
  and Rust comparison runners once per platform, which later unit workers
  install. See [engine builds](SHARD_DISPATCH.md#engine-builds).

The `trino-specialized-patterns-a`, `trino-specialized-patterns-b`, and
`trino-like-specialized` shards are in the formal campaign to answer what a
pattern gains from the specialized engine and from the opt-in compiler. The
report sets the regular engine, the specialized engine, and compiled beside each
other and beside their comparator: Joni for Trino regex, and Trino SQL LIKE for
the TPC-H LIKE workloads, which have no regular-engine variant. They measure
warmed steady-state operations only, not compilation cost. The workloads are
listed in [`specialized-patterns.tsv`](specialized-patterns.tsv).

## Enforcement

Formal campaign orchestration and aggregation accept only rows from the frozen
manifests.
