import csv
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DIRECTORY))
import host_duration


class TestLikeLifecycle(unittest.TestCase):
    def test_generated_rows_preserve_reused_cases_and_add_both_lifecycle_operations(self):
        spec = importlib.util.spec_from_file_location('like_manifest', DIRECTORY / 'generate-manifest.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        rows = []
        module.add_trino_like_rows(rows)
        self.assertEqual(len(rows), 90)
        with (DIRECTORY / 'rows.tsv').open() as source:
            recorded = list(csv.DictReader(source, delimiter='\t'))
        selected = [row for row in recorded if row['suite'] == 'trino-like']
        self.assertEqual({row.row_id for row in rows}, {row['row_id'] for row in selected})
        for shard in ('like-compile', 'like-single-use'):
            subset = [row for row in rows if row.shard_id == shard]
            self.assertEqual(len(subset), 24)
            self.assertEqual({row.system for row in subset}, {'regulator', 'trino-sql'})
            self.assertEqual({row.allocation_contract for row in subset}, {'recorded'})

        subset = [row for row in rows if row.shard_id == 'like-dfa-single-use']
        self.assertEqual(len(subset), 6)
        self.assertEqual({row.system for row in subset}, {'regulator', 'trino-sql', 'trino-optimized'})
        self.assertEqual({row.parameters for row in subset}, {'scenario=ANY_ASCII', 'scenario=ANY_MULTIBYTE'})
        self.assertEqual({row.benchmark for row in subset}, {'singleUse'})

    def test_full_protocol_budget_counts_both_implementations(self):
        with tempfile.TemporaryDirectory() as temporary:
            for shard in ('like-compile', 'like-single-use'):
                destination = Path(temporary) / shard
                destination.mkdir()
                subprocess.run(
                    [str(DIRECTORY / 'run-shard.sh'), shard, 'qualification', 'native-access', str(destination)],
                    env={**os.environ, 'BASELINE_PLAN_ONLY': 'true', 'BASELINE_DEFER_ACCEPTANCE': 'true',
                         'RE2_CAMPAIGN_REPLICA_ID': '1'}, capture_output=True, check=True)
                metadata = destination / 'run-metadata.txt'
                properties = host_duration.read_properties(metadata)
                self.assertEqual(properties['jmh_iteration_time'], '1s')
                self.assertEqual(properties['jmh_process_count'], '5')
                self.assertEqual(properties['candidate_jmh_row_count'], '24')
                self.assertEqual(properties['jmh_invocation_count'], '2')
                self.assertGreater(float(properties['route_static_duration_estimate_seconds']), 2400)
                self.assertLessEqual(host_duration.estimate([metadata], 600)[-1], 5400)

    def test_worker_verifies_lifecycle_only_after_like_classes_are_built(self):
        source = (DIRECTORY / 'run-shard.sh').read_text()
        final_line = source.split('prepare_final_line()\n', 1)[1].split('prepare_trino_like()\n', 1)[0]
        like = source.split('prepare_trino_like()\n', 1)[1].split('prepare_rebar()\n', 1)[0]
        self.assertNotIn('Verified 60 Trino LIKE lifecycle', final_line)
        self.assertIn('Verified 60 Trino LIKE lifecycle comparisons', like)
        self.assertIn('--method-map "${method}=${operation}"', source)

    def test_dfa_single_use_is_bounded_to_six_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)
            subprocess.run(
                [str(DIRECTORY / 'run-shard.sh'), 'like-dfa-single-use', 'qualification', 'native-access', str(destination)],
                env={**os.environ, 'BASELINE_PLAN_ONLY': 'true', 'BASELINE_DEFER_ACCEPTANCE': 'true',
                     'RE2_CAMPAIGN_REPLICA_ID': '1'}, capture_output=True, check=True)
            properties = host_duration.read_properties(destination / 'run-metadata.txt')
            self.assertEqual(properties['candidate_jmh_row_count'], '6')
            self.assertEqual(properties['jmh_invocation_count'], '3')
            self.assertEqual(properties['jmh_process_count'], '5')
            self.assertLess(float(properties['route_static_duration_estimate_seconds']), 1800)
        source = (DIRECTORY / 'run-shard.sh').read_text()
        self.assertIn('scenario_arguments=(-p scenario=ANY_ASCII,ANY_MULTIBYTE)', source)
        self.assertIn('run_trino_like_system trino-optimized trinoOptimizedSingleUse singleUse', source)


if __name__ == '__main__':
    unittest.main()
