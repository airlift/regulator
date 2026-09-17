"""Reduce verified language exports without discarding hosts or noisy samples."""

from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
from statistics import mean, median, stdev


LANGUAGES = {
    're2': {'label': 'RE2', 'comparator': 'native RE2'},
    'java': {'label': 'Java regex', 'comparator': 'JDK Pattern'},
    'trino': {'label': 'Trino regex', 'comparator': 'Joni'},
    'like': {'label': 'LIKE', 'comparator': 'Trino SQL LIKE'},
}
PLATFORMS = {'c9g': 'C9g · Graviton5', 'c8g': 'C8g · Graviton4', 'c8i': 'C8i · Intel'}
RELEASE_PLATFORMS = {'r9g': 'R9g · Graviton5', 'r8g': 'R8g · Graviton4', 'r8i': 'R8i · Intel'}
MEMORY_MODES = {'native': 'Native memory', 'safe': 'Pure Java'}
STATES = {'compared', 'not-compatible', 'did-not-finish'}


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def coefficient(values):
    return stdev(values) / mean(values) if len(values) > 1 else 0


def timing(observation):
    groups = observation['samples_ns']
    if not groups or any(not group for group in groups):
        raise ValueError('missing measurement samples')
    samples = [value for group in groups for value in group]
    if any(isinstance(value, bool) or not math.isfinite(value) or value <= 0 for value in samples):
        raise ValueError('invalid measurement sample')
    # Iterations in one fork are correlated. Keep the independent process
    # means as well as the collector's sample-level precision calculation.
    epochs = [mean(group) for group in groups]
    allocation = observation.get('allocation')
    return {
        'medianNs': median(samples), 'meanNs': mean(samples),
        'sampleCount': len(samples), 'epochMeansNs': epochs,
        'sampleRse': coefficient(samples) / math.sqrt(len(samples)),
        'epochRse': coefficient(epochs) / math.sqrt(len(epochs)),
        'allocationBytes': allocation['score'] if allocation else None,
    }


def outcome(observation):
    state = observation['outcome']
    if state not in STATES:
        raise ValueError(f'unpublishable outcome: {state}')
    if state == 'compared':
        return {'state': state, **timing(observation)}
    if state == 'not-compatible':
        if not observation.get('reason'):
            raise ValueError('incompatibility must have a reason')
        return {'state': state, 'reason': observation['reason']}
    attempts = observation.get('attempts', [])
    if len(attempts) < 2 or any(attempt.get('outcome') != state for attempt in attempts):
        raise ValueError('timeout requires repeated evidence')
    limits = {(attempt['phase'], attempt['limit_seconds']) for attempt in attempts}
    if len(limits) != 1:
        raise ValueError('timeout attempts disagree about their limits')
    phase, limit = next(iter(limits))
    if not isinstance(limit, (int, float)) or not math.isfinite(limit) or limit <= 0:
        raise ValueError('invalid timeout limit')
    return {'state': state, 'reason': f'{phase} exceeded {limit:g} s on {len(attempts)} attempts',
            'phase': phase, 'limitSeconds': limit}


