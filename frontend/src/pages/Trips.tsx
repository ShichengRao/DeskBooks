import { useEffect, useMemo, useState, type KeyboardEvent, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import clsx from "clsx";
import { api, qs } from "../api/client";
import { Field } from "../components/Field";
import { SidePanel } from "../components/SidePanel";
import {
  EMPTY_FILTERS,
  TransactionFiltersCard,
  TransactionsPagination,
  transactionFilterArgs,
  type TransactionFilters,
} from "../components/TransactionFilters";
import { currency, dateLabel } from "../lib/fmt";
import { legForDate, tripBlockReason, tripSpend } from "../lib/trips";
import type {
  Account,
  Category,
  Transaction,
  Trip,
  TripDetail,
  TripItem,
  TripLeg,
  TripReport,
  TripSummary,
} from "../api/types";

// A trip is a name and the rows you tick into it, in your own time.
//
// Nothing is proposed. A date window is wrong in both directions — rent
// bills while you are away, flights were bought months ahead — and a trip
// is small enough that deciding each row yourself is cheaper than
// correcting a guess. So the trip page is the ledger, filtered the way the
// Transactions tab filters it, with a checkbox that means "this counts":
// pick a date range and a category, tick what belongs, pick the next
// category. The running total sits beside the table, grouped by the trip's
// own labels rather than the categories budgets and analytics use.

type TripBody = {
  name: string;
  start_date: string | null;
  end_date: string | null;
  notes: string | null;
};

type ItemBody = {
  label: string;
  amount: string;
  date: string | null;
  leg_id: number | null;
  category_id: number | null;
  group_name: string | null;
};

type RowPatch = { group_name?: string | null; leg_id?: number | null; share?: number };

type Show = "trip" | "unclaimed" | "all";

const surfaceError = (error: Error) => alert(error.message);

function dateSpan(start: string, end: string) {
  return `${dateLabel(start)} – ${dateLabel(end)}`;
}

function nightsBetween(start: string, end: string) {
  const a = new Date(start).getTime();
  const b = new Date(end).getTime();
  if (Number.isNaN(a) || Number.isNaN(b)) return 0;
  return Math.max(0, Math.round((b - a) / 86_400_000));
}

function datesLine(trip: Trip, report?: TripReport) {
  if (trip.start_date && trip.end_date) {
    const nights = report?.nights ?? nightsBetween(trip.start_date, trip.end_date);
    return `${dateSpan(trip.start_date, trip.end_date)} · ${nights} ${nights === 1 ? "night" : "nights"}`;
  }
  if (report?.span_start && report.span_end) {
    return `No dates set · counted rows run ${dateSpan(report.span_start, report.span_end)}`;
  }
  return "No dates set";
}

// ---------- list ----------

export function Trips() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const trips = useQuery({
    queryKey: ["trips"],
    queryFn: () => api.get<TripSummary[]>("/api/trips"),
  });
  const create = useMutation({
    mutationFn: (body: TripBody) => api.post<TripDetail>("/api/trips", body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ["trips"] });
      navigate(`/trips/${data.trip.id}`);
    },
    onError: surfaceError,
  });

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-2">
        <h1 className="text-2xl font-semibold tracking-tight">Trips</h1>
        <div className="text-sm text-ink-500">
          Name a trip, then tick what it cost — whenever you get to it.
        </div>
      </div>
      <div>
        <button type="button" className="btn-primary" onClick={() => setCreating(true)}>
          New trip
        </button>
      </div>
      <div className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="tabular w-full min-w-[36rem] text-sm">
            <thead className="bg-ink-50 text-left">
              <tr>
                <th className="px-2 py-1.5">Trip</th>
                <th className="px-2 py-1.5">Dates</th>
                <th className="px-2 py-1.5 text-right">Rows</th>
                <th className="px-2 py-1.5 text-right">Total</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-100">
              {trips.data?.map((summary) => (
                <tr
                  key={summary.trip.id}
                  className="table-row-hover cursor-pointer"
                  onClick={() => navigate(`/trips/${summary.trip.id}`)}
                >
                  <td className="px-2 py-1.5">
                    <Link to={`/trips/${summary.trip.id}`} className="font-medium text-brand-700">
                      {summary.trip.name}
                    </Link>
                    {summary.trip.legs.length > 0 && (
                      <div className="text-xs text-ink-500">
                        {summary.trip.legs.map((leg) => leg.place).join(" → ")}
                      </div>
                    )}
                  </td>
                  <td className="px-2 py-1.5 text-ink-600">
                    {summary.trip.start_date && summary.trip.end_date ? (
                      dateSpan(summary.trip.start_date, summary.trip.end_date)
                    ) : (
                      <span className="text-ink-400">No dates yet</span>
                    )}
                  </td>
                  <td className="px-2 py-1.5 text-right text-ink-500">
                    {summary.expense_count + summary.item_count}
                  </td>
                  <td className="px-2 py-1.5 text-right font-semibold">{currency(summary.total)}</td>
                </tr>
              ))}
              {trips.data && trips.data.length === 0 && (
                <tr>
                  <td colSpan={4} className="p-8 text-center text-ink-500">
                    No trips yet. Start one with just a name — the rows can come later.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
      {creating && (
        <TripEditor
          trip={null}
          busy={create.isPending}
          onClose={() => setCreating(false)}
          onSubmit={(body) => create.mutate(body)}
        />
      )}
    </div>
  );
}

