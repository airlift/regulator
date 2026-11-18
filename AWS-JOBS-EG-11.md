# AWS timing jobs, round EG-11: W5 (#278), dense match starts through forEachMatch

For the coordinator to run on AWS Spot. Written 2026-11-17 PST on dain-1. **I wrote the predictions before anything ran.** Both legs are built on main `11b9291e1`.

**What #278 does.**
- After 64 dense group-zero finds inside a library all-match operation, one reverse many-match pass marks every match start, and each end comes from an anchored forward search.
- The library all-match operations are `count`'s matcher loop, the new `forEachMatch` (Dain's option A), and Trino's `extractAll`, `split` and `replace`.
- A caller's `find()` loop never switches.
- The many-match reverse DFA borrows half of the reverse DFA's free memory. It gives the loan back under pressure.
- A sparse-tail gate samples each 64 KiB piece of the rest of the input before switching.

**Budget.** About 4,000 forks, roughly **$1.25** at EG-8's rate ($0.000305 per fork); ask **$1.50** with margin. The engine-gaps hold is committed to $17.25 of $17.50, so this needs a top-up.

**Conventions,** as EG-10:
- **12 forks per leg per host.** Use **2 hosts per platform**, on **r9g** and **r8i**.
- Legs interleave fork by fork (ABAB). Both legs of a row run on the same host.
- **Native** mode only. jvmArgs are EG-8's template: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch`.
- Use the class's JMH defaults, and put the legs' jars at paths of equal length.
- For each row and platform, report the median C/A across both hosts, with the per-host values. Flag (⚑) any host pair that disagrees by more than 5%.

**Workloads:** `eg11-w5-workloads.tar.gz` in this kit (2,885,555 bytes, sha256 `f59b1c1b622107c4d40e53247bf22a686419422517af7b9c5f262ae0b196d22c`). It unpacks to `eg-workloads/`, holding the W5 `.klv` files, `manifest.json`, `SHA256SUMS` and `loan/`. Pass:
- `BenchmarkLanguageBulk`: `-p workloadFile=<dir>/eg-workloads/<file>.klv`, with `-p engine=` and `-p expectedResult=` from `manifest.json`;
- `BenchmarkDenseTrinoAllMatch`: `-p dataDir=<dir>/eg-workloads`;
- `BenchmarkDenseMatchStartLoan`: `-p dataDir=<dir>/eg-workloads/loan`.

The new classes check their answers in `@Setup`, and A and C agree on every one.

## Legs
- **A** `d9ce7dd663268d714f33c8aee406bf2881db5dd0`, branch `user/dain/bench-dense-match-starts-a2`: main plus two timing-only classes, `BenchmarkDenseTrinoAllMatch` and `BenchmarkDenseMatchStartLoan`.
  - Here `BenchmarkLanguageBulk` count-spans is a caller `find()` loop, as on main.
- **C** `ed8af0008a4f6006e8e24ecbbc9f936359a11a30`, branch `user/dain/bench-dense-match-starts-c2`: #278 `ad1c8134e8dea576fe32310fb6d287a7375f9ff9` (three commits: e89a7d19b, 5039fcdd9, ad1c8134e) plus the same two classes.
  - Here count-spans calls `forEachMatch`, which builds one matcher per call inside timing; every count-spans row is one haystack.
  - The loan class's dense setup also calls `forEachMatch`.
  - C's src/main patch-id against A equals #278's against main (`f780d7e8944e`).

## J1 — rows
1. **Dense targets**, `BenchmarkLanguageBulk.execute`, count-spans (6):
   - `curated--03-date--ascii` and `curated--03-date--unicode` × engines `rust`, `re2` (ascii 111817, unicode rust 111841 / re2 111817);
   - `diagnostic--03-date--sherlock-en` × `rust`, `re2` (184030).
2. **Trino all-match ops**, `BenchmarkDenseTrinoAllMatch` `extractAll`, `split`, `replace` (18):
   - workloads `curated--03-date--ascii` and `diagnostic--03-date--sherlock-en` (dense, switch on C);
   - `diagnostic--03-date--log`, `diagnostic--03-date--mixed-zh-sparse`, `diagnostic--03-date--zh` and `diagnostic--03-date--mixed-zh` (no switch on C).
