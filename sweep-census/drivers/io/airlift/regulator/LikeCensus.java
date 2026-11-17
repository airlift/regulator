package io.airlift.regulator;

import censusprobe.Probe;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

// LIKE census: the Regulator side of tools/re2-benchmark/trino-like BenchmarkTrinoLike and
// BenchmarkTrinoLikeTpch (same scenario inputs, copied), run once untimed under the census agent.
// Usage: LikeCensus <scenario|tpch> <name> <compile|matches|singleUse|generated> <repo-root>
public final class LikeCensus
{
    public static void main(String[] args)
            throws Exception
    {
        String kind = args[0];
        String name = args[1];
        String op = args[2];
        Path root = Path.of(args[3]);
        String pattern;
        List<Slice> inputs = new ArrayList<>();
        if (kind.equals("scenario")) {
            Object[] s = scenario(name);
            pattern = (String) s[0];
            inputs.add(Slices.wrappedBuffer((byte[]) s[1]));
        }
        else {
            String[] w = switch (name) {
                case "Q02_TYPE_BRASS" -> new String[] {"part-type", "%BRASS"};
                case "Q09_NAME_GREEN" -> new String[] {"part-name", "%green%"};
                case "Q13_ORDER_COMMENT" -> new String[] {"orders-comment", "%special%requests%"};
                case "Q14_TYPE_PROMO" -> new String[] {"part-type", "PROMO%"};
                case "Q16_TYPE_MEDIUM_POLISHED" -> new String[] {"part-type", "MEDIUM POLISHED%"};
                case "Q16_SUPPLIER_COMPLAINTS" -> new String[] {"supplier-comment", "%Customer%Complaints%"};
                case "Q20_NAME_FOREST" -> new String[] {"part-name", "forest%"};
                default -> throw new IllegalArgumentException(name);
            };
            pattern = w[1];
            byte[] lines;
            try (InputStream in = new GZIPInputStream(Files.newInputStream(root.resolve("src/test/resources/io/airlift/regulator/tpch-like/" + w[0] + ".txt.gzip")))) {
                lines = in.readAllBytes();
            }
            byte[] backing = new byte[16 + lines.length];
            int position = 16;
            int start = 0;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i] == '\n') {
                    int length = i - start;
                    System.arraycopy(lines, start, backing, position, length);
                    inputs.add(Slices.wrappedBuffer(backing, position, length));
                    position += length;
                    start = i + 1;
                }
            }
        }
        Slice slicePattern = Slices.utf8Slice(pattern);
        Probe.reset();
        TrinoLikePattern candidate = TrinoLikePattern.compile(slicePattern);
        TrinoLikePattern generated = null;
        if (op.equals("generated")) {
            generated = TrinoLikePattern.compile(slicePattern, TrinoLikePattern.Options.defaults().setGenerateBytecode(true));
        }
        long warm = 0;
        for (Slice input : inputs) {
            warm += candidate.matches(input) ? 1 : 0;
        }
        System.out.println("P\tagentFailures\t" + Probe.failures);
        Probe.dump("setup", System.out);
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        CensusRun.findPatterns("state.candidate", candidate, System.out, seen, 0);
        if (generated != null) {
            CensusRun.findPatterns("state.generated", generated, System.out, seen, 0);
        }
        int warmup = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        long result = 0;
        for (int pass = 0; pass <= warmup; pass++) {
        Probe.reset();
        result = 0;
        switch (op) {
            case "compile" -> CensusRun.findPatterns("result", TrinoLikePattern.compile(slicePattern), System.out, seen, 0);
            case "matches" -> {
                for (Slice input : inputs) {
                    result += candidate.matches(input) ? 1 : 0;
                }
            }
            case "generated" -> {
                for (Slice input : inputs) {
                    result += generated.matches(input) ? 1 : 0;
                }
            }
            case "singleUse" -> {
                for (Slice input : inputs) {
                    result += TrinoLikePattern.compile(slicePattern).matches(input) ? 1 : 0;
                }
            }
            default -> throw new IllegalArgumentException(op);
        }
        Probe.dump(pass == 0 ? "first" : pass == warmup ? "op" : "warm", pass == 0 || pass == warmup ? System.out : new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        }
        System.out.println("P\tresult\t" + result + " (setup " + warm + ")");
    }

    private static Object[] scenario(String name)
    {
        return switch (name) {
            case "EXACT_MATCH" -> new Object[] {"needle", bytes("needle")};
            case "PREFIX_LARGE" -> new Object[] {"needle%", inject(filled(32_768), bytes("needle"), 0)};
            case "SUFFIX_LARGE" -> new Object[] {"%needle", inject(filled(32_768), bytes("needle"), 32_762)};
            case "CONTAINS_ABSENT" -> new Object[] {"%needle%", filled(32_768)};
            case "CONTAINS_LATE" -> new Object[] {"%needle%", inject(filled(32_768), bytes("needle"), 32_700)};
            case "ORDERED_LATE" -> new Object[] {"%alpha%omega%", inject(inject(filled(32_768), bytes("alpha"), 31_900), bytes("omega"), 32_700)};
            case "ORDERED_DENSE_FALSE" -> new Object[] {"%aab%bba%", denseFalse()};
            case "ANY_ASCII" -> new Object[] {"_", bytes("x")};
            case "ANY_MULTIBYTE" -> new Object[] {"_", bytes("💰")};
            case "MIXED_LATE" -> new Object[] {"%alpha_omega%", inject(filled(32_768), bytes("alpha💰omega"), 32_700)};
            case "MIXED_ABSENT" -> new Object[] {"%alpha_omega%", filled(32_768)};
            case "WILDCARD_CHAIN" -> new Object[] {"%alpha_bravo_charlie%", inject(filled(1_024), bytes("alpha-bravo-charlie"), 900)};
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static byte[] bytes(String value)
    {
        return value.getBytes(UTF_8);
    }

    private static byte[] filled(int length)
    {
        byte[] input = new byte[length];
        Arrays.fill(input, (byte) 'x');
        return input;
    }

    private static byte[] inject(byte[] input, byte[] value, int offset)
    {
        System.arraycopy(value, 0, input, offset, value.length);
        return input;
    }

    private static byte[] denseFalse()
    {
        byte[] input = filled(32_768);
        byte[] candidate = bytes("aabx");
        for (int offset = 0; offset + candidate.length <= input.length; offset += candidate.length) {
            System.arraycopy(candidate, 0, input, offset, candidate.length);
        }
        return input;
    }
}
