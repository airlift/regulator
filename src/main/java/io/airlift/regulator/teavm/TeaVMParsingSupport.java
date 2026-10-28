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
package io.airlift.regulator.teavm;

import org.teavm.extension.spi.resources.ResourcesPolicy;
import org.teavm.extension.spi.substitution.SubstitutionPolicy;
import org.teavm.extension.spi.substitution.SubstitutionSink;

import java.util.Collection;

/// TeaVM support for compiling Regulator's pattern-parsing API to JavaScript or Wasm GC.
///
/// It substitutes this package's byte-array-backed [Slice] for Airlift Slice and includes the
/// pinned Unicode data that parsing reads, adding the character names only when the program can
/// reach Java's `\N{name}` lookup, as any use of `JavaRegexp.parse` does. Matching and full
/// compilation remain JVM-only.
///
/// Regulator does not register this class automatically. To enable it, name it in both of these
/// files in the application's `META-INF/services` resources:
///
/// - `org.teavm.extension.spi.substitution.SubstitutionPolicy`
/// - `org.teavm.extension.spi.resources.ResourcesPolicy`
public final class TeaVMParsingSupport
        implements SubstitutionPolicy, ResourcesPolicy
{
    // These mirror UnicodeDatabase's resources, which are package-private there.
    private static final String RESOURCE = "io/airlift/regulator/unicode/unicode-18.0.0.bin";
    private static final String NAMES_RESOURCE = "io/airlift/regulator/unicode/unicode-names-18.0.0.bin";

    @Override
    public void contribute(SubstitutionSink sink)
    {
        sink.selectClasses(name -> name.equals("io.airlift.slice.Slice"))
                .replacePackage("io.airlift.slice", "io.airlift.regulator.teavm")
                .dontFallbackWhenNoSubstitution();
    }

    @Override
    public String[] supplyResources(Collection<? extends String> classNames)
    {
        // Only Java's \N{name} escapes read character names, so their larger resource is included
        // only when the program reaches the class that loads it.
        if (classNames.contains("io.airlift.regulator.UnicodeDatabase$Names")) {
            return new String[] {RESOURCE, NAMES_RESOURCE};
        }
        return new String[] {RESOURCE};
    }
}
