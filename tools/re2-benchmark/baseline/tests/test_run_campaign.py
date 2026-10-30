import base64
import collections
import contextlib
import csv
import fnmatch
import gzip
import hashlib
import importlib.util
import io
import json
import math
import os
import random
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).parents[1] / "run-campaign.py"
sys.path.insert(0, str(SCRIPT.parent))
import cloud  # noqa: E402
import test_engine_build  # noqa: E402
import test_fleet_budget  # noqa: E402
import unit_dispatch  # noqa: E402
from shard_results import write_tsv  # noqa: E402
from test_cloud import AMAZON_LINUX, FakeCloud  # noqa: E402
from test_unit_session import UnitCampaign, UnitFixture  # noqa: E402
SPEC = importlib.util.spec_from_file_location("baseline_run_campaign", SCRIPT)
RUN_CAMPAIGN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN_CAMPAIGN)
# run-campaign.py puts the language tooling on the path; its campaign fixture runs attempts as workers do.
import test_fleet  # noqa: E402


def shell_function(source, name):
    """Returns one function definition from a shell script's source."""
    start = source.index(f"\n{name}()\n") + 1
    return source[start:source.index("\n}\n", start) + 3]


def shell_functions(source, *names):
    return "".join(shell_function(source, name) for name in names)


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
            "time",
            "zstd")

    def arguments(self, root, maximum_concurrent=1):
        return SimpleNamespace(
            phase="smoke",
            campaign_id="baseline",
            campaign_root=root / "campaign",
            max_concurrent=maximum_concurrent,
            candidate_archive=root / "candidate.tar.gz",
            candidate_archive_sha256="0" * 64,
            engine_tree="d" * 40,
            transfer_buckets={region: "bucket" for region in RUN_CAMPAIGN.default_regions()})

    def test_platform_limit_applies_independently_of_global_capacity(self):
        arguments = SimpleNamespace(max_concurrent=9, max_concurrent_per_platform=3)
        job = SimpleNamespace(platform=SimpleNamespace(name='r9g'))
        other = SimpleNamespace(platform=SimpleNamespace(name='r8i'))
        running = [job] * 3
        self.assertFalse(RUN_CAMPAIGN.platform_has_capacity(job, running, arguments))
        self.assertTrue(RUN_CAMPAIGN.platform_has_capacity(other, running, arguments))
        running.pop()
        self.assertTrue(RUN_CAMPAIGN.platform_has_capacity(job, running, arguments))

    def test_reserve_is_frozen_on_resume_but_launch_regions_and_purchase_are_not(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            arguments = SimpleNamespace(platform_regions={"r8i": ["eu-west-1", "ap-south-1"]},
                                        purchases={"r8i": "spot"}, spot_vcpu_reserve=0)
            RUN_CAMPAIGN.validate_execution_policy(arguments, root)
            # A restart may move to other regions or buy differently; each result records its region and market.
            for change in ({"purchases": {}}, {"platform_regions": {"r8i": ["ap-south-1", "eu-west-1"]}}):
                RUN_CAMPAIGN.validate_execution_policy(SimpleNamespace(**{**vars(arguments), **change}), root)
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "changed on restart"):
                RUN_CAMPAIGN.validate_execution_policy(SimpleNamespace(**{**vars(arguments), "spot_vcpu_reserve": 32}),
                                                       root)
            arguments.smoke_protocol = 'qualification'
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "changed on restart"):
                RUN_CAMPAIGN.validate_execution_policy(arguments, root)

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
            RUN_CAMPAIGN.resolve_engine_builds(SCRIPT.parents[3], arguments)
        arguments = SimpleNamespace(phase="build", engine_builds=Path("builds"))
        with self.assertRaisesRegex(SystemExit, "apply only to measuring phases"):
            RUN_CAMPAIGN.resolve_engine_builds(SCRIPT.parents[3], arguments)

    def test_measuring_campaign_installs_builds_of_its_candidate_and_build_inputs(self):
        # The build phase ran under its own campaign ID and heap; any measuring campaign whose checkout
        # makes the same engines may install them.
        checkout = SCRIPT.parents[3]
        candidate = RUN_CAMPAIGN.check_output(["git", "rev-parse", "HEAD"], cwd=checkout)
        pin = RUN_CAMPAIGN.engine_build.properties(
            checkout / "tools/re2-benchmark/trino-joni/pins.env")["TRINO_COMPARATOR_TRINO_COMMIT"]

        def other_scripts(root):
            scripts = root / "other-checkout"
            for path in RUN_CAMPAIGN.engine_build.BUILD_INPUTS:
                (scripts / path).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(checkout / path, scripts / path)
            with (scripts / "tools/re2-benchmark/build.sh").open("a") as script:
                script.write("# changed\n")
            return {"scripts": scripts}

        def rewrite_state(**changes):
            def rewrite(builds):
                path = builds / "campaign-state.tsv"
                state = {**RUN_CAMPAIGN.read_campaign_state(path), **changes}
                write_tsv(path, RUN_CAMPAIGN.CAMPAIGN_STATE_FIELDS, [state])
            return rewrite

        def reject(builds):
            receipt = RUN_CAMPAIGN.read_accepted_units(builds / "accepted-units.tsv")[0]
            item = SimpleNamespace(platform=receipt["platform"], unit_id=receipt["unit_id"], replica=1)
            RUN_CAMPAIGN.persist_semantic_rejection(builds / "semantic-rejections.tsv", item, "session-r8i/2")

        for name, build, change, error in (
                ("accepted", {}, None, None),
                ("other candidate", {"candidate": "f" * 40}, None, "candidate_commit"),
                ("other build scripts", other_scripts, None, "row_protocol_sha256 differs"),
                ("other Trino revision", {"trino_commit": "0" * 40}, None, "is not the checkout's pin"),
                ("no campaign state", {}, lambda builds: (builds / "campaign-state.tsv").unlink(),
                 "campaign-state.tsv"),
                ("campaign state of another campaign", {}, rewrite_state(campaign_id="other-build"), "other-build"),
                ("campaign state of a measuring phase", {}, rewrite_state(phase="smoke"), "not a build phase"),
                ("semantic rejection", {}, reject, "semantic failure")):
            with self.subTest(name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                builds = root / "builds"
                options = build(root) if callable(build) else build
                receipts = []
                for platform in test_engine_build.PLATFORMS:
                    package = {"candidate": candidate, "trino_commit": pin, **options}
                    _, receipt = test_engine_build.TestEngineBuild().package(root, platform, **package)
                    directory = RUN_CAMPAIGN.unit_attempt_directory(builds, receipt)
                    directory.parent.mkdir(parents=True)
                    (root / platform[0]).rename(directory)
                    RUN_CAMPAIGN.persist_accepted_unit(builds / "accepted-units.tsv", receipt)
                    receipts.append(receipt)
                builder = SimpleNamespace(phase="build", campaign_id="baseline", heap_size="8g")
                RUN_CAMPAIGN.campaign_started_at(builds / "campaign-state.tsv", builder)
                RUN_CAMPAIGN.write_ledger(builds / "semantic-rejections.tsv", [],
                                          RUN_CAMPAIGN.unit_session.SEMANTIC_REJECTION_FIELDS)
                RUN_CAMPAIGN.write_phase_complete(builds, builder)
                if change:
                    change(builds)
                arguments = SimpleNamespace(
                    phase="smoke", campaign_id="language", heap_size="16g", engine_builds=builds,
                    candidate_commit=candidate, candidate_archive_sha256="5" * 64)
                if error is None:
                    RUN_CAMPAIGN.resolve_engine_builds(checkout, arguments)
                    self.assertEqual({receipt["platform"]: receipt["observed_rows_sha256"] for receipt in receipts},
                                     arguments.engine_build_policy)
                else:
                    with self.assertRaisesRegex(SystemExit, "engine builds are not accepted: .*" + error):
                        RUN_CAMPAIGN.resolve_engine_builds(checkout, arguments)

    def engine_build(self, directory):
        """Write a platform's three engine archives, each holding its own name, and their engine build manifest;
        returns the archives' metadata and the manifest's SHA-256."""
        archives = {}
        for name in RUN_CAMPAIGN.engine_build.ARCHIVES:
            (directory / f"{name}.tar.zst").write_bytes(name.encode())
            archives[name] = {"file": f"{name}.tar.zst", "sha256": hashlib.sha256(name.encode()).hexdigest()}
        (directory / "engine-build.json").write_text(json.dumps({"archives": archives}))
        return archives, hashlib.sha256((directory / "engine-build.json").read_bytes()).hexdigest()

    def test_engine_archives_upload_once_per_region(self):
        with tempfile.TemporaryDirectory() as temporary:
            build = Path(temporary)
            archives, manifest = self.engine_build(build)
            trino, classes, native = (archives[name]["sha256"] for name in ("trino", "classes", "native"))
            arguments = SimpleNamespace(
                platform_regions={"r8i": ["us-west-2", "us-east-2"]},
                transfer_buckets={"us-west-2": "west", "us-east-2": "east"},
                engine_build_directories={"r8i": build}, engine_build_policy={"r8i": manifest})
            jobs = [RUN_CAMPAIGN.unit_worker_job(self.platform, ordinal, 10, "spot") for ordinal in range(1, 5)]
            for stalls, expected_puts in ((1, 4), (RUN_CAMPAIGN.SHARED_ARCHIVE_UPLOAD_ATTEMPTS, None)):
                with self.subTest(stalls=stalls):
                    # Ohio already holds every archive.
                    aws = arguments.cloud = FakeCloud()
                    for name in RUN_CAMPAIGN.engine_build.ARCHIVES:
                        aws.put("us-east-2", "east", f"prefix/engine-builds/{archives[name]['sha256']}.tar.zst",
                                name.encode(), sha256=archives[name]["sha256"])
                    puts = []

                    def put(region, bucket, key, body, **kwargs):
                        puts.append((bucket, key))
                        if len(puts) <= stalls:
                            raise RUN_CAMPAIGN.CloudError("RequestTimeout")
                        return FakeCloud.put(aws, region, bucket, key, body, **kwargs)

                    with patch.dict(os.environ, {"TRANSFER_PREFIX": "prefix"}), \
                            patch.object(aws, "put", side_effect=put), contextlib.redirect_stdout(io.StringIO()):
                        if expected_puts is None:
                            with self.assertRaisesRegex(SystemExit, "could not upload the r8i (classes|trino|native) "
                                                                    "archive to s3://west/"):
                                RUN_CAMPAIGN.upload_engine_builds(arguments, jobs)
                            continue
                        RUN_CAMPAIGN.upload_engine_builds(arguments, jobs)
                    # Every region but Ohio gets one copy of each archive a baseline unit runs, and the stalled first
                    # upload is retried.
                    self.assertEqual(sorted([("west", f"prefix/engine-builds/{trino}.tar.zst"),
                                             ("west", f"prefix/engine-builds/{classes}.tar.zst"),
                                             ("west", f"prefix/engine-builds/{native}.tar.zst")]), sorted(set(puts)))
                    self.assertEqual(expected_puts, len(puts))
                    self.assertEqual({(bucket, key): sha256 for (bucket, key), (_, _, sha256) in aws.objects.items()
                                      if bucket == "west"},
                                     {(bucket, key): key.split("/")[-1].split(".")[0] for bucket, key in puts})

    def test_candidate_archive_is_shared_by_content_once_per_region(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "candidate.tar.gz"
            archive.write_bytes(b"candidate")
            sha256 = hashlib.sha256(b"candidate").hexdigest()
            aws = FakeCloud()
            arguments = SimpleNamespace(
                platform_regions={"r8i": ["us-west-2", "us-east-2"]},
                transfer_buckets={"us-west-2": "west", "us-east-2": "east"},
                candidate_archive=archive, candidate_archive_sha256=sha256, cloud=aws)
            jobs = [RUN_CAMPAIGN.unit_worker_job(self.platform, ordinal, 10, "spot") for ordinal in range(1, 5)]
            with patch.dict(os.environ, {"TRANSFER_PREFIX": "prefix"}), contextlib.redirect_stdout(io.StringIO()):
                RUN_CAMPAIGN.upload_candidate_archive(arguments, jobs)
                # A later phase of the campaign finds each copy in place.
                RUN_CAMPAIGN.upload_candidate_archive(arguments, jobs)
            key = f"prefix/candidates/{sha256}.tar.gz"
            self.assertEqual({("west", key): sha256, ("east", key): sha256},
                             {name: checksum for name, (_, _, checksum) in aws.objects.items()})
            self.assertEqual(2, sum(call[0] == "put" for call in aws.calls))

    def admit(self, arguments, result_root, job, observed=None):
        """Place and admit one launch as the unit dispatcher does, by default in a loop of its own: the job as placed,
        and whether it was admitted."""
        candidate, _, admitted, _, _ = RUN_CAMPAIGN.admit_launch(
            arguments, result_root, result_root / "job-attempts.tsv", set(), job, 0, 0,
            {} if observed is None else observed)
        return candidate, admitted

    def shared_fleet(self, regions=()):
        """A shared fleet budget for Spot hosts in its home region, us-west-2, and in regions."""
        fixture = test_fleet_budget.TestFleetBudget()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        if regions:
            fixture.policy["regions"] = {region: {"max_vcpus": 64, "spot_vcpu_reserve": 0} for region in regions}
            fixture.path.write_text(json.dumps(fixture.policy))
        return RUN_CAMPAIGN.FleetBudget(fixture.path)

    def test_spot_platform_waits_for_quota_and_an_on_demand_platform_refuses_spot(self):
        job = RUN_CAMPAIGN.Job(self.platform, "unit-worker", 1, 10, 1, "spot", "unit-worker")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            arguments = self.arguments(root)
            arguments.purchases, arguments.spot_vcpu_reserve = {"r8i": "spot"}, 0
            arguments.platform_regions = {"r8i": ["us-west-2"]}
            aws = arguments.cloud = FakeCloud()
            arguments.shared_budget = self.shared_fleet()
            # Other work holds the region's Spot quota, then ends.
            aws.quotas[("us-west-2", "spot")] = 8
            aws.region_instances["us-west-2"] = [
                cloud.Instance("i-other", "r8i.2xlarge", "spot", "running", "", {}, 0)]
            loop = {}
            waiting, admitted = self.admit(arguments, root, job, loop)
            self.assertEqual(("spot", False), (waiting.market, admitted))
            aws.region_instances["us-west-2"] = []
            # Every admission of a loop reads the fleet as the loop described it; the next loop finds the quota free.
            self.assertFalse(self.admit(arguments, root, job, loop)[1])
            launched, admitted = self.admit(arguments, root, job)
            self.assertEqual(("spot", True), (launched.market, admitted))
            # The quota is asked for once.
            self.assertEqual({"instances": 2, "spot_requests": 2, "vcpu_quota": 1, "vcpus": 1},
                             {operation: [call[0] for call in aws.calls].count(operation)
                              for operation in ("instances", "spot_requests", "vcpu_quota", "vcpus")})
            self.assertIn("queued-capacity\tfleet quota or pending-launch limit",
                          (root / "job-attempts.tsv").read_text())
            # A Spot platform launches no On-Demand host, and an On-Demand platform no Spot host.
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "buys only Spot"):
                RUN_CAMPAIGN.check_purchase(arguments, replace(launched, market="on-demand"))
            arguments.purchases["r8i"] = "on-demand"
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "buys only On-Demand"):
                RUN_CAMPAIGN.check_purchase(arguments, launched)

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

    def git(self, repository, *arguments):
        return subprocess.check_output(["git", *arguments], cwd=repository, text=True).strip()

    def commit(self, repository, files, message):
        for name, content in files.items():
            (repository / name).parent.mkdir(parents=True, exist_ok=True)
            (repository / name).write_text(content)
        self.git(repository, "add", ".")
        self.git(repository, "commit", "-qm", message)
        return self.git(repository, "rev-parse", "HEAD")

    def test_candidate_freezes_from_git_whatever_else_the_checkout_holds(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository = root / "repository"
            repository.mkdir()
            self.git(repository, "init", "-q")
            self.git(repository, "config", "user.name", "Test")
            self.git(repository, "config", "user.email", "test@example.com")
            baseline = "tools/re2-benchmark/baseline"
            # The candidate's generator writes its tables from a module beside it.
            candidate = self.commit(repository, {
                "src/main/Engine.java": "final class Engine {}\n",
                f"{baseline}/neighbour.py": "TABLE = 'frozen'\n",
                f"{baseline}/generate-units.py":
                    "import argparse, pathlib, neighbour\n"
                    "parser = argparse.ArgumentParser()\n"
                    "parser.add_argument('--output-directory', type=pathlib.Path,"
                    " default=pathlib.Path(__file__).parent)\n"
                    "for name in ('row-protocol.tsv', 'units.tsv', 'unit-rows.tsv'):\n"
                    "    (parser.parse_args().output_directory / name).write_text(neighbour.TABLE + name)\n",
                **{f"{baseline}/{name}": f"{name}\n" for name in RUN_CAMPAIGN.PROVENANCE_MANIFESTS.values()},
                ".gitignore": "__pycache__/\n" + "".join(
                    f"/{baseline}/{table}\n" for table in RUN_CAMPAIGN.work_units.TABLES)},
                "Candidate")
            subprocess.run([sys.executable, str(repository / baseline / "generate-units.py")], check=True)
            # The controller's checkout has since moved on, and has uncommitted engine changes.
            head = self.commit(repository, {"src/main/Engine.java": "final class Engine { int next; }\n"}, "Next")
            (repository / "src/main/Engine.java").write_text("uncommitted\n")
            campaign_root = root / "campaign"
            arguments = SimpleNamespace(candidates=[("first", candidate[:12])], campaign="tournament",
                                        campaign_root=campaign_root)
            # The controller plans from this checkout's tables, so a stale or missing one is refused before any
            # archive is written.
            units = repository / baseline / "units.tsv"
            for change in (lambda: units.write_text("stale\n"), units.unlink):
                change()
                with self.assertRaisesRegex(SystemExit, r"generated tables \['units\.tsv'\] differ"):
                    RUN_CAMPAIGN.validate_source(repository, arguments)
            self.assertFalse((campaign_root / "first" / "candidate" / "regulator.tar.gz").exists())
            subprocess.run([sys.executable, str(repository / baseline / "generate-units.py")], check=True)

            RUN_CAMPAIGN.validate_source(repository, arguments)

            self.assertEqual(candidate, arguments.candidate_commit)
            self.assertEqual(candidate, self.git(repository, "rev-parse", "refs/benchmarks/tournament/first"))
            self.assertEqual(self.git(repository, "rev-parse", f"{candidate}:src/main"), arguments.engine_tree)
            with tarfile.open(arguments.candidate_archive) as archive:
                self.assertEqual(candidate, archive.pax_headers["comment"])
                self.assertEqual(b"final class Engine {}\n", archive.extractfile("src/main/Engine.java").read())
                self.assertEqual(b"frozenunits.tsv", archive.extractfile(f"{baseline}/units.tsv").read())
            with arguments.candidate_provenance.open(newline="") as provenance:
                [values] = csv.DictReader(provenance, delimiter="\t")
            self.assertEqual({"candidate_ref": "refs/benchmarks/tournament/first", "candidate_commit": candidate,
                              "engine_tree": arguments.engine_tree,
                              "archive_sha256": arguments.candidate_archive_sha256},
                             {field: values[field] for field in
                              ("candidate_ref", "candidate_commit", "engine_tree", "archive_sha256")})
            self.assertEqual("name\tcommit\tref\tarchive_sha256\n"
                             f"first\t{candidate}\trefs/benchmarks/tournament/first\t"
                             f"{arguments.candidate_archive_sha256}\n",
                             (campaign_root / "candidates.tsv").read_text())
            # A restart reproduces the freeze, and refuses a changed archive or another commit under the name.
            RUN_CAMPAIGN.validate_source(repository, arguments)
            frozen = arguments.candidate_archive.read_bytes()
            arguments.candidate_archive.write_bytes(frozen + b"\0")
            with self.assertRaisesRegex(SystemExit, "differs from the deterministic archive"):
                RUN_CAMPAIGN.validate_source(repository, arguments)
            arguments.candidate_archive.write_bytes(frozen)
            with self.assertRaisesRegex(SystemExit, "already names"):
                RUN_CAMPAIGN.validate_source(repository, SimpleNamespace(**{**vars(arguments),
                                                                            "candidates": [("first", head)]}))
            # Hosts run the candidate's tooling while this checkout's checks their results, so the two must match.
            (repository / baseline / "neighbour.py").write_text("TABLE = 'edited'\n")
            with self.assertRaisesRegex(SystemExit, "requires committed benchmark tooling"):
                RUN_CAMPAIGN.validate_source(repository, arguments)
            self.commit(repository, {}, "Tooling")
            with self.assertRaisesRegex(SystemExit, "other benchmark tooling"):
                RUN_CAMPAIGN.validate_source(repository, arguments)

    def test_campaign_advances_each_candidate_as_its_own_phases_complete(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            campaign_root = Path(temporary_directory)
            arguments = SimpleNamespace(
                campaign="tournament", campaign_root=campaign_root, language_plan=[], selected_shards=["trino-like"],
                max_concurrent=16, max_concurrent_per_platform=64, spot_vcpu_reserve=16, release_version="",
                phase_timeout_seconds=None, fleet_budget=campaign_root / "fleet-policy.json", platform_region_values=[],
                purchase_values=[], transfer_bucket=[], smoke_protocol="smoke", smoke_results=None,
                primary_results=None, confirmation_jobs=None, engine_builds=None, execute=True, detach=False)
            candidates = [RUN_CAMPAIGN.FrozenCandidate(name, commit * 40, f"refs/benchmarks/tournament/{name}",
                                                       campaign_root / name / "candidate", "0" * 64, "e" * 40)
                          for name, commit in (("a", "a"), ("b", "b"))]
            frozen = {candidate.name: candidate for candidate in candidates}
            run_chains = RUN_CAMPAIGN.run_chains
            # Each step's child runs this many polls; A's smoke is slow and then fails. A's reduction lists a job.
            polls = {"a/smoke": 4}
            failing = {"a/smoke"}
            unit_id = next(unit_id for unit_id, unit in sorted(RUN_CAMPAIGN.work_units.load(SCRIPT.parent).items())
                           if unit["shard_id"] == "trino-like")
            confirmed = {"a/reduction": f"r8i\ttrino-like\t{unit_id}\n"}
            events = []

            class Child:
                def __init__(self, step):
                    self.step = step
                    self.name = str(step.root.relative_to(campaign_root))
                    self.remaining = polls.get(self.name, 1)

                def poll(self):
                    self.remaining -= 1
                    if self.remaining:
                        return None
                    events.append(("exit", self.name))
                    if self.name in failing:
                        return 1
                    self.step.root.mkdir(parents=True, exist_ok=True)
                    if self.step.label == "reduction":
                        (self.step.root / "confirmation-jobs.tsv").write_text(
                            "platform\tshard_id\tunit_id\n" + confirmed.get(self.name, ""))
                        (self.step.root / "output-checksums.tsv").touch()
                    else:
                        (self.step.root / "campaign-events.tsv").touch()
                        (self.step.root / RUN_CAMPAIGN.PHASE_COMPLETE).touch()
                    return 0

            def start(step, share):
                child = Child(step)
                events.append(("start", child.name, step.command(share)))
                return child

            def run(names=("a", "b")):
                events.clear()
                arguments.candidates = [(name, frozen[name].commit) for name in names]
                driver_polls = iter(range(100))

                def sleep(_):
                    # A driver that never finishes fails rather than hangs.
                    if next(driver_polls, None) is None:
                        self.fail("the campaign driver never finished")

                with patch.object(RUN_CAMPAIGN, "freeze_candidate",
                                  side_effect=lambda root, campaign_root, campaign, name, commitish: frozen[name]), \
                        patch.object(RUN_CAMPAIGN, "run_chains", lambda chains, maximum, per_platform: run_chains(
                            chains, maximum, per_platform, start=start, adopt=lambda step: None, poll_seconds=0)), \
                        patch.object(RUN_CAMPAIGN.time, "sleep", sleep), contextlib.redirect_stdout(io.StringIO()):
                    RUN_CAMPAIGN.run_campaign(arguments, SCRIPT.parents[3])

            # A's failure stops only A, and the campaign then exits naming it.
            with self.assertRaisesRegex(SystemExit, "failed candidates:\ntournament.a: smoke failed") as failed:
                run()

            order = [event[:2] for event in events]
            # A build takes a host for each platform's one engine build, leaving the rest for the other chain.
            command = next(event[2] for event in events if event[:2] == ("start", "a/build"))
            self.assertIn("--max-concurrent=2", command)
            self.assertIn("--max-concurrent-per-platform=1", command)
            # B's primary starts once B's smoke passed, while A's smoke still runs. B's primary holds three of the
            # seven replica sets left, so it gets its reserved host plus 3/7 of the other 14; its per-platform share
            # is capped at its 18 items.
            self.assertLess(order.index(("start", "b/primary")), order.index(("exit", "a/smoke")))
            command = next(event[2] for event in events if event[:2] == ("start", "b/primary"))
            self.assertIn(f"--candidate=b={'b' * 40}", command)
            self.assertIn("--max-concurrent=7", command)
            self.assertIn("--max-concurrent-per-platform=18", command)
            # B's reduction takes its primary's events.
            command = next(event[2] for event in events if event[:2] == ("start", "b/reduction"))
            self.assertIn(f"--events={campaign_root / 'b' / 'primary' / 'campaign-events.tsv'}", command)
            # B reduces its primary and, with no confirmation jobs listed, completes.
            self.assertNotIn(("start", "a/primary"), order)
            self.assertIn(("exit", "b/reduction"), order)
            self.assertNotIn(("start", "b/confirmation"), order)
            self.assertNotIn("tournament.b", str(failed.exception))

            # A restart skips every completed step: A resumes at its smoke, alone, with a host for each of its 12
            # work items, and confirms the job its reduction listed on the shards it measured, with one host.
            failing.clear()
            run()

            self.assertEqual([("start", "a/smoke"), ("start", "a/primary"), ("start", "a/reduction"),
                              ("start", "a/confirmation")], [event[:2] for event in events if event[0] == "start"])
            self.assertIn("--max-concurrent=12", events[0][2])
            command = next(event[2] for event in events if event[:2] == ("start", "a/confirmation"))
            self.assertIn("--shard=trino-like", command)
            self.assertIn("--max-concurrent=1", command)
            # The confirmation's jobs weigh on its chain's share only once the reduction that lists them completed.
            [chain, _] = RUN_CAMPAIGN.campaign_chains(arguments, candidates, {})
            (campaign_root / "a" / "reduction" / "output-checksums.tsv").unlink()
            self.assertEqual([], chain.steps[4].remaining())
            (campaign_root / "a" / "reduction" / "output-checksums.tsv").touch()
            self.assertEqual([("r8i", sum(math.ceil(float(group["estimated_seconds"])) for group in
                                          RUN_CAMPAIGN.work_units.load(SCRIPT.parent)[unit_id]["groups"]))],
                             chain.steps[4].remaining())
            # A restart measures what the campaign started measuring, of the candidates it started with.
            with self.assertRaisesRegex(SystemExit, "candidates differ"):
                run(names=("a",))
            arguments.selected_shards = None
            with self.assertRaisesRegex(SystemExit, "options differ"):
                run()
            self.assertEqual([], events)

            # A restart adopts a phase's controller by the process ID and start time it records, though the restart
            # changed its --purchase, and leaves the phase's lock to any controller starting meanwhile. Once the
            # controller exited, the record's process ID may name a process started since, which is not adopted.
            arguments.purchase_values = ["r8i=on-demand"]
            [chain, _] = RUN_CAMPAIGN.campaign_chains(arguments, candidates, {})
            record = chain.steps[2].root / RUN_CAMPAIGN.DRIVER_CHILD
            with RUN_CAMPAIGN.controller_lock(chain.steps[2].root, RUN_CAMPAIGN.DRIVER_CHILD), \
                    patch.object(RUN_CAMPAIGN.fcntl, "flock", side_effect=AssertionError("took the phase's lock")):
                self.assertEqual(os.getpid(), RUN_CAMPAIGN.child_process_id(chain.steps[2]))
                record.write_text(f"pid={os.getpid()}\nstarted=Thu Jan  1 00:00:00 1970\n")
                self.assertIsNone(RUN_CAMPAIGN.child_process_id(chain.steps[2]))
            # A reduction keeps no record, so a restart adopts it only from a process running its command for this
            # campaign root, from any interpreter path, and not for another root, even one differing only in spaces.
            arguments.campaign_root = campaign_root / "campaign root"
            [chain, _] = RUN_CAMPAIGN.campaign_chains(arguments, candidates, {})
            step = chain.steps[3]
            command = " ".join(step.command(None)[1:])
            others = "\n".join(f"10{index} python3 " + command.replace("campaign root", root)
                               for index, root in ((1, "elsewhere"), (2, "campaign  root")))
            with patch.object(RUN_CAMPAIGN, "check_output",
                              return_value=f"{others}\n103 /opt/Python 3/bin/python3 {command}"):
                self.assertEqual(103, RUN_CAMPAIGN.child_process_id(step))
            with patch.object(RUN_CAMPAIGN, "check_output", return_value=others):
                self.assertIsNone(RUN_CAMPAIGN.child_process_id(step))

    def test_restarted_phase_waits_for_its_frozen_share_and_a_new_one_takes_free_hosts(self):
        # Under limits of four, A's phase froze half of them and failed, and B's next phase then took all four. A
        # restarted driver adopts B's phase, which still runs, before restarting A's, which waits for B's hosts.
        with tempfile.TemporaryDirectory() as temporary_directory:
            events = []

            def phase(name, frozen):
                root = Path(temporary_directory) / name
                root.mkdir()
                (root / "execution-policy.json").write_text(
                    json.dumps({"max_concurrent": frozen, "max_concurrent_per_platform": frozen}))
                return RUN_CAMPAIGN.CampaignStep(name, root, None,
                                                 lambda: (root / RUN_CAMPAIGN.PHASE_COMPLETE).is_file())

            class Child:
                def __init__(self, step, polls):
                    self.step, self.polls = step, polls

                def poll(self):
                    self.polls -= 1
                    if self.polls:
                        return None
                    events.append(("exit", self.step.label))
                    (self.step.root / RUN_CAMPAIGN.PHASE_COMPLETE).touch()
                    return 0

            def start(step, share):
                events.append(("start", step.label, share))
                return Child(step, 1)

            def run(steps, start, adopt):
                events.clear()
                chains = [RUN_CAMPAIGN.CampaignChain(step.label, [step]) for step in steps]
                driver_polls = iter(range(10))

                def sleep(_):
                    # A driver that never finishes fails rather than hangs.
                    if next(driver_polls, None) is None:
                        self.fail("the campaign driver never finished")

                with patch.object(RUN_CAMPAIGN.time, "sleep", sleep), contextlib.redirect_stdout(io.StringIO()):
                    RUN_CAMPAIGN.run_chains(chains, 4, 4, start=start, adopt=adopt)
                self.assertEqual(["" for _ in chains], [chain.failure for chain in chains])

            a, b = phase("a", 2), phase("b", 4)
            run([a, b], start, lambda step: Child(step, 2) if step is b else None)
            self.assertEqual([("exit", "b"), ("start", "a", (2, 2)), ("exit", "a")], events)

            # A restarted driver can start a phase whose controller holds its lock but has not yet recorded itself.
            # The duplicate exits finding the lock held, and the driver then adopts the running controller.
            c, owner = phase("c", 4), []

            def start_duplicate(step, share):
                events.append(("start", step.label, share))
                owner.append(Child(step, 2))
                return SimpleNamespace(poll=lambda: RUN_CAMPAIGN.CONTROLLER_BUSY_STATUS)

            run([c], start_duplicate, lambda step: owner[0] if owner else None)
            self.assertEqual([("start", "c", (4, 4)), ("exit", "c")], events)

            # Beside another chain's last phase, which leaves no phase to start, a phase takes the free hosts its
            # three work items can use rather than its sliver of the work left.
            f, g = phase("f", 2), RUN_CAMPAIGN.CampaignStep(
                "g", Path(temporary_directory) / "g", None,
                lambda: (Path(temporary_directory) / "g" / RUN_CAMPAIGN.PHASE_COMPLETE).is_file(),
                remaining=lambda: [("r8i", 600)] * 3)
            f.remaining = lambda: [("r8i", 1_000_000)]
            g.root.mkdir()
            run([f, g], start, lambda step: Child(step, 2) if step is f else None)
            self.assertEqual(("start", "g", (2, 2)), events[0])

            # New phases split the hosts left after one each by their seconds of work left, not their work items: h's
            # three items of 1000 seconds take one of the two spare hosts, where counting items, k's nine to its
            # three, would give it none; k, with no other phase left to start, then takes the rest.
            h, k = (RUN_CAMPAIGN.CampaignStep(name, Path(temporary_directory) / name, None,
                                              lambda name=name: (Path(temporary_directory) / name /
                                                                 RUN_CAMPAIGN.PHASE_COMPLETE).is_file(),
                                              remaining=lambda work=work: work)
                    for name, work in (("h", [("r8i", 1000)] * 3), ("k", [("r8i", 10)] * 9)))
            for step in (h, k):
                step.root.mkdir()
            run([h, k], start, lambda step: None)
            self.assertEqual([("start", "h", (2, 2)), ("start", "k", (2, 2))], events[:2])

            # Limits that cannot hold a share a phase froze stop the driver, though that phase runs adopted, naming
            # the limits that hold it.
            d = phase("d", 8)
            with self.assertRaisesRegex(SystemExit, "d d 8 hosts, 8 per platform; give at least --max-concurrent 8 "
                                                    "--max-concurrent-per-platform 8"):
                run([d, phase("e", 2)], start, lambda step: Child(step, 1) if step is d else None)
            self.assertEqual([], events)

    def test_detached_campaign_returns_and_a_second_launch_reports_the_live_driver(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            campaign_root = Path(temporary_directory)
            arguments = SimpleNamespace(
                campaign="tournament", campaign_root=campaign_root, candidates=[("a", "main")],
                fleet_budget=campaign_root / "fleet-policy.json", smoke_results=None, primary_results=None,
                confirmation_jobs=None, engine_builds=None, language_plan=[], execute=True, detach=True, max_concurrent=1, max_concurrent_per_platform=1,
                selected_shards=None, smoke_protocol="smoke", release_version="")
            argv = ["run-campaign.py", "campaign", "tournament", "--execute", "--detach", "--candidate=a=main"]
            output = io.StringIO()

            def start(command, **_):
                # The driver takes the campaign's controller lock as it starts, and records itself.
                driver.enter_context(RUN_CAMPAIGN.controller_lock(campaign_root, RUN_CAMPAIGN.CAMPAIGN_DRIVER))
                return SimpleNamespace(pid=os.getpid())

            # Popen starts only the driver here, so the driver's start time is given.
            with contextlib.ExitStack() as driver, patch.object(RUN_CAMPAIGN.sys, "argv", argv), \
                    contextlib.redirect_stdout(output), \
                    patch.object(RUN_CAMPAIGN.subprocess, "Popen", side_effect=start) as popen, \
                    patch.object(RUN_CAMPAIGN, "process_start", return_value="Thu Oct 29 00:00:00 2026"), \
                    patch.object(RUN_CAMPAIGN, "resolve_commit", return_value="a" * 40), \
                    patch.object(RUN_CAMPAIGN, "freeze_candidate") as freeze:
                RUN_CAMPAIGN.run_campaign(arguments, SCRIPT.parents[3])
                RUN_CAMPAIGN.run_campaign(arguments, SCRIPT.parents[3])

            # Only the driver freezes and runs; the launch returns once it started it in its own session.
            freeze.assert_not_called()
            popen.assert_called_once()
            command, options = popen.call_args
            self.assertEqual([sys.executable, str(SCRIPT.resolve()), "campaign", "tournament", "--execute",
                              "--candidate=a=main"], command[0])
            self.assertTrue(options["start_new_session"])
            self.assertEqual(str(campaign_root / RUN_CAMPAIGN.CAMPAIGN_DRIVER_LOG), options["stdout"].name)
            started, reported = output.getvalue().splitlines()
            self.assertIn(f"started controller process {os.getpid()}", started)
            self.assertIn(f"already runs as process {os.getpid()}", reported)

    def test_campaign_refuses_candidates_whose_chains_share_a_campaign_id(self):
        # With a language plan, candidate a's language chain and candidate a.lang's baseline chain are both
        # tournament.a.lang, so they would reserve the fleet and number their hosts as one campaign.
        arguments = SimpleNamespace(
            campaign="tournament", campaign_root=Path("unused"), candidates=[("a", "main"), ("a.lang", "main")],
            fleet_budget=Path("fleet-policy.json"), smoke_results=None, primary_results=None, confirmation_jobs=None,
            engine_builds=None, language_plan=[Path("plan")], execute=True, detach=False)
        with patch.object(RUN_CAMPAIGN, "freeze_candidate") as freeze, \
                self.assertRaisesRegex(SystemExit, "a and a.lang would both run as campaign tournament.a.lang"):
            RUN_CAMPAIGN.run_campaign(arguments, SCRIPT.parents[3])
        freeze.assert_not_called()

    def test_status_reports_each_candidates_phases_from_their_ledgers(self):
        now = datetime(2026, 10, 28, 23, 40, tzinfo=timezone.utc).timestamp()
        with tempfile.TemporaryDirectory() as temporary_directory:
            campaign_root = Path(temporary_directory) / "tournament"
            build, primary = campaign_root / "a" / "build", campaign_root / "a" / "primary"
            language_smoke = campaign_root / "b" / "lang" / "smoke"
            write_tsv(campaign_root / "candidates.tsv", RUN_CAMPAIGN.CANDIDATE_FIELDS,
                      [{"name": name, "commit": name * 40, "ref": f"refs/benchmarks/tournament/{name}",
                        "archive_sha256": "0" * 64} for name in ("a", "b")])
            build.mkdir(parents=True)
            (build / RUN_CAMPAIGN.PHASE_COMPLETE).write_text("complete\n")
            primary.mkdir(parents=True)
            # Three units: one accepted, one half accepted with 900 seconds left, and one of 1800 seconds untouched.
            write_tsv(primary / "work-items.tsv", RUN_CAMPAIGN.WORK_ITEM_FIELDS, [
                {"platform": "r8i", "instance_type": "r8i.2xlarge", "shard_id": "s", "unit_id": unit, "replica": "1",
                 "estimated_seconds": "-", "groups": groups, "excluded_instances": "-"}
                for unit, groups in (("u1", "1:600,2:300"), ("u2", "1:900,2:900"), ("u3", "1:1800"))])
            write_tsv(primary / "accepted-units.tsv", RUN_CAMPAIGN.unit_session.UNIT_RECEIPT_FIELDS, [
                {**dict.fromkeys(RUN_CAMPAIGN.unit_session.UNIT_RECEIPT_FIELDS, "-"), "platform": "r8i",
                 "unit_id": unit, "replica_id": "1", "group_id": group, "unit_attempt_id": f"session/{unit}"}
                for unit, group in (("u1", "1"), ("u1", "2"), ("u2", "1"))])
            jobs = [replace(RUN_CAMPAIGN.unit_worker_job(self.platform, epoch, epoch, market), region=region)
                    for epoch, market, region in ((1, "spot", "us-east-2"), (2, "spot", "us-east-2"),
                                                  (3, "on-demand", "us-west-2"), (4, "spot", "us-east-2"),
                                                  (5, "on-demand", "us-west-2"))]
            # Worker 3 runs its last work lease behind the stop lease it was given; worker 4 holds only its stop lease.
            workers = {1: RUN_CAMPAIGN.UnitWorker(jobs[0], None, None, started=True, leases={1: "u2"}),
                       2: RUN_CAMPAIGN.UnitWorker(jobs[1], None, None),
                       3: RUN_CAMPAIGN.UnitWorker(jobs[2], None, None, started=True, leases={1: "u3", 2: None},
                                                  settled={2}, stopping=True),
                       4: RUN_CAMPAIGN.UnitWorker(jobs[3], None, None, started=True, leases={1: None}, stopping=True),
                       5: RUN_CAMPAIGN.UnitWorker(jobs[4], None, None, started=True, leases={1: "u1"}, settled={1})}
            # Candidate b's language smoke stopped with its controller, so its snapshot's workers may have stopped.
            language_smoke.mkdir(parents=True)
            write_tsv(language_smoke / "work-items.tsv", RUN_CAMPAIGN.WORK_ITEM_FIELDS, [
                {"platform": "r8i", "instance_type": "r8i.2xlarge", "shard_id": "s", "unit_id": "u1", "replica": "1",
                 "estimated_seconds": "-", "groups": "1:600", "excluded_instances": "-"}])
            for result_root in (primary, language_smoke):
                with patch.object(RUN_CAMPAIGN.time, "time", return_value=now - 20):
                    RUN_CAMPAIGN.UnitDispatcher.write_status(SimpleNamespace(
                        result_root=result_root, queued=lambda: [], workers=workers, terminated=set(),
                        unresolved_items=lambda: [(SimpleNamespace(platform="r8i", unit_id="u4", replica=1), "spent")]))
            # A restarted controller's snapshot supersedes the unresolved units its predecessor left.
            write_tsv(primary / "unresolved-units.tsv", ("platform", "unit_id", "replica", "detail"),
                      [{"platform": "r8i", "unit_id": unit, "replica": "1", "detail": "not accepted"}
                       for unit in ("u2", "u3")])
            policy = Path(temporary_directory) / "fleet" / "fleet-policy.json"
            (primary / "execution-policy.json").write_text(json.dumps({"fleet_budget": str(policy)}))
            policy.parent.mkdir()
            owner = str(primary.resolve())
            (policy.parent / "fleet-state.json").write_text(json.dumps({
                "completed": {"other/1": {"owner": "other", "machine_hours": 9, "hourly_rate": 1},
                              "x/5": {"owner": owner, "machine_hours": 1, "hourly_rate": 0.1}},
                "reservations": {"x/1": {"owner": owner, "started_at": now - 1800, "reserved_until": now + 3600,
                                         "hourly_rate": 0.1}}}))
            for seconds in ("170.0", "180.0"):
                RUN_CAMPAIGN.write_attempt(primary / "job-attempts.tsv", jobs[0], "stopped",
                                           f"boot_to_first_unit_seconds={seconds}")
            write_tsv(primary / unit_dispatch.UNIT_UPLOADS, unit_dispatch.UNIT_UPLOAD_FIELDS, [
                {**dict.fromkeys(unit_dispatch.UNIT_UPLOAD_FIELDS, "-"), "upload_seconds": seconds}
                for seconds in ("3", "5")])

            # A phase's controller runs as its record names it; status leaves the phase's lock to any controller
            # starting meanwhile.
            with RUN_CAMPAIGN.controller_lock(primary, RUN_CAMPAIGN.DRIVER_CHILD), \
                    patch.object(RUN_CAMPAIGN.fcntl, "flock", side_effect=AssertionError("took the phase's lock")):
                lines = RUN_CAMPAIGN.campaign_status(campaign_root, now, timezone.utc)
                # The command reports a campaign root or one phase's root.
                for status_root, expected in ((campaign_root, lines[:4]), (primary, [f"phase {primary}", lines[3]])):
                    output = io.StringIO()
                    with patch.object(sys, "argv", ["run-campaign.py", "status", str(status_root)]), \
                            contextlib.redirect_stdout(output):
                        RUN_CAMPAIGN.main()
                    self.assertEqual(expected, output.getvalue().splitlines()[:len(expected)])
            # Its record outlives it, and once its process ID names a process started since, names no running
            # controller.
            (primary / RUN_CAMPAIGN.DRIVER_CHILD).write_text(f"pid={os.getpid()}\nstarted=Thu Jan  1 00:00:00 1970\n")
            self.assertIn("  primary           no live controller",
                          RUN_CAMPAIGN.campaign_status(campaign_root, now, timezone.utc))

        # 2700 estimated seconds remain over two running workers.
        self.assertEqual([
            f"campaign {campaign_root}: no live driver",
            "candidate a at aaaaaaaaaaaa",
            "  build             complete",
            f"  primary           running as process {os.getpid()}",
            "    units     1/3 accepted (3/5 groups), 1 unresolved, 0 queued",
            "    workers   1 booting, 2 running, 1 idle, 1 stopping, as of 20s ago",
            "              r8i r8i.2xlarge us-east-2 spot: 1 booting, 1 running, 1 stopping",
            "              r8i r8i.2xlarge us-west-2 on-demand: 1 running, 1 idle",
            "    spend     $0.15 so far, $0.10 reserved",
            "    finish    about 12:02 a.m. UTC on Thu Oct 29",
            "    medians   boot to first unit 175s, upload 4s",
            "candidate b at bbbbbbbbbbbb",
            "  lang smoke        no live controller",
            "    units     0/1 accepted (0/1 groups), 1 unresolved, 0 queued"], lines)

    def test_status_results_rank_rows_by_ratio_worst_first(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            # A line separator in the campaign root's name stays inside the field that shows it.
            campaign_root = Path(temporary_directory) / "tourna\u2028ment"
            primary, language = campaign_root / "a" / "primary", campaign_root / "a" / "lang" / "primary"
            unreadable = campaign_root / "b" / "primary"
            write_tsv(campaign_root / "candidates.tsv", RUN_CAMPAIGN.CANDIDATE_FIELDS, [
                {"name": name, "commit": name * 40, "ref": f"refs/benchmarks/tournament/{name}",
                 "archive_sha256": "0" * 64} for name in ("a", "b")])
            for root in (primary, language, unreadable):
                write_tsv(root / "work-items.tsv", RUN_CAMPAIGN.WORK_ITEM_FIELDS, [])
            # B's primary has a ledger no controller writes, which hides neither of A's phases.
            (unreadable / "accepted-units.tsv").write_text("platform\n")
            receipts = {root: [{**dict.fromkeys(RUN_CAMPAIGN.unit_session.UNIT_RECEIPT_FIELDS, "-"), "platform": "r9g",
                                "unit_id": "u1", "replica_id": str(replica), "group_id": "1",
                                "unit_attempt_id": f"session-{replica}/1", "instance_id": f"i-{replica}",
                                "host_epoch": str(replica), "shard_id": "like-compile"} for replica in replicas]
                        for root, replicas in ((primary, (1, 2, 3)), (language, (1, 2)))}
            # Regulator's compile ran 1, 2, and 9 times Trino's on the three hosts, whose median 1.0 measured at
            # 0.29; the second host's comparator missed its precision target. Every host also measured a row this
            # checkout's rows.tsv does not list.
            for receipt, score, outcome in zip(receipts[primary], ("100", "200", "900"),
                                               ("accepted", "precision-rejected", "accepted")):
                write_tsv(unit_dispatch.unit_attempt_directory(primary, receipt) / "observed-rows.tsv",
                          RUN_CAMPAIGN.unit_session.OBSERVED_FIELDS, [
                              {**dict.fromkeys(RUN_CAMPAIGN.unit_session.OBSERVED_FIELDS, "-"),
                               "row_id": f"like-compile/{case}/{system}", "shard_id": "like-compile",
                               "system": system, "score": value, "score_unit": "ns/op", "outcome": state}
                              for case, system, value, state in (
                                  ("CONTAINS_ABSENT", "regulator", score, "accepted"),
                                  ("CONTAINS_ABSENT", "trino-sql", "100", outcome),
                                  ("RETIRED", "regulator", score, "accepted"))])
            # The phase ended with a unit that spent its attempts, and another accepted group's retained directory is
            # gone.
            write_tsv(primary / "unresolved-units.tsv", ("platform", "unit_id", "replica", "detail"), [
                {"platform": "r9g", "unit_id": "u2", "replica": "3",
                 "detail": "unit failed with status 1; spent its 3 attempts"}])
            receipts[primary].append({**receipts[primary][0], "unit_id": "u3", "unit_attempt_id": "session-4/1"})
            # Java's native memory mode, a 1.0 win, now takes 2 and 3 times JDK Pattern's mean time on the two hosts,
            # though its median sample ties on the first, 2.75 times over both; so does its long-words case, already a
            # 1.0 loss. Its pure Java mode takes 1.04 times, within the material limit. Regulator cannot run the
            # lookbehind case.
            for receipt, native, safe, jdk in zip(receipts[language], ([100, 100, 400], [900]), ([104], [312]),
                                                  ([100], [300])):
                partition = unit_dispatch.unit_attempt_directory(language, receipt) / "partitions" / "p1"
                partition.mkdir(parents=True)
                (partition / "language-results.json").write_text(json.dumps({
                    "manifest": {"suite": "language-bulk"},
                    "observations": {
                        **{name: {"outcome": "compared", "samples_ns": [samples]}
                           for name, samples in (("native", native), ("safe", safe), ("jdk", jdk))},
                        "lookbehind": {"outcome": "not-compatible", "reason": "no lookbehind"}},
                    "comparisons": [{"language": "java", "mode": mode, "logical_case": case,
                                     "operation": "execute", "candidate": candidate, "comparator": "jdk"}
                                    for mode, case, candidate in (("native", "grep/every-line", "native"),
                                                                  ("native", "grep/long-words-ascii", "native"),
                                                                  ("safe", "grep/every-line", "safe"),
                                                                  ("native", "grep/lookbehind", "lookbehind"))]}))
            for root in (primary, language):
                write_tsv(root / "accepted-units.tsv", RUN_CAMPAIGN.unit_session.UNIT_RECEIPT_FIELDS, receipts[root])

            def status(root, *options):
                output = io.StringIO()
                with patch.object(sys, "argv", ["run-campaign.py", "status", str(root), *options]), \
                        contextlib.redirect_stdout(output):
                    RUN_CAMPAIGN.main()
                return output.getvalue().split("\n")[:-1]

            lines, table = status(campaign_root, "--results"), status(campaign_root, "--results", "--tsv")
            # Given one phase's root, the command reports that phase as the campaign labels it.
            self.assertEqual(lines[6:12], status(language, "--results"))

        # Missing results and an unresolved unit come first, then a row this checkout's rows.tsv does not list, then
        # the slowest rows, each against its 1.0 ratio, then a row the comparison does not time, counting the hosts
        # that landed it.
        self.assertEqual([
            "candidate a primary: rows 2, slower 1, 1.0 wins lost 1, unresolved 1",
            "   ratio  n  host ratios                    1.0  platform row  flags",
            "       -  -                                   -  -        -  accepted groups without retained results: 1",
            "       -  -                                   -  r9g      u2 replica 3  "
            "unresolved: unit failed with status 1; spent its 3 attempts",
            "       -  3                                   -  r9g      like-compile/RETIRED/regulator  "
            "not in this checkout's rows.tsv",
            "   2.000  3  r1=1.000 r2=2.000 r3=9.000   0.293  r9g      like-compile/CONTAINS_ABSENT/regulator::trino-sql  "
            "slower, 1.0 win now a loss, precision rejected",
            "candidate a lang primary: rows 4, slower 2, 1.0 wins lost 1, unresolved 0",
            "   ratio  n  host ratios                    1.0  platform row  flags",
            "   2.750  2  r1=2.000 r2=3.000            0.082  r9g      java/native/language-bulk/grep/every-line/execute  "
            "slower, 1.0 win now a loss",
            "   2.750  2  r1=2.000 r2=3.000            6.332  r9g      "
            "java/native/language-bulk/grep/long-words-ascii/execute  slower",
            "   1.040  2  r1=1.040 r2=1.040            0.082  r9g      java/safe/language-bulk/grep/every-line/execute",
            "       -  2                                   -  r9g      java/native/language-bulk/grep/lookbehind/execute  "
            "not-compatible, candidate: no lookbehind",
            "candidate b primary: unreadable: UnsafeCampaignError: unexpected unit receipt schema in "
            f"{unreadable / 'accepted-units.tsv'}"],
            lines)
        self.assertEqual(["\t".join(RUN_CAMPAIGN.RESULT_FIELDS),
                          "a\tprimary\tr9g\tu2 replica 3\t-\t-\t\t-\t"
                          "unresolved: unit failed with status 1; spent its 3 attempts",
                          "a\tprimary\tr9g\tlike-compile/CONTAINS_ABSENT/regulator::trino-sql\t2.000\t3\t"
                          "r1=1.000 r2=2.000 r3=9.000\t0.293\tslower, 1.0 win now a loss, precision rejected",
                          "b\tprimary\t-\t-\t-\t-\t-\t-\tunreadable: UnsafeCampaignError: unexpected unit receipt "
                          f"schema in {unreadable / 'accepted-units.tsv'}"],
                         [table[0], table[2], table[4], table[-1]])
        self.assertEqual(10, len(table))

    def test_primary_requires_accepted_smoke_results(self):
        arguments = SimpleNamespace(phase="primary", smoke_results=None)
        with self.assertRaisesRegex(SystemExit, "requires --smoke-results"):
            RUN_CAMPAIGN.validate_prerequisites(arguments, None)

    def test_campaign_rejects_volatile_build_output_paths(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            durable = root.parent / "durable"
            target = root / "target"
            arguments = SimpleNamespace(campaign_root=durable / "campaign")

            RUN_CAMPAIGN.validate_durable_paths(root, arguments, durable / "campaign" / "results")

            cases = (
                ("result root", target / "results", arguments),
                ("campaign root", durable / "results", SimpleNamespace(campaign_root=target / "campaign")),
            )
            for label, result_root, case_arguments in cases:
                with self.subTest(label=label), self.assertRaisesRegex(SystemExit, label):
                    RUN_CAMPAIGN.validate_durable_paths(root, case_arguments, result_root)

    def worker(self, root, language, market="spot"):
        """A worker's arguments, job, metadata and engine archives, for a baseline pool or, with language, a
        language pool that runs one of the two shards with plan bundles."""
        archives, manifest = self.engine_build(root)
        arguments = self.arguments(root)
        arguments.candidate_commit = "c" * 40
        with gzip.open(arguments.candidate_archive, "wb") as candidate:
            candidate.write(b"candidate")
        arguments.engine_build_directories = {"r8i": root}
        arguments.engine_build_policy = {"r8i": manifest}
        platform = self.platform
        if language:
            platform = replace(self.platform, instance_type="r8i.large", vcpus=2)
            (root / "provenance.tsv").write_text("provenance\n")
            arguments.language_plans = {"language-lifecycle": root}
            arguments.language_bundles = {"language-bulk": (root / "bulk.tar.zst", "1" * 64),
                                          "language-lifecycle": (root / "lifecycle.tar.zst", "2" * 64)}
            arguments.candidate_provenance = root / "provenance.tsv"
            # The other pool's shard has a bundle too, which the language pool's worker leaves alone.
            arguments.pool_shards = {("r8i", "r8i.2xlarge"): ["language-bulk"],
                                     ("r8i", "r8i.large"): ["language-lifecycle"]}
        arguments.shared_budget = SimpleNamespace(policy={"hourly_rates": {platform.instance_type: {"spot": 0.5}}})
        with patch.dict(os.environ, {"BENCHMARK_HEAP_SIZE": ""}):
            RUN_CAMPAIGN.campaign_heap_size(arguments)
        job = replace(RUN_CAMPAIGN.unit_worker_job(platform, 1, 10, market), region="us-west-2", stop_by=1800000045)
        metadata = RUN_CAMPAIGN.worker_metadata(arguments, job, "baseline-e10-20261030T000000Z",
                                                "campaign/baseline-e10-20261030T000000Z")
        self.enterContext(patch.dict(os.environ, {"TRANSFER_PREFIX": "campaign"}))
        return arguments, job, metadata, archives

    def worker_user_data(self, root, language):
        """A worker's user data, the settings it exports by name, and its engine archives."""
        arguments, job, metadata, archives = self.worker(root, language)
        # A worker runs the heap its phase captured when it started, whatever the controller's environment says now.
        with patch.dict(os.environ, {"BENCHMARK_HEAP_SIZE": "2g"}):
            user_data = RUN_CAMPAIGN.worker_user_data(arguments, job, metadata)
        self.assertTrue(user_data.endswith(RUN_CAMPAIGN.WORKER_USER_DATA.read_text().split("\n", 1)[1]))
        return user_data, user_data_settings(user_data), archives

    def test_user_data_installs_only_its_pools_archives_and_stops_at_its_stop_time(self):
        for language, expected, bundles in (
                # A baseline pool also runs the native runners its specialized-pattern comparators need.
                (False, ("classes", "trino", "native"), []),
                # A language pool runs the native runners and the plans of its own shards, and fetches the
                # candidate provenance their units measure.
                (True, ("classes", "native"), ["2" * 64])):
            with self.subTest(language=language), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                user_data, settings, archives = self.worker_user_data(root, language)
                bundle_lines = [f"language-plan {sha256} s3://bucket/campaign/language-plans/{sha256}.tar.zst"
                                for sha256 in bundles]
                self.assertEqual(
                    [f"jdk {'0' * 64} s3://bucket/campaign/jdks/{'0' * 64}.tar.gz",
                     *(f"{name} {archives[name]['sha256']} s3://bucket/campaign/engine-builds/"
                       f"{archives[name]['sha256']}.tar.zst" for name in expected), *bundle_lines],
                    settings["HOST_ARCHIVES"].splitlines())
                provenance = hashlib.sha256(b"provenance\n").hexdigest() if language else ""
                self.assertEqual(
                    (f"s3://bucket/campaign/candidates/{provenance}.tsv" if language else "", provenance),
                    (settings["LANGUAGE_CANDIDATE_PROVENANCE_URI"], settings["LANGUAGE_CANDIDATE_PROVENANCE_SHA256"]))
                self.assertEqual((hashlib.sha256(b"candidate").hexdigest(), "smoke", "d" * 40, "8g"),
                                 tuple(settings[name] for name in (
                                     "REGULATOR_CONTENT_SHA256", "BASELINE_PROTOCOL", "BASELINE_EXPECTED_ENGINE_TREE",
                                     "BENCHMARK_HEAP_SIZE")))
                self.assertEqual("s3://bucket/campaign/baseline-e10-20261030T000000Z/results/host.tar.gz",
                                 settings["HOST_ARCHIVE_URI"])
                (root / "user-data.sh").write_text(user_data)
                subprocess.run(["bash", "-n", str(root / "user-data.sh")], check=True)
                # The host powers off at its stop time, which a lease moves from 1800000045 to 1800000060.
                start = user_data.index("HOST_STOP_BY_FILE=")
                loop = user_data[start:user_data.index(") &\n", start)].replace("/var/tmp/re2-stop-by", "stop-by")
                result = subprocess.run(["bash", "-c", f"HOST_STOP_BY={settings['HOST_STOP_BY']}\n"
                                         "clock=1800000000\n"
                                         "date() { echo \"${clock}\"; }\n"
                                         "sleep() { clock=$((clock + $1)); ((clock != 1800000030)) || "
                                         "echo 1800000060 > stop-by; }\n"
                                         "shutdown() { echo \"shutdown $* at ${clock}\"; }\n"
                                         f"{loop})\n"], cwd=temporary, text=True, capture_output=True, check=False)
                self.assertEqual((0, "shutdown -h now at 1800000060\n"), (result.returncode, result.stdout))

    def test_worker_profile_reaches_every_object_its_user_data_names_and_no_other(self):
        policy = json.loads((SCRIPT.parents[1] / "aws" / "worker-policy.json").read_text())

        def allowed(action, bucket, key=None, prefix=None):
            resource = f"arn:aws:s3:::{bucket}" + (f"/{key}" if key is not None else "")
            return any(statement["Action"] == action and
                       any(fnmatch.fnmatchcase(resource, pattern) for pattern in
                           (statement["Resource"] if isinstance(statement["Resource"], list)
                            else [statement["Resource"]])) and
                       all(fnmatch.fnmatchcase(prefix or "", pattern) for pattern in
                           statement.get("Condition", {}).get("StringLike", {}).values())
                       for statement in policy["Statement"])

        with tempfile.TemporaryDirectory() as temporary:
            _, settings, _ = self.worker_user_data(Path(temporary), language=True)
        bucket = "regulator-benchmark-938463256137-us-west-2"
        reads = [line.split()[2] for line in settings["HOST_ARCHIVES"].splitlines()] + [
            settings["CANDIDATE_URI"], settings["LANGUAGE_CANDIDATE_PROVENANCE_URI"]]
        leases, results = settings["UNIT_WORKER_LEASE_PREFIX"], settings["UNIT_WORKER_RESULT_PREFIX"]
        for uri in reads:
            self.assertTrue(allowed("s3:GetObject", bucket, uri.removeprefix("s3://bucket/")), uri)
            self.assertFalse(allowed("s3:PutObject", bucket, uri.removeprefix("s3://bucket/")), uri)
        # The worker reads and claims its leases, lists them to tell one not yet written from one it cannot read,
        # and writes its results and host archive.
        self.assertTrue(allowed("s3:GetObject", bucket, f"{leases}/1.json"))
        self.assertTrue(allowed("s3:PutObject", bucket, f"{leases}/2.json"))
        self.assertTrue(allowed("s3:ListBucket", bucket, prefix=f"{leases}/"))
        for key in (f"{results}/heartbeat/1.json", settings["HOST_ARCHIVE_URI"].removeprefix("s3://bucket/")):
            self.assertTrue(allowed("s3:PutObject", bucket, key), key)
        self.assertFalse(allowed("s3:ListBucket", bucket, prefix=f"{results}/"))
        self.assertFalse(allowed("s3:GetObject", "another-bucket", f"{leases}/1.json"))

    def test_host_installs_the_build_toolchain_only_without_prebuilt_engines(self):
        host = (SCRIPT.parents[1] / "aws" / "run-host.sh").read_text()
        pins = {"NATIVE_COMPILER_PACKAGE": "gcc-pin", "CMAKE_PACKAGE": "cmake-pin", "CARGO_PACKAGE": "cargo-pin",
                "RUST_PACKAGE": "rust-pin"}
        # A build phase's worker, with no prebuilt engines, builds them; a measuring worker runs them prebuilt.
        for manifest in ("", "a" * 64):
            script = ("sudo() { echo \"installed $*\"; }\n"
                      + "".join(f"{name}={pin}\n" for name, pin in pins.items())
                      + f"{shell_function(host, 'install_packages')}ENGINE_BUILD_MANIFEST_SHA256={manifest}\n"
                      "install_packages\n")
            output = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=True).stdout
            installed = " ".join(line for line in output.splitlines() if line.startswith("installed"))
            with self.subTest(manifest=manifest):
                for pin in pins.values():
                    self.assertEqual(pin in installed, not manifest)
                # No worker installs perf.
                self.assertNotIn("perf", installed.split())

    def install_host_archives(self, root, archives, manifest="a" * 64):
        """Run run-host.sh's archive install for (name, SHA-256, path) archives, with S3 a local directory."""
        host = (SCRIPT.parents[1] / "aws" / "run-host.sh").read_text()
        script = ('aws() { echo "$*" >> "${AWS_CALLS}"; cat "${3#s3://bucket}"; }\nsleep() { :; }\nset -euo pipefail\n'
                  + shell_functions(host, "timeline", "unpack", "install_archive", "install_archives")
                  + 'TRINO_ARCHIVE_SHA256=none\ninstall_archives\necho "trino=${TRINO_ARCHIVE_SHA256}"\n')
        (root / "results").mkdir(exist_ok=True)
        environment = {
            **os.environ, "REGULATOR_DIR": str(SCRIPT.parents[3]), "RESULT_DIR": str(root / "results"),
            "STARTUP_TIMELINE": str(root / "results/startup-timeline.tsv"), "BASELINE_REGION": "us-west-2",
            "BASELINE_SHARED_WORK_DIR": str(root / "shared"), "ENGINE_BUILD_MANIFEST_SHA256": manifest,
            "AWS_CALLS": str(root / "aws-calls.txt"),
            "HOST_ARCHIVES": "".join(f"{name} {sha256} s3://bucket{path}\n" for name, sha256, path in archives)}
        return subprocess.run(["bash"], input=script, env=environment, text=True, capture_output=True)

    def test_host_streams_the_engine_archives_its_units_run_into_place(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            build, receipt = test_engine_build.TestEngineBuild().package(root)
            archives = json.loads((build / "engine-build.json").read_text())["archives"]
            sources = [build / archives[name]["file"] for name in ("classes", "trino")]
            result = self.install_host_archives(
                root, [(name, archives[name]["sha256"], source) for name, source in zip(("classes", "trino"), sources)],
                receipt["observed_rows_sha256"])

            self.assertEqual(0, result.returncode, result.stderr)
            # Each archive is downloaded once.
            self.assertEqual(
                [f"s3 cp s3://bucket{source} - --region us-west-2 --only-show-errors" for source in sources],
                (root / "aws-calls.txt").read_text().splitlines())
            shared = root / "shared"
            self.assertTrue((shared / "pinned-trino/classes/io/trino/Benchmark.class").is_file())
            self.assertFalse((shared / "language-native").exists())
            classes = shared / "regulator-classes"
            self.assertEqual("test-classes\n", (classes / "test-classes/io/airlift/Fixture.class").read_text())
            self.assertEqual(
                (f"{classes}/test-classes:{classes}/classes:{classes}/lib/slice-2.8.jar:"
                 f"{classes}/lib/jmh-core-1.37.jar", archives["classes"]["sha256"]),
                RUN_CAMPAIGN.engine_build.installed_classes(shared))
            self.assertEqual(archives["trino"]["sha256"] + "\n", (shared / "engine-build/trino.sha256").read_text())
            self.assertEqual([f"engine_build_manifest_sha256={receipt['observed_rows_sha256']}",
                              f"classes_archive_sha256={archives['classes']['sha256']}",
                              f"trino_archive_sha256={archives['trino']['sha256']}"],
                             (root / "results/engine-build.txt").read_text().splitlines())
            self.assertIn(f"trino={archives['trino']['sha256']}", result.stdout)
            # Each archive adds its step to the startup timeline.
            steps = [line.split("\t") for line in (root / "results/startup-timeline.tsv").read_text().splitlines()]
            self.assertEqual(["classes", "trino"], [step[0] for step in steps])
            self.assertTrue(all(float(started) <= float(finished) for _, started, finished in steps))
            self.assertEqual([], list(shared.glob(".install.*")))

    def test_host_refuses_an_archive_that_fails_its_pin_or_holds_an_unsafe_entry(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            build, _ = test_engine_build.TestEngineBuild().package(root)
            classes = json.loads((build / "engine-build.json").read_text())["archives"]["classes"]
            unsafe = root / "unsafe.tar.zst"
            stream = io.BytesIO()
            with tarfile.open(fileobj=stream, mode="w") as archive:
                for name in ("regulator-classes/classpath.txt", "../escaped.txt"):
                    member = tarfile.TarInfo(name)
                    member.size = len(name)
                    archive.addfile(member, io.BytesIO(name.encode()))
            subprocess.run(["zstd", "-q", "-o", str(unsafe)], input=stream.getvalue(), check=True)
            unsafe_sha256 = hashlib.sha256(unsafe.read_bytes()).hexdigest()
            for case, archive, error in (
                    ("pin mismatch", ("classes", "0" * 64, build / classes["file"]),
                     f"Archive s3://bucket{build / classes['file']} has SHA-256 {classes['sha256']}, not its pinned "
                     f"{'0' * 64}"),
                    ("unsafe entry", ("classes", unsafe_sha256, unsafe), "unsafe archive entry: '../escaped.txt'")):
                with self.subTest(case=case):
                    shutil.rmtree(root / "shared", ignore_errors=True)
                    result = self.install_host_archives(root, [archive])

                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(error, result.stderr)
                    # A refused archive leaves nothing installed, staged, or written outside its destination.
                    self.assertEqual([], list((root / "shared").iterdir()))
                    self.assertFalse((root / "escaped.txt").exists())

    def test_prerequisite_rejects_different_engine_builds(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "execution-policy.json").write_text(json.dumps({"engine_builds": {"r8i": "1" * 64}}))
            arguments = SimpleNamespace(phase="primary", campaign_id="baseline", engine_build_policy={"r8i": "2" * 64})
            with self.assertRaisesRegex(SystemExit, "engine builds its smoke prerequisite used"):
                RUN_CAMPAIGN.validate_unit_phase_prerequisite(arguments, None, root, "smoke")

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

    def test_build_phase_runs_one_engine_build_per_platform(self):
        platforms = RUN_CAMPAIGN.read_platforms(SCRIPT.parent)
        engine_build = RUN_CAMPAIGN.engine_build
        items = RUN_CAMPAIGN.build_work_items("build", platforms, engine_build.units(), [engine_build.SHARD])

        # Each platform's build is one Spot-eligible lease on its frozen instance type.
        self.assertEqual([(platform.name, platform.instance_type, engine_build.UNIT_ID, 1) for platform in platforms],
                         [(item.platform, item.instance_type, item.unit_id, item.replica) for item in items])
        self.assertTrue(all(item.estimated_seconds <= RUN_CAMPAIGN.work_units.UNIT_SECONDS for item in items))

    def test_shared_policy_checks_the_actual_aws_account_and_region(self):
        policy = {"account": "123456789012", "region": "us-east-2",
                  "regions": {"us-east-1": {"max_vcpus": 8, "spot_vcpu_reserve": 0}}}
        arguments = SimpleNamespace(shared_budget=SimpleNamespace(policy=policy), cloud=FakeCloud())
        for account, region, regions in (("234567890123", "us-east-2", None),
                                         ("123456789012", "us-west-2", None),
                                         ("123456789012", "us-east-2", ["us-east-2", "eu-west-1"])):
            arguments.platform_regions = {"r8i": regions} if regions else {}
            arguments.cloud.account_id = account
            with self.subTest(account=account, region=region, regions=regions), \
                    patch.dict(os.environ, {"AWS_REGION": region}), \
                    self.assertRaisesRegex(SystemExit, "differs from the shared fleet policy"):
                RUN_CAMPAIGN.validate_aws(arguments, [self.platform])

    def test_aws_preflight_requires_each_region_to_run_the_pinned_image(self):
        aws = FakeCloud()
        arguments = SimpleNamespace(
            shared_budget=None, purchases={"r8i": "spot"}, spot_vcpu_reserve=0, cloud=aws,
            platform_regions={"r8i": ["eu-west-1", "us-west-2", "ap-south-1"]},
            regional_amis={("r8i", "us-west-2"): "ami-test", ("r8i", "eu-west-1"): "ami-ireland"},
            transfer_buckets={"eu-west-1": "bucket-ireland"})
        aws.default_quota = 64
        # The Oregon pin names the image even when Oregon does not offer the type.
        aws.images[("us-west-2", "ami-test")] = aws.images[("eu-west-1", "ami-ireland")] = replace(
            AMAZON_LINUX, name="al2023-pinned")
        aws.offerings[("us-west-2", "r8i.2xlarge")] = aws.offerings[("ap-south-1", "r8i.2xlarge")] = set()
        aws.buckets["bucket-ireland"] = cloud.BucketInfo("eu-west-1", {"Project": "regulator-benchmark"})

        RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        self.assertEqual(arguments.ami_names, {"r8i": "al2023-pinned"})
        self.assertEqual(arguments.region_offerings, {"r8i.2xlarge": ["eu-west-1"]})

        aws.networks["ap-south-1"] = cloud.Network("vpc-ap-south-1", "sg-ap-south-1", ())
        with self.assertRaisesRegex(SystemExit, "ap-south-1 has no public default-VPC subnet"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        del aws.networks["ap-south-1"]
        aws.buckets["bucket-ireland"] = cloud.BucketInfo("us-west-2", {"Project": "regulator-benchmark"})
        with self.assertRaisesRegex(SystemExit, "bucket-ireland is not a tagged"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        aws.images[("eu-west-1", "ami-ireland")] = replace(AMAZON_LINUX, name="al2023-other")
        with self.assertRaisesRegex(SystemExit, "ami-ireland in eu-west-1 is not al2023-pinned"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        del arguments.regional_amis[("r8i", "eu-west-1")]
        with self.assertRaisesRegex(SystemExit, "no r8i AMI for eu-west-1"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])

    def test_spot_launch_takes_the_best_scored_region_with_headroom(self):
        job = RUN_CAMPAIGN.unit_worker_job(self.platform, 1, 10, "spot")
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
                arguments.purchases, arguments.spot_vcpu_reserve = {"r8i": "spot"}, 0
                arguments.platform_regions = {"r8i": regions}
                arguments.regional_amis = {("r8i", region): "ami-" + region for region in regions}
                arguments.transfer_buckets = {region: "bucket-" + region for region in regions}
                arguments.shared_budget = self.shared_fleet(["eu-west-1", "ap-south-1"])
                aws = arguments.cloud = FakeCloud()
                if scored:
                    aws.scores = {"us-west-2": 3, "eu-west-1": 9, "ap-south-1": 9}
                # Another workload's open Spot request holds Ireland's quota.
                aws.quotas[("eu-west-1", "spot")] = 8
                aws.region_requests["eu-west-1"] = [cloud.SpotRequest("sir-other", "", "r8i.2xlarge", {})]
                launched, _ = self.admit(arguments, result_root, job)
                metadata = RUN_CAMPAIGN.worker_metadata(arguments, launched, "session", "prefix")
                self.assertEqual(expected_queries, [call[1] for call in aws.calls if call[0] == "instances"])
                self.assertEqual((expected_region, expected_score, "bucket-" + expected_region),
                                 (metadata["aws_region"], metadata["placement_score"], metadata["bucket"]))

    def test_spot_placement_leaves_out_regions_that_found_no_capacity(self):
        # With no region left, the pool has no Spot placement; with one left, no score is asked for.
        for regions, failures, expected in ((["us-west-2"], ("us-west-2",), []),
                                            (["us-west-2", "eu-west-1", "ap-south-1"], ("us-west-2", "ap-south-1"),
                                             [("eu-west-1", "")])):
            with self.subTest(regions=regions):
                arguments = SimpleNamespace(platform_regions={"r8i": regions}, cloud=FakeCloud())
                job = RUN_CAMPAIGN.unit_worker_job(self.platform, 1, 10, "spot", spot_capacity_failures=failures)
                self.assertEqual(expected, RUN_CAMPAIGN.launch_placements(arguments, job))
                self.assertEqual([], arguments.cloud.calls)

    def test_each_platform_launches_in_its_own_regions_order(self):
        # The October 28, 2026 scores: r8i's three regions tie, so its list order stands, while r9g's best-scored
        # regions come first. On-Demand takes each list as given.
        platforms = {platform.name: platform for platform in RUN_CAMPAIGN.read_platforms(SCRIPT.parent)}
        arguments = SimpleNamespace(max_concurrent=64, cloud=FakeCloud(),
                                    platform_regions={"r8i": ["eu-west-1", "eu-central-1", "ap-south-1"],
                                                      "r9g": ["us-west-2", "us-east-2", "eu-central-1"]})
        arguments.cloud.scores = {"eu-west-1": 3, "eu-central-1": 3, "ap-south-1": 3, "us-west-2": 1, "us-east-2": 3}
        for name, market, expected in (
                ("r8i", "spot", ["eu-west-1", "eu-central-1", "ap-south-1"]),
                ("r9g", "spot", ["us-east-2", "eu-central-1", "us-west-2"]),
                ("r9g", "on-demand", ["us-west-2", "us-east-2", "eu-central-1"])):
            with self.subTest(platform=name, market=market):
                job = RUN_CAMPAIGN.unit_worker_job(platforms[name], 1, 10, market)
                self.assertEqual(expected, [region for region, _ in RUN_CAMPAIGN.launch_placements(arguments, job)])
        # AWS refuses to score more than 50 instances, whatever the campaign's concurrency.
        self.assertEqual({50}, {call[3] for call in arguments.cloud.calls if call[0] == "placement_scores"})

    def test_aws_preflight_uses_routed_worker_topologies(self):
        platform = replace(
            self.platform,
            instance_type="r8i.large",
            vcpus=2,
            concurrency_instance_type="r8i.2xlarge",
            concurrency_vcpus=8)
        # A unit worker runs on the instance its plan allocates, here the platform's multicore worker.
        jobs = [RUN_CAMPAIGN.unit_worker_job(replace(platform, instance_type="r8i.2xlarge", vcpus=8), 1, 1, "spot")]
        aws = FakeCloud()
        arguments = SimpleNamespace(shared_budget=None, purchases={"r8i": "spot"}, spot_vcpu_reserve=4, cloud=aws,
                                    platform_regions={"r8i": ["us-west-2"]})
        aws.quotas[("us-west-2", "spot")] = 11
        aws.images[("us-west-2", platform.ami_id)] = AMAZON_LINUX
        aws.offerings[("us-west-2", "r8i.2xlarge")] = set()
        with self.assertRaisesRegex(SystemExit, "cannot admit one host"):
            RUN_CAMPAIGN.validate_aws(arguments, [platform], jobs)

        arguments.spot_vcpu_reserve = 3
        with self.assertRaisesRegex(SystemExit, "r8i.2xlarge is not offered"):
            RUN_CAMPAIGN.validate_aws(arguments, [platform], jobs)

    def test_aws_preflight_rejects_deadline_capacity_above_effective_spot_quota(self):
        policy = {"account": "123456789012", "region": "us-west-2",
                  "max_vcpus": 64, "spot_vcpu_reserve": 16}
        aws = FakeCloud()
        arguments = SimpleNamespace(
            shared_budget=SimpleNamespace(policy=policy), purchases={"r8i": "spot"}, spot_vcpu_reserve=16, cloud=aws,
            platform_regions={"r8i": ["us-west-2"]})
        aws.quotas[("us-west-2", "spot")] = 64
        # Below the On-Demand safety reserve, which binds only a region with an allowance.
        aws.quotas[("us-west-2", "on-demand")] = 16
        aws.images[("us-west-2", self.platform.ami_id)] = AMAZON_LINUX
        with self.assertRaisesRegex(SystemExit, "max_vcpus exceeds"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        policy["max_vcpus"] = 48
        RUN_CAMPAIGN.validate_aws(arguments, [self.platform])

    @staticmethod
    def replica_items(arguments, platforms, units, seconds):
        """Three replicas of each unit on each platform, as a primary phase runs them, with their pools."""
        items = [RUN_CAMPAIGN.WorkItem(platform.name, platform.instance_type, "shard", unit, replica, seconds,
                                       (("1", seconds),))
                 for unit in units for replica in (1, 2, 3) for platform in platforms]
        arguments.unit_platforms = RUN_CAMPAIGN.pool_platforms(platforms, items)
        return items

    def test_campaign_concurrency_uses_configuration(self):
        arguments = SimpleNamespace(campaign_id="baseline", max_concurrent=512, phase="primary",
                                    platform_regions={"r8i": ["us-west-2"]})
        items = self.replica_items(arguments, [self.platform], ["unit-0001"], 900)
        RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.execute = True
        with self.assertRaisesRegex(SystemExit, "requires a shared fleet budget"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.shared_budget = SimpleNamespace(policy={"region": "us-west-2", "max_hosts": 512, "max_vcpus": 1024,
                                                          "spot_vcpu_reserve": 16})
        arguments.purchases, arguments.spot_vcpu_reserve = {"r8i": "spot"}, 16
        # Hosts read their inputs from a shared bucket in their own region.
        with self.assertRaisesRegex(SystemExit, r"bucket in every allowed region; name one for \['us-west-2'\]"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.transfer_buckets = {"us-west-2": "west"}
        # The controller names every host's prefix under the transfer prefix.
        for prefix, error in (("", "requires TRANSFER_PREFIX"), ("../another-campaign", "safe relative S3 prefix"),
                              ("prefix/", "safe relative S3 prefix")):
            with self.subTest(prefix=prefix), patch.dict(os.environ, {"TRANSFER_PREFIX": prefix}), \
                    self.assertRaisesRegex(SystemExit, error):
                RUN_CAMPAIGN.validate_configuration(arguments, items)
        self.enterContext(patch.dict(os.environ, {"TRANSFER_PREFIX": "prefix"}))
        RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.spot_vcpu_reserve = 0
        with self.assertRaisesRegex(SystemExit, "reserve matching its policy"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.spot_vcpu_reserve = 16
        # A shared fleet runs On-Demand only where its policy allows it.
        arguments.purchases = {}
        arguments.shared_budget.policy.update(regions={
            "eu-west-1": {"max_vcpus": 64, "spot_vcpu_reserve": 0, "on_demand_max_vcpus": 64}})
        with self.assertRaisesRegex(SystemExit, "allows no On-Demand in a region of r8i"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.platform_regions["r8i"].append("eu-west-1")
        arguments.transfer_buckets["eu-west-1"] = "ireland"
        RUN_CAMPAIGN.validate_configuration(arguments, items)
        # A platform launches only in regions holding its image.
        arguments.regional_amis = {("r8i", "us-west-2"): "ami", ("r9g", "eu-west-1"): "ami"}
        with self.assertRaisesRegex(SystemExit, "no r8i AMI for its regions"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.regional_amis[("r8i", "eu-west-1")] = "ami"
        RUN_CAMPAIGN.validate_configuration(arguments, items)
        # A worker's planned span runs through its setup, lifetime and uploads, then a last unit as long as its
        # pool's longest: a baseline pool's 27,000 seconds, and a slow 75,600-second unit's 101,700.
        self.assertEqual(RUN_CAMPAIGN.unit_worker_timeout(900), 27000)
        self.assertEqual(RUN_CAMPAIGN.unit_worker_timeout(75600), 101700)
        # A phase refuses a campaign ID the driver would, as the ID tags its hosts and names its S3 prefixes.
        arguments.campaign_id = "tournament.a/b"
        with self.assertRaisesRegex(SystemExit, "campaign ID must be"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.campaign_id = "baseline"
        arguments.max_concurrent = 0
        with self.assertRaisesRegex(SystemExit, "positive integer"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)

    def test_deadline_uses_shared_fleet_host_and_vcpu_limits(self):
        arguments = SimpleNamespace(campaign_id="baseline", max_concurrent=512, phase="primary",
                                    purchases={"r8i": "spot"}, spot_vcpu_reserve=0,
                                    platform_regions={"r8i": ["us-west-2"]},
                                    shared_budget=SimpleNamespace(policy={"region": "us-west-2", "max_hosts": 512,
                                                                         "max_vcpus": 8, "spot_vcpu_reserve": 0}),
                                    phase_timeout_seconds=54000)
        # 18 worker hours of units: one worker lifetime and a retry wave on three workers, but not on one.
        items = self.replica_items(arguments, [self.platform], [f"unit-{index}" for index in range(24)], 900)
        with self.assertRaisesRegex(SystemExit, "cannot fit its phase deadline"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.shared_budget.policy.update(max_vcpus=1024, max_hosts=1)
        with self.assertRaisesRegex(SystemExit, "cannot fit its phase deadline"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.shared_budget.policy["max_hosts"] = 512
        RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.shared_budget.policy["max_vcpus"] = 1
        with self.assertRaisesRegex(SystemExit, "cannot admit every worker type"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        # Another allowed region's limit admits the workers, and a wave fills every region's limit at once:
        # the three 8-vCPU workers need Oregon's one vCPU beside Ireland's 23.
        arguments.platform_regions["r8i"].append("eu-west-1")
        arguments.shared_budget.policy["regions"] = {"eu-west-1": {"max_vcpus": 23, "spot_vcpu_reserve": 0}}
        RUN_CAMPAIGN.validate_configuration(arguments, items)
        # A unit campaign's limits apply to its pools' worker hosts.
        platforms = RUN_CAMPAIGN.read_platforms(SCRIPT.parent)
        arguments.shared_budget.policy["regions"] = {}
        items = RUN_CAMPAIGN.build_work_items("primary", platforms, RUN_CAMPAIGN.work_units.load(SCRIPT.parent),
                                              ["trino-operations"])
        arguments.unit_platforms = RUN_CAMPAIGN.pool_platforms(platforms, items)
        arguments.purchases = {platform.name: "spot" for platform in platforms}
        arguments.phase_timeout_seconds = None
        with self.assertRaisesRegex(SystemExit, "cannot admit every worker type"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)

    def test_primary_topology_fits_deadline_with_retry_headroom(self):
        platforms = [
            self.platform,
            RUN_CAMPAIGN.Platform(
                "r8g", "arm", "r8g.2xlarge", 8, "ami", "jdk", "0" * 64,
                "gcc", "cmake", "glibc", "cargo", "rust", "time", "zstd"),
            RUN_CAMPAIGN.Platform(
                "r9g", "arm", "r9g.2xlarge", 8, "ami", "jdk", "0" * 64,
                "gcc", "cmake", "glibc", "cargo", "rust", "time", "zstd"),
        ]
        arguments = SimpleNamespace(campaign_id="baseline", max_concurrent=64, phase="primary")
        items = self.replica_items(arguments, platforms, [f"unit-{index}" for index in range(33)], 2700)

        RUN_CAMPAIGN.validate_configuration(arguments, items)

        arguments.max_concurrent, arguments.phase_timeout_seconds = 32, 57600
        with self.assertRaisesRegex(SystemExit, "cannot fit its phase deadline"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)

    def test_campaign_phase_maps_to_execution_protocol(self):
        arguments = SimpleNamespace(phase="smoke")
        self.assertEqual("smoke", RUN_CAMPAIGN.phase_protocol(arguments))
        arguments.smoke_protocol = "qualification"
        self.assertEqual("qualification", RUN_CAMPAIGN.phase_protocol(arguments))
        for phase in ("primary", "confirmation"):
            arguments.phase = phase
            self.assertEqual("qualification", RUN_CAMPAIGN.phase_protocol(arguments))

    def test_language_plan_runs_its_units_at_qualification_on_its_allocations(self):
        fleet = RUN_CAMPAIGN.language_fleet
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            fleet.collection.prepare(root / "source")
            plan = fleet.prepare(root / "source", root / "plan")
            units = RUN_CAMPAIGN.work_units.load(root / "plan")
            for phase, selected, replicas in (("smoke", fleet.smoke_units(root / "plan", plan, units), ("1",)),
                                              ("primary", units, ("1", "2", "3"))):
                with self.subTest(phase=phase):
                    with patch.object(sys, "argv", ["run-campaign.py", phase, "language", "--language-plan",
                                                    str(root / "plan"), "--candidate", "lifecycle=HEAD",
                                                    "--campaign-root", str(root)]):
                        arguments = RUN_CAMPAIGN.parse_args()
                    with patch.object(RUN_CAMPAIGN, "parse_args", return_value=arguments), \
                            contextlib.redirect_stdout(io.StringIO()):
                        RUN_CAMPAIGN.main()
                    self.assertEqual(RUN_CAMPAIGN.phase_protocol(arguments), "qualification")
                    self.assertEqual("language.lifecycle.lang", arguments.campaign_id)
                    with (root / "lifecycle/lang" / phase / "work-items.tsv").open(newline="") as manifest:
                        items = list(csv.DictReader(manifest, delimiter="\t"))
                    # The lifecycle suite runs on the larger r9g allocation its plan freezes.
                    self.assertEqual({(item["platform"], item["instance_type"]) for item in items},
                                     {("r9g", "r9g.2xlarge"), ("r8i", "r8i.large")})
                    self.assertEqual(sorted((item["platform"], item["unit_id"], item["replica"]) for item in items),
                                     sorted((platform, unit_id, replica) for platform in ("r8i", "r9g")
                                            for unit_id in selected for replica in replicas))
            # A campaign weights each language phase's share of the hosts by the work its controller plans, from the
            # units it checked before it started. The driver keeps weighing shares from the validated units even if
            # the plan files later change.
            arguments = SimpleNamespace(
                campaign="language", campaign_root=root, language_plan=[root / "plan"], selected_shards=None,
                smoke_protocol="smoke", spot_vcpu_reserve=16, release_version="", phase_timeout_seconds=None,
                fleet_budget=None, platform_region_values=[], purchase_values=[], transfer_bucket=[])
            plans = RUN_CAMPAIGN.validate_language_plans(arguments.language_plan)
            [_, language] = RUN_CAMPAIGN.campaign_chains(arguments, [RUN_CAMPAIGN.FrozenCandidate(
                "lifecycle", "a" * 40, "refs/benchmarks/language/lifecycle", root / "lifecycle" / "candidate", "0" * 64,
                "e" * 40)], {phase: fleet.campaign_units(plans, phase) for phase in ("smoke", "primary")})
            planned_units = (root / "plan" / "units.tsv").read_bytes()
            (root / "plan" / "units.tsv").write_text("changed after the campaign started\n")
            for step in language.steps[1:]:
                with (step.root / "work-items.tsv").open(newline="") as manifest:
                    groups = [(item, *group.split(":")) for item in csv.DictReader(manifest, delimiter="\t")
                              for group in item["groups"].split(",")]
                # A group the phase accepted leaves the work left.
                item, group_id, _ = groups[0]
                write_tsv(step.root / "accepted-units.tsv", RUN_CAMPAIGN.unit_session.UNIT_RECEIPT_FIELDS, [
                    {**dict.fromkeys(RUN_CAMPAIGN.unit_session.UNIT_RECEIPT_FIELDS, "-"), "platform": item["platform"],
                     "unit_id": item["unit_id"], "replica_id": item["replica"], "group_id": group_id,
                     "unit_attempt_id": "session/1"}])
                self.assertEqual(sum(math.ceil(float(seconds)) for _, _, seconds in groups[1:]),
                                 sum(seconds for _, seconds in step.remaining()))
            (root / "plan" / "units.tsv").write_bytes(planned_units)
            for phase, plan_count, environment, refusal in (
                    ("confirmation", 1, {}, "only smoke and primary"),
                    ("primary", 2, {}, "language plans share shard"),
                    ("primary", 1, {"BENCHMARK_HEAP_SIZE": "16g"}, "language protocol requires an 8g heap")):
                with self.subTest(refusal=refusal), \
                        patch.object(sys, "argv", ["run-campaign.py", phase, "language",
                                                   *["--language-plan", str(root / "plan")] * plan_count,
                                                   "--candidate", "lifecycle=HEAD",
                                                   "--campaign-root", str(root / "refused")]), \
                        patch.dict(os.environ, environment), self.assertRaisesRegex(SystemExit, refusal):
                    RUN_CAMPAIGN.main()
            # A campaign refuses malformed options before it plans anything. Options are never abbreviated, so --det
            # is no --detach a detached driver would pass on to itself.
            for options, refusal in (
                    (["tournament", "--candidate", "a"], "--candidate takes one NAME=COMMITISH"),
                    (["tournament", "--candidate", "a/b=HEAD"], "--candidate takes one NAME=COMMITISH"),
                    (["tour/nament", "--candidate", "a=HEAD"], "candidate a's campaign ID must be"),
                    (["tournament", "--candidate", "a=HEAD", "--platform-regions", "r8i"],
                     "--platform-regions takes one PLATFORM=VALUE"),
                    (["tournament", "--candidate", "a=HEAD", "--purchase", "r8i=cheap"], "--purchase policies are"),
                    (["tournament", "--candidate", "a=HEAD", "--max-concurrent", "0"],
                     "--max-concurrent 0 cannot give each of the campaign's 1 phase sequences"),
                    (["tournament", "--candidate", "a=HEAD", "--language-plan", str(root / "plan"),
                      "--max-concurrent-per-platform", "1"],
                     "--max-concurrent-per-platform 1 cannot give each of the campaign's 2 phase sequences"),
                    # The driver plans its language phases' units, so it refuses plans they would refuse.
                    (["tournament", "--candidate", "a=HEAD", "--language-plan", str(root / "source")],
                     f"invalid language plan {(root / 'source').resolve()}"),
                    (["tournament", "--candidate", "a=HEAD", *["--language-plan", str(root / "plan")] * 2],
                     "language plans share shard"),
                    (["tournament", "--candidate", "a=HEAD", "--execute", "--det"], "unrecognized arguments: --det")):
                errors = io.StringIO()
                with self.subTest(refusal=refusal):
                    with patch.object(sys, "argv", ["run-campaign.py", "campaign", *options,
                                                    "--campaign-root", str(root / "refused")]), \
                            contextlib.redirect_stderr(errors), self.assertRaises(SystemExit) as refused:
                        RUN_CAMPAIGN.main()
                    self.assertIn(refusal, f"{refused.exception}{errors.getvalue()}")
            self.assertFalse((root / "refused").exists())
            # A second controller of a phase stops at once, before its slow setup, while the first owns the root, with
            # the status that tells a campaign driver to adopt the first.
            errors = io.StringIO()
            fleet_policy = test_fleet_budget.TestFleetBudget()
            fleet_policy.setUp()
            self.addCleanup(fleet_policy.doCleanups)
            with patch.object(sys, "argv", ["run-campaign.py", "smoke", "language", "--language-plan",
                                            str(root / "plan"), "--candidate", "lifecycle=HEAD",
                                            "--campaign-root", str(root), "--execute",
                                            "--fleet-budget", str(fleet_policy.path), "--spot-vcpu-reserve", "0",
                                            "--purchase", "r8i=spot", "--purchase", "r9g=spot",
                                            "--transfer-bucket", f"{RUN_CAMPAIGN.default_regions()[0]}=bucket"]), \
                    patch.dict(os.environ, {"TRANSFER_PREFIX": "prefix"}), \
                    RUN_CAMPAIGN.controller_lock(root / "lifecycle/lang/smoke", RUN_CAMPAIGN.DRIVER_CHILD), \
                    patch.object(RUN_CAMPAIGN, "validate_source") as setup, contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(errors), self.assertRaises(SystemExit) as busy:
                RUN_CAMPAIGN.main()
            setup.assert_not_called()
            self.assertEqual(RUN_CAMPAIGN.CONTROLLER_BUSY_STATUS, busy.exception.code)
            self.assertIn("another controller owns", errors.getvalue())

    def test_language_primary_accepts_its_attempts_and_exports_each_plan_once(self):
        fixture = test_fleet.TestFleet()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        checkout = SCRIPT.parents[3]
        result_root, receipts = fixture.run_campaign()
        for receipt in receipts:
            # Each attempt completes as run-unit.sh finishes it.
            directory = RUN_CAMPAIGN.unit_attempt_directory(result_root, receipt)
            with (directory / "unit-capacity.txt").open("a") as capacity:
                capacity.write("wall_seconds=600\nmaximum_resident_kibibytes=1048576\nexit_status=0\n"
                               "memory_available_after_bytes=29000000000\nminimum_available_memory_bytes=3435973837\n"
                               "swap_free_after_bytes=0\noom_kills_after=0\ncapacity_status=accepted\n")
            (directory / "status.txt").write_text("status=complete\n")
        plan = RUN_CAMPAIGN.language_fleet.validate(fixture.directory)
        arguments = SimpleNamespace(
            phase="primary", campaign_id=receipts[0]["campaign_id"], heap_size="8g", max_concurrent=2,
            candidate_archive_sha256=receipts[0]["candidate_archive_sha256"], engine_tree=receipts[0]["engine_tree"],
            candidate_commit=receipts[0]["candidate_commit"], language_plans={plan["shard_id"]: fixture.directory},
            shared_budget=Mock(policy={}, policy_path="fleet.json", **{"reservations.return_value": []}))

        def validation(root=checkout, release="", classes=(("r8i", "d" * 64), ("r9g", "c" * 64))):
            """The campaign's validation, installing each platform's engine build, whose classes archive has the
            SHA-256 classes gives it, by default the one its hosts installed."""
            arguments.release_version = release
            builds, manifests = {}, {}
            for platform, sha256 in classes:
                build = builds[platform] = fixture.root / "builds" / platform / sha256
                build.mkdir(parents=True, exist_ok=True)
                (build / "engine-build.json").write_text(json.dumps({"archives": {"classes": {"sha256": sha256}}}))
                manifests[platform] = hashlib.sha256((build / "engine-build.json").read_bytes()).hexdigest()
            installing = SimpleNamespace(**vars(arguments), engine_build_directories=builds,
                                         engine_build_policy=manifests)
            return RUN_CAMPAIGN.unit_validation(root, installing, "language", {fixture.directory: plan})

        accepted = validation()
        for receipt in receipts:
            RUN_CAMPAIGN.validate_unit_artifacts(accepted, result_root, receipt)
        accepted.validate_ledger(result_root / "accepted-units.tsv", "primary")
        # The dispatcher, finding every unit accepted, passes the phase gate and exports each plan.
        campaign = RUN_CAMPAIGN.language_fleet.campaign_units({fixture.directory: plan}, "primary")
        platforms = RUN_CAMPAIGN.read_platforms(SCRIPT.parent)
        items = RUN_CAMPAIGN.build_work_items("primary", platforms, campaign["units"], sorted(campaign["allocations"]),
                                              allocations=campaign["allocations"])
        with FakeUnitFleet(fixture.root / "hosts").stubs():
            RUN_CAMPAIGN.UnitDispatcher(arguments, checkout, result_root, items, platforms, accepted).run()
        # A restart reproduces each plan's export, and refuses one that changed.
        export = result_root / f"{plan['shard_id']}-results.json"
        exported = export.read_bytes()
        RUN_CAMPAIGN.aggregate_language_plans(arguments, result_root)
        self.assertEqual(exported, export.read_bytes())
        export.write_bytes(exported + b"\n")
        with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "changed on restart"):
            RUN_CAMPAIGN.aggregate_language_plans(arguments, result_root)
        # A controller whose checkout pins other comparators, which selects a release, or whose accepted build
        # holds other classes, here r9g's holding r8i's, refuses the hosts.
        other = fixture.root / "other-checkout"
        (other / "tools/re2-benchmark/baseline").mkdir(parents=True)
        (other / "tools/re2-benchmark/baseline/comparators.tsv").write_text("changed\n")
        for refused, error in ((validation(root=other), "comparator pins or release: .*comparator_manifest_sha256"),
                               (validation(release="1.0"), "comparator pins or release: .*regulator_release_version"),
                               (validation(classes=(("r8i", "d" * 64), ("r9g", "d" * 64))),
                                f"classes archive {'c' * 64}, not its platform's accepted build's {'d' * 64}")):
            with self.subTest(error=error), self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, error):
                RUN_CAMPAIGN.validate_unit_artifacts(refused, result_root, receipts[0])

    def test_a_lone_unit_past_the_unit_budget_runs_on_demand_unless_its_platform_buys_spot(self):
        items = [RUN_CAMPAIGN.WorkItem("r8i", "r8i.large", "language-bulk", unit_id, 1, seconds, (("1", seconds),))
                 for unit_id, seconds in (("lone", 950.0), ("packed", 900.0))]
        for purchase, expected in (("spot-then-on-demand", ["r8i/lone/replica-1"]), ("spot", []),
                                   ("on-demand", ["r8i/lone/replica-1", "r8i/packed/replica-1"])):
            dispatcher = RUN_CAMPAIGN.UnitDispatcher(SimpleNamespace(phase="primary", purchases={"r8i": purchase}),
                                                     SCRIPT.parents[3], Path("/nonexistent"), items,
                                                     RUN_CAMPAIGN.read_platforms(SCRIPT.parent), None)
            self.assertEqual([item.identity for item in items if dispatcher.on_demand(item)], expected)

    def test_items_lease_by_on_demand_work_then_replica_then_work_left(self):
        items = [RUN_CAMPAIGN.WorkItem("r8i", "r8i.large", "language-bulk", unit_id, replica, seconds,
                                       (("1", seconds),))
                 for unit_id, seconds in (("short", 100.0), ("long", 600.0), ("slow", 950.0)) for replica in (1, 2)]
        dispatcher = RUN_CAMPAIGN.UnitDispatcher(
            SimpleNamespace(phase="primary", purchases={"r8i": "spot-then-on-demand"}), SCRIPT.parents[3],
            Path("/nonexistent"), items, RUN_CAMPAIGN.read_platforms(SCRIPT.parent), None)
        # On-Demand work goes first, then the lowest replica, then the longest: an On-Demand host takes both
        # replicas of the slow unit, which only it may run, before any other unit's replica 1, and a Spot host never
        # takes it.
        for market, expected in (("spot", ["long/replica-1", "short/replica-1", "long/replica-2", "short/replica-2"]),
                                 ("on-demand", ["slow/replica-1", "slow/replica-2", "long/replica-1",
                                                "short/replica-1", "long/replica-2", "short/replica-2"])):
            worker = SimpleNamespace(job=SimpleNamespace(market=market), pool=("r8i", "r8i.large"), instance_id="")
            queued, order = dispatcher.queued(worker.pool), []
            while (item := dispatcher.next_item(worker, queued, set())) is not None:
                queued.remove(item)
                order.append(item.identity)
            self.assertEqual([f"r8i/{identity}" for identity in expected], order)

    def test_launch_tries_each_zone_with_capacity_and_classifies_what_aws_refused(self):
        def full(*zones, kind="capacity", code="InsufficientInstanceCapacity"):
            return lambda region, spec: (cloud.LaunchRejected(kind, code)
                                         if spec.subnet[-1] in zones else None)

        for market, rejection, expected in (
                # A full zone moves the launch to the next; only every zone full fails it.
                ("spot", full("a"), "launched"),
                ("spot", full("a", "b", "c"), ("retryable-spot", "launch-capacity:InsufficientInstanceCapacity")),
                # A refused Spot price or quota is the Spot market's, an On-Demand quota the pool's capacity.
                ("spot", full("a", "b", "c", kind="price", code="SpotMaxPriceTooLow"),
                 ("retryable-spot", "launch-capacity:SpotMaxPriceTooLow")),
                ("on-demand", full("a", "b", "c", kind="quota", code="VcpuLimitExceeded"),
                 ("retryable-capacity", "vcpu-limit:VcpuLimitExceeded")),
                ("on-demand", full("a", "b", "c", kind="ambiguous", code="InternalError"),
                 ("retryable-infrastructure", "launch-infrastructure:InternalError")),
                # A launch that offers the type in no public subnet fails before it asks.
                ("spot", None, ("retryable-infrastructure",
                                "launch-preflight:no public default-VPC subnet offers r8i.2xlarge"))):
            with self.subTest(market=market, expected=expected), tempfile.TemporaryDirectory() as temporary:
                arguments, job, metadata, _ = self.worker(Path(temporary), language=False, market=market)
                aws = arguments.cloud = FakeCloud()
                aws.networks["us-west-2"] = cloud.Network("vpc-1", "sg-1", tuple(
                    (f"subnet-{zone}", f"us-west-2{zone}") for zone in "abc"))
                aws.offerings["us-west-2", "r8i.2xlarge"] = set() if rejection is None else {
                    f"us-west-2{zone}" for zone in "abc"}
                aws.rejections = [] if rejection is None else [rejection]
                try:
                    instance_id, zone = RUN_CAMPAIGN.launch_worker(
                        aws, job, RUN_CAMPAIGN.launch_requests(arguments, job, metadata))
                except RUN_CAMPAIGN.LaunchFailed as failure:
                    self.assertEqual(expected, (failure.classification, failure.detail))
                    continue
                self.assertEqual("launched", expected)
                launches = [call[2] for call in aws.calls if call[0] == "run_instance"]
                (instance,) = aws.region_instances["us-west-2"]
                self.assertEqual((instance.id, f"us-west-2{launches[-1].subnet[-1]}"), (instance_id, zone))
                self.assertEqual(len(launches), len({spec.subnet for spec in launches}))
                # The host is found by its session, and buys Spot at no more than the budget's rate.
                self.assertEqual(metadata["session"], instance.tags["Session"])
                self.assertEqual(("spot", "0.5", "regulator-benchmark-worker"),
                                 (launches[-1].market, launches[-1].max_price, launches[-1].profile))

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
        # Only the identity check of the prebuilt harness, whose failure is a missing engine, runs bare.
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

    def test_route_runs_its_semantic_tests_on_the_installed_candidate_classes(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()
        stage = source[source.index("regulator_classes_build=$(prebuilt_engine classes)"):
                       source.index("prepare_pinned_trino()")]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            classes = root / "shared/regulator-classes"
            # The JUnit console launcher stands in for the tests: it records its directory and arguments and
            # writes a report, then exits with STATUS.
            java = ('java() {\n'
                    '    [[ " $* " == *" org.junit.platform.console.ConsoleLauncher "* ]] || return 0\n'
                    '    printf "%s\\n" "${PWD}" "$@" > "${RESULT_DIR}/launcher-arguments"\n'
                    '    local argument\n'
                    '    for argument; do [[ ${argument} != --reports-dir=* ]] || '
                    'echo "<testsuite/>" > "${argument#--reports-dir=}/TEST-junit-jupiter.xml"; done\n'
                    '    return "${STATUS}"\n}\n')
            prefix = (f"set -euo pipefail\nRESULT_DIR={shlex.quote(str(root / 'route'))}\n"
                      f"ROOT={shlex.quote(str(root))}\nSHARED_WORK_DIR={shlex.quote(str(root / 'shared'))}\n"
                      "mkdir -p \"${RESULT_DIR}/logs\"\nROUTE=native-access\nCPU_LIST=0\nHEAP_SIZE=1g\n"
                      "semantic_tests=TestScanPlan,TestTrinoLikeCorpus\nRE2_CAMPAIGN_HOST_EPOCH=\n"
                      "taskset() { shift 2; \"$@\"; }\n" + java + shell_functions(source, "prebuilt_engine"))
            for installed, status, expected in ((True, 0, 0), (True, 1, RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS),
                                                (False, 0, 1)):
                with self.subTest(installed=installed, status=status):
                    shutil.rmtree(root / "shared", ignore_errors=True)
                    shutil.rmtree(root / "route", ignore_errors=True)
                    (root / "shared/engine-build").mkdir(parents=True)
                    classes.mkdir()
                    (classes / "classpath.txt").write_text("test-classes:classes:lib/slice-2.8.jar\n")
                    if installed:
                        (root / "shared/engine-build/classes.sha256").write_text("c" * 64 + "\n")
                    result = subprocess.run(
                        ["bash"], input=f'{prefix}STATUS={status}\n{stage}echo "${{regulator_classes_build}}"\n',
                        text=True, capture_output=True, check=False)
                    self.assertEqual(expected, result.returncode, result.stderr)
                    if expected != 0:
                        continue
                    # The route records the classes archive it ran.
                    self.assertEqual(f"prebuilt:{'c' * 64}\n", result.stdout)
                    arguments = (root / "route/launcher-arguments").read_text().splitlines()
                    # The tests run from the source root, on the installed classpath, without Maven.
                    self.assertEqual(str(root), arguments[0])
                    self.assertEqual(
                        f"{classes}/test-classes:{classes}/classes:{classes}/lib/slice-2.8.jar:"
                        f"{classes}/lib/junit-platform-console-standalone.jar",
                        arguments[arguments.index("-cp") + 1])
                    self.assertIn("--enable-native-access=ALL-UNNAMED", arguments)
                    self.assertIn(f"--scan-class-path={classes}/test-classes", arguments)
                    self.assertIn(r"--include-classname=^(.+\.)?(TestScanPlan|TestTrinoLikeCorpus)$", arguments)
                    self.assertTrue((root / "route/semantic-reports/TEST-junit-jupiter.xml").is_file())

    def test_route_fails_without_a_prebuilt_trino_harness_that_passes_its_identity_check(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prefix = self.semantic_verifier_script(source, temporary)
            # A missing harness, or one whose identity fails before any semantic check runs, fails the route as a
            # missing engine does and is retried; once the full verification runs, its failure is final. An
            # identity-only setting in the environment does not reach the full verification.
            comparator = root / "tools/re2-benchmark/trino-joni"
            comparator.mkdir(parents=True)
            (comparator / "verify.sh").write_text(
                '#!/bin/bash\nprintf fixture > "${TRINO_COMPARATOR_WORK_DIR}/classpath.txt"\n'
                '[[ "${FAIL}" != identity ]] || exit 1\n'
                '[[ "${TRINO_COMPARATOR_IDENTITY_ONLY:-false}" == true || "${FAIL}" != semantics ]] || exit 1\n')
            (comparator / "verify.sh").chmod(0o755)
            for failure, identity_only, installed, expected in (
                    ("semantics", "", True, RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS),
                    ("semantics", "export TRINO_COMPARATOR_IDENTITY_ONLY=true\n", True,
                     RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS),
                    ("identity", "", True, 1),
                    ("none", "", False, 1),
                    ("none", "", True, 0)):
                with self.subTest(failure=failure, identity_only=bool(identity_only), installed=installed):
                    shutil.rmtree(root / "shared", ignore_errors=True)
                    (root / "shared/engine-build").mkdir(parents=True)
                    (root / "shared/pinned-trino").mkdir()
                    if installed:
                        (root / "shared/engine-build/trino.sha256").write_text("a" * 64)
                    script = (prefix + identity_only + "expected_native_access=true\n"
                              + shell_functions(source, "prebuilt_engine", "prepare_pinned_trino")
                              + f"FAIL={failure} prepare_pinned_trino\n"
                              + 'printf "%s\\n" "${trino_harness_build}"\n')
                    result = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=False)
                    self.assertEqual(expected, result.returncode, result.stderr)
                    if expected == 0:
                        self.assertEqual(f"prebuilt:{'a' * 64}\n", result.stdout)

            # The real identity check passes a prebuilt harness that lists its benchmark methods, and the full
            # verification then runs each verifier once.
            shutil.rmtree(comparator)
            shutil.copytree(SCRIPT.parents[1] / "trino-joni", comparator)
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
                f'        cat {shlex.quote(str(root / "benchmarks.txt"))} ;;\n'
                f'    *) for argument; do [[ ${{argument}} != *Verifier ]] || echo "${{argument}}" '
                f'>> {shlex.quote(str(root / "verifiers"))}; '
                'done ;;\nesac\n')
            (binary / "java").chmod(0o755)
            script = (prefix + "expected_native_access=true\n"
                      + shell_functions(source, "prebuilt_engine", "prepare_pinned_trino") + "prepare_pinned_trino\n")
            result = subprocess.run(["bash"], input=script, text=True, capture_output=True, check=False,
                                    env={**os.environ, "PATH": f"{binary}{os.pathsep}{os.environ['PATH']}"})
            self.assertEqual(0, result.returncode, result.stderr)
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
            # The unit's own script, beside a stubbed planner, shard and language collector, on a host whose swap
            # is in use.
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
                "(output / 'route-plan.tsv').write_text('route\\thandler\\tsystems\\n"
                "native-access\\tjmh\\tregulator\\n')\nprint('trino-operations')\n")
            (baseline / "run-shard.sh").write_text('#!/bin/bash\nexit "${SHARD_STATUS}"\n')
            language_plan = root / "target/language-plans/language-lifecycle"
            language_plan.mkdir(parents=True)
            (language_plan / "units.tsv").write_text("unit_id\nlanguage-unit\n")
            (root / "tools/re2-benchmark/language").mkdir()
            (root / "tools/re2-benchmark/language/fleet.py").write_text(
                "import os\nimport sys\nfrom pathlib import Path\n"
                "Path(sys.argv[sys.argv.index('--unit-directory') + 1], 'collected').touch()\n"
                "sys.exit(int(os.environ['SHARD_STATUS']))\n")
            for script_path in (*binary.iterdir(), baseline / "run-unit.sh", baseline / "run-shard.sh"):
                script_path.chmod(0o755)
            environment = {name: value for name, value in os.environ.items()
                           if not name.startswith(("BASELINE_", "RE2_", "JONI_"))}
            environment.update({
                "PATH": f"{binary}{os.pathsep}{os.environ['PATH']}", "RE2_CAMPAIGN_ID": "campaign",
                "RE2_CAMPAIGN_PLATFORM": "r8i", "RE2_CAMPAIGN_HOST_EPOCH": "1",
                "RE2_ENGINEERING_ARCHITECTURE": "intel", "BASELINE_INSTANCE_ID": "i-1",
                "BASELINE_INSTANCE_TYPE": "r8i.large", "BASELINE_AVAILABILITY_ZONE": "us-west-2a",
                "RE2_CAMPAIGN_HOST_SESSION": "host-session-1", "RE2_CAMPAIGN_LEASE_SEQUENCE": "1",
                "LANGUAGE_SOURCE_ARCHIVE": str(root / "regulator.tar.gz"),
                "LANGUAGE_CANDIDATE_PROVENANCE": str(root / "candidate-provenance.tsv")})
            # A semantic failure stays the unit's verdict; any other failure, a protocol rejection among them,
            # is the capacity gate's, for a baseline unit and a language unit alike.
            for unit_id in ("unit", "language-unit"):
                for shard_status, expected in ((RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS,
                                                RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS),
                                               (RUN_CAMPAIGN.PROTOCOL_REJECTION_STATUS, 1)):
                    with self.subTest(unit=unit_id, shard_status=shard_status):
                        unit = root / f"{unit_id}-{shard_status}"
                        # The host's market comes from its environment files, which both name it.
                        unit.mkdir()
                        for name in ("environment-manifest.txt", "environment.txt"):
                            (unit / name).write_text("instance_market_type=spot\n")
                        result = subprocess.run(
                            [str(baseline / "run-unit.sh"), unit_id, "qualification", "1", "1", str(unit)],
                            env={**environment, "SHARD_STATUS": str(shard_status)},
                            capture_output=True, text=True, check=False)
                        self.assertEqual(expected, result.returncode, result.stderr)
                        self.assertEqual(unit_id == "language-unit", (unit / "collected").exists())
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

    def test_route_cpu_steal_is_the_eighth_value_of_the_aggregate_cpu_line(self):
        source = (SCRIPT.parent / "run-shard.sh").read_text()
        with tempfile.TemporaryDirectory() as temporary_directory:
            stat = Path(temporary_directory) / "stat"
            # Ticks beyond 2^31 stay exact.
            stat.write_text("cpu  4000000000 10 20 30 40 50 60 7 8 9\n"
                            "cpu0 1 2 3 4 5 6 7 8 9 10\nintr 99\n")
            function = shell_function(source, "cpu_ticks").replace("/proc/stat", str(stat))

            result = subprocess.run(["bash"], input=f"set -euo pipefail\n{function}\ncpu_ticks\n", text=True,
                                    capture_output=True, check=False)

        self.assertEqual(result.returncode, 0, result.stderr)
        # The total leaves out guest and guest_nice, which Linux already counts in user and nice.
        self.assertEqual(result.stdout, f"7 {4000000000 + 10 + 20 + 30 + 40 + 50 + 60 + 7}\n")

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

    def test_restart_rejects_a_changed_heap(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            with patch.dict(os.environ, {"BENCHMARK_HEAP_SIZE": "8g"}):
                RUN_CAMPAIGN.campaign_started_at(root / "campaign-state.tsv", self.arguments(root))
            with patch.dict(os.environ, {"BENCHMARK_HEAP_SIZE": "2g"}), \
                    self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "heap_size"):
                RUN_CAMPAIGN.campaign_started_at(root / "campaign-state.tsv", self.arguments(root))

    def test_budget_recovery_releases_only_the_epochs_no_worker_holds(self):
        job = RUN_CAMPAIGN.unit_worker_job(self.platform, 1, 10, "spot")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            attempts = result_root / "job-attempts.tsv"
            RUN_CAMPAIGN.write_attempt(attempts, job, "starting", "session=baseline-e10")
            budget = Mock()
            # Epoch 10's worker is live; the controller stopped after reserving epoch 11 but before its launch.
            budget.reservations.return_value = [
                {"campaign_id": "baseline", "logical_identity": job.logical_identity, "host_epoch": epoch,
                 "attempt": job.attempt} for epoch in (10, 11)]
            RUN_CAMPAIGN.recover_budget_reservations(
                budget, SimpleNamespace(campaign_id="baseline"), result_root, [job], attempts, {10})
            budget.release.assert_called_once_with("baseline", 11, cleanup_verified=True)
            self.assertEqual([("10", "starting", "session=baseline-e10"),
                              ("11", "retryable-infrastructure", "controller stopped before launch")],
                             [(row["host_epoch"], row["outcome"], row["detail"])
                              for row in RUN_CAMPAIGN.read_attempts(attempts)])

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

    def test_unit_ledger_recovers_retained_attempts_and_confirms_on_new_instances(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory) / "results"
            campaign = UnitCampaign(result_root, "trino-like-specialized")
            ledger = result_root / "accepted-units.tsv"
            receipt = campaign.receipts[0]
            arguments = SimpleNamespace(
                campaign_id=receipt["campaign_id"], candidate_archive_sha256=receipt["candidate_archive_sha256"],
                candidate_commit=receipt["candidate_commit"], engine_tree=receipt["engine_tree"],
                heap_size=receipt["heap_size"], phase="primary")

            def recover():
                validation = RUN_CAMPAIGN.unit_validation(SCRIPT.parents[3], arguments, "baseline")
                return RUN_CAMPAIGN.recover_accepted_units(validation, result_root, ledger)

            recover()
            # A restart revalidates the ledger against the retained attempts, one per group.
            self.assertEqual(12, len(recover()))
            self.assertEqual(
                sorted(campaign.receipts, key=RUN_CAMPAIGN.group_identity),
                sorted(RUN_CAMPAIGN.read_accepted_units(ledger), key=RUN_CAMPAIGN.group_identity))
            unit = campaign.units[0]
            confirmations = Path(temporary_directory) / "confirmation-jobs.tsv"
            confirmations.write_text(
                f"platform\tshard_id\tunit_id\nr8i\t{unit['shard_id']}\t{unit['unit_id']}\n")
            # Replica 3 measured its second group on host 5 after an interruption; the confirmation avoids
            # every instance of the split replica.
            split = [receipt for receipt in campaign.receipts
                     if (receipt["platform"], receipt["unit_id"], receipt["replica_id"], receipt["group_id"]) !=
                     ("r8i", unit["unit_id"], "3", "2")] + campaign.add("r8i", unit, 3, host=5, groups=("2",))
            self.assertEqual(
                [("r8i", unit["unit_id"], 4, frozenset({"i-r8i-1", "i-r8i-2", "i-r8i-3", "i-r8i-5"}))],
                RUN_CAMPAIGN.read_confirmation_units(
                    confirmations, [self.platform], {unit["unit_id"]: unit}, split))
            with self.assertRaisesRegex(SystemExit, "needs every group of its primary replicas"):
                RUN_CAMPAIGN.read_confirmation_units(
                    confirmations, [self.platform], {unit["unit_id"]: unit}, split[:-1])
            # A requeued replica's later completion is superseded: the first accepted attempt stays.
            first = {}
            for accepted in campaign.receipts:
                first.setdefault(RUN_CAMPAIGN.unit_logical_identity(accepted), {})[accepted["group_id"]] = accepted
            campaign.add("r8i", unit, 2, host=4)
            self.assertEqual(first, recover())
            # An accepted attempt whose retained evidence no longer holds stops the restart.
            (RUN_CAMPAIGN.unit_attempt_directory(result_root, receipt) / "status.txt").write_text(
                "status=failed\n")
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError,
                                        "unit artifacts are not accepted .*did not complete"):
                recover()

    def test_shared_fleet_launches_on_demand_where_its_allowance_has_headroom(self):
        fixture = test_fleet_budget.TestFleetBudget()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        policy = fixture.policy
        policy["regions"] = {"eu-west-1": {"max_vcpus": 64, "spot_vcpu_reserve": 0, "on_demand_max_vcpus": 64}}
        for rates in policy["hourly_rates"].values():
            rates["on_demand_total"] = 2 * rates["total"]
        fixture.path.write_text(json.dumps(policy))
        job = RUN_CAMPAIGN.Job(self.platform, "unit-worker", 1, 10, 1, "on-demand", "unit-worker")
        aws = FakeCloud()
        aws.quotas[("eu-west-1", "on-demand")] = 64
        aws.region_instances["eu-west-1"] = [cloud.Instance("i-spot", "r8i.2xlarge", "spot", "running", "", {}, 0),
                                             cloud.Instance("i-other", "r8i.2xlarge", "on-demand", "running", "", {}, 0)]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            arguments = self.arguments(root)
            arguments.shared_budget, arguments.cloud = RUN_CAMPAIGN.FleetBudget(fixture.path), aws
            arguments.spot_vcpu_reserve, arguments.platform_regions = 0, {"r8i": ["us-west-2", "eu-west-1"]}
            launched, admitted = self.admit(arguments, root, job)
        # Oregon has no allowance; Ireland's quota, less its reserve and other work, fits the host.
        self.assertEqual({"eu-west-1"}, {call[1] for call in aws.calls})
        self.assertEqual(("eu-west-1", "on-demand", True), (launched.region, launched.market, admitted))
        reserved = json.loads(arguments.shared_budget.state_path.read_text())["reservations"]
        self.assertEqual([(item["region"], item["market"], item["hourly_rate"]) for item in reserved.values()],
                         [("eu-west-1", "on-demand", 0.60)])
        snapshot = RUN_CAMPAIGN.shared_fleet_snapshot(arguments, "eu-west-1", "on-demand", {})
        self.assertEqual(snapshot["instances"],
                         [{"id": "i-other", "type": "r8i.2xlarge", "vcpus": 8, "campaign_id": None, "host_epoch": None}])

    def test_resumed_campaign_events_keep_their_columns(self):
        job = RUN_CAMPAIGN.unit_worker_job(self.platform, 1, 10, "spot")
        with tempfile.TemporaryDirectory() as temporary_directory:
            result_root = Path(temporary_directory)
            worker = RUN_CAMPAIGN.UnitWorker(job, {
                "session": "session", "aws_region": "us-east-2", "placement_score": "9",
                "instance_id": "i-0123456789abcdef0", "availability_zone": "us-east-2b"}, result_root / "session")
            events = result_root / "campaign-events.tsv"
            fields = ("event_type", "campaign_id", "platform", "shard_id", "replica_id", "instance_id",
                      "host_epoch", "reason", "artifact_uri", "artifact_sha256", "details")
            events.write_text("\t".join(fields) + "\n")

            RUN_CAMPAIGN.write_event(events, "interruption", "baseline", job, worker, "spot-interruption")

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


def user_data_settings(user_data):
    """The settings a worker's user data exports, by name."""
    body = RUN_CAMPAIGN.WORKER_USER_DATA.read_text().split("\n", 1)[1]
    exports = shlex.split(user_data[user_data.index("\n") + 1:len(user_data) - len(body)])
    return dict(token.split("=", 1) for token in exports if token != "export")


class FakeUnitHost:
    """A unit worker host on the fleet's instance, as its user data runs it, exchanging its leases and uploads
    through the fleet's S3: each lease runs as fixture evidence.

    The host acts each time the controller sleeps between polls. It boots once its setup, drawn by the fleet, has
    passed since its launch, then reads its next lease from S3, and once that lease has been absent for the idle
    time its user data names, claims it as its own stop, as the worker does, stopping if the claim wins and running
    the controller's lease otherwise. A lease runs its groups in order, each for the time the fleet gives it,
    uploading each group's result and marker as it finishes, and a failed group ends the lease; a host ends at most
    one lease each time it acts, and takes its next lease the time after. As the worker does, it heartbeats only as
    a lease starts, as each group completes, and as it stops or is interrupted. A script names what a lease does
    instead of completing: "interrupt" uploads an interruption bundle and exits as a Spot interruption, with the
    worker's status 43, "interrupt-progress" does so once the lease's first group finished, and "notice" does so
    before it fetches the lease. A host whose lease is scripted to "claim" claims it the moment its previous lease
    ends, as if its idle time ran out then. A host scripted to "silent-interrupt" does so with none of its heartbeat
    uploads landing, and one scripted to "silent-crash" runs the lease's first group and then fails, its heartbeats
    also lost. "stall" stops the host once its lease starts; once the controller terminates its instance, the
    lease's completion lands late, after another host completed the same replica, or never, once no other host runs,
    and with "stall-interrupt" the stopping host reports an interruption of the lease instead. With "stall-semantic"
    the late completion is a semantic failure, and with "stall-semantic-early" it lands as soon as another host
    leased the replica, before that host completes it. A host whose boot the fleet fails exits before its first
    heartbeat. A host whose boot the fleet slows stays pending until it is terminated, one the fleet leaves unlisted
    is launched without RunInstances's answer and is missing from EC2's listing while it stays unlisted, and one
    whose cleanup the fleet slows keeps shutting down once terminated until every other host has exited. Every host
    that booted uploads its host archive as it exits, as the user data's exit trap does, even one that failed during
    bootstrap, and its instance then terminates. A lease completes failed with "semantic-failure" (status 44),
    "protocol-rejection" (status 42), "fail" (status 1), or "capacity-rejected", whose capacity gate failed it with
    status 1. "corrupt-archive" uploads a result that differs from its marker's checksum, and "foreign-receipt" a
    receipt that names another attempt. "foreign-marker" completes with a marker naming another campaign, and
    "partial-marker" with one lacking its result checksum. A host whose user data names a release measures RELEASE's
    JAR, except for a lease scripted to "unreleased", which measures the candidate's own classes. The fleet's unit
    scripts give every lease of a unit, on any host, a behavior.
    """

    # Each scripted failure's exit status, as run-unit.sh reports it.
    STATUSES = {
        "semantic-failure": RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS,
        "stall-semantic": RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS,
        "stall-semantic-early": RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS,
        "protocol-rejection": RUN_CAMPAIGN.PROTOCOL_REJECTION_STATUS, "fail": 1, "capacity-rejected": 1,
    }

    def __init__(self, fleet, number, region, spec):
        settings = user_data_settings(spec.user_data)
        self.fleet = fleet
        self.number = number
        self.region = region
        self.instance_type = spec.instance_type
        self.market = spec.market
        self.platform = settings["RE2_CAMPAIGN_PLATFORM"]
        self.epoch = settings["RE2_CAMPAIGN_HOST_EPOCH"]
        self.stop_by = int(settings["HOST_STOP_BY"])
        self.architecture = settings["RE2_ENGINEERING_ARCHITECTURE"]
        self.session = settings["RE2_CAMPAIGN_HOST_SESSION"]
        self.bucket = settings["UNIT_WORKER_BUCKET"]
        self.lease_prefix = settings["UNIT_WORKER_LEASE_PREFIX"] + "/"
        self.result_prefix = settings["UNIT_WORKER_RESULT_PREFIX"] + "/"
        self.prefix = self.result_prefix.removesuffix("results/")
        self.release_version = settings["REGULATOR_RELEASE_VERSION"]
        self.idle_seconds = int(settings["UNIT_WORKER_IDLE_SECONDS"])
        self.zone = next(zone for subnet, zone in fleet.region_network(region).subnets if subnet == spec.subnet)
        self.launched_at = fleet.clock
        self.setup_seconds = fleet.setup_seconds(self)
        self.instance_id = None
        self.script = fleet.scripts.get(number, {})
        self.sequence = 1
        # Since when the host has waited for its next lease, and the lease it runs, with its groups yet to finish
        # and when the first of them does.
        self.idle_since = None
        self.running = None
        # The sequence it last found no lease for.
        self.absent = None
        self.booted = False
        self.stalled = False
        self.stopping = False
        self.terminations = 0
        self.status = None

    def tick(self):
        if self.stopping and self.status is None and not self.stalled:
            if all(host is self or host.status is not None for host in self.fleet.hosts):
                self.exit(143)
            return
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
                self.exit(143)
            elif all(host is self or host.status is not None for host in self.fleet.hosts):
                self.exit(143)
            return
        if self.status is not None or self.stalled:
            return
        if not self.booted:
            if self.fleet.slow_boot(self) or self.fleet.clock < self.launched_at + self.setup_seconds:
                return
            self.booted = True
            self.idle_since = self.fleet.clock
            self.fleet.set_state(self, "running")
            if self.fleet.failed_boot(self):
                return self.exit(1)
        if self.running is not None:
            return self.advance()
        if self.script.get(self.sequence) == "notice":
            return self.interrupt(None)
        lease = self.next_lease()
        if lease is None and self.fleet.clock - self.idle_since >= self.idle_seconds:
            lease = self.claim()
        if lease is None:
            if self.absent != self.sequence:
                self.absent = self.sequence
                self.fleet.log.append(("absent", self.session, self.sequence))
            return
        if lease.get("stop"):
            return self.stop()
        behavior = self.script.get(self.sequence)
        self.heartbeat(lease["unit_id"])
        if behavior in ("interrupt", "silent-interrupt"):
            return self.interrupt(lease["unit_id"])
        if behavior == "silent-crash":
            self.run_group(lease, lease["groups"][0], None)
            return self.exit(1)
        if behavior == "interrupt-progress":
            self.run_group(lease, lease["groups"][0], None)
            return self.interrupt(lease["unit_id"], lease["groups"][:1], lease["groups"][1])
        if behavior in ("stall", "stall-interrupt", "stall-semantic", "stall-semantic-early"):
            self.stalled = True
            return
        self.run(lease)

    def interrupt(self, unit_id, finished=(), running=None):
        notice = json.dumps({"schema_version": 2, "sequence": self.sequence, "unit_id": unit_id,
                             "finished_groups": list(finished), "running_group": running}).encode()
        bundle = io.BytesIO()
        with tarfile.open(fileobj=bundle, mode="w:gz") as archive:
            member = tarfile.TarInfo("interruption.json")
            member.size = len(notice)
            archive.addfile(member, io.BytesIO(notice))
        self.put(f"events/{self.sequence}-interrupted.tar.gz", bundle.getvalue())
        self.exit(RUN_CAMPAIGN.UNIT_WORKER_INTERRUPTED_STATUS)

    def heartbeat(self, unit_id):
        if {"silent-interrupt", "silent-crash"} & set(self.script.values()):
            return
        self.put(f"heartbeat/{self.sequence}.json", json.dumps({"sequence": self.sequence, "unit_id": unit_id}).encode())

    def next_lease(self):
        """The host's next lease as S3 holds it, or None."""
        lease = self.fleet.objects.get((self.bucket, f"{self.lease_prefix}{self.sequence}.json"))
        return None if lease is None else json.loads(lease[0])

    def claim(self):
        """Claim the next lease as the host's own stop, as an idle worker does, with S3's conditional write, which
        loses to a lease the controller wrote first; returns the lease that won."""
        key = (self.bucket, f"{self.lease_prefix}{self.sequence}.json")
        if key not in self.fleet.objects:
            self.fleet.objects[key] = (json.dumps({"schema_version": 2, "sequence": self.sequence, "stop": True,
                                                   "by": "worker"}).encode(), self.fleet.time(), None)
            self.fleet.log.append(("claim", self.session, self.sequence))
        return self.next_lease()

    def stop(self):
        """Stop as the worker does, at a stop lease or once idle: a final heartbeat, then exit."""
        self.heartbeat(None)
        self.exit(0)

    @property
    def leases(self):
        """The leases the controller wrote for this host, by sequence, whether or not its cleanup deleted them."""
        return {int(key[len(self.lease_prefix):-len(".json")]): json.loads(body)
                for (bucket, key), body in self.fleet.written_leases.items()
                if bucket == self.bucket and key.startswith(self.lease_prefix)}

    @property
    def objects(self):
        """The host's uploads, by key below its result prefix, as (body, modification time)."""
        return {key[len(self.result_prefix):]: (body, modified)
                for (bucket, key), (body, modified, _) in self.fleet.objects.items()
                if bucket == self.bucket and key.startswith(self.result_prefix)}

    def put(self, key, body, modified=None):
        self.fleet.objects[self.bucket, self.result_prefix + key] = (
            body, self.fleet.time() if modified is None else modified, None)

    def run(self, lease):
        behavior = self.script.get(self.sequence, self.fleet.unit_scripts.get(lease["unit_id"]))
        groups = list(lease["groups"])
        self.running = (lease, behavior, groups, self.fleet.clock + self.fleet.group_seconds(self, lease, groups[0]))
        self.advance()

    def advance(self):
        """Finish each group of the running lease whose time has come; a failed or last group ends the lease."""
        lease, behavior, groups, finishes = self.running
        while finishes <= self.fleet.clock:
            group = groups.pop(0)
            if self.run_group(lease, group, behavior) or not groups:
                break
            finishes += self.fleet.group_seconds(self, lease, groups[0])
        else:
            self.running = (lease, behavior, groups, finishes)
            return
        self.running = None
        self.fleet.log.append(("complete", self.session, self.sequence))
        self.sequence += 1
        self.idle_since = self.fleet.clock
        if self.script.get(self.sequence) == "claim":
            # Its idle time runs out as the lease ends, before the controller can write the next one.
            self.claim()

    def run_group(self, lease, group, behavior):
        """Measure one group of the lease and upload its result and marker; returns its exit status."""
        sequence = self.sequence
        unit = self.fleet.units[lease["unit_id"]]
        directory = self.fleet.scratch / self.session / f"lease-{sequence}" / f"group-{group}"
        directory.mkdir(parents=True)
        host = {"campaign_id": lease["campaign_id"], "platform": self.platform, "instance_id": self.instance_id,
                "host_epoch": self.epoch, "architecture": self.architecture, "instance_type": self.instance_type,
                "availability_zone": self.zone}
        attempt = f"{self.session}/{sequence + 100 if behavior == 'foreign-receipt' else sequence}"
        if self.fleet.synthetic:
            RUN_CAMPAIGN.unit_session.write_unit_receipt(directory / "unit-receipt.tsv", {
                field: "-" for field in RUN_CAMPAIGN.unit_session.UNIT_RECEIPT_FIELDS} | host | {
                "schema_version": RUN_CAMPAIGN.unit_session.UNIT_SCHEMA_VERSION, "unit_id": lease["unit_id"],
                "replica_id": str(lease["replica_id"]), "group_id": group, "unit_attempt_id": attempt,
                "region": self.region, "market": self.market, "shard_id": unit["shard_id"]})
        else:
            fixture = UnitFixture(directory, unit["shard_id"], unit["unit_index"], str(lease["replica_id"]), host,
                                  group=group, market=self.market)
            if self.release_version and behavior != "unreleased":
                measure_release(directory)
            fixture.accept(attempt)
        status = self.STATUSES.get(behavior, 0)
        if behavior == "capacity-rejected":
            capacity = directory / "unit-capacity.txt"
            capacity.write_text(capacity.read_text().replace("capacity_status=accepted", "capacity_status=rejected"))
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w:gz") as result:
            result.add(directory, arcname=".")
        prefix = f"units/{sequence}/groups/{group}"
        self.put(f"{prefix}/result.tar.gz",
                 archive.getvalue()[:-1] if behavior == "corrupt-archive" else archive.getvalue())
        marker = {
            "schema_version": 2, "host_session": self.session, "sequence": sequence,
            "campaign_id": "other-campaign" if behavior == "foreign-marker" else lease["campaign_id"],
            "phase": lease["phase"], "platform": self.platform,
            "unit_id": lease["unit_id"], "replica_id": lease["replica_id"], "group_id": group,
            "unit_attempt_id": f"{self.session}/{sequence}", "exit_status": status,
            "unit_status": "failed" if status else "complete",
            "result_sha256": hashlib.sha256(archive.getvalue()).hexdigest(), "result_bytes": len(archive.getvalue()),
            "pack_seconds": 0.5, "upload_seconds": 1.5, "upload_attempts": 1}
        if behavior == "partial-marker":
            del marker["result_sha256"]
        self.put(f"{prefix}/completion.json", json.dumps(marker).encode())
        self.heartbeat(lease["unit_id"])
        return status

    def exit(self, status):
        """Exit as the user data's trap does: upload the host archive, with the startup timeline of a host that
        began a unit, then power off, which terminates the instance."""
        if self.booted:
            results = {"re2-results/exit-status": f"{status}\n"}
            if status == 0 and self.leases:
                results["re2-results/startup-timeline.tsv"] = (
                    "step\tstarted\tfinished\nboot\t1000.000\t1000.000\npackages\t1010.000\t1100.000\n"
                    "worker\t1200.000\t1250.500\n")
            archive = io.BytesIO()
            with tarfile.open(fileobj=archive, mode="w:gz") as host:
                for name, content in results.items():
                    member = tarfile.TarInfo(name)
                    member.size = len(content)
                    host.addfile(member, io.BytesIO(content.encode()))
            self.put("host.tar.gz", archive.getvalue())
        self.status = status
        self.fleet.set_state(self, "terminated", "Client.UserInitiatedShutdown" if self.terminations
                             else "Client.InstanceInitiatedShutdown")
        self.fleet.log.append(("exit", self.session))

    def terminate(self):
        self.terminations += 1
        self.fleet.log.append(("terminate", self.session))
        if self.status is None:
            self.stopping = True
            if self.stalled or self.fleet.slow_cleanup(self):
                self.fleet.set_state(self, "shutting-down")
            else:
                self.exit(143)


class FakeUnitFleet(FakeCloud):
    """AWS with the unit worker hosts the controller launches, which exchange their leases and uploads with the
    controller through its S3.

    A host's setup takes the seconds setup_seconds gives it, none by default, so it boots the first time it acts. A
    timed fleet's hosts measure each group for its estimate; otherwise a lease takes no time. Given synthetic units,
    which own no rows to measure, a host uploads only each group's receipt, and the campaign accepts the receipt
    alone. The fleet records each controller loop's seconds and calls by operation, from one sleep between polls to the
    next, with the most keys one of its listings returned and the age of the oldest completion marker S3 holds at its
    end.
    """

    def __init__(self, scratch, scripts=None, abort_after=None, full_spot=None, failed_boot=lambda host: False,
                 unit_scripts=None, stuck_prefix=lambda host: False, unlisted=lambda host: False,
                 slow_boot=lambda host: False, slow_cleanup=lambda host: False, setup_seconds=lambda host: 0,
                 timed=False, units=None):
        super().__init__()
        self.scratch = scratch
        self.setup_seconds = setup_seconds
        self.timed = timed
        self.loops = []
        self.loop_started = (self.clock, 0)
        self.listed = 0
        self.failed_boot = failed_boot
        self.slow_boot = slow_boot
        self.slow_cleanup = slow_cleanup
        self.stuck_prefix = stuck_prefix
        self.unlisted = unlisted
        self.scripts = scripts or {}
        self.unit_scripts = unit_scripts or {}
        # The controller stops after this many polls, by raising this exception, as a crash does by default.
        self.abort_after = abort_after
        self.abort = SystemExit
        # Each platform's first Spot launches that find no capacity.
        self.full_spot = dict(full_spot or {})
        # The shared fleet's reservations a restart recovers.
        self.reservations = []
        # Every lease the controller wrote, by bucket and key.
        self.written_leases = {}
        # Every launch the controller asked for, in order, and whether AWS refused it.
        self.launches = []
        self.synthetic = units is not None
        self.units = units or RUN_CAMPAIGN.work_units.load(SCRIPT.parent)
        self.hosts = []
        self.log = []
        self.sleeps = 0
        # The digest of the tooling the controller runs.
        self.tooling = "c" * 64

    def run_instance(self, region, spec):
        if (region, spec.token) not in self.launched:
            platform = spec.tags["Platform"]
            refused = spec.market == "spot" and self.full_spot.get(platform, 0) > 0
            self.launches.append(SimpleNamespace(platform=platform, market=spec.market, region=region,
                                                 launched_at=self.clock, refused=refused))
            if refused:
                self.full_spot[platform] -= 1
                self.call("run_instance", region, spec)
                raise cloud.LaunchRejected("capacity", "InsufficientInstanceCapacity")
        host = FakeUnitHost(self, len(self.hosts) + 1, region, spec)
        host.instance_id = super().run_instance(region, spec)
        if host.instance_id not in {other.instance_id for other in self.hosts}:
            self.hosts.append(host)
        if self.unlisted(host):
            raise cloud.LaunchUnknown("RunInstances gave no answer")
        return host.instance_id

    def instances(self, region):
        hidden = {host.instance_id for host in self.hosts if self.unlisted(host)}
        return [instance for instance in super().instances(region) if instance.id not in hidden]

    def set_state(self, host, state, reason=""):
        self.region_instances[host.region] = [
            replace(instance, state=state, state_reason=reason) if instance.id == host.instance_id else instance
            for instance in self.region_instances[host.region]]

    def terminate(self, region, ids):
        self.call("terminate", region, *ids)
        for host in self.hosts:
            if host.instance_id in ids:
                host.terminate()

    def delete(self, region, bucket, keys):
        stuck = {host.prefix for host in self.hosts if self.stuck_prefix(host)}
        super().delete(region, bucket, [key for key in keys if not any(key.startswith(prefix) for prefix in stuck)])

    def put(self, region, bucket, key, body, *, if_none_match=False, sha256=None):
        written = super().put(region, bucket, key, body, if_none_match=if_none_match, sha256=sha256)
        lease = re.fullmatch(r"(?:.+/)?([^/]+)/input/leases/([0-9]+)\.json", key)
        if written and lease:
            self.written_leases[bucket, key] = body
            self.log.append(("lease", lease[1], int(lease[2]), json.loads(body).get("unit_id")))
        return written

    def list(self, region, bucket, prefix):
        listing = super().list(region, bucket, prefix)
        self.listed = max(self.listed, len(listing))
        return listing

    def group_seconds(self, host, lease, group):
        if not self.timed:
            return 0
        return RUN_CAMPAIGN.work_units.unit_group(self.units[lease["unit_id"]], group)["estimated_seconds"]

    def sleep(self, seconds):
        if seconds == RUN_CAMPAIGN.UNIT_POLL_SECONDS:
            started, calls = self.loop_started
            markers = [modified for (_, key), (_, modified, _) in self.objects.items() if key.endswith("/completion.json")]
            self.loops.append(SimpleNamespace(seconds=self.clock - started, calls=collections.Counter(
                call[0] for call in self.calls[calls:]), listed=self.listed,
                oldest_marker=self.time() - min(markers, default=self.time())))
            self.loop_started = (self.clock + seconds, len(self.calls))
            self.listed = 0
        self.clock += seconds
        if seconds != RUN_CAMPAIGN.UNIT_POLL_SECONDS:
            return
        self.sleeps += 1
        if self.sleeps > 2000:
            raise AssertionError("the controller never finished the phase")
        self.tick()
        if self.sleeps == self.abort_after:
            raise self.abort("controller stopped")

    def tick(self):
        for host in list(self.hosts):
            host.tick()

    def outage(self, seconds):
        """Let the hosts act, as often as the controller polls, for seconds with no controller running."""
        for _ in range(seconds // RUN_CAMPAIGN.UNIT_POLL_SECONDS):
            self.clock += RUN_CAMPAIGN.UNIT_POLL_SECONDS
            self.tick()

    def stubs(self):
        stack = contextlib.ExitStack()
        stack.enter_context(patch.dict(os.environ, {"TRANSFER_PREFIX": "campaign"}))
        stack.enter_context(patch.object(RUN_CAMPAIGN, "frozen_input_fingerprint", return_value=None))
        stack.enter_context(patch.object(RUN_CAMPAIGN, "tooling_digest", side_effect=lambda root: self.tooling))
        stack.enter_context(patch.object(RUN_CAMPAIGN.time, "sleep", side_effect=self.sleep))
        stack.enter_context(patch.object(RUN_CAMPAIGN.time, "monotonic", side_effect=self.monotonic))
        stack.enter_context(patch.object(RUN_CAMPAIGN.time, "time", side_effect=self.time))
        stack.enter_context(patch.object(RUN_CAMPAIGN, "ThreadPoolExecutor", side_effect=self.executor))
        if self.synthetic:
            validation = RUN_CAMPAIGN.unit_validation
            stack.enter_context(patch.object(RUN_CAMPAIGN, "validate_unit_artifacts"))
            stack.enter_context(patch.object(RUN_CAMPAIGN, "unit_validation", side_effect=lambda *args: replace(
                validation(*args), validate_ledger=lambda *_: None)))
        return stack


class TestUnitWorkers(unittest.TestCase):
    PLATFORMS = RUN_CAMPAIGN.read_platforms(SCRIPT.parent)

    def run_phase(self, root, fleet, phase, shards, max_concurrent, per_platform, platform_regions=None, purchases=None,
                  on_demand=True, deadline=None, confirmations=(), primary_results=None, release_version="",
                  extend=True, budget=None):
        """Drain the phase's work items through the fleet; return the result directory. Unless given a shared
        fleet budget, the stub budget admits every extension of a host's reservation if extend, logging each, and
        refuses every one otherwise.

        The unit attempt ledger is left in self.ledger."""
        if budget is None:
            budget = Mock(policy={"hourly_rates": {
                instance_type: {"spot": 0.2} for platform in self.PLATFORMS
                for instance_type in (platform.instance_type, platform.concurrency_instance_type)}},
                policy_path="fleet.json")
            budget.reserve.side_effect = lambda campaign, job, *_, **__: (
                on_demand or job.market == "spot", "fleet quota or pending-launch limit")

            def extend_reservation(campaign, epoch, *, until):
                fleet.log.append(("extend", epoch, until, extend))
                return extend, "fleet spending or machine-hour ceiling"

            budget.extend.side_effect = extend_reservation
            budget.reservations.return_value = fleet.reservations or []
        self.budget = budget
        regions = {region for listed in (platform_regions or {}).values() for region in listed}
        arguments = SimpleNamespace(
            phase=phase, campaign_id="campaign", campaign_root=root, max_concurrent=max_concurrent,
            max_concurrent_per_platform=per_platform, candidate_archive=root / "candidate.tar.gz",
            candidate_commit="a" * 40, candidate_archive_sha256="not-recorded", engine_tree="b" * 40, heap_size="8g",
            cloud=fleet, shared_budget=budget, selected_shards=shards, platform_regions=platform_regions or {},
            purchases=purchases or {}, topology_deadline_seconds=deadline, primary_results=primary_results,
            release_version=release_version,
            transfer_buckets={region: "bucket" for region in regions | set(RUN_CAMPAIGN.default_regions())})
        self.arguments = arguments
        with gzip.open(arguments.candidate_archive, "wb") as candidate:
            candidate.write(b"candidate")
        items = RUN_CAMPAIGN.build_work_items(phase, self.PLATFORMS, fleet.units, shards, confirmations)
        result_root = root / "results"
        result_root.mkdir(exist_ok=True)
        RUN_CAMPAIGN.write_work_items(result_root / "work-items.tsv", items)
        with fleet.stubs():
            self.validation = RUN_CAMPAIGN.unit_validation(SCRIPT.parents[3], arguments, "baseline")
            try:
                RUN_CAMPAIGN.UnitDispatcher(arguments, SCRIPT.parents[3], result_root, items, self.PLATFORMS,
                                            self.validation).run()
            finally:
                self.ledger = []
                if (result_root / "unit-attempts.tsv").exists():
                    with (result_root / "unit-attempts.tsv").open(newline="") as ledger:
                        self.ledger = list(csv.DictReader(ledger, delimiter="\t"))
        accepted = RUN_CAMPAIGN.read_accepted_units(result_root / "accepted-units.tsv")
        self.assertEqual(sorted(f"{item.identity}/group-{group}" for item in items for group, _ in item.groups),
                         sorted(RUN_CAMPAIGN.group_identity(receipt) for receipt in accepted))
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
            dispatchers.append((dispatcher, {epoch: (worker.job, worker.stop_by)
                                             for epoch, worker in dispatcher.workers.items()}))

        with patch.object(RUN_CAMPAIGN.UnitDispatcher, "recover", autospec=True, side_effect=recover_and_record):
            yield dispatchers

    def unresolved(self, result_root):
        with (result_root / "unresolved-units.tsv").open(newline="") as unresolved:
            return {(row["platform"], row["unit_id"], row["replica"]): row["detail"]
                    for row in csv.DictReader(unresolved, delimiter="\t")}

    def test_lease_writes_once_and_listing_strips_the_workers_prefix(self):
        worker = SimpleNamespace(host_session="r8i-session-1", metadata={
            "aws_region": "eu-west-1", "bucket": "bucket", "input_prefix": "campaign/r8i-session-1/input",
            "result_prefix": "campaign/r8i-session-1/results"})
        body = json.dumps({"sequence": 1, "unit_id": "unit"}, sort_keys=True) + "\n"
        lease = ("eu-west-1", "bucket", "campaign/r8i-session-1/input/leases/1.json")
        # A lease written before a restart or a lost response conflicts, and stands only if it is this one. The
        # host's claim of the sequence as its own stop wins, and the host never holds the lease.
        for case, existing, operations, outcome in (
                ("new key", None, ["put"], True),
                ("same lease", body, ["put", "get"], True),
                ("host's claim", '{"by": "worker", "sequence": 1, "stop": true}\n', ["put", "get"], False),
                ("another lease", '{"sequence": 1, "unit_id": "other"}\n', ["put", "get"],
                 "lease 1 differs from its ledger"),
                ("listing", None, ["list"], None)):
            with self.subTest(case=case):
                aws = FakeCloud()
                arguments = SimpleNamespace(cloud=aws)
                if existing is not None:
                    aws.put(*lease, existing.encode())
                aws.objects["bucket", "campaign/r8i-session-1/results/heartbeat/1.json"] = (b"{}", 1793030400.0, None)
                aws.objects["bucket", "campaign/r8i-session-10/results/heartbeat/1.json"] = (b"{}", 1793030401.0, None)
                aws.calls.clear()
                if case == "listing":
                    self.assertEqual({"heartbeat/1.json": 1793030400.0},
                                     RUN_CAMPAIGN.list_unit_worker_results(arguments, worker))
                    self.assertEqual([("list", "eu-west-1", "bucket", "campaign/r8i-session-1/results/")], aws.calls)
                    continue
                if isinstance(outcome, str):
                    with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, outcome):
                        RUN_CAMPAIGN.write_leases(arguments, worker, [(1, json.loads(body))])
                else:
                    self.assertEqual([(1, outcome)], RUN_CAMPAIGN.write_leases(arguments, worker, [(1, json.loads(body))]))
                self.assertEqual([(operation, *lease) for operation in operations], aws.calls)
                self.assertEqual((existing or body).encode(), aws.objects[lease[1:]][0])

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
            # Each stopped worker's attempt reports how long its host took from boot to its first unit.
            with (result_root / "job-attempts.tsv").open(newline="") as attempts:
                stopped = [row for row in csv.DictReader(attempts, delimiter="\t") if row["outcome"] == "stopped"]
            self.assertEqual(2, len(stopped))
            self.assertEqual({"boot_to_first_unit_seconds=250.5"}, {row["detail"] for row in stopped})
            # Each group's upload timings, as its marker reports them, are kept beside the accepted groups.
            with (result_root / unit_dispatch.UNIT_UPLOADS).open(newline="") as uploads:
                self.assertEqual(
                    sorted((receipt["unit_id"], receipt["group_id"], "1.5") for receipt in
                           RUN_CAMPAIGN.read_accepted_units(result_root / "accepted-units.tsv")),
                    sorted((row["unit_id"], row["group_id"], row["upload_seconds"])
                           for row in csv.DictReader(uploads, delimiter="\t")))

    def test_leases_wait_for_the_budget_to_cover_their_hosts_stop_times(self):
        # A host powers off at the stop time it was launched with or a lease sent it, so whatever becomes of its
        # controller, it stops within what the fleet budget covers: its reservation, as each admitted extension left
        # it, less its teardown.
        teardown = RUN_CAMPAIGN.UNIT_WORKER_TEARDOWN_SECONDS
        for extend in (True, False):
            with self.subTest(extend=extend), tempfile.TemporaryDirectory() as temporary:
                fleet = FakeUnitFleet(Path(temporary) / "hosts")
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1, extend=extend)
                hosts = {host.session: host for host in fleet.hosts}
                covered = {int(host.epoch): host.stop_by + teardown for host in fleet.hosts}
                self.assertLessEqual(covered.items(), {
                    (call.args[1].host_epoch, call.kwargs["until"]) for call in self.budget.reserve.call_args_list})
                for entry in fleet.log:
                    if entry[0] == "extend" and entry[3]:
                        covered[entry[1]] = max(covered[entry[1]], entry[2])
                    elif entry[0] == "lease" and entry[3] is not None:
                        host = hosts[entry[1]]
                        self.assertLessEqual(host.leases[entry[2]]["stop_by"] + teardown, covered[int(host.epoch)])
                for host in fleet.hosts:
                    # A lease ahead needs an extension; once refused, the next lease waits for the one before it,
                    # whose own stop time still covers it.
                    lease_ahead = (fleet.log.index(("lease", host.session, 2, host.leases[2]["unit_id"])) <
                                   fleet.log.index(("complete", host.session, 1)))
                    self.assertEqual(extend, lease_ahead)

    def test_next_phase_requires_the_phase_marked_complete_after_verified_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            fleet = FakeUnitFleet(Path(temporary) / "hosts")
            result_root = self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            with fleet.stubs():
                RUN_CAMPAIGN.validate_unit_phase_prerequisite(self.arguments, self.validation, result_root, "smoke")
                # A controller that stopped once its ledger was complete never proved its workers' cleanup.
                (result_root / RUN_CAMPAIGN.PHASE_COMPLETE).unlink()
                with self.assertRaisesRegex(SystemExit, "smoke prerequisite campaign is not accepted: "
                                                        "smoke phase has no completion marker after verified cleanup"):
                    RUN_CAMPAIGN.validate_unit_phase_prerequisite(
                        self.arguments, self.validation, result_root, "smoke")
        with tempfile.TemporaryDirectory() as temporary:
            # A worker whose prefix will not empty keeps its reservation, and its cleanup is retried, so the phase
            # is never marked complete; a controller stopped then cannot prove it either.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", stuck_prefix=lambda host: host.number == 1,
                                  abort_after=200)
            fleet.abort = RuntimeError
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError,
                                        "controller stopped; cleanup could not be proven for unit workers r8i/"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            stuck = fleet.hosts[0]
            self.assertEqual(0, stuck.status)
            self.assertNotIn(int(stuck.epoch), {call.args[1] for call in self.budget.release.call_args_list})
            self.assertFalse((Path(temporary) / "results" / RUN_CAMPAIGN.PHASE_COMPLETE).exists())

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
                # With no group of the lease finished, the whole unit runs again.
                self.assertEqual(
                    {(interrupted.leases[1]["unit_id"], ("1", "2")), (interrupted.leases[2]["unit_id"], ("1", "2"))},
                    {(replacement.leases[1]["unit_id"], tuple(replacement.leases[1]["groups"])),
                     (replacement.leases[2]["unit_id"], tuple(replacement.leases[2]["groups"]))})
                self.assertEqual(3, len(other.leases))
                with (result_root / "campaign-events.tsv").open(newline="") as events:
                    [event] = [row for row in csv.DictReader(events, delimiter="\t")
                               if row["event_type"] == "interruption"]
                self.assertEqual(("spot-interruption-notice", "us-west-2"), (event["reason"], event["region"]))
                self.assertEqual((interrupted.leases[1]["unit_id"], f"{interrupted.session}/1"),
                                 (event["unit_id"], event["unit_attempt_id"]))

    def test_interruption_after_a_finished_group_requeues_only_the_rest_on_spot(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The controller stops once it settled the interruption, before it leased the rest; a restart
            # rebuilds the rest from its ledgers.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: "interrupt-progress"}}, abort_after=3)
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            interrupted = fleet.hosts[0]
            unit_id = interrupted.leases[1]["unit_id"]
            self.assertEqual([(interrupted.session, "interrupted-progress", "1")],
                             [(row["host_session"], row["outcome"], row["groups"]) for row in self.ledger
                              if row["outcome"].startswith("interrupted")])
            fleet.abort_after = None
            with self.dispatchers() as dispatchers:
                result_root = self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            [(replacement, lease)] = [(host, lease) for host in fleet.hosts[1:] for lease in host.leases.values()
                                      if (host.platform, lease.get("unit_id")) == ("r8i", unit_id)]
            self.assertEqual(("spot", ["2"]), (replacement.market, lease["groups"]))
            # The interrupted lease spent none of the item's attempts.
            self.assertEqual({}, dispatchers[0][0].failures)
            accepted = {receipt["group_id"]: receipt["unit_attempt_id"]
                        for receipt in RUN_CAMPAIGN.read_accepted_units(result_root / "accepted-units.tsv")
                        if (receipt["platform"], receipt["unit_id"]) == ("r8i", unit_id)}
            self.assertEqual({"1": f"{interrupted.session}/1", "2": f"{replacement.session}/{lease['sequence']}"},
                             accepted)

    def test_pool_short_of_spot_capacity_retries_its_regions_and_turns_on_demand_only_after_its_wait(self):
        with tempfile.TemporaryDirectory() as temporary:
            # Both regions refuse the pool's first Spot launches, so each cools; before the wait is over the pool
            # tries Spot in every region again rather than asking for On-Demand.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", full_spot={"r8i": 2})
            result_root = self.run_phase(Path(temporary), fleet, "smoke", ["like-dfa-single-use"], 2, 1,
                                         platform_regions={"r8i": ["us-east-2", "us-west-2"]})
            first, second, third = [launch for launch in fleet.launches if launch.platform == "r8i"][:3]
            self.assertEqual([("spot", "us-east-2"), ("spot", "us-west-2"), ("spot", "us-east-2")],
                             [(host.market, host.region) for host in (first, second, third)])
            self.assertLess(third.launched_at - first.launched_at, RUN_CAMPAIGN.SPOT_SHORTAGE_WAIT_SECONDS)
            self.assertNotIn("on-demand", [call.args[1].market for call in self.budget.reserve.call_args_list])
            # Each failed launch is in the event history with its region.
            with (result_root / "campaign-events.tsv").open(newline="") as events:
                self.assertEqual(
                    [("interruption", "launch-capacity:InsufficientInstanceCapacity", region)
                     for region in ("us-east-2", "us-west-2")],
                    [(row["event_type"], row["reason"], row["region"]) for row in csv.DictReader(events, delimiter="\t")])
        with tempfile.TemporaryDirectory() as temporary:
            # Spot stays short; once the wait is over the pool launches On-Demand in its list's first region.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", full_spot={"r8i": 1000})
            result_root = self.run_phase(Path(temporary), fleet, "smoke", ["like-dfa-single-use"], 2, 1,
                                         platform_regions={"r8i": ["us-east-2", "us-west-2"]})
            launches = [launch for launch in fleet.launches if launch.platform == "r8i"]
            on_demand = next(launch for launch in launches if launch.market == "on-demand")
            self.assertEqual("us-east-2", on_demand.region)
            self.assertTrue(all(launch.market == "spot" for launch in launches[:launches.index(on_demand)]))
            self.assertGreaterEqual(on_demand.launched_at - launches[0].launched_at,
                                    RUN_CAMPAIGN.SPOT_SHORTAGE_WAIT_SECONDS)
            self.assertIn(("on-demand", "us-east-2"),
                          {(receipt["market"], receipt["region"])
                           for receipt in RUN_CAMPAIGN.read_accepted_units(result_root / "accepted-units.tsv")})

    def test_pool_launches_only_for_work_its_workers_would_start_after_a_boot(self):
        work = sum(float(unit["estimated_seconds"]) for unit in RUN_CAMPAIGN.work_units.load(SCRIPT.parent).values()
                   if unit["shard_id"] == "trino-like")
        # The first r8i worker is reclaimed as it boots, so its partner alone holds the pool's six units. A
        # replacement launches only if it would start one before that worker could.
        for boot_seconds, workers in ((RUN_CAMPAIGN.UNIT_WORKER_BOOT_SECONDS, 3), (work, 2)):
            with self.subTest(boot_seconds=boot_seconds), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(RUN_CAMPAIGN, "UNIT_WORKER_BOOT_SECONDS", boot_seconds):
                fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: "notice"}})
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like"], 4, 2)
                self.assertEqual(workers, sum(host.platform == "r8i" for host in fleet.hosts))

    def test_worker_silent_past_its_boot_deadline_hands_back_its_leases(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The second r8i worker never boots, its launch's answer is lost, and EC2 lists its instance only once the
            # first worker has finished its lease, whose unit fails. Its cleanup is slow.
            fleet = FakeUnitFleet(
                Path(temporary) / "hosts", scripts={1: {1: "fail"}}, slow_boot=lambda host: host.number == 2,
                slow_cleanup=lambda host: host.number == 2,
                unlisted=lambda host: host.number == 2 and ("complete", fleet.hosts[0].session, 1) not in fleet.log)
            with self.dispatchers() as dispatchers:
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 6, 3)
            first, silent = fleet.hosts[:2]
            failed, held = first.leases[1]["unit_id"], silent.leases[1]["unit_id"]
            # Its session's tag finds it. At its boot deadline the lease it was launched with is released without
            # spending an attempt, its host is stopped, and another host completes that unit.
            self.assertIn((silent.epoch, "started", f"instance_id={silent.instance_id};availability_zone=us-west-2a"),
                          [(row["host_epoch"], row["outcome"], row["detail"]) for row in
                           RUN_CAMPAIGN.read_attempts(Path(temporary) / "results" / "job-attempts.tsv")])
            self.assertEqual([("leased", "-"), ("released", RUN_CAMPAIGN.BOOT_DEADLINE_MISSED)],
                             [(row["outcome"], row["detail"] if row["outcome"] != "leased" else "-")
                              for row in self.ledger if row["host_session"] == silent.session])
            self.assertEqual({f"r8i/{failed}/replica-1": 1}, dispatchers[0][0].failures)
            [(replacement, sequence)] = [(host, sequence) for host in fleet.hosts[2:] for sequence, lease in
                                         host.leases.items() if (host.platform, lease.get("unit_id")) == ("r8i", held)]
            self.assertIn(("complete", replacement.session, sequence), fleet.log)
            self.assertEqual(1, silent.terminations)
            # The failed unit goes to a new host while the silent worker still cleans up.
            [retry] = [host for host in fleet.hosts[2:]
                       if (host.platform, host.leases[1].get("unit_id")) == ("r8i", failed)]
            self.assertLess(fleet.log.index(("lease", retry.session, 1, failed)),
                            fleet.log.index(("exit", silent.session)))

    def test_burst_finds_each_host_its_lease_at_boot_and_keeps_its_slack(self):
        with tempfile.TemporaryDirectory() as temporary:
            # 200 hosts of two platforms, most of which boot in about 67 s and a few in up to four minutes, measure
            # three 10-minute units each.
            boots = random.Random(5)
            setups = [boots.uniform(60, 75) if boots.random() < 0.95 else boots.uniform(120, 240) for _ in range(200)]
            units = {f"burst-{index}": {"unit_id": f"burst-{index}", "shard_id": "burst", "unit_index": str(index),
                                        "estimated_seconds": "600",
                                        "groups": [{"group_id": "1", "estimated_seconds": 600}]}
                     for index in range(1, 301)}
            fleet = FakeUnitFleet(Path(temporary) / "hosts", units=units, timed=True,
                                  setup_seconds=lambda host: setups[host.number - 1])
            self.run_phase(Path(temporary), fleet, "smoke", ["burst"], 200, 100)
            self.assertEqual(200, len(fleet.hosts))
            # Every host finds its first lease waiting as it boots, and its next as each lease ends, until the queue
            # is empty.
            sessions = {host.session: host for host in fleet.hosts}
            self.assertEqual([], [entry for entry in fleet.log if entry[0] == "absent" and
                                  "unit_id" in sessions[entry[1]].leases.get(entry[2], {})])
            # No host measuring between its heartbeats is judged lost.
            self.assertEqual([], self.leases("lost"))
            # Every loop covers all 200 workers in under 30 s, the one launching them and those accepting their
            # units among them.
            self.assertLess(max(loop.seconds for loop in fleet.loops), 30)

    def test_steady_fleet_keeps_its_slack_and_its_listings_short_for_hours(self):
        with tempfile.TemporaryDirectory() as temporary:
            # 200 hosts of two platforms measure 15-minute units for about six hours.
            units = {f"steady-{index}": {"unit_id": f"steady-{index}", "shard_id": "steady", "unit_index": str(index),
                                         "estimated_seconds": "900",
                                         "groups": [{"group_id": "1", "estimated_seconds": 900}]}
                     for index in range(1, 2301)}
            fleet = FakeUnitFleet(Path(temporary) / "hosts", units=units, timed=True,
                                  setup_seconds=lambda host: 67)
            self.run_phase(Path(temporary), fleet, "smoke", ["steady"], 200, 100)
            self.assertEqual(200, len(fleet.hosts))
            # Every host finds its next lease waiting as each lease ends, until the queue is empty.
            sessions = {host.session: host for host in fleet.hosts}
            self.assertEqual([], [entry for entry in fleet.log if entry[0] == "absent" and
                                  "unit_id" in sessions[entry[1]].leases.get(entry[2], {})])
            # Acceptance keeps up: each loop accepts, and deletes from S3, every completion the loop before it found,
            # so a session's listing holds only its current leases' objects however long it has run. Every loop,
            # even one accepting most of the fleet's units, takes under 30 s.
            self.assertLess(max(loop.oldest_marker for loop in fleet.loops), RUN_CAMPAIGN.UNIT_POLL_SECONDS)
            self.assertLessEqual(max(loop.listed for loop in fleet.loops), 4)
            self.assertLess(max(loop.seconds for loop in fleet.loops), 30)

    def test_hosts_work_through_a_controller_outage_on_their_slack(self):
        with tempfile.TemporaryDirectory() as temporary:
            # 20 hosts of two platforms measure four 15-minute units each. The controller stops seven minutes in,
            # with every host on its first lease and its second waiting, and is gone for 10 minutes, during which
            # every host finishes its first lease.
            units = {f"outage-{index}": {"unit_id": f"outage-{index}", "shard_id": "outage", "unit_index": str(index),
                                         "estimated_seconds": "900",
                                         "groups": [{"group_id": "1", "estimated_seconds": 900}]}
                     for index in range(1, 41)}
            fleet = FakeUnitFleet(Path(temporary) / "hosts", units=units, timed=True, setup_seconds=lambda host: 67,
                                  abort_after=28)
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(Path(temporary), fleet, "smoke", ["outage"], 20, 10)
            fleet.abort_after = None
            fleet.outage(600)
            self.assertEqual(20, sum(entry[0] == "complete" for entry in fleet.log))
            self.run_phase(Path(temporary), fleet, "smoke", ["outage"], 20, 10)
            self.assertEqual(20, len(fleet.hosts))
            # Each host that finished a lease found its next waiting, and none went idle and stopped itself. The
            # restarted controller leased no item twice.
            sessions = {host.session: host for host in fleet.hosts}
            self.assertEqual([], [entry for entry in fleet.log if entry[0] == "claim" or entry[0] == "absent" and
                                  "unit_id" in sessions[entry[1]].leases.get(entry[2], {})])
            leased = [(row["platform"], row["unit_id"], row["replica"]) for row in self.leases()]
            self.assertEqual((80, 80), (len(leased), len(set(leased))))

    def test_lease_written_after_the_hosts_idle_claim_requeues_its_unit_without_an_attempt(self):
        with tempfile.TemporaryDirectory() as temporary:
            # Each of the two r8i hosts takes one unit. The second's fails at once. The first boots two polls later
            # and claims its next lease as its idle stop as its own unit ends, before the controller, its unit not
            # yet accepted, leases it the failed unit as its slack.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {2: "claim"}, 2: {1: "fail"}},
                                  setup_seconds=lambda host: 40 if host.number == 1 else 0)
            with self.dispatchers() as dispatchers:
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 4, 2)
            claimed, failed = fleet.hosts[:2]
            unit = failed.leases[1]["unit_id"]
            # The controller's lease of it loses to the claim, so the unit goes to a new host having spent only
            # its failure's attempt, and the claiming host stops idle with nothing more written for it.
            self.assertEqual([("leased", unit, "-"), ("claimed", unit, RUN_CAMPAIGN.SELF_STOP_CLAIMED)],
                             [(row["outcome"], row["unit_id"], "-" if row["outcome"] == "leased" else row["detail"])
                              for row in self.ledger
                              if (row["host_session"], row["sequence"]) == (claimed.session, "2")])
            self.assertEqual(([1], 0, 0), (sorted(claimed.leases), claimed.status, claimed.terminations))
            self.assertEqual({f"r8i/{unit}/replica-1": 1}, dispatchers[0][0].failures)
            self.assertIn(("r8i", unit), {(host.platform, lease.get("unit_id"))
                                          for host in fleet.hosts[4:] for lease in host.leases.values()})

    def test_stalled_host_is_stopped_and_its_late_completion_superseded(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The host's instance keeps running, but its lease outruns its timeout from the heartbeat that started
            # it.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: "stall"}})
            self.run_phase(Path(temporary), fleet, "smoke", ["trino-like"], 4, 2)
            stalled = fleet.hosts[0]
            replacement = next(host for host in fleet.hosts[2:] if host.platform == stalled.platform and any(
                lease.get("unit_id") == stalled.leases[1]["unit_id"] for lease in host.leases.values()))
            # It is given a stop lease and terminated. Its lease is lost, spending an attempt, and the one behind it
            # is released; the completion that lands late is superseded by its replacement's.
            self.assertEqual((1, True), (stalled.terminations, stalled.leases[3]["stop"]))
            self.assertEqual([("1", "leased"), ("2", "leased"), ("3", "stop"), ("1", "lost stalled"),
                              ("2", "released stalled"), ("1", "superseded")],
                             [(row["sequence"], row["outcome"] + (f" {row['detail']}" if row["detail"] == "stalled"
                                                                  else "")) for row in self.ledger
                              if row["host_session"] == stalled.session])
            self.assertIn((replacement.session, "accepted"),
                          {(row["host_session"], row["outcome"]) for row in self.ledger})

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
            stopped = {int(host.epoch):
                       max([host.stop_by] + [lease.get("stop_by", 0) for lease in host.leases.values()])
                       for host in fleet.hosts}
            with self.dispatchers() as dispatchers:
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            self.assertEqual(2, len(fleet.hosts))
            # An adopted worker is booked to the region it was placed in.
            self.assertEqual({int(host.epoch): host.region for host in fleet.hosts},
                             {epoch: job.region for epoch, (job, _) in dispatchers[0][1].items()})
            leased = [(row["platform"], row["unit_id"], row["replica"]) for row in self.leases()]
            self.assertEqual(len(leased), len(set(leased)))
            self.assertEqual(4, len(self.leases("accepted")))
            # Each adopted worker's host stop time is the latest it was sent, at launch or in a lease, so the
            # restarted controller extends its reservation from there.
            self.assertEqual({epoch: stop_by for epoch, (_, stop_by) in dispatchers[0][1].items()},
                             {epoch: stopped[epoch] for epoch in dispatchers[0][1]})

    def test_cancelled_controller_leaves_the_fleet_running_and_a_failed_one_halts_it_until_its_restart(self):
        # Two controllers share a fleet; one stops partway, from Ctrl-C, SIGTERM, or a failure, its cleanup verified.
        def terminated(message):
            # The signal handler the controller installed, as SIGTERM would run it.
            signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)

        for name, abort, stopped, message in (("Ctrl-C", KeyboardInterrupt, KeyboardInterrupt, "controller stopped"),
                                              ("SIGTERM", terminated, KeyboardInterrupt, ""),
                                              ("failure", RuntimeError, RuntimeError, "controller stopped")):
            snapshot = {"quota": 736, "instances": [], "requests": []}
            with self.subTest(abort=name), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(RUN_CAMPAIGN, "shared_fleet_snapshot", return_value=snapshot):
                other = test_fleet_budget.TestFleetBudget()
                other.setUp()
                self.addCleanup(other.doCleanups)
                other.policy["hourly_rates"]["r9g.large"] = {"spot": 0.065, "total": 0.075}
                other.path.write_text(json.dumps(other.policy))
                other.budget, budget = (RUN_CAMPAIGN.FleetBudget(other.path) for _ in range(2))
                fleet = FakeUnitFleet(Path(temporary) / "hosts", abort_after=2)
                fleet.abort = abort
                with self.assertRaisesRegex(stopped, message), \
                        RUN_CAMPAIGN.controller_lock(Path(temporary) / "results", RUN_CAMPAIGN.DRIVER_CHILD):
                    self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1,
                                   purchases={"r8i": "spot", "r9g": "spot"}, budget=budget)
                self.assertEqual([], budget.reservations(Path(temporary) / "results"))
                if stopped is RuntimeError:
                    # A failure halts the fleet, and only its own controller's restart clears that halt.
                    other.budget.resume(other.root / "other")
                    with self.assertRaisesRegex(RuntimeError, "shared fleet is halted: controller stopped"):
                        other.reserve(1, campaign="other", now=fleet.time())
                else:
                    self.assertTrue(other.reserve(1, campaign="other", now=fleet.time())[0])
                fleet.abort_after = None
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1,
                               purchases={"r8i": "spot", "r9g": "spot"}, budget=budget)
                self.assertTrue(other.reserve(2, campaign="other", now=fleet.time())[0])

    def test_cancelled_controller_with_a_host_awaiting_cleanup_halts_the_fleet_until_its_own_restart(self):
        # A controller cancelled before its recovery adopted a host that an earlier run left halts the fleet, since
        # that host still awaits cleanup.
        fixture = test_fleet_budget.TestFleetBudget()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.reserve(1)
        RUN_CAMPAIGN.halt_and_clean_up(fixture.root / "baseline", fixture.budget, lambda: None, KeyboardInterrupt())
        with self.assertRaisesRegex(RuntimeError,
                                    "halted: KeyboardInterrupt; hosts of .* await verified cleanup") as halted:
            fixture.budget.check()
        # Another controller that observes the halt and proves its cleanup stops without recording one of its own.
        RUN_CAMPAIGN.halt_and_clean_up(fixture.root / "language", fixture.budget, lambda: None, halted.exception)
        self.assertEqual([str((fixture.root / "baseline").resolve())],
                         list(json.loads(fixture.budget.state_path.read_text())["halts"]))
        # One that cannot prove its own cleanup records its own halt, which outlasts the first owner's restart.
        def unproven():
            raise RuntimeError("cleanup could not be proven for unit workers r8i/unit-worker/replica-1")

        with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "cleanup could not be proven"):
            RUN_CAMPAIGN.halt_and_clean_up(fixture.root / "confirmation", fixture.budget, unproven, halted.exception)
        fixture.budget.release("baseline", 1, cleanup_verified=True)
        fixture.budget.resume(fixture.root / "baseline")
        with self.assertRaisesRegex(RuntimeError, "halted: .*cleanup could not be proven"):
            fixture.budget.check()

    def test_phase_launched_by_the_per_host_wrapper_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The wrapper's starting record named no session, so no instance can be found by its tag.
            (Path(temporary) / "results").mkdir()
            RUN_CAMPAIGN.write_attempt(
                Path(temporary) / "results" / "job-attempts.tsv",
                RUN_CAMPAIGN.unit_worker_job(self.PLATFORMS[0], 1, 1, "spot"), "starting",
                "region=us-west-2;placement_score=;slot=1;slot_failures=0;boot_by=1793001200;stop_by=1793004500")
            fleet = FakeUnitFleet(Path(temporary) / "hosts")
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "per-host wrapper.*run the phase in a new root"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            self.assertEqual([], fleet.hosts)

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

    def test_restart_settles_a_worker_that_exited_before_its_first_lease(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The controller stops before it settles a Spot launch that found no capacity.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", full_spot={"r8i": 1}, abort_after=1)
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            fleet.abort_after = None
            self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
            # Its restart finds no instance under the session's tag by the boot deadline, so it settles the attempt
            # as a launch that left no instance, without a lease.
            refused, *_ = fleet.launches
            self.assertEqual(("r8i", "spot", True), (refused.platform, refused.market, refused.refused))
            self.assertEqual([("starting", None), ("retryable-infrastructure", RUN_CAMPAIGN.LAUNCH_NOT_FOUND)],
                             [(row["outcome"], None if row["outcome"] == "starting" else row["detail"])
                              for row in RUN_CAMPAIGN.read_attempts(Path(temporary) / "results" / "job-attempts.tsv")
                              if row["host_epoch"] == "1"])
            self.assertEqual({"r9g", "r8i"}, {host.platform for host in fleet.hosts})
            # The leases written before its launch are released without spending an attempt.
            self.assertEqual({"1": ["leased", "released"], "2": ["leased", "released"]},
                             {sequence: [row["outcome"] for row in self.ledger
                                         if (row["host_epoch"], row["sequence"]) == ("1", sequence)]
                              for sequence in ("1", "2")})

    def test_restart_releases_a_reservation_made_while_its_worker_waited_for_capacity(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The pool buys On-Demand, which waits for the fleet's allowance.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", abort_after=3)
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1,
                               purchases={"r8i": "on-demand"}, on_demand=False)
            waiting = self.budget.reserve.call_args_list[-1].args[1]
            self.assertEqual(("r8i", "on-demand"), (waiting.platform.name, waiting.market))
            # The controller stopped after the reservation, before the launch.
            fleet.reservations = [{"campaign_id": "campaign", "logical_identity": waiting.logical_identity,
                                   "host_epoch": waiting.host_epoch, "attempt": waiting.attempt}]
            fleet.abort_after = None
            self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1,
                           purchases={"r8i": "on-demand"})
            self.budget.release.assert_any_call("campaign", waiting.host_epoch, cleanup_verified=True)
            # The On-Demand platform's every host was On-Demand.
            self.assertEqual({"on-demand"}, {host.market for host in fleet.hosts if host.platform == "r8i"})

    def test_completion_that_fails_its_checks_is_rejected_without_a_retry(self):
        # The campaign measures a release, so every other completion proves it measured the release's JAR.
        for script, detail in (
                ("corrupt-archive", "result archive does not match its completion marker"),
                ("capacity-rejected", "unit capacity gate rejected the host's resources"),
                ("foreign-receipt", "artifact gate: unit receipt names "),
                ("unreleased", "release-artifact.json")):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(RUN_CAMPAIGN.released_artifact, "manifest", return_value=RELEASE):
                fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: script}})
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
                ("foreign-marker", r"unit completion marker campaign-e[0-9]+-[0-9TZ]+/1 group 1 does not match its lease: "
                                   r"\['campaign_id'\]"),
                ("partial-marker", r"unit completion marker campaign-e[0-9]+-[0-9TZ]+/1 group 1 lacks \['result_sha256'\]")):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as temporary:
                fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {1: script}})
                with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, error):
                    self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 2, 1)
                self.budget.halt.assert_called_once()
                self.assertEqual([], [host.session for host in fleet.hosts if host.status is None])
                self.assertEqual([], self.leases("accepted"))
                self.assertEqual([], RUN_CAMPAIGN.read_accepted_units(Path(temporary) / "results/accepted-units.tsv"))

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

    def test_results_list_a_unit_lost_on_every_attempt_but_not_one_whose_retry_was_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            # One host at a time: the first r8i unit's host goes silent on each attempt, and the next unit fails once.
            attempts = RUN_CAMPAIGN.MAX_UNIT_ATTEMPTS
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={
                **{host: {1: "stall"} for host in range(1, attempts + 1)}, attempts + 1: {1: "fail"}})
            with self.assertRaisesRegex(SystemExit, "unresolved units"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 1, 1)
            lost, retried = fleet.hosts[0].leases[1]["unit_id"], fleet.hosts[attempts].leases[1]["unit_id"]
            self.assertEqual([("lost", lost)] * attempts + [("failed", retried), ("accepted", retried)],
                             [(row["outcome"], row["unit_id"]) for row in self.ledger
                              if row["platform"] == "r8i" and row["outcome"] in ("lost", "failed", "accepted")])
            # The status command's results list the lost unit with its reason: from unresolved-units.tsv once its
            # controller ended, and without it from the snapshot the controller rewrote each poll.
            result_root = Path(temporary) / "results"
            for source in ("unresolved-units.tsv", "snapshot"):
                [(_, _, results, _)] = RUN_CAMPAIGN.campaign_results(result_root)
                [unit] = [item for item in results if item["kind"] == "unit"]
                self.assertEqual(("r8i", f"{lost} replica 1"), (unit["platform"], unit["row"]))
                self.assertEqual(f"unresolved: stalled; spent its {attempts} attempts", unit["flags"][0])
                (result_root / "unresolved-units.tsv").unlink(missing_ok=True)

    def test_lease_that_finished_a_group_spends_an_attempt_though_no_heartbeat_landed(self):
        with tempfile.TemporaryDirectory() as temporary:
            # One host at a time: the first r8i unit fails on every host but its last, which finishes a group and
            # fails.
            attempts = RUN_CAMPAIGN.MAX_UNIT_ATTEMPTS
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={
                host: {1: "silent-crash" if host == attempts else "fail"} for host in range(1, attempts + 1)})
            with self.assertRaisesRegex(SystemExit, "unresolved units: r8i/.* worker exited: classification="
                                                    f"benchmark-failure; spent its {attempts} attempts"):
                self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 1, 1)
            crashed = fleet.hosts[attempts - 1]
            self.assertEqual(attempts, self.lease_count(crashed.leases[1]))
            # The lost lease records the group it finished.
            self.assertEqual([(crashed.session, "1")],
                             [(row["host_session"], row["groups"]) for row in self.leases("lost")])

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
                        self.arguments, self.validation, Path(temporary) / "results", "smoke")


if __name__ == "__main__":
    unittest.main()
