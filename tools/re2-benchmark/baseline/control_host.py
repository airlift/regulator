"""A campaign's control host, which runs the campaign's controller under systemd and serves its Grafana, and the
Mac-side steps that launch, replace, finish and tear it down.

The host's network, which this public repository never names, is the JSON SSM parameter NETWORK_PARAMETER: the VPC,
the private subnets the company's Tailscale subnet router carries, the CIDR the router's traffic arrives from, the
host's security group, and the internal load balancer, its security group and target group, whose record keeps
Grafana at one URL. The router forwards only tcp 443, so the load balancer's TLS listener carries Grafana and the
host's sshd listens on 443 too. The campaign root lives on the campaign's own EBS volume, so a replaced host carries
on with it. The Mac-side steps run under Dain's credentials, which alone may register hosts in the target group.
"""

import base64
import hashlib
import json
import os
import re
import shlex
import subprocess
import tarfile
from pathlib import Path

from campaign_io import fsync_directory
from cloud import CloudError

REGION = "us-west-2"
# al2023-ami-2023.12.20261102.0-kernel-6.18-x86_64, resolved in us-west-2 on 2026-10-30.
IMAGE = "ami-09f02015912e773f7"
INSTANCE_TYPE = "m7i.large"
PROFILE = "regulator-benchmark-control-host"
SECURITY_GROUP = "regulator-benchmark-control-host"
NETWORK_PARAMETER = "/regulator-benchmark/control-host-network"
NETWORK_KEYS = ("vpc", "subnets", "ingress_cidr", "zone", "record", "security_group", "load_balancer_security_group",
                "target_group", "load_balancer", "listener", "certificate")
# The port the host's sshd listens on beside 22, the only one the subnet router forwards.
SSH_PORT = 443
# The port Grafana answers the load balancer on.
GRAFANA_PORT = 3000
# The status ssh exits with when it cannot reach or log in to the host, rather than the remote command's.
SSH_FAILED = 255
# The known-hosts file of the control hosts' ssh keys, each checked against its host's console before it is added, so
# ssh to a control host trusts no key it has not seen there.
KNOWN_HOSTS = Path.home() / ".ssh" / "regulator-control-hosts"
# The block the host's console lists its ssh host keys' fingerprints in, as cloud-init and the user data print it.
FINGERPRINTS = re.compile(r"-----BEGIN SSH HOST KEY FINGERPRINTS-----(.*?)-----END SSH HOST KEY FINGERPRINTS-----",
                          re.DOTALL)
VOLUME_GIB = 200
VOLUME_DEVICE = "/dev/sdf"
# On the campaign volume: the campaign root, and the launch inputs with the controller's checkout.
CAMPAIGN_ROOT = "/regulator/campaign"
INPUTS = "/regulator/control-host"
# The controller's list of every file of the campaign root with its SHA-256, which finish checks the pulled root
# against.
MANIFEST = "campaign-files.sha256"
# Where the host records, in the campaign root, the URL its Grafana serves at, for the status command.
GRAFANA_URL_FILE = "grafana-url.txt"
USER_DATA = Path(__file__).resolve().parents[1] / "aws" / "control-host" / "user-data.sh"
# The exit status of a controller whose campaign ended with failed phases, or that refused to start, as it does the
# same way on every start, which the host's controller unit does not restart.
ENDED_EXIT = 3


def tags(campaign):
    """The tags of a campaign's control host, its volume and the volume's last snapshot."""
    return {"Project": "regulator-benchmark", "Role": "control-host", "Campaign": campaign,
            "Name": f"regulator-control-host-{campaign}"}


def grafana_parameters(campaign):
    """The SSM SecureStrings the host keeps the campaign's Grafana admin password and monitor token in."""
    return [f"/regulator-benchmark/campaigns/{campaign}/grafana-{name}" for name in ("admin-password", "monitor-token")]


def network(aws):
    """The control host's network, as the account setup put it in the network parameter."""
    value = aws.parameter(REGION, NETWORK_PARAMETER)
    if value is None:
        raise SystemExit(f"the account has no {NETWORK_PARAMETER} parameter; create it as aws/README.md \"Account "
                         "setup\" describes")
    try:
        found = json.loads(value)
    except ValueError:
        found = None
    if (not isinstance(found, dict) or sorted(found) != sorted(NETWORK_KEYS) or
            not all(isinstance(found[key], str) and found[key] for key in NETWORK_KEYS if key != "subnets") or
            not isinstance(found["subnets"], list) or not found["subnets"] or
            not all(isinstance(subnet, str) for subnet in found["subnets"])):
        raise SystemExit(f"the {NETWORK_PARAMETER} parameter is not a JSON object of {', '.join(NETWORK_KEYS)}, as "
                         "aws/README.md \"Account setup\" describes")
    return found


