# Readable source for ../random-cases.tsv. Run emit.py after editing.
#
# Each case is (id, pattern, options, text, start, end), as in match_cases.py.
# Every pattern and text is drawn from one seeded generator in the order below,
# so editing an earlier section changes every later draw. A pattern and text
# pair that repeats an earlier case is skipped, and each identifier keeps its
# draw number so identifiers stay stable. Every case uses the default options
# and searches the whole text. David Phillips wrote this corpus for the front
# end's first golden generator, in pull request 11.
import random

cases = []
seen = set()


def section(title):
    cases.append(("#", title))


def case(id, pattern, text):
    if (pattern, text) not in seen:
        seen.add((pattern, text))
        cases.append((id, pattern, "-", text, "-", "-"))


# Fixed texts, which the malformed patterns draw from.
inputs = ['', 'a', 'ab', 'ba', 'aaa', 'abaac', 'abcd', '1234', '4', 'kK\u212asS\u017f',
          '\u00e9', '\u00e9a', 'a\u00e9', '\u0661\u0662', '\u03b1\u0391\u03b2', '\u0301a', '\u200c', '\u200d',
          '\U0001f4b0a\U0001f4b0', '\n', '\r', '\r\n', '\r\nfoo\r\n', 'a\nb\n', '\x85\u2028',
          'a\u00a0b', 'a b\t\n', '\x07\f\t\n\r\v', '[]#&_-']

rng = random.Random(790431)
atoms = ['a', 'b', '.', '[ab]', '[^a]', r'\w', r'\b', '^', '$', '\u00e9', '']


def expression(depth):
    if depth == 0:
        return rng.choice(atoms)
    kind = rng.randrange(5)
    if kind == 0:
        return '(' + expression(depth - 1) + ')' + rng.choice(['*', '+', '?', '*?', '{0,2}', '{2}', ''])
    if kind == 1:
        return '(?:' + expression(depth - 1) + '|' + expression(depth - 1) + ')'
    if kind == 2:
        return expression(depth - 1) + expression(depth - 1)
    if kind == 3:
        return '(?' + rng.choice(['i', 'm', 's', 'U', 'mR', '-u']) + ':' + expression(depth - 1) + ')'
    return rng.choice(atoms)


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
