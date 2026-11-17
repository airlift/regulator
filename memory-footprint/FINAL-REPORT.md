# Memory footprint and DFA budget campaign: final report (work under the usage cap)

Written 2026-11-10 17:5x PST. Campaign launched 2026-11-08 08:5x PST. Manager thread `mcp:launch:588a546c…`; coordinator thread 99bebe93.

## Outcome

- **The default memory budget is 32 MiB,** down from 96 MiB (#239, decided by Dain on Nov 9). Every published benchmark row compiles at 32 MiB.
- **Only one benchmark entry still needs its own budget:** `curated/10-bounded-repeat/context` and its compile sibling, at 96 MiB. The row fills about 30 MB of DFA states and slows at 72 MiB and below (15–21× at 32 MiB). That is real match-time state, not a gate or a charge artifact, so it is recorded as the known remaining pill.
- **overlapping-words** (english and russian) left the list with #244. The class-run counter decides its captures with no program, and retained memory fell from 53–55 MB to about 34 KB. Its rows run 0.75–0.92 (english) and 0.47–0.57 (russian) of main's time at 96 MiB.
- **english-10** search and compile left the list with #246. Packed instructions and the measured charge let it compile its program at 32 MiB, on the same route as at 96, and it retains 14.7 MB instead of 26.1 MB.
- **The Rebar engine runner is at budgets-3,** built from main 9e284187f. `--version` prints `regulator 2.1-SNAPSHOT+budgets-3`; Main.java sha256 cbfa8814…, jar a0943e92…. The smoke test passed 20/20.

## Merged PRs

| PR | What |
|---|---|
| #205 | Compile large literal sets without a program (the I10 rescue route) |
| #206 | Report the memory budget a rejected pattern needs |
| #210 | Give shallow literal-set states dense rows |
| #229 | Start DFA state tables small and grow them, with a real-worst-case setup gate (G+I12) |
| #238 | Run budgeted benchmark rows at their own budget (pills, comparators included) |
| #239 | Lower the default memory budget to 32 MiB |
| #244 | Decide class-run alternation captures by count (I18; overlapping-words off the list; runner budgets-2) |
| #246 | Keep program instructions as packed words (I11 plus the measured charge; english-10 off the list; runner budgets-3) |

## #246, the last PR under the cap

- **Reviews:**
  - Adversarial review 1 is Complete. X1, M1, M2, M3 and L1 were applied and their repair checks passed, and K1–K4 are proven.
  - Polish 1 is Complete, with six findings applied.
  - Sol delegates audited every repair delta. The coordinator skipped the extra Fable deep review because round 1 already had Fable on both scopes.
- **M3, found during the rebase:** per-call `Prog.Inst` views in the compiler's clone loops allocated 347 MB on a cold nested-loop Trino compile, against main's 61 MB.
  - The fix reads and patches words directly and deletes the views. The same compile now allocates 45 MB.
  - Compiled program words are identical before and after the fix, and the match-time classes are byte-identical.
- **Disclosures in the body:**
  - english-10 compile at 32 MiB is 1.47× main at 32, because it now compiles the program rather than taking the literal-set route.
  - Main's 2,000 B fixed charge undercounted small programs; this PR fixes that.
  - The flatten bound is proven at flatten ratios ≤1 and ≥64/41. Between them, the worst constructed case peaks at 0.959 of the share.
  - The compile timing (2–9% faster) comes from round 3's build, 82f903040. The body says what changed since, and that the match path is unchanged.
- **Copilot:** it found a fixture test that decoded the same word on both sides; that is fixed. It asked for a tiny-program peak test; that was answered and not added, because allocation counts run 18–35% over the charge (they include garbage) and JOL is not a test dependency.

## Spend

AWS, against the $8 hold: **$4.252**.

| Run | $ |
|---|---|
| budget sweep | 0.780 |
| I10 A/B | 0.308 |
| I10-B A/B | 0.207 |
| Trino noise run | 0.023 |
| memo A/B | 1.268 |
| #229 confirm, both launches | 0.241 |
| round 3 | 1.214 |
| e4 pre-PR A/B | 0.211 |

Every host was terminated and every guard exited. Raw output is in S3; see each benchmark README. Details are in `SPEND.md`.

## Held: e4 (until after Sat Nov 14 02:00 PST)

- **What it is:** the tagged capture DFA without upstream's 512-instruction list-head guard. It is a recorded engine decision citing prog.cc:644-652; our tagged program is charged and reset-bounded, and falls back to BitState.
- **Pre-PR A/B accepted ($0.211):**
  - overlapping-words/ascii runs at 0.42 (r9g) and 0.32 (r8i) of main's time, and no row blocks.
  - One constructed thrash row is 2.2–2.6× slower at 256 KiB.
  - #237's judge-once slow-reset skip cuts that row's many-match resets from 168,039 to 23, and goes in as its own commit.
- **Branches:** `user/dain/mf-e4` c94d74c6f, and `user/dain/mf-e4-main` fc86048e1 (replayed on 78a005e71). Both are local and need a rebase onto 9e284187f.
- **Remaining before a PR:**
  - the slow-reset commit;
  - the javap/inlining argument for it (as #237), or a confirm under $0.05 at 32 MiB on both platforms;
  - the REGULATOR_DECISIONS bullet (draft in `~/.codex/delegates/2026-11-10/mf-e4-guard-trace.gpt-6.1-sol.md`);
  - body disclosures: the thrash row at both budgets, and the r8i email cell as identical-code noise;
  - adversarial and polish reviews, rule 13, the native gate.
- **Notes:** `E4-AB-BRIEF.md` and `benchmarks/e4-ab-2026-11-10/`.

## Open items, not under the cap

- **I10A-rev re-measure:** the swap-always literal-set option, set aside in I10. Re-measure it now that #210's dense rows exist.
- **Rust `x{0}` groups:** RustRegexp counts no group inside `x{0}`, so `(a)(b){0}` has 1 group where RE2 and the JDK have 2. That is consistent with regex-syntax 0.8.11 `hir/mod.rs:390`, but unverified against the crate's `captures_len`, and undocumented in RUST_REGEXP.md.

## Where things are

- **Notes:**
  - this bundle (`BUNDLE.md`, `MEMO.md`, `PREDICTIONS.md`, `RESULTS.tsv`, `IDEAS.md`, `benchmarks/`);
  - `~/AgentNotes/regulator/user/dain/mf-i18/` and `mf-i11/` (reviews and BUNDLE).
- **Rebar runner record:** `hyperscan-gap-techniques/BUILD.md`, plus `~/AgentScratch/regulator/user/dain/mf-i11/rebar-window/`.
