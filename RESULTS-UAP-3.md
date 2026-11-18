# RESULTS-UAP-3: r8i loop-shape D-cell for F1 and F3 (PR #279, untimed)

One r8i.large Spot host (us-east-2c, Intel Xeon 6975P-C, Temurin 25.0.4+7), 2026-11-18 00:56–01:17 PST. 36 of 36 forks
ran, none failed, none dropped. Every number is a counter or a disassembly; µs/op is diagnostic only. Spec:
AWS-JOBS-UAP-3.md (predictions unchanged). "Fast" below means `stateOffset` stays in a register across the byte loop;
"slow" means the loop stores the state from the transition load to a stack slot and reloads it for the next byte's index.

## Headline

- **The tier-4 `count` is not the switch.** C′ was fast at counts 54 and 70 and slow at 40, 43 and 45. F3 and D2's C′ ran
  the reverse of UAP-2's rule: every fork queued below 40 was slow (F3: 37, 39, 39; D2 C′: 31, 32, 39), and every fork at 40
  or above was fast (F3: 40, 40, 72, 104; D2 C′: 40). A was fast in all 7 forks, at counts 32–70.
- **F1 on D1 has one shape in all 7 forks:** the inlined helper loop, unrolled ×2, 33 instructions per 2 bytes (16.5 per byte),
  `stateOffset` in a register, **7.36 cycles per byte against A's 8.93 (−18%)**, at 4,436–4,608 invocations. But the
  loop has two `cmp eax,DWORD PTR [rsp]` (the transitions array length for the bounds check, reloaded from the stack each
  byte).
- **On D2 (match-dense), F1's helper loop spills `stateOffset`** to `[rsp+0x5c]` (and `position` to `[rsp+0x54]`) in all 4
  forks. The compile path changes there: `searchMany` gets an OSR compile first, then a standard compile at 44–189
  invocations, not ~4,353.
- D2 F1/C′ per byte: instructions 0.917, cycles 1.032 (inside 1.15). Both C′ perfnorm forks drew C′'s slow D2 shape. C′'s one
  fast D2 fork (perfasm, 113.9 µs) beat all four F1 D2 forks (137–150 µs) by about 20%.
- **Verdicts by the spec's rule: F1 NOT, F3 NOT** (below).

## Per fork

Count = the `count` attribute of the standard (non-OSR) C2 `<task>` for `Dfa searchMany`, compared with 40. OSR = the
earlier OSR compile (bci 275 in every case). No `make_not_entrant` or `uncommon_trap` event named any `searchMany`
compile in any fork. Loop = the hot byte loop. In perfasm forks it is the innermost loop holding the hottest
instruction (for F1, the helper loop). In perfnorm forks it is the loop in the fork's own assembly whose source frames
match the same leg's perfasm hot loop (Jaccard 0.88–1.0); perfnorm has no samples. Per byte = loop instructions ÷ bytes
per iteration. Floor = loop instructions per byte × 32,768; "ok" means instructions per op ≥ floor.

