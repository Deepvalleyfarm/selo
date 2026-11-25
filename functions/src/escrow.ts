import { createHash, randomInt } from "node:crypto";
import { FieldValue, Timestamp, Transaction } from "firebase-admin/firestore";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import { getDatabase } from "firebase-admin/database";
import { LIPILA_API_KEY, LIPILA_WEBHOOK_SECRET, PlatformConfig, db, loadConfig } from "./config";
import { canTransition, collectionFailureReason, haversineKm, insuranceId, milestoneReached, nextRetryMinutes, normaliseMsisdn, operatorLabel, OrderState, quote, split } from "./domain";
import { onPromoCollection } from "./promotions";
import { lipila, verifyLipilaSignature } from "./lipila";
import { notify, requireUser, str } from "./util";

const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");
const ledger = (orderId: string) => db.collection(`escrowLedger/${orderId}/entries`);
const orderRef = (id: string) => db.doc(`orders/${id}`);
const shortId = (p: string) => `${p}-${randomInt(10_000, 99_999)}${randomInt(10, 99)}`;

function feeSnapshot(c: PlatformConfig) {
  const { flatPlatformFeeKwacha, buyerServiceFeePct, riderPlatformFeePct, socialFundPct, deliveryFeePerKm, deliveryBaseFee,
    insurancePremiumPct, insuranceMaxDeclaredValue, agentPlatformCutPct, socialFundMilestoneDeliveries, paymentProcessingPct } = c;
  return { flatPlatformFeeKwacha, buyerServiceFeePct, riderPlatformFeePct, socialFundPct, deliveryFeePerKm, deliveryBaseFee,
    insurancePremiumPct, insuranceMaxDeclaredValue, agentPlatformCutPct, socialFundMilestoneDeliveries, paymentProcessingPct };
}

function move(tx: Transaction, id: string, from: OrderState, to: OrderState, extra: Record<string, unknown> = {}) {
  if (!canTransition(from, to)) throw new HttpsError("failed-precondition", `Order can't move from ${from} to ${to}.`);
  tx.update(orderRef(id), { state: to, [`timeline.${to}`]: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(), ...extra });
}

/** Sends the mobile money prompt. Attempt 0 uses SELO-COL-{id}; retries with another number use SELO-COL-{id}-R{n}. */
async function createCollection(orderId: string, totalNgwee: number, msisdn: string, narration: string, attempt = 0) {
  const ref = `SELO-COL-${orderId}${attempt > 0 ? `-R${attempt}` : ""}`;
  const res = await lipila.collect(ref, totalNgwee, msisdn, narration).catch(() => ({ ok: false, status: 0 }));
  await orderRef(orderId).update({ lipilaCollectionRef: ref, lipilaRef: ref, collectionRequestStatus: res.ok ? "SENT" : `FAILED_${res.status}`,
    walletMsisdn: msisdn, [`attemptWallets.${ref}`]: msisdn, collectionAttempt: attempt, paymentStatus: res.ok ? "PENDING" : "FAILED",
    paymentFailure: res.ok ? null : `${operatorLabel(msisdn)} couldn't start the payment. Try again or pay with another number.` });
}

async function issueDeliveryCode(orderId: string, buyerUid: string) {
  const code = String(randomInt(100_000, 999_999));
  await db.doc(`deliveryCodes/${orderId}`).set({ buyerUid, code });
  return sha256(code);
}

/* ------------------------------------------------------------------ create */

