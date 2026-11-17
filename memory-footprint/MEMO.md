# Memory budget memo (2026-11-09)

**For:** Dain's decision on the default memory budget (`Re2.Options.DEFAULT_MAX_MEMORY`, 96 MiB today).
Changing the default or the reset policy is Dain's decision; this memo recommends and shows the evidence.

**State:** final. Every cell is measured. Timings come from two AWS runs on r9g and r8i Spot hosts: the budget
sweep (2026-11-08, $0.78) and the memo A/B (2026-11-09, $1.27, `benchmarks/memo-ab-2026-11-09/`). Bytes come
from local counts.

## Recommendation

Lower the default to **32 MiB**. A published row that fails to compile, or measurably slows, at 32 MiB runs at
the budget it needs and shows a pill with that budget in the report. Comparators with a memory knob follow the
same rule.

| | (a) Today, with pills | (b) With the G + I12 prototypes |
|---|---|---|
| Default | 32 MiB | 32 MiB |
| unicode/overlapping-words (english, russian; all languages) | pill **96 MiB** | pill **72 MiB** (measured: 0.97–1.03× main at 96) |
| curated/10-bounded-repeat/context (all languages) | pill **96 MiB** | pill **96 MiB** (no prototype changes it) |
| dictionary english-10 search and per-line find (RE2, Java, Rust) | pill **96 MiB** (rescue route 4–6% slower on r9g search, 4% on r8i find) | same |
| dictionary english-10 count (RE2, Java, Rust) | default: the rescue route is **1.7–2.5× faster** than today | same |
| Every other published row, including url/search | default, unchanged | default, unchanged |
| Retained at 96 MiB, overlapping-words english | 50.3 MB | 32.9 MB |
| Retained at 96 MiB, 12-dictionary single | 8.1 MB | 3.8 MB |
| Retained at 96 MiB, sum over 192 large-program rows | 1,035.5 MiB | 788.9 MiB |

**Why 32 and not lower:**
- At 24 MiB, overlapping-words no longer compiles at all; its compile edge is 26.92 MiB, which neither
  prototype moves. Trino url/search also slows 5% in safe mode.
- At 16 MiB, url/search slows 6–7× in safe mode in every language, english-15 slows 1.2–1.5×, and 12-dictionary
  and english-15 change matcher.
- Below 32, the pill list stops being "a few rows".

**Why lower at all:** the budget is a cap, not a reservation.
- Published rows settle at a median of about 10 KB and a p90 of 113–320 KB.
- Only three pattern families use more than 32 MiB, so a 32 MiB default changes nothing for almost every pattern.
- What it changes is the worst case: a hostile or accidental pattern can take a third as much shared DFA memory
  per compiled pattern. That is the number a Trino operator plans around.

**Whether to ship G + I12:** they are local prototypes, reviewed and fixed but not yet PRs. Each stands on its own:
- G turns the overlapping-words pill from 96 into 72 MiB.
- I12 cuts retained memory on large programs by about a quarter at any budget.
- Both are neutral at 96 MiB.
I'd open them as one PR after this decision, because the memo's column (b) assumes both.

## What the default actually bounds

- **Inside the budget:** the programs and the current DFA tables, shared by every thread (lock-free readers, one
  locked writer). Forward DFAs get two thirds of it and reverse DFAs one third.
- **Outside the budget:** see `ceilings/VERDICT.md`. A lower default shrinks the per-matcher workspaces' upper
  bound too.
  - per-matcher NFA and BitState workspaces, each up to about the forward share and retained per matcher;
  - superseded DFA generations held by in-flight readers;
  - superseded native tables, at most 2× the newest;
  - compile transients, up to about 1.6×;
  - opt-in generated-class metaspace.
- **Comparators:**
  - C++ RE2 shares an 8 MiB budget (default `max_mem`), and its per-search scratch is uncounted.
  - The regex crate gives each NFA 10 MiB and each lazy DFA cache 2 MiB per thread. Its caches never shrink and
    grow with concurrency.
  - JDK and Joni have no ceiling.

## Evidence

**Settling (phase 1, local byte counts, 1,125 published row × language pairs):** every published row settles at
96 MiB in every language and both memory modes, with 0 resets and 0 bails. Only 20 of 1,272 DFA rows fill more
than half their budget. The large ones:

| Row | Regulator | C++ RE2 | regex crate |
|---|---|---|---|
| overlapping-words/russian | 55.6 MB (24.3 MB fixed) | 0.98 MB, runs on its NFA | 15.6 MB |
| 10-bounded-repeat/context | 29.5–33.3 MB, 88–99% DFA fill | 2.2 MB, 99 resets per run | 3.1 MB |
| url/search | 10.4–11.5 MB | 3.6 MB | 4.4 MB |
| english-10 (RE2, Java, Rust) | 25 MB fixed at 96 MiB; 2.8 MB on the rescue route | 11.4 MB, NFA only | NFA only |

