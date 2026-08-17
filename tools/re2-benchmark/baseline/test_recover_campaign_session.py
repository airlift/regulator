#!/usr/bin/env python3

import importlib.util
import unittest
from pathlib import Path


BASELINE = Path(__file__).resolve().parent


def load_module(name, path):
    specification = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


RECOVERY = load_module(
    "recover_campaign_session",
    BASELINE / "recover-campaign-session.py")


class TestRecoverCampaignSession(unittest.TestCase):
    def testAcceptsOnlyVerifiedCleanupAfterDownloadedResultParserRace(self):
        cleanup = {
            "instances_terminated": "verified",
            "iam_removed": "verified",
            "bucket_removed": "verified",
            "cleanup_status": "verified",
            "run_status": "failed",
            "failure_classification": "none",
        }
        log = (
            "Downloaded arm results\n"
            "/workspace/tools/re2-benchmark/aws/run-campaign.sh: line 1893: "
            "syntax error near unexpected token `done'\n")

        RECOVERY.verify_recovery_context(cleanup, log)

    def testRejectsOrdinaryCampaignFailure(self):
        cleanup = {
            "instances_terminated": "verified",
            "iam_removed": "verified",
            "bucket_removed": "verified",
            "cleanup_status": "verified",
            "run_status": "failed",
            "failure_classification": "none",
        }

        with self.assertRaisesRegex(ValueError, "parser-race signature"):
            RECOVERY.verify_recovery_context(cleanup, "intel run failed with status 1\n")

    def testRejectsIncompleteCleanup(self):
        cleanup = {
            "instances_terminated": "verified",
            "iam_removed": "verified",
            "bucket_removed": "preserved",
            "cleanup_status": "incomplete",
            "run_status": "failed",
            "failure_classification": "none",
        }
        log = (
            "Downloaded intel results\n"
            "run-campaign.sh: line 1893: syntax error near unexpected token `done'\n")

        with self.assertRaisesRegex(ValueError, "cleanup is not verified"):
            RECOVERY.verify_recovery_context(cleanup, log)


if __name__ == "__main__":
    unittest.main()
