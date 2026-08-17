#!/usr/bin/env python3

import csv
import hashlib
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


BASELINE_DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BASELINE_DIRECTORY))

from acceptance import (  # noqa: E402
    EVIDENCE_FIELDS,
    PROTOCOL_QUALIFICATION_FIELDS,
    RECEIPT_FIELDS,
    SESSION_FIELDS,
    ValidationError,
    validate_campaign,
    validate_confirmation_host_results,
    validate_host_results,
    verify_protocol_qualification,
)


class TestAcceptance(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary_directory.name)
        self.manifest = self.directory / "rows.tsv"
        write_tsv(
            self.manifest,
            ("row_id", "shard_id", "suite", "system"),
            [
                ("alpha/a/engine", "alpha", "suite-a", "engine"),
                ("alpha/a/native", "alpha", "suite-a", "native"),
                ("beta/b/engine", "beta", "suite-b", "engine"),
                ("beta/b/native", "beta", "suite-b", "native"),
            ])

    def tearDown(self):
        self.temporary_directory.cleanup()

    def write_session(self, **changes):
        values = {
            "schema_version": "2",
            "campaign_id": "campaign",
            "platform": "c8i",
            "shard_id": "alpha",
            "replica_id": "1",
            "instance_id": "i-00000000000000001",
            "host_epoch": "epoch-001",
            "architecture": "intel",
            "instance_type": "c8i.2xlarge",
            "availability_zone": "us-west-2a",
            "systems": "engine,native",
        }
        values.update(changes)
        path = self.directory / "session.tsv"
        write_dict_tsv(path, SESSION_FIELDS, [values])
        return path

    def write_observed(self, rows):
        path = self.directory / "observed.tsv"
        write_tsv(path, ("row_id", "shard_id", "system", "score"), rows)
        return path

    def valid_observed(self):
        return self.write_observed([
            ("alpha/a/engine", "alpha", "engine", "1.0"),
            ("alpha/a/native", "alpha", "native", "2.0"),
        ])

    def validate(self, session, observed, *, confirmation=False, qualified=False):
        route = self.directory / "routes" / "native-access"
        if route.parent.exists():
            shutil.rmtree(route.parent)
        route.mkdir(parents=True)
        shutil.copyfile(session, route / "route-session.tsv")
        shutil.copyfile(observed, route / "observed-rows.tsv")
        observed_digest = hashlib.sha256((route / "observed-rows.tsv").read_bytes()).hexdigest()
        (route / "observed-rows.tsv.sha256").write_text(
            f"{observed_digest}  observed-rows.tsv\n", encoding="utf-8")
        (route / "semantic-gate.tsv").write_text("contract\n", encoding="utf-8")
        (route / "semantic-evidence.tsv").write_text("test\tstatus\nfixture\tpassed\n", encoding="utf-8")
        (route / "calibration.tsv").write_text("calibration\n", encoding="utf-8")
        (route / "run-metadata.txt").write_text(
            f"candidate_commit={'a' * 40}\n"
            f"engine_tree={'b' * 40}\n"
            "heap_size=8g\n"
            f"protocol_qualification_required={'true' if qualified else 'false'}\n"
            f"protocol_representative_count={1 if qualified else 0}\n",
            encoding="utf-8")
        (route / "status.txt").write_text("status=complete\n", encoding="utf-8")
        (route / "raw").mkdir()
        (route / "logs").mkdir()
        raw = route / "raw" / "benchmark.json"
        log = route / "logs" / "benchmark.log"
        raw.write_text("[]\n", encoding="utf-8")
        log.write_text("complete\n", encoding="utf-8")
        (route / "raw-artifacts.sha256").write_text(
            f"{hashlib.sha256(log.read_bytes()).hexdigest()}  logs/benchmark.log\n"
            f"{hashlib.sha256(raw.read_bytes()).hexdigest()}  raw/benchmark.json\n",
            encoding="utf-8")
        artifacts = [
            "route-session.tsv", "semantic-gate.tsv", "semantic-evidence.tsv", "calibration.tsv",
            "observed-rows.tsv", "observed-rows.tsv.sha256", "raw-artifacts.sha256",
            "run-metadata.txt", "status.txt",
        ]
        if qualified:
            write_dict_tsv(route / "protocol-qualification.tsv", PROTOCOL_QUALIFICATION_FIELDS, ({
                "manifest_row_id": "alpha/a/engine",
                "system": "engine",
                "route": "native-access",
                "benchmark": "fixture.Benchmark.run",
                "parameters": "-",
                "reference_protocol": "5-forks-10x1s",
                "specialized_protocol": "5-independent-jvms-10x50ms-warmup-10x50ms-measurement",
                "reference_score": "100",
                "specialized_score": "101",
                "score_unit": "ns/op",
                "reference_sample_count": "50",
                "specialized_sample_count": "50",
                "reference_cv": "0.01",
                "specialized_cv": "0.01",
                "relative_difference": "0.01",
                "maximum_relative_difference": "0.05",
                "outcome": "accepted",
            },))
            artifacts.append("protocol-qualification.tsv")
        write_dict_tsv(self.directory / "route-evidence.tsv", EVIDENCE_FIELDS, ({
            "route": "native-access",
            "artifact": f"routes/native-access/{artifact}",
            "verification": "sha256",
            "sha256": hashlib.sha256((route / artifact).read_bytes()).hexdigest(),
        } for artifact in artifacts))
        validator = validate_confirmation_host_results if confirmation else validate_host_results
        return validator(self.manifest, session, observed)

    def test_accepts_exact_host_rows(self):
        receipt = self.validate(self.write_session(), self.valid_observed())
        self.assertEqual(receipt["row_count"], "2")
        self.assertEqual(receipt["systems"], "engine,native")
        self.assertEqual(len(receipt["manifest_sha256"]), 64)
        self.assertEqual(receipt["candidate_commit"], "a" * 40)
        self.assertEqual(receipt["engine_tree"], "b" * 40)
        self.assertEqual(receipt["candidate_archive_sha256"], "not-recorded")
        self.assertEqual(
            receipt["evidence_manifest_sha256"],
            hashlib.sha256((self.directory / "route-evidence.tsv").read_bytes()).hexdigest())

    def test_accepts_qualified_protocol_evidence(self):
        receipt = self.validate(self.write_session(), self.valid_observed(), qualified=True)
        self.assertEqual("2", receipt["row_count"])

    def test_rejects_protocol_evidence_with_too_few_samples(self):
        path = self.directory / "protocol.tsv"
        write_dict_tsv(path, PROTOCOL_QUALIFICATION_FIELDS, ({
            "manifest_row_id": "row",
            "system": "engine",
            "route": "native-access",
            "benchmark": "fixture.Benchmark.run",
            "parameters": "-",
            "reference_protocol": "5-forks-10x1s",
            "specialized_protocol": "5-independent-jvms-10x300ms-warmup-10x50ms-measurement",
            "reference_score": "100",
            "specialized_score": "101",
            "score_unit": "ns/op",
            "reference_sample_count": "49",
            "specialized_sample_count": "50",
            "reference_cv": "0.01",
            "specialized_cv": "0.01",
            "relative_difference": "0.01",
            "maximum_relative_difference": "0.05",
            "outcome": "accepted",
        },))

        with self.assertRaisesRegex(ValidationError, "fewer than 50 protocol samples"):
            verify_protocol_qualification(path, "native-access", {"engine"}, 1)

    def test_rejects_protocol_evidence_with_too_many_samples(self):
        path = self.directory / "protocol.tsv"
        row = self.protocol_row(reference_sample_count="51", specialized_sample_count="51")
        write_dict_tsv(path, PROTOCOL_QUALIFICATION_FIELDS, (row,))

        with self.assertRaisesRegex(ValidationError, "exactly 50 protocol samples"):
            verify_protocol_qualification(path, "native-access", {"engine"}, 1)

    def test_protocol_median_difference_boundary(self):
        path = self.directory / "protocol.tsv"
        for specialized_score, accepted in (
                (104.9999999999, True),
                (105.0, True),
                (105.0000000001, False)):
            with self.subTest(specialized_score=specialized_score):
                relative_difference = abs(specialized_score - 100) / 100
                row = self.protocol_row(
                    specialized_score=str(specialized_score),
                    relative_difference=str(relative_difference))
                write_dict_tsv(path, PROTOCOL_QUALIFICATION_FIELDS, (row,))

                if accepted:
                    verify_protocol_qualification(path, "native-access", {"engine"}, 1)
                else:
                    with self.assertRaisesRegex(ValidationError, "exceeds the protocol threshold"):
                        verify_protocol_qualification(path, "native-access", {"engine"}, 1)

    def test_rejects_protocol_evidence_with_high_relative_standard_error(self):
        path = self.directory / "protocol.tsv"
        row = self.protocol_row(reference_cv="0.36", specialized_cv="0.01")
        write_dict_tsv(path, PROTOCOL_QUALIFICATION_FIELDS, (row,))

        with self.assertRaisesRegex(ValidationError, "relative standard error"):
            verify_protocol_qualification(path, "native-access", {"engine"}, 1)

    def test_accepts_protocol_evidence_at_exact_relative_standard_error_limit(self):
        path = self.directory / "protocol.tsv"
        row = self.protocol_row(
            reference_cv="0.35355339059327384",
            specialized_cv="0.35355339059327384")
        write_dict_tsv(path, PROTOCOL_QUALIFICATION_FIELDS, (row,))

        verify_protocol_qualification(path, "native-access", {"engine"}, 1)

    def test_relative_standard_error_boundary_for_both_protocols(self):
        path = self.directory / "protocol.tsv"
        for changed_protocol in ("reference_cv", "specialized_cv"):
            for relative_standard_error, accepted in (
                    (0.04999999999890436, True),
                    (0.05, True),
                    (0.05000000000109563, False)):
                with self.subTest(
                        changed_protocol=changed_protocol,
                        relative_standard_error=relative_standard_error):
                    row = self.protocol_row(**{
                        changed_protocol: str(relative_standard_error * (50 ** 0.5)),
                    })
                    write_dict_tsv(path, PROTOCOL_QUALIFICATION_FIELDS, (row,))

                    if accepted:
                        verify_protocol_qualification(path, "native-access", {"engine"}, 1)
                    else:
                        with self.assertRaisesRegex(ValidationError, "relative standard error"):
                            verify_protocol_qualification(path, "native-access", {"engine"}, 1)

    def test_rejects_forged_protocol_derived_values(self):
        path = self.directory / "protocol.tsv"
        row = self.protocol_row(
            reference_score="100",
            specialized_score="110",
            relative_difference="0.01")
        write_dict_tsv(path, PROTOCOL_QUALIFICATION_FIELDS, (row,))

        with self.assertRaisesRegex(ValidationError, "relative difference does not match"):
            verify_protocol_qualification(path, "native-access", {"engine"}, 1)

    def test_rejects_nonfinite_protocol_statistics(self):
        path = self.directory / "protocol.tsv"
        write_dict_tsv(path, PROTOCOL_QUALIFICATION_FIELDS, (self.protocol_row(reference_cv="nan"),))

        with self.assertRaisesRegex(ValidationError, "invalid protocol statistics"):
            verify_protocol_qualification(path, "native-access", {"engine"}, 1)

    @staticmethod
    def protocol_row(**changes):
        row = {
            "manifest_row_id": "row",
            "system": "engine",
            "route": "native-access",
            "benchmark": "fixture.Benchmark.run",
            "parameters": "-",
            "reference_protocol": "5-forks-10x1s",
            "specialized_protocol": "5-independent-jvms-10x300ms-warmup-10x50ms-measurement",
            "reference_score": "100",
            "specialized_score": "101",
            "score_unit": "ns/op",
            "reference_sample_count": "50",
            "specialized_sample_count": "50",
            "reference_cv": "0.01",
            "specialized_cv": "0.01",
            "relative_difference": "0.01",
            "maximum_relative_difference": "0.05",
            "outcome": "accepted",
        }
        row.update(changes)
        return row

    def test_rejects_nested_raw_artifact_tampering(self):
        session = self.write_session()
        observed = self.valid_observed()
        self.validate(session, observed)
        (self.directory / "routes/native-access/raw/benchmark.json").write_text("tampered\n")

        with self.assertRaisesRegex(ValidationError, "raw artifact checksum mismatch"):
            validate_host_results(self.manifest, session, observed)

    def test_rejects_route_metadata_tampering(self):
        session = self.write_session()
        observed = self.valid_observed()
        self.validate(session, observed)
        (self.directory / "routes/native-access/run-metadata.txt").write_text(
            f"candidate_commit={'f' * 40}\nengine_tree={'b' * 40}\n")

        with self.assertRaisesRegex(ValidationError, "host evidence checksum mismatch"):
            validate_host_results(self.manifest, session, observed)

    def test_rejects_unlisted_route_evidence(self):
        session = self.write_session()
        observed = self.valid_observed()
        self.validate(session, observed)
        (self.directory / "routes/native-access/normalized-output.tsv").write_text("normalized\n")

        with self.assertRaisesRegex(ValidationError, "route 'native-access' evidence is incomplete"):
            validate_host_results(self.manifest, session, observed)

    def test_accepts_explicit_system_applicability(self):
        observed = self.write_observed([("alpha/a/engine", "alpha", "engine", "1.0")])
        receipt = self.validate(self.write_session(systems="engine"), observed)
        self.assertEqual(receipt["row_count"], "1")

    def test_primary_host_rejects_confirmation_replica(self):
        with self.assertRaisesRegex(ValidationError, "replica_id must be 1, 2, or 3"):
            self.validate(self.write_session(replica_id="4"), self.valid_observed())

    def test_confirmation_host_accepts_only_replica_four(self):
        receipt = self.validate(
            self.write_session(replica_id="4"), self.valid_observed(), confirmation=True)
        self.assertEqual(receipt["replica_id"], "4")

        with self.assertRaisesRegex(ValidationError, "confirmation replica_id must be 4"):
            self.validate(self.write_session(replica_id="3"), self.valid_observed(), confirmation=True)

    def test_rejects_missing_host_row(self):
        observed = self.write_observed([("alpha/a/engine", "alpha", "engine", "1.0")])
        with self.assertRaisesRegex(ValidationError, "missing rows"):
            self.validate(self.write_session(), observed)

    def test_rejects_duplicate_host_row(self):
        observed = self.write_observed([
            ("alpha/a/engine", "alpha", "engine", "1.0"),
            ("alpha/a/engine", "alpha", "engine", "1.1"),
            ("alpha/a/native", "alpha", "native", "2.0"),
        ])
        with self.assertRaisesRegex(ValidationError, "duplicate rows"):
            self.validate(self.write_session(), observed)

    def test_rejects_unexpected_host_row(self):
        observed = self.write_observed([
            ("alpha/a/engine", "alpha", "engine", "1.0"),
            ("alpha/a/native", "alpha", "native", "2.0"),
            ("beta/b/native", "beta", "native", "3.0"),
        ])
        with self.assertRaisesRegex(ValidationError, "unexpected rows"):
            self.validate(self.write_session(), observed)

    def test_rejects_mislabeled_host_row(self):
        observed = self.write_observed([
            ("alpha/a/engine", "alpha", "native", "1.0"),
            ("alpha/a/native", "alpha", "native", "2.0"),
        ])
        with self.assertRaisesRegex(ValidationError, "system is 'native', expected 'engine'"):
            self.validate(self.write_session(), observed)

    def build_campaign(self, replicas=(1, 2, 3)):
        platforms = self.directory / "platforms.tsv"
        write_tsv(
            platforms,
            ("platform", "architecture", "instance_type"),
            [
                ("c8i", "intel", "c8i.2xlarge"),
                ("c8g", "arm", "c8g.2xlarge"),
                ("c9g", "arm", "c9g.2xlarge"),
            ])
        shards = self.directory / "shards.tsv"
        write_tsv(shards, ("shard_id",), [("alpha",), ("beta",)])

        receipts = []
        sequence = 0
        for platform, architecture in (("c8i", "intel"), ("c8g", "arm"), ("c9g", "arm")):
            for shard_id in ("alpha", "beta"):
                for replica_id in replicas:
                    sequence += 1
                    session = self.write_session(
                        platform=platform,
                        shard_id=shard_id,
                        replica_id=str(replica_id),
                        instance_id=f"i-{sequence:017x}",
                        host_epoch=f"epoch-{sequence:03d}",
                        architecture=architecture,
                        instance_type=f"{platform}.2xlarge")
                    observed = self.directory / f"observed-{sequence}.tsv"
                    write_tsv(observed, ("row_id", "shard_id", "system"), [
                        (f"{shard_id}/{'a' if shard_id == 'alpha' else 'b'}/engine", shard_id, "engine"),
                        (f"{shard_id}/{'a' if shard_id == 'alpha' else 'b'}/native", shard_id, "native"),
                    ])
                    receipts.append(self.validate(session, observed))
        receipts_path = self.directory / "accepted-sessions.tsv"
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, receipts)
        return platforms, shards, receipts_path, receipts

    def test_accepts_complete_campaign(self):
        platforms, shards, receipts_path, _ = self.build_campaign()
        result = validate_campaign(self.manifest, platforms, shards, receipts_path, "campaign")
        self.assertEqual(result["accepted_sessions"], 18)
        self.assertEqual(result["accepted_rows"], 36)

    def test_accepts_complete_smoke_campaign(self):
        platforms, shards, receipts_path, _ = self.build_campaign((1,))
        result = validate_campaign(
            self.manifest,
            platforms,
            shards,
            receipts_path,
            "campaign",
            (1,))
        self.assertEqual(result["accepted_sessions"], 6)
        self.assertEqual(result["accepted_rows"], 12)

    def test_scoped_campaign_preserves_full_manifest_identity(self):
        platforms, shards, receipts_path, receipts = self.build_campaign()
        selected = [row for row in receipts if row['shard_id'] == 'alpha']
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, selected)
        result = validate_campaign(self.manifest, platforms, shards, receipts_path, 'campaign', selected_shards=['alpha'])
        self.assertEqual(result['accepted_sessions'], 9)
        self.assertEqual(result['accepted_rows'], 18)
        for scope in ([], ['unknown'], ['alpha', 'alpha']):
            with self.assertRaisesRegex(ValidationError, 'selected shards'):
                validate_campaign(self.manifest, platforms, shards, receipts_path, 'campaign', selected_shards=scope)
        with self.assertRaises(ValidationError):
            validate_campaign(self.manifest, platforms, shards, receipts_path, 'campaign')
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, selected[:-1])
        with self.assertRaises(ValidationError):
            validate_campaign(self.manifest, platforms, shards, receipts_path, 'campaign', selected_shards=['alpha'])

    def test_rejects_missing_replica(self):
        platforms, shards, receipts_path, receipts = self.build_campaign()
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, receipts[:-1])
        with self.assertRaisesRegex(ValidationError, "campaign session coverage failed"):
            validate_campaign(self.manifest, platforms, shards, receipts_path, "campaign")

    def test_rejects_duplicate_replica(self):
        platforms, shards, receipts_path, receipts = self.build_campaign()
        receipts[-1]["replica_id"] = "2"
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, receipts)
        with self.assertRaisesRegex(ValidationError, "campaign session coverage failed"):
            validate_campaign(self.manifest, platforms, shards, receipts_path, "campaign")

    def test_primary_campaign_rejects_confirmation_replica(self):
        platforms, shards, receipts_path, receipts = self.build_campaign()
        receipts[-1]["replica_id"] = "4"
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, receipts)
        with self.assertRaisesRegex(ValidationError, "replica_id must be 1, 2, or 3"):
            validate_campaign(self.manifest, platforms, shards, receipts_path, "campaign")

    def test_rejects_reused_instance(self):
        platforms, shards, receipts_path, receipts = self.build_campaign()
        receipts[1]["instance_id"] = receipts[0]["instance_id"]
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, receipts)
        with self.assertRaisesRegex(ValidationError, "reuses instance IDs"):
            validate_campaign(self.manifest, platforms, shards, receipts_path, "campaign")

    def test_rejects_reused_host_epoch(self):
        platforms, shards, receipts_path, receipts = self.build_campaign()
        receipts[1]["host_epoch"] = receipts[0]["host_epoch"]
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, receipts)
        with self.assertRaisesRegex(ValidationError, "reuses host epochs"):
            validate_campaign(self.manifest, platforms, shards, receipts_path, "campaign")

    def test_rejects_platform_identity_mismatch(self):
        platforms, shards, receipts_path, receipts = self.build_campaign()
        receipts[0]["architecture"] = "arm"
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, receipts)
        with self.assertRaisesRegex(ValidationError, "architecture is 'arm', expected 'intel'"):
            validate_campaign(self.manifest, platforms, shards, receipts_path, "campaign")

    def test_rejects_incomplete_system_coverage(self):
        platforms, shards, receipts_path, receipts = self.build_campaign()
        receipts[0]["systems"] = "engine"
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, receipts)
        with self.assertRaisesRegex(ValidationError, "systems do not cover shard"):
            validate_campaign(self.manifest, platforms, shards, receipts_path, "campaign")

    def test_rejects_stale_manifest_receipt(self):
        platforms, shards, receipts_path, receipts = self.build_campaign()
        receipts[0]["manifest_sha256"] = "0" * 64
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, receipts)
        with self.assertRaisesRegex(ValidationError, "manifest_sha256 does not match"):
            validate_campaign(self.manifest, platforms, shards, receipts_path, "campaign")

    def test_rejects_multiple_candidate_identities(self):
        platforms, shards, receipts_path, receipts = self.build_campaign()
        receipts[0]["candidate_commit"] = "f" * 40
        write_dict_tsv(receipts_path, RECEIPT_FIELDS, receipts)
        with self.assertRaisesRegex(ValidationError, "multiple candidate identities"):
            validate_campaign(self.manifest, platforms, shards, receipts_path, "campaign")


def write_tsv(path, fields, rows):
    with path.open("w", newline="") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        writer.writerow(fields)
        writer.writerows(rows)


def write_dict_tsv(path, fields, rows):
    with path.open("w", newline="") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    unittest.main()
