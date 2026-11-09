import { useEffect, useRef, useState } from "react";
import { Link, matchPath, NavLink, Outlet, useLocation } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import clsx from "clsx";
import { api, getTabProfile, PROFILE_GONE_EVENT, setTabProfile } from "../api/client";
import { Field } from "./Field";
import { SidePanel } from "./SidePanel";
import type { Profile, ProfileList } from "../api/types";

type Page = { to: string; label: string; end?: boolean };
type Workflow = { id: string; label: string; pages: Page[] };

const page = {
  dashboard: { to: "/", label: "Dashboard", end: true },
  transactions: { to: "/transactions", label: "Transactions" },
  networth: { to: "/networth", label: "Net Worth" },
  planning: { to: "/planning", label: "Planning" },
  budgets: { to: "/budgets", label: "Budgets" },
  trips: { to: "/trips", label: "Trips" },
  analytics: { to: "/analytics", label: "Analytics" },
  import: { to: "/import", label: "Import" },
  rules: { to: "/rules", label: "Rules" },
  splits: { to: "/reconcile", label: "Splits" },
  organize: { to: "/organize", label: "Organize" },
  backups: { to: "/backups", label: "Backups" },
} satisfies Record<string, Page>;

// The top row picks a workflow; the second row holds that workflow's pages.
// A page can sit in more than one workflow. You stay in the one you reached
// it from, and the first one listing it is its home when you arrive some
// other way, e.g. by a fresh link to a page outside the workflow you're in.
const workflows: Workflow[] = [
  { id: "overview", label: "Overview", pages: [page.dashboard, page.networth, page.planning, page.analytics] },
  { id: "spending", label: "Spending", pages: [page.transactions, page.budgets, page.analytics, page.trips] },
  {
    id: "upkeep",
    label: "Upkeep",
    pages: [page.import, page.rules, page.transactions, page.splits, page.organize, page.backups],
  },
];

const pages: Page[] = Object.values(page);
const pageAt = (pathname: string) =>
  pages.find((p) => matchPath({ path: p.to, end: p.end ?? false }, pathname));

// Per window, like the profile pin: which workflow you were last in, and
// the last page you used in each so switching back lands where you left
// off.
const NAV_KEY = "deskbooks.nav";
type NavMemory = { workflow: string | null; last: Record<string, string> };

function readNavMemory(): NavMemory {
  try {
    const stored = JSON.parse(window.sessionStorage.getItem(NAV_KEY) ?? "null");
    if (stored && typeof stored === "object") {
      return { workflow: stored.workflow ?? null, last: stored.last ?? {} };
    }
  } catch {
    // Unreadable or unavailable; start fresh.
  }
  return { workflow: null, last: {} };
}

function writeNavMemory(memory: NavMemory) {
  try {
    window.sessionStorage.setItem(NAV_KEY, JSON.stringify(memory));
  } catch {
    // sessionStorage unavailable — the choice just won't survive reloads.
  }
}

function useWorkflowNav() {
  const location = useLocation();
  const [memory, setMemory] = useState(readNavMemory);
  const current = pageAt(location.pathname);
  // Header links say which workflow they navigate within, as location
  // state, so the choice arrives together with the new page and comes back
  // with it on Back and Forward. Links inside pages don't, and keep you in
  // the workflow you were last in when the page belongs to it.
  const via = (location.state as { workflow?: string } | null)?.workflow;
  const holds = (id: string | null | undefined) =>
    workflows.find((w) => w.id === id && (!current || w.pages.includes(current)));
  const workflow =
    holds(via) ?? holds(memory.workflow) ?? workflows.find((w) => current && w.pages.includes(current)) ?? workflows[0];
  const here = location.pathname + location.search;

  useEffect(() => {
    setMemory((prev) => {
      const inWorkflow = current !== undefined && workflow.pages.includes(current);
      if (prev.workflow === workflow.id && (!inWorkflow || prev.last[workflow.id] === here)) return prev;
      const next = {
        workflow: workflow.id,
        last: inWorkflow ? { ...prev.last, [workflow.id]: here } : prev.last,
      };
      writeNavMemory(next);
      return next;
    });
  }, [workflow, current, here]);

  return { workflow, target: (w: Workflow) => memory.last[w.id] ?? w.pages[0].to };
}

const workflowClass = (isActive: boolean) =>
  clsx(
    "whitespace-nowrap px-3 py-1.5 rounded-md transition-colors",
    isActive ? "bg-brand-100 text-brand-800" : "text-ink-600 hover:bg-ink-100 hover:text-ink-900",
  );

