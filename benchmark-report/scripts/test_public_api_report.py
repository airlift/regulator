from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from test_language_data import report_fixture
from public_api_report import identity, normalized_capture, prior_label, replace_rows, verify_jdk, verify_selection


class TestPublicApiReport(unittest.TestCase):
    def test_jdk_label_requires_the_same_vendor_version_and_build(self):
        captured = 'openjdk version "25.0.4"\nOpenJDK Runtime Environment Temurin-25.0.4+7 (build 25.0.4+7-LTS)\n'
        verify_jdk('Temurin 25.0.4+7', captured)
        for different in (captured.replace('Temurin', 'Other'), captured.replace('+7-LTS', '+8-LTS'), ''):
            with self.assertRaisesRegex(ValueError, 'JDK'):
                verify_jdk('Temurin 25.0.4+7', different)

    def setUp(self):
        self.original = report_fixture()
        self.replacements = deepcopy([row for row in self.original['rows']
                                      if row['source'] == 'language'])
        self.expected = {identity(row) for row in self.replacements}
        self.source = {'originalReportSha256': 'fixture-report-hash',
                       'candidate': {'candidate_commit': 'fixture-new-candidate', 'engine_tree': 'fixture-new-tree'}}

    def apply(self):
        return replace_rows(self.original, self.replacements, self.expected, self.source)

    def test_predecessors_and_sources_are_preserved_without_mutating_input(self):
        before = deepcopy(self.original)
        report = self.apply()
        self.assertEqual(self.original, before)
        for row in report['rows']:
            if row['source'] == 'language':
                self.assertEqual(row['originalResult'], row['result'])
                self.assertEqual(row['measurementSource'], self.source['candidate'])
            else:
                self.assertNotIn('originalResult', row)
                self.assertNotIn('measurementSource', row)

    def test_changed_paths_are_not_labeled_as_shorter_measurement_intervals(self):
        count = {'language': 're2', 'operation': 'reusedCount', 'model': 'reusedCount'}
        self.assertIn('matcher-iteration', prior_label(count))
        self.assertIn('match-boundary', prior_label({**count, 'operation': 'execute', 'model': 'grep'}))
        self.assertEqual(prior_label({**count, 'language': 'trino'}), 'Previous measurement cohort')

    def test_replacement_preserves_both_work_contracts(self):
        old = next(row for row in self.original['rows'] if row['source'] == 'language')
        old['workContract'] = 'public-pattern-lifecycle-v1'
        new = next(row for row in self.replacements if identity(row) == identity(old))
        new['workContract'] = 'public-pattern-lifecycle-v1'
        updated = next(row for row in self.apply()['rows'] if identity(row) == identity(old))
        self.assertEqual(updated['originalWorkContract'], old['workContract'])
        self.assertEqual(updated['workContract'], new['workContract'])
        del new['workContract']
        updated = next(row for row in self.apply()['rows'] if identity(row) == identity(old))
        self.assertEqual(updated['originalWorkContract'], old['workContract'])
        self.assertNotIn('workContract', updated)

    def test_missing_duplicate_and_unexpected_rows_fail(self):
        for replacement in (self.replacements[:-1], self.replacements + self.replacements[:1],
                            self.replacements + [{**self.replacements[0], 'id': 'unexpected'}]):
            with self.subTest(rows=len(replacement)), self.assertRaisesRegex(ValueError, 'rows'):
                replace_rows(self.original, replacement, self.expected, self.source)

    def test_changed_workload_contract_fails(self):
        self.replacements[0]['inputBytes'] = 500
        with self.assertRaisesRegex(ValueError, 'workload contract'):
            self.apply()

    def test_missing_host_fails(self):
        self.replacements[0]['result']['hosts'].pop()
        with self.assertRaisesRegex(ValueError, 'three independent hosts'):
            self.apply()

    def test_second_followup_cannot_overwrite_prior_evidence(self):
        with self.assertRaisesRegex(ValueError, 'already applied'):
            replace_rows(self.apply(), self.replacements, self.expected, self.source)


