import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { descriptions, performance as performanceNotes } from "./workload-notes.json";

export type Result = {
  state: "compared" | "not-compatible" | "did-not-finish";
  reason?: string;
  candidateNs?: number;
  comparatorNs?: number;
  ratio?: number;
  minimumRatio?: number;
  maximumRatio?: number;
  deltaNs?: number;
  deltaNsPerByte?: number | null;
  warnings: string[];
  hosts: Array<{
    instanceId?: string;
    replica?: number;
    candidate: { medianNs?: number; meanNs?: number; epochMeansNs?: number[]; state: string; reason?: string };
    comparator: { medianNs?: number; meanNs?: number; epochMeansNs?: number[]; state: string; reason?: string };
  }>;
};
export type Row = {
  id: string;
  caseId: string;
  name: string;
  operation: string;
  model: string;
  population: string;
  family: string;
  language: string;
  platform: string;
  memoryMode: string;
  inputBytes: number | null;
  source: string;
  comparator?: string | null;
  mapping?: { status: string; reason: string; patternPreview: string; patternBytes: number };
  result: Result;
  originalResult?: Result;
  originalResultLabel?: string;
  measurementSource?: Record<string, unknown>;
  measurementWarnings?: string[];
  workContract?: string;
  workload?: { pattern: string; description?: string; performanceContext?: string; inputDescription?: string; flags?: string[]; inputs?: Array<{ text: string; bytes: number }> };
  patternSummary?: { bytes: number; sourceUrl?: string; fullPatternAvailable: boolean };
};
export type ReportData = {
  schemaVersion: 2;
  sources: { currentLabel: string; currentCandidate: string; engineTree: string; jdk: string };
  platforms: Record<string, string>;
  memoryModes: Record<string, string>;
  languages: Record<string, { label: string; comparator: string }>;
  rows: Row[];
  methodology: string[];
  provenance: Record<string, unknown>;
  presentation?: { reviewPreview: boolean };
  publication?: { status: "preliminary"; note: string; sourcePolicy: "mixed-development-revisions" };
  fullResultsUrl?: string;
  selection?: Selection;
};

type Sort = { key: string; descending: boolean } | null;
type SortValue = string | number | null | undefined;
type Selection = { language: string; cpu: string; memory: string };
const cpuOrder = ["c9g", "c8g", "c8i"];
const languageOrder = ["re2", "java", "trino", "like"];
const numberFormat = new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const percentFormat = new Intl.NumberFormat("en-US", { minimumFractionDigits: 1, maximumFractionDigits: 1 });

type ComparisonIssue = { label: string; reason: string };

// Classify the recorded call contract, never today's implementation or the ratio.
// Renamed traditional methods identify corrected captures; old names stay historical.
export function comparisonIssue(row: Row): ComparisonIssue | null {
  if (row.source.startsWith("baseline") && row.language === "re2") {
    if (row.operation.startsWith("possibleMatchRange")) return { label: "Different API work", reason: "Regulator internal program range analysis was paired with RE2 public prefix-aware range analysis. Needs a new program-to-program measurement." };
    if (["dotMatch", "asciiMatch", "searchPhoneRe2"].includes(row.operation)) return { label: "Needs remeasurement", reason: "Regulator requested a boolean; native RE2 also extracted captures. Corrected benchmarks request captures on both sides." };
    if (/^search.*Dfa$/.test(row.operation)) return { label: "Needs remeasurement", reason: "Regulator requested a match boundary; native DFA requested only a boolean. Corrected benchmarks request only a boolean on both sides." };
    if (["searchSuccessRe2", "searchSuccess1Re2", "searchAltMatchRe2"].includes(row.operation)) return { label: "Needs remeasurement", reason: "Regulator requested a prefix match; native RE2 requested a full match. Corrected benchmarks request a full match on both sides." };
  }
  if (row.source === "language" && row.operation === "execute") {
    if (row.language === "trino" && ["count-spans", "count-captures", "grep-captures"].includes(row.model) && row.workContract !== "trino-matcher-count-v3") return {
      label: "Different API work", reason: row.model === "count-spans"
        ? "Regulator builds an extracted-value list before summing lengths; Joni sums match boundaries without that list. These timings do not isolate matching speed."
        : "Regulator constructs capture lists and replacement output to count captures; Joni counts capture regions without constructing replacement output. These timings do not isolate matching speed.",
    };
    if (["re2", "java"].includes(row.language) && ["grep", "count"].includes(row.model) && !row.measurementSource && row.workContract !== "public-find-count-v2") return {
      label: "Historical API path", reason: row.model === "grep"
        ? "This capture used matcher boundary discovery, not the current public boolean find call. Retained for reference; needs remeasurement."
        : "This capture used reusable matcher iteration, not the current public count call. Retained for reference; needs remeasurement.",
    };
  }
  if (row.source.startsWith("baseline") && row.language === "trino" && ["extractAll", "split", "replaceLambda"].includes(row.operation) && row.workContract !== "joni-slice-output-v1") return {
    label: "Different output APIs", reason: "Regulator uses Slice lists; the Trino/Joni comparator uses SQL Blocks, including value-copying for extraction and split. These are different integration boundaries, not a pure engine comparison.",
  };
  return null;
}

