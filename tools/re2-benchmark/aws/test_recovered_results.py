import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


DIRECTORY = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('recovery', DIRECTORY / 'verify-recovered-results.py')
RECOVERY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECOVERY)


class TestRecoveredResults(unittest.TestCase):
    def test_inventory_requires_every_uploaded_byte(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            objects = root / 'objects'
            objects.mkdir()
            (objects / 'arm.tar.gz').write_bytes(b'raw')
            inventory = {'Contents': [{'Key': 'campaign/results/arm.tar.gz', 'Size': 3}]}
            (root / 'inventory.json').write_text(json.dumps(inventory))
            receipt = RECOVERY.verify(root, 'campaign/results/')
            self.assertEqual(receipt['objects'][0]['size'], 3)
            self.assertEqual(len(receipt['objects'][0]['sha256']), 64)
            (objects / 'arm.tar.gz').write_bytes(b'ra')
            with self.assertRaisesRegex(ValueError, 'size mismatch'):
                RECOVERY.verify(root, 'campaign/results/')
            (objects / 'arm.tar.gz').unlink()
            with self.assertRaises(FileNotFoundError):
                RECOVERY.verify(root, 'campaign/results/')

    def test_rejects_incomplete_or_escaping_inventory(self):
        for inventory in ({'IsTruncated': True}, {'Contents': [{'Key': 'elsewhere', 'Size': 0}]},
                          {'Contents': [{'Key': 'campaign/results/../outside', 'Size': 0}]}):
            with self.subTest(inventory=inventory), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / 'inventory.json').write_text(json.dumps(inventory))
                with self.assertRaises(ValueError):
                    RECOVERY.verify(root, 'campaign/results/')

    def test_cleanup_recovers_before_deletion_and_retains_failed_recovery(self):
        source = (DIRECTORY / 'run-campaign.sh').read_text()
        cleanup = source[source.index('cleanup()\n{'):source.index('\n}\n\nabort_campaign()') + 2]
        recovery = source[source.index('recover_uploaded_results()\n{'):source.index('\n}\n\ncleanup()') + 2]
        for fail in ('none', 'inventory', 'download', 'size'):
            with self.subTest(fail=fail), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                script = f'''
SESSION_DIR='{root}'
SCRIPT_DIR='{DIRECTORY}'
FAILURE_CLASSIFICATION=controller-abort
FAILURE_DETAIL=test
LAUNCH_ATTEMPTED=1
INSTANCE_IDS=(i-owned)
INSTANCE_PROFILE_NAME=
INSTANCE_ROLE_NAME=
BUCKET=transfer
BUCKET_OWNED=0
OBJECT_PREFIX=campaign
RESULT_PREFIX=campaign/results
CAMPAIGN_ID=test
CAMPAIGN_PLATFORM=r8g
CAMPAIGN_SHARD_ID=traditional-extra
CAMPAIGN_REPLICA_ID=1
CAMPAIGN_HOST_EPOCH=1
discover_campaign_instances() {{ return 0; }}
verify_campaign_instance_ownership() {{ return 0; }}
terminate_campaign_instances() {{ echo terminated >> '{root}/events'; }}
verify_bucket_prefix_absent() {{ return 0; }}
retry_cleanup_command() {{ echo deleted >> '{root}/events'; }}
aws_cli() {{
 if [[ "$1 $2" == 's3api list-objects-v2' ]]; then
  echo inventory >> '{root}/events'
  [[ '{fail}' != inventory ]] || return 1
  echo '{{"Contents":[{{"Key":"campaign/results/arm.tar.gz","Size":3}}]}}'
 elif [[ "$1 $2" == 's3 cp' ]]; then
  echo download >> '{root}/events'
  [[ '{fail}' != download ]] || return 1
  mkdir -p "$4"
  printf raw > "$4/arm.tar.gz"
  [[ '{fail}' != size ]] || printf r > "$4/arm.tar.gz"
 fi
 return 0
}}
{recovery}
{cleanup}
false
cleanup
'''
                result = subprocess.run(['bash'], input=script, text=True, capture_output=True)
                self.assertEqual(result.returncode, 1, result.stderr)
                events = (root / 'events').read_text().splitlines()
                self.assertEqual(events[0], 'terminated')
                if fail == 'none':
                    self.assertEqual(events, ['terminated', 'inventory', 'download', 'deleted'])
                    self.assertTrue((root / 'recovered-uploads/verified.json').exists())
                else:
                    self.assertNotIn('deleted', events)
                    self.assertIn('cleanup_status=failed', (root / 'cleanup-manifest.txt').read_text())
