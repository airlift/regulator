import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { createHash } from "node:crypto";
import { gzipSync } from "node:zlib";

import { build } from "esbuild";
import { readData } from "./read-data.mjs";
import { compactReport, reportPages } from "./page-data.mjs";
import { loadReportModule } from "./load-report-module.mjs";

const reportRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const { values } = parseArgs({ options: {
  "data-directory": { type: "string" },
  "output-name": { type: "string", default: "benchmarks" },
} });
if (!/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(values["output-name"])) throw new Error("Invalid report output name");
const outputRoot = resolve(reportRoot, "../target/benchmark-report", values["output-name"]);
const dataRoot = values["data-directory"] ? resolve(values["data-directory"]) : resolve(reportRoot, "data");
if (dataRoot === outputRoot || dataRoot.startsWith(outputRoot + sep) || outputRoot.startsWith(dataRoot + sep)) {
  throw new Error("Report input data and output directories must not overlap");
}
const manifest = JSON.parse(await readFile(resolve(dataRoot, "manifest.json"), "utf8"));
if (manifest.reportSchemaVersion !== 2
    || (manifest.releases ?? []).some(release => release.schemaVersion !== 2)
    || !/^[A-Za-z0-9._-]+$/.test(manifest.current)) {
  throw new Error("Unsupported benchmark report manifest");
}
const entryPoint = "src/language-report.tsx";
const dataFiles = new Set([manifest.current, ...(manifest.releases ?? []).map(release => release.file)]);
for (const file of dataFiles) {
  if (!/^[A-Za-z0-9._-]+\.json(?:\.gz)?$/.test(file)) throw new Error("Invalid report data filename");
  if (file.endsWith(".gz") && dataFiles.has(file.slice(0, -3))) throw new Error("Duplicate report output filename");
}

await rm(outputRoot, { recursive: true, force: true });
await mkdir(outputRoot, { recursive: true });

await build({
  absWorkingDir: reportRoot,
  entryPoints: [entryPoint],
  bundle: true,
  format: "esm",
  minify: true,
  outfile: resolve(outputRoot, "app.js"),
  platform: "browser",
  sourcemap: true,
  target: "es2022",
});

await cp(resolve(reportRoot, "src/styles.css"), resolve(outputRoot, "styles.css"));
await cp(resolve(reportRoot, "../docs/benchmarks/MEASUREMENT_QUALITY.md"), resolve(outputRoot, "measurement-quality.txt"));
await mkdir(resolve(outputRoot, "data"));
// Retain only versioned public captures in the site artifact. Complete campaign
// diagnostics live in the separately indexed private evidence archive.
for (const file of dataFiles) {
  await writeFile(resolve(outputRoot, "data", file.replace(/\.gz$/, "") + ".gz"), gzipSync(await readData(resolve(dataRoot, file))));
}
const { report } = await loadReportModule();
const data = JSON.parse((await readData(resolve(dataRoot, manifest.current))).toString("utf8"));
const hash = bytes => createHash("sha256").update(bytes).digest("hex").slice(0, 16);
const fullResults = JSON.stringify(report.downloadData(data));
const fullResultsFile = `full-results-${hash(fullResults)}.json.gz`;
await writeFile(resolve(outputRoot, "data", fullResultsFile), gzipSync(fullResults));
const sourceLinks = {};
const revisionScript = await readFile(resolve(reportRoot, "../tools/re2-benchmark/manifests/rebar-revision.sh"), "utf8");
const revision = revisionScript.match(/pinned_commit=([a-f0-9]{40})/)[1];
const definitions = await readFile(resolve(reportRoot, "../tools/re2-benchmark/manifests/rebar-extended-workloads.tsv"), "utf8");
for (const line of definitions.trim().split("\n").slice(1)) {
  const [name, , definition] = line.split("\t");
  sourceLinks[name] = `https://github.com/BurntSushi/rebar/blob/${revision}/benchmarks/definitions/${definition}`;
}
for (const row of data.rows.filter(row => row.caseId.startsWith("curated/"))) {
  sourceLinks[row.caseId] = `https://github.com/BurntSushi/rebar/blob/${revision}/benchmarks/definitions/${row.caseId.split("/").slice(0, 2).join("/")}.toml`;
}
const compact = { ...compactReport(data, report, sourceLinks), fullResultsUrl: `./data/${fullResultsFile}` };
const { rows, ...metadata } = compact;
const servedManifest = { ...metadata, siteSchemaVersion: 1, pages: {} };
for (const [key, page] of Object.entries(reportPages(compact))) {
  const json = JSON.stringify(page);
  const filename = `${key}-${hash(json)}.json`;
  servedManifest.pages[key] = filename;
  await writeFile(resolve(outputRoot, "data", filename), json);
}
await writeFile(resolve(outputRoot, "data/manifest.json"), JSON.stringify(servedManifest, null, 2) + "\n");
await writeFile(resolve(outputRoot, ".nojekyll"), "");
await writeFile(resolve(outputRoot, "index.html"), `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="description" content="Regulator benchmark report">
    <title>Regulator benchmark report</title>
    <link rel="stylesheet" href="./styles.css">
  </head>
  <body>
    <div id="root"><main class="loading-page">Loading benchmark results…</main></div>
    <script type="module" src="./app.js"></script>
  </body>
</html>
`);

console.log(`Built ${outputRoot}`);

const compiled = await build({
  absWorkingDir: reportRoot,
  entryPoints: [entryPoint],
  bundle: true,
  format: "iife",
  minify: true,
  platform: "browser",
  target: "es2022",
  write: false,
});
const styles = await readFile(resolve(reportRoot, "src/styles.css"), "utf8");
// The standalone file is intentionally self-contained, including downloadable evidence.
const embedded = JSON.stringify(data).replaceAll("<", "\\u003c");
const standalone = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Regulator benchmark report</title><style>${styles}</style></head>
<body><div id="root"></div><script type="application/json" id="report-data">${embedded}</script>
<script>window.__REGULATOR_REPORT__=JSON.parse(document.getElementById("report-data").textContent);</script>
<script>${compiled.outputFiles[0].text.replaceAll("</script", "<\\/script")}</script></body></html>\n`;
await writeFile(resolve(outputRoot, "regulator-benchmarks.html"), standalone);
console.log("Built standalone regulator-benchmarks.html; no server or network required");
