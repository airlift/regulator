import importlib.util
import sys
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parents[1]
SPECIFICATION = importlib.util.spec_from_file_location("validate_manifest", BASELINE / "validate-manifest.py")
VALIDATE_MANIFEST = importlib.util.module_from_spec(SPECIFICATION)
sys.modules["validate_manifest"] = VALIDATE_MANIFEST
SPECIFICATION.loader.exec_module(VALIDATE_MANIFEST)


class TestValidateManifest(unittest.TestCase):
    def testGenericJmhRouteRequiresEveryManifestSuite(self):
        rows = [{
            "shard_id": "trino-operations",
            "suite": "trino-everyday-operations",
            "system": "regulator-object-row",
        }]
        suite_rows = [{
            "shard_id": "trino-operations",
            "suite": "trino-public-operations",
            "systems": "regulator-native-access,regulator-object-row",
        }]
        dispatch_rows = [{
            "shard_id": "trino-operations",
            "native_access_handler": "trino-operations",
            "native_access_systems": "regulator-native-access,joni",
            "object_row_handler": "jmh",
            "object_row_systems": "regulator-object-row",
        }]

        with self.assertRaisesRegex(SystemExit, "generic JMH route has undeclared suites"):
            VALIDATE_MANIFEST.validate_jmh_route_coverage(rows, suite_rows, dispatch_rows)


if __name__ == "__main__":
    unittest.main()
