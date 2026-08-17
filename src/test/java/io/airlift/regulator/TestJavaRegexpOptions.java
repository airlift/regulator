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

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static io.airlift.slice.Slices.utf8Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestJavaRegexpOptions
{
    @Test
    public void testLiteralMode()
    {
        JavaRegexp regexp = JavaRegexp.compile(
                utf8Slice("[a]+"),
                JavaRegexp.Options.defaults().setLiteral(true));

        assertThat(regexp.matches(utf8Slice("[a]+"))).isTrue();
        assertThat(regexp.matches(utf8Slice("aaa"))).isFalse();
    }

    @Test
    public void testCaseInsensitiveLiteralMode()
    {
        JavaRegexp regexp = JavaRegexp.compile(
                utf8Slice("[k]+"),
                JavaRegexp.Options.defaults()
                        .setLiteral(true)
                        .setCaseInsensitive(true)
                        .setUnicodeCase(true));

        assertThat(regexp.matches(utf8Slice("[K]+"))).isTrue();
    }

    @Test
    public void testOptionsMatchJdkFlags()
    {
        assertMatchesJdk(
                "^élan.$",
                "ÉLAN\n",
                JavaRegexp.Options.defaults()
                        .setCaseInsensitive(true)
                        .setUnicodeCase(true)
                        .setMultiline(true)
                        .setDotMatchesNewline(true),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE | Pattern.DOTALL);
        assertMatchesJdk(
                "^b$",
                "a\rb\rc",
                JavaRegexp.Options.defaults()
                        .setMultiline(true)
                        .setUnixLines(true),
                Pattern.MULTILINE | Pattern.UNIX_LINES);
        assertMatchesJdk(
                "\\w+ # identifier",
                "élan",
                JavaRegexp.Options.defaults()
                        .setComments(true)
                        .setUnicodeCharacterClasses(true),
                Pattern.COMMENTS | Pattern.UNICODE_CHARACTER_CLASS);
    }

    @Test
    public void testCompilationSnapshotsOptions()
    {
        JavaRegexp.Options options = JavaRegexp.Options.defaults().setCaseInsensitive(true);
        JavaRegexp regexp = JavaRegexp.compile(utf8Slice("abc"), options);

        options.setCaseInsensitive(false);

        assertThat(regexp.matches(utf8Slice("ABC"))).isTrue();
    }

    @Test
    public void testMemoryBudget()
    {
        assertThat(JavaRegexp.Options.defaults().maxMemory()).isEqualTo(96L << 20);
        assertThatThrownBy(() -> JavaRegexp.Options.defaults().setMaxMemory(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> JavaRegexp.compile(
                utf8Slice("abc"),
                JavaRegexp.Options.defaults().setMaxMemory(1)))
                .isInstanceOf(RegexpCompileMemoryLimitException.class);
    }

    private static void assertMatchesJdk(String pattern, String input, JavaRegexp.Options options, int jdkFlags)
    {
        JavaRegexp regexp = JavaRegexp.compile(utf8Slice(pattern), options);
        Pattern jdkPattern = Pattern.compile(pattern, jdkFlags);

        assertThat(regexp.find(utf8Slice(input))).isEqualTo(jdkPattern.matcher(input).find());
    }
}
