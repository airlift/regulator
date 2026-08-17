# Baseline reduction and reporting

`aggregate_results.py` validates and summarizes baseline campaign results. It
rejects incomplete or inconsistent evidence. `render_report.py` turns the
summary into a deterministic Markdown report. Both scripts use only the Python
standard library and are frozen with the candidate before measurement.

## Inputs

The reducer requires the frozen `rows.tsv`, `platforms.tsv`, and `shards.tsv`,
the primary `accepted-sessions.tsv`, and one or more artifact roots or explicit
session directories. The required row count and coverage are checked by
`validate-manifest.py` against the frozen manifest.

Every accepted session directory must contain:

- `acceptance-receipt.tsv`
- `session.tsv`
- `observed-rows.tsv`
- `route-evidence.tsv`
- `routes/<route>/calibration.tsv`
- `routes/<route>/native-bracket.tsv` when that route measures native RE2
- `routes/<route>/run-metadata.txt`
- `routes/<route>/raw-artifacts.sha256`
- `host-capacity.txt`

The receipt ledger must contain exactly replicas 1, 2, and 3 for every
platform/shard pair. Accepted artifact discovery must match the ledger exactly.
The reducer rejects missing, duplicate, unexpected, or relabeled rows; receipt
or file checksum failures; reused instances or host epochs; and platform,
shard, system, or campaign mismatches.

`route-evidence.tsv` is the acceptance-time host evidence manifest. The reducer
verifies its receipt-bound SHA-256, every directly listed artifact, and every
raw/log file listed by each routed `raw-artifacts.sha256`. It reads calibration,
native brackets, and route metadata from those verified routed paths rather
than assuming a flattened host layout. Route sessions must have the same host
identity and their systems must exactly cover the combined host session.

The receipt also binds the candidate commit, engine tree, and deployed source
archive. When `--campaign-artifact` is supplied, its candidate commit must match
every accepted receipt. A mismatch invalidates reduction rather than being
reported as an informational provenance difference.

Confirmation receipts are supplied separately. They may contain only replica
4 and at most one session for a platform/shard pair. Confirmation rows are
preserved alongside the three primary rows and identified explicitly; they
never replace a primary host.

Calibration drift above 5% invalidates a host before acceptance. Native-bracket
drift is scoped to the benchmark identity in that bracket: drift above 5%
marks the affected rows and comparisons unresolved and makes their shard
eligible for confirmation without invalidating unrelated host measurements.
Evidence for interrupted and rejected hosts is supplied through event files
and remains separate from accepted measurements.

`host-capacity.txt` is written in two stages. Acceptance binds the immutable
memory-total and available-before prefix because GNU `time` cannot write wall
time and peak RSS until the measured host process exits. Reduction then
requires positive wall time and peak RSS, a zero exit status, valid before and
after available-memory values, and peak RSS no larger than physical memory.
The finalized file is included in the artifact inventory and immutable campaign
archive. `capacity.tsv` preserves these values for every accepted session.

### Campaign Events

Each `--events` file is TSV with required `event_type` and `reason` fields.
`event_type` is one of `interruption`, `rejected-session`, or `exclusion`.
The following identity and evidence fields are recognized when available:

```text
campaign_id platform shard_id replica_id instance_id host_epoch
artifact_uri artifact_sha256
```

The controller's preformatted `details` value is preserved. Additional columns
are merged into JSON-object details in sorted-key order. For plain-text details,
they are appended as a canonical `additional={...}` suffix. Events are copied
to `events.tsv` and represented in the gap ledger; they are never treated as
accepted measurements or unresolved accepted results.

`--rebar-exclusions` accepts the checked-in `rebar-exclusions.tsv` schema:

```text
name model definition reason
```

Each row becomes an exclusion event and a recorded gap with scope `Rebar`.
Name, model, definition, and reason remain available without a hand-written
campaign event.

`--rebar-joni-applicability` accepts the authoritative checked-in
`rebar-joni-applicability.tsv`:

```text
benchmark model status reason
```

