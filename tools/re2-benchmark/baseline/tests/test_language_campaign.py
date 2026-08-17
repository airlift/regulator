import copy
import os
from pathlib import Path
import subprocess
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import test_run_campaign as baseline_tests

import campaign
import collection
import test_fleet


CONTROLLER = baseline_tests.RUN_CAMPAIGN
LANGUAGE_DIRECTORY = Path(collection.__file__).parent


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
            candidate_provenance=self.provenance, engine_tree="engine-tree",
            language_plan=[self.fixture.directory], smoke_results=None)
        self.platforms = CONTROLLER.read_platforms(baseline_tests.SCRIPT.parent)
        self.result_root = self.root / "controller-results"
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

    def execute(self, **environment):
        sleep = time.sleep
        with patch.dict(os.environ, {
                "PYTHONPATH": str(LANGUAGE_DIRECTORY), "PYTHONDONTWRITEBYTECODE": "1",
                "FAKE_RAW": str(self.raw), "FAKE_PINS": self.pins_hash, **environment}), \
                patch.object(CONTROLLER, "check_output", side_effect=lambda command, **kwargs:
                             "commit" if command[-1] == "HEAD" else "tree"), \
                patch.object(CONTROLLER.time, "sleep", side_effect=lambda _: sleep(0.02)):
            CONTROLLER.execute_jobs(self.arguments, self.root, self.result_root, self.jobs)

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
import os
from pathlib import Path
import shutil
import sys
import batch_acceptance
import collection
import transport

if os.environ.get("FAKE_MUST_NOT_RUN"):
    raise RuntimeError("accepted work was relaunched")
epoch = os.environ["CAMPAIGN_HOST_EPOCH"]
session = Path(os.environ["RESULT_ROOT"]) / "session"
session.mkdir(parents=True)
cleanup = "instances_terminated=verified\niam_removed=verified\nbucket_removed=verified\nnetwork_resources=default-vpc-reused\ncleanup_status=verified\n"
if epoch == os.environ.get("FAKE_INTERRUPT_EPOCH"):
    (session / "cleanup-manifest.txt").write_text(cleanup + "run_status=failed\nfailure_classification=retryable-spot\nfailure_detail=spot-interruption\n")
    sys.exit(1)
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
    "regulator_commit": "commit", "regulator_archive_sha256": "archive",
    "java_runtime_version": "25.0.4+7", "comparator_manifest_sha256": os.environ["FAKE_PINS"]}
(result / "environment-manifest.txt").write_text("".join(f"{key}={value}\n" for key, value in environment.items()))
collection.save(result / "language-acceptance.json", batch_acceptance.validate(result))
(result / "host-capacity.txt").write_text("memory_total_bytes=34359738368\nmemory_available_before_bytes=30000000000\nswap_total_bytes=0\nswap_free_before_bytes=0\noom_kills_before=0\nwall_seconds=1\nmaximum_resident_kibibytes=1048576\nexit_status=0\nmemory_available_after_bytes=29000000000\nminimum_available_memory_bytes=3435973837\nswap_free_after_bytes=0\noom_kills_after=0\ncapacity_status=accepted\n")
(session / "cleanup-manifest.txt").write_text(cleanup + "run_status=success\n")
(session / "campaign-success").write_text("complete\n")
'''


if __name__ == "__main__":
    unittest.main()
