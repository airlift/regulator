#!/usr/bin/env python3
"""Apply a frozen measurement follow-up to a freshly reconstructed campaign."""

from __future__ import annotations

import copy
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
from statistics import mean


ROW_ANALYSIS_FIELDS = {
    'result', 'originalResult', 'originalResultLabel', 'measurementSource', 'hardware',
}


def read_bytes(path: Path) -> bytes:
    content = path.read_bytes()
    return gzip.decompress(content) if path.suffix == '.gz' else content


def load(path: Path):
    return json.loads(read_bytes(path))


def row_identity(row):
    return '|'.join(row[field] for field in ('id', 'language', 'platform', 'memoryMode'))


def immutable_report(report):
    value = copy.deepcopy(report)
    value['sources'].pop('currentLabel', None)
    value.pop('methodology', None)
    provenance = value.get('provenance', {})
    provenance.pop('measurementFollowup', None)
    # This identifies the verifier in the fresh checkout. The archived
    # reduction checksums retain the identity of the data being imported.
    provenance.pop('baselineReducerSha256', None)
    value['rows'] = [
        {key: item for key, item in row.items() if key not in ROW_ANALYSIS_FIELDS}
        for row in value['rows']
    ]
    return value


def analysis_entries(path, expected_sha256=None):
    if (expected_sha256 is not None and
            hashlib.sha256(path.read_bytes()).hexdigest() != expected_sha256):
        raise ValueError('measurement follow-up analysis inputs differ from provenance')
    entries = {}
    opener = gzip.open if path.suffix == '.gz' else open
    with opener(path, 'rt') as source:
        for line_number, line in enumerate(source, start=1):
            try:
                entry = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f'invalid analysis input at line {line_number}') from error
            identity = entry.get('identity')
            if not isinstance(identity, str) or not identity or entry.get('estimator') != 'mean':
                raise ValueError(f'invalid analysis input identity or estimator at line {line_number}')
            if identity in entries:
                raise ValueError('measurement follow-up analysis inputs contain duplicate rows')
            if not isinstance(entry.get('hosts'), list) or not entry['hosts']:
                raise ValueError(f'analysis input has no hosts at line {line_number}')
            entries[identity] = entry
    return entries


def projected_analysis_bytes(entries, identities):
    identities = set(identities)
    if identities != identities.intersection(entries):
        raise ValueError('public analysis projection is missing comparison inputs')
    buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode='wb', filename='', mtime=0) as output:
        for identity in sorted(identities):
            output.write((json.dumps(
                entries[identity], allow_nan=False, ensure_ascii=True,
                sort_keys=True, separators=(',', ':')) + '\n').encode())
    return buffer.getvalue()


def project_analysis_inputs(source, identities, destination=None):
    entries = analysis_entries(Path(source))
    content = projected_analysis_bytes(entries, identities)
    if destination is not None:
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
    return hashlib.sha256(content).hexdigest(), len(set(identities))


def normalized_mean(value):
    if 'groups' in value:
        if not value['groups'] or any(not group for group in value['groups']):
            raise ValueError('analysis input has empty process groups')
        return mean(mean(group) for group in value['groups'])
    if 'processMeansNs' in value:
        if not value['processMeansNs']:
            raise ValueError('analysis input has no process means')
        return mean(value['processMeansNs'])
    return value.get('meanNs')


