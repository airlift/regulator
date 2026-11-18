# RUN-EG-8: engine-gaps round EG-8 (J1 W4 #275, J2 W6 #276, J3 #273 with the reader remedy)

Runner: Mac thread (AWS dev account). Spec: AWS-JOBS-EG-8.md (unchanged; predictions untouched). Brief:
`~/AgentScratch/regulator/main/engine-gaps/RUNNER-BRIEF-EG-8.md`. Kit: `~/AgentScratch/regulator/main/engine-gaps/kit-eg8/`.
Project tag `T=regulator-engine-gaps-eg8`, a fresh key pair and SSH-only security groups (this Mac's /32) in us-east-2,
us-west-2, eu-central-1, eu-west-3, ap-south-1 and eu-west-2. Guard `eg8-guard.sh`, PID in `kit-eg8/guard.pid`, cap
$3.60 (`cap.txt`). The budget-curve runner (tag regulator-budget-curve-bc1) was live in the same account; only this
tag's resources were touched.

## Kit

- Scripts copied from `kit-eg7/` (read-only source; guard with reclaimed/lost handling, sync, ips lock,
  `ssh-masked.sh`, `rsh.sh` refusals, `waitev.sh` with `waitev.seen`). Changed only: tag, kit path, guard name, and:
  - `run-jobs.sh`: the fork JVM options are the spec's template, in its order:
    `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
  - `host-prep.sh`: checks every synced input against its SHA256SUMS (W4 top and `rows/`, W6, Trino corpus pins) and
    exits 1 on a mismatch; `start.sh` starts the runner only if it passed. `sync.sh` also ships the SUMS files.
  - The round's `gen-jobs.py` (writes `inputs/cases.tsv` too) and `summarize.py` (aborts unless every fork's JSON
    `jvmArgs` and log `# VM options:` line equal the template).
- Deps bundle (22 jars + cp.txt) copied from kit-eg7. No leg changes pom.xml (`git diff 11b9291e1 <leg> -- pom.xml`
  empty for all four; `git diff 6d8de8ec6 11b9291e1 -- pom.xml` empty).

## Legs and jar paths

Each leg is a `git archive` export of its full SHA (no patch), offline `test-compile` on the Mac (build only, never
timed), shipped as classes + test-classes, as EG-1 to EG-7 did. Every leg runs from `/home/ec2-user/bundles/<job><leg>/`;
the directory names are all three characters, so every classpath has equal length (the brief's `legs/a/`, `legs/b/`
idea, in the kit's existing layout):

| Job | Leg | SHA | Path on host | files |
|---|---|---|---|---|
| J1 | A main | `11b9291e1c1cc00c18a2662b7588a272961d2ed3` | `bundles/j1a` | 2361 |
| J1 | B #275 | `03abd97a04bf4a5b787ece87b5f2be44b164f18a` | `bundles/j1b` | 2365 |
| J2 | A main | `11b9291e1c1cc00c18a2662b7588a272961d2ed3` | `bundles/j2a` | 2361 |
| J2 | B #276 | `a12d6c3996cc0e5bf436f26d95fd9279073bbb62` | `bundles/j2b` | 2361 |
| J3 | A | `2ba9dbbdbdbe0ef8569227fb3d3e543d032e8a67` | `bundles/j3a` | 2375 |
| J3 | B | `eab662aca5efc4218ed04dcb1aacde6c07269b31` | `bundles/j3b` | 2375 |

- After `git fetch`, all five SHAs contain 11b9291e1 and sit on their pushed branches (`git branch -r --contains`).
  `git log 11b9291e1..<leg>`: 03abd97a0, a12d6c399 and 2ba9dbbdb are one commit each; eab662aca is five
  (731dbeb0e, b1ecd00dd, 6aef1c6b6, 010467cd9, then the benchmark class).

## Inputs

- `eg8-w4-workloads.tar.gz` (sha256 `b5ae68df…c4e81`) and `eg8-w6-workloads.tar.gz` (`fd616b69…a251`) matched the
  brief. Unpacked on the Mac: W4 17/17 top files and 34/34 `rows/` files match SHA256SUMS / SHA256SUMS.rows; W6 5/5.
- Trino rows corpus: EG-7's copy, 9 files matched against `tools/re2-benchmark/manifests/trino-row-corpus.tsv` at
  2ba9dbbdb (the pins are identical at 11b9291e1, 03abd97a0 and eab662aca).
- Expected results: top W4 and W6 files from their manifest.json; `rows/` files from the spec (35297, 447639, 450561,
  35297, 5674, 111817, 14437, 20547, 4073). Every fork checks its expected result. `inputs/cases.tsv` lists every
  bulk case (name, row id, engine, file, expected, job, family).
- On every host, `host-prep.sh` checked every synced input's sha256 against its SUMS file before the first fork
  (x hosts 28 files, y hosts 30, z hosts 2; bad=0 on all 12).

## JMH forms

- `io.airlift.regulator.BenchmarkLanguageBulk.execute -p engine=<e> -p workloadFile=<klv> -p expectedResult=<n>`
- `^…BenchmarkTrinoPlanCosts\.(contains|extract)$ -p pattern=<p> -p input=long-mixed,short-mixed,short-ascii`, then the
  same with `-p input=long-ascii` (negated-run: `(contains|count|extract)`)
- `^…BenchmarkEverydayTrinoRegexp\.(<ops>)$ -p workload=<w>`; `^…BenchmarkTrinoRows\.<contains|count>$ -p workload=<w>
  -p literal=<cells> -p corpusDirectory=<corpus dir>`
- `-f 1` with each class's own warmup and measurement, 12 forks per leg per host, ABAB (host 1 A,B; host 2 B,A).
  Native only. Temurin 25.0.4+7 (sha256 checked by the user data).

## Budget estimate (before launch)

- Per-cell fork times from EG-7's logs: Bulk 20.7 s, the 10 × 1 s + 5 × 1 s classes 15.4 s, TrinoRows 3.7 s; plus an
  allowance of 1.5 s per fork for AlwaysPreTouch and 0.5 s per invocation.
- Rows (cells): J1 46, J2 10, J3 51; 10,272 fork-cells. One host slot ≈ 13.9 h, so three groups per platform (12 hosts,
  the brief's maximum), 4.5–4.7 h each: ≈ 55.5 timed host-h + preflight and boot ≈ 57.5 host-h, ≈ $3.0 at the
  launch prices (≈ $3.3 with a reclaim allowance). The point estimate was under $3.20, so I launched and reported the
  estimate in the hosts-running milestone. Cap $3.60.

## Hosts and job layout (launch 2026-11-17 15:08 PST, 23:08Z)

- Three host groups per platform (`gen-jobs.py`); the two hosts of a group run identical cases:
  - **x** (≈ 4.69 h): J1 targets, all-greek, ASCII twins (gate), untouched programs (English rebar twins beyond
    boundary-any-english last), then J2 01-literal ru, en, zh.
  - **y** (≈ 4.50 h): J1 few-quit movers, no-assertion controls (03-date and quotes first), then J3 witnesses and
    controls.
  - **z** (≈ 4.69 h): J3 plan costs (long-ascii cells last), then J2 02-literal-alternate ru and casei-ru.
- Host 1 of each group first ran an untimed preflight: every case once on each leg, `-f 1 -wi 0 -i 1 -r 100ms`.
- 12 Spot one-time hosts, instance-initiated shutdown = terminate, 16 GB gp3. Guard deadline 21:45 PST
  (`deadline.txt`); on-host `shutdown -h +417`, read back on all 12 as 06:05Z (22:05 PST).

| host | platform | AZ | instance |
|---|---|---|---|
| r9g-x1 | r9g.large | us-east-2b | i-0ca3a597f50199ff2 |
| r9g-x2 | r9g.large | eu-central-1a | i-0f8d675dd97d197ff |
| r9g-y1 | r9g.large | us-west-2c | i-04a46690b1fdabc9d |
| r9g-y2 | r9g.large | eu-central-1c | i-03d94f6a10eb8c56e |
| r9g-z1 | r9g.large | us-east-2b | i-0c64e68da65651636 |
| r9g-z2 | r9g.large | eu-central-1a | i-02155676e1275fdf5 |
| r8i-x1 | r8i.large | eu-west-3a | i-020837cdd2ff80d3b |
| r8i-x2 | r8i.large | ap-south-1b | i-0008b4b6c3c4d812c |
| r8i-y1 | r8i.large | ap-south-1a | i-06ce919ed6beb7031 |
| r8i-y2 | r8i.large | eu-west-2b | i-008486ecf11f0da19 |
| r8i-z1 | r8i.large | eu-west-3a | i-00aa9db04fa672765 |
| r8i-z2 | r8i.large | ap-south-1b | i-0816b653ed97008f4 |

- Placement scores at 15:0x PST (target 3, single AZ): r9g.large 3 in use2-az2/az3, usw2-az3/az4, euc1-az1/2/3;
  r8i.large 3 in apne1-az2, aps1-az1/az3, eun1-az1/2/3, euw1-az2/az3, euw2-az3, euw3-az1. The budget-curve runner held
  r9g in us-east-2c and us-west-2d and r8i in eu-north-1, so r9g went to us-east-2b, us-west-2c and eu-central-1, and r8i
  to eu-west-3, ap-south-1 and eu-west-2. Every pair straddles two regions. Spot vCPU quotas checked first: eu-west-3
  allows 5 vCPU (two r8i.large), eu-west-2 32, the rest 256 or more.
- Sync was verified on r9g-x1 before the others started (bundle file counts and revisions, input sha256s, jobs file,
  timer, VM options and the first preflight lines). The other 11 were then started one at a time and checked for
  host prep, bundles, inputs, timer and CPU. All r8i hosts report Xeon 6975P-C.
- The job files as first started are in `kit-eg8/jobs-as-started/`.

## Deviations from AWS-JOBS-EG-8.md

- 6 hosts per platform in three case groups, as the brief allows when the round would run past ~3 h. Every row still
  has 2 hosts × 12 forks per leg per platform.
- JVM options follow the spec's template (`-XX:+UseG1GC -XX:+AlwaysPreTouch` added). EG-1 to EG-7 ran without those
  two flags, so EG-8's absolute scores are not strictly the same configuration as EG-7's (relevant to the
  "dot-all long-ascii moves by more than 10% from EG-7" condition). G1 is already the default on these hosts.
- "Builds the benchmarks jar on the hosts" is read as one build per leg from its own SHA (offline, on the Mac, never
  timed), shipped as class directories at equal-length paths, as RUN-EG-2 to RUN-EG-7 did.
- J3's plan-cost cases are split into a mixed/short case and a long-ascii case per pattern, so the drop-order rows run
  last; the cells are the spec's 36.

## Incidents

1. **r8i-y2 (eu-west-2b) reclaimed at 16:26:36 PST** (00:26:36Z, `Server.SpotInstanceTermination`). The guard logged
   RECLAIMED at 16:30:17, and `waitev.sh` reported it at 16:30:26. Its J1 movers, J1 03-date java and trino were complete
   (12/12 per leg) and are kept. J1 03-date rust had 17 forks; they moved to `kit-eg8/superseded/r8i-y2/`.
   - `gen-replace.py r8i-y2=r8i-y2r --apply`: 21 unfinished cases rerun in full (both legs × 12 forks, y2's B,A leg
     order, no preflight). To finish well inside the deadline I split them across two Spot hosts:
     - **r8i-y2r, us-east-1b** (use1-az2, score 3, $0.0402/h), i-0c61d7cb92763b56c: the 14 J1 control cases from
       03-date rust on (336 lines), started 16:31 PST.
     - **r8i-y2s, eu-central-1b** (euc1-az3, score 3, $0.0625/h), i-06fc50138c582b0a5: the 7 J3 witness and control
       cases (168 lines), started 16:32 PST.
     - Both pair with r8i-y1 (ap-south-1a). Same AMI by name, kit key imported (`setup-region.sh us-east-1`; the
       eu-central-1 SG already existed). Host prep (14 and 9 inputs, bad=0), bundles, jobs files, CPU (Xeon 6975P-C)
       and timers (06:05Z, 22:05 PST) were checked. The deadline is unchanged.
2. **Guard rollover** (planned) at 17:04:51 PST; relaunched at once.
3. **r9g-y1 (us-west-2c) reclaimed at 17:30:56 PST** (01:30:56Z, `Server.SpotInstanceTermination`); the guard logged it
   at 17:34:56, and `waitev.sh` reported it at 17:35:09. Its J1 movers, the 03-date and quotes controls and
   ing-suffix java are complete and kept. ing-suffix trino had 8 forks; they moved to `superseded/r9g-y1/`.
   - The 14 unfinished cases rerun in full (y1's A,B order, no preflight), split again:
     - **r9g-y1r, us-east-2b** (use2-az2, score 3, $0.0425/h), i-07416f9ae905e76a2: the 7 J1 control cases from
       ing-suffix trino on (168 lines), started 17:36 PST.
     - **r9g-y1s, us-west-2a** (usw2-az2, score 3, $0.0545/h), i-049a2d801e7562402: the 7 J3 witness and control cases
       (168 lines), started 17:36 PST.
     - Both pair with r9g-y2 (eu-central-1c). Host prep (7 and 9 inputs, bad=0), bundles, jobs files, aarch64 and
       timers (06:04Z, 22:04 PST) were checked.
4. r8i-y2s finished its 7 J3 cases (168/168) at 17:53 PST and was terminated by the guard.
5. **r8i-y2r (us-east-1b) reclaimed at 18:02:08 PST** (`Server.SpotInstanceTermination`); the guard logged it at
   18:05:20. Its ctl cases 03-date rust through ing-suffix rust are complete and kept; ing-suffix re2 had 17 forks,
   moved to `superseded/r8i-y2r/`.
   - The 5 unfinished cases (ing-suffix re2, before-holmes ×4; 120 lines) rerun in full on **r8i-y2t,
     eu-central-1b** ($0.0625/h), i-0c89094f6e2ccc538, started 18:06 PST, paired with r8i-y1. Host prep (5 inputs,
     bad=0), bundles, jobs file, CPU (Xeon 6975P-C) and timer (06:04Z) were checked.

6. The second planned guard rollover was at 18:57:46 PST, relaunched at once. The deadline
   (21:45 PST) never moved, so no timer was re-armed. Each replacement's timer was set to the same ≈ 22:05 PST and
   read back. One transient `pull failed` (r8i-y2t, 18:40 PST) cleared on the next pass.

## What ran

- 10,272 timed fork-cells, plus preflight. Every one of the 214 cells (row × engine/op × platform) has 12/12 forks per
  leg on 2 hosts. There were 0 FAILED invocations, and nothing was dropped.
- Every fork's JSON `jvmArgs` and log `# VM options:` line equal the template: 10,272 of 10,272.
- r9g ran 6 original hosts plus 2 replacements; r8i ran 6 plus 3. The last hosts, r9g-z1 and r9g-z2, ended at
  20:13 PST. Guard ALL DONE at 20:13:43 PST. Teardown and the 17-region sweep were clean by 20:17 PST.
- cost.py: $3.128 for 58.8 host-h (cap $3.60). See SPEND-EG-8.md.
- Archive: `archive/engine-gaps-eg8-20261117.tar.zst`, verified. Results: RESULTS-EG-8.md and RESULTS-EG-8.tsv.

## Commands

```sh
cd ~/AgentScratch/regulator/main/engine-gaps/kit-eg8
./bundle.sh 11b9291e1 03abd97a0 a12d6c399 2ba9dbbdb eab662aca
for m in j1a:11b9291e1 j1b:03abd97a0 j2a:11b9291e1 j2b:a12d6c399 j3a:2ba9dbbdb j3b:eab662aca; do cp -R bundles/${m#*:} bundles/${m%%:*}; done
python3 gen-jobs.py; ./setup.sh; ./setup-region.sh <region>
./launch.sh <r9g|r8i> <az> <host> <region>; ./start.sh <host>     # r9g-x1 first, sync checked, then the rest one at a time
bash -c 'echo $$ > guard.pid; exec ./eg8-guard.sh >> guard.log 2>&1'   # tracked background task; relaunch on ROLLOVER
./waitev.sh <seconds>; ./rsh.sh <host> '<cmd>'
python3 gen-replace.py <lost>=<new> --apply                         # reclaimed host: unfinished cases -> jobs/<new>.tsv
python3 summarize.py --tsv ~/AgentNotes/regulator/main/engine-gaps/RESULTS-EG-8.tsv --md results-tables.md --json summary.json
./teardown.sh > teardown.log 2>&1; ./sweep.sh > sweep.log 2>&1
```
