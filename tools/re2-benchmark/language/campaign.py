"""Language workload planning for the shared AWS resource controller. No AWS calls."""

import gzip
import io
import math
from pathlib import Path
import tarfile
import tempfile

import collection
import fleet


class LanguageCampaign:
    def __init__(self, directories, phase):
        if phase not in {"smoke", "primary"}:
            raise ValueError("language campaigns support smoke and primary; prepare a new plan for follow-up runs")
        self.phase = phase
        self.plans = {}
        self.batches = {}
        for directory in directories:
            directory = directory.resolve()
            plan = fleet.validate(directory)
            manifest = collection.load(directory / "source/manifest.json")
            suite = manifest["suite"]
            if suite in self.plans:
                raise ValueError("duplicate language suite")
            if phase == "primary" and plan["replicas"] != 3:
                raise ValueError("primary language campaign requires three independent replicas")
            self.plans[suite] = (directory, plan)
            selected = self.smoke_partitions(manifest, plan) if phase == "smoke" else None
            for batch in plan["host_batches"]:
                if selected is not None and (batch["replica"] != 1 or not any(
                        identity.split("/")[1] in selected for identity in batch["jobs"])):
                    continue
                self.batches[batch["id"]] = (suite, batch)
        if not self.batches:
            raise ValueError("language campaign has no host batches")

    @staticmethod
    def smoke_partitions(manifest, plan):
        cases = {case["id"]: case for case in manifest["cases"]}
        selected = {}
        for partition in plan["partitions"]:
            model = cases[partition["case"]].get("model", "lifecycle")
            selected.setdefault((partition["language"], model), partition["id"])
        result = set(selected.values())
        partitions = {(partition["case"], partition["language"]): partition["id"] for partition in plan["partitions"]}
        isolated = {partitions[tuple(pair)] for pair in plan.get("duration_policy", {}).get("isolated_pairs", [])}
        result.update(isolated)
        if "duration_policy" in plan:
            # Batches include isolated partitions in the same duration order.
            # Exercise the longest packed batch as well as every isolated one.
            packed = next((batch for batch in plan["host_batches"]
                           if all(identity.split("/")[1] not in isolated for identity in batch["jobs"])), None)
            if packed is not None:
                result.update(identity.split("/")[1] for identity in packed["jobs"])
        for pair in manifest.get("smoke_required_pairs", []):
            if not isinstance(pair, list) or len(pair) != 2 or not all(isinstance(value, str) for value in pair):
                raise ValueError("invalid required smoke case/language pair")
            if tuple(pair) not in partitions:
                raise ValueError("required smoke pair is absent from the selected plan")
            result.add(partitions[tuple(pair)])
        return result

    def deadline_seconds(self, concurrency, job_timeout, per_platform=None):
        # This is a controller safety deadline, not an expected completion time.
        waves = math.ceil(len(self.batches) / concurrency)
        if per_platform is not None:
            counts = {}
            for _, batch in self.batches.values():
                counts[batch['platform']] = counts.get(batch['platform'], 0) + 1
            waves = max(waves, max(math.ceil(count / per_platform) for count in counts.values()))
        return (waves + 1) * job_timeout

    def fingerprint(self):
        return [(str(directory), collection.digest((directory / "plan.json").read_bytes()))
                for directory, _ in self.plans.values()]

    def expected_package(self, identity):
        suite, batch = self.batches[identity]
        directory, plan = self.plans[suite]
        plan_hash = collection.digest(collection.encode(plan) + b"\n")
        # Plan files use collection.save's canonical JSON representation.
        if collection.digest((directory / "plan.json").read_bytes()) != plan_hash:
            raise ValueError("language plan changed during campaign")
        jobs = {job["id"]: job for job in plan["jobs"]}
        partitions = {partition["id"]: partition for partition in plan["partitions"]}
        return {"batch": batch, "plan_sha256": plan_hash, "packages": [
            {"directory": "partitions/" + jobs[job_id]["partition"], "receipt": {
                "job": jobs[job_id], "plan_sha256": plan_hash,
                "manifest_sha256": partitions[jobs[job_id]["partition"]]["manifest_sha256"]}}
            for job_id in batch["jobs"]]}

    def package(self, identity, destination):
        expected = self.expected_package(identity)
        destination.parent.mkdir(parents=True, exist_ok=True)
        suite, _ = self.batches[identity]
        directory, plan = self.plans[suite]
        with tempfile.TemporaryDirectory(prefix="language-package-", dir=destination.parent) as temporary:
            inputs = Path(temporary) / "inputs"
            actual = fleet.package_validated_batch(directory, plan, identity, inputs)
            if actual != expected:
                raise ValueError("packaged batch differs from its frozen plan")
            buffer = io.BytesIO()
            with tarfile.open(fileobj=buffer, mode="w") as archive:
                for path in sorted(inputs.rglob("*")):
                    if path.is_symlink():
                        raise ValueError("language input package contains a symlink")
                    member = archive.gettarinfo(path, str(path.relative_to(inputs)))
                    member.uid = member.gid = member.mtime = 0
                    member.uname = member.gname = ""
                    if path.is_file():
                        with path.open("rb") as file:
                            archive.addfile(member, file)
                    else:
                        archive.addfile(member)
            payload = gzip.compress(buffer.getvalue(), mtime=0)
        if destination.exists():
            if destination.read_bytes() != payload:
                raise ValueError("language package changed between attempts")
        else:
            with destination.open("xb") as file:
                file.write(payload)
        return collection.digest(payload)

    def validate_receipt(self, identity, receipt):
        expected = self.expected_package(identity)
        batch = expected["batch"]
        if (receipt.get("schema_version") != 1 or receipt.get("workload") != "language-batch" or
                receipt.get("platform") != batch["platform"] or receipt.get("shard_id") != batch["shard"] or
                receipt.get("replica_id") != str(batch["replica"]) or
                receipt.get("plan_sha256") != expected["plan_sha256"] or
                receipt.get("batch_sha256") != collection.digest(collection.encode(expected)) or
                set(receipt.get("exports", {})) != set(batch["jobs"])):
            raise ValueError("language receipt does not cover its frozen batch")
