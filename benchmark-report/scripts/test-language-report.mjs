import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { readData } from "./read-data.mjs";
import { loadReportModule } from "./load-report-module.mjs";
import { compactReport } from "./page-data.mjs";

const { report: exports, context } = await loadReportModule();
const { sorted, duration, comparisonText, tone, comparisonIssue, downloadData, displayResult, outcomeLabel, lifecycleRows, workloadSection, workloadCommentary, LanguageReport, RowEvidence } = exports;
const detailsHtml = (row, reused, extra = {}) => renderToStaticMarkup(React.createElement(RowEvidence, {
  row: { ...row, measurementWarnings: row.result.warnings }, reused, comparator: 'native RE2', ...extra,
}));

const input = [{ id: "µs", ns: 1250 }, { id: "ns", ns: 950 }, { id: "timeout", ns: null }, { id: "ms", ns: 2000000 }];
assert.equal(sorted(input, { key: "ns", descending: false }, (row, key) => row[key]).map(row => row.id).join(","), "ns,µs,ms,timeout");
assert.equal(sorted(input, { key: "ns", descending: true }, (row, key) => row[key]).map(row => row.id).join(","), "ms,µs,ns,timeout");
assert.equal(duration(1250, true), "+1.25 µs");
assert.equal(duration(-0.25, true), "−0.25 ns");
assert.equal(comparisonText({ state: "compared", ratio: 1.01 }), "< 2% difference");
assert.equal(comparisonText({ state: "compared", ratio: .99 }), "< 2% difference");
assert.equal(comparisonText({ state: "compared", ratio: 1 }), "< 2% difference");
assert.equal(comparisonText({ state: "compared", ratio: 1.02 }), "2.0% slower");
assert.equal(comparisonText({ state: "compared", ratio: .98 }), "2.0% faster");
assert.equal(comparisonText({ state: "compared", ratio: 1.07 }), "7.0% slower");
assert.equal(comparisonText({ state: "compared", ratio: 1.8, deltaNs: 1.5 }), "1.80× slower");
assert.equal(comparisonText({ state: "did-not-finish" }), "did not finish");
for (const [ratio, expected] of [[.5, 'win'], [.98, 'win'], [.99, 'tie'], [1, 'tie'], [1.01, 'tie'], [1.02, 'loss'], [2, 'loss']]) {
  for (const deltaNs of [-100, -1, 0, 1, 100]) {
    assert.equal(tone({ state: 'compared', ratio, deltaNs, warnings: [] }), expected);
  }
}
assert.equal(tone({ state: 'compared', ratio: 2, warnings: ['timing variable'] }), 'na');
assert.equal(tone({ state: 'not-compatible' }), 'na');
assert.equal(tone({ state: 'did-not-finish' }), 'timeout');

const result = { state: "compared", candidateNs: 100, comparatorNs: 20, ratio: 5,
  minimumRatio: 4.9, maximumRatio: 5.1, deltaNs: 80, deltaNsPerByte: null, hosts: [], warnings: [] };
const row = { id: "single", caseId: "example", name: "Example", operation: "singleUseContains",
  model: "contains", population: "ordinary-scalar", family: "literal", language: "re2", platform: "c9g", memoryMode: "native", source: "language", inputBytes: 20, result };
const reused = { ...row, id: "reused", operation: "reusedContains", result: { ...result, deltaNs: -10, ratio: .5, candidateNs: 10, comparatorNs: 20 } };
assert.equal(lifecycleRows([row, reused])[0].breakEven, 9);
const earlyLead = lifecycleRows([{ ...row, result: { ...result, deltaNs: -10 } }, { ...reused, result }])[0];
assert.equal(earlyLead.breakEven, 1);
assert.match(earlyLead.reason, /can reverse/);
assert.equal(lifecycleRows([{ ...row, result: { ...result, warnings: ["variable"] } }, reused])[0].breakEven, null);
assert.throws(() => lifecycleRows([row]), /Missing reused lifecycle partner/);

const data = { schemaVersion: 2, sources: { currentLabel: "Test capture", currentCandidate: "abcdef123456", jdk: "JDK 25" },
  platforms: { c9g: "C9g", c8g: "C8g", c8i: "Intel" }, memoryModes: { native: "Native", safe: "Pure Java" },
  languages: { re2: { label: "RE2", comparator: "native RE2" }, java: { label: "Java regex", comparator: "JDK Pattern" }, trino: { label: "Trino regex", comparator: "Joni" }, like: { label: "LIKE", comparator: "Trino SQL LIKE" } },
  rows: [row, reused, { ...row, id: "compile", operation: "compile" }], methodology: ["Test method"], provenance: { candidate: "test" } };

const releaseData = { ...data, platforms: { r9g: "R9g", r8g: "R8g", r8i: "R8i" },
  rows: data.rows.map(row => ({ ...row, platform: "r9g" })) };
assert.deepEqual(Array.from(exports.cpuOrder(releaseData)), ["r9g", "r8g", "r8i"]);
const releaseHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: releaseData }));
assert.match(releaseHtml, /R9g/);
assert.match(releaseHtml, /R8i/);
assert.doesNotMatch(releaseHtml, />C9g</);
const publishedReleaseHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: {
  ...releaseData, sources: { ...releaseData.sources, currentLabel: "Regulator 1.0 results", releaseVersion: "1.0" },
} }));
assert.match(publishedReleaseHtml, /<h1 class="release-title">Regulator 1.0 benchmarks<\/h1>/);
assert.match(publishedReleaseHtml, /class="source-line">JDK 25/);
assert.doesNotMatch(publishedReleaseHtml, /snapshot-badge|Regulator 1.0 results/);
assert.doesNotMatch(publishedReleaseHtml, /Engine abcdef/);
assert.equal(exports.summaryRatios(releaseData).re2.everyday, exports.summaryRatios(data).re2.everyday);

// README columns give each eligible row equal weight and use the same
// classification and equivalent-work checks as the report tables.
const summaryRow = (family, ratio, changes = {}) => ({ ...reused, family,
  result: { ...result, ratio }, ...changes });
const summaryRows = [
  ...Array.from({ length: 10 }, () => summaryRow("many", 0.1)),
  summaryRow("middle", 2), summaryRow("last", 4),
  summaryRow("text", 0.25, { population: "bulk-text", operation: "execute" }),
  summaryRow("text", 0.75, { population: "bulk-text", operation: "execute" }),
];
const assertRatio = (actual, expected) => assert.ok(Math.abs(actual / expected - 1) < 1e-12, `${actual} != ${expected}`);
assertRatio(exports.summaryRatios({ ...data, rows: summaryRows }).re2.everyday, Math.pow(0.1 ** 10 * 2 * 4, 1 / 12));
assertRatio(exports.summaryRatios({ ...data, rows: summaryRows }).re2.textProcessing, Math.sqrt(0.25 * 0.75));
assert.deepEqual(exports.summaryRatios({ ...data, rows: summaryRows.map(row => ({ ...row, family: "one" })) }),
  exports.summaryRatios({ ...data, rows: summaryRows }), "Collection labels do not change the weighting");
const uncertain = summaryRow("variation", 0.5, { result: { ...result, ratio: 0.5,
  warnings: ["Hosts disagree about which engine is faster"] } });
assert.equal(comparisonText(uncertain.result), "no consistent winner");
// Valid variable measurements still contribute to descriptive summaries.
assertRatio(exports.summaryRatios({ ...data, rows: [uncertain] }).re2.everyday, 0.5);
assertRatio(exports.summaryRatios({ ...data, rows: [uncertain, summaryRow("other", 2)] }).re2.everyday, 1);
assertRatio(exports.summaryRatios({ ...data, rows: [1e200, 1e200, 1e-200, 1e-200]
  .map(ratio => summaryRow("range", ratio)) }).re2.everyday, 1);
