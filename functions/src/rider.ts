/** Rider post-login features (PRD 6.x): decline, ratings, profile and settings, Social Fund claims, incidents, auto-settle. */
import bcrypt from "bcryptjs";
import { FieldValue } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { DB_ID, LIPILA_API_KEY, db, loadConfig } from "./config";
import { SECURITY_QUESTION_BANK } from "./domain-defaults";
import { acceptanceRate, CLAIM_CATEGORIES, claimProblem, isHero, normaliseAnswer, normaliseMsisdn } from "./domain";
import { disburse } from "./escrow";
import { lipila } from "./lipila";
import { notify, requireUser, securityEvent, str } from "./util";

/** Declining doesn't lock the rider; it lowers the acceptance rate and hands the job to the next rider. */
export const rider_declineOrder = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const id = str(req.data?.orderId, "order");
  await db.doc(`orders/${id}`).update({ notifiedRiders: FieldValue.arrayRemove(uid), declinedBy: FieldValue.arrayUnion(uid) }).catch(() => undefined);
  const wRef = db.doc(`riderWallets/${uid}`);
  await db.runTransaction(async (tx) => {
    const w = (await tx.get(wRef)).data() ?? {};
    const offered = (w.jobsOffered ?? 0) + 1, accepted = w.jobsAccepted ?? 0;
    tx.set(wRef, { jobsOffered: offered, jobsDeclined: (w.jobsDeclined ?? 0) + 1, acceptanceRate: acceptanceRate(accepted, offered) }, { merge: true });
  });
  return { ok: true };
});

export const rider_rateBuyer = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const id = str(req.data?.orderId, "order");
  const stars = Math.round(Number(req.data?.stars));
  if (!(stars >= 1 && stars <= 5)) throw new HttpsError("invalid-argument", "Choose 1 to 5 stars.");
  const o = (await db.doc(`orders/${id}`).get()).data();
  if (!o || o.riderUid !== uid || o.state !== "DELIVERED") throw new HttpsError("failed-precondition", "You can rate the buyer after delivery.");
  await db.doc(`reviews/rider_${id}`).set({ orderId: id, fromUid: uid, toUid: o.buyerUid, role: "RIDER_TO_BUYER", stars, at: FieldValue.serverTimestamp() });
  return { ok: true };
});

/** Rider-editable profile fields. Name, number and KYC data change only through support. */
export const rider_updateProfile = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const d = req.data ?? {};
  const u: Record<string, unknown> = {};
  if (typeof d.emergencyName === "string") u["emergencyContact.name"] = d.emergencyName.trim().slice(0, 60);
  if (typeof d.emergencyPhone === "string") {
    const m = normaliseMsisdn(d.emergencyPhone) ?? (/^\+?\d{9,13}$/.test(d.emergencyPhone.replace(/\s/g, "")) ? d.emergencyPhone.replace(/\s/g, "") : null);
    if (!m) throw new HttpsError("invalid-argument", "Enter a valid emergency contact number.");
    u["emergencyContact.phone"] = m;
  }
  if (typeof d.vehicle === "string" && ["Motorbike", "Bicycle", "Car", "On foot"].includes(d.vehicle)) u.vehicle = d.vehicle;
  if (typeof d.plate === "string") u["vehicleDetails.plate"] = d.plate.toUpperCase().replace(/[^A-Z0-9 ]/g, "").slice(0, 12);
  if (d.weeklyTargetNgwee != null) {
    const t = Math.round(Number(d.weeklyTargetNgwee));
    if (!(t >= 10_000 && t <= 5_000_000)) throw new HttpsError("invalid-argument", "Set a weekly target between K100 and K50,000.");
    u["riderSettings.weeklyTargetNgwee"] = t;
  }
  if (typeof d.autoSettle === "boolean") u["riderSettings.autoSettle"] = d.autoSettle;
  if (typeof d.photoPath === "string" && d.photoPath.startsWith(`profiles/${uid}/`)) u.photoPath = d.photoPath;
  if (!Object.keys(u).length) throw new HttpsError("invalid-argument", "Nothing to update.");
  await db.doc(`users/${uid}`).update({ ...u, updatedAt: FieldValue.serverTimestamp() });
  return { ok: true };
});

