from copy import deepcopy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from language_data import LANGUAGES, MEMORY_MODES, PLATFORMS, RELEASE_PLATFORMS, language_rows, outcome, reduce_hosts, timing, validate_language_report


def host(replica, candidate, comparator):
    return {'replica': replica, 'instanceId': f'host-{replica}',
            'candidate': outcome({'outcome': 'compared', 'samples_ns': [[candidate] * 10] * 5}),
            'comparator': outcome({'outcome': 'compared', 'samples_ns': [[comparator] * 10] * 5})}


def report_fixture():
    rows = []
    for language in LANGUAGES:
        for platform in PLATFORMS:
            for mode in MEMORY_MODES:
                operations = ('matches', 'compile', 'singleUse') if language == 'like' else ('compile', 'reusedContains', 'reusedCount', 'singleUseContains', 'singleUseCount', 'execute')
                for operation in operations:
                    source = ('baseline' if operation == 'matches' else 'like-supplement') if language == 'like' else 'language'
                    rows.append({'id': 'fixture/' + operation, 'caseId': 'fixture', 'name': 'Fixture',
                                 'language': language, 'platform': platform, 'memoryMode': mode,
                                 'operation': operation, 'model': operation, 'population': 'like' if language == 'like' else 'ordinary-scalar',
                                 'family': 'Fixture', 'source': source, 'inputBytes': None,
                                 'result': reduce_hosts([host(replica, 10, 20) for replica in (1, 2, 3)])})
    return {'schemaVersion': 2, 'sources': {'currentLabel': 'Fixture', 'currentCandidate': 'candidate', 'engineTree': 'engine', 'jdk': 'JDK'},
            'languages': LANGUAGES, 'platforms': PLATFORMS, 'memoryModes': MEMORY_MODES,
            'methodology': ['Fixture only'], 'provenance': {'fixture': True}, 'rows': rows}


