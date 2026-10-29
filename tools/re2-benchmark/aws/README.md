# AWS baseline qualification

The AWS tools run one kind of job, a `unit-worker`. Full qualification first
builds each platform's Trino harness and native runners once, as a build-phase
work unit, then measures baseline and language work units on unit workers. This
guide gives the baseline commands; see the
[language guide](../language/README.md) for language collection.

`baseline/run-campaign.py` plans and controls the engine build, the smoke,
primary, and confirmation runs of baseline shards, and the smoke and primary
runs of the language plans that `language/fleet.py` prepares.

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
On R9g the language lifecycle suite uses eight-vCPU `.2xlarge` workers. Other
language work, including slow dictionary measurements, and all R8i work stay
on `.large`. Count actual allocated vCPUs in admission; measured JVM CPU
affinity is a separate protocol setting.

The September 14 sizing survey observed ordinary Oregon Spot rates of
$0.0338–0.0593 per hour across the small types and zones. Refresh prices before
planning. The follow-up cost estimate combines observed instance state,
CloudTrail launch/shutdown events and verified cleanup. Wrapper lifetimes
include local preparation and recovery; they are not exact billed running
time. Saved price estimates are not a reconciled AWS invoice.

A Spot launch that finds no capacity in its zone tries each other zone of its
region that offers the type before it fails. A unit whose worker is reclaimed
or fails goes back to its pool, as [unit dispatch](#unit-dispatch) describes.
Without `--spot-only`, once a pool's remaining estimated work falls to 10% of
its total, its last items run on On-Demand workers, so a phase's last units do
not wait on Spot capacity. Budget these hours at On-Demand prices.

Use `--spot-only` to prohibit On-Demand workers. Other users' running and
pending Spot work still consumes quota. Keep one owner for each mutable capacity
limit. Transfer reservations only after cleanup or a complete finite-inventory
bound proves they are available. A temporary gap between worker launches is not
free reserved capacity.

## Shared fleet budget

Run both collectors on one persistent coordinator filesystem with renewable
AWS credentials. Supply the same `--fleet-budget PATH` to every phase, with
`--spot-only` unless the policy allows On-Demand. Set `--spot-vcpu-reserve` to
the policy's reserved headroom and use `--release-version 2.0` when measuring
the 2.0 release. The option names the release manifest
`tools/re2-benchmark/baseline/releases/<version>.json`, and the controller runs
only from source whose `src/main` tree matches that release. The
[maintenance guide](../../../docs/MAINTAINING_REGULATOR.md#final-qualification)
says how to record a release's manifest. Remeasure the 1.0 artifact with
`--release-version 1.0` from
[commit `32406d25`](https://github.com/airlift/regulator/tree/32406d25a6032b15d366cddb414ade57c972d0ee);
the published campaign's collector revisions are in its capture provenance.

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
  "max_attempt_seconds": 28800,
  "ancillary_reserve_usd": 5,
  "minimum_launch_interval_seconds": 1,
  "hourly_rates": {
    "r8i.large": {"spot": 0.08, "total": 0.10},
    "r9g.large": {"spot": 0.08, "total": 0.10}
  }
}
```

Replace the example account, limits, and rates with the selected campaign
values. Include the R9g `.2xlarge` price ceiling when running the language
lifecycle suite. `spot` is the maximum Spot bid; `total` includes storage and
other hourly costs, and `on_demand_total` is that hourly total for an
On-Demand host. `max_attempt_seconds` must cover a unit worker's wrapper timeout
and its cleanup, at least 27900 seconds. The controller validates the policy
when loading it. Freeze it with the job inventory; changing it requires a new
campaign rather than a resume.

The neighboring `fleet-state.json` coordinates both collectors with `flock`. It
counts instances, outstanding requests, and launches not yet visible to EC2.
Reservations remain until verified cleanup; crashes never expire them. A
rejected work unit follows the [unit dispatch](#unit-dispatch) retry rules.
Unproven cleanup, changed frozen inputs, conflicting accepted identities and
shared resource-policy failures halt both collectors. See the
[protocol rules](../baseline/SHARD_DISPATCH.md#jmh-protocol). Preserve the
policy and state together across restarts. More than 64 executing workers
requires this shared policy. Ramp from canaries to the approved cap, using the
configured pending-launch limit and frozen launch interval.

The controller defaults to 64 concurrent workers. Set `--max-concurrent` and
`--max-concurrent-per-platform` for the planned campaign; the shared policy
and live quota checks enforce aggregate capacity across collectors. Multicore
workers, reserved headroom, unrelated Spot use, and spending reservations can
reduce actual concurrency. Derived phase deadlines use the configured host,
per-platform, and shared vCPU limits, plus one retry wave and a capacity
allowance; the shared limit is the sum of the allowed regions' `max_vcpus`.
Every worker type, including each unit pool's host, must fit the largest
`max_vcpus` among the allowed regions that hold its image. Without a shared
policy, deadline planning uses host counts; live quota shortages can require
a larger explicit deadline. An explicit
`--phase-timeout-seconds` is frozen on first execution and cannot change on
resume.

For a focused change, budget only the affected cases and protected controls.
Preserve accepted work and resume with unchanged frozen inputs instead
of starting collection over.

## Qualification inputs

Execution requires:

- a clean committed Regulator candidate
- a candidate ref under `refs/benchmarks/`
- the deterministic candidate archive and provenance produced by
  `baseline/freeze-candidate.sh`
- the pinned Trino checkout, which every unit worker receives
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

`baseline/generate-units.py` derives the rest from them and writes it beside
the baseline tooling:

- `baseline/row-protocol.tsv`: each row's frozen qualification protocol
- `baseline/units.tsv` and `baseline/unit-rows.tsv`: the work units each shard's
  rows are divided into, measured one at a time by `baseline/run-unit.sh`

These tables are not checked in. [Freezing a candidate](#freeze-a-candidate)
generates them and adds them to the candidate archive, and the controller
reads them from the baseline directory, where the frozen archive must
reproduce them.

Generate the tables and run the validators before allocating hosts:

```bash
python3 tools/re2-benchmark/baseline/generate-units.py
python3 tools/re2-benchmark/baseline/validate-manifest.py
python3 tools/re2-benchmark/baseline/validate-protocol-representatives.py
```

## Freeze a candidate

`freeze-candidate.sh` runs the full build, verifies the production engine tree,
generates the row protocols and work units, creates an immutable local
benchmark ref, and writes a reproducible archive, holding the candidate and
its generated tables, and a provenance record:

```bash
tools/re2-benchmark/baseline/freeze-candidate.sh \
  refs/benchmarks/<candidate-name> \
  /durable/path/candidate.tar.gz \
  /durable/path/candidate-provenance.tsv
```

The output files must not already exist. The script never moves an existing
benchmark ref.

## Plan and execute

Without `--execute`, the controller writes the phase's work items to
`work-items.tsv` without allocating AWS resources:

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py \
  smoke <campaign-id> \
  --result-root /durable/path/<campaign-id>/smoke
```

Build each platform's engines once before the smoke. The build phase leases
one `engine-build` unit per platform to a unit worker of its pinned instance
type and AMI; see [engine builds](#engine-builds):

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
  --release-version 2.0 --fleet-budget /durable/path/fleet-policy.json
```

The primary phase requires the accepted smoke root and measures three
independent replicas of every work unit of every platform and shard:

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
  --release-version 2.0 --fleet-budget /durable/path/fleet-policy.json
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
  --release-version 2.0 --fleet-budget /durable/path/fleet-policy.json
```

Start with one canary per platform, then run full smoke before primary.
Use `--smoke-protocol qualification` for baseline duration qualification. The
default `smoke` protocol shortens timings and cannot establish a unit's
duration. The selection is frozen on execution and cannot change on resume.

Smoke, primary, and confirmation measure the phase's work units on [unit
workers](#unit-workers). The plan writes `work-items.tsv`: replica 1 of every
unit of the selected shards in smoke, replicas 1 to 3 in primary, and replica 4
of each `--confirmation-jobs` row, which names `platform`, `shard_id`, and
`unit_id`. A primary requires an accepted smoke, and a confirmation requires an
accepted primary. A shared fleet budget must allow `max_attempt_seconds` of at
least the longest pool's worker timeout plus 15 minutes: 27,900 seconds for
baseline units, and 102,600 for a pool holding a Java or Trino slow-bulk
comparison's 75,600-second unit. Each phase is still its own controller run;
pipelining a shard's primary behind its accepted smoke is future work.

Set `AWS_PROFILE` before any execution command when selecting a named profile;
otherwise the AWS CLI uses its standard credential chain. `TRINO_DIR` is
required for every campaign, language campaigns included, because every unit
worker receives the pinned Trino checkout.

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
Hosts record the name. The controller refuses an allowed region with
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

The `build` phase builds the pinned Trino harness once per platform, exactly as
a baseline host would, and the native comparison runners from the candidate's
sources: native RE2 (`language_benchmark`) and the Rust `regex` crate
(`language_rust_benchmark`), and their bulk runners for language units, with
generic tuning, each beside its build receipt. It freezes them in
`trino.tar.gz` and `native.tar.gz` under a manifest, `engine-build.json`. The
manifest lists each archive's SHA-256 and per-file hashes, and its inputs: the
candidate, the Trino commit, and the build scripts. The build checks only the
harness's identity and provenance; the semantic verifiers run in every measuring
route, where a failure is final rather than retried.

The build phase's unit, `engine-build`, runs replica 1 on every platform and is
estimated at 900 seconds, so it fits a Spot lease. Its workers install no
prebuilt engines: they install the pinned Cargo and Rust packages and build
their own. The unit is retried and accepted as any work unit is, into the build
root's `accepted-units.tsv`. Its receipt's observed digest is the manifest's
SHA-256, and its protocol and manifest digests cover the build scripts, so the
phase is accepted only when every platform's build came from the checkout's
scripts and one candidate.

Smoke, primary, and confirmation execution require `--engine-builds`, naming
an accepted build root for every platform. One build root serves any measuring
campaign, baseline or language, whatever its campaign ID and heap: the root is
revalidated under the campaign ID in its own `campaign-state.tsv`, which must
name a build phase, its cleanup must be verified and it must have no semantic
rejection. Against the measuring checkout, its builds must be of the same
candidate and archive, made by the same build scripts from the pinned Trino
commit, on each platform's pinned architecture and instance type. Each
platform's manifest SHA-256 is frozen in `execution-policy.json`, and primary
and confirmation refuse a prerequisite that used different builds. A language
unit worker runs the native runners of its platform's engine build.

Hosts check each downloaded archive with `sha256sum --check`, then verify the
archive and each installed file against the manifest; a mismatch fails the
session. Every measuring unit worker installs both archives, while a build
phase's worker installs none and builds its own: the runners land in
`language-native/` under the shared work directory, and
`engine-build/<archive>.sha256` marks each installed archive.
`engine-build.txt` records the installed build beside the host evidence and in
each route.

Preflight names each allowed region that lacks the shared transfer bucket
`--engine-builds` requires. Before it launches any host,
the controller puts each archive once in every region that may launch a host
installing it, under `TRANSFER_PREFIX/engine-builds/<sha256>.tar.gz`, and skips
an object with that key and checksum. A language plan's bundle, which a
language unit worker installs, travels the same way under
`TRANSFER_PREFIX/language-plans/<sha256>.tar.gz`; the wrapper takes the bundles
of a session in `LANGUAGE_PLAN_BUNDLE_SHA256S`, with the candidate provenance
their units measure. An upload slower than 256 KiB/s counts as
stalled and is stopped and retried; the campaign stops after three failed
attempts. A session whose archive is missing from its bucket fails as
`retryable-infrastructure` before it launches. Each session's instance role
may read exactly those archive keys, in addition to its own session prefix.
These archives are campaign inputs rather than session resources, so session
cleanup leaves them in place. Remove them once the campaign is done:

```bash
aws s3 rm "s3://<bucket>/<transfer-prefix>/engine-builds/" --recursive --region <region>
aws s3 rm "s3://<bucket>/<transfer-prefix>/language-plans/" --recursive --region <region>
```

## Single-job wrapper

The controller uses `run-campaign.sh` to run one AWS job, a
`CAMPAIGN_MODE=unit-worker` job that leases [work units](#unit-workers). The
wrapper verifies the archive, provisions an isolated
instance, uploads inputs through a private temporary S3 location, downloads the
result, and verifies instance, IAM, and S3 cleanup.

The fleet controller should normally invoke this wrapper. Direct invocation is
appropriate only while diagnosing one qualification job and still requires all
pinned identity variables.

`run-host.sh` runs on the instance. It verifies the AMI, JDK, package,
candidate, comparator, and manifest identities, and runs `run-unit-worker.sh`.
Only a worker without prebuilt engines, which builds them, installs the pinned
Cargo and Rust packages, because the Rust comparator builds the pinned `regex`
crate.

## Unit workers

A `CAMPAIGN_MODE=unit-worker` job launches a host that measures the work units
its controller leases to it, one at a time, instead of one shard. It requires
`CAMPAIGN_SHARD_ID=unit-worker` and `TIMEOUT_SECONDS` of the worker lifetime,
`UNIT_WORKER_LIFETIME_SECONDS` (default 21600), plus 4500 seconds for host
setup and final uploads, plus the time its last unit may take: 900, or the
pool's longest estimated unit when that is longer. The host
bootstraps with packages, the JDK, the environment record, and, unless it is a
build phase's worker, the platform's prebuilt engines. A language unit worker
also installs each language plan bundle under `target/language-plans/<shard>`.
It then runs `run-unit-worker.sh`, which runs `../baseline/run-unit.sh` for each
lease. The wrapper's `session.txt` records the host session (`session`),
`bucket`, `input_prefix`, and `result_prefix`; every key below is under one of
those prefixes.

| Key | Writer | Holds |
|---|---|---|
| `<input_prefix>/leases/<seq>.json` | controller | the host's lease `seq`, numbered from 1 |
| `<result_prefix>/heartbeat/<seq>.json` | host | its state while it waits for, runs, or uploads lease `seq` |
| `<result_prefix>/units/<seq>/result.tar.gz` | host | the unit directory of lease `seq` |
| `<result_prefix>/units/<seq>/completion.json` | host | the attempt's completion marker, written last |
| `<result_prefix>/events/<seq>-interrupted.tar.gz` | host | an interruption record and the partial unit |
| the job's result key | host user data | the host evidence, once the worker exits |

Every upload of `run-unit-worker.sh` except a heartbeat is one PUT with its
SHA-256 checksum and `If-None-Match: *`, so no key is overwritten. Each request
gives up after 5 seconds connecting or 30 reading, and a failed upload is
retried every 5 seconds while the worker keeps watching for an interruption
notice. An attempt starts only if its worst case of 35 seconds still ends within
`UNIT_WORKER_UPLOAD_SECONDS` (90) of the upload's start, so the whole upload
stays within that budget. A unit result's attempts and budget grow by a second
per 4 MiB, such as an engine build's archives, and its budget to at least two
whole attempts; an interruption bundle uploaded meanwhile keeps its own
35-second attempts. Since a request whose connection stays alive can
outlive the CLI's timeouts, every S3 call of the worker, heartbeats and lease
reads too, runs under coreutils `timeout`, which ends it after 35 seconds, or
a unit result's stretched attempt time; a call within an upload's budget also
ends when that budget does. The interruption bundle and
the final heartbeat after it share one budget. The user data's upload of the
host evidence, once the worker exits, is a plain `aws s3 cp` outside these
rules. The host's role adds only `s3:ListBucket` restricted to
`<input_prefix>/leases/`, so the worker tells a lease not yet written from one
it cannot read.

A lease is a JSON object:

```json
{"schema_version": 1, "campaign_id": "primary-20261024", "phase": "primary",
 "platform": "r8i", "host_session": "20261024T120000Z-4242", "sequence": 3,
 "unit_id": "<64 hex digits>", "replica_id": 2, "protocol": "qualification"}
```

A measuring phase's `unit_id` is 64 hex digits; the build phase's one unit is
`engine-build`, at replica 1. A stop lease replaces `phase`, `unit_id`,
`replica_id`, and `protocol` with `"stop": true`. The host reads lease `seq`
only after lease `seq - 1`'s completion marker lands, so the controller may
write one lease ahead. The host requires schema version 1 and its own sequence,
host session, campaign, and platform, and a work lease's unit ID, replica 1 to
4, phase, and protocol; any other lease stops it. Each lease read is kept in the
host evidence under `leases/`.

A heartbeat is `{"schema_version": 1, "host_session", "instance_id",
"sequence", "state", "unit_id", "replica_id", "timestamp", "stop_reason"}`,
written every 30 seconds and at each state change once bootstrap ends. `state`
is `waiting`, `running`, `uploading`, `stopped`, or `interrupted`; the unit,
replica, and stop reason are `null` when they do not apply.

A completion marker is `{"schema_version": 1, "host_session", "sequence",
"campaign_id", "phase", "platform", "unit_id", "replica_id",
"unit_attempt_id", "exit_status", "unit_status", "result_sha256",
"started_at", "finished_at"}`. `unit_attempt_id` is `<host_session>/<seq>`,
`unit_status` is `complete` or `failed`, and `result_sha256` is the digest of
`result.tar.gz`. A failed unit, including one whose representative failed with
status 42, is still uploaded and marked, and the host takes its next lease. A
`result.tar.gz` without its completion marker is not a finished attempt.

The worker polls the instance metadata service (IMDSv2) every 5 seconds and
after each of its S3 calls, so a stalled S3 call delays a notice by at most that
one call, and a stop with a notice already arrived, or arriving during its final
heartbeat, is an interruption. A Spot instance-action notice stops leasing and
kills the running unit's process group. The host uploads
`events/<seq>-interrupted.tar.gz`, holding `interruption.json`
(`schema_version`, `host_session`, `sequence`, `unit_id`, `replica_id`,
`notice_time`, `action`, `action_time`) and the partial unit directory, then
exits 43; its user data uploads the host evidence if the instance lasts. A unit
whose marker had not landed never completes. The wrapper classifies status 43
as `retryable-spot`, and a host that vanishes without uploading is classified
as any other host. A rebalance recommendation is only a hint that EC2 may
reclaim the instance, so the worker goes on leasing until the notice comes.

The worker stops, with its reason in the final heartbeat and
`worker-session.txt`, on:

| Reason | When | Status |
|---|---|---|
| `stop-lease` | it reads a stop lease | 0 |
| `idle` | its next lease is absent for `UNIT_WORKER_IDLE_SECONDS` (600) | 0 |
| `lifetime` | its lifetime has passed; no lease starts after it, a running unit finishes | 0 |
| `interrupted` | a Spot instance-action notice | 43 |
| `invalid-lease`, `lease-unreadable`, `upload-failure` | a malformed lease, 12 failed lease reads in a row, or an upload still failing at its bound | 1 |

The host evidence holds the environment files, `engine-build.txt`,
`host-capacity.txt`, `leases/`, `worker-leases.tsv` (each lease's outcome,
status, attempt ID, and the digest of its environment files),
`worker-session.txt`, the shared semantic receipts, and the logs.

Each unit result is self-contained: its routes record the host's CPU steal
over their measurements, and [unit acceptance](../baseline/ACCEPTANCE.md)
needs nothing from the host evidence.

The first accepted attempt of a platform, unit, and replica wins. A later
completed attempt of the same unit replica, such as one leased again after a
lost heartbeat, is recorded as superseded, not accepted, and does not halt the
campaign.

### Unit dispatch

The controller keeps a queue of the phase's work items and a pool of workers
per platform and instance type: a unit runs on the instance type its shard is
allocated on that platform, which is the platform's frozen type for every
baseline shard. Each worker is a job of the controller's ordinary launch path,
so regions, placement scores, Spot launch retries in the next region,
`--max-concurrent`, the per-platform limit, and the shared budget apply to it,
and its reservation lasts until its cleanup is verified. `unit-workers.tsv`
freezes each worker before its launch. A pool launches a worker only while its
queued items outnumber its idle workers.

When several regions are allowed, a worker whose Spot launch found no capacity
in any zone of its region cools that region for its pool for 15 minutes, and
the pool's new Spot workers launch elsewhere meanwhile. While every allowed
region cools, the pool's new workers launch On-Demand, and Spot resumes in each
region once its cool-down ends; a `--spot-only` pool instead tries every region
again at once. A restarted controller forgets the cool-downs.

Once a worker's session exists, the controller writes its first lease and
keeps one lease written ahead while no idle worker of the pool could take the
item. It leases On-Demand work first, then the longest eligible item, never a
unit the host already leased, and never to an instance a confirmation excludes
or another replica of the unit used. An item requeued for On-Demand only goes
to an On-Demand host, and On-Demand hosts also take every other item. When
the pool can give a worker nothing more, the worker gets a stop lease. Each
lease, and each outcome it reaches, is a row of `unit-attempts.tsv`, written
before the lease is. A unit estimated past the 15-minute unit budget, such as a
slow language comparison isolated in its own unit, is On-Demand work from the
start unless the campaign is `--spot-only`.

The controller polls each worker's uploads. A completion marker's result must
match `result_sha256` and its lease; the attempt then passes the unit gate,
is retained, and is added to
`accepted-units.tsv`. Other outcomes:

- An interruption record, or a status-43 exit while a lease ran, records an
  `interruption` event and requeues the unit for On-Demand only. A record
  that names no unit, from a notice while the host waited for its next lease,
  releases that lease without spending an attempt.
- A heartbeat 30 minutes old, or 10 minutes old on an instance no longer
  running, marks the host lost: its started lease is requeued, an unstarted
  one is released, and its wrapper is stopped. Cleanup still recovers a
  completion that lands, which is accepted if it is first and superseded
  otherwise.
- A failed unit is requeued. A unit replica gets 3 attempts across failures,
  interruptions, and lost hosts, then stays unresolved.
- A worker that fails before its first lease passes its launch slot to a
  replacement. A worker whose uploads include a heartbeat, a completion or an
  interruption bundle has started, even if no heartbeat landed. The host
  evidence its user data uploads on exit, even after a failed bootstrap, does
  not show a start. A slot whose worker has failed 3 times gives up. A worker
  whose wrapper refused its inputs (`input-rejection`, under
  [recovery and evidence](#recovery-and-evidence)) gives up its whole pool,
  free slots included, since every launch would be refused the same way. Once
  the pool has no worker or slot left, its queued work stays unresolved. Each
  worker's `starting` record in `job-attempts.tsv` names its slot (`slot`, the
  host epoch of the slot's first worker) and the failures it inherited
  (`slot_failures`), so a restarted controller resumes every slot's count.
- A status-42 protocol rejection, a status-44 semantic failure, a rejected
  unit capacity, a result that
  does not match its marker, and a failed artifact gate leave the unit
  unresolved without a retry while other work continues.
- Status 44 means the route's correctness stage failed: a semantic test, the
  DFA probe, a differential verifier or its count of verified cases, or the
  pinned Trino comparator's verification, including a check that could not
  start. The controller records the attempt
  in `semantic-rejections.tsv` (`platform`, `unit_id`, `replica_id`,
  `unit_attempt_id`) before it acts on it. No other attempt of the unit clears
  it, whether it completed before or after; the unit is listed unresolved, and
  the phase prerequisite and the reducer refuse a phase whose ledger lists a
  rejection or is missing.
- Work queued after the phase deadline, including work a lost, failed, or
  interrupted lease hands back, stays unresolved.
- A marker that names another lease, unproven cleanup, and changed frozen
  inputs stop the campaign as unsafe.

When a pool's remaining estimated work falls to 10% of its total, its remaining
items become On-Demand work, leased first, and On-Demand workers launch for them
within the budget's On-Demand allowance. Each Spot worker of the pool gets a
stop lease only once an On-Demand worker has been admitted to replace it; until
then it keeps taking the pool's items. While the budget refuses an On-Demand
launch, the pool's Spot launches go on for the items Spot may run. A
`--spot-only` campaign never switches. Once every item is accepted or
unresolved, the controller waits for every worker's verified cleanup, lists the
unresolved items in `unresolved-units.tsv`, and otherwise validates the phase's
accepted units as one campaign. Only then does it write `phase-complete.txt` in
the result root, atomically; a later phase, including one consuming an engine
build root, refuses a root without it, such as one whose controller stopped
after its ledger was complete.

## Recovery and evidence

A phase restarts from its ledgers, and only with the tooling that
started it: `tooling-digest.txt` records a digest of the tracked benchmark
tooling when the phase starts, and a restart that finds a different digest, or
none, is refused. The controller adopts each worker whose wrapper is still
running, rewrites its outstanding leases, which must match what was written,
and settles each exited worker from its recovered uploads, so no accepted unit
runs again and no unit is leased to two live hosts. A unit rejected for a
semantic failure stays unresolved across restarts, so measuring it again
means running the whole phase again in a new result root.

Failed jobs record their failure type. A session that fails before its first
RunInstances request, in setup such as the default VPC lookup or the instance
profile, is `retryable-infrastructure`, since no instance can exist; cleanup
still removes and verifies the bucket and profile it created. A session whose
frozen inputs fail their checks before launch, such as a dirty checkout or a
candidate archive whose SHA-256 or engine tree differs, is instead
`input-rejection`, since a retry would fail them again; the worker's whole pool
gives up. [Unit dispatch](#unit-dispatch) retries a failed unit instead.
Unverifiable cleanup and other unsafe controller failures stop admissions.

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
A unit worker's recovered uploads are settled as
[unit dispatch](#unit-dispatch) describes.

This covers late uploads and results available when a controller aborts.
Measurements still on an interrupted or forcibly terminated host may never
reach S3; the recovery receipt states that limitation. After cleanup is proven, a benchmark or artifact
rejection leaves its unit unaccepted while unrelated units finish.
