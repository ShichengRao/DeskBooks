import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

/**
 * The privacy contract for automation/: only src/connector-http.mjs may
 * open network connections. Everything else must be file/CLI work.
 *
 * Known limit: this guard checks import/require specifiers and a few
 * runtime tokens; it cannot see dynamic import(expression) tricks or the
 * global fetch(). Those are covered by review plus the PR-template
 * network checkbox.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const automationRoot = path.resolve(here, "..");
const SCANNED_DIRS = ["src", "fetchers", "bin"];
const NETWORK_EXEMPT = new Set([path.join("src", "connector-http.mjs")]);

const FORBIDDEN_SPECIFIERS = new Set([
  "http",
  "https",
  "net",
  "tls",
  "dgram",
  "http2",
  "node:http",
  "node:https",
  "node:net",
  "node:tls",
  "node:dgram",
  "node:http2",
  "axios",
  "node-fetch",
  "got",
  "undici",
  "ws",
]);
const FORBIDDEN_TOKENS = [/\bXMLHttpRequest\b/, /\bnew\s+WebSocket\b/, /\bglobalThis\.fetch\b/];

async function listModuleFiles() {
  const files = [];
  for (const dirName of SCANNED_DIRS) {
    const dir = path.join(automationRoot, dirName);
    for (const name of await readdir(dir)) {
      if (name.endsWith(".mjs")) {
        files.push(path.join(dirName, name));
      }
    }
  }
  return files;
}

function importSpecifiers(sourceText) {
  const specifiers = [];
  const patterns = [
    /import\s+[^;'"]*?from\s+["']([^"']+)["']/g,
    /import\s+["']([^"']+)["']/g,
    /import\(\s*["']([^"']+)["']\s*\)/g,
    /require\(\s*["']([^"']+)["']\s*\)/g,
  ];
  for (const pattern of patterns) {
    for (const match of sourceText.matchAll(pattern)) {
      specifiers.push(match[1]);
    }
  }
  return specifiers;
}

test("only connector-http.mjs may import network modules", async () => {
  const violations = [];
  for (const relative of await listModuleFiles()) {
    const text = await readFile(path.join(automationRoot, relative), "utf8");
    const exempt = NETWORK_EXEMPT.has(relative);
    for (const spec of importSpecifiers(text)) {
      if (FORBIDDEN_SPECIFIERS.has(spec) && !exempt) {
        violations.push(`${relative} imports ${spec}`);
      }
    }
    for (const token of FORBIDDEN_TOKENS) {
      if (token.test(text) && !exempt) {
        violations.push(`${relative} uses ${token}`);
      }
    }
  }
  assert.deepEqual(violations, []);
});

test("non-exempt modules import only relative paths or safe node builtins", async () => {
  const violations = [];
  for (const relative of await listModuleFiles()) {
    if (NETWORK_EXEMPT.has(relative)) {
      continue;
    }
    const text = await readFile(path.join(automationRoot, relative), "utf8");
    for (const spec of importSpecifiers(text)) {
      const isRelative = spec.startsWith("./") || spec.startsWith("../");
      const isSafeBuiltin = spec.startsWith("node:") && !FORBIDDEN_SPECIFIERS.has(spec);
      if (!isRelative && !isSafeBuiltin) {
        violations.push(`${relative} imports ${spec}`);
      }
    }
  }
  assert.deepEqual(violations, []);
});
