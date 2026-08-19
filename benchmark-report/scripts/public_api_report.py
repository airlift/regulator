"""Apply a complete public-operation qualification while preserving prior results."""

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re

from report_data import load_json


def verify_selection(original_manifest, selected_manifest):
    """Prove the follow-up kept the original patterns, input bytes, and results."""
    from language_data import digest

    original = json.loads(Path(original_manifest).read_text())
    selected = json.loads(Path(selected_manifest).read_text())
    if (selected['suite'] != original['suite'] or
            selected.get('followup_source_manifest_sha256') != digest(original_manifest)):
        raise ValueError('follow-up does not identify the original manifest')
    cases = {case['id']: case for case in original['cases']}
    if not selected['cases'] or len({case['id'] for case in selected['cases']}) != len(selected['cases']):
        raise ValueError('empty or duplicate selected cases')
    if any(cases.get(case['id']) != case for case in selected['cases']):
        raise ValueError('follow-up changed an original workload')
    protocol = dict(selected['protocol'])
    if protocol['measurement_timeout_seconds'] != 3600:
        raise ValueError('unexpected follow-up collection deadline')
    protocol['measurement_timeout_seconds'] = original['protocol']['measurement_timeout_seconds']
    if protocol != original['protocol']:
        raise ValueError('follow-up changed the measured protocol')
    return selected


def normalized_capture(index, manifests, selection=None, collector='public-api-followup'):
    """Read a complete, independently revalidated primary capture index."""
    from language_data import digest

    if (index['scope'] != 'accepted-primary-raw-evidence' or index['missing_batches'] or
            index['accepted_batches'] != index['planned_batches']):
        raise ValueError('follow-up requires complete validated primary evidence')
    ledger = Path(index['results']) / 'accepted-language-sessions.json'
    if digest(ledger) != index['ledger_sha256']:
        raise ValueError('accepted ledger changed after validation')
    cases = {(manifest['suite'], case['id']): case for manifest in manifests for case in manifest['cases']}
    protocols = {manifest['suite']: manifest['protocol'] for manifest in manifests}
    complete = {(suite, case, language) for suite, case in cases for language in ('re2', 'java', 'trino')}
    selected = complete if selection is None else set(map(tuple, selection))
    if not selected or not selected <= complete or (selection is not None and len(selected) != len(selection)):
        raise ValueError('invalid or duplicate follow-up selection')
    expected = {(suite, case, language, cpu, replica) for suite, case, language in selected
                for cpu in ('c9g', 'c8g', 'c8i') for replica in (1, 2, 3)}
    seen, instances, traces, batches, batch_hosts = set(), {}, {}, {}, {}
    jdk = None
    data = {'hosts': [], 'observations': [], 'comparisons': []}
    for entry in index['exports']:
        key = entry['suite'], entry['case'], entry['language'], entry['platform'], entry['replica']
        if key not in expected or key in seen:
            raise ValueError('unexpected or duplicate follow-up export')
        seen.add(key)
        replica_instances = instances.setdefault(key[:4], set())
        if entry['instance_id'] in replica_instances:
            raise ValueError('follow-up requires independent host partitions')
        replica_instances.add(entry['instance_id'])
        # A declared batch can hold several workloads on one host. Sharing that
        # host across different batches or replicas would defeat independence.
        batch = (entry['platform'], entry['replica'], entry.get('batch_identity', key))
        if batches.setdefault(entry['instance_id'], batch) != batch:
            raise ValueError('follow-up host is reused across different batches')
        if batch_hosts.setdefault(batch, entry['instance_id']) != entry['instance_id']:
            raise ValueError('follow-up batch spans different hosts')
        path = Path(entry['export_path'])
        if digest(path) != entry['export_sha256']:
            raise ValueError('validated export changed')
        export = json.loads(path.read_text())
        provenance = export['provenance']
        if jdk is not None and provenance['jdk'] != jdk:
            raise ValueError('follow-up mixes JDK versions')
        jdk = provenance['jdk']
        candidate = index['candidate']
        if (provenance['source_commit'] != candidate['candidate_commit'] or
                provenance['source_tree'] != candidate['root_tree'] or
                provenance['comparators_sha256'] != candidate['comparator_manifest_sha256'] or
                provenance['instance_identity']['instanceId'] != entry['instance_id']):
            raise ValueError('export source or host differs from the validated candidate')
        case = cases[entry['suite'], entry['case']]
        case_hash = hashlib.sha256(json.dumps(case, sort_keys=True, ensure_ascii=True, allow_nan=False).encode()).hexdigest()
        if (entry['case_sha256'] != case_hash or export['manifest']['cases'] != [case] or
                export['manifest']['selected_languages'] != [entry['language']] or
                export['manifest']['protocol'] != protocols[entry['suite']]):
            raise ValueError('export workload differs from the selected manifest')
        for receipt in export['verification']['receipts'].values():
            if receipt['outcome'] == 'completed':
                trace_key = entry['suite'], entry['case']
                if traces.setdefault(trace_key, receipt['trace']) != receipt['trace']:
                    raise ValueError('follow-up languages disagree on match traces')
        logical = list(key)
        data['hosts'].append({**entry, 'logical_identity': logical, 'collector': collector, 'provenance': provenance})
        data['observations'].extend({**row, 'id': identifier, 'logical_identity': logical}
                                    for identifier, row in export['observations'].items())
        data['comparisons'].extend({**row, 'logical_identity': logical} for row in export['comparisons'])
    if seen != expected:
        raise ValueError('missing follow-up exports')
    if len(batches) != index['accepted_batches']:
        raise ValueError('follow-up batch count differs from its accepted index')
    if digest(ledger) != index['ledger_sha256']:
        raise ValueError('accepted ledger changed during normalization')
    return data