def capture_fixture(root):
    ledger = root / 'accepted-language-sessions.json'
    ledger.write_text('fixture only')
    case = {'id': 'fixture'}
    manifests = [{'suite': 'language-bulk', 'cases': [case], 'protocol': {'fixture': True}}]
    index = {'scope': 'accepted-primary-raw-evidence', 'missing_batches': [],
             'accepted_batches': 27, 'planned_batches': 27, 'results': str(root),
             'ledger_sha256': hashlib.sha256(ledger.read_bytes()).hexdigest(),
             'candidate': {'candidate_commit': 'fixture-candidate', 'root_tree': 'fixture-tree',
                           'comparator_manifest_sha256': 'fixture-comparators'}, 'exports': []}
    for language in ('re2', 'java', 'trino'):
        for cpu in ('c9g', 'c8g', 'c8i'):
            for replica in (1, 2, 3):
                instance = f'{language}-{cpu}-{replica}'
                path = root / (instance + '.json')
                export = {'provenance': {'source_commit': 'fixture-candidate', 'source_tree': 'fixture-tree',
                                        'comparators_sha256': 'fixture-comparators', 'jdk': 'fixture-JDK',
                                        'instance_identity': {'instanceId': instance}},
                          'manifest': {**manifests[0], 'selected_languages': [language]},
                          'verification': {'receipts': {'fixture': {'outcome': 'completed', 'trace': 'same'}}},
                          'observations': {}, 'comparisons': []}
                path.write_text(json.dumps(export))
                index['exports'].append({'suite': 'language-bulk', 'case': 'fixture', 'language': language,
                                         'platform': cpu, 'replica': replica, 'instance_id': instance,
                                         'case_sha256': hashlib.sha256(json.dumps(case, sort_keys=True).encode()).hexdigest(),
                                         'export_path': str(path), 'export_sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    return manifests, index


class TestNormalizedCapture(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='public-api-report-fixture-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.manifests, self.index = capture_fixture(self.root)

    def test_complete_matrix_is_normalized(self):
        data = normalized_capture(self.index, self.manifests)
        self.assertEqual(len(data['hosts']), 27)
        self.assertEqual(len({tuple(row['logical_identity']) for row in data['hosts']}), 27)

    def test_missing_and_duplicate_exports_fail(self):
        for entries in (self.index['exports'][:-1], self.index['exports'] + self.index['exports'][:1]):
            with self.subTest(count=len(entries)), self.assertRaisesRegex(ValueError, 'export'):
                normalized_capture({**self.index, 'exports': entries}, self.manifests)

    def test_export_tampering_fails(self):
        Path(self.index['exports'][0]['export_path']).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'export changed'):
            normalized_capture(self.index, self.manifests)

    def test_changed_ledger_fails(self):
        (self.root / 'accepted-language-sessions.json').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'ledger changed'):
            normalized_capture(self.index, self.manifests)

    def test_mixed_jdk_and_changed_traces_fail(self):
        for field, value, error in (('jdk', 'other-JDK', 'JDK'), ('trace', 'different', 'traces')):
            entry = self.index['exports'][-1]
            path = Path(entry['export_path'])
            original = path.read_text()
            data = json.loads(original)
            if field == 'jdk':
                data['provenance']['jdk'] = value
            else:
                data['verification']['receipts']['fixture']['trace'] = value
            path.write_text(json.dumps(data))
            entry['export_sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, error):
                normalized_capture(self.index, self.manifests)
            path.write_text(original)
            entry['export_sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()


class TestSelection(unittest.TestCase):
    def test_original_input_contract_and_measurement_protocol_are_preserved(self):
        with tempfile.TemporaryDirectory(prefix='selection-fixture-') as temporary:
            root = Path(temporary)
            original = {'suite': 'language-bulk', 'cases': [{'id': 'a', 'bytes': 'original'}, {'id': 'b'}],
                        'protocol': {'forks': 5, 'measurement_timeout_seconds': 300}}
            before, after = root / 'original.json', root / 'selected.json'
            before.write_text(json.dumps(original))
            selected = {**original, 'cases': original['cases'][:1],
                        'followup_source_manifest_sha256': hashlib.sha256(before.read_bytes()).hexdigest(),
                        'protocol': {'forks': 5, 'measurement_timeout_seconds': 3600}}
            after.write_text(json.dumps(selected))
            self.assertEqual(verify_selection(before, after), selected)
            for mutation, message in (
                (lambda data: data['cases'][0].update(bytes='changed'), 'workload'),
                (lambda data: data['protocol'].update(forks=1), 'protocol'),
                (lambda data: data.update(followup_source_manifest_sha256='wrong'), 'manifest'),
            ):
                changed = deepcopy(selected)
                mutation(changed)
                after.write_text(json.dumps(changed))
                with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                    verify_selection(before, after)


if __name__ == '__main__':
    unittest.main()
