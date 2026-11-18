# RESULTS-EG-9: engine-gaps round EG-9 (D0, D1, J1 #275 fixed)

> **Flag (finished anyway):** J1's "other ASCII twins" **wrong-if tripped on r8i**:
> `imported/sherlock/word-ending-n-asciionly` java **1.069** (hosts 1.073, 1.065) and trino **1.064** (1.067, 1.061),
> both hosts above 1.03. It is new with the fix. EG-8 measured the unfixed B (03abd97a0) at 1.008 and 1.003; A is
> unchanged (1.205e6 vs 1.201e6 ns/op), and B rose from 1.211e6 to 1.290e6. r9g is 1.006 and 1.008. This family is a
> "wrong if", not a gate.

**Verdict: #275 PASS.** No gate tripped: the r8i controls fell from EG-8's 1.02–1.054 to 1.002–1.011, the Rust ASCII
twin is 0.997, and every Russian target is within 5% of EG-8. The r8i word-ending-n-asciionly java/trino regression
above is new with the fix and is the open item.

Spec: AWS-JOBS-EG-9.md (predictions unchanged). Run details, hosts and deviations: RUN-EG-9.md. Per-fork data:
RESULTS-EG-9.tsv (2,784 J1 forks; every fork's JSON `jvmArgs` and `# VM options:` line equal the template). D0/D1 forks
are untimed diagnostics, never scores; their raw `.perfasm`, `.xml` and logs are in the S3 archive (SPEND-EG-9.md).

## D0: #275's x86 register cost (r8i-x1, Xeon 6975P-C)

Legs: M main `11b9291e1`, B #275 `03abd97a0`, F fixed #275 `b42f6b6dc`. 2 perfasm forks per leg per row (M B F M B F),
plus 2 perfnorm forks per leg per row for per-op counts (perfasm columns are sample counts).

**Confirmed, by both rules:** B's hot loop reads a stack slot that M and F don't, and B executes more instructions per op
than M and F. F gives both back.

Where the loop bound comes from: `textEnd` is a parameter of each hot method. HotSpot x86_64 passes the first six Java
arguments in registers. #275 put its context argument before `textEnd`, making it the 7th argument (parm6) of
`searchForwardAbsolutePointers` and `searchForwardScanAcceleration`. The fix moved it back to 6th (parm5, `rdi`). In
`searchBackward` it is the 5th argument (`r9`) in every leg.

Static shape (C2 level 4, hottest nmethod, fork 1; frame = prologue `sub` + rbp + return address):

| method | leg | frame | `textEnd` arrives in | loop-bound compare `position < textEnd` | other hot stack reads of `textEnd` |
|---|---|---|---|---|---|
| searchForwardAbsolutePointers | M | 144 B | `rdi` | `cmp %edi,%r9d` (register) | none |
| searchForwardAbsolutePointers | B | 144 B | caller stack `[sp+0x90]` | `cmp 0x90(%rsp),%edi` (**stack slot**); `position == textEnd`: `cmp 0x90(%rsp),%r13d` | loads from `0x94(%rsp)` (mov, vmovss, mov, movslq): ≈ 1.3% of cycles on 03-date java |
| searchForwardAbsolutePointers | F | 144 B | `rdi` | `cmp %ebp,%r9d` (register) | none |
| searchForwardScanAcceleration | M | 160 B | `rdi` | `position >= textEnd`: `cmp 0x38(%rsp),%r8d` (local spill) | spilled in every leg |
| searchForwardScanAcceleration | B | 160 B | caller stack `[sp+0xa0]` | `cmp 0xa0(%rsp),%ebp` (incoming slot) | spilled in every leg |
| searchForwardScanAcceleration | F | 176 B | `rdi` | `cmp 0x40(%rsp),%r10d` (local spill) | spilled in every leg |
| searchBackward | M / B / F | 128 / 112 / 128 B | `r9` in every leg | no hot `textEnd` compare on the stack | none |

- All three legs compare against `ctxEnd` in a stack slot inside `searchForwardAbsolutePointers` (`0x94(%rsp)` in M,
  `0x9c(%rsp)` in B). That is common to the legs, not the regression.
- In `searchForwardScanAcceleration`, `textEnd` sits in a stack slot in all three legs (M and F spill it), so the
  instruction count there doesn't depend on the argument position.

Per op, perfnorm (2 forks per leg; cycles and instructions per benchmark op, one op = one workload pass):

| row | leg | instructions/op | vs M | cycles/op | vs M | IPC |
|---|---|---|---|---|---|---|
| 03-date ascii java | M | 5.671e7 | — | 1.330e7 | — | 4.26 |
| 03-date ascii java | B | 6.182e7 | **+9.0%** | 1.414e7 | **+6.3%** | 4.37 |
| 03-date ascii java | F | 5.710e7 | +0.7% | 1.427e7, 1.327e7 | +3.5% (one slow fork) | 4.15 |
| ing-suffix re2 | M | 5.932e6 | — | 1.363e6 | — | 4.35 |
| ing-suffix re2 | B | 6.214e6 | **+4.8%** | 1.423e6 | **+4.4%** | 4.37 |
| ing-suffix re2 | F | 5.927e6 | −0.1% | 1.369e6 | +0.5% | 4.33 |

One F fork on 03-date java ran 7% more cycles at M's instruction count, while its other fork matched M. That is
layout, not registers. In J1 the r8i 03-date java control is 1.011.

## D1: #273 quotedField on r8i (r8i-x1)

Legs: A `2ba9dbbdb` (main + BenchmarkTrinoPlanCosts), B `eab662aca` (#273 before the fix + the same class). No leg C
(J2 lists no SHA at this kit). `BenchmarkEverydayTrinoRegexp -p workload=quotedField`, contains and count as separate
invocations, 2 perfasm forks per leg per op, plus 2 perfnorm forks per leg per op.

Per op (perfnorm):

| op | leg | instructions/op | cycles/op | IPC | B vs A |
|---|---|---|---|---|---|
| contains | A | 439.7 | 74.3 | 5.91 | |
| contains | B | 446.8 | 70.2 | 6.36 | instructions +1.6%, cycles **−5.5%** |
| count | A | 579.9 | 95.1 | 6.10 | |
| count | B | 631.3 | 97.9 | 6.45 | instructions **+8.9%** (+51/op), cycles **+2.9%** |

Hot code: both legs inline everything into `containsOperation`/`countOperation` and `ScanPlan::matchUnanchoredAt`, both
128-bit vector paths (`Byte128Vector`).
- About 70% of cycles go to two vector blocks in both legs: the candidate scan `VectorScanner.findByte` (A line 253,
  B line 371; 26–28%) and the run reader's vector block, ending in `stops.firstTrue()`. A's reader is
  `findRunStop` line 60 (34–38%), a four-needle compare; B's is `findRe2ByteStop` line 138 (35–47%), an XOR and one
  signed compare. Most quoted fields stop inside the first 16-byte block.
- Run-reader scalar tails. A's `findRunStop` line 64 (`members[input[cursor] & 0xFF]`) is a counted loop unrolled ×4:
  byte loads at +0, +1, +2, +3. It takes 2–4% of cycles. B's `findRe2ByteStop` line 142 tail is a single byte walk (one
  load site). B adds `ScanPlanRun.re2ScanBlocks` line 597, a per-byte ASCII walk after each stop. It takes 2.5–3.4% of
  cycles in contains, where it is a single walk (one load site), and 3.6–3.8% in count, where it is unrolled ×4.
- Where contains and count differ: B's extra work is per run, in `re2ScanBlocks` (the minimum-bytes branch, the
  non-ASCII check after each stop, and the walk). On contains it adds only 7 instructions per op, and B's shorter
  dependency chain (XOR and one compare instead of four compares and three ORs) raises IPC from 5.91 to 6.36, so B is
  faster. On count it adds 51 instructions per op, and the same IPC gain (6.10 to 6.45) doesn't cover them, so B is
  slower. That count reads more runs per row than contains is my inference from the op semantics, not measured.
  - So on r8i the cause is per-run instruction overhead in the RE2-style run reader, not an uncounted byte walk.
    i23's r9g cause (the walk the JIT can't count) shows up here as the single-walk tails, but they are a small share
    of cycles on r8i.

