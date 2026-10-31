import csv
import importlib.util
from pathlib import Path
import sys
import unittest


DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DIRECTORY))


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
        subset = [row for row in rows if row.shard_id == 'trino-like']
        self.assertEqual(
            {(row.system, row.allocation_contract) for row in subset},
            {('regulator', 'allocation-free'), ('trino-sql', 'recorded'), ('trino-optimized', 'recorded')})
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

if __name__ == '__main__':
    unittest.main()
