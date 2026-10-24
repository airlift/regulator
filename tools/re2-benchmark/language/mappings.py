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
# Unicode-mode-off sources whose pattern can match non-UTF-8 bytes, such as a dot or a
# negated class. The crate's str API and RustRegexp both reject them. Each records its
# source pattern's SHA-256, as the inventory does, because several patterns are kilobytes long.
RUST_BYTE_CASES = {
    "curated/03-date/ascii": "97cd171850089efa20adec84678649a72ccf0d75170baaff15a5219042b0e46d",
    "curated/03-date/compile-ascii": "97cd171850089efa20adec84678649a72ccf0d75170baaff15a5219042b0e46d",
    "curated/05-lexer-veryl/compile-single": "67e843247dab802f1b298e8ecb7180581ee8ec71b9d13aaac57d5817f649adfe",
    "curated/05-lexer-veryl/single": "67e843247dab802f1b298e8ecb7180581ee8ec71b9d13aaac57d5817f649adfe",
    "curated/06-cloud-flare-redos/original": "bff98abfc5d264213ac21b89ef9de4bd64837721fd81de684522cdb489bf8a4f",
    "curated/06-cloud-flare-redos/simplified-long": "cf42fe5cc6401813225674f9f2b17ac6be29b2b4b367cf75b9a5b00cdbe1cb61",
    "curated/06-cloud-flare-redos/simplified-short": "cf42fe5cc6401813225674f9f2b17ac6be29b2b4b367cf75b9a5b00cdbe1cb61",
    "curated/07-unicode-character-data/compile": "89d864c44f80652cdddf68cb40f7cb01285dd0bc093ef50f736c425df9d3175c",
    "curated/07-unicode-character-data/parse-line": "89d864c44f80652cdddf68cb40f7cb01285dd0bc093ef50f736c425df9d3175c",
    "curated/09-aws-keys/compile-full": "280d3fd784adec2abe6d59663f2676aa97a0b239135d761522e2d7e008ffe24d",
    "curated/09-aws-keys/full": "280d3fd784adec2abe6d59663f2676aa97a0b239135d761522e2d7e008ffe24d",
    "curated/10-bounded-repeat/compile-context": "43a575741e98b19786d72d30957f413462dc33474be03f4c94a7bea812a2cd67",
    "curated/10-bounded-repeat/context": "43a575741e98b19786d72d30957f413462dc33474be03f4c94a7bea812a2cd67",
    "curated/11-unstructured-to-json/compile": "56b9e6cbe6f96643741df49e33a3401af72bf5e143b20d677736371ef332a899",
    "curated/11-unstructured-to-json/extract": "56b9e6cbe6f96643741df49e33a3401af72bf5e143b20d677736371ef332a899",
    "curated/14-quadratic/10x": "3825bf5efaecbb6cfe9a2867c9124c499ff599bea5c8a23aa804239cbbd76f51",
    "curated/14-quadratic/1x": "3825bf5efaecbb6cfe9a2867c9124c499ff599bea5c8a23aa804239cbbd76f51",
    "curated/14-quadratic/2x": "3825bf5efaecbb6cfe9a2867c9124c499ff599bea5c8a23aa804239cbbd76f51",
    "imported/leipzig/bounded-strings-ending-z": "7b8137fc10b635f9d53ab4ff25f0eace179cc59533dd8c44fa22cc4355176994",
    "imported/leipzig/certain-long-strings-ending-x": "8519366034582778e309e627184b05333e7503ba01ced3111d91c8989c6d6377",
    "imported/leipzig/quotes-bounded": "68764d7810d256b15dbb4ee7a6a7d7d282bce027da056b4c77a04ae9f9f05c78",
    "imported/leipzig/tom-river": "d9825c23a4bc430e6894023f704399ff125b753cc7f97713e477d4b7db6e15ba",
    "imported/leipzig/tom-sawyer-huckle-fin-prefix-long": "74b25b99518fed4b29c7f6ab625e08a81707d1b6feff4a5a9ac0bf9ad772def7",
    "imported/leipzig/tom-sawyer-huckle-fin-prefix-short": "8b2bf4222100ee92ce01579175a7cbf56628273be36bb1cc3347620e3c7b81e1",
    "imported/lh3lh3-reb/email": "6cc0e2ec3a0f3b344987f88881ff21d0d5b2e9cff30b09d27a2bde5ce099b76d",
    "imported/lh3lh3-reb/uri": "b64702455770fd570e7233b5810d725e62d291d33d1746c1cbc6d10e7c302e95",
    "imported/lh3lh3-reb/uri-or-email": "05c2ad9d4d1d7a6eb3d2a15ffdaa482c43b8dcc5874025ab5549ae1e33e2f633",
    "imported/mariomka/uri": "59aa370e8ffdb480c2f5bbfcff37061da270773771f9d1c9a7a91eb2ab5d04f7",
    "imported/rsc/anchored-literal-long-match": "619ad49a425f5de3149e062761e33452cec8117c397416eac430c4e00a2a7d34",
    "imported/rsc/anchored-literal-short-match": "619ad49a425f5de3149e062761e33452cec8117c397416eac430c4e00a2a7d34",
    "imported/rsc/not-literal": "c7202811ad9f558e37bb6efb7fc87b74e606ea4a8c3fa1609822eaef0df166d8",
    "imported/rsc/one-pass-long-prefix": "982d71057b15923a0a5ef4f54ad92537067f5e0040c5bdb8542b5526e482f40e",
    "imported/rsc/one-pass-long-prefix-not": "03f0a50739adf7febf1bda4034ab3248e9c15b03a28f5ad79a573e0441af2821",
    "imported/rsc/one-pass-short": "c9fc6eea1a0f666125fe0d20bc7939f6f4891dedcb5f1901bfd8bfe93e4555e9",
    "imported/rsc/one-pass-short-not": "2363320a2783b9466dc0930be4bf903873ac80940ef1c515a037c375e910808a",
    "imported/rsc/reallyhard0-1k": "5fde105fcb488fa5f5c3d8f56daf2729df31a07e36e4b6015307bc9318494a3c",
    "imported/rsc/reallyhard0-1mb": "5fde105fcb488fa5f5c3d8f56daf2729df31a07e36e4b6015307bc9318494a3c",
    "imported/rsc/reallyhard0-32": "5fde105fcb488fa5f5c3d8f56daf2729df31a07e36e4b6015307bc9318494a3c",
    "imported/rsc/reallyhard0-32k": "5fde105fcb488fa5f5c3d8f56daf2729df31a07e36e4b6015307bc9318494a3c",
    "imported/sherlock/holmes-cochar-watson": "955bce37c264a15caa8f8f0bd626baae2e266f35b165e10194f00e9465c6f99e",
    "imported/sherlock/holmes-coword-watson": "432131550649d3e7d87eef7b3d31c5b7d56782660cdff046510a2e0766fc1951",
    "imported/sherlock/quotes": "7e76857b9f5ad19a3346cc410c91142225d97cbc2556073dd27a4c78f9847b6d",
    "imported/sherlock/repeated-class-negation": "8519366034582778e309e627184b05333e7503ba01ced3111d91c8989c6d6377",
    "opt/accelerate/non-dna": "ed279e89531373824fe645f9eb69c93dba3a5d083211f70256d98cab2fe51490",
    "opt/accelerate/whole-line": "5b22f7373a0d958dc8e60e039ebdfbb1244ca8c46453d8935ce31bcd4d9d7847",
    "opt/onepass/fn-predicate": "e660528219fa315cb3dba0fea30eb78b48fe1e2386f3adb6f053f8c28ab99b3a",
    "opt/reverse-inner/no-quadratic-backward": "69017b22c25e5609e75cbdff4ba30829f9950c2d0ae4c5cf546e6a08e0fa6cca",
    "opt/reverse-inner/no-quadratic-forward": "c31d04bb33138f09a47f8b1c71b76345d56e520021f0b308057382f59d1028ec",
    "opt/reverse-suffix/no-quadratic": "80baafe7c0704be7a1642833e2fdcfa7ee488f09bce42860c25ca75088ae5fa6",
    "opt/reverse-suffix/unsound-leftmost-first": "46f91c758722704fdae9f67050d05950e10db6ac648bcf52d3bf097977ce88ed",
    "reported/i13-subset-regex/big-ascii": "917caf9189eb6f528a58bfcb65d99de377b254bb8dfa28a2b5b8557f4ebe3e23",
    "reported/i13-subset-regex/huge-ascii": "a4e23995f7f18b025ce238810b41a247163b08f5cf95cfedcd48cb0e4056be6a",
    "reported/i13-subset-regex/original-ascii": "8519366034582778e309e627184b05333e7503ba01ced3111d91c8989c6d6377",
    "slow/quadratic-haystack-1x": "3825bf5efaecbb6cfe9a2867c9124c499ff599bea5c8a23aa804239cbbd76f51",
    "slow/quadratic-haystack-2x": "3825bf5efaecbb6cfe9a2867c9124c499ff599bea5c8a23aa804239cbbd76f51",
    "unicode/compile/match-every-line-ascii": "e79d02a12dde0eefdae61bb0e07d33210a9243dd9ad1c03abe8259dd69b76b06",
    "wild/caddy/caddy": "cca583243bf31bd0cf39c63bced1d1680b202db78e918322c518e063b55f7fa9",
    "wild/dot-star-capture/rust-src-tools": "e785f4fac80c58c395df023183d6566522df5e9fd2369fd4a65f160c449f010c",
    "wild/url/compile": "babef6a9a9147c929f20bd1d52b0ff1754aa06e8e05a972370f48ecd83c16ec2",
    "wild/url/search": "babef6a9a9147c929f20bd1d52b0ff1754aa06e8e05a972370f48ecd83c16ec2",
}
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


def translate(identity, language, pattern, archived=False, unicode=False):
    """Return equivalent bytes and an explanation, or an explicit incompatibility.

    Archived 1.0 manifests keep the translations they were measured with. The
    source's Unicode flag guards the byte-mode exclusions.
    """
    if identity == "reported/i13-subset-regex/huge-ascii-nosuffixlit" and language != "re2":
        return None, ("Source uses Latin-1 byte matching and non-ASCII raw-byte escapes on multibyte input. "
                      "A Unicode code-point escape would change the byte-oriented match contract.")
    if language == "rust" and identity in RUST_BYTE_CASES:
        if collection.digest(pattern) != RUST_BYTE_CASES[identity] or unicode:
            raise ValueError("byte-mode source pattern or Unicode flag changed")
        return None, ("Source turns Unicode mode off and its pattern can match bytes that are not UTF-8. "
                      "Rust's str API rejects such a pattern, as RustRegexp does outside byte mode; only the crate's bytes API accepts it.")
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
        if pattern != rb"[a-q][^u-z]{80}[x\xE0-\xFF]":
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
