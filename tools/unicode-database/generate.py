#!/usr/bin/env python3
"""Generate or check the Regulator Unicode database from pinned UCD files."""

# Downloads the Unicode Character Database (UCD) files that manifest.txt lists
# into target/unicode-database/<version>/ and writes
# src/main/resources/io/airlift/regulator/unicode/unicode-<version>.bin,
# src/main/resources/io/airlift/regulator/unicode/unicode-names-<version>.bin and
# tools/unicode-database/generated.txt. Run it with:
#
#   python3 -I tools/unicode-database/generate.py
#
# With --check, the generator writes nothing in the source tree. It downloads
# the files again, generates in memory, and fails unless the committed database
# and generated.txt are byte-identical to the new ones:
#
#   python3 -I tools/unicode-database/generate.py --check
#
# Every input is only parsed, never evaluated, and a file whose SHA-256 differs
# from the manifest is refused before it is parsed or kept. Output depends only
# on the manifest, the files it pins, and this script, so rerunning the
# generator is a no-op.
#
# generated.txt records the generation key and the SHA-256 of each resource,
# which TestUnicodeDatabase checks on every build without a network.
# The generation key is the SHA-256 of the two lines
# "<sha256 hex>  generate.py\n" and "<sha256 hex>  manifest.txt\n", the output
# of "shasum -a 256 generate.py manifest.txt" in this directory, so it changes
# whenever the generator or its inputs do.
#
# Database format, version 1. A varint is unsigned LEB128; a signed varint is a
# varint of the zigzag encoding (0, -1, 1, -2, ... as 0, 1, 2, 3, ...).
#
#   magic              4 bytes, "RGUD"
#   format version     varint, 1
#   Unicode version    varint byte length, then ASCII, such as "18.0.0"
#   generation key     32 bytes
#   input digest       32 bytes, the SHA-256 of the input listing: one line
#                      "<sha256 hex>  <path>\n" per UCD file, where the path is
#                      relative to the ucd/ directory, such as
#                      emoji/emoji-data.txt, sorted by path
#   sections           until the end of the file, each a varint section id, a
#                      varint payload byte length, and the payload, in
#                      ascending order of id; a reader skips a section id it
#                      does not know
#
# Both resources have the same header. The names resource holds section 13;
# the main resource holds all other sections.
#
# Section 1, simple case folding orbits: the case folding classes of
# CaseFolding.txt statuses C and S, in ascending order of the code point every
# member folds to (the orbit's target). The payload is a varint orbit count,
# then per orbit the varint distance from the previous orbit's target (from 0
# for the first), a varint count of the other members, and each other member in
# ascending order as a signed varint distance from the previous value, starting
# from the target.
#
# Section 2, full case folding: the CaseFolding.txt status F mappings, each to
# two or more code points, in ascending order of the code point mapped. The
# payload is a varint mapping count, then per mapping the varint distance from
# the previous mapped code point (from 0 for the first), a varint count of the
# code points it maps to, and each of them as a signed varint distance from the
# previous value, starting from the mapped code point.
#
# Sections 3 and 4, simple uppercase and lowercase mappings: UnicodeData.txt
# fields 12 and 13, in ascending order of the code point mapped. The payload is
# a varint mapping count, then per mapping the varint distance from the
# previous mapped code point (from 0 for the first) and the signed varint
# distance from the mapped code point to its mapping.
#
# A property value set holds each value of a property and the code points that
# have it. Its payload is a varint value count, then per value, in ascending
# order of name, the varint byte length of the name and the name in printable
# ASCII, a varint range count, and per range, in ascending order, the varint
# distance from the code point after the previous range (from 0 for the first)
# to the range's first code point, and the varint count of its other code
# points. Ranges of one value neither overlap nor touch.
#
# Section 5, General_Category, a property value set: the UnicodeData.txt field 2
# values, such as Lu, including the code points of First and Last ranges.
# Unassigned code points, Cn, are absent, as are the one-letter major classes.
#
# Section 6, Script, a property value set: the Scripts.txt values, which are
# long names such as Old_Italic. Code points of the default value, Unknown, are
# absent.
#
# Section 7, Script short names: the PropertyValueAliases.txt sc rows, each a
# short name, such as Ital, and its long name, such as Old_Italic. The payload
# is a varint row count, then per row, in ascending order of short name, the
# short name and the long name, each as a varint byte length and ASCII.
#
# Section 8, Block, a property value set: the Blocks.txt values, which are
# names such as Latin-1 Supplement. Code points of the default value, No_Block,
# are absent.
#
# Section 9, binary properties, a property value set whose values are
# properties and their code points: Bidi_Mirrored, UnicodeData.txt field 9;
# every property of PropList.txt and of emoji/emoji-data.txt; and the binary
# properties of DerivedCoreProperties.txt, which is every property it lists but
# the enumerated Indic_Conjunct_Break. Two properties can share code points, as
# two Script_Extensions values can, unlike the values of the other sets.
#
# Section 10, Age, a property value set: the DerivedAge.txt values, which are
# versions such as 1.1. Code points of the default value, Unassigned, are
# absent.
#
# Sections 11 and 12, full uppercase and lowercase mappings: the unconditional
# SpecialCasing.txt uppercase and lowercase mappings to two or more code
# points, in the format of section 2. Every other code point fully maps as it
# simply maps.
#
# A character name set holds code points and a name of each. Its payload is a
# dictionary of the words in the names, then the names. The dictionary is a
# varint word count, a varint count of the leading frequent words, and per word
# the varint length of the prefix it shares with the previous word (0 for the
# first), the varint length of the rest, and the rest in ASCII. The frequent
# words are the words the names use most, at most 127, in descending order of
# use, and the other words follow in ascending order. Then come a varint name
# count and per name, in ascending order of code point, the varint distance
# from the previous code point (from 0 for the first), a varint of the count of
# leading tokens it shares with the previous name, shifted left four bits, plus
# the count of its other tokens, and each other token as a varint. Token 0 is a
# hyphen and the code point in at least four uppercase hexadecimal digits,
# appended to the previous token, and token n is dictionary word n - 1, after a
# space unless it starts the name. A name has at most 15 tokens.
#
# Section 13, Name, a character name set: the UnicodeData.txt field 1 names,
# such as LATIN SMALL LETTER A. A row whose field 1 is a label in angle
# brackets, such as <control> or a First or Last row, gives no name.
#
# Section 14, control names, a character name set: the Unicode_1_Name,
# UnicodeData.txt field 10, of each row labeled <control> that has one, such
# as LINE FEED (LF).
#
# Section 15, Script_Extensions, a property value set whose values are script
# short names, such as Arab: the code points whose ScriptExtensions.txt list
# names each script. A code point the file does not list has the extension of
# its Script value alone. Two scripts can share code points.
#
# Sections 16, 17, and 18, Grapheme_Cluster_Break, Word_Break, and
# Sentence_Break, property value sets: the values of
# auxiliary/GraphemeBreakProperty.txt, auxiliary/WordBreakProperty.txt, and
# auxiliary/SentenceBreakProperty.txt, which are long names such as
# Regional_Indicator. Code points of the default value, Other, are absent.
#
# An alias list holds the names of each property or each value of one: a varint
# row count, then per row, in ascending order of long name, the short name, the
# long name, a varint count of the other aliases, and each other alias, every
# name as a varint byte length and ASCII letters, digits, underscores, and
# periods.
#
# Section 19, property aliases, an alias list: the PropertyAliases.txt rows,
# such as WSpace, White_Space, and space.
#
# Section 20, property value aliases: the PropertyValueAliases.txt rows of
# General_Category, Script, Age, and the three break properties. The payload is
# a varint property count, then per property, in ascending order of its short
# name, such as gc, the short name as a varint byte length and ASCII, and the
# alias list of its values, such as Nd, Decimal_Number, and digit.
#
# The generator refuses data that breaks what the readers assume: a code point
# with two mappings of one kind, a simple fold whose target folds again, a full
# fold whose result folds again, a status S mapping without a status F one,
# simple case folding orbits that differ from the classes of code points with
# equal full folding, a lowercase-of-uppercase mapping that is not idempotent,
# a code point with two values of one single-valued property, such as two
# scripts, a First row without its Last row, a file of binary properties that
# adds a property the generator does not expect or omits one it does, a binary
# property that two files define, an unconditional special casing to one code
# point that differs from the simple mapping, two code points with one name,
# two controls with one control name, a name with characters other than
# uppercase letters, digits, hyphens, and parentheses, with spaces other than
# single ones between words, or with more than 15 tokens, a script extension
# that is not a script short name, a Script_Extensions list that names a script
# twice, a property of VALUE_ALIAS_PROPERTIES with no value aliases, and two
# alias rows of one list with one long name or with names that match loosely by
# UAX #44 rule UAX44-LM3.