def security_group(aws, network):
    """The control host's security group, which admits only ssh from the network's ingress CIDR and Grafana from the
    load balancer."""
    found = aws.security_group(REGION, network["vpc"], SECURITY_GROUP)
    if found is None or found[1] != {("tcp", SSH_PORT, SSH_PORT, network["ingress_cidr"]),
                                     ("tcp", GRAFANA_PORT, GRAFANA_PORT, network["load_balancer_security_group"])}:
        raise SystemExit(f"the control host's VPC has no {SECURITY_GROUP} security group admitting only tcp "
                         f"{SSH_PORT} from its ingress CIDR and tcp {GRAFANA_PORT} from its load balancer's security "
                         "group; create it as aws/README.md \"Account setup\" describes")
    return found[0]


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as input_file:
        for block in iter(lambda: input_file.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def write_launch_inputs(path, controller_arguments, commits, settings, plans):
    """Write the archive the host unpacks into INPUTS: controller.env, which names the command its controller unit
    runs, the candidate commits, first the controller's, settings.json, and each language plan as
    language-plans/<index>, as controller_arguments names them. systemd splits the command at whitespace, so no
    argument may hold any."""
    if any(not argument or any(character.isspace() for character in argument)
           for argument in controller_arguments):
        raise SystemExit(f"the controller's command cannot hold an empty argument or whitespace: "
                         f"{shlex.join(controller_arguments)}")
    environment = f"CONTROLLER_ARGUMENTS={' '.join(controller_arguments)}\n"
    if heap := os.environ.get("BENCHMARK_HEAP_SIZE"):
        environment += f"BENCHMARK_HEAP_SIZE={heap}\n"
    directory = path.parent / "launch-inputs"
    directory.mkdir()
    (directory / "controller.env").write_text(environment)
    (directory / "candidate-commits").write_text("".join(f"{commit}\n" for commit in commits))
    (directory / "settings.json").write_bytes(settings)
    with tarfile.open(path, "w:gz") as archive:
        for name in ("controller.env", "candidate-commits", "settings.json"):
            archive.add(directory / name, name)
        for index, plan in enumerate(plans):
            archive.add(plan, f"language-plans/{index}")


def grafana_url(network):
    """The URL the campaign's Grafana serves at, the network's record, which names the load balancer that forwards to
    whichever host is registered."""
    return f"https://{network['record']}"


def user_data(campaign, volume, ssh_public_key, inputs_uri, inputs_sha256, url):
    """The host's EC2 user data: aws/control-host/user-data.sh with its settings exported after its first line."""
    settings = {"CAMPAIGN": campaign, "CAMPAIGN_VOLUME": volume, "SSH_PUBLIC_KEY": ssh_public_key,
                "LAUNCH_INPUTS_URI": inputs_uri, "LAUNCH_INPUTS_SHA256": inputs_sha256, "CONTROL_HOST_REGION": REGION,
                "GRAFANA_URL": url}
    shebang, body = USER_DATA.read_text().split("\n", 1)
    return "".join([shebang, "\n", *(f"export {name}={shlex.quote(value)}\n" for name, value in settings.items()),
                    body])


def read_ssh_public_key(path):
    try:
        key = path.read_text().strip()
    except OSError as error:
        raise SystemExit(f"cannot read the ssh public key {path}: {error}") from error
    if not key.startswith(("ssh-", "ecdsa-", "sk-")) or "\n" in key:
        raise SystemExit(f"{path} is not one ssh public key")
    return key


def campaign_volume(aws, campaign):
    volumes = aws.volumes(REGION, tags(campaign))
    if len(volumes) != 1:
        raise SystemExit(f"campaign {campaign} has {len(volumes)} control host volumes; expected one")
    return volumes[0]


def live_hosts(aws, campaign):
    """The campaign's control hosts not yet terminated."""
    return [instance for instance in aws.instances(REGION)
            if instance.state != "terminated" and
            all(instance.tags.get(key) == value for key, value in tags(campaign).items())]


def check_target_group(aws, network, campaign):
    """Refuse to put the campaign's host in the load balancer's target group while another campaign's host, by its
    Campaign tag, is registered there: the one name would serve both campaigns' Grafana by turns."""
    registered = set(aws.registered_targets(REGION, network["target_group"]))
    campaigns = {instance.id: instance.tags.get("Campaign", "") for instance in aws.instances(REGION)
                 if instance.id in registered}
    others = sorted(f"{host} of campaign {campaigns.get(host) or 'unknown'}" for host in registered
                    if campaigns.get(host) != campaign)
    if others:
        raise SystemExit(f"the load balancer forwards Grafana to {', '.join(others)}; one name serves one "
                         f"campaign, so tear that campaign's host down before starting campaign {campaign}'s")


def launch(aws, campaign, ssh_public_key, inputs):
    """Launch the campaign's control host onto a new campaign volume, with the launch inputs archive at inputs."""
    found = network(aws)
    group = security_group(aws, found)
    check_target_group(aws, found, campaign)
    if aws.volumes(REGION, tags(campaign)):
        raise SystemExit(f"campaign {campaign} already has a control host volume; replace its host with control-host "
                         f"replace {campaign}, or tear it down first")
    zones = aws.subnet_zones(REGION, found["subnets"])
    sha256 = sha256_file(inputs)
    bucket = f"regulator-benchmark-{aws.account(REGION)}-{REGION}"
    key = f"campaigns/{campaign}/control-host/launch-inputs.tar.gz"
    aws.upload(REGION, bucket, key, inputs, sha256)
    # The volume names its launch inputs, so a host replacing one that never unpacked them still finds them.
    volume = aws.create_volume(REGION, zones[found["subnets"][0]], VOLUME_GIB, {
        **tags(campaign), "LaunchInputs": f"s3://{bucket}/{key}", "LaunchInputsSha256": sha256})
    aws.wait(REGION, "volume_available", volume)
    start(aws, found, group, campaign, campaign_volume(aws, campaign), ssh_public_key)


def replace(aws, campaign, ssh_public_key):
    """Terminate the campaign's control host, if it has one, and launch another onto its volume, in the volume's
    zone."""
    found = network(aws)
    group = security_group(aws, found)
    check_target_group(aws, found, campaign)
    volume = campaign_volume(aws, campaign)
    hosts = live_hosts(aws, campaign)
    if hosts:
        for host in hosts:
            aws.deregister_target(REGION, found["target_group"], host.id, GRAFANA_PORT)
        aws.terminate(REGION, [host.id for host in hosts])
        for host in hosts:
            aws.wait(REGION, "instance_terminated", host.id)
    # A host that has terminated may not yet have released the volume.
    aws.wait(REGION, "volume_available", volume.id)
    start(aws, found, group, campaign, volume, ssh_public_key)


def start(aws, network, group, campaign, volume, ssh_public_key):
    """Start a control host in the network's subnet in the volume's zone, attach the volume, and register the host in
    the load balancer's target group."""
    zones = aws.subnet_zones(REGION, network["subnets"])
    subnet = next((subnet for subnet in network["subnets"] if zones[subnet] == volume.zone), None)
    if subnet is None:
        raise SystemExit(f"no subnet of the {NETWORK_PARAMETER} parameter is in the campaign volume's zone "
                         f"{volume.zone}")
    image = aws.image(REGION, IMAGE)
    if image is None or (image.architecture, image.state) != ("x86_64", "available"):
        raise SystemExit(f"the control host's image {IMAGE} is not an available x86_64 image: {image}")
    data = user_data(campaign, volume.id, ssh_public_key, volume.tags["LaunchInputs"],
                     volume.tags["LaunchInputsSha256"], grafana_url(network))
    instance, address = aws.run_control_host(REGION, IMAGE, INSTANCE_TYPE, image.root_device, subnet, group, PROFILE,
                                             data, tags(campaign))
    aws.wait(REGION, "instance_running", instance)
    try:
        aws.attach_volume(REGION, volume.id, instance, VOLUME_DEVICE)
    except CloudError as error:
        # A host without the campaign volume would wait for it and then fail, so it goes, and replace starts another.
        aws.terminate(REGION, [instance])
        raise SystemExit(f"cannot attach campaign {campaign}'s volume {volume.id} to its new control host "
                         f"{instance}, which is terminated; run control-host replace {campaign} again: "
                         f"{error}") from error
    aws.register_target(REGION, network["target_group"], instance, GRAFANA_PORT)
    print(f"control host {instance} runs campaign {campaign} at {address}\n"
          f"Grafana: {grafana_url(network)} (admin password in SSM {grafana_parameters(campaign)[0]})\n"
          f"ssh, once control-host known-host {campaign} records its keys: ssh -p {SSH_PORT} "
          f"-o StrictHostKeyChecking=yes -o UserKnownHostsFile={KNOWN_HOSTS} ec2-user@{address}\n"
          f"without the subnet router: aws ssm start-session --region {REGION} --target {instance}", flush=True)



def root_files(root):
    """Every regular file under the campaign root but its manifest, by its path relative to the root."""
    return sorted(name for name in (path.relative_to(root).as_posix() for path in root.rglob("*")
                                    if path.is_file() and not path.is_symlink())
                  if name not in (MANIFEST, f"{MANIFEST}.new"))


def write_manifest(root):
    """Write the campaign root's manifest, each file's SHA-256 and path as sha256sum lists them, as its controller
    ends, durably, as it marks the campaign ended."""
    temporary = root / f"{MANIFEST}.new"
    with temporary.open("w") as output:
        output.write("".join(f"{sha256_file(root / name)}  {name}\n" for name in root_files(root)))
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, root / MANIFEST)
    fsync_directory(root)


