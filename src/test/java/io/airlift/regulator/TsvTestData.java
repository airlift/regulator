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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class TsvTestData
{
    private TsvTestData() {}

    static List<Row> load(String resource, int expectedColumns, int expectedRows)
    {
        if (expectedColumns <= 0) {
            throw new IllegalArgumentException("expectedColumns must be positive");
        }
        if (expectedRows <= 0) {
            throw new IllegalArgumentException("expectedRows must be positive");
        }

        InputStream input = TsvTestData.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("TSV test resource not found: " + resource);
        }

        List<Row> rows = new ArrayList<>(expectedRows);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            int lineNumber = 0;
            for (String line; (line = reader.readLine()) != null; ) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }

                List<String> fields = List.of(line.split("\\t", -1));
                if (fields.size() != expectedColumns) {
                    throw new IllegalStateException("%s:%s: expected %s columns, found %s"
                            .formatted(resource, lineNumber, expectedColumns, fields.size()));
                }
                rows.add(new Row(resource, lineNumber, fields));
            }
        }
        catch (IOException e) {
            throw new IllegalStateException("Failed to read TSV test resource: " + resource, e);
        }

        if (rows.size() != expectedRows) {
            throw new IllegalStateException("%s: expected %s data rows, found %s"
                    .formatted(resource, expectedRows, rows.size()));
        }
        return List.copyOf(rows);
    }

    record Row(String resource, int lineNumber, List<String> fields)
    {
        Row
        {
            fields = List.copyOf(fields);
        }

        String field(int index)
        {
            return fields.get(index);
        }
    }
}
