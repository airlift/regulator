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

EASY2_DFA = "traditional-extra-easy2/failed-search/easy2/dfa/"
# JMH splits -p values at commas, so this pattern can only be measured with its whole method.
COMMA_PATTERN = "compile-public/BenchmarkRe2CompileFocused.trinoRegexpCompileTotal/pattern-[,/]/regulator-native-access"
SMALL_BYTE_SET = (
    "accepted-routes/BenchmarkDfaSmallByteSet.countMatches/consumer-FIXED_THREE/inputShape-ABSENT/"
    "sourceLength-32768/")


def holding(units, row_id, role="owned"):
    return next(unit for unit in units.values() if row_id in unit[role])


class TestWorkUnits(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.units = work_units.load(BASELINE)
        cls.manifest_rows = row_protocol.read_tsv(BASELINE / "rows.tsv")
        cls.representatives = row_protocol.read_tsv(BASELINE / "protocol-representatives.tsv")
        cls.protocols = row_protocol.load(BASELINE / "row-protocol.tsv")

    def test_generator_writes_the_checked_in_units_and_summarizes_each_shard(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                [sys.executable, BASELINE / "generate-units.py", "--output-directory", temporary],
                check=True, capture_output=True, text=True)
            for name in ("units.tsv", "unit-rows.tsv"):
                with self.subTest(name=name):
                    self.assertEqual((BASELINE / name).read_text(), (Path(temporary) / name).read_text())
        # Each shard's unit minutes, by their smallest, median and largest unit.
        self.assertIn("like-dfa-single-use\tr8i.large\tr9g.large\t1\t12.5\t12.5\t12.5\t12.5\n", result.stdout)
        self.assertIn("lifecycle\tr8i.large\tr9g.large\t4\t12.3\t13.1\t13.1\t51.5\n", result.stdout)

    def test_route_scaling_groups_too_long_together_each_copy_their_native_bracket(self):
        # Both routes' eight one-second sizes and their native bracket are about 30 minutes.
        native_access = holding(self.units, EASY2_DFA + "16777216/regulator-native-access")
        object_row = holding(self.units, EASY2_DFA + "16777216/regulator-object-row")
        self.assertNotEqual(native_access["unit_id"], object_row["unit_id"])
        bracket = sorted(row_id for row_id in native_access["owned"] if row_id.endswith(("-before", "-after")))
        self.assertEqual(16, len(bracket))
        self.assertEqual(bracket, object_row["copies"])

    def test_a_method_holding_a_value_jmh_cannot_select_is_never_split(self):
        rows = [{"parameters": f"pattern={pattern};size={size}"} for pattern in ("a,b", "c") for size in ("1", "2")]
        self.assertIsNone(work_units.split_axis(rows, set()))

    def test_validator_rejects_units_that_break_a_measurement_requirement(self):
        def move(units, row_ids, target):
            for unit in units.values():
                unit["owned"] = [row_id for row_id in unit["owned"] if row_id not in row_ids]
            target["owned"].extend(row_ids)

        def split_scaling_group(units):
            row_id = EASY2_DFA + "4096/regulator-object-row"
            move(units, [row_id], holding(units, EASY2_DFA + "4096/regulator-native-access"))

        def drop_bracket_copy(units):
            holding(units, EASY2_DFA + "4096/native-re2-before", "copies")["copies"].remove(
                EASY2_DFA + "4096/native-re2-before")

        def break_selector_block(units):
            row_ids = [SMALL_BYTE_SET + system for system in ("regulator-native-access", "regulator-object-row")]
            source = holding(units, row_ids[0])
            target = next(unit for unit in units.values()
                          if unit["shard_id"] == "accepted-routes" and unit is not source)
            move(units, row_ids, target)

        def split_unselectable_value(units):
            source = holding(units, COMMA_PATTERN)
            target = next(unit for unit in units.values()
                          if unit["shard_id"] == "compile-public" and unit is not source)
            move(units, [COMMA_PATTERN], target)

        def detach_representative(units):
            next(unit for unit in units.values() if unit["representatives"])["representatives"].pop()

        cases = (
            (split_scaling_group, "scaling group"),
            (drop_bracket_copy, "not measured beside its native-re2 comparator"),
            (break_selector_block, "not a Cartesian block"),
            (split_unselectable_value, "has a value JMH cannot select and is split"),
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
