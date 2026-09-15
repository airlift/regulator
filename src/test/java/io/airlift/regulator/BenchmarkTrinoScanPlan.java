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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.airlift.regulator.Re2BenchmarkRunner.buildOptions;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.slice.Slices.utf8Slice;

/**
 * Public Trino operations over changing inputs and shared compiled plans. No compilation cache is involved.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class BenchmarkTrinoScanPlan
{
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({
                "URL",
                "URL_SHORT",
                "URL_128",
                "URL_4096",
                "ANCHORED_PREFIX",
                "ANCHORED_QUERY",
                "ANCHORED_HOST",
                "ANCHORED_HOST_4096",
                "ANCHORED_WWW_HOST",
                "UNANCHORED_URL",
                "UNANCHORED_END",
                "UNANCHORED_RUN",
                "UNANCHORED_RUN_LEADING",
                "UNANCHORED_SET",
                "UNANCHORED_MIXED",
                "UNANCHORED_TAIL_4096",
                "LONG_UNANCHORED_RUN",
                "FOLDED_ANCHORED",
                "FOLDED_OPTIONAL",
                "FOLDED_UNANCHORED",
                "FOLDED_RUN",
                "FOLDED_MIXED",
                "LITERAL_REPEATS",
                "LITERAL_CONTROLS",
                "DEEP_REJECTIONS",
                "MIXED",
                "LONG_PATH",
                "LONG_HOST",
                "FAMILY",
                "DIVERSE",
                "FALLBACK",
                "SETS",
                "BOUNDED",
                "LONG_SET",
                "LONG_ASCII",
                "SET_MIXED",
                "OPTIONALS",
                "OPTIONAL_RETRY",
                "OPTIONAL_MIXED",
                "LONG_OPTIONAL",
                "RETRY_64",
                "RETRY_127",
                "RETRY_128",
                "RETRY_256",
                "LONG_RETRY",
                "LOWERED_FALLBACK",
        })
        public String workload;

        @Param({"true", "false"})
        public boolean dotAll;

        Slice[] expressions;
        private TrinoRegexp[] patterns;
        private TrinoRegexpMatcher[] captures;
        private TrinoRegexpMatcher[] boundaries;
        private int[] extractionGroups;
        private Slice[] replacements;
        Slice[] inputs;
        private int cursor;
        private int patternIndex;

        @Setup
        public void setup()
        {
            int retryLength = switch (workload) {
                case "RETRY_64" -> 64;
                case "RETRY_127" -> 127;
                case "RETRY_128" -> 128;
                case "RETRY_256" -> 256;
                case "LONG_RETRY" -> 4096;
                default -> 0;
            };
            int count = switch (workload) {
                case "FAMILY", "DIVERSE", "SET_MIXED", "OPTIONAL_MIXED", "UNANCHORED_MIXED", "FOLDED_MIXED" -> 12;
                case "LITERAL_CONTROLS" -> 2;
                case "DEEP_REJECTIONS", "UNANCHORED_RUN_LEADING" -> 3;
                default -> 1;
            };
            expressions = new Slice[count];
            patterns = new TrinoRegexp[count];
            captures = new TrinoRegexpMatcher[count];
            boundaries = new TrinoRegexpMatcher[count];
            extractionGroups = new int[count];
            replacements = new Slice[count];
            inputs = new Slice[504];
            for (int index = 0; index < count; index++) {
                String expression = "^https?://(?:www\\.)?([^/]+)/.*$";
                if (workload.equals("ANCHORED_PREFIX")) {
                    expression = "^https?://[^/]+/";
                }
                else if (workload.equals("ANCHORED_QUERY")) {
                    expression = "^https?://[^/]+/[^?]*\\?";
                }
                else if (workload.equals("ANCHORED_HOST") || workload.equals("ANCHORED_HOST_4096")) {
                    expression = "^https?://([^/]+)";
                }
                else if (workload.equals("ANCHORED_WWW_HOST")) {
                    expression = "^https?://(?:www\\.)?([^/]+)/";
                }
                else if (workload.equals("UNANCHORED_URL")) {
                    expression = "https?://(?:www\\.)?([^/]+)/";
                }
                else if (workload.equals("UNANCHORED_END")) {
                    expression = "https?://(?:www\\.)?([^/]+)/.*$";
                }
                else if (workload.equals("UNANCHORED_RUN") || workload.equals("LONG_UNANCHORED_RUN")) {
                    expression = "([0-9]+)a";
                }
                else if (workload.equals("UNANCHORED_RUN_LEADING")) {
                    expression = switch (index) {
                        case 0 -> "(\\d+)zz";
                        case 1 -> "([0-9]+)a";
                        default -> "(\\w+)@(\\w+)";
                    };
                }
                else if (workload.equals("UNANCHORED_SET")) {
                    expression = "[?&]([^=]+)=";
                }
                else if (workload.equals("UNANCHORED_MIXED")) {
                    expression = "svc" + index + "://(?:www\\.)?([^/]+)/";
                }
                else if (workload.equals("UNANCHORED_TAIL_4096")) {
                    expression = "abc(.*)";
                }
                else if (workload.equals("FOLDED_ANCHORED")) {
                    expression = "(?i)^ABC([a-h]+):DEF$";
                }
                else if (workload.equals("FOLDED_OPTIONAL")) {
                    expression = "(?i)^colou?r:([a-h]+)$";
                }
                else if (workload.equals("FOLDED_UNANCHORED")) {
                    expression = "(?i)content-type:([^;]+);";
                }
                else if (workload.equals("FOLDED_RUN")) {
                    expression = "(?i)^([a-h]+):$";
                }
                else if (workload.equals("FOLDED_MIXED")) {
                    expression = "(?i)" + switch (index % 4) {
                        case 0 -> "^web" + index + ":([a-h]+);$";
                        case 1 -> "header" + index + ":([^;]+);";
                        case 2 -> "^colou?r" + index + ":([a-h]+)$";
                        default -> "^([a-h]+):END" + index + "$";
                    };
                }
                else if (workload.equals("LITERAL_REPEATS")) {
                    expression = "^(?:ab){2,4}([^/]+)/$";
                }
                else if (workload.equals("LITERAL_CONTROLS")) {
                    expression = index == 0 ? "^foo" : "^foo$";
                }
                else if (workload.equals("DEEP_REJECTIONS")) {
                    // Each pattern begins with a supported prefix and is rejected only by a later
                    // construct: a folded K, an over-limit literal repeat, and a run whose
                    // continuation overlaps the following literal.
                    expression = switch (index) {
                        case 0 -> "(?i)^colou?r:([a-h]+);K$";
                        case 1 -> "^https?://([^/]+)/(?:ab){1,6}$";
                        default -> "^https?://([^/]+)/([a-z]+)abc$";
                    };
                }
                else if (workload.equals("FAMILY")) {
                    expression = "^svc" + index + "://(?:www\\.)?([^/]+)/.*$";
                }
                else if (workload.equals("DIVERSE")) {
                    expression = switch (index % 4) {
                        case 0 -> "^svc" + index + ":(?:www\\.)?([^/]+)/.*$";
                        case 1 -> "^svc" + index + ":([^:]*)::(?:x)?([^|]+)\\|.*$";
                        case 2 -> "^svc" + index + ":(?:aa)?(?:bb)?([^,]+),end$";
                        default -> "^svc" + index + ":([^/]+)/([^:]*)::$";
                    };
                }
                else if (workload.equals("SETS") || workload.equals("LONG_SET") || workload.equals("LONG_ASCII")) {
                    expression = "^([^/:?#]+):([a-z0-9_]+);$";
                }
                else if (workload.equals("BOUNDED")) {
                    expression = "^([A-Z]{2,4}):([^/:]{2,8})/[0-9]{2}$";
                }
                else if (workload.equals("SET_MIXED")) {
                    expression = "^svc" + index + ":" + switch (index % 4) {
                        case 0 -> "(?:www\\.)?([^/]+)/.*$";
                        case 1 -> "([^/:?#]+):([a-z0-9_]+);$";
                        case 2 -> "([A-Z]{2,4}):([^/:]{2,8})/[0-9]{2}$";
                        default -> "([ab]+):$";
                    };
                }
                else if (workload.equals("OPTIONALS") || workload.equals("LONG_OPTIONAL")) {
                    expression = "^https?://(www\\.)?([^/:]+)(?::([0-9]{1,5}))?/.*$";
                }
                else if (workload.equals("OPTIONAL_RETRY") || retryLength > 0) {
                    expression = "^((a)?(b)?(c)?(d)?)abcd([^/]+)/$";
                }
                else if (workload.equals("OPTIONAL_MIXED")) {
                    expression = "^svc" + index + ":" + switch (index % 4) {
                        case 0 -> "(?:www\\.)?([^/]+)/.*$";
                        case 1 -> "(www\\.)?([^/:]+)(?::([0-9]{1,5}))?/.*$";
                        case 2 -> "(?:(?<key>[a-z]+):(?<value>[^/]+))?/end$";
                        default -> "((a)?(b)?(c)?(d)?)abcd([^/]+)/$";
                    };
                }
                else if (workload.equals("FALLBACK") || workload.equals("LOWERED_FALLBACK")) {
                    expression = "([a-z]+|x)[0-9]+" + (workload.equals("LOWERED_FALLBACK") ? "$" : "");
                }
                expressions[index] = utf8Slice((dotAll ? "(?s)" : "") + expression);
                patterns[index] = TrinoRegexp.compile(expressions[index]);
                captures[index] = patterns[index].matcher(EMPTY_SLICE);
                boundaries[index] = patterns[index].matcher(EMPTY_SLICE, 0);
                extractionGroups[index] = patterns[index].capturingGroupCount() == 0 ? 0 : 1;
                replacements[index] = utf8Slice("$" + extractionGroups[index]);
            }
            for (int row = 0; row < inputs.length; row++) {
                int plan = row % count;
                String host = "host" + row + ".example.com";
                String source = (row % 2 == 0 ? "https://" : "http://") + (row % 3 == 0 ? "www." : "") + host + "/path?q=" + row;
                if (workload.startsWith("URL_")) {
                    int targetLength = switch (workload) {
                        case "URL_SHORT" -> 48;
                        case "URL_128" -> 128;
                        case "URL_4096" -> 4096;
                        default -> throw new IllegalArgumentException("unknown URL control: " + workload);
                    };
                    source += "x".repeat(targetLength - source.length());
                }
                else if (workload.equals("LITERAL_CONTROLS")) {
                    source = switch ((row / count) % 4) {
                        case 0 -> "foo";
                        case 1 -> plan == 0 ? "foobar" : "foo\n";
                        case 2 -> "xfoo";
                        default -> plan == 0 ? "" : "foobar";
                    };
                }
                else if (workload.equals("DEEP_REJECTIONS")) {
                    source = switch (plan) {
                        case 0 -> switch ((row / count) % 4) {
                            case 0 -> "colour:bag;k";
                            case 1 -> "COLOR:ABC;K";
                            case 2 -> "color:bag;\u212A";
                            default -> "color:bag;x";
                        };
                        case 1 -> "https://" + host + "/" + switch ((row / count) % 4) {
                            case 0 -> "ab";
                            case 1 -> "ab".repeat(6);
                            case 2 -> "ab".repeat(7);
                            default -> "";
                        };
                        default -> "https://" + host + "/" + switch ((row / count) % 4) {
                            case 0 -> "pathabc";
                            case 1 -> "xabcabc";
                            case 2 -> "abc";
                            default -> "PATHabc";
                        };
                    };
                }
                else if (workload.equals("ANCHORED_PREFIX")) {
                    source = switch (row % 8) {
                        case 0 -> "http://" + host + "/path";
                        case 1 -> "https://www." + host + "/path";
                        case 2 -> "https://例え.テスト/道";
                        case 3 -> "https://" + host;
                        case 4 -> "https:///path";
                        case 5 -> "ftp://" + host + "/path";
                        case 6 -> "https://line\nhost/path";
                        default -> "https://" + host + "/?key=value";
                    };
                }
                else if (workload.equals("ANCHORED_QUERY")) {
                    source = switch (row % 8) {
                        case 0 -> "http://" + host + "/path?key=value";
                        case 1 -> "https://www." + host + "/?";
                        case 2 -> "https://例え.テスト/道?名前=値";
                        case 3 -> "https://" + host + "/path";
                        case 4 -> "https://" + host;
                        case 5 -> "ftp://" + host + "/path?key=value";
                        case 6 -> "https://" + host + "/a\nb?key=value";
                        default -> "https:///path?key=value";
                    };
                }
                else if (workload.equals("ANCHORED_HOST") || workload.equals("ANCHORED_WWW_HOST")) {
                    source = switch (row % 8) {
                        case 0 -> "http://" + host + "/path";
                        case 1 -> "https://www." + host + "/path";
                        case 2 -> "https://例え.テスト/道";
                        case 3 -> "https://" + host;
                        case 4 -> "https:///path";
                        case 5 -> "ftp://" + host + "/path";
                        case 6 -> "https://line\nhost/path";
                        default -> "https://www./path";
                    };
                }
                else if (workload.equals("ANCHORED_HOST_4096")) {
                    // A long host with no path separator: the unbounded run can reach the input end.
                    source = (row % 2 == 0 ? "https://" : "http://") + host;
                    source += "x".repeat(4096 - source.length());
                }
                else if (workload.equals("UNANCHORED_TAIL_4096")) {
                    // The trailing run has no end anchor and no newline, so it can reach the input end.
                    source = "x".repeat(row % 32) + "abc" + host;
                    source += "y".repeat(4096 - source.length());
                }
                else if (workload.equals("UNANCHORED_URL") || workload.equals("UNANCHORED_END")) {
                    String prefix = switch (row % 4) {
                        case 0 -> "";
                        case 1 -> "prefix ";
                        case 2 -> "x".repeat(32) + " ";
                        default -> "x".repeat(128) + " ";
                    };
                    source = prefix + switch ((row / 4) % 8) {
                        case 0 -> "http://" + host + "/path";
                        case 1 -> "https://www." + host + "/path";
                        case 2 -> "https://例え.テスト/道";
                        case 3 -> "https://" + host;
                        case 4 -> "https:///path";
                        case 5 -> "ftp://" + host + "/path";
                        case 6 -> "https://line\nhost/path";
                        default -> "https://" + host + "/path\n";
                    };
                }
                else if (workload.equals("UNANCHORED_RUN") || workload.equals("LONG_UNANCHORED_RUN")) {
                    int digits = workload.equals("LONG_UNANCHORED_RUN") ? 4096 : 8 + row % 57;
                    source = (row % 3 == 0 ? "prefix" : "") + "9".repeat(digits) + (row % 2 == 0 ? "a" : "b");
                }
                else if (workload.equals("UNANCHORED_RUN_LEADING")) {
                    // Prose with scattered short digit runs; only one row in eight matches.
                    source = "order " + row + " shipped in " + (row % 7 + 1) + (row % 5 == 0 ? " 箱" : " boxes") + ", 3 days late (ref " + (row * 37 % 1000) + ")";
                    if ((row / count) % 8 == 0) {
                        source += switch (plan) {
                            case 0 -> " 77zz";
                            case 1 -> " 77a";
                            default -> " user@host";
                        };
                    }
                }
                else if (workload.equals("UNANCHORED_SET")) {
                    String prefix = "x".repeat(switch ((row / 8) % 4) {
                        case 0 -> 0;
                        case 1 -> 16;
                        case 2 -> 64;
                        default -> 256;
                    });
                    source = prefix + switch (row % 8) {
                        case 0 -> "?key=value";
                        case 1 -> "path?first=1&second";
                        case 2 -> "&name=value";
                        case 3 -> " &é😀=value suffix";
                        case 4 -> "path-without-query";
                        case 5 -> "?=empty";
                        case 6 -> "？wide=1";
                        default -> "?line\nname=value";
                    };
                }
                else if (workload.equals("UNANCHORED_MIXED")) {
                    source = "prefix" + row + " svc" + plan + "://" + (row % 3 == 0 ? "www." : "") + host + "/path";
                    if ((row / count) % 7 == 0) {
                        source = "prefix" + row + " svc" + plan + "://missing-slash";
                    }
                }
                else if (workload.equals("FOLDED_ANCHORED")) {
                    source = switch (row % 6) {
                        case 0 -> "abcbag:def";
                        case 1 -> "ABCBAG:DEF";
                        case 2 -> "AbCabc:dEf";
                        case 3 -> "abci:def";
                        case 4 -> "xbcabc:def";
                        default -> "abc例:def";
                    };
                }
                else if (workload.equals("FOLDED_OPTIONAL")) {
                    source = switch (row % 6) {
                        case 0 -> "color:bag";
                        case 1 -> "COLOUR:ABC";
                        case 2 -> "CoLoR:deaf";
                        case 3 -> "colour:i";
                        case 4 -> "xcolor:abc";
                        default -> "color:例";
                    };
                }
                else if (workload.equals("FOLDED_UNANCHORED")) {
                    String prefix = "x".repeat((row % 4) * 32);
                    source = prefix + switch ((row / 4) % 6) {
                        case 0 -> "content-type:text/plain;";
                        case 1 -> "CONTENT-TYPE:application/json;";
                        case 2 -> "CoNtEnT-TyPe:例;";
                        case 3 -> "content-type:missing";
                        case 4 -> "content-length:42;";
                        default -> "content-type:;";
                    };
                }
                else if (workload.equals("FOLDED_RUN")) {
                    source = switch (row % 6) {
                        case 0 -> "abc:";
                        case 1 -> "ABCDEFGH:";
                        case 2 -> "BaD:";
                        case 3 -> "i:";
                        case 4 -> "abc";
                        default -> "é:";
                    };
                }
                else if (workload.equals("FOLDED_MIXED")) {
                    source = switch (plan % 4) {
                        case 0 -> (row % 2 == 0 ? "WEB" : "web") + plan + ":BaG;";
                        case 1 -> "prefix" + row + " " + (row % 2 == 0 ? "HEADER" : "header") + plan + ":value;";
                        case 2 -> (row % 2 == 0 ? "COLOUR" : "color") + plan + ":abc";
                        default -> "DeAf:" + (row % 2 == 0 ? "END" : "end") + plan;
                    };
                    if ((row / count) % 7 == 0) {
                        source += "invalid";
                    }
                }
                else if (workload.equals("LITERAL_REPEATS")) {
                    source = switch (row % 6) {
                        case 0 -> "ababhost/";
                        case 1 -> "abababhost/";
                        case 2 -> "ababababhost/";
                        case 3 -> "abhost/";
                        case 4 -> "ababababab/";
                        default -> "abab/";
                    };
                }
                else if (workload.equals("LONG_PATH")) {
                    source += "x".repeat(4096);
                }
                else if (workload.equals("LONG_HOST")) {
                    source = "https://" + "x".repeat(4096) + host + "/path";
                }
                else if (workload.equals("MIXED")) {
                    source = switch (row % 8) {
                        case 0 -> "https://www./path";
                        case 1 -> "https://" + host;
                        case 2 -> "https:///path";
                        case 3 -> "ftp://" + host + "/path";
                        case 4 -> "https://例え.テスト/道";
                        case 5 -> "https://" + host + "/a\nb";
                        case 6 -> "https://" + host + "/path\n";
                        default -> source;
                    };
                }
                else if (workload.equals("FAMILY")) {
                    source = "svc" + plan + "://" + (row % 3 == 0 ? "www." : "") + host + "/path";
                }
                else if (workload.equals("DIVERSE")) {
                    source = "svc" + plan + ":" + switch (plan % 4) {
                        case 0 -> "www." + host + "/path";
                        case 1 -> "key" + row + "::xvalue|path";
                        case 2 -> "aabb" + host + ",end";
                        default -> host + "/value::";
                    };
                }
                else if (workload.equals("SETS") || workload.equals("LONG_SET") || workload.equals("LONG_ASCII")) {
                    source = (workload.equals("LONG_SET") ? "x".repeat(4096) : "") + host + ":value_" + row + (workload.equals("LONG_ASCII") ? "x".repeat(4096) : "") + ";";
                }
                else if (workload.equals("BOUNDED")) {
                    source = switch (row % 5) {
                        case 0 -> "AB:é😀/12";
                        case 1 -> "ABCD:hostname/12";
                        case 2 -> "ABCDE:host/12";
                        case 3 -> "AB:é/12";
                        default -> "ABC:例え😀/12";
                    };
                }
                else if (workload.equals("SET_MIXED")) {
                    source = "svc" + plan + ":" + switch (plan % 4) {
                        case 0 -> "www." + host + "/path";
                        case 1 -> host + ":value_" + row + ";";
                        case 2 -> "AB:é😀/12";
                        default -> "ababba:";
                    };
                    if ((row / count) % 7 == 0) {
                        source += "invalid";
                    }
                }
                else if (workload.equals("OPTIONALS") || workload.equals("LONG_OPTIONAL")) {
                    source = (row % 2 == 0 ? "https://" : "http://") + (row % 3 == 0 ? "www." : "") + host + (row % 4 == 0 ? ":42" : "") + "/path";
                    if (row % 7 == 0) {
                        source = "https://www.host:123456/path";
                    }
                    if (workload.equals("LONG_OPTIONAL")) {
                        source += "x".repeat(4096);
                    }
                }
                else if (workload.equals("OPTIONAL_RETRY") || retryLength > 0) {
                    source = (row % 2 == 0 ? "" : "abcd") + "abcd" + host;
                    if (retryLength > 0) {
                        source += "x".repeat(retryLength - source.length() - 1);
                    }
                    source += "/";
                }
                else if (workload.equals("OPTIONAL_MIXED")) {
                    source = "svc" + plan + ":" + switch (plan % 4) {
                        case 0 -> "www." + host + "/path";
                        case 1 -> (row / count % 2 == 0 ? "www." : "") + host + (row / count % 3 == 0 ? ":42" : "") + "/path";
                        case 2 -> (row / count % 2 == 0 ? "key:value" : "") + "/end";
                        default -> (row / count % 2 == 0 ? "" : "abcd") + "abcd" + host + "/";
                    };
                }
                else if (workload.equals("FALLBACK") || workload.equals("LOWERED_FALLBACK")) {
                    source = "!alpha" + row + (workload.equals("LOWERED_FALLBACK") ? "" : "?");
                }
                inputs[row] = utf8Slice("padding" + source + "outside").slice(7, utf8Slice(source).length());
            }
        }

        private Slice next()
        {
            int row = cursor++;
            if (cursor == inputs.length) {
                cursor = 0;
            }
            patternIndex = row % patterns.length;
            return inputs[row];
        }
    }

    @Benchmark
    public boolean contains(BenchmarkData data)
    {
        Slice input = data.next();
        return data.patterns[data.patternIndex].contains(input);
    }

    @Benchmark
    public long count(BenchmarkData data)
    {
        Slice input = data.next();
        return data.patterns[data.patternIndex].count(input);
    }

    @Benchmark
    public long boundaries(BenchmarkData data)
    {
        Slice input = data.next();
        TrinoRegexpMatcher matcher = data.boundaries[data.patternIndex];
        return matcher.reset(input).find() ? ((long) matcher.start() << 32) | matcher.end() : -1;
    }

    @Benchmark
    public long captures(BenchmarkData data)
    {
        Slice input = data.next();
        TrinoRegexpMatcher matcher = data.captures[data.patternIndex];
        if (!matcher.reset(input).find()) {
            return -1;
        }
        long result = 0;
        for (int group = 0; group <= matcher.groupCount(); group++) {
            result = result * 31 + matcher.start(group);
            result = result * 31 + matcher.end(group);
        }
        return result;
    }

    @Benchmark
    public Slice extract(BenchmarkData data)
    {
        Slice input = data.next();
        return data.patterns[data.patternIndex].extract(input, data.extractionGroups[data.patternIndex]);
    }

    @Benchmark
    public Slice replace(BenchmarkData data)
    {
        Slice input = data.next();
        return data.patterns[data.patternIndex].replace(input, data.replacements[data.patternIndex]);
    }

    @Benchmark
    public TrinoRegexp compile(BenchmarkData data)
    {
        data.next();
        return TrinoRegexp.compile(data.expressions[data.patternIndex]);
    }

    /**
     * Returns the {@code workload} parameter values in declaration order.
     */
    static List<String> workloads()
            throws NoSuchFieldException
    {
        return List.of(BenchmarkData.class.getDeclaredField("workload").getAnnotation(Param.class).value());
    }

    public static void main(String[] args)
            throws Exception
    {
        Options options = buildOptions(BenchmarkTrinoScanPlan.class, args);
        new Runner(options).run();
    }
}
