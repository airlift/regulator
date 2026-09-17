import copy
from pathlib import Path
import sys
import unittest


sys.path.insert(0, str(Path(__file__).parent))
from reproduce import compared_rows


class TestReproduce(unittest.TestCase):
    def test_rejects_duplicate_compared_rows(self):
        row = {
            'id': 'case/execute',
            'language': 'java',
            'platform': 'r9g',
            'memoryMode': 'native',
            'result': {'state': 'compared'},
        }
        report = {'rows': [row, copy.deepcopy(row)]}

        with self.assertRaisesRegex(ValueError, 'duplicate comparison rows'):
            compared_rows(report)

    def test_ignores_noncompared_rows_without_hiding_duplicates(self):
        row = {
            'id': 'case/execute',
            'language': 'java',
            'platform': 'r9g',
            'memoryMode': 'native',
            'result': {'state': 'compared', 'ratio': 1.0},
        }
        excluded = copy.deepcopy(row)
        excluded['result'] = {'state': 'not-compatible'}

        self.assertEqual(compared_rows({'rows': [row, excluded]}), {
            'case/execute|java|r9g|native': row['result'],
        })


if __name__ == '__main__':
    unittest.main()
