import contextlib
import copy
import csv
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
import warnings
from unittest.mock import patch

import test_run_campaign as baseline_tests
import test_fleet_budget

import campaign
import collection
import test_fleet


CONTROLLER = baseline_tests.RUN_CAMPAIGN
LANGUAGE_DIRECTORY = Path(collection.__file__).parent
SLEEP = time.sleep
CHECK_OUTPUT = CONTROLLER.check_output
EXECUTE_JOBS = CONTROLLER.execute_jobs


class ControllerKilled(BaseException):
    """Stands in for a SIGKILL, which skips the controller's cleanup of running wrappers."""


class TestLanguageController(unittest.TestCase):
    def setUp(self):
        self.fixture = test_fleet.TestFleet()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.plan = self.fixture.prepare_small_plan(replicas=3)
        self.raw = self.fixture.write_results(self.plan)
        pins = self.root / "tools/re2-benchmark/baseline/comparators.tsv"
        pins.parent.mkdir(parents=True)
        pins.write_text("test comparator pins\n")
        self.pins_hash = collection.digest(pins.read_bytes())
        self.provenance = self.root / "candidate.tsv"
        self.provenance.write_text("frozen candidate fixture\n")
        self.arguments = SimpleNamespace(
            phase="smoke", campaign_id="language-test", max_concurrent=4, heap_size="8g",
            candidate_archive=self.root / "candidate.tar.gz", candidate_archive_sha256="archive",
            candidate_ref="refs/candidate", candidate_provenance=self.provenance, engine_tree="engine-tree",
            language_plan=[self.fixture.directory], smoke_results=None)
        self.platforms = CONTROLLER.read_platforms(baseline_tests.SCRIPT.parent)
        self.result_root = self.root / "controller-results"
        self.head = "commit"
        self.prepare_phase("smoke")
        wrapper = self.root / "tools/re2-benchmark/aws/run-campaign.sh"
        wrapper.parent.mkdir(parents=True)
        wrapper.write_text("#!/usr/bin/env python3\n" + FAKE_WRAPPER)
        wrapper.chmod(0o755)

    def prepare_phase(self, phase):
        self.arguments.phase = phase
        self.arguments.language_campaign = campaign.LanguageCampaign([self.fixture.directory], phase)
        self.jobs = CONTROLLER.build_language_jobs(self.arguments.language_campaign, self.platforms)
        self.result_root.mkdir(exist_ok=True)
        CONTROLLER.write_jobs(self.result_root / "jobs.tsv", self.jobs)

    def execute(self, arguments=None, jobs=None, *, sleep=None, **environment):
        def check_output(command, **kwargs):
            if command[0] == "ps":
                return CHECK_OUTPUT(command, **kwargs)
            return {"HEAD": self.head, "refs/candidate^{commit}": "commit"}.get(command[-1], "tree")

        with patch.dict(os.environ, {
                "PYTHONPATH": str(LANGUAGE_DIRECTORY), "PYTHONDONTWRITEBYTECODE": "1",
                "FAKE_RAW": str(self.raw), "FAKE_PINS": self.pins_hash, **environment}), \
                patch.object(CONTROLLER, "check_output", side_effect=check_output), \
                patch.object(CONTROLLER.time, "sleep", side_effect=sleep or (lambda _: SLEEP(0.02))):
            EXECUTE_JOBS(arguments or self.arguments, self.root, self.result_root, jobs or self.jobs)

    def refill(self, *options, sleep=None, **environment):
        """Run the controller's entry point with the operator's refill flags, parsed afresh."""
        def validate_source(_, arguments):
            arguments.candidate_archive_sha256, arguments.engine_tree = "archive", "engine-tree"

        argv = ["run-campaign.py", "primary", self.arguments.campaign_id, "--execute", "--refill-unresolved",
                "--max-concurrent", "1", "--result-root", str(self.result_root),
                "--language-plan", str(self.fixture.directory), "--candidate-ref", "refs/candidate",
                "--candidate-archive", str(self.arguments.candidate_archive),
                "--candidate-provenance", str(self.provenance), *options]
        with patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()), \
                patch.object(CONTROLLER, "validate_source", side_effect=validate_source), \
                patch.object(CONTROLLER, "validate_dependencies"), \
                patch.object(CONTROLLER, "validate_prerequisites"), \
                patch.object(CONTROLLER, "validate_aws"), \
                patch.object(CONTROLLER, "execute_jobs", side_effect=lambda arguments, _, result_root, jobs:
                             self.execute(arguments, jobs, sleep=sleep, **environment)):
            CONTROLLER.main()

    def exhaust_spot(self, pattern):
        """Run a Spot-only primary campaign whose logical jobs matching the pattern exhaust Spot."""
        self.result_root = Path(tempfile.mkdtemp(dir=self.root))
        self.prepare_phase("primary")
        self.arguments.spot_only, self.arguments.spot_vcpu_reserve = True, 0
        self.head = "commit"
        with patch.object(CONTROLLER, "current_spot_capacity", return_value={"available": 64}), \
                self.assertRaisesRegex(SystemExit, "exhausted its bounded replacement allowance"):
            self.execute(FAKE_SPOT_EXHAUSTED=pattern)
        # The refill's controller is newer than the candidate the hosts measure.
        self.head = "refill-controller"

    def test_real_controller_accepts_and_restart_rechecks_raw_evidence(self):
        self.execute()
        ledger = self.result_root / "accepted-language-sessions.json"
        accepted = CONTROLLER.read_accepted_sessions(ledger)
        self.assertEqual(len(accepted), 9)
        attempts = (self.result_root / "job-attempts.tsv").read_bytes()
        self.execute(FAKE_MUST_NOT_RUN="1")
        self.assertEqual((self.result_root / "job-attempts.tsv").read_bytes(), attempts)
        self.assertEqual(CONTROLLER.read_accepted_sessions(ledger), accepted)
        raw = next((self.result_root / "jobs").rglob("raw.json"))
        raw.write_text("[]\n")
        with self.assertRaisesRegex(RuntimeError, "invalid language evidence"):
            self.execute(FAKE_MUST_NOT_RUN="1")

    def test_shared_budget_releases_only_completed_and_cleaned_attempts(self):
        fixture = test_fleet_budget.TestFleetBudget()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        policy = fixture.policy
        policy["hourly_rates"] = {family + ".large": {"spot": 0.065, "total": 0.075}
                                  for family in ("r8i", "r8g", "r9g")}
        policy["hourly_rates"]["r9g.2xlarge"] = {"spot": 0.26, "total": 0.30}
        fixture.path.write_text(collection.encode(policy).decode())
        self.arguments.shared_budget = CONTROLLER.FleetBudget(fixture.path)
        self.arguments.spot_only, self.arguments.spot_vcpu_reserve = True, 0
        with patch.object(CONTROLLER, "shared_spot_snapshot", return_value={"quota": 16, "instances": [], "requests": []}), \
                patch.object(CONTROLLER, "CAPACITY_RETRY_SECONDS", 0.01):
            self.execute(FAKE_INTERRUPT_EPOCH="1")
        state = collection.load(self.arguments.shared_budget.state_path)
        self.assertEqual(state["reservations"], {})
        self.assertEqual(len(state["completed"]), 10)
        self.assertGreater(state["machine_hours"], 0)
        self.assertEqual({item["instance_type"] for item in state["completed"].values()},
                         {"r8i.large", "r8g.large", "r9g.2xlarge"})

    def test_restart_rejects_changed_candidate_plan_and_deadline(self):
        self.execute()
        original = self.arguments.candidate_archive_sha256
        self.arguments.candidate_archive_sha256 = "changed"
        with self.assertRaisesRegex(RuntimeError, "changed on restart"):
            self.execute(FAKE_MUST_NOT_RUN="1")
        self.arguments.candidate_archive_sha256 = original
        self.arguments.max_concurrent = 3
        with self.assertRaisesRegex(RuntimeError, "changed on restart"):
            self.execute(FAKE_MUST_NOT_RUN="1")
        self.arguments.max_concurrent = 4
        changed = copy.deepcopy(self.plan)
        changed["host_batches"][0]["jobs"].pop()
        collection.save(self.fixture.directory / "plan.json", changed)
        with self.assertRaisesRegex(ValueError, "plan changed"):
            self.execute(FAKE_MUST_NOT_RUN="1")

    def test_interrupted_batch_retries_whole_batch_on_fresh_host(self):
        self.execute(FAKE_INTERRUPT_EPOCH="1")
        accepted = CONTROLLER.read_accepted_sessions(self.result_root / "accepted-language-sessions.json")
        first = next(row for row in accepted if CONTROLLER.receipt_logical_identity(row) == self.jobs[0].logical_identity)
        self.assertGreater(int(first["host_epoch"]), len(self.jobs))
        self.assertEqual(set(first["exports"]), set(self.arguments.language_campaign.expected_package(
            self.jobs[0].logical_identity)["batch"]["jobs"]))
        self.assertIn("retryable-spot", (self.result_root / "job-attempts.tsv").read_text())

    def test_primary_aggregation_contains_all_logical_jobs(self):
        self.result_root = self.root / "primary-results"
        self.prepare_phase("primary")
        self.execute()
        # Suite spelling is owned by the input manifest, not the report layout.
        suite = next(iter(self.arguments.language_campaign.plans))
        output = self.result_root / (suite + "-results.json")
        data = collection.load(output)
        self.assertEqual(len(data["hosts"]), len(self.plan["jobs"]))
        self.assertEqual(len(data["observations"]), len(self.plan["jobs"]) * 15)
        before = output.read_bytes()
        self.execute(FAKE_MUST_NOT_RUN="1")
        self.assertEqual(output.read_bytes(), before)

    def test_refill_finishes_spot_exhausted_platform_on_demand_in_same_root(self):
        self.exhaust_spot("r8i/*/replica-[23]")
        policy = (self.result_root / "execution-policy.json").read_bytes()
        self.arguments.spot_only = False
        with self.assertRaisesRegex(RuntimeError, "changed on restart"):
            self.execute(FAKE_MUST_NOT_RUN="1")

        # The controller is killed while the second refill attempt runs, after the first was accepted.
        held = self.root / "held"
        self.addCleanup(held.unlink, missing_ok=True)

        def kill_controller_once_held(_):
            if held.exists():
                raise ControllerKilled()
            SLEEP(0.02)

        def release_held(_):
            held.unlink(missing_ok=True)
            SLEEP(0.02)

        # Let the refill's deadline follow its job count.
        with patch.dict(CONTROLLER.PHASE_TIMEOUT_SECONDS, primary=0), \
                patch.object(CONTROLLER, "current_on_demand_capacity", return_value={"available": 64}):
            # The killed controller leaves its wrapper process and log behind.
            with warnings.catch_warnings(), self.assertRaises(ControllerKilled):
                warnings.simplefilter("ignore", ResourceWarning)
                self.refill(sleep=kill_controller_once_held, FAKE_HOLD_SECOND_ON_DEMAND=str(held))
            # An identical restart keeps the deadline, waits for the detached attempt, and passes
            # that attempt's grant on after the interrupt aborts it.
            self.assertTrue(held.exists())
            self.refill(sleep=release_held)
            self.refill(FAKE_MUST_NOT_RUN="1")

        suite = next(iter(self.arguments.language_campaign.plans))
        hosts = collection.load(self.result_root / (suite + "-results.json"))["hosts"]
        self.assertEqual(len(hosts), len(self.plan["jobs"]))
        self.assertEqual((self.result_root / "execution-policy.json").read_bytes(), policy)
        refill_policy = collection.load(self.result_root / "refill-execution-policy.json")
        self.assertEqual((refill_policy["spot_only"], refill_policy["max_concurrent"]), (False, 1))
        with (self.result_root / "job-attempts.tsv").open(newline="") as attempts:
            refills = [row for row in csv.DictReader(attempts, delimiter="\t")
                       if row["outcome"] == "accepted" and row["market"] == "on-demand"]
        refilled = sum(job.platform.name == "r8i" and job.replica != 1 for job in self.jobs)
        self.assertEqual(sorted((row["platform"], row["attempt"]) for row in refills),
                         [("r8i", "4")] * (refilled - 1) + [("r8i", "5")])
        self.assertEqual(len(CONTROLLER.read_refill_grants(self.result_root)), refilled + 1)

    def test_refill_rejects_host_environment_unlike_the_campaign(self):
        self.exhaust_spot("r8i/*/replica-[23]")
        with patch.object(CONTROLLER, "current_on_demand_capacity", return_value={"available": 64}), \
                self.assertRaisesRegex(RuntimeError, r"differs from the campaign's: \['trino_commit'\]"):
            self.refill(FAKE_TRINO_COMMIT="newer-trino")
        # An ordinary restart must not recover the receipt the refill rejected.
        self.head = "commit"
        with self.assertRaisesRegex(RuntimeError, r"differs from the campaign's: \['trino_commit'\]"):
            self.execute(FAKE_MUST_NOT_RUN="1")

    def test_released_refill_checks_the_candidate_source_not_the_checkout(self):
        self.exhaust_spot("r8i/*/replica-[23]")
        manifest = Path("tools/re2-benchmark/baseline/releases/1.0.json")
        (self.root / manifest).parent.mkdir()
        (self.root / manifest).write_bytes((baseline_tests.SCRIPT.parents[3] / manifest).read_bytes())
        release = CONTROLLER.released_artifact.manifest(self.root, "1.0")
        # The original campaign measured source, so a refill the source check admits stops at
        # the original receipts.
        for candidate_tree, error in ((release["engine_tree"], "does not identify the selected release"),
                                      ("newer-tree", "production source differs from the selected release")):
            with self.subTest(candidate_tree), \
                    patch.object(CONTROLLER.released_artifact, "production_tree", return_value="checkout-tree"), \
                    patch.object(CONTROLLER, "check_output", return_value=candidate_tree), \
                    self.assertRaisesRegex((RuntimeError, SystemExit), error):
                self.refill("--release-version", "1.0", FAKE_MUST_NOT_RUN="1")

    def test_refill_without_original_host_environment_launches_nothing(self):
        # A refilled host's platform- and shard-chosen environment can only match an original's.
        for exhausted in ("*", "*/language-lifecycle-0001/*"):
            with self.subTest(exhausted):
                self.exhaust_spot(exhausted)
                with self.assertRaisesRegex(RuntimeError, "no accepted original host environment"):
                    self.refill(FAKE_MUST_NOT_RUN="1")

    def test_missing_batch_and_reused_host_cannot_qualify(self):
        self.execute()
        accepted = {CONTROLLER.receipt_logical_identity(row): row for row in CONTROLLER.read_accepted_sessions(
            self.result_root / "accepted-language-sessions.json")}
        incomplete = dict(accepted)
        incomplete.pop(next(iter(incomplete)))
        with self.assertRaisesRegex(RuntimeError, "missing"):
            CONTROLLER.validate_language_coverage(self.arguments, self.jobs, incomplete)
        for receipt in accepted.values():
            receipt["instance_id"] = "i-reused"
        with self.assertRaisesRegex(RuntimeError, "independent hosts"):
            CONTROLLER.validate_language_coverage(self.arguments, self.jobs, accepted)

    def test_generated_language_user_data_reaches_checksummed_worker_inputs(self):
        source = baseline_tests.AWS_RUNNER.read_text()
        function = source[source.index("create_user_data()\n{"):source.index("\n}\n\nlaunch_instance()") + 2]
        output = self.root / "user-data.sh"
        script = (function + "\nCAMPAIGN_MODE=language-batch\nLANGUAGE_BATCH_ARCHIVE_SHA256=batch-hash\n"
                  "LANGUAGE_CANDIDATE_PROVENANCE_SHA256=provenance-hash\n"
                  f"create_user_data arm aarch64 result.tar.gz {output} ami url sha\n")
        subprocess.run(["bash"], input=script, text=True, check=True)
        subprocess.run(["bash", "-n", str(output)], check=True)
        generated = output.read_text()
        self.assertIn("--sha256 'batch-hash'", generated)
        self.assertIn("'provenance-hash' /tmp/candidate-provenance.tsv | sha256sum --check", generated)
        self.assertLess(generated.index("export PYTHONDONTWRITEBYTECODE=1"), generated.index("python3"))
        self.assertIn('export LANGUAGE_SOURCE_ARCHIVE=/tmp/regulator.tar.gz', generated)
        self.assertIn('/tools/re2-benchmark/aws/run-host.sh', generated)