export const order_create = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "BUYER");
  const items = (req.data?.items ?? []) as { listingId: string; qty: number }[];
  if (!items.length || items.length > 20) throw new HttpsError("invalid-argument", "Your cart is empty.");
  const wallet = normaliseMsisdn(req.data?.walletMsisdn);
  if (!wallet) throw new HttpsError("invalid-argument", "Choose a mobile money wallet.");
  const dropoff = str(req.data?.dropoff, "delivery address");
  const cfg = await loadConfig();
  const listings = await Promise.all(items.map((i) => db.doc(`listings/${i.listingId}`).get()));
  if (listings.some((l) => !l.exists || l.get("status") !== "live")) throw new HttpsError("failed-precondition", "An item is no longer available.");
  const sellerUid = listings[0].get("sellerUid") as string;
  if (listings.some((l) => l.get("sellerUid") !== sellerUid)) throw new HttpsError("invalid-argument", "One seller per order.");
  const seller = (await db.doc(`users/${sellerUid}`).get()).data() ?? {};
  const buyer = (await db.doc(`users/${uid}`).get()).data() ?? {};
  const lines = items.map((i, k) => ({ listingId: i.listingId, title: listings[k].get("title"), qty: Math.max(1, Math.floor(i.qty)),
    unitNgwee: listings[k].get("priceNgwee") as number }));
  const goods = lines.reduce((s, l) => s + l.qty * l.unitNgwee, 0);
  const distanceKm = seller.geo && buyer.geo ? Math.round(haversineKm(seller.geo, buyer.geo) * 1.3 * 10) / 10 : 6;
  const q = quote(goods, distanceKm, cfg);
  const id = shortId("SNC");
  await orderRef(id).set({
    buyerUid: uid, buyerName: buyer.fullName ?? "", sellerUid, sellerName: seller.storeName ?? seller.fullName ?? "",
    agentUid: listings[0].get("agentUid") ?? null, items: lines, goodsNgwee: q.goodsNgwee, deliveryNgwee: q.deliveryNgwee,
    serviceFeeNgwee: q.serviceFeeNgwee, processingFeeNgwee: q.processingFeeNgwee, totalNgwee: q.totalNgwee, distanceKm, paymentStatus: "PENDING", pickup: seller.landmark ?? seller.area ?? "",
    pickupGeo: seller.geo ?? null, dropoff, dropoffGeo: buyer.geo ?? null, zoneName: seller.area ?? "", walletMsisdn: wallet,
    state: "INITIATED", feeSnapshot: feeSnapshot(cfg), isParcel: false, riderUid: null, notifiedRiders: [],
    sellerPayoutStatus: "HELD", riderPayoutStatus: "HELD", timeline: { INITIATED: FieldValue.serverTimestamp() },
    deliveryCodeHash: await issueDeliveryCode(id, uid), createdAt: FieldValue.serverTimestamp(),
  });
  await createCollection(id, q.totalNgwee, wallet, `SeloNaChipa order ${id}`);
  return { orderId: id, totalNgwee: q.totalNgwee };
});

/** Default number had no funds (or was declined): pay the same order, same amount, from another number. */
export const order_retryPayment = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "BUYER", "SELLER");
  const id = String(req.data?.orderId ?? "");
  const wallet = normaliseMsisdn(req.data?.walletMsisdn);
  if (!wallet) throw new HttpsError("invalid-argument", "Enter an Airtel Money, MTN Money or Zamtel Money number.");
  const o = (await orderRef(id).get()).data();
  if (!o || o.buyerUid !== uid) throw new HttpsError("not-found", "Order not found.");
  if (o.state !== "INITIATED") throw new HttpsError("failed-precondition", "This order is already paid.");
  if (o.paymentStatus !== "FAILED") throw new HttpsError("failed-precondition", "We're still waiting for the last payment prompt.");
  const attempt = (o.collectionAttempt ?? 0) + 1;
  if (attempt > 5) throw new HttpsError("resource-exhausted", "Too many attempts. Please place the order again.");
  await createCollection(id, o.totalNgwee, wallet, `SeloNaChipa order ${id}`, attempt);
  return { ok: true };
});

