/**
 * Account and support: sign-up OTP (proves the number before an account is created), "which security questions did I
 * choose", proof-of-delivery photos, and support tickets with threaded replies.
 */
import { createHash, randomInt } from "node:crypto";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { SMS_API_KEY, db } from "./config";
import { SECURITY_QUESTION_BANK } from "./domain-defaults";
import { normaliseMsisdn } from "./domain";
import { sendSms } from "./sms";
import { notify, requireAdmin, requireUser, str } from "./util";

const sha = (s: string) => createHash("sha256").update(s).digest("hex");
const OTP_TTL_MS = 10 * 60_000, OTP_MAX_SENDS = 3, OTP_MAX_TRIES = 5;

/** Sends a 6-digit sign-up code by SMS. Max 3 sends per number per hour; a number that already has an account is refused. */
export const auth_sendOtp = onCall({ secrets: [SMS_API_KEY] }, async (req) => {
  const m = normaliseMsisdn(req.data?.msisdn);
  if (!m) throw new HttpsError("invalid-argument", "Enter a valid Airtel, MTN or Zamtel number.");
  if ((await db.doc(`msisdnIndex/${m}`).get()).exists) throw new HttpsError("already-exists", "This number already has an account. Log in instead.");
  const ref = db.doc(`otp/${m}`);
  const code = String(randomInt(0, 1_000_000)).padStart(6, "0");
  await db.runTransaction(async (tx) => {
    const cur = (await tx.get(ref)).data();
    const windowStart = (cur?.windowStart as Timestamp | undefined)?.toMillis() ?? 0;
    const sends = Date.now() - windowStart < 3_600_000 ? (cur?.sends ?? 0) : 0;
    if (sends >= OTP_MAX_SENDS) throw new HttpsError("resource-exhausted", "Too many codes requested. Try again in an hour.");
    tx.set(ref, { hash: sha(`${m}:${code}`), expiresAt: Timestamp.fromMillis(Date.now() + OTP_TTL_MS), tries: 0, verified: false, sends: sends + 1,
      windowStart: sends === 0 ? Timestamp.now() : cur!.windowStart });
  });
  const r = await sendSms(m, `${code} is your SeloNaChipa sign-up code. It expires in 10 minutes. Never share it.`);
  if (!r.ok) throw new HttpsError("unavailable", "We couldn't send the SMS. Check the number and try again.");
  return { expiresInSec: OTP_TTL_MS / 1000 };
});

/** Checks the code; on success returns a one-time token that auth_register consumes. */
export const auth_verifyOtp = onCall(async (req) => {
  const m = normaliseMsisdn(req.data?.msisdn);
  const code = String(req.data?.code ?? "").replace(/\D/g, "");
  if (!m || code.length !== 6) throw new HttpsError("invalid-argument", "Enter the 6-digit code.");
  const ref = db.doc(`otp/${m}`);
  const token = sha(`${m}:${code}:${Date.now()}:${randomInt(1e9)}`).slice(0, 32);
  await db.runTransaction(async (tx) => {
    const o = (await tx.get(ref)).data();
    if (!o) throw new HttpsError("not-found", "Request a new code.");
    if ((o.expiresAt as Timestamp).toMillis() < Date.now()) throw new HttpsError("deadline-exceeded", "That code expired. Request a new one.");
    if ((o.tries ?? 0) >= OTP_MAX_TRIES) throw new HttpsError("resource-exhausted", "Too many wrong codes. Request a new one.");
    if (o.hash !== sha(`${m}:${code}`)) { tx.update(ref, { tries: FieldValue.increment(1) }); throw new HttpsError("permission-denied", "That code isn't right."); }
    tx.update(ref, { verified: true, tokenHash: sha(token), tokenExpiresAt: Timestamp.fromMillis(Date.now() + 30 * 60_000) });
  });
  return { otpToken: token };
});

/** Used by auth_register: true once, then the token is burnt. */
export async function consumeOtpToken(msisdn: string, token: unknown): Promise<boolean> {
  if (typeof token !== "string" || token.length !== 32) return false;
  const ref = db.doc(`otp/${msisdn}`);
  return db.runTransaction(async (tx) => {
    const o = (await tx.get(ref)).data();
    if (!o?.verified || o.tokenHash !== sha(token) || (o.tokenExpiresAt as Timestamp).toMillis() < Date.now()) return false;
    tx.delete(ref);
    return true;
  });
}

/** Which three questions this user chose (ids and wording only — never answers). */
export const auth_myQuestions = onCall(async (req) => {
  const { uid } = requireUser(req);
  const sec = (await db.doc(`users/${uid}/private/security`).get()).data();
  const ids = ((sec?.securityQuestions ?? []) as { questionId: string }[]).map((q) => q.questionId);
  const db2 = ids.length ? await db.getAll(...ids.map((id) => db.doc(`securityQuestionBank/${id}`))) : [];
  return { questions: ids.map((id, i) => ({ id, text: (db2[i]?.get("text_en") as string | undefined) ?? SECURITY_QUESTION_BANK[id] ?? id })) };
});

