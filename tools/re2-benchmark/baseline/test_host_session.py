#!/usr/bin/env python3

import importlib.util
import sys
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parent
sys.path.insert(0, str(BASELINE))


def load_module(name, path):
    specification = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


HOST_SESSION = load_module("host_session", BASELINE / "host_session.py")
SHARD_RESULTS = load_module("host_session_shard_results", BASELINE / "shard_results.py")


class TestHostSession(unittest.TestCase):
    def testCurrentDispatchExactlyCoversEveryShardAndKeepsNativeFirst(self):
        manifest = SHARD_RESULTS.load_manifest(BASELINE / "rows.tsv")
        shards = sorted({row["shard_id"] for row in manifest})
        for shard in shards:
            dispatch = HOST_SESSION.load_dispatch(BASELINE / "shard-dispatch.tsv", shard)
            routes = HOST_SESSION.applicable_routes(dispatch)
            self.assertEqual("native-access", routes[0][0])
            route_systems = set().union(*(systems for _, _, systems in routes))
            manifest_systems = {row["system"] for row in manifest if row["shard_id"] == shard}
            self.assertEqual(manifest_systems, route_systems, shard)


if __name__ == "__main__":
    unittest.main()
