import copy
import unittest

import publication_data


def row(identifier, platform='r9g'):
    return {
        'id': identifier,
        'caseId': identifier,
        'name': identifier,
        'operation': 'execute',
        'language': 'java',
        'platform': platform,
        'memoryMode': 'native',
        'model': 'count',
        'population': 'ordinary-scalar',
        'family': 'fixture',
        'inputBytes': 10,
        'mapping': {'status': 'direct', 'reason': '', 'patternPreview': 'a', 'patternBytes': 1},
        'workload': {'pattern': 'a', 'description': 'Frozen public description'},
        'result': {'state': 'compared'},
    }


class TestPublicationData(unittest.TestCase):
    def setUp(self):
        self.workloads = {
            'sources': {'currentCandidate': 'workloads'},
            'rows': [row('public', 'c9g')],
        }
        measured = row('public')
        measured['workload']['performanceContext'] = 'Released implementation context'
        self.campaign = {
            'sources': {'currentLabel': 'Complete campaign'},
            'provenance': {'releasedArtifact': {'version': '1.0'}},
            'rows': [measured, row('internal')],
        }

    def test_publication_keeps_frozen_workloads_and_final_measurements(self):
        result = publication_data.publication_data(self.campaign, self.workloads, 'a' * 64)
        self.assertEqual(['public'], [entry['id'] for entry in result['rows']])
        self.assertEqual('Frozen public description', result['rows'][0]['workload']['description'])
        self.assertEqual('', result['rows'][0]['workload']['performanceContext'])
        self.assertEqual('Released implementation context', result['rows'][0]['releasePerformanceContext'])
        self.assertEqual('Regulator 1.0 results', result['sources']['currentLabel'])
        self.assertEqual('1.0', result['sources']['releaseVersion'])
        self.assertEqual(1, result['publicationScope']['excludedCampaignRows'])
        self.assertNotIn('reportVisible', result['rows'][0])

    def test_publication_rejects_missing_or_changed_workloads(self):
        with self.assertRaisesRegex(ValueError, 'missing publication workload'):
            publication_data.publication_data({**self.campaign, 'rows': []}, self.workloads, 'a' * 64)
        changed = copy.deepcopy(self.campaign)
        changed['rows'][0]['inputBytes'] = 11
        with self.assertRaisesRegex(ValueError, 'workload changed'):
            publication_data.publication_data(changed, self.workloads, 'a' * 64)
        for field in ('population', 'family'):
            changed = copy.deepcopy(self.campaign)
            changed['rows'][0][field] = 'changed'
            with self.subTest(field=field):
                result = publication_data.publication_data(changed, self.workloads, 'a' * 64)
                self.assertEqual(self.workloads['rows'][0][field], result['rows'][0][field])


if __name__ == '__main__':
    unittest.main()