const excluded = [
  { platform: "c8i" }, { memoryMode: "safe" }, { operation: "singleUseContains" },
  { operation: "compile" }, { population: "diagnostics-and-stress" },
  { population: "bulk-text", caseId: "unicode/codepoints/any-one", operation: "execute" },
  { population: "bulk-text", caseId: "captures/contiguous-letters", operation: "execute" },
  { population: "bulk-text", caseId: "imported/sherlock/repeated-class-negation", operation: "execute" },
  { population: "bulk-text", operation: "execute", model: "count", workContract: "obsolete" },
  { result: { ...result, state: "did-not-finish", ratio: 0.001 } },
  { result: { ...result, state: "not-compatible", ratio: 0.001 } },
  ...[0, -1, NaN, Infinity].map(ratio => ({ result: { ...result, ratio } })),
].map(changes => summaryRow("excluded", 0.001, changes));
assert.deepEqual(exports.summaryRatios({ ...data, rows: [...summaryRows, ...excluded] }),
  exports.summaryRatios({ ...data, rows: summaryRows }));
const reclassified = summaryRow("text", 0.25, { caseId: "reported/i787-keywords/ascii",
  population: "diagnostics-and-stress", operation: "execute" });
assert.equal(exports.summaryRatios({ ...data, rows: [reclassified] }).re2.textProcessing, 0.25);
const likeSummaryRows = [
  summaryRow("LIKE", 0.25, { language: "like", population: "like", operation: "matches", caseId: "trino-like/ANY_ASCII" }),
  summaryRow("LIKE", 0.25, { language: "like", population: "like", operation: "matches", caseId: "trino-like/PREFIX_LARGE" }),
  summaryRow("Optional Trino DFA matcher", 0.75, { language: "like", population: "diagnostics-and-stress", operation: "matches",
    caseId: "trino-like/ANY_ASCII/optimized", comparator: "Trino DFA" }),
  summaryRow("LIKE", 100, { language: "like", population: "like", operation: "matches", caseId: "trino-like/ORDERED_DENSE_FALSE" }),
];
const likeSummary = exports.summaryRatios({ ...data, rows: likeSummaryRows }).like;
assertRatio(likeSummary.everyday, Math.cbrt(0.25 * 0.25 * 0.75));
assert.equal(likeSummary.textProcessing, null);
assert.equal(exports.summaryRatios({ ...data, rows: [] }).java.everyday, null);
const html = renderToStaticMarkup(React.createElement(LanguageReport, { data }));
assert.match(html, /Regex pattern lifecycle/);
assert.doesNotMatch(html, /<h2>Pattern construction/);
assert.ok(downloadData(data).rows.some(entry => entry.operation === 'compile'));
assert.doesNotMatch(html, /row-evidence-body/);
assert.match(detailsHtml(row, reused), /Compile the pattern, then test one input/);
assert.match(detailsHtml(row, reused), /using a warmed compiled pattern/);
assert.doesNotMatch(html, /Single-use measurements|Multi-use measurements/);
assert.match(html, /type="checkbox"/);
assert.match(html, /Pure Java/);
assert.ok(html.indexOf(">C9g<") < html.indexOf(">C8g<"));
assert.match(html, /aria-sort="none"/);
assert.match(html, /\+80.00 ns/);
assert.doesNotMatch(html.match(/<nav.*?<\/nav>/s)[0], /vs |native RE2|JDK Pattern|Joni/);
assert.match(html, /<h2 class="comparison-heading">Regulator vs native RE2<\/h2>/);
assert.match(html, /<p>Regulator receives UTF-8 input in Slice\.<\/p><p>Native RE2 receives the same UTF-8 bytes\.<\/p>/);
assert.doesNotMatch(html, /Execution timings reuse compiled patterns/);
assert.doesNotMatch(html, /report-toolbar|type="search"|Find a workload|Lower time is better|Within 2% is blue/);
assert.doesNotMatch(html, /Measurement method and source identity|Test method/);
assert.equal(downloadData(data).methodology, data.methodology);
assert.equal(downloadData(data).provenance, data.provenance);

const groupingCases = [
  ['captures/contiguous-letters', 'bulk-text', 'diagnostic-only'],
  ['grep/every-line', 'bulk-text', 'diagnostic-only'],
  ['wild/dot-star-capture/rust-src-tools', 'bulk-text', 'diagnostic-only'],
  ['reported/i787-keywords/opt-ascii', 'diagnostics-and-stress', 'diagnostic-only'],
  ['unicode/codepoints/any-one', 'bulk-text', 'synthetic-stress'],
  ['unicode/codepoints/any-all', 'bulk-text', 'synthetic-stress'],
  ['unicode/codepoints/letters-one', 'bulk-text', 'synthetic-stress'],
  ['unicode/codepoints/contiguous-greek', 'bulk-text', 'synthetic-stress'],
  ['unicode/overlapping-words/ascii', 'bulk-text', 'synthetic-stress'],
  ['unicode/overlapping-words/english', 'bulk-text', 'synthetic-stress'],
  ['unicode/overlapping-words/russian', 'bulk-text', 'synthetic-stress'],
  ['reported/i787-keywords/ascii', 'diagnostics-and-stress', 'bulk-text'],
  ['imported/leipzig/certain-long-strings-ending-x', 'bulk-text', 'diagnostics-and-stress'],
  ['imported/sherlock/repeated-class-negation', 'bulk-text', 'diagnostics-and-stress'],
  ['reported/i13-subset-regex/original-ascii', 'diagnostics-and-stress', 'diagnostics-and-stress'],
  ['curated/05-lexer-veryl/single', 'bulk-text', 'bulk-text'],
  ['wild/ruff/noqa', 'bulk-text', 'bulk-text'],
  ['wild/caddy/caddy', 'bulk-text', 'bulk-text'],
  ['wild/bibleref/short', 'bulk-text', 'bulk-text'],
  ['curated/12-dictionary/single', 'bulk-text', 'bulk-text'],
  ['curated/09-aws-keys/full', 'bulk-text', 'bulk-text'],
  ['unicode/word/boundary-any-english', 'bulk-text', 'bulk-text'],
  ['imported/sherlock/holmes-coword-watson', 'bulk-text', 'bulk-text'],
];
const groupingRows = groupingCases.map(([caseId, population]) => ({ ...reused, id: caseId, caseId, name: caseId,
  source: 'fixture', operation: 'execute', population, model: 'count', family: 'reported-regression' }));
for (const [index, [, , expected]] of groupingCases.entries()) {
  for (const state of ['compared', 'not-compatible', 'did-not-finish']) {
    assert.equal(workloadSection({ ...groupingRows[index], result: { ...result, state } }), expected);
  }
}
const groupedData = { ...data, rows: [...data.rows, ...groupingRows,
  { ...reused, id: 'external-compile', name: 'external-compile', operation: 'compile', population: 'compilation' },
  { ...reused, id: 'trino-operation', name: 'trino-operation', population: 'trino-operations' }] };
const groupedHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: groupedData }));
const headings = [...groupedHtml.matchAll(/<h2>(.*?)<\/h2>/g)].map(match => match[1]);
assert.deepEqual(headings, ['Everyday regex operations', 'Regex pattern lifecycle', 'Trino regex operations',
  'Text processing workloads', 'Adversarial and stress workloads']);
const textSection = groupedHtml.split('<h2>Text processing workloads</h2>')[1].split('</section>')[0];
const stressSection = groupedHtml.split('<h2>Adversarial and stress workloads</h2>')[1].split('</section>')[0];
for (const [caseId, , expected] of groupingCases) {
  assert.equal(textSection.includes(caseId), expected === 'bulk-text', caseId);
  assert.equal(stressSection.includes(caseId), ['synthetic-stress', 'diagnostics-and-stress'].includes(expected), caseId);
  if (expected === 'diagnostic-only') assert.ok(!groupedHtml.includes(caseId), caseId);
}
assert.match(stressSection, /<details class="diagnostic-group"><summary>Synthetic stress/);
assert.doesNotMatch(groupedHtml, /Compiler stress|external-compile/);
const groupedDownload = downloadData(groupedData);
assert.equal(groupedDownload.rows.length, groupedData.rows.length);
assert.ok(groupedDownload.rows.every((entry, index) => entry.result === groupedData.rows[index].result
  && entry.population === groupedData.rows[index].population
  && entry.reportSection === workloadSection(groupedData.rows[index])));

