"""Compose a local design preview from an existing report and outlier evidence.

This is not a release-data importer. It preserves mixed source identities and
uncertainty in the download, and never estimates missing candidate results.
"""

import argparse
from collections import defaultdict
from copy import deepcopy
import json
from pathlib import Path

from language_data import digest, reduce_hosts
from report_data import load_json


COMPARATORS = {'re2': 'native-re2', 'java': 'jdk', 'trino': 'joni'}
COHORTS = [('combined-smoke-final.json', 234, 1), ('repeats-final.json', 108, 3)]
ADVERSARIAL_FAMILIES = {'adversarial', 'reported-regression'}
LIKE_PATTERNS = {
    'EXACT_MATCH': 'needle', 'PREFIX_LARGE': 'needle%', 'SUFFIX_LARGE': '%needle',
    'CONTAINS_ABSENT': '%needle%', 'CONTAINS_LATE': '%needle%',
    'ORDERED_LATE': '%alpha%omega%', 'ORDERED_DENSE_FALSE': '%aab%bba%',
    'ANY_ASCII': '_', 'ANY_MULTIBYTE': '_', 'MIXED_LATE': '%alpha_omega%',
    'MIXED_ABSENT': '%alpha_omega%', 'WILDCARD_CHAIN': '%alpha_bravo_charlie%',
}
LIKE_INPUTS = {
    'EXACT_MATCH': 'The text "needle".',
    'PREFIX_LARGE': '32 KiB input beginning with "needle"; remaining bytes are x.',
    'SUFFIX_LARGE': '32 KiB input ending with "needle"; preceding bytes are x.',
    'CONTAINS_ABSENT': '32 KiB of x, with no occurrence of "needle".',
    'CONTAINS_LATE': '32 KiB input with "needle" at byte offset 32,700.',
    'ORDERED_LATE': '32 KiB input with "alpha" at offset 31,900 and "omega" at 32,700.',
    'ORDERED_DENSE_FALSE': '32 KiB of repeated "aabx", with no following "bba".',
    'ANY_ASCII': 'One ASCII character: x.',
    'ANY_MULTIBYTE': 'One four-byte UTF-8 character: 💰.',
    'MIXED_LATE': '32 KiB input containing "alpha💰omega" at offset 32,700.',
    'MIXED_ABSENT': '32 KiB of x, with no matching sequence.',
    'WILDCARD_CHAIN': '1 KiB input containing "alpha-bravo-charlie" at offset 900.',
}


def load(path):
    return load_json(Path(path))


def identity(row):
    return row['caseId'], row['language'], row['platform'], row['memoryMode'], row['operation']


def report_workload(row):
    if row['language'] == 'like' and row['caseId'] in {
        'trino-like/ANY_ASCII/optimized', 'trino-like/ANY_MULTIBYTE/optimized',
    }:
        return True
    if row['population'] != 'diagnostics-and-stress':
        return True
    return row['family'] in ADVERSARIAL_FAMILIES or row['caseId'] == 'imported/rsc/no-exponential'


def pair_cohort(data, expected, replicas):
    if data['uncompared'] or len(data['records']) != expected:
        raise ValueError('Incomplete outlier cohort')
    records = data['records']
    indexed = {(r['instance'], r['identity']): r for r in records}
    if len(indexed) != len(records):
        raise ValueError('Duplicate outlier observation')
    groups = defaultdict(list)
    for record in records:
        if record['kind'] != 'public' or record['engine'] != record['language']:
            continue
        language = record['language']
        comparator_id = '/'.join([record['case'], language, COMPARATORS[language], 'safe', record['operation']])
        comparator = indexed.get((record['instance'], comparator_id))
        if comparator is None:
            raise ValueError('Missing same-host comparator')
        for side in (record, comparator):
            if len(side['legs']) != 3 or side['legs'][0]['source_commit'] != side['legs'][2]['source_commit']:
                raise ValueError('Invalid source bracket')
            for leg in side['legs']:
                if leg['pattern_hex'] != record['legs'][0]['pattern_hex'] or leg['input_bytes'] != record['input_bytes']:
                    raise ValueError('Mismatched pattern or input size')
        key = record['case'], language, record['platform'], record['mode'], record['operation']
        groups[key].append((record, comparator))
    for group in groups.values():
        if len(group) != replicas or len({r['instance'] for r, _ in group}) != replicas:
            raise ValueError('Unexpected independent-host coverage')
    return groups


def replacement(group, input_bytes):
    hosts = []
    for index, (candidate, comparator) in enumerate(group, 1):
        hosts.append({
            'instanceId': candidate['instance'], 'replica': index,
            'candidate': {'state': 'compared', 'medianNs': candidate['candidate_ns']},
            'comparator': {'state': 'compared', 'medianNs': comparator['candidate_ns']},
        })
    return reduce_hosts(hosts, input_bytes)


