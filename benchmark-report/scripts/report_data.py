#!/usr/bin/env python3

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import re
import subprocess
import sys
from pathlib import Path


REPORT_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = REPORT_ROOT.parent
DATA_DIRECTORY = REPORT_ROOT / "data"
MANIFEST_FILE = DATA_DIRECTORY / "manifest.json"
README_FILE = REPOSITORY_ROOT / "README.md"
SUMMARY_START = "<!-- benchmark-summary:start -->"
SUMMARY_END = "<!-- benchmark-summary:end -->"
MANIFEST_SCHEMA_VERSION = 1
REPORT_SCHEMA_VERSION = 2
MAX_STORED_DATA_BYTES = 50 * 1024 * 1024


def read_data_bytes(path: Path) -> bytes:
    content = path.read_bytes()
    return gzip.decompress(content) if path.suffix == ".gz" else content


def load_json(path: Path):
    return json.loads(read_data_bytes(path).decode("utf-8"), parse_constant=reject_nonfinite_constant)


def reject_nonfinite_constant(value: str):
    raise ValueError(f"nonfinite JSON number is not allowed: {value}")


def encode_json(data, **arguments) -> str:
    return json.dumps(data, allow_nan=False, **arguments)


def require_finite_numbers(value) -> None:
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("report data contains a nonfinite number")
    if isinstance(value, dict):
        for nested in value.values():
            require_finite_numbers(nested)
    elif isinstance(value, list):
        for nested in value:
            require_finite_numbers(nested)


def require_object(value, path: str) -> dict:
    if not isinstance(value, dict):
        raise ValueError(f"{path} must be an object")
    return value


def require_array(value, path: str) -> list:
    if not isinstance(value, list):
        raise ValueError(f"{path} must be an array")
    return value


