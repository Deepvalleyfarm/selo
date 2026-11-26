/**
 * 🔒 Delivery confirmation by 5-digit code + escrow release (Parcels v2 §9). Shared by parcels and marketplace orders.
 *
 *  Rider arrives → requestDeliveryCode (assigned rider, IN_TRANSIT, inside the geofence, limits) → a random code is
 *  generated, only its HMAC is stored, and the plaintext is sent to the RECEIVER (FCM; SMS if no app or no ack in 30 s)
 *  → the receiver reads it out → verifyDeliveryCode → in ONE transaction: DELIVERED + escrow released (exactly once).
 *
 * The plaintext code exists only in memory inside requestDeliveryCode. It is never written to Firestore, logs,
 * analytics or error reports, and it is never returned to the rider.
 */
import { FieldValue, Timestamp, type DocumentData, type Transaction } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions/v2";
import { DELIVERY_CODE_SECRET, LIPILA_API_KEY, SMS_API_KEY, db, loadConfig, type PlatformConfig } from "./config";
import { heroBonus, normaliseMsisdn, riderTier, split } from "./domain";
import { coll, releaseGuard, statusField, writeLedger, type OrderKind } from "./ledger";
import {
  applyAttempt, canIssueCode, codeMatches, codeMessage, generateDeliveryCode, hashCode, haversineKm, withinGeofence, type CodeState, type LL,
} from "./parcel-rules";
import { sendSms, sendTemplate } from "./sms";
import { notify, requireAdmin, requireUser, securityEvent, str } from "./util";

/* ------------------------------------------------------------------ shared plumbing */

const now = () => Date.now();
const codeRef = (kind: OrderKind, id: string) => db.doc(`${coll(kind)}/${id}/deliveryCode/current`);
const docRef = (kind: OrderKind, id: string) => db.doc(`${coll(kind)}/${id}`);
const kindOf = (v: unknown): OrderKind => (v === "parcel" ? "parcel" : "order");
const isLL = (v: any): v is LL => v && Number.isFinite(v.lat) && Number.isFinite(v.lng);
export function codeSecret(): string {
  if (process.env.DELIVERY_CODE_SECRET) return process.env.DELIVERY_CODE_SECRET;
  const s = DELIVERY_CODE_SECRET.value();
  if (!s || s.length < 16) throw new HttpsError("failed-precondition", "Delivery codes are not configured.");
  return s;
}

/** Who receives the code, who paid, where the drop-off is — the same view for both kinds. */
export function view(kind: OrderKind, d: DocumentData) {
  const status = d[statusField(kind)] as string;
  if (kind === "parcel") {
    const r = d.codeRecipient ?? d.receiver ?? {};
    return { status, riderUid: d.riderUid as string | null, payerUid: d.senderUid as string, senderUid: d.senderUid as string,
      receiverUid: (r.uid ?? null) as string | null, receiverMsisdn: r.msisdn as string, receiverName: r.name as string,
      dest: isLL(d.dropoff) ? { lat: d.dropoff.lat, lng: d.dropoff.lng } : null, ref: d.id ?? "", label: "parcel" };
  }
  const r = d.codeRecipient ?? { uid: d.buyerUid, msisdn: d.buyerMsisdn, name: d.buyerName };
  return { status, riderUid: d.riderUid as string | null, payerUid: d.buyerUid as string, senderUid: d.sellerUid as string,
    receiverUid: (r.uid ?? null) as string | null, receiverMsisdn: r.msisdn as string, receiverName: (r.name ?? "") as string,
    dest: isLL(d.dropoffGeo) ? d.dropoffGeo as LL : null, ref: "", label: "order" };
}

function timeline(tx: Transaction, kind: OrderKind, id: string, event: string, extra: Record<string, unknown> = {}) {
  tx.set(db.collection(`${coll(kind)}/${id}/timeline`).doc(), { event, ...extra, at: FieldValue.serverTimestamp() });
}

/** Rider position used for the geofence: the server-recorded ping (≤ 2 min old) wins over the client's claim. */
export async function riderPosition(riderUid: string, client?: LL | null): Promise<{ at: LL | null; source: string }> {
  const p = (await db.doc(`riderLocations/${riderUid}`).get()).data();
  const age = p?.at instanceof Timestamp ? now() - p.at.toMillis() : Infinity;
  if (p && isLL(p) && age <= 120_000) return { at: { lat: p.lat, lng: p.lng }, source: "server_ping" };
  if (client && isLL(client)) return { at: client, source: "client_fix" };
  return { at: null, source: "none" };
}

async function fcmTokens(uid: string | null): Promise<string[]> {
  if (!uid) return [];
  return ((await db.doc(`users/${uid}/private/devices`).get()).data()?.fcmTokens as string[] | undefined) ?? [];
}

/* ------------------------------------------------------------------ dependencies (injectable for tests) */

export interface Deps {
  secret: () => string;
  cfg: () => Promise<PlatformConfig>;
  /** High-priority push carrying the code. Returns true if FCM accepted at least one message. */
  pushCode: (tokens: string[], code: string, kind: OrderKind, id: string, expiresAt: number) => Promise<boolean>;
  sms: (to: string, text: string) => Promise<{ ok: boolean }>;
  /** Resolves true once the receiver's app acknowledged the push, false after timeoutMs. */
  waitForAck: (kind: OrderKind, id: string, issuedAt: number, timeoutMs: number) => Promise<boolean>;
}

export const liveDeps: Deps = {
  secret: codeSecret,
  cfg: loadConfig,
  async pushCode(tokens, code, kind, id, expiresAt) {
    if (!tokens.length) return false;
    const r = await getMessaging().sendEachForMulticast({ tokens,
      notification: { title: "Your delivery code", body: codeMessage(code) },
      data: { type: "DELIVERY_CODE", kind, orderId: id, code, expiresAt: String(expiresAt) },
      android: { priority: "high", ttl: 15 * 60_000, notification: { channelId: "delivery_code", tag: `code_${id}` } } }).catch(() => null);
    return (r?.successCount ?? 0) > 0;
  },
  sms: (to: string, text: string) => sendSms(to, text, { eventType: "DELIVERY_CODE", sensitive: true }),
  waitForAck(kind, id, issuedAt, timeoutMs) {
    return new Promise((resolve) => {
      let done = false;
      const finish = (v: boolean) => { if (!done) { done = true; unsub(); clearTimeout(t); resolve(v); } };
      const unsub = codeRef(kind, id).onSnapshot((s) => { const a = s.get("ackedAt") as number | undefined; if (a && a >= issuedAt) finish(true); }, () => finish(false));
      const t = setTimeout(() => finish(false), timeoutMs);
    });
  },
};

