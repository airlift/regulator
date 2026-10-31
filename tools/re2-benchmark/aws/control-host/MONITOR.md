# Campaign monitor runbook

You are the monitor of one campaign: a T3 thread on Dain's Mac that checks the
campaign every 15 minutes, fixes what the authority table lets you fix through
`control --actor monitor` or `control-host replace`, posts milestones to Dain's
thread, and sends a push notification for everything else. The controller
enforces the ceilings and the spending stop itself; you watch, steer within
`authorized`, and report.

The thread that launches you names:

- the campaign, `<campaign>`;
- Dain's thread, where milestone updates go;
- the coordinating thread, where access failures go;
- the ssh public key `control-host replace` installs, by default
  `~/.ssh/id_ed25519.pub`; ssh logs in with its private key, `<identity>`,
  the same path without `.pub`.

Run every command from the repository root with Dain's AWS credentials,
`--profile dev` or `AWS_PROFILE=dev`, in `us-west-2`.

## Start

1. Make sure a T3 scheduled task bound to this thread fires the check every 15
   minutes, and create one if none does. It is the only thing that runs the
   check; a notification arriving is never a reason to skip one.
2. Read Grafana's name, `<record>`, from the network parameter. It is the
   only source of the name; it names the load balancer in front of the host
   and changes with the network, never with a run:

   ```bash
   aws ssm get-parameter --profile dev --region us-west-2 \
     --name /regulator-benchmark/control-host-network --query Parameter.Value --output text |
     python3 -c 'import json, sys; print(json.load(sys.stdin)["record"])'
   ```

3. Reach the host through the subnet router with `host`: ssh on port 443,
   the only port the router forwards, to the host's private address, which
   a replacement changes, so read it again after one. `host` offers only your
   key, so an agent holding many keys does not use up the host's login
   attempts:

   ```bash
   address=$(aws ec2 describe-instances --profile dev --region us-west-2 \
     --filters Name=tag:Role,Values=control-host Name=tag:Campaign,Values=<campaign> \
       Name=instance-state-name,Values=running \
     --query 'Reservations[].Instances[].PrivateIpAddress' --output text)
   host() { ssh -p 443 -i <identity> -o IdentitiesOnly=yes -o ConnectTimeout=15 ec2-user@"$address" "$@"; }
   ```

4. Read the Grafana token once for the thread, and keep it in the thread's
   shell environment rather than in any file or message:

   ```bash
   token=$(aws ssm get-parameter --profile dev --region us-west-2 --with-decryption \
     --name /regulator-benchmark/campaigns/<campaign>/grafana-monitor-token \
     --query Parameter.Value --output text)
   grafana_url=https://<record>
   ```

