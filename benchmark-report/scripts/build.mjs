import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

import { build } from "esbuild";
import { readData } from "./read-data.mjs";

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
await cp(dataRoot, resolve(outputRoot, "data"), { recursive: true });
for (const file of dataFiles) {
  if (file.endsWith(".gz")) {
    await writeFile(resolve(outputRoot, "data", file.slice(0, -3)), await readData(resolve(dataRoot, file)));
    await rm(resolve(outputRoot, "data", file));
  }
}
const servedManifest = {
  ...manifest,
  current: manifest.current.replace(/\.gz$/, ""),
  ...(manifest.releases ? { releases: manifest.releases.map(release => ({ ...release, file: release.file.replace(/\.gz$/, "") })) } : {}),
};
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
const data = (await readData(resolve(dataRoot, manifest.current))).toString("utf8");
const embedded = JSON.stringify(JSON.parse(data)).replaceAll("<", "\\u003c");
const standalone = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Regulator benchmark report</title><style>${styles}</style></head>
<body><div id="root"></div><script type="application/json" id="report-data">${embedded}</script>
<script>window.__REGULATOR_REPORT__=JSON.parse(document.getElementById("report-data").textContent);</script>
<script>${compiled.outputFiles[0].text.replaceAll("</script", "<\\/script")}</script></body></html>\n`;
await writeFile(resolve(outputRoot, "regulator-benchmarks.html"), standalone);
console.log("Built standalone regulator-benchmarks.html; no server or network required");