def manifest_problems(root):
    """How the campaign root differs from its manifest: files missing, files it does not list, and files whose
    SHA-256 differs."""
    listed = {name: digest for digest, name in
              (line.split("  ", 1) for line in (root / MANIFEST).read_text().splitlines())}
    found = root_files(root)
    return ([f"missing {name}" for name in sorted(set(listed) - set(found))] +
            [f"unlisted {name}" for name in found if name not in listed] +
            [f"changed {name}" for name in found if name in listed and sha256_file(root / name) != listed[name]])


def known_host(aws, campaign):
    """Record in KNOWN_HOSTS the ssh keys the campaign's running control host serves on SSH_PORT whose fingerprints
    its console lists, in place of any recorded for its address before, and return the address. A key the console
    does not list is never recorded, so ssh checking strictly against the file reaches only the host itself."""
    hosts = [host for host in live_hosts(aws, campaign) if host.state == "running"]
    if len(hosts) != 1:
        raise SystemExit(f"campaign {campaign} has {len(hosts)} running control hosts; expected one")
    host = hosts[0]
    listed = {fingerprint for block in FINGERPRINTS.findall(aws.console_output(REGION, host.id))
              for fingerprint in re.findall(r"SHA256:[A-Za-z0-9+/]+", block)}
    if not listed:
        raise SystemExit(f"campaign {campaign}'s control host {host.id} has not yet listed its ssh host keys on its "
                         "console; try again in a few minutes")
    name = f"[{host.address}]:{SSH_PORT}"
    try:
        served = subprocess.run(["ssh-keyscan", "-p", str(SSH_PORT), "-T", "15", host.address], capture_output=True,
                                text=True).stdout.splitlines()
    except OSError as error:
        raise SystemExit(f"cannot run ssh-keyscan: {error}") from error
    keys = [line for line in served if line.split()[:1] == [name]]
    if not keys:
        raise SystemExit(f"cannot read the ssh host keys of campaign {campaign}'s control host at {host.address}")
    trusted = [line for line in keys if fingerprint(line.split()[2]) in listed]
    if not trusted:
        raise SystemExit(f"no ssh host key campaign {campaign}'s control host at {host.address} serves matches a "
                         f"fingerprint its console {host.id} lists, so ssh to it is refused")
    kept = [line for line in (KNOWN_HOSTS.read_text().splitlines() if KNOWN_HOSTS.is_file() else [])
            if line.split()[:1] != [name]]
    KNOWN_HOSTS.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = KNOWN_HOSTS.with_name(f"{KNOWN_HOSTS.name}.new")
    temporary.write_text("".join(f"{line}\n" for line in [*kept, *trusted]))
    os.replace(temporary, KNOWN_HOSTS)
    return host.address


