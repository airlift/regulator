"""Expand the pinned Rebar registry (curated + extended) into per-language KLV workload files,
as rebar_inventory.prepare + bulk.prepare would, without building the rebar binary.
Usage: python3 -I expand_rebar.py <src-root> <rebar-root> <out-dir>"""
import sys, json, hashlib, re, tomllib, unicodedata
from pathlib import Path
src, rebar, out = map(Path, sys.argv[1:4])
sys.path.insert(0, str(src / "tools/re2-benchmark/language"))
import rebar_inventory, mappings  # repository code

META = set('\\.+*?()|[]{}^$#&-~')
def escape(p):
    return ''.join('\\' + c if c in META else c for c in p)

def lines_with_terminator(b):
    out, i = [], 0
    while i < len(b):
        j = b.find(b'\n', i)
        if j < 0:
            out.append(b[i:]); break
        out.append(b[i:j + 1]); i = j + 1
    return out

def is_ws(cp):
    return chr(cp).isspace() or unicodedata.category(chr(cp)) == 'Zs'

def haystack(spec):
    if isinstance(spec, str):
        return spec.encode()
    raw = spec['contents'].encode() if 'contents' in spec else (rebar / 'benchmarks/haystacks' / spec['path']).read_bytes()
    if spec.get('utf8-lossy'):
        raw = raw.decode('utf-8', errors='replace').encode()
    if spec.get('trim'):
        raw = raw.decode('utf-8', errors='surrogateescape').strip().encode('utf-8', errors='surrogateescape')
    s, e = spec.get('line-start'), spec.get('line-end')
    if s is not None or e is not None:
        ls = lines_with_terminator(raw)
        if e is not None:
            ls = ls[:e]
        if s is not None:
            ls = ls[s:]
        raw = b''.join(ls)
    if spec.get('repeat'):
        raw = raw * spec['repeat']
    if spec.get('prepend'):
        raw = spec['prepend'].encode() + raw
    if spec.get('append'):
        raw = raw + spec['append'].encode()
    return raw

def transform(opts, pats):
    if opts.get('literal'):
        pats = [escape(p) for p in pats]
    if opts.get('prepend'):
        pats = [opts['prepend'] + p for p in pats]
    if opts.get('append'):
        pats = [p + opts['append'] for p in pats]
    return pats

def regexes(spec):
    if isinstance(spec, str):
        return [spec]
    if isinstance(spec, list):
        return spec
    if 'path' in spec:
        raw = (rebar / 'benchmarks/regexes' / spec['path']).read_text()
        mode = spec.get('per-line', 'none')
        if mode == 'none':
            return transform(spec, [raw.strip()])
        lines = raw.splitlines()
        if mode == 'alternate':
            pats = transform(spec, lines)
            return ['|'.join('(?:%s)' % p for p in pats)]
        return transform(spec, lines)
    pats = spec['patterns']
    return transform(spec, [pats] if isinstance(pats, str) else pats)

def klv(fields):
    b = bytearray()
    for k, v in fields:
        b += k.encode() + b':' + str(len(v)).encode() + b':' + v + b'\n'
    return bytes(b)

rows, _ = rebar_inventory.registry()
out.mkdir(parents=True, exist_ok=True)
cases, mismatches = [], 0
for row in rows:
    name, model = row['name'], row['model']
    definition_path = row.get('definition', name.rsplit('/', 1)[0] + '.toml')
    definition = tomllib.loads((rebar / 'benchmarks/definitions' / definition_path).read_text())
    defid = definition_path.removesuffix('.toml') + '/'
    bench = [b for b in definition['bench'] if defid + b['name'] == name]
    assert len(bench) == 1, name
    bench = bench[0]
    pats = [p.encode() for p in regexes(bench['regex'])]
    hay = haystack(bench['haystack'])
    ci = bool(bench.get('case-insensitive', False))
    uni = bool(bench.get('unicode', False))
    expected = rebar_inventory.expected_native_result(bench)
    ok = True
    if 'pattern_sha256' in row:
        ok = (len(pats) == 1 and hashlib.sha256(pats[0]).hexdigest() == row['pattern_sha256']
              and hashlib.sha256(hay).hexdigest() == row['haystack_sha256'] and len(hay) == int(row['haystack_length']))
        if not ok:
            mismatches += 1
            print('MISMATCH', name, file=sys.stderr)
    base = [('name', name.encode()), ('model', model.encode())] + [('pattern', p) for p in pats] + [
        ('case-insensitive', b'true' if ci else b'false'), ('unicode', b'true' if uni else b'false'), ('haystack', hay),
        ('max-iters', b'0'), ('max-warmup-iters', b'0'), ('max-time', b'0'), ('max-warmup-time', b'0')]
    langs = {}
    for language in ('re2', 'java', 'trino', 'rust'):
        reason = None
        try:
            pats[0].decode('utf-8')
            pattern_utf8 = True
        except UnicodeDecodeError:
            pattern_utf8 = False
        try:
            hay.decode('utf-8'); hay_utf8 = True
        except UnicodeDecodeError:
            hay_utf8 = False
        if language != 're2':
            if not pattern_utf8:
                reason = 'pattern not UTF-8'
            elif model != 'compile' and not hay_utf8 and language != 'rust':
                reason = 'haystack not UTF-8'
        translated, explanation = mappings.translate(name, language, pats[0])
        if translated is None:
            reason = reason or explanation
        budget = None if reason else mappings.memory_budget(name, language)
        fields = list(base)
        if not reason and (translated != pats[0] or budget):
            fields = [f for f in fields if f[0] != 'pattern']
            fields.insert(2, ('pattern', translated))
            if budget:
                fields.append(('max-memory', str(budget).encode()))
        payload = klv(fields)
        digest = hashlib.sha256(payload).hexdigest()
        path = out / f'{digest}.klv'
        if not path.exists():
            path.write_bytes(payload)
        langs[language] = {'status': 'not-compatible' if reason else ('translated' if translated != pats[0] else 'identical'),
                           'reason': reason, 'file': str(path), 'pattern': (translated if not reason else pats[0]).decode('utf-8', 'replace')}
    cases.append({'id': name, 'model': model, 'patterns': len(pats), 'haystack_bytes': len(hay), 'expected': expected,
                  'unicode': uni, 'case_insensitive': ci, 'frozen_identity_ok': ok, 'languages': langs})
(out / 'cases.json').write_text(json.dumps(cases, indent=1))
print(len(cases), 'cases;', mismatches, 'frozen-identity mismatches')
