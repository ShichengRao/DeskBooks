// Thin fetch wrapper. Throws on non-2xx.

const BASE = "";

// Each browser tab is pinned to one profile and says so on every request
// (X-DeskBooks-Profile); the backend routes the request to that profile's
// database. Two windows can therefore live on two profiles at once. The
// pin survives reloads via sessionStorage — which is per-tab, so a new
// window starts on the app's default profile instead of inheriting this
// one's. Read synchronously at import time so even the first queries of a
// reload carry the right profile.
const TAB_PROFILE_KEY = "deskbooks.tab-profile";

function readStoredProfile(): string | null {
  try {
    return window.sessionStorage.getItem(TAB_PROFILE_KEY);
  } catch {
    return null;
  }
}

let tabProfile: string | null = readStoredProfile();

export function getTabProfile(): string | null {
  return tabProfile;
}

export function setTabProfile(slug: string | null) {
  tabProfile = slug;
  try {
    if (slug === null) window.sessionStorage.removeItem(TAB_PROFILE_KEY);
    else window.sessionStorage.setItem(TAB_PROFILE_KEY, slug);
  } catch {
    // sessionStorage unavailable — the pin just won't survive reloads.
  }
}

// Fired when the backend reports this tab's profile no longer exists
// (deleted in another tab); the layout turns it into a banner.
export const PROFILE_GONE_EVENT = "deskbooks:profile-gone";

function guardHeaders(): Record<string, string> {
  return tabProfile ? { "X-DeskBooks-Profile": tabProfile } : {};
}

async function fail(res: Response): Promise<never> {
  const text = await res.text();
  if (res.status === 404) {
    let payload: unknown;
    try {
      payload = JSON.parse(text);
    } catch {
      payload = null;
    }
    const detail =
      payload && typeof payload === "object"
        ? (payload as { detail?: { code?: string; detail?: string } }).detail
        : null;
    if (detail && typeof detail === "object" && detail.code === "profile_unknown") {
      window.dispatchEvent(new CustomEvent(PROFILE_GONE_EVENT, { detail }));
      throw new Error(detail.detail ?? "this tab's profile no longer exists");
    }
  }
  throw new Error(`${res.status} ${res.statusText}: ${text}`);
}

async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method, headers: { "Content-Type": "application/json", ...guardHeaders() } };
  if (body !== undefined) init.body = JSON.stringify(body);
  const res = await fetch(BASE + path, init);
  if (!res.ok) return fail(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  get: <T>(path: string) => req<T>("GET", path),
  post: <T>(path: string, body?: unknown) => req<T>("POST", path, body),
  put: <T>(path: string, body?: unknown) => req<T>("PUT", path, body),
  patch: <T>(path: string, body?: unknown) => req<T>("PATCH", path, body),
  del: <T>(path: string) => req<T>("DELETE", path),
  postForm: async <T>(path: string, formData: FormData): Promise<T> => {
    const res = await fetch(BASE + path, { method: "POST", body: formData, headers: guardHeaders() });
    if (!res.ok) return fail(res);
    return (await res.json()) as T;
  },
};

export function qs(params: Record<string, unknown>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "") continue;
    if (Array.isArray(v)) {
      for (const item of v) u.append(k, String(item));
    } else {
      u.set(k, String(v));
    }
  }
  const s = u.toString();
  return s ? `?${s}` : "";
}
