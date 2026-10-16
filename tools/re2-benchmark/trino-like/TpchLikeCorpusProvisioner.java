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
import io.trino.tpch.Order;
import io.trino.tpch.OrderGenerator;
import io.trino.tpch.Part;
import io.trino.tpch.PartGenerator;
import io.trino.tpch.Supplier;
import io.trino.tpch.SupplierGenerator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class TpchLikeCorpusProvisioner
{
    private static final List<String> HEADER = List.of(
            "column", "generator", "license", "scale_factor", "table", "field",
            "first_row", "row_count", "resource_path", "uncompressed_bytes", "sha256");

    private TpchLikeCorpusProvisioner() {}

    public static void main(String[] args)
            throws Exception
    {
        boolean check = args.length == 3 && args[2].equals("--check");
        if (args.length != 2 && !check) {
            throw new IllegalArgumentException("usage: TpchLikeCorpusProvisioner <repository-root> <manifest.tsv> [--check]");
        }
        Path root = Path.of(args[0]);
        List<String> lines = Files.readAllLines(Path.of(args[1]), UTF_8);
        if (!Arrays.asList(lines.getFirst().split("\t", -1)).equals(HEADER)) {
            throw new IllegalArgumentException("unexpected TPC-H LIKE corpus manifest header: " + lines.getFirst());
        }

        int resources = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split("\t", -1);
            if (row.length != HEADER.size()) {
                throw new IllegalArgumentException("invalid TPC-H LIKE corpus manifest row: " + line);
            }
            if (!row[1].equals("io.trino.tpch:tpch:1.4")) {
                throw new IllegalArgumentException("unsupported TPC-H generator for " + row[0] + ": " + row[1]);
            }
            byte[] source = generate(
                    Double.parseDouble(row[3]),
                    row[4],
                    row[5],
                    Long.parseLong(row[6]),
                    Integer.parseInt(row[7]));
            String actualSha256 = sha256(source);
            if (source.length != Long.parseLong(row[9]) || !actualSha256.equals(row[10])) {
                throw new IllegalStateException("generated TPC-H column differs from manifest: %s has %s bytes with SHA-256 %s"
                        .formatted(row[0], source.length, actualSha256));
            }

            Path resource = root.resolve(row[8]);
            if (check) {
                if (!Files.isRegularFile(resource)) {
                    throw new IllegalStateException("TPC-H LIKE corpus resource does not exist: " + resource);
                }
                if (!Arrays.equals(gunzip(Files.readAllBytes(resource)), source)) {
                    throw new IllegalStateException("TPC-H LIKE corpus resource differs from the generator: " + row[0]);
                }
            }
            else {
                Files.createDirectories(resource.getParent());
                Files.write(resource, gzip(source));
            }
            resources++;
        }
        String action = check ? "verified" : "provisioned";
        System.out.println(action + " " + resources + " TPC-H LIKE corpus resources");
    }

    private static byte[] generate(double scaleFactor, String table, String field, long firstRow, int rowCount)
    {
        return switch (table + "." + field) {
            case "part.name" -> join(new PartGenerator(scaleFactor, 1, 1), Part::rowNumber, Part::name, firstRow, rowCount);
            case "part.type" -> join(new PartGenerator(scaleFactor, 1, 1), Part::rowNumber, Part::type, firstRow, rowCount);
            case "orders.comment" -> join(new OrderGenerator(scaleFactor, 1, 1), Order::rowNumber, Order::comment, firstRow, rowCount);
            case "supplier.comment" -> join(new SupplierGenerator(scaleFactor, 1, 1), Supplier::rowNumber, Supplier::comment, firstRow, rowCount);
            default -> throw new IllegalArgumentException("unsupported TPC-H column: " + table + "." + field);
        };
    }

    private static <T> byte[] join(Iterable<T> rows, ToLongFunction<T> rowNumber, Function<T, String> field, long firstRow, int rowCount)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int written = 0;
        for (T row : rows) {
            // Generator row numbers are one-based, like the manifest's first row.
            if (rowNumber.applyAsLong(row) < firstRow) {
                continue;
            }
            String value = field.apply(row);
            if (value.indexOf('\n') >= 0) {
                throw new IllegalStateException("TPC-H value contains a line feed: " + value);
            }
            output.writeBytes(value.getBytes(UTF_8));
            output.write('\n');
            written++;
            if (written == rowCount) {
                return output.toByteArray();
            }
        }
        throw new IllegalArgumentException("TPC-H table has fewer than %s rows from row %s".formatted(rowCount, firstRow));
    }

    private static byte[] gzip(byte[] data)
            throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (OutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(data);
        }
        return output.toByteArray();
    }

    private static byte[] gunzip(byte[] data)
            throws IOException
    {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] data)
            throws NoSuchAlgorithmException
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
