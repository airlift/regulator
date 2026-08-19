from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from language_data import language_rows
from public_api_report import normalized_capture
from test_public_api_report import capture_fixture


class TestScopedCapture(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='scoped-report-fixture-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.manifests, self.index = capture_fixture(self.root)

    def test_one_batch_cannot_claim_different_instances(self):
        first = self.index['exports'][0]
        other = next(entry for entry in self.index['exports']
                     if entry['language'] != first['language'] and entry['platform'] == first['platform']
                     and entry['replica'] == first['replica'])
        first['batch_identity'] = other['batch_identity'] = 'same-batch'
        with self.assertRaisesRegex(ValueError, 'batch spans different hosts'):
            normalized_capture(self.index, self.manifests)

    def test_only_selected_languages_are_required(self):
        self.index['exports'] = [entry for entry in self.index['exports'] if entry['language'] == 'trino']
        self.index.update(accepted_batches=9, planned_batches=9)
        selection = [('language-bulk', 'fixture', 'trino')]
        self.assertEqual(len(normalized_capture(self.index, self.manifests, selection)['hosts']), 9)
        for invalid in ([], selection * 2, [('language-bulk', 'missing', 'trino')]):
            with self.assertRaisesRegex(ValueError, 'selection'):
                normalized_capture(self.index, self.manifests, invalid)
        with self.assertRaisesRegex(ValueError, 'missing follow-up'):
            normalized_capture(self.index, self.manifests)

    def test_multiple_cases_can_share_only_their_declared_batch(self):
        other = {'id': 'second'}
        self.manifests[0]['cases'].append(other)
        additions = []
        for entry in self.index['exports']:
            entry['batch_identity'] = entry['instance_id']
            following = {**entry, 'case': 'second'}
            following['case_sha256'] = hashlib.sha256(json.dumps(other, sort_keys=True).encode()).hexdigest()
            path = Path(entry['export_path'])
            data = json.loads(path.read_text())
            data['manifest']['cases'] = [other]
            following['export_path'] = str(path.with_name('second-' + path.name))
            Path(following['export_path']).write_text(json.dumps(data))
            following['export_sha256'] = hashlib.sha256(Path(following['export_path']).read_bytes()).hexdigest()
            additions.append(following)
        self.index['exports'].extend(additions)
        self.assertEqual(len(normalized_capture(self.index, self.manifests)['hosts']), 54)
        self.index['exports'][-1]['batch_identity'] = 'different-batch'
        with self.assertRaisesRegex(ValueError, 'different batches'):
            normalized_capture(self.index, self.manifests)


class TestScopedRows(unittest.TestCase):
    def test_selection_does_not_fill_in_unmeasured_languages(self):
        case = {'id': 'fixture', 'population': 'bulk-text', 'family': 'fixture', 'model': 'count',
                'pattern_bytes': 1, 'mappings': {language: {'status': 'identical', 'reason': 'fixture', 'pattern': 'a'}
                                               for language in ('re2', 'java', 'trino')}}
        manifest = {'suite': 'language-bulk', 'cases': [case]}
        case_hash = hashlib.sha256(json.dumps(case, sort_keys=True, ensure_ascii=True, allow_nan=False).encode()).hexdigest()
        data = {'hosts': [], 'observations': [], 'comparisons': []}
        for platform in ('c9g', 'c8g', 'c8i'):
            for replica in (1, 2, 3):
                identity = ['language-bulk', 'fixture', 'trino', platform, replica]
                data['hosts'].append({'logical_identity': identity, 'instance_id': f'{platform}-{replica}',
                                      'collector': 'fixture', 'case_sha256': case_hash})
                for mode in ('native', 'safe'):
                    for system in ('candidate', 'comparator'):
                        data['observations'].append({'logical_identity': identity, 'id': mode + '/' + system,
                                                     'outcome': 'compared', 'samples_ns': [[10]]})
                    data['comparisons'].append({'logical_identity': identity, 'id': mode, 'logical_case': 'fixture',
                                                'language': 'trino', 'mode': mode, 'operation': 'execute',
                                                'candidate': mode + '/candidate', 'comparator': mode + '/comparator',
                                                'input_bytes_per_operation': 1, 'work_contract': 'bulk-matched-outputs-v1'})
        selection = [('language-bulk', 'fixture', 'trino')]
        rows = language_rows(data, [manifest], selection)
        self.assertEqual(len(rows), 6)
        self.assertEqual({row['language'] for row in rows}, {'trino'})
        with self.assertRaisesRegex(ValueError, 'coverage mismatch'):
            language_rows(data, [manifest])
        with self.assertRaisesRegex(ValueError, 'selection'):
            language_rows(data, [manifest], selection * 2)
        missing = deepcopy(data)
        missing['comparisons'].pop()
        with self.assertRaisesRegex(ValueError, 'incomplete host coverage'):
            language_rows(missing, [manifest], selection)


if __name__ == '__main__':
    unittest.main()
