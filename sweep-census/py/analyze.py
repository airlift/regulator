"""Turn raw census output into one routes row per published 2.1 report row (the ROUTES.tsv schema).
Usage: python3 -I analyze.py <census-dir> <tag-2.1-root> <rowmap.tsv> <routes-out.tsv> <beside-sidecar-out.tsv>
A component ran on a row when the steady-state (op) phase entered one of its run anchors (anchors.py). It was
admitted when the static dump of the compiled pattern holds its class or field (STATIC below), with these
rules from method entries or the dump: S8 where the DFA built usable fixed-distance candidates in any phase,
Q4B (split from Q2) where the dump says longWindowLiteral=other, K1 skipping the unsupported() placeholder,
and L5X where the window literal search stepped aside for a 64-lane start literal filter."""
import os, sys, json, gzip, re, csv, collections as C
from pathlib import Path
CENSUS, TAG, ROWMAP, OUT, SIDE = map(Path, sys.argv[1:6])
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anchors import A
report = json.load(open(TAG / 'benchmark-report/data/manifest.json'))
rows = json.load(gzip.open(TAG / 'benchmark-report/data' / report['current']))['rows']
DFA_ROUTES = ['byte-scan-fallback', 'small-byte-set-scan', 'contiguous-range-scan', 'mixed-byte-set-scan', 'start-literal-filter-scan',
              'start-class-filter-scan', 'self-loop-exit-byte-scan', 'count-self-loop-scan', 'count-self-loop-match-hand-off',
              'count-self-loop-no-match-pointer-hand-off', 'count-self-loop-rearm', 'self-loop-stay-range-scan', 'matching-self-loop-scan',
              'matching-self-loop-exit-byte-scan', 'reverse-self-loop-exit-byte-scan', 'fixed-distance-short-skip-fallback', 'prefix-run-scan',
              'absolute-pointer-backward-search']
# The engine-level route, in the order a search reaches them; the census string lists those that ran.
ORDER = ['G1', 'G2', 'P1', 'P2', 'P4', 'R4', 'L10', 'L1', 'R2', 'L2', 'L3', 'L6', 'L6M', 'L6O', 'L7', 'L8', 'L9', 'A1', 'A2', 'A2S', 'F1', 'F2', 'F2B', 'F3', 'F4', 'F5', 'F6', 'F7',
         'K1', 'K1R', 'K1V', 'K2', 'K3', 'K4', 'K8', 'Q1', 'Q2', 'Q3', 'Q4', 'Q4B', 'L4', 'L4G', 'L4F', 'L4S', 'L5', 'L5X', 'S6', 'S1', 'S2', 'S3', 'S4', 'S5', 'E6', 'E6G', 'S7', 'S8', 'S9', 'S10', 'S11',
         'E1', 'E2', 'E3', 'E3F', 'E4', 'E5', 'E7', 'E8', 'E9', 'E10', 'E11', 'E12', 'K5', 'K6', 'K7', 'T1', 'E13', 'R3', 'R5', 'Q5', 'V1', 'V2', 'V3']

# Compile-time admission, read from the static dump of each compiled pattern (field values by class name).
STATIC = {'P1': r'ScanPlan[;,}=]|scanPlan=ScanPlan', 'G1': r'GENERATED<(?!CompiledTrinoLike)', 'G2': r'GENERATED<CompiledTrinoLike', 'L4': r'InnerLiteralSearch',
          'L5': r'WindowLiteralSearch', 'L6': r'DotStarLiteralSpanMatcher', 'L6M': r'DotStarLiteralSpanMatcher\$MultipleStops', 'L7': r'OrderedLiteralMatcher',
          'L8': r'LiteralNeighborMatcher', 'L9': r'loweredBooleanProgram=', 'A1': r'LiteralAlternationSpanMatcher', 'A2': r'LiteralSetSpanMatcher',
          'F1': r'FixedWidthByteSpanMatcher', 'F5': r'DisjointSuffixRepeatMatcher', 'F6': r'SingleByteMatcher', 'F7': r'singleByteRepeat=',
          'K1': r'=BoundedCharacterClassCounter', 'K2': r'retainedClassCountDfa=true', 'K3': r'WordRunMatcher', 'K4': r'WholeInputCapturePlan',
          'E11': r'PositionNfa', 'E12': r'TaggedAlternationProgram', 'Q1': r'requiredBytes=RequiredBytes',
          'S4': r'foldedStartRun=', 'L2': r'LiteralSearchKernel', 'K6': r'LiteralGapMatcher', 'K7': r'TrinoLikeWildcardMatcher'}