def fingerprint(key):
    """The SHA-256 fingerprint ssh-keygen -l prints for a public key, given as its base64 blob."""
    return "SHA256:" + base64.b64encode(hashlib.sha256(base64.b64decode(key)).digest()).decode().rstrip("=")


def finish(aws, campaign, local_root, identity=None):
    """Pull the campaign root from its control host into local_root over ssh on SSH_PORT to the host's private
    address, with only the private key identity when one is given, once its controller has ended, and check it
    against the controller's manifest. A local root that holds files is replaced only where an earlier finish pulled
    it."""
    if local_root.is_dir() and any(local_root.iterdir()) and not (local_root / MANIFEST).is_file():
        raise SystemExit(f"{local_root} holds files but no {MANIFEST}, so no finish pulled it; name another "
                         "--campaign-root")
    remote = f"ec2-user@{known_host(aws, campaign)}"
    # An agent holding many keys would spend the host's authentication attempts before it offered the right one.
    ssh = ["ssh", "-p", str(SSH_PORT), "-o", "StrictHostKeyChecking=yes", "-o", f"UserKnownHostsFile={KNOWN_HOSTS}",
           *(["-i", str(identity), "-o", "IdentitiesOnly=yes"] if identity else [])]
    try:
        status = subprocess.run([*ssh, remote, "test", "-f", f"{CAMPAIGN_ROOT}/{MANIFEST}"]).returncode
        if status == SSH_FAILED:
            raise SystemExit(f"cannot reach campaign {campaign}'s control host at {remote} over ssh; see ssh's error "
                             "above")
        if status != 0:
            raise SystemExit(f"campaign {campaign}'s controller has not ended: its root has no {MANIFEST}")
        local_root.mkdir(parents=True, exist_ok=True)
        # macOS's openrsync speaks only rsync's older protocol, which these options keep to.
        subprocess.run(["rsync", "-a", "--delete", "-e", shlex.join(ssh), f"{remote}:{CAMPAIGN_ROOT}/",
                        f"{local_root}/"], check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"cannot pull campaign {campaign}'s root from {remote}: {error}") from error
    if problems := manifest_problems(local_root):
        raise SystemExit(f"{local_root} differs from its controller's {MANIFEST}: {', '.join(problems[:10])}"
                         f"{f' and {len(problems) - 10} more' if len(problems) > 10 else ''}")
    print(f"pulled campaign {campaign}'s root into {local_root}: {len(root_files(local_root))} files match its "
          f"controller's {MANIFEST}", flush=True)