/* ================================================================== 1. request a code */

export interface RequestInput { kind: OrderKind; id: string; riderUid: string; position?: LL | null; forceNew?: boolean; smsOnly?: boolean; device?: string | null }
export interface RequestResult { state: "sent" | "active"; channel: string; expiresAt: number; attemptsLeft: number; codesLeft: number }

export async function requestDeliveryCodeCore(inp: RequestInput, deps: Deps = liveDeps): Promise<RequestResult> {
  const cfg = await deps.cfg();
  const ref = docRef(inp.kind, inp.id);
  const snap = await ref.get();
  const d = snap.data();
  if (!d) throw new HttpsError("not-found", "Order not found.");
  const v = view(inp.kind, d);
  if (v.riderUid !== inp.riderUid) throw new HttpsError("permission-denied", "This delivery isn't assigned to you.");
  if (v.status !== "IN_TRANSIT" && v.status !== "AWAITING_DELIVERY_CODE") throw new HttpsError("failed-precondition", "Collect the parcel before confirming delivery.");
  // 🔒 geofence: stops riders generating codes remotely and colluding with receivers
  const pos = await riderPosition(inp.riderUid, inp.position);
  const dest = v.dest;
  if (dest) {
    if (!pos.at || !withinGeofence(pos.at, dest, cfg.deliveryGeofenceM)) {
      await db.collection("deliveryCodeAttempts").add({ kind: inp.kind, orderId: inp.id, riderUid: inp.riderUid, action: "request", result: "outside_geofence",
        lat: pos.at?.lat ?? null, lng: pos.at?.lng ?? null, positionSource: pos.source, distanceM: pos.at ? Math.round(haversineKm(pos.at, dest) * 1000) : null,
        device: inp.device ?? null, at: FieldValue.serverTimestamp() });
      throw new HttpsError("failed-precondition", "You are not at the delivery location yet.");
    }
  }
  if (!v.receiverMsisdn) throw new HttpsError("failed-precondition", "This order has no receiver phone number. Contact support.");

  // reserve the code slot atomically
  const t = now();
  const secret = deps.secret();
  let code: string | null = null;
  const reserved = await db.runTransaction(async (tx) => {
    const cur = (await tx.get(codeRef(inp.kind, inp.id))).data() as (CodeState & { extraCodes?: number }) | undefined;
    const limitCfg = { ...cfg, deliveryCodeMaxPerOrder: cfg.deliveryCodeMaxPerOrder + (cur?.extraCodes ?? 0) };
    if (inp.smsOnly && (cur?.smsResends ?? 0) >= 1) throw new HttpsError("resource-exhausted", "The code can only be resent by SMS once.");
    const decision = canIssueCode(cur ?? null, t, limitCfg, inp.forceNew || inp.smsOnly);
    if (!decision.ok) throw new HttpsError("resource-exhausted", decision.reason);
    if (decision.reuse && cur) return { reuse: true as const, cur };
    code = generateDeliveryCode();
    const next: CodeState & Record<string, unknown> = {
      hash: hashCode(secret, inp.id, code), state: "sent", issuedAt: t, expiresAt: t + cfg.deliveryCodeExpiryMin * 60_000, attempts: 0,
      codesIssued: (cur?.codesIssued ?? 0) + (inp.smsOnly ? 0 : 1), channel: inp.smsOnly ? "sms" : "push", smsResends: (cur?.smsResends ?? 0) + (inp.smsOnly ? 1 : 0),
      lockedAt: null, ackedAt: null, receiverMsisdn: v.receiverMsisdn, extraCodes: cur?.extraCodes ?? 0,
    };
    tx.set(codeRef(inp.kind, inp.id), next);
    const field = statusField(inp.kind);
    tx.update(ref, { [field]: "AWAITING_DELIVERY_CODE", ...(v.status === "IN_TRANSIT" ? { [`timeline.AWAITING_DELIVERY_CODE`]: FieldValue.serverTimestamp() } : {}),
      arrivedAt: d.arrivedAt ?? FieldValue.serverTimestamp(), arrivalGeo: pos.at ?? null,
      codeSummary: { state: "sent", channel: "pending", attempts: 0, codesIssued: next.codesIssued, expiresAt: next.expiresAt, smsResends: next.smsResends },
      updatedAt: FieldValue.serverTimestamp() });
    timeline(tx, inp.kind, inp.id, cur ? (inp.smsOnly ? "DELIVERY_CODE_RESENT_SMS" : "DELIVERY_CODE_REISSUED") : "DELIVERY_CODE_SENT", { by: "rider", positionSource: pos.source });
    return { reuse: false as const, next };
  });

  if (reserved.reuse) {
    const c = reserved.cur;
    return { state: "active", channel: c.channel, expiresAt: c.expiresAt, attemptsLeft: cfg.deliveryCodeMaxAttempts - c.attempts,
      codesLeft: cfg.deliveryCodeMaxPerOrder - c.codesIssued };
  }
  // deliver the plaintext (memory only) — push, then SMS if there's no app or no ack in time
  const next = reserved.next;
  let channel = "sms";
  try {
    if (!inp.smsOnly) {
      const pushed = await deps.pushCode(await fcmTokens(v.receiverUid), code!, inp.kind, inp.id, next.expiresAt);
      const acked = pushed && await deps.waitForAck(inp.kind, inp.id, t, cfg.pushAckTimeoutSec * 1000);
      channel = acked ? "push" : pushed ? "push+sms" : "sms";
    }
    if (channel !== "push") {
      const r = await deps.sms(v.receiverMsisdn, codeMessage(code!));
      if (!r.ok && channel === "sms") channel = "failed";
    }
  } finally { code = null; }
  await codeRef(inp.kind, inp.id).update({ channel });
  await ref.update({ "codeSummary.channel": channel });
  await db.collection(`${coll(inp.kind)}/${inp.id}/timeline`).add({ event: "DELIVERY_CODE_CHANNEL", channel, at: FieldValue.serverTimestamp() });
  if (v.receiverUid) await notifyNoCode(v.receiverUid, "Delivery code sent", "Your rider has arrived. Open the app to see your delivery code.", "DELIVERY_CODE_READY", inp.kind, inp.id);
  if (v.senderUid && v.senderUid !== v.receiverUid) await notify(v.senderUid, "Rider has arrived", "The receiver was sent a delivery code.", "RIDER_ARRIVED", deepLink(inp.kind, inp.id));
  if (channel === "failed") throw new HttpsError("unavailable", "We couldn't reach the receiver. Try \"Resend by SMS\" or tap Receiver Unavailable.");
  return { state: "sent", channel, expiresAt: next.expiresAt, attemptsLeft: cfg.deliveryCodeMaxAttempts, codesLeft: cfg.deliveryCodeMaxPerOrder - next.codesIssued };
}

