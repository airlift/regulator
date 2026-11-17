"""Run census units in parallel, untimed, each in its own JVM with the census probe attached.
Usage: python3 -I run_census.py <checkout> <build-dir> <units.tsv> <parallelism> <census-dir>
<checkout> holds the built target/classes and target/test-classes the probe instruments; <build-dir> holds
censusprobe.jar, drivers/ and classpath.txt. A unit whose output already holds a result is skipped, so a rerun
resumes an interrupted census."""
import sys, subprocess
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
SRC, BUILD, UNITS = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), Path(sys.argv[3])
par, out = int(sys.argv[4]), Path(sys.argv[5])
probe = BUILD / 'censusprobe.jar'
cp = ':'.join([str(BUILD / 'drivers'), str(SRC / 'target/classes'), str(SRC / 'target/test-classes'), str(probe),
               (BUILD / 'classpath.txt').read_text().strip()])
out.mkdir(parents=True, exist_ok=True)
units = [l.rstrip('\n').split('\t') for l in open(UNITS) if l.strip()]
TIMEOUT = 2400

def name(key):
    return key.replace('/', '~').replace('|', '@').replace(':', '_')

def run(u):
    key, mode, runner, *args = u
    path = out / (name(key) + '.txt')
    if path.exists() and path.stat().st_size > 0 and 'P\tresult' in path.read_text(errors='replace'):
        return key, 'cached'
    cmd = ['java', '-javaagent:' + str(probe), '--add-modules=jdk.incubator.vector', '-Xmx6g', '-XX:+UseG1GC',
           '-Dio.airlift.regulator.dfa.route-counts=true']
    if mode == 'native':
        cmd.append('--enable-native-access=ALL-UNNAMED')
    cmd += ['-cp', cp]
    if runner == 'jmh':
        cmd += ['io.airlift.regulator.CensusRun'] + args
    else:
        cmd += ['io.airlift.regulator.LikeCensus'] + args[:3] + [str(SRC)] + args[3:]
    try:
        p = subprocess.run(cmd, cwd=SRC, capture_output=True, timeout=TIMEOUT)
        path.write_bytes(p.stdout + (b'' if p.returncode == 0 else b'P\texit\t%d\n' % p.returncode))
        (out / (name(key) + '.err')).write_bytes(p.stderr[-20000:])
        return key, p.returncode
    except subprocess.TimeoutExpired as e:
        path.write_bytes((e.stdout or b'') + b'P\ttimeout\t%d\n' % TIMEOUT)
        return key, 'timeout'

done = failed = 0
with ThreadPoolExecutor(par) as ex:
    for key, rc in ex.map(run, units):
        done += 1
        if rc not in (0, 'cached'):
            failed += 1
            print('FAIL', key, rc, flush=True)
        if done % 200 == 0:
            print('done', done, '/', len(units), flush=True)
print('finished', done, 'units,', failed, 'failed')
