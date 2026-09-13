import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { readData } from "./read-data.mjs";
import { loadReportModule } from "./load-report-module.mjs";

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
  'Text processing workloads', 'Adversarial, stress and diagnostic workloads']);
const textSection = groupedHtml.split('<h2>Text processing workloads</h2>')[1].split('</section>')[0];
const stressSection = groupedHtml.split('<h2>Adversarial, stress and diagnostic workloads</h2>')[1].split('</section>')[0];
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
assert.doesNotMatch(variableHtml, /†|5.00× slower/);
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
    for (const cpu of ['c9g', 'c8g', 'c8i']) {
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
