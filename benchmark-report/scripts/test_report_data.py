import importlib.util
import copy
import gzip
from contextlib import contextmanager
import json
import math
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


from test_language_data import report_fixture
from build_capture_data import write_report
from language_data import CURRENT_PLATFORMS, LANGUAGES, RELEASE_1_0_LANGUAGES, RELEASE_1_0_PLATFORMS


MODULE_PATH = Path(__file__).with_name("report_data.py")
SPEC = importlib.util.spec_from_file_location("report_data", MODULE_PATH)
report_data = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report_data)


def prepare_publication(report, platforms=RELEASE_1_0_PLATFORMS):
    report['platforms'] = platforms
    for row in report['rows']:
        row['platform'] = 'r' + row['platform'][1:]
        result = row['result']
        if result['state'] != 'compared':
            continue
        result['estimator'] = 'mean'
        result['uncertainty'] = {
            'method': 'hierarchical-bootstrap-v1', 'level': .95, 'replicates': 4000,
            'hostCount': len(result['hosts']),
            'ratioInterval': [result['ratio'], result['ratio']],
            'candidateIntervalNs': [result['candidateNs'], result['candidateNs']],
            'comparatorIntervalNs': [result['comparatorNs'], result['comparatorNs']],
            'processCounts': {
                side: [len(host[side]['epochMeansNs']) for host in result['hosts']]
                for side in ('candidate', 'comparator')
            },
        }
    return report


