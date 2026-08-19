from copy import deepcopy
import unittest

from baseline_data import output_contract


class TestOutputContract(unittest.TestCase):
    def setUp(self):
        self.pairs = [dict(platform='c9g', shard_id='operations', replica_id=str(replica),
                           host_epoch=f'host-{replica}', candidate_system='regulator-native-access',
                           comparator_system='joni') for replica in range(1, 4)]
        self.metadata = []
        for pair in self.pairs:
            for route, systems in [('native', 'regulator-native-access'), ('safe', 'regulator,joni')]:
                for key, value in [('systems', systems), ('trino_output_contract', 'joni-slice-output-v1')]:
                    self.metadata.append({**pair, 'source': f'routes/{route}/run-metadata.txt',
                                          'key': key, 'value': value})

    def test_every_paired_route_must_record_the_new_contract(self):
        self.assertEqual(output_contract(self.pairs, self.metadata), {'workContract': 'joni-slice-output-v1'})

    def test_historical_evidence_is_never_restamped(self):
        self.assertEqual(output_contract(self.pairs, []), {})
        self.assertEqual(output_contract(self.pairs, [row for row in self.metadata if row['key'] == 'systems']), {})

    def test_mixed_missing_or_unknown_contracts_fail(self):
        for index, row in enumerate(self.metadata):
            if row['key'] != 'trino_output_contract':
                continue
            with self.subTest(index=index), self.assertRaisesRegex(ValueError, 'mixed or unknown'):
                output_contract(self.pairs, self.metadata[:index] + self.metadata[index + 1:])
        changed = deepcopy(self.metadata)
        next(row for row in changed if row['key'] == 'trino_output_contract')['value'] = 'future-contract'
        with self.assertRaisesRegex(ValueError, 'mixed or unknown'):
            output_contract(self.pairs, changed)

    def test_other_host_metadata_cannot_supply_missing_evidence(self):
        changed = deepcopy(self.metadata)
        for row in changed:
            row['host_epoch'] = 'another-host'
        self.assertEqual(output_contract(self.pairs, changed), {})

    def test_duplicate_metadata_fails(self):
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            output_contract(self.pairs, self.metadata + self.metadata[:1])
