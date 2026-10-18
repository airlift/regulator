#!/usr/bin/env python3
"""Generate or check the Regulator Unicode database from pinned UCD files."""

# Downloads the Unicode Character Database (UCD) files that manifest.txt lists
# into target/unicode-database/<version>/ and writes
# src/main/resources/io/airlift/regulator/unicode/unicode-<version>.bin and
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
# generated.txt records two values, which TestUnicodeDatabase checks on every
# build without a network: the generation key, and the SHA-256 of the
# database. The generation key is the SHA-256 of the two lines
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
#                      "<sha256 hex>  <file name>\n" per UCD file, sorted by
#                      file name
#   sections           until the end of the file, each a varint section id, a
#                      varint payload byte length, and the payload, in
#                      ascending order of id; a reader skips a section id it
#                      does not know
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
# The generator refuses data that breaks what the readers assume: a code point
# with two mappings of one kind, a simple fold whose target folds again, a full
# fold whose result folds again, a status S mapping without a status F one,
# simple case folding orbits that differ from the classes of code points with
# equal full folding, and a lowercase-of-uppercase mapping that is not
# idempotent.

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

URL_PATTERN = re.compile(r"https://www\.unicode\.org/Public/(\d+\.\d+\.\d+)/ucd/([A-Za-z]+\.txt)")
INPUT_FILES = ("CaseFolding.txt", "UnicodeData.txt")

MAGIC = b"RGUD"
FORMAT_VERSION = 1
SECTION_SIMPLE_CASE_FOLDING_ORBITS = 1
SECTION_FULL_CASE_FOLDING = 2
SECTION_SIMPLE_UPPERCASE_MAPPINGS = 3
SECTION_SIMPLE_LOWERCASE_MAPPINGS = 4
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
        data = None if refresh or not file.is_file() else file.read_bytes()
        if data is None or sha256(data) != expected:
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            checksum = sha256(data)
            if checksum != expected:
                raise ValueError(f"{url} has sha256 {checksum}, not the {expected} pinned in manifest.txt")
            file.write_bytes(data)
        text = data.decode("utf-8")
        if name == "CaseFolding.txt":
            check_header(url, name, text, version)
        texts[name] = text
    return texts


def check_header(url, name, text, version):
    expected = f"# {pathlib.PurePosixPath(name).stem}-{version}.txt"
    if text.split("\n", 1)[0].rstrip("\r") != expected:
        raise ValueError(f"{url} does not start with {expected!r}")


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
    # UnicodeData.txt rows have 15 fields; field 12 is the simple uppercase
    # mapping and field 13 the simple lowercase mapping. A range of code points,
    # given by a First row and a Last row, has no mappings.
    uppercase = {}
    lowercase = {}
    previous = -1
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
        for mappings, field in ((uppercase, fields[12]), (lowercase, fields[13])):
            if not field:
                continue
            if fields[1].endswith(", First>") or fields[1].endswith(", Last>"):
                raise ValueError(f"UnicodeData.txt:{number}: a code point range has a case mapping")
            target = code_point(field, "UnicodeData.txt", number)
            if target == source:
                raise ValueError(f"UnicodeData.txt:{number}: U+{source:04X} maps to itself")
            mappings[source] = target

    # Java's case classes fold a code point to the lowercase of its uppercase,
    # which must fold to itself again.
    def java_fold(value):
        upper = uppercase.get(value, value)
        return lowercase.get(upper, upper)

    for value in range(MAX_CODE_POINT + 1):
        fold = java_fold(value)
        if java_fold(fold) != fold:
            raise ValueError(f"U+{value:04X} folds to U+{fold:04X} by lowercase of uppercase, which folds again")
    return sorted(uppercase.items()), sorted(lowercase.items())


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


def full_folding_section(mappings):
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
    return section(SECTION_FULL_CASE_FOLDING, bytes(payload))


def mapping_section(section_id, mappings):
    payload = bytearray(varint(len(mappings)))
    previous_source = 0
    for source, target in mappings:
        payload += varint(source - previous_source)
        payload += signed_varint(target - source)
        previous_source = source
    return section(section_id, bytes(payload))


def database(version, files, texts, key):
    simple_orbits, full_folds = case_folding(texts["CaseFolding.txt"])
    uppercase, lowercase = unicode_data(texts["UnicodeData.txt"])
    encoded_version = version.encode("ascii")
    return (MAGIC
            + varint(FORMAT_VERSION)
            + varint(len(encoded_version)) + encoded_version
            + key
            + input_digest(files)
            + orbit_section(simple_orbits)
            + full_folding_section(full_folds)
            + mapping_section(SECTION_SIMPLE_UPPERCASE_MAPPINGS, uppercase)
            + mapping_section(SECTION_SIMPLE_LOWERCASE_MAPPINGS, lowercase))


def generated(key, data):
    return ("# Written by generate.py; do not edit. TestUnicodeDatabase checks these\n"
            "# values against the committed generator, manifest, and database.\n"
            f"generation-key {key.hex()}\n"
            f"database-sha256 {sha256(data)}\n").encode("ascii")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="fail unless the committed database and generated.txt equal freshly generated ones, writing nothing in the source tree")
    args = parser.parse_args()

    version, files = read_manifest()
    key = generation_key()
    data = database(version, files, download(version, files, refresh=args.check), key)
    outputs = {
        OUTPUT_DIR / f"unicode-{version}.bin": data,
        GENERATED: generated(key, data),
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
