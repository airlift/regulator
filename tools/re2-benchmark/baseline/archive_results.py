#!/usr/bin/env python3

import argparse
import base64
import csv
import gzip
import hashlib
import io
import json
import os
import re
import stat
import subprocess
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path


DEFAULT_REGION = "us-west-2"
ARCHIVE_ROOT = "campaign-results"
EMBEDDED_MANIFEST = "ARTIFACT_SHA256.tsv"
KEY_PREFIX = "pre-review-baseline"
RETRIEVAL_FIELDS = (
    "s3_uri",
    "version_id",
    "archive_sha256",
    "candidate_commit",
    "candidate_ref",
    "campaign_id",
    "source_directory",
    "file_count",
    "source_manifest_sha256",
)


class ArchiveError(RuntimeError):
    pass


class AwsError(ArchiveError):
    def __init__(self, command, returncode, stderr):
        self.command = command
        self.returncode = returncode
        self.stderr = stderr
        super().__init__(f"AWS CLI failed ({returncode}): {stderr.strip()}")


@dataclass(frozen=True)
class ArchiveDetails:
    path: Path
    sha256: str
    checksum_sha256: str
    size: int
    file_count: int
    source_manifest: bytes = b""
    source_manifest_sha256: str = ""


class HashingReader:
    def __init__(self, input_file):
        self.input_file = input_file
        self.digest = hashlib.sha256()
        self.bytes_read = 0

    def read(self, size=-1):
        data = self.input_file.read(size)
        self.digest.update(data)
        self.bytes_read += len(data)
        return data


class AwsCli:
    def __init__(self, profile=None, region=DEFAULT_REGION):
        self.profile = profile
        self.region = region

    def call(self, *arguments, not_found_ok=False):
        command = ["aws", "--region", self.region, "--output", "json", *arguments]
        if self.profile:
            command[1:1] = ["--profile", self.profile]
        environment = os.environ.copy()
        environment["AWS_PAGER"] = ""
        result = subprocess.run(command, text=True, capture_output=True, env=environment)
        if result.returncode:
            if not_found_ok and is_not_found(result.stderr):
                return None
            raise AwsError(command, result.returncode, result.stderr)
        if not result.stdout.strip():
            return {}
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError as error:
            raise ArchiveError(f"AWS CLI returned invalid JSON for {' '.join(arguments)}") from error


def parse_arguments():
    parser = argparse.ArgumentParser(
        description="Archive an accepted baseline campaign in permanent versioned S3 storage")
    parser.add_argument("source_directory", type=Path)
    parser.add_argument("archive", type=Path)
    parser.add_argument("retrieval_metadata", type=Path)
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--candidate-ref", required=True)
    parser.add_argument("--campaign-id", required=True)
    parser.add_argument("--bucket")
    parser.add_argument("--profile")
    parser.add_argument("--region", default=DEFAULT_REGION)
    return parser.parse_args()


def is_not_found(stderr):
    return any(marker in stderr for marker in (
        "(404)",
        "Not Found",
        "NoSuchBucket",
        "NoSuchBucketPolicy",
        "NoSuchLifecycleConfiguration",
        "NoSuchKey",
    ))


def validate_identifier(value, name):
    if not value or any(character in value for character in "\t\r\n"):
        raise ArchiveError(f"{name} must be nonempty and contain no tabs or newlines")


def validate_inputs(source_directory, archive, retrieval_metadata, candidate_commit, candidate_ref, campaign_id):
    if source_directory.is_symlink() or not source_directory.is_dir():
        raise ArchiveError(f"source directory is not a directory: {source_directory}")
    if archive.exists():
        raise ArchiveError(f"archive already exists: {archive}")
    if retrieval_metadata.exists():
        raise ArchiveError(f"retrieval metadata already exists: {retrieval_metadata}")
    source_manifest = retrieval_metadata.with_name(retrieval_metadata.name + ".source-manifest.tsv")
    if source_manifest.exists():
        raise ArchiveError(f"source manifest already exists: {source_manifest}")
    if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", candidate_commit):
        raise ArchiveError("candidate commit must be a lowercase 40- or 64-character Git object ID")
    if not re.fullmatch(r"refs/benchmarks/[A-Za-z0-9._/-]+", candidate_ref):
        raise ArchiveError("candidate ref must be under refs/benchmarks")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", campaign_id):
        raise ArchiveError("campaign ID must contain 1-128 letters, digits, periods, underscores, or hyphens")

    source = source_directory.resolve()
    validate_identifier(str(source), "source directory")
    for output in (archive, retrieval_metadata, source_manifest):
        output_path = output.resolve()
        try:
            output_path.relative_to(source)
        except ValueError:
            continue
        raise ArchiveError(f"output must be outside the archived source directory: {output}")


