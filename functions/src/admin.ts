import { getAuth } from "firebase-admin/auth";
import { getMessaging } from "firebase-admin/messaging";
import { FieldValue } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { DEFAULT_CONFIG, LIPILA_API_KEY, db } from "./config";
import { completeDelivery, disburse, refund, retryPayout } from "./escrow";
import { AdminRole, audit, notify, requireAdmin, securityEvent, str } from "./util";
import { createHash, randomBytes } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";

/**
 * Back office APIs. Every write is audited (auditLogs) and money/role/KYC changes only happen here,
 * so the web back office and the Android app always agree.
 */
const ip = (r: { rawRequest: { ip?: string } }) => r.rawRequest.ip;

export const admin_updateConfig = onCall(async (req) => {
  const a = requireAdmin(req, "finance", "operations");
  const changes = (req.data?.changes ?? {}) as Record<string, unknown>;
  const allowed = Object.keys(DEFAULT_CONFIG);
  const clean: Record<string, number> = {};
  for (const [k, v] of Object.entries(changes)) {
    if (!allowed.includes(k)) throw new HttpsError("invalid-argument", `Unknown setting ${k}.`);
    if (typeof v !== "number" || !Number.isFinite(v) || v < 0) throw new HttpsError("invalid-argument", `${k} must be a positive number.`);
    clean[k] = v;
  }
  const ref = db.doc("platformConfig/default");
  const before = await db.runTransaction(async (tx) => {
    const cur = (await tx.get(ref)).data() ?? {};
    if (typeof req.data?.expectedVersion === "number" && (cur.version ?? 0) !== req.data.expectedVersion)
      throw new HttpsError("aborted", "Someone else changed the settings. Reload and try again.");
    tx.set(ref, { ...clean, version: (cur.version ?? 0) + 1, updatedBy: a.email, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    return cur;
  });
  await audit(a, "config.update", "platformConfig/default", Object.fromEntries(Object.keys(clean).map((k) => [k, before[k] ?? null])), clean, ip(req));
  return { ok: true, version: (before.version ?? 0) + 1 };
});

export const admin_setKyc = onCall(async (req) => {
  const a = requireAdmin(req, "compliance");
  const uid = str(req.data?.uid, "user");
  const approve = req.data?.approve === true;
  const reason = approve ? null : str(req.data?.reason, "reason", 300);
  const user = (await db.doc(`users/${uid}`).get()).data();
  if (!user) throw new HttpsError("not-found", "User not found.");
  await db.doc(`users/${uid}`).update({ accountStatus: approve ? "ACTIVE" : user.accountStatus, kycStatus: approve ? "VERIFIED" : "REJECTED",
    kycReason: reason, kycDecidedBy: a.email, kycDecidedAt: FieldValue.serverTimestamp() });
  await db.doc(`kycQueue/${uid}`).set({ status: approve ? "APPROVED" : "REJECTED", reason, decidedBy: a.email, decidedAt: FieldValue.serverTimestamp() }, { merge: true });
  await audit(a, approve ? "kyc.approve" : "kyc.reject", `users/${uid}`, { kycStatus: user.kycStatus }, { kycStatus: approve ? "VERIFIED" : "REJECTED", reason }, ip(req));
  await notify(uid, "Account Update", approve ? "Your account has been approved. Welcome to SeloNaChipa!" : `Your verification needs action: ${reason}`,
    "VERIFICATION_STATUS_UPDATE", "selo://profile");
  return { ok: true };
});

export const admin_suspendUser = onCall(async (req) => {
  const a = requireAdmin(req, "compliance", "support");
  const uid = str(req.data?.uid, "user");
  const suspend = req.data?.suspend !== false;
  const user = (await db.doc(`users/${uid}`).get()).data();
  if (!user) throw new HttpsError("not-found", "User not found.");
  await db.doc(`users/${uid}`).update({ accountStatus: suspend ? "SUSPENDED" : "ACTIVE" });
  if (suspend) { await getAuth().revokeRefreshTokens(uid); await db.doc(`riderPresence/${uid}`).set({ isOnline: false }, { merge: true }); }
  await audit(a, suspend ? "user.suspend" : "user.reactivate", `users/${uid}`, { accountStatus: user.accountStatus }, { accountStatus: suspend ? "SUSPENDED" : "ACTIVE" }, ip(req));
  return { ok: true };
});

/** Manual identity check by support → single-use reset token (legacy accounts without questions). */
export const admin_issueResetToken = onCall(async (req) => {
  const a = requireAdmin(req, "support");
  const uid = str(req.data?.uid, "user");
  const raw = randomBytes(24).toString("hex");
  await db.doc(`users/${uid}/private/security`).set({ resetTokenHash: createHash("sha256").update(raw).digest("hex"),
    resetTokenExpires: Timestamp.fromMillis(Date.now() + 30 * 60_000) }, { merge: true });
  await audit(a, "pin.manual_reset_token", `users/${uid}`, null, { expiresInMin: 30, note: str(req.data?.note, "verification note", 300) }, ip(req));
  await securityEvent("MANUAL_RESET_TOKEN", { uid, by: a.email });
  return { resetToken: `${uid}.${raw}` };
});

export const admin_retryPayout = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const a = requireAdmin(req, "finance");
  const ref = str(req.data?.ref, "payout");
  if (req.data?.markPaid === true) {
    const lipilaRef = str(req.data?.lipilaRef, "Lipila reference");
    await db.doc(`payouts/${ref}`).update({ status: "DISBURSED", manualLipilaRef: lipilaRef, completedAt: FieldValue.serverTimestamp() });
    await audit(a, "payout.mark_paid", `payouts/${ref}`, null, { lipilaRef }, ip(req));
  } else {
    await retryPayout(ref);
    await audit(a, "payout.retry", `payouts/${ref}`, null, null, ip(req));
  }
  return { ok: true };
});

