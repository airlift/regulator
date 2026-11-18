# RUN-EG-24: D-cell for #282 rebased (untimed, perfasm + LogCompilation)

Spec: `AWS-JOBS-EG-24.md` (sha256 `b0d0fee8…24b5`, which matches the blob on `c2ee545ae`). Brief: `RUNNER-BRIEF-EG-24.md`.
Run from Dain's Mac with `AWS_PROFILE=dev` on 2026-11-18, 12:42–13:26 PST. Tag `T=regulator-engine-gaps-eg24`.
Kit: `~/AgentScratch/regulator/main/engine-gaps/kit-eg24/`.

## Hosts

| Host | Platform | AZ | CPU | Spot $/h | Runner (PST) | Forks |
|---|---|---|---|---|---|---|
| r9g-e24a | r9g.large | us-east-2c (use2-az3) | Graviton4 / Neoverse V2 (implementer 0x41, part 0xd84), 2 vCPU, 15 GiB | 0.0320 | 12:43:19–13:12:52 | 54 perfasm + 54 perfnorm |
| r8i-e24a | r8i.large | ap-south-1b (aps1-az3) | Intel Xeon 6975P-C (as on EG-21's r8i hosts), 2 vCPU | 0.0334 | 12:45:42–13:20:26 | 66 perfasm + 66 perfnorm |

- **Pools:** both chosen at 12:4x PST from fresh placement scores, as the cheapest pools with score 3.
- **Quotas:** Spot vCPU 256 in both us-east-2 and ap-south-1.
- **Other runners:** no other regulator runner was live at launch. us-west-2a held non-regulator instances, so I avoided it.
- **Reclaims:** none. No forks failed, and no fork timed out. The longest fork took about 25 s; the per-fork timeout was 15 min.
- **JDK:** Temurin 25.0.4+7, from `platforms.tsv`, the same as EG-19 and EG-21.

## Legs and jars

| Leg | SHA | Bundle path on host | Source | Files |
|---|---|---|---|---|
| M | `0db30b513` (origin/main) | `~/bundles/e24m` | built fresh with `bundle.sh`: offline test-compile of a `git archive` export, no patch | 2402 |
| F′ | `3b1646d05` (`user/dain/plan-failed-minimum-budget`) | `~/bundles/e24p` | built fresh the same way | 2403 |
| A | `bdf26dc33` | `~/bundles/e24a` | copied from kit-eg21 (its `revision.txt` matches) | 2365 |
| F | `d03bdc07d` | `~/bundles/e24f` | copied from kit-eg21 (its `revision.txt` matches) | 2366 |

- **Dependencies:** a shared bundle of 22 jars, identical to kit-eg21's.
- **Equal paths:** the four bundle paths have the same length.
- **Identical inputs across legs:**
  - The pom.xml diff is empty across all legs.
  - The four benchmark classes the rows use are identical blobs in all legs.
- **Inputs:** none, as the spec says. host-prep reported 0 of 0, bad=0.

## Rows and order

| Row | Benchmark | Params |
|---|---|---|
| 7 | `BenchmarkRe2ScanPlan.contains` | workload=FOLDED_UNANCHORED, dotAll=false, scanPlan=true, generateBytecode=false |
| 6 | `BenchmarkTrinoScanPlan.contains` | FOLDED_UNANCHORED, dotAll=false, generateBytecode=false |
| 1 | `BenchmarkRe2ScanPlan.contains` | UNANCHORED_URL, scanPlan=true, generateBytecode=false |
| 2 | `BenchmarkRe2ScanPlan.count` | UNANCHORED_URL, scanPlan=true, generateBytecode=false |
| 5 | `BenchmarkRustScanPlan.contains` | UNANCHORED_URL, scanPlan=true, generateBytecode=false, unicodePerlClasses=false |
| 3 | `BenchmarkTrinoScanPlan.contains` | UNANCHORED_URL, generateBytecode=true |
| 10 | `BenchmarkRe2ScanPlan.contains` | UNANCHORED_SET, scanPlan=true, generateBytecode=true |
| 8 | `BenchmarkTrinoScanPlan.contains` | UNANCHORED_SET, generateBytecode=false |
| 14 | `BenchmarkEverydayTrinoRegexp.contains` | workload=quotedField |

- **Order:** rows ran in the order 7, 6, 1, 2, 5, 3, 10, 8, 14, with 3 forks per leg.
- **Interleaving:** legs alternated fork by fork.
  - Two-leg rows ran M F′ / F′ M / M F′.
  - r8i rows 6 and 7 rotated through M F′ A F / F′ A F M / A F M F′.
- **Labels:** `D24/<leg>-rNN-k` for perfasm forks and `D24n/<leg>-rNN-k` for perfnorm forks.
- **Fork data:** each fork's assembly, compile log and counts all come from that one fork.

## Profiler setup

- **hsdis:** taken from `rust-clear-losses-2/kit/hsdis/` (sha256 checked). host-prep checked that `-XX:+PrintAssembly` prints instructions before the first fork: 473 instruction lines on r9g and 314 on r8i.
- **perf:** 6.1.176.
  - r9g has no `branches` event.
  - r8i has no L1-icache, iTLB or stall events.
  - Neither gap matters, because the forks use only cycles and instructions.
- **Measured fork JVM:** `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`. That is the template, plus perfasm's diagnostic flags on perfasm forks.
- **Harness JVM:** `-Xms64m -Xmx3g`, following the UAP-6 lesson.
- **perfasm forks:** run by `diag.sh` with `-f 1 -jvm ~/jvmwrap.sh` and this profiler string:
  ```
  perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<dir>;saveLogToFile=<out>.perfasm
  ```
  - jvmwrap.sh copies perfasm's `-XX:LogFile` (the LogCompilation XML, which JMH deletes) to `<out>.xml`.
  - Each fork ran 5×1 s warmup and 5×1 s measurement, both from the benchmark's annotations.
- **Sync check on r9g fork 1:** VM invoker jvmwrap; VM options are the template plus the diagnostic flags; parameters as listed; "PrintAssembly processed: 239083"; perfasm log saved; XML copied. I checked this before the other host started.
- **perfnorm forks (deviation 1):** `-prof perfnorm:events=cycles,instructions -f 1`, run natively with the same JVM arguments. All 120 forks' recorded jvmArgs equal the template.

## Deviations

1. **Appended perfnorm forks.** perfasm's totals are frequency-mode sample counts, about 6,000 cycles and 6,000 instructions samples per fork, so they give no per-op instruction counts. That leaves the spec's instruction-ratio predictions ungradable. I appended 3 unmultiplexed perfnorm forks per leg per row: 54 on r9g and 66 on r8i. They ran after the spec's forks on the same hosts, and the results tables mark them `D24n`. Both threads were told before the analysis.
2. **`saveLogToFile` holds the full path.** In JMH 1.37, `saveLogToFile` is used as the whole output path and `saveLogTo` is ignored when it is set, so diag.sh passes the full path in it.
3. **Compile log copied by the wrapper.** jvmwrap.sh copies the LogCompilation XML, because JMH deletes perfasm's log file. EG-19 did the same.

## Analysis

All tools are in kit-eg24:

| Tool | What it does |
|---|---|
| `analyze.py` | Writes `analysis/forks.json` and `analysis/inline/`: the hot nmethod and C2 task per fork. It normalizes hidden-class addresses to `0x`. |
| `inltree.py` | Prints inline trees with caller sites, and decision hashes with `--sig`. |
| `shape24.py` | Finds loops, classifies stack slots (carried, spill, invariant, store-only, call-save), counts stores before and loads after each call site, and reports main-code bytes and loop head mod 32/64. |
| `rule24.py` / `slots24.py` | Compare loops by inline frame, with per-slot detail. |
| `dumprange.py` | Lists the instructions in an offset range. |
| `compare24.py` | Writes `analysis/compare.md`. |
| `pnm24.py` | Computes perfnorm medians and ratios. |
| `mkres24.py` | Writes RESULTS-EG-24.tsv. |

- **Generated-class fix:** the first pass reported rows 3 and 10 as "not found", because generated hidden-class names (`X/0x…::m`) didn't match the frame regexes. I fixed the regexes and reran those rows.
- **Hash normalization:** row 10's decision hashes varied per fork until hidden-class addresses were normalized.

## Commands (from kit-eg24)

```
./setup.sh; ./setup-region.sh us-east-2; ./setup-region.sh ap-south-1
python3 gen-jobs.py; python3 gen-extra.py               # perfnorm forks appended to the host tsv files
./launch.sh r9g-e24a r9g us-east-2c; ./launch.sh r8i-e24a r8i ap-south-1b
./sync.sh <host>; ./start.sh <host>                     # r9g checked first, then r8i
./eg24-guard.sh &  (guard.pid; cap 0.20, deadline 23:15Z, on-host shutdown timers 173 min, read back 23:35Z)
./waitev.sh / ./wait-for.sh                             # guard alerts between waits
./pull.sh <host>                                        # rsync-masked, without *.xml/*.perfasm/*.tmp in the sync pass
python3 analyze.py; xargs -P 8 ./shape-par.sh; python3 compare24.py; python3 pnm24.py; python3 mkres24.py
./teardown.sh; ./sweep.sh; ./sweep.sh
```

ssh, scp and rsync stderr went through the partial-address mask (`ssh-masked.sh`, `rsync-masked.sh`). `start.sh` prints no addresses.

## Lessons for the next runner

- In JMH 1.37, `saveLogToFile` must hold the full path; see deviation 2.
- perfasm can't grade per-op instruction predictions. If a spec predicts counts, plan perfnorm forks from the start.
- Generated classes appear as `Name/0x…` in perfasm frames and compile logs. Normalize the address before hashing inline trees.
- Right after teardown, a cancelled Spot request can still show as `active`. The first sweep cancels it, and the second sweep confirms the sweep is clean.
