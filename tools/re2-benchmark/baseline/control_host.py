"""A campaign's control host, which runs the campaign's controller under systemd and serves its Grafana, and the
Mac-side steps that launch, replace, finish and tear it down.

The host's network, which this public repository never names, is the JSON SSM parameter NETWORK_PARAMETER: the VPC,
the private subnets the company's Tailscale subnet router carries, the CIDR the router's traffic arrives from, and the
private zone and record name that keep Grafana at one URL. The campaign root lives on the campaign's own EBS volume,
so a replaced host carries on with it. The Mac-side steps run under Dain's credentials, which alone may write the
record.
"""

import hashlib
import json
import os
import shlex
import subprocess
import tarfile
from pathlib import Path

REGION = "us-west-2"
# al2023-ami-2023.12.20261102.0-kernel-6.18-x86_64, resolved in us-west-2 on 2026-10-30.
IMAGE = "ami-09f02015912e773f7"
INSTANCE_TYPE = "m7i.large"
PROFILE = "regulator-benchmark-control-host"
SECURITY_GROUP = "regulator-benchmark-control-host"
NETWORK_PARAMETER = "/regulator-benchmark/control-host-network"
NETWORK_KEYS = ("vpc", "subnets", "ingress_cidr", "zone", "record")
# ssh and Grafana, the only ports the security group admits.
PORTS = (22, 3000)
# The status ssh exits with when it cannot reach or log in to the host, rather than the remote command's.
SSH_FAILED = 255
VOLUME_GIB = 200
VOLUME_DEVICE = "/dev/sdf"
# On the campaign volume: the campaign root, and the launch inputs with the controller's checkout.
CAMPAIGN_ROOT = "/regulator/campaign"
INPUTS = "/regulator/control-host"
# The controller's list of every file of the campaign root with its SHA-256, which finish checks the pulled root
# against.
MANIFEST = "campaign-files.sha256"
USER_DATA = Path(__file__).resolve().parents[1] / "aws" / "control-host" / "user-data.sh"
# The exit status of a controller whose campaign ended with failed phases, which the host's controller unit does not
# restart.
FAILED_PHASES_EXIT = 3


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
    """The control host's security group, which admits only ssh and Grafana from the network's ingress CIDR."""
    found = aws.security_group(REGION, network["vpc"], SECURITY_GROUP)
    if found is None or found[1] != {("tcp", port, port, network["ingress_cidr"]) for port in PORTS}:
        raise SystemExit(f"the control host's VPC has no {SECURITY_GROUP} security group admitting only tcp "
                         f"{' and '.join(map(str, PORTS))} from its ingress CIDR; create it as aws/README.md "
                         "\"Account setup\" describes")
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


def user_data(campaign, volume, ssh_public_key, inputs_uri, inputs_sha256):
    """The host's EC2 user data: aws/control-host/user-data.sh with its settings exported after its first line."""
    settings = {"CAMPAIGN": campaign, "CAMPAIGN_VOLUME": volume, "SSH_PUBLIC_KEY": ssh_public_key,
                "LAUNCH_INPUTS_URI": inputs_uri, "LAUNCH_INPUTS_SHA256": inputs_sha256, "CONTROL_HOST_REGION": REGION}
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


def launch(aws, campaign, ssh_public_key, inputs):
    """Launch the campaign's control host onto a new campaign volume, with the launch inputs archive at inputs."""
    found = network(aws)
    group = security_group(aws, found)
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
    volume = campaign_volume(aws, campaign)
    hosts = live_hosts(aws, campaign)
    if hosts:
        aws.terminate(REGION, [host.id for host in hosts])
        for host in hosts:
            aws.wait(REGION, "instance_terminated", host.id)
        aws.wait(REGION, "volume_available", volume.id)
    start(aws, found, group, campaign, volume, ssh_public_key)


def start(aws, network, group, campaign, volume, ssh_public_key):
    """Start a control host in the network's subnet in the volume's zone, attach the volume, and point the record at
    the host."""
    zones = aws.subnet_zones(REGION, network["subnets"])
    subnet = next((subnet for subnet in network["subnets"] if zones[subnet] == volume.zone), None)
    if subnet is None:
        raise SystemExit(f"no subnet of the {NETWORK_PARAMETER} parameter is in the campaign volume's zone "
                         f"{volume.zone}")
    image = aws.image(REGION, IMAGE)
    if image is None or (image.architecture, image.state) != ("x86_64", "available"):
        raise SystemExit(f"the control host's image {IMAGE} is not an available x86_64 image: {image}")
    data = user_data(campaign, volume.id, ssh_public_key, volume.tags["LaunchInputs"],
                     volume.tags["LaunchInputsSha256"])
    instance, address = aws.run_control_host(REGION, IMAGE, INSTANCE_TYPE, image.root_device, subnet, group, PROFILE,
                                             data, tags(campaign))
    aws.wait(REGION, "instance_running", instance)
    aws.attach_volume(REGION, volume.id, instance, VOLUME_DEVICE)
    aws.change_record(REGION, network["zone"], "UPSERT", network["record"], address)
    print(f"control host {instance} runs campaign {campaign} at {address}\n"
          f"Grafana: http://{network['record']}:3000 (admin password in SSM {grafana_parameters(campaign)[0]})\n"
          f"ssh: ssh ec2-user@{network['record']}\n"
          f"without the subnet router: aws ssm start-session --region {REGION} --target {instance}", flush=True)



def root_files(root):
    """Every regular file under the campaign root but its manifest, by its path relative to the root."""
    return sorted(name for name in (path.relative_to(root).as_posix() for path in root.rglob("*")
                                    if path.is_file() and not path.is_symlink())
                  if name not in (MANIFEST, f"{MANIFEST}.new"))


def write_manifest(root):
    """Write the campaign root's manifest, each file's SHA-256 and path as sha256sum lists them, as its controller
    ends."""
    temporary = root / f"{MANIFEST}.new"
    temporary.write_text("".join(f"{sha256_file(root / name)}  {name}\n" for name in root_files(root)))
    os.replace(temporary, root / MANIFEST)


def manifest_problems(root):
    """How the campaign root differs from its manifest: files missing, files it does not list, and files whose
    SHA-256 differs."""
    listed = {name: digest for digest, name in
              (line.split("  ", 1) for line in (root / MANIFEST).read_text().splitlines())}
    found = root_files(root)
    return ([f"missing {name}" for name in sorted(set(listed) - set(found))] +
            [f"unlisted {name}" for name in found if name not in listed] +
            [f"changed {name}" for name in found if name in listed and sha256_file(root / name) != listed[name]])


def finish(aws, campaign, local_root, identity=None):
    """Pull the campaign root from its control host into local_root over ssh, with only the private key identity
    when one is given, once its controller has ended, and check it against the controller's manifest. A local root
    that holds files is replaced only where an earlier finish pulled it."""
    hosts = [host for host in live_hosts(aws, campaign) if host.state == "running"]
    if len(hosts) != 1:
        raise SystemExit(f"campaign {campaign} has {len(hosts)} running control hosts; expected one")
    if local_root.is_dir() and any(local_root.iterdir()) and not (local_root / MANIFEST).is_file():
        raise SystemExit(f"{local_root} holds files but no {MANIFEST}, so no finish pulled it; name another "
                         "--campaign-root")
    remote = f"ec2-user@{hosts[0].address}"
    # An agent holding many keys would spend the host's authentication attempts before it offered the right one.
    ssh = ["ssh", "-o", "StrictHostKeyChecking=accept-new",
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