def require_string(value, path: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{path} must be a nonempty string")
    return value


def require_nonnegative_integer(value, path: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 2**53 - 1:
        raise ValueError(f"{path} must be a nonnegative JavaScript-safe integer")
    return value


def required_member(value: dict, field: str, path: str):
    if field not in value:
        raise ValueError(f"{path}.{field} is required")
    return value[field]


def require_unique(values, path: str, identity: str) -> None:
    seen = set()
    for value in values:
        if value in seen:
            raise ValueError(f"{path} contains duplicate {identity} {value!r}")
        seen.add(value)


def validate_report(data: dict) -> None:
    from language_data import validate_language_report

    data = require_object(data, "report")
    require_supported_report_schema(data.get("schemaVersion"), "report schemaVersion")
    require_finite_numbers(data)
    validate_language_report(data)


def validate_data_filename_value(value, path: str) -> str:
    if (not isinstance(value, str) or
            not value or
            "/" in value or
            "\\" in value or
            re.fullmatch(r"[A-Za-z0-9._-]+", value) is None or
            value in {".", ".."}):
        raise ValueError(f"{path} must be a data filename")
    return value


def require_supported_report_schema(value, path: str) -> int:
    schema_version = require_nonnegative_integer(value, path)
    if schema_version != REPORT_SCHEMA_VERSION:
        raise ValueError(f"unsupported report schema version: {schema_version}")
    return schema_version


def validate_manifest(value) -> dict:
    manifest = require_object(value, "manifest")
    schema_version = require_nonnegative_integer(
        required_member(manifest, "schemaVersion", "manifest"), "manifest.schemaVersion")
    if schema_version != MANIFEST_SCHEMA_VERSION:
        raise ValueError(f"unsupported report manifest schema: {schema_version}")
    require_supported_report_schema(
        required_member(manifest, "reportSchemaVersion", "manifest"),
        "manifest.reportSchemaVersion")
    validate_data_filename_value(
        required_member(manifest, "current", "manifest"), "manifest.current")
    releases = require_array(required_member(manifest, "releases", "manifest"), "manifest.releases")
    if not releases:
        raise ValueError("manifest must contain at least one release")
    for index, value in enumerate(releases):
        release_path = f"manifest.releases[{index}]"
        release = require_object(value, release_path)
        require_supported_report_schema(
            required_member(release, "schemaVersion", release_path),
            f"{release_path}.schemaVersion")
        require_string(required_member(release, "candidate", release_path), f"{release_path}.candidate")
        require_string(required_member(release, "label", release_path), f"{release_path}.label")
        validate_data_filename_value(
            required_member(release, "file", release_path), f"{release_path}.file")
    require_unique((release["file"] for release in releases), "manifest.releases", "file")
    return manifest


def load_manifest() -> dict:
    return validate_manifest(load_json(MANIFEST_FILE))


def report_file(filename: str) -> Path:
    path = DATA_DIRECTORY / filename
    if not path.is_file():
        raise ValueError(f"manifest data file does not exist: {filename}")
    return path


def current_data_file() -> Path:
    manifest = load_manifest()
    return report_file(manifest["current"])


def data_digest(path: Path) -> str:
    return hashlib.sha256(read_data_bytes(path)).hexdigest()[:16]


def validate_data_filename(path: Path) -> None:
    digest = data_digest(path)
    if digest not in path.name:
        raise ValueError(f"data filename does not contain its content hash {digest}")


def validate_published_data() -> tuple[dict, Path, dict]:
    manifest = load_manifest()
    for path in DATA_DIRECTORY.iterdir():
        if path.is_file() and path.stat().st_size >= MAX_STORED_DATA_BYTES:
            raise ValueError(f"report data exceeds stored-file size limit: {path.name}")
    current_path = report_file(manifest["current"])
    validate_data_filename(current_path)
    current_data = load_json(current_path)
    validate_report(current_data)

    validated_reports = {manifest["current"]}
    for release in manifest["releases"]:
        if release["file"] in validated_reports:
            continue
        release_path = report_file(release["file"])
        validate_data_filename(release_path)
        validate_report(load_json(release_path))
        validated_reports.add(release["file"])
    return manifest, current_path, current_data


def ratio_text(ratio: float) -> str:
    if 0.98 <= ratio <= 1.02:
        return "within 2%"
    if ratio < 1:
        speedup = 1 / ratio
        if speedup < 1.1:
            return f"{(speedup - 1) * 100:.1f}% faster"
        return f"{speedup:.1f}× faster"
    if ratio < 1.1:
        return f"{(ratio - 1) * 100:.1f}% slower"
    return f"{ratio:.1f}× slower"


def summary_markdown(data: dict) -> str:
    calculated = subprocess.run(['node', str(REPORT_ROOT / 'scripts/summary-ratios.mjs')],
                                input=encode_json(data), text=True, capture_output=True, check=True)
    ratios = json.loads(calculated.stdout)
    comparators = {'re2': 'Native RE2', 'java': 'Java regex', 'trino': 'Trino regex (Joni)', 'like': 'Trino LIKE'}
    lines = [SUMMARY_START, "| Compared with | Everyday expressions | Text processing |", "|---|---:|---:|"]
    for language, comparator in comparators.items():
        cells = [ratio_text(value) if value is not None else '—'
                 for value in (ratios[language]['everyday'], ratios[language]['textProcessing'])]
        lines.append(f"| {comparator} | {' | '.join(cells)} |")
    source = data['sources']['currentLabel']
    source += (". Development builds with targeted updates; per-row sources are in the report"
               if data.get('publication', {}).get('status') == 'preliminary'
               else f" `{data['sources']['currentCandidate']}`")
    platform = next(cpu for cpu in data['platforms'] if cpu in ('r9g', 'c9g')).capitalize()
    lines.extend(("", f"_Source: {source}. {platform} with native access enabled and compiled patterns reused. "
                  "Geometric mean of per-workload time ratios; input conversion excluded._", SUMMARY_END))
    return "\n".join(lines)


def replace_summary(readme: str, replacement: str) -> str:
    if readme.count(SUMMARY_START) != 1 or readme.count(SUMMARY_END) != 1:
        raise ValueError("README must contain exactly one benchmark summary marker pair")
    before, remainder = readme.split(SUMMARY_START, 1)
    _, after = remainder.split(SUMMARY_END, 1)
    return before + replacement + after


def check() -> None:
    _, _, data = validate_published_data()
    expected = replace_summary(README_FILE.read_text(), summary_markdown(data))
    actual = README_FILE.read_text()
    if actual != expected:
        print("README benchmark summary is stale; run:", file=sys.stderr)
        print("  python3 benchmark-report/scripts/report_data.py update-readme", file=sys.stderr)
        raise SystemExit(1)

def import_data(source: Path) -> None:
    data = load_json(source)
    validate_report(data)
    schema_version = data["schemaVersion"]
    # Preserve the JSON encoding and its content hash independently of compression.
    indent = None if data.get('publication', {}).get('status') == 'preliminary' else 2
    encoded = (encode_json(data, indent=indent, ensure_ascii=False) + "\n").encode()
    digest = hashlib.sha256(encoded).hexdigest()[:16]
    candidate = data["sources"]["currentCandidate"]
    if not re.fullmatch(r"[A-Za-z0-9._-]+", candidate):
        raise ValueError("current candidate is not safe for a data filename")
    filename = f"regulator-{candidate}-{digest}.json.gz"
    compressed = gzip.compress(encoded, compresslevel=6, mtime=0)
    if len(compressed) >= MAX_STORED_DATA_BYTES:
        raise ValueError("report data exceeds stored-file size limit")

    releases = []
    if MANIFEST_FILE.exists():
        previous_manifest, _, _ = validate_published_data()
        releases = [
            release
            for release in previous_manifest["releases"]
            if release["file"].removesuffix(".gz") != filename.removesuffix(".gz")
        ]
    elif DATA_DIRECTORY.exists() and any(DATA_DIRECTORY.iterdir()):
        raise ValueError("report data directory contains files but no valid manifest")
    releases.insert(0, {
        "schemaVersion": schema_version,
        "candidate": candidate,
        "label": data["sources"]["currentLabel"],
        "file": filename,
    })
    manifest = {
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "reportSchemaVersion": schema_version,
        "current": filename,
        "releases": releases,
    }
    validate_manifest(manifest)

    DATA_DIRECTORY.mkdir(parents=True, exist_ok=True)
    destination = DATA_DIRECTORY / filename
    destination.write_bytes(compressed)
    MANIFEST_FILE.write_text(encode_json(manifest, indent=2) + "\n")
    print(f"Imported {source} as {destination}")


def update_readme() -> None:
    data = load_json(current_data_file())
    validate_report(data)
    README_FILE.write_text(replace_summary(README_FILE.read_text(), summary_markdown(data)))


def parse_arguments():
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("check")
    import_parser = subparsers.add_parser("import")
    import_parser.add_argument("source", type=Path)
    subparsers.add_parser("update-readme")
    return parser.parse_args()


def main() -> None:
    arguments = parse_arguments()
    if arguments.command == "check":
        check()
    elif arguments.command == "import":
        import_data(arguments.source)
    else:
        update_readme()


if __name__ == "__main__":
    main()