import argparse
import hashlib
import pathlib
import re
import urllib.request

TOOL_DIR = pathlib.Path(__file__).resolve().parent
ROOT_DIR = TOOL_DIR.parent.parent
MANIFEST = TOOL_DIR / "manifest.txt"
GENERATED = TOOL_DIR / "generated.txt"
DOWNLOAD_ROOT = ROOT_DIR / "target/unicode-database"
OUTPUT_DIR = ROOT_DIR / "src/main/resources/io/airlift/regulator/unicode"

URL_PATTERN = re.compile(r"https://www\.unicode\.org/Public/(\d+\.\d+\.\d+)/ucd/((?:auxiliary/|emoji/)?[A-Za-z-]+\.txt)")
INPUT_FILES = (
    "Blocks.txt",
    "CaseFolding.txt",
    "DerivedAge.txt",
    "DerivedCoreProperties.txt",
    "PropList.txt",
    "PropertyAliases.txt",
    "PropertyValueAliases.txt",
    "ScriptExtensions.txt",
    "Scripts.txt",
    "SpecialCasing.txt",
    "UnicodeData.txt",
    "auxiliary/GraphemeBreakProperty.txt",
    "auxiliary/SentenceBreakProperty.txt",
    "auxiliary/WordBreakProperty.txt",
    "emoji/emoji-data.txt",
)
# Files whose first line names the file and its version, such as "# Scripts-18.0.0.txt".
VERSIONED_FILES = (
    "Blocks.txt",
    "CaseFolding.txt",
    "DerivedAge.txt",
    "DerivedCoreProperties.txt",
    "PropList.txt",
    "PropertyAliases.txt",
    "PropertyValueAliases.txt",
    "ScriptExtensions.txt",
    "Scripts.txt",
    "SpecialCasing.txt",
    "auxiliary/GraphemeBreakProperty.txt",
    "auxiliary/SentenceBreakProperty.txt",
    "auxiliary/WordBreakProperty.txt",
)
# Files whose first line names the file, such as "# emoji-data.txt", and a later
# header line its version, such as "# Version: 18.0.0".
EMOJI_FILES = ("emoji/emoji-data.txt",)
PROP_LIST_PROPERTIES = (
    "ASCII_Hex_Digit",
    "Bidi_Control",
    "Dash",
    "Deprecated",
    "Diacritic",
    "Extender",
    "Hex_Digit",
    "Hyphen",
    "IDS_Binary_Operator",
    "IDS_Trinary_Operator",
    "IDS_Unary_Operator",
    "ID_Compat_Math_Continue",
    "ID_Compat_Math_Start",
    "Ideographic",
    "Join_Control",
    "Logical_Order_Exception",
    "Modifier_Combining_Mark",
    "Noncharacter_Code_Point",
    "Other_Alphabetic",
    "Other_Default_Ignorable_Code_Point",
    "Other_Grapheme_Extend",
    "Other_ID_Continue",
    "Other_ID_Start",
    "Other_Lowercase",
    "Other_Math",
    "Other_Uppercase",
    "Pattern_Syntax",
    "Pattern_White_Space",
    "Prepended_Concatenation_Mark",
    "Quotation_Mark",
    "Radical",
    "Regional_Indicator",
    "Sentence_Terminal",
    "Soft_Dotted",
    "Terminal_Punctuation",
    "Unified_Ideograph",
    "Variation_Selector",
    "White_Space",
)
DERIVED_CORE_PROPERTIES = (
    "Alphabetic",
    "Case_Ignorable",
    "Cased",
    "Changes_When_Casefolded",
    "Changes_When_Casemapped",
    "Changes_When_Lowercased",
    "Changes_When_Titlecased",
    "Changes_When_Uppercased",
    "Default_Ignorable_Code_Point",
    "Grapheme_Base",
    "Grapheme_Extend",
    "Grapheme_Link",
    "ID_Continue",
    "ID_Start",
    "Lowercase",
    "Math",
    "Uppercase",
    "XID_Continue",
    "XID_Start",
)
# DerivedCoreProperties.txt also lists an enumerated property, in rows
# "<code>[..<code>] ; <property> ; <value>".
DERIVED_CORE_ENUMERATED_PROPERTIES = ("InCB",)
EMOJI_PROPERTIES = (
    "Emoji",
    "Emoji_Component",
    "Emoji_Modifier",
    "Emoji_Modifier_Base",
    "Emoji_Presentation",
    "Extended_Pictographic",
)
# The properties whose value aliases section 20 holds, by short name.
VALUE_ALIAS_PROPERTIES = ("GCB", "SB", "WB", "age", "gc", "sc")
NAME = r"[A-Za-z0-9_]+"
# An alias is a name or a version, such as the Age value 1.1.
ALIAS = r"[A-Za-z0-9_.]+"
BLOCK_NAME = r"[A-Za-z0-9]+(?:[ -][A-Za-z0-9]+)*"
AGE = r"[0-9]+\.[0-9]+"
# A character name is words of letters, digits, hyphens, and parentheses, separated by single
# spaces, such as "LINE FEED (LF)".
CHARACTER_NAME = r"[A-Z0-9()-]+(?: [A-Z0-9()-]+)*"
# Each frequent word of a character name set, at most this many, gets a one-byte varint token;
# token 0 is reserved for the code point suffix.
FREQUENT_WORDS = 127
# The kept and new token counts of a character name share one varint, four bits each.
MAX_NAME_TOKENS = 15

