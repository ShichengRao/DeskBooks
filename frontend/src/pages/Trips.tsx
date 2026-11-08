import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import clsx from "clsx";
import { api, qs } from "../api/client";
import { Field } from "../components/Field";
import { SidePanel } from "../components/SidePanel";
import { currency, dateLabel, num } from "../lib/fmt";
import type {
  Category,
  Trip,
  TripCandidateBucketName,
  TripCandidateGroup,
  TripCandidates,
  TripCategoryPolicy,
  TripCategoryRule,
  TripDetail,
  TripLeg,
  TripSummary,
} from "../api/types";

// Trips name a journey and hold the spending that belongs to it.
//
// The membership is an explicit set rather than a stored date filter,
// because a window is wrong in both directions: rent, insurance and
// subscriptions keep billing while you are away, and the flights were
// bought months before you left. So the dates only ever *propose*, this
// page explains each proposal, and you confirm. The two judgements that
// no arithmetic can make — which categories are home life, which get
// booked ahead — are answered once per category and reused by every
// later trip, which is what keeps the second trip to a few clicks.

const BUCKETS: {
  name: TripCandidateBucketName;
  title: string;
  blurb: string;
  preselected: boolean;
}[] = [
  {
    name: "pre_booking",
    title: "Booked ahead",
    blurb: "found before the trip started, in categories you book in advance",
    preselected: true,
  },
  {
    name: "proposed",
    title: "During the trip",
    blurb: "spending inside the dates",
    preselected: true,
  },
  {
    name: "standing_charge",
    title: "Standing charges",
    blurb: "billed on a schedule, so they would have billed at home too",
    preselected: false,
  },
  {
    name: "home_life",
    title: "Home life",
    blurb: "categories you keep out of trips",
    preselected: false,
  },
];

const POLICY_CHOICES: { value: TripCategoryRule; label: string; hint: string }[] = [
  { value: "include", label: "Counts", hint: "Propose it when it falls inside a trip" },
  { value: "advance", label: "Booked ahead", hint: "Also look for it in the months before a trip" },
  { value: "skip", label: "Keep out", hint: "Never propose it; it bills whether you travel or not" },
];

function today() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function nightsBetween(start: string, end: string) {
  const a = new Date(start).getTime();
  const b = new Date(end).getTime();
  if (Number.isNaN(a) || Number.isNaN(b)) return 0;
  return Math.max(0, Math.round((b - a) / 86_400_000));
}

function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div>
      <div className="label mb-0.5">{label}</div>
      <div className="text-xl font-semibold tabular">{value}</div>
      {hint && <div className="text-xs text-ink-500">{hint}</div>}
    </div>
  );
}

function dateSpan(start: string, end: string) {
  return `${dateLabel(start)} – ${dateLabel(end)}`;
}

// ---------- list ----------

