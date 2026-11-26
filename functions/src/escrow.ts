import { randomInt } from "node:crypto";
import { FieldValue, Timestamp, Transaction } from "firebase-admin/firestore";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import { getDatabase } from "firebase-admin/database";
import { DB_ID, LIPILA_API_KEY, LIPILA_WEBHOOK_SECRET, MAPS_API_KEY, PlatformConfig, db, loadConfig } from "./config";
import { acceptanceRate, withinWorkingHours, canTransition, collectionFailureReason, haversineKm, network, nextRetryMinutes, normaliseMsisdn, operatorLabel, OrderState, quote, split } from "./domain";
import { onPromoCollection } from "./promotions";
import { writeLedger, type Leg } from "./ledger";
import { isFailed, isSuccessful, lipila } from "./lipila";
import { DEFAULT_TOLERANCE_SECONDS, isSuccessStatus, parseSecrets, toPayload, verifyLipilaSignature } from "./payments/lipila-webhook";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { notify, requireUser, str } from "./util";
import { sendSms, sendTemplate } from "./sms";
import { routeDistance } from "./geo";

const ledger = (orderId: string) => db.collection(`escrowLedger/${orderId}/entries`);
const orderRef = (id: string) => db.doc(`orders/${id}`);
const shortId = (p: string) => `${p}-${randomInt(10_000, 99_999)}${randomInt(10, 99)}`;

