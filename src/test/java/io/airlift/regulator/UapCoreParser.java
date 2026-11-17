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

import io.airlift.regulator.GoldenJsonl.JsonObject;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static io.airlift.regulator.Re2Set.MatchMode.FIND;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/// One section of the uap-core user-agent rules, `regexes.yaml` at the revision
/// `tools/re2-benchmark/manifests/uap-core-corpus.tsv` pins, parsed the way uap-core's
/// `docs/specification.md` describes and the way an application runs the rules on Regulator:
///
/// - every rule of the section is a pattern of one [FilteredRe2], in rule order
/// - one [Re2Set] finds the collection's atoms, each quoted, in the canonicalized user agent, and
///   the atoms it finds select the candidate rules
/// - the candidates run in ascending rule order, and the first that matches wins
///
/// A user agent is cut at [#MAX_USER_AGENT_BYTES] before parsing.
final class UapCoreParser
{
    static final int MAX_USER_AGENT_BYTES = 1024;

    private static final String NO_MATCH_FAMILY = "Other";
    private static final int NO_MATCH_RULE_ID = -1;

    private static final String RESOURCE_DIRECTORY = "io/airlift/regulator/uap-core/";

    /// A section of `regexes.yaml`. A rule's replacement for result field `i` is named by
    /// `replacementFields().get(i)`, and the field defaults to capturing group `i + 1`.
    enum Section
    {
        USER_AGENT,
        OS;

        /// The names uap-core's tests give the result fields, in result order.
        List<String> resultFields()
        {
            return switch (this) {
                case USER_AGENT -> List.of("family", "major", "minor", "patch");
                case OS -> List.of("family", "major", "minor", "patch", "patch_minor");
            };
        }

        private List<String> replacementFields()
        {
            return switch (this) {
                case USER_AGENT -> List.of("family_replacement", "v1_replacement", "v2_replacement", "v3_replacement");
                case OS -> List.of("os_replacement", "os_v1_replacement", "os_v2_replacement", "os_v3_replacement", "os_v4_replacement");
            };
        }

        /// Returns uap-core's test cases for this section, in file order.
        List<JsonObject> testCases()
        {
            return readResource(switch (this) {
                case USER_AGENT -> "test-ua.jsonl.gzip";
                case OS -> "test-os.jsonl.gzip";
            });
        }

        List<Rule> rules()
        {
            String resourceName = switch (this) {
                case USER_AGENT -> "user-agent-parsers.jsonl.gzip";
                case OS -> "os-parsers.jsonl.gzip";
            };
            List<String> replacementFields = replacementFields();
            List<Rule> rules = new ArrayList<>();
            for (JsonObject rule : readResource(resourceName)) {
                String[] replacements = new String[replacementFields.size()];
                for (int field = 0; field < replacements.length; field++) {
                    replacements[field] = rule.getString(replacementFields.get(field));
                }
                rules.add(new Rule(rule.getString("regex"), Arrays.asList(replacements)));
            }
            return rules;
        }
    }

    /// A rule's regular expression and its replacement for each result field, `null` where the
    /// rule has none.
    record Rule(String regex, List<String> replacements)
    {
        Rule
        {
            requireNonNull(regex, "regex is null");
            requireNonNull(replacements, "replacements is null");
        }
    }

    /// The winning rule, or [#NO_MATCH_RULE_ID], and the result fields in section order, `null`
    /// where a field is absent.
    record Parse(int ruleId, List<String> fields) {}

    private final int fieldCount;
    private final List<Rule> rules;
    private final FilteredRe2 filteredRe2;
    private final Re2Set atomSet;

