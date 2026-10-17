#!/usr/bin/env python3
"""Deterministic Rust frontend syntax, assertion, capture and iteration cases."""
import random
import sys


def hex_text(value):
    return value.encode().hex() or '-'


patterns = [
    '', 'a', 'é', '.', '.*', '.*?', 'a*', 'a|', '|a', '(a?)*', '(a*)*', '(a|ab)+',
    '(?P<name>a)(b)?', '(?<part.name>a)', '(?<_>é)', '(?<名前>.)', '(?<a[b]>a)',
    r'\d+', r'\D+', r'\s+', r'\S+', r'\w+', r'\W+', r'\pL+', r'\p{Greek}+',
    r'\p{gc=Lu}+', r'\p{Script:Latin}+', r'\P{^Greek}', r'\p{sc!=Greek}',
    r'\p{IsWhite_Space}', r'\p{space}', r'\p{ASCII}', r'\p{Any}', r'\p{Assigned}',
    r'\p{general category=decimal number}', r'\p{latin}', r'\p{gc=Cs}',
    r'\x61', r'\x{000061}', r'\u0061', r'\u{61}', r'\U00000061', r'\U{61}',
    r'\a\f\t\n\r\v', r'\ ', r'\#', r'\_', r'\-', r'\&',
    'a{01}', 'a{ 1 , 2 }', 'a{0}', 'a**', 'a++', 'a?+', 'a{2}{2}',
    '[a-c]', '[^a]', '[a-c&&b-d]', '[a-g~~b-h]', '[0-9--4]', '[a&&b]',
    '[a-z&&[^aeiou]]+', '[a-z--[aeiou]]+', '[a-cd-f&&c-e--d]', '[a[b]]',
    '[]a]', '[^]a]', '[-a]', '[a-]', '[--a]', '[a&&]', '[&&a]', '[a~~]',
    '[[:alpha:]]', '[[:^alpha:]]', '[[:word:]]', '[[:space:]]', '[[:punct:]]',
    '(?i)[a-z--k]', '(?i)[^a]', r'(?i)\P{Lu}', r'(?i)[\P{Lu}]',
    '(?i)k', '(?i)ſ', '(?i-u)k', '(?i-u)[a-z]', '(?U)a+', '(?U)a+?',
    '(?i)a(?-i)b', '(?i:a)b', '(?i:a|b)c', '(a(?i)b)c', '(?i)|a',
    '(?x)a # comment\n b', '(?x)[ a # comment\n b ]', '(?x:a)b c',
    '(?x)a * ?', '(?x) a { 1 , 2 }', '(?x) a\u2003b',
    '^', '$', '^$', r'\A', r'\z', r'\b', r'\B', r'\<', r'\>',
    r'\b{start}', r'\b{end}', r'\b{start-half}', r'\b{end-half}',
    r'\b{2}', r'\b\w+\b', r'\b{start-half}.', r'.\b{end-half}',
    '(?m)^', '(?m)$', '(?m)^$', '(?mR)^', '(?mR)$', '(?mR)^$',
    '(?R).', '(?sR).', '(?mR)^foo$', '(?mR)^\n', '(?mR)\r$',
    r'(?-u)\b', r'(?-u)\B', r'(?-u)\b{start}', r'(?-u)\b{end}',
    r'(?-u)\b{start-half}', r'(?-u)\b{end-half}', r'(?-u:\w+)',
    r'(?-u:[\D&&a])', r'(?-u:[^\D])', r'(?-u:[^\x80-\xFF])',
    # Invalid Rust syntax, including constructs accepted by other Regulator frontends.
    '(', ')', '[', '[a-', 'a{', 'a{1', 'a{,2}', 'a{2,1}', '*a', '(?)', '(?-)',
    '(?ii)a', '(?i-i)a', '(?d)a', '(?=a)', '(?!a)', '(?<=a)', '(?>a)',
    '(?<1a>a)', '(?<a>a)(?<a>b)', r'\1', r'\123', r'\Qabc\E', r'\Z', r'\C',
    r'\h', r'\R', r'\uD800', r'\x{110000}', r'\x{}', r'\u123', '\\',
    r'[\b]', r'[\A]', r'[\<]', r'\p{javaLowerCase}', r'\p{InGreek}',
    '(?-u:.)', '(?-u:é)', r'(?-u:\D)', r'(?-u:\pL)', r'(?-u:\xFF)',
    r'(?-u:\xC3\xA9)', r'[a-\d]', '[a-[b]]', '[[:unknown:]]',
]
patterns += [
    r'(?x)\x 6 1', r'(?x)\x{ 6 1 }', r'(?x)\u 0 0 6 1',
    r'(?x)\U 0 0 0 0 0 0 6 1', '(?x)\\p{ L # comment\n }', r'(?x)\p L',
    '(?x)a{1 2}', '(?x)a{1,2} ?', '(?x)(? i)a', '(?x)( ?i)a',
    '(?x)(?< name>a)', '(?x)(?P <a>a)', '(?x)[[ :alpha: ]]',
    '(?x)\\b { start }', '(?x)[ a - c ]', '[---a]', '[a---b]',
    '[----a]', '[a----b]', '[---]', '[--]', r'(?-u:\u00E9)',
    r'(?-u:\x{E9})', '(?-u:[é])', '(?-u:[é&&a])',
]
inputs = ['', 'a', 'ab', 'ba', 'aaa', 'abaac', 'abcd', '1234', '4', 'kKKsSſ',
          'é', 'éa', 'aé', '١٢', 'αΑβ', '\u0301a', '\u200c', '\u200d',
          '💰a💰', '\n', '\r', '\r\n', '\r\nfoo\r\n', 'a\nb\n', '\x85\u2028',
          'a\u00a0b', 'a b\t\n', '\x07\f\t\n\r\v', '[]#&_-']
seen = set()

def emit(pattern, text):
    if (pattern, text) not in seen:
        seen.add((pattern, text))
        print(f'rust-{len(seen):05}\t{hex_text(pattern)}\t{hex_text(text)}')

for pattern in patterns:
    for text in inputs:
        emit(pattern, text)

rng = random.Random(790431)
atoms = ['a', 'b', '.', '[ab]', '[^a]', r'\w', r'\b', '^', '$', 'é', '']

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

for _ in range(800):
    emit(expression(3), ''.join(rng.choice('abé\r\n ') for _ in range(rng.randrange(15))))

# Malformed token streams exercise parser termination and deterministic errors.
for _ in range(600):
    pattern = ''.join(rng.choice('ab()[]{}?*+|^$-~&012imx: <>\\') for _ in range(rng.randrange(18)))
    emit(pattern, rng.choice(inputs))

# Review regressions: Rust's ASCII-only "is" prefix and exact hexadecimal property.
for pattern in [r'\p{Lé}', r'\p{IsL}', r'\p{İsL}', r'\p{ıSL}',
                r'\p{İsgc=Lu}', r'\p{gc=İsLu}', r'\p{sc=İsLatin}',
                r'\p{Hex_Digit}', r'\P{Hex_Digit}', r'\p{ASCII_Hex_Digit}',
                r'\p{iſL}', r'\p{IſL}', r'\p{İſL}', r'\p{ıſL}']:
    for text in ['a', 'A', '1', '١', '९', '𝟙', '１', 'Ａ', 'ｆ', 'Ｇ', 'ｇ']:
        emit(pattern, text)