function feeSnapshot(c: PlatformConfig) {
  const { flatPlatformFeeKwacha, buyerServiceFeePct, riderPlatformFeePct, socialFundPct, deliveryFeePerKm, deliveryBaseFee,
    insurancePremiumPct, insuranceMaxDeclaredValue, agentPlatformCutPct, socialFundMilestoneDeliveries, paymentProcessingPct, heroBonusPct } = c;
  return { flatPlatformFeeKwacha, buyerServiceFeePct, riderPlatformFeePct, socialFundPct, deliveryFeePerKm, deliveryBaseFee,
    insurancePremiumPct, insuranceMaxDeclaredValue, agentPlatformCutPct, socialFundMilestoneDeliveries, paymentProcessingPct, heroBonusPct,
    sellerReleaseOn: c.sellerReleaseOn };
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


/* ------------------------------------------------------------------ create */

export const order_create = onCall({ secrets: [LIPILA_API_KEY, MAPS_API_KEY] }, async (req) => {
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
  const buyerPoint = buyer.location && Number.isFinite(buyer.location.latitude) ? { lat: buyer.location.latitude, lng: buyer.location.longitude } : buyer.geo ?? null;
  const dropGeo = isGeo(req.data?.dropoffGeo) ? { lat: Number(req.data.dropoffGeo.lat), lng: Number(req.data.dropoffGeo.lng) } : buyerPoint;
  const pickGeo = sellerPoint(seller);
  const route = pickGeo && dropGeo ? await routeDistance(pickGeo, dropGeo) : null; // Routes API matrix, cached
  const distanceKm = route?.distanceKm ?? 6;
  const q = quote(goods, distanceKm, cfg);
  const id = shortId("SNC");
  await orderRef(id).set({
    buyerUid: uid, buyerName: buyer.fullName ?? "", buyerMsisdn: buyer.msisdn ?? "", sellerUid, sellerName: seller.storeName ?? seller.fullName ?? "",
    agentUid: listings[0].get("agentUid") ?? null, items: lines, goodsNgwee: q.goodsNgwee, deliveryNgwee: q.deliveryNgwee,
    serviceFeeNgwee: q.serviceFeeNgwee, processingFeeNgwee: q.processingFeeNgwee, totalNgwee: q.totalNgwee, distanceKm, paymentStatus: "PENDING", pickup: seller.landmark ?? seller.area ?? "",
    pickupGeo: pickGeo, dropoff, dropoffGeo: dropGeo,
    pickupLocation: pickGeo ? { address: seller.location?.address ?? seller.landmark ?? seller.area ?? "", latitude: pickGeo.lat, longitude: pickGeo.lng } : null,
    deliveryLocation: dropGeo ? { address: dropoff, latitude: dropGeo.lat, longitude: dropGeo.lng } : null,
    etaMinutes: (route?.durationMin ?? 20) + 10, deliveryFee: q.deliveryNgwee / 100, distanceApproximate: route?.approximate ?? true, addressId: req.data?.addressId ?? null, zoneName: seller.area ?? "", walletMsisdn: wallet,
    state: "INITIATED", feeSnapshot: feeSnapshot(cfg), isParcel: false, riderUid: null, notifiedRiders: [],
    sellerPayoutStatus: "HELD", riderPayoutStatus: "HELD", timeline: { INITIATED: FieldValue.serverTimestamp() },
    createdAt: FieldValue.serverTimestamp(),
  });
  await createCollection(id, q.totalNgwee, wallet, `SeloNaChipa order ${id}`);
  return { orderId: id, totalNgwee: q.totalNgwee };
});

const isGeo = (v: any) => v && Number.isFinite(Number(v.lat)) && Number.isFinite(Number(v.lng));
/** Seller pickup point: the saved store location (users.location) first, the legacy geo field second. */
const sellerPoint = (s: any) => s?.location && Number.isFinite(s.location.latitude) ? { lat: s.location.latitude, lng: s.location.longitude } : isGeo(s?.geo) ? { lat: Number(s.geo.lat), lng: Number(s.geo.lng) } : null;

/** Checkout preview: delivery fee from the road distance between the seller and the chosen drop-off (§ address system). */
export const order_quote = onCall({ secrets: [MAPS_API_KEY] }, async (req) => {
  requireUser(req);
  const items = (req.data?.items ?? []) as { listingId: string; qty: number }[];
  if (!items.length) throw new HttpsError("invalid-argument", "Your cart is empty.");
  if (!isGeo(req.data?.dropoffGeo)) throw new HttpsError("invalid-argument", "Choose a delivery address.");
  const drop = { lat: Number(req.data.dropoffGeo.lat), lng: Number(req.data.dropoffGeo.lng) };
  const cfg = await loadConfig();
  const listings = await Promise.all(items.map((i) => db.doc(`listings/${i.listingId}`).get()));
  const unavailable = listings.filter((l) => !l.exists || l.get("status") !== "live" || (l.get("stock") ?? 1) <= 0).map((l) => l.id);
  const goods = items.reduce((s, it, k) => s + Math.max(1, Math.floor(it.qty)) * (listings[k].get("priceNgwee") ?? 0), 0);
  const seller = (await db.doc(`users/${listings[0].get("sellerUid")}`).get()).data() ?? {};
  const from = sellerPoint(seller);
  const route = from ? await routeDistance(from, drop) : { distanceKm: 6, durationMin: 20, approximate: true };
  const q = quote(goods, route.distanceKm, cfg);
  return { ...q, distanceKm: route.distanceKm, approximate: route.approximate || !from, etaMin: route.durationMin + 10, unavailable,
    feeBreakdown: { baseFee: cfg.deliveryBaseFee, perKm: cfg.deliveryFeePerKm, km: route.distanceKm } };
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


/* --------------------------------------------------------------- broadcast */

export async function broadcast(orderId: string) {
  const o = (await orderRef(orderId).get()).data();
  if (!o) return;
  const zones = (await db.collection("zones").where("isActive", "==", true).get()).docs;
  const zone = o.pickupGeo ? zones.find((z) => haversineKm(o.pickupGeo, z.get("centre")) <= z.get("radiusKm")) : zones.find((z) => z.get("name") === o.zoneName);
  const cfg = await loadConfig();
  let q = db.collection("riderPresence").where("isOnline", "==", true);
  if (zone) q = q.where("zoneIds", "array-contains", zone.id);
  const candidates = (await q.limit(50).get()).docs.filter((r) => (r.get("activeCount") ?? 0) < cfg.maxConcurrentDeliveries).map((r) => r.id);
  // Outside their own working hours riders get no dispatches, even when marked online (PRD 6.10).
  const hours = candidates.length ? await db.getAll(...candidates.map((id) => db.doc(`riderZones/${id}`))) : [];
  const riders = candidates.filter((_, i) => withinWorkingHours(hours[i]?.get("workingHours")));
  await orderRef(orderId).update({ zoneId: zone?.id ?? null, notifiedRiders: riders, broadcastAt: FieldValue.serverTimestamp() });
  const net = split(o.goodsNgwee, o.deliveryNgwee, o.feeSnapshot).riderNetNgwee;
  await Promise.all(riders.map((r) => notify(r, "New Order Available!", `K${(net / 100).toFixed(2)} net · ${o.pickup} → ${o.dropoff} · ~${o.distanceKm} km`,
    "NEW_ORDER_AVAILABLE", `selo://rider/jobs/${orderId}`)));
}

/* ----------------------------------------------------------------- webhook */

/**
 * Lipila webhook (Standard Webhooks signing, HMAC-SHA256 — see payments/lipila-webhook.ts).
 * 1) Verify on the raw bytes: headers → timestamp within 300 s → signature (constant time). Failure → 401.
 * 2) Idempotency: webhook_idempotency_log/{webhook-id} and lipila_webhook_events/{webhook-id} are created together;
 *    a repeat delivery is acknowledged with 200 and ignored.
 * 3) Every field is stored verbatim for the Super Admin Transaction Monitor; processing runs asynchronously
 *    (onLipilaWebhookEvent) so Lipila always gets a fast 200. Only a failure to *store* the event returns 503, so
 *    Lipila retries instead of the payment being lost.
 */
export const lipila_webhook = onRequest({ secrets: [LIPILA_WEBHOOK_SECRET] }, async (req, res) => {
  if (req.method !== "POST") { res.status(405).send("method"); return; }
  let webhookId: string;
  try {
    ({ webhookId } = verifyLipilaSignature(req.rawBody, req.headers as Record<string, unknown>, parseSecrets(LIPILA_WEBHOOK_SECRET.value()),
      { toleranceSeconds: Number(process.env.LIPILA_WEBHOOK_TOLERANCE_SECONDS) || DEFAULT_TOLERANCE_SECONDS }));
  } catch (e) {
    logger.warn("lipila webhook rejected", { reason: (e as Error).message, ip: req.ip });
    res.status(401).send("unauthorized"); return;
  }
  let raw: unknown; try { raw = JSON.parse(req.rawBody.toString("utf8")); } catch { raw = null; }
  const p = toPayload(raw);
  logger.info("lipila webhook", { webhookId, status: p.status, paymentType: p.paymentType, amount: p.amount, identifier: p.identifier, type: p.type });
  const batch = db.batch();
  batch.create(db.doc(`webhook_idempotency_log/${webhookId}`), { webhook_id: webhookId, received_at: FieldValue.serverTimestamp(), expires_at: Timestamp.fromMillis(Date.now() + 86_400_000) });
  batch.create(db.doc(`lipila_webhook_events/${webhookId}`), { webhookId, ...p, merchantId: process.env.LIPILA_MERCHANT_ID ?? null, tenantId: null,
    rawPayload: raw ?? null, processedAt: FieldValue.serverTimestamp(), source: "webhook", processed: false });
  try { await batch.commit(); }
  catch (e) {
    if (/already exists|ALREADY_EXISTS|code 6/i.test(String((e as Error).message ?? e)) || (e as { code?: number }).code === 6) { res.status(200).send("duplicate"); return; }
    logger.error("lipila webhook store failed", { webhookId, error: String(e) });
    res.status(503).send("retry"); return;
  }
  await recordTxStats(p).catch(() => undefined);
  res.status(200).send("ok");
});

/** Day counters for the transaction monitor's stat cards (sharded; summed per date range). */
async function recordTxStats(p: ReturnType<typeof toPayload>) {
  const day = new Date().toISOString().slice(0, 10);
  const key = (s: string) => s.replace(/[^A-Za-z0-9]+/g, "_").slice(0, 40) || "Unknown";
  const inc = FieldValue.increment(1);
  const upd: Record<string, unknown> = { total: inc, [`status_${key(p.status)}`]: inc, [`type_${key(p.type)}`]: inc, [`pt_${key(p.paymentType)}`]: inc };
  if (isSuccessStatus(p.status) && /^collection$/i.test(p.type)) { upd.volumeNgwee = FieldValue.increment(Math.round(p.amount * 100)); upd[`ptvol_${key(p.paymentType)}`] = FieldValue.increment(Math.round(p.amount * 100)); }
  await db.doc(`lipila_tx_stats/${day}/shards/${Math.floor(Math.random() * 5)}`).set(upd, { merge: true });
  await db.doc(`lipila_tx_stats/${day}`).set({ day }, { merge: true });
}

/** Processes each stored webhook event once. Errors are logged; Lipila already has its 200. */
export const onLipilaWebhookEvent = onDocumentCreated({ document: "lipila_webhook_events/{id}", database: DB_ID, secrets: [LIPILA_API_KEY] }, async (e) => {
  const ev = e.data?.data(); if (!ev) return;
  const ours = [ev.identifier, ev.referenceId].map(String).find((s) => s.startsWith("SELO-")) ?? String(ev.referenceId);
  try {
    await processLipilaEvent(ours, String(ev.status), ev.message || undefined, ev.rawPayload, ev.referenceId);
    await e.data!.ref.update({ processed: true, handledAt: FieldValue.serverTimestamp() });
  } catch (err) {
    logger.error("lipila event processing failed", { webhookId: e.params.id, error: String(err) });
    await e.data!.ref.update({ processed: false, processingError: String(err).slice(0, 300) });
  }
});

export async function processLipilaEvent(referenceId: string, status: string, message: string | undefined, body: unknown, lipilaRef?: string) {
  const ok = isSuccessful(status);
  if (!ok && !isFailed(status)) return; // Pending, Timeout, Network/System Error, Unknown: not final — reconciliation decides
  try {
    if (referenceId.startsWith("SELO-WD-")) { const { applyLipilaStatus } = await import("./payments/withdrawals"); await applyLipilaStatus(referenceId, status, message, body, "lipila", lipilaRef); }
    else if (referenceId.startsWith("SELO-COL-PCL-")) { const { onParcelCollection } = await import("./parcels"); await onParcelCollection(referenceId, ok, status, message); }
    else if (referenceId.startsWith("SELO-COL-")) await onCollection(referenceId, ok, status, message);
    else if (referenceId.startsWith("SELO-BST-") || referenceId.startsWith("SELO-STY-")) await onPromoCollection(referenceId, ok, status, message);
    else if (referenceId.startsWith("SELO-REG-")) await db.doc(`registrationFees/${referenceId}`).update({ status: ok ? "PAID" : "FAILED" });
    else {
      // Callback carrying only Lipila's own references: try withdrawals by Lipila identifier, then legacy payouts.
      const { applyLipilaStatus } = await import("./payments/withdrawals");
      const r = await applyLipilaStatus(referenceId, status, message, body, "lipila", lipilaRef ?? referenceId);
      if (r === "not_found") await onDisbursement(referenceId, ok);
    }
  } catch (err) {
    logger.error("lipila event failed", referenceId, err);
    throw err; // let the trigger retry
  }
}

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
    const H = (leg: Leg, amountNgwee: number) => amountNgwee > 0 && writeLedger(tx, { kind: "order", orderId, type: "HELD", leg, party: "escrow", amountNgwee, ref: reference });
    H("goods", o.goodsNgwee); H("delivery_fee", o.deliveryNgwee); H("service_fee", o.serviceFeeNgwee ?? 0); H("processing_fee", o.processingFeeNgwee ?? 0);
    if ((o.serviceFeeNgwee ?? 0) + (o.processingFeeNgwee ?? 0) > 0) tx.set(db.doc(`platformRevenue/rev_${reference}`), { source: "order_fees", orderId,
      amountNgwee: (o.serviceFeeNgwee ?? 0) + (o.processingFeeNgwee ?? 0), serviceFeeNgwee: o.serviceFeeNgwee ?? 0, processingFeeNgwee: o.processingFeeNgwee ?? 0,
      operator: operatorLabel(o.walletMsisdn), lipilaRef: reference, at: FieldValue.serverTimestamp() });
  });
  await notify(o.buyerUid, "Payment successful", "Funds secured in escrow.", "PAYMENT_CONFIRMED", `selo://orders/${orderId}`);
  await sendTemplate("ORDER_CONFIRMED", o.buyerMsisdn, [orderId, o.sellerName ?? "your seller"], orderId);
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
    if (p.leg !== "withdrawal") await notify(p.uid, "Payment released", `K${(p.amountNgwee / 100).toFixed(2)} sent to ${p.msisdn}`, "PAYOUT_SETTLED");
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
  if (p.leg === "withdrawal" && wait === null && p.withdrawalOf) {
    await restoreWithdrawal(p.withdrawalOf);
    await notify(p.uid, "Payout failed", "Your withdrawal could not be processed. The full amount is back in your balance.", "WITHDRAWAL_FAILED");
  } else if (p.leg === "withdrawal") await notify(p.uid, "Payout delayed", "Your withdrawal didn't go through yet. We'll retry automatically.", "WITHDRAWAL_RETRY");
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
  await db.runTransaction(async (tx) => {
    move(tx, orderId, o.state, "REFUNDED", { refundReason: reason, escrowStatus: "REFUNDED" });
    writeLedger(tx, { kind: "order", orderId, type: "REFUNDED", leg: "refund", party: "payer", partyUid: o.buyerUid, amountNgwee: o.totalNgwee, note: reason });
    if (o.riderUid) tx.set(db.doc(`riderWallets/${o.riderUid}`), { pendingNgwee: FieldValue.increment(-split(o.goodsNgwee, o.deliveryNgwee, o.feeSnapshot).riderNetNgwee),
      activeCount: FieldValue.increment(-1) }, { merge: true });
  });
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
    const offered = (wallet.get("jobsOffered") ?? 0) + 1, accepted = (wallet.get("jobsAccepted") ?? 0) + 1;
    tx.set(db.doc(`riderWallets/${uid}`), { activeCount: FieldValue.increment(1), jobsOffered: offered, jobsAccepted: accepted,
      acceptanceRate: acceptanceRate(accepted, offered),
      // rider's share sits in pending until the delivery code is verified (§10)
      pendingNgwee: FieldValue.increment(split(o.goodsNgwee, o.deliveryNgwee, o.feeSnapshot).riderNetNgwee) }, { merge: true });
    tx.set(db.doc(`riderPresence/${uid}`), { activeCount: FieldValue.increment(1) }, { merge: true });
    return o;
  });
  // Buyer may watch this rider's live location only while the order is active (RTDB rule orderViewers).
  await getDatabase().ref(`orderViewers/${uid}/${o.buyerUid}`).set(true);
  // Order chats: rider ↔ buyer (delivery instructions) and rider ↔ seller (packed and ready)
  const store = (await db.doc(`users/${o.sellerUid}`).get()).get("storeName") ?? o.sellerName ?? "";
  await db.doc(`chats/${id}_rb`).set({ participants: [uid, o.buyerUid], kind: "rider_buyer", orderId: id, riderUid: uid, buyerUid: o.buyerUid,
    storeName: store, lastMessage: "", lastAt: FieldValue.serverTimestamp(), unread: {} }, { merge: true });
  if (!o.isParcel) await db.doc(`chats/${id}_rs`).set({ participants: [uid, o.sellerUid], kind: "rider_seller", orderId: id, riderUid: uid,
    sellerUid: o.sellerUid, storeName: store, lastMessage: "", lastAt: FieldValue.serverTimestamp(), unread: {} }, { merge: true });
  await notify(o.sellerUid, "Rider on the way", `${rider.fullName} accepted order ${id}.`, "RIDER_ASSIGNED");
  await notify(o.buyerUid, "Rider assigned", `${rider.fullName} is collecting your order.`, "RIDER_ASSIGNED", `selo://orders/${id}`);
  await sendTemplate("RIDER_ASSIGNED", o.buyerMsisdn, [rider.fullName ?? "Your rider", String(rider.msisdn ?? "").replace(/^\+260/, "0")], id);
  return { ok: true };
});

