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

/**
 * The category of a regular-expression syntax error.
 */
public enum RegexpParseErrorCode
{
    INTERNAL_ERROR("internal parser error"),
    BAD_ESCAPE("invalid escape sequence"),
    BAD_CHAR_CLASS("invalid character class"),
    BAD_CHAR_RANGE("invalid character range"),
    MISSING_BRACKET("missing closing bracket"),
    MISSING_PAREN("missing closing parenthesis"),
    UNEXPECTED_PAREN("unexpected closing parenthesis"),
    TRAILING_BACKSLASH("trailing backslash"),
    REPEAT_ARGUMENT("invalid repetition argument"),
    REPEAT_SIZE("repetition size exceeds the supported limit"),
    BAD_REPEAT_OP("invalid repetition operator"),
    BAD_PERL_OP("invalid Perl syntax"),
    BAD_UTF8("pattern is not valid UTF-8"),
    BAD_NAMED_CAPTURE("invalid named capturing group"),
    UNSUPPORTED_CONSTRUCT("unsupported regular-expression construct"),
    UNSUPPORTED_FLAG("unsupported regular-expression flag"),
    PATTERN_TOO_LARGE("pattern exceeds the supported parser limit");

    private final String description;

    RegexpParseErrorCode(String description)
    {
        this.description = description;
    }

    String description()
    {
        return description;
    }
}