**Relative to comparators** on published rows, Regulator's settled memory is a median of:
- 1.62× C++ RE2;
- 0.64× the regex crate (165 of 245 rows smaller than the crate);
- 18× the JDK and 8.1× Joni, which keep no DFA.

**Threads (T = 1, 4, 16, one compiled pattern):** Regulator is flat; each matcher adds about 200 B. Regulator's
32 MiB default bounds all threads together. The crate holds exactly T caches, at T = 16:
- url/search 4.4 → 59.9 MB;
- overlapping-words/russian 15.6 → 179 MB;
- 10-bounded-repeat 3.1 → 49.4 MB, where Regulator holds 33.3 MB.

**Budget sweep, main (AWS, 2026-11-08):** published rows that slow, by budget:

| Budget | Rows that slow |
|---|---|
| 64 MiB | 10-bounded-repeat/context 1.2–15× |
| 48 MiB | + overlapping-words 130–1,260× (DFAs fail to initialize; the capture route drops to the NFA) |
| 32 MiB | same rows; english-10 compiles only on the rescue route |
| 24 MiB | + overlapping-words fails to compile; Trino url/search safe 1.05× |
| 16 MiB | + url/search 1.3–7× in every language; english-15 1.2–1.5× |
| 8 MiB | + url/search 12–19× |

**Edges (local, the smallest budget at which each part initializes, MiB):**

| Row | compile | forward first-match | forward longest | reverse | capture route |
|---|---|---|---|---|---|
| overlapping-words, main | 26.92 | 38.03 | 118.19 | 91.98 | 66.73 |
| overlapping-words, G (= G + I12) | 26.92 | 38.03 | 49.26 | 65.27 | 66.73 |
| english-10 (RE2/Java/Rust), main | 3.11 (rescue) | 81.86 | 135.99 | 163.94 | 3.11 |
| 12-dictionary single, english-15, main | 0.42 | 10.58 | 17.12 | 20.42 | 0.42 |
| 10-bounded-repeat/context Java/Trino, main | 0.39 | 0.60 | 1.07 | 1.33 | 0.39 |

The 10-bounded-repeat edges are tiny: that row slows from filling its states, not from a setup gate. Of 1,090
published rows, 30 have an edge above 16 MiB, all in the overlapping-words, english-10 and dictionary groups.
These edges were unchanged on main da1807c8d.

**Memo A/B (AWS, 2026-11-09; main da1807c8d against G, I12 and G + I12; 5,016 jobs; n = 2 per arm per platform):**
- **At 96 MiB, all three arms are neutral.**
  - Geometric mean against main is 0.997–1.002 on both platforms.
  - Controls the arms can't change show the same spread (9 of 120 outside ±3%).
  - The few large single-cell ratios are two-speed forks that hit main too.
  - A count probe shows 0 resets, 0 bails, and the same DFA routes and compiled classes in every arm.
- **overlapping-words:**
  - 32 MiB: no arm builds a DFA, so every arm runs 188–1,670× main at 96.
  - 48 and 64 MiB: only the forward DFA fits, giving 130–1,250×, the same in every arm.
  - 72 MiB: G and G + I12 admit the reverse DFA and run at 0.97–1.03× main at 96, which is 125–1,300× faster than
    main's own fallback at 72. Main and I12 stay on the fallback.
- **10-bounded-repeat/context:** no arm changes it at any budget.
  - 32 MiB: 15–21×.
  - 48 MiB: 9.6–16.8×.
  - 64 MiB: Java and Trino 5.6–7.4×; RE2 and Rust 1.18–1.57×.
  - 72 MiB: 1.15–1.56× with no resets.
- **url/search, english-15, 12-dictionary:** flat at every budget in every arm.
- **Newly admitted routes:** no newly admitted DFA is slower than the fallback it replaces.
- **english-10 on main, 32 against 96 MiB:**
  - search: 1.04–1.06 on r9g, 0.95–1.00 on r8i. Rust search: 1.059 native and 1.042 safe on r9g.
  - per-line find: 0.93–1.03 on r9g, 1.04 on r8i.
  - count: 0.40–0.60 on both platforms.
  - Trino url/search safe: 0.997 on r9g, 0.999 on r8i.
- The arms carried the prototypes from before review. The review fixes only make I12's pointer table smaller and
  restore main's many-match gate. On the fixed branches, the 96 MiB checks and edges are unchanged.

## Column (a): today, with pills

