from copy import deepcopy
import csv
import json
from pathlib import Path
import tempfile
import unittest
import tarfile
import io
import hashlib

from language_data import PLATFORMS
from qualify_capture_data import SHARDS, COLLECTOR_PATHS, apply_comparisons, cohort_receipts, measurements, verify_collector_source
from test_language_data import host


def write_receipts(directory, receipts):
    directory.mkdir(exist_ok=True)
    with (directory / 'accepted-sessions.tsv').open('w') as output:
        writer = csv.DictWriter(output, fieldnames=('platform', 'shard_id', 'replica_id', 'instance_id', 'systems'), delimiter='\t')
        writer.writeheader()
        writer.writerows(receipts)


class TestQualifyCaptureData(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.pilot = self.root / 'pilot'
        self.retry = self.root / 'retry'
        self.confirmation = self.root / 'confirmation'
        self.receipts = [
            {'platform': platform, 'shard_id': shard, 'replica_id': str(replica),
             'instance_id': f'{platform}-{shard}-{replica}', 'systems': 'joni,regulator-native-access,regulator-object-row'}
            for replica in (4, 1, 2) for platform in PLATFORMS for shard in SHARDS
        ]
        write_receipts(self.pilot, self.receipts[:5])
        write_receipts(self.retry, self.receipts[5:6])
        write_receipts(self.confirmation, self.receipts[6:])

    def test_accepts_separately_recorded_retry_without_changing_pilot(self):
        original = (self.pilot / 'accepted-sessions.tsv').read_bytes()
        receipts = cohort_receipts([self.pilot, self.retry], self.confirmation, PLATFORMS)
        self.assertEqual(len(receipts), 18)
        self.assertEqual((self.pilot / 'accepted-sessions.tsv').read_bytes(), original)

    def test_rejects_missing_and_duplicate_pilot_cells(self):
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            cohort_receipts([self.pilot], self.confirmation, PLATFORMS)
        write_receipts(self.retry, self.receipts[:1] + self.receipts[5:6])
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            cohort_receipts([self.pilot, self.retry], self.confirmation, PLATFORMS)

    def test_rejects_reused_host_and_wrong_replica(self):
        self.receipts[6]['instance_id'] = self.receipts[0]['instance_id']
        write_receipts(self.confirmation, self.receipts[6:])
        with self.assertRaisesRegex(ValueError, 'reused a host'):
            cohort_receipts([self.pilot, self.retry], self.confirmation, PLATFORMS)
        self.receipts[6]['instance_id'] = 'independent'
        self.receipts[6]['replica_id'] = '3'
        write_receipts(self.confirmation, self.receipts[6:])
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            cohort_receipts([self.pilot, self.retry], self.confirmation, PLATFORMS)

    def test_split_recovery_has_complete_candidate_slots(self):
        missing = self.receipts[-1]
        write_receipts(self.confirmation, self.receipts[6:-1])
        roots = [self.confirmation]
        for route in ('native-access', 'object-row'):
            root = self.root / route
            write_receipts(root, [{**missing, 'instance_id': 'split-' + route, 'systems': 'joni,regulator-' + route}])
            roots.append(root)
        self.assertEqual(len(cohort_receipts([self.pilot, self.retry], roots, PLATFORMS)), 19)
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            cohort_receipts([self.pilot, self.retry], roots[:-1], PLATFORMS)
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            cohort_receipts([self.pilot, self.retry], roots + [roots[-1]], PLATFORMS)
        write_receipts(roots[-1], [{**missing, 'instance_id': 'safe', 'systems': 'regulator-object-row'}])
        with self.assertRaisesRegex(ValueError, 'same-host Joni'):
            cohort_receipts([self.pilot, self.retry], roots, PLATFORMS)

    def test_source_equivalence_requires_identical_measured_code(self):
        def archive(path, commit, files):
            with tarfile.open(path, 'w:gz', format=tarfile.PAX_FORMAT, pax_headers={'comment': commit}) as output:
                for name, content in files.items():
                    entry = tarfile.TarInfo(name)
                    entry.size = len(content)
                    output.addfile(entry, io.BytesIO(content))
        base, candidate = self.root / 'base.tar.gz', self.root / 'candidate.tar.gz'
        files = {name: b'before' for name in COLLECTOR_PATHS}
        files['src/engine.java'] = b'unchanged'
        archive(base, 'base', files)
        changed = {**files, **{name: b'after' for name in COLLECTOR_PATHS}}
        archive(candidate, 'candidate', changed)
        sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
        proof = {'baseCandidate': 'base', 'collectorCandidate': 'candidate',
                 'baseArchiveSha256': sha(base), 'archiveSha256': sha(candidate),
                 'changedCollectorFiles': {name: hashlib.sha256(b'after').hexdigest() for name in COLLECTOR_PATHS}}
        self.assertEqual(verify_collector_source(proof, base, candidate, 'base'), proof)
        changed['src/engine.java'] = b'changed engine'
        archive(candidate, 'candidate', changed)
        proof['archiveSha256'] = sha(candidate)
        with self.assertRaisesRegex(ValueError, 'outside the declared'):
            verify_collector_source(proof, base, candidate, 'base')

    def raw_fixture(self):
        manifest = []
        paths = []
        for system, route in (('joni', 'native-access'), ('regulator-native-access', 'native-access'), ('regulator-object-row', 'object-row')):
            entry = {'benchmark': 'fixture', 'params': {}, 'warmupTime': '200 ms' if system == 'joni' else '50 ms',
                     'forks': 1 if system == 'joni' else 0,
                     'measurementTime': '200 ms' if system == 'joni' else '50 ms',
                     'warmupIterations': 10, 'measurementIterations': 10,
                     'primaryMetric': {'score': 10, 'scoreUnit': 'ns/op', 'rawData': [[10] * 10]}}
            manifest.append({'row_id': 'fixture/' + system, 'system': system, 'benchmark': 'fixture', 'parameters': '-'})
            for process in range(1 if system == 'joni' else 5):
                suffix = '.json' if system == 'joni' else f'-process-{process}.json'
                path = self.root / 'routes' / route / 'raw' / ('trino-operations-' + system + suffix)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps([entry]))
                paths.append(path)
        return manifest, paths

    def test_requires_raw_joni_interval_and_all_regulator_epochs(self):
        manifest, paths = self.raw_fixture()
        results = measurements(self.root, 'trino-operations', manifest)
        self.assertEqual(results['fixture/joni']['sampleCount'], 10)
        self.assertEqual(results['fixture/regulator-native-access']['sampleCount'], 50)
        entry = json.loads(paths[0].read_text())
        entry[0]['measurementTime'] = '50 ms'
        paths[0].write_text(json.dumps(entry))
        with self.assertRaisesRegex(ValueError, 'not 200 ms'):
            measurements(self.root, 'trino-operations', manifest)

    def test_later_process_cannot_hide_changed_metadata(self):
        manifest, paths = self.raw_fixture()
        entry = json.loads(paths[-1].read_text())
        entry[0]['warmupIterations'] = 1
        paths[-1].write_text(json.dumps(entry))
        with self.assertRaisesRegex(ValueError, 'iteration count changed'):
            measurements(self.root, 'trino-operations', manifest)

    def test_safe_only_raw_results_require_local_comparator(self):
        manifest, paths = self.raw_fixture()
        manifest = [row for row in manifest if row['system'] != 'regulator-native-access']
        with self.assertRaisesRegex(ValueError, 'missing precision-cohort raw'):
            measurements(self.root, 'trino-operations', manifest)
        paths[0].rename(self.root / 'routes/object-row/raw/trino-operations-joni.json')
        results = measurements(self.root, 'trino-operations', manifest)
        self.assertEqual(set(results), {'fixture/joni', 'fixture/regulator-object-row'})

    def test_rejects_missing_raw_epoch(self):
        manifest, paths = self.raw_fixture()
        paths[-1].unlink()
        with self.assertRaisesRegex(ValueError, 'process sample groups'):
            measurements(self.root, 'trino-operations', manifest)

    def test_rejects_changed_regulator_duration(self):
        manifest, paths = self.raw_fixture()
        entry = json.loads(paths[-1].read_text())
        entry[0]['measurementTime'] = '1 s'
        paths[-1].write_text(json.dumps(entry))
        with self.assertRaisesRegex(ValueError, 'Regulator protocol changed'):
            measurements(self.root, 'trino-operations', manifest)

    def comparison_fixture(self):
        rows, groups = [], {}
        original = {'state': 'compared', 'candidateNs': 123, 'comparatorNs': 456}
        for index in range(2160):
            identifier = f'trino-operations/fixture-{index}'
            rows.append({'id': 'baseline/' + identifier, 'platform': 'c9g', 'memoryMode': 'native',
                         'source': 'baseline', 'inputBytes': 10, 'result': deepcopy(original)})
            groups['c9g', identifier + '/regulator-native-access'] = [host(replica, 10, 20) for replica in (1, 2, 4)]
        return rows, groups

    def test_preserves_originals_and_uses_only_declared_followup_hosts(self):
        rows, groups = self.comparison_fixture()
        originals = [deepcopy(row['result']) for row in rows]
        self.assertEqual(apply_comparisons(rows, groups), 2160)
        self.assertEqual([row['originalResult'] for row in rows], originals)
        self.assertTrue(all(row['source'] == 'baseline-qualified' for row in rows))
        self.assertTrue(all(row['result']['deltaNs'] == -10 for row in rows))
        with self.assertRaisesRegex(ValueError, 'does not map exactly'):
            apply_comparisons(rows, groups)

    def test_incomplete_mapping_leaves_originals_untouched(self):
        rows, groups = self.comparison_fixture()
        original = deepcopy(rows)
        groups.pop(next(reversed(groups)))
        with self.assertRaisesRegex(ValueError, 'all three independent hosts'):
            apply_comparisons(rows, groups)
        self.assertEqual(rows, original)


if __name__ == '__main__':
    unittest.main()
