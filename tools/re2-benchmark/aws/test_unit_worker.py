import csv
import hashlib
import json
import os
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
from pathlib import Path


DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(DIRECTORY.parent / 'baseline'))
import engine_build
from unit_dispatch import UNIT_COMPLETION_FIELDS
UNIT = {name: hashlib.sha256(name.encode()).hexdigest() for name in ('first', 'second', 'third')}
# A host stop time no test reaches: 2100-01-01.
FAR_STOP_BY = 4102444800

# S3 as the aws CLI calls it from a unit worker: exact-key listings, reads, and
# checksummed conditional writes, with every write logged in order. A call whose
# key or prefix is under FAKE_FAILED_CALLS fails, logged as its key or prefix,
# until FAKE_FAILED_CALL_LIMIT calls have failed, if it is set, and a Spot
# notice arrives during the Nth failed call, where FAKE_NOTICE_DURING_FAILED_CALL
# is N. On a virtual FAKE_CLOCK a failed call hangs until the timeout it runs under
# ends it, or for an hour without one, and is also logged with its start and end.
# A call whose key or prefix is under FAKE_STALLED_CALLS stalls on the virtual
# clock until just before its timeout and then succeeds, logged as its operation,
# key, start, and end. A Spot notice arrives as the first such call starts whose
# operation FAKE_NOTICE_DURING_STALLED_CALL names, or whose key or body it is a
# fragment of, logged as a notice at that start. Every PUT but a heartbeat's must
# carry the conditional-write and checksum options. FAKE_RACING_LEASES holds
# leases, by key, that the controller writes just before the worker's own put of
# that key.
FAKE_AWS = r'''#!/usr/bin/env python3
import base64, hashlib, json, os, sys
from pathlib import Path
root = Path(os.environ['FAKE_S3'])
def set_clock(clock, now):
    # Replace the clock whole, so a concurrent reader never sees it empty.
    temporary = clock.with_name(f'{clock.name}.{os.getpid()}')
    temporary.write_text(str(now))
    os.replace(temporary, clock)
arguments = sys.argv[1:]
options, positional = {}, []
while arguments:
    argument = arguments.pop(0)
    if argument.startswith('--'):
        options[argument] = arguments.pop(0)
    else:
        positional.append(argument)
service, operation = positional[:2]
path = root / options['--bucket'] / options.get('--key', '')
target = options.get('--key', options.get('--prefix', ''))
notice = Path(os.environ['FAKE_NOTICES']) / 'spot-instance-action'
if os.environ.get('FAKE_CLOCK') and any(
        fragment in target for fragment in json.loads(os.environ.get('FAKE_STALLED_CALLS', '[]'))):
    clock = Path(os.environ['FAKE_CLOCK'])
    start = float(clock.read_text())
    trigger = os.environ.get('FAKE_NOTICE_DURING_STALLED_CALL')
    body = Path(options['--body']).read_bytes() if '--body' in options else b''
    with (root / 'stalled-calls.log').open('a') as log:
        if trigger and not notice.exists() and (trigger == operation or trigger in target or trigger.encode() in body):
            notice.write_text('{"action": "terminate", "time": "2026-10-24T12:02:00Z"}')
            log.write(f'notice {target} {start} {start}\n')
        end = start + float(os.environ.get('FAKE_TIMEOUT_SECONDS', 3600)) - 1
        set_clock(clock, end)
        log.write(f'{operation} {target} {start} {end}\n')
failures = root / 'failed-calls.log'
failure = 1 + (len(failures.read_text().splitlines()) if failures.exists() else 0)
if any(fragment in target for fragment in json.loads(os.environ.get('FAKE_FAILED_CALLS', '[]'))) and \
        failure <= int(os.environ.get('FAKE_FAILED_CALL_LIMIT', failure)):
    if os.environ.get('FAKE_NOTICE_DURING_FAILED_CALL') == str(failure):
        notice.write_text('{"action": "terminate", "time": "2026-10-24T12:02:00Z"}')
    line = target
    if os.environ.get('FAKE_CLOCK'):
        clock = Path(os.environ['FAKE_CLOCK'])
        start = float(clock.read_text())
        end = start + float(os.environ.get('FAKE_TIMEOUT_SECONDS', 3600))
        set_clock(clock, end)
        line += f' {start} {end}'
    with failures.open('a') as log:
        log.write(line + '\n')
    if os.environ.get('FAKE_CLOCK'):
        sys.exit(124)
    sys.exit(f'An error occurred (RequestTimeout) when calling the {operation} operation')
if operation == 'list-objects-v2':
    bucket = root / options['--bucket']
    keys = sorted(str(item.relative_to(bucket)) for item in bucket.rglob('*') if item.is_file())
    print(json.dumps({'Contents': [{'Key': key} for key in keys if key.startswith(options['--prefix'])]}))
elif operation == 'get-object':
    if not path.is_file():
        sys.exit('An error occurred (NoSuchKey) when calling the GetObject operation')
    Path(positional[2]).write_bytes(path.read_bytes())
elif operation == 'put-object':
    body = Path(options['--body']).read_bytes()
    racing = json.loads(os.environ.get('FAKE_RACING_LEASES', '{}')).get(options['--key'])
    if racing is not None and not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(racing)
    if '/heartbeat/' not in options['--key'] and (
            options.get('--if-none-match') != '*' or options.get('--checksum-algorithm') != 'SHA256' or
            '--checksum-sha256' not in options):
        sys.exit(f'PUT of {options["--key"]} lacks its conditional-write and checksum options')
    if options.get('--if-none-match') == '*' and path.exists():
        sys.exit('An error occurred (PreconditionFailed) when calling the PutObject operation')
    if '--checksum-sha256' in options and \
            base64.b64encode(hashlib.sha256(body).digest()).decode() != options['--checksum-sha256']:
        sys.exit('An error occurred (BadDigest) when calling the PutObject operation')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(body)
    with (root / 'puts.log').open('a') as log:
        log.write(options['--key'] + '\n')
'''

