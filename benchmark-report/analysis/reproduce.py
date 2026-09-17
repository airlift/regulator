"""Recompute all published numeric intervals from archived normalized evidence."""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
from statistics import mean
import sys
from uncertainty import uncertainty

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from measurement_followup import analysis_entries, projected_analysis_bytes


def compared_rows(report):
    rows = {}
    for row in report['rows']:
        if row['result']['state'] != 'compared':
            continue
        identity = '|'.join(row[key] for key in ('id', 'language', 'platform', 'memoryMode'))
        if identity in rows:
            raise ValueError('published report contains duplicate comparison rows')
        rows[identity] = row['result']
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    content = args.report.read_bytes()
    report = json.loads(gzip.decompress(content) if args.report.suffix == '.gz' else content)
    followup = report['provenance']['measurementFollowup']
    expected_hash = followup['analysisInputSha256']
    input_hash = hashlib.sha256(args.inputs.read_bytes()).hexdigest()
    rows = compared_rows(report)
    entries = analysis_entries(args.inputs)
    if input_hash == expected_hash:
        selected = entries
    elif input_hash == followup.get('completeAnalysisInputSha256'):
        projected = projected_analysis_bytes(entries, rows)
        if hashlib.sha256(projected).hexdigest() != expected_hash:
            raise ValueError('public analysis projection differs from publication provenance')
        selected = {identity: entries[identity] for identity in rows}
    else:
        raise ValueError('analysis inputs differ from publication provenance')
    if (set(selected) != set(rows) or followup.get('analysisInputRows') != len(selected)
            or followup.get('analysisInputScope') not in {
                'complete-campaign-comparisons', 'published-comparisons'}):
        raise ValueError('analysis input coverage differs from publication provenance')
    checked = set()
    for entry in selected.values():
        key = entry['identity']
        if key in checked or key not in rows or entry['estimator'] != 'mean':
            raise ValueError('analysis input coverage or estimator differs')
        expected = rows[key]
        if expected['estimator'] != 'mean':
            raise ValueError('report estimator differs')
        result = uncertainty(entry['hosts'], 'mean', key)
        if result != expected['uncertainty']:
            raise ValueError('uncertainty result differs: ' + key)
        costs = {}
        for side in ('candidate', 'comparator'):
            hosts = []
            for host in entry['hosts']:
                value = host[side]
                if 'groups' in value:
                    hosts.append(mean(mean(group) for group in value['groups']))
                elif 'processMeansNs' in value:
                    hosts.append(mean(value['processMeansNs']))
                else:
                    hosts.append(value['meanNs'])
            costs[side] = mean(hosts)
            if not math.isclose(costs[side], expected[side + 'Ns'], rel_tol=1e-10):
                raise ValueError('mean operation cost differs: ' + key)
        if not math.isclose(costs['candidate'] / costs['comparator'], expected['ratio'], rel_tol=1e-10):
            raise ValueError('mean ratio differs: ' + key)
        checked.add(key)
        if len(checked) % 500 == 0:
            print('Reproduced', len(checked), 'comparisons', flush=True)
    if checked != set(rows):
        raise ValueError('missing numeric comparison inputs')
    print('Reproduced every numeric comparison:', len(checked))


if __name__ == '__main__':
    main()
