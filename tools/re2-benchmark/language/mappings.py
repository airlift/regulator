"""Explicit translations for the frozen bulk corpus and the lifecycle workloads, never production regex rewriting."""

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
I13_PATTERN = rb"[a-q][^u-z]{80}[x\xE0-\xFF]"
RUST_EMPTY_MATCH_CASES = {
    "imported/sherlock/everything-greedy": rb".*",
    "imported/sherlock/everything-greedy-nl": rb"(?s).*",
}
RUST_SIZE_CASES = {
    "reported/i1095-word-repetition/unicode-compile": rb"\p{L}{256}",
    "reported/i1095-word-repetition/unicode-search": rb"\p{L}{256}",
}
# Rust, and Java's bulk Unicode cases, compile these in Unicode mode, where \d and \s also
# match non-ASCII digits and spaces. RE2's classes are ASCII, so the translation spells them out.
ASCII_CLASSES = {rb"\d": rb"[0-9]", rb"\s": rb"[\t\n\f\r ]"}
ASCII_CLASS_REASON = "Spell out RE2's ASCII \\d and \\s, which Unicode mode would widen to non-ASCII digits and spaces"
BIBLEREF_PATTERN = (rb"(?P<Book>(([1234]|I{1,4})[\t\f\pZ]*)?\pL+\.?)[\t\f\pZ]+(?P<Locations>((?P<Chapter>1?[0-9]?[0-9])"
                    rb"(-(?P<ChapterEnd>\d+)|,\s*(?P<ChapterNext>\\d+))*(:\s*(?P<Verse>\d+))?"
                    rb"(-(?P<VerseEnd>\d+)|,\s*(?P<VerseNext>\d+))*\s?)+)")
RUST_LIFECYCLE_CASES = {
    "everyday/trinoPhone": rb"\d{3}/\d{3}/\d{4}",
    "everyday/whitespace": rb"\s+",
}


def unicode_class(name):
    data = collection.load(Path(__file__).with_name("unicode-ranges.json"))
    if data["re2_commit"] != "972a15cedd008d846f1a39b2e88ce48d7f166cbd":
        raise ValueError("unexpected Unicode translation authority")
    # Braced scalar escapes are supported by every frontend and comparator.
    def rune(value):
        return "\\x{" + format(value, "x") + "}"
    return ("[" + "".join(rune(low) if low == high else rune(low) + "-" + rune(high)
                          for low, high in data["ranges"][name]) + "]").encode()


def ascii_classes(pattern):
    """Replace each \\d and \\s escape; an escaped backslash before a d or s stays literal."""
    return re.sub(rb"\\.", lambda escape: ASCII_CLASSES.get(escape.group(), escape.group()), pattern)


def translate_lifecycle(identity, language, pattern):
    """Return a lifecycle case's pattern for one language, with the reason when it differs from the source."""
    if language != "rust" or identity not in RUST_LIFECYCLE_CASES:
        return pattern, None
    if pattern.encode() != RUST_LIFECYCLE_CASES[identity]:
        raise ValueError("lifecycle source pattern changed")
    return ascii_classes(pattern.encode()).decode(), ASCII_CLASS_REASON


def translate(identity, language, pattern, archived=False):
    """Return equivalent bytes and an explanation, or an explicit incompatibility.

    Archived 1.0 manifests keep the translations they were measured with.
    """
    if identity == "reported/i13-subset-regex/huge-ascii-nosuffixlit" and language == "rust":
        if pattern != I13_PATTERN:
            raise ValueError("I13 source pattern changed")
        return pattern, ("Rust compiles this Unicode-off source in byte mode, where, as in RE2's Latin-1 mode, "
                         "the raw-byte escapes match single bytes")
    if identity == "reported/i13-subset-regex/huge-ascii-nosuffixlit" and language != "re2":
        return None, ("Source uses Latin-1 byte matching and non-ASCII raw-byte escapes on multibyte input. "
                      "A Unicode code-point escape would change the byte-oriented match contract.")
    if language == "rust" and identity in RUST_EMPTY_MATCH_CASES:
        if pattern != RUST_EMPTY_MATCH_CASES[identity]:
            raise ValueError("empty-match source pattern changed")
        return None, ("Rust iteration skips an empty match that ends where the previous match ended, "
                      "so it reports different matches than the source; no pattern rewrite preserves every span.")
    if language == "rust" and identity in RUST_SIZE_CASES:
        if pattern != RUST_SIZE_CASES[identity]:
            raise ValueError("size-limit source pattern changed")
        return None, ("The pinned crate rejects the pattern under its default 10 MiB compiled-size limit, "
                      "which RustRegexp does not reproduce, so there is no crate result to compare.")
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
        if pattern != I13_PATTERN:
            raise ValueError("I13 source pattern changed")
        return rb"[a-q][^u-z]{80}[x\x{E0}-\x{FF}]", (
            "Braced code-point escapes preserve the source Unicode range without Trino's unsupported raw-byte escapes")
    # Rust accepts the source's Python-style named captures and one-letter properties.
    if identity.startswith("wild/bibleref/") and language in {"java", "trino"}:
        if len(re.findall(rb"\(\?P<", pattern)) != 8:
            raise ValueError("bibleref capture layout changed")
        translated = pattern.replace(b"(?P<", b"(?<").replace(rb"\pL", rb"\p{L}").replace(rb"\pZ", rb"\p{Z}")
        reason = "Use Java/Trino named-capture syntax and braced Unicode properties; preserve capture order and names"
        # Java compiles this Unicode case with UNICODE_CHARACTER_CLASS; Joni's \d stays ASCII.
        if language == "java" and not archived:
            if pattern != BIBLEREF_PATTERN:
                raise ValueError("bibleref source pattern changed")
            return ascii_classes(translated), reason + ". " + ASCII_CLASS_REASON
        return translated, reason
    if identity.startswith("wild/bibleref/") and language == "rust":
        if pattern != BIBLEREF_PATTERN:
            raise ValueError("bibleref source pattern changed")
        return ascii_classes(pattern), ASCII_CLASS_REASON
    return pattern, "Unchanged source pattern and flags; independent operation and matched-byte verification required"
