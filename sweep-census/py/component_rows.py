"""Fold a routes table into component rows: every (component, row identity) pair, marked ran or admitted.
Usage: python3 -I component_rows.py <routes.tsv> <tag-2.1-root> <platform> <out.tsv>
A row identity is (language, caseId, operation) over the report rows of <platform>, both memory modes; its
evidence class is its first row's, its tiers and ratios are those rows'. A component ran on an identity when
it is in either row's route, and was admitted when it is only in either row's admitted (built) set.
Both platforms' rows carry the same census (the host's), so <platform> picks only whose tiers and ratios
are listed: run.sh passes r9g on aarch64 and r8i on x86_64."""
import sys, csv, json, gzip
from pathlib import Path

def case_ids(tag):
    report = json.load(open(tag / 'benchmark-report/data/manifest.json'))
    return {(r['id'], r['language'], r['platform'], r['memoryMode']): r['caseId']
            for r in json.load(gzip.open(tag / 'benchmark-report/data' / report['current']))['rows']}

def fold(routes, cases, platform):
    ident = {}
    for o in routes:
        if o['platform'] != platform:
            continue
        k = (o['language'], cases[(o['row_id'], o['language'], o['platform'], o['memory_mode'])], o['operation'])
        e = ident.setdefault(k, {'evidence': o['evidence'], 'ran': set(), 'built': set(), 'tiers': set(), 'ratios': []})
        e['ran'] |= set(o['route'].split('+')) - {''}
        e['built'] |= set(o['built'].split('+')) - {''}
        e['tiers'].add(o['tier'])
        if o['ratio']:
            e['ratios'].append(float(o['ratio']))
    rows = []
    for (lang, case, op), e in ident.items():
        for c in e['ran'] | e['built']:
            rows.append((c, lang, case, op, e['evidence'], 'ran' if c in e['ran'] else 'admitted', ','.join(sorted(e['tiers'])),
                         ','.join('%.3f' % r for r in e['ratios'])))
    return sorted(rows), len(ident)

def write(path, rows):
    with open(path, 'w') as f:
        f.write('component\tlanguage\tcase\toperation\tevidence\tpresence\ttiers\tratios\n')
        for r in rows:
            f.write('\t'.join(r) + '\n')

if __name__ == '__main__':
    routes = list(csv.DictReader(open(sys.argv[1]), delimiter='\t', quoting=csv.QUOTE_NONE))
    rows, n = fold(routes, case_ids(Path(sys.argv[2])), sys.argv[3])
    write(sys.argv[4], rows)
    print(len(rows), 'component rows over', n, 'identities')