| Fork | µs/op | Std tier-4: id, count, backedges | OSR | Hot nmethod | Loop: insns (bytes/iter), per byte, mod 64 | `[rsp]` L/S | stateOffset | Cycles/op | Insns/op | Cyc/B | IPC | r0403/op | Floor (loop/B × 32,768) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| D1 A-perfasm-1 | 74.8 | 863, **37** (<40), 580,608 | 861 | 863 std | 42 (1), 42.0, 27 | 7/3 | register | - | - | - | - | - | - |
| D1 A-perfasm-2 | 74.6 | 883, **38** (<40), 614,400 | 881 | 883 std | 42 (1), 42.0, 27 | 7/3 | register | - | - | - | - | - | - |
| D1 A-perfasm-3 | 74.5 | 861, **38** (<40), 605,184 | 859 | 861 std | 42 (1), 42.0, 27 | 7/3 | register | - | - | - | - | - | - |
| D1 A-perfasm-4 | 74.5 | 841, **40** (>=40), 624,640 | 838 | 841 std | 42 (1), 42.0, 14 | 7/3 | register | - | - | - | - | - | - |
| D1 C′-perfasm-1 | 118.5 | 840, **40** (>=40), 480,256 | 838 | 840 std | 49 (1), 49.0, 23 | 11/8 | spilled [rsp+0x34] | - | - | - | - | - | - |
| D1 C′-perfasm-2 | 76.2 | 875, **54** (>=40), 555,008 | 872 | 875 std | 48 (1), 48.0, 23 | 9/7 | register | - | - | - | - | - | - |
| D1 C′-perfasm-3 | 117.4 | 869, **45** (>=40), 541,696 | 866 | 869 std | 49 (1), 49.0, 23 | 11/8 | spilled [rsp+0x34] | - | - | - | - | - | - |
| D1 C′-perfasm-4 | 75.7 | 827, **39** (<40), 516,475 | 825 | 827 std | 41 (1), 41.0, 28 | 7/2 | register | - | - | - | - | - | - |
| D1 F1-perfasm-1 | 63.6 | 859, **4436** (>=40), 13,316 | - | 859 std (helper inlined) | 33 (2), 16.5, 0 | 2/0 | register | - | - | - | - | - | - |
| D1 F1-perfasm-2 | 63.5 | 915, **4438** (>=40), 13,322 | - | 915 std (helper inlined) | 33 (2), 16.5, 0 | 2/0 | register | - | - | - | - | - | - |
| D1 F1-perfasm-3 | 63.7 | 866, **4436** (>=40), 13,316 | - | 866 std (helper inlined) | 33 (2), 16.5, 0 | 2/0 | register | - | - | - | - | - | - |
| D1 F1-perfasm-4 | 63.6 | 895, **4608** (>=40), 13,832 | - | 895 std (helper inlined) | 33 (2), 16.5, 0 | 2/0 | register | - | - | - | - | - | - |
| D1 F3-perfasm-1 | 114.4 | 790, **39** (<40), 565,625 | 788 | 790 std | 52 (1), 52.0, 18 | 11/9 | spilled [rsp+0x68] | - | - | - | - | - | - |
| D1 F3-perfasm-2 | 115.2 | 876, **39** (<40), 646,144 | 874 | 876 std | 52 (1), 52.0, 18 | 11/9 | spilled [rsp+0x68] | - | - | - | - | - | - |
| D1 F3-perfasm-3 | 73.2 | 850, **40** (>=40), 440,320 | 847 | 850 std | 45 (1), 45.0, 27 | 8/6 | register | - | - | - | - | - | - |
| D1 F3-perfasm-4 | 116.0 | 854, **37** (<40), 550,912 | 853 | 854 std | 52 (1), 52.0, 18 | 11/9 | spilled [rsp+0x68] | - | - | - | - | - | - |
| D1 A-perfnorm-1 | 72.4 | 758, **32** (<40), 763,609 | 755 | 758 std | 42 (1), 42.0, 27 | 7/3 | register | 285,812 | 1,381,311 | 8.722 | 4.83 | 6.5 | 1,376,256 ok |
| D1 A-perfnorm-2 | 74.5 | 763, **67** (>=40), 1,274,112 | 760 | 763 std | 42 (1), 42.0, 13 | 7/3 | register | 292,490 | 1,379,869 | 8.926 | 4.72 | 6.4 | 1,376,256 ok |
| D1 A-perfnorm-3 | 74.7 | 775, **70** (>=40), 1,277,419 | 771 | 775 std | 42 (1), 42.0, 13 | 7/3 | register | 293,549 | 1,379,531 | 8.958 | 4.70 | 6.3 | 1,376,256 ok |
| D1 C′-perfnorm-1 | 72.7 | 762, **39** (<40), 594,428 | 759 | 762 std | 41 (1), 41.0, 28 | 7/2 | register | 284,861 | 1,346,284 | 8.693 | 4.73 | 5.4 | 1,343,488 ok |
| D1 C′-perfnorm-2 | 112.9 | 798, **43** (>=40), 742,400 | 795 | 798 std | 49 (1), 49.0, 23 | 11/8 | spilled [rsp+0x34] | 447,093 | 1,616,158 | 13.644 | 3.61 | 6.6 | 1,605,632 ok |
| D1 C′-perfnorm-3 | 72.9 | 767, **70** (>=40), 383,096 | 764 | 767 std | 48 (1), 48.0, 23 | 9/7 | register | 284,344 | 1,575,894 | 8.678 | 5.54 | 8.4 | 1,572,864 ok |
| D1 F1-perfnorm-1 | 61.3 | 812, **4608** (>=40), 13,832 | - | 812 std (helper inlined) | 33 (2), 16.5, 0 | 2/0 | register | 241,825 | 544,696 | 7.380 | 2.25 | 4.0 | 540,672 ok |
| D1 F1-perfnorm-2 | 61.3 | 818, **4608** (>=40), 13,832 | - | 818 std (helper inlined) | 33 (2), 16.5, 0 | 2/0 | register | 240,788 | 544,074 | 7.348 | 2.26 | 0.2 | 540,672 ok |
| D1 F1-perfnorm-3 | 61.3 | 813, **4553** (>=40), 13,667 | - | 813 std (helper inlined) | 33 (2), 16.5, 0 | 2/0 | register | 241,093 | 544,228 | 7.358 | 2.26 | 3.9 | 540,672 ok |
| D1 F3-perfnorm-1 | 71.5 | 784, **40** (>=40), 573,964 | 782 | 784 std | 45 (1), 45.0, 27 | 8/6 | register | 280,562 | 1,479,538 | 8.562 | 5.27 | 6.1 | 1,474,560 ok |
| D1 F3-perfnorm-2 | 73.2 | 762, **104** (>=40), 567,941 | 760 | 762 std | 48 (1), 48.0, 23 | 9/7 | register | 288,031 | 1,576,072 | 8.790 | 5.47 | 8.6 | 1,572,864 ok |
| D1 F3-perfnorm-3 | 71.7 | 816, **72** (>=40), 848,675 | 814 | 816 std | 45 (1), 45.0, 27 | 8/6 | register | 280,175 | 1,477,385 | 8.550 | 5.27 | 6.0 | 1,474,560 ok |
| D2 C′-perfasm-1 | 138.2 | 802, **31** (<40), 529,408 | 798 | 802 std | 42 (1), 42.0, 4 | 8/3 | spilled [rsp+0x54] | - | - | - | - | - | - |
| D2 C′-perfasm-2 | 113.9 | 752, **40** (>=40), 454,210 | 748 | 752 std | 41 (1), 41.0, 50 | 6/2 | register | - | - | - | - | - | - |
| D2 F1-perfasm-1 | 149.8 | 797, **44** (>=40), 308,224 | 792 | 797 std (helper inlined) | 44 (2), 22.0, 16 | 11/3 | spilled [rsp+0x5c] | - | - | - | - | - | - |
| D2 F1-perfasm-2 | 144.1 | 806, **189** (>=40), 1,498,112 | 803 | 806 std (helper inlined) | 41 (2), 20.5, 0 | 10/2 | spilled [rsp+0x5c] | - | - | - | - | - | - |
| D2 C′-perfnorm-1 | 136.5 | 727, **32** (<40), 450,560 | 724 | 727 std | 42 (1), 42.0, 4 | 8/3 | spilled [rsp+0x54] | 541,828 | 2,145,043 | 16.535 | 3.96 | 23.1 | 1,376,256 ok |
| D2 C′-perfnorm-2 | 137.4 | 732, **39** (<40), 470,016 | 726 | 732 std | 42 (1), 42.0, 4 | 8/3 | spilled [rsp+0x54] | 544,776 | 2,144,203 | 16.625 | 3.94 | 23.5 | 1,376,256 ok |
| D2 F1-perfnorm-1 | 137.4 | 736, **143** (>=40), 658,432 | 730 | 736 std (helper inlined) | 41 (2), 20.5, 0 | 10/2 | spilled [rsp+0x5c] | 542,819 | 1,948,478 | 16.566 | 3.59 | 21.5 | 671,744 ok |
| D2 F1-perfnorm-2 | 146.8 | 737, **147** (>=40), 446,871 | 731 | 737 std (helper inlined) | 41 (2), 20.5, 16 | 10/2 | spilled [rsp+0x5c] | 578,909 | 1,986,513 | 17.667 | 3.43 | 26.6 | 671,744 ok |

