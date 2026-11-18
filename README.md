# UAP-2 D-cell evidence (r8i, Dfa::searchMany byte loop)

Round UAP-2 of the uap-core work (PR #279), run 2026-11-18 00:01–00:06 PST on one r8i.large Spot host, Intel Xeon
6975P-C, Temurin 25.0.4+7, perf 6.1.176, hsdis-amd64. Each fork is
`BenchmarkPatternCollections.matchingSetPatternIds -p patternCount=16 -p sourceLength=32768 -f 1`, the class's own
warmup and measurement (10 × 1 s each), JVM options `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED
-Xms8g -Xmx8g -XX:+UseG1GC -XX:+AlwaysPreTouch` (native mode). These are untimed diagnostics, not benchmark scores.

Legs:
- **A** = `80bccc124` (main `bdf26dc33` plus the benchmark);
- **C** = `c46ea3278` (U1b before the fix);
- **D** = `fe44865e3` (U1b with `dfa.bytemap` hoisted into a local in `searchMany` and `searchManyFull`; since dropped from
  #279).

## Which fork is which

| Fork | Leg | Profiler | µs/op | Mode | Byte-loop shape |
|---|---|---|---|---|---|
| `A-perfasm` | A | perfasm | 72.9 | fast | 42 instructions; stateOffset in a register |
| `C-perfasm-2` | C | perfasm | 74.1 | fast | 41 instructions; stateOffset in a register |
| `C-perfasm` | C | perfasm | 115.5 | slow | 49 instructions; stateOffset spilled to `[rsp+0x34]` |
| `C-perfasm-3` | C | perfasm | 115.0 | slow | the same loop as `C-perfasm` |
| `D-perfasm` | D | perfasm | 120.7 | slow | 38 instructions; stateOffset spilled to `[rsp+0x30]` |
| `A-perfnorm` | A | perfnorm | 71.7 | fast | — |
| `C-perfnorm` | C | perfnorm | 74.2 | fast | — |
| `D-perfnorm` | D | perfnorm | 118.0 | slow | — |

- The spec asked for one perfasm and one perfnorm fork per leg. `C-perfasm-2` and `C-perfasm-3` were added because the
  first C perfasm fork landed in the slow mode, so that both C loops are captured.
- Timed forks in the same round: A ≈ 71.5, C ≈ 74.2 and D ≈ 118 µs/op on both r8i hosts. On r9g, D/A is 0.993.
- In UAP-1, all 48 of C's r8i guard forks were in the fast mode (≈ 74 µs).

## Hot loop (Hottest Region 1, Dfa::searchMany, C2, 93–98% of cycles)

| Fork | Start mod 64 | Instructions | `[rsp]` loads / stores | `bytemap` field load per byte | Loop-carried stateOffset |
|---|---|---|---|---|---|
| A-perfasm | 27 | 42 | 7 / 3 | yes | register |
| C-perfasm-2 (fast) | 28 | 41 | 7 / 2 | yes | register |
| C-perfasm, C-perfasm-3 (slow) | 23 | 49 | 11 / 8 | yes | stored to `[rsp+0x34]`, reloaded next byte |
| D-perfasm | 50 | 38 | 9 / 3 | no | stored to `[rsp+0x30]`, reloaded next byte |

- In D (and slow C), the state from each transition is stored to the stack at the end of the byte and loaded back for
  the next byte's index. That puts a store-to-load forward on the transition → next-index dependency chain.
- D also reloads the hoisted bytemap array and its length from the stack every byte.
- The hottest instruction is the compare after the transitions load: 43% of cycles in D, 34% in slow C, 18% in A.
- The extra ≈ 5.5 cycles per byte (below) fits the added forwarding latency. That is a reading of these numbers; the
  forwarding latency itself was not measured.
- `hot-loops.txt` has each loop with per-instruction cycle shares.

## perfnorm per op (`perfnorm-per-op.tsv`)

| Leg | µs/op | Cycles | Instructions | IPC | Cycles/byte | Alias (r0403) |
|---|---|---|---|---|---|---|
| A | 71.7 | 279,797 | 1,246,318 | 4.45 | 8.54 | 6.0 |
| C (fast) | 74.2 | 289,435 | 1,311,826 | 4.53 | 8.83 | 5.5 |
| D | 118.0 | 461,555 | 1,215,183 | 2.63 | 14.09 | 15.6 |

- D runs fewer instructions than A, but 1.65× the cycles.
- Alias counts are negligible in every leg.
- C's fast-mode +3.5% over A comes with the same loop shape and alignment, but 5.3% more instructions per op. Stack
  traffic and alignment don't explain it.
- **Alias event:** perf 6.1 has no named core events for this CPU, so `ld_blocks_partial.address_alias` is unavailable.
  `r0403` is the raw LD_BLOCKS.ADDRESS_ALIAS code (event 0x03, umask 0x04, its encoding on Golden Cove and later cores).
  `alias-list.txt` shows what perf accepted.

## Files

- `<fork>.perfasm.zst`: perfasm's saved log (zstd). It is HotSpot's full `-XX:+LogCompilation` XML log with
  `-XX:+PrintAssembly` (Intel syntax) output and perf cycle annotations. Each holds the compile tasks, the
  `inline_success` / `inline_fail` inlining decisions and every nmethod's assembly. This is the LogCompilation (`.xml`)
  content.
  - PrintCompilation and PrintInlining were not passed as separate flags (the spec's command did not ask for them), so
    there is no separate output; the LogCompilation tasks and inline tags carry the same information.
  - JMH writes perfasm's own log to a temporary file and deletes it after parsing; this saved log is the copy.
- `<fork>.log`: the JMH console output, with the hottest regions (`top=3`) or the perfnorm table.
- `<fork>.json`: the JMH result, including the perfnorm secondary metrics.
- `perfnorm-per-op.tsv`, `hot-loops.txt`: the tables above, made by `dcell-analyze.py` (included) from the files here.
- `dcell.sh`: the on-host script that ran each fork.
- `perf-check.txt`, `alias-list.txt`, `alias-event.txt`: the host's perf checks.

## Masking

Every file was checked for IPv4 addresses, EC2 host names, the AWS account ID and the archive bucket name.
- The only match was the IPv4 loopback address: JMH's forked-JVM command line inside each perfasm log, two lines
  per log. It was replaced with `<ip>` in all five `.perfasm.zst` files.
- Nothing else was found or changed.

`SHA256SUMS` covers every other file in this directory.
