"""A campaign's control host, which runs the campaign's controller under systemd and serves its Grafana, and the
Mac-side steps that launch, replace, finish and tear it down, and fetch a finished campaign's raw root.

The host's network, which this public repository never names, is the JSON SSM parameter NETWORK_PARAMETER: the VPC,
the private subnets the company's Tailscale subnet router carries, the CIDR the router's traffic arrives from, the
host's security group, and the internal load balancer, its security group and target group, whose record keeps
Grafana at one URL. The router forwards only tcp 443, so the load balancer's TLS listener carries Grafana and the
host's sshd listens on 443 too. The campaign root lives on the campaign's own EBS volume, so a replaced host carries
on with it. The Mac-side steps run under Dain's credentials, which alone may register hosts in the target group.
A finished campaign's raw root lives in the private archive bucket, which no campaign bucket's expiry reaches; only
its summaries come to the Mac.
"""

import base64
import fnmatch
import hashlib
import io
import json
import os
import re
import shlex
import subprocess
import tarfile
import tempfile
from pathlib import Path

from campaign_io import fsync_directory, ledger_rows, locked, write_ledger
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
# The tag a teardown marks the campaign volume with before anything else, so control-host replace starts no host on
# it after. A replace under way terminates its host before its controller starts, unless it stalls between attaching
# the volume and looking for the tag.
TEARDOWN_TAG = {"Teardown": "started"}
VOLUME_DEVICE = "/dev/sdf"
# On the campaign volume: the campaign root, and the launch inputs with the controller's checkout.
CAMPAIGN_ROOT = "/regulator/campaign"
INPUTS = "/regulator/control-host"
# The controller's list of every file of the campaign root with its SHA-256, which finish checks the pulled root
# against.
MANIFEST = "campaign-files.sha256"
# The python of the host's venv and the controller's checkout on the campaign volume, as the user data installs them,
# which run finish's step on the host.
HOST_PYTHON = "/opt/regulator/venv/bin/python"
HOST_RUN_CAMPAIGN = f"{INPUTS}/checkout/tools/re2-benchmark/baseline/run-campaign.py"
# The raw trees of a campaign root, which finish leaves in the root's archive, as rsync exclude patterns anchored at
# the root: the JDKs, each candidate's archive, and the units, unit results, jobs and language plan bundles of each
# phase and language phase. A summary is a file the manifest lists outside them, and finish pulls every summary: the
# ledgers, settings, events and options, each candidate's provenance, each phase's accepted units, the reductions, and
# the language results.
RAW_TREES = ("/jdks/", "/*/candidate/regulator.tar.gz",
             *(f"/*/{phase}/{tree}/" for phase in ("*", "lang/*")
               for tree in ("units", "unit-results", "jobs", "language-plans")))
# The storage class of a raw root's archive: cheap to keep, and read back in milliseconds when it is wanted.
ARCHIVE_STORAGE_CLASS = "GLACIER_IR"
# Where finish records, in the local root, the raw root's archive, which fetch restores it from.
RAW_ARCHIVE = "raw-archive.tsv"
RAW_ARCHIVE_FIELDS = ("bucket", "key", "archive_sha256", "bytes", "manifest_sha256")
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


def registered_hosts(aws, network):
    """The hosts registered in the load balancer's target group, by ID, each with its Campaign tag, or "" when it has
    none. A host that has terminated, or that EC2 no longer lists, as about an hour after, has None, since it serves
    no campaign."""
    registered = aws.registered_targets(REGION, network["target_group"])
    instances = {instance.id: instance for instance in aws.instances(REGION)}
    return {host: (instances[host].tags.get("Campaign", "")
                   if host in instances and instances[host].state != "terminated" else None)
            for host in registered}


def other_hosts(aws, network, campaign):
    """The hosts registered in the load balancer's target group that are not tagged for the campaign, each named with
    its campaign: the one name would serve both campaigns' Grafana by turns. A host that has terminated serves none."""
    return sorted(f"{host} of campaign {owner or 'unknown'}"
                  for host, owner in registered_hosts(aws, network).items() if owner not in (None, campaign))


