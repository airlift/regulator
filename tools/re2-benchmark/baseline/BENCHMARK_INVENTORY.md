# Benchmark mode inventory

The AWS wrappers, `aws/run-campaign.sh` and `aws/run-host.sh`, accept three
modes.

## Accepted modes

- `baseline-shard` runs one shard selected by `baseline/run-campaign.py`.
  Its exact rows and systems come from `rows.tsv`, and route ownership comes
  from `shard-dispatch.tsv`.
- `language-batch` runs a public-language comparison batch selected by
  `language/fleet.py`, with collection and acceptance managed by
  `language/campaign.py`. See the [language guide](../language/README.md).
- `engine-build` measures nothing. The build phase of `baseline/run-campaign.py`
  runs it once per platform to build the native RE2 benchmark, the Rebar
  engines, and the pinned Trino harness that later baseline hosts install. See
  [engine builds](SHARD_DISPATCH.md#engine-builds).

The wrappers reject other modes. Standalone diagnostics remain outside the
formal campaign and do not become accepted evidence merely by using the same
benchmark classes.

The `trino-specialized-patterns-a`, `trino-specialized-patterns-b`, and
`trino-like-specialized` shards are in the formal campaign to answer what a
pattern gains from the specialized engine and from the opt-in compiler. The
report sets the regular engine, the specialized engine, and compiled beside each
other and beside their comparator: Joni for Trino regex, and Trino SQL LIKE for
the TPC-H LIKE workloads, which have no regular-engine variant. They measure
warmed steady-state operations only, not compilation cost. The workloads are
listed in [`specialized-patterns.tsv`](specialized-patterns.tsv).

## Enforcement

`validate-mode-inventory.py` verifies that both AWS wrappers accept only
`baseline-shard`, `language-batch`, and `engine-build`. Formal campaign orchestration and
aggregation accept only rows from the frozen manifests.