/**
 * PICKED_UP. The seller leg is released here only when the admin policy is "pickup"; by default (Parcels v2 §9.4)
 * seller proceeds are released when the buyer's delivery code is verified.
 */
export const rider_markCollected = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const id = str(req.data?.orderId, "order");
  const cfg = await loadConfig();
  const o: Record<string, any> = await db.runTransaction(async (tx): Promise<Record<string, any>> => {
    const o = (await tx.get(orderRef(id))).data();
    if (!o || o.riderUid !== uid) throw new HttpsError("not-found", "Order not found.");
    move(tx, id, "RIDER_ASSIGNED", "PICKED_UP", { collectedAt: FieldValue.serverTimestamp(), collectionPhoto: req.data?.photoPath ?? null });
    const s = split(o.goodsNgwee, o.deliveryNgwee, o.feeSnapshot);
    const atPickup = !o.isParcel && (o.feeSnapshot?.sellerReleaseOn ?? cfg.sellerReleaseOn) === "pickup";
    if (atPickup) {
      tx.update(orderRef(id), { sellerPayoutStatus: "RELEASING" });
      writeLedger(tx, { kind: "order", orderId: id, type: "RELEASED", leg: "seller_proceeds", party: "seller", partyUid: o.sellerUid, amountNgwee: s.sellerNetNgwee, note: "policy: pickup" });
      writeLedger(tx, { kind: "order", orderId: id, type: "RELEASED", leg: "platform_fee", party: "platform", amountNgwee: s.platformFeeNgwee, note: "seller flat fee" });
    }
    return { ...(o as Record<string, any>), atPickup };
  });
  if (o.atPickup) {
    const s = split(o.goodsNgwee, o.deliveryNgwee, o.feeSnapshot);
    const seller = (await db.doc(`users/${o.sellerUid}`).get()).data()!;
    await disburse(`SELO-SELR-${id}`, "seller", o.sellerUid, s.sellerNetNgwee, seller.payoutWallet, id);
    await db.doc(`sellerStats/${o.sellerUid}`).set({ earningsNgwee: FieldValue.increment(s.sellerNetNgwee), todayNgwee: FieldValue.increment(s.sellerNetNgwee),
      monthNgwee: FieldValue.increment(s.sellerNetNgwee), monthOrders: FieldValue.increment(1) }, { merge: true });
    await notify(o.sellerUid, "Payment released", `Rider collected ${id}. K${(s.sellerNetNgwee / 100).toFixed(2)} is on its way to your wallet.`, "PAYOUT");
  } else if (!o.isParcel) await notify(o.sellerUid, "Order collected", `Rider collected ${id}. You'll be paid as soon as the buyer confirms delivery with their code.`, "COLLECTED");
  await db.runTransaction(async (tx) => move(tx, id, "PICKED_UP", "IN_TRANSIT"));
  await notify(o.buyerUid, "Your order is on the way!", "You'll get a 5-digit delivery code when the rider arrives. Only share it once you have your order.", "IN_TRANSIT", `selo://orders/${id}`);
  if (o.buyerMsisdn) await sendSms(o.buyerMsisdn, `SeloNaChipa: your order ${id} is on its way. You will receive a 5-digit delivery code when the rider arrives. Only share it with the rider once you have your order.`, { eventType: "ORDER_IN_TRANSIT", referenceId: id });
  return { ok: true };
});

