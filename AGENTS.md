# Regulator Project

Regulator is a high-performance regular-expression engine for Slice. Its core
matching algorithms are a faithful Java port of Google RE2, with separate
frontends for RE2, Trino regular expressions, Java regular expressions, and
Trino SQL LIKE.

## Key Principle

**Follow upstream RE2 behavior by default.** When deviating, document in `REGULATOR_DECISIONS.md`.

## Documentation

**Root directory (frequently used):**
- `REGULATOR_DECISIONS.md` -- Documented deviations from upstream RE2

**docs/ directory (reference):**
- `docs/README.md` -- Documentation index and authority rules
- `docs/porting/WORKFLOW.md` -- Development workflow and conventions
- `docs/integrations/TRINO.md` -- Current public API and Trino design research
- `docs/benchmarks/METHODOLOGY.md` -- Benchmarking and performance-analysis guide
- `docs/benchmarks/QUALIFICATION_PLAN.md` -- Final candidate benchmark plan

## References

ALWAYS SEE:

- `docs/coding-standards.md`
- `docs/testing.md`
- `docs/git.md`
- `docs/MAINTAINING_REGULATOR.md`

## Build & Test

Use the single-module Maven wrapper commands in
[`docs/testing.md`](docs/testing.md) for builds, tests, and benchmark setup.

## Verification

Before considering work complete:

Run checks appropriate to the changed paths as defined in
`docs/MAINTAINING_REGULATOR.md` and `docs/testing.md`. Every commit must build
cleanly and pass its tests.

Machine-specific preferences can be added to `CLAUDE.local.md` or `AGENTS.override.md` (neither checked in).

## Performance Work

Before investigating performance gaps, designing benchmarks, or changing hot
paths, read [`docs/MAINTAINING_REGULATOR.md`](docs/MAINTAINING_REGULATOR.md) for
correctness and qualification gates and
[`docs/benchmarks/METHODOLOGY.md`](docs/benchmarks/METHODOLOGY.md) for comparison
contracts, target-host measurements, and analysis procedures. Check
`REGULATOR_DECISIONS.md` for accepted trade-offs affecting the proposed change.
These project rules apply without requiring any personal agent skills.

## Implementation Standards

- **API cleanup before 1.0.** An explicitly approved pre-release cleanup may use direct renames instead of temporary bridge APIs. This exception expires with the first 1.0 publication; preserve published API compatibility afterward.
- **Features must be fully implemented.** A fix that only works for one mode (e.g., LATIN1) but not the default mode (UTF-8) is NOT complete. Do not claim something is "fixed" unless it works for all supported configurations.
- **Verify against the actual benchmark.** If a plan targets a specific benchmark, the fix must improve that exact benchmark, not a variant of it.
- **Default mode is UTF-8.** Most patterns compile with UTF-8 encoding by default. Any optimization must work for UTF-8 programs, not just LATIN1.
- **Respect each language's contract.** Follow the per-language authorities in `docs/MAINTAINING_REGULATOR.md`, the language references, and accepted deviations in `REGULATOR_DECISIONS.md`. Fully implement behavior within those contracts. A difficult repair does not authorize adding a new compatibility gap or reopening an accepted difference.
- **Test-first for optimizations.** Before fixing any performance issue:
  1. Write a failing test that demonstrates the problem
  2. Test must directly verify the code path (e.g., sentinel returned), not timing
  3. Base expected behavior on the affected language's authority and accepted decisions; use pinned C++ RE2 goldens for RE2 semantics
  4. Implement the fix
  5. Test must pass - fix is NOT complete until test passes
  See `docs/MAINTAINING_REGULATOR.md` for the correctness and performance gates.

## Upstream Source

- Pinned commit: `972a15cedd008d846f1a39b2e88ce48d7f166cbd`
- Reproducible fetch/build: `tools/re2-golden/build.sh`
- Fetched source location: `target/re2-golden-dependencies/re2/`