STATIC = {k: re.compile(v) for k, v in STATIC.items()}
RUN = {k: re.compile(v[0]) for k, v in A.items()}
BUILD = {k: re.compile(v[1] or v[0]) for k, v in A.items()}

def section(r):
    if r['operation'] == 'compile' or r['population'] == 'compilation':
        return 'diagnostic-only'
    cid = r['caseId']
    if r['language'] == 'like' and cid.endswith('/optimized'):
        return 'like' if r['id'].startswith('baseline/like-dfa') else 'diagnostic-only'
    if cid in ('captures/contiguous-letters', 'grep/every-line', 'wild/dot-star-capture/rust-src-tools', 'reported/i787-keywords/opt-ascii'):
        return 'diagnostic-only'
    if cid.startswith('unicode/codepoints/') or cid.startswith('unicode/overlapping-words/'):
        return 'synthetic-stress'
    if cid == 'reported/i787-keywords/ascii':
        return 'bulk-text'
    if cid in ('imported/leipzig/certain-long-strings-ending-x', 'imported/sherlock/repeated-class-negation'):
        return 'diagnostics-and-stress'
    return r['population']
EVIDENCE = {'ordinary-scalar': 'everyday', 'trino-operations': 'everyday', 'bulk-text': 'everyday', 'like': 'everyday',
            'specialized-patterns': 'synthetic', 'synthetic-stress': 'synthetic', 'diagnostics-and-stress': 'constructed', 'diagnostic-only': 'diagnostic'}

def tier(r):
    res = r['result']
    if res['state'] != 'compared':
        return 'timeout' if res['state'] == 'did-not-finish' else 'na'
    if not res.get('uncertainty') and res.get('warnings'):
        return 'na'
    sec = section(r)
    basis = 'byte' if sec in ('bulk-text', 'literal-probe', 'synthetic-stress', 'diagnostics-and-stress') and res.get('deltaNsPerByte') is not None else 'call'
    gap = res['deltaNsPerByte'] if basis == 'byte' else res['deltaNs']
    tie_gap, clear_gap = {'byte': .1, 'call': 2}[basis], {'byte': .3, 'call': 5}[basis]
    ratio = res['ratio']
    iv = (res.get('uncertainty') or {}).get('ratioInterval')
    if (iv and iv[0] <= 1 <= iv[1]) or (res.get('minimumRatio') is not None and res['minimumRatio'] <= 1 <= res['maximumRatio']) or .95 <= ratio <= 1.05 or (gap is not None and abs(gap) < tie_gap):
        return 'tie'
    clear = (gap is None or abs(gap) >= clear_gap) and (ratio <= .8 or ratio >= 1.25)
    return ('clear' if clear else 'modest') + ('-win' if ratio < 1 else '-loss')

def fname(key):
    return key.replace('/', '~').replace('|', '@').replace(':', '_')

