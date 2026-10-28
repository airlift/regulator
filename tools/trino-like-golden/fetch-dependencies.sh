#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
DEPENDENCY_DIR=${TRINO_LIKE_GOLDEN_DEPENDENCY_DIR:-"${ROOT_DIR}/target/trino-like-golden-dependencies"}

# Use the benchmark comparator's Trino pin, so the corpus and the comparator
# follow the same LikeMatcher.
# shellcheck source-path=SCRIPTDIR source=../re2-benchmark/trino-joni/pins.env
source "${SCRIPT_DIR}/../re2-benchmark/trino-joni/pins.env"
TRINO_REPOSITORY=${TRINO_COMPARATOR_TRINO_REPOSITORY}
TRINO_COMMIT=${TRINO_COMPARATOR_TRINO_COMMIT}
TRINO_TREE=${TRINO_COMPARATOR_TRINO_TREE}
LIKE_MATCHER_PATH=core/trino-main/src/main/java/io/trino/likematcher
# The versions Trino uses at that commit: Guava from its airbase 399 parent,
# fastutil from its own pom.xml.
GUAVA_ARTIFACT=com.google.guava:guava:33.6.0-jre
FASTUTIL_ARTIFACT=it.unimi.dsi:fastutil:8.5.19

TRINO_GIT_SOURCE=${TRINO_LIKE_GOLDEN_TRINO_GIT_SOURCE:-${TRINO_REPOSITORY}}

mkdir -p "${DEPENDENCY_DIR}"
DEPENDENCY_DIR=$(cd "${DEPENDENCY_DIR}" && pwd)
GIT_DIR="${DEPENDENCY_DIR}/trino.git"
LIKE_MATCHER_DIR="${DEPENDENCY_DIR}/likematcher"
LIB_DIR="${DEPENDENCY_DIR}/lib"

# A partial clone: the fetch brings the pinned commit's trees but no file
# contents, and the archive below fetches only the blobs it reads.
if [[ ! -d "${GIT_DIR}" ]]; then
    git init --bare -q "${GIT_DIR}"
    git --git-dir="${GIT_DIR}" remote add origin "${TRINO_GIT_SOURCE}"
    git --git-dir="${GIT_DIR}" config remote.origin.promisor true
    git --git-dir="${GIT_DIR}" config remote.origin.partialclonefilter blob:none
fi
git --git-dir="${GIT_DIR}" remote set-url origin "${TRINO_GIT_SOURCE}"
# A local repository's upload-pack ignores the filter and sends every blob
# unless uploadpack.allowFilter is set. For a local source only, set it on the
# upload-pack command, which leaves the source repository unchanged.
if [[ -d "${TRINO_GIT_SOURCE}" || "${TRINO_GIT_SOURCE}" == file://* ]]; then
    git --git-dir="${GIT_DIR}" config remote.origin.uploadpack 'git -c uploadpack.allowFilter=true upload-pack'
else
    git --git-dir="${GIT_DIR}" config --unset-all remote.origin.uploadpack || true
fi

# Test the ref rather than the commit: in a partial clone, looking up a missing
# commit would fetch it lazily, with its whole history.
pinned_ref=refs/heads/pinned
if [[ "$(git --git-dir="${GIT_DIR}" for-each-ref --format='%(objectname)' "${pinned_ref}")" != "${TRINO_COMMIT}" ]]; then
    git --git-dir="${GIT_DIR}" fetch -q --depth=1 --filter=blob:none origin "+${TRINO_COMMIT}:${pinned_ref}"
fi

actual_commit=$(git --git-dir="${GIT_DIR}" rev-parse "${pinned_ref}^{commit}")
actual_tree=$(git --git-dir="${GIT_DIR}" rev-parse "${pinned_ref}^{tree}")
if [[ "${actual_commit}" != "${TRINO_COMMIT}" ]]; then
    echo "Expected Trino commit ${TRINO_COMMIT}, found ${actual_commit}" >&2
    exit 1
fi
if [[ "${actual_tree}" != "${TRINO_TREE}" ]]; then
    echo "Expected Trino tree ${TRINO_TREE}, found ${actual_tree}" >&2
    exit 1
fi

# Archive the package's own tree: archiving the commit with a path filter would
# first fetch every blob in the commit.
rm -rf "${LIKE_MATCHER_DIR}"
mkdir -p "${LIKE_MATCHER_DIR}"
git --git-dir="${GIT_DIR}" archive --format=tar "${TRINO_COMMIT}:${LIKE_MATCHER_PATH}" | tar -xf - -C "${LIKE_MATCHER_DIR}"

rm -rf "${LIB_DIR}"
for artifact in "${GUAVA_ARTIFACT}" "${FASTUTIL_ARTIFACT}"; do
    (cd "${ROOT_DIR}" && ./mvnw -q dependency:copy -Dartifact="${artifact}" -DoutputDirectory="${LIB_DIR}")
done
guava_version=${GUAVA_ARTIFACT##*:}
fastutil_version=${FASTUTIL_ARTIFACT##*:}

printf 'TRINO_COMMIT=%s\n' "${TRINO_COMMIT}"
printf 'TRINO_LIKE_MATCHER_DIR=%s\n' "${LIKE_MATCHER_DIR}"
printf 'GUAVA_JAR=%s\n' "${LIB_DIR}/guava-${guava_version}.jar"
printf 'FASTUTIL_JAR=%s\n' "${LIB_DIR}/fastutil-${fastutil_version}.jar"
