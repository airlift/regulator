"""Adapt frozen baseline comparisons to the language report's result schema."""

from collections import defaultdict
import csv
from pathlib import Path
from statistics import median

from language_data import SPECIALIZED_VARIANT_SUFFIXES, reduce_hosts


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
# A variant's system is regulator, its variant, then its memory route: regulator-generated-object-row.
SPECIALIZED_SYSTEM_VARIANTS = {'': '', '-regular-engine': '/regular-engine', '-generated': '/generated'}
# Regex variants run on each memory route; LIKE has no routes, so its rows appear in both views.
SPECIALIZED_ROUTES = {'-native-access': ('native',), '-object-row': ('safe',), '': ('native', 'safe')}
# The report language of each baseline comparator a specialized-pattern catalog row can name.
COMPARATOR_LANGUAGES = {'native-re2': 're2', 'joni': 'trino', 'trino-sql': 'like'}
BENCHMARK_TOOLS = Path(__file__).resolve().parents[2] / 'tools/re2-benchmark'
SPECIALIZED_CATALOG = BENCHMARK_TOOLS / 'baseline/specialized-patterns.tsv'


def read_rows(path):
    with Path(path).open() as source:
        return list(csv.DictReader(source, delimiter='\t'))


def specialized_suite(suite):
    """Whether a baseline suite measures specialized patterns, as each suite named for them does."""
    return 'specialized' in suite.split('-')


def specialized_patterns():
    """Read the baseline's specialized-pattern catalog, keyed by shard, workload and operation."""
    # Patterns contain quotes, so split the lines rather than parse CSV.
    lines = SPECIALIZED_CATALOG.read_text().splitlines()
    fields = lines[0].split('\t')
    catalog = {}
    for line in lines[1:]:
        entry = dict(zip(fields, line.split('\t'), strict=True))
        key = (entry['shard_id'], entry['workload'], entry['operation'])
        if key in catalog:
            raise ValueError(f'duplicate specialized pattern: {"/".join(key)}')
        catalog[key] = entry
    return catalog


def tpch_columns():
    """Return TPC-H column metadata, with its LIKE pattern, keyed by benchmark workload."""
    columns = {row['column']: row for row in read_rows(BENCHMARK_TOOLS / 'manifests/tpch-like-corpus.tsv')}
    # BenchmarkTrinoLikeTpch names each workload id after its enum constant: Q02_TYPE_BRASS is q02-type-brass.
    return {row['id'].upper().replace('-', '_'): {**columns[row['column']], 'pattern': row['pattern']}
            for row in read_rows(BENCHMARK_TOOLS / 'manifests/tpch-like-workloads.tsv')}


def specialized_variant(language, system):
    """Return a specialized-pattern system's case-ID suffix and memory route."""
    routes = ('',) if language == 'like' else ('-native-access', '-object-row')
    for fragment, suffix in SPECIALIZED_SYSTEM_VARIANTS.items():
        for route in routes:
            if system == 'regulator' + fragment + route:
                return suffix, route
    raise ValueError(f'unknown specialized-pattern system: {system}')


