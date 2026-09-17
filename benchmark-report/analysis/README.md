# Reproducing timing intervals

The report build reads saved estimates; it does not run a statistical analysis
or require NumPy. To reproduce the interval calculation separately:

```sh
python3.12 -m venv target/benchmark-analysis
target/benchmark-analysis/bin/pip install -r benchmark-report/analysis/requirements.txt
target/benchmark-analysis/bin/python -m unittest discover -s benchmark-report/analysis
```

The replay environment requires Python 3.12 or newer because its pinned NumPy
version does not support older Python releases. Ordinary report and benchmark
tooling continues to support Python 3.11 or newer.

`uncertainty(hosts, "mean", comparison_identity)` accepts three or more paired
host records. Each record has a distinct `instanceId`, and `candidate` and
`comparator` objects with `groups`: one list of measured iteration costs in
nanoseconds per independent process. Native repetitions from a single process
form one group. Native before/after brackets form two groups. Do not split
iterations or native repetitions into invented independent processes.

When only native process aggregates survive, supply `processMeansNs` and
`processMediansNs`. The result records that limitation. A host aggregate without
process data must be supplied as `meanNs` and `medianNs`; its conditional
status is explicit in `conditionalHostAggregates`. Do not fabricate raw data.

The mean estimate averages iterations within a process, processes within a
host and hosts equally. The ratio divides mean candidate cost by mean comparator
cost. The deterministic seed comes from the comparison identity. Paired hosts
are resampled jointly, then whole processes are resampled within each selected
host. The default 4,000 draws produce approximate 95% percentile intervals.

These intervals describe observed sampling variation. Three or four hosts
cannot establish a distribution-free precision guarantee or rule out an unseen
compiler state. The returned process ranges and variation components describe
execution variability separately from uncertainty in mean cost. An interval
crossing parity is not evidence of equivalence.

After retrieving the campaign archive, verify the current publication with the
saved public-input projection or its recorded complete-campaign source:

```sh
target/benchmark-analysis/bin/python benchmark-report/analysis/reproduce.py \
  publication-analysis-inputs.jsonl.gz path/to/published-report.json.gz
```

The replay checks the normalized-input hash, every mean cost and ratio, every
interval and variation component, and exact numeric comparison coverage. When
given the complete-campaign source, it derives the deterministic public
projection and verifies both recorded hashes before analyzing the selected rows.
It rejects missing, duplicated, changed or differently aggregated inputs.

The campaign archive retains the reduction scripts, exact normalized inputs,
raw measurements and source identities used for the publication capture.
Original median estimates remain reproducible with `estimator="median"`.
The final comparison estimator is fixed to `mean` across workloads.
