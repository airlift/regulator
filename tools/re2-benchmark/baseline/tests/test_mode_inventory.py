import importlib.util
from pathlib import Path
import tempfile
import unittest


DIRECTORY = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('mode_inventory', DIRECTORY / 'validate-mode-inventory.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class TestModeInventory(unittest.TestCase):
    def test_checked_in_wrappers_have_both_supported_modes(self):
        MODULE.main()

    def test_additional_mode_is_not_silently_ignored(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / 'wrapper.sh'
            source.write_text('if [[ "${MODE}" != baseline-shard && "${MODE}" != language-batch && "${MODE}" != surprise ]]; then\n')
            self.assertEqual(MODULE.shell_modes(source, 'MODE'), {'baseline-shard', 'language-batch', 'surprise'})


if __name__ == '__main__':
    unittest.main()