// Literal-probe pairs show both comparator ratios and the host-normalized tuned gain.
const probe = (caseId, family, candidateNs, ratio) => ({ ...reused, id: `language-bulk/${caseId}/execute`, caseId, name: caseId,
  operation: 'execute', model: 'count', population: 'literal-probe', family, inputBytes: 1000, workContract: 'public-find-count-v2',
  result: { ...result, candidateNs, comparatorNs: candidateNs / ratio, ratio, minimumRatio: ratio, maximumRatio: ratio,
    deltaNs: candidateNs - candidateNs / ratio, deltaNsPerByte: null } });
const probeRows = [probe('literal-probe/nginx-mozilla', 'uniform', 300, 1.5), probe('literal-probe/nginx-mozilla/tuned', 'sampled-log-lines', 100, .5)];
const [probePair] = exports.literalProbeRows(probeRows);
assert.equal(probePair.distribution, 'Learned from sample logs');
assertRatio(probePair.gain.ratio, 1 / 3);
assert.throws(() => exports.literalProbeRows([probeRows[0]]), /Missing tuned literal-probe partner/);
assert.throws(() => exports.literalProbeRows([probeRows[1]]), /Missing default literal-probe partner/);
assert.equal(exports.variantGain(probeRows[1].result, { ...result, state: 'did-not-finish', reason: 'timeout' }).state, 'did-not-finish');
const probeHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...groupedData, rows: [...groupedData.rows, ...probeRows] } }));
const probeHeadings = [...probeHtml.matchAll(/<h2>(.*?)<\/h2>/g)].map(match => match[1]);
assert.equal(probeHeadings.indexOf('Literal probe distributions'), probeHeadings.indexOf('Text processing workloads') + 1);
const probeSection = probeHtml.split('<h2>Literal probe distributions</h2>')[1].split('</section>')[0];
assert.match(probeSection, /Mozilla\/5.0 · nginx access log/);
assert.match(probeSection, /1.50× slower.*2.00× faster.*3.00× faster/s);
assert.doesNotMatch(probeHtml.split('<h2>Text processing workloads</h2>')[1].split('</section>')[0], /literal-probe/);
assert.deepEqual(exports.summaryRatios({ ...data, rows: [...summaryRows, ...probeRows] }), exports.summaryRatios({ ...data, rows: summaryRows }));
assert.equal(exports.displayedMeasurements(probeRows).length, 2);
// Two ratios that each include parity cannot add up to a clear tuned gain.
const uncertainProbe = (row, ratioInterval) => ({ ...row, result: { ...row.result, uncertainty: { method: 'hierarchical-bootstrap-v1', level: .95, ratioInterval,
  candidateIntervalNs: [row.result.candidateNs * .99, row.result.candidateNs * 1.01], comparatorIntervalNs: [row.result.comparatorNs * .99, row.result.comparatorNs * 1.01],
  forkMeanRangeNs: { candidate: null, comparator: null } } } });
const uncertainProbeRows = [uncertainProbe(probe('literal-probe/nginx-mozilla', 'uniform', 100, 1), [.5, 1.5]),
  uncertainProbe(probe('literal-probe/nginx-mozilla/tuned', 'sampled-log-lines', 50, .5), [.2, 1.2])];
const uncertainProbeHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...groupedData, rows: [...groupedData.rows, ...uncertainProbeRows] } }));
assert.match(uncertainProbeHtml, /divided by default ratio: 0.5000×. Conservative interval 0.1333 to 2.4000×.">no clear difference</);

const partial = { state: 'did-not-finish', reason: 'comparator: execution exceeded 30 s on 2 attempts', warnings: [],
  hosts: [80, 100, 120].map((ns, index) => ({ instanceId: `host-${index}`, replica: index + 1,
    candidate: { state: 'compared', medianNs: ns }, comparator: { state: 'did-not-finish' } })) };
assert.equal(displayResult(partial).candidateNs, 100);
assert.equal(displayResult(partial).comparatorNs, undefined);
assert.equal(displayResult({ ...partial, deltaNs: 10, ratio: .5 }).deltaNs, undefined);
assert.equal(displayResult({ ...partial, deltaNs: 10, ratio: .5 }).ratio, undefined);
assert.equal(outcomeLabel(partial, 'Joni'), 'Joni did not finish');
const reversedPartial = { ...partial, hosts: partial.hosts.map(host => ({ ...host, candidate: host.comparator, comparator: host.candidate })) };
assert.equal(displayResult(reversedPartial).comparatorNs, 100);
assert.equal(displayResult(reversedPartial).candidateNs, undefined);
assert.equal(outcomeLabel(reversedPartial, 'Joni'), 'Regulator did not finish');
const incompatiblePartial = { ...partial, state: 'not-compatible', hosts: partial.hosts.map(host => ({ ...host, comparator: { state: 'not-compatible' } })) };
assert.equal(outcomeLabel(incompatiblePartial, 'Joni'), 'Joni not compatible');
assert.equal(outcomeLabel({ ...incompatiblePartial, hosts: incompatiblePartial.hosts.map(host => ({ ...host, candidate: { state: 'not-compatible' } })) }, 'Joni'), 'Neither engine is compatible');
assert.equal(displayResult({ ...partial, hosts: [partial.hosts[0], reversedPartial.hosts[0]] }).candidateNs, undefined);
assert.equal(displayResult({ ...partial, hosts: [] }).candidateNs, undefined);
const partialHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...data, rows: [{ ...reused, result: partial }] } }));
assert.match(partialHtml, /<td class="measurement">100.00 ns<\/td><td class="measurement"><\/td>/);
assert.match(partialHtml, /native RE2 did not finish/);
assert.match(detailsHtml({ ...reused, result: partial }), /execution exceeded 30 s/);
assert.doesNotMatch(partialHtml, /class="outcome-reason"/);
assert.match(partialHtml, /class="measurement delta"><\/td>/);

