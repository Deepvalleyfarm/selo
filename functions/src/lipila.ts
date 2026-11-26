/**
 * Lipila mobile-money gateway client (Airtel Money, MTN MoMo, Zamtel Kwacha) — production configuration.
 *
 *  POST {LIPILA_BASE_URL}{LIPILA_COLLECTION_PATH}    buyer payments (goods, parcels, boosts, registration)
 *  POST {LIPILA_BASE_URL}{LIPILA_DISBURSEMENT_PATH}  payouts and wallet withdrawals
 *  headers: accept, Content-Type, x-api-key (Secret Manager), callbackUrl (our webhook, carrying a secret token)
 *  body:    { referenceId, amount, accountNumber (260…), currency: "ZMW", narration }
 *
 * The production base URL must be set explicitly (LIPILA_BASE_URL); there is no default, so a deployment can never
 * silently talk to the sandbox. Status-lookup paths are configurable because they aren't in the integration notes;
 * without them, reconciliation sends stuck items to manual review instead of guessing.
 * The API key is never logged.
 */
import { defineString } from "firebase-functions/params";
import { LIPILA_API_KEY, LIPILA_BASE_URL, LIPILA_CALLBACK_URL, LIPILA_WEBHOOK_SECRET } from "./config";

const P = {
  collectionPath: defineString("LIPILA_COLLECTION_PATH", { default: "/api/v1/collections/mobile-money" }),
  disbursementPath: defineString("LIPILA_DISBURSEMENT_PATH", { default: "/api/v1/disbursements/mobile-money" }),
  // e.g. /api/v1/collections/check-status?referenceId={referenceId} — confirm with Lipila, then set these.
  collectionStatusPath: defineString("LIPILA_COLLECTION_STATUS_PATH", { default: "" }),
  disbursementStatusPath: defineString("LIPILA_DISBURSEMENT_STATUS_PATH", { default: "" }),
  lookupPath: defineString("LIPILA_LOOKUP_PATH", { default: "" }),
};
const val = (p: { value(): string }, env: string, def = "") => { try { return p.value() || process.env[env] || def; } catch { return process.env[env] || def; } };

export interface LipilaResult { ok: boolean; status: number; reference: string; lipilaStatus?: string; identifier?: string; message?: string; raw?: unknown; network?: boolean }

let fetchImpl: typeof fetch = (...a) => fetch(...a);
let sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
/** Test hooks. */
export function _setLipilaTransport(f: typeof fetch, s?: (ms: number) => Promise<void>) { fetchImpl = f; if (s) sleep = s; }
export const lipilaConfig = () => ({
  baseUrl: val(LIPILA_BASE_URL, "LIPILA_BASE_URL").replace(/\/+$/, ""), apiKey: val(LIPILA_API_KEY, "LIPILA_API_KEY"),
  callbackUrl: val(LIPILA_CALLBACK_URL, "LIPILA_CALLBACK_URL"), webhookSecret: val(LIPILA_WEBHOOK_SECRET, "LIPILA_WEBHOOK_SECRET"),
  collectionPath: val(P.collectionPath, "LIPILA_COLLECTION_PATH", "/api/v1/collections/mobile-money"),
  disbursementPath: val(P.disbursementPath, "LIPILA_DISBURSEMENT_PATH", "/api/v1/disbursements/mobile-money"),
  collectionStatusPath: val(P.collectionStatusPath, "LIPILA_COLLECTION_STATUS_PATH"), disbursementStatusPath: val(P.disbursementStatusPath, "LIPILA_DISBURSEMENT_STATUS_PATH"),
  lookupPath: val(P.lookupPath, "LIPILA_LOOKUP_PATH")
});
export const lipilaConfigProblem = () => { const c = lipilaConfig(); return !c.baseUrl ? "LIPILA_BASE_URL is not set" : !c.apiKey ? "LIPILA_API_KEY is not set" : !c.callbackUrl ? "LIPILA_CALLBACK_URL is not set" : null; };

/** Lipila wants 260XXXXXXXXX. */
export const toAccountNumber = (msisdn: string) => String(msisdn).replace(/\D/g, "").replace(/^0(?=[79]\d{8}$)/, "260");

