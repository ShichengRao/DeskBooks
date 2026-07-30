/**
 * Teller (teller.io) connector — API-based, no browser.
 *
 * Free for personal use (developer tier, up to 100 connections). Auth is
 * mutual TLS with the certificate pair Teller issues at signup, plus a
 * per-enrollment access token sent as HTTP basic auth (`token:`). The
 * token lives in the macOS Keychain; certificate/key paths live in
 * config.local.json.
 *
 * Status: normalizers are fixture-tested; the live API path follows
 * https://teller.io/docs/api but has not been exercised against a real
 * enrollment yet. Start with `"environment": "sandbox"` tokens.
 *
 * Source config:
 *   {
 *     "name": "teller",
 *     "browser": false,
 *     "module": "./fetchers/teller.mjs",
 *     "certPath": "~/.config/deskbooks/teller/certificate.pem",
 *     "keyPath": "~/.config/deskbooks/teller/private_key.pem",
 *     "tokenService": "DeskBooks.Teller",
 *     "tokenAccount": "teller",
 *     "lookbackDays": 90,
 *     "invertAmounts": false,
 *     "accounts": [
 *       { "tellerAccountId": "acc_...", "deskbooksAccountId": 3 }
 *     ]
 *   }
 */
import { readFile } from "node:fs/promises";
import { basicAuthHeader, httpsGetJson } from "../src/connector-http.mjs";
import { resolveFrom } from "../src/fetcher-api.mjs";
import { readGenericPassword } from "../src/keychain.mjs";
import {
  buildStagedBalances,
  buildStagedTransactions,
  writeStagedFile,
} from "../src/staged-formats.mjs";

export const TELLER_HOSTS = ["api.teller.io"];
const BASE_URL = "https://api.teller.io";

export function normalizeTellerTransactions({ transactions, invertAmounts = false }) {
  return transactions.map((txn) => {
    let amount = String(txn.amount);
    if (invertAmounts) {
      amount = amount.startsWith("-") ? amount.slice(1) : `-${amount}`;
    }
    return {
      id: txn.id,
      date: txn.date,
      description: txn.description ?? "",
      amount,
      pending: txn.status !== "posted",
      merchant: txn.details?.counterparty?.name ?? null,
    };
  });
}

export function normalizeTellerBalances({ mappings, balancesByTellerId }) {
  const rows = [];
  for (const mapping of mappings) {
    const balance = balancesByTellerId[mapping.tellerAccountId];
    if (!balance) {
      continue;
    }
    // `ledger` is the bank's settled balance. Liability accounts keep the
    // sign Teller reports; the app's net-worth views already normalize
    // credit/liability categories at read time.
    rows.push({
      accountId: mapping.deskbooksAccountId,
      balance: balance.ledger != null ? String(balance.ledger) : null,
    });
  }
  return rows;
}

function isoDaysAgo(days) {
  const d = new Date(Date.now() - days * 24 * 60 * 60 * 1000);
  return d.toISOString().slice(0, 10);
}

function validateSource(source) {
  if (!source.certPath || !source.keyPath) {
    throw new Error(`${source.name}: certPath and keyPath are required`);
  }
  const accounts = source.accounts ?? [];
  if (!accounts.length) {
    throw new Error(
      `${source.name}: accounts mapping is required; run automation/bin/list-teller-accounts.mjs to discover IDs`,
    );
  }
  for (const mapping of accounts) {
    if (!mapping.tellerAccountId || !Number.isInteger(mapping.deskbooksAccountId)) {
      throw new Error(`${source.name}: each account needs tellerAccountId and integer deskbooksAccountId`);
    }
  }
  return accounts;
}

export async function fetch({ source, config, downloadsDir }) {
  const mappings = validateSource(source);
  const cert = await readFile(resolveFrom(config.__dir, source.certPath));
  const key = await readFile(resolveFrom(config.__dir, source.keyPath));
  const token = await readGenericPassword({
    service: source.tokenService || "DeskBooks.Teller",
    account: source.tokenAccount || "teller",
  });
  const http = {
    allowedHosts: TELLER_HOSTS,
    headers: { authorization: basicAuthHeader(token) },
    cert,
    key,
  };

  const startDate = isoDaysAgo(source.lookbackDays ?? 90);
  const today = new Date().toISOString().slice(0, 10);
  const entries = [];

  const balancesByTellerId = {};
  for (const mapping of mappings) {
    const txns = await httpsGetJson(
      `${BASE_URL}/accounts/${mapping.tellerAccountId}/transactions?start_date=${startDate}`,
      http,
    );
    const staged = buildStagedTransactions({
      accountId: mapping.deskbooksAccountId,
      transactions: normalizeTellerTransactions({
        transactions: txns,
        invertAmounts: source.invertAmounts === true,
      }),
    });
    const filePath = await writeStagedFile(
      downloadsDir,
      `${today}-${source.name}-acct${mapping.deskbooksAccountId}-transactions.json`,
      staged,
    );
    entries.push({
      path: filePath,
      kind: "statement",
      accountId: mapping.deskbooksAccountId,
      importerName: "staged_json",
    });

    balancesByTellerId[mapping.tellerAccountId] = await httpsGetJson(
      `${BASE_URL}/accounts/${mapping.tellerAccountId}/balances`,
      http,
    );
  }

  const balanceRows = normalizeTellerBalances({ mappings, balancesByTellerId });
  if (balanceRows.length) {
    const stagedBalances = buildStagedBalances({ asOf: today, rows: balanceRows });
    const balancesPath = await writeStagedFile(
      downloadsDir,
      `${today}-${source.name}-balances.json`,
      stagedBalances,
    );
    entries.push({ path: balancesPath, kind: "balances" });
  }

  return { entries };
}
