import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { runFetchers } from "../src/run-fetchers.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixtureBundle = path.join(here, "fixtures", "example-bundle.json");

async function writeConfig(dir, config) {
  const configPath = path.join(dir, "config.json");
  await writeFile(configPath, JSON.stringify(config, null, 2));
  return configPath;
}

async function readJsonl(filePath) {
  const raw = await readFile(filePath, "utf8");
  return raw
    .split("\n")
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

test("stages entries per kind, isolates failures, and still writes the latest manifest", async () => {
  const dir = await mkdtemp(path.join(tmpdir(), "deskbooks-runner-"));
  const stagingDir = path.join(dir, "staging");

  const goodCsv = path.join(dir, "good-csv.mjs");
  await writeFile(
    goodCsv,
    `import { writeFile } from "node:fs/promises";
import path from "node:path";
export async function fetch({ downloadsDir }) {
  const file = path.join(downloadsDir, "statement.csv");
  await writeFile(file, "Date,Description,Amount\\n2026-07-01,COFFEE,-4.50\\n");
  return { files: [file] };
}
`,
  );
  const failing = path.join(dir, "failing.mjs");
  await writeFile(failing, "export async function fetch() { throw new Error('boom'); }\n");

  const configPath = await writeConfig(dir, {
    stagingDir,
    profile: "personal",
    sources: [
      {
        name: "json_connector",
        browser: false,
        module: path.join(here, "..", "fetchers", "example-json-connector.mjs"),
        fixturePath: fixtureBundle,
      },
      { name: "flaky", browser: false, module: failing },
      {
        name: "csvish",
        module: goodCsv,
        accountId: 7,
        importerName: "running_balance_bank",
        profile: "scratch",
      },
    ],
  });

  const { entries, failures } = await runFetchers({ configPath });

  assert.equal(failures.length, 1);
  assert.equal(failures[0].source, "flaky");

  const kinds = entries.map((e) => e.kind).sort();
  assert.deepEqual(kinds, ["balances", "statement", "statement"]);

  const statementJson = entries.find((e) => e.kind === "statement" && e.source === "json_connector");
  assert.equal(statementJson.account_id, 1);
  assert.equal(statementJson.importer_name, "staged_json");

  const balances = entries.find((e) => e.kind === "balances");
  assert.equal(balances.account_id, null);
  assert.equal(balances.importer_name, null);
  const balancesPayload = JSON.parse(await readFile(balances.path, "utf8"));
  assert.equal(balancesPayload.format, "deskbooks.staged-balances/v1");
  assert.equal(balancesPayload.balances.length, 2);

  // Config-level profile stamps manifest entries and staged payloads; a
  // source-level profile overrides it.
  assert.equal(statementJson.profile, "personal");
  assert.equal(balances.profile, "personal");
  assert.equal(balancesPayload.profile, "personal");
  const statementPayload = JSON.parse(await readFile(statementJson.path, "utf8"));
  assert.equal(statementPayload.profile, "personal");

  const csvEntry = entries.find((e) => e.source === "csvish");
  assert.equal(csvEntry.kind, "statement");
  assert.equal(csvEntry.account_id, 7);
  assert.equal(csvEntry.importer_name, "running_balance_bank");
  assert.equal(csvEntry.profile, "scratch");

  const latest = await readJsonl(path.join(stagingDir, "latest-manifest.jsonl"));
  assert.equal(latest.length, 3);
  const history = await readJsonl(path.join(stagingDir, "manifest.jsonl"));
  assert.equal(history.length, 3);
  assert.ok(latest.every((entry) => typeof entry.sha256 === "string" && entry.sha256.length === 64));
});

test("refuses browser sources — connectors are API/file based", async () => {
  const dir = await mkdtemp(path.join(tmpdir(), "deskbooks-runner-"));
  const configPath = await writeConfig(dir, {
    stagingDir: path.join(dir, "staging"),
    sources: [{ name: "browserish", browser: true, module: "./whatever.mjs" }],
  });
  await assert.rejects(runFetchers({ configPath }), /browserish: browser connectors are not supported/);
});

test("statement entries without an account id fail that source only", async () => {
  const dir = await mkdtemp(path.join(tmpdir(), "deskbooks-runner-"));
  const noAccount = path.join(dir, "no-account.mjs");
  await writeFile(
    noAccount,
    `import { writeFile } from "node:fs/promises";
import path from "node:path";
export async function fetch({ downloadsDir }) {
  const file = path.join(downloadsDir, "x.csv");
  await writeFile(file, "data\\n");
  return { files: [file] };
}
`,
  );
  const configPath = await writeConfig(dir, {
    stagingDir: path.join(dir, "staging"),
    sources: [{ name: "incomplete", browser: false, module: noAccount }],
  });
  const { entries, failures } = await runFetchers({ configPath });
  assert.equal(entries.length, 0);
  assert.equal(failures.length, 1);
  assert.match(failures[0].error.message, /statement entries need an integer accountId/);
});

test("defaults staging under PFA_DATA_DIR when config sets no stagingDir", async (t) => {
  const dir = await mkdtemp(path.join(tmpdir(), "deskbooks-runner-"));
  const dataDir = path.join(dir, "data");
  const previous = process.env.PFA_DATA_DIR;
  process.env.PFA_DATA_DIR = dataDir;
  t.after(() => {
    if (previous === undefined) {
      delete process.env.PFA_DATA_DIR;
    } else {
      process.env.PFA_DATA_DIR = previous;
    }
  });

  const configPath = await writeConfig(dir, {
    sources: [
      {
        name: "json_connector",
        browser: false,
        module: path.join(here, "..", "fetchers", "example-json-connector.mjs"),
        fixturePath: fixtureBundle,
      },
    ],
  });
  const { entries, failures } = await runFetchers({ configPath });
  assert.equal(failures.length, 0);
  assert.ok(entries.length > 0);
  for (const entry of entries) {
    assert.ok(
      entry.path.startsWith(path.join(dataDir, "import-staging")),
      `${entry.path} should live under PFA_DATA_DIR/import-staging`,
    );
  }
});