function TripList({
  trips,
  onOpen,
  onNew,
}: {
  trips: TripSummary[] | undefined;
  onOpen: (id: number) => void;
  onNew: () => void;
}) {
  return (
    <div className="card overflow-hidden">
      <div className="flex items-baseline justify-between gap-3 bg-ink-50 px-3 py-2 text-sm">
        <span>Trips</span>
        <span className="text-xs text-ink-500">{trips?.length ?? 0} trips</span>
      </div>
      <div className="overflow-x-auto">
        <table className="tabular w-full min-w-[40rem] text-sm">
          <thead className="bg-ink-50 text-left">
            <tr>
              <th className="px-2 py-1.5">Trip</th>
              <th className="px-2 py-1.5">Dates</th>
              <th className="px-2 py-1.5 text-right">Nights</th>
              <th className="px-2 py-1.5 text-right">Total</th>
              <th className="px-2 py-1.5 text-right">Per night</th>
              <th className="px-2 py-1.5 text-right">Rows</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {trips?.map((summary) => (
              <tr
                key={summary.trip.id}
                className="table-row-hover cursor-pointer"
                onClick={() => onOpen(summary.trip.id)}
              >
                <td className="px-2 py-1.5">
                  <div className="font-medium text-brand-700">{summary.trip.name}</div>
                  {summary.trip.legs.length > 0 && (
                    <div className="text-xs text-ink-500">
                      {summary.trip.legs.map((leg) => leg.place).join(" → ")}
                    </div>
                  )}
                </td>
                <td className="px-2 py-1.5 text-ink-600">
                  {dateSpan(summary.trip.start_date, summary.trip.end_date)}
                </td>
                <td className="px-2 py-1.5 text-right">{summary.nights}</td>
                <td className="px-2 py-1.5 text-right font-semibold">{currency(summary.total)}</td>
                <td className="px-2 py-1.5 text-right text-ink-600">
                  {summary.per_night === null ? "—" : currency(summary.per_night)}
                </td>
                <td className="px-2 py-1.5 text-right text-ink-500">
                  {summary.expense_count + summary.item_count}
                </td>
              </tr>
            ))}
            {(!trips || trips.length === 0) && (
              <tr>
                <td colSpan={6} className="p-8 text-center text-ink-500">
                  <div className="italic">No trips yet.</div>
                  <button type="button" className="btn-primary mt-3" onClick={onNew}>
                    Plan your first trip
                  </button>
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ---------- detail ----------

function Itinerary({
  detail,
  onAddLeg,
  onEditLeg,
}: {
  detail: TripDetail;
  onAddLeg: () => void;
  onEditLeg: (leg: TripLeg) => void;
}) {
  const legs = detail.report.legs;
  const unassigned = num(detail.report.unassigned_total);
  return (
    <div className="card p-4">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <div className="text-sm">
          Stops
          <span className="ml-2 text-xs font-normal text-ink-500">
            where you were, and what it cost there
          </span>
        </div>
        <button type="button" className="btn text-xs" onClick={onAddLeg}>
          Add stop
        </button>
      </div>
      {legs.length === 0 ? (
        <div className="text-sm text-ink-500">
          No stops yet. A trip works without them — totals just group by category instead of by
          place. Add stops to break the same total down per city.
        </div>
      ) : (
        <div className="flex flex-wrap gap-2">
          {legs.map((leg) => (
            <button
              key={leg.id}
              type="button"
              className="min-w-[9rem] flex-1 rounded-md border border-ink-200 p-3 text-left hover:bg-ink-50"
              onClick={() => {
                const match = detail.trip.legs.find((l) => l.id === leg.id);
                if (match) onEditLeg(match);
              }}
            >
              <div className="font-medium">{leg.place}</div>
              <div className="text-xs text-ink-500">
                {dateSpan(leg.start_date, leg.end_date)} · {leg.nights}n
              </div>
              <div className="tabular mt-1 font-semibold">{currency(leg.total)}</div>
              <div className="tabular text-xs text-ink-500">
                {leg.per_night === null ? "—" : `${currency(leg.per_night)}/night`}
              </div>
            </button>
          ))}
          {unassigned > 0 && (
            <div className="min-w-[9rem] flex-1 rounded-md border border-dashed border-ink-200 p-3">
              <div className="font-medium text-ink-600">No stop</div>
              <div className="text-xs text-ink-500">travel days and things booked ahead</div>
              <div className="tabular mt-1 font-semibold">{currency(unassigned)}</div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function CostMatrix({ detail }: { detail: TripDetail }) {
  const { categories, legs, unassigned_total, total } = detail.report;
  const hasLegs = legs.length > 0;
  const hasUnassigned = num(unassigned_total) !== 0;
  if (categories.length === 0) {
    return (
      <div className="card p-8 text-center text-sm text-ink-500">
        Nothing counted yet. <span className="italic">Review spending</span> proposes what the
        dates turned up.
      </div>
    );
  }
  return (
    <div className="card overflow-hidden">
      <div className="bg-ink-50 px-3 py-2 text-sm">
        What it cost
        <span className="ml-2 text-xs font-normal text-ink-500">
          {hasLegs ? "by category and stop" : "by category"}
        </span>
      </div>
      <div className="overflow-x-auto">
        <table className="tabular w-full min-w-[32rem] text-sm">
          <thead className="bg-ink-50 text-left">
            <tr>
              <th className="px-2 py-1.5">Category</th>
              {legs.map((leg) => (
                <th key={leg.id} className="px-2 py-1.5 text-right">
                  {leg.place}
                  <div className="text-xs font-normal text-ink-500">{leg.nights}n</div>
                </th>
              ))}
              {hasUnassigned && <th className="px-2 py-1.5 text-right">No stop</th>}
              <th className="px-2 py-1.5 text-right">Total</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {categories.map((row) => (
              <tr key={row.category_id ?? "none"} className="table-row-hover">
                <td className="px-2 py-1.5">{row.category_name}</td>
                {legs.map((leg) => {
                  const cell = num(row.by_leg[String(leg.id)]);
                  return (
                    <td key={leg.id} className="px-2 py-1.5 text-right">
                      {cell === 0 ? <span className="text-ink-300">—</span> : currency(cell)}
                    </td>
                  );
                })}
                {hasUnassigned && (
                  <td className="px-2 py-1.5 text-right">
                    {num(row.unassigned) === 0 ? (
                      <span className="text-ink-300">—</span>
                    ) : (
                      currency(row.unassigned)
                    )}
                  </td>
                )}
                <td className="px-2 py-1.5 text-right font-semibold">{currency(row.total)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot className="border-t-2 border-ink-200 bg-ink-50">
            <tr>
              <td className="px-2 py-2 font-medium">Total</td>
              {legs.map((leg) => (
                <td key={leg.id} className="px-2 py-2 text-right font-semibold">
                  {currency(leg.total)}
                </td>
              ))}
              {hasUnassigned && (
                <td className="px-2 py-2 text-right font-semibold">{currency(unassigned_total)}</td>
              )}
              <td className="px-2 py-2 text-right font-semibold">{currency(total)}</td>
            </tr>
            <tr className="text-xs text-ink-500">
              <td className="px-2 pb-2">Per night</td>
              {legs.map((leg) => (
                <td key={leg.id} className="px-2 pb-2 text-right">
                  {leg.per_night === null ? "—" : currency(leg.per_night)}
                </td>
              ))}
              {hasUnassigned && <td className="px-2 pb-2" />}
              <td className="px-2 pb-2 text-right">
                {detail.report.per_night === null ? "—" : currency(detail.report.per_night)}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
  );
}

function RowsTable({
  detail,
  onSetShare,
  onSetLeg,
  onRemove,
  onEditItem,
  busy,
}: {
  detail: TripDetail;
  onSetShare: (transactionId: number, share: number, legId: number | null) => void;
  onSetLeg: (transactionId: number, legId: number | null, share: number) => void;
  onRemove: (transactionId: number) => void;
  onEditItem: (itemId: number) => void;
  busy: boolean;
}) {
  const legs = detail.trip.legs;
  return (
    <div className="card overflow-hidden">
      <div className="flex items-baseline justify-between gap-3 bg-ink-50 px-3 py-2 text-sm">
        <span>
          Everything counted
          <span className="ml-2 text-xs font-normal text-ink-500">
            the rows behind the total, so the number is checkable
          </span>
        </span>
        <span className="whitespace-nowrap text-xs text-ink-500">
          {detail.report.expense_count} from the ledger
          {detail.report.item_count > 0 && ` · ${detail.report.item_count} added by hand`}
        </span>
      </div>
      <div className="overflow-x-auto">
        <table className="tabular w-full min-w-[44rem] text-sm">
          <thead className="bg-ink-50 text-left">
            <tr>
              <th className="px-2 py-1.5">Date</th>
              <th className="px-2 py-1.5">What</th>
              <th className="px-2 py-1.5">Category</th>
              <th className="px-2 py-1.5">Stop</th>
              <th className="px-2 py-1.5 w-24 text-right">Share</th>
              <th className="px-2 py-1.5 text-right">Counted</th>
              <th className="px-2 py-1.5 w-20" />
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {detail.report.rows.map((row) => {
              const key = `${row.kind}-${row.transaction_id ?? row.item_id}`;
              const share = row.share === null ? 1 : num(row.share);
              return (
                <tr key={key} className="table-row-hover">
                  <td className="px-2 py-1.5 whitespace-nowrap text-ink-600">
                    {row.date ? dateLabel(row.date) : "—"}
                  </td>
                  <td className="px-2 py-1.5">
                    <span className="font-medium">{row.label}</span>
                    {row.kind === "item" && (
                      <span className="pill ml-2 bg-ink-100 text-ink-600">by hand</span>
                    )}
                    {row.personal_share !== null && (
                      <span
                        className="pill ml-2 bg-warn-500/10 text-warn-600"
                        title="Already shared with a split group; the trip counts your part of your part."
                      >
                        split {Math.round(num(row.personal_share) * 100)}%
                      </span>
                    )}
                  </td>
                  <td className="px-2 py-1.5 text-ink-600">{row.category_name ?? "—"}</td>
                  <td className="px-2 py-1.5">
                    {row.kind === "item" ? (
                      <span className="text-ink-500">
                        {legs.find((l) => l.id === row.leg_id)?.place ?? "—"}
                      </span>
                    ) : (
                      <select
                        className="input py-0.5 text-xs"
                        value={row.leg_id ?? ""}
                        disabled={busy || legs.length === 0}
                        onChange={(e) =>
                          onSetLeg(
                            row.transaction_id as number,
                            e.target.value ? parseInt(e.target.value, 10) : null,
                            share,
                          )
                        }
                      >
                        <option value="">No stop</option>
                        {legs.map((leg) => (
                          <option key={leg.id} value={leg.id}>
                            {leg.place}
                          </option>
                        ))}
                      </select>
                    )}
                  </td>
                  <td className="px-2 py-1.5 text-right">
                    {row.kind === "item" ? (
                      <span className="text-ink-300">—</span>
                    ) : (
                      <input
                        type="number"
                        className="input py-0.5 text-right text-xs"
                        min={1}
                        max={100}
                        step={1}
                        defaultValue={Math.round(share * 100)}
                        disabled={busy}
                        title="How much of this charge was the trip's — a quarter of a drugstore order is 25."
                        onBlur={(e) => {
                          const pct = parseInt(e.target.value, 10);
                          if (!Number.isFinite(pct)) return;
                          const next = Math.min(100, Math.max(1, pct)) / 100;
                          if (Math.abs(next - share) < 0.0001) return;
                          onSetShare(row.transaction_id as number, next, row.leg_id);
                        }}
                      />
                    )}
                  </td>
                  <td className="px-2 py-1.5 text-right font-semibold">{currency(row.spend)}</td>
                  <td className="px-2 py-1.5 text-right">
                    {row.kind === "item" ? (
                      <button
                        type="button"
                        className="btn-ghost text-xs"
                        onClick={() => onEditItem(row.item_id as number)}
                      >
                        Edit
                      </button>
                    ) : (
                      <button
                        type="button"
                        className="btn-ghost text-xs text-bad-600"
                        disabled={busy}
                        onClick={() => onRemove(row.transaction_id as number)}
                      >
                        Remove
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
            {detail.report.rows.length === 0 && (
              <tr>
                <td colSpan={7} className="p-6 text-center italic text-ink-500">
                  Nothing counted toward this trip yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ---------- candidate review ----------

function PolicyTriage({
  candidates,
  onSet,
  busy,
}: {
  candidates: TripCandidates;
  onSet: (categoryId: number, policy: TripCategoryRule) => void;
  busy: boolean;
}) {
  if (candidates.undecided_categories.length === 0) return null;
  return (
    <div className="mb-4 rounded-md border border-brand-200 bg-brand-50 p-3">
      <div className="text-sm font-medium">Decide these once</div>
      <div className="mb-3 text-xs text-ink-600">
        Answered per category and remembered, so later trips skip straight to the list. Something
        charged in every month of your data is almost always home life.
      </div>
      <div className="space-y-1.5">
        {candidates.undecided_categories.map((row) => (
          <div
            key={row.category_id}
            className="flex flex-wrap items-center justify-between gap-2 rounded border border-ink-200 bg-white px-2 py-1.5"
          >
            <div className="min-w-0">
              <div className="truncate text-sm font-medium">{row.category_name}</div>
              <div className="text-xs text-ink-500">
                charged in {row.months_charged} of {row.months_observed} months
              </div>
            </div>
            <div className="flex shrink-0 gap-1">
              {POLICY_CHOICES.map((choice) => (
                <button
                  key={choice.value}
                  type="button"
                  className="btn text-xs"
                  disabled={busy}
                  title={choice.hint}
                  onClick={() => onSet(row.category_id, choice.value)}
                >
                  {choice.label}
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function CandidateBucket({
  bucket,
  groups,
  selected,
  onToggle,
  onToggleAll,
}: {
  bucket: (typeof BUCKETS)[number];
  groups: TripCandidateGroup[];
  selected: Set<string>;
  onToggle: (key: string) => void;
  onToggleAll: (keys: string[], on: boolean) => void;
}) {
  if (groups.length === 0) return null;
  const keys = groups.map((g) => g.key);
  const allOn = keys.every((k) => selected.has(k));
  const bucketTotal = groups.reduce((acc, g) => acc + num(g.total), 0);
  return (
    <div className="mb-4">
      <div className="mb-1.5 flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <span className="text-sm font-medium">{bucket.title}</span>
          <span className="ml-2 text-xs text-ink-500">{bucket.blurb}</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="tabular text-xs text-ink-500">{currency(bucketTotal)}</span>
          <button
            type="button"
            className="btn-ghost text-xs"
            onClick={() => onToggleAll(keys, !allOn)}
          >
            {allOn ? "None" : "All"}
          </button>
        </div>
      </div>
      <div className="divide-y divide-ink-100 rounded-md border border-ink-200">
        {groups.map((group) => {
          const on = selected.has(group.key);
          return (
            <label
              key={group.key}
              className={clsx(
                "flex cursor-pointer items-center gap-3 px-2 py-1.5 text-sm hover:bg-ink-50",
                !on && "text-ink-400",
              )}
            >
              <input
                type="checkbox"
                checked={on}
                onChange={() => onToggle(group.key)}
                className="shrink-0"
              />
              <span className={clsx("min-w-0 flex-1", on && "text-ink-900")}>
                <span className="font-medium">{group.merchant}</span>
                {group.occurrences > 1 && (
                  <span className="ml-1.5 text-xs text-ink-500">×{group.occurrences}</span>
                )}
                <span className="block truncate text-xs text-ink-500">
                  {group.category_name ?? "Uncategorized"} ·{" "}
                  {group.first_date === group.last_date
                    ? dateLabel(group.first_date)
                    : dateSpan(group.first_date, group.last_date)}
                </span>
              </span>
              <span className="tabular shrink-0 font-semibold">{currency(group.total)}</span>
            </label>
          );
        })}
      </div>
    </div>
  );
}

function ReviewPanel({
  candidates,
  isLoading,
  lookbackDays,
  onLookback,
  onPolicy,
  onConfirm,
  onClose,
  busy,
}: {
  candidates: TripCandidates | undefined;
  isLoading: boolean;
  lookbackDays: number;
  onLookback: (days: number) => void;
  onPolicy: (categoryId: number, policy: TripCategoryRule) => void;
  onConfirm: (transactionIds: number[]) => void;
  onClose: () => void;
  busy: boolean;
}) {
  const [selected, setSelected] = useState<Set<string>>(new Set());

  // Re-seed whenever the proposal changes — a policy answer reshuffles
  // the buckets, and stale ticks would be ticks on rows that moved.
  const seed = candidates?.groups.map((g) => g.key).join("|") ?? "";
  useEffect(() => {
    if (!candidates) return;
    const preselected = new Set(
      BUCKETS.filter((b) => b.preselected).map((b) => b.name as string),
    );
    setSelected(
      new Set(candidates.groups.filter((g) => preselected.has(g.bucket)).map((g) => g.key)),
    );
  }, [seed, candidates]);

  const byBucket = useMemo(() => {
    const out = new Map<TripCandidateBucketName, TripCandidateGroup[]>();
    for (const group of candidates?.groups ?? []) {
      const list = out.get(group.bucket) ?? [];
      list.push(group);
      out.set(group.bucket, list);
    }
    return out;
  }, [candidates]);

  const chosen = (candidates?.groups ?? []).filter((g) => selected.has(g.key));
  const chosenTotal = chosen.reduce((acc, g) => acc + num(g.total), 0);
  const chosenIds = chosen.flatMap((g) => g.transactions.map((t) => t.id));

  const toggle = (key: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  const toggleAll = (keys: string[], on: boolean) =>
    setSelected((prev) => {
      const next = new Set(prev);
      for (const key of keys) {
        if (on) next.add(key);
        else next.delete(key);
      }
      return next;
    });

  return (
    <SidePanel title="Review spending" onClose={onClose} maxWidth="max-w-3xl">
      <div className="mb-4 flex flex-wrap items-end gap-3 rounded-md bg-ink-50 p-3">
        <label className="block">
          <div className="label mb-1">Look back before the trip</div>
          <select
            className="input"
            value={lookbackDays}
            onChange={(e) => onLookback(parseInt(e.target.value, 10))}
          >
            <option value={0}>Not at all</option>
            <option value={60}>2 months</option>
            <option value={180}>6 months</option>
            <option value={365}>A year</option>
          </select>
        </label>
        <div className="flex-1 text-xs text-ink-600">
          Only categories you marked <span className="font-medium">booked ahead</span> are searched
          before the trip — otherwise a whole season of ordinary spending would turn up.
        </div>
      </div>

      {isLoading && <div className="py-8 text-center text-sm text-ink-500">Reading the ledger…</div>}

      {candidates && (
        <>
          <PolicyTriage candidates={candidates} onSet={onPolicy} busy={busy} />
          {candidates.groups.length === 0 ? (
            <div className="py-8 text-center text-sm text-ink-500">
              Nothing left to propose. Every spending row in these dates is either counted already
              or in a category you keep out of trips.
            </div>
          ) : (
            BUCKETS.map((bucket) => (
              <CandidateBucket
                key={bucket.name}
                bucket={bucket}
                groups={byBucket.get(bucket.name) ?? []}
                selected={selected}
                onToggle={toggle}
                onToggleAll={toggleAll}
              />
            ))
          )}
        </>
      )}

      <div className="sticky bottom-0 z-10 -mx-4 mt-4 flex flex-wrap items-center gap-3 border-t border-ink-100 bg-white px-4 py-3 sm:-mx-6 sm:px-6">
        <button
          type="button"
          className="btn-primary"
          disabled={busy || chosenIds.length === 0}
          onClick={() => onConfirm(chosenIds)}
        >
          Count {chosenIds.length} {chosenIds.length === 1 ? "row" : "rows"}
        </button>
        <span className="tabular text-sm font-semibold">{currency(chosenTotal)}</span>
        <button type="button" className="btn" onClick={onClose}>
          Cancel
        </button>
      </div>
    </SidePanel>
  );
}

// ---------- editors ----------

function TripEditor({
  trip,
  onClose,
  onSubmit,
  busy,
}: {
  trip: Trip | null;
  onClose: () => void;
  onSubmit: (body: { name: string; start_date: string; end_date: string; notes: string | null }) => void;
  busy: boolean;
}) {
  const [name, setName] = useState(trip?.name ?? "");
  const [start, setStart] = useState(trip?.start_date ?? today());
  const [end, setEnd] = useState(trip?.end_date ?? today());
  const [notes, setNotes] = useState(trip?.notes ?? "");
  const valid = name.trim() !== "" && !!start && !!end && end >= start;

  return (
    <SidePanel
      title={trip ? "Edit trip" : "New trip"}
      onClose={onClose}
      onSubmit={() =>
        onSubmit({
          name: name.trim(),
          start_date: start,
          end_date: end,
          notes: notes.trim() || null,
        })
      }
      maxWidth="max-w-md"
    >
      <div className="space-y-3">
        <Field label="Name">
          <input
            className="input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="West Coast"
            autoFocus
            required
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Leaves">
            <input
              type="date"
              className="input"
              value={start}
              onChange={(e) => setStart(e.target.value)}
              required
            />
          </Field>
          <Field label="Back">
            <input
              type="date"
              className="input"
              value={end}
              onChange={(e) => setEnd(e.target.value)}
              required
            />
          </Field>
        </div>
        {start && end && end >= start && (
          <div className="text-xs text-ink-500">{nightsBetween(start, end)} nights</div>
        )}
        {start && end && end < start && (
          <div className="text-xs text-bad-600">The trip ends before it starts.</div>
        )}
        <Field label="Notes">
          <textarea
            className="input h-20"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Who you stayed with, what the trip was for."
          />
        </Field>
      </div>
      <div className="sticky bottom-0 z-10 -mx-4 mt-4 flex flex-wrap items-center gap-2 border-t border-ink-100 bg-white px-4 py-3 sm:-mx-6 sm:px-6">
        <button type="submit" className="btn-primary" disabled={busy || !valid}>
          {trip ? "Save trip" : "Create trip"}
        </button>
        <button type="button" className="btn" onClick={onClose}>
          Cancel
        </button>
      </div>
    </SidePanel>
  );
}

function LegEditor({
  leg,
  trip,
  onClose,
  onSubmit,
  onDelete,
  busy,
}: {
  leg: TripLeg | null;
  trip: Trip;
  onClose: () => void;
  onSubmit: (body: { place: string; start_date: string; end_date: string }) => void;
  onDelete: (() => void) | null;
  busy: boolean;
}) {
  const [place, setPlace] = useState(leg?.place ?? "");
  const [start, setStart] = useState(leg?.start_date ?? trip.start_date);
  const [end, setEnd] = useState(leg?.end_date ?? trip.end_date);
  const valid = place.trim() !== "" && !!start && !!end && end >= start;

  return (
    <SidePanel
      title={leg ? `Edit ${leg.place}` : "Add stop"}
      onClose={onClose}
      onSubmit={() => onSubmit({ place: place.trim(), start_date: start, end_date: end })}
      maxWidth="max-w-md"
    >
      <div className="space-y-3">
        <Field label="Place">
          <input
            className="input"
            value={place}
            onChange={(e) => setPlace(e.target.value)}
            placeholder="Seattle"
            autoFocus
            required
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Arrive">
            <input
              type="date"
              className="input"
              value={start}
              onChange={(e) => setStart(e.target.value)}
              required
            />
          </Field>
          <Field label="Leave">
            <input
              type="date"
              className="input"
              value={end}
              onChange={(e) => setEnd(e.target.value)}
              required
            />
          </Field>
        </div>
        <div className="text-xs text-ink-500">
          Spending inside these dates is filed here. Stops need not join up — a travel day between
          two stays belongs to neither, and stays on the trip without a stop.
        </div>
      </div>
      <div className="sticky bottom-0 z-10 -mx-4 mt-4 flex flex-wrap items-center gap-2 border-t border-ink-100 bg-white px-4 py-3 sm:-mx-6 sm:px-6">
        <button type="submit" className="btn-primary" disabled={busy || !valid}>
          {leg ? "Save stop" : "Add stop"}
        </button>
        <button type="button" className="btn" onClick={onClose}>
          Cancel
        </button>
        {onDelete && (
          <button
            type="button"
            className="btn-ghost ml-auto text-xs text-bad-600"
            disabled={busy}
            onClick={onDelete}
          >
            Delete stop
          </button>
        )}
      </div>
    </SidePanel>
  );
}

function ItemEditor({
  item,
  trip,
  categories,
  onClose,
  onSubmit,
  onDelete,
  busy,
}: {
  item: { id: number; label: string; amount: string; date: string | null; leg_id: number | null; category_id: number | null } | null;
  trip: Trip;
  categories: Category[];
  onClose: () => void;
  onSubmit: (body: {
    label: string;
    amount: string;
    date: string | null;
    leg_id: number | null;
    category_id: number | null;
  }) => void;
  onDelete: (() => void) | null;
  busy: boolean;
}) {
  const [label, setLabel] = useState(item?.label ?? "");
  const [amount, setAmount] = useState(item ? String(num(item.amount)) : "");
  const [when, setWhen] = useState(item?.date ?? trip.start_date);
  const [legId, setLegId] = useState<string>(item?.leg_id ? String(item.leg_id) : "");
  const [categoryId, setCategoryId] = useState<string>(
    item?.category_id ? String(item.category_id) : "",
  );
  const parsed = parseFloat(amount);
  const valid = label.trim() !== "" && Number.isFinite(parsed) && parsed > 0;

  return (
    <SidePanel
      title={item ? "Edit cost" : "Add a cost"}
      onClose={onClose}
      onSubmit={() =>
        onSubmit({
          label: label.trim(),
          amount: parsed.toFixed(2),
          date: when || null,
          leg_id: legId ? parseInt(legId, 10) : null,
          category_id: categoryId ? parseInt(categoryId, 10) : null,
        })
      }
      maxWidth="max-w-md"
    >
      <div className="space-y-3">
        <div className="rounded-md bg-ink-50 p-3 text-xs text-ink-600">
          For spending the ledger never saw — cash, a fare paid in coins, a friend's card. Without
          these the trip total cannot match the one you work out by hand.
        </div>
        <Field label="What">
          <input
            className="input"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="Bus fares"
            autoFocus
            required
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Amount">
            <input
              type="number"
              className="input"
              min="0.01"
              step="0.01"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="13.50"
              required
            />
          </Field>
          <Field label="Date">
            <input
              type="date"
              className="input"
              value={when ?? ""}
              onChange={(e) => setWhen(e.target.value)}
            />
          </Field>
        </div>
        <Field label="Category">
          <select
            className="input"
            value={categoryId}
            onChange={(e) => setCategoryId(e.target.value)}
          >
            <option value="">No category</option>
            {categories
              .filter((c) => !c.archived)
              .map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
          </select>
        </Field>
        {trip.legs.length > 0 && (
          <Field label="Stop">
            <select className="input" value={legId} onChange={(e) => setLegId(e.target.value)}>
              <option value="">Work it out from the date</option>
              {trip.legs.map((leg) => (
                <option key={leg.id} value={leg.id}>
                  {leg.place}
                </option>
              ))}
            </select>
          </Field>
        )}
      </div>
      <div className="sticky bottom-0 z-10 -mx-4 mt-4 flex flex-wrap items-center gap-2 border-t border-ink-100 bg-white px-4 py-3 sm:-mx-6 sm:px-6">
        <button type="submit" className="btn-primary" disabled={busy || !valid}>
          {item ? "Save cost" : "Add cost"}
        </button>
        <button type="button" className="btn" onClick={onClose}>
          Cancel
        </button>
        {onDelete && (
          <button
            type="button"
            className="btn-ghost ml-auto text-xs text-bad-600"
            disabled={busy}
            onClick={onDelete}
          >
            Delete cost
          </button>
        )}
      </div>
    </SidePanel>
  );
}

// ---------- page ----------

type PanelState =
  | { kind: "none" }
  | { kind: "trip"; trip: Trip | null }
  | { kind: "leg"; leg: TripLeg | null }
  | { kind: "item"; itemId: number | null }
  | { kind: "review" };

export function Trips() {
  const qc = useQueryClient();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [panel, setPanel] = useState<PanelState>({ kind: "none" });
  const [lookbackDays, setLookbackDays] = useState(180);

  const trips = useQuery({
    queryKey: ["trips"],
    queryFn: () => api.get<TripSummary[]>("/api/trips"),
  });
  const detail = useQuery({
    queryKey: ["trip", selectedId],
    queryFn: () => api.get<TripDetail>(`/api/trips/${selectedId}`),
    enabled: selectedId !== null,
  });
  const candidates = useQuery({
    queryKey: ["trip-candidates", selectedId, lookbackDays],
    queryFn: () =>
      api.get<TripCandidates>(
        `/api/trips/${selectedId}/candidates` + qs({ lookback_days: lookbackDays }),
      ),
    enabled: selectedId !== null && panel.kind === "review",
  });
  const categories = useQuery({
    queryKey: ["categories"],
    queryFn: () => api.get<Category[]>("/api/categories"),
  });

  const surfaceError = (error: Error) => alert(error.message);
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["trips"] });
    qc.invalidateQueries({ queryKey: ["trip", selectedId] });
    qc.invalidateQueries({ queryKey: ["trip-candidates"] });
  };
  const closePanel = () => setPanel({ kind: "none" });

  const createTrip = useMutation({
    mutationFn: (body: unknown) => api.post<TripDetail>("/api/trips", body),
    onSuccess: (data) => {
      setSelectedId(data.trip.id);
      closePanel();
      refresh();
      // A fresh trip has nothing in it; go straight to the proposal.
      setPanel({ kind: "review" });
    },
    onError: surfaceError,
  });
  const patchTrip = useMutation({
    mutationFn: (body: unknown) => api.patch<TripDetail>(`/api/trips/${selectedId}`, body),
    onSuccess: () => {
      closePanel();
      refresh();
    },
    onError: surfaceError,
  });
  const removeTrip = useMutation({
    mutationFn: (id: number) => api.del(`/api/trips/${id}`),
    onSuccess: () => {
      setSelectedId(null);
      refresh();
    },
    onError: surfaceError,
  });
  const setExpenses = useMutation({
    mutationFn: (body: unknown) => api.put<TripDetail>(`/api/trips/${selectedId}/expenses`, body),
    onSuccess: () => refresh(),
    onError: surfaceError,
  });
  const addLeg = useMutation({
    mutationFn: (body: unknown) => api.post<TripDetail>(`/api/trips/${selectedId}/legs`, body),
    onSuccess: () => {
      closePanel();
      refresh();
    },
    onError: surfaceError,
  });
  const patchLeg = useMutation({
    mutationFn: ({ id, body }: { id: number; body: unknown }) =>
      api.patch<TripDetail>(`/api/trips/${selectedId}/legs/${id}`, body),
    onSuccess: () => {
      closePanel();
      refresh();
    },
    onError: surfaceError,
  });
  const removeLeg = useMutation({
    mutationFn: (id: number) => api.del(`/api/trips/${selectedId}/legs/${id}`),
    onSuccess: () => {
      closePanel();
      refresh();
    },
    onError: surfaceError,
  });
  const addItem = useMutation({
    mutationFn: (body: unknown) => api.post<TripDetail>(`/api/trips/${selectedId}/items`, body),
    onSuccess: () => {
      closePanel();
      refresh();
    },
    onError: surfaceError,
  });
  const patchItem = useMutation({
    mutationFn: ({ id, body }: { id: number; body: unknown }) =>
      api.patch<TripDetail>(`/api/trips/${selectedId}/items/${id}`, body),
    onSuccess: () => {
      closePanel();
      refresh();
    },
    onError: surfaceError,
  });
  const removeItem = useMutation({
    mutationFn: (id: number) => api.del(`/api/trips/${selectedId}/items/${id}`),
    onSuccess: () => {
      closePanel();
      refresh();
    },
    onError: surfaceError,
  });
  const setPolicy = useMutation({
    mutationFn: (body: unknown) => api.put<TripCategoryPolicy[]>("/api/trips/policies", body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["trip-candidates"] }),
    onError: surfaceError,
  });

  const busy =
    createTrip.isPending ||
    patchTrip.isPending ||
    setExpenses.isPending ||
    addLeg.isPending ||
    patchLeg.isPending ||
    removeLeg.isPending ||
    addItem.isPending ||
    patchItem.isPending ||
    removeItem.isPending ||
    setPolicy.isPending;

  const current = detail.data;
  const editingItem =
    panel.kind === "item" && panel.itemId !== null
      ? current?.items.find((i) => i.id === panel.itemId) ?? null
      : null;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-2">
        <h1 className="text-2xl font-semibold tracking-tight">Trips</h1>
        <div className="text-sm text-ink-500">
          Name a journey, then confirm what it cost — the dates propose, you decide.
        </div>
      </div>

      {selectedId === null ? (
        <>
          <div>
            <button type="button" className="btn-primary" onClick={() => setPanel({ kind: "trip", trip: null })}>
              New trip
            </button>
          </div>
          <TripList
            trips={trips.data}
            onOpen={setSelectedId}
            onNew={() => setPanel({ kind: "trip", trip: null })}
          />
        </>
      ) : (
        <>
          <button type="button" className="btn-ghost text-sm" onClick={() => setSelectedId(null)}>
            ← All trips
          </button>
          {detail.isLoading && <div className="card p-8 text-center text-sm text-ink-500">Loading…</div>}
          {current && (
            <>
              <div className="card p-4">
                <div className="flex flex-wrap items-start justify-between gap-4">
                  <div>
                    <div className="text-xl font-semibold">{current.trip.name}</div>
                    <div className="text-sm text-ink-500">
                      {dateSpan(current.trip.start_date, current.trip.end_date)} ·{" "}
                      {current.report.nights} nights
                    </div>
                    {current.trip.notes && (
                      <div className="mt-2 max-w-prose whitespace-pre-wrap text-sm text-ink-600">
                        {current.trip.notes}
                      </div>
                    )}
                  </div>
                  <div className="flex flex-wrap gap-6">
                    <Stat label="Total" value={currency(current.report.total)} />
                    <Stat
                      label="Per night"
                      value={
                        current.report.per_night === null
                          ? "—"
                          : currency(current.report.per_night)
                      }
                    />
                  </div>
                </div>
                <div className="mt-4 flex flex-wrap gap-2">
                  <button type="button" className="btn-primary" onClick={() => setPanel({ kind: "review" })}>
                    Review spending
                  </button>
                  <button type="button" className="btn" onClick={() => setPanel({ kind: "item", itemId: null })}>
                    Add a cost
                  </button>
                  <button
                    type="button"
                    className="btn"
                    onClick={() => setPanel({ kind: "trip", trip: current.trip })}
                  >
                    Edit trip
                  </button>
                  <button
                    type="button"
                    className="btn-ghost ml-auto text-xs text-bad-600"
                    onClick={() => {
                      if (
                        confirm(
                          `Delete "${current.trip.name}"? The transactions it counted are not touched.`,
                        )
                      ) {
                        removeTrip.mutate(current.trip.id);
                      }
                    }}
                  >
                    Delete trip
                  </button>
                </div>
              </div>

              <Itinerary
                detail={current}
                onAddLeg={() => setPanel({ kind: "leg", leg: null })}
                onEditLeg={(leg) => setPanel({ kind: "leg", leg })}
              />
              <CostMatrix detail={current} />
              <RowsTable
                detail={current}
                busy={busy}
                onSetShare={(transactionId, share, legId) =>
                  setExpenses.mutate({
                    expenses: [{ transaction_id: transactionId, share, leg_id: legId }],
                    auto_assign_legs: false,
                  })
                }
                onSetLeg={(transactionId, legId, share) =>
                  setExpenses.mutate({
                    expenses: [{ transaction_id: transactionId, leg_id: legId, share }],
                    auto_assign_legs: false,
                  })
                }
                onRemove={(transactionId) =>
                  setExpenses.mutate({ remove_transaction_ids: [transactionId] })
                }
                onEditItem={(itemId) => setPanel({ kind: "item", itemId })}
              />
            </>
          )}
        </>
      )}

      {panel.kind === "trip" && (
        <TripEditor
          trip={panel.trip}
          busy={busy}
          onClose={closePanel}
          onSubmit={(body) => {
            if (panel.trip) patchTrip.mutate(body);
            else createTrip.mutate(body);
          }}
        />
      )}

      {panel.kind === "leg" && current && (
        <LegEditor
          leg={panel.leg}
          trip={current.trip}
          busy={busy}
          onClose={closePanel}
          onSubmit={(body) => {
            if (panel.leg) patchLeg.mutate({ id: panel.leg.id, body });
            else addLeg.mutate(body);
          }}
          onDelete={
            panel.leg
              ? () => {
                  const leg = panel.leg as TripLeg;
                  if (
                    confirm(
                      `Delete ${leg.place}? Its spending stays on the trip, just without a stop.`,
                    )
                  ) {
                    removeLeg.mutate(leg.id);
                  }
                }
              : null
          }
        />
      )}

      {panel.kind === "item" && current && (
        <ItemEditor
          item={editingItem}
          trip={current.trip}
          categories={categories.data ?? []}
          busy={busy}
          onClose={closePanel}
          onSubmit={(body) => {
            if (editingItem) patchItem.mutate({ id: editingItem.id, body });
            else addItem.mutate(body);
          }}
          onDelete={editingItem ? () => removeItem.mutate(editingItem.id) : null}
        />
      )}

      {panel.kind === "review" && selectedId !== null && (
        <ReviewPanel
          candidates={candidates.data}
          isLoading={candidates.isLoading}
          lookbackDays={lookbackDays}
          onLookback={setLookbackDays}
          busy={busy}
          onPolicy={(categoryId, policy) =>
            setPolicy.mutate({ policies: [{ category_id: categoryId, policy }] })
          }
          onConfirm={(transactionIds) =>
            setExpenses.mutate(
              { expenses: transactionIds.map((id) => ({ transaction_id: id })) },
              { onSuccess: closePanel },
            )
          }
          onClose={closePanel}
        />
      )}
    </div>
  );
}
