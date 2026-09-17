"""Build report data from complete verified captures, never from prior HTML."""

import argparse
from collections import defaultdict
import csv
import json
import math
from pathlib import Path
from statistics import mean
import sys

from baseline_data import LIKE_INPUT_BYTES, baseline_rows, read_rows
from language_data import LANGUAGES, MEMORY_MODES, PLATFORMS, digest, language_rows, outcome, reduce_hosts
from report_data import validate_report


ENGINE_TREE = 'fae35d9229c443e10b894ccdc105ef9c0b03c031'


def load(path):
    with Path(path).open() as source:
        return json.load(source)


def validate_reduction(directory):
    for row in read_rows(directory / 'output-checksums.tsv'):
        path = directory / row['file']
        if path.stat().st_size != int(row['size_bytes']) or digest(path) != row['sha256']:
            raise ValueError(f'baseline reduction changed: {path}')


def like_supplement(directory, manifest, *, dfa=False, engine_tree=ENGINE_TREE):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/re2-benchmark/baseline'))
    from acceptance import validate_host_results
    receipts = read_rows(directory / 'accepted-sessions.tsv')
    shards = ('like-dfa-single-use',) if dfa else ('like-compile', 'like-single-use')
    scenarios = {'ANY_ASCII', 'ANY_MULTIBYTE'} if dfa else set(LIKE_INPUT_BYTES)
    systems = ('regulator', 'trino-sql', 'trino-optimized') if dfa else ('regulator', 'trino-sql')
    expected = {(platform, shard, str(replica)) for platform in PLATFORMS
                for shard in shards for replica in (1, 2, 3)}
    if {(row['platform'], row['shard_id'], row['replica_id']) for row in receipts} != expected or len(receipts) != len(expected):
        raise ValueError('LIKE supplement is incomplete')
    if len({row['instance_id'] for row in receipts}) != len(expected):
        raise ValueError('LIKE supplement reused a host')
    grouped = defaultdict(list)
    for receipt in receipts:
        if receipt['engine_tree'] != engine_tree:
            raise ValueError('LIKE supplement changed the production engine')
        base = directory / 'jobs' / receipt['platform'] / receipt['shard_id'] / ('replica-' + receipt['replica_id']) / ('epoch-' + receipt['host_epoch'])
        sessions = list(base.glob('*/' + receipt['architecture'] + '/re2-results'))
        if len(sessions) != 1:
            raise ValueError('LIKE accepted epoch has ambiguous artifacts')
        session = sessions[0]
        if validate_host_results(manifest, session / 'session.tsv', session / 'observed-rows.tsv') != receipt:
            raise ValueError('LIKE raw artifacts differ from their accepted receipt')
        normalized = {row['row_id']: row for row in read_rows(session / 'observed-rows.tsv')}
        observed = defaultdict(dict)
        operation = 'compile' if receipt['shard_id'] == 'like-compile' else 'singleUse'
        for system in systems:
            files = sorted((session / 'routes/native-access/raw').glob(f'trino-like-{system}-process-*.json'))
            if len(files) != 5:
                raise ValueError('LIKE supplement requires all five process epochs')
            samples = defaultdict(list)
            allocations = defaultdict(list)
            for path in files:
                entries = load(path)
                if len(entries) != len(scenarios):
                    raise ValueError('incomplete LIKE process output')
                seen = set()
                for entry in entries:
                    scenario = entry['params']['scenario']
                    if scenario in seen or scenario not in scenarios:
                        raise ValueError('duplicate or unexpected LIKE scenario in process output')
                    seen.add(scenario)
                    method = {'regulator': 'candidate', 'trino-sql': 'trinoSql', 'trino-optimized': 'trinoOptimized'}[system]
                    method += 'Compile' if operation == 'compile' else 'SingleUse'
                    if not entry['benchmark'].endswith('.' + method) or entry['primaryMetric']['scoreUnit'] != 'ns/op':
                        raise ValueError('wrong LIKE lifecycle method or time unit')
                    if entry['warmupTime'] != '1 s' or entry['measurementTime'] != '1 s' or entry['warmupIterations'] != 10 or entry['measurementIterations'] != 10:
                        raise ValueError('LIKE lifecycle protocol differs from the declared full protocol')
                    raw = entry['primaryMetric']['rawData']
                    if len(raw) != 1 or len(raw[0]) != 10:
                        raise ValueError('incomplete LIKE measurement iterations')
                    samples[scenario].append(raw[0])
                    allocation = entry.get('secondaryMetrics', {}).get('gc.alloc.rate.norm')
                    if allocation is not None:
                        if allocation['scoreUnit'] != 'B/op':
                            raise ValueError('wrong LIKE allocation unit')
                        allocations[scenario].append(allocation['score'])
            if set(samples) != scenarios or any(len(groups) != 5 for groups in samples.values()):
                raise ValueError('missing LIKE scenario or process epoch')
            for scenario, groups in samples.items():
                if not allocations[scenario]:
                    raise ValueError('LIKE supplement has no allocation measurement')
                metric = outcome({'outcome': 'compared', 'samples_ns': groups,
                                  'allocation': {'score': mean(allocations[scenario])}})
                row = normalized[f"{receipt['shard_id']}/{scenario}/{system}"]
                if row['outcome'] not in {'accepted', 'precision-rejected'} or not math.isclose(float(row['score']), metric['medianNs'], rel_tol=1e-10):
                    raise ValueError('LIKE raw median disagrees with accepted observation')
                observed[scenario][system] = metric
        for scenario, pair in observed.items():
            for comparator in systems[1:]:
                grouped[receipt['platform'], scenario, operation, comparator].append({
                    'instanceId': receipt['instance_id'], 'replica': int(receipt['replica_id']),
                    'candidate': pair['regulator'], 'comparator': pair[comparator],
                })
    output = []
    for (platform, scenario, operation, comparator), hosts in sorted(grouped.items()):
        if len(hosts) != 3:
            raise ValueError('incomplete LIKE host comparison')
        size = LIKE_INPUT_BYTES[scenario] if operation == 'singleUse' else None
        result = reduce_hosts(hosts, size)
        suffix = '/optimized' if comparator == 'trino-optimized' else ''
        for mode in MEMORY_MODES:
            output.append({'id': f'like-lifecycle/{scenario}{suffix}/{operation}', 'caseId': 'trino-like/' + scenario + suffix,
                           'name': scenario.replace('_', ' ').lower(), 'operation': operation, 'model': operation,
                           'population': 'like', 'family': 'LIKE', 'language': 'like', 'platform': platform,
                           'memoryMode': mode, 'inputBytes': size, 'source': 'like-supplement', 'result': result})
    return output


