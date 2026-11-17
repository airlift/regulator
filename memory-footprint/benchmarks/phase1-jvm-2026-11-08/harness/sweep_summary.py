"""Sweep summary: per budget, rows that reset, bail, or reject; result identity."""
import csv, sys, collections
rows = list(csv.DictReader(open(f'{sys.argv[1]}/SWEEP-jvm.tsv'), delimiter='\t'))
print('rows', len(rows), collections.Counter(r['result_identical'] for r in rows))
for b in (96, 64, 32, 24, 16, 8):
    ran = [r for r in rows if r[f'rejected_{b}m'] != '-']
    rej = [r for r in ran if r[f'rejected_{b}m'] == 'yes']
    reset = [r for r in ran if r[f'resets_{b}m'] not in ('-', '0')]
    bail = [r for r in ran if r[f'bails_{b}m'] not in ('-', '0')]
    print(f'{b:>3} MiB: ran {len(ran)}, rejected {len(rej)}, reset {len(reset)}, bailed {len(bail)}')
    for r in reset + [x for x in bail if x not in reset]:
        print(f"      {r['row_id']} {r['language']} {r['mode']} resets={r[f'resets_{b}m']} bails={r[f'bails_{b}m']}")
    for r in rej:
        print(f"      REJ {r['row_id']} {r['language']} {r['mode']}")
