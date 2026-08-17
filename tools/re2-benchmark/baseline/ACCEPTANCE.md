# Exact-row acceptance

These validators check results independently of the AWS controller. They read
tab-separated files, giving local checks, EC2 runs, and final aggregation the
same acceptance rules.

## Host acceptance

`validate-host-results.py` accepts three inputs:

- `rows.tsv`, the frozen exact-row manifest
- a one-row host session TSV with these columns, in order:

  ```text
  schema_version campaign_id platform shard_id replica_id instance_id host_epoch architecture instance_type availability_zone systems
  ```

- an observed-row TSV containing at least `row_id`, `shard_id`, and `system`

`systems` is a comma-separated list of the systems run by this host. The
validator selects exactly those manifest rows for the session's shard and
systems. It rejects missing, duplicate, unexpected, or mislabeled rows.
Additional observed-row columns may contain scores and provenance.

On success, the validator writes a one-row acceptance receipt. It records the
session and candidate identities and SHA-256 digests of the full benchmark
manifest, selected rows, combined observed file, and host evidence manifest
as it existed at acceptance.

Formal host sessions use `route-evidence.tsv` as the host evidence manifest.
Each row names an artifact below the host-session directory and its verification
mode. The manifest covers:

- every route's session, calibration, native bracket when applicable,
  semantic gate, representative protocol qualification when applicable, run
  metadata, normalized rows, status, and raw-artifact manifest
- every raw timing file and log through recursive verification of each route's
  `raw-artifacts.sha256`
- host environment and comparator provenance
- the exact-row, platform, comparator, and protocol-representative manifest
  identities
- the candidate commit, engine tree, and deployed source-archive identity
- the immutable prefix of `host-capacity.txt`

The receipt records `evidence_manifest_sha256`, `candidate_commit`,
`candidate_archive_sha256`, and `engine_tree`. Any change to route metadata,
raw measurements, logs, comparator provenance, or the capacity identity after
acceptance invalidates the receipt.

Representative protocol evidence is accepted only when both sides declare
exactly 50 samples, contain finite positive scores and finite nonnegative
coefficients of variation, remain at or below 5% relative standard error, and
have a recomputed median-score difference at or below 5%. The validator
recomputes derived values instead of trusting the receipt fields.

GNU `time` finalizes wall time and peak RSS only after host acceptance returns.
The receipt therefore commits the already-written memory-total and
available-before fields. The reducer additionally requires the completed
capacity fields and the immutable campaign archive binds the finalized file.
This two-stage contract avoids claiming a digest for values that do not yet
exist at acceptance time.

```bash
tools/re2-benchmark/baseline/validate-host-results.py \
    --session host-session.tsv \
    --observed-rows observed-rows.tsv \
    --receipt accepted-session.tsv
```

The primary host validator requires `replica_id` to be exactly `1`, `2`, or
`3`. `host_epoch` is an opaque, globally unique identifier for one EC2
lifetime; a replacement instance must use a new epoch. Reusing an epoch for
another accepted session is rejected by campaign acceptance.

The one bounded confirmation session uses replica `4` and a separate entry
point. The confirmation validator accepts only replica `4`; the primary host
and campaign validators continue to reject it.

```bash
tools/re2-benchmark/baseline/validate-confirmation-host-results.py \
    --session confirmation-host-session.tsv \
    --observed-rows confirmation-observed-rows.tsv \
    --receipt accepted-confirmation-session.tsv
```

## Campaign acceptance

The campaign controller atomically adds each validated host receipt to
`accepted-sessions.tsv` as soon as the job completes. Do not reconstruct this
ledger by concatenating result files. On restart, the controller revalidates
every ledger row against its retained artifact and recovers a completed
artifact if the controller stopped immediately before the atomic ledger update.
The candidate archive, provenance, and result root must remain outside Maven's
`target` directory so build cleanup cannot remove retained evidence.

The campaign validator requires every platform and shard in `platforms.tsv`
and `shards.tsv`, with replicas 1, 2, and 3 for each combination. Every host
must cover all systems assigned to its shard.

The validator also requires:

- exactly the `c8i`, `c8g`, and `c9g` platform identities
- the architecture and exact instance type pinned in `platforms.tsv`
- globally distinct EC2 instance IDs
- globally distinct host epochs
- receipt row counts and manifest digests matching the frozen manifest
- one candidate commit, engine tree, and source-archive identity across all
  accepted sessions

Replica `4` confirmation receipts are intentionally not included in
`accepted-sessions.tsv`. Primary campaign acceptance always requires exactly
replicas `1`, `2`, and `3`.

Primary execution requires the complete accepted smoke result root through
`--smoke-results`. Confirmation execution similarly requires the complete
accepted primary result root through `--primary-results`. The controller
revalidates cleanup, capacity, exact artifacts, and regenerated receipts before
starting the next phase.

```bash
tools/re2-benchmark/baseline/validate-campaign-results.py \
    --campaign-id pre-review-20260802 \
    --accepted-sessions accepted-sessions.tsv
```

Run the isolated self-tests with:

```bash
python3 -m unittest discover -s tools/re2-benchmark/baseline/tests -v
```