F1 helper (inlined, `inline_success reason='inline (hot)'`, in every F1 fork): on D1, `stepSelfLoopRearmBytes` had its own
C2 compiles too, an OSR compile at about 23 invocations and a standard one at 95–128. On D1 the outer `searchMany` body
took 0.07–0.15% of cycles (the hot method's share minus the helper loop's). On D2 the helper loop took 31–33% and the rest
of `searchMany` about 60% (method share 91.8–92.4%).

The loops are in `ana/D*-*.loop.txt` in the archive. The fast A loop (42), the fast C′ loop (41) and the slow C′ loop (49,
spill `[rsp+0x34]`) are UAP-2's shapes. C′ and F3 also show a second fast loop of 48 instructions (`C′-perfasm-2`,
`C′-perfnorm-3`, `F3-perfnorm-2`): 9 loads and 7 stores to the stack per byte, but the state stays in `ecx`/`r14d`. F3's
fast loop is 45 instructions and its slow loop 52 (spill `[rsp+0x68]`).

## Summary lines

**Mode check** (fast = `stateOffset` in a register):

| Leg | Count < 40 | Count ≥ 40 | Shape followed UAP-2's rule (< 40 fast, ≥ 40 slow)? |
|---|---|---|---|
| A (D1) | 4 forks (32, 37, 38, 38): all fast | 3 forks (40, 67, 70): all fast | no: 3 fast forks at ≥ 40 |
| C′ (D1) | 2 forks (39, 39): both fast | 5 forks: 3 slow (40, 43, 45), 2 fast (54, 70) | no: 2 of 5 ≥ 40 forks fast |
| F1 (D1) | none | 7 forks (4,436–4,608): one shape, the helper loop with the state in a register | n/a: one shape |
| F3 (D1) | 3 forks (37, 39, 39): all slow | 4 forks (40, 40, 72, 104): all fast | no: the reverse in all 7 |
| C′ (D2) | 3 forks (31, 32, 39): all slow | 1 fork (40): fast | no: the reverse in all 4 |
| F1 (D2) | none | 4 forks (44, 143, 147, 189): all spill in the helper loop | n/a: one shape |