context.window = { location: { hash: '#language=like&cpu=c9g&memory=native' } };
const likeRows = ['singleUse', 'matches', 'compile'].map(operation => ({ ...row, id: operation, operation, language: 'like', population: 'like' }));
const likeHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...data, rows: likeRows } }));
assert.match(likeHtml, /LIKE pattern lifecycle/);
assert.doesNotMatch(likeHtml, /<h2>Pattern construction/);
assert.doesNotMatch(likeHtml, /reused match|<small>single use/);
const likeColorHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...data, rows: likeRows.map(entry => ({
  ...entry, result: { ...entry.result, ratio: 1.8, deltaNs: 1.5, warnings: [] },
})) } }));
assert.match(likeColorHtml, /class="scan-result loss"[^>]*>1.80× slower/);
assert.match(likeColorHtml, /class="measurement delta loss">\+1.50 ns/);
assert.doesNotMatch(likeColorHtml, /class="scan-result tie"|class="measurement delta tie"/);
const dfaRows = likeRows.map(entry => ({ ...entry, caseId: 'trino-like/ANY_ASCII', name: 'ANY_ASCII' }));
const enabled = { ...dfaRows[1], id: 'optimized', caseId: 'trino-like/ANY_ASCII/optimized', population: 'diagnostics-and-stress', comparator: 'Trino DFA', result: { ...result, ratio: 0.125 } };
const dfaLifecycle = lifecycleRows([...dfaRows, enabled]);
assert.equal(dfaLifecycle.length, 2);
assert.equal(dfaLifecycle[0].name, 'ANY_ASCII · DFA disabled');
assert.equal(dfaLifecycle[1].name, 'ANY_ASCII · DFA enabled');
assert.equal(dfaLifecycle[1].single, undefined);
assert.equal(dfaLifecycle[1].breakEven, null);
assert.equal(dfaLifecycle[1].reused.result.ratio, 0.125);
assert.equal(workloadSection(enabled), 'like');
assert.equal(workloadSection({ ...enabled, caseId: 'trino-like/EXACT_MATCH/optimized' }), 'diagnostic-only');
const dfaHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...data, rows: [...dfaRows, enabled] } }));
assert.doesNotMatch(dfaHtml, /Adversarial|reused match|<small>single use/);
const enabledHtml = dfaHtml.match(/<tr><td><details[^]*?ANY_ASCII · DFA enabled[^]*?<\/tr>/)[0].split('ANY_ASCII · DFA enabled')[1];
assert.match(enabledHtml, /<td class="measurement"><\/td><td class="measurement delta "><\/td>/);
assert.doesNotMatch(dfaHtml, /break-even/i);
assert.match(detailsHtml(enabled, enabled, { missingSingle: true }), /Single-use timings were not collected/);
const enabledSingle = { ...dfaRows[0], id: 'optimized-single', caseId: enabled.caseId };
const completeDfaLifecycle = lifecycleRows([...dfaRows, enabled, enabledSingle]);
assert.equal(completeDfaLifecycle.length, 2);
assert.equal(completeDfaLifecycle[1].single.id, 'optimized-single');
const expectedLikeOrder = ['PREFIX_LARGE', 'SUFFIX_LARGE', 'EXACT_MATCH', 'CONTAINS_LATE', 'CONTAINS_ABSENT',
  'ORDERED_LATE', 'MIXED_LATE', 'MIXED_ABSENT', 'WILDCARD_CHAIN', 'ANY_ASCII', 'ANY_ASCII/optimized',
  'ANY_MULTIBYTE', 'ANY_MULTIBYTE/optimized', 'ORDERED_DENSE_FALSE'];
const shuffledLikeRows = [...expectedLikeOrder].reverse().flatMap(scenario => ['matches', 'singleUse'].map(operation => ({
  ...likeRows[0], id: `${scenario}/${operation}`, caseId: `trino-like/${scenario}`, name: scenario, operation,
})));
const orderedLike = lifecycleRows(shuffledLikeRows);
assert.equal(orderedLike.map(entry => entry.reused.caseId).join(','), expectedLikeOrder.map(scenario => `trino-like/${scenario}`).join(','));
assert.equal(shuffledLikeRows[0].caseId, 'trino-like/ORDERED_DENSE_FALSE');
const orderedLikeHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...data, rows: shuffledLikeRows } }));
assert.ok(orderedLikeHtml.indexOf('prefix, 6 bytes') < orderedLikeHtml.indexOf('suffix, 6 bytes'));
assert.ok(orderedLikeHtml.indexOf('ANY_MULTIBYTE · DFA enabled') < orderedLikeHtml.indexOf('ordered dense false · stress test'));
assert.equal(sorted(orderedLike, { key: 'name', descending: false }, entry => entry.name)[0].name, 'ANY_ASCII · DFA disabled');
const regexOrder = lifecycleRows(shuffledLikeRows.map(entry => ({ ...entry, language: 'java' })));
assert.equal(regexOrder[0].reused.caseId, 'trino-like/ORDERED_DENSE_FALSE');
delete context.window;

// Each specialized pattern is one row: absolute variant times, gains over the page's
// first measured variant with host speed cancelled, and the last variant against the comparator.
// Rows are shaped as the baseline import emits them, each variant with gain warnings over every earlier one.
const specializedSuites = { trino: ['trino-specialized-patterns', 'trino-specialized'], like: ['trino-like-specialized', 'trino-like-specialized'], re2: ['re2-specialized-patterns', 're2-specialized'] };
const specialized = (language, workload, operation, variant, candidateNs, ratio, ratioInterval, gains, candidateUncertainty = .01) => {
  const [suite, prefix] = specializedSuites[language];
  return { ...reused, id: `baseline/${suite}/${workload}${language === 'like' ? '' : `/${operation}`}${variant}`, caseId: `${prefix}/${workload}${variant}`, name: workload,
    operation, model: operation, language, population: 'specialized-patterns', family: 'Specialized engine and compiler', source: 'baseline', inputBytes: null,
    ...(Object.keys(gains).length ? { variantGainWarnings: gains } : {}),
    result: { ...result, candidateNs, comparatorNs: candidateNs / ratio, ratio, minimumRatio: ratio, maximumRatio: ratio, deltaNs: candidateNs - candidateNs / ratio,
      uncertainty: { method: 'hierarchical-bootstrap-v1', level: .95, ratioInterval,
        candidateIntervalNs: [candidateNs * (1 - candidateUncertainty), candidateNs * (1 + candidateUncertainty)],
        comparatorIntervalNs: [candidateNs / ratio * .99, candidateNs / ratio * 1.01], forkMeanRangeNs: { candidate: null, comparator: null } } } };
};
const specializedVariants = (language, workload, operation, variants) => {
  const rows = [];
  for (const [variant, candidateNs, ratio, ratioInterval, candidateUncertainty] of variants) {
    rows.push(specialized(language, workload, operation, variant, candidateNs, ratio, ratioInterval, Object.fromEntries(rows.map(earlier => [earlier.id, []])), candidateUncertainty));
  }
  return rows;
};
// Compiled code has its own Joni measurement: its gain divides comparator-relative ratios (0.2 / 2), not raw times (50 / 400).
const trinoSpecialized = specializedVariants('trino', 'url-host', 'extract',
  [['/regular-engine', 400, 2, [1.8, 2.2], .2], ['', 200, 1, [.9, 1.1]], ['/generated', 50, .2, [.15, .25], .3]]);
const likeSpecialized = specializedVariants('like', 'Q02_TYPE_BRASS', 'matches', [['', 100, .5, [.4, .6]], ['/generated', 50, .25, [.2, .3]]])
  .map(entry => ({ ...entry, workload: { pattern: '%BRASS', description: 'Matches part types that end in BRASS.', columnRows: 16384,
    inputDescription: 'Each operation scans the first 16,384 rows of TPC-H part.type at scale factor 1.' } }));
assert.throws(() => exports.specializedPatternRows([trinoSpecialized[2]]), /Missing specialized-engine partner/);
assert.equal(exports.displayedMeasurements(trinoSpecialized).length, 3);
// The three variants share one Joni measurement, which the quality summary counts once.
assert.equal(exports.qualityDistribution(trinoSpecialized, 'candidate').count, 3);
assert.equal(exports.qualityDistribution(trinoSpecialized, 'comparator').count, 1);
assert.deepEqual(exports.summaryRatios({ ...data, rows: [...likeSummaryRows, ...likeSpecialized] }).like, likeSummary);
const specializedPage = (language, rows, pageData = data) => {
  context.window = { location: { hash: `#language=${language}&cpu=c9g&memory=native` } };
  let html;
  try {
    html = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...pageData, rows } }));
  } finally {
    delete context.window;
  }
  const section = html.split('<h2>Specialized engine and compiler</h2>')[1]?.split('</section>')[0];
  return { headings: [...html.matchAll(/<h2>(.*?)<\/h2>/g)].map(match => match[1]), section,
    columns: section && [...section.matchAll(/<button type="button" class="sort-button"[^>]*>(.*?)<span/g)].map(match => match[1]) };
};
const regularClause = /the regular engine runs the same patterns without one/;
// A second operation on one workload keeps its own row.
const replaceSpecialized = specializedVariants('trino', 'url-host', 'replace',
  [['/regular-engine', 300, 1, [.9, 1.1]], ['', 300, 1, [.9, 1.1]], ['/generated', 300, 1, [.9, 1.1]]]);