MAGIC = b"RGUD"
FORMAT_VERSION = 1
SECTION_SIMPLE_CASE_FOLDING_ORBITS = 1
SECTION_FULL_CASE_FOLDING = 2
SECTION_SIMPLE_UPPERCASE_MAPPINGS = 3
SECTION_SIMPLE_LOWERCASE_MAPPINGS = 4
SECTION_GENERAL_CATEGORY = 5
SECTION_SCRIPT = 6
SECTION_SCRIPT_SHORT_NAMES = 7
SECTION_BLOCK = 8
SECTION_BINARY_PROPERTIES = 9
SECTION_AGE = 10
SECTION_FULL_UPPERCASE_MAPPINGS = 11
SECTION_FULL_LOWERCASE_MAPPINGS = 12
SECTION_NAME = 13
SECTION_CONTROL_NAME = 14
SECTION_SCRIPT_EXTENSIONS = 15
SECTION_GRAPHEME_CLUSTER_BREAK = 16
SECTION_WORD_BREAK = 17
SECTION_SENTENCE_BREAK = 18
SECTION_PROPERTY_ALIASES = 19
SECTION_PROPERTY_VALUE_ALIASES = 20
MAX_CODE_POINT = 0x10FFFF


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_manifest():
    # Rows are "<sha256 hex>  <url>"; "#" starts a comment line.
    version = None
    files = {}
    for number, line in enumerate(MANIFEST.read_text(encoding="ascii").split("\n"), 1):
        if not line or line.startswith("#"):
            continue
        fields = line.split("  ")
        match = URL_PATTERN.fullmatch(fields[1]) if len(fields) == 2 else None
        if match is None or re.fullmatch(r"[0-9a-f]{64}", fields[0]) is None:
            raise ValueError(f"manifest.txt:{number}: expected '<sha256>  <unicode.org UCD URL>', not {line!r}")
        if version is not None and match.group(1) != version:
            raise ValueError(f"manifest.txt:{number}: Unicode {match.group(1)} differs from Unicode {version}")
        version = match.group(1)
        name = match.group(2)
        if name in files:
            raise ValueError(f"manifest.txt:{number}: {name} is listed twice")
        files[name] = (fields[1], fields[0])
    if sorted(files) != sorted(INPUT_FILES):
        raise ValueError(f"manifest.txt must list exactly {', '.join(INPUT_FILES)}")
    return version, files


def generation_key():
    listing = "".join(f"{sha256((TOOL_DIR / name).read_bytes())}  {name}\n" for name in sorted(("generate.py", "manifest.txt")))
    return hashlib.sha256(listing.encode("ascii")).digest()


