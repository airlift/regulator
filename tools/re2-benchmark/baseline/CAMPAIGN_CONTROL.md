# Steerable campaign control

The design of the campaign controller: one process on an AWS control host
that runs every phase of a campaign, takes live commands, and reports
through Grafana. Operator steps live in [`../aws/README.md`](../aws/README.md).
This document records the design and the decisions behind it; the lifecycle
sections describe the behavior the controller implements.

## Problem

The 2.0 release run `r20-20261029a` (2026-10-29) halted its whole fleet 51
minutes after launch with no primary work accepted. The language primary
launched 183 hosts in one burst. Each launch was a blocking wrapper start of
about 2.5 s, so the burst alone took 8 minutes; the next pass polled the
workers with one `aws` CLI process each, about 4.75 minutes more. Worker
replica-1 was ready 67 s after launch (boot 7 s, credentials 1 s, candidate
8 s, packages 13 s, JDK and plans 8 s) and waited the full 600 s idle stop
for a lease that never came. 54 workers did the same and their wrappers
deleted their S3 prefixes; the controller then wrote leases into 19 cleaned
prefixes; the cleanup proof saw them and halted the shared fleet. About 10 of
the 31.8 billed machine-hours were idle hosts. The monitoring delegate's
report never reached the coordinator, so nothing acted until morning.

## Principles

1. **A ready host never waits on the controller.** Work is there when setup
   finishes and the next unit is there before the current one ends. A slow,
   busy or restarting controller costs nothing until a host runs out of slack.
2. **The controller's cost per action is a network round trip, not a process
   start.** Anything done per host per loop is cheap and concurrent at 200+.
3. **As little per-host state in AWS as possible.** Every per-host resource is
   a cleanup proof that can fail.
4. **Large-fleet behavior is designed and tested, not discovered.** Burst and
   steady state at full scale run in the simulated fleet.
5. **Tooling size is a cost.** Delete more than is added; one path.
6. **People and agents see progress from the same live data**, a Grafana LGTM
   stack fed by the controller.
7. **The run lives in AWS; laptops observe and steer.**

## Terms

- **Campaign**: one frozen candidate measured through its **phases** (build,
  smoke, primary, confirmation; baseline and each language plan), each with
  its own result root and ledgers, unchanged.
- **Controller**: the one process that runs every phase of a campaign. It
  runs on the **control host**, an On-Demand instance in the dev account.
- **Pool**: a phase's workers of one platform and instance type. **Worker**:
  one host, driven by **leases** in S3. **Slack**: the leases a worker holds
  beyond the one it runs; the controller keeps it at one.
- **Share**: the hosts a phase may hold now, recomputed every loop or pinned.
- **Settings**: what may change during a run. **Frozen inputs**: what decides
  measurement validity and cannot.
- **Pause** (phase): no new launches or leases; running leases finish and the
  workers idle-stop. A phase paused for a fault still reports its hosts' boots
  to the boot window and still stop-leases them at a stop. A pause spends none
  of the phase's deadline (see [Progress and finish](#progress-and-finish)).
  **Drain**: no new launches; queued work goes to live workers until they
  stop. **Stop** (campaign): stop-lease every worker, prove cleanup, exit.
  **Spending stop**: no launch anywhere until cleared.
- **Leak**: an instance or Spot request that outlives its cleanup. **Leftover**:
  an S3 object that outlives its session; it costs nothing and is retried.
- **Monitor**: the agent thread that reads Grafana on a schedule and acts
  within the runbook. **Actor**: who made a settings change: Dain, the
  monitor, or the controller.

## Lifecycle

### Account setup, once

Like the shared buckets, these resources exist before any campaign and are
never created or deleted per host or per campaign (principle 3; today each
host creates four IAM resources and deletes three):

- the **worker role** and its instance profile, which every worker instance
  runs under. Its policy allows, in every `regulator-benchmark-938463256137-*`
  bucket, reads under `campaigns/*/input/*` and `shared/*`, writes under
  `campaigns/*/results/*`, and writes and listing under
  `campaigns/*/input/leases/*` for the self-stop claim. A host of one campaign could write another session's
  prefix; every result is checked against its marker's SHA-256 and lease
  identity, and the hosts are ours, so this is accepted.
- the **control host role**: run, describe and tag instances, and terminate
  workers and cancel their Spot requests; placement scores, offerings, images,
  service quotas; read and write the campaign buckets and create one for
  `add-region`; write and read campaign root archives in the archive bucket
  but never delete them; read the network parameter and keep the campaign's
  Grafana credentials; Session Manager (`AmazonSSMManagedInstanceCore`); pass
  the worker role. It has no load balancer permission: a policy scoped to the
  target group would name it in this public repository, so the Mac-side
  commands, under Dain's credentials, register each control host there.