## J1: #275 fixed (b42f6b6dc) vs main 11b9291e1, BenchmarkLanguageBulk.execute, native

B/A < 1 is faster. Per-host B/A = median(B) / median(A) over that host's 12 forks per leg; the platform B/A is the median
of the two hosts. ⚑ = hosts differ by more than 5% (none). A and B are medians over both hosts' forks, ns/op. Host
names: x1r/x1s and y2r/y2s are the replacements for reclaimed r9g-x1 and r9g-y2 (RUN-EG-9.md, incidents).

| family | row | engine | platform | per-host B/A | **B/A** | n | A (ns/op) | B (ns/op) |
|---|---|---|---|---|---|---|---|---|
| tgt | curated/08-words/all-russian | rust | r9g | x1 0.267, x2 0.268 | **0.267** | 12/12 | 1.147e+07 | 3.06e+06 |
| tgt | curated/08-words/all-russian | rust | r8i | x1 0.247, x2 0.248 | **0.248** | 12/12 | 1.13e+07 | 2.8e+06 |
| tgt | curated/08-words/long-russian | rust | r9g | x1 0.025, x2 0.025 | **0.025** | 12/12 | 1.045e+07 | 2.629e+05 |
| tgt | curated/08-words/long-russian | rust | r8i | x1 0.026, x2 0.026 | **0.026** | 12/12 | 8.71e+06 | 2.265e+05 |
| tgt | unicode/word/boundary-any-russian | rust | r9g | x1 0.272, x2 0.274 | **0.273** | 12/12 | 5.638e+07 | 1.541e+07 |
| tgt | unicode/word/boundary-any-russian | rust | r8i | x1 0.245, x2 0.245 | **0.245** | 12/12 | 5.583e+07 | 1.367e+07 |
| tgt | unicode/word/boundary-long-russian | rust | r9g | x1 0.025, x2 0.025 | **0.025** | 12/12 | 5.13e+07 | 1.27e+06 |
| tgt | unicode/word/boundary-long-russian | rust | r8i | x1 0.025, x2 0.025 | **0.025** | 12/12 | 4.271e+07 | 1.087e+06 |
| tgt | unicode/word/around-holmes-russian | rust | r9g | x1 0.024, x2 0.024 | **0.024** | 12/12 | 5.117e+07 | 1.225e+06 |
| tgt | unicode/word/around-holmes-russian | rust | r8i | x1 0.017, x2 0.017 | **0.017** | 12/12 | 5.995e+07 | 1.016e+06 |
| twin-rust | i787-opt-unicode-asciionly | rust | r9g | x1r 0.982, x2 0.985 | **0.983** | 12/12 | 2.651e+05 | 2.607e+05 |
| twin-rust | i787-opt-unicode-asciionly | rust | r8i | x1 1.001, x2 0.993 | **0.997** | 12/12 | 2.46e+05 | 2.464e+05 |
| diag | diagnostic/greek-words/all-greek | rust | r9g | x1r 0.442, x2 0.441 | **0.442** | 12/12 | 2.479e+07 | 1.094e+07 |
| diag | diagnostic/greek-words/all-greek | rust | r8i | x1 0.424, x2 0.422 | **0.423** | 12/12 | 2.446e+07 | 1.034e+07 |
| twin | imported/sherlock/word-ending-n-asciionly | java | r9g | x1r 1.008, x2 1.004 | **1.006** | 12/12 | 1.01e+06 | 1.015e+06 |
| twin | imported/sherlock/word-ending-n-asciionly | java | r8i | x1 1.073, x2 1.065 | **1.069** | 12/12 | 1.205e+06 | 1.29e+06 |
| twin | imported/sherlock/word-ending-n-asciionly | trino | r9g | x1r 1.002, x2 1.014 | **1.008** | 12/12 | 1.02e+06 | 1.029e+06 |
| twin | imported/sherlock/word-ending-n-asciionly | trino | r8i | x1 1.067, x2 1.061 | **1.064** | 12/12 | 1.213e+06 | 1.292e+06 |
| twin | imported/sherlock/words | java | r9g | x1s 1.001, x2 1.002 | **1.002** | 12/12 | 2.128e+06 | 2.134e+06 |
| twin | imported/sherlock/words | java | r8i | x1 0.969, x2 0.989 | **0.979** | 12/12 | 2.517e+06 | 2.486e+06 |
| twin | imported/sherlock/words | trino | r9g | x1s 1.003, x2 1.003 | **1.003** | 12/12 | 2.129e+06 | 2.134e+06 |
| twin | imported/sherlock/words | trino | r8i | x1 0.998, x2 1.000 | **0.999** | 12/12 | 2.614e+06 | 2.613e+06 |
| twin | unicode/word/boundary-any-english | java | r9g | x1s 1.009, x2 1.000 | **1.004** | 12/12 | 3.813e+06 | 3.824e+06 |
| twin | unicode/word/boundary-any-english | java | r8i | x1 1.000, x2 1.001 | **1.001** | 12/12 | 5.275e+06 | 5.279e+06 |
| twin | diagnostic/rust-unicode-ascii/word-ending-n | rust | r9g | x1s 1.010, x2 1.010 | **1.010** | 12/12 | 1.406e+06 | 1.418e+06 |
| twin | diagnostic/rust-unicode-ascii/word-ending-n | rust | r8i | x1 1.002, x2 1.000 | **1.001** | 12/12 | 1.329e+06 | 1.331e+06 |
| ctl | curated/03-date/ascii | java | r9g | y1 1.017, y2 1.014 | **1.015** | 12/12 | 3.062e+06 | 3.107e+06 |
| ctl | curated/03-date/ascii | java | r8i | y1 1.013, y2 1.008 | **1.011** | 12/12 | 3.422e+06 | 3.455e+06 |
| ctl | curated/03-date/ascii | trino | r9g | y1 1.020, y2 1.013 | **1.016** | 12/12 | 3.06e+06 | 3.111e+06 |
| ctl | curated/03-date/ascii | trino | r8i | y1 0.996, y2 1.010 | **1.003** | 12/12 | 3.273e+06 | 3.271e+06 |
| ctl | curated/03-date/ascii | rust | r9g | y1 1.015, y2 1.012 | **1.014** | 12/12 | 3.074e+06 | 3.12e+06 |
| ctl | curated/03-date/ascii | rust | r8i | y1 0.999, y2 1.005 | **1.002** | 12/12 | 3.452e+06 | 3.46e+06 |
| ctl | curated/03-date/ascii | re2 | r9g | y1 1.017, y2 1.021 | **1.019** | 12/12 | 3.055e+06 | 3.112e+06 |
| ctl | curated/03-date/ascii | re2 | r8i | y1 1.002, y2 1.007 | **1.004** | 12/12 | 3.413e+06 | 3.428e+06 |
| ctl | imported/sherlock/ing-suffix | java | r9g | y1 0.992, y2 1.005 | **0.999** | 12/12 | 2.942e+05 | 2.947e+05 |
| ctl | imported/sherlock/ing-suffix | java | r8i | y1 1.006, y2 1.007 | **1.006** | 12/12 | 3.503e+05 | 3.526e+05 |
| ctl | imported/sherlock/ing-suffix | trino | r9g | y1 0.999, y2r 1.001 | **1.000** | 12/12 | 2.945e+05 | 2.948e+05 |
| ctl | imported/sherlock/ing-suffix | trino | r8i | y1 1.002, y2 1.007 | **1.004** | 12/12 | 3.516e+05 | 3.539e+05 |
| ctl | imported/sherlock/ing-suffix | rust | r9g | y1 0.996, y2r 0.991 | **0.993** | 12/12 | 2.988e+05 | 2.976e+05 |
| ctl | imported/sherlock/ing-suffix | rust | r8i | y1 1.005, y2 1.005 | **1.005** | 12/12 | 3.526e+05 | 3.541e+05 |
| ctl | imported/sherlock/ing-suffix | re2 | r9g | y1 0.984, y2r 1.004 | **0.994** | 12/12 | 2.964e+05 | 2.959e+05 |
| ctl | imported/sherlock/ing-suffix | re2 | r8i | y1 1.004, y2 1.001 | **1.002** | 12/12 | 3.52e+05 | 3.526e+05 |
| ctl | imported/sherlock/quotes | java | r9g | y1 1.003, y2r 0.997 | **1.000** | 12/12 | 2.637e+05 | 2.64e+05 |
| ctl | imported/sherlock/quotes | java | r8i | y1 0.993, y2 1.008 | **1.001** | 12/12 | 2.757e+05 | 2.767e+05 |
| ctl | imported/sherlock/quotes | trino | r9g | y1 0.998, y2r 1.006 | **1.002** | 12/12 | 2.632e+05 | 2.636e+05 |
| ctl | imported/sherlock/quotes | trino | r8i | y1 0.995, y2 0.996 | **0.995** | 12/12 | 2.762e+05 | 2.751e+05 |
| ctl | imported/sherlock/quotes | rust | r9g | y1 0.995, y2r 1.005 | **1.000** | 12/12 | 2.637e+05 | 2.642e+05 |
| ctl | imported/sherlock/quotes | rust | r8i | y1 0.978, y2 0.996 | **0.987** | 12/12 | 2.766e+05 | 2.74e+05 |
| ctl | imported/sherlock/quotes | re2 | r9g | y1 1.003, y2s 1.006 | **1.004** | 12/12 | 2.635e+05 | 2.651e+05 |
| ctl | imported/sherlock/quotes | re2 | r8i | y1 0.990, y2 1.000 | **0.995** | 12/12 | 2.766e+05 | 2.757e+05 |
| ctl | imported/sherlock/before-holmes | java | r9g | y1 1.003, y2s 1.002 | **1.002** | 12/12 | 5.872e+04 | 5.88e+04 |
| ctl | imported/sherlock/before-holmes | java | r8i | y1 0.997, y2 0.995 | **0.996** | 12/12 | 4.777e+04 | 4.765e+04 |
| ctl | imported/sherlock/before-holmes | trino | r9g | y1 0.974, y2s 0.972 | **0.973** | 12/12 | 6.054e+04 | 5.891e+04 |
| ctl | imported/sherlock/before-holmes | trino | r8i | y1 0.998, y2 0.997 | **0.997** | 12/12 | 4.777e+04 | 4.764e+04 |
| ctl | imported/sherlock/before-holmes | rust | r9g | y1 1.007, y2s 1.002 | **1.004** | 12/12 | 5.879e+04 | 5.897e+04 |
| ctl | imported/sherlock/before-holmes | rust | r8i | y1 1.013, y2 0.997 | **1.005** | 12/12 | 4.804e+04 | 4.842e+04 |
| ctl | imported/sherlock/before-holmes | re2 | r9g | y1 1.003, y2s 1.002 | **1.003** | 12/12 | 5.86e+04 | 5.875e+04 |
| ctl | imported/sherlock/before-holmes | re2 | r8i | y1 0.991, y2 0.988 | **0.989** | 12/12 | 4.769e+04 | 4.723e+04 |

