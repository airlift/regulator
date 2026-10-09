#!/usr/bin/env python3
"""Generate syntax aliases (not Unicode character tables) from Rust regex 1.13.1."""
import argparse
import json
from pathlib import Path
import re
import subprocess

REVISION = '2b527599eb9eea0dcc288c704584f242f26a5c61'


def generate(source):
    revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != REVISION:
        raise ValueError(f'Expected Rust regex {REVISION}, got {revision}')
    tables = source / 'regex-syntax/src/unicode_tables'
    names = dict(re.findall(r'\("([^"]+)", "([^"]+)"\)', (tables / 'property_names.rs').read_text(encoding="utf-8")))
    values = {}
    for name, entries in re.findall(r'    \(\n        "([^"]+)",\n        &\[(.*?)        \],\n    \),', (tables / 'property_values.rs').read_text(encoding="utf-8"), re.S):
        values[name] = dict(re.findall(r'\("([^"]+)", "([^"]+)"\)', entries))
    assert len(values) == 7, values.keys()
    values['General_Category'].update({'any': 'Any', 'ascii': 'ASCII', 'assigned': 'Assigned'})
    boolean_names = set(re.findall(r'\("([^"]+)",', (tables / 'property_bool.rs').read_text(encoding="utf-8").split('];', 1)[0]))
    aliases = {}
    for family in ['Script', 'General_Category']:
        for alias, value in values[family].items():
            aliases[alias] = f'{family}={value}'
    # Rust gives property names precedence over category/script aliases, with three exceptions.
    for alias, name in names.items():
        if alias in ('cf', 'sc', 'lc'):
            continue
        aliases.pop(alias, None)
        if name in boolean_names:
            aliases[alias] = f'binary={name}'
    for alias, name in names.items():
        for value_alias, value in values.get(name, {}).items():
            aliases[f'{alias}={value_alias}'] = f'{name}={value}'
    entries = ',\n'.join(f'            entry({json.dumps(alias)}, {json.dumps(canonical)})'
                         for alias, canonical in sorted(aliases.items()))
    return JAVA_HEADER + entries + ");\n\n    private RustUnicodeAliases() {}\n}\n"


JAVA_HEADER = """/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.regulator;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Generated from Rust regex 1.13.1 by tools/rust-golden/generate-property-aliases.py.
 * Unicode property aliases only; character membership comes from the JVM.
 * See META-INF/LICENSE-rust-unicode.txt for the Unicode data license.
 */
final class RustUnicodeAliases
{
    // Explicit type arguments keep javac inference bounded for this large initializer.
    static final Map<String, String> ALIASES = Map.<String, String>ofEntries(
"""


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.write_text(generate(args.source), encoding="utf-8")
