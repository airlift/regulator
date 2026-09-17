"""Frozen source selection must survive unrelated changes to the local branch."""
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

SOURCE=Path(__file__).with_name('run-campaign.sh')

class TestCandidateCommit(unittest.TestCase):
    def select(self, reference):
        source=SOURCE.read_text()
        start=source.index('candidate_commit()\n{')
        function=source[start:source.index('\n}\n',start)+3]
        with tempfile.TemporaryDirectory() as temporary:
            archive=Path(temporary)/'source.tar.gz'
            with tarfile.open(archive,'w:gz',format=tarfile.PAX_FORMAT,pax_headers={'comment':reference}) as target:
                entry=tarfile.TarInfo('source.txt');entry.size=4;target.addfile(entry,io.BytesIO(b'code'))
            environment={**os.environ,'REGULATOR_DIR':'/assigned-worktree','BASELINE_CANDIDATE_ARCHIVE':str(archive)}
            # Return the requested revision, making accidental HEAD resolution visible.
            script=function+'\ngit() { test "$1" = -C && test "$2" = /assigned-worktree && test "$3" = rev-parse && test "$4" = --verify && printf "%s\\n" "$5"; }\ncandidate_commit\n'
            return subprocess.run(['bash','-c',script],env=environment,text=True,capture_output=True)

    def test_frozen_archive_revision_does_not_follow_branch_head(self):
        revision='0123456789abcdef0123456789abcdef01234567'
        result=self.select(revision);self.assertEqual(result.returncode,0);self.assertEqual(result.stdout.strip(),revision+'^{commit}')
        self.assertIn('rev-parse "${REGULATOR_COMMIT}:src/main"',SOURCE.read_text())

    def test_missing_or_movable_archive_identity_is_rejected(self):
        for reference in ('','HEAD','main','HEAD~1','--all','abc123','A'*40):
            with self.subTest(reference=reference):
                result=self.select(reference);self.assertNotEqual(result.returncode,0);self.assertEqual(result.stdout,'')
                self.assertIn('full Git commit',result.stderr)

if __name__=='__main__':unittest.main()