def normalized_tar_info(name, source_stat=None, directory=False, size=0):
    information = tarfile.TarInfo(name)
    information.uid = 0
    information.gid = 0
    information.uname = ""
    information.gname = ""
    information.mtime = 0
    information.pax_headers = {}
    if directory:
        information.type = tarfile.DIRTYPE
        information.mode = 0o755
        information.size = 0
    else:
        information.type = tarfile.REGTYPE
        information.mode = 0o755 if source_stat and source_stat.st_mode & 0o111 else 0o644
        information.size = size
    return information


def collect_source_entries(source_directory):
    entries = []
    for path in source_directory.rglob("*"):
        relative_path = path.relative_to(source_directory)
        archive_path = relative_path.as_posix()
        if any(character in archive_path for character in "\t\r\n"):
            raise ArchiveError(f"artifact path contains a tab or newline: {relative_path}")
        if archive_path == EMBEDDED_MANIFEST:
            raise ArchiveError(f"source uses reserved archive path: {relative_path}")
        path_stat = path.lstat()
        if stat.S_ISLNK(path_stat.st_mode):
            raise ArchiveError(f"symbolic links are not allowed in campaign results: {relative_path}")
        if not (stat.S_ISDIR(path_stat.st_mode) or stat.S_ISREG(path_stat.st_mode)):
            raise ArchiveError(f"special files are not allowed in campaign results: {relative_path}")
        entries.append((archive_path, path, path_stat))
    return sorted(entries, key=lambda entry: entry[0].encode("utf-8"))


def verify_source_tree_unchanged(source_directory, initial_root_stat, initial_entries):
    final_root_stat = source_directory.stat()
    final_entries = collect_source_entries(source_directory)
    if not same_file_state(initial_root_stat, final_root_stat):
        raise ArchiveError("campaign result tree changed while creating archive")
    if [entry[0] for entry in initial_entries] != [entry[0] for entry in final_entries]:
        raise ArchiveError("campaign result tree changed while creating archive")
    for initial, final in zip(initial_entries, final_entries):
        if not same_file_state(initial[2], final[2]):
            raise ArchiveError(f"campaign result changed while creating archive: {initial[1]}")


def add_source_file(output_tar, archive_path, path, initial_stat):
    with path.open("rb") as input_file:
        opened_stat = os.fstat(input_file.fileno())
        if not same_file_state(initial_stat, opened_stat):
            raise ArchiveError(f"campaign result changed while creating archive: {path}")
        reader = HashingReader(input_file)
        output_tar.addfile(
            normalized_tar_info(archive_path, opened_stat, size=opened_stat.st_size),
            reader)
        final_stat = os.fstat(input_file.fileno())
    if reader.bytes_read != opened_stat.st_size or not same_file_state(opened_stat, final_stat):
        raise ArchiveError(f"campaign result changed while creating archive: {path}")
    return reader.digest.hexdigest(), reader.bytes_read


def same_file_state(left, right):
    return (
        left.st_dev,
        left.st_ino,
        left.st_size,
        left.st_mtime_ns,
        left.st_ctime_ns,
    ) == (
        right.st_dev,
        right.st_ino,
        right.st_size,
        right.st_mtime_ns,
        right.st_ctime_ns,
    )


def build_embedded_manifest(file_records):
    output = io.StringIO(newline="")
    writer = csv.writer(output, delimiter="\t", lineterminator="\n")
    writer.writerow(("path", "size", "sha256"))
    writer.writerows(file_records)
    return output.getvalue().encode("utf-8")


