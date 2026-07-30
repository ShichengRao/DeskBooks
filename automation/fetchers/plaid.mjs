/**
 * Plaid connector — API-based, no browser.
 *
 * Plaid's free Trial plan (teams created on/after 2026-04-15) supports up
 * to 10 production Items — one Item is one bank login — which covers a
 * personal setup. Credentials are three private files (chmod 600):
 * client id, secret, and the per-Item access token produced by
 * bin/plaid-link-setup.mjs.
 *
 * Sign convention: Plaid reports positive amounts for money leaving the
 * account; DeskBooks stores outflow-negative, so amounts are negated
 * here. Set "invertAmounts": true only if an institution reports the
 * opposite of Plaid's documented convention.
 *
 * Status: normalizers are fixture-tested; the live API path follows
 * https://plaid.com/docs but has not been exercised against a real Item
 * yet. Start with "environment": "sandbox" and preview-only.
 *
 * Source config:
 *   {
 *     "name": "plaid_mybank",
 *     "module": "./fetchers/plaid.mjs",
 *     "environment": "sandbox",
 *     "clientIdPath": "~/.config/deskbooks/plaid/client-id",
 *     "secretPath": "~/.config/deskbooks/plaid/secret",
 *     "accessTokenPath": "~/.config/deskbooks/plaid/access-token-mybank",
 *     "lookbackDays": 90,
 *     "invertAmounts": false,
 *     "accounts": [
 *       { "plaidAccountId": "acc_...", "deskbooksAccountId": 3 }
 *     ]
 *   }
 */
import { readFile } from "node:fs/promises";
import { httpsPostJson } from "../src/connector-http.mjs";
import { resolveFrom } from "../src/fetcher-api.mjs";
import {
  buildStagedBalances,
  buildStagedTransactions,
  writeStagedFile,
} from "../src/staged-formats.mjs";

export const PLAID_HOSTS = ["sandbox.plaid.com", "production.plaid.com"];
const PAGE_SIZE = 500;
const MAX_TRANSACTIONS = 10_000;

export function plaidHost(environment) {
  const host = `${environment}.plaid.com`;
  if (!PLAID_HOSTS.includes(host)) {
    throw new Error(`environment must be sandbox or production, got: ${environment}`);
  }
  return `https://${host}`;
}

function negatedAmountString(value, label) {
  const n = Number(value);
  if (!Number.isFinite(n)) {
    throw new Error(`${label}: amount is not a number: ${value}`);
  }
  if (n === 0) {
    return "0.00";
  }
  const magnitude = String(Math.abs(n));
  return n > 0 ? `-${magnitude}` : magnitude;
}

export function normalizePlaidTransactions({ transactions, invertAmounts = false }) {
  return transactions.map((txn, index) => {
    let amount = negatedAmountString(txn.amount, `transactions[${index}]`);
    if (invertAmounts) {
      amount = amount.startsWith("-") ? amount.slice(1) : `-${amount}`;
    }
    return {
      id: txn.transaction_id,
      date: txn.date,
      description: txn.name ?? "",
      amount,
      pending: txn.pending === true,
      merchant: txn.merchant_name ?? null,
    };
  });
}

export function normalizePlaidBalances({ mappings, accountsById }) {
  const rows = [];
  for (const mapping of mappings) {
    const account = accountsById[mapping.plaidAccountId];
    if (!account) {
      continue;
    }
    const current = account.balances?.current;
    rows.push({
      accountId: mapping.deskbooksAccountId,
      balance: current == null ? null : String(current),
    });
  }
  return rows;
}

function isoDaysAgo(days) {
  const d = new Date(Date.now() - days * 24 * 60 * 60 * 1000);
  return d.toISOString().slice(0, 10);
}

function validateSource(source) {
  for (const field of ["clientIdPath", "secretPath", "accessTokenPath"]) {
    if (!source[field]) {
      throw new Error(`${source.name}: ${field} is required`);
    }
  }
  const accounts = source.accounts ?? [];
  if (!accounts.length) {
    throw new Error(
      `${source.name}: accounts mapping is required; run automation/bin/list-plaid-accounts.mjs to discover IDs`,
    );
  }
  for (const mapping of accounts) {
    if (!mapping.plaidAccountId || !Number.isInteger(mapping.deskbooksAccountId)) {
      throw new Error(`${source.name}: each account needs plaidAccountId and integer deskbooksAccountId`);
    }
  }
  return accounts;
}

async function readSecretFile(configDir, rawPath) {
  const value = (await readFile(resolveFrom(configDir, rawPath), "utf8")).trim();
  if (!value) {
    throw new Error(`credential file is empty: ${rawPath}`);
  }
  return value;
}

export async function fetch({ source, config, downloadsDir }) {
  const mappings = validateSource(source);
  const base = plaidHost(source.environment || "sandbox");
  const clientId = await readSecretFile(config.__dir, source.clientIdPath);
  const secret = await readSecretFile(config.__dir, source.secretPath);
  const accessToken = await readSecretFile(config.__dir, source.accessTokenPath);
  const http = { allowedHosts: PLAID_HOSTS };
  const auth = { client_id: clientId, secret, access_token: accessToken };

  const startDate = isoDaysAgo(source.lookbackDays ?? 90);
  const today = new Date().toISOString().slice(0, 10);

  const transactions = [];
  let total = Infinity;
  while (transactions.length < total && transactions.length < MAX_TRANSACTIONS) {
    const page = await httpsPostJson(
      `${base}/transactions/get`,
      {
        ...auth,
        start_date: startDate,
        end_date: today,
        options: { count: PAGE_SIZE, offset: transactions.length },
      },
      http,
    );
    total = page.total_transactions ?? page.transactions.length;
    transactions.push(...page.transactions);
    if (page.transactions.length === 0) {
      break;
    }
  }
  if (transactions.length >= MAX_TRANSACTIONS && transactions.length < total) {
    console.warn(
      `[plaid] ${source.name}: capped at ${MAX_TRANSACTIONS} of ${total} transactions; shorten lookbackDays to cover the rest`,
    );
  }

  const balancesResponse = await httpsPostJson(`${base}/accounts/balance/get`, { ...auth }, http);
  const accountsById = {};
  for (const account of balancesResponse.accounts ?? []) {
    accountsById[account.account_id] = account;
  }

  const mappedIds = new Set(mappings.map((m) => m.plaidAccountId));
  for (const account of balancesResponse.accounts ?? []) {
    if (!mappedIds.has(account.account_id)) {
      console.log(
        `[plaid] ${source.name}: skipping unmapped account ${account.account_id} (${account.name ?? "?"} …${account.mask ?? "????"})`,
      );
    }
  }

  const entries = [];
  for (const mapping of mappings) {
    const accountTxns = transactions.filter((t) => t.account_id === mapping.plaidAccountId);
    const staged = buildStagedTransactions({
      accountId: mapping.deskbooksAccountId,
      transactions: normalizePlaidTransactions({
        transactions: accountTxns,
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
  }

  const balanceRows = normalizePlaidBalances({ mappings, accountsById });
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
