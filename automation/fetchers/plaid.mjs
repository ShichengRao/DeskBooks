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
 *     "investments": false,
 *     "accounts": [
 *       { "plaidAccountId": "acc_...", "deskbooksAccountId": 3 },
 *       { "plaidAccountId": "acc_...", "deskbooksAccountId": 9, "balances": false }
 *     ]
 *   }
 *
 * "balances": false stages the account's transactions but never its
 * balance, keeping it out of the net-worth series. "transactions": false
 * is the mirror — balance only, no rows.
 *
 * "investments": true additionally reads /investments/transactions/get,
 * which is where brokerages, IRAs, 401(k)s and donor-advised funds report
 * their activity. Without it those accounts stage zero transactions —
 * /transactions/get returns an empty list for them rather than an error.
 * The Item must have consented to the investments product; see
 * docs/AUTOMATED_IMPORTS.md.
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

// Plaid splits transaction history across two endpoints by account type.
// /transactions/get covers depository and credit accounts and returns
// nothing at all for an investment account — no error, just an empty
// list, which reads exactly like a quiet quarter. Brokerages, IRAs,
// 401(k)s and donor-advised funds report through
// /investments/transactions/get instead, behind its own product.
const INVESTMENTS_CONSENT_ERRORS = ["ADDITIONAL_CONSENT_REQUIRED", "PRODUCTS_NOT_SUPPORTED"];
// The first investments call on an Item is slow: Plaid pulls the history
// from the institution while holding the request open, which overruns the
// 30s default and fails the run right after consent is granted — the one
// moment you are certain to be watching. Later calls return in under a
// second.
const INVESTMENTS_TIMEOUT_MS = 180_000;

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

function investmentDescription(txn, securitiesById) {
  const name = txn.name ?? "";
  const ticker = securitiesById.get(txn.security_id)?.ticker_symbol;
  // A ticker earns its place only when the name doesn't already carry it —
  // Plaid's names run from "BUY" to "Bought 3 ACME @ 12.10".
  if (!ticker || name.toUpperCase().includes(ticker.toUpperCase())) {
    return name;
  }
  return name ? `${name} (${ticker})` : ticker;
}

export function normalizePlaidInvestmentTransactions({
  transactions,
  securities = [],
  invertAmounts = false,
}) {
  const securitiesById = new Map((securities ?? []).map((s) => [s.security_id, s]));
  return transactions.map((txn, index) => {
    // Investment amounts arrive at share-price precision (five decimals
    // is common, since a dollar amount buys a fractional share), while
    // the ledger stores cents. Round here rather than letting sub-cent
    // values into the database, where they would never round-trip and
    // would not match the same transaction seen from another source.
    let amount = negatedAmountString(
      Math.round(Number(txn.amount) * 100) / 100,
      `investment_transactions[${index}]`,
    );
    if (invertAmounts) {
      amount = amount.startsWith("-") ? amount.slice(1) : `-${amount}`;
    }
    // Investment transactions carry no pending state and no
    // authorized/posted pair — Plaid reports one settled date — so the
    // fields the card path uses to straddle posting stay null here.
    return {
      id: txn.investment_transaction_id,
      date: txn.date,
      description: investmentDescription(txn, securitiesById),
      amount,
      pending: false,
      post_date: null,
      merchant: null,
    };
  });
}

export function groupMappings(mappings) {
  // Several provider accounts may roll up into one DeskBooks account
  // (e.g. a ladder of CDs tracked as a single savings account).
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
  if ("investments" in source && typeof source.investments !== "boolean") {
    throw new Error(
      `${source.name}: "investments" must be true or false, got: ${JSON.stringify(source.investments)}`,
    );
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
    for (const field of ["balances", "transactions"]) {
      if (field in mapping && typeof mapping[field] !== "boolean") {
        throw new Error(
          `${source.name}: account ${mapping.plaidAccountId}: "${field}" must be true or false, got: ${JSON.stringify(mapping[field])}`,
        );
      }
    }
    if (mapping.balances === false && mapping.transactions === false) {
      throw new Error(
        `${source.name}: account ${mapping.plaidAccountId} maps to DeskBooks account ${mapping.deskbooksAccountId} with both "balances" and "transactions" false, which fetches nothing — remove the mapping instead`,
      );
    }
  }
  return accounts;
}

async function fetchInvestmentTransactions({ base, auth, http, startDate, endDate, sourceName }) {
  const transactions = [];
  const securities = new Map();
  let total = Infinity;
  while (transactions.length < total && transactions.length < MAX_TRANSACTIONS) {
    let page;
    try {
      page = await httpsPostJson(
        `${base}/investments/transactions/get`,
        {
          ...auth,
          start_date: startDate,
          end_date: endDate,
          options: { count: PAGE_SIZE, offset: transactions.length },
        },
        { ...http, timeoutMs: INVESTMENTS_TIMEOUT_MS },
      );
    } catch (error) {
      const message = String(error.message);
      if (INVESTMENTS_CONSENT_ERRORS.some((code) => message.includes(code))) {
        // Worth its own message: the config asked for investments, and
        // the fix is a browser round-trip, not a config edit. Failing the
        // source is deliberate — quietly staging without the investment
        // rows would look like an account with no activity.
        throw new Error(
          `${sourceName}: this Item has not consented to the investments product, so investment ` +
            "transactions cannot be read. Re-consent with " +
            "`node bin/plaid-link-setup.mjs --products transactions,investments --access-token <path>` " +
            "(update mode keeps the Item, so your account mappings stay valid), or set " +
            `"investments": false on the source. Plaid said: ${message}`,
        );
      }
      throw error;
    }
    for (const security of page.securities ?? []) {
      securities.set(security.security_id, security);
    }
    const batch = page.investment_transactions ?? [];
    total = page.total_investment_transactions ?? batch.length;
    transactions.push(...batch);
    if (batch.length === 0) {
      break;
    }
  }
  if (transactions.length >= MAX_TRANSACTIONS && transactions.length < total) {
    console.warn(
      `[plaid] ${sourceName}: capped at ${MAX_TRANSACTIONS} of ${total} investment transactions; shorten lookbackDays to cover the rest`,
    );
  }
  return { transactions, securities: [...securities.values()] };
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

  let investments = { transactions: [], securities: [] };
  if (source.investments === true) {
    investments = await fetchInvestmentTransactions({
      base,
      auth,
      http,
      startDate,
      endDate: today,
      sourceName: source.name,
    });
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
  // "transactions": false is the mirror of "balances": false — take an
  // account's balance for net worth without its row-by-row activity.
  // Turning investments on covers every account in the Item, and a
  // retirement plan's dividend reinvestments are rarely worth importing
  // just to reach the one account whose activity you wanted.
  const stagedMappings = mappings.filter((mapping) => mapping.transactions !== false);
  for (const [deskbooksAccountId, plaidIds] of groupMappings(stagedMappings)) {
    const idSet = new Set(plaidIds);
    const invertAmounts = source.invertAmounts === true;
    // One DeskBooks account draws from whichever endpoint its provider
    // accounts report through; an Item holding both a checking account and
    // a brokerage contributes to each side separately.
    const staged = buildStagedTransactions({
      accountId: deskbooksAccountId,
      profile,
      transactions: [
        ...normalizePlaidTransactions({
          transactions: transactions.filter((t) => idSet.has(t.account_id)),
          invertAmounts,
        }),
        ...normalizePlaidInvestmentTransactions({
          transactions: investments.transactions.filter((t) => idSet.has(t.account_id)),
          securities: investments.securities,
          invertAmounts,
        }),
      ],
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
