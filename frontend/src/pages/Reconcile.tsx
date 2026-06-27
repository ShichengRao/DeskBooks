import { useEffect, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import clsx from "clsx";
import { api, qs } from "../api/client";
import type { Account, ReconcileResponse, SplitGroupSummary, Transaction } from "../api/types";
import { currency, dateLabel } from "../lib/fmt";
import { KindPill } from "../lib/kinds";

// Mirrors the "Total (Bank) vs Bank (actual)" reconciliation row from the
// original 6-month expense spreadsheet.

type RangeMode = "month" | "custom";

const monthOptions = Array.from({ length: 12 }, (_, i) => i + 1);

function dateInputValue(year: number, month: number, day: number) {
  return `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

function monthEndDate(year: number, month: number) {
  const endDateObj = new Date(year, month, 0);
  return dateInputValue(endDateObj.getFullYear(), endDateObj.getMonth() + 1, endDateObj.getDate());
}

function ReconcileFilters({
  accounts,
  accountId,
  setAccountId,
  rangeMode,
  setRangeMode,
  year,
  setYear,
  month,
  setMonth,
  customStart,
  setCustomStart,
  customEnd,
  setCustomEnd,
}: {
  accounts: Account[] | undefined;
  accountId: number | "";
  setAccountId: (value: number | "") => void;
  rangeMode: RangeMode;
  setRangeMode: (value: RangeMode) => void;
  year: number;
  setYear: (value: number) => void;
  month: number;
  setMonth: (value: number) => void;
  customStart: string;
  setCustomStart: (value: string) => void;
  customEnd: string;
  setCustomEnd: (value: string) => void;
}) {
  return (
    <div className="card p-4 grid grid-cols-1 md:grid-cols-5 gap-3">
      <label className="block">
        <div className="label mb-1">Account</div>
        <select
          className="input"
          value={accountId}
          onChange={(e) => setAccountId(e.target.value ? parseInt(e.target.value, 10) : "")}
        >
          <option value="">— pick —</option>
          {accounts?.map((a) => (
            <option key={a.id} value={a.id}>{a.name}</option>
          ))}
        </select>
      </label>
      <label className="block">
        <div className="label mb-1">Range</div>
        <select className="input" value={rangeMode} onChange={(e) => setRangeMode(e.target.value as RangeMode)}>
          <option value="month">Calendar month</option>
          <option value="custom">Custom dates</option>
        </select>
      </label>
      <label className="block">
        <div className="label mb-1">Year</div>
        <input
          type="number"
          className="input tabular"
          value={year}
          onChange={(e) => setYear(parseInt(e.target.value, 10))}
          disabled={rangeMode === "custom"}
        />
      </label>
      <label className="block">
        <div className="label mb-1">Month</div>
        <select
          className="input"
          value={month}
          onChange={(e) => setMonth(parseInt(e.target.value, 10))}
          disabled={rangeMode === "custom"}
        >
          {monthOptions.map((m) => (
            <option key={m} value={m}>{new Date(2000, m - 1, 1).toLocaleString("en", { month: "long" })}</option>
          ))}
        </select>
      </label>
      <div className="grid grid-cols-2 gap-2 md:col-span-5">
        <label className="block">
          <div className="label mb-1">Custom start</div>
          <input
            type="date"
            className="input"
            value={customStart}
            onChange={(e) => setCustomStart(e.target.value)}
            disabled={rangeMode === "month"}
          />
        </label>
        <label className="block">
          <div className="label mb-1">Custom end</div>
          <input
            type="date"
            className="input"
            value={customEnd}
            onChange={(e) => setCustomEnd(e.target.value)}
            disabled={rangeMode === "month"}
          />
        </label>
      </div>
    </div>
  );
}

function SummaryCards({
  summary,
  statementInput,
  setStatementInput,
  statementNotes,
  setStatementNotes,
  saveStatement,
  isSaving,
  rangeMode,
  displayedDelta,
}: {
  summary: ReconcileResponse;
  statementInput: string;
  setStatementInput: (value: string) => void;
  statementNotes: string;
  setStatementNotes: (value: string) => void;
  saveStatement: () => void;
  isSaving: boolean;
  rangeMode: RangeMode;
  displayedDelta: number | null;
}) {
  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
      <div className="card p-4">
        <div className="label">Imported net (signed)</div>
        <div className="text-2xl font-semibold tabular mt-1">{currency(summary.imported_total)}</div>
        <div className="text-xs text-ink-500 mt-1">
          {summary.transaction_count} transactions · inflows {currency(summary.imported_inflows)} · outflows {currency(summary.imported_outflows)}
        </div>
      </div>
      <div className="card p-4">
        <div className="label">Statement net (from your bank UI)</div>
        <input
          type="number"
          step="0.01"
          className="input tabular text-right text-2xl font-semibold mt-1 py-2"
          placeholder="—"
          value={statementInput}
          onChange={(e) => setStatementInput(e.target.value)}
        />
        <textarea
          className="input mt-2 text-xs"
          rows={2}
          placeholder="Notes (e.g., source file, observed quirks)"
          value={statementNotes}
          onChange={(e) => setStatementNotes(e.target.value)}
        />
        <button
          className="btn-primary mt-2 text-xs"
          onClick={saveStatement}
          disabled={isSaving || rangeMode === "custom"}
        >
          {rangeMode === "custom" ? "Custom total is not saved" : "Save statement total"}
        </button>
      </div>
      <DeltaCard displayedDelta={displayedDelta} />
    </div>
  );
}

function DeltaCard({ displayedDelta }: { displayedDelta: number | null }) {
  const deltaMatches = displayedDelta !== null && Math.abs(displayedDelta) < 0.5;

  return (
    <div className="card p-4">
      <div className="label">Δ (imported − statement)</div>
      <div
        className={clsx(
          "text-2xl font-semibold tabular mt-1",
          displayedDelta === null
            ? "text-ink-400"
            : deltaMatches
              ? "text-good-600"
              : "text-bad-600",
        )}
      >
        {displayedDelta !== null ? currency(displayedDelta, { showSign: true }) : "—"}
      </div>
      <div className="text-xs text-ink-500 mt-1">
        {displayedDelta === null
          ? "Enter a statement total to see the reconciliation delta."
          : deltaMatches
            ? "Matches within rounding."
            : "Investigate which rows are missing or extra."}
      </div>
    </div>
  );
}

function ByKindBreakdown({ byKind }: { byKind: Record<string, string> }) {
  return (
    <div className="card p-4">
      <div className="text-sm font-medium mb-2">By kind</div>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-2 tabular">
        {Object.entries(byKind).map(([k, v]) => (
          <div key={k} className="flex items-center justify-between bg-ink-50 rounded-md px-3 py-2">
            <KindPill kind={k as any} />
            <span className={clsx("font-medium", Number(v) < 0 ? "text-bad-600" : "text-good-600")}>
              {currency(v)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

function SectionTable({
  title,
  countLabel,
  headers,
  emptyColSpan,
  emptyText,
  isEmpty,
  children,
}: {
  title: string;
  countLabel: string;
  headers: ReactNode;
  emptyColSpan: number;
  emptyText: string;
  isEmpty: boolean;
  children: ReactNode;
}) {
  return (
    <div className="card overflow-hidden">
      <div className="px-3 py-2 bg-ink-50 text-sm flex items-baseline justify-between">
        <span>{title}</span>
        <span className="text-ink-500 text-xs">{countLabel}</span>
      </div>
      <table className="w-full text-sm tabular">
        <thead className="bg-ink-50 text-left">
          <tr>{headers}</tr>
        </thead>
        <tbody className="divide-y divide-ink-100">
          {children}
          {isEmpty && (
            <tr>
              <td colSpan={emptyColSpan} className="p-6 text-center text-ink-500 italic">
                {emptyText}
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function SplitGroupsTable({ groups }: { groups: SplitGroupSummary[] | undefined }) {
  return (
    <SectionTable
      title="Split groups in range"
      countLabel={`${groups?.length ?? 0} groups`}
      headers={
        <>
            <th className="px-2 py-1.5">Group</th>
            <th className="px-2 py-1.5 text-right">Shared charges</th>
            <th className="px-2 py-1.5 text-right">Your share</th>
            <th className="px-2 py-1.5 text-right">Expected back</th>
            <th className="px-2 py-1.5 text-right">Received</th>
            <th className="px-2 py-1.5 text-right">Remaining</th>
        </>
      }
      emptyColSpan={6}
      emptyText="No split transactions in this range."
      isEmpty={!groups || groups.length === 0}
    >
      {groups?.map((g) => (
        <tr key={g.group_name} className="table-row-hover">
          <td className="px-2 py-1.5">
            <div className="font-medium">{g.group_name}</div>
            <div className="text-xs text-ink-500">{g.transaction_count} split transactions</div>
          </td>
          <td className="px-2 py-1.5 text-right">{currency(g.shared_outflows)}</td>
          <td className="px-2 py-1.5 text-right">{currency(g.personal_outflows)}</td>
          <td className="px-2 py-1.5 text-right">{currency(g.expected_reimbursement)}</td>
          <td className="px-2 py-1.5 text-right text-good-600">{currency(g.received_reimbursement)}</td>
          <td
            className={clsx(
              "px-2 py-1.5 text-right font-semibold",
              Math.abs(Number(g.remaining_owed)) < 0.5 ? "text-good-600" : "text-bad-600",
            )}
          >
            {currency(g.remaining_owed, { showSign: true })}
          </td>
        </tr>
      ))}
    </SectionTable>
  );
}

function TransactionsTable({ transactions }: { transactions: Transaction[] | undefined }) {
  return (
    <SectionTable
      title="Transactions in range"
      countLabel={`${transactions?.length ?? 0} rows`}
      headers={
        <>
            <th className="px-2 py-1.5 w-24">Date</th>
            <th className="px-2 py-1.5">Description</th>
            <th className="px-2 py-1.5 w-32">Kind</th>
            <th className="px-2 py-1.5 w-32 text-right">Amount</th>
        </>
      }
      emptyColSpan={4}
      emptyText="No transactions in this account/range."
      isEmpty={!transactions || transactions.length === 0}
    >
      {transactions?.map((t) => (
        <tr key={t.id} className={clsx("table-row-hover", t.is_excluded_from_totals && "opacity-50")}>
          <td className="px-2 py-1 text-ink-600">{dateLabel(t.date)}</td>
          <td className="px-2 py-1">
            <div className="font-medium">{t.merchant ?? t.description_normalized ?? t.description_raw}</div>
            <div className="text-xs text-ink-500 truncate max-w-md">{t.description_raw}</div>
          </td>
          <td className="px-2 py-1"><KindPill kind={t.kind} /></td>
          <td className={clsx("px-2 py-1 text-right font-medium", Number(t.amount) < 0 ? "text-bad-600" : "text-good-600")}>
            {currency(t.amount)}
          </td>
        </tr>
      ))}
    </SectionTable>
  );
}

export function Reconcile() {
  const qc = useQueryClient();
  const accounts = useQuery({ queryKey: ["accounts"], queryFn: () => api.get<Account[]>("/api/accounts") });

  const now = new Date();
  const [accountId, setAccountId] = useState<number | "">("");
  const [year, setYear] = useState<number>(now.getFullYear());
  const [month, setMonth] = useState<number>(now.getMonth() + 1); // 1..12
  const [rangeMode, setRangeMode] = useState<RangeMode>("month");
  const [customStart, setCustomStart] = useState<string>(dateInputValue(now.getFullYear(), now.getMonth() + 1, 1));
  const [customEnd, setCustomEnd] = useState<string>(monthEndDate(now.getFullYear(), now.getMonth() + 1));
  const [statementInput, setStatementInput] = useState<string>("");
  const [statementNotes, setStatementNotes] = useState<string>("");

  // Default to WF Checking the first time accounts load.
  useEffect(() => {
    if (!accountId && accounts.data) {
      const wf = accounts.data.find((a) => a.name === "Wells Fargo Checking");
      if (wf) setAccountId(wf.id);
      else if (accounts.data.length) setAccountId(accounts.data[0].id);
    }
  }, [accounts.data, accountId]);

  const startDate = dateInputValue(year, month, 1);
  const endDate = monthEndDate(year, month);
  const activeStart = rangeMode === "month" ? startDate : customStart;
  const activeEnd = rangeMode === "month" ? endDate : customEnd;
  const canQuery = !!accountId && !!activeStart && !!activeEnd && activeEnd >= activeStart;

  const summary = useQuery({
    queryKey: ["reconcile", accountId, rangeMode, year, month, activeStart, activeEnd],
    queryFn: () =>
      api.get<ReconcileResponse>(
        "/api/analytics/reconcile" +
          qs(
            rangeMode === "month"
              ? { account_id: accountId, year, month }
              : { account_id: accountId, start: activeStart, end: activeEnd },
          ),
      ),
    enabled: canQuery,
  });

  // Initialize the statement input from the loaded summary (whenever
  // the (account, year, month) tuple changes — not on every refetch).
  useEffect(() => {
    if (summary.data && rangeMode === "month") {
      setStatementInput(summary.data.statement_total ?? "");
      setStatementNotes(summary.data.statement_notes ?? "");
    }
    if (rangeMode === "custom") {
      setStatementInput("");
      setStatementNotes("");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rangeMode, summary.data?.account_id, summary.data?.year, summary.data?.month, summary.data?.start, summary.data?.end]);

  const txs = useQuery({
    queryKey: ["reconcile-tx", accountId, activeStart, activeEnd],
    queryFn: () =>
      api.get<Transaction[]>(
        "/api/transactions" +
          qs({ account_id: accountId, start: activeStart, end: activeEnd, limit: 2000 }),
      ),
    enabled: canQuery,
  });
  const splits = useQuery({
    queryKey: ["split-summary", activeStart, activeEnd],
    queryFn: () =>
      api.get<SplitGroupSummary[]>(
        "/api/analytics/splits" + qs({ start: activeStart, end: activeEnd }),
      ),
    enabled: canQuery,
  });

  // The client wrapper doesn't have a `put` helper, so we use raw fetch.
  const putStatement = useMutation({
    mutationFn: async () =>
      fetch("/api/analytics/reconcile", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          account_id: accountId,
          year,
          month,
          statement_total: statementInput === "" ? null : statementInput,
          notes: statementNotes || null,
        }),
      }).then((r) => r.json()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["reconcile"] });
    },
  });

  const s = summary.data;
  const statementValue = statementInput === "" ? null : Number(statementInput);
  const displayedDelta =
    s && statementValue !== null
      ? Number(s.imported_total) - statementValue
      : s?.delta !== null && s?.delta !== undefined
        ? Number(s.delta)
        : null;

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Reconcile</h1>
        <div className="text-sm text-ink-500">
          Compare imported transactions against your bank/CC statement total.
        </div>
      </div>

      <ReconcileFilters
        accounts={accounts.data}
        accountId={accountId}
        setAccountId={setAccountId}
        rangeMode={rangeMode}
        setRangeMode={setRangeMode}
        year={year}
        setYear={setYear}
        month={month}
        setMonth={setMonth}
        customStart={customStart}
        setCustomStart={setCustomStart}
        customEnd={customEnd}
        setCustomEnd={setCustomEnd}
      />

      {s && (
        <SummaryCards
          summary={s}
          statementInput={statementInput}
          setStatementInput={setStatementInput}
          statementNotes={statementNotes}
          setStatementNotes={setStatementNotes}
          saveStatement={() => putStatement.mutate()}
          isSaving={putStatement.isPending}
          rangeMode={rangeMode}
          displayedDelta={displayedDelta}
        />
      )}

      {s && <ByKindBreakdown byKind={s.by_kind} />}

      <SplitGroupsTable groups={splits.data} />
      <TransactionsTable transactions={txs.data} />
    </div>
  );
}
