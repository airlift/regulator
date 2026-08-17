# Coding standards

Use [Airlift's code style](https://github.com/airlift/codestyle/blob/master/IntelliJIdea2019/Airlift.xml).
Import it into IntelliJ. Build and test commands live in [testing.md](testing.md).

## Implementation

- Keep dialect-specific behavior in its frontend. Share small, stateless
  mechanics only when the callers intentionally have the same contract.
- Prefer readable duplication to a configurable abstraction that hides
  language differences. Document shared behavior so a diverging caller knows
  to make its own copy.
- Use `Optional.orElseThrow()` rather than `Optional.get()`.
- Validate required constructor arguments with `requireNonNull`, with messages
  such as `"options is null"`.
- Add tests with each feature and repair. Follow [testing.md](testing.md) and
  the per-language correctness gate in
  [MAINTAINING_REGULATOR.md](MAINTAINING_REGULATOR.md).
- Protected matching loops need the maintenance guide's performance checks,
  including for seemingly cosmetic changes that alter generated code.

## Naming

Use domain terms consistently across related operations. Prefer descriptive
names to obscure abbreviations or names that merely repeat the Java type.
Existing conventional abbreviations can remain; do not invent new ones just
to shorten a name. Boolean names should make the true case clear.

Use `UPPER_CASE` for constants. A simple loop can use `i`; nested or non-trivial
indexing needs names that distinguish each index. Avoid renaming established
locals solely to impose a different naming preference.

## Comments

Explain invariants, intentional semantic differences, algorithm phases, and
non-obvious performance constraints. Preserve these explanations in dense
parser, concurrency, and bit-level code. Remove comments that only restate
obvious code, and check the remaining text against the implementation.

Empty `catch` blocks must contain a short comment explaining why ignoring the
exception is intentional. These comments are required by the style checks.
