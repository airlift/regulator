/*
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

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

final class TestingExhaustive
{
    private TestingExhaustive() {}

    public static void runExhaustiveTest(
            List<String> atomStrings,
            List<Slice> ops,
            int maxAtoms,
            int maxOps,
            String strAlphabet,
            int maxStrLen,
            String topWrapper)
    {
        List<Slice> alphabet = StringGenerator.explodeUtf8(Slices.wrappedBuffer(strAlphabet.getBytes(UTF_8)));
        runExhaustiveTestWithAlphabet(atomStrings, ops, maxAtoms, maxOps, alphabet, maxStrLen, topWrapper);
    }

    public static void runExhaustiveTestWithAlphabet(
            List<String> atomStrings,
            List<Slice> ops,
            int maxAtoms,
            int maxOps,
            List<Slice> alphabet,
            int maxStrLen,
            String topWrapper)
    {
        List<Slice> atoms = new ArrayList<>();
        for (String a : atomStrings) {
            atoms.add(Slices.wrappedBuffer(a.getBytes(UTF_8)));
        }

        CollectingGenerator gen = new CollectingGenerator(atoms, ops, maxAtoms, maxOps);
        gen.generate();

        StringGenerator strings = new StringGenerator(maxStrLen, alphabet);

        Slice wrapperPrefix = null;
        Slice wrapperSuffix = null;
        if (topWrapper != null) {
            int placeholderIndex = topWrapper.indexOf("%s");
            if (placeholderIndex >= 0) {
                wrapperPrefix = Slices.wrappedBuffer(topWrapper.substring(0, placeholderIndex).getBytes(UTF_8));
                wrapperSuffix = Slices.wrappedBuffer(topWrapper.substring(placeholderIndex + 2).getBytes(UTF_8));
            }
        }

        List<String> failures = new ArrayList<>();
        for (Slice regexp : gen.regexps()) {
            Slice testRegexp = regexp;
            if (wrapperPrefix != null) {
                testRegexp = Slices.wrappedBuffer(ByteArrays.concat(wrapperPrefix, regexp, wrapperSuffix));
            }

            Tester tester = new Tester(testRegexp, Tester.Config.fullMatrix());
            if (tester.error()) {
                failures.add("regexp=" + toUtf8(testRegexp) + " error: " + tester.failureMessage());
                continue;
            }

            strings.reset();
            while (strings.hasNext()) {
                Slice text = strings.next();
                if (!tester.testInput(text)) {
                    failures.add("regexp=" + toUtf8(testRegexp) + " " + tester.failureMessage());
                    break;
                }
            }
        }

        assertThat(failures).isEmpty();
    }

    private static String toUtf8(Slice bytes)
    {
        return new String(bytes.byteArray(), bytes.byteArrayOffset(), bytes.length(), UTF_8);
    }

    private static final class CollectingGenerator
            extends RegexpGenerator
    {
        private final List<Slice> regexps = new ArrayList<>();

        CollectingGenerator(List<Slice> atoms, List<Slice> ops, int maxAtoms, int maxOps)
        {
            super(atoms, ops, maxAtoms, maxOps);
        }

        @Override
        protected void handleRegexp(Slice regexp)
        {
            regexps.add(regexp);
        }

        public List<Slice> regexps()
        {
            return regexps;
        }
    }
}