def create_archive(source_directory, archive):
    source_directory = source_directory.resolve()
    source_root_stat = source_directory.stat()
    entries = collect_source_entries(source_directory)
    archive.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="wb", dir=archive.parent, prefix=f".{archive.name}.", delete=False) as raw_output:
            temporary_path = Path(raw_output.name)
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw_output, mtime=0, compresslevel=9) as compressed:
                with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as output_tar:
                    output_tar.addfile(normalized_tar_info(ARCHIVE_ROOT, directory=True))
                    file_records = []
                    for relative_path, path, path_stat in entries:
                        archive_path = f"{ARCHIVE_ROOT}/{relative_path}"
                        if stat.S_ISDIR(path_stat.st_mode):
                            output_tar.addfile(normalized_tar_info(archive_path, directory=True))
                        else:
                            digest, size = add_source_file(output_tar, archive_path, path, path_stat)
                            file_records.append((archive_path, str(size), digest))
                    manifest = build_embedded_manifest(file_records)
                    output_tar.addfile(
                        normalized_tar_info(
                            f"{ARCHIVE_ROOT}/{EMBEDDED_MANIFEST}", size=len(manifest)),
                        io.BytesIO(manifest))
            verify_source_tree_unchanged(source_directory, source_root_stat, entries)
            raw_output.flush()
            os.fsync(raw_output.fileno())
        temporary_path.chmod(0o644)
        try:
            os.link(temporary_path, archive)
        except FileExistsError as error:
            raise ArchiveError(f"archive already exists: {archive}") from error
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)

    digest = hashlib.sha256()
    with archive.open("rb") as input_file:
        for data in iter(lambda: input_file.read(1024 * 1024), b""):
            digest.update(data)
    archive_size = archive.stat().st_size
    return ArchiveDetails(
        archive,
        digest.hexdigest(),
        base64.b64encode(digest.digest()).decode("ascii"),
        archive_size,
        sum(1 for _, _, path_stat in entries if stat.S_ISREG(path_stat.st_mode)),
        manifest,
        hashlib.sha256(manifest).hexdigest())


def deterministic_bucket_name(account_id, region):
    if not re.fullmatch(r"[0-9]{12}", account_id):
        raise ArchiveError(f"AWS account ID is invalid: {account_id!r}")
    if not re.fullmatch(r"[a-z0-9-]+", region):
        raise ArchiveError(f"AWS region is invalid: {region!r}")
    return f"airlift-regulator-baseline-{account_id}-{region}"


def validate_bucket_name(bucket):
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket):
        raise ArchiveError(f"S3 bucket name is invalid: {bucket!r}")


def ensure_bucket(aws, bucket, account_id, region):
    existing = aws.call(
        "s3api", "head-bucket",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id,
        not_found_ok=True)
    if existing is None:
        arguments = ["s3api", "create-bucket", "--bucket", bucket, "--object-ownership", "BucketOwnerEnforced"]
        if region != "us-east-1":
            arguments.extend(("--create-bucket-configuration", f"LocationConstraint={region}"))
        aws.call(*arguments)

    aws.call(
        "s3api", "put-bucket-ownership-controls",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id,
        "--ownership-controls", '{"Rules":[{"ObjectOwnership":"BucketOwnerEnforced"}]}')

    public_access = {
        "BlockPublicAcls": True,
        "IgnorePublicAcls": True,
        "BlockPublicPolicy": True,
        "RestrictPublicBuckets": True,
    }
    aws.call(
        "s3api", "put-public-access-block",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id,
        "--public-access-block-configuration", json.dumps(public_access, separators=(",", ":")))
    aws.call(
        "s3api", "put-bucket-versioning",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id,
        "--versioning-configuration", "Status=Enabled")
    encryption = {
        "Rules": [{
            "ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"},
        }],
    }
    aws.call(
        "s3api", "put-bucket-encryption",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id,
        "--server-side-encryption-configuration", json.dumps(encryption, separators=(",", ":")))
    verify_bucket(aws, bucket, account_id, region)


