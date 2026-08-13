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
 *       { "plaidAccountId": "acc_...", "deskbooksAccountId": 3 },
 *       { "plaidAccountId": "acc_...", "deskbooksAccountId": 9, "balances": false }
 *     ]
 *   }
 *
 * "balances": false stages the account's transactions but never its
 * balance, keeping it out of the net-worth series.
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
    // Plaid's `date` is the posted date; `authorized_date` is when the
    // transaction actually happened. The app's convention (and the CSV
    // importers') is transaction date in `date`, posted date in
    // `post_date` — card transactions typically post 1–3 days late, so
    // getting this wrong shifts every card row.
    return {
      id: txn.transaction_id,
      date: txn.authorized_date ?? txn.date,
      description: txn.name ?? "",
      amount,
      pending: txn.pending === true,
      post_date: txn.authorized_date ? txn.date : null,
      merchant: txn.merchant_name ?? null,
    };
  });
}

export function groupMappings(mappings) {
  // Several provider accounts may roll up into one DeskBooks account
  // (e.g. nine CDs tracked as a single "Marcus CDs" account).
  const byDeskbooksId = new Map();
  for (const mapping of mappings) {
    const ids = byDeskbooksId.get(mapping.deskbooksAccountId) ?? [];
    ids.push(mapping.plaidAccountId);
    byDeskbooksId.set(mapping.deskbooksAccountId, ids);
  }
  return byDeskbooksId;
}

export function normalizePlaidBalances({ mappings, accountsById }) {
  // Balances of provider accounts sharing a DeskBooks account are summed
  // (integer-cent math). A row is emitted as null only when every mapped
  // provider account reports a null balance.
  //
  // "balances": false opts a mapping out entirely: its transactions still
  // import, but no balance row is ever staged, so the account stays out of
  // the net-worth series (net worth is the sum of snapshot balance rows).
  // Donor-advised funds are the motivating case — the giving is worth
  // tracking, the balance is money you no longer own.
  const rows = [];
  for (const [deskbooksAccountId, plaidIds] of groupMappings(
    mappings.filter((mapping) => mapping.balances !== false),
  )) {
    let cents = 0;
    let seen = 0;
    for (const plaidId of plaidIds) {
      const current = accountsById[plaidId]?.balances?.current;
      if (current == null) {
        continue;
      }
      cents += Math.round(Number(current) * 100);
      seen += 1;
    }
    if (seen === 0 && plaidIds.every((id) => !accountsById[id])) {
      continue; // no data for any mapped account this run
    }
    rows.push({
      accountId: deskbooksAccountId,
      balance: seen === 0 ? null : (cents / 100).toFixed(2),
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
    // Fail loud rather than silently staging a balance the mapping meant
    // to suppress — a typo here quietly lands money in net worth.
    if ("balances" in mapping && typeof mapping.balances !== "boolean") {
      throw new Error(
        `${source.name}: account ${mapping.plaidAccountId}: "balances" must be true or false, got: ${JSON.stringify(mapping.balances)}`,
      );
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

export async function fetch({ source, config, profile = null, downloadsDir }) {
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
  for (const [deskbooksAccountId, plaidIds] of groupMappings(mappings)) {
    const idSet = new Set(plaidIds);
    const accountTxns = transactions.filter((t) => idSet.has(t.account_id));
    const staged = buildStagedTransactions({
      accountId: deskbooksAccountId,
      profile,
      transactions: normalizePlaidTransactions({
        transactions: accountTxns,
        invertAmounts: source.invertAmounts === true,
      }),
    });
    const filePath = await writeStagedFile(
      downloadsDir,
      `${today}-${source.name}-acct${deskbooksAccountId}-transactions.json`,
      staged,
    );
    entries.push({
      path: filePath,
      kind: "statement",
      accountId: deskbooksAccountId,
      importerName: "staged_json",
    });
  }

  const balanceRows = normalizePlaidBalances({ mappings, accountsById });
  if (balanceRows.length) {
    const stagedBalances = buildStagedBalances({ asOf: today, rows: balanceRows, profile });
    const balancesPath = await writeStagedFile(
      downloadsDir,
      `${today}-${source.name}-balances.json`,
      stagedBalances,
    );
    entries.push({ path: balancesPath, kind: "balances" });
  }

  return { entries };
}