def describe(row, cases):
    case = cases.get(row['caseId'])
    if case:
        mapping = case['mappings'][row['language']]
        pattern = mapping.get('pattern')
        if pattern is None:
            pattern = bytes.fromhex(mapping['pattern_hex']).decode('utf-8', errors='backslashreplace')
        if row.get('mapping') and row['mapping']['patternPreview'] != pattern[:240]:
            raise ValueError(f"Pattern preview disagrees with manifest: {row['id']}")
        details = {'pattern': pattern}
        if case.get('sources'):
            details['inputs'] = [{'text': bytes.fromhex(source['hex']).decode('utf-8', errors='backslashreplace'),
                                  'bytes': source['input_bytes']} for source in case['sources']]
            details['inputDescription'] = 'Inputs rotate round-robin, one input per invocation. Byte count is the average input size.'
        elif row['operation'] != 'compile':
            details['inputDescription'] = 'One operation processes the complete corpus. Byte count includes input that an optimized matcher may skip.'
        row['workload'] = details
    elif row['language'] == 'like':
        scenario = row['caseId'].split('/')[1]
        row['workload'] = {'pattern': LIKE_PATTERNS[scenario], 'inputDescription': LIKE_INPUTS[scenario]}


def compose(base, cohorts, cases, measured_cases):
    result = deepcopy(base)
    rows = {identity(row): row for row in result['rows']}
    if len(rows) != len(result['rows']):
        raise ValueError('Duplicate report identity')
    replacements = {}
    for name, data, expected, replicas in cohorts:
        for key, group in pair_cohort(data, expected, replicas).items():
            if key not in rows:
                continue
            row = rows[key]
            original_case, measured_case = cases[key[0]], measured_cases[key[0]]
            for field in ('source_haystack_sha256', 'source_pattern_sha256', 'model', 'expected_result'):
                if original_case[field] != measured_case[field]:
                    raise ValueError(f'Workload mismatch: {key}/{field}')
            mapping = original_case['mappings'][key[1]]
            if mapping['input_file_sha256'] != measured_case['mappings'][key[1]]['input_file_sha256']:
                raise ValueError('Mapped input identity changed')
            if row['inputBytes'] != group[0][0]['input_bytes'] or row['model'] != measured_case['model']:
                raise ValueError('Report operation differs from qualification')
            if mapping['pattern_hex'] != group[0][0]['legs'][0]['pattern_hex']:
                raise ValueError('Measured pattern differs from report')
            row.setdefault('previousMeasurements', []).append({
                'result': row['result'], 'measurementSource': row.get('measurementSource', base['sources'])})
            row['result'] = replacement(group, row['inputBytes'])
            row['measurementSource'] = {'cohort': name, 'sourceBrackets': [r for pair in group for r in pair]}
            replacements[key] = name
    excluded = [row for row in result['rows'] if not report_workload(row)]
    result['rows'] = [row for row in result['rows'] if report_workload(row)]
    for row in result['rows']:
        describe(row, cases)
    result['sources'] = {**result['sources'], 'currentLabel': 'September 2026'}
    result['presentation'] = {'reviewPreview': True}
    result['provenance']['localReviewPreview'] = {
        'purpose': 'Private integrated report for visual review, not a newly collected full run or release qualification.',
        'selection': 'Use independent repeats wherever present; otherwise use the initial targeted cohort. Never pool cohorts or estimate missing rows.',
        'display': 'Tables show point estimates without precision annotations. Warnings and all prior measurements remain in this download.',
        'replacementCount': len(replacements),
        'replacements': [{'identity': list(key), 'cohort': cohort} for key, cohort in sorted(replacements.items())],
        'excludedFamilies': sorted({row['family'] for row in excluded}),
        'excludedRowCount': len(excluded),
        'candidateSourceCommits': sorted({record['legs'][0]['source_commit']
                                         for _, data, _, _ in cohorts for record in data['records']}),
    }
    result['methodology'] = [
        'The comparisons use the same workload and operation on each paired host. Lower time is better.',
        'Raw results include source identities, independent-host measurements, variation, and predecessor results.',
        'This local review combines the existing full dataset with targeted outlier measurements. It is not a new full benchmark run.',
    ]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('base', 'evidence-directory', 'bulk-manifest', 'lifecycle-manifest', 'output-directory'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    cases = {case['id']: case for path in (args.bulk_manifest, args.lifecycle_manifest) for case in load(path)['cases']}
    measured = {case['id']: case for case in load(args.evidence_directory / 'combined-plan-v4/source/manifest.json')['cases']}
    cohorts = [(name, load(args.evidence_directory / name), expected, replicas) for name, expected, replicas in COHORTS]
    preview = compose(load(args.base), cohorts, cases, measured)
    preview['provenance']['localReviewPreview']['inputChecksums'] = {
        str(path): digest(path) for path in [args.base, args.bulk_manifest, args.lifecycle_manifest,
                                           *(args.evidence_directory / name for name, _, _ in COHORTS)]}
    args.output_directory.mkdir(parents=True, exist_ok=True)
    (args.output_directory / 'review-results.json').write_text(json.dumps(preview, ensure_ascii=True, allow_nan=False))
    (args.output_directory / 'manifest.json').write_text(json.dumps({
        'schemaVersion': 1, 'reportSchemaVersion': 2, 'current': 'review-results.json'}))
    print(json.dumps({key: preview['provenance']['localReviewPreview'][key]
                      for key in ('replacementCount', 'excludedRowCount', 'excludedFamilies')}, indent=2))
    print(f"{len(preview['rows'])} report rows written to {args.output_directory}")


if __name__ == '__main__':
    main()
