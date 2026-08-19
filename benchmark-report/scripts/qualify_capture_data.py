"""Apply an independently collected precision cohort and retain its predecessor."""

import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import sys
import tarfile
import hashlib

from baseline_data import read_rows
from language_data import digest, outcome, reduce_hosts
from report_data import load_json, validate_report

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/re2-benchmark/baseline'))
from acceptance import validate_confirmation_host_results, validate_host_results
from shard_results import jmh_results

SHARDS = ('trino-operations', 'trino-final-line')
CANDIDATES = {'regulator-native-access', 'regulator-object-row'}
COLLECTOR_PATHS = {
    'tools/re2-benchmark/baseline/host_session.py',
    'tools/re2-benchmark/baseline/run-host-session.sh',
    'tools/re2-benchmark/baseline/run-shard.sh',
    'tools/re2-benchmark/baseline/tests/test_selected_route.py',
}


def verify_collector_source(proof, base_archive, collector_archive, base_candidate):
    if proof['baseCandidate'] != base_candidate or digest(base_archive) != proof['baseArchiveSha256'] or digest(collector_archive) != proof['archiveSha256']:
        raise ValueError('collector source archive identity mismatch')
    inventories = []
    for archive, commit in ((base_archive, base_candidate), (collector_archive, proof['collectorCandidate'])):
        with tarfile.open(archive) as source:
            if source.pax_headers.get('comment') != commit:
                raise ValueError('collector source archive commit mismatch')
            entries = {}
            for member in source:
                if member.isfile():
                    entries[member.name] = (member.mode, hashlib.sha256(source.extractfile(member).read()).hexdigest())
                elif not member.isdir():
                    entries[member.name] = (member.mode, member.type, member.linkname)
            inventories.append(entries)
    before, after = inventories
    changed = {name for name in before.keys() | after.keys() if before.get(name) != after.get(name)}
    if changed != COLLECTOR_PATHS or set(proof['changedCollectorFiles']) != changed:
        raise ValueError('collector archive changed files outside the declared recovery collector')
    for name in changed:
        if after[name][1] != proof['changedCollectorFiles'][name]:
            raise ValueError('collector file checksum mismatch')
    return proof


def measurements(session, shard, expected):
    output = {}
    systems = {row['system'] for row in expected}
    if 'joni' not in systems or not systems & CANDIDATES or systems - (CANDIDATES | {'joni'}):
        raise ValueError('precision-cohort requires a same-host Joni pair')
    joni_route = 'native-access' if 'regulator-native-access' in systems else 'object-row'
    for system, route in (('joni', joni_route), ('regulator-native-access', 'native-access'), ('regulator-object-row', 'object-row')):
        if system not in systems:
            continue
        suffix = '.json' if system == 'joni' else '-*.json'
        paths = sorted((session / 'routes' / route / 'raw').glob(shard + '-' + system + suffix))
        if not paths:
            raise ValueError('missing precision-cohort raw results')
        # The merger retains the first process's metadata. Check every file
        # before merging so later processes cannot hide a changed protocol.
        for path in paths:
            for entry in load_json(path):
                if system == 'joni' and (entry['warmupTime'] != '200 ms' or entry['measurementTime'] != '200 ms'):
                    raise ValueError('precision-cohort Joni interval is not 200 ms')
                if system == 'joni' and entry['forks'] != 1:
                    raise ValueError('precision-cohort Joni fork count changed')
                if system != 'joni':
                    # These are the two frozen full-protocol exceptions, not
                    # a duration inferred from whichever files were collected.
                    full = (system == 'regulator-native-access' and shard == 'trino-final-line' and
                            entry['benchmark'].endswith('.countRegulator') and
                            entry.get('params', {}).get('workload') == 'captureLateMatch' and
                            entry['params'].get('sourceLength') in {'1024', '32768'})
                    interval, forks = ('1 s', 5) if full else ('50 ms', 0)
                    if entry['warmupTime'] != interval or entry['measurementTime'] != interval or entry['forks'] != forks:
                        raise ValueError('precision-cohort Regulator protocol changed')
                if entry['warmupIterations'] != 10 or entry['measurementIterations'] != 10:
                    raise ValueError('precision-cohort iteration count changed')
                metric = entry['primaryMetric']
                if metric['scoreUnit'] != 'ns/op' or not metric.get('rawData') or any(len(group) != 10 for group in metric['rawData']):
                    raise ValueError('invalid precision-cohort samples or unit')
        entries = jmh_results(paths, merge_secondary_metrics=False,
                              require_one_process_group_per_file=False,
                              allow_disjoint_process_sets=True,
                              expected_process_groups=1 if system == 'joni' else 5)
        observed = {}
        for (benchmark, parameters), entry in entries.items():
            metric = entry['primaryMetric']
            if shard == 'trino-final-line':
                benchmark = benchmark.rsplit('.', 1)[-1].removesuffix('Joni').removesuffix('Regulator')
            key = (benchmark, parameters)
            if key in observed:
                raise ValueError('duplicate mapped precision-cohort result')
            observed[key] = outcome({'outcome': 'compared', 'samples_ns': metric['rawData']})
        selected = [row for row in expected if row['system'] == system]
        if len(observed) != len(selected):
            raise ValueError('precision-cohort raw coverage differs from manifest')
        for row in selected:
            output[row['row_id']] = observed[row['benchmark'], row['parameters']]
    return output


