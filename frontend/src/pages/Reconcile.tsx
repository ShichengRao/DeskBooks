import { useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import clsx from "clsx";
import { api, qs } from "../api/client";
import type { CancelCandidate, CancelPair, SplitGroupSummary, Transaction } from "../api/types";
import { currency, dateLabel } from "../lib/fmt";
import { KindPill } from "../lib/kinds";

// The tab used to compare imported totals against bank-statement numbers;
// that never earned its keep. It now tracks the two flows where rows relate
// to each other: shared splits, and equal-and-opposite pairs that should
// cancel out of cashflow.

function dateInputValue(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function daysAgo(n: number) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return dateInputValue(d);
}

function RangeCard({
  start,
  end,
  setStart,
  setEnd,
}: {
  start: string;
  end: string;
  setStart: (value: string) => void;
  setEnd: (value: string) => void;
}) {
  const presets: { label: string; start: string; end: string }[] = [
    { label: "Last 3 months", start: daysAgo(90), end: dateInputValue(new Date()) },
    { label: "Last 12 months", start: daysAgo(365), end: dateInputValue(new Date()) },
    { label: "All time", start: "2000-01-01", end: dateInputValue(new Date()) },
  ];
  return (
    <div className="card p-4 flex flex-wrap items-end gap-3">
      <label className="block">
        <div className="label mb-1">From</div>
        <input type="date" className="input" value={start} onChange={(e) => setStart(e.target.value)} />
      </label>
      <label className="block">
        <div className="label mb-1">To</div>
        <input type="date" className="input" value={end} onChange={(e) => setEnd(e.target.value)} />
      </label>
      <div className="flex gap-2 pb-1">
        {presets.map((p) => (
          <button
            key={p.label}
            type="button"
            className={clsx(
              "btn-ghost text-xs",
              start === p.start && end === p.end && "bg-brand-100 text-brand-800",
            )}
            onClick={() => {
              setStart(p.start);
              setEnd(p.end);
            }}
          >
            {p.label}
          </button>
        ))}
      </div>
    </div>
  );
}

function SectionTable({
  title,
  subtitle,
  countLabel,
  headers,
  emptyColSpan,
  emptyText,
  isEmpty,
  children,
}: {
  title: string;
  subtitle?: string;
  countLabel: string;
  headers: ReactNode;
  emptyColSpan: number;
  emptyText: string;
  isEmpty: boolean;
  children: ReactNode;
}) {
  return (
    <div className="card overflow-hidden">
      <div className="px-3 py-2 bg-ink-50 text-sm flex items-baseline justify-between gap-3">
        <span>
          {title}
          {subtitle && <span className="ml-2 text-xs text-ink-500 font-normal">{subtitle}</span>}
        </span>
        <span className="text-ink-500 text-xs whitespace-nowrap">{countLabel}</span>
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
      title="Split groups"
      subtitle="shared charges and what's still owed back"
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

function TxCell({ tx }: { tx: Transaction }) {
  return (
    <td className="px-2 py-1.5">
      <div className="font-medium">{tx.merchant ?? tx.description_normalized ?? tx.description_raw}</div>
      <div className="text-xs text-ink-500 flex items-center gap-2">
        <span>{dateLabel(tx.date)}</span>
        <KindPill kind={tx.kind} />
        <span className={clsx("font-medium", Number(tx.amount) < 0 ? "text-bad-600" : "text-good-600")}>
          {currency(tx.amount)}
        </span>
      </div>
    </td>
  );
}

function CandidatesTable({
  candidates,
  onLink,
  linking,
}: {
  candidates: CancelCandidate[] | undefined;
  onLink: (candidate: CancelCandidate) => void;
  linking: boolean;
}) {
  return (
    <SectionTable
      title="Suggested cancel-outs"
      subtitle="equal-and-opposite rows that probably net to zero; linking marks both as transfers so they drop out of spending"
      countLabel={`${candidates?.length ?? 0} suggestions`}
      headers={
        <>
          <th className="px-2 py-1.5">One side</th>
          <th className="px-2 py-1.5">Other side</th>
          <th className="px-2 py-1.5 w-24 text-right">Amount</th>
          <th className="px-2 py-1.5 w-20 text-right">Gap</th>
          <th className="px-2 py-1.5 w-28"></th>
        </>
      }
      emptyColSpan={5}
      emptyText="No offsetting pairs found in this range."
      isEmpty={!candidates || candidates.length === 0}
    >
      {candidates?.map((c) => (
        <tr key={`${c.a.id}-${c.b.id}`} className="table-row-hover">
          <TxCell tx={c.a} />
          <TxCell tx={c.b} />
          <td className="px-2 py-1.5 text-right font-medium">{currency(Math.abs(Number(c.a.amount)))}</td>
          <td className="px-2 py-1.5 text-right text-ink-500">
            {c.gap_days === 0 ? "same day" : `${c.gap_days}d`}
          </td>
          <td className="px-2 py-1.5 text-right">
            <button type="button" className="btn text-xs" disabled={linking} onClick={() => onLink(c)}>
              Link pair
            </button>
          </td>
        </tr>
      ))}
    </SectionTable>
  );
}

function LinkedPairsTable({
  pairs,
  onUnlink,
  unlinking,
}: {
  pairs: CancelPair[] | undefined;
  onUnlink: (pair: CancelPair) => void;
  unlinking: boolean;
}) {
  return (
    <SectionTable
      title="Linked pairs"
      subtitle="already cancelling each other out"
      countLabel={`${pairs?.length ?? 0} pairs`}
      headers={
        <>
          <th className="px-2 py-1.5">One side</th>
          <th className="px-2 py-1.5">Other side</th>
          <th className="px-2 py-1.5 w-24 text-right">Amount</th>
          <th className="px-2 py-1.5 w-28"></th>
        </>
      }
      emptyColSpan={4}
      emptyText="No linked pairs in this range."
      isEmpty={!pairs || pairs.length === 0}
    >
      {pairs?.map((p) => (
        <tr key={`${p.a.id}-${p.b.id}`} className="table-row-hover">
          <TxCell tx={p.a} />
          <TxCell tx={p.b} />
          <td className="px-2 py-1.5 text-right font-medium">{currency(Math.abs(Number(p.a.amount)))}</td>
          <td className="px-2 py-1.5 text-right">
            <button
              type="button"
              className="btn-ghost text-xs text-bad-600"
              disabled={unlinking}
              onClick={() => onUnlink(p)}
            >
              Unlink
            </button>
          </td>
        </tr>
      ))}
    </SectionTable>
  );
}

export function Reconcile() {
  const qc = useQueryClient();
  const [start, setStart] = useState<string>(daysAgo(90));
  const [end, setEnd] = useState<string>(dateInputValue(new Date()));
  const canQuery = !!start && !!end && end >= start;

  const splits = useQuery({
    queryKey: ["split-summary", start, end],
    queryFn: () => api.get<SplitGroupSummary[]>("/api/analytics/splits" + qs({ start, end })),
    enabled: canQuery,
  });
  const candidates = useQuery({
    queryKey: ["cancel-candidates", start, end],
    queryFn: () =>
      api.get<CancelCandidate[]>("/api/analytics/cancel-candidates" + qs({ start, end })),
    enabled: canQuery,
  });
  const pairs = useQuery({
    queryKey: ["cancel-pairs", start, end],
    queryFn: () => api.get<CancelPair[]>("/api/analytics/cancel-pairs" + qs({ start, end })),
    enabled: canQuery,
  });

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["cancel-candidates"] });
    qc.invalidateQueries({ queryKey: ["cancel-pairs"] });
  };
  const link = useMutation({
    mutationFn: (c: CancelCandidate) =>
      api.post("/api/transactions/pair", { transaction_a_id: c.a.id, transaction_b_id: c.b.id }),
    onSuccess: refresh,
  });
  const unlink = useMutation({
    mutationFn: (p: CancelPair) => api.post(`/api/transactions/${p.a.id}/unpair`, {}),
    onSuccess: refresh,
  });

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Splits & netting</h1>
        <div className="text-sm text-ink-500">
          Track shared expenses, and net out refunds and reversals.
        </div>
      </div>

      <RangeCard start={start} end={end} setStart={setStart} setEnd={setEnd} />

      <SplitGroupsTable groups={splits.data} />
      <CandidatesTable candidates={candidates.data} onLink={(c) => link.mutate(c)} linking={link.isPending} />
      <LinkedPairsTable pairs={pairs.data} onUnlink={(p) => unlink.mutate(p)} unlinking={unlink.isPending} />
    </div>
  );
}