export function Layout() {
  const queryClient = useQueryClient();
  const navRef = useRef<HTMLElement>(null);
  const location = useLocation();
  const nav = useWorkflowNav();
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
    onError: (error, slug) => {
      // The read-only demo 403s the default-setting mutation, but the pin
      // is purely client-side — switch this window anyway.
      if (error instanceof Error && error.message.startsWith("403")) {
        setTabProfile(slug);
        window.location.reload();
        return;
      }
      alert(error instanceof Error ? error.message : String(error));
    },
  });
  const surfaceError = (error: Error) => alert(error.message);
  const createProfile = useMutation({
    mutationFn: (body: { name: string; seed_starter_data: boolean }) =>
      api.post<ProfileList>("/api/profiles", body),
    onSuccess: (data) => {
      // Follow the new profile in this tab only.
      setTabProfile(data.active_slug);
      window.location.reload();
    },
    onError: surfaceError,
  });
  const duplicateProfile = useMutation({
    mutationFn: (body: { name: string; source_slug: string }) =>
      api.post<ProfileList>("/api/profiles/duplicate", body),
    onSuccess: (data) => {
      setTabProfile(data.active_slug);
      window.location.reload();
    },
    onError: surfaceError,
  });
  const deleteProfile = useMutation({
    mutationFn: (slug: string) => api.del<Profile>(`/api/profiles/${encodeURIComponent(slug)}`),
    onSuccess: () => {
      // The pinned profile is gone; fall back to the app default.
      setTabProfile(null);
      window.location.reload();
    },
    onError: surfaceError,
  });
  const renameProfile = useMutation({
    // Display name only; the slug this window is pinned by doesn't change.
    mutationFn: (body: { slug: string; name: string }) =>
      api.patch<ProfileList>(`/api/profiles/${encodeURIComponent(body.slug)}`, { name: body.name }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["profiles"] }),
    onError: surfaceError,
  });

  // On a phone the Upkeep row scrolls sideways, so the page you are on can
  // start off-screen. Bring it into view.
  useEffect(() => {
    const active = navRef.current?.querySelector("[aria-current='page']");
    active?.scrollIntoView({ block: "nearest", inline: "center" });
  }, [location.pathname, nav.workflow]);

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
  const profileBusy =
    switchProfile.isPending ||
    createProfile.isPending ||
    duplicateProfile.isPending ||
    deleteProfile.isPending ||
    renameProfile.isPending;
  const chooseProfile = (slug: string) => {
    setProfileMenuOpen(false);
    if (!slug || slug === tabProfile) return;
    switchProfile.mutate(slug);
  };
  const renameActiveProfile = () => {
    if (!currentProfile) return;
    const name = prompt(`Rename profile "${currentProfile.name}" to:`, currentProfile.name);
    const cleaned = name?.trim();
    if (!cleaned || cleaned === currentProfile.name) return;
    setProfileMenuOpen(false);
    renameProfile.mutate({ slug: currentProfile.slug, name: cleaned });
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
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 sm:gap-x-6 sm:px-6">
          <div className="font-semibold text-ink-900 tracking-tight">DeskBooks</div>
          <div className="relative text-xs text-ink-600">
            <span className="sr-only">Profile</span>
            <button
              type="button"
              className="input flex min-w-32 items-center justify-between gap-3 py-1 text-left text-xs sm:min-w-40"
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
                    className="block w-full px-3 py-2 text-left text-xs text-ink-700 hover:bg-ink-50"
                    onClick={renameActiveProfile}
                    disabled={!currentProfile || profileBusy}
                  >
                    Rename this profile
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
          <nav
            aria-label="Workflows"
            className="no-scrollbar order-last -mx-4 flex w-full items-center gap-1 overflow-x-auto px-4 text-sm
              sm:-mx-6 sm:px-6 nav:order-none nav:mx-0 nav:w-auto nav:overflow-visible nav:px-0"
          >
            {workflows.map((w) => {
              const isActive = w.id === nav.workflow.id;
              return (
                <Link
                  key={w.id}
                  to={nav.target(w)}
                  state={{ workflow: w.id }}
                  className={workflowClass(isActive)}
                  aria-current={isActive ? "true" : undefined}
                >
                  {w.label}
                </Link>
              );
            })}
          </nav>
          <button
            type="button"
            className="btn-ghost ml-auto shrink-0 text-xs text-bad-600 hover:bg-bad-500/10"
            onClick={stopApp}
          >
            Stop app
          </button>
        </div>
        <nav
          ref={navRef}
          aria-label={nav.workflow.label}
          className="no-scrollbar flex gap-1 overflow-x-auto border-t border-ink-100 px-4 text-sm sm:px-6"
        >
          {nav.workflow.pages.map((p) => (
            <NavLink
              key={p.to}
              to={p.to}
              end={p.end}
              state={{ workflow: nav.workflow.id }}
              className={({ isActive }) =>
                clsx(
                  "shrink-0 whitespace-nowrap border-b-2 px-2 py-2 transition-colors sm:px-3",
                  isActive
                    ? "border-brand-600 text-brand-800"
                    : "border-transparent text-ink-600 hover:border-ink-200 hover:text-ink-900",
                )
              }
            >
              {p.label}
            </NavLink>
          ))}
        </nav>
      </header>
      {goneProfile && (
        <div className="flex flex-wrap items-center gap-3 border-b border-bad-500/30 bg-bad-500/10 px-4 py-2 text-sm text-bad-700 sm:px-6">
          <span className="flex-1 min-w-0">
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
      <main className="flex-1 p-4 sm:p-6 max-w-[1600px] w-full mx-auto">
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
      <div className="sticky bottom-0 z-10 -mx-4 mt-4 flex flex-wrap items-center gap-2 border-t border-ink-100 bg-white px-4 py-3 sm:-mx-6 sm:px-6">
        <button type="submit" className="btn-primary" disabled={isSaving || !canSubmit}>
          Create profile
        </button>
        <button type="button" className="btn" onClick={onClose}>Cancel</button>
      </div>
      {error && <div className="mt-2 text-sm text-bad-600">{error.message}</div>}
    </SidePanel>
  );
}
