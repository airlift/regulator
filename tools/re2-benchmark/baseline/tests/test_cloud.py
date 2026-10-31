import bisect
import hashlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from concurrent.futures import Future
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import cloud  # noqa: E402
from cloud import CloudError, LaunchRejected  # noqa: E402

AMAZON_LINUX = cloud.Image("x86_64", "available", "137112412989", "al2023-test", "/dev/xvda")


class Objects(dict):
    """S3's objects by (bucket, key), with their keys kept in order, so a listing reads only its prefix's keys."""

    def __init__(self):
        super().__init__()
        self.keys_in_order = []

    def __setitem__(self, name, value):
        if name not in self:
            bisect.insort(self.keys_in_order, name)
        super().__setitem__(name, value)

    def __delitem__(self, name):
        super().__delitem__(name)
        del self.keys_in_order[bisect.bisect_left(self.keys_in_order, name)]

    def pop(self, name, *default):
        if name in self:
            del self.keys_in_order[bisect.bisect_left(self.keys_in_order, name)]
        return super().pop(name, *default)

    def under(self, bucket, prefix):
        """The keys of the bucket under the prefix, in order."""
        index = bisect.bisect_left(self.keys_in_order, (bucket, prefix))
        while index < len(self.keys_in_order):
            name, key = self.keys_in_order[index]
            if name != bucket or not key.startswith(prefix):
                return
            yield key
            index += 1


class FakeFuture(Future):
    """A task's result on the simulated clock: done once the clock reaches the time its task finished, and waiting for
    it advances the clock to then."""

    def __init__(self, cloud, finished):
        super().__init__()
        self.cloud = cloud
        self.finished = finished

    def done(self):
        return self.finished <= self.cloud.clock

    def result(self, timeout=None):
        self.cloud.clock = max(self.cloud.clock, self.finished)
        return super().result(timeout)

    def exception(self, timeout=None):
        self.cloud.clock = max(self.cloud.clock, self.finished)
        return super().exception(timeout)


class FakeExecutor:
    """A thread pool on the simulated clock: each of its threads is a lane of simulated time. A task runs at once, on
    the lane free soonest, from when it was submitted or when that lane is free, whichever is later, and its calls
    take that lane's time rather than the caller's; so tasks on separate lanes overlap, and tasks beyond the pool's
    size queue, as on real threads."""

    def __init__(self, cloud, max_workers):
        self.cloud = cloud
        self.lanes = [0.0] * max_workers

    def submit(self, function, *arguments, **keywords):
        lane = min(range(len(self.lanes)), key=self.lanes.__getitem__)
        submitted = self.cloud.clock
        self.cloud.clock = max(submitted, self.lanes[lane])
        try:
            value, error = function(*arguments, **keywords), None
        except BaseException as exception:
            value, error = None, exception
        self.lanes[lane], self.cloud.clock = self.cloud.clock, submitted
        future = FakeFuture(self.cloud, self.lanes[lane])
        if error is None:
            future.set_result(value)
        else:
            future.set_exception(error)
        return future

    def shutdown(self, wait=True, *, cancel_futures=False):
        pass


