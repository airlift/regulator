# Regulator 1.0 benchmark results

The released-artifact campaign and its measurement-quality follow-up are
complete. The public report retains the frozen 5,004-row user-facing workload
inventory and reports mean costs with approximate 95% intervals. The private
archive preserves the complete 9,462-row campaign capture, earlier captures,
diagnostics, and raw evidence.

The campaign measures the published `io.airlift:regulator:1.0` artifact. The
measurement-quality follow-up repairs collection and reporting without changing
the engine. It preserves the initial campaign, every superseded cohort, and
all valid observations in each declared publication cohort.

## Reused-matching results

On Graviton5 with native access enabled, the report summarizes speedups as
follows. These are geometric means over the report's fixed workload categories,
not promises about every regular expression. Patterns are compiled before
timing, and input conversion is excluded.

| Comparator | Workload | Regulator speedup |
| --- | --- | --- |
| JDK Pattern | Text processing | 6.56x |
| JDK Pattern | Everyday matching | 2.07x |
| Native RE2 | Text processing | 1.73x |
| Native RE2 | Everyday matching | 2.38x |
| Joni | Text processing | 3.95x |
| Joni | Everyday matching | 3.83x |
| Trino LIKE | Everyday matching | 5.50x |

Everyday regex results use reused contains/count operations. Text processing
uses the bulk-text population. LIKE uses reused matches, including the optional
comparator DFA settings where shown; its ordered dense-false stress case stays
separate. Compilation, first use, synthetic stress and internal engine
operations do not enter this table. Intel, Graviton4 and pure-Java access have
their own report views.