const deepLink = (kind: OrderKind, id: string) => (kind === "parcel" ? `selo://parcels/${id}` : `selo://orders/${id}`);
/** In-app notification that never contains the code itself. */
async function notifyNoCode(uid: string, title: string, body: string, type: string, kind: OrderKind, id: string) {
  await notify(uid, title, body, type, `${deepLink(kind, id)}/code`);
}

/* ================================================================== 2. verify + release */

export interface VerifyInput { kind: OrderKind; id: string; riderUid: string; code: string; position?: LL | null; device?: string | null; ip?: string | null }
export type VerifyResult =
  | { result: "verified"; riderEarningsNgwee: number; alreadyReleased?: boolean }
  | { result: "wrong"; attemptsLeft: number } | { result: "locked" } | { result: "expired" } | { result: "no_code" };

export async function verifyDeliveryCodeCore(inp: VerifyInput, deps: Deps = liveDeps): Promise<VerifyResult> {
  const cfg = await deps.cfg();
  const secret = deps.secret();
  const code = String(inp.code ?? "").replace(/\D/g, "");
  const pos = await riderPosition(inp.riderUid, inp.position);
  const t = now();
  const out = await db.runTransaction(async (tx) => {
    const ref = docRef(inp.kind, inp.id);
    // all reads first (Firestore transactions), including the wallet the release may credit
    const wRef = db.doc(`riderWallets/${inp.riderUid}`);
    const [snap, cSnap, guard, wSnap] = await Promise.all([tx.get(ref), tx.get(codeRef(inp.kind, inp.id)), tx.get(releaseGuard(inp.kind, inp.id)), tx.get(wRef)]);
    const d = snap.data();
    if (!d) throw new HttpsError("not-found", "Order not found.");
    const v = view(inp.kind, d);
    if (v.riderUid !== inp.riderUid) throw new HttpsError("permission-denied", "This delivery isn't assigned to you.");
    if (guard.exists) return { kind: "done" as const, earnings: guard.get("riderEarningsNgwee") as number, post: null };
    if (v.status !== "AWAITING_DELIVERY_CODE") throw new HttpsError("failed-precondition", "Tap Confirm Delivery at the drop-off first.");
    const cur = (cSnap.data() as CodeState | undefined) ?? null;
    const matches = !!cur && codeMatches(secret, inp.id, code, cur.hash);
    const { outcome, next } = applyAttempt(cur, matches, t, cfg);
    if (next) tx.set(codeRef(inp.kind, inp.id), next, { merge: true });
    tx.set(db.collection("deliveryCodeAttempts").doc(), { kind: inp.kind, orderId: inp.id, riderUid: inp.riderUid, action: "verify", result: outcome.result,
      lat: pos.at?.lat ?? null, lng: pos.at?.lng ?? null, positionSource: pos.source, device: inp.device ?? null, ip: inp.ip ?? null, at: FieldValue.serverTimestamp() });
    if (next) tx.update(ref, { "codeSummary.state": next.state, "codeSummary.attempts": next.attempts });
    if (outcome.result !== "verified") {
      if (outcome.result === "locked" && cur?.state === "sent") {
        tx.set(db.collection("codeLocks").doc(), { kind: inp.kind, orderId: inp.id, riderUid: inp.riderUid, at: Timestamp.fromMillis(t) });
        timeline(tx, inp.kind, inp.id, "DELIVERY_CODE_LOCKED", { attempts: next?.attempts ?? null });
      }
      return { kind: "fail" as const, outcome, v, justLocked: outcome.result === "locked" && cur?.state === "sent" };
    }
    // verified → release in the same transaction
    const w = wSnap.data() ?? {};
    const post = releaseInTx(tx, inp.kind, inp.id, d, w, cfg, { by: `rider:${inp.riderUid}`, override: false,
      verification: { verifiedAt: Timestamp.fromMillis(t), riderGeo: pos.at ?? null, positionSource: pos.source, channel: cur?.channel ?? null, attempts: next?.attempts ?? 1 } });
    return { kind: "released" as const, earnings: post.riderEarningsNgwee, post };
  });
  if (out.kind === "done") return { result: "verified", riderEarningsNgwee: out.earnings, alreadyReleased: true };
  if (out.kind === "fail") {
    if (out.justLocked) await afterLock(inp, out.v, cfg);
    return out.outcome as VerifyResult;
  }
  await afterRelease(inp.kind, inp.id, out.post!);
  return { result: "verified", riderEarningsNgwee: out.earnings };
}

interface ReleasePost {
  riderUid: string; riderEarningsNgwee: number; payerUid: string; senderUid: string; receiverUid: string | null; receiverMsisdn: string;
  senderMsisdn: string | null; seller?: { uid: string; amountNgwee: number; wallet: string } | null; buyerUid?: string; override: boolean;
}

/**
 * Releases escrow inside the caller's transaction. All reads (order doc, wallet, guard) must already be done.
 * Amounts come from the fee snapshot stored at payment time, never from current config or client input.
 */
