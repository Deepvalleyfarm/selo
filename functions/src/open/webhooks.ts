/**
 * SeloNachipa Open Network — signed webhooks.
 *
 * Events are written to an outbox (openEvents) first, then delivered, so nothing is lost when a partner is down:
 *  - deterministic event ids (type + subject + version) → the same change never produces two events (dedup);
 *  - headers webhook-id / webhook-timestamp / webhook-signature (HMAC-SHA256, Standard Webhooks format) so partners can
 *    verify and de-duplicate on their side too;
 *  - retries with back-off (1 m → 24 h, 9 attempts), then FAILED and kept for manual or API replay;
 *  - full history per partner (GET /api/v1/webhooks/events) and replay (POST /api/v1/webhooks/events/:id/replay).
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { DB_ID, db } from "../config";
import { decrypt, sha256, signWebhook } from "./crypto";
import { type Env, type EventType, OPEN_API_ENC_KEY, OPEN_SECRETS, secretValue } from "./model";

export const BACKOFF_MIN = [1, 5, 15, 60, 180, 360, 720, 1440];
export const nextDelayMin = (attempt: number) => BACKOFF_MIN[Math.min(attempt, BACKOFF_MIN.length - 1)];

/** Deterministic id: re-running a trigger can't create a second copy of the same event. */
export const eventId = (pid: string, type: string, subject: string, version: string | number = 1) => `evt_${sha256(`${pid}|${type}|${subject}|${version}`).slice(0, 28)}`;

export async function emit(pid: string, env: Env, type: EventType, subject: string, data: Record<string, unknown>, version: string | number = 1) {
  if (!pid) return null;
  const id = eventId(pid, type, subject, version);
  await db.doc(`openEvents/${id}`).create({ pid, env, type, subject, data, status: "PENDING", attempts: 0, nextAttemptAt: Timestamp.now(),
    createdAt: FieldValue.serverTimestamp() }).catch(() => undefined); // already emitted → ignore
  return id;
}

let transport: typeof fetch = (...a) => fetch(...a);
export function _setWebhookTransport(f: typeof fetch) { transport = f; }

export async function deliver(id: string): Promise<string> {
  const ref = db.doc(`openEvents/${id}`);
  const ev = (await ref.get()).data();
  if (!ev || ev.status === "DELIVERED" || ev.status === "SKIPPED") return ev?.status ?? "MISSING";
  const p = (await db.doc(`openPartners/${ev.pid}`).get()).data();
  const hook = p?.webhook;
  if (!hook?.url || !(hook.events ?? []).includes(ev.type) || !hook.secretEnc) { await ref.update({ status: "SKIPPED", reason: !hook?.url ? "no webhook URL" : "not subscribed" }); return "SKIPPED"; }
  const ts = Math.floor(Date.now() / 1000);
  const body = JSON.stringify({ id, type: ev.type, created: (ev.createdAt as Timestamp | undefined)?.toDate().toISOString() ?? new Date().toISOString(), environment: ev.env, data: ev.data });
  let status = 0, err: string | null = null;
  try {
    const secret = decrypt(hook.secretEnc, secretValue(OPEN_API_ENC_KEY, "OPEN_API_ENC_KEY"));
    const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 10_000);
    const r = await transport(hook.url, { method: "POST", signal: ctl.signal, body, headers: { "content-type": "application/json", "user-agent": "SeloNachipa-Webhooks/1",
      "webhook-id": id, "webhook-timestamp": String(ts), "webhook-signature": signWebhook(secret, id, ts, body) } }).finally(() => clearTimeout(t));
    status = r.status;
  } catch (e) { err = String(e).slice(0, 200); }
  const attempts = (ev.attempts ?? 0) + 1;
  if (status >= 200 && status < 300) { await ref.update({ status: "DELIVERED", attempts, lastStatus: status, deliveredAt: FieldValue.serverTimestamp(), lastError: null }); return "DELIVERED"; }
  const failed = attempts >= BACKOFF_MIN.length + 1;
  await ref.update({ status: failed ? "FAILED" : "RETRYING", attempts, lastStatus: status || null, lastError: err ?? `HTTP ${status}`,
    nextAttemptAt: failed ? null : Timestamp.fromMillis(Date.now() + nextDelayMin(attempts - 1) * 60_000) });
  return failed ? "FAILED" : "RETRYING";
}

export const onOpenEvent = onDocumentCreated({ document: "openEvents/{id}", database: DB_ID, secrets: OPEN_SECRETS }, async (e) => { await deliver(e.params.id); });

/** Every minute: retry due events (partner back online → backlog drains in order). */
export const scheduled_openWebhookRetry = onSchedule({ schedule: "every 1 minutes", secrets: OPEN_SECRETS }, async () => {
  const due = await db.collection("openEvents").where("status", "==", "RETRYING").where("nextAttemptAt", "<=", Timestamp.now()).orderBy("nextAttemptAt").limit(200).get();
  for (const d of due.docs) await deliver(d.id);
});

/** Replay (partner API or back office): resets a FAILED/SKIPPED/DELIVERED event and sends it again with the same id. */
export async function replay(id: string, pid?: string) {
  const ref = db.doc(`openEvents/${id}`); const ev = (await ref.get()).data();
  if (!ev || (pid && ev.pid !== pid)) return null;
  await ref.update({ status: "PENDING", attempts: 0, nextAttemptAt: Timestamp.now(), replayedAt: FieldValue.serverTimestamp() });
  return deliver(id);
}