3. **Sparse and Chinese count-spans controls**, `BenchmarkLanguageBulk.execute`, engine `rust` (4): `diagnostic--03-date--log` (12326), `mixed-zh-sparse` (27933), `zh` (44907), `mixed-zh` (67182).
4. **Words controls**, count-spans, engine `rust` (2): `curated--08-words--all-english` (56691) and `imported--sherlock--words` (447639). Neither takes the cursor that switches.
5. **Loan control**, `BenchmarkDenseMatchStartLoan` `findAfterDenseCount` and `findFresh`, `-p budget=default`, `-p unicode=false,true` (4).
   - On C the default budget takes the loan: 5.4 MB ASCII, 4.3 MB Unicode, with 0 extra resets counted locally.
   - The stress budgets never switch on C, so they are left out.
6. **Everyday controls** (4), where the per-find path changed by a null check and a compare:
   - `BenchmarkEverydayTrinoRegexp` `extract` on `keyValue` and `quotedField`, and `count` on `email`;
   - `BenchmarkTrinoRows.contains` english/low-selective.
7. **Not eligible**, count, `curated--01-literal--sherlock-en`, engine `rust` (513) (1).

**Forks:** 39 rows × 96 = **3,744 forks, about $1.14**; with setup and reclaim margin, ask $1.50.

## Predictions (C/A against main; above 1 is slower)

| Family | Rows | r9g | r8i | Gate / wrong if |
|---|---|---|---|---|
| Dense date count-spans | 03-date ascii, unicode × rust, re2 | **0.70–0.90** | **0.70–0.90** | Wrong if above 0.95. Against the crate, r9g goes from 1.26–1.29x to 0.95–1.15x |
| Dense date over English | 03-date sherlock-en × rust, re2 | **0.75–0.95** | 0.75–0.95 | Wrong if above 0.98 |
| Trino all-match, dense | extractAll, split, replace × ascii, sherlock-en | **0.75–0.92** | 0.75–0.92 | Wrong if above 0.97. The result building (slices, the output buffer) doesn't shrink |
| Trino all-match, no switch | 3 ops × log, mixed-zh-sparse, zh, mixed-zh | 1.00 ±2% | 1.00 ±2% | **Gate:** above 1.02 on both hosts of a platform keeps #278 draft |
| Sparse-tail and zh count-spans | log, mixed-zh-sparse, zh, mixed-zh | 1.00 ±2% | 1.00 ±2% | **Gate:** as above |
| Words count-spans | all-english, sherlock words | 1.00 ±2% | 1.00 ±2% | **Gate:** as above |
| Loan control | findAfterDenseCount, findFresh × ascii, unicode | 1.00 ±2% | 1.00 ±2% | **Gate:** findAfterDenseCount above 1.02 on both hosts, or more than 2% above findFresh's C/A |
| Everyday | keyValue, quotedField extract; email count; TrinoRows contains | 1.00 ±2% | 1.00 ±2% | **Gate:** as above |
| Not eligible | 01-literal sherlock-en count | 1.00 ±2% | 1.00 ±2% | **Gate:** as above |

- **The sparse-tail bound.** The coordinator asked that a switch over a sparse tail cost at most about 2x main's single forward pass over that tail. On C, `mixed-zh-sparse` doesn't switch: the gate samples each 64 KiB piece and finds the tail sparse. So the bound is not exercised, and the prediction is 1.00 ±2%. **I am wrong if** mixed-zh-sparse moves by more than 2%; then the gate's sampling itself costs time.
- **I am wrong if** the dense targets stay above 0.95. Then the reverse pass costs more than the forward searches it saves on these inputs; check the start-set pass counters on the AWS JDK.
- **Cut order:** first the everyday controls, then the Trino `split` rows on the no-switch workloads, then the words controls. Never cut the dense targets, the loan control or mixed-zh-sparse.

## Legs table

| Leg | SHA | Branch |
|---|---|---|
| A | `d9ce7dd663268d714f33c8aee406bf2881db5dd0` | user/dain/bench-dense-match-starts-a2 (main `11b9291e1` plus the two classes) |
| C | `ed8af0008a4f6006e8e24ecbbc9f936359a11a30` | user/dain/bench-dense-match-starts-c2 (#278 `ad1c8134e` plus the two classes) |
