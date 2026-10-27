import gzip
import json
from pathlib import Path
import tempfile
import unittest

import measurement_followup


class TestMeasurementFollowup(unittest.TestCase):
    def test_projects_public_inputs_deterministically(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'inputs.jsonl.gz'
            with path.open('wb') as destination, gzip.GzipFile(
                    fileobj=destination, mode='wb', filename='', mtime=0) as output:
                for identity in ('row|java|r9g|native', 'other|java|r9g|native'):
                    output.write((json.dumps({
                        'identity': identity, 'estimator': 'mean',
                        'hosts': [{'instanceId': 'host-1', 'candidate': {'meanNs': 10.0},
                                   'comparator': {'meanNs': 20.0}}],
                    }) + '\n').encode())
            entries = measurement_followup.analysis_entries(path)
            projected = measurement_followup.projected_analysis_bytes(entries, {'row|java|r9g|native'})
            self.assertEqual(projected, measurement_followup.projected_analysis_bytes(entries, ['row|java|r9g|native']))


if __name__ == '__main__':
    unittest.main()
