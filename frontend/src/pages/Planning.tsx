import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import clsx from "clsx";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api } from "../api/client";
import { Field } from "../components/Field";
import { SidePanel } from "../components/SidePanel";
import type {
  Account,
  FireProjection,
  FireSettings,
  Goal,
  GoalKind,
  GoalRevision,
  JournalEntry,
  JournalEntryRevision,
} from "../api/types";
import { accountCategoryLabel } from "../lib/labels";
import { compactCurrency, currency, dateLabel, num } from "../lib/fmt";

const GOAL_KINDS: GoalKind[] = ["savings", "purchase", "retirement", "other"];
type GoalForm = Partial<Goal> & { change_summary?: string };
type GoalProgress = { current: string | null; target: string | null; percent: number | null; as_of?: string };

export function Planning() {
  const goals = useQuery({ queryKey: ["goals"], queryFn: () => api.get<Goal[]>("/api/goals") });
  const accounts = useQuery({ queryKey: ["accounts"], queryFn: () => api.get<Account[]>("/api/accounts") });
  const journal = useQuery({ queryKey: ["journal"], queryFn: () => api.get<JournalEntry[]>("/api/journal") });

  const [selectedGoalId, setSelectedGoalId] = useState<number | "new" | null>(null);
  const [selectedEntryId, setSelectedEntryId] = useState<number | "new" | null>(null);

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold tracking-tight">Planning</h1>

      <FireCalculator />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div className="card p-4">
          <div className="flex items-center justify-between mb-3">
            <div className="text-sm font-medium">Goals</div>
            <button className="btn-primary text-xs" onClick={() => setSelectedGoalId("new")}>
              + New goal
            </button>
          </div>
          <div className="space-y-2">
            {goals.data?.map((g) => (
              <button
                key={g.id}
                className={clsx(
                  "w-full text-left card p-3 hover:border-brand-400 transition-colors",
                  selectedGoalId === g.id && "border-brand-500 ring-1 ring-brand-500",
                )}
                onClick={() => setSelectedGoalId(g.id)}
              >
                <div className="flex items-baseline justify-between">
                  <div className="font-medium">{g.title}</div>
                  <div className="text-xs text-ink-500">
                    {g.target_amount ? currency(g.target_amount) : "no target"}
                    {g.target_date && ` · ${dateLabel(g.target_date)}`}
                  </div>
                </div>
                <div className="text-xs text-ink-500 mt-1">{g.kind} · {g.status}</div>
              </button>
            ))}
          </div>
        </div>

        <div className="card p-4">
          <div className="flex items-center justify-between mb-3">
            <div className="text-sm font-medium">Journal</div>
            <button className="btn-primary text-xs" onClick={() => setSelectedEntryId("new")}>
              + New entry
            </button>
          </div>
          <div className="space-y-2">
            {journal.data?.map((e) => (
              <button
                key={e.id}
                className={clsx(
                  "w-full text-left card p-3 hover:border-brand-400",
                  selectedEntryId === e.id && "border-brand-500 ring-1 ring-brand-500",
                )}
                onClick={() => setSelectedEntryId(e.id)}
              >
                <div className="flex items-baseline justify-between">
                  <div className="font-medium">{e.title}</div>
                  <div className="text-xs text-ink-500">{dateLabel(e.entry_date)}</div>
                </div>
                <div className="text-xs text-ink-500 mt-1 line-clamp-2 whitespace-pre-line">
                  {e.body_markdown.slice(0, 160)}
                </div>
              </button>
            ))}
          </div>
        </div>
      </div>

      {selectedGoalId !== null && (
        <GoalEditor
          goalId={selectedGoalId === "new" ? null : selectedGoalId}
          accounts={accounts.data ?? []}
          onClose={() => setSelectedGoalId(null)}
        />
      )}
      {selectedEntryId !== null && (
        <EntryEditor
          entryId={selectedEntryId === "new" ? null : selectedEntryId}
          goals={goals.data ?? []}
          onClose={() => setSelectedEntryId(null)}
        />
      )}
    </div>
  );
}