class FakeCloud:
    """AWS as AwsCloud reaches it, simulated: one account whose regions each have a default VPC with a public subnet
    in zone a that offers every instance type, the images, quotas, instances, Spot requests, placement scores and
    buckets a test gives it or the controller creates, each created one's lifecycle configuration, the worker instance
    profile, and S3 objects by bucket and key. Without scores, placement
    scores fail, as AWS does for a configuration it cannot score. An image a test does not give is Amazon Linux.

    A launch adds a pending instance, or returns the one its client token already launched, unless a test's
    rejections refuse it: each is a function of the region and launch spec that returns a LaunchRejected to raise,
    or None. A terminated instance stays listed as terminated.

    A control host's account also has the SSM parameters, security groups and subnets a test gives it, and the
    volumes, snapshots and Route 53 records the commands create. Waiting for an instance to run starts it, and
    waiting for a volume to be available finds it so once no running instance holds it.

    Time is simulated: every call advances the clock by its cost, as its round trip would, and time() and
    monotonic() read it, so a scenario takes the time its calls would. Calls made on a pool's threads, as executor
    gives them, take the time of their thread's lane.
    """

    # Seconds of a round trip: an S3 object call is short, an EC2 description longer, and a placement query longer
    # still.
    COSTS = {"list": 0.05, "get": 0.05, "put": 0.05, "upload": 0.05, "delete": 0.05, "head": 0.05,
             "instances": 0.5, "spot_requests": 0.5, "placement_scores": 1.0, "run_instance": 1.0}
    DEFAULT_COST = 0.2
    # The simulated epoch's start, 2026-10-26 07:33:20 UTC.
    EPOCH = 1_793_000_000
    MONOTONIC = 10_000

    def __init__(self):
        self.clock = 0.0
        self.calls = []
        self.account_id = "123456789012"
        # By region; a region not given has its default network.
        self.networks = {}
        # By (region, instance type); a pair not given is offered in the region's zone a.
        self.offerings = {}
        # By (region, image ID).
        self.images = {}
        # By (region, market).
        self.quotas = {}
        self.default_quota = 1000
        self.instance_vcpus = {"r8i.large": 2, "r8i.2xlarge": 8, "r9g.large": 2, "r9g.2xlarge": 8}
        self.buckets = {}
        self.lifecycles = {}
        self.scores = None
        self.region_instances = {}
        self.region_requests = {}
        # By (bucket, key), as (body, modified, the SHA-256 an upload kept in its metadata or None).
        self.objects = Objects()
        self.profiles = {"regulator-benchmark-worker": ("regulator-benchmark-worker",)}
        self.rejections = []
        # Each launch's instance ID, by region and client token.
        self.launched = {}
        # By name.
        self.parameters = {}
        # By (VPC, name), as (ID, inbound rules).
        self.security_groups = {}
        # Each subnet's zone.
        self.subnets = {}
        self.region_volumes = {}
        # Each volume's instance, by volume ID.
        self.attachments = {}
        self.snapshots = []
        # Each A record's address, by (zone, name).
        self.records = {}
        # Each control host launch's parameters, by instance ID.
        self.control_hosts = {}

    def executor(self, max_workers):
        return FakeExecutor(self, max_workers)

    def time(self):
        return self.EPOCH + self.clock

    def monotonic(self):
        return self.MONOTONIC + self.clock

    def call(self, operation, *details):
        self.clock += self.COSTS.get(operation, self.DEFAULT_COST)
        self.calls.append((operation, *details))

    def account(self, region):
        self.call("account", region)
        return self.account_id

    def network(self, region):
        self.call("network", region)
        return self.region_network(region)

    def region_network(self, region):
        return self.networks.get(region, cloud.Network(f"vpc-{region}", f"sg-{region}",
                                                       ((f"subnet-{region}a", f"{region}a"),)))

    def zones_offering(self, region, instance_type):
        self.call("zones_offering", region, instance_type)
        return set(self.offerings.get((region, instance_type), {f"{region}a"}))

    def image(self, region, ami):
        self.call("image", region, ami)
        return self.images.get((region, ami), AMAZON_LINUX)

    def vcpu_quota(self, region, market):
        self.call("vcpu_quota", region, market)
        return self.quotas.get((region, market), self.default_quota)

    def vcpus(self, region, types):
        self.call("vcpus", region, *sorted(types))
        return {name: self.instance_vcpus[name] for name in types if name in self.instance_vcpus}

    def bucket(self, region, name):
        self.call("bucket", region, name)
        if name not in self.buckets:
            raise CloudError(f"NoSuchBucket: {name}")
        return self.buckets[name]

    def create_bucket(self, region, name, tags, lifecycle):
        self.call("create_bucket", region, name)
        self.buckets[name] = cloud.BucketInfo(region, dict(tags))
        self.lifecycles[name] = lifecycle

    def placement_scores(self, instance_type, regions, target):
        self.call("placement_scores", instance_type, tuple(regions), target)
        if self.scores is None:
            raise CloudError("placement scores are unavailable")
        return {region: score for region, score in self.scores.items() if region in regions}

    def instances(self, region):
        self.call("instances", region)
        return list(self.region_instances.get(region, ()))

    def run_instance(self, region, spec):
        self.call("run_instance", region, spec)
        if (region, spec.token) not in self.launched:
            for rejection in self.rejections:
                if (error := rejection(region, spec)) is not None:
                    raise error
            instance = cloud.Instance(f"i-{len(self.launched) + 1}", spec.instance_type, spec.market, "pending", "",
                                      dict(spec.tags), self.time())
            self.region_instances.setdefault(region, []).append(instance)
            self.launched[region, spec.token] = instance.id
        return self.launched[region, spec.token]

    def terminate(self, region, ids):
        self.call("terminate", region, *ids)
        self.region_instances[region] = [
            replace(instance, state="terminated", state_reason="Client.UserInitiatedShutdown")
            if instance.id in ids else instance for instance in self.region_instances.get(region, ())]

    def instance_profile(self, region, name):
        self.call("instance_profile", region, name)
        return self.profiles.get(name)

    def spot_requests(self, region):
        self.call("spot_requests", region)
        return list(self.region_requests.get(region, ()))

    def list(self, region, bucket, prefix):
        self.call("list", region, bucket, prefix)
        return {key: (len(self.objects[bucket, key][0]), self.objects[bucket, key][1])
                for key in self.objects.under(bucket, prefix)}

    def get(self, region, bucket, key, path):
        self.call("get", region, bucket, key)
        if (bucket, key) not in self.objects:
            raise CloudError(f"NoSuchKey: s3://{bucket}/{key}")
        path.write_bytes(self.objects[bucket, key][0])

    def put(self, region, bucket, key, body, *, if_none_match=False):
        self.call("put", region, bucket, key)
        if if_none_match and (bucket, key) in self.objects:
            return False
        self.objects[bucket, key] = (body, self.time(), None)
        return True

    def upload(self, region, bucket, key, path, sha256):
        self.call("upload", region, bucket, key)
        self.objects[bucket, key] = (path.read_bytes(), self.time(), sha256)

    def delete(self, region, bucket, keys):
        self.call("delete", region, bucket, len(keys))
        for key in keys:
            self.objects.pop((bucket, key), None)

    def head(self, region, bucket, key):
        self.call("head", region, bucket, key)
        if (bucket, key) not in self.objects:
            return None
        _, modified, sha256 = self.objects[bucket, key]
        return cloud.ObjectHead(sha256, self.time() - modified)

    def run_control_host(self, region, image, instance_type, root_device, subnet, security_group, profile, user_data,
                         tags):
        self.call("run_control_host", region, subnet)
        instance = cloud.Instance(f"i-control-{len(self.control_hosts) + 1}", instance_type, "on-demand",
                                  "pending", "", dict(tags), self.time(), f"10.0.0.{len(self.control_hosts) + 1}")
        self.region_instances.setdefault(region, []).append(instance)
        self.control_hosts[instance.id] = {"image": image, "subnet": subnet, "security_group": security_group,
                                           "profile": profile, "user_data": user_data}
        return instance.id, instance.address

    def wait(self, region, condition, resource):
        self.call("wait", region, condition, resource)
        if condition == "instance_running":
            self.region_instances[region] = [replace(instance, state="running") if instance.id == resource else
                                             instance for instance in self.region_instances[region]]
        elif condition == "volume_available":
            holder = self.attachments.get(resource)
            if any(instance.id == holder and instance.state != "terminated"
                   for instance in self.region_instances.get(region, ())):
                raise CloudError(f"Waiter VolumeAvailable failed: {resource} is in use")
            self.attachments.pop(resource, None)
            self.region_volumes[region] = [replace(volume, state="available") if volume.id == resource else volume
                                           for volume in self.region_volumes[region]]

    def parameter(self, region, name):
        self.call("parameter", region, name)
        return self.parameters.get(name)

    def delete_parameters(self, region, names):
        self.call("delete_parameters", region, *names)
        for name in names:
            self.parameters.pop(name, None)

    def subnet_zones(self, region, subnets):
        self.call("subnet_zones", region, *subnets)
        return {subnet: self.subnets[subnet] for subnet in subnets}

    def security_group(self, region, vpc, name):
        self.call("security_group", region, vpc, name)
        return self.security_groups.get((vpc, name))

    def volumes(self, region, tags):
        self.call("volumes", region)
        return [volume for volume in self.region_volumes.get(region, ())
                if all(volume.tags.get(key) == value for key, value in tags.items())]

    def create_volume(self, region, zone, gib, tags):
        self.call("create_volume", region, zone, gib)
        volume = cloud.Volume(f"vol-{len(self.region_volumes.get(region, ())) + 1}", zone, "creating", dict(tags))
        self.region_volumes.setdefault(region, []).append(volume)
        return volume.id

    def attach_volume(self, region, volume, instance, device):
        self.call("attach_volume", region, volume, instance, device)
        self.attachments[volume] = instance
        self.region_volumes[region] = [replace(item, state="in-use") if item.id == volume else item
                                       for item in self.region_volumes[region]]

    def delete_volume(self, region, volume):
        self.call("delete_volume", region, volume)
        self.region_volumes[region] = [item for item in self.region_volumes[region] if item.id != volume]

    def create_snapshot(self, region, volume, tags):
        self.call("create_snapshot", region, volume)
        self.snapshots.append((volume, dict(tags)))
        return f"snap-{len(self.snapshots)}"

    def change_record(self, region, zone, action, name, address):
        self.call("change_record", action, name, address)
        if action == "UPSERT":
            self.records[zone, name] = address
            return True
        if self.records.get((zone, name)) != address:
            return False
        del self.records[zone, name]
        return True


