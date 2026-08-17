# Regulator Documentation

This directory contains current user, reviewer, and maintainer documentation
for Regulator. Development notebooks and intermediate benchmark reports are
not retained in the repository.

## Start Here

1. [`reference/REGULATOR_API.md`](reference/REGULATOR_API.md) describes the public API and lifecycle contracts.
2. [`integrations/REGEXP_LANGUAGES.md`](integrations/REGEXP_LANGUAGES.md) explains how to choose a pattern language.

## Language Reference

| Document | Purpose |
|---|---|
| [`reference/languages/RE2.md`](reference/languages/RE2.md) | RE2 syntax, modes, and options |
| [`reference/languages/TRINO_REGEXP.md`](reference/languages/TRINO_REGEXP.md) | Trino's Joni-derived regular-expression language |
| [`reference/languages/JAVA_REGEXP.md`](reference/languages/JAVA_REGEXP.md) | Supported regular subset of Java 25 Pattern syntax |
| [`reference/languages/TRINO_LIKE.md`](reference/languages/TRINO_LIKE.md) | Trino SQL LIKE syntax and semantics |
| [`reference/languages/UNSUPPORTED_FEATURES.md`](reference/languages/UNSUPPORTED_FEATURES.md) | Unsupported constructs and future-support policy |

## API And Integration

| Document | Purpose |
|---|---|
| [`reference/REGULATOR_API.md`](reference/REGULATOR_API.md) | Public API, ownership, matching, captures, and resources |
| [`integrations/TRINO.md`](integrations/TRINO.md) | Trino compatibility boundary and adoption requirements |
| [`integrations/JVM_UNICODE.md`](integrations/JVM_UNICODE.md) | JVM-sourced Unicode policy and regeneration |
| [`reference/REVERSE_DFA.md`](reference/REVERSE_DFA.md) | Reverse-DFA design reference |

## Documentation Rules

- Keep user-facing language and API documents descriptive rather than chronological.
- Do not commit intermediate benchmark results, campaign notebooks, raw profiles, or rejected experiments.
- Commit one reproducible final benchmark report for a frozen review or release candidate.

## Upstream Source

- Repository: <https://github.com/google/re2>
- Pinned commit: `972a15cedd008d846f1a39b2e88ce48d7f166cbd`
- Reproducible fetch/build: `../tools/re2-golden/build.sh`
- Fetched source: `../target/re2-golden-dependencies/re2/`
