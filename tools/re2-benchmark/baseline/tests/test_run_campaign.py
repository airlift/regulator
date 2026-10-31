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
from collections import Counter
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from opentelemetry.sdk._logs.export import InMemoryLogRecordExporter, SimpleLogRecordProcessor
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter


SCRIPT = Path(__file__).parents[1] / "run-campaign.py"
sys.path.insert(0, str(SCRIPT.parent))
import cloud  # noqa: E402
import telemetry  # noqa: E402
from campaign_settings import BOOT_WINDOW_FIRST, BOOT_WINDOW_MAX, EVENT_FIELDS, EVENTS, CampaignSettings  # noqa: E402
import test_engine_build  # noqa: E402
import test_control_host  # noqa: E402
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


def campaign_settings(root, platform_regions=None, purchase=None, buckets=None, **values):
    """Seed the campaign root's settings, and record its options as campaign "campaign" with the default Spot vCPU
    reserve, as its first execution does, with values over test_fleet_budget's: each platform's launch regions, by
    default the default region for both platforms, how it buys hosts, and each of those regions with its bucket, which
    buckets names or is "bucket", and a limit of 736 vCPUs."""
    platform_regions = platform_regions or dict.fromkeys(("r8i", "r9g"), RUN_CAMPAIGN.default_regions())
    regions = dict.fromkeys(region for listed in platform_regions.values() for region in listed)
    root.mkdir(parents=True, exist_ok=True)
    settings = test_fleet_budget.seed_settings(root, **{
        "platform_regions": platform_regions, "purchase": purchase or {},
        "regions": {region: {"bucket": (buckets or {}).get(region, "bucket"), "max_vcpus": 736} for region in regions},
        **values})
    RUN_CAMPAIGN.check_campaign_options(SimpleNamespace(
        campaign="campaign", campaign_root=root, cloud=FakeCloud(), settings=settings,
        spot_vcpu_reserve=RUN_CAMPAIGN.SPOT_VCPU_SAFETY_RESERVE, phase_timeout_seconds=None, selected_shards=None,
        smoke_protocol="smoke", release_version="", language_plan=[]))
    return settings


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

    def arguments(self, root, **settings):
        """A phase's arguments, under settings seeded in its campaign root, root, with the given settings of
        campaign_settings."""
        return SimpleNamespace(
            phase="smoke",
            campaign_id="baseline",
            campaign_root=root,
            candidate_archive=root / "candidate.tar.gz",
            candidate_archive_sha256="0" * 64,
            engine_tree="d" * 40,
            spot_vcpu_reserve=0,
            settings=campaign_settings(root, **settings))

    def settings(self, **options):
        """Settings of campaign_settings seeded in a campaign root of their own."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        return campaign_settings(Path(temporary.name), **options)

    def test_hosts_are_shared_by_the_seconds_left_within_the_limits_and_each_phases_items(self):
        # The platforms split 20 hosts by their seconds left, and each platform's phases split its hosts the same
        # way, every phase with work first taking a host, so the build, whose items hold no estimate, runs beside
        # the others.
        self.assertEqual({"a/build": {"r8i": 1, "r9g": 1}, "a/primary": {"r8i": 7, "r9g": 8}, "b/smoke": {"r8i": 3}},
                         RUN_CAMPAIGN.allocate({"a/build": {"r8i": (0, 1), "r9g": (0, 1)},
                                                "a/primary": {"r8i": (9000, 40), "r9g": (9000, 40)},
                                                "b/smoke": {"r8i": (3000, 5)}}, 20, 14))
        # No platform takes past its limit, and no phase past its items, as a host runs one at a time; what a cap
        # leaves goes to the others.
        self.assertEqual({"a": {"r8i": 2}, "b": {"r8i": 4}},
                         RUN_CAMPAIGN.allocate({"a": {"r8i": (100, 2)}, "b": {"r8i": (100, 50)}}, 20, 6))
        # A pinned phase takes its hosts first, and the others share the rest.
        self.assertEqual({"a": {"r8i": 2, "r9g": 1}, "b": {"r8i": 2}},
                         RUN_CAMPAIGN.allocate({"a": {"r8i": (900, 50), "r9g": (100, 50)}, "b": {"r8i": (100, 50)}},
                                               20, 4, {"a": 3}))

        def shares(held, pinned=None, waiting=50, paused=(), draining=()):
            # Phases a and b, a with little work left and b with much, of waiting items, holding hosts. A paused
            # phase launches nothing, and neither takes nor gives up hosts; a draining one launches nothing.
            running = [SimpleNamespace(key=key, dispatcher=SimpleNamespace(
                demand=lambda seconds=seconds, items=items: {"r8i": (seconds, items)},
                holding=lambda open_only=False, held=held[key]: Counter(r8i=held),
                steering="paused" if key in paused else "draining" if key in draining else "running", share=None))
                for key, seconds, items in (("a", 100, 50), ("b", 900, waiting))]
            settings = self.settings(max_hosts=10, max_hosts_per_platform=10)
            if pinned:
                settings.change("dain", "pin", lambda current: current["phases"].update(
                    a={"state": "running", "share": pinned}))
            RUN_CAMPAIGN.Campaign.assign_shares(SimpleNamespace(arguments=SimpleNamespace(settings=settings)),
                                                running)
            return [(phase.dispatcher.share, phase.dispatcher.shed) for phase in running]

        # A phase over its target keeps the hosts it holds; the other's share grows only by what the limits leave
        # free, and once they leave none while it waits for more, the phase over its target gives up the hosts past
        # it.
        self.assertEqual([({"r8i": 6}, {"r8i": 4}), ({"r8i": 4}, {})], shares({"a": 6, "b": 0}))
        # It keeps them while the other phase needs no more, as all its work has hosts, and gives up those past its
        # pin whether or not another phase waits.
        self.assertEqual([({"r8i": 8}, {}), ({"r8i": 2}, {})], shares({"a": 8, "b": 2}, waiting=2))
        self.assertEqual([({"r8i": 8}, {"r8i": 5}), ({"r8i": 2}, {})], shares({"a": 8, "b": 2}, 3, waiting=2))
        self.assertEqual([(None, {}), ({"r8i": 2}, {})], shares({"a": 8, "b": 2}, paused="a"))
        # A draining phase whose hosts go on taking its leases gives up those a lowered limit leaves no room for.
        self.assertEqual([(None, {"r8i": 2}), ({"r8i": 4}, {})], shares({"a": 8, "b": 4}, draining="a"))

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
                settings=self.settings(platform_regions={"r8i": ["us-west-2", "us-east-2"]},
                                       buckets={"us-west-2": "west", "us-east-2": "east"}),
                engine_build_directories={"r8i": build}, engine_build_policy={"r8i": manifest}, campaign_root=build)
            jobs = [RUN_CAMPAIGN.unit_worker_job(self.platform, ordinal, 10, "spot") for ordinal in range(1, 5)]
            for stalls, expected_puts in ((1, 4), (RUN_CAMPAIGN.SHARED_ARCHIVE_UPLOAD_ATTEMPTS, None)):
                with self.subTest(stalls=stalls):
                    # Ohio already holds every archive.
                    aws = arguments.cloud = FakeCloud()
                    for name in RUN_CAMPAIGN.engine_build.ARCHIVES:
                        aws.upload("us-east-2", "east", f"shared/engine-builds/{archives[name]['sha256']}.tar.zst",
                                   build / archives[name]["file"], archives[name]["sha256"])
                    puts = []

                    def upload(region, bucket, key, path, sha256):
                        puts.append((bucket, key))
                        if len(puts) <= stalls:
                            raise RUN_CAMPAIGN.CloudError("RequestTimeout")
                        return FakeCloud.upload(aws, region, bucket, key, path, sha256)

                    with patch.object(aws, "upload", side_effect=upload), contextlib.redirect_stdout(io.StringIO()):
                        if expected_puts is None:
                            with self.assertRaisesRegex(SystemExit, "could not upload the r8i (classes|trino|native) "
                                                                    "archive to s3://west/"):
                                RUN_CAMPAIGN.upload_engine_builds(arguments, jobs)
                            continue
                        RUN_CAMPAIGN.upload_engine_builds(arguments, jobs)
                    # Every region but Ohio gets one copy of each archive a baseline unit runs, and the stalled first
                    # upload is retried.
                    self.assertEqual(sorted([("west", f"shared/engine-builds/{trino}.tar.zst"),
                                             ("west", f"shared/engine-builds/{classes}.tar.zst"),
                                             ("west", f"shared/engine-builds/{native}.tar.zst")]), sorted(set(puts)))
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
                settings=self.settings(platform_regions={"r8i": ["us-west-2", "us-east-2"]},
                                       buckets={"us-west-2": "west", "us-east-2": "east"}),
                candidate_archive=archive, candidate_archive_sha256=sha256, cloud=aws, campaign_root=Path(temporary))
            jobs = [RUN_CAMPAIGN.unit_worker_job(self.platform, ordinal, 10, "spot") for ordinal in range(1, 5)]
            with contextlib.redirect_stdout(io.StringIO()):
                RUN_CAMPAIGN.upload_candidate_archive(arguments, jobs)
                # A later phase of the campaign finds each copy in place.
                RUN_CAMPAIGN.upload_candidate_archive(arguments, jobs)
                self.assertEqual(2, sum(call[0] == "upload" for call in aws.calls))
                # Five days on, a phase puts each copy again, so the bucket's seven-day rule cannot expire it while
                # the phase runs.
                aws.clock += RUN_CAMPAIGN.SHARED_ARCHIVE_REFRESH_SECONDS
                RUN_CAMPAIGN.upload_candidate_archive(arguments, jobs)
                self.assertEqual(4, sum(call[0] == "upload" for call in aws.calls))
            key = f"shared/candidates/{sha256}.tar.gz"
            self.assertEqual({("west", key): sha256, ("east", key): sha256},
                             {name: checksum for name, (_, _, checksum) in aws.objects.items()})
            # The campaign records it once among its shared archives, for a region added later.
            self.assertEqual([(key, sha256, str(archive))], [
                (row["key"], row["sha256"], row["path"]) for row in RUN_CAMPAIGN.read_shared_archives(
                    Path(temporary) / RUN_CAMPAIGN.SHARED_ARCHIVES)])

    def admit(self, arguments, result_root, job, observed=None):
        """Place and admit one launch as the unit dispatcher does, by default in a loop of its own: the job as placed,
        and whether it was admitted."""
        candidate, _, refusal, _, _ = RUN_CAMPAIGN.admit_launch(
            arguments, result_root, result_root / "job-attempts.tsv", set(), job, 0, 0,
            {} if observed is None else observed)
        return candidate, refusal is None

    @staticmethod
    def shared_fleet(arguments):
        """The campaign's fleet budget, as its controller keeps it, under the phase's settings."""
        return RUN_CAMPAIGN.FleetBudget(arguments.campaign_root, arguments.settings, arguments.spot_vcpu_reserve)

    def test_spot_platform_waits_for_quota_and_an_on_demand_platform_refuses_spot(self):
        job = RUN_CAMPAIGN.Job(self.platform, "unit-worker", 1, 10, 1, "spot", "unit-worker")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            arguments = self.arguments(root, platform_regions={"r8i": ["us-west-2"]}, purchase={"r8i": "spot"})
            aws = arguments.cloud = FakeCloud()
            arguments.shared_budget = self.shared_fleet(arguments)
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
            self.assertIn("queued-capacity\tfleet quota",
                          (root / "job-attempts.tsv").read_text())
            # AWS raises the quota, which admission takes once its answer is five minutes old.
            aws.quotas[("us-west-2", "spot")] = 16
            second = replace(job, host_epoch=11)
            self.assertFalse(self.admit(arguments, root, second)[1])
            later = RUN_CAMPAIGN.time.monotonic() + RUN_CAMPAIGN.VCPU_QUOTA_CACHE_SECONDS
            with patch.object(RUN_CAMPAIGN.time, "monotonic", return_value=later):
                self.assertTrue(self.admit(arguments, root, second)[1])
            # A Spot platform launches no On-Demand host, and an On-Demand platform no Spot host.
            with self.assertRaisesRegex(RUN_CAMPAIGN.UnsafeCampaignError, "buys only Spot"):
                RUN_CAMPAIGN.check_purchase(arguments, replace(launched, market="on-demand"))
            arguments.settings.change("dain", "r8i buys On-Demand",
                                      lambda settings: settings["purchase"].update(r8i="on-demand"))
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

    def test_campaign_runs_every_candidates_phases_in_one_controller_as_their_steps_complete(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            campaign_root = Path(temporary_directory)
            given = Path(temporary_directory) / "settings.json"
            given.write_text(json.dumps({**test_fleet_budget.SETTINGS, "max_hosts": 16, "max_hosts_per_platform": 64}))
            arguments = SimpleNamespace(
                campaign="tournament", campaign_root=campaign_root, language_plan=[], selected_shards=["trino-like"],
                release_version="", smoke_protocol="smoke", smoke_results=None, primary_results=None,
                confirmation_jobs=None, engine_builds=None, execute=True, controller=True, spot_vcpu_reserve=32,
                phase_timeout_seconds=None, cloud=FakeCloud())
            candidates = [RUN_CAMPAIGN.FrozenCandidate(name, commit * 40, f"refs/benchmarks/tournament/{name}",
                                                       campaign_root / name / "candidate", "0" * 64, "e" * 40)
                          for name, commit in (("a", "a"), ("b", "b"))]
            frozen = {candidate.name: candidate for candidate in candidates}
            # Each phase's dispatch runs this many loops; A's smoke is slow and then fails. A's reduction lists a job.
            loops = {"a/smoke": 4}
            failing = {"a/smoke"}
            unit_id = next(unit_id for unit_id, unit in sorted(RUN_CAMPAIGN.work_units.load(SCRIPT.parent).items())
                           if unit["shard_id"] == "trino-like")
            confirmed = {"a": f"r8i\ttrino-like\t{unit_id}\n"}
            events = []

            class Dispatch:
                """A phase's dispatch, as prepare_phase begins it for the phase's namespace."""

                def __init__(self, arguments):
                    [(name, commit)] = arguments.candidates
                    self.arguments, self.loops = arguments, None
                    self.key = f"{name}/{'lang/' if arguments.language_plan else ''}{arguments.phase}"
                    self.share, self.workers, self.started_at = None, {}, 0

                def recover(self):
                    self.loops = loops.get(self.key, 1)
                    events.append(("start", self.key, self.arguments))

                def demand(self):
                    return {"r8i": (900, 1)}

                def holding(self, open_only=False):
                    return Counter()

                def deadline(self):
                    return 0

                def step(self, deadline, observed):
                    self.loops -= 1
                    return not self.loops

                def points(self, now):
                    return {}

                def finish_estimate(self):
                    return None

                def end(self):
                    events.append(("end", self.key))
                    if self.key in failing:
                        raise SystemExit("campaign completed with unresolved units")
                    root = campaign_root / self.key
                    root.mkdir(parents=True, exist_ok=True)
                    (root / "campaign-events.tsv").touch()
                    (root / RUN_CAMPAIGN.PHASE_COMPLETE).touch()

            def reduce(options):
                name = options.output_dir.parent.name
                events.append(("reduce", name, options))
                options.output_dir.mkdir(parents=True)
                (options.output_dir / "confirmation-jobs.tsv").write_text(
                    "platform\tshard_id\tunit_id\n" + confirmed.get(name, ""))
                (options.output_dir / "output-checksums.tsv").touch()

            def run(names=("a", "b")):
                events.clear()
                arguments.candidates = [(name, frozen[name].commit) for name in names]
                # Each restart repeats the command that started the campaign.
                arguments.settings = given
                controller_loops = iter(range(100))

                def sleep(_):
                    # A controller that never finishes fails rather than hangs.
                    if next(controller_loops, None) is None:
                        self.fail("the campaign's controller never finished")

                with patch.object(RUN_CAMPAIGN, "freeze_candidate",
                                  side_effect=lambda root, campaign_root, campaign, name, commitish: frozen[name]), \
                        patch.object(RUN_CAMPAIGN, "prepare_phase", side_effect=lambda arguments, root: (
                            Dispatch(arguments), None)), \
                        patch.object(RUN_CAMPAIGN.aggregate_results, "aggregate", side_effect=reduce), \
                        patch.object(RUN_CAMPAIGN, "ThreadPoolExecutor", side_effect=FakeCloud().executor), \
                        patch.object(RUN_CAMPAIGN.time, "sleep", sleep), contextlib.redirect_stdout(io.StringIO()):
                    RUN_CAMPAIGN.run_campaign(arguments, SCRIPT.parents[3])

            # A's failure stops only A, and the campaign then exits naming it, with the status the control host's
            # unit does not restart.
            errors = io.StringIO()
            with contextlib.redirect_stderr(errors), self.assertRaises(SystemExit) as failed:
                run()
            self.assertEqual(RUN_CAMPAIGN.control_host.ENDED_EXIT, failed.exception.code)
            self.assertIn("failed phases:\na/smoke: campaign completed with unresolved", errors.getvalue())
            # Having ended, the controller lists every file of the root for finish to check its copy against.
            self.assertEqual([], RUN_CAMPAIGN.control_host.manifest_problems(campaign_root))

            order = [event[:2] for event in events]
            # B's primary starts once B's smoke passed, while A's smoke still runs, each phase taking the
            # candidate's frozen commit.
            self.assertLess(order.index(("start", "b/primary")), order.index(("end", "a/smoke")))
            [primary] = [event[2] for event in events if event[:2] == ("start", "b/primary")]
            self.assertEqual(([("b", "b" * 40)], ["trino-like"]), (primary.candidates, primary.selected_shards))
            # B's reduction takes its primary's events, and with no confirmation jobs listed, B completes.
            [reduction] = [event[2] for event in events if event[:2] == ("reduce", "b")]
            self.assertEqual([campaign_root / "b" / "primary" / "campaign-events.tsv"], reduction.events)
            self.assertNotIn(("start", "a/primary"), order)
            self.assertNotIn(("start", "b/confirmation"), order)
            self.assertNotIn("b/", errors.getvalue())
            # The controller records itself in the campaign root, and each phase's state in the settings it seeded,
            # as its own changes.
            self.assertEqual(str(os.getpid()), RUN_CAMPAIGN.read_properties(
                campaign_root / RUN_CAMPAIGN.CONTROLLER_RECORD)["pid"])
            settings = CampaignSettings(campaign_root).current
            self.assertEqual({"a/build": "complete", "a/smoke": "failed", "a/primary": "pending",
                              "a/confirmation": "pending", "b/build": "complete", "b/smoke": "complete",
                              "b/primary": "complete", "b/confirmation": "complete"},
                             {key: steering["state"] for key, steering in settings["phases"].items()})
            self.assertEqual((16, {"controller"}), (settings["max_hosts"], {
                row["actor"] for row in csv.DictReader((campaign_root / EVENTS).read_text().splitlines(),
                                                       delimiter="\t") if row["change"] != "seed"}))

            def control(*command):
                with contextlib.redirect_stdout(io.StringIO()):
                    RUN_CAMPAIGN.control(RUN_CAMPAIGN.control_parser().parse_args(
                        [str(campaign_root), "--campaign", "tournament", "--actor", "dain", *command]), FakeCloud())

            # The ended campaign never starts again, as on a reboot or a replaced host, and takes no change but
            # reopen, so its root still matches its manifest, until Dain reopens it.
            with patch.object(RUN_CAMPAIGN, "process_start", return_value="Sat Oct 31 09:00:00 2026"), \
                    self.assertRaisesRegex(SystemExit, "campaign tournament has ended"):
                run()
            self.assertEqual([], events)
            with self.assertRaisesRegex(SystemExit, "campaign tournament has ended; only reopen"):
                control("set-limit", "max-hosts", "8")
            self.assertEqual([], RUN_CAMPAIGN.control_host.manifest_problems(campaign_root))

            def reopen():
                control("reopen")

            reopen()
            # A restart skips every completed step: A resumes at its smoke, and confirms the job its reduction
            # listed on the shards it measured.
            failing.clear()
            run()

            self.assertEqual([("start", "a/smoke"), ("start", "a/primary"), ("reduce", "a"),
                              ("start", "a/confirmation")], [event[:2] for event in events if event[0] != "end"])
            confirmation = events[-2][2]
            self.assertEqual((campaign_root / "a" / "reduction" / "confirmation-jobs.tsv", ["trino-like"]),
                             (confirmation.confirmation_jobs, confirmation.selected_shards))
            # A restart measures what the campaign started measuring, of the candidates it started with, with the
            # Spot vCPU reserve it started with, in the AWS account it started in, as the campaign it started as.
            reopen()
            with self.assertRaisesRegex(SystemExit, "candidates differ"):
                run(names=("a",))
            for option, value in (("selected_shards", None), ("spot_vcpu_reserve", 16), ("account_id", "1" * 12),
                                  ("campaign", "other")):
                options = arguments.cloud if option == "account_id" else arguments
                with self.subTest(option=option), patch.object(options, option, value), \
                        self.assertRaisesRegex(SystemExit, "options differ"):
                    run()
                self.assertEqual([], events)

    def test_control_commands_change_the_live_settings_in_their_actors_name(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            settings = campaign_settings(root, max_hosts=100, max_usd=50)
            # The controller has recorded its phases' states.
            settings.change("controller", "phases", lambda current: current["phases"].update({
                "a/primary": {"state": "running", "share": "auto"},
                "a/confirmation": {"state": "pending", "share": "auto"}}))
            settings.change("controller", "spending stop", lambda current: current.update(
                spending_stop={"by": "controller", "at": 1, "reason": "leak"}))

            aws = FakeCloud()

            def control(*command, actor="dain", campaign="campaign"):
                with contextlib.redirect_stdout(io.StringIO()):
                    RUN_CAMPAIGN.control(RUN_CAMPAIGN.control_parser().parse_args(
                        [str(root), "--campaign", campaign, "--actor", actor, *command]), aws)
                return settings.reload()

            region = RUN_CAMPAIGN.default_regions()[0]
            for command, actor, field, value in (
                    (["set-limit", "max-hosts", "80"], "monitor", "max_hosts", 80),
                    (["set-limit", "max-usd", "45.5"], "dain", "max_usd", 45.5),
                    (["set-limit", "region", region, "max-vcpus", "640"], "monitor", "regions",
                     {region: {"bucket": "bucket", "max_vcpus": 640}}),
                    (["set-purchase", "r9g", "on-demand"], "dain", "purchase", {"r9g": "on-demand"}),
                    (["set-share", "a/primary", "40"], "dain", "phases", {"state": "running", "share": 40}),
                    (["pause", "a/primary", "--reason", "bad AMI"], "dain", "phases",
                     {"state": "paused", "share": 40, "reason": "bad AMI"}),
                    (["drain", "a/primary"], "dain", "phases", {"state": "draining", "share": 40}),
                    (["resume", "a/primary"], "monitor", "phases", {"state": "running", "share": 40}),
                    (["set-share", "a/primary", "auto"], "dain", "phases", {"state": "running", "share": "auto"}),
                    (["clear-spending-stop"], "dain", "spending_stop", None),
                    (["authorize", "regions", "us-east-2", region], "dain", "authorized",
                     {"max_hosts": 100, "max_usd": 50, "regions": sorted(["us-east-2", region])}),
                    (["stop", "--reason", "release run done"], "dain", "stop",
                     {"by": "dain", "at": round(RUN_CAMPAIGN.time.time()), "reason": "release run done"})):
                with self.subTest(command=command):
                    current = control(*command, actor=actor)[field]
                    self.assertEqual(value, {key: item for key, item in current["a/primary"].items() if key != "since"}
                                     if field == "phases" else
                                     {key: current[key] for key in value} if field in ("regions", "purchase") else
                                     current)
            # A new region gets its shared bucket, private, tagged, and expiring objects as the checked-in rule
            # says, and every archive the campaign shared, before the platforms --platforms names list it.
            archive = root / "candidate.tar.gz"
            archive.write_bytes(b"candidate")
            sha256 = hashlib.sha256(b"candidate").hexdigest()
            key = f"shared/candidates/{sha256}.tar.gz"
            with contextlib.redirect_stdout(io.StringIO()):
                RUN_CAMPAIGN.upload_shared_archives(SimpleNamespace(cloud=aws, campaign_root=root, settings=settings),
                                                    {(region, key): (archive, sha256, "candidate archive")})
            bucket = "regulator-benchmark-123456789012-eu-central-1"
            current = control("add-region", "eu-central-1", "--max-vcpus", "256", "--platforms", "r9g")
            self.assertEqual(({"bucket": bucket, "max_vcpus": 256}, ["eu-central-1"]),
                             (current["regions"]["eu-central-1"], [
                                 region for platform, regions in current["platform_regions"].items()
                                 for region in regions if region == "eu-central-1" and platform == "r9g"]))
            self.assertNotIn("eu-central-1", current["platform_regions"]["r8i"])
            self.assertEqual((cloud.BucketInfo("eu-central-1", {"Project": "regulator-benchmark"}),
                              json.loads((SCRIPT.parents[1] / "aws" / "bucket-lifecycle.json").read_text()), sha256),
                             (aws.buckets[bucket], aws.lifecycles[bucket], aws.objects[bucket, key][2]))
            with open(root / EVENTS, newline="") as events:
                rows = list(csv.DictReader(events, delimiter="\t"))
            self.assertIn(("monitor", "a/primary running"), [(row["actor"], row["change"]) for row in rows])
            # A command the settings cannot take, its actor may not make, or the region's quota after its reserve or
            # its images cannot hold, changes nothing. The monitor runs only the commands its runbook grants.
            for command, actor, refusal in (
                    (["set-limit", "max-hosts", "101"], "monitor", "max_hosts only within the authorized"),
                    *((command, "monitor", f"only dain runs {command[0]}") for command in (
                        ["authorize", "max-hosts", "500"], ["clear-spending-stop"], ["stop"], ["pause", "a/primary"],
                        ["set-purchase", "r9g", "spot"])),
                    (["set-limit", "hosts", "5"], "dain", "set-limit takes"),
                    (["pause", "a/confirmation"], "dain", "a/confirmation is pending"),
                    (["resume", "a/primary"], "dain", "a/primary is running"),
                    (["drain", "b/primary"], "dain", "no phase b/primary"),
                    (["add-region", "eu-central-1", "--max-vcpus", "256"], "dain", "has region eu-central-1"),
                    (["add-region", "ap-south-1", "--max-vcpus", "256", "--platforms", "r8i"], "monitor",
                     "only the authorized regions"),
                    (["set-limit", "region", region, "max-vcpus", "969"], "dain",
                     "max_vcpus 969 exceeds the Standard spot quota of 1000 vCPUs after its reserve of 32"),
                    (["add-region", "us-east-2", "--max-vcpus", "969"], "dain", "max_vcpus 969 exceeds"),
                    (["add-region", "ap-northeast-2", "--max-vcpus", "256"], "dain",
                     "no r9g image in ap-northeast-2")):
                with self.subTest(command=command), self.assertRaisesRegex(SystemExit, refusal):
                    control(*command, actor=actor)
            # A command for another campaign never steers this one.
            with self.assertRaisesRegex(SystemExit, "is campaign campaign's root, not campaign other's"):
                control("set-limit", "max-hosts", "80", campaign="other")
            self.assertEqual(len(rows), len((root / EVENTS).read_text().splitlines()) - 1)
            # A refused region gets no bucket.
            self.assertEqual([bucket], list(aws.buckets))

    def test_campaign_refuses_candidates_whose_chains_share_a_campaign_id(self):
        # With a language plan, candidate a's language chain and candidate a.lang's baseline chain are both
        # tournament.a.lang, so they would reserve the fleet and number their hosts as one campaign.
        arguments = SimpleNamespace(
            campaign="tournament", campaign_root=Path("unused"), candidates=[("a", "main"), ("a.lang", "main")],
            smoke_results=None, primary_results=None, confirmation_jobs=None, engine_builds=None,
            language_plan=[Path("plan")], execute=True)
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
            # The control host records where its Grafana serves, and the settings steer each phase and hold a
            # spending stop and a stop.
            (campaign_root / "grafana-url.txt").write_text("https://control.test\n")
            campaign_settings(campaign_root).change("dain", "steer", lambda settings: settings.update(
                phases={"a/primary": {"state": "paused", "share": "auto", "reason": "bad AMI"},
                        "b/lang/smoke": {"state": "draining", "share": "auto"}},
                spending_stop={"by": "controller", "at": round(now), "reason": "instance i-1 did not terminate"},
                stop={"by": "dain", "at": round(now), "reason": "smoke over"}))
            build.mkdir(parents=True)
            (build / RUN_CAMPAIGN.PHASE_COMPLETE).write_text("complete\n")
            primary.mkdir(parents=True)
            # Four units: one accepted, one half accepted with 900 seconds left, one of 1800 seconds untouched, and
            # one a rejection left unresolved.
            write_tsv(primary / "work-items.tsv", RUN_CAMPAIGN.WORK_ITEM_FIELDS, [
                {"platform": "r8i", "instance_type": "r8i.2xlarge", "shard_id": "s", "unit_id": unit, "replica": "1",
                 "estimated_seconds": seconds, "groups": groups, "excluded_instances": "-"}
                for unit, seconds, groups in (("u1", "900", "1:600,2:300"), ("u2", "1800", "1:900,2:900"),
                                              ("u3", "1800", "1:1800"), ("u4", "600", "1:600"))])
            write_tsv(primary / "accepted-units.tsv", RUN_CAMPAIGN.unit_session.UNIT_RECEIPT_FIELDS, [
                {**dict.fromkeys(RUN_CAMPAIGN.unit_session.UNIT_RECEIPT_FIELDS, "-"), "platform": "r8i",
                 "unit_id": unit, "replica_id": "1", "group_id": group, "unit_attempt_id": f"session/{unit}"}
                for unit, group in (("u1", "1"), ("u1", "2"), ("u2", "1"))])
            # Worker 1 holds u2's lease and worker 2's lease of u4 was rejected, so only u3 is queued.
            worker = RUN_CAMPAIGN.UnitWorker(RUN_CAMPAIGN.unit_worker_job(self.platform, 1, 1, "spot"),
                                             {"session": "session-1"}, None)
            items = {item.identity: item for item in RUN_CAMPAIGN.read_work_items(primary / "work-items.tsv")}
            for epoch, unit, outcomes in ((1, "u2", ("leased",)), (2, "u4", ("leased", "rejected"))):
                worker.job = replace(worker.job, host_epoch=epoch)
                for outcome in outcomes:
                    unit_dispatch.write_unit_attempt(primary / "unit-attempts.tsv", worker, 1,
                                                     items[f"r8i/{unit}/replica-1"], outcome,
                                                     "artifact gate: bad" if outcome == "rejected" else "-")
            # A restarted controller reads the units unresolved so far from the ledgers, not those its predecessor
            # left when it ended.
            write_tsv(primary / "unresolved-units.tsv", ("platform", "unit_id", "replica", "detail"),
                      [{"platform": "r8i", "unit_id": unit, "replica": "1", "detail": "not accepted"}
                       for unit in ("u2", "u3")])
            # Candidate b's language smoke has no host yet.
            language_smoke.mkdir(parents=True)
            write_tsv(language_smoke / "work-items.tsv", RUN_CAMPAIGN.WORK_ITEM_FIELDS, [
                {"platform": "r8i", "instance_type": "r8i.2xlarge", "shard_id": "s", "unit_id": "u1", "replica": "1",
                 "estimated_seconds": "600", "groups": "1:600", "excluded_instances": "-"}])
            # The primary's three live hosts, one not yet reporting, each took a minute or so to report, as did the
            # host it completed, and the language smoke completed one host. The fleet names each phase by its key.
            def host(started, until, rate, region, market, ready=None):
                return {"owner": "a/primary", "instance_type": "r8i.2xlarge", "region": region, "market": market,
                        "started_at": now - started, "reserved_until": now + until, "hourly_rate": rate,
                        **({} if ready is None else {"reported_at": now - started + ready})}

            (campaign_root / "fleet-state.json").write_text(json.dumps({
                "completed": {"other/1": {"owner": "other", "machine_hours": 9, "hourly_rate": 1},
                              "y/1": {"owner": "b/lang/smoke", "machine_hours": 2, "hourly_rate": 0.1},
                              "x/5": {**host(7200, 0, 0.1, "us-east-2", "spot", 70), "machine_hours": 1}},
                "reservations": {"x/1": host(1800, 3600, 0.1, "us-east-2", "spot", 60),
                                 "x/2": host(600, 3000, 0.12, "us-east-2", "spot"),
                                 "x/3": host(1200, 2400, 0.3, "us-west-2", "on-demand", 80)}}))
            for seconds in ("170.0", "180.0"):
                RUN_CAMPAIGN.write_attempt(primary / "job-attempts.tsv", worker.job, "stopped",
                                           f"boot_to_first_unit_seconds={seconds}")
            # Each accepted group took twice its estimate.
            write_tsv(primary / unit_dispatch.UNIT_UPLOADS, unit_dispatch.UNIT_UPLOAD_FIELDS, [
                {**dict.fromkeys(unit_dispatch.UNIT_UPLOAD_FIELDS, "-"), "host_session": "session", "sequence": unit,
                 "group_id": group, "group_seconds": measured, "upload_seconds": seconds}
                for unit, group, measured, seconds in (("u1", "1", "1200", "3"), ("u1", "2", "600", "4"),
                                                       ("u2", "1", "1800", "5"))])
            # An earlier controller recorded no group's measured seconds.
            write_tsv(language_smoke / unit_dispatch.UNIT_UPLOADS, unit_dispatch.UNIT_UPLOAD_FIELDS[:-1], [
                {**dict.fromkeys(unit_dispatch.UNIT_UPLOAD_FIELDS[:-1], "-"), "upload_seconds": "7"}])

            # The campaign's controller runs as its record names it; status leaves the campaign's lock to any
            # controller starting meanwhile.
            with RUN_CAMPAIGN.controller_lock(campaign_root, "campaign"), \
                    patch.object(RUN_CAMPAIGN.fcntl, "flock", side_effect=AssertionError("took the campaign's lock")):
                lines = RUN_CAMPAIGN.campaign_status(campaign_root, now, timezone.utc)
                # The command reports a campaign root or one phase's root.
                for status_root, expected in ((campaign_root, lines[:7]),
                                              (primary, [f"phase {primary}", *lines[1:4], lines[6]])):
                    output = io.StringIO()
                    with patch.object(sys, "argv", ["run-campaign.py", "status", str(status_root)]), \
                            contextlib.redirect_stdout(output):
                        RUN_CAMPAIGN.main()
                    self.assertEqual(expected, output.getvalue().splitlines()[:len(expected)])
            # Its record outlives it, and once its process ID names a process started since, names no running
            # controller.
            (campaign_root / RUN_CAMPAIGN.CONTROLLER_RECORD).write_text(
                f"pid={os.getpid()}\nstarted=Thu Jan  1 00:00:00 1970\n")
            stopped = RUN_CAMPAIGN.campaign_status(campaign_root, now, timezone.utc)
            self.assertEqual(f"campaign {campaign_root}: no live controller", stopped[0])
            self.assertIn("  primary           no live controller, paused: bad AMI", stopped)
            # A controller that ended its stopped campaign listed the root's files as it ended.
            self.assertIn("stopping by dain: smoke over", stopped)
            RUN_CAMPAIGN.control_host.write_manifest(campaign_root)
            ended = RUN_CAMPAIGN.campaign_status(campaign_root, now, timezone.utc)
            self.assertIn("stopped by dain: smoke over", ended)
            # Its root, pulled to the Mac, reports the same spend and medians there, as does one whose fleet names
            # each phase by its root on the control host, as earlier controllers wrote it.
            pulled = Path(temporary_directory) / "pulled"
            shutil.copytree(campaign_root, pulled)
            self.assertEqual(ended[1:], RUN_CAMPAIGN.campaign_status(pulled, now, timezone.utc)[1:])
            state = json.loads((pulled / RUN_CAMPAIGN.STATE_FILE).read_text())
            for item in [*state["reservations"].values(), *state["completed"].values()]:
                item["owner"] = f"{RUN_CAMPAIGN.control_host.CAMPAIGN_ROOT}/{item['owner']}"
            (pulled / RUN_CAMPAIGN.STATE_FILE).write_text(json.dumps(state))
            self.assertEqual(ended[1:], RUN_CAMPAIGN.campaign_status(pulled, now, timezone.utc)[1:])

        # 2700 estimated seconds remain, twice that at the accepted groups' pace, over the three live hosts.
        self.assertEqual([
            f"campaign {campaign_root}: controller running as process {os.getpid()}",
            "Grafana   https://control.test",
            "spending stop by controller: instance i-1 did not terminate",
            "stopping by dain: smoke over",
            "candidate a at aaaaaaaaaaaa",
            "  build             complete",
            f"  primary           paused as process {os.getpid()}: bad AMI",
            "    units     1/4 accepted (3/6 groups), 1 unresolved, 1 queued",
            "    hosts     3 live, 1 not yet reporting",
            "              r8i.2xlarge us-east-2 spot: 2 live, 1 not yet reporting",
            "              r8i.2xlarge us-west-2 on-demand: 1 live",
            "    spend     $0.27 so far, $0.40 reserved",
            "    finish    about 12:10 a.m. UTC on Thu Oct 29",
            "    medians   boot to ready 70s, boot to first unit 175s, upload 4s",
            "candidate b at bbbbbbbbbbbb",
            f"  lang smoke        draining as process {os.getpid()}",
            "    units     0/1 accepted (0/1 groups), 0 unresolved, 1 queued",
            "    hosts     0 live",
            "    spend     $0.20 so far, $0.00 reserved",
            "    finish    unknown; no host runs",
            "    medians   upload 7s"], lines)

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
            # The phase failed with a unit that spent its attempts while the language primary runs on, and another
            # accepted group's retained directory is gone.
            campaign_settings(campaign_root).controller_change("a/primary failed", lambda settings: settings.update(
                phases={"a/primary": {"state": "failed", "share": "auto"}}))
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

            with RUN_CAMPAIGN.controller_lock(campaign_root, "campaign"):
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
        with patch.dict(os.environ, {"BENCHMARK_HEAP_SIZE": ""}):
            RUN_CAMPAIGN.campaign_heap_size(arguments)
        job = replace(RUN_CAMPAIGN.unit_worker_job(platform, 1, 10, market), region="us-west-2", stop_by=1800000045)
        metadata = RUN_CAMPAIGN.worker_metadata(arguments, job, "baseline-e10-20261030T000000Z",
                                                "campaigns/campaign/baseline-e10-20261030T000000Z")
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
                bundle_lines = [f"language-plan {sha256} s3://bucket/shared/language-plans/{sha256}.tar.zst"
                                for sha256 in bundles]
                self.assertEqual(
                    [f"jdk {'0' * 64} s3://bucket/shared/jdks/{'0' * 64}.tar.gz",
                     *(f"{name} {archives[name]['sha256']} s3://bucket/shared/engine-builds/"
                       f"{archives[name]['sha256']}.tar.zst" for name in expected), *bundle_lines],
                    settings["HOST_ARCHIVES"].splitlines())
                provenance = hashlib.sha256(b"provenance\n").hexdigest() if language else ""
                self.assertEqual(
                    (f"s3://bucket/shared/candidates/{provenance}.tsv" if language else "", provenance),
                    (settings["LANGUAGE_CANDIDATE_PROVENANCE_URI"], settings["LANGUAGE_CANDIDATE_PROVENANCE_SHA256"]))
                self.assertEqual((hashlib.sha256(b"candidate").hexdigest(), "smoke", "d" * 40, "8g"),
                                 tuple(settings[name] for name in (
                                     "REGULATOR_CONTENT_SHA256", "BASELINE_PROTOCOL", "BASELINE_EXPECTED_ENGINE_TREE",
                                     "BENCHMARK_HEAP_SIZE")))
                self.assertEqual("s3://bucket/campaigns/campaign/baseline-e10-20261030T000000Z/results/host.tar.gz",
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

    def test_aws_preflight_requires_each_region_to_run_the_pinned_image(self):
        aws = FakeCloud()
        arguments = SimpleNamespace(
            spot_vcpu_reserve=0, cloud=aws,
            settings=self.settings(platform_regions={"r8i": ["eu-west-1", "us-west-2", "ap-south-1"]},
                                   purchase={"r8i": "spot"}, buckets={"eu-west-1": "bucket-ireland",
                                                                      "us-west-2": "bucket-oregon",
                                                                      "ap-south-1": "bucket-mumbai"}),
            regional_amis={("r8i", "us-west-2"): "ami-test", ("r8i", "eu-west-1"): "ami-ireland"})
        arguments.settings.change("dain", "fit the quota", lambda settings: [
            limits.update(max_vcpus=64) for limits in settings["regions"].values()])
        aws.default_quota = 64
        # The Oregon pin names the image even when Oregon does not offer the type.
        aws.images[("us-west-2", "ami-test")] = aws.images[("eu-west-1", "ami-ireland")] = replace(
            AMAZON_LINUX, name="al2023-pinned")
        aws.offerings[("us-west-2", "r8i.2xlarge")] = aws.offerings[("ap-south-1", "r8i.2xlarge")] = set()
        for region, bucket in (("eu-west-1", "bucket-ireland"), ("us-west-2", "bucket-oregon"),
                               ("ap-south-1", "bucket-mumbai")):
            aws.buckets[bucket] = cloud.BucketInfo(region, {"Project": "regulator-benchmark"})

        RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        self.assertEqual(arguments.ami_names, {"r8i": "al2023-pinned"})
        self.assertEqual(arguments.region_offerings, {"r8i.2xlarge": ["eu-west-1"]})

        # A campaign's phases share what AWS showed it; a restarted controller looks again.
        def restarted():
            RUN_CAMPAIGN.validate_aws(SimpleNamespace(**{key: value for key, value in vars(arguments).items()
                                                         if key not in ("networks", "images", "zone_offerings")}),
                                      [self.platform])

        aws.networks["ap-south-1"] = cloud.Network("vpc-ap-south-1", "sg-ap-south-1", ())
        with self.assertRaisesRegex(SystemExit, "ap-south-1 has no public default-VPC subnet"):
            restarted()
        del aws.networks["ap-south-1"]
        aws.buckets["bucket-ireland"] = cloud.BucketInfo("us-west-2", {"Project": "regulator-benchmark"})
        with self.assertRaisesRegex(SystemExit, "bucket-ireland is not a tagged"):
            restarted()
        aws.images[("eu-west-1", "ami-ireland")] = replace(AMAZON_LINUX, name="al2023-other")
        with self.assertRaisesRegex(SystemExit, "ami-ireland in eu-west-1 is not al2023-pinned"):
            restarted()
        del arguments.regional_amis[("r8i", "eu-west-1")]
        with self.assertRaisesRegex(SystemExit, "no r8i AMI for eu-west-1"):
            restarted()

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
                arguments = self.arguments(root, platform_regions={"r8i": regions}, purchase={"r8i": "spot"},
                                           buckets={region: "bucket-" + region for region in regions})
                arguments.regional_amis = {("r8i", region): "ami-" + region for region in regions}
                arguments.shared_budget = self.shared_fleet(arguments)
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
                arguments = SimpleNamespace(settings=self.settings(platform_regions={"r8i": regions}), cloud=FakeCloud())
                job = RUN_CAMPAIGN.unit_worker_job(self.platform, 1, 10, "spot", capacity_failures=failures)
                self.assertEqual(expected, RUN_CAMPAIGN.launch_placements(arguments, job))
                self.assertEqual([], arguments.cloud.calls)

    def test_each_platform_launches_in_its_own_regions_order(self):
        # The October 28, 2026 scores: r8i's three regions tie, so its list order stands, while r9g's best-scored
        # regions come first. On-Demand takes each list as given.
        platforms = {platform.name: platform for platform in RUN_CAMPAIGN.read_platforms(SCRIPT.parent)}
        arguments = SimpleNamespace(cloud=FakeCloud(), settings=self.settings(
            platform_regions={"r8i": ["eu-west-1", "eu-central-1", "ap-south-1"],
                              "r9g": ["us-west-2", "us-east-2", "eu-central-1"]}))
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
        arguments = SimpleNamespace(spot_vcpu_reserve=4, cloud=aws, settings=self.settings(
            platform_regions={"r8i": ["us-west-2"]}, purchase={"r8i": "spot"}))
        arguments.settings.change("dain", "fit the quota",
                                  lambda settings: settings["regions"]["us-west-2"].update(max_vcpus=7))
        aws.quotas[("us-west-2", "spot")] = 11
        aws.images[("us-west-2", platform.ami_id)] = AMAZON_LINUX
        aws.offerings[("us-west-2", "r8i.2xlarge")] = set()
        with self.assertRaisesRegex(SystemExit, "cannot admit one host"):
            RUN_CAMPAIGN.validate_aws(arguments, [platform], jobs)

        arguments.spot_vcpu_reserve = 3
        with self.assertRaisesRegex(SystemExit, "r8i.2xlarge is not offered"):
            RUN_CAMPAIGN.validate_aws(arguments, [platform], jobs)

    def test_aws_preflight_rejects_deadline_capacity_above_effective_spot_quota(self):
        aws = FakeCloud()
        arguments = SimpleNamespace(spot_vcpu_reserve=16, cloud=aws, settings=self.settings(
            platform_regions={"r8i": ["us-west-2"]}, purchase={"r8i": "spot"}))

        def limit(vcpus):
            arguments.settings.change("dain", f"Oregon's limit {vcpus} vCPUs",
                                      lambda settings: settings["regions"]["us-west-2"].update(max_vcpus=vcpus))

        limit(64)
        aws.quotas[("us-west-2", "spot")] = 64
        # Below the On-Demand safety reserve, which binds only a region with an allowance.
        aws.quotas[("us-west-2", "on-demand")] = 16
        aws.images[("us-west-2", self.platform.ami_id)] = AMAZON_LINUX
        aws.buckets["bucket"] = cloud.BucketInfo("us-west-2", {"Project": "regulator-benchmark"})
        with self.assertRaisesRegex(SystemExit, "max_vcpus exceeds"):
            RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        limit(48)
        RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        # Once AWS raises the quota, a check asks for it afresh, and admission takes what the check found.
        aws.quotas[("us-west-2", "spot")] = 96
        limit(80)
        RUN_CAMPAIGN.validate_aws(arguments, [self.platform])
        aws.calls.clear()
        self.assertEqual(96, RUN_CAMPAIGN.vcpu_quota(arguments, "us-west-2", "spot"))
        self.assertEqual([], aws.calls)

    @staticmethod
    def replica_items(arguments, platforms, units, seconds):
        """Three replicas of each unit on each platform, as a primary phase runs them, with their pools."""
        items = [RUN_CAMPAIGN.WorkItem(platform.name, platform.instance_type, "shard", unit, replica, seconds,
                                       (("1", seconds),))
                 for unit in units for replica in (1, 2, 3) for platform in platforms]
        arguments.unit_platforms = RUN_CAMPAIGN.pool_platforms(platforms, items)
        return items

    def test_campaign_checks_its_phases_against_its_settings(self):
        arguments = SimpleNamespace(campaign_id="baseline", phase="primary")
        items = self.replica_items(arguments, [self.platform], ["unit-0001"], 900)
        # A phase planned alone, before its campaign has settings, plans for the default region.
        RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.execute = True
        with self.assertRaisesRegex(SystemExit, "requires the campaign's settings"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        arguments.settings = self.settings(platform_regions={"r8i": ["us-west-2"]}, purchase={"r8i": "spot"})
        RUN_CAMPAIGN.validate_configuration(arguments, items)
        # A campaign runs On-Demand only where its settings allow it.
        arguments.settings.change("dain", "r8i may buy On-Demand", lambda settings: settings["purchase"].clear())
        with self.assertRaisesRegex(SystemExit, "allow no On-Demand in a region of r8i"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)

        def allow_on_demand(settings):
            settings["regions"]["eu-west-1"] = {"bucket": "ireland", "max_vcpus": 64, "on_demand_max_vcpus": 64}
            settings["platform_regions"]["r8i"].append("eu-west-1")
            for rates in settings["hourly_rates"].values():
                rates["on_demand_total"] = 2 * rates["total"]

        arguments.settings.change("dain", "Ireland allows On-Demand", allow_on_demand)
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

    def test_deadline_uses_the_settings_host_and_vcpu_limits(self):
        arguments = SimpleNamespace(campaign_id="baseline", phase="primary", spot_vcpu_reserve=0,
                                    phase_timeout_seconds=54000, settings=self.settings(
                                        platform_regions={"r8i": ["us-west-2"]}, purchase={"r8i": "spot"},
                                        max_hosts=512))

        def change(update):
            arguments.settings.change("dain", "limits", update)

        def oregon(vcpus):
            return lambda settings: settings["regions"]["us-west-2"].update(max_vcpus=vcpus)

        change(oregon(8))
        # 18 worker hours of units: one worker lifetime and a retry wave on three workers, but not on one.
        items = self.replica_items(arguments, [self.platform], [f"unit-{index}" for index in range(24)], 900)
        with self.assertRaisesRegex(SystemExit, "cannot fit its phase deadline"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        change(lambda settings: oregon(1024)(settings) or settings.update(max_hosts=1))
        with self.assertRaisesRegex(SystemExit, "cannot fit its phase deadline"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        change(lambda settings: settings.update(max_hosts=512))
        RUN_CAMPAIGN.validate_configuration(arguments, items)
        change(oregon(1))
        with self.assertRaisesRegex(SystemExit, "cannot admit every worker type"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)
        # Another allowed region's limit admits the workers, and a wave fills every region's limit at once:
        # the three 8-vCPU workers need Oregon's one vCPU beside Ireland's 23.
        change(lambda settings: settings["regions"].update({"eu-west-1": {"bucket": "ireland", "max_vcpus": 23}}) or
               settings["platform_regions"]["r8i"].append("eu-west-1"))
        RUN_CAMPAIGN.validate_configuration(arguments, items)
        # A unit campaign's limits apply to its pools' worker hosts.
        platforms = RUN_CAMPAIGN.read_platforms(SCRIPT.parent)
        change(lambda settings: settings.update(platform_regions={"r8i": ["us-west-2"], "r9g": ["us-west-2"]},
                                                purchase={"r8i": "spot", "r9g": "spot"}))
        items = RUN_CAMPAIGN.build_work_items("primary", platforms, RUN_CAMPAIGN.work_units.load(SCRIPT.parent),
                                              ["trino-operations"])
        arguments.unit_platforms = RUN_CAMPAIGN.pool_platforms(platforms, items)
        arguments.phase_timeout_seconds = None
        with self.assertRaisesRegex(SystemExit, "cannot admit every worker type"):
            RUN_CAMPAIGN.validate_configuration(arguments, items)

    def test_primary_topology_fits_deadline_with_retry_headroom(self):
        platforms = [
            self.platform,
            RUN_CAMPAIGN.Platform(
                "r9g", "arm", "r9g.2xlarge", 8, "ami", "jdk", "0" * 64,
                "gcc", "cmake", "glibc", "cargo", "rust", "time", "zstd"),
        ]
        arguments = SimpleNamespace(campaign_id="baseline", phase="primary", settings=self.settings(
            purchase={"r8i": "spot", "r9g": "spot"}, max_hosts=64, max_hosts_per_platform=64))
        items = self.replica_items(arguments, platforms, [f"unit-{index}" for index in range(33)], 2700)

        RUN_CAMPAIGN.validate_configuration(arguments, items)

        arguments.phase_timeout_seconds = 57600
        arguments.settings.change("dain", "max_hosts 64 to 16", lambda settings: settings.update(max_hosts=16))
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
            # A campaign refuses malformed options before it plans anything. Options are never abbreviated.
            for options, refusal in (
                    (["tournament", "--candidate", "a"], "--candidate takes one NAME=COMMITISH"),
                    (["tournament", "--candidate", "a/b=HEAD"], "--candidate takes one NAME=COMMITISH"),
                    (["tour/nament", "--candidate", "a=HEAD"], "candidate a's campaign ID must be"),
                    # Executing a campaign from the Mac launches its control host, reached by the key given, and the
                    # host's unit runs the controller itself.
                    (["tournament", "--candidate", "a=HEAD", "--execute"], "requires --ssh-public-key PATH"),
                    (["tournament", "--candidate", "a=HEAD", "--controller"], "it requires --execute"),
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
            # A campaign's first execution seeds its settings from the file it names. A controller's refusal recurs
            # on every start, so it exits with the status the host's unit does not restart.
            errors = io.StringIO()
            with patch.object(sys, "argv", ["run-campaign.py", "campaign", "tournament", "--candidate", "a=HEAD",
                                            "--execute", "--controller", "--campaign-root", str(root / "refused")]), \
                    contextlib.redirect_stderr(errors), self.assertRaises(SystemExit) as refused:
                RUN_CAMPAIGN.main()
            self.assertEqual(RUN_CAMPAIGN.control_host.ENDED_EXIT, refused.exception.code)
            self.assertIn("name them with --settings FILE", errors.getvalue())
            self.assertFalse((root / "refused").exists())
            # A phase runs only inside its campaign's controller, and a second controller of a campaign stops at
            # once, before it freezes anything, while the first owns the campaign root.
            given = root / "settings.json"
            given.write_text(json.dumps(test_fleet_budget.SETTINGS))
            execute = ["--candidate", "lifecycle=HEAD", "--campaign-root", str(root), "--execute", "--controller",
                       "--settings", str(given)]
            for command, refusal in ((["smoke", "language", *execute], "a phase runs only inside its campaign's"),
                                     (["campaign", "language", *execute], f"owns campaign root {root}")):
                errors = io.StringIO()
                with self.subTest(refusal=refusal), patch.object(sys, "argv", ["run-campaign.py", *command]), \
                        RUN_CAMPAIGN.controller_lock(root, "language"), \
                        patch.object(RUN_CAMPAIGN, "freeze_candidate") as freeze, \
                        patch.object(RUN_CAMPAIGN, "validate_source") as setup, \
                        contextlib.redirect_stderr(errors), self.assertRaises(SystemExit) as refused:
                    RUN_CAMPAIGN.main()
                self.assertIn(refusal, f"{refused.exception}{errors.getvalue()}")
                freeze.assert_not_called()
                setup.assert_not_called()
            # A campaign root the earlier controllers started is refused, naming what shows it, and left as it was:
            # the per-phase controllers' records, or a fleet naming its phases by their roots on the control host.
            for path, content in ((root / "old" / "campaign-driver.txt", "pid=1\n"),
                                  (root / "old" / RUN_CAMPAIGN.STATE_FILE, json.dumps({"reservations": {}, "completed": {
                                      "x/1": {"owner": f"{RUN_CAMPAIGN.control_host.CAMPAIGN_ROOT}/a/primary"}}})),
                                  (root / "old" / "a" / "lang" / "smoke" / "driver-child.txt", "pid=1\n"),
                                  (root / "old" / "a" / "primary" / "execution-policy.json",
                                   json.dumps({"max_concurrent": 16}))):
                with self.subTest(path=path.name), tempfile.TemporaryDirectory() as old:
                    path = Path(old) / path.relative_to(root)
                    path.parent.mkdir(parents=True)
                    path.write_text(content)
                    arguments = SimpleNamespace(
                        campaign="tournament", campaign_root=Path(old) / "old", candidates=[("a", "main")],
                        smoke_results=None, primary_results=None, confirmation_jobs=None, engine_builds=None,
                        language_plan=[], execute=True, controller=True, settings=given)
                    with self.assertRaisesRegex(SystemExit, f"as {re.escape(str(path))} shows"):
                        RUN_CAMPAIGN.run_campaign(arguments, SCRIPT.parents[3])
                    self.assertFalse((Path(old) / "old" / RUN_CAMPAIGN.CONTROLLER_LOCK).exists())
            # Executed from the Mac, once the account passes the controller's checks, the campaign launches its
            # control host with the command the host's unit runs: each candidate at its commit, and the settings and
            # plan as the launch inputs hold them on the host's volume.
            key = root / "control.pub"
            key.write_text(f"{test_control_host.SSH_PUBLIC_KEY}\n")
            aws = test_control_host.control_host_cloud()
            with patch.object(sys, "argv", ["run-campaign.py", "campaign", "language", "--candidate", "lifecycle=HEAD",
                                            "--language-plan", str(root / "plan"), "--execute", "--settings",
                                            str(given), "--ssh-public-key", str(key)]), \
                    patch.object(RUN_CAMPAIGN.cloud, "AwsCloud", return_value=aws), \
                    patch.object(RUN_CAMPAIGN, "check_candidate_tooling"), \
                    patch.object(RUN_CAMPAIGN, "validate_aws") as validate, contextlib.redirect_stdout(io.StringIO()):
                RUN_CAMPAIGN.main()
            validate.assert_called_once()
            [(_, launched)] = aws.control_hosts.items()
            uri = test_control_host.user_data_settings(launched["user_data"])["LAUNCH_INPUTS_URI"]
            body = aws.objects[tuple(uri.removeprefix("s3://").split("/", 1))][0]
            with tarfile.open(fileobj=io.BytesIO(body)) as archive:
                inputs = {member.name: archive.extractfile(member).read() for member in archive if member.isfile()}
            commit = RUN_CAMPAIGN.resolve_commit(SCRIPT.parents[3], "HEAD")
            self.assertEqual(
                "CONTROLLER_ARGUMENTS=campaign language --execute --controller --campaign-root /regulator/campaign "
                "--settings /regulator/control-host/settings.json --spot-vcpu-reserve 32 --smoke-protocol smoke "
                f"--candidate lifecycle={commit} --language-plan /regulator/control-host/language-plans/0\n",
                inputs["controller.env"].decode())
            self.assertEqual((f"{commit}\n".encode(), given.read_bytes(), (root / "plan" / "plan.json").read_bytes()),
                             (inputs["candidate-commits"], inputs["settings.json"],
                              inputs["language-plans/0/plan.json"]))

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
            phase="primary", campaign_id=receipts[0]["campaign_id"], heap_size="8g",
            candidate_archive_sha256=receipts[0]["candidate_archive_sha256"], engine_tree=receipts[0]["engine_tree"],
            candidate_commit=receipts[0]["candidate_commit"], language_plans={plan["shard_id"]: fixture.directory},
            settings=self.settings(max_hosts=2), shared_budget=Mock(**{
                "reservations.return_value": [], "report_boots.return_value": BOOT_WINDOW_MAX}))

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
            dispatcher = RUN_CAMPAIGN.UnitDispatcher(SimpleNamespace(phase="primary", settings=self.settings(
                purchase={"r8i": purchase})),
                                                     SCRIPT.parents[3], Path("/nonexistent"), items,
                                                     RUN_CAMPAIGN.read_platforms(SCRIPT.parent), None)
            self.assertEqual([item.identity for item in items if dispatcher.on_demand(item)], expected)

    def test_items_lease_by_on_demand_work_then_replica_then_work_left(self):
        items = [RUN_CAMPAIGN.WorkItem("r8i", "r8i.large", "language-bulk", unit_id, replica, seconds,
                                       (("1", seconds),))
                 for unit_id, seconds in (("short", 100.0), ("long", 600.0), ("slow", 950.0)) for replica in (1, 2)]
        dispatcher = RUN_CAMPAIGN.UnitDispatcher(
            SimpleNamespace(phase="primary", settings=self.settings(purchase={"r8i": "spot-then-on-demand"})),
            SCRIPT.parents[3],
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
        def full(*zones, kind="capacity", code="InsufficientInstanceCapacity", message=""):
            return lambda region, spec: (cloud.LaunchRejected(kind, code, message)
                                         if spec.subnet[-1] in zones else None)

        for market, rejection, expected in (
                # A full zone moves the launch to the next; only every zone full fails it.
                ("spot", full("a"), "launched"),
                ("spot", full("a", "b", "c"), ("retryable-spot", "launch-capacity:InsufficientInstanceCapacity")),
                # A refused Spot price or quota is the Spot market's, an On-Demand capacity or quota the pool's
                # capacity.
                ("spot", full("a", "b", "c", kind="price", code="SpotMaxPriceTooLow"),
                 ("retryable-spot", "launch-capacity:SpotMaxPriceTooLow")),
                ("on-demand", full("a", "b", "c"),
                 ("retryable-capacity", "launch-capacity:InsufficientInstanceCapacity")),
                ("on-demand", full("a", "b", "c", kind="quota", code="VcpuLimitExceeded"),
                 ("retryable-capacity", "launch-capacity:VcpuLimitExceeded")),
                # A request AWS will never grant fails as the infrastructure's, in either market, with its explanation.
                ("spot", full("a", "b", "c", kind="request", code="UnauthorizedOperation", message="not authorized"),
                 ("retryable-infrastructure", "launch-infrastructure:UnauthorizedOperation: not authorized")),
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
                self.assertEqual(("spot", "0.28", "regulator-benchmark-worker"),
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

    def test_a_crash_during_a_ledger_append_leaves_whole_rows(self):
        job = RUN_CAMPAIGN.unit_worker_job(self.platform, 1, 10, "spot")
        with tempfile.TemporaryDirectory() as temporary_directory:
            attempts = Path(temporary_directory) / "job-attempts.tsv"
            # A crash as the ledger is created leaves none, rather than one without its header.
            with patch.object(os, "replace", side_effect=OSError("crashed")), \
                    self.assertRaises(OSError):
                RUN_CAMPAIGN.write_attempt(attempts, job, "starting", "session=baseline-e10")
            self.assertEqual([], RUN_CAMPAIGN.read_attempts(attempts))
            # A crash partway through a row leaves it without its line end: a restart, and the reduction reading the
            # events, read the rows before it, and its next row replaces it, though the crash tore a character or
            # came past a line break in a value, as a settings change's reason may hold, which the row writes as a
            # space.
            worker = SimpleNamespace(metadata=dict.fromkeys(
                ("instance_id", "aws_region", "availability_zone", "placement_score"), "-"), directory=Path("worker"))
            ledgers = {
                "job-attempts.tsv": (
                    lambda path, value: RUN_CAMPAIGN.write_attempt(path, job, "stopped", value),
                    lambda path: [row["detail"] for row in RUN_CAMPAIGN.read_attempts(path)]),
                EVENTS: (
                    lambda path, value: RUN_CAMPAIGN.append_ledger(
                        path, EVENT_FIELDS, {"version": 2, "at": 1, "actor": "dain", "change": value}),
                    lambda path: [row["change"] for row in csv.DictReader(RUN_CAMPAIGN.ledger_lines(path),
                                                                         delimiter="\t")]),
                "campaign-events.tsv": (
                    lambda path, value: RUN_CAMPAIGN.write_event(path, "interruption", "baseline", job, worker, value),
                    lambda path: [row["reason"] for row in RUN_CAMPAIGN.aggregate_results.load_events([path])])}
            # Each crash keeps the first bytes of the row's value it names.
            for case, (ledger, value, kept) in enumerate((
                    ("job-attempts.tsv", "lease written", 8), ("job-attempts.tsv", "bail é", 6),
                    (EVENTS, "a paused: fault\nsee the log", 16), ("campaign-events.tsv", "spot-interruption", 4))):
                with self.subTest(ledger=ledger, value=value):
                    path = Path(temporary_directory) / str(case) / ledger
                    append, read = ledgers[ledger]
                    append(path, "first")
                    append(path, value)
                    path.write_bytes(path.read_bytes()[:path.read_bytes().rindex(value[:4].encode()) + kept])
                    self.assertEqual(["first"], read(path))
                    append(path, value)
                    self.assertEqual(["first", value.replace("\n", " ")], read(path))

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
        job = RUN_CAMPAIGN.Job(self.platform, "unit-worker", 1, 10, 1, "on-demand", "unit-worker")
        aws = FakeCloud()
        aws.quotas[("eu-west-1", "on-demand")] = 64
        aws.region_instances["eu-west-1"] = [cloud.Instance("i-spot", "r8i.2xlarge", "spot", "running", "", {}, 0),
                                             cloud.Instance("i-other", "r8i.2xlarge", "on-demand", "running", "", {}, 0)]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            arguments = self.arguments(root, platform_regions={"r8i": ["us-west-2", "eu-west-1"]})

            def allow_on_demand(settings):
                settings["regions"]["eu-west-1"].update(max_vcpus=64, on_demand_max_vcpus=64)
                for rates in settings["hourly_rates"].values():
                    rates["on_demand_total"] = 2 * rates["total"]

            arguments.settings.change("dain", "Ireland allows On-Demand", allow_on_demand)
            arguments.shared_budget, arguments.cloud = self.shared_fleet(arguments), aws
            launched, admitted = self.admit(arguments, root, job)
            reserved = json.loads(arguments.shared_budget.state_path.read_text())["reservations"]
        # Oregon has no allowance; Ireland's quota, less its reserve and other work, fits the host.
        self.assertEqual({"eu-west-1"}, {call[1] for call in aws.calls})
        self.assertEqual(("eu-west-1", "on-demand", True), (launched.region, launched.market, admitted))
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
    worker's status 43, "interrupt-progress" does so once the lease's first group finished, its bundle landing while
    its instance still runs, as a notice's two minutes allow, and its exit the next time it acts, and "notice" does
    so before it fetches the lease; "interrupt-semantic" is "interrupt-progress" whose first group is a semantic
    failure. A host whose lease is scripted to "claim" claims it the moment its previous lease
    ends, as if its idle time ran out then. A host scripted to "silent-interrupt" does so with none of its heartbeat
    uploads landing, and one scripted to "silent-crash" runs the lease's first group and then fails, its heartbeats
    also lost; with "silent-groups" the lease completes with only the heartbeat that started it landing, and with
    "silent-stall" it runs its first group and stalls, none of its heartbeats landing. "stall"
    stops the host once its lease starts; once the controller terminates its instance, the lease's completion lands
    late, after another host completed the same replica, or never, once no other host runs,
    and with "stall-interrupt" the stopping host reports an interruption of the lease instead. With "stall-semantic"
    the late completion is a semantic failure, and with "stall-semantic-early" it lands as soon as another host
    leased the replica, before that host completes it. A host whose boot the fleet fails exits before its first
    heartbeat. As the user data's watcher does, a host powers off at its stop time, the latest of the one it was
    launched with and those of the work leases it read, whatever it is doing, and as the worker does, it takes no
    lease once the lifetime its user data names has passed since it booted, stopping instead. A host whose boot the
    fleet slows stays pending until it is terminated, one the fleet leaves unlisted
    is launched without RunInstances's answer and is missing from EC2's listing while it stays unlisted, and one
    whose cleanup the fleet slows keeps shutting down once terminated until every other host has exited. Every host
    that booted uploads its host archive as it exits, as the user data's exit trap does, even one that failed during
    bootstrap, and its instance then terminates, or while the fleet leaks it, keeps shutting down. A lease completes failed with "semantic-failure" (status 44),
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
        "interrupt-semantic": RUN_CAMPAIGN.SEMANTIC_FAILURE_STATUS,
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
        self.lifetime_seconds = int(settings["UNIT_WORKER_LIFETIME_SECONDS"])
        self.power_off_at = self.stop_by
        self.zone = next(zone for subnet, zone in fleet.region_network(region).subnets if subnet == spec.subnet)
        self.launched_at = fleet.clock
        self.setup_seconds = fleet.setup_seconds(self)
        self.instance_id = None
        self.script = fleet.scripts.get(number, {})
        self.sequence = 1
        # Since when the host has waited for its next lease, and the lease it runs, with its groups yet to finish
        # and when the first of them does.
        self.idle_since = None
        self.booted_at = None
        self.running = None
        # The sequence it last found no lease for.
        self.absent = None
        self.booted = False
        self.stalled = False
        self.stopping = False
        self.terminations = 0
        self.status = None
        self.ended_at = None
        self.leaking = False
        # Whether the host uploaded its interruption bundle and exits the next time it acts.
        self.interrupted = False

    def tick(self):
        if self.leaking and not self.fleet.leaked(self):
            self.leaking = False
            self.fleet.set_state(self, "terminated", self.shutdown_reason)
        if self.interrupted and self.status is None:
            return self.exit(RUN_CAMPAIGN.UNIT_WORKER_INTERRUPTED_STATUS)
        if self.status is None and not self.stopping and self.fleet.time() >= self.power_off_at:
            self.fleet.log.append(("power-off", self.session))
            return self.exit(143)
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
            self.booted_at = self.idle_since = self.fleet.clock
            self.fleet.set_state(self, "running")
            if self.fleet.failed_boot(self):
                return self.exit(1)
        if self.running is not None:
            return self.advance()
        if self.script.get(self.sequence) == "notice":
            return self.interrupt(None)
        if self.fleet.clock - self.booted_at >= self.lifetime_seconds:
            self.fleet.log.append(("lifetime", self.session))
            return self.stop()
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
        self.power_off_at = max(self.power_off_at, lease["stop_by"])
        behavior = self.script.get(self.sequence)
        self.heartbeat(lease["unit_id"])
        if behavior in ("interrupt", "silent-interrupt"):
            return self.interrupt(lease["unit_id"])
        if behavior == "silent-crash":
            self.run_group(lease, lease["groups"][0], None)
            return self.exit(1)
        if behavior == "silent-stall":
            self.run_group(lease, lease["groups"][0], None)
            self.stalled = True
            return
        if behavior in ("interrupt-progress", "interrupt-semantic"):
            self.run_group(lease, lease["groups"][0], behavior)
            return self.interrupt(lease["unit_id"], lease["groups"][:1], lease["groups"][1], running_on=True)
        if behavior in ("stall", "stall-interrupt", "stall-semantic", "stall-semantic-early"):
            self.stalled = True
            return
        self.run(lease)

    def interrupt(self, unit_id, finished=(), running=None, running_on=False):
        notice = json.dumps({"schema_version": 2, "sequence": self.sequence, "unit_id": unit_id,
                             "finished_groups": list(finished), "running_group": running}).encode()
        bundle = io.BytesIO()
        with tarfile.open(fileobj=bundle, mode="w:gz") as archive:
            member = tarfile.TarInfo("interruption.json")
            member.size = len(notice)
            archive.addfile(member, io.BytesIO(notice))
        self.put(f"events/{self.sequence}-interrupted.tar.gz", bundle.getvalue())
        self.interrupted = True
        if not running_on:
            self.exit(RUN_CAMPAIGN.UNIT_WORKER_INTERRUPTED_STATUS)

    def heartbeat(self, unit_id):
        if {"silent-interrupt", "silent-crash", "silent-stall"} & set(self.script.values()):
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
            "pack_seconds": 0.5, "upload_seconds": 1.5, "upload_attempts": 1,
            **{field: datetime.fromtimestamp(moment, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
               for field, moment in (("started_at", self.fleet.time() - self.fleet.group_seconds(self, lease, group)),
                                     ("finished_at", self.fleet.time()))}}
        if behavior == "partial-marker":
            del marker["result_sha256"]
        self.put(f"{prefix}/completion.json", json.dumps(marker).encode())
        if behavior != "silent-groups":
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
        self.status, self.ended_at = status, self.fleet.time()
        self.leaking = self.fleet.leaked(self)
        self.fleet.set_state(self, "shutting-down" if self.leaking else "terminated", self.shutdown_reason)
        self.fleet.log.append(("exit", self.session))

    @property
    def shutdown_reason(self):
        return "Client.UserInitiatedShutdown" if self.terminations else "Client.InstanceInitiatedShutdown"

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
    timed fleet's hosts measure each group for pace times its estimate; otherwise a lease takes no time. Given synthetic units,
    which own no rows to measure, a host uploads only each group's receipt, and the campaign accepts the receipt
    alone. The fleet records each controller loop's seconds and calls by operation, from one sleep between polls to the
    next, with the most keys one of its listings returned and the age of the oldest completion marker S3 holds at its
    end.
    """

    def __init__(self, scratch, scripts=None, abort_after=None, full=None, failed_boot=lambda host: False,
                 unit_scripts=None, stuck_prefix=lambda host: False, unlisted=lambda host: False,
                 slow_boot=lambda host: False, slow_cleanup=lambda host: False, setup_seconds=lambda host: 0,
                 timed=False, pace=1, units=None, leaked=lambda host: False):
        super().__init__()
        self.scratch = scratch
        self.setup_seconds = setup_seconds
        self.timed = timed
        self.pace = pace
        self.loops = []
        self.loop_started = (self.clock, 0)
        self.listed = 0
        self.failed_boot = failed_boot
        self.slow_boot = slow_boot
        self.slow_cleanup = slow_cleanup
        self.stuck_prefix = stuck_prefix
        self.leaked = leaked
        self.unlisted = unlisted
        self.scripts = scripts or {}
        self.unit_scripts = unit_scripts or {}
        # The controller stops after this many polls, by raising this exception, as a crash does by default.
        self.abort_after = abort_after
        self.abort = SystemExit
        # Each platform's first launches in each market that find no capacity.
        self.full = dict(full or {})
        # The shared fleet's reservations a restart recovers.
        self.reservations = []
        # Every lease the controller wrote, by bucket and key.
        self.written_leases = {}
        # Every launch the controller asked for, in order, with the controller loop that asked, and whether AWS
        # refused it.
        self.launches = []
        self.synthetic = units is not None
        self.units = units or RUN_CAMPAIGN.work_units.load(SCRIPT.parent)
        self.hosts = []
        self.log = []
        self.sleeps = 0
        # The digest of the tooling the controller runs.
        self.tooling = "c" * 64
        # The default region's shared bucket, and each arm platform's image.
        self.buckets["bucket"] = cloud.BucketInfo(RUN_CAMPAIGN.default_regions()[0], RUN_CAMPAIGN.SHARED_BUCKET_TAGS)
        self.arm_images = {platform.ami_id for platform in RUN_CAMPAIGN.read_platforms(SCRIPT.parent)
                           if platform.architecture == "arm"}

    def image(self, region, ami):
        image = super().image(region, ami)
        return replace(image, architecture="arm64") if ami in self.arm_images else image

    def run_instance(self, region, spec):
        if (region, spec.token) not in self.launched:
            platform = spec.tags["Platform"]
            refused = self.full.get((platform, spec.market), 0) > 0
            self.launches.append(SimpleNamespace(platform=platform, market=spec.market, region=region,
                                                 launched_at=self.clock, loop=self.sleeps, refused=refused))
            if refused:
                self.full[platform, spec.market] -= 1
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

    def put(self, region, bucket, key, body, *, if_none_match=False):
        written = super().put(region, bucket, key, body, if_none_match=if_none_match)
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
        return self.pace * RUN_CAMPAIGN.work_units.unit_group(self.units[lease["unit_id"]], group)["estimated_seconds"]

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

    def run_phase(self, root, fleet, phase, shards, max_concurrent, per_platform, **options):
        """Drain the phase's work items through the fleet, as dispatcher's options build its controller; return the
        result directory.

        The unit attempt ledger is left in self.ledger."""
        with fleet.stubs(), contextlib.redirect_stdout(io.StringIO()):
            dispatcher = self.dispatcher(root, fleet, phase, shards, max_concurrent, per_platform, **options)
            try:
                dispatcher.run()
            finally:
                self.ledger = self.unit_attempts(dispatcher.result_root)
        self.assert_accepted(dispatcher)
        return dispatcher.result_root

    def unit_attempts(self, result_root):
        if not (result_root / "unit-attempts.tsv").exists():
            return []
        with (result_root / "unit-attempts.tsv").open(newline="") as ledger:
            return list(csv.DictReader(ledger, delimiter="\t"))

    def assert_accepted(self, dispatcher):
        """Assert that every group of the phase's work items was accepted."""
        accepted = RUN_CAMPAIGN.read_accepted_units(dispatcher.accepted_path)
        self.assertEqual(sorted(f"{identity}/group-{group}" for identity, item in dispatcher.items.items()
                                for group, _ in item.groups),
                         sorted(RUN_CAMPAIGN.group_identity(receipt) for receipt in accepted))

    def dispatcher(self, root, fleet, phase, shards, max_concurrent, per_platform, platform_regions=None,
                   purchases=None, on_demand=True, deadline=None, confirmations=(), primary_results=None,
                   release_version="", extend=True, shared=False, budget=None, results="results", telemetry=None):
        """The controller of the phase's work items, with the fleet's stubs in place, its result directory in root,
        under the settings its campaign root, root, holds, or else settings it seeds there: its limits, each platform's
        regions, the default region unless platform_regions names others, and its purchases, its results in the
        directory results names, and its telemetry going where telemetry sends it, or nowhere. With shared, its budget is the campaign's own, as its controller keeps it for every
        phase, which sees EC2 hold none of its hosts, so only its host limit and its boot window pace launches. Unless
        given one, the stub budget admits every extension of a host's reservation if extend, logging each, and
        refuses every one otherwise, and keeps its boot window at its largest."""
        shared_budgets = vars(self).setdefault("shared_budgets", {})
        if shared and root in shared_budgets:
            settings = shared_budgets[root].settings
        else:
            settings = self.campaign_settings(root, max_concurrent, per_platform, platform_regions, purchases)
        if shared:
            budget = self.shared_budget(root, settings)
        elif budget is None:
            budget = Mock()
            budget.reserve.side_effect = lambda campaign, job, *_, **__: (
                on_demand or job.market == "spot", "fleet quota")

            def extend_reservation(campaign, epoch, *, until):
                fleet.log.append(("extend", epoch, until, extend))
                return extend, "fleet spending or machine-hour ceiling"

            budget.extend.side_effect = extend_reservation
            budget.reservations.return_value = fleet.reservations or []
            budget.report_boots.return_value = BOOT_WINDOW_MAX
        self.budget = budget
        arguments = SimpleNamespace(
            phase=phase, campaign="campaign", campaign_id="campaign", campaign_root=root, candidate_archive=root / "candidate.tar.gz",
            candidate_commit="a" * 40, candidate_archive_sha256="not-recorded", engine_tree="b" * 40, heap_size="8g",
            cloud=fleet, settings=settings, spot_vcpu_reserve=0, shared_budget=budget, selected_shards=shards,
            topology_deadline_seconds=deadline, primary_results=primary_results, release_version=release_version,
            telemetry=telemetry)
        self.arguments = arguments
        with gzip.open(arguments.candidate_archive, "wb") as candidate:
            candidate.write(b"candidate")
        items = RUN_CAMPAIGN.build_work_items(phase, self.PLATFORMS, fleet.units, shards, confirmations)
        result_root = root / results
        result_root.mkdir(exist_ok=True)
        RUN_CAMPAIGN.write_work_items(result_root / "work-items.tsv", items)
        self.validation = RUN_CAMPAIGN.unit_validation(SCRIPT.parents[3], arguments, "baseline")
        return RUN_CAMPAIGN.UnitDispatcher(arguments, SCRIPT.parents[3], result_root, items, self.PLATFORMS,
                                           self.validation)

    def campaign_settings(self, root, max_hosts, per_platform, platform_regions=None, purchases=None):
        """The settings the campaign root holds, or else those dispatcher seeds there."""
        if (root / RUN_CAMPAIGN.CAMPAIGN_SETTINGS).is_file():
            return CampaignSettings(root)
        return campaign_settings(root, {**{platform.name: RUN_CAMPAIGN.default_regions() for platform in self.PLATFORMS},
                                        **(platform_regions or {})}, purchases, max_hosts=max_hosts,
                                 max_hosts_per_platform=per_platform)

    def shared_budget(self, root, settings):
        """The campaign's own budget, as its controller keeps it for every phase, which sees EC2 hold none of its
        hosts."""
        shared_budgets = vars(self).setdefault("shared_budgets", {})
        if root not in shared_budgets:
            snapshot = patch.object(RUN_CAMPAIGN, "shared_fleet_snapshot",
                                    return_value={"quota": 736, "instances": [], "requests": []})
            snapshot.start()
            self.addCleanup(snapshot.stop)
            shared_budgets[root] = RUN_CAMPAIGN.FleetBudget(root, settings, 0)
        return shared_budgets[root]

    def run_campaign(self, root, fleet, phases, max_hosts, per_platform, loop=lambda: None, telemetry=None,
                     **options):
        """Run phases, each (key, phase, shards, after), as the campaign's controller runs them over the fleet, each
        phase's dispatcher built as dispatcher builds it with options, its key naming its results, and the campaign's
        budget, its telemetry going where telemetry sends it, or nowhere; loop runs before each of the controller's
        polls, with each phase's dispatcher so far in self.running. Returns the steps that failed and each phase's
        dispatcher by key."""
        budget = self.shared_budget(root, self.campaign_settings(root, max_hosts, per_platform,
                                                                 options.get("platform_regions"),
                                                                 options.get("purchases")))
        arguments = SimpleNamespace(settings=budget.settings, shared_budget=budget, telemetry=telemetry)
        dispatchers = self.running = {}

        def prepare(phase, _):
            dispatcher = dispatchers[phase.key] = self.dispatcher(
                root, fleet, phase.phase, phase.shards, max_hosts, per_platform, shared=True, results=phase.key,
                telemetry=phase.telemetry, **options)
            # As its setup does, the phase checks AWS for its workers in their regions, returning a check that
            # fails with its dispatcher; the fleet's hosts install no JDK and no candidate archive.
            vars(dispatcher.arguments).update(unit_platforms=dispatcher.platforms, language_plan=[])
            try:
                RUN_CAMPAIGN.check_regions(dispatcher.arguments, list(dispatcher.platforms.values()))
                dispatcher.begin()
                return dispatcher, None
            except (Exception, SystemExit) as error:
                return dispatcher, error

        poll = fleet.sleep

        def sleep(seconds):
            if seconds == RUN_CAMPAIGN.UNIT_POLL_SECONDS:
                loop()
            poll(seconds)

        fleet.sleep = sleep
        try:
            with fleet.stubs(), patch.object(RUN_CAMPAIGN, "prepare_phase", side_effect=prepare), \
                    patch.object(RUN_CAMPAIGN, "stage_jdks"), patch.object(RUN_CAMPAIGN, "upload_candidate_archive"):
                failed = RUN_CAMPAIGN.Campaign(arguments, SCRIPT.parents[3], [
                    RUN_CAMPAIGN.Phase(key, root / key, after, {"key": key, "phase": phase, "shards": shards})
                    for key, phase, shards, after in phases]).run()
        finally:
            fleet.sleep = poll
        return failed, dispatchers

    def control(self, root, *command, actor="dain", aws=None):
        """Run a control command on the campaign root, as the actor, reaching AWS through aws."""
        with contextlib.redirect_stdout(io.StringIO()):
            RUN_CAMPAIGN.control(RUN_CAMPAIGN.control_parser().parse_args(
                [str(root), "--campaign", "campaign", "--actor", actor, *command]), aws)

    def leases(self, outcome="leased"):
        return [row for row in self.ledger if row["outcome"] == outcome]

    @staticmethod
    def boot_windows(root):
        """The boot window's sizes in turn, as the controller changed them in the campaign's settings."""
        with (root / EVENTS).open(newline="") as events:
            return [BOOT_WINDOW_FIRST] + [int(row["change"].split(" to ")[1])
                                          for row in csv.DictReader(events, delimiter="\t")
                                          if row["change"].startswith("boot window")]

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
            fleet = FakeUnitFleet(Path(temporary) / "hosts", full={("r8i", "spot"): 2})
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
            fleet = FakeUnitFleet(Path(temporary) / "hosts", full={("r8i", "spot"): 1000})
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

    def test_on_demand_pool_moves_past_regions_with_no_capacity_without_spending_replacements(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The r8i pool buys only On-Demand. Its first region never has capacity, and its second refuses its first
            # three workers, more than a slot's replacements. Each refusal cools its region for the pool and spends
            # none of the slot's replacements, so the pool's next worker tries the other region and its work is all
            # accepted.
            fleet = FakeUnitFleet(Path(temporary) / "hosts")
            run_instance = fleet.run_instance
            refused = {}

            def refuse(region, spec):
                # Every zone refuses a worker the region refuses.
                session = spec.tags["Session"]
                if spec.tags["Platform"] == "r8i" and (
                        region == "us-east-2" or refused.get(session) == region or
                        session not in refused and list(refused.values()).count("us-west-2") < 3):
                    refused[session] = region
                    raise cloud.LaunchRejected("capacity", "InsufficientInstanceCapacity")
                return run_instance(region, spec)

            fleet.run_instance = refuse
            self.run_phase(Path(temporary), fleet, "smoke", ["like-dfa-single-use"], 2, 1,
                           purchases={"r8i": "on-demand"}, platform_regions={"r8i": ["us-east-2", "us-west-2"]})
            self.assertEqual(["us-east-2", "us-west-2"], list(refused.values())[:2])
            self.assertLess(RUN_CAMPAIGN.MAX_REPLACEMENTS_PER_JOB + 1, len(refused))

    def test_pool_moves_past_a_region_that_refuses_its_requests(self):
        # The first region refuses every r8i request outright. Its pool moves to the second, as a refusal spends a
        # slot's replacement only once no region is left to try, so a pool every region refuses still gives up.
        for refusing, expected in ((("us-east-2",), None), (("us-east-2", "us-west-2"), "exhausted their replacements")):
            with self.subTest(refusing=refusing), tempfile.TemporaryDirectory() as temporary:
                fleet = FakeUnitFleet(Path(temporary) / "hosts")
                run_instance = fleet.run_instance
                refused = []

                def refuse(region, spec):
                    if spec.tags["Platform"] == "r8i" and region in refusing:
                        refused.append(region)
                        raise cloud.LaunchRejected("request", "UnauthorizedOperation", "denied in this region")
                    return run_instance(region, spec)

                fleet.run_instance = refuse
                with contextlib.ExitStack() as stack:
                    if expected:
                        stack.enter_context(self.assertRaisesRegex(SystemExit, f"unresolved units: .*{expected}"))
                    self.run_phase(Path(temporary), fleet, "smoke", ["like-dfa-single-use"], 2, 1,
                                   platform_regions={"r8i": ["us-east-2", "us-west-2"]})
                self.assertEqual(["us-east-2"] + ["us-west-2"] * (RUN_CAMPAIGN.MAX_REPLACEMENTS_PER_JOB + 1)
                                 if expected else ["us-east-2"], refused)

    def test_pool_short_of_capacity_tries_one_launch_a_loop_while_the_other_pool_fills_the_boot_window(self):
        # Each pool has work for ten hosts, as many as the boot window first admits, and r8i's next 20 launches in
        # the market it buys find no capacity. The region allows On-Demand hosts.
        units = {f"short-{index}": {"unit_id": f"short-{index}", "shard_id": "short", "unit_index": str(index),
                                    "estimated_seconds": "600",
                                    "groups": [{"group_id": "1", "estimated_seconds": 600}]}
                 for index in range(1, 21)}

        def allow_on_demand(settings):
            settings["regions"][RUN_CAMPAIGN.default_regions()[0]]["on_demand_max_vcpus"] = 736
            for rates in settings["hourly_rates"].values():
                rates["on_demand_total"] = 2 * rates["total"]

        for market in ("spot", "on-demand"):
            with self.subTest(market=market), tempfile.TemporaryDirectory() as temporary:
                fleet = FakeUnitFleet(Path(temporary) / "hosts", units=units, timed=True,
                                      setup_seconds=lambda host: 67, full={("r8i", market): 20})
                self.campaign_settings(Path(temporary), 20, 10, purchases={"r8i": market, "r9g": "spot"}).change(
                    "dain", "On-Demand allowed", allow_on_demand)
                self.run_phase(Path(temporary), fleet, "smoke", ["short"], 20, 10,
                               purchases={"r8i": market, "r9g": "spot"}, shared=True)
                # The pools take turns in the window, so r9g launches its half in the loop r8i first finds no
                # capacity.
                refused = collections.Counter(launch.loop for launch in fleet.launches if launch.refused)
                first = min(refused)
                self.assertEqual(BOOT_WINDOW_FIRST // 2, refused[first])
                self.assertEqual(BOOT_WINDOW_FIRST // 2, sum(launch.platform == "r9g" and launch.loop == first
                                                             for launch in fleet.launches))
                # From then on r8i tries one launch a loop until one finds capacity.
                self.assertEqual({1}, {count for loop, count in refused.items() if loop != first})

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
        # Dain raises the limits to ten hosts a platform as the first wave launches, two hosts a platform, one
        # leasing its pool's 15-minute unit and a 36-second one and the other two short ones. The first r8i host is reclaimed as it boots, and its
        # replacement takes the long unit back, which frees its partner for all but two of the short units, so a
        # single further worker launches for those.
        units = {name: {"unit_id": name, "shard_id": "mixed", "unit_index": str(index), "estimated_seconds": seconds,
                        "groups": [{"group_id": "1", "estimated_seconds": int(seconds)}]}
                 for index, (name, seconds) in enumerate([("long", "900")] +
                                                         [(f"short-{index}", "36") for index in range(1, 13)])}
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts", units=units, timed=True, scripts={1: {1: "notice"}})
            raised = []

            def loop():
                if not raised and fleet.launches:
                    self.control(root, "set-limit", "max-hosts", "20")
                    self.control(root, "set-limit", "max-hosts-per-platform", "10")
                    raised.append(fleet.sleeps + 1)

            failed, _ = self.run_campaign(root, fleet, [("smoke", "smoke", ["mixed"], ())], 4, 2, loop,
                                          purchases={"r8i": "spot", "r9g": "spot"})
            self.assertEqual([], failed)
            self.assertEqual(2, sum(launch.platform == "r8i" and launch.loop == raised[0] for launch in fleet.launches))

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
            late_items, validate = RUN_CAMPAIGN.UnitDispatcher.late_items, RUN_CAMPAIGN.validate_frozen_inputs
            projections, checks = Counter(), Counter()

            def project(dispatcher, pool, now):
                projections[fleet.sleeps, pool] += 1
                return late_items(dispatcher, pool, now)

            def check(*arguments):
                checks[fleet.sleeps] += 1
                return validate(*arguments)

            with patch.object(RUN_CAMPAIGN.UnitDispatcher, "late_items", project), \
                    patch.object(RUN_CAMPAIGN, "validate_frozen_inputs", check):
                self.run_phase(Path(temporary), fleet, "smoke", ["burst"], 200, 100,
                               purchases={"r8i": "spot", "r9g": "spot"}, shared=True)
            self.assertEqual(200, len(fleet.hosts))
            # Each loop projects each pool's late items once, and checks the frozen inputs at most once however many
            # workers it starts and settles.
            self.assertEqual({1}, set(projections.values()))
            self.assertEqual({1}, set(checks.values()))
            # The hosts launch in waves, each as large as the boot window, which doubles as each wave reports in
            # time, up to its largest.
            self.assertEqual([10, 20, 40, 50], self.boot_windows(Path(temporary)))
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
            # 200 hosts of two platforms measure 15-minute units for about five hours, each in 90% of its estimate.
            units = {f"steady-{index}": {"unit_id": f"steady-{index}", "shard_id": "steady", "unit_index": str(index),
                                         "estimated_seconds": "900",
                                         "groups": [{"group_id": "1", "estimated_seconds": 900}]}
                     for index in range(1, 2301)}
            fleet = FakeUnitFleet(Path(temporary) / "hosts", units=units, timed=True, pace=0.9,
                                  setup_seconds=lambda host: 67)
            results = Path(temporary) / "results"
            finishes = []
            poll = fleet.sleep

            def sleep(seconds):
                # Every 20 polls, status estimates the finish from the phase's ledgers and its fleet's reservations.
                state = RUN_CAMPAIGN.read_fleet_state(Path(temporary) / RUN_CAMPAIGN.STATE_FILE)
                if seconds == RUN_CAMPAIGN.UNIT_POLL_SECONDS and state and fleet.sleeps % 20 == 0:
                    progress = RUN_CAMPAIGN.phase_progress(
                        results, RUN_CAMPAIGN.owner_fleet(state, "results", fleet.time()), True)
                    if progress.finish is not None:
                        finishes.append((bool(progress.accepted), fleet.time() + progress.finish))
                poll(seconds)

            fleet.sleep = sleep
            # The campaign's own budget reserves each host, as status reads them, and its boot window admits the
            # whole fleet at once, so the steady state begins as the hosts boot.
            campaign_settings(Path(temporary), max_hosts=200, max_hosts_per_platform=100,
                              boot_window={"initial": 200, "max": 200})
            self.run_phase(Path(temporary), fleet, "smoke", ["steady"], 200, 100, shared=True)
            self.assertEqual(200, len(fleet.hosts))
            # The estimate from the units' estimates alone is late by more than a unit, and once the first groups
            # are accepted their measured seconds correct it to within one unit's 15 minutes of the actual finish.
            finished = fleet.EPOCH + max(host.idle_since for host in fleet.hosts)
            self.assertGreater(finishes[0][1] - finished, 900)
            self.assertEqual([], [finish for accepted, finish in finishes if accepted and abs(finish - finished) >= 900])
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

    def test_hosts_end_on_their_own_within_their_reservations_once_the_controller_is_gone(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(RUN_CAMPAIGN, "UNIT_WORKER_LIFETIME_SECONDS", 35 * 60):
            # 20 hosts of two platforms, whose user data gives them a 35-minute lifetime, each measure a 15-minute
            # unit and hold a 15- or 5-minute one of slack when the controller stops, seven minutes in, and never
            # returns. The second lease of the first two hosts hangs.
            units = {f"gone-{index}": {"unit_id": f"gone-{index}", "shard_id": "gone", "unit_index": str(index),
                                       "estimated_seconds": str(seconds),
                                       "groups": [{"group_id": "1", "estimated_seconds": seconds}]}
                     for index, seconds in enumerate([900] * 15 + [300] * 25, 1)}
            fleet = FakeUnitFleet(Path(temporary) / "hosts", units=units, timed=True, setup_seconds=lambda host: 67,
                                  abort_after=28, scripts={1: {2: "stall"}, 2: {2: "stall"}})
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(Path(temporary), fleet, "smoke", ["gone"], 20, 10)
            gone = fleet.time()
            fleet.outage(3 * 3600)
            # A hung host powers off at its stop time. A host whose two leases took 20 minutes waits out its idle
            # stop and stops itself; one whose leases took 30 minutes reaches its lifetime first, as it waits.
            self.assertEqual(20, len(fleet.hosts))
            ends = {entry[1]: {"power-off": "stop time", "lifetime": "lifetime", "claim": "idle stop"}[entry[0]]
                    for entry in fleet.log if entry[0] in ("power-off", "lifetime", "claim")}
            self.assertEqual({host.session: "stop time" if host.number <= 2 else "lifetime" if sum(
                                  int(fleet.units[lease["unit_id"]]["estimated_seconds"])
                                  for lease in host.leases.values() if "unit_id" in lease) == 1800 else "idle stop"
                              for host in fleet.hosts}, ends)
            self.assertEqual({"stop time", "lifetime", "idle stop"}, set(ends.values()))
            # Every instance terminated, each by the stop time its reservation covers, as the watcher's 15-second
            # poll finds it, and the fleet spent no more after the controller's end than each host running two
            # leases at twice their estimate and an idle stop.
            self.assertEqual({"terminated"}, {instance.state for instances in fleet.region_instances.values()
                                              for instance in instances})
            for host in fleet.hosts:
                self.assertLessEqual(host.ended_at, max(lease.get("stop_by", host.stop_by)
                                                        for lease in host.leases.values()) + 15)
            self.assertLessEqual(sum(host.ended_at - gone for host in fleet.hosts),
                                 len(fleet.hosts) * (2 * 2 * 900 + RUN_CAMPAIGN.UNIT_WORKER_IDLE_SECONDS))

    def test_late_boot_wave_halves_the_boot_window_and_a_healthy_one_doubles_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            # 20 hosts measure two 10-minute units each. The first ten take six minutes to boot, a minute past the
            # five they have to report in; the rest boot in about one.
            units = {f"boot-{index}": {"unit_id": f"boot-{index}", "shard_id": "boot", "unit_index": str(index),
                                       "estimated_seconds": "600",
                                       "groups": [{"group_id": "1", "estimated_seconds": 600}]}
                     for index in range(1, 21)}
            fleet = FakeUnitFleet(Path(temporary) / "hosts", units=units, timed=True,
                                  setup_seconds=lambda host: 360 if host.number <= 10 else 67)
            self.run_phase(Path(temporary), fleet, "smoke", ["boot"], 20, 10,
                           purchases={"r8i": "spot", "r9g": "spot"}, shared=True)
            # The late wave halved the window, and no host launched until the late hosts reported; the next wave of
            # five reported in time and doubled it.
            self.assertEqual([10, 5, 10], self.boot_windows(Path(temporary)))
            launched = sorted(host.launched_at for host in fleet.hosts)
            self.assertEqual(20, len(launched))
            self.assertGreaterEqual(launched[10], launched[0] + 360)

    def test_paused_phase_leases_nothing_a_draining_one_launches_nothing_and_a_stop_ends_every_worker(self):
        units = {f"steer-{index}": {"unit_id": f"steer-{index}", "shard_id": "steer", "unit_index": str(index),
                                    "estimated_seconds": "900", "groups": [{"group_id": "1", "estimated_seconds": 900}]}
                 for index in range(1, 17)}
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()) as log:
            # Four hosts measure 15-minute units. Two and a half minutes in, with every host running its first
            # lease and holding its next, Dain pauses the phase, and resumes it once its hosts have stopped. Once
            # the four hosts launched then all run, he drains it, and 20 minutes later stops the campaign.
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts", units=units, timed=True, setup_seconds=lambda host: 67)
            marks = {}

            def loop():
                if fleet.sleeps == 10:
                    self.control(root, "pause", "smoke", "--reason", "checking the image")
                    marks["paused"] = len(fleet.log)
                elif "paused" in marks and "resumed" not in marks and all(host.status is not None
                                                                         for host in fleet.hosts):
                    self.control(root, "resume", "smoke")
                    marks["resumed"] = len(fleet.log)
                elif "resumed" in marks and "drained" not in marks and len(fleet.hosts) == 8 and all(
                        host.booted for host in fleet.hosts[4:]):
                    self.control(root, "drain", "smoke")
                    marks["drained"] = (len(fleet.log), len(fleet.launches), fleet.sleeps)
                elif "drained" in marks and "stopped" not in marks and fleet.sleeps == marks["drained"][2] + 80:
                    self.control(root, "stop", "--reason", "enough")
                    marks["stopped"] = len(fleet.log)

            failed, dispatchers = self.run_campaign(root, fleet, [("smoke", "smoke", ["steer"], ())], 4, 2, loop,
                                                    purchases={"r8i": "spot", "r9g": "spot"})
            [dispatcher] = dispatchers.values()
            self.ledger = self.unit_attempts(dispatcher.result_root)
            paused, resumed = fleet.hosts[:4], fleet.hosts[4:]
            # While the phase was paused it wrote no lease, and its hosts finished the leases they held, each one
            # accepted, and stopped themselves when their idle time ran out.
            self.assertEqual([], [entry for entry in fleet.log[marks["paused"]:marks["resumed"]] if entry[0] == "lease"])
            sessions = {host.session for host in paused}
            self.assertEqual(*(sorted((row["host_session"], row["sequence"]) for row in self.ledger
                                      if row["outcome"] == outcome and row["host_session"] in sessions)
                               for outcome in ("leased", "accepted")))
            self.assertEqual(sessions, {entry[1] for entry in fleet.log if entry[0] == "claim"})
            # Draining, it launched no host, while its live hosts took its queued work.
            log_mark, launches, _ = marks["drained"]
            self.assertEqual((8, launches), (len(fleet.hosts), len(fleet.launches)))
            self.assertTrue(any(entry[0] == "lease" and entry[3] for entry in fleet.log[log_mark:marks["stopped"]]))
            # The stop gave every host a stop lease behind the leases it held, none interrupted, and the controller
            # ended once their cleanup was proven, with the phase's remaining work still queued.
            self.assertEqual({host.session for host in resumed},
                             {entry[1] for entry in fleet.log[marks["stopped"]:] if entry[0] == "lease" and
                              entry[3] is None})
            self.assertEqual([], [entry for entry in fleet.log[marks["stopped"]:] if entry[0] == "lease" and
                                  entry[3]])
            self.assertEqual(([], {}, set()), (failed, dispatcher.workers, {
                state for instances in fleet.region_instances.values() for state in
                {instance.state for instance in instances}} - {"terminated"}))
            self.assertTrue(dispatcher.queued())
            self.assertFalse((dispatcher.result_root / RUN_CAMPAIGN.PHASE_COMPLETE).exists())
            self.assertIn("campaign stopped by dain: enough", log.getvalue())

    def test_a_launch_refusal_reads_cleared_once_its_pool_stops_launching(self):
        units = {f"watch-{index}": {"unit_id": f"watch-{index}", "shard_id": "watch", "unit_index": str(index),
                                    "estimated_seconds": "900", "groups": [{"group_id": "1", "estimated_seconds": 900}]}
                 for index in range(1, 9)}
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            # Oregon's vCPUs hold two of the four hosts the limits allow, so the fleet's quota refuses the next
            # launch. Two minutes into that refusal, Dain lowers the fleet to the two hosts it has: no pool then has
            # room to launch, so none is refused.
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts", units=units, timed=True, setup_seconds=lambda host: 67)
            reader = InMemoryMetricReader()
            self.campaign_settings(root, 4, 2)
            self.control(root, "set-limit", "region", "us-west-2", "max-vcpus", "4")
            refusals, pinned = [], []

            def loop():
                refusals.append({point.attributes["reason"]: point.value
                                 for resource in reader.get_metrics_data().resource_metrics
                                 for scope in resource.scope_metrics for metric in scope.metrics
                                 if metric.name == "regulator_launch_refused_seconds"
                                 for point in metric.data.data_points})
                if not pinned and refusals[-1].get("fleet quota", 0) >= 120:
                    self.control(root, "set-limit", "max-hosts", "2")
                    pinned.append(len(refusals))

            failed, _ = self.run_campaign(root, fleet, [("smoke", "smoke", ["watch"], ())], 4, 2, loop,
                                          telemetry.Telemetry("campaign", [reader]),
                                          purchases={"r8i": "spot", "r9g": "spot"})
            self.assertEqual([], failed)
            # From the loop after the limit falls, the refusal reads 0, not its age.
            self.assertEqual([{"fleet quota": 0}], list(map(dict, set(
                tuple(refused.items()) for refused in refusals[pinned[0] + 1:]))))

    def test_controller_exports_its_progress_health_events_and_unit_attempts(self):
        units = {f"watch-{index}": {"unit_id": f"watch-{index}", "shard_id": "watch", "unit_index": str(index),
                                    "estimated_seconds": "900", "groups": [{"group_id": "1", "estimated_seconds": 900}]}
                 for index in range(1, 9)}
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            # Two hosts of each platform measure eight 15-minute units, each in its estimate. Two and a half minutes in, with every host
            # running its first lease and holding its next, Dain pauses the phase, and resumes it once its hosts have
            # stopped.
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts", units=units, timed=True, setup_seconds=lambda host: 67)
            reader, spans, logs = InMemoryMetricReader(), InMemorySpanExporter(), InMemoryLogRecordExporter()
            exported = telemetry.Telemetry("campaign", [reader], [SimpleSpanProcessor(spans)],
                                           [SimpleLogRecordProcessor(logs)])
            snapshots, steered = [], []

            def loop():
                data = reader.get_metrics_data()
                snapshots.append({metric.name: {tuple(sorted(point.attributes.items())): point.value
                                                for point in metric.data.data_points}
                                  for resource in data.resource_metrics for scope in resource.scope_metrics
                                  for metric in scope.metrics})
                if fleet.sleeps == 10:
                    self.control(root, "pause", "smoke", "--reason", "checking the image")
                    steered.append("paused")
                elif steered == ["paused"] and all(host.status is not None for host in fleet.hosts):
                    self.control(root, "resume", "smoke")
                    steered.append("resumed")

            failed, _ = self.run_campaign(root, fleet, [("smoke", "smoke", ["watch"], ())], 4, 2, loop,
                                          exported, purchases={"r8i": "spot", "r9g": "spot"})
            self.assertEqual([], failed)
            # As the hosts run, the gauges hold the progress model: each worker's group with its estimate and
            # elapsed seconds, the work left, its correction and finish, and each pool's slack.
            running = snapshots[9]

            def values(name, **labels):
                return [value for attributes, value in running[name].items()
                        if labels.items() <= dict(attributes).items()]

            pools = {"r8i/r8i.large", "r9g/r9g.large"}
            self.assertEqual(4, sum(values("regulator_workers", phase="smoke", state="running")))
            # Once no worker boots, each pool's booting count reads 0 rather than the last count it had.
            self.assertEqual([0, 0], values("regulator_workers", phase="smoke", state="booting"))
            self.assertEqual((16, 0, 8), (*values("regulator_units"), *values("regulator_accepted_units"),
                                          sum(values("regulator_queued_items"))))
            self.assertEqual(16 * 900, sum(values("regulator_work_left_seconds")))
            self.assertEqual([1.0, 1.0, 1, 1], values("regulator_correction") + values("regulator_slack_min"))
            self.assertEqual(pools, {dict(attributes)["pool"] for attributes in running["regulator_finish_time"]})
            self.assertEqual([900] * 4, values("regulator_worker_group_estimated_seconds", group="1"))
            self.assertEqual(4, len({dict(attributes)["session"] for attributes in
                                     running["regulator_worker_group_elapsed_seconds"]}))
            for name in ("regulator_loop_seconds", "regulator_boot_window_hosts", "regulator_spending_stop",
                         "regulator_spend_usd", "regulator_spend_projected_usd", "regulator_max_usd",
                         "regulator_region_vcpus", "regulator_deadline_time", "regulator_boot_to_ready_seconds"):
                self.assertTrue(running[name], name)
            # The pause shows as the phase's state, and each host that stopped itself idle while the pause held its
            # queued work counts as an idle stop with work queued.
            self.assertTrue(any(snapshot["regulator_phase_state"].get(
                (("phase", "smoke"), ("state", "paused"))) for snapshot in snapshots))
            self.assertEqual(0, snapshots[-1]["regulator_phase_state"][(("phase", "smoke"), ("state", "paused"))])
            self.assertEqual({(("phase", "smoke"), ("queued", "true")): 4}, snapshots[-1]["regulator_idle_stops"])
            # Each unit attempt is traced from its lease to its acceptance, its steps one after another.
            attempts = [span for span in spans.get_finished_spans() if span.name == "unit attempt"]
            self.assertEqual(16, len(attempts))
            for attempt in attempts:
                steps = sorted((span for span in spans.get_finished_spans()
                                if span.parent and span.parent.span_id == attempt.context.span_id),
                               key=lambda span: span.start_time)
                self.assertEqual((["leased", "started", "uploaded", "accepted"], "accepted", 900, 900.0),
                                 ([span.name for span in steps], attempt.attributes["outcome"],
                                  attempt.attributes["estimated_seconds"], attempt.attributes["measured_seconds"]))
                self.assertEqual([attempt.start_time, *(span.end_time for span in steps)],
                                 [*(span.start_time for span in steps), attempt.end_time])
            # The events are logged with the local time in each line, Dain's changes in his name.
            records = [record.log_record for record in logs.get_finished_logs()]
            for record in records:
                self.assertRegex(record.body, r"^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]{8} \S+ ")
            self.assertEqual(["controller-started", "phase-starting", "milestone", "milestone", "phase-complete"],
                             [record.event_name for record in records if record.event_name != "settings-change"])
            self.assertEqual(["first host reporting", "replica 1 accepted"],
                             [record.attributes["milestone"] for record in records if record.event_name == "milestone"])
            self.assertEqual(["smoke paused: checking the image", "smoke running"],
                             [record.attributes["change"] for record in records if record.attributes.get("actor") == "dain"])

    def test_a_new_region_a_raised_limit_a_pin_and_a_finished_phase_move_hosts_the_next_loop(self):
        units = {f"{shard}-{index}": {"unit_id": f"{shard}-{index}", "shard_id": shard, "unit_index": str(index),
                                      "estimated_seconds": "900",
                                      "groups": [{"group_id": "1", "estimated_seconds": 900}]}
                 for shard, count in (("short", 2), ("long", 6)) for index in range(1, count + 1)}
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            # The smoke and primary phases share four hosts, all Oregon's vCPUs allow, measuring 15-minute units.
            # Once the hosts run, Dain adds Frankfurt, and two loops later raises the limits to eight hosts. Once the
            # smoke phase completes and the primary runs eight hosts, he pauses it, and once a host of each platform
            # has finished its leases while others still run theirs, pins it to six hosts and resumes it.
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts", units=units, timed=True, setup_seconds=lambda host: 67)
            self.campaign_settings(root, 4, 2)
            self.control(root, "set-limit", "region", "us-west-2", "max-vcpus", "8")
            primary = root / "primary"
            marks = {}

            def workers(state):
                """The primary's workers in the state, by host epoch, as its controller holds them."""
                dispatcher = self.running.get("primary")
                return {} if dispatcher is None else {
                    epoch: worker.job.platform.name for epoch, worker in dispatcher.workers.items()
                    if worker.state(epoch in dispatcher.terminated) == state}

            def finish():
                state = RUN_CAMPAIGN.read_fleet_state(root / RUN_CAMPAIGN.STATE_FILE)
                return RUN_CAMPAIGN.phase_progress(primary, RUN_CAMPAIGN.owner_fleet(state, "primary", fleet.time()),
                                                   True).finish

            def loop():
                if "added" not in marks and len(fleet.hosts) == 4 and all(host.booted for host in fleet.hosts):
                    self.control(root, "add-region", "eu-central-1", "--max-vcpus", "8", aws=fleet)
                    marks["added"] = fleet.sleeps
                elif "raised" not in marks and "added" in marks and fleet.sleeps == marks["added"] + 2:
                    self.control(root, "set-limit", "max-hosts", "8")
                    self.control(root, "set-limit", "max-hosts-per-platform", "4")
                    marks["raised"] = (len(fleet.launches), finish())
                elif "raised" in marks and "estimate" not in marks:
                    marks["estimate"] = (fleet.launches[marks["raised"][0]:], finish())
                elif "finished" not in marks and (root / "smoke" / RUN_CAMPAIGN.PHASE_COMPLETE).exists():
                    marks["finished"] = len(fleet.launches)
                elif "finished" in marks and "flowed" not in marks:
                    marks["flowed"] = fleet.launches[marks["finished"]:]
                elif "flowed" in marks and "paused" not in marks and len(workers("running")) == 8:
                    self.control(root, "pause", "primary")
                    marks["paused"] = True
                elif "paused" in marks and "pinned" not in marks and workers("running") and set(
                        workers("idle").values()) == {"r8i", "r9g"}:
                    self.control(root, "set-share", "primary", "6")
                    self.control(root, "resume", "primary")
                    marks["pinned"] = (len(fleet.log), workers("idle"))
                elif "pinned" in marks and "shed" not in marks:
                    marks["shed"] = [entry[1] for entry in fleet.log[marks["pinned"][0]:]
                                     if entry[0] == "lease" and entry[3] is None]

            failed, dispatchers = self.run_campaign(root, fleet, [("smoke", "smoke", ["short"], ()),
                                                                  ("primary", "primary", ["long"], ())], 4, 2, loop,
                                                    purchases={"r8i": "spot", "r9g": "spot"})
            self.assertEqual([], failed)
            for dispatcher in dispatchers.values():
                self.assert_accepted(dispatcher)
            # The loop after the raise launched the primary's four new hosts, every one in Frankfurt, and its finish
            # estimate fell to a third as its pools went from one host each to three.
            launched, estimate = marks["estimate"]
            self.assertEqual(([("eu-central-1", "r8i")] * 2 + [("eu-central-1", "r9g")] * 2, marks["raised"][1] / 3),
                             (sorted((launch.region, launch.platform) for launch in launched), estimate))
            # The loop after the smoke phase completed, its two Oregon hosts' places went to the primary.
            self.assertEqual([("us-west-2", "r8i"), ("us-west-2", "r9g")],
                             sorted((launch.region, launch.platform) for launch in marks["flowed"]))
            # The pin stop-leased one idle host of each platform and none running a lease, and every lease the
            # primary wrote was accepted.
            _, idle = marks["pinned"]
            epochs = {host.session: int(host.epoch) for host in fleet.hosts}
            self.assertEqual(["r8i", "r9g"], sorted(idle.get(epochs[session], "running") for session in marks["shed"]))
            ledger = self.unit_attempts(dispatchers["primary"].result_root)
            self.assertEqual(*(sorted((row["platform"], row["unit_id"], row["replica"]) for row in ledger
                                      if row["outcome"] == outcome) for outcome in ("leased", "accepted")))

    def test_lowered_host_limit_sheds_hosts_behind_their_leases(self):
        units = {f"long-{index}": {"unit_id": f"long-{index}", "shard_id": "long", "unit_index": str(index),
                                   "estimated_seconds": "900", "groups": [{"group_id": "1", "estimated_seconds": 900}]}
                 for index in range(1, 7)}
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            # Four hosts run the primary's 15-minute units, and once all four run, Dain lowers the fleet's limit to
            # two hosts. The next loop stop-leases one host of each platform behind the leases it holds, and every
            # lease is still accepted.
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts", units=units, timed=True)
            marks = {}

            def loop():
                dispatcher = self.running.get("primary")
                if dispatcher is None:
                    return
                workers = dispatcher.workers.values()
                if "lowered" not in marks and len(workers) == 4 and all(worker.started for worker in workers):
                    self.control(root, "set-limit", "max-hosts", "2")
                    marks["lowered"] = True
                elif "lowered" in marks and "open" not in marks:
                    marks["open"] = sorted(worker.job.platform.name for worker in workers if worker.open())

            failed, dispatchers = self.run_campaign(root, fleet, [("primary", "primary", ["long"], ())], 4, 2, loop,
                                                    purchases={"r8i": "spot", "r9g": "spot"})
            self.assertEqual([], failed)
            self.assertEqual(["r8i", "r9g"], marks["open"])
            ledger = self.unit_attempts(dispatchers["primary"].result_root)
            self.assertEqual(*(sorted((row["platform"], row["unit_id"], row["replica"]) for row in ledger
                                      if row["outcome"] == outcome) for outcome in ("leased", "accepted")))

    def test_leftover_is_retried_and_a_leak_stops_spending_while_the_other_phase_finishes_its_leases(self):
        units = {f"{shard}-{index}": {"unit_id": f"{shard}-{index}", "shard_id": shard, "unit_index": str(index),
                                      "estimated_seconds": "900",
                                      "groups": [{"group_id": "1", "estimated_seconds": 900}]}
                 for shard, count in (("short", 1), ("long", 4)) for index in range(1, count + 1)}
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()) as log:
            # Two phases share the fleet. The smoke phase's two hosts measure a unit each and stop: the r8i host's
            # prefix will not empty, and the r9g host's instance never terminates. The primary phase's four hosts
            # measure 15-minute units meanwhile.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", units=units, timed=True, setup_seconds=lambda host: 67,
                                  stuck_prefix=lambda host: host.epoch == "1", leaked=lambda host: host.epoch == "2")
            with fleet.stubs():
                running = {}
                # The campaign's controller gives each phase its share of each platform's hosts.
                for phase, shard, share in (("smoke", "short", 1), ("primary", "long", 2)):
                    dispatcher = self.dispatcher(Path(temporary), fleet, phase, [shard], 6, 3,
                                                 purchases={"r8i": "spot", "r9g": "spot"}, shared=True, results=phase)
                    dispatcher.share = dict.fromkeys(("r8i", "r9g"), share)
                    running[dispatcher] = dispatcher.begin()
                    dispatcher.recover()
                smoke, primary = phases = list(running)
                budget = self.budget

                def run(until):
                    # Each loop runs each phase not yet over, as one controller would.
                    while running and not until():
                        for dispatcher, deadline in list(running.items()):
                            if dispatcher.step(deadline):
                                del running[dispatcher]
                        fleet.sleep(RUN_CAMPAIGN.UNIT_POLL_SECONDS)

                def spending_stop():
                    stop = CampaignSettings(Path(temporary)).current["spending_stop"]
                    return stop and stop["reason"]

                run(spending_stop)
                stuck, leaked = (next(host for host in fleet.hosts if host.epoch == epoch) for epoch in "12")
                # The smoke host whose instance never terminated stopped the fleet's spending, while the primary
                # phase's hosts measured their leases.
                self.assertEqual(f"instance {leaked.instance_id} of {leaked.session} did not terminate",
                                 spending_stop())
                self.assertIn(f"leak: instance {leaked.instance_id}", log.getvalue())
                self.assertEqual(4, len(primary.workers))
                launches = len(fleet.launches)
                run(lambda: not primary.workers)
                # The primary phase's hosts finished every lease they held: each unit leased was accepted. With no
                # extension or launch admitted, its remaining work waits.
                ledger = self.unit_attempts(primary.result_root)
                self.assertEqual(*(sorted((row["platform"], row["unit_id"], row["replica"]) for row in ledger
                                          if row["outcome"] == outcome) for outcome in ("leased", "accepted")))
                self.assertEqual(launches, len(fleet.launches))
                self.assertIn("queued-capacity\tspending_stop", primary.attempts_path.read_text())
                # The prefix that would not empty was retried every loop since its host stopped, and the primary
                # phase went on leasing meanwhile.
                self.assertGreater(log.getvalue().count(f"leftover: the prefix of {stuck.session},"), 100)
                self.assertIn("lease", [entry[0] for entry in fleet.log[fleet.log.index(("exit", stuck.session)):]
                                        if entry[1] not in (stuck.session, leaked.session)])
                # Once the prefix empties, the instance terminates and the stop is cleared, both phases finish.
                fleet.stuck_prefix = fleet.leaked = lambda host: False
                budget.settings.change("dain", "clear the spending stop",
                                       lambda settings: settings.update(spending_stop=None))
                run(lambda: False)
                for dispatcher in phases:
                    dispatcher.end()
                    self.assert_accepted(dispatcher)
                    self.assertEqual([], budget.reservations(dispatcher.result_root))

    def test_ended_host_takes_no_lease_while_its_cleanup_waits(self):
        with tempfile.TemporaryDirectory() as temporary:
            # Each of the two r8i hosts takes one unit. The first finishes its unit, claims its next lease as its idle
            # stop and exits; its cleanup deletes its leases, but S3 keeps its results for five minutes. The second
            # boots a minute later and fails its unit, which no host that held it may take again.
            fleet = FakeUnitFleet(Path(temporary) / "hosts", scripts={1: {2: "claim"}, 2: {1: "fail"}},
                                  setup_seconds=lambda host: 60 if host.number == 2 else 0)
            delete = fleet.delete

            def keep_results(region, bucket, keys):
                ended = fleet.hosts[0]
                delete(region, bucket, [key for key in keys if not (
                    key.startswith(ended.result_prefix) and fleet.time() < ended.ended_at + 300)])

            fleet.delete = keep_results
            self.run_phase(Path(temporary), fleet, "smoke", ["trino-like-specialized"], 4, 2)
            ended = fleet.hosts[0]
            self.assertEqual([], [entry for entry in fleet.log[fleet.log.index(("exit", ended.session)):]
                                  if entry[:2] == ("lease", ended.session)])

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

    def test_host_whose_group_heartbeats_are_lost_is_judged_by_its_completions(self):
        units = {"long": {"unit_id": "long", "shard_id": "long", "unit_index": "1", "estimated_seconds": "2700",
                          "groups": [{"group_id": str(group), "estimated_seconds": 900} for group in (1, 2, 3)]}}
        # The first host's lease runs three 15-minute groups, and only the heartbeat that started it lands. Its
        # third group runs past twice its estimate from that heartbeat, but not from its second group's completion,
        # so the host is never judged stalled: nor while the acceptance threads are slow to gather its completions,
        # nor once a restart during its third group finds the groups before it accepted and gone from S3. With none
        # of its heartbeats landing, its first group's completion still shows its progress, so once it stalls in its
        # second group it is judged stalled.
        for script, case, lost in (("silent-groups", "", []), ("silent-groups", "slow acceptance", []),
                                   ("silent-groups", "restart", []), ("silent-stall", "", ["stalled"])):
            with self.subTest(script=script, case=case), tempfile.TemporaryDirectory() as temporary, \
                    contextlib.ExitStack() as stack:
                fleet = FakeUnitFleet(Path(temporary) / "hosts", units=units, timed=True, pace=1.9,
                                      scripts={1: {1: script}}, abort_after=240 if case == "restart" else None)
                if case == "slow acceptance":
                    gather = RUN_CAMPAIGN.gather_group

                    def slow(*arguments):
                        fleet.clock += 4000
                        return gather(*arguments)

                    stack.enter_context(patch.object(RUN_CAMPAIGN, "gather_group", side_effect=slow))
                if case == "restart":
                    with self.assertRaisesRegex(SystemExit, "controller stopped"):
                        self.run_phase(Path(temporary), fleet, "smoke", ["long"], 4, 2)
                    fleet.abort_after = None
                self.run_phase(Path(temporary), fleet, "smoke", ["long"], 4, 2)
                self.assertEqual(lost, [row["detail"] for row in self.ledger if row["outcome"] == "lost"])
                self.assertEqual(len(lost), fleet.hosts[0].terminations)

    def test_termination_that_keeps_failing_leaks_from_its_first_request(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            # EC2 refuses to terminate the stalled host for half an hour, and the controller restarts ten minutes
            # after its first request. The restarted controller still asks, and the instance has leaked 15 minutes
            # after that first request.
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts", scripts={1: {1: "stall"}})
            terminate = fleet.terminate
            requests, leaks = [], []

            def refuse(region, ids):
                stop = CampaignSettings(root).current["spending_stop"]
                if stop and not leaks:
                    leaks.append(fleet.time())
                    self.control(root, "clear-spending-stop")
                if fleet.hosts[0].instance_id not in ids or requests and fleet.time() >= requests[0] + 1800:
                    return terminate(region, ids)
                if not requests:
                    fleet.abort_after = fleet.sleeps + 40
                requests.append(fleet.time())
                raise cloud.CloudError("RequestLimitExceeded")

            fleet.terminate = refuse
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(root, fleet, "smoke", ["trino-like"], 4, 2, shared=True)
            fleet.abort_after = None
            self.run_phase(root, fleet, "smoke", ["trino-like"], 4, 2, shared=True)
            self.assertLess(requests[0] + 600, max(requests))
            [leak] = leaks
            self.assertGreaterEqual(leak - requests[0], RUN_CAMPAIGN.TERMINATION_LEAK_SECONDS)
            self.assertLess(leak - requests[0], RUN_CAMPAIGN.TERMINATION_LEAK_SECONDS + RUN_CAMPAIGN.UNIT_POLL_SECONDS)

    def test_host_the_controller_stopped_settles_as_its_stop_across_restarts(self):
        with tempfile.TemporaryDirectory() as temporary:
            # Host 1 stalls, and its late semantic failure lands once its unit's replacement, host 3, is leased and
            # still booting, so host 3 can take nothing and is stopped. The controller restarts as it asks EC2 to
            # terminate host 3, which terminates during the outage. The restarted controller settles it as its own
            # stop: no boot failure shrinks the boot window, no failed session is logged, and once it is settled, a
            # restart leaves its slot uncharged.
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts", scripts={1: {1: "stall-semantic-early"}},
                                  setup_seconds=lambda host: 600 if host.number == 3 else 0)
            terminate = fleet.terminate

            def stop_then_restart(region, ids):
                if fleet.hosts[2:] and fleet.hosts[2].instance_id in ids:
                    fleet.abort_after = fleet.sleeps + 1
                terminate(region, ids)

            fleet.terminate = stop_then_restart
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_phase(root, fleet, "smoke", ["like-dfa-single-use"], 4, 2, shared=True)
            fleet.abort_after, fleet.terminate = None, terminate
            for _ in range(2):
                with self.dispatchers() as dispatchers, self.assertRaisesRegex(SystemExit, "unresolved units"):
                    self.run_phase(root, fleet, "smoke", ["like-dfa-single-use"], 4, 2, shared=True)
            stopped = int(fleet.hosts[2].epoch)
            self.assertEqual("controller-abort", [row["outcome"] for row in RUN_CAMPAIGN.read_attempts(
                root / "results" / "job-attempts.tsv") if int(row["host_epoch"]) == stopped][-1])
            self.assertEqual([BOOT_WINDOW_FIRST], self.boot_windows(root))
            with (root / "results" / "campaign-events.tsv").open(newline="") as events:
                self.assertNotIn(str(stopped), [row["host_epoch"] for row in csv.DictReader(events, delimiter="\t")])
            self.assertIn((stopped, 0), dispatchers[0][0].free_slots[("r8i", fleet.hosts[2].instance_type)])

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

    def test_resource_the_fleet_cannot_account_for_stops_spending_and_pauses_nothing(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()) as log:
            # The first look at the fleet finds a Spot request with no instance type. Once the stop stands, Dain
            # clears it.
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts")
            empty = {"quota": 736, "instances": [], "requests": []}
            looks = iter([RUN_CAMPAIGN.AccountMismatch("cannot account for a Spot instance or request with no "
                                                       "instance type")])

            def snapshot(*_):
                look = next(looks, empty)
                if isinstance(look, Exception):
                    raise look
                return look

            stops = []

            def loop():
                if CampaignSettings(root).current["spending_stop"]:
                    stops.append(CampaignSettings(root).current["spending_stop"]["reason"])
                    self.control(root, "clear-spending-stop")

            self.shared_budget(root, self.campaign_settings(root, 2, 1))
            with patch.object(RUN_CAMPAIGN, "shared_fleet_snapshot", side_effect=snapshot):
                failed, dispatchers = self.run_campaign(root, fleet, [("smoke", "smoke", ["trino-like-specialized"],
                                                                       ())], 2, 1, loop)
            self.assertEqual(["cannot account for a Spot instance or request with no instance type"], stops)
            self.assertNotIn("paused", log.getvalue())
            self.assertEqual([], failed)
            self.assert_accepted(dispatchers["smoke"])

    def test_sigterm_stops_only_the_controller_and_the_next_one_adopts_its_hosts(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()) as log:
            # SIGTERM reaches the controller two polls in, while its hosts measure their first leases.
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts")
            phases = [("smoke", "smoke", ["trino-like-specialized"], ())]

            def loop():
                if fleet.sleeps == 2:
                    signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)

            # It ends cleanly with one line, its hosts still running with their reservations held.
            self.assertEqual([], self.run_campaign(root, fleet, phases, 2, 1, loop)[0])
            self.assertEqual(1, log.getvalue().count("controller stopped by SIGTERM"))
            sessions = {host.session for host in fleet.hosts}
            self.assertEqual((2, sessions), (len(self.budget.reservations(root / "smoke")),
                                             {host.session for host in fleet.hosts if host.status is None}))
            # The next controller adopts them, leasing nothing twice, and finishes the phase.
            failed, dispatchers = self.run_campaign(root, fleet, phases, 2, 1)
            self.ledger = self.unit_attempts(dispatchers["smoke"].result_root)
            self.assertEqual([], failed)
            self.assert_accepted(dispatchers["smoke"])
            self.assertLessEqual(sessions, {row["host_session"] for row in self.leases("accepted")})
            leased = [(row["platform"], row["unit_id"], row["replica"]) for row in self.leases()]
            self.assertEqual(len(leased), len(set(leased)))

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

    def test_admitted_worker_launches_only_with_its_first_leases(self):
        shards = ["trino-like-specialized"]
        with self.subTest(scenario="leases denied"), tempfile.TemporaryDirectory() as temporary:
            # S3 refuses every lease for eight loops, longer than a slot's replacements would last: no worker
            # launches meanwhile, and each pool waits a short cool-down after each launch that found its leases
            # refused, spending none of its slot's replacements, so the phase finishes once S3 takes them again.
            fleet = FakeUnitFleet(Path(temporary) / "hosts")
            put = fleet.put
            denied = collections.defaultdict(set)

            def deny_leases(region, bucket, key, body, **options):
                if "/input/leases/" in key and fleet.sleeps < 8:
                    denied[json.loads(body)["platform"]].add(fleet.sleeps)
                    raise cloud.CloudError("SlowDown")
                return put(region, bucket, key, body, **options)

            fleet.put = deny_leases
            self.run_phase(Path(temporary), fleet, "smoke", shards, 2, 1)
            self.assertLessEqual(8, min(launch.loop for launch in fleet.launches))
            for loops in denied.values():
                self.assertLess(RUN_CAMPAIGN.MAX_REPLACEMENTS_PER_JOB, len(loops))
                self.assertFalse(any(loop + 1 in loops for loop in loops))
            with (Path(temporary) / "results" / "job-attempts.tsv").open(newline="") as attempts:
                outcomes = {(row["outcome"], row["detail"]) for row in csv.DictReader(attempts, delimiter="\t")
                            if row["outcome"] not in ("starting", "started", "stopped")}
            self.assertEqual({("retryable-infrastructure", RUN_CAMPAIGN.FIRST_LEASES_UNWRITTEN)}, outcomes)
        for scenario in ("disk full", "user data unreadable"):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as temporary, \
                    contextlib.redirect_stdout(io.StringIO()) as log:
                # In the loop that admits the smoke's two workers, the disk is full from the second's starting record
                # on, or the controller cannot read their user data. The smoke pauses for every error the loop met,
                # and Dain stops the campaign. A worker admitted before an admission error still launches with its
                # leases, one whose launch was never asked for has ended, so the stop waits for no boot allowance,
                # and no admission's reservation outlives the stop.
                root = Path(temporary)
                fleet = FakeUnitFleet(root / "hosts")
                write_attempt = RUN_CAMPAIGN.write_attempt
                starts, stopped = [], []

                def full_disk(path, job, outcome, detail="-"):
                    if outcome == "starting":
                        starts.append((job, fleet.sleeps))
                    if scenario == "disk full" and len(starts) > 1 and starts[1][1] == fleet.sleeps:
                        raise OSError(28, "No space left on device")
                    write_attempt(path, job, outcome, detail)

                def loop():
                    if "smoke: paused: " in log.getvalue() and not stopped:
                        self.control(root, "stop")
                        stopped.append(fleet.sleeps)

                unreadable = OSError(5, "Input/output error")
                with patch.object(RUN_CAMPAIGN, "write_attempt", side_effect=full_disk), \
                        patch.object(RUN_CAMPAIGN, "worker_user_data", side_effect=unreadable
                                     if scenario == "user data unreadable" else RUN_CAMPAIGN.worker_user_data):
                    failed, _ = self.run_campaign(root, fleet, [("smoke", "smoke", shards, ())], 2, 1, loop)
                self.assertEqual([], failed)
                self.assertEqual({}, json.loads((root / RUN_CAMPAIGN.STATE_FILE).read_text())["reservations"])
                if scenario == "disk full":
                    [launched] = fleet.hosts
                    self.assertEqual((starts[0][0].platform.name, [1, 2]), (launched.platform, sorted(
                        sequence for sequence, lease in launched.leases.items() if not lease.get("stop"))))
                    self.assertIn("smoke: paused: [Errno 28] No space left on device; launching the workers admitted "
                                  "before it also failed: [Errno 28] No space left on device", log.getvalue())
                else:
                    self.assertEqual([], fleet.hosts)
                    self.assertEqual(stopped[0] + 2, fleet.sleeps)
                    self.assertIn("smoke: paused: [Errno 5] Input/output error", log.getvalue())

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

    def test_pause_spends_none_of_the_phase_deadline(self):
        # The primary's controller stops and is down for half an hour. Paused before the stop, or by Dain as the
        # controller went down, and resumed ten minutes after the restart, three times its ten-minute deadline later,
        # it still launches for its queued work. Resumed as the controller went down, the outage spends its deadline.
        for paused, outage, failed in (("before the stop", None, []), ("in the outage", "pause", []),
                                       ("before the stop", "resume", ["primary"])):
            with self.subTest(paused=paused, outage=outage), tempfile.TemporaryDirectory() as temporary, \
                    contextlib.redirect_stdout(io.StringIO()):
                root = Path(temporary)
                phases = [("primary", "primary", ["like-dfa-single-use"], ())]
                fleet = FakeUnitFleet(root / "hosts", abort_after=10 if paused == "before the stop" else 1)

                def pause():
                    if paused == "before the stop" and \
                            CampaignSettings(root).current["phases"]["primary"]["state"] == "running":
                        self.control(root, "pause", "primary")

                with self.assertRaisesRegex(SystemExit, "controller stopped"):
                    self.run_campaign(root, fleet, phases, 2, 1, pause, deadline=600)
                fleet.abort_after = None
                if outage:
                    with fleet.stubs():
                        self.control(root, outage, "primary")
                fleet.outage(1800)
                resume_at = fleet.clock + 600

                def resume():
                    if fleet.clock >= resume_at and \
                            CampaignSettings(root).current["phases"]["primary"]["state"] == "paused":
                        self.control(root, "resume", "primary")

                self.assertEqual(failed, [phase.key for phase in self.run_campaign(
                    root, fleet, phases, 2, 1, resume, deadline=600)[0]])

    def test_a_phase_running_for_days_checks_its_regions_daily(self):
        # Dain pauses the primary at its first poll and resumes it a day later. Each check of a phase's regions puts
        # its archives again once they are five days old, so a phase that runs for a week cannot outlive them.
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts")
            checked, steered = [], []

            def check(arguments, platforms):
                checked.append(fleet.clock)
                return check_regions(arguments, platforms)

            def steer():
                if not steered:
                    self.control(root, "pause", "primary")
                    steered.append("paused")
                elif steered == ["paused"] and len(checked) == 1:
                    fleet.clock += RUN_CAMPAIGN.REGION_RECHECK_SECONDS
                    steered.append("a day later")
                elif len(checked) == 2 and "resumed" not in steered:
                    self.control(root, "resume", "primary")
                    steered.append("resumed")

            check_regions = RUN_CAMPAIGN.check_regions
            with patch.object(RUN_CAMPAIGN, "check_regions", side_effect=check):
                failed, dispatchers = self.run_campaign(root, fleet, [("primary", "primary", ["like-dfa-single-use"],
                                                                       ())], 2, 1, steer)
            self.assertEqual([], failed)
            self.assert_accepted(dispatchers["primary"])
            # Its setup checked them, and with no region changed, a day later its controller checked them again.
            self.assertEqual(2, len(checked))
            self.assertGreaterEqual(checked[1] - checked[0], RUN_CAMPAIGN.REGION_RECHECK_SECONDS)

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
            fleet = FakeUnitFleet(Path(temporary) / "hosts", full={("r8i", "spot"): 1}, abort_after=1)
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

    def test_completion_marker_unlike_its_lease_pauses_only_its_phase(self):
        # A marker that names another campaign, or lacks a field the controller reads, is not evidence of this lease,
        # so the controller pauses the smoke phase rather than judge the unit, while the primary phase finishes. Once
        # the primary is complete and the paused phase's hosts' cleanup is proven, Dain resumes it, and it pauses
        # again as its dispatch restarts from its ledgers, since the marker still stands, so he stops the campaign.
        for script, error in (
                ("foreign-marker", r"unit completion marker campaign-e1-[0-9TZ]+/1 group 1 does not match its "
                                   r"lease: \['campaign_id'\]"),
                ("partial-marker", r"unit completion marker campaign-e1-[0-9TZ]+/1 group 1 lacks "
                                   r"\['result_sha256'\]")):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as temporary, \
                    contextlib.redirect_stdout(io.StringIO()) as log:
                root = Path(temporary)
                fleet = FakeUnitFleet(root / "hosts", scripts={1: {1: script}})
                resumed = []

                def loop():
                    if not resumed and (root / "primary" / RUN_CAMPAIGN.PHASE_COMPLETE).exists() and \
                            not self.budget.reservations(root / "smoke"):
                        self.control(root, "resume", "smoke")
                        resumed.append(log.getvalue().count("smoke: paused: "))
                    elif resumed and log.getvalue().count("smoke: paused: ") > resumed[0]:
                        self.control(root, "stop")

                failed, dispatchers = self.run_campaign(
                    root, fleet, [("smoke", "smoke", ["trino-like-specialized"], ()),
                                  ("primary", "primary", ["like-dfa-single-use"], ())], 4, 2, loop)
                self.assertEqual([], failed)
                paused = CampaignSettings(root).current["phases"]["smoke"]
                self.assertEqual("paused", paused["state"])
                self.assertRegex(paused["reason"], error)
                self.assertEqual(([1], 2), (resumed, log.getvalue().count("smoke: paused: ")))
                # Its hosts finished their leases and stopped, each one's cleanup proven, and the unit was not
                # accepted.
                smoke = dispatchers["smoke"]
                self.assertEqual([], self.budget.reservations(smoke.result_root))
                self.assertEqual([], [host.session for host in fleet.hosts if host.status is None])
                self.assertNotIn(fleet.hosts[0].leases[1]["unit_id"], {
                    receipt["unit_id"] for receipt in RUN_CAMPAIGN.read_accepted_units(smoke.accepted_path)})
                self.assert_accepted(dispatchers["primary"])

    def test_setup_that_fails_pauses_its_phase_until_it_is_resumed(self):
        # The controller stops with the smoke's hosts live, and the region's Spot quota then drops below its limit,
        # so the restarted controller's setup of the smoke refuses it. The smoke pauses with the reason, rather than
        # failing; Dain lowers the limit and resumes it, and its setup runs again and adopts its hosts.
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temporary)
            phases = [("smoke", "smoke", ["trino-like-specialized"], ())]
            fleet = FakeUnitFleet(root / "hosts", abort_after=2)
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_campaign(root, fleet, phases, 2, 1)
            fleet.abort_after = None
            region = RUN_CAMPAIGN.default_regions()[0]
            fleet.quotas[region, "spot"] = 512
            reasons = []

            def loop():
                steering = CampaignSettings(root).current["phases"]["smoke"]
                if not reasons and steering["state"] == "paused":
                    reasons.append(steering["reason"])
                    self.control(root, "set-limit", "region", region, "max-vcpus", "512")
                    self.control(root, "resume", "smoke")

            failed, dispatchers = self.run_campaign(root, fleet, phases, 2, 1, loop)
            self.assertEqual([], failed)
            self.assertEqual([f"the campaign settings' max_vcpus exceeds the Standard Spot quota after its reserve "
                              f"in {region}"], reasons)
            self.assert_accepted(dispatchers["smoke"])
            self.assertEqual(2, len(fleet.hosts))
            leased = [(row["platform"], row["unit_id"], row["replica"])
                      for row in self.unit_attempts(dispatchers["smoke"].result_root) if row["outcome"] == "leased"]
            self.assertEqual(len(leased), len(set(leased)))

    def test_phase_whose_setup_fails_holds_its_hosts_until_they_stop(self):
        # The controller stops with the smoke's hosts live, and the restarted controller's setup of the smoke fails
        # its check of AWS. Its hosts still count against the limits, so the primary launches none beside them, and
        # once Dain stops the campaign they take their stop leases and stop, each one's cleanup proven.
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts", abort_after=2)
            with self.assertRaisesRegex(SystemExit, "controller stopped"):
                self.run_campaign(root, fleet, [("smoke", "smoke", ["trino-like-specialized"], ())], 4, 1)
            fleet.abort_after = None
            check_regions = RUN_CAMPAIGN.check_regions
            paused = []

            def check(arguments, platforms):
                if arguments.phase == "smoke":
                    raise SystemExit("the smoke's regions failed their check")
                return check_regions(arguments, platforms)

            def loop():
                # The primary has three loops to launch beside the paused smoke before Dain stops the campaign.
                if CampaignSettings(root).current["phases"]["smoke"]["state"] == "paused":
                    paused.append("loop")
                    if len(paused) == 3:
                        self.control(root, "stop")

            with patch.object(RUN_CAMPAIGN, "check_regions", side_effect=check):
                failed, _ = self.run_campaign(
                    root, fleet, [("smoke", "smoke", ["trino-like-specialized"], ()),
                                  ("primary", "primary", ["like-dfa-single-use"], ())], 4, 1, loop)
            self.assertEqual([], failed)
            self.assertEqual(2, len(fleet.hosts))
            self.assertEqual([], self.budget.reservations(root / "smoke"))
            self.assertTrue(all(host.leases[max(host.leases)].get("stop") for host in fleet.hosts))

    def test_phase_paused_for_a_fault_reports_its_boots_and_takes_a_stop(self):
        # Host 1's marker names another campaign, so the smoke pauses while host 2 still boots, and Dain then stops the
        # campaign. Host 2's boot still reaches the boot window, and it takes its stop lease behind the leases it was
        # launched with, rather than idling until it stops itself.
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()) as log:
            root = Path(temporary)
            fleet = FakeUnitFleet(root / "hosts", scripts={1: {1: "foreign-marker"}},
                                  setup_seconds=lambda host: 0 if host.number == 1 else 120)

            def loop():
                if "smoke: paused: " in log.getvalue() and not CampaignSettings(root).current["stop"]:
                    self.assertFalse(fleet.hosts[1].booted)
                    self.control(root, "stop")

            failed, _ = self.run_campaign(root, fleet, [("smoke", "smoke", ["trino-like-specialized"], ())], 2, 1,
                                          loop)
            self.assertEqual([], failed)
            booting = fleet.hosts[1]
            [reservation] = [item for item in json.loads((root / RUN_CAMPAIGN.STATE_FILE).read_text())[
                "completed"].values() if str(item["host_epoch"]) == booting.epoch]
            self.assertIn("reported_at", reservation)
            self.assertTrue(booting.leases[max(booting.leases)].get("stop"))
            self.assertNotIn("claim", [entry[0] for entry in fleet.log if entry[1:2] == (booting.session,)])

    def test_semantic_failure_or_protocol_rejection_is_final(self):
        # A semantic failure stands though the host's interruption notice lands with it.
        for script, detail in (("semantic-failure", "semantic failure: status 44"),
                               ("interrupt-semantic", "semantic failure: status 44"),
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
