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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

public class TestOnePassWorkspace
{
    @Test
    public void testMatcherOwnsAndReusesCaptureScratch()
            throws ReflectiveOperationException
    {
        Re2 pattern = Re2.compile(Slices.utf8Slice("(?<first>a+)-(?<second>b+)"));
        assertThat(pattern.forwardProgramForDiagnostics().isOnePass()).isTrue();

        Slice firstInput = Slices.utf8Slice("aaa-bb trailing");
        Re2Matcher matcher = pattern.matcher(firstInput);
        Field workspaceField = field(Re2Matcher.class, "onePassWorkspace");
        assertThat(workspaceField.get(matcher)).isNull();
        assertThat(field(Re2Matcher.class, "bitStateWorkspace").get(matcher)).isNull();
        assertThat(field(Re2Matcher.class, "nfaWorkspace").get(matcher)).isNull();

        assertThat(matcher.lookingAt()).isTrue();
        assertMatch(matcher, firstInput, 0, 6, 0, 3, 4, 6);
        Object workspace = workspaceField.get(matcher);
        assertThat(workspace).isNotNull();
        assertThat(field(Re2Matcher.class, "bitStateWorkspace").get(matcher)).isNull();
        assertThat(field(Re2Matcher.class, "nfaWorkspace").get(matcher)).isNull();
        Object captures = field(OnePass.Workspace.class, "captures").get(workspace);
        Object matchCaptures = field(OnePass.Workspace.class, "matchCaptures").get(workspace);
        assertThat(captures).isNotNull();
        assertThat(matchCaptures).isNotNull();
        assertThat(matchCaptures).isNotSameAs(captures);

        Slice secondInput = Slices.utf8Slice("a-bbbb trailing");
        matcher.reset(secondInput);
        assertThat(matcher.lookingAt()).isTrue();
        assertMatch(matcher, secondInput, 0, 6, 0, 1, 2, 6);
        assertThat(field(OnePass.Workspace.class, "captures").get(workspace)).isSameAs(captures);
        assertThat(field(OnePass.Workspace.class, "matchCaptures").get(workspace)).isSameAs(matchCaptures);
    }

    private static void assertMatch(
            Re2Matcher matcher,
            Slice input,
            int matchStart,
            int matchEnd,
            int firstStart,
            int firstEnd,
            int secondStart,
            int secondEnd)
    {
        assertThat(matcher.start()).isEqualTo(matchStart);
        assertThat(matcher.end()).isEqualTo(matchEnd);
        assertThat(matcher.start(1)).isEqualTo(firstStart);
        assertThat(matcher.end(1)).isEqualTo(firstEnd);
        assertThat(matcher.start(2)).isEqualTo(secondStart);
        assertThat(matcher.end(2)).isEqualTo(secondEnd);
        assertThat(matcher.group(1)).isEqualTo(input.slice(firstStart, firstEnd - firstStart));
        assertThat(matcher.group(2)).isEqualTo(input.slice(secondStart, secondEnd - secondStart));
    }

    private static Field field(Class<?> type, String name)
            throws NoSuchFieldException
    {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
