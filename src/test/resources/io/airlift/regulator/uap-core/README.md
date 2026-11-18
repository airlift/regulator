# uap-core rules and test user agents

These files are derived from
[ua-parser/uap-core](https://github.com/ua-parser/uap-core) at commit
`73e7340c3ed8055051607b296bf46ead7aa5f19e`, under the Apache License 2.0.
`LICENSE` is uap-core's license notice, unchanged; the full terms are the
repository's root `LICENSE`. uap-core has no `NOTICE` file at that commit.

The other files were changed from the original: `user-agent-parsers.jsonl.gzip`
and `os-parsers.jsonl.gzip` hold the `user_agent_parsers` and `os_parsers`
sections of `regexes.yaml`, and `test-ua.jsonl.gzip` and `test-os.jsonl.gzip`
the cases of `tests/test_ua.yaml` and `tests/test_os.yaml`, converted from YAML
to gzip JSON lines with every value unchanged. The device rules and tests are
left out. `tools/re2-benchmark/manifests/provision-uap-core.py` regenerates
them, and `tools/re2-benchmark/manifests/uap-core-corpus.tsv` pins the sources
and the generated resources.