The reducer validates the adjacent checksum, exhaustive workload identity set,
exact compatible-row relationship, and digest embedded in the frozen row
manifest. Compatible rows have measurements in `rows.tsv` and do not create
events. A `not-compatible` row becomes a Joni-scoped exclusion event and is not
converted into a numeric comparison.

An accepted Rebar route may also contain `raw/rebar-outcomes.tsv`. It records
complete extended-corpus verifier mismatches and compatible Joni rows that
exceeded the 30-second verification or 120-second measurement limit. The
outcome records the phase; a measurement timeout can follow successful
verification. Other curated failures cannot reach an accepted session. The
reducer verifies each outcome against the exact manifest
row, records a result row without performance data in `host-rows.tsv`, records
the outcome in `rebar-outcomes.tsv` and the gap ledger, and excludes the
affected host-row ratio from comparator summaries.

### Retrieval Metadata

Each optional `--artifact-index` file must contain the session identity fields
from `session.tsv`. All other columns are passed through to `metadata.tsv`.
These conventional fields are also copied directly into the artifact
inventory:

```text
artifact_uri artifact_sha256 retrieved_at
```

`retrieval_uri`, `archive_sha256`, and `retrieval_timestamp` are accepted as
equivalent names. Independently of external storage metadata, the reducer
computes a deterministic artifact-set digest and inventories every file in the
accepted session, including raw timing artifacts.

`--campaign-artifact` accepts the one-row retrieval metadata emitted by
`archive_results.py`:

```text
s3_uri version_id archive_sha256 candidate_commit candidate_ref campaign_id
source_directory file_count source_manifest_sha256
```

The campaign ID must match the primary receipt ledger. The immutable S3 URI,
version, archive checksum, candidate identity, source directory, and file count
are attached to every accepted session's metadata and inventory. Optional
per-session artifact indexes remain available as more specific provenance.
The adjacent `<campaign-artifact>.source-manifest.tsv` must have the recorded
SHA-256. Every accepted session file must appear in that exported embedded
archive manifest with its exact size and digest. This recursively binds routed
metadata, raw manifests and artifacts, comparator provenance, finalized
capacity, and acceptance receipts to the immutable campaign archive.

## Comparator Rules

Ratios are emitted only when the manifest identifies a same-host comparator
relationship. The reducer does not infer relationships from similar benchmark
names or descriptive labels:

- A logical family containing `native-re2-before` and `native-re2-after`
  compares each Regulator row with the median bracket score.
- A family whose comparator is Joni compares each Regulator row with its
  same-host `joni` row.
- A Trino LIKE family compares `regulator` separately with
  `trino-optimized` and `trino-sql`.
- A `general-route-control` family compares `regulator-native-access` with
  `regulator-object-row`.
- Other manifest comparator labels do not create a ratio unless one of these
  concrete paired shapes is present.
- A host-row comparison with a classified extended Rebar semantic mismatch on
  either side is omitted because its timing is informational rather than a
  semantically equivalent performance comparison.

Ratios use normalized Java cost divided by comparator cost. Below 1 is faster;
above 1 is slower. Time units are converted before division. Throughput units
are inverted so the direction remains consistent. Retained-memory bytes are a
cost, so fewer retained bytes also produces a ratio below 1.

## Aggregation

The host session is the independent unit. The reducer preserves all primary
and confirmation hosts, then reports for every platform and manifest row:

- primary and all-host medians
- minimum and maximum score
- primary and all-host coefficient of variation
- an explicit note that a useful confidence interval is unsupported with only
  three or four independent hosts
- median allocation
- maximum accepted calibration and native-bracket drift
- benchmark contract-identity consistency
- identifiable input-size scaling classification
- every unresolved reason

The producer's legacy `result_checksum` column contains a digest of every field
in the row's frozen manifest contract, including expected results, workload,
system and comparator. It is not derived from measured output or route-wide
verification outcomes. Reducer outputs therefore expose it as
`contract_identity`. Replica disagreement means the declared contracts differ;
it does not demonstrate an incorrect measured result. The complete semantic
receipt and classified outcomes remain separate, integrity-checked evidence.
A timeout on another row must not change this row's identity. The campaign and
reducer also enforce pinned source/runtime/comparator provenance. Raw timing-file
checksums belong in the artifact inventory.