def input_digest(files):
    listing = "".join(f"{checksum}  {name}\n" for name, (_, checksum) in sorted(files.items()))
    return hashlib.sha256(listing.encode("ascii")).digest()


def download(version, files, refresh):
    # A file already downloaded is reused only if it still has the pinned checksum.
    directory = DOWNLOAD_ROOT / version
    directory.mkdir(parents=True, exist_ok=True)
    texts = {}
    for name, (url, expected) in sorted(files.items()):
        file = directory / name
        file.parent.mkdir(parents=True, exist_ok=True)
        data = None if refresh or not file.is_file() else file.read_bytes()
        if data is None or sha256(data) != expected:
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            checksum = sha256(data)
            if checksum != expected:
                raise ValueError(f"{url} has sha256 {checksum}, not the {expected} pinned in manifest.txt")
            file.write_bytes(data)
        text = data.decode("utf-8")
        if name in VERSIONED_FILES:
            check_header(url, name, text, version)
        if name in EMOJI_FILES:
            check_emoji_header(url, name, text, version)
        texts[name] = text
    return texts


def check_header(url, name, text, version):
    expected = f"# {pathlib.PurePosixPath(name).stem}-{version}.txt"
    if text.split("\n", 1)[0].rstrip("\r") != expected:
        raise ValueError(f"{url} does not start with {expected!r}")


def check_emoji_header(url, name, text, version):
    lines = text.split("\n")
    header = []
    for line in lines:
        if not line.startswith("#"):
            break
        header.append(line.rstrip("\r"))
    # Some releases give the version without its update number, such as "17.0".
    versions = {f"# Version: {version}", f"# Version: {version.removesuffix('.0')}"}
    expected = f"# {pathlib.PurePosixPath(name).name}"
    if not header or header[0] != expected or versions.isdisjoint(header):
        raise ValueError(f"{url} does not start with {expected!r} and a '# Version: {version}' line")


def code_point(field, name, number):
    value = int(field, 16)
    if value > MAX_CODE_POINT or 0xD800 <= value <= 0xDFFF:
        raise ValueError(f"{name}:{number}: {field} is not a Unicode scalar value")
    return value


def case_folding(text):
    # CaseFolding.txt rows are "<code>; <status>; <mapping>; # <name>". Statuses C
    # and S are the simple case folding, C and F the full case folding, and T the
    # Turkic special cases, which no reader uses.
    simple = {}
    full = {}
    statuses = {}
    for number, line in enumerate(text.split("\n"), 1):
        row = line.split("#", 1)[0].strip()
        if not row:
            continue
        fields = [field.strip() for field in row.split(";")]
        if len(fields) != 4 or fields[3]:
            raise ValueError(f"CaseFolding.txt:{number}: unexpected row {line!r}")
        status = fields[1]
        if status not in ("C", "S", "F", "T"):
            raise ValueError(f"CaseFolding.txt:{number}: unknown status {status!r}")
        source = code_point(fields[0], "CaseFolding.txt", number)
        if status in statuses.setdefault(source, set()):
            raise ValueError(f"CaseFolding.txt:{number}: U+{source:04X} has two status {status} mappings")
        statuses[source].add(status)
        if status == "T":
            continue
        mapping = [code_point(value, "CaseFolding.txt", number) for value in fields[2].split()]
        if status == "F":
            if len(mapping) < 2:
                raise ValueError(f"CaseFolding.txt:{number}: status F maps to {len(mapping)} code points")
            full[source] = tuple(mapping)
            continue
        if len(mapping) != 1:
            raise ValueError(f"CaseFolding.txt:{number}: status {status} maps to {len(mapping)} code points")
        if source in simple:
            raise ValueError(f"CaseFolding.txt:{number}: U+{source:04X} has two simple folds")
        simple[source] = mapping[0]

    for source, members in statuses.items():
        if "S" in members and "F" not in members:
            raise ValueError(f"U+{source:04X} has a status S mapping without a status F one")
        if "C" in members and "F" in members:
            raise ValueError(f"U+{source:04X} has status C and F mappings")

    orbits = {}
    for source, target in simple.items():
        if source == target:
            raise ValueError(f"U+{source:04X} folds to itself")
        if target in simple:
            raise ValueError(f"U+{source:04X} folds to U+{target:04X}, which folds again")
        orbits.setdefault(target, [target]).append(source)
    for source, mapping in full.items():
        for value in mapping:
            if value in simple or value in full:
                raise ValueError(f"U+{source:04X} fully folds to U+{value:04X}, which folds again")

    # Full case folding of one code point is its status F mapping, or else its
    # status C one. Trino folds single code points by the simple orbits, so each
    # orbit must hold exactly the code points with one full folding.
    def full_fold(value):
        return full.get(value, (simple.get(value, value),))

    orbit_by_full_fold = {}
    for target, members in orbits.items():
        folds = {full_fold(member) for member in members}
        if len(folds) != 1:
            raise ValueError(f"the simple orbit of U+{target:04X} holds code points with different full folds")
        fold = folds.pop()
        if orbit_by_full_fold.setdefault(fold, target) != target:
            raise ValueError(f"the simple orbits of U+{target:04X} and U+{orbit_by_full_fold[fold]:04X} have one full fold")
    for source, mapping in full.items():
        if orbit_by_full_fold.setdefault(mapping, source) != simple.get(source, source):
            raise ValueError(f"U+{source:04X} shares its full fold with a code point outside its simple orbit")

    simple_orbits = [(target, sorted(members[1:])) for target, members in sorted(orbits.items())]
    return simple_orbits, sorted(full.items())


