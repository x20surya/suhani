export interface TokenResponse {
  url: string;
  token: string;
  room: string;
}

export interface OpenOrder {
  client_name: string;
  garment: string;
  due_date: string | null;
  balance: number;
}

export interface Due {
  name: string;
  due: number;
}

export interface Payment {
  client_name: string;
  amount: number;
  created_at: string | null;
}

export interface Note {
  content: string;
  created_at: string | null;
}

export interface OrdersResponse {
  open_orders: OpenOrder[];
  dues: Due[];
  recent_payments: Payment[];
  notes?: Note[];
}

export interface BriefingResponse {
  due_today: { client_name: string; garment: string }[];
  overdue: { client_name: string; garment: string }[];
  money_pending: { due: number }[];
}

/* ---------------- access key ----------------
   She opens https://host/?k=<secret> once. We store it and send it as a header
   from then on, so the installed home-screen icon (start_url "/", no query
   string) keeps working and she never sees a login. The key is stripped from
   the address bar immediately so it does not linger in screenshots or history.
*/

const KEY_STORAGE = "suhani.key";

function captureKeyFromUrl(): void {
  const url = new URL(window.location.href);
  const k = url.searchParams.get("k");
  if (!k) return;
  try {
    localStorage.setItem(KEY_STORAGE, k);
  } catch {
    /* private mode: fall through, the in-memory copy below still works */
  }
  memoryKey = k;
  url.searchParams.delete("k");
  window.history.replaceState({}, "", url.pathname + url.search + url.hash);
}

let memoryKey = "";

function appKey(): string {
  if (memoryKey) return memoryKey;
  try {
    return localStorage.getItem(KEY_STORAGE) ?? "";
  } catch {
    return "";
  }
}

captureKeyFromUrl();

/** True once a key is present, so the UI can tell "locked out" from "broken". */
export const hasKey = () => Boolean(appKey());

export class UnauthorizedError extends Error {}

function authHeaders(): HeadersInit {
  const k = appKey();
  return k ? { "X-Suhani-Key": k } : {};
}

async function getJSON<T>(path: string): Promise<T> {
  const res = await fetch(path, { headers: authHeaders() });
  if (!res.ok) {
    let detail = res.statusText;
    try {
      detail = (await res.json()).detail ?? detail;
    } catch {
      /* non-JSON error body */
    }
    if (res.status === 401 || res.status === 503) throw new UnauthorizedError(detail);
    throw new Error(detail);
  }
  return res.json() as Promise<T>;
}

export const fetchToken = () => getJSON<TokenResponse>("/api/token");
export const fetchOrders = () => getJSON<OrdersResponse>("/api/orders");
export const fetchBriefing = () => getJSON<BriefingResponse>("/api/briefing");

/* ---------------- push reminders ---------------- */

function urlBase64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - (base64.length % 4)) % 4);
  const normalized = (base64 + padding).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(normalized);
  // Backed by a real ArrayBuffer so it satisfies BufferSource for
  // applicationServerKey; Uint8Array.from() yields ArrayBufferLike, which does not.
  const view = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i += 1) view[i] = raw.charCodeAt(i);
  return view;
}

export type PushResult = "enabled" | "denied" | "unsupported" | "no-keys";

/** Enrols this device for the daily "what's due" push. */
export async function enablePushReminders(): Promise<PushResult> {
  if (!("serviceWorker" in navigator) || !("PushManager" in window)) return "unsupported";

  const permission = await Notification.requestPermission();
  if (permission !== "granted") return "denied";

  const { key } = await getJSON<{ key: string }>("/api/vapid-public-key");
  if (!key) return "no-keys";

  const registration = await navigator.serviceWorker.ready;
  const existing = await registration.pushManager.getSubscription();
  const subscription =
    existing ??
    (await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(key),
    }));

  const res = await fetch("/api/push/subscribe", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify({ subscription: subscription.toJSON() }),
  });
  if (!res.ok) throw new Error("Could not register for reminders");
  return "enabled";
}

/** Already enrolled on this device? Drives the button's resting label. */
export async function pushAlreadyEnabled(): Promise<boolean> {
  if (!("serviceWorker" in navigator) || !("PushManager" in window)) return false;
  if (Notification.permission !== "granted") return false;
  const registration = await navigator.serviceWorker.ready;
  return Boolean(await registration.pushManager.getSubscription());
}

/** Indian 2-2-3 grouping. Latin digits: CLDR resolves hi-IN to `latn`. */
const rupees = new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
});

export const formatRupees = (n: number) => rupees.format(n);

export const formatShortDate = (iso: string | null) =>
  iso
    ? new Date(iso + "T00:00:00").toLocaleDateString("en-IN", {
        day: "numeric",
        month: "short",
      })
    : "no date set";
