#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from textwrap import dedent


SCRIPT_PATH = Path(__file__).with_name("check-commit-descriptions.py")
SPEC = importlib.util.spec_from_file_location("check_commit_descriptions", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    error_message = f"Unable to load {SCRIPT_PATH}"
    raise RuntimeError(error_message)

check_commit_descriptions = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = check_commit_descriptions
SPEC.loader.exec_module(check_commit_descriptions)


class TestCheckCommitDescriptions(unittest.TestCase):
    def test_accepts_wrapped_description(self) -> None:
        self.assertEqual(
            get_violating_lines(
                """
Add useful check

This line is wrapped.
This line is also wrapped before it gets too wide.
"""
            ),
            [],
        )

    def test_accepts_regular_text_at_seventy_nine_characters(self) -> None:
        self.assertEqual(
            get_violating_lines(
                """
Add useful check

This line is exactly seventy-nine characters long and it should pass as written
"""
            ),
            [],
        )

    def test_rejects_regular_text_over_seventy_nine_characters(self) -> None:
        self.assertEqual(
            get_violating_lines(
                """
Add useful check

This line is exactly eighty characters long and it should fail as written here.!
"""
            ),
            [3],
        )

    def test_accepts_long_url_when_surrounding_text_is_wrapped(self) -> None:
        self.assertEqual(
            get_violating_lines(
                """
Add useful check

See https://example.com/path/that/is/long/enough/to/exceed/the/wrap/limit for context.
"""
            ),
            [],
        )

    def test_rejects_long_regular_text_around_url(self) -> None:
        self.assertEqual(
            get_violating_lines(
                """
Add useful check

This surrounding prose is still far too long and should not be hidden behind a long URL. https://example.com/long/url
"""
            ),
            [3],
        )

    def test_accepts_long_unwrappable_token_when_surrounding_text_is_wrapped(self) -> None:
        self.assertEqual(
            get_violating_lines(
                """
Add useful check

Use com.example.really.long.package.name.with.enough.parts.to.exceed.the.wrap.limit.without.spaces.
"""
            ),
            [],
        )

    def test_accepts_code_blocks(self) -> None:
        self.assertEqual(
            get_violating_lines(
                """
Add useful check

```
This line is ordinary prose in a code block but should not be checked by wrapping rules.
```
"""
            ),
            [],
        )

    def test_accepts_block_quotes(self) -> None:
        self.assertEqual(
            get_violating_lines(
                """
Add useful check

> This quoted body line can exceed seventy-nine characters because wrapping it would alter quoted text.
"""
            ),
            [],
        )

    def test_accepts_trailers(self) -> None:
        self.assertEqual(
            get_violating_lines(
                """
Add useful check

Signed-off-by: Example Person With A Very Long Name <example.person.with.a.long.name@example.com>
"""
            ),
            [],
        )

    def test_accepts_message_file(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w+", encoding="utf-8") as message_file:
            message_file.write(
                dedent(
                    """
                    Add useful check

                    This line is wrapped.
                    """
                ).strip()
            )
            message_file.flush()

            self.assertEqual(
                check_commit_descriptions.check_commit_message_file(Path(message_file.name)),
                [],
            )

    def test_rejects_message_file_with_unwrapped_description(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w+", encoding="utf-8") as message_file:
            message_file.write(
                dedent(
                    """
                    Add useful check

                    This line is exactly eighty characters long and it should fail as written here.!
                    """
                ).strip()
            )
            message_file.flush()

            violations = check_commit_descriptions.check_commit_message_file(Path(message_file.name))

        self.assertEqual([violation.line_number for violation in violations], [3])

    def test_ignores_message_file_comments(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w+", encoding="utf-8") as message_file:
            message_file.write(
                dedent(
                    """
                    Add useful check

                    This line is wrapped.

                    # This template comment is intentionally long enough to fail if comments are validated.
                    """
                ).strip()
            )
            message_file.flush()

            self.assertEqual(
                check_commit_descriptions.check_commit_message_file(Path(message_file.name)),
                [],
            )

    def test_ignores_verbose_diff_after_scissors_line(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w+", encoding="utf-8") as message_file:
            message_file.write(
                dedent(
                    """
                    Add useful check

                    This line is wrapped.

                    # ------------------------ >8 ------------------------
                    # Do not modify or remove the line above.
                    # Everything below it will be ignored.
                    diff --git a/example.txt b/example.txt
                    +This verbose diff line is intentionally long enough to fail if the diff is validated.
                    """
                ).strip()
            )
            message_file.flush()

            self.assertEqual(
                check_commit_descriptions.check_commit_message_file(Path(message_file.name)),
                [],
            )

    def test_checks_text_before_scissors_line(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w+", encoding="utf-8") as message_file:
            message_file.write(
                dedent(
                    """
                    Add useful check

                    This line is exactly eighty characters long and it should fail as written here.!

                    # ------------------------ >8 ------------------------
                    diff --git a/example.txt b/example.txt
                    """
                ).strip()
            )
            message_file.flush()

            violations = check_commit_descriptions.check_commit_message_file(Path(message_file.name))

        self.assertEqual([violation.line_number for violation in violations], [3])

    def test_formats_commit_message_reference_without_truncation(self) -> None:
        self.assertEqual(
            check_commit_descriptions.format_commit_reference("commit message"),
            "commit message",
        )

    def test_formats_commit_sha_with_truncation(self) -> None:
        self.assertEqual(
            check_commit_descriptions.format_commit_reference("0123456789abcdef0123456789abcdef01234567"),
            "0123456789ab",
        )


def get_violating_lines(message: str) -> list[int]:
    violations = check_commit_descriptions.get_description_violations(
        "abc123",
        dedent(message).strip(),
    )
    return [violation.line_number for violation in violations]


if __name__ == "__main__":
    unittest.main()
