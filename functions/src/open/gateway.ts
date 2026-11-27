/**
 * SeloNachipa Open Network — HTTP gateway for /api/v1.
 *
 * Every authenticated request:
 *   1. verifies the bearer token (HS256, 1 h) AND re-reads the partner and client, so suspension or revocation
 *      blocks access immediately (not at token expiry);
 *   2. checks the route's scope against the token's scopes (which already respect enabled services);
 *   3. applies the partner's per-minute rate limit;
 *   4. for writes, honours Idempotency-Key: the same key + same body replays the stored response, a different body is
 *      rejected (409), so retries never create duplicates;
 *   5. is written to the audit log and the partner's daily usage counters.
 * Partners only ever see their own data: every handler filters by the token's partner id.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import type { Request } from "firebase-functions/v2/https";
import { db } from "../config";
import { sha256, signToken, verifySecret, verifyToken } from "./crypto";
import { accessProblem, effectiveScopes, type Env, OPEN_API_JWT_SECRET, type Partner, secretValue } from "./model";

export interface Ctx { pid: string; cid: string; env: Env; scopes: string[]; partner: Partner; params: Record<string, string>; body: any; query: Record<string, string>; ip: string }
export interface Reply { status: number; body: unknown }
export class ApiError extends Error { constructor(public status: number, public code: string, message: string, public details?: unknown) { super(message); } }
type Handler = (c: Ctx) => Promise<Reply | unknown>;
interface Route { method: string; re: RegExp; keys: string[]; scope: string | null; handler: Handler; idempotent: boolean }
const routes: Route[] = [];

/** Register a route: path like "/products/:externalId/stock". */
export function route(method: string, path: string, scope: string | null, handler: Handler, opts: { idempotent?: boolean } = {}) {
  const keys: string[] = [];
  const re = new RegExp("^" + path.replace(/:(\w+)/g, (_, k) => { keys.push(k); return "([^/]+)"; }) + "/?$");
  routes.push({ method, re, keys, scope, handler, idempotent: opts.idempotent ?? method !== "GET" });
}
export function matchRoute(method: string, path: string) {
  for (const r of routes) { if (r.method !== method) continue; const m = r.re.exec(path); if (m) return { r, params: Object.fromEntries(r.keys.map((k, i) => [k, decodeURIComponent(m[i + 1])])) }; }
  return null;
}
export const routeTable = () => routes.map((r) => ({ method: r.method, path: r.re.source, scope: r.scope }));

const jwtKey = () => secretValue(OPEN_API_JWT_SECRET, "OPEN_API_JWT_SECRET");
export const ok = (body: unknown, status = 200): Reply => ({ status, body });

/* ---------------------------------------------------------------- OAuth 2.0 client credentials */
export async function issueToken(clientId: string, clientSecret: string, scopeStr?: string): Promise<Reply> {
  const client = clientId ? (await db.doc(`openClients/${clientId}`).get()).data() : undefined;
  if (!client || !verifySecret(clientSecret ?? "", client.salt, client.hash)) return ok({ error: "invalid_client", error_description: "Unknown client or wrong secret." }, 401);
  const partner = (await db.doc(`openPartners/${client.partnerId}`).get()).data() as Partner | undefined;
  const problem = accessProblem(partner ? { ...partner } : null, { status: client.status, env: client.env });
  if (problem) return ok({ error: "unauthorized_client", error_description: problem }, 403);
  const scopes = effectiveScopes(partner!, scopeStr ? scopeStr.split(/[ ,]+/).filter(Boolean) : null);
  if (!scopes.length) return ok({ error: "invalid_scope", error_description: "No permitted scopes for this client." }, 400);
  const token = signToken({ pid: client.partnerId, cid: clientId, env: client.env, scope: scopes }, jwtKey());
  await db.doc(`openClients/${clientId}`).update({ lastTokenAt: FieldValue.serverTimestamp() }).catch(() => undefined);
  return ok({ access_token: token, token_type: "Bearer", expires_in: 3600, scope: scopes.join(" "), environment: client.env });
}

/* ---------------------------------------------------------------- rate limit */
export async function rateLimited(pid: string, limitPerMin: number, nowMs = Date.now()): Promise<boolean> {
  const minute = Math.floor(nowMs / 60_000);
  const ref = db.doc(`openRate/${pid}_${minute}`);
  const n = await db.runTransaction(async (tx) => { const c = ((await tx.get(ref)).get("n") ?? 0) as number; tx.set(ref, { n: c + 1, pid, expireAt: Timestamp.fromMillis(nowMs + 3 * 60_000) }); return c + 1; });
  return n > Math.max(1, limitPerMin);
}

