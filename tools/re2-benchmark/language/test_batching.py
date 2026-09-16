import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parent))
import batching


class TestBatching(unittest.TestCase):
    def setUp(self):
        self.parts = [{"id": f"case-{index:04d}-java", "case": f"case/{index}", "language": "java"} for index in range(6)]
        self.counts = {part["id"]: 1 for part in self.parts}
        self.policy = {"schema_version": 1, "source_sha256": "a" * 64,
                       "partition_seconds": {batching.partition_key(part): (900 if index == 0 else 300)
                                             for index, part in enumerate(self.parts)},
                       "isolated_pairs": [["case/0", "java"]], "max_batch_seconds": 1000, "bootstrap_seconds": 600}

    def test_packing_preserves_complete_comparisons_and_isolates_slow_work(self):
        groups = batching.groups(self.parts, self.counts, 8, self.policy)
        self.assertIn([self.parts[0]["id"]], groups)
        self.assertEqual(sorted(len(group) for group in groups), [1, 2, 3])
        flattened = [identity for group in groups for identity in group]
        self.assertEqual(sorted(flattened), sorted(self.counts))
        self.assertEqual(len(flattened), len(set(flattened)))
        self.assertEqual(groups, batching.groups(list(reversed(self.parts)), self.counts, 8, self.policy))

    def test_operation_budget_still_limits_fast_packing(self):
        groups = batching.groups(self.parts, self.counts, 2, self.policy)
        self.assertEqual(sorted(map(len, groups)), [1, 1, 2, 2])

    def test_rejects_missing_estimates_unknown_isolation_and_overlong_comparisons(self):
        for change in ("missing", "unknown", "overlong", "nan", "deadline"):
            policy = copy.deepcopy(self.policy)
            if change == "missing":
                policy["partition_seconds"].pop("case/0/java")
            elif change == "unknown":
                policy["isolated_pairs"].append(["missing", "java"])
            elif change == "overlong":
                policy["partition_seconds"]["case/0/java"] = 1200
            elif change == "nan":
                policy["partition_seconds"]["case/0/java"] = float("nan")
            else:
                policy["bootstrap_seconds"] = 5000
            with self.subTest(change=change), self.assertRaises(ValueError):
                batching.groups(self.parts, self.counts, 8, policy)


if __name__ == "__main__":
    unittest.main()
