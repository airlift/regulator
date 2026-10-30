import base64
import hashlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import cloud  # noqa: E402
from cloud import CloudError  # noqa: E402

AMAZON_LINUX = cloud.Image("x86_64", "available", "137112412989", "al2023-test")


class FakeCloud:
    """AWS as AwsCloud reaches it, simulated: one account whose regions each have a default VPC with a public subnet
    in zone a that offers every instance type, the images, quotas, instances, Spot requests, placement scores and
    buckets a test gives it, and S3 objects by bucket and key. Without scores, placement scores fail, as AWS does
    for a configuration it cannot score.

    Time is simulated: every call advances the clock by its cost, as its round trip would, and time() and
    monotonic() read it, so a scenario takes the time its calls would.
    """

    # Seconds of a round trip: an S3 object call is short, an EC2 description longer, and a placement query longer
    # still.
    COSTS = {"list": 0.05, "get": 0.05, "put": 0.05, "head_sha256": 0.05, "instances": 0.5, "spot_requests": 0.5,
             "placement_scores": 1.0}
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
        self.objects = {}

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
        return self.networks.get(region, cloud.Network(f"vpc-{region}", ((f"subnet-{region}a", f"{region}a"),)))

    def zones_offering(self, region, instance_type):
        self.call("zones_offering", region, instance_type)
        return set(self.offerings.get((region, instance_type), {f"{region}a"}))

    def image(self, region, ami):
        self.call("image", region, ami)
        return self.images.get((region, ami))

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

    def spot_requests(self, region):
        self.call("spot_requests", region)
        return list(self.region_requests.get(region, ()))

    def list(self, region, bucket, prefix):
        self.call("list", region, bucket, prefix)
        return {key: (len(body), modified) for (name, key), (body, modified, _) in self.objects.items()
                if name == bucket and key.startswith(prefix)}

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
        ec2.add_response("describe_subnets", {"Subnets": [
            {"SubnetId": "subnet-b", "AvailabilityZone": "us-west-2b", "MapPublicIpOnLaunch": True},
            {"SubnetId": "subnet-private", "AvailabilityZone": "us-west-2c", "MapPublicIpOnLaunch": False}]},
            {"Filters": [{"Name": "vpc-id", "Values": ["vpc-default"]}, {"Name": "state", "Values": ["available"]}]})
        ec2.add_response("describe_vpcs", {"Vpcs": []}, {"Filters": [{"Name": "is-default", "Values": ["true"]}]})
        ec2.add_response("describe_images", {"Images": [
            {"Architecture": "x86_64", "State": "available", "OwnerId": "137112412989", "Name": "al2023-test"}]},
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
        self.assertEqual(cloud.Network("vpc-default", (("subnet-b", "us-west-2b"),)), aws.network("us-west-2"))
        self.assertEqual(cloud.Network(None, ()), aws.network("us-west-2"))
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

    def test_commands_that_never_reach_aws_run_without_the_sdk(self):
        result = subprocess.run([sys.executable, "-c", "import sys; import cloud; cloud.AwsCloud(); "
                                 "sys.exit('boto3' in sys.modules or 'botocore' in sys.modules)"],
                                cwd=Path(cloud.__file__).parent)
        self.assertEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
