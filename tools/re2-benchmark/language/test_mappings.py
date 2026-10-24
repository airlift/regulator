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
            translated = {mappings.translate(identity, language, pattern)[0] for language in ("re2", "java", "trino", "rust")}
            self.assertEqual(translated, {mappings.unicode_class(name) + suffix})
            self.assertNotIn(b"\\p", next(iter(translated)))
            with self.assertRaisesRegex(ValueError, "source pattern changed"):
                mappings.translate(identity, "java", b"different")

    def test_klv_round_trip_preserves_repeated_patterns_and_arbitrary_bytes(self):
        fields = {"name": [b"example"], "model": [b"count"], "pattern": [b"a", b"b"],
                  "haystack": [b"\x00\xff\n:"], "unicode": [b"false"], "case-insensitive": [b"false"]}
        self.assertEqual(rebar_inventory.decode_klv(bulk.encode_klv(fields)), fields)

    def test_bibleref_preserves_named_capture_order_with_braced_properties(self):
        pattern = mappings.BIBLEREF_PATTERN
        expected = pattern.replace(b"(?P<", b"(?<").replace(rb"\pL", rb"\p{L}").replace(rb"\pZ", rb"\p{Z}")
        self.assertEqual(mappings.translate("wild/bibleref/long", "trino", pattern)[0], expected)
        # Java compiles the case with Unicode classes; archived 1.0 manifests keep the syntax-only translation.
        self.assertEqual(mappings.translate("wild/bibleref/long", "java", pattern)[0], mappings.ascii_classes(expected))
        self.assertEqual(mappings.translate("wild/bibleref/long", "java", pattern, archived=True)[0], expected)

    def test_rust_spells_out_re2_ascii_digit_and_space_classes(self):
        self.assertEqual(mappings.ascii_classes(rb"\d\\d\s\\s\w"), rb"[0-9]\\d[\t\n\f\r ]\\s\w")
        self.assertEqual(mappings.translate("wild/bibleref/long", "rust", mappings.BIBLEREF_PATTERN)[0],
                         mappings.ascii_classes(mappings.BIBLEREF_PATTERN))
        self.assertEqual(mappings.translate_lifecycle("everyday/trinoPhone", "rust", r"\d{3}/\d{3}/\d{4}")[0],
                         "[0-9]{3}/[0-9]{3}/[0-9]{4}")
        self.assertEqual(mappings.translate_lifecycle("everyday/whitespace", "rust", r"\s+")[0], r"[\t\n\f\r ]+")
        # Java and Trino compile lifecycle patterns with ASCII classes.
        self.assertEqual(mappings.translate_lifecycle("everyday/whitespace", "java", r"\s+"), (r"\s+", None))
        with self.assertRaisesRegex(ValueError, "source pattern changed"):
            mappings.translate("wild/bibleref/long", "rust", rb"\d+")
        with self.assertRaisesRegex(ValueError, "source pattern changed"):
            mappings.translate_lifecycle("everyday/whitespace", "rust", r"\s*")

    def test_rust_excludes_byte_patterns_its_empty_match_rule_and_its_size_limit(self):
        for identity, pattern in (("imported/rsc/not-literal", rb".y"), ("curated/14-quadratic/1x", rb".*[^A-Z]|[A-Z]")):
            self.assertIsNone(mappings.translate(identity, "rust", pattern)[0])
            self.assertEqual(mappings.translate(identity, "java", pattern)[0], pattern)
        # Pin both the source pattern and its Unicode-off flag.
        for pattern, unicode in ((rb".z", False), (rb".y", True)):
            with self.assertRaisesRegex(ValueError, "source pattern or Unicode flag changed"):
                mappings.translate("imported/rsc/not-literal", "rust", pattern, unicode=unicode)
        for cases in (mappings.RUST_EMPTY_MATCH_CASES, mappings.RUST_SIZE_CASES):
            for identity, pattern in cases.items():
                self.assertIsNone(mappings.translate(identity, "rust", pattern)[0])
                with self.assertRaisesRegex(ValueError, "source pattern changed"):
                    mappings.translate(identity, "rust", b"different")

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
