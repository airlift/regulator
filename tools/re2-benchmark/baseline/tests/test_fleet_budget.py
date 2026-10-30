from concurrent.futures import ThreadPoolExecutor
import contextlib
import copy
import io
import json
from operator import itemgetter
from pathlib import Path
import tempfile
import sys
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from campaign_settings import CampaignSettings
import fleet_budget

# The settings a campaign's first execution names, which the tests change as Dain would.
SETTINGS = {"max_hosts": 368, "max_hosts_per_platform": 368, "max_usd": 150, "max_machine_hours": 1500,
            "regions": {"us-west-2": {"bucket": "west", "max_vcpus": 736}},
            "platform_regions": {"r8i": ["us-west-2"]}, "purchase": {"r8i": "spot"},
            "hourly_rates": {"r8i.large": {"spot": 0.065, "total": 0.075}, "r8i.2xlarge": {"spot": 0.28, "total": 0.30},
                             "r9g.large": {"spot": 0.065, "total": 0.075}, "r9g.2xlarge": {"spot": 0.28, "total": 0.30}}}


def seed_settings(root, **values):
    """Seed the campaign root's settings as its first execution does, from SETTINGS with values replacing its."""
    return CampaignSettings.seed(root, {**copy.deepcopy(SETTINGS), **values})


