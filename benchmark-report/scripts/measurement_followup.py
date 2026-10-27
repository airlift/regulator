#!/usr/bin/env python3
"""Read and project the archived analysis inputs of a measurement follow-up for replay."""

from __future__ import annotations

import gzip
import hashlib
import io
import json


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
