/**
 * Template for API-style connectors (`browser: false`) and the fixture
 * vehicle for the automation test-suite. Reads a local JSON bundle and
 * stages it in the two connector formats — exactly what a real API
 * connector does after its HTTPS calls, with the network part replaced
 * by a file read.
 *
 * Bundle shape (source.fixturePath):
 *   { "accounts": [ { "accountId": 1, "transactions": [
 *       { "id": "t1", "date": "2026-07-01", "description": "COFFEE",
 *         "amount": "-4.50", "pending": false } ] } ],
 *     "balances": { "asOf": "2026-07-30", "rows": [
 *       { "accountId": 1, "balance": "100.00" } ] } }
 */
import { readFile } from "node:fs/promises";
import { resolveFrom } from "../src/fetcher-api.mjs";
import {
  buildStagedBalances,
  buildStagedTransactions,
  writeStagedFile,
} from "../src/staged-formats.mjs";

export async function fetch({ source, config, profile = null, downloadsDir }) {
  if (!source.fixturePath) {
    throw new Error(`${source.name}: fixturePath is required`);
  }
  const bundlePath = resolveFrom(config.__dir, source.fixturePath);
  const bundle = JSON.parse(await readFile(bundlePath, "utf8"));
  const today = new Date().toISOString().slice(0, 10);
  const entries = [];

  for (const account of bundle.accounts ?? []) {
    const staged = buildStagedTransactions({
      accountId: account.accountId,
      profile,
      transactions: account.transactions ?? [],
    });
    const filePath = await writeStagedFile(
      downloadsDir,
      `${today}-${source.name}-acct${account.accountId}-transactions.json`,
      staged,
    );
    entries.push({
      path: filePath,
      kind: "statement",
      accountId: account.accountId,
      importerName: "staged_json",
    });
  }

  if (bundle.balances) {
    const staged = buildStagedBalances({
      asOf: bundle.balances.asOf ?? today,
      rows: bundle.balances.rows ?? [],
      profile,
    });
    const filePath = await writeStagedFile(downloadsDir, `${today}-${source.name}-balances.json`, staged);
    entries.push({ path: filePath, kind: "balances" });
  }

  return { entries };
}
