import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import clsx from "clsx";
import { api, qs } from "../api/client";
import type {
  BudgetDefault,
  BudgetMonthSummary,
  BudgetOverride,
  BudgetReport,
  BudgetReportRow,
} from "../api/types";
import { currency, monthLabel, num } from "../lib/fmt";

type RangePreset = "6m" | "12m" | "custom";

function currentMonthValue() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
}

function addMonths(monthValue: string, delta: number) {
  const [year, month] = monthValue.split("-").map(Number);
  const d = new Date(year, month - 1 + delta, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

function monthDate(monthValue: string) {
  return `${monthValue}-01`;
}

function monthValue(dateValue: string) {
  return dateValue.slice(0, 7);
}

function moneyInput(value: string | null) {
  if (value === null) return "";
  return Number(value).toFixed(2);
}

function SummaryCard({
  label,
  value,
  tone,
}: {
  label: string;
  value: string | number;
  tone?: "good" | "bad";
}) {
  return (
    <div className="card p-4">
      <div className="label">{label}</div>
      <div
        className={clsx(
          "text-2xl font-semibold tabular mt-1",
          tone === "good" && "text-good-600",
          tone === "bad" && "text-bad-600",
        )}
      >
        {currency(value, { showSign: label.startsWith("Delta") })}
      </div>
    </div>
  );
}

function MonthButton({
  month,
  active,
  onClick,
}: {
  month: BudgetMonthSummary;
  active: boolean;
  onClick: () => void;
}) {
  const delta = num(month.delta_total);
  return (
    <button
      type="button"
      className={clsx(
        "text-left rounded-lg border p-3 bg-white hover:border-brand-400 transition-colors",
        active ? "border-brand-500 ring-1 ring-brand-500" : "border-ink-200",
      )}
      onClick={onClick}
    >
      <div className="flex items-baseline justify-between gap-3">
        <div className="font-medium">{monthLabel(month.month.slice(0, 7))}</div>
        <div className={clsx("font-semibold tabular", delta >= 0 ? "text-good-600" : "text-bad-600")}>
          {currency(delta, { showSign: true })}
        </div>
      </div>
      <div className="mt-2 grid grid-cols-2 gap-2 text-xs text-ink-500 tabular">
        <span>Plan {currency(month.planned_total)}</span>
        <span className="text-right">Actual {currency(month.actual_total)}</span>
      </div>
    </button>
  );
}

export function Budgets() {
  const qc = useQueryClient();
  const current = currentMonthValue();
  const [preset, setPreset] = useState<RangePreset>("6m");
  const [rangeEnd, setRangeEnd] = useState(current);
  const [customStart, setCustomStart] = useState(addMonths(current, -5));
  const [customEnd, setCustomEnd] = useState(current);
  const [focusMonth, setFocusMonth] = useState<string | null>(current);

  const startMonth = preset === "custom" ? customStart : addMonths(rangeEnd, preset === "6m" ? -5 : -11);
  const endMonth = preset === "custom" ? customEnd : rangeEnd;

  useEffect(() => {
    if (focusMonth && focusMonth < startMonth) setFocusMonth(startMonth);
    if (focusMonth && focusMonth > endMonth) setFocusMonth(endMonth);
  }, [endMonth, focusMonth, startMonth]);

  const report = useQuery({
    queryKey: ["budgets", startMonth, endMonth, focusMonth],
    queryFn: () =>
      api.get<BudgetReport>(
        `/api/budgets${qs({
          start: monthDate(startMonth),
          end: monthDate(endMonth),
          focus_month: focusMonth ? monthDate(focusMonth) : undefined,
        })}`,
      ),
  });

  const [defaultDrafts, setDefaultDrafts] = useState<Record<number, string>>({});
  const [overrideDrafts, setOverrideDrafts] = useState<Record<number, string>>({});
  const [dirtyDefaults, setDirtyDefaults] = useState<Set<number>>(new Set());
  const [dirtyOverrides, setDirtyOverrides] = useState<Set<number>>(new Set());

  useEffect(() => {
    setDirtyOverrides(new Set());
  }, [focusMonth]);

  useEffect(() => {
    if (!report.data) return;
    setDefaultDrafts((prev) =>
      Object.fromEntries(
        report.data.rows.map((row) => [
          row.category_id,
          dirtyDefaults.has(row.category_id)
            ? (prev[row.category_id] ?? moneyInput(row.default_amount))
            : moneyInput(row.default_amount),
        ]),
      ),
    );
    setOverrideDrafts((prev) =>
      Object.fromEntries(
        report.data.rows.map((row) => [
          row.category_id,
          dirtyOverrides.has(row.category_id)
            ? (prev[row.category_id] ?? moneyInput(row.override_amount))
            : moneyInput(row.override_amount),
        ]),
      ),
    );
  }, [dirtyDefaults, dirtyOverrides, report.data]);

  const refresh = () => qc.invalidateQueries({ queryKey: ["budgets"] });

  const saveDefault = useMutation({
    mutationFn: (row: BudgetReportRow) => {
      const raw = defaultDrafts[row.category_id]?.trim() ?? "";
      if (raw === "") {
        if (row.default_budget_id === null) return Promise.resolve(null);
        return api.del(`/api/budgets/defaults/${row.default_budget_id}`);
      }
      return api.put<BudgetDefault>("/api/budgets/defaults", {
        category_id: row.category_id,
        amount: raw,
        notes: row.default_notes,
      });
    },
    onSuccess: (_data, row) => {
      setDirtyDefaults((prev) => {
        const next = new Set(prev);
        next.delete(row.category_id);
        return next;
      });
      refresh();
    },
  });

  const saveOverride = useMutation({
    mutationFn: (row: BudgetReportRow) => {
      const raw = overrideDrafts[row.category_id]?.trim() ?? "";
      if (raw === "") {
        if (row.override_budget_id === null) return Promise.resolve(null);
        return api.del(`/api/budgets/overrides/${row.override_budget_id}`);
      }
      return api.put<BudgetOverride>("/api/budgets/overrides", {
        month: monthDate(focusMonth ?? endMonth),
        category_id: row.category_id,
        amount: raw,
        notes: row.override_notes,
      });
    },
    onSuccess: (_data, row) => {
      setDirtyOverrides((prev) => {
        const next = new Set(prev);
        next.delete(row.category_id);
        return next;
      });
      refresh();
    },
  });

  const rows = report.data?.rows ?? [];
  const isRangeView = focusMonth === null;
  const focusSummary = focusMonth
    ? report.data?.months.find((m) => monthValue(m.month) === focusMonth)
    : null;
  const focusDelta = num(focusSummary?.delta_total);
  const rangeDelta = num(report.data?.delta_total);

  const changedDefaults = useMemo(() => {
    const changed = new Set<number>();
    for (const row of rows) {
      if ((defaultDrafts[row.category_id] ?? "") !== moneyInput(row.default_amount)) {
        changed.add(row.category_id);
      }
    }
    return changed;
  }, [defaultDrafts, rows]);

  const changedOverrides = useMemo(() => {
    const changed = new Set<number>();
    for (const row of rows) {
      if ((overrideDrafts[row.category_id] ?? "") !== moneyInput(row.override_amount)) {
        changed.add(row.category_id);
      }
    }
    return changed;
  }, [overrideDrafts, rows]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Budgets</h1>
        <div className="flex flex-wrap items-end gap-3">
          <label className="block">
            <div className="label mb-1">Range</div>
            <select className="input w-36" value={preset} onChange={(e) => setPreset(e.target.value as RangePreset)}>
              <option value="6m">Past 6 months</option>
              <option value="12m">Past year</option>
              <option value="custom">Custom</option>
            </select>
          </label>
          {preset === "custom" ? (
            <>
              <label className="block">
                <div className="label mb-1">Start</div>
                <input type="month" className="input w-40" value={customStart} onChange={(e) => setCustomStart(e.target.value)} />
              </label>
              <label className="block">
                <div className="label mb-1">End</div>
                <input type="month" className="input w-40" value={customEnd} onChange={(e) => setCustomEnd(e.target.value)} />
              </label>
            </>
          ) : (
            <label className="block">
              <div className="label mb-1">Ending</div>
              <input type="month" className="input w-40" value={rangeEnd} onChange={(e) => setRangeEnd(e.target.value)} />
            </label>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <SummaryCard label="Range planned" value={report.data?.planned_total ?? "0"} />
        <SummaryCard label="Range actual" value={report.data?.actual_total ?? "0"} />
        <SummaryCard label="Range delta" value={report.data?.delta_total ?? "0"} tone={rangeDelta >= 0 ? "good" : "bad"} />
        <SummaryCard
          label={isRangeView ? "Table delta" : "Selected delta"}
          value={isRangeView ? (report.data?.delta_total ?? "0") : (focusSummary?.delta_total ?? "0")}
          tone={(isRangeView ? rangeDelta : focusDelta) >= 0 ? "good" : "bad"}
        />
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 xl:grid-cols-6 gap-3">
        {report.data?.months.map((month) => (
          <MonthButton
            key={month.month}
            month={month}
            active={focusMonth !== null && monthValue(month.month) === focusMonth}
            onClick={() => {
              const clicked = monthValue(month.month);
              setFocusMonth((currentFocus) => (currentFocus === clicked ? null : clicked));
            }}
          />
        ))}
      </div>

      <div className="card overflow-hidden">
        <div className="px-3 py-2 bg-ink-50 flex items-baseline justify-between">
          <div className="text-sm font-medium">
            {isRangeView
              ? `${monthLabel(startMonth)} to ${monthLabel(endMonth)} category net`
              : `${monthLabel(focusMonth)} category plan`}
          </div>
          <div className="text-xs text-ink-500">
            {isRangeView
              ? "Click a month above to edit defaults and overrides for that month."
              : "Defaults apply every month; overrides replace the default for this month only."}
          </div>
        </div>
        <table className="w-full text-sm tabular">
          <thead className="bg-ink-50 text-left">
            <tr>
              <th className="px-3 py-2 font-medium">Category</th>
              {!isRangeView && <th className="px-3 py-2 font-medium text-right">Default</th>}
              {!isRangeView && <th className="px-3 py-2 font-medium text-right">Override</th>}
              <th className="px-3 py-2 font-medium text-right">{isRangeView ? "Budget" : "Effective"}</th>
              <th className="px-3 py-2 font-medium text-right">Actual</th>
              <th className="px-3 py-2 font-medium text-right">Delta</th>
              <th className="px-3 py-2 font-medium text-right">Rows</th>
              {!isRangeView && <th className="px-3 py-2 font-medium text-right">Actions</th>}
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {rows.map((row) => {
              const rowDelta = row.delta === null ? null : num(row.delta);
              const defaultChanged = changedDefaults.has(row.category_id);
              const overrideChanged = changedOverrides.has(row.category_id);
              return (
                <tr key={row.category_id} className="table-row-hover">
                  <td className="px-3 py-2">
                    <div className={clsx("font-medium", row.has_children && "text-ink-900")} style={{ paddingLeft: `${row.depth * 18}px` }}>
                      {row.category_name}
                    </div>
                    {row.parent_name && (
                      <div className="text-xs text-ink-500" style={{ paddingLeft: `${row.depth * 18}px` }}>
                        {row.parent_name}
                      </div>
                    )}
                  </td>
                  {!isRangeView && (
                    <td className="px-3 py-2 w-36">
                      <input
                        type="number"
                        min="0"
                        step="0.01"
                        className={clsx("input text-right tabular", defaultChanged && "border-brand-400")}
                        value={defaultDrafts[row.category_id] ?? ""}
                        onChange={(e) => {
                          setDefaultDrafts((prev) => ({ ...prev, [row.category_id]: e.target.value }));
                          setDirtyDefaults((prev) => new Set(prev).add(row.category_id));
                        }}
                      />
                    </td>
                  )}
                  {!isRangeView && (
                    <td className="px-3 py-2 w-36">
                      <input
                        type="number"
                        min="0"
                        step="0.01"
                        className={clsx("input text-right tabular", overrideChanged && "border-brand-400")}
                        placeholder="inherit"
                        value={overrideDrafts[row.category_id] ?? ""}
                        onChange={(e) => {
                          setOverrideDrafts((prev) => ({ ...prev, [row.category_id]: e.target.value }));
                          setDirtyOverrides((prev) => new Set(prev).add(row.category_id));
                        }}
                      />
                    </td>
                  )}
                  <td className="px-3 py-2 text-right font-medium">{currency(row.target_amount)}</td>
                  <td className="px-3 py-2 text-right">{currency(row.actual_amount)}</td>
                  <td className={clsx("px-3 py-2 text-right font-medium", rowDelta === null ? "text-ink-400" : rowDelta >= 0 ? "text-good-600" : "text-bad-600")}>
                    {row.delta === null ? "—" : currency(row.delta, { showSign: true })}
                  </td>
                  <td className="px-3 py-2 text-right text-ink-500">{row.transaction_count}</td>
                  {!isRangeView && (
                    <td className="px-3 py-2">
                      <div className="flex items-center justify-end gap-2">
                        <button className="btn-primary text-xs" disabled={!defaultChanged || saveDefault.isPending} onClick={() => saveDefault.mutate(row)}>
                          Save default
                        </button>
                        <button className="btn text-xs" disabled={!overrideChanged || saveOverride.isPending} onClick={() => saveOverride.mutate(row)}>
                          Save override
                        </button>
                      </div>
                    </td>
                  )}
                </tr>
              );
            })}
            {rows.length === 0 && (
              <tr>
                <td colSpan={isRangeView ? 5 : 8} className="px-3 py-8 text-center text-ink-500">
                  No expense categories yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
