# Exact-row acceptance

These validators check results independently of the AWS controller. They read
tab-separated files, giving local checks, EC2 runs, and final aggregation the
same acceptance rules.

## Unit acceptance

`validate-unit-results.py` accepts one work-unit attempt on its own, from the
directory `run-unit.sh` writes. The unit directory holds a one-row
`unit-session.tsv` with these columns, in order:

```text
schema_version campaign_id platform unit_id replica_id unit_attempt_id instance_id host_epoch architecture instance_type availability_zone region
```

`unit_attempt_id` is `<host_session>/<lease_seq>`, the host session that
leased the unit and the lease's sequence number there, so each retry of a unit
is a distinct attempt. The validator rejects the attempt unless:

- the unit is one of the candidate's frozen units, its `unit-rows.tsv` is
  exactly the frozen rows of the unit, roles included, and its ID recomputes
  from its owned rows and representatives under the frozen row protocols
- `expected-rows.tsv` and `observed-rows.tsv` hold exactly the owned rows
- `route-plan.tsv` is the unit's plan for its replica, route order and
  calibration split included, and every route's session matches the unit
  session's host and the unit's shard
- the unit's `calibration.tsv`, from the first route's before and the last
  route's after calibration, drifted at most 5%; a unit measured on a worker
  host recomputes it from the two host calibrations it references, each
  present with its digest, and a chained unit, whose before is the previous
  unit's after, must reference them
- a chained unit's `unit-chain.txt`, bound in its evidence, names the lease
  before it on the same host, that lease's after calibration as its before, a
  gap of at most 60 seconds, and the same environment digest as its own; an
  unchained unit has none. The controller and the reducer also check that link
  against the previous lease's own completion marker, retained in the host
  session's directory as `lease-<n>-completion.json`: that lease completed, and
  its after calibration is the one the link names
- every route ran the frozen protocol: qualification or smoke, the default
  Joni JMH window, and exactly the JMH selectors that protocol plans for its
  share
- every route's Surefire reports are the files of its semantic receipt,
  whether that receipt was measured by this unit or cached from an earlier
  unit of the shard on the same host
- the representatives the routes ran are exactly the unit's for the replica,
  replica 1's for replica 4, and each passed the representative protocol rule
  below
- every artifact in `unit-evidence.tsv` verifies

Representative protocol evidence is accepted only when both sides declare
exactly 50 samples, contain finite positive scores and finite nonnegative
coefficients of variation, remain at or below 5% relative standard error, and
have a recomputed median-score difference at or below 5%. The validator
recomputes derived values instead of trusting the receipt fields.

Each row of `unit-evidence.tsv` names an artifact below the unit directory and
its verification mode. Its route rows bind each route's session, JMH
selectors, semantic gate, representative
protocol qualification when applicable, run metadata, normalized rows, status,
and raw-artifact manifest, and through recursive verification of each route's
`raw-artifacts.sha256`, every raw timing file and log. Its `host` rows bind the
environment and engine-build provenance, route plan, unit rows, expected and
observed rows, calibration, unit session, and the immutable
prefix of `unit-capacity.txt`. Any change to route metadata, raw measurements,
logs, comparator provenance, or the capacity identity after acceptance
invalidates the receipt.

GNU `time` finalizes wall time and peak RSS only after acceptance returns. The
receipt therefore commits the already-written memory-total and
available-before fields. The reducer additionally requires the completed
capacity fields and the immutable campaign archive binds the finalized file.
This two-stage contract avoids claiming a digest for values that do not yet
exist at acceptance time. A worker host's unit also has `host` rows with
verification `host-sha256` for its calibrations, which live once in the host
evidence as `calibrations/<sha256>.json`; `--host-evidence` names that
directory.

On success, the validator writes `unit-receipt.tsv`: the unit session, its
shard and owned row count, the digest of the unit's frozen row
protocols, the manifest, observed-row, and evidence-manifest digests, and the
candidate commit, archive, engine tree, and heap size.