export function downloadData(data: ReportData) {
  return { ...data, rows: data.rows.map(row => ({ ...row, comparisonAssessment: comparisonIssue(row), reportSection: workloadSection(row), commentary: workloadCommentary(row) })) };
}

export function workloadSection(row: Row): string {
  if (row.operation === "compile" || row.population === "compilation") return "diagnostic-only";
  if (row.language === "like" && row.caseId.endsWith("/optimized")) return likeDfaVariant(row) ? "like" : "diagnostic-only";
  if (["captures/contiguous-letters", "grep/every-line", "wild/dot-star-capture/rust-src-tools", "reported/i787-keywords/opt-ascii"].includes(row.caseId)) return "diagnostic-only";
  if (row.caseId.startsWith("unicode/codepoints/") || row.caseId.startsWith("unicode/overlapping-words/")) return "synthetic-stress";
  if (row.caseId === "reported/i787-keywords/ascii") return "bulk-text";
  if (["imported/leipzig/certain-long-strings-ending-x", "imported/sherlock/repeated-class-negation"].includes(row.caseId)) return "diagnostics-and-stress";
  return row.population;
}

export function workloadCommentary(row: Row) {
  const matches = (note: { cases?: string[]; prefixes?: string[] }) =>
    note.cases?.includes(row.caseId) || note.prefixes?.some(prefix => row.caseId.startsWith(prefix));
  return {
    description: descriptions.find(matches)?.text,
    performance: row.result.state === "not-compatible" ? undefined : row.workload?.performanceContext ?? performanceNotes.find(note => matches(note)
      && (!note.languages || note.languages.includes(row.language))
      && note.operations.includes(row.operation)
      && (!note.qualifiedOnly || row.measurementSource?.cohort != null))?.text,
  };
}

export function displayResult(result: Result): Result {
  if (result.state === "compared") return result;
  const completedTime = (side: "candidate" | "comparator") => {
    const hosts = result.hosts;
    if (!hosts.length || hosts.some(host => host[side].state !== "compared" || !Number.isFinite(host[side].medianNs))) return undefined;
    const values = hosts.map(host => host[side].medianNs!).sort((a, b) => a - b);
    const middle = Math.floor(values.length / 2);
    return values.length % 2 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
  };
  // Preserve the successful engine's measured host median, never a timeout limit
  // or an estimate. Without both engines there is no numerical comparison.
  return { ...result, candidateNs: completedTime("candidate"), comparatorNs: completedTime("comparator"),
    ratio: undefined, minimumRatio: undefined, maximumRatio: undefined, deltaNs: undefined, deltaNsPerByte: undefined };
}

export function outcomeLabel(result: Result, comparator: string): string {
  if (result.hosts.length > 0 && result.hosts.every(host =>
    host.candidate.state === "not-compatible" && host.comparator.state === "not-compatible")) {
    return "Neither engine is compatible";
  }
  const labels: string[] = [];
  for (const [side, name] of [["candidate", "Regulator"], ["comparator", comparator]] as const) {
    const states = new Set(result.hosts.map(host => host[side].state));
    if (states.has("not-compatible")) labels.push(`${name} not compatible`);
    else if (states.has("did-not-finish")) labels.push(`${name} did not finish`);
  }
  return labels.join("; ") || comparisonText(result);
}

export function sorted<T>(rows: T[], sort: Sort, value: (row: T, key: string) => SortValue): T[] {
  if (!sort) return rows;
  return [...rows].sort((left, right) => {
    const a = value(left, sort.key), b = value(right, sort.key);
    if (a == null) return b == null ? 0 : 1;
    if (b == null) return -1;
    const difference = typeof a === "number" && typeof b === "number"
      ? a - b : String(a).localeCompare(String(b), undefined, { numeric: true });
    return sort.descending ? -difference : difference;
  });
}

export function duration(ns: number | null | undefined, signed = false): string {
  if (ns == null) return "";
  const magnitude = Math.abs(ns);
  const sign = signed ? ns < 0 ? "−" : ns > 0 ? "+" : "" : "";
  const [divisor, unit] = magnitude >= 1e9 ? [1e9, "s"]
    : magnitude >= 1e6 ? [1e6, "ms"] : magnitude >= 1e3 ? [1e3, "µs"] : [1, "ns"];
  return `${sign}${numberFormat.format(magnitude / divisor)} ${unit}`;
}

export function comparisonText(result: Result): string {
  if (result.state === "not-compatible") return "not compatible";
  if (result.state === "did-not-finish") return "did not finish";
  if (result.warnings?.some(warning => warning.includes("disagree"))) return "no consistent winner";
  if (result.warnings?.length) return "timing variable";
  const ratio = result.ratio!;
  if (ratio > .98 && ratio < 1.02) return "< 2% difference";
  const factor = ratio < 1 ? 1 / ratio : ratio;
  const change = ratio < 1 ? "faster" : "slower";
  return factor < 1.1 ? `${percentFormat.format((factor - 1) * 100)}% ${change}`
    : `${numberFormat.format(factor)}× ${change}`;
}

