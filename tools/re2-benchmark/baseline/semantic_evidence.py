#!/usr/bin/env python3

import argparse
import csv
import hashlib
import xml.etree.ElementTree as ElementTree
from pathlib import Path


FIELDS = ("kind", "identifier", "result")


def parse_arguments():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--expected-tests", required=True)
    parser.add_argument("--route", required=True)
    parser.add_argument("--handler", required=True)
    parser.add_argument("--native-access", choices=("true", "false"), required=True)
    parser.add_argument("--semantic-log", type=Path, required=True)
    parser.add_argument("--rebar-verification", type=Path, action="append", default=[])
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def junit_evidence(report_directory, expected_tests):
    rows = []
    observed_classes = set()
    report_paths = sorted(report_directory.glob("TEST-*.xml"))
    if not report_paths:
        raise ValueError(f"No Surefire XML reports found in {report_directory}")
    for path in report_paths:
        root = ElementTree.parse(path).getroot()
        for test_case in root.iter("testcase"):
            class_name = test_case.attrib.get("classname", "")
            test_name = test_case.attrib.get("name", "")
            if not class_name or not test_name:
                raise ValueError(f"Test case in {path} has no class or method name")
            failures = [child.tag for child in test_case if child.tag in {"error", "failure", "skipped"}]
            if failures:
                raise ValueError(f"Semantic test {class_name}#{test_name} was not successful: {failures}")
            observed_classes.add(class_name.rsplit(".", 1)[-1])
            rows.append(("junit", f"{class_name}#{test_name}", "passed"))
    missing = sorted(set(expected_tests) - observed_classes)
    if missing:
        raise ValueError(f"Semantic test reports are missing requested classes: {missing}")
    return rows


def verifier_evidence(path):
    verified = sorted({line.strip() for line in path.read_text(encoding="utf-8").splitlines()
                       if line.strip().startswith("Verified ")})
    return [("verifier", line, "passed") for line in verified]


def rebar_evidence(paths):
    rows = []
    for path in paths:
        with path.open(newline="", encoding="utf-8") as input_file:
            rows.extend(tuple(row) for row in csv.reader(input_file))
    rows.sort()
    digest = hashlib.sha256()
    for row in rows:
        digest.update("\0".join(row).encode())
        digest.update(b"\n")
    return [("rebar", "exact-system-verification", digest.hexdigest())]


def build_evidence(arguments):
    expected_tests = [value for value in arguments.expected_tests.split(",") if value]
    if not expected_tests:
        raise ValueError("No semantic tests were requested")
    rows = [
        ("route", "route", arguments.route),
        ("route", "handler", arguments.handler),
        ("route", "native-access", arguments.native_access),
    ]
    rows.extend(junit_evidence(arguments.reports, expected_tests))
    rows.extend(verifier_evidence(arguments.semantic_log))
    if arguments.rebar_verification:
        rows.extend(rebar_evidence(arguments.rebar_verification))
    return sorted(rows)


def main():
    arguments = parse_arguments()
    rows = build_evidence(arguments)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    with arguments.output.open("w", newline="", encoding="utf-8") as output_file:
        writer = csv.writer(output_file, delimiter="\t", lineterminator="\n")
        writer.writerow(FIELDS)
        writer.writerows(rows)


if __name__ == "__main__":
    main()