- an internal **Network Load Balancer** in the router's subnets, since the
  router forwards only tcp 443: a TLS listener on 443 with the account's
  wildcard certificate forwards to a target group on tcp 3000, Grafana, and
  the private DNS record is a permanent alias of the load balancer.
- the control host's **security group** in the router's VPC, admitting only
  tcp 443, ssh, from the VPC's CIDR and tcp 3000 from the load balancer's
  own security group, shared by every campaign's control host.
- the private **archive bucket**,
  `regulator-benchmark-archive-938463256137-us-west-2`, which keeps each
  finished campaign's raw root for good. Its only lifecycle rule aborts
  incomplete multipart uploads, and finish refuses an enabled rule that would
  expire an archive or move it out of Glacier Instant Retrieval.

Every campaign bucket keeps two trees with their own lifecycle rules, from
`../aws/bucket-lifecycle.json`. The content-addressed archives every campaign
reuses (candidates, engine builds, plan bundles, JDKs) live under `shared/`
and expire seven days after upload; a phase's region check, which a running
phase repeats every day, or a region `add-region` adds, puts an archive again
once it is five days old, so no phase outlives its archives. A check that
fails still puts them again in each region the phase launches in, each region
on its own, so one region's failure leaves the others' fresh. Each campaign's
worker sessions live under
`campaigns/<campaign>/<session>/` and expire after one day, which sweeps what
a lost controller leaves. One rule over the whole bucket could not serve both:
a one-day expiry would delete archives that a later phase skips uploading
because it finds them in place. Old campaign roots stay readable, because
their ledgers record keys rather than derive them.

The control host is reached through the company's existing Tailscale subnet
router, which carries the dev VPC's private subnets onto the tailnet but
forwards only tcp 443: Grafana through the internal load balancer at
`https://<record>`, and ssh on port 443 at the host's private address. Nothing
for Tailscale is installed on the host, and no Tailscale key or tag is needed.
ssh trusts a host's keys only once its console lists their fingerprints:
`control-host known-host`, which `finish` and the monitor's `host` both use,
records only those keys for the host's address. The VPC, the subnets the router
carries, the ingress CIDR, the private DNS zone, the record name, and the
security groups, load balancer, target group, listener and certificate the
account setup creates are stored as one JSON SSM parameter,
`/regulator-benchmark/control-host-network` in us-west-2, created during
account setup. The repository is public and names only the parameter.
`../aws/README.md` documents the setup with the `dev` profile, as it does the
buckets.

### Launch a campaign

From the Mac, `run-campaign.py campaign <id> --execute --ssh-public-key PATH ...`:

1. Validates options, plans, quotas and placement as today, with the SDK.
2. Creates the campaign volume (gp3, 200 GB; past full campaign roots are
   16 to 28 GB and LGTM data is small) and launches the control host in
   us-west-2 (m7i.large, 8 GB, enough for the controller and `otel-lgtm`) in
   one of the router-carried private subnets, with no public address; its
   outbound traffic goes through the VPC's NAT gateway. Its security group,
   from the account setup, admits only ssh on tcp 443 from the VPC's CIDR,
   where tailnet traffic arrives from the router, and Grafana on tcp 3000
   from the load balancer; nothing is open to the internet. The launch inputs (the controller's command, the candidate
   commits, the settings and the language plans) travel through the Oregon
   bucket onto the volume. Session Manager
   is the shell fallback when the router is down; the run never depends on
   the router. Its user data installs Python, boto3 and the OpenTelemetry SDK
   at pinned versions and Docker with `grafana/otel-lgtm` (Grafana on port
   3000, OTLP on 4317 and 4318 bound to loopback); generates the Grafana admin
   password into an SSM SecureString and turns anonymous access off; mounts
   the volume at the campaign root; clones the public repository at the
   candidate commit (the controller's tooling is the candidate's, which the
   campaign already requires); and starts the systemd unit
   `regulator-campaign@<id>` with the recorded command. That command is the
   launch's with `--controller` and the host's paths: `--controller` runs
   the controller in the process, and without it `--execute` launches a
   control host.