const trinoPage = specializedPage('trino', [...data.rows, ...data.rows.map(entry => ({ ...entry, id: `trino-${entry.id}`, language: 'trino' })), ...trinoSpecialized, ...replaceSpecialized]);
assert.deepEqual(trinoPage.headings, ['Everyday regex operations', 'Specialized engine and compiler', 'Regex pattern lifecycle', 'Measurement notes']);
assert.deepEqual(trinoPage.columns, ['Pattern / operation', 'Joni', 'Regular engine', 'Specialized engine', 'Compiled',
  'Specialized vs regular', 'Compiled vs regular', 'Compiled vs Joni']);
assert.match(trinoPage.section, /url-host<small>extract<\/small>.*200.00 ns.*400.00 ns.*200.00 ns.*50.00 ns.*2.00× faster.*10.00× faster.*5.00× faster/s);
assert.match(trinoPage.section, /url-host<small>replace<\/small>/);
assert.equal(trinoPage.section.split('<tbody>')[1].match(/<tr>/g).length, 2);
assert.match(trinoPage.section, /Compiled ratio divided by regular engine ratio: 0.1000×. Conservative interval 0.0682 to 0.1389×./);
assert.match(trinoPage.section, regularClause);
// Each variant's time carries its own uncertainty marker.
assert.match(trinoPage.section, /400.00 ns<sup><button[^>]*title="Regulator timing uncertainty: 20.0%. Exceeds 5%."/);
assert.match(trinoPage.section, /50.00 ns<sup><button[^>]*title="Regulator timing uncertainty: 30.0%. Exceeds 5%."/);
assert.equal(trinoPage.section.match(/quality-marker/g).length, 2);
assert.equal(specializedPage('re2', [...data.rows, ...trinoSpecialized]).section, undefined);
const likePage = specializedPage('like', compactReport({ ...data, rows: [...likeRows, ...likeSpecialized] }, exports).rows);
assert.deepEqual(likePage.headings, ['Specialized engine and compiler', 'LIKE pattern lifecycle']);
assert.deepEqual(likePage.columns, ['Pattern', 'Trino SQL LIKE', 'Specialized engine', 'Compiled', 'Compiled vs specialized', 'Compiled vs Trino SQL LIKE']);
assert.match(likePage.section, /Compiled ratio divided by specialized engine ratio: 0.5000×. Conservative interval 0.3333 to 0.7500×./);
assert.doesNotMatch(likePage.section, /reused match/);
assert.doesNotMatch(likePage.section, regularClause);
// Each LIKE time scans many column rows, not one input.
assert.match(likePage.section, /Each time is one scan of the first 16,384 rows of a TPC-H column\./);
const likeDetails = renderToStaticMarkup(React.createElement(RowEvidence, { row: likeSpecialized[0], comparator: 'Trino SQL LIKE' }));
assert.match(likeDetails, /Count the rows of one column that match a warmed LIKE pattern\..*first 16,384 rows of TPC-H part.type/s);
assert.doesNotMatch(likeDetails, /one complete input/);
// A language whose catalog rows the import accepts gets the section with its own comparator and variants.
const re2Page = specializedPage('re2', [...data.rows, ...specializedVariants('re2', 'literal-prefix', 'contains', [['', 100, .5, [.4, .6]], ['/generated', 50, .25, [.2, .3]]])]);
assert.deepEqual(re2Page.columns, ['Pattern / operation', 'native RE2', 'Specialized engine', 'Compiled', 'Compiled vs specialized', 'Compiled vs native RE2']);
assert.match(re2Page.section, /literal-prefix<small>contains<\/small>/);

// Without intervals, a gain cell shows the host-paired warnings the import supplies, not either variant's
// warnings against Joni. Against Joni the regular engine is 0.8, 1.0 and 1.2 and compiled code 0.1, 0.125 and
// 0.15, so the hosts disagree about Joni, yet compiled code is 0.125 of the regular engine on every host.
const hostRun = (entry, candidates, warnings, gains) => {
  const ratios = candidates.map(ns => ns / 100);
  return { ...entry, ...(gains ? { variantGainWarnings: gains } : {}), result: { state: 'compared', candidateNs: candidates[1], comparatorNs: 100, ratio: ratios[1],
    minimumRatio: ratios[0], maximumRatio: ratios[2], deltaNs: candidates[1] - 100, deltaNsPerByte: null, warnings,
    hosts: candidates.map((ns, index) => ({ replica: index + 1, instanceId: `i-${index + 1}`, candidate: { state: 'compared', medianNs: ns }, comparator: { state: 'compared', medianNs: 100 } })) } };
};
const variation = 'Host ratio variation exceeds 5%';
const disagree = 'Hosts disagree about which engine is faster';
const [regularRun, specializedRun, compiledRun] = trinoSpecialized;
const constantGain = [hostRun(regularRun, [80, 100, 120], [variation, disagree]),
  hostRun(specializedRun, [50, 50, 50], [], { [regularRun.id]: [variation] }),
  hostRun(compiledRun, [10, 12.5, 15], [variation], { [regularRun.id]: [], [specializedRun.id]: [variation] })];
// Compiled code is 0.9, 1.0 and 1.1 of the regular engine, so those host pairs disagree about which variant is faster.
const [regularCross, specializedCross, compiledCross] = replaceSpecialized;
const crossingGain = [hostRun(regularCross, [100, 100, 100], []), hostRun(specializedCross, [100, 100, 100], [], { [regularCross.id]: [] }),
  hostRun(compiledCross, [90, 100, 110], [variation], { [regularCross.id]: [variation, disagree], [specializedCross.id]: [variation, disagree] })];
