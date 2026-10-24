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

public final class TeaVMParsingSupport
        implements SubstitutionPolicy, ResourcesPolicy
{
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
        return new String[] {"io/airlift/regulator/unicode/unicode-18.0.0.bin"};
    }
}