def verify_bucket(aws, bucket, account_id, region):
    location = aws.call(
        "s3api", "get-bucket-location",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id).get("LocationConstraint")
    actual_region = "us-east-1" if location is None else location
    if actual_region != region:
        raise ArchiveError(f"bucket {bucket} is in {actual_region}, expected {region}")

    owner = aws.call(
        "s3api", "get-bucket-ownership-controls",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id)
    ownership_rules = owner.get("OwnershipControls", {}).get("Rules", [])
    if not any(rule.get("ObjectOwnership") == "BucketOwnerEnforced" for rule in ownership_rules):
        raise ArchiveError(f"bucket {bucket} does not enforce bucket-owner object ownership")

    public_access = aws.call(
        "s3api", "get-public-access-block",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id).get("PublicAccessBlockConfiguration", {})
    if not all(public_access.get(field) is True for field in (
            "BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets")):
        raise ArchiveError(f"bucket {bucket} does not block all public access")

    policy_status = aws.call(
        "s3api", "get-bucket-policy-status",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id,
        not_found_ok=True)
    if policy_status is not None and policy_status.get("PolicyStatus", {}).get("IsPublic") is not False:
        raise ArchiveError(f"bucket {bucket} has a public or unverifiable bucket policy")

    lifecycle = aws.call(
        "s3api", "get-bucket-lifecycle-configuration",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id,
        not_found_ok=True)
    if lifecycle is not None:
        for rule in lifecycle.get("Rules", []):
            if any(field in rule for field in (
                    "Expiration", "NoncurrentVersionExpiration", "NoncurrentVersionTransitions", "Transitions")):
                raise ArchiveError(f"bucket {bucket} has a lifecycle rule that can remove or transition evidence")

    versioning = aws.call(
        "s3api", "get-bucket-versioning",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id)
    if versioning.get("Status") != "Enabled":
        raise ArchiveError(f"bucket {bucket} does not have versioning enabled")

    encryption = aws.call(
        "s3api", "get-bucket-encryption",
        "--bucket", bucket,
        "--expected-bucket-owner", account_id)
    rules = encryption.get("ServerSideEncryptionConfiguration", {}).get("Rules", [])
    if not any(
            rule.get("ApplyServerSideEncryptionByDefault", {}).get("SSEAlgorithm") == "AES256"
            for rule in rules):
        raise ArchiveError(f"bucket {bucket} does not have AES256 default encryption")


def object_key(candidate_commit, campaign_id):
    return f"{KEY_PREFIX}/{candidate_commit}/{campaign_id}/campaign-results.tar.gz"


def ensure_object_absent(aws, bucket, key, account_id):
    history = aws.call(
        "s3api", "list-object-versions",
        "--bucket", bucket,
        "--prefix", key,
        "--expected-bucket-owner", account_id)
    versions = history.get("Versions", []) + history.get("DeleteMarkers", [])
    if any(version.get("Key") == key for version in versions):
        raise ArchiveError(f"immutable artifact already exists: s3://{bucket}/{key}")


def upload_archive(aws, bucket, key, account_id, details, candidate_commit, candidate_ref, campaign_id):
    if not re.fullmatch(r"[0-9a-f]{64}", details.source_manifest_sha256):
        raise ArchiveError("archive source manifest SHA-256 is invalid")
    ensure_object_absent(aws, bucket, key, account_id)
    metadata = {
        "archive-sha256": details.sha256,
        "candidate-commit": candidate_commit,
        "candidate-ref": candidate_ref,
        "campaign-id": campaign_id,
        "file-count": str(details.file_count),
        "source-manifest-sha256": details.source_manifest_sha256,
    }
    try:
        response = aws.call(
            "s3api", "put-object",
            "--bucket", bucket,
            "--key", key,
            "--body", str(details.path),
            "--expected-bucket-owner", account_id,
            "--if-none-match", "*",
            "--checksum-algorithm", "SHA256",
            "--checksum-sha256", details.checksum_sha256,
            "--server-side-encryption", "AES256",
            "--metadata", json.dumps(metadata, sort_keys=True, separators=(",", ":")))
    except AwsError as error:
        if "PreconditionFailed" in error.stderr or "(412)" in error.stderr:
            raise ArchiveError(f"immutable artifact already exists: s3://{bucket}/{key}") from error
        raise

    version_id = response.get("VersionId")
    if not version_id or version_id == "null":
        raise ArchiveError("S3 upload did not return a version ID")
    if response.get("ChecksumSHA256") != details.checksum_sha256:
        raise ArchiveError("S3 upload response checksum does not match the local archive")

    head = aws.call(
        "s3api", "head-object",
        "--bucket", bucket,
        "--key", key,
        "--version-id", version_id,
        "--checksum-mode", "ENABLED",
        "--expected-bucket-owner", account_id)
    verify_uploaded_object(response, head, details, metadata)
    return version_id


