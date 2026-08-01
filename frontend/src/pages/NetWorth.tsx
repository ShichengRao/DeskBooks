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
import type { Account, AccountCategory, AccountType, NetWorthSeriesPoint, NetWorthSnapshot, SignConvention, SnapshotPrefillBalance } from "../api/types";
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
  { category: "liability", key: "cat_liability", pctKey: "pct_liability", label: accountCategoryLabel("liability") },
] as const;

type ChartColors = ReturnType<typeof useChartColors>;
type NetWorthChartRow = Record<string, number | string> & { date: string; total: number };
type AccountFormBody = {
  name: string;
  institution: string | null;
  account_category: AccountCategory;
  type: AccountType;
  currency: string;
  sign_convention: SignConvention;
  url: string | null;
  notes: string | null;
  is_closed: boolean;
  sort_order: number;
};
type WorkbookImportResult = {
  imported: number;
  skipped_existing: number;
  missing_accounts: string[];
};

function accountUrlKey(rawUrl: string) {
  const trimmed = rawUrl.trim();
  try {
    const url = new URL(trimmed);
    url.hash = "";
    url.protocol = url.protocol.toLowerCase();
    url.hostname = url.hostname.toLowerCase();
    url.pathname = url.pathname.replace(/\/+$/, "") || "/";
    return url.toString();
  } catch {
    return trimmed.replace(/\/+$/, "").toLowerCase();
  }
}

function uniqueAccountLinks(accounts: Account[]) {
  const seen = new Set<string>();
  const links: string[] = [];
  for (const account of accounts) {
    const url = account.url?.trim();
    if (!url) continue;

    const key = accountUrlKey(url);
    if (seen.has(key)) continue;
    seen.add(key);
    links.push(url);
  }
  return links;
}

function openAccountLinks(links: string[]) {
  for (const link of links) {
    window.open(link, "_blank", "noopener");
  }
}

const ACCOUNT_CATEGORIES: AccountCategory[] = ["bank", "investment", "tax_advantaged", "credit", "liability", "nonsense", "cash"];
const ACCOUNT_TYPES: AccountType[] = ["checking", "savings", "cd", "brokerage", "crypto", "wallet", "retirement", "college", "hsa", "credit_card", "cash", "other"];
const SIGN_CONVENTIONS: SignConvention[] = ["outflow_negative", "outflow_positive"];
const DORMANT_ACCOUNT_SNAPSHOT_COUNT = 5;

function isZeroBalance(value: string | null | undefined) {
  if (value === null || value === undefined || value === "") return true;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed === 0;
}

function recentSnapshotsForDate(snapshots: NetWorthSnapshot[], snapshotDate: string) {
  return [...snapshots]
    .filter((snapshot) => snapshot.snapshot_date <= snapshotDate)
    .sort((a, b) => b.snapshot_date.localeCompare(a.snapshot_date))
    .slice(0, DORMANT_ACCOUNT_SNAPSHOT_COUNT);
}

function isDormantAccount(
  account: Account,
  recentSnapshots: NetWorthSnapshot[],
  forceVisibleAccountIds: Set<number>,
) {
  if (forceVisibleAccountIds.has(account.id)) return false;
  if (recentSnapshots.length < DORMANT_ACCOUNT_SNAPSHOT_COUNT) return false;

  const oldestRecentSnapshot = recentSnapshots[recentSnapshots.length - 1];
  if (account.opened_at && oldestRecentSnapshot && account.opened_at > oldestRecentSnapshot.snapshot_date) {
    return false;
  }

  return recentSnapshots.every((snapshot) => {
    const balance = snapshot.balances.find((item) => item.account_id === account.id);
    if (!balance) return true;
    return isZeroBalance(balance.balance);
  });
}

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
  const [forceVisibleAccountIds, setForceVisibleAccountIds] = useState<Set<number>>(() => new Set());
  const [importingWorkbook, setImportingWorkbook] = useState(false);

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

  const editingSnapshot = editingSnapId === "new"
    ? null
    : (snapshots.data?.find((snapshot) => snapshot.id === editingSnapId) ?? null);

  return (
    <div className="space-y-6">
      <NetWorthHeader
        showLog={showLog}
        onShowLog={setShowLog}
        onNewAccount={() => setCreatingAccount(true)}
        onImportWorkbook={() => setImportingWorkbook(true)}
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
        snapshots={snapshots.data ?? []}
        accounts={accounts.data ?? []}
        accountById={accountById}
        forceVisibleAccountIds={forceVisibleAccountIds}
        onNewAccount={() => setCreatingAccount(true)}
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
        onSaved={(created) => {
          setForceVisibleAccountIds((current) => {
            const next = new Set(current);
            for (const account of created) next.add(account.id);
            return next;
          });
          qc.invalidateQueries({ queryKey: ["accounts"] });
          qc.invalidateQueries({ queryKey: ["snapshots"] });
          qc.invalidateQueries({ queryKey: ["nw-series"] });
          setCreatingAccount(false);
        }}
      />
      <WorkbookImportDialog
        open={importingWorkbook}
        onClose={() => setImportingWorkbook(false)}
        onImported={() => {
          qc.invalidateQueries({ queryKey: ["snapshots"] });
          qc.invalidateQueries({ queryKey: ["nw-series"] });
          qc.invalidateQueries({ queryKey: ["goal-progress"] });
        }}
      />
    </div>
  );
}