def check_target_group(aws, network, campaign):
    """Refuse to register the campaign's host in the load balancer's target group while another campaign's host is
    registered there."""
    if others := other_hosts(aws, network, campaign):
        raise SystemExit(f"the load balancer forwards Grafana to {', '.join(others)}; one name serves one "
                         f"campaign, so tear that campaign's host down before starting campaign {campaign}'s")


def deregister_hosts(aws, network, campaign):
    """Deregister from the load balancer's target group every host of the campaign, live or not, and every host that
    has terminated; return the hosts deregistered."""
    hosts = [host for host, owner in registered_hosts(aws, network).items() if owner in (None, campaign)]
    for host in hosts:
        aws.deregister_target(REGION, network["target_group"], host, GRAFANA_PORT)
    return hosts


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
    zone. It refuses once a teardown has begun. A host it launched as the teardown began goes before its controller
    starts, unless the replace stalls between the attach and its look for the teardown. Either way the host may write
    its boot files first. So never run teardown and replace at the same time."""
    found = network(aws)
    group = security_group(aws, found)
    check_target_group(aws, found, campaign)
    volume = campaign_volume(aws, campaign)
    if refusal := teardown_refusal(aws, campaign, volume.id):
        raise SystemExit(refusal)
    deregister_hosts(aws, found, campaign)
    hosts = live_hosts(aws, campaign)
    if hosts:
        aws.terminate(REGION, [host.id for host in hosts])
        for host in hosts:
            aws.wait(REGION, "instance_terminated", host.id)
    # A host that has terminated may not yet have released the volume.
    aws.wait(REGION, "volume_available", volume.id)
    start(aws, found, group, campaign, volume, ssh_public_key)


def start(aws, network, group, campaign, volume, ssh_public_key):
    """Start a control host in the network's subnet in the volume's zone, attach the volume, and register the host in
    the load balancer's target group. Back the host out if another campaign's host is registered there too."""
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
        fate = discard(aws, network, instance, registered=False)
        raise SystemExit(f"cannot attach campaign {campaign}'s volume {volume.id} to its new control host "
                         f"{instance}: {error}; that host {fate}; run control-host replace {campaign} again") from error
    # A teardown may have begun after this replace checked the volume. It terminates every host it finds and
    # snapshots the volume once they are gone. So look for it again: a host the teardown did not find goes before its
    # controller starts, and so does one whose look fails. Only a replace that stalls between the attach and this look
    # can start its controller after the snapshot.
    try:
        refusal = teardown_refusal(aws, campaign, volume.id)
    except CloudError as error:
        fate = discard(aws, network, instance, registered=False)
        raise SystemExit(f"cannot check that no teardown has begun on campaign {campaign}'s volume {volume.id}: "
                         f"{error}; its new control host {instance} {fate}; run control-host replace {campaign} "
                         "again") from error
    if refusal:
        fate = discard(aws, network, instance, registered=False)
        raise SystemExit(f"{refusal}; its new control host {instance} {fate}")
    # Another campaign's launch may have passed its own check of the target group at the same time as this one, as in
    # the moment a replace leaves its campaign without a host. So each launch looks again once it registers, and one
    # that finds the other's host backs out before its controller starts. A launch whose look fails backs out too and
    # deregisters its host: a registration whose answer was lost may have taken effect, and deregistering a host never
    # registered is harmless.
    try:
        aws.register_target(REGION, network["target_group"], instance, GRAFANA_PORT)
        others = other_hosts(aws, network, campaign)
    except CloudError as error:
        fate = discard(aws, network, instance, registered=True)
        raise SystemExit(f"cannot register campaign {campaign}'s new control host {instance} in the load balancer's "
                         f"target group and check that no other campaign's host is there: {error}; that host {fate}; "
                         f"run control-host replace {campaign} again") from error
    if others:
        fate = discard(aws, network, instance, registered=True)
        raise SystemExit(f"the load balancer also forwards Grafana to {', '.join(others)}, registered at the same "
                         f"time as campaign {campaign}'s new control host {instance}; one name serves one campaign, so "
                         f"that host {fate}; once the other campaign's host is torn down, run control-host replace "
                         f"{campaign}")
    print(f"control host {instance} runs campaign {campaign} at {address}\n"
          f"Grafana: {grafana_url(network)} (admin password in SSM {grafana_parameters(campaign)[0]})\n"
          f"ssh, once control-host known-host {campaign} records its keys: "
          f"{shlex.join([*trusted_ssh(), f'ec2-user@{address}'])}\n"
          f"without the subnet router: aws ssm start-session --region {REGION} --target {instance}", flush=True)


def teardown_refusal(aws, campaign, volume_id):
    """Why no host goes on the campaign volume once a teardown has marked it, or deleted it, or "" before."""
    if any(item.id == volume_id and not TEARDOWN_TAG.items() <= item.tags.items()
           for item in aws.volumes(REGION, tags(campaign))):
        return ""
    return (f"campaign {campaign}'s teardown has begun on its volume {volume_id}, whose snapshot is to be its root's "
            f"last copy on EBS, so control-host replace starts no host on it; finish the teardown with run-campaign.py "
            f"teardown {campaign} --confirm")


def discard(aws, network, instance, registered):
    """Deregister a new control host of the campaign from the load balancer's target group, if it was registered,
    and request its termination either way. Return what became of it for the refusal that follows, which says what
    takes down a host left running or registered. Each step whose request failed is named, since its outcome is
    unconfirmed."""
    deregistration = termination = None
    if registered:
        try:
            aws.deregister_target(REGION, network["target_group"], instance, GRAFANA_PORT)
        except CloudError as error:
            deregistration = error
    try:
        aws.terminate(REGION, [instance])
    except CloudError as error:
        termination = error
    if deregistration and termination:
        return (f"has its deregistration and termination unconfirmed, since deregistering it failed: "
                f"{deregistration}, and terminating it failed: {termination}")
    if deregistration:
        return f"has its termination requested, but its deregistration unconfirmed: {deregistration}"
    if termination:
        fate = f"its termination unconfirmed: {termination}"
        return f"has its deregistration requested, but {fate}" if registered else f"has {fate}"
    return "has its deregistration and termination requested" if registered else "has its termination requested"


def root_files(root):
    """Every regular file under the campaign root but its manifest and its record of its raw root's archive, by its
    path relative to the root."""
    return sorted(name for name in (path.relative_to(root).as_posix() for path in root.rglob("*")
                                    if path.is_file() and not path.is_symlink())
                  if name not in (MANIFEST, f"{MANIFEST}.new", RAW_ARCHIVE, f"{RAW_ARCHIVE}.new"))


def write_manifest(root, manifest=None):
    """Write the campaign root's manifest durably as its controller ends, which marks the campaign ended. It lists
    each file's SHA-256 and path as sha256sum does. finish gives the bytes of the manifest it pulled instead."""
    temporary = root / f"{MANIFEST}.new"
    with temporary.open("wb") as output:
        output.write(manifest if manifest is not None else
                     "".join(f"{sha256_file(root / name)}  {name}\n" for name in root_files(root)).encode())
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, root / MANIFEST)
    fsync_directory(root)


