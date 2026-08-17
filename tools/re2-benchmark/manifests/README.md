# Benchmark workload manifests

These files freeze the workload identity used by the Rebar benchmark runners:

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
```

`tools/re2-benchmark/rebar/generate-manifest.sh` compares an expanded
41-workload manifest with the checked-in CSV. `rebar/prepare.sh --selected`
generates the focused KLV corpus from the pinned Rebar checkout and verifies
each uncompressed length and checksum.

Update these files only when the pinned Rebar revision or the intentional
benchmark intersection changes. Regenerate from that exact revision, review
every added or removed workload and result-demand change, update both checksum
files, and rerun the Rebar Python tests. A manifest update changes the
qualification corpus and therefore requires a new frozen candidate and new
qualification results.