class TestReportData(unittest.TestCase):
    def test_complete_output_is_validated_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'complete.json'
            report = report_fixture()
            report['rows'][0]['result']['uncertainty'] = {}
            with self.assertRaisesRegex(ValueError, 'uncertainty'):
                write_report(report, output)
            self.assertFalse(output.exists())

    def test_import_rejects_unsupported_or_missing_report_version_before_writing(self):
        for version in (1, 3, True, 2**53, None):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                report = report_fixture()
                if version is None:
                    del report['schemaVersion']
                else:
                    report['schemaVersion'] = version
                source = root / 'input.json'
                source.write_text(report_data.encode_json(report))
                with self.use_data_directory(root / 'published'):
                    with self.assertRaisesRegex(ValueError, 'schema'):
                        report_data.import_data(source)
                    self.assertFalse(report_data.DATA_DIRECTORY.exists())

    def test_build_rejects_unsupported_current_and_retained_report_versions(self):
        for field in ('reportSchemaVersion', 'retained'):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                manifest = {'schemaVersion': 1, 'reportSchemaVersion': 2, 'current': 'current.json',
                            'releases': [{'schemaVersion': 2, 'file': 'old.json'}]}
                if field == 'retained':
                    manifest['releases'][0]['schemaVersion'] = 1
                else:
                    manifest[field] = 1
                (root / 'manifest.json').write_text(json.dumps(manifest))
                result = subprocess.run(['node', str(MODULE_PATH.with_name('build.mjs')),
                                         '--data-directory', str(root), '--output-name', 'rejected-format'],
                                        capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Unsupported benchmark report manifest', result.stderr)

    def test_compressed_data_roundtrip_and_digest(self):
        encoded = '{"label":"Straße", "rows": []}\n'.encode()
        with tempfile.TemporaryDirectory() as directory:
            plain = Path(directory) / 'report.json'
            compressed = Path(directory) / 'report.json.gz'
            plain.write_bytes(encoded)
            compressed.write_bytes(gzip.compress(encoded, mtime=0))
            self.assertEqual(report_data.load_json(plain), report_data.load_json(compressed))
            self.assertEqual(report_data.data_digest(plain), report_data.data_digest(compressed))
            compressed.write_bytes(gzip.compress(b'{"value":NaN}', mtime=0))
            with self.assertRaisesRegex(ValueError, 'nonfinite JSON number'):
                report_data.load_json(compressed)

    def test_import_compresses_deterministically_and_enforces_size_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'input.json'
            source.write_text(report_data.encode_json(report_fixture()))
            with self.use_data_directory(root / 'published'):
                report_data.import_data(source)
                manifest, path, data, _ = report_data.validate_published_data()
                self.assertTrue(path.name.endswith('.json.gz'))
                first = path.read_bytes()
                report_data.import_data(path)
                self.assertEqual(first, path.read_bytes())
                self.assertEqual(manifest, report_data.load_manifest())
                self.assertEqual(data, report_fixture())
                with patch.object(report_data, 'MAX_STORED_DATA_BYTES', len(first)):
                    with self.assertRaisesRegex(ValueError, 'size limit'):
                        report_data.validate_published_data()
            with self.use_data_directory(root / 'too-large'):
                with patch.object(report_data, 'MAX_STORED_DATA_BYTES', 1):
                    with self.assertRaisesRegex(ValueError, 'size limit'):
                        report_data.import_data(source)
                self.assertFalse(report_data.DATA_DIRECTORY.exists())

    def test_import_accepts_only_whole_campaign_profiles(self):
        # Rust is measured only on R9g and R8i, and R8g only without Rust.
        for platforms, languages, error in ((CURRENT_PLATFORMS, LANGUAGES, None),
                                            (RELEASE_1_0_PLATFORMS, LANGUAGES, 'campaign profile'),
                                            (CURRENT_PLATFORMS, RELEASE_1_0_LANGUAGES, 'campaign profile')):
            with self.subTest(platforms=list(platforms), languages=list(languages)), \
                    tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = root / 'input.json'
                source.write_text(report_data.encode_json(report_fixture(platforms, languages)))
                with self.use_data_directory(root / 'published'):
                    if error is None:
                        report_data.import_data(source)
                        current = report_data.validate_published_data()[2]
                        self.assertEqual(report_fixture(platforms, languages), current)
                        self.assertIn('| Rust regex crate | 2.0× faster | — |', report_data.summary_markdown(current))
                    else:
                        with self.assertRaisesRegex(ValueError, error):
                            report_data.import_data(source)

    def test_import_does_not_duplicate_an_uncompressed_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = report_fixture()
            filename = self.write_report(root, report, report['sources']['currentCandidate'])
            manifest = {'schemaVersion': 1, 'reportSchemaVersion': 2, 'current': filename,
                        'releases': [{'schemaVersion': 2, 'candidate': 'fixture',
                                      'label': 'Fixture', 'file': filename}]}
            (root / 'manifest.json').write_text(json.dumps(manifest))
            with self.use_data_directory(root):
                report_data.import_data(root / filename)
                manifest, _, _, _ = report_data.validate_published_data()
                self.assertEqual([filename + '.gz'], [entry['file'] for entry in manifest['releases']])

    def test_build_preserves_current_and_historical_data(self):
        scripts = MODULE_PATH.parent
        output = scripts.parents[1] / 'target/benchmark-report/compression-fixture'
        report = report_fixture()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            current = self.write_report(root, report)
            historical = self.write_report(root, report_fixture(), 'historical')
            manifest = {
                'schemaVersion': 1, 'reportSchemaVersion': report['schemaVersion'],
                'current': current,
                'releases': [{'schemaVersion': 2, 'candidate': 'historical',
                              'label': 'Historical', 'file': historical}],
            }
            manifest_path = root / 'manifest.json'
            manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
            command = ['node', str(scripts / 'build.mjs'), '--data-directory', str(root),
                       '--output-name', 'compression-fixture']
            subprocess.run(command, check=True, capture_output=True)
            before = {path.relative_to(output): path.read_bytes()
                      for path in output.rglob('*') if path.is_file()}
            for filename in (current, historical):
                path = root / filename
                path.with_suffix('.json.gz').write_bytes(gzip.compress(path.read_bytes(), mtime=0))
                path.unlink()
            manifest['current'] += '.gz'
            manifest['releases'][0]['file'] += '.gz'
            manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
            subprocess.run(command, check=True, capture_output=True)
            after = {path.relative_to(output): path.read_bytes()
                     for path in output.rglob('*') if path.is_file()}
            self.assertEqual(before, after)
            with self.use_data_directory(root):
                report_data.validate_published_data()
                path = root / manifest['current']
                path.write_bytes(path.read_bytes()[:-8])
                with self.assertRaises(EOFError):
                    report_data.validate_published_data()

    @contextmanager
    def use_data_directory(self, data_directory):
        original_data_directory = report_data.DATA_DIRECTORY
        original_manifest = report_data.MANIFEST_FILE
        try:
            report_data.DATA_DIRECTORY = data_directory
            report_data.MANIFEST_FILE = data_directory / "manifest.json"
            yield
        finally:
            report_data.DATA_DIRECTORY = original_data_directory
            report_data.MANIFEST_FILE = original_manifest

    def write_report(self, data_directory, report, candidate="candidate"):
        encoded = (report_data.encode_json(report, indent=2, ensure_ascii=False) + "\n").encode()
        digest = report_data.hashlib.sha256(encoded).hexdigest()[:16]
        filename = f"regulator-{candidate}-{digest}.json"
        (data_directory / filename).write_bytes(encoded)
        return filename

    def test_load_json_rejects_nonfinite_constants(self):
        for token in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(token=token), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "report.json"
                path.write_text('{"nested":{"measurement":' + token + "}}")
                with self.assertRaisesRegex(ValueError, "nonfinite JSON number"):
                    report_data.load_json(path)

    def test_validate_report_rejects_nested_nonfinite_number(self):
        report = report_fixture()
        report['diagnostics'] = {'nestedMeasurement': math.nan}
        with self.assertRaisesRegex(ValueError, 'report data contains a nonfinite number'):
            report_data.validate_report(report)

    def test_released_publication_freezes_artifact_sources_and_grouping(self):
        release = {
            'schema_version': 1, 'group_id': 'io.airlift', 'artifact_id': 'regulator',
            'version': '1.0', 'source_commit': 'a' * 40, 'engine_tree': 'b' * 40,
            'jar_sha256': 'c' * 64,
        }
        source = report_fixture()
        source['rows'][0].update(population='ordinary-scalar', family='fixture',
                                 mapping={'status': 'translated', 'reason': 'expanded class', 'patternPreview': 'a', 'patternBytes': 1})
        current = prepare_publication(copy.deepcopy(source))
        # The frozen row records the source pattern's size; the current row records the measured translation's.
        current['rows'][0]['mapping']['patternBytes'] = 7000
        current['sources'].update(currentLabel='Regulator 1.0 results', currentCandidate=release['source_commit'],
                                  engineTree=release['engine_tree'], releaseVersion='1.0')
        current['provenance']['releasedArtifact'] = copy.deepcopy(release)
        current['publicationScope'] = {
            'policy': 'user-facing-release-v1', 'workloadSourceCandidate': 'source',
            'workloadSourceRows': len(current['rows']), 'completeCampaignRows': len(current['rows']),
            'publishedRows': len(current['rows']), 'excludedCampaignRows': 0,
        }
        with tempfile.TemporaryDirectory() as directory:
            source_path = Path(directory) / 'source.json'
            source_path.write_text(report_data.encode_json(source))
            current['publicationScope']['workloadSourceSha256'] = report_data.file_sha256(source_path)
            releases = [({'candidate': 'source'}, source_path, source)]
            with patch.object(report_data.released_artifact, 'manifest', return_value=release):
                report_data.validate_publication_scope(current, releases)
                legacy_result = copy.deepcopy(current)
                legacy_result['rows'][0]['originalResult'] = copy.deepcopy(source['rows'][0]['result'])
                report_data.validate_publication_scope(legacy_result, releases)
                for estimator, uncertainty in (
                        (None, None), ('median', {}), ('mean', None),
                        ('mean', {'method': 'other', 'level': .95}),
                        ('mean', {'method': 'hierarchical-bootstrap-v1', 'level': .90})):
                    changed = copy.deepcopy(current)
                    result = changed['rows'][0]['result']
                    if estimator is None:
                        result.pop('estimator')
                    else:
                        result['estimator'] = estimator
                    if uncertainty is None:
                        result.pop('uncertainty')
                    else:
                        result['uncertainty'] = uncertainty
                    with self.subTest(estimator=estimator, uncertainty=uncertainty), \
                            self.assertRaisesRegex(ValueError, 'mean with uncertainty'):
                        report_data.validate_publication_scope(changed, releases)
                legacy = copy.deepcopy(current)
                legacy['platforms'] = source['platforms']
                for row in legacy['rows']:
                    row['platform'] = 'c' + row['platform'][1:]
                with self.assertRaisesRegex(ValueError, 'platform set'):
                    report_data.validate_publication_scope(legacy, releases)
                legacy['platforms'] = CURRENT_PLATFORMS
                with self.assertRaisesRegex(ValueError, 'platform set'):
                    report_data.validate_publication_scope(legacy, releases)
                for field, value in (
                        ('id', 'changed'), ('population', 'changed'), ('family', 'changed'),
                        ('artifact.version', '1.1'), ('artifact.source_commit', 'd' * 40),
                        ('artifact.engine_tree', 'd' * 40), ('artifact.jar_sha256', 'd' * 64),
                        ('sources.currentCandidate', 'd' * 40), ('sources.engineTree', 'd' * 40)):
                    changed = copy.deepcopy(current)
                    if field.startswith('artifact.'):
                        changed['provenance']['releasedArtifact'][field.split('.', 1)[1]] = value
                    elif field.startswith('sources.'):
                        changed['sources'][field.split('.', 1)[1]] = value
                    else:
                        changed['rows'][0][field] = value
                    with self.subTest(field=field), self.assertRaises(ValueError):
                        report_data.validate_publication_scope(changed, releases)

                migrated = copy.deepcopy(current)
                previous = source['rows'][0]
                previous.update(id='like-lifecycle/ANY_ASCII/compile', language='like',
                                operation='compile', caseId='trino-like/ANY_ASCII')
                migrated['rows'][0].update(id='baseline/like-compile/ANY_ASCII', language='like',
                                           operation='compile', caseId='trino-like/ANY_ASCII')
                report_data.validate_publication_scope(migrated, releases)
                migrated['rows'][0]['id'] = 'baseline/like-compile/changed'
                with self.assertRaisesRegex(ValueError, 'id changed'):
                    report_data.validate_publication_scope(migrated, releases)

                previous.update(id='like-lifecycle/ANY_ASCII/optimized/singleUse',
                                operation='singleUse', caseId='trino-like/ANY_ASCII/optimized')
                migrated['rows'][0].update(id='baseline/like-dfa-single-use/ANY_ASCII/optimized',
                                           operation='singleUse', caseId='trino-like/ANY_ASCII/optimized')
                report_data.validate_publication_scope(migrated, releases)

    def test_later_release_validates_final_and_candidate_reports(self):
        release = {
            'schema_version': 1, 'group_id': 'io.airlift', 'artifact_id': 'regulator',
            'version': '2.0', 'source_commit': 'a' * 40, 'engine_tree': 'b' * 40,
            'jar_sha256': 'c' * 64,
        }
        final = prepare_publication(report_fixture(CURRENT_PLATFORMS, LANGUAGES), CURRENT_PLATFORMS)
        # A release campaign measures the whole LIKE lifecycle in its baseline.
        for row in final['rows']:
            if row['source'] == 'like-supplement':
                row['source'] = 'baseline'
        final['sources'].update(currentLabel='Regulator 2.0 results', currentCandidate=release['source_commit'],
                                engineTree=release['engine_tree'], releaseVersion='2.0')
        final['provenance'] = {'releasedArtifact': release,
                               'collectors': {'baseline': {'candidate_commit': release['source_commit'],
                                                           'engine_tree': release['engine_tree']}}}
        final['publicationScope'] = {
            'policy': 'user-facing-release-v2', 'completeCampaignRows': len(final['rows']) + 20,
            'publishedRows': len(final['rows']), 'excludedCampaignRows': 20,
            'internalEvidence': 'docs/benchmarks/results-2.0/raw-archives.json',
        }
        # A campaign of a candidate build before the release.
        preliminary = copy.deepcopy(final)
        del preliminary['provenance']['releasedArtifact']
        preliminary['sources']['currentLabel'] = 'Regulator 2.0 preliminary results'
        preliminary['publication'] = {'status': 'preliminary', 'sourcePolicy': 'candidate-build',
                                      'note': 'Candidate build.'}

        def unreleased(report):
            del report['sources']['releaseVersion'], report['publicationScope']

        cases = (
            ('final publication', final, lambda report: report.update(publication=preliminary['publication']),
             'must not carry publication metadata'),
            ('final 1.0 scope', final,
             lambda report: report['publicationScope'].update(policy='user-facing-release-v1'), 'publication scope'),
            ('final label', final,
             lambda report: report['sources'].update(currentLabel='Regulator 2.0 final results'), 'currentLabel'),
            ('final unreleased', final, unreleased, 'requires a release version'),
            ('candidate without publication', preliminary, lambda report: report.pop('publication'),
             'report without released-artifact provenance requires candidate-build publication metadata'),
            ('candidate mixed revisions', preliminary,
             lambda report: report['publication'].update(sourcePolicy='mixed-development-revisions'),
             'report without released-artifact provenance requires candidate-build publication metadata'),
            ('candidate label', preliminary,
             lambda report: report['sources'].update(currentLabel='Regulator 9.0 preliminary results'), 'currentLabel'),
            ('candidate without collectors', preliminary, lambda report: report['provenance'].pop('collectors'),
             'requires collector provenance'),
            ('candidate collector tree', preliminary,
             lambda report: report['provenance']['collectors']['baseline'].update(engine_tree='d' * 40),
             'collector engine tree'),
            # The report names a commit no collector built.
            ('candidate commit', preliminary, lambda report: report['sources'].update(currentCandidate='d' * 40),
             'collector commit'),
            ('candidate unreleased', preliminary, unreleased, 'requires a release version'),
        )
        with patch.object(report_data.released_artifact, 'manifest', return_value=release):
            report_data.validate_publication_scope(final, [])
            report_data.validate_publication_scope(preliminary, [])
            for name, report, change, message in cases:
                changed = copy.deepcopy(report)
                change(changed)
                with self.subTest(name), self.assertRaisesRegex(ValueError, message):
                    report_data.validate_publication_scope(changed, [])
            # The final report supersedes its preliminary one, which cannot then displace it.
            with tempfile.TemporaryDirectory() as directory, self.use_data_directory(Path(directory) / 'published'):
                root = Path(directory)
                for name, report in (('preliminary', preliminary), ('final', final)):
                    (root / f'{name}.json').write_text(report_data.encode_json(report))
                report_data.import_data(root / 'preliminary.json')
                report_data.import_data(root / 'final.json')
                manifest = report_data.MANIFEST_FILE.read_bytes()
                self.assertEqual(['Regulator 2.0 results'],
                                 [entry['label'] for entry in json.loads(manifest)['releases']])
                # Nor can a preliminary report of another candidate build of the same release.
                other = copy.deepcopy(preliminary)
                other['sources']['currentCandidate'] = 'e' * 40
                other['provenance']['collectors']['baseline']['candidate_commit'] = 'e' * 40
                (root / 'other.json').write_text(report_data.encode_json(other))
                for name in ('preliminary', 'other'):
                    with self.subTest(name), self.assertRaisesRegex(ValueError, 'released-artifact report'):
                        report_data.import_data(root / f'{name}.json')
                    self.assertEqual(manifest, report_data.MANIFEST_FILE.read_bytes())
                # Import refuses a publication that carries a hidden row.
                probe = copy.deepcopy(final)
                probe['rows'][0]['family'] = 'optimizer-probe'
                (root / 'probe.json').write_text(report_data.encode_json(probe))
                with self.assertRaisesRegex(ValueError, 'hidden row'):
                    report_data.import_data(root / 'probe.json')
                self.assertEqual(manifest, report_data.MANIFEST_FILE.read_bytes())
                # Hiding a stored workload leaves the stored report valid, and the publication regenerated
                # without it imports.
                extended = copy.deepcopy(final)
                extended['rows'] += [dict(copy.deepcopy(row), id='fixture/probe') for row in final['rows']
                                     if row['id'] == 'fixture/execute']
                extended['publicationScope'].update(publishedRows=len(extended['rows']),
                                                    excludedCampaignRows=len(final['rows']) + 20 - len(extended['rows']))
                (root / 'extended.json').write_text(report_data.encode_json(extended))
                report_data.import_data(root / 'extended.json')
                with patch.object(report_data.publication_data, 'hidden', lambda row: row['id'] == 'fixture/probe'):
                    report_data.validate_published_data()
                    report_data.import_data(root / 'final.json')
                    self.assertEqual(final, report_data.validate_published_data()[2])

    def test_report_serialization_rejects_nonfinite_number(self):
        with self.assertRaises(ValueError):
            report_data.encode_json({"nested": {"measurement": math.inf}})

        self.assertEqual('{"measurement": 1.5}', report_data.encode_json({"measurement": 1.5}))

    def test_current_report_has_valid_runtime_schema(self):
        report_data.validate_report(report_data.load_json(report_data.current_data_file()))

    def test_import_retains_and_validates_previous_release(self):
        previous = report_fixture()
        previous['sources'].update(currentCandidate='previous', currentLabel='Previous fixture')
        report = report_fixture()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            previous_path = root / 'previous.json'
            previous_path.write_text(report_data.encode_json(previous))
            current_path = root / 'current.json'
            current_path.write_text(report_data.encode_json(report))
            with self.use_data_directory(root / 'published'):
                report_data.import_data(previous_path)
                report_data.import_data(current_path)
                manifest, _, current, _ = report_data.validate_published_data()
                self.assertEqual(2, manifest['reportSchemaVersion'])
                self.assertEqual(['candidate', 'previous'], [entry['candidate'] for entry in manifest['releases']])
                self.assertEqual(report, current)
                summary = report_data.summary_markdown(current)
                self.assertIn('| Java regex | 2.0× faster | — |', summary)
                self.assertIn('Joni', summary)
                self.assertIn('| Compared with | Everyday expressions | Text processing |', summary)
                self.assertIn('| Trino LIKE | 2.0× faster | — |', summary)

    def test_preliminary_summary_names_one_measured_commit_only_for_a_candidate_build(self):
        report = report_fixture()
        report['sources']['currentLabel'] = '1.0 preliminary results'
        report['publication'] = {'status': 'preliminary', 'sourcePolicy': 'mixed-development-revisions',
                                 'note': 'Development builds with targeted updates.'}
        summary = report_data.summary_markdown(report)
        self.assertIn('1.0 preliminary results', summary)
        self.assertIn('per-row sources', summary)
        self.assertNotIn('`candidate`', summary)
        # A candidate build's preliminary results name the one commit they measured.
        report['sources']['currentLabel'] = 'Regulator 2.0 preliminary results'
        report['publication']['sourcePolicy'] = 'candidate-build'
        summary = report_data.summary_markdown(report)
        self.assertIn('_Source: Regulator 2.0 preliminary results `candidate`.', summary)

    def test_import_preserves_javascript_integer_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = report_fixture()
            report["rows"][0]["inputBytes"] = 2**53 - 1
            report["rows"][1]["inputBytes"] = 0
            source = root / "input.json"
            source.write_text(report_data.encode_json(report))
            with self.use_data_directory(root / "published"):
                report_data.import_data(source)
                _, published, _, _ = report_data.validate_published_data()
                subprocess.run([
                    "node", "-e",
                    "const data = JSON.parse(require('zlib').gunzipSync(require('fs').readFileSync(process.argv[1])).toString('utf8'));"
                    "if (data.rows[0].inputBytes !== Number.MAX_SAFE_INTEGER ||"
                    "data.rows[1].inputBytes !== 0) process.exit(1);",
                    str(published),
                ], check=True)

    def test_validate_report_rejects_duplicate_rendered_identities(self):
        report = report_fixture()
        report['rows'].append(copy.deepcopy(report['rows'][0]))
        with self.assertRaisesRegex(ValueError, 'duplicate report row'):
            report_data.validate_report(report)

    def test_validate_report_allows_nullable_optional_measurements(self):
        report = report_fixture()
        report['rows'][0]['result']['deltaNsPerByte'] = None
        report_data.validate_report(report)

    def test_validate_report_rejects_missing_summary_inputs(self):
        report = report_fixture()
        report['rows'] = [row for row in report['rows'] if row['language'] != 'like']
        with self.assertRaisesRegex(ValueError, 'coverage'):
            report_data.validate_report(report)

    def test_import_rejects_malformed_report_before_writing(self):
        for mutation, error in (
                (lambda data: data.update(provenance={}), 'provenance'),
                (lambda data: data['rows'][0]['result'].update(ratio=True), 'ratio'),
                (lambda data: data['rows'].pop(), 'coverage')):
            with self.subTest(error=error), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                report = report_fixture()
                mutation(report)
                source = root / 'invalid.json'
                source.write_text(report_data.encode_json(report))
                with self.use_data_directory(root / 'data'):
                    with self.assertRaisesRegex(ValueError, error):
                        report_data.import_data(source)
                    self.assertFalse(report_data.DATA_DIRECTORY.exists())

    def test_manifest_rejects_platform_dependent_filenames(self):
        for field, filename in (("current", "current\\report.json"), ("release", "old\\report.json")):
            with self.subTest(field=field):
                manifest = {
                    "schemaVersion": 1,
                    "reportSchemaVersion": 2,
                    "current": "current.json",
                    "releases": [{
                        "schemaVersion": 2,
                        "candidate": "candidate",
                        "label": "Candidate",
                        "file": "old.json",
                    }],
                }
                if field == "current":
                    manifest["current"] = filename
                else:
                    manifest["releases"][0]["file"] = filename
                with self.assertRaisesRegex(ValueError, "data filename"):
                    report_data.validate_manifest(manifest)

    def test_check_rejects_malformed_historical_report(self):
        report = report_fixture()
        malformed_report = copy.deepcopy(report)
        malformed_report["provenance"] = {}
        with tempfile.TemporaryDirectory() as directory:
            data_directory = Path(directory)
            current = self.write_report(data_directory, report, "current")
            historical = self.write_report(data_directory, malformed_report, "historical")
            manifest = {
                "schemaVersion": 1,
                "reportSchemaVersion": 2,
                "current": current,
                "releases": [
                    {
                        "schemaVersion": 2,
                        "candidate": "historical",
                        "label": "Historical",
                        "file": historical,
                    },
                ],
            }
            (data_directory / "manifest.json").write_text(report_data.encode_json(manifest))
            with self.use_data_directory(data_directory):
                with self.assertRaisesRegex(ValueError, "provenance"):
                    report_data.validate_published_data()

    def test_historical_report_validation_is_versioned(self):
        report = report_fixture()
        with tempfile.TemporaryDirectory() as directory:
            data_directory = Path(directory)
            current = self.write_report(data_directory, report, "current")
            manifest = {
                "schemaVersion": 1,
                "reportSchemaVersion": 2,
                "current": current,
                "releases": [{
                    "schemaVersion": 3,
                    "candidate": "current",
                    "label": "Current",
                    "file": current,
                }],
            }
            (data_directory / "manifest.json").write_text(report_data.encode_json(manifest))
            with self.use_data_directory(data_directory):
                with self.assertRaisesRegex(ValueError, "unsupported report schema"):
                    report_data.validate_published_data()

    def test_import_rejects_malformed_existing_manifest_without_mutation(self):
        report = report_fixture()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data_directory = root / "data"
            data_directory.mkdir()
            manifest_file = data_directory / "manifest.json"
            original_manifest = '{"schemaVersion":1,"releases":[null]}\n'
            manifest_file.write_text(original_manifest)
            source = root / "source.json"
            source.write_text(report_data.encode_json(report))
            original_files = sorted(path.name for path in data_directory.iterdir())

            with self.use_data_directory(data_directory):
                with self.assertRaises(ValueError):
                    report_data.import_data(source)

            self.assertEqual(original_manifest, manifest_file.read_text())
            self.assertEqual(original_files, sorted(path.name for path in data_directory.iterdir()))

    def test_import_preserves_unknown_report_fields(self):
        report = report_fixture()
        report["producerExtension"] = {"nullable": None, "value": 42}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.json"
            source.write_text(report_data.encode_json(report))
            data_directory = root / "data"
            with self.use_data_directory(data_directory):
                report_data.import_data(source)
                imported = report_data.load_json(report_data.current_data_file())
            self.assertEqual({"nullable": None, "value": 42}, imported["producerExtension"])

    def test_released_publication_is_pinned_to_user_facing_workloads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workloads = report_fixture()
            workloads['sources'].update(currentCandidate='workloads', currentLabel='Preliminary workloads')
            source_name = self.write_report(root, workloads, 'workloads')
            source_path = root / source_name
            report = prepare_publication(copy.deepcopy(workloads))
            release = report_data.released_artifact.manifest(report_data.REPOSITORY_ROOT, '1.0')
            report['sources'].update(currentCandidate=release['source_commit'], engineTree=release['engine_tree'],
                                     currentLabel='Regulator 1.0 results', releaseVersion='1.0')
            report['provenance']['releasedArtifact'] = release
            report['publicationScope'] = {
                'policy': 'user-facing-release-v1',
                'workloadSourceCandidate': 'workloads',
                'workloadSourceSha256': report_data.file_sha256(source_path),
                'workloadSourceRows': len(workloads['rows']),
                'completeCampaignRows': len(workloads['rows']) + 10,
                'publishedRows': len(workloads['rows']),
                'excludedCampaignRows': 10,
                'internalEvidence': 'private archive',
            }
            releases = [({'candidate': 'workloads'}, source_path, workloads)]
            report_data.validate_publication_scope(report, releases)
            report['rows'].append(copy.deepcopy(report['rows'][0]))
            with self.assertRaisesRegex(ValueError, 'row counts'):
                report_data.validate_publication_scope(report, releases)

    def test_released_publication_rejects_extra_or_changed_workloads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workloads = report_fixture()
            workloads['sources'].update(currentCandidate='workloads', currentLabel='Preliminary workloads')
            source_name = self.write_report(root, workloads, 'workloads')
            source_path = root / source_name
            report = prepare_publication(copy.deepcopy(workloads))
            release = report_data.released_artifact.manifest(report_data.REPOSITORY_ROOT, '1.0')
            report['sources'].update(currentCandidate=release['source_commit'], engineTree=release['engine_tree'],
                                     currentLabel='Regulator 1.0 results', releaseVersion='1.0')
            report['provenance']['releasedArtifact'] = release

            def scope(rows):
                return {
                    'policy': 'user-facing-release-v1',
                    'workloadSourceCandidate': 'workloads',
                    'workloadSourceSha256': report_data.file_sha256(source_path),
                    'workloadSourceRows': len(workloads['rows']),
                    'completeCampaignRows': rows + 10,
                    'publishedRows': rows,
                    'excludedCampaignRows': 10,
                    'internalEvidence': 'private archive',
                }

            releases = [({'candidate': 'workloads'}, source_path, workloads)]
            valid_rows = copy.deepcopy(report['rows'])
            report['rows'].append(copy.deepcopy(report['rows'][0]))
            report['publicationScope'] = scope(len(report['rows']))
            with self.assertRaisesRegex(ValueError, 'row counts'):
                report_data.validate_publication_scope(report, releases)

            report['rows'] = valid_rows
            report['publicationScope'] = scope(len(report['rows']))
            report['rows'][0]['name'] = 'changed public workload'
            with self.assertRaisesRegex(ValueError, 'name changed'):
                report_data.validate_publication_scope(report, releases)

    def test_released_publication_rejects_missing_or_wrong_source_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workloads = report_fixture()
            workloads['sources'].update(currentCandidate='workloads', currentLabel='Preliminary workloads')
            source_name = self.write_report(root, workloads, 'workloads')
            source_path = root / source_name
            report = prepare_publication(copy.deepcopy(workloads))
            release = report_data.released_artifact.manifest(report_data.REPOSITORY_ROOT, '1.0')
            report['sources'].update(currentCandidate=release['source_commit'], engineTree=release['engine_tree'],
                                     currentLabel='Regulator 1.0 results', releaseVersion='1.0')
            report['provenance']['releasedArtifact'] = release
            report['publicationScope'] = {
                'policy': 'user-facing-release-v1',
                'workloadSourceCandidate': 'workloads',
                'workloadSourceSha256': '0' * 64,
                'workloadSourceRows': len(workloads['rows']),
                'completeCampaignRows': len(workloads['rows']),
                'publishedRows': len(workloads['rows']),
                'excludedCampaignRows': 0,
                'internalEvidence': 'private archive',
            }
            with self.assertRaisesRegex(ValueError, 'source is missing'):
                report_data.validate_publication_scope(report, [])
            with self.assertRaisesRegex(ValueError, 'source is missing'):
                report_data.validate_publication_scope(
                    report, [({'candidate': 'workloads'}, source_path, workloads)])


if __name__ == "__main__":
    unittest.main()
