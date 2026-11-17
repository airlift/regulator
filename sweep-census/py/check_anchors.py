"""Check the anchors against the built classes' method table (MethodTable output).
Usage: python3 -I check_anchors.py <methods.tsv> <anchor-check.md>
Lists run and build anchors that match no method (an anchor a rename left behind credits nothing), and for each
platform form in anchors.PLATFORM_FORMS the components its run anchors credit next to its counterpart's.
Exits 1 when a platform form or its counterpart is missing from the classes or they credit different components
(other than the E3/E3F split by design)."""
import os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anchors import A, PLATFORM_FORMS
methods = sorted({f"{c}.{m.split('(')[0]}" for c, m, *_ in (l.rstrip('\n').split('\t') for l in open(sys.argv[1]))})
RUN = {k: re.compile(v[0]) for k, v in A.items() if v[0] != r'^$'}
BUILD = {k: re.compile(v[1]) for k, v in A.items() if v[1] and v[1] != r'^$'}

def credited(method):
    return sorted(k for k, rx in RUN.items() if rx.search(method))

out = ['# Anchor check', '', f'{len(methods):,} methods in the built classes; {len(A)} components.', '']
dead = [(k, 'run') for k, rx in RUN.items() if not any(rx.search(m) for m in methods)]
dead += [(k, 'build') for k, rx in BUILD.items() if not any(rx.search(m) for m in methods)]
out.append('## Anchors that match no method\n')
out += [f'- {k} {kind} anchor' for k, kind in sorted(dead)] or ['None.']
out += ['', '## Platform forms', '', '| form | gate | credits | counterpart | credits | check |', '|---|---|---|---|---|---|']
bad = 0
for form, gate, other in PLATFORM_FORMS:
    present = form in methods and other in methods
    a, b = credited(form), credited(other)
    split = form.startswith('Dfa.searchForwardPairs') and a == ['E3'] and b == ['E3F']
    ok = present and (a == b or split)
    bad += not ok
    check = 'split by design' if split else 'ok' if ok else 'MISSING METHOD' if not present else 'DIFFERENT COMPONENTS'
    out.append(f"| `{form}` | {gate} | {'+'.join(a) or '-'} | `{other}` | {'+'.join(b) or '-'} | {check} |")
open(sys.argv[2], 'w').write('\n'.join(out) + '\n')
print(len(dead), 'anchors match no method;', bad, 'platform form problems')
sys.exit(1 if bad else 0)
