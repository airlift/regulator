"""The AWS calls a campaign controller makes, each one round trip through the SDK.

AwsCloud is the only path to AWS. Tests drive the controller through a fake with the same methods, so every
method takes and returns plain values, and every SDK failure arrives as CloudError. boto3 is imported on first
use, so the commands that never reach AWS, such as status, run without it.
"""

import base64
import contextlib
import shutil
import threading
from dataclasses import dataclass

SPOT_QUOTA_CODE = "L-34B43A08"
ON_DEMAND_QUOTA_CODE = "L-1216C47A"


class CloudError(RuntimeError):
    """An AWS call failed, or AWS could not be reached."""


@dataclass(frozen=True)
class Network:
    """A region's default VPC, or None without one, and its subnets that give instances public addresses, as
    (subnet, availability zone)."""
    vpc: str
    subnets: tuple


@dataclass(frozen=True)
class Image:
    architecture: str
    state: str
    owner: str
    name: str


@dataclass(frozen=True)
class BucketInfo:
    region: str
    tags: dict


@dataclass(frozen=True)
class Instance:
    """An EC2 instance in any state. lifecycle is spot or on-demand, and launched is in epoch seconds."""
    id: str
    type: str
    lifecycle: str
    state: str
    state_reason: str
    tags: dict
    launched: float


@dataclass(frozen=True)
class SpotRequest:
    """An open or active Spot request; instance_id is empty until it is fulfilled."""
    id: str
    instance_id: str
    type: str
    tags: dict


def tag_dictionary(tags):
    return {tag["Key"]: tag["Value"] for tag in tags or ()}


