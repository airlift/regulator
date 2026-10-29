# Exact-row acceptance

These validators check results independently of the AWS controller. They read
tab-separated files, giving local checks, EC2 runs, and final aggregation the
same acceptance rules.

## Unit acceptance

`validate-unit-results.py` accepts one measured group of a work-unit attempt
on its own, from the directory `run-unit.sh` writes. The directory holds a
one-row `unit-session.tsv` with these columns, in order:

```text
schema_version campaign_id platform unit_id replica_id group_id unit_attempt_id instance_id host_epoch architecture instance_type availability_zone region
```

`unit_attempt_id` is `<host_session>/<lease_seq>`, the host session that
leased the unit and the lease's sequence number there, so each retry of a unit
is a distinct attempt; `group_id` names the
[measured group](SHARD_DISPATCH.md#work-units) of the unit it measured. The
validator rejects the group unless:

- the unit is one of the candidate's frozen units, its `unit-rows.tsv` is
  exactly the frozen rows of the whole unit, roles and groups included, and
  its ID recomputes from its owned rows and representatives under the frozen
  row protocols, and the group is one of its groups
- `expected-rows.tsv` and `observed-rows.tsv` hold exactly the group's rows
- `route-plan.tsv` is the group's plan for its replica, route order included,
  and every route's session matches the unit session's host, unit, and shard
- every route's `run-metadata.txt` records the host's CPU steal ticks and
  total ticks over the route, with the steal no greater than a nonzero total
- every route ran the frozen protocol: qualification or smoke, the default
  Joni JMH window, and exactly the JMH selectors that protocol plans for its
  share
- every route's semantic test reports are the files of its semantic receipt,
  whether that receipt was measured by this unit or cached from an earlier
  unit of the shard on the same host
- the representatives the routes ran are exactly the group's for the replica,
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
observed rows, unit session, and the immutable
prefix of `unit-capacity.txt`. Any change to route metadata, raw measurements,
logs, comparator provenance, or the capacity identity after acceptance
invalidates the receipt.

GNU `time` finalizes wall time and peak RSS only after acceptance returns. The
receipt therefore commits the already-written memory-total and
available-before fields. The reducer additionally requires the completed
capacity fields and the immutable campaign archive binds the finalized file.
This two-stage contract avoids claiming a digest for values that do not yet
exist at acceptance time.

On success, the validator writes `unit-receipt.tsv`: the unit session, its
shard and the group's row count, the digest of the group's frozen row
protocols, the manifest, observed-row, and evidence-manifest digests, and the
candidate commit, archive, engine tree, and heap size.

```bash
tools/re2-benchmark/baseline/validate-unit-results.py \
    --unit-directory unit-attempt \
    --receipt unit-attempt/unit-receipt.tsv
```

A language unit attempt has no routes, so `language/fleet.py`
accepts it instead, into the same receipt; the
[language guide](../language/README.md#work-units) lists its checks. Its
row-protocol digest covers the partition manifests and operations the group
measures, its manifest digest is the plan's, and its observed-row digest covers
the exports of its partitions.

## Campaign acceptance

The candidate archive, provenance, and result root must remain outside Maven's
`target` directory so build cleanup cannot remove retained evidence.

Primary execution requires the complete accepted smoke result root through
`--smoke-results`. Confirmation execution similarly requires the complete
accepted primary result root through `--primary-results`, and smoke, primary,
and confirmation require the accepted build root through `--engine-builds`. A
phase's controller writes `phase-complete.txt` in its result root only after it
has verified every worker's cleanup and the phase gate has passed. Before
starting the next phase, the controller requires that marker, then revalidates
capacity, exact artifacts, and regenerated receipts.

A phase keeps `accepted-units.tsv`: one `unit-receipt.tsv` row per accepted
group of a unit replica, added atomically as soon as the group is accepted. Do
not reconstruct this ledger by concatenating result files. The phase leases
units to worker hosts and writes this ledger (`../aws/README.md`). Each
accepted group is retained at
`units/<platform>/<unit_id>/replica-<n>/<host_session>/lease-<seq>/group-<g>`
under the result root. On restart, recovery revalidates every ledger
row against that directory and adds a retained attempt the ledger lacks once it
passes the same checks: final status, accepted capacity, a receipt regenerated
from the evidence, and the frozen candidate. A baseline unit's receipt is
regenerated from its own attempt, a language unit's through the
[language unit checks](../language/README.md#work-units), and an engine
build's from its own archives. A `--release-version` campaign also requires
each route's attestation that it measured the release's pinned
JAR. The first accepted attempt of a unit replica's group wins; a later attempt
that also passes, such as one leased again after its host was judged lost, is
superseded and stays out of the ledger. The controller writes
`semantic-rejections.tsv` beside the ledger when the phase starts and adds each
attempt that exited 44, a failed correctness stage, atomically by `platform`,
`unit_id`, `replica_id`, and `unit_attempt_id` before acting on it. The phase
ends by validating the ledger as the campaign below, and a primary or
confirmation revalidates the previous phase's ledger and attempts first,
refusing a previous phase whose rejection ledger is missing or lists an
attempt, even when another attempt of that unit was accepted.

Campaign validation of baseline units requires every group of every platform
and frozen unit of the selected shards, once each, with replicas 1, 2, and 3. A
replica may gather its groups from several attempts, such as one interrupted
after its first group and the attempt that measured the rest. It also requires:

- no EC2 instance measuring groups of two replicas of one unit, confirmation
  included; different units may share a host, its host epoch, and its host
  session
- exactly the `r8i` and `r9g` platform identities
- each pair of unit attempt ID and group at most once, and each instance, host
  epoch, and host session naming one host
- the architecture and instance type pinned for the unit's shard
- each receipt's row count and row-protocol digest, those of its group,
  recomputed from the frozen `units.tsv`, `unit-rows.tsv`, and
  `row-protocol.tsv`, so every receipt names the same frozen units and
  protocols
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
published campaign's ledgers. A unit confirmation is replica `4`, covering
every group of the unit once per platform, on instances none of that unit's
replicas used. Every
campaign validation checks this against the primary ledger: the controller's
confirmation phase gate, the reducer, and the report importer.

Run the isolated self-tests with:

```bash
python3 -m unittest discover -s tools/re2-benchmark/baseline/tests -v
```
