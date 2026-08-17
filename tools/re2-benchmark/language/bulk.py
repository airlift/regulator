#!/usr/bin/env python3
"""Collect shared bulk operations without substituting a different Rebar model."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import gzip
from pathlib import Path

import collection
import mappings
import rebar_inventory


CLASS = "io.airlift.regulator.BenchmarkLanguageBulk"


def prepare(inventory_directory, destination):
    inventory = rebar_inventory.validate(inventory_directory)
    destination.mkdir(parents=True, exist_ok=False)
    (destination / "inputs").mkdir()
    cases = []
    for original in inventory["cases"]:
        payload = gzip.decompress((inventory_directory / original["klv_file"]).read_bytes())
        path = f"inputs/{original['klv_sha256']}.klv"
        (destination / path).write_bytes(payload)
        language_mappings = {}
        for language in collection.ENGINES:
            reason = None
            if language != "re2":
                if not original["patterns"][0]["valid_utf8"]:
                    reason = "Pattern bytes cannot represent a UTF-8 expression in this public language API"
                elif original["model"] != "compile" and not original["haystack"]["valid_utf8"]:
                    reason = ("The JDK String API cannot represent the malformed UTF-8 input" if language == "java" else
                              "Trino does not define a shared cross-engine matching contract for malformed UTF-8 input")
            fields = rebar_inventory.decode_klv(payload)
            pattern = fields["pattern"][0]
            translated, explanation = mappings.translate(original["id"], language, pattern)
            if translated is None:
                reason = reason or explanation
            mapped_payload = payload
            if not reason and translated != pattern:
                fields["pattern"] = [translated]
                mapped_payload = encode_klv(fields)
            mapped_hash = collection.digest(mapped_payload)
            mapped_path = f"inputs/{mapped_hash}.klv"
            (destination / mapped_path).write_bytes(mapped_payload)
            language_mappings[language] = {
                "status": "not-compatible" if reason else "translated" if translated != pattern else "identical",
                "reason": reason or explanation, "verifier": "bulk-result-boundaries-and-bytes-v2",
                "pattern_hex": (translated if not reason else pattern).hex(),
                "input_file": mapped_path, "input_file_sha256": mapped_hash}
        cases.append({"id": original["id"], "model": original["model"], "population": original["population"],
                      "family": original["family"], "source_file": path, "source_sha256": original["klv_sha256"],
                      "source_pattern_sha256": original["patterns"][0]["sha256"],
                      "source_haystack_sha256": original["haystack"]["sha256"],
                      "input_bytes_per_operation": original["haystack"]["bytes"],
                      "pattern_bytes": original["patterns"][0]["bytes"],
                      "expected_result": original["native_expected_result"], "mappings": language_mappings})
    manifest = {"schema_version": 3, "suite": "language-bulk", "protocol": collection.PROTOCOL,
                "languages": collection.ENGINES, "memory_modes": collection.MODES,
                "operations": ["compile", "execute"], "cases": cases,
                "rebar_commit": inventory["rebar_commit"],
                "inventory_sha256": collection.digest((inventory_directory / "inventory.json").read_bytes()),
                "representation": "Original byte inputs; JDK text and UTF-8 offset maps decoded before timing",
                "line_policy": "CR-trimmed lines materialized before timing in every engine; no phantom final line",
                "regulator_operation_policy": "Public find/contains for boolean lines; public count for counts; public matcher iteration for spans/captures",
                "trino_capture_policy": "Trino and Joni construct a matcher per source inside timing and iterate spans/captures without output construction",
                "scope": "Shared language comparison; official Rebar shard results remain separate"}
    validate_manifest(manifest, destination)
    collection.save(destination / "manifest.json", manifest)
    return manifest


def encode_klv(fields):
    return b"".join(key.encode("ascii") + b":" + str(len(value)).encode() + b":" + value + b"\n"
                    for key, values in fields.items() for value in values)


def validate_manifest(manifest, directory):
    if (manifest["schema_version"] != 3 or manifest["suite"] != "language-bulk" or
            manifest["protocol"] != collection.PROTOCOL or
            manifest.get("operations") != ["compile", "execute"] or
            collection.encode(manifest["languages"]) != collection.encode(collection.ENGINES) or
            collection.encode(manifest["memory_modes"]) != collection.encode(collection.MODES)):
        raise ValueError("invalid bulk collection protocol")
    selected = manifest.get("selected_languages", list(collection.ENGINES))
    if not selected or len(set(selected)) != len(selected) or not set(selected) <= set(collection.ENGINES):
        raise ValueError("invalid selected bulk languages")
    if "selected_languages" in manifest and not manifest.get("parent_manifest_sha256"):
        raise ValueError("bulk partition must identify its parent")
    identifiers = [case["id"] for case in manifest["cases"]]
    if not identifiers or len(identifiers) != len(set(identifiers)):
        raise ValueError("missing or duplicate bulk cases")
    for case in manifest["cases"]:
        if case["model"] not in rebar_inventory.MODELS or set(case["mappings"]) != set(collection.ENGINES):
            raise ValueError("missing bulk operation or language mapping")
        if type(case["expected_result"]) is not int or case["expected_result"] < 0:
            raise ValueError("invalid expected bulk result")
        source_path = (directory / case["source_file"]).resolve()
        if not source_path.is_relative_to(directory.resolve()):
            raise ValueError("bulk source path escapes manifest")
        source = source_path.read_bytes()
        if collection.digest(source) != case["source_sha256"]:
            raise ValueError("bulk source changed")
        source_fields = rebar_inventory.decode_klv(source)
        if (source_fields["name"] != [case["id"].encode()] or
                source_fields["model"] != [case["model"].encode()] or
                len(source_fields["pattern"]) != 1 or
                len(source_fields["pattern"][0]) != case["pattern_bytes"] or
                collection.digest(source_fields["haystack"][0]) != case["source_haystack_sha256"]):
            raise ValueError("bulk source metadata changed")
        if collection.digest(source_fields["pattern"][0]) != case["source_pattern_sha256"]:
            raise ValueError("bulk source pattern changed")
        for language, mapping in case["mappings"].items():
            if mapping["status"] not in {"pending", "identical", "translated", "not-compatible"} or not mapping["reason"]:
                raise ValueError("unclassified bulk mapping")
            path = (directory / mapping["input_file"]).resolve()
            if not path.is_relative_to(directory.resolve()):
                raise ValueError("bulk input path escapes manifest")
            payload = path.read_bytes()
            if collection.digest(payload) != mapping["input_file_sha256"]:
                raise ValueError("bulk input checksum mismatch")
            fields = rebar_inventory.decode_klv(payload)
            translated, _ = mappings.translate(case["id"], language, source_fields["pattern"][0])
            expected_fields = dict(source_fields)
            if mapping["status"] != "not-compatible":
                if translated is None:
                    raise ValueError("incompatible bulk translation marked comparable")
                expected_fields["pattern"] = [translated]
                expected_status = "identical" if translated == source_fields["pattern"][0] else "translated"
                if mapping["status"] not in {"pending", expected_status}:
                    raise ValueError("bulk translation status changed")
            if fields != expected_fields:
                raise ValueError("bulk mapping differs from its declared translation or flags")
            if fields["name"] != [case["id"].encode()] or fields["model"] != [case["model"].encode()]:
                raise ValueError("bulk input identifies a different operation")
            if mapping.get("pattern_hex") != fields["pattern"][0].hex():
                raise ValueError("bulk mapping does not record its actual pattern")
            if mapping.get("verifier") not in {"bulk-result-and-matched-bytes-v1", "bulk-result-boundaries-and-bytes-v2"}:
                raise ValueError("missing bulk verification contract")
            if (len(fields["haystack"][0]) != case["input_bytes_per_operation"] or
                    collection.digest(fields["haystack"][0]) != case["source_haystack_sha256"]):
                raise ValueError("bulk mapping changed the input")
            if mapping["status"] in {"pending", "identical"} and mapping["input_file_sha256"] != case["source_sha256"]:
                raise ValueError("unrecorded bulk translation")


def verify(directory, results, java, classpath, native):
    """Qualify each operation independently, including an original native-RE2 reference."""
    manifest = collection.load(directory / "manifest.json")
    validate_manifest(manifest, directory)
    if any(mapping["status"] == "pending" for case in manifest["cases"] for mapping in case["mappings"].values()):
        raise ValueError("pending bulk mappings cannot qualify")
    results.mkdir(parents=True, exist_ok=False)
    runners = collection.runner_identity(java, classpath, native)
    collection.save(results / "runners.json", runners)

    def command(engine, mode, action, workload):
        return ([native, "--" + action, str(workload)] if engine == "native-re2" else
                collection.java_command(java, classpath, mode) + [CLASS, "--" + action, engine, str(workload)])

    def run(case, language, engine, mode, identity):
        mapping = case["mappings"][language]
        operation = collection.case_operations(manifest, case)[0]
        if mapping["status"] == "not-compatible":
            excluded = {"outcome": "not-compatible", "reason": mapping["reason"]}
            return {**excluded, "operations": {operation: excluded.copy()}}
        workload = (directory / mapping["input_file"]).resolve()
        prefix = results / identity
        execution = collection.verify_command(command(engine, mode, "execute", workload), prefix / operation, engine, mode)
        if execution["outcome"] == "did-not-finish":
            return {**execution, "operations": {operation: execution.copy()}}
        expected = "compiled\n" if operation == "compile" else str(case["expected_result"]) + "\n"
        if execution["trace"] != expected:
            raise ValueError(f"bulk result verification failed: {identity}")
        trace = collection.verify_command(command(engine, mode, "trace", workload), prefix / "trace", engine, mode, hash_trace=True)
        if trace["outcome"] != "completed":
            raise ValueError(f"bulk trace verification exceeded its limit, not an operation timeout: {identity}")
        trace["raw_trace_file"] = str((prefix / "trace/verification-0.stdout").relative_to(results))
        return {**trace, "operations": {operation: execution}}

    references = {}
    for case in manifest["cases"]:
        prefix = results / "reference" / case["id"]
        source = (directory / case["source_file"]).resolve()
        count = collection.verify_command([native, "--execute", str(source)], prefix / "execute", "native-re2", "safe")
        expected = "compiled\n" if case["model"] == "compile" else str(case["expected_result"]) + "\n"
        if count["outcome"] != "completed" or count["trace"] != expected:
            raise ValueError(f"original native RE2 reference did not verify: {case['id']}")
        trace = collection.verify_command([native, "--trace", str(source)], prefix / "trace", "native-re2", "safe", hash_trace=True)
        if trace["outcome"] != "completed":
            raise ValueError(f"original native RE2 trace did not finish: {case['id']}")
        trace["raw_trace_file"] = str((prefix / "trace/verification-0.stdout").relative_to(results))
        references[case["id"]] = {**trace, "execution": count}

    receipts = {}
    # Workers pin this process and its children to one CPU. Concurrent verifiers
    # compete for that CPU and can turn wall-clock limits into false timeouts.
    observations = list(collection.observations(manifest))
    for identity, case, language, engine, mode in observations:
        receipt = run(case, language, engine, mode, identity)
        if receipt["outcome"] == "completed" and receipt["trace"] != references[case["id"]]["trace"]:
            raise ValueError(f"bulk translation or matching differs from native source semantics: {identity}")
        receipts[identity] = receipt
        collection.save(results / "verification.partial.json", receipts)
        if len(receipts) % 30 == 0:
            print(f"Verified {len(receipts)}/{len(observations)} bulk observations", flush=True)
    validate_receipts(manifest, receipts)
    if runners != collection.runner_identity(java, classpath, native):
        raise ValueError("bulk runners changed during verification")
    evidence = {"manifest_sha256": collection.digest((directory / "manifest.json").read_bytes()),
                "runners_sha256": collection.digest(collection.encode(runners)),
                "receipts": receipts, "references": references}
    validate_evidence(manifest, evidence, results)
    collection.save(results / "verification.json", evidence)
    return evidence


def validate_receipts(manifest, receipts):
    observations = list(collection.observations(manifest))
    if set(receipts) != {identity for identity, *_ in observations}:
        raise ValueError("missing or unexpected bulk receipts")
    traces = {}
    for identity, case, language, engine, mode in observations:
        receipt = receipts[identity]
        operation = collection.case_operations(manifest, case)[0]
        if set(receipt.get("operations", {})) != {operation}:
            raise ValueError("missing bulk operation receipt")
        execution = receipt["operations"][operation]
        outcome = receipt["outcome"]
        if outcome != execution["outcome"]:
            raise ValueError("trace outcome cannot replace operation evidence")
        if (outcome == "not-compatible") != (case["mappings"][language]["status"] == "not-compatible"):
            raise ValueError("bulk compatibility receipt contradicts mapping")
        if outcome == "not-compatible":
            continue
        if outcome == "completed":
            expected = "compiled\n" if operation == "compile" else str(case["expected_result"]) + "\n"
            if execution.get("trace") != expected or execution.get("trace_sha256") != collection.digest(expected.encode()):
                raise ValueError("bulk operation result differs from expected result")
            trace = receipt.get("trace", "")
            if (len(trace) != 72 or not trace.startswith("sha256:") or not trace.endswith("\n") or
                    receipt.get("trace_sha256") != collection.digest(trace.encode())):
                raise ValueError("invalid bulk trace hash")
            if traces.setdefault(case["id"], trace) != trace:
                raise ValueError("bulk languages or memory modes disagree")
        else:
            # This helper's timeout branch is independent of the ordinary-suite expected values.
            collection.validate_operation_receipt(case, language, operation, execution)


def validate_evidence(manifest, evidence, results):
    validate_receipts(manifest, evidence["receipts"])
    if set(evidence["references"]) != {case["id"] for case in manifest["cases"]}:
        raise ValueError("missing original native RE2 reference")
    def validate_trace(entry):
        path = (results / entry["raw_trace_file"]).resolve()
        if not path.is_relative_to(results.resolve()):
            raise ValueError("bulk trace path escapes results")
        if (entry["trace"] != "sha256:" + collection.digest(path.read_bytes()) + "\n" or
                entry.get("trace_sha256") != collection.digest(entry["trace"].encode())):
            raise ValueError("bulk raw trace changed")

    for case in manifest["cases"]:
        reference = evidence["references"][case["id"]]
        expected = "compiled\n" if case["model"] == "compile" else str(case["expected_result"]) + "\n"
        execution = reference["execution"]
        if (reference["outcome"] != "completed" or execution["outcome"] != "completed" or
                execution.get("trace") != expected or execution.get("trace_sha256") != collection.digest(expected.encode())):
            raise ValueError("original native RE2 operation did not verify")
        validate_trace(reference)
    for identity, case, *_ in collection.observations(manifest):
        receipt = evidence["receipts"][identity]
        if receipt["outcome"] != "completed":
            continue
        validate_trace(receipt)
        if receipt["trace"] != evidence["references"][case["id"]]["trace"]:
            raise ValueError("bulk receipt differs from original native RE2")


def probe_one(case, language, engine, directory, results, java, classpath, native):
    mapping = case["mappings"][language]
    if mapping["status"] == "not-compatible":
        return {"outcome": "not-compatible", "reason": mapping["reason"]}
    workload = str((directory / mapping["input_file"]).resolve())
    evidence = {}
    for action in ("execute", "trace"):
        command = ([str(Path(native).resolve()), "--" + action, workload] if engine == "native-re2" else
                   collection.java_command(java, classpath, "safe") + [CLASS, "--" + action, engine, workload])
        prefix = results / case["id"] / language / engine / action
        try:
            receipt = collection.run_process(command, prefix)
        except ValueError as error:
            receipt = {"outcome": "runner-error", "reason": str(error)}
        if receipt["outcome"] == "completed":
            output = prefix.with_suffix(".stdout").read_bytes()
            receipt["stdout_sha256"] = collection.digest(output)
            if action == "execute":
                receipt["result"] = output.decode().strip()
        evidence[action] = receipt
    return evidence


def probe(directory, results, java, classpath, native, workers=3):
    """Inspect actual outputs. Probe failures are never automatically made publishable outcomes."""
    manifest = collection.load(directory / "manifest.json")
    validate_manifest(manifest, directory)
    results.mkdir(parents=True, exist_ok=False)
    evidence = {}
    with ThreadPoolExecutor(max_workers=workers) as executor:
        pending = {executor.submit(probe_one, case, language, engine, directory, results, java, classpath, native):
                   f"{case['id']}/{language}/{engine}"
                   for case in manifest["cases"] for language, engines in collection.ENGINES.items() for engine in engines}
        for future in as_completed(pending):
            evidence[pending[future]] = future.result()
            collection.save(results / "probe.partial.json", evidence)
            if len(evidence) % 30 == 0:
                print(f"Probed {len(evidence)}/{len(pending)} bulk engine mappings", flush=True)
    collection.save(results / "probe.json", {"kind": "preparation-only", "observations": evidence})
    collection.save(results / "summary.json", summarize(manifest, evidence))
    return evidence


def summarize(manifest, evidence):
    """Report missing and conflicting evidence without reclassifying it as compatibility."""
    issues = []
    expected_keys = set()
    for case in manifest["cases"]:
        traces = {}
        expected = "compiled" if case["model"] == "compile" else str(case["expected_result"])
        for language, engines in collection.ENGINES.items():
            pair = {}
            for engine in engines:
                key = f"{case['id']}/{language}/{engine}"
                expected_keys.add(key)
                entry = evidence.get(key)
                if entry is None:
                    issues.append({"id": key, "kind": "missing-observation"})
                    continue
                if entry.get("outcome") == "not-compatible":
                    if case["mappings"][language]["status"] != "not-compatible":
                        issues.append({"id": key, "kind": "unrecorded-incompatibility"})
                    continue
                for action in ("execute", "trace"):
                    receipt = entry.get(action, {})
                    if receipt.get("outcome") != "completed":
                        issues.append({"id": key, "kind": "incomplete-preflight", "action": action,
                                       "receipt": receipt})
                execution = entry.get("execute", {})
                if execution.get("outcome") == "completed" and execution.get("result") != expected:
                    issues.append({"id": key, "kind": "expected-result-mismatch",
                                   "expected": expected, "actual": execution.get("result")})
                trace = entry.get("trace", {})
                if trace.get("outcome") == "completed":
                    pair[engine] = trace["stdout_sha256"]
                    traces[key] = trace["stdout_sha256"]
            if len(set(pair.values())) > 1:
                issues.append({"id": f"{case['id']}/{language}", "kind": "engine-trace-mismatch", "traces": pair})
        if len(set(traces.values())) > 1:
            issues.append({"id": case["id"], "kind": "cross-language-trace-mismatch", "traces": traces})
    for key in evidence.keys() - expected_keys:
        issues.append({"id": key, "kind": "unexpected-observation"})
    return {"kind": "preparation-only", "expected_observations": len(expected_keys),
            "observations": len(evidence), "issues": issues,
            "qualification_ready": False,
            "reason": "Probes are diagnostic; complete mappings, native/safe verification, timeout confirmation, and fleet integration are required"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "probe", "summarize"))
    parser.add_argument("--inventory", type=Path)
    parser.add_argument("--manifest-directory", type=Path, required=True)
    parser.add_argument("--results", type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--classpath")
    parser.add_argument("--native-runner")
    args = parser.parse_args()
    if args.command == "prepare":
        if args.inventory is None:
            parser.error("prepare requires --inventory")
        manifest = prepare(args.inventory, args.manifest_directory)
        print(f"Prepared {len(manifest['cases'])} bulk workload definitions")
    elif args.command == "probe":
        if not args.results or not args.classpath or not args.native_runner:
            parser.error("probe requires results, classpath, and native runner")
        probe(args.manifest_directory, args.results, args.java, args.classpath, args.native_runner)
    else:
        if args.results is None:
            parser.error("summarize requires --results")
        manifest = collection.load(args.manifest_directory / "manifest.json")
        validate_manifest(manifest, args.manifest_directory)
        evidence = collection.load(args.results / "probe.json")["observations"]
        summary = summarize(manifest, evidence)
        collection.save(args.results / "summary.json", summary)
        print(f"{summary['observations']}/{summary['expected_observations']} observations; {len(summary['issues'])} issues need triage")


if __name__ == "__main__":
    main()