def manifest_problems(root, wanted=lambda name: True, manifest=None):
    """How the campaign root differs from its manifest, or from the manifest whose bytes are given: missing files that
    wanted keeps, files the manifest does not list, and listed files whose SHA-256 differs."""
    manifest = (root / MANIFEST).read_bytes() if manifest is None else manifest
    listed = {name: digest for digest, name in (line.split("  ", 1) for line in manifest.decode().splitlines())}
    found = root_files(root)
    return ([f"missing {name}" for name in sorted(set(listed) - set(found)) if wanted(name)] +
            [f"unlisted {name}" for name in found if name not in listed] +
            [f"changed {name}" for name in found if name in listed and sha256_file(root / name) != listed[name]])


def check_manifest(root, wanted=lambda name: True, manifest=None):
    """Refuse a campaign root that differs from its manifest, as manifest_problems finds."""
    if problems := manifest_problems(root, wanted, manifest):
        raise SystemExit(f"{root} differs from its controller's {MANIFEST}: {', '.join(problems[:10])}"
                         f"{f' and {len(problems) - 10} more' if len(problems) > 10 else ''}")


def summary(name):
    """Whether the file the manifest lists at name, relative to the campaign root, is a summary, which finish pulls:
    whether no RAW_TREES pattern matches it, as rsync matches them."""
    parts = name.split("/")
    for pattern in RAW_TREES:
        steps = pattern.strip("/").split("/")
        if ((len(parts) > len(steps) if pattern.endswith("/") else len(parts) == len(steps)) and
                all(fnmatch.fnmatchcase(part, step) for part, step in zip(parts, steps))):
            return False
    return True


