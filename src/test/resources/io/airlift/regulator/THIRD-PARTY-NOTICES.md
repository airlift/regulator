# Third-party notices

Some resources in this directory hold data taken from other projects. This
file lists them, except the RE2 and Rust `regex` test fixtures, which the
repository's `LICENSE-re2.txt` and `LICENSE-rust-regex.txt` cover, and the data
of subdirectories that carry their own `README.md`.

## elastic/examples Apache log lines

The `source` values of the `googlebotLine` rows of
`everyday-trino-workloads.tsv` are lines 24 to 31 of
`Common Data Formats/apache_logs/apache_logs` in
[elastic/examples](https://github.com/elastic/examples) at commit
`6d86454ebb7a850bcd7e80abe86fe683370018a6`, excerpted unchanged, one line per
row. They are used under the Apache License 2.0, copied unchanged from that
commit in `LICENSE-elastic-examples.txt`. elastic/examples has no `NOTICE`
file at that commit.

## ClickBench URL pattern

The `clickbenchHost` pattern of `specialized-trino-workloads.tsv`,
`^https?://(?:www\.)?([^/]+)/.*$`, is the regular expression of query 29 in
`clickhouse/queries.sql` of
[ClickBench](https://github.com/ClickHouse/ClickBench), whose repository is
licensed under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
Its inputs are written for this project.
