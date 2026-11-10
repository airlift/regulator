import unittest
from pathlib import Path
import tempfile
from unittest import mock

import bulk
import collection
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
        # Rust's byte mode reads the raw-byte escapes as RE2's Latin-1 mode does.
        self.assertEqual(mappings.translate("reported/i13-subset-regex/huge-ascii-nosuffixlit", "rust",
                                           mappings.I13_PATTERN)[0], mappings.I13_PATTERN)
        with self.assertRaisesRegex(ValueError, "source pattern changed"):
            mappings.translate("reported/i13-subset-regex/huge-ascii-nosuffixlit", "rust", b"different")
        self.assertEqual(mappings.translate("reported/i13-subset-regex/huge-unicode-nosuffixlit", "trino",
                                           mappings.I13_PATTERN)[0],
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

    def test_rust_excludes_its_empty_match_rule_and_its_size_limit(self):
        for cases in (mappings.RUST_EMPTY_MATCH_CASES, mappings.RUST_SIZE_CASES):
            for identity, pattern in cases.items():
                self.assertIsNone(mappings.translate(identity, "rust", pattern)[0])
                with self.assertRaisesRegex(ValueError, "source pattern changed"):
                    mappings.translate(identity, "rust", b"different")

    def test_memory_budgets_name_frozen_rows_and_languages(self):
        manifests = Path(__file__).resolve().parents[1] / "manifests"
        rows = {line.split("\t")[0] for line in (manifests / "rebar-extended-workloads.tsv").read_text().splitlines()[1:]}
        rows |= {line.split(",")[0] for line in (manifests / "rebar-workloads.csv").read_text().splitlines()[1:]}
        for identity, (_, languages) in mappings.MEMORY_BUDGETS_MIB.items():
            self.assertIn(identity, rows)
            self.assertLessEqual(set(languages), set(collection.ENGINES))
        self.assertEqual(mappings.memory_budget("curated/10-bounded-repeat/context", "rust"),
                         mappings.MEMORY_BUDGETS_MIB["curated/10-bounded-repeat/context"][0] << 20)
        self.assertIsNone(mappings.memory_budget("dictionary/search/english-10", "rust"))
        self.assertIsNone(mappings.memory_budget("unicode/overlapping-words/ascii", "re2"))
        # A budget applies only in its languages.
        with mock.patch.dict(mappings.MEMORY_BUDGETS_MIB, {"example": (96, ("re2",))}):
            self.assertEqual(mappings.memory_budget("example", "re2"), 96 << 20)
            self.assertIsNone(mappings.memory_budget("example", "trino"))

    def test_memory_budget_list_parses_strictly(self):
        # The shipped list loads under these rules, and each budgeted row's compile sibling is listed with it.
        budgets = mappings.load_memory_budgets()
        self.assertEqual(budgets["curated/10-bounded-repeat/compile-context"], budgets["curated/10-bounded-repeat/context"])
        header = "# A comment\nrow_id\tlanguages\tbudget_mib\n"
        self.assertEqual(mappings.parse_memory_budgets(header + "a\tre2,rust\t72\n# Another\nb\tjava\t8796093022207\n"),
                         {"a": (72, ("re2", "rust")), "b": (8796093022207, ("java",))})
        # The same cases as RebarRunner's loader test.
        for rows in ("a\tre2\t96\n\nb\tre2\t96\n", "a\tre2\t96\na\tjava\t96\n", "a\tgo\t96\n", "a\tre2,re2\t96\n",
                     "a\t\t96\n", "a\tre2\n", "a\tre2\t9_6\n", "a\tre2\t 96\n", "a\tre2\t96 \n", "a\tre2\t0\n",
                     "a\tre2\t096\n", "a\tre2\t17592186044416\n", "a\tre2\t8796093022208\n", "a\tre2\t96\r\n"):
            with self.subTest(rows=rows), self.assertRaisesRegex(ValueError, "^memory budget list line "):
                mappings.parse_memory_budgets(header + rows)
        with self.assertRaisesRegex(ValueError, "expected the header row"):
            mappings.parse_memory_budgets("# A comment\na\tre2\t96\n")
        # The shipped file is read with its line endings intact, so a CRLF copy fails as it does in RebarRunner.
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "budgets.tsv"
            path.write_bytes(mappings.MEMORY_BUDGETS_FILE.read_bytes().replace(b"\n", b"\r\n"))
            with mock.patch.object(mappings, "MEMORY_BUDGETS_FILE", path):
                with self.assertRaisesRegex(ValueError, "expected the header row"):
                    mappings.load_memory_budgets()
        with self.assertRaisesRegex(ValueError, "no header row"):
            mappings.parse_memory_budgets("# A comment\n")

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
