# Benchmark workload manifests

These files freeze the workload identity of the Rebar workloads that the
language bulk comparison runs:

- `rebar-workloads.csv` records the 41-workload curated native RE2
  intersection, including pattern and input hashes, expected results, and
  result demand.
- `rebar-extended-workloads.tsv` records the 197 non-curated informational
  workloads at the pinned Rebar revision that explicitly include native RE2
  and use a supported model. These rows are reported separately.
- `rebar-exclusions.tsv` records every other non-curated row and why it is not
  semantically applicable, including the composite `regex-redux` application.
- `rebar-workload-taxonomy.tsv` classifies every curated and extended row as
  bulk text, compilation, or diagnostics and stress. Rules must be exhaustive
  and non-overlapping. Its adjacent checksum freezes the reviewed rules.
- `rebar-selected-workloads.tsv` records the ten expanded KLV inputs used by
  focused DFA campaigns.
- the adjacent `.sha256` files protect the checked-in definitions.

Run the consistency check with:

```bash
tools/re2-benchmark/manifests/validate-rebar-workloads.sh
python3 tools/re2-benchmark/manifests/validate-workload-taxonomy.py
tools/re2-benchmark/manifests/validate-extended-rebar.sh <pinned-rebar-checkout>
```

The last check regenerates the extended manifest and exclusions from a
checkout of the pinned Rebar revision and compares them with the checked-in
files.

Update these files only when the pinned Rebar revision or the intentional
benchmark intersection changes. Regenerate from that exact revision, review
every added or removed workload and result-demand change, update both checksum
files, and rerun the manifest tests with
`python3 -m unittest discover -s tools/re2-benchmark/manifests`. A manifest
update changes the qualification corpus and therefore requires a new frozen
candidate and new qualification results.

`literal-probe-corpus.tsv` pins the corpora for the literal-probe distribution
workloads by URL, revision, size, checksum, and license, and
`literal-probe-workloads.tsv` lists those workloads. The corpora are fetched by
`language/literal_probe.py` and never committed; see the
[language guide](../language/README.md#literal-probe-distributions).

`trino-row-corpus.tsv` pins the corpora of the normative Trino row workloads
by location, revision, size, checksum, and license. The subtitle corpora come
from repository resources, and the access-log entries equal those of
`literal-probe-corpus.tsv`. `trino-rows/trino_rows.py` fetches the others and
generates the workload manifests; see the
[workload guide](../trino-rows/README.md).

`tpch-like-corpus.tsv` pins the committed TPC-H column samples used by the
Trino LIKE TPC-H benchmark by generator, scale factor, row range, size, and
checksum, and `tpch-like-workloads.tsv` lists its workloads with their source
queries and expected match counts. See the
[LIKE guide](../trino-like/README.md#tpc-h-column-samples).
