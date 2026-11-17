"""Map every published 2.1 report row to one untimed census unit.
Usage: python3 -I build_units.py <tag-2.1-root> <inputs-dir>
Reads the 2.1 report data under <tag-2.1-root>/benchmark-report/data and the workloads in <inputs-dir>/bulk
and <inputs-dir>/lifecycle. Writes <inputs-dir>/units.tsv (unit_id, mode, runner, args...) and
<inputs-dir>/rowmap.tsv (row key -> unit_id)."""
import sys, json, gzip
from pathlib import Path
TAG, INPUTS = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
report = json.load(open(TAG / 'benchmark-report/data/manifest.json'))
rows = json.load(gzip.open(TAG / 'benchmark-report/data' / report['current']))['rows']
LIFE = INPUTS / 'lifecycle'
life = json.load(open(LIFE / 'manifest.json'))
life_cases = {c['id']: c for c in life['cases']}
bulk = {c['id']: c for c in json.load(open(INPUTS / 'bulk/cases.json'))}
units, rowmap, missing = {}, [], []
SPEC = {'re2': 'BenchmarkSpecializedRe2', 'java': 'BenchmarkSpecializedJavaRegexp', 'rust': 'BenchmarkSpecializedRustRegexp', 'trino': 'BenchmarkSpecializedTrinoRegexp'}

def unit(uid, mode, runner, *args):
    key = uid + '|' + mode
    units[key] = (key, mode, runner) + tuple(str(a) for a in args)
    return key

for r in rows:
    mode = r['memoryMode']
    rid, lang, op = r['id'], r['language'], r['operation']
    parts = rid.split('/')
    status = (r.get('mapping') or {}).get('status')
    key = None
    if parts[0] == 'language-lifecycle':
        case = life_cases['/'.join(parts[1:3])]
        m = case['mappings'][lang]
        wf = LIFE / m['input_file']
        key = unit(f'life:{lang}:{case["id"]}:{op}', mode, 'jmh', 'io.airlift.regulator.BenchmarkLanguageComparison', op,
                   f'engine={lang},workloadFile={wf}', 1 if op == 'compile' else len(case['sources']), 8 if op == 'compile' else 64 * len(case['sources']))
    elif parts[0] == 'language-bulk':
        cid = r['caseId']
        c = bulk[cid]
        m = c['languages'][lang]
        key = unit(f'bulk:{lang}:{cid}:{op}', mode, 'jmh', 'io.airlift.regulator.BenchmarkLanguageBulk', op,
                   f'engine={lang},workloadFile={Path(m["file"]).resolve()},expectedResult={c["expected"]}', 1, 2 if op == 'execute' else 8)
    elif parts[0] == 'baseline':
        suite = parts[1]
        if suite == 'trino-operations':
            wl = parts[3]
            key = unit(f'base:trino:{wl}:{op}', mode, 'jmh', 'io.airlift.regulator.BenchmarkEverydayTrinoRegexp', op, f'workload={wl}', 16, 1024)
        elif suite.endswith('-specialized-patterns'):
            l = suite.split('-')[0]
            wl = parts[2]
            variant = parts[4] if len(parts) > 4 else ''
            method = op + {'': '', 'generated': 'Generated', 'regular-engine': 'RegularEngine'}[variant]
            key = unit(f'base:{l}-specialized:{wl}:{method}', mode, 'jmh', f'io.airlift.regulator.{SPEC[l]}', method, f'workload={wl}', 16, 1024)
        elif suite in ('like-compile', 'like-single-use', 'like-dfa-single-use', 'trino-like'):
            scen = parts[2]
            likeop = {'compile': 'compile', 'singleUse': 'singleUse', 'matches': 'matches'}[op]
            key = unit(f'like:{scen}:{likeop}', mode, 'like', 'scenario', scen, likeop, 64)
        elif suite == 'trino-like-specialized':
            wl = parts[2]
            likeop = 'generated' if len(parts) > 3 and parts[3] == 'generated' else 'matches'
            key = unit(f'like-tpch:{wl}:{likeop}', mode, 'like', 'tpch', wl, likeop, 2)
    if key is None:
        missing.append(rid)
        continue
    rowmap.append((rid, lang, op, r['platform'], mode, key, r['result']['state'], status or ''))

with open(INPUTS / 'units.tsv', 'w') as f:
    for u in units.values():
        f.write('\t'.join(u) + '\n')
with open(INPUTS / 'rowmap.tsv', 'w') as f:
    f.write('row_id\tlanguage\toperation\tplatform\tmemory_mode\tunit\tstate\tmapping\n')
    for x in rowmap:
        f.write('\t'.join(x) + '\n')
print(len(rows), 'rows;', len(rowmap), 'mapped;', len(units), 'units;', len(missing), 'missing', missing[:5])
if missing:
    sys.exit('unmapped report rows')
