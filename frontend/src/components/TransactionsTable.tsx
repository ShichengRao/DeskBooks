import clsx from "clsx";
import type { Account, Category, Transaction } from "../api/types";
import { currency, dateLabel } from "../lib/fmt";
import { KindPill } from "../lib/kinds";

type CategoryGroup = { group: Category; leaves: Category[] };

export function TransactionsTable({
  txs,
  loading,
  total,
  selection,
  accountById,
  categoryById,
  categoryGroups,
  editingCategoryTxId,
  deletePending,
  onSelectAll,
  onToggleSelection,
  onEditCategory,
  onUpdateCategory,
  onEditSplit,
  onEdit,
  onDelete,
}: {
  txs: Transaction[];
  loading: boolean;
  total: number;
  selection: Set<number>;
  accountById: Record<number, Account>;
  categoryById: Record<number, Category>;
  categoryGroups: CategoryGroup[];
  editingCategoryTxId: number | null;
  deletePending: boolean;
  onSelectAll: (ids: number[]) => void;
  onToggleSelection: (id: number) => void;
  onEditCategory: (id: number | null) => void;
  onUpdateCategory: (id: number, categoryId: number | null) => void;
  onEditSplit: (tx: Transaction) => void;
  onEdit: (tx: Transaction) => void;
  onDelete: (id: number) => void;
}) {
  return (
    <div className="card overflow-hidden">
      <table className="w-full text-sm tabular">
        <thead className="bg-ink-50 text-left">
          <tr>
            <th className="px-2 py-2 w-8">
              <input
                type="checkbox"
                checked={selection.size === txs.length && txs.length > 0}
                onChange={(e) => onSelectAll(e.target.checked ? txs.map((tx) => tx.id) : [])}
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
          {loading && <TableMessage>Loading…</TableMessage>}
          {txs.map((tx) => (
            <TransactionRow
              key={tx.id}
              tx={tx}
              account={accountById[tx.account_id]}
              category={tx.category_id ? categoryById[tx.category_id] : null}
              categoryGroups={categoryGroups}
              selected={selection.has(tx.id)}
              editingCategory={editingCategoryTxId === tx.id}
              deletePending={deletePending}
              onToggleSelection={onToggleSelection}
              onEditCategory={onEditCategory}
              onUpdateCategory={onUpdateCategory}
              onEditSplit={onEditSplit}
              onEdit={onEdit}
              onDelete={onDelete}
            />
          ))}
          {!loading && txs.length === 0 && <TableMessage>No transactions match these filters.</TableMessage>}
        </tbody>
        {txs.length > 0 && (
          <tfoot className="bg-ink-50">
            <tr>
              <td colSpan={7} className="px-2 py-2 text-right text-ink-500">Page total (signed)</td>
              <td className="px-2 py-2 text-right font-semibold">{currency(total)}</td>
              <td></td>
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  );
}

function TransactionRow({
  tx,
  account,
  category,
  categoryGroups,
  selected,
  editingCategory,
  deletePending,
  onToggleSelection,
  onEditCategory,
  onUpdateCategory,
  onEditSplit,
  onEdit,
  onDelete,
}: {
  tx: Transaction;
  account?: Account;
  category: Category | null;
  categoryGroups: CategoryGroup[];
  selected: boolean;
  editingCategory: boolean;
  deletePending: boolean;
  onToggleSelection: (id: number) => void;
  onEditCategory: (id: number | null) => void;
  onUpdateCategory: (id: number, categoryId: number | null) => void;
  onEditSplit: (tx: Transaction) => void;
  onEdit: (tx: Transaction) => void;
  onDelete: (id: number) => void;
}) {
  return (
    <tr className={clsx("table-row-hover", tx.is_excluded_from_totals && "opacity-50")}>
      <td className="px-2 py-1.5">
        <input type="checkbox" checked={selected} onChange={() => onToggleSelection(tx.id)} />
      </td>
      <td className="px-2 py-1.5 text-ink-600">{dateLabel(tx.date)}</td>
      <td className="px-2 py-1.5">
        <div className="font-medium">{tx.merchant ?? tx.description_normalized ?? tx.description_raw}</div>
        <div className="text-xs text-ink-500 truncate max-w-md">{tx.description_raw}</div>
      </td>
      <td className="px-2 py-1.5 text-ink-600">{account?.name ?? "—"}</td>
      <td className="px-2 py-1.5">
        <CategoryCell
          tx={tx}
          category={category}
          categoryGroups={categoryGroups}
          editing={editingCategory}
          onEditCategory={onEditCategory}
          onUpdateCategory={onUpdateCategory}
        />
      </td>
      <td className="px-2 py-1.5"><KindPill kind={tx.kind} /></td>
      <td className="px-2 py-1.5 text-xs">
        {tx.split ? (
          <button className="btn-ghost text-xs" onClick={() => onEditSplit(tx)}>
            {tx.split.group_name} · {(Number(tx.split.personal_share) * 100).toFixed(0)}%
          </button>
        ) : (
          <button className="btn-ghost text-xs text-ink-400" onClick={() => onEditSplit(tx)}>Split</button>
        )}
      </td>
      <td className={clsx("px-2 py-1.5 text-right font-medium", Number(tx.amount) < 0 ? "text-bad-600" : "text-good-600")}>
        {currency(tx.amount)}
      </td>
      <td className="px-2 py-1.5 text-right">
        <button type="button" className="btn-ghost text-xs" onClick={() => onEdit(tx)}>Edit</button>
        <button
          type="button"
          className="btn-ghost text-xs text-bad-600 hover:bg-bad-500/10"
          onClick={() => {
            if (confirm("Delete this transaction?")) onDelete(tx.id);
          }}
          disabled={deletePending}
        >
          Delete
        </button>
      </td>
    </tr>
  );
}

function CategoryCell({
  tx,
  category,
  categoryGroups,
  editing,
  onEditCategory,
  onUpdateCategory,
}: {
  tx: Transaction;
  category: Category | null;
  categoryGroups: CategoryGroup[];
  editing: boolean;
  onEditCategory: (id: number | null) => void;
  onUpdateCategory: (id: number, categoryId: number | null) => void;
}) {
  if (editing) {
    return (
      <select
        className="input py-0.5 text-xs"
        value={tx.category_id ?? ""}
        autoFocus
        onBlur={() => onEditCategory(null)}
        onChange={(e) => {
          onUpdateCategory(tx.id, e.target.value ? parseInt(e.target.value, 10) : null);
          onEditCategory(null);
        }}
      >
        <option value="">—</option>
        {categoryGroups.map(({ group, leaves }) =>
          leaves.length === 0 ? (
            <option key={group.id} value={group.id}>{group.name}</option>
          ) : (
            <optgroup key={group.id} label={group.name}>
              {leaves.map((leaf) => (
                <option key={leaf.id} value={leaf.id}>{leaf.name}</option>
              ))}
            </optgroup>
          ),
        )}
      </select>
    );
  }

  return (
    <div className="flex items-center gap-1.5">
      <span className={clsx("truncate", !category && "text-ink-400")}>{category?.name ?? "—"}</span>
      <button type="button" className="btn-ghost text-xs" onClick={() => onEditCategory(tx.id)}>Change</button>
    </div>
  );
}

function TableMessage({ children }: { children: string }) {
  return (
    <tr>
      <td colSpan={9} className="p-8 text-center text-ink-500">{children}</td>
    </tr>
  );
}
