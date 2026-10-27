import base64
import contextlib
import csv
import hashlib
import importlib.util
import io
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).parents[1] / "run-campaign.py"
AWS_RUNNER = SCRIPT.parents[1] / "aws" / "run-campaign.sh"
sys.path.insert(0, str(SCRIPT.parent))
import test_fleet_budget  # noqa: E402
import unit_dispatch  # noqa: E402
from test_unit_session import UnitCampaign, UnitFixture, store_host_calibration  # noqa: E402
SPEC = importlib.util.spec_from_file_location("baseline_run_campaign", SCRIPT)
RUN_CAMPAIGN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN_CAMPAIGN)


def shell_function(source, name):
    """Returns one function definition from a shell script's source."""
    start = source.index(f"\n{name}()\n") + 1
    return source[start:source.index("\n}\n", start) + 3]


def shell_functions(source, *names):
    return "".join(shell_function(source, name) for name in names)


class FakeProcess:
    def __init__(self, process_id, status=None, wait_action=None, poll_action=None):
        self.pid = process_id
        self.status = status
        self.wait_action = wait_action
        self.poll_action = poll_action
        self.signals = []
        self.wait_timeouts = []
        self.killed = False

    def poll(self):
        if self.poll_action is not None:
            self.poll_action()
            self.poll_action = None
        return self.status

    def send_signal(self, sent_signal):
        self.signals.append(sent_signal)

    def wait(self, timeout):
        self.wait_timeouts.append(timeout)
        if self.wait_action is not None:
            self.wait_action()
            self.wait_action = None
        if self.status is None:
            self.status = 143
        return self.status

    def kill(self):
        self.killed = True
        self.status = -9


