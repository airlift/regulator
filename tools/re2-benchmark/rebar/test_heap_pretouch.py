import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


TOOLS = Path(__file__).resolve().parents[1]


class TestHeapPretouch(unittest.TestCase):
    def test_runners_preserve_heap_and_default_measurement_flags(self):
        for runner in ('rebar/run-regulator.sh', 'trino-joni/run-rebar.sh'):
            with self.subTest(runner=runner), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                script = root / 'tools/re2-benchmark' / runner
                script.parent.mkdir(parents=True)
                shutil.copy2(TOOLS / runner, script)
                target = root / 'target'
                target.mkdir()
                (target / 'rebar-classpath.txt').write_text('fixture')
                comparator = target / 'trino-joni-comparator'
                comparator.mkdir()
                (comparator / 'classpath.txt').write_text('fixture')
                binary = root / 'bin'
                binary.mkdir()
                java = binary / 'java'
                java.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
                java.chmod(0o755)
                environment = {**os.environ, 'PATH': str(binary) + os.pathsep + os.environ['PATH'],
                               'REBAR_HEAP_SIZE': '8g', 'REBAR_NATIVE_ACCESS': 'enabled',
                               'TRINO_COMPARATOR_WORK_DIR': str(comparator)}
                environment.pop('REBAR_HEAP_PRETOUCH', None)
                for setting in (None, 'true', 'false', 'invalid'):
                    if setting is not None:
                        environment['REBAR_HEAP_PRETOUCH'] = setting
                    result = subprocess.run(['bash', str(script)], env=environment, capture_output=True, text=True)
                    if setting == 'invalid':
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn('must be true or false', result.stderr)
                        continue
                    self.assertEqual(result.returncode, 0, result.stderr)
                    flags = result.stdout.splitlines()
                    self.assertIn('-Xms8g', flags)
                    self.assertIn('-Xmx8g', flags)
                    flag = '-XX:-AlwaysPreTouch' if setting == 'false' else '-XX:+AlwaysPreTouch'
                    self.assertEqual([value for value in flags if 'AlwaysPreTouch' in value], [flag])


if __name__ == '__main__':
    unittest.main()