const preliminary = { ...data, publication: { status: 'preliminary', sourcePolicy: 'mixed-development-revisions', note: 'Preliminary.' } };
const constantRows = compactReport({ ...preliminary, rows: [...constantGain, ...crossingGain] }, exports).rows;
const constantPage = specializedPage('trino', constantRows, preliminary);
const [constantRow, crossingRow] = ['extract', 'replace'].map(operation => constantPage.section.split('<tr>').find(cells => cells.includes(`<small>${operation}</small>`)));
assert.match(constantRow, /100.00 ns.*100.00 ns.*50.00 ns.*12.50 ns.*2.00× faster.*8.00× faster.*8.00× faster/s);
assert.doesNotMatch(constantRow, /no consistent winner/);
assert.match(crossingRow, /Compiled ratio divided by regular engine ratio: 1.0000×.">no consistent winner/);
assert.throws(() => specializedPage('trino', [constantGain[0], constantGain[1], { ...constantGain[2], variantGainWarnings: {} }]), /Missing host-paired gain warnings/);
// The preliminary cells hide variation warnings; the row details keep every variant's full warnings.
const [constantPattern] = exports.specializedPatternRows(constantRows.filter(entry => entry.operation === 'extract').map(entry => ({ ...entry, measurementWarnings: entry.result.warnings })));
const constantDetails = renderToStaticMarkup(React.createElement(RowEvidence, { row: constantPattern.anchor, variants: constantPattern.evidence, comparator: 'Joni' }));
assert.match(constantDetails, /<h4>Regular engine measurement variation<\/h4><p>Host ratio variation exceeds 5%. Hosts disagree about which engine is faster.<\/p>/);
assert.match(constantDetails, /<h4>Compiled measurement variation<\/h4><p>Host ratio variation exceeds 5%.<\/p>/);
assert.doesNotMatch(constantDetails, /Specialized engine measurement variation/);
const classified = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...data, rows: [
  ...data.rows,
  { ...reused, id: 'timeout', name: 'Timed out', result: { state: 'did-not-finish', reason: 'execution exceeded 30 s on 2 attempts', hosts: [], warnings: [] } },
  { ...reused, id: 'incompatible', name: 'Not representable', result: { state: 'not-compatible', reason: 'No equivalent Unicode contract', hosts: [], warnings: [] } },
] } }));
assert.match(classified, /comparison-row timeout/);
assert.match(classified, /execution exceeded 30 s on 2 attempts/);
assert.match(classified, /comparison-row na/);
assert.match(classified, /No equivalent Unicode contract/);
assert.doesNotMatch(classified, /NaN|Infinity|undefined/);
const qualified = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...data, rows: [
  { ...reused, source: 'baseline-qualified', originalResult: result },
] } }));
assert.doesNotMatch(qualified, /Original shorter-interval measurements/);
assert.match(qualified, /Download raw results/);
assert.doesNotMatch(qualified, /NaN|Infinity|undefined/);
const publicApi = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...data, rows: [
  { ...reused, originalResult: result, originalResultLabel: 'Previous count-by-matcher-iteration measurements' },
] } }));
assert.doesNotMatch(publicApi, /Previous count-by-matcher-iteration measurements/);
assert.doesNotMatch(publicApi, /Original shorter-interval measurements|NaN|Infinity|undefined/);
assert.equal(downloadData({ ...data, rows: [{ ...reused, originalResult: result }] }).rows[0].originalResult, result);
const mismatched = { ...reused, source: 'baseline', operation: 'possibleMatchRangePrefix', population: 'diagnostics-and-stress', result };
const mismatchedHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...data, rows: [mismatched] } }));
assert.match(mismatchedHtml, /Measurements awaiting alignment/);
assert.match(mismatchedHtml, /Different API work/);
assert.match(detailsHtml(mismatched), /internal program range analysis/);
assert.match(mismatchedHtml, /comparison-row na/);
assert.match(mismatchedHtml, /100.00 ns/);
const annotated = downloadData({ ...data, rows: [mismatched] });
assert.equal(annotated.rows[0].comparisonAssessment.label, 'Different API work');
assert.equal(annotated.rows[0].result, mismatched.result);
assert.ok(!('comparisonAssessment' in mismatched));
assert.doesNotMatch(mismatchedHtml, /5.00× slower|\+80.00 ns|scan-result loss|scan-result win/);
assert.equal(comparisonIssue({ ...mismatched, operation: 'programPossibleMatchRangePrefix' }), null);
for (const operation of ['dotMatch', 'asciiMatch', 'searchPhoneRe2', 'searchEasy0Dfa', 'searchAltMatchRe2']) {
  assert.equal(comparisonIssue({ ...mismatched, operation }).label, 'Needs remeasurement');
}
for (const operation of ['dotMatchCaptures', 'asciiMatchCaptures', 'searchPhoneRe2Captures', 'searchEasy0DfaBoolean', 'searchAltMatchRe2FullMatch']) {
  assert.equal(comparisonIssue({ ...mismatched, operation }), null);
}
const bulk = { ...reused, population: 'bulk-text', operation: 'execute', model: 'count' };
assert.equal(comparisonIssue(bulk).label, 'Historical API path');
assert.equal(comparisonIssue({ ...bulk, measurementSource: { candidate_commit: 'followup' } }), null);
assert.equal(comparisonIssue({ ...bulk, language: 'trino', model: 'count-captures' }).label, 'Different API work');
assert.equal(comparisonIssue({ ...bulk, language: 'trino', model: 'count-spans' }).label, 'Different API work');
assert.equal(comparisonIssue({ ...bulk, language: 'trino', model: 'count-spans', workContract: 'trino-matcher-count-v3' }), null);
assert.equal(comparisonIssue({ ...bulk, language: 'trino', model: 'count-captures', workContract: 'trino-output-assisted-count-v1' }).label, 'Different API work');
assert.equal(comparisonIssue({ ...bulk, language: 'trino' }), null);
assert.equal(comparisonIssue({ ...mismatched, language: 'trino', operation: 'split' }).label, 'Different output APIs');
for (const operation of ['extractAll', 'split', 'replaceLambda']) {
  assert.equal(comparisonIssue({ ...mismatched, language: 'trino', operation, workContract: 'joni-slice-output-v1' }), null);
  assert.equal(comparisonIssue({ ...mismatched, language: 'trino', operation, workContract: 'sql-block-output-v1' }).label, 'Different output APIs');
}
assert.equal(comparisonText({ ...result, warnings: ['sample precision exceeds 5%'] }), 'timing variable');
assert.equal(comparisonText({ ...result, warnings: ['Hosts disagree about which engine is faster'] }), 'no consistent winner');
const variableHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...data, rows: [{ ...reused, result: { ...result, warnings: ['sample precision exceeds 5%'] } }] } }));
assert.match(variableHtml, /comparison-row na/);
assert.match(variableHtml, /timing variable/);
assert.doesNotMatch(variableHtml, /5.00× slower/);
assert.match(variableHtml, /Download raw results/);
const preliminaryPublication = { status: 'preliminary', sourcePolicy: 'mixed-development-revisions', note: 'Development builds with targeted updates.' };
const preliminaryData = { ...data, publication: preliminaryPublication, rows: [{ ...reused, result: { ...result, warnings: ['sample precision exceeds 5%'] } }] };
const preliminaryHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: preliminaryData }));
assert.match(preliminaryHtml, /Development builds with targeted updates/);
assert.match(preliminaryHtml, /5.00× slower/);
assert.match(detailsHtml(preliminaryData.rows[0]), /Measurement variation/);
assert.match(detailsHtml(preliminaryData.rows[0]), /sample precision exceeds 5%/);
assert.doesNotMatch(preliminaryHtml, /Engine abcdef/);
const disputedHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...preliminaryData, rows: [{ ...reused, result: { ...result, warnings: ['Hosts disagree about which engine is faster'] } }] } }));
assert.match(disputedHtml, /no consistent winner/);
assert.doesNotMatch(disputedHtml, /5.00× slower/);
assert.deepEqual(downloadData(preliminaryData).rows[0].result.warnings, ['sample precision exceeds 5%']);

const contextRow = { ...reused, workload: { pattern: 'a<b>.*', inputDescription: '20 bytes of text.', inputs: [{ text: 'example <input>', bytes: 15 }] } };
const contextHtml = detailsHtml(contextRow);
assert.match(contextHtml, /a&lt;b&gt;\.\*/);
assert.match(contextHtml, /20 input bytes per operation/);
assert.match(contextHtml, /Rotating inputs/);
assert.match(contextHtml, /example &lt;input&gt;/);
assert.doesNotMatch(contextHtml.match(/class="row-evidence-body">(.*?)<\/div>/s)[1], /independent hosts/);
const previewData = { ...data, presentation: { reviewPreview: true }, rows: [{ ...contextRow, result: { ...result, warnings: ['sample precision exceeds 5%'] } }] };
const previewHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: previewData }));
assert.match(previewHtml, /5.00× slower/);
assert.doesNotMatch(previewHtml, /timing variable|Engine abcdef/);
assert.equal(downloadData(previewData).rows[0].result.warnings[0], 'sample precision exceeds 5%');

const literalCount = { ...reused, caseId: 'everyday/trinoLiteral', language: 'java', operation: 'reusedCount',
  measurementSource: { cohort: 'routing-20260911-probes-r2' } };