export const admin_resolveDispute = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const a = requireAdmin(req, "support", "finance");
  const id = str(req.data?.disputeId, "dispute");
  const outcome = req.data?.outcome as "refund" | "release" | "split";
  const dRef = db.doc(`disputes/${id}`);
  const d = (await dRef.get()).data();
  if (!d) throw new HttpsError("not-found", "Dispute not found.");
  if (outcome === "refund") await refund(d.orderId, "Dispute resolved in your favour");
  else if (outcome === "release") await completeDelivery(d.orderId, `admin:${a.email}`);
  else if (outcome === "split") {
    const o = (await db.doc(`orders/${d.orderId}`).get()).data()!;
    const buyerShare = Math.round(Number(req.data?.buyerShareNgwee) || 0);
    if (buyerShare <= 0 || buyerShare > o.totalNgwee) throw new HttpsError("invalid-argument", "Enter the buyer's share.");
    await disburse(`SELO-REF-${d.orderId}-P`, "refund", o.buyerUid, buyerShare, o.walletMsisdn, d.orderId);
  } else throw new HttpsError("invalid-argument", "Choose an outcome.");
  await dRef.update({ status: "RESOLVED", resolution: outcome, notes: req.data?.notes ?? null, resolvedBy: a.email, resolvedAt: FieldValue.serverTimestamp() });
  await audit(a, `dispute.${outcome}`, `disputes/${id}`, { status: d.status }, { status: "RESOLVED", outcome }, ip(req));
  return { ok: true };
});

export const admin_decideClaim = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const a = requireAdmin(req, "finance");
  const id = str(req.data?.claimId, "claim");
  const approve = req.data?.approve === true;
  const cRef = db.doc(`socialFundClaims/${id}`);
  const c = (await cRef.get()).data();
  if (!c || c.status !== "PENDING") throw new HttpsError("failed-precondition", "Claim already decided.");
  const amount = approve ? Math.round(Number(req.data?.amountNgwee ?? c.amountNgwee)) : 0;
  if (approve) {
    const rider = (await db.doc(`users/${c.riderUid}`).get()).data()!;
    await db.doc(`riderWallets/${c.riderUid}`).update({ socialFundNgwee: FieldValue.increment(-Math.min(amount, c.amountNgwee)) });
    await disburse(`SELO-SFC-${id}`, "social_fund_claim", c.riderUid, amount, rider.payoutWallet);
  }
  await cRef.update({ status: approve ? "APPROVED" : "REJECTED", approvedNgwee: amount, decidedBy: a.email, decidedAt: FieldValue.serverTimestamp(), reason: req.data?.reason ?? null });
  await audit(a, approve ? "claim.approve" : "claim.reject", `socialFundClaims/${id}`, null, { amount }, ip(req));
  await notify(c.riderUid, "Social Fund claim", approve ? `Approved: K${(amount / 100).toFixed(2)} is on its way.` : "Your claim was not approved. Tap for details.", "CLAIM_DECIDED");
  return { ok: true };
});

export const admin_moderateListing = onCall(async (req) => {
  const a = requireAdmin(req, "moderator");
  const id = str(req.data?.listingId, "listing");
  const status = req.data?.status as string;
  if (!["live", "paused", "removed"].includes(status)) throw new HttpsError("invalid-argument", "Invalid status.");
  const before = (await db.doc(`listings/${id}`).get()).data();
  await db.doc(`listings/${id}`).update({ status, moderatedBy: a.email, moderatedAt: FieldValue.serverTimestamp() });
  await audit(a, "listing.moderate", `listings/${id}`, { status: before?.status }, { status, reason: req.data?.reason ?? null }, ip(req));
  return { ok: true };
});

export const admin_sendNotification = onCall(async (req) => {
  const a = requireAdmin(req, "operations", "moderator");
  const title = str(req.data?.title, "title", 80), body = str(req.data?.body, "message", 300);
  const audience = req.data?.audience as { role?: string; uid?: string; city?: string };
  let q: FirebaseFirestore.Query = db.collection("users");
  if (audience?.uid) { await notify(audience.uid, title, body, "BROADCAST"); }
  else {
    if (audience?.role) q = q.where("role", "==", audience.role);
    if (audience?.city) q = q.where("district", "==", audience.city);
    const users = await q.select().limit(5000).get();
    for (const u of users.docs) await notify(u.id, title, body, "BROADCAST");
  }
  await audit(a, "notification.send", "users", null, { title, audience }, ip(req));
  return { ok: true };
});

/** Super Admin only: grant or remove an admin role (custom claim). */
export const admin_setRole = onCall(async (req) => {
  const a = requireAdmin(req);
  if (a.role !== "super_admin") throw new HttpsError("permission-denied", "Only a Super Admin can change admin roles.");
  const email = str(req.data?.email, "email");
  const role = (req.data?.role ?? null) as AdminRole | null;
  const user = await getAuth().getUserByEmail(email).catch(() => getAuth().createUser({ email }));
  await getAuth().setCustomUserClaims(user.uid, role ? { admin: role } : {});
  await db.doc(`admins/${user.uid}`).set({ email, role, updatedBy: a.email, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  await audit(a, "admin.set_role", `admins/${user.uid}`, null, { email, role }, ip(req));
  return { ok: true };
});

/** Pushes a data-only message so background apps wake and re-read platformConfig. */
export async function broadcastConfigChange(version: number) {
  await getMessaging().send({ topic: "config-updates", data: { type: "CONFIG_UPDATED", version: String(version) } }).catch(() => undefined);
}
