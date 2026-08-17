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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static io.airlift.slice.Slices.wrappedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Materializes both public input representations before JMH starts timing. The wire format uses
 * hex so patterns, newlines, empty strings, and supplementary characters survive unchanged.
 */
public record TestingLanguageBenchmarkInputs(Slice pattern, String patternText, List<Source> sources)
{
    public record Source(Slice bytes, String text, long expectedCount) {}

    public static TestingLanguageBenchmarkInputs read(Path path)
            throws IOException
    {
        List<String> lines = Files.readAllLines(path, UTF_8);
        if (lines.size() < 2) {
            throw new IllegalArgumentException("language workload requires a pattern and inputs");
        }
        Slice pattern = wrappedBuffer(HexFormat.of().parseHex(lines.getFirst()));
        List<Source> sources = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) {
                throw new IllegalArgumentException("expected offset, count, and hex input");
            }
            int offset = Integer.parseInt(fields[0]);
            long count = Long.parseLong(fields[1]);
            if (offset < 0 || count < 0) {
                throw new IllegalArgumentException("negative input offset or count");
            }
            byte[] bytes = HexFormat.of().parseHex(fields[2]);
            byte[] storage = new byte[Math.addExact(offset, bytes.length)];
            System.arraycopy(bytes, 0, storage, offset, bytes.length);
            Slice source = wrappedBuffer(storage, offset, bytes.length);
            sources.add(new Source(source, decode(source), count));
        }
        return new TestingLanguageBenchmarkInputs(pattern, decode(pattern), List.copyOf(sources));
    }

    private static String decode(Slice bytes)
            throws CharacterCodingException
    {
        // Replacement decoding would make the JDK benchmark measure a different logical input.
        return UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.getBytes())).toString();
    }
}