def cohort_receipts(pilots, confirmation, platforms):
    """Require the full declared cohort, including a separately recorded retry."""
    collected = []
    instances = set()
    if isinstance(confirmation, Path):
        confirmation = [confirmation]
    for directories, replicas in ((pilots, {4}), (confirmation, {1, 2})):
        identities = set()
        wanted = {(platform, shard, replica, system) for platform in platforms for shard in SHARDS for replica in replicas for system in CANDIDATES}
        for directory in directories:
            for receipt in read_rows(directory / 'accepted-sessions.tsv'):
                identity = (receipt['platform'], receipt['shard_id'], int(receipt['replica_id']))
                systems = set(receipt['systems'].split(','))
                if 'joni' not in systems or not systems & CANDIDATES or systems - (CANDIDATES | {'joni'}):
                    raise ValueError('precision-cohort requires a same-host Joni pair')
                slots = {(*identity, system) for system in systems & CANDIDATES}
                if identities & slots:
                    raise ValueError('duplicate precision-cohort identity')
                if receipt['instance_id'] in instances:
                    raise ValueError('precision cohort reused a host')
                identities.update(slots)
                instances.add(receipt['instance_id'])
                collected.append((directory, receipt))
        if identities != wanted:
            raise ValueError('precision cohort is incomplete or contains unexpected identities')
    return collected


def qualify(report, pilots, confirmation, manifest, collector_proof=None):
    validate_report(report)
    if 'precisionCohort' in report['provenance']:
        raise ValueError('report already includes a precision cohort')
    expected = defaultdict(list)
    for row in read_rows(manifest):
        if row['shard_id'] in SHARDS:
            expected[row['shard_id']].append(row)
    instances = set()
    groups = defaultdict(list)
    receipts_digests = {}
    receipts = cohort_receipts(pilots, confirmation, report['platforms'])
    if isinstance(confirmation, Path):
        confirmation = [confirmation]
    for directory in [*pilots, *confirmation]:
        receipts_digests[str(directory)] = digest(directory / 'accepted-sessions.tsv')
    for directory, receipt in receipts:
        instances.add(receipt['instance_id'])
        equivalent_collector = (collector_proof is not None and
                                receipt['candidate_commit'] == collector_proof['collectorCandidate'] and
                                receipt['candidate_archive_sha256'] == collector_proof['archiveSha256'] and
                                collector_proof['engineTree'] == report['sources']['engineTree'])
        if receipt['engine_tree'] != report['sources']['engineTree'] or (receipt['candidate_commit'] != report['sources']['currentCandidate'] and not equivalent_collector):
            raise ValueError('precision cohort changed the candidate source')
        if collector_proof is not None and not equivalent_collector and receipt['candidate_archive_sha256'] != collector_proof['baseArchiveSha256']:
            raise ValueError('precision cohort changed the original source archive')
        base = directory / 'jobs' / receipt['platform'] / receipt['shard_id'] / ('replica-' + receipt['replica_id']) / ('epoch-' + receipt['host_epoch'])
        sessions = list(base.glob('*/' + receipt['architecture'] + '/re2-results'))
        if len(sessions) != 1:
            raise ValueError('precision cohort has ambiguous session artifacts')
        session = sessions[0]
        validator = validate_confirmation_host_results if receipt['replica_id'] == '4' else validate_host_results
        if validator(manifest, session / 'session.tsv', session / 'observed-rows.tsv') != receipt:
            raise ValueError('precision-cohort artifacts differ from the accepted receipt')
        systems = set(receipt['systems'].split(','))
        metrics = measurements(session, receipt['shard_id'], [row for row in expected[receipt['shard_id']] if row['system'] in systems])
        normalized = {row['row_id']: row for row in read_rows(session / 'observed-rows.tsv')}
        for identifier, metric in metrics.items():
            if not math.isclose(metric['medianNs'], float(normalized[identifier]['score']), rel_tol=1e-10):
                raise ValueError('precision-cohort raw median disagrees with accepted observation')
            if identifier.endswith('/joni'):
                continue
            key = (receipt['platform'], identifier)
            groups[key].append({'replica': int(receipt['replica_id']), 'instanceId': receipt['instance_id'],
                                'candidate': metric, 'comparator': metrics[identifier.rsplit('/', 1)[0] + '/joni']})
    replacements = apply_comparisons(report['rows'], groups)
    report['sources']['currentLabel'] = 'Final-v1 qualified capture'
    report['provenance']['precisionCohort'] = {
        'sourceManifestSha256': digest(manifest), 'acceptedReceiptsSha256': receipts_digests,
        'hostCount': len(instances), 'comparisons': replacements, 'joniInterval': '200 ms',
        'regulatorProtocol': 'Five independent JVM epochs, ten 50 ms warmup and measurement intervals; the declared native final-line count exceptions retain five forks with ten one-second warmup and measurement intervals.',
        'selection': 'All pilot hosts and two additional paired measurements per CPU, suite and memory mode. No completed host is discarded.',
        'previousCohort': 'Original 50 ms Joni results are preserved in each affected row and in the complete baseline archive.',
    }
    if collector_proof is not None:
        report['provenance']['precisionCohort']['collectorSourceEquivalence'] = collector_proof
    report['methodology'] = [note.replace('Existing baseline diagnostics', 'Unreplaced baseline diagnostics') for note in report['methodology']]
    report['methodology'].append('Trino public-operation and final-line comparisons use a separate three-host precision cohort. Each host runs one Joni fork with ten 200 ms warmup and measurement intervals. Regulator retains five independent JVM epochs with ten 50 ms warmup and measurement intervals, plus the declared native final-line count exceptions using five forks and one-second intervals. The original shorter-interval cohort is available in each affected row and is not blended into the new median.')
    if collector_proof is not None:
        report['methodology'].append('One C8g final-line replica uses separate native-memory and pure-Java hosts after a combined collection hit its watchdog. Each candidate remains paired with Joni on its own host; the two memory modes do not share that replica\'s Joni measurement.')
    validate_report(report)
    return report


