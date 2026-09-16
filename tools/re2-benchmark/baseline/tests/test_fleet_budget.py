from concurrent.futures import ThreadPoolExecutor
import copy
import json
from pathlib import Path
import tempfile
import sys
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
import fleet_budget


class TestFleetBudget(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.policy = {"schema_version": 1, "account": "123456789012", "region": "us-west-2",
                       "market": "spot", "spot_vcpu_reserve": 0, "max_vcpus": 736, "max_hosts": 368,
                       "max_pending_launches": 32, "max_machine_hours": 1500, "max_usd": 150,
                       "max_attempt_seconds": 7200, "ancillary_reserve_usd": 15,
                       "minimum_launch_interval_seconds": 0,
                       "hourly_rates": {"r8i.large": {"spot": 0.065, "total": 0.075},
                                        "r8i.2xlarge": {"spot": 0.28, "total": 0.30}}}
        self.path = self.root / "policy.json"
        self.path.write_text(json.dumps(self.policy))
        self.budget = fleet_budget.FleetBudget(self.path)
        self.snapshot = {"quota": 736, "instances": [], "requests": []}

    def job(self, epoch, large=False):
        return SimpleNamespace(market="spot", host_epoch=epoch, logical_identity="r8i/test/replica-1",
                               platform=SimpleNamespace(instance_type="r8i.2xlarge" if large else "r8i.large",
                                                        vcpus=8 if large else 2))

    def reserve(self, epoch, large=False, campaign="baseline", now=1000):
        return self.budget.reserve(campaign, self.job(epoch, large), self.root / campaign,
                                   lambda: self.snapshot, now)

    def test_two_controllers_cannot_reserve_the_same_remaining_vcpus(self):
        self.snapshot["quota"] = 2
        def reserve(campaign):
            independent = fleet_budget.FleetBudget(self.path)
            return independent.reserve(campaign, self.job(1), self.root / campaign, lambda: self.snapshot, 1000)[0]
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
        current = fleet_budget.capacity(self.snapshot, reservations, self.policy)
        self.assertEqual(current, {"available": 710, "reserved": 2, "external": 24, "pending_launches": 0})

    def test_larger_workers_consume_actual_vcpus(self):
        self.snapshot["quota"] = 10
        self.assertTrue(self.reserve(1, large=True)[0])
        self.assertTrue(self.reserve(2)[0])
        self.assertFalse(self.reserve(3)[0])

    def test_finished_instances_do_not_consume_pending_launch_slots(self):
        self.policy['max_pending_launches'] = 1
        self.path.write_text(json.dumps(self.policy))
        self.budget = fleet_budget.FleetBudget(self.path)
        self.snapshot['quota'] = 6
        self.assertTrue(self.reserve(1)[0])
        self.assertFalse(self.reserve(2)[0])
        self.snapshot['instances'] = [
            {'id': 'i-first', 'vcpus': 2, 'campaign_id': 'baseline', 'host_epoch': 1}]
        self.assertTrue(self.reserve(2)[0])
        # First host has terminated but still owns its reservation until cleanup.
        # Restarting a controller must retain proof that it was already launched.
        self.budget = fleet_budget.FleetBudget(self.path)
        self.snapshot['instances'] = [
            {'id': 'i-second', 'vcpus': 2, 'campaign_id': 'baseline', 'host_epoch': 2}]
        self.assertTrue(self.reserve(3)[0])
        state = json.loads(self.budget.state_path.read_text())
        self.assertEqual(len(state['reservations']), 3)
        self.assertEqual(state['machine_hours'], 0)
        self.assertTrue(state['reservations']['baseline/1']['launch_observed'])
        self.assertFalse(self.reserve(4)[0])
        with self.assertRaisesRegex(RuntimeError, 'verified cleanup'):
            self.budget.release('baseline', 1, cleanup_verified=False)

    def test_unfulfilled_request_is_not_proof_of_a_launched_instance(self):
        self.reserve(1)
        self.snapshot['requests'] = [
            {'instance_id': None, 'vcpus': 2, 'campaign_id': 'baseline', 'host_epoch': 1}]
        self.assertTrue(self.reserve(2)[0])
        self.snapshot['requests'] = []
        reservations = json.loads(self.budget.state_path.read_text())['reservations']
        self.assertEqual(fleet_budget.capacity(self.snapshot, reservations, self.policy)['pending_launches'], 2)

    def test_unfulfilled_request_consumes_pending_launch_slot(self):
        self.policy['max_pending_launches'] = 1
        self.path.write_text(json.dumps(self.policy))
        self.budget = fleet_budget.FleetBudget(self.path)
        self.assertTrue(self.reserve(1)[0])
        self.snapshot['requests'] = [
            {'instance_id': None, 'vcpus': 2, 'campaign_id': 'baseline', 'host_epoch': 1}]

        self.assertFalse(self.reserve(2)[0])

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
                fleet_budget.capacity(self.snapshot, reservations, self.policy)

    def test_request_for_a_visible_instance_is_deduplicated_before_reservation_check(self):
        self.reserve(1)
        self.snapshot['instances'] = [
            {'id': 'i-first', 'vcpus': 2, 'campaign_id': 'baseline', 'host_epoch': 1}]
        self.snapshot['requests'] = [
            {'instance_id': 'i-first', 'vcpus': 2, 'campaign_id': 'baseline', 'host_epoch': 1}]
        reservations = json.loads(self.budget.state_path.read_text())['reservations']
        self.assertEqual(fleet_budget.capacity(self.snapshot, reservations, self.policy),
                         {'available': 734, 'reserved': 2, 'external': 0, 'pending_launches': 0})

        self.snapshot['requests'].append(
            {'instance_id': None, 'vcpus': 2, 'campaign_id': 'baseline', 'host_epoch': 1})
        with self.assertRaisesRegex(fleet_budget.BudgetError, 'multiple Spot resources'):
            fleet_budget.capacity(self.snapshot, reservations, self.policy)

    def test_fulfilled_request_proof_preserves_reservation_and_read_only_capacity(self):
        self.reserve(1)
        reservations = json.loads(self.budget.state_path.read_text())["reservations"]
        self.snapshot["requests"] = [
            {"instance_id": "i-first", "vcpus": 2, "campaign_id": "baseline", "host_epoch": 1}]
        before = copy.deepcopy(reservations)
        fleet_budget.capacity(self.snapshot, reservations, self.policy)
        self.assertEqual(reservations, before)
        fleet_budget.capacity(self.snapshot, reservations, self.policy, remember=True)
        self.snapshot["requests"] = []
        self.assertEqual(fleet_budget.capacity(self.snapshot, reservations, self.policy),
                         {"available": 734, "reserved": 2, "external": 0, "pending_launches": 0})
        self.snapshot["instances"] = [
            {"id": "i-first", "vcpus": 8, "campaign_id": "baseline", "host_epoch": 1}]
        with self.assertRaisesRegex(RuntimeError, "reserved topology"):
            fleet_budget.capacity(self.snapshot, reservations, self.policy, remember=True)

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
        for field, value in (("max_machine_hours", 3), ("max_usd", 15.2)):
            with self.subTest(field=field):
                self.budget.state_path.unlink(missing_ok=True)
                policy = copy.deepcopy(self.policy)
                policy[field] = value
                self.path.write_text(json.dumps(policy))
                self.budget = fleet_budget.FleetBudget(self.path)
                self.assertTrue(self.reserve(1)[0])
                self.assertEqual(self.reserve(2), (False, "fleet spending or machine-hour ceiling"))

    def test_policy_and_used_host_epochs_are_immutable_on_resume(self):
        self.reserve(1)
        with self.assertRaisesRegex(RuntimeError, "already used"):
            self.reserve(1)
        self.budget.release("baseline", 1, cleanup_verified=True, now=1001)
        with self.assertRaisesRegex(RuntimeError, "already used"):
            self.reserve(1)
        self.policy["max_hosts"] = 300
        self.path.write_text(json.dumps(self.policy))
        restarted = fleet_budget.FleetBudget(self.path)
        with self.assertRaisesRegex(RuntimeError, "changed on restart"):
            restarted.reserve("language", self.job(2), self.root, lambda: self.snapshot, 1002)

    def test_forbids_on_demand(self):
        job = self.job(1)
        job.market = "on-demand"
        with self.assertRaisesRegex(RuntimeError, "On-Demand"):
            self.budget.reserve("baseline", job, self.root, lambda: self.snapshot)
        self.policy["market"] = "on-demand"
        with self.assertRaises(fleet_budget.BudgetError):
            fleet_budget.validate_policy(self.policy)

    def test_configured_account_region_and_limits_allow_another_campaign(self):
        self.policy.update(account="234567890123", region="us-east-2",
                           max_vcpus=1024, max_hosts=512, max_pending_launches=40,
                           max_machine_hours=2000, max_usd=200)
        self.path.write_text(json.dumps(self.policy))
        self.budget = fleet_budget.FleetBudget(self.path)
        self.assertTrue(self.reserve(1)[0])

    def test_reserved_headroom_is_unavailable_to_both_controllers(self):
        self.policy["spot_vcpu_reserve"] = 4
        self.path.write_text(json.dumps(self.policy))
        self.budget = fleet_budget.FleetBudget(self.path)
        self.snapshot["quota"] = 8
        self.snapshot["instances"] = [{"id": "i-other", "vcpus": 2}]
        self.assertTrue(self.reserve(1)[0])
        self.assertFalse(self.reserve(2, campaign="language")[0])

    def test_invalid_policy_values_are_rejected(self):
        invalid = {"account": [None, "", "123", 123456789012],
                   "region": [None, "", "bad region"],
                   "spot_vcpu_reserve": [-1, True, 1.5],
                   "max_vcpus": [0, -1, True, 1.5],
                   "max_hosts": [0, True, 1.5],
                   "max_pending_launches": [0, True, 1.5],
                   "max_attempt_seconds": [0, True, 1.5],
                   "max_machine_hours": [0, -1, True, float("inf"), float("nan")],
                   "max_usd": [0, -1, True, float("inf"), float("nan")]}
        for name, values in invalid.items():
            for value in values:
                with self.subTest(name=name, value=value):
                    policy = {**self.policy, name: value}
                    with self.assertRaises(fleet_budget.BudgetError):
                        fleet_budget.validate_policy(policy)

    def test_fatal_failure_stops_other_controllers_and_bounds_attempt_lifetimes(self):
        self.reserve(1)
        self.budget.check(now=1001)
        with self.assertRaisesRegex(RuntimeError, "lifetime allowance"):
            self.budget.check(now=8201)
        self.budget.halt("semantic disagreement")
        other = fleet_budget.FleetBudget(self.path)
        with self.assertRaisesRegex(RuntimeError, "semantic disagreement"):
            other.reserve("language", self.job(2), self.root, lambda: self.snapshot, 1002)
        # The stop marker must not prevent verified cleanup from returning capacity.
        other.release("baseline", 1, cleanup_verified=True, now=1003)
        self.assertEqual(other.reservations(self.root / "baseline"), [])

    def test_failure_after_resume_stops_admission_and_preserves_first_reason(self):
        self.reserve(1)
        with self.budget.locked() as state:
            state["halted"] = None
        self.budget.halt("semantic disagreement after resume")
        other = fleet_budget.FleetBudget(self.path)
        other.halt("secondary controller failure")
        with self.assertRaisesRegex(RuntimeError, "semantic disagreement after resume"):
            other.check(now=1001)
        with self.assertRaisesRegex(RuntimeError, "semantic disagreement after resume"):
            other.reserve("language", self.job(2), self.root, lambda: self.snapshot, 1002)
        other.release("baseline", 1, cleanup_verified=True, now=1003)
        self.assertEqual(other.reservations(self.root / "baseline"), [])


if __name__ == "__main__":
    unittest.main()
