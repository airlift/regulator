#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "Usage: $0 <refs/benchmarks/name> <archive.tar.gz> <provenance.tsv>" >&2
    exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
CANDIDATE_REF=$1
ARCHIVE=$2
PROVENANCE=$3
if [[ ! "${CANDIDATE_REF}" =~ ^refs/benchmarks/[A-Za-z0-9._/-]+$ ]]; then
    echo "Candidate ref must be under refs/benchmarks: ${CANDIDATE_REF}" >&2
    exit 1
fi
if git -C "${ROOT}" show-ref --verify --quiet "${CANDIDATE_REF}"; then
    echo "Candidate ref already exists and will not be moved: ${CANDIDATE_REF}" >&2
    exit 1
fi
if [[ -n $(git -C "${ROOT}" status --porcelain --untracked-files=all) ]]; then
    echo "Candidate freeze requires a clean worktree" >&2
    exit 1
fi
if [[ -e "${ARCHIVE}" || -e "${PROVENANCE}" ]]; then
    echo "Candidate archive and provenance outputs must not already exist" >&2
    exit 1
fi

commit=$(git -C "${ROOT}" rev-parse HEAD)
root_tree=$(git -C "${ROOT}" rev-parse HEAD^{tree})
engine_tree=$(git -C "${ROOT}" rev-parse HEAD:src/main)
(cd "${ROOT}" && ./mvnw clean install)
if [[ -n $(git -C "${ROOT}" status --porcelain --untracked-files=all) ]]; then
    echo "Candidate build changed the worktree" >&2
    exit 1
fi

python3 "${SCRIPT_DIR}/validate-manifest.py"
python3 "${SCRIPT_DIR}/validate-protocol-representatives.py"

mkdir -p "$(dirname "${ARCHIVE}")" "$(dirname "${PROVENANCE}")"
git -C "${ROOT}" archive --format=tar "${commit}" |
    gzip -n > "${ARCHIVE}"
archive_sha256=$(sha256sum "${ARCHIVE}" | awk '{print $1}')
archive_tree=$(gzip -dc "${ARCHIVE}" |
    tar -tf - |
    LC_ALL=C sort |
    sha256sum |
    awk '{print $1}')

manifest_sha256=$(sha256sum "${SCRIPT_DIR}/rows.tsv" | awk '{print $1}')
platforms_sha256=$(sha256sum "${SCRIPT_DIR}/platforms.tsv" | awk '{print $1}')
comparators_sha256=$(sha256sum "${SCRIPT_DIR}/comparators.tsv" | awk '{print $1}')
shards_sha256=$(sha256sum "${SCRIPT_DIR}/shards.tsv" | awk '{print $1}')
protocol_representatives_sha256=$(sha256sum "${SCRIPT_DIR}/protocol-representatives.tsv" | awk '{print $1}')

git -C "${ROOT}" update-ref "${CANDIDATE_REF}" "${commit}" "0000000000000000000000000000000000000000"
if [[ $(git -C "${ROOT}" rev-parse "${CANDIDATE_REF}^{commit}") != "${commit}" ]]; then
    echo "Candidate ref verification failed" >&2
    exit 1
fi

{
    printf 'candidate_ref\tcandidate_commit\troot_tree\tengine_tree\tarchive_sha256\tarchive_file_list_sha256\trow_manifest_sha256\tplatform_manifest_sha256\tcomparator_manifest_sha256\tshard_manifest_sha256\tprotocol_representatives_sha256\n'
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "${CANDIDATE_REF}" "${commit}" "${root_tree}" "${engine_tree}" \
        "${archive_sha256}" "${archive_tree}" "${manifest_sha256}" \
        "${platforms_sha256}" "${comparators_sha256}" "${shards_sha256}" \
        "${protocol_representatives_sha256}"
} > "${PROVENANCE}"

echo "Created immutable candidate ${CANDIDATE_REF} at ${commit}"
echo "Archive SHA-256: ${archive_sha256}"
