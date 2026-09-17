import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { gzipSync, gunzipSync } from "node:zlib";
import { createHash } from "node:crypto";
import { pathToFileURL } from "node:url";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { readData } from "./read-data.mjs";
import { loadReportModule } from "./load-report-module.mjs";
import { compactReport } from "./page-data.mjs";

const site = new URL("../../target/benchmark-report/benchmarks/", import.meta.url);
const { report } = await loadReportModule();
const manifest = JSON.parse(await readFile(new URL("data/manifest.json", site), "utf8"));
const inputRoot = process.env.REPORT_DATA_DIRECTORY
  ? pathToFileURL(`${process.env.REPORT_DATA_DIRECTORY}/`)
  : new URL("../data/", import.meta.url);
const inputManifest = JSON.parse(await readFile(new URL("manifest.json", inputRoot), "utf8"));
const original = JSON.parse((await readData(new URL(inputManifest.current, inputRoot))).toString("utf8"));
const scope = original.publicationScope;
assert.equal(scope?.policy, "user-facing-release-v1");
const workloadEntry = inputManifest.releases.find(entry => entry.candidate === scope?.workloadSourceCandidate);
assert.ok(workloadEntry, "Publication workload source remains versioned");
const published = workloadEntry
  ? JSON.parse(await readData(new URL(workloadEntry.file, inputRoot))) : null;
const download = JSON.parse(gunzipSync(await readFile(new URL(manifest.fullResultsUrl, site))));
const digest = value => createHash("sha256").update(JSON.stringify(value)).digest("hex");
assert.equal(digest(download), digest(report.downloadData(original)), "Public download is the annotated publication dataset");
assert.equal(original.rows.length, scope.publishedRows);
assert.equal(scope.completeCampaignRows - scope.excludedCampaignRows, scope.publishedRows);
assert.ok(original.rows.every(row => !("reportVisible" in row)));
assert.doesNotMatch(JSON.stringify(original), /[†‡]/, "Public data uses only measurement-level uncertainty markers");
assert.equal(Object.keys(manifest.pages).length, 24);
const builtDataFiles = (await readdir(new URL("data/", site))).sort();
assert.equal(builtDataFiles.filter(name => name.endsWith(".json")).length, 25);
assert.deepEqual(builtDataFiles.filter(name => name.endsWith(".json.gz")).sort(), [
  inputManifest.current.replace(/\.gz$/, "") + ".gz",
  ...inputManifest.releases.map(entry => entry.file.replace(/\.gz$/, "") + ".gz"),
  manifest.fullResultsUrl.split("/").at(-1),
].filter((name, index, names) => names.indexOf(name) === index).sort(),
"Pages data contains only publication captures, compact pages and the public download");
assert.ok(Buffer.byteLength(JSON.stringify(manifest)) < 10_000);
const sizes = [];
for (const [key, filename] of Object.entries(manifest.pages)) {
  const bytes = await readFile(new URL(`data/${filename}`, site));
  const page = JSON.parse(bytes);
  assert.equal(report.selectionKey(page.selection), key);
  const { language, cpu, memory } = page.selection;
  const expected = original.rows.filter(row => row.language === language && row.platform === cpu
    && row.memoryMode === memory && report.workloadSection(row) !== "diagnostic-only");
  assert.deepEqual(page.rows.map(row => row.id), expected.map(row => row.id));
  for (const [index, row] of page.rows.entries()) {
    const source = expected[index];
    // Exercise every real fold-down, including rows without pattern metadata.
    assert.doesNotThrow(() => renderToStaticMarkup(React.createElement(report.RowEvidence, { row, comparator: "Comparator" })));
    assert.deepEqual(report.workloadCommentary(row), report.workloadCommentary(source));
    assert.deepEqual(report.comparisonIssue(row), report.comparisonIssue(source));
    assert.equal(report.outcomeLabel(row.result, "Comparator"), report.outcomeLabel(source.result, "Comparator"));
    for (const field of ["state", "candidateNs", "comparatorNs", "ratio", "minimumRatio", "maximumRatio", "deltaNs", "deltaNsPerByte", "warnings", "reason", "estimator"]) {
      assert.deepEqual(report.displayResult(row.result)[field], report.displayResult(source.result)[field], `${row.id}/${field}`);
    }
    if (source.result.uncertainty) {
      for (const field of ["method", "level", "ratioInterval", "candidateIntervalNs", "comparatorIntervalNs", "forkMeanRangeNs"]) {
        assert.deepEqual(row.result.uncertainty[field], source.result.uncertainty[field], `${row.id}/${field}`);
      }
    }
    assert.ok(!row.previousMeasurements && !row.originalResult);
    const pattern = source.workload?.pattern ?? source.mapping?.patternPreview;
    if (pattern && Buffer.byteLength(pattern) > 4096) {
      assert.equal(row.patternSummary.bytes, Buffer.byteLength(pattern));
      assert.ok((row.workload?.pattern ?? row.mapping.patternPreview).endsWith("…"));
      assert.ok(Buffer.byteLength(row.workload?.pattern ?? row.mapping.patternPreview) < 1000);
      const expanded = renderToStaticMarkup(React.createElement(report.RowEvidence, { row, comparator: "Comparator" }));
      assert.match(expanded, /Only a short preview is shown/);
      assert.ok(expanded.length < 10_000, "Expanded preview stays small");
    }
    else assert.deepEqual(row.workload, source.workload);
  }
  // Collapsed table markup, numeric cells, grouping, statuses and order must be identical.
  const render = data => renderToStaticMarkup(React.createElement(report.LanguageReport, { data, selection: page.selection }));
  assert.equal(render(page).split("<footer")[0], render(original).split("<footer")[0]);
  assert.doesNotMatch(render(page), /row-evidence-body/);
  assert.match(render(page), /Download report data \(JSON.gz\)/);
  // Mean-cost intervals and per-host evidence make the public pages substantial.
  // Keep each page bounded without dropping publication evidence.
  assert.ok(bytes.length < 1_100_000, `${key} uncompressed size budget`);
  assert.ok(gzipSync(bytes).length < 210_000, `${key} compressed size budget`);
  sizes.push([key, bytes.length, gzipSync(bytes).length]);
}

