# TeaVM pattern parsing check

After installing the current Regulator artifact with `./mvnw install`, run:

```bash
tools/teavm/verify.sh
```

This compiles the public `RustRegexp.parse` API to JavaScript and Wasm GC
with TeaVM and runs both outputs in Node. It exercises Unicode properties and case
folding, capture metadata, syntax options, nonzero Slice offsets, copying,
and UTF-8 error spans. The check registers Regulator's optional TeaVM support
explicitly.

`io.airlift.regulator.teavm.TeaVMParsingSupport` supplies a byte-array-backed
Slice substitution for the parser's reachable operations and includes its
pinned Unicode resource through TeaVM's extension SPI. To enable it, add these
files to your application's `src/main/resources/META-INF/services` directory:

- `org.teavm.extension.spi.substitution.SubstitutionPolicy`
- `org.teavm.extension.spi.resources.ResourcesPolicy`

Each file contains `io.airlift.regulator.teavm.TeaVMParsingSupport`. Regulator
does not register these providers automatically.

Missing Slice operations fail dependency checking instead of falling back to
JVM-only code. Matching and full compilation remain JVM APIs;
this check deliberately reaches only parsing and capture metadata.
