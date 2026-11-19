# AWS jobs, round EG-27: #278 with R3, rebased onto main 069ffc1be (D-cell, then retime)

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1. **I wrote the predictions before anything ran.** Part 1 is an untimed D-cell. Part 2 is the timed retime the coordinator approved (about $0.30–0.40), plus the new Rust Unicode rows.

**Why this round.**
- **EG-23** passed #278 R2′ by rule, with three flags:
  - **Flag 1:** an r9g slower level on Trino extractAll log, unexplained.
  - **Trino extractAll lost its caller inlining** on many forks (A 47/48 and 48/48 against R2′ 21/48 and 25/48).
  - **Words' Flag 3,** which doesn't block.
- **R3** (from i33, Sol's fix): the public `extractAll` keeps its early checks and calls one private `extractAllMatches`, which holds the matcher, the bracket and the loop. The loop helper is gone.
  - **Cause:** with the loop in a helper, extractAll compiled late with the matcher setup inlined, at 4–8 KB, past InlineSmallCode (2,500 B on both architectures).
  - **Local aarch64:** 8 of 8 forks inline it into the caller, at 384 B standalone or not compiled on its own.
- **The rebase onto 069ffc1be (#275, Unicode word boundaries on the DFA)** brought two adaptations:
  1. The start-set pass passes the text and context to the transition step, as main's backward search does. **New behaviour: Rust's dense Unicode `\b` operations over non-ASCII text now switch to the start set;** before, the pass quit at the first non-ASCII byte.
  2. #275's decoded-word-cache refusals take the start-set loan back first, through the shared helper state allocation uses.
- **Delta reviews:** Sol's review of the R3 fold and the rebase found nothing. 768 optimized-against-generic differential cases passed in both modes, including Cyrillic, malformed text and regions.
- **Local switch check (no timing):**
  - On the new row `curated/08-words/all-russian` (`\b\w+\b`, unicode, rebar's Russian subtitles), the start-set pass fires on R3 in every run and never on A3, with identical answers (107,391, equal to rebar's rust/regex count).
  - The 03-date pattern has no `\b`, so its Unicode rows over Russian or Chinese text don't exercise the change. They are left out.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| A3 | `f43cb5255b702a7cb8a35d13db653df77325eaa1` | `user/dain/bench-dense-match-starts-a3`: main `069ffc1be` plus the two timing-only classes (`BenchmarkDenseMatchStartLoan`, `BenchmarkDenseTrinoAllMatch`). Count-spans is a caller `find()` loop. |
| R3 | `f13c3606b42a3454b40c96d2ae69fe859cea278f` | `user/dain/bench-dense-match-starts-r3`: #278 head `fd8a3bddd` plus the same classes. Count-spans calls `forEachMatch`, whose consumer sums the spans. |

Both legs pass `-DskipTests verify`. If #278's src/main changes before this round's first timed fork, the manager tells the coordinator, then the runner.

**Rebase onto main `124c4de87` (#279), after the legs were built.**
- #278's PR head is now `28e8831d1`, #278 rebased onto `124c4de87`. The only conflict was keep-both, in `TestRetainedSize`. #278's src/main delta is the same as in `fd8a3bddd`.
- #279 changes only Dfa's many-match search: `searchMany`, `scanManyBytes`, many-match state construction, and an argument guard for many-match programs at the top of `Dfa.search`. None of that is on the Trino extractAll path, the dense starts or the start-set pass.
- So the legs stay on `069ffc1be` and still measure exactly #278's change, by the coordinator's rule.

**Workloads:** `eg27-workloads.tar.gz` (2,955,741 bytes, sha256 `9b9e1c66562696c8fdd2e3c418cc267293f1c271295214705ee8e886cd894051`), shipped on the kit branch. It is EG-11's `eg-workloads/` unchanged, plus `curated--08-words--all-russian.klv`, its manifest entry (expected `rust` 107391) and a `SHA256SUMS` line. Pass the files as in AWS-JOBS-EG-11.md.

**Conventions,** as EG-23:
- **Mode:** native only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **JMH:** the class's defaults, with the legs' jars at paths of equal length. Legs interleave fork by fork.

## Part 1 — D-cell (untimed): one host each on r9g and r8i, 3 perfasm + LogCompilation forks per leg per row
- **perfasm:** `-prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>;saveLogToFile=<leg>-<row>-<fork>.perfasm'`;
- **compile logs:** `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<row>-<fork>.xml`.
- **Host:** `kernel.perf_event_paranoid` ≤ 1, with hsdis as in EG-24. Ship the raw files to S3.

| D row | Benchmark | Why |
|---|---|---|
| D1 | `BenchmarkDenseTrinoAllMatch` `-p op=extractAll`, `diagnostic--03-date--log` | R3's inline fix |
| D2 | `BenchmarkDenseTrinoAllMatch` `-p op=extractAll`, `diagnostic--03-date--mixed-zh-sparse` | the second ready-bar row |
| D3 | `BenchmarkLanguageBulk.execute` count-spans `rust`, `diagnostic--03-date--mixed-zh-sparse` (27933) | the forEachMatch caller loop (EG-19's mzs row) |
| D4 | `BenchmarkLanguageBulk.execute` count-spans `rust`, `curated--08-words--all-russian` (107391) | the new switching path over Cyrillic |
| D5 | `BenchmarkLanguageBulk.execute` count `rust`, `curated--01-literal--sherlock-en` (513) | #276's pair-scan budget sites, a control; #278 doesn't touch them |

**Report** per row, leg and platform:
1. **Inline decisions:** whether Trino `extractAll` inlines into the benchmark caller in every fork (with C2's reason where it doesn't), and `extractAll`'s and `extractAllMatches`' compiled sizes. Also the decisions for `forEachMatch`, `beginAllMatchOperation`, `endAllMatchOperation` and `find`, and on D5 `findPair` and its budget test.
2. **Hot-method sizes:** compiled main-code bytes of `findGeneral`, `Dfa::searchCandidateBoundary`, `Dfa::analyzeStart`, `Dfa::searchAnchoredCandidate`, and on D4 the start-set pass `collectMatchStartsBackward` and `computeTransition`. Do this per fork, for Flag 1.
3. **The loop-shape rule** in the hot loops against A3: fail only if loop-carried state or position is stored and reloaded inside a loop where A3 keeps it in a register. Loop-invariant reloads pass.
4. **Pointer-table paths:** confirm that the native pointer-table transition readers (`Dfa` absolute pointer transitions) compile identically on both legs on D3 and D4.

**D-cell budget:** 5 rows × 2 legs × 3 forks × 2 hosts = 60 forks.

## Part 2 — retime: 2 hosts per platform, r9g and r8i
**Standard rows, 12 forks per leg per host:**

| # | Benchmark | Role |
|---|---|---|
| T1 | count-spans `rust`, `curated--08-words--all-russian` | **new, gated:** the new switch over real Russian text |
| T2 | count-spans `rust`, `curated--03-date--unicode` | dense switch row, link to EG-23 |
| T3 | `BenchmarkDenseTrinoAllMatch.extractAll`, `curated--03-date--ascii` | the switch check (dense extractAll) |
| T4 | count-spans `rust`, `diagnostic--03-date--mixed-zh-sparse` | no-switch control, EG-23's 1.014 |
| T5 | `BenchmarkEverydayTrinoRegexp.extract`, `-p workload=keyValue` | everyday control |

**Mode-share rows, 24 forks per leg per host:**

| # | Benchmark | Role |
|---|---|---|
| M1 | `BenchmarkDenseTrinoAllMatch.extractAll`, `diagnostic--03-date--log` | **ready bar** |
| M2 | `BenchmarkDenseTrinoAllMatch.extractAll`, `diagnostic--03-date--mixed-zh-sparse` | **ready bar** |

- **Compile logs:** M1 and M2 forks in both legs add `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<host>-<fork>.xml`.
- **Report per fork:** whether `extractAll` inlined into the caller, and the final C2 sizes of `Dfa::searchAnchoredCandidate`, `Dfa::searchCandidateBoundary` and `Dfa::analyzeStart`. These are set against the fork's score for Flag 1.

**Retime budget:**
- 5 × 96 = 480 standard forks, plus 2 × 192 = 384 mode-share forks, for 864 timed forks.
- With the 60 D-cell forks, that is about $0.33 at EG-23's realized $0.00036 per fork.
- **Ask $0.42.**

**Order:** run the D-cell first and ship its results. Start the retime only after the coordinator says the D-cell shows no blocking shape change. The D-cell takes about $0.03.

## Grading (the coordinator's rules)
- **Per row and platform:** the median of the per-host R3/A3 ratios, with each host's value. Flag (⚑) any host pair that disagrees by more than 5%.
- **Gate:** a row fails if it is above 1.02 on both hosts of a platform. That applies to T1, T4, T5, M1 and M2.
- **Ready bar:** M1 and M2 at or below 1.02 on both hosts of both platforms, by the median of per-host ratios, with the pooled fast-state ratio reported beside it. T1 must pass the gate.
- **Mode shares (M1, M2):**
  - split each host's forks at an 8% gap in the sorted scores (or by compile shape where the logs show distinct shapes);
  - pool both hosts of a platform;
  - report each leg's slow count, both modes' medians, and the one-sided Fisher p for "R3's slow share is above A3's";
  - report the fast-state ratio beside the gate.
- **Words' Flag 3 doesn't block.**

## Predictions (R3/A3; below 1 is faster)
| Row | r9g | r8i | Gate / wrong if |
|---|---|---|---|
| T1 all-russian count-spans | **0.60–0.85** | **0.55–0.85** | **Gate:** above 1.02 on both hosts. Wrong if above 0.92 (the switch should win as on the dense date rows) |
| T2 03-date unicode | 0.66–0.74 | 0.60–0.72 | wrong if above 0.80 (EG-23: switch rows held EG-13's CF values) |
| T3 extractAll dense ascii | 0.68–0.78 | 0.62–0.77 | wrong if above 0.85 |
| T4 mzs count-spans | 1.000–1.015 | 0.99–1.01 | **Gate:** above 1.02 on both hosts |
| T5 keyValue extract | 0.99–1.015 | 0.99–1.015 | **Gate:** as above; report its mode split (bimodal) |
| M1 extractAll log | **0.98–1.01**; R3 inlined in at least as many forks as A3 | **0.98–1.01**; the same | **Ready bar** |
| M2 extractAll mzs | 0.99–1.015 | 0.99–1.015 | **Ready bar** |

**D-cell predictions:**
- `extractAll` inlines into the caller in 3 of 3 forks on both legs and both platforms. On R3 it compiles to under 500 B or not on its own.
- `extractAllMatches` is an out-of-line call, once per operation.
- No row fails the loop-shape rule.
- D5's pair-scan decisions are identical on both legs.
- Pointer-table readers compile identically.

**Where the predictions come from.**
- **M1:** EG-23's loss was the inline race. R3 removes the race (8 of 8 locally, against R2′'s 3 of 4 and A's 1 of 4). Instructions per op are within 0.7% of R2′'s.
- **T1:** the same switch as the dense date rows. The all-russian row has 107k matches in 124 KB, about 1.15 bytes between matches, as dense as 03-date's.
- **T4 and M2:** the no-switch path is unchanged from R2′. EG-23 had mzs count-spans at 1.014.

**I am wrong if:**
- **M1 R3 inlines in fewer forks than A3 on either platform:** R3 didn't remove the race on AWS.
- **M1 passes on inlining but stays above 1.02 on both r9g hosts:** the cost is Flag 1's level, and the per-fork DFA sizes should show which shape tracks it.
- **T1 is above 0.92:** the new switch over Cyrillic loses something the date rows don't. First check that its passes fire inside timing.
- **D4 shows a loop-carried store and reload in the start-set pass on R3 only.**

**Cut order** if the guard nears the cap:
1. T5.
2. Then T2.
3. Never cut M1, M2, T1 or T3.