function GoalEditor({ goalId, accounts, onClose }: { goalId: number | null; accounts: Account[]; onClose: () => void }) {
  const qc = useQueryClient();
  const goal = useQuery({
    queryKey: ["goal", goalId],
    queryFn: () => api.get<Goal>(`/api/goals/${goalId}`),
    enabled: goalId !== null,
  });
  const revisions = useQuery({
    queryKey: ["goal-revisions", goalId],
    queryFn: () => api.get<GoalRevision[]>(`/api/goals/${goalId}/revisions`),
    enabled: goalId !== null,
  });
  const progress = useQuery({
    queryKey: ["goal-progress", goalId],
    queryFn: () => api.get<GoalProgress>(`/api/goals/${goalId}/progress`),
    enabled: goalId !== null,
  });
  const [form, setForm] = useState<GoalForm>({
    title: "",
    target_amount: null,
    target_date: null,
    kind: "savings",
    status: "active",
    linked_account_ids: [],
    notes_markdown: "",
  });
  const [edited, setEdited] = useState(false);

  useEffect(() => {
    if (goal.data && !edited) setForm(goal.data);
  }, [goal.data, edited]);

  const save = useMutation({
    mutationFn: async () => {
      if (goalId === null) {
        return api.post<Goal>("/api/goals", {
          title: form.title,
          target_amount: form.target_amount,
          target_date: form.target_date,
          kind: form.kind,
          status: form.status,
          linked_account_ids: form.linked_account_ids,
          notes_markdown: form.notes_markdown,
        });
      }
      const { change_summary, ...patch } = form as any;
      return api.patch<Goal>(`/api/goals/${goalId}`, { ...patch, change_summary });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["goals"] });
      qc.invalidateQueries({ queryKey: ["goal", goalId] });
      qc.invalidateQueries({ queryKey: ["goal-revisions", goalId] });
      onClose();
    },
  });

  const remove = useMutation({
    mutationFn: () => api.del(`/api/goals/${goalId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["goals"] });
      qc.invalidateQueries({ queryKey: ["goal", goalId] });
      qc.invalidateQueries({ queryKey: ["goal-revisions", goalId] });
      onClose();
    },
  });

  return (
    <SidePanel
      title={goalId ? "Edit goal" : "New goal"}
      onClose={onClose}
      onSubmit={() => save.mutate()}
      maxWidth="max-w-3xl"
    >
        <GoalProgressCard progress={progress.data ?? null} />
        <GoalFormFields
          goalId={goalId}
          form={form}
          accounts={accounts}
          onChange={(patch, markEdited = true) => {
            setForm({ ...form, ...patch });
            if (markEdited) setEdited(true);
          }}
        />
        <GoalEditorActions
          goalId={goalId}
          savePending={save.isPending}
          deletePending={remove.isPending}
          onClose={onClose}
          onDelete={() => remove.mutate()}
        />
        <GoalHistory goalId={goalId} revisions={revisions.data ?? []} />
    </SidePanel>
  );
}

function GoalProgressCard({ progress }: { progress: GoalProgress | null }) {
  if (!progress) return null;
  return (
    <div className="card p-3 mb-4 bg-brand-50">
      <div className="flex items-baseline justify-between text-sm">
        <span>Current progress</span>
        <span className="font-semibold tabular">
          {currency(progress.current)} / {currency(progress.target)}{" "}
          {progress.percent !== null && `(${progress.percent.toFixed(1)}%)`}
        </span>
      </div>
      {progress.as_of && <div className="text-xs text-ink-500 mt-1">as of {dateLabel(progress.as_of)}</div>}
      <div className="mt-2 h-1.5 bg-white rounded-full overflow-hidden">
        <div
          className="h-full bg-brand-500"
          style={{ width: `${Math.min(100, Math.max(0, progress.percent ?? 0))}%` }}
        />
      </div>
    </div>
  );
}

function GoalFormFields({
  goalId,
  form,
  accounts,
  onChange,
}: {
  goalId: number | null;
  form: GoalForm;
  accounts: Account[];
  onChange: (patch: GoalForm, markEdited?: boolean) => void;
}) {
  return (
    <div className="space-y-3">
      <Field label="Title">
        <input className="input" value={form.title ?? ""} onChange={(e) => onChange({ title: e.target.value })} />
      </Field>
      <GoalTargetFields form={form} onChange={onChange} />
      <Field label="Linked accounts">
        <select
          className="input"
          multiple
          size={6}
          value={(form.linked_account_ids ?? []).map(String)}
          onChange={(e) => {
            const ids = Array.from(e.target.selectedOptions).map((option) => parseInt(option.value, 10));
            onChange({ linked_account_ids: ids });
          }}
        >
          {accounts.map((account) => (
            <option key={account.id} value={account.id}>{account.name} ({accountCategoryLabel(account.account_category)})</option>
          ))}
        </select>
      </Field>
      <Field label="Notes (markdown)">
        <textarea
          className="input min-h-[10rem] font-mono text-xs"
          value={form.notes_markdown ?? ""}
          onChange={(e) => onChange({ notes_markdown: e.target.value })}
        />
      </Field>
      {goalId !== null && (
        <Field label="Change summary (shown in history)">
          <input
            className="input"
            placeholder="why am I editing this goal?"
            value={form.change_summary ?? ""}
            onChange={(e) => onChange({ change_summary: e.target.value }, false)}
          />
        </Field>
      )}
    </div>
  );
}

function GoalTargetFields({
  form,
  onChange,
}: {
  form: GoalForm;
  onChange: (patch: GoalForm, markEdited?: boolean) => void;
}) {
  return (
    <div className="grid grid-cols-2 gap-3">
      <Field label="Target amount">
        <input
          type="number"
          step="0.01"
          className="input tabular"
          value={form.target_amount ?? ""}
          onChange={(e) => onChange({ target_amount: e.target.value || null })}
        />
      </Field>
      <Field label="Target date">
        <input
          type="date"
          className="input"
          value={form.target_date ?? ""}
          onChange={(e) => onChange({ target_date: e.target.value || null })}
        />
      </Field>
      <Field label="Kind">
        <select className="input" value={form.kind ?? "savings"} onChange={(e) => onChange({ kind: e.target.value as GoalKind })}>
          {GOAL_KINDS.map((kind) => (
            <option key={kind} value={kind}>{kind}</option>
          ))}
        </select>
      </Field>
      <Field label="Status">
        <select className="input" value={form.status ?? "active"} onChange={(e) => onChange({ status: e.target.value as Goal["status"] })}>
          <option value="active">active</option>
          <option value="met">met</option>
          <option value="paused">paused</option>
          <option value="abandoned">abandoned</option>
        </select>
      </Field>
    </div>
  );
}

function GoalEditorActions({
  goalId,
  savePending,
  deletePending,
  onClose,
  onDelete,
}: {
  goalId: number | null;
  savePending: boolean;
  deletePending: boolean;
  onClose: () => void;
  onDelete: () => void;
}) {
  return (
    <div className="sticky bottom-0 z-10 -mx-6 mt-4 flex items-center gap-2 border-t border-ink-100 bg-white px-6 py-3">
      <button type="submit" className="btn-primary" disabled={savePending}>Save</button>
      <button type="button" className="btn" onClick={onClose}>Cancel</button>
      {goalId !== null && (
        <button
          type="button"
          className="btn-danger ml-auto"
          onClick={() => {
            if (confirm("Delete this goal?")) onDelete();
          }}
          disabled={deletePending}
        >
          Delete goal
        </button>
      )}
    </div>
  );
}

function GoalHistory({ goalId, revisions }: { goalId: number | null; revisions: GoalRevision[] }) {
  if (goalId === null) return null;
  return (
    <div className="mt-6">
      <div className="text-sm font-medium mb-2">History</div>
      <ul className="space-y-2">
        {revisions.map((revision) => (
          <li key={revision.id} className="card p-3 text-xs">
            <div className="flex items-baseline justify-between">
              <span className="font-medium">{revision.change_summary ?? "(no summary)"}</span>
              <span className="text-ink-500">{dateLabel(revision.changed_at)}</span>
            </div>
            <pre className="mt-2 text-[11px] text-ink-600 overflow-x-auto whitespace-pre-wrap">
              {JSON.stringify(revision.snapshot, null, 2)}
            </pre>
          </li>
        ))}
      </ul>
    </div>
  );
}

function EntryEditor({ entryId, goals, onClose }: { entryId: number | null; goals: Goal[]; onClose: () => void }) {
  const qc = useQueryClient();
  const entry = useQuery({
    queryKey: ["entry", entryId],
    queryFn: () => api.get<JournalEntry>(`/api/journal/${entryId}`),
    enabled: entryId !== null,
  });
  const revisions = useQuery({
    queryKey: ["entry-revisions", entryId],
    queryFn: () => api.get<JournalEntryRevision[]>(`/api/journal/${entryId}/revisions`),
    enabled: entryId !== null,
  });
  const [form, setForm] = useState<Partial<JournalEntry> & { change_summary?: string }>({
    entry_date: new Date().toISOString().slice(0, 10),
    title: "",
    body_markdown: "",
    goal_id: null,
  });
  const [edited, setEdited] = useState(false);
  useEffect(() => {
    if (entry.data && !edited) setForm(entry.data);
  }, [entry.data, edited]);

  const save = useMutation({
    mutationFn: async () => {
      if (entryId === null) {
        return api.post<JournalEntry>("/api/journal", {
          entry_date: form.entry_date,
          title: form.title,
          body_markdown: form.body_markdown,
          goal_id: form.goal_id,
        });
      }
      return api.patch<JournalEntry>(`/api/journal/${entryId}`, form);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["journal"] });
      qc.invalidateQueries({ queryKey: ["entry", entryId] });
      qc.invalidateQueries({ queryKey: ["entry-revisions", entryId] });
      onClose();
    },
  });

  const remove = useMutation({
    mutationFn: () => api.del(`/api/journal/${entryId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["journal"] });
      qc.invalidateQueries({ queryKey: ["entry", entryId] });
      qc.invalidateQueries({ queryKey: ["entry-revisions", entryId] });
      onClose();
    },
  });

  return (
    <SidePanel
      title={entryId ? "Edit entry" : "New entry"}
      onClose={onClose}
      onSubmit={() => save.mutate()}
      maxWidth="max-w-3xl"
    >
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <Field label="Title">
              <input className="input" value={form.title ?? ""} onChange={(e) => { setForm({ ...form, title: e.target.value }); setEdited(true); }} />
            </Field>
            <Field label="Date">
              <input
                type="date"
                className="input"
                value={form.entry_date ?? ""}
                onChange={(e) => { setForm({ ...form, entry_date: e.target.value }); setEdited(true); }}
              />
            </Field>
          </div>
          <Field label="Linked goal (optional)">
            <select
              className="input"
              value={form.goal_id ?? ""}
              onChange={(e) => { setForm({ ...form, goal_id: e.target.value ? parseInt(e.target.value, 10) : null }); setEdited(true); }}
            >
              <option value="">— none —</option>
              {goals.map((g) => (
                <option key={g.id} value={g.id}>{g.title}</option>
              ))}
            </select>
          </Field>
          <Field label="Body (markdown)">
            <textarea
              className="input min-h-[20rem] font-mono text-xs"
              value={form.body_markdown ?? ""}
              onChange={(e) => { setForm({ ...form, body_markdown: e.target.value }); setEdited(true); }}
            />
          </Field>
          {entryId !== null && (
            <Field label="Change summary (shown in history)">
              <input
                className="input"
                placeholder="brief note about what changed"
                value={form.change_summary ?? ""}
                onChange={(e) => setForm({ ...form, change_summary: e.target.value })}
              />
            </Field>
          )}
        </div>
        <div className="sticky bottom-0 z-10 -mx-6 mt-4 flex items-center gap-2 border-t border-ink-100 bg-white px-6 py-3">
          <button type="submit" className="btn-primary" disabled={save.isPending}>
            Save
          </button>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          {entryId !== null && (
            <button
              type="button"
              className="btn-danger ml-auto"
              onClick={() => {
                if (confirm("Delete this journal entry?")) remove.mutate();
              }}
              disabled={remove.isPending}
            >
              Delete entry
            </button>
          )}
        </div>

        {entryId !== null && (
          <div className="mt-6">
            <div className="text-sm font-medium mb-2">History</div>
            <ul className="space-y-2">
              {revisions.data?.map((r) => (
                <li key={r.id} className="card p-3">
                  <div className="flex items-baseline justify-between text-xs">
                    <span className="font-medium">{r.change_summary ?? "(no summary)"}</span>
                    <span className="text-ink-500">{dateLabel(r.changed_at)}</span>
                  </div>
                  <div className="text-sm font-medium mt-1">{r.title}</div>
                  <pre className="mt-1 text-[11px] text-ink-600 whitespace-pre-wrap font-mono">
                    {r.body_markdown}
                  </pre>
                </li>
              ))}
            </ul>
          </div>
        )}
    </SidePanel>
  );
}

