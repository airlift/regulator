from pathlib import Path
import sys
import unittest


sys.path.insert(0, str(Path(__file__).parent))
from estimates import estimate_comparisons
from uncertainty import uncertainty


class TestEstimates(unittest.TestCase):
    def row(self, state='compared'):
        # Three hosts on which the candidate's processes average 50, 60 and 70 ns against the comparator's 100 ns.
        hosts = [{'instanceId': f'i-{index}',
                  'candidate': {'state': 'compared', 'medianNs': cost, 'meanNs': cost, 'epochMeansNs': [cost - 5, cost + 5]},
                  'comparator': {'state': 'compared', 'medianNs': 90.0, 'meanNs': 100.0, 'epochMeansNs': [100.0] * 3}}
                 for index, cost in enumerate((50.0, 60.0, 70.0))]
        # Warnings reduced from the host medians, beside the precision and acceptance warnings.
        warnings = ['Hosts disagree about which engine is faster', 'Regulator sample precision exceeds 5%',
                    'At least one original host exceeded the sample precision gate']
        return {'id': 'case/execute', 'language': 'java', 'platform': 'r9g', 'memoryMode': 'native',
                'inputBytes': 8, 'result': {'state': state, 'estimator': 'median', 'hosts': hosts, 'warnings': warnings}}

    def test_comparisons_are_mean_costs_with_process_bootstrap_intervals(self):
        compared, excluded = self.row(), self.row('not-compatible')
        estimate_comparisons({'rows': [compared, excluded]})
        result = compared['result']
        self.assertEqual(
            {'estimator': 'mean', 'candidateNs': 60.0, 'comparatorNs': 100.0, 'ratio': 0.6, 'minimumRatio': 0.5,
             'maximumRatio': 0.7, 'deltaNs': -40.0, 'deltaNsPerByte': -5.0},
            {field: result[field] for field in (
                'estimator', 'candidateNs', 'comparatorNs', 'ratio', 'minimumRatio', 'maximumRatio', 'deltaNs',
                'deltaNsPerByte')})
        # Whether the hosts disagree is judged from the mean ratios, 0.5 to 0.7; precision and acceptance stay.
        self.assertEqual(['Host ratio variation exceeds 5%', 'Regulator sample precision exceeds 5%',
                          'At least one original host exceeded the sample precision gate'], result['warnings'])
        # Each host's processes are resampled whole; none is invented from a host's median.
        self.assertEqual(uncertainty([{'instanceId': host['instanceId'],
                                       **{side: {'processMeansNs': host[side]['epochMeansNs']}
                                          for side in ('candidate', 'comparator')}} for host in result['hosts']],
                                     'mean', 'case/execute|java|r9g|native'), result['uncertainty'])
        self.assertEqual(self.row('not-compatible'), excluded)

    def test_host_without_process_means_is_refused(self):
        row = self.row()
        del row['result']['hosts'][1]['comparator']['meanNs']
        del row['result']['hosts'][1]['comparator']['epochMeansNs']
        with self.assertRaisesRegex(ValueError, 'comparator host i-1 lacks meanNs and epochMeansNs'):
            estimate_comparisons({'rows': [row]})


if __name__ == '__main__':
    unittest.main()
