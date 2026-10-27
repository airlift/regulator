import copy
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASELINE))
import row_protocol  # noqa: E402
import work_units  # noqa: E402

SPECIFICATION = importlib.util.spec_from_file_location("validate_units", BASELINE / "validate-units.py")
VALIDATOR = importlib.util.module_from_spec(SPECIFICATION)
sys.modules["validate_units"] = VALIDATOR
SPECIFICATION.loader.exec_module(VALIDATOR)

ISO_DATE_COUNT = "trino-operations/everyday/isoDate/count/"


def holding(units, row_id):
    return next(unit for unit in units.values() if row_id in unit["owned"])


class TestWorkUnits(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.units = work_units.load(BASELINE)
        cls.manifest_rows = row_protocol.read_tsv(BASELINE / "rows.tsv")
        cls.representatives = row_protocol.read_tsv(BASELINE / "protocol-representatives.tsv")
        cls.protocols = row_protocol.load(BASELINE / "row-protocol.tsv")

    def test_generator_writes_the_tables_and_summarizes_each_shard(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                [sys.executable, BASELINE / "generate-units.py", "--output-directory", temporary],
                check=True, capture_output=True, text=True)
            self.assertEqual(sorted(work_units.TABLES), sorted(path.name for path in Path(temporary).iterdir()))
        # Each shard's unit minutes, by their smallest, median and largest unit.
        self.assertIn("like-dfa-single-use\t1\t12.5\t12.5\t12.5\t12.5\n", result.stdout)
        self.assertIn("trino-operations\t4\t12.1\t12.2\t12.3\t48.7\n", result.stdout)

    def test_a_method_holding_a_value_jmh_cannot_select_is_never_split(self):
        rows = [{"parameters": f"pattern={pattern};size={size}"} for pattern in ("a,b", "c") for size in ("1", "2")]
        self.assertIsNone(work_units.split_axis(rows, set()))

    def test_validator_rejects_units_that_break_a_measurement_requirement(self):
        def move(units, row_ids, target):
            for unit in units.values():
                unit["owned"] = [row_id for row_id in unit["owned"] if row_id not in row_ids]
            target["owned"].extend(row_ids)

        def split_comparison(units):
            source = holding(units, ISO_DATE_COUNT + "joni")
            target = next(unit for unit in units.values()
                          if unit["shard_id"] == "trino-operations" and unit is not source)
            move(units, [ISO_DATE_COUNT + "joni"], target)

        def detach_representative(units):
            next(unit for unit in units.values() if unit["representatives"])["representatives"].pop()

        cases = (
            (split_comparison, "not measured beside its joni comparator"),
            (detach_representative, "representatives missing"),
        )
        # The planned units pass before any mutation.
        VALIDATOR.check(self.units, self.manifest_rows, self.representatives, self.protocols)
        for mutate, message in cases:
            with self.subTest(mutation=mutate.__name__):
                units = copy.deepcopy(self.units)
                mutate(units)
                with self.assertRaisesRegex(ValueError, message):
                    VALIDATOR.check(units, self.manifest_rows, self.representatives, self.protocols)


if __name__ == "__main__":
    unittest.main()