if (published) {
  const identity = row => [row.caseId, row.operation, row.language,
    row.platform.replace(/^c/, "r"), row.memoryMode].join("|");
  assert.deepEqual(original.rows.map(identity), published.rows.map(identity), "Publication workload inventory is frozen");
  assert.equal(download.rows.length, 5004, "The public download contains the frozen publication inventory");
  assert.equal(download.rows.length, original.rows.length, "The public download contains only publication rows");
  const workloadDetails = workload => Object.fromEntries(
    Object.entries(workload ?? {}).filter(([field]) => field !== "performanceContext"));
  for (const [index, row] of original.rows.entries()) {
    const before = published.rows[index];
    assert.deepEqual(workloadDetails(row.workload), workloadDetails(before.workload),
      `${identity(row)} preserves published workload details`);
  }
  for (const [key, filename] of Object.entries(manifest.pages)) {
    const page = JSON.parse(await readFile(new URL(`data/${filename}`, site)));
    const {language, cpu, memory} = page.selection;
    const previousRows = published.rows.filter(row => row.language === language
      && row.platform === cpu.replace(/^r/, "c") && row.memoryMode === memory
      && report.workloadSection(row) !== "diagnostic-only");
    assert.deepEqual(page.rows.map(identity), previousRows.map(identity), `${key}: published workload order`);
    assert.equal(new Set(page.rows.map(identity)).size, page.rows.length, `${key}: no duplicate logical workloads`);
    const previous = compactReport({ ...published, rows: previousRows }, report).rows;
    for (const [index, row] of page.rows.entries()) {
      const before = previous[index];
      for (const field of ["name", "caseId", "operation", "model", "inputBytes"]) {
        assert.equal(row[field], before[field], `${key}/${row.id}/${field}`);
      }
      for (const field of ["pattern", "description", "inputDescription", "inputs", "flags"]) {
        assert.deepEqual(row.workload?.[field], before.workload?.[field], `${key}/${row.id}/${field}`);
      }
      assert.deepEqual(report.workloadCommentary(row), report.workloadCommentary(before));
    }
    const eligible = page.rows.filter(row => !report.comparisonIssue(row));
    for (const pair of report.lifecycleRows(eligible)) {
      assert.doesNotThrow(() => renderToStaticMarkup(React.createElement(report.RowEvidence,
        { row: pair.single ?? pair.reused, reused: pair.single ? pair.reused : undefined, comparator: "Comparator" })));
    }
    const shown = report.displayedMeasurements(eligible);
    assert.equal(shown.length, {java: 188, re2: 188, trino: 292, like: 28}[language], `${key}: published display count`);
  }
}

// Mapping-only captures already contain previews, not the full regex text.
const mappedRow = original.rows.find(row => row.mapping?.patternBytes > 4096
  && report.workloadSection(row) !== "diagnostic-only");
const { workload, ...mappingOnly } = mappedRow;
const sourceUrl = "https://example.com/pinned-pattern";
const projected = compactReport({ ...original, rows: [mappingOnly] }, report,
  { [mappedRow.caseId]: sourceUrl }).rows[0];
