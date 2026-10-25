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
        self.write_session(self.session, self.shard)
        self.release = patch.object(preparation.released_artifact, "validate_baseline")
        self.release_mock = self.release.start()
        self.addCleanup(self.release.stop)
        self.receipt = preparation.build(self.session, self.shard)
        (self.session / preparation.RECEIPT).write_text(json.dumps(self.receipt))

    @staticmethod
    def write_session(session, shard, verified=()):
        identity = {key: "fixture" for key in ("baseline_campaign", "platform", "host_epoch", "instance_id",
                    "instance_type", "availability_zone", "regulator_snapshot_commit", "regulator_snapshot_sha256")}
        identity.update(shard=shard, replica="1")
        (session / "environment.txt").write_text("Linux host 6.12\nopenjdk version 25.0.4\n" + "".join(f"{key}={value}\n" for key, value in identity.items()))
        (session / "environment-manifest.txt").write_text("fixture\n")
        dispatch = preparation.load_dispatch(preparation.BASELINE / "shard-dispatch.tsv", shard)
        for route, handler, systems in preparation.applicable_routes(dispatch):
            directory = session / "routes" / route
            (directory / "semantic-reports").mkdir(parents=True)
            (directory / "logs").mkdir()
            (directory / "status.txt").write_text("status=complete\n")
            metadata = {"verification_only": "true", "protocol": "smoke", "shard_id": shard,
                        "route": route, "handler": handler, "candidate_commit": "fixture", "systems": ",".join(sorted(systems)),
                        "manifest_sha256": preparation.digest(preparation.BASELINE / "rows.tsv")}
            (directory / "run-metadata.txt").write_text("".join(f"{key}={value}\n" for key, value in metadata.items()))
            xml = "<testsuite>" + "".join(f'<testcase classname="{name}" name="contract"/>' for name in dispatch["semantic_tests"].split(",")) + "</testsuite>"
            (directory / "semantic-reports/TEST-fixture.xml").write_text(xml)
            native = str(route == "native-access").lower()
            (directory / "logs/semantic-tests.log").write_text(
                ("OK enabled\n" if route == "native-access" else "OK disabled\n") + "".join(f"{line}\n" for line in verified))
            args = SimpleNamespace(reports=directory / "semantic-reports", expected_tests=dispatch["semantic_tests"],
                                   route=route, handler=handler, native_access=native,
                                   semantic_log=directory / "logs/semantic-tests.log", rebar_verification=[])
            with (directory / "semantic-evidence.tsv").open("w") as output:
                writer = csv.writer(output, delimiter="\t", lineterminator="\n")
                writer.writerow(semantic_evidence.FIELDS)
                writer.writerows(semantic_evidence.build_evidence(args))
            (directory / "semantic-gate.tsv").write_text("route\ttests\toutcome\tsemantic_result_digest\n" +
                f'{route}\t{dispatch["semantic_tests"]}\taccepted\t{preparation.digest(directory / "semantic-evidence.tsv")}\n')

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

    def test_like_semantics_require_the_encoded_comparison_count(self):
        verified = ["Verified Slice adapter outputs and callback traces against pinned Trino",
                    "Verified 1 paired Trino/Joni operation results (SHA-256 fixture)",
                    "Verified 1 everyday Regulator/Joni operation results (SHA-256 fixture)",
                    "Verified 60 Trino LIKE lifecycle comparisons", "Verified 530255 Trino LIKE comparisons",
                    "Verified 810155 escaped Trino LIKE comparisons"]
        for count, accepted in (("9330", True), ("21770", False)):
            with self.subTest(count=count), tempfile.TemporaryDirectory() as temp:
                session = Path(temp)
                self.write_session(session, "trino-like", verified + [f"Verified {count} encoded Trino LIKE comparisons"])
                if accepted:
                    self.assertEqual(preparation.build(session, "trino-like")["shard_id"], "trino-like")
                else:
                    with self.assertRaisesRegex(ValueError, "mode or differential"):
                        preparation.build(session, "trino-like")

    def test_specialized_semantics_require_the_specialized_verifier(self):
        verified = ["Verified Slice adapter outputs and callback traces against pinned Trino",
                    "Verified 1 paired Trino/Joni operation results (SHA-256 fixture)",
                    "Verified 1 everyday Regulator/Joni operation results (SHA-256 fixture)"]
        specialized = "Verified 1 specialized Regulator/Joni operation results (SHA-256 fixture)"
        for shard in ("trino-specialized-patterns-a", "trino-specialized-patterns-b"):
            for lines, accepted in ((verified + [specialized], True), (verified, False)):
                with self.subTest(shard=shard, accepted=accepted), tempfile.TemporaryDirectory() as temp:
                    session = Path(temp)
                    self.write_session(session, shard, lines)
                    if accepted:
                        self.assertEqual(preparation.build(session, shard)["shard_id"], shard)
                    else:
                        with self.assertRaisesRegex(ValueError, "missing specialized Trino differential"):
                            preparation.build(session, shard)

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
