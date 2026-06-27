import type { AccountCategory, TransactionKind } from "../api/types";

export const ACCOUNT_CATEGORY_LABELS: Record<AccountCategory, string> = {
  bank: "Bank Accounts",
  investment: "Investment Accounts",
  nonsense: "Wallets / Crypto / Misc",
  tax_advantaged: "Tax Advantaged Accounts",
  credit: "Credit Cards",
  liability: "Liabilities",
  cash: "Cash",
};

export const TRANSACTION_KIND_LABELS: Record<TransactionKind, string> = {
  expense: "Expense",
  income: "Income",
  transfer: "Transfer",
  investment: "Investment",
  donation: "Donation",
  tax: "Tax",
  cc_payment: "Credit Card Payment",
  refund: "Refund",
  reimbursement: "Reimbursement",
  other_non_expense: "Other Non-Expense",
  uncategorized: "Uncategorized",
};

export function accountCategoryLabel(value: string | null | undefined) {
  if (!value) return "Uncategorized";
  return ACCOUNT_CATEGORY_LABELS[value as AccountCategory] ?? titleize(value);
}

export function transactionKindLabel(value: string | null | undefined) {
  if (!value) return "Uncategorized";
  return TRANSACTION_KIND_LABELS[value as TransactionKind] ?? titleize(value);
}

function titleize(value: string) {
  return value
    .replace(/_/g, " ")
    .replace(/\w\S*/g, (part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase());
}
