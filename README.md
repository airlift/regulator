# Regulator

Regulator is a high-performance regular expression engine for UTF-8
[Slice](https://github.com/airlift/slice) data. It provides predictable
linear-time matching and is designed for data-processing systems that compile
patterns once and apply them repeatedly.

The implementation is being prepared for its initial public release.

## Building

Regulator requires Java 25.

```bash
./mvnw clean install
```

## License

Regulator is licensed under the [Apache License 2.0](LICENSE).
