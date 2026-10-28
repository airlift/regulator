# Readable source for ../random-cases.tsv. Run emit.py after editing.
#
# Each case is (id, pattern, options, text, start, end), as in match_cases.py.
# Every pattern and text is drawn from one seeded generator in the order below,
# so editing an earlier section changes every later draw. A case whose pattern,
# text, and options repeat an earlier case is skipped, and each identifier keeps
# its draw number so identifiers stay stable. Every case searches the whole text,
# and every case before the bytes API section uses the default options. David
# Phillips wrote this corpus for the front end's first golden generator, in
# pull request 11.
import random

cases = []
seen = set()


def section(title):
    cases.append(("#", title))


def case(id, pattern, text, options="-"):
    if (pattern, text, options) not in seen:
        seen.add((pattern, text, options))
        cases.append((id, pattern, options, text, "-", "-"))


# Fixed texts, which the malformed patterns draw from.
inputs = ['', 'a', 'ab', 'ba', 'aaa', 'abaac', 'abcd', '1234', '4', 'kK\u212asS\u017f',
          '\u00e9', '\u00e9a', 'a\u00e9', '\u0661\u0662', '\u03b1\u0391\u03b2', '\u0301a', '\u200c', '\u200d',
          '\U0001f4b0a\U0001f4b0', '\n', '\r', '\r\n', '\r\nfoo\r\n', 'a\nb\n', '\x85\u2028',
          'a\u00a0b', 'a b\t\n', '\x07\f\t\n\r\v', '[]#&_-']

rng = random.Random(790431)
atoms = ['a', 'b', '.', '[ab]', '[^a]', r'\w', r'\b', '^', '$', '\u00e9', '']


def expression(depth, choices=atoms):
    if depth == 0:
        return rng.choice(choices)
    kind = rng.randrange(5)
    if kind == 0:
        return '(' + expression(depth - 1, choices) + ')' + rng.choice(['*', '+', '?', '*?', '{0,2}', '{2}', ''])
    if kind == 1:
        return '(?:' + expression(depth - 1, choices) + '|' + expression(depth - 1, choices) + ')'
    if kind == 2:
        return expression(depth - 1, choices) + expression(depth - 1, choices)
    if kind == 3:
        return '(?' + rng.choice(['i', 'm', 's', 'U', 'mR', '-u']) + ':' + expression(depth - 1, choices) + ')'
    return rng.choice(choices)


section("Random expressions over random texts")
for index in range(800):
    case(f"random_{index:03}", expression(3), ''.join(rng.choice('ab\u00e9\r\n ') for _ in range(rng.randrange(15))))

section("Malformed token streams")
# Exercise parser termination and deterministic errors.
for index in range(600):
    pattern = ''.join(rng.choice('ab()[]{}?*+|^$-~&012imx: <>\\') for _ in range(rng.randrange(18)))
    case(f"malformed_{index:03}", pattern, rng.choice(inputs))

section("Property name regressions")
# Rust's ASCII-only "is" prefix and the exact hexadecimal digit property.
for pattern_index, pattern in enumerate(['\\p{L\u00e9}', r'\p{IsL}', '\\p{\u0130sL}', '\\p{\u0131SL}',
                                         '\\p{\u0130sgc=Lu}', '\\p{gc=\u0130sLu}', '\\p{sc=\u0130sLatin}',
                                         r'\p{Hex_Digit}', r'\P{Hex_Digit}', r'\p{ASCII_Hex_Digit}',
                                         '\\p{i\u017fL}', '\\p{I\u017fL}', '\\p{\u0130\u017fL}', '\\p{\u0131\u017fL}']):
    for text_index, text in enumerate(['a', 'A', '1', '\u0661', '\u096f', '\U0001d7d9', '\uff11', '\uff21', '\uff46', '\uff27', '\uff47']):
        case(f"property_{pattern_index:02}_{text_index:02}", pattern, text)

section("Random byte expressions over random bytes with the bytes API")
# Byte and Unicode constructs over texts that split and break UTF-8: a, é and
# its two bytes alone, a byte that is never UTF-8, CR, LF, and space.
byte_atoms = ['a', '.', '(?-u:.)', r'(?-u:\xA9)', '(?-u:[^a])', '[^a]', r'\w', r'(?-u:\w)', r'\b', r'\B',
              r'(?-u:\b)', r'\b{start-half}', r'\b{end}', '^', '$', '\u00e9', '']
byte_texts = [b'a', b'\xc3\xa9', b'\xc3', b'\xa9', b'\xff', b'\r', b'\n', b' ']
for index in range(400):
    text = b''.join(rng.choice(byte_texts) for _ in range(rng.randrange(12)))
    case(f"bytes_{index:03}", expression(3, byte_atoms), text, "bytes")

section("Random assertion expressions over CRLF and non-ASCII texts")
# Rust's CRLF line anchors and word boundaries next to CR, LF, and bytes outside
# ASCII, which the DFA evaluates or quits on, alternating the string and bytes
# APIs. String texts are valid UTF-8; bytes texts also split and break it.
assertion_atoms = ['a', '_', r'\w', r'\W', '.', r'\b', r'\B', r'\b{start}', r'\b{end}', r'\b{start-half}',
                   r'\b{end-half}', r'\<', r'\>', r'(?-u:\b)', r'(?-u:\b{start})', r'(?-u:\b{end-half})',
                   '^', '$', '(?m:^)', '(?m:$)', '(?mR:^)', '(?mR:$)', '\r', '\n', '\u00e9']
assertion_texts = ['a', '_', ' ', '\r', '\n', '\u00e9', '\u03b1']
assertion_byte_texts = [b'a', b'_', b' ', b'\r', b'\n', b'\xc3\xa9', b'\xc3', b'\xa9', b'\xff']
for index in range(400):
    pattern = expression(3, assertion_atoms)
    if index % 2 == 0:
        text, options = ''.join(rng.choice(assertion_texts) for _ in range(rng.randrange(12))), "-"
    else:
        text, options = b''.join(rng.choice(assertion_byte_texts) for _ in range(rng.randrange(12))), "bytes"
    case(f"assertion_{index:03}", pattern, text, options)
