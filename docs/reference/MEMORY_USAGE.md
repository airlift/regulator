# Memory usage

This guide explains how much memory a compiled pattern uses, what bounds it,
how to measure it, and how threads share it. The [API guide](REGULATOR_API.md) holds the full
contract: [Compiled patterns](REGULATOR_API.md#compiled-patterns),
[Retained memory](REGULATOR_API.md#retained-memory), and
[Limits and defaults](REGULATOR_API.md#limits-and-defaults).

## The memory budget

Each compiled regular expression has a planning budget, 32 MiB by default. It
covers the compiled program, its lazily built DFA caches, and the specialized
matchers that compilation plans for it. Parsing is not charged, and a compiled
pattern does not keep its parse tree. Each capture search in progress may also
hold working memory beside the budget, and a `RegexpMatcher` keeps that memory
for later finds (see [Compiled patterns](REGULATOR_API.md#compiled-patterns)).
SQL `LIKE` patterns have no budget. Simple patterns retain only what they use.
The budget is a planning budget, not an exact heap limit, and it excludes the
pattern bytes, caller input, result objects, and output.

The default budgets of comparable engines:

| Engine | Default budget | What it covers |
|---|---|---|
| Regulator | 32 MiB per pattern | The program, every DFA cache, and planned matchers, two thirds for forward search and one third for reverse |
| RE2 | 8 MiB per pattern (`max_mem`) | The program, its one-pass table, and every DFA cache, with the same split |
| Rust `regex` crate | 10 MiB per NFA (`size_limit`) and 2 MiB per lazy DFA cache (`dfa_size_limit`) | Each concurrent search holds its own caches, so the total grows with threads |
| JDK `java.util.regex` and Joni | None | Memory is bounded only by the heap and thread stack |
| PCRE2 | 20,000,000 KiB of match-time backtracking heap, plus match and depth limits | Backtracking during a match; no cache budget |

A Regulator pattern's DFA caches are shared by every thread inside its one
budget, as
[How threads share a pattern's memory](#how-threads-share-a-patterns-memory)
describes. Its default is 4 times RE2's because Regulator is built for data
processing, where one compiled pattern runs over many rows. The budget is a
cap, not a reservation, and the default keeps the large DFA graphs of nearly
every benchmarked pattern cached without resets. A pattern that needs more to
compile, or to keep its DFAs cached, takes a larger budget from `setMaxMemory`.

## Measuring what a pattern holds

To learn what a pattern holds, read its `retainedSizeInBytes()`: an estimate
of the heap bytes it retains now, plus the native memory of its live DFA
pointer tables, its DFA caches included. It changes as searches build DFA
states and caches reset, and the caches grow within the budget, which
`maxMemoryBytes()` returns. A pattern shared by many threads counts once; each
thread's `RegexpMatcher` reports its own working memory from its own
`retainedSizeInBytes()`, which leaves out the pattern. See
[Retained memory](REGULATOR_API.md#retained-memory) for what the estimate
leaves out.

## When the budget runs out

Compilation that exceeds the budget throws
`RegexpCompileMemoryLimitException`. Where compilation proves a lower bound on
the budget the pattern needs, the message reports it; a larger budget may still
be needed. During matching, a full DFA cache resets, and repeated resets fall
back to the NFA, so ordinary matching never throws a memory-limit exception.
`Re2Set` is the exception: it throws `RegexpMatchMemoryLimitException` rather
than report no match, and so do the `FilteredRe2` methods that run its atom
search, an `Re2Set`. A `FilteredRe2` whose atom search exceeds its budget at
build time still builds without it, and those methods throw
`IllegalStateException` instead. See [Compiled patterns](REGULATOR_API.md#compiled-patterns) and
[Memory Budgets And DFA Caching](../../REGULATOR_DECISIONS.md#memory-budgets-and-dfa-caching).

## How threads share a pattern's memory

An engine that builds DFA states lazily, while it searches, chooses where
they live: in one cache that every thread searching the pattern shares, or in
a cache that each search holds. The choice trades total memory and warm-up
against contention and isolation. Each choice suits some workloads, and none
is right for all of them.

| Engine | Shared by every thread | Held by each search or thread |
|---|---|---|
| Regulator | The program, planned matchers, and one DFA cache for each direction and match kind | Capture-search working memory, which a `RegexpMatcher` keeps between finds |
| RE2 | The program, its one-pass table, and one DFA cache for each direction and match kind | NFA and backtracker working memory |
| Rust `regex` crate | The compiled NFAs and other read-only parts | Lazy DFA caches and every engine's scratch, taken from a pool |
| JDK `java.util.regex` and PCRE2 | The compiled pattern | A `Matcher`, or a PCRE2 match data block and JIT stack; no lazily built cache |

### One shared cache: Regulator and RE2

Regulator and RE2 share one cache. A state that one thread builds serves every
thread, and the cache's memory does not grow with the number of threads.
Regulator's retained sizes follow the same split: a pattern's
`retainedSizeInBytes()` counts its shared caches once, and a matcher's counts
only the working memory that matcher keeps for its thread.
Because the budget is charged once per pattern, not once per thread, a larger
budget is affordable, and a pattern with a large DFA graph can stay cached
rather than reset. When a search resets the cache too often for the bytes it
reads, both engines finish that search without the DFA. The costs:

- A search that needs a missing state finishes under the DFA's one lock, so
  many threads warming a cold cache take turns, and one long search can hold
  the lock for the rest of its input.
- A full cache resets for every thread at once, and threads that search
  unlike text compete for the same cache.
- RE2 takes a shared lock for each search, and a thread that resets holds the
  cache exclusively for the rest of its search, so other searches of that DFA
  wait. Regulator's searches of a warm cache take no lock.
- A Regulator reset leaves the old tables to searches still reading them, so
  until those searches finish, the memory a pattern retains can exceed its
  budget by up to one old copy for each search in flight.
- Regulator's capture searches each take their own allowance from the
  budget, so their working memory, unlike the DFA cache, grows with threads.

Measured with `BenchmarkSharedPatternThreads` on 16-vCPU Graviton and Intel
hosts, against the same pattern compiled once for each thread:

- Warm searches scale with threads. At 8 and 50 threads, finds and counts on a
  shared pattern ran 0.79 to 1.16 times as long as on a pattern per thread: a
  search of a warm cache takes no lock, and most store nothing to it. A count
  that updates a shared route hint as it goes, such as one of `\b\w+n\b`,
  ran 1.06 to 1.33 times as long.
- A cold cache fills one search at a time. Workers counting distinct 16 KiB
  rows on a freshly compiled pattern with a large DFA graph,
  `(?i)[a-q][^u-z]{80}x`, finished about 23 rows a second together, the rate
  of a single worker, 7 to 14 times slower than workers with patterns of their
  own. Workers counting the same row share the work: 50 of them finished as
  fast as one, 3.5 to 6 times faster than with patterns of their own.
- One thread's resets slow the others on the same pattern. Beside one thread
  counting a row that resets the cache too often, seven threads counting short
  rows that fit it ran 8.6 to 11 times slower than on patterns of their own.
  Beside six such threads, 44 ran 31 to 55 times slower on hosts where they
  did not stall, and stalled for long stretches on others. Without the
  resetting thread, sharing cost the short counts nothing measurable.

### A cache per search: the Rust crate

The Rust `regex` crate gives each search its own cache. A search shares no
mutable cache state while it runs, so no search waits on another's state
building or loses states to another's reset, and each cache holds only the
states the searches that used it needed; a cache returned to the pool can
later serve a search on another thread. The first thread to search a `Regex`
keeps a cache of its own and takes it without a lock; other threads take one
from a pool of 8 stacks, each behind a mutex. A lazy DFA cache is capped at
2 MiB by default, which suits a few threads. The costs:

- Memory grows with concurrent searches: each holds its own lazy DFA caches,
  and the pool keeps every cache returned to it and does not shrink.
- Each thread warms its own caches, building states other threads have
  already built.
- A large program can fill a small cache and clear it often. Once a cache has
  been cleared three times, a search that fills it again after fewer than 10
  bytes per state gives up the lazy DFA and finishes on another engine.
- A thread that finds its pool stack locked creates a cache for that one
  search, which starts cold and is dropped afterward, and a returned cache
  whose stack stays locked is dropped too. The crate documents that this
  contention can slow many threads running short searches, and suggests a
  `Regex` clone for each thread, or an explicit `Cache` passed to the
  `regex-automata` crate's `meta::Regex::search_with`.

### No cache: backtracking engines

Backtracking engines such as the JDK's and PCRE2 build no states, so they have
no cache to share, warm, or reset. The compiled pattern does not change as it
matches, once any PCRE2 JIT compilation of it has finished, and each thread
brings its own `Matcher` or match data. A match's memory follows its
backtracking instead, bounded by the heap and thread stack in the JDK, and by
PCRE2's heap limit or JIT stack.

### Choosing

Which design suits a workload depends on how many threads search one pattern
at once, how large the pattern's DFA graph grows, and how alike the text each
thread searches is. See
[Shared DFA Concurrency](../../REGULATOR_DECISIONS.md#shared-dfa-concurrency) for
how Regulator publishes and resets its shared cache.
