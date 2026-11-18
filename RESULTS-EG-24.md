# RESULTS-EG-24: #282 rebased (F′) against main (M), r8i row 7 cause and run readers (D-cell)

Untimed diagnostic round. Every number here comes from a profiled or counter fork, 3 forks per leg, and none is a timing verdict.
Hosts: r9g.large (Graviton4) and r8i.large (Xeon 6975P-C), one each. Legs: M `0db30b513`, F′ `3b1646d05`, and A `bdf26dc33` / F `d03bdc07d` on r8i rows 6 and 7.
All 120 spec perfasm forks and all 120 appended perfnorm forks finished: 0 failed, 0 timeouts. Per-fork data is in RESULTS-EG-24.tsv.

## Row 7 decision (coordinator's rule)

**Outcome: row 7 does not block #282. F′ stores and reloads no loop-carried state that M keeps in a register. The cell names no F′-only cause, so the rule's third branch applies: ready, with the r8i row 7 cost stated as "unexplained, not a loop spill".**

- **The r8i step loop with the out-of-line `matchesAsciiFolded` call** (bci 38→210→227, `matchAsciiFoldedUnanchoredAt`):
  - Both legs keep the same three values in stack slots across the call: `cursor`, the input array and one more int. x86 C2 has no callee-saved registers.
  - M: `mov 0x40(%rsp),%ecx` (argument), call, `add 0x40(%rsp),%edx`, then `mov %edx,0x40(%rsp)` at the loop head. F′ has the same sequence on the same slot 0x40.
  - Carried slots: M {0x20 array, 0x40 cursor, 0x48}, F′ {0x28 array, 0x40 cursor, 0x48}. That is 8 carried accesses per pass in each leg.
  - M also spills one more value around the call (`mov 0xc(%rsp)` → `mov %r11d,0x94(%rsp)`), which F′ doesn't. In all, F′ makes 14 stack accesses per pass to M's 16, in 42 instructions to M's 45 (189 B against 206 B).
- **Around the call:** `matchesAsciiFolded` has 2 stores before it in F′ and 3 in M. `findRe2ByteStop` is 7 stores before / 9 loads after in both. `re2WalkNonAscii` is 8/5 in F′ and 7/5 in M.
- **Layout:**
  - F′'s executor is 48–56 B smaller on r8i (3152–3168 B against 3208–3216 B).
  - The call loop's head sits at mod 32 = 9, mod 64 = 41 in F′, against 12 / 44 in M.
  - In one fork per leg, the cycles fall instead on a 24-instruction call-free form of the step loop.
- **Counters (perfnorm, r8i):** F′/M instructions 0.9969, cycles 1.0037, ns/op 1.0033.
- **The A/F link reproduces EG-21's cost, but the rebased pair carries little of it:**
  - Row 7: F/A instructions 1.0030, cycles 1.0184. Row 6: F/A 1.0005 and 1.0219.
  - M/F′ show 1.0037 (row 7) and 1.0053 (row 6) in cycles.
  - A and F have the same carried slots {0x18, 0x20, 0x40, 0x48} and the same call-site traffic. Their loop heads are at the same mod 32 = 7, mod 64 = 39 in both.
  - So nothing in checks 1–4 explains A/F's 1.8% either.

## Wrong-ifs

| Wrong if | Result |
|---|---|
| Row 7 r8i: F′ stores and reloads `cursor`, `scanned` or retry state in the step loop where M keeps it in a register | **Not tripped.** Same carried slots in both legs; F′ has 2 fewer stack accesses per pass. |
| A run-reader row (3, 10, 8, 14) fails the loop-shape rule against M on either platform | **Not tripped.** No F′-only loop-carried slot. Two rows add redundant store-backs of invariant values (below). |
| An inline tree differs between M and F′ in any row | **Not tripped.** Decisions are identical in every row, platform and fork. Only bytecode sizes differ. |
| A/F show no difference on row 7 while M/F′ do, or the reverse | **Tripped, in the reverse sense, in cycles only.** A/F show +1.8% cycles on row 7 (+2.2% on row 6); M/F′ show +0.4% (+0.5%). Main's reader changes moved most of the cost, so EG-21's row 7 no longer describes the rebased PR. In the code checks, A/F and M/F′ show the same pattern: no slot or inline difference. |