def unicode_data(text):
    # UnicodeData.txt rows have 15 fields; field 1 is the name, field 2 the
    # general category, field 9 Bidi_Mirrored, Y or N, field 10 the
    # Unicode_1_Name, field 12 the simple uppercase mapping, and field 13 the
    # simple lowercase mapping. A range of code points, given by a First row and
    # a Last row of the same name, category, and Bidi_Mirrored, has no mappings
    # and no name of its own. The name of any other row in angle brackets, such
    # as <control>, is a label, not a Name.
    uppercase = {}
    lowercase = {}
    categories = {}
    mirrored = []
    names = []
    control_names = []
    previous = -1
    first = None
    for number, line in enumerate(text.split("\n"), 1):
        if not line:
            continue
        fields = line.split(";")
        if len(fields) != 15:
            raise ValueError(f"UnicodeData.txt:{number}: unexpected row {line!r}")
        # The surrogate ranges have rows of their own.
        source = int(fields[0], 16)
        if source > MAX_CODE_POINT:
            raise ValueError(f"UnicodeData.txt:{number}: {fields[0]} is not a code point")
        if source <= previous:
            raise ValueError(f"UnicodeData.txt:{number}: U+{source:04X} is out of order")
        previous = source
        category = fields[2]
        if re.fullmatch(r"[A-Z][a-z]", category) is None or category == "Cn":
            raise ValueError(f"UnicodeData.txt:{number}: unexpected general category {category!r}")
        if fields[9] not in ("Y", "N"):
            raise ValueError(f"UnicodeData.txt:{number}: unexpected Bidi_Mirrored {fields[9]!r}")
        range_name = re.fullmatch(r"<(.+), (First|Last)>", fields[1])
        if first is not None:
            if range_name is None or range_name.group(2) != "Last" or (range_name.group(1), category, fields[9]) != first[1:]:
                raise ValueError(f"UnicodeData.txt:{number}: expected the Last row of {first[1]}")
            low = first[0]
            first = None
        elif range_name is not None and range_name.group(2) == "First":
            first = (source, range_name.group(1), category, fields[9])
        elif range_name is not None:
            raise ValueError(f"UnicodeData.txt:{number}: a Last row without its First row")
        else:
            low = source
        if first is None:
            add_range(categories, category, low, source)
            if fields[9] == "Y":
                mirrored.append((low, source))
        if fields[1] == "<control>":
            if fields[10]:
                control_names.append((source, character_name(fields[10], number)))
        elif not fields[1].startswith("<"):
            names.append((source, character_name(fields[1], number)))
        for mappings, field in ((uppercase, fields[12]), (lowercase, fields[13])):
            if not field:
                continue
            if fields[1].endswith(", First>") or fields[1].endswith(", Last>"):
                raise ValueError(f"UnicodeData.txt:{number}: a code point range has a case mapping")
            target = code_point(field, "UnicodeData.txt", number)
            if target == source:
                raise ValueError(f"UnicodeData.txt:{number}: U+{source:04X} maps to itself")
            mappings[source] = target
    if first is not None:
        raise ValueError(f"UnicodeData.txt: expected the Last row of {first[1]}")

    # Java's case classes fold a code point to the lowercase of its uppercase,
    # which must fold to itself again.
    def java_fold(value):
        upper = uppercase.get(value, value)
        return lowercase.get(upper, upper)

    for value in range(MAX_CODE_POINT + 1):
        fold = java_fold(value)
        if java_fold(fold) != fold:
            raise ValueError(f"U+{value:04X} folds to U+{fold:04X} by lowercase of uppercase, which folds again")
    unique_names = {name for _, name in names}
    if len(unique_names) != len(names):
        raise ValueError("UnicodeData.txt gives two code points one name")
    # A control's Unicode_1_Name may also be another code point's name, such as
    # BELL, but no two controls share one.
    if len({name for _, name in control_names}) != len(control_names):
        raise ValueError("UnicodeData.txt gives two controls one Unicode_1_Name")
    return sorted(uppercase.items()), sorted(lowercase.items()), categories, mirrored, names, control_names


def character_name(name, number):
    if re.fullmatch(CHARACTER_NAME, name) is None:
        raise ValueError(f"UnicodeData.txt:{number}: unexpected character name {name!r}")
    return name


def special_casing(text, uppercase, lowercase):
    # SpecialCasing.txt rows are "<code>; <lower>; <title>; <upper>; # <name>",
    # each mapping one or more code points, or with a condition list before the
    # comment, such as Final_Sigma or tr. Only the unconditional lowercase and
    # uppercase mappings are read; a mapping to one code point must equal the
    # simple mapping of UnicodeData.txt, so only those to two or more are kept.
    full_uppercase = {}
    full_lowercase = {}
    unconditional = set()
    for number, line in enumerate(text.split("\n"), 1):
        row = line.split("#", 1)[0].strip()
        if not row:
            continue
        fields = [field.strip() for field in row.split(";")]
        if len(fields) not in (5, 6) or fields[-1]:
            raise ValueError(f"SpecialCasing.txt:{number}: unexpected row {line!r}")
        if len(fields) == 6:
            continue
        source = code_point(fields[0], "SpecialCasing.txt", number)
        if source in unconditional:
            raise ValueError(f"SpecialCasing.txt:{number}: U+{source:04X} has two unconditional rows")
        unconditional.add(source)
        for mappings, simple, field in ((full_uppercase, uppercase, fields[3]), (full_lowercase, lowercase, fields[1])):
            mapping = tuple(code_point(value, "SpecialCasing.txt", number) for value in field.split())
            if not mapping:
                raise ValueError(f"SpecialCasing.txt:{number}: an empty mapping")
            if len(mapping) == 1:
                if mapping[0] != simple.get(source, source):
                    raise ValueError(f"SpecialCasing.txt:{number}: U+{source:04X} maps to one code point other than its simple mapping")
                continue
            mappings[source] = mapping
    return sorted(full_uppercase.items()), sorted(full_lowercase.items())