class TestRunCampaign(unittest.TestCase):
    def setUp(self):
        self.platform = RUN_CAMPAIGN.Platform(
            "r8i",
            "intel",
            "r8i.2xlarge",
            8,
            "ami-test",
            "https://example.invalid/jdk.tar.gz",
            "0" * 64,
            "gcc",
            "cmake",
            "glibc",
            "cargo",
            "rust",
            "time")

    def arguments(self, root, maximum_concurrent=1):
        return SimpleNamespace(
            phase="smoke",
            campaign_id="baseline",
            max_concurrent=maximum_concurrent,
            candidate_archive=root / "candidate.tar.gz",
            candidate_archive_sha256="0" * 64,
            engine_tree="d" * 40)

    def test_shared_fleet_contains_rejections_after_cleanup(self):
        for classification in ("benchmark-failure", "none", "invalid-artifacts"):
            with self.subTest(classification=classification), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                result_root = root / "results"
                result_root.mkdir()
                arguments = self.arguments(root, maximum_concurrent=2)
                budget = Mock(policy={"hourly_rates": {self.platform.instance_type: {"spot": 0.2}}})
                budget.reserve.return_value = (True, "")
                arguments.shared_budget = budget
                jobs = [RUN_CAMPAIGN.Job(self.platform, shard, 1, index, 1, "spot", "language-batch")
                        for index, shard in enumerate(("like-single-use", "like-dfa-single-use", "like-compile"), 1)]
                processes = [FakeProcess(100 + job.host_epoch, status=0 if classification == "invalid-artifacts" else 1,
                             poll_action=lambda job=job: self.write_cleanup_session(
                                 result_root, job, failure_classification=classification)) for job in jobs]
                with patch.object(RUN_CAMPAIGN, "recover_budget_reservations"), \
                        patch.object(RUN_CAMPAIGN.subprocess, "Popen", side_effect=processes), \
                        patch.object(RUN_CAMPAIGN.time, "sleep"):
                    with self.assertRaises(SystemExit):
                        RUN_CAMPAIGN.execute_jobs(arguments, root, result_root, jobs)
                budget.halt.assert_not_called()
                self.assertEqual(budget.release.call_count, 3)
                self.assertTrue(all(not process.signals for process in processes))
                unresolved = (result_root / "unresolved-jobs.tsv").read_text()
                for job in jobs:
                    self.assertIn(job.shard, unresolved)
                self.assertNotIn("accepted", (result_root / "job-attempts.tsv").read_text())

    def test_release_campaign_rejects_missing_and_different_language_artifacts(self):
        root = SCRIPT.parents[3]
        arguments = self.arguments(root)
        arguments.release_version = "1.0"
        job = RUN_CAMPAIGN.Job(self.platform, "language", 1, 1, 1, "spot", "language-batch")
        for receipt in ({}, {"released_artifact": {"version": "1.1"}}):
            with self.subTest(receipt=receipt), self.assertRaisesRegex(RuntimeError, "selected release"):
                RUN_CAMPAIGN.validate_receipt_candidate(root, arguments, root, job, receipt)

    def test_platform_limit_applies_independently_of_global_capacity(self):
        arguments = SimpleNamespace(max_concurrent=9, max_concurrent_per_platform=3)
        job = SimpleNamespace(platform=SimpleNamespace(name='r9g'))
        other = SimpleNamespace(platform=SimpleNamespace(name='r8i'))
        running = {index: (None, job, None) for index in range(3)}
        self.assertFalse(RUN_CAMPAIGN.platform_has_capacity(job, running, arguments))
        self.assertTrue(RUN_CAMPAIGN.platform_has_capacity(other, running, arguments))
        del running[0]
        self.assertTrue(RUN_CAMPAIGN.platform_has_capacity(job, running, arguments))

    def test_language_jobs_use_the_allocation_frozen_in_the_plan(self):
        platforms = RUN_CAMPAIGN.read_platforms(SCRIPT.parent)
        language = SimpleNamespace(phase="primary", batches={
            "job": ("language-lifecycle", {
                "platform": "r9g", "instance_type": "r9g.2xlarge", "vcpus": 8,
                "shard": "language-lifecycle-0000", "replica": 1,
            }),
        })

        jobs = RUN_CAMPAIGN.build_language_jobs(language, platforms)

        self.assertEqual(len(jobs), 1)
        self.assertEqual(jobs[0].platform.instance_type, "r9g.2xlarge")
        self.assertEqual(jobs[0].platform.vcpus, 8)

    def test_spot_only_replacements_never_fall_back(self):
        # A Spot capacity failure moves to the next allowed region without spending the allowance.
        regions = ("us-east-2", "us-west-2")
        for attempt in (1, 2):
            for classification, detail, moved in (
                    ("retryable-spot", "launch-capacity:InsufficientInstanceCapacity", True),
                    ("retryable-spot", "instance-terminated-capacity-oversubscribed", False),
                    ("retryable-capacity", "vcpu-limit:VcpuLimitExceeded", False),
                    ("retryable-infrastructure", "wrapper failed", False)):
                with self.subTest(attempt=attempt, classification=classification, detail=detail):
                    job = RUN_CAMPAIGN.Job(
                        self.platform, "like-dfa-single-use", 1, 10, attempt, "spot", "language-batch", region="us-east-2")
                    replacement = RUN_CAMPAIGN.replacement_job(
                        job, 11, classification, detail, regions=regions, spot_only=True)
                    self.assertEqual(replacement.market, "spot")
                    self.assertEqual(replacement.attempt, attempt + 1)
                    self.assertEqual(replacement.spot_capacity_failures, ("us-east-2",) if moved else ())
        # Three counted attempts, after a move from a region with no capacity.
        exhausted = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 4, "spot", "language-batch", region="us-west-2",
                                     spot_capacity_failures=("us-east-2",))
        with self.assertRaisesRegex(RuntimeError, "exhausted"):
            RUN_CAMPAIGN.replacement_job(
                exhausted, 12, "retryable-spot", "launch-capacity:InsufficientInstanceCapacity",
                regions=regions, spot_only=True)
        on_demand = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "on-demand", "language-batch")
        with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "On-Demand"):
            RUN_CAMPAIGN.replacement_job(on_demand, 11, "retryable-spot", "interrupted", spot_only=True)

    def test_spot_only_policy_and_reserve_are_frozen_on_resume(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            arguments = SimpleNamespace(spot_only=True, spot_vcpu_reserve=0)
            RUN_CAMPAIGN.validate_execution_policy(arguments, root)
            RUN_CAMPAIGN.validate_execution_policy(arguments, root)
            arguments.spot_only = False
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "changed on restart"):
                RUN_CAMPAIGN.validate_execution_policy(arguments, root)
            arguments.spot_only, arguments.spot_vcpu_reserve = True, 32
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "changed on restart"):
                RUN_CAMPAIGN.validate_execution_policy(arguments, root)
            arguments.spot_vcpu_reserve = 0
            arguments.smoke_protocol = 'qualification'
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "changed on restart"):
                RUN_CAMPAIGN.validate_execution_policy(arguments, root)

            refill = SimpleNamespace(refill_unresolved=True, smoke_protocol="qualification")
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "release and protocol"):
                RUN_CAMPAIGN.validate_execution_policy(refill, root)
            with self.assertRaisesRegex(SystemExit, "existing campaign"):
                RUN_CAMPAIGN.refill_launch_jobs(refill, root / "jobs", [])
            original = (root / "execution-policy.json").read_bytes()
            refill.smoke_protocol = "smoke"
            RUN_CAMPAIGN.validate_execution_policy(refill, root)
            RUN_CAMPAIGN.validate_execution_policy(refill, root)
            self.assertEqual((root / "execution-policy.json").read_bytes(), original)
            refill.max_concurrent = 3
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "changed on restart"):
                RUN_CAMPAIGN.validate_execution_policy(refill, root)

    def test_engine_builds_are_frozen(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            arguments = SimpleNamespace(engine_build_policy={"r8i": "1" * 64})
            RUN_CAMPAIGN.validate_execution_policy(arguments, root)
            arguments.engine_build_policy = {"r8i": "2" * 64}
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "changed on restart"):
                RUN_CAMPAIGN.validate_execution_policy(arguments, root)

    def test_baseline_execution_requires_accepted_engine_builds(self):
        arguments = SimpleNamespace(phase="smoke", engine_builds=None)
        with self.assertRaisesRegex(SystemExit, "smoke execution requires --engine-builds from an accepted build"):
            RUN_CAMPAIGN.resolve_engine_builds(SCRIPT.parents[3], arguments, [self.platform])
        arguments = SimpleNamespace(phase="build", engine_builds=Path("builds"))
        with self.assertRaisesRegex(SystemExit, "apply only to baseline"):
            RUN_CAMPAIGN.resolve_engine_builds(SCRIPT.parents[3], arguments, [self.platform])
        arguments = SimpleNamespace(phase="smoke", engine_builds=Path("builds"),
                                    regions=["us-west-2", "us-east-2"], transfer_buckets={"us-west-2": "bucket"})
        with self.assertRaisesRegex(SystemExit, r"bucket in every allowed region; name one for \['us-east-2'\]"):
            RUN_CAMPAIGN.resolve_engine_builds(SCRIPT.parents[3], arguments, [self.platform])

    def test_engine_archives_upload_once_per_region(self):
        with tempfile.TemporaryDirectory() as temporary:
            build = Path(temporary)
            (build / "trino.tar.gz").write_bytes(b"trino")
            trino = hashlib.sha256(b"trino").hexdigest()
            (build / "engine-build.json").write_text(
                json.dumps({"archives": {"trino": {"file": "trino.tar.gz", "sha256": trino}}}))
            arguments = SimpleNamespace(
                regions=["us-west-2", "us-east-2"], transfer_buckets={"us-west-2": "west", "us-east-2": "east"},
                engine_build_directories={"r8i": build},
                engine_build_policy={"r8i": hashlib.sha256((build / "engine-build.json").read_bytes()).hexdigest()})
            jobs = [RUN_CAMPAIGN.Job(self.platform, shard, replica, 10, 1, "spot", "language-batch")
                    for shard in ("trino-like", "trino-operations") for replica in (1, 2)]
            present = {("east", f"prefix/engine-builds/{trino}.tar.gz"): base64.b64encode(bytes.fromhex(trino)).decode()}
            for stalls, expected_puts in ((1, 2), (RUN_CAMPAIGN.ENGINE_BUILD_UPLOAD_ATTEMPTS, None)):
                with self.subTest(stalls=stalls):
                    puts = []

                    def aws(command, **kwargs):
                        bucket, key = command[command.index("--bucket") + 1], command[command.index("--key") + 1]
                        if "head-object" in command:
                            if (bucket, key) not in present:
                                raise subprocess.CalledProcessError(254, command)
                            return present[bucket, key]
                        puts.append((bucket, key))
                        self.assertIn("timeout", kwargs)
                        if len(puts) <= stalls:
                            raise subprocess.TimeoutExpired(command, kwargs["timeout"])
                        return ""

                    with patch.dict(os.environ, {"TRANSFER_PREFIX": "prefix"}), \
                            patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws), \
                            contextlib.redirect_stdout(io.StringIO()):
                        if expected_puts is None:
                            with self.assertRaisesRegex(SystemExit, "could not upload the r8i engine archive to s3://west/"):
                                RUN_CAMPAIGN.upload_engine_builds(arguments, jobs)
                            continue
                        RUN_CAMPAIGN.upload_engine_builds(arguments, jobs)
                    # Each region gets the archive once; Ohio already holds it, and the stalled first
                    # upload is retried.
                    self.assertEqual([("west", f"prefix/engine-builds/{trino}.tar.gz")], sorted(set(puts)))
                    self.assertEqual(expected_puts, len(puts))

    def test_spot_only_launcher_rejects_on_demand_before_aws(self):
        environment = dict(os.environ, CAMPAIGN_SPOT_ONLY="1", INSTANCE_MARKET_TYPE="on-demand")
        result = subprocess.run([str(AWS_RUNNER), "test"], env=environment, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Spot-only policy prohibits On-Demand", result.stderr)

    def test_spot_only_quota_shortage_waits_without_on_demand(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result_root = root / "results"
            result_root.mkdir()
            arguments = self.arguments(root)
            arguments.spot_only, arguments.spot_vcpu_reserve = True, 0
            process = FakeProcess(110, status=1, poll_action=lambda: self.write_cleanup_session(
                result_root, job, failure_classification="benchmark-failure"))
            with patch.object(RUN_CAMPAIGN, "current_spot_capacity", side_effect=[
                        {"available": 0}, {"available": 8}]), \
                    patch.object(RUN_CAMPAIGN, "current_on_demand_capacity") as on_demand, \
                    patch.object(RUN_CAMPAIGN, "CAPACITY_RETRY_SECONDS", 0), \
                    patch.object(RUN_CAMPAIGN.time, "sleep"), \
                    patch.object(RUN_CAMPAIGN.subprocess, "Popen", return_value=process) as popen:
                with self.assertRaises(SystemExit):
                    RUN_CAMPAIGN.execute_jobs(arguments, root, result_root, [job])
            on_demand.assert_not_called()
            self.assertEqual(popen.call_args.kwargs["env"]["INSTANCE_MARKET_TYPE"], "spot")
            self.assertEqual(popen.call_args.kwargs["env"]["CAMPAIGN_SPOT_ONLY"], "1")
            self.assertIn("Spot-only policy waiting", (result_root / "job-attempts.tsv").read_text())

    def freeze_archive(self, repository, archive):
        """Archive HEAD with generated tables beside the baseline tooling, as freeze-candidate.sh does."""
        baseline = repository / "tools/re2-benchmark/baseline"
        baseline.mkdir(parents=True, exist_ok=True)
        for table in ("row-protocol.tsv", "units.tsv", "unit-rows.tsv"):
            (baseline / table).write_text(f"{table}\n")
        with archive.open("wb") as output_file:
            git_archive = subprocess.Popen(
                ["git", "archive", "--format=tar", "--prefix=tools/re2-benchmark/baseline/",
                 *(f"--add-file={baseline / table}" for table in ("row-protocol.tsv", "units.tsv", "unit-rows.tsv")),
                 "--prefix=", "HEAD"], cwd=repository, stdout=subprocess.PIPE)
            gzip = subprocess.run(["gzip", "-n"], stdin=git_archive.stdout, stdout=output_file, check=True)
            git_archive.stdout.close()
            self.assertEqual(git_archive.wait(), 0)
            self.assertEqual(gzip.returncode, 0)
        return archive

    def frozen_candidate(self, root):
        repository = root / "repository"
        repository.mkdir()
        subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
        subprocess.run(["git", "config", "user.name", "Test"], cwd=repository, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=repository, check=True)
        (repository / "src/main").mkdir(parents=True)
        (repository / "src/main/Engine.java").write_text("final class Engine {}\n")
        baseline = repository / "tools/re2-benchmark/baseline"
        baseline.mkdir(parents=True)
        manifest_names = (
            "rows.tsv",
            "platforms.tsv",
            "comparators.tsv",
            "shards.tsv",
            "protocol-representatives.tsv",
        )
        for name in manifest_names:
            (baseline / name).write_text(f"{name}\n")
        (repository / ".gitignore").write_text(
            "".join(f"/tools/re2-benchmark/baseline/{table}\n" for table in RUN_CAMPAIGN.work_units.TABLES))
        subprocess.run(["git", "add", "."], cwd=repository, check=True)
        subprocess.run(["git", "commit", "-qm", "Candidate"], cwd=repository, check=True)

        candidate_ref = "refs/benchmarks/test-candidate"
        subprocess.run(["git", "update-ref", candidate_ref, "HEAD"], cwd=repository, check=True)
        archive = self.freeze_archive(repository, root / "candidate.tar.gz")

        commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()
        root_tree = subprocess.check_output(
            ["git", "rev-parse", "HEAD^{tree}"], cwd=repository, text=True).strip()
        engine_tree = subprocess.check_output(
            ["git", "rev-parse", "HEAD:src/main"], cwd=repository, text=True).strip()
        provenance = root / "candidate.tsv"
        archive_listing = subprocess.check_output(["tar", "-tzf", str(archive)])
        values = {
            "candidate_ref": candidate_ref,
            "candidate_commit": commit,
            "root_tree": root_tree,
            "engine_tree": engine_tree,
            "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "archive_file_list_sha256": hashlib.sha256(
                b"".join(sorted(archive_listing.splitlines(keepends=True)))).hexdigest(),
            **{
                field: hashlib.sha256((baseline / name).read_bytes()).hexdigest()
                for field, name in (
                    ("row_manifest_sha256", "rows.tsv"),
                    ("platform_manifest_sha256", "platforms.tsv"),
                    ("comparator_manifest_sha256", "comparators.tsv"),
                    ("shard_manifest_sha256", "shards.tsv"),
                    ("protocol_representatives_sha256", "protocol-representatives.tsv"),
                )
            },
        }
        with provenance.open("w", newline="") as output_file:
            writer = csv.DictWriter(
                output_file,
                fieldnames=RUN_CAMPAIGN.CANDIDATE_PROVENANCE_FIELDS,
                delimiter="\t",
                lineterminator="\n")
            writer.writeheader()
            writer.writerow(values)
        arguments = SimpleNamespace(
            candidate_ref=candidate_ref,
            candidate_archive=archive,
            candidate_provenance=provenance)
        return repository, arguments, values

    @staticmethod
    def receipt_for_job(job):
        """The receipt a batch host accepts, as the job loop reads it."""
        return {
            "schema_version": 1,
            "workload": "language-batch",
            "campaign_id": "baseline",
            "platform": job.platform.name,
            "shard_id": job.shard,
            "replica_id": str(job.replica),
            "host_epoch": str(job.host_epoch),
            "architecture": job.platform.architecture,
            "instance_type": job.platform.instance_type,
            "candidate_commit": "a" * 40,
            "candidate_archive_sha256": "5" * 64,
            "engine_tree": "e" * 40,
            "heap_size": "8g",
        }

    @staticmethod
    def write_receipt(path, receipt):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(receipt))

    def write_cleanup_session(
            self,
            result_root,
            job,
            *,
            failure_classification="controller-abort",
            failure_detail="test failure",
            bucket_removed="verified",
            launched=True,
            region="us-east-2"):
        """Leave the session a wrapper leaves after its launch in region ends."""
        session = RUN_CAMPAIGN.job_result_root(result_root, job) / "session"
        session.mkdir(parents=True)
        if launched:
            (session / "session.txt").write_text(
                "session=session\n"
                "bucket=re2-baseline-test-bucket\n"
                "intel_instance_id=i-0123456789abcdef0\n"
                "arm_instance_id=none\n")
        (session / "cleanup-manifest.txt").write_text(
            "instances_terminated=verified\n"
            "iam_removed=verified\n"
            f"bucket_removed={bucket_removed}\n"
            "network_resources=default-vpc-reused\n"
            f"cleanup_status={'verified' if bucket_removed == 'verified' else 'failed'}\n"
            "run_status=failed\n"
            f"failure_classification={failure_classification}\n"
            f"failure_detail={failure_detail}\n"
            f"aws_region={region}\n"
            f"intel_availability_zone={region}b\n"
            "arm_availability_zone=none\n"
            "placement_score=9\n")
        return session

    def test_capacity_evidence_requires_complete_successful_measurement(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            capacity = Path(temporary_directory) / "host-capacity.txt"
            capacity.write_text(
                "memory_total_bytes=34359738368\n"
                "memory_available_before_bytes=30000000000\n"
                "swap_total_bytes=0\n"
                "swap_free_before_bytes=0\n"
                "oom_kills_before=0\n"
                "wall_seconds=123.45\n"
                "maximum_resident_kibibytes=1048576\n"
                "exit_status=0\n"
                "memory_available_after_bytes=29000000000\n"
                "minimum_available_memory_bytes=3435973837\n"
                "swap_free_after_bytes=0\n"
                "oom_kills_after=0\n"
                "capacity_status=accepted\n")

            values = RUN_CAMPAIGN.validate_capacity(capacity)

            self.assertEqual(values["maximum_resident_kibibytes"], "1048576")

    def test_deterministic_archive_checksum_matches_the_frozen_archive_and_its_tables(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=root, check=True)
            (root / "file.txt").write_text("content\n")
            subprocess.run(["git", "add", "file.txt"], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "Test"], cwd=root, check=True)
            with self.assertRaisesRegex(SystemExit, "generated tables .* are missing"):
                RUN_CAMPAIGN.deterministic_archive_sha256(root, "HEAD")
            archive = self.freeze_archive(root, root / "candidate.tar.gz")
            with tarfile.open(archive) as members:
                self.assertEqual(["file.txt", "tools/re2-benchmark/baseline/row-protocol.tsv",
                                  "tools/re2-benchmark/baseline/unit-rows.tsv", "tools/re2-benchmark/baseline/units.tsv"],
                                 sorted(member.name for member in members if member.isfile()))

            self.assertEqual(
                RUN_CAMPAIGN.deterministic_archive_sha256(root, "HEAD"),
                hashlib.sha256(archive.read_bytes()).hexdigest())
            # Tables regenerated differently since the freeze no longer reproduce the candidate archive.
            (root / "tools/re2-benchmark/baseline/units.tsv").write_text("changed\n")
            self.assertNotEqual(
                RUN_CAMPAIGN.deterministic_archive_sha256(root, "HEAD"),
                hashlib.sha256(archive.read_bytes()).hexdigest())

    def test_candidate_source_accepts_its_own_new_engine_tree(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository, arguments, values = self.frozen_candidate(root)

            RUN_CAMPAIGN.validate_source(repository, arguments)

            self.assertEqual(arguments.engine_tree, values["engine_tree"])
            self.assertEqual(arguments.candidate_archive_sha256, values["archive_sha256"])

            # Only a refill may run a controller newer than the candidate it measures.
            (repository / "controller.py").write_text("refill\n")
            subprocess.run(["git", "add", "."], cwd=repository, check=True)
            subprocess.run(["git", "commit", "-qm", "Controller"], cwd=repository, check=True)
            with self.assertRaisesRegex(SystemExit, "but HEAD is"):
                RUN_CAMPAIGN.validate_source(repository, arguments)
            arguments.refill_unresolved = True
            RUN_CAMPAIGN.validate_source(repository, arguments)

    def test_archive_listing_preserves_directory_slashes_from_freeze_script(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            _, arguments, values = self.frozen_candidate(Path(temporary_directory))
            self.assertEqual(RUN_CAMPAIGN.archive_file_list_sha256(arguments.candidate_archive),
                             values["archive_file_list_sha256"])

    def test_candidate_source_rejects_mismatched_engine_tree_provenance(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository, arguments, values = self.frozen_candidate(root)
            values["engine_tree"] = "f" * 40
            with arguments.candidate_provenance.open("w", newline="") as output_file:
                writer = csv.DictWriter(
                    output_file,
                    fieldnames=RUN_CAMPAIGN.CANDIDATE_PROVENANCE_FIELDS,
                    delimiter="\t",
                    lineterminator="\n")
                writer.writeheader()
                writer.writerow(values)

            with self.assertRaisesRegex(SystemExit, "candidate provenance engine_tree"):
                RUN_CAMPAIGN.validate_source(repository, arguments)

    def test_primary_requires_accepted_smoke_results(self):
        arguments = SimpleNamespace(phase="primary", smoke_results=None)
        with self.assertRaisesRegex(SystemExit, "requires --smoke-results"):
            RUN_CAMPAIGN.validate_prerequisites(Path("."), arguments, [self.platform])

    def test_campaign_rejects_volatile_build_output_paths(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            durable = root.parent / "durable"
            target = root / "target"
            arguments = SimpleNamespace(
                candidate_archive=durable / "candidate.tar.gz",
                candidate_provenance=durable / "candidate.tsv")

            RUN_CAMPAIGN.validate_durable_paths(root, arguments, durable / "results")

            cases = (
                ("result root", target / "results", arguments),
                ("candidate archive", durable / "results", SimpleNamespace(
                    candidate_archive=target / "candidate.tar.gz",
                    candidate_provenance=durable / "candidate.tsv")),
                ("candidate provenance", durable / "results", SimpleNamespace(
                    candidate_archive=durable / "candidate.tar.gz",
                    candidate_provenance=target / "candidate.tsv")),
            )
            for label, result_root, case_arguments in cases:
                with self.subTest(label=label), self.assertRaisesRegex(SystemExit, label):
                    RUN_CAMPAIGN.validate_durable_paths(root, case_arguments, result_root)

    def test_formal_wrapper_rejects_nonstandard_timeout(self):
        runner = SCRIPT.parents[1] / "aws" / "run-campaign.sh"
        result = subprocess.run(
            [str(runner)],
            capture_output=True,
            text=True,
            env={**os.environ, "CAMPAIGN_MODE": "engine-build", "TIMEOUT_SECONDS": "5401"})

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("require TIMEOUT_SECONDS=5400", result.stderr)

    def test_user_data_waits_for_instance_profile_and_retries_transfers(self):
        source = AWS_RUNNER.read_text()

        self.assertIn("wait_for_aws_credentials()", source)
        self.assertIn("for credential_attempt in {1..60}", source)
        self.assertIn("download_input()", source)
        self.assertIn("for download_attempt in {1..24}", source)
        self.assertIn("for upload_attempt in {1..12}", source)

    def test_baseline_controller_omits_diagnostic_dependencies(self):
        source = AWS_RUNNER.read_text()

        self.assertNotIn("hsdis", source.lower())
        self.assertNotIn("CAPTURE_PIPELINE", source)
        self.assertNotIn("BASELINE_CAMPAIGN_PROFILE", source)

    def test_generated_user_data_is_valid_bash(self):
        source = AWS_RUNNER.read_text()
        function = source[
            source.index("create_user_data()\n{"):
            source.index("\n}\n\nlaunch_instance()") + 2]
        for mode in ("engine-build", "unit-worker"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary_directory:
                user_data = Path(temporary_directory) / "user-data.sh"
                script = (
                    f"{function}\n"
                    f"CAMPAIGN_MODE={mode}\n"
                    f"create_user_data test x86_64 result.tar.gz {user_data} ami url sha\n"
                    f"bash -n {user_data}\n")

                result = subprocess.run(
                    ["bash"], input=script, text=True, capture_output=True, check=False)

                self.assertEqual(result.returncode, 0, result.stderr)

    def test_only_a_unit_worker_may_list_and_only_its_leases(self):
        source = AWS_RUNNER.read_text()
        function = source[
            source.index("create_instance_profile()\n{"):
            source.index("\n}\n\nsubnet_rotation()") + 2]
        for mode in ("engine-build", "unit-worker"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary_directory:
                script = (
                    "aws_cli() { :; }\nsleep() { :; }\n"
                    f"{function}\n"
                    f"CAMPAIGN_MODE={mode} SESSION_DIR={shlex.quote(temporary_directory)} LOWER_SESSION_ID=session\n"
                    "BUCKET=transfer INPUT_PREFIX=campaign/session/input RESULT_PREFIX=campaign/session/results\n"
                    "ENGINE_BUILD_DIRECTORY=\n"
                    "create_instance_profile\n")

                result = subprocess.run(
                    ["bash"], input=script, text=True, capture_output=True, check=False)

                self.assertEqual(result.returncode, 0, result.stderr)
                policy = json.loads((Path(temporary_directory) / "instance-transfer-policy.json").read_text())
                listings = [statement for statement in policy["Statement"] if statement["Action"] == "s3:ListBucket"]
                expected = [{
                    "Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::transfer",
                    "Condition": {"StringLike": {"s3:prefix": "campaign/session/input/leases/*"}},
                }] if mode == "unit-worker" else []
                self.assertEqual(expected, listings)
                self.assertEqual(
                    [("s3:GetObject", "arn:aws:s3:::transfer/campaign/session/input/*"),
                     ("s3:PutObject", "arn:aws:s3:::transfer/campaign/session/results/*")],
                    [(statement["Action"], statement["Resource"]) for statement in policy["Statement"]
                     if statement["Action"] != "s3:ListBucket"])

    def test_host_installs_rust_exactly_when_the_controller_expects_it(self):
        host = (AWS_RUNNER.parent / "run-host.sh").read_text()
        install = host[host.index("install_packages()\n{"):host.index("\n}\n\ninstall_java()") + 2]
        controller = AWS_RUNNER.read_text()
        expected = controller[controller.index("expected_cargo_package=not-installed"):
                              controller.index("if [[ ! -f \"${environment_manifest}\" ]]")]
        for mode in ("engine-build", "unit-worker", "language-batch"):
            script = (f"sudo() {{ echo \"$*\"; }}\nCARGO_PACKAGE=cargo-pin\nRUST_PACKAGE=rust-pin\n{install}\n"
                      f"BENCHMARK_MODE={mode} install_packages\n"
                      f"CAMPAIGN_MODE={mode}\n{expected}\n"
                      "echo \"expected=${expected_cargo_package}\"\n")
            output = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=True).stdout
            with self.subTest(mode=mode):
                self.assertEqual("cargo-pin rust-pin" in output, "expected=cargo-pin" in output)
                self.assertEqual("cargo-pin rust-pin" in output, mode == "language-batch")

    def test_missing_remote_artifact_is_retryable_infrastructure(self):
        source = AWS_RUNNER.read_text()

        self.assertIn(
            '"instance-terminated-without-results:state=${state};spot_status=${spot_status:-none}"',
            source)

    def test_shared_transfer_bucket_requires_prefix(self):
        result = subprocess.run(
            [str(AWS_RUNNER)],
            capture_output=True,
            text=True,
            env={
                **os.environ,
                "CAMPAIGN_MODE": "engine-build",
                "TRANSFER_BUCKET": "benchmark-transfer"})

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TRANSFER_PREFIX is required", result.stderr)

    def test_shared_transfer_prefix_must_be_safe(self):
        result = subprocess.run(
            [str(AWS_RUNNER)],
            capture_output=True,
            text=True,
            env={
                **os.environ,
                "CAMPAIGN_MODE": "engine-build",
                "TRANSFER_BUCKET": "benchmark-transfer",
                "TRANSFER_PREFIX": "../another-campaign"})

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must be a safe relative S3 prefix", result.stderr)

    def test_prerequisite_rejects_different_engine_builds(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "execution-policy.json").write_text(json.dumps({"engine_builds": {"r8i": "1" * 64}}))
            arguments = SimpleNamespace(phase="primary", campaign_id="baseline", engine_build_policy={"r8i": "2" * 64})
            with self.assertRaisesRegex(SystemExit, "engine builds its smoke prerequisite used"):
                RUN_CAMPAIGN.validate_unit_phase_prerequisite(root, arguments, root, (1,), "smoke")

    def test_capacity_evidence_rejects_failed_session(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            capacity = Path(temporary_directory) / "host-capacity.txt"
            capacity.write_text(
                "memory_total_bytes=34359738368\n"
                "memory_available_before_bytes=30000000000\n"
                "swap_total_bytes=0\n"
                "swap_free_before_bytes=0\n"
                "oom_kills_before=0\n"
                "wall_seconds=123.45\n"
                "maximum_resident_kibibytes=1048576\n"
                "exit_status=1\n"
                "memory_available_after_bytes=29000000000\n"
                "minimum_available_memory_bytes=3435973837\n"
                "swap_free_after_bytes=0\n"
                "oom_kills_after=0\n"
                "capacity_status=accepted\n")

            with self.assertRaisesRegex(RuntimeError, "exit status 1"):
                RUN_CAMPAIGN.validate_capacity(capacity)

    def test_capacity_evidence_rejects_swap_oom_and_insufficient_headroom(self):
        base = (
            "memory_total_bytes=34359738368\n"
            "memory_available_before_bytes=30000000000\n"
            "swap_total_bytes=1073741824\n"
            "swap_free_before_bytes=1073741824\n"
            "oom_kills_before=0\n"
            "wall_seconds=123.45\n"
            "maximum_resident_kibibytes=1048576\n"
            "exit_status=0\n"
            "minimum_available_memory_bytes=3435973837\n"
            "capacity_status=accepted\n")
        cases = (
            ("memory_available_after_bytes=29000000000\nswap_free_after_bytes=536870912\noom_kills_after=0\n", "consumed swap"),
            ("memory_available_after_bytes=29000000000\nswap_free_after_bytes=1073741824\noom_kills_after=1\n", "OOM kill"),
            ("memory_available_after_bytes=3000000000\nswap_free_after_bytes=1073741824\noom_kills_after=0\n", "memory headroom"),
            ("memory_available_after_bytes=29000000000\nswap_free_after_bytes=1073741824\noom_kills_after=0\n"
             "disk_available_before_bytes=30000000000\ndisk_available_after_bytes=1073741824\nminimum_available_disk_bytes=2147483648\n", "disk headroom"),
            ("memory_available_after_bytes=29000000000\nswap_free_after_bytes=1073741824\noom_kills_after=0\n"
             "disk_available_before_bytes=30000000000\n", "invalid disk capacity"),
        )
        for suffix, message in cases:
            with self.subTest(message=message), tempfile.TemporaryDirectory() as temporary_directory:
                capacity = Path(temporary_directory) / "host-capacity.txt"
                capacity.write_text(base + suffix)
                with self.assertRaisesRegex(RuntimeError, message):
                    RUN_CAMPAIGN.validate_capacity(capacity)

    def test_replacement_uses_a_new_epoch(self):
        initial = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")

        # A failure Spot did not cause retries on Spot once.
        replacement = RUN_CAMPAIGN.replacement_job(initial, 117, "retryable-infrastructure")

        self.assertEqual(replacement.logical_identity, initial.logical_identity)
        self.assertEqual(replacement.host_epoch, 117)
        self.assertEqual(replacement.attempt, 2)
        self.assertEqual(replacement.market, "spot")

    def test_build_phase_runs_one_engine_build_per_platform(self):
        platforms = RUN_CAMPAIGN.read_platforms(SCRIPT.parent)
        build = RUN_CAMPAIGN.build_engine_jobs(platforms)

        self.assertEqual([(platform.name, "engine-build", "engine-build") for platform in platforms],
                         [(job.platform.name, job.shard, job.workload) for job in build])
        # Build hosts take epochs no baseline or language phase reaches.
        self.assertEqual(sorted(RUN_CAMPAIGN.PHASE_HOST_EPOCH_START.values())[-1], build[0].host_epoch)

    def test_shared_policy_checks_the_actual_aws_account_and_region(self):
        policy = {"account": "123456789012", "region": "us-east-2",
                  "regions": {"us-east-1": {"max_vcpus": 8, "spot_vcpu_reserve": 0}}}
        arguments = SimpleNamespace(shared_budget=SimpleNamespace(policy=policy))
        for account, region, regions in (("234567890123", "us-east-2", None),
                                         ("123456789012", "us-west-2", None),
                                         ("123456789012", "us-east-2", ["us-east-2", "eu-west-1"])):
            arguments.regions = regions
            with self.subTest(account=account, region=region, regions=regions), \
                    patch.dict(os.environ, {"AWS_REGION": region}), \
                    patch.object(RUN_CAMPAIGN, "check_output", return_value=account), \
                    self.assertRaisesRegex(SystemExit, "differs from the shared fleet policy"):
                RUN_CAMPAIGN.validate_aws(arguments, [self.platform])

    def test_aws_preflight_requires_each_region_to_run_the_pinned_image(self):
        arguments = SimpleNamespace(
            shared_budget=None, spot_only=True, spot_vcpu_reserve=0,
            regions=["eu-west-1", "us-west-2", "ap-south-1"],
            regional_amis={("r8i", "us-west-2"): "ami-test", ("r8i", "eu-west-1"): "ami-ireland"},
            transfer_buckets={"eu-west-1": "bucket-ireland"})
        names = {"ami-test": "al2023-pinned", "ami-ireland": "al2023-pinned"}
        location = "EU"
        public_subnets = {"eu-west-1", "us-west-2", "ap-south-1"}

        def aws_result(command, **_):
            region = command[command.index("--region") + 1]
            if "get-caller-identity" in command:
                return "123456789012"
            if "describe-vpcs" in command:
                return "vpc-" + region
            if "describe-subnets" in command:
                self.assertIn("Name=vpc-id,Values=vpc-" + region, command)
                return region + "a" if region in public_subnets else ""
            if "get-service-quota" in command:
                return "64"
            if "describe-images" in command:
                image = command[command.index("--image-ids") + 1]
                # The Oregon pin names the image even when Oregon does not offer the type.
                self.assertEqual(region, "us-west-2" if image == "ami-test" else "eu-west-1")
                return f"x86_64\tavailable\t137112412989\t{names[image]}"
            if "describe-instance-type-offerings" in command:
                return "" if region in ("ap-south-1", "us-west-2") else region + "a"
            if "get-bucket-location" in command:
                return location
            if "get-bucket-tagging" in command:
                return "1"
            raise AssertionError(command)

        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        self.assertEqual(arguments.ami_names, {"r8i": "al2023-pinned"})
        self.assertEqual(arguments.region_offerings, {"r8i.2xlarge": ["eu-west-1"]})

        public_subnets.discard("ap-south-1")
        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result), \
                self.assertRaisesRegex(SystemExit, "ap-south-1 has no public default-VPC subnet"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        public_subnets.add("ap-south-1")
        location = "us-west-2"
        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result), \
                self.assertRaisesRegex(SystemExit, "bucket-ireland is not a tagged"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        names["ami-ireland"] = "al2023-other"
        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result), \
                self.assertRaisesRegex(SystemExit, "ami-ireland in eu-west-1 is not al2023-pinned"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        del arguments.regional_amis[("r8i", "eu-west-1")]
        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result), \
                self.assertRaisesRegex(SystemExit, "no r8i AMI for eu-west-1"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])

    def test_spot_launch_takes_the_best_scored_region_with_headroom(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        regions = ["us-west-2", "eu-west-1", "ap-south-1"]
        # Ireland ties Mumbai but comes first, then lacks quota headroom. Without scores, the
        # first allowed region with headroom launches and no score is recorded.
        for scored, expected_queries, expected_region, expected_score in (
                (True, ["eu-west-1", "ap-south-1"], "ap-south-1", "9"),
                (False, ["us-west-2"], "us-west-2", "")):
            with self.subTest(scored=scored), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                result_root = root / "results"
                result_root.mkdir()
                arguments = self.arguments(root)
                arguments.spot_only, arguments.spot_vcpu_reserve = True, 0
                arguments.regions = regions
                arguments.regional_amis = {("r8i", region): "ami-" + region for region in regions}
                arguments.transfer_buckets = {"ap-south-1": "bucket-mumbai"}
                queried = []

                def spot_capacity(_, __, ___, region):
                    queried.append(region)
                    return {"available": 0 if region == "eu-west-1" else 8}

                def placement_scores(command, **_):
                    self.assertIn("get-spot-placement-scores", command)
                    if not scored:
                        raise subprocess.CalledProcessError(254, command)
                    return "us-west-2\t3\neu-west-1\t9\nap-south-1\t9\n"

                process = FakeProcess(110, status=1, poll_action=lambda: self.write_cleanup_session(
                    result_root, job, failure_classification="benchmark-failure"))
                with patch.object(RUN_CAMPAIGN, "check_output", side_effect=placement_scores), \
                        patch.object(RUN_CAMPAIGN, "current_spot_capacity", side_effect=spot_capacity), \
                        patch.object(RUN_CAMPAIGN.time, "sleep"), \
                        patch.object(RUN_CAMPAIGN.subprocess, "Popen", return_value=process) as popen:
                    with self.assertRaises(SystemExit):
                        RUN_CAMPAIGN.execute_jobs(arguments, root, result_root, [job])
                environment = popen.call_args.kwargs["env"]
                self.assertEqual(queried, expected_queries)
                self.assertEqual(environment["AWS_REGION"], expected_region)
                self.assertEqual(environment["CAMPAIGN_PLACEMENT_SCORE"], expected_score)
                self.assertEqual(environment["BENCHMARK_INTEL_AMI_ID"], "ami-" + expected_region)
                self.assertEqual(environment.get("TRANSFER_BUCKET"), "bucket-mumbai" if scored else None)

    def test_spot_placement_leaves_out_regions_that_found_no_capacity(self):
        # With no region left, a unit pool turns to On-Demand; with one left, no score is asked for.
        for regions, failures, expected in ((["us-west-2"], ("us-west-2",), []),
                                            (["us-west-2", "eu-west-1", "ap-south-1"], ("us-west-2", "ap-south-1"),
                                             [("eu-west-1", "")])):
            with self.subTest(regions=regions):
                arguments = SimpleNamespace(regions=regions)
                job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch",
                                       spot_capacity_failures=failures)
                with patch.object(RUN_CAMPAIGN, "check_output", side_effect=AssertionError("scores queried")):
                    self.assertEqual(expected, RUN_CAMPAIGN.launch_placements(arguments, job))

    def test_aws_preflight_uses_routed_worker_topologies(self):
        platform = RUN_CAMPAIGN.replace(
            self.platform,
            instance_type="r8i.large",
            vcpus=2,
            concurrency_instance_type="r8i.2xlarge",
            concurrency_vcpus=8)
        # A batch runs on the worker its plan allocates, here the platform's multicore worker.
        jobs = [RUN_CAMPAIGN.Job(RUN_CAMPAIGN.replace(platform, instance_type="r8i.2xlarge", vcpus=8),
                                 "language-lifecycle-0000", 1, 1, 1, "spot", "language-batch")]
        arguments = SimpleNamespace(shared_budget=None, spot_only=True, spot_vcpu_reserve=4)
        spot_quota = 11

        def aws_result(command, **_):
            if "get-caller-identity" in command:
                return "123456789012"
            if RUN_CAMPAIGN.STANDARD_SPOT_QUOTA_CODE in command:
                return str(spot_quota)
            if RUN_CAMPAIGN.STANDARD_ON_DEMAND_QUOTA_CODE in command:
                return "100"
            if "describe-images" in command:
                return "x86_64\tavailable\t137112412989\tal2023-test"
            if "describe-instance-type-offerings" in command:
                return "us-west-2a" if "Name=instance-type,Values=r8i.large" in command else ""
            if "describe-vpcs" in command:
                return "vpc-default"
            if "describe-subnets" in command:
                return "us-west-2a"
            raise AssertionError(command)

        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result), \
                self.assertRaisesRegex(SystemExit, "cannot admit one host"):
            RUN_CAMPAIGN.validate_aws(arguments, [platform], jobs)

        spot_quota = 12
        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result), \
                self.assertRaisesRegex(SystemExit, "r8i.2xlarge is not offered"):
            RUN_CAMPAIGN.validate_aws(arguments, [platform], jobs)

    def test_aws_preflight_rejects_deadline_capacity_above_effective_spot_quota(self):
        policy = {"account": "123456789012", "region": "us-west-2",
                  "max_vcpus": 64, "spot_vcpu_reserve": 16}
        arguments = SimpleNamespace(
            shared_budget=SimpleNamespace(policy=policy), spot_only=True, spot_vcpu_reserve=16)

        def aws_result(command, **_):
            if "get-caller-identity" in command:
                return policy["account"]
            if RUN_CAMPAIGN.STANDARD_SPOT_QUOTA_CODE in command:
                return "64"
            if RUN_CAMPAIGN.STANDARD_ON_DEMAND_QUOTA_CODE in command:
                # Below the On-Demand safety reserve, which binds only a region with an allowance.
                return "16"
            if "describe-images" in command:
                return "x86_64\tavailable\t137112412989\tal2023-test"
            if "describe-instance-type-offerings" in command:
                return "us-west-2a"
            if "describe-vpcs" in command:
                return "vpc-default"
            if "describe-subnets" in command:
                return "us-west-2a"
            raise AssertionError(command)

        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result), \
                self.assertRaisesRegex(SystemExit, "max_vcpus exceeds"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        policy["max_vcpus"] = 48
        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])

    @staticmethod
    def replica_jobs(platforms, batches):
        """Three replicas of each batch on each platform, as a primary language phase runs them."""
        return [RUN_CAMPAIGN.Job(platform, batch, replica, epoch, 1, "spot", "language-batch")
                for epoch, (batch, replica, platform) in enumerate(
                    ((batch, replica, platform) for batch in batches for replica in (1, 2, 3)
                     for platform in platforms), start=RUN_CAMPAIGN.PHASE_HOST_EPOCH_START["primary"])]

    def test_campaign_concurrency_uses_configuration(self):
        jobs = self.replica_jobs([self.platform], ["like-dfa-single-use"])
        arguments = SimpleNamespace(campaign_id="baseline", max_concurrent=512, phase="primary", regions=["us-west-2"])
        RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.execute = True
        with self.assertRaisesRegex(SystemExit, "requires a shared fleet budget"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.shared_budget = SimpleNamespace(policy={"region": "us-west-2", "max_hosts": 512, "max_vcpus": 1024,
                                                          "spot_vcpu_reserve": 16})
        arguments.spot_only, arguments.spot_vcpu_reserve = True, 16
        RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.spot_vcpu_reserve = 0
        with self.assertRaisesRegex(SystemExit, "reserve matching its policy"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.spot_vcpu_reserve = 16
        # A shared fleet runs On-Demand only where its policy allows it.
        arguments.spot_only = False
        arguments.shared_budget.policy.update(regions={
            "eu-west-1": {"max_vcpus": 64, "spot_vcpu_reserve": 0, "on_demand_max_vcpus": 64}})
        with self.assertRaisesRegex(SystemExit, "--spot-only unless the policy allows On-Demand"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.regions.append("eu-west-1")
        RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        # An allowance in a region without the worker type's image cannot launch it.
        arguments.regional_amis = {("r8i", "us-west-2"): "ami", ("r9g", "eu-west-1"): "ami"}
        with self.assertRaisesRegex(SystemExit, "--spot-only unless the policy allows On-Demand"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.regional_amis[("r8i", "eu-west-1")] = "ami"
        RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.spot_only = True
        arguments.refill_unresolved = True
        with self.assertRaisesRegex(SystemExit, "without --spot-only or a shared fleet budget"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.spot_only = False
        with self.assertRaisesRegex(SystemExit, "without --spot-only or a shared fleet budget"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.refill_unresolved, arguments.spot_only = False, True
        arguments.max_concurrent = 0
        with self.assertRaisesRegex(SystemExit, "positive integer"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)

    def test_deadline_uses_shared_fleet_host_and_vcpu_limits(self):
        jobs = self.replica_jobs([self.platform], ["like-dfa-single-use"])
        arguments = SimpleNamespace(campaign_id="baseline", max_concurrent=512, phase="primary",
                                    spot_only=True, spot_vcpu_reserve=0, regions=["us-west-2"],
                                    shared_budget=SimpleNamespace(policy={"region": "us-west-2", "max_hosts": 512,
                                                                         "max_vcpus": 8, "spot_vcpu_reserve": 0}),
                                    phase_timeout_seconds=2 * RUN_CAMPAIGN.JOB_TIMEOUT_SECONDS)
        with self.assertRaisesRegex(SystemExit, "cannot fit its phase deadline"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.shared_budget.policy.update(max_vcpus=1024, max_hosts=1)
        with self.assertRaisesRegex(SystemExit, "cannot fit its phase deadline"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.shared_budget.policy["max_hosts"] = 512
        RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.shared_budget.policy["max_vcpus"] = 1
        with self.assertRaisesRegex(SystemExit, "cannot admit every worker type"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        # Another allowed region's limit admits the workers, and a wave fills every region's limit at once:
        # the three 8-vCPU jobs need Oregon's one vCPU beside Ireland's 23.
        arguments.regions.append("eu-west-1")
        arguments.shared_budget.policy["regions"] = {"eu-west-1": {"max_vcpus": 23, "spot_vcpu_reserve": 0}}
        RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        # A unit campaign's limits apply to its pools' worker hosts.
        platforms = RUN_CAMPAIGN.read_platforms(SCRIPT.parent)
        arguments.unit_platforms = {platform.name: platform for platform in platforms}
        arguments.shared_budget.policy.update(max_attempt_seconds=86400, regions={})
        items = RUN_CAMPAIGN.build_work_items("primary", platforms, RUN_CAMPAIGN.work_units.load(SCRIPT.parent),
                                              ["trino-operations"])
        arguments.phase_timeout_seconds = None
        with self.assertRaisesRegex(SystemExit, "cannot admit every worker type"):
            RUN_CAMPAIGN.validate_configuration(arguments, [], items=items)

    def test_primary_topology_fits_deadline_with_retry_headroom(self):
        platforms = [
            self.platform,
            RUN_CAMPAIGN.Platform(
                "r8g", "arm", "r8g.2xlarge", 8, "ami", "jdk", "0" * 64,
                "gcc", "cmake", "glibc", "cargo", "rust", "time"),
            RUN_CAMPAIGN.Platform(
                "r9g", "arm", "r9g.2xlarge", 8, "ami", "jdk", "0" * 64,
                "gcc", "cmake", "glibc", "cargo", "rust", "time"),
        ]
        jobs = self.replica_jobs(platforms, [f"batch-{index}" for index in range(33)])

        RUN_CAMPAIGN.validate_configuration(
            SimpleNamespace(campaign_id="baseline", max_concurrent=64, phase="primary"),
            jobs)

        with self.assertRaisesRegex(SystemExit, "cannot fit its phase deadline"):
            RUN_CAMPAIGN.validate_configuration(
                SimpleNamespace(campaign_id="baseline", max_concurrent=32, phase="primary", phase_timeout_seconds=36000),
                jobs)

    def test_campaign_phase_maps_to_execution_protocol(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            candidate_archive = root / "candidate.tar.gz"
            arguments = SimpleNamespace(
                campaign_id="baseline",
                candidate_archive=candidate_archive,
                candidate_archive_sha256="0" * 64,
                engine_tree="d" * 40,
                result_root=root)
            job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")

            arguments.phase = "smoke"
            self.assertEqual(
                RUN_CAMPAIGN.job_environment(job, arguments, root, root)["BASELINE_PROTOCOL"],
                "smoke")

            arguments.smoke_protocol = 'qualification'
            self.assertEqual(RUN_CAMPAIGN.job_environment(job, arguments, root, root)["BASELINE_PROTOCOL"], "qualification")

            for phase in ("primary", "confirmation"):
                arguments.phase = phase
                environment = RUN_CAMPAIGN.job_environment(job, arguments, root, root)
                self.assertEqual(environment["BASELINE_PROTOCOL"], "qualification")
                self.assertEqual(
                    environment["BASELINE_EXPECTED_ENGINE_TREE"],
                    "d" * 40)

    def test_spot_retry_visits_each_pool_independently_of_global_epochs(self):
        source = AWS_RUNNER.read_text()
        selector = source[source.index('subnet_rotation()'):source.index('\ncreate_user_data()')]
        fixture = '''
aws_cli() {
    if [[ "$*" == *describe-instance-type-offerings* ]]; then
        printf 'us-west-2a us-west-2b us-west-2c us-west-2d\\n'
    else
        printf 'subnet-%s\\tus-west-2%s\\n' a a b b c c d d
    fi
}
VPC_ID=vpc-test
CAMPAIGN_PLATFORM=r8i
CAMPAIGN_SHARD_ID=trino-operations
CAMPAIGN_REPLICA_ID=1
for CAMPAIGN_ATTEMPT in 1 2 3 4; do
    CAMPAIGN_HOST_EPOCH=$((CAMPAIGN_ATTEMPT * 4 + 1))
    subnet_rotation r8i.large | head -n 1
done
'''
        result = subprocess.run(['bash', '-c', selector + fixture], capture_output=True, text=True, check=True)
        self.assertEqual(set(result.stdout.splitlines()), {
            'subnet-a us-west-2a', 'subnet-b us-west-2b', 'subnet-c us-west-2c', 'subnet-d us-west-2d'})
        self.assertEqual(len(result.stdout.splitlines()), 4)

    def test_spot_capacity_failure_tries_the_regions_other_zones_first(self):
        source = AWS_RUNNER.read_text()
        functions = shell_functions(source, "subnet_rotation", "record_launch_zone", "launch_instance",
                                    "classify_launch_error", "launch_error_is_rejection")
        fixture = '''
aws_cli() {
    case "$*" in
        *describe-images*) printf '/dev/xvda\\n' ;;
        *describe-instance-type-offerings*)
            printf 'offerings\\n' >> "${SESSION_DIR}/described"
            printf 'us-east-2a us-east-2b us-east-2c\\n' ;;
        *describe-subnets*)
            printf 'subnets\\n' >> "${SESSION_DIR}/described"
            printf 'subnet-%s\\tus-east-2%s\\n' a a b b c c ;;
        *run-instances*)
            subnet=$*
            subnet=${subnet#*SubnetId=}
            printf '%s\\n' "${subnet%%,*}" >> "${SESSION_DIR}/tried"
            if (($(wc -l < "${SESSION_DIR}/tried") <= FULL_ZONES)); then
                printf 'An error occurred (%s) when calling the RunInstances operation\\n' "${CAPACITY_ERROR}" >&2
                return 254
            fi
            printf 'i-launched\\n' ;;
    esac
}
benchmark_ami_id() { printf 'ami-test\\n'; }
benchmark_ami_name() { :; }
jdk_archive_url() { :; }
jdk_archive_sha256() { :; }
create_user_data() { :; }
record_failure_classification() { printf '%s|%s\\n' "$1" "${2%%:*}"; }
sleep() { :; }
CAMPAIGN_PLATFORM=r8i CAMPAIGN_SHARD_ID=unit-worker CAMPAIGN_REPLICA_ID=1 CAMPAIGN_ATTEMPT=1
INSTANCE_MARKET_TYPE=spot LOWER_SESSION_ID=session VPC_ID=vpc-test
launch_instance intel x86_64 r8i.large parameter result.tar.gz || printf 'failed\\n'
printf '%s %s\\n' "${LAUNCHED_INSTANCE_ID:-none}" "${INTEL_AVAILABILITY_ZONE}"
'''
        # One full zone moves the launch to the next; only all three full make it the region's failure.
        for error, full_zones, expected in (
                ("InsufficientInstanceCapacity", 1, ["i-launched"]),
                ("UnfulfillableCapacity", 1, ["i-launched"]),
                ("InsufficientInstanceCapacity", 3, ["retryable-spot|launch-capacity", "failed", "none"])):
            with self.subTest(error=error, full_zones=full_zones), tempfile.TemporaryDirectory() as temporary:
                result = subprocess.run(
                    ["bash", "-c", functions + fixture], capture_output=True, text=True,
                    env={**os.environ, "SESSION_DIR": temporary, "FULL_ZONES": str(full_zones),
                         "CAPACITY_ERROR": error}, check=True)
                tried = Path(temporary, "tried").read_text().split()
                self.assertEqual(min(full_zones + 1, 3), len(set(tried)))
                self.assertEqual(len(tried), len(set(tried)))
                # The launch enumerates the region's zones and subnets once, however many it tries.
                self.assertEqual(["offerings", "subnets"], Path(temporary, "described").read_text().split())
                # The session records the zone it last tried.
                self.assertEqual(expected + [f"us-east-2{tried[-1][-1]}"],
                                 result.stdout.replace(" ", "\n").splitlines())

    def test_launch_without_a_public_subnet_fails_retryably_before_submission(self):
        source = AWS_RUNNER.read_text()
        functions = shell_functions(source, "subnet_rotation", "record_launch_zone", "launch_instance")
        fixture = '''
aws_cli() {
    case "$*" in
        *describe-images*) printf '/dev/xvda\\n' ;;
        *describe-instance-type-offerings*) printf 'us-east-2a\\n' ;;
        *run-instances*) printf 'submitted\\n' ;;
    esac
}
benchmark_ami_id() { printf 'ami-test\\n'; }
jdk_archive_url() { :; }
jdk_archive_sha256() { :; }
record_failure_classification() { printf '%s|%s\\n' "$1" "${2%%:*}"; }
LAUNCH_ATTEMPTED=0
trap 'printf "attempted=%s\\n" "${LAUNCH_ATTEMPTED}"' EXIT
launch_instance intel x86_64 r8i.large parameter result.tar.gz
'''
        result = subprocess.run(["bash", "-c", functions + fixture], capture_output=True, text=True)
        # Cleanup then knows no instance can exist, and the controller retries the launch.
        self.assertEqual((1, ["retryable-infrastructure|launch-preflight", "attempted=0"]),
                         (result.returncode, result.stdout.splitlines()))

    def test_aws_command_uses_standard_credential_chain_without_profile(self):
        with patch.dict(os.environ, {"AWS_REGION": "us-east-1"}, clear=True):
            self.assertEqual(
                RUN_CAMPAIGN.aws_output(SimpleNamespace()),
                ["aws", "--region", "us-east-1"])

        with patch.dict(
                os.environ,
                {"AWS_PROFILE": "benchmark", "AWS_REGION": "us-east-1"},
                clear=True):
            self.assertEqual(
                RUN_CAMPAIGN.aws_output(SimpleNamespace()),
                ["aws", "--profile", "benchmark", "--region", "us-east-1"])

    def test_aws_wrapper_omits_empty_profile(self):
        source = AWS_RUNNER.read_text()
        function_start = source.index("aws_cli()\n{")
        function_end = source.index("\n}\n", function_start) + 3
        function = source[function_start:function_end]
        script = (
            "AWS_PROFILE=\n"
            "AWS_REGION=us-east-1\n"
            "aws() { printf '%s\\n' \"$*\"; }\n"
            f"{function}\n"
            "aws_cli sts get-caller-identity\n"
            "AWS_PROFILE=benchmark\n"
            "aws_cli sts get-caller-identity\n")

        result = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=False)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            result.stdout.splitlines(),
            [
                "--region us-east-1 sts get-caller-identity",
                "--profile benchmark --region us-east-1 sts get-caller-identity",
            ])

    def test_job_environment_does_not_invent_aws_profile_or_trino_checkout(self):
        with tempfile.TemporaryDirectory() as temporary_directory, \
                patch.dict(os.environ, {}, clear=True):
            root = Path(temporary_directory)
            arguments = self.arguments(root)
            job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")

            with patch.object(RUN_CAMPAIGN, "check_output"):
                environment = RUN_CAMPAIGN.job_environment(
                    RUN_CAMPAIGN.place(job, RUN_CAMPAIGN.launch_placements(arguments, job)[0]), arguments, root, root)

            self.assertNotIn("AWS_PROFILE", environment)
            self.assertNotIn("TRINO_DIR", environment)

    def test_trino_checkout_is_required_only_for_jobs_that_use_it(self):
        # Every shard whose route handler measures or verifies against pinned Trino needs the checkout.
        trino_handlers = {"trino-operations", "trino-like"}
        with (SCRIPT.parent / "shard-dispatch.tsv").open(newline="") as input_file:
            dispatch = list(csv.DictReader(input_file, delimiter="\t"))
        for row in dispatch:
            job = RUN_CAMPAIGN.Job(self.platform, row["shard_id"], 1, 10, 1, "spot", "language-batch")
            with self.subTest(shard=row["shard_id"]), tempfile.TemporaryDirectory() as temporary_directory, \
                    patch.dict(os.environ, {}, clear=True), patch.object(RUN_CAMPAIGN.subprocess, "run"):
                root = Path(temporary_directory)
                if row["native_access_handler"] in trino_handlers:
                    with self.assertRaisesRegex(SystemExit, "requires TRINO_DIR"):
                        RUN_CAMPAIGN.validate_dependencies(root, SimpleNamespace(), [job])
                else:
                    RUN_CAMPAIGN.validate_dependencies(root, SimpleNamespace(), [job])

    def test_interruption_unavailability_quota_or_second_failure_moves_to_on_demand(self):
        # A Spot interruption, Spot launch unavailability in the only allowed region, or a second
        # failure of any kind. Other allowed regions do not delay On-Demand after a host started.
        for attempt, classification, detail, regions in (
                (1, "retryable-spot", "instance-terminated-no-capacity", ("us-east-2", "us-west-2")),
                (1, "retryable-spot", "launch-capacity:InsufficientInstanceCapacity", ("us-east-2",)),
                (1, "retryable-capacity", "", ()),
                (2, "retryable-infrastructure", "", ())):
            with self.subTest(attempt=attempt, classification=classification, regions=regions):
                job = RUN_CAMPAIGN.Job(
                    self.platform, "like-dfa-single-use", 1, 117, attempt, "spot", "language-batch", region="us-east-2")
                replacement = RUN_CAMPAIGN.replacement_job(job, 118, classification, detail, regions=regions)
                self.assertEqual(replacement.attempt, attempt + 1)
                self.assertEqual(replacement.market, "on-demand")

    def test_spot_vcpu_quota_failure_is_retryable(self):
        source = AWS_RUNNER.read_text()

        self.assertIn(
            "InsufficientInstanceCapacity|UnfulfillableCapacity|MaxSpotInstanceCountExceeded",
            source)
        self.assertIn('record_failure_classification retryable-capacity "vcpu-limit:', source)

    def test_session_bucket_omits_the_location_constraint_s3_rejects_in_us_east_1(self):
        source = AWS_RUNNER.read_text()

        self.assertIn('if [[ "${AWS_REGION}" == us-east-1 ]]; then\n'
                      '        aws_cli s3api create-bucket --bucket "${BUCKET}" >/dev/null\n    else', source)

    def test_session_file_appears_only_whole(self):
        source = AWS_RUNNER.read_text()

        self.assertNotIn('cat > "${SESSION_DIR}/session.txt"', source)
        self.assertIn('mv "${SESSION_DIR}/session.txt.new" "${SESSION_DIR}/session.txt"', source)

    def test_cleanup_ignores_repeated_termination_signals(self):
        source = AWS_RUNNER.read_text()
        cleanup = source[source.index("cleanup()\n{"):source.index("\n}\n\nabort_campaign()")]

        self.assertIn("trap '' INT TERM", cleanup)

    def test_termination_signal_marks_campaign_failed(self):
        source = AWS_RUNNER.read_text()

        self.assertIn("FAILURE_CLASSIFICATION=controller-abort", source)
        self.assertIn('FAILURE_DETAIL="received $1"', source)
        self.assertIn("trap cleanup EXIT", source)
        self.assertIn("trap 'abort_campaign TERM 143' TERM", source)

        function = source[
            source.index("abort_campaign()\n{"):
            source.index("\n}\n\ntrap cleanup EXIT") + 2]
        script = (
            "FAILURE_CLASSIFICATION=none\n"
            "FAILURE_DETAIL=none\n"
            "cleanup() { result=$?; printf '%s|%s|%s\\n' \"$result\" "
            "\"$FAILURE_CLASSIFICATION\" \"$FAILURE_DETAIL\"; exit \"$result\"; }\n"
            f"{function}\n"
            "trap cleanup EXIT\n"
            "trap 'abort_campaign TERM 143' TERM\n"
            "kill -TERM $$\n")

        result = subprocess.run(["bash", "-c", script], text=True, capture_output=True)

        self.assertEqual(143, result.returncode)
        self.assertEqual("143|controller-abort|received TERM\n", result.stdout)

    def test_cleanup_accepts_an_empty_remaining_instance_array(self):
        source = AWS_RUNNER.read_text()
        function = source[
            source.index("terminate_campaign_instances()\n{"):
            source.index("\n}\n\nverify_campaign_instance_ownership()") + 2]
        script = (
            "set -u\n"
            "INSTANCE_IDS=(i-test)\n"
            "retry_cleanup_command() { return 0; }\n"
            "instance_state() { printf 'terminated\\n'; }\n"
            f"{function}\n"
            "terminate_campaign_instances\n")

        result = subprocess.run(
            ["bash"],
            input=script,
            text=True,
            capture_output=True,
            check=False)

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_instance_state_retries_launch_propagation_not_found(self):
        source = AWS_RUNNER.read_text()
        function = source[
            source.index("instance_state()\n{"):
            source.index("\n}\n\nterminate_campaign_instances()") + 2]
        with tempfile.TemporaryDirectory() as temporary_directory:
            counter = Path(temporary_directory) / "counter"
            counter.write_text("0\n")
            script = (
                "set -u\n"
                "sleep() { :; }\n"
                "aws_cli() {\n"
                f"  attempt=$(cat {counter})\n"
                "  attempt=$((attempt + 1))\n"
                f"  printf '%s\\n' \"$attempt\" > {counter}\n"
                "  if ((attempt < 3)); then\n"
                "    printf 'InvalidInstanceID.NotFound\\n' >&2\n"
                "    return 1\n"
                "  fi\n"
                "  printf 'pending\\n'\n"
                "}\n"
                f"{function}\n"
                "instance_state i-new\n"
                f"cat {counter}\n")

            result = subprocess.run(
                ["bash"], input=script, text=True, capture_output=True, check=False)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("pending\n3\n", result.stdout)

    def test_instance_state_keeps_known_launch_pending_while_aws_propagates(self):
        source = AWS_RUNNER.read_text()
        function = source[
            source.index("instance_state()\n{"):
            source.index("\n}\n\nterminate_campaign_instances()") + 2]
        with tempfile.TemporaryDirectory() as temporary_directory:
            counter = Path(temporary_directory) / "counter"
            counter.write_text("0\n")
            script = (
                "set -u\n"
                "sleep() { :; }\n"
                "aws_cli() {\n"
                f"  attempt=$(cat {counter})\n"
                "  attempt=$((attempt + 1))\n"
                f"  printf '%s\\n' \"$attempt\" > {counter}\n"
                "  printf 'InvalidInstanceID.NotFound\\n' >&2\n"
                "  return 1\n"
                "}\n"
                f"{function}\n"
                "instance_state i-new\n"
                f"cat {counter}\n"
                f"printf '0\\n' > {counter}\n"
                "instance_state i-terminated true\n"
                f"cat {counter}\n")

            result = subprocess.run(
                ["bash"], input=script, text=True, capture_output=True, check=False)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("pending\n5\nterminated\n5\n", result.stdout)

    def test_absence_verification_retries_eventual_consistency(self):
        source = AWS_RUNNER.read_text()
        function = source[
            source.index("verify_aws_resource_absent()\n{"):
            source.index("\n}\n\ninstance_state()") + 2]
        with tempfile.TemporaryDirectory() as temporary_directory:
            counter = Path(temporary_directory) / "counter"
            counter.write_text("0\n")
            script = (
            "set -u\n"
            "sleep() { :; }\n"
            "aws_cli() {\n"
            f"  attempt=$(cat {counter})\n"
            "  attempt=$((attempt + 1))\n"
            f"  printf '%s\\n' \"$attempt\" > {counter}\n"
            "  if ((attempt < 3)); then return 0; fi\n"
            "  printf 'NoSuchBucket\\n' >&2\n"
            "  return 1\n"
            "}\n"
            f"{function}\n"
            "verify_aws_resource_absent bucket NoSuchBucket s3api head-bucket --bucket test\n"
            f"cat {counter}\n")

            result = subprocess.run(
                ["bash"], input=script, text=True, capture_output=True, check=False)

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("3\n", result.stdout)

    def test_launch_error_classification_distinguishes_spot_and_on_demand_capacity(self):
        source = AWS_RUNNER.read_text()
        function = source[
            source.index("classify_launch_error()\n{"):
            source.index("\n}\n\nretry_cleanup_command()") + 2]
        script = (
            "record_failure_classification() { printf '%s|%s\\n' \"$1\" \"$2\"; }\n"
            f"{function}\n"
            "classify_launch_error spot VcpuLimitExceeded\n"
            "classify_launch_error on-demand VcpuLimitExceeded\n"
            "classify_launch_error spot SpotMaxPriceTooLow\n"
            "if classify_launch_error on-demand InternalError; then exit 9; fi\n")

        result = subprocess.run(
            ["bash"], input=script, text=True, capture_output=True, check=False)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), [
            "retryable-spot|launch-capacity:VcpuLimitExceeded",
            "retryable-capacity|vcpu-limit:VcpuLimitExceeded",
            # A Spot price above the ceiling rejects the request outright.
            "retryable-spot|launch-capacity:SpotMaxPriceTooLow",
        ])

    def test_retryable_no_instance_launch_cleans_all_temporary_resources(self):
        source = AWS_RUNNER.read_text()
        functions = shell_functions(
            source, "aws_cli", "record_failure_classification", "is_retryable_failure_classification",
            "classify_launch_error", "launch_error_is_rejection", "discover_campaign_instances", "cleanup",
            "subnet_rotation", "record_launch_zone", "launch_instance")
        fixture = '''
aws() {
    case "$*" in
        *describe-images*) printf '/dev/xvda\\n' ;;
        *describe-instance-type-offerings*) printf 'us-east-2b\\n' ;;
        *describe-subnets*) printf 'subnet-b\\tus-east-2b\\n' ;;
        *run-instances*)
            printf '%s\\n' "${AWS_MAX_ATTEMPTS:-unlimited}" >> "${SESSION_DIR}/submitted"
            # Unless its attempts are limited, the CLI resubmits on its own and reports only the last error.
            for ((request = 0; request < ${AWS_MAX_ATTEMPTS:-5}; request++)); do
                printf 'x\\n' >> "${SESSION_DIR}/requests"
                error='An error occurred (RequestLimitExceeded) when calling the RunInstances operation'
                if [[ -n "${FIRST_ERROR}" && $(wc -l < "${SESSION_DIR}/requests") -eq 1 ]]; then
                    error=${FIRST_ERROR}
                fi
            done
            printf '%s\\n' "${error}" >&2
            return 254 ;;
        *describe-instances*) ;;
    esac
}
benchmark_ami_id() { printf 'ami-test\\n'; }
benchmark_ami_name() { :; }
jdk_archive_url() { :; }
jdk_archive_sha256() { :; }
create_user_data() { :; }
retry_cleanup_command() { printf 'removed %s\\n' "$2" >&2; }
verify_aws_resource_absent() { printf 'verified %s\\n' "$1" >&2; }
sleep() { :; }
SESSION_ID=session LOWER_SESSION_ID=session VPC_ID=vpc-test INSTANCE_MARKET_TYPE=on-demand AWS_PROFILE=
FAILURE_CLASSIFICATION=none FAILURE_DETAIL= LAUNCH_ATTEMPTED=0 LAUNCH_AMBIGUOUS=0 INSTANCE_IDS=()
INSTANCE_PROFILE_NAME=test-profile INSTANCE_ROLE_NAME=test-role BUCKET=test-bucket BUCKET_OWNED=1
AWS_REGION=us-east-2 INTEL_AVAILABILITY_ZONE=none ARM_AVAILABILITY_ZONE=none CAMPAIGN_PLACEMENT_SCORE=9
OBJECT_PREFIX= CAMPAIGN_MODE=engine-build CAMPAIGN_ID=baseline CAMPAIGN_PLATFORM=r8i
CAMPAIGN_SHARD_ID=engine-build CAMPAIGN_REPLICA_ID=1 CAMPAIGN_ATTEMPT=1 CAMPAIGN_HOST_EPOCH=10
DOWNLOADED_RESULT_COUNT=0
launch_instance intel x86_64 r8i.large parameter result.tar.gz || cleanup
'''
        # RequestLimitExceeded proves AWS launched nothing. A timed-out submission may have launched an
        # instance that discovery does not see yet, even though every later submission was rejected, so each
        # submission's outcome must reach the launch loop, which alone retries.
        for first_error, expected in (
                ("", "verified"),
                ("Read timeout on endpoint URL: \"https://ec2.us-east-2.amazonaws.com/\"", "failed")):
            with self.subTest(first_error=first_error), tempfile.TemporaryDirectory() as temporary_directory:
                session = Path(temporary_directory) / "session"
                session.mkdir()
                result = subprocess.run(
                    ["bash", "-c", f"SESSION_DIR={session}\n" + functions + fixture],
                    env={**os.environ, "FIRST_ERROR": first_error}, text=True, capture_output=True, check=False)

                self.assertEqual(result.returncode, 1, result.stderr)
                cleanup = RUN_CAMPAIGN.read_properties(session / "cleanup-manifest.txt")
                self.assertEqual(cleanup["failure_classification"], "retryable-infrastructure")
                self.assertEqual(cleanup["cleanup_status"], expected)
                self.assertEqual((session / "submitted").read_text().split(), ["1"] * 5)
                self.assertEqual(cleanup["run_status"], "failed")
                if expected == "verified":
                    self.assertEqual(
                        [cleanup[field] for field in (
                            "instances_terminated", "iam_removed", "bucket_removed", "aws_region",
                            "intel_availability_zone", "placement_score")],
                        ["verified", "verified", "verified", "us-east-2", "us-east-2b", "9"])
                    self.assertLessEqual(
                        {"removed delete-instance-profile", "removed delete-role", "removed delete-bucket",
                         "verified instance profile test-profile", "verified role test-role",
                         "verified bucket test-bucket"},
                        set(result.stderr.splitlines()))
                else:
                    self.assertIn("Cleanup refused", result.stderr)
                    self.assertEqual(cleanup["instances_terminated"], "not-created")

    def test_retryable_launch_does_not_hide_failed_instance_discovery(self):
        source = AWS_RUNNER.read_text()
        retryable_function = source[
            source.index("is_retryable_failure_classification()\n{"):
            source.index("\n}\n\nclassify_launch_error()") + 2]
        cleanup_function = source[
            source.index("cleanup()\n{"):
            source.index("\n}\n\nabort_campaign()") + 2]
        with tempfile.TemporaryDirectory() as temporary_directory:
            session = Path(temporary_directory) / "session"
            session.mkdir()
            script = (
                f"SESSION_DIR={session}\n"
                "SESSION_ID=session\n"
                "FAILURE_CLASSIFICATION=retryable-capacity\n"
                "FAILURE_DETAIL=vcpu-limit:VcpuLimitExceeded\n"
                "LAUNCH_ATTEMPTED=1\n"
                "INSTANCE_IDS=()\n"
                "INSTANCE_PROFILE_NAME=test-profile\n"
                "INSTANCE_ROLE_NAME=test-role\n"
                "BUCKET=test-bucket\n"
                "CAMPAIGN_MODE=engine-build\n"
                "CAMPAIGN_ID=baseline\n"
                "CAMPAIGN_PLATFORM=r8i\n"
                "CAMPAIGN_SHARD_ID=engine-build\n"
                "CAMPAIGN_REPLICA_ID=1\n"
                "CAMPAIGN_HOST_EPOCH=10\n"
                "DOWNLOADED_RESULT_COUNT=0\n"
                "discover_campaign_instances() { return 1; }\n"
                f"{retryable_function}\n"
                f"{cleanup_function}\n"
                "false\n"
                "cleanup\n")

            result = subprocess.run(
                ["bash"], input=script, text=True, capture_output=True, check=False)

            self.assertEqual(result.returncode, 1)
            self.assertIn("Cleanup refused", result.stderr)
            cleanup = RUN_CAMPAIGN.read_properties(session / "cleanup-manifest.txt")
            self.assertEqual(cleanup["instances_terminated"], "not-created")
            self.assertEqual(cleanup["iam_removed"], "unverified")
            self.assertEqual(cleanup["bucket_removed"], "unverified")
            self.assertEqual(cleanup["cleanup_status"], "failed")

    def test_preflight_failure_removes_what_setup_created_and_retries_as_infrastructure(self):
        source = AWS_RUNNER.read_text()
        cleanup_function = source[
            source.index("cleanup()\n{"):
            source.index("\n}\n\nabort_campaign()") + 2]
        classification_function = source[
            source.index("record_failure_classification()\n{"):
            source.index("\n}\n\nspot_request_status()") + 2]
        # Cleanup runs at two points. Before setup created anything, as after a failed default VPC or
        # security group lookup. After setup created the bucket and instance profile, as after a launch
        # that failed before its RunInstances request.
        for created in (False, True):
            with self.subTest(created=created), tempfile.TemporaryDirectory() as temporary_directory:
                session = Path(temporary_directory) / "session"
                session.mkdir()
                resources = "test" if created else ""
                script = (
                    f"SESSION_DIR={session}\n"
                    "FAILURE_CLASSIFICATION=\n"
                    "FAILURE_DETAIL=preflight\n"
                    "LAUNCH_ATTEMPTED=0\n"
                    "INSTANCE_IDS=()\n"
                    f"INSTANCE_PROFILE_NAME={resources}\n"
                    f"INSTANCE_ROLE_NAME={resources}\n"
                    f"BUCKET={resources}\n"
                    f"BUCKET_OWNED={int(created)}\n"
                    "OBJECT_PREFIX=\n"
                    "CAMPAIGN_MODE=engine-build\n"
                    "CAMPAIGN_ID=baseline\n"
                    "CAMPAIGN_PLATFORM=r8i\n"
                    "CAMPAIGN_SHARD_ID=engine-build\n"
                    "CAMPAIGN_REPLICA_ID=1\n"
                    "CAMPAIGN_HOST_EPOCH=10\n"
                    "DOWNLOADED_RESULT_COUNT=0\n"
                    "retry_cleanup_command() { printf 'removed %s\\n' \"$2\" >&2; }\n"
                    "verify_aws_resource_absent() { printf 'verified %s\\n' \"$1\" >&2; }\n"
                    f"{classification_function}\n"
                    f"{cleanup_function}\n"
                    "false\n"
                    "cleanup\n")

                result = subprocess.run(
                    ["bash"], input=script, text=True, capture_output=True, check=False)

                self.assertEqual(result.returncode, 1, result.stderr)
                cleanup = RUN_CAMPAIGN.read_properties(session / "cleanup-manifest.txt")
                self.assertEqual(cleanup["instances_terminated"], "verified")
                self.assertEqual(cleanup["iam_removed"], "verified")
                self.assertEqual(cleanup["bucket_removed"], "verified")
                self.assertEqual(cleanup["cleanup_status"], "verified")
                # No instance can exist, so the controller retries it as infrastructure.
                self.assertEqual(cleanup["failure_classification"], "retryable-infrastructure")
                if created:
                    self.assertLessEqual(
                        {"removed delete-instance-profile", "removed delete-role", "removed delete-bucket",
                         "verified instance profile test", "verified role test", "verified bucket test"},
                        set(result.stderr.splitlines()))

    def test_semantic_evidence_uses_actual_surefire_report_directory(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertNotIn("-Dsurefire.reportsDirectory=", source)
        self.assertIn('default_semantic_report_dir="${ROOT}/target/surefire-reports"', source)
        self.assertIn('cp "${default_semantic_report_dir}"/TEST-*.xml "${semantic_report_dir}/"', source)

    def semantic_verifier_script(self, source, directory):
        """The run-shard.sh semantic-verifier functions, set up for a native-access route under directory."""
        functions = source[source.index("SEMANTIC_FAILURE_STATUS="):source.index("\n}\n", source.index(
            "run_semantic_verifier()")) + 3]
        quoted = shlex.quote(str(directory))
        return (f"set -euo pipefail\nRESULT_DIR={quoted}\nmkdir -p \"${{RESULT_DIR}}/logs\"\nROOT={quoted}\n"
                f"SHARED_WORK_DIR={quoted}/shared\nROUTE=native-access\nCPU_LIST=0\nCLASSPATH=fixture\n"
                f"route_jvm_arguments=()\n{functions}\n")

    def test_failed_semantic_checks_exit_with_the_semantic_failure_status(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertIn(f"SEMANTIC_FAILURE_STATUS={RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS}\n", source)
        statements = re.sub(r"(\\|\|\||&&)\n\s*", r"\1 ", source).splitlines()
        # A verifier that verified too few cases fails as a verdict.
        checks = [statement for statement in statements if "grep -Fx 'Verified" in statement]
        self.assertEqual(3, len(checks), checks)
        self.assertTrue(all("|| semantic_failure " in statement for statement in checks), checks)
        # The DFA probe, every Java verifier, and the pinned Trino comparator's verification fail as a verdict.
        # Only the identity check of a prebuilt harness, whose failure rebuilds it, runs bare.
        verifiers = [statement for statement in statements if re.search(
            r" (io\.airlift\.regulator\.DfaAbsolutePointerProbe|\S*Verifier|\S*BenchmarkTrinoLike\S*)( |$)",
            statement) and " java " in statement]
        verifiers += [statement for statement in statements
                      if "trino-joni/verify.sh" in statement and not statement.lstrip().startswith("if ")]
        self.assertEqual(5, len(verifiers), verifiers)
        self.assertTrue(all(statement.lstrip().startswith("run_semantic_verifier ") for statement in verifiers),
                        verifiers)

        probe = source[source.index("expected_native_access=false\n"):source.index("# Units of one shard on one host")]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prefix = self.semantic_verifier_script(source, temporary)
            # The DFA probe runs its check on another thread, so its assertion reaches main wrapped.
            for output, status, expected in (
                    ('Exception in thread "main" java.util.concurrent.ExecutionException: java.lang.AssertionError: '
                     'unexpected native-access capability', 1, RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS),
                    ("OK enabled", 0, 0)):
                with self.subTest(output=output):
                    script = (prefix + "taskset() { shift 2; \"$@\"; }\n"
                              f"java() {{ echo {shlex.quote(output)} >&2; return {status}; }}\n" + probe)
                    result = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=False)
                    self.assertEqual(expected, result.returncode, result.stderr)
                    self.assertIn(output, (root / "logs" / "semantic-tests.log").read_text())

    def test_prebuilt_trino_harness_is_rebuilt_only_when_its_identity_fails(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prefix = self.semantic_verifier_script(source, temporary)
            # A prebuilt Trino harness is rebuilt only when its identity fails, before any semantic check runs;
            # an identity-only setting in the environment does not reach the full verification.
            comparator = root / "tools/re2-benchmark/trino-joni"
            comparator.mkdir(parents=True)
            (comparator / "verify.sh").write_text(
                '#!/bin/bash\nprintf fixture > "${TRINO_COMPARATOR_WORK_DIR}/classpath.txt"\n'
                '[[ -f "${TRINO_COMPARATOR_WORK_DIR}/rebuilt" ]] || [[ "${FAIL}" == semantics ]] || exit 1\n'
                '[[ "${TRINO_COMPARATOR_IDENTITY_ONLY:-false}" == true || "${FAIL}" != semantics ]] || exit 1\n')
            (comparator / "prepare.sh").write_text('#!/bin/bash\ntouch "${TRINO_COMPARATOR_WORK_DIR}/rebuilt"\n')
            for script_path in comparator.iterdir():
                script_path.chmod(0o755)
            for failure, identity_only, expected, rebuilt in (
                    ("semantics", "", RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS, False),
                    ("semantics", "export TRINO_COMPARATOR_IDENTITY_ONLY=true\n",
                     RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS, False),
                    ("identity", "", 0, True)):
                with self.subTest(failure=failure, identity_only=bool(identity_only)):
                    shutil.rmtree(root / "shared", ignore_errors=True)
                    (root / "shared/engine-build").mkdir(parents=True)
                    (root / "shared/engine-build/trino.sha256").write_text("a" * 64)
                    script = (prefix + identity_only + "expected_native_access=true\n"
                              + shell_functions(source, "prebuilt_engine", "prepare_pinned_trino")
                              + f"FAIL={failure} prepare_pinned_trino\n")
                    result = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=False)
                    self.assertEqual(expected, result.returncode, result.stderr)
                    self.assertEqual(rebuilt, (root / "shared/pinned-trino/rebuilt").exists())

            # A cached harness that lists no benchmark methods fails the real identity check, so it is
            # rebuilt before any semantic check runs; the full verification then runs each verifier once.
            shutil.rmtree(comparator)
            shutil.copytree(SCRIPT.parents[1] / "trino-joni", comparator, ignore=shutil.ignore_patterns("prepare.sh"))
            (comparator / "prepare.sh").write_text('#!/bin/bash\ntouch "${TRINO_COMPARATOR_WORK_DIR}/rebuilt"\n')
            (comparator / "prepare.sh").chmod(0o755)
            verify = (comparator / "verify.sh").read_text()
            pins = dict(line.split("=", 1) for line in (comparator / "pins.env").read_text().splitlines())
            version = pins["TRINO_COMPARATOR_JONI_VERSION"]
            joni = root / f"repository/io/airlift/joni/{version}/joni-{version}.jar"
            joni.parent.mkdir(parents=True)
            joni.write_text("fixture joni")
            pins["TRINO_COMPARATOR_JONI_SHA256"] = hashlib.sha256(joni.read_bytes()).hexdigest()
            (comparator / "pins.env").write_text("".join(f"{name}={value}\n" for name, value in pins.items()))
            provenance = [f"{key}={pins[pin]}" for key, pin in re.findall(r'grep -Fqx "(\w+)=\$\{(\w+)\}"', verify)]
            provenance += [f"{key}={hashlib.sha256((comparator / name).read_bytes()).hexdigest()}"
                           for key, name in re.findall(r'grep -Fqx "(\w+)=\$\(sha256 "\$\{SCRIPT_DIR\}/(\S+)"\)"',
                                                         verify)]
            shutil.rmtree(root / "shared", ignore_errors=True)
            work = root / "shared/pinned-trino"
            work.mkdir(parents=True)
            (root / "shared/engine-build").mkdir()
            (root / "shared/engine-build/trino.sha256").write_text("a" * 64)
            (work / "provenance.properties").write_text("".join(f"{line}\n" for line in provenance))
            (work / "classpath.txt").write_text(str(joni))
            (work / "joni-jar.path").write_text(str(joni))
            (root / "benchmarks.txt").write_text(verify.split("<<'EOF'\n")[1].split("\nEOF\n")[0] + "\n")
            binary = root / "bin"
            binary.mkdir()
            (binary / "java").write_text(
                '#!/bin/bash\ncase " $* " in\n'
                '    *" org.openjdk.jmh.Main "*)\n'
                '        [[ ! -f "${TRINO_COMPARATOR_WORK_DIR}/rebuilt" ]] || '
                f'cat {shlex.quote(str(root / "benchmarks.txt"))} ;;\n'
                f'    *) for argument; do [[ ${{argument}} != *Verifier ]] || echo "${{argument}}" '
                f'>> {shlex.quote(str(root / "verifiers"))}; '
                'done ;;\nesac\n')
            (binary / "java").chmod(0o755)
            script = (prefix + "expected_native_access=true\n"
                      + shell_functions(source, "prebuilt_engine", "prepare_pinned_trino") + "prepare_pinned_trino\n")
            result = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=False,
                                    env={**os.environ, "PATH": f"{binary}{os.pathsep}{os.environ['PATH']}"})
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue((work / "rebuilt").exists())
            verifier_classes = sorted(set(re.findall(r"io\.trino\.operator\.scalar\.(\w+Verifier)\b", verify)))
            self.assertEqual(5, len(verifier_classes), verifier_classes)
            self.assertEqual([f"io.trino.operator.scalar.{verifier}" for verifier in verifier_classes],
                             sorted((root / "verifiers").read_text().splitlines()))

            # The real verification refuses an unknown identity-only setting and a classpath entry not on this host.
            missing = root / "repository/missing.jar"
            for identity_only, classpath, message in (
                    ("yes", str(joni), "TRINO_COMPARATOR_IDENTITY_ONLY must be true or false: yes"),
                    ("true", f"{joni}:{missing}", f"Comparator classpath entry is missing: {missing}")):
                with self.subTest(refusal=message):
                    (work / "classpath.txt").write_text(classpath)
                    result = subprocess.run(
                        [comparator / "verify.sh"], text=True, capture_output=True, check=False,
                        env={**os.environ, "PATH": f"{binary}{os.pathsep}{os.environ['PATH']}",
                             "TRINO_COMPARATOR_WORK_DIR": str(work), "TRINO_COMPARATOR_IDENTITY_ONLY": identity_only})
                    self.assertEqual(1, result.returncode, result.stderr)
                    self.assertIn(message, result.stderr)

    def test_semantic_failure_outranks_a_rejected_unit_capacity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            # The unit's own script, beside a stubbed planner and shard, on a host whose swap is in use.
            baseline = root / "tools/re2-benchmark/baseline"
            baseline.mkdir(parents=True)
            proc = root / "proc"
            proc.mkdir()
            (proc / "meminfo").write_text("MemTotal: 64000000 kB\nMemAvailable: 60000000 kB\n"
                                          "SwapTotal: 1024 kB\nSwapFree: 0 kB\n")
            (proc / "vmstat").write_text("oom_kill 0\n")
            binary = root / "bin"
            binary.mkdir()
            (binary / "df").write_text("#!/bin/bash\nprintf 'Avail\\n999999999999\\n'\n")
            (binary / "time").write_text(
                '#!/bin/bash\nfor argument; do case "${argument}" in --output=*) output=${argument#--output=};; '
                '--*) ;; *) break;; esac; shift; done\n"$@"\nstatus=$?\n'
                'printf "wall_seconds=1\\nmaximum_resident_kibibytes=1\\nexit_status=%s\\n" "${status}" >> "${output}"\n'
                'exit "${status}"\n')
            source = (SCRIPT.parent / "run-unit.sh").read_text()
            (baseline / "run-unit.sh").write_text(
                source.replace("/proc/", f"{proc}/").replace("/usr/bin/time", str(binary / "time")))
            (baseline / "unit_session.py").write_text(
                "import sys\nfrom pathlib import Path\n"
                "if sys.argv[1] == 'copy-environment':\n    sys.exit()\n"
                "output = Path(sys.argv[sys.argv.index('--output-directory') + 1])\n"
                "(output / 'route-plan.tsv').write_text('route\\trows\\tsystems\\tcalibration\\n"
                "native-access\\t1\\tregulator\\tbefore\\n')\nprint('trino-operations')\n")
            (baseline / "run-shard.sh").write_text('#!/bin/bash\nexit "${SHARD_STATUS}"\n')
            for script_path in (*binary.iterdir(), baseline / "run-unit.sh", baseline / "run-shard.sh"):
                script_path.chmod(0o755)
            environment = {name: value for name, value in os.environ.items()
                           if not name.startswith(("BASELINE_", "RE2_", "JONI_"))}
            environment.update({
                "PATH": f"{binary}{os.pathsep}{os.environ['PATH']}", "RE2_CAMPAIGN_ID": "campaign",
                "RE2_CAMPAIGN_PLATFORM": "r8i", "RE2_CAMPAIGN_HOST_EPOCH": "1",
                "RE2_ENGINEERING_ARCHITECTURE": "intel", "BASELINE_INSTANCE_ID": "i-1",
                "BASELINE_INSTANCE_TYPE": "r8i.large", "BASELINE_AVAILABILITY_ZONE": "us-west-2a",
                "RE2_CAMPAIGN_HOST_SESSION": "host-session-1", "RE2_CAMPAIGN_LEASE_SEQUENCE": "1"})
            # A semantic failure stays the unit's verdict; any other failure, a protocol rejection among them,
            # is the capacity gate's.
            for shard_status, expected in ((RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS,
                                            RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS),
                                           (RUN_CAMPAIGN.PROTOCOL_REJECTION_STATUS, 1)):
                with self.subTest(shard_status=shard_status):
                    unit = root / f"unit-{shard_status}"
                    result = subprocess.run(
                        [str(baseline / "run-unit.sh"), "unit", "qualification", "1", str(unit)],
                        env={**environment, "SHARD_STATUS": str(shard_status)},
                        capture_output=True, text=True, check=False)
                    self.assertEqual(expected, result.returncode, result.stderr)
                    capacity = (unit / "unit-capacity.txt").read_text()
                    self.assertIn(f"exit_status={shard_status}\n", capacity)
                    self.assertIn("capacity_status=rejected\n", capacity)

    def test_missing_package_version_is_one_property_value(self):
        source = (SCRIPT.parents[1] / "aws" / "run-host.sh").read_text()
        function_start = source.index("package_version()")
        function_end = source.index("\n}\n", function_start) + 3
        function = source[function_start:function_end]
        script = (
            "set -euo pipefail\n"
            f"{function}\n"
            "rpm() { printf 'package cargo is not installed\\n'; return 1; }\n"
            "test \"$(package_version cargo)\" = not-installed\n")

        result = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=False)

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_trino_joni_preparation_supports_source_archives(self):
        source = (SCRIPT.parents[1] / "trino-joni" / "prepare.sh").read_text()

        self.assertEqual(source.count("./mvnw -q -Dmaven.gitcommitid.skip=true"), 2)

    def test_protocol_representative_awk_avoids_builtin_names(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertNotIn('-v system="${representative_system}"', source)
        self.assertIn('-v target_system="${representative_system}"', source)

    def test_protocol_qualification_samples_five_independent_jvms(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertIn("PROTOCOL_QUALIFICATION_PROCESS_COUNT=5", source)
        self.assertIn('-f "${PROTOCOL_QUALIFICATION_PROCESS_COUNT}"', source)
        self.assertIn(
            'process_index <= PROTOCOL_QUALIFICATION_PROCESS_COUNT',
            source,
        )
        self.assertIn(
            '"${PROTOCOL_QUALIFICATION_PROCESS_COUNT}-independent-jvms-',
            source,
        )

    def test_optional_protocol_qualification_is_a_successful_no_op(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()
        function_start = source.index("run_protocol_qualification()")
        function_end = source.index("\n}\n", function_start) + 3
        function = source[function_start:function_end]
        script = (
            "set -euo pipefail\n"
            "protocol_qualification_required=false\n"
            f"{function}\n"
            "run_protocol_qualification\n"
            "echo reached\n")

        result = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=False)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "reached\n")

    def test_calibration_uses_process_stable_workload(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertIn(
            "calibration_filter='^io\\.airlift\\.regulator\\.BenchmarkExpressionPlans\\.findLiteral$'",
            source)
        self.assertIn("calibration_parameters=(-p workload=KIB_LATE)", source)
        self.assertEqual(source.count('"${calibration_parameters[@]}"'), 3)
        self.assertNotIn("calibration-before.json\" -p textSize=32768", source)
        self.assertNotIn("calibration-after.json\" -p textSize=32768", source)

    def test_remote_benchmark_failure_is_classified(self):
        source = (SCRIPT.parent.parent / "aws" / "run-campaign.sh").read_text()

        self.assertIn(
            'record_failure_classification benchmark-failure "remote-exit-status:${result_status}"',
            source)

    def test_cleanup_records_and_guards_its_shell_origin(self):
        source = (SCRIPT.parent.parent / "aws" / "run-campaign.sh").read_text()

        self.assertIn('ROOT_BASHPID=${BASHPID}', source)
        self.assertIn('cleanup_bash_pid=${BASHPID}', source)
        self.assertIn('cleanup_subshell=${BASH_SUBSHELL}', source)
        self.assertIn("printf 'cleanup_cause=%s\\n' \"${CLEANUP_CAUSE:-exit}\"", source)
        self.assertIn('${BASHPID} -ne ${ROOT_BASHPID}', source)
        self.assertIn('CLEANUP_CAUSE=$1', source)
        self.assertIn("trap 'abort_campaign INT 130' INT", source)
        self.assertIn("trap 'abort_campaign TERM 143' TERM", source)

    def test_replacement_allowance_is_bounded(self):
        final_attempt = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 118, 3, "on-demand", "language-batch")

        with self.assertRaisesRegex(RuntimeError, "exhausted"):
            RUN_CAMPAIGN.replacement_job(final_attempt, 119, "retryable-infrastructure")
        # A move past a region without Spot capacity is not a replacement.
        moved = RUN_CAMPAIGN.replace(final_attempt, spot_capacity_failures=("us-east-2",))
        self.assertEqual(RUN_CAMPAIGN.replacement_job(moved, 119, "retryable-infrastructure").attempt, 4)
        with self.assertRaisesRegex(RuntimeError, "exhausted"):
            RUN_CAMPAIGN.replacement_job(
                RUN_CAMPAIGN.replace(moved, attempt=4), 120, "retryable-infrastructure")

    def test_retry_requires_verified_cleanup_and_aws_classification(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            session = RUN_CAMPAIGN.job_result_root(result_root, job) / "session"
            session.mkdir(parents=True)
            (session / "cleanup-manifest.txt").write_text(
                "instances_terminated=verified\n"
                "iam_removed=verified\n"
                "bucket_removed=verified\n"
                "network_resources=default-vpc-reused\n"
                "cleanup_status=verified\n"
                "run_status=failed\n"
                "failure_classification=retryable-spot\n"
                "failure_detail=instance-terminated-no-capacity\n")

            actual_session, cleanup = RUN_CAMPAIGN.validate_failed_job(result_root, job)

            self.assertEqual(actual_session, session)
            self.assertEqual(cleanup["failure_classification"], "retryable-spot")
            self.assertEqual(cleanup["failure_detail"], "instance-terminated-no-capacity")

    def test_retry_rejects_unverified_cleanup(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            session = RUN_CAMPAIGN.job_result_root(result_root, job) / "session"
            session.mkdir(parents=True)
            (session / "cleanup-manifest.txt").write_text(
                "instances_terminated=verified\n"
                "iam_removed=verified\n"
                "bucket_removed=retained-for-recovery\n"
                "network_resources=default-vpc-reused\n"
                "cleanup_status=failed\n"
                "run_status=failed\n"
                "failure_classification=retryable-spot\n"
                "failure_detail=instance-terminated-no-capacity\n")

            with self.assertRaisesRegex(RuntimeError, "bucket_removed"):
                RUN_CAMPAIGN.validate_failed_job(result_root, job)

    def test_ordinary_failure_does_not_stop_independent_job(self):
        failed_job = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        outstanding_job = RUN_CAMPAIGN.Job(
            self.platform, "like-compile", 1, 11, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            failed_process = FakeProcess(
                101,
                status=1,
                poll_action=lambda: self.write_cleanup_session(
                    result_root, failed_job, failure_classification="benchmark-failure"))
            outstanding_process = FakeProcess(
                102,
                status=1,
                poll_action=lambda: self.write_cleanup_session(
                    result_root, outstanding_job, failure_classification="benchmark-failure"))

            with patch.object(
                    RUN_CAMPAIGN.subprocess,
                    "Popen",
                    side_effect=[failed_process, outstanding_process]):
                with self.assertRaises(SystemExit) as failure:
                    RUN_CAMPAIGN.execute_jobs(
                        self.arguments(root, maximum_concurrent=2),
                        root,
                        result_root,
                        [failed_job, outstanding_job])

            message = str(failure.exception)
            self.assertIn("campaign completed with unresolved jobs", message)
            self.assertIn(failed_job.identity, message)
            self.assertIn(outstanding_job.identity, message)
            self.assertEqual(failed_process.signals, [])
            self.assertEqual(outstanding_process.signals, [])
            unresolved = (result_root / "unresolved-jobs.tsv").read_text()
            self.assertIn("like-dfa-single-use", unresolved)
            self.assertIn("like-compile", unresolved)
            RUN_CAMPAIGN.validate_cleanup_manifest(result_root, outstanding_job)

    def test_unverified_cleanup_stops_sibling_jobs(self):
        failed_job = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        sibling_job = RUN_CAMPAIGN.Job(
            self.platform, "like-compile", 1, 11, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            failed_process = FakeProcess(
                103,
                status=1,
                poll_action=lambda: self.write_cleanup_session(
                    result_root,
                    failed_job,
                    failure_classification="benchmark-failure",
                    bucket_removed="retained-for-recovery"))
            sibling_process = FakeProcess(
                104,
                wait_action=lambda: self.write_cleanup_session(result_root, sibling_job))

            with patch.object(
                    RUN_CAMPAIGN.subprocess,
                    "Popen",
                    side_effect=[failed_process, sibling_process]):
                with self.assertRaises(RUN_CAMPAIGN.UnsafeCampaignError) as failure:
                    RUN_CAMPAIGN.execute_jobs(
                        self.arguments(root, maximum_concurrent=2),
                        root,
                        result_root,
                        [failed_job, sibling_job])

            message = str(failure.exception)
            self.assertIn("cleanup could not be proven", message)
            self.assertIn("wrapper_pid=103", message)
            self.assertIn(failed_job.identity, message)
            self.assertIn("bucket=re2-baseline-test-bucket", message)
            self.assertIn("intel_instance_id=i-0123456789abcdef0", message)
            self.assertIn("bucket_removed='retained-for-recovery'", message)
            self.assertEqual(sibling_process.signals, [RUN_CAMPAIGN.signal.SIGTERM])
            self.assertEqual(sibling_process.wait_timeouts, [15 * 60])

    def test_accepted_receipt_is_persisted_without_waiting_for_campaign_success(self):
        accepted_job = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        failed_job = RUN_CAMPAIGN.Job(
            self.platform, "like-compile", 1, 11, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            receipt_path = root / "accepted-receipt.json"
            self.write_receipt(receipt_path, self.receipt_for_job(accepted_job))
            accepted_process = FakeProcess(105, status=0)
            failed_process = FakeProcess(
                106,
                status=1,
                poll_action=lambda: self.write_cleanup_session(
                    result_root, failed_job, failure_classification="benchmark-failure"))

            with patch.object(
                    RUN_CAMPAIGN.subprocess,
                    "Popen",
                    side_effect=[accepted_process, failed_process]), \
                    patch.object(RUN_CAMPAIGN, "validate_job_artifacts", return_value=receipt_path), \
                    patch.object(RUN_CAMPAIGN, "validate_receipt_candidate"):
                with self.assertRaises(SystemExit):
                    RUN_CAMPAIGN.execute_jobs(
                        self.arguments(root, maximum_concurrent=2),
                        root,
                        result_root,
                        [accepted_job, failed_job])

            accepted = RUN_CAMPAIGN.read_accepted_sessions(result_root / "accepted-language-sessions.json")
            self.assertEqual([RUN_CAMPAIGN.receipt_logical_identity(row) for row in accepted], [
                accepted_job.logical_identity])

    def test_restart_skips_previously_accepted_job(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            receipt_path = root / "accepted-receipt.json"
            receipt = self.receipt_for_job(job)
            self.write_receipt(receipt_path, receipt)
            RUN_CAMPAIGN.write_ledger(result_root / "accepted-language-sessions.json", [receipt])
            arguments = self.arguments(root)
            arguments.candidate_archive_sha256 = receipt["candidate_archive_sha256"]
            arguments.engine_tree = receipt["engine_tree"]
            arguments.heap_size = receipt["heap_size"]

            with patch.object(RUN_CAMPAIGN, "validate_job_artifacts", return_value=receipt_path), \
                    patch.object(RUN_CAMPAIGN, "check_output", return_value=receipt["candidate_commit"]), \
                    patch.object(RUN_CAMPAIGN.subprocess, "Popen") as popen:
                RUN_CAMPAIGN.execute_jobs(
                    arguments, root, result_root, [job])

            popen.assert_not_called()
            self.assertEqual(
                RUN_CAMPAIGN.read_accepted_sessions(result_root / "accepted-language-sessions.json"),
                [receipt])

    def test_recovery_rejects_receipt_from_different_heap(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            receipt = self.receipt_for_job(job)
            receipt_path = root / "accepted-receipt.json"
            self.write_receipt(receipt_path, receipt)
            ledger = root / "accepted-language-sessions.json"
            RUN_CAMPAIGN.write_ledger(ledger, [receipt])
            arguments = self.arguments(root)
            arguments.candidate_archive_sha256 = receipt["candidate_archive_sha256"]
            arguments.engine_tree = receipt["engine_tree"]
            arguments.heap_size = "2g"
            with patch.object(RUN_CAMPAIGN, "check_output", return_value=receipt["candidate_commit"]), \
                    patch.object(RUN_CAMPAIGN, "validate_job_artifacts", return_value=receipt_path):
                with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "heap_size"):
                    RUN_CAMPAIGN.recover_accepted_sessions(root, arguments, root, [job], ledger)

    def test_restart_waits_for_detached_wrapper_cleanup(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            attempts = result_root / "job-attempts.tsv"
            RUN_CAMPAIGN.write_attempt(attempts, job, "started", "wrapper_pid=123")

            with patch.object(RUN_CAMPAIGN, "wrapper_process_id", return_value=123), \
                    patch.object(
                        RUN_CAMPAIGN.time,
                        "sleep",
                        side_effect=lambda _: self.write_cleanup_session(result_root, job)):
                RUN_CAMPAIGN.settle_detached_attempts(result_root, [job], attempts)

            RUN_CAMPAIGN.validate_cleanup_manifest(result_root, job)

    def test_restart_rejects_detached_wrapper_without_cleanup(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            attempts = result_root / "job-attempts.tsv"
            RUN_CAMPAIGN.write_attempt(attempts, job, "started", "wrapper_pid=123")

            with patch.object(RUN_CAMPAIGN, "wrapper_process_id", return_value=None), \
                    patch.object(RUN_CAMPAIGN.time, "sleep"):
                with self.assertRaisesRegex(
                        RUN_CAMPAIGN.UnsafeCampaignError,
                        "exited without cleanup evidence"):
                    RUN_CAMPAIGN.settle_detached_attempts(result_root, [job], attempts)

    def test_restart_preserves_original_campaign_start_time(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            state = Path(temporary_directory) / "campaign-state.tsv"
            arguments = self.arguments(Path(temporary_directory))
            with patch.object(RUN_CAMPAIGN.time, "time", return_value=1000.0):
                first = RUN_CAMPAIGN.campaign_started_at(state, arguments)
            with patch.object(RUN_CAMPAIGN.time, "time", return_value=2000.0):
                second = RUN_CAMPAIGN.campaign_started_at(state, arguments)

            self.assertEqual(first, 1000.0)
            self.assertEqual(second, first)

    def test_restart_rejects_changed_heap_before_recovery_or_launch(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            with patch.dict(os.environ, {"BENCHMARK_HEAP_SIZE": "8g"}):
                original = self.arguments(root)
                RUN_CAMPAIGN.campaign_started_at(root / "campaign-state.tsv", original)
            with patch.dict(os.environ, {"BENCHMARK_HEAP_SIZE": "2g"}):
                resumed = self.arguments(root)
                with patch.object(RUN_CAMPAIGN, "settle_detached_attempts") as settle, \
                        patch.object(RUN_CAMPAIGN, "recover_accepted_sessions") as recover, \
                        patch.object(RUN_CAMPAIGN.subprocess, "Popen") as launch:
                    with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "heap_size"):
                        RUN_CAMPAIGN.execute_jobs_locked(resumed, root, root, [])
                settle.assert_not_called()
                recover.assert_not_called()
                launch.assert_not_called()

    def test_job_environment_uses_captured_heap_not_later_environment(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            arguments = self.arguments(root)
            job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 1, 1, "spot", "language-batch")
            with patch.dict(os.environ, {"BENCHMARK_HEAP_SIZE": ""}):
                self.assertEqual("8g", RUN_CAMPAIGN.campaign_heap_size(arguments))
            with patch.dict(os.environ, {"BENCHMARK_HEAP_SIZE": "2g"}):
                environment = RUN_CAMPAIGN.job_environment(job, arguments, root, root)
            self.assertEqual("8g", environment["BENCHMARK_HEAP_SIZE"])

    def test_starting_attempt_without_process_retries_on_fresh_epoch(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            attempts = result_root / "job-attempts.tsv"
            RUN_CAMPAIGN.write_attempt(attempts, job, "starting")

            with patch.object(RUN_CAMPAIGN, "wrapper_process_id", return_value=None), \
                    patch.object(RUN_CAMPAIGN.time, "sleep"):
                RUN_CAMPAIGN.settle_detached_attempts(result_root, [job], attempts)
            pending, unresolved, next_epoch = RUN_CAMPAIGN.prepare_resumed_jobs(
                result_root, [job], attempts, {})

            self.assertEqual(unresolved, [])
            self.assertEqual(len(pending), 1)
            replacement, _ = pending[0]
            self.assertEqual(replacement.host_epoch, 11)
            self.assertEqual(replacement.attempt, 2)
            self.assertEqual(next_epoch, 12)

    def test_budget_recovery_releases_settled_prelaunch_reservation(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            attempts = result_root / "job-attempts.tsv"
            RUN_CAMPAIGN.write_attempt(attempts, job, "starting")
            budget = Mock()
            budget.reservations.return_value = [{
                "campaign_id": "baseline",
                "logical_identity": job.logical_identity,
                "host_epoch": job.host_epoch,
                "attempt": job.attempt,
            }]

            with patch.object(RUN_CAMPAIGN, "wrapper_process_id", return_value=None), \
                    patch.object(RUN_CAMPAIGN.time, "sleep"):
                RUN_CAMPAIGN.settle_detached_attempts(result_root, [job], attempts)
            RUN_CAMPAIGN.recover_budget_reservations(
                budget, SimpleNamespace(campaign_id="baseline"), result_root, [job], attempts)

            budget.release.assert_called_once_with("baseline", 10, cleanup_verified=True)

    def test_budget_recovery_requires_cleanup_after_launch(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            attempts = result_root / "job-attempts.tsv"
            RUN_CAMPAIGN.write_attempt(attempts, job, "starting")
            RUN_CAMPAIGN.write_attempt(attempts, job, "started", "wrapper_pid=123")
            RUN_CAMPAIGN.write_attempt(attempts, job, "retryable-infrastructure", "controller stopped")
            budget = Mock()
            budget.reservations.return_value = [{
                "campaign_id": "baseline",
                "logical_identity": job.logical_identity,
                "host_epoch": job.host_epoch,
                "attempt": job.attempt,
            }]

            with self.assertRaisesRegex(RuntimeError, "produced no result directory"):
                RUN_CAMPAIGN.recover_budget_reservations(
                    budget, SimpleNamespace(campaign_id="baseline"), result_root, [job], attempts)

            budget.release.assert_not_called()

    def test_resume_epoch_accounts_for_accepted_replacement(self):
        accepted_job = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 1, 1, "spot", "language-batch")
        failed_job = RUN_CAMPAIGN.Job(
            self.platform, "like-compile", 1, 2, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            attempts = result_root / "job-attempts.tsv"
            accepted_epoch = RUN_CAMPAIGN.job_result_root(
                result_root, RUN_CAMPAIGN.Job(
                    self.platform, "like-dfa-single-use", 1, 3, 2, "spot", "language-batch"))
            accepted_epoch.mkdir(parents=True)
            self.write_cleanup_session(
                result_root,
                failed_job,
                failure_classification="retryable-spot")
            RUN_CAMPAIGN.write_attempt(attempts, failed_job, "retryable-spot", "interrupted")

            pending, unresolved, next_epoch = RUN_CAMPAIGN.prepare_resumed_jobs(
                result_root,
                [accepted_job, failed_job],
                attempts,
                {accepted_job.logical_identity: self.receipt_for_job(accepted_job)})

            self.assertEqual(unresolved, [])
            replacement, _ = pending[0]
            self.assertEqual(replacement.host_epoch, 4)
            self.assertEqual(next_epoch, 5)

    def test_refill_replaces_each_exhausted_job_once_on_demand(self):
        accepted_job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 1, 1, "spot", "language-batch")
        exhausted_job = RUN_CAMPAIGN.Job(self.platform, "like-compile", 1, 2, 3, "spot", "language-batch")
        rejected_job = RUN_CAMPAIGN.Job(self.platform, "like-single-use", 1, 3, 1, "spot", "language-batch")
        jobs = [accepted_job, RUN_CAMPAIGN.replace(exhausted_job, attempt=1), rejected_job]
        accepted = {accepted_job.logical_identity: self.receipt_for_job(accepted_job)}
        refill = RUN_CAMPAIGN.replace(exhausted_job, host_epoch=4, attempt=4, market="on-demand")
        capacity_retry = RUN_CAMPAIGN.replace(refill, host_epoch=5, attempt=5)
        # A failed refill attempt stays unresolved, even when its wrapper, or the wrapper of its
        # capacity retry, could not start. A controller stopped before it started the wrapper used
        # no attempt, so the grant remains.
        for refill_end, expected_pending, expected_unresolved in (
                ("host failure", [], [refill, rejected_job]),
                ("other pre-start failure", [], [exhausted_job, rejected_job]),
                ("capacity retry pre-start failure", [], [refill, rejected_job]),
                (RUN_CAMPAIGN.WRAPPER_NOT_CREATED, [(RUN_CAMPAIGN.replace(refill, host_epoch=5), 0)], [rejected_job])):
            with self.subTest(refill_end), tempfile.TemporaryDirectory() as temporary_directory:
                result_root = Path(temporary_directory)
                attempts = result_root / "job-attempts.tsv"
                self.write_cleanup_session(result_root, exhausted_job, failure_classification="retryable-spot",
                                           failure_detail="launch-capacity:InsufficientInstanceCapacity")
                RUN_CAMPAIGN.write_attempt(attempts, exhausted_job, "unresolved", "exhausted")
                self.write_cleanup_session(result_root, rejected_job, failure_classification="benchmark-failure")
                RUN_CAMPAIGN.write_attempt(attempts, rejected_job, "rejected", "benchmark failure")

                pending, unresolved, _ = RUN_CAMPAIGN.prepare_refill(result_root, jobs, attempts, accepted)

                self.assertEqual(pending, [(refill, 0)])
                self.assertEqual([job for job, _ in unresolved], [rejected_job])

                RUN_CAMPAIGN.write_attempt(attempts, refill, "starting")
                if refill_end == "host failure":
                    self.write_cleanup_session(result_root, refill, failure_classification="retryable-infrastructure")
                    RUN_CAMPAIGN.write_attempt(attempts, refill, "unresolved", "exhausted")
                elif refill_end == "capacity retry pre-start failure":
                    self.write_cleanup_session(result_root, refill, failure_classification="retryable-capacity")
                    RUN_CAMPAIGN.write_attempt(attempts, refill, "retryable-capacity", "capacity")
                    RUN_CAMPAIGN.write_attempt(attempts, capacity_retry, "starting")
                    RUN_CAMPAIGN.write_attempt(attempts, capacity_retry, "retryable-infrastructure", refill_end)
                else:
                    RUN_CAMPAIGN.write_attempt(attempts, refill, "retryable-infrastructure", refill_end)
                pending, unresolved, _ = RUN_CAMPAIGN.prepare_refill(result_root, jobs, attempts, accepted)

                self.assertEqual(pending, expected_pending)
                self.assertEqual([job for job, _ in unresolved], expected_unresolved)

    def test_unexpected_controller_error_stops_sibling_jobs(self):
        malformed_job = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        sibling_job = RUN_CAMPAIGN.Job(
            self.platform, "like-compile", 1, 11, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            malformed_receipt = root / "malformed.json"
            malformed_receipt.write_text('{"schema_version": 2}')
            malformed_process = FakeProcess(
                110,
                status=0,
                poll_action=lambda: self.write_cleanup_session(result_root, malformed_job))
            sibling_process = FakeProcess(
                111,
                wait_action=lambda: self.write_cleanup_session(result_root, sibling_job))

            with patch.object(
                    RUN_CAMPAIGN.subprocess,
                    "Popen",
                    side_effect=[malformed_process, sibling_process]), \
                    patch.object(RUN_CAMPAIGN, "validate_job_artifacts", return_value=malformed_receipt):
                with self.assertRaisesRegex(RuntimeError, "unexpected JSON receipt schema"):
                    RUN_CAMPAIGN.execute_jobs(
                        self.arguments(root, maximum_concurrent=2),
                        root,
                        result_root,
                        [malformed_job, sibling_job])

            self.assertEqual(sibling_process.signals, [RUN_CAMPAIGN.signal.SIGTERM])
            self.assertEqual(sibling_process.wait_timeouts, [15 * 60])

    def test_attempt_ledger_failure_stops_the_campaign_and_cleans_any_child(self):
        # The starting record fails before the wrapper starts, the started record after it.
        for failed_write, started in ((1, False), (2, True)):
            with self.subTest(failed_write=failed_write), tempfile.TemporaryDirectory() as temporary_directory:
                job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
                root = Path(temporary_directory)
                result_root = root / "results"
                result_root.mkdir()
                process = FakeProcess(
                    112,
                    wait_action=lambda: self.write_cleanup_session(result_root, job))
                real_write_attempt = RUN_CAMPAIGN.write_attempt
                write_count = 0

                def fail_one_attempt_write(*arguments, **keywords):
                    nonlocal write_count
                    write_count += 1
                    if write_count == failed_write:
                        raise OSError("simulated durable-ledger failure")
                    return real_write_attempt(*arguments, **keywords)

                # A one-job phase is all tail; keep this launch on Spot. A ledger failure mistaken for a failed
                # start would be retried at once, starting a second wrapper.
                wrappers = [process] if started else []
                with patch.object(RUN_CAMPAIGN.subprocess, "Popen", side_effect=wrappers) as popen, \
                        patch.object(RUN_CAMPAIGN, "ON_DEMAND_TAIL_PERCENT", 0), \
                        patch.object(RUN_CAMPAIGN, "CAPACITY_RETRY_SECONDS", 0), \
                        patch.object(RUN_CAMPAIGN.time, "sleep"), \
                        patch.object(
                            RUN_CAMPAIGN,
                            "write_attempt",
                            side_effect=fail_one_attempt_write):
                    with self.assertRaisesRegex(OSError, "simulated durable-ledger failure"):
                        RUN_CAMPAIGN.execute_jobs(
                            self.arguments(root), root, result_root, [job])

                self.assertEqual(started, popen.called)
                self.assertEqual([RUN_CAMPAIGN.signal.SIGTERM] if started else [], process.signals)
                self.assertEqual([15 * 60] if started else [], process.wait_timeouts)

    def test_wrapper_that_fails_to_start_is_retried_on_a_fresh_epoch(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            replacement = FakeProcess(
                113, status=1, poll_action=lambda: self.write_cleanup_session(
                    result_root, RUN_CAMPAIGN.replace(job, host_epoch=11, attempt=2),
                    failure_classification="benchmark-failure"))
            # A one-job phase is all tail; keep its retry on Spot.
            with patch.object(RUN_CAMPAIGN.subprocess, "Popen",
                              side_effect=[OSError("exec format error"), replacement]) as popen, \
                    patch.object(RUN_CAMPAIGN, "ON_DEMAND_TAIL_PERCENT", 0), \
                    patch.object(RUN_CAMPAIGN, "CAPACITY_RETRY_SECONDS", 0), \
                    patch.object(RUN_CAMPAIGN.time, "sleep"):
                with self.assertRaisesRegex(SystemExit, "campaign completed with unresolved jobs"):
                    RUN_CAMPAIGN.execute_jobs(self.arguments(root), root, result_root, [job])

            self.assertEqual(2, popen.call_count)
            attempts = RUN_CAMPAIGN.read_attempts(result_root / "job-attempts.tsv")
            self.assertIn(("10", "retryable-infrastructure", "controller could not start wrapper: exec format error"),
                          [(row["host_epoch"], row["outcome"], row["detail"]) for row in attempts])
            self.assertIn(("11", "started"), [(row["host_epoch"], row["outcome"]) for row in attempts])

    def test_tooling_digest_covers_each_tracked_files_path_and_content(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            tool = root / "tools/re2-benchmark/tool.sh"
            tool.parent.mkdir(parents=True)
            tool.write_text("tool\n")
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "runs from a Git checkout"):
                RUN_CAMPAIGN.tooling_digest(root)
            (root / ".git").touch()
            tracked = ["tools/re2-benchmark/tool.sh"]
            with patch.object(RUN_CAMPAIGN, "check_output", side_effect=lambda *_, **__: "\n".join(tracked)):
                digest = RUN_CAMPAIGN.tooling_digest(root)
                self.assertEqual(digest, RUN_CAMPAIGN.tooling_digest(root))
                tool.write_text("changed\n")
                changed = RUN_CAMPAIGN.tooling_digest(root)
                tool.rename(root / "tools/re2-benchmark/renamed.sh")
                tracked = ["tools/re2-benchmark/renamed.sh"]
                renamed = RUN_CAMPAIGN.tooling_digest(root)
            self.assertEqual(3, len({digest, changed, renamed}))

    def test_frozen_fingerprint_ignores_unrelated_worktree_state(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            (root / ".git").touch()
            tool = root / "tools/re2-benchmark/tool.sh"
            tool.parent.mkdir(parents=True)
            tool.write_text("tool\n")
            archive = root / "candidate.tar.gz"
            provenance = root / "provenance.tsv"
            jobs = root / "jobs.tsv"
            for path in (archive, provenance, jobs):
                path.write_text(path.name)
            arguments = SimpleNamespace(
                candidate_ref="refs/benchmarks/candidate",
                candidate_archive=archive,
                candidate_provenance=provenance)
            commands = []

            def git_result(command, **_):
                commands.append(command)
                if "ls-files" in command:
                    return "tools/re2-benchmark/tool.sh"
                if "rev-parse" in command:
                    return "a" * 40
                raise AssertionError(command)

            with patch.object(RUN_CAMPAIGN, "check_output", side_effect=git_result):
                fingerprint = RUN_CAMPAIGN.frozen_input_fingerprint(root, arguments, jobs)

            self.assertTrue(fingerprint)
            self.assertFalse(any("status" in command for command in commands))

    def test_conflicting_accepted_receipt_is_unsafe(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            ledger = root / "accepted-language-sessions.json"
            first_path = root / "first.json"
            second_path = root / "second.json"
            self.write_receipt(first_path, self.receipt_for_job(job))
            self.write_receipt(second_path, {
                **self.receipt_for_job(job),
                "host_epoch": "11",
            })

            RUN_CAMPAIGN.persist_accepted_receipt(ledger, first_path)
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "conflicting"):
                RUN_CAMPAIGN.persist_accepted_receipt(ledger, second_path)

    def test_unit_ledger_recovers_retained_attempts_and_confirms_on_new_instances(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory) / "results"
            campaign = UnitCampaign(result_root, "like-dfa-single-use")
            ledger = result_root / "accepted-units.tsv"
            receipt = campaign.receipts[0]
            arguments = SimpleNamespace(
                campaign_id=receipt["campaign_id"], candidate_archive_sha256=receipt["candidate_archive_sha256"],
                engine_tree=receipt["engine_tree"], heap_size=receipt["heap_size"])

            def recover():
                return RUN_CAMPAIGN.recover_accepted_units(SCRIPT.parents[3], arguments, result_root, ledger)

            with patch.object(RUN_CAMPAIGN, "check_output", return_value=receipt["candidate_commit"]):
                recover()
                # A restart revalidates the ledger against the retained attempts.
                self.assertEqual(len(recover()), 6)
                self.assertEqual(
                    sorted(campaign.receipts, key=RUN_CAMPAIGN.unit_logical_identity),
                    sorted(RUN_CAMPAIGN.read_accepted_units(ledger), key=RUN_CAMPAIGN.unit_logical_identity))
                unit = campaign.units[0]
                confirmations = Path(temporary_directory) / "confirmation-jobs.tsv"
                confirmations.write_text(
                    f"platform\tshard_id\tunit_id\nr8i\t{unit['shard_id']}\t{unit['unit_id']}\n")
                self.assertEqual(
                    [("r8i", unit["unit_id"], 4, frozenset({"i-r8i-1", "i-r8i-2", "i-r8i-3"}))],
                    RUN_CAMPAIGN.read_confirmation_units(
                        confirmations, [self.platform], {unit["unit_id"]: unit}, campaign.receipts))
                # A requeued replica's later completion is superseded: the first accepted attempt stays.
                first = {RUN_CAMPAIGN.unit_logical_identity(accepted): accepted for accepted in campaign.receipts}
                campaign.add("r8i", unit, 2, host=4)
                self.assertEqual(first, recover())
                # An accepted attempt whose retained evidence no longer holds stops the restart.
                (RUN_CAMPAIGN.unit_attempt_directory(result_root, receipt) / "status.txt").write_text(
                    "status=failed\n")
                with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError,
                                            "unit artifacts are not accepted .*did not complete"):
                    recover()

    def test_on_demand_capacity_accounts_for_unrelated_work_and_local_reservations(self):
        running_job = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 10, 1, "on-demand", "language-batch")
        instances = [
            {
                "instance_type": "m8gd.4xlarge",
                "lifecycle": None,
                "tags": [],
            },
            {
                "instance_type": "r8i.2xlarge",
                "lifecycle": None,
                "tags": [
                    {"Key": "BaselineCampaign", "Value": "baseline"},
                    {"Key": "HostEpoch", "Value": "10"},
                ],
            },
            {
                "instance_type": "r8i.2xlarge",
                "lifecycle": "spot",
                "tags": [],
            },
        ]
        descriptions = [
            {"instance_type": "r8i.2xlarge", "vcpus": 8},
            {"instance_type": "m8gd.4xlarge", "vcpus": 16},
        ]

        def aws_result(command, **_):
            if "get-service-quota" in command:
                return "500"
            if "describe-instances" in command:
                return json.dumps(instances)
            if "describe-instance-types" in command:
                return json.dumps(descriptions)
            raise AssertionError(command)

        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result):
            capacity = RUN_CAMPAIGN.current_on_demand_capacity(
                self.arguments(Path(".")), "baseline", [running_job])

        self.assertEqual(capacity, {
            "quota": 500,
            "used": 16,
            "reserved": 8,
            "safety_reserve": 32,
            "available": 444,
        })

    def test_spot_capacity_accounts_for_unrelated_work_and_local_reservations(self):
        running_job = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        instances = [
            {
                "instance_id": "i-external",
                "instance_type": "m8gd.4xlarge",
                "lifecycle": "spot",
                "tags": [],
            },
            {
                "instance_id": "i-local",
                "instance_type": "r8i.2xlarge",
                "lifecycle": "spot",
                "tags": [
                    {"Key": "BaselineCampaign", "Value": "baseline"},
                    {"Key": "HostEpoch", "Value": "10"},
                ],
            },
            {
                "instance_id": "i-on-demand",
                "instance_type": "r8i.2xlarge",
                "lifecycle": None,
                "tags": [],
            },
        ]
        requests = [
            {
                "instance_id": "i-local",
                "instance_type": "r8i.2xlarge",
                "tags": [],
            },
            {
                "instance_id": None,
                "instance_type": "r8g.large",
                "tags": [],
            },
        ]
        descriptions = [
            {"instance_type": "r8i.2xlarge", "vcpus": 8},
            {"instance_type": "r8g.large", "vcpus": 2},
            {"instance_type": "m8gd.4xlarge", "vcpus": 16},
        ]

        def aws_result(command, **_):
            if "get-service-quota" in command:
                return "352"
            if "describe-instances" in command:
                return json.dumps(instances)
            if "describe-spot-instance-requests" in command:
                return json.dumps(requests)
            if "describe-instance-types" in command:
                return json.dumps(descriptions)
            raise AssertionError(command)

        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result):
            capacity = RUN_CAMPAIGN.current_spot_capacity(
                self.arguments(Path(".")), "baseline", [running_job])

        self.assertEqual(capacity, {
            "quota": 352,
            "used": 18,
            "reserved": 8,
            "safety_reserve": 32,
            "available": 294,
        })

    def test_spot_quota_shortage_changes_unlaunched_job_to_on_demand(self):
        spot_job = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            process = FakeProcess(
                110,
                status=1,
                poll_action=lambda: self.write_cleanup_session(
                    result_root,
                    RUN_CAMPAIGN.replace(spot_job, market="on-demand"),
                    failure_classification="benchmark-failure"))

            # A one-job phase is all tail; without the tail rule only the Spot quota shortage moves it to
            # On-Demand.
            with patch.object(RUN_CAMPAIGN, "current_spot_capacity", return_value={
                        "quota": 352,
                        "used": 320,
                        "reserved": 0,
                        "safety_reserve": 32,
                        "available": 0,
                    }), \
                    patch.object(RUN_CAMPAIGN, "current_on_demand_capacity", return_value={
                        "quota": 532,
                        "used": 103,
                        "reserved": 0,
                        "safety_reserve": 32,
                        "available": 397,
                    }), \
                    patch.object(RUN_CAMPAIGN, "ON_DEMAND_TAIL_PERCENT", 0), \
                    patch.object(RUN_CAMPAIGN.subprocess, "Popen", return_value=process) as popen:
                with self.assertRaises(SystemExit):
                    RUN_CAMPAIGN.execute_jobs(
                        self.arguments(root, maximum_concurrent=64), root, result_root, [spot_job])

            self.assertEqual(
                popen.call_args.kwargs["env"]["INSTANCE_MARKET_TYPE"],
                "on-demand")

    def test_on_demand_vcpu_limit_has_unbounded_capacity_retry(self):
        job = RUN_CAMPAIGN.Job(
            self.platform,
            "like-dfa-single-use",
            1,
            100,
            RUN_CAMPAIGN.MAX_REPLACEMENTS_PER_JOB + 1,
            "on-demand", "language-batch")

        replacement = RUN_CAMPAIGN.replacement_job(job, 101, "retryable-capacity")

        self.assertEqual(replacement.market, "on-demand")
        self.assertEqual(replacement.host_epoch, 101)
        self.assertEqual(replacement.attempt, job.attempt + 1)

    def test_on_demand_capacity_wait_queues_instead_of_aborting(self):
        job = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 10, 1, "on-demand", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            process = FakeProcess(
                107,
                status=1,
                poll_action=lambda: self.write_cleanup_session(
                    result_root, job, failure_classification="benchmark-failure"))
            no_capacity = {
                "quota": 500,
                "used": 468,
                "reserved": 0,
                "safety_reserve": 32,
                "available": 0,
            }
            capacity = {**no_capacity, "used": 460, "available": 8}

            with patch.object(
                    RUN_CAMPAIGN,
                    "current_on_demand_capacity",
                    side_effect=[no_capacity, capacity]), \
                    patch.object(RUN_CAMPAIGN.subprocess, "Popen", return_value=process), \
                    patch.object(RUN_CAMPAIGN.time, "sleep"), \
                    patch.object(RUN_CAMPAIGN, "CAPACITY_RETRY_SECONDS", 0):
                with self.assertRaises(SystemExit):
                    RUN_CAMPAIGN.execute_jobs(
                        self.arguments(root), root, result_root, [job])

            attempts = (result_root / "job-attempts.tsv").read_text()
            self.assertIn("queued-capacity", attempts)
            self.assertIn("started", attempts)
            self.assertIn("rejected", attempts)

    def test_spot_unavailability_falls_back_to_on_demand_on_fresh_epoch(self):
        spot_job = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        on_demand_job = RUN_CAMPAIGN.Job(
            self.platform, "like-dfa-single-use", 1, 11, 2, "on-demand", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            spot_process = FakeProcess(
                108,
                status=1,
                poll_action=lambda: self.write_cleanup_session(
                    result_root,
                    spot_job,
                    failure_classification="retryable-spot",
                    failure_detail="launch-capacity:InsufficientInstanceCapacity",
                    launched=False))
            on_demand_process = FakeProcess(
                109,
                status=1,
                poll_action=lambda: self.write_cleanup_session(
                    result_root, on_demand_job, failure_classification="benchmark-failure"))

            # A one-job phase is all tail; without the tail rule only Spot unavailability moves it to On-Demand.
            with patch.object(
                    RUN_CAMPAIGN.subprocess,
                    "Popen",
                    side_effect=[spot_process, on_demand_process]) as popen, \
                    patch.object(RUN_CAMPAIGN, "current_on_demand_capacity", return_value={
                        "quota": 500,
                        "used": 0,
                        "reserved": 0,
                        "safety_reserve": 32,
                        "available": 468,
                    }), \
                    patch.object(RUN_CAMPAIGN, "ON_DEMAND_TAIL_PERCENT", 0), \
                    patch.dict(os.environ, {"AWS_REGION": "us-east-2", "CAMPAIGN_PLACEMENT_SCORE": "9"}):
                with self.assertRaises(SystemExit):
                    RUN_CAMPAIGN.execute_jobs(
                        self.arguments(root), root, result_root, [spot_job])

            markets = [call.kwargs["env"]["INSTANCE_MARKET_TYPE"] for call in popen.call_args_list]
            epochs = [call.kwargs["env"]["CAMPAIGN_HOST_EPOCH"] for call in popen.call_args_list]
            self.assertEqual(markets, ["spot", "on-demand"])
            self.assertEqual(epochs, ["10", "11"])
            self.assertIn("starting\tregion=us-east-2;placement_score=9",
                          (result_root / "job-attempts.tsv").read_text())
            with (result_root / "campaign-events.tsv").open(newline="") as input_file:
                failed_launch = next(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual(
                {field: failed_launch[field] for field in (
                    "event_type", "instance_id", "reason", "region", "availability_zone",
                    "placement_score", "failure_classification")},
                {
                    "event_type": "interruption",
                    "instance_id": "",
                    "reason": "launch-capacity:InsufficientInstanceCapacity",
                    "region": "us-east-2",
                    "availability_zone": "us-east-2b",
                    "placement_score": "9",
                    "failure_classification": "retryable-spot",
                })

    def test_spot_unavailability_tries_each_allowed_region_before_on_demand(self):
        # Neither region has Spot capacity, and the controller restarts after the second fails.
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result_root = root / "results"
            result_root.mkdir()
            arguments = self.arguments(root)
            arguments.regions = ["us-east-2", "us-west-2"]
            jobs = [RUN_CAMPAIGN.replace(job, host_epoch=epoch, attempt=epoch - 9) for epoch in (10, 11, 12)]
            launch_failure = {"failure_classification": "retryable-spot",
                              "failure_detail": "launch-capacity:InsufficientInstanceCapacity", "launched": False}

            def interrupt_controller():
                second.poll_action = None
                self.write_cleanup_session(result_root, jobs[1], **launch_failure, region="us-west-2")
                raise KeyboardInterrupt

            first = FakeProcess(110, status=1, poll_action=lambda: self.write_cleanup_session(
                result_root, jobs[0], **launch_failure))
            second = FakeProcess(111, status=1, poll_action=interrupt_controller)
            on_demand = FakeProcess(112, status=1, poll_action=lambda: self.write_cleanup_session(
                result_root, jobs[2], failure_classification="benchmark-failure"))
            launches = []
            # A one-job phase is all tail; without the tail rule only Spot unavailability in every region moves
            # it to On-Demand.
            with patch.object(RUN_CAMPAIGN, "check_output",
                              side_effect=subprocess.CalledProcessError(254, "get-spot-placement-scores")), \
                    patch.object(RUN_CAMPAIGN, "current_on_demand_capacity", return_value={"available": 8}), \
                    patch.object(RUN_CAMPAIGN, "ON_DEMAND_TAIL_PERCENT", 0), \
                    patch.object(RUN_CAMPAIGN.time, "sleep"):
                for processes, failure in (([first, second], KeyboardInterrupt), ([on_demand], SystemExit)):
                    with patch.object(RUN_CAMPAIGN.subprocess, "Popen", side_effect=processes) as popen, \
                            self.assertRaises(failure):
                        RUN_CAMPAIGN.execute_jobs(arguments, root, result_root, [job])
                    launches += [(call.kwargs["env"]["INSTANCE_MARKET_TYPE"], call.kwargs["env"]["AWS_REGION"])
                                 for call in popen.call_args_list]
            self.assertEqual(launches, [("spot", "us-east-2"), ("spot", "us-west-2"), ("on-demand", "us-east-2")])

    def test_phase_tail_launches_on_demand_unless_spot_only(self):
        # Ten shard jobs leave a one-job tail.
        jobs = [RUN_CAMPAIGN.Job(self.platform, f"language-bulk-{epoch:04d}", 1, epoch, 1, "spot", "language-batch")
                for epoch in range(1, 11)]
        for spot_only, expected in ((False, ["spot"] * 9 + ["on-demand"]), (True, ["spot"] * 10)):
            with self.subTest(spot_only=spot_only), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                result_root = root / "results"
                result_root.mkdir()
                arguments = self.arguments(root)
                arguments.spot_only, arguments.spot_vcpu_reserve = spot_only, 0
                processes = [FakeProcess(100 + job.host_epoch, status=1, poll_action=lambda job=job:
                                         self.write_cleanup_session(result_root, job,
                                                                    failure_classification="benchmark-failure"))
                             for job in jobs]
                with patch.object(RUN_CAMPAIGN, "current_spot_capacity", return_value={"available": 8}), \
                        patch.object(RUN_CAMPAIGN, "current_on_demand_capacity", return_value={"available": 8}), \
                        patch.object(RUN_CAMPAIGN.time, "sleep"), \
                        patch.object(RUN_CAMPAIGN.subprocess, "Popen", side_effect=processes) as popen:
                    with self.assertRaises(SystemExit):
                        RUN_CAMPAIGN.execute_jobs(arguments, root, result_root, jobs)
                self.assertEqual([call.kwargs["env"]["INSTANCE_MARKET_TYPE"] for call in popen.call_args_list],
                                 expected)

    def test_shared_fleet_launches_on_demand_where_its_allowance_has_headroom(self):
        fixture = test_fleet_budget.TestFleetBudget()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        policy = fixture.policy
        policy["regions"] = {"eu-west-1": {"max_vcpus": 64, "spot_vcpu_reserve": 0, "on_demand_max_vcpus": 64}}
        for rates in policy["hourly_rates"].values():
            rates["on_demand_total"] = 2 * rates["total"]
        fixture.path.write_text(json.dumps(policy))
        # A one-job phase is all tail, so its only launch is On-Demand.
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        queried = []

        def aws_result(command, **_):
            region = command[command.index("--region") + 1]
            queried.append(region)
            if "get-service-quota" in command:
                self.assertIn(RUN_CAMPAIGN.STANDARD_ON_DEMAND_QUOTA_CODE, command)
                return "64"
            if "describe-instances" in command:
                self.assertNotIn("Name=instance-lifecycle,Values=spot", command)
                return json.dumps([{"id": "i-spot", "type": "r8i.2xlarge", "lifecycle": "spot"},
                                   {"id": "i-other", "type": "r8i.2xlarge", "lifecycle": None}])
            if "describe-instance-types" in command:
                return json.dumps([{"type": "r8i.2xlarge", "vcpus": 8}])
            raise AssertionError(command)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result_root = root / "results"
            result_root.mkdir()
            arguments = self.arguments(root)
            arguments.shared_budget = RUN_CAMPAIGN.FleetBudget(fixture.path)
            arguments.spot_vcpu_reserve, arguments.regions = 0, ["us-west-2", "eu-west-1"]
            process = FakeProcess(110, status=1, poll_action=lambda: self.write_cleanup_session(
                result_root, job, failure_classification="benchmark-failure", region="eu-west-1"))
            with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result), \
                    patch.object(RUN_CAMPAIGN.time, "sleep"), \
                    patch.object(RUN_CAMPAIGN.subprocess, "Popen", return_value=process) as popen:
                with self.assertRaises(SystemExit):
                    RUN_CAMPAIGN.execute_jobs(arguments, root, result_root, [job])
        # Oregon has no allowance; Ireland's quota, less its reserve and other work, fits the host.
        self.assertEqual(set(queried), {"eu-west-1"})
        environment = popen.call_args.kwargs["env"]
        self.assertEqual((environment["AWS_REGION"], environment["INSTANCE_MARKET_TYPE"]), ("eu-west-1", "on-demand"))
        completed = json.loads(arguments.shared_budget.state_path.read_text())["completed"]
        self.assertEqual([(item["region"], item["market"], item["hourly_rate"]) for item in completed.values()],
                         [("eu-west-1", "on-demand", 0.60)])
        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result):
            snapshot = RUN_CAMPAIGN.shared_fleet_snapshot(arguments, "eu-west-1", "on-demand")
        self.assertEqual(snapshot["instances"],
                         [{"id": "i-other", "type": "r8i.2xlarge", "vcpus": 8, "campaign_id": None, "host_epoch": None}])

    def test_resumed_campaign_events_keep_their_columns(self):
        job = RUN_CAMPAIGN.Job(self.platform, "like-dfa-single-use", 1, 10, 1, "spot", "language-batch")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            session = self.write_cleanup_session(result_root, job)
            events = result_root / "campaign-events.tsv"
            fields = ("event_type", "campaign_id", "platform", "shard_id", "replica_id", "instance_id",
                      "host_epoch", "reason", "artifact_uri", "artifact_sha256", "details")
            events.write_text("\t".join(fields) + "\n")

            RUN_CAMPAIGN.write_event(events, "interruption", "baseline", job, session, "spot-interruption")

            with events.open(newline="") as input_file:
                rows = list(csv.DictReader(input_file, delimiter="\t"))
            self.assertEqual(tuple(rows[0]), fields)
            self.assertEqual(rows[0]["instance_id"], "i-0123456789abcdef0")


RELEASE_JAR = b"released regulator\n"
RELEASE = {"schema_version": 1, "group_id": "io.airlift", "artifact_id": "regulator", "version": "1.0",
           "source_commit": "c" * 40, "engine_tree": "b" * 40, "jar_sha256": hashlib.sha256(RELEASE_JAR).hexdigest()}


def measure_release(directory):
    """Record that each route of a unit attempt measured RELEASE's JAR, as run-shard.sh attests it."""
    jar = f"/opt/re2-work/release/regulator-{RELEASE['version']}.jar"
    attestation = json.dumps({
        "manifest": RELEASE, "jar_path": jar,
        "code_sources": {f"io.airlift.regulator.{name}": jar for name in RUN_CAMPAIGN.released_artifact.PUBLIC_CLASSES},
        "production_classes": [f"{RUN_CAMPAIGN.released_artifact.PACKAGE}{name}.class"
                               for name in RUN_CAMPAIGN.released_artifact.PUBLIC_CLASSES],
        "classpath": [{"path": jar, "files": {Path(jar).name: RELEASE["jar_sha256"]}}],
    }, sort_keys=True) + "\n"
    for metadata in directory.glob("routes/*/run-metadata.txt"):
        (metadata.parent / "release").mkdir()
        (metadata.parent / "release" / Path(jar).name).write_bytes(RELEASE_JAR)
        (metadata.parent / "release-artifact.json").write_text(attestation)
        with metadata.open("a") as output:
            output.write(f"release_version={RELEASE['version']}\n"
                         f"release_artifact_sha256={hashlib.sha256(attestation.encode()).hexdigest()}\n")


class FakeUnitHost:
    """A unit worker host behind a stubbed wrapper and S3: each lease runs as fixture evidence.

    A script names what a lease does instead of completing: "interrupt" uploads an interruption
    bundle and fails as a Spot interruption, as the wrapper reports a worker's status 43, and
    "notice" does so before it fetches the lease. A host scripted to
    "silent-interrupt" does so with none of its heartbeat uploads landing. "stall" stops the
    heartbeat; once the controller stops its wrapper, the lease's completion lands late, after
    another host completed the same replica, or never, once no other host runs, and with
    "stall-interrupt" the stopped host reports an interruption of the lease instead. With
    "stall-semantic" the late completion is a semantic failure, and with "stall-semantic-early"
    it lands as soon as another host leased the replica, before that host completes it; with
    "stall-lose-calibration" its calibrations never land. "rebalance"
    stops the host before it fetches the lease, as a rebalance recommendation does. A host the
    fleet finds no Spot capacity for, or whose boot the fleet fails, exits before its first
    heartbeat, as the wrapper's launch does. Every host that launched uploads its host archive
    as it exits, as the user data's exit trap does, even one that failed during bootstrap.
    In a calibrated fleet each lease uploads its host calibrations, except when it is scripted
    to "lose-calibration". A lease scripted to "chain" takes its before calibration from the
    lease before it, as the worker does within the chain window; "false-chain" claims that link
    for a before calibration the previous lease never took. A lease completes failed with
    "semantic-failure" (status 44), "protocol-rejection" (status 42), "fail" (status 1), or
    "capacity-rejected", whose capacity gate failed it with status 1. "corrupt-archive" uploads a
    result that differs from its marker's checksum, and "foreign-receipt" a receipt that names another
    attempt. "foreign-marker" completes with a marker naming another campaign, and "partial-marker" with
    one lacking its result checksum. A host whose environment names a release measures RELEASE's JAR,
    except for a lease scripted to "unreleased", which measures the candidate's own classes. The
    fleet's unit scripts give every lease of a unit, on any host, a behavior.
    """

    # Each scripted failure's exit status, as run-unit.sh reports it.
    STATUSES = {
        "semantic-failure": RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS,
        "stall-semantic": RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS,
        "stall-semantic-early": RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS,
        "protocol-rejection": RUN_CAMPAIGN.PROTOCOL_REJECTION_STATUS, "fail": 1, "capacity-rejected": 1,
    }

    def __init__(self, fleet, number, identity, environment):
        self.fleet = fleet
        self.number = number
        self.pid = 4_000_000 + number
        self.identity = identity
        self.platform = environment["CAMPAIGN_PLATFORM"]
        self.instance_type = environment.get("INTEL_INSTANCE_TYPE") or environment["ARM_INSTANCE_TYPE"]
        self.market = environment["INSTANCE_MARKET_TYPE"]
        self.region = environment["AWS_REGION"]
        self.launched_at = fleet.clock
        self.epoch = environment["CAMPAIGN_HOST_EPOCH"]
        self.archive_key = (f"{self.platform}/{environment['CAMPAIGN_SHARD_ID']}/"
                            f"replica-{environment['CAMPAIGN_REPLICA_ID']}/epoch-{self.epoch}/"
                            f"{environment['CAMPAIGN_ARCHITECTURES']}.tar.gz")
        self.architecture = environment["CAMPAIGN_ARCHITECTURES"]
        self.instance_type = environment["INTEL_INSTANCE_TYPE" if self.architecture == "intel" else "ARM_INSTANCE_TYPE"]
        self.session_directory = Path(environment["RESULT_ROOT"]) / f"{self.platform}-session-{number}"
        self.session = self.session_directory.name
        self.instance_id = f"i-{self.platform}-{number}"
        self.release_version = environment["REGULATOR_RELEASE_VERSION"]
        self.script = fleet.scripts.get(number, {})
        self.full = self.market == "spot" and fleet.full_spot.get(self.platform, 0) > 0
        if self.full:
            fleet.full_spot[self.platform] -= 1
        self.objects = {}
        self.leases = {}
        self.sequence = 1
        # The last completed lease's after calibration, which the next lease may chain from.
        self.after = None
        self.booted = False
        self.stalled = False
        self.stopping = False
        self.status = None
        self.signals = []

    def tick(self):
        if self.stopping and self.status is None:
            lease = self.leases[self.sequence]
            behavior = self.script.get(self.sequence)
            if behavior == "stall-interrupt":
                return self.interrupt(lease["unit_id"])
            if any(host is not self and host.platform == self.platform and any(
                    other.get("unit_id") == lease["unit_id"] and other.get("replica_id") == lease["replica_id"]
                    for sequence, other in host.leases.items()
                    if sequence < host.sequence or behavior == "stall-semantic-early")
                   for host in self.fleet.hosts):
                self.run(lease)
                self.exit(143, "controller-abort", "terminated")
            elif all(host is self or host.status is not None for host in self.fleet.hosts):
                self.exit(143, "controller-abort", "terminated")
            return
        if self.status is not None or self.stalled:
            return
        if not self.booted:
            self.session_directory.mkdir(parents=True)
            self.booted = True
            # A launch that finds no capacity never publishes its session.
            if self.full:
                return self.exit(1, "retryable-spot", "launch-capacity:InsufficientInstanceCapacity")
            other = "arm" if self.architecture == "intel" else "intel"
            (self.session_directory / "session.txt").write_text(
                f"session={self.session}\nbucket=bucket\ninput_prefix={self.session}/input\n"
                f"result_prefix={self.session}/results\naws_region={self.region}\nplacement_score=9\n"
                f"{self.architecture}_instance_id={self.instance_id}\n{other}_instance_id=none\n")
            if self.fleet.failed_boot(self):
                return self.exit(1, "retryable-infrastructure", "bootstrap failed")
        if self.script.get(self.sequence) == "notice":
            return self.interrupt(None)
        lease = self.leases.get(self.sequence)
        if lease is None:
            return self.heartbeat(None)
        if lease.get("stop"):
            return self.exit(0)
        behavior = self.script.get(self.sequence)
        if behavior == "rebalance":
            return self.rebalance()
        self.heartbeat(lease["unit_id"])
        if behavior in ("interrupt", "silent-interrupt"):
            return self.interrupt(lease["unit_id"])
        if behavior in ("stall", "stall-interrupt", "stall-semantic", "stall-semantic-early",
                         "stall-lose-calibration"):
            key = f"heartbeat/{self.sequence}.json"
            self.objects[key] = (self.objects[key][0],
                                 RUN_CAMPAIGN.time.time() - RUN_CAMPAIGN.UNIT_HEARTBEAT_LOST_SECONDS - 60)
            self.stalled = True
            return
        self.run(lease)

    def rebalance(self):
        evidence = self.session_directory / self.architecture / "re2-results"
        evidence.mkdir(parents=True)
        (evidence / "worker-session.txt").write_text(
            f"host_session={self.session}\nstop_reason=rebalance\nlast_sequence={self.sequence}\n"
            "stopped_at=2026-10-25T04:34:28Z\n")
        (evidence / "environment-manifest.txt").write_text(
            f"availability_zone={self.region}b\ninstance_pending_time=2026-10-25T04:33:30Z\n")
        with (self.session_directory / "session.txt").open("a") as session:
            session.write(f"{self.architecture}_availability_zone={self.region}b\n")
        self.exit(0)

    def interrupt(self, unit_id):
        notice = json.dumps({"sequence": self.sequence, "unit_id": unit_id}).encode()
        bundle = io.BytesIO()
        with tarfile.open(fileobj=bundle, mode="w:gz") as archive:
            member = tarfile.TarInfo("interruption.json")
            member.size = len(notice)
            archive.addfile(member, io.BytesIO(notice))
        self.put(f"events/{self.sequence}-interrupted.tar.gz", bundle.getvalue())
        self.exit(1, "retryable-spot", "spot-interruption-notice")

    def heartbeat(self, unit_id):
        if "silent-interrupt" in self.script.values():
            return
        self.put(f"heartbeat/{self.sequence}.json", json.dumps({"sequence": self.sequence, "unit_id": unit_id}).encode())

    def put(self, key, body):
        self.objects[key] = (body, RUN_CAMPAIGN.time.time())

    def run(self, lease):
        sequence = self.sequence
        behavior = self.script.get(sequence, self.fleet.unit_scripts.get(lease["unit_id"]))
        unit = self.fleet.units[lease["unit_id"]]
        host = self.fleet.scratch / self.session
        directory = host / f"lease-{sequence}"
        directory.mkdir(parents=True)
        chained = behavior in ("chain", "false-chain")
        fixture = UnitFixture(directory, unit["shard_id"], unit["unit_index"], str(lease["replica_id"]), {
            "campaign_id": lease["campaign_id"], "platform": self.platform, "instance_id": self.instance_id,
            "host_epoch": self.epoch, "architecture": self.architecture, "instance_type": self.instance_type,
            "availability_zone": f"{self.region}b",
        }, chained=chained)
        calibrations = []
        if self.fleet.calibrated:
            calibrations = [store_host_calibration(host, score) for score in (100, 101)]
            if chained:
                # The previous lease closed at 101; a false link names a before of 100.5 instead.
                before = self.after if behavior == "chain" else store_host_calibration(host, 100.5)
                calibrations = [before, store_host_calibration(host, 102)]
                fixture.chain(before, previous_attempt=f"{self.session}/{sequence - 1}")
            fixture.calibrate_on_host(host, *calibrations)
            self.after = calibrations[1]
            # A lost before calibration never lands; the after still does.
            uploaded = calibrations[1:] if behavior in ("lose-calibration", "stall-lose-calibration") else calibrations
            for calibration in uploaded:
                self.put(f"calibrations/{calibration.name}", calibration.read_bytes())
        if self.release_version and behavior != "unreleased":
            measure_release(directory)
        fixture.accept(f"{self.session}/{sequence + 100 if behavior == 'foreign-receipt' else sequence}")
        status = self.STATUSES.get(behavior, 0)
        if behavior == "capacity-rejected":
            capacity = directory / "unit-capacity.txt"
            capacity.write_text(capacity.read_text().replace("capacity_status=accepted", "capacity_status=rejected"))
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w:gz") as result:
            result.add(directory, arcname=".")
        self.put(f"units/{sequence}/result.tar.gz",
                 archive.getvalue()[:-1] if behavior == "corrupt-archive" else archive.getvalue())
        marker = {
            "schema_version": 1, "host_session": self.session, "sequence": sequence,
            "campaign_id": "other-campaign" if behavior == "foreign-marker" else lease["campaign_id"],
            "phase": lease["phase"], "platform": self.platform,
            "unit_id": lease["unit_id"], "replica_id": lease["replica_id"],
            "unit_attempt_id": f"{self.session}/{sequence}", "exit_status": status,
            "unit_status": "failed" if status else "complete",
            "result_sha256": hashlib.sha256(archive.getvalue()).hexdigest(),
            "calibration_before": calibrations[0].stem if calibrations else None,
            "calibration_after": calibrations[1].stem if calibrations else None,
            "calibration_chained": chained}
        if behavior == "partial-marker":
            del marker["result_sha256"]
        self.put(f"units/{sequence}/completion.json", json.dumps(marker).encode())
        self.fleet.log.append(("complete", self.session, sequence))
        self.sequence += 1
        self.heartbeat(None)

    def exit(self, status, classification="none", detail="-"):
        """Clean up as the wrapper does: recover the uploads, then record verified cleanup."""
        if self.booted and not self.full:
            self.put(self.archive_key, b"host archive")
        objects = self.session_directory / "recovered-uploads" / "objects"
        for key, (body, _) in self.objects.items():
            (objects / key).parent.mkdir(parents=True, exist_ok=True)
            (objects / key).write_bytes(body)
        (self.session_directory / "cleanup-manifest.txt").write_text(
            "instances_terminated=verified\niam_removed=verified\nbucket_removed=verified\n"
            "network_resources=default-vpc-reused\ncleanup_status=verified\n"
            f"run_status={'complete' if status == 0 else 'failed'}\nfailure_classification={classification}\n"
            f"failure_detail={detail}\naws_region={self.region}\n")
        if status == 0:
            (self.session_directory / "campaign-success").touch()
        self.status = status

    def poll(self):
        return self.status

    def send_signal(self, sent_signal):
        self.signals.append(sent_signal)
        if self.status is None:
            if self.stalled:
                self.stopping = True
            else:
                self.exit(143, "controller-abort", "terminated")

    def wait(self, timeout):
        return self.status

    def kill(self):
        self.status = -9


class FakeUnitFleet:
    """Unit worker hosts the stubbed wrapper launches, with their S3 leases and uploads."""

    def __init__(self, scratch, scripts=None, abort_after=None, full_spot=None, calibrated=False,
                 failed_boot=lambda host: False, unit_scripts=None):
        self.scratch = scratch
        self.calibrated = calibrated
        self.failed_boot = failed_boot
        self.scripts = scripts or {}
        self.unit_scripts = unit_scripts or {}
        self.abort_after = abort_after
        # Each platform's first Spot launches that find no capacity.
        self.full_spot = dict(full_spot or {})
        # The shared fleet's reservations a restart recovers, or None to leave recovery stubbed.
        self.reservations = None
        # Seconds the controller has slept, added to its monotonic clock.
        self.clock = 0
        self.units = RUN_CAMPAIGN.work_units.load(SCRIPT.parent)
        self.hosts = []
        self.log = []
        self.sleeps = 0
        # The digest of the tooling the controller runs.
        self.tooling = "c" * 64

    def host(self, worker):
        return next(host for host in self.hosts if host.session == worker.metadata["session"])

    def popen(self, command, **kwargs):
        host = FakeUnitHost(self, len(self.hosts) + 1, command[1], kwargs["env"])
        self.hosts.append(host)
        return host

    def list_results(self, arguments, worker):
        return {key: modified for key, (_, modified) in self.host(worker).objects.items()}

    def get_result(self, arguments, worker, key, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(self.host(worker).objects[key][0])

    def put_lease(self, arguments, worker, sequence, document):
        host = self.host(worker)
        if sequence in host.leases:
            if host.leases[sequence] != document:
                raise AssertionError(f"lease {sequence} of {host.session} rewritten")
            return
        host.leases[sequence] = document
        self.log.append(("lease", host.session, sequence, document.get("unit_id")))

    def check_output(self, command, **kwargs):
        if command[:2] == ["git", "rev-parse"]:
            return "a" * 40
        if "get-spot-placement-scores" in command:
            raise subprocess.CalledProcessError(254, command)
        if command[:2] == ["ps", "-p"]:
            host = next(host for host in self.hosts if host.pid == int(command[2]))
            if host.status is not None:
                raise subprocess.CalledProcessError(1, command)
            return f"bash run-campaign.sh {host.identity}"
        raise AssertionError(command)

    def kill(self, process_id, sent_signal):
        next(host for host in self.hosts if host.pid == process_id).send_signal(sent_signal)

    def sleep(self, seconds):
        self.clock += seconds
        if seconds != RUN_CAMPAIGN.UNIT_POLL_SECONDS:
            return
        self.sleeps += 1
        if self.sleeps > 2000:
            raise AssertionError("the controller never finished the phase")
        for host in list(self.hosts):
            host.tick()
        if self.sleeps == self.abort_after:
            raise SystemExit("controller stopped")

    def stubs(self):
        stack = contextlib.ExitStack()
        for name, value in (
                ("list_unit_worker_results", self.list_results), ("get_unit_worker_result", self.get_result),
                ("put_unit_lease", self.put_lease), ("check_output", self.check_output)):
            stack.enter_context(patch.object(RUN_CAMPAIGN, name, side_effect=value))
        stack.enter_context(patch.object(RUN_CAMPAIGN, "unit_worker_instance_state", return_value="running"))
        stack.enter_context(patch.object(RUN_CAMPAIGN, "frozen_input_fingerprint", return_value=None))
        stack.enter_context(patch.object(RUN_CAMPAIGN, "tooling_digest", side_effect=lambda root: self.tooling))
        if self.reservations is None:
            stack.enter_context(patch.object(RUN_CAMPAIGN, "recover_budget_reservations"))
        stack.enter_context(patch.object(RUN_CAMPAIGN.subprocess, "Popen", side_effect=self.popen))
        stack.enter_context(patch.object(RUN_CAMPAIGN.time, "sleep", side_effect=self.sleep))
        monotonic = RUN_CAMPAIGN.time.monotonic
        stack.enter_context(patch.object(RUN_CAMPAIGN.time, "monotonic", side_effect=lambda: monotonic() + self.clock))
        stack.enter_context(patch.object(RUN_CAMPAIGN.os, "kill", side_effect=self.kill))
        return stack


class TestUnitWorkers(unittest.TestCase):
    PLATFORMS = RUN_CAMPAIGN.read_platforms(SCRIPT.parent)

    def run_phase(self, root, fleet, phase, shards, max_concurrent, per_platform, regions=None, on_demand=True,
                  deadline=None, confirmations=(), primary_results=None, release_version=""):
        """Drain the phase's work items through the fleet; return the result directory.

        The unit attempt ledger is left in self.ledger."""
        self.budget = budget = Mock(policy={"hourly_rates": {
            instance_type: {"spot": 0.2} for platform in self.PLATFORMS
            for instance_type in (platform.instance_type, platform.concurrency_instance_type)}},
            policy_path="fleet.json")
        budget.reserve.side_effect = lambda campaign, job, *_: (
            on_demand or job.market == "spot", "fleet quota or pending-launch limit")
        budget.reservations.return_value = fleet.reservations or []
        arguments = SimpleNamespace(
            phase=phase, campaign_id="campaign", max_concurrent=max_concurrent,
            max_concurrent_per_platform=per_platform, candidate_archive=root / "candidate.tar.gz",
            candidate_archive_sha256="not-recorded", engine_tree="b" * 40, heap_size="8g",
            shared_budget=budget, selected_shards=shards, regions=regions, topology_deadline_seconds=deadline,
            primary_results=primary_results, release_version=release_version)
        self.arguments = arguments
        items = RUN_CAMPAIGN.build_work_items(phase, self.PLATFORMS, fleet.units, shards, confirmations)
        result_root = root / "results"
        result_root.mkdir(exist_ok=True)
        RUN_CAMPAIGN.write_work_items(result_root / "work-items.tsv", items)
        with fleet.stubs():
            try:
                RUN_CAMPAIGN.execute_units(arguments, SCRIPT.parents[3], result_root, items, self.PLATFORMS)
            finally:
                self.ledger = []
                if (result_root / "unit-attempts.tsv").exists():
                    with (result_root / "unit-attempts.tsv").open(newline="") as ledger:
                        self.ledger = list(csv.DictReader(ledger, delimiter="\t"))
        accepted = RUN_CAMPAIGN.read_accepted_units(result_root / "accepted-units.tsv")
        self.assertEqual(sorted(item.identity for item in items),
                         sorted(RUN_CAMPAIGN.unit_logical_identity(receipt) for receipt in accepted))
        return result_root

    def leases(self, outcome="leased"):
        return [row for row in self.ledger if row["outcome"] == outcome]

    def lease_count(self, lease, platform="r8i"):
        """How many leases the platform's hosts were given for the replica a lease names."""
        return sum((row["platform"], row["unit_id"], row["replica"]) ==
                   (platform, lease["unit_id"], str(lease["replica_id"])) for row in self.leases())

    @contextlib.contextmanager
    def dispatchers(self):
        """Collect each run's dispatcher, with the workers it adopted, once it has recovered."""
        recover = RUN_CAMPAIGN.UnitDispatcher.recover
        dispatchers = []

        def recover_and_record(dispatcher):
            recover(dispatcher)
            dispatchers.append((dispatcher, {epoch: worker.job for epoch, worker in dispatcher.workers.items()}))

        with patch.object(RUN_CAMPAIGN.UnitDispatcher, "recover", autospec=True, side_effect=recover_and_record):
            yield dispatchers

    def unresolved(self, result_root):
        with (result_root / "unresolved-units.tsv").open(newline="") as unresolved:
            return {(row["platform"], row["unit_id"], row["replica"]): row["detail"]
                    for row in csv.DictReader(unresolved, delimiter="\t")}

    def test_lease_writes_once_and_listing_strips_the_workers_prefix(self):
        worker = SimpleNamespace(host_session="r8i-session-1", metadata={
            "aws_region": "eu-west-1", "bucket": "bucket", "input_prefix": "campaign/input/r8i-session-1",
            "result_prefix": "campaign/results/r8i-session-1"})
        body = json.dumps({"sequence": 1, "unit_id": "unit"}, sort_keys=True) + "\n"
        listing = json.dumps({"Contents": [
            {"Key": "campaign/results/r8i-session-1/heartbeat/1.json", "LastModified": "2026-10-26T16:00:00+00:00"},
            {"Key": "campaign/results/r8i-session-10/heartbeat/1.json", "LastModified": "2026-10-26T16:00:01+00:00"}]})
        # A lease written before a restart or a lost response conflicts, and stands only if it is this one.
        for case, existing, operations, error in (
                ("new key", None, ["put-object"], None),
                ("same lease", body, ["put-object", "get-object"], None),
                ("another lease", '{"sequence": 1, "unit_id": "other"}\n', ["put-object", "get-object"],
                 "lease 1 differs from its ledger"),
                ("listing", None, ["list-objects-v2"], None)):
            with self.subTest(case=case):
                calls = []

                def s3(command, **_):
                    operation = command[command.index("s3api") + 1]
                    calls.append(operation)
                    self.assertEqual(["eu-west-1", "bucket"], [command[command.index("--region") + 1],
                                                              command[command.index("--bucket") + 1]])
                    if operation == "list-objects-v2":
                        self.assertEqual("campaign/results/r8i-session-1/", command[command.index("--prefix") + 1])
                        return listing
                    self.assertEqual("campaign/input/r8i-session-1/leases/1.json",
                                     command[command.index("--key") + 1])
                    if operation == "put-object":
                        self.assertEqual("*", command[command.index("--if-none-match") + 1])
                        self.assertEqual(body, Path(command[command.index("--body") + 1]).read_text())
                        if existing is not None:
                            raise subprocess.CalledProcessError(254, command)
                    else:
                        Path(command[-1]).write_text(existing)
                    return ""

                with patch.object(unit_dispatch, "check_output", side_effect=s3):
                    if case == "listing":
                        self.assertEqual({"heartbeat/1.json": 1793030400.0},
                                         RUN_CAMPAIGN.list_unit_worker_results(SimpleNamespace(), worker))
                    elif error:
                        with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, error):
                            RUN_CAMPAIGN.put_unit_lease(SimpleNamespace(), worker, 1, json.loads(body))
                    else:
                        RUN_CAMPAIGN.put_unit_lease(SimpleNamespace(), worker, 1, json.loads(body))
                self.assertEqual(operations, calls)

    def test_phase_drains_through_workers_with_one_lease_ahead(self):
        with tempfile.TemporaryDirectory() as temporary:
            fleet = FakeUnitFleet(Path(temporary) / "hosts")
            gate = RUN_CAMPAIGN.unit_session.validate_unit_campaign
            with patch.object(RUN_CAMPAIGN.unit_session, "validate_unit_campaign", wraps=gate) as validated:
                result_root = self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            validated.assert_called_once()
            self.assertEqual(2, len(fleet.hosts))
            # Each planned worker launched; a pool at its limit plans none ahead.
            self.assertEqual(2, len(RUN_CAMPAIGN.read_unit_workers(result_root / "unit-workers.tsv", self.PLATFORMS)))
            for host in fleet.hosts:
                # The second lease is written before the first completes; the longest unit runs first.
                self.assertLess(fleet.log.index(("lease", host.session, 2, host.leases[2]["unit_id"])),
                                fleet.log.index(("complete", host.session, 1)))
                estimates = [float(fleet.units[host.leases[sequence]["unit_id"]]["estimated_seconds"])
                             for sequence in (1, 2)]
                self.assertEqual(sorted(estimates, reverse=True), estimates)
                self.assertEqual(True, host.leases[3].get("stop"))
                self.assertEqual(0, host.status)

    def test_confirmation_runs_on_a_new_instance_and_is_accepted_beside_the_primary_campaign(self):
        with tempfile.TemporaryDirectory() as temporary:
            fleet = FakeUnitFleet(Path(temporary) / "hosts")
            for phase in ("primary", "confirmation"):
                (Path(temporary) / phase).mkdir()
            primary = self.run_phase(Path(temporary) / "primary", fleet, "primary", ["like-dfa-single-use"], 4, 2)
            receipts = RUN_CAMPAIGN.read_accepted_units(primary / "accepted-units.tsv")
            unit_id = receipts[0]["unit_id"]
            used = frozenset(receipt["instance_id"] for receipt in receipts
                             if (receipt["platform"], receipt["unit_id"]) == ("r8i", unit_id))
            gate = RUN_CAMPAIGN.unit_session.validate_unit_campaign
            with patch.object(RUN_CAMPAIGN.unit_session, "validate_unit_campaign", wraps=gate) as validated:
                confirmation = self.run_phase(Path(temporary) / "confirmation", fleet, "confirmation",
                                              ["like-dfa-single-use"], 2, 1, confirmations=[("r8i", unit_id, 4, used)],
                                              primary_results=primary)
            [confirmed] = RUN_CAMPAIGN.read_accepted_units(confirmation / "accepted-units.tsv")
            self.assertNotIn(confirmed["instance_id"], used)
            # The phase gate checks the primary campaign with the confirmation beside it.
            validated.assert_called_once()
            self.assertEqual((primary / "accepted-units.tsv", [confirmed]),
                             (validated.call_args.args[3], list(validated.call_args.kwargs["confirmations"])))

    def test_interruption_requeues_its_unit_on_demand(self):
        # The interruption is recorded once, whether or not the host's heartbeats reached S3.
        for script in ("interrupt", "silent-interrupt"):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as temporary:
                fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: script}})
                result_root = self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
                interrupted, other, replacement = fleet.hosts
                self.assertEqual(("spot", "spot", "on-demand"), (interrupted.market, other.market, replacement.market))
                self.assertEqual(
                    {interrupted.leases[1]["unit_id"], interrupted.leases[2]["unit_id"]},
                    {replacement.leases[1]["unit_id"], replacement.leases[2]["unit_id"]})
                self.assertEqual(3, len(other.leases))
                with (result_root / "campaign-events.tsv").open(newline="") as events:
                    [event] = [row for row in csv.DictReader(events, delimiter="\t")
                               if row["event_type"] == "interruption"]
                self.assertEqual(("spot-interruption-notice", "us-west-2"), (event["reason"], event["region"]))
                self.assertEqual((interrupted.leases[1]["unit_id"], f"{interrupted.session}/1"),
                                 (event["unit_id"], event["unit_attempt_id"]))

    def test_spot_returns_to_a_full_region_after_its_cool_down(self):
        with tempfile.TemporaryDirectory() as temporary:
            fleet = FakeUnitFleet(Path(temporary) / "hosts", full_spot={"r8i": 2})
            result_root = self.run_phase(Path(temporary), fleet, "smoke", ["like-dfa-single-use"], 2, 1,
                                         regions=["us-east-2", "us-west-2"], on_demand=False)
            first, second, third = [host for host in fleet.hosts if host.platform == "r8i"][:3]
            self.assertEqual([("spot", "us-east-2"), ("spot", "us-west-2"), ("spot", "us-east-2")],
                             [(host.market, host.region) for host in (first, second, third)])
            # Each failed launch is in the event history with its region.
            with (result_root / "campaign-events.tsv").open(newline="") as events:
                self.assertEqual(
                    [("interruption", "launch-capacity:InsufficientInstanceCapacity", region)
                     for region in ("us-east-2", "us-west-2")],
                    [(row["event_type"], row["reason"], row["region"]) for row in csv.DictReader(events, delimiter="\t")])
            # While both regions cooled, the pool asked for On-Demand, which this budget refuses.
            self.assertIn("on-demand", [call.args[1].market for call in self.budget.reserve.call_args_list])
            self.assertGreaterEqual(third.launched_at - first.launched_at, RUN_CAMPAIGN.SPOT_REGION_COOL_DOWN_SECONDS)

    def test_early_rebalance_moves_the_pools_spot_workers_to_another_region(self):
        # Before its first unit, or a minute after launch with one unit done.
        for sequence in (1, 2):
            with self.subTest(sequence=sequence), tempfile.TemporaryDirectory() as temporary:
                fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {sequence: "rebalance"}})
                result_root = self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"],
                                             2, 1, regions=["us-east-2", "us-west-2"])
                rebalanced = fleet.hosts[0]
                replacement = next(host for host in fleet.hosts[1:] if host.platform == rebalanced.platform)
                self.assertEqual([("spot", "us-east-2"), ("spot", "us-west-2")],
                                 [(host.market, host.region) for host in (rebalanced, replacement)])
                with (result_root / "campaign-events.tsv").open(newline="") as events:
                    event = next(csv.DictReader(events, delimiter="\t"))
                self.assertEqual(("interruption", "rebalance-recommendation", "us-east-2", "us-east-2b", "9"),
                                 (event["event_type"], event["reason"], event["region"],
                                  event["availability_zone"], event["placement_score"]))

    def test_late_completion_after_requeue_is_superseded(self):
        # A superseded completion needs none of its evidence, so calibrations that never landed do not reject it.
        for script in ("stall", "stall-lose-calibration"):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as temporary:
                fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: script}}, calibrated=True)
                self.run_phase(Path(temporary), fleet, "smoke", ["like-dfa-single-use"], 4, 2)
                stalled = fleet.hosts[0]
                replacement = next(host for host in fleet.hosts[2:] if host.platform == stalled.platform)
                self.assertEqual([RUN_CAMPAIGN.signal.SIGTERM], stalled.signals)
                outcomes = {(row["host_session"], row["outcome"]) for row in self.ledger}
                self.assertLessEqual({(stalled.session, "lost"), (stalled.session, "superseded"),
                                      (replacement.session, "accepted")}, outcomes)

    def test_tail_stops_spot_workers_and_finishes_on_demand(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(RUN_CAMPAIGN, "ON_DEMAND_TAIL_PERCENT", 70):
            fleet = FakeUnitFleet(Path(temporary) / "hosts")
            self.run_phase(Path(temporary), fleet, "primary", ["like-dfa-single-use"], 4, 2)
            spot = [host for host in fleet.hosts if host.market == "spot"]
            on_demand = [host for host in fleet.hosts if host.market == "on-demand"]
            self.assertEqual((4, 2), (len(spot), len(on_demand)))
            for host in spot:
                self.assertEqual([1, 2], sorted(host.leases))
                self.assertTrue(host.leases[2]["stop"])
            self.assertEqual([3, 3], [host.leases[1]["replica_id"] for host in on_demand])
            for session in {row["host_session"] for row in self.leases()}:
                units = [row["unit_id"] for row in self.leases() if row["host_session"] == session]
                self.assertEqual(len(units), len(set(units)))

    def test_restart_resumes_live_workers_without_duplicate_leases(self):
        with tempfile.TemporaryDirectory() as temporary:
            fleet = FakeUnitFleet(Path(temporary) / "hosts", abort_after=2)
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            self.assertTrue(any(entry[0] == "complete" for entry in fleet.log))
            fleet.abort_after = None
            with self.dispatchers() as dispatchers:
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            self.assertEqual(2, len(fleet.hosts))
            # An adopted worker is booked to the region it was placed in.
            self.assertEqual({int(host.epoch): host.region for host in fleet.hosts},
                             {epoch: job.region for epoch, job in dispatchers[0][1].items()})
            leased = [(row["platform"], row["unit_id"], row["replica"]) for row in self.leases()]
            self.assertEqual(len(leased), len(set(leased)))
            self.assertEqual(4, len(self.leases("accepted")))

    def test_phase_resumes_only_with_the_tooling_that_started_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            fleet = FakeUnitFleet(Path(temporary) / "hosts", abort_after=2)
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            fleet.abort_after = None
            digest = Path(temporary) / "results" / RUN_CAMPAIGN.TOOLING_DIGEST
            fleet.tooling = "d" * 64
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "started by other tooling"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            fleet.tooling = "c" * 64
            digest.unlink()
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "records no tooling digest"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)

    def test_boot_failures_pass_to_their_slots_replacements(self):
        shards = ["trino-like-specialized"]
        with self.subTest(scenario="half the boots fail"), tempfile.TemporaryDirectory() as temporary:
            # Three of six concurrent r8i boots fail; each slot's replacement measures its units.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", failed_boot=lambda host: host.number in (2, 4, 6))
            self.run_phase(Path(temporary), fleet, "primary", shards, 12, 6)
            self.assertEqual(["r8i"] * 6, [host.platform for host in fleet.hosts[:6]])
        with self.subTest(scenario="silent Spot worker started"), tempfile.TemporaryDirectory() as temporary:
            # A Spot worker whose heartbeats never landed ran its lease, so it started; the two On-Demand
            # replacements that then fail to boot leave its slot one failure short of giving up.
            failed = set()

            def two_on_demand_failures(host):
                if host.market == "on-demand" and len(failed) < 2:
                    failed.add(host.number)
                return host.number in failed

            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: "silent-interrupt"}},
                                  failed_boot=two_on_demand_failures)
            self.run_phase(Path(temporary), fleet, "smoke", shards, 2, 1)
            self.assertEqual(["spot", "on-demand", "on-demand", "on-demand"],
                             [host.market for host in fleet.hosts if host.platform == "r8i"][:4])
        with self.subTest(scenario="every boot fails"), tempfile.TemporaryDirectory() as temporary:
            # A slot that keeps failing still gives up, and its pool's work stays unresolved.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", failed_boot=lambda host: host.platform == "r8i")
            with self.assertRaisesRegex(SystemExit, "unresolved units: r8i/"):
                self.run_phase(Path(temporary), fleet, "primary", shards, 12, 6)
            self.assertEqual(6 * (RUN_CAMPAIGN.MAX_REPLACEMENTS_PER_JOB + 1),
                             sum(host.platform == "r8i" for host in fleet.hosts))
            unresolved = self.unresolved(Path(temporary) / "results")
            self.assertEqual({"r8i"}, {platform for platform, _, _ in unresolved})
            self.assertTrue(all("exhausted their replacements" in detail for detail in unresolved.values()))
        with self.subTest(scenario="restart at every poll"), tempfile.TemporaryDirectory() as temporary:
            # A restart at every poll, and one after the phase ends, keeps each slot's count and its giving up.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", failed_boot=lambda host: host.platform == "r8i")
            restarts = ended = 0
            while ended < 2:
                fleet.abort_after = fleet.sleeps + 1
                try:
                    self.run_phase(Path(temporary), fleet, "primary", shards, 12, 6)
                except SystemExit as error:
                    if str(error) == "controller stopped":
                        restarts += 1
                    else:
                        self.assertRegex(str(error), "unresolved units: r8i/")
                        ended += 1
                self.assertLessEqual(sum(host.platform == "r8i" for host in fleet.hosts),
                                     6 * (RUN_CAMPAIGN.MAX_REPLACEMENTS_PER_JOB + 1))
            self.assertGreater(restarts, 2)
            self.assertEqual(6 * (RUN_CAMPAIGN.MAX_REPLACEMENTS_PER_JOB + 1),
                             sum(host.platform == "r8i" for host in fleet.hosts))
            with (Path(temporary) / "results" / "job-attempts.tsv").open(newline="") as attempts:
                starting = [dict(part.split("=", 1) for part in row["detail"].split(";"))
                            for row in csv.DictReader(attempts, delimiter="\t")
                            if row["outcome"] == "starting" and row["platform"] == "r8i"]
            slots = {}
            for placement in starting:
                slots.setdefault(placement["slot"], []).append(int(placement["slot_failures"]))
            self.assertEqual([[0, 1, 2]] * 6, list(slots.values()))

    def test_spot_workers_keep_launching_while_on_demand_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The interrupted unit must run On-Demand, which the fleet refuses until the deadline.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: "interrupt"}})
            with self.assertRaisesRegex(SystemExit, "unresolved units"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1,
                               on_demand=False, deadline=3600)
            interrupted = fleet.hosts[0]
            self.assertEqual(
                {("r8i", interrupted.leases[1]["unit_id"], "1")}, set(self.unresolved(Path(temporary) / "results")))
            replacement = next(host for host in fleet.hosts[1:] if host.platform == "r8i")
            self.assertEqual(("spot", interrupted.leases[2]["unit_id"]),
                             (replacement.market, replacement.leases[1]["unit_id"]))

    def test_tail_keeps_spot_workers_while_on_demand_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(RUN_CAMPAIGN, "ON_DEMAND_TAIL_PERCENT", 70):
            fleet = FakeUnitFleet(Path(temporary) / "hosts")
            self.run_phase(Path(temporary), fleet, "primary", ["like-dfa-single-use"], 4, 2, on_demand=False)
            self.assertEqual({"spot"}, {host.market for host in fleet.hosts})

    def test_work_requeued_after_the_deadline_is_left_unresolved(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The deadline passes while the first host holds its lease; the host is then lost.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: "stall"}})
            with self.assertRaisesRegex(SystemExit, "unresolved units"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1,
                               deadline=1.5 * RUN_CAMPAIGN.UNIT_POLL_SECONDS)
            stalled = fleet.hosts[0]
            unresolved = self.unresolved(Path(temporary) / "results")
            self.assertIn(("r8i", stalled.leases[1]["unit_id"], "1"), unresolved)
            self.assertIn((stalled.session, "lost"), {(row["host_session"], row["outcome"]) for row in self.ledger})

    def test_notice_between_units_releases_the_waiting_lease(self):
        with tempfile.TemporaryDirectory() as temporary:
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {2: "notice"}})
            self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            noticed = fleet.hosts[0]
            outcomes = [row["outcome"] for row in self.ledger
                        if (row["host_session"], row["sequence"]) == (noticed.session, "2")]
            self.assertEqual(["leased", "released"], outcomes)
            # The unit was not charged, so it runs on Spot again.
            replacement = next(host for host in fleet.hosts[1:] if host.platform == "r8i")
            self.assertEqual(("spot", noticed.leases[2]["unit_id"]),
                             (replacement.market, replacement.leases[1]["unit_id"]))

    def test_lost_lease_whose_host_reports_an_interruption_spends_one_attempt(self):
        with tempfile.TemporaryDirectory() as temporary:
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: "stall-interrupt"}})
            with self.dispatchers() as dispatchers:
                self.run_phase(Path(temporary), fleet, "smoke", ["like-dfa-single-use"], 2, 1)
            stalled = fleet.hosts[0]
            outcomes = [row["outcome"] for row in self.ledger
                        if (row["host_session"], row["sequence"]) == (stalled.session, "1")]
            self.assertEqual(["leased", "lost", "interrupted"], outcomes)
            identity = f"r8i/{stalled.leases[1]['unit_id']}/replica-1"
            self.assertEqual(1, dispatchers[0][0].failures[identity])

    def test_worker_session_waits_for_a_whole_session_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            session = Path(temporary)
            job = RUN_CAMPAIGN.Job(self.PLATFORMS[0], RUN_CAMPAIGN.UNIT_WORKER_SHARD, 1, 1, 1, "spot", "language-batch")
            worker = RUN_CAMPAIGN.UnitWorker(job, None, None, session=session)
            # The worker already knows its session directory, so the dispatcher's result root goes unread.
            dispatcher = SimpleNamespace(result_root=session)
            for contents, ready in (("", False), ("session=s\nbucket=b\n", False),
                                    ("session=s\nbucket=b\nresult_prefix=s/results\n", True)):
                (session / "session.txt").write_text(contents)
                self.assertEqual(ready, RUN_CAMPAIGN.UnitDispatcher.worker_session(dispatcher, worker))
            self.assertEqual("s/results", worker.metadata["result_prefix"])

    def test_restart_settles_a_worker_that_exited_before_its_first_lease(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The controller stops while a Spot launch that found no capacity exits.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", full_spot={"r8i": 1}, abort_after=1)
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            fleet.abort_after = None
            self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            failed = fleet.hosts[0]
            self.assertEqual(("r8i", "spot", 1), (failed.platform, failed.market, failed.status))
            self.assertEqual({}, failed.leases)

    def test_restart_releases_a_reservation_made_while_its_worker_waited_for_capacity(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The only region has no Spot capacity, and On-Demand waits for the fleet's allowance.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", full_spot={"r8i": 1}, abort_after=3)
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1,
                               on_demand=False)
            waiting = self.budget.reserve.call_args_list[-1].args[1]
            self.assertEqual(("r8i", "on-demand"), (waiting.platform.name, waiting.market))
            # The controller stopped after the reservation, before the wrapper started.
            fleet.reservations = [{"campaign_id": "campaign", "logical_identity": waiting.logical_identity,
                                   "host_epoch": waiting.host_epoch, "attempt": waiting.attempt}]
            fleet.abort_after = None
            self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            self.budget.release.assert_any_call("campaign", waiting.host_epoch, cleanup_verified=True)

    def test_restart_redoes_a_calibration_copy_a_crash_cut_short(self):
        with tempfile.TemporaryDirectory() as temporary:
            fleet = FakeUnitFleet(Path(temporary) / "hosts", calibrated=True)
            copyfile = RUN_CAMPAIGN.shutil.copyfile

            def crash_while_retaining(source, target, **kwargs):
                if Path(source).parent.name == "calibrations" and "units" in Path(target).parts:
                    Path(target).write_bytes(Path(source).read_bytes()[:10])
                    raise SystemExit("controller stopped")
                return copyfile(source, target, **kwargs)

            with patch.object(RUN_CAMPAIGN.shutil, "copyfile", side_effect=crash_while_retaining), \
                    self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            result_root = self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            for calibration in result_root.glob("units/**/calibrations/*"):
                self.assertEqual(calibration.name, hashlib.sha256(calibration.read_bytes()).hexdigest() + ".json")

    def test_completion_that_fails_its_checks_is_rejected_without_a_retry(self):
        # The campaign measures a release, so every other completion proves it measured the release's JAR.
        for script, detail in (
                ("lose-calibration", "named host calibration is missing"),
                ("corrupt-archive", "result archive does not match its completion marker"),
                ("capacity-rejected", "unit capacity gate rejected the host's resources"),
                ("foreign-receipt", "artifact gate: unit receipt names "),
                ("unreleased", "release-artifact.json")):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(RUN_CAMPAIGN.released_artifact, "manifest", return_value=RELEASE):
                fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: script}}, calibrated=True)
                with self.assertRaisesRegex(SystemExit, f"unresolved units: .*{re.escape(detail)}"):
                    self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1,
                                   release_version=RELEASE["version"])
                rejected = fleet.hosts[0]
                self.assertEqual([(rejected.session, "1")], [(row["host_session"], row["sequence"])
                                                            for row in self.leases("rejected")])
                self.assertTrue(self.leases("accepted"))
                self.assertEqual(1, self.lease_count(rejected.leases[1]))
                self.assertEqual(0, rejected.status)

    def test_completion_marker_unlike_its_lease_stops_the_campaign(self):
        # A marker that names another campaign, or lacks a field the controller reads, is not evidence of
        # this lease, so the controller stops every worker rather than judge the unit.
        for script, error in (
                ("foreign-marker", r"unit completion marker r8i-session-1/1 does not match its lease: "
                                   r"\['campaign_id'\]"),
                ("partial-marker", r"unit completion marker r8i-session-1/1 lacks \['result_sha256'\]")):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as temporary:
                fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: script}})
                with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, error):
                    self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
                self.budget.halt.assert_called_once()
                self.assertEqual([], [host.session for host in fleet.hosts if host.status is None])
                self.assertEqual([], self.leases("accepted"))
                self.assertEqual([], RUN_CAMPAIGN.read_accepted_units(Path(temporary) / "results/accepted-units.tsv"))

    def test_chained_calibration_must_be_the_previous_leases_after(self):
        with tempfile.TemporaryDirectory() as temporary:
            # Each host's second lease claims a chain from its first; the r9g host's claim is false.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {2: "chain"}, 2: {2: "false-chain"}},
                                  calibrated=True)
            with self.assertRaisesRegex(SystemExit, "unresolved units: r9g/.*does not chain from the completion "
                                                    "of r9g-session-2/1: calibration_after"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            chained, falsely_chained = fleet.hosts
            self.assertEqual((("r8i", "accepted"), ("r9g", "rejected")), tuple(
                (host.platform, next(row["outcome"] for row in self.ledger if row["outcome"] != "leased" and
                                     (row["host_session"], row["sequence"]) == (host.session, "2")))
                for host in (chained, falsely_chained)))
            # The accepted chained attempt keeps its previous lease's completion marker beside it.
            retained = next((Path(temporary) / "results" / "units").glob(f"r8i/*/replica-1/{chained.session}/lease-2"))
            marker = json.loads((retained.parent / "lease-1-completion.json").read_text())
            link = RUN_CAMPAIGN.read_properties(retained / "unit-chain.txt")
            self.assertEqual((f"{chained.session}/1", "complete", link["calibration"]),
                             (marker["unit_attempt_id"], marker["unit_status"], marker["calibration_after"]))

    def test_semantic_failure_or_protocol_rejection_is_final(self):
        for script, detail in (("semantic-failure", "semantic failure: status 44"),
                               ("protocol-rejection", "numerical protocol rejection: status 42")):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as temporary:
                fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: script}})
                with self.assertRaisesRegex(SystemExit, f"unresolved units: .*{detail}"):
                    self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
                self.assertEqual(1, self.lease_count(fleet.hosts[0].leases[1]))

    def test_failed_unit_is_retried_until_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: "fail"}})
            self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            failed = fleet.hosts[0]
            self.assertEqual([(failed.session, "1")],
                             [(row["host_session"], row["sequence"]) for row in self.leases("failed")])
            self.assertEqual(2, self.lease_count(failed.leases[1]))

    def test_unit_that_fails_every_attempt_is_left_unresolved(self):
        with tempfile.TemporaryDirectory() as temporary:
            units = RUN_CAMPAIGN.work_units.load(SCRIPT.parent)
            unit_id = next(unit_id for unit_id, unit in sorted(units.items()) if unit["shard_id"] == "trino-like-specialized")
            fleet = FakeUnitFleet(Path(temporary) / "hosts", unit_scripts={unit_id: "fail"})
            with self.assertRaisesRegex(SystemExit, f"unresolved units: r8i/{unit_id}/replica-1 .*unit failed with "
                                                    f"status 1; spent its {RUN_CAMPAIGN.MAX_UNIT_ATTEMPTS} attempts"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            self.assertEqual(RUN_CAMPAIGN.MAX_UNIT_ATTEMPTS, self.lease_count({"unit_id": unit_id, "replica_id": 1}))

    def test_semantic_failure_of_a_lost_lease_outlasts_its_replacement(self):
        # The lost lease's semantic failure lands before or after its replacement completes.
        for script, replacement_outcome in (("stall-semantic-early", "superseded"), ("stall-semantic", "accepted")):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as temporary:
                fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: script}})
                with self.assertRaisesRegex(SystemExit, "unresolved units: r8i/.*semantic failure: status 44"):
                    self.run_phase(Path(temporary), fleet, "smoke", ["like-dfa-single-use"], 4, 2)
                stalled = fleet.hosts[0]
                outcomes = {(row["host_session"], row["sequence"]): row["outcome"]
                            for row in self.ledger if row["outcome"] not in ("leased", "lost")}
                replacement = next(host for host in fleet.hosts[1:] if host.platform == "r8i" and any(
                    lease.get("unit_id") == stalled.leases[1]["unit_id"] for lease in host.leases.values()))
                sequence = next(sequence for sequence, lease in replacement.leases.items()
                                if lease.get("unit_id") == stalled.leases[1]["unit_id"])
                self.assertEqual(("rejected", replacement_outcome),
                                 (outcomes[(stalled.session, "1")], outcomes[(replacement.session, str(sequence))]))
                self.assertEqual({("r8i", stalled.leases[1]["unit_id"], "1"): "semantic failure: status 44"},
                                 self.unresolved(Path(temporary) / "results"))
                # The next phase refuses this one, even where the unit's replacement was accepted.
                with fleet.stubs(), self.assertRaisesRegex(
                        SystemExit, f"smoke prerequisite campaign is not accepted: .*semantic failure: "
                                    f"\\['{stalled.session}/1'\\]"):
                    RUN_CAMPAIGN.validate_unit_phase_prerequisite(
                        SCRIPT.parents[3], self.arguments, Path(temporary) / "results", (1,), "smoke")
                # So does the standalone campaign validator.
                validation = subprocess.run(
                    [sys.executable, SCRIPT.parent / "validate-campaign-results.py", "--campaign-id", "campaign",
                     "--accepted-units", Path(temporary) / "results/accepted-units.tsv",
                     "--shard", "like-dfa-single-use"],
                    capture_output=True, text=True)
                self.assertEqual(1, validation.returncode, validation.stdout)
                self.assertIn(f"rejected unit attempts for a semantic failure: ['{stalled.session}/1']",
                              validation.stderr)


if __name__ == "__main__":
    unittest.main()