## Predictions graded

| Row | Platform | Inline tree | F′-only loop-carried spill | Instructions F′/M (predicted → measured) | Grade |
|---|---|---|---|---|---|
| 7 | r8i | identical ✓ | no ✓ | 1.000–1.003 → **0.9969** | **partial:** shapes as predicted; F′ runs 2 fewer instructions per op than the range allows |
| 7 | r9g | identical ✓ | no ✓ | 1.000–1.003 → **0.9883** | **partial:** F′ runs 1.2% fewer instructions than predicted (cycles 0.9985) |
| 6 | both | identical ✓ | no ✓ | 1.000–1.005 → r8i **0.9960**, r9g **0.9884** | **partial:** below the range on both platforms. On M, row 6 runs the same executor and reader as row 7. |
| 1, 5 | r9g | identical ✓ | no ✓, but the mechanism is gone | 0.975–0.995 → **0.9804 / 0.9804** | **partial:** the ratio hits, but M no longer spills the scan position. On main the scan loops (`re2ByteStop`/`findRe2ByteStop`) have no stack stores in either leg. |
| 1, 5 | r8i | identical ✓ | no ✓; no scan-position spill on x86 in either leg | 0.98–1.00 → **1.0039 / 1.0039** | **partial:** +4 instructions per op. The step loop is 67 instructions against 65. |
| 2 | r8i | identical ✓ | no ✓ (two extra reloads, below) | 0.98–1.00 → **1.0152** | **miss:** cycles 1.0299 |
| 2 | r9g | identical ✓ | no ✓ | 0.98–1.00 → **0.9768** | **miss:** fewer instructions than the range, but cycles 1.0131 |
| 3, 10, 8, 14 | r8i | identical ✓ | no ✓ | 0.995–1.005 → 0.9980, 0.9998, 1.0016, 1.0028 | **hit** (row 14 cycles 1.0191) |
| 3, 10, 8, 14 | r9g | identical ✓ | no ✓ | 0.995–1.005 → 0.9988, **1.0056**, 1.0015, **1.0052** | **partial:** 10 and 14 are just above the range |

Not predicted, flagged for the coordinator (diagnostic, 3 forks per leg):
- **Row 2 cycles F′/M are 1.030 on r8i and 1.013 on r9g.** EG-21 timed row 2 faster on both platforms against the old main.
- **Row 14 r8i cycles are 1.019.**
- A timed check would settle both. This round doesn't time anything.

## 1. Inline trees (LogCompilation, every fork)

The hot executor's C2 task, taken from the nmethod that perfasm shows hot. Every leg's 3 forks give one decision hash, and M = F′ in every row.

