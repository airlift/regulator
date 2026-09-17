import copy
import unittest
from uncertainty import uncertainty

class TestUncertainty(unittest.TestCase):
    def hosts(self):
        return [{'instanceId':str(i),'candidate':{'groups':[[a]*20 for a in (80,100,120,100,100)]},
                 'comparator':{'groups':[[100]*20 for _ in range(5)]}} for i in range(3)]
    def test_more_correlated_iterations_do_not_create_precision(self):
        hosts=self.hosts();a=uncertainty(hosts,'mean','same')
        for h in hosts:
            for side in ('candidate','comparator'):h[side]['groups']=[g*10 for g in h[side]['groups']]
        b=uncertainty(hosts,'mean','same')
        self.assertEqual(a['ratioInterval'],b['ratioInterval'])
        self.assertLess(a['ratioInterval'][0],1);self.assertGreater(a['ratioInterval'][1],1)
    def test_joint_host_resampling_preserves_shared_machine_effect(self):
        hosts=self.hosts()
        for i,h in enumerate(hosts):
            h['candidate']['groups']=[[100*(i+1)]*20]*5
            h['comparator']['groups']=[[200*(i+1)]*20]*5
        for estimator in ('mean','median'):
            result=uncertainty(hosts,estimator,'paired')
            self.assertEqual(result['ratioInterval'],[.5,.5])
            self.assertLess(result['candidateIntervalNs'][0],result['candidateIntervalNs'][1])
    def test_missing_raw_is_explicit_and_not_a_fabricated_process(self):
        hosts=self.hosts()
        for h in hosts:h['comparator']={'medianNs':100,'meanNs':100}
        result=uncertainty(hosts,'median','partial')
        self.assertEqual(result['processCounts']['comparator'],[None]*3)
        self.assertEqual(len(result['conditionalHostAggregates']),3)
    def test_independent_hosts_and_valid_raw_required(self):
        hosts=self.hosts();hosts[1]['instanceId']=hosts[0]['instanceId']
        with self.assertRaises(ValueError):uncertainty(hosts,'mean','invalid')
        hosts=self.hosts();hosts[0]['candidate']['groups'][0][0]=float('nan')
        with self.assertRaises(ValueError):uncertainty(hosts,'mean','invalid')

if __name__=='__main__':unittest.main()
