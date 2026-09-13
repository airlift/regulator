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


MODULE_PATH = Path(__file__).with_name("report_data.py")
SPEC = importlib.util.spec_from_file_location("report_data", MODULE_PATH)
report_data = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report_data)


class TestReportData(unittest.TestCase):
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
                manifest, path, data = report_data.validate_published_data()
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
                manifest, _, _ = report_data.validate_published_data()
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
                manifest, _, current = report_data.validate_published_data()
                self.assertEqual(2, manifest['reportSchemaVersion'])
                self.assertEqual(['candidate', 'previous'], [entry['candidate'] for entry in manifest['releases']])
                self.assertEqual(report, current)
                summary = report_data.summary_markdown(current)
                self.assertIn('| Java regex | 2.0× faster | — |', summary)
                self.assertIn('Joni', summary)
                self.assertIn('| Compared with | Everyday expressions | Text processing |', summary)
                self.assertIn('| Trino LIKE | 2.0× faster | — |', summary)

    def test_preliminary_summary_does_not_claim_one_measured_commit(self):
        report = report_fixture()
        report['sources']['currentLabel'] = '1.0 preliminary results'
        report['publication'] = {'status': 'preliminary', 'sourcePolicy': 'mixed-development-revisions',
                                 'note': 'Development builds with targeted updates.'}
        summary = report_data.summary_markdown(report)
        self.assertIn('1.0 preliminary results', summary)
        self.assertIn('per-row sources', summary)
        self.assertNotIn('`candidate`', summary)

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
                _, published, _ = report_data.validate_published_data()
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


if __name__ == "__main__":
    unittest.main()
