import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { invalidateSnapshotQueries, invalidateTxQueries } from "../api/invalidate";
import type { Account, ImportBatch, ImportPreview, StagedApplyResult, StagedEntry } from "../api/types";
import { currency, dateLabel } from "../lib/fmt";
import clsx from "clsx";

export function Import() {
  const qc = useQueryClient();
  const accounts = useQuery({ queryKey: ["accounts"], queryFn: () => api.get<Account[]>("/api/accounts") });
  const batches = useQuery({ queryKey: ["batches"], queryFn: () => api.get<ImportBatch[]>("/api/imports") });
  const staged = useQuery({
    queryKey: ["staged-imports"],
    queryFn: () => api.get<StagedEntry[]>("/api/imports/staged"),
  });
  const [stagedResult, setStagedResult] = useState<StagedApplyResult | null>(null);

  const stagedDismissMut = useMutation({
    mutationFn: ({ sha256s, dismissed }: { sha256s: string[]; dismissed: boolean }) =>
      api.post<{ dismissed: number; changed: number }>("/api/imports/staged/dismiss", {
        sha256s,
        dismissed,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["staged-imports"] });
    },
  });

  const stagedApplyMut = useMutation({
    mutationFn: (sha256s: string[]) =>
      api.post<StagedApplyResult>("/api/imports/staged/apply", { sha256s }),
    onSuccess: (result) => {
      setStagedResult(result);
      qc.invalidateQueries({ queryKey: ["staged-imports"] });
      qc.invalidateQueries({ queryKey: ["batches"] });
      invalidateTxQueries(qc);
      invalidateSnapshotQueries(qc); // balances files merge into snapshots
    },
  });

  const [file, setFile] = useState<File | null>(null);
  const [localPath, setLocalPath] = useState("");
  const [accountId, setAccountId] = useState<number | "">("");
  const [importerName, setImporterName] = useState<string>("");
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [skipDuplicates, setSkipDuplicates] = useState(true);

  const previewMut = useMutation({
    mutationFn: async () => {
      if (!accountId) throw new Error("account required");
      if (localPath.trim()) {
        return api.post<ImportPreview>("/api/imports/preview-path", {
          path: localPath.trim(),
          account_id: accountId,
          importer_name: importerName || null,
        });
      }
      if (!file) throw new Error("file or local path required");
      const fd = new FormData();
      fd.append("file", file);
      fd.append("account_id", String(accountId));
      if (importerName) fd.append("importer_name", importerName);
      return api.postForm<ImportPreview>("/api/imports/preview", fd);
    },
    onSuccess: (p) => setPreview(p),
  });

  const applyMut = useMutation({
    mutationFn: () =>
      api.post<ImportBatch>("/api/imports/apply", {
        ...preview,
        skip_duplicates: skipDuplicates,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["batches"] });
      invalidateTxQueries(qc);
      setPreview(null);
      setFile(null);
      setLocalPath("");
    },
  });

  const rollbackMut = useMutation({
    mutationFn: (id: number) => api.post(`/api/imports/${id}/rollback`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["batches"] });
      invalidateTxQueries(qc);
    },
  });

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold tracking-tight">Import</h1>
      <StagedImportsPanel
        entries={staged.data ?? []}
        result={stagedResult}
        pending={stagedApplyMut.isPending}
        error={stagedApplyMut.isError ? String((stagedApplyMut.error as Error).message) : null}
        onImport={(sha256s) => stagedApplyMut.mutate(sha256s)}
          onDismiss={(sha256s, dismissed) => stagedDismissMut.mutate({ sha256s, dismissed })}
      />
      <ImportUploadPanel
        accounts={accounts.data ?? []}
        accountId={accountId}
        importerName={importerName}
        file={file}
        localPath={localPath}
        pending={previewMut.isPending}
        error={previewMut.isError ? String((previewMut.error as Error).message) : null}
        onAccountId={setAccountId}
        onImporterName={setImporterName}
        onFile={setFile}
        onLocalPath={setLocalPath}
        onPreview={() => previewMut.mutate()}
      />
      <ImportPreviewPanel
        preview={preview}
        skipDuplicates={skipDuplicates}
        pending={applyMut.isPending}
        error={applyMut.isError ? String((applyMut.error as Error).message) : null}
        onSkipDuplicates={setSkipDuplicates}
        onApply={() => applyMut.mutate()}
        onDiscard={() => setPreview(null)}
      />
      <PastImportsPanel
        batches={batches.data ?? []}
        accounts={accounts.data ?? []}
        onRollback={(id) => rollbackMut.mutate(id)}
      />
    </div>
  );
}