// ---------- one trip ----------

type Panel =
  | { kind: "none" }
  | { kind: "trip" }
  | { kind: "leg"; leg: TripLeg | null }
  | { kind: "item"; item: TripItem | null };

export function TripPage() {
  const { tripId: param } = useParams();
  const tripId = Number(param);
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [show, setShow] = useState<Show | null>(null);
  const [filters, setFilters] = useState<TransactionFilters>(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(100);
  const [panel, setPanel] = useState<Panel>({ kind: "none" });

  const detail = useQuery({
    queryKey: ["trip", tripId],
    queryFn: () => api.get<TripDetail>(`/api/trips/${tripId}`),
    enabled: Number.isFinite(tripId),
  });
  const accounts = useQuery({
    queryKey: ["accounts"],
    queryFn: () => api.get<Account[]>("/api/accounts"),
  });
  const categories = useQuery({
    queryKey: ["categories-all"],
    queryFn: () => api.get<Category[]>("/api/categories?include_archived=true"),
  });

  // First visit: open where the work is — what's left to add for a new
  // trip, what's counted for one already under way — with the trip's own
  // dates as the starting date range.
  useEffect(() => {
    if (!detail.data || show !== null) return;
    const { trip, report } = detail.data;
    setShow(report.expense_count + report.item_count > 0 ? "trip" : "unclaimed");
    if (trip.start_date && trip.end_date) {
      setFilters({ ...EMPTY_FILTERS, start: trip.start_date, end: trip.end_date });
    }
  }, [detail.data, show]);

  // A narrower view never strands you past its last page.
  useEffect(() => setPage(0), [show, filters]);

  const view: Show = show ?? "trip";
  const args = {
    ...transactionFilterArgs(filters),
    // "In this trip" is the whole trip, whatever the dates say: a deposit
    // paid a year ahead belongs on it as much as the hotel bar.
    ...(view === "trip" ? { start: undefined, end: undefined, trip_id: tripId } : {}),
    ...(view === "unclaimed" ? { not_in_trip: true } : {}),
  };
  const rows = useQuery({
    queryKey: ["trip-rows", tripId, view, filters, page, pageSize],
    queryFn: () =>
      api.get<Transaction[]>("/api/transactions" + qs({ ...args, limit: pageSize, offset: page * pageSize })),
    enabled: show !== null,
  });
  const count = useQuery({
    queryKey: ["trip-rows-count", tripId, view, filters],
    queryFn: () => api.get<{ count: number }>("/api/transactions/count" + qs(args)),
    enabled: show !== null,
  });

  const accountById = useMemo(
    () => Object.fromEntries((accounts.data ?? []).map((a) => [a.id, a])),
    [accounts.data],
  );
  const categoryById = useMemo(
    () => Object.fromEntries((categories.data ?? []).map((c) => [c.id, c])),
    [categories.data],
  );

  // Ticks and edits patch the rows already on screen instead of
  // refetching them. Refetching "Not in a trip" after a tick would drop the
  // row you just ticked and slide the next one under your cursor; this way
  // rows stay put until you change the filters.
  const patchRows = (update: (tx: Transaction) => Transaction | null) =>
    qc.setQueriesData<Transaction[]>({ queryKey: ["trip-rows", tripId] }, (old) =>
      old?.map((tx) => update(tx) ?? tx),
    );
  const refreshTotals = () => {
    qc.invalidateQueries({ queryKey: ["trip", tripId] });
    qc.invalidateQueries({ queryKey: ["trips"] });
    // The Transactions tab shows which trip each row is in.
    qc.invalidateQueries({ queryKey: ["transactions"] });
  };
  const refreshAll = () => {
    refreshTotals();
    qc.invalidateQueries({ queryKey: ["trip-rows", tripId] });
  };

  const membership = useMutation({
    mutationFn: (change: { add: Transaction[]; remove: Transaction[] }) =>
      api.put<TripDetail>(`/api/trips/${tripId}/expenses`, {
        add_transaction_ids: change.add.map((tx) => tx.id),
        remove_transaction_ids: change.remove.map((tx) => tx.id),
      }),
    onSuccess: (data, change) => {
      const added = new Set(change.add.map((tx) => tx.id));
      const removed = new Set(change.remove.map((tx) => tx.id));
      patchRows((tx) => {
        if (added.has(tx.id) && tx.trip?.trip_id !== tripId) {
          return {
            ...tx,
            trip: {
              trip_id: tripId,
              trip_name: data.trip.name,
              group_name: null,
              leg_id: legForDate(data.trip.legs, tx.date),
              share: "1",
            },
          };
        }
        if (removed.has(tx.id)) return { ...tx, trip: null };
        return null;
      });
      refreshTotals();
    },
    onError: surfaceError,
  });
  const editRow = useMutation({
    mutationFn: ({ tx, patch }: { tx: Transaction; patch: RowPatch }) =>
      api.patch<TripDetail>(`/api/trips/${tripId}/expenses/${tx.id}`, patch),
    onSuccess: (_, { tx, patch }) => {
      patchRows((row) => {
        if (row.id !== tx.id || !row.trip) return null;
        const trip = { ...row.trip };
        if ("group_name" in patch) trip.group_name = patch.group_name?.trim() || null;
        if ("leg_id" in patch) trip.leg_id = patch.leg_id ?? null;
        if ("share" in patch && patch.share !== undefined) trip.share = String(patch.share);
        return { ...row, trip };
      });
      refreshTotals();
    },
    onError: surfaceError,
  });
  const closeAfter = {
    onSuccess: () => {
      setPanel({ kind: "none" });
      refreshAll();
    },
    onError: surfaceError,
  };
  const patchTrip = useMutation({
    mutationFn: (body: TripBody) => api.patch<TripDetail>(`/api/trips/${tripId}`, body),
    onSuccess: (data) => {
      // New dates become the date range you're browsing.
      const { start_date, end_date } = data.trip;
      setFilters((f) => ({ ...f, start: start_date ?? "", end: end_date ?? "" }));
      closeAfter.onSuccess();
    },
    onError: surfaceError,
  });
  const removeTrip = useMutation({
    mutationFn: () => api.del(`/api/trips/${tripId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["trips"] });
      qc.invalidateQueries({ queryKey: ["transactions"] });
      navigate("/trips");
    },
    onError: surfaceError,
  });
  const saveLeg = useMutation({
    mutationFn: ({ id, body }: { id: number | null; body: unknown }) =>
      id === null
        ? api.post<TripDetail>(`/api/trips/${tripId}/legs`, body)
        : api.patch<TripDetail>(`/api/trips/${tripId}/legs/${id}`, body),
    ...closeAfter,
  });
  const removeLeg = useMutation({
    mutationFn: (id: number) => api.del(`/api/trips/${tripId}/legs/${id}`),
    ...closeAfter,
  });
  const saveItem = useMutation({
    mutationFn: ({ id, body }: { id: number | null; body: ItemBody }) =>
      id === null
        ? api.post<TripDetail>(`/api/trips/${tripId}/items`, body)
        : api.patch<TripDetail>(`/api/trips/${tripId}/items/${id}`, body),
    ...closeAfter,
  });
  const removeItem = useMutation({
    mutationFn: (id: number) => api.del(`/api/trips/${tripId}/items/${id}`),
    ...closeAfter,
  });

  if (detail.isError) {
    return (
      <div className="space-y-3">
        <Link to="/trips" className="btn-ghost text-sm">← All trips</Link>
        <div className="card p-8 text-center text-sm text-ink-500">That trip doesn't exist any more.</div>
      </div>
    );
  }
  const current = detail.data;
  if (!current) {
    return <div className="p-8 text-sm text-ink-500">Loading…</div>;
  }
  const { trip, report } = current;
  const txs = rows.data ?? [];
  const totalRows = count.data?.count ?? 0;
  const editorBusy =
    patchTrip.isPending || saveLeg.isPending || removeLeg.isPending || saveItem.isPending || removeItem.isPending;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <Link to="/trips" className="text-sm text-ink-500 hover:text-ink-900">← All trips</Link>
          <h1 className="text-2xl font-semibold tracking-tight">{trip.name}</h1>
          <div className="text-sm text-ink-500">{datesLine(trip, report)}</div>
        </div>
        <div className="flex flex-wrap gap-2">
          <button type="button" className="btn" onClick={() => setPanel({ kind: "trip" })}>
            Edit trip
          </button>
          <button
            type="button"
            className="btn-ghost text-bad-600 hover:bg-bad-500/10"
            disabled={removeTrip.isPending}
            onClick={() => {
              if (confirm(`Delete "${trip.name}"? The transactions it counted are not touched.`)) {
                removeTrip.mutate();
              }
            }}
          >
            Delete trip
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_20rem]">
        <aside className="xl:col-start-2 xl:row-start-1">
          <div className="space-y-4 xl:sticky xl:top-4">
            <TripTotals
              trip={trip}
              report={report}
              items={current.items}
              onAddLeg={() => setPanel({ kind: "leg", leg: null })}
              onEditLeg={(leg) => setPanel({ kind: "leg", leg })}
              onAddItem={() => setPanel({ kind: "item", item: null })}
              onEditItem={(item) => setPanel({ kind: "item", item })}
            />
          </div>
        </aside>

        <div className="min-w-0 space-y-3 xl:col-start-1 xl:row-start-1">
          <ShowSwitch value={view} counted={report.expense_count} onChange={setShow} />
          <TransactionFiltersCard
            filters={filters}
            accounts={accounts.data ?? []}
            categories={categories.data ?? []}
            onChange={setFilters}
            datesIgnoredBecause={
              view === "trip" ? "\"In this trip\" shows the whole trip, whatever the dates" : undefined
            }
          />
          <TripRowsTable
            txs={txs}
            loading={rows.isLoading}
            view={view}
            tripId={tripId}
            legs={trip.legs}
            groupSuggestions={current.group_suggestions}
            accountById={accountById}
            categoryById={categoryById}
            onToggle={(add, remove) => membership.mutate({ add, remove })}
            onEdit={(tx, patch) => editRow.mutate({ tx, patch })}
          />
          <TransactionsPagination
            page={page}
            pageSize={pageSize}
            totalPages={Math.max(1, Math.ceil(totalRows / pageSize))}
            onPage={setPage}
            onPageSize={(size) => {
              setPageSize(size);
              setPage(0);
            }}
          />
        </div>
      </div>

      {panel.kind === "trip" && (
        <TripEditor
          trip={trip}
          busy={editorBusy}
          onClose={() => setPanel({ kind: "none" })}
          onSubmit={(body) => patchTrip.mutate(body)}
        />
      )}
      {panel.kind === "leg" && (
        <LegEditor
          leg={panel.leg}
          trip={trip}
          busy={editorBusy}
          onClose={() => setPanel({ kind: "none" })}
          onSubmit={(body) => saveLeg.mutate({ id: panel.leg?.id ?? null, body })}
          onDelete={
            panel.leg
              ? () => {
                  const leg = panel.leg as TripLeg;
                  if (confirm(`Delete ${leg.place}? Its spending stays on the trip, just without a stop.`)) {
                    removeLeg.mutate(leg.id);
                  }
                }
              : null
          }
        />
      )}
      {panel.kind === "item" && (
        <ItemEditor
          item={panel.item}
          trip={trip}
          categories={categories.data ?? []}
          groupSuggestions={current.group_suggestions}
          busy={editorBusy}
          onClose={() => setPanel({ kind: "none" })}
          onSubmit={(body) => saveItem.mutate({ id: panel.item?.id ?? null, body })}
          onDelete={
            panel.item
              ? () => {
                  const item = panel.item as TripItem;
                  if (confirm(`Delete "${item.label}"?`)) removeItem.mutate(item.id);
                }
              : null
          }
        />
      )}
    </div>
  );
}

function ShowSwitch({
  value,
  counted,
  onChange,
}: {
  value: Show;
  counted: number;
  onChange: (value: Show) => void;
}) {
  const options: { value: Show; label: string; hint: string }[] = [
    { value: "trip", label: `In this trip (${counted})`, hint: "Everything counted so far, whatever its date" },
    { value: "unclaimed", label: "Not in a trip", hint: "Rows no trip has counted yet — where you pick from" },
    { value: "all", label: "All", hint: "Everything the filters match, counted or not" },
  ];
  return (
    <div className="inline-flex overflow-hidden rounded-md border border-ink-200 bg-white text-sm" role="group" aria-label="Show">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          title={option.hint}
          aria-pressed={value === option.value}
          className={clsx(
            "border-r border-ink-200 px-3 py-1.5 last:border-r-0",
            value === option.value ? "bg-brand-100 text-brand-800" : "text-ink-600 hover:bg-ink-100",
          )}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

// ---------- the table ----------

function TripRowsTable({
  txs,
  loading,
  view,
  tripId,
  legs,
  groupSuggestions,
  accountById,
  categoryById,
  onToggle,
  onEdit,
}: {
  txs: Transaction[];
  loading: boolean;
  view: Show;
  tripId: number;
  legs: TripLeg[];
  groupSuggestions: string[];
  accountById: Record<number, Account>;
  categoryById: Record<number, Category>;
  onToggle: (add: Transaction[], remove: Transaction[]) => void;
  onEdit: (tx: Transaction, patch: RowPatch) => void;
}) {
  const tickable = txs.filter((tx) => !tripBlockReason(tx, tripId));
  const counted = tickable.filter((tx) => tx.trip?.trip_id === tripId);
  const allCounted = tickable.length > 0 && counted.length === tickable.length;
  const columns = legs.length > 0 ? 10 : 9;
  const shownTotal = counted.reduce((acc, tx) => acc + tripSpend(tx), 0);

  return (
    <div className="card overflow-hidden">
      <datalist id="trip-groups">
        {groupSuggestions.map((name) => (
          <option key={name} value={name} />
        ))}
      </datalist>
      <div className="overflow-x-auto">
        <table className="tabular w-full min-w-[64rem] text-sm">
          <thead className="bg-ink-50 text-left">
            <tr>
              <th className="w-8 px-2 py-2">
                <input
                  type="checkbox"
                  aria-label={allCounted ? "Untick every row shown" : "Tick every row shown"}
                  title={allCounted ? "Untick every row shown" : "Tick every row shown that can count"}
                  checked={allCounted}
                  disabled={tickable.length === 0}
                  ref={(el) => {
                    if (el) el.indeterminate = counted.length > 0 && !allCounted;
                  }}
                  onChange={() =>
                    allCounted
                      ? onToggle([], counted)
                      : onToggle(tickable.filter((tx) => tx.trip?.trip_id !== tripId), [])
                  }
                />
              </th>
              <th className="w-24 px-2 py-2">Date</th>
              <th className="px-2 py-2">Description</th>
              <th className="w-36 px-2 py-2">Category</th>
              <th className="w-32 px-2 py-2">Account</th>
              <th className="w-28 px-2 py-2 text-right">Amount</th>
              <th className="w-40 px-2 py-2">Trip group</th>
              {legs.length > 0 && <th className="w-40 px-2 py-2">Stop</th>}
              <th className="w-20 px-2 py-2 text-right">Share</th>
              <th className="w-24 px-2 py-2 text-right">Counts</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {loading && (
              <tr>
                <td colSpan={columns} className="p-8 text-center text-ink-500">Loading…</td>
              </tr>
            )}
            {txs.map((tx) => (
              <TripRow
                key={tx.id}
                tx={tx}
                tripId={tripId}
                legs={legs}
                account={accountById[tx.account_id]}
                category={tx.category_id ? categoryById[tx.category_id] : undefined}
                onToggle={onToggle}
                onEdit={onEdit}
              />
            ))}
            {!loading && txs.length === 0 && (
              <tr>
                <td colSpan={columns} className="p-8 text-center text-ink-500">
                  {view === "trip"
                    ? "Nothing counted yet. Switch to \"Not in a trip\", narrow the dates or pick a category, and tick what belongs."
                    : "No transactions match these filters."}
                </td>
              </tr>
            )}
          </tbody>
          {counted.length > 0 && (
            <tfoot className="bg-ink-50">
              <tr>
                <td colSpan={columns - 1} className="px-2 py-2 text-right text-ink-500">
                  Counted on this page
                </td>
                <td className="px-2 py-2 text-right font-semibold">{currency(shownTotal)}</td>
              </tr>
            </tfoot>
          )}
        </table>
      </div>
    </div>
  );
}

function TripRow({
  tx,
  tripId,
  legs,
  account,
  category,
  onToggle,
  onEdit,
}: {
  tx: Transaction;
  tripId: number;
  legs: TripLeg[];
  account?: Account;
  category?: Category;
  onToggle: (add: Transaction[], remove: Transaction[]) => void;
  onEdit: (tx: Transaction, patch: RowPatch) => void;
}) {
  const counted = tx.trip?.trip_id === tripId;
  const blocked = tripBlockReason(tx, tripId);
  const sharePct = tx.trip ? Math.round(Number(tx.trip.share) * 100) : 100;
  const commitOnEnter = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") e.currentTarget.blur();
  };

  return (
    <tr className={clsx("table-row-hover", counted && "bg-brand-50/60", blocked && "text-ink-400")}>
      <td className="px-2 py-1.5">
        <input
          type="checkbox"
          aria-label={counted ? "Untick from this trip" : "Tick into this trip"}
          checked={counted}
          disabled={!!blocked}
          title={blocked ?? undefined}
          onChange={() => (counted ? onToggle([], [tx]) : onToggle([tx], []))}
        />
      </td>
      <td className="whitespace-nowrap px-2 py-1.5">{dateLabel(tx.date)}</td>
      <td className="px-2 py-1.5">
        <div className={clsx("font-medium", blocked && "font-normal")}>
          {tx.merchant ?? tx.description_normalized ?? tx.description_raw}
        </div>
        <div className="flex items-center gap-2 text-xs text-ink-500">
          <span className="max-w-xs truncate">{tx.description_raw}</span>
          {tx.trip && !counted && (
            <Link to={`/trips/${tx.trip.trip_id}`} className="pill shrink-0 bg-ink-100 text-ink-600 hover:bg-ink-200">
              in {tx.trip.trip_name}
            </Link>
          )}
        </div>
      </td>
      <td className="px-2 py-1.5">{category?.name ?? "—"}</td>
      <td className="px-2 py-1.5">{account?.name ?? "—"}</td>
      <td
        className={clsx(
          "px-2 py-1.5 text-right font-medium",
          !blocked && (Number(tx.amount) < 0 ? "text-bad-600" : "text-good-600"),
        )}
      >
        {currency(tx.amount)}
      </td>
      <td className="px-2 py-1.5">
        {counted && (
          <input
            key={`${tx.id}:${tx.trip?.group_name ?? ""}`}
            className="input py-0.5 text-xs"
            list="trip-groups"
            aria-label="Trip group"
            defaultValue={tx.trip?.group_name ?? ""}
            placeholder={category?.name ?? "Other"}
            title="The trip's own label for this row. Leave it blank to use the category."
            onKeyDown={commitOnEnter}
            onBlur={(e) => {
              const next = e.target.value.trim();
              if (next !== (tx.trip?.group_name ?? "")) onEdit(tx, { group_name: next || null });
            }}
          />
        )}
      </td>
      {legs.length > 0 && (
        <td className="px-2 py-1.5">
          {counted && (
            <select
              className="input py-0.5 text-xs"
              aria-label="Stop"
              value={tx.trip?.leg_id ?? ""}
              onChange={(e) => onEdit(tx, { leg_id: e.target.value ? Number(e.target.value) : null })}
            >
              <option value="">No stop</option>
              {legs.map((leg) => (
                <option key={leg.id} value={leg.id}>{leg.place}</option>
              ))}
            </select>
          )}
        </td>
      )}
      <td className="px-2 py-1.5 text-right">
        {counted && (
          <span className="inline-flex items-center gap-1">
            <input
              key={`${tx.id}:${tx.trip?.share}`}
              type="number"
              min={1}
              max={100}
              step={1}
              className="input w-14 py-0.5 text-right text-xs"
              aria-label="Share of this charge"
              defaultValue={sharePct}
              title="How much of this charge was the trip's — a quarter of a drugstore order is 25."
              onKeyDown={commitOnEnter}
              onBlur={(e) => {
                const pct = parseInt(e.target.value, 10);
                if (!Number.isFinite(pct)) {
                  e.target.value = String(sharePct);
                  return;
                }
                const next = Math.min(100, Math.max(1, pct));
                e.target.value = String(next);
                if (next !== sharePct) onEdit(tx, { share: next / 100 });
              }}
            />
            <span className="text-xs text-ink-500">%</span>
          </span>
        )}
      </td>
      <td className="px-2 py-1.5 text-right font-semibold">{counted ? currency(tripSpend(tx)) : ""}</td>
    </tr>
  );
}

// ---------- the running total ----------

function TripTotals({
  trip,
  report,
  items,
  onAddLeg,
  onEditLeg,
  onAddItem,
  onEditItem,
}: {
  trip: Trip;
  report: TripReport;
  items: TripItem[];
  onAddLeg: () => void;
  onEditLeg: (leg: TripLeg) => void;
  onAddItem: () => void;
  onEditItem: (item: TripItem) => void;
}) {
  const legName = (id: string) => report.legs.find((leg) => String(leg.id) === id)?.place ?? "?";
  const rowCount = report.expense_count + report.item_count;

  return (
    <div className="card divide-y divide-ink-100">
      <div className="p-4">
        <div className="label mb-0.5">Total</div>
        <div className="tabular text-2xl font-semibold">{currency(report.total)}</div>
        <div className="text-xs text-ink-500">
          {report.per_night !== null && `${currency(report.per_night)}/night · `}
          {rowCount} {rowCount === 1 ? "row" : "rows"}
        </div>
      </div>

      <div className="p-4">
        <div className="label mb-2">By group</div>
        {report.groups.length === 0 ? (
          <div className="text-sm text-ink-500">Nothing counted yet.</div>
        ) : (
          <ul className="space-y-1.5 text-sm">
            {report.groups.map((group) => {
              const perStop = Object.entries(group.by_leg).filter(([, amount]) => Number(amount) !== 0);
              return (
                <li key={group.name}>
                  <div className="flex items-baseline justify-between gap-3">
                    <span className="truncate">{group.name}</span>
                    <span className="tabular font-medium">{currency(group.total)}</span>
                  </div>
                  {report.legs.length > 0 && perStop.length > 0 && (
                    <div className="tabular text-xs text-ink-500">
                      {perStop.map(([id, amount]) => `${legName(id)} ${currency(amount)}`).join(" · ")}
                      {Number(group.unassigned) !== 0 && ` · no stop ${currency(group.unassigned)}`}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <div className="p-4">
        <div className="mb-2 flex items-baseline justify-between">
          <span className="label">Stops</span>
          <button type="button" className="btn-ghost text-xs" onClick={onAddLeg}>Add stop</button>
        </div>
        {report.legs.length === 0 ? (
          <div className="text-xs text-ink-500">
            Optional. Add stops to split the total by place; rows file by date.
          </div>
        ) : (
          <ul className="space-y-1 text-sm">
            {report.legs.map((leg) => (
              <li key={leg.id}>
                <button
                  type="button"
                  className="flex w-full items-baseline justify-between gap-3 rounded px-1 py-0.5 text-left hover:bg-ink-50"
                  onClick={() => {
                    const match = trip.legs.find((l) => l.id === leg.id);
                    if (match) onEditLeg(match);
                  }}
                >
                  <span className="min-w-0">
                    <span className="block truncate">{leg.place}</span>
                    <span className="block text-xs text-ink-500">
                      {dateSpan(leg.start_date, leg.end_date)} · {leg.nights}n
                    </span>
                  </span>
                  <span className="tabular shrink-0 text-right">
                    <span className="block font-medium">{currency(leg.total)}</span>
                    {leg.per_night !== null && (
                      <span className="block text-xs text-ink-500">{currency(leg.per_night)}/n</span>
                    )}
                  </span>
                </button>
              </li>
            ))}
            {Number(report.unassigned_total) !== 0 && (
              <li className="flex items-baseline justify-between gap-3 px-1 text-ink-500">
                <span>No stop</span>
                <span className="tabular">{currency(report.unassigned_total)}</span>
              </li>
            )}
          </ul>
        )}
      </div>

      <div className="p-4">
        <div className="mb-2 flex items-baseline justify-between">
          <span className="label">Added by hand</span>
          <button type="button" className="btn-ghost text-xs" onClick={onAddItem}>Add a cost</button>
        </div>
        {items.length === 0 ? (
          <div className="text-xs text-ink-500">
            For spending the ledger never saw — cash, a ride paid from PayPal, a refund that hasn't
            posted yet.
          </div>
        ) : (
          <ul className="space-y-1 text-sm">
            {items.map((item) => (
              <li key={item.id}>
                <button
                  type="button"
                  className="flex w-full items-baseline justify-between gap-3 rounded px-1 py-0.5 text-left hover:bg-ink-50"
                  onClick={() => onEditItem(item)}
                >
                  <span className="min-w-0">
                    <span className="block truncate">{item.label}</span>
                    <span className="block text-xs text-ink-500">
                      {[item.date && dateLabel(item.date), item.group_name].filter(Boolean).join(" · ") || "—"}
                    </span>
                  </span>
                  <span className={clsx("tabular shrink-0 font-medium", Number(item.amount) < 0 && "text-good-600")}>
                    {currency(item.amount)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

// ---------- editors ----------

function Footer({ children }: { children: ReactNode }) {
  return (
    <div className="sticky bottom-0 z-10 -mx-4 mt-4 flex flex-wrap items-center gap-2 border-t border-ink-100 bg-white px-4 py-3 sm:-mx-6 sm:px-6">
      {children}
    </div>
  );
}

function TripEditor({
  trip,
  busy,
  onClose,
  onSubmit,
}: {
  trip: Trip | null;
  busy: boolean;
  onClose: () => void;
  onSubmit: (body: TripBody) => void;
}) {
  const [name, setName] = useState(trip?.name ?? "");
  const [start, setStart] = useState(trip?.start_date ?? "");
  const [end, setEnd] = useState(trip?.end_date ?? "");
  const [notes, setNotes] = useState(trip?.notes ?? "");
  const halfDated = !!start !== !!end;
  const backwards = !!start && !!end && end < start;
  const valid = name.trim() !== "" && !halfDated && !backwards;

  return (
    <SidePanel
      title={trip ? "Edit trip" : "New trip"}
      onClose={onClose}
      onSubmit={() =>
        onSubmit({
          name: name.trim(),
          start_date: start || null,
          end_date: end || null,
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
            placeholder="Florida, November"
            autoFocus
            required
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Leaves">
            <input type="date" className="input" value={start} onChange={(e) => setStart(e.target.value)} />
          </Field>
          <Field label="Back">
            <input type="date" className="input" value={end} onChange={(e) => setEnd(e.target.value)} />
          </Field>
        </div>
        <div className="flex items-baseline justify-between gap-3 text-xs">
          <span className={clsx(halfDated || backwards ? "text-bad-600" : "text-ink-500")}>
            {halfDated
              ? "Give both dates, or neither."
              : backwards
                ? "The trip ends before it starts."
                : start && end
                  ? `${nightsBetween(start, end)} nights`
                  : "Optional. Dates set the starting date range and give a per-night figure."}
          </span>
          {(start || end) && (
            <button
              type="button"
              className="btn-ghost text-xs"
              onClick={() => {
                setStart("");
                setEnd("");
              }}
            >
              Clear dates
            </button>
          )}
        </div>
        <Field label="Notes">
          <textarea
            className="input h-20"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Who you stayed with, what the trip was for."
          />
        </Field>
      </div>
      <Footer>
        <button type="submit" className="btn-primary" disabled={busy || !valid}>
          {trip ? "Save trip" : "Create trip"}
        </button>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
      </Footer>
    </SidePanel>
  );
}

function LegEditor({
  leg,
  trip,
  busy,
  onClose,
  onSubmit,
  onDelete,
}: {
  leg: TripLeg | null;
  trip: Trip;
  busy: boolean;
  onClose: () => void;
  onSubmit: (body: { place: string; start_date: string; end_date: string }) => void;
  onDelete: (() => void) | null;
}) {
  const [place, setPlace] = useState(leg?.place ?? "");
  const [start, setStart] = useState(leg?.start_date ?? trip.start_date ?? "");
  const [end, setEnd] = useState(leg?.end_date ?? trip.end_date ?? "");
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
            placeholder="Fort Lauderdale"
            autoFocus
            required
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Arrive">
            <input type="date" className="input" value={start} onChange={(e) => setStart(e.target.value)} required />
          </Field>
          <Field label="Leave">
            <input type="date" className="input" value={end} onChange={(e) => setEnd(e.target.value)} required />
          </Field>
        </div>
        <div className="text-xs text-ink-500">
          Counted rows dated inside the stop are filed under it, unless you've already filed them
          somewhere. Stops needn't join up — a travel day between two stays belongs to neither.
        </div>
      </div>
      <Footer>
        <button type="submit" className="btn-primary" disabled={busy || !valid}>
          {leg ? "Save stop" : "Add stop"}
        </button>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
        {onDelete && (
          <button type="button" className="btn-ghost ml-auto text-xs text-bad-600" disabled={busy} onClick={onDelete}>
            Delete stop
          </button>
        )}
      </Footer>
    </SidePanel>
  );
}

function ItemEditor({
  item,
  trip,
  categories,
  groupSuggestions,
  busy,
  onClose,
  onSubmit,
  onDelete,
}: {
  item: TripItem | null;
  trip: Trip;
  categories: Category[];
  groupSuggestions: string[];
  busy: boolean;
  onClose: () => void;
  onSubmit: (body: ItemBody) => void;
  onDelete: (() => void) | null;
}) {
  const startAmount = item ? Number(item.amount) : null;
  const [credit, setCredit] = useState(startAmount !== null && startAmount < 0);
  const [label, setLabel] = useState(item?.label ?? "");
  const [amount, setAmount] = useState(startAmount !== null ? String(Math.abs(startAmount)) : "");
  const [when, setWhen] = useState(item?.date ?? "");
  const [group, setGroup] = useState(item?.group_name ?? "");
  const [categoryId, setCategoryId] = useState(item?.category_id ? String(item.category_id) : "");
  const [legId, setLegId] = useState(item?.leg_id ? String(item.leg_id) : "");
  const parsed = parseFloat(amount);
  const valid = label.trim() !== "" && Number.isFinite(parsed) && parsed > 0;

  return (
    <SidePanel
      title={item ? "Edit cost" : "Add a cost"}
      onClose={onClose}
      onSubmit={() =>
        onSubmit({
          label: label.trim(),
          amount: (credit ? -parsed : parsed).toFixed(2),
          date: when || null,
          leg_id: legId ? Number(legId) : null,
          category_id: categoryId ? Number(categoryId) : null,
          group_name: group.trim() || null,
        })
      }
      maxWidth="max-w-md"
    >
      <div className="space-y-3">
        <div className="rounded-md bg-ink-50 p-3 text-xs text-ink-600">
          For anything the ledger never saw — cash, a ride paid from a PayPal balance, a friend's
          card, or a refund that hasn't posted yet.
        </div>
        <div className="inline-flex overflow-hidden rounded-md border border-ink-200 text-sm" role="group" aria-label="Cost or credit">
          {[false, true].map((isCredit) => (
            <button
              key={String(isCredit)}
              type="button"
              aria-pressed={credit === isCredit}
              className={clsx(
                "border-r border-ink-200 px-3 py-1.5 last:border-r-0",
                credit === isCredit ? "bg-brand-100 text-brand-800" : "text-ink-600 hover:bg-ink-100",
              )}
              onClick={() => setCredit(isCredit)}
            >
              {isCredit ? "Credit" : "Cost"}
            </button>
          ))}
        </div>
        <Field label="What">
          <input
            className="input"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder={credit ? "Ticket refund" : "Ride home from the airport"}
            autoFocus
            required
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={credit ? "Amount back" : "Amount"}>
            <input
              type="number"
              className="input"
              min="0.01"
              step="0.01"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="85.42"
              required
            />
          </Field>
          <Field label="Date">
            <input type="date" className="input" value={when} onChange={(e) => setWhen(e.target.value)} />
          </Field>
        </div>
        <Field label="Trip group">
          <input
            className="input"
            list="trip-groups-editor"
            value={group}
            onChange={(e) => setGroup(e.target.value)}
            placeholder="Transportation"
          />
          <datalist id="trip-groups-editor">
            {groupSuggestions.map((name) => (
              <option key={name} value={name} />
            ))}
          </datalist>
        </Field>
        <Field label="Category">
          <select className="input" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">No category</option>
            {categories
              .filter((c) => !c.archived)
              .map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
          </select>
        </Field>
        {trip.legs.length > 0 && (
          <Field label="Stop">
            <select className="input" value={legId} onChange={(e) => setLegId(e.target.value)}>
              <option value="">Work it out from the date</option>
              {trip.legs.map((leg) => (
                <option key={leg.id} value={leg.id}>{leg.place}</option>
              ))}
            </select>
          </Field>
        )}
      </div>
      <Footer>
        <button type="submit" className="btn-primary" disabled={busy || !valid}>
          {item ? "Save" : credit ? "Add credit" : "Add cost"}
        </button>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
        {onDelete && (
          <button type="button" className="btn-ghost ml-auto text-xs text-bad-600" disabled={busy} onClick={onDelete}>
            Delete
          </button>
        )}
      </Footer>
    </SidePanel>
  );
}
