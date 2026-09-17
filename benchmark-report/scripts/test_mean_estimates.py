from copy import deepcopy
import unittest
from language_data import validate_estimate, validate_hardware

class TestMeanEstimates(unittest.TestCase):
    def fixture(self):
        return {'estimator':'mean','candidateNs':20,'comparatorNs':40,'ratio':.5,'deltaNs':-20,
                'hosts':[{'candidate':{'meanNs':x},'comparator':{'meanNs':2*x}} for x in (10,20,30)],
                'uncertainty':{'method':'hierarchical-bootstrap-v1','level':.95,'replicates':4000,'hostCount':3,
                  'ratioInterval':[.5,.5],'candidateIntervalNs':[10,30],'comparatorIntervalNs':[20,60],
                  'forkMeanRangeNs':{'candidate':[10,30],'comparator':[20,60]},
                  'processCounts':{'candidate':[5,5,5],'comparator':[1,1,1]}}}

    def test_mean_uses_equal_host_weights_and_ratio_of_costs(self):
        result=self.fixture();validate_estimate(result)
        for field,value in (('candidateNs',19),('ratio',.51),('deltaNs',-19)):
            with self.subTest(field=field):
                changed=deepcopy(result);changed[field]=value
                with self.assertRaises(ValueError):validate_estimate(changed)

    def test_missing_host_mean_is_not_replaced_by_median(self):
        result=self.fixture();result['hosts'][0]['candidate']={'medianNs':10}
        with self.assertRaisesRegex(ValueError,'measured host means'):validate_estimate(result)

    def test_rejects_invalid_intervals_and_wrong_population(self):
        result=self.fixture()
        for key,value in (('ratioInterval',[.6,.4]),('candidateIntervalNs',[0,10]),
                          ('comparatorIntervalNs',[10,float('nan')]),('hostCount',4),('level',.99),
                          ('processCounts',{'candidate':[5,5,5],'comparator':[1,1]})):
            with self.subTest(key=key):
                changed=deepcopy(result);changed['uncertainty'][key]=value
                with self.assertRaises(ValueError):validate_estimate(changed)

    def test_original_captures_without_declared_estimator_still_validate(self):
        validate_estimate({})
        with self.assertRaisesRegex(ValueError,'unknown timing estimator'):validate_estimate({'estimator':'best-fork'})



class TestHardwareAllocation(unittest.TestCase):
    def test_legacy_results_and_uniform_allocations(self):
        validate_hardware({})
        hardware = {'instanceType': 'r9g.2xlarge', 'allocatedVcpus': 8}
        validate_hardware({'platform': 'r9g', 'hardware': hardware,
                           'result': {'hosts': [dict(hardware) for _ in range(3)]}})

    def test_mixed_sizes_wrong_cpu_family_and_missing_provenance_fail(self):
        hardware = {'instanceType': 'r9g.2xlarge', 'allocatedVcpus': 8}
        for other in ({}, {'instanceType': 'r9g.large', 'allocatedVcpus': 2},
                      {'instanceType': 'r9g.2xlarge', 'allocatedVcpus': 2},
                      {'instanceType': 'r8g.2xlarge', 'allocatedVcpus': 8}):
            with self.subTest(other=other), self.assertRaises(ValueError):
                validate_hardware({'platform': 'r9g', 'hardware': hardware,
                                   'result': {'hosts': [hardware, hardware, other]}})

if __name__ == '__main__':
    unittest.main()