assert.match(workloadCommentary(literalCount).performance, /accelerated prefix scanner/);
assert.equal(workloadCommentary({ ...literalCount, measurementSource: undefined }).performance, undefined);
assert.equal(workloadCommentary({ ...literalCount, operation: 'reusedContains' }).performance, undefined);
assert.equal(workloadCommentary({ ...literalCount, language: 'trino' }).performance, undefined);
assert.equal(workloadCommentary({ ...literalCount, result: { ...result, state: 'not-compatible' } }).performance, undefined);
assert.match(workloadCommentary({ ...literalCount, memoryMode: 'safe' }).performance, /accelerated prefix scanner/);
const delimiterCount = { ...literalCount, caseId: 'everyday/delimiter' };
assert.match(workloadCommentary(delimiterCount).performance, /shared byte-class scanner/);
assert.match(workloadCommentary({ ...delimiterCount, operation: 'reusedContains' }).performance, /shared byte-class scanner/);
assert.equal(workloadCommentary({ ...delimiterCount, measurementSource: undefined }).performance, undefined);
assert.match(workloadCommentary({ ...literalCount, caseId: 'everyday/whitespace' }).performance, /specialized character-run counter/);
assert.match(workloadCommentary({ ...literalCount, caseId: 'everyday/whitespace', operation: 'reusedContains' }).performance, /Contains currently uses the DFA/);
const veryl = { ...reused, caseId: 'curated/05-lexer-veryl/single', language: 'java', operation: 'execute',
  population: 'bulk-text', source: 'fixture', workload: { pattern: '(token)' } };
assert.match(workloadCommentary(veryl).description, /not single-use compilation/);
assert.equal(workloadCommentary(veryl).performance, undefined);
const qualifiedVeryl = { ...veryl, measurementSource: { cohort: 'repeats-final.json' } };
assert.match(workloadCommentary(qualifiedVeryl).performance, /capture bookkeeping/);
assert.match(workloadCommentary({ ...qualifiedVeryl, language: 're2' }).performance, /one-pass capture/);
assert.equal(workloadCommentary({ ...qualifiedVeryl, caseId: 'reported/i13-subset-regex/huge-unicode-nosuffixlit' }).performance, undefined);
assert.match(workloadCommentary({ ...qualifiedVeryl, caseId: 'reported/i13-subset-regex/huge-unicode' }).performance, /ending x first/);
context.window = { location: { hash: '#language=java&cpu=c9g&memory=native' } };
const commentaryHtml = detailsHtml(qualifiedVeryl);
assert.ok(commentaryHtml.indexOf('What this tests') < commentaryHtml.indexOf('<h4>Pattern</h4>'));
assert.match(commentaryHtml, /<h4>Performance context<\/h4>/);
const lifecycleCommentaryHtml = detailsHtml({ ...literalCount, id: 'single-literal-count', operation: 'singleUseCount' }, literalCount);
assert.match(lifecycleCommentaryHtml, /Multi-use performance context/);
delete context.window;
assert.equal(workloadCommentary({ ...reused, caseId: 'unannotated' }).description, undefined);
assert.equal(workloadCommentary({ ...reused, caseId: 'unannotated' }).performance, undefined);
assert.equal(downloadData({ ...data, rows: [qualifiedVeryl] }).rows[0].commentary.performance, workloadCommentary(qualifiedVeryl).performance);

const suffixSingle = { ...row, language: 'like', caseId: 'trino-like/SUFFIX_LARGE', name: 'suffix large', operation: 'singleUse' };
const suffixReused = { ...suffixSingle, id: 'suffix-reused', operation: 'matches' };
assert.equal(lifecycleRows([suffixSingle, suffixReused])[0].name, 'suffix, 6 bytes · 32 KiB input');
assert.match(workloadCommentary(suffixReused).description, /not because the suffix is long/);
assert.match(workloadCommentary(suffixReused).performance, /1-3 ns/);
const refreshedSuffix = { ...suffixReused, workload: { pattern: '%needle', performanceContext: 'Short literals use precomputed word comparisons.' } };
assert.equal(workloadCommentary(refreshedSuffix).performance, refreshedSuffix.workload.performanceContext);
assert.equal(downloadData({ ...data, rows: [refreshedSuffix] }).rows[0].commentary.performance, refreshedSuffix.workload.performanceContext);
assert.equal(workloadCommentary({ ...refreshedSuffix, result: { state: 'not-compatible' } }).performance, undefined);
assert.match(workloadCommentary({ ...suffixReused, caseId: 'trino-like/ORDERED_DENSE_FALSE' }).description, /Synthetic stress test/);
assert.match(workloadCommentary({ ...suffixReused, caseId: 'trino-like/ORDERED_DENSE_FALSE' }).performance, /linear-time KMP/);
assert.match(workloadCommentary({ ...suffixSingle, caseId: 'trino-like/ANY_ASCII/optimized' }).performance, /construction advantage/);
assert.match(workloadCommentary({ ...suffixSingle, caseId: 'trino-like/ANY_ASCII' }).performance, /DFA optimization disabled/);
assert.match(workloadCommentary({ ...suffixSingle, caseId: 'trino-like/ANY_MULTIBYTE' }).performance, /DFA optimization disabled/);
context.window = { location: { hash: '#language=like&cpu=c9g&memory=native' } };
const likeNotesHtml = detailsHtml(suffixSingle, suffixReused);
assert.match(likeNotesHtml, /Single-use performance context/);
assert.match(likeNotesHtml, /Multi-use performance context/);
assert.match(likeNotesHtml, /Cheaper construction/);
assert.match(likeNotesHtml, /six literal bytes/);

const dataDirectory = process.env.REPORT_DATA_DIRECTORY
  ? pathToFileURL(`${process.env.REPORT_DATA_DIRECTORY}/`)
  : new URL('../data/', import.meta.url);