export function tone(result: Result): string {
  if (result.state !== "compared") return result.state === "not-compatible" ? "na" : "timeout";
  if (result.warnings.length > 0) return "na";
  if (result.ratio! > .98 && result.ratio! < 1.02) return "tie";
  return result.ratio! < 1 ? "win" : "loss";
}

function evidence(result: Result): string {
  if (result.state !== "compared") return result.reason || result.state;
  return [
    `Regulator ${duration(result.candidateNs)}; comparator ${duration(result.comparatorNs)}.`,
    `Same-host ratio median ${result.ratio!.toFixed(4)}×; range ${result.minimumRatio!.toFixed(4)}–${result.maximumRatio!.toFixed(4)}×.`,
    `${result.hosts.length} independent hosts; the observed range is not a confidence interval.`,
    ...result.warnings,
  ].join(" ");
}

function Ratio({ result, comparator }: { result?: Result; comparator: string }) {
  if (!result) return null;
  return <span className={`scan-result ${tone(result)}`} title={evidence(result)}>
    {result.state === "compared" ? comparisonText(result) : outcomeLabel(result, comparator)}
  </span>;
}

function Header({ label, column, sort, setSort, title }: {
  label: string; column: string; sort: Sort; setSort: (sort: Sort) => void; title?: string;
}) {
  const active = sort?.key === column;
  return <th aria-sort={active ? sort.descending ? "descending" : "ascending" : "none"}>
    <button type="button" className="sort-button" title={title} onClick={() => setSort({ key: column, descending: active ? !sort.descending : false })}>
      {label}<span aria-hidden="true">{active ? sort.descending ? "↓" : "↑" : "↕"}</span>
    </button>
  </th>;
}

function operationDescription(row: Row): string {
  const operation = row.operation === "execute" ? row.model : row.operation;
  return ({
    count: "Count all non-overlapping matches in the input.",
    "count-spans": "Find all matches and sum their matched lengths.",
    "count-captures": "Find all matches and count participating capture groups, including the whole match.",
    grep: "Test each line for a match and count matching lines.",
    "grep-captures": "Find the first match on each line and count its participating capture groups.",
    compile: "Construct the compiled pattern without executing it.",
    reusedContains: "Test one input for a match using a warmed compiled pattern.",
    reusedCount: "Count matches in one input using a warmed compiled pattern.",
    singleUseContains: "Compile the pattern, then test one input for a match.",
    singleUseCount: "Compile the pattern, then count matches in one input.",
    singleUse: "Compile the LIKE pattern, then match one input.",
    matches: "Match one complete input using a warmed LIKE pattern.",
    contains: "Test one input for a match.",
    extract: "Extract the selected capture from the first match.",
    extractAll: "Extract the selected capture from every match into a Slice list.",
    split: "Split the input at matches and return a Slice list.",
    replace: "Replace matches and construct the resulting Slice.",
    replaceLambda: "Replace matches using a capture-based function and construct the resulting Slice.",
  } as Record<string, string>)[operation] || operationName(operation);
}

type EvidenceProps = { row: Row; reused?: Row; comparator: string; hideOperation?: boolean; missingSingle?: boolean };

