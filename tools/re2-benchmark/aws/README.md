# AWS baseline qualification

The AWS tools run one kind of job, a `unit-worker`. Full qualification first
builds each platform's candidate classes, Trino harness, and native runners
once, as a build-phase work unit, then measures baseline and language work
units on unit workers. This guide gives the baseline commands; see the
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
in the campaign settings and account for other users' capacity reservations.

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

A launch that finds no capacity in its zone tries each other zone of its
region that offers the type before it fails. A unit whose worker is reclaimed
or fails goes back to its pool, as [unit dispatch](#unit-dispatch) describes.
Each platform launches in its own regions and buys hosts by its own purchase
policy; see [launch regions and purchase](#launch-regions-and-purchase).
Under the default `spot-then-on-demand` purchase, once a pool's remaining
estimated work falls to 10% of its total, its last items run on On-Demand
workers, so a phase's last units do not wait on Spot capacity. Budget these
hours at On-Demand prices.

Set a platform's `purchase` to `spot` in the
[campaign settings](#shared-fleet-budget) to prohibit its On-Demand workers.
Other users' running and pending Spot work still consumes quota. Keep one
owner for each mutable capacity limit. Transfer reservations only after
cleanup or a complete finite-inventory bound proves they are available. A
temporary gap between worker launches is not free reserved capacity.

## Shared fleet budget

Run the controller on one persistent filesystem with renewable AWS
credentials, and use `--release-version 2.0` when measuring the 2.0 release.
The option names the release manifest
`tools/re2-benchmark/baseline/releases/<version>.json`, and the named
candidate's committed `src/main` tree must match that release. The
[maintenance guide](../../../docs/MAINTAINING_REGULATOR.md#final-qualification)
says how to record a release's manifest. A candidate-build campaign, run
before the release, omits `--release-version` from collection and reduction;
its publication still names the release as `release_version` in its paths file
and publishes preliminary results. Remeasure the 1.0 artifact with
`--release-version 1.0` from
[commit `32406d25`](https://github.com/airlift/regulator/tree/32406d25a6032b15d366cddb414ade57c972d0ee);
the published campaign's collector revisions are in its capture provenance.

A campaign's limits are its live settings, in `campaign-settings.json` at the
campaign root, which the controller reloads every loop, so a changed limit
applies to the next launch, lease extension, and share. The campaign's first
execution seeds them from the JSON file `--settings FILE` names; a restart runs
under the settings the root holds. For example, settings for at most 16
ordinary workers in one region could be:

```json
{
  "max_hosts": 16,
  "max_hosts_per_platform": 16,
  "max_usd": 20,
  "max_machine_hours": 100,
  "regions": {"us-west-2": {"bucket": "<bucket>", "max_vcpus": 32}},
  "platform_regions": {"r8i": ["us-west-2"], "r9g": ["us-west-2"]},
  "purchase": {"r8i": "spot", "r9g": "spot"},
  "hourly_rates": {
    "r8i.large": {"spot": 0.08, "total": 0.10},
    "r9g.large": {"spot": 0.08, "total": 0.10}
  }
}
```

- `max_hosts` and `max_hosts_per_platform` bound the running phases' hosts in
  all and on each platform.
- `max_usd` and `max_machine_hours` bound the campaign's spending and machine
  hours.
- `regions` gives each launch region's shared transfer bucket and the fleet's
  `max_vcpus` there, and `platform_regions` lists each platform's launch
  regions among them; see [launch regions](#launch-regions-and-purchase).
- `purchase` names how each platform buys hosts.
- `hourly_rates` gives each worker type's price ceilings: `spot` is the
  maximum Spot bid, `total` includes storage and other hourly costs, and
  `on_demand_total` is that hourly total for an On-Demand host. They apply in
  every region, so set them for the most expensive launch region, and include
  the R9g `.2xlarge` ceilings when running the language lifecycle suite.

Seeding adds `version` 1, the `boot_window` described below, the `authorized`
envelope of hosts, spending, and regions, which defaults to the given limits,
an empty `phases`, where the controller records each phase's state, and no
`spending_stop` or `stop`. The
controller refuses settings it could not run under when it seeds them; on a
reload it keeps the last version it loaded and logs why. Every change takes
`campaign-settings.lock` while it rewrites the file, takes the next version,
and appends a row naming its actor, `dain`, `monitor`, or `controller`, to
`campaign-settings-events.tsv`. The controller itself changes only the phases'
states, the boot window's size, and the spending stop.

What decides the measurements stays frozen in `campaign-options.json`: the AWS
account of the settings' buckets, `--spot-vcpu-reserve`, the vCPUs of each
region's Standard Spot quota the fleet leaves free (default 32), and
`--phase-timeout-seconds`, beside the options [a campaign](#run-a-campaign)
freezes. A restart that changes them is refused.

Admission counts each region's quota, Spot reserve, and `max_vcpus` against
that region's instances, requests, and reservations in the same market. Each
reservation records its region and market. Hosts, machine hours, and spending
remain fleet-wide. Preflight refuses a region whose `max_vcpus` exceeds its
Spot quota after the reserve. The worker types remain those in
`platforms.tsv`.

Settings without `on_demand_max_vcpus` refuse On-Demand, so their platforms buy
with `purchase` `spot`. To allow On-Demand replacements and phase tails, set
`on_demand_max_vcpus` in a region's entry, and give every worker type an
`on_demand_total` hourly cost:

```json
"regions": {"us-west-2": {"bucket": "<bucket>", "max_vcpus": 32, "on_demand_max_vcpus": 16}}
```

The allowance bounds only the fleet's own On-Demand vCPUs in that region.
Other workloads' On-Demand use and a 32-vCPU safety reserve come out of the
region's On-Demand quota, which admission checks the same way as Spot quota.
On-Demand hosts count against the same host, machine-hour, and spending
ceilings, at their `on_demand_total` rate. Regions without an allowance launch
only Spot, and an On-Demand launch takes the first region of its platform's
list that admits it. Each platform whose purchase is not `spot` needs an
allowance in a region of its list.

`fleet-state.json` at the campaign root holds the campaign's reservations,
changed under `fleet-budget.lock`. It counts instances, outstanding requests,
and launches not yet visible to EC2. Each host's reservation holds its hours
and cost only until a stop time that covers the work it was issued, so a fleet
admits as many hosts as the work they hold, not their whole lifetimes, can pay
for:

- A launch reserves the host's boot allowance of 20 minutes, a first lease of
  its pool's longest queued item, the worker's 10-minute idle stop, a 15-minute
  wind-down for its final uploads, and 15 minutes of teardown until verified
  cleanup.
- The controller budgets a lease at twice its groups' estimate, or twice the
  15-minute unit budget when that is longer. Before issuing one, it extends
  the reservation to cover each lease the host would hold at that allowance,
  run one after another from now, or from the end of its boot allowance, then
  its idle stop, wind-down, and teardown. A lease the budget refuses is not
  issued; a worker holding no other lease gets a stop lease instead.
- Admission and every extension must keep the completed hosts' actual hours,
  plus each reservation's hours to its stop time, or to now once a host ran
  longer, within `max_machine_hours`, and their cost within `max_usd`.

At $0.10 an `r8i.large` hour, a launch's 90 minutes reserve $0.15, and each
lease of a 15-minute unit is budgeted at $0.05 more. These are estimates: a
lease that outruns its allowance leaves the next extension, counted from now,
to cover the extra time. The earlier reservation of a worker's whole
7.75-hour wrapper lifetime held $0.78, so $10 admits 66 such launches where it
admitted 12.

The host enforces the same stop time: it powers off at the time its launch or
its latest lease names, whatever it is doing, as [unit workers](#unit-workers)
describes. So a host whose controller died stops within its reservation.
Reservations remain until verified cleanup, which charges the host's actual
hours; crashes never expire them, and one past its stop time counts as spent
to now until then. A
rejected work unit follows the [unit dispatch](#unit-dispatch) retry rules.
A fault while the controller recovers or dispatches a phase's hosts, through
changed frozen inputs, a completion marker unlike its lease, conflicting
accepted identities, a ledger error, or any other error, pauses that phase
alone, as [steering a campaign](#steer-a-campaign) describes, and the other
phases go on. A resource AWS lists that the fleet cannot account for, like a
leak, sets a [spending stop](#recovering-uploads-during-cleanup). SIGTERM stops
only the controller: it logs one line and exits with status 0, and its hosts
keep their leases and reservations for the next controller, which adopts them.
See the
[protocol rules](../baseline/SHARD_DISPATCH.md#jmh-protocol). Preserve the
campaign root, its settings and fleet state together, across restarts.

The boot window also paces launches: it caps the campaign's hosts launched but
not yet reporting, so a bad image, region, or user-data change costs at most
one window of hosts before it shows. The window starts at the settings'
`boot_window` `initial`, 10 unless the seed names another. Its wave is the
hosts admitted since it last changed; once as many of them as it holds report
within 5 minutes of launch, it doubles, up to its `max`, 50 by default, and
once any is late, or ends before it reported for a reason other than a Spot
reclaim, it halves, down to 5. Each resize is the controller's settings change
of its `size`, so the events record the window's history. A launch it refuses
waits with reason `boot window`. Each loop, a phase's pools in turn first take
up to an even part of the room the window has left, and the pools that need
more then share the rest.

Multicore workers, the Spot reserve, unrelated Spot use, and spending
reservations can keep the fleet below `max_hosts`. A phase's derived deadline
uses the settings' host, per-platform, and vCPU limits when it first starts,
plus one retry wave and a capacity allowance; the vCPU limit is the sum of the
launch regions' `max_vcpus`. Every worker type, including each unit pool's
host, must fit the largest `max_vcpus` among the regions of its platform's
list. An explicit `--phase-timeout-seconds` is frozen on first execution and
cannot change on resume.

For a focused change, budget only the affected cases and protected controls.
Preserve accepted work and resume with unchanged frozen inputs instead
of starting collection over.

## Account setup

A few resources exist before any campaign and are never created or deleted
per host or per campaign. Create them once in the account, with the `dev`
profile and from the repository root; the controller and the control host only
check them. A create step fails on a resource that already exists, with
`EntityAlreadyExists`, `LimitExceeded` for an instance profile's second role,
`InvalidGroup.Duplicate`, `InvalidPermission.Duplicate`, or
`BucketAlreadyOwnedByYou`, and changes nothing, so the sequence is safe to
re-run; the policy, parameter, and lifecycle steps overwrite, so re-run them
whenever their file changes.

The [worker profile](#worker-profile), which every worker runs under:

```bash
aws iam create-role --profile dev --role-name regulator-benchmark-worker \
  --assume-role-policy-document '{"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
    "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole"}]}'
aws iam put-role-policy --profile dev --role-name regulator-benchmark-worker \
  --policy-name regulator-benchmark-worker \
  --policy-document file://tools/re2-benchmark/aws/worker-policy.json
aws iam create-instance-profile --profile dev --instance-profile-name regulator-benchmark-worker
aws iam add-role-to-instance-profile --profile dev --instance-profile-name regulator-benchmark-worker \
  --role-name regulator-benchmark-worker
```

The [control host](#control-host) profile, which may launch, describe, and end
workers, pass the worker role, read and write the campaign buckets and create
one for `add-region`, read the network parameter, and keep the campaign's
Grafana credentials; `control-host/control-host-policy.json` is its policy,
and Session Manager's managed policy gives the shell fallback:

```bash
aws iam create-role --profile dev --role-name regulator-benchmark-control-host \
  --assume-role-policy-document '{"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
    "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole"}]}'
aws iam put-role-policy --profile dev --role-name regulator-benchmark-control-host \
  --policy-name regulator-benchmark-control-host \
  --policy-document file://tools/re2-benchmark/aws/control-host/control-host-policy.json
aws iam attach-role-policy --profile dev --role-name regulator-benchmark-control-host \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws iam create-instance-profile --profile dev --instance-profile-name regulator-benchmark-control-host
aws iam add-role-to-instance-profile --profile dev --instance-profile-name regulator-benchmark-control-host \
  --role-name regulator-benchmark-control-host
```

The control host's network, which this public repository never names, is one
JSON SSM parameter, `/regulator-benchmark/control-host-network` in
`us-west-2`. The company's Tailscale subnet router forwards only tcp 443 into
the VPC, so Grafana is served by an internal Network Load Balancer with a TLS
listener on 443, and the host's sshd listens on 443 as well. Write the
parameter to a file outside the repository, `<network.json>`, starting with
the keys that name the existing network:

```json
{"vpc": "vpc-<id>", "subnets": ["subnet-<id>", "subnet-<id>"],
 "ingress_cidr": "<cidr>", "zone": "<hosted-zone-id>", "record": "<name>"}
```

- `vpc`: the VPC whose private subnets the company's Tailscale subnet router
  carries onto the tailnet.
- `subnets`: only the private subnets of that VPC the router carries; their
  outbound traffic goes through the VPC's NAT gateway. A host in a subnet the
  router does not carry is unreachable.
- `ingress_cidr`: the VPC's CIDR, where tailnet traffic arrives from the
  router.
- `zone`: the ID of the private Route 53 hosted zone associated with the VPC.
- `record`: the fully qualified name in that zone that names the load
  balancer, covered by the account's wildcard certificate, so Grafana keeps
  the URL `https://<record>` across host replacements.

The steps below create the rest, each shared by every campaign's control host,
and add their IDs to the file. On a re-run, a `create-*` command fails for a
resource that exists; describe it instead. The control host's security group,
`regulator-benchmark-control-host`, and the load balancer's,
`regulator-benchmark-control-nlb`, both in that VPC:

```bash
field() { python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' <network.json> "$1"; }
host_group=$(aws ec2 create-security-group --profile dev --region us-west-2 \
  --group-name regulator-benchmark-control-host --vpc-id "$(field vpc)" \
  --description "regulator-benchmark control host: ssh and Grafana from the subnet router" \
  --tag-specifications 'ResourceType=security-group,Tags=[{Key=Project,Value=regulator-benchmark}]' \
  --query GroupId --output text)
balancer_group=$(aws ec2 create-security-group --profile dev --region us-west-2 \
  --group-name regulator-benchmark-control-nlb --vpc-id "$(field vpc)" \
  --description "Regulator campaign Grafana load balancer: TLS from the VPC" \
  --tag-specifications 'ResourceType=security-group,Tags=[{Key=Project,Value=regulator-benchmark}]' \
  --query GroupId --output text)
```

The load balancer's group admits tcp 443 from `ingress_cidr` and sends only
tcp 3000 to the host's group. The host's group admits only tcp 443, ssh, from
`ingress_cidr` and tcp 3000, Grafana, from the load balancer's group, and
launch refuses a group that admits anything else:

```bash
aws ec2 authorize-security-group-ingress --profile dev --region us-west-2 --group-id "$balancer_group" \
  --protocol tcp --port 443 --cidr "$(field ingress_cidr)"
aws ec2 revoke-security-group-egress --profile dev --region us-west-2 --group-id "$balancer_group" \
  --protocol all --cidr 0.0.0.0/0
aws ec2 authorize-security-group-egress --profile dev --region us-west-2 --group-id "$balancer_group" \
  --ip-permissions "IpProtocol=tcp,FromPort=3000,ToPort=3000,UserIdGroupPairs=[{GroupId=$host_group}]"
aws ec2 authorize-security-group-ingress --profile dev --region us-west-2 --group-id "$host_group" \
  --protocol tcp --port 443 --cidr "$(field ingress_cidr)"
aws ec2 authorize-security-group-ingress --profile dev --region us-west-2 --group-id "$host_group" \
  --protocol tcp --port 3000 --source-group "$balancer_group"
```

The internal load balancer, `regulator-control`, in the router's subnets with
cross-zone balancing on, so it reaches a host in either subnet's zone; its
target group, `regulator-control-grafana`, forwarding tcp 3000 to instances
and checking Grafana's health over HTTP; and its TLS listener on 443 with the
account's wildcard certificate for `<record>`:

```bash
balancer=$(aws elbv2 create-load-balancer --profile dev --region us-west-2 --name regulator-control \
  --type network --scheme internal --subnets <subnet-id> <subnet-id> --security-groups "$balancer_group" \
  --tags Key=Project,Value=regulator-benchmark --query 'LoadBalancers[0].LoadBalancerArn' --output text)
aws elbv2 modify-load-balancer-attributes --profile dev --region us-west-2 --load-balancer-arn "$balancer" \
  --attributes Key=load_balancing.cross_zone.enabled,Value=true
target_group=$(aws elbv2 create-target-group --profile dev --region us-west-2 \
  --name regulator-control-grafana --protocol TCP --port 3000 --vpc-id "$(field vpc)" --target-type instance \
  --health-check-protocol HTTP --health-check-path /api/health --health-check-interval-seconds 10 \
  --healthy-threshold-count 2 --unhealthy-threshold-count 2 --matcher HttpCode=200 \
  --tags Key=Project,Value=regulator-benchmark --query 'TargetGroups[0].TargetGroupArn' --output text)
certificate=$(aws acm list-certificates --profile dev --region us-west-2 \
  --query "CertificateSummaryList[?DomainName=='<wildcard-domain>'].CertificateArn | [0]" --output text)
listener=$(aws elbv2 create-listener --profile dev --region us-west-2 --load-balancer-arn "$balancer" \
  --protocol TLS --port 443 --certificates CertificateArn="$certificate" \
  --ssl-policy ELBSecurityPolicy-TLS13-1-2-2021-06 \
  --default-actions Type=forward,TargetGroupArn="$target_group" \
  --query 'Listeners[0].ListenerArn' --output text)
```

The record is a permanent alias of the load balancer. The launch and
`control-host replace` register each new host in the target group and
deregister the hosts they replace; `teardown` deregisters the campaign's
hosts and leaves the record, the load balancer, and the target group:

```bash
aws elbv2 describe-load-balancers --profile dev --region us-west-2 --load-balancer-arns "$balancer" \
  --query 'LoadBalancers[0].[DNSName, CanonicalHostedZoneId]' --output text
aws route53 change-resource-record-sets --profile dev --hosted-zone-id "$(field zone)" --change-batch \
  '{"Changes": [{"Action": "UPSERT", "ResourceRecordSet": {"Name": "<record>", "Type": "A", "AliasTarget": {
    "DNSName": "<load-balancer-dns-name>", "HostedZoneId": "<load-balancer-hosted-zone-id>",
    "EvaluateTargetHealth": false}}}]}'
```

Add the IDs to `<network.json>`, which then holds every key launch reads, and
put it in the parameter:

```json
{"vpc": "vpc-<id>", "subnets": ["subnet-<id>", "subnet-<id>"],
 "ingress_cidr": "<cidr>", "zone": "<hosted-zone-id>", "record": "<name>",
 "security_group": "<host-group-id>", "load_balancer_security_group": "<balancer-group-id>",
 "target_group": "<target-group-arn>", "load_balancer": "<load-balancer-arn>",
 "listener": "<listener-arn>", "certificate": "<certificate-arn>"}
```

```bash
aws ssm put-parameter --profile dev --region us-west-2 --overwrite --type String \
  --name /regulator-benchmark/control-host-network --value file://<network.json>
```

A shared bucket in each launch region, named
`regulator-benchmark-938463256137-<region>`, is private, encrypted, tagged,
and carries the lifecycle rules of the
[bucket layout](#launch-regions-and-purchase); `add-region` creates one the
same way. Omit `--create-bucket-configuration` in `us-east-1`:

```bash
aws s3api create-bucket --profile dev --bucket <bucket> --region <region> \
  --create-bucket-configuration LocationConstraint=<region>
aws s3api put-public-access-block --profile dev --bucket <bucket> --region <region> \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --profile dev --bucket <bucket> --region <region> \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"},"BucketKeyEnabled":true}]}'
aws s3api put-bucket-tagging --profile dev --bucket <bucket> --region <region> \
  --tagging 'TagSet=[{Key=Project,Value=regulator-benchmark}]'
aws s3api put-bucket-lifecycle-configuration --profile dev --bucket <bucket> --region <region> \
  --lifecycle-configuration file://tools/re2-benchmark/aws/bucket-lifecycle.json
```

Put the lifecycle configuration on every existing campaign bucket as well,
which replaces whatever rules it had; this lists them with their regions:

```bash
aws s3api list-buckets --profile dev --prefix regulator-benchmark-938463256137- \
  --query 'Buckets[].[Name,BucketRegion]' --output text
```

Objects outside `shared/` and `campaigns/` never expire, so remove what an
older layout left in a bucket once:

```bash
aws s3 rm --profile dev "s3://<bucket>/" --region <region> --recursive \
  --exclude 'shared/*' --exclude 'campaigns/*'
```

## Qualification inputs

Execution requires:

- each candidate as a commit of the controller's checkout, whose
  `tools/re2-benchmark` tree is the checkout's committed one; the rest of the
  checkout, such as uncommitted changes under `src/`, does not matter
- `zstd` and `curl` on the controller, which compresses language plan bundles
  and stages each platform's pinned JDK
- working AWS credentials from the standard AWS credential chain, or an
  explicitly selected profile, and the configured region
- the [account setup](#account-setup): the worker and control host profiles,
  the control host's network and security group, and a shared bucket in each
  launch region

Keep the campaign root outside Maven's `target` directory. The controller
rejects volatile paths and revalidates the candidate, manifests, and accepted
receipts whenever it resumes.

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
generates them with the candidate's own generator and adds them to its
archive, and the controller reads its own copy from the baseline directory,
which must match.

Generate the tables and run the validators before allocating hosts:

```bash
python3 tools/re2-benchmark/baseline/generate-units.py
python3 tools/re2-benchmark/baseline/validate-manifest.py
python3 tools/re2-benchmark/baseline/validate-protocol-representatives.py
```

## Freeze a candidate

Each phase names its candidate with `--candidate NAME=COMMITISH` and keeps its
results under a campaign root, by default
`benchmark-results/pre-review-baseline/<campaign>`:

- `candidates.tsv`: each candidate's name, commit, ref, and archive SHA-256
- `<name>/candidate/`: the candidate's `regulator.tar.gz` and `provenance.tsv`
- `<name>/build`, `<name>/smoke`, `<name>/primary`, `<name>/reduction`, and
  `<name>/confirmation`: its phases, run as campaign `<campaign>.<name>`
- `<name>/lang/smoke` and `<name>/lang/primary`: its language plan's phases,
  run as campaign `<campaign>.<name>.lang`

A candidate's campaign ID, `<campaign>.<name>` or `<campaign>.<name>.lang`,
keys its budget reservations, host tags, and host epochs. It is 1-63 letters,
digits, `.`, `_`, and `-`, starting with a letter or digit, and a campaign
refuses candidates whose campaign IDs would collide, such as `a`'s language
phases and `a.lang`'s baseline phases.

The first execution freezes the candidate from Git, never from the working
tree. It creates the immutable ref `refs/benchmarks/<campaign>/<name>`,
generates the row protocols and work units with the candidate's own
generator, and writes the deterministic archive of the candidate commit with
those tables, and its provenance. Every later phase and restart freezes the
candidate again, accepting the existing ref only at the same commit and
refusing an archive, provenance, or `candidates.tsv` row that differs.

Hosts run the candidate archive's copy of the benchmark tooling while the
controller checks their results with its own, so the candidate's
`tools/re2-benchmark` tree must be the controller's committed one. Freezing
does not build or test the candidate: the build phase compiles it, every route
runs its semantic verifiers, and CI runs the full suite.

## Run a campaign

`campaign` freezes every candidate and runs each through its phases in one
controller process: build, smoke, primary, the reduction of its primary, and a
confirmation of the jobs that reduction lists, if any. Each candidate starts a
phase as soon as its own previous phase completes, and a failed phase stops
only its own candidate's later phases. A fault while the controller recovers
or dispatches a phase's hosts pauses only that phase, as
[steering a campaign](#steer-a-campaign) describes. With `--language-plan`,
each candidate also runs the plan's smoke and primary on its build; the
command refuses plans its language phases would refuse, such as two plans of
one shard, before it freezes anything.

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py \
  campaign <campaign> --execute \
  --candidate before=main --candidate after=user/me/faster-scan \
  --settings <campaign>-settings.json --spot-vcpu-reserve 16 \
  --ssh-public-key ~/.ssh/id_ed25519.pub
```

Run from the Mac with Dain's credentials, the command checks the candidates,
the settings, and the account as the controller would, and then launches the
campaign's [control host](#control-host), which runs the controller. The host
fetches every candidate from GitHub, so push each one first.
Every candidate's `tools/re2-benchmark` tree must be the controller's committed
one, so `before=main` works only while `main` carries the same benchmark
tooling as the checkout running the controller.

The first execution seeds the campaign's [settings](#shared-fleet-budget)
from `--settings`, and every candidate's phases share them. The running phases
share the settings' `max_hosts` and `max_hosts_per_platform`, and their shares
follow the work left, set again every poll. The hosts are
split across platforms by the corrected seconds left in each platform's work
items not yet accepted, as `status` computes them below, and a platform's
hosts among its phases the same way. No platform takes more than its limit,
and no phase more hosts on a platform than its work items left there, since a
host runs one at a time. Every platform, and every phase on it, with work left first takes one host while
hosts last, so a build, whose items carry no estimate, runs beside the
others. A phase holding more hosts than its share keeps them, and the others
grow only into hosts the limits leave free, so a language primary, most of a
release campaign's work, takes most of the hosts as they free up beside the
baseline's primary. Once the limits leave no host free for a phase below its
target, a phase above its own gives up the hosts past it: it stop-leases its
idle workers first, then those holding the least work, each behind the leases
it holds, so no lease is interrupted. Hosts move only while a phase waits for
them, so the shares' changes as the work left is corrected move none on their
own. A phase's share is never frozen, and the controller records each phase's
state, `pending`, `running`, `paused` with its fault, `complete`, or `failed`,
in the settings' `phases`.

### Control host

A campaign's controller runs on its own control host in `us-west-2`, an
`m7i.large` with no public address in a private subnet that the company's
Tailscale subnet router carries, as the
[network parameter](#account-setup) names. The campaign root lives on the
campaign's own 200 GiB gp3 volume, mounted at `/regulator`, so a replaced host
carries on with it. The launch uploads its inputs to the Oregon bucket's
`campaigns/<campaign>/control-host/`, creates the volume, starts the host, and
registers it in the parameter's target group, which the internal load
balancer forwards Grafana to. The host's user data,
`control-host/user-data.sh`:

- installs the key `--ssh-public-key` names for `ec2-user`, and has sshd
  listen on port 443 beside 22, since the subnet router forwards only 443;
- mounts the volume, formatting it the first time, and puts the campaign root
  at `/regulator/campaign`;
- unpacks the launch inputs onto the volume the first time: the controller's
  command, the candidate commits, the settings, and the language plans;
- fetches the candidate commits from GitHub and checks out the first, whose
  benchmark tooling every candidate shares;
- starts `regulator-campaign@<campaign>`, which runs the launch's command with
  `--controller` and the host's paths; the unit restarts a controller that
  crashes, up to five times in ten minutes, and leaves one whose campaign
  ended, with failed phases or without;
- starts `grafana/otel-lgtm`, pinned by digest, with Grafana on port 3000 for
  the load balancer and its links built from `https://<record>`, OTLP on the
  host's loopback only, its data on the volume, and its dashboards provisioned
  from `control-host/grafana/`;
- keeps Grafana's admin password and a Viewer service-account token for the
  monitor in the SSM SecureStrings
  `/regulator-benchmark/campaigns/<campaign>/grafana-admin-password` and
  `.../grafana-monitor-token`, generating them the first time; anonymous
  access is off.

The controller is the campaign's only telemetry exporter, and its unit is what
turns it on: it sets `OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318`, so
the controller sends OTLP/HTTP to the collector beside it, and anywhere the
variable is unset, as on the Mac, nothing is exported. Workers export nothing;
the controller turns their S3 evidence into telemetry. Every loop sets its
gauges, which `baseline/telemetry.py` lists: the progress model (each worker's
group with its estimated and elapsed seconds, work left, correction, and
finish by pool), workers by state, queued and accepted work, slack, launch
refusals by reason, the boot window, spend, phase states, and the loop's
seconds. It counts idle stops, Spot interruptions, and stalled hosts; logs its
events (starts, completions, pauses, failures, settings changes with their
actor, leaks, leftovers, and milestones) with the local Pacific time in each
line; and traces each unit attempt as it settles, with its leased, started,
uploaded, and accepted steps. Export runs on the SDK's own threads, so a
collector that is down drops what it misses and the loop goes on; the
controller's journal still has every event's line.

Grafana's Regulator folder holds three dashboards, provisioned from
`control-host/grafana/`: Regulator fleet (phase states, units accepted, finish,
workers, queued and corrected work left, each worker's group, hosts not yet
reporting, events, and unit attempts), Regulator health (one panel per health
check, red at its threshold: loop seconds over 30, a worker at zero slack, an
idle stop with work queued, the boot window shrinking, a launch refusal over
10 minutes, a finish past the deadline, projected spend at `max_usd`, a
spending stop, a paused phase, and a leftover), and Regulator spend. There is
no Grafana alerting; the monitor, a thread on the Mac that
[`control-host/MONITOR.md`](control-host/MONITOR.md) instructs, reads the
panels by their titles every 15 minutes and steers through
`control --actor monitor`.

Grafana is then `https://<record>` across replacements, through the subnet
router and the load balancer. ssh reaches the host on port 443 at its private
address, which the launch prints and a replacement changes; Session Manager is
the shell when the router is down. ssh logs in with the private key of the launch's
`--ssh-public-key`; name it with `-i` and `-o IdentitiesOnly=yes` when it is
not ssh's default, since an agent holding many keys can spend the host's
login attempts first. `status` and `control` run on the host, against
`/regulator/campaign`:

```bash
ssh -p 443 -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes ec2-user@<address> /opt/regulator/venv/bin/python \
  /regulator/control-host/checkout/tools/re2-benchmark/baseline/run-campaign.py status /regulator/campaign
```

The host outlives its campaign. To replace it, for instance once it fails,
terminate it and launch another onto the campaign's volume, in the volume's
zone, which unpacks nothing again:

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py \
  control-host replace <campaign> --ssh-public-key ~/.ssh/id_ed25519.pub
```

A controller that ends, whether every phase completed, some failed, or the
campaign was stopped, lists every file of the campaign root with its SHA-256
in `campaign-files.sha256`; one that SIGTERM stopped lists nothing. `finish`
pulls the root of an ended campaign over ssh on port 443 with rsync into the results
directory the reduction and archiving read, by default
`benchmark-results/pre-review-baseline/<campaign>`, and checks every file
against that list. It replaces a local root only where an earlier `finish`
pulled it, and terminates nothing. `--ssh-identity` names the private key ssh
offers alone, as `-i` does above:

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py finish <campaign> --ssh-identity ~/.ssh/id_ed25519
```

Only Dain tears a control host down, once the campaign's root is pulled and
nothing more is wanted from its Grafana. `teardown` deregisters the host from
the load balancer and terminates it, snapshots the volume, which is then the
root's last copy until Dain deletes it, and deletes the volume and the Grafana
parameters; the record, the load balancer, and its target group stay. It
refuses without `--confirm`:

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py teardown <campaign> --confirm
```

### Steer a campaign

`control` changes a running campaign's settings, which its controller applies
on its next loop. `--actor dain` or `--actor monitor` is required, and the
settings' events record it:

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py control /durable/path/<campaign> --actor dain \
  set-limit max-hosts 300
```

- `add-region <region> --max-vcpus N [--on-demand-max-vcpus N]
  [--platforms <platform>...]` creates the region's shared bucket,
  `regulator-benchmark-<account>-<region>`, unless it has one, as
  [the setup](#launch-regions-and-purchase) below does, puts every archive the
  campaign has shared, which `shared-archives.tsv` in the campaign root
  records, in it, each verified by its SHA-256, and then adds the region,
  listed for the platforms named, or for every platform. The running phases
  launch there once their [check](#launch-regions-and-purchase) passes.
- `set-limit max-hosts|max-hosts-per-platform|max-usd|max-machine-hours N`
  and `set-limit region <region> max-vcpus N` change a limit.
- `set-purchase <platform> spot|spot-then-on-demand|on-demand` changes how a
  platform buys hosts.
- `set-share <phase> N|auto` pins a phase, named by its key in the settings'
  `phases` such as `after/lang/primary`, to N hosts, which it takes before the
  phases sharing by work left; above its pin it gives up hosts as above
  whether or not another phase waits. `auto` returns it to its share by work
  left.
- `pause <phase> [--reason TEXT]` leases and launches nothing for a running
  or draining phase: its running leases, and the leases its workers already
  hold, finish, and its workers stop at their idle stop. `drain <phase>`
  launches nothing for it, while its live workers take its queued work until
  they stop. `resume <phase>` runs a paused or draining phase again. A restart
  keeps a phase paused or draining.
- `clear-spending-stop` admits launches and lease extensions again after a
  [spending stop](#recovering-uploads-during-cleanup).
- `stop [--reason TEXT]` stop-leases every worker, each behind the leases it
  holds, waits for every worker's cleanup to be proven, and ends the
  controller with status 0; the phases keep their queued work. A restart of a
  stopped campaign only stops the hosts an earlier controller left.
- `authorize max-hosts N`, `authorize max-usd N`, and
  `authorize regions <region>...` set the `authorized` envelope, which only
  `--actor dain` changes. The monitor raises `max_hosts`,
  `max_hosts_per_platform`, and `max_usd` only within it, and a region's vCPUs
  only in its regions, where admission still holds the fleet within the
  region's quota after its reserve; it never raises `max_machine_hours`, which
  the envelope does not name. Any actor may lower a limit.

The controller pauses a phase itself for a fault in its dispatch, logging
`<phase>: paused: <fault>` and recording the fault as the phase's `reason`.
It trusts nothing more the phase's hosts report and only proves each one's
cleanup as it stops at its idle stop. `resume` restarts the phase's dispatch
from its ledgers, as a restarted controller would, and the phase pauses again
while its fault stands. The controller keeps running while a phase is paused
for a fault; `stop` ends the campaign.

Each phase's setup, which plans its work items, checks its prerequisites and
AWS, and uploads what its hosts read, runs on a setup thread, and a
reduction runs on its own thread, so the controller's poll never waits for
them. The phases share the controller's request and acceptance threads and
what it has learned of each region's networks, images, offerings, and
placement scores.

The controller takes the campaign root's `controller.lock` before it freezes
anything and records its process ID and start time in `controller.txt` there;
a second controller of the root stops at once. Run the command under
`nohup` or a terminal multiplexer to keep it running after the shell ends.
Run the same command again to resume: it skips every completed phase, whose
root holds `phase-complete.txt`, and every completed reduction, whose root
holds `output-checksums.tsv`, and resumes the others from their ledgers. The
controller records the options that decide what its phases measure, the
selected shards, smoke protocol, release version, and language plans, in
`campaign-options.json` with the [frozen fleet options](#shared-fleet-budget),
and refuses a restart that changes them.

The command refuses a campaign root the earlier controllers started, which ran
each phase as its own process: one holding `campaign-driver.txt`, a phase
holding `driver-child.txt`, or a phase's `execution-policy.json` that froze its
share of the hosts or named a fleet policy file. It names the file it found,
and the root is never migrated; start a new campaign root. `status`,
`--results`, the reduction, and archiving still read such a root's ledgers.

See a campaign's progress, or one phase's, without disturbing it:

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py status /durable/path/<campaign>
```

```text
campaign /durable/path/<campaign>: controller running as process 4242
Grafana   https://<record>
candidate before at 0c1d2e3f4a5b
  build             complete
  smoke             complete
  primary           running as process 4242
    units     212/449 accepted (530/1104 groups), 1 unresolved, 180 queued
    hosts     27 live, 2 not yet reporting
              r8i.large us-east-2 spot: 16 live, 2 not yet reporting
              r9g.large us-west-2 on-demand: 11 live
    spend     $3.12 so far, $1.80 reserved
    finish    about 6:40 p.m. PDT
    medians   boot to ready 68s, boot to first unit 172s, upload 4s
```

The command only reads the ledgers; everything live, each worker's lease and
group among it, is in Grafana, whose URL the control host records in the
campaign root's `grafana-url.txt`. A spending stop or a stop the settings hold
follows it, with who made it and why. Units and groups accepted, the estimate
left, spend, and medians come from the phase's ledgers: `work-items.tsv`,
`accepted-units.tsv`, `unit-attempts.tsv`, `semantic-rejections.tsv`,
`unresolved-units.tsv` once the phase ends, the campaign root's
`fleet-state.json`, or in an old root the fleet state beside the policy its
`execution-policy.json` names, `job-attempts.tsv`, and `unit-uploads.tsv`.
While the phase runs, its unresolved units are those a semantic failure or a
rejection ended or that spent their attempts, as the controller's recovery
reads them, and its queued units are those neither accepted, unresolved, nor
held by a lease not yet settled. Its hosts are its fleet reservations, each
held until the host's cleanup is proven, and one not yet reporting has a
reservation its controller has not seen report. Spend is the phase's completed
hosts' cost plus the cost of its live hosts' hours so far; reserved is what
their reservations hold beyond now. The finish is when the latest of the
phase's pools, a platform's workers of one instance type, finishes its work
left at its live hosts, as a local clock time. A pool's work left is the
estimated seconds of its groups not yet accepted, of units not left
unresolved, corrected by the ratio of measured to estimated seconds over its
groups accepted so far, and the controller weights the phases' shares of the
hosts by the same corrected seconds. A phase that is not complete runs, is
paused with its reason, or drains, as the campaign's settings record it, while
the campaign's controller does. Given a phase's root, the command reports that
phase alone.

With `--results`, the command reads each primary phase's accepted groups so
far instead, or the given phase's, from the phase's retained group
directories, and reports every row's Regulator time divided by its
comparator's, so a ratio above 1 means Regulator is slower:

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py status /durable/path/<campaign> --results
```

```text
candidate after lang primary: rows 1180, slower 41, 1.0 wins lost 3, unresolved 1
   ratio  n  host ratios                    1.0  platform row  flags
       -  -                                   -  r8i      5f3a9c0e...d41b replica 2  unresolved: semantic failure: status 44
   1.200  2  r1=1.180 r2=1.220            0.082  r9g      java/native/language-bulk/grep/every-line/execute  slower, 1.0 win now a loss
```

The unit ID, a 64-digit hex digest, is shortened here. In table order, the
columns are the row's ratio; `n`, how many replicas' hosts have landed the
row, timed or not; the ratio on each replica's host; the row's 1.0 ratio; and
the platform, row, and flags. Baseline rows are each comparison's same-host
ratios, as the baseline reduction pairs them, and the row's ratio is their
median; language rows are reduced per language, memory mode, suite, case, and
operation, as the report reduces them, and the row's ratio is the average
Regulator host time divided by the average comparator host time, the report's
mean estimator. The 1.0 ratio is each baseline comparison's median over its
primary hosts in `docs/benchmarks/results-1.0/baseline-tables.tar.gz`, or each
language row's published ratio in the 1.0 report.

Flags mark a row slower past the baseline reduction's material limit, a 1.0
win, faster past that limit, that is now slower, a precision rejection, a row
whose hosts cannot be reduced, a row this checkout's `rows.tsv` does not list,
and a row the comparison does not time, `not-compatible` or `did-not-finish`,
with its reason. Units the phase left unresolved come first, each with its
reason: a semantic verification failure, even where another attempt was
accepted, or attempts spent, or a rejection; once the phase ends, also the
phase's launch limit or no worker left to take it, and every other unit not
accepted, as `not accepted`. A failed lease whose retry was accepted is not listed. While
the phase runs they come from its ledgers, and once it ends from
`unresolved-units.tsv`, as the unresolved count does. A row counting the
accepted groups whose retained directories are missing also comes first. Rows
that cannot be reduced or are not listed come next, then rows by ratio, slowest
first, and rows the comparison does not time last. A phase whose results
cannot be read reports why, and the other phases still report. Add `--tsv` for
one tab-separated table with the columns `candidate`, `phase`, `platform`,
`row`, `ratio`, `replicas`, `host_ratios`, `ratio_1_0`, and `flags`.

## Run a phase

A phase runs only inside its campaign's controller. Given one phase, the
command plans it: it writes the phase's work items to `work-items.tsv` without
allocating AWS resources, and with `--execute` it refuses and names the
`campaign` command to run instead:

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py \
  smoke <campaign> --candidate <name>=<commit> \
  --campaign-root /durable/path/<campaign>
```

Every execution requires a shared transfer bucket in every launch region; see
[launch regions](#launch-regions-and-purchase). The
[settings example](#shared-fleet-budget) names one region with one shared
bucket.

The campaign builds each platform's engines once before the smoke. The build
phase leases one `engine-build` unit per platform to a unit worker of its
pinned instance type and AMI; see [engine builds](#engine-builds). A measuring
phase installs the candidate's build, or the accepted build `--engine-builds`
names.

The primary phase requires the candidate's accepted smoke, or the one
`--smoke-results` names, and measures three independent replicas of every
work unit of every platform and shard. The confirmation phase measures only the
bounded list the preliminary reduction emits in its
`confirmation-jobs.tsv`, and requires the candidate's accepted primary, or the
one `--primary-results` names.

Start with one canary per platform, then run full smoke before primary.
Use `--smoke-protocol qualification` for baseline duration qualification. The
default `smoke` protocol shortens timings and cannot establish a unit's
duration. The selection is frozen on execution and cannot change on resume.

Smoke, primary, and confirmation measure the phase's work units on [unit
workers](#unit-workers). The plan writes `work-items.tsv`: replica 1 of every
unit of the selected shards in smoke, replicas 1 to 3 in primary, and replica 4
of each `--confirmation-jobs` row, which names `platform`, `shard_id`, and
`unit_id`. A primary requires an accepted smoke, and a confirmation requires an
accepted primary.

Set `AWS_PROFILE` before any execution command when selecting a named profile;
otherwise the controller and the AWS CLI use the standard credential chain. The
controller reaches AWS through boto3, from the pinned
`tools/re2-benchmark/requirements.txt`.

### Launch regions and purchase

Each platform has its own ordered list of launch regions and its own purchase
policy, both in the [campaign settings](#shared-fleet-budget); benchmarks do
not depend on the region.

- `platform_regions` names each platform's regions, in the order its ties go,
  from the settings' `regions`. The controller refuses a platform without a
  list.
- `purchase` names how each platform buys hosts. The default,
  `spot-then-on-demand`, buys Spot and turns to On-Demand for interrupted and
  oversized items, the phase tail, and a Spot shortage outlasting its bounded
  wait. `spot` buys only Spot and never falls back. `on-demand` buys only
  On-Demand.

Neither is frozen. The controller reads both every loop. Each phase checks its
platforms' regions when it starts, and a running phase checks them again
whenever the settings' regions or a platform's list of them change, as
[`add-region`](#steer-a-campaign) does, so a campaign whose Spot capacity ran
dry moves to other regions without a restart. A phase launches in a region
only once its check there passed: the bucket's tag and region, the AMI, the
worker type's offerings, the quota, and every archive its hosts read. A check
that fails is logged, and the phase keeps launching where it did until the
next change. Every result records its host's region and market.

For each Spot launch, the controller asks AWS for the Spot placement score of
the worker type in every region of its platform's list that offers it, for the
campaign's concurrency but at most 50 instances, since AWS refused larger
queries. It caches the answer for five minutes and launches in the best-scored
region with headroom in the shared budget. Equal scores keep the list's order.
When scores are unavailable, the first region of the list with headroom
launches and the session records no score. On-Demand launches take the first
region of the list with headroom. The budget's headroom counts the region's
quota, read once per controller, and its instances and Spot requests, described
at most once per dispatch loop. A list of one region asks for no score.
Within the chosen region, each worker starts in a zone its identity picks among
those that offer the worker type, and a launch that finds a zone without
capacity moves on to the region's next zone. The controller cleans up each
worker in its own region, and its unit receipts record the region and the
market the host read from instance metadata.

A worker type's Spot shortage starts when one of its Spot launches finds no
capacity in a region. It ends when one of its Spot workers starts. A
`spot-then-on-demand` pool keeps buying Spot for 15 minutes of shortage, one
region cool-down, so every region of its list is tried again; after that, while
every region cools, its new workers launch On-Demand in the list's order. A `spot` pool waits for Spot however long the shortage lasts.
A restarted controller forgets the shortage.

Quotas are regional and account-wide, so read them before choosing lists. On
October 28, 2026, the account's standard Spot quota was 256 vCPUs in
`us-east-2`, `eu-central-1`, `eu-west-1`, and `ap-south-1` and 736 in
`us-west-2`; its standard On-Demand quota was 1,000 vCPUs in `us-west-2`.
Preflight reads the live quotas and refuses a platform whose regions cannot
admit one host under its purchase. That afternoon, at 2:30 p.m. PDT, r8i
scored 1 in Oregon, Ohio, and Virginia and 3 in Ireland, Frankfurt, and
Mumbai, and r9g scored 3 in Ohio and Frankfurt. A campaign then could send
r8i to Europe and Mumbai on Spot alone, and r9g to Ohio and Frankfurt with
Oregon's On-Demand quota behind it:

```json
"regions": {
  "eu-west-1": {"bucket": "<bucket>", "max_vcpus": 224},
  "eu-central-1": {"bucket": "<bucket>", "max_vcpus": 224},
  "ap-south-1": {"bucket": "<bucket>", "max_vcpus": 224},
  "us-east-2": {"bucket": "<bucket>", "max_vcpus": 224},
  "us-west-2": {"bucket": "<bucket>", "max_vcpus": 704, "on_demand_max_vcpus": 400}
},
"platform_regions": {
  "r8i": ["eu-west-1", "eu-central-1", "ap-south-1"],
  "r9g": ["us-east-2", "eu-central-1", "us-west-2"]
},
"purchase": {"r8i": "spot", "r9g": "spot-then-on-demand"}
```

r8i launches in Ireland until its quota or capacity runs out, then Frankfurt,
then Mumbai, since equal scores keep the list's order. r9g launches Spot in
its best-scored region, Ohio before Frankfurt at equal scores, and after a
15-minute shortage turns to On-Demand, which the settings allow only in
Oregon, where `on_demand_max_vcpus` bounds r9g's On-Demand hosts. Each region
needs the platform's AMI in `platform-regions.tsv` and its entry in the
settings' `regions`, with a shared transfer bucket.

`platforms.tsv` pins each AMI by its ID in `us-west-2`. Preflight resolves that
image's name and requires each launch region's AMI in `platform-regions.tsv`
to have that exact name, Amazon's owner ID, and the platform's architecture.
Hosts record the name. The controller refuses a platform's region with
no row in the table. Amazon deprecates its images after about three months;
deprecated images still launch by ID but are hidden from name searches. To add
a region, find the pinned image's copy there:

```bash
aws ec2 describe-images --region <region> --owners 137112412989 --include-deprecated \
  --filters "Name=name,Values=$(aws ec2 describe-images --region us-west-2 \
  --image-ids <pinned-ami-id> --query 'Images[0].Name' --output text)" \
  --query 'Images[0].ImageId' --output text
```

Hosts read campaign inputs from a shared bucket in their launch region. Name
each launch region's bucket as its `bucket` in the settings' `regions`.
Each shared bucket must carry the `Project=regulator-benchmark` tag, and
preflight checks the tag and the bucket's region. A bucket holds two trees:

- `shared/` holds the content-addressed archives described below, which
  every campaign reuses by key. The `ExpireSharedArchives` rule in
  `bucket-lifecycle.json` expires an object seven days after its upload, and a
  phase, or a region `add-region` adds, puts an archive again once it is five
  days old, so an archive outlives any phase that uses it.
- `campaigns/<campaign>/<session>/` holds each worker's leases and results.
  Cleanup removes the session's prefix, and the `ExpireCampaignSessions` rule
  expires what a lost controller leaves one day after it was written.

Both rules also abort a multipart upload left incomplete for a day. An object
outside both trees never expires; the [account setup](#account-setup) removes
any an older layout left, and creates each bucket as `add-region` does.

### Engine builds

The `build` phase builds, once per platform, the candidate's classes, the
pinned Trino harness, and the native comparison runners from the candidate's
sources: native RE2 (`language_benchmark`) and the Rust `regex` crate
(`language_rust_benchmark`), and their bulk runners for language units, with
generic tuning, each beside its build receipt. It freezes them in
`classes.tar.zst`, `trino.tar.zst`, and `native.tar.zst` under a manifest,
`engine-build.json`. Each archive is a deterministic tar compressed with zstd
at level 9 on every core; decompression speed does not depend on the level,
and the contents are mostly jars and binaries that compress little further.
The manifest lists each archive's SHA-256 and its inputs: the candidate, the
Trino commit, and the build scripts. A builder fetches the pinned Trino commit
itself, so no host receives a Trino checkout.
The build checks only the harness's identity and provenance; the semantic
verifiers run in every measuring route, where a failure is final rather than
retried.

The classes archive holds `regulator-classes/` with the candidate's `classes/`
and `test-classes/`, its test-scope dependency jars in `lib/`, and
`classpath.txt`, their classpath relative to `regulator-classes/`. Its `lib/`
also holds `junit-platform-console-standalone.jar`, the JUnit console launcher
of the project's JUnit Platform version, which runs a route's semantic tests;
it is not on the classpath. The harness's classpath names the installed
candidate classes and jars rather than the builder's Maven output, so measuring
hosts neither run Maven nor fill a Maven repository.

The build phase's unit, `engine-build`, runs replica 1 on every platform and is
estimated at 900 seconds, so it fits a Spot lease. Its workers install no
prebuilt engines: they install the pinned Cargo and Rust packages and build
their own. The unit is retried and accepted as any work unit is, into the build
root's `accepted-units.tsv`. Its receipt's observed digest is the manifest's
SHA-256, and its protocol and manifest digests cover the build scripts, so the
phase is accepted only when every platform's build came from the checkout's
scripts and one candidate.

Smoke, primary, and confirmation execution require an accepted build root for
every platform: the candidate's build phase, or the root `--engine-builds` names
instead. One build root serves any measuring campaign, baseline or language,
whatever its campaign ID and heap: the root is revalidated under the campaign ID
in its own `campaign-state.tsv`, which must name a build phase, its cleanup must
be verified and it must have no semantic rejection. Against the measuring
checkout, its builds must be of the same candidate and archive, made by the same
build scripts from the pinned Trino commit, on each platform's pinned
architecture and instance type. Each platform's manifest SHA-256 is frozen in
`execution-policy.json`, and primary and confirmation refuse a prerequisite that
used different builds. Baseline and language unit workers run the candidate
classes and native runners of their platform's engine build.

A host installs each archive in one streaming pass: the S3 download feeds
both a SHA-256 digest of the whole archive and `zstd -d`, whose tar stream is
extracted into a staging directory inside the destination. The extraction
refuses absolute, parent-relative, and duplicate entries, and any entry other
than a regular file or directory. Only once the stream ends with the archive's
pinned SHA-256 does the host move the staged tree into place; a mismatch
removes the staging directory and fails the session. A failed download or
extraction is tried again, three attempts in all, and a complete stream that
fails its pin is not.

A host installs only the archives its pool's units run: a baseline unit worker
installs the classes, Trino, and native archives, since its specialized-pattern
shards run native comparators, a language unit worker the classes and native
archives, and a build phase's worker none, since it builds its own. The
candidate classes land in `regulator-classes/`, the harness in `pinned-trino/`,
and the runners in `language-native/` under the shared work directory, and
`engine-build/<archive>.sha256` marks each installed archive. `engine-build.txt`
records the manifest and installed archives beside the host evidence and in
each route.

Before it launches any host, the controller puts each archive once in every
region that may launch a host installing it, under
`shared/engine-builds/<sha256>.tar.zst`, and skips an object with that key
whose metadata records the archive's SHA-256 and which is under five days old.
The frozen candidate archive, which every host unpacks first,
travels the same way under `shared/candidates/<sha256>.tar.gz`. A
language plan's bundle, which a language unit worker
installs, travels the same way under
`shared/language-plans/<sha256>.tar.zst`; a worker's user data names
the bundles of its own pool's shards. The candidate provenance their units
measure travels under `shared/candidates/<sha256>.tsv`. The platform's
pinned JDK travels the
same way under `shared/jdks/<sha256>.tar.gz`: the controller downloads
it once per campaign from its pinned URL into the campaign root's `jdks/`,
checks it against the pinned SHA-256, and puts it in each region, so hosts never
fetch it from its publisher.
Each upload is the SDK's managed transfer, in parallel parts once the archive
is large, each part checked by S3 against its own SHA-256; a multipart
object's checksum is not the whole archive's, so the upload keeps the
archive's SHA-256 in the object's `sha256` metadata, and hosts check the
whole archive against its pin after download. A failed upload, such as one
whose part waited 60 seconds for a read, is retried; the campaign stops after
three failed attempts. The
[worker profile](#worker-profile) may read those archive keys, in addition to
each worker's own input prefix. These archives are campaign inputs rather than
worker resources, so worker cleanup leaves them in place for the lifecycle rule
to expire.

## Launching a worker

The controller launches each [unit worker](#unit-workers) itself. It names the
worker's host session `<campaign>-e<host epoch>-<UTC second>`, such as
`baseline.a-e12-20261024T120000Z`, records the session and its prefix in the
worker's `starting` row of `job-attempts.tsv`, and then calls `RunInstances`
in the worker's region with:

- the platform's AMI, the worker type, and a 40 GiB encrypted gp3 root;
- a public subnet of the region's default VPC and its default security group;
- the [worker profile](#worker-profile), IMDSv2 required, and
  `InstanceInitiatedShutdownBehavior=terminate`, so a host that powers off
  terminates;
- Spot as a one-time request whose maximum price is the settings' Spot hourly
  rate, or On-Demand;
- tags naming the campaign, phase, platform, replica, host epoch, and session;
- a client token derived from the session and zone;
- user data that exports the worker's settings, its prefixes, archives, stop
  time, and pinned identities, after the first line of
  `worker-user-data.sh`.

A launch AWS refuses for capacity moves on to the region's next zone. A
refused Spot launch, for capacity, quota, or price, is `retryable-spot`; an
On-Demand one, for capacity or quota, is `retryable-capacity`; either has
detail `launch-capacity:<code>`. Anything else is
`retryable-infrastructure`. A request whose answer never comes is sent again
with the same client token, so AWS launches at most one instance, and the
controller then finds the instance by its session's tag.

`worker-user-data.sh` starts the host's stop-time watchdog and startup
timeline, waits for the profile's credentials, downloads and checks the frozen
candidate, and runs `run-host.sh`. On exit it uploads the host evidence to
`<result_prefix>/host.tar.gz` and powers off.

`run-host.sh` runs on the instance. It verifies the AMI, JDK, package,
candidate, comparator, and manifest identities, and runs `run-unit-worker.sh`.
Every host installs the pinned glibc, GNU `time`, and zstd packages. Only a
worker without prebuilt engines, which builds them, installs the build
toolchain: the pinned compiler, CMake, Cargo, and Rust packages with git, make,
and ninja, because it builds the native runners, the pinned `regex` crate, and
the Trino harness. A measuring host records those packages as `not-installed`.

### Worker profile

Every worker runs under one permanent instance profile,
`regulator-benchmark-worker`, whose role may read any campaign's inputs and
shared archives, and write its results and lease claims, in the
`regulator-benchmark-938463256137-*` buckets; `worker-policy.json` is its
policy. Preflight checks that the profile exists, with one IAM read, and the
controller never writes IAM. The [account setup](#account-setup) creates it.

## Unit workers

A unit worker is a host that measures the work units its controller leases to
it, one at a time. Its planned span is the worker lifetime,
`UNIT_WORKER_LIFETIME_SECONDS` (default 21600), plus 4500 seconds for host setup
and final uploads, plus the time its last unit may take: 900, or the pool's
longest estimated unit when that is longer. Its user data names
`HOST_STOP_BY`, the epoch second its
[fleet reservation](#shared-fleet-budget) covers the host until, and passes
`UNIT_WORKER_IDLE_SECONDS` (default 600) to the worker. The user data keeps the
host's stop time in `/var/tmp/re2-stop-by` and starts a watchdog before anything
else, which powers the host off once that time passes, whether it is installing,
measuring, or uploading. Only a work lease moves the stop time, and only later.
The host bootstraps with packages, the JDK, its pool's prebuilt engine archives
unless it is a build phase's worker, and the environment record. A language unit
worker also installs the plan bundle of each of its pool's shards under
`target/language-plans/<shard>`. The engine archives, plan bundles, and JDK
install in the same [streaming pass](#engine-builds), the JDK through gzip. The
candidate archive does not: the host downloads it, checks its SHA-256, and only
then extracts it. The host writes `startup-timeline.tsv` beside its evidence:
one row per startup step, with columns `step`, `started`, and `finished` in
epoch seconds. Its steps are `boot`, from the kernel's boot to the start of user
data, then `credentials`, `candidate`, `packages`, each archive by name,
`environment`, and `worker`, which ends when the worker begins its first unit.
The controller reports the time from boot to that first unit as
`boot_to_first_unit_seconds=` in the detail of the attempt's `job-attempts.tsv`
row. The host runs `run-unit-worker.sh`, which runs `../baseline/run-unit.sh`
for each measured group of each lease. The worker's `starting` row records its
host session (`session`) and its prefix (`prefix`); its input prefix is
`<prefix>/input` and its result prefix `<prefix>/results`, and every key below
is under one of those.

| Key | Writer | Holds |
|---|---|---|
| `<input_prefix>/leases/<seq>.json` | controller | the host's lease `seq`, numbered from 1 |
| `<result_prefix>/heartbeat/<seq>.json` | host | its state as lease `seq` starts, as each of its groups completes, or as the host stops while it holds or awaits it |
| `<result_prefix>/units/<seq>/groups/<g>/result.tar.gz` | host | the directory of group `g` of lease `seq` |
| `<result_prefix>/units/<seq>/groups/<g>/completion.json` | host | the group's completion marker, written after its result |
| `<result_prefix>/events/<seq>-interrupted.tar.gz` | host | an interruption record and the running group's log |
| `<result_prefix>/host.tar.gz` | host user data | the host evidence, once the worker exits |

Every upload of `run-unit-worker.sh` is one PUT with its SHA-256 checksum and,
but for a heartbeat, which replaces its lease's last one, `If-None-Match: *`,
so no other key is overwritten. Each request
gives up after 5 seconds connecting or 30 reading, and a failed upload is
retried every 5 seconds while the worker keeps watching for an interruption
notice. An attempt starts only if its worst case of 35 seconds still ends within
`UNIT_WORKER_UPLOAD_SECONDS` (90) of the upload's start, so the whole upload
stays within that budget. A group result's attempts and budget grow by a second
per 4 MiB, such as an engine build's archives, and its budget to at least two
whole attempts; an interruption bundle uploaded meanwhile keeps its own
35-second attempts. Since a request whose connection stays alive can
outlive the CLI's timeouts, every S3 call of the worker, heartbeats and lease
reads too, runs under coreutils `timeout`, which ends it after 35 seconds, or
a group result's stretched attempt time; a call within an upload's budget also
ends when that budget does. The interruption bundle and
the final heartbeat after it share one budget. The user data's upload of the
host evidence, once the worker exits, is a plain `aws s3 cp` outside these
rules. The worker profile allows `s3:ListBucket` only below an input prefix's
`leases/`, so the worker tells a lease not yet written from one it cannot read.

A lease is a JSON object:

```json
{"schema_version": 2, "campaign_id": "primary-20261024", "phase": "primary",
 "platform": "r8i", "host_session": "primary-20261024-e12-20261024T120000Z", "sequence": 3,
 "unit_id": "<64 hex digits>", "replica_id": 2, "groups": ["2", "1"],
 "protocol": "qualification", "stop_by": 1793102400}
```

A measuring phase's `unit_id` is 64 hex digits; the build phase's one unit is
`engine-build`, at replica 1. `groups` lists the unit's
[measured groups](../baseline/SHARD_DISPATCH.md#work-units) the lease runs, in
order: those of the replica not yet accepted, in the replica's run order. A
stop lease replaces `phase`, `unit_id`, `replica_id`, `groups`, `protocol`, and
`stop_by` with `"stop": true`. Before an idle stop the host claims its next
lease as its own, a stop lease that adds `"by": "worker"`, with the same
conditional write, so either the claim or the controller's lease lands; when
the controller's landed first, the host reads and runs it. `stop_by` is the host's shutdown time, in epoch
seconds, that the controller's fleet reservation covers once the host holds
this lease; the worker moves the host's stop time to it when that is later.
The host reads lease `seq` only after lease `seq - 1` is done, so the
controller may write one lease ahead. The host requires schema version 2 and
its own sequence, host session, campaign, and platform, and a work lease's
unit ID, replica 1 to 4, phase, distinct group IDs, protocol, and stop time;
any other lease stops it. Each lease read is kept in the host evidence under
`leases/`.

A heartbeat is `{"schema_version": 1, "host_session", "instance_id",
"sequence", "state", "unit_id", "replica_id", "timestamp", "stop_reason"}`,
written only as a lease starts, as each of its groups completes, and as the
worker stops or is interrupted, so a measuring host makes no S3 calls between
groups. `state` is `running`, `stopped`, or `interrupted`; the unit, replica,
and stop reason are `null` when they do not apply.

The host runs each group of a lease into its own directory and uploads the
group's `result.tar.gz`, then its completion marker, before it starts the next
group, so a reclaimed instance loses only the group it was running. A
completion marker is `{"schema_version": 2, "host_session", "sequence",
"campaign_id", "phase", "platform", "unit_id", "replica_id", "group_id",
"unit_attempt_id", "exit_status", "unit_status", "result_sha256",
"result_bytes", "pack_seconds", "upload_seconds", "upload_attempts",
"started_at", "finished_at"}`. `unit_attempt_id` is `<host_session>/<seq>`,
`unit_status` is `complete` or `failed`, and `result_sha256` and
`result_bytes` are the digest and size of `result.tar.gz`. `pack_seconds` and
`upload_seconds` are the wall time, to the millisecond, the host spent packing
the result and uploading it, and `upload_attempts` the PUT attempts its upload
took. A failed group, including one whose representative failed with status 42,
is still uploaded and marked, and it ends the lease: the host runs none of the
lease's later groups and takes its next lease. A lease is done once its last
group's marker lands, or a marker reports a failure. A `result.tar.gz` without
its completion marker is not a finished group.

The worker polls the instance metadata service (IMDSv2) every 5 seconds and
after each of its S3 calls, so a stalled S3 call delays a notice by at most that
one call, and a stop with a notice already arrived, or arriving during its final
heartbeat, is an interruption. A Spot instance-action notice stops leasing and
kills the running group's process group. The host uploads
`events/<seq>-interrupted.tar.gz`, holding `interruption.json`
(`schema_version` 2, `host_session`, `sequence`, `unit_id`, `replica_id`,
`finished_groups`, the lease's groups whose markers landed, `running_group`,
`notice_time`, `action`, `action_time`) and the running group's
`worker-unit.log`, then exits 43; the finished groups are already uploaded.
Its user data uploads the host evidence if the instance lasts. A group whose
marker had not landed never completes. The controller reads the worker's exit
status from the host evidence and classifies status 43 as `retryable-spot`. A
host EC2 reclaims before it uploads is also `retryable-spot`, and any other
host that ends without uploading is `retryable-infrastructure`. A rebalance recommendation is only a hint that EC2 may
reclaim the instance, so the worker goes on leasing until the notice comes.

The worker stops, with its reason in the final heartbeat and
`worker-session.txt`, on:

| Reason | When | Status |
|---|---|---|
| `stop-lease` | it reads a stop lease | 0 |
| `idle` | its next lease is absent for `UNIT_WORKER_IDLE_SECONDS` (600) and its claim of it lands | 0 |
| `lifetime` | its lifetime has passed; no lease starts after it, a running unit finishes | 0 |
| `interrupted` | a Spot instance-action notice | 43 |
| `invalid-lease`, `lease-unreadable`, `upload-failure` | a malformed lease, 12 failed lease reads in a row, or an upload still failing at its bound | 1 |

The host evidence holds the environment files, `engine-build.txt`,
`host-capacity.txt`, `leases/`, `worker-leases.tsv` (each group's outcome,
status, attempt ID, and the digest of its environment files),
`worker-session.txt`, the shared semantic receipts, and the logs.

Each group result is self-contained: its routes record the host's CPU steal
over their measurements, and [unit acceptance](../baseline/ACCEPTANCE.md)
needs nothing from the host evidence.

The first accepted attempt of a platform, unit, replica, and group wins. A
later completed attempt of the same group, such as one leased again after a
lost heartbeat, is recorded as superseded, not accepted, and does not pause
the phase.

### Unit dispatch

The controller keeps a queue of the phase's work items and a pool of workers
per platform and instance type: a unit runs on the instance type its shard is
allocated on that platform, which is the platform's frozen type for every
baseline shard. Each worker is a job of the controller's ordinary launch path,
so regions, placement scores, Spot launch retries in the next region, the
settings' host limits, and the shared budget apply to it,
and its reservation lasts until its cleanup is verified. `unit-workers.tsv`
freezes each worker before its launch.

A pool launches a worker only when it would start queued work sooner than the
pool's workers would. The controller projects when each worker could take new
work: a booting worker 5 minutes after its launch, and a busy one once its
leases have run their groups' estimates. The running lease's estimate counts
from when that lease began. Taking the items in the order it leases them, the
worker free soonest that may take an item takes it. A new worker would start its
first item 5 minutes from now, so the pool launches one when some item would
start later than that, or no worker may take it. The market, the limits, and the
budget then apply as below. A worker still booting is stopped once it holds no
lease of open work and no queued item is one it could take; work requeued later
launches a worker by the same rule. Its stop lease records it as stopping, so it
keeps its reservation and takes no work until its cleanup is verified.

A worker whose launch found no capacity in any zone of its region, or no
quota, cools that region for its pool's launches in that market for 15
minutes, and the pool's new workers in that market launch in the list's other
regions meanwhile. Such a refusal spends none of its slot's replacements. While
every region of the list cools, the pool tries every region again at once; a
`spot-then-on-demand` pool whose Spot shortage has lasted its
[bounded wait](#launch-regions-and-purchase) launches On-Demand instead, and
Spot resumes in each region once its cool-down ends. Through its shortage the
pool tries one Spot launch a loop, so a pool AWS keeps refusing leaves the boot
window to the others. A restarted controller forgets the cool-downs.

The controller writes a worker's first lease before it launches the host, so
the host finds its work waiting when its setup ends. It also writes the second
lease then, unless the pool's idle workers and the workers it may still launch
could take every queued item, so a burst spreads its first leases across its
hosts. Once the worker reports, the controller keeps one lease written ahead
while no idle worker of the pool could take the
item. Eligible items lease by On-Demand priority, then replica, then remaining
work: On-Demand work first, then the item of the lowest replica, and of those
the one with the most estimated work remaining, so the phase has a first
reading of every unit as early as it can. A lease never goes to a unit the host
already leased, nor to an instance a confirmation excludes or that measured any
group of the unit's replicas. A lease runs the item's remaining groups. An item
requeued for On-Demand only goes to an On-Demand host, and On-Demand hosts also
take every other item. When the pool can give a worker nothing more, the worker
gets a stop lease. Each lease, and each outcome it reaches, is a row of
`unit-attempts.tsv`, written before the lease is; its `groups` are the groups
a lease runs, or those whose markers an outcome processed. A unit whose
longest remaining group is estimated past the 15-minute unit budget, such as a
slow language comparison isolated in its own unit, is On-Demand work unless its
platform buys only Spot; it leases first because only the On-Demand hosts
launched for it may take it.

Each loop lists every live worker's uploads at once, one listing per worker
on a pool of request threads, and writes the loop's leases and launches on the
same threads. The controller processes a lease's markers in its groups' order,
each as it lands. Four acceptance threads gather a group's completion, one
group of a lease at a time: its result must match `result_sha256` and its
lease, and the group then passes the unit gate. The controller applies each
gathered group on the loop's own thread, normally the loop after it landed: it
retains the group and appends it to `accepted-units.tsv`, one row per group,
and the marker's upload timings and the seconds between its `started_at` and
`finished_at`, the group's measured seconds, to `unit-uploads.tsv`. Once its row is durable,
the group's marker and result are deleted from S3, and a settled lease's
heartbeat and lease object go too, so a worker's prefix holds only its current
leases however long it runs; the ledgers keep the group finished across a
restart. Once the lease's last group is processed, the lease is `accepted`,
or `superseded` when every group was accepted first elsewhere. Other outcomes:

- An interruption record, or a status-43 exit while a lease ran, records an
  `interruption` event. Once a group of the lease was accepted, the lease is
  `interrupted-progress`: the item keeps its accepted groups, and its
  remaining groups are requeued on either market without spending an attempt.
  Otherwise the lease is `interrupted`, spends an attempt, and requeues the
  item for On-Demand only, unless its platform buys only Spot. A record that
  names no unit, from a notice while the host waited for its next lease,
  releases that lease without spending an attempt.
- A lease the controller writes after its host claimed that sequence as its
  idle stop never runs: it is `claimed`, those behind it are released, its item
  is requeued without spending an attempt, and the host gets nothing more.
- A host whose instance is no longer pending or running, or that EC2 still
  lists none of by its boot deadline, is lost. A lease has started if one of
  its groups finished or a heartbeat shows it began; the lost host's started
  lease is requeued, and an unstarted one is released.
- A running host has stalled once its latest heartbeat, or the upload of the
  latest of its lease's groups the controller saw finish if that is later, is
  older than the budget of its lease's unfinished groups: twice their estimate,
  or twice the 15-minute unit budget when that is longer. It gets a stop lease
  and is terminated, its lease is `lost` with detail `stalled`, and the leases
  behind it are released. Cleanup still recovers a completion that lands from
  a lost or stalled host, which is accepted if it is first and superseded
  otherwise.
- A failed group requeues the item's remaining groups. A unit replica gets 3
  attempts across failures, interruptions without progress, and lost hosts,
  then stays unresolved.
- A worker that has not reported by its boot deadline, 20 minutes after its
  launch, is stopped, and the leases it was launched with are released
  without spending an attempt.
- A worker that fails before its first lease passes its launch slot to a
  replacement. A worker whose uploads include a heartbeat, a completion or an
  interruption bundle has started, even if no heartbeat landed. The host
  evidence its user data uploads on exit, even after a failed bootstrap, does
  not show a start. A slot whose worker has failed 3 times gives up. Once
  the pool has no worker or slot left, its queued work stays unresolved. Each
  worker's `starting` record in `job-attempts.tsv` names its slot (`slot`, the
  host epoch of the slot's first worker) and the failures it inherited
  (`slot_failures`), so a restarted controller resumes every slot's count.
- A status-42 protocol rejection, a status-44 semantic failure, a rejected
  unit capacity, a result that
  does not match its marker, and a failed artifact gate, in any group, leave
  the unit replica unresolved without a retry while other work continues.
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
- A marker that names another lease and changed frozen inputs pause the
  phase.
- A worker whose cleanup cannot be proven keeps its reservation, and each loop
  tries its cleanup again; the phase does not complete while one remains. A
  leftover, a terminated worker's prefix that will not empty, is logged each
  loop and pauses nothing. A leak, an instance not terminated 15 minutes after
  the controller first asked EC2 to terminate it, is logged and sets the
  campaign's spending stop, as
  [recovering uploads during cleanup](#recovering-uploads-during-cleanup)
  describes.

When a pool's remaining estimated work, the groups not yet accepted, falls to
10% of its total, its remaining items become On-Demand work, leased first, and
On-Demand workers launch for them within the budget's On-Demand allowance. Each
Spot worker of the pool gets a stop lease only once an On-Demand worker has been
admitted to replace it; until then it keeps taking the pool's items. While the
budget refuses an On-Demand launch, the pool's Spot launches go on for the items
Spot may run. A `spot` pool never switches, and an `on-demand` pool runs every
item On-Demand. Once every item is accepted or unresolved, the controller waits
for every worker's verified cleanup, lists the unresolved items in
`unresolved-units.tsv`, and otherwise validates the phase's accepted units as
one campaign. Only then does it write `phase-complete.txt` in the result root,
atomically; a later phase, including one consuming an engine build root, refuses
a root without it, such as one whose controller stopped after its ledger was
complete.

## Recovery and evidence

A phase restarts from its ledgers, and only with the tooling that
started it: `tooling-digest.txt` records a digest of the tracked benchmark
tooling when the phase starts, and a restart that finds a different digest, or
none, is refused. The controller adopts each worker its `job-attempts.tsv`
shows launched and not settled, finding its instance by ID or, when the
launch's answer never came, by its session's tag, rewrites its outstanding
leases, which must match what was written, and settles it once its cleanup is
proven, so no accepted group runs again and no unit is leased to two live
hosts. A worker EC2 still lists none of by its boot deadline settles as
`retryable-infrastructure`. A root whose workers the earlier per-host wrapper
launched is refused; run the phase in a new root. Each item's remaining
groups are rebuilt from the ledgers. A unit rejected for a
semantic failure stays unresolved across restarts, so measuring it again
means running the whole phase again in a new result root.

Failed jobs record their failure type, as [launching a worker](#launching-a-worker)
and [unit workers](#unit-workers) describe. Frozen inputs that fail their checks
before a launch, such as a candidate archive whose SHA-256 or engine tree
differs, pause the phase, since a retry would fail them again.
[Unit dispatch](#unit-dispatch) retries a failed unit instead.

See:

- `../baseline/SHARD_DISPATCH.md` for routes and timing protocol
- `../baseline/ACCEPTANCE.md` for host and campaign receipts
- `../baseline/REPORTING.md` for reduction and report generation
- `../baseline/ARTIFACT_ARCHIVE.md` for permanent evidence storage

### Recovering uploads during cleanup

A worker's cleanup is two proofs, in order. First, EC2 lists its instance as
terminated; the controller asks EC2 to terminate one not yet terminated. Then the
controller downloads every object under the worker's result prefix into
`<session>/recovered-uploads/objects/`, by its key below that prefix, deletes
the worker's whole prefix, leases included, and lists it empty. A failed
listing, download, or deletion keeps the prefix and the worker's reservation,
and the next loop tries again. Only once both proofs hold does the controller
release the reservation and settle the worker from its recovered uploads, as
[unit dispatch](#unit-dispatch) describes.

The controller asks EC2 to terminate an instance each loop until EC2 lists it
shutting down, logging each request that fails. Before the first, it records a
`terminating` row in `job-attempts.tsv` with the request's time
(`requested_at`), from which a restarted controller resumes the clock. An
instance still not terminated 15 minutes after that first request, whether or
not it succeeded, is a leak: the controller logs it and sets the campaign settings' `spending_stop`, as its
own settings change naming the reason. While the stop stands, the budget admits
no launch and no lease extension for any phase, refusing each with reason
`spending_stop`, but stops no controller, so every host finishes the leases it
holds within its reservation. The stop stands until Dain clears it, once the
instance is gone, with `control ... --actor dain clear-spending-stop`. An
instance or Spot request AWS lists with no instance type, or whose vCPUs AWS
omits, sets the stop in the same way, since the fleet cannot account for it.

This covers late uploads and results available when a controller aborts.
Measurements still on an interrupted or forcibly terminated host may never
reach S3. After cleanup is proven, a benchmark or artifact rejection leaves
its unit unaccepted while unrelated units finish.