export function releaseInTx(tx: Transaction, kind: OrderKind, id: string, d: DocumentData, w: DocumentData, cfg: PlatformConfig,
  o: { by: string; override: boolean; reason?: string; verification?: Record<string, unknown> }): ReleasePost {
  const v = view(kind, d);
  const riderUid = v.riderUid!;
  const L = (leg: Parameters<typeof writeLedger>[1]["leg"], party: Parameters<typeof writeLedger>[1]["party"], amount: number, extra: Partial<Parameters<typeof writeLedger>[1]> = {}) =>
    amount > 0 && writeLedger(tx, { kind, orderId: id, type: "RELEASED", leg, party, amountNgwee: amount, override: o.override, by: o.by, ...extra });

  let riderEarnings: number, fund: number, riderPending: number, riderPlatformFee: number;
  let seller: ReleasePost["seller"] = null;
  const revenue: Record<string, number> = {};
  if (kind === "parcel") {
    const f = d.fees;
    riderEarnings = f.riderEarningsNgwee; fund = f.socialFundNgwee; riderPending = f.riderEarningsNgwee; riderPlatformFee = f.platformFeeNgwee;
    L("platform_fee", "platform", f.platformFeeNgwee); L("sending_fee", "platform", f.sendingNgwee - (d.creditAppliedNgwee ?? 0)); L("insurance", "platform", f.insuranceNgwee);
    revenue.platform_fee = f.platformFeeNgwee; revenue.sending_fee = f.sendingNgwee - (d.creditAppliedNgwee ?? 0); revenue.insurance = f.insuranceNgwee;
  } else {
    const s = split(d.goodsNgwee, d.deliveryNgwee, d.feeSnapshot);
    const bonus = heroBonus(s.riderNetNgwee, w.tier, d.feeSnapshot?.heroBonusPct ?? 0);
    riderEarnings = s.riderNetNgwee + bonus; fund = s.socialFundNgwee; riderPending = s.riderNetNgwee; riderPlatformFee = s.riderPlatformFeeNgwee;
    L("platform_fee", "platform", s.riderPlatformFeeNgwee, { note: "rider leg" });
    L("hero_bonus", "rider", bonus, { partyUid: riderUid, note: "platform-funded" });
    revenue.rider_platform_fee = s.riderPlatformFeeNgwee; revenue.hero_bonus = -bonus;
    const sellerPolicy = d.feeSnapshot?.sellerReleaseOn ?? cfg.sellerReleaseOn;
    if (!d.isParcel && d.sellerPayoutStatus === "HELD") {
      L("platform_fee", "platform", s.platformFeeNgwee, { note: "seller flat fee" }); revenue.seller_platform_fee = s.platformFeeNgwee;
      if (sellerPolicy === "delivery_code" && (cfg.sellerDisputeHoldHours ?? 0) <= 0) {
        L("seller_proceeds", "seller", s.sellerNetNgwee, { partyUid: d.sellerUid });
        seller = { uid: d.sellerUid, amountNgwee: s.sellerNetNgwee, wallet: "" };
        tx.update(docRef(kind, id), { sellerPayoutStatus: "RELEASING" });
      } else tx.update(docRef(kind, id), { sellerPayoutStatus: "HELD_DISPUTE_WINDOW",
        sellerReleaseAt: Timestamp.fromMillis(now() + (cfg.sellerDisputeHoldHours ?? 0) * 3_600_000) });
    }
  }
  // Optional rider cover (Safety Centre): a small premium per completed delivery, taken from that delivery's earnings.
  const premium = w.insurance?.on ? Math.min(riderEarnings, Math.max(0, Math.round(Number(w.insurance.premiumNgwee ?? 0)))) : 0;
  if (premium > 0) { L("insurance", "platform", premium, { partyUid: riderUid, note: "rider cover premium" }); riderEarnings -= premium; revenue.rider_insurance = premium; }
  const before = w.availableNgwee ?? 0;
  L("rider_earnings", "rider", riderEarnings, { partyUid: riderUid, balanceBefore: before, balanceAfter: before + riderEarnings });
  L("social_fund", "rider_social_fund", fund, { partyUid: riderUid, balanceBefore: w.socialFundNgwee ?? 0, balanceAfter: (w.socialFundNgwee ?? 0) + fund });

  const completed = (w.completedDeliveries ?? 0) + 1;
  const week = [...(w.weekFund ?? [0, 0, 0, 0, 0, 0, 0])]; week[6] = (week[6] ?? 0) + fund;
  tx.set(db.doc(`riderWallets/${riderUid}`), {
    availableNgwee: before + riderEarnings, pendingNgwee: Math.max(0, (w.pendingNgwee ?? 0) - riderPending), socialFundNgwee: (w.socialFundNgwee ?? 0) + fund,
    lifetimeNgwee: (w.lifetimeNgwee ?? 0) + riderEarnings, completedDeliveries: completed, activeCount: Math.max(0, (w.activeCount ?? 1) - 1),
    todayNetNgwee: FieldValue.increment(riderEarnings), todayDeliveries: FieldValue.increment(1), weekFund: week,
    tier: riderTier(completed, w.tier === "Ambassador"), lastUpdated: FieldValue.serverTimestamp(),
  }, { merge: true });
  tx.set(db.doc(`riderPresence/${riderUid}`), { activeCount: FieldValue.increment(-1) }, { merge: true });
  const area = (a?: string) => String(a ?? "").split(",")[0].trim();
  const pickup = kind === "parcel" ? d.pickup?.address : d.pickup, dropoff = kind === "parcel" ? d.dropoff?.address : d.dropoff;
  tx.set(db.collection(`ledgers/${riderUid}/entries`).doc(), { type: "delivery_credit", kind, orderId: id,
    gross: kind === "parcel" ? d.fees.deliveryNgwee : d.deliveryNgwee, platformFee: riderPlatformFee,
    socialLevy: fund, net: riderPending, bonus: riderEarnings + premium - riderPending, insurancePremium: premium, km: d.distanceKm ?? 0, pickup: pickup ?? "", dropoff: dropoff ?? "",
    pickupArea: kind === "parcel" ? d.pickup?.neighbourhood ?? area(pickup) : area(pickup), dropArea: kind === "parcel" ? d.dropoff?.neighbourhood ?? area(dropoff) : area(dropoff),
    item: kind === "parcel" ? d.description : (d.items ?? []).map((i: { title: string; qty: number }) => `${i.qty} × ${i.title}`).join(", "),
    podUrl: d.proofOfDelivery ?? null, override: o.override, at: FieldValue.serverTimestamp() });
  tx.set(db.collection("socialFundTransactions").doc(), { riderUid, kind, orderId: id, amountNgwee: fund, type: "CONTRIBUTION", at: FieldValue.serverTimestamp() });
  const revTotal = Object.values(revenue).reduce((a, b) => a + b, 0);
  if (revTotal !== 0) tx.set(db.doc(`platformRevenue/rev_release_${kind}_${id}`), { source: kind === "parcel" ? "parcel_fees" : "order_rider_fees", kind, orderId: id,
    amountNgwee: revTotal, breakdown: revenue, override: o.override, at: FieldValue.serverTimestamp() });
  tx.create(releaseGuard(kind, id), { kind, orderId: id, riderUid, riderEarningsNgwee: riderEarnings, socialFundNgwee: fund, override: o.override, by: o.by,
    reason: o.reason ?? null, at: FieldValue.serverTimestamp() });
  tx.update(docRef(kind, id), { [statusField(kind)]: "DELIVERED", [`timeline.DELIVERED`]: FieldValue.serverTimestamp(), deliveredAt: FieldValue.serverTimestamp(),
    riderPayoutStatus: "RELEASED", escrowStatus: "RELEASED", deliveryOverride: o.override, verification: o.verification ?? { override: true, by: o.by, reason: o.reason ?? null },
    "codeSummary.state": o.override ? "overridden" : "verified", unavailable: null, completeAfter: Timestamp.fromMillis(now() + cfg.disputeWindowHours * 3_600_000),
    updatedAt: FieldValue.serverTimestamp() });
  timeline(tx, kind, id, o.override ? "DELIVERED_ADMIN_OVERRIDE" : "DELIVERED", { by: o.by, riderEarningsNgwee: riderEarnings });
  // order chats close an hour after delivery
  const closesAt = Timestamp.fromMillis(now() + cfg.chatCloseAfterMin * 60_000);
  for (const suffix of ["rb", "rs"]) tx.set(db.doc(`chats/${id}_${suffix}`), { closesAt }, { merge: true });
  return { riderUid, riderEarningsNgwee: riderEarnings, payerUid: v.payerUid, senderUid: v.senderUid, receiverUid: v.receiverUid, receiverMsisdn: v.receiverMsisdn,
    senderMsisdn: kind === "parcel" ? d.senderMsisdn ?? null : null, seller, buyerUid: kind === "order" ? d.buyerUid : undefined, override: o.override };
}

