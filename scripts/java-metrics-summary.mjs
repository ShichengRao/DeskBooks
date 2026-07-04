#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const javaRoot = path.join(root, "backend-java");
const reports = path.join(javaRoot, "build", "reports");

function readIfExists(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, "utf8") : "";
}

function firstExisting(...files) {
  return files.find((file) => fs.existsSync(file)) ?? files[0];
}

function attrMap(raw) {
  return Object.fromEntries([...raw.matchAll(/\s([\w:-]+)="([^"]*)"/g)].map(([, key, value]) => [key, value]));
}

function parseCsv(text) {
  const rows = [];
  let field = "";
  let row = [];
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    const next = text[i + 1];
    if (quoted) {
      if (char === '"' && next === '"') {
        field += '"';
        i++;
      } else if (char === '"') {
        quoted = false;
      } else {
        field += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ",") {
      row.push(field);
      field = "";
    } else if (char === "\n") {
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else if (char !== "\r") {
      field += char;
    }
  }
  if (field.length > 0 || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  if (rows.length === 0) {
    return [];
  }
  const headers = rows[0].map((header) => header.trim());
  return rows.slice(1)
    .filter((values) => values.some((value) => value !== ""))
    .map((values) => Object.fromEntries(headers.map((header, index) => [header, values[index] ?? ""])));
}

function number(row, names) {
  for (const name of names) {
    const value = row[name] ?? row[name.toLowerCase()] ?? row[name.toUpperCase()];
    if (value !== undefined && value !== "") {
      const parsed = Number(value);
      if (Number.isFinite(parsed)) {
        return parsed;
      }
    }
  }
  return 0;
}

function classNameFromFile(file) {
  const normalized = file.replaceAll("\\", "/");
  const marker = "/src/main/java/";
  const index = normalized.indexOf(marker);
  const relative = index >= 0 ? normalized.slice(index + marker.length) : normalized;
  return relative.replace(/\.java$/, "").replaceAll("/", ".");
}

function keyFor(kind, name) {
  return `${kind}:${name}`;
}

const candidates = new Map();
function candidate(kind, name) {
  const key = keyFor(kind, name);
  if (!candidates.has(key)) {
    candidates.set(key, {
      kind,
      name,
      score: 0,
      loc: 0,
      complexity: 0,
      coupling: 0,
      duplicateLines: 0,
      lineCoverage: null,
      pmdViolations: 0,
      notes: [],
    });
  }
  return candidates.get(key);
}

function addNote(item, note) {
  if (!item.notes.includes(note)) {
    item.notes.push(note);
  }
}

function loadCk() {
  const methodCsv = firstExisting(
    path.join(reports, "ck", "method.csv"),
    path.join(reports, "ckmethod.csv"));
  const classCsv = firstExisting(
    path.join(reports, "ck", "class.csv"),
    path.join(reports, "ckclass.csv"));

  for (const row of parseCsv(readIfExists(methodCsv))) {
    const className = row.class || row.Class || row.className || row.type || "";
    const methodName = (row.method || row.Method || row.methodName || "").split("/")[0];
    if (!className || !methodName) {
      continue;
    }
    const item = candidate("method", `${className}#${methodName}`);
    item.loc = Math.max(item.loc, number(row, ["loc"]));
    item.complexity = Math.max(item.complexity, number(row, ["wmc", "variablesQty", "maxNestedBlocksQty"]));
    item.coupling = Math.max(item.coupling, number(row, ["cbo", "rfc", "methodInvocationsQty", "methodsInvokedQty"]));
  }

  for (const row of parseCsv(readIfExists(classCsv))) {
    const className = row.class || row.Class || row.className || row.type || "";
    if (!className) {
      continue;
    }
    const item = candidate("class", className);
    item.loc = Math.max(item.loc, number(row, ["loc"]));
    item.complexity = Math.max(item.complexity, number(row, ["wmc"]));
    item.coupling = Math.max(item.coupling, number(row, ["cbo", "fanout", "fanOut", "rfc"]));
  }
}

function loadJacoco() {
  const xml = readIfExists(path.join(reports, "jacoco", "test", "jacocoTestReport.xml"));
  for (const pkgMatch of xml.matchAll(/<package\b([^>]*)>([\s\S]*?)<\/package>/g)) {
    const packageName = attrMap(pkgMatch[1]).name?.replaceAll("/", ".") ?? "";
    for (const classMatch of pkgMatch[2].matchAll(/<class\b([^>]*)>([\s\S]*?)<\/class>/g)) {
      const classAttrs = attrMap(classMatch[1]);
      const className = (classAttrs.name ?? "").replaceAll("/", ".") || `${packageName}.${classAttrs.sourcefilename?.replace(/\.java$/, "") ?? ""}`;
      for (const methodMatch of classMatch[2].matchAll(/<method\b([^>]*)>([\s\S]*?)<\/method>/g)) {
        const methodAttrs = attrMap(methodMatch[1]);
        if (!methodAttrs.name || methodAttrs.name === "<init>") {
          continue;
        }
        const lineCounter = [...methodMatch[2].matchAll(/<counter\b([^>]*)\/>/g)]
          .map((match) => attrMap(match[1]))
          .find((attrs) => attrs.type === "LINE");
        if (!lineCounter) {
          continue;
        }
        const missed = Number(lineCounter.missed ?? 0);
        const covered = Number(lineCounter.covered ?? 0);
        const total = missed + covered;
        if (total === 0) {
          continue;
        }
        candidate("method", `${className}#${methodAttrs.name}`).lineCoverage = covered / total;
      }
    }
  }
}

function loadPmd() {
  const xml = readIfExists(path.join(reports, "pmd", "main.xml"));
  for (const violationMatch of xml.matchAll(/<violation\b([^>]*)>([\s\S]*?)<\/violation>/g)) {
    const attrs = attrMap(violationMatch[1]);
    if (!attrs.class) {
      continue;
    }
    const className = attrs.package ? `${attrs.package}.${attrs.class}` : attrs.class;
    const target = attrs.method ? candidate("method", `${className}#${attrs.method}`) : candidate("class", className);
    target.pmdViolations++;
    const priority = Number(attrs.priority ?? 5);
    target.score += Math.max(1, 6 - priority) * 10;
    addNote(target, `PMD ${attrs.rule ?? "violation"}`);
  }
}

function loadCpd() {
  const xml = readIfExists(path.join(reports, "cpd", "main.xml"));
  for (const duplicationMatch of xml.matchAll(/<duplication\b([^>]*)>([\s\S]*?)<\/duplication>/g)) {
    const duplication = attrMap(duplicationMatch[1]);
    const lines = Number(duplication.lines ?? 0);
    for (const fileMatch of duplicationMatch[2].matchAll(/<file\b([^>]*)\/>/g)) {
      const file = attrMap(fileMatch[1]).path;
      if (!file) {
        continue;
      }
      const item = candidate("class", classNameFromFile(file));
      item.duplicateLines += lines;
      addNote(item, `CPD ${lines} duplicated lines`);
    }
  }
}

loadCk();
loadJacoco();
loadPmd();
loadCpd();

for (const item of candidates.values()) {
  item.score += item.loc * (item.kind === "class" ? 0.4 : 0.8);
  item.score += item.complexity * 6;
  item.score += item.coupling * 3;
  item.score += item.duplicateLines * 2;
  if (item.lineCoverage !== null) {
    item.score += (1 - item.lineCoverage) * 30;
  }
}

const ranked = [...candidates.values()]
  .filter((item) => item.score > 0)
  .sort((a, b) => b.score - a.score)
  .slice(0, 15);

console.log("Java metrics hotspot summary");
console.log(`Reports root: ${reports}`);
if (ranked.length === 0) {
  console.log("No metric candidates found. Check that javaMetrics generated reports successfully.");
  process.exit(0);
}

for (const [index, item] of ranked.entries()) {
  const coverage = item.lineCoverage === null ? "n/a" : `${Math.round(item.lineCoverage * 100)}%`;
  console.log(`${index + 1}. ${item.kind} ${item.name}`);
  console.log(`   score=${item.score.toFixed(1)} loc=${item.loc} complexity=${item.complexity} coupling=${item.coupling} dupLines=${item.duplicateLines} lineCov=${coverage} pmd=${item.pmdViolations}`);
  if (item.notes.length > 0) {
    console.log(`   ${item.notes.slice(0, 3).join("; ")}`);
  }
}
