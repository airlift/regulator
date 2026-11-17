"""Write SUMMARY.md: per component, the identities it ran on and was admitted on; unit failures; platform forms.
Usage: python3 -I summarize.py <out-dir> <arch>
Reads <out-dir>/routes-<arch>.tsv, component-rows-<arch>.tsv, census/, anchor-check.md and RUN.txt."""
import os, re, sys, csv, collections as C
from pathlib import Path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anchors import PLATFORM_FORMS
OUT, ARCH = Path(sys.argv[1]), sys.argv[2]
routes = list(csv.DictReader(open(OUT / f'routes-{ARCH}.tsv'), delimiter='\t', quoting=csv.QUOTE_NONE))
crows = list(csv.DictReader(open(OUT / f'component-rows-{ARCH}.tsv'), delimiter='\t', quoting=csv.QUOTE_NONE))
EVIDENCE = ('everyday', 'synthetic', 'constructed', 'diagnostic')
LIST_LIMIT = 40

def fname(key):
    return key.replace('/', '~').replace('|', '@').replace(':', '_')

md = [f'# Route census on {ARCH}', '']
if (OUT / 'RUN.txt').exists():
    md += ['```', (OUT / 'RUN.txt').read_text().strip(), '```', '']
units = sorted({r['unit'] for r in routes})
status = {r['unit']: r['census'] for r in routes}
md += [f'{len(units):,} units over {len(routes):,} report rows; {len({(r["component"], r["language"], r["case"], r["operation"]) for r in crows}):,} '
       f'component rows. Identities are (language, case, operation); tiers and ratios come from the report rows of '
       f'the platform run.sh chose for this architecture. Columns: identities by evidence class '
       f'({" / ".join(EVIDENCE)}).', '']

# Components table.
by = C.defaultdict(lambda: {'ran': [], 'admitted': []})
for r in crows:
    by[r['component']][r['presence']].append(r)
md += ['## Components', '', '| component | ran | admitted only | languages ran |', '|---|---|---|---|']
def counts(rs):
    c = C.Counter(r['evidence'] for r in rs)
    return ' / '.join(str(c[e]) for e in EVIDENCE) if rs else '-'
def natural(k):
    letters, number, suffix = re.fullmatch(r'([A-Z]+)(\d*)(.*)', k).groups()
    return letters, int(number or 0), suffix
order = sorted(by, key=natural)
for k in order:
    langs = sorted({r['language'] for r in by[k]['ran']})
    md.append(f"| {k} | {counts(by[k]['ran'])} | {counts(by[k]['admitted'])} | {', '.join(langs) or '-'} |")
md.append('')

# Identities per component, grouped by (case, operation) with the languages.
md += ['## Identities per component', '',
       f'Each line is one case and operation with the languages it covers. Components with more than {LIST_LIMIT} lines '
       f'list the first {LIST_LIMIT}; component-rows-{ARCH}.tsv has every row.', '']
for k in order:
    md.append(f'### {k}')
    for presence in ('ran', 'admitted'):
        groups = C.defaultdict(set)
        for r in by[k][presence]:
            groups[(r['evidence'], r['case'], r['operation'])].add(r['language'])
        if not groups:
            continue
        md.append(f'- {presence}{"" if presence == "ran" else " only"} ({len(by[k][presence])} identities):')
        items = sorted(groups.items(), key=lambda kv: (EVIDENCE.index(kv[0][0]), kv[0][1], kv[0][2]))
        for (ev, case, op), langs in items[:LIST_LIMIT]:
            md.append(f'  - {ev} `{case}` {op}: {", ".join(sorted(langs))}')
        if len(items) > LIST_LIMIT:
            md.append(f'  - and {len(items) - LIST_LIMIT} more')
    md.append('')

# Unit failures.
bad = [u for u in units if status[u] != 'ok']
md += ['## Unit failures', '', f'{len(bad)} units did not finish cleanly (`exit`/`timeout`), or failed result verification at setup '
       '(`setup-verify-failed`: the benchmark checks its answer against Rebar\'s RE2 count, and the census records the route anyway).', '']
for u in bad:
    detail = ''
    p = OUT / 'census' / (fname(u) + '.txt')
    if p.exists():
        for line in p.read_text(errors='replace').splitlines():
            if line.startswith('P\tsetupFailed\t'):
                detail = line.split('\t', 2)[2][:160]
                break
    if not detail and (OUT / 'census' / (fname(u) + '.err')).exists():
        lines = [l for l in (OUT / 'census' / (fname(u) + '.err')).read_text(errors='replace').splitlines() if 'Exception' in l or 'Error' in l]
        detail = lines[0][:160] if lines else ''
    md.append(f'- `{u}` {status[u]}' + (f': {detail}' if detail else ''))
md.append('')

# Platform forms: units whose census entered each form (op phase, then any phase).
entered = C.defaultdict(lambda: [set(), set()])
names = {f for f, _, _ in PLATFORM_FORMS} | {o for _, _, o in PLATFORM_FORMS}
for u in units:
    p = OUT / 'census' / (fname(u) + '.txt')
    if not p.exists():
        continue
    for line in p.read_text(errors='replace').splitlines():
        if line.startswith('M\t'):
            _, phase, method, _ = line.split('\t')
            m = method.split('(')[0]
            if m in names:
                entered[m][1].add(u)
                if phase == 'op':
                    entered[m][0].add(u)
md += ['## Platform forms entered', '', 'Units whose census entered each platform form and its counterpart: steady state (op), then any phase.', '',
       '| form | gate | units op / any | counterpart | units op / any |', '|---|---|---|---|---|']
for form, gate, other in PLATFORM_FORMS:
    a, b = entered[form], entered[other]
    md.append(f'| `{form}` | {gate} | {len(a[0])} / {len(a[1])} | `{other}` | {len(b[0])} / {len(b[1])} |')
md.append('')
if (OUT / 'anchor-check.md').exists():
    md += ['## Anchor check', '', 'From the built classes\' method table; see anchor-check.md for the platform-form table.', '']
    text = (OUT / 'anchor-check.md').read_text()
    md += [text.split('## Anchors that match no method', 1)[1].split('## Platform forms', 1)[0].strip(), '']
(OUT / 'SUMMARY.md').write_text('\n'.join(md) + '\n')
print('SUMMARY.md:', len(order), 'components,', len(bad), 'unit failures')