def apply_comparisons(rows, groups):
    seen = set()
    replacements = []
    for row in rows:
        if row['source'] != 'baseline' or not any(row['id'].startswith('baseline/' + shard + '/') for shard in SHARDS):
            continue
        system = 'regulator-native-access' if row['memoryMode'] == 'native' else 'regulator-object-row'
        key = (row['platform'], row['id'].removeprefix('baseline/') + '/' + system)
        hosts = groups.get(key, [])
        if len(hosts) != 3 or {host['replica'] for host in hosts} != {1, 2, 4}:
            raise ValueError('precision comparison needs all three independent hosts')
        if key in seen:
            raise ValueError('duplicate report comparison for precision cohort')
        seen.add(key)
        if 'originalResult' in row:
            raise ValueError('precision comparison already has an original result')
        replacements.append((row, reduce_hosts(hosts, row['inputBytes'])))
    if seen != set(groups) or len(replacements) != 2160:
        raise ValueError('precision cohort does not map exactly to the published comparisons')
    for row, result in replacements:
        row['originalResult'] = row['result']
        row['result'] = result
        row['source'] = 'baseline-qualified'
    return len(replacements)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('original', 'manifest', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--confirmation', type=Path, action='append', required=True)
    parser.add_argument('--collector-protocol', type=Path)
    parser.add_argument('--base-archive', type=Path)
    parser.add_argument('--collector-archive', type=Path)
    parser.add_argument('--pilot', type=Path, action='append', required=True,
                        help='Pilot receipt directory; repeat to include a separately recorded infrastructure retry')
    arguments = parser.parse_args()
    report = load_json(arguments.original)
    collector_proof = None
    if arguments.collector_protocol:
        if not arguments.base_archive or not arguments.collector_archive:
            parser.error('collector protocol requires both source archives')
        collector_proof = verify_collector_source(load_json(arguments.collector_protocol)['sourceEquivalence'],
            arguments.base_archive, arguments.collector_archive, report['sources']['currentCandidate'])
    report = qualify(report, arguments.pilot, arguments.confirmation, arguments.manifest, collector_proof)
    report['provenance']['originalReportSha256'] = digest(arguments.original)
    arguments.output.write_text(json.dumps(report, allow_nan=False, ensure_ascii=False, separators=(',', ':')) + '\n')
    print('Built precision-qualified report:', arguments.output)


if __name__ == '__main__':
    main()
