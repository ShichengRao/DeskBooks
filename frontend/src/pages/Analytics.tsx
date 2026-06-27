import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Plot from "react-plotly.js";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis, Legend } from "recharts";
import { ChartLegend } from "../components/ChartLegend";
import { ChartColorControls, useChartColors } from "../components/ChartColorControls";
import { api, qs } from "../api/client";
import type { MonthlyPoint, RecurringVendor, SankeyResponse } from "../api/types";
import { colorAt } from "../lib/chartColors";
import { currency, monthLabel, num } from "../lib/fmt";

export function Analytics() {
  const today = new Date();
  const yearNow = today.getFullYear();
  const [year, setYear] = useState(yearNow);
  const [monthlyStart, setMonthlyStart] = useState(`${yearNow}-01-01`);
  const [monthlyEnd, setMonthlyEnd] = useState(`${yearNow}-12-31`);
  const [sankeyStart, setSankeyStart] = useState(`${yearNow}-01-01`);
  const [sankeyEnd, setSankeyEnd] = useState(`${yearNow}-12-31`);
  const [focusedSankey, setFocusedSankey] = useState<string | null>(null);
  const [focusedExpense, setFocusedExpense] = useState<string | null>(null);
  const chartColors = useChartColors();

  const monthly = useQuery({
    queryKey: ["monthly", monthlyStart, monthlyEnd],
    queryFn: () => api.get<MonthlyPoint[]>(`/api/analytics/monthly?start=${monthlyStart}&end=${monthlyEnd}`),
    enabled: Boolean(monthlyStart && monthlyEnd),
  });
  const sankey = useQuery({
    queryKey: ["sankey", sankeyStart, sankeyEnd],
    queryFn: () =>
      api.get<SankeyResponse>(
        "/api/analytics/sankey" + qs({ start: sankeyStart, end: sankeyEnd }),
      ),
    enabled: Boolean(sankeyStart && sankeyEnd),
  });
  const [recurringStart, setRecurringStart] = useState("");
  const [recurringEnd, setRecurringEnd] = useState("");
  const [recurringMin, setRecurringMin] = useState(3);
  const recurring = useQuery({
    queryKey: ["recurring", recurringStart, recurringEnd, recurringMin],
    queryFn: () =>
      api.get<RecurringVendor[]>(
        "/api/analytics/recurring" +
          qs({
            min_occurrences: recurringMin,
            start: recurringStart || undefined,
            end: recurringEnd || undefined,
          }),
      ),
  });

  const monthlyChart = useMemo(() => {
    // by_expense_category only contains expense-kind rows (salary etc. are
    // separate fields), so the stacked bar can't accidentally include income.
    const totals: Record<string, number> = {};
    for (const p of monthly.data ?? []) {
      for (const [k, v] of Object.entries(p.by_expense_category)) {
        totals[k] = (totals[k] ?? 0) + Math.abs(num(v));
      }
    }
    let cats = Object.entries(totals)
      .sort((a, b) => b[1] - a[1])
      .map(([k]) => k);
    if (focusedExpense) cats = cats.filter((c) => c === focusedExpense);
    const data = (monthly.data ?? []).map((p) => {
      const row: any = { month: p.month, __total: num(p.expenses_total) };
      for (const c of cats) row[c] = Math.abs(num(p.by_expense_category[c] ?? 0));
      return row;
    });
    return { data, cats };
  }, [focusedExpense, monthly.data]);

  const visibleSankey = useMemo(
    () => focusSankey(sankey.data, focusedSankey),
    [focusedSankey, sankey.data],
  );

  // Per-month transaction count for the coverage indicator. A zero / very
  // low count is almost always "I haven't imported this month's CSV yet"
  // rather than "I didn't spend anything."
  const coverageQ = useQuery({
    queryKey: ["coverage", monthlyStart, monthlyEnd],
    queryFn: () => api.get<MonthlyPoint[]>(`/api/analytics/monthly?start=${monthlyStart}&end=${monthlyEnd}`),
    enabled: Boolean(monthlyStart && monthlyEnd),
  });
  const coverage = useMemo(() => {
    const months: { month: string; count: number }[] = [];
    const startDate = new Date(`${monthlyStart}T00:00:00`);
    const endDate = new Date(`${monthlyEnd}T00:00:00`);
    const cursor = new Date(startDate.getFullYear(), startDate.getMonth(), 1);
    while (cursor <= endDate) {
      const key = `${cursor.getFullYear()}-${String(cursor.getMonth() + 1).padStart(2, "0")}`;
      const p = (coverageQ.data ?? []).find((x) => x.month === key);
      const count = p ? Object.values(p.by_kind).filter((v) => num(v) !== 0).length : 0;
      months.push({ month: key, count });
      cursor.setMonth(cursor.getMonth() + 1);
    }
    return months;
  }, [coverageQ.data, monthlyEnd, monthlyStart]);

  return (
    <div className="space-y-6">
      <div className="flex items-baseline justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Analytics</h1>
        <div className="flex items-center gap-2">
          <button className="btn" onClick={() => setYear(year - 1)}>←</button>
          <div className="font-medium text-lg tabular">{year}</div>
          <button className="btn" onClick={() => setYear(year + 1)}>→</button>
          <button
            className="btn"
            onClick={() => {
              const start = `${year}-01-01`;
              const end = `${year}-12-31`;
              setMonthlyStart(start);
              setMonthlyEnd(end);
              setSankeyStart(start);
              setSankeyEnd(end);
            }}
          >
            use year
          </button>
        </div>
      </div>

      <div className="card p-4">
        <div className="flex items-center justify-between gap-3 flex-wrap mb-2">
          <div className="text-sm font-medium">
            Sankey: inflows → outflows ({sankey.data?.label ?? `${sankeyStart} to ${sankeyEnd}`})
          </div>
          <div className="flex items-center gap-2 text-xs flex-wrap">
            <DateRangeControls
              start={sankeyStart}
              end={sankeyEnd}
              onStart={setSankeyStart}
              onEnd={setSankeyEnd}
            />
            <ChartColorControls
              paletteId={chartColors.paletteId}
              colors={chartColors.colors}
              onPaletteChange={chartColors.setPaletteId}
              onColorChange={chartColors.setColor}
            />
          </div>
        </div>
        {sankey.isLoading ? (
          <div className="text-sm text-ink-500 italic py-12 text-center">
            Loading Sankey data…
          </div>
        ) : visibleSankey && visibleSankey.nodes.length > 1 ? (
          <Plot
            data={[
              {
                type: "sankey",
                orientation: "h",
                node: {
                  pad: 14,
                  thickness: 16,
                  line: { color: "#ccc", width: 0.5 },
                  label: visibleSankey.nodes.map((n) => n.name),
                  color: visibleSankey.nodes.map((_, i) => colorAt(chartColors.colors, i)),
                },
                link: {
                  source: visibleSankey.links.map((l) => l.source),
                  target: visibleSankey.links.map((l) => l.target),
                  value: visibleSankey.links.map((l) => l.value),
                  label: visibleSankey.links.map((l) => l.label ?? ""),
                  color: visibleSankey.links.map((l) => `${colorAt(chartColors.colors, l.source)}66`),
                },
              } as any,
            ]}
            layout={{
              autosize: true,
              height: 480,
              margin: { l: 20, r: 20, t: 10, b: 10 },
              font: { family: "Inter, system-ui", size: 12 },
            }}
            useResizeHandler
            style={{ width: "100%", height: "480px" }}
            config={{ displayModeBar: false }}
            onClick={(event: any) => {
              const point = event?.points?.[0];
              const label = typeof point?.label === "string" ? point.label : null;
              const sourceLabel = typeof point?.source?.label === "string" ? point.source.label : null;
              const targetLabel = typeof point?.target?.label === "string" ? point.target.label : null;
              setFocusedSankey(label || sourceLabel || targetLabel);
            }}
          />
        ) : (
          <div className="text-sm text-ink-500 italic py-12 text-center">
            No transactions in this period yet — import a CSV to populate this chart.
          </div>
        )}
        {focusedSankey && (
          <button className="btn-ghost text-xs mt-2" onClick={() => setFocusedSankey(null)}>
            clear Sankey focus: {focusedSankey}
          </button>
        )}
        {sankey.data?.notes?.length ? (
          <ul className="mt-2 text-xs text-ink-500 list-disc pl-5">
            {sankey.data.notes.map((n) => <li key={n}>{n}</li>)}
          </ul>
        ) : null}
      </div>

      <div className="card p-4">
        <div className="flex items-baseline justify-between mb-2">
          <div>
            <div className="text-sm font-medium">Monthly expenses by category</div>
            <div className="mt-2 flex items-center gap-2 text-xs flex-wrap">
              <DateRangeControls
                start={monthlyStart}
                end={monthlyEnd}
                onStart={setMonthlyStart}
                onEnd={setMonthlyEnd}
              />
              {focusedExpense && (
                <button className="btn-ghost text-xs" onClick={() => setFocusedExpense(null)}>
                  clear category: {focusedExpense}
                </button>
              )}
            </div>
          </div>
          <div className="text-[11px] text-ink-500 flex items-center gap-1">
            <span>Data coverage:</span>
            <div className="flex items-center gap-0.5">
              {coverage.map((m) => (
                <span
                  key={m.month}
                  title={`${m.month}: ${m.count > 0 ? "data present" : "no data — import may be missing"}`}
                  className={`inline-block w-2.5 h-3 rounded-sm ${m.count > 0 ? "bg-good-500" : "bg-ink-200"}`}
                />
              ))}
            </div>
            <span className="ml-1">{monthlyStart}→{monthlyEnd}</span>
          </div>
        </div>
        <div className="h-80">
          {monthly.isLoading ? (
            <div className="text-sm text-ink-500 italic py-12 text-center">
              Loading monthly expenses…
            </div>
          ) : monthlyChart.data.length > 0 ? (
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={monthlyChart.data}>
                <CartesianGrid stroke="#eceef2" vertical={false} />
                <XAxis dataKey="month" tickFormatter={monthLabel} tick={{ fontSize: 12 }} />
                <YAxis tickFormatter={(v) => currency(v).replace(/\.\d\d/, "")} tick={{ fontSize: 12 }} width={70} />
                <Tooltip content={<MonthlyExpenseTooltip />} />
                <Legend
                  content={(props) => (
                    <ChartLegend
                      payload={props.payload as any}
                      focusedKey={focusedExpense}
                      onToggle={(key) => setFocusedExpense(focusedExpense === key ? null : key)}
                    />
                  )}
                />
                {monthlyChart.cats.slice(0, 10).map((c, i) => (
                  <Bar
                    key={c}
                    dataKey={c}
                    stackId="exp"
                    fill={colorAt(chartColors.colors, i)}
                    name={c}
                    onClick={() => setFocusedExpense(c)}
                    cursor="pointer"
                  />
                ))}
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <div className="text-sm text-ink-500 italic py-12 text-center">
              No data — import some transactions.
            </div>
          )}
        </div>
      </div>

      <div className="card p-4">
        <div className="flex items-baseline justify-between mb-2 gap-3 flex-wrap">
          <div className="text-sm font-medium">Recurring merchants</div>
          <div className="flex items-center gap-2 text-xs">
            <label className="flex items-center gap-1">
              <span className="text-ink-500">From</span>
              <input
                type="date"
                className="input"
                value={recurringStart}
                onChange={(e) => setRecurringStart(e.target.value)}
              />
            </label>
            <label className="flex items-center gap-1">
              <span className="text-ink-500">To</span>
              <input
                type="date"
                className="input"
                value={recurringEnd}
                onChange={(e) => setRecurringEnd(e.target.value)}
              />
            </label>
            <label className="flex items-center gap-1">
              <span className="text-ink-500">Min occurrences</span>
              <input
                type="number"
                min={1}
                className="input max-w-[5rem] tabular text-right"
                value={recurringMin}
                onChange={(e) => setRecurringMin(parseInt(e.target.value || "1", 10))}
              />
            </label>
            <button
              className="btn-ghost text-xs"
              onClick={() => {
                setRecurringStart("");
                setRecurringEnd("");
                setRecurringMin(3);
              }}
            >
              reset
            </button>
          </div>
        </div>
        {recurring.isLoading ? (
          <div className="text-sm text-ink-500 italic">Loading recurring merchants…</div>
        ) : recurring.data?.length ? (
          <table className="w-full text-sm tabular">
            <thead className="bg-ink-50">
              <tr>
                <th className="px-3 py-2 text-left">Merchant</th>
                <th className="px-3 py-2 text-right">Count</th>
                <th className="px-3 py-2 text-right">Avg</th>
                <th className="px-3 py-2 text-right">Total</th>
                <th className="px-3 py-2 text-right">Cadence (days)</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-100">
              {recurring.data.map((r) => (
                <tr key={r.merchant}>
                  <td className="px-3 py-1.5">{r.merchant}</td>
                  <td className="px-3 py-1.5 text-right">{r.occurrences}</td>
                  <td className="px-3 py-1.5 text-right">{currency(r.avg_amount)}</td>
                  <td className="px-3 py-1.5 text-right">{currency(r.total_amount)}</td>
                  <td className="px-3 py-1.5 text-right">
                    {r.cadence_days_estimate ? r.cadence_days_estimate.toFixed(1) : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div className="text-sm text-ink-500 italic">No recurring merchants detected yet.</div>
        )}
      </div>
    </div>
  );
}

function MonthlyExpenseTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null;
  const row = payload[0]?.payload ?? {};
  const categoryRows = payload
    .filter((p: any) => p.dataKey !== "__total" && Number(p.value) !== 0)
    .sort((a: any, b: any) => Number(b.value) - Number(a.value));
  return (
    <div className="rounded-md border border-ink-200 bg-white px-3 py-2 text-xs shadow-sm min-w-[14rem]">
      <div className="font-medium text-ink-900 mb-1">{monthLabel(String(label))}</div>
      <div className="flex items-center justify-between gap-4 border-b border-ink-100 pb-1 mb-1">
        <span className="text-ink-500">Total expenses</span>
        <span className="font-semibold tabular">{currency(row.__total ?? 0)}</span>
      </div>
      <div className="space-y-0.5">
        {categoryRows.map((p: any) => (
          <div key={p.dataKey} className="flex items-center justify-between gap-4">
            <span className="flex items-center gap-1.5 text-ink-600">
              <span className="h-2 w-2 rounded-sm" style={{ backgroundColor: p.color }} />
              {p.name}
            </span>
            <span className="tabular">{currency(p.value)}</span>
          </div>
        ))}
      </div>
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

function focusSankey(data: SankeyResponse | undefined, focus: string | null): SankeyResponse | undefined {
  if (!data || !focus) return data;
  const matchingNodeIds = new Set<number>();
  data.nodes.forEach((node, index) => {
    if (node.name === focus) matchingNodeIds.add(index);
  });
  const exactLinks = data.links.filter((link) => link.label === focus);
  let keptLinks = data.links;
  if (exactLinks.length && !matchingNodeIds.size) {
    keptLinks = data.links.filter(
      (link) =>
        exactLinks.includes(link) ||
        exactLinks.some((exact) => link.target === exact.source || link.source === exact.target),
    );
  } else {
    const seedNodes = new Set(matchingNodeIds);
    for (const link of exactLinks) {
      seedNodes.add(link.source);
      seedNodes.add(link.target);
    }
    if (!seedNodes.size) return data;
    keptLinks = data.links.filter((link) => seedNodes.has(link.source) || seedNodes.has(link.target));
  }
  const keptNodeIds = new Set<number>();
  for (const link of keptLinks) {
    keptNodeIds.add(link.source);
    keptNodeIds.add(link.target);
  }
  const keptIds = Array.from(keptNodeIds).sort((a, b) => a - b);
  const remap = new Map(keptIds.map((oldId, newId) => [oldId, newId]));
  return {
    ...data,
    nodes: keptIds.map((id) => data.nodes[id]),
    links: keptLinks.map((link) => ({
      ...link,
      source: remap.get(link.source)!,
      target: remap.get(link.target)!,
    })),
  };
}
