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

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSlice
{
    @Test
    void testViewsAndCopies()
    {
        byte[] bytes = "xxéyy".getBytes(UTF_8);
        Slice view = new Slice(bytes, 2, 2);
        assertThat(view.byteArray()).isSameAs(bytes);
        assertThat(view.byteArrayOffset()).isEqualTo(2);
        assertThat(view.length()).isEqualTo(2);
        assertThat(view.toStringUtf8()).isEqualTo("é");

        assertThat(view.slice(0, 2)).isEqualTo(view);
        assertThat(view.slice(2, 0).toStringUtf8()).isEmpty();

        Slice copy = view.copy();
        assertThat(copy).isEqualTo(view);
        assertThat(copy.hashCode()).isEqualTo(view.hashCode());
        assertThat(copy.byteArray()).isNotSameAs(bytes);
        assertThat(copy.byteArrayOffset()).isZero();

        bytes[2] = 'a';
        assertThat(copy.toStringUtf8()).isEqualTo("é");
        assertThat(copy).isNotEqualTo(view);

        assertThatThrownBy(() -> view.slice(1, 2)).isInstanceOf(IndexOutOfBoundsException.class);
    }
}
