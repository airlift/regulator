import copy
import gzip
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))
import collection
import rebar_inventory as inventory


class TestRebarInventory(unittest.TestCase):
    def payload(self):
        fields = {"name": b"fixture/case", "model": b"count", "pattern": b"a:\n",
                  "haystack": b"\x00:a\n\xff", "unicode": b"false", "case-insensitive": b"true"}
        return b"".join(key.encode() + b":" + str(len(value)).encode() + b":" + value + b"\n"
                        for key, value in fields.items())

    def test_klv_preserves_binary_newlines_and_colons(self):
        fields = inventory.decode_klv(self.payload())
        self.assertEqual(fields["pattern"], [b"a:\n"])
        self.assertEqual(fields["haystack"], [b"\x00:a\n\xff"])
        self.assertEqual(inventory.decode_klv(self.payload() + b"pattern:1:b\n")["pattern"], [b"a:\n", b"b"])

    def test_klv_rejects_truncated_negative_duplicate_missing_and_invalid_fields(self):
        for payload in (self.payload()[:-1], self.payload() + b"extra:99:x\n",
                        self.payload() + b"extra:-1:x\n", self.payload() + b"name:1:x\n",
                        b"name:1:x\n", self.payload().replace(b"unicode:5:false", b"unicode:5:other")):
            with self.assertRaises(ValueError):
                inventory.decode_klv(payload)

    def test_native_expected_counts_follow_ordered_engine_rules(self):
        self.assertEqual(inventory.expected_native_result({"count": 5}), 5)
        self.assertEqual(inventory.expected_native_result({"count": [
            {"engine": "java/.*", "count": 6}, {"engine": "^re2$", "count": 7}, {"engine": ".*", "count": 8}]}), 7)
        for expected in (True, -1, [{"engine": "^java$", "count": 4}]):
            with self.assertRaisesRegex(ValueError, "expected result"):
                inventory.expected_native_result({"count": expected})

    def test_representation_does_not_replace_invalid_utf8(self):
        self.assertEqual(inventory.input_identity(b"\xff"), {
            "bytes": 1, "sha256": collection.digest(b"\xff"), "ascii": False, "valid_utf8": False})
        self.assertTrue(inventory.input_identity("😀".encode())["valid_utf8"])
        self.assertFalse(inventory.input_identity("😀".encode())["ascii"])

    def test_full_checked_in_inventory_has_no_missing_models(self):
        rows, applicability = inventory.registry()
        self.assertEqual(len(rows), 238)
        self.assertEqual(len(applicability), 238)
        self.assertEqual({row["model"] for row in rows}, inventory.MODELS)

    def test_validation_binds_binary_input_and_never_promotes_pending_to_compatible(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            payload = self.payload()
            (directory / "case.klv.gz").write_bytes(gzip.compress(payload, mtime=0))
            fields = inventory.decode_klv(payload)
            case = {"id": "fixture/case", "model": "count", "klv_file": "case.klv.gz",
                    "klv_sha256": collection.digest(payload), "klv_bytes": len(payload),
                    "patterns": [inventory.input_identity(pattern) for pattern in fields["pattern"]],
                    "haystack": inventory.input_identity(fields["haystack"][0]),
                    "unicode": False, "case_insensitive": True,
                    "language_preparation": {language: {"state": "pending"} for language in collection.ENGINES}}
            original = {"schema_version": 1, "kind": "preparation-only", "rebar_commit": inventory.PINNED_REBAR,
                        "manifests": {}, "model_counts": {"count": 1}, "cases": [case]}
            collection.save(directory / "inventory.json", original)
            with patch.object(inventory, "registry", return_value=([{"name": "fixture/case", "model": "count"}], {})):
                self.assertEqual(inventory.validate(directory), original)
                for mutate in (lambda item: item["cases"].append(item["cases"][0]),
                               lambda item: item["cases"][0].update(klv_sha256="changed"),
                               lambda item: item["cases"][0].update(unicode=True),
                               lambda item: item["cases"][0].update(klv_file="../escape"),
                               lambda item: item["cases"][0]["language_preparation"]["java"].update(state="not-compatible")):
                    broken = copy.deepcopy(original)
                    mutate(broken)
                    collection.save(directory / "inventory.json", broken)
                    with self.assertRaises(ValueError):
                        inventory.validate(directory)


if __name__ == "__main__":
    unittest.main()
