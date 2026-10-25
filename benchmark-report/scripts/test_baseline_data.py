from collections import Counter
from copy import deepcopy
import csv
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import baseline_data
from baseline_data import (BENCHMARK_TOOLS, COMPARATOR_LANGUAGES, baseline_rows, output_contract, read_rows,
                           specialized_patterns, specialized_suite)
from language_data import SPECIALIZED_VARIANT_SUFFIXES

sys.path.insert(0, str(BENCHMARK_TOOLS / 'baseline'))
import aggregate_results


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


class TestSpecializedPatternRows(unittest.TestCase):
    MANIFEST = [row for row in read_rows(BENCHMARK_TOOLS / 'baseline/rows.tsv') if specialized_suite(row['suite'])]
    CATALOG = baseline_data.SPECIALIZED_CATALOG.read_text().splitlines()
    GENERATED = 'trino-specialized-patterns-a/clickbenchHost/replace/regulator-generated-native-access'
    # A Regulator variant of a hypothetical RE2 section, compared with native RE2 by the shared baseline.
    RE2_CATALOG = '\t'.join(('re2-specialized-patterns', 'literalPrefix', 'contains', 'abc[0-9]+', 'Literal prefix', 'Finds a literal prefix.',
                             'native-re2', 're2-specialized/'))
    RE2 = [{'row_id': f're2-specialized-patterns/literalPrefix/contains/{system}', 'shard_id': 're2-specialized-patterns',
            'suite': 're2-specialized-patterns', 'system': system, 'benchmark': f'io.airlift.regulator.BenchmarkSpecializedRe2.contains{suffix}',
            'parameters': 'workload=literalPrefix', 'comparator': 'native-re2'}
           for system, suffix in (('regulator-native-access', ''), ('regulator-object-row', ''), ('regulator-generated-native-access', 'Generated'),
                                  ('regulator-generated-object-row', 'Generated'), ('native-re2-before', ''), ('native-re2-after', ''))]

    def rows(self, manifest=MANIFEST, score=lambda row, replica: 10, hosts=lambda rows: rows, comparisons=lambda rows: rows):
        # The baseline reducer pairs every Regulator variant on a host with that host's one comparator run.
        # Regulator variants take score(row, replica) ns and comparators always take 100 ns.
        tables = {'row-aggregates': [], 'host-rows': []}
        for row in manifest:
            tables['row-aggregates'].append({'platform': 'c9g', 'row_id': row['row_id'], 'benchmark': row['benchmark'], 'parameters': row['parameters']})
            for replica in (1, 2, 3):
                tables['host-rows'].append({
                    'platform': 'c9g', 'shard_id': row['shard_id'], 'suite': row['suite'], 'rebar_corpus': '', 'replica_id': str(replica),
                    'host_epoch': f'epoch-{replica}', 'confirmation': 'false', 'row_id': row['row_id'], 'instance_id': f'i-{replica}',
                    'score': str(score(row, replica) if row['system'].startswith('regulator') else 100), 'score_unit': 'ns/op',
                    'native_bracket_drift': '', 'outcome': 'accepted'})
        tables['host-rows'] = hosts(tables['host-rows'])
        _, ratios = aggregate_results.build_comparisons(tables['host-rows'], manifest)
        tables['comparison-aggregates'] = [{'platform': 'c9g', 'comparison_id': comparison, 'unresolved': 'false', 'unresolved_reasons': ''}
                                           for comparison in sorted({row['comparison_id'] for row in ratios})]
        tables['host-comparator-ratios'] = comparisons(ratios)
        with tempfile.TemporaryDirectory() as directory:
            for name, rows in tables.items():
                with (Path(directory) / f'{name}.tsv').open('w', newline='') as output:
                    writer = csv.DictWriter(output, fieldnames=list(rows[0]), delimiter='\t')
                    writer.writeheader()
                    writer.writerows(rows)
            return baseline_rows(directory, {'cases': []})

    def catalog(self, lines):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        catalog = Path(directory.name) / 'specialized-patterns.tsv'
        catalog.write_text('\n'.join(lines) + '\n')
        return patch.object(baseline_data, 'SPECIALIZED_CATALOG', catalog)

    def test_every_variant_pairs_with_its_specialized_engine_row_and_earlier_variants(self):
        rows = self.rows()
        catalog = {(workload, operation): entry for (_, workload, operation), entry in specialized_patterns().items()}
        self.assertEqual({row['population'] for row in rows}, {'specialized-patterns'})
        self.assertEqual({row['comparator'] for row in rows}, {None})
        views = {}
        for row in rows:
            views.setdefault((row['language'], row['memoryMode']), []).append(row)
        # LIKE has no memory routes, so its rows appear in both views. LIKE has no regular-engine variant.
        variants = {'trino': {'/regular-engine', '', '/generated'}, 'like': {'', '/generated'}}
        patterns = Counter(COMPARATOR_LANGUAGES[entry['comparator']] for entry in catalog.values())
        self.assertEqual({view: len(view_rows) for view, view_rows in views.items()},
                         {(language, mode): count * len(variants[language]) for language, count in patterns.items() for mode in ('native', 'safe')})
        for view_rows in views.values():
            self.assertEqual(len({row['id'] for row in view_rows}), len(view_rows))
            anchors = {}
            for row in view_rows:
                expected = catalog[row['caseId'].split('/')[1], row['operation']]
                self.assertEqual((row['name'], row['workload']['pattern'], row['workload']['description']),
                                 (expected['name'], expected['pattern'], expected['description']))
                anchor = expected['case_prefix'] + expected['workload']
                variant = row['caseId'].removeprefix(anchor)
                anchors.setdefault((anchor, row['operation']), {})[variant] = row
            for anchor in anchors.values():
                self.assertEqual(set(anchor), variants[view_rows[0]['language']])
                # Each variant carries gain warnings over every earlier variant in column order.
                earlier = set()
                for suffix in [suffix for suffix in SPECIALIZED_VARIANT_SUFFIXES if suffix in anchor]:
                    self.assertEqual(set(anchor[suffix].get('variantGainWarnings', {})), earlier)
                    earlier.add(anchor[suffix]['id'])

        regular = next(row for row in rows if row['caseId'] == 'trino-specialized/clickbenchHost/regular-engine' and row['memoryMode'] == 'safe')
        self.assertEqual(regular['id'], 'baseline/trino-specialized-patterns/clickbenchHost/replace/regular-engine')
        generated = next(row for row in rows if row['caseId'] == 'trino-like-specialized/Q14_TYPE_PROMO/generated')
        self.assertEqual(generated['id'], 'baseline/trino-like-specialized/Q14_TYPE_PROMO/generated')
        # Each LIKE time scans many TPC-H column rows rather than matching one input.
        self.assertEqual(generated['workload']['columnRows'], 16384)
        self.assertEqual(generated['workload']['inputDescription'], 'Each operation scans the first 16,384 rows of TPC-H part.type at scale factor 1.')
        self.assertIsNone(generated['inputBytes'])

    def test_variant_gain_warnings_pair_each_host_with_itself(self):
        # Against Joni the regular engine is 0.8, 1.0 and 1.2 and generated code 0.1, 0.125 and 0.15:
        # the hosts disagree about Joni, but generated code is 0.125 of the regular engine on every host.
        # The specialized engine's constant 50 ns makes generated code vary against it instead.
        times = {'regular-engine': (80, 100, 120), 'generated': (10, 12.5, 15)}
        def score(row, replica):
            variant = next((name for name in times if f'-{name}-' in row['system']), None)
            return times[variant][replica - 1] if variant else 50
        def gains(hosts=lambda rows: rows, comparisons=lambda rows: rows):
            rows = {row['caseId']: row for row in self.rows(score=score, hosts=hosts, comparisons=comparisons)
                    if row['memoryMode'] == 'native' and row['caseId'].startswith('trino-specialized/clickbenchHost')}
            return rows, rows['trino-specialized/clickbenchHost/generated']['variantGainWarnings']
        rows, warnings = gains()
        regular = rows['trino-specialized/clickbenchHost/regular-engine']
        self.assertIn('Hosts disagree about which engine is faster', regular['result']['warnings'])
        self.assertNotIn('variantGainWarnings', regular)
        self.assertEqual(warnings, {regular['id']: [], rows['trino-specialized/clickbenchHost']['id']: ['Host ratio variation exceeds 5%']})

        def reordered(rows):
            return [row for row in rows if row['candidate_row_id'] != self.GENERATED] + [
                row for row in reversed(rows) if row['candidate_row_id'] == self.GENERATED]
        self.assertEqual(gains(comparisons=reordered)[1], warnings)
        # Raw-sample precision still applies to each variant's own measurement.
        def rejected(rows):
            return [{**row, 'outcome': 'precision-rejected'} if (row['row_id'], row['replica_id']) == (self.GENERATED, '3') else row for row in rows]
        self.assertEqual(gains(hosts=rejected)[1][regular['id']], ['At least one original host exceeded the sample precision gate'])

    def test_two_operations_on_one_workload_remain_two_rows(self):
        extract = [row for row in self.MANIFEST if '/urlHostPort/extract/' in row['row_id']]
        replace = [{**row, 'row_id': row['row_id'].replace('/extract/', '/replace/'), 'benchmark': row['benchmark'].replace('.extract', '.replace')}
                   for row in extract]
        url = next(line for line in self.CATALOG if '\turlHostPort\textract\t' in line)
        with self.catalog([*self.CATALOG, url.replace('\textract\t', '\treplace\t')]):
            rows = [row for row in self.rows(self.MANIFEST + replace)
                    if row['caseId'] == 'trino-specialized/urlHostPort' and row['memoryMode'] == 'native']
        self.assertEqual({(row['id'], row['operation']) for row in rows},
                         {('baseline/trino-specialized-patterns/urlHostPort/extract', 'extract'),
                          ('baseline/trino-specialized-patterns/urlHostPort/replace', 'replace')})

    def test_duplicate_catalog_entries_fail(self):
        with self.catalog([*self.CATALOG, self.CATALOG[1]]), self.assertRaisesRegex(ValueError, 'duplicate specialized pattern'):
            specialized_patterns()

    def test_a_new_language_reaches_the_report_through_its_catalog_rows(self):
        with self.catalog([*self.CATALOG, self.RE2_CATALOG]):
            rows = [row for row in self.rows(self.MANIFEST + self.RE2) if row['language'] == 're2']
        cases = ('re2-specialized/literalPrefix', 're2-specialized/literalPrefix/generated')
        self.assertEqual({(row['caseId'], row['memoryMode']) for row in rows}, {(case, mode) for case in cases for mode in ('native', 'safe')})
        self.assertEqual({row['population'] for row in rows}, {'specialized-patterns'})
        generated = next(row for row in rows if row['caseId'] == 're2-specialized/literalPrefix/generated')
        self.assertEqual(set(generated['variantGainWarnings']), {'baseline/re2-specialized-patterns/literalPrefix/contains'})

    def test_rejects_rows_that_differ_from_the_catalog_or_their_pairing(self):
        experimental = [{**row, 'row_id': row['row_id'].replace('/regulator-generated-', '/regulator-experimental-'),
                         'system': row['system'].replace('regulator-generated-', 'regulator-experimental-')}
                        for row in self.MANIFEST if row['system'].startswith('regulator-generated-')]
        mislabelled = [{**row, 'parameters': 'workload=keyValue'} if row['row_id'] == self.GENERATED else row for row in self.MANIFEST]
        def like(system, **changes):
            return lambda rows: [{**row, **changes} if row['row_id'].startswith('trino-like-specialized/') and row['row_id'].endswith('/' + system)
                                 else row for row in rows]
        def like_comparisons(**changes):
            return lambda rows: [{**row, **changes} if row['suite'] == 'trino-like-specialized' else row for row in rows]
        def third(**changes):
            return lambda rows: [{**row, **changes} if (row['candidate_row_id'], row['replica_id']) == (self.GENERATED, '3') else row for row in rows]
        def missing(rows):
            return [row for row in rows if (row['candidate_row_id'], row['replica_id']) != (self.GENERATED, '3')]
        cases = {
            # The reducer pairs every Regulator system with Joni, so an unknown regex variant reaches import.
            'unknown variant': ({'manifest': self.MANIFEST + experimental}, 'unknown specialized-pattern system: regulator-experimental-native-access'),
            'uncatalogued suite': ({'manifest': self.MANIFEST + self.RE2}, 'not in the catalog: re2-specialized-patterns/literalPrefix/contains'),
            'mislabelled workload': ({'manifest': mislabelled}, 'measures workload keyValue'),
            'candidate unit': ({'hosts': like('regulator', score_unit='us/op')}, 'is in us/op, not ns/op'),
            # The reducer scales a 0.1 us comparator into the ratio, but import would publish 0.1 ns.
            'comparator unit': ({'hosts': like('trino-sql', score='0.1', score_unit='us/op')}, 'is in us/op, not ns/op'),
            'unexpected comparator': ({'comparisons': like_comparisons(comparator_system='trino-optimized')},
                                      'compared with trino-optimized, not trino-sql'),
            'missing host': ({'comparisons': missing}, 'must share one host cohort'),
            'comparator score': ({'comparisons': third(comparator_score='101')}, 'one comparator measurement on host epoch-3'),
            'comparator row': ({'comparisons': third(comparator_row_ids='trino-specialized-patterns-a/urlHostPort/extract/joni')},
                               'one comparator measurement on host epoch-3'),
        }
        for case, (fixture, error) in cases.items():
            with self.subTest(case=case), self.assertRaisesRegex(ValueError, error):
                self.rows(**fixture)
        changed = [line.replace('\t%BRASS\t', '\t%COPPER\t') for line in self.CATALOG]
        with self.catalog(changed), self.assertRaisesRegex(ValueError, 'differs from its TPC-H workload: Q02_TYPE_BRASS'):
            self.rows()