export const parcel_create = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "BUYER", "SELLER");
  const d = req.data ?? {};
  const wallet = normaliseMsisdn(d.walletMsisdn);
  const recipient = normaliseMsisdn(d.recipientMsisdn);
  if (!wallet || !recipient) throw new HttpsError("invalid-argument", "Check the phone numbers.");
  const cfg = await loadConfig();
  const declared = d.insured ? Math.min(Number(d.declaredValueNgwee) || 0, cfg.insuranceMaxDeclaredValue * 100) : 0;
  const distanceKm = Math.max(0.5, Number(d.distanceKm) || 7);
  const q = quote(0, distanceKm, cfg, declared);
  const id = shortId("PCL");
  const ins = d.insured ? insuranceId() : null;
  const sender = (await db.doc(`users/${uid}`).get()).data() ?? {};
  await orderRef(id).set({
    buyerUid: uid, buyerName: sender.fullName ?? "", sellerUid: uid, sellerName: sender.fullName ?? "", isParcel: true,
    items: [{ listingId: "", title: str(d.contents, "contents"), qty: 1, unitNgwee: 0 }], size: d.size ?? "Medium",
    recipientName: str(d.recipientName, "recipient"), recipientMsisdn: recipient, goodsNgwee: 0, deliveryNgwee: q.deliveryNgwee,
    insurancePremiumNgwee: q.insurancePremiumNgwee, totalNgwee: q.totalNgwee, declaredValueNgwee: declared, insuranceId: ins,
    isInsured: !!ins, distanceKm, pickup: str(d.pickup, "pickup"), dropoff: str(d.dropoff, "drop-off"), zoneName: sender.area ?? "",
    walletMsisdn: wallet, state: "INITIATED", feeSnapshot: feeSnapshot(cfg), riderUid: null, notifiedRiders: [],
    sellerPayoutStatus: "DISBURSED", riderPayoutStatus: "HELD", timeline: { INITIATED: FieldValue.serverTimestamp() },
    deliveryCodeHash: await issueDeliveryCode(id, uid), createdAt: FieldValue.serverTimestamp(),
  });
  if (ins) await db.doc(`insurancePolicies/${ins}`).set({ orderId: id, declaredValueNgwee: declared, premiumNgwee: q.insurancePremiumNgwee, status: "ACTIVE", createdAt: FieldValue.serverTimestamp() });
  await createCollection(id, q.totalNgwee, wallet, `SeloExpress parcel ${id}`);
  return { orderId: id, totalNgwee: q.totalNgwee, insuranceId: ins };
});

/* --------------------------------------------------------------- broadcast */

export async function broadcast(orderId: string) {
  const o = (await orderRef(orderId).get()).data();
  if (!o) return;
  const zones = (await db.collection("zones").where("isActive", "==", true).get()).docs;
  const zone = o.pickupGeo ? zones.find((z) => haversineKm(o.pickupGeo, z.get("centre")) <= z.get("radiusKm")) : zones.find((z) => z.get("name") === o.zoneName);
  const cfg = await loadConfig();
  let q = db.collection("riderPresence").where("isOnline", "==", true);
  if (zone) q = q.where("zoneIds", "array-contains", zone.id);
  const riders = (await q.limit(50).get()).docs.filter((r) => (r.get("activeCount") ?? 0) < cfg.maxConcurrentDeliveries).map((r) => r.id);
  await orderRef(orderId).update({ zoneId: zone?.id ?? null, notifiedRiders: riders, broadcastAt: FieldValue.serverTimestamp() });
  const net = split(o.goodsNgwee, o.deliveryNgwee, o.feeSnapshot).riderNetNgwee;
  await Promise.all(riders.map((r) => notify(r, "New Order Available!", `K${(net / 100).toFixed(2)} net · ${o.pickup} → ${o.dropoff} · ~${o.distanceKm} km`,
    "NEW_ORDER_AVAILABLE", `selo://rider/jobs/${orderId}`)));
}

/* ----------------------------------------------------------------- webhook */

/** Lipila calls this for every collection and disbursement result. Idempotent per reference. */
export const lipila_webhook = onRequest({ secrets: [LIPILA_WEBHOOK_SECRET, LIPILA_API_KEY] }, async (req, res) => {
  if (!verifyLipilaSignature(req.rawBody, req.get("x-lipila-signature") ?? undefined, LIPILA_WEBHOOK_SECRET.value())) {
    res.status(401).send("bad signature"); return;
  }
  const { referenceId, status, message } = req.body as { referenceId: string; status: string; message?: string };
  const ok = /^(success|successful|completed)$/i.test(String(status));
  const seen = db.doc(`lipilaEvents/${referenceId}_${status}`);
  if ((await seen.get()).exists) { res.send("duplicate"); return; }
  await seen.set({ body: req.body, at: FieldValue.serverTimestamp() });

  try {
    if (referenceId.startsWith("SELO-COL-")) await onCollection(referenceId, ok, String(status), message);
    else if (referenceId.startsWith("SELO-BST-") || referenceId.startsWith("SELO-STY-")) await onPromoCollection(referenceId, ok, String(status), message);
    else if (referenceId.startsWith("SELO-REG-")) await db.doc(`registrationFees/${referenceId}`).update({ status: ok ? "PAID" : "FAILED" });
    else await onDisbursement(referenceId, ok);
    res.send("ok");
  } catch (e) {
    logger.error("webhook failed", referenceId, e);
    res.status(500).send("error");
  }
});