/** Buyer "Confirm receipt" after a verified delivery: DELIVERED → COMPLETED (never releases money by itself). */
export const order_confirmDelivery = onCall(async (req) => {
  const { uid } = requireUser(req, "BUYER", "SELLER");
  const id = str(req.data?.orderId, "order");
  const o = (await orderRef(id).get()).data();
  if (!o || o.buyerUid !== uid) throw new HttpsError("not-found", "Order not found.");
  if (o.state !== "DELIVERED") throw new HttpsError("failed-precondition", "Your rider confirms delivery with your 5-digit code first.");
  await db.runTransaction(async (tx) => move(tx, id, "DELIVERED", "COMPLETED", { completedAt: FieldValue.serverTimestamp(), completedBy: "buyer" }));
  return { ok: true };
});

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
/**
 * Rider / seller / agent withdrawal (§10): PIN confirmation, minimum K20, Lipila disbursement.
 * The amount is reserved (deducted) atomically when requested and restored if the disbursement finally fails.
 */
/**
 * Full-balance withdrawal to the user's payout wallet (riders, sellers, agents). Body: { pin }.
 * See payments/withdrawals.ts for the state machine. Any amount sent by the app is ignored.
 */
export const wallet_withdraw = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid, role } = requireUser(req, "RIDER", "SELLER", "AGENT");
  const { requestWithdrawal } = await import("./payments/withdrawals");
  const retryOf = typeof req.data?.retryOf === "string" ? req.data.retryOf.slice(0, 60) : null;
  return requestWithdrawal(uid, role, String(req.data?.pin ?? ""), role === "RIDER" ? "rider" : role === "SELLER" ? "seller" : "agent", retryOf);
});

