"""The AWS calls a campaign controller makes, each one round trip through the SDK.

AwsCloud is the only path to AWS. Tests drive the controller through a fake with the same methods, so every
method takes and returns plain values, and every SDK failure arrives as CloudError. boto3 is imported on first
use, so the commands that never reach AWS, such as status, run without it.
"""

import contextlib
import email.utils
import shutil
import threading
from dataclasses import dataclass

SPOT_QUOTA_CODE = "L-34B43A08"
ON_DEMAND_QUOTA_CODE = "L-1216C47A"


class CloudError(RuntimeError):
    """An AWS call failed, or AWS could not be reached."""


class LaunchRejected(CloudError):
    """AWS refused a launch, so no instance exists: kind is capacity, quota or price, and code is AWS's error code."""

    def __init__(self, kind, code):
        super().__init__(f"{kind}: {code}")
        self.kind = kind
        self.code = code


class LaunchUnknown(CloudError):
    """A launch failed every attempt without AWS refusing it, so an instance may exist; its tags find it."""


# The RunInstances errors that prove AWS launched nothing, by the kind of shortage each reports. Any other failure, a
# timeout among them, is retried with the same client token, which AWS answers with the instance it launched.
LAUNCH_REJECTIONS = {
    "InsufficientInstanceCapacity": "capacity", "UnfulfillableCapacity": "capacity",
    "VcpuLimitExceeded": "quota", "InstanceLimitExceeded": "quota", "MaxSpotInstanceCountExceeded": "quota",
    "SpotMaxPriceTooLow": "price",
}
LAUNCH_ATTEMPTS = 3


@dataclass(frozen=True)
class Network:
    """A region's default VPC, or None without one, its default security group, and its subnets that give instances
    public addresses, as (subnet, availability zone)."""
    vpc: str
    security_group: str
    subnets: tuple


@dataclass(frozen=True)
class Image:
    architecture: str
    state: str
    owner: str
    name: str
    root_device: str


@dataclass(frozen=True)
class BucketInfo:
    region: str
    tags: dict


@dataclass(frozen=True)
class ObjectHead:
    """An S3 object's SHA-256, in hex, as upload kept it in the object's metadata, or None, and its age in seconds by
    S3's own clock."""
    sha256: str
    age: float


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


