# AWS baseline qualification

The AWS tools run two kinds of jobs: `baseline-shard` and `language-batch`.
Both are needed for full qualification. This guide gives the baseline commands;
see the [language guide](../language/README.md) for language collection.

`baseline/run-campaign.py` plans and controls baseline smoke, primary, and
confirmation runs. `language/fleet.py` prepares language plans, and
`language/campaign.py` controls their collection and acceptance. Standalone
engineering diagnostics are separate from both.

Do not collect performance evidence on a development machine. Local commands
may validate manifests, scripts, and protocol behavior, but performance
decisions require the pinned R8i, R8g, and R9g hosts in `baseline/platforms.tsv`.

## Machine-hours and cost

Estimate machine-hours from the job inventory and full smoke timings,
including startup and retries. Parallel workers reduce elapsed time, not
aggregate machine-hours. Refresh regional prices before collection.

Ordinary workers use two-vCPU `r8i.large`, `r8g.large`, and `r9g.large`
instances with 16 GiB RAM and 40 GiB gp3 roots. The `lifecycle-shared-cold`
shard uses the corresponding eight-vCPU `.2xlarge` instance and affinity
`0-7`; ordinary workloads use CPU `0`. The measured heap remains 8 GiB.
Forked JMH launchers use 64–256 MiB and pass the measured JVM options to
the fork. Host receipts verify CPU count, affinity, memory, and disk headroom.

Use `--release-version 1.0` to measure the published JAR. The collector
checks its checksum and class origins, and requires diagnostic production
sources to match the release. `--smoke-protocol qualification` exercises
full timing during smoke; both selections are frozen on first execution.
Phase deadlines account for total and per-platform host waves plus a retry
wave. `--phase-timeout-seconds` sets an explicit frozen deadline.

For a focused change, budget only the affected cases and protected controls.
A source bracket measures three legs on each host, so include all three in its
estimate. That does not require running the entire release campaign three
times. Preserve accepted work and resume with unchanged frozen inputs instead
of starting collection over.

## Qualification inputs

Execution requires:

- a clean committed Regulator candidate
- a candidate ref under `refs/benchmarks/`
- the deterministic candidate archive and provenance produced by
  `baseline/freeze-candidate.sh`
- the pinned Trino checkout for shards that compare Joni or Trino LIKE
- the pinned Rebar checkout described in `../rebar/README.md`
- working AWS credentials from the standard AWS credential chain, or an
  explicitly selected profile, and the configured region

Keep the candidate archive, provenance, and result roots outside Maven's
`target` directory. The controller rejects volatile paths and revalidates the
candidate, manifests, and accepted receipts whenever it resumes.

The checked-in inputs are:

- `baseline/rows.tsv`: every accepted benchmark row
- `baseline/shards.tsv`: the qualification shards, including focused LIKE lifecycle collection
- `baseline/shard-dispatch.tsv`: route and system ownership
- `baseline/platforms.tsv`: instance, AMI, JDK, and package identities
- `baseline/comparators.tsv`: pinned comparator identities
- `baseline/protocol-representatives.tsv`: bounded/full protocol equivalence

Run the validators before allocating hosts:

```bash
python3 tools/re2-benchmark/baseline/validate-manifest.py
python3 tools/re2-benchmark/baseline/validate-protocol-representatives.py
python3 tools/re2-benchmark/baseline/validate-shard-plans.py
python3 tools/re2-benchmark/baseline/validate-mode-inventory.py
```

## Freeze a candidate

`freeze-candidate.sh` runs the full build, verifies the production engine tree,
creates an immutable local benchmark ref, and writes a reproducible archive and
provenance record:

```bash
tools/re2-benchmark/baseline/freeze-candidate.sh \
  refs/benchmarks/<candidate-name> \
  /durable/path/candidate.tar.gz \
  /durable/path/candidate-provenance.tsv
```

The output files must not already exist. The script never moves an existing
benchmark ref.

## Plan and execute

Without `--execute`, the controller writes the exact job manifest without
allocating AWS resources:

