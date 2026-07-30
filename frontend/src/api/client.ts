// Thin fetch wrapper. Throws on non-2xx.

const BASE = "";

// Cross-tab safety: the active profile is server-side state, so another
// tab switching profiles would silently redirect this tab's reads and
// writes. Every request states the profile this tab believes it's on
// (set by the layout once profiles load); the backend answers 409 when
// that's no longer the active profile, and we surface that as an event
// the layout turns into a blocking banner.
let expectedProfile: string | null = null;

export function setExpectedProfile(slug: string | null) {
  expectedProfile = slug;
}

export const PROFILE_MISMATCH_EVENT = "deskbooks:profile-mismatch";

function guardHeaders(): Record<string, string> {
  return expectedProfile ? { "X-DeskBooks-Profile": expectedProfile } : {};
}

async function fail(res: Response): Promise<never> {
  const text = await res.text();
  if (res.status === 409) {
    let payload: unknown;
    try {
      payload = JSON.parse(text);
    } catch {
      payload = null;
    }
    if (payload && typeof payload === "object" && (payload as { code?: string }).code === "profile_mismatch") {
      window.dispatchEvent(new CustomEvent(PROFILE_MISMATCH_EVENT, { detail: payload }));
      throw new Error((payload as { detail?: string }).detail ?? "active profile changed in another tab");
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
