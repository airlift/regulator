#!/usr/bin/env python3
"""Project a complete release campaign onto a frozen user-facing workload set."""

from __future__ import annotations

import argparse
import copy
import gzip
import hashlib
import json
from pathlib import Path


POLICY = 'user-facing-release-v1'
NOTES = json.loads((Path(__file__).resolve().parents[1] / 'src/workload-notes.json').read_text())


def read_bytes(path: Path) -> bytes:
    content = path.read_bytes()
    return gzip.decompress(content) if path.suffix == '.gz' else content


def load(path: Path):
    return json.loads(read_bytes(path))


def workload_key(row):
    return (row['caseId'], row['operation'], row['language'],
            row['platform'].replace('c', 'r', 1), row['memoryMode'])


def presentation_performance(row):
    if row['result']['state'] == 'not-compatible':
        return None
    if row.get('workload', {}).get('performanceContext') is not None:
        return row['workload']['performanceContext']
    for note in NOTES['performance']:
        matches = row['caseId'] in note.get('cases', []) or any(
            row['caseId'].startswith(prefix) for prefix in note.get('prefixes', []))
        if (matches
                and (not note.get('languages') or row['language'] in note['languages'])
                and row['operation'] in note['operations']
                and (not note.get('qualifiedOnly') or row.get('measurementSource', {}).get('cohort') is not None)):
            return note['text']
    return None


def publication_data(campaign, workloads, workload_source_sha256):
    artifact = campaign.get('provenance', {}).get('releasedArtifact', {})
    release_version = artifact.get('version')
    if not isinstance(release_version, str) or not release_version:
        raise ValueError('release publication requires released-artifact provenance')

    index = {}
    for row in campaign['rows']:
        index.setdefault(workload_key(row), []).append(row)

    selected = set()
    rows = []
    for previous in workloads['rows']:
        key = workload_key(previous)
        matches = index.get(key, [])
        # The final LIKE campaign has both an ordinary disabled single-use
        # result and a disabled control from the DFA collector. Preserve the
        # workload chosen by the frozen publication instead of selecting by
        # measured performance.
        if len(matches) > 1 and previous['language'] == 'like' and previous['operation'] == 'singleUse':
            matches = [row for row in matches if row['id'].startswith('baseline/like-single-use/')]
        if len(matches) != 1:
            raise ValueError(f'ambiguous or missing publication workload: {key}')
        row = matches[0]
        for field in ('caseId', 'operation', 'language', 'memoryMode', 'model', 'inputBytes'):
            if row[field] != previous[field]:
                raise ValueError(f'publication workload changed: {key}/{field}')
        for field in ('status', 'reason', 'patternPreview', 'patternBytes'):
            if row.get('mapping', {}).get(field) != previous.get('mapping', {}).get(field):
                raise ValueError(f'publication pattern mapping changed: {key}/{field}')
        identity = (row['id'], row['language'], row['platform'], row['memoryMode'])
        if identity in selected:
            raise ValueError(f'duplicate publication workload: {key}')
        selected.add(identity)
        published = copy.deepcopy(row)
        for field in ('population', 'family'):
            published[field] = previous[field]
        published['releasePerformanceContext'] = row.get('workload', {}).get('performanceContext')
        published['workload'] = copy.deepcopy(previous.get('workload')) or {}
        # An explicit empty value suppresses current fallback commentary when
        # the frozen publication showed no performance note for this row.
        published['workload']['performanceContext'] = presentation_performance(previous) or ''
        rows.append(published)

    result = copy.deepcopy(campaign)
    result['sources']['currentLabel'] = f'Regulator {release_version} results'
    result['sources']['releaseVersion'] = release_version
    result['rows'] = rows
    result['publicationScope'] = {
        'policy': POLICY,
        'workloadSourceCandidate': workloads['sources']['currentCandidate'],
        'workloadSourceSha256': workload_source_sha256,
        'workloadSourceRows': len(workloads['rows']),
        'completeCampaignRows': len(campaign['rows']),
        'publishedRows': len(rows),
        'excludedCampaignRows': len(campaign['rows']) - len(rows),
        'internalEvidence': 'docs/benchmarks/results-1.0/raw-archives.json',
    }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--campaign', type=Path, required=True,
                        help='complete verified campaign capture; retained outside the public report')
    parser.add_argument('--workloads', type=Path, required=True,
                        help='frozen public workload report whose identities and presentation are preserved')
    parser.add_argument('--output', type=Path, required=True)
    arguments = parser.parse_args()
    result = publication_data(
        load(arguments.campaign),
        load(arguments.workloads),
        hashlib.sha256(arguments.workloads.read_bytes()).hexdigest())
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(result, allow_nan=False, ensure_ascii=False, separators=(',', ':')) + '\n')
    print(f"Published {len(result['rows'])} user-facing rows; "
          f"excluded {result['publicationScope']['excludedCampaignRows']} campaign-only rows: {arguments.output}")


if __name__ == '__main__':
    main()