@dataclass(frozen=True)
class LaunchSpec:
    """One instance to launch: market is spot or on-demand, max_price the hourly Spot price ceiling, and token the
    client token that makes a resubmitted request return the instance its first submission launched."""
    image: str
    instance_type: str
    root_device: str
    subnet: str
    security_group: str
    profile: str
    market: str
    max_price: str
    user_data: str
    tags: dict
    token: str


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
                # A connection for each of the controller's 16 request threads and 4 acceptance threads.
                self.clients[key] = self.session.client(service, region_name=region, config=Config(
                    retries={"mode": "standard"}, connect_timeout=10, read_timeout=60, max_pool_connections=20))
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
            return Network(None, None, ())
        vpc = vpcs[0]["VpcId"]
        [group] = self.call("ec2", region, "describe_security_groups", Filters=[
            {"Name": "vpc-id", "Values": [vpc]}, {"Name": "group-name", "Values": ["default"]}])["SecurityGroups"]
        subnets = self.pages("ec2", region, "describe_subnets", "Subnets", Filters=[
            {"Name": "vpc-id", "Values": [vpc]}, {"Name": "state", "Values": ["available"]}])
        return Network(vpc, group["GroupId"], tuple(sorted((subnet["SubnetId"], subnet["AvailabilityZone"])
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
        return Image(image["Architecture"], image["State"], image["OwnerId"], image.get("Name", ""),
                     image.get("RootDeviceName", ""))

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

    def create_bucket(self, region, name, tags, lifecycle):
        """Create the bucket in the region: private, encrypted with S3's own keys, tagged, and expiring objects as
        the lifecycle configuration says."""
        location = {} if region == "us-east-1" else {"CreateBucketConfiguration": {"LocationConstraint": region}}
        self.call("s3", region, "create_bucket", Bucket=name, **location)
        self.call("s3", region, "put_public_access_block", Bucket=name, PublicAccessBlockConfiguration=dict.fromkeys(
            ("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets"), True))
        self.call("s3", region, "put_bucket_encryption", Bucket=name, ServerSideEncryptionConfiguration={"Rules": [
            {"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}, "BucketKeyEnabled": True}]})
        self.call("s3", region, "put_bucket_tagging", Bucket=name,
                  Tagging={"TagSet": [{"Key": key, "Value": value} for key, value in sorted(tags.items())]})
        self.call("s3", region, "put_bucket_lifecycle_configuration", Bucket=name, LifecycleConfiguration=lifecycle)

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

    def run_instance(self, region, spec):
        """Launch one instance, terminated when it shuts down, and return its ID. Raises LaunchRejected when AWS
        refuses it, and LaunchUnknown when every attempt failed otherwise."""
        parameters = {
            "ImageId": spec.image, "InstanceType": spec.instance_type, "MinCount": 1, "MaxCount": 1,
            "ClientToken": spec.token, "UserData": spec.user_data,
            "BlockDeviceMappings": [{"DeviceName": spec.root_device, "Ebs": {
                "VolumeSize": 40, "VolumeType": "gp3", "DeleteOnTermination": True, "Encrypted": True}}],
            "MetadataOptions": {"HttpTokens": "required", "HttpEndpoint": "enabled"},
            "IamInstanceProfile": {"Name": spec.profile},
            "InstanceInitiatedShutdownBehavior": "terminate",
            "NetworkInterfaces": [{"DeviceIndex": 0, "SubnetId": spec.subnet, "Groups": [spec.security_group],
                                   "AssociatePublicIpAddress": True}],
            "TagSpecifications": [{"ResourceType": "instance", "Tags": [
                {"Key": key, "Value": value} for key, value in spec.tags.items()]}]}
        if spec.market == "spot":
            parameters["InstanceMarketOptions"] = {"MarketType": "spot", "SpotOptions": {
                "SpotInstanceType": "one-time", "InstanceInterruptionBehavior": "terminate",
                "MaxPrice": spec.max_price}}
        for attempt in range(1, LAUNCH_ATTEMPTS + 1):
            try:
                return self.call("ec2", region, "run_instances", **parameters)["Instances"][0]["InstanceId"]
            except CloudError as error:
                code = error_code(error.__cause__)
                if code in LAUNCH_REJECTIONS:
                    raise LaunchRejected(LAUNCH_REJECTIONS[code], code) from error
                if attempt == LAUNCH_ATTEMPTS:
                    raise LaunchUnknown(str(error)) from error

    def terminate(self, region, ids):
        self.call("ec2", region, "terminate_instances", InstanceIds=list(ids))

    def instance_profile(self, region, name):
        """The roles of the account's instance profile of that name, or None where it has none."""
        try:
            profile = self.call("iam", region, "get_instance_profile", InstanceProfileName=name)["InstanceProfile"]
        except CloudError as error:
            if error_code(error.__cause__) == "NoSuchEntity":
                return None
            raise
        return tuple(role["RoleName"] for role in profile["Roles"])

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

    def put(self, region, bucket, key, body, *, if_none_match=False):
        """Write the object's bytes in one request. With if_none_match it is written only where no object has the
        key, and returns False where one does."""
        parameters = {"Bucket": bucket, "Key": key, "Body": body}
        if if_none_match:
            parameters["IfNoneMatch"] = "*"
        try:
            self.call("s3", region, "put_object", **parameters)
        except CloudError as error:
            if if_none_match and error_code(error.__cause__) == "PreconditionFailed":
                return False
            raise
        return True

    def upload(self, region, bucket, key, path, sha256):
        """Write the file at the path with the SDK's managed transfer, in parallel parts once it is large, each part
        checked by S3 against its own SHA-256. A multipart object's checksum is not the whole file's, so sha256, the
        whole file's, is kept in the object's metadata."""
        with translated():
            self.client("s3", region).upload_file(str(path), bucket, key, ExtraArgs={
                "Metadata": {"sha256": sha256}, "ChecksumAlgorithm": "SHA256"})

    def delete(self, region, bucket, keys):
        """Delete the objects, a thousand to a request, as S3 allows."""
        keys = sorted(keys)
        for start in range(0, len(keys), 1000):
            failed = self.call("s3", region, "delete_objects", Bucket=bucket, Delete={
                "Objects": [{"Key": key} for key in keys[start:start + 1000]], "Quiet": True}).get("Errors")
            if failed:
                raise CloudError(f"could not delete {len(failed)} objects from s3://{bucket}, such as "
                                 f"{failed[0]['Key']}: {failed[0].get('Code')}")

    def head(self, region, bucket, key):
        """The object's head, or None where there is no object."""
        try:
            head = self.call("s3", region, "head_object", Bucket=bucket, Key=key)
        except CloudError as error:
            if error_code(error.__cause__) in ("404", "NoSuchKey", "NotFound"):
                return None
            raise
        now = email.utils.parsedate_to_datetime(head["ResponseMetadata"]["HTTPHeaders"]["date"])
        return ObjectHead(head.get("Metadata", {}).get("sha256"), (now - head["LastModified"]).total_seconds())


def error_code(error):
    response = getattr(error, "response", None) or {}
    return response.get("Error", {}).get("Code", "")


@contextlib.contextmanager
def translated():
    """Raise an SDK failure, a managed transfer's among them, or a failure to reach AWS, as CloudError."""
    from boto3.exceptions import Boto3Error
    from botocore.exceptions import BotoCoreError, ClientError
    try:
        yield
    except (Boto3Error, BotoCoreError, ClientError, OSError) as error:
        raise CloudError(str(error)) from error
