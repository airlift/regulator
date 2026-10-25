# Benchmark mode inventory

The AWS wrappers, `aws/run-campaign.sh` and `aws/run-host.sh`, accept two
benchmark modes.

## Accepted modes

- `baseline-shard` runs one shard selected by `baseline/run-campaign.py`.
  Its exact rows and systems come from `rows.tsv`, and route ownership comes
  from `shard-dispatch.tsv`.
- `language-batch` runs a public-language comparison batch selected by
  `language/fleet.py`, with collection and acceptance managed by
  `language/campaign.py`. See the [language guide](../language/README.md).

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
`baseline-shard` and `language-batch`. Formal campaign orchestration and
aggregation accept only rows from the frozen manifests.
