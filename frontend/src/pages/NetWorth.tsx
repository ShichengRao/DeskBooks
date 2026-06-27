import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api, qs } from "../api/client";
import { ChartLegend } from "../components/ChartLegend";
import { ChartColorControls, useChartColors } from "../components/ChartColorControls";
import { DateRangeControls } from "../components/DateRangeControls";
import { Field } from "../components/Field";
import { SidePanel } from "../components/SidePanel";
import type { Account, AccountCategory, AccountType, NetWorthSeriesPoint, NetWorthSnapshot, SignConvention } from "../api/types";
import { colorAt } from "../lib/chartColors";
import { accountCategoryLabel } from "../lib/labels";
import { compactCurrency, currency, dateLabel, num, shortDateLabel } from "../lib/fmt";

const ACCOUNT_CATEGORY_SERIES = [
  { category: "bank", key: "cat_bank", pctKey: "pct_bank", label: accountCategoryLabel("bank") },
  { category: "investment", key: "cat_investment", pctKey: "pct_investment", label: accountCategoryLabel("investment") },
  { category: "tax_advantaged", key: "cat_tax_advantaged", pctKey: "pct_tax_advantaged", label: accountCategoryLabel("tax_advantaged") },
  { category: "nonsense", key: "cat_nonsense", pctKey: "pct_nonsense", label: accountCategoryLabel("nonsense") },
  { category: "cash", key: "cat_cash", pctKey: "pct_cash", label: accountCategoryLabel("cash") },
  { category: "credit", key: "cat_credit", pctKey: "pct_credit", label: accountCategoryLabel("credit") },
] as const;

type ChartColors = ReturnType<typeof useChartColors>;
type NetWorthChartRow = Record<string, number | string> & { date: string; total: number };
type AccountFormBody = {
  name: string;
  institution: string | null;
  account_category: AccountCategory;
  type: AccountType;
  is_liquid: boolean;
  is_taxable: boolean;
  currency: string;
  sign_convention: SignConvention;
  url: string | null;
  notes: string | null;
  is_closed: boolean;
  sort_order: number;
};

const ACCOUNT_CATEGORIES: AccountCategory[] = ["bank", "investment", "tax_advantaged", "credit", "liability", "nonsense", "cash"];
const ACCOUNT_TYPES: AccountType[] = ["checking", "savings", "cd", "brokerage", "crypto", "wallet", "retirement", "college", "hsa", "credit_card", "cash", "other"];
const SIGN_CONVENTIONS: SignConvention[] = ["outflow_negative", "outflow_positive"];