cache = {}
def load(key):
    if key in cache:
        return cache[key]
    p = CENSUS / (fname(key) + '.txt')
    d = {'op': C.Counter(), 'first': C.Counter(), 'setup': C.Counter(), 'routes_op': C.Counter(), 'routes_first': C.Counter(), 'routes_setup': C.Counter(), 'static': [], 'status': 'ok', 'result': ''}
    if not p.exists():
        d['status'] = 'missing'
    else:
        for line in p.read_text(errors='replace').splitlines():
            f = line.split('\t')
            if f[0] == 'M':
                d[f[1]][f[2].split('(')[0]] += int(f[3])
            elif f[0] == 'R':
                d['routes_' + f[1]][DFA_ROUTES[int(f[2])]] += int(f[3])
            elif f[0] == 'S':
                d['static'].append((f[1], f[2]))
            elif f[0] == 'P':
                if f[1] == 'result':
                    d['result'] = f[2]
                elif f[1] in ('exit', 'timeout'):
                    d['status'] = f[1] + ':' + f[2]
                elif f[1] == 'setupFailed':
                    d['setup_failed'] = f[2][:160]
    ran, built = [], []
    for k in ORDER:
        if k not in RUN:
            continue
        if any(RUN[k].search(m) for m in d['op']):
            ran.append(k)
    d['ran_first'] = [k for k in ORDER if k in RUN and any(RUN[k].search(m) for m in d['first'])]
    blob = ' '.join(st for _, st in d['static'])
    for k in ORDER:
        if k in STATIC and STATIC[k].search(blob):
            built.append(k)
    # Q4B: the long-window search of the pattern's required bytes is for a literal beside its routes.
    beside = 'longWindowLiteral=other' in blob
    if beside:
        for lst in (ran, d['ran_first']):
            if 'Q2' in lst:
                lst[lst.index('Q2')] = 'Q4B'
        built.append('Q4B')
    # S8: admitted where the DFA built usable fixed-distance candidates (lazily, in any phase).
    if any(BUILD['S8'].search(m) for ph in ('setup', 'first', 'op') for m in d[ph]):
        built.append('S8')
    # L5X: WindowLiteralSearch.analyze checks the 64-lane start literal filter only once it has a literal window and
    # constructs the search unless that check is true, so more checks than constructions means it stepped aside.
    keeps = sum(v for ph in ('setup', 'first', 'op') for m, v in d[ph].items() if m == 'WindowLiteralSearch.keepsOneOrTwoLiteralStartFilter')
    inits = sum(v for ph in ('setup', 'first', 'op') for m, v in d[ph].items() if m == 'WindowLiteralSearch.<init>')
    if keeps > inits:
        built.append('L5X')
    built.sort(key=ORDER.index)
    d['beside_admitted'] = sum(v for m, v in d['setup'].items() if m.endswith('RequiredBytes.withBesideRouteSearch'))
    d['beside_other'] = beside
    d['ran'], d['built'] = ran, built
    shape = ''
    for path, st in d['static']:
        m = re.match(r'class=([^;]+)', st)
        if m:
            shape = m.group(1)
            inner = re.search(r'\{inner:class=([^;]+)', st)
            if inner:
                shape += '>' + inner.group(1)
            break
    d['shape'] = shape
    cache[key] = d
    return d

rowmap = {}
for x in csv.DictReader(open(ROWMAP), delimiter='\t'):
    rowmap[(x['row_id'], x['language'], x['platform'], x['memory_mode'])] = x['unit']
out = []
for r in rows:
    key = rowmap[(r['id'], r['language'], r['platform'], r['memoryMode'])]
    d = load(key)
    sec = section(r)
    out.append({'row_id': r['id'], 'language': r['language'], 'operation': r['operation'], 'platform': r['platform'], 'memory_mode': r['memoryMode'],
                'section': sec, 'evidence': EVIDENCE[sec], 'family': r['family'], 'state': r['result']['state'],
                'ratio': '%.4f' % r['result']['ratio'] if r['result'].get('ratio') else '', 'tier': tier(r),
                'mapping': (r.get('mapping') or {}).get('status', ''), 'census': d['status'] + ('/setup-verify-failed' if d.get('setup_failed') else ''),
                'shape': d['shape'], 'route': '+'.join(d['ran']), 'built': '+'.join(d['built']),
                'dfa_routes': ','.join(f'{k}={v}' for k, v in sorted(d['routes_op'].items())),
                'cold_route': '' if d['ran_first'] == d['ran'] or not d['first'] else '+'.join(d['ran_first']), 'unit': key})
cols = list(out[0])
with open(OUT, 'w') as f:
    f.write('\t'.join(cols) + '\n')
    for o in out:
        f.write('\t'.join(str(o[c]) for c in cols) + '\n')
with open(SIDE, 'w') as f:
    f.write('unit\tbeside_admitted\tlong_window_literal_other\n')
    for key, d in cache.items():
        if d.get('beside_admitted') or d.get('beside_other'):
            f.write(f"{key}\t{d['beside_admitted']}\t{d['beside_other']}\n")
print(len(out), 'rows from', len(cache), 'units;', dict(C.Counter(o['census'] for o in out)))