- **overlapping-words, pill 96 MiB.**
  - Below about 92 MiB its reverse longest-match DFA can't initialize, and below 66.73 MiB its tagged capture
    route goes too. Captures then run on the NFA, 130–1,700× slower (Trino on r8i is the worst).
  - Below 26.92 MiB it doesn't compile.
  - The C++ RE2 comparison: upstream runs this row on its NFA at its 8 MiB default (0.98 MB settled). It would
    need about 55–60 MiB to run it on DFAs.
- **10-bounded-repeat/context, pill 96 MiB.** It slows at 72 MiB and below in every language. Its states really
  fill about 30 MB.
- **english-10, rescue rule.**
  - Below 39.88 MiB (the program's need) RE2, Java and Rust compile english-10 as a program-less literal set (I10,
    #205), so the pattern never fails to compile. Today's speed also needs the forward DFA, which initializes only
    from 81.86 MiB.
  - On the rescue route, search is 4–6% slower on r9g and per-line find 4% slower on r8i, so those rows get the
    96 MiB pill.
  - Count is 1.7–2.5× faster on the rescue route than on today's program route. Count rows run at the default
    with no pill.
  - Trino already uses the literal set at every budget.
- **url/search:** no pill. It is flat at 32 MiB in every language, Trino safe mode included.

## Column (b): with the G + I12 prototypes

The prototypes are local branches on main da1807c8d: G e08c79941, I12 b7101fdb1, G + I12 3880845c5. Details are in
`gate/PROTO.md`.
- **G** sizes the setup gate's worst-case DFA state honestly: at most min(P, L+1) mark slots, plus an
  upstream-style stack for reversed programs.
  - It keeps the 20-state reservation, which is upstream's performance floor; correctness needs 3.
  - It keeps main's gate for many-match DFAs. Their true bound, L+1+M, is now stated in the code, but the gate
    doesn't need it, because states are charged at their real size.
- **I12** starts DFA state tables at 256 states and grows them, charged, never past today's size. Programs of up
  to 256 instructions are unchanged.
  - Its native pointer table leaves room for the tables' growth. Without that, the review found budget windows
    where the DFA reset forever.
- **Checks:**
  - Two independent correctness reviews (Opus and Codex).
  - Bound fuzzing over about 49 million DFA searches.
  - 366–367 targeted tests per branch in both memory modes.
  - Identical search results throughout.

What changes:
- **overlapping-words:** pill **72 MiB**, measured at main's 96 MiB speed. The row leaves the pill list only with
  I18 (next round).
- **Retained memory at 96 MiB:**
  - overlapping-words 50.3 → 32.9 MB (russian 53.0 → 35.7), 12-dictionary 8.1 → 3.8 MB, 03-date 2.9 → 1.3 MB.
  - Over 192 large-program rows the sum falls from 1,035.5 to 788.9 MiB, with no resets and the same state counts.
- **No published row newly fits under 32 MiB,** so the default stays 32 in both columns.

## Losses this trades, and why

- **Patterns that compiled at 96 MiB and don't at 32:**
  - None among published rows: overlapping-words compiles down to 26.92 MiB, and english-10 has the rescue route.
  - An unpublished pattern that needs 32–96 MiB now throws `RegexpCompileMemoryLimitException`. Since #206 that
    exception states the budget the pattern needs ("needs a larger memory budget, at least N MiB") wherever a
    bound can be proven.
- **Rows that slow at the default:** only the pill rows above. No unpilled published row slows.
- **BUDGET losses (ideas built and dropped for budget reasons):**
  - english-10 root skip (O-14): built, then dropped, because its longest-match DFA can't initialize beside it.
    It is nearly worthless after B3.
  - I13: the tagged-alternation program at compile, set aside because no setup is deferred to first match.
  - I15a: a 3-state gate, set aside on analysis as likely to thrash. It comes back only if a published row is left
    below its edge and a 3-vs-20 A/B is no worse than the NFA fallback.

## New finding for later

On main da1807c8d, english-10 count is 1.7–2.5× faster on the program-less literal set than on its program, at
any budget. That reverses the I10 option A result measured on cf8537076, probably because #210 added dense
literal-set rows. Using the literal set even when the program fits (I10 option A, swap-always) is worth
measuring again.

## Not decided here

- The reset policy is unchanged; nothing in this memo needs it to change.
- The usage API (pattern and matcher retained bytes) and the report's per-row memory columns are separate
  proposals (PR-3, PR-4).
- Next round, approved: I11 (DFAs on packed instructions, with the lowered compile charge), I18 (class-run plan
  for overlapping-words captures), and an e4 measurement (the tagged capture DFA without upstream's
  512-instruction guard).
