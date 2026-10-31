import contextlib
import hashlib
import io
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import cloud  # noqa: E402
import control_host  # noqa: E402
import telemetry  # noqa: E402
from cloud import CloudError  # noqa: E402
from test_cloud import FakeCloud  # noqa: E402

# A network parameter of made-up identifiers, the CIDR from the documentation range.
NETWORK = {"vpc": "vpc-router", "subnets": ["subnet-b", "subnet-a"], "ingress_cidr": "192.0.2.0/24",
           "zone": "Z-test", "record": "control.test", "security_group": "sg-control",
           "load_balancer_security_group": "sg-balancer", "target_group": "arn:target-group",
           "load_balancer": "arn:balancer", "listener": "arn:listener", "certificate": "arn:certificate"}
SSH_PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAItest dain@mac"
# Two ssh host keys, as ssh-keyscan prints them, and the fingerprint ssh-keygen -l prints for each.
HOST_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDFBF9zl104NO16Oi8hhaurUGHGUSidcNN7l8xRWsjQq"
HOST_KEY_FINGERPRINT = "SHA256:8Ry9HHJV3IjVlw/07MBlyfNNpAULlvCo3jytFqWhB+w"
OTHER_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBqG8Ok8C3tNff9ph7g2xiVIS9qh1c0UCbB3xqPOwXlf"
OTHER_KEY_FINGERPRINT = "SHA256:OaSJXQgSzkGqJaLM1DCBBlHrbYlP1Q0ipS8tJ1gSYWg"
CONTROL_HOST = Path(control_host.__file__).parents[1] / "aws" / "control-host"


def control_host_cloud():
    """An account set up as aws/README.md "Account setup" describes."""
    aws = FakeCloud()
    aws.parameters[control_host.NETWORK_PARAMETER] = json.dumps(NETWORK)
    aws.subnets.update({"subnet-a": "us-west-2a", "subnet-b": "us-west-2b"})
    aws.security_groups["vpc-router", control_host.SECURITY_GROUP] = (
        "sg-control", {("tcp", 443, 443, NETWORK["ingress_cidr"]), ("tcp", 3000, 3000, "sg-balancer")})
    return aws


def user_data_settings(user_data):
    """The settings a control host's user data exports, by name."""
    body = control_host.USER_DATA.read_text().split("\n", 1)[1]
    exports = shlex.split(user_data[user_data.index("\n") + 1:len(user_data) - len(body)])
    return dict(token.split("=", 1) for token in exports if token != "export")


