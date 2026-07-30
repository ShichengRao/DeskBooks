import assert from "node:assert/strict";
import test from "node:test";

import {
  buildStagedBalances,
  buildStagedTransactions,
  STAGED_BALANCES_FORMAT,
  STAGED_TRANSACTIONS_FORMAT,
} from "../src/staged-formats.mjs";
import { groupMappings, normalizePlaidBalances, normalizePlaidTransactions } from "../fetchers/plaid.mjs";

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

test("staged payloads carry a profile stamp only when one is given", () => {
  const stamped = buildStagedTransactions({ accountId: 1, transactions: [], profile: "personal" });
  assert.equal(stamped.profile, "personal");
  const unstamped = buildStagedTransactions({ accountId: 1, transactions: [] });
  assert.ok(!("profile" in unstamped));

  const balances = buildStagedBalances({ asOf: "2026-07-30", rows: [], profile: "personal" });
  assert.equal(balances.profile, "personal");
  assert.ok(!("profile" in buildStagedBalances({ asOf: "2026-07-30", rows: [] })));

  assert.throws(
    () => buildStagedBalances({ asOf: "2026-07-30", rows: [], profile: "  " }),
    /profile must be a non-empty string/,
  );
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

test("normalizePlaidTransactions flips Plaid's outflow-positive sign convention", () => {
  const rows = normalizePlaidTransactions({
    transactions: [
      {
        transaction_id: "txn_a",
        date: "2026-07-03",
        authorized_date: "2026-07-01",
        name: "COFFEE SHOP",
        amount: 12.34,
        pending: false,
        merchant_name: "Coffee Co",
      },
      { transaction_id: "txn_b", date: "2026-07-02", name: "PAYROLL", amount: -2500, pending: false },
      { transaction_id: "txn_c", date: "2026-07-03", name: "HOLD", amount: 5, pending: true },
      { transaction_id: "txn_d", date: "2026-07-04", name: "ZERO", amount: 0, pending: false },
    ],
  });
  assert.equal(rows[0].amount, "-12.34"); // Plaid positive outflow -> DeskBooks negative
  assert.equal(rows[0].merchant, "Coffee Co");
  assert.equal(rows[0].date, "2026-07-01"); // authorized (transaction) date wins
  assert.equal(rows[0].post_date, "2026-07-03"); // Plaid's date field is the posted date
  assert.equal(rows[1].post_date, null); // no authorized_date -> no post_date claim
  assert.equal(rows[1].amount, "2500"); // Plaid negative inflow -> DeskBooks positive
  assert.equal(rows[1].merchant, null);
  assert.equal(rows[2].pending, true);
  assert.equal(rows[3].amount, "0.00");
});

test("normalizePlaidTransactions supports the invertAmounts escape hatch", () => {
  const rows = normalizePlaidTransactions({
    transactions: [
      { transaction_id: "a", date: "2026-07-01", name: "X", amount: 12.34, pending: false },
    ],
    invertAmounts: true,
  });
  assert.equal(rows[0].amount, "12.34");
});

test("normalizePlaidBalances maps current balances through the account mapping", () => {
  const rows = normalizePlaidBalances({
    mappings: [
      { plaidAccountId: "acc_1", deskbooksAccountId: 3 },
      { plaidAccountId: "acc_2", deskbooksAccountId: 4 },
      { plaidAccountId: "acc_missing", deskbooksAccountId: 5 },
    ],
    accountsById: {
      acc_1: { balances: { current: 100.25, available: 90 } },
      acc_2: { balances: { current: null } },
    },
  });
  assert.deepEqual(rows, [
    { accountId: 3, balance: "100.25" },
    { accountId: 4, balance: null },
  ]);
});

test("normalizePlaidBalances sums provider accounts that share a DeskBooks account", () => {
  const rows = normalizePlaidBalances({
    mappings: [
      { plaidAccountId: "cd_1", deskbooksAccountId: 7 },
      { plaidAccountId: "cd_2", deskbooksAccountId: 7 },
      { plaidAccountId: "cd_null", deskbooksAccountId: 7 },
      { plaidAccountId: "sav", deskbooksAccountId: 6 },
    ],
    accountsById: {
      cd_1: { balances: { current: 1000.1 } },
      cd_2: { balances: { current: 2000.05 } },
      cd_null: { balances: { current: null } },
      sav: { balances: { current: 55.55 } },
    },
  });
  assert.deepEqual(rows, [
    { accountId: 7, balance: "3000.15" },
    { accountId: 6, balance: "55.55" },
  ]);
});

test("groupMappings folds many provider accounts into one DeskBooks account", () => {
  const grouped = groupMappings([
    { plaidAccountId: "a", deskbooksAccountId: 1 },
    { plaidAccountId: "b", deskbooksAccountId: 2 },
    { plaidAccountId: "c", deskbooksAccountId: 1 },
  ]);
  assert.deepEqual([...grouped.entries()], [
    [1, ["a", "c"]],
    [2, ["b"]],
  ]);
});
