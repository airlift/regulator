import argparse
import copy
import csv
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release_capture
# release_capture puts the baseline and language tooling on the path; their campaign fixtures run attempts as
# hosts do.
import aggregate_results
import collection
import test_fleet
from test_unit_session import UnitCampaign


class TestReleaseCapture(unittest.TestCase):
    def setUp(self):
        self.release = release_capture.released_artifact.manifest(release_capture.ROOT, '1.0')
        self.collector = {'candidate_commit': 'c' * 40, 'root_tree': 'd' * 40, 'archive_sha256': 'e' * 64}
        part = {'id': 'case-0000-java', 'case': 'example', 'language': 'java'}
        self.manifest = {'suite': 'language-bulk', 'cases': [{'id': 'example', 'model': 'count'}]}
        platforms = release_capture.fleet.PLATFORMS
        self.data = {'plan': {'schema_version': 4, 'platforms': list(platforms),
                              'platform_allocations': release_capture.fleet.platform_allocations('language-bulk', platforms),
                              'partitions': [part]},
                     'candidate': {'source_commit': self.collector['candidate_commit'], 'source_tree': self.collector['root_tree']},
                     'hosts': [], 'observations': [], 'comparisons': []}
        self.receipts = []
        # The fixture's hosts measured no rows of their unit.
        self.units = {'unit': {'owned': []}}
        jar = '/worker/regulator-1.0.jar'
        classes = release_capture.released_artifact.PUBLIC_CLASSES
        artifact = {'manifest': self.release, 'jar_path': jar,
                    'production_classes': ['io/airlift/regulator/' + name + '.class' for name in classes],
                    'code_sources': {'io.airlift.regulator.' + name: jar for name in classes},
                    'classpath': [{'path': jar, 'files': {'regulator-1.0.jar': self.release['jar_sha256']}}]}
        # Each replica of the comparison's one unit, accepted from an attempt on its own r8i host.
        exports = hashlib.sha256(f"{part['id']}  {'f' * 64}\n".encode()).hexdigest()
        instance_type = release_capture.fleet.platform_allocation('language-bulk', 'r8i')['instance_type']
        for replica in (1, 2, 3):
            receipt = {'unit_attempt_id': f'host-{replica}/1', 'platform': 'r8i', 'unit_id': 'unit',
                       'replica_id': str(replica), 'instance_id': f'i-{replica}', 'instance_type': instance_type,
                       'observed_rows_sha256': exports, 'engine_tree': self.release['engine_tree'],
                       'candidate_commit': self.collector['candidate_commit'],
                       'candidate_archive_sha256': self.collector['archive_sha256']}
            self.receipts.append(receipt)
            provenance = {**self.data['candidate'],
                          'instance_identity': {'instanceId': receipt['instance_id'], 'instanceType': instance_type},
                          'jvm_build': {'released_artifact': copy.deepcopy(artifact),
                                        'jvm': {'classpath': copy.deepcopy(artifact['classpath'])}}}
            self.data['hosts'].append({'receipt': dict(receipt), 'partitions': [
                {'id': part['id'], 'export_sha256': 'f' * 64, 'provenance': provenance}]})

    def normalize(self):
        return release_capture.normalize_language(self.data, self.manifest, self.release, self.collector, self.receipts,
                                                  self.units)

    def test_released_artifact_and_collector_remain_separate(self):
        data = self.normalize()
        self.assertEqual(len(data['hosts']), 3)
        self.assertEqual({host['collector'] for host in data['hosts']}, {self.collector['candidate_commit']})
        self.assertNotEqual(self.collector['candidate_commit'], self.release['source_commit'])

    def test_lifecycle_r9g_uses_the_frozen_larger_allocation(self):
        self.manifest['suite'] = 'language-lifecycle'
        self.data['plan']['platform_allocations'] = release_capture.fleet.platform_allocations(
            'language-lifecycle', release_capture.fleet.PLATFORMS)
        instance_type = release_capture.fleet.platform_allocation('language-lifecycle', 'r9g')['instance_type']
        for receipt, host in zip(self.receipts, self.data['hosts']):
            receipt.update(platform='r9g', instance_type=instance_type)
            host['receipt'] = dict(receipt)
            host['partitions'][0]['provenance']['instance_identity']['instanceType'] = instance_type
        self.assertEqual(len(self.normalize()['hosts']), 3)

        self.data['hosts'][0]['partitions'][0]['provenance']['instance_identity']['instanceType'] = 'r9g.large'
        with self.assertRaisesRegex(ValueError, 'accepted instance'):
            self.normalize()
        # Hosts whose receipts and measurements agree on an instance type other than the frozen allocation's.
        for receipt, host in zip(self.receipts, self.data['hosts']):
            receipt['instance_type'] = 'r9g.large'
            host['receipt'] = dict(receipt)
            host['partitions'][0]['provenance']['instance_identity']['instanceType'] = 'r9g.large'
        with self.assertRaisesRegex(ValueError, 'frozen allocation'):
            self.normalize()

    def test_each_capture_pins_its_own_collector(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / name for name in ('language.tsv', 'baseline.tsv')]
            identities = [{**self.collector, 'engine_tree': self.release['engine_tree']},
                          {**self.collector, 'candidate_commit': 'a' * 40,
                           'root_tree': 'b' * 40, 'archive_sha256': '1' * 64,
                           'engine_tree': self.release['engine_tree']}]
            for path, identity in zip(paths, identities):
                with path.open('w') as output:
                    writer = csv.DictWriter(output, fieldnames=identity, delimiter='\t')
                    writer.writeheader()
                    writer.writerow(identity)
            plan = {'candidate_provenance': str(paths[0])}
            self.assertEqual(identities[0], release_capture.load_collector({}, plan, self.release))
            self.assertEqual(identities[1], release_capture.load_collector(
                {'candidate_provenance': str(paths[1])}, plan, self.release))
            with self.assertRaisesRegex(ValueError, 'changed the collector identity'):
                release_capture.normalize_language(self.data, self.manifest, self.release,
                                                   identities[1], self.receipts, self.units)
            with self.assertRaisesRegex(ValueError, 'production tree'):
                release_capture.load_collector({}, plan, {**self.release, 'engine_tree': '0' * 40})
            with self.assertRaisesRegex(ValueError, 'pinned collector'):
                release_capture.load_collector({}, {}, self.release)
            with self.assertRaises(FileNotFoundError):
                release_capture.load_collector({'candidate_provenance': str(paths[0]) + '.missing'},
                                               plan, self.release)

    def test_baseline_accepts_only_a_complete_unit_campaign_of_its_collector(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shards = [row['shard_id'] for row in release_capture.read_rows(release_capture.BASELINE / 'shards.tsv')]
            # Replicas 1 to 3 of every unit on both platforms, each worker running every unit of its replica,
            # and one unit confirmed on a fourth host.
            campaign = UnitCampaign(root / 'results', *shards)
            confirmed = campaign.add('r8i', campaign.units[0], 4, host=4)
            receipts = campaign.receipts
            relabelled = [{**receipt, 'candidate_commit': 'f' * 40} for receipt in receipts + [confirmed]]

            def phase(name, ledger, rejected=()):
                # The controller keeps each phase's semantic-rejection ledger beside its accepted units.
                (root / name).mkdir()
                with (root / name / release_capture.unit_session.SEMANTIC_REJECTIONS).open('w') as output:
                    fields = release_capture.unit_session.SEMANTIC_REJECTION_FIELDS
                    writer = csv.DictWriter(output, fieldnames=fields, delimiter='\t', extrasaction='ignore')
                    writer.writeheader()
                    writer.writerows(rejected)
                return campaign.write_ledger(root / name / 'accepted-units.tsv', ledger)

            primary, confirmation = phase('primary', receipts), phase('confirmation', [confirmed])
            phase('partial', receipts[1:])
            phase('rejected', receipts, rejected=receipts[:1])
            phase('relabelled', relabelled[:-1])
            phase('relabelled-confirmation', relabelled[-1:])
            # A confirmation ledger in an older receipt schema, with a column the current one lacks.
            (root / 'stale').mkdir()
            with (root / 'stale/accepted-units.tsv').open('w') as output:
                fields = [*release_capture.unit_session.UNIT_RECEIPT_FIELDS, 'copied_row_count']
                writer = csv.DictWriter(output, fieldnames=fields, delimiter='\t')
                writer.writeheader()
                writer.writerow({**confirmed, 'copied_row_count': '0'})

            def reduce(name, release_version=''):
                aggregate_results.aggregate(argparse.Namespace(
                    manifest=release_capture.BASELINE / 'rows.tsv', platforms=release_capture.BASELINE / 'platforms.tsv',
                    shards=release_capture.BASELINE / 'shards.tsv', primary_receipts=[primary],
                    confirmation_receipts=[confirmation], artifacts_root=[campaign.root], session_dir=[], events=[],
                    artifact_index=[], campaign_artifact=None, output_dir=root / name,
                    expected_manifest_rows=aggregate_results.parser().get_default('expected_manifest_rows'),
                    release_version=release_version))
                return str(root / name)
            # The fixture's attempts carry no release JAR; released_artifact's own tests cover the reducer's proof.
            with patch.object(release_capture.released_artifact, 'validate_baseline'):
                reduction = reduce('reduction', '1.0')
            unproven = reduce('unproven')
            baseline = {'primary': str(root / 'primary'), 'confirmation': str(root / 'confirmation'),
                        'reduction': reduction, 'campaign_id': 'campaign'}
            release = {'version': '1.0', 'engine_tree': confirmed['engine_tree']}
            collector = {'candidate_commit': confirmed['candidate_commit'],
                         'archive_sha256': confirmed['candidate_archive_sha256'],
                         'platform_manifest_sha256': release_capture.digest(release_capture.BASELINE / 'platforms.tsv')}
            release_capture.validate_baseline(baseline, release, collector)
            lifecycle = collection.prepare(root / 'lifecycle')
            rows = release_capture.baseline_rows(reduction, lifecycle)
            # Every population imports on both platforms, though each worker ran several units of a shard.
            populations = ('trino-operations', 'like', 'specialized-patterns', 'diagnostics-and-stress')
            self.assertEqual({(platform, population) for platform in ('r8i', 'r9g') for population in populations},
                             {(row['platform'], row['population']) for row in rows})
            cases = (
                ({}, {'engine_tree': '0' * 40}, {}, 'changed release source or collector'),
                ({}, {}, {'candidate_commit': '0' * 40}, 'changed release source or collector'),
                ({}, {}, {'archive_sha256': '0' * 64}, 'changed release source or collector'),
                ({'primary': str(root / 'partial')}, {}, {}, 'unit coverage failed'),
                ({'primary': str(root / 'absent')}, {}, {}, 'cannot read'),
                ({'confirmation': str(root / 'absent')}, {}, {}, 'cannot read'),
                ({'confirmation': str(root / 'stale')}, {}, {}, 'columns must be'),
                ({'primary': str(root / 'rejected')}, {}, {}, 'semantic failure'),
                ({'reduction': unproven}, {}, {}, 'did not prove'),
                # Receipts relabelled to another candidate, against the reduction of this one.
                ({'primary': str(root / 'relabelled'), 'confirmation': str(root / 'relabelled-confirmation')}, {},
                 {'candidate_commit': 'f' * 40}, 'changed the accepted unit attempts'),
            )
            for paths, source, pin, message in cases:
                with self.subTest(paths=paths, source=source, pin=pin), self.assertRaisesRegex(ValueError, message):
                    release_capture.validate_baseline({**baseline, **paths}, {**release, **source}, {**collector, **pin})

    def test_language_imports_exactly_each_plans_share_of_one_accepted_campaign(self):
        fixture = test_fleet.TestFleet()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        # Two plans of one case, measured by one campaign whose ledgers share its result root.
        manifest = collection.load(fixture.source / 'manifest.json')
        manifest['cases'] = manifest['cases'][:1]
        collection.save(fixture.source / 'manifest.json', manifest)
        plans = [fixture.root / shard for shard in ('language-a', 'language-b')]
        for directory in plans:
            release_capture.fleet.prepare(fixture.source, directory, shard_id=directory.name)
        result_root, receipts = fixture.run_campaign(directories=plans)
        items = []
        for directory in plans:
            export = result_root / f'{directory.name}-results.json'
            release_capture.fleet.aggregate(directory, result_root, export)
            items.append({'plan': str(directory), 'export': str(export)})
        release = {'engine_tree': receipts[0]['engine_tree']}
        collector = {'candidate_commit': receipts[0]['candidate_commit'], 'root_tree': 'tree',
                     'archive_sha256': receipts[0]['candidate_archive_sha256']}
        session = release_capture.unit_session

        def moved_row(data):
            # A row imported as another unit's, measured on the same host.
            row = data['observations'][0]
            source = next(host['receipt'] for host in data['hosts']
                          if host['receipt']['unit_attempt_id'] == row['unit_attempt_id'])
            row['unit_attempt_id'] = next(
                host['receipt']['unit_attempt_id'] for host in data['hosts']
                if host['receipt']['instance_id'] == source['instance_id'] and host['receipt']['unit_id'] != source['unit_id'])

        def imported(items, change):
            # Each plan's imported rows, with the accepted receipts of the plan's units.
            plans = {Path(item['plan']): release_capture.fleet.validate(Path(item['plan'])) for item in items}
            results = []
            for item, accepted in zip(items, release_capture.language_receipts(items, plans)):
                data = release_capture.load(item['export'])
                # The fixture's attempts carry no release JAR; released_artifact's own tests cover its proof.
                for host in data['hosts']:
                    for part in host['partitions']:
                        part['provenance']['jvm_build']['released_artifact'] = {'manifest': release}
                change(data)
                units = release_capture.work_units.load(Path(item['plan']))
                normalized = release_capture.normalize_language(data, manifest, release, collector, accepted, units)
                results.append((Path(item['plan']).name, accepted, units, normalized['observations']))
            return results

        rejected = [{field: receipts[0][field] for field in session.SEMANTIC_REJECTION_FIELDS}]
        cases = (
            ('complete', items, receipts, [], None),
            ('semantic rejection', items, receipts, rejected, 'semantic failure'),
            ('missing replica', items, [receipt for receipt in receipts if receipt['replica_id'] != '3'], [],
             'unit coverage failed'),
            ('plan not imported', items[:1], receipts, [], 'unexpected platform'),
            ('reused replica host', items, [{**receipt, 'instance_id': 'i-r9g-1'} if receipt['instance_id'] == 'i-r9g-2'
                                            else receipt for receipt in receipts], [], 'share an EC2 instance'),
            ('row moved between units', items, receipts, [], "differ from their accepted units' rows"),
        )
        for name, imported_items, ledger, rejections, message in cases:
            release_capture.fleet.write_tsv(result_root / 'accepted-units.tsv', session.UNIT_RECEIPT_FIELDS, ledger)
            release_capture.fleet.write_tsv(result_root / session.SEMANTIC_REJECTIONS,
                                            session.SEMANTIC_REJECTION_FIELDS, rejections)
            change = moved_row if name == 'row moved between units' else lambda data: None
            with self.subTest(name), patch.object(release_capture.released_artifact, 'validate_saved'):
                if message is None:
                    # Every row is its accepted attempt's: that unit's row of the plan, on that host and replica.
                    for shard, accepted, units, rows in imported(imported_items, change):
                        attempts = {receipt['unit_attempt_id']: receipt for receipt in accepted}
                        self.assertEqual(len(rows),
                                         sum(len(units[receipt['unit_id']]['owned']) for receipt in accepted))
                        for row in rows:
                            receipt = attempts[row['unit_attempt_id']]
                            case, language, _ = release_capture.fleet.row_atom(row['id'])
                            self.assertEqual(receipt['shard_id'], shard)
                            self.assertIn(row['id'], units[receipt['unit_id']]['owned'])
                            self.assertEqual(row['logical_identity'],
                                             [manifest['suite'], case, language, receipt['platform'],
                                              int(receipt['replica_id']), receipt['unit_id']])
                else:
                    with self.assertRaisesRegex(ValueError, message):
                        imported(imported_items, change)

    def test_rejects_missing_hosts_or_acceptance_receipts(self):
        self.data['hosts'].pop()
        with self.assertRaisesRegex(ValueError, 'missing, duplicate, or changed hosts'):
            self.normalize()
        self.setUp()
        self.receipts.pop()
        with self.assertRaisesRegex(ValueError, 'missing, duplicate, or changed hosts'):
            self.normalize()

    def test_rejects_changed_collector_archive(self):
        self.receipts[0]['candidate_archive_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'artifact or collector'):
            self.normalize()

    def test_rejects_mixed_jars_and_source_only_measurements(self):
        jvm_build = self.data['hosts'][0]['partitions'][0]['provenance']['jvm_build']
        jvm_build['released_artifact']['manifest'] = {**self.release, 'jar_sha256': '0' * 64}
        with self.assertRaisesRegex(ValueError, 'published release'):
            self.normalize()
        del jvm_build['released_artifact']
        with self.assertRaisesRegex(ValueError, 'published release'):
            self.normalize()

    def test_rejects_changed_export_or_scoped_plan(self):
        self.data['hosts'][0]['partitions'][0]['export_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'accepted exports'):
            self.normalize()
        self.data['plan']['selection'] = [['example', 'java']]
        with self.assertRaisesRegex(ValueError, 'complete matrix'):
            self.normalize()


if __name__ == '__main__':
    unittest.main()