class TestControlHost(unittest.TestCase):

    def test_host_is_launched_onto_its_campaign_volume_and_replaced_onto_the_same_one(self):
        aws = control_host_cloud()
        with tempfile.TemporaryDirectory() as temporary:
            inputs = Path(temporary) / "launch-inputs.tar.gz"
            inputs.write_bytes(b"inputs")
            sha256 = hashlib.sha256(b"inputs").hexdigest()
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                control_host.launch(aws, "c", SSH_PUBLIC_KEY, inputs)
            # The launch inputs travel through the campaign's tree of the Oregon bucket, and the volume names them,
            # in the zone of the network's first subnet.
            uri = "s3://regulator-benchmark-123456789012-us-west-2/campaigns/c/control-host/launch-inputs.tar.gz"
            bucket, key = uri.removeprefix("s3://").split("/", 1)
            self.assertEqual((b"inputs", sha256), (aws.objects[bucket, key][0], aws.objects[bucket, key][2]))
            [volume] = aws.region_volumes["us-west-2"]
            self.assertEqual(("us-west-2b", {**control_host.tags("c"), "LaunchInputs": uri,
                                             "LaunchInputsSha256": sha256}), (volume.zone, volume.tags))
            # The host runs in that subnet under its own profile and group, with the volume attached, and the load
            # balancer forwards Grafana to it.
            [(first, launched)] = aws.control_hosts.items()
            self.assertEqual({"image": control_host.IMAGE, "subnet": "subnet-b", "security_group": "sg-control",
                              "profile": control_host.PROFILE},
                             {name: value for name, value in launched.items() if name != "user_data"})
            self.assertEqual({"CAMPAIGN": "c", "CAMPAIGN_VOLUME": volume.id, "SSH_PUBLIC_KEY": SSH_PUBLIC_KEY,
                              "LAUNCH_INPUTS_URI": uri, "LAUNCH_INPUTS_SHA256": sha256,
                              "CONTROL_HOST_REGION": "us-west-2", "GRAFANA_URL": "https://control.test"},
                             user_data_settings(launched["user_data"]))
            self.assertEqual({volume.id: first}, aws.attachments)
            self.assertEqual({"arn:target-group": {(first, 3000)}}, aws.targets)
            self.assertIn("Grafana: https://control.test", printed.getvalue())
            self.assertIn("ssh, once control-host known-host c records its keys: ssh -p 443 "
                          "-o StrictHostKeyChecking=yes", printed.getvalue())
            # The volume holds the campaign root, so the campaign launches once.
            with self.assertRaisesRegex(SystemExit, "already has a control host volume"):
                control_host.launch(aws, "c", SSH_PUBLIC_KEY, inputs)
            # Grafana's one name serves one campaign, so another campaign launches nothing while its host serves.
            with self.assertRaisesRegex(SystemExit, f"forwards Grafana to {first} of campaign c; "):
                control_host.launch(aws, "d", SSH_PUBLIC_KEY, inputs)
            self.assertEqual([volume], aws.region_volumes["us-west-2"])

        # A replacement takes the old host's place on the volume, in the volume's zone whatever order the subnets
        # are listed in, and in the target group.
        aws.parameters[control_host.NETWORK_PARAMETER] = json.dumps({**NETWORK, "subnets": ["subnet-a", "subnet-b"]})
        with contextlib.redirect_stdout(io.StringIO()):
            control_host.replace(aws, "c", SSH_PUBLIC_KEY)
        [old, new] = aws.region_instances["us-west-2"]
        self.assertEqual((first, "terminated", "running"), (old.id, old.state, new.state))
        self.assertEqual("subnet-b", aws.control_hosts[new.id]["subnet"])
        self.assertEqual({volume.id: new.id}, aws.attachments)
        self.assertEqual({"arn:target-group": {(new.id, 3000)}}, aws.targets)

        # A host that ended on its own is replaced once it releases the volume, and leaves the target group.
        aws.terminate("us-west-2", [new.id])
        with contextlib.redirect_stdout(io.StringIO()):
            control_host.replace(aws, "c", SSH_PUBLIC_KEY)
        self.assertEqual(["terminated", "terminated", "running"],
                         [host.state for host in aws.region_instances["us-west-2"]])
        self.assertEqual({"arn:target-group": {(aws.region_instances["us-west-2"][2].id, 3000)}}, aws.targets)
        # A replacement the volume cannot attach to is terminated, leaving the volume to the next.
        with patch.object(aws, "attach_volume", side_effect=CloudError("IncorrectState: busy")), \
                contextlib.redirect_stdout(io.StringIO()), \
                self.assertRaisesRegex(SystemExit, "cannot attach campaign c's volume vol-1 to its new control host"):
            control_host.replace(aws, "c", SSH_PUBLIC_KEY)
        self.assertEqual(["terminated"] * 4, [host.state for host in aws.region_instances["us-west-2"]])
        self.assertEqual(["available"], [volume.state for volume in aws.region_volumes["us-west-2"]])

    def test_teardown_keeps_only_a_snapshot_of_the_campaign_volume(self):
        aws = control_host_cloud()
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            inputs = Path(temporary) / "launch-inputs.tar.gz"
            inputs.write_bytes(b"inputs")
            control_host.launch(aws, "c", SSH_PUBLIC_KEY, inputs)
            aws.parameters.update(dict.fromkeys(control_host.grafana_parameters("c"), "secret"))
            [volume] = aws.region_volumes["us-west-2"]
            # The host ended on its own, so only its registration is left of it.
            aws.terminate("us-west-2", [aws.region_instances["us-west-2"][0].id])
            # Only Dain tears a host down, and says so.
            calls = len(aws.calls)
            with self.assertRaisesRegex(SystemExit, "only Dain runs it, with --confirm"):
                control_host.teardown(aws, "c", confirm=False)
            self.assertEqual(calls, len(aws.calls))
            # A teardown interrupted while its snapshot completes, and again as it deletes the credentials, finishes
            # when run again, taking no second snapshot.
            wait = aws.wait

            def snapshot_never_completes(region, condition, resource):
                if condition == "snapshot_completed":
                    raise CloudError("Waiter SnapshotCompleted failed: Max attempts exceeded")
                wait(region, condition, resource)

            for step, failure in (("wait", snapshot_never_completes),
                                  ("delete_parameters", CloudError("ThrottlingException: Rate exceeded"))):
                with self.subTest(step=step), patch.object(aws, step, side_effect=failure), \
                        self.assertRaises(CloudError):
                    control_host.teardown(aws, "c", confirm=True)
                if step == "wait":
                    # Its snapshot is the root's last copy, so no host is replaced onto the volume to write it again.
                    with self.assertRaisesRegex(SystemExit, "finish the teardown"):
                        control_host.replace(aws, "c", SSH_PUBLIC_KEY)
            control_host.teardown(aws, "c", confirm=True)
        self.assertEqual("terminated", aws.region_instances["us-west-2"][0].state)
        self.assertEqual([cloud.Snapshot("snap-1", volume.id, "completed", control_host.tags("c"))],
                         aws.region_snapshots["us-west-2"])
        # The load balancer has forgotten every host of the campaign, and the account setup's own resources stay.
        self.assertEqual(([], []), (aws.registered_targets("us-west-2", "arn:target-group"),
                                    aws.region_volumes["us-west-2"]))
        self.assertEqual([control_host.NETWORK_PARAMETER], list(aws.parameters))

    def test_network_and_security_group_are_checked_before_anything_is_created(self):
        cidr = NETWORK["ingress_cidr"]
        for change, refusal in (
                (lambda aws: aws.parameters.clear(), "has no /regulator-benchmark/control-host-network parameter"),
                (lambda aws: aws.parameters.update({control_host.NETWORK_PARAMETER: json.dumps(
                    {**NETWORK, "subnets": []})}), "is not a JSON object of vpc, subnets"),
                (lambda aws: aws.security_groups.clear(), "no regulator-benchmark-control-host security group"),
                # A group admitting anything beyond ssh from the router and Grafana from the load balancer is
                # refused.
                (lambda aws: aws.security_groups.update({("vpc-router", control_host.SECURITY_GROUP): (
                    "sg-control", {("tcp", 443, 443, cidr), ("tcp", 3000, 3000, "sg-balancer"),
                                   ("tcp", 3000, 3000, cidr)})}),
                 "admitting only tcp 443 from its ingress CIDR and tcp 3000 from its load balancer's")):
            with self.subTest(refusal=refusal), tempfile.TemporaryDirectory() as temporary:
                aws = control_host_cloud()
                change(aws)
                with self.assertRaisesRegex(SystemExit, refusal):
                    control_host.launch(aws, "c", SSH_PUBLIC_KEY, Path(temporary) / "launch-inputs.tar.gz")
                self.assertLessEqual({call[0] for call in aws.calls}, {"parameter", "security_group"})

    def test_finish_pulls_an_ended_campaign_root_and_checks_it_against_its_controllers_manifest(self):
        aws = control_host_cloud()
        aws.region_instances["us-west-2"] = [cloud.Instance("i-host", "m7i.large", "on-demand", "running", "",
                                                            control_host.tags("c"), 0, "10.0.0.1")]
        with tempfile.TemporaryDirectory() as temporary:
            host, local = Path(temporary) / "host", Path(temporary) / "local"
            known_hosts = Path(temporary) / "ssh" / "regulator-control-hosts"
            (host / "a" / "primary").mkdir(parents=True)
            (host / "candidates.tsv").write_text("a\n")
            (host / "a" / "primary" / "accepted-units.tsv").write_text("unit\n")
            damaged = []
            reachable = [False]

            def run(command, check=False, **options):
                """ssh-keyscan of the host, which serves HOST_KEY, ssh to the host, which fails as ssh does until it
                is reachable, and rsync, which copies the root over the local one and damages any file damaged
                names."""
                if command[0] == "ssh-keyscan":
                    self.assertEqual(["ssh-keyscan", "-p", "443", "-T", "15", "10.0.0.1"], command)
                    return subprocess.CompletedProcess(command, 0, f"# 10.0.0.1:443 SSH-2.0-OpenSSH_9.8\n"
                                                                   f"[10.0.0.1]:443 {HOST_KEY}\n", "")
                if command[0] == "ssh":
                    return subprocess.CompletedProcess(command, 255 if not reachable[0] else
                                                       0 if (host / control_host.MANIFEST).is_file() else 1)
                # ssh trusts only the host keys its console lists, and offers only the key it was launched with.
                self.assertEqual(["rsync", "-a", "--delete", "-e", "ssh -p 443 -o StrictHostKeyChecking=yes "
                                  f"-o UserKnownHostsFile={known_hosts} -i /keys/control-host -o IdentitiesOnly=yes",
                                  "ec2-user@10.0.0.1:/regulator/campaign/", f"{local}/"], command)
                shutil.rmtree(local)
                shutil.copytree(host, local)
                for name in damaged:
                    (local / name).write_text("damaged\n")
                return subprocess.CompletedProcess(command, 0)

            with patch.object(control_host.subprocess, "run", side_effect=run), \
                    patch.object(control_host, "KNOWN_HOSTS", known_hosts), contextlib.redirect_stdout(io.StringIO()):
                # A host that has not yet listed its keys on its console, or serves a key other than the ones listed,
                # is not trusted.
                with self.assertRaisesRegex(SystemExit, "has not yet listed its ssh host keys"):
                    control_host.finish(aws, "c", local, Path("/keys/control-host"))
                aws.consoles["i-host"] = ("ec2: -----BEGIN SSH HOST KEY FINGERPRINTS-----\n"
                                          f"ec2: 256 {OTHER_KEY_FINGERPRINT} no comment (ED25519)\n"
                                          "ec2: -----END SSH HOST KEY FINGERPRINTS-----\n")
                with self.assertRaisesRegex(SystemExit, "no ssh host key .* matches a fingerprint its console"):
                    control_host.finish(aws, "c", local, Path("/keys/control-host"))
                self.assertFalse(known_hosts.exists())
                # A key the console lists replaces any an earlier host at the address had.
                aws.consoles["i-host"] = aws.consoles["i-host"].replace(OTHER_KEY_FINGERPRINT, HOST_KEY_FINGERPRINT)
                known_hosts.parent.mkdir()
                known_hosts.write_text(f"[10.0.0.1]:443 {OTHER_KEY}\n[10.0.0.2]:443 {OTHER_KEY}\n")
                # A host ssh cannot reach is named as such, not as a controller still running.
                with self.assertRaisesRegex(SystemExit, "cannot reach campaign c's control host at "
                                                        "ec2-user@10.0.0.1 over ssh"):
                    control_host.finish(aws, "c", local, Path("/keys/control-host"))
                self.assertEqual(f"[10.0.0.2]:443 {OTHER_KEY}\n[10.0.0.1]:443 {HOST_KEY}\n", known_hosts.read_text())
                reachable[0] = True
                # A root whose controller has not ended has no manifest, and nothing is pulled.
                with self.assertRaisesRegex(SystemExit, "controller has not ended"):
                    control_host.finish(aws, "c", local, Path("/keys/control-host"))
                self.assertFalse(local.exists())
                # The manifest marks the campaign ended, so it and its name are durable once written.
                with patch.object(control_host.os, "fsync", wraps=os.fsync) as synced, \
                        patch.object(control_host, "fsync_directory") as directory_synced:
                    control_host.write_manifest(host)
                synced.assert_called_once()
                directory_synced.assert_called_once_with(host)
                control_host.finish(aws, "c", local, Path("/keys/control-host"))
                self.assertEqual("unit\n", (local / "a" / "primary" / "accepted-units.tsv").read_text())
                # A file that differs from the manifest is named, and a finish again replaces the copy.
                damaged.append("a/primary/accepted-units.tsv")
                with self.assertRaisesRegex(SystemExit, "differs from its controller's campaign-files.sha256: "
                                                        "changed a/primary/accepted-units.tsv$"):
                    control_host.finish(aws, "c", local, Path("/keys/control-host"))
                # A local root that no finish pulled is left alone.
                (local / control_host.MANIFEST).unlink()
                with self.assertRaisesRegex(SystemExit, "holds files but no campaign-files.sha256"):
                    control_host.finish(aws, "c", local, Path("/keys/control-host"))

    def test_user_data_and_units_agree_on_the_host_layout(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "user-data.sh"
            path.write_text(control_host.user_data("c", "vol-1", SSH_PUBLIC_KEY, "s3://bucket/inputs", "0" * 64,
                                                   "https://control.test"))
            subprocess.run(["bash", "-n", str(path)], check=True)
        user_data = control_host.USER_DATA.read_text()
        campaign = (CONTROL_HOST / "regulator-campaign@.service").read_text()
        lgtm = (CONTROL_HOST / "regulator-lgtm.service").read_text()
        # A first boot interrupted while unpacking the launch inputs, or after creating the checkout, leaves the next
        # boot to finish them.
        self.assertIn('-C "${INPUTS}.new"\n    mv -T "${INPUTS}.new" "${INPUTS}"\n', user_data)
        self.assertIn('git -C "${CHECKOUT}" remote get-url origin', user_data)
        # The controller unit runs the recorded command from the volume, with the environment user data built, and
        # leaves a campaign that ended with failed phases ended.
        venv = re.search(r"^VENV=(\S+)$", user_data, re.MULTILINE)[1]
        self.assertIn(f"\nExecStart={venv}/bin/python tools/re2-benchmark/baseline/run-campaign.py "
                      "$CONTROLLER_ARGUMENTS\n", campaign)
        self.assertIn(f"\nEnvironmentFile={control_host.INPUTS}/controller.env\n", campaign)
        self.assertIn(f"\nWorkingDirectory={control_host.INPUTS}/checkout\n", campaign)
        self.assertIn(f"\nRestartPreventExitStatus={control_host.ENDED_EXIT}\n", campaign)
        # The controller exports to the collector's OTLP/HTTP port, which answers only the host itself.
        self.assertIn("\nEnvironment=OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318\n", campaign)
        self.assertIn('systemctl enable --now "regulator-campaign@${CAMPAIGN}.service"', user_data)
        # sshd listens on the one port the subnet router forwards, and the console lists its keys' fingerprints for
        # the Mac to trust them by, before the slow installs.
        self.assertLess(user_data.index("Port 443"), user_data.index("dnf install -y docker"))
        self.assertLess(user_data.index("END SSH HOST KEY FINGERPRINTS"), user_data.index("dnf install -y docker"))
        # The host keeps Dain's local time, which the controller's events and status over ssh print.
        self.assertIn("\ntimedatectl set-timezone America/Los_Angeles\n", user_data)
        # The campaign root records Grafana's URL for the status command.
        self.assertIn(f'> "${{MOUNT}}/campaign/{control_host.GRAFANA_URL_FILE}"', user_data)
        # Grafana answers the load balancer on port 3000 with anonymous access off and builds its links from the
        # load balancer's URL, OTLP answers only the host itself, the image is pinned, and the dashboards' provider
        # reads where the unit mounts them.
        self.assertEqual(["-p 3000:3000", "-p 127.0.0.1:4317:4317", "-p 127.0.0.1:4318:4318"],
                         re.findall(r"-p \S+", lgtm))
        self.assertIn("-e GF_AUTH_ANONYMOUS_ENABLED=false", lgtm)
        self.assertIn("-e GF_SERVER_ROOT_URL", lgtm)
        self.assertIn('GF_SERVER_ROOT_URL=%s\\n\' "${password}" "${GRAFANA_URL}"', user_data)
        self.assertRegex(lgtm, r"grafana/otel-lgtm@sha256:[0-9a-f]{64}\n")
        provider = re.search(r"^      path: (\S+)$", (CONTROL_HOST / "grafana" / "dashboards.yaml").read_text(),
                             re.MULTILINE)[1]
        self.assertIn(f"/aws/control-host/grafana:{provider}:ro", lgtm)

    def test_dashboards_query_only_what_the_controller_exports(self):
        # Prometheus names each counter with _total.
        exported = set(telemetry.GAUGES) | {f"{name}_total" for name in telemetry.COUNTERS}
        paths = sorted((CONTROL_HOST / "grafana").glob("*.json"))
        self.assertEqual(["fleet.json", "health.json", "spend.json"], [path.name for path in paths])
        for path in paths:
            with self.subTest(dashboard=path.name):
                panels = json.loads(path.read_text())["panels"]
                # The monitor's runbook finds each panel by its title.
                titles = [panel["title"] for panel in panels]
                self.assertEqual(sorted(set(titles)), sorted(titles))
                for panel in panels:
                    for target in panel["targets"]:
                        # Each query reads a datasource otel-lgtm provisions, and each metric one the controller
                        # exports.
                        self.assertIn(target["datasource"]["uid"], ("prometheus", "loki", "tempo"))
                        self.assertLessEqual(set(re.findall(r"\bregulator_[a-z_]+", target.get("expr", ""))),
                                             exported, panel["title"])


if __name__ == "__main__":
    unittest.main()
