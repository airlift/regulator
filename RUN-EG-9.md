# RUN-EG-9: engine-gaps round EG-9 (D0 #275 perfasm, D1 #273 quotedField perfasm, J1 #275 fixed)

Runner: Mac thread (AWS dev account). Spec: AWS-JOBS-EG-9.md (unchanged; identical to orphan commit `0c193c9da` on
`user/dain/eg9-kit`; predictions untouched). Brief: `~/AgentScratch/regulator/main/engine-gaps/RUNNER-BRIEF-EG-9.md`.
Kit: `~/AgentScratch/regulator/main/engine-gaps/kit-eg9/`. Project tag `T=regulator-engine-gaps-eg9`, a fresh key pair
and SSH-only security groups (this Mac's /32) in us-east-2 (unused), us-west-2, eu-central-1, eu-north-1, ap-south-1 and
us-east-1. Guard `eg9-guard.sh`, PID in `kit-eg9/guard.pid`, cap $1.40 (`cap.txt`), deadline 02:00 PST Nov 18
(`deadline.txt`). The uap-core runner (tag regulator-uap-core-uap1) was live in the same account; only this tag's
resources were touched, and none of its pools was used.

## Kit

- Scripts copied from `kit-eg8/` (read-only source): env, setup, setup-region, launch, start, sync, run-jobs, pull, ips,
  rsh (terminated/reclaimed/lost refusals), ssh-masked, waitev (with `waitev.seen`), guard, cost, teardown, sweep,
  gen-replace, summarize. Changed: tag, kit path, guard name, and:
  - `launch.sh` installs `perf binutils zstd` on every host (so any replacement can take D0/D1).
  - `host-prep.sh` checks the W4 SUMS only; on a host given `~/hsdis` (the D host) it sets
    `kernel.perf_event_paranoid=-1` and `kernel.kptr_restrict=0` (as rust-clear-losses-2 round 2), records a perf
    check, copies `hsdis-amd64.so` (from `rust-clear-losses-2/kit/hsdis/`) into `/opt/jdk/lib/`, and checks that
    `-XX:+PrintAssembly` prints instructions. `start.sh` ships hsdis only to hosts whose jobs have D lines.
  - New `diag.sh` (script mode) runs one D0/D1 perfasm fork: the template JVM options plus the spec's diagnostic flags,
    `-f 1`, `-prof perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogToFile=<out>.perfasm`,
    and `-jvm jvmwrap.sh`. The `.perfasm` and `.xml` are zstd-compressed on the host after each fork.
  - New `jvmwrap.sh`: runs the forked JVM unchanged, then copies the HotSpot log of the last `-XX:LogFile=` to the
    spec's `<leg>-<row>-<fork>.xml` path (see deviations).
  - `gen-jobs.py` and `summarize.py` for this round (J1 only in the summary; it aborts unless every fork's JSON
    `jvmArgs` and log `# VM options:` line equal the template).
- Deps bundle copied from kit-eg8. No leg changes pom.xml (`git diff 11b9291e1 <leg> -- pom.xml` empty for all five).

## Legs and jar paths

Each leg is a `git archive` export of its full SHA (no patch), offline `test-compile` on the Mac (build only, never
timed), shipped as classes + test-classes. The four EG-8 bundles were reused (revision.txt matches); b42f6b6dc was built
fresh. Every leg runs from `/home/ec2-user/bundles/<3 chars>/`, so every classpath has equal length:

| Job | Leg | SHA | Path on host |
|---|---|---|---|
| D0 | M main | `11b9291e1c1cc00c18a2662b7588a272961d2ed3` | `bundles/d0m` |
| D0 | B #275 | `03abd97a04bf4a5b787ece87b5f2be44b164f18a` | `bundles/d0b` |
| D0 | F fixed #275 | `b42f6b6dce606cf01ae8e051f3246062c4bef76c` | `bundles/d0f` |
| D1 | A | `2ba9dbbdbdbe0ef8569227fb3d3e543d032e8a67` | `bundles/d1a` |
| D1 | B | `eab662aca5efc4218ed04dcb1aacde6c07269b31` | `bundles/d1b` |
| J1 | A main | `11b9291e1c1cc00c18a2662b7588a272961d2ed3` | `bundles/j1a` |
| J1 | B fixed #275 | `b42f6b6dce606cf01ae8e051f3246062c4bef76c` | `bundles/j1b` |

- After `git fetch`, every SHA contains 11b9291e1 and sits on its pushed branch. `git log 11b9291e1..b42f6b6dc` is one
  commit ("Decide Unicode word boundaries on the DFA").
- **J1 B swap check.** At 21:3x PST `refs/pull/275/head` was `b42f6b6dc`. At 21:4x PST the engine-gaps manager reported
  that it moved to `e69c326ba2ec31293ab0430e09f98deb84bde889`, a test-only change (TestHotMethodSize), and told me
  not to swap. I verified `git diff b42f6b6dc e69c326ba -- src/main` is empty. J1 B and D0 F stayed `b42f6b6dc`.

## Inputs

- `eg8-w4-workloads.tar.gz`: 9,747,711 B, sha256 `b5ae68df…c4e81`, as the brief says. Unpacked on the Mac: 17/17 top
  files and 34/34 `rows/` files match SHA256SUMS / SHA256SUMS.rows. Expected results from manifest.json (top) and the
  spec (rows); they match the spec's list.
- On every host, `host-prep.sh` checked each synced input against its SUMS file before the first fork: x hosts 13 or
  15 files, y hosts 16; bad=0 on all 8.
- D1 needs no external input (`BenchmarkEverydayTrinoRegexp -p workload=quotedField`).

## Profiler setup (r8i-x1)

- perf 6.1.176 (AL2023 package), `perf_event_paranoid=-1`, `kptr_restrict=0`; `perf stat -e cycles,instructions true`
  counts both. hsdis-amd64 loads with Temurin 25.0.4+7: `-XX:+PrintAssembly -version` prints 379 disassembled
  instruction lines (JDK 25 prints no "Loaded disassembler" line).
- Every D fork's log shows `# VM invoker: /home/ec2-user/jvmwrap.sh`, the template plus the diagnostic flags in
  `# VM options:`, "PrintAssembly processed: ~320,000 address lines", and perf columns for cycles and instructions.

## Budget estimate (before launch)

- J1 2,784 forks at EG-8's Bulk cost (20.7 s + 1.5 s pretouch + 0.5 s per invocation) is 17.6 timed host-h; D0/D1 about
  0.5 h; preflight and boot about 1 h; about 19 host-h at $0.015–0.060/h plus EBS and IPv4, ≈ $0.93. That is under the
  $1.20 notify line, so I launched without asking. Cap $1.40.

## Hosts and job layout (launch 2026-11-17 21:35 PST, 05:35Z)

- J1 alone is 4.4 h per host with one pair per platform, so I ran two host groups per platform (`gen-jobs.py`), each
  pair carrying whole rows:
  - **x** (≈ 2.0 h): Russian targets, the Rust ASCII twin (gate), all-greek, then the other ASCII twins (cut first).
  - **y** (≈ 2.4 h): 03-date and ing-suffix controls (gates), then quotes and before-holmes (cut second).
- Host 1 of each group first ran an untimed preflight: every case once on each leg, `-f 1 -wi 0 -i 1 -r 100ms`. ABAB per
  fork: host 1 A,B; host 2 B,A. 12 forks per leg per host. Native only.
- r8i-x1 ran D0 (M B F M B F per row) and D1 (A B A B per op), then D0n/D1n perfnorm forks, before its J1 forks except
  one (see deviations).
- 8 Spot one-time hosts, instance-initiated shutdown = terminate, 16 GB gp3, on-host `shutdown -h +284`, read back on all
  8 as ≈ 10:20Z (02:20 PST).

| host | platform | AZ | instance | Spot $/h at launch |
|---|---|---|---|---|
| r9g-x1 | r9g.large | us-west-2a | i-0adb298deecc46e47 | 0.0547 |
| r9g-x2 | r9g.large | eu-central-1a | i-01b4be01a8cc391fb | 0.0602 |
| r9g-y1 | r9g.large | eu-central-1a | i-087d2675946c11562 | 0.0602 |
| r9g-y2 | r9g.large | us-west-2a | i-0f79b77ae15bd3d54 | 0.0547 |
| r8i-x1 | r8i.large | eu-north-1b | i-03e94eaf00114e842 | 0.0155 |
| r8i-x2 | r8i.large | ap-south-1b | i-0ca032e1629c194de | 0.0347 |
| r8i-y1 | r8i.large | eu-north-1c | i-0a585c3471b2f9c1d | 0.0148 |
| r8i-y2 | r8i.large | us-east-1c | i-0af87c3b6516a00ed | 0.0413 |

- Placement scores at 21:3x PST (target 2, single AZ): r9g.large 3 in usw2-az2/az3/az4, use2-az2/az3, euc1-az2/az3;
  r8i.large 3 in use1-az2/az4, euw1-az2, eun1-az2/az3, euc1-az1/az3, aps1-az3, apne2-az4, apne1-az2. uap-core held
  us-west-2b/c/d, us-east-2b/c and eu-central-1b for r9g, and us-east-2a, eu-north-1a, sa-east-1b,
  ap-northeast-2b/d and us-east-1b for r8i. So r9g used us-west-2a and eu-central-1a (the only score-3 pools clear of
  uap-core), and r8i used eu-north-1b/1c, ap-south-1b and us-east-1c. Each pair straddles two regions. Spot vCPU quotas
  were checked first: us-west-2 736, eu-central-1 and ap-south-1 256, eu-north-1 and us-east-1 32.
- All r8i hosts report Intel Xeon 6975P-C.

## Deviations from AWS-JOBS-EG-9.md

1. **D order on r8i-x1.** My first host-prep hsdis check required HotSpot's "Loaded disassembler" line, which JDK 25
   doesn't print. `runs/hsdis-ok` was not written, so the 20 D lines failed at once (0 s, nothing ran) and the runner
   went on to preflight and J1. hsdis did work (379 instruction lines). I wrote `hsdis-ok` by hand and appended the 20 D
   lines to `jobs/r8i-x1.next`; the runner takes those before its next queued line. So D0 and D1 ran after r8i-x1's first
   J1 fork (all-russian A f1) and before the rest. The check now uses the instruction count alone.
2. **D per-op counts from perfnorm.** perfasm columns are sample counts, not per-op cycles or instructions. So r8i-x1
   also ran `-prof perfnorm:events=cycles,instructions` forks with the template JVM options: D0n, 2 per leg per row, and
   D1n, 2 per leg per op (20 forks, about 8 min). They come after D and before the rest of r8i-x1's J1. Untimed for J1.
3. **`.xml` files.** JMH 1.37 puts perfasm's own `-XX:LogFile=<tmp>` after the user's jvmArgs (the last one wins) and
   deletes that log after parsing. So the spec's `-XX:LogFile=<out>/…xml` stays on the command line but has no effect;
   `jvmwrap.sh` copies perfasm's log, which holds the same compilation log, to that path when the fork exits.
4. **`saveLogToFile`** instead of the spec's `saveLogTo`: in JMH, `saveLogTo` is a directory option and writes
   `<dir>/<id>.log`. The file names follow the spec, `<leg>-<row>-<fork>.perfasm` (D1 `<leg>-<op>-<fork>`).
5. D1 runs contains and count as separate invocations (one perfasm log per benchmark): 2 legs × 2 ops × 2 forks = 8.
   Leg C is absent because J2 lists no SHA at this kit commit.
6. Two host groups per platform, which the brief allows when one pair would run past about 3 h.

## Incidents

1. **r9g-x1 (us-west-2a) reclaimed at 22:28:43 PST** (06:28:43Z, `Server.SpotInstanceTermination`). The guard logged
   RECLAIMED at 22:33:39, and `waitev.sh` reported it at 22:33:51. Its preflight and the 5 Russian target cases (12/12 per
   leg) are complete and kept. The Rust ASCII twin had 1 A fork and a partial B; they moved to `superseded/r9g-x1/`.
   - `gen-replace.py r9g-x1=r9g-x1r --apply`: the 8 unfinished cases rerun in full (both legs × 12 forks, x1's A,B order,
     no preflight), split across two Spot hosts so each finishes in about 40 min:
     - **r9g-x1r, us-west-2a** (usw2-az2, score 3, $0.0547/h), i-0289eaf2ae4b4925c: i787 Rust twin, all-greek, and
       word-ending-n-asciionly java and trino (96 lines). Started 22:35 PST.
     - **r9g-x1s, us-east-2c** (use2-az3, score 3, $0.0314/h), i-0bc25478002b7004a: words java and trino,
       boundary-any-english java, rust-unicode-ascii word-ending-n (96 lines). Started 22:35 PST. This is a uap-core r9g
       pool: the only other score-3 pool clear of uap-core was eu-central-1a, which holds the pair partner r9g-x2.
     - Both pair with r9g-x2 (eu-central-1a). Host prep (4 inputs each, bad=0), bundles, jobs files, aarch64 and timers
       (≈ 10:19Z, 02:19 PST) were checked. The deadline is unchanged.
2. **r9g-y2 (us-west-2a) reclaimed at 22:28:40 PST**, the same pool event (`Server.SpotInstanceTermination`). The
   guard logged it at 22:33:55; `waitev.sh` reported it at 22:35:55, folded into the call that was handling incident 1.
   Its 03-date ×4 and ing-suffix java cases are complete and kept. ing-suffix trino had 1 B fork; it moved to
   `superseded/r9g-y2/`.
   - `gen-replace.py r9g-y2=r9g-y2r --apply`: the 11 unfinished cases rerun in full (y2's B,A order, no preflight),
     split across two Spot hosts away from us-west-2a:
     - **r9g-y2r, us-east-2b** (use2-az2, score 3, $0.0425/h), i-015223cfd3c25aed3: ing-suffix trino, rust and re2, and
       quotes java, trino and rust (144 lines). Started 22:37 PST.
     - **r9g-y2s, us-west-2d** (usw2-az4, score 3, $0.0331/h), i-01b8b65b5ac458dc9: quotes re2 and before-holmes ×4
       (120 lines). Started 22:37 PST.
     - Both are uap-core r9g pools (no score-3 pool clear of uap-core was left that isn't us-west-2a, just reclaimed,
       or eu-central-1a, which holds the pair partner r9g-y1). Both pair with r9g-y1. Host prep (6 and 5 inputs, bad=0),
       bundles, jobs files, aarch64 and timers (≈ 10:19Z) were checked.

3. One transient `pull failed` (r9g-x1s, 23:02 PST): the host was healthy and the next pass pulled it.
4. Planned guard rollover at 23:30:08 PST; relaunched at once. The deadline (02:00 PST) never moved, so no timer was
   re-armed.

## What ran

- D0: 12 perfasm forks + 12 perfnorm forks; D1: 8 perfasm forks + 8 perfnorm forks (r8i-x1, all ok). The 20 D lines
  that failed at once on the first pass ran nothing (deviation 1).
- J1: 2,784 timed forks. Every one of the 58 cells (29 rows × 2 platforms) has 12/12 forks per leg on 2 hosts. There
  were 0 FAILED J1 invocations; nothing was cut. Every fork's JSON `jvmArgs` and log `# VM options:` line equal the
  template (2,784 of 2,784, checked by summarize.py).
- Last hosts (r9g-y1, r8i-y1, r8i-y2) finished at 00:08–00:14 PST Nov 18; guard ALL DONE at 00:14:11 PST. Teardown done
  and the 17-region sweep clean by 00:17 PST.
- cost.py: $0.898 for 19.27 host-h (cap $1.40). See SPEND-EG-9.md. Results: RESULTS-EG-9.md and RESULTS-EG-9.tsv.

## Commands

```sh
cd ~/AgentScratch/regulator/main/engine-gaps/kit-eg9
./bundle.sh 11b9291e1 03abd97a0 2ba9dbbdb eab662aca b42f6b6dc
for m in d0m:11b9291e1 d0b:03abd97a0 d0f:b42f6b6dc d1a:2ba9dbbdb d1b:eab662aca j1a:11b9291e1 j1b:b42f6b6dc; do cp -R bundles/${m#*:} bundles/${m%%:*}; done
python3 gen-jobs.py; ./setup.sh; ./setup-region.sh <region>
./launch.sh <r9g|r8i> <az> <host> <region>; ./start.sh <host>       # r8i-x1 first, checked, then the rest
bash -c 'echo $$ > guard.pid; exec ./eg9-guard.sh >> guard.log 2>&1'   # tracked background task; relaunch on ROLLOVER
./waitev.sh <seconds>; ./rsh.sh <host> '<cmd>'
python3 d0-analyze.py ana runs/r8i-x1/D0/*.perfasm.zst                  # D0: frame, textEnd location, hot [rsp], compares
python3 d1-attr.py 8 runs/r8i-x1/D1/*.log; python3 d1-lines.py <method:line> <log>   # D1 attribution
python3 summarize.py --tsv ~/AgentNotes/regulator/main/engine-gaps/RESULTS-EG-9.tsv --md results-tables.md --json summary.json
./teardown.sh > teardown.log 2>&1; ./sweep.sh > sweep.log 2>&1
```
