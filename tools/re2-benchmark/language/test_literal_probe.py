import gzip
import io
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

import collection
import literal_probe
import rebar_inventory


class TestLiteralProbe(unittest.TestCase):
    def test_log_sample_draws_whole_first_half_lines_in_java_random_order(self):
        # Expected values from java.util.Random and Collections.shuffle.
        random = literal_probe.JavaRandom(42)
        self.assertEqual([random.next_int(10) for _ in range(5)], [0, 3, 8, 4, 0])
        self.assertEqual(literal_probe.JavaRandom(1).next_int(16), 11)
        values = list(range(6))
        literal_probe.shuffle(values, literal_probe.JavaRandom(7))
        self.assertEqual(values, [3, 0, 1, 2, 5, 4])

        # Only whole lines from the first half; a long line is skipped, not truncated.
        with patch.object(literal_probe, "SAMPLE_BYTES", 6):
            sample = literal_probe.sample_lines(b"aa\nbbbbbbb\ncc\ndd\n")
        lines = sample.splitlines(keepends=True)
        self.assertEqual((len(sample), len(lines)), (6, 2))
        self.assertLessEqual(set(lines), {b"aa\n", b"cc\n", b"dd\n"})
        self.assertEqual(literal_probe.split_half(b"ab\ncd\nef"), (b"ab\ncd\n", b"ef"))

    def test_prepare_pairs_default_and_tuned_cases(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            corpora = {"text": b"exactly so\r\nexactly\n", "access.log": b"x Mozilla/5.0\nqq\nz Mozilla/5.0\nMozilla/5x0 Mozilla/5.0\n",
                       "other.log": b"q\nMozilla/5.0\n"}
            (root / "corpus").mkdir()
            rows = ["name\tkind\tlocation\trevision\tmax_bytes\tbytes\tsha256\tlicense"]
            for name, data in corpora.items():
                (root / "corpus" / name).write_bytes(data)
                rows.append(f"{name}\turl\thttps://example.com/{name}\tpinned\t100\t{len(data)}\t{collection.digest(data)}\ttest")
            (root / "corpus.tsv").write_text("\n".join(rows) + "\n")
            (root / "workloads.tsv").write_text(
                "id\tpattern\tcorpus\thaystack\tdistribution\texpected_count\n"
                "prose\texactly\ttext\twhole\tenglish-text\t2\n"
                "log\tMozilla/5.0\taccess.log\tsecond-half\tsampled-log-lines\t1\n")
            sample = b"".join(literal_probe.sample_lines(literal_probe.split_half(corpora[name])[0])
                              for name in ("access.log", "other.log"))
            with patch.multiple(literal_probe, CORPUS=root / "corpus.tsv", WORKLOADS=root / "workloads.tsv",
                                SAMPLE_CORPORA=("access.log", "other.log"), SAMPLE_SHA256=collection.digest(sample)):
                manifest = literal_probe.prepare(root / "corpus", root / "manifest")
                self.assertEqual([(case["id"], case["family"], case["input_bytes_per_operation"]) for case in manifest["cases"]], [
                    ("literal-probe/prose", "uniform", 20), ("literal-probe/prose/tuned", "english-text", 20),
                    ("literal-probe/log", "uniform", 24), ("literal-probe/log/tuned", "sampled-log-lines", 24)])
                self.assertEqual(manifest["smoke_required_pairs"], [["literal-probe/log/tuned", language] for language in collection.ENGINES])
                fields = [rebar_inventory.decode_klv((root / "manifest" / case["source_file"]).read_bytes())
                          for case in manifest["cases"]]
                self.assertEqual([set(entry) - {"name", "model", "pattern", "haystack", "unicode", "case-insensitive"} for entry in fields],
                                 [set(), {"byte-frequencies"}, set(), {"byte-frequency-sample"}])
                self.assertEqual(fields[1]["byte-frequencies"], [b"english-text"])
                self.assertEqual(fields[3]["byte-frequency-sample"], [sample])
                # The escaped dot keeps the wildcard-only near miss out of the literal's count.
                self.assertEqual([entry["pattern"] for entry in fields], [[b"exactly"]] * 2 + [[rb"Mozilla/5\.0"]] * 2)
                self.assertEqual(len(re.findall(fields[2]["pattern"][0], fields[2]["haystack"][0])), 1)

                (root / "workloads.tsv").write_text((root / "workloads.tsv").read_text().replace("\t2\n", "\t3\n"))
                with self.assertRaisesRegex(ValueError, "expected count changed"):
                    literal_probe.prepare(root / "corpus", root / "changed-count")
                (root / "corpus" / "text").write_bytes(b"changed")
                with self.assertRaisesRegex(ValueError, "differs from its pin"):
                    literal_probe.prepare(root / "corpus", root / "changed-corpus")

    def test_download_truncates_decompressed_source_at_a_line_feed(self):
        row = {"name": "events.json", "kind": "gzip-url", "location": "https://example.com/events.json.gz", "max_bytes": "10"}
        with patch.object(literal_probe, "open_url", return_value=io.BytesIO(gzip.compress(b"one\ntwo\nthree\nfour\n"))):
            self.assertEqual(literal_probe.download(row), b"one\ntwo\n")

    def test_checked_in_workloads_name_pinned_corpora(self):
        corpora = literal_probe.corpus_rows()
        workloads = literal_probe.workload_rows(corpora)
        self.assertEqual({row["corpus"] for row in workloads} | set(literal_probe.SAMPLE_CORPORA), set(corpora))
        self.assertTrue(all(row["license"] and len(row["sha256"]) == 64 for row in corpora.values()))


if __name__ == "__main__":
    unittest.main()