function NetWorthHeader({
  showLog,
  onShowLog,
  onNewAccount,
  onImportWorkbook,
  onNewSnapshot,
}: {
  showLog: boolean;
  onShowLog: (value: boolean) => void;
  onNewAccount: () => void;
  onImportWorkbook: () => void;
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
        <button className="btn" onClick={onImportWorkbook}>Import workbook</button>
        <button className="btn-primary" onClick={onNewSnapshot}>+ New snapshot</button>
      </div>
    </div>
  );
}

function WorkbookImportDialog({
  open,
  onClose,
  onImported,
}: {
  open: boolean;
  onClose: () => void;
  onImported: () => void;
}) {
  if (!open) return null;
  return <WorkbookImportPanel onClose={onClose} onImported={onImported} />;
}

function WorkbookImportPanel({ onClose, onImported }: { onClose: () => void; onImported: () => void }) {
  const [path, setPath] = useState("");
  const [accountMap, setAccountMap] = useState("");
  const [result, setResult] = useState<WorkbookImportResult | null>(null);
  const save = useMutation({
    mutationFn: () => {
      let parsedMap: Record<string, string> = {};
      const trimmedMap = accountMap.trim();
      if (trimmedMap) {
        const parsed = JSON.parse(trimmedMap);
        if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
          throw new Error("Account map must be a JSON object.");
        }
        parsedMap = Object.fromEntries(
          Object.entries(parsed).map(([key, value]) => {
            if (typeof value !== "string") throw new Error("Account map values must be account names.");
            return [key, value];
          }),
        );
      }
      return api.post<WorkbookImportResult>("/api/snapshots/import-workbook", {
        path: path.trim(),
        account_map: parsedMap,
      });
    },
    onSuccess: (data) => {
      setResult(data);
      onImported();
    },
  });

  return (
    <SidePanel title="Import net worth workbook" onClose={onClose} onSubmit={() => save.mutate()} maxWidth="max-w-xl">
      <div className="space-y-3">
        <Field label="Local workbook path">
          <input
            className="input"
            value={path}
            onChange={(e) => setPath(e.target.value)}
            placeholder="/path/to/net-worth.xlsx"
            required
          />
        </Field>
        <Field label="Account row map (optional JSON)">
          <textarea
            className="input min-h-[8rem] font-mono text-xs"
            value={accountMap}
            onChange={(e) => setAccountMap(e.target.value)}
            placeholder={'{\n  "Sheet name!12": "Account name"\n}'}
          />
        </Field>
        {result && (
          <div className="rounded-md border border-ink-200 bg-ink-50 p-3 text-sm text-ink-700">
            Imported {result.imported} snapshot(s); skipped {result.skipped_existing} existing date(s).
            {result.missing_accounts.length > 0 && (
              <div className="mt-1 text-bad-600">Missing accounts: {result.missing_accounts.join(", ")}</div>
            )}
          </div>
        )}
      </div>
      <div className="sticky bottom-0 z-10 -mx-6 mt-4 flex items-center gap-2 border-t border-ink-100 bg-white px-6 py-3">
        <button type="submit" className="btn-primary" disabled={save.isPending || !path.trim()}>
          Import workbook
        </button>
        <button type="button" className="btn" onClick={onClose}>Close</button>
      </div>
      {save.isError && <div className="mt-2 text-sm text-bad-600">{String((save.error as Error).message)}</div>}
    </SidePanel>
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
  // Liabilities and carried card balances sit below zero; extend the floor
  // to fit them (log scale can't show negatives, so it keeps its floor).
  const valueFloor = Math.min(
    0,
    ...data.flatMap((row) => ACCOUNT_CATEGORY_SERIES.map((s) => Number(row[s.key] ?? 0))),
  );
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
              domain={showLog ? [1000, "auto"] : [valueFloor, "auto"]}
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
  // Allocations live on a 0–100% scale; extend the floor only as far as
  // the data actually dips (credit cards at -0.1% used to drag the
  // auto-domain down to -30%).
  const pctFloor = Math.min(
    0,
    Math.floor(
      Math.min(
        0,
        ...data.flatMap((row) => ACCOUNT_CATEGORY_SERIES.map((s) => Number(row[s.pctKey] ?? 0))),
      ),
    ),
  );
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
            <YAxis
              domain={[pctFloor, 100]}
              ticks={[0, 25, 50, 75, 100]}
              tickFormatter={(value) => `${Number(value).toFixed(0)}%`}
              tick={{ fontSize: 12 }}
              stroke="#7a8392"
              width={70}
            />
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
  snapshots,
  accounts,
  accountById,
  forceVisibleAccountIds,
  onNewAccount,
  onClose,
  onSaved,
}: {
  editingSnapId: number | "new" | null;
  snapshot: NetWorthSnapshot | null;
  snapshots: NetWorthSnapshot[];
  accounts: Account[];
  accountById: Record<number, Account>;
  forceVisibleAccountIds: Set<number>;
  onNewAccount: () => void;
  onClose: () => void;
  onSaved: () => void;
}) {
  if (editingSnapId === null) return null;
  return (
    <SnapshotEditor
      key={String(editingSnapId)}
      snapshot={snapshot}
      snapshots={snapshots}
      accounts={accounts}
      accountById={accountById}
      forceVisibleAccountIds={forceVisibleAccountIds}
      onNewAccount={onNewAccount}
      onClose={onClose}
      onSaved={onSaved}
    />
  );
}

