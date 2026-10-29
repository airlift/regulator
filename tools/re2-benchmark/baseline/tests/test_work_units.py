import copy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASELINE))
import row_protocol  # noqa: E402
import work_units  # noqa: E402

ISO_DATE_COUNT = "trino-operations/everyday/isoDate/count/"


def holding(units, row_id):
    return next(unit for unit in units if row_id in unit["owned"])


class TestWorkUnits(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.units = work_units.generate_from(BASELINE)
        cls.manifest_rows = row_protocol.read_tsv(BASELINE / "rows.tsv")
        cls.protocols = row_protocol.load(BASELINE / "row-protocol.tsv")

    def test_generator_writes_the_tables_and_summarizes_each_shard(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                [sys.executable, BASELINE / "generate-units.py", "--output-directory", temporary],
                check=True, capture_output=True, text=True)
            self.assertEqual(sorted(work_units.TABLES), sorted(path.name for path in Path(temporary).iterdir()))
        # Each shard's unit minutes, by their smallest, median and largest unit, and its measured groups.
        self.assertIn("like-dfa-single-use\t1\t10.6\t10.6\t10.6\t10.6\t1\n", result.stdout)
        self.assertIn("trino-operations\t3\t13.1\t13.2\t13.2\t39.5\t6\n", result.stdout)

    def test_groups_partition_each_unit_and_keep_what_is_measured_together(self):
        by_id = {row["row_id"]: row for row in self.manifest_rows}
        group_of = {}
        for unit in self.units:
            self.assertEqual(sorted(unit["owned"]),
                             sorted(row_id for group in unit["groups"] for row_id in group["owned"]))
            for group in unit["groups"]:
                group_of.update(dict.fromkeys(group["owned"], (unit["unit_id"], group["group_id"])))
                # A group fills the minimum unless it is its unit's only one.
                self.assertTrue(len(unit["groups"]) == 1 or group["estimated_seconds"] >= work_units.GROUP_SECONDS)
        together = [[candidate["row_id"], *(row["row_id"] for row in comparators)]
                    for _, candidate, _, comparators in row_protocol.comparison_specs(self.manifest_rows)]
        together += [[row["row_id"] for row, _ in points]
                     for _, points in row_protocol.identify_scaling_groups(self.manifest_rows).values()]
        logical = {}
        for row in self.manifest_rows:
            logical.setdefault(row_protocol.logical_row_id(row), []).append(row["row_id"])
        together += logical.values()
        for unit in self.units:
            together += [[row["row_id"] for _, rows in methods for row in rows]
                         for _, methods in work_units.launches([by_id[row_id] for row_id in unit["owned"]],
                                                               self.protocols)]
        for row_ids in together:
            self.assertEqual(1, len({group_of[row_id] for row_id in row_ids}), row_ids[0])

    def test_a_method_holding_a_value_jmh_cannot_select_is_never_split(self):
        rows = [{"parameters": f"pattern={pattern};size={size}"} for pattern in ("a,b", "c") for size in ("1", "2")]
        self.assertIsNone(work_units.split_axis(rows, set()))

    def test_check_rejects_a_comparison_split_across_units(self):
        units = copy.deepcopy(self.units)
        source = holding(units, ISO_DATE_COUNT + "joni")
        target = next(unit for unit in units if unit["shard_id"] == "trino-operations" and unit is not source)
        for rows in (source["owned"], source["groups"][0]["owned"]):
            rows.remove(ISO_DATE_COUNT + "joni")
        for rows in (target["owned"], target["groups"][0]["owned"]):
            rows.append(ISO_DATE_COUNT + "joni")
        with self.assertRaisesRegex(ValueError, "not measured beside its joni comparator"):
            work_units.check(units, self.manifest_rows, self.protocols)

    def test_check_rejects_malformed_groups(self):
        def drop_row(groups):
            groups[0]["owned"].remove(ISO_DATE_COUNT + "joni")

        def shorten(groups):
            groups[1]["estimated_seconds"] = work_units.GROUP_SECONDS - 1

        def split_comparison(groups):
            drop_row(groups)
            groups[1]["owned"].append(ISO_DATE_COUNT + "joni")

        for change, message in ((drop_row, "groups do not partition its rows"),
                                (shorten, f"group 2 is under {work_units.GROUP_SECONDS} seconds"),
                                (split_comparison, "not measured beside its joni comparator")):
            units = copy.deepcopy(self.units)
            # The comparison's unit has two groups, and the first holds the comparison.
            change(holding(units, ISO_DATE_COUNT + "joni")["groups"])
            with self.subTest(change.__name__), self.assertRaisesRegex(ValueError, message):
                work_units.check(units, self.manifest_rows, self.protocols)


if __name__ == "__main__":
    unittest.main()
