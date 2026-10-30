# Benchmark mode inventory

`aws/run-host.sh` accepts one mode, `unit-worker`, and rejects any other.

## Accepted modes

- `unit-worker` runs no shard of its own. It leases the work units that
  `baseline/run-campaign.py` dispatches, from baseline shards or from the
  public-language plans of `language/fleet.py`, and measures them one at a
  time. See [work units](SHARD_DISPATCH.md#work-units) and the
  [language guide](../language/README.md). The build phase's `engine-build`
  unit measures nothing: it builds the candidate's classes, the pinned Trino
  harness, and the native RE2 and Rust comparison runners once per platform,
  which later unit workers install. See
  [engine builds](SHARD_DISPATCH.md#engine-builds).

The specialized-pattern shards are in the formal campaign to answer what a
pattern gains from the specialized engine and from the opt-in compiler:
`trino-specialized-patterns-a` and `-b`, `re2-specialized-patterns-a` and
`-b`, `java-specialized-patterns-a` and `-b`, `rust-specialized-patterns-a`
and `-b`, and `trino-like-specialized`. The report sets the regular engine, the
specialized engine, and compiled beside each other and beside their
comparator: Joni for Trino regex, native RE2 for RE2, JDK `Pattern` for Java,
the `regex` crate for Rust, and Trino SQL LIKE for the TPC-H LIKE workloads,
which have no regular-engine variant. They measure warmed steady-state
operations only, not compilation cost. The workloads are listed in
[`specialized-patterns.tsv`](specialized-patterns.tsv).

## Enforcement

Formal campaign orchestration and aggregation accept only rows from the frozen
manifests.