class AwsCloud:
    """AWS through boto3, in the account and credentials of the environment's standard chain (AWS_PROFILE
    included)."""

    def __init__(self):
        self.session = None
        self.clients = {}
        self.lock = threading.Lock()

    def client(self, service, region):
        with self.lock:
            if self.session is None:
                import boto3
                self.session = boto3.Session()
            key = (service, region)
            if key not in self.clients:
                from botocore.config import Config
                self.clients[key] = self.session.client(service, region_name=region, config=Config(
                    retries={"mode": "standard"}, connect_timeout=10, read_timeout=60))
            return self.clients[key]

    def call(self, service, region, operation, **parameters):
        with translated():
            return getattr(self.client(service, region), operation)(**parameters)

    def pages(self, service, region, operation, key, **parameters):
        with translated():
            paginator = self.client(service, region).get_paginator(operation)
            return [item for page in paginator.paginate(**parameters) for item in page.get(key, ())]

    def account(self, region):
        return self.call("sts", region, "get_caller_identity")["Account"]

    def network(self, region):
        vpcs = self.call("ec2", region, "describe_vpcs", Filters=[{"Name": "is-default", "Values": ["true"]}])["Vpcs"]
        if not vpcs:
            return Network(None, ())
        vpc = vpcs[0]["VpcId"]
        subnets = self.pages("ec2", region, "describe_subnets", "Subnets", Filters=[
            {"Name": "vpc-id", "Values": [vpc]}, {"Name": "state", "Values": ["available"]}])
        return Network(vpc, tuple(sorted((subnet["SubnetId"], subnet["AvailabilityZone"])
                                         for subnet in subnets if subnet.get("MapPublicIpOnLaunch"))))

    def zones_offering(self, region, instance_type):
        offerings = self.pages("ec2", region, "describe_instance_type_offerings", "InstanceTypeOfferings",
                               LocationType="availability-zone",
                               Filters=[{"Name": "instance-type", "Values": [instance_type]}])
        return {offering["Location"] for offering in offerings}

    def image(self, region, ami):
        """The image's identity, or None where the region has no such image."""
        images = self.call("ec2", region, "describe_images", ImageIds=[ami])["Images"]
        if not images:
            return None
        image = images[0]
        return Image(image["Architecture"], image["State"], image["OwnerId"], image.get("Name", ""))

    def vcpu_quota(self, region, market):
        """The region's Standard instance vCPU quota for the market, spot or on-demand."""
        code = SPOT_QUOTA_CODE if market == "spot" else ON_DEMAND_QUOTA_CODE
        return int(self.call("service-quotas", region, "get_service_quota", ServiceCode="ec2",
                             QuotaCode=code)["Quota"]["Value"])

    def vcpus(self, region, types):
        """Each instance type's default vCPUs."""
        if not types:
            return {}
        descriptions = self.pages("ec2", region, "describe_instance_types", "InstanceTypes",
                                  InstanceTypes=sorted(types))
        return {item["InstanceType"]: item["VCpuInfo"]["DefaultVCpus"] for item in descriptions}

    def bucket(self, region, name):
        location = self.call("s3", region, "get_bucket_location", Bucket=name).get("LocationConstraint")
        try:
            tags = self.call("s3", region, "get_bucket_tagging", Bucket=name)["TagSet"]
        except CloudError as error:
            if error_code(error.__cause__) != "NoSuchTagSet":
                raise
            tags = []
        # S3 reports us-east-1 as no constraint and keeps the legacy EU name for eu-west-1.
        return BucketInfo({None: "us-east-1", "": "us-east-1", "EU": "eu-west-1"}.get(location, location),
                          tag_dictionary(tags))

    def placement_scores(self, instance_type, regions, target):
        """Each region's 1-10 Spot placement score for target instances of the type, asked from the first."""
        scores = self.pages("ec2", regions[0], "get_spot_placement_scores", "SpotPlacementScores",
                            InstanceTypes=[instance_type], TargetCapacity=target, RegionNames=list(regions))
        return {score["Region"]: score["Score"] for score in scores}

    def instances(self, region):
        reservations = self.pages("ec2", region, "describe_instances", "Reservations")
        return [Instance(item["InstanceId"], item["InstanceType"],
                         "spot" if item.get("InstanceLifecycle") == "spot" else "on-demand", item["State"]["Name"],
                         item.get("StateReason", {}).get("Code", ""), tag_dictionary(item.get("Tags")),
                         item["LaunchTime"].timestamp())
                for reservation in reservations for item in reservation["Instances"]]

    def spot_requests(self, region):
        requests = self.pages("ec2", region, "describe_spot_instance_requests", "SpotInstanceRequests",
                              Filters=[{"Name": "state", "Values": ["open", "active"]}])
        return [SpotRequest(item["SpotInstanceRequestId"], item.get("InstanceId", ""),
                            item.get("LaunchSpecification", {}).get("InstanceType", ""),
                            tag_dictionary(item.get("Tags")))
                for item in requests]

    def list(self, region, bucket, prefix):
        """Each object under the prefix, by its full key, as (size, modification time in epoch seconds)."""
        objects = self.pages("s3", region, "list_objects_v2", "Contents", Bucket=bucket, Prefix=prefix)
        return {item["Key"]: (item["Size"], item["LastModified"].timestamp()) for item in objects}

    def get(self, region, bucket, key, path):
        body = self.call("s3", region, "get_object", Bucket=bucket, Key=key)["Body"]
        with translated(), contextlib.closing(body), path.open("wb") as output:
            shutil.copyfileobj(body, output)

    def put(self, region, bucket, key, body, *, if_none_match=False, sha256=None):
        """Write the object, bytes or the file at a path, in one request. With if_none_match it is written only
        where no object has the key, and returns False where one does; with sha256, S3 verifies the body against
        it and keeps it as the object's checksum."""
        parameters = {"Bucket": bucket, "Key": key}
        if if_none_match:
            parameters["IfNoneMatch"] = "*"
        if sha256 is not None:
            parameters.update(ChecksumAlgorithm="SHA256",
                              ChecksumSHA256=base64.b64encode(bytes.fromhex(sha256)).decode())
        try:
            if isinstance(body, bytes):
                self.call("s3", region, "put_object", Body=body, **parameters)
            else:
                with body.open("rb") as content:
                    self.call("s3", region, "put_object", Body=content, **parameters)
        except CloudError as error:
            if if_none_match and error_code(error.__cause__) == "PreconditionFailed":
                return False
            raise
        return True

    def head_sha256(self, region, bucket, key):
        """The object's whole-object SHA-256, in hex, or None where there is no object or it has no checksum."""
        try:
            head = self.call("s3", region, "head_object", Bucket=bucket, Key=key, ChecksumMode="ENABLED")
        except CloudError as error:
            if error_code(error.__cause__) in ("404", "NoSuchKey", "NotFound"):
                return None
            raise
        checksum = head.get("ChecksumSHA256")
        return base64.b64decode(checksum).hex() if checksum and "-" not in checksum else None


def error_code(error):
    response = getattr(error, "response", None) or {}
    return response.get("Error", {}).get("Code", "")


@contextlib.contextmanager
def translated():
    """Raise an SDK failure, or a failure to reach AWS, as CloudError."""
    from botocore.exceptions import BotoCoreError, ClientError
    try:
        yield
    except (BotoCoreError, ClientError, OSError) as error:
        raise CloudError(str(error)) from error
