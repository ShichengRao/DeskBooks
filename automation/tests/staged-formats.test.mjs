import assert from "node:assert/strict";
import test from "node:test";

import {
  buildStagedBalances,
  buildStagedTransactions,
  STAGED_BALANCES_FORMAT,
  STAGED_TRANSACTIONS_FORMAT,
} from "../src/staged-formats.mjs";
import { normalizeTellerBalances, normalizeTellerTransactions } from "../fetchers/teller.mjs";

test("buildStagedTransactions normalizes and validates rows", () => {
  const staged = buildStagedTransactions({
    accountId: 3,
    transactions: [
      { id: 12, date: "2026-07-01", description: "COFFEE", amount: "-4.50" },
      { date: "2026-07-02", description: "REFUND", amount: "4.50", pending: true },
    ],
  });
  assert.equal(staged.format, STAGED_TRANSACTIONS_FORMAT);
  assert.equal(staged.account_id, 3);
  assert.equal(staged.transactions[0].id, "12");
  assert.equal(staged.transactions[0].pending, false);
  assert.equal(staged.transactions[0].merchant, null);
  assert.equal(staged.transactions[1].pending, true);
});

test("buildStagedTransactions refuses float amounts and bad dates", () => {
  assert.throws(
    () => buildStagedTransactions({ accountId: 1, transactions: [{ date: "2026-07-01", amount: -4.5 }] }),
    /decimal string, not a JS number/,
  );
  assert.throws(
    () => buildStagedTransactions({ accountId: 1, transactions: [{ date: "07/01/2026", amount: "-1" }] }),
    /ISO date/,
  );
  assert.throws(() => buildStagedTransactions({ transactions: [] }), /integer accountId/);
});

test("buildStagedBalances validates rows and keeps explicit nulls", () => {
  const staged = buildStagedBalances({
    asOf: "2026-07-30",
    rows: [
      { accountId: 1, balance: "10.00" },
      { accountId: 2, balance: null },
    ],
  });
  assert.equal(staged.format, STAGED_BALANCES_FORMAT);
  assert.deepEqual(staged.balances, [
    { account_id: 1, balance: "10.00" },
    { account_id: 2, balance: null },
  ]);
  assert.throws(() => buildStagedBalances({ asOf: "soon", rows: [] }), /ISO date/);
});

test("normalizeTellerTransactions maps fields and flags pending", () => {
  const rows = normalizeTellerTransactions({
    transactions: [
      {
        id: "txn_a",
        date: "2026-07-01",
        description: "CARD PAYMENT",
        amount: "-12.34",
        status: "posted",
        details: { counterparty: { name: "Coffee Co" } },
      },
      { id: "txn_b", date: "2026-07-02", description: "HOLD", amount: "-5.00", status: "pending" },
    ],
  });
  assert.equal(rows[0].merchant, "Coffee Co");
  assert.equal(rows[0].pending, false);
  assert.equal(rows[1].pending, true);
  assert.equal(rows[1].merchant, null);
});

test("normalizeTellerTransactions can invert provider sign conventions", () => {
  const rows = normalizeTellerTransactions({
    transactions: [
      { id: "a", date: "2026-07-01", description: "X", amount: "12.34", status: "posted" },
      { id: "b", date: "2026-07-01", description: "Y", amount: "-1.00", status: "posted" },
    ],
    invertAmounts: true,
  });
  assert.equal(rows[0].amount, "-12.34");
  assert.equal(rows[1].amount, "1.00");
});

test("normalizeTellerBalances maps ledger balances through the account mapping", () => {
  const rows = normalizeTellerBalances({
    mappings: [
      { tellerAccountId: "acc_1", deskbooksAccountId: 3 },
      { tellerAccountId: "acc_2", deskbooksAccountId: 4 },
      { tellerAccountId: "acc_missing", deskbooksAccountId: 5 },
    ],
    balancesByTellerId: {
      acc_1: { ledger: "100.25", available: "90.00" },
      acc_2: { ledger: null },
    },
  });
  assert.deepEqual(rows, [
    { accountId: 3, balance: "100.25" },
    { accountId: 4, balance: null },
  ]);
});