/** Wallet state for the withdraw button (balance, locked amount, in-flight withdrawal, minimum). */
export const wallet_status = onCall(async (req) => {
  const { uid, role } = requireUser(req, "RIDER", "SELLER", "AGENT");
  const { walletOf, withdrawBlock } = await import("./payments/withdrawals");
  const cfg = await loadConfig();
  const w = walletOf(role, uid);
  const s = await db.doc(w.doc).get();
  const active = s.get("activeWithdrawalId") as string | undefined;
  const req2 = active ? (await db.doc(`withdrawal_requests/${active}`).get()).data() : null;
  const available = (s.get(w.field) as number | undefined) ?? 0;
  return { availableNgwee: available, lockedNgwee: (s.get("lockedNgwee") as number | undefined) ?? 0, minNgwee: Math.round(cfg.minWithdrawal * 100),
    block: withdrawBlock({ availableNgwee: available, activeWithdrawalId: active }, Math.round(cfg.minWithdrawal * 100)),
    active: req2 ? { id: active, status: req2.status, amountNgwee: req2.amount_ngwee, message: req2.error_message ?? null } : null };
});

export async function reserveWithdrawal(wid: string, w: { uid: string; role: string; gross: number; fee: number; msisdn: string; walletDoc: string; field: string; auto?: boolean }) {
  await db.runTransaction(async (tx) => {
    const bal = (await tx.get(db.doc(w.walletDoc))).get(w.field) ?? 0;
    if (w.gross > bal) throw new HttpsError("failed-precondition", "That's more than your available balance.");
    tx.set(db.doc(w.walletDoc), { [w.field]: bal - w.gross, withdrawingNgwee: FieldValue.increment(w.gross) }, { merge: true });
    tx.set(db.doc(`withdrawals/${wid}`), { uid: w.uid, role: w.role, grossNgwee: w.gross, feeNgwee: w.fee, netNgwee: w.gross - w.fee, msisdn: w.msisdn,
      walletDoc: w.walletDoc, field: w.field, status: "PROCESSING", reserved: true, auto: !!w.auto, balanceBefore: bal, balanceAfter: bal - w.gross, createdAt: FieldValue.serverTimestamp() });
  });
}

