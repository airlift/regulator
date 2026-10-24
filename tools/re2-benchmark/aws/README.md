# AWS baseline qualification

The AWS tools run three kinds of jobs: `baseline-shard`, `language-batch`, and
`engine-build`. The first two are needed for full qualification; the third
builds engines for the first. This guide gives the baseline commands;
see the [language guide](../language/README.md) for language collection.

`baseline/run-campaign.py` plans and controls the baseline engine build and the
smoke, primary, and confirmation runs. `language/fleet.py` prepares language
plans, and `language/campaign.py` controls their collection and acceptance.
Standalone engineering diagnostics are separate from both.

Do not collect performance evidence on a development machine. Local commands
may validate manifests, scripts, and protocol behavior, but performance
decisions require the pinned R8i and R9g hosts in `baseline/platforms.tsv`.

## Machine-hours and cost

A machine-hour means one entire EC2 instance running for one hour, not one
vCPU-hour. Parallel workers shorten elapsed time; they do not reduce total
machine-hours. Estimate each frozen cohort from complete pilot-job durations,
including startup, verification, result recovery and retries. Record limits
in the campaign policy and account for other users' capacity reservations.

The [1.0 cost ledger](../../../docs/benchmarks/RESULTS_1.0.md#cost-and-reproduction-budget)
separates the initial campaign, measurement-quality follow-up, and successful
replacement-collection floor. The initial campaign recorded 1,983.84 wrapper
machine-hours and an estimated $165 including its ancillary reserve. Its
roughly 2,000-hour / $200 planning example applies to that original protocol.
It does not price the longer follow-up protocols or the combined work.

Estimate a fresh run by summing `hours[type] * spot_price[type]` across instance
types. Add storage, public IPv4, transfer and interruption allowances. For
example, 2,000 hours at a runtime-weighted $0.075 per instance-hour is $150 in
compute, but the required hours and mix must come from the selected protocol.
An eight-vCPU `.2xlarge` hour costs differently from a two-vCPU `.large` hour.
A successful-job floor excludes failed attempts and qualification diagnostics;
it is not a complete campaign budget or a guaranteed maximum.

Ordinary workers are `r8i.large` and `r9g.large`, each with two vCPUs and
16 GiB RAM. Preserve the 8 GiB measured heap, use a 64–256 MiB launcher for
forked JMH, and use 40 GiB gp3 roots after disk validation.
The original `lifecycle-shared-cold` shard uses eight-vCPU `.2xlarge` workers.
The measurement-quality follow-up also uses `r9g.2xlarge` for Trino, lifecycle
and selected traditional/LIKE cohorts, based on exact production integrations
and allocation controls. Other language work, including slow dictionary
measurements, stays on `.large`. Count actual allocated vCPUs in admission;
measured JVM CPU affinity is a separate protocol setting.

The September 14 sizing survey observed ordinary Oregon Spot rates of
$0.0338–0.0593 per hour across the small types and zones. Refresh prices before
planning. The follow-up cost estimate combines observed instance state,
CloudTrail launch/shutdown events and verified cleanup. Wrapper lifetimes
include local preparation and recovery; they are not exact billed running
time. Saved price estimates are not a reconciled AWS invoice.

Without `--spot-only`, a job whose Spot host is reclaimed, or that cannot get
Spot capacity, retries on the same On-Demand instance type; any other
retryable failure gets one more Spot attempt first. A Spot launch that finds
no capacity in its zone tries each other zone of its region that offers the
type before it fails. When several regions are allowed, a Spot launch that
found no capacity in any zone first retries Spot in the next allowed region it
has not tried, in placement order, and moves to On-Demand only after every
allowed region has failed. These region moves do not count against the job's
replacement allowance. Once the unaccepted jobs number at most 10% of the
phase's jobs, rounded up, the remaining launches use On-Demand, so a phase's
last hosts do not wait on Spot capacity. Budget these hours at On-Demand
prices.

Use `--spot-only` to prohibit On-Demand workers and fallback. Preserve
interrupted attempts and retry on fresh Spot hosts within the frozen retry
limits. A Spot launch that found no capacity still moves through the allowed
regions it has not tried, without spending a replacement, and then retries Spot
within those limits. Other users' running and pending Spot work still consumes
quota. Keep one owner for each mutable capacity limit. Transfer reservations
only after cleanup or a complete finite-inventory bound proves they are
available. A temporary gap between worker launches is not free reserved
capacity.

## Shared fleet budget

Run both collectors on one persistent coordinator filesystem with renewable
AWS credentials. Supply the same `--fleet-budget PATH` to every phase, with
`--spot-only` unless the policy allows On-Demand. Set `--spot-vcpu-reserve` to
the policy's reserved headroom and use `--release-version 1.0` when
reproducing the published release.

The policy records the expected AWS account and home region, resource and
spending ceilings, and hourly price ceilings for each selected worker type.
The controller checks the active account against it, and every allowed launch
region against the policy's regions, before execution. The top-level
`max_vcpus` and `spot_vcpu_reserve` limit the home region. A campaign that may
launch elsewhere adds a `regions` object naming each other region's own
`max_vcpus` and `spot_vcpu_reserve`, because Spot quotas are regional:

```json
"regions": {"eu-west-1": {"max_vcpus": 64, "spot_vcpu_reserve": 16}}
```

Admission counts each region's quota, reserve, vCPU limit, and pending
launches against that region's instances and reservations in the same market.
Each reservation records its region and market. Hosts, machine hours,
spending, and the launch interval remain fleet-wide. The hourly price ceilings
apply in every region, so set them for the most expensive allowed region.
Policies without `regions` keep their single-region meaning. The worker types
remain those in `platforms.tsv`.

A policy without `on_demand_max_vcpus` refuses On-Demand, so its campaigns
require `--spot-only`. To allow On-Demand replacements and phase tails, set
`on_demand_max_vcpus` at the top level for the home region, or in a region's
entry for that region, and give every worker type an `on_demand_total` hourly
cost:

```json
"on_demand_max_vcpus": 400,
"regions": {"eu-west-1": {"max_vcpus": 64, "spot_vcpu_reserve": 16, "on_demand_max_vcpus": 16}}
```

The allowance bounds only the fleet's own On-Demand vCPUs in that region.
Other workloads' On-Demand use and a 32-vCPU safety reserve come out of the
region's On-Demand quota, which admission checks the same way as Spot quota.
On-Demand hosts count against the same host, machine-hour, and spending
ceilings, at their `on_demand_total` rate. Regions without an allowance launch
only Spot, and an On-Demand launch takes the first allowed region that admits
it. Without `--spot-only`, each worker type needs an allowance in an allowed
region that holds its image in `platform-regions.tsv`.

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
    "r9g.large": {"spot": 0.08, "total": 0.10}
  }
}
```

Replace the example account, limits, and rates with the selected campaign
values. Include `.2xlarge` price ceilings when running multicore shards. `spot`
is the maximum Spot bid; `total` includes storage and other hourly costs, and
`on_demand_total` is that hourly total for an On-Demand host. The controller
validates the policy when loading it. Freeze it with the job inventory; changing
it requires a new campaign rather than a resume.

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
allowance; the shared limit is the sum of the allowed regions' `max_vcpus`.
Every worker type must fit the largest
`max_vcpus` among the allowed regions that hold its image. Without a shared
policy, deadline planning uses host counts; live quota shortages can require
a larger explicit deadline. An explicit
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
- `baseline/platform-regions.tsv`: each platform's AMI ID in each launch region
- `baseline/comparators.tsv`: pinned comparator identities
- `baseline/protocol-representatives.tsv`: bounded/full protocol equivalence
- `baseline/row-protocol.tsv`: each row's frozen qualification protocol

Run the validators before allocating hosts:

```bash
python3 tools/re2-benchmark/baseline/validate-manifest.py
python3 tools/re2-benchmark/baseline/validate-protocol-representatives.py
python3 tools/re2-benchmark/baseline/validate-row-protocol.py
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

