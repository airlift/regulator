import { readFileSync } from "node:fs";
import { loadReportModule } from "./load-report-module.mjs";

// The Python publication tool uses the report's own classification and API checks.
const { report } = await loadReportModule();
const data = JSON.parse(readFileSync(0, "utf8"));
process.stdout.write(JSON.stringify(report.summaryRatios(data)));