class TestFleetBudget(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.settings = seed_settings(self.root)
        self.budget = fleet_budget.FleetBudget(self.root, self.settings, 0)
        self.snapshot = {"quota": 736, "instances": [], "requests": []}

    def change(self, update):
        self.settings.change("dain", "test", update)

    def job(self, epoch, large=False):
        return SimpleNamespace(market="spot", region="us-west-2", host_epoch=epoch, logical_identity="r8i/test/replica-1",
                               platform=SimpleNamespace(instance_type="r8i.2xlarge" if large else "r8i.large",
                                                        vcpus=8 if large else 2))

    def reserve(self, epoch, large=False, campaign="baseline", now=1000, seconds=7200):
        """Reserve a host, which reports at once, so the boot window never holds it."""
        admitted = self.budget.reserve(campaign, self.job(epoch, large), self.root / campaign,
                                       lambda: self.snapshot, now, until=now + seconds)
        self.budget.report_boots(campaign, [epoch], now)
        return admitted

    def test_two_controllers_cannot_reserve_the_same_remaining_vcpus(self):
        self.snapshot["quota"] = 2
        def reserve(campaign):
            independent = fleet_budget.FleetBudget(self.root, CampaignSettings(self.root), 0)
            return independent.reserve(campaign, self.job(1), self.root / campaign, lambda: self.snapshot, 1000,
                                       until=8200)[0]
        with ThreadPoolExecutor(max_workers=2) as pool:
            outcomes = list(pool.map(reserve, ("baseline", "language")))
        self.assertEqual(sorted(outcomes), [False, True])
        state = json.loads(self.budget.state_path.read_text())
        self.assertEqual(len(state["reservations"]), 1)

    def test_counts_outstanding_requests_and_deduplicates_visible_instances(self):
        self.reserve(1)
        reservations = json.loads(self.budget.state_path.read_text())["reservations"]
        self.snapshot["instances"] = [
            {"id": "i-own", "vcpus": 2, "campaign_id": "baseline", "host_epoch": 1},
            {"id": "i-other", "vcpus": 8}]
        self.snapshot["requests"] = [{"instance_id": "i-own", "vcpus": 2},
                                     {"instance_id": "i-other", "vcpus": 8},
                                     {"instance_id": None, "vcpus": 16}]
        current = fleet_budget.capacity(self.snapshot, reservations, "us-west-2", SETTINGS["regions"]["us-west-2"], 0)
        self.assertEqual(current, {"available": 710, "reserved": 2, "external": 24})

    def test_larger_workers_consume_actual_vcpus(self):
        self.snapshot["quota"] = 10
        self.assertTrue(self.reserve(1, large=True)[0])
        self.assertTrue(self.reserve(2)[0])
        self.assertFalse(self.reserve(3)[0])

    def test_duplicate_unfulfilled_requests_cannot_share_a_reservation(self):
        self.reserve(1)
        request = {'instance_id': None, 'vcpus': 2,
                   'campaign_id': 'baseline', 'host_epoch': 1}
        reservations = json.loads(self.budget.state_path.read_text())['reservations']
        for instance_ids in ((None, None), ('i-first', 'i-second')):
            self.snapshot['requests'] = [
                {**request, 'instance_id': instance_id} for instance_id in instance_ids]
            with self.subTest(instance_ids=instance_ids), \
                    self.assertRaisesRegex(fleet_budget.BudgetError, 'multiple Spot resources'):
                fleet_budget.capacity(self.snapshot, reservations, 'us-west-2', SETTINGS['regions']['us-west-2'], 0)

    def test_request_for_a_visible_instance_is_deduplicated_before_reservation_check(self):
        self.reserve(1)
        self.snapshot['instances'] = [
            {'id': 'i-first', 'vcpus': 2, 'campaign_id': 'baseline', 'host_epoch': 1}]
        self.snapshot['requests'] = [
            {'instance_id': 'i-first', 'vcpus': 2, 'campaign_id': 'baseline', 'host_epoch': 1}]
        reservations = json.loads(self.budget.state_path.read_text())['reservations']

        def capacity():
            return fleet_budget.capacity(self.snapshot, reservations, 'us-west-2', SETTINGS['regions']['us-west-2'], 0)

        self.assertEqual(capacity(), {'available': 734, 'reserved': 2, 'external': 0})
        self.snapshot['requests'].append(
            {'instance_id': None, 'vcpus': 2, 'campaign_id': 'baseline', 'host_epoch': 1})
        with self.assertRaisesRegex(fleet_budget.BudgetError, 'multiple Spot resources'):
            capacity()
        self.snapshot['requests'].pop()
        self.snapshot['instances'][0]['vcpus'] = 8
        with self.assertRaisesRegex(fleet_budget.BudgetError, 'reserved topology'):
            capacity()

    def test_stale_reservations_never_expire_without_cleanup(self):
        self.snapshot["quota"] = 2
        self.assertTrue(self.reserve(1)[0])
        self.assertFalse(self.reserve(2, now=100000)[0])
        with self.assertRaisesRegex(RuntimeError, "verified cleanup"):
            self.budget.release("baseline", 1, cleanup_verified=False, now=4600)
        self.budget.release("baseline", 1, cleanup_verified=True, now=4600)
        self.assertTrue(self.reserve(2, now=4600)[0])
        state = json.loads(self.budget.state_path.read_text())
        self.assertEqual(state["machine_hours"], 1)
        self.assertEqual(state["cost_usd"], 0.075)
        self.budget.release("baseline", 1, cleanup_verified=True, now=9999)
        self.assertEqual(json.loads(self.budget.state_path.read_text())["machine_hours"], 1)

    def test_hour_and_cost_ceilings_include_inflight_attempts(self):
        for field, value in (("max_machine_hours", 3), ("max_usd", 0.2)):
            with self.subTest(field=field):
                self.budget.state_path.unlink(missing_ok=True)
                self.change(lambda settings: settings.update({**SETTINGS, field: value}))
                self.assertTrue(self.reserve(1)[0])
                self.assertEqual(self.reserve(2), (False, "fleet spending or machine-hour ceiling"))

    def test_reservations_hold_issued_work_and_extend_within_the_ceilings(self):
        # An $8 fleet, at $0.10 an r8i.large hour.
        self.change(lambda settings: settings.update(max_usd=8) or
                    settings["hourly_rates"].update({"r8i.large": {"spot": 0.08, "total": 0.10}}))

        def admitted(seconds):
            self.budget.state_path.unlink(missing_ok=True)
            for count in range(SETTINGS["max_hosts"] + 1):
                if not self.reserve(count + 1, seconds=seconds)[0]:
                    return count
            self.fail("the fleet admitted more hosts than its limit")

        # Reserving each worker's whole planned span and cleanup, 7.75 hours, admitted 10 workers; reserving a
        # launch's boot and first lease, as the controller does, 1.5 hours, admits 53.
        self.assertEqual(10, admitted(27900))
        self.assertEqual(53, admitted(5400))
        # The fleet has $0.05 left, so it extends a host's reservation by a quarter hour but not by an hour, and a
        # host already covered needs nothing more.
        self.assertEqual((False, "fleet spending or machine-hour ceiling"),
                         self.budget.extend("baseline", 1, now=2000, until=6400 + 3600))
        self.assertEqual((True, "reserved"), self.budget.extend("baseline", 1, now=2000, until=6400 + 900))
        self.assertEqual((True, "reserved"), self.budget.extend("baseline", 2, now=2000, until=6000))
        reservations = json.loads(self.budget.state_path.read_text())["reservations"]
        self.assertEqual((7300, 6400), (reservations["baseline/1"]["reserved_until"],
                                        reservations["baseline/2"]["reserved_until"]))
        # Cleanup charges a host's actual hours, which returns the rest of its reservation.
        self.budget.release("baseline", 3, cleanup_verified=True, now=1000 + 1800)
        self.assertEqual((True, "reserved"), self.budget.extend("baseline", 1, now=1000 + 1800, until=7300 + 3600))

    def test_used_host_epochs_are_never_reserved_again(self):
        self.reserve(1)
        with self.assertRaisesRegex(RuntimeError, "already used"):
            self.reserve(1)
        self.budget.release("baseline", 1, cleanup_verified=True, now=1001)
        with self.assertRaisesRegex(RuntimeError, "already used"):
            self.reserve(1)

    def test_on_demand_allowance_admits_against_each_regions_quota_headroom(self):
        def allow_on_demand(settings):
            settings["regions"] = {"us-west-2": {"bucket": "west", "max_vcpus": 736, "on_demand_max_vcpus": 400},
                                   "eu-west-1": {"bucket": "ireland", "max_vcpus": 64, "on_demand_max_vcpus": 8},
                                   "ap-south-1": {"bucket": "mumbai", "max_vcpus": 64}}
            for rates in settings["hourly_rates"].values():
                rates["on_demand_total"] = 2 * rates["total"]

        self.change(allow_on_demand)
        # Other workloads leave Oregon 16 vCPUs of On-Demand headroom after the safety reserve.
        snapshots = {("us-west-2", "on-demand"): {"quota": 1000, "instances": [{"id": "i-other", "vcpus": 952}],
                                                  "requests": []},
                     ("eu-west-1", "on-demand"): {"quota": 1000, "instances": [], "requests": []},
                     ("us-west-2", "spot"): self.snapshot}

        def reserve(epoch, region, market="on-demand"):
            job = self.job(epoch, large=True)
            job.market, job.region = market, region
            return self.budget.reserve("baseline", job, self.root / "baseline",
                                       lambda: snapshots[(region, market)], 1000, until=8200)[0]

        # Spot reservations take no On-Demand headroom.
        self.assertTrue(reserve(1, "us-west-2", "spot"))
        self.assertTrue(reserve(2, "us-west-2"))
        self.assertTrue(reserve(3, "us-west-2"))
        self.assertFalse(reserve(4, "us-west-2"))
        # Ireland's own allowance binds before its quota, and Mumbai has none, so it launches no On-Demand.
        self.assertTrue(reserve(4, "eu-west-1"))
        self.assertFalse(reserve(5, "eu-west-1"))
        self.assertFalse(reserve(5, "ap-south-1"))
        reservations = json.loads(self.budget.state_path.read_text())["reservations"]
        self.assertEqual({key: (item["market"], item["hourly_rate"]) for key, item in reservations.items()},
                         {"baseline/1": ("spot", 0.30), "baseline/2": ("on-demand", 0.60),
                          "baseline/3": ("on-demand", 0.60), "baseline/4": ("on-demand", 0.60)})

    def test_each_region_admits_against_its_own_quota_and_limits(self):
        # Ireland's quota less the campaign's Spot vCPU reserve leaves five vCPUs, under its own limit and Oregon's.
        self.change(lambda settings: settings["regions"].update({"eu-west-1": {"bucket": "ireland", "max_vcpus": 8}}))
        self.budget = fleet_budget.FleetBudget(self.root, self.settings, 2)
        snapshots = {"us-west-2": self.snapshot, "eu-west-1": {"quota": 7, "instances": [], "requests": []}}

        def reserve(epoch, region):
            job = self.job(epoch)
            job.region = region
            return self.budget.reserve("baseline", job, self.root / "baseline", lambda: snapshots[region], 1000,
                                       until=8200)[0]

        self.assertTrue(reserve(1, "eu-west-1"))
        self.assertTrue(reserve(2, "eu-west-1"))
        self.assertFalse(reserve(3, "eu-west-1"))
        self.assertTrue(reserve(3, "us-west-2"))
        state = json.loads(self.budget.state_path.read_text())
        self.assertEqual({key: item["region"] for key, item in state["reservations"].items()},
                         {"baseline/1": "eu-west-1", "baseline/2": "eu-west-1", "baseline/3": "us-west-2"})
        with self.assertRaisesRegex(fleet_budget.BudgetError, "do not cover region ap-south-1"):
            reserve(4, "ap-south-1")

    def test_reservation_past_its_end_counts_as_spent_until_released(self):
        # A controller that slept, or died, leaves its host's reservation past its end; the host stopped by then.
        self.reserve(1)
        # Charged to now, the 1,500 machine-hour ceiling holds no more.
        self.assertEqual((False, "fleet spending or machine-hour ceiling"),
                         self.reserve(2, campaign="language", now=1000 + 1500 * 3600))
        # Released a thousand hours in, the same reservation is charged those hours and no more, so the fleet admits
        # the launch then.
        self.budget.state_path.unlink()
        self.reserve(1)
        self.budget.release("baseline", 1, cleanup_verified=True, now=1000 + 1000 * 3600)
        self.assertTrue(self.reserve(2, campaign="language", now=1000 + 1500 * 3600)[0])

    def test_spending_stop_refuses_launches_and_extensions_until_cleared(self):
        self.reserve(1)
        # While the settings file does not validate, the stop is not recorded, and the controller logs why and goes
        # on to try again.
        settings = self.budget.settings.path.read_text()
        self.budget.settings.path.write_text("{}\n")
        with contextlib.redirect_stdout(io.StringIO()) as log:
            self.assertFalse(self.budget.stop_spending("instance i-1 did not terminate"))
        self.assertIn("cannot record spending stop: instance i-1 did not terminate in the campaign settings: ",
                      log.getvalue())
        self.budget.settings.path.write_text(settings)
        self.assertTrue(self.budget.stop_spending("instance i-1 did not terminate"))
        # The first reason stands.
        self.budget.stop_spending("instance i-2 did not terminate")
        other = fleet_budget.FleetBudget(self.root, CampaignSettings(self.root), 0)
        self.assertEqual((False, "spending_stop"), self.reserve(2, campaign="language", now=1001))
        self.assertEqual((False, "spending_stop"), other.extend("baseline", 1, now=1002, until=9000))
        # A host keeps what it holds, and verified cleanup still returns it; the stop stands, as the controller's.
        self.assertEqual((True, "reserved"), other.extend("baseline", 1, now=1002, until=8200))
        other.release("baseline", 1, cleanup_verified=True, now=1003)
        self.assertEqual(("controller", "instance i-1 did not terminate"),
                         itemgetter("by", "reason")(CampaignSettings(self.root).current["spending_stop"]))
        self.assertEqual((False, "spending_stop"), self.reserve(3, campaign="language", now=1004))
        self.change(lambda settings: settings.update(spending_stop=None))
        self.assertTrue(self.reserve(3, campaign="language", now=1005)[0])

    def test_boot_window_holds_hosts_not_yet_reporting_and_halves_on_a_boot_failure(self):
        def launch(epoch, now):
            return self.budget.reserve("baseline", self.job(epoch), self.root / "baseline", lambda: self.snapshot,
                                       now, until=now + 7200)

        def window():
            return CampaignSettings(self.root).current["boot_window"]["size"]

        # Ten hosts may boot at once. One gone before it reported, as a Spot reclaim is, frees its place without
        # judging the window.
        self.assertEqual([(True, "reserved")] * 10 + [(False, "boot window")],
                         [launch(epoch, 1000) for epoch in range(1, 12)])
        self.budget.release("baseline", 1, cleanup_verified=True, now=1100)
        self.assertTrue(launch(11, 1100)[0])
        self.assertEqual(10, window())
        # A host that ended before it reported halves the window, never below five, and starts a new wave, which
        # the hosts launched before it are no part of.
        for epoch, now in ((2, 1200), (3, 1210)):
            self.budget.boot_failed("baseline", epoch, now=now)
            self.budget.release("baseline", epoch, cleanup_verified=True, now=now)
        self.assertEqual(5, window())
        self.assertEqual(5, self.budget.report_boots("baseline", range(4, 12), now=1250))
        self.assertTrue(launch(12, 1300)[0])
        self.budget.boot_failed("baseline", 12, now=1350)
        self.assertEqual(5, window())


if __name__ == "__main__":
    unittest.main()