def known_host(aws, campaign):
    """Record in KNOWN_HOSTS the ssh keys the campaign's running control host serves on SSH_PORT whose fingerprints
    its console lists, in place of any recorded for its address before, and return the address. A key the console
    does not list is never recorded, so ssh checking strictly against the file reaches only the host itself."""
    hosts = [host for host in live_hosts(aws, campaign) if host.state == "running"]
    if len(hosts) != 1:
        raise SystemExit(f"campaign {campaign} has {len(hosts)} running control hosts; expected one")
    host = hosts[0]
    listed = {listing for block in FINGERPRINTS.findall(aws.console_output(REGION, host.id))
              for listing in re.findall(r"SHA256:[A-Za-z0-9+/]+", block)}
    if not listed:
        raise SystemExit(f"campaign {campaign}'s control host {host.id} has not yet listed its ssh host keys on its "
                         "console; try again in a few minutes")
    name = f"[{host.address}]:{SSH_PORT}"
    try:
        scan = subprocess.run(["ssh-keyscan", "-p", str(SSH_PORT), "-T", "15", host.address], capture_output=True,
                              text=True)
    except OSError as error:
        raise SystemExit(f"cannot run ssh-keyscan: {error}") from error
    keys = [line for line in scan.stdout.splitlines() if line.split()[:1] == [name]]
    if not keys:
        error = scan.stderr.strip() or "no error output"
        raise SystemExit(f"cannot read the ssh host keys of campaign {campaign}'s control host at {host.address}: "
                         f"ssh-keyscan exited with status {scan.returncode}: {error}")
    trusted = [line for line in keys if fingerprint(line.split()[2]) in listed]
    if not trusted:
        raise SystemExit(f"no ssh host key campaign {campaign}'s control host at {host.address} serves matches a "
                         f"fingerprint its console {host.id} lists, so ssh to it is refused")
    KNOWN_HOSTS.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # finish and known-host may record keys at the same time. Each rewrites the file from the version before it, so
    # each waits for the other under the lock and stages its own copy.
    with locked(KNOWN_HOSTS.with_name(f"{KNOWN_HOSTS.name}.lock")):
        kept = [line for line in (KNOWN_HOSTS.read_text().splitlines() if KNOWN_HOSTS.is_file() else [])
                if line.split()[:1] != [name]]
        descriptor, temporary = tempfile.mkstemp(prefix=f"{KNOWN_HOSTS.name}.", suffix=".new", dir=KNOWN_HOSTS.parent)
        try:
            with os.fdopen(descriptor, "w") as output:
                output.write("".join(f"{line}\n" for line in [*kept, *trusted]))
            os.replace(temporary, KNOWN_HOSTS)
        finally:
            Path(temporary).unlink(missing_ok=True)
    return host.address


def trusted_ssh():
    """ssh to a control host on SSH_PORT, trusting only the host keys KNOWN_HOSTS records: none from the system's
    known-hosts file or a command ssh's configuration names."""
    return ["ssh", "-p", str(SSH_PORT), "-o", "StrictHostKeyChecking=yes", "-o", f"UserKnownHostsFile={KNOWN_HOSTS}",
            "-o", "GlobalKnownHostsFile=/dev/null", "-o", "KnownHostsCommand=none"]


def fingerprint(key):
    """The SHA-256 fingerprint ssh-keygen -l prints for a public key, given as its base64 blob."""
    return "SHA256:" + base64.b64encode(hashlib.sha256(base64.b64decode(key)).digest()).decode().rstrip("=")


def archive_location(aws, campaign, manifest_sha256):
    """The private archive bucket, the key of the campaign root's archive as the manifest whose SHA-256 is given lists
    the root, and the key of that manifest beside it. The bucket is not a campaign bucket, so no expiry rule of theirs
    reaches it, and a reopened campaign that ends again writes a new manifest, and so a new archive."""
    key = f"campaigns/{campaign}/root-{manifest_sha256}"
    return f"regulator-benchmark-archive-{aws.account(REGION)}-{REGION}", f"{key}.tar.zst", f"{key}.sha256"