class TestAwsCloud(unittest.TestCase):
    """The adapter's requests and its reading of the answers, against the SDK's stubbed responses."""

    def setUp(self):
        # Requests are signed before the stubber answers them, so they need credentials, never real ones.
        environment = patch.dict(os.environ, {"AWS_ACCESS_KEY_ID": "testing", "AWS_SECRET_ACCESS_KEY": "testing",
                                              "AWS_CONFIG_FILE": os.devnull, "AWS_SHARED_CREDENTIALS_FILE": os.devnull})
        environment.start()
        self.addCleanup(environment.stop)
        os.environ.pop("AWS_PROFILE", None)
        os.environ.pop("AWS_SESSION_TOKEN", None)
        from botocore.stub import Stubber
        self.cloud = cloud.AwsCloud()
        self.stubbers = {}

        def stub(service, region="us-west-2"):
            if (service, region) not in self.stubbers:
                stubber = Stubber(self.cloud.client(service, region))
                stubber.activate()
                self.addCleanup(stubber.assert_no_pending_responses)
                self.addCleanup(stubber.deactivate)
                self.stubbers[service, region] = stubber
            return self.stubbers[service, region]

        self.stub = stub

    def test_reads_describe_the_account_its_regions_and_its_fleet(self):
        ec2 = self.stub("ec2")
        self.stub("sts").add_response("get_caller_identity", {"Account": "123456789012"})
        ec2.add_response("describe_vpcs", {"Vpcs": [{"VpcId": "vpc-default"}]},
                         {"Filters": [{"Name": "is-default", "Values": ["true"]}]})
        ec2.add_response("describe_security_groups", {"SecurityGroups": [{"GroupId": "sg-default"}]}, {"Filters": [
            {"Name": "vpc-id", "Values": ["vpc-default"]}, {"Name": "group-name", "Values": ["default"]}]})
        ec2.add_response("describe_subnets", {"Subnets": [
            {"SubnetId": "subnet-b", "AvailabilityZone": "us-west-2b", "MapPublicIpOnLaunch": True},
            {"SubnetId": "subnet-private", "AvailabilityZone": "us-west-2c", "MapPublicIpOnLaunch": False}]},
            {"Filters": [{"Name": "vpc-id", "Values": ["vpc-default"]}, {"Name": "state", "Values": ["available"]}]})
        ec2.add_response("describe_vpcs", {"Vpcs": []}, {"Filters": [{"Name": "is-default", "Values": ["true"]}]})
        ec2.add_response("describe_images", {"Images": [
            {"Architecture": "x86_64", "State": "available", "OwnerId": "137112412989", "Name": "al2023-test",
             "RootDeviceName": "/dev/xvda"}]},
            {"ImageIds": ["ami-test"]})
        ec2.add_response("describe_images", {"Images": []}, {"ImageIds": ["ami-gone"]})
        self.stub("service-quotas").add_response(
            "get_service_quota", {"Quota": {"Value": 640.0}}, {"ServiceCode": "ec2", "QuotaCode": "L-34B43A08"})
        ec2.add_response("describe_instance_types", {"InstanceTypes": [
            {"InstanceType": "r8i.large", "VCpuInfo": {"DefaultVCpus": 2}}]}, {"InstanceTypes": ["r8i.large"]})
        launched = datetime(2026, 10, 30, tzinfo=timezone.utc)
        # One description answers in two pages.
        ec2.add_response("describe_instances", {"Reservations": [{"Instances": [
            {"InstanceId": "i-spot", "InstanceType": "r8i.large", "InstanceLifecycle": "spot",
             "State": {"Name": "terminated"}, "StateReason": {"Code": "Server.SpotInstanceTermination"},
             "Tags": [{"Key": "HostEpoch", "Value": "7"}], "LaunchTime": launched}]}], "NextToken": "next"}, {})
        ec2.add_response("describe_instances", {"Reservations": [{"Instances": [
            {"InstanceId": "i-on-demand", "InstanceType": "r9g.large", "State": {"Name": "running"},
             "LaunchTime": launched, "PrivateIpAddress": "10.0.0.9"}]}]}, {"NextToken": "next"})
        ec2.add_response("describe_spot_instance_requests", {"SpotInstanceRequests": [
            {"SpotInstanceRequestId": "sir-open", "LaunchSpecification": {"InstanceType": "r8i.large"}}]},
            {"Filters": [{"Name": "state", "Values": ["open", "active"]}]})
        ec2.add_response("get_spot_placement_scores", {"SpotPlacementScores": [
            {"Region": "us-west-2", "Score": 9}, {"Region": "us-east-2", "Score": 3}]},
            {"InstanceTypes": ["r8i.large"], "TargetCapacity": 50, "RegionNames": ["us-west-2", "us-east-2"]})
        ec2.add_response("describe_instance_type_offerings", {"InstanceTypeOfferings": [
            {"Location": "us-west-2a"}, {"Location": "us-west-2b"}]}, {
            "LocationType": "availability-zone", "Filters": [{"Name": "instance-type", "Values": ["r8i.large"]}]})

        aws = self.cloud
        self.assertEqual("123456789012", aws.account("us-west-2"))
        self.assertEqual(cloud.Network("vpc-default", "sg-default", (("subnet-b", "us-west-2b"),)),
                         aws.network("us-west-2"))
        self.assertEqual(cloud.Network(None, None, ()), aws.network("us-west-2"))
        self.assertEqual(AMAZON_LINUX, aws.image("us-west-2", "ami-test"))
        self.assertIsNone(aws.image("us-west-2", "ami-gone"))
        self.assertEqual(640, aws.vcpu_quota("us-west-2", "spot"))
        self.assertEqual({"r8i.large": 2}, aws.vcpus("us-west-2", {"r8i.large"}))
        self.assertEqual({}, aws.vcpus("us-west-2", set()))
        self.assertEqual([
            cloud.Instance("i-spot", "r8i.large", "spot", "terminated", "Server.SpotInstanceTermination",
                           {"HostEpoch": "7"}, launched.timestamp()),
            cloud.Instance("i-on-demand", "r9g.large", "on-demand", "running", "", {}, launched.timestamp(),
                           "10.0.0.9")],
            aws.instances("us-west-2"))
        self.assertEqual([cloud.SpotRequest("sir-open", "", "r8i.large", {})], aws.spot_requests("us-west-2"))
        self.assertEqual({"us-west-2": 9, "us-east-2": 3},
                         aws.placement_scores("r8i.large", ["us-west-2", "us-east-2"], 50))
        self.assertEqual({"us-west-2a", "us-west-2b"}, aws.zones_offering("us-west-2", "r8i.large"))

    def test_bucket_is_created_private_and_names_its_region_and_tags(self):
        lifecycle = {"Rules": [{"ID": "ExpireCampaignArtifacts", "Status": "Enabled", "Filter": {"Prefix": ""},
                                "Expiration": {"Days": 1}}]}
        # A bucket outside us-east-1 names its region.
        for region, location in (("us-east-1", {}), ("eu-west-1", {"CreateBucketConfiguration": {
                "LocationConstraint": "eu-west-1"}})):
            s3 = self.stub("s3", region)
            s3.add_response("create_bucket", {}, {"Bucket": "new", **location})
            s3.add_response("put_public_access_block", {}, {"Bucket": "new", "PublicAccessBlockConfiguration": {
                "BlockPublicAcls": True, "IgnorePublicAcls": True, "BlockPublicPolicy": True,
                "RestrictPublicBuckets": True}})
            s3.add_response("put_bucket_encryption", {}, {"Bucket": "new", "ServerSideEncryptionConfiguration": {
                "Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"},
                           "BucketKeyEnabled": True}]}})
            s3.add_response("put_bucket_tagging", {}, {"Bucket": "new", "Tagging": {"TagSet": [
                {"Key": "Project", "Value": "regulator-benchmark"}]}})
            s3.add_response("put_bucket_lifecycle_configuration", {},
                            {"Bucket": "new", "LifecycleConfiguration": lifecycle})
            self.cloud.create_bucket(region, "new", {"Project": "regulator-benchmark"}, lifecycle)
        s3 = self.stub("s3", "eu-west-1")
        # S3 keeps the legacy EU name for eu-west-1, and a bucket without tags has no tag set.
        s3.add_response("get_bucket_location", {"LocationConstraint": "EU"}, {"Bucket": "ireland"})
        s3.add_response("get_bucket_tagging", {"TagSet": [{"Key": "Project", "Value": "regulator-benchmark"}]},
                        {"Bucket": "ireland"})
        s3.add_response("get_bucket_location", {}, {"Bucket": "virginia"})
        s3.add_client_error("get_bucket_tagging", "NoSuchTagSet", http_status_code=404,
                            expected_params={"Bucket": "virginia"})
        self.assertEqual(cloud.BucketInfo("eu-west-1", {"Project": "regulator-benchmark"}),
                         self.cloud.bucket("eu-west-1", "ireland"))
        self.assertEqual(cloud.BucketInfo("us-east-1", {}), self.cloud.bucket("eu-west-1", "virginia"))

    def test_objects_are_listed_read_and_written_once(self):
        from botocore.response import StreamingBody
        from botocore.stub import ANY
        s3 = self.stub("s3")
        modified = datetime(2026, 10, 30, tzinfo=timezone.utc)
        s3.add_response("list_objects_v2", {"Contents": [{"Key": "session/results/heartbeat/1.json", "Size": 12,
                                                          "LastModified": modified}]},
                        {"Bucket": "bucket", "Prefix": "session/results/"})
        s3.add_response("get_object", {"Body": StreamingBody(io.BytesIO(b"lease body"), 10)},
                        {"Bucket": "bucket", "Key": "lease"})
        s3.add_response("put_object", {}, {"Bucket": "bucket", "Key": "lease", "Body": b"{}", "IfNoneMatch": "*"})
        # A conditional write that finds the key reports it, and any other failure is a CloudError.
        s3.add_client_error("put_object", "PreconditionFailed", http_status_code=412)
        s3.add_client_error("put_object", "SlowDown", http_status_code=503)
        # A managed upload keeps the whole file's SHA-256 in the object's metadata, which head reads back.
        archive_sha256 = hashlib.sha256(b"archive").hexdigest()
        s3.add_response("put_object", {}, {"Bucket": "bucket", "Key": "archive", "Body": ANY,
                                           "Metadata": {"sha256": archive_sha256}, "ChecksumAlgorithm": "SHA256"})
        s3.add_client_error("put_object", "SlowDown", http_status_code=503)
        # Its age is by S3's clock, the response's date, whatever the controller's clock says.
        s3.add_response("head_object", {"Metadata": {"sha256": archive_sha256}, "LastModified": modified,
                                        "ResponseMetadata": {"HTTPHeaders": {"date": "Sat, 31 Oct 2026 00:00:30 GMT"}}},
                        {"Bucket": "bucket", "Key": "archive"})
        s3.add_client_error("head_object", "404", http_status_code=404)

        aws = self.cloud
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "object"
            self.assertEqual({"session/results/heartbeat/1.json": (12, modified.timestamp())},
                             aws.list("us-west-2", "bucket", "session/results/"))
            aws.get("us-west-2", "bucket", "lease", path)
            self.assertEqual(b"lease body", path.read_bytes())
            self.assertTrue(aws.put("us-west-2", "bucket", "lease", b"{}", if_none_match=True))
            self.assertFalse(aws.put("us-west-2", "bucket", "lease", b"{}", if_none_match=True))
            with self.assertRaisesRegex(CloudError, "SlowDown"):
                aws.put("us-west-2", "bucket", "lease", b"{}", if_none_match=True)
            path.write_bytes(b"archive")
            aws.upload("us-west-2", "bucket", "archive", path, archive_sha256)
            with self.assertRaisesRegex(CloudError, "SlowDown"):
                aws.upload("us-west-2", "bucket", "archive", path, archive_sha256)
            self.assertEqual(cloud.ObjectHead(archive_sha256, 86430), aws.head("us-west-2", "bucket", "archive"))
            self.assertIsNone(aws.head("us-west-2", "bucket", "absent"))

    def test_launch_classifies_refusals_and_resubmits_an_unanswered_request(self):
        ec2 = self.stub("ec2")
        spec = cloud.LaunchSpec("ami-test", "r8i.large", "/dev/xvda", "subnet-a", "sg-default",
                                "regulator-benchmark-worker", "spot", "0.2", "#!/bin/bash\n",
                                {"Session": "campaign-e1"}, "token")
        request = {
            "ImageId": "ami-test", "InstanceType": "r8i.large", "MinCount": 1, "MaxCount": 1, "ClientToken": "token",
            "UserData": "#!/bin/bash\n",
            "BlockDeviceMappings": [{"DeviceName": "/dev/xvda", "Ebs": {
                "VolumeSize": 40, "VolumeType": "gp3", "DeleteOnTermination": True, "Encrypted": True}}],
            "MetadataOptions": {"HttpTokens": "required", "HttpEndpoint": "enabled"},
            "IamInstanceProfile": {"Name": "regulator-benchmark-worker"},
            "InstanceInitiatedShutdownBehavior": "terminate",
            "NetworkInterfaces": [{"DeviceIndex": 0, "SubnetId": "subnet-a", "Groups": ["sg-default"],
                                   "AssociatePublicIpAddress": True}],
            "TagSpecifications": [{"ResourceType": "instance", "Tags": [{"Key": "Session", "Value": "campaign-e1"}]}],
            "InstanceMarketOptions": {"MarketType": "spot", "SpotOptions": {
                "SpotInstanceType": "one-time", "InstanceInterruptionBehavior": "terminate", "MaxPrice": "0.2"}}}
        # An unanswered request is resubmitted with its token until AWS names the instance.
        ec2.add_client_error("run_instances", "InternalError", http_status_code=500, expected_params=request)
        ec2.add_response("run_instances", {"Instances": [{"InstanceId": "i-launched"}]}, request)
        on_demand = {key: value for key, value in request.items() if key != "InstanceMarketOptions"}
        ec2.add_client_error("run_instances", "VcpuLimitExceeded", expected_params=on_demand)
        ec2.add_client_error("run_instances", "InsufficientInstanceCapacity")
        for _ in range(cloud.LAUNCH_ATTEMPTS):
            ec2.add_client_error("run_instances", "InternalError", http_status_code=500)
        ec2.add_response("terminate_instances", {}, {"InstanceIds": ["i-launched"]})

        aws = self.cloud
        self.assertEqual("i-launched", aws.run_instance("us-west-2", spec))
        with self.assertRaises(LaunchRejected) as rejected:
            aws.run_instance("us-west-2", replace(spec, market="on-demand"))
        self.assertEqual(("quota", "VcpuLimitExceeded"), (rejected.exception.kind, rejected.exception.code))
        with self.assertRaises(LaunchRejected) as rejected:
            aws.run_instance("us-west-2", spec)
        self.assertEqual("capacity", rejected.exception.kind)
        with self.assertRaises(cloud.LaunchUnknown):
            aws.run_instance("us-west-2", spec)
        aws.terminate("us-west-2", ["i-launched"])

    def test_control_host_is_launched_private_onto_its_volume_and_named_by_its_record(self):
        ec2, ssm, route53 = self.stub("ec2"), self.stub("ssm"), self.stub("route53")
        tags = [{"Key": "Campaign", "Value": "c"}]
        ssm.add_response("get_parameter", {"Parameter": {"Value": '{"vpc": "vpc-a"}'}},
                         {"Name": "/network", "WithDecryption": True})
        ssm.add_client_error("get_parameter", "ParameterNotFound", http_status_code=400)
        ssm.add_response("delete_parameters", {}, {"Names": ["/a", "/b"]})
        ec2.add_response("describe_subnets", {"Subnets": [{"SubnetId": "subnet-a", "AvailabilityZone": "us-west-2a"}]},
                         {"SubnetIds": ["subnet-a"]})
        # Every source of an inbound rule shows, so a rule admitting more than the router's CIDR is seen.
        groups = {"Filters": [{"Name": "vpc-id", "Values": ["vpc-a"]}, {"Name": "group-name", "Values": ["host"]}]}
        ec2.add_response("describe_security_groups", {"SecurityGroups": [{"GroupId": "sg-host", "IpPermissions": [
            {"IpProtocol": "tcp", "FromPort": 22, "ToPort": 22, "IpRanges": [{"CidrIp": "10.0.0.0/16"}],
             "Ipv6Ranges": [{"CidrIpv6": "::/0"}]}]}]}, groups)
        ec2.add_response("describe_security_groups", {"SecurityGroups": []}, groups)
        ec2.add_response("describe_volumes", {"Volumes": [
            {"VolumeId": "vol-a", "AvailabilityZone": "us-west-2a", "State": "in-use", "Tags": tags},
            {"VolumeId": "vol-gone", "AvailabilityZone": "us-west-2a", "State": "deleted", "Tags": tags}]},
            {"Filters": [{"Name": "tag:Campaign", "Values": ["c"]}]})
        ec2.add_response("create_volume", {"VolumeId": "vol-new"}, {
            "AvailabilityZone": "us-west-2a", "Size": 200, "VolumeType": "gp3", "Encrypted": True,
            "TagSpecifications": [{"ResourceType": "volume", "Tags": tags}]})
        ec2.add_response("run_instances", {"Instances": [{"InstanceId": "i-host", "PrivateIpAddress": "10.0.1.5"}]}, {
            "ImageId": "ami-test", "InstanceType": "m7i.large", "MinCount": 1, "MaxCount": 1,
            "UserData": "#!/bin/bash\n",
            "BlockDeviceMappings": [{"DeviceName": "/dev/xvda", "Ebs": {
                "VolumeSize": 30, "VolumeType": "gp3", "DeleteOnTermination": True, "Encrypted": True}}],
            "MetadataOptions": {"HttpTokens": "required", "HttpEndpoint": "enabled"},
            "IamInstanceProfile": {"Name": "host-profile"}, "InstanceInitiatedShutdownBehavior": "stop",
            "NetworkInterfaces": [{"DeviceIndex": 0, "SubnetId": "subnet-a", "Groups": ["sg-host"],
                                   "AssociatePublicIpAddress": False}],
            "TagSpecifications": [{"ResourceType": "instance", "Tags": tags}]})
        ec2.add_response("describe_instances", {"Reservations": [{"Instances": [{"State": {"Name": "running"}}]}]},
                         {"InstanceIds": ["i-host"]})
        ec2.add_response("attach_volume", {}, {"VolumeId": "vol-new", "InstanceId": "i-host", "Device": "/dev/sdf"})
        ec2.add_response("create_snapshot", {"SnapshotId": "snap-a"}, {
            "VolumeId": "vol-new", "TagSpecifications": [{"ResourceType": "snapshot", "Tags": tags}]})
        ec2.add_response("delete_volume", {}, {"VolumeId": "vol-new"})
        record = {"Name": "host.example", "Type": "A", "TTL": 60, "ResourceRecords": [{"Value": "10.0.1.5"}]}
        route53.add_response("change_resource_record_sets", {"ChangeInfo": {
            "Id": "change", "Status": "PENDING", "SubmittedAt": datetime(2026, 10, 30)}}, {
            "HostedZoneId": "zone", "ChangeBatch": {"Changes": [{"Action": "UPSERT", "ResourceRecordSet": record}]}})
        # A deletion that finds the record naming another host leaves it.
        route53.add_client_error("change_resource_record_sets", "InvalidChangeBatch", http_status_code=400)

        aws = self.cloud
        self.assertEqual('{"vpc": "vpc-a"}', aws.parameter("us-west-2", "/network"))
        self.assertIsNone(aws.parameter("us-west-2", "/absent"))
        aws.delete_parameters("us-west-2", ["/a", "/b"])
        self.assertEqual({"subnet-a": "us-west-2a"}, aws.subnet_zones("us-west-2", ["subnet-a"]))
        self.assertEqual(("sg-host", {("tcp", 22, 22, "10.0.0.0/16"), ("tcp", 22, 22, "::/0")}),
                         aws.security_group("us-west-2", "vpc-a", "host"))
        self.assertIsNone(aws.security_group("us-west-2", "vpc-a", "host"))
        self.assertEqual([cloud.Volume("vol-a", "us-west-2a", "in-use", {"Campaign": "c"})],
                         aws.volumes("us-west-2", {"Campaign": "c"}))
        self.assertEqual("vol-new", aws.create_volume("us-west-2", "us-west-2a", 200, {"Campaign": "c"}))
        self.assertEqual(("i-host", "10.0.1.5"), aws.run_control_host(
            "us-west-2", "ami-test", "m7i.large", "/dev/xvda", "subnet-a", "sg-host", "host-profile", "#!/bin/bash\n",
            {"Campaign": "c"}))
        aws.wait("us-west-2", "instance_running", "i-host")
        aws.attach_volume("us-west-2", "vol-new", "i-host", "/dev/sdf")
        self.assertEqual("snap-a", aws.create_snapshot("us-west-2", "vol-new", {"Campaign": "c"}))
        aws.delete_volume("us-west-2", "vol-new")
        self.assertTrue(aws.change_record("us-west-2", "zone", "UPSERT", "host.example", "10.0.1.5"))
        self.assertFalse(aws.change_record("us-west-2", "zone", "DELETE", "host.example", "10.0.1.5"))

    def test_worker_profile_and_prefix_deletion(self):
        iam = self.stub("iam")
        iam.add_response("get_instance_profile", {"InstanceProfile": {
            "Path": "/", "InstanceProfileName": "worker", "InstanceProfileId": "AIPAEXAMPLEEXAMPLE1",
            "Arn": "arn:aws:iam::123456789012:instance-profile/worker", "CreateDate": datetime(2026, 10, 30),
            "Roles": [{"Path": "/", "RoleName": "worker-role", "RoleId": "AROAEXAMPLEEXAMPLE1",
                       "Arn": "arn:aws:iam::123456789012:role/worker-role", "CreateDate": datetime(2026, 10, 30)}]}},
            {"InstanceProfileName": "worker"})
        iam.add_client_error("get_instance_profile", "NoSuchEntity", http_status_code=404)
        s3 = self.stub("s3")
        keys = [f"session/results/{index:04d}" for index in range(1001)]
        s3.add_response("delete_objects", {}, {"Bucket": "bucket", "Delete": {
            "Objects": [{"Key": key} for key in keys[:1000]], "Quiet": True}})
        s3.add_response("delete_objects", {}, {"Bucket": "bucket", "Delete": {
            "Objects": [{"Key": keys[1000]}], "Quiet": True}})
        s3.add_response("delete_objects", {"Errors": [{"Key": keys[0], "Code": "AccessDenied"}]})

        aws = self.cloud
        self.assertEqual(("worker-role",), aws.instance_profile("us-west-2", "worker"))
        self.assertIsNone(aws.instance_profile("us-west-2", "absent"))
        aws.delete("us-west-2", "bucket", reversed(keys))
        with self.assertRaisesRegex(CloudError, "AccessDenied"):
            aws.delete("us-west-2", "bucket", keys[:1])

    def test_commands_that_never_reach_aws_run_without_the_sdk(self):
        result = subprocess.run([sys.executable, "-c", "import sys; import cloud; cloud.AwsCloud(); "
                                 "sys.exit('boto3' in sys.modules or 'botocore' in sys.modules)"],
                                cwd=Path(cloud.__file__).parent)
        self.assertEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
