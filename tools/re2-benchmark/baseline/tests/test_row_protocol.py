import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASELINE))
import row_protocol  # noqa: E402


class TestRowProtocol(unittest.TestCase):
    def test_generator_writes_the_checked_in_protocols(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "row-protocol.tsv"
            subprocess.run([sys.executable, BASELINE / "generate-row-protocol.py", "--output", output],
                           check=True, capture_output=True)
            self.assertEqual((BASELINE / "row-protocol.tsv").read_text(), output.read_text())

    def test_rows_keep_the_protocol_their_whole_route_selected(self):
        protocols = row_protocol.load(BASELINE / "row-protocol.tsv")
        cases = {
            # A route with more than 30 candidate and Joni rows used 50 ms windows and the 5% gate.
            "accepted-routes/BenchmarkBoundedCharacterClassCounter.generalMatcher/sourceLength-32768/"
            "workload-ascii/regulator-object-row": "jmh-processes 5 10 50ms 10 50ms 0.05",
            "lifecycle/BenchmarkDfaCache.growStateCacheOnNextInsert/-/regulator-native-access":
                "jmh-processes 5 10 1s 10 1s -",
            # trino-like counted none of its 36 rows, so it kept one-second windows.
            "trino-like/EXACT_MATCH/regulator": "jmh-processes 5 10 1s 10 1s -",
            "compile-public/BenchmarkRe2CompilerPhases.analyzeExpression/pattern-(.*)-(\\d+)-of-(\\d+)/"
            "regulator-native-access": "jmh-processes 5 10 300ms 10 50ms 0.05",
            "traditional-capture/compile/parse/compile/regulator-native-access":
                "jmh-processes 5 10 300ms 10 50ms 0.05",
            "traditional-search/failed-search/easy0/dfa/32768/regulator-object-row":
                "jmh-forks 5 10 1s 10 1s 0.05",
            "trino-operations/BenchmarkTrinoRegexp.contains/sourceLength-1024/workload-captureSparse/joni":
                "jmh-forks 1 10 50ms 10 50ms 0.05",
            "traditional-extra-easy2/failed-search/easy2/dfa/16777216/native-re2-before":
                "native-bracket 1 - - 5 1s -",
            "rebar-a/curated/04-ruff-noqa/tweaked/grep-captures/joni": "rebar - - 5s - 5s -",
            "retained-memory/retained-memory/joni": "memory-census - - - - - -",
        }
        for row_id, expected in cases.items():
            with self.subTest(row_id=row_id):
                protocol = protocols[row_id]
                self.assertEqual(expected, " ".join(protocol[field] for field in row_protocol.FIELDS[1:]))


if __name__ == "__main__":
    unittest.main()