def build(arguments):
    validate_reduction(arguments.baseline)
    manifests = [load(arguments.lifecycle_manifest), load(arguments.bulk_manifest)]
    data = load(arguments.language)
    engine_trees = {source['engine_tree'] for source in data['source_equivalence']['archives'].values()}
    if engine_trees != {ENGINE_TREE}:
        raise ValueError('language captures do not share the frozen engine')
    rows = language_rows(data, manifests)
    rows.extend(baseline_rows(arguments.baseline, manifests[0]))
    rows.extend(like_supplement(arguments.like_capture, arguments.like_manifest))
    source = data['source_equivalence']['archives']['original']
    report = {
        'schemaVersion': 2, 'sources': {'currentLabel': 'Final-v1 capture', 'currentCandidate': source['candidate_commit'],
                                      'engineTree': ENGINE_TREE, 'jdk': 'Temurin 25.0.4+7'},
        'languages': LANGUAGES, 'platforms': PLATFORMS, 'memoryModes': MEMORY_MODES, 'rows': rows,
        'methodology': [
            'Times are medians of all measured samples within each host, then medians across independent hosts. Ratios and deltas are calculated within each host before aggregation.',
            'Every shared-language case has three independent hosts. Existing baseline diagnostics have three primary hosts and, where required, one additional confirmation host. No noisy host is removed.',
            'Observed host ranges are not confidence intervals. The dagger preserves raw-sample, process-level, and cross-host timing uncertainty. Blue presentation bands are not statistical error bounds.',
            'Shared-language JVM measurements use five independent forks with ten one-second warmup and measurement intervals; native RE2 has five repetitions. Representation conversion is outside the JDK timing.',
            'Regulator LIKE does not use native-memory DFA tables. Identical pure-Java LIKE measurements appear in both memory views. Single use measures construction and first match against Trino SQL LIKE with optimization disabled.',
            'Compilation delta is elapsed time per operation. Execution delta per byte uses the frozen input size or average input size of the rotation, not the number of bytes the engine happened to inspect.',
            'Public language matrices cover every CPU and memory mode. Additional internal-engine diagnostics include native-only routes; their row counts can differ between memory views. Internal compiler phases, allocation and retained-memory results remain in the separate complete baseline evidence.',
            'Compatible timeout cases require two attempts at the declared phase limit. Incompatibility is a separate semantic classification. Neither receives a numerical comparison.',
        ],
        'provenance': {
            'engineTree': ENGINE_TREE, 'candidate': source['candidate_commit'],
            'originalArchiveSha256': source['archive_sha256'], 'languageExportSha256': digest(arguments.language),
            'languageManifestSha256': [digest(arguments.lifecycle_manifest), digest(arguments.bulk_manifest)],
            'baselineReductionChecksumsSha256': digest(arguments.baseline / 'output-checksums.tsv'),
            'likeReceiptsSha256': digest(arguments.like_capture / 'accepted-sessions.tsv'),
            'likeManifestSha256': digest(arguments.like_manifest),
            'comparators': read_rows(Path(__file__).resolve().parents[2] / 'tools/re2-benchmark/baseline/comparators.tsv'),
            'languageObservationCount': len(data['observations']), 'languageLogicalExports': data['logical_jobs'],
            'preservedOverlappingExports': len(data['extra_recovery_exports_not_selected']),
        },
    }
    validate_report(report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('language', 'baseline', 'lifecycle-manifest', 'bulk-manifest', 'like-capture', 'like-manifest', 'output'):
        parser.add_argument('--' + name, type=Path, required=name == 'output')
    parser.add_argument('--released-campaign', type=Path, help='complete release campaign paths and pinned artifact version')
    arguments = parser.parse_args()
    if arguments.released_campaign:
        from release_capture import build as build_release
        report = build_release(arguments.released_campaign)
    else:
        if any(getattr(arguments, name) is None for name in ('language', 'baseline', 'lifecycle_manifest', 'bulk_manifest', 'like_capture', 'like_manifest')):
            parser.error('legacy capture requires all language, baseline, manifest, and LIKE inputs')
        report = build(arguments)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(report, allow_nan=False, ensure_ascii=False, separators=(',', ':')) + '\n')
    print(f"Built {len(report['rows'])} report rows from verified captures: {arguments.output}")


if __name__ == '__main__':
    main()
