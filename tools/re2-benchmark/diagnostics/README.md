# Measurement diagnostics

`pilot-core.json` tests the reported Trino capture/split transient and the RE2
`easy2` process variability, with stable literal and `easy0` controls. It runs
24 primary JMH selections per host: six case/mode combinations, two CPU
allowances, and two iteration durations. Every selection uses five isolated
forks, ten warmup iterations and ten measured iterations. The measured heap is
8 GiB, pre-touched, and production classes come from the checksummed 1.0 JAR.

The short and long variants both isolate each case. Historical baseline batches
shared a JVM between cases, so comparison against the historical batch protocol
also changes process isolation. Do not attribute that difference to duration
alone. CPU allowances `0` and `0,1` affect the entire JVM, including compiler
and GC threads; they do not isolate just the benchmark thread. CPU topology
comes from the host environment record.

Two additional runs capture HotSpot compilation, GC, and safepoint logs, with
the JMH allocation profiler. Keep these separate from primary timing. Native
RE2 `easy0` and `easy2` controls run five repetitions before and after the Java
variants. Even-numbered hosts reverse timing order. Retain every fork and
iteration. Analyze process/host variation and stationarity without dropping
slow executions or replacing scores with a favorable subwindow.

Use the existing AWS runner with `BENCHMARK_DIAGNOSTIC_PLAN=pilot-core`,
`CAMPAIGN_SHARD_ID=trino-final-line`, `BASELINE_PROTOCOL=smoke`, and
`REGULATOR_RELEASE_VERSION=1.0`, plus the usual frozen source, platform and
Spot-only settings. The plan is part of the checksummed source archive.
At this point the runner completes the original smoke before running diagnostics.
The smoke is preparatory evidence and does not replace the isolated timings. The runner's
90-minute deadline, result recovery and cleanup apply to the entire host.

Artifacts are under `diagnostics/` in the host result. `complete.json` exists
only after every planned timing, instrumented run and native control passes
selection/completeness checks. `artifacts.json` hashes the recovered inputs,
commands, release/classpath attestations and raw output. The preparation
receipt verifies inputs and semantics; it is not timing qualification. Diagnostic
output is not formal replacement measurement data and must never be imported
as such. Earlier frozen collectors also ran ordinary smoke timings, whose
receipts remain part of those historical attempts.

Run the offline tests with:

```sh
python3 -m unittest discover -s tools/re2-benchmark/diagnostics
```

`pilot-extended.json` adds the empty-match callback transient and two public
language cases, JDK `hard-32k` and Trino Twain. It runs 28 primary commands and
three instrumented commands per host. Its two original KLV inputs are supplied
as `BENCHMARK_DIAGNOSTIC_INPUT_ARCHIVE`, with compressed archive checksum
`BENCHMARK_DIAGNOSTIC_INPUT_SHA256`. The archive must contain only the two
`<sha256>.klv` files named in the plan. Each payload is checked again on the
worker. The resolved input paths, original plan and input bytes are retained.
The hashes and expected outputs come from the original language manifest.

For these bulk cases, the worker checks Java's complete trace against native
RE2 before collecting timings, in every selected memory mode. It also runs
matched native bulk controls before and after the Java variants and records
the native build receipt. JVM ergonomic flags are captured separately under
both CPU allowances, including the selected garbage collector.

The extended pilot's instrumented runs use three independent forks, ten
one-second warmups and forty one-second measured iterations. This longer trace
keeps the original CPU-0 affinity and tests whether the repeated late-window
slowdowns persist or recur after the
original ten-second measurement window. These traces remain diagnostic only.

The original language suite explicitly used G1, even under CPU-0 affinity.
`pilot-language-g1.json` repeats the four bulk-engine selections with explicit
G1 in both CPU allowances, including the forty-second CPU-0 instrumented trace.
The extended pilot uses ergonomic collector selection as an additional control;
its CPU-0 results must not be described as an exact original-language-protocol
replication. Both plans use the same checksummed input archive. The baseline
pilot retains the baseline's ergonomic collector selection.