/** Payout wallet: Airtel Money, MTN MoMo or Zamtel Kwacha, confirmed with a Lipila name lookup. */
export const wallet_setPayout = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "RIDER", "SELLER", "AGENT");
  const m = normaliseMsisdn(req.data?.msisdn);
  if (!m) throw new HttpsError("invalid-argument", "Enter an Airtel Money, MTN Money or Zamtel Money number.");
  const name = await lipila.lookup(m).catch(() => null);
  if (!name) throw new HttpsError("not-found", "We couldn't find a mobile money wallet on that number.");
  await db.doc(`users/${uid}`).update({ payoutWallet: m, payoutWalletHolder: name, payoutWalletChangedAt: FieldValue.serverTimestamp() });
  await securityEvent("PAYOUT_WALLET_CHANGED", { uid, msisdn: m });
  await notify(uid, "Payout wallet changed", `Payouts now go to ${name} on ${m.replace("+260", "0").slice(0, 4)}•••${m.slice(-3)}. If this wasn't you, contact support now.`, "SECURITY");
  return { holderName: name };
});

/** Replace the three recovery questions. Needs the current PIN. */
export const auth_updateSecurityQuestions = onCall(async (req) => {
  const { uid } = requireUser(req);
  const answers = (Array.isArray(req.data?.answers) ? req.data.answers : []) as { questionId: string; answer: string }[];
  if (answers.length !== 3 || new Set(answers.map((a) => a.questionId)).size !== 3 || answers.some((a) => !SECURITY_QUESTION_BANK[a.questionId] && !a.questionId.startsWith("q_")))
    throw new HttpsError("invalid-argument", "Choose three different questions.");
  if (answers.some((a) => normaliseAnswer(String(a.answer ?? "")).length < 2)) throw new HttpsError("invalid-argument", "Answer all three questions.");
  const secRef = db.doc(`users/${uid}/private/security`);
  const sec = (await secRef.get()).data();
  if (!sec || !(await bcrypt.compare(String(req.data?.pin ?? ""), sec.pinHash))) throw new HttpsError("permission-denied", "Current PIN is incorrect.");
  const hashed = await Promise.all(answers.map(async (a) => ({ questionId: a.questionId, answerHash: await bcrypt.hash(normaliseAnswer(a.answer), 12) })));
  await secRef.update({ securityQuestions: hashed, sqAttempts: 0, sqLockedUntil: null });
  await securityEvent("SECURITY_QUESTIONS_CHANGED", { uid });
  return { ok: true };
});

export const rider_fileClaim = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const d = req.data ?? {};
  const w = (await db.doc(`riderWallets/${uid}`).get()).data() ?? {};
  const photo = typeof d.photoPath === "string" && d.photoPath.startsWith(`claims/${uid}/`) ? d.photoPath : null;
  const amount = Math.round(Number(d.amountNgwee));
  const problem = claimProblem(String(d.category), amount, w.socialFundNgwee ?? 0, w.tier, !!photo);
  if (problem) throw new HttpsError("invalid-argument", problem);
  const u = (await db.doc(`users/${uid}`).get()).data() ?? {};
  const ref = db.collection("socialFundClaims").doc();
  await ref.set({ riderUid: uid, riderName: u.fullName ?? "", category: CLAIM_CATEGORIES[d.category as keyof typeof CLAIM_CATEGORIES].label,
    categoryKey: d.category, amountNgwee: amount, description: String(d.description ?? "").slice(0, 1000), photoPath: photo,
    status: "PENDING", tier: w.tier ?? "Starter", createdAt: FieldValue.serverTimestamp() });
  await notify(uid, "Claim submitted", "The Selonachipa team will review and respond within 48 hours.", "CLAIM_SUBMITTED");
  return { claimId: ref.id };
});

