import argparse
import copy
import csv
import hashlib
import json
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
from test_unit_session import UnitCampaign, UnitFixture


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
        self.units = {'unit': {'unit_id': 'unit', 'owned': [], 'groups': [{'group_id': '1', 'owned': []}]}}
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
            receipt = {'unit_attempt_id': f'host-{replica}/1', 'group_id': '1', 'platform': 'r8i', 'unit_id': 'unit',
                       'replica_id': str(replica), 'instance_id': f'i-{replica}', 'instance_type': instance_type,
                       'observed_rows_sha256': exports, 'engine_tree': self.release['engine_tree'],
                       'candidate_commit': self.collector['candidate_commit'],
                       'candidate_archive_sha256': self.collector['archive_sha256']}
            self.receipts.append(receipt)
            provenance = {**self.data['candidate'],
                          'instance_identity': {'instanceId': receipt['instance_id'], 'instanceType': instance_type},
                          'jvm_build': {'released_artifact': copy.deepcopy(artifact),
                                        'jvm': {'classpath': [jar]}}}
            self.data['hosts'].append({'receipt': dict(receipt), 'partitions': [
                {'id': part['id'], 'export_sha256': 'f' * 64, 'provenance': provenance}]})

    def normalize(self, released=True):
        # A candidate-build campaign measures the release's tree without its JAR.
        return release_capture.normalize_language(self.data, self.manifest, self.release if released else None,
                                                  self.release['engine_tree'], self.collector, self.receipts, self.units)

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
            tree = self.release['engine_tree']
            self.assertEqual(identities[0], release_capture.load_collector({}, plan, tree))
            self.assertEqual(identities[1], release_capture.load_collector(
                {'candidate_provenance': str(paths[1])}, plan, tree))
            with self.assertRaisesRegex(ValueError, 'changed the collector identity'):
                release_capture.normalize_language(self.data, self.manifest, self.release, tree,
                                                   identities[1], self.receipts, self.units)
            with self.assertRaisesRegex(ValueError, 'production tree'):
                release_capture.load_collector({}, plan, '0' * 40)
            with self.assertRaisesRegex(ValueError, 'pinned collector'):
                release_capture.load_collector({}, {}, tree)
            with self.assertRaises(FileNotFoundError):
                release_capture.load_collector({'candidate_provenance': str(paths[0]) + '.missing'},
                                               plan, tree)

    def test_baseline_accepts_only_a_complete_unit_campaign_of_its_collector(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shards = [row['shard_id'] for row in release_capture.read_rows(release_capture.BASELINE / 'shards.tsv')]
            # Replicas 1 to 3 of every unit on both platforms, each worker running every unit of its replica. The
            # host check flags r8i's third host for its CPU steal, so a fourth host confirms every unit it ran.
            # Regulator runs 1.5 times slower on the flagged host and 1.04 times slower on the fourth.
            campaign = UnitCampaign(
                root / 'results', *shards,
                score=lambda row, platform, replica: {3: 1.5, 4: 1.04}.get(replica, 1) if (
                    platform == 'r8i' and row['system'].startswith('regulator')) else 1,
                steal=lambda platform, host: 20 if (platform, host) == ('r8i', 3) else 0)
            receipts = campaign.receipts

            def phase(name, ledger, rejected=()):
                # The controller keeps each phase's semantic-rejection ledger beside its accepted units.
                (root / name).mkdir()
                with (root / name / release_capture.unit_session.SEMANTIC_REJECTIONS).open('w') as output:
                    fields = release_capture.unit_session.SEMANTIC_REJECTION_FIELDS
                    writer = csv.DictWriter(output, fieldnames=fields, delimiter='\t', extrasaction='ignore')
                    writer.writeheader()
                    writer.writerows(rejected)
                return campaign.write_ledger(root / name / 'accepted-units.tsv', ledger)

            primary = phase('primary', receipts)

            def reduce(name, release_version='', confirmations=(), primary=primary, campaign=campaign):
                aggregate_results.aggregate(argparse.Namespace(
                    manifest=release_capture.BASELINE / 'rows.tsv', platforms=release_capture.BASELINE / 'platforms.tsv',
                    shards=release_capture.BASELINE / 'shards.tsv', primary_receipts=[primary],
                    confirmation_receipts=list(confirmations), artifacts_root=[campaign.root], session_dir=[], events=[],
                    artifact_index=[], campaign_artifact=None, output_dir=root / name,
                    expected_manifest_rows=aggregate_results.parser().get_default('expected_manifest_rows'),
                    release_version=release_version))
                return str(root / name)

            # Reduced before its confirmation, so the reduction leaves the flagged host's comparisons unresolved.
            unconfirmed = reduce('unconfirmed')
            confirmed = [receipt for unit in campaign.units for receipt in campaign.add('r8i', unit, 4, host=4)]
            relabelled = [{**receipt, 'candidate_commit': 'f' * 40} for receipt in receipts + confirmed]
            confirmation = phase('confirmation', confirmed)
            phase('partial', receipts[1:])
            phase('rejected', receipts, rejected=receipts[:1])
            phase('relabelled', relabelled[:len(receipts)])
            phase('relabelled-confirmation', relabelled[len(receipts):])
            # A confirmation ledger in an older receipt schema, with a column the current one lacks.
            (root / 'stale').mkdir()
            with (root / 'stale/accepted-units.tsv').open('w') as output:
                fields = [*release_capture.unit_session.UNIT_RECEIPT_FIELDS, 'copied_row_count']
                writer = csv.DictWriter(output, fieldnames=fields, delimiter='\t')
                writer.writeheader()
                writer.writerows({**receipt, 'copied_row_count': '0'} for receipt in confirmed)
            # The fixture's attempts carry no release JAR; released_artifact's own tests cover the reducer's proof.
            with patch.object(release_capture.released_artifact, 'validate_baseline'):
                reduction = reduce('reduction', '1.0', [confirmation])
            unproven = reduce('unproven', confirmations=[confirmation])
            # A release campaign's routes record the release they loaded, as run-shard.sh does, but reduced without
            # its release the summary names none.
            write_metadata = UnitFixture.write_metadata

            def write_release_metadata(fixture, directory, representative_count):
                write_metadata(fixture, directory, representative_count)
                with (directory / 'run-metadata.txt').open('a') as metadata:
                    metadata.write(f"release_version=1.0\nrelease_artifact_sha256={'0' * 64}\n")

            with patch.object(UnitFixture, 'write_metadata', write_release_metadata):
                released = UnitCampaign(root / 'released-results', *shards)
            released_reduction = reduce('released-reduction', primary=phase('released', released.receipts),
                                        campaign=released)
            baseline = {'primary': str(root / 'primary'), 'confirmation': str(root / 'confirmation'),
                        'reduction': reduction, 'campaign_id': 'campaign'}
            tree = confirmed[0]['engine_tree']
            release = {'version': '1.0', 'engine_tree': tree}
            collector = {'candidate_commit': confirmed[0]['candidate_commit'],
                         'archive_sha256': confirmed[0]['candidate_archive_sha256'],
                         'platform_manifest_sha256': release_capture.digest(release_capture.BASELINE / 'platforms.tsv')}
            release_capture.validate_baseline(baseline, release, tree, collector)
            # A candidate-build campaign's reduction proves no release.
            release_capture.validate_baseline({**baseline, 'reduction': unproven}, None, tree, collector)
            lifecycle = collection.prepare(root / 'lifecycle')
            rows = release_capture.baseline_rows(reduction, lifecycle)
            # Every population imports on both platforms, though each worker ran several units of a shard.
            populations = ('trino-operations', 'like', 'specialized-patterns', 'diagnostics-and-stress')
            self.assertEqual({(platform, population) for platform in ('r8i', 'r9g') for population in populations},
                             {(row['platform'], row['population']) for row in rows})
            # Without the flagged host, each comparison's ratio and hosts are the reduction's.
            self.assertEqual({(row['platform'], float(row['median_ratio']), int(row['host_count']))
                              for row in release_capture.read_rows(Path(reduction) / 'comparison-aggregates.tsv')},
                             {(row['platform'], row['result']['ratio'], len(row['result']['hosts'])) for row in rows})
            # The flagged host measured none of them, and the fourth host stands in for it on r8i.
            instances = {(row['platform'], host['instanceId']) for row in rows for host in row['result']['hosts']}
            self.assertNotIn(('r8i', 'i-r8i-3'), instances)
            self.assertIn(('r8i', 'i-r8i-4'), instances)
            # Before its confirmation, a comparison the flagged host measured has two hosts left and is refused.
            with self.assertRaisesRegex(ValueError, r"fewer than three hosts in r8i/.*; each needs its unit's confirmation"):
                release_capture.baseline_rows(unconfirmed, lifecycle)
            cases = (
                ({}, '0' * 40, release, {}, 'changed the measured build or collector'),
                ({}, tree, release, {'candidate_commit': '0' * 40}, 'changed the measured build or collector'),
                ({}, tree, release, {'archive_sha256': '0' * 64}, 'changed the measured build or collector'),
                ({'primary': str(root / 'partial')}, tree, release, {}, 'unit coverage failed'),
                ({'primary': str(root / 'absent')}, tree, release, {}, 'cannot read'),
                ({'confirmation': str(root / 'absent')}, tree, release, {}, 'cannot read'),
                ({'confirmation': str(root / 'stale')}, tree, release, {}, 'columns must be'),
                ({'primary': str(root / 'rejected')}, tree, release, {}, 'semantic failure'),
                ({'reduction': unproven}, tree, release, {}, 'did not prove'),
                ({}, tree, None, {}, 'candidate baseline summary requires release_version to be an empty string'),
                ({'primary': str(root / 'released'), 'confirmation': None, 'reduction': released_reduction}, tree, None,
                 {}, 'candidate baseline attempts contain recorded release metadata'),
                # Receipts relabelled to another candidate, against the reduction of this one.
                ({'primary': str(root / 'relabelled'), 'confirmation': str(root / 'relabelled-confirmation')}, tree,
                 release, {'candidate_commit': 'f' * 40}, 'changed the accepted unit attempts'),
            )
            for paths, engine_tree, measured_release, pin, message in cases:
                with self.subTest(paths=paths, engine_tree=engine_tree, release=measured_release, pin=pin), \
                        self.assertRaisesRegex(ValueError, message):
                    release_capture.validate_baseline({**baseline, **paths}, measured_release, engine_tree,
                                                      {**collector, **pin})

    def test_build_refuses_a_campaign_it_cannot_attribute(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases = (
                # Refused before reading the campaign's reduction, which this one lacks.
                ('v2.0', None, 'not a release version'),
                ('1.0', '', 'only its released-artifact report'),
                # A candidate build without a pinned baseline collector.
                ('2.0', '', 'pinned collector'),
                ('2.1', '2.0', 'different release'),
            )
            for version, proven, message in cases:
                reduction = root / f'reduction-{proven}'
                if proven is not None:
                    reduction.mkdir(exist_ok=True)
                    (reduction / 'summary.json').write_text(json.dumps({'release_version': proven}))
                plan = root / 'plan.json'
                plan.write_text(json.dumps({'release_version': version, 'baseline': {'reduction': str(reduction)}}))
                with self.subTest(version=version, proven=proven), self.assertRaisesRegex(ValueError, message):
                    release_capture.build(plan)

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
            target = next(host['receipt'] for host in data['hosts']
                          if host['receipt']['instance_id'] == source['instance_id'] and
                          host['receipt']['unit_id'] != source['unit_id'])
            row.update(unit_attempt_id=target['unit_attempt_id'], group_id=target['group_id'])

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
                normalized = release_capture.normalize_language(data, manifest, release, release['engine_tree'],
                                                                collector, accepted, units)
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
            ('row moved between units', items, receipts, [], "differ from their accepted groups' rows"),
        )
        for name, imported_items, ledger, rejections, message in cases:
            release_capture.fleet.write_tsv(result_root / 'accepted-units.tsv', session.UNIT_RECEIPT_FIELDS, ledger)
            release_capture.fleet.write_tsv(result_root / session.SEMANTIC_REJECTIONS,
                                            session.SEMANTIC_REJECTION_FIELDS, rejections)
            change = moved_row if name == 'row moved between units' else lambda data: None
            with self.subTest(name), patch.object(release_capture.released_artifact, 'validate_saved'):
                if message is None:
                    # Every row is its accepted group's: that group's row of the plan, on that host and replica.
                    for shard, accepted, units, rows in imported(imported_items, change):
                        attempts = {(receipt['unit_attempt_id'], receipt['group_id']): receipt for receipt in accepted}

                        def owned(receipt):
                            return release_capture.work_units.unit_group(units[receipt['unit_id']],
                                                                         receipt['group_id'])['owned']

                        self.assertEqual(len(rows), sum(len(owned(receipt)) for receipt in accepted))
                        for row in rows:
                            receipt = attempts[row['unit_attempt_id'], row['group_id']]
                            case, language, _ = release_capture.fleet.row_atom(row['id'])
                            self.assertEqual(receipt['shard_id'], shard)
                            self.assertIn(row['id'], owned(receipt))
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
        with self.assertRaisesRegex(ValueError, 'measured build or collector'):
            self.normalize()

    def test_rejects_mixed_jars_and_source_only_measurements(self):
        jvm_build = self.data['hosts'][0]['partitions'][0]['provenance']['jvm_build']
        jvm_build['released_artifact']['manifest'] = {**self.release, 'jar_sha256': '0' * 64}
        with self.assertRaisesRegex(ValueError, 'published release'):
            self.normalize()
        del jvm_build['released_artifact']
        with self.assertRaisesRegex(ValueError, 'published release'):
            self.normalize()

    def test_candidate_build_hosts_carry_no_released_artifact(self):
        for host in self.data['hosts'][:-1]:
            del host['partitions'][0]['provenance']['jvm_build']['released_artifact']
        with self.assertRaisesRegex(ValueError, 'released-artifact host'):
            self.normalize(released=False)
        del self.data['hosts'][-1]['partitions'][0]['provenance']['jvm_build']['released_artifact']
        self.assertEqual(len(self.normalize(released=False)['hosts']), 3)

    def test_rejects_changed_export_or_scoped_plan(self):
        self.data['hosts'][0]['partitions'][0]['export_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'accepted exports'):
            self.normalize()
        self.data['plan']['selection'] = [['example', 'java']]
        with self.assertRaisesRegex(ValueError, 'complete matrix'):
            self.normalize()


if __name__ == '__main__':
    unittest.main()