# Instance metadata: a Spot notice exists once its file does.
FAKE_CURL = r'''#!/usr/bin/env python3
import os, sys
from pathlib import Path
if 'PUT' in sys.argv:
    print('token')
    sys.exit(0)
notice = Path(os.environ['FAKE_NOTICES']) / sys.argv[-1].rsplit('/meta-data/', 1)[1].replace('/', '-')
if not notice.is_file():
    sys.exit(22)
print(notice.read_text())
'''

# A virtual clock: date +%s reads it, sleep advances it, and other dates are real.
FAKE_DATE = r'''#!/usr/bin/env python3
import os, sys
from pathlib import Path
if sys.argv[1:] != ['+%s']:
    os.execv('/bin/date', ['date', *sys.argv[1:]])
print(int(float(Path(os.environ['FAKE_CLOCK']).read_text())))
'''
FAKE_SLEEP = r'''#!/usr/bin/env python3
import os, sys
from pathlib import Path
clock = Path(os.environ['FAKE_CLOCK'])
# Replace the clock whole, so a concurrent reader never sees it empty.
temporary = clock.with_name(f'{clock.name}.{os.getpid()}')
temporary.write_text(str(float(clock.read_text()) + float(sys.argv[1])))
os.replace(temporary, clock)
'''
# coreutils timeout on the virtual clock: it tells the command when it would be killed.
FAKE_TIMEOUT = r'''#!/usr/bin/env python3
import os, sys
os.environ['FAKE_TIMEOUT_SECONDS'] = sys.argv[1]
os.execvp(sys.argv[2], sys.argv[2:])
'''

# wc -c as the worker sizes a unit result: FAKE_RESULT_BYTES stands in for a large archive.
FAKE_WC = r'''#!/usr/bin/env python3
import os
print(os.environ['FAKE_RESULT_BYTES'])
'''

