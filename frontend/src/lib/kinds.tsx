import clsx from "clsx";
import type { TransactionKind } from "../api/types";
import { transactionKindLabel } from "./labels";

export const ALL_KINDS: TransactionKind[] = [
  "expense",
  "income",
  "transfer",
  "investment",
  "donation",
  "tax",
  "cc_payment",
  "refund",
  "reimbursement",
  "other_non_expense",
  "uncategorized",
];

export const KIND_TONES: Record<string, string> = {
  expense: "bg-bad-500/10 text-bad-600",
  income: "bg-good-500/10 text-good-600",
  transfer: "bg-ink-200/60 text-ink-700",
  investment: "bg-brand-100 text-brand-700",
  donation: "bg-purple-100 text-purple-700",
  tax: "bg-warn-500/10 text-warn-600",
  cc_payment: "bg-ink-200/60 text-ink-700",
  refund: "bg-good-500/10 text-good-700",
  reimbursement: "bg-good-500/10 text-good-700",
  other_non_expense: "bg-ink-200/60 text-ink-700",
  uncategorized: "bg-ink-100 text-ink-500",
};

export function KindPill({ kind }: { kind: TransactionKind }) {
  return <span className={clsx("pill", KIND_TONES[kind])}>{transactionKindLabel(kind)}</span>;
}
