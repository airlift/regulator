import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import collection


class TestMeasurementBudget(unittest.TestCase):
    def test_a_slow_process_gets_twice_its_estimate_and_others_the_protocol_limit(self):
        budget = collection.unit_cost.measurement_budget
        # Five forks of twenty 17.1-second iterations fit in the protocol's hour twice over.
        self.assertEqual(3600, budget(collection.PROTOCOL, False, 17.1))
        self.assertEqual(3600, budget(collection.PROTOCOL, True))
        # The steady bulk protocol's eighty iterations do not: its launcher and forks start, then run.
        steady = collection.STEADY_PROTOCOLS["language-bulk"]
        self.assertEqual(math.ceil(2 * (6 * 2 + 5 * 80 * 17.1)), budget(steady, False, 17.1))
        self.assertEqual(3600, budget(steady, False, 17.1, allocation=True))
        # An iteration ends with the operation in progress, so a 0.3-second operation stretches it to 1.2 seconds.
        self.assertAlmostEqual(1.2, collection.unit_cost.iteration_seconds(steady, 0.3))

    def test_runner_honors_the_derived_measurement_deadline(self):
        budget = collection.unit_cost.measurement_budget(collection.STEADY_PROTOCOLS["language-bulk"], False, 17.1)
        with tempfile.TemporaryDirectory() as temporary:
            prefix = Path(temporary) / "measurement"
            with patch.object(collection.subprocess, "Popen") as launch, \
                    patch.object(collection.time, "monotonic", side_effect=[0, 3601, budget]), \
                    patch.object(collection.time, "sleep"), \
                    patch.object(collection, "terminate_process_group") as terminate:
                launch.return_value.poll.side_effect = [None, None, 0, 0]
                launch.return_value.returncode = 0
                result = collection.run_process(["java", "jmh"], prefix, measuring=True,
                                                measurement_timeout_seconds=budget)
            self.assertEqual(result["outcome"], "completed")
            terminate.assert_not_called()
            with patch.object(collection.subprocess, "Popen") as launch, \
                    patch.object(collection.time, "monotonic", side_effect=[0, budget + 1]), \
                    patch.object(collection, "terminate_process_group") as terminate:
                launch.return_value.poll.side_effect = [None, 0]
                result = collection.run_process(["java", "jmh"], prefix, measuring=True,
                                                measurement_timeout_seconds=budget)
            self.assertEqual((result["outcome"], result["phase"], result["limit_seconds"]),
                             ("did-not-finish", "measurement", budget))
            terminate.assert_called_once_with(launch.return_value)

    def test_only_a_measurement_takes_a_budget_in_whole_seconds(self):
        for options in ({"measurement_timeout_seconds": 3600}, {"measuring": True},
                        {"measuring": True, "measurement_timeout_seconds": 3600.5},
                        {"measuring": True, "measurement_timeout_seconds": 0}):
            with self.subTest(options=options), self.assertRaisesRegex(ValueError, "whole-process budget"):
                collection.run_process([], Path("unused"), **options)


if __name__ == "__main__":
    unittest.main()