def verify_uploaded_object(response, head, details, expected_metadata):
    version_id = response.get("VersionId")
    checks = {
        "version ID": head.get("VersionId") == version_id,
        "returned checksum": response.get("ChecksumSHA256") == details.checksum_sha256,
        "stored checksum": head.get("ChecksumSHA256") == details.checksum_sha256,
        "stored size": head.get("ContentLength") == details.size,
        "server-side encryption": head.get("ServerSideEncryption") == "AES256",
        "object metadata": head.get("Metadata") == expected_metadata,
    }
    failures = [name for name, valid in checks.items() if not valid]
    if failures:
        raise ArchiveError(f"uploaded artifact verification failed: {', '.join(failures)}")


def retrieval_record(bucket, key, version_id, details, candidate_commit, candidate_ref, campaign_id, source_directory):
    return {
        "s3_uri": f"s3://{bucket}/{key}",
        "version_id": version_id,
        "archive_sha256": details.sha256,
        "candidate_commit": candidate_commit,
        "candidate_ref": candidate_ref,
        "campaign_id": campaign_id,
        "source_directory": str(source_directory.resolve()),
        "file_count": str(details.file_count),
        "source_manifest_sha256": details.source_manifest_sha256,
    }


def write_retrieval_metadata(path, record):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", newline="", dir=path.parent,
                prefix=f".{path.name}.", delete=False) as output_file:
            temporary_path = Path(output_file.name)
            writer = csv.DictWriter(output_file, fieldnames=RETRIEVAL_FIELDS, delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerow(record)
            output_file.flush()
            os.fsync(output_file.fileno())
        temporary_path.chmod(0o644)
        try:
            os.link(temporary_path, path)
        except FileExistsError as error:
            raise ArchiveError(f"retrieval metadata already exists: {path}") from error
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def write_source_manifest(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="wb", dir=path.parent, prefix=f".{path.name}.", delete=False) as output_file:
            temporary_path = Path(output_file.name)
            output_file.write(content)
            output_file.flush()
            os.fsync(output_file.fileno())
        temporary_path.chmod(0o644)
        try:
            os.link(temporary_path, path)
        except FileExistsError as error:
            raise ArchiveError(f"source manifest already exists: {path}") from error
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def main():
    arguments = parse_arguments()
    validate_inputs(
        arguments.source_directory,
        arguments.archive,
        arguments.retrieval_metadata,
        arguments.candidate_commit,
        arguments.candidate_ref,
        arguments.campaign_id)

    details = create_archive(arguments.source_directory, arguments.archive)
    write_source_manifest(
        arguments.retrieval_metadata.with_name(arguments.retrieval_metadata.name + ".source-manifest.tsv"),
        details.source_manifest)
    aws = AwsCli(arguments.profile, arguments.region)
    identity = aws.call("sts", "get-caller-identity")
    account_id = identity.get("Account", "")
    if not re.fullmatch(r"[0-9]{12}", account_id):
        raise ArchiveError(f"AWS account ID is invalid: {account_id!r}")
    bucket = arguments.bucket or deterministic_bucket_name(account_id, arguments.region)
    validate_bucket_name(bucket)
    ensure_bucket(aws, bucket, account_id, arguments.region)
    key = object_key(arguments.candidate_commit, arguments.campaign_id)
    version_id = upload_archive(
        aws,
        bucket,
        key,
        account_id,
        details,
        arguments.candidate_commit,
        arguments.candidate_ref,
        arguments.campaign_id)
    record = retrieval_record(
        bucket,
        key,
        version_id,
        details,
        arguments.candidate_commit,
        arguments.candidate_ref,
        arguments.campaign_id,
        arguments.source_directory)
    write_retrieval_metadata(arguments.retrieval_metadata, record)
    print(record["s3_uri"])
    print(f"version_id={version_id}")
    print(f"archive_sha256={details.sha256}")
    print(f"source_manifest_sha256={details.source_manifest_sha256}")
    print(f"retrieval_metadata={arguments.retrieval_metadata}")


if __name__ == "__main__":
    main()
