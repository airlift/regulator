// The publication JSON is evidence. These projections contain only what the UI reads.
const rowFields = ["id", "caseId", "name", "operation", "model", "population", "family",
  "language", "platform", "memoryMode", "inputBytes", "source", "comparator", "workContract"];
const resultFields = ["state", "reason", "candidateNs", "comparatorNs", "ratio", "minimumRatio",
  "maximumRatio", "deltaNs", "deltaNsPerByte", "warnings", "estimator"];
const pick = (value, fields) => Object.fromEntries(fields.filter(key => value[key] !== undefined).map(key => [key, value[key]]));

export function compactReport(data, report, sourceLinks = {}) {
  return {
    ...pick(data, ["schemaVersion", "sources", "platforms", "memoryModes", "languages", "publication", "presentation"]),
    methodology: [], provenance: {},
    rows: data.rows.filter(row => report.workloadSection(row) !== "diagnostic-only").map(row => {
      const compact = { ...pick(row, rowFields),
        result: { ...pick(row.result, resultFields), hosts: row.result.hosts.map(host => ({
          ...pick(host, ["instanceType"]),
          candidate: pick(host.candidate, ["state", "medianNs"]),
          comparator: pick(host.comparator, ["state", "medianNs"]),
        })) },
      };
      if (row.result.uncertainty) compact.result.uncertainty = pick(row.result.uncertainty,
        ["method", "level", "ratioInterval", "candidateIntervalNs", "comparatorIntervalNs", "forkMeanRangeNs"]);
      // Presence and cohort gate existing compatibility checks and qualified commentary.
      if (row.measurementSource) compact.measurementSource = pick(row.measurementSource, ["cohort"]);
      if (row.mapping) compact.mapping = pick(row.mapping, ["status", "reason", "patternPreview", "patternBytes"]);
      if (row.workload) compact.workload = pick(row.workload, ["pattern", "description", "performanceContext", "inputDescription", "flags", "inputs"]);
      const pattern = row.workload?.pattern ?? row.mapping?.patternPreview;
      const fullPatternAvailable = row.workload?.pattern !== undefined;
      const patternBytes = fullPatternAvailable ? Buffer.byteLength(pattern) : row.mapping?.patternBytes;
      if (pattern !== undefined && patternBytes > 4096) {
        const preview = fullPatternAvailable ? [...pattern].slice(0, 240).join("") + "…" : pattern;
        if (fullPatternAvailable) compact.workload.pattern = preview;
        if (compact.mapping) compact.mapping.patternPreview = preview;
        compact.patternSummary = { bytes: patternBytes, sourceUrl: sourceLinks[row.caseId], fullPatternAvailable };
      }
      return compact;
    }),
  };
}

export function reportPages(data) {
  return Object.fromEntries(Object.keys(data.languages).flatMap(language =>
    Object.keys(data.platforms).flatMap(cpu => Object.keys(data.memoryModes).map(memory => {
      const selection = { language, cpu, memory };
      const key = `${language}-${cpu}-${memory}`;
      return [key, { ...data, selection, rows: data.rows.filter(row =>
        row.language === language && row.platform === cpu && row.memoryMode === memory) }];
    }))));
}
