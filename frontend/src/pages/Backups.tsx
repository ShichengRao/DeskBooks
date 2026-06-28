import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Backup, BackupList } from "../api/types";

function formatBytes(bytes: number) {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

function timestamp(value: string) {
  return new Date(value).toLocaleString("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

export function Backups() {
  const qc = useQueryClient();
  const backups = useQuery({
    queryKey: ["backups"],
    queryFn: () => api.get<BackupList>("/api/backups"),
  });

  const createBackup = useMutation({
    mutationFn: () => api.post<Backup>("/api/backups"),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["backups"] }),
  });

  const restoreBackup = useMutation({
    mutationFn: (name: string) => api.post<Backup>(`/api/backups/${encodeURIComponent(name)}/restore`),
    onSuccess: () => window.location.reload(),
  });

  const deleteBackup = useMutation({
    mutationFn: (name: string) => api.del<Backup>(`/api/backups/${encodeURIComponent(name)}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["backups"] }),
  });

  const restore = (backup: Backup) => {
    if (!confirm(`Restore ${backup.name}? The current database will be backed up first.`)) return;
    restoreBackup.mutate(backup.name);
  };

  const remove = (backup: Backup) => {
    if (!confirm(`Delete backup ${backup.name}? This cannot be undone.`)) return;
    deleteBackup.mutate(backup.name);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Backups</h1>
        <button
          type="button"
          className="btn-primary"
          onClick={() => createBackup.mutate()}
          disabled={createBackup.isPending}
        >
          Create backup
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="card p-4">
          <div className="label">Profile</div>
          <div className="text-2xl font-semibold mt-1">{backups.data?.profile_slug ?? "—"}</div>
        </div>
        <div className="card p-4">
          <div className="label">Snapshots</div>
          <div className="text-2xl font-semibold tabular mt-1">
            {backups.data?.backups.length ?? 0}
          </div>
        </div>
        <div className="card p-4">
          <div className="label">Latest</div>
          <div className="text-2xl font-semibold mt-1">
            {backups.data?.backups[0] ? timestamp(backups.data.backups[0].created_at) : "—"}
          </div>
        </div>
      </div>

      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-ink-50 text-left">
            <tr>
              <th className="px-3 py-2 font-medium">Created</th>
              <th className="px-3 py-2 font-medium">File</th>
              <th className="px-3 py-2 font-medium text-right">Size</th>
              <th className="px-3 py-2 font-medium text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {backups.data?.backups.map((backup) => (
              <tr key={backup.name} className="table-row-hover">
                <td className="px-3 py-2 whitespace-nowrap">{timestamp(backup.created_at)}</td>
                <td className="px-3 py-2">
                  <div className="font-medium">{backup.name}</div>
                  <div className="text-xs text-ink-500 font-mono break-all">{backup.path}</div>
                </td>
                <td className="px-3 py-2 text-right tabular">{formatBytes(backup.size_bytes)}</td>
                <td className="px-3 py-2">
                  <div className="flex justify-end gap-2">
                    <button
                      type="button"
                      className="btn text-xs"
                      onClick={() => restore(backup)}
                      disabled={restoreBackup.isPending}
                    >
                      Restore
                    </button>
                    <button
                      type="button"
                      className="btn-danger text-xs"
                      onClick={() => remove(backup)}
                      disabled={deleteBackup.isPending || restoreBackup.isPending}
                    >
                      Delete
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {backups.data?.backups.length === 0 && (
              <tr>
                <td colSpan={4} className="px-3 py-8 text-center text-ink-500">
                  No backups yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
