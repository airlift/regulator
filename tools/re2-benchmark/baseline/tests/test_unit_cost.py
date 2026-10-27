import sys
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASELINE))
import row_protocol  # noqa: E402
import unit_cost  # noqa: E402


def protocol(execution, processes="-", warmup_iterations="-", warmup_time="-",
             measurement_iterations="-", measurement_time="-"):
    return row_protocol.protocol("row", execution, processes, warmup_iterations, warmup_time,
                                 measurement_iterations, measurement_time)


class TestUnitCost(unittest.TestCase):
    def test_each_execution_charges_its_measurement_windows(self):
        cases = (
            (protocol("jmh-processes", 5, 10, "50ms", 10, "50ms"), 5),
            (protocol("jmh-processes", 5, 10, "300ms", 10, "50ms"), 17.5),
            (protocol("jmh-processes", 5, 10, "1s", 10, "1s"), 100),
            # Every JMH fork starts its own JVM.
            (protocol("jmh-forks", 1, 10, "50ms", 10, "50ms"), 3),
            (protocol("jmh-forks", 5, 10, "1s", 10, "1s"), 110),
        )
        for row_protocol_fields, seconds in cases:
            with self.subTest(protocol=row_protocol_fields):
                self.assertAlmostEqual(seconds, unit_cost.row_seconds(row_protocol_fields))

    def test_rows_of_one_method_and_protocol_share_one_selector_startup(self):
        bounded = protocol("jmh-processes", 5, 10, "50ms", 10, "50ms")
        rows = [
            {"row_id": "a", "system": "regulator-native-access", "benchmark": "Benchmark.search", "suite": "search"},
            {"row_id": "b", "system": "regulator-native-access", "benchmark": "Benchmark.search", "suite": "search"},
            {"row_id": "c", "system": "regulator-object-row", "benchmark": "Benchmark.search", "suite": "search"},
        ]
        protocols = {row["row_id"]: bounded for row in rows}
        # Three rows whose 50 ms windows add up to five seconds each, two selectors that each start a
        # compiler-hints JVM and five processes at two seconds, and one calibration of five forks plus their
        # parent.
        self.assertAlmostEqual(15 + 24 + 112, unit_cost.estimate(rows, protocols, calibration_invocations=1))
        self.assertAlmostEqual(15 + 24 + 132, unit_cost.estimate(rows, protocols, representative_count=1))
        # A specialized-pattern suite runs each workload in its own selector.
        specialized = [{**row, "suite": "trino-specialized-patterns"} for row in rows]
        self.assertAlmostEqual(15 + 36, unit_cost.estimate(specialized, protocols))
        # Another method, or another protocol of the same method, starts a selector of its own.
        longer = protocol("jmh-processes", 5, 10, "300ms", 10, "50ms")
        for split, row, row_protocol_fields in (
                ("method", {**rows[0], "row_id": "d", "benchmark": "Benchmark.match"}, bounded),
                ("protocol", {**rows[0], "row_id": "d"}, longer)):
            with self.subTest(split=split):
                self.assertAlmostEqual(
                    15 + 24 + unit_cost.row_seconds(row_protocol_fields) + 12,
                    unit_cost.estimate([*rows, row], {**protocols, "d": row_protocol_fields}))

    def test_trino_like_rows_are_charged_their_one_second_windows(self):
        rows = [row for row in row_protocol.read_tsv(BASELINE / "rows.tsv") if row["shard_id"] == "trino-like"]
        protocols = row_protocol.load(BASELINE / "row-protocol.tsv")
        self.assertEqual(36, len(rows))
        # Each of the three systems is one selector.
        self.assertAlmostEqual(36 * 100 + 3 * 12, unit_cost.estimate(rows, protocols))


if __name__ == "__main__":
    unittest.main()