| Row | Executor | Decision hash r8i / r9g | Bytecode size changes, M → F′ |
|---|---|---|---|
| 7, 6 | `ScanPlan::matchAsciiFoldedUnanchoredAt` (own nmethod; the JMH stub's call to it is `OUT hot method too big` in both legs) | ee9b680e / ee9b680e | `re2Match` 126 → 131 B |
| 1, 5 | `ScanPlan::matchUnanchoredAt` | 77fad559 / 77fad559 | `re2Match` 126 → 131 B |
| 2 | `ScanPlan::matchUnanchoredAt` | f43b076f / f43b076f | `re2Match` 126 → 131 B |
| 3 | generated `matchUnanchoredAt` | a230c124 / a230c124 | `re2RunMatch` 122 → 127 B |
| 10 | generated `search`, with the generated `matchUnanchoredAt` inlined | dc8501a0 / aa8bf788 | `re2RunMatch` 122 → 127, `runMatch` 59 → 62, generated `matchUnanchoredAt` 236 → 278 B (still IN) |
| 8, 14 | `ScanPlan::matchUnanchoredAt` | fae9e062, d5d1fb70 (both platforms) | `re2Match` 126 → 131 B |

- **Out-of-line calls in the row 6/7 executor (M and F′):** `findRe2ByteStop` (OUT too big), `re2WalkNonAscii` (OUT too big) and `matchesAsciiFolded` (OUT hot method too big). On main, `findRe2RunStop` and `re2CharacterWidth` are gone from this executor.
- **A/F row 7:** `findRe2RunStop` and `re2CharacterWidth` are OUT too big, and `matchesAsciiFolded` is OUT hot method too big. A = F apart from `re2Match` 126 → 131 B.
- **A/F row 6:** Trino's own `ScanPlanRun.match` inlines with no calls (200 → 208 B). On M/F′, row 6 reads through `re2Match` with the same tree as row 7, so it is no longer an independent control for row 7.

## 2. Loop-shape rule (perfasm hot loops)

"Carried" means a slot loaded before it is stored in the loop body, outside the call boundary. Invariant reloads are counted separately.

| Row | r8i | r9g |
|---|---|---|
| 7, 6 | pass: same carried slots {array, cursor, 0x48}; F′ has fewer accesses (above) | pass: 11 carried accesses in the `matchesAsciiFolded` loop in both legs (50 against 53–64 instructions) |
| 1, 5 | pass: step loop with 2 carried accesses in both legs; scan loops have no stores | pass: step loop carries the same 3 slots in both legs; F′ adds 7 intra-pass spill accesses (96 instructions against 93) |
| 2 | pass, with notes: both legs store and reload `cursor` (0x2c) and `operation` (0/0x94) in the step loop. F′ adds a second reload of `operation` (`mov 0x94(%rsp),%r10d` before the step load) and reloads the step reference (`mov 0x3c(%rsp),%r8d`) where M keeps registers. Not new carried slots. | pass: carried 26 against 28; F′ has 22 spill accesses against 14 |
| 3 | pass: carried counts match fork for fork (0/0, 3/3, 4/4, 14/14) | pass: identical (reader loop 78 against 81 instructions, 0 carried) |
| 10 | pass: 0 carried in both legs | pass: 0 carried in both legs |
| 8 | pass: identical slot sets in the reader loops | pass, with a note: F′'s reader loop reloads slot 0x24, an invariant tested at bci 20, into w17. It call-saves it around `re2WalkNonAscii` and stores it back unchanged (`stp w1, w17, [sp, #0x20]` at bci 75), so that's 2 more accesses per pass. Not `cursor`/`scanned` or a scan position. |
| 14 | pass, with a note: in F′'s step loop (200 against 186 instructions), the input end (0xa4, M's 0xa0) and slot 0x4 are reloaded at the back edge (`mov 0x4(%rsp),%r9d`, `mov 0xa4(%rsp),%eax`). Both are stored back unchanged (`mov %eax,0xa4(%rsp)`, `mov %r9d,0x4(%rsp)`) with no write in between, so they are redundant store-backs of invariants. The position slot 0x10 is stored and reloaded in both legs (`mov %r8d,0x10(%rsp)` at `re2ByteStop@45`); F′ adds one more reload of it at `re2ScanNonAscii@6`. | pass: 0 carried in both legs |

## 3. Spill and reload around out-of-line calls (rows 6 and 7)

Stores in the straight line before each call and loads after it, the same in all 3 forks of each leg.