def archive_metadata(manifest):
    """The metadata of the campaign root's archive as the manifest whose bytes are given lists the root: the
    manifest's SHA-256 and its file count."""
    return {"manifest-sha256": hashlib.sha256(manifest).hexdigest(),
            "file-count": str(len(manifest.decode().splitlines()))}


class ArchiveStream:
    """The campaign root as tar writes it through zstd, read as one stream, with the SHA-256 and size of what was
    read. Its end comes only once tar and zstd have both exited cleanly, so an upload of a root cut short fails
    before it completes."""

    def __init__(self, root):
        self.tar = subprocess.Popen(["tar", "-C", str(root), "-cf", "-", "."], stdout=subprocess.PIPE)
        self.zstd = subprocess.Popen(["zstd", "-T0", "-q", "-c"], stdin=self.tar.stdout, stdout=subprocess.PIPE)
        self.tar.stdout.close()
        self.digest = hashlib.sha256()
        self.size = 0

    def read(self, size):
        block = self.zstd.stdout.read(size)
        if not block:
            for process in (self.tar, self.zstd):
                if process.wait() != 0:
                    raise subprocess.CalledProcessError(process.returncode, process.args)
        self.digest.update(block)
        self.size += len(block)
        return block

    def __enter__(self):
        return self

    def __exit__(self, *exception):
        # An upload that failed partway leaves the pipeline writing; ending it frees the volume's reads.
        for process in (self.zstd, self.tar):
            process.kill()
            process.wait()
        self.zstd.stdout.close()


def archive_root(aws, campaign):
    """Archive the ended campaign's root from its control host, as finish runs this there: tar through zstd, streamed
    into the archive bucket in REGION, so the bulk never leaves it, in ARCHIVE_STORAGE_CLASS with metadata naming the
    manifest's SHA-256 and its file count. S3 takes an object's metadata before its bytes, so the manifest beside
    the archive, written once the archive is complete, records the archive's SHA-256 and size. Neither is ever
    overwritten: an archive already there is reused where its metadata matches, so finish may run again, and one
    whose manifest beside it is missing, as when an earlier run stopped between them, is read back as a stream for
    its SHA-256. A root is archived only once it matches its manifest, as it is then kept for good."""
    root = Path(CAMPAIGN_ROOT)
    if not (root / MANIFEST).is_file():
        raise SystemExit(f"campaign {campaign}'s controller has not ended: its root has no {MANIFEST}")
    manifest = (root / MANIFEST).read_bytes()
    expected = archive_metadata(manifest)
    try:
        bucket, key, manifest_key = archive_location(aws, campaign, expected["manifest-sha256"])
        sidecar = None
        if aws.head(REGION, bucket, key) is None:
            check_manifest(root, manifest=manifest)
            with ArchiveStream(root) as stream:
                if aws.upload_stream(REGION, bucket, key, stream, expected, ARCHIVE_STORAGE_CLASS):
                    sidecar = {"archive-sha256": stream.digest.hexdigest(), "archive-bytes": str(stream.size)}
        archive = aws.head(REGION, bucket, key)
        if archive is None or (archive.metadata, archive.storage_class) != (expected, ARCHIVE_STORAGE_CLASS):
            found = "nothing" if archive is None else f"metadata {archive.metadata} in {archive.storage_class}"
            raise SystemExit(f"s3://{bucket}/{key} is not campaign {campaign}'s root as its {MANIFEST} lists it: it "
                             f"holds {found}, and is never overwritten")
        if sidecar is None and aws.head(REGION, bucket, manifest_key) is None:
            # The host may write only under its campaign volume's trees, so the archive is hashed as it is read.
            sha256, size = aws.digest(REGION, bucket, key)
            sidecar = {"archive-sha256": sha256, "archive-bytes": str(size)}
        if sidecar is not None:
            # A manifest beside it that another run wrote first stays; finish checks whichever is there from the Mac.
            aws.upload_stream(REGION, bucket, manifest_key, io.BytesIO(manifest), sidecar, ARCHIVE_STORAGE_CLASS)
    except (CloudError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"cannot archive campaign {campaign}'s root to S3: {error}") from error
    print(f"campaign {campaign}'s root is archived as s3://{bucket}/{key}", flush=True)