def identity(row):
    return row['language'], row['platform'], row['memoryMode'], row['id']


def prior_label(row):
    if row['language'] in {'re2', 'java'}:
        if row['operation'] in {'reusedCount', 'singleUseCount'} or row['model'] == 'count':
            return 'Previous count-by-matcher-iteration measurements'
        if row['model'] == 'grep':
            return 'Previous boolean measurements using match-boundary discovery'
    return 'Previous measurement cohort'


def replace_rows(original, replacements, expected, source):
    """Keep predecessor measurements and distinguish a changed operation path."""
    from language_data import validate_language_report

    validate_language_report(original)
    if 'publicApiFollowup' in original['provenance']:
        raise ValueError('public API follow-up already applied')
    mapped = {identity(row): row for row in replacements}
    if len(mapped) != len(replacements) or set(mapped) != set(expected):
        raise ValueError('follow-up has missing, duplicate, or unexpected rows')
    report = deepcopy(original)
    seen = set()
    for row in report['rows']:
        key = identity(row)
        if key not in mapped:
            continue
        new = mapped[key]
        # Input bytes and requested results must be the same. Only the API path
        # and measured source may change, not what work the row represents.
        for field in ('caseId', 'operation', 'model', 'population', 'family', 'inputBytes', 'mapping', 'source'):
            if row.get(field) != new.get(field):
                raise ValueError(f'follow-up changes workload contract: {key}/{field}')
        if 'originalResult' in row:
            raise ValueError('follow-up would overwrite predecessor evidence')
        row['originalResult'] = row['result']
        row['originalResultLabel'] = prior_label(row)
        row['originalResultSourceReportSha256'] = source['originalReportSha256']
        row['result'] = deepcopy(new['result'])
        if 'workContract' in row:
            row['originalWorkContract'] = row.pop('workContract')
        if 'workContract' in new:
            row['workContract'] = new['workContract']
        row['measurementSource'] = deepcopy(source['candidate'])
        seen.add(key)
    if seen != set(expected):
        raise ValueError('follow-up does not map exactly to existing rows')
    report['sources']['currentCandidate'] = source['candidate']['candidate_commit']
    report['sources']['engineTree'] = source['candidate']['engine_tree']
    report['sources']['currentLabel'] = 'Qualified capture with public API follow-up'
    report['provenance']['publicApiFollowup'] = deepcopy(source)
    report['methodology'].append(
        'The selected bulk and ordinary lifecycle rows use the separately qualified public API follow-up. '
        'For these rows, RE2 and Java plain match counts use public count; boolean lines use public find. '
        'Span and capture workloads retain their existing matcher paths. '
        'Their predecessor results remain labeled by the earlier operation path. '
        'Untouched rows retain the original capture and its recorded source provenance. '
        'The follow-up does not blend pilot or previous measurements into its three-host medians.')
    report['methodology'].append(
        'Public count may initialize cached counting tables on first use. '
        'When no specialized count route applies, each call creates a matcher. '
        'Single-use rows include initialization; multi-use rows include any per-call fallback allocation. '
        'Reusing a pattern does not imply an allocation-free operation.')
    validate_language_report(report)
    return report


