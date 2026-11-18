# EG-24 r8i extract: rows 14, 2, 1 and 7 (M and F′)

Raw output from engine-gaps round EG-24 (an untimed D-cell, 2026-11-18) on r8i.large (Xeon 6975P-C). It is here for
the #282 row 14 investigation, which runs on an aarch64 host that cannot produce x86 assembly. Never merge this branch.

- **Legs:** M = main 0db30b513; Fp = #282 rebased, 3b1646d05.
- **Rows** (EG-21 numbering):
  - r14: Trino quotedField contains;
  - r02: RE2 count, UNANCHORED_URL;
  - r01: RE2 contains, UNANCHORED_URL;
  - r07: RE2 contains, FOLDED_UNANCHORED.
- **`perfasm-logcompilation/`:** for each leg, row and fork 1–3:
  - the JMH JSON;
  - the fork log (PrintCompilation and PrintInlining);
  - the perfasm listing (`.perfasm.zst`);
  - the LogCompilation XML (`.xml.zst`).

  Decompress with `zstd -d`.
- **`perfnorm/`:** the appended cycles and instructions forks (JSON).
- **Notes:** RESULTS-EG-24.md, RESULTS-EG-24.tsv and RUN-EG-24.md are the round's notes.

The full archive, with every row on both platforms, is `archive/engine-gaps-eg24-20261118.tar.zst` in the benchmark
archive bucket.
