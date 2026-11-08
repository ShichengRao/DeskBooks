import { Fragment } from "react";
import type { Account, AccountCategory, Category, TransactionKind } from "../api/types";
import { Field } from "./Field";
import { useVisibleKinds } from "../lib/kinds";
import { accountCategoryLabel, transactionKindLabel } from "../lib/labels";

// The Transactions tab's filter card and pager, shared with the trip page
// so picking a trip's rows works exactly like browsing the ledger.

export type TransactionFilters = {
  start: string;
  end: string;
  account_id: string;
  account_category: AccountCategory | "";
  category_id: string;
  kind: TransactionKind[];
  amount_min: string;
  amount_max: string;
  q: string;
};

export const EMPTY_FILTERS: TransactionFilters = {
  start: "",
  end: "",
  account_id: "",
  account_category: "",
  category_id: "",
  kind: [],
  amount_min: "",
  amount_max: "",
  q: "",
};

/** Filters as `/api/transactions` query parameters. */
export function transactionFilterArgs(filters: TransactionFilters) {
  return {
    start: filters.start || undefined,
    end: filters.end || undefined,
    account_id: filters.account_id || undefined,
    account_category: filters.account_category ? [filters.account_category] : undefined,
    category_id: filters.category_id || undefined,
    kind: filters.kind.length ? filters.kind : undefined,
    amount_min: filters.amount_min || undefined,
    amount_max: filters.amount_max || undefined,
    q: filters.q || undefined,
  };
}

const ACCOUNT_FILTER_CATEGORIES: AccountCategory[] = [
  "bank",
  "credit",
  "investment",
  "tax_advantaged",
  "nonsense",
  "cash",
];

export function TransactionFiltersCard({
  filters,
  accounts,
  categories,
  onChange,
  datesIgnoredBecause,
}: {
  filters: TransactionFilters;
  accounts: Account[];
  categories: Category[];
  onChange: (filters: TransactionFilters) => void;
  /** Set when the view shows rows regardless of date; the date inputs
   *  stay visible, disabled, with this as the reason. */
  datesIgnoredBecause?: string;
}) {
  const visibleKinds = useVisibleKinds();
  const update = (patch: Partial<TransactionFilters>) => onChange({ ...filters, ...patch });

  return (
    <div className="card p-3">
      <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-9 gap-2 items-end">
        <Field label="Search">
          <input className="input" placeholder="merchant or description" value={filters.q} onChange={(e) => update({ q: e.target.value })} />
        </Field>
        <Field label="From">
          <input type="date" className="input" value={filters.start} disabled={!!datesIgnoredBecause} title={datesIgnoredBecause} onChange={(e) => update({ start: e.target.value })} />
        </Field>
        <Field label="To">
          <input type="date" className="input" value={filters.end} disabled={!!datesIgnoredBecause} title={datesIgnoredBecause} onChange={(e) => update({ end: e.target.value })} />
        </Field>
        <Field label="Account type">
          <select
            className="input"
            value={filters.account_category}
            onChange={(e) => update({ account_category: e.target.value as AccountCategory | "" })}
          >
            <option value="">All types</option>
            {ACCOUNT_FILTER_CATEGORIES.map((cat) => (
              <option key={cat} value={cat}>{accountCategoryLabel(cat)}</option>
            ))}
          </select>
        </Field>
        <Field label="Account">
          <select className="input" value={filters.account_id} onChange={(e) => update({ account_id: e.target.value })}>
            <option value="">All accounts</option>
            {accounts
              .filter((a) => !filters.account_category || a.account_category === filters.account_category)
              .map((a) => (
                <option key={a.id} value={a.id}>{a.name}</option>
              ))}
          </select>
        </Field>
        <Field label="Category">
          <select className="input" value={filters.category_id} onChange={(e) => update({ category_id: e.target.value })}>
            <option value="">All categories</option>
            {/* Parents stay selectable: picking Housing includes its
                subcategories' transactions (the backend expands to
                descendants). */}
            {categories.filter((c) => !c.archived && c.parent_id === null).map((parent) => (
              <Fragment key={parent.id}>
                <option value={parent.id}>{parent.name}</option>
                {categories.filter((c) => !c.archived && c.parent_id === parent.id).map((child) => (
                  <option key={child.id} value={child.id}>{`\u00A0\u00A0\u00A0\u00B7 ${child.name}`}</option>
                ))}
              </Fragment>
            ))}
          </select>
        </Field>
        <Field label="Kind">
          <select
            className="input"
            value={filters.kind[0] ?? ""}
            onChange={(e) => update({ kind: e.target.value ? [e.target.value as TransactionKind] : [] })}
          >
            <option value="">All kinds</option>
            {visibleKinds.map((k) => (
              <option key={k} value={k}>{transactionKindLabel(k)}</option>
            ))}
          </select>
        </Field>
        <Field label="Signed amount from">
          <input
            type="number"
            step="0.01"
            className="input tabular text-right"
            value={filters.amount_min}
            onChange={(e) => update({ amount_min: e.target.value })}
            placeholder="-50.00"
          />
        </Field>
        <Field label="Signed amount to">
          <input
            type="number"
            step="0.01"
            className="input tabular text-right"
            value={filters.amount_max}
            onChange={(e) => update({ amount_max: e.target.value })}
            placeholder="-10.00"
          />
        </Field>
      </div>
    </div>
  );
}

export function TransactionsPagination({
  page,
  pageSize,
  totalPages,
  onPage,
  onPageSize,
}: {
  page: number;
  pageSize: number;
  totalPages: number;
  onPage: (page: number) => void;
  onPageSize: (size: number) => void;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
      <div className="flex items-center gap-2">
        <span className="text-ink-500">Page size</span>
        <select className="input max-w-[5rem]" value={pageSize} onChange={(e) => onPageSize(parseInt(e.target.value, 10))}>
          {[50, 100, 250, 500, 1000].map((n) => (
            <option key={n} value={n}>{n}</option>
          ))}
        </select>
      </div>
      <div className="flex flex-wrap items-center gap-2 tabular">
        <button className="btn" disabled={page === 0} onClick={() => onPage(0)}>«</button>
        <button className="btn" disabled={page === 0} onClick={() => onPage(page - 1)}>‹ Prev</button>
        <span className="text-ink-600">Page {page + 1} of {totalPages.toLocaleString()}</span>
        <button className="btn" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next ›</button>
        <button className="btn" disabled={page + 1 >= totalPages} onClick={() => onPage(totalPages - 1)}>»</button>
      </div>
    </div>
  );
}