async function onCollection(reference: string, ok: boolean, status: string, message?: string) {
  const orderId = reference.replace(/^SELO-COL-/, "").replace(/-R\d+$/, "");
  const o = (await orderRef(orderId).get()).data();
  if (!o) return;
  const current = (o.lipilaCollectionRef ?? `SELO-COL-${orderId}`) === reference;
  if (!ok) {
    // Keep the order open: the buyer can pay from another number (order_retryPayment). Unpaid orders expire after unpaidOrderCancelMin.
    if (o.state === "INITIATED" && current) {
      const reason = collectionFailureReason(status, message, o.walletMsisdn);
      await orderRef(orderId).update({ paymentStatus: "FAILED", paymentFailure: reason, updatedAt: FieldValue.serverTimestamp() });
      await notify(o.buyerUid, "Payment didn't go through", `${reason} Open the order to pay with another number.`, "PAYMENT_FAILED", `selo://orders/${orderId}`);
    }
    return;
  }
  if (o.state !== "INITIATED") {
    // An earlier attempt was approved after a later one already paid: return the duplicate.
    // An approval for an expired/cancelled order is returned too.
    if (o.paidRef !== reference) await disburse(`SELO-REF-${reference}`, "duplicate_refund", o.buyerUid, o.totalNgwee,
      o.attemptWallets?.[reference] ?? o.walletMsisdn, orderId);
    return;
  }
  await db.runTransaction(async (tx) => {
    move(tx, orderId, "INITIATED", "ESCROW_LOCKED", { escrowLockedAt: FieldValue.serverTimestamp(), paymentStatus: "PAID", paymentFailure: null, paidRef: reference });
    tx.set(ledger(orderId).doc(), { type: "credit", leg: "collection", party: "escrow", amountNgwee: o.totalNgwee, lipilaRef: reference, at: FieldValue.serverTimestamp() });
    if ((o.serviceFeeNgwee ?? 0) + (o.processingFeeNgwee ?? 0) > 0) tx.set(db.doc(`platformRevenue/rev_${reference}`), { source: "order_fees", orderId,
      amountNgwee: (o.serviceFeeNgwee ?? 0) + (o.processingFeeNgwee ?? 0), serviceFeeNgwee: o.serviceFeeNgwee ?? 0, processingFeeNgwee: o.processingFeeNgwee ?? 0,
      operator: operatorLabel(o.walletMsisdn), lipilaRef: reference, at: FieldValue.serverTimestamp() });
  });
  await notify(o.buyerUid, "Payment successful", "Funds secured in escrow.", "PAYMENT_CONFIRMED", `selo://orders/${orderId}`);
  if (!o.isParcel) await notify(o.sellerUid, "New order", `${o.items.map((i: { title: string; qty: number }) => `${i.title} × ${i.qty}`).join(", ")} · escrow held`, "NEW_ORDER", `selo://seller/orders/${orderId}`);
  await db.runTransaction(async (tx) => move(tx, orderId, "ESCROW_LOCKED", "BROADCASTING"));
  await broadcast(orderId);
}

