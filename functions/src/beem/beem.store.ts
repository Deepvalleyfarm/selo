/**
 * Persistence for the Beem module. The interface is storage-agnostic so other applications (e.g. Mabala on
 * PostgreSQL + Prisma — see migrations/001_beem_logs.sql) can plug in their own implementation. SeloNaChipa uses
 * Firestore collections with exactly the same field names as the SQL tables: otp_logs, sms_logs.
 */
import { randomUUID } from "node:crypto";
import { FieldValue, Timestamp } from "firebase-admin/firestore";

export interface OtpLog { msisdn: string; pin_id: string | null; event_type: "OTP_REQUESTED" | "OTP_VERIFIED" | "OTP_FAILED"; beem_code: number | null;
  beem_message: string | null; app_context: string; ip_address: string | null; user_agent: string | null; success: boolean; error_detail: string | null }
export interface SmsLog { msisdn: string; event_type: string; message_body: string; beem_code: number | null; beem_message: string | null;
  reference_id: string | null; application: string; success: boolean; error_detail: string | null }
export interface OtpSession { msisdn: string; pinId: string; appContext: string; requestedAt: number; attempts: number; verified: boolean; uid?: string | null }

export interface BeemStore {
  logOtp(e: OtpLog): Promise<void>;
  logSms(e: SmsLog): Promise<void>;
  getSession(msisdn: string): Promise<OtpSession | null>;
  saveSession(s: OtpSession): Promise<void>;
  /** Records one hit in a sliding window and returns the count inside it (including this one). */
  hit(key: string, windowMs: number): Promise<number>;
}

const day = () => new Date().toISOString().slice(0, 10);

export function firestoreStore(db: FirebaseFirestore.Firestore): BeemStore {
  const stat = (fields: Record<string, number>) => db.doc(`beem_stats/${day()}/shards/${Math.floor(Math.random() * 5)}`)
    .set(Object.fromEntries(Object.entries(fields).map(([k, v]) => [k, FieldValue.increment(v)])), { merge: true })
    .then(() => db.doc(`beem_stats/${day()}`).set({ day: day() }, { merge: true })).catch(() => undefined);
  return {
    async logOtp(e) {
      await db.collection("otp_logs").doc(randomUUID()).set({ ...e, created_at: FieldValue.serverTimestamp() });
      const k = e.event_type === "OTP_REQUESTED" ? (e.success ? "otp_requested" : "otp_request_failed") : e.event_type === "OTP_VERIFIED" ? "otp_verified" : "otp_failed";
      await stat({ [k]: 1, ...(e.beem_code != null ? { [`code_${e.beem_code}`]: 1 } : {}) });
    },
    async logSms(e) {
      await db.collection("sms_logs").doc(randomUUID()).set({ ...e, created_at: FieldValue.serverTimestamp() });
      await stat({ [e.success ? "sms_sent" : "sms_failed"]: 1 });
    },
    async getSession(msisdn) {
      const d = await db.doc(`otp_sessions/${msisdn}`).get();
      return d.exists ? (d.data() as OtpSession) : null;
    },
    async saveSession(s) { await db.doc(`otp_sessions/${s.msisdn}`).set({ ...s, expiresAt: Timestamp.fromMillis(s.requestedAt + 24 * 3_600_000) }); },
    async hit(key, windowMs) {
      const ref = db.doc(`otp_rate/${key.replace(/[^\w.:-]/g, "_")}`);
      return db.runTransaction(async (tx) => {
        const now = Date.now();
        const hits = (((await tx.get(ref)).get("hits") as number[] | undefined) ?? []).filter((t) => t > now - windowMs);
        hits.push(now);
        tx.set(ref, { hits: hits.slice(-50), expiresAt: Timestamp.fromMillis(now + windowMs) });
        return hits.length;
      });
    },
  };
}

/** In-memory store for unit tests and local runs. */
export function memoryStore(): BeemStore & { otp: OtpLog[]; sms: SmsLog[]; sessions: Map<string, OtpSession> } {
  const otp: OtpLog[] = [], sms: SmsLog[] = [], sessions = new Map<string, OtpSession>(), hits = new Map<string, number[]>();
  return { otp, sms, sessions,
    async logOtp(e) { otp.push(e); }, async logSms(e) { sms.push(e); },
    async getSession(m) { return sessions.get(m) ?? null; }, async saveSession(s) { sessions.set(s.msisdn, { ...s }); },
    async hit(k, w) { const now = Date.now(); const h = (hits.get(k) ?? []).filter((t) => t > now - w); h.push(now); hits.set(k, h); return h.length; } };
}
