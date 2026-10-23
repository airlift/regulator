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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.checkFromIndexSize;
import static java.util.Objects.requireNonNull;

/// Parser-facing Slice operations for TeaVM, without JVM object sizing or VarHandles.
public final class Slice
{
    static final Slice EMPTY_SLICE = new Slice(new byte[0]);
    private final byte[] bytes;
    private final int offset;
    private final int length;

    Slice(byte[] bytes)
    {
        this(bytes, 0, bytes.length);
    }

    Slice(byte[] bytes, int offset, int length)
    {
        this.bytes = requireNonNull(bytes, "bytes is null");
        checkFromIndexSize(offset, length, bytes.length);
        this.offset = offset;
        this.length = length;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Slice exposes its backing bytes and offset for parser access")
    public byte[] byteArray()
    {
        return bytes;
    }

    public int byteArrayOffset()
    {
        return offset;
    }

    public int length()
    {
        return length;
    }

    public Slice slice(int index, int size)
    {
        checkFromIndexSize(index, size, length);
        return new Slice(bytes, offset + index, size);
    }

    public Slice copy()
    {
        return new Slice(Arrays.copyOfRange(bytes, offset, offset + length));
    }

    public String toStringUtf8()
    {
        return new String(bytes, offset, length, UTF_8);
    }

    @Override
    public boolean equals(Object other)
    {
        return other instanceof Slice slice &&
                Arrays.equals(bytes, offset, offset + length, slice.bytes, slice.offset, slice.offset + slice.length);
    }

    @Override
    public int hashCode()
    {
        int hash = 1;
        for (int i = offset; i < offset + length; i++) {
            hash = 31 * hash + bytes[i];
        }
        return hash;
    }
}