async function onDisbursement(ref: string, ok: boolean) {
  const pRef = db.doc(`payouts/${ref}`);
  const p = (await pRef.get()).data();
  if (!p) return;
  if (ok) {
    await pRef.update({ status: "DISBURSED", completedAt: FieldValue.serverTimestamp() });
    if (p.orderId) {
      await orderRef(p.orderId).update({ [`${p.leg}PayoutStatus`]: p.leg === "refund" ? "REFUNDED" : "DISBURSED" });
      await ledger(p.orderId).add({ type: "debit", leg: p.leg, party: p.leg, amountNgwee: p.amountNgwee, lipilaRef: ref, at: FieldValue.serverTimestamp() });
      const o = (await orderRef(p.orderId).get()).data();
      if (o?.state === "PAYOUT_PENDING_RETRY") await orderRef(p.orderId).update({ state: "DELIVERED" });
    }
    if (p.withdrawalOf) await settleWithdrawal(p.withdrawalOf, p.grossNgwee, ref);
    await notify(p.uid, p.leg === "withdrawal" ? "Payout Sent" : "Payment released", `K${(p.amountNgwee / 100).toFixed(2)} sent to ${p.msisdn}`, "WITHDRAWAL_COMPLETED");
  } else {
    await failPayout(ref, p.attempts ?? 0);
  }
}

async function failPayout(ref: string, attempts: number) {
  const pRef = db.doc(`payouts/${ref}`);
  const p = (await pRef.get()).data()!;
  const wait = nextRetryMinutes(attempts);
  await pRef.update({ status: wait === null ? "ESCALATED" : "FAILED_RETRY", attempts: attempts + 1,
    nextAttemptAt: wait === null ? null : Timestamp.fromMillis(Date.now() + wait * 60_000), lastFailureAt: FieldValue.serverTimestamp() });
  if (p.orderId) await orderRef(p.orderId).update({ [`${p.leg}PayoutStatus`]: "FAILED_RETRY", ...(p.leg === "rider" ? { state: "PAYOUT_PENDING_RETRY" } : {}) });
  if (p.leg === "withdrawal") await notify(p.uid, "Payout Failed", "Your withdrawal could not be processed. Your balance is unchanged.", "WITHDRAWAL_FAILED");
}

/** Creates a payout record then calls Lipila; failures go to the retry queue, never dropped. */
export async function disburse(ref: string, leg: string, uid: string, amountNgwee: number, msisdn: string, orderId?: string, extra: Record<string, unknown> = {}) {
  await db.doc(`payouts/${ref}`).set({ ref, leg, uid, amountNgwee, msisdn, orderId: orderId ?? null, status: "PENDING", attempts: 0,
    createdAt: FieldValue.serverTimestamp(), ...extra }, { merge: true });
  const r = await lipila.disburse(ref, amountNgwee, msisdn, `SeloNaChipa ${leg}`).catch(() => ({ ok: false, status: 0 }));
  if (!r.ok) await failPayout(ref, 0);
}

export async function retryPayout(ref: string) {
  const p = (await db.doc(`payouts/${ref}`).get()).data();
  if (!p || p.status === "DISBURSED") return;
  await db.doc(`payouts/${ref}`).update({ status: "PENDING" });
  const r = await lipila.disburse(ref, p.amountNgwee, p.msisdn, `SeloNaChipa ${p.leg} retry`).catch(() => ({ ok: false, status: 0 }));
  if (!r.ok) await failPayout(ref, p.attempts ?? 0);
}

export async function refund(orderId: string, reason: string) {
  const o = (await orderRef(orderId).get()).data();
  if (!o || !["ESCROW_LOCKED", "BROADCASTING", "RIDER_ASSIGNED"].includes(o.state)) return;
  await db.runTransaction(async (tx) => move(tx, orderId, o.state, "REFUNDED", { refundReason: reason }));
  await disburse(`SELO-REF-${orderId}`, "refund", o.buyerUid, o.totalNgwee, o.walletMsisdn, orderId);
  await notify(o.buyerUid, "Order refunded", `${reason}. K${(o.totalNgwee / 100).toFixed(2)} is on its way back to your wallet.`, "REFUND");
}

/* -------------------------------------------------------- seller and rider */

export const order_confirmPack = onCall(async (req) => {
  const { uid } = requireUser(req, "SELLER", "AGENT");
  const id = str(req.data?.orderId, "order");
  const o = (await orderRef(id).get()).data();
  if (!o || (o.sellerUid !== uid && o.agentUid !== uid)) throw new HttpsError("not-found", "Order not found.");
  await orderRef(id).update({ packedAt: FieldValue.serverTimestamp() });
  return { ok: true };
});

export const order_sellerReject = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "SELLER", "AGENT");
  const id = str(req.data?.orderId, "order");
  const o = (await orderRef(id).get()).data();
  if (!o || o.sellerUid !== uid) throw new HttpsError("not-found", "Order not found.");
  await refund(id, "The seller couldn't fulfil this order");
  return { ok: true };
});