3. Registers the host in the load balancer's target group, so Grafana keeps
   one URL across host replacements, and returns with that URL and the ssh
   line, port 443 at the host's private address. One URL serves one
   campaign: the launch and `control-host replace` refuse while the target
   group holds any live host not tagged for this campaign, whether its
   `Campaign` tag names another or it has none, which the refusal names as
   campaign `unknown`. A registered host that has terminated, or that EC2 no
   longer lists, serves none and does not count; `replace` and `teardown`
   deregister it whatever its campaign, along with every host of their own
   campaign, whether or not it still runs. Two launches can pass that check
   at once, as when one starts while a `replace` has deregistered the host it
   replaces, so each looks again once it registers its host, and one that
   finds another campaign's host there, or cannot register or look, deregisters
   and terminates its own before its controller starts, and refuses. Two
   campaigns launched at the same moment can each find the other's host and
   both back out, so never start two campaigns at once; if they back out,
   start each again, one at a time. Everything after this runs in AWS
   (principle 7).

The campaign's frozen inputs (candidate archive, tables, plan bundles, JDK
archives) are built on the control host and uploaded to each region's bucket
with the SDK's managed multipart transfer, which replaces the single-stream
upload that stalled at 200 KB/s.

### Launch a worker

The per-host wrapper `aws/run-campaign.sh` (1,617 lines, 38 CLI call sites,
one bash process per host) is deleted. The controller launches with boto3:

1. Chooses the session name, bucket and prefixes before launch.
2. **Writes lease 1, and lease 2 when the pool has more than one waiting
   worker's worth of work, before `RunInstances`** (principle 1). A new
   instance has measured nothing, so no replica-diversity or confirmation
   exclusion can apply to it; those rules only constrain later leases, by the
   instance ID the host reports. The boot deadline releases the leases of a
   host that never boots, without spending an attempt. A worker whose first
   leases S3 did not all take is not launched: its launch fails as
   `retryable-infrastructure`, spends none of its slot's replacements, since
   nothing booted, and its pool waits 30 s before it launches again, so an S3
   outage holds launches back without failing the phase.
   Workers admitted in a loop are launched even when a later admission
   fails, so none stays recorded as starting without a launch; one whose
   launch an error kept the controller from asking for has failed to
   launch, spending none of its slot's replacements. An admission whose
   starting record fails releases its reservation, and the phase's fault
   names every error the loop met.
3. Calls `RunInstances` with user data naming the prefixes, the archives and
   the permanent profile, `InstanceInitiatedShutdownBehavior=terminate`, and
   tags the instance with campaign, phase, host epoch and session.

A host is ready in about a minute and finds work waiting. A restart, a slow
loop or a stalled controller costs nothing until a host finishes its lease
and its slack. The 13 s of package installs would need a custom AMI per
region and architecture to remove; at 200 hosts it is about $0.07 and not
worth the AMI state.

### Worker

Hosts keep the CLI (`run-unit-worker.sh`, `run-host.sh`); they poll at a low
rate and setup time is host time.

- **Heartbeats at group boundaries only.** The worker heartbeated every
  30 s through the CLI, a full Python process, on a 2-vCPU host during
  measurement with no CPU pinning. It now uploads a heartbeat when a lease
  starts, when each group completes, and when it stops, retrying a failed one
  as it does any upload. Liveness between
  boundaries comes from the per-region `DescribeInstances`. The Spot-notice
  check stays: one IMDS request, milliseconds, no process start.
- **Self-stop claim.** Before an idle stop the worker writes its own next
  lease `{"stop": true, "by": "worker"}` with `--if-none-match '*'`. If it
  wins, a later controller put of that sequence fails and the controller
  requeues the item without spending an attempt; if the controller won, the
  worker reads and runs the lease. S3's conditional write makes this atomic.
- **Idle stop** stays 600 s; with lease at launch it fires only when the pool
  has nothing queued.
- **Teardown without a controller.** A worker needs no controller to end: it
  stops at its idle stop, its lease stop time, or its 6-hour lifetime, powers
  off, and the instance terminates on shutdown. If the controller and the
  control host die for good, every host finishes the lease it runs and the one
  of slack, waits out its idle stop, and terminates; a host whose groups hang
  powers off at its stop time instead. Worst case for 200 hosts: a host
  admitted just before the controller died, whose two 15-minute units hang,
  lasts its 20-minute boot allowance, both leases at twice their estimate, 10
  idle minutes and 15 minutes of wind-down, about 105 minutes; at the 29th's
  mix (91 r8i.large and 16 r9g.large at $0.10, 76 r9g.2xlarge at $0.28, about
  $32 an hour) that is about $56, and about $21 at the 40 minutes a typical
  lease and slack take. What remains is S3 objects only, swept by the buckets'
  session lifecycle rule after a day or by the next controller.

### The scheduler loop

One loop, target under 30 s, every action a round trip:

1. **Poll.** One `ListObjectsV2` per live session on a thread pool (about
   50 ms each; 200 sessions in under a second). A group's objects are deleted
   from S3 once accepted and retained locally, so a session's prefix holds
   only its heartbeats, the current lease's uploads and its leases, and the
   listing never grows over hours. One `DescribeInstances` per region,
   filtered by the campaign tag, gives every host's state.