function SnapshotEditor({
  snapshot,
  snapshots,
  accounts,
  accountById,
  forceVisibleAccountIds,
  onNewAccount,
  onClose,
  onSaved,
}: {
  snapshot: NetWorthSnapshot | null;
  snapshots: NetWorthSnapshot[];
  accounts: Account[];
  accountById: Record<number, Account>;
  forceVisibleAccountIds: Set<number>;
  onNewAccount: () => void;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [date, setDate] = useState(snapshot?.snapshot_date ?? new Date().toISOString().slice(0, 10));
  const [notes, setNotes] = useState(snapshot?.notes ?? "");
  const [showDormantAccounts, setShowDormantAccounts] = useState(false);
  // A new snapshot starts blank on purpose: values arrive either from
  // "fill from connections" or by hand, so it's always clear where a
  // number came from. Editing an existing snapshot loads its own values.
  const initialBalances = useMemo(() => {
    const m: Record<number, string> = {};
    for (const b of snapshot?.balances ?? []) m[b.account_id] = b.balance ?? "";
    return m;
  }, [snapshot]);
  const [balances, setBalances] = useState<Record<number, string>>(initialBalances);

  // Latest connector-staged balances (refreshed by `make fetch-preview`).
  // Filling is explicit and partial: only connected accounts change, and
  // nothing is saved until the user submits.
  const prefill = useQuery({
    queryKey: ["snapshot-prefill"],
    queryFn: () => api.get<SnapshotPrefillBalance[]>("/api/snapshots/prefill"),
  });
  const prefillRows = prefill.data ?? [];
  const prefillAsOf = prefillRows.map((r) => r.as_of).sort().at(-1);
  const fillFromConnections = () =>
    setBalances((prev) => {
      const next = { ...prev };
      for (const row of prefillRows) next[row.account_id] = row.balance;
      return next;
    });

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

  const visibleAccounts = useMemo(() => {
    const recentSnapshots = recentSnapshotsForDate(snapshots, date);
    return showDormantAccounts
      ? accounts
      : accounts.filter((account) => !isDormantAccount(account, recentSnapshots, forceVisibleAccountIds));
  }, [accounts, date, forceVisibleAccountIds, showDormantAccounts, snapshots]);

  const grouped = useMemo(() => {
    const m: Record<string, Account[]> = {};
    for (const a of visibleAccounts) {
      const k = a.account_category;
      (m[k] ??= []).push(a);
    }
    return m;
  }, [visibleAccounts]);

  const dormantAccountCount = useMemo(() => {
    const recentSnapshots = recentSnapshotsForDate(snapshots, date);
    return accounts.filter((account) => isDormantAccount(account, recentSnapshots, forceVisibleAccountIds)).length;
  }, [accounts, date, forceVisibleAccountIds, snapshots]);

  const total = Object.values(balances).reduce((a, v) => a + (parseFloat(v) || 0), 0);
  const accountLinks = useMemo(() => uniqueAccountLinks(visibleAccounts), [visibleAccounts]);

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
            {accountLinks.length > 0
              ? `${accountLinks.length} unique visible account links`
              : "No account login links yet. Add account URLs with the Account button on the Net Worth page."}
          </span>
          {accountLinks.length > 0 && (
            <button
              type="button"
              className="btn-ghost text-xs"
              onClick={() => openAccountLinks(accountLinks)}
              title="Open every unique visible account login"
            >
              ↗ open all links
            </button>
          )}
        </div>
        {prefillRows.length > 0 && (
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2 text-xs text-ink-500">
            <span>
              {prefillRows.length} balance{prefillRows.length === 1 ? "" : "s"} available from
              connections{prefillAsOf ? ` (as of ${prefillAsOf})` : ""}. Run a fetch to refresh.
            </span>
            <button
              type="button"
              className="btn-ghost text-xs"
              onClick={fillFromConnections}
              title="Fill connected accounts from the latest fetched balances; other accounts are untouched and nothing is saved yet"
            >
              ↓ fill from connections
            </button>
          </div>
        )}
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2 text-xs text-ink-500">
          {dormantAccountCount > 0 ? (
            <span>
              {dormantAccountCount} zero-balance account{dormantAccountCount === 1 ? "" : "s"} hidden after {DORMANT_ACCOUNT_SNAPSHOT_COUNT} snapshots
            </span>
          ) : (
            <span />
          )}
          <div className="flex items-center gap-2">
            {dormantAccountCount > 0 && (
              <button
                type="button"
                className="btn-ghost text-xs"
                onClick={() => setShowDormantAccounts((value) => !value)}
              >
                {showDormantAccounts ? "Hide dormant" : "Show hidden"}
              </button>
            )}
            <button type="button" className="btn text-xs" onClick={onNewAccount}>
              + Account
            </button>
          </div>
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
                      <span
                        className="text-[10px] text-ink-300 tabular"
                        title="Account id — connector configs map provider accounts to this id"
                      >
                        #{a.id}
                      </span>
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
  onSaved: (accounts: Account[]) => void;
}) {
  const [bulk, setBulk] = useState(false);
  if (!open) return null;
  return bulk ? (
    <BulkAccountEditor onClose={onClose} onSaved={onSaved} onSingle={() => setBulk(false)} />
  ) : (
    <AccountEditor onClose={onClose} onSaved={onSaved} onBulk={() => setBulk(true)} />
  );
}

