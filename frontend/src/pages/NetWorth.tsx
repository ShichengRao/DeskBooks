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
import { SidePanel } from "../components/SidePanel";
import type { Account, NetWorthSeriesPoint, NetWorthSnapshot } from "../api/types";
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

  return (
    <div className="space-y-6">
      <div className="flex items-baseline justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Net Worth</h1>
        <div className="flex items-center gap-2">
          <label className="flex items-center gap-1 text-sm text-ink-600">
            <input
              type="checkbox"
              checked={showLog}
              onChange={(e) => setShowLog(e.target.checked)}
            />
            log scale
          </label>
          <button className="btn-primary" onClick={() => setEditingSnapId("new")}>
            + New snapshot
          </button>
        </div>
      </div>

      <div className="card p-4">
        <div className="flex items-center justify-between gap-3 flex-wrap mb-2">
          <div>
            <div className="text-sm font-medium">Net worth by account category</div>
            {focusedValueSeries && (
              <button className="btn-ghost text-xs mt-1" onClick={() => setFocusedValueSeries(null)}>
                show all categories
              </button>
            )}
          </div>
          <div className="flex items-center gap-2 text-xs flex-wrap">
            <DateRangeControls
              start={rangeStart}
              end={rangeEnd}
              onStart={setRangeStart}
              onEnd={setRangeEnd}
            />
            <button
              type="button"
              className="btn-ghost text-xs"
              onClick={() => {
                setRangeStart("");
                setRangeEnd("");
              }}
            >
              all time
            </button>
            <ChartColorControls
              paletteId={chartColors.paletteId}
              colors={chartColors.colors}
              onPaletteChange={chartColors.setPaletteId}
              onColorChange={chartColors.setColor}
            />
          </div>
        </div>
        <div className="h-72">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={chartData} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
              <CartesianGrid stroke="#eceef2" vertical={false} />
              <XAxis
                dataKey="date"
                tickFormatter={(d) => shortDateLabel(d)}
                tick={{ fontSize: 12 }}
                stroke="#7a8392"
              />
              <YAxis
                // Log scale fails on 0/negative values. Pick a safe minimum
                // bound when log is on, and skip non-positive points.
                scale={showLog ? "log" : "auto"}
                domain={showLog ? [1000, "auto"] : [0, "auto"]}
                allowDataOverflow
                tickFormatter={(v) => compactCurrency(v)}
                tick={{ fontSize: 12 }}
                stroke="#7a8392"
                width={70}
              />
              <Tooltip
                formatter={(v: number) => currency(v)}
                labelFormatter={(l) => dateLabel(l as string)}
              />
              <Legend
                content={(props) => (
                  <ChartLegend
                    payload={props.payload as any}
                    focusedKey={focusedValueSeries}
                    onToggle={(key) => setFocusedValueSeries(focusedValueSeries === key ? null : key)}
                  />
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
                hide={focusedValueSeries !== null && focusedValueSeries !== "total"}
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
                  hide={focusedValueSeries !== null && focusedValueSeries !== seriesDef.key}
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="card p-4">
        <div className="mb-2">
          <div className="text-sm font-medium">Allocation by account category</div>
          {focusedPercentSeries && (
            <button className="btn-ghost text-xs mt-1" onClick={() => setFocusedPercentSeries(null)}>
              show all percentages
            </button>
          )}
        </div>
        <div className="h-72">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={chartData}>
              <CartesianGrid stroke="#eceef2" vertical={false} />
              <XAxis
                dataKey="date"
                tickFormatter={(d) => shortDateLabel(d)}
                tick={{ fontSize: 12 }}
                stroke="#7a8392"
              />
              <YAxis
                domain={["auto", "auto"]}
                tickFormatter={(v) => `${Number(v).toFixed(0)}%`}
                tick={{ fontSize: 12 }}
                stroke="#7a8392"
                width={70}
              />
              <Tooltip formatter={(v: number) => `${v.toFixed(1)}%`} labelFormatter={(l) => dateLabel(l as string)} />
              <Legend
                content={(props) => (
                  <ChartLegend
                    payload={props.payload as any}
                    focusedKey={focusedPercentSeries}
                    onToggle={(key) => setFocusedPercentSeries(focusedPercentSeries === key ? null : key)}
                  />
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
                  hide={focusedPercentSeries !== null && focusedPercentSeries !== seriesDef.pctKey}
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </div>
      </div>

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
            {snapshots.data?.map((s) => {
              const point = series.data?.find((p) => p.snapshot_date === s.snapshot_date);
              return (
                <tr key={s.id} className="table-row-hover">
                  <td className="px-3 py-2">{dateLabel(s.snapshot_date)}</td>
                  <td className="px-3 py-2 text-right font-medium">{currency(point?.total)}</td>
                  <td className="px-3 py-2 text-right">{currency(point?.by_category.bank)}</td>
                  <td className="px-3 py-2 text-right">{currency(point?.by_category.investment)}</td>
                  <td className="px-3 py-2 text-right">{currency(point?.by_category.tax_advantaged)}</td>
                  <td className="px-3 py-2 text-right">{currency(point?.by_category.nonsense)}</td>
                  <td className="px-3 py-2 text-right">{currency(point?.by_category.cash)}</td>
                  <td className="px-3 py-2 text-right">
                    <button className="btn-ghost text-xs" onClick={() => setEditingSnapId(s.id)}>
                      Edit
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {editingSnapId !== null && (
        <SnapshotEditor
          // Re-key on the editing target so reopening the panel for a different
          // snapshot resets local state (the useState initializer only runs once).
          key={String(editingSnapId)}
          snapshot={
            editingSnapId === "new"
              ? null
              : (snapshots.data?.find((s) => s.id === editingSnapId) ?? null)
          }
          prefillFrom={editingSnapId === "new" ? latest : undefined}
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
      )}
    </div>
  );
}

function DateRangeControls({
  start,
  end,
  onStart,
  onEnd,
}: {
  start: string;
  end: string;
  onStart: (value: string) => void;
  onEnd: (value: string) => void;
}) {
  return (
    <>
      <label className="flex items-center gap-1">
        <span className="text-ink-500">From</span>
        <input type="date" className="input" value={start} onChange={(e) => onStart(e.target.value)} />
      </label>
      <label className="flex items-center gap-1">
        <span className="text-ink-500">To</span>
        <input type="date" className="input" value={end} onChange={(e) => onEnd(e.target.value)} />
      </label>
    </>
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
          <span>Click the ↗ icon next to any account to open its login in a new tab.</span>
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