    UapCoreParser(Section section, long ruleMemory, long atomSetMemory)
    {
        fieldCount = section.resultFields().size();
        rules = section.rules();
        FilteredRe2.Builder filteredBuilder = FilteredRe2.builder();
        for (Rule rule : rules) {
            filteredBuilder.add(Slices.utf8Slice(rule.regex()), Re2.Options.defaults().setMaxMemory(ruleMemory));
        }
        filteredRe2 = filteredBuilder.build();

        Re2Set.Builder atomSetBuilder = Re2Set.builder(Re2.Options.defaults().setMaxMemory(atomSetMemory), FIND);
        for (Slice atom : filteredRe2.atoms()) {
            atomSetBuilder.add(Re2.quote(atom));
        }
        atomSet = atomSetBuilder.build();
    }

    int ruleCount()
    {
        return rules.size();
    }

    int atomCount()
    {
        return atomSet.size();
    }

    /// Returns `userAgent` cut to its first [#MAX_USER_AGENT_BYTES] bytes.
    static Slice cut(Slice userAgent)
    {
        return userAgent.length() > MAX_USER_AGENT_BYTES ? userAgent.slice(0, MAX_USER_AGENT_BYTES) : userAgent;
    }

    /// Returns the text the atom set searches for a user agent already [cut][#cut(Slice)].
    Slice canonicalizeText(Slice userAgent)
    {
        return filteredRe2.canonicalizeText(userAgent);
    }

    /// Returns the IDs of the atoms found in `canonicalText`, in no particular order.
    int[] matchingAtomIds(Slice canonicalText)
    {
        return atomSet.matchingPatternIds(canonicalText);
    }

    Parse parse(Slice userAgent)
    {
        Slice text = cut(userAgent);
        int[] atomIds = atomSet.matchingPatternIds(filteredRe2.canonicalizeText(text));
        for (int ruleId : filteredRe2.potentialPatternIds(atomIds)) {
            MatchResult match = filteredRe2.pattern(ruleId).findResult(text);
            if (match != null) {
                return new Parse(ruleId, fields(rules.get(ruleId), match));
            }
        }
        String[] fields = new String[fieldCount];
        fields[0] = NO_MATCH_FAMILY;
        return new Parse(NO_MATCH_RULE_ID, Arrays.asList(fields));
    }

    /// Fills result field `i` from the rule's replacement for it, with each `$1` to `$9` replaced by
    /// that group's text, or else from group `i + 1`. A replacement without a group reference is
    /// used verbatim. A field whose text is empty is absent.
    private static List<String> fields(Rule rule, MatchResult match)
    {
        String[] fields = new String[rule.replacements().size()];
        for (int field = 0; field < fields.length; field++) {
            String replacement = rule.replacements().get(field);
            String value = replacement == null ? group(match, field + 1) : substitute(replacement, match);
            fields[field] = value == null || value.isEmpty() ? null : value;
        }
        return Arrays.asList(fields);
    }

    private static String substitute(String replacement, MatchResult match)
    {
        if (replacement.indexOf('$') < 0) {
            return replacement;
        }
        StringBuilder value = new StringBuilder(replacement.length());
        for (int index = 0; index < replacement.length(); index++) {
            char character = replacement.charAt(index);
            char next = index + 1 < replacement.length() ? replacement.charAt(index + 1) : 0;
            if (character == '$' && next >= '1' && next <= '9') {
                index++;
                String group = group(match, next - '0');
                if (group != null) {
                    value.append(group);
                }
            }
            else {
                value.append(character);
            }
        }
        return value.toString();
    }

    /// Returns the text of `group`, or `null` when the rule has no such group or it did not
    /// participate in the match.
    private static String group(MatchResult match, int group)
    {
        return group <= match.groupCount() ? match.groupUtf8(group) : null;
    }

    /// Reads a uap-core resource the provisioning script wrote, one JSON object per line.
    private static List<JsonObject> readResource(String resourceName)
    {
        String resource = RESOURCE_DIRECTORY + resourceName;
        InputStream input = UapCoreParser.class.getClassLoader().getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalArgumentException("uap-core resource does not exist: " + resource);
        }
        List<JsonObject> objects = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(input), UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                objects.add(GoldenJsonl.parseObject(resource, objects.size() + 1, line));
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("failed to read uap-core resource: " + resource, e);
        }
        return objects;
    }
}
