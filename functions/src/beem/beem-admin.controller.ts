/**
 * BEEM-OTP-001 · Module 4 backend: health status, scheduled checks and admin log APIs (Super Admin only).
 *  - beem_healthCheck   runs every 5 minutes → beem_health/current (+ 7-day history)
 *  - admin_beemHealth   callable "Recheck" from the monitor
 *  - api (HTTP)         POST /api/auth/otp/request · POST /api/auth/otp/verify
 *                       GET  /api/admin/beem/health · GET /api/admin/beem/logs?type=otp|sms&from=&to=&event=&status=&q=&limit=&after=
 * The back office reads otp_logs / sms_logs / beem_stats directly; Firestore rules restrict them to super_admin.
 */
import { getAuth } from "firebase-admin/auth";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { db } from "../config";
import { BEEM_SECRETS, maskMsisdn } from "./beem.config";
import { checkBeemHealth, type BeemHealth } from "./beem-health.service";
import { metaOf, requestOtp, verifyOtp } from "./otp.controller";

export async function runHealthCheck(by: string): Promise<BeemHealth> {
  const h = await checkBeemHealth();
  const prev = (await db.doc("beem_health/current").get()).data();
  await db.doc("beem_health/current").set({ ...h, checkedBy: by, updatedAt: FieldValue.serverTimestamp() });
  await db.collection("beem_health/current/history").add({ state: h.state, otp: h.otp.state, sms: h.sms.state, balance: h.balance, checkedAt: h.checkedAt,
    expiresAt: Timestamp.fromMillis(Date.now() + 7 * 86_400_000) });
  if (prev?.state && prev.state !== h.state && h.state !== "LIVE")
    await db.collection("opsAlerts").add({ type: "BEEM_STATUS", level: h.state, message: `Beem integration is ${h.state}: OTP ${h.otp.detail}; SMS ${h.sms.detail}`,
      status: "OPEN", at: FieldValue.serverTimestamp() });
  return h;
}

export const beem_healthCheck = onSchedule({ schedule: "every 5 minutes", secrets: BEEM_SECRETS, timeoutSeconds: 60 }, async () => { await runHealthCheck("schedule"); });

const requireSuperAdmin = (token: Record<string, unknown> | undefined) => {
  if (token?.admin !== "super_admin") throw new HttpsError("permission-denied", "The Beem monitor is for Super Admins only.");
};

export const admin_beemHealth = onCall({ secrets: BEEM_SECRETS }, async (req) => {
  requireSuperAdmin(req.auth?.token);
  return runHealthCheck(req.auth?.token.email ?? req.auth!.uid);
});

/** Log query used by the HTTP API (the back office queries Firestore directly with the same filters). */
async function queryLogs(type: "otp" | "sms", q: Record<string, string>) {
  let ref: FirebaseFirestore.Query = db.collection(type === "otp" ? "otp_logs" : "sms_logs").orderBy("created_at", "desc");
  if (q.from) ref = ref.where("created_at", ">=", Timestamp.fromDate(new Date(q.from)));
  if (q.to) ref = ref.where("created_at", "<=", Timestamp.fromDate(new Date(q.to)));
  if (q.event) ref = ref.where("event_type", "==", q.event);
  if (q.status === "success" || q.status === "failed") ref = ref.where("success", "==", q.status === "success");
  if (q.q && /^\d{6,15}$/.test(q.q)) ref = ref.where("msisdn", "==", q.q);
  if (q.after) { const cur = await db.doc(`${type === "otp" ? "otp_logs" : "sms_logs"}/${q.after}`).get(); if (cur.exists) ref = ref.startAfter(cur); }
  const snap = await ref.limit(Math.min(200, Number(q.limit) || 50)).get();
  return snap.docs.map((d) => { const x = d.data(); return { id: d.id, ...x, msisdn: maskMsisdn(String(x.msisdn ?? "")), created_at: (x.created_at as Timestamp | undefined)?.toDate().toISOString() ?? null }; });
}

/** HTTP surface for web clients and other applications. */
export const api = onRequest({ secrets: BEEM_SECRETS, cors: true }, async (req, res) => {
  const path = req.path.replace(/^\/api/, "");
  try {
    if (req.method === "POST" && path === "/auth/otp/request") {
      const r = await requestOtp(req.body?.msisdn, req.body?.appContext, metaOf(req));
      res.status(r.success ? 200 : r.reason === "rate_limited" ? 429 : r.reason === "config" ? 503 : 400)
        .json(r.success ? { success: true, pinId: r.pinId, expiresInSeconds: r.expiresInSeconds } : r);
      return;
    }
    if (req.method === "POST" && path === "/auth/otp/verify") {
      const r = await verifyOtp(req.body?.pinId, req.body?.pin, req.body?.msisdn, metaOf(req));
      res.status(r.success ? 200 : 400).json(r.success ? { success: true, verified: true, otpToken: r.otpToken } : r);
      return;
    }
    if (req.method === "GET" && path.startsWith("/admin/beem/")) {
      const token = (req.get("authorization") ?? "").replace(/^Bearer /, "");
      const decoded = token ? await getAuth().verifyIdToken(token).catch(() => null) : null;
      if (decoded?.admin !== "super_admin") { res.status(403).json({ error: "Super Admin only" }); return; }
      if (path === "/admin/beem/health") { res.json(req.query.recheck ? await runHealthCheck(decoded.email ?? decoded.uid) : (await db.doc("beem_health/current").get()).data() ?? null); return; }
      if (path === "/admin/beem/logs") { res.json({ rows: await queryLogs(req.query.type === "sms" ? "sms" : "otp", req.query as Record<string, string>) }); return; }
    }
    res.status(404).json({ error: "Not found" });
  } catch (e) {
    logger.error("api error", path, String(e));
    res.status(500).json({ error: "Something went wrong" });
  }
});
