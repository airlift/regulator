import csv
import json
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import semantic_preparation as preparation
import semantic_evidence
import remeasure


class TestSemanticPreparation(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.session = Path(self.temp.name)
        self.shard = "traditional-search"
        identity = {key: "fixture" for key in ("baseline_campaign", "platform", "host_epoch", "instance_id",
                    "instance_type", "availability_zone", "regulator_snapshot_commit", "regulator_snapshot_sha256")}
        identity.update(shard=self.shard, replica="1")
        (self.session / "environment.txt").write_text("Linux host 6.12\nopenjdk version 25.0.4\n" + "".join(f"{key}={value}\n" for key, value in identity.items()))
        (self.session / "environment-manifest.txt").write_text("fixture\n")
        dispatch = preparation.load_dispatch(preparation.BASELINE / "shard-dispatch.tsv", self.shard)
        for route, handler, systems in preparation.applicable_routes(dispatch):
            directory = self.session / "routes" / route
            (directory / "semantic-reports").mkdir(parents=True)
            (directory / "logs").mkdir()
            (directory / "status.txt").write_text("status=complete\n")
            metadata = {"verification_only": "true", "protocol": "smoke", "shard_id": self.shard,
                        "route": route, "handler": handler, "candidate_commit": "fixture", "systems": ",".join(sorted(systems)),
                        "manifest_sha256": preparation.digest(preparation.BASELINE / "rows.tsv")}
            (directory / "run-metadata.txt").write_text("".join(f"{key}={value}\n" for key, value in metadata.items()))
            xml = "<testsuite>" + "".join(f'<testcase classname="{name}" name="contract"/>' for name in dispatch["semantic_tests"].split(",")) + "</testsuite>"
            (directory / "semantic-reports/TEST-fixture.xml").write_text(xml)
            native = str(route == "native-access").lower()
            (directory / "logs/semantic-tests.log").write_text("OK enabled\n" if route == "native-access" else "OK disabled\n")
            args = SimpleNamespace(reports=directory / "semantic-reports", expected_tests=dispatch["semantic_tests"],
                                   route=route, handler=handler, native_access=native,
                                   semantic_log=directory / "logs/semantic-tests.log", rebar_verification=[])
            with (directory / "semantic-evidence.tsv").open("w") as output:
                writer = csv.writer(output, delimiter="\t", lineterminator="\n")
                writer.writerow(semantic_evidence.FIELDS)
                writer.writerows(semantic_evidence.build_evidence(args))
            (directory / "semantic-gate.tsv").write_text("route\ttests\toutcome\tsemantic_result_digest\n" +
                f'{route}\t{dispatch["semantic_tests"]}\taccepted\t{preparation.digest(directory / "semantic-evidence.tsv")}\n')
        self.release = patch.object(preparation.released_artifact, "validate_baseline")
        self.release_mock = self.release.start()
        self.addCleanup(self.release.stop)
        self.receipt = preparation.build(self.session, self.shard)
        (self.session / preparation.RECEIPT).write_text(json.dumps(self.receipt))

    def test_semantics_do_not_require_timing_or_allocation_smoke(self):
        self.assertEqual(preparation.validate(self.session), self.receipt)
        self.assertEqual(remeasure.preparation_receipt(self.session), self.receipt)
        self.release_mock.assert_called()
        self.assertFalse((self.session / "observed-rows.tsv").exists())

    def test_failed_test_is_rejected_even_with_reforged_gate(self):
        path = self.session / "routes/native-access/semantic-reports/TEST-fixture.xml"
        path.write_text(path.read_text().replace('/>', '><failure/></testcase>', 1))
        with self.assertRaisesRegex(ValueError, "not successful"):
            preparation.build(self.session, self.shard)

    def test_missing_route_is_rejected(self):
        shutil.rmtree(self.session / "routes/object-row")
        with self.assertRaisesRegex(ValueError, "every required route"):
            preparation.validate(self.session)

    def test_missing_receipt_cannot_fall_back_to_timing_acceptance(self):
        (self.session / preparation.RECEIPT).unlink()
        with self.assertRaisesRegex(ValueError, "missing semantic preparation receipt"):
            remeasure.preparation_receipt(self.session)

    def test_changed_raw_evidence_is_rejected(self):
        path = self.session / "routes/native-access/logs/semantic-tests.log"
        path.write_text(path.read_text() + "Changed raw evidence\n")
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            preparation.validate(self.session)

    def test_release_is_independently_revalidated(self):
        self.release_mock.side_effect = ValueError("release changed")
        with self.assertRaisesRegex(ValueError, "release changed"):
            preparation.validate(self.session)

    def test_wrong_mode_probe_is_rejected(self):
        path = self.session / "routes/native-access/logs/semantic-tests.log"
        path.write_text("OK disabled\n")
        with self.assertRaisesRegex(ValueError, "mode or differential"):
            preparation.build(self.session, self.shard)

    def test_missing_requested_test_is_rejected(self):
        path = self.session / "routes/native-access/semantic-reports/TEST-fixture.xml"
        path.write_text('<testsuite><testcase classname="Unrelated" name="test"/></testsuite>')
        with self.assertRaisesRegex(ValueError, "missing requested classes"):
            preparation.build(self.session, self.shard)


if __name__ == "__main__":
    unittest.main()
