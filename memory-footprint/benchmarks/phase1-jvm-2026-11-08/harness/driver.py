"""Phase 1 JVM memory-footprint driver: builds jobs, runs MemoryProbe JVMs, aggregates TSVs.

Byte counting only. The wall clock bounds how long a row runs; nothing is timed or reported.

  driver.py jobs                      build work/jobs.json from the report, manifests, rebar index
  driver.py run <pass> [--parallel N] run a pass: primary | sweep | threads (resumable)
  driver.py aggregate <outdir>        write MEMORY-jvm.tsv, SWEEP-jvm.tsv, THREADS-jvm.tsv
"""
import argparse
import csv
import gzip
import json
import math
import statistics
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CHECKOUT = Path('/Users/dain/.t3/worktrees/regulator/user-dain-memory-footprint')
REPORT = CHECKOUT / 'benchmark-report/data/regulator-858993c3497e2df5576bbf412d4373fc7b7eae5b-137669c8e9fac99b.json.gz'
BULK = Path('/Users/dain/work/airlift/regulator-benchmark-results/release-2-0-candidate-20261029/plans/bulk-manifest')
LIFECYCLE = ROOT / 'language-manifest'
REBAR_INDEX = ROOT / 'rebar-klv/index.tsv'
REBAR_RESULTS = Path.home() / 'AgentNotes/regulator/user/dain/rebar-prototype/results'
WORK = ROOT / 'work'
MODES = ('safe', 'native')
COMPARATOR = {'java': 'jdk', 'trino': 'joni'}
OPS_CAP = 20_000_000
PRIMARY_WALL = 60
SWEEP_BUDGETS = '96,64,32,24,16,8'
SWEEP_COLUMNS = (96, 64, 32, 24, 16, 8)
DEFAULT_BUDGET = 96 << 20


def report_pairs():
    rows = json.load(gzip.open(REPORT))['rows']
    pairs = defaultdict(list)
    for row in rows:
        pairs[(row['id'], row['language'])].append(row)
    return pairs


def min_ns(rows, key):
    values = [r['result'].get(key) for r in rows if r['result'].get(key)]
    return min(values) if values else None


def target_ops(seconds, ns):
    if not ns:
        return OPS_CAP
    return max(1, min(OPS_CAP, math.ceil(seconds / (ns * 1e-9))))


