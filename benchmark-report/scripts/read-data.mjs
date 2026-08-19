import { readFile } from "node:fs/promises";
import { gunzipSync } from "node:zlib";

// Compression is a repository storage detail; browsers still receive JSON.
export async function readData(path) {
  const content = await readFile(path);
  return String(path).endsWith(".gz") ? gunzipSync(content) : content;
}
