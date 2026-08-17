import unittest
from pathlib import Path
import tempfile

import bulk
import mappings
import rebar_inventory
import unicode_ranges


class TestMappings(unittest.TestCase):
    def test_lf_rules_are_explicit_and_source_guarded(self):
        for identity, pattern in mappings.JAVA_LINE_CASES.items():
            expected = rb"(?d)^.*$" if identity == "opt/accelerate/whole-line" else b"(?d)" + pattern
            self.assertEqual(mappings.translate(identity, "java", pattern)[0], expected)
            self.assertEqual(mappings.translate(identity, "re2", pattern)[0], pattern)
            with self.assertRaisesRegex(ValueError, "source pattern changed"):
                mappings.translate(identity, "java", b"different")

    def test_boundaries_and_byte_escapes_are_not_silently_reinterpreted(self):
        for identity, pattern in mappings.BOUNDARY_CASES.items():
            self.assertIsNone(mappings.translate(identity, "trino", pattern)[0])
            self.assertEqual(mappings.translate(identity, "java", pattern)[0], pattern)
        self.assertIsNone(mappings.translate("reported/i13-subset-regex/huge-ascii-nosuffixlit", "java", b"unused")[0])
        self.assertEqual(mappings.translate("reported/i13-subset-regex/huge-unicode-nosuffixlit", "trino",
                                           rb"[a-q][^u-z]{80}[x\xE0-\xFF]")[0],
                         rb"[a-q][^u-z]{80}[x\x{E0}-\x{FF}]")

    def test_pinned_unicode_classes_are_identical_in_all_languages(self):
        for identity, (pattern, name, suffix) in mappings.UNICODE_CASES.items():
            translated = {mappings.translate(identity, language, pattern)[0] for language in ("re2", "java", "trino")}
            self.assertEqual(translated, {mappings.unicode_class(name) + suffix})
            self.assertNotIn(b"\\p", next(iter(translated)))
            with self.assertRaisesRegex(ValueError, "source pattern changed"):
                mappings.translate(identity, "java", b"different")

    def test_klv_round_trip_preserves_repeated_patterns_and_arbitrary_bytes(self):
        fields = {"name": [b"example"], "model": [b"count"], "pattern": [b"a", b"b"],
                  "haystack": [b"\x00\xff\n:"], "unicode": [b"false"], "case-insensitive": [b"false"]}
        self.assertEqual(rebar_inventory.decode_klv(bulk.encode_klv(fields)), fields)

    def test_bibleref_preserves_named_capture_order_with_braced_properties(self):
        pattern = b"".join(b"(?P<g" + str(index).encode() + rb">\pL\pZ)" for index in range(8))
        expected = pattern.replace(b"(?P<", b"(?<").replace(rb"\pL", rb"\p{L}").replace(rb"\pZ", rb"\p{Z}")
        for language in ("java", "trino"):
            self.assertEqual(mappings.translate("wild/bibleref/long", language, pattern)[0], expected)

    def test_unicode_range_generator_rejects_missing_or_overlapping_ranges(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "unicode_groups.cc"
            source.write_text("\n".join(
                f"static const URange{width} {name}_range{width}[] = {{ {{ {low}, {low + 1} }}, }};"
                for name in ("L", "Greek") for width, low in ((16, 65), (32, 65536))))
            generated = unicode_ranges.generate(source)
            self.assertEqual(generated["ranges"]["L"], [[65, 66], [65536, 65537]])
            source.write_text(source.read_text().replace("65536, 65537", "65, 66"))
            with self.assertRaisesRegex(ValueError, "overlapping"):
                unicode_ranges.generate(source)
            source.write_text("")
            with self.assertRaisesRegex(ValueError, "missing"):
                unicode_ranges.generate(source)


if __name__ == "__main__":
    unittest.main()