Scaling is identified only when a benchmark/system family has at least three
numeric values of a size-, length-, count-, capture-, group-, or repetition-
named parameter while all other parameters are fixed. A log-log slope is
recorded as decreasing, constant, sublinear, linear, or superlinear. Different
classes across accepted hosts are unresolved. Unidentifiable rows are reported
as such rather than assigned an expected class.

A row or comparison remains unresolved when applicable if:

- any contributing measurement exceeds the 5% relative standard-error gate
- host CV exceeds 5%
- same-host candidate/comparator ratio CV exceeds 5%
- hosts disagree in both material directions outside the 0.95-1.05 band
- an allocation-free row reports any allocation
- benchmark contract identities differ
- identifiable scaling classes differ across hosts

A median ratio at or beyond 0.5 or 2.0 is a serious performance outlier. These
rows are preserved in `serious-outliers.tsv` and the gap ledger, but excluded
from `general-classes.tsv`. Curated and extended Rebar rows are always grouped
separately.

`confirmation-jobs.tsv` deduplicates primary instability to the exact
`platform, shard_id` schema consumed by `run-campaign.py`. Host CV or primary
same-host ratio CV above 5%, precision rejection, material direction
disagreement, contract-identity disagreement, and scaling class disagreement
request confirmation. After confirmation, the all-host ratio CV determines
whether ratio variability remains unresolved. A
deterministic allocation violation or a serious but stable ratio does not. A
platform/shard that already has a replica-4 session is omitted because its one
confirmation allowance is spent.

## Outputs

The reducer writes:

- `host-rows.tsv`: every accepted host measurement
- `row-aggregates.tsv`: per-platform row statistics and unresolved state
- `host-comparator-ratios.tsv`: every same-host ratio
- `comparison-aggregates.tsv`: per-platform comparison statistics
- `general-classes.tsv`: class summaries without serious outliers
- `serious-outliers.tsv`: serious comparison rows
- `scaling.tsv`: per-host scaling signatures
- `gap-ledger.tsv`: unresolved conditions, serious outliers, and events
- `events.tsv`: interruptions, rejected sessions, and exclusions
- `artifact-inventory.tsv`: file and retrieval checksums
- `metadata.tsv`: run and retrieval metadata passthrough
- `capacity.tsv`: finalized wall-time, peak-RSS, and host-memory evidence
- `rebar-outcomes.tsv`: exact extended-corpus semantic mismatches by host
- `confirmation-jobs.tsv`: unique primary-unstable platform/shard pairs still
  eligible for the bounded replica-4 wave
- `summary.json`: deterministic campaign counts and identity
- `output-checksums.tsv`: checksums for every reducer output above

The renderer verifies `output-checksums.tsv`, writes the Markdown report, and
writes a sibling `.sha256` file. It does not use the current time, filesystem
iteration order, or host locale, so identical reducer inputs produce identical
output.

## Commands

```bash
python3 tools/re2-benchmark/baseline/aggregate_results.py \
    --primary-receipts /durable/path/campaign/primary/accepted-sessions.tsv \
    --confirmation-receipts /durable/path/campaign/confirmation/accepted-sessions.tsv \
    --artifacts-root /durable/path/campaign/primary \
    --artifacts-root /durable/path/campaign/confirmation \
    --events /durable/path/campaign/campaign-events.tsv \
    --rebar-exclusions tools/re2-benchmark/manifests/rebar-exclusions.tsv \
    --rebar-joni-applicability tools/re2-benchmark/manifests/rebar-joni-applicability.tsv \
    --artifact-index /durable/path/campaign/artifact-index.tsv \
    --campaign-artifact /durable/path/campaign/retrieval-metadata.tsv \
    --output-dir /durable/path/campaign/report-data

python3 tools/re2-benchmark/baseline/render_report.py \
    --input-dir /durable/path/campaign/report-data \
    --output /durable/path/campaign/BASELINE_REPORT.md
```

The isolated tests use synthetic C8i, C8g, and C9g campaigns:

```bash
python3 -m unittest tools/re2-benchmark/baseline/tests/test_aggregate_results.py -v
```
