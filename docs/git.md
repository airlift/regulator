# Git

## Branches and messages

Use `user/<username>/<short-description>` for feature branches, with a
hyphen-separated description shorter than 30 characters.

Commit subjects are capitalized imperative phrases, at most 50 characters,
without a trailing period. Separate an optional body with a blank line and
wrap it at 72 characters. Explain the reason for the change rather than
repeating the diff. Do not include AI tools as co-authors.

## Commit structure and verification

Keep each commit a coherent change that builds and passes its tests. Include
the tests for a feature or repair in the same commit. Separate an independent
refactor when that makes review easier; do not split code from its correctness
evidence just to make smaller commits.

Fold repairs into the earliest owning commit where the corrected form is
coherent. After rewriting, check the affected intermediate commits and verify
that the final tree retains the intended changes. See [testing.md](testing.md)
for local checks.

## Publication

Pull requests should explain the change, its motivation, and the evidence a
reviewer needs. Use a short descriptive title.

Agents must have explicit user authorization before pushing or writing to
GitHub. Local commits or history cleanup do not authorize publication.
A `/code-review` PR comment is optional and requires authorization separately
from creating the PR. After an authorized history rewrite, use an exact
`--force-with-lease` when the user requests publishing the rewritten branch.