2. **Lease.** Every live worker is topped up to one lease of slack before any
   launch. A worker whose session has not reported yet keeps the leases it
   was launched with.
3. **Accept.** Completion markers go to an acceptance pool (4 threads):
   download, verify SHA-256, extract, validate, retain, delete from S3. The
   loop thread applies each finished acceptance to the ledgers, so ledger
   writes stay single-writer and the loop never blocks on a download. Steady
   state at 200 hosts is about one group every few seconds.
4. **Shares.** Each running phase's target = its weight among running phases
   by corrected seconds left, over `max_hosts`, with the per-platform cap
   applied in the same allocation. A pinned share overrides. A phase above
   its target gives up hosts while another phase waits for them, which
   redistributes them, while its share is pinned, or while the hosts held pass
   `max_hosts` or the per-platform cap, as once one is lowered. It stop-leases
   idle workers first and never interrupts a lease.
   While it gives them up it launches no host past its target, so each host
   it gives up goes to the phase waiting for it, by the loop after the host
   ends, rather than back to it. A draining phase gives up the open hosts a
   lowered limit still leaves no room for once the launching phases have
   given up theirs. A finished phase's hosts flow to the others the same
   loop.
5. **Launch.** Admission per pool as today, with one fleet snapshot per loop,
   placement scores at `min(50, target)` cached five minutes, and the boot
   window below. A `RunInstances` round trip is about 1 s, not 2.5 s of
   process start.
6. **Export** metrics and events (below), then sleep to the next 15 s tick.

**Lost and stalled hosts.** A host whose instance is no longer pending or
running is lost at once: its started lease is requeued and its unstarted one
released, as today. A running host whose lease has passed its timeout
(twice its unfinished groups' estimate) since its latest progress (a
heartbeat, a group upload, or its adoption) is stalled: it gets a stop lease
and is terminated, and its lease is requeued. A group upload S3 lists counts
before it is accepted, and a restarted controller's adoption counts only for
a lease that shows progress. The heartbeat ages of 10 and 30 minutes go.

**Boot window.** Hosts launched but not yet reporting are capped
campaign-wide: 10 at first, doubling after each wave whose hosts all
reported within 5 minutes with no boot failure, up to 50; a boot failure or a
late wave halves it (floor 5). A launch that booted nothing is settled in
the loop that made it, so it holds no room from the phases stepped after it.
The window bounds what a bad AMI, region or
user-data change can cost before it shows, which is the remaining reason to
ramp once leases are written at launch.

### Progress and finish

The dashboards are built around one model, exported every loop:

- **Per worker**: the lease and group it runs, when the group started, its
  estimated seconds, and its actual seconds so far. A finished group records
  estimated and actual seconds.
- **Remaining work**: the estimated seconds of groups still queued or in
  flight, per phase and pool.
- **Correction**: the observed ratio of actual to estimated seconds over the
  phase's finished groups, per pool, 1.0 until the first finishes.
- **Finish estimate**: corrected remaining work divided by live hosts of the
  pool, as a time, or never for a pool with work left and no live host,
  exported as the largest finite value since OpenTelemetry drops infinite
  ones; the phase's is its latest pool's. It responds to a
  capacity change on the next loop, so adding a region moves the estimate at
  once. It also weights the shares.

A phase's launches end at its deadline: its timeout after its first start,
moved out by every second its settings record it paused since then. A
settings change that ends a pause adds the time since the phase's `since` in
the settings to the phase's `paused_seconds` there, and a pause still open
counts until now, so a restarted controller reads every pause back, including
a pause and its resume both made while no controller ran. A fault pause counts
from the loop whose settings change records it. Time with no controller and
no pause still spends the deadline. A first start records the seconds the
settings already hold, so a pause before the phase started credits nothing.

The ledgers record estimated seconds with each work item and the actual
seconds in each accepted group's unit session, so `status` reproduces the
same numbers from the ledgers alone.

### Cleanup

Cleanup is the controller's: terminate the instance (or cancel the Spot
request), wait for `terminated`, download any objects still in the prefix
(today's recovered uploads, now a controller function rather than
`verify-recovered-results.py`), delete the prefix, verify it empty. The proof
has two parts, instance and prefix, instead of instance, profile, role,
bucket and wrapper exit. The controller asks again each loop until EC2 lists
the instance shutting down. An instance still not terminated 15 minutes after
the first request, whether or not it succeeded, is a **leak**: spending stop
and alert. That request is recorded before it is made, so a restart keeps its
clock. A prefix that will not empty is a **leftover**: alert, retried each
loop; the host is dead and the controller is the only other writer, so it is
never a halt. A worker whose host has ended takes no further lease while its
cleanup is proven, so the controller writes nothing more into its prefix. The
budget's reservation is released at verified cleanup as today.