def check_archive_lifecycle(aws, bucket, key):
    """Refuse an archive bucket with an enabled lifecycle rule that reaches the archive at key and would expire it or
    move it out of ARCHIVE_STORAGE_CLASS. The archive carries no tags, so a rule that selects by tag never reaches
    it."""
    for rule in aws.lifecycle_rules(REGION, bucket):
        selection = rule.get("Filter", {})
        prefix = selection.get("Prefix", selection.get("And", {}).get("Prefix", rule.get("Prefix", "")))
        moves = [transition for transition in rule.get("Transitions", []) + rule.get("NoncurrentVersionTransitions", [])
                 if transition.get("StorageClass") != ARCHIVE_STORAGE_CLASS]
        if (rule.get("Status") == "Enabled" and "Tag" not in selection and not selection.get("And", {}).get("Tags") and
                key.startswith(prefix) and ("Expiration" in rule or "NoncurrentVersionExpiration" in rule or moves)):
            raise SystemExit(f"the archive bucket {bucket} has the lifecycle rule {rule.get('ID', '')!r}, which "
                             f"would expire or move s3://{bucket}/{key}; a campaign's raw root is kept for good")


def confirmed_archive(aws, campaign, bucket, key, manifest_key, manifest, expected):
    """The row of RAW_ARCHIVE for the campaign root's archive at key, once its metadata and storage class match
    expected, the archive_metadata of the manifest's bytes, and the manifest beside it records the archive's SHA-256
    and size."""
    archive = aws.head(REGION, bucket, key)
    sidecar = aws.head(REGION, bucket, manifest_key)
    if (archive is None or sidecar is None or (archive.metadata, archive.storage_class) !=
            (expected, ARCHIVE_STORAGE_CLASS) or sidecar.size != len(manifest) or
            not re.fullmatch(r"[0-9a-f]{64}", sidecar.metadata.get("archive-sha256", "")) or
            sidecar.metadata.get("archive-bytes") != str(archive.size)):
        raise SystemExit(f"s3://{bucket}/{key} is not campaign {campaign}'s archived root as its {MANIFEST} lists it, "
                         f"in {ARCHIVE_STORAGE_CLASS}, with its manifest beside it recording its SHA-256 and size")
    return {"bucket": bucket, "key": key, "archive_sha256": sidecar.metadata["archive-sha256"],
            "bytes": str(archive.size), "manifest_sha256": expected["manifest-sha256"]}


def host_manifest(ssh, remote, campaign):
    """The bytes of the campaign root's manifest on its control host, or None where it cannot be read, as before its
    controller has ended."""
    read = subprocess.run([*ssh, remote, "cat", f"{CAMPAIGN_ROOT}/{MANIFEST}"], stdout=subprocess.PIPE)
    if read.returncode == SSH_FAILED:
        raise SystemExit(f"cannot reach campaign {campaign}'s control host at {remote} over ssh; see ssh's error above")
    return read.stdout if read.returncode == 0 else None