/** After commit: seller payout, notifications (§9.7), referral credit, live-location clean-up. Failures here never undo the release. */
async function afterRelease(kind: OrderKind, id: string, p: ReleasePost) {
  const earn = `K${(p.riderEarningsNgwee / 100).toFixed(2)}`;
  const jobs: Promise<unknown>[] = [
    notify(p.riderUid, "Delivery confirmed", `Your Earnings: ${earn}`, "DELIVERY_CONFIRMED"),
  ];
  if (kind === "parcel") {
    jobs.push(notify(p.senderUid, "Parcel delivered", `Your parcel ${id} was delivered and confirmed with the receiver's code.`, "DELIVERED", deepLink(kind, id)));
    jobs.push(sendTemplate("PARCEL_DELIVERED", p.senderMsisdn, [id], id));
    if (p.receiverUid) jobs.push(notify(p.receiverUid, "Delivered", `Parcel ${id} is marked delivered. Rate your rider in the app.`, "DELIVERED", deepLink(kind, id)));
    jobs.push(referralCredit(p.senderUid, id));
  } else {
    jobs.push(notify(p.payerUid, "Order delivered", "Delivery confirmed with your code. Tap to rate your rider.", "DELIVERED", deepLink(kind, id)));
    if (p.seller) jobs.push((async () => {
      const { disburse } = await import("./escrow");
      const u = (await db.doc(`users/${p.seller!.uid}`).get()).data();
      if (!u?.payoutWallet) { // never call the gateway without a wallet: park it for finance
        await db.doc(`payouts/SELO-SELR-${id}`).set({ ref: `SELO-SELR-${id}`, leg: "seller", uid: p.seller!.uid, amountNgwee: p.seller!.amountNgwee, orderId: id,
          status: "ESCALATED", reason: "Seller has no payout wallet", attempts: 0, createdAt: FieldValue.serverTimestamp() });
        return;
      }
      await disburse(`SELO-SELR-${id}`, "seller", p.seller!.uid, p.seller!.amountNgwee, u.payoutWallet, id);
      await db.doc(`sellerStats/${p.seller!.uid}`).set({ earningsNgwee: FieldValue.increment(p.seller!.amountNgwee), todayNgwee: FieldValue.increment(p.seller!.amountNgwee),
        monthNgwee: FieldValue.increment(p.seller!.amountNgwee), monthOrders: FieldValue.increment(1) }, { merge: true });
      await notify(p.seller!.uid, "Payment released", `Order ${id} was delivered. K${(p.seller!.amountNgwee / 100).toFixed(2)} is on its way to your wallet.`, "PAYOUT");
    })());
  }
  const results = await Promise.allSettled(jobs);
  results.filter((r) => r.status === "rejected").forEach((r) => logger.warn("post-release step failed", kind, id, String((r as PromiseRejectedResult).reason)));
  try {
    const { getDatabase } = await import("firebase-admin/database");
    if (p.buyerUid) await Promise.race([getDatabase().ref(`orderViewers/${p.riderUid}/${p.buyerUid}`).remove(), new Promise((r) => setTimeout(r, 3_000))]);
  } catch { /* RTDB optional */ }
}

async function referralCredit(senderUid: string, parcelId: string) {
  const u = (await db.doc(`users/${senderUid}`).get()).data();
  if (!u?.referredBy || u.firstParcelCredited) return;
  const cfg = await loadConfig();
  const amount = Math.round(cfg.referralCreditKwacha * 100);
  await db.runTransaction(async (tx) => {
    const fresh = (await tx.get(db.doc(`users/${senderUid}`))).data();
    if (fresh?.firstParcelCredited) return;
    tx.update(db.doc(`users/${senderUid}`), { firstParcelCredited: true });
    const ref = await tx.get(db.collection("users").where("referralCode", "==", u.referredBy).limit(1));
    const referrer = ref.docs[0]; if (!referrer) return;
    tx.update(referrer.ref, { parcelCreditNgwee: FieldValue.increment(amount) });
    tx.set(db.collection("referrals").doc(), { referrerUid: referrer.id, referredUid: senderUid, parcelId, creditNgwee: amount, at: FieldValue.serverTimestamp() });
  });
}

