#!/usr/bin/env node
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { createServer } from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const backendDir = path.join(root, "backend");
const javaDir = path.join(root, "backend-java");
const verbose = process.env.PARITY_VERBOSE === "1";
const waitTimeoutMs = Number(process.env.PARITY_WAIT_TIMEOUT_MS ?? "90000");

const running = [];
let tempRoot;

function log(message) {
  console.log(`[parity] ${message}`);
}

function tail(lines) {
  return lines.slice(-80).join("");
}

function captureOutput(child, name) {
  const lines = [];
  const capture = (streamName, chunk) => {
    const text = chunk.toString();
    lines.push(text);
    if (lines.length > 200) {
      lines.splice(0, lines.length - 200);
    }
    if (verbose) {
      process[streamName].write(`[${name}] ${text}`);
    }
  };
  child.stdout?.on("data", (chunk) => capture("stdout", chunk));
  child.stderr?.on("data", (chunk) => capture("stderr", chunk));
  return lines;
}

function spawnLogged(name, command, args, options) {
  const child = spawn(command, args, {
    cwd: options.cwd,
    env: options.env,
    stdio: ["ignore", "pipe", "pipe"],
  });
  const output = captureOutput(child, name);
  child.once("exit", (code, signal) => {
    child.parityExit = { code, signal };
  });
  return { child, output };
}

async function runCommand(name, command, args, options) {
  const { child, output } = spawnLogged(name, command, args, options);
  return new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`${name} exited with ${code ?? signal}\n${tail(output)}`));
      }
    });
  });
}

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      server.close(() => resolve(port));
    });
  });
}

async function waitForHealth(server) {
  const deadline = Date.now() + waitTimeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    if (server.child.parityExit) {
      throw new Error(`${server.name} exited before health check passed\n${tail(server.output)}`);
    }
    try {
      const response = await fetch(`${server.baseUrl}/api/health`);
      if (response.ok) {
        const json = await response.json();
        if (json?.ok === true) {
          return;
        }
      }
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`${server.name} did not become healthy: ${lastError?.message ?? "timeout"}\n${tail(server.output)}`);
}

async function startPython(port, dataDir) {
  const env = {
    ...process.env,
    PFA_DATA_DIR: dataDir,
    PFA_ALLOW_SHUTDOWN: "1",
    PFA_CORS_ORIGINS: "http://localhost:5173,http://127.0.0.1:5173",
  };
  await runCommand("python-bootstrap", "uv", ["run", "python", "-m", "app.bootstrap"], {
    cwd: backendDir,
    env,
  });
  const launched = spawnLogged("python", "uv", [
    "run",
    "uvicorn",
    "app.main:app",
    "--host",
    "127.0.0.1",
    "--port",
    String(port),
    "--log-level",
    "warning",
  ], {
    cwd: backendDir,
    env,
  });
  const server = { name: "python", baseUrl: `http://127.0.0.1:${port}`, ...launched };
  running.push(server);
  await waitForHealth(server);
  return server;
}

async function startJava(port, dataDir) {
  const env = {
    ...process.env,
    PFA_DATA_DIR: dataDir,
    PFA_ALLOW_SHUTDOWN: "1",
    PFA_SEED_STARTER_DATA: "1",
    PFA_CORS_ORIGINS: "http://localhost:5173,http://127.0.0.1:5173",
    BACKEND_PORT: String(port),
  };
  if (process.env.JAVA_GRADLE_USER_HOME) {
    env.GRADLE_USER_HOME = process.env.JAVA_GRADLE_USER_HOME;
  }
  const launched = spawnLogged("java", process.env.JAVA_GRADLE ?? "gradle", ["bootRun"], {
    cwd: javaDir,
    env,
  });
  const server = { name: "java", baseUrl: `http://127.0.0.1:${port}`, ...launched };
  running.push(server);
  await waitForHealth(server);
  return server;
}

async function requestJson(server, method, route, body) {
  const response = await fetch(`${server.baseUrl}${route}`, {
    method,
    headers: body === undefined ? undefined : { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  if (text) {
    json = JSON.parse(text);
  }
  if (!response.ok) {
    throw new Error(`${server.name} ${method} ${route} -> ${response.status}: ${text}`);
  }
  return json;
}

const getJson = (server, route) => requestJson(server, "GET", route);
const postJson = (server, route, body) => requestJson(server, "POST", route, body);
const putJson = (server, route, body) => requestJson(server, "PUT", route, body);

function stable(value) {
  if (Array.isArray(value)) {
    return value.map(stable);
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, stable(item)]));
  }
  if (typeof value === "string" && /^-?\d+(?:\.\d+)?$/.test(value)) {
    return normalizeDecimalString(value);
  }
  return value;
}

