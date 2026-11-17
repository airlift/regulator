"""Move safe-mode primary records with DFAs, and all safe-mode thread records, to work/superseded/.

The first walker counted a heap memory segment (the safe-mode DFA pointer table) shallow only, so
safe-mode heap bytes missed the pointer table. Native-mode records and DFA-free records are unaffected.
"""
import json
from pathlib import Path
work = Path(__file__).resolve().parent.parent / 'work'
out = work / 'superseded'
out.mkdir(exist_ok=True)
moved = 0
with open(out / 'walker-v1-safe.jsonl', 'a') as sink:
    for path in sorted(work.glob('primary-safe-*.jsonl')) + sorted(work.glob('threads-safe-*.jsonl')):
        keep = []
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            affected = path.name.startswith('threads-') or r.get('fixed_dfas', 0) > 0 or any(c.get('dfas', 0) > 0 for c in r.get('checkpoints', []))
            if affected:
                sink.write(line + '\n')
                moved += 1
            else:
                keep.append(line)
        path.write_text(''.join(l + '\n' for l in keep))
print('moved', moved)