export const rider_acceptOrder = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const id = str(req.data?.orderId, "order");
  const cfg = await loadConfig();
  const rider = (await db.doc(`users/${uid}`).get()).data()!;
  if (rider.accountStatus !== "ACTIVE") throw new HttpsError("permission-denied", "Your account isn't verified yet.");
  const o = await db.runTransaction(async (tx) => {
    const snap = await tx.get(orderRef(id));
    const wallet = await tx.get(db.doc(`riderWallets/${uid}`));
    const o = snap.data();
    if (!o || o.state !== "BROADCASTING" || o.riderUid) throw new HttpsError("already-exists", "Order already taken.");
    if ((wallet.get("activeCount") ?? 0) >= cfg.maxConcurrentDeliveries) throw new HttpsError("resource-exhausted", `You already have ${cfg.maxConcurrentDeliveries} active deliveries.`);
    if (o.isInsured && (wallet.get("collateralNgwee") ?? 0) < o.declaredValueNgwee * 0.1)
      throw new HttpsError("failed-precondition", "Your rider fund must exceed 10% of this parcel's declared value.");
    move(tx, id, "BROADCASTING", "RIDER_ASSIGNED", { riderUid: uid, riderName: rider.fullName, assignedAt: FieldValue.serverTimestamp() });
    tx.update(db.doc(`riderWallets/${uid}`), { activeCount: FieldValue.increment(1) });
    tx.set(db.doc(`riderPresence/${uid}`), { activeCount: FieldValue.increment(1) }, { merge: true });
    return o;
  });
  // Buyer may watch this rider's live location only while the order is active (RTDB rule orderViewers).
  await getDatabase().ref(`orderViewers/${uid}/${o.buyerUid}`).set(true);
  await notify(o.sellerUid, "Rider on the way", `${rider.fullName} accepted order ${id}.`, "RIDER_ASSIGNED");
  await notify(o.buyerUid, "Rider assigned", `${rider.fullName} is collecting your order.`, "RIDER_ASSIGNED", `selo://orders/${id}`);
  return { ok: true };
});

/** PICKED_UP releases the seller leg (order amount minus flat platform fee). */
export const rider_markCollected = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const id = str(req.data?.orderId, "order");
  const o = await db.runTransaction(async (tx) => {
    const o = (await tx.get(orderRef(id))).data();
    if (!o || o.riderUid !== uid) throw new HttpsError("not-found", "Order not found.");
    move(tx, id, "RIDER_ASSIGNED", "PICKED_UP", { collectedAt: FieldValue.serverTimestamp(), collectionPhoto: req.data?.photoPath ?? null });
    const s = split(o.goodsNgwee, o.deliveryNgwee, o.feeSnapshot);
    if (!o.isParcel) tx.set(ledger(id).doc(), { type: "credit", leg: "platform_fee", party: "platform", amountNgwee: s.platformFeeNgwee, at: FieldValue.serverTimestamp() });
    return o;
  });
  if (!o.isParcel) {
    const s = split(o.goodsNgwee, o.deliveryNgwee, o.feeSnapshot);
    const seller = (await db.doc(`users/${o.sellerUid}`).get()).data()!;
    await disburse(`SELO-SELR-${id}`, "seller", o.sellerUid, s.sellerNetNgwee, seller.payoutWallet, id);
    await db.doc(`sellerStats/${o.sellerUid}`).set({ earningsNgwee: FieldValue.increment(s.sellerNetNgwee), todayNgwee: FieldValue.increment(s.sellerNetNgwee),
      monthNgwee: FieldValue.increment(s.sellerNetNgwee), monthOrders: FieldValue.increment(1) }, { merge: true });
    await notify(o.sellerUid, "Payment released", `Rider collected ${id}. K${(s.sellerNetNgwee / 100).toFixed(2)} is on its way to your wallet.`, "PAYOUT");
  }
  await db.runTransaction(async (tx) => move(tx, id, "PICKED_UP", "IN_TRANSIT"));
  await notify(o.buyerUid, "Your order is on the way!", "Track your rider in the app.", "IN_TRANSIT", `selo://orders/${id}`);
  return { ok: true };
});

