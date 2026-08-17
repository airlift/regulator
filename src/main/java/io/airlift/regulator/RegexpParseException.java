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

import static java.util.Objects.requireNonNull;

/**
 * Thrown when a pattern cannot be parsed. Offsets refer to bytes in the logical pattern Slice.
 */
public final class RegexpParseException
        extends IllegalArgumentException
{
    private final RegexpParseErrorCode errorCode;
    private final Slice invalidPatternSegment;
    private final int byteOffset;

    RegexpParseException(RegexpParseErrorCode errorCode, Slice invalidPatternSegment, int byteOffset)
    {
        this(errorCode, invalidPatternSegment, byteOffset, null);
    }

    RegexpParseException(RegexpParseErrorCode errorCode, Slice invalidPatternSegment, int byteOffset, String detail)
    {
        super(buildMessage(errorCode, detail, byteOffset));
        this.errorCode = requireNonNull(errorCode, "errorCode is null");
        this.invalidPatternSegment = invalidPatternSegment;
        this.byteOffset = byteOffset;
    }

    /**
     * Returns the stable error category.
     */
    public RegexpParseErrorCode errorCode()
    {
        return errorCode;
    }

    /**
     * Returns a copy of the invalid pattern bytes when available, otherwise {@code null}.
     */
    public Slice invalidPatternSegment()
    {
        return invalidPatternSegment == null ? null : invalidPatternSegment.copy();
    }

    /**
     * Returns the byte offset in the logical pattern, or {@code -1} when no offset is available.
     */
    public int byteOffset()
    {
        return byteOffset;
    }

    private static String buildMessage(RegexpParseErrorCode errorCode, String detail, int byteOffset)
    {
        String message = requireNonNull(errorCode, "errorCode is null").description();
        if (detail != null) {
            message += ": " + detail;
        }
        if (byteOffset >= 0) {
            return message + " at byte offset " + byteOffset;
        }
        return message;
    }
}