def finish(aws, campaign, local_root, identity=None):
    """Archive an ended campaign's raw root to S3 from its control host and pull its summaries into local_root, over
    ssh on SSH_PORT to the host's private address, with only the private key identity when one is given. The host
    archives its root, as archive_root does; finish confirms the archive from here, pulls every summary, checks them
    against the controller's manifest, and records the archive in RAW_ARCHIVE for fetch. A local root that holds files
    is replaced only where an earlier finish pulled it, and raw files fetch restored there stay. A first finish writes
    the manifest before it pulls, so it may run again if it fails; a later finish replaces the manifest and record
    only once the pulled summaries match the manifest the host still holds, so one that fails keeps the earlier
    finish's."""
    if local_root.is_dir() and any(local_root.iterdir()) and not (local_root / MANIFEST).is_file():
        raise SystemExit(f"{local_root} holds files but no {MANIFEST}, so no finish pulled it; name another "
                         "--campaign-root")
    remote = f"ec2-user@{known_host(aws, campaign)}"
    # An agent holding many keys would spend the host's authentication attempts before it offered the right one.
    ssh = [*trusted_ssh(), *(["-i", str(identity), "-o", "IdentitiesOnly=yes"] if identity else [])]
    try:
        if (manifest := host_manifest(ssh, remote, campaign)) is None:
            raise SystemExit(f"campaign {campaign}'s root has no readable {MANIFEST}; its controller may not have "
                             "ended")
        expected = archive_metadata(manifest)
        bucket, key, manifest_key = archive_location(aws, campaign, expected["manifest-sha256"])
        check_archive_lifecycle(aws, bucket, key)
        archived = subprocess.run([*ssh, remote, HOST_PYTHON, HOST_RUN_CAMPAIGN, "archive-root",
                                   shlex.quote(campaign)]).returncode
        # argparse exits 2 for a step its run-campaign.py does not know, as on a host launched before archive-root.
        if archived == 2:
            raise SystemExit(f"campaign {campaign}'s control host at {remote} could not run archive-root; its tooling "
                             "may predate archive-root, and such a campaign is finished with the tooling it launched "
                             "with")
        if archived != 0:
            raise SystemExit(f"campaign {campaign}'s control host at {remote} did not archive its root; see the error "
                             "above")
        record = confirmed_archive(aws, campaign, bucket, key, manifest_key, manifest, expected)
        local_root.mkdir(parents=True, exist_ok=True)
        # The manifest marks a root finish pulled, so a first finish that fails partway may run again.
        if not (local_root / MANIFEST).is_file():
            write_manifest(local_root, manifest)
        # The rsync leaves the local manifest and record alone. macOS's openrsync speaks only rsync's older protocol,
        # which these options keep to.
        subprocess.run(["rsync", "-a", "--delete", *(f"--exclude={pattern}" for pattern in (
            *RAW_TREES, f"/{MANIFEST}", f"/{RAW_ARCHIVE}")), "-e", shlex.join(ssh), f"{remote}:{CAMPAIGN_ROOT}/",
                        f"{local_root}/"], check=True)
        if host_manifest(ssh, remote, campaign) != manifest:
            raise SystemExit(f"campaign {campaign}'s {MANIFEST} went missing or changed while finish ran, as a "
                             "reopen does; run finish again once its controller ends")
    except CloudError as error:
        raise SystemExit(f"cannot check campaign {campaign}'s archived root in S3: {error}") from error
    except (OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"cannot pull campaign {campaign}'s root from {remote}: {error}") from error
    check_manifest(local_root, summary, manifest)
    write_manifest(local_root, manifest)
    write_ledger(local_root / RAW_ARCHIVE, [record], RAW_ARCHIVE_FIELDS, key=None)
    summaries = sum(1 for line in manifest.decode().splitlines() if summary(line.split("  ", 1)[1]))
    print(f"campaign {campaign}'s raw root is archived as s3://{bucket}/{key}; its {summaries} summaries in "
          f"{local_root} match its controller's {MANIFEST}", flush=True)