def reduce_hosts(hosts, normalization_bytes=None):
    if not hosts or len({host['instanceId'] for host in hosts}) != len(hosts):
        raise ValueError('comparison requires independent hosts')
    states = {(host['candidate']['state'], host['comparator']['state']) for host in hosts}
    if len(states) != 1:
        raise ValueError('hosts disagree about the comparison outcome')
    candidate_state, comparator_state = next(iter(states))
    warnings = []
    if (candidate_state, comparator_state) != ('compared', 'compared'):
        states = {candidate_state, comparator_state}
        state = 'not-compatible' if 'not-compatible' in states else 'did-not-finish'
        reasons = sorted({f"{side}: {host[side]['reason']}" for host in hosts
                          for side in ('candidate', 'comparator') if host[side]['state'] != 'compared'})
        return {'state': state, 'reason': '; '.join(reasons), 'hosts': hosts, 'warnings': warnings}
    ratios = [host['candidate']['medianNs'] / host['comparator']['medianNs'] for host in hosts]
    deltas = [host['candidate']['medianNs'] - host['comparator']['medianNs'] for host in hosts]
    if coefficient(ratios) > .05:
        warnings.append('Host ratio variation exceeds 5%')
    if min(ratios) < .98 and max(ratios) > 1.02:
        warnings.append('Hosts disagree about which engine is faster')
    for side, label in (('candidate', 'Regulator'), ('comparator', 'Comparator')):
        if any(host[side].get('sampleRse', 0) > .05 for host in hosts):
            warnings.append(f'{label} sample precision exceeds 5%')
        if any(host[side].get('epochRse', 0) > .05 for host in hosts):
            warnings.append(f'{label} process-level precision exceeds 5%')
    return {
        'state': 'compared', 'candidateNs': median([host['candidate']['medianNs'] for host in hosts]),
        'comparatorNs': median([host['comparator']['medianNs'] for host in hosts]),
        'ratio': median(ratios), 'minimumRatio': min(ratios), 'maximumRatio': max(ratios),
        'deltaNs': median(deltas),
        'deltaNsPerByte': median(deltas) / normalization_bytes if normalization_bytes else None,
        'hosts': hosts, 'warnings': warnings,
    }


def language_rows(data, manifests, selection=None, platforms=PLATFORMS):
    observations = {(tuple(row['logical_identity']), row['id']): row for row in data['observations']}
    if len(observations) != len(data['observations']):
        raise ValueError('duplicate observation')
    hosts = {tuple(host['logical_identity']): host for host in data['hosts']}
    if len(hosts) != len(data['hosts']):
        raise ValueError('duplicate logical host')
    cases = {(manifest['suite'], case['id']): case for manifest in manifests for case in manifest['cases']}
    groups = defaultdict(list)
    descriptions = {}
    seen = set()
    for comparison in data['comparisons']:
        identity = tuple(comparison['logical_identity'])
        key = (identity, comparison['id'])
        if key in seen:
            raise ValueError('duplicate comparison')
        seen.add(key)
        suite, case_id, language, platform, replica = identity
        if comparison['logical_case'] != case_id or comparison['language'] != language:
            raise ValueError('comparison identity disagrees with its host')
        case = cases[suite, case_id]
        host = hosts[identity]
        encoded_case = json.dumps(case, sort_keys=True, ensure_ascii=True, allow_nan=False).encode()
        if hashlib.sha256(encoded_case).hexdigest() != host['case_sha256']:
            raise ValueError('case manifest does not match captured identity')
        mode, operation = comparison['mode'], comparison['operation']
        key = (language, platform, mode, suite, case_id, operation)
        groups[key].append({
            'replica': replica, 'instanceId': host['instance_id'], 'collector': host['collector'],
            'candidate': outcome(observations[identity, comparison['candidate']]),
            'comparator': outcome(observations[identity, comparison['comparator']]),
        })
        if key in descriptions and descriptions[key].get('work_contract') != comparison.get('work_contract'):
            raise ValueError('mixed benchmark call contracts across hosts')
        descriptions[key] = comparison
    rows = []
    for key, evidence in sorted(groups.items()):
        language, platform, mode, suite, case_id, operation = key
        if {host['replica'] for host in evidence} != {1, 2, 3} or len(evidence) != 3:
            raise ValueError(f'incomplete host coverage: {key}')
        case = cases[suite, case_id]
        size = descriptions[key]['input_bytes_per_operation']
        if operation == 'compile' and size is not None:
            raise ValueError('compilation must not be normalized per input byte')
        mapping = case['mappings'][language]
        pattern = mapping.get('pattern')
        if pattern is None:
            pattern = bytes.fromhex(mapping['pattern_hex']).decode('utf-8', errors='backslashreplace')
        rows.append({
            'id': f'{suite}/{case_id}/{operation}', 'caseId': case_id,
            'name': case_id, 'operation': operation, 'model': case.get('model', operation),
            'population': case['population'], 'family': case['family'],
            'language': language, 'platform': platform, 'memoryMode': mode,
            'inputBytes': size, 'source': 'language',
            'mapping': {'status': mapping['status'], 'reason': mapping['reason'],
                        'patternPreview': pattern[:240], 'patternBytes': case['pattern_bytes']},
            'result': reduce_hosts(evidence, size),
        })
        if descriptions[key].get('work_contract') is not None:
            rows[-1]['workContract'] = descriptions[key]['work_contract']
    complete = {(manifest['suite'], case['id'], language)
                for manifest in manifests for case in manifest['cases'] for language in ('re2', 'java', 'trino')}
    selected = complete if selection is None else set(map(tuple, selection))
    if not selected or not selected <= complete or (selection is not None and len(selected) != len(selection)):
        raise ValueError('invalid or duplicate language selection')
    expected = {(language, platform, mode, f'{suite}/{case_id}/{operation}')
                for suite, case_id, language in selected
                for platform in platforms for mode in MEMORY_MODES
                for operation in (('compile', 'singleUseContains', 'singleUseCount', 'reusedContains', 'reusedCount')
                                  if suite == 'language-lifecycle' else
                                  ('compile',) if cases[suite, case_id]['model'] == 'compile' else ('execute',))}
    actual = {(row['language'], row['platform'], row['memoryMode'], row['id']) for row in rows}
    if actual != expected or len(rows) != len(expected):
        raise ValueError(f'language coverage mismatch: expected {len(expected)}, got {len(rows)}')
    return rows


