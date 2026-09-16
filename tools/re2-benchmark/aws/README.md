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
decisions require the pinned R8i, R8g, and R9g hosts in `baseline/platforms.tsv`.

## Machine-hours and cost

A machine-hour means one entire EC2 instance running for one hour, not one
vCPU-hour. Parallel workers shorten elapsed time; they do not reduce total
machine-hours. Estimate the campaign from its frozen job inventory and full
smoke timings, including retries, startup, and result recovery. Record the
machine-hour and dollar ceilings in the campaign policy. Initial estimates are not limits for future collections.

Ordinary workers are `r8i.large`, `r8g.large`, and `r9g.large`, each with two
vCPUs and 16 GiB RAM. Preserve the 8 GiB measured heap, use a 64–256 MiB
launcher for forked JMH, and use 40 GiB gp3 roots after disk validation in
smoke. The `lifecycle-shared-cold` multicore shard uses the same generations'
8-vCPU `.2xlarge` workers. Count their actual vCPUs in admission.

The September 14 sizing survey observed ordinary Oregon Spot rates of
$0.0338–0.0593 per hour across these types and zones. Refresh prices before
freezing per-type ceilings. These observations exclude storage, public IPv4,
transfer, and larger concurrency workers. The shared ledger uses conservative
elapsed wrapper lifetimes and frozen total hourly rates, including failures.
Reconcile these estimates with billable instance lifetimes afterward.

Use `--spot-only` to prohibit On-Demand workers and fallback. Preserve
interrupted attempts and retry on fresh Spot hosts within the frozen retry
limits. Other users' running and pending Spot work still consumes quota.

## Shared fleet budget

Run both collectors on one persistent coordinator filesystem with renewable
AWS credentials. Supply the same `--fleet-budget PATH` and `--spot-only` to
every phase. Set `--spot-vcpu-reserve` to the policy's reserved headroom and
use `--release-version 1.0` when reproducing the published release.

The policy records the expected AWS account and region, resource and spending
ceilings, and hourly price ceilings for each selected worker type. The
controller checks the active account and region against it before execution.
Set `AWS_REGION` to that region and update the region-specific AMI pins before
freezing a new campaign. The worker types remain those in `platforms.tsv`.

For example, a policy for at most 16 ordinary workers could contain:

```json
{
  "schema_version": 1,
  "account": "123456789012",
  "region": "us-west-2",
  "market": "spot",
  "spot_vcpu_reserve": 16,
  "max_vcpus": 32,
  "max_hosts": 16,
  "max_pending_launches": 4,
  "max_machine_hours": 100,
  "max_usd": 20,
  "max_attempt_seconds": 7200,
  "ancillary_reserve_usd": 5,
  "minimum_launch_interval_seconds": 1,
  "hourly_rates": {
    "r8i.large": {"spot": 0.08, "total": 0.10},
    "r8g.large": {"spot": 0.08, "total": 0.10},
    "r9g.large": {"spot": 0.08, "total": 0.10}
  }
}
```

Replace the example account, limits, and rates with the selected campaign
values. Include `.2xlarge` price ceilings when running multicore shards.
`spot` is the maximum Spot bid; `total` includes storage and other hourly
costs. The controller validates the policy when loading it. Freeze it with
the job inventory; changing it requires a new campaign rather than a resume.

The neighboring `fleet-state.json` coordinates both collectors with `flock`.
It counts instances, outstanding requests, and launches not yet visible to
EC2. Reservations remain until verified cleanup; crashes never expire them.
A failed benchmark or rejected artifact leaves that job unaccepted and
unresolved after its cleanup is proven. Independent jobs continue, including
queued work. These rejections do not trigger automatic retries or weaken the
acceptance gates. Unproven cleanup, changed frozen inputs, conflicting accepted
identities and shared resource-policy failures halt both collectors. See the
[protocol rules](../baseline/SHARD_DISPATCH.md#jmh-protocol).
Preserve the policy and state together across restarts. More than 64 executing
workers requires this shared policy. Ramp from canaries to the approved cap,
using the configured pending-launch limit and frozen launch interval.

The controller defaults to 64 concurrent workers. Set `--max-concurrent` and
`--max-concurrent-per-platform` for the planned campaign; the shared policy
and live quota checks enforce aggregate capacity across collectors. Multicore
workers, reserved headroom, unrelated Spot use, and spending reservations can
reduce actual concurrency. Derived phase deadlines use the configured host,
per-platform, and shared vCPU limits, plus one retry wave and a capacity
allowance. Without a shared policy, deadline planning uses host counts; live
quota shortages can require a larger explicit deadline. An explicit
`--phase-timeout-seconds` is frozen on first execution and cannot change on
resume.

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
  --max-concurrent 16 --spot-only --spot-vcpu-reserve 16 \
  --release-version 1.0 --fleet-budget /durable/path/fleet-policy.json
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
  --max-concurrent 16 --spot-only --spot-vcpu-reserve 16 \
  --release-version 1.0 --fleet-budget /durable/path/fleet-policy.json
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
  --max-concurrent 16 --spot-only --spot-vcpu-reserve 16 \
  --release-version 1.0 --fleet-budget /durable/path/fleet-policy.json
```

Start with one canary per platform, then run full smoke before primary.
Use `--smoke-protocol qualification` for baseline duration qualification. The
default `smoke` protocol shortens timings and cannot establish a full shard's
duration. The selection is frozen on execution and cannot change on resume.
Spot jobs wait when quota or capacity prevents a launch. An interrupted job
restarts on a fresh Spot host after verified cleanup.

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

Failed jobs record their failure type. Unclassified benchmark failures and
invalid artifacts remain unresolved after verified cleanup while independent
jobs continue. Unverifiable cleanup and other unsafe controller failures stop
admissions across both collectors.

See:

- `../baseline/SHARD_DISPATCH.md` for routes and timing protocol
- `../baseline/ACCEPTANCE.md` for host and campaign receipts
- `../baseline/REPORTING.md` for reduction and report generation
- `../baseline/ARTIFACT_ARCHIVE.md` for permanent evidence storage

### Recovering uploads during cleanup

After verifying instance termination, the wrapper inventories and downloads
all uploaded result objects into the session's `recovered-uploads` directory.
It records each object's size and local SHA-256 before deleting the transfer
prefix. A failed inventory or download retains that prefix and fails cleanup.
Recovered uploads are raw evidence and do not become accepted sessions.

This covers late uploads and results available when a controller aborts.
Measurements still on an interrupted or forcibly terminated host may never
reach S3; the recovery receipt states that limitation. After cleanup is proven, a benchmark or artifact
rejection leaves its host unaccepted while unrelated jobs finish.
