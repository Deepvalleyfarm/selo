/**
 * BEEM-OTP-001 · OTP routes.
 *  - Callables used by the Android app: auth_sendOtp / auth_verifyOtp.
 *  - HTTP (web and other apps): POST /api/auth/otp/request, POST /api/auth/otp/verify (Hosting rewrite /api/** → api).
 * SeloNaChipa glue: "signup" refuses numbers that already have an account, and a verified number receives a one-time
 * otpToken that auth_register consumes (so registration can't skip verification).
 */
import { createHash, randomBytes } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { db } from "../config";
import { BEEM_SECRETS, beemConfig, cleanMsisdn } from "./beem.config";
import type { Meta, RequestResult, VerifyResult } from "./beem-otp.service";
import { otpService } from "./index";

const sha = (s: string) => createHash("sha256").update(s).digest("hex");
const CONTEXTS = new Set(["signup", "phone_change", "pin_reset"]);

export function metaOf(req: { ip?: string; headers?: Record<string, unknown>; get?: (h: string) => string | undefined }, uid?: string | null): Meta {
  const fwd = String(req.headers?.["x-forwarded-for"] ?? "").split(",")[0].trim();
  return { ip: fwd || req.ip || null, userAgent: (req.get?.("user-agent") ?? String(req.headers?.["user-agent"] ?? "")) || null, uid: uid ?? null };
}

/** Request flow with SeloNaChipa's account checks in front of the shared service. */
export async function requestOtp(msisdnRaw: unknown, appContext: unknown, meta: Meta): Promise<RequestResult> {
  const ctx = CONTEXTS.has(String(appContext)) ? String(appContext) : "signup";
  const m = cleanMsisdn(msisdnRaw, beemConfig().countryCodes);
  // Check before sending, so no SMS is spent on a number that can't sign up.
  if (m && ctx === "signup" && (await db.doc(`msisdnIndex/+${m}`).get()).exists)
    return { success: false, reason: "exists", message: "This number already has an account. Log in instead." };
  return otpService.request(msisdnRaw, ctx, meta);
}

/** Verify flow; on success issues the one-time registration token. */
export async function verifyOtp(pinId: unknown, pin: unknown, msisdnRaw: unknown, meta: Meta): Promise<VerifyResult & { otpToken?: string }> {
  const r = await otpService.verify(pinId, pin, msisdnRaw, meta);
  if (!r.success) return r;
  const token = randomBytes(16).toString("hex");
  await db.doc(`otp/+${r.msisdn}`).set({ verified: true, tokenHash: sha(token), tokenExpiresAt: Timestamp.fromMillis(Date.now() + 30 * 60_000),
    appContext: r.appContext, verifiedAt: Timestamp.now() });
  if (r.appContext === "phone_change" && meta.uid) await db.doc(`users/${meta.uid}`).set({ msisdnVerified: true, msisdnVerifiedAt: Timestamp.now() }, { merge: true });
  return { ...r, otpToken: token };
}

const fail = (r: { reason: string; message: string; beemCode?: number | null; retryAfterSeconds?: number }) => {
  const code = r.reason === "rate_limited" ? "resource-exhausted" : r.reason === "exists" ? "already-exists" : r.reason === "invalid_number" || r.reason === "invalid_input" ? "invalid-argument"
    : r.reason === "config" || r.reason === "auth" ? "failed-precondition" : r.reason === "network" || r.reason === "server" || r.reason === "beem_101" ? "unavailable" : "permission-denied";
  return new HttpsError(code, r.message, { reason: r.reason, beemCode: r.beemCode ?? null, retryAfterSeconds: r.retryAfterSeconds ?? null });
};

/** Sends a Beem OTP. Returns { pinId, expiresInSeconds } — never the PIN. */
export const auth_sendOtp = onCall({ secrets: BEEM_SECRETS }, async (req: CallableRequest) => {
  const r = await requestOtp(req.data?.msisdn, req.data?.appContext ?? "signup", metaOf(req.rawRequest, req.auth?.uid));
  if (!r.success) throw fail(r);
  return { success: true, pinId: r.pinId, expiresInSeconds: r.expiresInSeconds, expiresInSec: r.expiresInSeconds };
});

/** Verifies the PIN (typed or auto-read from the SMS). Body: { msisdn, pinId, pin }. */
export const auth_verifyOtp = onCall({ secrets: BEEM_SECRETS }, async (req: CallableRequest) => {
  const r = await verifyOtp(req.data?.pinId, req.data?.pin ?? req.data?.code, req.data?.msisdn, metaOf(req.rawRequest, req.auth?.uid));
  if (!r.success) throw fail(r);
  return { success: true, otpToken: r.otpToken };
});
