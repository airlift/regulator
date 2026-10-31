#!/usr/bin/env bash
# A campaign control host's EC2 user data. The Mac-side launch and control-host replace send this script with the
# host's settings exported after its first line, as control_host.py's user_data names them. It installs Dain's ssh
# key, has sshd listen on 443, mounts the campaign volume, unpacks the launch inputs onto it the first time, checks
# out the candidate commits, starts the campaign's controller, and then starts Grafana with credentials it keeps in
# SSM. Nothing here stops the host: it outlives the campaign until teardown.
set -Eeuo pipefail
exec > >(tee -a /var/log/regulator-control-host.log) 2>&1

MOUNT=/regulator
INPUTS=${MOUNT}/control-host
CHECKOUT=${INPUTS}/checkout
VENV=/opt/regulator/venv
PARAMETERS=/regulator-benchmark/campaigns/${CAMPAIGN}

# Every clock time the host prints, the controller's events and status over ssh alike, is Dain's local time.
timedatectl set-timezone America/Los_Angeles

install -d -m 700 -o ec2-user -g ec2-user /home/ec2-user/.ssh
printf '%s\n' "${SSH_PUBLIC_KEY}" > /home/ec2-user/.ssh/authorized_keys
chown ec2-user:ec2-user /home/ec2-user/.ssh/authorized_keys
chmod 600 /home/ec2-user/.ssh/authorized_keys
# The subnet router forwards only tcp 443, so sshd listens there beside 22, before anything slow. SELinux confines
# sshd to its labeled ports; AL2023 leaves it permissive, but an enforcing host needs 443 relabeled.
printf 'Port 22\nPort 443\n' > /etc/ssh/sshd_config.d/60-regulator-ports.conf
if [[ $(getenforce 2> /dev/null) == Enforcing ]]; then
    dnf install -y policycoreutils-python-utils
    semanage port -a -t ssh_port_t -p tcp 443 2> /dev/null || semanage port -m -t ssh_port_t -p tcp 443
fi
systemctl restart sshd

dnf install -y docker git python3.12 python3.12-pip rsync zstd
systemctl enable --now docker

