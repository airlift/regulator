from copy import deepcopy
import unittest

from build_review_preview import compose, pair_cohort, report_workload


def observation(engine, mode, candidate_ns, instance='host-1'):
    return {
        'platform': 'c9g', 'instance': instance, 'case': 'example', 'language': 'java',
        'engine': engine, 'mode': mode, 'operation': 'execute', 'input_bytes': 10,
        'kind': 'public', 'identity': f'example/java/{engine}/{mode}/execute',
        'baseline_ns': 900, 'candidate_ns': candidate_ns,
        'legs': [{'source_commit': source, 'pattern_hex': '78', 'input_bytes': 10}
                 for source in ['candidate', 'control', 'candidate']],
    }


class TestReviewPreview(unittest.TestCase):
    def setUp(self):
        self.case = {
            'source_haystack_sha256': 'input', 'source_pattern_sha256': 'pattern',
            'model': 'count', 'expected_result': 1,
            'mappings': {'java': {'input_file_sha256': 'mapped-input', 'pattern_hex': '78'}},
        }
        self.row = {
            'id': 'language-bulk/example/execute', 'caseId': 'example', 'name': 'example',
            'language': 'java', 'platform': 'c9g', 'memoryMode': 'native', 'operation': 'execute',
            'source': 'language', 'inputBytes': 10, 'model': 'count', 'population': 'bulk-text',
            'family': 'text-processing', 'result': {'candidateNs': 900},
            'mapping': {'patternPreview': 'x'},
        }
        self.base = {'rows': [self.row], 'sources': {}, 'provenance': {}}
        self.data = {'records': [observation('java', 'native', 20), observation('jdk', 'safe', 5)], 'uncompared': []}

    def test_uses_same_host_comparator_not_previous_regulator(self):
        result = compose(self.base, [('initial', self.data, 2, 1)], {'example': self.case}, {'example': self.case})
        row = result['rows'][0]
        self.assertEqual(row['result']['ratio'], 4)
        self.assertEqual(row['result']['deltaNs'], 15)
        self.assertEqual(row['result']['deltaNsPerByte'], 1.5)
        self.assertEqual(row['workload']['pattern'], 'x')
        self.assertEqual(row['previousMeasurements'][0]['result'], self.row['result'])
        self.assertEqual(self.row['result'], {'candidateNs': 900})

    def test_repeats_replace_even_when_slower_without_pooling(self):
        repeat = deepcopy(self.data)
        repeat['records'][0]['candidate_ns'] = 40
        result = compose(self.base, [('initial', self.data, 2, 1), ('repeat', repeat, 2, 1)], {'example': self.case}, {'example': self.case})
        self.assertEqual(result['rows'][0]['result']['candidateNs'], 40)
        self.assertEqual(len(result['rows'][0]['previousMeasurements']), 2)

    def test_rejects_cross_host_and_missing_or_duplicate_results(self):
        broken = deepcopy(self.data)
        broken['records'][1]['instance'] = 'other-host'
        with self.assertRaisesRegex(ValueError, 'same-host'):
            pair_cohort(broken, 2, 1)
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            pair_cohort(self.data, 3, 1)
        broken = {'records': self.data['records'] * 2, 'uncompared': []}
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            pair_cohort(broken, 4, 1)

    def test_rejects_changed_workload(self):
        changed = {**self.case, 'source_haystack_sha256': 'other-input'}
        with self.assertRaisesRegex(ValueError, 'Workload mismatch'):
            compose(self.base, [('initial', self.data, 2, 1)], {'example': self.case}, {'example': changed})

    def test_does_not_extrapolate_to_unmeasured_rows(self):
        untouched = {**self.row, 'caseId': 'other', 'id': 'other'}
        self.base['rows'].append(untouched)
        result = compose(self.base, [('initial', self.data, 2, 1)], {'example': self.case}, {'example': self.case})
        self.assertEqual(result['rows'][1]['result'], untouched['result'])

    def test_separates_diagnostics_from_adversarial(self):
        diagnostic = {**self.row, 'population': 'diagnostics-and-stress', 'family': 'optimizer-probe'}
        self.assertFalse(report_workload(diagnostic))
        self.assertTrue(report_workload({**diagnostic, 'family': 'adversarial'}))
        self.assertTrue(report_workload({**diagnostic, 'family': 'reported-regression'}))
        self.assertTrue(report_workload({**self.row, 'population': 'compilation'}))

    def test_retains_only_dfa_eligible_like_variants(self):
        for scenario in ['ANY_ASCII', 'ANY_MULTIBYTE', 'EXACT_MATCH', 'MIXED_LATE']:
            row = {**self.row, 'language': 'like', 'population': 'diagnostics-and-stress',
                   'family': 'Optional Trino DFA matcher', 'caseId': f'trino-like/{scenario}/optimized'}
            self.assertEqual(report_workload(row), scenario in ['ANY_ASCII', 'ANY_MULTIBYTE'])


if __name__ == '__main__':
    unittest.main()
