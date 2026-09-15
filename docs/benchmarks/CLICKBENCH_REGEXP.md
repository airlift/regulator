# ClickBench URL regex diagnostic

`BenchmarkClickBenchRegexp` exercises the exact pattern from
[ClickBench q29](https://github.com/ClickHouse/ClickBench/blob/main/clickhouse/queries.sql#L29):

```regex
^https?://(?:www\.)?([^/]+)/.*$
```

The inputs are synthetic. This is a diagnostic benchmark, not the full SQL query,
not a reproduction of ClickHouse's published speedup, and not part of the frozen
dashboard dataset. It measures the RE2 frontend, which does not use the Trino
scan plan.

## Semantics and cases

The primary configuration is the RE2 frontend with `dotAll=true`, matching
ClickHouse's dot-newline setting. `dotAll=false` is a separate RE2 control. It is
not a Trino comparison: RE2's text-end `$` differs from Trino's end assertion.
The replacement is RE2's `\1`.

Cases cover HTTP, HTTPS, optional `www.`, rejected schemes, empty hostnames,
missing path slashes, multibyte hostnames and paths, and newlines in the hostname,
path, or final position. Long-host and long-path cases independently add 4 KiB
of scanning work. All cases use a Slice with a nonzero backing-array offset.

`https://www./path` must capture `www.`. Consuming the optional prefix first
fails, so a specialized executor must retry with that group absent. A simple
irrevocable sequence of greedy operations would be incorrect.

`TestBenchmarkClickBenchRegexp` checks match results, both capture offsets,
extraction, replacement, and matcher reset. Malformed UTF-8 is a correctness
control outside the timing matrix. RE2 dot-all is not a wildcard over arbitrary
invalid bytes. Expected match and capture results were also checked against the
pinned native RE2 oracle for both dot settings.

## Operations

| Method | What it measures |
|---|---|
| `compile` | Pattern compilation with prebuilt options, no cache lookup |
| `contains` | Boolean search without requested captures |
| `matchBoundaries` | Group zero through the caller-buffer API |
| `captureWithReusedMatcher` | Search and group 1 offsets with a reset, reused matcher |
| `extract` | Search, capture recovery, and construction of the extracted result |
| `replaceFirst` | Public first-match replacement, preserving the input on a miss |
| `replaceAll` | Public global replacement for the same anchored pattern |

Every method returns its result to JMH. Boundary methods pack both offsets into
the returned value. These are separate public-operation costs, not additive
stages: subtracting two rows does not isolate capture recovery or allocation.
The fixed-input rows diagnose scaling and routes; they do not model production
branch profiles.

## Trino specialized execution

The [Trino scan-plan guide](TRINO_SCAN_PLAN.md) describes specialized Trino
execution of this pattern and its benchmark, including normal-dot semantics.

## ClickHouse reference

The restricted processor is described in
[PR #108004](https://github.com/ClickHouse/ClickHouse/pull/108004). The inspected
implementation is pinned to `v26.8.2.7-lts`:

- [Program analysis](https://github.com/ClickHouse/ClickHouse/blob/v26.8.2.7-lts/src/Common/RegexpJIT/RegexpProgram.cpp)
- [Generated executor](https://github.com/ClickHouse/ClickHouse/blob/v26.8.2.7-lts/src/Interpreters/JIT/CompileRegexp.cpp)
- [Replacement integration](https://github.com/ClickHouse/ClickHouse/blob/v26.8.2.7-lts/src/Functions/ReplaceRegexpImpl.h)

The useful hypothesis is that delimiter scans and direct literal checks remove
generic automaton work. Whether Java plan dispatch is cheap enough, and whether
path processing dominates Regulator's existing route, remain measurement questions.
