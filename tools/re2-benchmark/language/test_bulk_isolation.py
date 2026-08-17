"""Exercise bulk verification ordering without JVM or wall-clock dependencies."""

import threading
import unittest
from unittest.mock import patch

import bulk
import collection
import test_bulk


class TestBulkIsolation(unittest.TestCase):
    def setUp(self):
        test_bulk.TestBulkEvidence.setUp(self)
        self.verifier_thread = threading.get_ident()
        self.calls = []
        self.failure = None
        self.changed_trace = False

    def verified(self, command, prefix, engine, mode, *, hash_trace=False):
        self.assertEqual(threading.get_ident(), self.verifier_thread,
                         "Verification must run serially on the caller's pinned CPU")
        relative = prefix.relative_to(self.results).as_posix()
        self.calls.append(relative)
        if not relative.startswith("reference/") and self.failure:
            raise self.failure
        output = b"2:61,61\n" if hash_trace else b"2\n"
        if hash_trace and self.changed_trace and not relative.startswith("reference/"):
            output = b"2:62,62\n"
        prefix.mkdir(parents=True, exist_ok=True)
        (prefix / "verification-0.stdout").write_bytes(output)
        trace = "sha256:" + collection.digest(output) + "\n" if hash_trace else output.decode()
        return {"outcome": "completed", "trace": trace,
                "trace_sha256": collection.digest(trace.encode()), "command": command}

    def verify(self):
        self.results = self.root / "verified"
        with patch.object(collection, "runner_identity", return_value={"fixture": "runners"}), \
                patch.object(collection, "verify_command", side_effect=self.verified):
            return collection.verify(self.directory, self.results, "java", "classes", "native")

    def test_bulk_verification_runs_in_order_on_pinned_caller(self):
        evidence = self.verify()
        identities = [identity for identity, *_ in collection.observations(self.manifest)]
        expected = ["reference/example/execute", "reference/example/trace"]
        for identity in identities:
            expected.extend([identity + "/execute", identity + "/trace"])
        self.assertEqual(self.calls, expected)
        self.assertEqual(list(evidence["receipts"]), identities)
        self.assertEqual(collection.load(self.results / "verification.partial.json"), evidence["receipts"])
        bulk.validate_evidence(self.manifest, evidence, self.results)

    def test_inconsistent_timeout_stops_before_next_observation(self):
        self.failure = ValueError("nonreproducible timeout")
        with self.assertRaisesRegex(ValueError, "nonreproducible timeout"):
            self.verify()
        self.assertEqual(len(self.calls), 3)
        self.assertFalse((self.results / "verification.json").exists())

    def test_trace_mismatch_still_rejects_collection(self):
        self.changed_trace = True
        with self.assertRaisesRegex(ValueError, "differs from native source semantics"):
            self.verify()
        self.assertEqual(len(self.calls), 4)
        self.assertFalse((self.results / "verification.json").exists())


if __name__ == "__main__":
    unittest.main()