# A unit's measured group as run-unit.sh measures it on a worker: it exits with
# the status scripted for its lease, or for its lease and group as "<seq>/<group>".
# A group scripted with a child starts one that outlives it unless its process
# group is stopped.
FAKE_RUN_UNIT = r'''#!/usr/bin/env python3
import json, os, subprocess, sys, time
from pathlib import Path
unit_id, protocol, replica, group, directory = sys.argv[1:]
sequence = os.environ['RE2_CAMPAIGN_LEASE_SEQUENCE']
units = json.loads(os.environ['FAKE_UNITS'])
behavior = units.get(f'{sequence}/{group}', units.get(sequence, {}))
notices = Path(os.environ['FAKE_NOTICES'])
child = subprocess.Popen(['sleep', '600']).pid if behavior.get('child') else None
with open(os.environ['FAKE_RUNS'], 'a') as runs:
    runs.write(json.dumps({
        'sequence': int(sequence), 'unit_id': unit_id, 'protocol': protocol, 'replica': replica, 'group': group,
        'replica_environment': os.environ['RE2_CAMPAIGN_REPLICA_ID'], 'pid': os.getpid(), 'child': child,
        'order': os.environ.get('JONI_COMPARATOR_ORDER'),
    }) + '\n')
print(f'measuring lease {sequence} group {group}', flush=True)
if behavior.get('notice'):
    (notices / 'spot-instance-action').write_text('{"action": "terminate", "time": "2026-10-24T12:02:00Z"}')
    time.sleep(60)
if behavior.get('rebalance'):
    (notices / 'events-recommendations-rebalance').write_text('{"noticeTime": "2026-10-24T12:00:00Z"}')
# The sleep on PATH, so a unit on the virtual clock takes virtual time.
subprocess.run(['sleep', str(behavior.get('seconds', 0))], check=True)
sys.exit(behavior.get('status', 0))
'''


def lease(sequence, unit=None, replica=1, groups=('1',), **fields):
    work = {'unit_id': UNIT[unit], 'replica_id': replica, 'phase': 'primary', 'protocol': 'smoke'} if unit else {}
    if unit or 'unit_id' in fields:
        work.update(groups=list(groups), stop_by=FAR_STOP_BY)
    return {'schema_version': 2, 'campaign_id': 'campaign', 'platform': 'r8i', 'host_session': 'host-1',
            'sequence': sequence, **work, **fields}