/** DELIVERED credits the rider leg and the ring-fenced Social Fund, then checks the milestone. */
async function completeDelivery(id: string, by: string) {
  const res = await db.runTransaction(async (tx) => {
    const o = (await tx.get(orderRef(id))).data();
    if (!o) throw new HttpsError("not-found", "Order not found.");
    const wRef = db.doc(`riderWallets/${o.riderUid}`);
    const w = (await tx.get(wRef)).data() ?? {};
    move(tx, id, o.state, "DELIVERED", { deliveredAt: FieldValue.serverTimestamp(), confirmedBy: by, riderPayoutStatus: "DISBURSED" });
    const s = split(o.goodsNgwee, o.deliveryNgwee, o.feeSnapshot);
    const completed = (w.completedDeliveries ?? 0) + 1;
    const unlock = milestoneReached(completed, o.feeSnapshot.socialFundMilestoneDeliveries);
    const fund = (w.socialFundNgwee ?? 0) + s.socialFundNgwee;
    const week = [...(w.weekFund ?? [0, 0, 0, 0, 0, 0, 0])]; week[6] = (week[6] ?? 0) + s.socialFundNgwee;
    tx.set(wRef, {
      availableNgwee: (w.availableNgwee ?? 0) + s.riderNetNgwee + (unlock ? fund : 0), socialFundNgwee: unlock ? 0 : fund,
      completedDeliveries: completed, activeCount: Math.max(0, (w.activeCount ?? 1) - 1), todayNetNgwee: FieldValue.increment(s.riderNetNgwee),
      todayDeliveries: FieldValue.increment(1), weekFund: week, lastMilestoneUnlockAt: unlock ? FieldValue.serverTimestamp() : (w.lastMilestoneUnlockAt ?? null),
      tier: completed >= 300 ? "Hero" : completed >= 50 ? "Rising" : (w.tier ?? "Starter"),
    }, { merge: true });
    tx.set(db.doc(`riderPresence/${o.riderUid}`), { activeCount: FieldValue.increment(-1) }, { merge: true });
    tx.set(db.collection("socialFundTransactions").doc(), { riderUid: o.riderUid, orderId: id, amountNgwee: s.socialFundNgwee, type: "CONTRIBUTION", at: FieldValue.serverTimestamp() });
    if (unlock) tx.set(db.collection("socialFundTransactions").doc(), { riderUid: o.riderUid, orderId: id, amountNgwee: fund, type: "WITHDRAWAL", at: FieldValue.serverTimestamp() });
    tx.set(db.collection(`ledgers/${o.riderUid}/entries`).doc(), { type: "delivery_credit", orderId: id, gross: o.deliveryNgwee,
      platformFee: s.riderPlatformFeeNgwee, socialLevy: s.socialFundNgwee, net: s.riderNetNgwee, at: FieldValue.serverTimestamp() });
    tx.set(ledger(id).doc(), { type: "debit", leg: "rider", party: "rider", amountNgwee: s.riderNetNgwee, at: FieldValue.serverTimestamp() });
    tx.set(ledger(id).doc(), { type: "debit", leg: "social_fund", party: "rider_social_fund", amountNgwee: s.socialFundNgwee, at: FieldValue.serverTimestamp() });
    return { o, s, unlock, fund };
  });
  await notify(res.o.riderUid, "Delivery Confirmed", `K${(res.s.riderNetNgwee / 100).toFixed(2)} credited to your earnings!`, "DELIVERY_CONFIRMED");
  if (res.unlock) await notify(res.o.riderUid, "Social Fund unlocked", `K${(res.fund / 100).toFixed(2)} moved to your available balance.`, "SOCIAL_FUND_UNLOCKED");
  await db.doc(`deliveryCodes/${id}`).delete().catch(() => undefined);
  await getDatabase().ref(`orderViewers/${res.o.riderUid}/${res.o.buyerUid}`).remove().catch(() => undefined);
}