def property_values(name, text, value_pattern=NAME, single_valued=True, enumerated=()):
    # Rows of a property file are "<code>[..<code>] ; <value>", followed by an
    # optional "# <comment>". In a file of one single-valued property, a code
    # point has at most one value; in a file of binary properties, each value
    # is a property that its code points have, and the rows of the enumerated
    # properties it also lists are skipped.
    values = {}
    for number, line in enumerate(text.split("\n"), 1):
        row = line.split("#", 1)[0].strip()
        if not row:
            continue
        fields = [field.strip() for field in row.split(";")]
        if len(fields) == 3 and fields[1] in enumerated:
            continue
        match = re.fullmatch(r"([0-9A-F]{4,6})(?:\.\.([0-9A-F]{4,6}))?", fields[0])
        if len(fields) != 2 or match is None or re.fullmatch(value_pattern, fields[1]) is None:
            raise ValueError(f"{name}:{number}: unexpected row {line!r}")
        low = int(match.group(1), 16)
        high = int(match.group(2), 16) if match.group(2) else low
        if high < low or high > MAX_CODE_POINT:
            raise ValueError(f"{name}:{number}: bad code point range {fields[0]}")
        add_range(values, fields[1], low, high)
    if single_valued:
        check_single_valued(name, values)
    return values


def binary_properties(name, text, properties, enumerated=()):
    # A file must list exactly the expected properties, so a property that a
    # new version adds is a reviewed change to the generator.
    values = property_values(name, text, single_valued=False, enumerated=enumerated)
    missing = sorted(set(properties) - set(values))
    unexpected = sorted(set(values) - set(properties))
    if missing or unexpected:
        raise ValueError(f"{name} omits the expected properties {missing} and lists the unexpected ones {unexpected}")
    return values


def add_binary_properties(binary, properties):
    for value, ranges in properties.items():
        if value in binary:
            raise ValueError(f"binary property {value} is listed by two files")
        binary[value] = ranges


def script_extensions(text, short_names):
    # ScriptExtensions.txt rows are "<code>[..<code>] ; <short names>", the
    # script short names separated by single spaces, followed by an optional
    # "# <comment>". Each value of the result is one script.
    extensions = {}
    for value, ranges in property_values("ScriptExtensions.txt", text, rf"{NAME}(?: {NAME})*").items():
        scripts = value.split(" ")
        if len(set(scripts)) != len(scripts):
            raise ValueError(f"ScriptExtensions.txt: {value} names a script twice")
        for script in scripts:
            if script not in short_names:
                raise ValueError(f"ScriptExtensions.txt: {script} is not a script short name")
            for low, high in ranges:
                add_range(extensions, script, low, high)
    return extensions


def property_aliases(text):
    # PropertyAliases.txt rows are "<short name> ; <long name>", followed by
    # optional further aliases.
    rows = []
    for number, line in enumerate(text.split("\n"), 1):
        row = line.split("#", 1)[0].strip()
        if not row:
            continue
        fields = [field.strip() for field in row.split(";")]
        if len(fields) < 2 or any(re.fullmatch(NAME, field) is None for field in fields):
            raise ValueError(f"PropertyAliases.txt:{number}: unexpected row {line!r}")
        rows.append(tuple(fields))
    return alias_list("PropertyAliases.txt", rows)


def property_value_aliases(text):
    # PropertyValueAliases.txt rows are
    # "<property> ; <short name> ; <long name>", followed by optional further
    # aliases and an optional "# <comment>". Only the rows of
    # VALUE_ALIAS_PROPERTIES are read.
    rows = {}
    for number, line in enumerate(text.split("\n"), 1):
        row = line.split("#", 1)[0].strip()
        if not row:
            continue
        fields = [field.strip() for field in row.split(";")]
        if fields[0] not in VALUE_ALIAS_PROPERTIES:
            continue
        if len(fields) < 3 or any(re.fullmatch(ALIAS, field) is None for field in fields[1:]):
            raise ValueError(f"PropertyValueAliases.txt:{number}: unexpected row {line!r}")
        rows.setdefault(fields[0], []).append(tuple(fields[1:]))
    missing = [prop for prop in VALUE_ALIAS_PROPERTIES if prop not in rows]
    if missing:
        raise ValueError(f"PropertyValueAliases.txt lists no value of {', '.join(missing)}")
    return {prop: alias_list(f"PropertyValueAliases.txt {prop}", prop_rows) for prop, prop_rows in rows.items()}


def alias_list(name, rows):
    # Rows are (short name, long name, other aliases...), returned in ascending
    # order of long name. UAX44-LM3 matches names ignoring case, spaces,
    # underscores, hyphens, and an initial "is", and no two rows may share a
    # long name or a name by that rule.
    by_long_name = {}
    loose_names = {}
    for row in rows:
        if row[1] in by_long_name:
            raise ValueError(f"{name}: {row[1]} is listed twice")
        by_long_name[row[1]] = row
        for alias in row:
            key = re.sub(r"[ _-]", "", alias).lower()
            key = key[2:] if key.startswith("is") else key
            if loose_names.setdefault(key, row[1]) != row[1]:
                raise ValueError(f"{name}: {alias} of {row[1]} matches a name of {loose_names[key]}")
    return [by_long_name[long_name] for long_name in sorted(by_long_name)]