The [interactive report](https://airlift.github.io/regulator/benchmarks/) shows
mean operation costs and approximate 95% intervals consistently. A candidate /
comparator time ratio below 1 favors Regulator. An interval containing 1 means
there is no established winner; it does not establish equivalence. Expand any
comparison to see absolute costs, process ranges and host counts. The public
download contains the same 5,004 publication rows with full measurements and
provenance, including individual instance identities. Campaign-only diagnostics
are available through the private archive index.

## What the measurement follow-up established

The original campaign completed its jobs, but some measurements needed repair.
Short windows sometimes captured only part of recurring expensive behavior.
Some JVMs also reached different execution states despite stable readings
within each JVM. Native controls often remain stable while Java measurements differ across
forks or hosts. This argues against a single persistent host-speed factor,
but does not rule out transient hardware effects between native brackets.

The replacement collectors isolate each case in fresh JVM forks, keep allocation
profiling separate, use longer qualified warmup and measurement windows, and
report mean costs. Mean costs include recurring expensive work that medians
can hide. Every valid host and fork remains included; no favorable window or
fast host substitutes for an unfavorable observation.

**Decision: retain the completed campaign. No further full rerun is justified
by the diagnostic evidence.** The corrected collection and mean estimator
support the headline comparisons, which change little from the initial 1.0
results. The follow-up materially improves baseline precision, but it does not
establish one-percent precision for every workload.

Independent repeats expose a specific limitation in six R9g fixed-input
comparisons: two 1 KiB literal-callback ratios differ by about 5%, and four
32 KiB no-match replacement ratios differ by 14% to 27%. Their approximate
within-cohort intervals do not overlap. Longer measured windows change the
no-match ratios by only 0.7% to 2.3%, so extending every timing is not a proven
repair. The quality assessment records the difference, the private archive
retains the complete formal cohorts, and it preserves the faster diagnostic
cohorts separately. All six are excluded from headline summaries; both cohorts
agree on the performance direction.

These observations establish limits on repeatability without proving that the
remaining variation is unavoidable. Fine-grained claims for those allocating
operations require targeted replication across collection periods and
availability zones, with matched memory-sensitive controls. The current
three-host intervals describe observed sampling variation and must not be
read as bounds on every future campaign.

| Population | Numeric comparisons | Median relative interval half-width | Half-width above 1% | Half-width above 5% | Interval includes parity |
| --- | --- | --- | --- | --- | --- |
| Complete language collection | 5,280 | 0.93% | 2,509 / 5,280, 47.5% | 331 / 5,280, 6.3% | 58 |
| Complete imported baseline | 4,008 | 1.13% | 2,203 / 4,008, 55.0% | 313 / 4,008, 7.8% | 50 |

Relative half-width is half the approximate 95% interval width divided by the
measured ratio. It describes the precision of the mean ratio, not within-process
variability. These diagnostic counts cover all 9,288 numeric comparisons in
the complete captured campaign, including both access modes and all CPU
families; they are not the row count of the public report. Rows sharing
observations are not independent tests.

Variation within a process, between fresh processes, and across hosts is
reported separately. Coefficient of variation is standard deviation divided
by mean. It describes execution variability; it is not an error bar on the
estimated mean. The [measurement-quality assessment](MEASUREMENT_QUALITY.md)
explains the distinction, diagnostics, limitations and next-campaign plan.

| CPU | Native control | Hosts | Mean cost, ns | Across-host CV | Median absolute before/after change |
| --- | --- | --- | --- | --- | --- |
| R8G | Easy0 | 270 | 22962.69 | 0.06% | 0.03% |
| R8G | Easy2 | 270 | 100959.54 | 0.03% | 0.03% |
| R8I | Easy0 | 270 | 6905.05 | 1.03% | 0.21% |
| R8I | Easy2 | 270 | 70671.87 | 0.41% | 0.08% |
| R9G | Easy0 | 270 | 20793.57 | 0.44% | 0.17% |
| R9G | Easy2 | 270 | 83050.73 | 0.24% | 0.09% |

Those C++ controls run identical 256 KiB workloads before and after the Java
measurements. They quantify variability for those operations. Large native
working sets can vary much more: the formal R9g 16 MiB bit-state comparison has
5.10% Java host CV and 23.26% native host CV even after five-minute warmup and
five-minute measured windows. Its Java/native half-window ratios are 1.869 and
1.892. Long traces contain recurring fast and slow periods, so choosing the
last or fastest JVM would misrepresent the observation.

Replicas use distinct EC2 instance lifetimes, but physical-host placement and
shared environmental effects are not controlled. The approximate intervals
resample paired hosts and whole processes, not individual iterations. With
three or four hosts they describe observed sampling variation and cannot
rule out unobserved machine or compiler states. Native controls help identify
shared changes; they do not supply a universal hardware-noise subtraction.

## Changes from the earlier results

| Comparator / workload | Preliminary development capture | Initial 1.0 | Original samples, means | Updated 1.0 | First / last half |
| --- | --- | --- | --- | --- | --- |
| JDK Pattern / Text processing | 6.540x | 6.549x | 6.549x | 6.562x | 6.561x / 6.563x |
| JDK Pattern / Everyday matching | 2.076x | 2.065x | 2.065x | 2.068x | 2.068x / 2.068x |
| Native RE2 / Text processing | 1.723x | 1.726x | 1.727x | 1.727x | 1.727x / 1.727x |
| Native RE2 / Everyday matching | 2.408x | 2.383x | 2.378x | 2.383x | 2.383x / 2.383x |
| Joni / Text processing | 4.049x | 3.954x | 3.954x | 3.954x | 3.954x / 3.953x |
| Joni / Everyday matching | 3.869x | 3.832x | 3.840x | 3.826x | 3.827x / 3.825x |
| Trino LIKE / Everyday matching | 5.504x | 5.482x | 5.519x | 5.502x | 5.503x / 5.501x |

The initial and updated 1.0 columns use identical headline membership. The
original-mean column separates changing the estimator from changing the samples.
The preliminary capture used development revisions on C-family machines; it
is a historical comparison, not an isolated experiment on a code change.
Both user-facing publication captures remain in the
[report manifest](../../benchmark-report/data/manifest.json). The complete
campaign captures are indexed by the private evidence archive.

Using means for both sets of samples, 3,228 of 9,288 numeric ratios change by more than 1%, 1,934 by more than 5%, and 1,126 by more than 10%. These counts compare measurement cohorts; they are not engine regressions or failed-measurement counts.

Two concrete cases that prompted the follow-up:

| Case | Initial median ratio | Original mean ratio | Updated mean ratio | Updated Regulator / comparator cost, microseconds |
| --- | --- | --- | --- | --- |
| 32 KiB capture split, R8i pure Java | 1.149 | 1.449 | 1.188, interval 1.145 to 1.216 | 333.43 / 280.64 |
| 256 KiB easy2 DFA, R9g native access | 1.404 | 1.480 | 0.829, interval 0.827 to 0.832 | 68.83 / 83.02 |

## Measurement identity and coverage

| Item | Identity |
| --- | --- |
| Release source | `68e42b7d2a104798524189aac7e2a008b49c3d4a` |
| Production tree | `1a0bc1dbab54333e4615f29440de243d1edc4940` |
| Published JAR SHA-256 | `8f2591e7cf1d3e3a92cda61a63a5b8feda8ecbd2b71b9d077dc257f62b61fb75` |
| Native RE2 | `972a15cedd008d846f1a39b2e88ce48d7f166cbd` |
| Rebar corpus | `463d00f31887e84c38467805b9e3122c314b9521` |
| Trino comparator | `c7503d170344c4e266f03fdb39e53c82aa7e3594` |
| Airlift Joni | `2.1.5.3` |
| JDK | Temurin `25.0.4+7` |

Public APIs load the released JAR. Internal diagnostics use its matching
production source. Collector commits are recorded separately in the capture
and archives; reorganizing collector history does not change release identity.

The current public capture contains 5,004 frozen publication rows: 4,836
numeric comparisons and 168 explicit nonnumeric outcomes. The page layout
shows 4,176 of those rows and keeps 828 publication rows, such as compile-only
measurements, in the public download. The private complete capture preserves
all 9,462 campaign row identities: 9,288 numeric comparisons and 174 explicit
nonnumeric outcomes. The follow-up replaces 3,834 complete-campaign rows;
other numeric rows use the original observations with the mean estimator.

The original campaign completed 2,767 qualification, primary and selected
confirmation jobs. The follow-up uses 810 Trino baseline batches, 1,233 language
batches and 180 selected traditional/LIKE batches, followed by nine exact easy2
correction jobs, or 2,232 physical jobs. The last nine jobs also uniformly
supersede the nine earlier easy2 control batches, leaving 2,223 selected
publication jobs.
These replace observations within the existing workload population; they do
not add 2,223 distinct regular expressions. Diagnostic hosts and infrastructure
retries are counted separately.

All collection uses Oregon Spot capacity. Ordinary workers have two vCPUs and
16 GiB RAM. Affected R9g Trino, lifecycle and traditional/LIKE cohorts use
`r9g.2xlarge`, with eight vCPUs and 64 GiB RAM. Other language work remains on
`.large` instances, including the slow dictionary comparison. The measured
heap stays at 8 GiB; the benchmark CPU allowance is separately fixed by each
protocol. Every replacement comparison records its verified allocation.

The archived original baseline's 1,535 unresolved comparisons and 6,670 flagged
rows mixed timing screens, performance objectives and allocation objectives.
They are historical ledger counts, not current failed-measurement counts.
Positive fractional allocation observations remain positive; they do not
establish an exact-zero contract. Unsupported operations and bounded
non-completion retain explicit nonnumeric outcomes.

## Cost and reproduction budget

The initial campaign recorded **1,983.84 wrapper machine-hours**. Its ledger
estimated **$144.96**, including an hourly overhead allowance, plus a separate
**$20 ancillary reserve**, or about **$165**. Wrapper lifetimes include local
preparation and recovery and are not exact billed running time.

The measurement-quality follow-up, including diagnostics, failed attempts,
superseded cohorts and the final corrections, has **3,191 allocated
instance lifetimes**. Launch/shutdown events give approximately
**2,441.90 instance-hours and $210.00 in compute**.
Observed inactivity and verified cleanup bound the accounting duration at
**2,453.67 hours and $210.94** using the saved
per-type Spot quotes. All recorded allocated instances have closed lifetimes.
These are duration and price estimates, not an AWS invoice. Pending time is
included; Spot prices are not integrated over each instance's actual lifetime.

| Follow-up instance type | Upper estimated instance-hours | Saved hourly Spot quote | Compute estimate at that quote |
| --- | ---: | ---: | ---: |
| `r8g.2xlarge` | 5.64 | $0.2373 | $1.34 |
| `r8g.large` | 705.22 | $0.0601 | $42.38 |
| `r8i.2xlarge` | 5.11 | $0.2124 | $1.08 |
| `r8i.large` | 746.83 | $0.0577 | $43.09 |
| `r9g.2xlarge` | 449.47 | $0.2104 | $94.57 |
| `r9g.large` | 541.40 | $0.0526 | $28.48 |

Together, the original estimate and additional compute are about
**$376**, with the follow-up's EBS, storage, public IPv4, transfer and taxes
additional. The original reserve and follow-up compute have different accounting
bases; this sum is a planning estimate, not a reconciled total charge.

For a fresh run of the current protocols, the table below sums observed
successful wrapper durations by actual instance type. It omits superseded
cohorts and exploratory diagnostics, includes original jobs needed for
unchanged comparisons, and adds an explicit 20–30% allowance for qualification
and retries. The second scope also includes the original supplemental baseline
measurements. It does not claim that those historical diagnostics use the new
public-report protocol.

| Reproduction scope | Successful-job reference hours | Hours with 20–30% allowance | Compute with 20–30% allowance at saved quotes |
| --- | ---: | ---: | ---: |
| Frozen publication workload set | 2,580 | 3,096–3,354 | $258–$280 |
| Publication workloads plus supplemental diagnostics | 2,780 | 3,336–3,614 | $273–$296 |

The allowance is a planning assumption. Changed software, hardware or measurement requirements can require new diagnostics. Capacity shortages and sequential qualification can extend wall-clock time without a proportional increase in machine-hours. Ancillary costs and price changes are additional.

A future run should sum machine-hours separately for each instance type,
multiply by its own Spot prices, then allow for interruptions and ancillary
costs. An eight-vCPU `.2xlarge` hour is not interchangeable with a two-vCPU
`.large` hour. The original roughly $200 budget described the initial campaign;
it is not the cost of the expanded follow-up or a guaranteed future ceiling.
The [AWS guide](../../tools/re2-benchmark/aws/README.md#machine-hours-and-cost)
records allocation and accounting details.

## Original Rebar diagnostics

These measurements retain the original protocol and estimator. They are archived
diagnostics and were not recollected by the measurement-quality follow-up.

The completed Rebar cohort includes all 180 primary hosts and its 49 selected
confirmation hosts. The table below uses R9g native access and every numeric
comparison in the curated population, including serious outliers and timing
warnings. It keeps compilation separate from matching. These are geometric
means of paired-host Regulator time divided by comparator time; below 1 is
faster. They are not the public language report's everyday or text-processing
headline.

| Rebar operation | Native RE2 ratio | Numeric cases | Joni ratio | Numeric cases |
| --- | ---: | ---: | ---: | ---: |
| Compile | 1.396 | 10 | 6.032 | 10 |
| Count | 0.368 | 18 | 0.110 | 18 |
| Count captures | 0.039 | 1 | 0.179 | 1 |
| Count spans | 0.888 | 6 | 0.013 | 5 |
| Grep | 0.862 | 1 | 0.451 | 1 |
| Grep captures | 0.831 | 5 | 0.349 | 5 |

All ten curated compile cases are slower than native RE2 on this platform.
Among the 18 count cases, five are slower than native RE2 even though the
geometric mean favors Regulator. No count case is slower than Joni by its
median ratio. Those observations do not turn unresolved rows into reliable
winner claims: two count comparisons remain unresolved against each comparator,
one grep-capture comparison against each, and one compile comparison against
Joni. The individual tables retain those flags. A one-case category describes
that case only.

Across both Rebar populations, all platforms and memory modes, 302 of 2,754
numeric comparisons remain unresolved after confirmation. The fourth host
can expose additional variation; it is not guaranteed to reduce the unresolved
count. The full tables preserve nonnumeric outcomes and the extended corpus
separately. `general-classes.tsv` excludes serious outliers and must not be
substituted for the complete population used here.

## Original memory, scaling, and concurrency diagnostics

These measurements also retain their original protocol and estimator.

The retained-memory scalar combines several census populations. Compiled
rows use per-root average heap; the other lifecycle rows subtract caller-owned
input from total retained heap. Off-heap storage is added separately. This is
not a universal per-pattern footprint. The evidence package includes each
accepted host's per-workload census tables so compiled, warm-pattern, and
active-matcher results can be compared separately.

Read every scaling curve with its recorded size parameter. In the accepted
route diagnostics, a self-loop scanner curve varies the number of exit bytes
while holding input length fixed. A fitted class for that curve does not
describe growth with input length. Fits over a finite range do not establish
asymptotic complexity or universal parity with native RE2.

`searchSharedColdWave` reports nanoseconds for an entire synchronized wave of
searches. The measurement includes the Phaser barriers and coordinator's
result collection. Cache reset occurs in invocation setup. Aggregate searches
per second are `workerCount * 1e9 / wave_ns`. Sixteen workers oversubscribe
the eight-vCPU host. These measurements describe the selected shared-cold
workload, not service capacity or a tail-latency guarantee.

For the 262,144-byte input with native access, the final wave measurements are:

| Platform | One worker, microseconds | Eight workers, microseconds | Sixteen workers, microseconds | Eight-worker aggregate throughput versus one |
| --- | ---: | ---: | ---: | ---: |
| R8i | 271.950 | 416.348 | 920.076 | 5.23x |
| R8g | 300.289 | 312.356 | 656.963 | 7.69x |
| R9g | 251.038 | 268.083 | 531.435 | 7.49x |

These rows have three independent Intel hosts and four hosts on each Arm
platform after the selected confirmations. All eleven concurrency hosts
completed with exit zero, and peak resident memory stayed below 8.85 GB.
Twelve short-input rows still exceed the 5% host-variation threshold; the
evidence retains them as unresolved. Do not generalize the large-input table
to those short-input waves.

## Reproduction, verification and archives

The [methodology](METHODOLOGY.md), [qualification plan](QUALIFICATION_PLAN.md),
[replacement collector guide](../../tools/re2-benchmark/baseline/REMEASUREMENT.md)
and [language collector guide](../../tools/re2-benchmark/language/README.md)
define the protocols. The archived inventories, source snapshots, qualification
decisions and controller inputs record each collection exactly.

The [analysis utility](../../benchmark-report/analysis/README.md) reproduces
every published numeric mean, ratio, interval and variation component from
the archived normalized inputs, without provisioning machines. The full raw
archives retain the source evidence behind those inputs, including warmup
traces, native brackets, allocation and compiler diagnostics, environment
manifests and cleanup receipts.

The [retrieval index](results-1.0/raw-archives.json) retains the original campaign archives and exact object versions for every follow-up segment. All 2,945 attempts from the main follow-up are preserved in 47 verified segments; final correction attempts have a separate segment. Source-history bundles preserve every measured collector commit. Each follow-up archive was downloaded by exact version, checked against its SHA-256, and independently restored. The final analysis archive contains normalized replay inputs, qualification decisions, cost and cleanup evidence, and the reviewed report. Storage is private and requires credentials authorized for the evidence bucket.

The offline collector checks, Java test compilation, report build and data checks passed. Independent replay verified every published numeric estimate and interval against the saved normalized observations. Coverage checks preserve all original identities and both supported memory modes. Every temporary campaign instance and owned transfer resource is closed; permanent evidence archives remain available.

Version 1.0 does not win every comparison. Adverse results and their measured
uncertainty remain in the report. Engine optimizations belong to a later
release and a separately identified measurement campaign.