### Failure scoping

| Failure | Scope | Action |
|---|---|---|
| Leftover | none | retried, alert while it stands |
| Phase fault: bad marker, changed frozen inputs, conflicting receipts, ledger error, exception in a worker phase's setup or dispatch | phase | paused with reason; others continue; alert; `resume` sets it up again once the completions its paused dispatch was gathering are gathered |
| Exception in a reduction | phase | failed with reason, so the steps after it never start; others continue; alert |
| Leak, account mismatch | campaign | spending stop; alert naming the resource; running leases finish |
| Budget ceiling | campaign | spending stop for launches; alert |
| Controller crash | process | systemd restarts it (`Restart=on-failure`, 30 s, five in 10 minutes then stop); recovery adopts live instances by tag from the ledgers; alert |
| Control host lost | host | `control-host replace` from the Mac launches a new host onto the volume; hosts keep running on their slack meanwhile |
| Controller and control host gone for good | run | workers tear themselves down (above); the next controller or the lifecycle rule sweeps S3 |

The fleet halt, `halt_and_clean_up`, the `halts` in `fleet-state.json`, and
wrapper adoption are gone. SIGTERM is a clean stop of the controller process:
hosts keep running, one log line, exit 0.

### Frozen inputs and live settings

Frozen, in `campaign-options.json` and each phase's `execution-policy.json`
as today minus the removed fields: candidate, tooling digest, protocols,
release version, plan digests, engine-build manifests, phase timeouts,
`spot_vcpu_reserve`.

Live, in `campaign-settings.json` at the campaign root, changed only by the
`control` command under the campaign lock and reloaded each loop:

```json
{
  "version": 7,
  "max_hosts": 200, "max_hosts_per_platform": 100,
  "max_usd": 200, "max_machine_hours": 1600,
  "regions": {
    "us-west-2": {"bucket": "regulator-benchmark-938463256137-us-west-2", "max_vcpus": 640, "on_demand_max_vcpus": 240},
    "us-east-2": {"bucket": "regulator-benchmark-938463256137-us-east-2", "max_vcpus": 200}
  },
  "platform_regions": {"r8i": ["us-west-2", "us-east-2"], "r9g": ["us-east-2", "us-west-2"]},
  "purchase": {"r8i": "spot", "r9g": "spot"},
  "hourly_rates": {"r8i.large": {"spot": 0.08, "total": 0.10, "on_demand_total": 0.16}},
  "phases": {"main/lang/primary": {"state": "running", "share": "auto"}},
  "boot_window": {"initial": 10, "max": 50},
  "authorized": {"max_hosts": 300, "max_usd": 300, "regions": ["us-west-2", "us-east-2", "eu-west-1", "eu-central-1"]}
}
```

`FleetBudget` stays as the durable reservation ledger; its limits read the
settings and its frozen-policy check goes. Each settings change is an event
naming its actor: Dain, the monitor, or the controller (which changes only
phase states, the boot window and the spending stop). `control` takes
`--actor dain|monitor`; without it the command refuses, so no change is
anonymous. Only Dain sets `authorized` and `hourly_rates`: the settings refuse
a change to either by any other actor. The monitor runs only the commands the
authority table grants it: `set-limit`, `add-region` and `resume`. The
settings take a `resume` from the monitor only for a phase the controller
paused for a fault, as the phase's `by` records. With `--actor monitor`,
`control` refuses every other command, `stop`, `clear-spending-stop` and
`authorize` among them.

### Campaign roots of the old driver

There is no migration. A root with a `campaign-driver.txt`, a phase with a
`driver-child.txt`, or any root whose `execution-policy.json` carries the
removed share fields is an old root, as is a root whose `fleet-state.json`
names a phase by its absolute root on the control host rather than its key:
the new controller refuses to run, resume or adopt it, naming the file it
found. Old roots stay readable results: `status`, the reduction, `--results`,
archiving and publication read their ledgers unchanged, because the ledger
layout does not change, and `status` reads such a fleet's owners as the keys
of the roots under the host's campaign root.
`r20-20261029a` is archived as evidence of the failure and the 2.0 run starts
in a new root.

### Commands

`run-campaign.py control <root> --campaign <campaign> <command>` runs on the
control host; from the Mac it is the same command over ssh on port 443
through the subnet router. `--campaign <campaign>` is required, and refused
unless the root's controller recorded that campaign in
`campaign-options.json` as it started, so a command meant for one campaign
never steers another's root. Each takes the lock and validates. One that
changes the settings, and every reopen, bumps the version and logs the event
with its actor; one that changes nothing says the settings already hold it.
The new version takes effect only once its event is logged.