def build_jobs():
    pairs = report_pairs()
    lifecycle = {c['id']: c for c in json.load(open(LIFECYCLE / 'manifest.json'))['cases']}
    bulk = {c['id']: c for c in json.load(open(BULK / 'manifest.json'))['cases']}
    jobs = []
    skips = []

    def add(family, row_id, language, job_engine, job_variant, ops, **spec):
        job_id = f'{len(jobs):05d}'
        jobs.append(dict(id=job_id, family=family, row_id=row_id, language=language, engine=job_engine,
                         variant=job_variant, target=ops, spec=spec))

    for (row_id, language), rows in sorted(pairs.items()):
        first = rows[0]
        state = first['result']['state']
        cand = min_ns(rows, 'candidateNs')
        comp = min_ns(rows, 'comparatorNs')
        parts = row_id.split('/')
        if parts[0] == 'language-lifecycle':
            case_id, op = '/'.join(parts[1:-1]), parts[-1]
            case = lifecycle[case_id]
            file = str(LIFECYCLE / case['mappings'][language]['input_file'])
            ops = 1 if op == 'compile' or op.startswith('singleUse') else target_ops(20, cand)
            add('lifecycle', row_id, language, 'regulator', '-', ops, engine=language, op=op, file=file)
            if language in COMPARATOR:
                if state == 'not-compatible':
                    skips.append((row_id, language, COMPARATOR[language], 'comparator not compatible'))
                else:
                    cops = 1 if op == 'compile' or op.startswith('singleUse') else target_ops(20, comp)
                    add('lifecycle', row_id, language, COMPARATOR[language], '-', cops, engine=COMPARATOR[language], op=op, file=file)
        elif parts[0] == 'language-bulk':
            case_id, op = '/'.join(parts[1:-1]), parts[-1]
            case = bulk[case_id]
            mapping = case['mappings'][language]
            if (op == 'compile') != (case['model'] == 'compile'):
                raise ValueError(f'{row_id}: op {op} vs model {case["model"]}')
            file = str(BULK / mapping['input_file'])
            ops = 1 if op == 'compile' else target_ops(20, cand)
            add('bulk', row_id, language, 'regulator', '-', ops, engine=language, file=file, expected=case['expected_result'])
            if language in COMPARATOR:
                if state == 'not-compatible':
                    skips.append((row_id, language, COMPARATOR[language], 'comparator not compatible'))
                elif any(r['result']['state'] == 'did-not-finish' for r in rows):
                    skips.append((row_id, language, COMPARATOR[language], 'comparator did not finish in the published run (quadratic); one operation does not complete'))
                else:
                    cops = 1 if op == 'compile' else target_ops(20, comp)
                    add('bulk', row_id, language, COMPARATOR[language], '-', cops, engine=COMPARATOR[language], file=file, expected=case['expected_result'])
        elif parts[1] == 'trino-operations':
            workload, op = parts[3], parts[4]
            add('everyday', row_id, language, 'regulator', '-', target_ops(1, cand), engine='regulator', param=workload, op=op)
            add('everyday', row_id, language, 'joni', '-', target_ops(1, comp), engine='joni', param=workload, op=op)
        elif parts[1].endswith('-specialized-patterns'):
            lang = parts[1].split('-')[0]
            workload, op = parts[2], parts[3]
            variant = parts[4] if len(parts) > 4 else 'default'
            add('specialized', row_id, language, 'regulator', variant, target_ops(20, cand),
                lang=lang, engine='regulator', variant=variant, param=workload, op=op)
            if variant == 'default' and lang in COMPARATOR:
                add('specialized', row_id, language, COMPARATOR[lang], '-', target_ops(20, comp),
                    lang=lang, engine=COMPARATOR[lang], variant='default', param=workload, op=op)
        elif parts[1] in ('like-compile', 'like-single-use', 'like-dfa-single-use', 'trino-like', 'trino-like-specialized'):
            kind = {'like-compile': 'compile', 'like-single-use': 'single-use', 'like-dfa-single-use': 'single-use',
                    'trino-like': 'matches', 'trino-like-specialized': 'tpch'}[parts[1]]
            generated = len(parts) > 3 and parts[3] == 'generated'
            ops = 1 if kind in ('compile', 'single-use') else target_ops(20, cand)
            add('like', row_id, language, 'regulator', 'generated' if generated else '-', ops,
                kind=kind, param=parts[2], generated=str(generated).lower())
            if first.get('comparator') or (first['result'].get('comparatorNs')):
                skips.append((row_id, language, 'trino-sql/trino-optimized', 'Trino LIKE comparators are outside the JVM comparator scope (jdk, joni)'))
        else:
            raise ValueError(row_id)

    iters = defaultdict(int)
    for platform in ('r9g', 'r8i'):
        for row in csv.DictReader(open(REBAR_RESULTS / f'{platform}.csv')):
            if row['engine'].startswith('regulator/') and row['iters']:
                iters[(row['name'], row['engine'])] = max(iters[(row['name'], row['engine'])], int(row['iters']))
    for row in csv.DictReader(open(REBAR_INDEX), delimiter='\t'):
        lang = row['engine'].split('/')[1]
        row_id = f"rebar/{row['name']}"
        if row['err']:
            skips.append((row_id, lang, 'regulator', 'rebar run failed: ' + row['err'].split('last line of stderr is: ')[-1][:120]))
            continue
        n = iters[(row['name'], row['engine'])]
        ops = 1 if row['model'] in ('compile', 'regex-redux') else max(1, min(2_000_000, math.ceil(n * 1.5)))
        add('rebar', row_id, lang, 'regulator', '-', ops, lang=lang, file=str(ROOT / row['klv']), model=row['model'])
    WORK.mkdir(exist_ok=True)
    json.dump({'jobs': jobs, 'skips': skips}, open(WORK / 'jobs.json', 'w'), indent=1)
    by = defaultdict(int)
    for job in jobs:
        by[(job['family'], job['engine'])] += 1
    for key, count in sorted(by.items()):
        print(key, count)
    print('jobs', len(jobs), 'skips', len(skips))


