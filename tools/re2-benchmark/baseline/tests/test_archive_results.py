import csv
import hashlib
import importlib.util
import io
import tarfile
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "archive_results.py"
SPEC = importlib.util.spec_from_file_location("baseline_archive_results", SCRIPT)
ARCHIVE_RESULTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ARCHIVE_RESULTS)


class FakeAws:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def call(self, *arguments, not_found_ok=False):
        self.calls.append((arguments, not_found_ok))
        if not self.responses:
            raise AssertionError(f"unexpected AWS call: {arguments}")
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


class TestArchiveResults(unittest.TestCase):
    def testAwsCliUsesStandardCredentialChainWhenProfileIsAbsent(self):
        result = SimpleNamespace(returncode=0, stdout="{}", stderr="")
        with patch.object(ARCHIVE_RESULTS.subprocess, "run", return_value=result) as run:
            ARCHIVE_RESULTS.AwsCli(region="us-east-1").call("sts", "get-caller-identity")
            ARCHIVE_RESULTS.AwsCli(profile="benchmark", region="us-east-1").call(
                "sts", "get-caller-identity")

        self.assertEqual(
            run.call_args_list[0].args[0],
            ["aws", "--region", "us-east-1", "--output", "json", "sts", "get-caller-identity"])
        self.assertEqual(
            run.call_args_list[1].args[0],
            [
                "aws", "--profile", "benchmark", "--region", "us-east-1",
                "--output", "json", "sts", "get-caller-identity",
            ])

    def testArchiveIsDeterministicAndContainsChecksums(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "source"
            source.mkdir()
            (source / "nested").mkdir()
            (source / "alpha.txt").write_bytes(b"alpha\n")
            executable = source / "nested" / "run.sh"
            executable.write_bytes(b"#!/bin/sh\nexit 0\n")
            executable.chmod(0o751)
            first = root / "first.tar.gz"
            second = root / "second.tar.gz"

            first_details = ARCHIVE_RESULTS.create_archive(source, first)
            (source / "alpha.txt").touch()
            executable.touch()
            second_details = ARCHIVE_RESULTS.create_archive(source, second)

            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(first_details.sha256, second_details.sha256)
            self.assertEqual(first_details.file_count, 2)
            self.assertEqual(
                first_details.source_manifest_sha256,
                hashlib.sha256(first_details.source_manifest).hexdigest())
            with tarfile.open(first, "r:gz") as archive:
                members = archive.getmembers()
                self.assertEqual(
                    [member.name for member in members],
                    [
                        "campaign-results",
                        "campaign-results/alpha.txt",
                        "campaign-results/nested",
                        "campaign-results/nested/run.sh",
                        "campaign-results/ARTIFACT_SHA256.tsv",
                    ])
                for member in members:
                    self.assertEqual(member.uid, 0)
                    self.assertEqual(member.gid, 0)
                    self.assertEqual(member.uname, "")
                    self.assertEqual(member.gname, "")
                    self.assertEqual(member.mtime, 0)
                self.assertEqual(archive.getmember("campaign-results/alpha.txt").mode, 0o644)
                self.assertEqual(archive.getmember("campaign-results/nested/run.sh").mode, 0o755)
                manifest = archive.extractfile(
                    "campaign-results/ARTIFACT_SHA256.tsv").read().decode("utf-8")

            rows = list(csv.DictReader(io.StringIO(manifest), delimiter="\t"))
            self.assertEqual(rows, [
                {
                    "path": "campaign-results/alpha.txt",
                    "size": "6",
                    "sha256": hashlib.sha256(b"alpha\n").hexdigest(),
                },
                {
                    "path": "campaign-results/nested/run.sh",
                    "size": "17",
                    "sha256": hashlib.sha256(b"#!/bin/sh\nexit 0\n").hexdigest(),
                },
            ])

    def testArchiveRefusesExistingOutput(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "source"
            source.mkdir()
            archive = root / "results.tar.gz"
            archive.write_bytes(b"existing")

            with self.assertRaisesRegex(ARCHIVE_RESULTS.ArchiveError, "already exists"):
                ARCHIVE_RESULTS.validate_inputs(
                    source,
                    archive,
                    root / "retrieval.tsv",
                    "a" * 40,
                    "refs/benchmarks/candidate",
                    "campaign-1")

    def testArchiveRefusesSymbolicLinks(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            source = Path(temporary_directory) / "source"
            source.mkdir()
            (source / "target").write_text("value")
            (source / "link").symlink_to("target")

            with self.assertRaisesRegex(ARCHIVE_RESULTS.ArchiveError, "Symbolic links|symbolic links"):
                ARCHIVE_RESULTS.collect_source_entries(source)

    def testObjectRefusalDoesNotUpload(self):
        aws = FakeAws([{"Versions": [{"Key": "key", "VersionId": "existing-version"}]}])

        with self.assertRaisesRegex(ARCHIVE_RESULTS.ArchiveError, "already exists"):
            ARCHIVE_RESULTS.ensure_object_absent(
                aws,
                "bucket",
                "key",
                "123456789012")

        self.assertEqual(len(aws.calls), 1)
        self.assertIn("list-object-versions", aws.calls[0][0])

    def testObjectRefusalDetectsHistoricalDeleteMarker(self):
        aws = FakeAws([{"DeleteMarkers": [{"Key": "key", "VersionId": "deleted-version"}]}])

        with self.assertRaisesRegex(ARCHIVE_RESULTS.ArchiveError, "already exists"):
            ARCHIVE_RESULTS.ensure_object_absent(
                aws,
                "bucket",
                "key",
                "123456789012")

    def testUploadUsesConditionalWriteAndVerifiesExplicitVersion(self):
        details = ARCHIVE_RESULTS.ArchiveDetails(
            Path("archive.tar.gz"),
            "1" * 64,
            "checksum-base64",
            123,
            7,
            b"manifest",
            "2" * 64)
        metadata = {
            "archive-sha256": details.sha256,
            "candidate-commit": "a" * 40,
            "candidate-ref": "refs/benchmarks/candidate",
            "campaign-id": "campaign-1",
            "file-count": "7",
            "source-manifest-sha256": "2" * 64,
        }
        aws = FakeAws([
            {"Versions": []},
            {"VersionId": "version-1", "ChecksumSHA256": "checksum-base64"},
            {
                "VersionId": "version-1",
                "ChecksumSHA256": "checksum-base64",
                "ContentLength": 123,
                "ServerSideEncryption": "AES256",
                "Metadata": metadata,
            },
        ])

        version_id = ARCHIVE_RESULTS.upload_archive(
            aws,
            "bucket",
            "key",
            "123456789012",
            details,
            "a" * 40,
            "refs/benchmarks/candidate",
            "campaign-1")

        self.assertEqual(version_id, "version-1")
        upload = aws.calls[1][0]
        self.assertIn("--if-none-match", upload)
        self.assertEqual(upload[upload.index("--if-none-match") + 1], "*")
        head = aws.calls[2][0]
        self.assertEqual(head[head.index("--version-id") + 1], "version-1")

    def testConditionalUploadRaceRefusesOverwrite(self):
        details = ARCHIVE_RESULTS.ArchiveDetails(
            Path("archive.tar.gz"),
            "1" * 64,
            "checksum-base64",
            123,
            7,
            b"manifest",
            "2" * 64)
        aws = FakeAws([
            {"Versions": []},
            ARCHIVE_RESULTS.AwsError(
                ["aws", "s3api", "put-object"],
                255,
                "An error occurred (PreconditionFailed) when calling PutObject: condition failed"),
        ])

        with self.assertRaisesRegex(ARCHIVE_RESULTS.ArchiveError, "already exists"):
            ARCHIVE_RESULTS.upload_archive(
                aws,
                "bucket",
                "key",
                "123456789012",
                details,
                "a" * 40,
                "refs/benchmarks/candidate",
                "campaign-1")

        self.assertEqual(len(aws.calls), 2)

    def testUploadVerificationAcceptsExactVersionChecksumSizeAndMetadata(self):
        details = ARCHIVE_RESULTS.ArchiveDetails(
            Path("archive.tar.gz"),
            "1" * 64,
            "checksum-base64",
            123,
            7,
            b"manifest",
            "2" * 64)
        metadata = {
            "archive-sha256": details.sha256,
            "candidate-commit": "a" * 40,
            "candidate-ref": "refs/benchmarks/candidate",
            "campaign-id": "campaign-1",
            "file-count": "7",
            "source-manifest-sha256": "2" * 64,
        }
        response = {"VersionId": "version-1", "ChecksumSHA256": "checksum-base64"}
        head = {
            "VersionId": "version-1",
            "ChecksumSHA256": "checksum-base64",
            "ContentLength": 123,
            "ServerSideEncryption": "AES256",
            "Metadata": metadata,
        }

        ARCHIVE_RESULTS.verify_uploaded_object(response, head, details, metadata)

    def testUploadVerificationRejectsAnyMismatch(self):
        details = ARCHIVE_RESULTS.ArchiveDetails(
            Path("archive.tar.gz"),
            "1" * 64,
            "checksum-base64",
            123,
            7,
            b"manifest",
            "2" * 64)
        response = {"VersionId": "version-1", "ChecksumSHA256": "checksum-base64"}
        head = {
            "VersionId": "version-2",
            "ChecksumSHA256": "wrong",
            "ContentLength": 122,
            "ServerSideEncryption": "aws:kms",
            "Metadata": {},
        }

        with self.assertRaisesRegex(ARCHIVE_RESULTS.ArchiveError, "version ID.*stored checksum.*stored size"):
            ARCHIVE_RESULTS.verify_uploaded_object(response, head, details, {})

    def testRetrievalMetadataHasStableRequiredSchema(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "source"
            source.mkdir()
            output = root / "retrieval.tsv"
            details = ARCHIVE_RESULTS.ArchiveDetails(
                root / "archive.tar.gz",
                "1" * 64,
                "checksum-base64",
                123,
                7,
                b"manifest",
                "2" * 64)
            record = ARCHIVE_RESULTS.retrieval_record(
                "bucket",
                "key",
                "version-1",
                details,
                "a" * 40,
                "refs/benchmarks/candidate",
                "campaign-1",
                source)

            ARCHIVE_RESULTS.write_retrieval_metadata(output, record)

            with output.open(newline="") as input_file:
                reader = csv.DictReader(input_file, delimiter="\t")
                self.assertEqual(tuple(reader.fieldnames), ARCHIVE_RESULTS.RETRIEVAL_FIELDS)
                self.assertEqual(list(reader), [record])
            with self.assertRaisesRegex(ARCHIVE_RESULTS.ArchiveError, "already exists"):
                ARCHIVE_RESULTS.write_retrieval_metadata(output, record)

    def testExportedSourceManifestIsWrittenExactlyOnce(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "retrieval.tsv.source-manifest.tsv"
            content = b"path\tsize\tsha256\ncampaign-results/a\t1\t" + b"a" * 64 + b"\n"

            ARCHIVE_RESULTS.write_source_manifest(output, content)

            self.assertEqual(output.read_bytes(), content)
            with self.assertRaisesRegex(ARCHIVE_RESULTS.ArchiveError, "already exists"):
                ARCHIVE_RESULTS.write_source_manifest(output, content)

    def testDeterministicBucketAndObjectNames(self):
        bucket = ARCHIVE_RESULTS.deterministic_bucket_name("123456789012", "us-west-2")
        key = ARCHIVE_RESULTS.object_key("a" * 40, "campaign-1")

        self.assertEqual(bucket, "airlift-regulator-baseline-123456789012-us-west-2")
        self.assertEqual(
            key,
            f"pre-review-baseline/{'a' * 40}/campaign-1/campaign-results.tar.gz")


if __name__ == "__main__":
    unittest.main()