export const order_confirmDelivery = onCall(async (req) => {
  const { uid } = requireUser(req, "BUYER", "SELLER");
  const id = str(req.data?.orderId, "order");
  const o = (await orderRef(id).get()).data();
  if (!o || o.buyerUid !== uid) throw new HttpsError("not-found", "Order not found.");
  if (o.state !== "IN_TRANSIT") throw new HttpsError("failed-precondition", "Your order isn't with a rider yet.");
  await completeDelivery(id, "buyer");
  return { ok: true };
});

export const rider_confirmDelivery = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const id = str(req.data?.orderId, "order");
  const o = (await orderRef(id).get()).data();
  if (!o || o.riderUid !== uid) throw new HttpsError("not-found", "Order not found.");
  if (o.deliveryCodeHash !== sha256(String(req.data?.code ?? ""))) throw new HttpsError("permission-denied", "That code doesn't match. Ask the buyer to check their app.");
  await orderRef(id).update({ proofOfDelivery: req.data?.photoPath ?? null });
  await completeDelivery(id, "rider_code");
  return { ok: true };
});

export { completeDelivery };

export const rider_setPresence = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const online = req.data?.online === true;
  const zones = (await db.doc(`riderZones/${uid}`).get()).data()?.activeZoneIds ?? [];
  if (online && !zones.length) throw new HttpsError("failed-precondition", "Activate at least one delivery zone to go online.");
  await db.doc(`riderPresence/${uid}`).set({ isOnline: online, zoneIds: zones, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return { ok: true };
});

/* ------------------------------------------------------------------ wallets */

export const wallet_lookup = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const m = normaliseMsisdn(req.data?.msisdn);
  if (!m) throw new HttpsError("invalid-argument", "Enter a valid wallet number.");
  const name = await lipila.lookup(m);
  if (!name) throw new HttpsError("not-found", "We couldn't find a wallet on that number.");
  return { holderName: name };
});

/** Rider / seller / agent withdrawal. Balance is deducted only when the Lipila webhook confirms. */
export const wallet_withdraw = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid, role } = requireUser(req, "RIDER", "SELLER", "AGENT");
  const cfg = await loadConfig();
  const gross = Math.round(Number(req.data?.amountNgwee));
  const fee = Math.round(cfg.riderWithdrawalFee * 100);
  if (!Number.isFinite(gross) || gross - fee < cfg.minWithdrawal * 100)
    throw new HttpsError("invalid-argument", `Minimum withdrawal is K${cfg.minWithdrawal + cfg.riderWithdrawalFee} (K${cfg.minWithdrawal} payout + K${cfg.riderWithdrawalFee} fee).`);
  const user = (await db.doc(`users/${uid}`).get()).data()!;
  const walletDoc = role === "RIDER" ? `riderWallets/${uid}` : `sellerStats/${uid}`;
  const field = role === "RIDER" ? "availableNgwee" : "earningsNgwee";
  const bal = (await db.doc(walletDoc).get()).get(field) ?? 0;
  if (gross > bal) throw new HttpsError("failed-precondition", "That's more than your available balance.");
  const wid = shortId("WD");
  await db.doc(`withdrawals/${wid}`).set({ uid, role, grossNgwee: gross, feeNgwee: fee, netNgwee: gross - fee, msisdn: user.payoutWallet,
    walletDoc, field, status: "PROCESSING", createdAt: FieldValue.serverTimestamp() });
  await disburse(`SELO-RIDW-${uid.slice(0, 8)}-${Date.now()}`, "withdrawal", uid, gross - fee, user.payoutWallet, undefined, { withdrawalOf: wid, grossNgwee: gross });
  return { withdrawalId: wid };
});

async function settleWithdrawal(wid: string, gross: number, ref: string) {
  const w = (await db.doc(`withdrawals/${wid}`).get()).data();
  if (!w || w.status === "COMPLETED") return;
  await db.doc(w.walletDoc).update({ [w.field]: FieldValue.increment(-gross) });
  await db.doc(`withdrawals/${wid}`).update({ status: "COMPLETED", lipilaRef: ref, completedAt: FieldValue.serverTimestamp() });
  await db.collection(`ledgers/${w.uid}/entries`).add({ type: "withdrawal", gross, fee: w.feeNgwee, net: w.netNgwee, lipilaRef: ref, at: FieldValue.serverTimestamp() });
}
