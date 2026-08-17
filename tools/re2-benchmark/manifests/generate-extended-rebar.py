#!/usr/bin/env python3

import argparse
import csv
import hashlib
import os
import re
import tempfile
from pathlib import Path


PINNED_REBAR_COMMIT = "463d00f31887e84c38467805b9e3122c314b9521"
SUPPORTED_MODELS = {"compile", "count", "count-spans", "count-captures", "grep", "grep-captures"}
EXCLUDED_BENCHMARKS = {"imported/regex-redux/regex-redux"}


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("rebar_root", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--exclusions-output", type=Path)
    parser.add_argument("--check", action="store_true")
    return parser.parse_args()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def generate(rebar_root):
    definitions_root = rebar_root / "benchmarks/definitions"
    rows = []
    exclusions = []
    for definition in sorted(definitions_root.rglob("*.toml")):
        relative = definition.relative_to(definitions_root)
        if relative.parts[0] in {"curated", "test"}:
            continue
        definition_name = relative.with_suffix("").as_posix()
        definition_sha256 = sha256(definition)
        sections = definition.read_text().split("[[bench]]")[1:]
        for section in sections:
            model = field(section, "model")
            benchmark_name = field(section, "name")
            engines_match = re.search(r"(?ms)^engines\s*=\s*\[(.*?)^\s*\]", section)
            engines_text = "" if engines_match is None else "\n".join(
                line.split("#", 1)[0] for line in engines_match.group(1).splitlines())
            engines = re.findall(r"['\"]([^'\"]+)['\"]", engines_text)
            name = f"{definition_name}/{benchmark_name}"
            if name in EXCLUDED_BENCHMARKS:
                exclusions.append((name, model or "unknown", relative.as_posix(), "composite-application"))
                continue
            if model not in SUPPORTED_MODELS:
                exclusions.append((name, model or "unknown", relative.as_posix(), "unsupported-model"))
                continue
            if "re2" not in engines:
                exclusions.append((name, model, relative.as_posix(), "no-native-re2-semantics"))
                continue
            rows.append((name, model, relative.as_posix(), definition_sha256))
    return rows, exclusions


def field(section, name):
    match = re.search(rf"(?m)^{name}\s*=\s*(['\"])(.*?)\1\s*$", section)
    return None if match is None else match.group(2)


def render(rows, output):
    with output.open("w", newline="") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        writer.writerow(("name", "model", "definition", "definition_sha256"))
        writer.writerows(rows)


def render_exclusions(rows, output):
    with output.open("w", newline="") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        writer.writerow(("name", "model", "definition", "reason"))
        writer.writerows(rows)


def replace_or_check(output, render_function, rows, check):
    temporary_directory = None if check else output.parent
    descriptor, name = tempfile.mkstemp(
        prefix=output.name + ".", suffix=".generated", dir=temporary_directory)
    os.close(descriptor)
    temporary = Path(name)
    try:
        render_function(rows, temporary)
        if check:
            if not output.exists() or temporary.read_bytes() != output.read_bytes():
                raise SystemExit(f"Rebar manifest differs from generated output: {output}")
        else:
            temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    arguments = parse_args()
    rows, exclusions = generate(arguments.rebar_root)
    replace_or_check(arguments.output, render, rows, arguments.check)
    if arguments.exclusions_output is not None:
        replace_or_check(arguments.exclusions_output, render_exclusions, exclusions, arguments.check)
    print(f"generated {len(rows)} extended Rebar rows and {len(exclusions)} exclusions")


if __name__ == "__main__":
    main()
