import csv
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import trino_rows


class TestTrinoRows(unittest.TestCase):
    def test_rows_are_nonempty_lines_and_pooled_corpora_share_the_sample(self):
        self.assertEqual(trino_rows.lines(b"a\r\n\r\nb\n\nc"), [b"a", b"b", b"c"])
        self.assertEqual(trino_rows.sample_counts([2000] * 6), [683] * 4 + [682] * 2)
        self.assertEqual(trino_rows.stride_indexes(10, 4), [0, 2, 5, 7])
        with self.assertRaisesRegex(ValueError, "cannot sample"):
            trino_rows.sample_counts([4095])

    def test_grid_literals_follow_first_byte_tercile_and_hit_band(self):
        # Sampled even lines: 30 hold mmmm, 3 of those also aaaa.b, 10 others kkkk.
        # Odd lines supply the candidates, so zzzz, zzzy, and kkkq occur in no sampled row.
        sampled = [b"mmmm aaaa.b" if index < 3 else b"mmmm" if index < 30 else b"kkkk" if index < 40 else b"x y"
                   for index in range(100)]
        others = [b"zzzz aaaa.b", b"mmmm kkkk zzzy kkkq"] * 50
        data = b"".join(row + b"\r\n" for pair in zip(sampled, others) for row in pair)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "fixture.log").write_bytes(data)
            corpora = {"fixture.log": {"name": "fixture.log", "kind": "url", "bytes": str(len(data)),
                                       "sha256": trino_rows.sha256(data)}}
            fixture = (("fixture", ("fixture.log",), ("x y",)),)
            with patch.object(trino_rows, "ROWS", 100):
                workloads, literals = trino_rows.generate(directory, corpora, fixture)
                self.assertEqual((workloads, literals), trino_rows.generate(directory, corpora, fixture))
                (directory / "fixture.log").write_bytes(data + b"z\n")
                with self.assertRaisesRegex(ValueError, "differs from its pin"):
                    trino_rows.generate(directory, corpora, (("fixture", ("fixture.log",), ()),))

        workload = next(csv.DictReader(io.StringIO(workloads), delimiter="\t", quoting=csv.QUOTE_NONE))
        self.assertEqual({key: workload[key] for key in ("sources", "population_rows", "sampled_rows", "rows", "p50_bytes")},
                         {"sources": "fixture.log", "population_rows": "200", "sampled_rows": "100", "rows": "100", "p50_bytes": "3"})
        rows = {row["literal_id"]: row for row in csv.DictReader(io.StringIO(literals), delimiter="\t", quoting=csv.QUOTE_NONE)}
        # Starter counts in the sampled rows: z 0, a 12, k 40, m 120 of 172. Cumulative counts
        # end at 0, 12, 52, and 172, so z, a, and k are low, and m alone is high; kkkk is in 10%
        # of rows, outside every band.
        self.assertEqual({key: (row["pattern"], row["candidates"], row["hit_rows"], row["matches"])
                          for key, row in rows.items() if row["literal"]}, {
            "low-none": (rows["low-none"]["literal"], "3", "0", "0"),
            "low-selective": ("aaaa\\.b", "1", "3", "3"),
            "high-common": ("mmmm", "1", "30", "30"),
            "picked-1": ("x y", "-", "60", "60")})
        self.assertIn(rows["low-none"]["literal"], {"zzzz", "zzzy", "kkkq"})
        self.assertEqual([row["group"] for row in rows.values()], ["grid"] * 9 + ["picked"])
        self.assertEqual({key for key, row in rows.items() if not row["literal"]},
                         {"low-common", "mid-none", "mid-selective", "mid-common", "high-none", "high-selective"})
        self.assertEqual((rows["high-common"]["first_byte"], rows["high-common"]["first_byte_count"]), ("0x6D", "120"))

    def test_checked_in_manifests_name_pinned_corpora(self):
        corpora = trino_rows.corpus_rows()
        self.assertEqual({name for _, names, _ in trino_rows.WORKLOADS for name in names}, set(corpora))
        self.assertTrue(all(row["license"] and len(row["sha256"]) == 64 for row in corpora.values()))
        # Access logs keep the literal-probe pins, so one corpus directory serves both.
        literal_probe = trino_rows.read_table(trino_rows.ROOT / "tools/re2-benchmark/manifests/literal-probe-corpus.tsv")
        for row in literal_probe:
            if row["name"] in corpora:
                self.assertEqual(corpora[row["name"]], row)

        workloads = trino_rows.read_table(trino_rows.WORKLOADS_OUTPUT)
        literals = trino_rows.read_table(trino_rows.LITERALS_OUTPUT)
        self.assertEqual([row["workload"] for row in workloads], [workload for workload, _, _ in trino_rows.WORKLOADS])
        for workload, _, picked in trino_rows.WORKLOADS:
            self.assertEqual([row["literal_id"] for row in literals if row["workload"] == workload],
                             [f"{tercile}-{band}" for tercile in trino_rows.TERCILES for band in trino_rows.BAND_NAMES] +
                             [f"picked-{index}" for index in range(1, len(picked) + 1)])


if __name__ == "__main__":
    unittest.main()