export function NetWorth() {
  const qc = useQueryClient();
  const accounts = useQuery({ queryKey: ["accounts"], queryFn: () => api.get<Account[]>("/api/accounts") });
  const snapshots = useQuery({
    queryKey: ["snapshots"],
    queryFn: () => api.get<NetWorthSnapshot[]>("/api/snapshots"),
  });
  const [rangeStart, setRangeStart] = useState("");
  const [rangeEnd, setRangeEnd] = useState("");
  const [focusedValueSeries, setFocusedValueSeries] = useState<string | null>(null);
  const [focusedPercentSeries, setFocusedPercentSeries] = useState<string | null>(null);
  const chartColors = useChartColors();
  const series = useQuery({
    queryKey: ["nw-series", rangeStart, rangeEnd],
    queryFn: () =>
      api.get<NetWorthSeriesPoint[]>(
        "/api/snapshots/series" + qs({ start: rangeStart || undefined, end: rangeEnd || undefined }),
      ),
  });

  const [showLog, setShowLog] = useState(false);
  const [editingSnapId, setEditingSnapId] = useState<number | "new" | null>(null);
  const [creatingAccount, setCreatingAccount] = useState(false);

  const chartData =
    series.data?.map((p) => {
      const total = num(p.total);
      const categoryValues = Object.fromEntries(Object.entries(p.by_category).map(([k, v]) => [`cat_${k}`, num(v)]));
      const categoryPercentages = Object.fromEntries(
        Object.entries(p.by_category).map(([k, v]) => [`pct_${k}`, total ? (num(v) / total) * 100 : 0]),
      );
      return {
        date: p.snapshot_date,
        total,
        ...categoryValues,
        ...categoryPercentages,
      };
    }) ?? [];

  const accountById = useMemo(
    () => Object.fromEntries((accounts.data ?? []).map((a) => [a.id, a])),
    [accounts.data],
  );

  const latest = snapshots.data?.[0];
  const editingSnapshot = editingSnapId === "new"
    ? null
    : (snapshots.data?.find((snapshot) => snapshot.id === editingSnapId) ?? null);

  return (
    <div className="space-y-6">
      <NetWorthHeader
        showLog={showLog}
        onShowLog={setShowLog}
        onNewAccount={() => setCreatingAccount(true)}
        onNewSnapshot={() => setEditingSnapId("new")}
      />
      <NetWorthValuePanel
        data={chartData}
        focused={focusedValueSeries}
        showLog={showLog}
        start={rangeStart}
        end={rangeEnd}
        snapshotCount={snapshots.data?.length ?? 0}
        chartColors={chartColors}
        onFocus={setFocusedValueSeries}
        onStart={setRangeStart}
        onEnd={setRangeEnd}
        onAllTime={() => {
          setRangeStart("");
          setRangeEnd("");
        }}
      />
      <NetWorthAllocationPanel
        data={chartData}
        focused={focusedPercentSeries}
        snapshotCount={snapshots.data?.length ?? 0}
        hasRangeFilter={Boolean(rangeStart || rangeEnd)}
        chartColors={chartColors}
        onFocus={setFocusedPercentSeries}
      />
      <NetWorthSnapshotsTable
        snapshots={snapshots.data ?? []}
        series={series.data ?? []}
        onEdit={setEditingSnapId}
      />
      <SnapshotEditorDialog
        editingSnapId={editingSnapId}
        snapshot={editingSnapshot}
        latest={latest}
        accounts={accounts.data ?? []}
        accountById={accountById}
        onClose={() => setEditingSnapId(null)}
        onSaved={() => {
          qc.invalidateQueries({ queryKey: ["snapshots"] });
          qc.invalidateQueries({ queryKey: ["nw-series"] });
          qc.invalidateQueries({ queryKey: ["goal-progress"] });
          setEditingSnapId(null);
        }}
      />
      <AccountEditorDialog
        open={creatingAccount}
        onClose={() => setCreatingAccount(false)}
        onSaved={() => {
          qc.invalidateQueries({ queryKey: ["accounts"] });
          qc.invalidateQueries({ queryKey: ["snapshots"] });
          qc.invalidateQueries({ queryKey: ["nw-series"] });
          setCreatingAccount(false);
        }}
      />
    </div>
  );
}

function NetWorthHeader({
  showLog,
  onShowLog,
  onNewAccount,
  onNewSnapshot,
}: {
  showLog: boolean;
  onShowLog: (value: boolean) => void;
  onNewAccount: () => void;
  onNewSnapshot: () => void;
}) {
  return (
    <div className="flex items-baseline justify-between">
      <h1 className="text-2xl font-semibold tracking-tight">Net Worth</h1>
      <div className="flex items-center gap-2">
        <label className="flex items-center gap-1 text-sm text-ink-600">
          <input type="checkbox" checked={showLog} onChange={(e) => onShowLog(e.target.checked)} />
          log scale
        </label>
        <button className="btn" onClick={onNewAccount}>+ Account</button>
        <button className="btn-primary" onClick={onNewSnapshot}>+ New snapshot</button>
      </div>
    </div>
  );
}