Build each platform's engines once before the smoke. The build phase runs one
job per platform on its pinned instance type and AMI; see
[engine builds](#engine-builds):

```bash
TRINO_DIR=/path/to/trino \
python3 tools/re2-benchmark/baseline/run-campaign.py \
  build <campaign-id> --execute \
  --candidate-ref refs/benchmarks/<candidate-name> \
  --candidate-archive /durable/path/candidate.tar.gz \
  --candidate-provenance /durable/path/candidate-provenance.tsv \
  --result-root /durable/path/<campaign-id>/build \
  --spot-only --spot-vcpu-reserve 16
```

`--engine-builds` requires a shared transfer bucket in every allowed region
and `TRANSFER_PREFIX`; see [launch regions](#launch-regions). The examples
below run in one region with one shared bucket.

Execute the smoke after inspecting the smoke plan above:

```bash
TRINO_DIR=/path/to/trino TRANSFER_PREFIX=<transfer-prefix> \
python3 tools/re2-benchmark/baseline/run-campaign.py \
  smoke <campaign-id> --execute \
  --candidate-ref refs/benchmarks/<candidate-name> \
  --candidate-archive /durable/path/candidate.tar.gz \
  --candidate-provenance /durable/path/candidate-provenance.tsv \
  --result-root /durable/path/<campaign-id>/smoke \
  --engine-builds /durable/path/<campaign-id>/build \
  --transfer-bucket us-west-2=<bucket> \
  --max-concurrent 16 --spot-only --spot-vcpu-reserve 16 \
  --release-version 1.0 --fleet-budget /durable/path/fleet-policy.json
```

The primary phase requires the accepted smoke root and runs three independent
host replicas for every platform and shard:

```bash
TRINO_DIR=/path/to/trino TRANSFER_PREFIX=<transfer-prefix> \
python3 tools/re2-benchmark/baseline/run-campaign.py \
  primary <campaign-id> --execute \
  --candidate-ref refs/benchmarks/<candidate-name> \
  --candidate-archive /durable/path/candidate.tar.gz \
  --candidate-provenance /durable/path/candidate-provenance.tsv \
  --smoke-results /durable/path/<campaign-id>/smoke \
  --result-root /durable/path/<campaign-id>/primary \
  --engine-builds /durable/path/<campaign-id>/build \
  --transfer-bucket us-west-2=<bucket> \
  --max-concurrent 16 --spot-only --spot-vcpu-reserve 16 \
  --release-version 1.0 --fleet-budget /durable/path/fleet-policy.json
```

Use confirmation only for the bounded list emitted by preliminary reduction:

```bash
TRINO_DIR=/path/to/trino TRANSFER_PREFIX=<transfer-prefix> \
python3 tools/re2-benchmark/baseline/run-campaign.py \
  confirmation <campaign-id> --execute \
  --candidate-ref refs/benchmarks/<candidate-name> \
  --candidate-archive /durable/path/candidate.tar.gz \
  --candidate-provenance /durable/path/candidate-provenance.tsv \
  --primary-results /durable/path/<campaign-id>/primary \
  --confirmation-jobs /durable/path/confirmation-jobs.tsv \
  --result-root /durable/path/<campaign-id>/confirmation \
  --engine-builds /durable/path/<campaign-id>/build \
  --transfer-bucket us-west-2=<bucket> \
  --max-concurrent 16 --spot-only --spot-vcpu-reserve 16 \
  --release-version 1.0 --fleet-budget /durable/path/fleet-policy.json
```

Start with one canary per platform, then run full smoke before primary.
Use `--smoke-protocol qualification` for baseline duration qualification. The
default `smoke` protocol shortens timings and cannot establish a full shard's
duration. The selection is frozen on execution and cannot change on resume.
Spot jobs wait when quota or capacity prevents a launch. An interrupted job
restarts after verified cleanup: on a fresh Spot host with `--spot-only`, and
otherwise On-Demand.

Set `AWS_PROFILE` before any execution command when selecting a named profile;
otherwise the AWS CLI uses its standard credential chain. `TRINO_DIR` is
required because the full campaign includes Trino and Joni shards.

### Launch regions

Hosts launch in `AWS_REGION`, or `us-west-2` when it is unset. Repeat
`--region` to allow several regions; benchmarks do not depend on the region.
For each Spot launch, the controller asks AWS for the Spot placement score of
the worker type in every allowed region that offers it, caches the answer for
five minutes, and launches in the best-scored region with quota headroom.
Equal scores go to the earlier `--region`. When scores are unavailable, the
first allowed region with headroom launches and the session records no score.
On-Demand launches take the first allowed region with headroom. Headroom is
the shared budget's when one is set. Without one, On-Demand launches query
quota, and Spot launches query it only with `--spot-only` or a
`--max-concurrent` above 32; otherwise a Spot launch takes the first placement
and learns of a full region from EC2. A campaign
with one region asks for no score. Within the chosen region, each attempt
starts in a different zone that offers the worker type, and a Spot launch that
finds a zone without capacity moves on to the region's next zone. Each session
runs and verifies its cleanup in its own region.

`platforms.tsv` pins each AMI by its ID in `us-west-2`. Preflight resolves that
image's name and requires each allowed region's AMI in `platform-regions.tsv`
to have that exact name, Amazon's owner ID, and the platform's architecture.
Hosts record the name, and a refill accepts another region's copy of an
original host's image by name. The controller refuses an allowed region with
no row in the table. Amazon deprecates its images after about three months;
deprecated images still launch by ID but are hidden from name searches. To add
a region, find the pinned image's copy there:

```bash
aws ec2 describe-images --region <region> --owners 137112412989 --include-deprecated \
  --filters "Name=name,Values=$(aws ec2 describe-images --region us-west-2 \
  --image-ids <pinned-ami-id> --query 'Images[0].Name' --output text)" \
  --query 'Images[0].ImageId' --output text
```

Hosts read campaign inputs from a bucket in their launch region. Without a
shared bucket, each session creates and removes its own bucket there. To reuse
shared buckets, give each region's bucket with `--transfer-bucket
REGION=BUCKET` and set `TRANSFER_PREFIX`; regions without one use per-session
buckets, but `--engine-builds` requires one in every allowed region. Each
shared bucket must carry the `Project=regulator-benchmark` tag, and preflight
checks the tag and the bucket's region. A single-region campaign may still set
`TRANSFER_BUCKET`; a multi-region campaign must use `--transfer-bucket`
instead. Every session uploads its own inputs to its bucket, except the engine
build archives described below. To create a shared bucket, omitting
`--create-bucket-configuration` in `us-east-1`:

```bash
aws s3api create-bucket --bucket <bucket> --region <region> \
  --create-bucket-configuration LocationConstraint=<region>
aws s3api put-public-access-block --bucket <bucket> --region <region> \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-tagging --bucket <bucket> --region <region> \
  --tagging 'TagSet=[{Key=Project,Value=regulator-benchmark}]'
```

### Engine builds

The `build` phase builds the native RE2 benchmark, the Rebar engines and native
runners, and the pinned Trino harness once per platform, exactly as a baseline
host would. Each build records the compiler's expansion of `-march=native` or
`-mcpu=native` and the builder's CPU identity, and freezes its outputs in
`native.tar.gz` and `trino.tar.gz` under a manifest, `engine-build.json`. The
manifest lists each archive's SHA-256 and per-file hashes, and its inputs: the
candidate, the Rebar and Trino commits, the toolchain packages, and the build
scripts. Accepted builds go to `accepted-engine-builds.json`.

Smoke, primary, and confirmation execution require either `--engine-builds`,
naming an accepted build root for every platform, or `--local-engine-builds`,
which has every host build its own engines as before. The choice and each
platform's manifest SHA-256 are frozen in `execution-policy.json`, and primary
and confirmation refuse a prerequisite that used different builds. Policies
recorded before the build phase existed resume as local builds. Language
batches do not use engine builds; their Rust comparator still builds on the
host.

Hosts check every downloaded archive with `sha256sum --check`, then verify each
archive and installed file against the manifest; a mismatch fails the session.
After installing packages, the host computes its own native target and CPU
identity. If either differs from the builder's, it ignores the native archive
and builds the native engines locally, as before. The Java harness is installed
either way. `engine-build.txt` records the choice and its reason beside the
host evidence and in each route, and every route's `run-metadata.txt` records
whether each engine was prebuilt, with its archive SHA-256, or built locally.

Preflight names each allowed region that lacks the shared transfer bucket
`--engine-builds` requires. Before it launches any host,
the controller puts each archive once in every region that may launch a host
installing it, under `TRANSFER_PREFIX/engine-builds/<sha256>.tar.gz`, and skips
an object with that key and checksum. An upload slower than 256 KiB/s counts as
stalled and is stopped and retried; the campaign stops after three failed
attempts. A session whose archive is missing from its bucket fails as
`retryable-infrastructure` before it launches. Each session's instance role
may read exactly those archive keys, in addition to its own session prefix.
These archives are campaign inputs rather than session resources, so session
cleanup leaves them in place. Remove them once the campaign is done:

```bash
aws s3 rm "s3://<bucket>/<transfer-prefix>/engine-builds/" --recursive --region <region>
```

`--local-engine-builds` uploads no archives and needs no shared bucket.

## Single-job wrapper

The controllers use `run-campaign.sh` to run one AWS job. A
`CAMPAIGN_MODE=baseline-shard` job selects one architecture, platform, shard,
and replica. A `CAMPAIGN_MODE=language-batch` job selects one language batch and
its frozen inputs. A `CAMPAIGN_MODE=engine-build` job builds one platform's
engines. The wrapper verifies the archive, provisions an isolated instance,
uploads inputs through a private temporary S3 location, downloads the result,
and verifies instance, IAM, and S3 cleanup.

The fleet controller should normally invoke this wrapper. Direct invocation is
appropriate only while diagnosing one qualification job and still requires all
pinned identity variables. The language fleet launches through the same
controller and wrapper, so it uses the same launch regions.

`run-host.sh` runs on the instance. It accepts the same three
modes, verifies the AMI, JDK, package, candidate, comparator, and manifest
identities, and delegates to `baseline/run-host-session.sh`,
`language/run-host-session.sh`, or `baseline/build-engines.sh`. Language
batches and engine builds, like Rebar shards, install the pinned Cargo and Rust
packages, because the Rust language comparator builds the pinned `regex` crate
on the host and the engine build compiles Rebar.

## Recovery and evidence

The controller atomically records each accepted job in a receipt ledger.
On restart, it revalidates saved artifacts, recovers receipts written just
before interruption, and skips accepted jobs. Conflicting receipts, changed
inputs, or unverifiable resources stop the campaign.

Failed jobs record their failure type. A session that fails before its first
RunInstances request, in setup such as the default VPC lookup or the instance
profile, is `retryable-infrastructure`, since no instance can exist; cleanup
still removes and verifies the bucket and profile it created. Unclassified
benchmark failures and invalid artifacts remain unresolved after verified
cleanup while independent jobs continue. Unverifiable cleanup and other unsafe
controller failures stop admissions across both collectors.

A Spot-only campaign can exhaust its retries when one platform runs out of
Spot capacity. Once no job awaits an ordinary retry, rerun the phase in the
same result root with `--execute --refill-unresolved`, without `--spot-only`
or `--fleet-budget`, to give each exhausted job one more attempt on
On-Demand. Restart an interrupted refill with the same flag and policy.
A refill attempt that fails stays unresolved and gets no second refill; only
a launch waiting for On-Demand quota is retried.

A refill leaves `execution-policy.json` unchanged and records its own policy,
start time, and granted jobs in `refill-execution-policy.json`,
`refill-state.tsv`, and `refill-jobs.tsv`. Hosts measure the candidate
archive, so a refill may run from a newer controller checkout and identifies
the candidate by `--candidate-ref`. Each refill host's environment must match
the campaign's accepted original hosts, so those hosts must already cover
every platform, shard, and Trino use the refill needs, or it refuses before
launching.

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