| Platform | Leg | matchesAsciiFolded | findRe2ByteStop | re2WalkNonAscii | findRe2RunStop | re2CharacterWidth |
|---|---|---|---|---|---|---|
| r8i | M | 3/0 | 7/9 | 7/5 | - | - |
| r8i | F′ | 2/0 | 7/9 | 8/5 | - | - |
| r8i | A (row 7) | 3/0 | - | - | 8/1 | 2/0 |
| r8i | F (row 7) | 3/0 | - | - | 8/1 | 2/0 |
| r8i | A / F (row 6) | 4/0 | - | - | - | - |
| r9g | M and F′ | 3/0 | 8/9 | 8/8 | - | - |

The 0 loads after `matchesAsciiFolded` come from the window ending at the result test. Its reloads (`cursor`, the array) are counted in the loop's carried slots above. That traffic is call-save traffic in both legs, not a recurrence spill.

## 4. Code size and loop alignment

Executor main-code bytes, and the hottest innermost loop per fork (instructions / bytes / head mod 32, mod 64).

| Platform | Row | M B | F′ B | M hot loop | F′ hot loop |
|---|---|---|---|---|---|
| r8i | 7 | 3208–3216 | 3152–3168 | 24/96/16,48; 45/206/12,44; 45/206/12,44 | 42/189/9,41; 24/96/0,32; 42/189/9,41 |
| r8i | 6 | 3208–3216 | 3152–3168 | 45/206/12,44 ×3 | 24/96/0,32; 42/189/9,41; 24/96/0,32 |
| r8i | 1, 5 | 6424 | 6504 | 65/283/30,30 ×3 | 67/296/23,23 ×3 |
| r8i | 2 | 6544–6744 | 6600 | 65/288/18,18; 65/288/18,18; 67/304/2,34 | 65/298/22,22 ×3 |
| r8i | 3 | 4784 | 4816–4824 | 66/286/16,16; 66/286/16,16; 99/399/0,32 | 64/284/16,48; 64/284/16,48; 101/415/16,48 |
| r8i | 10 | 5824–5832 | 5880–5896 | 70/316/16,48; 70/316/16,48; 30/137/16,16 | 30/136/0,32; 69/312/28,60; 69/312/28,60 |
| r8i | 8 | 3816–3960 | 3928–4088 | 145/641/31,31; 145/641/31,31; 155/675/21,53 | 58/252/22,54; 155/677/19,51; 160/708/4,36 |
| r8i | 14 | 5176–5184 | 5456–5464 | 123/523/28,28; 186/864/28,28 ×2 | 134/609/19,19; 200/980/19,19 ×2 |
| r9g | 7 | 2536 | 2536–2552 | 64/256/12,44 ×2; 53/212/12,44 | 50/200/12,44 ×2; 133/532/12,12 |
| r9g | 6 | 2536 | 2544–2560 | 53/212/12,44; 64/256/12,44; 53/212/12,44 | 50/200/12,44; 133/532/12,12; 123/492/12,12 |
| r9g | 1, 5 | 5456 | 5496–5504 | 93/372/4,4 ×3 | 96/384/4,36 (one fork 24,56) |
| r9g | 2 | 4616 | 4760 | 104/416/8,8 ×3 | 106/424/4,36 ×3 |
| r9g | 3 | 3472 | 3480 | 81/324/16,48 ×3 | 78/312/16,48 ×3 |
| r9g | 10 | 5272 | 5360 | 34/136/16,48 ×3 | 34/136/16,48 ×3 |
| r9g | 8 | 2768 | 2800–2840 | 124/496/12,12 ×2; 141/564/16,16 | 58/232/28,60; 126/504/12,12; 140/560/20,20 |
| r9g | 14 | 3424–3536 | 3488–3536 | 148/592/8,8; 92/368/8,8; 142/568/20,20 | 85/340/12,12; 151/604/8,8; 85/340/12,12 |
| r8i A/F | 7 | A 4056–4064 | F 4144–4152 | A 23/90/13,45 ×2; 46/209/7,39 | F 45/204/7,39 ×2; 72/282/31,31 |
| r8i A/F | 6 | A 4000–4008 | F 4056–4072 | A 58/245/13,45 ×2; 47/209/13,45 | F 47/210/13,45 ×2; 58/246/13,45 |