def script_short_names(value_aliases):
    # The short and long name of each script, from the Script (sc) value
    # aliases, in ascending order of short name. Unlike an Age value, a script
    # name holds no period.
    for row in value_aliases["sc"]:
        if any(re.fullmatch(NAME, name) is None for name in row):
            raise ValueError(f"PropertyValueAliases.txt: unexpected script names {row!r}")
    return sorted((row[0], row[1]) for row in value_aliases["sc"])


def add_range(values, value, low, high):
    values.setdefault(value, []).append((low, high))


def coalesce(ranges):
    # Sorts ranges and merges the ones that overlap or touch.
    merged = []
    for low, high in sorted(ranges):
        if merged and low <= merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], max(merged[-1][1], high))
        else:
            merged.append((low, high))
    return merged


def check_single_valued(name, values):
    claimed = sorted((low, high, value) for value, ranges in values.items() for low, high in ranges)
    for (_, previous_high, previous_value), (low, _, value) in zip(claimed, claimed[1:]):
        if low <= previous_high:
            raise ValueError(f"{name}: U+{low:04X} is both {previous_value} and {value}")


def varint(value):
    if value < 0:
        raise ValueError(f"negative varint {value}")
    encoded = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            encoded.append(byte | 0x80)
        else:
            encoded.append(byte)
            return bytes(encoded)


def signed_varint(value):
    return varint(value * 2 if value >= 0 else -value * 2 - 1)


def section(section_id, payload):
    return varint(section_id) + varint(len(payload)) + payload


def orbit_section(orbits):
    payload = bytearray(varint(len(orbits)))
    previous_target = 0
    for target, members in orbits:
        payload += varint(target - previous_target)
        payload += varint(len(members))
        previous = target
        for member in members:
            payload += signed_varint(member - previous)
            previous = member
        previous_target = target
    return section(SECTION_SIMPLE_CASE_FOLDING_ORBITS, bytes(payload))


def multi_character_section(section_id, mappings):
    payload = bytearray(varint(len(mappings)))
    previous_source = 0
    for source, mapping in mappings:
        payload += varint(source - previous_source)
        payload += varint(len(mapping))
        previous = source
        for value in mapping:
            payload += signed_varint(value - previous)
            previous = value
        previous_source = source
    return section(section_id, bytes(payload))


def property_value_section(section_id, values):
    payload = bytearray(varint(len(values)))
    for name in sorted(values):
        ranges = coalesce(values[name])
        payload += ascii_name(name)
        payload += varint(len(ranges))
        next_code_point = 0
        for low, high in ranges:
            payload += varint(low - next_code_point)
            payload += varint(high - low)
            next_code_point = high + 1
    return section(section_id, bytes(payload))


def name_section(section_id, pairs):
    payload = bytearray(varint(len(pairs)))
    for key, value in pairs:
        payload += ascii_name(key) + ascii_name(value)
    return section(section_id, bytes(payload))


def alias_list_payload(rows):
    payload = bytearray(varint(len(rows)))
    for short_name, long_name, *aliases in rows:
        payload += ascii_name(short_name) + ascii_name(long_name) + varint(len(aliases))
        for alias in aliases:
            payload += ascii_name(alias)
    return bytes(payload)


def property_value_alias_section(section_id, properties):
    payload = bytearray(varint(len(properties)))
    for prop in sorted(properties):
        payload += ascii_name(prop) + alias_list_payload(properties[prop])
    return section(section_id, bytes(payload))


def ascii_name(name):
    encoded = name.encode("ascii")
    return varint(len(encoded)) + encoded


def mapping_section(section_id, mappings):
    payload = bytearray(varint(len(mappings)))
    previous_source = 0
    for source, target in mappings:
        payload += varint(source - previous_source)
        payload += signed_varint(target - source)
        previous_source = source
    return section(section_id, bytes(payload))


def name_tokens(code_point, name):
    # The words of a name, with the hyphen and code point that end a name such
    # as "CJK COMPATIBILITY IDEOGRAPH-F900" as a separate token, None.
    words = name.split(" ")
    suffix = f"-{code_point:04X}"
    if len(words[-1]) > len(suffix) and words[-1].endswith(suffix):
        words[-1] = words[-1][:-len(suffix)]
        words.append(None)
    if len(words) > MAX_NAME_TOKENS:
        raise ValueError(f"the name of U+{code_point:04X} has more than {MAX_NAME_TOKENS} tokens")
    return words


def character_name_section(section_id, names):
    tokens = [(code_point, name_tokens(code_point, name)) for code_point, name in names]
    counts = {}
    for _, words in tokens:
        for word in words:
            if word is not None:
                counts[word] = counts.get(word, 0) + 1
    by_frequency = sorted(counts, key=lambda word: (-counts[word], word))
    frequent = by_frequency[:FREQUENT_WORDS]
    words = frequent + sorted(by_frequency[FREQUENT_WORDS:])
    if len(words) >= 0xFFFF:
        raise ValueError(f"section {section_id} has more than {0xFFFF - 1} words")
    token_values = {word: index + 1 for index, word in enumerate(words)}
    token_values[None] = 0

    payload = bytearray(varint(len(words)) + varint(len(frequent)))
    previous_word = b""
    for word in words:
        encoded_word = word.encode("ascii")
        shared = 0
        while shared < min(len(word), len(previous_word)) and encoded_word[shared] == previous_word[shared]:
            shared += 1
        payload += varint(shared) + varint(len(encoded_word) - shared) + encoded_word[shared:]
        previous_word = encoded_word

    payload += varint(len(tokens))
    previous_code_point = 0
    previous_tokens = []
    for code_point, name_words in tokens:
        kept = 0
        while kept < min(len(name_words), len(previous_tokens)) and name_words[kept] == previous_tokens[kept]:
            kept += 1
        payload += varint(code_point - previous_code_point)
        payload += varint(kept << 4 | len(name_words) - kept)
        for word in name_words[kept:]:
            payload += varint(token_values[word])
        previous_code_point = code_point
        previous_tokens = name_words
    return section(section_id, bytes(payload))


