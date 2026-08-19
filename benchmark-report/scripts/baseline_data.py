"""Adapt frozen baseline comparisons to the language report's result schema."""

from collections import defaultdict
import csv
from pathlib import Path
from statistics import median

from language_data import reduce_hosts


# Sizes in the frozen BenchmarkTrinoLike scenario definitions. They describe
# the complete input, including bytes skipped by prefix or suffix kernels.
LIKE_INPUT_BYTES = {
    'EXACT_MATCH': 6, 'PREFIX_LARGE': 32768, 'SUFFIX_LARGE': 32768,
    'CONTAINS_ABSENT': 32768, 'CONTAINS_LATE': 32768, 'ORDERED_LATE': 32768,
    'ORDERED_DENSE_FALSE': 32768, 'ANY_ASCII': 1, 'ANY_MULTIBYTE': 4,
    'MIXED_LATE': 32768, 'MIXED_ABSENT': 32768, 'WILDCARD_CHAIN': 1024,
}
BASELINE_WARNINGS = {
    'precision-rejected': 'At least one original host exceeded the sample precision gate',
    'host-direction-disagreement': 'Hosts disagree about which engine is faster',
    'native-bracket-drift-above-5-percent': 'Native comparator changed by more than 5% between bracketing measurements',
    'ratio-host-cv-above-5-percent': 'Host ratio variation exceeds 5%',
}


def read_rows(path):
    with Path(path).open() as source:
        return list(csv.DictReader(source, delimiter='\t'))


def output_contract(pairs, metadata):
    """Require captured contracts for both routes of every paired host."""
    contracts = []
    for pair in pairs:
        routes = defaultdict(dict)
        for row in metadata:
            if (all(row[field] == pair[field] for field in
                    ('platform', 'shard_id', 'replica_id', 'host_epoch'))
                    and row['source'].endswith('/run-metadata.txt')):
                if row['key'] in routes[row['source']]:
                    raise ValueError('duplicate captured route metadata')
                routes[row['source']][row['key']] = row['value']
        for system in (pair['candidate_system'], pair['comparator_system']):
            matching = [route for route in routes.values()
                        if system in route.get('systems', '').split(',')]
            if len(matching) > 1:
                raise ValueError('ambiguous captured output contract')
            contracts.append(matching[0].get('trino_output_contract') if matching else None)
    if any(contract is not None for contract in contracts):
        if set(contracts) != {'joni-slice-output-v1'}:
            raise ValueError('mixed or unknown Trino output contracts')
        return {'workContract': 'joni-slice-output-v1'}
    return {}


def baseline_rows(directory, lifecycle_manifest):
    directory = Path(directory)
    metadata_path = directory / 'metadata.tsv'
    metadata = read_rows(metadata_path) if metadata_path.exists() else []
    aggregates = {(row['platform'], row['comparison_id']): row
                  for row in read_rows(directory / 'comparison-aggregates.tsv')}
    timings = {(row['platform'], row['row_id']): row
               for row in read_rows(directory / 'row-aggregates.tsv')}
    hosts = {(row['platform'], row['host_epoch'], row['row_id']): row
             for row in read_rows(directory / 'host-rows.tsv')}
    sizes = {case['id']: case['input_bytes_per_operation'] for case in lifecycle_manifest['cases']}
    cases = {case['id']: case for case in lifecycle_manifest['cases']}
    grouped = defaultdict(list)
    for row in read_rows(directory / 'host-comparator-ratios.tsv'):
        if row['score_unit'] == 'ns/op':
            grouped[row['platform'], row['comparison_id']].append(row)
    output = []
    for identity, pairs in sorted(grouped.items()):
        row = pairs[0]
        suite, candidate_system, comparator = row['suite'], row['candidate_system'], row['comparator_system']
        if suite == 'trino-like' and comparator in {'trino-sql', 'trino-optimized'}:
            language, family = 'like', 'LIKE'
            population = 'like' if comparator == 'trino-sql' else 'diagnostics-and-stress'
            if comparator == 'trino-optimized':
                family = 'Optional Trino DFA matcher'
            modes = ('native', 'safe')
        elif suite.startswith('trino-') and comparator == 'joni':
            language = 'trino'
            population = 'trino-operations' if suite == 'trino-everyday-operations' else 'diagnostics-and-stress'
            family = {'trino-public-operations': 'Fixed-input public operations', 'trino-final-line': 'Final-line semantics'}.get(suite, 'Trino operations')
            modes = ('native',) if candidate_system == 'regulator-native-access' else ('safe',)
        elif comparator == 'native-re2' and suite == 'traditional':
            language, population, family = 're2', 'diagnostics-and-stress', 'Engine-route and scaling controls'
            modes = ('native',) if candidate_system == 'regulator-native-access' else ('safe',)
        else:
            # Official Rebar, allocation and internal route results are retained
            # in the full baseline download, not relabeled as a public frontend.
            continue
        candidate = timings[row['platform'], row['candidate_row_id']]
        parameters = dict(item.split('=', 1) for item in candidate['parameters'].split(';') if '=' in item)
        operation = candidate['benchmark'].rsplit('.', 1)[-1]
        case_id = row['logical_row_id']
        name = case_id
        size = None
        if suite == 'trino-everyday-operations':
            workload = parameters['workload']
            case_id = 'everyday/' + workload
            size = sizes[case_id]
            family = cases[case_id]['family']
            name = workload
        elif suite == 'trino-like':
            case_id = 'trino-like/' + parameters['scenario']
            name = parameters['scenario'].replace('_', ' ').lower()
            size = LIKE_INPUT_BYTES[parameters['scenario']]
            if comparator == 'trino-optimized':
                case_id += '/optimized'
        elif 'sourceLength' in parameters:
            size = int(parameters['sourceLength'])
        elif 'size' in parameters and parameters['size'].isdigit():
            size = int(parameters['size'])
        if 'compile' in operation.lower():
            size = None
            if language == 're2':
                # These are internal parse/simplify/program phases, collected
                # only in native mode. Shared-language compile rows measure
                # the public compiler in both modes and own the stress table.
                continue
        evidence = []
        for pair in pairs:
            host = hosts[pair['platform'], pair['host_epoch'], pair['candidate_row_id']]
            evidence.append({
                'replica': int(pair['replica_id']), 'instanceId': host['instance_id'],
                'candidate': {'state': 'compared', 'medianNs': float(pair['candidate_score'])},
                'comparator': {'state': 'compared', 'medianNs': float(pair['comparator_score'])},
            })
        result = reduce_hosts(evidence, size)
        contract = output_contract(pairs, metadata) if language == 'trino' and operation in {
            'extractAll', 'split', 'replaceLambda'} else {}
        # The baseline reducer already checked raw-sample precision. Do not
        # infer precise timing from its reduced medians alone.
        aggregate = aggregates[identity]
        if aggregate['unresolved'] == 'true':
            result['warnings'].extend(BASELINE_WARNINGS[reason] for reason in aggregate['unresolved_reasons'].split(','))
            result['warnings'] = list(dict.fromkeys(result['warnings']))
        for mode in modes:
            output.append({
                'id': 'baseline/' + row['logical_row_id'] + ('/optimized' if comparator == 'trino-optimized' else ''), 'caseId': case_id, 'name': name,
                'operation': operation, 'model': operation, 'population': population, 'family': family,
                'language': language, 'platform': row['platform'], 'memoryMode': mode,
                'comparator': 'Trino DFA' if comparator == 'trino-optimized' else None,
                'inputBytes': size, 'source': 'baseline', 'result': result,
                **contract,
            })
    return output
