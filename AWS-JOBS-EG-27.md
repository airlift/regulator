# AWS jobs, round EG-27′: #278 with R3, rebased onto main 124c4de87 with the #279 repair and the quit-on-text rule (reduced D-cell, then retime)

For the coordinator to run on AWS Spot. Written 2026-11-18 PST on dain-1 and revised 2026-11-19 for the new legs. **I wrote the predictions before anything ran.** Part 1′ is an untimed reduced D-cell. Part 2 is the timed retime the coordinator approved. **The total ask stays $0.42,** including the original Part 1 that already ran ($0.021).

**Why this round.**
- **EG-23** passed #278 R2′ by rule, with three flags:
  - **Flag 1:** an r9g slower level on Trino extractAll log, unexplained.
  - **Trino extractAll lost its caller inlining** on many forks (A 47/48 and 48/48 against R2′ 21/48 and 25/48).
  - **Words' Flag 3,** which doesn't block.
- **R3** (from i33, Sol's fix): the public `extractAll` keeps its early checks and calls one private `extractAllMatches`, which holds the matcher, the bracket and the loop. The loop helper is gone.
  - **Cause:** with the loop in a helper, extractAll compiled late with the matcher setup inlined, at 4–8 KB, past InlineSmallCode (2,500 B on both architectures).
  - **Local aarch64:** 8 of 8 forks inline it into the caller, at 384 B standalone or not compiled on its own.
- **The rebase onto 069ffc1be (#275, Unicode word boundaries on the DFA)** brought two adaptations:
  1. The start-set pass passed the text and context to the transition step, so Rust's dense Unicode `\b` operations over non-ASCII text switched. **Withdrawn after EG-27 Part 1,** as described under the legs below.
  2. #275's decoded-word-cache refusals take the start-set loan back first, through the shared helper state allocation uses.
- **Delta reviews:** Sol's review of the R3 fold and the rebase found nothing. 768 optimized-against-generic differential cases passed in both modes, including Cyrillic, malformed text and regions.

## Legs
| Leg | SHA | Branch |
|---|---|---|
| A3′ | `22b70c4e7abbcad62a9f74d467bd7f180c786f2f` | `user/dain/bench-dense-match-starts-a3p`: main `124c4de87` plus the two timing-only classes (`BenchmarkDenseMatchStartLoan`, `BenchmarkDenseTrinoAllMatch`). Count-spans is a caller `find()` loop. |
| R3″ | `4bb8c50f6e9cc49a91a1624b5b72e6a7a6ed725c` | `user/dain/bench-dense-match-starts-r3pp`: #278 head `7109defa5` plus the same classes. Count-spans calls `forEachMatch`, whose consumer sums the spans. |

The benchmark-class diffs are byte-identical to the first legs' (A3 `f43cb5255`, R3 `f13c3606b`), and both legs pass `-DskipTests verify`. If #278's src/main changes before this round's first timed fork, the manager tells the coordinator, then the runner.

**Why the legs changed.** The first legs were on `069ffc1be`, before #279.
- **#279's change:** a many-match DFA over a program with no empty-width assertions now reports a match on entering a state, where it used to report one byte late.
- **The defect on the rebased head (`28e8831d1`):** #278's start-set pass, a reverse many-match DFA, kept the old convention. So it marked every start one byte right for assertion-free patterns, and the switch failed (1 of 42,916 finds served from the set on 03-date ascii).
- **The repair:** the pass reads the DFA's own convention once, through a constant index base outside the hot loop, and skips the final lookbehind step under the new one. It also takes the loan-recall reference while the start-set DFA is still published.
- **EG-27 Part 1's D4** (the first legs): the switch over Cyrillic cost 1.22× (r9g) and 1.25× (r8i). The pass's hottest loop called `computeContextTransition` out of line on every iteration, because a reverse step over non-ASCII text next to a word decision has to decode the character. On r8i, the pass also carried its position on the stack.
- **The quit-on-text rule (coordinator's option (a)):** the pass now ends, unfinished, at the first step whose transition must be read from the text (Unicode `\b` next to non-ASCII text). Those operations keep the find-by-find search for good, as on main. ASCII-text `\b` and assertion-free patterns still switch.
- **What the head changes elsewhere:** neither change touches Trino `extractAll`/`extractAllMatches`, the matcher's operation bracket or `findPair`.

**Switch check on the new legs (local, no timing).** A3′ never switches. On R3″, with answers identical to the generic matcher:
- `08-words/all-russian` count-spans: 107,391; the start-set DFA is built, but no pass finishes and no find is served from a set;
- `03-date/ascii` count-spans: 111,817, with 42,853 of 42,916 served;
- `03-date/ascii` Trino extract-all: the passes fire;
- `diagnostic/03-date/mixed-zh-sparse`: never switches on any leg, as before. It is the no-switch control.

**Workloads:** `eg27-workloads.tar.gz` (2,955,741 bytes, sha256 `9b9e1c66562696c8fdd2e3c418cc267293f1c271295214705ee8e886cd894051`), shipped on the kit branch. It is EG-11's `eg-workloads/` unchanged, plus `curated--08-words--all-russian.klv`, its manifest entry (expected `rust` 107391) and a `SHA256SUMS` line. Pass the files as in AWS-JOBS-EG-11.md.

**Conventions,** as EG-23:
- **Mode:** native only, with EG-8's template jvmArgs: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- **JMH:** the class's defaults, with the legs' jars at paths of equal length. Legs interleave fork by fork.

## Part 1′ — reduced D-cell (untimed): one host each on r9g and r8i, 3 perfasm + LogCompilation forks per leg per row
The original Part 1 ran on the first legs, A3/R3 on `069ffc1be`. **Its D1, D2 and D5 (Trino extractAll inlining, and the pair-scan control) carry over to the new head:** neither #279, the repair nor the quit-on-text rule touches those paths. Its D3 and D4 are superseded, and run again here, with the same forks per leg per host.

- **perfasm:** `-prof 'perfasm:events=cycles,instructions;hotThreshold=0.005;printMargin=24;saveLog=true;saveLogTo=<out>;saveLogToFile=<leg>-<row>-<fork>.perfasm'`;
- **compile logs:** `-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining -XX:+LogCompilation -XX:LogFile=<out>/<leg>-<row>-<fork>.xml`.
- **Host:** `kernel.perf_event_paranoid` ≤ 1, with hsdis as in EG-24. Ship the raw files to S3.

| D row | Benchmark | Why |
|---|---|---|
| D3 | `BenchmarkLanguageBulk.execute` count-spans `rust`, `diagnostic--03-date--mixed-zh-sparse` (27933) | the forEachMatch caller loop (EG-19's mzs row); no switch |
| D4 | `BenchmarkLanguageBulk.execute` count-spans `rust`, `curated--08-words--all-russian` (107391) | the quitting pass over Cyrillic: no hot loop with a call, and no position carried on the stack |
| D6 | `BenchmarkLanguageBulk.execute` count-spans `rust`, `curated--03-date--ascii` | **new:** the switching path under #279's convention (assertion-free), the repaired index base |

**Report** per row, leg and platform:
1. **Inline decisions** for `forEachMatch`, `beginAllMatchOperation`, `endAllMatchOperation` and `find`.
2. **Hot-method sizes:** compiled main-code bytes of `findGeneral`, `Dfa::searchCandidateBoundary`, `Dfa::analyzeStart` and `Dfa::searchAnchoredCandidate`, and on D4 and D6 the start-set pass `collectMatchStartsBackward` and `computeTransition`.
3. **The loop-shape rule** in the hot loops against A3′: fail only if loop-carried state or position is stored and reloaded inside a loop where A3′ keeps it in a register. Loop-invariant reloads pass. On D6, also report whether the pass's inner loop keeps `position`, `stateOffset` and the index base in registers. On D4, report any hot loop (≥ 1% of cycles) with an out-of-line call or a stack-carried position that A3′ lacks, and whether `collectMatchStartsBackward` or `computeContextTransition` is hot at all on R3″.
4. **Pointer-table paths:** confirm that the native pointer-table transition readers (`Dfa` absolute pointer transitions) compile identically on both legs on D3.

**D-cell budget:** 3 rows × 2 legs × 3 forks × 2 hosts = 36 forks.

## Part 2 — retime: 2 hosts per platform, r9g and r8i
**Standard rows, 12 forks per leg per host:**

| # | Benchmark | Role |
|---|---|---|
| T1 | count-spans `rust`, `curated--08-words--all-russian` | **new, gated:** a no-switch row; the pass quits on the first text-read step |
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
- With the 36 Part 1′ forks, that is about $0.32 at EG-23's realized $0.00036 per fork.
- With the original Part 1 (about $0.035), the total stays inside **the $0.42 ask.**

**Order:** run Part 1′ first and ship its results. Start the retime only after the coordinator says the D-cell shows no blocking shape change. Part 1′ takes about $0.02.

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

## Predictions (R3″/A3′; below 1 is faster)
| Row | r9g | r8i | Gate / wrong if |
|---|---|---|---|
| T1 all-russian count-spans | **0.99–1.02** | **0.99–1.02** | **Gate:** above 1.02 on both hosts. Wrong if above 1.02: the no-switch path costs something beyond one partial pass from the region end |
| T2 03-date unicode | 0.66–0.74 | 0.60–0.72 | wrong if above 0.80 (EG-23: switch rows held EG-13's CF values) |
| T3 extractAll dense ascii | 0.68–0.78 | 0.62–0.77 | wrong if above 0.85 |
| T4 mzs count-spans | 1.000–1.015 | 0.99–1.01 | **Gate:** above 1.02 on both hosts |
| T5 keyValue extract | 0.99–1.015 | 0.99–1.015 | **Gate:** as above; report its mode split (bimodal) |
| M1 extractAll log | **0.98–1.01**; R3 inlined in at least as many forks as A3 | **0.98–1.01**; the same | **Ready bar** |
| M2 extractAll mzs | 0.99–1.015 | 0.99–1.015 | **Ready bar** |

**Part 1′ predictions:**
- `forEachMatch`, `beginAllMatchOperation` and `endAllMatchOperation` decide as in the original Part 1's D3 on both legs.
- **D4:** neither `collectMatchStartsBackward` nor an extra `computeContextTransition` is hot (≥ 1%) on R3″. The hot loops match A3′'s find-by-find shape, with no out-of-line call and no stack-carried position that A3′ lacks.
- **D6:** `collectMatchStartsBackward` is hot on R3″, with its inner loop call-free and the index base hoisted as a loop invariant. No loop-carried store and reload.
- Pointer-table readers compile identically on D3.
- D1, D2 and D5 carry over from the original Part 1.

**Where the predictions come from.**
- **M1:** EG-23's loss was the inline race. R3 removes the race (8 of 8 locally, against R2′'s 3 of 4 and A's 1 of 4). Instructions per op are within 0.7% of R2′'s.
- **T1:** the same switch as the dense date rows. The all-russian row has 107k matches in 124 KB, about 1.15 bytes between matches, as dense as 03-date's.
- **T4 and M2:** the no-switch path is unchanged from R2′. EG-23 had mzs count-spans at 1.014.

**I am wrong if:**
- **M1 R3 inlines in fewer forks than A3 on either platform:** R3 didn't remove the race on AWS.
- **M1 passes on inlining but stays above 1.02 on both r9g hosts:** the cost is Flag 1's level, and the per-fork DFA sizes should show which shape tracks it.
- **T1 is above 1.02:** the quitting pass or the forEachMatch caller costs something on the no-switch path. Check whether the pass is built and quits once per operation, or more often.
- **D6 shows a loop-carried store and reload in the start-set pass,** or **D4 shows a hot loop with an out-of-line call or a stack-carried position that A3′ lacks.**

**Cut order** if the guard nears the cap:
1. T5.
2. Then T2.
3. Never cut M1, M2, T1 or T3.