/** §9.3: notify everyone after a lockout; flag riders with 3 lockouts in 24 h. */
async function afterLock(inp: VerifyInput, v: ReturnType<typeof view>, cfg: PlatformConfig) {
  await notify(inp.riderUid, "Code locked", `Too many wrong codes. You can request a new code in ${cfg.deliveryCodeCooldownMin} minutes.`, "CODE_LOCKED");
  if (v.receiverUid) await notify(v.receiverUid, "Wrong delivery code entered", "Someone entered a wrong code. If you did not expect a delivery, contact support.", "CODE_LOCKED");
  else await sendSms(v.receiverMsisdn, "SeloNaChipa: someone entered a wrong delivery code for your order. If you did not expect a delivery, contact support on +260 978 070 734.");
  if (v.senderUid && v.senderUid !== v.receiverUid) await notify(v.senderUid, "Delivery code locked", "Too many wrong codes were entered at the drop-off. We're keeping your item safe.", "CODE_LOCKED");
  const since = Timestamp.fromMillis(now() - 24 * 3_600_000);
  const locks = await db.collection("codeLocks").where("riderUid", "==", inp.riderUid).where("at", ">=", since).count().get();
  if (locks.data().count >= cfg.riderLockFlagCount) {
    await db.doc(`riderFlags/${inp.riderUid}`).set({ riderUid: inp.riderUid, reason: "repeated_code_lockouts", count24h: locks.data().count, status: "OPEN",
      flaggedAt: FieldValue.serverTimestamp() }, { merge: true });
    await securityEvent("RIDER_CODE_LOCKOUTS", { uid: inp.riderUid, count24h: locks.data().count });
  }
}

/* ================================================================== 3. receiver unavailable, sender response, failure */

export async function receiverUnavailableCore(kind: OrderKind, id: string, riderUid: string, position: LL | null, cfg?: PlatformConfig) {
  cfg = cfg ?? await loadConfig();
  const ref = docRef(kind, id);
  const d = (await ref.get()).data();
  if (!d) throw new HttpsError("not-found", "Order not found.");
  const v = view(kind, d);
  if (v.riderUid !== riderUid) throw new HttpsError("permission-denied", "This delivery isn't assigned to you.");
  if (v.status !== "AWAITING_DELIVERY_CODE") throw new HttpsError("failed-precondition", "Tap Confirm Delivery at the drop-off first.");
  const arrived = (d.arrivedAt as Timestamp | undefined)?.toMillis() ?? now();
  const waited = (now() - arrived) / 60_000;
  if (waited < cfg.receiverWaitMin) throw new HttpsError("failed-precondition", `Please wait at the drop-off for ${Math.ceil(cfg.receiverWaitMin - waited)} more minute(s) before reporting the receiver unavailable.`);
  const pos = await riderPosition(riderUid, position);
  if (v.dest && (!pos.at || !withinGeofence(pos.at, v.dest, cfg.deliveryGeofenceM))) throw new HttpsError("failed-precondition", "You need to be at the delivery location.");
  if (d.unavailable?.reportedAt) return { deadline: (d.unavailable.deadline as Timestamp).toMillis() };
  const deadline = now() + cfg.senderResponseMin * 60_000;
  await ref.update({ unavailable: { reportedAt: FieldValue.serverTimestamp(), deadline: Timestamp.fromMillis(deadline), response: null, extended: false } });
  await db.collection(`${coll(kind)}/${id}/timeline`).add({ event: "RECEIVER_UNAVAILABLE", by: riderUid, at: FieldValue.serverTimestamp() });
  const txt = `SeloNaChipa: your rider is waiting at the drop-off for ${kind === "parcel" ? "parcel" : "order"} ${id}. Please come out or call the rider.`;
  if (v.receiverUid) await notify(v.receiverUid, "Your rider is waiting", "Please meet the rider now with your delivery code.", "RECEIVER_UNAVAILABLE", deepLink(kind, id));
  await sendSms(v.receiverMsisdn, txt);
  const payer = v.payerUid;
  await notify(payer, "Receiver not available", `The rider can't reach the receiver. Choose: give another contact, ask the rider to wait, or cancel. You have ${cfg.senderResponseMin} minutes.`,
    "RECEIVER_UNAVAILABLE_ACTION", `${deepLink(kind, id)}/unavailable`);
  if (kind === "parcel" && d.senderMsisdn) await sendSms(d.senderMsisdn, `SeloNaChipa: the receiver of parcel ${id} can't be reached. Open the app within ${cfg.senderResponseMin} min to give another contact, ask the rider to wait, or cancel.`);
  return { deadline };
}

export async function senderRespondCore(kind: OrderKind, id: string, uid: string, action: "alt_contact" | "wait" | "cancel", alt?: { name?: string; msisdn?: string }) {
  const cfg = await loadConfig();
  const ref = docRef(kind, id);
  const d = (await ref.get()).data();
  if (!d) throw new HttpsError("not-found", "Order not found.");
  const v = view(kind, d);
  if (v.payerUid !== uid) throw new HttpsError("permission-denied", "Only the sender can decide this.");
  if (!d.unavailable?.reportedAt || d.unavailable.response === "cancel") throw new HttpsError("failed-precondition", "There's nothing to decide right now.");
  if (action === "wait") {
    if (d.unavailable.extended) throw new HttpsError("failed-precondition", "The rider has already been asked to wait once.");
    const deadline = (d.unavailable.deadline as Timestamp).toMillis() + cfg.senderResponseMin * 60_000;
    await ref.update({ "unavailable.response": "wait", "unavailable.extended": true, "unavailable.deadline": Timestamp.fromMillis(deadline) });
    await notify(v.riderUid!, "Please wait", `The sender asked you to wait up to ${cfg.senderResponseMin} more minutes.`, "SENDER_WAIT");
  } else if (action === "alt_contact") {
    const m = normaliseMsisdn(alt?.msisdn);
    if (!m) throw new HttpsError("invalid-argument", "Enter a valid Airtel, MTN or Zamtel number.");
    const idx = (await db.doc(`msisdnIndex/${m}`).get()).data();
    await db.runTransaction(async (tx) => {
      tx.update(ref, { codeRecipient: { name: String(alt?.name ?? "").slice(0, 60) || "Alternative contact", msisdn: m, uid: idx?.uid ?? null },
        "unavailable.response": "alt_contact", "unavailable.deadline": Timestamp.fromMillis(now() + cfg.senderResponseMin * 60_000) });
      // the alternative contact gets one extra code beyond the normal limit
      tx.set(codeRef(kind, id), { extraCodes: FieldValue.increment(1), state: "superseded" }, { merge: true });
    });
    await notify(v.riderUid!, "New contact", "The sender gave another contact. Tap Confirm Delivery to send them a new code.", "ALT_CONTACT");
  } else {
    await failDelivery(kind, id, "sender_cancelled_at_dropoff");
    return { status: "DELIVERY_FAILED" };
  }
  await db.collection(`${coll(kind)}/${id}/timeline`).add({ event: `SENDER_RESPONSE_${action.toUpperCase()}`, by: uid, at: FieldValue.serverTimestamp() });
  return { status: "AWAITING_DELIVERY_CODE" };
}