def variant_gain_warnings(variant, baseline, hosts):
    """Warn about one variant's gain over another from each host's own pair of times.

    Both variants run beside one comparator measurement on each host, so the
    comparator cancels from the host's gain: its variation and its direction
    against Regulator say nothing about the gain.
    """
    logical = variant[0]['logical_row_id']
    def by_host(pairs):
        return {(pair['replica_id'], pair['host_epoch']): pair for pair in pairs}
    variant, baseline = by_host(variant), by_host(baseline)
    if variant.keys() != baseline.keys():
        raise ValueError(f'specialized-pattern variants of {logical} must share one host cohort')
    evidence = []
    for host in sorted(variant):
        pairs = (variant[host], baseline[host])
        if len({(pair['comparator_row_ids'], pair['comparator_score']) for pair in pairs}) != 1:
            raise ValueError(f'specialized-pattern variants of {logical} must share one comparator measurement on host {host[1]}')
        evidence.append({'instanceId': host, 'candidate': {'state': 'compared', 'medianNs': float(pairs[0]['candidate_score'])},
                         'comparator': {'state': 'compared', 'medianNs': float(pairs[1]['candidate_score'])}})
    warnings = reduce_hosts(evidence)['warnings']
    # Raw-sample precision still applies to either variant's own measurement.
    pairs = [*variant.values(), *baseline.values()]
    if any(hosts[pair['platform'], pair['host_epoch'], pair['candidate_row_id']]['outcome'] != 'accepted' for pair in pairs):
        warnings.append(BASELINE_WARNINGS['precision-rejected'])
    return warnings


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
    # Only specialized-pattern rows read the live catalogs, so captures without them never depend on those files.
    catalog = columns = None
    grouped = defaultdict(list)
    for row in read_rows(directory / 'host-comparator-ratios.tsv'):
        if specialized_suite(row['suite']):
            # Reject specialized comparisons instead of silently dropping unsupported units. The
            # reducer scales the comparator's unit into the ratio, but these rows publish both times.
            units = {row['score_unit'], *(hosts[row['platform'], row['host_epoch'], comparator]['score_unit']
                                          for comparator in row['comparator_row_ids'].split(','))}
            if units != {'ns/op'}:
                raise ValueError(f'specialized-pattern comparison {row["comparison_id"]} is in {", ".join(sorted(units - {"ns/op"}))}, not ns/op')
        if row['score_unit'] == 'ns/op':
            grouped[row['platform'], row['comparison_id']].append(row)
    output = []
    # Each specialized pattern's variants, keyed by platform, logical row and memory route.
    patterns = defaultdict(dict)
    for identity, pairs in sorted(grouped.items()):
        row = pairs[0]
        suite, candidate_system, comparator = row['suite'], row['candidate_system'], row['comparator_system']
        variant, route = '', None
        if specialized_suite(suite):
            if catalog is None:
                catalog = specialized_patterns()
            # Row IDs begin with the shard, and regex row IDs name the operation after the workload.
            # Each regex benchmark method also names its variant; LIKE's method is its one operation, matches.
            shard, _, workload_operation = row['logical_row_id'].partition('/')
            workload, _, operation = workload_operation.partition('/')
            operation = operation or timings[row['platform'], row['candidate_row_id']]['benchmark'].rsplit('.', 1)[-1]
            entry = catalog.get((shard, workload, operation))
            if entry is None:
                raise ValueError(f'specialized-pattern row is not in the catalog: {row["logical_row_id"]}')
            if comparator != entry['comparator']:
                raise ValueError(f'specialized-pattern row {row["logical_row_id"]} is compared with {comparator}, not {entry["comparator"]}')
            language = COMPARATOR_LANGUAGES[comparator]
            population, family = 'specialized-patterns', 'Specialized engine and compiler'
            variant, route = specialized_variant(language, candidate_system)
            modes = SPECIALIZED_ROUTES[route]
        elif suite == 'trino-like' and comparator in {'trino-sql', 'trino-optimized'}:
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
        report_id = case_id
        name = case_id
        size = None
        workload_details = {}
        if specialized_suite(suite):
            operation = entry['operation']
            if parameters['workload'] != workload:
                raise ValueError(f'specialized-pattern row {row["logical_row_id"]} measures workload {parameters["workload"]}')
            name = entry['name']
            case_id = entry['case_prefix'] + workload + variant
            # The regex workloads are split across shards, so the report names them by suite.
            report_id = suite + '/' + workload_operation
            workload_details = {'workload': {'pattern': entry['pattern'], 'description': entry['description']}}
            if language == 'like':
                # Each LIKE operation counts the matches in the leading rows of one TPC-H column.
                if columns is None:
                    columns = tpch_columns()
                column = columns[workload]
                if column['pattern'] != entry['pattern']:
                    raise ValueError(f'specialized LIKE pattern differs from its TPC-H workload: {workload}')
                rows_scanned = int(column['row_count'])
                scanned = f'{column["table"]}.{column["field"]} at scale factor {column["scale_factor"]}'
                workload_details['workload'] |= {
                    'columnRows': rows_scanned, 'inputDescription': f'Each operation scans the first {rows_scanned:,} rows of TPC-H {scanned}.'}
        elif suite == 'trino-everyday-operations':
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
        views = []
        for mode in modes:
            views.append({
                'id': 'baseline/' + report_id + ('/optimized' if comparator == 'trino-optimized' else variant), 'caseId': case_id, 'name': name,
                'operation': operation, 'model': operation, 'population': population, 'family': family,
                'language': language, 'platform': row['platform'], 'memoryMode': mode,
                'comparator': 'Trino DFA' if comparator == 'trino-optimized' else None,
                'inputBytes': size, 'source': 'baseline', 'result': result,
                **workload_details, **contract,
            })
        output.extend(views)
        if population == 'specialized-patterns':
            patterns[row['platform'], row['logical_row_id'], route][variant] = (pairs, views)
    # Each variant's gain over every earlier variant in the report's column order.
    for variants in patterns.values():
        present = [suffix for suffix in SPECIALIZED_VARIANT_SUFFIXES if suffix in variants]
        for index, suffix in enumerate(present):
            pairs, views = variants[suffix]
            gains = {}
            for earlier in present[:index]:
                earlier_pairs, earlier_views = variants[earlier]
                gains[earlier_views[0]['id']] = variant_gain_warnings(pairs, earlier_pairs, hosts)
            if gains:
                for view in views:
                    view['variantGainWarnings'] = gains
    return output
