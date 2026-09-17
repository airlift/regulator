import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import collection
import transport


class TestSlowBulkBudget(unittest.TestCase):
    def test_long_budget_preserves_measurements_and_operation_limits(self):
        ordinary = collection.STEADY_PROTOCOLS['language-bulk']
        slow = collection.SLOW_BULK_PROTOCOL
        self.assertEqual({k for k in slow if slow[k] != ordinary[k]},
                         {'measurement_profile', 'measurement_timeout_seconds'})
        per_fork = slow['startup_timeout_seconds'] + slow['compile_timeout_seconds']
        per_fork += (1 + slow['warmup_iterations'] + slow['measurement_iterations']) * slow['execution_timeout_seconds']
        self.assertGreaterEqual(slow['measurement_timeout_seconds'], slow['forks'] * per_fork)
        self.assertEqual(collection.protocol_for({'suite': 'language-bulk', 'protocol': slow}), slow)
        for suite in ('language-lifecycle', 'unknown'):
            with self.assertRaises(ValueError):
                collection.protocol_for({'suite': suite, 'protocol': slow})
        changed = {**slow, 'measurement_timeout_seconds': 18000}
        with self.assertRaises(ValueError):
            collection.protocol_for({'suite': 'language-bulk', 'protocol': changed})

    def test_runner_honors_the_selected_measurement_deadline(self):
        with tempfile.TemporaryDirectory() as temporary:
            prefix = Path(temporary) / 'measurement'
            with patch.object(collection.subprocess, 'Popen') as launch, \
                    patch.object(collection.time, 'monotonic', side_effect=[0, 3601, 12000]), \
                    patch.object(collection.time, 'sleep'), \
                    patch.object(collection, 'terminate_process_group') as terminate:
                launch.return_value.poll.side_effect = [None, None, 0, 0]
                launch.return_value.returncode = 0
                result = collection.run_process(['java', 'jmh'], prefix, measuring=True,
                                                measurement_timeout_seconds=12600)
            self.assertEqual(result['outcome'], 'completed')
            terminate.assert_not_called()
            with patch.object(collection.subprocess, 'Popen') as launch, \
                    patch.object(collection.time, 'monotonic', side_effect=[0, 12601]), \
                    patch.object(collection, 'terminate_process_group') as terminate:
                launch.return_value.poll.side_effect = [None, 0]
                result = collection.run_process(['java', 'jmh'], prefix, measuring=True,
                                                measurement_timeout_seconds=12600)
            self.assertEqual(result['outcome'], 'did-not-finish')
            self.assertEqual(result['limit_seconds'], 12600)
            terminate.assert_called_once_with(launch.return_value)

    def test_verification_cannot_inherit_a_long_measurement_budget(self):
        with self.assertRaises(ValueError):
            collection.run_process([], Path('unused'), measurement_timeout_seconds=12600)

    def test_transport_requires_matching_isolated_host_budget(self):
        package = {'packages': [{'directory': 'comparison'}]}
        ordinary = {'suite': 'language-bulk', 'protocol': collection.STEADY_PROTOCOLS['language-bulk']}
        with patch.object(collection, 'load', return_value=ordinary):
            transport.validate_host_budget(package, Path('inputs'), 5400)
            with self.assertRaises(ValueError):
                transport.validate_host_budget(package, Path('inputs'), 81000)
        for language, expected in (('java', 81000), ('trino', 81000), ('re2', 68400)):
            manifest = {
                'suite': 'language-bulk', 'protocol': collection.SLOW_BULK_PROTOCOL,
                'selected_languages': [language], 'cases': [{'id': 'fixture', 'model': 'count'}],
            }
            with self.subTest(language=language), patch.object(collection, 'load', return_value=manifest):
                transport.validate_host_budget(package, Path('inputs'), expected)
                with self.assertRaises(ValueError):
                    transport.validate_host_budget(package, Path('inputs'), 18000)
                with self.assertRaises(ValueError):
                    transport.validate_host_budget({'packages': package['packages'] * 2}, Path('inputs'), expected)
                for field in ('source_control', 'focused_controls', 'diagnostic_profiles'):
                    with self.subTest(field=field):
                        changed = {**manifest, field: {}}
                        with patch.object(collection, 'load', return_value=changed), self.assertRaises(ValueError):
                            transport.validate_host_budget(package, Path('inputs'), expected)


if __name__ == '__main__':
    unittest.main()