function normalizeDecimalString(value) {
  const negative = value.startsWith("-");
  const unsigned = negative ? value.slice(1) : value;
  const [wholePart, fractionPart = ""] = unsigned.split(".");
  const whole = wholePart.replace(/^0+(?=\d)/, "") || "0";
  const fraction = fractionPart.replace(/0+$/, "");
  if (whole === "0" && fraction === "") {
    return "0";
  }
  return `${negative ? "-" : ""}${whole}${fraction ? `.${fraction}` : ""}`;
}

function compare(label, pythonValue, javaValue) {
  assert.deepStrictEqual(stable(javaValue), stable(pythonValue), label);
  log(`ok: ${label}`);
}

function byName(rows, name) {
  const row = rows.find((item) => item.name === name);
  assert.ok(row, `missing row named ${name}`);
  return row;
}

function sortedImporters(rows) {
  return rows.map(({ name, label }) => ({ name, label })).sort((a, b) => a.name.localeCompare(b.name));
}

function previewSummary(preview) {
  return {
    importer_name: preview.importer_name,
    source_filename: preview.source_filename,
    sniff_notes: preview.sniff_notes,
    rows: preview.rows.map((row) => ({
      row_index: row.row_index,
      date: row.date,
      post_date: row.post_date,
      description_normalized: row.description_normalized,
      merchant: row.merchant,
      amount: row.amount,
      suggested_kind: row.suggested_kind,
      is_duplicate: row.is_duplicate,
    })),
  };
}

function transactionSummary(tx) {
  return {
    id: tx.id,
    account_id: tx.account_id,
    date: tx.date,
    post_date: tx.post_date,
    description_raw: tx.description_raw,
    description_normalized: tx.description_normalized,
    merchant: tx.merchant,
    amount: tx.amount,
    category_id: tx.category_id,
    kind: tx.kind,
    is_user_categorized: tx.is_user_categorized,
    is_excluded_from_totals: tx.is_excluded_from_totals,
    notes: tx.notes,
    transfer_pair_id: tx.transfer_pair_id,
    import_batch_id: tx.import_batch_id,
    matched_rule_id: tx.matched_rule_id,
    tags: tx.tags,
    split: tx.split,
  };
}

function budgetDefaultSummary(row) {
  return {
    id: row.id,
    category_id: row.category_id,
    amount: row.amount,
    notes: row.notes,
  };
}

function budgetReportSummary(report) {
  const groceries = report.rows.find((row) => row.category_name === "Groceries");
  assert.ok(groceries, "missing Groceries budget row");
  return {
    start: report.start,
    end: report.end,
    focus_month: report.focus_month,
    planned_total: report.planned_total,
    actual_total: report.actual_total,
    delta_total: report.delta_total,
    budgeted_actual_total: report.budgeted_actual_total,
    unbudgeted_actual_total: report.unbudgeted_actual_total,
    uncategorized_actual: report.uncategorized_actual,
    groceries: {
      category_id: groceries.category_id,
      parent_id: groceries.parent_id,
      default_budget_id: groceries.default_budget_id,
      default_amount: groceries.default_amount,
      target_amount: groceries.target_amount,
      actual_amount: groceries.actual_amount,
      delta: groceries.delta,
      transaction_count: groceries.transaction_count,
      default_notes: groceries.default_notes,
    },
  };
}

function sankeySummary(response) {
  const nodeNames = response.nodes.map((node) => node.name);
  const links = response.links
    .map((link) => ({
      source: nodeNames[link.source],
      target: nodeNames[link.target],
      value: link.value,
      label: link.label,
    }))
    .sort((a, b) => `${a.source}|${a.target}|${a.label}`.localeCompare(`${b.source}|${b.target}|${b.label}`));
  return {
    period_label: response.period_label,
    totals: response.totals,
    links,
  };
}

async function compareInitialState(python, java) {
  compare("health", await getJson(python, "/api/health"), await getJson(java, "/api/health"));
  compare("profiles", await getJson(python, "/api/profiles"), await getJson(java, "/api/profiles"));
  const pythonAccounts = await getJson(python, "/api/accounts");
  const javaAccounts = await getJson(java, "/api/accounts");
  compare("starter accounts", pythonAccounts, javaAccounts);
  const pythonCategories = await getJson(python, "/api/categories");
  const javaCategories = await getJson(java, "/api/categories");
  compare("starter categories", pythonCategories, javaCategories);
  compare("importer metadata", sortedImporters(await getJson(python, "/api/imports/importers")), sortedImporters(await getJson(java, "/api/imports/importers")));
  return {
    pythonAccounts,
    javaAccounts,
    pythonCategories,
    javaCategories,
  };
}

