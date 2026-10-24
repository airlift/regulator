# Writes ../syntax-cases.tsv, ../match-cases.tsv, and ../random-cases.tsv from
# syntax_cases.py, match_cases.py, and random_cases.py. Run with
# `python3 -I tools/rust-regex-golden/cases/emit.py`, then regenerate the
# fixtures with generate.sh.
#
# Patterns and texts are written as lowercase UTF-8 hex, or `-` when empty, so
# the tables hold arbitrary text without depending on a platform encoding. A
# text given as a bytes object is written as those bytes.
# Output depends only on the case lists, so rerunning emit.py is a no-op.

import importlib.util
import pathlib
import sys

# Keep the source tree free of __pycache__ directories.
sys.dont_write_bytecode = True

CASES_DIR = pathlib.Path(__file__).resolve().parent
TOOL_DIR = CASES_DIR.parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, CASES_DIR / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.cases


def encode(text):
    data = text if isinstance(text, bytes) else text.encode("utf-8")
    return data.hex() if data else "-"


def emit(output, header, cases, row):
    ids = set()
    lines = [header]
    for case in cases:
        if case[0] == "#":
            lines.extend(["", f"# {case[1]}"])
            continue
        if case[0] in ids:
            raise ValueError(f"duplicate case id {case[0]}")
        ids.add(case[0])
        lines.append("\t".join(row(*case)))
    with open(TOOL_DIR / output, "w", encoding="ascii", newline="\n") as file:
        file.write("\n".join(lines) + "\n")
    print(f"{output}: {len(ids)} cases")


def main():
    emit(
        "syntax-cases.tsv",
        "# id\tpattern hex\toptions",
        load("syntax_cases"),
        lambda id, pattern, options: [id, encode(pattern), options],
    )
    for output, source in [("match-cases.tsv", "match_cases"), ("random-cases.tsv", "random_cases")]:
        emit(
            output,
            "# id\tpattern hex\toptions\ttext hex\tstart\tend",
            load(source),
            lambda id, pattern, options, text, start, end: [id, encode(pattern), options, encode(text), start, end],
        )


if __name__ == "__main__":
    main()