function Name(props: EvidenceProps) {
  const [open, setOpen] = useState(false);
  const { row, hideOperation = false } = props;
  return <details className="row-evidence" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>{row.name.replace(/^everyday\//, "")}{!hideOperation && <small>{row.operation === "execute" ? row.model : operationName(row.operation)}</small>}</summary>
    {open && <RowEvidence {...props} />}
  </details>;
}

export function RowEvidence({ row, reused, comparator, missingSingle = false }: EvidenceProps) {
  const reason = (result: Result) => result.reason?.replace(/\bcandidate:/g, "Regulator:").replace(/\bcomparator:/g, `${comparator}:`);
  const commentary = workloadCommentary(reused ?? row);
  const singleUseCommentary = reused ? workloadCommentary(row).performance : undefined;
  return <div className="row-evidence-body">
      {missingSingle && <p>Single-use timings were not collected with Trino's DFA enabled. Single-use comparisons are left blank.</p>}
      {comparisonIssue(row) && <p>{comparisonIssue(row)!.reason}</p>}
      {row.result.reason && <p>{reused && "Single use: "}{reason(row.result)}</p>}
      {reused?.result.reason && <p>Multi-use: {reason(reused.result)}</p>}
      {(commentary.description || row.workload?.description) && <><h4>What this tests</h4><p>{commentary.description ?? row.workload?.description}</p></>}
      {singleUseCommentary && singleUseCommentary !== commentary.performance && <><h4>Single-use performance context</h4><p>{singleUseCommentary}</p></>}
      {commentary.performance && <><h4>{reused && singleUseCommentary !== commentary.performance ? "Multi-use performance context" : "Performance context"}</h4><p>{commentary.performance}</p></>}
      {(row.workload || row.mapping) && <><h4>Pattern</h4><pre>{row.workload?.pattern ?? row.mapping!.patternPreview}</pre></>}
      {row.patternSummary && <p>This pattern contains {numberFormat.format(row.patternSummary.bytes / 1000)} KB of regex text. Only a short preview is shown.
        {row.patternSummary.sourceUrl && <> See the <a href={row.patternSummary.sourceUrl}>benchmark definition and pattern source</a>.</>}
        {row.patternSummary.fullPatternAvailable && <> The full pattern is also included in the full results download.</>}</p>}
      {row.workload?.flags?.length ? <p>Flags: {row.workload.flags.join(", ")}</p> : null}
      <p>{operationDescription(row)}{reused && ` ${operationDescription(reused)}`}</p>
      {row.inputBytes != null && <p>{new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 }).format(row.inputBytes)} input bytes per operation{!Number.isInteger(row.inputBytes) && " on average"}.</p>}
      {row.workload?.inputDescription && <p>{row.workload.inputDescription}</p>}
      {row.workload?.inputs && <><h4>Rotating inputs</h4>{row.workload.inputs.map((input, index) => <pre key={index}>{input.text}</pre>)}</>}
      {row.measurementWarnings?.length ? <><h4>{reused ? "Single-use measurement variation" : "Measurement variation"}</h4><p>{row.measurementWarnings.join(". ")}.</p></> : null}
      {reused?.measurementWarnings?.length ? <><h4>Multi-use measurement variation</h4><p>{reused.measurementWarnings.join(". ")}.</p></> : null}
      {row.mapping && !row.result.reason && !["identical", "direct"].includes(row.mapping.status) && <p>{row.mapping.reason}</p>}
    </div>;
}

function operationName(operation: string): string {
  return ({ reusedContains: "contains", reusedCount: "count", singleUseContains: "single-use contains", singleUseCount: "single-use count", compile: "construction", singleUse: "single use", matches: "reused match" } as Record<string, string>)[operation] || operation;
}

function ResultsTable({ rows, comparator }: { rows: Row[]; comparator: string }) {
  const [sort, setSort] = useState<Sort>(null);
  const bytes = rows.some(row => !comparisonIssue(row) && row.result.deltaNsPerByte != null);
  const numeric = rows.some(row => row.result.candidateNs != null || row.result.comparatorNs != null);
  const comparable = rows.some(row => !comparisonIssue(row));
  const ordered = sorted(rows, sort, (row, key) => key === "name" ? row.name : comparisonIssue(row) && ["ratio", "deltaNs", "deltaNsPerByte"].includes(key) ? null : (row.result as unknown as Record<string, SortValue>)[key]);
  return <div className="table-scroll"><table className="measurement-table">
    <thead><tr>
      <Header label="Pattern / workload" column="name" sort={sort} setSort={setSort} />
      {numeric && <><Header label="Regulator" column="candidateNs" sort={sort} setSort={setSort} />
        <Header label={comparator} column="comparatorNs" sort={sort} setSort={setSort} /></>}
      <Header label={`vs ${comparator}`} column="ratio" sort={sort} setSort={setSort} />
      {numeric && comparable && <Header label="Δ / operation" column="deltaNs" sort={sort} setSort={setSort} />}
      {bytes && <Header label="Δ ns / byte" column="deltaNsPerByte" sort={sort} setSort={setSort} />}
    </tr></thead>
    <tbody>{ordered.map(row => <tr key={row.id} className={`comparison-row ${comparisonIssue(row) ? "na" : tone(row.result)}`}>
      <td><Name row={row} comparator={comparator} /></td>
      {numeric && <><td className="measurement">{duration(row.result.candidateNs)}</td><td className="measurement">{duration(row.result.comparatorNs)}</td></>}
      <td className="measurement">{comparisonIssue(row) ? <span>{comparisonIssue(row)!.label}</span> : <Ratio result={row.result} comparator={comparator} />}</td>
      {numeric && comparable && <td className="measurement delta">{!comparisonIssue(row) && duration(row.result.deltaNs, true)}</td>}
      {bytes && <td className="measurement delta" title={row.result.deltaNsPerByte != null ? `${row.result.deltaNsPerByte} ns per input byte` : ""}>
        {!comparisonIssue(row) && row.result.deltaNsPerByte != null && `${row.result.deltaNsPerByte < 0 ? "−" : row.result.deltaNsPerByte > 0 ? "+" : ""}${numberFormat.format(Math.abs(row.result.deltaNsPerByte))}`}
      </td>}
    </tr>)}</tbody>
  </table></div>;
}

// Only these collected LIKE workloads select Trino's DFA when enabled.
function likeDfaVariant(row: Row): boolean {
  return row.language === "like" && /^trino-like\/ANY_(ASCII|MULTIBYTE)(\/optimized)?$/.test(row.caseId);
}

type Lifecycle = { id: string; name: string; single?: Row; reused: Row; breakEven: number | null; reason: string };

// Default reading order follows workload purpose, never measured performance.
const likeWorkloadOrder = [
  "PREFIX_LARGE", "SUFFIX_LARGE", "EXACT_MATCH", "CONTAINS_LATE", "CONTAINS_ABSENT",
  "ORDERED_LATE", "MIXED_LATE", "MIXED_ABSENT", "WILDCARD_CHAIN", "ANY_ASCII", "ANY_MULTIBYTE",
];

export function lifecycleRows(rows: Row[]): Lifecycle[] {
  const byKey = new Map(rows.map(row => [`${row.caseId}/${row.operation}`, row]));
  const lifecycle: Lifecycle[] = rows.filter(row => ["singleUseContains", "singleUseCount", "singleUse"].includes(row.operation)).map(single => {
    const reusedName = single.operation === "singleUse" ? "matches" : single.operation.replace("singleUse", "reused");
    const reused = byKey.get(`${single.caseId}/${reusedName}`);
    if (!reused) throw new Error(`Missing reused lifecycle partner for ${single.id}`);
    const bothCompared = single.result.state === "compared" && reused.result.state === "compared";
    const unresolved = comparisonIssue(single) || comparisonIssue(reused) || single.result.warnings.length > 0 || reused.result.warnings.length > 0;
    const deficit = single.result.deltaNs!;
    const saving = -reused.result.deltaNs!;
    // One single-use operation already includes the first execution. Each
    // subsequent use adds the measured reused-operation difference.
    const breakEven = bothCompared && !unresolved ? deficit <= 0 ? 1 : saving > 0 ? 1 + Math.ceil(deficit / saving) : null : null;
    const reason = !bothCompared ? "No comparison for one lifecycle phase" : unresolved ? "Timing varies; no reliable crossover estimate"
      : deficit <= 0 ? saving < 0 ? "Ahead on first use, but each additional use reduces that lead and can reverse it" : "Ahead from first use"
      : saving <= 0 ? "No measured crossover" : "Estimated from independently measured single-use and reused operations";
    const suffix = single.language !== "like" ? ` · ${operationName(reused.operation)}`
      : likeDfaVariant(single) ? ` · DFA ${single.caseId.endsWith("/optimized") ? "enabled" : "disabled"}` : "";
    const name = ({
      "trino-like/SUFFIX_LARGE": "suffix, 6 bytes · 32 KiB input",
      "trino-like/PREFIX_LARGE": "prefix, 6 bytes · 32 KiB input",
      "trino-like/ORDERED_DENSE_FALSE": "ordered dense false · stress test",
    } as Record<string, string>)[single.caseId] ?? single.name.replace(/^everyday\//, "");
    return { id: single.id, name: name + suffix, single, reused, breakEven, reason };
  });
  for (const reused of rows.filter(row => likeDfaVariant(row) && row.caseId.endsWith("/optimized") && row.operation === "matches")) {
    if (byKey.has(`${reused.caseId}/singleUse`)) continue;
    const disabledIndex = lifecycle.findIndex(row => row.reused.caseId === reused.caseId.replace(/\/optimized$/, ""));
    const name = disabledIndex >= 0 ? lifecycle[disabledIndex].name.replace(/ · DFA disabled$/, "") : reused.name;
    const enabled = { id: reused.id, name: `${name} · DFA enabled`, reused, breakEven: null, reason: "Single-use timings not collected" };
    lifecycle.splice(disabledIndex >= 0 ? disabledIndex + 1 : lifecycle.length, 0, enabled);
  }
  if (lifecycle.every(row => row.reused.language === "like")) {
    const rank = (row: Lifecycle) => {
      const scenario = row.reused.caseId.split("/")[1];
      if (scenario === "ORDERED_DENSE_FALSE") return likeWorkloadOrder.length + 1;
      const index = likeWorkloadOrder.indexOf(scenario);
      return index < 0 ? likeWorkloadOrder.length : index;
    };
    lifecycle.sort((left, right) => rank(left) - rank(right)
      || Number(left.reused.caseId.endsWith("/optimized")) - Number(right.reused.caseId.endsWith("/optimized")));
  }
  return lifecycle;
}

function LifecycleTable({ rows, like, comparator }: { rows: Row[]; like: boolean; comparator: string }) {
  const [sort, setSort] = useState<Sort>(null);
  const lifecycle = lifecycleRows(rows);
  const bytes = lifecycle.some(row => row.reused.result.deltaNsPerByte != null);
  const ordered = sorted(lifecycle, sort, (row, key) => {
    if (key === "name") return row.name;
    if (key === "breakEven") return row.breakEven;
    if (key === "reusedBytes") return row.reused.result.deltaNsPerByte;
    return key === "single" ? row.single?.result.ratio : key === "singleDelta" ? row.single?.result.deltaNs
      : key === "reused" ? row.reused.result.ratio : row.reused.result.deltaNs;
  });
  return <div className="table-scroll"><table className="measurement-table lifecycle-table"><thead><tr>
    {[[like ? "Pattern" : "Pattern / operation", "name"], ["Single use", "single"], ["Single-use Δ", "singleDelta"], ["Multi-use", "reused"], ["Per-use Δ", "reusedDelta"], ...(bytes ? [["Δ ns / byte", "reusedBytes"]] : []), ...(!like ? [["Break-even uses", "breakEven"]] : [])].map(([label, column]) => <Header key={column} label={label} column={column} sort={sort} setSort={setSort} title={column === "reusedBytes" ? "Multi-use time difference per input byte" : undefined} />)}
  </tr></thead><tbody>{ordered.map(row => <tr key={row.id}>
    <td><Name row={{ ...(row.single ?? row.reused), name: row.name }} reused={row.single ? row.reused : undefined} comparator={comparator} hideOperation={like} missingSingle={!row.single} /></td>
    <td className="measurement"><Ratio result={row.single?.result} comparator={comparator} /></td>
    <td className={`measurement delta ${row.single ? tone(row.single.result) : ""}`}>{duration(row.single?.result.deltaNs, true)}</td>
    <td className="measurement"><Ratio result={row.reused.result} comparator={comparator} /></td>
    <td className={`measurement delta ${tone(row.reused.result)}`}>{duration(row.reused.result.deltaNs, true)}</td>
    {bytes && <td className={`measurement delta ${tone(row.reused.result)}`} title={row.reused.result.deltaNsPerByte != null ? `${row.reused.result.deltaNsPerByte} ns per input byte` : ""}>
      {row.reused.result.deltaNsPerByte != null && `${row.reused.result.deltaNsPerByte < 0 ? "−" : row.reused.result.deltaNsPerByte > 0 ? "+" : ""}${numberFormat.format(Math.abs(row.reused.result.deltaNsPerByte))}`}
    </td>}
    {!like && <td className="measurement" title={row.reason}>{!row.single ? "" : row.breakEven === 1 ? "first use" : row.breakEven != null ? row.breakEven.toLocaleString() : "not determined"}</td>}
  </tr>)}</tbody></table></div>;
}

function selectionFromUrl(): Selection {
  const parameters = new URLSearchParams(typeof window === "undefined" ? "" : window.location.hash.slice(1));
  return { language: languageOrder.includes(parameters.get("language") || "") ? parameters.get("language")! : "re2",
    cpu: cpuOrder.includes(parameters.get("cpu") || "") ? parameters.get("cpu")! : "c9g",
    memory: parameters.get("memory") === "safe" ? "safe" : "native" };
}

export function LanguageReport({ data, selection = selectionFromUrl(), onSelect, loading, error, onRetry }: {
  data: ReportData; selection?: Selection; onSelect?: (selection: Selection) => void;
  loading?: boolean; error?: string | null; onRetry?: () => void;
}) {
  const select = (changes: Partial<Selection>) => {
    const next = { ...selection, ...changes };
    onSelect?.(next);
    window.location.hash = new URLSearchParams(next).toString();
  };
  const { language, cpu, memory } = selection;
  const comparator = data.languages[language].comparator;
  const preview = data.presentation?.reviewPreview === true;
  const preliminary = data.publication?.status === "preliminary";
  // Preliminary tables show median estimates, but never assert a consistent
  // winner when hosts disagree. Full warnings remain in row details and downloads.
  const selectedRows = data.rows.filter(row => row.language === language && row.platform === cpu && row.memoryMode === memory && workloadSection(row) !== "diagnostic-only")
    .map(row => ({ ...row, measurementWarnings: row.result.warnings, result: { ...displayResult(row.result),
      ...(preview ? { warnings: [] } : preliminary ? { warnings: row.result.warnings.filter(warning => warning.includes("disagree")) } : {}) } }));
  const pending = selectedRows.filter(row => comparisonIssue(row));
  const rows = selectedRows.filter(row => !comparisonIssue(row));
  const ordinary = rows.filter(row => row.population === "ordinary-scalar" && row.operation.startsWith("reused"));
  const bulk = rows.filter(row => workloadSection(row) === "bulk-text");
  const operations = rows.filter(row => row.population === "trino-operations");
  const diagnostic = rows.filter(row => workloadSection(row) === "diagnostics-and-stress")
    .map(row => row.population === "bulk-text" ? { ...row, family: "reported-regression" } : row);
  const stress = rows.filter(row => workloadSection(row) === "synthetic-stress");
  const lifecycle = rows;
  const hasLifecycle = lifecycle.some(row => row.operation.startsWith("singleUse"));
  const groupNames = [...new Set(diagnostic.map(row => row.family))].sort();
  const section = (title: string, explanation: React.ReactNode, contents: React.ReactNode) => <section className="scan-section"><div className="scan-title"><h2>{title}</h2></div><p className="section-note">{explanation}</p>{contents}</section>;
  return <main className="scan-page language-page">
    <header className="scan-header"><div><span className="snapshot-badge">{data.sources.currentLabel}</span><h1>Regulator benchmarks</h1>
      <p className="source-line">{!preview && !preliminary && `Engine ${data.sources.currentCandidate.slice(0, 12)} · `}{data.sources.jdk}</p>
      {preliminary && <p className="source-line">{data.publication!.note}</p>}</div>
      <div className="benchmark-controls"><div className="platform-switch">{cpuOrder.map(platform => <button key={platform} className={cpu === platform ? "active" : ""} onClick={() => select({ cpu: platform })}>{data.platforms[platform]}</button>)}</div>
        <label className="safe-toggle"><input type="checkbox" checked={memory === "safe"} onChange={event => select({ memory: event.target.checked ? "safe" : "native" })} /> Pure Java · no native memory</label>
      </div></header>
    <nav className="language-tabs" aria-label="Pattern API">{languageOrder.map(id => <button key={id} className={id === language ? "active" : ""} onClick={() => select({ language: id })}>{data.languages[id].label}</button>)}</nav>
    <div className="comparison-intro">
      <h2 className="comparison-heading">Regulator vs {comparator}</h2>
      {language === "re2" && <><p>Regulator receives UTF-8 input in Slice.</p><p>Native RE2 receives the same UTF-8 bytes.</p></>}
      {language === "java" && <><p>Regulator receives UTF-8 input in Slice.</p><p>JDK Pattern receives precomputed UTF-16 Java strings. Input conversion is outside the timings.</p></>}
      {language === "trino" && <><p>Regulator receives prepared UTF-8 input in Slice.</p><p>Trino's Joni library receives the same UTF-8 bytes.</p></>}
      {language === "like" && <><p>Regulator receives UTF-8 input and patterns in Slice.</p><p>Trino LikeMatcher receives UTF-8 input bytes and patterns as Java strings.</p></>}
    </div>
    {pending.length > 0 && <p className="section-note">{pending.length} measurements are separated below because they need remeasurement or use different API work. Their recorded times remain available, but they are not included in speed comparisons.</p>}
    {ordinary.length > 0 && section("Everyday regex operations", "Compiled patterns over rotating inputs. Contains and count are measured separately.", <ResultsTable rows={ordinary} comparator={comparator} />)}
    {hasLifecycle && section(language === "like" ? "LIKE pattern lifecycle" : "Regex pattern lifecycle", <>Single use includes compilation and the first operation. Multi-use measures each operation with a warmed compiled pattern.{language !== "like" && " Break-even is an estimate, not a measured execution count."}
      {language === "like" && <><br />Trino's DFA optimization applies only to some patterns. Those patterns have separate enabled and disabled rows.</>}</>, <LifecycleTable rows={lifecycle} like={language === "like"} comparator={comparator} />)}
    {operations.length > 0 && section("Trino regex operations", "Complete public operations using compiled patterns on rotating Slice values, compared with pinned Joni.", <ResultsTable rows={operations} comparator={comparator} />)}
    {bulk.length > 0 && section("Text processing workloads", "Document searches, tokenization, log parsing and capture workloads using compiled patterns. Expand a row for its pattern, operation and input details.", <ResultsTable rows={bulk} comparator={comparator} />)}
    {(diagnostic.length > 0 || stress.length > 0) && section(preview || preliminary ? "Adversarial and stress workloads" : "Adversarial, stress and diagnostic workloads", "Pathological inputs and synthetic stress tests. These are not representative of typical application performance; interpret them individually.", <div className="adversarial-groups">
      {groupNames.map(family => <details className="diagnostic-group" key={family}><summary>{family}<span>{diagnostic.filter(row => row.family === family).length} rows</span></summary><ResultsTable rows={diagnostic.filter(row => row.family === family)} comparator={diagnostic.find(row => row.family === family)?.comparator || comparator} /></details>)}
      {stress.length > 0 && <details className="diagnostic-group"><summary>Synthetic stress<span>{stress.length} rows</span></summary><ResultsTable rows={stress} comparator={comparator} /></details>}
    </div>)}
    {pending.length > 0 && section("Measurements awaiting alignment", "Historical evidence, not equivalent-work speed comparisons. The reason is shown beside each measurement. No timings have been corrected or estimated.", <ResultsTable rows={pending} comparator={comparator} />)}
    {loading ? <p role="status">Loading benchmark results…</p> : error ? <p role="alert">Unable to load results: {error} <button onClick={onRetry}>Retry</button></p> : selectedRows.length === 0 && <p>No measurements are available for this selection.</p>}
    <footer className="scan-footer">
      {data.fullResultsUrl ? <a href={data.fullResultsUrl} download>Download full results (JSON.gz)</a> : <button type="button" onClick={() => {
        const url = URL.createObjectURL(new Blob([JSON.stringify(downloadData(data))], { type: "application/json" }));
        const link = document.createElement("a");
        link.href = url;
        link.download = "regulator-benchmark-results.json";
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }}>Download raw results (JSON)</button>}
    </footer>
  </main>;
}

declare global { interface Window { __REGULATOR_REPORT__?: ReportData } }

type SiteManifest = Omit<ReportData, "rows"> & { siteSchemaVersion: 1; pages: Record<string, string> };
export const selectionKey = ({ language, cpu, memory }: Selection) => `${language}-${cpu}-${memory}`;

// The manifest renders the loading view before any page arrives. Check the same
// display contract for both; full benchmark-evidence validation stays in tooling.
function validateDisplayMetadata(data: Omit<ReportData, "rows">) {
  const text = (value: unknown) => typeof value === "string" && value.trim().length > 0;
  if (!["currentLabel", "currentCandidate", "jdk"].every(key => text(data?.sources?.[key as keyof ReportData["sources"]]))
      || !cpuOrder.every(cpu => text(data?.platforms?.[cpu]))
      || !languageOrder.every(language => text(data?.languages?.[language]?.label) && text(data?.languages?.[language]?.comparator))
      || (data?.publication !== undefined && !text(data.publication?.note))) {
    throw new Error("Invalid report display metadata");
  }
}

export function createReportLoader(request: typeof fetch) {
  let manifestPromise: Promise<SiteManifest> | undefined;
  const pages = new Map<string, Promise<ReportData>>();
  async function read(url: string, options?: RequestInit) {
    const response = await request(url, options);
    if (!response.ok) throw new Error(`Request failed: ${response.status}`);
    return response.json();
  }
  return {
    manifest(): Promise<SiteManifest> {
      manifestPromise ??= read("./data/manifest.json", { cache: "no-cache" }).then((value: SiteManifest) => {
        validateDisplayMetadata(value);
        if (value.siteSchemaVersion !== 1 || value.schemaVersion !== 2 || !value.pages
            || !/^\.\/data\/[A-Za-z0-9._-]+\.json\.gz$/.test(value.fullResultsUrl || "")) throw new Error("Unsupported report manifest");
        return value;
      }).catch(error => { manifestPromise = undefined; throw error; });
      return manifestPromise;
    },
    page(selection: Selection, manifest: SiteManifest): Promise<ReportData> {
      const selectionId = selectionKey(selection);
      const filename = manifest.pages[selectionId];
      if (!filename || !/^[A-Za-z0-9._-]+\.json$/.test(filename)) return Promise.reject(new Error("Missing report selection"));
      const key = `${selectionId}/${filename}`;
      if (!pages.has(key)) {
        const requestedManifest = manifestPromise;
        pages.set(key, read(`./data/${filename}`).then((value: ReportData) => {
          validateDisplayMetadata(value);
          if (value.schemaVersion !== 2 || !value.selection || selectionKey(value.selection) !== selectionId
              || !Array.isArray(value.rows) || value.rows.some(row => row.language !== selection.language
                || row.platform !== selection.cpu || row.memoryMode !== selection.memory)) throw new Error("Incorrect report selection");
          return value;
        }).catch(error => {
          pages.delete(key);
          // A deployment may have removed this manifest's files. Let the next
          // attempt revalidate it, without evicting a newer in-flight manifest.
          if (manifestPromise === requestedManifest) manifestPromise = undefined;
          throw error;
        }));
      }
      return pages.get(key)!;
    },
  };
}

function App() {
  const embedded = window.__REGULATOR_REPORT__;
  const [selection, setSelection] = useState(selectionFromUrl);
  const [loader] = useState(() => createReportLoader(fetch));
  const [manifest, setManifest] = useState<SiteManifest | null>(null);
  const [loaded, setLoaded] = useState<ReportData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const onChange = () => setSelection(selectionFromUrl());
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  useEffect(() => {
    if (embedded) return;
    let cancelled = false;
    setError(null);
    void loader.manifest().then(async manifest => {
      if (cancelled) return;
      setManifest(manifest);
      const data = await loader.page(selection, manifest);
      if (!cancelled) setLoaded(data);
    }).catch(failure => { if (!cancelled) setError(String(failure)); });
    // Requests are cached across selections. A late response cannot replace the active view.
    return () => { cancelled = true; };
  }, [selection.language, selection.cpu, selection.memory, attempt, embedded, loader]);
  const active = embedded || (loaded?.selection && selectionKey(loaded.selection) === selectionKey(selection) ? loaded : null);
  const data = active || (manifest && { ...manifest, rows: [] });
  const retry = () => setAttempt(value => value + 1);
  if (!data) return <main className="loading-page">{error
    ? <p role="alert">Unable to load results: {error} <button onClick={retry}>Retry</button></p>
    : <p role="status">Loading benchmark results…</p>}</main>;
  return <LanguageReport data={data} selection={selection} onSelect={setSelection}
    loading={!active && !error} error={error} onRetry={retry} />;
}

const root = typeof document === "undefined" ? null : document.getElementById("root");
if (root) createRoot(root).render(<StrictMode><App /></StrictMode>);
