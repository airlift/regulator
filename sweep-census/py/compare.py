"""Write DIFF.md: every component whose ran identities differ from a comparison component-rows file.
Usage: python3 -I compare.py <component-rows.tsv> <comparison component-rows.tsv> <arch> <DIFF.md> [<routes.tsv> <comparison ROUTES.tsv>]
Lists each differing component with its gained (+) and lost (-) ran identities, then a shorter table of admitted-only
changes, then units whose census status differs when both routes tables are given. Past about 30 KB the per-row
lists are cut to the first rows of each component, with the counts kept."""
import sys, csv, collections as C
from pathlib import Path
NEW, OLD, ARCH, OUT = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], Path(sys.argv[4])
ROUTES = (Path(sys.argv[5]), Path(sys.argv[6])) if len(sys.argv) > 6 else None
LIMIT_BYTES = 30_000
# Splits the census expects between platforms, named so a reader does not chase them.
EXPECTED_SPLITS = {
    'E3': 'object paired rows: every non-aarch64 os.arch (E3F on aarch64)',
    'E3F': 'flat paired rows: aarch64 only (E3 elsewhere)',
    'L5X': 'window literal search steps aside at 64 lanes',
}

def load(path):
    rows = list(csv.DictReader(open(path), delimiter='\t', quoting=csv.QUOTE_NONE))
    by = C.defaultdict(dict)
    for r in rows:
        by[r['presence']][(r['component'], r['language'], r['case'], r['operation'])] = r
    return by

new, old = load(NEW), load(OLD)

def diff(presence):
    a, b = new.get(presence, {}), old.get(presence, {})
    if presence == 'admitted':
        # an identity that moved between ran and admitted is reported once, under ran
        a = {k: v for k, v in a.items() if k not in old.get('ran', {})}
        b = {k: v for k, v in b.items() if k not in new.get('ran', {})}
    out = C.defaultdict(lambda: ([], []))
    for k in a.keys() - b.keys():
        out[k[0]][0].append(a[k])
    for k in b.keys() - a.keys():
        out[k[0]][1].append(b[k])
    return out

ran, admitted = diff('ran'), diff('admitted')
units = []
if ROUTES and all(p.exists() for p in ROUTES):
    status = [{r['unit']: r['census'] for r in csv.DictReader(open(p), delimiter='\t', quoting=csv.QUOTE_NONE)} for p in ROUTES]
    units = sorted((u, status[1].get(u, 'absent'), s) for u, s in status[0].items() if status[1].get(u) != s)

def row(sign, r):
    return f"| {sign} | {r['language']} | `{r['case']}` | {r['operation']} | {r['evidence']} | {r['tiers']} |"

def render(limit):
    md = [f'# Census diff: {ARCH} against `{OLD.name}`', '',
          f'Ran identities (language, case, operation) per component. `+` ran here and not in the comparison, `-` the reverse. '
          f'{len(ran)} components differ in ran identities, {len(admitted)} in admitted-only identities, {len(units)} units in census status.', '']
    if not ran and not admitted and not units:
        md.append('No differences.')
    if ran:
        md += ['## Ran identities', '', '| component | + | - | note |', '|---|---:|---:|---|']
        for comp in sorted(ran):
            g, l = ran[comp]
            md.append(f'| {comp} | {len(g)} | {len(l)} | {EXPECTED_SPLITS.get(comp, "")} |')
        md.append('')
        for comp in sorted(ran):
            g, l = ran[comp]
            md += [f'### {comp} (+{len(g)} / -{len(l)})', '', '| | language | case | operation | evidence | tiers |', '|---|---|---|---|---|---|']
            rows = [('+', r) for r in sorted(g, key=lambda r: (r['case'], r['language'], r['operation']))] + \
                   [('-', r) for r in sorted(l, key=lambda r: (r['case'], r['language'], r['operation']))]
            md += [row(s, r) for s, r in rows[:limit]]
            if len(rows) > limit:
                md.append(f'| | | and {len(rows) - limit} more | | | |')
            md.append('')
    if admitted:
        md += ['## Admitted-only identities', '', 'Identities where the component was admitted (built) but did not run, on one side only.', '',
               '| component | + | - | first rows |', '|---|---:|---:|---|']
        for comp in sorted(admitted):
            g, l = admitted[comp]
            first = [f"+{r['language']} `{r['case']}` {r['operation']}" for r in sorted(g, key=lambda r: r['case'])] + \
                    [f"-{r['language']} `{r['case']}` {r['operation']}" for r in sorted(l, key=lambda r: r['case'])]
            shown = max(1, min(limit, 5))
            md.append(f"| {comp} | {len(g)} | {len(l)} | {'; '.join(first[:shown])}{' …' if len(first) > shown else ''} |")
        md.append('')
    if units:
        md += ['## Unit status', '', '| unit | comparison | here |', '|---|---|---|']
        md += [f'| `{u}` | {o} | {n} |' for u, o, n in units[:max(limit, 20)]]
        if len(units) > max(limit, 20):
            md.append(f'| and {len(units) - max(limit, 20)} more | | |')
        md.append('')
    return '\n'.join(md) + '\n'

for limit in (10**9, 50, 20, 10, 5, 2, 0):
    text = render(limit)
    if len(text.encode()) <= LIMIT_BYTES:
        break
OUT.write_text(text)
print(f'DIFF.md: {len(ran)} components differ in ran identities, {len(admitted)} in admitted-only, {len(units)} units in status; {len(text.encode()):,} bytes')