```bash
tools/re2-benchmark/baseline/validate-unit-results.py \
    --unit-directory unit-attempt \
    --receipt unit-attempt/unit-receipt.tsv
```

A language unit attempt has no routes or calibrations, so `language/fleet.py`
accepts it instead, into the same receipt; the
[language guide](../language/README.md#work-units) lists its checks. Its
row-protocol digest covers the partition manifests and operations the unit
measures, its manifest digest is the plan's, and its observed-row digest covers
the exports of its partitions.

## Campaign acceptance

The candidate archive, provenance, and result root must remain outside Maven's
`target` directory so build cleanup cannot remove retained evidence.

Primary execution requires the complete accepted smoke result root through
`--smoke-results`. Confirmation execution similarly requires the complete
accepted primary result root through `--primary-results`. The controller
revalidates cleanup, capacity, exact artifacts, and regenerated receipts before
starting the next phase.

A phase keeps `accepted-units.tsv`: one `unit-receipt.tsv` row per accepted
unit replica, added atomically as soon as the attempt is accepted. Do not
reconstruct this ledger by concatenating result files. The phase leases units
to worker hosts and writes this ledger (`../aws/README.md`). Each attempt is
retained at `units/<platform>/<unit_id>/replica-<n>/<host_session>/lease-<seq>`
under the result root; a baseline attempt sits beside the host session's
`calibrations/` it references. On restart, recovery revalidates every ledger
row against that directory and adds a retained attempt the ledger lacks once it
passes the same checks: final status, accepted capacity, a receipt regenerated
from the evidence, and the frozen candidate. A baseline unit's receipt is
regenerated with its host's calibrations, a language unit's through the
[language unit checks](../language/README.md#work-units), and an engine
build's from its own archives. A `--release-version` campaign also requires
each route's attestation that it measured the release's pinned
JAR. The first accepted attempt of a unit replica wins; a later attempt that
also passes, such as one leased again after its host was judged lost, is
superseded and stays out of the ledger. The controller writes
`semantic-rejections.tsv` beside the ledger when the phase starts and adds each
attempt that exited 44, a failed correctness stage, atomically by `platform`,
`unit_id`, `replica_id`, and `unit_attempt_id` before acting on it. The phase
ends by validating the ledger as the campaign below, and a primary or
confirmation revalidates the previous phase's ledger and attempts first,
refusing a previous phase whose rejection ledger is missing or lists an
attempt, even when another attempt of that unit was accepted.

Campaign validation of baseline units requires every platform and frozen unit
of the selected shards, with replicas 1, 2, and 3. It also requires:

- the three replicas of a unit on distinct EC2 instances; different units may
  share a host, its host epoch, and its host session
- exactly the `r8i` and `r9g` platform identities
- globally distinct unit attempt IDs, and each instance, host epoch, and host
  session naming one host
- the architecture and instance type pinned for the unit's shard
- each receipt's owned row count and row-protocol digest
  recomputed from the frozen `units.tsv` and `row-protocol.tsv`, so every
  receipt names the same frozen units and protocols
- the frozen manifest digest and one candidate commit, engine tree, and
  source-archive identity across all attempts
- a `semantic-rejections.tsv` beside the ledger that lists no attempt
- with `--release-version`, each accepted attempt, retained beside the ledger,
  attesting on every route that it measured that release's pinned JAR

A language campaign applies the same rule to its plans' units, each plan
standing in for the frozen tables, and an engine build phase requires one
build on every platform, made by the checkout's build scripts. The
controller's phase gate applies these checks, except that it requires only
replica 1 for a build or smoke phase, and only the smoke units of a language
smoke, and leaves the rejection ledger to the next phase's prerequisite and the
reducer. The report importer revalidates the
published campaign's ledgers. A unit confirmation is replica `4`, at most one
per platform and unit, on an instance none of that unit's replicas used. Every
campaign validation checks this against the primary ledger: the controller's
confirmation phase gate, the reducer, and the report importer.

Run the isolated self-tests with:

```bash
python3 -m unittest discover -s tools/re2-benchmark/baseline/tests -v
```