Where a leg's forks name different hottest loops, the cycles split between two forms of the same loop (fork state), not between legs.

## 5. Instructions and cycles per op (perfnorm forks, deviation 1)

Medians of 3 unmultiplexed forks (`cycles,instructions`). All 120 forks' jvmArgs match the template.

| Platform | Row | M ins/op | F′ ins/op | F′/M ins | M cyc/op | F′ cyc/op | F′/M cyc | F′/M ns/op | F/A ins | F/A cyc |
|---|---|---|---|---|---|---|---|---|---|---|
| r8i | 7 | 600.4 | 598.5 | 0.9969 | 111.7 | 112.1 | 1.0037 | 1.0033 | 1.0030 | 1.0184 |
| r8i | 6 | 602.6 | 600.2 | 0.9960 | 115.7 | 116.3 | 1.0053 | 1.0052 | 1.0005 | 1.0219 |
| r8i | 1 | 1018.0 | 1022.0 | 1.0039 | 206.2 | 207.2 | 1.0048 | 1.0048 | - | - |
| r8i | 2 | 1117.6 | 1134.5 | 1.0152 | 209.0 | 215.3 | 1.0299 | 1.0299 | - | - |
| r8i | 5 | 1018.3 | 1022.3 | 1.0039 | 203.5 | 203.3 | 0.9991 | 0.9993 | - | - |
| r8i | 3 | 521.0 | 519.9 | 0.9980 | 115.7 | 116.2 | 1.0042 | 1.0049 | - | - |
| r8i | 10 | 561.3 | 561.1 | 0.9998 | 106.2 | 106.5 | 1.0023 | 1.0014 | - | - |
| r8i | 8 | 667.0 | 668.0 | 1.0016 | 122.3 | 122.5 | 1.0020 | 1.0021 | - | - |
| r8i | 14 | 427.7 | 428.9 | 1.0028 | 68.3 | 69.6 | 1.0191 | 1.0192 | - | - |
| r9g | 7 | 627.2 | 619.9 | 0.9883 | 118.7 | 118.5 | 0.9985 | 0.9980 | - | - |
| r9g | 6 | 632.4 | 625.1 | 0.9884 | 124.2 | 123.9 | 0.9975 | 0.9976 | - | - |
| r9g | 1 | 1062.3 | 1041.4 | 0.9804 | 174.7 | 173.6 | 0.9937 | 0.9934 | - | - |
| r9g | 2 | 1345.7 | 1314.5 | 0.9768 | 264.7 | 268.2 | 1.0131 | 1.0132 | - | - |
| r9g | 5 | 1062.3 | 1041.5 | 0.9804 | 175.9 | 174.5 | 0.9925 | 0.9924 | - | - |
| r9g | 3 | 484.1 | 483.5 | 0.9988 | 76.0 | 75.1 | 0.9891 | 0.9892 | - | - |
| r9g | 10 | 526.6 | 529.6 | 1.0056 | 82.5 | 83.0 | 1.0056 | 1.0058 | - | - |
| r9g | 8 | 612.3 | 613.2 | 1.0015 | 106.9 | 105.3 | 0.9857 | 0.9855 | - | - |
| r9g | 14 | 365.1 | 367.0 | 1.0052 | 53.3 | 53.8 | 1.0083 | 1.0081 | - | - |

Bimodal forks:
- **Row 2:** r8i M 1117.6 / 1117.5 / 1147.8 and F′ 1133.6 / 1164.4 / 1134.5 instructions per op; r9g M 1345.8 / 1300.9 / 1345.7 and F′ 1314.1 / 1359.3 / 1314.5. The medians take each leg's common state.
- **Row 14 r8i cycles:** M 68.1–68.6, F′ 65.4–70.3.
- **Row 10 r8i:** one F′ fork ran 98.3 cycles against 106.5 for the others.