# The launch attaches the volume once the host runs, so it may arrive after this script starts.
device=/dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_${CAMPAIGN_VOLUME//-/}
for _ in $(seq 120); do
    [[ -e ${device} ]] && break
    sleep 5
done
if [[ ! -e ${device} ]]; then
    echo "campaign volume ${CAMPAIGN_VOLUME} was never attached" >&2
    exit 1
fi
# blkid exits 2 when the device holds no file system, which only a new volume lacks.
status=0
blkid -p "${device}" > /dev/null || status=$?
if ((status == 2)); then
    mkfs.xfs "${device}"
elif ((status != 0)); then
    echo "cannot probe campaign volume ${CAMPAIGN_VOLUME}: blkid exited ${status}" >&2
    exit 1
fi
mkdir -p "${MOUNT}"
if ! grep -q " ${MOUNT} " /etc/fstab; then
    printf 'UUID=%s %s xfs defaults,nofail 0 2\n' "$(blkid -s UUID -o value "${device}")" "${MOUNT}" >> /etc/fstab
fi
mountpoint -q "${MOUNT}" || mount "${MOUNT}"
install -d -o ec2-user -g ec2-user "${MOUNT}/campaign"
# The status command names the URL Grafana serves at, which the load balancer keeps across host replacements.
printf '%s\n' "${GRAFANA_URL}" > "${MOUNT}/campaign/grafana-url.txt"
chown ec2-user:ec2-user "${MOUNT}/campaign/grafana-url.txt"
# The telemetry store belongs to the container's own user.
install -d -m 777 "${MOUNT}/lgtm"

# The launch inputs: the controller's command, the candidate commits, the campaign's settings, and its language
# plans. A replaced host finds them on the volume already. They are unpacked beside their place and moved into it
# whole, so a first boot interrupted while unpacking leaves them for the next boot to unpack again.
if [[ ! -f ${INPUTS}/controller.env ]]; then
    aws s3 cp --region "${CONTROL_HOST_REGION}" --no-progress "${LAUNCH_INPUTS_URI}" /var/tmp/launch-inputs.tar.gz
    printf '%s  %s\n' "${LAUNCH_INPUTS_SHA256}" /var/tmp/launch-inputs.tar.gz | sha256sum --check --strict
    rm -rf "${INPUTS}.new" "${INPUTS}"
    install -d -o ec2-user -g ec2-user "${INPUTS}.new"
    sudo -u ec2-user tar -xzf /var/tmp/launch-inputs.tar.gz -C "${INPUTS}.new"
    mv -T "${INPUTS}.new" "${INPUTS}"
    rm /var/tmp/launch-inputs.tar.gz
fi

# The controller runs the first candidate's tooling, which every candidate shares. A first boot interrupted after
# creating the repository may have left it without its remote.
[[ -d ${CHECKOUT}/.git ]] || sudo -u ec2-user git init -q "${CHECKOUT}"
if ! sudo -u ec2-user git -C "${CHECKOUT}" remote get-url origin > /dev/null 2>&1; then
    sudo -u ec2-user git -C "${CHECKOUT}" remote add origin https://github.com/airlift/regulator.git
fi
while read -r commit; do
    if ! sudo -u ec2-user git -C "${CHECKOUT}" cat-file -e "${commit}^{commit}" 2> /dev/null; then
        sudo -u ec2-user git -C "${CHECKOUT}" fetch -q --depth 1 origin "${commit}"
    fi
done < "${INPUTS}/candidate-commits"
sudo -u ec2-user git -C "${CHECKOUT}" checkout -q --detach "$(head -n 1 "${INPUTS}/candidate-commits")"

python3.12 -m venv "${VENV}"
"${VENV}/bin/pip" install --quiet --requirement "${CHECKOUT}/tools/re2-benchmark/requirements.txt"
sudo -u ec2-user "${VENV}/bin/python" "${CHECKOUT}/tools/re2-benchmark/baseline/generate-units.py"

install -m 644 "${CHECKOUT}/tools/re2-benchmark/aws/control-host/regulator-campaign@.service" \
    "${CHECKOUT}/tools/re2-benchmark/aws/control-host/regulator-lgtm.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now "regulator-campaign@${CAMPAIGN}.service"

# Grafana's admin password and the monitor's token live in SSM, so a replaced host serves the same Grafana, whose
# data is on the volume.
parameter()
{
    aws ssm get-parameter --region "${CONTROL_HOST_REGION}" --with-decryption --name "${PARAMETERS}/$1" \
        --query Parameter.Value --output text
}
keep()
{
    aws ssm put-parameter --region "${CONTROL_HOST_REGION}" --type SecureString --name "${PARAMETERS}/$1" \
        --value "$2" > /dev/null
}
if ! password=$(parameter grafana-admin-password 2> /dev/null); then
    password=$(openssl rand -hex 24)
    keep grafana-admin-password "${password}"
fi
install -d -m 700 /etc/regulator
# Grafana builds its links from the URL the load balancer serves it at.
(umask 077 && printf 'GF_SECURITY_ADMIN_PASSWORD=%s\nGF_SERVER_ROOT_URL=%s\n' "${password}" "${GRAFANA_URL}" \
    > /etc/regulator/lgtm.env)
systemctl enable --now regulator-lgtm.service
for _ in $(seq 60); do
    curl -fsS http://127.0.0.1:3000/api/health > /dev/null 2>&1 && break
    sleep 5
done

grafana()
{
    # The admin password reaches curl on its standard input, never its command line.
    printf 'user = "admin:%s"\n' "${password}" |
        curl -fsS --config - -H 'Content-Type: application/json' "$@"
}
if ! parameter grafana-monitor-token > /dev/null 2>&1; then
    account=$(grafana 'http://127.0.0.1:3000/api/serviceaccounts/search?query=monitor' |
        python3 -c 'import json, sys; print(next((account["id"] for account in json.load(sys.stdin)["serviceAccounts"]
                                                  if account["name"] == "monitor"), ""))')
    if [[ -z ${account} ]]; then
        account=$(grafana -d '{"name": "monitor", "role": "Viewer"}' http://127.0.0.1:3000/api/serviceaccounts |
            python3 -c 'import json, sys; print(json.load(sys.stdin)["id"])')
    fi
    token=$(grafana -d "{\"name\": \"monitor-$(date +%s)\"}" \
        "http://127.0.0.1:3000/api/serviceaccounts/${account}/tokens" |
        python3 -c 'import json, sys; print(json.load(sys.stdin)["key"])')
    keep grafana-monitor-token "${token}"
fi
echo "control host ready for campaign ${CAMPAIGN}"
