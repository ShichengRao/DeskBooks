/**
 * Staged file formats shared by API connectors and the backend importer.
 *
 * deskbooks.staged-transactions/v1 — one file per DeskBooks account:
 *   { "format": "...", "account_id": 3, "transactions": [
 *       { "id": "txn_x", "date": "2026-07-01", "description": "COFFEE",
 *         "amount": "-4.50", "pending": false, "post_date": null,
 *         "merchant": null } ] }
 *   Amounts are strings, outflow-negative (the app's sign convention).
 *
 * deskbooks.staged-balances/v1 — one file per run:
 *   { "format": "...", "as_of": "2026-07-30", "balances": [
 *       { "account_id": 2, "balance": "1234.56" } ] }
 */
import { writeFile } from "node:fs/promises";
import path from "node:path";
import { ensureDir, safeFilename } from "./fetcher-api.mjs";

export const STAGED_TRANSACTIONS_FORMAT = "deskbooks.staged-transactions/v1";
export const STAGED_BALANCES_FORMAT = "deskbooks.staged-balances/v1";

const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

function assertIsoDate(value, label) {
  if (typeof value !== "string" || !ISO_DATE_RE.test(value)) {
    throw new Error(`${label} must be an ISO date (YYYY-MM-DD), got: ${value}`);
  }
  return value;
}

function assertAmountString(value, label) {
  if (typeof value === "number") {
    // Refuse floats at the boundary; connectors must format decimals as strings.
    throw new Error(`${label} must be a decimal string, not a JS number`);
  }
  if (typeof value !== "string" || !/^[+-]?\d+(\.\d+)?$/.test(value)) {
    throw new Error(`${label} must be a decimal string, got: ${value}`);
  }
  return value;
}

export function buildStagedTransactions({ accountId, transactions }) {
  if (!Number.isInteger(accountId)) {
    throw new Error("staged transactions need an integer accountId");
  }
  return {
    format: STAGED_TRANSACTIONS_FORMAT,
    account_id: accountId,
    transactions: transactions.map((txn, index) => ({
      id: txn.id != null ? String(txn.id) : null,
      date: assertIsoDate(txn.date, `transactions[${index}].date`),
      description: String(txn.description ?? ""),
      amount: assertAmountString(txn.amount, `transactions[${index}].amount`),
      pending: txn.pending === true,
      post_date: txn.post_date ? assertIsoDate(txn.post_date, `transactions[${index}].post_date`) : null,
      merchant: txn.merchant != null ? String(txn.merchant) : null,
    })),
  };
}

export function buildStagedBalances({ asOf, rows }) {
  return {
    format: STAGED_BALANCES_FORMAT,
    as_of: assertIsoDate(asOf, "as_of"),
    balances: rows.map((row, index) => {
      if (!Number.isInteger(row.accountId)) {
        throw new Error(`balances[${index}] needs an integer accountId`);
      }
      return {
        account_id: row.accountId,
        balance: row.balance == null ? null : assertAmountString(row.balance, `balances[${index}].balance`),
      };
    }),
  };
}

export async function writeStagedFile(downloadsDir, baseName, payload) {
  await ensureDir(downloadsDir);
  const filePath = path.join(downloadsDir, safeFilename(baseName));
  await writeFile(filePath, `${JSON.stringify(payload, null, 2)}\n`, {
    encoding: "utf8",
    mode: 0o600,
  });
  return filePath;
}