// ----- FIRE calculator -----------------------------------------------------

const CATS: { key: keyof FireSettings; label: string; hint: string }[] = [
  { key: "growth_bank", label: accountCategoryLabel("bank"), hint: "checking, savings, CDs" },
  { key: "growth_investment", label: accountCategoryLabel("investment"), hint: "taxable brokerage, bonds" },
  { key: "growth_tax_advantaged", label: accountCategoryLabel("tax_advantaged"), hint: "401k, IRA, 529, HSA" },
  { key: "growth_nonsense", label: accountCategoryLabel("nonsense"), hint: "crypto / wallets / misc" },
  { key: "growth_cash", label: "Cash", hint: "physical cash" },
  { key: "growth_credit", label: "Credit / Liability", hint: "debt growth (usually 0)" },
];

function FireCalculator() {
  const qc = useQueryClient();
  const settingsQ = useQuery({
    queryKey: ["fire-settings"],
    queryFn: () => api.get<FireSettings>("/api/analytics/fire/settings"),
  });
  const projectionQ = useQuery({
    queryKey: ["fire-projection"],
    queryFn: () => api.get<FireProjection>("/api/analytics/fire/projection?max_years=60"),
  });

  const [form, setForm] = useState<FireSettings | null>(null);
  // Hydrate the form whenever the server's settings load; never overwrite
  // user edits in flight.
  useEffect(() => {
    if (settingsQ.data && form === null) setForm(settingsQ.data);
  }, [settingsQ.data, form]);

  const save = useMutation({
    mutationFn: (s: FireSettings) =>
      fetch("/api/analytics/fire/settings", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          growth_bank: s.growth_bank,
          growth_investment: s.growth_investment,
          growth_tax_advantaged: s.growth_tax_advantaged,
          growth_nonsense: s.growth_nonsense,
          growth_cash: s.growth_cash,
          growth_credit: s.growth_credit,
          annual_retirement_spending: s.annual_retirement_spending,
          withdrawal_rate: s.withdrawal_rate,
        }),
      }).then((r) => r.json()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["fire-settings"] });
      qc.invalidateQueries({ queryKey: ["fire-projection"] });
    },
  });

  const projection = projectionQ.data;
  const target = projection ? num(projection.target_total) : 0;
  const yearsToRetire =
    projection?.retirement_year !== null && projection?.retirement_year !== undefined
      ? projection.retirement_year - new Date().getFullYear()
      : null;

  const chartData =
    projection?.years.map((y) => ({
      year: y.year,
      total: num(y.total),
      target,
    })) ?? [];

  return (
    <div className="card p-4">
      <div className="flex items-baseline justify-between mb-3">
        <div>
          <div className="text-sm font-medium">FIRE projection</div>
          <div className="text-xs text-ink-500 mt-0.5">
            Real (inflation-adjusted) growth rates. Compounds your latest NLV; no contributions modeled.
          </div>
        </div>
        {projection && (
          <div className="text-right">
            {projection.retirement_year ? (
              <>
                <div className="text-2xl font-semibold text-good-600 tabular">{projection.retirement_year}</div>
                <div className="text-xs text-ink-500">
                  in {yearsToRetire} year{yearsToRetire === 1 ? "" : "s"} · target {currency(projection.target_total)}
                </div>
              </>
            ) : (
              <>
                <div className="text-2xl font-semibold text-bad-600">never</div>
                <div className="text-xs text-ink-500">
                  in 60 yrs · target {currency(projection.target_total)}
                </div>
              </>
            )}
          </div>
        )}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_2fr] gap-4">
        <div className="space-y-3">
          <div>
            <div className="label mb-1">Annual retirement spending (today's $)</div>
            <input
              type="number"
              step="1000"
              className="input tabular text-right"
              value={form?.annual_retirement_spending ?? ""}
              onChange={(e) => form && setForm({ ...form, annual_retirement_spending: e.target.value })}
            />
          </div>
          <div>
            <div className="label mb-1">Withdrawal rate</div>
            <div className="flex items-center gap-2">
              <input
                type="number"
                step="0.0025"
                className="input tabular text-right max-w-[6rem]"
                value={form?.withdrawal_rate ?? ""}
                onChange={(e) => form && setForm({ ...form, withdrawal_rate: e.target.value })}
              />
              <span className="text-xs text-ink-500">e.g. 0.04 = 4% rule</span>
            </div>
          </div>
          <div className="border-t border-ink-100 pt-3 space-y-2">
            <div className="label">Real growth rate by category</div>
            {CATS.map((c) => (
              <label key={c.key} className="flex items-center gap-2">
                <span className="text-sm w-32">{c.label}</span>
                <input
                  type="number"
                  step="0.005"
                  className="input tabular text-right max-w-[5rem]"
                  value={(form as any)?.[c.key] ?? ""}
                  onChange={(e) => form && setForm({ ...form, [c.key]: e.target.value })}
                />
                <span className="text-xs text-ink-400">{c.hint}</span>
              </label>
            ))}
          </div>
          <button
            className="btn-primary"
            onClick={() => form && save.mutate(form)}
            disabled={!form || save.isPending}
          >
            Save & re-project
          </button>
        </div>

        <div className="space-y-3">
          <div className="h-64">
            {chartData.length > 0 ? (
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={chartData} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
                  <defs>
                    <linearGradient id="gFire" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#22a559" stopOpacity={0.4} />
                      <stop offset="100%" stopColor="#22a559" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid stroke="#eceef2" vertical={false} />
                  <XAxis dataKey="year" tick={{ fontSize: 12 }} stroke="#7a8392" />
                  <YAxis
                    tickFormatter={(v) => compactCurrency(v)}
                    tick={{ fontSize: 12 }}
                    stroke="#7a8392"
                    width={70}
                  />
                  <Tooltip formatter={(v: number) => currency(v)} />
                  <ReferenceLine y={target} stroke="#dc2a3c" strokeDasharray="3 3" label={{ value: "target", fill: "#dc2a3c", fontSize: 11, position: "left" }} />
                  {projection?.retirement_year && (
                    <ReferenceLine
                      x={projection.retirement_year}
                      stroke="#1c54e6"
                      strokeDasharray="3 3"
                      label={{ value: `${projection.retirement_year}`, fill: "#1c54e6", fontSize: 11, position: "top" }}
                    />
                  )}
                  <Area type="monotone" dataKey="total" stroke="#22a559" fill="url(#gFire)" strokeWidth={2} />
                </AreaChart>
              </ResponsiveContainer>
            ) : (
              <div className="text-sm text-ink-500 italic h-full grid place-items-center">Loading…</div>
            )}
          </div>
          {projection?.notes && (
            <ul className="text-xs text-ink-500 list-disc pl-5 space-y-0.5">
              {projection.notes.map((n) => <li key={n}>{n}</li>)}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