# This process replaces only the AWS wrapper. It returns explicit synthetic raw
# samples, then uses the real input transport, exporter, and acceptance validator.
FAKE_WRAPPER = r'''
import fnmatch
import os
from pathlib import Path
import shutil
import sys
import time
import batch_acceptance
import collection
import transport

if os.environ.get("FAKE_MUST_NOT_RUN"):
    raise RuntimeError("accepted work was relaunched")
epoch = os.environ["CAMPAIGN_HOST_EPOCH"]
session = Path(os.environ["RESULT_ROOT"]) / "session"
session.mkdir(parents=True)
cleanup = "instances_terminated=verified\niam_removed=verified\nbucket_removed=verified\nnetwork_resources=default-vpc-reused\ncleanup_status=verified\n"
logical_job = "/".join((os.environ["CAMPAIGN_PLATFORM"], os.environ["CAMPAIGN_SHARD_ID"],
                        "replica-" + os.environ["CAMPAIGN_REPLICA_ID"]))
if epoch == os.environ.get("FAKE_INTERRUPT_EPOCH") or (
        os.environ["INSTANCE_MARKET_TYPE"] == "spot" and
        fnmatch.fnmatch(logical_job, os.environ.get("FAKE_SPOT_EXHAUSTED", "-"))):
    (session / "cleanup-manifest.txt").write_text(cleanup + "run_status=failed\nfailure_classification=retryable-spot\nfailure_detail=spot-interruption\n")
    sys.exit(1)
held = os.environ.get("FAKE_HOLD_SECOND_ON_DEMAND")
if held and os.environ["INSTANCE_MARKET_TYPE"] == "on-demand":
    if Path(held + ".first").exists():
        # Run until released, then record the abort the operator's interrupt causes.
        Path(held).touch()
        while Path(held).exists():
            time.sleep(0.02)
        (session / "cleanup-manifest.txt").write_text(cleanup + "run_status=failed\nfailure_classification=controller-abort\nfailure_detail=controller-interrupt\n")
        sys.exit(1)
    Path(held + ".first").touch()
result = session / os.environ["CAMPAIGN_ARCHITECTURES"] / "re2-results"
package = transport.unpack(Path(os.environ["LANGUAGE_BATCH_ARCHIVE"]), result / "language-inputs",
    os.environ["LANGUAGE_BATCH_ARCHIVE_SHA256"], os.environ["CAMPAIGN_PLATFORM"],
    os.environ["CAMPAIGN_SHARD_ID"], int(os.environ["CAMPAIGN_REPLICA_ID"]))
worker = result / "language-worker"
instance_type = os.environ["INTEL_INSTANCE_TYPE"] if os.environ["CAMPAIGN_ARCHITECTURES"] == "intel" else os.environ["ARM_INSTANCE_TYPE"]
for entry in package["packages"]:
    job = entry["receipt"]["job"]
    destination = worker / "results" / job["id"]
    shutil.copytree(Path(os.environ["FAKE_RAW"]) / job["id"], destination)
    provenance = collection.load(destination / "provenance.json")
    provenance.update(jdk="Temurin 25.0.4+7", comparators_sha256=os.environ["FAKE_PINS"],
        instance_identity={"instanceId": "i-" + epoch, "instanceType": instance_type})
    collection.save(destination / "provenance.json", provenance)
    collection.export(result / "language-inputs" / entry["directory"], destination)
collection.save(worker / "worker.json", {"kind": "qualification", "package": package})
collection.save(worker / "cpu-affinity.json", [0])
environment = {
    "verification_status": "verified", "benchmark_mode": "language-batch",
    "platform": os.environ["CAMPAIGN_PLATFORM"], "shard": os.environ["CAMPAIGN_SHARD_ID"],
    "replica": os.environ["CAMPAIGN_REPLICA_ID"], "host_epoch": epoch,
    "baseline_campaign": os.environ["CAMPAIGN_ID"], "campaign_architecture": os.environ["CAMPAIGN_ARCHITECTURES"],
    "benchmark_heap_size": "8g", "instance_id": "i-" + epoch, "instance_type": instance_type,
    "logical_cpu_count": os.environ["BENCHMARK_EXPECTED_VCPUS"],
    "regulator_commit": "commit", "regulator_archive_sha256": "archive",
    "java_runtime_version": "25.0.4+7", "comparator_manifest_sha256": os.environ["FAKE_PINS"],
    "trino_used": "false", "trino_commit": os.environ.get("FAKE_TRINO_COMMIT", "none")}
(result / "environment-manifest.txt").write_text("".join(f"{key}={value}\n" for key, value in environment.items()))
collection.save(result / "language-acceptance.json", batch_acceptance.validate(result))
(result / "host-capacity.txt").write_text("memory_total_bytes=34359738368\nmemory_available_before_bytes=30000000000\nswap_total_bytes=0\nswap_free_before_bytes=0\noom_kills_before=0\nwall_seconds=1\nmaximum_resident_kibibytes=1048576\nexit_status=0\nmemory_available_after_bytes=29000000000\nminimum_available_memory_bytes=3435973837\nswap_free_after_bytes=0\noom_kills_after=0\ncapacity_status=accepted\n")
(session / "cleanup-manifest.txt").write_text(cleanup + "run_status=success\n")
(session / "campaign-success").write_text("complete\n")
'''


if __name__ == "__main__":
    unittest.main()