async function request(method: "GET" | "POST", url: string, body?: Record<string, unknown>): Promise<Omit<LipilaResult, "reference">> {
  const c = lipilaConfig();
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 20_000);
  try {
    const res = await fetchImpl(url, { method, signal: ctl.signal,
      headers: { accept: "application/json", "Content-Type": "application/json", "x-api-key": c.apiKey, callbackUrl: c.callbackUrl },
      body: body ? JSON.stringify(body) : undefined });
    const raw = await res.json().catch(() => ({})) as Record<string, any>;
    const d = (raw?.data ?? raw) as Record<string, any>;
    return { ok: res.ok, status: res.status, lipilaStatus: d?.status ?? raw?.status, identifier: d?.identifier ?? raw?.identifier,
      message: d?.message ?? raw?.message ?? raw?.error ?? raw?.title, raw, network: false };
  } catch {
    return { ok: false, status: 0, network: true, message: "Could not reach Lipila" };
  } finally { clearTimeout(t); }
}

/** POST with bounded backoff on 429 only (2 retries). Other errors return immediately. */
async function send(path: string, body: Record<string, unknown>): Promise<LipilaResult> {
  const problem = lipilaConfigProblem();
  if (problem) return { ok: false, status: 0, reference: String(body.referenceId), message: `Payments are not configured (${problem}).` };
  const url = `${lipilaConfig().baseUrl}${path}`;
  let r = await request("POST", url, body);
  for (const wait of [1_000, 3_000]) { if (r.status !== 429) break; await sleep(wait); r = await request("POST", url, body); }
  return { ...r, reference: String(body.referenceId) };
}

const kwacha = (ngwee: number) => Math.round(ngwee) / 100;

export const lipila = {
  /** Push prompt on the payer's phone. Payment is confirmed only by the webhook / status lookup, never by this reply. */
  collect: (referenceId: string, amountNgwee: number, msisdn: string, narration: string) =>
    send(lipilaConfig().collectionPath, { referenceId, amount: kwacha(amountNgwee), accountNumber: toAccountNumber(msisdn), currency: "ZMW", narration: narration.slice(0, 100) }),

  disburse: (referenceId: string, amountNgwee: number, msisdn: string, narration: string) =>
    send(lipilaConfig().disbursementPath, { referenceId, amount: kwacha(amountNgwee), accountNumber: toAccountNumber(msisdn), currency: "ZMW", narration: narration.slice(0, 100) }),

  /** Status lookup for reconciliation. null = not configured or unreachable (caller must not assume failure). */
  async status(kind: "collection" | "disbursement", referenceId: string, identifier?: string | null): Promise<{ status: string; message?: string; raw: unknown } | null> {
    const c = lipilaConfig();
    const tpl = kind === "collection" ? c.collectionStatusPath : c.disbursementStatusPath;
    if (!tpl || lipilaConfigProblem()) return null;
    const path = tpl.replace("{referenceId}", encodeURIComponent(referenceId)).replace("{identifier}", encodeURIComponent(identifier ?? ""));
    const r = await request("GET", `${c.baseUrl}${path}`);
    if (!r.ok || !r.lipilaStatus) return null;
    return { status: String(r.lipilaStatus), message: r.message, raw: r.raw };
  },

  /** Account-holder name lookup ("Wallet found: NAME. Is this you?"). null when not configured or not found. */
  async lookup(msisdn: string): Promise<string | null> {
    const c = lipilaConfig();
    if (!c.lookupPath || lipilaConfigProblem()) return null;
    const r = await request("POST", `${c.baseUrl}${c.lookupPath}`, { accountNumber: toAccountNumber(msisdn) });
    const raw = r.raw as { accountName?: string; name?: string; data?: { accountName?: string; name?: string } } | undefined;
    const name = raw?.data?.accountName ?? raw?.data?.name ?? raw?.accountName ?? raw?.name;
    return r.ok && name ? String(name).toUpperCase() : null;
  },
};

export { isSuccessStatus as isSuccessful, isFinalFailure as isFailed } from "./payments/lipila-webhook";

