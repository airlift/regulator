# Regulator

Regulator is a high-performance regular-expression engine for UTF-8
[Slice](https://github.com/airlift/slice) data. It matches in linear time with
bounded memory, making it a good fit for data-processing systems that compile
patterns once and apply them repeatedly.

Regulator provides separate compilers for:

- RE2 syntax
- Trino regular-expression syntax
- the regular subset of Java `Pattern`
- Trino SQL `LIKE` patterns

The three regex compilers share Regulator's RE2-based matching engines. SQL
`LIKE` has its own parser, planner, and wildcard matcher. It shares specialized
literal matchers with the regex engines where the languages behave the same way.

## Dependency

Regulator is published as `io.airlift:regulator` and requires Java 25 or newer.
Releases are tested on Java 25 and the current feature release used by Trino,
including short-term-support releases.

```xml
<dependency>
    <groupId>io.airlift</groupId>
    <artifactId>regulator</artifactId>
    <version>${regulator.version}</version>
</dependency>
```

Slice is Regulator's only runtime dependency.

## Usage

Patterns and inputs are `Slice` values. Match positions are byte offsets within
the input Slice, and captures are zero-copy views of it. If your data starts as
Java strings, convert it explicitly. UTF-8 encoding and decoding can allocate
and scan the entire value.

```java
import io.airlift.regulator.Re2;
import io.airlift.regulator.Re2Matcher;
import io.airlift.slice.Slice;

import static io.airlift.slice.Slices.utf8Slice;

Re2 re2 = Re2.compile(utf8Slice("(?<key>[a-z]+)=(?<value>[0-9]+)"));
Slice input = utf8Slice("user=alice count=42");

Re2Matcher matcher = re2.matcher(input);
while (matcher.find()) {
    Slice key = matcher.group("key");
    Slice value = matcher.group("value");
}
```

Share compiled patterns across threads, but give each thread its own matcher.
A `Re2Matcher` keeps mutable state and reuses its matching workspace.

Use the compiler matching the pattern's language:

```java
Slice patternBytes = utf8Slice("[a-z]+");
Re2 re2 = Re2.compile(patternBytes);
TrinoRegexp trinoRegexp = TrinoRegexp.compile(patternBytes);
JavaRegexp javaRegexp = JavaRegexp.compile(patternBytes);
TrinoLikePattern likePattern = TrinoLikePattern.compile(patternBytes);
```

The same text can compile in two languages and mean different things.

## Runtime acceleration

Regulator works without special JVM flags. These optional flags enable faster
paths without changing matching behavior:

```text
--add-modules jdk.incubator.vector
--enable-native-access=ALL-UNNAMED
```

The Vector API lets Regulator scan multiple input bytes at a time when looking
for candidate matches. Native access lets eligible DFA searches use a small
native memory allocation for the DFA transition table.

This is native memory, not native code or a JNI library. It is entirely internal;
the API never exposes native pointers. Patterns, inputs, captures, and results
remain Java and Slice data. Without these flags, Regulator uses scalar scanners
and the pure-Java DFA.

The DFA is sensitive to even one extra instruction per transition. Native memory
avoids following Java object references for table rows and decoding compressed
references, also known as compressed oops. In the current C9g measurements, the
pure-Java route is about 7% slower for the median Trino operation. Many
reused-pattern operations and all LIKE-only routes are effectively unchanged.
Most applications should use the pure-Java default. Native access is worth
considering when regex execution is a substantial part of the workload.

## Documentation

- [Documentation index](docs/README.md)
- [API guide](docs/reference/REGULATOR_API.md)
- [Pattern languages](docs/integrations/REGEXP_LANGUAGES.md)
- [RE2 language](docs/reference/languages/RE2.md)
- [Trino regular-expression language](docs/reference/languages/TRINO_REGEXP.md)
- [Java regular-expression language](docs/reference/languages/JAVA_REGEXP.md)
- [Trino SQL LIKE language](docs/reference/languages/TRINO_LIKE.md)
- [Unsupported features](docs/reference/languages/UNSUPPORTED_FEATURES.md)

## Building

```bash
./mvnw clean install
```

## License

Regulator is licensed under the [Apache License 2.0](LICENSE). It includes a
Java port of RE2; the required RE2 copyright and BSD license notice are provided
in [LICENSE-re2.txt](LICENSE-re2.txt).