def validate_estimate(result):
    """Check declared point estimates and intervals without inventing raw samples."""
    estimator = result.get('estimator')
    if estimator is not None and estimator not in {'mean', 'median'}:
        raise ValueError('unknown timing estimator')
    if estimator == 'mean':
        for side, field in (('candidate', 'candidateNs'), ('comparator', 'comparatorNs')):
            costs = [host[side].get('meanNs') for host in result['hosts']]
            if any(type(value) not in (int, float) or not math.isfinite(value) or value <= 0 for value in costs):
                raise ValueError('mean estimator requires measured host means')
            if not math.isclose(result[field], mean(costs), rel_tol=1e-10):
                raise ValueError('mean estimate differs from equal-weight host means')
        if not math.isclose(result['ratio'], result['candidateNs'] / result['comparatorNs'], rel_tol=1e-10):
            raise ValueError('mean ratio differs from ratio of mean costs')
        if not math.isclose(result['deltaNs'], result['candidateNs'] - result['comparatorNs'], rel_tol=1e-10, abs_tol=1e-12):
            raise ValueError('mean delta differs from operation costs')
    interval = result.get('uncertainty')
    if interval is None:
        return
    if (estimator is None or interval.get('method') != 'hierarchical-bootstrap-v1' or interval.get('level') != .95
            or interval.get('replicates') != 4000 or interval.get('hostCount') != len(result['hosts'])):
        raise ValueError('invalid uncertainty method or independent-host count')
    def bounds(values):
        if (not isinstance(values, list) or len(values) != 2
                or any(type(value) not in (int, float) or not math.isfinite(value) or value <= 0 for value in values)
                or values[0] > values[1]):
            raise ValueError('invalid uncertainty interval or process range')
    for key in ('ratioInterval', 'candidateIntervalNs', 'comparatorIntervalNs'):
        bounds(interval.get(key))
    for side in ('candidate', 'comparator'):
        values = interval.get('forkMeanRangeNs', {}).get(side)
        if values is not None:
            bounds(values)
        counts = interval.get('processCounts', {}).get(side)
        if (not isinstance(counts, list) or len(counts) != len(result['hosts'])
                or any(value is not None and (type(value) is not int or value <= 0) for value in counts)):
            raise ValueError('invalid independent-process counts')


