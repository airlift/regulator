# AWS baseline qualification

The AWS tools run two kinds of jobs: `baseline-shard` and `language-batch`.
Both are needed for full qualification. This guide gives the baseline commands;
see the [language guide](../language/README.md) for language collection.

`baseline/run-campaign.py` plans and controls baseline smoke, primary, and
confirmation runs. `language/fleet.py` prepares language plans, and
`language/campaign.py` controls their collection and acceptance. Standalone
engineering diagnostics are separate from both.

Do not collect performance evidence on a development machine. Local commands
may validate manifests, scripts, and protocol behavior, but performance
decisions require the pinned C8i, C8g, and C9g hosts in `baseline/platforms.tsv`.

## Machine-hours and cost

Budget roughly **1,500 machine-hours** for a full collection across all three
platforms, including both baseline and language comparisons. A machine-hour
means one entire EC2 instance running for one hour, not one vCPU-hour.
Parallel workers shorten elapsed time; they do not reduce total machine-hours.

This is a planning allowance, not a fixed requirement or an upper bound. The
September 2026 development capture recorded about 190 machine-hours for accepted
primary baseline sessions and 750 for accepted primary language sessions, about
940 combined. Those are measured host-session durations, not billed instance
lifetimes. They exclude smoke, confirmations, failed or redundant attempts,
later follow-ups, and startup outside the measured sessions.

The current baseline shard model estimates about 424 machine-hours for its
three primary replicas across three platforms, including bootstrap/build
allowances. Combining that estimate with the historical 750 language hours and
adding roughly 25% contingency gives the rounded 1,500-hour budget. Recheck the
job plans and smoke durations before launching. Corpus changes, slow cases,
packing, interruptions, and retries can move the total substantially.

For a dollar reference, Linux shared-tenancy On-Demand rates in `us-west-2`,
checked September 12, 2026, are:

| Instance | USD per machine-hour | 500 hours |
| --- | ---: | ---: |
| `c8i.2xlarge` | $0.37484 | $187.42 |
| `c8g.2xlarge` | $0.31904 | $159.52 |
| `c9g.2xlarge` | $0.34776 | $173.88 |
| Total, 1,500 hours evenly split | | **$520.82** |

Source: [AWS EC2 regional price catalog](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/us-west-2/index.csv),
rates effective September 1, 2026. About **$521 in On-Demand compute** is an
estimate at those rates, not the actual bill for the development capture.
It excludes EBS, S3, data transfer, and taxes. Use actual billable instance
lifetimes and market rates to reconcile spending; accepted benchmark durations
alone undercount it.

**Prefer Spot for full runs.** This is expensive enough that the discount
matters. The controller already tries Spot first and falls back to the same
On-Demand instance type when needed. Spot prices vary by time and availability
zone, and interrupted attempts consume paid time before restarting. Budget for
fallback and retries rather than assuming every hour gets a Spot discount.
The concurrency ceiling is not a spending cap.

For a focused change, budget only the affected cases and protected controls.
A source bracket measures three legs on each host, so include all three in its
estimate. That does not require running the entire release campaign three
times. Preserve accepted work and resume with unchanged frozen inputs instead
of starting collection over.

## Qualification inputs

Execution requires:

- a clean committed Regulator candidate
- a candidate ref under `refs/benchmarks/`
- the deterministic candidate archive and provenance produced by
  `baseline/freeze-candidate.sh`
- the pinned Trino checkout for shards that compare Joni or Trino LIKE
- the pinned Rebar checkout described in `../rebar/README.md`
- working AWS credentials from the standard AWS credential chain, or an
  explicitly selected profile, and the configured region

Keep the candidate archive, provenance, and result roots outside Maven's
`target` directory. The controller rejects volatile paths and revalidates the
candidate, manifests, and accepted receipts whenever it resumes.

The checked-in inputs are:

- `baseline/rows.tsv`: every accepted benchmark row
- `baseline/shards.tsv`: the qualification shards, including focused LIKE lifecycle collection
- `baseline/shard-dispatch.tsv`: route and system ownership
- `baseline/platforms.tsv`: instance, AMI, JDK, and package identities
- `baseline/comparators.tsv`: pinned comparator identities
- `baseline/protocol-representatives.tsv`: bounded/full protocol equivalence

Run the validators before allocating hosts:

```bash
python3 tools/re2-benchmark/baseline/validate-manifest.py
python3 tools/re2-benchmark/baseline/validate-protocol-representatives.py
python3 tools/re2-benchmark/baseline/validate-shard-plans.py
python3 tools/re2-benchmark/baseline/validate-mode-inventory.py
```

