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

## Enforcement

`validate-mode-inventory.py` verifies that both AWS wrappers accept only
`baseline-shard` and `language-batch`. Formal campaign orchestration and
aggregation accept only rows from the frozen manifests.
