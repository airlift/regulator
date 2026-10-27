import argparse
import copy
import csv
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release_capture
# release_capture puts the baseline tooling on the path; its unit campaign fixture runs attempts as hosts do.
import aggregate_results
import collection
from test_unit_session import UnitCampaign


class TestReleaseCapture(unittest.TestCase):
    def setUp(self):
        self.release = release_capture.released_artifact.manifest(release_capture.ROOT, '1.0')
        self.collector = {'candidate_commit': 'c' * 40, 'root_tree': 'd' * 40, 'archive_sha256': 'e' * 64}
        part = {'id': 'case-0000-java', 'case': 'example', 'language': 'java'}
        allocation = release_capture.fleet.platform_allocation('language-bulk', 'r8i')
        jobs = [{'id': f'r8i/case-0000-java/replica-{replica}', 'platform': 'r8i', **allocation,
                 'partition': part['id'], 'replica': replica}
                for replica in (1, 2, 3)]
        self.manifest = {'suite': 'language-bulk', 'cases': [{'id': 'example', 'model': 'count'}]}
        platforms = release_capture.fleet.PLATFORMS
        self.data = {'plan': {'schema_version': 3, 'replicas': 3, 'platforms': list(platforms),
                              'platform_allocations': release_capture.fleet.platform_allocations('language-bulk', platforms),
                              'jobs': jobs, 'partitions': [part]},
                     'candidate': {'source_commit': self.collector['candidate_commit'], 'source_tree': self.collector['root_tree']},
                     'hosts': [], 'observations': [], 'comparisons': []}
        self.receipts = []
        jar = '/worker/regulator-1.0.jar'
        classes = release_capture.released_artifact.PUBLIC_CLASSES
        artifact = {'manifest': self.release, 'jar_path': jar,
                    'production_classes': ['io/airlift/regulator/' + name + '.class' for name in classes],
                    'code_sources': {'io.airlift.regulator.' + name: jar for name in classes},
                    'classpath': [{'path': jar, 'files': {'regulator-1.0.jar': self.release['jar_sha256']}}]}
        for job in jobs:
            instance = 'i-' + str(job['replica'])
            self.data['hosts'].append({'job': job, 'export_sha256': 'f' * 64,
                                      'provenance': {**self.data['candidate'],
                                                     'instance_identity': {'instanceId': instance,
                                                                           'instanceType': job['instance_type']},
                                                     'jvm_build': {'released_artifact': copy.deepcopy(artifact),
                                                                   'jvm': {'classpath': copy.deepcopy(artifact['classpath'])}}}})
            self.receipts.append({'plan_sha256': release_capture.digest_plan(self.data['plan']),
                                  'candidate_archive_sha256': self.collector['archive_sha256'],
                                  'candidate_commit': self.collector['candidate_commit'], 'released_artifact': self.release,
                                  'instance_id': instance, 'exports': {job['id']: 'f' * 64}})

    def normalize(self):
        return release_capture.normalize_language(self.data, self.manifest, self.release, self.collector, self.receipts)

    def refresh_receipts(self):
        plan_sha256 = release_capture.digest_plan(self.data['plan'])
        for host, receipt in zip(self.data['hosts'], self.receipts):
            receipt['plan_sha256'] = plan_sha256
            receipt['exports'] = {host['job']['id']: 'f' * 64}

    def test_released_artifact_and_collector_remain_separate(self):
        data = self.normalize()
        self.assertEqual(len(data['hosts']), 3)
        self.assertEqual({host['collector'] for host in data['hosts']}, {self.collector['candidate_commit']})
        self.assertNotEqual(self.collector['candidate_commit'], self.release['source_commit'])

    def test_lifecycle_r9g_uses_the_frozen_larger_allocation(self):
        self.manifest['suite'] = 'language-lifecycle'
        allocation = release_capture.fleet.platform_allocation('language-lifecycle', 'r9g')
        self.data['plan']['platform_allocations'] = release_capture.fleet.platform_allocations(
            'language-lifecycle', release_capture.fleet.PLATFORMS)
        for host in self.data['hosts']:
            job = host['job']
            job.update(id=job['id'].replace('r8i/', 'r9g/'), platform='r9g', **allocation)
            host['provenance']['instance_identity']['instanceType'] = allocation['instance_type']
        self.refresh_receipts()
        self.assertEqual(len(self.normalize()['hosts']), 3)

        self.data['hosts'][0]['provenance']['instance_identity']['instanceType'] = 'r9g.large'
        with self.assertRaisesRegex(ValueError, 'accepted instance'):
            self.normalize()

    def test_shared_language_ledger_routes_each_frozen_plan(self):
        other_plan = {**self.data['plan'], 'suite': 'language-lifecycle'}
        other_receipts = [{**receipt, 'plan_sha256': release_capture.digest_plan(other_plan)}
                          for receipt in self.receipts]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'accepted-language-sessions.json'
            path.write_text(json.dumps({'receipts': self.receipts + other_receipts}))
            ledgers = {}
            selected = release_capture.select_language_receipts(path, self.data['plan'], ledgers)
            normalized = release_capture.normalize_language(
                self.data, self.manifest, self.release, self.collector, selected)
            self.assertEqual(len(normalized['hosts']), 3)
            with self.assertRaisesRegex(ValueError, 'outside the imported captures'):
                release_capture.validate_language_receipt_coverage(ledgers)
            self.assertEqual(other_receipts, release_capture.select_language_receipts(path, other_plan, ledgers))
            release_capture.validate_language_receipt_coverage(ledgers)
            with self.assertRaisesRegex(ValueError, 'multiple captures'):
                release_capture.select_language_receipts(path, other_plan, ledgers)

    def test_shared_language_ledger_rejects_unknown_plan_receipts(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'accepted-language-sessions.json'
            unknown = {**self.receipts[0], 'plan_sha256': '0' * 64}
            path.write_text(json.dumps({'receipts': self.receipts + [unknown]}))
            ledgers = {}
            self.assertEqual(self.receipts, release_capture.select_language_receipts(path, self.data['plan'], ledgers))
            with self.assertRaisesRegex(ValueError, 'outside the imported captures'):
                release_capture.validate_language_receipt_coverage(ledgers)

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
                                                   identities[1], self.receipts)
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

    def test_rejects_missing_hosts_or_acceptance_receipts(self):
        self.data['hosts'].pop()
        with self.assertRaisesRegex(ValueError, 'missing or duplicate hosts'):
            self.normalize()
        self.setUp()
        self.receipts.pop()
        with self.assertRaisesRegex(ValueError, 'complete plan'):
            self.normalize()

    def test_rejects_changed_collector_archive(self):
        self.receipts[0]['candidate_archive_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'artifact or collector'):
            self.normalize()

    def test_rejects_mixed_jars_and_source_only_measurements(self):
        receipt = self.data['hosts'][0]['provenance']['jvm_build']
        receipt['released_artifact']['manifest'] = {**self.release, 'jar_sha256': '0' * 64}
        with self.assertRaisesRegex(ValueError, 'published release'):
            self.normalize()
        del receipt['released_artifact']
        with self.assertRaisesRegex(ValueError, 'published release'):
            self.normalize()

    def test_rejects_reused_replica_host_even_with_matching_ledger(self):
        self.data['hosts'][1]['provenance']['instance_identity']['instanceId'] = 'i-1'
        self.receipts[1]['instance_id'] = 'i-1'
        with self.assertRaisesRegex(ValueError, 'independent hosts'):
            self.normalize()

    def test_rejects_changed_export_or_previous_single_host_replacement(self):
        self.data['hosts'][0]['export_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'accepted instance or export'):
            self.normalize()
        self.data['plan']['replicas'] = 1
        with self.assertRaisesRegex(ValueError, 'three-host matrix'):
            self.normalize()


if __name__ == '__main__':
    unittest.main()
