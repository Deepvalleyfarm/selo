/**
 * HTTP transport to Beem. TLS certificates are verified normally (we do NOT disable certificate checks).
 * Network failures and timeouts are retried once after 2 s; HTTP responses (including 4xx auth/validation errors)
 * are never retried.
 */
import { authHeader, type BeemConfig } from "./beem.config";

export interface BeemHttpResult { ok: boolean; status: number; body: any; network: boolean; latencyMs: number }

const TIMEOUT_MS = 10_000;
let fetchImpl: typeof fetch = (...a) => fetch(...a);
let sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
/** Test hooks. */
export function _setTransport(f: typeof fetch, s?: (ms: number) => Promise<void>) { fetchImpl = f; if (s) sleep = s; }

async function once(method: "GET" | "POST", url: string, cfg: BeemConfig, body?: unknown): Promise<BeemHttpResult> {
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  const started = Date.now();
  try {
    const r = await fetchImpl(url, { method, signal: ctl.signal,
      headers: { Authorization: authHeader(cfg), "Content-Type": "application/json", Accept: "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await r.text();
    let json: any = null; try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text.slice(0, 300) }; }
    return { ok: r.ok, status: r.status, body: json, network: false, latencyMs: Date.now() - started };
  } catch {
    return { ok: false, status: 0, body: null, network: true, latencyMs: Date.now() - started };
  } finally { clearTimeout(t); }
}

export async function beemRequest(method: "GET" | "POST", url: string, cfg: BeemConfig, body?: unknown): Promise<BeemHttpResult> {
  const first = await once(method, url, cfg, body);
  if (!first.network) return first;
  await sleep(2_000);
  return once(method, url, cfg, body);
}

/** Beem wraps codes as {data:{message:{code,message}}} (OTP) or {code,message} / {successful, ...} (SMS). */
export function beemCode(body: any): { code: number | null; message: string } {
  const m = body?.data?.message ?? body?.message ?? body;
  const code = Number(m?.code ?? body?.code);
  return { code: Number.isFinite(code) ? code : null, message: String(m?.message ?? body?.message ?? "") };
}