const STAGED_STATUS_LABEL: Record<StagedEntry["status"], string> = {
  new: "ready",
  imported: "imported",
  empty: "no rows",
  other_profile: "other profile",
  unknown_account: "unknown account",
  missing_file: "file missing",
  invalid: "invalid",
  dismissed: "ignored",
};

function StagedImportsPanel({
  entries,
  result,
  pending,
  error,
  onImport,
  onDismiss,
}: {
  entries: StagedEntry[];
  result: StagedApplyResult | null;
  pending: boolean;
  error: string | null;
  onImport: (sha256s: string[]) => void;
  onDismiss: (sha256s: string[], dismissed: boolean) => void;
}) {
  // Fully hidden until a connector has staged something — a CSV-only setup
  // never sees this panel.
  if (!entries.length) return null;
  // Already-imported and empty files stay off the page: they'd duplicate
  // the Past imports table. Problems (unknown account, missing file, …)
  // stay visible so an unimportable file is never silently invisible.
  const actionable = entries.filter((entry) => entry.status !== "imported" && entry.status !== "empty");
  const ready = actionable.filter((entry) => entry.status === "new");
  const doneCount = entries.length - actionable.length;
  const lastFetched = entries
    .map((entry) => entry.downloaded_at)
    .filter(Boolean)
    .sort()
    .at(-1);
  if (!actionable.length) {
    return (
      <div className="card p-4 flex items-baseline justify-between">
        <div className="text-sm font-medium">Staged from connectors</div>
        <div className="text-xs text-ink-500">
          all {doneCount} staged file(s) imported
          {lastFetched ? ` · last fetch ${dateLabel(lastFetched)}` : ""} · run{" "}
          <span className="font-mono">make fetch</span> to pull fresh data
        </div>
      </div>
    );
  }
  return (
    <div className="card p-4">
      <div className="flex items-baseline justify-between mb-3">
        <div className="text-sm font-medium">Staged from connectors</div>
        <div className="text-xs text-ink-500">
          {lastFetched ? `last fetch ${dateLabel(lastFetched)} · ` : ""}
          {ready.length ? `${ready.length} file(s) ready` : "nothing importable"}
          {doneCount ? ` · ${doneCount} already imported` : ""}
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-ink-50">
            <tr>
              <th className="px-3 py-2 text-left">File</th>
              <th className="px-3 py-2 text-left">Source</th>
              <th className="px-3 py-2 text-left">Account</th>
              <th className="px-3 py-2 text-right">Rows</th>
              <th className="px-3 py-2 text-left">Status</th>
              <th className="px-3 py-2"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {actionable.map((entry) => (
              <StagedImportRow key={entry.sha256} entry={entry} pending={pending} onImport={onImport} onDismiss={onDismiss} />
            ))}
          </tbody>
        </table>
      </div>
      <div className="mt-3 flex items-center gap-2">
        <button
          className="btn-primary"
          disabled={!ready.length || pending}
          onClick={() => onImport([])}
        >
          Import all new ({ready.length})
        </button>
        {error && <span className="text-sm text-bad-600">{error}</span>}
      </div>
      {result && <StagedApplySummary result={result} />}
    </div>
  );
}