const manifest = JSON.parse(await readFile(new URL('manifest.json', dataDirectory), 'utf8'));
const capture = JSON.parse((await readData(new URL(manifest.current, dataDirectory))).toString('utf8'));
for (const entry of capture.rows.filter(entry => entry.language === 'like'
    && ['matches', 'singleUse'].includes(entry.operation)
    && workloadSection(entry) !== 'diagnostic-only'
    && entry.result.state === 'compared'
    && (entry.result.ratio > 1.02 || entry.result.ratio < .2))) {
  assert.ok(workloadCommentary(entry).performance, `Missing LIKE outlier commentary: ${entry.caseId}/${entry.operation}`);
}
const notes = JSON.parse(await readFile(new URL('../src/workload-notes.json', import.meta.url), 'utf8'));
for (const note of [...notes.descriptions, ...notes.performance]) {
  assert.ok(note.text && (note.cases?.length || note.prefixes?.length));
  for (const caseId of note.cases || []) assert.ok(capture.rows.some(entry => entry.caseId === caseId), `Unknown commentary case: ${caseId}`);
  for (const prefix of note.prefixes || []) assert.ok(capture.rows.some(entry => entry.caseId.startsWith(prefix)), `Unknown commentary prefix: ${prefix}`);
}
// This immutable snapshot is the counterexample: retain every row, but classify its recorded work.
if (manifest.current === 'regulator-f9a982f9f8efc2ca5af0e889a2739dab673ed7ea-9fbaffde10f49400.json') {
  const historical = capture.rows.filter(row => comparisonIssue(row)?.label === 'Historical API path');
  assert.equal(historical.length, 768);
  assert.equal(historical.filter(row => row.result.state === 'compared').length, 750);
  const counts = new Map();
  for (const entry of capture.rows) {
    const issue = comparisonIssue(entry);
    if (issue) counts.set(issue.label, (counts.get(issue.label) || 0) + 1);
  }
  assert.equal(counts.get('Needs remeasurement'), 726);
  assert.equal(counts.get('Different API work'), 834);
  assert.equal(counts.get('Different output APIs'), 810);
}
if (capture.schemaVersion === 2) {
  if (capture.provenance.fairComparisonQualification) {
    assert.equal(capture.rows.filter(entry => comparisonIssue(entry)).length, 0,
      'Qualified comparisons must not still await API alignment');
  }
  for (const language of ['re2', 'java', 'trino', 'like']) {
    for (const cpu of exports.cpuOrder(capture)) {
      for (const memory of ['native', 'safe']) {
        const selected = capture.rows.filter(entry => entry.language === language && entry.platform === cpu && entry.memoryMode === memory);
        assert.ok(selected.length > 0, `Missing view ${language}/${cpu}/${memory}`);
        context.window = { location: { hash: `#language=${language}&cpu=${cpu}&memory=${memory}` } };
        const rendered = renderToStaticMarkup(React.createElement(LanguageReport, { data: capture }));
        const visible = selected.filter(entry => workloadSection(entry) !== 'diagnostic-only');
        assert.match(rendered, /<h2 class="comparison-heading">Regulator vs /);
        assert.doesNotMatch(rendered, /report-toolbar|type="search"|Within 2% is blue|negative Δ saves time/);
        const intro = rendered.match(/<div class="comparison-intro">(.*?)<\/div>/s)[1];
        assert.equal([...intro.matchAll(/<p>/g)].length, 2);
        assert.doesNotMatch(intro, /Execution timings|lifecycle|first use/);
        if (language === 'java') assert.match(rendered, /precomputed UTF-16 Java strings/);
        if (language === 'like') {
          assert.match(intro, /Trino LikeMatcher receives UTF-8 input bytes/);
          assert.doesNotMatch(intro, /standard SQL LIKE configuration|not enabled by the SQL LIKE path/);
          assert.match(rendered, /DFA optimization applies only to some patterns/);
          assert.doesNotMatch(rendered, /break-even/i);
          assert.doesNotMatch(rendered, /reused match|<small>single use/);
          assert.doesNotMatch(rendered, /SQL wrapper overhead|pure-Java kernels in both memory settings/);
          assert.doesNotMatch(rendered.split('<h2>LIKE pattern lifecycle</h2>')[1], /optimize=false/);
        }
        assert.equal(/type="checkbox" checked=""/.test(rendered), memory === 'safe');
        assert.match(rendered, /Download raw results \(JSON\)/);
        assert.doesNotMatch(rendered, /Measurement method and source identity/);
        assert.doesNotMatch(rendered.match(/<footer.*?<\/footer>/s)[0], /<details|<pre/);
        assert.doesNotMatch(rendered, /<h2>Pattern construction/);
        if (selected.some(entry => entry.operation.startsWith('singleUse') && !comparisonIssue(entry))) {
          assert.match(rendered, language === 'like' ? /LIKE pattern lifecycle/ : /Regex pattern lifecycle/);
          if (language !== 'like') assert.match(rendered, /Break-even uses/);
          assert.doesNotMatch(rendered, /Multi-use Δ ns \/ byte/);
          if (lifecycleRows(visible).some(entry => entry.reused.result.deltaNsPerByte != null)) {
            assert.match(rendered, /title="Multi-use time difference per input byte">Δ ns \/ byte/);
          }
        }
        assert.doesNotMatch(rendered, /<td class="measurement[^"]*"[^>]*>(?:NaN|Infinity|undefined)(?:<|$)/);
        assert.equal(rendered.includes('Measurements awaiting alignment'), visible.some(entry => comparisonIssue(entry)));
        lifecycleRows(selected);
        for (const key of ['candidateNs', 'comparatorNs', 'ratio', 'deltaNs', 'deltaNsPerByte']) {
          const values = sorted(selected, { key, descending: false }, entry => entry.result[key])
            .map(entry => entry.result[key]).filter(value => value != null);
          assert.ok(values.every((value, index) => index === 0 || value >= values[index - 1]));
        }
      }
    }
  }
  delete context.window;
  const downloadable = downloadData(capture);
  assert.equal(downloadable.rows.length, capture.rows.length);
  assert.ok(downloadable.rows.every((entry, index) => entry.result === capture.rows[index].result));
  console.log('All 24 current-data language/CPU/memory views and raw download contents passed');
}
console.log("Language report rendering, contract isolation, unit sorting, lifecycle and display tests passed");

// A precise small difference and an uncertain large point estimate are different outcomes.
const meanEstimate = { ...result, estimator: "mean", ratio: .999, candidateNs: 99.9,
  comparatorNs: 100, deltaNs: -.1, hosts: [{}, {}, {}], warnings: [], uncertainty: {
    method: "hierarchical-bootstrap-v1", level: .95, ratioInterval: [.98, 1.02],
    candidateIntervalNs: [98, 102], comparatorIntervalNs: [99, 101],
    forkMeanRangeNs: { candidate: [97, 103], comparator: [98, 102] },
  } };
assert.equal(comparisonText(meanEstimate), "no clear difference");
assert.equal(tone(meanEstimate), "tie");
const preciseMean = { ...meanEstimate, ratio: .99,
  uncertainty: { ...meanEstimate.uncertainty, ratioInterval: [.985, .995] } };
assert.equal(tone(preciseMean), "win");
assert.match(comparisonText(preciseMean), /faster/);
assert.equal(tone({ ...preciseMean, warnings: ["historical warning"] }), "win");
const meanRow = { id: "mean-test", caseId: "mean-test", name: "mean-test", operation: "execute",
  model: "count", population: "bulk-text", family: "test", language: "java", platform: "r9g",
  memoryMode: "native", inputBytes: 100, source: "language", result: meanEstimate };
const meanHtml = detailsHtml(meanRow);
assert.doesNotMatch(meanHtml, /Measurement notes|Approximate 95% intervals|Observed process means|Ratio of mean costs|independent hosts|EC2 allocation/);

// Quality annotations describe each engine, not the uncertainty of their ratio.
const qualityResult = { ...result, estimator: 'mean', uncertainty: {
  method: 'hierarchical-bootstrap-v1', level: .95, ratioInterval: [.9, 1.1],
  candidateIntervalNs: [95, 105], comparatorIntervalNs: [18, 22],
  forkMeanRangeNs: { candidate: [95, 105], comparator: [18, 22] },
} };
assert.equal(exports.timingUncertainty(qualityResult, 'candidate'), .05);
assert.equal(exports.timingUncertainty(qualityResult, 'comparator'), .10);
assert.equal(exports.timingUncertainty(result, 'candidate'), null);
const qualityRow = { ...row, result: qualityResult };
const qualityReused = { ...reused, result: qualityResult };
const qualityRows = exports.displayedMeasurements([qualityRow, qualityReused,
  { ...row, id: 'compile', operation: 'compile', result: qualityResult }]);
assert.equal(qualityRows.length, 2); // Reused appears in two tables, but counts once.
assert.equal(exports.qualityDistribution(qualityRows, 'candidate').aboveFive, 0);
assert.equal(exports.qualityDistribution(qualityRows, 'comparator').aboveFive, 2);
assert.equal(exports.qualityDistribution([], 'candidate'), null);
const qualityHtml = renderToStaticMarkup(React.createElement(LanguageReport, { data: { ...data, rows: [qualityRow, qualityReused] } }));
assert.match(qualityHtml, /2 distinct numeric comparisons/);
assert.match(qualityHtml, /Median/);
assert.match(qualityHtml, /native RE2 timing uncertainty: 10.0%/);
assert.doesNotMatch(qualityHtml, /Regulator timing uncertainty: 5.0%/);
assert.doesNotMatch(qualityHtml, /ratio-interval/);
assert.match(qualityHtml, /No clear difference/i);
assert.match(qualityHtml, /<span class="scan-result tie"[^>]*>no clear difference<sup><button[^>]*>\*<\/button><\/sup><\/span>/);
assert.match(qualityHtml, /id="quality-high"/);
// Expanded rows explain only applicable markers; unflagged measurements stay quiet.
const flaggedDetails = detailsHtml(qualityRow);
assert.match(flaggedDetails, /\* native RE2 timing uncertainty: 10.0%/);
assert.doesNotMatch(flaggedDetails, /Regulator timing uncertainty|Approximate 95% intervals|Observed process means|independent hosts|EC2 allocation/);
assert.match(detailsHtml(qualityRow, qualityReused), /Multi-use measurement notes/);