```bash
python3 tools/re2-benchmark/baseline/run-campaign.py \
  smoke <campaign-id> \
  --result-root /durable/path/<campaign-id>/smoke
```

Execute the smoke after inspecting that plan:

```bash
TRINO_DIR=/path/to/trino \
python3 tools/re2-benchmark/baseline/run-campaign.py \
  smoke <campaign-id> --execute \
  --candidate-ref refs/benchmarks/<candidate-name> \
  --candidate-archive /durable/path/candidate.tar.gz \
  --candidate-provenance /durable/path/candidate-provenance.tsv \
  --result-root /durable/path/<campaign-id>/smoke \
  --max-concurrent 64 --release-version 1.0
```

The primary phase requires the accepted smoke root and runs three independent
host replicas for every platform and shard:

```bash
TRINO_DIR=/path/to/trino \
python3 tools/re2-benchmark/baseline/run-campaign.py \
  primary <campaign-id> --execute \
  --candidate-ref refs/benchmarks/<candidate-name> \
  --candidate-archive /durable/path/candidate.tar.gz \
  --candidate-provenance /durable/path/candidate-provenance.tsv \
  --smoke-results /durable/path/<campaign-id>/smoke \
  --result-root /durable/path/<campaign-id>/primary \
  --max-concurrent 64 --release-version 1.0
```

Use confirmation only for the bounded list emitted by preliminary reduction:

```bash
TRINO_DIR=/path/to/trino \
python3 tools/re2-benchmark/baseline/run-campaign.py \
  confirmation <campaign-id> --execute \
  --candidate-ref refs/benchmarks/<candidate-name> \
  --candidate-archive /durable/path/candidate.tar.gz \
  --candidate-provenance /durable/path/candidate-provenance.tsv \
  --primary-results /durable/path/<campaign-id>/primary \
  --confirmation-jobs /durable/path/confirmation-jobs.tsv \
  --result-root /durable/path/<campaign-id>/confirmation \
  --max-concurrent 64 --release-version 1.0
```

The controller can start with fewer than 64 hosts; that number is a ceiling.
Jobs try Spot first, then the same On-Demand instance type. They wait in the
queue when quota or capacity prevents a launch. Interrupted Spot jobs restart
in a fresh host session.

Set `AWS_PROFILE` before any execution command when selecting a named profile;
otherwise the AWS CLI uses its standard credential chain. `TRINO_DIR` is
required because the full campaign includes Trino and Joni shards.

## Single-job wrapper

The controllers use `run-campaign.sh` to run one AWS job. A
`CAMPAIGN_MODE=baseline-shard` job selects one architecture, platform, shard, and
replica. A `CAMPAIGN_MODE=language-batch` job selects one language batch and its
frozen inputs. The wrapper verifies the archive, provisions an isolated
instance, uploads inputs through a private temporary S3 location, downloads
the result, and verifies instance, IAM, and S3 cleanup.

The fleet controller should normally invoke this wrapper. Direct invocation is
appropriate only while diagnosing one qualification job and still requires all
pinned identity variables.

`run-host.sh` runs on the instance. It accepts the same two
modes, verifies the AMI, JDK, package, candidate, comparator, and manifest
identities, and delegates to `baseline/run-host-session.sh` or
`language/run-host-session.sh`.

## Recovery and evidence

The controller atomically records each accepted job in a receipt ledger.
On restart, it revalidates saved artifacts, recovers receipts written just
before interruption, and skips accepted jobs. Conflicting receipts, changed
inputs, or unverifiable resources stop the campaign.

Failed jobs record their failure type. Independent jobs continue unless source
identity, resource ownership, or cleanup cannot be verified. Even a successful
benchmark is rejected if its cloud cleanup cannot be confirmed.

See:

- `../baseline/SHARD_DISPATCH.md` for routes and timing protocol
- `../baseline/ACCEPTANCE.md` for host and campaign receipts
- `../baseline/REPORTING.md` for reduction and report generation
- `../baseline/ARTIFACT_ARCHIVE.md` for permanent evidence storage
