import copy
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import measurement_followup


def report():
    return {
        'schemaVersion': 2,
        'sources': {'currentLabel': 'Fresh', 'currentCandidate': 'a' * 40,
                    'engineTree': 'b' * 40, 'jdk': 'JDK'},
        'languages': {}, 'platforms': {}, 'memoryModes': [],
        'methodology': ['Fresh methodology'],
        'provenance': {'releasedArtifact': {'version': '1.0', 'source_commit': 'a' * 40,
                                            'engine_tree': 'b' * 40, 'jar_sha256': 'c' * 64}},
        'rows': [{
            'id': 'row', 'caseId': 'case', 'operation': 'execute', 'language': 'java',
            'platform': 'r8g', 'memoryMode': 'native', 'population': 'ordinary',
            'family': 'fixture', 'inputBytes': 10,
            'result': {'state': 'compared', 'estimator': 'median'},
        }],
    }


class TestMeasurementFollowup(unittest.TestCase):
    def setUp(self):
        self.fresh = report()
        self.followup = copy.deepcopy(self.fresh)
        self.followup['sources']['currentLabel'] = 'Follow-up'
        self.followup['methodology'] = ['Follow-up methodology']
        hosts = [
            {'instanceId': f'host-{replica}',
             'candidate': {'state': 'compared', 'meanNs': 10.0},
             'comparator': {'state': 'compared', 'meanNs': 20.0}}
            for replica in (1, 2, 3)
        ]
        self.followup['rows'][0].update({
            'result': {'state': 'compared', 'estimator': 'mean', 'candidateNs': 10.0,
                       'comparatorNs': 20.0, 'ratio': .5, 'deltaNs': -10.0,
                       'deltaNsPerByte': -1.0, 'hosts': hosts, 'uncertainty': {}},
            'originalResult': {'state': 'compared', 'estimator': 'median'},
            'originalResultLabel': 'Original',
            'measurementSource': {'cohort': 'replacement'},
            'hardware': {'instanceType': 'r8g.large'},
        })

    def write_inputs(self, directory, entries=None):
        path = Path(directory) / 'inputs.jsonl.gz'
        entries = entries or [{
            'identity': 'row|java|r8g|native', 'estimator': 'mean',
            'hosts': [
                {'instanceId': f'host-{replica}',
                 'candidate': {'meanNs': 10.0, 'groups': [[10.0]]},
                 'comparator': {'meanNs': 20.0, 'groups': [[20.0]]}}
                for replica in (1, 2, 3)
            ],
        }]
        with path.open('wb') as destination, gzip.GzipFile(
                fileobj=destination, mode='wb', filename='', mtime=0) as output:
            for entry in entries:
                output.write((json.dumps(entry) + '\n').encode())
        self.followup['provenance']['measurementFollowup'] = {
            'schemaVersion': 1,
            'analysisInputSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        return path

    def test_applies_only_saved_analysis_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            result = measurement_followup.apply(
                self.fresh, self.followup, self.write_inputs(directory))
        self.assertEqual(self.followup['rows'], result['rows'])
        self.assertEqual(1, result['provenance']['measurementFollowup']['analysisInputRows'])
        self.assertEqual('complete-campaign-comparisons',
                         result['provenance']['measurementFollowup']['analysisInputScope'])

    def test_rejects_changed_structure_artifact_hash_and_analysis_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            inputs = self.write_inputs(directory)
            for mutation, message in (
                    (lambda value: value['rows'][0].update(family='changed'), 'campaign or workload'),
                    (lambda value: value['provenance']['releasedArtifact'].update(jar_sha256='d' * 64),
                     'released-artifact'),
                    (lambda value: value['rows'].append(copy.deepcopy(value['rows'][0])), 'campaign or workload')):
                changed = copy.deepcopy(self.followup)
                mutation(changed)
                with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                    measurement_followup.apply(self.fresh, changed, inputs)
            changed_inputs = Path(directory) / 'changed.jsonl.gz'
            changed_inputs.write_bytes(inputs.read_bytes() + b'changed')
            with self.assertRaisesRegex(ValueError, 'analysis inputs differ'):
                measurement_followup.apply(self.fresh, self.followup, changed_inputs)
            missing = self.write_inputs(directory, [{
                'identity': 'other|java|r8g|native', 'estimator': 'mean',
                'hosts': [{'instanceId': 'host', 'candidate': {'meanNs': 1},
                           'comparator': {'meanNs': 1}}],
            }])
            with self.assertRaisesRegex(ValueError, 'analysis coverage differs'):
                measurement_followup.apply(self.fresh, self.followup, missing)

    def test_rejects_missing_analysis_contract_and_changed_host_means(self):
        with tempfile.TemporaryDirectory() as directory:
            inputs = self.write_inputs(directory)
            for field in ('estimator', 'uncertainty'):
                changed = copy.deepcopy(self.followup)
                changed['rows'][0]['result'].pop(field)
                with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'mean estimate and uncertainty'):
                    measurement_followup.apply(self.fresh, changed, inputs)
            entries = measurement_followup.analysis_entries(inputs)
            entries['row|java|r8g|native']['hosts'][0]['candidate']['groups'] = [[11.0]]
            changed_inputs = self.write_inputs(directory, list(entries.values()))
            with self.assertRaisesRegex(ValueError, 'host mean differs'):
                measurement_followup.apply(self.fresh, self.followup, changed_inputs)

    def test_allows_current_verifier_hash_and_projects_public_inputs_deterministically(self):
        self.fresh['provenance']['baselineReducerSha256'] = 'new'
        self.followup['provenance']['baselineReducerSha256'] = 'archived'
        with tempfile.TemporaryDirectory() as directory:
            inputs = self.write_inputs(directory)
            measurement_followup.apply(self.fresh, self.followup, inputs)
            first = Path(directory) / 'first.gz'
            second = Path(directory) / 'second.gz'
            one = measurement_followup.project_analysis_inputs(
                inputs, {'row|java|r8g|native'}, first)
            two = measurement_followup.project_analysis_inputs(
                inputs, {'row|java|r8g|native'}, second)
            self.assertEqual(one, two)
            self.assertEqual(first.read_bytes(), second.read_bytes())


if __name__ == '__main__':
    unittest.main()
