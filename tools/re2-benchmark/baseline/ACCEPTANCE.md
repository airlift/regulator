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

## Unit acceptance

`validate-unit-results.py` accepts one work-unit attempt on its own, from the
directory `run-unit.sh` writes, an entry point separate from host acceptance
and its receipts. The unit directory holds a one-row `unit-session.tsv` with
these columns, in order:

```text
schema_version campaign_id platform unit_id replica_id unit_attempt_id instance_id host_epoch architecture instance_type availability_zone region
```

`unit_attempt_id` is `<host_session>/<lease_seq>`, the host session that
leased the unit and the lease's sequence number there, so each retry of a unit
is a distinct attempt. The validator rejects the attempt unless:

- the unit is a checked-in unit, its `unit-rows.tsv` is exactly the checked-in
  rows of the unit, roles included, and its ID recomputes from its owned rows,
  copies, and representatives under the frozen row protocols
- `expected-rows.tsv` and `observed-rows.tsv` hold exactly the owned and
  copied rows, each observed row marked `owner` or `copy`; copies are
  measured for pairing only
- `route-plan.tsv` is the unit's plan for its replica, route order and
  calibration split included, and every route's session matches the unit
  session's host and the unit's shard
- the unit's `calibration.tsv`, from the first route's before and the last
  route's after calibration, drifted at most 5%
- every route ran the frozen protocol: qualification or smoke, the default
  Joni JMH window, and exactly the JMH selectors that protocol plans for its
  share
- every route's Surefire reports are the files of its semantic receipt,
  whether that receipt was measured by this unit or cached from an earlier
  unit of the shard on the same host
- the representatives the routes ran are exactly the unit's for the replica,
  replica 1's for replica 4, and each passed the representative protocol rule
- every artifact in `unit-evidence.tsv` verifies

`unit-evidence.tsv` has the `route-evidence.tsv` shape. Its route rows bind
each route's artifacts as host sessions do, except that a unit route holds
its JMH selectors and no calibration of its own. Its `host` rows bind the
environment and engine-build provenance, route plan, unit rows, expected and
observed rows, Rebar outcomes, calibration, unit session, and the immutable
prefix of `unit-capacity.txt`. As for hosts, GNU `time` completes that file
only after acceptance returns.

On success, the validator writes `unit-receipt.tsv`: the unit session, its
shard and owned and copied row counts, the digest of the unit's frozen row
protocols, the manifest, observed-row, and evidence-manifest digests, and the
candidate commit, archive, engine tree, and heap size.

```bash
tools/re2-benchmark/baseline/validate-unit-results.py \
    --unit-directory unit-attempt \
    --receipt unit-attempt/unit-receipt.tsv
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

- exactly the `r8i` and `r9g` platform identities, or the 1.0 release's
  `r8i`, `r8g`, and `r9g` from its archived `platforms.tsv`
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

A campaign of work units keeps `accepted-units.tsv` instead: one
`unit-receipt.tsv` row per accepted unit replica, added atomically like a host
receipt. Hosts do not yet pull units, so no controller phase writes this ledger
yet; `run-campaign.py` holds its persistence and recovery for the unit
dispatcher. Each attempt is retained at
`units/<platform>/<unit_id>/replica-<n>/<host_session>/lease-<seq>` under the
result root. On restart, recovery revalidates every ledger row against that
directory and adds a retained attempt the ledger lacks once it passes the same
checks: final status, accepted capacity, a receipt regenerated from the
evidence, and the frozen candidate. The first accepted attempt of a unit
replica wins; a later attempt that also passes is superseded and stays out of
the ledger.

With `--accepted-units`, the campaign validator requires every platform and
checked-in unit of the selected shards, with replicas 1, 2, and 3. It also
requires:

- the three replicas of a unit on distinct EC2 instances; different units may
  share a host, its host epoch, and its host session
- globally distinct unit attempt IDs, and each instance, host epoch, and host
  session naming one host
- the architecture and instance type pinned for the unit's shard
- each receipt's owned and copied row counts and row-protocol digest
  recomputed from the checked-in `units.tsv` and `row-protocol.tsv`, so every
  receipt names the same frozen units and protocols
- the frozen manifest digest and one candidate commit, engine tree, and
  source-archive identity across all attempts

A unit confirmation is replica `4`, at most one per platform and unit, on an
instance none of that unit's replicas used.

```bash
tools/re2-benchmark/baseline/validate-campaign-results.py \
    --campaign-id units-20261101 \
    --accepted-units accepted-units.tsv
```

Run the isolated self-tests with:

```bash
python3 -m unittest discover -s tools/re2-benchmark/baseline/tests -v
```
