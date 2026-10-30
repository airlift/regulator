import base64
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
    buckets a test gives it, the worker instance profile, and S3 objects by bucket and key. Without scores, placement
    scores fail, as AWS does for a configuration it cannot score. An image a test does not give is Amazon Linux.

    A launch adds a pending instance, or returns the one its client token already launched, unless a test's
    rejections refuse it: each is a function of the region and launch spec that returns a LaunchRejected to raise,
    or None. A terminated instance stays listed as terminated.

    Time is simulated: every call advances the clock by its cost, as its round trip would, and time() and
    monotonic() read it, so a scenario takes the time its calls would. Calls made on a pool's threads, as executor
    gives them, take the time of their thread's lane.
    """

    # Seconds of a round trip: an S3 object call is short, an EC2 description longer, and a placement query longer
    # still.
    COSTS = {"list": 0.05, "get": 0.05, "put": 0.05, "delete": 0.05, "head_sha256": 0.05, "instances": 0.5,
             "spot_requests": 0.5, "placement_scores": 1.0, "run_instance": 1.0}
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
        self.scores = None
        self.region_instances = {}
        self.region_requests = {}
        # By (bucket, key), as (body, modified, SHA-256 or None).
        self.objects = Objects()
        self.profiles = {"regulator-benchmark-worker": ("regulator-benchmark-worker",)}
        self.rejections = []
        # Each launch's instance ID, by region and client token.
        self.launched = {}

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

    def put(self, region, bucket, key, body, *, if_none_match=False, sha256=None):
        self.call("put", region, bucket, key)
        body = body if isinstance(body, bytes) else body.read_bytes()
        if sha256 is not None and hashlib.sha256(body).hexdigest() != sha256:
            raise CloudError(f"BadDigest: s3://{bucket}/{key}")
        if if_none_match and (bucket, key) in self.objects:
            return False
        self.objects[bucket, key] = (body, self.time(), sha256)
        return True

    def delete(self, region, bucket, keys):
        self.call("delete", region, bucket, len(keys))
        for key in keys:
            self.objects.pop((bucket, key), None)

    def head_sha256(self, region, bucket, key):
        self.call("head_sha256", region, bucket, key)
        return self.objects[bucket, key][2] if (bucket, key) in self.objects else None


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
             "LaunchTime": launched}]}]}, {"NextToken": "next"})
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
            cloud.Instance("i-on-demand", "r9g.large", "on-demand", "running", "", {}, launched.timestamp())],
            aws.instances("us-west-2"))
        self.assertEqual([cloud.SpotRequest("sir-open", "", "r8i.large", {})], aws.spot_requests("us-west-2"))
        self.assertEqual({"us-west-2": 9, "us-east-2": 3},
                         aws.placement_scores("r8i.large", ["us-west-2", "us-east-2"], 50))
        self.assertEqual({"us-west-2a", "us-west-2b"}, aws.zones_offering("us-west-2", "r8i.large"))

    def test_bucket_names_its_region_and_tags(self):
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
        archive_sha256 = hashlib.sha256(b"archive").hexdigest()
        checksum = base64.b64encode(bytes.fromhex(archive_sha256)).decode()
        s3.add_response("put_object", {}, {"Bucket": "bucket", "Key": "archive", "Body": ANY,
                                           "ChecksumAlgorithm": "SHA256", "ChecksumSHA256": checksum})
        s3.add_response("head_object", {"ChecksumSHA256": checksum},
                        {"Bucket": "bucket", "Key": "archive", "ChecksumMode": "ENABLED"})
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
            self.assertTrue(aws.put("us-west-2", "bucket", "archive", path, sha256=archive_sha256))
            self.assertEqual(archive_sha256, aws.head_sha256("us-west-2", "bucket", "archive"))
            self.assertIsNone(aws.head_sha256("us-west-2", "bucket", "absent"))

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