class Worker:
    """A unit worker on a host whose S3, metadata service, and units are fakes, optionally on a virtual clock."""

    def __init__(self, root, clock=False, result_bytes=None):
        self.root = root
        tools = root / 'tools'
        (tools / 'aws').mkdir(parents=True)
        (tools / 'baseline').mkdir()
        shutil.copy(DIRECTORY / 'run-unit-worker.sh', tools / 'aws' / 'run-unit-worker.sh')
        # The worker's environment digest is acceptance's own, from the real baseline modules.
        (tools / 'baseline' / 'unit_session.py').symlink_to(DIRECTORY.parent / 'baseline' / 'unit_session.py')
        self.script = tools / 'aws' / 'run-unit-worker.sh'
        binary = root / 'bin'
        binary.mkdir()
        fakes = [(binary / 'aws', FAKE_AWS), (binary / 'curl', FAKE_CURL),
                 (tools / 'baseline' / 'run-unit.sh', FAKE_RUN_UNIT)]
        if clock:
            fakes += [(binary / 'date', FAKE_DATE), (binary / 'sleep', FAKE_SLEEP),
                      (binary / 'timeout', FAKE_TIMEOUT)]
        if result_bytes is not None:
            fakes.append((binary / 'wc', FAKE_WC))
        for path, source in fakes:
            path.write_text(source)
            path.chmod(0o755)
        self.host = root / 'host'
        self.host.mkdir()
        (self.host / 'environment-manifest.txt').write_text('instance_id=i-1\n')
        (self.host / 'environment.txt').write_text('instance_id=i-1\n')
        self.s3 = root / 's3'
        self.bucket = self.s3 / 'bucket'
        self.notices = root / 'notices'
        self.notices.mkdir()
        # The host's stop time as its user data records it, and as leases move it.
        self.stop_by = root / 'stop-by'
        self.stop_by.write_text(f'{FAR_STOP_BY}\n')
        self.runs_log = root / 'runs.jsonl'
        self.environment = {
            **os.environ,
            'PATH': f'{binary}{os.pathsep}{os.environ["PATH"]}',
            'FAKE_S3': str(self.s3), 'FAKE_NOTICES': str(self.notices), 'FAKE_RUNS': str(self.runs_log),
            'FAKE_UNITS': '{}',
            'RE2_CAMPAIGN_ID': 'campaign', 'RE2_CAMPAIGN_PLATFORM': 'r8i', 'RE2_CAMPAIGN_HOST_SESSION': 'host-1',
            'BASELINE_PROTOCOL': 'smoke', 'BASELINE_REGION': 'us-west-2',
            'UNIT_WORKER_BUCKET': 'bucket', 'UNIT_WORKER_LEASE_PREFIX': 'input/leases',
            'UNIT_WORKER_RESULT_PREFIX': 'results', 'UNIT_WORKER_POLL_SECONDS': '0.05',
            'UNIT_WORKER_STOP_BY_FILE': str(self.stop_by),
            'JONI_COMPARATOR_ORDER': 'forward',
            # macOS tar would add AppleDouble files to the bundles a Linux worker writes.
            'COPYFILE_DISABLE': '1',
        }
        if clock:
            # A virtual second costs no real time, so the worker polls each one.
            self.environment.update({'FAKE_CLOCK': str(root / 'clock'), 'UNIT_WORKER_POLL_SECONDS': '1'})
            (root / 'clock').write_text(str(time.time()))
        if result_bytes is not None:
            self.environment['FAKE_RESULT_BYTES'] = str(result_bytes)

    def write_lease(self, document):
        path = self.bucket / 'input' / 'leases' / f'{document["sequence"]}.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(document))

    def run(self, units=None, controller=lambda worker: None, **environment):
        """units scripts each lease, by sequence, or each group, by "<sequence>/<group>"."""
        self.environment['FAKE_UNITS'] = json.dumps({str(key): value for key, value in (units or {}).items()})
        self.environment.update({f'UNIT_WORKER_{name.upper()}': str(value) for name, value in environment.items()})
        process = subprocess.Popen(
            ['bash', str(self.script), str(self.host), str(self.root / 'units')], env=self.environment,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
        try:
            deadline = time.monotonic() + 60
            while process.poll() is None and time.monotonic() < deadline:
                controller(self)
                time.sleep(0.05)
            output = process.communicate(timeout=10)[0]
        finally:
            # A worker that outlived its deadline, or a failed controller callback, leaves nothing running.
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.communicate()
        return process.returncode, output

    def runs(self):
        if not self.runs_log.exists():
            return []
        return [json.loads(line) for line in self.runs_log.read_text().splitlines()]

    def object(self, key):
        return self.bucket / 'results' / key

    def json(self, key):
        return json.loads(self.object(key).read_text())

    def puts(self):
        return (self.s3 / 'puts.log').read_text().splitlines()

    def stop_reason(self):
        return dict(line.split('=', 1) for line in (self.host / 'worker-session.txt').read_text().splitlines())[
            'stop_reason']


class TestUnitWorker(unittest.TestCase):
    def test_worker_runs_leases_and_their_groups_in_order(self):
        def write_stop_lease_once_awaited(worker):
            if (worker.object('units/3/groups/1/completion.json').exists() and
                    not (worker.bucket / 'input/leases/4.json').exists()):
                worker.write_lease(lease(4, stop=True))

        with tempfile.TemporaryDirectory() as temporary:
            worker = Worker(Path(temporary))
            worker.write_lease(lease(1, 'first', 1))
            # A mirrored replica runs its unit's groups in reverse.
            worker.write_lease(lease(2, 'second', 2, groups=('2', '1')))
            worker.write_lease(lease(3, 'third', 3))
            # The controller writes the stop lease only once the host waits for it.
            status, output = worker.run({}, write_stop_lease_once_awaited, idle_seconds=30)

            self.assertEqual(0, status, output)
            runs = worker.runs()
            groups = [(1, '1'), (2, '2'), (2, '1'), (3, '1')]
            self.assertEqual(
                [(1, UNIT['first'], '1', '1'), (2, UNIT['second'], '2', '2'), (2, UNIT['second'], '2', '1'),
                 (3, UNIT['third'], '3', '1')],
                [(run['sequence'], run['unit_id'], run['replica'], run['group']) for run in runs])
            self.assertTrue(all(run['replica_environment'] == run['replica'] and run['order'] is None
                                for run in runs))
            for sequence, group in groups:
                prefix = f'units/{sequence}/groups/{group}'
                completion = worker.json(f'{prefix}/completion.json')
                self.assertLessEqual(set(UNIT_COMPLETION_FIELDS), set(completion))
                result = worker.object(f'{prefix}/result.tar.gz').read_bytes()
                self.assertEqual(
                    ('complete', f'host-1/{sequence}', group, hashlib.sha256(result).hexdigest(), len(result), 1),
                    (completion['unit_status'], completion['unit_attempt_id'], completion['group_id'],
                     completion['result_sha256'], completion['result_bytes'], completion['upload_attempts']))
                # The marker reports how long the group's result took to pack and upload.
                self.assertTrue(all(type(completion[field]) is float and completion[field] >= 0
                                    for field in ('pack_seconds', 'upload_seconds')), completion)
            with (worker.host / 'worker-leases.tsv').open() as ledger:
                leases = list(csv.DictReader(ledger, delimiter='\t'))
            self.assertEqual([(f'host-1/{sequence}', group) for sequence, group in groups],
                             [(row['unit_attempt_id'], row['group_id']) for row in leases])
            self.assertEqual(['complete'] * 4, [row['outcome'] for row in leases])
            # Each group's result and marker land as soon as the group finishes. The worker heartbeats only as each
            # lease starts, as each group completes, and as it stops.
            expected = []
            for sequence in (1, 2, 3):
                expected.append(f'results/heartbeat/{sequence}.json')
                for group in (group for leased, group in groups if leased == sequence):
                    expected += [f'results/units/{sequence}/groups/{group}/result.tar.gz',
                                 f'results/units/{sequence}/groups/{group}/completion.json',
                                 f'results/heartbeat/{sequence}.json']
            self.assertEqual([*expected, 'results/heartbeat/4.json'], worker.puts())
            self.assertEqual(('stopped', 'stop-lease'),
                             (worker.json('heartbeat/4.json')['state'], worker.json('heartbeat/4.json')['stop_reason']))
            self.assertEqual('stop-lease', worker.stop_reason())
            # The host's startup timeline ends once, when the worker begins its first unit.
            steps = [line.split('\t') for line in (worker.host / 'startup-timeline.tsv').read_text().splitlines()]
            self.assertEqual(['worker'], [step[0] for step in steps])
            self.assertLessEqual(float(steps[0][1]), float(steps[0][2]))

    def test_worker_stops_when_idle_or_out_of_lifetime(self):
        cases = (
            ('idle', (), ('1',), {}, {'idle_seconds': 1}, []),
            # A failed group is recorded with its status and ends its lease, and the worker goes on.
            ('idle', ('first',), ('1', '2'), {'1/1': {'status': 3}}, {'idle_seconds': 1}, [1]),
            # A unit in flight finishes; no lease starts after the lifetime.
            ('lifetime', ('first', 'second'), ('1',), {1: {'seconds': 2.5}}, {'lifetime_seconds': 2}, [1]),
            # A rebalance recommendation is only a hint; leasing goes on until an instance-action notice.
            ('idle', ('first', 'second'), ('1',), {1: {'rebalance': True}}, {'idle_seconds': 1}, [1, 2]),
        )
        for reason, units, groups, behavior, environment, completed in cases:
            with self.subTest(reason=reason, behavior=behavior), tempfile.TemporaryDirectory() as temporary:
                worker = Worker(Path(temporary), clock=True)
                for sequence, unit in enumerate(units, 1):
                    worker.write_lease(lease(sequence, unit, groups=groups))
                status, output = worker.run(behavior, **environment)

                self.assertEqual(0, status, output)
                self.assertEqual(reason, worker.stop_reason())
                self.assertEqual(completed, [run['sequence'] for run in worker.runs()])
                for sequence in completed:
                    unit_status = behavior.get(sequence, behavior.get(f'{sequence}/1', {})).get('status', 0)
                    completion = worker.json(f'units/{sequence}/groups/1/completion.json')
                    self.assertEqual((unit_status, 'failed' if unit_status else 'complete'),
                                     (completion['exit_status'], completion['unit_status']))
                heartbeat = worker.json(f'heartbeat/{len(completed) + 1}.json')
                self.assertEqual(('stopped', reason), (heartbeat['state'], heartbeat['stop_reason']))
                if reason == 'idle':
                    # Before it stops idle, the worker claims its next lease as its own stop.
                    claim = worker.bucket / 'input' / 'leases' / f'{len(units) + 1}.json'
                    self.assertEqual(lease(len(units) + 1, stop=True, by='worker'), json.loads(claim.read_text()))

    def test_idle_claim_that_loses_to_the_controllers_lease_runs_that_lease(self):
        # The controller writes lease 1 just as the idle worker claims it, so the claim loses, and the worker runs
        # the lease; its claim of lease 2 then wins.
        with tempfile.TemporaryDirectory() as temporary:
            worker = Worker(Path(temporary), clock=True)
            worker.environment['FAKE_RACING_LEASES'] = json.dumps(
                {'input/leases/1.json': json.dumps(lease(1, 'first'))})
            status, output = worker.run(idle_seconds=1)

            self.assertEqual(0, status, output)
            self.assertEqual(('idle', [(1, UNIT['first'])]),
                             (worker.stop_reason(), [(run['sequence'], run['unit_id']) for run in worker.runs()]))
            self.assertEqual(lease(2, stop=True, by='worker'),
                             json.loads((worker.bucket / 'input' / 'leases' / '2.json').read_text()))

    def test_leases_move_the_hosts_stop_time_only_later(self):
        # The host powers off at its stop time, so a lease that names an earlier stop time leaves it unchanged.
        with tempfile.TemporaryDirectory() as temporary:
            worker = Worker(Path(temporary), clock=True)
            start = int(float((worker.root / 'clock').read_text()))
            worker.stop_by.write_text(f'{start + 30}\n')
            worker.write_lease(lease(1, 'first', stop_by=start + 300))
            worker.write_lease(lease(2, 'second', stop_by=start + 200))
            status, output = worker.run({1: {'seconds': 20}, 2: {'seconds': 20}}, idle_seconds=10)

            self.assertEqual(0, status, output)
            self.assertEqual(('idle', [1, 2]), (worker.stop_reason(), [run['sequence'] for run in worker.runs()]))
            self.assertEqual(f'{start + 300}\n', worker.stop_by.read_text())

    def test_worker_runs_the_build_phases_engine_build_lease(self):
        # The controller leases a platform's engine build as the build phase's one unit, named rather than hashed.
        with tempfile.TemporaryDirectory() as temporary:
            worker = Worker(Path(temporary))
            worker.environment['BASELINE_PROTOCOL'] = 'qualification'
            worker.write_lease(lease(1, unit_id=engine_build.UNIT_ID, replica_id=1, phase='build',
                                     protocol='qualification'))
            status, output = worker.run(idle_seconds=1)

            self.assertEqual(0, status, output)
            self.assertEqual([(engine_build.UNIT_ID, '1')], [(run['unit_id'], run['replica']) for run in worker.runs()])
            completion = worker.json('units/1/groups/1/completion.json')
            self.assertEqual(('build', engine_build.UNIT_ID, 'complete'),
                             (completion['phase'], completion['unit_id'], completion['unit_status']))

    def test_worker_refuses_a_lease_that_is_not_its_next(self):
        for name, body in (
                ('malformed', '{not json'),
                ('another host', json.dumps(lease(1, 'first', host_session='host-2'))),
                ('another campaign', json.dumps(lease(1, 'first', campaign_id='other'))),
                ('another sequence', json.dumps(lease(2, 'first'))),
                ('no unit', json.dumps(lease(1))),
                ('no stop time', json.dumps(lease(1, 'first', stop_by=None))),
                ('repeated group', json.dumps(lease(1, 'first', groups=('1', '1')))),
                ('two groups in one name', json.dumps(lease(1, 'first', groups=('1,2',)))),
                ('measuring engine build', json.dumps(lease(1, 'first', unit_id=engine_build.UNIT_ID))),
                ('build of a measuring unit', json.dumps(lease(1, 'first', phase='build'))),
                ('engine build at replica 2',
                 json.dumps(lease(1, unit_id=engine_build.UNIT_ID, replica_id=2, phase='build', protocol='smoke')))):
            with self.subTest(lease=name), tempfile.TemporaryDirectory() as temporary:
                worker = Worker(Path(temporary))
                path = worker.bucket / 'input' / 'leases' / '1.json'
                path.parent.mkdir(parents=True)
                path.write_text(body)
                status, output = worker.run()

                self.assertEqual(1, status, output)
                self.assertEqual('invalid-lease', worker.stop_reason())
                self.assertEqual([], worker.runs())

    def test_interruption_keeps_finished_groups_and_bundles_only_the_running_one(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = Worker(Path(temporary))
            worker.write_lease(lease(1, 'first', 2, groups=('1', '2', '3')))
            worker.write_lease(lease(2, 'second'))
            status, output = worker.run({'1/2': {'notice': True, 'child': True}})

            self.assertEqual(43, status, output)
            finished, run = worker.runs()
            self.assertEqual(('1', '2'), (finished['group'], run['group']))
            with self.assertRaises(ProcessLookupError):
                os.kill(run['pid'], 0)
            # The unit's whole process group stops; its orphaned child is reaped soon after.
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                try:
                    os.kill(run['child'], 0)
                except ProcessLookupError:
                    break
                time.sleep(0.05)
            else:
                os.kill(run['child'], signal.SIGKILL)
                self.fail('the unit\'s child outlived the interruption')
            self.assertEqual(['units/1/groups/1/result.tar.gz', 'units/1/groups/1/completion.json'],
                             [key.removeprefix('results/') for key in worker.puts() if '/units/' in key])
            with tarfile.open(worker.object('events/1-interrupted.tar.gz')) as bundle:
                members = [member.name for member in bundle.getmembers() if member.isfile()]
                interruption = json.load(bundle.extractfile('interruption.json'))
                log = bundle.extractfile('lease-1/group-2/worker-unit.log').read().decode()
            # The finished group is already uploaded; the bundle carries only the running group's log.
            self.assertEqual(['interruption.json', 'lease-1/group-2/worker-unit.log'], members)
            self.assertEqual(
                (1, UNIT['first'], 2, ['1'], '2', 'terminate', '2026-10-24T12:02:00Z'),
                (interruption['sequence'], interruption['unit_id'], interruption['replica_id'],
                 interruption['finished_groups'], interruption['running_group'], interruption['action'],
                 interruption['action_time']))
            self.assertIn('measuring lease 1 group 2', log)
            self.assertEqual('interrupted', worker.json('heartbeat/1.json')['state'])
            self.assertEqual('interrupted', worker.stop_reason())

    def test_failed_result_upload_or_lease_read_leaves_the_unit_incomplete(self):
        # A failing upload gives up within its 90 seconds, each attempt hanging until its timeout on a virtual
        # clock; a Spot notice during its last attempt interrupts the host, as does one during the twelfth failed
        # lease read in a row, on which the worker gives up.
        for failing, notice, expected_status, expected_reason in (
                ('results/units/', 0, 1, 'upload-failure'),
                ('results/units/', 2, 43, 'interrupted'),
                ('input/leases/', 0, 1, 'lease-unreadable'),
                ('input/leases/', 12, 43, 'interrupted')):
            with self.subTest(failing=failing, notice=notice), tempfile.TemporaryDirectory() as temporary:
                worker = Worker(Path(temporary), clock=True)
                worker.write_lease(lease(1, 'first'))
                worker.environment.update({'FAKE_FAILED_CALLS': json.dumps([failing]),
                                           'FAKE_NOTICE_DURING_FAILED_CALL': str(notice)})
                status, output = worker.run(upload_seconds=90)

                self.assertEqual(expected_status, status, output)
                self.assertEqual(expected_reason, worker.stop_reason())
                self.assertFalse(worker.object('units/1/groups/1/completion.json').exists())
                self.assertEqual(bool(notice), worker.object('events/1-interrupted.tar.gz').exists())
                attempts = [[float(value) for value in line.split()[1:]]
                            for line in (worker.s3 / 'failed-calls.log').read_text().splitlines()]
                if expected_reason == 'upload-failure':
                    self.assertEqual(2, len(attempts))
                    self.assertLessEqual(attempts[-1][1] - attempts[0][0], 90)
                elif expected_reason == 'lease-unreadable':
                    self.assertEqual(12, len(attempts))

    def test_failed_heartbeat_is_retried(self):
        # The heartbeat as the lease starts fails once, hanging until its timeout on a virtual clock, and its retry
        # lands before the group's result.
        with tempfile.TemporaryDirectory() as temporary:
            worker = Worker(Path(temporary), clock=True)
            worker.write_lease(lease(1, 'first'))
            worker.write_lease(lease(2, stop=True))
            worker.environment.update({'FAKE_FAILED_CALLS': json.dumps(['results/heartbeat/']),
                                       'FAKE_FAILED_CALL_LIMIT': '1'})
            status, output = worker.run()

            self.assertEqual(0, status, output)
            self.assertEqual(1, len((worker.s3 / 'failed-calls.log').read_text().splitlines()))
            self.assertEqual(['results/heartbeat/1.json', 'results/units/1/groups/1/result.tar.gz'], worker.puts()[:2])

    def test_spot_notice_during_lease_acquisition_or_upload_is_bundled_within_two_minutes(self):
        # Every S3 call stalls until just before its timeout. A notice during the lease's listing or download
        # interrupts the host before its unit starts; one during the unit's result or completion upload
        # interrupts it with that unit; and one during a stop's heartbeat makes the stop an interruption.
        for trigger, unit, environment in (
                ('list-objects-v2', None, {}),
                ('get-object', None, {}),
                ('result.tar.gz', UNIT['first'], {}),
                ('completion.json', UNIT['first'], {}),
                ('"state": "stopped"', None, {'lifetime_seconds': 1})):
            with self.subTest(trigger=trigger), tempfile.TemporaryDirectory() as temporary:
                worker = Worker(Path(temporary), clock=True)
                worker.write_lease(lease(1, 'first'))
                worker.environment.update({
                    'FAKE_STALLED_CALLS': json.dumps(['input/leases/', 'results/']),
                    'FAKE_NOTICE_DURING_STALLED_CALL': trigger})
                status, output = worker.run(**environment)

                self.assertEqual(43, status, output)
                self.assertEqual('interrupted', worker.stop_reason())
                self.assertEqual([unit] if unit else [], [run['unit_id'] for run in worker.runs()])
                calls = [line.split() for line in (worker.s3 / 'stalled-calls.log').read_text().splitlines()]
                notice = next(float(start) for called, _, start, _ in calls if called == 'notice')
                uploaded = next(float(end) for _, key, _, end in calls if key == 'results/events/1-interrupted.tar.gz')
                self.assertLessEqual(uploaded - notice, 120)
                with tarfile.open(worker.object('events/1-interrupted.tar.gz')) as bundle:
                    self.assertEqual(unit, json.load(bundle.extractfile('interruption.json'))['unit_id'])

    def test_spot_notice_during_a_large_result_upload_still_uploads_the_interruption_bundle(self):
        # A 224 MiB result stretches its upload attempts past the whole interruption budget; the notice that
        # arrives during it is acted on when the attempt ends, and the bundle gets attempts of its own.
        with tempfile.TemporaryDirectory() as temporary:
            worker = Worker(Path(temporary), clock=True, result_bytes=224 * 1024 * 1024)
            worker.write_lease(lease(1, 'first'))
            worker.environment.update({'FAKE_STALLED_CALLS': json.dumps(['results/']),
                                       'FAKE_NOTICE_DURING_STALLED_CALL': 'result.tar.gz'})
            status, output = worker.run()

            self.assertEqual(43, status, output)
            self.assertEqual('interrupted', worker.stop_reason())
            self.assertTrue(worker.object('units/1/groups/1/result.tar.gz').exists())
            self.assertFalse(worker.object('units/1/groups/1/completion.json').exists())
            with tarfile.open(worker.object('events/1-interrupted.tar.gz')) as bundle:
                self.assertEqual(UNIT['first'], json.load(bundle.extractfile('interruption.json'))['unit_id'])
            # The result's attempt ran past an unstretched one; the interruption bundle's did not.
            calls = [line.split() for line in (worker.s3 / 'stalled-calls.log').read_text().splitlines()]
            seconds = {key: float(end) - float(start) for called, key, start, end in calls if called != 'notice'}
            unstretched = 5 + 30
            self.assertGreater(seconds['results/units/1/groups/1/result.tar.gz'], unstretched)
            self.assertLess(seconds['results/events/1-interrupted.tar.gz'], unstretched)


if __name__ == '__main__':
    unittest.main()
