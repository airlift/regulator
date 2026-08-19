import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from build_capture_data import like_supplement
from language_data import PLATFORMS

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/re2-benchmark/baseline'))
import acceptance


class TestLikeSupplement(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.receipts = []
        self.normalized = []
        self.paths = []
        for platform in PLATFORMS:
            for replica in (1, 2, 3):
                receipt = dict(platform=platform, shard_id='like-dfa-single-use', replica_id=str(replica),
                               instance_id=f'{platform}-{replica}', host_epoch=str(replica),
                               engine_tree='candidate-tree', architecture='arm' if platform != 'c8i' else 'intel')
                self.receipts.append(receipt)
                base = (self.directory / 'jobs' / platform / receipt['shard_id'] /
                        f'replica-{replica}' / f'epoch-{replica}' / 'session' / receipt['architecture'] / 're2-results')
                raw = base / 'routes/native-access/raw'
                raw.mkdir(parents=True)
                for system, method, score in (('regulator', 'candidate', 20), ('trino-sql', 'trinoSql', 40),
                                               ('trino-optimized', 'trinoOptimized', 80)):
                    entries = []
                    for scenario in ('ANY_ASCII', 'ANY_MULTIBYTE'):
                        entries.append(dict(params={'scenario': scenario}, benchmark='BenchmarkTrinoLike.' + method + 'SingleUse',
                                            warmupTime='1 s', measurementTime='1 s', warmupIterations=10,
                                            measurementIterations=10, primaryMetric={'scoreUnit': 'ns/op', 'rawData': [[score] * 10]},
                                            secondaryMetrics={'gc.alloc.rate.norm': {'scoreUnit': 'B/op', 'score': 16}}))
                        if platform == 'c9g' and replica == 1:
                            self.normalized.append({'row_id': f'like-dfa-single-use/{scenario}/{system}',
                                                    'outcome': 'accepted', 'score': str(score)})
                    for epoch in range(5):
                        path = raw / f'trino-like-{system}-process-{epoch}.json'
                        process_entries = json.loads(json.dumps(entries))
                        if epoch != 0:
                            for entry in process_entries:
                                entry['secondaryMetrics'] = {}
                        path.write_text(json.dumps(process_entries))
                        self.paths.append(path)
        self.addCleanup(patch.stopall)
        patch('build_capture_data.read_rows', side_effect=lambda path:
              self.receipts if path.name == 'accepted-sessions.tsv' else self.normalized).start()
        patch.object(acceptance, 'validate_host_results', side_effect=lambda manifest, session, observations:
                     next(row for row in self.receipts if
                          row['platform'] == session.parts[-8] and row['host_epoch'] == session.parts[-5].removeprefix('epoch-'))).start()

    def build(self):
        return like_supplement(self.directory, Path('manifest.tsv'), dfa=True, engine_tree='candidate-tree')

    def test_pairs_each_comparator_and_preserves_both_memory_views(self):
        rows = self.build()
        self.assertEqual(len(rows), 24)
        for row in rows:
            optimized = row['caseId'].endswith('/optimized')
            self.assertEqual(row['result']['ratio'], .25 if optimized else .5)
            self.assertEqual(row['result']['candidateNs'], 20)
            self.assertEqual(row['operation'], 'singleUse')
            self.assertEqual(len(row['result']['hosts']), 3)
            self.assertEqual(row['inputBytes'], 4 if 'MULTIBYTE' in row['caseId'] else 1)
        self.assertEqual({row['memoryMode'] for row in rows}, {'native', 'safe'})

    def test_rejects_incomplete_or_reused_hosts_and_wrong_engine(self):
        saved = self.receipts[-1]
        self.receipts.pop()
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            self.build()
        self.receipts.append(saved)
        saved['instance_id'] = self.receipts[0]['instance_id']
        with self.assertRaisesRegex(ValueError, 'reused a host'):
            self.build()
        saved['instance_id'] = 'restored'
        saved['engine_tree'] = 'wrong-tree'
        with self.assertRaisesRegex(ValueError, 'production engine'):
            self.build()

    def test_rejects_wrong_method_missing_allocation_and_wrong_scenario(self):
        path = self.paths[0]
        original = json.loads(path.read_text())
        for change, message in (
                (lambda entry: entry.update(benchmark='BenchmarkTrinoLike.candidateCompile'), 'method'),
                (lambda entry: entry.update(secondaryMetrics={}), 'allocation'),
                (lambda entry: entry['params'].update(scenario='EXACT_MATCH'), 'scenario')):
            entries = json.loads(json.dumps(original))
            change(entries[0])
            path.write_text(json.dumps(entries))
            with self.assertRaisesRegex(ValueError, message):
                self.build()

    def test_rejects_raw_normalized_disagreement(self):
        self.normalized[0]['score'] = '21'
        with self.assertRaisesRegex(ValueError, 'median disagrees'):
            self.build()


if __name__ == '__main__':
    unittest.main()
