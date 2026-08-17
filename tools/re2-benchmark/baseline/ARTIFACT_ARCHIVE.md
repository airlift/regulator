# Permanent baseline artifact archive

`archive_results.py` preserves a complete accepted campaign outside the local
checkout. This permanent archive is separate from the temporary S3 buckets
used to transfer host results. Those buckets must still be deleted and pass
the cleanup checks.

## Archive format

The tool creates a deterministic `tar.gz` with the fixed root
`campaign-results/`. Entries are sorted by UTF-8 path and have normalized
ownership, timestamps, and modes. Symbolic links and special files are rejected
so the archive cannot refer to data outside the result tree.

The source tree is checked again after writing the archive. Archival fails if a
file or directory was added, removed, or changed while the snapshot was being
created.

`campaign-results/ARTIFACT_SHA256.tsv` records the path, byte size, and SHA-256
of every regular source file. The manifest does not list itself. The source
directory must not contain a file with this reserved name, and archive outputs
must be outside the source directory.

The tool also writes the same manifest bytes beside the retrieval metadata as
`<retrieval-metadata>.source-manifest.tsv`. Retrieval metadata and S3 object
metadata record its SHA-256. The reducer uses this exported copy to prove that
each recursively verified host artifact, including finalized capacity
evidence, is part of the immutable archive without downloading the archive.

## Permanent S3 storage

The default destination is the account-specific bucket
`airlift-regulator-baseline-<account>-<region>`. The AWS CLI uses its standard
credential chain unless `--profile` selects a named profile. The default region
is `us-west-2`. A different dedicated bucket may be supplied with `--bucket`.

The tool creates the bucket when necessary and enforces:

- S3 Object Ownership with ACLs disabled
- all four S3 public-access blocks
- no public bucket policy
- bucket versioning
- S3-managed AES-256 encryption
- no lifecycle rule that could expire or transition the evidence

Artifacts use this key:

```text
pre-review-baseline/<candidate-commit>/<campaign-id>/campaign-results.tar.gz
```

The historical `pre-review-baseline` key prefix is retained for archive
compatibility; the utility also stores release qualification campaigns.

The tool checks the complete version history, including delete markers, and
uploads with `If-None-Match: *`, so an existing candidate/campaign artifact is
never overwritten or silently replaced after deletion. The request
includes the archive SHA-256, source-manifest SHA-256, candidate identity,
campaign identity, and file count as object metadata. After upload, the tool
reads that exact object version and verifies its version ID, SHA-256 checksum,
byte size, encryption, and metadata.

## Usage

Run this after the accepted raw campaign is complete and before final reduction
and report generation. Preliminary reduction used to select confirmation jobs
does not require a campaign archive.

```bash
python3 tools/re2-benchmark/baseline/archive_results.py \
    /durable/path/<campaign-id>/results \
    /durable/path/<campaign-id>/campaign-results.tar.gz \
    /durable/path/<campaign-id>/retrieval-metadata.tsv \
    --candidate-commit <candidate-commit> \
    --candidate-ref refs/benchmarks/<candidate-name> \
    --campaign-id <campaign-id>
```

Keep these paths outside Maven's `target` directory. This command writes to
AWS; agents require explicit authorization for that upload and destination.

The retrieval TSV has one row containing the immutable S3 URI and version ID,
archive SHA-256, candidate commit and ref, campaign ID, absolute source
directory, archived source-file count, and source-manifest SHA-256. Keep the
exported source manifest with this small retrieval record and commit both with
the baseline report; keep the raw archive in the permanent private bucket.

The utility uses only the Python standard library and AWS CLI. It never deletes
the permanent bucket or any archived object version.
