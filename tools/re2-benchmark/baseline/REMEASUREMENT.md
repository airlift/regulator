# Isolated Trino replacement measurements

`remeasure.py` reuses the original exact-row manifest, semantic preparation, pinned
comparators, release attestation and AWS transport. It collects fresh timing
for `trino-operations` and `trino-final-line`. It does not rewrite the original
baseline or convert diagnostic output into replacement results.

The frozen protocol is in `remeasurement-protocol.json`. Its independent pilot
must pass before `qualified_by_pilot` can be enabled. The attestations under
`qualification-evidence/` record the archived decision hash, evidence hashes,
protocol summary and verdict without publishing private host identifiers. The
controller and worker both verify the attestation hash, archived decision hash
and successful verdict before accepting a plan. Each exact case runs five independent JMH
forks with explicit G1, an 8 GiB pre-touched heap, twenty one-second warmups
and twenty one-second measured iterations. One benchmark thread may run on
either of the two allowed CPUs; compiler and GC threads have the same
allowance. This is single-thread throughput with normal JVM helper threads.

Primary timing has no profiler. A separate fresh JVM records allocation after
all primary timings, with three warmup and three measured iterations. Allocation
samples never enter timing scores. Native RE2 `easy0` and `easy2` controls run
before and after each job. Preserve every valid fork, including slow execution
states. The estimator averages iteration means, then fork means, then host
means with equal weights at each level. Publication must represent process and
host uncertainty rather than counting individual iterations as independent.

Four logical operations form one partition. The two shards have 46 and 44
partitions, respectively. Three independent hosts on each of R8i, R8g and R9g
produce 810 required host jobs, 9,720 engine/host selections and 2,160 public
comparisons. Regulator's two memory modes and one shared Joni comparator run
on each host. The primary timing floor is 540 machine-hours, before builds,
semantic verification, allocation collection, native controls and retries.

Inspect one partition without AWS access:

```sh
python3 tools/re2-benchmark/baseline/remeasure.py \
  --shard trino-final-line --partition 0
```

Use the existing [AWS runner](../aws/README.md) with its frozen source archive,
release identity and Spot-only settings. The replacement protocol freezes the
instance type and allocated vCPU count for every platform, and the runner uses
that allocation when it provisions the host. Set
`BASELINE_PROTOCOL=smoke`, `BASELINE_PROTOCOL_QUALIFICATION=false`,
`REGULATOR_RELEASE_VERSION=1.0`, the original `CAMPAIGN_SHARD_ID`, and
`BENCHMARK_REMEASUREMENT_PARTITION` to the selected zero-based partition.
Replacement mode skips the old short timing smoke and its drift threshold.
It still verifies every required route, semantic test, native-access probe,
pinned differential verifier and published JAR. `semantic-preparation.json`
binds that evidence to the host. Recovered evidence is checked independently.
The wrapper checks the plan before provisioning. Diagnostic and replacement
modes are mutually exclusive. Every required partition/platform combination
needs replicas 1, 2 and 3 with distinct EC2 lifetimes.

Artifacts live under `remeasurement/`, separately from preparation receipts and
`diagnostics/`. Completion binds the plan, raw timings, JVM arguments,
allocation output, native controls, release JAR and semantic evidence.
Independent validation rejects missing samples, wrong selections, changed
versions or arguments, profiled primary timing, swaps, OOM events and excessive
resident memory. Noisy but valid timing is not an infrastructure failure and
must not be retried selectively.

After each primary command, the worker uploads a partial archive between
measurements. The existing controller recovers all uploaded objects before
removing its temporary bucket. A Spot interruption may lose the active command;
completed commands remain available as partial evidence. Partial evidence does
not satisfy a completed host job. Keep failed attempts and their resource costs
in the campaign ledger.

## Selected traditional and LIKE operations

`remeasurement-retained-protocol.json` selects 44 original logical operations
across traditional RE2 and LIKE shards. The selection includes observed
persistent timing-window shifts, matched diagnostic cases, and stable controls.
It covers 20 partitions, 180 independent host jobs and 86 Java selections per
platform/replica set. The primary Java measurement floor is 114 machine-hours.
Both candidate memory routes and the original SQL/optimized LIKE comparators
remain selected wherever those systems occur in the original manifest.

This protocol uses sixty one-second warmups and twenty one-second measured
iterations in each of five fresh forks. The exact original native RE2
comparators run before and after Java collection with ten seconds of warmup
and five measured repetitions, in addition to the stable native controls.
The large fanout and fixed-prefix search cases use sixty measured seconds.
Alternate-match engine cases use five minutes each of warmup and measurement,
with one logical operation per partition. These cases use twenty-second native
repetitions. The prospective qualification gate still applies to every override.
Native elapsed time is retained separately from CPU time. The collector
validates both; publication uses elapsed time.

The selection helper verifies the original row-manifest hash and rejects
unknown, duplicated or incomplete selections. A non-Trino shard automatically
selects this protocol. Its `qualified_by_pilot` gate must be enabled only after
the independent diagnostics support it. Validate representative formal jobs
on all three CPU families before expanding the frozen partition inventory.
The existing Trino replacement protocol and its 810 jobs are unchanged.


## Allocations and qualification evidence

The final 1.0 follow-up uses `r8g.large` and `r8i.large` for the Trino and
selected traditional/LIKE measurements, and `r9g.2xlarge` for their R9g
counterparts. The measured heap remains 8 GiB and the allowed CPU set remains
`0,1`; allocating eight vCPUs does not make the benchmark multithreaded.
Verify instance type, allocated vCPUs, heap, replica and environment checksums
for every accepted host. Never combine different allocations within a published
comparison without defining a separate population.

The larger R9g allocation completed exact partitions that exceeded the runtime
budget on small hosts and reduced the observed kernel-CPU overhead. Fixed
search controls also supported that choice. Larger R8g/R8i controls did not
establish a general stability improvement, so those families retain small
workers. These are measured configuration choices, not an identified kernel
mechanism or a universal one-percent precision claim.

The archive preserves every qualification control, superseded cohort, failure
and valid observation available from the workers. Whole cohorts are superseded
uniformly. No individual host or fork is dropped because its timing is noisy,
slow or unfavorable. Exact integration jobs validate the qualified protocol
through the production collector before expanding to the complete inventory.