## Predictions (graded)

| family | prediction r9g / r8i | measured r9g / r8i | grade |
|---|---|---|---|
| No-assertion controls (16 rows) | 0.99–1.02 / 0.99–1.02 | 0.973–1.019 / 0.987–1.011 | **partial**: every row ≤ 1.02, and the r8i 1.02–1.054 rows are gone (03-date 1.002–1.011, ing-suffix 1.002–1.006), but three rows fall just under the range on the fast side: r9g before-holmes trino 0.973 (EG-8 0.983), r8i quotes rust 0.987, r8i before-holmes re2 0.990 |
| Rust ASCII twin (i787-opt-unicode-asciionly) | 0.98–1.01 / 0.99–1.02 | 0.983 / 0.997 | **hit**: EG-8's r8i 1.054 is gone |
| Other ASCII twins (6 rows) | 0.99–1.02 / 0.98–1.02 | 1.002–1.010 / 0.979–1.069 | **miss** on r8i: word-ending-n-asciionly java 1.069 and trino 1.064; the other four are 0.979–1.001. r9g hit |
| Russian targets all-russian, boundary-any-russian | 0.26–0.29 / 0.23–0.26 | 0.267, 0.273 / 0.248, 0.245 | **hit** |
| Russian targets long-russian, boundary-long-russian, around-holmes-russian | 0.024–0.027 / 0.016–0.027 | 0.0251, 0.0247, 0.0239 / 0.0260, 0.0254, 0.0170 | **hit** (r9g around-holmes 0.0239 rounds to the range's 0.024 floor) |
| Mixed leads (all-greek) | 0.43–0.46 / 0.40–0.44 | 0.442 / 0.423 | **hit** |

## Gates and "wrong if"

| condition | tripped? | row |
|---|---|---|
| Gate: any no-assertion control above 1.02 on both hosts of a platform | **no** | closest: r9g 03-date re2, hosts 1.017 / 1.021 (one host); r9g 03-date trino 1.020 / 1.013. r8i max 1.013 |
| Gate: Rust ASCII twin above 1.02 on both hosts | **no** | r9g 0.982 / 0.985; r8i 1.001 / 0.993 |
| Gate: all-russian, boundary-any-russian more than 5% above EG-8 (r9g 0.271 / 0.276, r8i 0.246 / 0.245) | **no** | r9g 0.267 / 0.273; r8i 0.248 (+0.7%) / 0.245 |
| Gate: long-russian, boundary-long-russian, around-holmes-russian more than 5% above EG-8 (0.025, 0.025, 0.024 / 0.026, 0.025, 0.017) | **no** | r9g 0.0251, 0.0247, 0.0239; r8i 0.0260, 0.0254, 0.0170 |
| Wrong if: other ASCII twins above 1.03 on both hosts | **yes (r8i)** | word-ending-n-asciionly java 1.073 / 1.065 and trino 1.067 / 1.061 |
| Wrong if: all-greek above 0.47 / 0.44 | **no** | 0.442 / 0.423 |
| I am wrong if the r8i controls stay at EG-8's 1.02–1.05 while D0 shows no stack slot in F | **no** | r8i controls 1.002–1.011; D0 shows the stack-slot loop bound and the extra instructions only in B |

## One line

**#275 PASS:** the argument-order fix removes the r8i control regression, as D0 explains (textEnd back in a register,
4.8–9.0% extra instructions gone). The open item is a new r8i-only +6.4–6.9% on word-ending-n-asciionly java/trino;
A is unchanged and B is slower than the unfixed B.
