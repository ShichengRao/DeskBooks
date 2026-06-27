import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import clsx from "clsx";
import { api } from "../api/client";
import { invalidateTxQueries } from "../api/invalidate";
import { Field } from "../components/Field";
import { SidePanel } from "../components/SidePanel";
import { ALL_KINDS } from "../lib/kinds";
import type { Account, Category, Rule, RuleCoverage, RuleProposal, RuleProposalBacktestInput, TransactionKind } from "../api/types";
import { transactionKindLabel } from "../lib/labels";
import { currency, dateLabel } from "../lib/fmt";

export function Rules() {
  const qc = useQueryClient();
  const rules = useQuery({ queryKey: ["rules"], queryFn: () => api.get<Rule[]>("/api/rules") });
  const categories = useQuery({ queryKey: ["categories"], queryFn: () => api.get<Category[]>("/api/categories") });
  const accounts = useQuery({ queryKey: ["accounts"], queryFn: () => api.get<Account[]>("/api/accounts") });
  const coverage = useQuery({
    queryKey: ["rule-coverage"],
    queryFn: () => api.get<RuleCoverage>("/api/rules/coverage"),
  });

  const [editing, setEditing] = useState<Rule | "new" | null>(null);
  const [showProposals, setShowProposals] = useState(false);
  const [expandedProposal, setExpandedProposal] = useState<string | null>(null);
  const [selectedRuleIds, setSelectedRuleIds] = useState<Set<number>>(new Set());
  const proposals = useQuery({
    queryKey: ["rule-proposals"],
    queryFn: () => api.get<RuleProposal[]>("/api/rules/proposals?min_support=3&limit=50"),
    enabled: showProposals,
  });

  const categoryById = useMemo(
    () => Object.fromEntries((categories.data ?? []).map((c) => [c.id, c])),
    [categories.data],
  );
  const visibleRules = (rules.data ?? []).filter((r) => r.is_active);
  const selectedVisibleCount = visibleRules.filter((r) => selectedRuleIds.has(r.id)).length;
  const allVisibleSelected = visibleRules.length > 0 && selectedVisibleCount === visibleRules.length;

  const reapply = useMutation({
    mutationFn: () => api.post<{ rows_changed: number }>("/api/rules/reapply"),
    onSuccess: (data) => {
      invalidateTxQueries(qc);
      qc.invalidateQueries({ queryKey: ["rules"] });
      qc.invalidateQueries({ queryKey: ["rule-coverage"] });
      qc.invalidateQueries({ queryKey: ["rule-proposals"] });
      alert(`Reapplied rules. ${data.rows_changed} transaction(s) changed.`);
    },
  });

  const save = useMutation({
    mutationFn: async (rule: Partial<Rule>) => {
      if (editing === "new" || editing === null) {
        return api.post<Rule>("/api/rules", rule);
      }
      return api.patch<Rule>(`/api/rules/${(editing as Rule).id}`, rule);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["rules"] });
      qc.invalidateQueries({ queryKey: ["rule-coverage"] });
      qc.invalidateQueries({ queryKey: ["rule-proposals"] });
      setEditing(null);
    },
  });

  const del = useMutation({
    mutationFn: (id: number) => api.del(`/api/rules/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["rules"] });
      qc.invalidateQueries({ queryKey: ["rule-coverage"] });
      qc.invalidateQueries({ queryKey: ["rule-proposals"] });
      setSelectedRuleIds(new Set());
    },
  });

  const bulkDelete = useMutation({
    mutationFn: (ids: number[]) => api.post<{ deleted: number }>("/api/rules/bulk-delete", { ids }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["rules"] });
      qc.invalidateQueries({ queryKey: ["rule-coverage"] });
      qc.invalidateQueries({ queryKey: ["rule-proposals"] });
      setSelectedRuleIds(new Set());
    },
  });

  const promote = useMutation({
    mutationFn: (proposal: RuleProposal) =>
      api.post<Rule>("/api/rules", {
        name: proposal.name.slice(0, 120),
        priority: 100,
        is_active: true,
        match_account_id: proposal.match_account_id,
        match_description_pattern: proposal.match_description_pattern,
        set_category_id: proposal.set_category_id,
        set_kind: proposal.set_kind,
        set_merchant: proposal.set_merchant,
        notes: `Promoted from proposal. Backtest: ${proposal.correct_matches}/${proposal.total_user_labeled_matches} labeled matches correct (${(proposal.accuracy * 100).toFixed(1)}%). Matches ${proposal.all_transaction_matches} total transactions (${proposal.all_coverage_percent.toFixed(1)}%).`,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["rules"] });
      qc.invalidateQueries({ queryKey: ["rule-proposals"] });
      qc.invalidateQueries({ queryKey: ["rule-coverage"] });
    },
  });

  const rejectProposal = useMutation({
    mutationFn: (proposal: RuleProposalBacktestInput) =>
      api.post<{ status: string; created: boolean }>("/api/rules/proposals/reject", proposal),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["rule-proposals"] });
    },
  });

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Rules</h1>
        <div className="flex items-center gap-2">
          <label className="flex items-center gap-1.5 text-sm text-ink-600">
            <input
              type="checkbox"
              checked={showProposals}
              onChange={(e) => setShowProposals(e.target.checked)}
            />
            show proposals
          </label>
          <button className="btn" onClick={() => reapply.mutate()} disabled={reapply.isPending}>
            Re-apply to unreviewed
          </button>
          <button className="btn-primary" onClick={() => setEditing("new")}>+ New rule</button>
        </div>
      </div>

      <RuleCoverageSummary coverage={coverage.data ?? null} loading={coverage.isLoading} />

      <div className={clsx("grid gap-4", showProposals ? "grid-cols-1 xl:grid-cols-2" : "grid-cols-1")}>
      <div className="card overflow-hidden">
        <div className="px-3 py-2 border-b border-ink-100 flex items-baseline justify-between">
          <div className="text-sm font-medium">Active rules</div>
          <div className="flex items-center gap-2">
            {selectedRuleIds.size > 0 && (
              <>
                <span className="text-xs text-ink-500">{selectedRuleIds.size} selected</span>
                <button
                  className="btn-danger text-xs"
                  onClick={() => {
                    if (confirm(`Delete ${selectedRuleIds.size} selected rule(s)?`)) {
                      bulkDelete.mutate(Array.from(selectedRuleIds));
                    }
                  }}
                  disabled={bulkDelete.isPending}
                >
                  Delete selected
                </button>
                <button className="btn-ghost text-xs" onClick={() => setSelectedRuleIds(new Set())}>
                  Clear
                </button>
              </>
            )}
            <div className="text-xs text-ink-500">{visibleRules.length} active</div>
          </div>
        </div>
        <table className="w-full text-sm">
          <thead className="bg-ink-50 text-left">
            <tr>
              <th className="px-3 py-2 w-8">
                <input
                  type="checkbox"
                  checked={allVisibleSelected}
                  onChange={(e) =>
                    setSelectedRuleIds(new Set(e.target.checked ? visibleRules.map((r) => r.id) : []))
                  }
                />
              </th>
              <th className="px-3 py-2 w-16">Prio</th>
              <th className="px-3 py-2">Name</th>
              <th className="px-3 py-2">Pattern</th>
              <th className="px-3 py-2">Sets</th>
              <th className="px-3 py-2 w-24 text-right">Applied</th>
              <th className="px-3 py-2 w-20"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {visibleRules.map((r) => {
              const cat = r.set_category_id ? categoryById[r.set_category_id] : null;
              return (
                <tr key={r.id} className="table-row-hover">
                  <td className="px-3 py-1.5">
                    <input
                      type="checkbox"
                      checked={selectedRuleIds.has(r.id)}
                      onChange={() => {
                        setSelectedRuleIds((prev) => {
                          const next = new Set(prev);
                          if (next.has(r.id)) next.delete(r.id);
                          else next.add(r.id);
                          return next;
                        });
                      }}
                    />
                  </td>
                  <td className="px-3 py-1.5 tabular">{r.priority}</td>
                  <td className="px-3 py-1.5 font-medium">{r.name}</td>
                  <td className="px-3 py-1.5 font-mono text-xs text-ink-700 max-w-md truncate">
                    {r.match_description_pattern ?? "—"}
                  </td>
                  <td className="px-3 py-1.5 text-xs">
                    <div className="flex flex-wrap items-center gap-1">
                      {cat && <span className="pill bg-brand-100 text-brand-700">{cat.name}</span>}
                      {cat && r.set_kind && <span className="text-ink-400">·</span>}
                      {r.set_kind && <span className="pill bg-ink-200/60 text-ink-700">{transactionKindLabel(r.set_kind)}</span>}
                    </div>
                  </td>
                  <td className="px-3 py-1.5 text-right tabular text-xs">
                    <span
                      className={clsx(
                        "font-semibold",
                        r.apply_count === 0 ? "text-ink-400" : "text-good-600",
                      )}
                      title={
                        r.apply_count === 0
                          ? "This rule has never matched a transaction."
                          : `Matched ${r.apply_count} transactions.`
                      }
                    >
                      {r.apply_count.toLocaleString()}×
                    </span>
                    <div className="text-[10px] text-ink-500">
                      {r.last_applied_at ? `last: ${dateLabel(r.last_applied_at)}` : "never run"}
                    </div>
                  </td>
                  <td className="px-3 py-1.5 text-right">
                    <button className="btn-ghost text-xs" onClick={() => setEditing(r)}>Edit</button>
                    <button
                      className="btn-ghost text-xs text-bad-600"
                      onClick={() => { if (confirm(`Delete rule ${r.name}?`)) del.mutate(r.id); }}
                    >
                      ×
                    </button>
                  </td>
                </tr>
              );
            })}
            {visibleRules.length === 0 && (
              <tr>
                <td colSpan={7} className="p-8 text-center text-sm text-ink-500">
                  No active rules yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {showProposals && (
        <RuleProposals
          proposals={proposals.data ?? []}
          loading={proposals.isLoading}
          categories={categories.data ?? []}
          categoryById={categoryById}
          expandedKey={expandedProposal}
          onToggleExpanded={(key) => setExpandedProposal(expandedProposal === key ? null : key)}
          onPromote={(proposal) => promote.mutate(proposal)}
          onReject={(proposal) => rejectProposal.mutate(proposal)}
          promoting={promote.isPending}
          rejecting={rejectProposal.isPending}
        />
      )}
      </div>

      {editing !== null && (
        <RuleEditor
          rule={editing === "new" ? null : editing}
          categories={categories.data ?? []}
          accounts={accounts.data ?? []}
          onClose={() => setEditing(null)}
          onSave={(r) => save.mutate(r)}
        />
      )}
    </div>
  );
}

function RuleCoverageSummary({
  coverage,
  loading,
}: {
  coverage: RuleCoverage | null;
  loading: boolean;
}) {
  const accuracyLabel =
    coverage?.labeled_accuracy == null
      ? "No labeled matches yet"
      : `${(coverage.labeled_accuracy * 100).toFixed(1)}% labeled accuracy`;

  return (
    <div className="card p-3">
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
        <div>
          <div className="label">Active rules</div>
          <div className="text-xl font-semibold tabular">
            {loading ? "…" : (coverage?.active_rule_count ?? 0).toLocaleString()}
          </div>
        </div>
        <div>
          <div className="label">Total coverage</div>
          <div className="text-xl font-semibold tabular">
            {loading ? "…" : `${(coverage?.coverage_percent ?? 0).toFixed(1)}%`}
          </div>
          <div className="text-xs text-ink-500 tabular">
            {(coverage?.matched_transactions ?? 0).toLocaleString()} / {(coverage?.total_transactions ?? 0).toLocaleString()} tx
          </div>
        </div>
        <div>
          <div className="label">Backtest</div>
          <div className="text-xl font-semibold tabular">{loading ? "…" : accuracyLabel}</div>
          <div className="text-xs text-ink-500 tabular">
            {(coverage?.labeled_correct_matches ?? 0).toLocaleString()} correct · {(coverage?.labeled_incorrect_matches ?? 0).toLocaleString()} conflicts
          </div>
        </div>
        <div>
          <div className="label">Labeled matched</div>
          <div className="text-xl font-semibold tabular">
            {loading ? "…" : (coverage?.labeled_matched_transactions ?? 0).toLocaleString()}
          </div>
          <div className="text-xs text-ink-500 tabular">
            of {(coverage?.labeled_transactions ?? 0).toLocaleString()} labeled tx
          </div>
        </div>
      </div>
    </div>
  );
}

function RuleProposals({
  proposals,
  loading,
  categories,
  categoryById,
  expandedKey,
  onToggleExpanded,
  onPromote,
  onReject,
  promoting,
  rejecting,
}: {
  proposals: RuleProposal[];
  loading: boolean;
  categories: Category[];
  categoryById: Record<number, Category>;
  expandedKey: string | null;
  onToggleExpanded: (key: string) => void;
  onPromote: (proposal: RuleProposal) => void;
  onReject: (proposal: RuleProposalBacktestInput) => void;
  promoting: boolean;
  rejecting: boolean;
}) {
  return (
    <div className="card overflow-hidden">
      <div className="px-3 py-2 border-b border-ink-100 flex items-baseline justify-between">
        <div>
          <div className="text-sm font-medium">Rule proposals</div>
          <div className="text-xs text-ink-500">
            Generated from manually categorized transactions; promote the ones you trust.
          </div>
        </div>
        <div className="text-xs text-ink-500">{proposals.length} candidates</div>
      </div>
      {loading ? (
        <div className="p-8 text-center text-sm text-ink-500">Generating proposals…</div>
      ) : proposals.length === 0 ? (
        <div className="p-8 text-center text-sm text-ink-500">
          No proposals yet. Categorize more repeated merchants, then come back here.
        </div>
      ) : (
        <div className="divide-y divide-ink-100">
          {proposals.map((p) => (
            <RuleProposalCard
              key={p.key}
              proposal={p}
              categories={categories}
              categoryById={categoryById}
              expanded={expandedKey === p.key}
              onToggleExpanded={() => onToggleExpanded(p.key)}
              onPromote={onPromote}
              onReject={onReject}
              promoting={promoting}
              rejecting={rejecting}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function RuleProposalCard({
  proposal,
  categories,
  categoryById,
  expanded,
  onToggleExpanded,
  onPromote,
  onReject,
  promoting,
  rejecting,
}: {
  proposal: RuleProposal;
  categories: Category[];
  categoryById: Record<number, Category>;
  expanded: boolean;
  onToggleExpanded: () => void;
  onPromote: (proposal: RuleProposal) => void;
  onReject: (proposal: RuleProposalBacktestInput) => void;
  promoting: boolean;
  rejecting: boolean;
}) {
  const [draft, setDraft] = useState<RuleProposalBacktestInput>({
    key: proposal.key,
    name: proposal.name,
    match_description_pattern: proposal.match_description_pattern,
    match_account_id: proposal.match_account_id,
    set_category_id: proposal.set_category_id,
    set_kind: proposal.set_kind,
    set_merchant: proposal.set_merchant,
  });
  const backtest = useMutation({
    mutationFn: (body: RuleProposalBacktestInput) =>
      api.post<RuleProposal>("/api/rules/proposals/backtest", body),
  });

  useEffect(() => {
    const timer = window.setTimeout(() => {
      backtest.mutate(draft);
    }, 350);
    return () => window.clearTimeout(timer);
  }, [draft.name, draft.match_description_pattern, draft.match_account_id, draft.set_category_id, draft.set_kind, draft.set_merchant]);

  const p: RuleProposal = { ...(backtest.data ?? proposal), ...draft };
  const cat = p.set_category_id ? categoryById[p.set_category_id] : null;

  return (
    <div className="p-3">
      <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_9rem] gap-3">
        <div className="space-y-2 min-w-0">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
            <Field label="Proposal name">
              <input
                className="input"
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              />
            </Field>
            <Field label="Set category">
              <select
                className="input"
                value={draft.set_category_id ?? ""}
                onChange={(e) => {
                  const category = e.target.value ? categoryById[parseInt(e.target.value, 10)] : null;
                  setDraft({
                    ...draft,
                    set_category_id: category?.id ?? null,
                    set_kind: category ? category.kind : draft.set_kind,
                  });
                }}
              >
                <option value="">No category</option>
                {categories.map((c) => (
                  <option key={c.id} value={c.id}>{c.name} ({transactionKindLabel(c.kind)})</option>
                ))}
              </select>
            </Field>
          </div>
          <Field label="Regex">
            <input
              className="input font-mono"
              value={draft.match_description_pattern}
              onChange={(e) => setDraft({ ...draft, match_description_pattern: e.target.value })}
            />
          </Field>
          <div className="flex flex-wrap gap-1 text-xs">
            {cat && <span className="pill bg-brand-100 text-brand-700">{cat.name}</span>}
            <span className="pill bg-ink-200/60 text-ink-700">{transactionKindLabel(p.set_kind)}</span>
            {backtest.isPending && <span className="pill bg-ink-100 text-ink-500">updating</span>}
          </div>
        </div>
        <div className="text-right shrink-0">
          <div className="text-sm font-semibold tabular text-good-600">
            {(p.accuracy * 100).toFixed(1)}%
          </div>
          <div className="text-[11px] text-ink-500 tabular">
            {p.correct_matches}/{p.total_user_labeled_matches} correct
          </div>
          <div className="text-[11px] text-ink-500 tabular">
            adds {p.added_transaction_matches} tx · {p.added_coverage_percent.toFixed(1)}%
          </div>
          <div className="text-[11px] text-ink-500 tabular">
            raw {p.all_transaction_matches} tx · {p.all_coverage_percent.toFixed(1)}%
          </div>
          <div className="text-[11px] text-ink-500 tabular">
            {p.labeled_coverage_percent.toFixed(1)}% labeled
          </div>
        </div>
      </div>
      <div className="mt-2 flex items-center gap-2">
        <button
          className="btn-primary text-xs"
          onClick={() => onPromote(p)}
          disabled={promoting || backtest.isPending}
        >
          Promote
        </button>
        <button className="btn-ghost text-xs" onClick={onToggleExpanded}>
          {expanded ? "Hide breakdown" : "Breakdown"}
        </button>
        <button
          className="btn-ghost text-xs text-bad-600"
          onClick={() => onReject(draft)}
          disabled={rejecting}
        >
          Reject
        </button>
        <button className="btn-ghost text-xs" onClick={() => navigator.clipboard?.writeText(p.name)}>
          Copy name
        </button>
        <button className="btn-ghost text-xs" onClick={() => navigator.clipboard?.writeText(p.match_description_pattern)}>
          Copy regex
        </button>
      </div>
      {expanded && (
        <div className="mt-3 grid grid-cols-1 lg:grid-cols-2 gap-3">
          <div>
            <div className="label mb-1">Historical breakdown</div>
            <div className="space-y-1">
              {p.breakdown.map((b) => {
                const bCat = b.category_id ? categoryById[b.category_id] : null;
                const isWinner = b.category_id === p.set_category_id && b.kind === p.set_kind;
                return (
                  <div
                    key={`${b.category_id}-${b.kind}`}
                    className={clsx(
                      "rounded border px-2 py-1 text-xs flex items-center justify-between",
                      isWinner ? "border-good-500/30 bg-good-500/5" : "border-ink-100 bg-ink-50",
                    )}
                  >
                    <span>{bCat?.name ?? "No category"} · {transactionKindLabel(b.kind)}</span>
                    <span className="font-semibold tabular">{b.count}</span>
                  </div>
                );
              })}
            </div>
          </div>
          <div>
            <div className="label mb-1">Examples</div>
            <div className="space-y-1">
              {p.examples.map((ex) => (
                <div
                  key={ex.transaction_id}
                  className={clsx(
                    "rounded border px-2 py-1 text-xs",
                    ex.correct ? "border-good-500/20" : "border-bad-500/30 bg-bad-500/5",
                  )}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate">{ex.description}</span>
                    <span className="tabular shrink-0">{currency(ex.amount)}</span>
                  </div>
                  <div className="text-[11px] text-ink-500">
                    {dateLabel(ex.date)} · {categoryById[ex.category_id ?? -1]?.name ?? "No category"} · {transactionKindLabel(ex.kind)}
                    {!ex.correct && <span className="text-bad-600 font-medium"> · conflict</span>}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function RuleEditor({
  rule,
  categories,
  accounts,
  onClose,
  onSave,
}: {
  rule: Rule | null;
  categories: Category[];
  accounts: Account[];
  onClose: () => void;
  onSave: (r: Partial<Rule>) => void;
}) {
  const [r, setR] = useState<Partial<Rule>>(
    rule ?? {
      name: "",
      priority: 100,
      is_active: true,
      match_description_pattern: "",
      set_kind: "expense",
      set_category_id: null,
    },
  );

  return (
    <SidePanel
      title={rule ? "Edit rule" : "New rule"}
      onClose={onClose}
      onSubmit={() => onSave(r)}
      maxWidth="max-w-xl"
    >
        <div className="space-y-3">
          <Field label="Name"><input className="input" value={r.name ?? ""} onChange={(e) => setR({ ...r, name: e.target.value })} /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Priority (lower = first)"><input type="number" className="input" value={r.priority ?? 100} onChange={(e) => setR({ ...r, priority: parseInt(e.target.value, 10) })} /></Field>
            <Field label="Active"><label className="flex items-center gap-2 mt-2"><input type="checkbox" checked={r.is_active ?? true} onChange={(e) => setR({ ...r, is_active: e.target.checked })} /> active</label></Field>
          </div>
          <Field label="Match: description regex (case-insensitive)">
            <input className="input font-mono" value={r.match_description_pattern ?? ""} onChange={(e) => setR({ ...r, match_description_pattern: e.target.value })} />
          </Field>
          <Field label="Match: account (optional)">
            <select className="input" value={r.match_account_id ?? ""} onChange={(e) => setR({ ...r, match_account_id: e.target.value ? parseInt(e.target.value, 10) : null })}>
              <option value="">any account</option>
              {accounts.map((a) => (<option key={a.id} value={a.id}>{a.name}</option>))}
            </select>
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Match: amount min"><input type="number" step="0.01" className="input" value={r.match_amount_min ?? ""} onChange={(e) => setR({ ...r, match_amount_min: e.target.value || null })} /></Field>
            <Field label="Match: amount max"><input type="number" step="0.01" className="input" value={r.match_amount_max ?? ""} onChange={(e) => setR({ ...r, match_amount_max: e.target.value || null })} /></Field>
          </div>
          <Field label="Set: category">
            <select className="input" value={r.set_category_id ?? ""} onChange={(e) => setR({ ...r, set_category_id: e.target.value ? parseInt(e.target.value, 10) : null })}>
              <option value="">—</option>
              {categories.map((c) => (<option key={c.id} value={c.id}>{c.name} ({transactionKindLabel(c.kind)})</option>))}
            </select>
          </Field>
          <Field label="Set: kind">
            <select className="input" value={r.set_kind ?? ""} onChange={(e) => setR({ ...r, set_kind: e.target.value as TransactionKind || null })}>
              <option value="">—</option>
              {ALL_KINDS.map((k) => (<option key={k} value={k}>{transactionKindLabel(k)}</option>))}
            </select>
          </Field>
          <Field label="Set: merchant override"><input className="input" value={r.set_merchant ?? ""} onChange={(e) => setR({ ...r, set_merchant: e.target.value || null })} /></Field>
          <Field label="Notes"><textarea className="input min-h-[5rem]" value={r.notes ?? ""} onChange={(e) => setR({ ...r, notes: e.target.value })} /></Field>
        </div>
        <div className="sticky bottom-0 z-10 -mx-6 mt-4 flex items-center gap-2 border-t border-ink-100 bg-white px-6 py-3">
          <button type="submit" className="btn-primary">Save</button>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
        </div>
    </SidePanel>
  );
}