function NetWorthValuePanel({
  data,
  focused,
  showLog,
  start,
  end,
  snapshotCount,
  chartColors,
  onFocus,
  onStart,
  onEnd,
  onAllTime,
}: {
  data: NetWorthChartRow[];
  focused: string | null;
  showLog: boolean;
  start: string;
  end: string;
  snapshotCount: number;
  chartColors: ChartColors;
  onFocus: (value: string | null) => void;
  onStart: (value: string) => void;
  onEnd: (value: string) => void;
  onAllTime: () => void;
}) {
  const hasRangeFilter = Boolean(start || end);
  return (
    <div className="card p-4">
      <div className="flex items-center justify-between gap-3 flex-wrap mb-2">
        <div>
          <div className="text-sm font-medium">Net worth by account category</div>
          {focused && <button className="btn-ghost text-xs mt-1" onClick={() => onFocus(null)}>show all categories</button>}
        </div>
        <NetWorthChartControls
          start={start}
          end={end}
          chartColors={chartColors}
          onStart={onStart}
          onEnd={onEnd}
          onAllTime={onAllTime}
        />
      </div>
      <div className="h-72">
        {data.length === 0 ? (
          <NetWorthEmptyState snapshotCount={snapshotCount} hasRangeFilter={hasRangeFilter} />
        ) : (
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
            <CartesianGrid stroke="#eceef2" vertical={false} />
            <XAxis dataKey="date" tickFormatter={(date) => shortDateLabel(date)} tick={{ fontSize: 12 }} stroke="#7a8392" />
            <YAxis
              scale={showLog ? "log" : "auto"}
              domain={showLog ? [1000, "auto"] : [0, "auto"]}
              allowDataOverflow
              tickFormatter={(value) => compactCurrency(value)}
              tick={{ fontSize: 12 }}
              stroke="#7a8392"
              width={70}
            />
            <Tooltip formatter={(value: number) => currency(value)} labelFormatter={(label) => dateLabel(label as string)} />
            <Legend
              content={(props) => (
                <ChartLegend payload={props.payload as any} focusedKey={focused} onToggle={(key) => onFocus(focused === key ? null : key)} />
              )}
            />
            <Line
              type="monotone"
              dataKey="total"
              stroke={colorAt(chartColors.colors, 0)}
              strokeWidth={2}
              dot={false}
              name="Total Net Worth"
              connectNulls
              hide={focused !== null && focused !== "total"}
            />
            {ACCOUNT_CATEGORY_SERIES.map((seriesDef, index) => (
              <Line
                key={seriesDef.key}
                type="monotone"
                dataKey={seriesDef.key}
                stroke={colorAt(chartColors.colors, index + 1)}
                strokeWidth={1.6}
                dot={false}
                name={seriesDef.label}
                connectNulls
                hide={focused !== null && focused !== seriesDef.key}
              />
            ))}
          </LineChart>
        </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}

function NetWorthChartControls({
  start,
  end,
  chartColors,
  onStart,
  onEnd,
  onAllTime,
}: {
  start: string;
  end: string;
  chartColors: ChartColors;
  onStart: (value: string) => void;
  onEnd: (value: string) => void;
  onAllTime: () => void;
}) {
  return (
    <div className="flex items-center gap-2 text-xs flex-wrap">
      <DateRangeControls start={start} end={end} onStart={onStart} onEnd={onEnd} />
      <button type="button" className="btn-ghost text-xs" onClick={onAllTime}>all time</button>
      <ChartColorControls
        paletteId={chartColors.paletteId}
        colors={chartColors.colors}
        onPaletteChange={chartColors.setPaletteId}
        onColorChange={chartColors.setColor}
      />
    </div>
  );
}

function NetWorthAllocationPanel({
  data,
  focused,
  snapshotCount,
  hasRangeFilter,
  chartColors,
  onFocus,
}: {
  data: NetWorthChartRow[];
  focused: string | null;
  snapshotCount: number;
  hasRangeFilter: boolean;
  chartColors: ChartColors;
  onFocus: (value: string | null) => void;
}) {
  return (
    <div className="card p-4">
      <div className="mb-2">
        <div className="text-sm font-medium">Allocation by account category</div>
        {focused && <button className="btn-ghost text-xs mt-1" onClick={() => onFocus(null)}>show all percentages</button>}
      </div>
      <div className="h-72">
        {data.length === 0 ? (
          <NetWorthEmptyState snapshotCount={snapshotCount} hasRangeFilter={hasRangeFilter} />
        ) : (
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data}>
            <CartesianGrid stroke="#eceef2" vertical={false} />
            <XAxis dataKey="date" tickFormatter={(date) => shortDateLabel(date)} tick={{ fontSize: 12 }} stroke="#7a8392" />
            <YAxis domain={["auto", "auto"]} tickFormatter={(value) => `${Number(value).toFixed(0)}%`} tick={{ fontSize: 12 }} stroke="#7a8392" width={70} />
            <Tooltip formatter={(value: number) => `${value.toFixed(1)}%`} labelFormatter={(label) => dateLabel(label as string)} />
            <Legend
              content={(props) => (
                <ChartLegend payload={props.payload as any} focusedKey={focused} onToggle={(key) => onFocus(focused === key ? null : key)} />
              )}
            />
            {ACCOUNT_CATEGORY_SERIES.map((seriesDef, index) => (
              <Line
                key={seriesDef.pctKey}
                dataKey={seriesDef.pctKey}
                stroke={colorAt(chartColors.colors, index + 1)}
                name={seriesDef.label}
                dot={false}
                connectNulls
                hide={focused !== null && focused !== seriesDef.pctKey}
              />
            ))}
          </LineChart>
        </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}

function NetWorthEmptyState({
  snapshotCount,
  hasRangeFilter,
}: {
  snapshotCount: number;
  hasRangeFilter: boolean;
}) {
  const message =
    snapshotCount > 0 && hasRangeFilter
      ? "No snapshots found within time range."
      : "No snapshots found. You can add one by clicking the New snapshot button in the top right.";
  return (
    <div className="h-full flex items-center justify-center rounded-md border border-dashed border-ink-200 bg-ink-50 px-6 text-center text-sm text-ink-500">
      {message}
    </div>
  );
}

function NetWorthSnapshotsTable({
  snapshots,
  series,
  onEdit,
}: {
  snapshots: NetWorthSnapshot[];
  series: NetWorthSeriesPoint[];
  onEdit: (snapshotId: number) => void;
}) {
  return (
    <div className="card overflow-x-auto">
      <table className="text-sm w-full tabular">
        <thead className="bg-ink-50">
          <tr>
            <th className="px-3 py-2 text-left">Snapshot</th>
            <th className="px-3 py-2 text-right">Total</th>
            <th className="px-3 py-2 text-right">Bank</th>
            <th className="px-3 py-2 text-right">Investment</th>
            <th className="px-3 py-2 text-right">Tax Advantaged</th>
            <th className="px-3 py-2 text-right">Wallets / Crypto</th>
            <th className="px-3 py-2 text-right">Cash</th>
            <th className="px-3 py-2"></th>
          </tr>
        </thead>
        <tbody className="divide-y divide-ink-100">
          {snapshots.map((snapshot) => (
            <NetWorthSnapshotRow
              key={snapshot.id}
              snapshot={snapshot}
              point={series.find((point) => point.snapshot_date === snapshot.snapshot_date)}
              onEdit={onEdit}
            />
          ))}
          {snapshots.length === 0 && (
            <tr>
              <td colSpan={8} className="px-3 py-8 text-center text-ink-500">
                No snapshots found. You can add one by clicking the New snapshot button in the top right.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function NetWorthSnapshotRow({
  snapshot,
  point,
  onEdit,
}: {
  snapshot: NetWorthSnapshot;
  point?: NetWorthSeriesPoint;
  onEdit: (snapshotId: number) => void;
}) {
  return (
    <tr className="table-row-hover">
      <td className="px-3 py-2">{dateLabel(snapshot.snapshot_date)}</td>
      <td className="px-3 py-2 text-right font-medium">{currency(point?.total)}</td>
      <td className="px-3 py-2 text-right">{currency(point?.by_category.bank)}</td>
      <td className="px-3 py-2 text-right">{currency(point?.by_category.investment)}</td>
      <td className="px-3 py-2 text-right">{currency(point?.by_category.tax_advantaged)}</td>
      <td className="px-3 py-2 text-right">{currency(point?.by_category.nonsense)}</td>
      <td className="px-3 py-2 text-right">{currency(point?.by_category.cash)}</td>
      <td className="px-3 py-2 text-right">
        <button className="btn-ghost text-xs" onClick={() => onEdit(snapshot.id)}>Edit</button>
      </td>
    </tr>
  );
}

function SnapshotEditorDialog({
  editingSnapId,
  snapshot,
  latest,
  accounts,
  accountById,
  onClose,
  onSaved,
}: {
  editingSnapId: number | "new" | null;
  snapshot: NetWorthSnapshot | null;
  latest?: NetWorthSnapshot;
  accounts: Account[];
  accountById: Record<number, Account>;
  onClose: () => void;
  onSaved: () => void;
}) {
  if (editingSnapId === null) return null;
  return (
    <SnapshotEditor
      key={String(editingSnapId)}
      snapshot={snapshot}
      prefillFrom={editingSnapId === "new" ? latest : undefined}
      accounts={accounts}
      accountById={accountById}
      onClose={onClose}
      onSaved={onSaved}
    />
  );
}

function SnapshotEditor({
  snapshot,
  prefillFrom,
  accounts,
  accountById,
  onClose,
  onSaved,
}: {
  snapshot: NetWorthSnapshot | null;
  prefillFrom?: NetWorthSnapshot;
  accounts: Account[];
  accountById: Record<number, Account>;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [date, setDate] = useState(snapshot?.snapshot_date ?? new Date().toISOString().slice(0, 10));
  const [notes, setNotes] = useState(snapshot?.notes ?? "");
  const initialBalances = useMemo(() => {
    const source = snapshot?.balances ?? prefillFrom?.balances ?? [];
    const m: Record<number, string> = {};
    for (const b of source) m[b.account_id] = b.balance ?? "";
    return m;
  }, [snapshot, prefillFrom]);
  const [balances, setBalances] = useState<Record<number, string>>(initialBalances);

  const save = useMutation({
    mutationFn: async () => {
      const body = {
        snapshot_date: date,
        notes: notes || null,
        balances: Object.entries(balances)
          .filter(([, v]) => v !== "" && v !== undefined)
          .map(([accId, v]) => ({
            account_id: parseInt(accId, 10),
            balance: v,
          })),
      };
      if (snapshot) {
        return api.patch(`/api/snapshots/${snapshot.id}`, body);
      }
      return api.post("/api/snapshots", body);
    },
    onSuccess: onSaved,
  });

  const remove = useMutation({
    mutationFn: () => api.del(`/api/snapshots/${snapshot!.id}`),
    onSuccess: onSaved,
  });

  const grouped = useMemo(() => {
    const m: Record<string, Account[]> = {};
    for (const a of accounts) {
      const k = a.account_category;
      (m[k] ??= []).push(a);
    }
    return m;
  }, [accounts]);

  const total = Object.values(balances).reduce((a, v) => a + (parseFloat(v) || 0), 0);
  const hasLoginLinks = accounts.some((a) => a.url && !a.is_closed);

  return (
    <SidePanel
      title={snapshot ? "Edit snapshot" : "New snapshot"}
      onClose={onClose}
      onSubmit={() => save.mutate()}
      maxWidth="max-w-2xl"
    >
        <div className="grid grid-cols-2 gap-3 mb-4">
          <label>
            <div className="label">Date</div>
            <input
              type="date"
              className="input"
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </label>
          <label>
            <div className="label">Notes</div>
            <input
              type="text"
              className="input"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
            />
          </label>
        </div>
        <div className="flex items-center justify-between mb-2 text-xs text-ink-500">
          <span>
            {hasLoginLinks
              ? "Click the ↗ icon next to any account to open its login in a new tab."
              : "No account login links yet. Add account URLs with the Account button on the Net Worth page."}
          </span>
          {hasLoginLinks && (
            <button
              type="button"
              className="btn-ghost text-xs"
              onClick={() => {
                for (const a of accounts) {
                  if (a.url && !a.is_closed && (balances[a.id] ?? "") === "") {
                    window.open(a.url, "_blank", "noopener");
                  }
                }
              }}
              title="Open all unfilled accounts' login pages"
            >
              ↗ open all unfilled
            </button>
          )}
        </div>
        <div className="space-y-3">
          {Object.entries(grouped).map(([cat, accs]) => (
            <div key={cat} className="card p-3">
              <div className="label mb-2">{accountCategoryLabel(cat)}</div>
              <div className="space-y-1">
                {accs.map((a) => (
                  <div key={a.id} className="flex items-center gap-2">
                    <div className="flex-1 text-sm flex items-center gap-1.5">
                      <span className={a.is_closed ? "text-ink-400 italic" : ""}>{a.name}</span>
                      {a.institution && (
                        <span className="text-xs text-ink-400">· {a.institution}</span>
                      )}
                    </div>
                    {a.url ? (
                      <a
                        href={a.url}
                        target="_blank"
                        rel="noopener"
                        className="text-xs text-brand-600 hover:underline px-1"
                        title={`Open ${a.name} login`}
                      >
                        ↗
                      </a>
                    ) : (
                      <span className="w-4" />
                    )}
                    <input
                      type="number"
                      step="0.01"
                      className="input max-w-[10rem] tabular text-right"
                      placeholder="—"
                      value={balances[a.id] ?? ""}
                      onChange={(e) =>
                        setBalances({ ...balances, [a.id]: e.target.value })
                      }
                    />
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
        <div className="mt-4 p-3 bg-ink-50 rounded-md flex items-baseline justify-between">
          <div className="label">Sum of entered balances</div>
          <div className="text-lg font-semibold tabular">{currency(total)}</div>
        </div>
        <div className="sticky bottom-0 z-10 -mx-6 mt-4 flex items-center gap-2 border-t border-ink-100 bg-white px-6 py-3">
          <button type="submit" className="btn-primary" disabled={save.isPending}>
            Save snapshot
          </button>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          {snapshot && (
            <button
              type="button"
              className="btn-danger ml-auto"
              onClick={() => {
                if (confirm("Delete this snapshot?")) remove.mutate();
              }}
            >
              Delete
            </button>
          )}
        </div>
        {save.isError && (
          <div className="mt-2 text-sm text-bad-600">{String((save.error as Error).message)}</div>
        )}
    </SidePanel>
  );
}

function AccountEditorDialog({
  open,
  onClose,
  onSaved,
}: {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  if (!open) return null;
  return <AccountEditor onClose={onClose} onSaved={onSaved} />;
}

function AccountEditor({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState<AccountFormBody>({
    name: "",
    institution: null,
    account_category: "bank",
    type: "checking",
    is_liquid: true,
    is_taxable: true,
    currency: "USD",
    sign_convention: "outflow_negative",
    url: null,
    notes: null,
    is_closed: false,
    sort_order: 0,
  });

  const save = useMutation({
    mutationFn: () =>
      api.post<Account>("/api/accounts", {
        ...form,
        name: form.name.trim(),
        institution: form.institution?.trim() || null,
        currency: form.currency.trim() || "USD",
        url: form.url?.trim() || null,
        notes: form.notes?.trim() || null,
      }),
    onSuccess: onSaved,
  });

  const update = <K extends keyof AccountFormBody>(key: K, value: AccountFormBody[K]) =>
    setForm((prev) => ({ ...prev, [key]: value }));

  return (
    <SidePanel title="New account" onClose={onClose} onSubmit={() => save.mutate()} maxWidth="max-w-xl">
      <div className="grid grid-cols-2 gap-3">
        <Field label="Name">
          <input className="input" value={form.name} onChange={(e) => update("name", e.target.value)} required />
        </Field>
        <Field label="Institution">
          <input className="input" value={form.institution ?? ""} onChange={(e) => update("institution", e.target.value)} />
        </Field>
        <Field label="Category">
          <select className="input" value={form.account_category} onChange={(e) => update("account_category", e.target.value as AccountCategory)}>
            {ACCOUNT_CATEGORIES.map((category) => (
              <option key={category} value={category}>{accountCategoryLabel(category)}</option>
            ))}
          </select>
        </Field>
        <Field label="Type">
          <select className="input" value={form.type} onChange={(e) => update("type", e.target.value as AccountType)}>
            {ACCOUNT_TYPES.map((type) => (
              <option key={type} value={type}>{type.replace(/_/g, " ")}</option>
            ))}
          </select>
        </Field>
        <Field label="Sign convention">
          <select className="input" value={form.sign_convention} onChange={(e) => update("sign_convention", e.target.value as SignConvention)}>
            {SIGN_CONVENTIONS.map((convention) => (
              <option key={convention} value={convention}>{convention.replace(/_/g, " ")}</option>
            ))}
          </select>
        </Field>
        <Field label="Currency">
          <input className="input" value={form.currency} onChange={(e) => update("currency", e.target.value)} />
        </Field>
        <Field label="Login URL">
          <input className="input" value={form.url ?? ""} onChange={(e) => update("url", e.target.value)} />
        </Field>
        <Field label="Sort order">
          <input type="number" className="input" value={form.sort_order} onChange={(e) => update("sort_order", Number(e.target.value) || 0)} />
        </Field>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-3 text-sm text-ink-700">
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={form.is_liquid} onChange={(e) => update("is_liquid", e.target.checked)} />
          Liquid
        </label>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={form.is_taxable} onChange={(e) => update("is_taxable", e.target.checked)} />
          Taxable
        </label>
      </div>
      <Field label="Notes">
        <textarea className="input min-h-24" value={form.notes ?? ""} onChange={(e) => update("notes", e.target.value)} />
      </Field>
      <div className="sticky bottom-0 z-10 -mx-6 mt-4 flex items-center gap-2 border-t border-ink-100 bg-white px-6 py-3">
        <button type="submit" className="btn-primary" disabled={save.isPending || !form.name.trim()}>
          Save account
        </button>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
      </div>
      {save.isError && <div className="mt-2 text-sm text-bad-600">{String((save.error as Error).message)}</div>}
    </SidePanel>
  );
}