`pilot-jvm-controls.json` uses the `traditional-extra-easy2` smoke and its base
Regulator classpath. It compares explicit G1, explicit Serial, and ergonomic
GC with one or two allowed CPUs. A G1 variant holds `ActiveProcessorCount=1`
under both CPU allowances to separate actual scheduling capacity from JVM
processor-count ergonomics. It has ten primary commands and six instrumented
commands per host. The instrumented commands use three forks on each CPU
allowance. Native `easy0`/`easy2` controls and a Java `easy0` control remain.
Plan/shard mismatches are rejected locally before AWS provisioning.

`pilot-warmup.json` holds the four language-engine selections and explicit G1
constant, and extends warmup to sixty one-second iterations before twenty
measured iterations. Three forks under each CPU allowance produce eight
primary commands per host. This prospectively selected window tests whether
the repeated late transitions end before measurement; all samples remain.
The earlier plans provide instrumented traces, so this plan has no separate
instrumented pass. It remains a diagnostic, not replacement qualification.

`pilot-baseline-validation.json` prospectively validates the replacement
protocol on capture/split and empty-match callback replacement, including
both Regulator modes and Joni. It uses five forks, twenty one-second warmups
and twenty one-second measured iterations, explicit G1 and CPUs 0 and 1.
Its six primary commands per host have no timing instrumentation. These
measurements stay diagnostic; later replacement jobs collect fresh results.

`pilot-bit-state.json` tests four original alternate-match BitState sizes after
thirty one-second warmups with twenty measured iterations and five forks.
It compares both CPU allowances with explicit G1. Matched native BitState
controls and a stable DFA control bracket the Java measurements. Separate
compiler/GC traces cover the largest size. This tests the additional
within-process variation found during the retained baseline audit.

## Extended controls

The remaining checked-in plans test retained-operation windows, instance-size
effects, and timing changes discovered during the complete follow-up. Their JSON
files specify the exact cases, memory routes, forks, durations, and native controls.
The plan name does not select an EC2 type; record the actual platform, allocation,
affinity, heap, and frozen source for every host. A two-CPU affinity on an
eight-vCPU instance is a distinct configuration from a two-vCPU instance.

| Plans | Question |
| --- | --- |
| `pilot-retained-capture`, `pilot-retained-search` | Do original retained cases agree with isolated, longer measurements? |
| `pilot-memory-small`, `pilot-memory-large` | Does allocation size affect the exact memory-sensitive workloads? |
| `pilot-search-long-window`, `pilot-bit-state-long-window` | Do slow states settle or recur over longer windows? |
| `pilot-search-bounded-big-fixed`, `pilot-search-bounded-fanout` | Can exact search families be measured within a bounded host job? |
| `pilot-lifecycle-allocation` | Does allocation size affect compilation and first-use measurements? |
| `pilot-final-line-temporal`, `pilot-nomatch-temporal` | How do complete 80-second replacement averages compare with their first 20 seconds? |
| `pilot-compilation-temporal` | Does the observed compilation timing change persist over an 80-second measurement? |
| `pilot-capture-temporal` | Does split BitState capture continue to drift after 60 seconds of warmup? |
| `pilot-callback-temporal` | Does the exact R9g literal callback replacement keep changing after the formal window? |
| `pilot-nomatch-r9g-temporal` | Do the exact R9g 32 KiB no-match plain and callback replacements keep changing after the formal window? |

Analyze every fixed host and fork. Compare the complete-window mean with the
original window, inspect ordered timing and separate compiler/GC traces, and
review the paired engine ratio. A temporal screen prompts investigation; it
does not permit removing a slow host or selecting a later window. Controls on
one CPU family do not directly qualify another family. If a protocol defect
is demonstrated, select a uniform affected-operation replacement cohort before
collecting new publication measurements. Keep the diagnostic and all superseded
observations with their source identities.
