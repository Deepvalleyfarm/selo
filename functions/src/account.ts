/**
 * Account and support: sign-up OTP (proves the number before an account is created), "which security questions did I
 * choose", proof-of-delivery photos, and support tickets with threaded replies.
 */
import { createHash } from "node:crypto";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { db } from "./config";
import { SECURITY_QUESTION_BANK } from "./domain-defaults";
import { notify, requireAdmin, requireUser, str } from "./util";

const sha = (s: string) => createHash("sha256").update(s).digest("hex");
/* Sign-up OTP now runs on Beem Africa: see beem/otp.controller.ts (auth_sendOtp / auth_verifyOtp). */

/** True while a verified, unexpired token exists (does not burn it). Lets a person mid-registration use the address search. */
export async function peekOtpToken(msisdn: string, token: unknown): Promise<boolean> {
  if (typeof token !== "string" || token.length !== 32) return false;
  const o = (await db.doc(`otp/${msisdn}`).get()).data();
  return !!o?.verified && o.tokenHash === sha(token) && (o.tokenExpiresAt as Timestamp).toMillis() > Date.now();
}

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
