"""Exact original-row selections for the retained baseline follow-up."""
import csv
import hashlib
from pathlib import Path


def timing_protocol(shard, protocol):
    overrides = protocol.get('shard_overrides', {})
    allowed = {'traditional-extra-alt-engines', 'traditional-extra-big-fixed', 'traditional-extra-fanout'}
    if not isinstance(overrides, dict) or set(overrides) - allowed:
        raise ValueError('unknown retained protocol override')
    for name, values in overrides.items():
        expected = ({'operations_per_partition': 1, 'warmup_iterations': 300,
                     'measurement_iterations': 300, 'native_measurement_seconds': 20}
                    if name == 'traditional-extra-alt-engines' else
                    {'measurement_iterations': 60, 'native_measurement_seconds': 20})
        if values != expected or any(type(v) is not int for v in values.values()):
            raise ValueError('unrecognized retained protocol override')
    if type(protocol.get('native_measurement_seconds', 1)) is not int or protocol.get('native_measurement_seconds', 1) != 1:
        raise ValueError('invalid default native timing duration')
    return {**protocol, **overrides.get(shard, {})}


def partition_plan(base, shard, partition, protocol):
    if (protocol.get('family') != 'retained-baseline-v1'
            or type(protocol.get('schema_version')) is not int or protocol.get('schema_version') != 1
            or type(protocol.get('qualified_by_pilot')) is not bool
            or type(partition) is not int or partition < 0
            or type(protocol.get('forks')) is not int or protocol.get('forks') != 5
            or type(protocol.get('warmup_iterations')) is not int or protocol.get('warmup_iterations') != 60
            or type(protocol.get('measurement_iterations')) is not int or protocol.get('measurement_iterations') != 20
            or type(protocol.get('iteration_seconds')) is not int or protocol.get('iteration_seconds') != 1
            or protocol.get('gc') != 'G1' or protocol.get('cpu_allowance') != '0,1' or protocol.get('heap') != '8g'):
        raise ValueError('unrecognized retained baseline protocol')
    path = Path(base) / 'rows.tsv'
    if hashlib.sha256(path.read_bytes()).hexdigest() != protocol['original_row_manifest_sha256']:
        raise ValueError('original row manifest changed')
    selected = protocol['selected_logical_rows']
    if not selected or selected != sorted(set(selected)):
        raise ValueError('retained selection must be nonempty, sorted and unique')
    groups = {}
    with path.open() as source:
        for row in csv.DictReader(source, delimiter='\t'):
            identity = row['row_id'].rsplit('/', 1)[0]
            if identity in selected:
                systems = groups.setdefault(identity, {})
                if row['system'] in systems:
                    raise ValueError('duplicate original baseline system')
                systems[row['system']] = row
    if set(groups) != set(selected):
        raise ValueError('unknown retained baseline identity')
    size = timing_protocol(shard, protocol)['operations_per_partition']
    if type(size) is not int or not 1 <= size <= 4:
        raise ValueError('invalid retained partition size')
    matching = sorted(identity for identity, systems in groups.items()
                      if next(iter(systems.values()))['shard_id'] == shard)
    identities = matching[partition * size:(partition + 1) * size]
    if not identities:
        raise ValueError('retained partition is outside frozen selection')
    selections = []
    native = {'Search_Easy0_CachedDFA/262144/threads:1', 'Search_Easy2_CachedDFA/262144/threads:1'}
    for identity in identities:
        systems = groups[identity]
        like = next(iter(systems.values()))['suite'] == 'trino-like'
        if like:
            if not {'regulator', 'trino-sql'} <= set(systems) or set(systems) - {'regulator', 'trino-sql', 'trino-optimized'}:
                raise ValueError('incomplete retained LIKE comparison')
            suffix = {'compile': 'Compile', 'singleUse': 'SingleUse', 'matches': ''}[systems['regulator']['benchmark']]
            requested = [('regulator', 'object-row'), ('regulator', 'native-access')]
            requested += [(system, 'native-access') for system in ('trino-sql', 'trino-optimized') if system in systems]
        else:
            if not {'native-re2-before', 'native-re2-after'} <= set(systems):
                raise ValueError('missing retained native brackets')
            candidates = set(systems) - {'native-re2-before', 'native-re2-after'}
            if not candidates or candidates - {'regulator-object-row', 'regulator-native-access'}:
                raise ValueError('invalid retained candidate systems')
            requested = [(system, 'object-row' if system == 'regulator-object-row' else 'native-access') for system in sorted(candidates)]
            native.update(row['comparator'] for row in systems.values())
        for system, route in requested:
            row = systems[system]
            benchmark = row['benchmark']
            if like:
                prefix = {'regulator': 'candidate', 'trino-sql': 'trinoSql', 'trino-optimized': 'trinoOptimized'}[system]
                benchmark = 'io.airlift.regulator.benchmark.BenchmarkTrinoLike.' + prefix + suffix
            parameters = {} if row['parameters'] == '-' else dict(value.split('=', 1) for value in row['parameters'].split(';'))
            selections.append({'original_row': row, 'benchmark': benchmark, 'parameters': parameters, 'route': route, 'gc': 'g1'})
    return {'kind': 'replacement-measurement', 'schema_version': 1, 'shard': shard, 'partition': partition,
            'protocol': protocol, 'selections': selections, 'native_names': sorted(native)}
