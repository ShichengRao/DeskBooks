import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { api, qs } from "../api/client";
import { invalidateTxQueries } from "../api/invalidate";
import { Field } from "../components/Field";
import { SidePanel } from "../components/SidePanel";
import { TransactionsTable } from "../components/TransactionsTable";
import { ALL_KINDS } from "../lib/kinds";
import type { Account, AccountCategory, Category, Transaction, TransactionKind } from "../api/types";
import { accountCategoryLabel, transactionKindLabel } from "../lib/labels";
import { currency } from "../lib/fmt";

type TransactionFilters = {
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

type CategoryGroup = { group: Category; leaves: Category[] };

const ACCOUNT_FILTER_CATEGORIES: AccountCategory[] = [
  "bank",
  "credit",
  "investment",
  "tax_advantaged",
  "nonsense",
  "cash",
];

export function Transactions() {
  const qc = useQueryClient();
  const [filters, setFilters] = useState<TransactionFilters>({
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
  useEffect(() => {
    setPage(0);
  }, [filters.start, filters.end, filters.account_id, filters.account_category, filters.category_id, filters.kind.join(","), filters.amount_min, filters.amount_max, filters.q]);
  const [selection, setSelection] = useState<Set<number>>(new Set());
  const [creatingTx, setCreatingTx] = useState(false);
  const [editingTx, setEditingTx] = useState<Transaction | null>(null);
  const [editingSplit, setEditingSplit] = useState<Transaction | "bulk" | null>(null);
  const [editingCategoryTxId, setEditingCategoryTxId] = useState<number | null>(null);
  const {
    accounts,
    categories,
    txQ,
    totalRows,
    totalPages,
    accountById,
    categoryById,
    categoryGroups,
  } = useTransactionPageData(filters, page, pageSize);
  const { updateTx, createTx, editTx, bulkUpdate, setSplit, deleteTx, bulkDelete } =
    useTransactionMutations({ qc, setCreatingTx, setEditingTx, setEditingSplit, setSelection });

  const toggleSelection = (id: number) => {
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
  const selectedIds = Array.from(selection);

  return (
    <div className="space-y-4">
      <TransactionsHeader
        totalRows={totalRows}
        showing={txs.length}
        expensesSum={expensesSum}
        incomeSum={incomeSum}
        onCreate={() => setCreatingTx(true)}
      />
      <TransactionFiltersCard
        filters={filters}
        accounts={accounts.data ?? []}
        categories={categories.data ?? []}
        onChange={setFilters}
      />
      <BulkTransactionActions
        selectedIds={selectedIds}
        categories={categories.data ?? []}
        pendingDelete={bulkDelete.isPending}
        onBulkUpdate={(patch) => bulkUpdate.mutate({ ids: selectedIds, ...patch })}
        onBulkDelete={() => bulkDelete.mutate(selectedIds)}
        onMarkSplit={() => setEditingSplit("bulk")}
        onClear={() => setSelection(new Set())}
      />
      <TransactionsTable
        txs={txs}
        loading={txQ.isLoading}
        total={total}
        selection={selection}
        accountById={accountById}
        categoryById={categoryById}
        categoryGroups={categoryGroups}
        editingCategoryTxId={editingCategoryTxId}
        deletePending={deleteTx.isPending}
        onSelectAll={(ids) => setSelection(new Set(ids))}
        onToggleSelection={toggleSelection}
        onEditCategory={setEditingCategoryTxId}
        onUpdateCategory={(id, categoryId) => updateTx.mutate({ id, patch: { category_id: categoryId } })}
        onEditSplit={setEditingSplit}
        onEdit={setEditingTx}
        onDelete={(id) => deleteTx.mutate(id)}
      />
      <TransactionsPagination
        page={page}
        pageSize={pageSize}
        totalPages={totalPages}
        onPage={setPage}
        onPageSize={(size) => {
          setPageSize(size);
          setPage(0);
        }}
      />
      <TransactionDialogs
        creating={creatingTx}
        editingTx={editingTx}
        editingSplit={editingSplit}
        selectionSize={selection.size}
        accounts={accounts.data ?? []}
        categories={categories.data ?? []}
        categoryGroups={categoryGroups}
        createPending={createTx.isPending}
        editPending={editTx.isPending}
        splitPending={setSplit.isPending || bulkUpdate.isPending}
        onCloseCreate={() => setCreatingTx(false)}
        onCloseEdit={() => setEditingTx(null)}
        onCloseSplit={() => setEditingSplit(null)}
        onCreate={(body) => createTx.mutate(body)}
        onEdit={(id, body) => editTx.mutate({ id, body })}
        onSaveSplit={(body) => {
          if (editingSplit === "bulk") {
            bulkUpdate.mutate({
              ids: selectedIds,
              split_group_name: body.group_name,
              split_personal_share: body.personal_share,
              split_notes: body.notes,
            });
            setEditingSplit(null);
          } else if (editingSplit) {
            setSplit.mutate({ id: editingSplit.id, body });
          }
        }}
        onClearSplit={() => {
          if (editingSplit === "bulk") {
            bulkUpdate.mutate({ ids: selectedIds, clear_split: true });
            setEditingSplit(null);
          } else if (editingSplit) {
            setSplit.mutate({ id: editingSplit.id, body: { group_name: null, personal_share: "0.5", notes: null } });
          }
        }}
      />
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

function useTransactionPageData(filters: TransactionFilters, page: number, pageSize: number) {
  const accounts = useQuery({ queryKey: ["accounts"], queryFn: () => api.get<Account[]>("/api/accounts") });
  const categories = useQuery({ queryKey: ["categories"], queryFn: () => api.get<Category[]>("/api/categories") });
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
    return parents.map((group) => ({ group, leaves: cats.filter((c) => c.parent_id === group.id) }));
  }, [categories.data]);
  const totalRows = countQ.data?.count ?? 0;

  return {
    accounts,
    categories,
    txQ,
    totalRows,
    totalPages: Math.max(1, Math.ceil(totalRows / pageSize)),
    accountById,
    categoryById,
    categoryGroups,
  };
}

function useTransactionMutations({
  qc,
  setCreatingTx,
  setEditingTx,
  setEditingSplit,
  setSelection,
}: {
  qc: QueryClient;
  setCreatingTx: (value: boolean) => void;
  setEditingTx: (value: Transaction | null) => void;
  setEditingSplit: (value: Transaction | "bulk" | null) => void;
  setSelection: (value: Set<number>) => void;
}) {
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
      }).then((response) => {
        if (!response.ok) throw new Error("split update failed");
        return response.json() as Promise<Transaction>;
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

  return { updateTx, createTx, editTx, bulkUpdate, setSplit, deleteTx, bulkDelete };
}

function TransactionsHeader({
  totalRows,
  showing,
  expensesSum,
  incomeSum,
  onCreate,
}: {
  totalRows: number;
  showing: number;
  expensesSum: number;
  incomeSum: number;
  onCreate: () => void;
}) {
  return (
    <div className="flex items-baseline justify-between">
      <h1 className="text-2xl font-semibold tracking-tight">Transactions</h1>
      <div className="flex items-center gap-3">
        <div className="text-sm text-ink-500 tabular">
          {totalRows.toLocaleString()} matching · showing {showing} · expense {currency(-expensesSum)} · income {currency(incomeSum)}
        </div>
        <button className="btn-primary" onClick={onCreate}>+ Add transaction</button>
      </div>
    </div>
  );
}

function TransactionFiltersCard({
  filters,
  accounts,
  categories,
  onChange,
}: {
  filters: TransactionFilters;
  accounts: Account[];
  categories: Category[];
  onChange: (filters: TransactionFilters) => void;
}) {
  const update = (patch: Partial<TransactionFilters>) => onChange({ ...filters, ...patch });

  return (
    <div className="card p-3">
      <div className="grid grid-cols-2 md:grid-cols-9 gap-2 items-end">
        <Field label="Search">
          <input className="input" placeholder="merchant or description" value={filters.q} onChange={(e) => update({ q: e.target.value })} />
        </Field>
        <Field label="From">
          <input type="date" className="input" value={filters.start} onChange={(e) => update({ start: e.target.value })} />
        </Field>
        <Field label="To">
          <input type="date" className="input" value={filters.end} onChange={(e) => update({ end: e.target.value })} />
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
            {categories.map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
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
            {ALL_KINDS.map((k) => (
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

function BulkTransactionActions({
  selectedIds,
  categories,
  pendingDelete,
  onBulkUpdate,
  onBulkDelete,
  onMarkSplit,
  onClear,
}: {
  selectedIds: number[];
  categories: Category[];
  pendingDelete: boolean;
  onBulkUpdate: (patch: Record<string, unknown>) => void;
  onBulkDelete: () => void;
  onMarkSplit: () => void;
  onClear: () => void;
}) {
  if (selectedIds.length === 0) return null;

  return (
    <div className="card p-3 flex items-center gap-3 bg-brand-50 border-brand-200">
      <div className="text-sm"><strong>{selectedIds.length}</strong> selected</div>
      <select
        className="input max-w-xs"
        onChange={(e) => {
          if (!e.target.value) return;
          onBulkUpdate({ category_id: parseInt(e.target.value, 10) });
          e.target.value = "";
        }}
        defaultValue=""
      >
        <option value="" disabled>Bulk recategorize as…</option>
        {categories.map((c) => (
          <option key={c.id} value={c.id}>{c.name} ({transactionKindLabel(c.kind)})</option>
        ))}
      </select>
      <select
        className="input max-w-xs"
        onChange={(e) => {
          if (!e.target.value) return;
          onBulkUpdate({ kind: e.target.value });
          e.target.value = "";
        }}
        defaultValue=""
      >
        <option value="" disabled>Bulk set kind…</option>
        {ALL_KINDS.map((k) => (
          <option key={k} value={k}>{transactionKindLabel(k)}</option>
        ))}
      </select>
      <button className="btn" onClick={() => onBulkUpdate({ is_excluded_from_totals: true })}>Exclude from totals</button>
      <button className="btn" onClick={() => onBulkUpdate({ is_excluded_from_totals: false })}>Include in totals</button>
      <button className="btn" onClick={onMarkSplit}>Mark split</button>
      <button className="btn" onClick={() => onBulkUpdate({ clear_split: true })}>Clear split</button>
      <button
        className="btn-danger"
        onClick={() => {
          if (confirm(`Delete ${selectedIds.length} selected transaction(s)?`)) onBulkDelete();
        }}
        disabled={pendingDelete}
      >
        Delete selected
      </button>
      <button className="btn-ghost" onClick={onClear}>Clear</button>
    </div>
  );
}

function TransactionsPagination({
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
    <div className="flex items-center justify-between text-sm">
      <div className="flex items-center gap-2">
        <span className="text-ink-500">Page size</span>
        <select className="input max-w-[5rem]" value={pageSize} onChange={(e) => onPageSize(parseInt(e.target.value, 10))}>
          {[50, 100, 250, 500, 1000].map((n) => (
            <option key={n} value={n}>{n}</option>
          ))}
        </select>
      </div>
      <div className="flex items-center gap-2 tabular">
        <button className="btn" disabled={page === 0} onClick={() => onPage(0)}>«</button>
        <button className="btn" disabled={page === 0} onClick={() => onPage(page - 1)}>‹ Prev</button>
        <span className="text-ink-600">Page {page + 1} of {totalPages.toLocaleString()}</span>
        <button className="btn" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next ›</button>
        <button className="btn" disabled={page + 1 >= totalPages} onClick={() => onPage(totalPages - 1)}>»</button>
      </div>
    </div>
  );
}

function TransactionDialogs({
  creating,
  editingTx,
  editingSplit,
  selectionSize,
  accounts,
  categories,
  categoryGroups,
  createPending,
  editPending,
  splitPending,
  onCloseCreate,
  onCloseEdit,
  onCloseSplit,
  onCreate,
  onEdit,
  onSaveSplit,
  onClearSplit,
}: {
  creating: boolean;
  editingTx: Transaction | null;
  editingSplit: Transaction | "bulk" | null;
  selectionSize: number;
  accounts: Account[];
  categories: Category[];
  categoryGroups: CategoryGroup[];
  createPending: boolean;
  editPending: boolean;
  splitPending: boolean;
  onCloseCreate: () => void;
  onCloseEdit: () => void;
  onCloseSplit: () => void;
  onCreate: (body: TransactionFormBody) => void;
  onEdit: (id: number, body: TransactionFormBody) => void;
  onSaveSplit: (body: TransactionSplitBody) => void;
  onClearSplit: () => void;
}) {
  return (
    <>
      {creating && (
        <TransactionEditor
          accounts={accounts}
          categories={categories}
          categoryGroups={categoryGroups}
          pending={createPending}
          onClose={onCloseCreate}
          onSave={onCreate}
        />
      )}
      {editingTx && (
        <TransactionEditor
          tx={editingTx}
          accounts={accounts}
          categories={categories}
          categoryGroups={categoryGroups}
          pending={editPending}
          onClose={onCloseEdit}
          onSave={(body) => onEdit(editingTx.id, body)}
        />
      )}
      {editingSplit && (
        <SplitEditor
          tx={editingSplit === "bulk" ? null : editingSplit}
          selectedCount={selectionSize}
          pending={splitPending}
          onClose={onCloseSplit}
          onSave={onSaveSplit}
          onClear={onClearSplit}
        />
      )}
    </>
  );
}

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
