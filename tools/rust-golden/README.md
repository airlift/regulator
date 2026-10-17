# Rust regex reference fixtures

These tools generate syntax aliases and match fixtures for `RustRegexp`. They
are maintenance tools only: Rust, Cargo, and native executables are not part of
the Maven build, CI test runtime, or published Java library.

The matching authority is Rust `regex` **1.13.1**. `Cargo.lock` pins its complete
dependency graph. The alias source is the same release at commit
`2b527599eb9eea0dcc288c704584f242f26a5c61`.

Run from the repository root:

```bash
cargo build --release --locked --manifest-path tools/rust-golden/Cargo.toml
mkdir -p target
python3 tools/rust-golden/generate-cases.py > target/rust-golden-cases.tsv
tools/rust-golden/target/release/regulator-rust-golden \
    < target/rust-golden-cases.tsv \
    > src/test/resources/io/airlift/regulator/rust-golden.tsv
./mvnw -Dtest=TestRustRegexp,TestRustRegexpDifferential test
```

The deterministic corpus covers syntax failures, captures, nullable repetition,
class algebra, Unicode/ASCII classes and boundaries, CRLF, UTF-8 byte offsets,
verbose tokenization, and non-overlapping iteration. It also contains generated
expressions and inputs with a fixed seed. Unicode examples use established code
points whose tested membership agrees with Regulator's supported JVM policy.
Deferred properties have explicit Java rejection tests rather than native
matching goldens.

Input TSV fields are case ID, UTF-8 pattern bytes in hexadecimal, and UTF-8 input
bytes in hexadecimal. A hyphen denotes the empty string. The output adds either
`ERROR`, `NONE`, or semicolon-separated successive matches. Each match contains
comma-separated capture start/end byte pairs, including group zero. Unmatched
captures are `-1:-1`.

## Property aliases

Property names and aliases are syntax data only. Character membership and case
folding continue to come from the JVM. To regenerate the aliases, prepare a
checkout of the pinned source, then run:

```bash
python3 tools/rust-golden/generate-property-aliases.py /path/to/regex \
    src/main/java/io/airlift/regulator/RustUnicodeAliases.java
```

The generator verifies the exact source commit and emits a sorted, immutable Java
map. The generated source is checked in; normal builds need no Rust checkout.
The Unicode license for this derived alias data is included in `src/main/resources/META-INF/LICENSE-rust-unicode.txt`.
Review regenerated changes and run the Rust frontend tests before committing.
Do not replace expected results merely because generation succeeds.