function StagedImportRow({
  entry,
  pending,
  onImport,
  onDismiss,
}: {
  entry: StagedEntry;
  pending: boolean;
  onImport: (sha256s: string[]) => void;
  onDismiss: (sha256s: string[], dismissed: boolean) => void;
}) {
  const problem = ["unknown_account", "missing_file", "invalid"].includes(entry.status);
  const rows =
    entry.row_count == null
      ? "—"
      : entry.kind === "balances"
        ? `${entry.new_count} changed / ${entry.row_count}`
        : `${entry.new_count} new / ${entry.row_count}`;
  return (
    <tr className={clsx("table-row-hover", entry.status !== "new" && "opacity-60")}>
      <td className="px-3 py-1.5 font-mono text-xs" title={entry.path}>
        {entry.file_name}
      </td>
      <td className="px-3 py-1.5">{entry.source}</td>
      <td className="px-3 py-1.5">
        {entry.kind === "balances"
          ? `balances${entry.as_of ? ` · as of ${entry.as_of}` : ""}`
          : (entry.account_name ?? (entry.account_id != null ? `#${entry.account_id}` : "—"))}
      </td>
      <td className="px-3 py-1.5 text-right tabular">{rows}</td>
      <td
        className={clsx("px-3 py-1.5", entry.status === "new" && "text-good-600 font-medium", problem && "text-bad-600")}
        title={entry.detail ?? (entry.status === "other_profile" && entry.profile ? `staged for profile "${entry.profile}"` : undefined)}
      >
        {STAGED_STATUS_LABEL[entry.status] ?? entry.status}
      </td>
      <td className="px-3 py-1.5 text-right whitespace-nowrap">
        {entry.status === "new" && (
          <>
            <button className="btn-ghost text-xs" disabled={pending} onClick={() => onImport([entry.sha256])}>
              Import
            </button>
            <button
              className="btn-ghost text-xs text-ink-500"
              disabled={pending}
              title="Never import this file. It stays on disk; you can put it back."
              onClick={() => onDismiss([entry.sha256], true)}
            >
              Ignore
            </button>
          </>
        )}
        {entry.status === "dismissed" && (
          <button className="btn-ghost text-xs" disabled={pending} onClick={() => onDismiss([entry.sha256], false)}>
            Un-ignore
          </button>
        )}
      </td>
    </tr>
  );
}

function StagedApplySummary({ result }: { result: StagedApplyResult }) {
  const imported = result.outcomes.filter((o) => o.status === "imported");
  const rows = imported.reduce((sum, o) => sum + (o.rows_applied ?? 0), 0);
  const dups = imported.reduce((sum, o) => sum + (o.duplicates ?? 0), 0);
  const rest = result.outcomes.filter((o) => o.status !== "imported");
  return (
    <div className="mt-2 text-xs text-ink-600 space-y-0.5">
      <div>
        Imported {imported.length} file(s) · {rows.toLocaleString()} rows applied
        {dups ? ` · ${dups.toLocaleString()} duplicates skipped` : ""}
        {result.backup_name ? ` · backup ${result.backup_name}` : ""}
      </div>
      {rest.map((o) => (
        <div key={o.sha256}>
          {o.file_name}: {o.status.replace(/_/g, " ")}
          {o.detail ? ` — ${o.detail}` : ""}
        </div>
      ))}
    </div>
  );
}