/** A withdrawal whose disbursement finally failed gives the reserved amount back, atomically. */
export async function restoreWithdrawal(wid: string) {
  await db.runTransaction(async (tx) => {
    const w = (await tx.get(db.doc(`withdrawals/${wid}`))).data();
    if (!w || w.status !== "PROCESSING" || !w.reserved) return;
    tx.set(db.doc(w.walletDoc), { [w.field]: FieldValue.increment(w.grossNgwee), withdrawingNgwee: FieldValue.increment(-w.grossNgwee) }, { merge: true });
    tx.update(db.doc(`withdrawals/${wid}`), { status: "FAILED_RESTORED", failedAt: FieldValue.serverTimestamp() });
  });
}

async function settleWithdrawal(wid: string, gross: number, ref: string) {
  const w = (await db.doc(`withdrawals/${wid}`).get()).data();
  if (!w || w.status === "COMPLETED") return;
  // reserved withdrawals were deducted at request time; legacy ones are deducted now
  if (w.reserved) await db.doc(w.walletDoc).set({ withdrawingNgwee: FieldValue.increment(-gross) }, { merge: true });
  else await db.doc(w.walletDoc).update({ [w.field]: FieldValue.increment(-gross) });
  await db.doc(`withdrawals/${wid}`).update({ status: "COMPLETED", lipilaRef: ref, completedAt: FieldValue.serverTimestamp() });
  await db.collection(`ledgers/${w.uid}/entries`).add({ type: "withdrawal", gross, fee: w.feeNgwee, net: w.netNgwee, lipilaRef: ref, at: FieldValue.serverTimestamp() });
  const net = selo(w.msisdn);
  await notify(w.uid, "Withdrawal sent", `K${(w.netNgwee / 100).toFixed(2)} has been sent to your ${net} wallet ending ${String(w.msisdn).slice(-4)}`, "WITHDRAWAL_COMPLETED");
}
const selo = (m: string) => ({ airtel: "Airtel", mtn: "MTN", zamtel: "Zamtel" } as Record<string, string>)[network(m)] ?? "mobile money";
