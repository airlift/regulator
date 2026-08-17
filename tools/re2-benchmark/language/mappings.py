"""Explicit translations for the frozen bulk corpus, never production regex rewriting."""

import re
from pathlib import Path

import collection


BOUNDARY_CASES = {
    "curated/08-words/all-english": rb"\b[0-9A-Za-z_]+\b",
    "opt/backtrack/words-english": rb"\b(?:(\w{6})|(\w{5}))\b",
    "unicode/word/boundary-any-english": rb"\b\w+\b",
}
JAVA_LINE_CASES = {
    "imported/sherlock/everything-greedy": rb".*",
    "imported/sherlock/line-boundary-sherlock-holmes": rb"(?m)^Sherlock Holmes|Sherlock Holmes$",
    "opt/accelerate/whole-line": rb"(?m)^.*$",
}
UNICODE_CASES = {
    "unicode/codepoints/letters-one": (rb"\p{L}{100}", "L", b"{100}"),
    "unicode/codepoints/contiguous-greek": (rb"\p{Greek}+", "Greek", b"+"),
}
I13_CASES = {
    "reported/i13-subset-regex/huge-ascii-nosuffixlit",
    "reported/i13-subset-regex/huge-unicode-nosuffixlit",
}


def unicode_class(name):
    data = collection.load(Path(__file__).with_name("unicode-ranges.json"))
    if data["re2_commit"] != "972a15cedd008d846f1a39b2e88ce48d7f166cbd":
        raise ValueError("unexpected Unicode translation authority")
    # Braced scalar escapes are supported by all three frontends and comparators.
    def rune(value):
        return "\\x{" + format(value, "x") + "}"
    return ("[" + "".join(rune(low) if low == high else rune(low) + "-" + rune(high)
                          for low, high in data["ranges"][name]) + "]").encode()


def translate(identity, language, pattern):
    """Return equivalent bytes and an explanation, or an explicit incompatibility."""
    if identity == "reported/i13-subset-regex/huge-ascii-nosuffixlit" and language != "re2":
        return None, ("Source uses Latin-1 byte matching and non-ASCII raw-byte escapes on multibyte input. "
                      "A Unicode code-point escape would change the byte-oriented match contract.")
    if identity in BOUNDARY_CASES:
        if pattern != BOUNDARY_CASES[identity]:
            raise ValueError("word-boundary source pattern changed")
        if language == "trino":
            return None, ("Source requires ASCII word boundaries; Trino/Joni use Unicode word boundaries. "
                          "Trino has no ASCII-boundary flag or lookaround to express the same whole-match contract.")
    if identity in JAVA_LINE_CASES and language == "java":
        if pattern != JAVA_LINE_CASES[identity]:
            raise ValueError("line-rule source pattern changed")
        if identity == "opt/accelerate/whole-line":
            return rb"(?d)^.*$", (
                "Grep inputs are split into LF-free lines before matching. Use LF-only dot and whole-input anchors, "
                "which preserve RE2 matches on empty lines; Java multiline caret intentionally rejects end-of-input.")
        return b"(?d)" + pattern, "UNIX_LINES preserves the source RE2 LF-only dot and multiline anchor rules"
    if identity in UNICODE_CASES:
        expected, name, suffix = UNICODE_CASES[identity]
        if pattern != expected:
            raise ValueError("Unicode-property source pattern changed")
        return unicode_class(name) + suffix, (
            "Explicit pinned RE2 " + name + " ranges preserve source membership across different Unicode versions. "
            "Both engines receive the same expanded class; this is not a dynamic Unicode-property compilation measurement.")
    if identity in I13_CASES and language == "trino":
        if pattern != rb"[a-q][^u-z]{80}[x\xE0-\xFF]":
            raise ValueError("I13 source pattern changed")
        return rb"[a-q][^u-z]{80}[x\x{E0}-\x{FF}]", (
            "Braced code-point escapes preserve the source Unicode range without Trino's unsupported raw-byte escapes")
    if identity.startswith("wild/bibleref/") and language != "re2":
        if len(re.findall(rb"\(\?P<", pattern)) != 8:
            raise ValueError("bibleref capture layout changed")
        translated = pattern.replace(b"(?P<", b"(?<").replace(rb"\pL", rb"\p{L}").replace(rb"\pZ", rb"\p{Z}")
        return translated, "Use Java/Trino named-capture syntax and braced Unicode properties; preserve capture order and names"
    return pattern, "Unchanged source pattern and flags; independent operation and matched-byte verification required"