def database(version, files, texts, key):
    simple_orbits, full_folds = case_folding(texts["CaseFolding.txt"])
    uppercase, lowercase, categories, mirrored, names, control_names = unicode_data(texts["UnicodeData.txt"])
    full_uppercase, full_lowercase = special_casing(texts["SpecialCasing.txt"], dict(uppercase), dict(lowercase))
    scripts = property_values("Scripts.txt", texts["Scripts.txt"])
    blocks = property_values("Blocks.txt", texts["Blocks.txt"], BLOCK_NAME)
    value_aliases = property_value_aliases(texts["PropertyValueAliases.txt"])
    short_names = script_short_names(value_aliases)
    binary = {"Bidi_Mirrored": mirrored}
    add_binary_properties(binary, binary_properties("emoji/emoji-data.txt", texts["emoji/emoji-data.txt"], EMOJI_PROPERTIES))
    add_binary_properties(binary, binary_properties("PropList.txt", texts["PropList.txt"], PROP_LIST_PROPERTIES))
    add_binary_properties(binary, binary_properties("DerivedCoreProperties.txt", texts["DerivedCoreProperties.txt"], DERIVED_CORE_PROPERTIES, DERIVED_CORE_ENUMERATED_PROPERTIES))
    ages = property_values("DerivedAge.txt", texts["DerivedAge.txt"], AGE)
    extensions = script_extensions(texts["ScriptExtensions.txt"], dict(short_names))
    grapheme_cluster_breaks = property_values("auxiliary/GraphemeBreakProperty.txt", texts["auxiliary/GraphemeBreakProperty.txt"])
    word_breaks = property_values("auxiliary/WordBreakProperty.txt", texts["auxiliary/WordBreakProperty.txt"])
    sentence_breaks = property_values("auxiliary/SentenceBreakProperty.txt", texts["auxiliary/SentenceBreakProperty.txt"])
    encoded_version = version.encode("ascii")
    header = (MAGIC
              + varint(FORMAT_VERSION)
              + varint(len(encoded_version)) + encoded_version
              + key
              + input_digest(files))
    data = (header
            + orbit_section(simple_orbits)
            + multi_character_section(SECTION_FULL_CASE_FOLDING, full_folds)
            + mapping_section(SECTION_SIMPLE_UPPERCASE_MAPPINGS, uppercase)
            + mapping_section(SECTION_SIMPLE_LOWERCASE_MAPPINGS, lowercase)
            + property_value_section(SECTION_GENERAL_CATEGORY, categories)
            + property_value_section(SECTION_SCRIPT, scripts)
            + name_section(SECTION_SCRIPT_SHORT_NAMES, short_names)
            + property_value_section(SECTION_BLOCK, blocks)
            + property_value_section(SECTION_BINARY_PROPERTIES, binary)
            + property_value_section(SECTION_AGE, ages)
            + multi_character_section(SECTION_FULL_UPPERCASE_MAPPINGS, full_uppercase)
            + multi_character_section(SECTION_FULL_LOWERCASE_MAPPINGS, full_lowercase)
            + character_name_section(SECTION_CONTROL_NAME, control_names)
            + property_value_section(SECTION_SCRIPT_EXTENSIONS, extensions)
            + property_value_section(SECTION_GRAPHEME_CLUSTER_BREAK, grapheme_cluster_breaks)
            + property_value_section(SECTION_WORD_BREAK, word_breaks)
            + property_value_section(SECTION_SENTENCE_BREAK, sentence_breaks)
            + section(SECTION_PROPERTY_ALIASES, alias_list_payload(property_aliases(texts["PropertyAliases.txt"])))
            + property_value_alias_section(SECTION_PROPERTY_VALUE_ALIASES, value_aliases))
    names_data = header + character_name_section(SECTION_NAME, names)
    return data, names_data


def generated(key, data, names):
    return ("# Written by generate.py; do not edit. TestUnicodeDatabase checks these\n"
            "# values against the committed generator, manifest, and resources.\n"
            f"generation-key {key.hex()}\n"
            f"database-sha256 {sha256(data)}\n"
            f"names-sha256 {sha256(names)}\n").encode("ascii")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="fail unless the committed database and generated.txt equal freshly generated ones, writing nothing in the source tree")
    args = parser.parse_args()

    version, files = read_manifest()
    key = generation_key()
    data, names = database(version, files, download(version, files, refresh=args.check), key)
    outputs = {
        OUTPUT_DIR / f"unicode-{version}.bin": data,
        OUTPUT_DIR / f"unicode-names-{version}.bin": names,
        GENERATED: generated(key, data, names),
    }

    if not args.check:
        for output, content in outputs.items():
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(content)
            print(f"{output.relative_to(ROOT_DIR)}: written")
        return

    differences = []
    for output, content in outputs.items():
        if not output.is_file() or output.read_bytes() != content:
            differences.append(output.relative_to(ROOT_DIR))
    for difference in differences:
        print(f"{difference}: differs from the generated file")
    if differences:
        raise SystemExit("the Unicode database is out of date; run python3 -I tools/unicode-database/generate.py")
    print(f"the Unicode {version} database is up to date")


if __name__ == "__main__":
    main()
