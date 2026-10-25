import csv
import hashlib
import json
import os
import shutil
import signal
import subprocess
import tarfile
import tempfile
import time
import unittest
from pathlib import Path


DIRECTORY = Path(__file__).resolve().parent
UNIT = {name: hashlib.sha256(name.encode()).hexdigest() for name in ('first', 'second', 'third')}

# S3 as the aws CLI calls it from a unit worker: exact-key listings, reads, and
# checksummed conditional writes, with every write logged in order. A call whose
# key or prefix is under FAKE_FAILED_CALLS fails, logged as its key or prefix, and
# a Spot notice arrives during the Nth failed call, where FAKE_NOTICE_DURING_FAILED_CALL
# is N. On a virtual FAKE_CLOCK a failed call hangs until the timeout it runs under
# ends it, or for an hour without one, and is also logged with its start and end.
# A call whose key or prefix is under FAKE_STALLED_CALLS stalls on the virtual
# clock until just before its timeout and then succeeds, logged as its operation,
# key, start, and end. A Spot notice arrives as the first such call starts whose
# operation FAKE_NOTICE_DURING_STALLED_CALL names, or whose key or body it is a
# fragment of, logged as a notice at that start. A rebalance recommendation arrives
# during the first call whose operation FAKE_REBALANCE_DURING_CALL names. Every PUT
# but a heartbeat's must carry the conditional-write and checksum options.
FAKE_AWS = r'''#!/usr/bin/env python3
import base64, hashlib, json, os, sys
from pathlib import Path
root = Path(os.environ['FAKE_S3'])
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
rebalance = Path(os.environ['FAKE_NOTICES']) / 'events-recommendations-rebalance'
if os.environ.get('FAKE_REBALANCE_DURING_CALL') == operation and not rebalance.exists():
    rebalance.write_text('{"noticeTime": "2026-10-24T12:00:00Z"}')
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
        clock.write_text(str(start + float(os.environ.get('FAKE_TIMEOUT_SECONDS', 3600)) - 1))
        log.write(f'{operation} {target} {start} {clock.read_text()}\n')
if any(fragment in target for fragment in json.loads(os.environ.get('FAKE_FAILED_CALLS', '[]'))):
    failures = root / 'failed-calls.log'
    failure = 1 + (len(failures.read_text().splitlines()) if failures.exists() else 0)
    if os.environ.get('FAKE_NOTICE_DURING_FAILED_CALL') == str(failure):
        notice.write_text('{"action": "terminate", "time": "2026-10-24T12:02:00Z"}')
    line = target
    if os.environ.get('FAKE_CLOCK'):
        clock = Path(os.environ['FAKE_CLOCK'])
        start = float(clock.read_text())
        clock.write_text(str(start + float(os.environ.get('FAKE_TIMEOUT_SECONDS', 3600))))
        line += f' {start} {clock.read_text()}'
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
clock.write_text(str(float(clock.read_text()) + float(sys.argv[1])))
'''
# coreutils timeout on the virtual clock: it tells the command when it would be killed.
FAKE_TIMEOUT = r'''#!/usr/bin/env python3
import os, sys
os.environ['FAKE_TIMEOUT_SECONDS'] = sys.argv[1]
os.execvp(sys.argv[2], sys.argv[2:])
'''