- `add-region <region> --max-vcpus N [--on-demand-max-vcpus N] [--platforms ...]`:
  creates the region's bucket if absent with the same lifecycle rule, uploads
  the campaign's shared archives (multipart), then adds the region, so the
  controller never sees a region without archives. It refuses, before creating
  anything, a platform `platform-regions.tsv` lists no image for in the region.
- `set-limit max-hosts|max-hosts-per-platform|max-usd|max-machine-hours N`,
  `set-limit region <r> max-vcpus N`
- `pause <phase> [--reason]`, `resume <phase>`, `drain <phase>`
- `set-share <phase> N|auto` (how hosts move between phases)
- `set-purchase <platform> spot|spot-then-on-demand|on-demand`
- `clear-spending-stop`, `stop`, `reopen` (an ended campaign)
- From the Mac: `control-host replace`, `finish`, `fetch`, `teardown` (below).

A raised or new region vCPU limit is checked against the region's Standard
quota after its reserve, the check each phase's setup makes, and refused when
the quota cannot hold it, so no phase starts under a limit it would refuse.

### Telemetry: one health path

The controller is the only exporter, over OTLP to the `otel-lgtm` container
on the same host. Workers get no new software or endpoint; the controller
turns their S3 evidence into:

- **Metrics** (Prometheus): loop seconds; the progress model above; per phase:
  workers by state, queued, accepted units and groups, unresolved, slack per
  worker (minimum and count at zero), boot-to-ready, idle stops (with and
  without queued work), interruptions, stalled hosts, boot window, launch
  refusals by reason, spend and spend rate; per region: vCPUs by market,
  pending launches; `spending_stop`, phase state, settings version. A
  refusal, worker state or phase state that has cleared reads 0, so a
  panel's current value never holds a stale one; a pool with no room for
  another host, or no need of one, is not launching, so it holds no refusal,
  nor does a phase paused for a fault. A refusal waiting only on the boot window keeps its
  age, and its reason is one of a fixed few, a failed capacity query's
  error going only to the job attempt.
- **Logs** (Loki): structured events: phase starts and completions, settings
  changes with their actor, pauses, leaks, leftovers, restarts, milestones
  (first host reporting, replica 1 accepted, phase complete), with the local
  time in the line.
- **Traces** (Tempo): one span per unit attempt, children leased, started,
  uploaded, accepted, from the lease ledger and the host's timestamps,
  emitted when the attempt settles.

Dashboards are provisioned from JSON checked in under
`tools/re2-benchmark/aws/control-host/grafana/`: a fleet view (who is doing
what, where the campaign stands, what is left, when it finishes at current
capacity), a health view, and a spend view. The health thresholds (loop over
30 s, slack at zero on any worker, idle stop with work queued, boot window
shrunk, refusal over 10 minutes, finish past deadline, projected spend over
`max_usd`, spending stop, phase paused, leftover standing) are panel
thresholds there and the monitor's runbook queries name those panels. No
Grafana alerting.

What goes: `dispatch-status.json`. The ledgers remain the source of truth for
recovery and for `status`, which keeps reading them (units, groups, spend,
estimate) and prints the Grafana URL for everything live. `status` over ssh
is the fallback while the collector is down. One live path, one durable path.

Telemetry never blocks the run: export is asynchronous and a dead collector
drops data. systemd restarts the container.

### The monitor

A monitor thread per run on Dain's Mac, driven by a T3 scheduled task every
15 minutes bound to that thread, so it fires whether or not any notification
arrives. Each check queries Grafana's HTTP API through the subnet router with a
service-account token read once over ssh, runs the runbook's queries, posts
a short update at milestones, and sends a push notification for anything it
cannot fix. It changes nothing on the control host except through `control
--actor monitor`. If the scheduled task stops firing nobody notices until
Dain looks; that is accepted because the controller enforces the ceilings and
the spending stop.

| The monitor may, unattended | The monitor must ask |
|---|---|
| `control-host replace` once, when the controller or host is dead and systemd has given up after crashes, or never got its unit | Clear a spending stop; a second replacement; a controller that refused to start (status 3) |
| Resume a phase the controller paused for a cause since cleared, once per cause | Resume a phase paused twice for one cause, or one Dain paused or drained |
| Raise `max_hosts`, `max_hosts_per_platform`, a region's vCPUs or `max_usd` within `authorized` while refusals or a late finish stand | Anything outside `authorized`; the monitor never edits `authorized` |
| `add-region` from `authorized.regions` when refusals name capacity | Stop the campaign |
| Report | Result anomalies: unresolved units, semantic rejections; anything the runbook does not name |