HEAVY_DFA_BYTES = 1 << 20


def sweep_plan(job, record):
    """Budgets and op count for one row's sweep run.

    Every row runs 32, 24, 16 and 8 MiB. A row whose default-budget run used at least 1 MiB of
    DFA state, or reset, repeats its full default-run op count and adds 64 MiB; other rows cap
    the op count (10 for whole-haystack rows, 100 for scalar rows), since their DFA state fits
    every budget. Cheap scalar families also run a 96 MiB mirror compile to validate the mirror.
    """
    s = summarize(record)
    ops = max(1, record['ops'])
    heavy = s['peak_used'] >= HEAVY_DFA_BYTES or s['resets'] > 0
    budgets = ['32', '24', '16', '8']
    if heavy:
        budgets.insert(0, '64')
    else:
        ops = min(ops, 10 if job['family'] in ('bulk', 'rebar') else 100)
    if job['family'] in ('lifecycle', 'everyday', 'specialized'):
        budgets.insert(0, '96')
    return {'budgets': ','.join(budgets), 'target': ops, 'wall': 30, 'heavy': str(heavy).lower()}


def job_line(job, extra):
    items = {'id': job['id'], 'family': job['family'], 'target': job['target'], 'wall': PRIMARY_WALL}
    items.update(job['spec'])
    items.update(extra)
    return '\t'.join(f'{k}={v}' for k, v in items.items())


def load_results(pattern):
    results = {}
    for path in sorted(WORK.glob(pattern)):
        for line in path.read_text().splitlines():
            if line.strip():
                record = json.loads(line)
                results.setdefault((record['job'], record['mode']), []).append(record)
    return results


CHUNK = {'lifecycle': 40, 'bulk': 6, 'everyday': 30, 'specialized': 15, 'like': 30, 'rebar': 5}


