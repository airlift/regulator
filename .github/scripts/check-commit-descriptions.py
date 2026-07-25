#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

# The docs ask authors to wrap at 72; hard CI enforcement allows a little slack.
MAX_WRAPPED_LINE_LENGTH = 79

REVISION_RANGE_PATTERN = re.compile(r"^origin/[A-Za-z0-9._/-]+\.\.HEAD$")
URL_PATTERN = re.compile(r"(?:https?://|ssh://|git@|www\.)\S+")
TRAILER_PATTERN = re.compile(
    r"^(?:"
    r"Signed-off-by|Co-authored-by|Reviewed-by|Acked-by|Tested-by|Reported-by|"
    r"Fixes|Refs|Relates-to|Change-Id"
    r"):\s+\S.+$"
)
SCISSORS_LINE_SUFFIX = " ------------------------ >8 ------------------------"


@dataclass(frozen=True)
class CommitDescriptionViolation:
    commit: str
    subject: str
    line_number: int
    length: int
    line: str


def run_git(arguments: list[str], input_text: str | None = None) -> str:
    try:
        result = subprocess.run(
            ["git", *arguments],
            check=True,
            input=input_text,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except subprocess.CalledProcessError as exception:
        print(f"git {' '.join(arguments)} failed:", file=sys.stderr)
        print(exception.stderr, file=sys.stderr)
        raise SystemExit(exception.returncode) from exception

    return result.stdout


def get_commits(revision_range: str) -> list[str]:
    if REVISION_RANGE_PATTERN.fullmatch(revision_range) is None:
        print(
            "Revision range must match origin/<base-ref>..HEAD with a safe base ref.",
            file=sys.stderr,
        )
        raise SystemExit(1)

    output = run_git(["rev-list", "--reverse", "--no-merges", revision_range])
    return [line for line in output.splitlines() if line]


def get_commit_message(commit: str) -> str:
    return run_git(["show", "-s", "--format=%B", commit])


def is_wrapping_exempt(line: str, in_code_block: bool) -> bool:
    stripped = line.strip()

    if in_code_block:
        return True
    if not stripped:
        return True
    if stripped.startswith(">"):
        return True
    if TRAILER_PATTERN.fullmatch(stripped):
        return True

    tokens = stripped.split()
    if any(URL_PATTERN.search(token) for token in tokens):
        return is_wrapped_after_removing_unwrappable_tokens(tokens)
    if any(len(token) > MAX_WRAPPED_LINE_LENGTH for token in tokens):
        return is_wrapped_after_removing_unwrappable_tokens(tokens)

    return False


def is_wrapped_after_removing_unwrappable_tokens(tokens: list[str]) -> bool:
    wrappable_tokens = [
        token
        for token in tokens
        if not URL_PATTERN.search(token) and len(token) <= MAX_WRAPPED_LINE_LENGTH
    ]
    return len(" ".join(wrappable_tokens)) <= MAX_WRAPPED_LINE_LENGTH


def get_description_violations(commit: str, message: str) -> list[CommitDescriptionViolation]:
    lines = message.splitlines()
    if not lines:
        return []

    subject = lines[0]
    in_code_block = False
    violations = []

    for line_number, line in enumerate(lines[1:], start=2):
        stripped = line.strip()
        starts_code_fence = stripped.startswith("```") or stripped.startswith("~~~")

        if (
            len(line) > MAX_WRAPPED_LINE_LENGTH
            and not starts_code_fence
            and not is_wrapping_exempt(line, in_code_block)
        ):
            violations.append(
                CommitDescriptionViolation(
                    commit=commit,
                    subject=subject,
                    line_number=line_number,
                    length=len(line),
                    line=line,
                )
            )

        if starts_code_fence:
            in_code_block = not in_code_block

    return violations


def check_commit_descriptions(revision_range: str) -> list[CommitDescriptionViolation]:
    violations = []
    for commit in get_commits(revision_range):
        violations.extend(get_description_violations(commit, get_commit_message(commit)))
    return violations


def check_commit_message_file(message_file: Path) -> list[CommitDescriptionViolation]:
    try:
        message = clean_commit_message_file(message_file.read_text())
    except OSError as exception:
        print(f"Unable to read commit message file {message_file}: {exception}", file=sys.stderr)
        raise SystemExit(1) from exception

    return get_description_violations("commit message", message)


def clean_commit_message_file(message: str) -> str:
    return strip_commit_comments(truncate_commit_scissors(message))


def truncate_commit_scissors(message: str) -> str:
    lines = message.splitlines(keepends=True)
    for line_number, line in enumerate(lines):
        if is_commit_scissors_line(line.rstrip("\r\n")):
            return "".join(lines[:line_number])

    return message


def is_commit_scissors_line(line: str) -> bool:
    return (
        len(line) == len(SCISSORS_LINE_SUFFIX) + 1
        and not line[0].isspace()
        and line.endswith(SCISSORS_LINE_SUFFIX)
    )


def strip_commit_comments(message: str) -> str:
    return run_git(["stripspace", "--strip-comments"], input_text=message)


def print_violations(violations: list[CommitDescriptionViolation]) -> None:
    print(
        "Commit bodies should wrap at 72 characters; "
        f"this check fails ordinary text over {MAX_WRAPPED_LINE_LENGTH} characters.",
        file=sys.stderr,
    )
    print(
        "Long URLs, trailers, quoted text, code blocks, and long unwrappable tokens are allowed.",
        file=sys.stderr,
    )
    print(file=sys.stderr)

    for violation in violations:
        print(
            f"{format_commit_reference(violation.commit)} {violation.subject}",
            file=sys.stderr,
        )
        print(
            f"  line {violation.line_number}: {violation.length} characters",
            file=sys.stderr,
        )
        print(f"  {violation.line}", file=sys.stderr)
        print(file=sys.stderr)


def format_commit_reference(commit: str) -> str:
    if re.fullmatch(r"[0-9a-fA-F]{40}", commit):
        return commit[:12]
    return commit


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Check that commit description lines wrap ordinary text at "
            f"{MAX_WRAPPED_LINE_LENGTH} characters."
        )
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "revision_range",
        nargs="?",
        help="Git revision range to check, such as origin/main..HEAD.",
    )
    group.add_argument(
        "--message-file",
        type=Path,
        help="Commit message file to check, as passed to a commit-msg hook.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.message_file:
        violations = check_commit_message_file(args.message_file)
        if violations:
            print_violations(violations)
            return 1

        print("Checked commit message description; ordinary text is wrapped.")
        return 0

    violations = check_commit_descriptions(args.revision_range)
    if violations:
        print_violations(violations)
        return 1

    commit_count = len(get_commits(args.revision_range))
    print(f"Checked {commit_count} commit descriptions; ordinary text is wrapped.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
