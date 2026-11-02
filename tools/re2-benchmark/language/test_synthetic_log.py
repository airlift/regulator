from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

import collection
import fleet
import rebar_inventory
import synthetic_log


class TestSyntheticLog(unittest.TestCase):
    def test_haystacks_match_their_pins(self):
        haystacks = synthetic_log.haystacks()
        self.assertEqual({name: (collection.digest(haystack), len(re.findall(synthetic_log.PATTERN, haystack)))
                          for name, haystack in haystacks.items()}, synthetic_log.PINS)
        # Logs end at a whole line within 1 MiB; the near misses fill it.
        self.assertEqual([(len(haystack) <= 1 << 20, haystack.endswith(b"\n")) for haystack in haystacks.values()],
                         [(True, True), (True, True), (True, False)])
        self.assertEqual(len(haystacks["near-miss"]), 1 << 20)
        lines = haystacks["realistic"].splitlines()
        self.assertRegex(lines[0], rb"^2026-11-01T00:00:00\.\d{3}Z INFO  \[\w+\]( [a-z]+){4,11} \d+$")
        levels = [line.split()[1] for line in lines]
        # Percent shares of the drawn levels, near the 80/10/6/3/1 weights.
        self.assertEqual({level: round(levels.count(level) * 100 / len(levels)) for level in set(levels)},
                         {b"INFO": 79, b"DEBUG": 10, b"WARN": 6, b"ERROR": 3, b"FATAL": 1})

    def test_prepare_selects_requested_languages(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = synthetic_log.prepare(root / "manifest")
            self.assertEqual([(case["id"], case["expected_result"]) for case in manifest["cases"]], [
                ("synthetic-log/realistic", 1169), ("synthetic-log/match-dense", 10254), ("synthetic-log/near-miss", 0)])
            fields = rebar_inventory.decode_klv((root / "manifest" / manifest["cases"][2]["source_file"]).read_bytes())
            self.assertEqual((fields["pattern"], fields["haystack"][0][:15]), ([b"ERROR|WARN|FATAL"], b"ERxO WAxN FAxA "))
            self.assertEqual(manifest["smoke_required_pairs"], [["synthetic-log/near-miss", "rust"],
                                                                ["synthetic-log/near-miss", "trino"]])
            selection = collection.load(root / "manifest" / "selection.json")
            self.assertEqual(len(selection), 6)
            fleet.prepare(root / "manifest", root / "fleet", selection, shard_id="synthetic-log")
            self.assertEqual(sorted(path.name for path in (root / "fleet" / "partitions").iterdir()),
                             [f"case-000{index}-{language}" for index in range(3) for language in ("rust", "trino")])

            re2 = synthetic_log.prepare(root / "re2", ("re2",))
            self.assertEqual(collection.load(root / "re2" / "selection.json"), [["synthetic-log/realistic", "re2"],
                                                                                ["synthetic-log/match-dense", "re2"],
                                                                                ["synthetic-log/near-miss", "re2"]])
            self.assertEqual(re2["smoke_required_pairs"], [["synthetic-log/near-miss", "re2"]])
            # A rejected language list writes nothing, so a corrected retry can use the same directory.
            for languages in (("rust", "perl"), (), ("rust", " trino")):
                with self.assertRaisesRegex(ValueError, "invalid synthetic-log languages"):
                    synthetic_log.prepare(root / "unknown", languages)
                self.assertFalse((root / "unknown").exists())
            with patch.dict(synthetic_log.PINS, {"near-miss": (synthetic_log.PINS["near-miss"][0], 1)}):
                with self.assertRaisesRegex(ValueError, "differs from its pin: near-miss"):
                    synthetic_log.prepare(root / "changed")


if __name__ == "__main__":
    unittest.main()