function ImportUploadPanel({
  accounts,
  accountId,
  importerName,
  file,
  localPath,
  pending,
  error,
  onAccountId,
  onImporterName,
  onFile,
  onLocalPath,
  onPreview,
}: {
  accounts: Account[];
  accountId: number | "";
  importerName: string;
  file: File | null;
  localPath: string;
  pending: boolean;
  error: string | null;
  onAccountId: (value: number | "") => void;
  onImporterName: (value: string) => void;
  onFile: (value: File | null) => void;
  onLocalPath: (value: string) => void;
  onPreview: () => void;
}) {
  const hasSource = Boolean(file || localPath.trim());
  return (
    <div className="card p-4">
      <div className="text-sm font-medium mb-3">Upload a CSV or XLSX</div>
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-3">
        <label className="block">
          <div className="label mb-1">Account</div>
          <select className="input" value={accountId} onChange={(e) => onAccountId(e.target.value ? parseInt(e.target.value, 10) : "")}>
            <option value="">— select —</option>
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>{account.name}</option>
            ))}
          </select>
        </label>
        <label className="block">
          <div className="label mb-1">Importer (optional)</div>
          <select className="input" value={importerName} onChange={(e) => onImporterName(e.target.value)}>
            <option value="">auto-detect</option>
            <option value="chase_credit">Chase Credit Card</option>
            <option value="wells_fargo_checking">Wells Fargo Checking</option>
            <option value="amex">Amex</option>
            <option value="contribution_history">Fidelity Charitable Contribution History</option>
          </select>
        </label>
        <label className="block">
          <div className="label mb-1">File</div>
          <input
            type="file"
            accept=".csv,.CSV,.xlsx,.XLSX,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            className="input"
            onChange={(e) => onFile(e.target.files?.[0] ?? null)}
          />
        </label>
        <label className="block">
          <div className="label mb-1">Local path</div>
          <input
            className="input"
            value={localPath}
            onChange={(e) => onLocalPath(e.target.value)}
            placeholder="/path/to/export.csv"
          />
        </label>
      </div>
      <div className="mt-3 flex items-center gap-2">
        <button className="btn-primary" disabled={!hasSource || !accountId || pending} onClick={onPreview}>Preview</button>
        {error && <span className="text-sm text-bad-600">{error}</span>}
      </div>
    </div>
  );
}

function ImportPreviewPanel({
  preview,
  skipDuplicates,
  pending,
  error,
  onSkipDuplicates,
  onApply,
  onDiscard,
}: {
  preview: ImportPreview | null;
  skipDuplicates: boolean;
  pending: boolean;
  error: string | null;
  onSkipDuplicates: (value: boolean) => void;
  onApply: () => void;
  onDiscard: () => void;
}) {
  if (!preview) return null;
  return (
    <div className="card p-4">
      <div className="flex items-baseline justify-between mb-3">
        <div className="text-sm font-medium">
          Preview: {preview.rows.length} rows · matched importer <span className="font-mono">{preview.importer_name}</span>
        </div>
        <label className="text-xs text-ink-600 flex items-center gap-1">
          <input type="checkbox" checked={skipDuplicates} onChange={(e) => onSkipDuplicates(e.target.checked)} />
          skip {preview.rows.filter((row) => row.is_duplicate).length} duplicate(s)
        </label>
      </div>
      <ImportPreviewTable preview={preview} />
      <div className="mt-3 flex items-center gap-2">
        <button className="btn-primary" disabled={pending} onClick={onApply}>Apply import</button>
        <button className="btn" onClick={onDiscard}>Discard</button>
      </div>
      {error && <div className="mt-2 text-sm text-bad-600">{error}</div>}
    </div>
  );
}