def fetch(aws, campaign, local_root):
    """Restore into local_root, beside the summaries finish pulled, the raw root it archived: download the archive
    RAW_ARCHIVE records, check its SHA-256, unpack its directories, regular files and hard links, refusing any other
    member, any that would land outside the root, and a manifest other than the one finish recorded, and check the
    whole root against that manifest. Running it again unpacks the same files over themselves."""
    path = local_root / RAW_ARCHIVE
    if not path.is_file():
        raise SystemExit(f"{local_root} has no {RAW_ARCHIVE}; run finish {campaign} into it first")
    try:
        rows = list(ledger_rows(path))
    except UnicodeDecodeError:
        rows = []
    # A finish that failed between writing the manifest and the record leaves a record of the manifest before, which
    # a finish again replaces.
    if (len(rows) != 1 or list(rows[0]) != list(RAW_ARCHIVE_FIELDS) or None in rows[0].values() or
            not rows[0]["key"].startswith(f"campaigns/{campaign}/") or
            rows[0]["manifest_sha256"] != sha256_file(local_root / MANIFEST)):
        raise SystemExit(f"{path} is not the record finish wrote of campaign {campaign}'s root as its {MANIFEST} "
                         f"lists it; run finish {campaign} into {local_root} again")
    record = rows[0]
    uri = f"s3://{record['bucket']}/{record['key']}"
    with tempfile.TemporaryDirectory(prefix="raw-archive-", dir=local_root.parent) as temporary:
        archive = Path(temporary) / "root.tar.zst"
        try:
            aws.get(REGION, record["bucket"], record["key"], archive)
        except CloudError as error:
            raise SystemExit(f"cannot download {uri}: {error}") from error
        if sha256_file(archive) != record["archive_sha256"]:
            raise SystemExit(f"{uri} differs from the archive {RAW_ARCHIVE} records: its SHA-256 differs")
        try:
            with tarfile.open(archive, "r|zst") as members:
                for member in members:
                    # tar keeps a file linked to one before it as a link, as an interrupted write leaves its temporary
                    # file.
                    if not (member.isfile() or member.isdir() or member.islnk()):
                        raise SystemExit(f"{uri} holds {member.name}, which is neither a file, a directory, nor a "
                                         "hard link")
                    # The manifest finish recorded stays, and the archive's must hold the same bytes, under whatever
                    # name reaches it: the one the filter would extract it as, a case variant on a disk that ignores
                    # case, or a link to it.
                    member = tarfile.data_filter(member, local_root)
                    target = local_root / member.name
                    if not (target.exists() and target.samefile(local_root / MANIFEST)):
                        members.extract(member, local_root, filter="data")
                    elif (not member.isfile() or
                          hashlib.file_digest(members.extractfile(member), "sha256").hexdigest() !=
                          record["manifest_sha256"]):
                        raise SystemExit(f"{uri} holds a {MANIFEST} other than the one {RAW_ARCHIVE} records")
        except (tarfile.TarError, OSError) as error:
            raise SystemExit(f"cannot unpack {uri}: {error}") from error
    check_manifest(local_root)
    print(f"restored campaign {campaign}'s raw root from {uri} into {local_root}: {len(root_files(local_root))} files "
          f"match its controller's {MANIFEST}", flush=True)


def teardown(aws, campaign, confirm):
    """Remove the campaign's control host for good, as only Dain does: mark its volume torn down, terminate every host
    of the campaign, including one a replace launched since, deregister them from the load balancer and wait for it
    to forget them, snapshot the volume once they are gone, which is then the campaign root's last copy on EBS, and
    delete the volume and the Grafana credentials. The raw root's archive, if finish ran, is the other copy, which
    teardown neither needs nor touches. The record, the load balancer and its target group belong to the account
    setup.

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
    # The mark comes first, so a replace running beside the teardown starts no controller on the volume after it,
    # unless the replace stalls between the attach and its look. Either way the replace may still put a host there.
    # The hosts go next, with any a replace launched before the mark, so the snapshot holds the root as its controller
    # last wrote it, and the load balancer forgets them, so no other campaign's launch finds them there.
    for volume in volumes:
        aws.tag_volume(REGION, volume.id, TEARDOWN_TAG)
    while hosts := live_hosts(aws, campaign):
        aws.terminate(REGION, [host.id for host in hosts])
        for host in hosts:
            aws.wait(REGION, "instance_terminated", host.id)
    if deregistered := deregister_hosts(aws, found, campaign):
        aws.wait_deregistered(REGION, found["target_group"], deregistered, GRAFANA_PORT)
    for volume in volumes:
        aws.wait(REGION, "volume_available", volume.id)
        snapshot = next((existing.id for existing in aws.snapshots(REGION, tags(campaign))
                         if existing.volume == volume.id), None) or aws.create_snapshot(REGION, volume.id,
                                                                                         tags(campaign))
        aws.wait(REGION, "snapshot_completed", snapshot)
        aws.delete_volume(REGION, volume.id)
    aws.delete_parameters(REGION, grafana_parameters(campaign))
    snapshots = sorted(snapshot.id for snapshot in aws.snapshots(REGION, tags(campaign)))
    if not snapshots:
        copies = "no snapshot holds its root"
    elif len(snapshots) == 1:
        copies = f"snapshot {snapshots[0]} holds its root"
    else:
        copies = f"snapshots {', '.join(snapshots)} hold its root"
    print(f"tore down campaign {campaign}'s control host; {copies} on EBS, and the archive a finish recorded in its "
          f"{RAW_ARCHIVE}, if one ran, is a copy of its raw root", flush=True)