def validate_hardware(row):
    allocation = row.get('hardware')
    if allocation is None:
        return
    instance_type = allocation.get('instanceType')
    expected = {row['platform'] + '.large': 2, row['platform'] + '.2xlarge': 8}
    if instance_type not in expected or allocation.get('allocatedVcpus') != expected[instance_type]:
        raise ValueError('invalid row hardware allocation')
    if any(host.get('instanceType') != instance_type or host.get('allocatedVcpus') != expected[instance_type]
           for host in row['result']['hosts']):
        raise ValueError('comparison mixes hardware allocations or lacks host provenance')


def validate_language_report(data):
    if data.get('schemaVersion') != 2:
        raise ValueError('unsupported language report schema')
    publication = data.get('publication')
    preliminary = publication is not None
    if preliminary:
        if (not isinstance(publication, dict) or publication.get('status') != 'preliminary'
                or publication.get('sourcePolicy') != 'mixed-development-revisions'
                or not isinstance(publication.get('note'), str) or not publication['note'].strip()
                or 'preliminary' not in data['sources']['currentLabel'].lower()):
            raise ValueError('invalid preliminary publication metadata')
        if data.get('presentation', {}).get('reviewPreview'):
            raise ValueError('preliminary publication cannot use private-preview presentation')
    platforms = data['platforms']
    if set(platforms) not in (set(PLATFORMS), set(RELEASE_PLATFORMS)) or set(data['memoryModes']) != set(MEMORY_MODES):
        raise ValueError('missing platform or memory mode')
    if set(data['languages']) != set(LANGUAGES):
        raise ValueError('missing language')
    if not data.get('methodology') or not data.get('provenance'):
        raise ValueError('report requires method and source provenance')
    for key in ('currentLabel', 'currentCandidate', 'engineTree', 'jdk'):
        if not isinstance(data['sources'][key], str) or not data['sources'][key]:
            raise ValueError(f'missing source {key}')
    seen = set()
    coverage = defaultdict(set)
    identities = defaultdict(set)
    lifecycle = defaultdict(set)
    for row in data['rows']:
        language, platform, mode = row['language'], row['platform'], row['memoryMode']
        if language not in LANGUAGES or platform not in platforms or mode not in MEMORY_MODES:
            raise ValueError('unknown language, CPU, or memory mode')
        identity = (language, platform, mode, row['id'])
        if identity in seen:
            raise ValueError('duplicate report row')
        seen.add(identity)
        # Public language and LIKE matrices are exhaustive in every view.
        # Older internal-engine diagnostics have native-only routes by design.
        if row['source'] == 'language' or language == 'like':
            identities[language, platform, mode].add(row['id'])
        for key in ('id', 'caseId', 'name', 'operation', 'model', 'population', 'family', 'source'):
            if not isinstance(row[key], str) or not row[key]:
                raise ValueError(f'missing row {key}')
        if 'workContract' in row:
            contracts = set()
            if row['source'].startswith('baseline') and language == 'trino' and row['operation'] in {'extractAll', 'split', 'replaceLambda'}:
                contracts = {'joni-slice-output-v1'}
            elif row['source'] == 'language' and row['operation'] != 'execute':
                contracts = {'public-pattern-lifecycle-v1'}
            elif row['source'] == 'language':
                contracts = {'bulk-matched-outputs-v1'}
                if language == 'trino' and row['model'] in {'count-spans', 'count-captures', 'grep-captures'}:
                    contracts = {'trino-output-assisted-count-v1', 'trino-matcher-count-v3'}
                elif language in {'re2', 'java'} and row['model'] in {'grep', 'count'}:
                    contracts = {'public-find-count-v2'}
            if row['workContract'] not in contracts:
                raise ValueError('invalid benchmark work contract')
        coverage[language, platform, mode].add((row['source'], row['operation']))
        lifecycle[language, platform, mode, row['caseId']].add(row['operation'])
        size = row['inputBytes']
        if size is not None and (isinstance(size, bool) or not isinstance(size, (int, float)) or not math.isfinite(size) or size < 0):
            raise ValueError('invalid input size')
        validate_hardware(row)
        results = [row['result']]
        if 'originalResult' in row:
            results.append(row['originalResult'])
        for result in results:
            if result['state'] not in STATES:
                raise ValueError('unpublishable result')
            if not isinstance(result['warnings'], list) or any(not isinstance(value, str) or not value for value in result['warnings']):
                raise ValueError('invalid timing warning')
            if len(result['hosts']) < (1 if preliminary else 3) or len({host['instanceId'] for host in result['hosts']}) != len(result['hosts']):
                raise ValueError('preliminary report requires independent hosts' if preliminary
                                 else 'report requires at least three independent hosts per row')
            if preliminary and len(result['hosts']) < 3 and (not isinstance(row.get('measurementSource'), dict) or not row['measurementSource']):
                raise ValueError('preliminary targeted rows require measurement source provenance')
            if result['state'] == 'compared':
                for key in ('candidateNs', 'comparatorNs', 'ratio', 'minimumRatio', 'maximumRatio'):
                    value = result[key]
                    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
                        raise ValueError(f'invalid {key}')
                if not result['minimumRatio'] <= result['ratio'] <= result['maximumRatio']:
                    raise ValueError('ratio is outside host range')
                if isinstance(result['deltaNs'], bool) or not isinstance(result['deltaNs'], (int, float)) or not math.isfinite(result['deltaNs']):
                    raise ValueError('invalid delta')
                normalized = result['deltaNsPerByte']
                if normalized is not None:
                    if not row['inputBytes'] or row['inputBytes'] <= 0 or row['operation'] == 'compile':
                        raise ValueError('invalid input-byte normalization')
                    if not math.isclose(normalized, result['deltaNs'] / row['inputBytes'], rel_tol=1e-10, abs_tol=1e-12):
                        raise ValueError('normalized delta does not match operation delta')
                validate_estimate(result)
            else:
                if result.get('uncertainty') is not None:
                    raise ValueError('non-comparable outcome cannot have ratio uncertainty')
                if not result.get('reason') or any(key in result for key in ('ratio', 'candidateNs', 'comparatorNs', 'deltaNs', 'deltaNsPerByte')):
                    raise ValueError('non-comparable result must have a reason and no numerical comparison')
    for language in LANGUAGES:
        for platform in platforms:
            for mode in MEMORY_MODES:
                operations = coverage[language, platform, mode]
                if language == 'like':
                    source = 'baseline' if data.get('provenance', {}).get('releasedArtifact') else 'like-supplement'
                    required = {('baseline', 'matches'), (source, 'compile'), (source, 'singleUse')}
                else:
                    required = {('language', operation) for operation in (
                        'compile', 'reusedContains', 'reusedCount',
                        'singleUseContains', 'singleUseCount', 'execute')}
                if not required <= operations:
                    raise ValueError(f'incomplete language/lifecycle coverage: {language}/{platform}/{mode}')
                if identities[language, platform, mode] != identities[language, next(iter(platforms)), 'native']:
                    raise ValueError('workload identities differ across CPUs or memory modes')
    for operations in lifecycle.values():
        for single, reused in (('singleUse', 'matches'), ('singleUseContains', 'reusedContains'), ('singleUseCount', 'reusedCount')):
            if single in operations and reused not in operations:
                raise ValueError('missing reused lifecycle partner')
    shared = {language: {row['id'] for row in data['rows'] if row['language'] == language and row['source'] == 'language'}
              for language in ('re2', 'java', 'trino')}
    if shared['re2'] != shared['java'] or shared['re2'] != shared['trino']:
        raise ValueError('shared workload identities differ across regex languages')