assert.equal(projected.patternSummary?.bytes, mappedRow.mapping.patternBytes);
assert.equal(projected.patternSummary.sourceUrl, sourceUrl);
assert.equal(projected.mapping.patternPreview, mappedRow.mapping.patternPreview);
const contextOnly = { ...mappingOnly, workload: { performanceContext: "Released compiler path." } };
const contextProjection = compactReport({ ...original, rows: [contextOnly] }, report).rows[0];
assert.deepEqual(contextProjection.workload, contextOnly.workload,
  "A mapping preview must not become an apparent complete workload pattern");
assert.equal(contextProjection.patternSummary.fullPatternAvailable, false);
const evidence = row => renderToStaticMarkup(React.createElement(report.RowEvidence, { row, comparator: "Comparator" }));
assert.doesNotThrow(() => evidence({ ...mappingOnly, mapping: undefined, workload: { performanceContext: "Context only" } }));
assert.match(evidence(projected), /Only a short preview is shown/);
assert.ok(evidence(projected).includes(sourceUrl));
assert.doesNotMatch(evidence(projected), /full pattern is also included/);
// Released captures may carry only mapping previews. Exercise full-pattern
// disclosure with an explicit fixture rather than assuming a capture shape.
const fullPattern = compactReport({ ...original, rows: [{ ...mappedRow,
  workload: { pattern: "a".repeat(5000) } }] }, report).rows[0];
assert.match(evidence(fullPattern), /full pattern is also included/);
// The actual UTF-8 pattern wins over a mapping's declared source size.
const shortPattern = compactReport({ ...original, rows: [{ ...mappedRow, workload: { pattern: "é" } }] }, report).rows[0];
assert.equal(shortPattern.patternSummary, undefined);
const boundaryPattern = bytes => compactReport({ ...original, rows: [{ ...mappingOnly,
  mapping: { ...mappingOnly.mapping, patternBytes: bytes } }] }, report).rows[0];
assert.equal(boundaryPattern(4096).patternSummary, undefined);
assert.equal(boundaryPattern(4097).patternSummary.bytes, 4097);

// Preserve allocation evidence in page data without displaying a raw measurement block.
// A fixture exercises the path even while the current historical capture lacks it.
const allocationFixture = { ...mappingOnly, mapping: undefined, workload: undefined,
  result: { state: "compared", estimator: "mean", warnings: [], candidateNs: 100, comparatorNs: 200,
    ratio: .5, minimumRatio: .49, maximumRatio: .51, deltaNs: -100,
    hosts: Array.from({ length: 3 }, () => ({ instanceType: "r9g.2xlarge",
      candidate: { state: "compared", medianNs: 100 }, comparator: { state: "compared", medianNs: 200 } })),
    uncertainty: { method: "hierarchical-bootstrap-v1", level: .95, ratioInterval: [.49, .51],
      candidateIntervalNs: [99, 101], comparatorIntervalNs: [199, 201],
      forkMeanRangeNs: { candidate: [98, 102], comparator: [198, 202] } } } };
const allocationPageRow = compactReport({ ...original, rows: [allocationFixture] }, report).rows[0];
assert.equal(allocationPageRow.result.hosts[0].instanceType, "r9g.2xlarge");
assert.doesNotMatch(evidence(allocationPageRow), /EC2 allocation|Observed process means|Approximate 95% intervals/);

// The real loader fetches one selected page, shares in-flight requests, caches
// visited selections, and evicts failed requests so Retry can actually retry.
const requests = [];
let failNext = false;
let corruptNext = false;
const loader = report.createReportLoader(async url => {
  requests.push(url);
  if (failNext) { failNext = false; return { ok: false, status: 503 }; }
  return { ok: true, json: async () => {
    const value = JSON.parse(await readFile(new URL(url, site), "utf8"));
    if (corruptNext) { corruptNext = false; value.selection = { language: "wrong" }; }
    return value;
  } };
});
failNext = true;
await assert.rejects(loader.manifest(), /503/);
const liveManifest = await loader.manifest();
await loader.manifest();
assert.equal(requests.length, 2);
const cpus = report.cpuOrder(original);
const selection = { language: "re2", cpu: cpus[0], memory: "native" };
const first = await Promise.all([loader.page(selection, liveManifest), loader.page(selection, liveManifest)]);
assert.equal(first[0], first[1]);
assert.equal(requests.length, 3);
await loader.page(selection, liveManifest);
assert.equal(requests.length, 3);
const other = { ...selection, language: "like" };
failNext = true;
await assert.rejects(loader.page(other, liveManifest), /503/);
corruptNext = true;
await assert.rejects(loader.page(other, liveManifest), /Incorrect report selection/);
await loader.page(other, liveManifest);
assert.equal(requests.length, 6);
assert.ok(requests.every(url => !url.endsWith(".gz")), "Browsing never fetches the full evidence");
await assert.rejects(loader.page({ ...selection, language: "missing" }, liveManifest), /Missing report selection/);