/** DELIVERY_FAILED: no code, no release. Escrow stays HELD until an admin refunds or overrides (§9.5). */
export async function failDelivery(kind: OrderKind, id: string, reason: string) {
  const cfg = await loadConfig();
  const res = await db.runTransaction(async (tx) => {
    const ref = docRef(kind, id);
    const d = (await tx.get(ref)).data();
    if (!d) return null;
    const v = view(kind, d);
    if (!["AWAITING_DELIVERY_CODE", "IN_TRANSIT"].includes(v.status)) return null;
    const share = kind === "parcel" ? d.fees.riderEarningsNgwee : split(d.goodsNgwee, d.deliveryNgwee, d.feeSnapshot).riderNetNgwee;
    tx.update(ref, { [statusField(kind)]: "DELIVERY_FAILED", [`timeline.DELIVERY_FAILED`]: FieldValue.serverTimestamp(), failedAt: FieldValue.serverTimestamp(),
      failureReason: reason, returnStatus: cfg.failedDeliveryAction === "hold_at_hub" ? "HOLD_AT_HUB" : "RETURN_TO_SENDER", escrowStatus: "HELD_PENDING_ADMIN",
      "unavailable.response": d.unavailable?.response ?? "timeout" });
    tx.set(db.doc(`riderWallets/${v.riderUid}`), { pendingNgwee: FieldValue.increment(-share), activeCount: FieldValue.increment(-1) }, { merge: true });
    tx.set(codeRef(kind, id), { state: "expired" }, { merge: true });
    timeline(tx, kind, id, "DELIVERY_FAILED", { reason });
    return { v, senderMsisdn: kind === "parcel" ? d.senderMsisdn : null };
  });
  if (!res) return;
  const { v } = res;
  const what = kind === "parcel" ? "parcel" : "order";
  const ret = cfg.failedDeliveryAction === "hold_at_hub" ? "It will be held at the hub." : "The rider will return it to the sender.";
  await Promise.allSettled([
    notify(v.payerUid, "Delivery failed", `The ${what} ${id} couldn't be delivered. ${ret} Support will contact you about any refund.`, "DELIVERY_FAILED", deepLink(kind, id)),
    v.riderUid ? notify(v.riderUid, "Delivery failed", `${id}: ${ret}`, "DELIVERY_FAILED") : null,
    v.receiverUid ? notify(v.receiverUid, "Delivery failed", `We couldn't deliver ${what} ${id}.`, "DELIVERY_FAILED") : null,
    sendSms(v.receiverMsisdn, `SeloNaChipa: we couldn't deliver ${what} ${id} today. Contact the sender or support on +260 978 070 734.`),
    res.senderMsisdn ? sendSms(res.senderMsisdn, `SeloNaChipa: parcel ${id} couldn't be delivered. ${ret}`) : null,
  ]);
}

/* ================================================================== 4. admin override */

export async function overrideDeliveryCore(kind: OrderKind, id: string, admin: { uid: string; email: string; role: string }, reason: string, evidence: string[]) {
  if (reason.trim().length < 10) throw new HttpsError("invalid-argument", "Give a reason of at least 10 characters.");
  if (!evidence.length) throw new HttpsError("invalid-argument", "Attach evidence: call log, photo or chat reference.");
  const cfg = await loadConfig();
  const out = await db.runTransaction(async (tx) => {
    const ref = docRef(kind, id);
    const [snap, guard] = await Promise.all([tx.get(ref), tx.get(releaseGuard(kind, id))]);
    const d = snap.data();
    if (!d) throw new HttpsError("not-found", "Order not found.");
    if (guard.exists) throw new HttpsError("already-exists", "Escrow for this delivery was already released.");
    const v = view(kind, d);
    if (!v.riderUid) throw new HttpsError("failed-precondition", "No rider is assigned.");
    if (!["IN_TRANSIT", "AWAITING_DELIVERY_CODE", "DELIVERY_FAILED", "DISPUTED"].includes(v.status)) throw new HttpsError("failed-precondition", `Can't force-complete from ${v.status}.`);
    const w = (await tx.get(db.doc(`riderWallets/${v.riderUid}`))).data() ?? {};
    if (v.status === "DELIVERY_FAILED") { // pending was cleared on failure; put it back so the release nets correctly
      w.pendingNgwee = (w.pendingNgwee ?? 0) + (kind === "parcel" ? d.fees.riderEarningsNgwee : split(d.goodsNgwee, d.deliveryNgwee, d.feeSnapshot).riderNetNgwee);
      w.activeCount = (w.activeCount ?? 0) + 1;
    }
    const post = releaseInTx(tx, kind, id, d, w, cfg, { by: `admin:${admin.email}`, override: true, reason });
    tx.set(db.collection("admin_audit_log").doc(), { action: "delivery.override", kind, orderId: id, actorUid: admin.uid, actorEmail: admin.email, actorRole: admin.role,
      reason, evidence, fromStatus: v.status, riderUid: v.riderUid, riderEarningsNgwee: post.riderEarningsNgwee, at: FieldValue.serverTimestamp() });
    return post;
  });
  await afterRelease(kind, id, out);
  return { riderEarningsNgwee: out.riderEarningsNgwee };
}

/* ================================================================== callables */

const riderReq = (req: CallableRequest) => requireUser(req, "RIDER");
const pos = (v: any): LL | null => (isLL(v) ? { lat: Number(v.lat), lng: Number(v.lng) } : null);
const device = (req: CallableRequest) => (typeof req.data?.deviceId === "string" ? req.data.deviceId.slice(0, 80) : null);
const opts = { enforceAppCheck: process.env.SELO_APP_CHECK === "1", secrets: [DELIVERY_CODE_SECRET, SMS_API_KEY] };

