import { useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router";
import { useMutation, useQuery } from "@tanstack/react-query";
import clsx from "clsx";
import { api, getTabProfile, PROFILE_GONE_EVENT, setTabProfile } from "../api/client";
import { Field } from "./Field";
import { SidePanel } from "./SidePanel";
import type { Profile, ProfileList } from "../api/types";

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
  const [profileEditorOpen, setProfileEditorOpen] = useState(false);
  const [profileMenuOpen, setProfileMenuOpen] = useState(false);
  // The profile this tab is pinned to (per-tab via sessionStorage). Every
  // API call carries it and routes to that profile's database, so two
  // windows can live on two profiles at once. The server's "active"
  // profile is only the default for fresh windows and the CLI.
  const [tabProfile, setTabProfileState] = useState<string | null>(() => getTabProfile());
  const [goneProfile, setGoneProfile] = useState<string | null>(null);
  const profiles = useQuery({
    queryKey: ["profiles"],
    queryFn: () => api.get<ProfileList>("/api/profiles"),
    // Keep the menu's default marker fresh when returning to this tab.
    refetchOnWindowFocus: "always",
  });

  useEffect(() => {
    const active = profiles.data?.active_slug;
    if (!active || tabProfile !== null) return;
    // Fresh window: adopt the app's default profile as this tab's pin.
    setTabProfile(active);
    setTabProfileState(active);
  }, [profiles.data, tabProfile]);

  useEffect(() => {
    const onGone = (event: Event) => {
      const detail = (event as CustomEvent).detail as { expected_profile?: string };
      setGoneProfile(detail?.expected_profile ?? tabProfile ?? "?");
    };
    window.addEventListener(PROFILE_GONE_EVENT, onGone);
    return () => window.removeEventListener(PROFILE_GONE_EVENT, onGone);
  }, [tabProfile]);
  const switchProfile = useMutation({
    // Repin this tab, and make the choice the app-wide default for new
    // windows and the CLI. Other open windows keep their own pins.
    mutationFn: (slug: string) =>
      api.post<ProfileList>("/api/profiles/active", { slug }),
    onSuccess: (data, slug) => {
      setTabProfile(slug);
      window.location.reload();
    },
  });
  const createProfile = useMutation({
    mutationFn: (body: { name: string; seed_starter_data: boolean }) =>
      api.post<ProfileList>("/api/profiles", body),
    onSuccess: (data) => {
      // Follow the new profile in this tab only.
      setTabProfile(data.active_slug);
      window.location.reload();
    },
  });
  const duplicateProfile = useMutation({
    mutationFn: (body: { name: string; source_slug: string }) =>
      api.post<ProfileList>("/api/profiles/duplicate", body),
    onSuccess: (data) => {
      setTabProfile(data.active_slug);
      window.location.reload();
    },
  });
  const deleteProfile = useMutation({
    mutationFn: (slug: string) => api.del<Profile>(`/api/profiles/${encodeURIComponent(slug)}`),
    onSuccess: () => {
      // The pinned profile is gone; fall back to the app default.
      setTabProfile(null);
      window.location.reload();
    },
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
    setProfileMenuOpen(false);
    setProfileEditorOpen(true);
  };
  const profileName = (slug: string | null) =>
    profiles.data?.profiles.find((p) => p.slug === slug)?.name ?? slug ?? "?";
  // The header names the profile this WINDOW is pinned to; other windows
  // may be pinned elsewhere.
  const currentProfile = profiles.data?.profiles.find(
    (p) => p.slug === (tabProfile ?? profiles.data?.active_slug),
  );
  const canDeleteProfile = Boolean(profiles.data && profiles.data.profiles.length > 1 && currentProfile);
  const profileBusy = switchProfile.isPending || createProfile.isPending || duplicateProfile.isPending || deleteProfile.isPending;
  const chooseProfile = (slug: string) => {
    setProfileMenuOpen(false);
    if (!slug || slug === tabProfile) return;
    switchProfile.mutate(slug);
  };
  const removeActiveProfile = () => {
    if (!currentProfile) return;
    const ok = confirm(
      `Delete profile "${currentProfile.name}" and its local SQLite file? This cannot be undone.`,
    );
    if (!ok) return;
    setProfileMenuOpen(false);
    deleteProfile.mutate(currentProfile.slug);
  };

  return (
    <div className="min-h-full flex flex-col">
      <header className="border-b border-ink-200 bg-white">
        <div className="px-6 py-3 flex items-center gap-6">
          <div className="font-semibold text-ink-900 tracking-tight">DeskBooks</div>
          <div className="relative text-xs text-ink-600">
            <span className="sr-only">Profile</span>
            <button
              type="button"
              className="input flex min-w-40 items-center justify-between gap-3 py-1 text-left text-xs"
              disabled={profiles.isLoading || profileBusy}
              onClick={() => setProfileMenuOpen((open) => !open)}
              title="Local profile"
            >
              <span className="truncate">{currentProfile?.name ?? "Loading"}</span>
              <span aria-hidden>v</span>
            </button>
            {profileMenuOpen && (
              <div className="absolute left-0 top-full z-30 mt-1 w-60 overflow-hidden rounded-md border border-ink-200 bg-white py-1 shadow-lg">
                <div className="max-h-64 overflow-auto py-1">
                  {profiles.data?.profiles.map((profile) => (
                    <button
                      type="button"
                      key={profile.slug}
                      className={clsx(
                        "flex w-full items-center justify-between gap-3 px-3 py-2 text-left text-xs hover:bg-ink-50",
                        profile.slug === tabProfile ? "font-medium text-brand-700" : "text-ink-700",
                      )}
                      onClick={() => chooseProfile(profile.slug)}
                      disabled={profileBusy}
                    >
                      <span className="truncate">{profile.name}</span>
                      <span className="flex items-center gap-2">
                        {profile.slug === tabProfile && <span className="text-brand-600">this window</span>}
                        {profile.is_active && (
                          <span className="text-ink-400" title="New windows and the fetch CLI open on this profile">
                            default
                          </span>
                        )}
                      </span>
                    </button>
                  ))}
                </div>
                <div className="border-t border-ink-100 py-1">
                  <button
                    type="button"
                    className="block w-full px-3 py-2 text-left text-xs text-ink-700 hover:bg-ink-50"
                    onClick={addProfile}
                    disabled={profileBusy}
                  >
                    New profile
                  </button>
                  <button
                    type="button"
                    className="block w-full px-3 py-2 text-left text-xs text-bad-600 hover:bg-bad-500/10 disabled:text-ink-300 disabled:hover:bg-transparent"
                    onClick={removeActiveProfile}
                    disabled={!canDeleteProfile || profileBusy}
                  >
                    Delete active profile
                  </button>
                </div>
              </div>
            )}
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
      {goneProfile && (
        <div className="flex items-center gap-3 border-b border-bad-500/30 bg-bad-500/10 px-6 py-2 text-sm text-bad-700">
          <span className="flex-1">
            This window was on <strong>{profileName(goneProfile)}</strong>, but that profile no
            longer exists (deleted in another window?).
          </span>
          <button
            type="button"
            className="btn text-xs"
            onClick={() => {
              setTabProfile(null);
              window.location.reload();
            }}
          >
            Switch to the default profile
          </button>
        </div>
      )}
      <main className="flex-1 p-6 max-w-[1600px] w-full mx-auto">
        <Outlet />
      </main>
      {profileEditorOpen && (
        <ProfileEditor
          profiles={profiles.data}
          isSaving={createProfile.isPending || duplicateProfile.isPending || deleteProfile.isPending}
          error={createProfile.error || duplicateProfile.error || deleteProfile.error}
          onClose={() => setProfileEditorOpen(false)}
          onSubmit={(name, mode, seedStarterData, sourceSlug) => {
            if (mode === "copy") {
              duplicateProfile.mutate({ name, source_slug: sourceSlug });
              return;
            }
            createProfile.mutate({ name, seed_starter_data: seedStarterData });
          }}
        />
      )}
    </div>
  );
}

function ProfileEditor({
  profiles,
  isSaving,
  error,
  onClose,
  onSubmit,
}: {
  profiles: ProfileList | undefined;
  isSaving: boolean;
  error: Error | null;
  onClose: () => void;
  onSubmit: (name: string, mode: "fresh" | "copy", seedStarterData: boolean, sourceSlug: string) => void;
}) {
  const active = profiles?.profiles.find((p) => p.slug === profiles.active_slug);
  const [mode, setMode] = useState<"fresh" | "copy">("fresh");
  const [name, setName] = useState("");
  const [seedStarterData, setSeedStarterData] = useState(true);
  const [sourceSlug, setSourceSlug] = useState(profiles?.active_slug ?? "");
  const trimmed = name.trim();
  const effectiveSourceSlug = sourceSlug || profiles?.active_slug || "";
  const canSubmit = Boolean(trimmed && (mode === "fresh" || effectiveSourceSlug));

  return (
    <SidePanel title="New local profile" onClose={onClose} onSubmit={() => onSubmit(trimmed, mode, seedStarterData, effectiveSourceSlug)} maxWidth="max-w-md">
      <div className="space-y-3">
        <Field label="Profile name">
          <input
            className="input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={mode === "copy" && active ? `${active.name} copy` : "Personal"}
            autoFocus
            required
          />
        </Field>
        <Field label="Start from">
          <select className="input" value={mode} onChange={(e) => setMode(e.target.value as "fresh" | "copy")}>
            <option value="fresh">Fresh profile</option>
            <option value="copy">Copy existing profile</option>
          </select>
        </Field>
        {mode === "fresh" ? (
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
        ) : (
          <Field label="Source profile">
            <select className="input" value={effectiveSourceSlug} onChange={(e) => setSourceSlug(e.target.value)} required>
              {(profiles?.profiles ?? []).map((profile) => (
                <option key={profile.slug} value={profile.slug}>{profile.name}</option>
              ))}
            </select>
          </Field>
        )}
      </div>
      <div className="sticky bottom-0 z-10 -mx-6 mt-4 flex items-center gap-2 border-t border-ink-100 bg-white px-6 py-3">
        <button type="submit" className="btn-primary" disabled={isSaving || !canSubmit}>
          Create profile
        </button>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
      </div>
      {error && <div className="mt-2 text-sm text-bad-600">{error.message}</div>}
    </SidePanel>
  );
}