def run_pass(name, parallel, only_family=None, limit=None):
    data = json.load(open(WORK / 'jobs.json'))
    jobs = [j for j in data['jobs'] if not only_family or j['family'] == only_family]
    done = load_results(f'{name}-*.jsonl')
    primary = load_results('primary-*.jsonl') if name != 'primary' else {}
    sweep_done = load_results('sweep-*.jsonl') if name == 'sweep-edge' else {}
    tasks = []
    for mode in MODES:
        pending = []
        for job in jobs:
            if (job['id'], mode) in done:
                continue
            if name == 'primary':
                if job['family'] == 'rebar' and mode == 'safe':
                    continue
                pending.append(job_line(job, {'budgets': 'default'}))
            elif name == 'sweep':
                if job['engine'] != 'regulator' or job['family'] == 'like':
                    continue
                records = primary.get((job['id'], mode))
                if not records or 'error' in records[0] or records[0].get('rejected'):
                    continue
                pending.append(job_line(job, sweep_plan(job, records[0])))
            elif name == 'sweep-edge':
                # Rows a sweep budget rejected without a 64 MiB run: locate the rejection edge.
                if job['engine'] != 'regulator' or job['family'] == 'like':
                    continue
                records = sweep_done.get((job['id'], mode), [])
                budgets = {r['budget'] >> 20 for r in records}
                if not any(r.get('rejected') for r in records) or 64 in budgets:
                    continue
                prim = primary[(job['id'], mode)][0]
                ops = min(max(1, prim['ops']), 10 if job['family'] in ('bulk', 'rebar') else 100)
                pending.append(job_line(job, {'budgets': '64,48', 'target': ops, 'wall': 30}))
            elif name == 'threads':
                if job['id'] not in THREAD_SET:
                    continue
                records = primary.get((job['id'], mode))
                if not records or 'error' in records[0]:
                    continue
                ops = max(1, records[0]['ops'])
                pending.append(job_line(job, {'threads': '1,4,16', 'target': ops, 'wall': 120}))
        family_of = {j['id']: j['family'] for j in jobs}
        groups = defaultdict(list)
        for line in pending:
            groups[family_of[line.split('\t')[0][3:]]].append(line)
        for family, lines in groups.items():
            size = CHUNK[family] if not name.startswith('sweep') else max(1, CHUNK[family] // 3)
            if name == 'threads':
                size = 1
            for i in range(0, len(lines), size):
                tasks.append((mode, family, i // size, lines[i:i + size]))
    if limit:
        tasks = tasks[:limit]
    print(f'{name}: {len(tasks)} JVM tasks', flush=True)
    running = []
    stamp = time.strftime('%H%M%S')
    while tasks or running:
        while tasks and len(running) < parallel:
            mode, family, index, lines = tasks.pop(0)
            tag = f'{name}-{mode}-{family}-{stamp}-{index:04d}'
            jobs_file = WORK / 'chunks' / f'{tag}.tsv'
            jobs_file.parent.mkdir(exist_ok=True)
            jobs_file.write_text('\n'.join(lines) + '\n')
            log = open(WORK / 'logs' / f'{tag}.log', 'w') if (WORK / 'logs').mkdir(exist_ok=True) or True else None
            heap = '12g' if family in ('bulk', 'rebar') else '4g'
            proc = subprocess.Popen([str(HERE / 'probe.sh'), mode, str(jobs_file), str(WORK / f'{tag}.jsonl'), heap],
                                    stdout=log, stderr=subprocess.STDOUT)
            running.append((proc, tag, log))
        time.sleep(1)
        for item in list(running):
            proc, tag, log = item
            if proc.poll() is not None:
                log.close()
                running.remove(item)
                if proc.returncode != 0:
                    print(f'  {tag} exited {proc.returncode}', flush=True)
        print(f'  {len(tasks)} queued, {len(running)} running', end='\r', flush=True)
    print()


THREAD_SET = set()


def load_thread_set():
    path = WORK / 'thread-jobs.txt'
    if path.exists():
        THREAD_SET.update(path.read_text().split())


# ------------------------------------------------------------------------------------------
# Aggregation

MEMORY_HEADER = ('row_id', 'language', 'engine', 'mode', 'operation', 'input_bytes', 'ops', 'fixed_heap_bytes',
                 'fixed_native_bytes', 'peak_heap_bytes', 'peak_native_bytes', 'settled_heap_bytes',
                 'settled_native_bytes', 'dfa_budget_bytes', 'dfa_peak_used_bytes', 'resets', 'resets_per_kop', 'bails',
                 'settles', 'plateau_bytes', 'notes', 'compile_alloc_bytes', 'metaspace_bytes', 'matcher_heap_bytes')


def operation_of(job):
    spec = job['spec']
    if job['family'] == 'rebar':
        return spec['model']
    if job['family'] == 'bulk':
        return job['row_id'].rsplit('/', 1)[1]
    if job['family'] == 'like':
        return {'compile': 'compile', 'single-use': 'singleUse', 'matches': 'matches', 'tpch': 'matches'}[spec['kind']]
    return spec.get('op', '-')


def summarize(record):
    """Per-row numbers from one probe record."""
    cps = record.get('checkpoints') or []
    last = cps[-1] if cps else {}
    resets = last.get('resets', 0)
    ops = record.get('ops', 0)
    sampled_heap = max([c['heap'] for c in cps] + [record['fixed_heap']])
    sampled_native = max([c['native'] + c['native_superseded'] for c in cps] + [record.get('fixed_native', 0)])
    settled_heap = last.get('heap', record['fixed_heap'])
    settled_native = last.get('native', 0)
    budget = last.get('budget', 0)
    used = last.get('used', 0)
    peak_used = max([c['used'] for c in cps] + [0])
    notes = []
    peak_heap = sampled_heap
    if resets > 0:
        peak_used = max(peak_used, budget)
        bound = settled_heap + max(0, budget - used)
        if bound > peak_heap:
            peak_heap = bound
        notes.append('peak bounded by DFA budget (resets)')
    # Settling: no resets and flat DFA use over the last part of the run.
    settles = 'n/a'
    if record.get('stop') in ('compile',) or ops <= 1:
        settles = 'n/a'
    elif record.get('stop') == 'stable':
        settles = 'yes'
    else:
        half = [c for c in cps if c['ops'] <= ops / 2]
        ref = half[-1] if half else cps[0]
        settles = 'yes' if ref['resets'] == last['resets'] and ref['used'] == last['used'] and ref['states'] == last['states'] else 'no'
        if record.get('stop') == 'wall':
            notes.append(f'hit wall cap at {ops} ops')
    plateau = settled_heap + settled_native if settles == 'yes' else '-'
    return dict(ops=ops, resets=resets, peak_heap=peak_heap, peak_native=sampled_native, settled_heap=settled_heap,
                settled_native=settled_native, budget=budget, peak_used=peak_used, settles=settles, plateau=plateau,
                bails=last.get('bails', 0), notes=notes, dfas=last.get('dfas', 0))


def klv_haystack_bytes(path):
    """Total haystack bytes in a rebar KLV file (key:length:value records)."""
    data = Path(path).read_bytes()
    total = 0
    pos = 0
    while pos < len(data):
        colon = data.index(b':', pos)
        key = data[pos:colon]
        colon2 = data.index(b':', colon + 1)
        length = int(data[colon + 1:colon2])
        if key == b'haystack':
            total += length
        pos = colon2 + 1 + length + 1
    return total


def input_bytes(pairs, job):
    if job['family'] == 'rebar':
        return klv_haystack_bytes(job['spec']['file'])
    rows = pairs.get((job['row_id'], job['language']))
    if rows and rows[0].get('inputBytes') is not None:
        return rows[0]['inputBytes']
    return '-'


def aggregate(outdir):
    outdir = Path(outdir)
    data = json.load(open(WORK / 'jobs.json'))
    jobs = {j['id']: j for j in data['jobs']}
    pairs = report_pairs()
    primary = load_results('primary-*.jsonl')
    sweep = load_results('sweep-*.jsonl')
    threads = load_results('threads-*.jsonl')
    memory_rows = []
    for job_id, job in jobs.items():
        for mode in MODES:
            records = primary.get((job_id, mode))
            if not records:
                continue
            record = records[0]
            mode_label = mode if job['engine'] == 'regulator' else '-'
            if job['engine'] != 'regulator' and mode == 'native':
                continue  # comparators: mode '-', report the safe-mode JVM
            base = [job['row_id'], job['language'], job['engine'], mode_label, operation_of(job), input_bytes(pairs, job)]
            state = pairs[(job['row_id'], job['language'])][0]['result']['state'] if (job['row_id'], job['language']) in pairs else None
            if 'error' in record or record.get('rejected'):
                reason = record.get('error', 'rejected')
                if state == 'not-compatible' and 'ParseException' in reason:
                    label = 'skipped: pair is not-compatible in the published report; the pattern does not parse in this language: '
                else:
                    label = 'rejected: ' if record.get('rejected') else 'error: '
                memory_rows.append(base + ['-'] * 14 + [label + reason.split(' @ ')[0][:300], '-', '-', '-'])
                continue
            s = summarize(record)
            notes = list(s['notes'])
            if state and state != 'compared':
                notes.append(f'report_state={state}')
            if job['variant'] not in ('-', 'default'):
                notes.append('variant=' + job['variant'])
            notes.append('impl=' + record.get('impl', '?').split('/')[0])
            if record.get('note'):
                notes.append(record['note'])
            if record.get('matcher_dfas'):
                notes.append(f"dfas_on_matcher={record['matcher_dfas']}")
            notes.append(f"dfas={s['dfas']}")
            notes.append(f"shared_heap_excluded={record.get('shared_heap', 0)}")
            notes.append(f"stop={record.get('stop')}")
            generated = job['variant'] == 'generated'
            meta = '-'
            if generated:
                meta = record['metaspace']
                notes.append(f"hidden_classes={record['classes']}")
            is_regulator = job['engine'] == 'regulator'
            has_dfa = is_regulator and job['family'] != 'like'
            resets_per_kop = round(s['resets'] * 1000 / s['ops'], 3) if s['ops'] else 0
            memory_rows.append(base + [
                s['ops'], record['fixed_heap'], record.get('fixed_native', 0), s['peak_heap'], s['peak_native'],
                s['settled_heap'], s['settled_native'],
                s['budget'] if has_dfa else '-', s['peak_used'] if has_dfa else '-',
                s['resets'] if has_dfa else '-', resets_per_kop if has_dfa else '-',
                s['bails'] if has_dfa else '-', s['settles'] if is_regulator or s['settles'] != 'n/a' else s['settles'],
                s['plateau'], '; '.join(notes), record['compile_alloc'], meta, record['matcher_heap']])
    for row_id, language, engine, reason in data['skips']:
        if engine not in ('regulator', 'jdk', 'joni'):
            continue  # Trino LIKE comparators: out of the JVM comparator scope, covered in the README
        mode_label = 'native' if engine == 'regulator' else '-'
        operation = row_id.rsplit('/', 1)[1] if row_id.startswith('language-') else '-'
        memory_rows.append([row_id, language, engine, mode_label, operation, '-'] + ['-'] * 14 + ['skipped: ' + reason, '-', '-', '-'])
    with open(outdir / 'MEMORY-jvm.tsv', 'w', newline='') as out:
        writer = csv.writer(out, delimiter='\t', lineterminator='\n')
        writer.writerow(MEMORY_HEADER)
        writer.writerows(memory_rows)

    # Sweep
    sweep_rows = []
    header = ['row_id', 'language', 'mode']
    for b in SWEEP_COLUMNS:
        header += [f'resets_{b}m', f'bails_{b}m', f'rejected_{b}m']
    header += ['result_identical', 'notes']
    for job_id, job in jobs.items():
        if job['engine'] != 'regulator' or job['family'] == 'like':
            continue
        for mode in MODES:
            records = sweep.get((job_id, mode))
            prim = primary.get((job_id, mode))
            if not records or not prim:
                continue
            prim = prim[0]
            by_budget = {r['budget'] >> 20: r for r in records}
            default_from_primary = 96 not in by_budget
            runner_fallback = 'runner budget' in (prim.get('note') or '')
            if default_from_primary and not runner_fallback:
                by_budget[96] = prim  # bulk and rebar: the default compile already uses 96 MiB
            if runner_fallback:
                # The default 96 MiB compile rejects this rebar pattern; the runner compiles at 384 MiB.
                by_budget[96] = {'rejected': True, 'error': 'default rejects'}
            row = [job['row_id'], job['language'], mode]
            identical = True
            compared = 0
            notes = []
            ref = {c['ops']: c['checksum'] for c in prim.get('checkpoints', [])}
            for b in SWEEP_COLUMNS:
                r = by_budget.get(b)
                if r is None:
                    row += ['-', '-', '-']
                    continue
                if 'error' in r and not r.get('rejected'):
                    row += ['-', '-', 'error']
                    notes.append(f'{b}m error: {r["error"][:120]}')
                    continue
                if r.get('rejected'):
                    row += ['-', '-', 'yes']
                    continue
                cps = r.get('checkpoints', [])
                last = cps[-1] if cps else {}
                row += [last.get('resets', 0), last.get('bails', 0), 'no']
                common = [c for c in cps if c['ops'] in ref]
                if not common:
                    notes.append(f'{b}m no checkpoint shared with the default run')
                if common and r is not prim:
                    compared += 1
                if common:
                    c = common[-1]
                    if c['checksum'] != ref[c['ops']]:
                        identical = False
                        notes.append(f'{b}m checksum differs at {c["ops"]} ops')
                if r.get('fixed_heap') is not None and b == 96 and r['fixed_heap'] != prim['fixed_heap']:
                    notes.append(f"96m mirror fixed_heap {r['fixed_heap']} != default {prim['fixed_heap']}")
                if r.get('stop') == 'wall':
                    notes.append(f'{b}m hit wall cap at {r["ops"]} ops')
                if str(r.get('impl')).split('/')[0] != str(prim.get('impl')).split('/')[0]:
                    notes.append(f"{b}m impl={str(r.get('impl')).split('/')[0]}")
            if default_from_primary and not runner_fallback:
                notes.append('96m = default run')
            if runner_fallback:
                notes.append('default 96 MiB rejects; the rebar runner compiles at 384 MiB, which the default run used')
            if 48 in by_budget:
                notes.append('48m ' + ('rejected' if by_budget[48].get('rejected') else 'accepted'))
            notes.append(f"heavy={sweep_plan(job, prim)['heavy'] == 'true' and 'yes' or 'no'}")
            notes.append(f"sweep_ops={max((r.get('ops', 0) for r in records), default=0)}")
            row += ['NO' if not identical else ('yes' if compared else 'n/a'), '; '.join(notes)]
            sweep_rows.append(row)
    with open(outdir / 'SWEEP-jvm.tsv', 'w', newline='') as out:
        writer = csv.writer(out, delimiter='\t', lineterminator='\n')
        writer.writerow(header)
        writer.writerows(sweep_rows)

    # Threads
    thread_rows = []
    for job_id, job in jobs.items():
        for mode in MODES:
            for record in threads.get((job_id, mode), []):
                cps = record.get('checkpoints', [])
                if not cps:
                    thread_rows.append([job['row_id'], job['language'], mode, record.get('threads'), '-', '-', '-', '-', '-', record.get('error', 'no data')[:200]])
                    continue
                last = cps[-1]
                notes = [f"family={job['family']}", f"ops_per_thread={last.get('ops_min', last['ops'])}" + (f"..{last['ops_max']}" if last.get('ops_max', last.get('ops_min')) != last.get('ops_min') else ''), f"dfas={last['dfas']}",
                         f"budget={last['budget']}"]
                peak_heap = max(c['heap'] for c in cps)
                if last['resets'] > 0:
                    peak_heap = max(peak_heap, last['heap'] + max(0, last['budget'] - last['used']))
                    notes.append('peak bounded by DFA budget (resets)')
                if record.get('wall_stopped'):
                    notes.append('wall cap reached')
                if record.get('error'):
                    notes.append('error: ' + record['error'][:150])
                if job['variant'] not in ('-', 'default'):
                    notes.append('variant=' + job['variant'])
                thread_rows.append([job['row_id'], job['language'], mode, record['threads'], peak_heap,
                                    max(c['native'] + c['native_superseded'] for c in cps), last['heap'], last['native'],
                                    last['resets'], '; '.join(notes)])
    with open(outdir / 'THREADS-jvm.tsv', 'w', newline='') as out:
        writer = csv.writer(out, delimiter='\t', lineterminator='\n')
        writer.writerow(['row_id', 'language', 'mode', 'threads', 'peak_heap_bytes', 'peak_native_bytes',
                         'settled_heap_bytes', 'settled_native_bytes', 'resets', 'notes'])
        writer.writerows(thread_rows)
    print('memory', len(memory_rows), 'sweep', len(sweep_rows), 'threads', len(thread_rows))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command')
    parser.add_argument('arg', nargs='?')
    parser.add_argument('--parallel', type=int, default=10)
    parser.add_argument('--family')
    parser.add_argument('--limit', type=int)
    args = parser.parse_args()
    load_thread_set()
    if args.command == 'jobs':
        build_jobs()
    elif args.command == 'run':
        run_pass(args.arg, args.parallel, args.family, args.limit)
    elif args.command == 'aggregate':
        aggregate(args.arg)
    elif args.command == 'pick-threads':
        pick_threads()
    else:
        sys.exit('unknown command')



def pick_threads():
    """Thread-scaling subset: every non-settling Regulator row plus settling rows across families."""
    data = json.load(open(WORK / 'jobs.json'))
    jobs = {j['id']: j for j in data['jobs']}
    primary = load_results('primary-*.jsonl')
    stats = {}
    for (job_id, mode), records in primary.items():
        job = jobs[job_id]
        record = records[0]
        if job['engine'] != 'regulator' or 'error' in record or record.get('rejected'):
            continue
        s = summarize(record)
        stats.setdefault(job_id, []).append((mode, s))
    chosen = {}
    for job_id, items in stats.items():
        if any(s['settles'] == 'no' or s['resets'] > 0 for _, s in items):
            chosen[job_id] = 'non-settling'
    def best(predicate, key, reason):
        candidates = [(key(jobs[j], items), j) for j, items in stats.items() if j not in chosen and predicate(jobs[j], items)]
        if candidates:
            chosen[max(candidates)[1]] = reason
    used = lambda job, items: max(s['peak_used'] for _, s in items)
    settled = lambda job, items: all(s['settles'] == 'yes' for _, s in items)
    best(lambda j, i: settled(j, i) and j['family'] == 'bulk' and j['spec'].get('engine') and 'count' == bulk_model(j), used, 'DFA-heavy count')
    best(lambda j, i: settled(j, i) and j['family'] == 'bulk' and bulk_model(j) == 'count-captures', used, 'captures')
    best(lambda j, i: settled(j, i) and j['family'] == 'rebar' and j['spec']['model'] == 'grep-captures', used, 'rebar grep-captures')
    for language in ('re2', 'java', 'trino', 'rust'):
        best(lambda j, i, l=language: settled(j, i) and j['family'] == 'bulk' and j['language'] == l, used, f'bulk {language}')
        best(lambda j, i, l=language: settled(j, i) and j['family'] == 'lifecycle' and j['language'] == l and j['spec']['op'].startswith('reused'), used, f'lifecycle {language}')
    best(lambda j, i: settled(j, i) and j['family'] == 'everyday', used, 'trino-operations')
    best(lambda j, i: settled(j, i) and j['family'] == 'specialized', used, 'specialized')
    best(lambda j, i: settled(j, i) and j['family'] == 'like' and j['spec']['kind'] == 'matches' and j['spec']['param'] == 'WILDCARD_CHAIN', used, 'LIKE wildcard')
    (WORK / 'thread-jobs.txt').write_text('\n'.join(sorted(chosen)) + '\n')
    for job_id, reason in sorted(chosen.items()):
        print(job_id, reason, jobs[job_id]['row_id'], jobs[job_id]['language'])
    print(len(chosen))


_BULK_MODELS = {}


def bulk_model(job):
    if job['family'] != 'bulk':
        return None
    if not _BULK_MODELS:
        for case in json.load(open(BULK / 'manifest.json'))['cases']:
            _BULK_MODELS[case['id']] = case['model']
    return _BULK_MODELS['/'.join(job['row_id'].split('/')[1:-1])]


if __name__ == '__main__':
    main()
