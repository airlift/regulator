# Pinned Trino/Joni comparator

This comparator measures Regulator and Airlift Joni through equivalent
Trino-shaped operations. Both implementations consume the exact workload and
input `Slice` produced by `TestingTrinoRegexpBenchmarkInputs`.

The library-output comparisons use `JoniSliceOperations` for `extractAll`,
`split`, and callback replacement. This adapter follows the pinned Trino search
loops, but returns lists of Slice views and passes an immutable list of captures
to callbacks. It does not construct SQL Blocks. The remaining scalar and Slice
operations call the pinned Trino functions directly. Report these measurements
as Joni with a Slice adapter, not full Trino SQL execution.

Before timing, the verifier compares complete output bytes, list structure, and
callback traces against the real Trino SQL functions. The baseline collector
records `trino_output_contract=joni-slice-output-v1`; old Block-based timings
retain their original contract and cannot qualify the new comparison.

Regulator owns the comparator's benchmark and verification sources.
`prepare.sh` extracts Trino commit `c7503d170344c4e266f03fdb39e53c82aa7e3594`
from a Git archive into `target/`, builds it in isolation, and compiles the
comparator against Trino's Joni operation classes. It neither modifies nor
builds from a developer's Trino checkout.

The preparation fails unless Trino has the expected source tree and resolves
Airlift Joni `2.1.5.3` with SHA-256
`521c1bcc51d5b1de83dcdf285f48c01afd5dbb434b34fdc61442e63b67562efe`.
It emits `target/trino-joni-comparator/provenance.properties` with the exact
source and artifact identities.

The runtime classpath excludes Trino's historical RE2J dependency. Verification
fails if that dependency or an unexpected benchmark method enters the
comparator.

Prepare and verify with:

```bash
tools/re2-benchmark/trino-joni/prepare.sh
tools/re2-benchmark/trino-joni/verify.sh
```

`TRINO_COMPARATOR_TRINO_GIT_SOURCE` may point to a local Trino Git repository
to avoid downloading objects. The source repository is read only; the pinned
commit is archived and extracted beneath Regulator's `target/` directory.

Run the paired JMH matrix with:

```bash
tools/re2-benchmark/trino-joni/run.sh target/trino-joni-comparator/results.json
```

The same prepared comparator also provides a KLV-protocol engine for the pinned
Rebar workload matrix:

```bash
tools/re2-benchmark/trino-joni/run-rebar.sh --version
```

In normal use Rebar invokes `run-rebar.sh` from its generated engine definition;
the runner consumes the workload's KLV payload on standard input and writes
Rebar timing samples on standard output. It requires a completed `prepare.sh`
run and uses the same pinned Joni artifact and provenance record as the
Trino-shaped JMH comparisons.

Additional arguments pass directly to JMH. For a short execution smoke test
on a designated AWS host, without forks or warmup:

```bash
tools/re2-benchmark/trino-joni/run.sh \
    target/trino-joni-comparator/smoke.json \
    -f 0 -wi 0 -i 1 -r 1ms
```

The deterministic verifier checks all five workloads, both source lengths, and
all eight operations before timing begins. It compares normalized Regulator and
Joni results and checks a frozen SHA-256 over the corpus and operation outputs.

The application-shaped comparator additionally checks 13 patterns against
eight rotating short or medium Slice values apiece, including nonzero Slice
offsets. Three cases are translated from the pinned Trino
`BenchmarkRegexpFunctions`; its two synthetic single-character dot-star probes
remain diagnostic-only and are recorded in the translation ledger. Every other
origin is recorded as a designed control. Run its Joni side with:

```bash
tools/re2-benchmark/trino-joni/run-everyday.sh \
    target/trino-joni-comparator/everyday-results.json
```
