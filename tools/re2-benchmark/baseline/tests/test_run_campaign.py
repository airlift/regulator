import csv
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).parents[1] / "run-campaign.py"
AWS_RUNNER = SCRIPT.parents[1] / "aws" / "run-campaign.sh"
REBAR_REVISION = SCRIPT.parents[1] / "manifests" / "rebar-revision.sh"
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location("baseline_run_campaign", SCRIPT)
RUN_CAMPAIGN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN_CAMPAIGN)


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
        for classification in ("protocol-qualification", "benchmark-failure", "none", "invalid-artifacts"):
            with self.subTest(classification=classification), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                result_root = root / "results"
                result_root.mkdir()
                arguments = self.arguments(root, maximum_concurrent=2)
                budget = Mock(policy={"hourly_rates": {self.platform.instance_type: {"spot": 0.2}}})
                budget.reserve.return_value = (True, "")
                arguments.shared_budget = budget
                jobs = [RUN_CAMPAIGN.Job(self.platform, shard, 1, index, 1, "spot")
                        for index, shard in enumerate(("traditional-extra", "traditional-search", "traditional-capture"), 1)]
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
                RUN_CAMPAIGN.validate_receipt_candidate(root, arguments, job, receipt)

    def test_release_campaign_requires_baseline_artifact_proof_on_recovery(self):
        root = SCRIPT.parents[3]
        arguments = self.arguments(root)
        arguments.release_version = "1.0"
        job = RUN_CAMPAIGN.Job(self.platform, "engine", 1, 1, 1, "spot")
        with self.assertRaisesRegex(RuntimeError, "recovered artifact evidence"):
            RUN_CAMPAIGN.validate_receipt_candidate(root, arguments, job, {})

    def test_platform_limit_applies_independently_of_global_capacity(self):
        arguments = SimpleNamespace(max_concurrent=9, max_concurrent_per_platform=3)
        job = SimpleNamespace(platform=SimpleNamespace(name='r9g'))
        other = SimpleNamespace(platform=SimpleNamespace(name='r8i'))
        running = {index: (None, job, None) for index in range(3)}
        self.assertFalse(RUN_CAMPAIGN.platform_has_capacity(job, running, arguments))
        self.assertTrue(RUN_CAMPAIGN.platform_has_capacity(other, running, arguments))
        del running[0]
        self.assertTrue(RUN_CAMPAIGN.platform_has_capacity(job, running, arguments))

    def test_pinned_jobs_allocate_two_vcpus_except_multicore_shard(self):
        platforms = RUN_CAMPAIGN.read_platforms(SCRIPT.parent)
        jobs = RUN_CAMPAIGN.build_jobs("primary", platforms, ["engine", "lifecycle-shared-cold"], None)
        self.assertEqual(len(jobs), 18)
        for job in jobs:
            expected_vcpus = 8 if job.shard == "lifecycle-shared-cold" else 2
            expected_size = ".2xlarge" if expected_vcpus == 8 else ".large"
            self.assertEqual(job.platform.vcpus, expected_vcpus)
            self.assertEqual(job.platform.instance_type, job.platform.name + expected_size)
            arguments = self.arguments(SCRIPT.parents[3])
            environment = RUN_CAMPAIGN.job_environment(job, arguments, SCRIPT.parents[3], Path("results"))
            self.assertEqual(environment["BENCHMARK_EXPECTED_VCPUS"], str(expected_vcpus))
            self.assertEqual(environment["BENCHMARK_CPU_LIST"], "0-7" if expected_vcpus == 8 else "0")

    def test_spot_only_replacements_never_fall_back(self):
        for attempt in (1, 2):
            for classification, detail in (
                    ("retryable-spot", "launch-capacity:InsufficientInstanceCapacity"),
                    ("retryable-spot", "instance-terminated-capacity-oversubscribed"),
                    ("retryable-capacity", "vcpu-limit:VcpuLimitExceeded"),
                    ("retryable-infrastructure", "wrapper failed")):
                with self.subTest(attempt=attempt, classification=classification, detail=detail):
                    job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, attempt, "spot")
                    replacement = RUN_CAMPAIGN.replacement_job(
                        job, 11, classification, detail, spot_only=True)
                    self.assertEqual(replacement.market, "spot")
                    self.assertEqual(replacement.attempt, attempt + 1)
        job = RUN_CAMPAIGN.replace(job, market="on-demand")
        with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "On-Demand"):
            RUN_CAMPAIGN.replacement_job(job, 11, "retryable-spot", "interrupted", spot_only=True)

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

    def test_spot_only_launcher_rejects_on_demand_before_aws(self):
        environment = dict(os.environ, CAMPAIGN_SPOT_ONLY="1", INSTANCE_MARKET_TYPE="on-demand")
        result = subprocess.run([str(AWS_RUNNER), "test"], env=environment, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Spot-only policy prohibits On-Demand", result.stderr)

    def test_spot_only_quota_shortage_waits_without_on_demand(self):
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
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
        subprocess.run(["git", "add", "."], cwd=repository, check=True)
        subprocess.run(["git", "commit", "-qm", "Candidate"], cwd=repository, check=True)

        candidate_ref = "refs/benchmarks/test-candidate"
        subprocess.run(["git", "update-ref", candidate_ref, "HEAD"], cwd=repository, check=True)
        archive = root / "candidate.tar.gz"
        with archive.open("wb") as output_file:
            git_archive = subprocess.Popen(
                ["git", "archive", "--format=tar", "HEAD"], cwd=repository, stdout=subprocess.PIPE)
            gzip = subprocess.run(["gzip", "-n"], stdin=git_archive.stdout, stdout=output_file, check=True)
            git_archive.stdout.close()
            self.assertEqual(git_archive.wait(), 0)
            self.assertEqual(gzip.returncode, 0)

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

    def prerequisite_receipt(self):
        return {
            "schema_version": "2",
            "campaign_id": "baseline",
            "platform": "r8i",
            "shard_id": "traditional-search",
            "replica_id": "1",
            "instance_id": "i-00000000000000001",
            "host_epoch": "10",
            "architecture": "intel",
            "instance_type": "r8i.2xlarge",
            "availability_zone": "us-west-2a",
            "systems": "regulator-native-access",
            "row_count": "1",
            "manifest_sha256": "1" * 64,
            "expected_rows_sha256": "2" * 64,
            "observed_rows_sha256": "3" * 64,
            "evidence_manifest_sha256": "4" * 64,
            "candidate_commit": "a" * 40,
            "candidate_archive_sha256": "5" * 64,
            "engine_tree": "e" * 40,
            "heap_size": "8g",
        }

    def receipt_for_job(self, job):
        return {
            **self.prerequisite_receipt(),
            "platform": job.platform.name,
            "shard_id": job.shard,
            "replica_id": str(job.replica),
            "host_epoch": str(job.host_epoch),
            "architecture": job.platform.architecture,
            "instance_type": job.platform.instance_type,
        }

    @staticmethod
    def write_receipts(path, rows):
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", newline="") as output_file:
            writer = csv.DictWriter(
                output_file,
                fieldnames=RUN_CAMPAIGN.RECEIPT_FIELDS,
                delimiter="\t",
                lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)

    def write_cleanup_session(
            self,
            result_root,
            job,
            *,
            failure_classification="controller-abort",
            failure_detail="test failure",
            bucket_removed="verified"):
        session = RUN_CAMPAIGN.job_result_root(result_root, job) / "session"
        session.mkdir(parents=True)
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
            f"failure_detail={failure_detail}\n")
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

    def test_deterministic_archive_checksum_matches_git_archive(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=root, check=True)
            (root / "file.txt").write_text("content\n")
            subprocess.run(["git", "add", "file.txt"], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "Test"], cwd=root, check=True)
            archive = root / "candidate.tar.gz"
            with archive.open("wb") as output_file:
                git_archive = subprocess.Popen(
                    ["git", "archive", "--format=tar", "HEAD"], cwd=root, stdout=subprocess.PIPE)
                gzip = subprocess.run(["gzip", "-n"], stdin=git_archive.stdout, stdout=output_file, check=True)
                git_archive.stdout.close()
                self.assertEqual(git_archive.wait(), 0)
                self.assertEqual(gzip.returncode, 0)

            self.assertEqual(
                RUN_CAMPAIGN.deterministic_archive_sha256(root, "HEAD"),
                hashlib.sha256(archive.read_bytes()).hexdigest())

    def test_candidate_source_accepts_its_own_new_engine_tree(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository, arguments, values = self.frozen_candidate(root)

            RUN_CAMPAIGN.validate_source(repository, arguments)

            self.assertEqual(arguments.engine_tree, values["engine_tree"])
            self.assertEqual(arguments.candidate_archive_sha256, values["archive_sha256"])

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
            env={**os.environ, "CAMPAIGN_MODE": "baseline-shard", "TIMEOUT_SECONDS": "5401"})

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("require TIMEOUT_SECONDS=5400", result.stderr)

    def test_diagnostic_input_archive_and_checksum_are_required_together(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "inputs.tar.gz"
            archive.write_bytes(b"fixture")
            for environment in (
                    {"BENCHMARK_DIAGNOSTIC_INPUT_ARCHIVE": str(archive)},
                    {"BENCHMARK_DIAGNOSTIC_INPUT_SHA256": "0" * 64}):
                result = subprocess.run(
                    [str(AWS_RUNNER)], capture_output=True, text=True,
                    env={**os.environ, **environment})
                with self.subTest(environment=environment):
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("Diagnostic input requires a plan, archive and SHA-256", result.stderr)

    def test_native_re2_execution_requires_native_manifest_rows(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()
        guard = '[[ "${ROUTE}" == native-access && ${native_system_row_count} -gt 0 ]]'

        self.assertGreaterEqual(source.count(guard), 3)

    def test_rebar_measurement_watchdog_allows_slow_valid_joni_rows(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertIn("REBAR_MEASUREMENT_TIMEOUT=120s", source)
        self.assertEqual(source.count('--timeout "${REBAR_MEASUREMENT_TIMEOUT}"'), 2)

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

    def test_rebar_checkout_survives_candidate_clean(self):
        source = AWS_RUNNER.read_text()

        self.assertIn(
            'REBAR_OFFICIAL_DIR=${REBAR_OFFICIAL_DIR:-"${HOME}/.cache/regulator/'
            'rebar-463d00f31887e84c38467805b9e3122c314b9521"}',
            source)

    def test_generated_user_data_is_valid_bash(self):
        source = AWS_RUNNER.read_text()
        function = source[
            source.index("create_user_data()\n{"):
            source.index("\n}\n\nlaunch_instance()") + 2]
        with tempfile.TemporaryDirectory() as temporary_directory:
            user_data = Path(temporary_directory) / "user-data.sh"
            script = (
                f"{function}\n"
                "CAMPAIGN_MODE=baseline-shard\n"
                f"create_user_data test x86_64 result.tar.gz {user_data} ami url sha\n"
                f"bash -n {user_data}\n")

            result = subprocess.run(
                ["bash"], input=script, text=True, capture_output=True, check=False)

            self.assertEqual(result.returncode, 0, result.stderr)

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
                "CAMPAIGN_MODE": "baseline-shard",
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
                "CAMPAIGN_MODE": "baseline-shard",
                "TRANSFER_BUCKET": "benchmark-transfer",
                "TRANSFER_PREFIX": "../another-campaign"})

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must be a safe relative S3 prefix", result.stderr)

    def test_prerequisite_recomputes_artifact_receipt(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            results = root / "smoke"
            receipt = self.prerequisite_receipt()
            self.write_receipts(results / "accepted-sessions.tsv", [receipt])
            artifact_receipt = root / "artifact" / "acceptance-receipt.tsv"
            self.write_receipts(artifact_receipt, [receipt])
            arguments = SimpleNamespace(
                phase="primary",
                campaign_id="baseline",
                candidate_archive_sha256="5" * 64,
                engine_tree=receipt["engine_tree"])

            with patch.object(RUN_CAMPAIGN, "validate_campaign"), \
                    patch.object(RUN_CAMPAIGN, "validate_job_artifacts", return_value=artifact_receipt), \
                    patch.object(RUN_CAMPAIGN, "validate_host_results", return_value=receipt) as regenerate, \
                    patch.object(RUN_CAMPAIGN, "check_output", return_value="a" * 40):
                RUN_CAMPAIGN.validate_phase_prerequisite(
                    root, arguments, [self.platform], results, (1,), "smoke")

            regenerate.assert_called_once()

            arguments.release_version = '1.0'
            with patch.object(RUN_CAMPAIGN, "validate_campaign"), \
                    patch.object(RUN_CAMPAIGN, "validate_job_artifacts", return_value=artifact_receipt), \
                    patch.object(RUN_CAMPAIGN, "validate_host_results", return_value=receipt), \
                    patch.object(RUN_CAMPAIGN.released_artifact, "manifest", return_value={}), \
                    patch.object(RUN_CAMPAIGN.released_artifact, "validate_baseline", side_effect=ValueError('missing release proof')):
                with self.assertRaisesRegex(SystemExit, 'missing release proof'):
                    RUN_CAMPAIGN.validate_phase_prerequisite(
                        root, arguments, [self.platform], results, (1,), "smoke")

    def test_prerequisite_rejects_unreproducible_receipt(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            results = root / "smoke"
            receipt = self.prerequisite_receipt()
            self.write_receipts(results / "accepted-sessions.tsv", [receipt])
            artifact_receipt = root / "artifact" / "acceptance-receipt.tsv"
            self.write_receipts(artifact_receipt, [receipt])
            regenerated = {**receipt, "observed_rows_sha256": "9" * 64}
            arguments = SimpleNamespace(
                phase="primary",
                campaign_id="baseline",
                candidate_archive_sha256="5" * 64,
                engine_tree=receipt["engine_tree"])

            with patch.object(RUN_CAMPAIGN, "validate_campaign"), \
                    patch.object(RUN_CAMPAIGN, "validate_job_artifacts", return_value=artifact_receipt), \
                    patch.object(RUN_CAMPAIGN, "validate_host_results", return_value=regenerated), \
                    patch.object(RUN_CAMPAIGN, "check_output", return_value="a" * 40):
                with self.assertRaisesRegex(SystemExit, "cannot be reproduced"):
                    RUN_CAMPAIGN.validate_phase_prerequisite(
                        root, arguments, [self.platform], results, (1,), "smoke")

    def test_prerequisite_rejects_changed_heap(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            receipt = self.prerequisite_receipt()
            self.write_receipts(root / "accepted-sessions.tsv", [receipt])
            arguments = SimpleNamespace(phase="primary", campaign_id="baseline",
                                        candidate_archive_sha256="5" * 64,
                                        engine_tree=receipt["engine_tree"], heap_size="2g")
            with patch.object(RUN_CAMPAIGN, "validate_campaign"), \
                    patch.object(RUN_CAMPAIGN, "check_output", return_value="a" * 40), \
                    patch.object(RUN_CAMPAIGN, "validate_host_results", return_value=receipt), \
                    patch.object(RUN_CAMPAIGN, "validate_job_artifacts", return_value=root / "accepted-sessions.tsv"):
                with self.assertRaisesRegex(SystemExit, "heap_size"):
                    RUN_CAMPAIGN.validate_phase_prerequisite(root, arguments, [self.platform], root, (1,), "smoke")

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
        initial = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")

        replacement = RUN_CAMPAIGN.replacement_job(
            initial, 117, "retryable-spot", "instance-terminated-no-capacity")

        self.assertEqual(replacement.logical_identity, initial.logical_identity)
        self.assertEqual(replacement.host_epoch, 117)
        self.assertEqual(replacement.attempt, 2)
        self.assertEqual(replacement.market, "spot")

    def test_phase_epoch_ranges_do_not_overlap(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            confirmation_file = Path(temporary_directory) / "confirmation.tsv"
            confirmation_file.write_text("platform\tshard_id\nr8i\ttraditional-search\n")
            smoke = RUN_CAMPAIGN.build_jobs(
                "smoke", [self.platform], ["traditional-search"], None)
            primary = RUN_CAMPAIGN.build_jobs(
                "primary", [self.platform], ["traditional-search"], None)
            confirmation = RUN_CAMPAIGN.build_jobs(
                "confirmation", [self.platform], ["traditional-search"], confirmation_file)

        epochs = {job.host_epoch for job in smoke + primary + confirmation}
        self.assertEqual(len(smoke) + len(primary) + len(confirmation), len(epochs))

    def test_rebar_jobs_run_before_shorter_shards(self):
        second_platform = RUN_CAMPAIGN.Platform(
            "r8g",
            "arm",
            "r8g.2xlarge",
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

        jobs = RUN_CAMPAIGN.build_jobs(
            "primary",
            [self.platform, second_platform],
            ["traditional-search", "rebar-b", "rebar-a"],
            None)

        self.assertEqual(
            ["rebar-a"] * 6 + ["rebar-b"] * 6 + ["traditional-search"] * 6,
            [job.shard for job in jobs])
        self.assertEqual(
            [(replica, platform) for replica in (1, 2, 3) for platform in ("r8i", "r8g")],
            [(job.replica, job.platform.name) for job in jobs[:6]])

    def test_shared_policy_checks_the_actual_aws_account_and_region(self):
        arguments = SimpleNamespace(shared_budget=SimpleNamespace(
            policy={"account": "123456789012", "region": "us-east-2"}))
        for account, region in (("234567890123", "us-east-2"), ("123456789012", "us-west-2")):
            with self.subTest(account=account, region=region), \
                    patch.dict(os.environ, {"AWS_REGION": region}), \
                    patch.object(RUN_CAMPAIGN, "check_output", return_value=account), \
                    self.assertRaisesRegex(SystemExit, "differs from the shared fleet policy"):
                RUN_CAMPAIGN.validate_aws(arguments, [self.platform])

    def test_aws_preflight_uses_routed_worker_topologies(self):
        platform = RUN_CAMPAIGN.replace(
            self.platform,
            instance_type="r8i.large",
            vcpus=2,
            concurrency_instance_type="r8i.2xlarge",
            concurrency_vcpus=8)
        jobs = RUN_CAMPAIGN.build_jobs("smoke", [platform], ["lifecycle-shared-cold"], None)
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
                return "x86_64\tavailable\t137112412989"
            if "describe-instance-type-offerings" in command:
                return "us-west-2a" if "Name=instance-type,Values=r8i.large" in command else ""
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
                return "100"
            if "describe-images" in command:
                return "x86_64\tavailable\t137112412989"
            if "describe-instance-type-offerings" in command:
                return "us-west-2a"
            raise AssertionError(command)

        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result), \
                self.assertRaisesRegex(SystemExit, "max_vcpus exceeds"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        policy["max_vcpus"] = 48
        with patch.object(RUN_CAMPAIGN, "check_output", side_effect=aws_result):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])

    def test_campaign_concurrency_uses_configuration(self):
        jobs = RUN_CAMPAIGN.build_jobs(
            "primary", [self.platform], ["traditional-search"], None)
        arguments = SimpleNamespace(campaign_id="baseline", max_concurrent=512, phase="primary")
        RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.execute = True
        with self.assertRaisesRegex(SystemExit, "requires a shared fleet budget"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.shared_budget = SimpleNamespace(policy={"max_hosts": 512, "max_vcpus": 1024,
                                                          "spot_vcpu_reserve": 16})
        arguments.spot_only, arguments.spot_vcpu_reserve = True, 16
        RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.spot_vcpu_reserve = 0
        with self.assertRaisesRegex(SystemExit, "reserve matching its policy"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        arguments.spot_vcpu_reserve = 16
        arguments.max_concurrent = 0
        with self.assertRaisesRegex(SystemExit, "positive integer"):
            RUN_CAMPAIGN.validate_configuration(arguments, jobs)

    def test_deadline_uses_shared_fleet_host_and_vcpu_limits(self):
        jobs = RUN_CAMPAIGN.build_jobs("primary", [self.platform], ["traditional-search"], None)
        arguments = SimpleNamespace(campaign_id="baseline", max_concurrent=512, phase="primary",
                                    spot_only=True, spot_vcpu_reserve=0,
                                    shared_budget=SimpleNamespace(policy={"max_hosts": 512, "max_vcpus": 8,
                                                                         "spot_vcpu_reserve": 0}),
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
        shards = [f"shard-{index}" for index in range(33)]
        jobs = RUN_CAMPAIGN.build_jobs("primary", platforms, shards, None)

        RUN_CAMPAIGN.validate_configuration(
            SimpleNamespace(campaign_id="baseline", max_concurrent=64, phase="primary"),
            jobs)

        with self.assertRaisesRegex(SystemExit, "cannot fit its phase deadline"):
            RUN_CAMPAIGN.validate_configuration(
                SimpleNamespace(campaign_id="baseline", max_concurrent=32, phase="primary", phase_timeout_seconds=36000),
                jobs)

    def test_full_manifest_deadline_accounts_for_platform_and_vcpu_limits(self):
        jobs = RUN_CAMPAIGN.build_jobs("primary", RUN_CAMPAIGN.read_platforms(SCRIPT.parent),
                                       RUN_CAMPAIGN.read_shards(SCRIPT.parent), None)
        arguments = SimpleNamespace(campaign_id="final", max_concurrent=64, phase="primary")
        RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        self.assertEqual(RUN_CAMPAIGN.phase_timeout(arguments), 8 * 5400 + 3600)
        arguments.max_concurrent_per_platform = 10
        RUN_CAMPAIGN.validate_configuration(arguments, jobs)
        self.assertEqual(RUN_CAMPAIGN.phase_timeout(arguments), 15 * 5400 + 3600)

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
            job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")

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
        selector = source[source.index('select_subnet()'):source.index('\ncreate_user_data()')]
        fixture = '''
aws_cli() {
    if [[ "$*" == *describe-instance-type-offerings* ]]; then
        printf 'us-west-2a us-west-2b us-west-2c us-west-2d\\n'
    else
        printf 'subnet-a subnet-b subnet-c subnet-d\\n'
    fi
}
VPC_ID=vpc-test
CAMPAIGN_PLATFORM=r8i
CAMPAIGN_SHARD_ID=rebar-i
CAMPAIGN_REPLICA_ID=1
for CAMPAIGN_ATTEMPT in 1 2 3 4; do
    CAMPAIGN_HOST_EPOCH=$((CAMPAIGN_ATTEMPT * 4 + 1))
    select_subnet r8i.large
done
'''
        result = subprocess.run(['bash', '-c', selector + fixture], capture_output=True, text=True, check=True)
        self.assertEqual(set(result.stdout.splitlines()), {'subnet-a', 'subnet-b', 'subnet-c', 'subnet-d'})
        self.assertEqual(len(result.stdout.splitlines()), 4)

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
            job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")

            environment = RUN_CAMPAIGN.job_environment(job, arguments, root, root)

            self.assertNotIn("AWS_PROFILE", environment)
            self.assertNotIn("TRINO_DIR", environment)

    def test_trino_checkout_is_required_only_for_jobs_that_use_it(self):
        traditional = RUN_CAMPAIGN.Job(
            self.platform, "traditional-search", 1, 10, 1, "spot")
        trino = RUN_CAMPAIGN.Job(
            self.platform, "trino-operations", 1, 11, 1, "spot")
        with tempfile.TemporaryDirectory() as temporary_directory, \
                patch.dict(os.environ, {}, clear=True), \
                patch.object(RUN_CAMPAIGN.subprocess, "run"):
            root = Path(temporary_directory)
            RUN_CAMPAIGN.validate_dependencies(root, SimpleNamespace(), [traditional])
            with self.assertRaisesRegex(SystemExit, "requires TRINO_DIR"):
                RUN_CAMPAIGN.validate_dependencies(root, SimpleNamespace(), [trino])

    def test_dependencies_reject_invalid_host_duration_plans(self):
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")

        def validate(command, **kwargs):
            if Path(command[0]).name == "validate-shard-plans.py":
                raise subprocess.CalledProcessError(1, command)

        with tempfile.TemporaryDirectory() as directory, \
                patch.object(RUN_CAMPAIGN.subprocess, "run", side_effect=validate):
            with self.assertRaises(subprocess.CalledProcessError):
                RUN_CAMPAIGN.validate_dependencies(Path(directory), SimpleNamespace(), [job])

    def test_second_replacement_moves_to_on_demand(self):
        second_attempt = RUN_CAMPAIGN.Job(
            self.platform, "traditional-search", 1, 117, 2, "spot")

        replacement = RUN_CAMPAIGN.replacement_job(
            second_attempt,
            118,
            "retryable-spot",
            "instance-terminated-capacity-oversubscribed")

        self.assertEqual(replacement.attempt, 3)
        self.assertEqual(replacement.market, "on-demand")

    def test_launch_capacity_moves_directly_to_on_demand(self):
        initial = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")

        replacement = RUN_CAMPAIGN.replacement_job(
            initial,
            117,
            "retryable-spot",
            "launch-capacity:InsufficientInstanceCapacity")

        self.assertEqual(replacement.market, "on-demand")

    def test_spot_vcpu_quota_failure_is_retryable(self):
        source = AWS_RUNNER.read_text()

        self.assertIn(
            "InsufficientInstanceCapacity|UnfulfillableCapacity|MaxSpotInstanceCountExceeded",
            source)
        self.assertIn('record_failure_classification retryable-capacity "vcpu-limit:', source)

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
            "if classify_launch_error on-demand InternalError; then exit 9; fi\n")

        result = subprocess.run(
            ["bash"], input=script, text=True, capture_output=True, check=False)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), [
            "retryable-spot|launch-capacity:VcpuLimitExceeded",
            "retryable-capacity|vcpu-limit:VcpuLimitExceeded",
        ])

    def test_retryable_no_instance_launch_cleans_all_temporary_resources(self):
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
                "FAILURE_CLASSIFICATION=retryable-capacity\n"
                "FAILURE_DETAIL=vcpu-limit:VcpuLimitExceeded\n"
                "LAUNCH_ATTEMPTED=1\n"
                "INSTANCE_IDS=()\n"
                "INSTANCE_PROFILE_NAME=test-profile\n"
                "INSTANCE_ROLE_NAME=test-role\n"
                "BUCKET=test-bucket\n"
                "BUCKET_OWNED=1\n"
                "OBJECT_PREFIX=\n"
                "CAMPAIGN_MODE=baseline-shard\n"
                "CAMPAIGN_ID=baseline\n"
                "CAMPAIGN_PLATFORM=r8i\n"
                "CAMPAIGN_SHARD_ID=rebar-c\n"
                "CAMPAIGN_REPLICA_ID=1\n"
                "CAMPAIGN_HOST_EPOCH=10\n"
                "DOWNLOADED_RESULT_COUNT=0\n"
                "discover_campaign_instances() { return 0; }\n"
                "retry_cleanup_command() { return 0; }\n"
                "verify_aws_resource_absent() { return 0; }\n"
                f"{retryable_function}\n"
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
            self.assertEqual(cleanup["run_status"], "failed")

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
                "CAMPAIGN_MODE=baseline-shard\n"
                "CAMPAIGN_ID=baseline\n"
                "CAMPAIGN_PLATFORM=r8i\n"
                "CAMPAIGN_SHARD_ID=rebar-c\n"
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

    def test_preflight_failure_proves_no_resources_need_cleanup(self):
        source = AWS_RUNNER.read_text()
        cleanup_function = source[
            source.index("cleanup()\n{"):
            source.index("\n}\n\nabort_campaign()") + 2]
        with tempfile.TemporaryDirectory() as temporary_directory:
            session = Path(temporary_directory) / "session"
            session.mkdir()
            script = (
                f"SESSION_DIR={session}\n"
                "FAILURE_CLASSIFICATION=\n"
                "FAILURE_DETAIL=preflight\n"
                "LAUNCH_ATTEMPTED=0\n"
                "INSTANCE_IDS=()\n"
                "INSTANCE_PROFILE_NAME=\n"
                "INSTANCE_ROLE_NAME=\n"
                "BUCKET=\n"
                "BUCKET_OWNED=0\n"
                "OBJECT_PREFIX=\n"
                "CAMPAIGN_MODE=baseline-shard\n"
                "CAMPAIGN_ID=baseline\n"
                "CAMPAIGN_PLATFORM=r8i\n"
                "CAMPAIGN_SHARD_ID=rebar-c\n"
                "CAMPAIGN_REPLICA_ID=1\n"
                "CAMPAIGN_HOST_EPOCH=10\n"
                "DOWNLOADED_RESULT_COUNT=0\n"
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

    def test_semantic_evidence_uses_actual_surefire_report_directory(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertNotIn("-Dsurefire.reportsDirectory=", source)
        self.assertIn('default_semantic_report_dir="${ROOT}/target/surefire-reports"', source)
        self.assertIn('cp "${default_semantic_report_dir}"/TEST-*.xml "${semantic_report_dir}/"', source)

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

    def test_aws_wrappers_only_dispatch_baseline_shards(self):
        host_source = (SCRIPT.parents[1] / "aws" / "run-host.sh").read_text()
        campaign_source = (SCRIPT.parents[1] / "aws" / "run-campaign.sh").read_text()

        self.assertIn('RE2_BENCHMARK_MODE must be baseline-shard', host_source)
        self.assertIn('CAMPAIGN_MODE=${CAMPAIGN_MODE:-baseline-shard}', campaign_source)
        self.assertIn('"${CAMPAIGN_MODE}" != baseline-shard', campaign_source)
        self.assertNotIn("run_trino_final_line_protocol_diagnostic", host_source)
        self.assertNotIn("TRINO_FINAL_LINE_PROTOCOL_DIAGNOSTIC", campaign_source)

    def test_extended_rebar_manifest_check_is_concurrency_safe(self):
        generator = SCRIPT.parents[1] / "manifests" / "generate-extended-rebar.py"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            definitions = root / "rebar" / "benchmarks" / "definitions" / "suite"
            definitions.mkdir(parents=True)
            (definitions / "example.toml").write_text(
                "[[bench]]\nname = 'example'\nmodel = 'grep'\nengines = ['re2']\n")
            output = root / "rows.tsv"
            exclusions = root / "exclusions.tsv"
            command = [
                sys.executable,
                str(generator),
                str(root / "rebar"),
                str(output),
                "--exclusions-output",
                str(exclusions),
            ]
            subprocess.run(command, check=True, capture_output=True, text=True)

            processes = [
                subprocess.Popen(command + ["--check"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                for _ in range(16)
            ]
            scratch_files_during_checks = list(root.glob("*.generated"))
            results = [process.communicate() + (process.returncode,) for process in processes]

            self.assertEqual([result for result in results if result[2] != 0], [])
            self.assertEqual(scratch_files_during_checks, [])
            self.assertEqual(list(root.glob("*.generated")), [])

    def test_archived_rebar_revision_receipt_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".regulator-rebar-commit").write_text(
                f"{RUN_CAMPAIGN.PINNED_REBAR_COMMIT}\n")

            result = subprocess.run(
                [str(REBAR_REVISION), str(root)],
                capture_output=True,
                text=True,
                check=False)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), RUN_CAMPAIGN.PINNED_REBAR_COMMIT)

    def test_archived_rebar_revision_receipt_rejects_wrong_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".regulator-rebar-commit").write_text(f"{'0' * 40}\n")

            result = subprocess.run(
                [str(REBAR_REVISION), str(root)],
                capture_output=True,
                text=True,
                check=False)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Extended Rebar validation requires", result.stderr)

    def test_qualification_rebar_archive_contains_revision_receipt(self):
        source = AWS_RUNNER.read_text()

        self.assertIn(
            '--add-virtual-file="rebar-corpus/.regulator-rebar-commit:${commit}"',
            source)

    def test_rebar_preparation_accepts_archived_revision_receipt(self):
        source = (SCRIPT.parents[1] / "rebar" / "prepare.sh").read_text()

        self.assertEqual(source.count('"$manifest_directory/rebar-revision.sh" "$rebar_root"'), 2)
        self.assertNotIn('git -C "$rebar_root" rev-parse HEAD', source)

    def test_native_rebar_build_accepts_archived_revision_receipt(self):
        source = (SCRIPT.parents[1] / "rebar" / "build-native.sh").read_text()

        self.assertIn(
            '"$repo_root/tools/re2-benchmark/manifests/rebar-revision.sh" "$rebar_root"',
            source)
        self.assertNotIn('git -C "$rebar_root" status', source)

    def test_native_rebar_build_uses_pinned_private_abseil(self):
        source = (SCRIPT.parents[1] / "rebar" / "build-native.sh").read_text()
        self.assertIn(
            "readonly pinned_abseil_commit=d38452e1ee03523a208362186fd42248ff2609f6",
            source,
        )
        self.assertIn('git -C "$pinned_abseil_root" rev-parse HEAD', source)
        self.assertIn('-DCMAKE_CXX_FLAGS_RELEASE="$flags"', source)
        self.assertIn('CXXFLAGS="$flags -I$abseil_install_root/include"', source)
        self.assertIn(
            'PKG_CONFIG_PATH="$abseil_install_root/lib64/pkgconfig:'
            '$abseil_install_root/lib/pkgconfig"',
            source,
        )
        self.assertIn(
            'PKG_CONFIG_LIBDIR="$abseil_install_root/lib64/pkgconfig:'
            '$abseil_install_root/lib/pkgconfig"',
            source,
        )

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

    def test_smoke_stability_checks_use_qualification_measurement_windows(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertEqual(source.count("REBAR_MAXIMUM_TIME=5s"), 2)
        self.assertEqual(source.count("REBAR_WARMUP_TIME=5s"), 2)
        self.assertIn("CALIBRATION_WARMUP_ITERATIONS=3", source)
        self.assertIn("CALIBRATION_MEASUREMENT_ITERATIONS=3", source)
        self.assertIn("CALIBRATION_WARMUP_TIME=1s", source)
        self.assertIn("CALIBRATION_MEASUREMENT_TIME=1s", source)
        self.assertIn('-wi "${CALIBRATION_WARMUP_ITERATIONS}"', source)
        self.assertIn('-i "${CALIBRATION_MEASUREMENT_ITERATIONS}"', source)

    def test_calibration_uses_process_stable_workload(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertIn(
            "calibration_filter='^io\\.airlift\\.regulator\\.BenchmarkExpressionPlans\\.findLiteral$'",
            source)
        self.assertIn("calibration_parameters=(-p workload=KIB_LATE)", source)
        self.assertEqual(source.count('"${calibration_parameters[@]}"'), 3)
        self.assertNotIn("calibration-before.json\" -p textSize=32768", source)
        self.assertNotIn("calibration-after.json\" -p textSize=32768", source)

    def test_rebar_runner_uses_current_package_and_canonical_controls(self):
        runner = (SCRIPT.parents[1] / "rebar" / "run-regulator.sh").read_text()
        shard = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertIn("io.airlift.regulator.Dfa::search", runner)
        self.assertNotIn("io.airlift.slice.re2", runner)
        self.assertIn(
            "REBAR_PRIMARY_ENGINE_FILTER='^(?:re2/pinned-host-tuned-before|regulator/re2|"
            "re2/pinned-host-tuned-after)$'",
            shard)
        self.assertNotIn("re2/pinned-portable|regulator/re2", shard)

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

    def test_compiler_rows_use_extended_warmup(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()
        suites = (SCRIPT.parent / "jmh-suites.tsv").read_text()

        self.assertIn("EXTENDED_WARMUP_TIME=300ms", source)
        self.assertIn("EXTENDED_WARMUP_SECONDS=0.3", source)
        self.assertIn('$3 == "compile" || $3 == "public-compile"', source)
        self.assertIn('BenchmarkRe2Practical[.]compilePhase', source)
        self.assertIn('${suite}" == compile || "${suite}" == public-compile', source)
        self.assertIn(
            'JMH_PROCESS_WARMUP_TIME_OVERRIDE=${EXTENDED_WARMUP_TIME}',
            source)
        self.assertIn(
            'specialized_warmup_time=${EXTENDED_WARMUP_TIME}',
            source)
        self.assertIn(
            '"${benchmark}" == io.airlift.regulator.BenchmarkRe2Compile*',
            source)
        self.assertIn(
            'BenchmarkRe2Parse\\.searchPhoneRe2',
            source)
        self.assertIn(
            'local regular_filter="^(?!${extended_prefix})${filter#^}"',
            source)
        self.assertIn(
            'local extended_filter="^(?=${extended_prefix})${filter#^}"',
            source)
        self.assertIn(
            'regular_selection+=(--exclude-benchmark-prefix "${benchmark_prefix}")',
            source)
        self.assertIn(
            'extended_selection+=(--include-benchmark-prefix "${benchmark_prefix}")',
            source)
        self.assertIn(
            'compile-public\tpublic-compile\t^io\\.airlift\\.regulator\\.'
            'BenchmarkJavaRegexpPublicApi\\.compile$\t-\tregulator-native-access\t',
            suites)

    def test_nonforked_jmh_processes_install_jmh_compiler_hints(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertIn("prepare_jmh_host_compiler_arguments()", source)
        self.assertIn("io.airlift.regulator.BenchmarkCompilerHints", source)
        self.assertGreaterEqual(source.count('"${jmh_host_compiler_arguments[@]}"'), 3)

    def test_bounded_measurements_use_independent_jvms(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()

        self.assertEqual(source.count("-f 0"), 3)
        self.assertGreaterEqual(source.count('java "${jmh_process_jvm_arguments[@]}"'), 2)
        self.assertEqual(source.count('if [[ ${process_index} -eq 1 ]]'), 3)
        self.assertEqual(source.count('profiler_arguments=(-prof gc)'), 3)
        self.assertEqual(source.count('"${profiler_arguments[@]}"'), 2)
        self.assertIn('secondary_metric_arguments=(--allow-missing-secondary-metrics)', source)
        self.assertIn('jmh_process_jvm_arguments=("${route_jvm_arguments[@]}"', source)
        self.assertGreaterEqual(source.count('-jvmArgs "${jmh_fork_arguments}"'), 2)

    def test_replacement_allowance_is_bounded(self):
        final_attempt = RUN_CAMPAIGN.Job(
            self.platform, "traditional-search", 1, 118, 3, "on-demand")

        with self.assertRaisesRegex(RuntimeError, "exhausted"):
            RUN_CAMPAIGN.replacement_job(
                final_attempt, 119, "retryable-infrastructure", "unexpected")

    def test_retry_requires_verified_cleanup_and_aws_classification(self):
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
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
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
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
            self.platform, "traditional-search", 1, 10, 1, "spot")
        outstanding_job = RUN_CAMPAIGN.Job(
            self.platform, "traditional-capture", 1, 11, 1, "spot")
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
            self.assertIn("traditional-search", unresolved)
            self.assertIn("traditional-capture", unresolved)
            RUN_CAMPAIGN.validate_cleanup_manifest(result_root, outstanding_job)

    def test_unverified_cleanup_stops_sibling_jobs(self):
        failed_job = RUN_CAMPAIGN.Job(
            self.platform, "traditional-search", 1, 10, 1, "spot")
        sibling_job = RUN_CAMPAIGN.Job(
            self.platform, "traditional-capture", 1, 11, 1, "spot")
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
            self.platform, "traditional-search", 1, 10, 1, "spot")
        failed_job = RUN_CAMPAIGN.Job(
            self.platform, "traditional-capture", 1, 11, 1, "spot")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            receipt_path = root / "accepted-receipt.tsv"
            self.write_receipts(receipt_path, [self.receipt_for_job(accepted_job)])
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
                    patch.object(RUN_CAMPAIGN, "validate_receipt_candidate"), \
                    patch.object(RUN_CAMPAIGN, "validate_campaign"):
                with self.assertRaises(SystemExit):
                    RUN_CAMPAIGN.execute_jobs(
                        self.arguments(root, maximum_concurrent=2),
                        root,
                        result_root,
                        [accepted_job, failed_job])

            accepted = RUN_CAMPAIGN.read_accepted_sessions(result_root / "accepted-sessions.tsv")
            self.assertEqual([RUN_CAMPAIGN.receipt_logical_identity(row) for row in accepted], [
                accepted_job.logical_identity])

    def test_restart_skips_previously_accepted_job(self):
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            receipt_path = root / "accepted-receipt.tsv"
            receipt = self.receipt_for_job(job)
            self.write_receipts(receipt_path, [receipt])
            self.write_receipts(result_root / "accepted-sessions.tsv", [receipt])
            arguments = self.arguments(root)
            arguments.candidate_archive_sha256 = receipt["candidate_archive_sha256"]
            arguments.engine_tree = receipt["engine_tree"]
            arguments.heap_size = receipt["heap_size"]

            with patch.object(RUN_CAMPAIGN, "validate_job_artifacts", return_value=receipt_path), \
                    patch.object(RUN_CAMPAIGN, "check_output", return_value=receipt["candidate_commit"]), \
                    patch.object(RUN_CAMPAIGN, "validate_campaign"), \
                    patch.object(RUN_CAMPAIGN.subprocess, "Popen") as popen:
                RUN_CAMPAIGN.execute_jobs(
                    arguments, root, result_root, [job])

            popen.assert_not_called()
            self.assertEqual(
                RUN_CAMPAIGN.read_accepted_sessions(result_root / "accepted-sessions.tsv"),
                [receipt])

    def test_recovery_rejects_receipt_from_different_heap(self):
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            receipt = self.receipt_for_job(job)
            ledger = root / "accepted-sessions.tsv"
            self.write_receipts(ledger, [receipt])
            arguments = self.arguments(root)
            arguments.candidate_archive_sha256 = receipt["candidate_archive_sha256"]
            arguments.engine_tree = receipt["engine_tree"]
            arguments.heap_size = "2g"
            with patch.object(RUN_CAMPAIGN, "check_output", return_value=receipt["candidate_commit"]), \
                    patch.object(RUN_CAMPAIGN, "validate_job_artifacts", return_value=ledger):
                with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "heap_size"):
                    RUN_CAMPAIGN.recover_accepted_sessions(root, arguments, root, [job], ledger)

    def test_restart_waits_for_detached_wrapper_cleanup(self):
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
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
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
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
            job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 1, 1, "spot")
            with patch.dict(os.environ, {"BENCHMARK_HEAP_SIZE": ""}):
                self.assertEqual("8g", RUN_CAMPAIGN.campaign_heap_size(arguments))
            with patch.dict(os.environ, {"BENCHMARK_HEAP_SIZE": "2g"}):
                environment = RUN_CAMPAIGN.job_environment(job, arguments, root, root)
            self.assertEqual("8g", environment["BENCHMARK_HEAP_SIZE"])

    def test_starting_attempt_without_process_retries_on_fresh_epoch(self):
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
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
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
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
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
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
            self.platform, "traditional-search", 1, 1, 1, "spot")
        failed_job = RUN_CAMPAIGN.Job(
            self.platform, "traditional-capture", 1, 2, 1, "spot")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            attempts = result_root / "job-attempts.tsv"
            accepted_epoch = RUN_CAMPAIGN.job_result_root(
                result_root, RUN_CAMPAIGN.Job(
                    self.platform, "traditional-search", 1, 3, 2, "spot"))
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

    def test_unexpected_controller_error_stops_sibling_jobs(self):
        malformed_job = RUN_CAMPAIGN.Job(
            self.platform, "traditional-search", 1, 10, 1, "spot")
        sibling_job = RUN_CAMPAIGN.Job(
            self.platform, "traditional-capture", 1, 11, 1, "spot")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            malformed_receipt = root / "malformed.tsv"
            malformed_receipt.write_text("wrong\nvalue\n")
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
                with self.assertRaisesRegex(RuntimeError, "unexpected receipt schema"):
                    RUN_CAMPAIGN.execute_jobs(
                        self.arguments(root, maximum_concurrent=2),
                        root,
                        result_root,
                        [malformed_job, sibling_job])

            self.assertEqual(sibling_process.signals, [RUN_CAMPAIGN.signal.SIGTERM])
            self.assertEqual(sibling_process.wait_timeouts, [15 * 60])

    def test_attempt_ledger_failure_after_process_start_cleans_child(self):
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            result_root = root / "results"
            result_root.mkdir()
            process = FakeProcess(
                112,
                wait_action=lambda: self.write_cleanup_session(result_root, job))
            real_write_attempt = RUN_CAMPAIGN.write_attempt
            write_count = 0

            def fail_second_attempt_write(*arguments, **keywords):
                nonlocal write_count
                write_count += 1
                if write_count == 2:
                    raise OSError("simulated durable-ledger failure")
                return real_write_attempt(*arguments, **keywords)

            with patch.object(RUN_CAMPAIGN.subprocess, "Popen", return_value=process), \
                    patch.object(
                        RUN_CAMPAIGN,
                        "write_attempt",
                        side_effect=fail_second_attempt_write):
                with self.assertRaisesRegex(OSError, "simulated durable-ledger failure"):
                    RUN_CAMPAIGN.execute_jobs(
                        self.arguments(root), root, result_root, [job])

            self.assertEqual(process.signals, [RUN_CAMPAIGN.signal.SIGTERM])
            self.assertEqual(process.wait_timeouts, [15 * 60])

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
        job = RUN_CAMPAIGN.Job(self.platform, "traditional-search", 1, 10, 1, "spot")
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            ledger = root / "accepted-sessions.tsv"
            first_path = root / "first.tsv"
            second_path = root / "second.tsv"
            self.write_receipts(first_path, [self.receipt_for_job(job)])
            self.write_receipts(second_path, [{
                **self.receipt_for_job(job),
                "host_epoch": "11",
            }])

            RUN_CAMPAIGN.persist_accepted_receipt(ledger, first_path)
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "conflicting"):
                RUN_CAMPAIGN.persist_accepted_receipt(ledger, second_path)

    def test_on_demand_capacity_accounts_for_unrelated_work_and_local_reservations(self):
        running_job = RUN_CAMPAIGN.Job(
            self.platform, "traditional-search", 1, 10, 1, "on-demand")
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
            self.platform, "traditional-search", 1, 10, 1, "spot")
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
            self.platform, "traditional-search", 1, 10, 1, "spot")
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
            "traditional-search",
            1,
            100,
            RUN_CAMPAIGN.MAX_REPLACEMENTS_PER_JOB + 1,
            "on-demand")

        replacement = RUN_CAMPAIGN.replacement_job(
            job, 101, "retryable-capacity", "vcpu-limit:VcpuLimitExceeded")

        self.assertEqual(replacement.market, "on-demand")
        self.assertEqual(replacement.host_epoch, 101)
        self.assertEqual(replacement.attempt, job.attempt + 1)

    def test_on_demand_capacity_wait_queues_instead_of_aborting(self):
        job = RUN_CAMPAIGN.Job(
            self.platform, "traditional-search", 1, 10, 1, "on-demand")
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
            self.platform, "traditional-search", 1, 10, 1, "spot")
        on_demand_job = RUN_CAMPAIGN.Job(
            self.platform, "traditional-search", 1, 11, 2, "on-demand")
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
                    failure_detail="launch-capacity:InsufficientInstanceCapacity"))
            on_demand_process = FakeProcess(
                109,
                status=1,
                poll_action=lambda: self.write_cleanup_session(
                    result_root, on_demand_job, failure_classification="benchmark-failure"))

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
                    }):
                with self.assertRaises(SystemExit):
                    RUN_CAMPAIGN.execute_jobs(
                        self.arguments(root), root, result_root, [spot_job])

            markets = [call.kwargs["env"]["INSTANCE_MARKET_TYPE"] for call in popen.call_args_list]
            epochs = [call.kwargs["env"]["CAMPAIGN_HOST_EPOCH"] for call in popen.call_args_list]
            self.assertEqual(markets, ["spot", "on-demand"])
            self.assertEqual(epochs, ["10", "11"])


if __name__ == "__main__":
    unittest.main()
