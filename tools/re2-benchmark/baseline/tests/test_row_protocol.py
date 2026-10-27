import sys
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASELINE))
import row_protocol  # noqa: E402


class TestRowProtocol(unittest.TestCase):
    def test_rows_keep_the_protocol_their_whole_route_selected(self):
        protocols = row_protocol.load(BASELINE / "row-protocol.tsv")
        cases = {
            # A route with more than 30 candidate and Joni rows used 50 ms windows and the 5% gate.
            "trino-operations/everyday/delimiter/contains/regulator-native-access":
                "jmh-processes 5 10 50ms 10 50ms 0.05",
            "trino-operations/everyday/delimiter/contains/joni": "jmh-forks 1 10 50ms 10 50ms 0.05",
            # trino-like counted none of its 36 rows, so it kept one-second windows.
            "trino-like/EXACT_MATCH/regulator": "jmh-processes 5 10 1s 10 1s -",
            "trino-specialized-patterns-a/urlHostPort/extract/joni": "jmh-forks 1 10 1s 10 1s -",
        }
        for row_id, expected in cases.items():
            with self.subTest(row_id=row_id):
                protocol = protocols[row_id]
                self.assertEqual(expected, " ".join(protocol[field] for field in row_protocol.FIELDS[1:]))


if __name__ == "__main__":
    unittest.main()
