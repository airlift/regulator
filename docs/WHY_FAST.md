# Why Regulator is fast

Regulator is a pure-Java regular-expression engine. Its matching algorithms
come from RE2: a lazily built DFA, a one-pass engine (OnePass), a bounded
backtracker (BitState), and an NFA, all with linear-time and bounded-memory
guarantees. Those guarantees stay. The speed comes from three habits layered
on top:

1. When match starts are rare, most input bytes never reach an automaton.
   Fast scans find the few places a match can start, and cheap checks reject
   inputs that cannot match at all.
2. When an automaton does run, each step is as cheap as the JVM can make it.
3. Compilation spends effort choosing a route for each pattern, and the JIT
   then compiles that route with the running workload's profile.

This page explains those mechanisms for a curious engineer. The
[optimization catalog](reference/OPTIMIZATION_CATALOG.md) lists the
optimizations with their locations and origins, and the
[benchmark report](https://airlift.github.io/regulator/benchmarks/) has the
measurements.

## Skip what cannot match

A regex search spends most of its time rejecting text. The cheapest rejection
is one that runs no automaton at all.

**Whole-input checks.** Compilation records facts about every possible match:
its minimum length, the bytes an anchored match can begin with, and, for short
inputs, a literal or a few bytes every match must contain. A short input that
fails one of these checks returns no match before any engine starts. In
row-at-a-time workloads with short rows, such as a query filtering a column,
rows that cannot match often end here.

**Literal scans.** When a pattern starts with a literal, a scan looks for a
pair of its bytes, usually the first and last, and checks the whole literal
only where both appear. Testing two positions at once removes most false
candidates that a single-byte search would stop on. On windows of at least
1 KiB with the Vector API, the scan tests the pair at 16 to 64 positions per
step. Otherwise it scans 8-byte words (SWAR): a prefix that starts with an
ASCII byte scans for that byte and checks the other per candidate, and other
literals test both bytes in each word. An optional byte-frequency table can
move the probe to a rarer byte of the literal. When a literal is in the
middle of a pattern, as in `\w+@example\.com`, the search finds the literal
first and then runs the automaton backward and forward from it, a technique
taken from `regex-automata`, the crate that implements the Rust `regex`
crate's engines.

**Multi-literal and byte-set filters.** Patterns that begin with one of a few
literals use a nibble-table filter in the style of Teddy, the algorithm the
`aho-corasick` crate implements and credits to Hyperscan. It checks up to
eight literal groups at three byte positions; four- and eight-group filters
need one new vector load per step, and two-group filters three. Large
literal alternations use an Aho-Corasick automaton ported from the same crate.
Patterns that can only start at a small set of bytes scan for that set.

**Staying linear.** A fast scan stays fast only while false candidates are
sparse. The scans watch for the opposite case. A literal search whose failed
candidates compare more bytes than it has covered switches to a
Knuth-Morris-Pratt search, which is linear however dense the near-misses are.
Some candidate scans sample the input first and run only when candidates are
sparse, and a scan plan whose failed attempts in one search exceed its work
budget hands the rest of that search to the ordinary engine.

## Run the cheapest engine for the question

Different questions need different engines. Whether a pattern matches, where
a match ends, where it starts, and what its groups captured each cost
progressively more. Regulator answers each with the least it can.

- A yes-or-no question that no specialized route answers, such as an exact
  literal's scan or a scan plan, runs the forward DFA and stops at the first
  match.
- A match start usually needs a reverse DFA pass. Regulator skips it when the
  answer is already known: a fixed-width match starts its width before the
  end, a pattern that matches empty at every position starts at the search
  start, and a one-character match that ends in a valid UTF-8 character
  starts at that character's lead byte.
- Captures run on RE2's OnePass engine when the pattern is unambiguous.
  RE2's OnePass handles at most four explicit groups. Regulator's handles up
  to 31, and also handles assertions that depend on the surrounding text. Each
  step's entry holds both the transition's action and the next state's row
  offset, so one load per byte gets both.
- Ambiguous captures through a matcher, on input from 4 KiB up to the
  backtracker's limit, run one bounded backtracking pass instead of a forward
  DFA, a reverse DFA, and a capture pass. Its visited bitmap keeps it linear,
  and the bitmap's size is bounded.

## Use a specialized plan for a common shape

Many real patterns follow a handful of shapes: extract a URL's host, split
`key=value`, count runs of a character class, test for one of a dozen words.
Compilation recognizes these and builds a dedicated route for each.

- **Scan plans** handle literals, character runs, optional pieces, and
  captures in a short, bounded program. A plan accepts a variable-length run
  only when no byte of the run can begin what follows it, so the run stops
  exactly where a backtracking match would. Plans also bound their optional
  branches. Each unanchored search has a work budget, and a search that
  exhausts it continues on the ordinary engine.
- **Specialized matchers** cover literal alternations, fixed-width spans,
  ordered literals with gaps, word runs, and runs of one byte.
- **SQL `LIKE`** has its own planner: an exact match, a prefix, a suffix, a
  contained literal, ordered literals, or literals with `_` gaps each compile
  to their own routine, and only general wildcard patterns use the wildcard
  matcher.

Each route must produce exactly the results of the ordinary engine, on valid
UTF-8 and, for every language except Trino regular expressions, on any input.
A pattern that no plan fits runs on the shared engines.

## Make each DFA step cheap

When the DFA does run, its inner loop is a load, a check, and a load. The
question is what those loads cost on the JVM.

**Rows as references.** Each DFA state is an array of references to the next
states' arrays, so a step loads the next row directly from the current row.
An unbuilt or special transition leaves the loop for a cold path, which builds
the state or handles the match.

**An optional native table.** With native access enabled, the DFA can also
keep its transitions in native memory as 64-bit addresses of the next row,
with the state's kind tagged in the low bits. A step is then one load and one
add, with no compressed-reference decoding. This is storage allocated through
the JDK's foreign memory API, not native code. Its benefit depends on the
workload, so the report publishes both modes.

**Two bytes at a time.** For small DFAs on long input, a bounded paired table
holds the composed transition for each pair of byte classes (as in RE2,
bytes the pattern treats alike share a class), so one load advances two
bytes. It switches itself off when searches keep ending within a few bytes.

**Self-loops.** With the Vector API, a non-matching state that loops on every
byte except one to three exit bytes, such as the `\C*` in RE2's `a\C*b`,
scans ahead to its next exit byte. Counting operations also scan states that
exit on every non-ASCII byte, such as UTF-8 `[^\n]*`, and have a dedicated
loop for counting matches of a repeated class such as `\w+`. Other looping
states step one byte at a time in a tight loop that stays on the state.

**No lock per search.** A compiled pattern's DFA cache is shared by every
thread that uses it. RE2 takes a reader lock on the cache for each search.
Regulator's readers load one published table generation and take no lock. A
cache reset publishes new tables and leaves the old ones to searches already
reading them, and a reader that needs a missing entry retries as an exclusive
search holding Regulator's cache write lock.

## Why a JIT-compiled engine can compete

A JIT does not make code fast by itself. What it gives Regulator is
specialization after the pattern and the workload are known.

**Each pattern shape gets its own code path.** Each language's
compiled-pattern class is sealed, with a subclass for each shape, such as an
exact literal, a literal alternation, a fixed-width span, or a scan plan. A
call site that sees one pattern sees one implementation and one profile, so
the JIT can inline the route rather than dispatching through a strategy
switch.

**Generated bytecode goes further.** With the option enabled, an eligible
pattern gets its own hidden JVM class: each plan operation becomes
straight-line code and the plan's data become constants. A caller that holds
the pattern as a constant, as a query engine generating code per query does,
lets the JIT inline the pattern into its own loop. The README puts generated
patterns at typically about 1.5–2× faster than the default compiled form. See
[Binding a compiled pattern](reference/REGULATOR_API.md#binding-a-compiled-pattern).

**The JIT targets the CPU it runs on.** Vector API scans compile to the
running CPU's vector instructions, and the few loops whose best form differs
by platform, such as candidate tests on Graviton's SVE and on Intel, have
separate implementations.

**Matching allocates little.** Inputs and captures are views of the caller's
bytes (Airlift `Slice` objects), and a matcher reuses its capture buffer and
workspaces from one match to the next.

## Where Regulator does not win

The [README summary](../README.md#performance) puts Regulator behind the Rust
crate on text processing, on Graviton with native access and reused patterns.
The [benchmark report](https://airlift.github.io/regulator/benchmarks/) shows
where that comes from:

- **Line search, captures, and some counts.** The crate's clear wins are
  mostly in grep-style searches over many lines and in capture-heavy
  operations, such as the Ruff and Caddy workloads, and in some counts that
  sum matched lengths, such as dictionary search over many English words.
  Counting over the classic book-text corpora is close to parity overall,
  with a few clear crate wins.
- **Adversarial patterns.** Rows built to stress backtracking engines, such as
  `imported/rsc/no-exponential` and `curated/06-cloud-flare-redos/original`,
  run faster on native RE2 and the crate. The report groups them as
  adversarial and stress workloads, which it notes are not representative of
  typical application performance.
- **Intel.** The README summary is measured on Graviton. Several everyday
  `contains` rows lose to the crate more clearly on Intel than on Graviton.
- **Compilation.** Regulator spends compile time on analysis and plans, so it
  compiles most everyday patterns more slowly than native RE2; compile timings
  are in the report's full download. A pattern used only a few times may not
  earn that back. For a set of everyday patterns, the report's lifecycle rows
  estimate how many uses Regulator needs to catch up with each comparator,
  where the timings allow an estimate.
- **Warm-up.** The results are steady-state measurements after JIT warm-up.
  Until the JIT compiles a pattern's route, it runs more slowly, and a
  generated class needs its own warm-up.

## Further reading

- [FAQ](FAQ.md)
- [Optimization catalog](reference/OPTIMIZATION_CATALOG.md)
- [RE2 optimization registry](reference/OPTIMIZATION_REGISTRY.md)
- [Specialized scan plan](benchmarks/SCAN_PLAN.md)
- [Reverse DFA](reference/REVERSE_DFA.md)
- [Design decisions](../REGULATOR_DECISIONS.md)
- [Benchmark methodology](benchmarks/METHODOLOGY.md)
