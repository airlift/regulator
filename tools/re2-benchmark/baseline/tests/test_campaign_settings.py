import copy
import csv
from pathlib import Path
import tempfile
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
import campaign_settings
from campaign_settings import CampaignSettings, SettingsError
from test_fleet_budget import SETTINGS, seed_settings


class TestCampaignSettings(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def events(self):
        with (self.root / campaign_settings.EVENTS).open(newline="") as input_file:
            return [(row["version"], row["actor"], row["change"])
                    for row in csv.DictReader(input_file, delimiter="\t")]

    def test_each_change_takes_the_next_version_and_records_its_actor(self):
        settings = seed_settings(self.root)
        self.assertEqual((1, None, None, {"initial": 10, "max": 50, "size": 10}),
                         (settings.current["version"], settings.current["spending_stop"], settings.current["stop"],
                          settings.current["boot_window"]))
        self.assertEqual({"max_hosts": 368, "max_usd": 150, "regions": ["us-west-2"]}, settings.current["authorized"])
        # A restart seeds nothing: the campaign keeps the settings it runs under.
        CampaignSettings.seed(self.root, {**SETTINGS, "max_hosts": 1})
        other = CampaignSettings(self.root)
        other.change("monitor", "max_hosts 368 to 200", lambda current: current.update(max_hosts=200))
        # A change that changes nothing records nothing.
        other.change("monitor", "max_hosts 200 to 200", lambda current: current.update(max_hosts=200))
        self.assertEqual((2, 200), (settings.reload()["version"], settings.current["max_hosts"]))
        # A change the settings could not run under is refused whole, as is one naming no actor.
        with self.assertRaisesRegex(SettingsError, "max_usd"):
            other.change("dain", "max_usd to nothing", lambda current: current.update(max_usd=0, max_hosts=1))
        with self.assertRaisesRegex(SettingsError, "actor"):
            other.change("someone", "max_hosts 200 to 1", lambda current: current.update(max_hosts=1))
        self.assertEqual((2, 200), (settings.reload()["version"], settings.current["max_hosts"]))
        self.assertEqual([("1", "dain", "seed"), ("2", "monitor", "max_hosts 368 to 200")], self.events())

    def test_settings_the_controller_could_not_run_under_are_refused(self):
        on_demand = copy.deepcopy(SETTINGS)
        on_demand["regions"]["us-west-2"]["on_demand_max_vcpus"] = 8
        for rates in on_demand["hourly_rates"].values():
            rates["on_demand_total"] = 2 * rates["total"]
        campaign_settings.seeded(on_demand)
        region = SETTINGS["regions"]["us-west-2"]
        invalid = {"max_hosts": [0, True, 1.5, None],
                   "max_hosts_per_platform": [0, -1],
                   "max_machine_hours": [0, -1, True, float("inf"), float("nan")],
                   "max_usd": [0, -1, True, float("inf"), float("nan")],
                   "regions": [[], {}, {"bad region": region}, {"us-west-2": {**region, "max_vcpus": 0}},
                               {"us-west-2": {"max_vcpus": 8}}, {"us-west-2": {**region, "on_demand_max_vcpus": 0}},
                               {"us-west-2": {**region, "vcpus": 8}}],
                   "platform_regions": [{"r8i": []}, {"r8i": ["eu-west-1"]}, {"r8i": ["us-west-2", "us-west-2"]}],
                   "purchase": [{"r8i": "cheapest"}],
                   "hourly_rates": [{}, {"r8i.huge": {"spot": 0.1, "total": 0.2}},
                                    {"r8i.large": {"spot": 0.2, "total": 0.1}}],
                   "phases": [{"a/primary": {"state": "sleeping", "share": "auto"}},
                              {"a/primary": {"state": "running", "share": 0}}],
                   "boot_window": [{"initial": 4, "max": 50}, {"initial": 20, "max": 10}],
                   "spending_stop": [{"by": "someone", "at": 1, "reason": "leak"}, {"by": "dain", "at": 1}],
                   "authorized": [{"max_hosts": 1, "max_usd": 1}],
                   "launch_interval": [0]}
        for name, values in invalid.items():
            for value in values:
                with self.subTest(name=name, value=value), self.assertRaises(SettingsError):
                    campaign_settings.seeded({**SETTINGS, name: value})
        # Settings allowing On-Demand anywhere price every worker type's On-Demand hour.
        del on_demand["hourly_rates"]["r8i.large"]["on_demand_total"]
        with self.assertRaisesRegex(SettingsError, "r8i.large hourly price"):
            campaign_settings.seeded(on_demand)


if __name__ == "__main__":
    unittest.main()