def verify_jdk(label, captured):
    vendor, version = label.split(' ', 1)
    build = re.search(r'^OpenJDK Runtime Environment ([^ ]+) \(build ([^)]+)\)$', captured, re.MULTILINE)
    if build is None or build.group(1) != f'{vendor}-{version}' or build.group(2).removesuffix('-LTS') != version:
        raise ValueError('follow-up JDK differs from original report')


def build(original_path, index_path, original_manifests, selected_manifests):
    from language_data import digest, language_rows

    originals = {json.loads(Path(path).read_text())['suite']: path for path in original_manifests}
    selected = {json.loads(Path(path).read_text())['suite']: path for path in selected_manifests}
    if (len(originals) != len(original_manifests) or len(selected) != len(selected_manifests) or
            set(originals) != {'language-bulk', 'language-lifecycle'} or set(selected) != set(originals)):
        raise ValueError('exactly one original and selected manifest per suite is required')
    manifests = [verify_selection(originals[suite], selected[suite]) for suite in sorted(originals)]
    original = load_json(Path(original_path))
    index = json.loads(Path(index_path).read_text())
    data = normalized_capture(index, manifests)
    for host in data['hosts']:
        verify_jdk(original['sources']['jdk'], host['provenance']['jdk'])
    replacements = language_rows(data, manifests)
    expected = {(language, cpu, mode, f"{manifest['suite']}/{case['id']}/{operation}")
                for manifest in manifests for case in manifest['cases']
                for language in ('re2', 'java', 'trino') for cpu in ('c9g', 'c8g', 'c8i')
                for mode in ('native', 'safe')
                for operation in (('compile', 'singleUseContains', 'singleUseCount', 'reusedContains', 'reusedCount')
                                  if manifest['suite'] == 'language-lifecycle' else ('execute',))}
    source = {
        'candidate': index['candidate'], 'campaignId': index['campaign_id'],
        'originalReportSha256': digest(original_path), 'validatedIndexSha256': digest(index_path),
        'acceptedLedgerSha256': index['ledger_sha256'], 'hostCount': index['accepted_batches'],
        'originalManifestSha256': {suite: digest(path) for suite, path in originals.items()},
        'selectedManifestSha256': {suite: digest(path) for suite, path in selected.items()},
        'comparisons': len(expected),
        'selection': 'All three independent primary hosts per CPU, language and case; native and safe paired with their comparator on each host. No pilot or failed attempt enters the medians.',
    }
    return replace_rows(original, replacements, expected, source)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('original', 'index', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('original-manifest', 'selected-manifest'):
        parser.add_argument('--' + name, type=Path, action='append', required=True)
    arguments = parser.parse_args()
    if arguments.output.exists():
        parser.error('Output exists; preserve previous report artifacts')
    report = build(arguments.original, arguments.index, arguments.original_manifest, arguments.selected_manifest)
    with arguments.output.open('x') as output:
        output.write(json.dumps(report, allow_nan=False, ensure_ascii=False, separators=(',', ':')) + '\n')
    print(f"Built {len(report['rows'])} report rows; {report['provenance']['publicApiFollowup']['comparisons']} replaced")


if __name__ == '__main__':
    main()
