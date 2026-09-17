import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

from build_capture_data import validate_released_campaign_paths


class TestBuildCaptureData(unittest.TestCase):
    def arguments(self, root):
        names = ('released_campaign', 'publication_workloads', 'measurement_followup',
                 'analysis_inputs', 'output', 'complete_output', 'publication_analysis_output')
        return SimpleNamespace(**{name: root / name for name in names})

    def test_released_campaign_outputs_are_isolated_from_every_input_and_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            arguments = self.arguments(root)
            validate_released_campaign_paths(arguments)
            outputs = ('output', 'complete_output', 'publication_analysis_output')
            inputs = ('released_campaign', 'publication_workloads', 'measurement_followup', 'analysis_inputs')
            for output in outputs:
                for other in (*outputs, *inputs):
                    if output == other:
                        continue
                    changed = self.arguments(root)
                    setattr(changed, output, getattr(changed, other))
                    with self.subTest(output=output, other=other), self.assertRaisesRegex(
                            ValueError, 'must differ'):
                        validate_released_campaign_paths(changed)

    def test_released_campaign_outputs_reject_symlink_and_hardlink_aliases(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for alias in ('symlink', 'hardlink'):
                arguments = self.arguments(root)
                arguments.analysis_inputs.write_text('evidence')
                path = root / alias
                if alias == 'symlink':
                    path.symlink_to(arguments.analysis_inputs)
                else:
                    os.link(arguments.analysis_inputs, path)
                arguments.output = path
                with self.subTest(alias=alias), self.assertRaisesRegex(ValueError, 'must differ'):
                    validate_released_campaign_paths(arguments)

    def test_released_campaign_outputs_reject_referenced_evidence_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            arguments = self.arguments(root)
            evidence = root / 'evidence'
            (evidence / 'language').mkdir(parents=True)
            (evidence / 'primary').mkdir()
            export = evidence / 'language/export.json'
            export.write_text('raw export')
            candidate = evidence / 'candidate.tsv'
            candidate.write_text('collector')
            arguments.released_campaign.write_text(json.dumps({
                'candidate_provenance': str(candidate),
                'language': [{'plan': str(evidence / 'language'), 'export': str(export)}],
                'baseline': {'primary': str(evidence / 'primary'),
                             'reduction': str(evidence / 'reduction')},
            }))

            for output in (export, evidence / 'primary/accepted-sessions.tsv'):
                changed = self.arguments(root)
                changed.output = output
                with self.subTest(output=output), self.assertRaisesRegex(ValueError, 'must differ'):
                    validate_released_campaign_paths(changed)
            self.assertEqual('raw export', export.read_text())
            self.assertEqual('collector', candidate.read_text())


if __name__ == '__main__':
    unittest.main()