# A unit as run-unit.sh measures it on a worker: it stores its calibrations in
# the host evidence, chaining the before it is given, and names them in its table,
# then exits with its scripted status. A unit scripted with a child starts one
# that outlives it unless its process group is stopped.
FAKE_RUN_UNIT = r'''#!/usr/bin/env python3
import hashlib, json, os, subprocess, sys, time
from pathlib import Path
unit_id, protocol, replica, directory = sys.argv[1:]
sequence = os.environ['RE2_CAMPAIGN_LEASE_SEQUENCE']
behavior = json.loads(os.environ['FAKE_UNITS']).get(sequence, {})
notices = Path(os.environ['FAKE_NOTICES'])
child = subprocess.Popen(['sleep', '600']).pid if behavior.get('child') else None
with open(os.environ['FAKE_RUNS'], 'a') as runs:
    runs.write(json.dumps({
        'sequence': int(sequence), 'unit_id': unit_id, 'protocol': protocol, 'replica': replica,
        'replica_environment': os.environ['RE2_CAMPAIGN_REPLICA_ID'], 'pid': os.getpid(), 'child': child,
        'chained': os.environ['BASELINE_UNIT_CALIBRATION_BEFORE'],
        'orders': [os.environ.get(name) for name in ('JONI_COMPARATOR_ORDER', 'REBAR_COMPARATOR_ORDER')],
    }) + '\n')
print(f'measuring lease {sequence}', flush=True)
if behavior.get('notice'):
    (notices / 'spot-instance-action').write_text('{"action": "terminate", "time": "2026-10-24T12:02:00Z"}')
    time.sleep(60)
if behavior.get('rebalance'):
    (notices / 'events-recommendations-rebalance').write_text('{"noticeTime": "2026-10-24T12:00:00Z"}')
# The sleep on PATH, so a unit on the virtual clock takes virtual time.
subprocess.run(['sleep', str(behavior.get('seconds', 0))], check=True)
store = Path(os.environ['BASELINE_UNIT_HOST_EVIDENCE']) / 'calibrations'
def stored(content):
    path = store / (hashlib.sha256(content.encode()).hexdigest() + '.json')
    path.write_text(content)
    return path
before = os.environ['BASELINE_UNIT_CALIBRATION_BEFORE']
before = store / f'{before}.json' if before else stored(f'before {sequence}')
after = stored(f'after {sequence}')
if behavior.get('stale'):
    os.utime(after, (time.time() - 120, time.time() - 120))
Path(directory, 'calibration.tsv').write_text(
    f'phase\traw_result\nbefore\t{before.name}\nafter\t{after.name}\n')
sys.exit(behavior.get('status', 0))
'''


def lease(sequence, unit=None, replica=1, **fields):
    work = {'unit_id': UNIT[unit], 'replica_id': replica, 'phase': 'primary', 'protocol': 'smoke'} if unit else {}
    return {'schema_version': 1, 'campaign_id': 'campaign', 'platform': 'r8i', 'host_session': 'host-1',
            'sequence': sequence, **work, **fields}


