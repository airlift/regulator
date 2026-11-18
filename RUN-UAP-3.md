# RUN-UAP-3: uap-core round UAP-3 (r8i loop-shape D-cell for F1 and F3, PR #279)

Runner: Mac thread (AWS dev account). Spec: AWS-JOBS-UAP-3.md (unchanged; predictions untouched). Brief:
`~/AgentScratch/regulator/user/dain/uap-core/aws-uap3/RUNNER-BRIEF-UAP-3.md`. Kit:
`~/AgentScratch/regulator/user/dain/uap-core/aws-uap3/kit-uap3/`. Project tag `T=regulator-uap-core-uap3`, a fresh key
pair and an SSH-only security group (this Mac's /32) in us-east-2. Guard `uap3-guard.sh`, PID in `kit-uap3/guard.pid`, cap
$0.15, deadline 2026-11-18 03:15 PST (never reached); on-host `shutdown -h` timer 11:34Z (03:34 PST), read back. UAP-2,
EG-11 and BC-2 were live in the same account; only this tag's resources were touched.

## Kit

- Scripts copied (copy only) from `kit-uap2/`: env, setup, launch, start, sync, run-jobs, pull, ips, rsh, ssh-masked,
  waitev, guard, cost, teardown, sweep, bundle, host-prep (UAP-2's profiler setup: `perf_event_paranoid=-1`,
  `kptr_restrict=0`, perf check, hsdis install and `-XX:+PrintAssembly` check), s3-bucket, platforms.tsv, pins, the deps
  bundle and leg A's bundle (revision.txt `80bccc124…`, the same SHA). Changed: tag, kit path, guard name, and:
  - `start.sh` ships hsdis to hosts whose job labels start `D<n>/` (UAP-2 matched only `D/`);
  - **new `dcell.sh`**: the spec's two commands verbatim (perfasm `intelSyntax=true;events=cycles,instructions;top=3;
    saveLog=true;saveLogToFile=$OUT.perfasm`; perfnorm with `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation
    -XX:LogFile=$OUT.xml -XX:+PrintAssembly -XX:PrintAssemblyOptions=intel` and `perfnorm:events=cycles,instructions,r0403`),
    `-p patternCount=16 -p sourceLength=32768 -t 1 -f 1`, the template JVM options, per-fork `timeout -k 30 900`. The
    whole JMH console goes to `$OUT.console.zst` (PrintAssembly writes there in perfnorm forks), `.perfasm` and `.xml`
    are zstd-compressed;
  - **new `dcell3.py`** (the reader; RESULTS-UAP-3.md says how it picks loops and reads the compile log).
- hsdis: `rust-clear-losses-2/kit/hsdis/hsdis-amd64.so`, sha256 db5274a5… (UAP-2's). Host check: 580 instruction
  lines from `-XX:+PrintAssembly -version`; perf counted cycles and instructions; `r0403` counted.

## Legs and jar paths

Each leg is a `git archive` export of its full SHA (no patch), offline `test-compile` on the Mac (build only, never
timed), shipped as classes + test-classes. After `git fetch`, every SHA is on its pushed branch and contains main
`bdf26dc33`; `git diff c46ea3278 94cf51bf6 -- src/main` is empty; no leg changes pom.xml. The bundles sit at
equal-length paths. The kit's `bundles/` layout serves as the brief's `legs/` (as in UAP-2):

| Leg | SHA | Branch | Path on host | files |
|---|---|---|---|---|
| A | `80bccc124bfcdfa6e8cc8b5ff2c880b9cd1d4e75` | `user/dain/uap-core` | `/home/ec2-user/bundles/a/` | 2402 |
| C′ | `94cf51bf6abebf14c71616a11e29b9bf96d330ac` | `user/dain/uap-core-dense` | `/home/ec2-user/bundles/c/` | 2414 |
| F1 | `8883e814455c9d917200eb9f74b22562f31d69ec` | `user/dain/uap-core-f1` | `/home/ec2-user/bundles/1/` | 2414 |
| F3 | `f5042eece11c74c5503401a6007d782b961fef1a` | `user/dain/uap-core-f3` | `/home/ec2-user/bundles/3/` | 2414 |

- The match-dense row exists at C′, F1 and F3 (not at A, as expected). host-prep's uap-core pin check passed for all four
  bundles (16/16 files). No workloads outside the jar; no corpus checksum mismatch.

## Host

| Host | Platform | AZ | Instance | Spot $/h | CPU |
|---|---|---|---|---|---|
| r8i-x1 | r8i.large | us-east-2c (use2-az3) | i-0f1d09ebc5248b0e8 | 0.0268 | Intel(R) Xeon(R) 6975P-C, 2 vCPU |

- Placement score 3 at 00:5x PST. No other runner had r8i in us-east-2c (BC-2's r8i were in us-east-2a, EG-11's in
  ap-northeast-2b and ap-south-1). us-east-2 Spot vCPU quota 256.
- Temurin 25.0.4+7 (sha256 checked by the user data), perf 6.1.176, launched 00:55 PST, runner started 00:56:38 PST,
  guard started 00:56:35 PST.

## Order

36 forks on the one host, legs interleaved fork by fork in the order A, C′, F1, F3:
- D1 rounds perfasm, perfnorm, perfasm, perfnorm, perfasm, perfnorm, perfasm (the fourth perfasm last, per drop step 2);
- then D2: C′, F1 × perfasm ×2, then perfnorm ×2 (drop step 1 last).

Each fork took 24–28 s. All 36 ran by 01:17 PST; nothing was dropped, nothing timed out.

## Deviations from AWS-JOBS-UAP-3.md

1. Jar paths: `bundles/{a,c,1,3}/` (equal length) instead of the brief's `legs/…`, so the copied kit scripts stay unchanged.
2. The hot nmethod in perfnorm forks is inferred (no samples): the last standard tier-4 `searchMany` compile. Its loop is
   the candidate whose source frames match the same leg's perfasm hot loop. The floor check passed in all 18 perfnorm
   forks, with 0.2–0.7% margin on D1, so each disassembled loop is the one that ran.
3. Per-exit instructions and cycles on D2 are estimates from per-op counts and perfasm shares (RESULTS-UAP-3.md says
   how); exits per byte (0.242) is the spec's dain-1 count, not measured here.
4. While a fork was in flight, the guard's pulls copied partial files (`.console`, `.json.tmp`, raw `.xml`); one pull logged
   "pull failed" at 09:12:12Z (files renamed by zstd mid-transfer). The final pull got every finished file. The 12 stale
   partials were moved to `kit-uap3/stale-partials/` and left out of the results and the archive.

## Commands

```sh
cd ~/AgentScratch/regulator/user/dain/uap-core/aws-uap3/kit-uap3
./bundle.sh 94cf51bf6abebf14c71616a11e29b9bf96d330ac 8883e814455c9d917200eb9f74b22562f31d69ec f5042eece11c74c5503401a6007d782b961fef1a
mv bundles/94cf51bf6 bundles/c; mv bundles/8883e8144 bundles/1; mv bundles/f5042eece bundles/3   # a, deps copied from kit-uap2
./setup.sh; ./launch.sh r8i us-east-2c r8i-x1 us-east-2
./start.sh r8i-x1 prep; ./rsh.sh r8i-x1 'nohup bash run-jobs.sh jobs/r8i-x1.tsv >/dev/null 2>&1 </dev/null &'
bash -c 'echo $$ > guard.pid; exec ./uap3-guard.sh >> guard.log 2>&1'   # tracked background task
./waitev.sh <seconds>
python3 dcell3.py runs/r8i-x1 --asm ana --json ana/rows.json --tsv ana/results.tsv
./teardown.sh > teardown.log 2>&1; ./sweep.sh > sweep.log 2>&1
```