function ImportPreviewTable({ preview }: { preview: ImportPreview }) {
  return (
    <div className="overflow-x-auto max-h-[60vh] border border-ink-200 rounded-md">
      <table className="w-full text-xs tabular">
        <thead className="bg-ink-50 sticky top-0">
          <tr>
            <th className="px-2 py-1.5 text-left">Date</th>
            <th className="px-2 py-1.5 text-left">Description</th>
            <th className="px-2 py-1.5 text-left">Merchant</th>
            <th className="px-2 py-1.5 text-left">Kind</th>
            <th className="px-2 py-1.5 text-right">Amount</th>
            <th className="px-2 py-1.5 text-left">Dup?</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-ink-100">
          {preview.rows.map((row) => (
            <tr key={row.row_index} className={clsx(row.is_duplicate && "opacity-50")}>
              <td className="px-2 py-1">{row.date}</td>
              <td className="px-2 py-1 max-w-md truncate">{row.description_raw}</td>
              <td className="px-2 py-1">{row.merchant ?? "—"}</td>
              <td className="px-2 py-1">{row.suggested_kind}</td>
              <td className={clsx("px-2 py-1 text-right", Number(row.amount) < 0 ? "text-bad-600" : "text-good-600")}>
                {currency(row.amount)}
              </td>
              <td className="px-2 py-1">{row.is_duplicate ? "yes" : ""}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PastImportsPanel({
  batches,
  accounts,
  onRollback,
}: {
  batches: ImportBatch[];
  accounts: Account[];
  onRollback: (id: number) => void;
}) {
  return (
    <div className="card p-4">
      <div className="flex items-baseline justify-between mb-3">
        <div className="text-sm font-medium">Past imports</div>
        <div className="text-xs text-ink-500">
          {batches.length} batches · duplicate rows are detected by account, date, amount, and normalized description.
        </div>
      </div>
      {batches.length ? (
        <PastImportsTable batches={batches} accounts={accounts} onRollback={onRollback} />
      ) : (
        <div className="text-sm text-ink-500 italic">No imports yet.</div>
      )}
    </div>
  );
}

function PastImportsTable({
  batches,
  accounts,
  onRollback,
}: {
  batches: ImportBatch[];
  accounts: Account[];
  onRollback: (id: number) => void;
}) {
  return (
    <table className="w-full text-sm">
      <thead className="bg-ink-50">
        <tr>
          <th className="px-3 py-2 text-left">When</th>
          <th className="px-3 py-2 text-left">File</th>
          <th className="px-3 py-2 text-left">Account</th>
          <th className="px-3 py-2 text-right" title="Imported as new transactions">Applied</th>
          <th className="px-3 py-2 text-right" title="Rows that matched an existing transaction (skipped)">Skipped&nbsp;dups</th>
          <th className="px-3 py-2 text-right" title="Total rows in the source file">Total&nbsp;rows</th>
          <th className="px-3 py-2 text-left">Status</th>
          <th className="px-3 py-2"></th>
        </tr>
      </thead>
      <tbody className="divide-y divide-ink-100">
        {batches.map((batch) => (
          <PastImportRow
            key={batch.id}
            batch={batch}
            account={accounts.find((account) => account.id === batch.account_id)}
            onRollback={onRollback}
          />
        ))}
      </tbody>
    </table>
  );
}

function PastImportRow({
  batch,
  account,
  onRollback,
}: {
  batch: ImportBatch;
  account?: Account;
  onRollback: (id: number) => void;
}) {
  return (
    <tr className="table-row-hover">
      <td className="px-3 py-1.5">{dateLabel(batch.imported_at)}</td>
      <td className="px-3 py-1.5 font-mono text-xs">{batch.source_filename}</td>
      <td className="px-3 py-1.5">{account?.name ?? `#${batch.account_id}`}</td>
      <td className="px-3 py-1.5 text-right tabular font-medium">{batch.row_count_applied.toLocaleString()}</td>
      <td className="px-3 py-1.5 text-right tabular text-ink-500">{batch.row_count_duplicate.toLocaleString()}</td>
      <td className="px-3 py-1.5 text-right tabular text-ink-500">{batch.row_count_total.toLocaleString()}</td>
      <td className="px-3 py-1.5">{batch.status}</td>
      <td className="px-3 py-1.5 text-right">
        {batch.status === "applied" && (
          <button
            className="btn-ghost text-xs text-bad-600"
            onClick={() => {
              if (confirm(`Rollback ${batch.row_count_applied} transactions from ${batch.source_filename}?`)) onRollback(batch.id);
            }}
          >
            Rollback
          </button>
        )}
      </td>
    </tr>
  );
}