/** Supporting photo after a code-verified delivery (§9.1 step 7). Path must be the rider's own upload for this order. */
export const delivery_attachPod = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const kind = req.data?.kind === "order" ? "orders" : "parcels";
  const id = str(req.data?.orderId, "order");
  const path = String(req.data?.photoPath ?? "");
  if (!path.startsWith(`pod/${id}/${uid}_`)) throw new HttpsError("invalid-argument", "Upload the photo first.");
  const ref = db.doc(`${kind}/${id}`);
  const d = (await ref.get()).data();
  if (!d || d.riderUid !== uid) throw new HttpsError("not-found", "Not found.");
  await ref.update({ proofOfDelivery: path });
  await db.collection(`${kind}/${id}/timeline`).add({ event: "PROOF_OF_DELIVERY_PHOTO", at: FieldValue.serverTimestamp() });
  const led = await db.collection(`ledgers/${uid}/entries`).where("orderId", "==", id).limit(1).get();
  if (!led.empty) await led.docs[0].ref.update({ podUrl: path });
  return { ok: true };
});

/* ------------------------------------------------------------------ support tickets */

const CATEGORIES = ["Order or delivery", "Payment or refund", "Account and PIN", "Parcel", "Rider or seller conduct", "Other"];

export const support_createTicket = onCall(async (req) => {
  const { uid, role } = requireUser(req);
  const subject = str(req.data?.subject, "subject", 120);
  const message = str(req.data?.message, "message", 2000);
  if (message.length < 10) throw new HttpsError("invalid-argument", "Tell us a bit more (at least 10 characters).");
  const category = CATEGORIES.includes(req.data?.category) ? req.data.category : "Other";
  const recent = await db.collection("supportTickets").where("uid", "==", uid).where("status", "==", "OPEN").count().get();
  if (recent.data().count >= 5) throw new HttpsError("resource-exhausted", "You have 5 open requests. We'll reply to those first.");
  const u = (await db.doc(`users/${uid}`).get()).data() ?? {};
  const ref = db.collection("supportTickets").doc();
  const reference = `SUP-${ref.id.slice(0, 6).toUpperCase()}`;
  const b = db.batch();
  b.set(ref, { uid, role, reference, name: u.fullName ?? "", msisdn: u.msisdn ?? null, subject, category, orderRef: String(req.data?.orderRef ?? "").slice(0, 30),
    status: "OPEN", lastMessage: message.slice(0, 140), lastFrom: "user", unreadForUser: 0, createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() });
  b.set(ref.collection("messages").doc(), { from: "user", uid, text: message, at: FieldValue.serverTimestamp() });
  await b.commit();
  await notify(uid, "We got your message", `Request ${reference}: our team replies within 24 hours.`, "SUPPORT_RECEIVED", `selo://support/${ref.id}`);
  return { ticketId: ref.id, reference };
});

export const support_reply = onCall(async (req) => {
  const { uid } = requireUser(req);
  const id = str(req.data?.ticketId, "ticket");
  const text = str(req.data?.text, "message", 2000);
  const ref = db.doc(`supportTickets/${id}`);
  const t = (await ref.get()).data();
  if (!t || t.uid !== uid) throw new HttpsError("not-found", "Request not found.");
  await ref.collection("messages").add({ from: "user", uid, text, at: FieldValue.serverTimestamp() });
  await ref.update({ status: "OPEN", lastMessage: text.slice(0, 140), lastFrom: "user", unreadForUser: 0, updatedAt: FieldValue.serverTimestamp() });
  return { ok: true };
});

export const support_markRead = onCall(async (req) => {
  const { uid } = requireUser(req);
  const ref = db.doc(`supportTickets/${str(req.data?.ticketId, "ticket")}`);
  const t = (await ref.get()).data();
  if (t?.uid === uid) await ref.update({ unreadForUser: 0 });
  return { ok: true };
});

export const admin_replyTicket = onCall(async (req) => {
  const a = requireAdmin(req, "support", "operations");
  const id = str(req.data?.ticketId, "ticket");
  const text = str(req.data?.text, "reply", 2000);
  const close = req.data?.close === true;
  const ref = db.doc(`supportTickets/${id}`);
  const t = (await ref.get()).data();
  if (!t) throw new HttpsError("not-found", "Ticket not found.");
  await ref.collection("messages").add({ from: "support", uid: a.uid, agent: a.email, text, at: FieldValue.serverTimestamp() });
  await ref.update({ status: close ? "RESOLVED" : "AWAITING_USER", lastMessage: text.slice(0, 140), lastFrom: "support", unreadForUser: FieldValue.increment(1),
    assignee: a.email, updatedAt: FieldValue.serverTimestamp() });
  await notify(t.uid, `Support · ${t.reference}`, text.slice(0, 120), "SUPPORT_REPLY", `selo://support/${id}`);
  return { ok: true };
});