// A deployment replaces hashed files while a tab still holds the old manifest.
// A failed selection must refresh that manifest on Retry, without an automatic
// retry loop or returning a cached page from the previous deployment.
let deployed = false;
const deploymentRequests = [];
const selectionId = report.selectionKey(selection);
const otherId = report.selectionKey(other);
const updatedManifest = { ...manifest, pages: { ...manifest.pages,
  [selectionId]: "updated-re2.json", [otherId]: "updated-like.json" } };
const updatedPage = { ...first[0], sources: { ...first[0].sources, currentLabel: "Updated capture" } };
const deploymentLoader = report.createReportLoader(async (url, options) => {
  deploymentRequests.push({ url, options });
  if (url === "./data/manifest.json") return { ok: true, json: async () => deployed ? updatedManifest : manifest };
  if (url === "./data/updated-re2.json") return { ok: true, json: async () => updatedPage };
  if (url === "./data/updated-like.json") return { ok: true, json: async () => ({ ...updatedPage, selection: other, rows: [] }) };
  if (deployed) return { ok: false, status: 404 };
  return { ok: true, json: async () => first[0] };
});
const beforeDeploy = await deploymentLoader.manifest();
await deploymentLoader.page(selection, beforeDeploy);
await deploymentLoader.manifest();
assert.equal(deploymentRequests.length, 2, "Normal navigation keeps the manifest cached");
deployed = true;
await assert.rejects(deploymentLoader.page(other, beforeDeploy), /404/);
assert.equal(deploymentRequests.length, 3, "Failure does not start automatic retries");
const afterDeploy = await deploymentLoader.manifest();
assert.equal(afterDeploy.pages[otherId], "updated-like.json");
assert.equal((await deploymentLoader.page(other, afterDeploy)).sources.currentLabel, "Updated capture");
assert.equal((await deploymentLoader.page(selection, afterDeploy)).sources.currentLabel, "Updated capture",
  "The old selection cache must not hide the newly deployed page");
await deploymentLoader.manifest();
await deploymentLoader.page(selection, afterDeploy);
assert.equal(deploymentRequests.length, 6, "Recovered pages and manifest are cached");
assert.ok(deploymentRequests.filter(request => request.url.endsWith("manifest.json"))
  .every(request => request.options?.cache === "no-cache"), "Manifest fetches revalidate the browser HTTP cache");

// Both the loading view's manifest and loaded pages must reject missing display
// metadata before React sees it. A corrected response must be retryable.
const invalidMetadata = [
  value => { delete value.languages; },
  value => { delete value.languages.java; },
  value => { value.languages.re2.label = {}; },
  value => { delete value.languages.like.comparator; },
  value => { delete value.sources; },
  value => { delete value.sources.currentCandidate; },
  value => { value.sources.currentLabel = {}; },
  value => { value.sources.jdk = null; },
  value => { delete value.platforms; },
  value => { delete value.platforms[cpus[2]]; },
  value => { value.platforms[cpus[0]] = []; },
  value => { value.publication = { status: "preliminary", note: {} }; },
];
for (const invalidate of invalidMetadata) {
  for (const kind of ["manifest", "page"]) {
    const valid = kind === "manifest" ? manifest : first[0];
    const invalid = structuredClone(valid);
    invalidate(invalid);
    let attempts = 0;
    const retryLoader = report.createReportLoader(async () => ({ ok: true,
      json: async () => ++attempts === 1 ? invalid : valid }));
    const read = () => kind === "manifest" ? retryLoader.manifest() : retryLoader.page(selection, manifest);
    await assert.rejects(read(), /Invalid report display metadata/);
    const recovered = await read();
    assert.equal(attempts, 2);
    assert.doesNotThrow(() => renderToStaticMarkup(React.createElement(report.LanguageReport,
      { data: { ...recovered, rows: [] }, selection })));
  }
}
console.log("All 24 compact pages preserve table markup, results and commentary; the gzip download preserves the public evidence.");
console.log("Loader selection, in-flight deduplication, cache, failure/retry and payload validation passed.");
console.log("Page sizes [selection, JSON bytes, gzip bytes]:", sizes);