class TestLanguageData(unittest.TestCase):
    def test_release_report_supports_r_family_and_integrated_like_lifecycle(self):
        data = report_fixture()
        data['provenance']['releasedArtifact'] = {'version': '1.0'}
        data['platforms'] = RELEASE_PLATFORMS
        for row in data['rows']:
            row['platform'] = 'r' + row['platform'][1:]
            if row['source'] == 'like-supplement':
                row['source'] = 'baseline'
        validate_language_report(data)
        data['rows'][0]['platform'] = 'c9g'
        with self.assertRaises(ValueError):
            validate_language_report(data)

    def test_like_coverage_requires_the_capture_format_source_pairs(self):
        historical = report_fixture()
        compile_row = next(row for row in historical['rows']
                           if row['language'] == 'like' and row['operation'] == 'compile')
        compile_row['source'] = 'baseline'
        with self.assertRaisesRegex(ValueError, 'incomplete language/lifecycle'):
            validate_language_report(historical)

        released = report_fixture()
        released['provenance']['releasedArtifact'] = {'version': '1.0'}
        for row in released['rows']:
            if row['language'] == 'like':
                row['source'] = 'baseline'
        validate_language_report(released)
        next(row for row in released['rows']
             if row['language'] == 'like' and row['operation'] == 'singleUse')['source'] = 'like-supplement'
        with self.assertRaisesRegex(ValueError, 'incomplete language/lifecycle'):
            validate_language_report(released)
    def test_host_call_contracts_must_agree_and_survive_reduction(self):
        languages = ('re2', 'java', 'trino')
        case = {'id': 'fixture', 'model': 'count', 'population': 'bulk-text', 'family': 'test', 'pattern_bytes': 1,
                'mappings': {language: {'pattern': 'a', 'status': 'compatible', 'reason': 'same bytes'} for language in languages}}
        manifest = {'suite': 'language-bulk', 'cases': [case]}
        case_hash = hashlib.sha256(json.dumps(case, sort_keys=True, ensure_ascii=True, allow_nan=False).encode()).hexdigest()
        data = {'hosts': [], 'observations': [], 'comparisons': []}
        for language in languages:
            for platform in PLATFORMS:
                for replica in (1, 2, 3):
                    identity = ['language-bulk', 'fixture', language, platform, replica]
                    data['hosts'].append({'logical_identity': identity, 'instance_id': f'host-{replica}', 'collector': 'test', 'case_sha256': case_hash})
                    for mode in MEMORY_MODES:
                        candidate, comparator = f'{mode}/candidate', f'{mode}/comparator'
                        for identifier in (candidate, comparator):
                            data['observations'].append({'logical_identity': identity, 'id': identifier, 'outcome': 'compared', 'samples_ns': [[10]]})
                        data['comparisons'].append({'logical_identity': identity, 'id': mode, 'logical_case': 'fixture', 'language': language,
                                                    'mode': mode, 'operation': 'execute', 'candidate': candidate, 'comparator': comparator,
                                                    'input_bytes_per_operation': 1, 'work_contract': 'bulk-matched-outputs-v1' if language == 'trino' else 'public-find-count-v2'})
        reduced = language_rows(data, [manifest])
        self.assertEqual(len(reduced), 18)
        self.assertTrue(all(row['workContract'] == 'public-find-count-v2' for row in reduced if row['language'] != 'trino'))
        changed = deepcopy(data)
        changed['comparisons'][0]['work_contract'] = 'different-contract'
        with self.assertRaisesRegex(ValueError, 'mixed benchmark call contracts'):
            language_rows(changed, [manifest])
        del changed['comparisons'][0]['work_contract']
        with self.assertRaisesRegex(ValueError, 'mixed benchmark call contracts'):
            language_rows(changed, [manifest])
        for comparison in data['comparisons']:
            del comparison['work_contract']
        self.assertTrue(all('workContract' not in row for row in language_rows(data, [manifest])))

    def test_standalone_build_keeps_data_inline_and_escapes_html(self):
        scripts = Path(__file__).resolve().parent
        data = report_fixture()
        data['sources']['currentLabel'] = 'Test fixture, not benchmark results'
        data['rows'][0]['name'] = '</script><script>unexpected()</script>'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'fixture.json').write_text(json.dumps(data))
            (root / 'manifest.json').write_text(json.dumps({'reportSchemaVersion': 2, 'current': 'fixture.json'}))
            subprocess.run(['node', str(scripts / 'build.mjs'), '--data-directory', str(root), '--output-name', 'test-fixture'], check=True, capture_output=True)
        html = (scripts.parents[1] / 'target/benchmark-report/test-fixture/regulator-benchmarks.html').read_text()
        self.assertIn('Test fixture, not benchmark results', html)
        self.assertNotIn('<script src=', html)
        self.assertNotIn('<link rel="stylesheet"', html)
        self.assertNotIn('</script><script>unexpected()', html)
        embedded = html.split('<script type="application/json" id="report-data">', 1)[1].split('</script>', 1)[0]
        self.assertEqual(json.loads(embedded), data)

    def test_complete_report_schema(self):
        validate_language_report(report_fixture())

    def test_preliminary_allows_identified_targeted_results_without_weakening_final_gate(self):
        data = report_fixture()
        data['sources']['currentLabel'] = '1.0 preliminary results'
        data['publication'] = {'status': 'preliminary', 'sourcePolicy': 'mixed-development-revisions',
                               'note': 'Development builds with targeted updates.'}
        row = data['rows'][0]
        row['result'] = reduce_hosts([host(1, 10, 20)])
        row['measurementSource'] = {'cohort': 'targeted', 'candidate': 'candidate'}
        validate_language_report(data)
        del data['publication']
        with self.assertRaisesRegex(ValueError, 'independent hosts'):
            validate_language_report(data)

    def test_preliminary_rejects_missing_hosts_sources_labels_and_private_preview(self):
        data = report_fixture()
        data['sources']['currentLabel'] = '1.0 preliminary results'
        data['publication'] = {'status': 'preliminary', 'sourcePolicy': 'mixed-development-revisions',
                               'note': 'Development builds with targeted updates.'}
        data['rows'][0]['result'] = reduce_hosts([host(1, 10, 20)])
        data['rows'][0]['measurementSource'] = {'cohort': 'targeted'}
        for mutate, message in (
                (lambda d: d['rows'][0]['result'].update(hosts=[]), 'independent hosts'),
                (lambda d: d['rows'][0].pop('measurementSource'), 'source provenance'),
                (lambda d: d['sources'].update(currentLabel='Final results'), 'publication metadata'),
                (lambda d: d['publication'].update(status='final'), 'publication metadata'),
                (lambda d: d['publication'].update(note=''), 'publication metadata'),
                (lambda d: d.update(presentation={'reviewPreview': True}), 'private-preview')):
            with self.subTest(message=message):
                changed = deepcopy(data)
                mutate(changed)
                with self.assertRaisesRegex(ValueError, message):
                    validate_language_report(changed)

    def test_work_contract_is_not_a_blanket_comparability_override(self):
        data = report_fixture()
        row = next(row for row in data['rows'] if row['language'] == 'trino' and row['operation'] == 'execute')
        row['model'] = 'count-captures'
        row['workContract'] = 'trino-output-assisted-count-v1'
        validate_language_report(data)
        row['workContract'] = 'trino-matcher-count-v3'
        validate_language_report(data)
        for invalid in ('public-find-count-v2', 'unknown', None):
            row['workContract'] = invalid
            with self.assertRaisesRegex(ValueError, 'benchmark work contract'):
                validate_language_report(data)

    def test_rejects_missing_lifecycle_coverage(self):
        data = report_fixture()
        data['rows'] = [row for row in data['rows'] if row['language'] != 'like' or row['operation'] != 'singleUse']
        with self.assertRaisesRegex(ValueError, 'incomplete language/lifecycle'):
            validate_language_report(data)

    def test_rejects_missing_single_workload_even_if_operation_remains(self):
        data = report_fixture()
        extra = deepcopy(data['rows'][0])
        extra['id'] = 'additional/compile'
        data['rows'].append(extra)
        with self.assertRaisesRegex(ValueError, 'identities differ'):
            validate_language_report(data)

    def test_rejects_unpaired_lifecycle_and_invalid_measurements(self):
        for mutation, error in (
                (lambda row: row.update(caseId='different'), 'missing reused lifecycle partner'),
                (lambda row: row.update(inputBytes=True), 'invalid input size'),
                (lambda row: row['result'].update(deltaNs=True), 'invalid delta'),
                (lambda row: row['result'].update(deltaNsPerByte=2), 'normalization'),
                (lambda row: row['result'].update(state='verification-failed'), 'unpublishable')):
            with self.subTest(error=error):
                data = report_fixture()
                row = next(row for row in data['rows'] if row['operation'] == 'singleUseContains')
                mutation(row)
                with self.assertRaisesRegex(ValueError, error):
                    validate_language_report(data)

    def test_paired_medians_do_not_divide_unpaired_medians(self):
        rows = [host(1, 10, 1), host(2, 20, 100), host(3, 30, 10)]
        result = reduce_hosts(rows, 10)
        self.assertEqual(result['ratio'], 3)
        self.assertEqual(result['candidateNs'], 20)
        self.assertEqual(result['comparatorNs'], 10)
        self.assertEqual(result['deltaNs'], 9)
        self.assertEqual(result['deltaNsPerByte'], .9)
        self.assertIn('Hosts disagree about which engine is faster', result['warnings'])

    def test_original_results_receive_the_same_validation(self):
        data = report_fixture()
        data['rows'][0]['originalResult'] = deepcopy(data['rows'][0]['result'])
        validate_language_report(data)
        data['rows'][0]['originalResult']['deltaNs'] = float('nan')
        with self.assertRaisesRegex(ValueError, 'invalid delta'):
            validate_language_report(data)

    def test_retains_slow_process_even_when_median_is_fast(self):
        row = {'samples_ns': [[1] * 10, [1] * 10, [100] * 10, [1] * 10, [1] * 10]}
        result = timing(row)
        self.assertEqual(result['medianNs'], 1)
        self.assertEqual(result['epochMeansNs'], [1, 1, 100, 1, 1])
        self.assertGreater(result['epochRse'], .05)

    def test_compile_has_no_byte_delta(self):
        result = reduce_hosts([host(1, 10000, 1000)])
        self.assertEqual(result['deltaNs'], 9000)
        self.assertIsNone(result['deltaNsPerByte'])

    def test_invalid_or_unverified_results_never_become_scores(self):
        for state in ('verification-failed', 'missing', 'pending'):
            with self.subTest(state=state), self.assertRaises(ValueError):
                outcome({'outcome': state})
        for value in (float('nan'), float('inf'), 0, -1, True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                timing({'samples_ns': [[value]]})

    def test_timeout_is_distinct_from_incompatibility_and_has_no_ratio(self):
        timeout = outcome({'outcome': 'did-not-finish', 'attempts': [
            {'outcome': 'did-not-finish', 'phase': 'execution', 'limit_seconds': 30}] * 2})
        rows = [host(1, 1, 1)]
        rows[0]['comparator'] = timeout
        result = reduce_hosts(rows)
        self.assertEqual(result['state'], 'did-not-finish')
        self.assertNotIn('ratio', result)
        self.assertIn('execution exceeded 30 s', result['reason'])
        rows[0]['comparator'] = outcome({'outcome': 'not-compatible', 'reason': 'Different word boundaries'})
        self.assertEqual(reduce_hosts(rows)['state'], 'not-compatible')

    def test_rejects_unconfirmed_timeouts_and_shared_hosts(self):
        with self.assertRaises(ValueError):
            outcome({'outcome': 'did-not-finish', 'attempts': []})
        row = host(1, 1, 1)
        with self.assertRaises(ValueError):
            reduce_hosts([row, deepcopy(row)])

    def test_disagreeing_host_outcomes_require_investigation(self):
        rows = [host(1, 1, 1), host(2, 1, 1)]
        rows[1]['comparator'] = {'state': 'not-compatible', 'reason': 'unexpected'}
        with self.assertRaises(ValueError):
            reduce_hosts(rows)


if __name__ == '__main__':
    unittest.main()
