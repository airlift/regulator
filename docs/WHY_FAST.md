# Why Regulator is fast

Regulator is a pure-Java regular-expression engine. Its matching algorithms
come from RE2: a lazily built DFA, a one-pass engine (OnePass), a bounded
backtracker (BitState), and an NFA, all with bounded memory and linear time
per search. Those guarantees stay. The speed comes from three habits layered
on top:

1. When match starts are rare, most input bytes never reach an automaton.
   Fast scans find the few places a match can start, and cheap checks reject
   inputs that cannot match at all.
2. When an automaton does run, each step is as cheap as the JVM can make it.
3. Compilation spends effort choosing a route for each pattern, and the JIT
   then compiles that route with the running workload's profile.

Few of these mechanisms began in Regulator. Most were first built in RE2, the
Rust `regex` crate and its companion crates, Hyperscan, ClickHouse, .NET,
Trino, and the string-matching literature, and the
[optimization catalog](reference/OPTIMIZATION_CATALOG.md) credits each one; if
a credit there is wrong, please
[send a pull request](https://github.com/airlift/regulator/edit/main/docs/reference/OPTIMIZATION_CATALOG.md)
that corrects it. The hard part is making them work together across four regex
languages without changing any language's results, and making them fast on the
JVM, where a technique that wins in C or Rust often has to take another form.

This page explains those mechanisms for a curious engineer. The catalog lists
the optimizations with their locations and origins, and the
[benchmark report](https://airlift.github.io/regulator/benchmarks/) has the
measurements.

## Skip what cannot match

A regex search spends most of its time rejecting text. The cheapest rejection
is one that runs no automaton at all.

**Early rejection.**
[Compilation records facts](reference/OPTIMIZATION_CATALOG.md#engine-selection-and-early-rejection)
about every possible match: its minimum length, the bytes an anchored match
can begin with, and, for short inputs, a literal or a few bytes every match
must contain. A short input that fails one of these checks returns no match
before any engine starts. In row-at-a-time workloads with short rows, such as
a query filtering a column, rows that cannot match often end here.

**Literal scans.** When a pattern starts with a literal, a scan looks for a
[pair of its bytes](reference/OPTIMIZATION_CATALOG.md#front-and-back-prefix-scan),
usually the first and last, and checks the whole literal only where both
appear. Testing two positions at once removes most false candidates that a
single-byte search would stop on. On windows of at least 1 KiB with the Vector
API, the scan tests the pair at 16 to 64 positions per step. Otherwise it
scans 8-byte words (SWAR): a prefix that starts with an ASCII byte scans for
that byte and checks the other per candidate, and other literals test both
bytes in each word. An optional byte-frequency table can move the probe to a
rarer byte of the literal. When a literal is in the middle of a pattern, as in
`\w+@example\.com`, the search finds the literal first and then
[runs the automaton backward and forward from it](reference/OPTIMIZATION_CATALOG.md#reverse-inner-literal-search),
a technique adapted from `regex-automata`, the crate that implements the Rust
`regex` crate's engines.

**Multi-literal and byte-set filters.** Patterns that begin with one of a few
literals use a
[nibble-table filter](reference/OPTIMIZATION_CATALOG.md#start-literal-filter)
adapted from Hyperscan's Teddy algorithm, by way of the `aho-corasick` crate's
implementation. It checks up to eight literal groups at three byte positions;
four- and eight-group filters need one new vector load per step, and two-group
filters three. Large literal alternations use an Aho-Corasick automaton ported
from the same crate. Patterns that can only start at a small set of bytes scan
for that set.

**Staying linear.** A fast scan stays fast only while false candidates are
sparse. The scans watch for the opposite case. A literal search whose failed
candidates compare more than 64 bytes beyond what it has covered switches to a
[Knuth-Morris-Pratt search](reference/OPTIMIZATION_CATALOG.md#kmp-handoff-for-dense-false-candidates),
which is linear however dense the near-misses are. Some candidate scans sample
the input first and run only when candidates are sparse, and a scan plan whose
failed attempts in one search exceed its work budget hands the rest of that
search to the ordinary engine.

## Run the cheapest engine for the question

Different questions need different engines. Whether a pattern matches, where
a match ends, where it starts, and what its groups captured each cost
progressively more. Regulator answers each with the least it can.

- A yes-or-no question that no specialized route answers, such as an exact
  literal's scan or a scan plan, runs the forward DFA and stops at the first
  match.
- A match start usually needs a reverse DFA pass.
  [Regulator skips it](reference/OPTIMIZATION_CATALOG.md#match-starts-without-a-reverse-dfa)
  when the answer is already known: a fixed-width match starts its width
  before the end, a pattern that matches empty at every position starts at the
  search start, and a one-character match that ends in a valid UTF-8 character
  starts at that character's lead byte.
- Captures run on RE2's OnePass engine when the pattern is unambiguous. RE2's
  OnePass handles at most four explicit groups.
  [Regulator's handles up to 31](reference/OPTIMIZATION_CATALOG.md#extended-onepass-captures);
  the Rust crate's one-pass engine went past RE2's limit first, to 16. Beyond
  RE2's line and word assertions, Regulator's OnePass checks Rust's CRLF
  anchors and word boundaries, which the crate's engine also checks, Java's
  and Trino's word boundaries, and the Java `$` and `\Z` and Trino `$` that
  also match before a final line terminator. RE2 packs the next state's index
  into each transition's action; Regulator's entry also holds that state's row
  offset, premultiplied as the crate's other DFAs store their state IDs, so a
  step finds the next row without a multiply.
- Ambiguous captures through a matcher run
  [one bounded backtracking pass](reference/OPTIMIZATION_CATALOG.md#direct-bitstate-capture)
  instead of a forward DFA, a reverse DFA, and a capture pass, on input from
  4 KiB up to the backtracker's unanchored limit. That limit keeps upstream's
  256 Kibit visited bitmap, so only small programs, of at most 63 lists,
  qualify. The bitmap keeps the pass linear.

## Use a specialized plan for a common shape

Many real patterns follow a handful of shapes: extract a URL's host, split
`key=value`, count runs of a character class, test for one of a dozen words.
Compilation recognizes these and builds a dedicated route for each.

- **[Scan plans](reference/OPTIMIZATION_CATALOG.md#bounded-scan-plan)**,
  adapted from the programs ClickHouse's regular-expression JIT compiles,
  handle literals, character runs, optional pieces, and captures in a short,
  bounded program. As in ClickHouse, a plan accepts a variable-length run only
  when no byte of the run can begin what follows it, so the run stops exactly
  where a backtracking match would. Plans also bound their optional branches.
  Each unanchored search has a work budget, and a search that exhausts it
  continues on the ordinary engine.
- **[Specialized matchers](reference/OPTIMIZATION_CATALOG.md#specialized-matchers)**
  cover literal alternations, fixed-width spans, ordered literals with gaps,
  word runs, and runs of one byte.
- **SQL `LIKE`** has its
  [own planner](reference/OPTIMIZATION_CATALOG.md#plan-per-pattern-shape): an
  exact match, a prefix, a suffix, a contained literal, ordered literals, or
  literals with `_` gaps each compile to their own routine, and only general
  wildcard patterns use the wildcard matcher.

Each route must produce exactly the results of the ordinary engine, on valid
UTF-8 and, for every language except Trino regular expressions, on any input.
A regular expression that no plan fits runs on the shared engines.

## Make each DFA step cheap

When the DFA does run, its inner loop is a load, a check, and a load. The
question is what those loads cost on the JVM.

**A pointer table.** Eligible DFA searches keep a
[table of 64-bit entries](reference/OPTIMIZATION_CATALOG.md#absolute-pointer-table)
naming the next row, with the state's kind tagged in the low bits, so a match
does not leave the loop. With native access the table is native memory and an
entry is the row's address; without it the table is a Java `long[]` and an
entry is the row's offset, which costs one more addition. Neither form decodes
compressed references. The native form is storage allocated through the JDK's
foreign memory API, not native code. Its benefit depends on the workload. The
report publishes both modes; the
[FAQ](FAQ.md#should-i-enable-the-vector-api-or-native-access) describes the
current pure-Java cost.

**Object rows behind it.** Each DFA state is also an array of references to
the next states' arrays,
[the Java form of RE2's pointer rows](reference/OPTIMIZATION_CATALOG.md#object-rows-with-an-integer-sidecar),
so a step can load the next row directly from the current row. These rows are
authoritative, and searches step on them when no pointer table or paired row
serves the search. An unbuilt transition leaves either loop for a cold path,
which builds the state; special transitions also leave the object-row loop,
while the pointer loop handles their tags directly.

**Two bytes at a time.** For small DFAs on input of at least 256 bytes,
forward searches can use a
[bounded paired table](reference/OPTIMIZATION_CATALOG.md#bounded-paired-table)
that holds the composed transition for each pair of byte classes (as in RE2,
bytes the pattern treats alike share a class), so one load advances two bytes.

The whole table is built only when it fits an estimated 64 KiB, or less when
the DFA's budget has less room. Otherwise the DFA pairs two rows within
16 KiB, chosen from its cached start states and the states that loop on
themselves, but only when exactly two qualify or the budget has room for only
two. Paired rows and the pointer table never run together: the pointer table
waits while paired rows exist, and once a pointer table is allocated the DFA
stops pairing. Pairing also turns itself off when first-match searches keep
finding matches that end fewer than 16 bytes from where the search started.

Brodie, Taylor, and Cytron first described the composed two-byte transition in
2006, for hardware DFAs. Among RE2, the Rust crate's `regex-automata`, PCRE2,
and Hyperscan, none composes a general two-byte transition table; Hyperscan's
McClellan wide states, which collapse linear chains of states, are the closest
analog. On the Trino and Java Sherlock `word-ending-n` book-text searches with
native access, paired rows took about 18% less time than the pointer table on
Graviton, and a few percent less on Intel.

**Self-loops.** Many searches spend long runs in a state that loops on itself,
such as the `\C*` in RE2's `a\C*b` or UTF-8 `[^\n]*`. With the Vector API, a
state that leaves only on one to three exit bytes, or on every non-ASCII byte
plus up to three ASCII bytes, can
[scan ahead to its next exit byte](reference/OPTIMIZATION_CATALOG.md#self-loop-exit-byte-scan)
instead of stepping, as the Rust crate's DFA acceleration and Hyperscan's
McClellan acceleration do. Counts do this for both kinds of state, some
forward searches do it for a matching loop after 32 stepped bytes, and a
reverse search of at least 64 bytes skips back over a loop at its end.

Other forward searches step through non-matching loops, except end-anchored
and full-match searches with at least 64 KiB left: these
[sample 64 transitions first](reference/OPTIMIZATION_CATALOG.md#sampled-self-loop-scan)
and, when at least 90% stay on their state, switch to a loop that tests "the
next row is this row", or to the vector scan when the state has one to three
exit bytes. Counts of a repeated class such as `\w+` have a dedicated loop of
their own.

**No lock per search.** A compiled pattern's DFA cache is shared by every
thread that uses it. RE2 takes a reader lock on the cache for each search.
Regulator's readers load one published table generation and
[take no lock](reference/OPTIMIZATION_CATALOG.md#copy-on-reset-readers), as in
read-copy update. A cache reset publishes new tables and leaves the old ones
to searches already reading them, and a reader that needs a missing entry
retries as an exclusive search holding Regulator's cache write lock.

## Why a JIT-compiled engine can compete

A JIT does not make code fast by itself. What it gives Regulator is
specialization after the pattern and the workload are known.

**Each pattern shape gets its own code path.** Each language's
[compiled-pattern class is sealed](reference/OPTIMIZATION_CATALOG.md#sealed-language-classes-and-shapes),
with a subclass for each shape, such as an exact literal, a literal
alternation, a fixed-width span, or a scan plan. A call site that sees one
pattern sees one implementation, so the JIT can inline the route rather than
dispatching through a strategy switch.

**Generated bytecode goes further.** With the option enabled, an eligible
pattern gets
[its own hidden JVM class](reference/OPTIMIZATION_CATALOG.md#generated-class-is-the-pattern):
each plan operation becomes straight-line code and the plan's data become
constants. A caller that holds the pattern as a constant, as a query engine
generating code per query does, lets the JIT inline the pattern into its own
loop. The straight-line form is adapted from ClickHouse's regular-expression
JIT, and .NET generates code per pattern the same way. The README puts
generated patterns at typically about 1.5–2× faster than the default compiled
form. See
[Binding a compiled pattern](reference/REGULATOR_API.md#binding-a-compiled-pattern).

**The JIT targets the CPU it runs on.** Vector API scans compile to the
running CPU's vector instructions, and the few loops whose best form differs
by platform, such as candidate tests on Graviton's SVE and on Intel, have
[separate implementations](reference/OPTIMIZATION_CATALOG.md#sve-candidate-masks).

**Matching allocates little.** Inputs and captures are views of the caller's
bytes (Airlift `Slice` objects), and a matcher reuses its capture buffer and
[workspaces](reference/OPTIMIZATION_CATALOG.md#matcher-owned-workspaces) from
one match to the next.

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
  compiles more slowly than the JDK and Joni, and than native RE2 on most
  everyday patterns. A pattern used only a few times may not earn that
  back; see
  [How many uses does a pattern need to pay back its compile time?](FAQ.md#how-many-uses-does-a-pattern-need-to-pay-back-its-compile-time).
- **Warm-up.** The results are steady-state measurements after JIT warm-up.
  Until the JIT compiles a pattern's route, it runs more slowly. A generated
  class needs its own warm-up, which on short rows took hundreds of thousands
  to millions of rows before it overtook the default form.

## Further reading

- [FAQ](FAQ.md)
- [Optimization catalog](reference/OPTIMIZATION_CATALOG.md)
- [RE2 optimization registry](reference/OPTIMIZATION_REGISTRY.md)
- [Specialized scan plan](benchmarks/SCAN_PLAN.md)
- [Reverse DFA](reference/REVERSE_DFA.md)
- [Design decisions](../REGULATOR_DECISIONS.md)
- [Benchmark methodology](benchmarks/METHODOLOGY.md)