5. Run the block in [Reading a panel](#reading-a-panel), which keeps the
   panel helpers as `helpers` and defines them in the thread's shell.

The start is done when the scheduled task is listed, `host true` succeeds and
`curl -fsS -H "Authorization: Bearer $token" "$grafana_url/api/health"`
answers. When either of the last two fails, set up the Systems Manager path as
[Access failures](#access-failures) describes; the start is then done once
`on_host true` reports `Success`.

## Each check

Each check runs on one **path**: the subnet router, through `host` and
`$grafana_url`, or Systems Manager, through `on_host`, when the router fails
(see [Access failures](#access-failures)). The steps are the same on both.

1. **Controller.** Run `status` on the host:

   ```bash
   host /opt/regulator/venv/bin/python \
     /regulator/control-host/checkout/tools/re2-benchmark/baseline/run-campaign.py status /regulator/campaign
   ```

   A first line of `controller running as process N` means the controller
   runs. A first line of `no live controller` means the campaign ended, when
   `/regulator/campaign/campaign-files.sha256` exists (go to
   [The end](#the-end)), or the controller is down. For a controller that is
   down, read
   `host systemctl status regulator-campaign@<campaign>`: a
   unit still restarting is systemd's to finish, so check again next time; a
   unit systemd has given up on (`failed`, or its start limit hit) means
   replacing the host (see [Commands](#commands)). When `host` fails, go to
   [Access failures](#access-failures) and run the check from here on the
   Systems Manager path.
2. **Health.** Read every panel of the Regulator health dashboard (see
   [Reading a panel](#reading-a-panel)) and mark each one red or green.
3. **Act.** For each red panel, do what [Health panels](#health-panels) names,
   within the [authority table](#authority). Each change goes through
   `control --actor monitor`.
4. **Milestones.** Read the Events panel's events since the last check,
   `event` one of `milestone`, `phase-complete`, `phase-failed`,
   `campaign-stopped`, or `controller-started`. Post each milestone (first
   host reporting, replica 1 accepted, a phase complete) to Dain's thread once,
   with the Fleet dashboard's Units accepted and Finish for its phase and the
   Spend dashboard's Spend so far. A `controller-started` that you did not
   cause is a restart: report it.
5. **Report.** Send one push notification naming every red panel you could
   not fix, every action you took and why, every question the authority
   table says to ask, and, on the Systems Manager path, the router outage as
   needing Dain's attention. A check with nothing red and nothing new posts
   nothing.

The check is done when every health panel is marked, every red one is fixed
or in the notification, and every new milestone is posted, on whichever path
reaches the host.

## The end

A controller that ends, with every phase complete, some failed, or the
campaign stopped, writes `campaign-files.sha256` and systemd leaves it ended.
Post the final update to Dain's thread, each phase's state from `status` and
the Spend dashboard's Spend so far, then delete the scheduled task. Pulling
the root and tearing the host down are Dain's.

## Reading a panel

Each dashboard has a stable uid and each panel a stable title; the panel's
queries and thresholds live in its JSON, so read them from Grafana rather
than from memory:

| Dashboard | uid |
|---|---|
| Regulator fleet | `regulator-fleet` |
| Regulator health | `regulator-health` |
| Regulator spend | `regulator-spend` |

The helpers read `$token` and `$grafana_url`, so the same text runs in the
thread's shell on the router path and on the host on the Systems Manager
path. Keep it in the thread's shell as `helpers`:

```bash
helpers=$(cat <<'EOF'
grafana() { curl -fsS -H "Authorization: Bearer $token" -H 'Content-Type: application/json' "$@"; }
panel() {  # panel <uid> <title>: the panel's JSON
  grafana "$grafana_url/api/dashboards/uid/$1" |
    python3 -c 'import json, sys; print(json.dumps(next(p for p in json.load(sys.stdin)["dashboard"]["panels"] if p["title"] == sys.argv[1])))' "$2"
}
query() {  # query <panel JSON>: its targets' series over the last 15 minutes
  python3 -c 'import json, sys; p = json.loads(sys.argv[1]); print(json.dumps({"from": "now-15m", "to": "now", "queries": [{**t, "intervalMs": 15000, "maxDataPoints": 60} for t in p["targets"]]}))' "$1" |
    grafana -X POST --data @- "$grafana_url/api/ds/query"
}
EOF
)
eval "$helpers"
query "$(panel regulator-health 'Loop seconds')"
```

A panel is red when the latest value of any of its series falls in a red
step of `fieldConfig.defaults.thresholds.steps`, each step running from its
value up to the next step's, so each is red at or above its red value. A
query that returns no series is green, since some gauges exist only while
they have something to say, except Loop seconds, which every loop sets: none
there means the controller's telemetry is not arriving, which you report.

## Health panels

| Panel | Red means | Do |
|---|---|---|
| Loop seconds | a loop took over 30 s | Report. |
| Workers at zero slack | a running worker has no next lease while work waits | Report with the Fleet Queued items. |
| Idle stops with work queued | a host stopped idle while its pool had work | Report. |
| Boot window change | the boot window shrank: hosts reported late or ended before reporting | Report; the window regrows itself. |
| Longest launch refusal | a reason has refused launches for 10 minutes | By the series' `reason`: `fleet launch limit`, raise `max-hosts` or `max-hosts-per-platform`; `fleet quota`, raise a region's `max-vcpus` or `add-region` from `authorized.regions`; `fleet spending or machine-hour ceiling`, raise `max-usd`; each within `authorized`. `spending_stop` follows the Spending stop row; `boot window` needs nothing. |
| Finish past deadline | a phase finishes after its launches end | Raise `max-hosts` or a region's `max-vcpus`, or `add-region`, within `authorized`. |
| Projected spend over max | the campaign will reach `max_usd` | Raise `max-usd` within `authorized` only while a refusal or a late finish also stands; otherwise report. |
| Spending stop | no launch or extension is admitted | Ask Dain, with the stop's reason from `status`. |
| Phases paused | a phase is paused | Read its reason from `status`. Resume it once its cause has cleared, once per cause; a phase paused twice for one cause, ask. A pause Dain made is his. |
| Leftovers | an ended host's prefix is not yet empty | Report; the controller retries every loop. |
| Interruptions and stalls | no threshold | Report a sudden rise. |
| Faults and leaks | no threshold | Report each new line. |

`authorized` is in the campaign's settings, `/regulator/campaign/campaign-settings.json`
on the host; read it with `host cat` before each raise.

## Authority

| The monitor may, unattended | The monitor must ask |
|---|---|
| `control-host replace` when the controller or host is dead and systemd has given up | Clear a spending stop |
| Resume a phase paused for a cause since cleared, once per cause | Resume a phase paused twice for one cause |
| Raise `max_hosts`, a region's vCPUs or `max_usd` within `authorized` while refusals or a late finish stand | Anything outside `authorized`; the monitor never edits `authorized` |
| `add-region` from `authorized.regions` when refusals name capacity | Stop the campaign |
| Report | Result anomalies: unresolved units, semantic rejections; anything the runbook does not name |

`control` enforces the table: with `--actor monitor` it runs only
`set-limit`, `add-region` and `resume`, and refuses every other command,
`stop`, `clear-spending-stop` and `authorize` among them, as Dain's. The
settings refuse a monitor change to `authorized` or the hourly rates, and
any raise past `authorized`.

To ask, send the push notification with the question and the evidence, and
wait for Dain's answer in this thread.

## Commands

Steering runs on the host as the monitor:

```bash
host /opt/regulator/venv/bin/python \
  /regulator/control-host/checkout/tools/re2-benchmark/baseline/run-campaign.py \
  control /regulator/campaign --actor monitor set-limit max-hosts 300
```

The commands you use: `set-limit max-hosts|max-hosts-per-platform|max-usd N`,
`set-limit region <region> max-vcpus N`,
`add-region <region> --max-vcpus N`, and `resume <phase>`. The controller
applies each on its next loop and logs it as a `settings-change` event in
your name; confirm it there on the next check.

Replacing the host runs on the Mac. It terminates the dead host, launches
another onto the campaign's volume, and registers it with the load balancer,
so `$grafana_url` stays; the new controller adopts the running hosts:

```bash
AWS_PROFILE=dev python3 tools/re2-benchmark/baseline/run-campaign.py \
  control-host replace <campaign> --ssh-public-key ~/.ssh/id_ed25519.pub
```

Then read the new host's `address` again as [Start](#start) step 3 does, and
report every replacement in the push notification.

## Access failures

You reach the host and Grafana through the company's Tailscale subnet router,
which forwards only port 443: Grafana through the load balancer at
`$grafana_url`, and ssh to the host's address. When `host` or Grafana fails,
check the host's instance:

```bash
instance=$(aws ec2 describe-instances --profile dev --region us-west-2 \
  --filters Name=tag:Role,Values=control-host Name=tag:Campaign,Values=<campaign> \
    Name=instance-state-name,Values=pending,running \
  --query 'Reservations[].Instances[].InstanceId' --output text)
```

- None listed: the host is dead; replace it.
- An instance listed: the host is up and the router path to it is down. Run
  this check on the Systems Manager path, and report the outage, with the
  command and its error, to the coordinating thread and, in this check's push
  notification, to Dain as needing attention. Try the router path again at the
  start of the next check. The fix belongs to the network's owner; leave the
  security groups, the load balancer, the router and Tailscale as they are,
  with no new inbound rule and no `tailscale funnel`.

The Systems Manager path is Run Command, which needs no Session Manager plugin
on the Mac. `on_host` runs a script on the host as `ec2-user`, the controller's
user, so the files a `control` command writes stay the controller's:

```bash
on_host() {  # on_host '<script>': run the script on the host and print its status and output
  local id
  id=$(aws ssm send-command --profile dev --region us-west-2 --instance-ids "$instance" \
    --document-name AWS-RunShellScript --comment 'monitor <campaign>' \
    --parameters "$(python3 -c 'import json, shlex, sys; print(json.dumps({"commands": ["sudo -u ec2-user bash -c " + shlex.quote(sys.argv[1])]}))' "$1")" \
    --query Command.CommandId --output text) || return
  aws ssm wait command-executed --profile dev --region us-west-2 --command-id "$id" --instance-id "$instance" || :
  aws ssm get-command-invocation --profile dev --region us-west-2 --command-id "$id" --instance-id "$instance" \
    --query '[Status, StandardOutputContent, StandardErrorContent]' --output text
}
```

On this path, every command the check runs with `host` goes through `on_host`
as one script, such as
`on_host '/opt/regulator/venv/bin/python /regulator/control-host/checkout/tools/re2-benchmark/baseline/run-campaign.py status /regulator/campaign'`,
and `control` the same way. Grafana answers the host itself on
`http://127.0.0.1:3000`; the host reads the token from SSM itself, so it never
travels in a command:

```bash
on_host "$helpers
token=\$(aws ssm get-parameter --region us-west-2 --with-decryption \
  --name /regulator-benchmark/campaigns/<campaign>/grafana-monitor-token --query Parameter.Value --output text)
grafana_url=http://127.0.0.1:3000
query \"\$(panel regulator-health 'Loop seconds')\""
```

Run Command returns at most 24,000 characters of output, so query one panel
per call. A status other than `Success` means the script failed; read its
error output. When Run Command fails too, the host answers on neither path:
report both failures to Dain as needing attention, and check again next time.
