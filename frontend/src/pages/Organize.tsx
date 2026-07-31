import { Fragment, useMemo } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { ALL_KINDS } from "../lib/kinds";
import { accountCategoryLabel, transactionKindLabel } from "../lib/labels";
import type {
  Account,
  AccountCategory,
  AccountType,
  Category,
  CategoryUsage,
  KindSettings,
} from "../api/types";

const ACCOUNT_CATEGORIES: AccountCategory[] = [
  "bank",
  "investment",
  "tax_advantaged",
  "credit",
  "liability",
  "nonsense",
  "cash",
];
const ACCOUNT_TYPES: AccountType[] = [
  "checking",
  "savings",
  "cd",
  "brokerage",
  "crypto",
  "wallet",
  "retirement",
  "college",
  "hsa",
  "credit_card",
  "cash",
  "other",
];

export function Organize() {
  const qc = useQueryClient();
  const categories = useQuery({
    queryKey: ["categories-all"],
    queryFn: () => api.get<Category[]>("/api/categories?include_archived=true"),
  });
  const usage = useQuery({
    queryKey: ["category-usage"],
    queryFn: () => api.get<CategoryUsage[]>("/api/categories/usage"),
  });
  const kindSettings = useQuery({
    queryKey: ["kind-settings"],
    queryFn: () => api.get<KindSettings>("/api/settings/kinds"),
  });
  const accounts = useQuery({ queryKey: ["accounts"], queryFn: () => api.get<Account[]>("/api/accounts") });

  const invalidateCategories = () => {
    qc.invalidateQueries({ queryKey: ["categories-all"] });
    qc.invalidateQueries({ queryKey: ["categories"] });
    qc.invalidateQueries({ queryKey: ["category-usage"] });
  };
  const patchCategory = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Record<string, unknown> }) =>
      api.patch(`/api/categories/${id}`, body),
    onSuccess: invalidateCategories,
  });
  const archiveCategory = useMutation({
    mutationFn: (id: number) => api.del(`/api/categories/${id}`),
    onSuccess: invalidateCategories,
  });
  const putKinds = useMutation({
    mutationFn: (hidden: string[]) => api.put<KindSettings>("/api/settings/kinds", { hidden }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["kind-settings"] }),
  });
  const patchAccount = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Record<string, unknown> }) =>
      api.patch(`/api/accounts/${id}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["accounts"] }),
  });

  const usageById = useMemo(
    () => Object.fromEntries((usage.data ?? []).map((u) => [u.category_id, u])),
    [usage.data],
  );
  const all = categories.data ?? [];
  const active = all.filter((c) => !c.archived);
  const archived = all.filter((c) => c.archived);
  const parents = active.filter((c) => c.parent_id === null);
  const childrenOf = (id: number) => active.filter((c) => c.parent_id === id);

  const usageText = (u: CategoryUsage | undefined) => {
    if (!u) return "";
    const bits = [];
    if (u.transactions) bits.push(`${u.transactions.toLocaleString()} txns`);
    if (u.rules) bits.push(`${u.rules} rule${u.rules === 1 ? "" : "s"}`);
    if (u.budgets) bits.push(`${u.budgets} budget${u.budgets === 1 ? "" : "s"}`);
    return bits.length ? bits.join(" · ") : "unused";
  };

  const archiveWithWarning = (category: Category) => {
    if (childrenOf(category.id).length > 0) {
      alert(`"${category.name}" still has subcategories — move or archive them first.`);
      return;
    }
    const u = usageById[category.id];
    const used = u && (u.transactions || u.rules || u.budgets);
    const message = used
      ? `"${category.name}" is still referenced by ${usageText(u)}. Archiving hides it from pickers; those references keep working and it can be restored here. Continue?`
      : `Archive "${category.name}"? It can be restored from the archived list.`;
    if (confirm(message)) archiveCategory.mutate(category.id);
  };

  const hidden = new Set(kindSettings.data?.hidden ?? []);
  const toggleKind = (kind: string) => {
    const counts = kindSettings.data?.counts ?? {};
    if (!hidden.has(kind) && (counts[kind] ?? 0) > 0) {
      const ok = confirm(
        `${counts[kind].toLocaleString()} transaction(s) currently use the "${transactionKindLabel(kind)}" kind. ` +
          "Hiding removes it from pickers and filters only — those rows keep their kind and still count in analytics. Continue?",
      );
      if (!ok) return;
    }
    const next = new Set(hidden);
    if (next.has(kind)) next.delete(kind);
    else next.add(kind);
    putKinds.mutate([...next]);
  };

  const busy = patchCategory.isPending || archiveCategory.isPending;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold tracking-tight">Organize</h1>

      <div className="card p-4">
        <div className="flex items-baseline justify-between mb-3">
          <div className="text-sm font-medium">Categories</div>
          <div className="text-xs text-ink-500">
            One level of nesting: top-level groups with leaf subcategories.
          </div>
        </div>
        <div className="space-y-1">
          {parents.map((parent) => (
            <Fragment key={parent.id}>
              <CategoryRow
                category={parent}
                depth={0}
                parents={parents}
                hasChildren={childrenOf(parent.id).length > 0}
                usage={usageText(usageById[parent.id])}
                busy={busy}
                onNest={(parentId) => patchCategory.mutate({ id: parent.id, body: { parent_id: parentId } })}
                onArchive={() => archiveWithWarning(parent)}
              />
              {childrenOf(parent.id).map((child) => (
                <CategoryRow
                  key={child.id}
                  category={child}
                  depth={1}
                  parents={parents}
                  hasChildren={false}
                  usage={usageText(usageById[child.id])}
                  busy={busy}
                  onNest={(parentId) => patchCategory.mutate({ id: child.id, body: { parent_id: parentId } })}
                  onArchive={() => archiveWithWarning(child)}
                />
              ))}
            </Fragment>
          ))}
        </div>
        {(patchCategory.isError || archiveCategory.isError) && (
          <div className="mt-2 text-sm text-bad-600">
            {String(((patchCategory.error || archiveCategory.error) as Error).message)}
          </div>
        )}
        {archived.length > 0 && (
          <div className="mt-4 border-t border-ink-100 pt-3">
            <div className="label mb-2">Archived</div>
            <div className="space-y-1">
              {archived.map((category) => (
                <div key={category.id} className="flex items-center gap-2 text-sm text-ink-500">
                  <span className="flex-1 italic">{category.name}</span>
                  <span className="text-xs">{usageText(usageById[category.id])}</span>
                  <button
                    className="btn-ghost text-xs"
                    disabled={busy}
                    onClick={() => patchCategory.mutate({ id: category.id, body: { archived: false } })}
                  >
                    Restore
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      <div className="card p-4">
        <div className="flex items-baseline justify-between mb-3">
          <div className="text-sm font-medium">Transaction kinds</div>
          <div className="text-xs text-ink-500">
            Hiding removes a kind from pickers and filters; existing rows keep it and analytics still
            count them.
          </div>
        </div>
        <div className="space-y-1">
          {ALL_KINDS.filter((k) => k !== "uncategorized").map((kind) => {
            const count = kindSettings.data?.counts?.[kind] ?? 0;
            const isHidden = hidden.has(kind);
            return (
              <div key={kind} className="flex items-center gap-3 text-sm">
                <span className={isHidden ? "flex-1 text-ink-400 line-through" : "flex-1"}>
                  {transactionKindLabel(kind)}
                </span>
                <span className="text-xs text-ink-500 tabular">
                  {count ? `${count.toLocaleString()} txns` : "unused"}
                </span>
                <button
                  className="btn-ghost text-xs"
                  disabled={putKinds.isPending}
                  onClick={() => toggleKind(kind)}
                >
                  {isHidden ? "Show" : "Hide"}
                </button>
              </div>
            );
          })}
        </div>
        {putKinds.isError && (
          <div className="mt-2 text-sm text-bad-600">{String((putKinds.error as Error).message)}</div>
        )}
      </div>

      <div className="card p-4">
        <div className="flex items-baseline justify-between mb-3">
          <div className="text-sm font-medium">Accounts</div>
          <div className="text-xs text-ink-500">
            Account type drives the net-worth grouping and filters.
          </div>
        </div>
        <div className="space-y-1">
          {ACCOUNT_CATEGORIES.flatMap((group) =>
            (accounts.data ?? [])
              .filter((account) => account.account_category === group)
              .map((account) => (
                <div key={account.id} className="flex items-center gap-2 text-sm">
                  <span className={account.is_closed ? "flex-1 text-ink-400 italic" : "flex-1"}>
                    {account.name}
                    {account.institution && (
                      <span className="text-xs text-ink-400"> · {account.institution}</span>
                    )}
                  </span>
                  <select
                    className="input max-w-[14rem] text-xs"
                    value={account.account_category}
                    disabled={patchAccount.isPending}
                    onChange={(e) =>
                      patchAccount.mutate({ id: account.id, body: { account_category: e.target.value } })
                    }
                  >
                    {ACCOUNT_CATEGORIES.map((category) => (
                      <option key={category} value={category}>{accountCategoryLabel(category)}</option>
                    ))}
                  </select>
                  <select
                    className="input max-w-[10rem] text-xs"
                    value={account.type}
                    disabled={patchAccount.isPending}
                    onChange={(e) => patchAccount.mutate({ id: account.id, body: { type: e.target.value } })}
                  >
                    {ACCOUNT_TYPES.map((type) => (
                      <option key={type} value={type}>{type.replace(/_/g, " ")}</option>
                    ))}
                  </select>
                </div>
              )),
          )}
        </div>
        {patchAccount.isError && (
          <div className="mt-2 text-sm text-bad-600">{String((patchAccount.error as Error).message)}</div>
        )}
      </div>
    </div>
  );
}

function CategoryRow({
  category,
  depth,
  parents,
  hasChildren,
  usage,
  busy,
  onNest,
  onArchive,
}: {
  category: Category;
  depth: number;
  parents: Category[];
  hasChildren: boolean;
  usage: string;
  busy: boolean;
  onNest: (parentId: number | null) => void;
  onArchive: () => void;
}) {
  return (
    <div className="flex items-center gap-2 text-sm">
      <span className="flex-1" style={{ paddingLeft: depth * 20 }}>
        {depth > 0 && <span className="text-ink-400">· </span>}
        {category.name}
        <span className="text-xs text-ink-400"> · {usage}</span>
      </span>
      {hasChildren ? (
        <span
          className="text-xs text-ink-400 px-2"
          title="Has subcategories — move them out before nesting this one"
        >
          group
        </span>
      ) : (
        <select
          className="input max-w-[13rem] text-xs"
          value={category.parent_id ?? ""}
          disabled={busy}
          onChange={(e) => onNest(e.target.value ? parseInt(e.target.value, 10) : null)}
        >
          <option value="">Top level</option>
          {parents
            .filter((p) => p.id !== category.id)
            .map((p) => (
              <option key={p.id} value={p.id}>under {p.name}</option>
            ))}
        </select>
      )}
      <button className="btn-ghost text-xs text-bad-600" disabled={busy} onClick={onArchive}>
        Archive
      </button>
    </div>
  );
}