The shape did not follow the count. In 16 of the 25 non-F1 forks it went against UAP-2's rule (A 3, C′ D1 2, F3 7, C′ D2 4).

**F1 trigger:** D1 counts 4,436, 4,438, 4,436, 4,608 (perfasm) and 4,608, 4,608, 4,553 (perfnorm).
**The trigger held in every D1 F1 fork (count ≥ 40, within 2–6% of 4,353). It did not hold on D2**, where all four F1 forks
compiled `searchMany` at 44–189 invocations after an OSR compile (D2's per-match exits put the backedges back in the outer
loop).

**D2** (perfnorm medians of 2 forks; exits per byte from the spec's dain-1 count):

| Leg | Instructions/byte | Cycles/byte | Exits/byte |
|---|---|---|---|
| C′ | 65.45 | 16.58 | 0.242 |
| F1 | 60.04 | 17.12 | 0.242 |
| **F1/C′** | **0.917** | **1.032** | |

- Both C′ perfnorm forks ran C′'s slow D2 loop (42 instructions, spill `[rsp+0x54]`, counts 32 and 39). C′'s only fast D2
  fork was `C′-perfasm-2` (count 40, 113.9 µs). It has no per-op counts.
- Around each exit (estimates). F1 runs about 163 instructions per exit beyond its 20.5 per byte helper loop
  ((1,967,496 − 20.5 × 32,768) ÷ 7,944). The outer body takes about 60% of 560,864 cycles per op, about 42 cycles per exit.
  For comparison, C′ runs about 97 instructions per exit beyond its 42 per byte loop.

## Predictions

| Prediction | Grade | Why |
|---|---|---|
| A, D1: fast in ≥ 6 of 7, ~42 instructions, 8.3–8.8 cycles/byte | **partial** | Fast in 7 of 7, 42 instructions, state in a register, but 8.72, 8.93, 8.96 cycles/byte: 2 of 3 above 8.8. No A fork spilled. |
| C′, D1: bimodal, the shape follows the count, ≥ 1 of 7 slow at ~14 cycles/byte | **miss** | Bimodal (3 of 7 slow, 13.64 cycles/byte), but `C′-perfasm-2` (count 54) and `C′-perfnorm-3` (70) were fast, which meets the wrong-if. |
| C′, D1 fast forks: instructions/op within 1% of A | **miss** | `C′-perfnorm-1` −2.4% (41-instruction loop), but `C′-perfnorm-3` +14.2% (48-instruction loop), floor passing in both. |
| F1, D1: inline helper loop, 13–16 instructions/byte, no `[rsp]` operand, state in a register, 6.0–8.5 cycles/byte, ≤ A | **miss** | Helper loop, state in a register and 7.36 cycles/byte (A 8.93) in all 7, but 16.5 instructions/byte and two `cmp eax,[rsp]` stack operands per iteration, which meets the written wrong-if. |
| F1 trigger: count ≥ 40 (~4,353) in every F1 fork, one shape | **hit** | All 7 D1 forks at 4,436–4,608, one shape. (D2's F1 forks compiled at 44–189 with a spilled helper loop; that is D2's row.) |
| F3, D1: bimodal like C′, fast forks within 1% of A | **miss** | Bimodal (3 slow, 4 fast), but its fast forks ran +7.1%, +7.2% and +14.2% instructions/op over A. |
| D2: F1/C′ instructions 1.00–1.15, cycles 0.95–1.15 | **partial** | Cycles 1.032 (inside 1.15). Instructions 0.917, below the range. The C′ baseline is its slow D2 shape only. |

## Verdicts

- **F1: NOT.** By the spec's rule it fails twice. On D1 it keeps the state in a register in all 7 forks at 7.36 cycles/byte
  (A 8.93), but its hot loop has two stack operands per 2 bytes. On D2 it spills `stateOffset` to `[rsp+0x5c]` in all 4 forks.
  D2 cycles stay inside 1.15 against C′'s slow forks.
- **F3: NOT.** It stays bimodal (3 slow at counts 37–39, 4 fast at 40–104), so the end-of-text control flow isn't the switch.
  Its fast loops run 7–14% more instructions than A.
