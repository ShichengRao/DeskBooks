import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { invalidateTxQueries } from "../api/invalidate";
import type { Account, ImportBatch, ImportPreview } from "../api/types";
import { currency, dateLabel } from "../lib/fmt";
import clsx from "clsx";

export function Import() {
  const qc = useQueryClient();
  const accounts = useQuery({ queryKey: ["accounts"], queryFn: () => api.get<Account[]>("/api/accounts") });
  const batches = useQuery({ queryKey: ["batches"], queryFn: () => api.get<ImportBatch[]>("/api/imports") });

  const [file, setFile] = useState<File | null>(null);
  const [accountId, setAccountId] = useState<number | "">("");
  const [importerName, setImporterName] = useState<string>("");
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [skipDuplicates, setSkipDuplicates] = useState(true);

  const previewMut = useMutation({
    mutationFn: async () => {
      if (!file || !accountId) throw new Error("file and account required");
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

      <div className="card p-4">
        <div className="text-sm font-medium mb-3">Upload a CSV</div>
        <div className="grid grid-cols-3 gap-3">
          <label className="block">
            <div className="label mb-1">Account</div>
            <select
              className="input"
              value={accountId}
              onChange={(e) => setAccountId(e.target.value ? parseInt(e.target.value, 10) : "")}
            >
              <option value="">— select —</option>
              {accounts.data?.map((a) => (
                <option key={a.id} value={a.id}>{a.name}</option>
              ))}
            </select>
          </label>
          <label className="block">
            <div className="label mb-1">Importer (optional)</div>
            <select className="input" value={importerName} onChange={(e) => setImporterName(e.target.value)}>
              <option value="">auto-detect</option>
              <option value="chase_credit">Chase Credit Card</option>
              <option value="wells_fargo_checking">Wells Fargo Checking</option>
              <option value="amex">Amex (charges positive)</option>
            </select>
          </label>
          <label className="block">
            <div className="label mb-1">CSV file</div>
            <input
              type="file"
              accept=".csv,.CSV,text/csv"
              className="input"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </label>
        </div>
        <div className="mt-3 flex items-center gap-2">
          <button
            className="btn-primary"
            disabled={!file || !accountId || previewMut.isPending}
            onClick={() => previewMut.mutate()}
          >
            Preview
          </button>
          {previewMut.isError && (
            <span className="text-sm text-bad-600">{String((previewMut.error as Error).message)}</span>
          )}
        </div>
      </div>

      {preview && (
        <div className="card p-4">
          <div className="flex items-baseline justify-between mb-3">
            <div className="text-sm font-medium">
              Preview: {preview.rows.length} rows · matched importer{" "}
              <span className="font-mono">{preview.importer_name}</span>
            </div>
            <label className="text-xs text-ink-600 flex items-center gap-1">
              <input
                type="checkbox"
                checked={skipDuplicates}
                onChange={(e) => setSkipDuplicates(e.target.checked)}
              />
              skip {preview.rows.filter((r) => r.is_duplicate).length} duplicate(s)
            </label>
          </div>
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
                {preview.rows.map((r) => (
                  <tr key={r.row_index} className={clsx(r.is_duplicate && "opacity-50")}>
                    <td className="px-2 py-1">{r.date}</td>
                    <td className="px-2 py-1 max-w-md truncate">{r.description_raw}</td>
                    <td className="px-2 py-1">{r.merchant ?? "—"}</td>
                    <td className="px-2 py-1">{r.suggested_kind}</td>
                    <td className={clsx("px-2 py-1 text-right", Number(r.amount) < 0 ? "text-bad-600" : "text-good-600")}>
                      {currency(r.amount)}
                    </td>
                    <td className="px-2 py-1">{r.is_duplicate ? "yes" : ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-3 flex items-center gap-2">
            <button className="btn-primary" disabled={applyMut.isPending} onClick={() => applyMut.mutate()}>
              Apply import
            </button>
            <button className="btn" onClick={() => setPreview(null)}>Discard</button>
          </div>
          {applyMut.isError && (
            <div className="mt-2 text-sm text-bad-600">{String((applyMut.error as Error).message)}</div>
          )}
        </div>
      )}

      <div className="card p-4">
        <div className="flex items-baseline justify-between mb-3">
          <div className="text-sm font-medium">Past imports</div>
          <div className="text-xs text-ink-500">
            {batches.data?.length ?? 0} batches · re-running the loader against an existing batch is a no-op (idempotent by filename + account).
          </div>
        </div>
        {batches.data?.length ? (
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
              {batches.data.map((b) => {
                const acc = accounts.data?.find((a) => a.id === b.account_id);
                return (
                  <tr key={b.id} className="table-row-hover">
                    <td className="px-3 py-1.5">{dateLabel(b.imported_at)}</td>
                    <td className="px-3 py-1.5 font-mono text-xs">{b.source_filename}</td>
                    <td className="px-3 py-1.5">{acc?.name ?? `#${b.account_id}`}</td>
                    <td className="px-3 py-1.5 text-right tabular font-medium">{b.row_count_applied.toLocaleString()}</td>
                    <td className="px-3 py-1.5 text-right tabular text-ink-500">{b.row_count_duplicate.toLocaleString()}</td>
                    <td className="px-3 py-1.5 text-right tabular text-ink-500">{b.row_count_total.toLocaleString()}</td>
                    <td className="px-3 py-1.5">{b.status}</td>
                    <td className="px-3 py-1.5 text-right">
                      {b.status === "applied" && (
                        <button
                          className="btn-ghost text-xs text-bad-600"
                          onClick={() => {
                            if (confirm(`Rollback ${b.row_count_applied} transactions from ${b.source_filename}?`)) {
                              rollbackMut.mutate(b.id);
                            }
                          }}
                        >
                          Rollback
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        ) : (
          <div className="text-sm text-ink-500 italic">No imports yet.</div>
        )}
      </div>
    </div>
  );
}