/* ---------------------------------------------------------------- request pipeline */
export async function handle(req: Request): Promise<Reply & { headers?: Record<string, string> }> {
  const path = req.path.replace(/^\/api\/v1/, "") || "/";
  const t0 = Date.now();
  const ip = String(req.headers["x-forwarded-for"] ?? req.ip ?? "").split(",")[0].trim();
  if (req.method === "POST" && path === "/oauth/token") {
    const b = typeof req.body === "object" && req.body ? req.body : {};
    const basic = String(req.get("authorization") ?? "").match(/^Basic (.+)$/);
    const [bid, bsec] = basic ? Buffer.from(basic[1], "base64").toString().split(":") : [];
    if ((b.grant_type ?? "client_credentials") !== "client_credentials") return ok({ error: "unsupported_grant_type" }, 400);
    return issueToken(String(b.client_id ?? bid ?? ""), String(b.client_secret ?? bsec ?? ""), b.scope);
  }
  const m = matchRoute(req.method, path);
  if (!m) return ok({ error: { code: "not_found", message: `No route ${req.method} /api/v1${path}` } }, 404);
  if (m.r.scope === null) return { status: 200, body: await m.r.handler({ params: m.params, query: req.query as any, body: req.body } as Ctx) };

  const claims = verifyToken(String(req.get("authorization") ?? "").replace(/^Bearer /, ""), jwtKey());
  if (!claims) return ok({ error: { code: "unauthorized", message: "Missing, invalid or expired access token." } }, 401);
  const [pSnap, cSnap] = await Promise.all([db.doc(`openPartners/${claims.pid}`).get(), db.doc(`openClients/${claims.cid}`).get()]);
  const partner = pSnap.exists ? ({ id: pSnap.id, ...pSnap.data() } as Partner) : null;
  const problem = accessProblem(partner, cSnap.exists ? { status: cSnap.get("status"), env: cSnap.get("env") } : null);
  if (problem) return ok({ error: { code: "forbidden", message: `Access blocked: ${problem}.` } }, 403);
  const scopes = effectiveScopes(partner!, claims.scope); // re-check: an admin may have removed a scope since the token was issued
  if (m.r.scope && !scopes.includes(m.r.scope)) return ok({ error: { code: "insufficient_scope", message: `This call needs the ${m.r.scope} scope.` } }, 403);
  if (await rateLimited(claims.pid, partner!.rateLimitPerMin ?? 120)) return { ...ok({ error: { code: "rate_limited", message: "Too many requests. Retry after a minute." } }, 429), headers: { "Retry-After": "60" } };

  const ctx: Ctx = { pid: claims.pid, cid: claims.cid, env: claims.env, scopes, partner: partner!, params: m.params, body: req.body ?? {}, query: (req.query ?? {}) as any, ip };
  let reply: Reply;
  const idemKey = m.r.idempotent ? String(req.get("idempotency-key") ?? "").slice(0, 120) : "";
  const idemRef = idemKey ? db.doc(`openIdempotency/${sha256(`${claims.pid}|${claims.env}|${req.method}|${path}|${idemKey}`)}`) : null;
  const fingerprint = sha256(JSON.stringify(req.body ?? {}));
  try {
    if (m.r.idempotent && !idemKey) throw new ApiError(400, "idempotency_key_required", "Send an Idempotency-Key header (a unique value per operation) on every write.");
    const prior = idemRef ? (await idemRef.get()).data() : undefined;
    if (prior) {
      if (prior.fingerprint !== fingerprint) throw new ApiError(409, "idempotency_conflict", "This Idempotency-Key was already used with a different request body.");
      reply = { status: prior.status, body: { ...(prior.body ?? {}), idempotentReplay: true } };
    } else {
      const out = await m.r.handler(ctx);
      reply = out && typeof out === "object" && "status" in (out as any) && "body" in (out as any) ? (out as Reply) : ok(out);
      if (idemRef && reply.status < 500) await idemRef.set({ pid: claims.pid, fingerprint, status: reply.status, body: reply.body ?? null, at: FieldValue.serverTimestamp(),
        expireAt: Timestamp.fromMillis(Date.now() + 7 * 86_400_000) });
    }
  } catch (e) {
    if (e instanceof ApiError) reply = ok({ error: { code: e.code, message: e.message, details: e.details ?? undefined } }, e.status);
    else { logger.error("open api error", path, String(e)); reply = ok({ error: { code: "internal", message: "Something went wrong. Retry with the same Idempotency-Key." } }, 500); }
  }
  // audit + usage (never block the response on these)
  const group = path.split("/")[1] ?? "root", day = new Date().toISOString().slice(0, 10);
  void Promise.all([
    db.collection("openAudit").add({ pid: claims.pid, cid: claims.cid, env: claims.env, method: req.method, path, status: reply.status, ms: Date.now() - t0, ip,
      idempotencyKey: idemKey || null, at: FieldValue.serverTimestamp() }),
    db.doc(`openUsage/${claims.pid}_${claims.env}_${day}`).set({ pid: claims.pid, env: claims.env, day, calls: FieldValue.increment(1),
      [`groups.${group}`]: FieldValue.increment(1), ...(reply.status >= 400 ? { errors: FieldValue.increment(1) } : {}) }, { merge: true }),
  ]).catch(() => undefined);
  return reply;
}