class Worker:
    """A unit worker on a host whose S3, metadata service, and units are fakes, optionally on a virtual clock."""

    def __init__(self, root, clock=False):
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
            'JONI_COMPARATOR_ORDER': 'forward', 'REBAR_COMPARATOR_ORDER': 'forward',
        }
        if clock:
            # A virtual second costs no real time, so the worker polls each one.
            self.environment.update({'FAKE_CLOCK': str(root / 'clock'), 'UNIT_WORKER_POLL_SECONDS': '1'})
            (root / 'clock').write_text(str(time.time()))

    def write_lease(self, document):
        path = self.bucket / 'input' / 'leases' / f'{document["sequence"]}.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(document))

    def run(self, units=None, controller=lambda worker: None, **environment):
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
    def test_worker_runs_leases_in_order_and_chains_a_calibration_within_a_minute(self):
        def write_stop_lease_once_awaited(worker):
            if worker.object('heartbeat/4.json').exists() and not (worker.bucket / 'input/leases/4.json').exists():
                worker.write_lease(lease(4, stop=True))

        with tempfile.TemporaryDirectory() as temporary:
            worker = Worker(Path(temporary))
            worker.write_lease(lease(1, 'first', 1))
            worker.write_lease(lease(2, 'second', 2))
            worker.write_lease(lease(3, 'third', 3))
            # The controller writes the stop lease only once the host waits for it.
            status, output = worker.run({2: {'stale': True}}, write_stop_lease_once_awaited, idle_seconds=30)

            self.assertEqual(0, status, output)
            runs = worker.runs()
            self.assertEqual([(1, UNIT['first'], '1'), (2, UNIT['second'], '2'), (3, UNIT['third'], '3')],
                             [(run['sequence'], run['unit_id'], run['replica']) for run in runs])
            self.assertTrue(all(run['replica_environment'] == run['replica'] and run['orders'] == [None, None]
                                for run in runs))
            completions = [worker.json(f'units/{sequence}/completion.json') for sequence in (1, 2, 3)]
            self.assertEqual(['complete'] * 3, [completion['unit_status'] for completion in completions])
            for sequence, completion in enumerate(completions, 1):
                result = worker.object(f'units/{sequence}/result.tar.gz').read_bytes()
                self.assertEqual(hashlib.sha256(result).hexdigest(), completion['result_sha256'])
                self.assertEqual(f'host-1/{sequence}', completion['unit_attempt_id'])
            # The first unit's after is the second's before; the second's after was too old to chain.
            self.assertEqual(['', completions[0]['calibration_after'], ''], [run['chained'] for run in runs])
            self.assertEqual([False, True, False], [completion['calibration_chained'] for completion in completions])
            self.assertEqual(completions[0]['calibration_after'], completions[1]['calibration_before'])
            # The chained unit's evidence names its link, which the host's ledger also records.
            with tarfile.open(worker.object('units/2/result.tar.gz')) as result:
                link = dict(line.split('=', 1) for line in
                            result.extractfile('./unit-chain.txt').read().decode().splitlines())
            with tarfile.open(worker.object('units/3/result.tar.gz')) as result:
                self.assertNotIn('./unit-chain.txt', result.getnames())
            with (worker.host / 'worker-leases.tsv').open() as ledger:
                leases = list(csv.DictReader(ledger, delimiter='\t'))
            self.assertEqual(['host-1/1', 'host-1/2', 'host-1/3'], [row['unit_attempt_id'] for row in leases])
            self.assertEqual(['none', 'host-1/1', 'none'], [row['chained_from'] for row in leases])
            self.assertEqual({'previous_attempt': 'host-1/1', 'calibration': completions[0]['calibration_after'],
                              'previous_environment_sha256': leases[0]['environment_sha256']},
                             {key: link[key] for key in ('previous_attempt', 'calibration',
                                                          'previous_environment_sha256')})
            self.assertLessEqual(int(link['gap_seconds']), 60)
            puts = [key for key in worker.puts() if not key.startswith('results/heartbeat/')]
            self.assertEqual([
                f'results/calibrations/{completions[0]["calibration_before"]}.json',
                f'results/calibrations/{completions[0]["calibration_after"]}.json',
                'results/units/1/result.tar.gz', 'results/units/1/completion.json',
                f'results/calibrations/{completions[1]["calibration_after"]}.json',
                'results/units/2/result.tar.gz', 'results/units/2/completion.json',
                f'results/calibrations/{completions[2]["calibration_before"]}.json',
                f'results/calibrations/{completions[2]["calibration_after"]}.json',
                'results/units/3/result.tar.gz', 'results/units/3/completion.json',
            ], puts)
            self.assertEqual(('stopped', 'stop-lease'),
                             (worker.json('heartbeat/4.json')['state'], worker.json('heartbeat/4.json')['stop_reason']))
            self.assertEqual('stop-lease', worker.stop_reason())

    def test_worker_stops_when_idle_out_of_lifetime_or_asked_to_rebalance(self):
        cases = (
            ('idle', (), {}, {'idle_seconds': 1}, [], None),
            # A failed unit is recorded with its status, and the worker goes on.
            ('idle', ('first',), {1: {'status': 3}}, {'idle_seconds': 1}, [1], None),
            # A unit in flight finishes; no lease starts after the lifetime.
            ('lifetime', ('first', 'second'), {1: {'seconds': 2.5}}, {'lifetime_seconds': 2}, [1], None),
            ('rebalance', ('first', 'second'), {1: {'rebalance': True}}, {}, [1], None),
            # A recommendation that arrives while the lease is read leaves the lease unstarted.
            ('rebalance', ('first',), {}, {}, [], 'get-object'),
        )
        for reason, units, behavior, environment, completed, rebalance_during in cases:
            with self.subTest(reason=reason, behavior=behavior, rebalance_during=rebalance_during), \
                    tempfile.TemporaryDirectory() as temporary:
                worker = Worker(Path(temporary), clock=True)
                for sequence, unit in enumerate(units, 1):
                    worker.write_lease(lease(sequence, unit))
                if rebalance_during:
                    worker.environment['FAKE_REBALANCE_DURING_CALL'] = rebalance_during
                status, output = worker.run(behavior, **environment)

                self.assertEqual(0, status, output)
                self.assertEqual(reason, worker.stop_reason())
                self.assertEqual(completed, [run['sequence'] for run in worker.runs()])
                for sequence in completed:
                    unit_status = behavior.get(sequence, {}).get('status', 0)
                    completion = worker.json(f'units/{sequence}/completion.json')
                    self.assertEqual((unit_status, 'failed' if unit_status else 'complete'),
                                     (completion['exit_status'], completion['unit_status']))
                heartbeat = worker.json(f'heartbeat/{len(completed) + 1}.json')
                self.assertEqual(('stopped', reason), (heartbeat['state'], heartbeat['stop_reason']))

    def test_worker_refuses_a_lease_that_is_not_its_next(self):
        for name, body in (
                ('malformed', '{not json'),
                ('another host', json.dumps(lease(1, 'first', host_session='host-2'))),
                ('another campaign', json.dumps(lease(1, 'first', campaign_id='other'))),
                ('another sequence', json.dumps(lease(2, 'first'))),
                ('no unit', json.dumps(lease(1)))):
            with self.subTest(lease=name), tempfile.TemporaryDirectory() as temporary:
                worker = Worker(Path(temporary))
                path = worker.bucket / 'input' / 'leases' / '1.json'
                path.parent.mkdir(parents=True)
                path.write_text(body)
                status, output = worker.run()

                self.assertEqual(1, status, output)
                self.assertEqual('invalid-lease', worker.stop_reason())
                self.assertEqual([], worker.runs())

    def test_interruption_stops_the_unit_and_uploads_it_without_completing_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            worker = Worker(Path(temporary))
            worker.write_lease(lease(1, 'first', 2))
            worker.write_lease(lease(2, 'second'))
            status, output = worker.run({1: {'notice': True, 'child': True}})

            self.assertEqual(43, status, output)
            [run] = worker.runs()
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
            self.assertFalse(worker.object('units/1/completion.json').exists())
            self.assertFalse(worker.object('units/1/result.tar.gz').exists())
            with tarfile.open(worker.object('events/1-interrupted.tar.gz')) as bundle:
                interruption = json.load(bundle.extractfile('interruption.json'))
                log = bundle.extractfile('lease-1/worker-unit.log').read().decode()
            self.assertEqual(
                (1, UNIT['first'], 2, 'terminate', '2026-10-24T12:02:00Z'),
                (interruption['sequence'], interruption['unit_id'], interruption['replica_id'],
                 interruption['action'], interruption['action_time']))
            self.assertIn('measuring lease 1', log)
            self.assertEqual('interrupted', worker.json('heartbeat/1.json')['state'])
            self.assertEqual('interrupted', worker.stop_reason())

    def test_failed_calibration_upload_or_lease_read_leaves_the_unit_incomplete(self):
        # A failing upload gives up within its 90 seconds, each attempt hanging until its timeout on a virtual
        # clock; a Spot notice during its last attempt interrupts the host, as does one during the twelfth failed
        # lease read in a row, on which the worker gives up.
        for failing, notice, expected_status, expected_reason in (
                ('results/calibrations/', 0, 1, 'upload-failure'),
                ('results/calibrations/', 2, 43, 'interrupted'),
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
                self.assertFalse(worker.object('units/1/completion.json').exists())
                self.assertEqual(bool(notice), worker.object('events/1-interrupted.tar.gz').exists())
                attempts = [[float(value) for value in line.split()[1:]]
                            for line in (worker.s3 / 'failed-calls.log').read_text().splitlines()]
                if expected_reason == 'upload-failure':
                    self.assertEqual(2, len(attempts))
                    self.assertLessEqual(attempts[-1][1] - attempts[0][0], 90)
                elif expected_reason == 'lease-unreadable':
                    self.assertEqual(12, len(attempts))

    def test_spot_notice_during_lease_acquisition_or_upload_is_bundled_within_two_minutes(self):
        # Every S3 call stalls until just before its timeout. A notice during the lease's listing or download
        # interrupts the host before its unit starts; one during the heartbeat that starts the unit's upload, or
        # during its completion upload, interrupts it with that unit; and one during a stop's heartbeat makes
        # the stop an interruption.
        for trigger, unit, environment in (
                ('list-objects-v2', None, {}),
                ('get-object', None, {}),
                ('"state": "uploading"', UNIT['first'], {}),
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


if __name__ == '__main__':
    unittest.main()