const INCIDENT_TYPES = ["accident", "buyer_dispute", "seller_dispute", "unsafe_order", "other"];
export const incident_report = onCall(async (req) => {
  const { uid, role } = requireUser(req);
  const d = req.data ?? {};
  const type = INCIDENT_TYPES.includes(d.type) ? d.type : "other";
  const description = str(d.description, "description", 2000);
  if (description.length < 10) throw new HttpsError("invalid-argument", "Describe what happened in a few words.");
  const ref = db.collection("incidents").doc();
  const id = `INC-${ref.id.slice(0, 6).toUpperCase()}`;
  const u = (await db.doc(`users/${uid}`).get()).data() ?? {};
  await ref.set({ uid, role, reference: id, type, description, orderRef: typeof d.orderRef === "string" ? d.orderRef.slice(0, 30) : "",
    photoPath: typeof d.photoPath === "string" && d.photoPath.startsWith(`incidents/${uid}/`) ? d.photoPath : null,
    emergencyContact: u.emergencyContact ?? null, status: "OPEN", createdAt: FieldValue.serverTimestamp() });
  await notify(uid, "Incident received", `We got your report ${id}. Operations will review it within 24 hours.`, "INCIDENT_ACK");
  return { incidentId: id };
});

/** Chat list preview and unread counts, kept by the server (clients can only add messages). */
export const onChatMessage = onDocumentCreated({ document: "chats/{chatId}/messages/{m}", database: DB_ID }, async (e) => {
  const m = e.data?.data(); if (!m) return;
  const chat = db.doc(`chats/${e.params.chatId}`);
  const c = (await chat.get()).data(); if (!c) return;
  const others = (c.participants as string[]).filter((p) => p !== m.senderUid);
  await chat.update({ lastMessage: String(m.text ?? "").slice(0, 140), lastAt: FieldValue.serverTimestamp(), [`unread.${m.senderUid}`]: 0,
    ...Object.fromEntries(others.map((p) => [`unread.${p}`, FieldValue.increment(1)])) });
  await Promise.all(others.map((p) => notify(p, c.orderId ? `Message · ${c.orderId}` : "New message", String(m.text ?? "").slice(0, 100), "CHAT", `selo://chats/${e.params.chatId}`)));
});

/** Daily rider jobs at 00:00 Lusaka: auto-settle earnings, Social Fund summary, acceptance-rate nudges. */
export const scheduled_riderDaily = onSchedule({ schedule: "0 0 * * *", timeZone: "Africa/Lusaka", secrets: [LIPILA_API_KEY] }, async () => {
  const cfg = await loadConfig();
  const wallets = await db.collection("riderWallets").get();
  const date = new Date().toISOString().slice(0, 10);
  for (const w of wallets.docs) {
    const x = w.data();
    const u = (await db.doc(`users/${w.id}`).get()).data();
    if (!u) continue;
    const available = x.availableNgwee ?? 0;
    if (u.riderSettings?.autoSettle !== false && u.payoutWallet && available >= cfg.minWithdrawal * 100) {
      const wid = `AUTO-${w.id.slice(0, 8)}-${date}`;
      await db.doc(`withdrawals/${wid}`).set({ uid: w.id, role: "RIDER", grossNgwee: available, feeNgwee: 0, netNgwee: available, msisdn: u.payoutWallet,
        walletDoc: `riderWallets/${w.id}`, field: "availableNgwee", status: "PROCESSING", auto: true, createdAt: FieldValue.serverTimestamp() });
      await disburse(`SELO-RIDW-${w.id.slice(0, 8)}-${date}`, "withdrawal", w.id, available, u.payoutWallet, undefined, { withdrawalOf: wid, grossNgwee: available });
    }
    const today = (x.weekFund ?? [])[6] ?? 0;
    if (today > 0) await notify(w.id, "Social Fund today", `+K${(today / 100).toFixed(2)} from today's deliveries. Balance K${((x.socialFundNgwee ?? 0) / 100).toFixed(2)}.`, "SOCIAL_FUND_DAILY");
    if ((x.jobsOffered ?? 0) >= 10 && (x.acceptanceRate ?? 100) < 80)
      await notify(w.id, "Acceptance rate below 80%", `You're at ${x.acceptanceRate}%. Riders above 80% get priority dispatch and keep Hero eligibility.`, "ACCEPTANCE_WARNING");
    if (isHero(x.tier) && !x.heroNotified) { await notify(w.id, "Hero tier unlocked", "300 deliveries! You now earn a Hero bonus on every delivery and can claim school fees.", "TIER_MILESTONE"); await w.ref.update({ heroNotified: true }); }
  }
});

