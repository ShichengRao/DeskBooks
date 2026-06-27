import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import clsx from "clsx";
import { api, qs } from "../api/client";
import { invalidateTxQueries } from "../api/invalidate";
import { Field } from "../components/Field";
import { SidePanel } from "../components/SidePanel";
import { ALL_KINDS, KindPill } from "../lib/kinds";
import type { Account, AccountCategory, Category, Transaction, TransactionKind } from "../api/types";
import { accountCategoryLabel, transactionKindLabel } from "../lib/labels";
import { currency, dateLabel } from "../lib/fmt";

export function Transactions() {
  const qc = useQueryClient();
  const accounts = useQuery({ queryKey: ["accounts"], queryFn: () => api.get<Account[]>("/api/accounts") });
  const categories = useQuery({ queryKey: ["categories"], queryFn: () => api.get<Category[]>("/api/categories") });

  const [filters, setFilters] = useState({
    start: "",
    end: "",
    account_id: "",
    account_category: "" as AccountCategory | "",
    category_id: "",
    kind: [] as TransactionKind[],
    amount_min: "",
    amount_max: "",
    q: "",
  });
  const [pageSize, setPageSize] = useState(100);
  const [page, setPage] = useState(0);
  // Reset to first page whenever filters change so a narrowed view never
  // strands the user past the new last page.
  useMemo(() => {
    setPage(0);
  }, [filters.start, filters.end, filters.account_id, filters.account_category, filters.category_id, filters.kind.join(","), filters.amount_min, filters.amount_max, filters.q]);
  const [selection, setSelection] = useState<Set<number>>(new Set());
  const [creatingTx, setCreatingTx] = useState(false);
  const [editingTx, setEditingTx] = useState<Transaction | null>(null);
  const [editingSplit, setEditingSplit] = useState<Transaction | "bulk" | null>(null);
  const [editingCategoryTxId, setEditingCategoryTxId] = useState<number | null>(null);

  const apiArgs = {
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
  const txQ = useQuery({
    queryKey: ["transactions", filters, page, pageSize],
    queryFn: () =>
      api.get<Transaction[]>("/api/transactions" + qs({ ...apiArgs, limit: pageSize, offset: page * pageSize })),
  });
  const countQ = useQuery({
    queryKey: ["transactions-count", filters],
    queryFn: () => api.get<{ count: number }>("/api/transactions/count" + qs(apiArgs)),
  });
  const totalRows = countQ.data?.count ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalRows / pageSize));

  const accountById = useMemo(
    () => Object.fromEntries((accounts.data ?? []).map((a) => [a.id, a])),
    [accounts.data],
  );
  const categoryById = useMemo(
    () => Object.fromEntries((categories.data ?? []).map((c) => [c.id, c])),
    [categories.data],
  );
  const categoryGroups = useMemo(() => {
    const cats = categories.data ?? [];
    const parents = cats.filter((c) => c.parent_id === null);
    return parents.map((p) => ({ group: p, leaves: cats.filter((c) => c.parent_id === p.id) }));
  }, [categories.data]);

  const updateTx = useMutation({
    mutationFn: (args: { id: number; patch: Partial<Transaction> }) =>
      api.patch<Transaction>(`/api/transactions/${args.id}`, args.patch),
    onSuccess: () => invalidateTxQueries(qc),
  });

  const createTx = useMutation({
    mutationFn: (body: TransactionFormBody) => api.post<Transaction>("/api/transactions", body),
    onSuccess: () => {
      invalidateTxQueries(qc);
      setCreatingTx(false);
    },
  });

  const editTx = useMutation({
    mutationFn: (args: { id: number; body: TransactionFormBody }) =>
      api.patch<Transaction>(`/api/transactions/${args.id}`, args.body),
    onSuccess: () => {
      invalidateTxQueries(qc);
      setEditingTx(null);
    },
  });

  const bulkUpdate = useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      api.patch<{ updated: number }>("/api/transactions/bulk/update", body),
    onSuccess: () => {
      invalidateTxQueries(qc);
      setSelection(new Set());
    },
  });

  const setSplit = useMutation({
    mutationFn: (args: { id: number; body: TransactionSplitBody }) =>
      fetch(`/api/transactions/${args.id}/split`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(args.body),
      }).then((r) => {
        if (!r.ok) throw new Error("split update failed");
        return r.json() as Promise<Transaction>;
      }),
    onSuccess: () => {
      invalidateTxQueries(qc);
      setEditingSplit(null);
    },
  });

  const deleteTx = useMutation({
    mutationFn: (id: number) => api.del(`/api/transactions/${id}`),
    onSuccess: () => invalidateTxQueries(qc),
  });

  const bulkDelete = useMutation({
    mutationFn: async (ids: number[]) => {
      await Promise.all(ids.map((id) => api.del(`/api/transactions/${id}`)));
      return { deleted: ids.length };
    },
    onSuccess: () => {
      invalidateTxQueries(qc);
      setSelection(new Set());
    },
  });

  const toggle = (id: number) => {
    setSelection((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  };

  const txs = txQ.data ?? [];
  const total = txs.reduce((acc, t) => acc + Number(t.amount), 0);

  const expensesSum = txs.filter((t) => t.kind === "expense").reduce((a, t) => a + Number(t.amount), 0);
  const incomeSum = txs.filter((t) => t.kind === "income").reduce((a, t) => a + Number(t.amount), 0);

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Transactions</h1>
        <div className="flex items-center gap-3">
          <div className="text-sm text-ink-500 tabular">
            {totalRows.toLocaleString()} matching · showing {txs.length} · expense {currency(-expensesSum)} · income {currency(incomeSum)}
          </div>
          <button className="btn-primary" onClick={() => setCreatingTx(true)}>+ Add transaction</button>
        </div>
      </div>

      <div className="card p-3">
        <div className="grid grid-cols-2 md:grid-cols-9 gap-2 items-end">
          <Field label="Search">
            <input
              className="input"
              placeholder="merchant or description"
              value={filters.q}
              onChange={(e) => setFilters({ ...filters, q: e.target.value })}
            />
          </Field>
          <Field label="From">
            <input
              type="date"
              className="input"
              value={filters.start}
              onChange={(e) => setFilters({ ...filters, start: e.target.value })}
            />
          </Field>
          <Field label="To">
            <input
              type="date"
              className="input"
              value={filters.end}
              onChange={(e) => setFilters({ ...filters, end: e.target.value })}
            />
          </Field>
          <Field label="Account type">
            <select
              className="input"
              value={filters.account_category}
              onChange={(e) => setFilters({ ...filters, account_category: e.target.value as AccountCategory | "" })}
            >
              <option value="">All types</option>
              {(["bank", "credit", "investment", "tax_advantaged", "nonsense", "cash"] as AccountCategory[]).map((cat) => (
                <option key={cat} value={cat}>{accountCategoryLabel(cat)}</option>
              ))}
            </select>
          </Field>
          <Field label="Account">
            <select
              className="input"
              value={filters.account_id}
              onChange={(e) => setFilters({ ...filters, account_id: e.target.value })}
            >
              <option value="">All accounts</option>
              {accounts.data
                ?.filter((a) => !filters.account_category || a.account_category === filters.account_category)
                .map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
            </select>
          </Field>
          <Field label="Category">
            <select
              className="input"
              value={filters.category_id}
              onChange={(e) => setFilters({ ...filters, category_id: e.target.value })}
            >
              <option value="">All categories</option>
              {categories.data?.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Kind">
            <select
              className="input"
              value={filters.kind[0] ?? ""}
              onChange={(e) =>
                setFilters({
                  ...filters,
                  kind: e.target.value ? [e.target.value as TransactionKind] : [],
                })
              }
            >
              <option value="">All kinds</option>
              {ALL_KINDS.map((k) => (
                <option key={k} value={k}>
                  {transactionKindLabel(k)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Signed amount from">
            <input
              type="number"
              step="0.01"
              className="input tabular text-right"
              value={filters.amount_min}
              onChange={(e) => setFilters({ ...filters, amount_min: e.target.value })}
              placeholder="-50.00"
            />
          </Field>
          <Field label="Signed amount to">
            <input
              type="number"
              step="0.01"
              className="input tabular text-right"
              value={filters.amount_max}
              onChange={(e) => setFilters({ ...filters, amount_max: e.target.value })}
              placeholder="-10.00"
            />
          </Field>
        </div>
      </div>

      {selection.size > 0 && (
        <div className="card p-3 flex items-center gap-3 bg-brand-50 border-brand-200">
          <div className="text-sm">
            <strong>{selection.size}</strong> selected
          </div>
          <select
            className="input max-w-xs"
            onChange={(e) => {
              if (!e.target.value) return;
              bulkUpdate.mutate({
                ids: Array.from(selection),
                category_id: parseInt(e.target.value, 10),
              });
              e.target.value = "";
            }}
            defaultValue=""
          >
            <option value="" disabled>
              Bulk recategorize as…
            </option>
            {categories.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({transactionKindLabel(c.kind)})
              </option>
            ))}
          </select>
          <select
            className="input max-w-xs"
            onChange={(e) => {
              if (!e.target.value) return;
              bulkUpdate.mutate({ ids: Array.from(selection), kind: e.target.value });
              e.target.value = "";
            }}
            defaultValue=""
          >
            <option value="" disabled>
              Bulk set kind…
            </option>
            {ALL_KINDS.map((k) => (
              <option key={k} value={k}>
                {transactionKindLabel(k)}
              </option>
            ))}
          </select>
          <button className="btn" onClick={() => bulkUpdate.mutate({ ids: Array.from(selection), is_excluded_from_totals: true })}>
            Exclude from totals
          </button>
          <button className="btn" onClick={() => bulkUpdate.mutate({ ids: Array.from(selection), is_excluded_from_totals: false })}>
            Include in totals
          </button>
          <button className="btn" onClick={() => setEditingSplit("bulk")}>
            Mark split
          </button>
          <button
            className="btn"
            onClick={() => bulkUpdate.mutate({ ids: Array.from(selection), clear_split: true })}
          >
            Clear split
          </button>
          <button
            className="btn-danger"
            onClick={() => {
              if (confirm(`Delete ${selection.size} selected transaction(s)?`)) {
                bulkDelete.mutate(Array.from(selection));
              }
            }}
            disabled={bulkDelete.isPending}
          >
            Delete selected
          </button>
          <button className="btn-ghost" onClick={() => setSelection(new Set())}>Clear</button>
        </div>
      )}

      <div className="card overflow-hidden">
        <table className="w-full text-sm tabular">
          <thead className="bg-ink-50 text-left">
            <tr>
              <th className="px-2 py-2 w-8">
                <input
                  type="checkbox"
                  checked={selection.size === txs.length && txs.length > 0}
                  onChange={(e) => setSelection(new Set(e.target.checked ? txs.map((t) => t.id) : []))}
                />
              </th>
              <th className="px-2 py-2 w-24">Date</th>
              <th className="px-2 py-2">Description</th>
              <th className="px-2 py-2 w-36">Account</th>
              <th className="px-2 py-2 w-44">Category</th>
              <th className="px-2 py-2 w-32">Kind</th>
              <th className="px-2 py-2 w-32">Split</th>
              <th className="px-2 py-2 w-32 text-right">Amount</th>
              <th className="px-2 py-2 w-16"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {txQ.isLoading && (
              <tr>
                <td colSpan={9} className="p-8 text-center text-ink-500">
                  Loading…
                </td>
              </tr>
            )}
            {txs.map((t) => {
              const acc = accountById[t.account_id];
              const cat = t.category_id ? categoryById[t.category_id] : null;
              return (
                <tr
                  key={t.id}
                  className={clsx(
                    "table-row-hover",
                    t.is_excluded_from_totals && "opacity-50",
                  )}
                >
                  <td className="px-2 py-1.5">
                    <input
                      type="checkbox"
                      checked={selection.has(t.id)}
                      onChange={() => toggle(t.id)}
                    />
                  </td>
                  <td className="px-2 py-1.5 text-ink-600">{dateLabel(t.date)}</td>
                  <td className="px-2 py-1.5">
                    <div className="font-medium">{t.merchant ?? t.description_normalized ?? t.description_raw}</div>
                    <div className="text-xs text-ink-500 truncate max-w-md">{t.description_raw}</div>
                  </td>
                  <td className="px-2 py-1.5 text-ink-600">{acc?.name ?? "—"}</td>
                  <td className="px-2 py-1.5">
                    {editingCategoryTxId === t.id ? (
                      <select
                        className="input py-0.5 text-xs"
                        value={t.category_id ?? ""}
                        autoFocus
                        onBlur={() => setEditingCategoryTxId(null)}
                        onChange={(e) => {
                          updateTx.mutate({
                            id: t.id,
                            patch: { category_id: e.target.value ? parseInt(e.target.value, 10) : null },
                          });
                          setEditingCategoryTxId(null);
                        }}
                      >
                        <option value="">—</option>
                        {categoryGroups.map(({ group, leaves }) =>
                          leaves.length === 0 ? (
                            <option key={group.id} value={group.id}>
                              {group.name}
                            </option>
                          ) : (
                            <optgroup key={group.id} label={group.name}>
                              {leaves.map((l) => (
                                <option key={l.id} value={l.id}>
                                  {l.name}
                                </option>
                              ))}
                            </optgroup>
                          ),
                        )}
                      </select>
                    ) : (
                      <div className="flex items-center gap-1.5">
                        <span className={clsx("truncate", !cat && "text-ink-400")}>{cat?.name ?? "—"}</span>
                        <button
                          type="button"
                          className="btn-ghost text-xs"
                          onClick={() => setEditingCategoryTxId(t.id)}
                        >
                          Change
                        </button>
                      </div>
                    )}
                  </td>
                  <td className="px-2 py-1.5">
                    <KindPill kind={t.kind} />
                  </td>
                  <td className="px-2 py-1.5 text-xs">
                    {t.split ? (
                      <button className="btn-ghost text-xs" onClick={() => setEditingSplit(t)}>
                        {t.split.group_name} · {(Number(t.split.personal_share) * 100).toFixed(0)}%
                      </button>
                    ) : (
                      <button className="btn-ghost text-xs text-ink-400" onClick={() => setEditingSplit(t)}>
                        Split
                      </button>
                    )}
                  </td>
                  <td
                    className={clsx(
                      "px-2 py-1.5 text-right font-medium",
                      Number(t.amount) < 0 ? "text-bad-600" : "text-good-600",
                    )}
                  >
                    {currency(t.amount)}
                  </td>
                  <td className="px-2 py-1.5 text-right">
                    <button
                      type="button"
                      className="btn-ghost text-xs"
                      onClick={() => setEditingTx(t)}
                    >
                      Edit
                    </button>
                    <button
                      type="button"
                      className="btn-ghost text-xs text-bad-600 hover:bg-bad-500/10"
                      onClick={() => {
                        if (confirm("Delete this transaction?")) deleteTx.mutate(t.id);
                      }}
                      disabled={deleteTx.isPending}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              );
            })}
            {!txQ.isLoading && txs.length === 0 && (
              <tr>
                <td colSpan={9} className="p-8 text-center text-ink-500">
                  No transactions match these filters.
                </td>
              </tr>
            )}
          </tbody>
          {txs.length > 0 && (
            <tfoot className="bg-ink-50">
              <tr>
                <td colSpan={7} className="px-2 py-2 text-right text-ink-500">
                  Page total (signed)
                </td>
                <td className="px-2 py-2 text-right font-semibold">{currency(total)}</td>
                <td></td>
              </tr>
            </tfoot>
          )}
        </table>
      </div>

      <div className="flex items-center justify-between text-sm">
        <div className="flex items-center gap-2">
          <span className="text-ink-500">Page size</span>
          <select
            className="input max-w-[5rem]"
            value={pageSize}
            onChange={(e) => {
              setPageSize(parseInt(e.target.value, 10));
              setPage(0);
            }}
          >
            {[50, 100, 250, 500, 1000].map((n) => (
              <option key={n} value={n}>{n}</option>
            ))}
          </select>
        </div>
        <div className="flex items-center gap-2 tabular">
          <button className="btn" disabled={page === 0} onClick={() => setPage(0)}>«</button>
          <button className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>‹ Prev</button>
          <span className="text-ink-600">
            Page {page + 1} of {totalPages.toLocaleString()}
          </span>
          <button className="btn" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next ›</button>
          <button className="btn" disabled={page + 1 >= totalPages} onClick={() => setPage(totalPages - 1)}>»</button>
        </div>
      </div>

      {creatingTx && (
        <TransactionEditor
          accounts={accounts.data ?? []}
          categories={categories.data ?? []}
          categoryGroups={categoryGroups}
          pending={createTx.isPending}
          onClose={() => setCreatingTx(false)}
          onSave={(body) => createTx.mutate(body)}
        />
      )}
      {editingTx && (
        <TransactionEditor
          tx={editingTx}
          accounts={accounts.data ?? []}
          categories={categories.data ?? []}
          categoryGroups={categoryGroups}
          pending={editTx.isPending}
          onClose={() => setEditingTx(null)}
          onSave={(body) => editTx.mutate({ id: editingTx.id, body })}
        />
      )}
      {editingSplit && (
        <SplitEditor
          tx={editingSplit === "bulk" ? null : editingSplit}
          selectedCount={selection.size}
          pending={setSplit.isPending || bulkUpdate.isPending}
          onClose={() => setEditingSplit(null)}
          onSave={(body) => {
            if (editingSplit === "bulk") {
              bulkUpdate.mutate({
                ids: Array.from(selection),
                split_group_name: body.group_name,
                split_personal_share: body.personal_share,
                split_notes: body.notes,
              });
              setEditingSplit(null);
            } else {
              setSplit.mutate({ id: editingSplit.id, body });
            }
          }}
          onClear={() => {
            if (editingSplit === "bulk") {
              bulkUpdate.mutate({ ids: Array.from(selection), clear_split: true });
              setEditingSplit(null);
            } else {
              setSplit.mutate({ id: editingSplit.id, body: { group_name: null, personal_share: "0.5", notes: null } });
            }
          }}
        />
      )}
    </div>
  );
}

type TransactionSplitBody = {
  group_name: string | null;
  personal_share: string;
  notes: string | null;
};

type TransactionFormBody = {
  account_id: number;
  date: string;
  post_date?: string | null;
  description_raw: string;
  description_normalized?: string | null;
  merchant?: string | null;
  amount: string;
  category_id?: number | null;
  kind: TransactionKind;
  is_excluded_from_totals: boolean;
  notes?: string | null;
};

function SplitEditor({
  tx,
  selectedCount,
  pending,
  onClose,
  onSave,
  onClear,
}: {
  tx: Transaction | null;
  selectedCount: number;
  pending: boolean;
  onClose: () => void;
  onSave: (body: TransactionSplitBody) => void;
  onClear: () => void;
}) {
  const [groupName, setGroupName] = useState(tx?.split?.group_name ?? "roommate");
  const [sharePercent, setSharePercent] = useState(
    tx?.split ? String(Number(tx.split.personal_share) * 100) : Number(tx?.amount ?? 0) > 0 ? "0" : "50",
  );
  const [notes, setNotes] = useState(tx?.split?.notes ?? "");

  return (
    <SidePanel
      title={tx ? "Split transaction" : `Split ${selectedCount} selected`}
      onClose={onClose}
      onSubmit={() =>
        onSave({
          group_name: groupName.trim() || null,
          personal_share: String(Math.max(0, Math.min(100, Number(sharePercent) || 0)) / 100),
          notes: notes.trim() || null,
        })
      }
      maxWidth="max-w-lg"
    >
      <div className="space-y-3">
        <Field label="Split group">
          <input className="input" value={groupName} onChange={(e) => setGroupName(e.target.value)} />
        </Field>
        <Field label="Personal share percent">
          <input
            type="number"
            min="0"
            max="100"
            step="1"
            className="input tabular text-right"
            value={sharePercent}
            onChange={(e) => setSharePercent(e.target.value)}
          />
        </Field>
        <div className="text-xs text-ink-500">
          Use 50 for shared bills where half is yours. Use 0 for reimbursement inflows so they reconcile the split without counting as income.
        </div>
        <Field label="Notes">
          <textarea className="input min-h-20" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button type="button" className="btn-danger" onClick={onClear} disabled={pending}>Clear split</button>
          <button type="submit" className="btn-primary" disabled={pending}>Save split</button>
        </div>
      </div>
    </SidePanel>
  );
}

function TransactionEditor({
  tx,
  accounts,
  categories,
  categoryGroups,
  pending,
  onClose,
  onSave,
}: {
  tx?: Transaction;
  accounts: Account[];
  categories: Category[];
  categoryGroups: { group: Category; leaves: Category[] }[];
  pending: boolean;
  onClose: () => void;
  onSave: (body: TransactionFormBody) => void;
}) {
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState({
    account_id: tx ? String(tx.account_id) : accounts[0]?.id ? String(accounts[0].id) : "",
    date: tx?.date ?? today,
    post_date: tx?.post_date ?? "",
    description_raw: tx?.description_raw ?? "",
    merchant: tx?.merchant ?? "",
    amount: tx?.amount ?? "",
    category_id: tx?.category_id ? String(tx.category_id) : "",
    kind: tx?.kind ?? ("expense" as TransactionKind),
    is_excluded_from_totals: tx?.is_excluded_from_totals ?? false,
    notes: tx?.notes ?? "",
  });
  const categoryById = useMemo(
    () => Object.fromEntries(categories.map((c) => [c.id, c])),
    [categories],
  );

  const submit = () => {
    if (!form.account_id || !form.date || !form.description_raw.trim() || !form.amount) {
      alert("Account, date, description, and amount are required.");
      return;
    }
    onSave({
      account_id: parseInt(form.account_id, 10),
      date: form.date,
      post_date: form.post_date || null,
      description_raw: form.description_raw.trim(),
      merchant: form.merchant.trim() || null,
      amount: form.amount,
      category_id: form.category_id ? parseInt(form.category_id, 10) : null,
      kind: form.kind,
      is_excluded_from_totals: form.is_excluded_from_totals,
      notes: form.notes.trim() || null,
    });
  };

  return (
    <SidePanel title={tx ? "Edit transaction" : "Add transaction"} onClose={onClose} onSubmit={submit}>
      <div className="space-y-3">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <Field label="Account">
            <select
              className="input"
              value={form.account_id}
              onChange={(e) => setForm({ ...form, account_id: e.target.value })}
              required
            >
              <option value="">Choose account</option>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>{a.name}</option>
              ))}
            </select>
          </Field>
          <Field label="Date">
            <input
              type="date"
              className="input"
              value={form.date}
              onChange={(e) => setForm({ ...form, date: e.target.value })}
              required
            />
          </Field>
          <Field label="Post date">
            <input
              type="date"
              className="input"
              value={form.post_date}
              onChange={(e) => setForm({ ...form, post_date: e.target.value })}
            />
          </Field>
          <Field label="Signed amount">
            <input
              type="number"
              step="0.01"
              className="input"
              placeholder="-42.50"
              value={form.amount}
              onChange={(e) => setForm({ ...form, amount: e.target.value })}
              required
            />
          </Field>
          <Field label="Kind">
            <select
              className="input"
              value={form.kind}
              onChange={(e) => setForm({ ...form, kind: e.target.value as TransactionKind })}
            >
              {ALL_KINDS.map((k) => (
                <option key={k} value={k}>{transactionKindLabel(k)}</option>
              ))}
            </select>
          </Field>
        </div>

        <Field label="Description">
          <input
            className="input"
            value={form.description_raw}
            onChange={(e) => setForm({ ...form, description_raw: e.target.value })}
            required
          />
        </Field>
        <Field label="Merchant">
          <input
            className="input"
            value={form.merchant}
            onChange={(e) => setForm({ ...form, merchant: e.target.value })}
          />
        </Field>
        <Field label="Category">
          <select
            className="input"
            value={form.category_id}
            onChange={(e) => {
              const category = e.target.value ? categoryById[parseInt(e.target.value, 10)] : null;
              setForm({
                ...form,
                category_id: e.target.value,
                kind: category ? category.kind : form.kind,
              });
            }}
          >
            <option value="">No category</option>
            {categoryGroups.map(({ group, leaves }) =>
              leaves.length === 0 ? (
                <option key={group.id} value={group.id}>{group.name}</option>
              ) : (
                <optgroup key={group.id} label={group.name}>
                  {leaves.map((l) => (
                    <option key={l.id} value={l.id}>{l.name}</option>
                  ))}
                </optgroup>
              ),
            )}
          </select>
        </Field>
        <label className="flex items-center gap-2 text-sm text-ink-700">
          <input
            type="checkbox"
            checked={form.is_excluded_from_totals}
            onChange={(e) => setForm({ ...form, is_excluded_from_totals: e.target.checked })}
          />
          Exclude from totals
        </label>
        <Field label="Notes">
          <textarea
            className="input min-h-24"
            value={form.notes}
            onChange={(e) => setForm({ ...form, notes: e.target.value })}
          />
        </Field>

        <div className="sticky bottom-0 z-10 -mx-6 flex justify-end gap-2 border-t border-ink-100 bg-white px-6 py-3">
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={pending}>
            {tx ? "Save transaction" : "Add transaction"}
          </button>
        </div>
      </div>
    </SidePanel>
  );
}
