"""Summaries of MEMORY-jvm.tsv / SWEEP-jvm.tsv / THREADS-jvm.tsv for the report message and README."""
import csv, sys, statistics, collections
d = sys.argv[1]
rows = list(csv.DictReader(open(f'{d}/MEMORY-jvm.tsv'), delimiter='\t'))
reg = [r for r in rows if r['engine'] == 'regulator' and r['ops'] != '-']
def num(r, k): return int(r[k]) if r[k] not in ('-', '') else None
def dist(items, key, label):
    vals = sorted(((num(r, key), r) for r in items if num(r, key) is not None), key=lambda t: t[0])
    if not vals: return
    v = [x for x, _ in vals]
    p90 = v[int(0.9 * (len(v) - 1))]
    mx = vals[-1]
    print(f"{label} {key}: n={len(v)} median={statistics.median(v):,.0f} p90={p90:,} max={mx[0]:,} ({mx[1]['row_id']} {mx[1]['language']} {mx[1]['mode']})")
print('regulator rows', len(reg), collections.Counter(r['settles'] for r in reg))
print('skips/errors', sum(1 for r in rows if r['ops'] == '-'))
for mode in ('safe', 'native'):
    sub = [r for r in reg if r['mode'] == mode]
    for key in ('fixed_heap_bytes', 'fixed_native_bytes', 'peak_heap_bytes', 'peak_native_bytes', 'settled_heap_bytes', 'dfa_peak_used_bytes', 'compile_alloc_bytes', 'matcher_heap_bytes'):
        dist(sub, key, mode)
    def total(r): return num(r, 'peak_heap_bytes') + num(r, 'peak_native_bytes')
    top = sorted(sub, key=lambda r: -total(r))[:5]
    for r in top: print('  top peak total', mode, f'{total(r):,}', r['row_id'], r['language'], r['operation'])
gen = [r for r in reg if r['metaspace_bytes'] not in ('-', '')]
dist(gen, 'metaspace_bytes', 'generated')
# ratio vs comparator (safe Regulator vs comparator, same row/language)
comp = {(r['row_id'], r['language']): r for r in rows if r['engine'] in ('jdk', 'joni') and r['ops'] != '-'}
for key in ('fixed_heap_bytes', 'peak_heap_bytes'):
    for eng in ('jdk', 'joni'):
        for mode in ('safe', 'native'):
            ratios = []
            for r in reg:
                c = comp.get((r['row_id'], r['language']))
                if r['mode'] != mode or not c or c['engine'] != eng: continue
                a = num(r, key) + (num(r, key.replace('heap', 'native')) or 0)
                b = num(c, key)
                if a and b: ratios.append((a / b, r['row_id']))
            if ratios:
                ratios.sort()
                v = [x for x, _ in ratios]
                print(f"ratio regulator/{eng} {mode} {key}(+native): n={len(v)} median={statistics.median(v):.2f} p10={v[int(.1*(len(v)-1))]:.2f} p90={v[int(.9*(len(v)-1))]:.2f} min={v[0]:.3f} ({ratios[0][1]}) max={v[-1]:.1f} ({ratios[-1][1]})")