def teardown(aws, campaign, confirm):
    """Remove the campaign's control host for good, as only Dain does: deregister it from the load balancer and
    terminate it, snapshot its volume, which is then the campaign root's last copy, and delete the volume and the
    Grafana credentials. The record, the load balancer and its target group belong to the account setup.

    Each step finds what an earlier, interrupted teardown left by the campaign's tags, so running it again finishes:
    a host already terminated is not waited for, a snapshot already taken of the volume is not taken again, and with
    the volume deleted only the credentials are left to delete."""
    if not confirm:
        raise SystemExit(f"teardown terminates campaign {campaign}'s control host and deletes its volume, keeping a "
                         "snapshot; only Dain runs it, with --confirm")
    found = network(aws)
    volumes = aws.volumes(REGION, tags(campaign))
    if len(volumes) > 1:
        raise SystemExit(f"campaign {campaign} has {len(volumes)} control host volumes; expected one")
    # The host goes first, so the snapshot holds the root as its controller last wrote it.
    hosts = live_hosts(aws, campaign)
    if hosts:
        for host in hosts:
            aws.deregister_target(REGION, found["target_group"], host.id, GRAFANA_PORT)
        aws.terminate(REGION, [host.id for host in hosts])
        for host in hosts:
            aws.wait(REGION, "instance_terminated", host.id)
    for volume in volumes:
        aws.wait(REGION, "volume_available", volume.id)
        snapshot = next((snapshot.id for snapshot in aws.snapshots(REGION, tags(campaign))
                         if snapshot.volume == volume.id), None) or aws.create_snapshot(REGION, volume.id,
                                                                                         tags(campaign))
        aws.wait(REGION, "snapshot_completed", snapshot)
        aws.delete_volume(REGION, volume.id)
    aws.delete_parameters(REGION, grafana_parameters(campaign))
    snapshots = sorted(snapshot.id for snapshot in aws.snapshots(REGION, tags(campaign)))
    print(f"tore down campaign {campaign}'s control host; its root's last copy is snapshot "
          f"{', '.join(snapshots) or 'none'}", flush=True)