## Freeze a candidate

`freeze-candidate.sh` runs the full build, verifies the production engine tree,
creates an immutable local benchmark ref, and writes a reproducible archive and
provenance record:

```bash
tools/re2-benchmark/baseline/freeze-candidate.sh \
  refs/benchmarks/<candidate-name> \
  /durable/path/candidate.tar.gz \
  /durable/path/candidate-provenance.tsv
```

The output files must not already exist. The script never moves an existing
benchmark ref.

## Plan and execute

Without `--execute`, the controller writes the exact job manifest without
allocating AWS resources:

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py \
  smoke <campaign-id> \
  --result-root /durable/path/<campaign-id>/smoke
```

Execute the smoke after inspecting that plan:

```bash
TRINO_DIR=/path/to/trino \
python3 tools/re2-benchmark/baseline/run-campaign.py \
  smoke <campaign-id> --execute \
  --candidate-ref refs/benchmarks/<candidate-name> \
  --candidate-archive /durable/path/candidate.tar.gz \
  --candidate-provenance /durable/path/candidate-provenance.tsv \
  --result-root /durable/path/<campaign-id>/smoke \
  --max-concurrent 64
```

The primary phase requires the accepted smoke root and runs three independent
host replicas for every platform and shard:

```bash
TRINO_DIR=/path/to/trino \
python3 tools/re2-benchmark/baseline/run-campaign.py \
  primary <campaign-id> --execute \
  --candidate-ref refs/benchmarks/<candidate-name> \
  --candidate-archive /durable/path/candidate.tar.gz \
  --candidate-provenance /durable/path/candidate-provenance.tsv \
  --smoke-results /durable/path/<campaign-id>/smoke \
  --result-root /durable/path/<campaign-id>/primary \
  --max-concurrent 64
```

Use confirmation only for the bounded list emitted by preliminary reduction:

```bash
TRINO_DIR=/path/to/trino \
python3 tools/re2-benchmark/baseline/run-campaign.py \
  confirmation <campaign-id> --execute \
  --candidate-ref refs/benchmarks/<candidate-name> \
  --candidate-archive /durable/path/candidate.tar.gz \
  --candidate-provenance /durable/path/candidate-provenance.tsv \
  --primary-results /durable/path/<campaign-id>/primary \
  --confirmation-jobs /durable/path/confirmation-jobs.tsv \
  --result-root /durable/path/<campaign-id>/confirmation \
  --max-concurrent 64
```

The controller can start with fewer than 64 hosts; that number is a ceiling.
Jobs try Spot first, then the same On-Demand instance type. They wait in the
queue when quota or capacity prevents a launch. Interrupted Spot jobs restart
in a fresh host session.

Set `AWS_PROFILE` before any execution command when selecting a named profile;
otherwise the AWS CLI uses its standard credential chain. `TRINO_DIR` is
required because the full campaign includes Trino and Joni shards.

## Single-job wrapper

The controllers use `run-campaign.sh` to run one AWS job. A
`CAMPAIGN_MODE=baseline-shard` job selects one architecture, platform, shard, and
replica. A `CAMPAIGN_MODE=language-batch` job selects one language batch and its
frozen inputs. The wrapper verifies the archive, provisions an isolated
instance, uploads inputs through a private temporary S3 location, downloads
the result, and verifies instance, IAM, and S3 cleanup.

The fleet controller should normally invoke this wrapper. Direct invocation is
appropriate only while diagnosing one qualification job and still requires all
pinned identity variables.

`run-host.sh` runs on the instance. It accepts the same two
modes, verifies the AMI, JDK, package, candidate, comparator, and manifest
identities, and delegates to `baseline/run-host-session.sh` or
`language/run-host-session.sh`.

## Recovery and evidence

The controller atomically records each accepted job in a receipt ledger.
On restart, it revalidates saved artifacts, recovers receipts written just
before interruption, and skips accepted jobs. Conflicting receipts, changed
inputs, or unverifiable resources stop the campaign.

Failed jobs record their failure type. Independent jobs continue unless source
identity, resource ownership, or cleanup cannot be verified. Even a successful
benchmark is rejected if its cloud cleanup cannot be confirmed.

See:

- `../baseline/SHARD_DISPATCH.md` for routes and timing protocol
- `../baseline/ACCEPTANCE.md` for host and campaign receipts
- `../baseline/REPORTING.md` for reduction and report generation
- `../baseline/ARTIFACT_ARCHIVE.md` for permanent evidence storage
