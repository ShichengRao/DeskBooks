import { useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import clsx from "clsx";
import { api } from "../api/client";
import { Field } from "./Field";
import { SidePanel } from "./SidePanel";
import type { ProfileList } from "../api/types";

const tabs: { to: string; label: string; end?: boolean; group?: "view" | "edit" }[] = [
  // "View" tabs — read-mostly dashboards.
  { to: "/", label: "Dashboard", end: true, group: "view" },
  { to: "/transactions", label: "Transactions", group: "view" },
  { to: "/networth", label: "Net Worth", group: "view" },
  { to: "/planning", label: "Planning", group: "view" },
  { to: "/budgets", label: "Budgets", group: "view" },
  { to: "/analytics", label: "Analytics", group: "view" },
  // "Fill data" tabs — workflows optimized for data entry.
  { to: "/import", label: "Import", group: "edit" },
  { to: "/reconcile", label: "Reconcile", group: "edit" },
  { to: "/rules", label: "Rules", group: "edit" },
  { to: "/backups", label: "Backups", group: "edit" },
];

export function Layout() {
  const [profileEditor, setProfileEditor] = useState<{
    mode: "create" | "duplicate";
    initialName: string;
  } | null>(null);
  const profiles = useQuery({
    queryKey: ["profiles"],
    queryFn: () => api.get<ProfileList>("/api/profiles"),
  });
  const switchProfile = useMutation({
    mutationFn: (slug: string) =>
      api.post<ProfileList>("/api/profiles/active", { slug }),
    onSuccess: () => window.location.reload(),
  });
  const createProfile = useMutation({
    mutationFn: (body: { name: string; seed_starter_data: boolean }) =>
      api.post<ProfileList>("/api/profiles", body),
    onSuccess: () => window.location.reload(),
  });
  const duplicateProfile = useMutation({
    mutationFn: (name: string) => api.post<ProfileList>("/api/profiles/duplicate", { name }),
    onSuccess: () => window.location.reload(),
  });

  const stopApp = async () => {
    if (!confirm("Stop the local app servers?")) return;
    try {
      await fetch("/api/admin/shutdown", { method: "POST" });
    } catch {
      // The request may be interrupted by the server exiting; that's fine.
    }
  };

  const addProfile = () => {
    setProfileEditor({ mode: "create", initialName: "" });
  };

  const copyProfile = () => {
    const active = profiles.data?.profiles.find((p) => p.slug === profiles.data?.active_slug);
    setProfileEditor({
      mode: "duplicate",
      initialName: active ? `${active.name} copy` : "Profile copy",
    });
  };

  return (
    <div className="min-h-full flex flex-col">
      <header className="border-b border-ink-200 bg-white">
        <div className="px-6 py-3 flex items-center gap-6">
          <div className="font-semibold text-ink-900 tracking-tight">DeskBooks</div>
          <div className="flex items-center gap-1 text-xs text-ink-600">
            <span className="sr-only">Profile</span>
            <select
              className="input min-w-32 py-1 text-xs"
              value={profiles.data?.active_slug ?? ""}
              disabled={profiles.isLoading || switchProfile.isPending || createProfile.isPending || duplicateProfile.isPending}
              onChange={(e) => {
                if (!e.target.value || e.target.value === profiles.data?.active_slug) return;
                switchProfile.mutate(e.target.value);
              }}
              title="Local profile"
            >
              {!profiles.data && <option value="">Loading</option>}
              {profiles.data?.profiles.map((p) => (
                <option key={p.slug} value={p.slug}>
                  {p.name}
                </option>
              ))}
            </select>
            <button
              type="button"
              className="btn-ghost px-2 py-1 text-xs"
              onClick={addProfile}
              disabled={createProfile.isPending || duplicateProfile.isPending}
              title="Create local profile"
            >
              +
            </button>
            <button
              type="button"
              className="btn-ghost px-2 py-1 text-xs"
              onClick={copyProfile}
              disabled={!profiles.data || createProfile.isPending || duplicateProfile.isPending}
              title="Duplicate active profile"
            >
              Duplicate
            </button>
          </div>
          <nav className="flex items-center gap-1 text-sm">
            {tabs.map((t, i) => {
              // Visual gap between the "view" group and the "edit" group so
              // the data-entry workflows feel like their own zone.
              const prev = tabs[i - 1];
              const showDivider = prev && prev.group !== t.group;
              return (
                <span key={t.to} className="flex items-center gap-1">
                  {showDivider && (
                    <span className="mx-1 h-5 w-px bg-ink-200" aria-hidden />
                  )}
                  <NavLink
                    to={t.to}
                    end={t.end}
                    className={({ isActive }) =>
                      clsx(
                        "px-3 py-1.5 rounded-md transition-colors",
                        isActive
                          ? "bg-brand-100 text-brand-800"
                          : "text-ink-600 hover:bg-ink-100 hover:text-ink-900",
                      )
                    }
                  >
                    {t.label}
                  </NavLink>
                </span>
              );
            })}
          </nav>
          <button
            type="button"
            className="btn-ghost ml-auto text-xs text-bad-600 hover:bg-bad-500/10"
            onClick={stopApp}
          >
            Stop app
          </button>
        </div>
      </header>
      <main className="flex-1 p-6 max-w-[1600px] w-full mx-auto">
        <Outlet />
      </main>
      {profileEditor && (
        <ProfileEditor
          mode={profileEditor.mode}
          initialName={profileEditor.initialName}
          isSaving={createProfile.isPending || duplicateProfile.isPending}
          error={createProfile.error || duplicateProfile.error}
          onClose={() => setProfileEditor(null)}
          onSubmit={(name, seedStarterData) => {
            if (profileEditor.mode === "create") {
              createProfile.mutate({ name, seed_starter_data: seedStarterData });
            } else {
              duplicateProfile.mutate(name);
            }
          }}
        />
      )}
    </div>
  );
}

function ProfileEditor({
  mode,
  initialName,
  isSaving,
  error,
  onClose,
  onSubmit,
}: {
  mode: "create" | "duplicate";
  initialName: string;
  isSaving: boolean;
  error: Error | null;
  onClose: () => void;
  onSubmit: (name: string, seedStarterData: boolean) => void;
}) {
  const [name, setName] = useState(initialName);
  const [seedStarterData, setSeedStarterData] = useState(true);
  const trimmed = name.trim();
  const title = mode === "create" ? "New local profile" : "Duplicate active profile";

  return (
    <SidePanel title={title} onClose={onClose} onSubmit={() => onSubmit(trimmed, seedStarterData)} maxWidth="max-w-md">
      <div className="space-y-3">
        <Field label="Profile name">
          <input
            className="input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={mode === "create" ? "Personal" : "Profile copy"}
            autoFocus
            required
          />
        </Field>
        {mode === "duplicate" && (
          <p className="text-sm text-ink-500">
            This creates a separate local SQLite file from the currently active profile and switches to it.
          </p>
        )}
        {mode === "create" && (
          <label className="flex items-start gap-2 text-sm text-ink-700">
            <input
              type="checkbox"
              className="mt-1"
              checked={seedStarterData}
              onChange={(e) => setSeedStarterData(e.target.checked)}
            />
            <span>
              Seed starter accounts, categories, and demo notes.
            </span>
          </label>
        )}
      </div>
      <div className="sticky bottom-0 z-10 -mx-6 mt-4 flex items-center gap-2 border-t border-ink-100 bg-white px-6 py-3">
        <button type="submit" className="btn-primary" disabled={isSaving || !trimmed}>
          {mode === "create" ? "Create profile" : "Duplicate profile"}
        </button>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
      </div>
      {error && <div className="mt-2 text-sm text-bad-600">{error.message}</div>}
    </SidePanel>
  );
}
