import copy
from pathlib import Path
import tempfile
import unittest
import io
import tarfile

import campaign
import collection
import fleet
import transport


class TestLanguageCampaign(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        source = self.root / "source"
        manifest = collection.prepare(source)
        manifest["cases"] = manifest["cases"][:1]
        collection.save(source / "manifest.json", manifest)
        self.directory = self.root / "fleet"
        self.plan = fleet.prepare(source, self.directory)

    def test_smoke_and_primary_preserve_language_and_replica_coverage(self):
        smoke = campaign.LanguageCampaign([self.directory], "smoke")
        primary = campaign.LanguageCampaign([self.directory], "primary")
        self.assertEqual(len(smoke.batches), 9)
        self.assertEqual(len(primary.batches), 27)
        self.assertTrue(all(batch["replica"] == 1 for _, batch in smoke.batches.values()))
        self.assertEqual(primary.deadline_seconds(64, 5400), 10800)
        self.assertEqual(primary.deadline_seconds(9, 5400, 1), 54000)

    def test_package_is_reproducible_and_binds_exact_jobs(self):
        workload = campaign.LanguageCampaign([self.directory], "primary")
        identity = next(iter(workload.batches))
        path = self.root / "package.tar.gz"
        checksum = workload.package(identity, path)
        self.assertEqual(workload.package(identity, path), checksum)
        expected = workload.expected_package(identity)
        receipt = {"schema_version": 1, "workload": "language-batch",
                   "platform": expected["batch"]["platform"], "shard_id": expected["batch"]["shard"],
                   "replica_id": str(expected["batch"]["replica"]), "plan_sha256": expected["plan_sha256"],
                   "batch_sha256": collection.digest(collection.encode(expected)),
                   "exports": dict.fromkeys(expected["batch"]["jobs"], "hash")}
        workload.validate_receipt(identity, receipt)
        for field in ("platform", "plan_sha256", "batch_sha256"):
            broken = copy.deepcopy(receipt)
            broken[field] = "different"
            with self.assertRaisesRegex(ValueError, "frozen batch"):
                workload.validate_receipt(identity, broken)
        receipt["exports"] = {}
        with self.assertRaisesRegex(ValueError, "frozen batch"):
            workload.validate_receipt(identity, receipt)

    def test_required_smoke_pairs_extend_representatives_and_fail_closed(self):
        manifest = {"cases": [{"id": "first", "model": "count"}, {"id": "target", "model": "count"}],
                    "smoke_required_pairs": [["target", "java"]]}
        plan = {"partitions": [{"id": "a", "case": "first", "language": "java"},
                               {"id": "b", "case": "target", "language": "java"}]}
        self.assertEqual(campaign.LanguageCampaign.smoke_partitions(manifest, plan), {"a", "b"})
        for pair in (["missing", "java"], ["target", "re2"], "target", [["target"], "java"]):
            manifest["smoke_required_pairs"] = [pair]
            with self.subTest(pair=pair), self.assertRaises(ValueError):
                campaign.LanguageCampaign.smoke_partitions(manifest, plan)

    def test_changed_plan_and_missing_replicas_cannot_resume(self):
        workload = campaign.LanguageCampaign([self.directory], "primary")
        identity = next(iter(workload.batches))
        self.plan["host_batches"].pop()
        collection.save(self.directory / "plan.json", self.plan)
        with self.assertRaisesRegex(ValueError, "plan changed"):
            workload.expected_package(identity)
        with self.assertRaisesRegex(ValueError, "batch assignments"):
            campaign.LanguageCampaign([self.directory], "primary")

    def test_archive_transport_checks_assignment_checksum_and_paths(self):
        workload = campaign.LanguageCampaign([self.directory], "primary")
        identity = next(iter(workload.batches))
        path = self.root / "package.tar.gz"
        checksum = workload.package(identity, path)
        package = workload.expected_package(identity)
        batch = package["batch"]
        self.assertEqual(transport.unpack(path, self.root / "unpacked", checksum,
                                          batch["platform"], batch["shard"], batch["replica"]), package)
        with self.assertRaisesRegex(ValueError, "checksum"):
            transport.unpack(path, self.root / "bad-hash", "changed", batch["platform"], batch["shard"], 1)
        with self.assertRaisesRegex(ValueError, "assignment"):
            transport.unpack(path, self.root / "bad-host", checksum, "r8i", batch["shard"], 1)
        for index, name in enumerate(("../escape", "/absolute", "link")):
            with tarfile.open(path, "w:gz") as archive:
                member = tarfile.TarInfo(name)
                if name == "link":
                    member.type = tarfile.SYMTYPE
                    member.linkname = "../escape"
                archive.addfile(member, io.BytesIO())
            with self.assertRaisesRegex(ValueError, "unsafe"):
                transport.unpack(path, self.root / f"unsafe-{index}", collection.digest(path.read_bytes()),
                                 batch["platform"], batch["shard"], 1)


if __name__ == "__main__":
    unittest.main()