export const requestDeliveryCode = onCall({ ...opts, timeoutSeconds: 75 }, async (req) => {
  const { uid } = riderReq(req);
  return requestDeliveryCodeCore({ kind: kindOf(req.data?.kind), id: str(req.data?.orderId, "order"), riderUid: uid, position: pos(req.data?.position),
    forceNew: req.data?.resend === true, smsOnly: req.data?.smsOnly === true, device: device(req) });
});

export const verifyDeliveryCode = onCall(opts, async (req) => {
  const { uid } = riderReq(req);
  const r = await verifyDeliveryCodeCore({ kind: kindOf(req.data?.kind), id: str(req.data?.orderId, "order"), riderUid: uid, code: String(req.data?.code ?? ""),
    position: pos(req.data?.position), device: device(req), ip: req.rawRequest?.ip ?? null });
  return r;
});

/** Receiver's app confirms the push arrived (stops the SMS fallback). No code is sent or returned. */
export const deliveryCode_ack = onCall(async (req) => {
  const { uid } = requireUser(req);
  const kind = kindOf(req.data?.kind), id = str(req.data?.orderId, "order");
  const d = (await docRef(kind, id).get()).data();
  if (!d || view(kind, d).receiverUid !== uid) throw new HttpsError("permission-denied", "Not your delivery.");
  await codeRef(kind, id).set({ ackedAt: now() }, { merge: true });
  return { ok: true };
});

export const receiverUnavailable = onCall(async (req) => {
  const { uid } = riderReq(req);
  return receiverUnavailableCore(kindOf(req.data?.kind), str(req.data?.orderId, "order"), uid, pos(req.data?.position));
});

export const senderRespondUnavailable = onCall(async (req) => {
  const { uid } = requireUser(req);
  const action = req.data?.action;
  if (!["alt_contact", "wait", "cancel"].includes(action)) throw new HttpsError("invalid-argument", "Choose an option.");
  return senderRespondCore(kindOf(req.data?.kind), str(req.data?.orderId, "order"), uid, action, { name: req.data?.name, msisdn: req.data?.msisdn });
});

/** Receiver taps Confirm Receipt → COMPLETED early (otherwise after the dispute window). */
export const confirmReceipt = onCall(async (req) => {
  const { uid } = requireUser(req);
  const kind = kindOf(req.data?.kind), id = str(req.data?.orderId, "order");
  const ref = docRef(kind, id);
  const d = (await ref.get()).data();
  if (!d) throw new HttpsError("not-found", "Order not found.");
  const v = view(kind, d);
  if (uid !== v.receiverUid && uid !== v.payerUid) throw new HttpsError("permission-denied", "Not your delivery.");
  if (v.status !== "DELIVERED") throw new HttpsError("failed-precondition", "This delivery isn't waiting for confirmation.");
  await complete(kind, id, "receiver_confirmed");
  return { ok: true };
});

async function complete(kind: OrderKind, id: string, by: string) {
  await db.runTransaction(async (tx) => {
    const ref = docRef(kind, id);
    const d = (await tx.get(ref)).data();
    if (!d || d[statusField(kind)] !== "DELIVERED") return;
    tx.update(ref, { [statusField(kind)]: "COMPLETED", [`timeline.COMPLETED`]: FieldValue.serverTimestamp(), completedAt: FieldValue.serverTimestamp(), completedBy: by });
    timeline(tx, kind, id, "COMPLETED", { by });
  });
}

export const admin_overrideDelivery = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const a = requireAdmin(req, "operations", "support");
  const evidence = (Array.isArray(req.data?.evidence) ? req.data.evidence : []).filter((x: unknown) => typeof x === "string" && x.length <= 500).slice(0, 10);
  return overrideDeliveryCore(kindOf(req.data?.kind), str(req.data?.orderId, "order"), a, String(req.data?.reason ?? ""), evidence);
});

/** Every minute: unanswered "receiver unavailable" → DELIVERY_FAILED. Every run: DELIVERED past the dispute window → COMPLETED. */
export const scheduled_deliveryConfirmation = onSchedule({ schedule: "every 1 minutes", secrets: [LIPILA_API_KEY, SMS_API_KEY] }, async () => {
  const nowTs = Timestamp.now();
  for (const kind of ["parcel", "order"] as OrderKind[]) {
    const f = statusField(kind);
    const waiting = await db.collection(coll(kind)).where(f, "==", "AWAITING_DELIVERY_CODE").where("unavailable.deadline", "<=", nowTs).limit(100).get();
    for (const d of waiting.docs) await failDelivery(kind, d.id, d.get("unavailable.response") === "wait" ? "receiver_unavailable_after_wait" : "receiver_unavailable_no_response");
    const done = await db.collection(coll(kind)).where(f, "==", "DELIVERED").where("completeAfter", "<=", nowTs).limit(200).get();
    for (const d of done.docs) await complete(kind, d.id, "dispute_window_closed");
  }
  // seller proceeds held for a dispute window (sellerDisputeHoldHours > 0)
  const held = await db.collection("orders").where("sellerPayoutStatus", "==", "HELD_DISPUTE_WINDOW").where("sellerReleaseAt", "<=", nowTs).limit(100).get();
  for (const o of held.docs) {
    if (["DISPUTED", "REFUNDED"].includes(o.get("state"))) continue;
    const s = split(o.get("goodsNgwee"), o.get("deliveryNgwee"), o.get("feeSnapshot"));
    await db.runTransaction(async (tx) => {
      const fresh = (await tx.get(o.ref)).data();
      if (fresh?.sellerPayoutStatus !== "HELD_DISPUTE_WINDOW") return;
      tx.update(o.ref, { sellerPayoutStatus: "RELEASING" });
      writeLedger(tx, { kind: "order", orderId: o.id, type: "RELEASED", leg: "seller_proceeds", party: "seller", partyUid: o.get("sellerUid"), amountNgwee: s.sellerNetNgwee, note: "after dispute hold" });
    });
    const { disburse } = await import("./escrow");
    const u = (await db.doc(`users/${o.get("sellerUid")}`).get()).data();
    await disburse(`SELO-SELR-${o.id}`, "seller", o.get("sellerUid"), s.sellerNetNgwee, u?.payoutWallet, o.id);
  }
});
