import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import vm from "node:vm";
import ts from "typescript";

// Use the same report calculations in the static build and rendering tests.
export async function loadReportModule() {
  const path = new URL("../src/language-report.tsx", import.meta.url);
  const exports = {};
  const context = { module: { exports }, exports, require: createRequire(path), URLSearchParams, Intl };
  vm.runInNewContext(ts.transpileModule(await readFile(path, "utf8"), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, context, { filename: "language-report.tsx" });
  return { report: exports, context };
}
