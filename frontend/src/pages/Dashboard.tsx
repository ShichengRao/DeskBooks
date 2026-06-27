import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { api, qs } from "../api/client";
import { ChartColorControls, useChartColors } from "../components/ChartColorControls";
import { DateRangeControls } from "../components/DateRangeControls";
import type { FireProjection, Goal, NetWorthSeriesPoint } from "../api/types";
import { colorAt } from "../lib/chartColors";
import { accountCategoryLabel } from "../lib/labels";
import { compactCurrency, currency, dateLabel, num } from "../lib/fmt";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

export function Dashboard() {
  const [rangeStart, setRangeStart] = useState("");
  const [rangeEnd, setRangeEnd] = useState("");
  const chartColors = useChartColors();
  const series = useQuery({
    queryKey: ["nw-series", rangeStart, rangeEnd],
    queryFn: () =>
      api.get<NetWorthSeriesPoint[]>(
        "/api/snapshots/series" + qs({ start: rangeStart || undefined, end: rangeEnd || undefined }),
      ),
  });
  const goals = useQuery({
    queryKey: ["goals"],
    queryFn: () => api.get<Goal[]>("/api/goals"),
  });
  // FIRE projection is small and cheap; load it on the Dashboard so the
  // retirement goal can show "projected: 2042" inline.
  const fire = useQuery({
    queryKey: ["fire-projection"],
    queryFn: () => api.get<FireProjection>("/api/analytics/fire/projection?max_years=60"),
  });

  const last = series.data?.[series.data.length - 1];
  const first = series.data?.[0];
  const change = last && first ? num(last.total) - num(first.total) : 0;
  const changePct = last && first && num(first.total) ? (change / num(first.total)) * 100 : 0;

  const chartData =
    series.data?.map((p) => ({
      date: p.snapshot_date,
      total: num(p.total),
      liquid: num(p.liquid),
      tax_advantaged: num(p.tax_advantaged),
    })) ?? [];

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold tracking-tight">Dashboard</h1>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Stat label="Net Worth" value={currency(last?.total)} sub={last ? `as of ${dateLabel(last.snapshot_date)}` : ""} />
        <Stat
          label="Change since first snapshot"
          value={currency(change, { showSign: true })}
          sub={`${changePct >= 0 ? "+" : ""}${changePct.toFixed(1)}%`}
          tone={change >= 0 ? "good" : "bad"}
        />
        <Stat label="Bank Accounts" value={compactCurrency(last?.by_category.bank)} />
        <Stat label="Tax Advantaged Accounts" value={compactCurrency(last?.by_category.tax_advantaged)} />
      </div>

      <div className="card p-4">
        <div className="flex justify-between items-start gap-3 mb-2 flex-wrap">
          <div className="text-sm font-medium">Net worth over time</div>
          <div className="flex items-center gap-2 text-xs flex-wrap justify-end">
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
            <Link to="/networth" className="text-xs text-brand-600 hover:underline">
              See snapshots →
            </Link>
          </div>
        </div>
        <div className="h-64">
          {chartData.length > 0 ? (
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={chartData} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
                <defs>
                  <linearGradient id="gTotal" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={colorAt(chartColors.colors, 0)} stopOpacity={0.5} />
                    <stop offset="100%" stopColor={colorAt(chartColors.colors, 0)} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="#eceef2" vertical={false} />
                <XAxis
                  dataKey="date"
                  tickFormatter={(d) => dateLabel(d).split(",")[0]}
                  tick={{ fontSize: 12 }}
                  stroke="#7a8392"
                />
                <YAxis
                  tickFormatter={(v) => compactCurrency(v)}
                  tick={{ fontSize: 12 }}
                  stroke="#7a8392"
                  width={70}
                />
                <Tooltip
                  formatter={(v: number) => currency(v)}
                  labelFormatter={(l: string) => dateLabel(l)}
                />
                <Area type="monotone" dataKey="total" stroke={colorAt(chartColors.colors, 0)} fill="url(#gTotal)" strokeWidth={2} />
              </AreaChart>
            </ResponsiveContainer>
          ) : (
            <Empty>No snapshots yet — head to <Link to="/networth" className="underline">Net Worth</Link> to add one.</Empty>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div className="card p-4">
          <div className="flex justify-between items-baseline mb-3">
            <div className="text-sm font-medium">Active goals</div>
            <Link to="/planning" className="text-xs text-brand-600 hover:underline">
              See planning →
            </Link>
          </div>
          {goals.data?.length ? (
            <ul className="divide-y divide-ink-100">
              {goals.data
                .filter((g) => g.status === "active")
                .slice(0, 5)
                .map((g) => (
                  <GoalRow key={g.id} goal={g} fire={fire.data} />
                ))}
            </ul>
          ) : (
            <Empty>No goals yet.</Empty>
          )}
        </div>

        <div className="card p-4">
          <div className="text-sm font-medium mb-3">By account category (latest)</div>
          {last ? (
            <ul className="space-y-2 tabular">
              {Object.entries(last.by_category)
                .sort((a, b) => num(b[1]) - num(a[1]))
                .map(([k, v]) => (
                  <li key={k} className="flex items-center justify-between text-sm">
                    <span className="text-ink-700">{accountCategoryLabel(k)}</span>
                    <span className="font-medium">{currency(v)}</span>
                  </li>
                ))}
            </ul>
          ) : (
            <Empty>—</Empty>
          )}
        </div>
      </div>
    </div>
  );
}

function Stat({
  label,
  value,
  sub,
  tone,
}: {
  label: string;
  value: string;
  sub?: string;
  tone?: "good" | "bad";
}) {
  return (
    <div className="card p-4">
      <div className="label">{label}</div>
      <div
        className={`mt-1 text-2xl font-semibold tabular ${tone === "good" ? "text-good-600" : tone === "bad" ? "text-bad-600" : ""}`}
      >
        {value}
      </div>
      {sub && <div className="text-xs text-ink-500 mt-0.5">{sub}</div>}
    </div>
  );
}

function GoalRow({ goal, fire }: { goal: Goal; fire: FireProjection | undefined }) {
  const progress = useQuery({
    queryKey: ["goal-progress", goal.id],
    queryFn: () => api.get<{ current: string | null; target: string | null; percent: number | null; as_of?: string }>(`/api/goals/${goal.id}/progress`),
  });
  const pct = progress.data?.percent ?? null;
  // Surface the FIRE projection on retirement-kind goals. If the goal has
  // a target_date and the projection beats it, show that as a win.
  const showFire = goal.kind === "retirement" && fire;
  const projectedYear = fire?.retirement_year ?? null;
  const goalYear = goal.target_date ? new Date(goal.target_date).getFullYear() : null;
  const onTrack =
    showFire && goalYear && projectedYear !== null && projectedYear <= goalYear;
  return (
    <li className="py-2">
      <div className="flex items-baseline justify-between">
        <div className="font-medium text-sm">{goal.title}</div>
        <div className="text-xs text-ink-500">
          {goal.target_amount ? currency(goal.target_amount) : "no target"}
          {goal.target_date ? ` · by ${dateLabel(goal.target_date)}` : ""}
        </div>
      </div>
      <div className="mt-1 h-1.5 bg-ink-100 rounded-full overflow-hidden">
        <div
          className="h-full bg-brand-500"
          style={{ width: `${Math.min(100, Math.max(0, pct ?? 0))}%` }}
        />
      </div>
      <div className="text-xs text-ink-500 mt-1 tabular flex items-baseline justify-between gap-2">
        <span>
          {progress.data?.current ? currency(progress.data.current) : "—"}{" "}
          {pct !== null ? `(${pct.toFixed(1)}%)` : ""}
        </span>
        {showFire && (
          <span
            className={
              projectedYear === null
                ? "text-bad-600"
                : onTrack
                  ? "text-good-600"
                  : goalYear
                    ? "text-warn-600"
                    : "text-ink-500"
            }
            title={
              projectedYear === null
                ? "FIRE projection: target never reached within 60 years"
                : "Year your projected NLV crosses (annual spend / withdrawal rate)"
            }
          >
            FIRE: {projectedYear ?? "—"}
            {goalYear && projectedYear !== null && (
              <> ({projectedYear <= goalYear ? "ahead" : "behind"} by {Math.abs(projectedYear - goalYear)}y)</>
            )}
          </span>
        )}
      </div>
    </li>
  );
}

function Empty({ children }: { children: React.ReactNode }) {
  return <div className="text-sm text-ink-500 italic p-6 text-center">{children}</div>;
}