The runbook is an agent instruction file written with `writing-for-agents`.

### Finish and teardown

The control host outlives the campaign. After every chain completes, after a
campaign stop, and after a failure, the controller writes its marker and the
host keeps serving Grafana so Dain can see what happened; idle it costs a few
dollars a day. A controller refuses to start on a root with the marker, so a
reboot or a replaced host never runs an ended campaign again, and `control`
refuses every command on it but Dain's `control ... reopen`, which records
its event and then clears the marker under the settings lock, so the root
stays as the manifest lists it. A controller's refusal, of an
ended root or any other a restart would repeat, exits 3, which systemd does
not restart.

Raw benchmark output is rarely read, and a root runs to tens of gigabytes
(r20's was 70 GB in 830k files), so it lives in S3 and only the summaries come
to the Mac. `run-campaign.py finish <campaign>` from the Mac first has the
control host archive its root, over ssh on port 443: the host's
`run-campaign.py archive-root <campaign>`, which only finish runs, checks the
root against its manifest before writing a new archive, refusing one that
differs, and streams `tar` through `zstd` into the private archive bucket
`regulator-benchmark-archive-938463256137-us-west-2` in Glacier Instant
Retrieval, so the bulk never leaves the region. The bucket is not a campaign
bucket, so no campaign bucket's expiry reaches it. The archive's key,
`campaigns/<campaign>/root-<manifest SHA-256>.tar.zst`, is written once and
never overwritten, with metadata naming the manifest's SHA-256 and file count;
S3 takes metadata before the bytes, so the manifest written beside it once the
archive is complete, `root-<manifest SHA-256>.sha256`, records the archive's
SHA-256 and size. A finish run again reuses the archive, and a reopened
campaign that ends again writes a new manifest and so a new archive. Finish
refuses an archive bucket with an enabled lifecycle rule that would expire
this archive or move it out of Glacier Instant Retrieval, confirms the
archive's metadata and storage class from the Mac, and then pulls with rsync
every file but the raw trees: the JDKs, each candidate's archive, and each
phase's units, unit results, jobs and language plan bundles. It checks every
pulled file and every summary the manifest lists against the manifest, and
records the archive in the local root's `raw-archive.tsv`. A first finish
writes the manifest before it pulls, so it may run again if it fails; a later
finish replaces the local manifest and record only once the summaries match
the manifest the host still holds, so one that fails, as during a reopen,
keeps the earlier finish's. A campaign whose host tooling predates
`archive-root` is finished with the tooling it launched with. It terminates
nothing.

`run-campaign.py fetch <campaign>` restores the raw root from the archive
beside the summaries, checking the archive's SHA-256, unpacking only
directories, regular files and hard links inside the root, refusing an archive
whose manifest differs from the recorded one, and checking the whole root
against the manifest. `status --results` and reducing a phase again read the
raw units, so they need it first.

`run-campaign.py teardown <campaign> --confirm` first tags the campaign's
volume as torn down, then terminates every host of the campaign, again until
none runs, so a host a concurrent `replace` launched goes too. It then
deregisters them from the load balancer and waits until the load balancer has
drained and forgotten them, snapshots the volume, and deletes the volume and
the Grafana parameters; the record and the load balancer stay. The hosts go
first so the snapshot holds the root as the controller last wrote it. Only
Dain runs it, and it refuses without `--confirm`. The snapshot is the last copy
on EBS until Dain deletes it; the archive, if finish ran, is the other copy,
which teardown leaves alone. Each step finds what an earlier teardown did by the
campaign's tags, so a teardown that failed partway finishes when run again,
with no second snapshot. Once the volume carries the tag, `control-host
replace` refuses, and a replace that checked the volume before the tag looks
again once the volume is attached, and terminates its host before its
controller starts. A host that attaches as the snapshot is taken may still
write its boot files, such as the checkout, which the next host writes again.
Only a replace that stalls for minutes between the attach and that look, as
on a Mac that sleeps, lets its controller start and write after the snapshot.
Never run teardown and replace at the same time. The tag lives in AWS, so the
two commands need not run on the same machine.

## Lower stakes before launch

**Simulated fleet** (principle 4). `FakeUnitFleet` gains a simulated clock
where every SDK call, launch and acceptance has a cost, hosts with a boot
delay distribution, a 600 s idle stop, prefix cleanup, and the self-stop
claim; boto3 is behind one small client interface the fake implements. The
old loop fails the burst on it. Scenarios:

1. A 200-host burst: every host finds a lease at boot; no worker ever reaches
   zero slack; loop time stays under the threshold; the window grows 10, 20,
   40, 50.
2. Steady state: 200 hosts for six simulated hours of 15-minute units; slack
   never reaches zero, acceptance keeps up, the listing stays bounded, the
   finish estimate converges on the actual finish.
3. A controller outage of 10 minutes mid-run: hosts keep working on their
   slack, nothing is leased twice after recovery, nothing idle-stops.
4. The self-stop race: a controller put after a worker's claim loses, the
   item is requeued with no attempt spent.
5. A leftover prefix is retried and never pauses anything; an instance that
   will not terminate causes a spending stop that leaves other phases' leases
   running.
6. A settings change mid-run: a new region and a raised limit are used next
   loop and the finish estimate moves; a pinned share drains idle workers
   first; a finished phase's hosts flow to the running one.
7. A late boot wave halves the window; a healthy wave doubles it.
8. The controller is gone for good: every host ends by idle stop, stop time
   or lifetime and terminates; the spend matches the worst case above.

**Full-scale AWS rehearsal.** A smoke-protocol campaign of 180 or more hosts
across us-west-2 and us-east-2 from a real control host, with the monitor
thread running its schedule against it: it performs the `add-region` itself,
sees a deliberate controller kill and its systemd restart, and its updates
reach Dain's thread. Budget: $25 including the control host.

## Dependencies

boto3 and the OpenTelemetry SDK, pinned in
`tools/re2-benchmark/requirements.txt`, are the tooling's first Python
dependencies. The Mac needs boto3 for the launch, replace, finish, fetch and
teardown commands; the tests stub the client interface, so
`validate-tooling.py` still needs no credentials but does need the packages
installed. CI installs them.

## Build plan

Three stages in one branch and one pull request, the commits grouped by
stage, each commit building and passing its tests. Estimates are build days
including both reviews.

**A. Safety and speed** (3 to 4 days). boto3 client interface; the wrapper
folded into the controller with cleanup as two proofs; lease at launch and
slack; heartbeats at group boundaries, lost and stalled hosts from instance
state; concurrent polling, acceptance pool, cached fleet snapshot, placement
target; self-stop claim and the leftover class; boot window; the simulated
fleet with scenarios 1 to 5, 7 and 8. Deletes `aws/run-campaign.sh`,
`verify-recovered-results.py`, `test_recovered_results.py`, wrapper adoption.

**B. One process, live settings** (2 days). Phases in one process; shares
and the progress model; pause, drain, stop, spending stop;
`campaign-settings.json` and `control` with actors and `add-region`;
scenario 6. Deletes the per-phase child paths, the fleet halt and frozen
shares.

**C. Control host and telemetry** (2 to 3 days). Account setup command or
steps; launch, replace, finish and teardown; user data, systemd unit, volume,
private network access and DNS record, `otel-lgtm`; OTel export; dashboards; `status` reads ledgers only;
the monitor runbook.

Then the rehearsal (1 day plus fixes), adversarial review, polish review, PR:
about 9 to 11 build days. The 2.0 run waits for all three.

## Decisions

Settled with Dain on 2026-10-30:

1. One controller process per campaign, on an AWS control host under systemd.
2. Monitor authority as in the table; the monitor never edits `authorized`.
3. The boot window bounds hosts not yet reporting.
4. Idle stop stays 600 s.
5. The rehearsal is a monitor rehearsal of 180 or more hosts, $25 cap.
6. One Opus build thread for all three stages.
7. A permanent worker role and instance profile, created once in the dev
   account with the `dev` profile, giving up per-host prefix isolation.
8. No local health files; `status` over ssh on the ledgers is the fallback.
9. Accepted group objects are deleted from S3 once retained.
10. Control host: m7i.large in us-west-2 in a private subnet the company's
    Tailscale subnet router carries, reached on its one forwarded port, 443:
    Grafana through an internal load balancer the private DNS record aliases,
    and ssh at the host's private address, with no Tailscale or key on the
    host; network identifiers only in the SSM
    parameter `/regulator-benchmark/control-host-network`; 200 GB gp3 volume,
    public repository clone at the candidate commit, files under
    `tools/re2-benchmark/aws/control-host/`.
11. All three stages before the 2.0 run, in one pull request.
12. No migration of old campaign roots.
13. Heartbeats at group boundaries only; liveness from instance state.
14. Workers tear down without a controller; the abandoned-run cost is bounded
    as above and leaves S3 objects only.
15. The control host outlives the campaign; `finish` archives the raw root to
    S3 and pulls the summaries, `fetch` restores the raw root, and
    `teardown`, run only by Dain, removes the host and volume.