function AccountEditor({
  onClose,
  onSaved,
  onBulk,
}: {
  onClose: () => void;
  onSaved: (accounts: Account[]) => void;
  onBulk: () => void;
}) {
  const [form, setForm] = useState<AccountFormBody>({
    name: "",
    institution: null,
    account_category: "bank",
    type: "checking",
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
    onSuccess: (account) => onSaved([account]),
  });

  const update = <K extends keyof AccountFormBody>(key: K, value: AccountFormBody[K]) =>
    setForm((prev) => ({ ...prev, [key]: value }));

  return (
    <SidePanel title="New account" onClose={onClose} onSubmit={() => save.mutate()} maxWidth="max-w-xl">
      <div className="mb-3 text-xs text-ink-500">
        Setting up several accounts?{" "}
        <button type="button" className="text-brand-600 hover:underline" onClick={onBulk}>
          Add multiple at once
        </button>
      </div>
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

type BulkAccountRow = {
  name: string;
  institution: string;
  account_category: AccountCategory;
  type: AccountType;
};

// When the category changes, jump the type to that category's usual one —
// still editable afterwards.
const DEFAULT_TYPE_FOR_CATEGORY: Record<AccountCategory, AccountType> = {
  bank: "checking",
  investment: "brokerage",
  tax_advantaged: "retirement",
  credit: "credit_card",
  liability: "other",
  nonsense: "other",
  cash: "cash",
};

function emptyBulkRow(): BulkAccountRow {
  return { name: "", institution: "", account_category: "bank", type: "checking" };
}

function BulkAccountEditor({
  onClose,
  onSaved,
  onSingle,
}: {
  onClose: () => void;
  onSaved: (accounts: Account[]) => void;
  onSingle: () => void;
}) {
  const [rows, setRows] = useState<BulkAccountRow[]>(() => [emptyBulkRow(), emptyBulkRow(), emptyBulkRow()]);
  const filled = rows.filter((row) => row.name.trim());

  const save = useMutation({
    mutationFn: () =>
      api.post<Account[]>("/api/accounts/bulk", {
        accounts: filled.map((row) => ({
          name: row.name.trim(),
          institution: row.institution.trim() || null,
          account_category: row.account_category,
          type: row.type,
        })),
      }),
    onSuccess: onSaved,
  });

  const update = <K extends keyof BulkAccountRow>(index: number, key: K, value: BulkAccountRow[K]) =>
    setRows((prev) =>
      prev.map((row, i) => {
        if (i !== index) return row;
        const next = { ...row, [key]: value };
        if (key === "account_category") {
          next.type = DEFAULT_TYPE_FOR_CATEGORY[value as AccountCategory];
        }
        return next;
      }),
    );

  return (
    <SidePanel title="New accounts" onClose={onClose} onSubmit={() => save.mutate()} maxWidth="max-w-3xl">
      <div className="mb-3 text-xs text-ink-500">
        One row per account — blank rows are ignored. Currency and sign convention use the defaults;
        edit an account later for URLs and notes.{" "}
        <button type="button" className="text-brand-600 hover:underline" onClick={onSingle}>
          Back to single account
        </button>
      </div>
      <div className="space-y-2">
        <div className="grid grid-cols-[1fr_1fr_9rem_9rem_2rem] gap-2 label">
          <span>Name</span>
          <span>Institution</span>
          <span>Category</span>
          <span>Type</span>
          <span />
        </div>
        {rows.map((row, index) => (
          <div key={index} className="grid grid-cols-[1fr_1fr_9rem_9rem_2rem] gap-2 items-center">
            <input
              className="input"
              value={row.name}
              placeholder={`Account ${index + 1}`}
              onChange={(e) => update(index, "name", e.target.value)}
            />
            <input
              className="input"
              value={row.institution}
              onChange={(e) => update(index, "institution", e.target.value)}
            />
            <select
              className="input"
              value={row.account_category}
              onChange={(e) => update(index, "account_category", e.target.value as AccountCategory)}
            >
              {ACCOUNT_CATEGORIES.map((category) => (
                <option key={category} value={category}>{accountCategoryLabel(category)}</option>
              ))}
            </select>
            <select
              className="input"
              value={row.type}
              onChange={(e) => update(index, "type", e.target.value as AccountType)}
            >
              {ACCOUNT_TYPES.map((type) => (
                <option key={type} value={type}>{type.replace(/_/g, " ")}</option>
              ))}
            </select>
            <button
              type="button"
              className="btn-ghost text-ink-400"
              title="Remove row"
              onClick={() => setRows((prev) => prev.filter((_, i) => i !== index))}
              disabled={rows.length === 1}
            >
              ×
            </button>
          </div>
        ))}
      </div>
      <div className="mt-2">
        <button type="button" className="btn-ghost text-xs" onClick={() => setRows((prev) => [...prev, emptyBulkRow()])}>
          + Add row
        </button>
      </div>
      <div className="sticky bottom-0 z-10 -mx-6 mt-4 flex items-center gap-2 border-t border-ink-100 bg-white px-6 py-3">
        <button type="submit" className="btn-primary" disabled={save.isPending || filled.length === 0}>
          Create {filled.length || ""} account{filled.length === 1 ? "" : "s"}
        </button>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
      </div>
      {save.isError && <div className="mt-2 text-sm text-bad-600">{String((save.error as Error).message)}</div>}
    </SidePanel>
  );
}