def validate_result(row, entry):
    result = row.get('result', {})
    if result.get('state') != 'compared':
        return
    if result.get('estimator') != 'mean' or not isinstance(result.get('uncertainty'), dict):
        raise ValueError('compared measurement follow-up requires a mean estimate and uncertainty')
    report_hosts = {host.get('instanceId'): host for host in result.get('hosts', [])}
    input_hosts = {host.get('instanceId'): host for host in entry['hosts']}
    if (None in report_hosts or None in input_hosts or len(report_hosts) != len(result.get('hosts', []))
            or len(input_hosts) != len(entry['hosts']) or set(report_hosts) != set(input_hosts)):
        raise ValueError('measurement follow-up host identities differ from analysis inputs')
    costs = {'candidate': [], 'comparator': []}
    for instance_id, normalized in input_hosts.items():
        for side in costs:
            value = normalized_mean(normalized[side])
            if (isinstance(value, bool) or not isinstance(value, (int, float))
                    or not math.isfinite(value) or value <= 0):
                raise ValueError('analysis input has an invalid process mean')
            recorded = report_hosts[instance_id][side].get('meanNs')
            if (isinstance(recorded, bool) or not isinstance(recorded, (int, float))
                    or not math.isfinite(recorded)
                    or not math.isclose(value, recorded, rel_tol=1e-10)):
                raise ValueError('measurement follow-up host mean differs from analysis inputs')
            costs[side].append(value)
    candidate, comparator = mean(costs['candidate']), mean(costs['comparator'])
    expected = {
        'candidateNs': candidate,
        'comparatorNs': comparator,
        'ratio': candidate / comparator,
        'deltaNs': candidate - comparator,
    }
    if row.get('inputBytes'):
        expected['deltaNsPerByte'] = (candidate - comparator) / row['inputBytes']
    elif result.get('deltaNsPerByte') is not None:
        raise ValueError('measurement follow-up has an invalid normalized delta')
    if any(not math.isclose(result.get(field), value, rel_tol=1e-10, abs_tol=1e-12)
           for field, value in expected.items()):
        raise ValueError('measurement follow-up point estimate differs from analysis inputs')


def apply(fresh, followup, analysis_inputs):
    if fresh.get('provenance', {}).get('releasedArtifact') != followup.get('provenance', {}).get('releasedArtifact'):
        raise ValueError('measurement follow-up released-artifact identity differs')
    if immutable_report(fresh) != immutable_report(followup):
        raise ValueError('measurement follow-up changed campaign or workload identity')

    fresh_identities = [row_identity(row) for row in fresh['rows']]
    followup_identities = [row_identity(row) for row in followup['rows']]
    if (fresh_identities != followup_identities or
            len(fresh_identities) != len(set(fresh_identities))):
        raise ValueError('measurement follow-up row coverage or order differs')

    metadata = followup.get('provenance', {}).get('measurementFollowup')
    if not isinstance(metadata, dict) or metadata.get('schemaVersion') != 1:
        raise ValueError('measurement follow-up provenance is missing or unsupported')
    expected_analysis = metadata.get('analysisInputSha256')
    if not isinstance(expected_analysis, str) or len(expected_analysis) != 64:
        raise ValueError('measurement follow-up analysis-input identity is missing')
    entries = analysis_entries(Path(analysis_inputs), expected_analysis)
    expected_analysis_rows = {
        row_identity(row) for row in followup['rows'] if row['result']['state'] == 'compared'
    }
    observed_analysis = set(entries)
    if observed_analysis != expected_analysis_rows:
        missing = sorted(expected_analysis_rows - observed_analysis)
        extra = sorted(observed_analysis - expected_analysis_rows)
        raise ValueError(
            f'measurement follow-up analysis coverage differs: missing={missing[:3]}, extra={extra[:3]}')
    for row in followup['rows']:
        if row['result']['state'] == 'compared':
            validate_result(row, entries[row_identity(row)])

    result = copy.deepcopy(fresh)
    result['sources']['currentLabel'] = followup['sources']['currentLabel']
    result['methodology'] = copy.deepcopy(followup['methodology'])
    result['provenance']['measurementFollowup'] = {
        **copy.deepcopy(metadata), 'analysisInputRows': len(entries),
        'analysisInputScope': 'complete-campaign-comparisons',
    }
    for target, saved in zip(result['rows'], followup['rows']):
        for field in ROW_ANALYSIS_FIELDS:
            if field in saved:
                target[field] = copy.deepcopy(saved[field])
            else:
                target.pop(field, None)
    return result