async function compareImportPreviews(python, java, state) {
  const samples = [
    ["chase_credit", "chase_credit_sample.csv", "Credit Card"],
    ["wells_fargo_checking", "wells_fargo_checking_sample.csv", "Checking"],
    ["amex", "amex_sample.csv", "Credit Card"],
  ];
  for (const [importerName, filename, accountName] of samples) {
    const samplePath = path.join(root, "samples", filename);
    const pythonAccount = byName(state.pythonAccounts, accountName);
    const javaAccount = byName(state.javaAccounts, accountName);
    const pythonPreview = await postJson(python, "/api/imports/preview-path", {
      path: samplePath,
      account_id: pythonAccount.id,
      importer_name: importerName,
    });
    const javaPreview = await postJson(java, "/api/imports/preview-path", {
      path: samplePath,
      account_id: javaAccount.id,
      importer_name: importerName,
    });
    compare(`import preview ${filename}`, previewSummary(pythonPreview), previewSummary(javaPreview));
  }
}

async function compareMutatingWorkflow(python, java, state) {
  const pythonChecking = byName(state.pythonAccounts, "Checking");
  const javaChecking = byName(state.javaAccounts, "Checking");
  const pythonGroceries = byName(state.pythonCategories, "Groceries");
  const javaGroceries = byName(state.javaCategories, "Groceries");

  const transactionBody = {
    date: "2026-06-03",
    description_raw: "PARITY GROCERY",
    amount: "-42.18",
    category_id: pythonGroceries.id,
    notes: "parity smoke",
  };
  const pythonTx = await postJson(python, "/api/transactions", {
    ...transactionBody,
    account_id: pythonChecking.id,
  });
  const javaTx = await postJson(java, "/api/transactions", {
    ...transactionBody,
    account_id: javaChecking.id,
    category_id: javaGroceries.id,
  });
  compare("manual transaction create", transactionSummary(pythonTx), transactionSummary(javaTx));
  compare(
    "transaction list",
    (await getJson(python, "/api/transactions?start=2026-06-01&end=2026-06-30")).map(transactionSummary),
    (await getJson(java, "/api/transactions?start=2026-06-01&end=2026-06-30")).map(transactionSummary),
  );
  compare("transaction count", await getJson(python, "/api/transactions/count?start=2026-06-01&end=2026-06-30"), await getJson(java, "/api/transactions/count?start=2026-06-01&end=2026-06-30"));

  const budgetBody = { category_id: pythonGroceries.id, amount: "500.00", notes: "parity budget" };
  const pythonBudget = await putJson(python, "/api/budgets/defaults", budgetBody);
  const javaBudget = await putJson(java, "/api/budgets/defaults", {
    ...budgetBody,
    category_id: javaGroceries.id,
  });
  compare("budget default", budgetDefaultSummary(pythonBudget), budgetDefaultSummary(javaBudget));
  compare(
    "budget report",
    budgetReportSummary(await getJson(python, "/api/budgets?month=2026-06-01")),
    budgetReportSummary(await getJson(java, "/api/budgets?month=2026-06-01")),
  );
  compare("monthly analytics", await getJson(python, "/api/analytics/monthly?start=2026-06-01&end=2026-06-30"), await getJson(java, "/api/analytics/monthly?start=2026-06-01&end=2026-06-30"));
  compare(
    "sankey analytics",
    sankeySummary(await getJson(python, "/api/analytics/sankey?start=2026-06-01&end=2026-06-30")),
    sankeySummary(await getJson(java, "/api/analytics/sankey?start=2026-06-01&end=2026-06-30")),
  );
}

async function cleanup() {
  for (const server of running.reverse()) {
    if (!server.child.killed && !server.child.parityExit) {
      server.child.kill("SIGTERM");
    }
  }
  await Promise.all(running.map((server) => new Promise((resolve) => {
    if (server.child.parityExit) {
      resolve();
    } else {
      server.child.once("exit", resolve);
      setTimeout(resolve, 5000);
    }
  })));
  if (tempRoot && process.env.PARITY_KEEP_DATA !== "1") {
    await rm(tempRoot, { recursive: true, force: true });
  }
}

async function main() {
  process.once("SIGINT", async () => {
    await cleanup();
    process.exit(130);
  });
  process.once("SIGTERM", async () => {
    await cleanup();
    process.exit(143);
  });

  tempRoot = await mkdtemp(path.join(os.tmpdir(), "deskbooks-parity-"));
  const pythonPort = Number(process.env.PYTHON_PARITY_PORT || await freePort());
  const javaPort = Number(process.env.JAVA_PARITY_PORT || await freePort());
  const pythonData = path.join(tempRoot, "python-data");
  const javaData = path.join(tempRoot, "java-data");
  log(`using Python port ${pythonPort} and Java port ${javaPort}`);

  const python = await startPython(pythonPort, pythonData);
  const java = await startJava(javaPort, javaData);
  const state = await compareInitialState(python, java);
  await compareImportPreviews(python, java, state);
  await compareMutatingWorkflow(python, java, state);
  log("side-by-side parity smoke passed");
}

try {
  await main();
} finally {
  await cleanup();
}
