"""Builds rebar KLV inputs for the Regulator rebar rows from rebar's benchmark definitions.

Mirrors rebar src/format/benchmarks.rs: regex and haystack options, group names from the TOML path.
Usage: rebar_klv.py <rebar checkout> <results csv> <output dir>
Writes <output dir>/<sha>.klv and index.tsv (name, model, engine, klv, iters).
"""
import csv
import hashlib
import sys
import tomllib
from pathlib import Path

META = set('\\.+*?()|[]{}^$#&-~')


def escape(pattern):
    return ''.join('\\' + c if c in META else c for c in pattern)


def transform_patterns(options, patterns):
    if options.get('literal'):
        patterns = [escape(p) for p in patterns]
    if 'prepend' in options:
        patterns = [options['prepend'] + p for p in patterns]
    if 'append' in options:
        patterns = [p + options['append'] for p in patterns]
    return patterns


def regexes(definition, root):
    regex = definition['regex']
    if isinstance(regex, str):
        return [regex]
    if isinstance(regex, list):
        return list(regex)
    if 'path' in regex:
        raw = (root / 'benchmarks' / 'regexes' / regex['path']).read_text(encoding='utf-8')
        per_line = regex.get('per-line', 'none')
        if per_line == 'none':
            return transform_patterns(regex, [raw.strip()])
        lines = raw.splitlines()
        if per_line == 'alternate':
            return ['|'.join(f'(?:{p})' for p in transform_patterns(regex, lines))]
        return transform_patterns(regex, lines)
    patterns = regex['patterns']
    return transform_patterns(regex, [patterns] if isinstance(patterns, str) else list(patterns))


def lines_with_terminator(raw):
    out = []
    start = 0
    while start < len(raw):
        end = raw.find(b'\n', start)
        end = len(raw) if end < 0 else end + 1
        out.append(raw[start:end])
        start = end
    return out


def haystack(definition, root):
    hay = definition['haystack']
    if isinstance(hay, str):
        return hay.encode()
    raw = (root / 'benchmarks' / 'haystacks' / hay['path']).read_bytes() if 'path' in hay else hay['contents'].encode()
    if hay.get('utf8-lossy'):
        raw = raw.decode('utf-8', errors='replace').encode()
    if hay.get('trim'):
        raw = raw.strip()
    start, end = hay.get('line-start'), hay.get('line-end')
    if start is not None or end is not None:
        lines = lines_with_terminator(raw)
        if end is not None:
            lines = lines[:end]
        if start is not None:
            lines = lines[start:]
        raw = b''.join(lines)
    if 'repeat' in hay:
        raw = raw * hay['repeat']
    if 'prepend' in hay:
        raw = hay['prepend'].encode() + raw
    if 'append' in hay:
        raw = raw + hay['append'].encode()
    return raw


def klv(name, definition, root):
    items = [('name', name.encode()), ('model', definition['model'].encode()),
             ('case-insensitive', str(definition.get('case-insensitive', False)).lower().encode()),
             ('unicode', str(definition.get('unicode', False)).lower().encode())]
    items += [('pattern', p.encode()) for p in regexes(definition, root)]
    items.append(('haystack', haystack(definition, root)))
    return b''.join(k.encode() + b':' + str(len(v)).encode() + b':' + v + b'\n' for k, v in items)


def definitions(root):
    base = root / 'benchmarks' / 'definitions'
    for path in sorted(base.rglob('*.toml')):
        group = str(path.relative_to(base).with_suffix(''))
        for bench in tomllib.loads(path.read_text(encoding='utf-8')).get('bench', []):
            yield f"{group}/{bench['name']}", bench


def main():
    root, results, output = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    output.mkdir(parents=True, exist_ok=True)
    wanted = {}
    for row in csv.DictReader(results.open()):
        if row['engine'].startswith('regulator/'):
            wanted[(row['name'], row['engine'])] = row
    defs = dict(definitions(root))
    with (output / 'index.tsv').open('w') as index:
        index.write('name\tmodel\tengine\tklv\titers\terr\n')
        for (name, engine), row in sorted(wanted.items()):
            data = klv(name, defs[name], root)
            digest = hashlib.sha256(data).hexdigest()[:24]
            path = output / f'{digest}.klv'
            if not path.exists():
                path.write_bytes(data)
            index.write(f"{name}\t{defs[name]['model']}\t{engine}\t{path}\t{row['iters']}\t{row['err']}\n")


if __name__ == '__main__':
    main()
