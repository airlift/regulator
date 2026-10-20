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

import io.airlift.regulator.UnicodeDatabase.Aliases;
import io.airlift.regulator.UnicodeDatabase.CodePointMappings;
import io.airlift.regulator.UnicodeDatabase.MultiCharacterMappings;
import io.airlift.regulator.UnicodeDatabase.PropertyValues;
import org.assertj.core.api.AbstractBooleanAssert;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class TestUnicodeDatabase
{
    private static final Path TOOL_DIRECTORY = Path.of("tools/unicode-database");
    private static final String REGENERATE = "out of date; rerun python3 -I tools/unicode-database/generate.py";

    // The committed database must be the one the committed generator writes from the committed
    // manifest. Regenerating needs the network, so this checks the values the generator recorded.
    @Test
    public void testDatabaseMatchesGenerator()
            throws IOException
    {
        Map<String, String> recorded = new HashMap<>();
        for (String line : Files.readAllLines(TOOL_DIRECTORY.resolve("generated.txt"), StandardCharsets.US_ASCII)) {
            if (!line.startsWith("#")) {
                String[] fields = line.split(" ");
                assertThat(fields).as(line).hasSize(2);
                recorded.put(fields[0], fields[1]);
            }
        }

        StringBuilder listing = new StringBuilder();
        for (String name : new String[] {"generate.py", "manifest.txt"}) {
            listing.append(sha256Hex(Files.readAllBytes(TOOL_DIRECTORY.resolve(name)))).append("  ").append(name).append('\n');
        }
        String generationKey = sha256Hex(listing.toString().getBytes(StandardCharsets.US_ASCII));
        assertThat(generationKey).as("generation key of generate.py and manifest.txt: generated.txt is " + REGENERATE).isEqualTo(recorded.get("generation-key"));

        byte[] database;
        try (InputStream input = UnicodeDatabase.class.getResourceAsStream(UnicodeDatabase.RESOURCE)) {
            assertThat(input).as(UnicodeDatabase.RESOURCE).isNotNull();
            database = input.readAllBytes();
        }
        assertThat(sha256Hex(database)).as("SHA-256 of %s: the database is %s", UnicodeDatabase.RESOURCE, REGENERATE).isEqualTo(recorded.get("database-sha256"));
        assertThat(HexFormat.of().formatHex(UnicodeDatabase.generationKey())).isEqualTo(generationKey);
    }

    // The case folding and simple case mapping sections decode to their UCD data, including
    // mappings to smaller code points, such as Cherokee small letters, which fold to their
    // capitals.
    @Test
    public void testSectionsHoldUnicodeMappings()
    {
        CodePointMappings simpleCaseFolding = UnicodeDatabase.simpleCaseFolding();
        assertThat(simpleCaseFolding.map('A')).isEqualTo('a');
        assertThat(simpleCaseFolding.map('a')).isEqualTo('a');
        assertThat(simpleCaseFolding.map(0x212A)).isEqualTo('k');
        assertThat(simpleCaseFolding.map(0x1E9E)).isEqualTo(0xDF);
        assertThat(simpleCaseFolding.map(0xAB70)).isEqualTo(0x13A0);
        assertThat(simpleCaseFolding.map(0x130)).isEqualTo(0x130);

        MultiCharacterMappings fullCaseFolding = UnicodeDatabase.fullCaseFolding();
        assertThat(fullCaseFolding.mapping(0xDF)).containsExactly('s', 's');
        assertThat(fullCaseFolding.mapping(0x130)).containsExactly('i', 0x307);
        assertThat(fullCaseFolding.mapping('A')).isNull();

        assertThat(UnicodeDatabase.simpleUppercaseMappings().map('a')).isEqualTo('A');
        assertThat(UnicodeDatabase.simpleUppercaseMappings().map(0xDF)).isEqualTo(0xDF);
        assertThat(UnicodeDatabase.simpleLowercaseMappings().map(0x130)).isEqualTo('i');
        assertThat(UnicodeDatabase.simpleLowercaseMappings().map(0x13A0)).isEqualTo(0xAB70);
    }

    // The property value sets hold every binary property of PropList.txt, DerivedCoreProperties.txt,
    // and emoji-data.txt, each script a ScriptExtensions.txt list names, and the break properties,
    // and the alias sections hold the PropertyAliases.txt rows and the PropertyValueAliases.txt
    // rows of six properties.
    @Test
    public void testSectionsHoldUnicodeProperties()
    {
        PropertyValues binaryProperties = UnicodeDatabase.binaryProperties();
        assertMembership(binaryProperties, "White_Space", ' ').isTrue();
        assertMembership(binaryProperties, "ID_Start", 'a').isTrue();
        assertMembership(binaryProperties, "ID_Start", '1').isFalse();
        assertMembership(binaryProperties, "Changes_When_Casefolded", 'A').isTrue();
        assertMembership(binaryProperties, "Changes_When_Casefolded", 'a').isFalse();
        assertMembership(binaryProperties, "Extended_Pictographic", 0x1F600).isTrue();
        assertThat(binaryProperties.names()).doesNotContain("InCB", "Indic_Conjunct_Break");

        // U+0640 ARABIC TATWEEL is Common, and its extension names Arabic and Syriac among others.
        assertMembership(UnicodeDatabase.scriptExtensions(), "Arab", 0x640).isTrue();
        assertMembership(UnicodeDatabase.scriptExtensions(), "Syrc", 0x640).isTrue();
        assertMembership(UnicodeDatabase.scriptExtensions(), "Latn", 'a').isFalse();

        assertThat(UnicodeDatabase.graphemeClusterBreaks().ranges("CR")).containsExactly('\r', '\r');
        assertMembership(UnicodeDatabase.wordBreaks(), "ALetter", 'a').isTrue();
        assertMembership(UnicodeDatabase.sentenceBreaks(), "ATerm", '.').isTrue();
        assertThat(UnicodeDatabase.sentenceBreaks().ranges("Other")).isNull();

        assertThat(UnicodeDatabase.propertyAliases()).contains(
                new Aliases("WSpace", "White_Space", List.of("space")),
                new Aliases("gc", "General_Category", List.of()));
        Map<String, List<Aliases>> valueAliases = UnicodeDatabase.propertyValueAliases();
        assertThat(valueAliases).containsOnlyKeys("GCB", "SB", "WB", "age", "gc", "sc");
        assertThat(valueAliases.get("gc")).contains(new Aliases("Nd", "Decimal_Number", List.of("digit")));
        assertThat(valueAliases.get("sc")).contains(new Aliases("Zinh", "Inherited", List.of("Qaai")));
        assertThat(valueAliases.get("age")).contains(new Aliases("1.1", "V1_1", List.of()));
        assertThat(valueAliases.get("WB")).contains(new Aliases("LE", "ALetter", List.of()));
    }

    private static AbstractBooleanAssert<?> assertMembership(PropertyValues values, String name, int codePoint)
    {
        return assertThat(contains(values, name, codePoint)).as("%s contains U+%04X", name, codePoint);
    }

    private static boolean contains(PropertyValues values, String name, int codePoint)
    {
        int[] ranges = values.ranges(name);
        assertThat(ranges).as(name).isNotNull();
        for (int index = 0; index < ranges.length; index += 2) {
            if (ranges[index] <= codePoint && codePoint <= ranges[index + 1]) {
                return true;
            }
        }
        return false;
    }

    private static String sha256Hex(byte[] data)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        }
        catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
