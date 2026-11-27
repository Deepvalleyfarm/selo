/**
 * Parcels Module v2: same-city parcels ≤ 5 kg sent by buyers, sellers and agents, carried by riders.
 * Money: sender pays delivery fee + sending (service) fee (+ insurance) into escrow; the rider's share is released only by
 * a verified 5-digit delivery code (deliveryConfirmation.ts). Only Cloud Functions change `status`.
 */
import { createHash, randomBytes } from "node:crypto";
import { FieldValue, Timestamp, type Transaction } from "firebase-admin/firestore";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { LIPILA_API_KEY, MAPS_API_KEY, SMS_API_KEY, TRACKING_BASE_URL, db, loadConfig, type PlatformConfig } from "./config";
import { collectionFailureReason, normaliseMsisdn, operatorLabel, withinWorkingHours } from "./domain";
import { disburse } from "./escrow";
import { nearestNeighbourhood, routeDistance } from "./geo";
import { writeLedger } from "./ledger";
import { lipila } from "./lipila";
import {
  batchProblem, canMoveParcel, cityById, cityOf, generatePickupPin, INTERCITY_MESSAGE, quoteParcel, receiverLocked, sameCityProblem, suggestCategory,
  weightOk, PARCEL_CATEGORIES, type LL, type ParcelStatus,
} from "./parcel-rules";
import { sendTemplate } from "./sms";
import { offerInWaves } from "./dispatch";
import { audit, notify, requireAdmin, requireUser, str } from "./util";

const ref = (id: string) => db.doc(`parcels/${id}`);
const isLL = (v: any): boolean => !!v && Number.isFinite(Number(v.lat)) && Number.isFinite(Number(v.lng));
const ACTIVE: ParcelStatus[] = ["PENDING_PAYMENT", "AWAITING_RIDER", "RIDER_ASSIGNED", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"];
const shortId = () => `PCL-${randomBytes(4).readUInt32BE(0).toString(36).toUpperCase().padStart(7, "0").slice(0, 7)}`;

function move(tx: Transaction, id: string, from: ParcelStatus, to: ParcelStatus, extra: Record<string, unknown> = {}, event?: string) {
  if (!canMoveParcel(from, to)) throw new HttpsError("failed-precondition", `Parcel can't move from ${from} to ${to}.`);
  tx.update(ref(id), { status: to, [`timeline.${to}`]: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(), ...extra });
  tx.set(db.collection(`parcels/${id}/timeline`).doc(), { event: event ?? to, from, to, at: FieldValue.serverTimestamp() });
}

interface Stop { label: string; address: string; lat: number; lng: number; neighbourhood: string; addressId?: string | null; landmark?: string | null }
function stop(v: any, field: string): Stop {
  if (!v || !isLL(v)) throw new HttpsError("invalid-argument", `Choose the ${field} on the map or from the suggestions.`);
  const at = { lat: Number(v.lat), lng: Number(v.lng) };
  const city = cityOf(at);
  if (!city) throw new HttpsError("failed-precondition", `That ${field} is outside the cities we serve.`);
  const nb = typeof v.neighbourhood === "string" && city.neighbourhoods.includes(v.neighbourhood) ? v.neighbourhood : null;
  return { label: String(v.label ?? "").slice(0, 80), address: String(v.address ?? v.label ?? "").slice(0, 200), ...at,
    neighbourhood: nb ?? nearestNb(at), addressId: typeof v.addressId === "string" ? v.addressId : null,
    landmark: typeof v.landmark === "string" && v.landmark.trim() ? v.landmark.trim().slice(0, 80) : null };
}
function nearestNb(at: LL) { return nearestNeighbourhood(at)?.name ?? cityOf(at)!.name; }

/* ------------------------------------------------------------------ quote (§4 step 3) */

async function quote(pickup: Stop, dropoff: Stop, declaredNgwee: number, cfg: PlatformConfig) {
  const problem = sameCityProblem(pickup, dropoff);
  if (problem) throw new HttpsError("failed-precondition", problem);
  const r = await routeDistance(pickup, dropoff);
  return { ...quoteParcel(r.distanceKm, r.durationMin, declaredNgwee, cfg), approximate: r.approximate, cityId: cityOf(pickup)!.id, cityName: cityOf(pickup)!.name };
}

/** What the sender sees: delivery fee, service fee, insurance, total, ETA. Never a per-km rate or percentages (§3.4). */
export const parcel_quote = onCall({ secrets: [MAPS_API_KEY] }, async (req) => {
  requireUser(req, "BUYER", "SELLER", "AGENT");
  const cfg = await loadConfig();
  const declared = Math.max(0, Math.round(Number(req.data?.declaredValueNgwee) || 0));
  const q = await quote(stop(req.data?.pickup, "pickup"), stop(req.data?.dropoff, "delivery address"), req.data?.insured ? declared : 0, cfg);
  return { distanceKm: q.distanceKm, approximate: q.approximate, deliveryFeeNgwee: q.deliveryNgwee, serviceFeeNgwee: q.sendingNgwee, insuranceNgwee: q.insuranceNgwee,
    totalNgwee: q.totalNgwee, etaMin: q.etaMin, cityName: q.cityName };
});

/* ------------------------------------------------------------------ create + pay */

export const parcel_create = onCall({ secrets: [LIPILA_API_KEY, MAPS_API_KEY] }, async (req) => {
  const { uid, role } = requireUser(req, "BUYER", "SELLER", "AGENT");
  const d = req.data ?? {};
  const cfg = await loadConfig();
  if (!weightOk(d.weightBand)) throw new HttpsError("invalid-argument", `Parcels must be ${cfg.parcelMaxWeightKg} kg or less.`);
  const description = str(d.description, "description", 200);
  const category = (PARCEL_CATEGORIES as readonly string[]).includes(d.category) ? d.category : suggestCategory(description);
  const receiverMsisdn = normaliseMsisdn(d.receiver?.msisdn);
  if (!receiverMsisdn) throw new HttpsError("invalid-argument", "Enter the receiver's Airtel, MTN or Zamtel number — the delivery code is sent to it.");
  const receiverName = str(d.receiver?.name, "receiver name", 60);
  const wallet = normaliseMsisdn(d.walletMsisdn);
  if (!wallet) throw new HttpsError("invalid-argument", "Choose a mobile money number to pay with.");
  const pickup = stop(d.pickup, "pickup"), dropoff = stop(d.dropoff, "delivery address");
  const active = await db.collection("parcels").where("senderUid", "==", uid).where("status", "in", ACTIVE).count().get();
  if (active.data().count >= cfg.maxActiveOrdersPerUser) throw new HttpsError("resource-exhausted", `You can have up to ${cfg.maxActiveOrdersPerUser} active parcels at a time.`);
  const declared = d.insured ? Math.max(0, Math.round(Number(d.declaredValueNgwee) || 0)) : 0;
  const q = await quote(pickup, dropoff, declared, cfg);
  const scheduledFor = d.scheduledFor ? Number(d.scheduledFor) : null;
  if (scheduledFor && (scheduledFor < Date.now() + 30 * 60_000 || scheduledFor > Date.now() + 7 * 86_400_000))
    throw new HttpsError("invalid-argument", "Schedule between 30 minutes and 7 days from now.");
  const [sender, receiverIdx] = await Promise.all([db.doc(`users/${uid}`).get(), db.doc(`msisdnIndex/${receiverMsisdn}`).get()]);
  const s = sender.data() ?? {};
  const credit = Math.min(s.parcelCreditNgwee ?? 0, q.sendingNgwee);
  const id = shortId();
  const pin = generatePickupPin();
  const trackingToken = randomBytes(12).toString("base64url");
  await db.runTransaction(async (tx) => {
    tx.create(ref(id), {
      senderUid: uid, senderRole: role, senderName: s.fullName ?? "", senderMsisdn: s.msisdn ?? null, cityId: q.cityId, cityName: q.cityName,
      pickup, dropoff, weightBand: d.weightBand, description, category, handlingNotes: String(d.handlingNotes ?? "").slice(0, 200),
      receiver: { name: receiverName, msisdn: receiverMsisdn, uid: receiverIdx.get("uid") ?? null }, deliveryInstructions: String(d.deliveryInstructions ?? "").slice(0, 300),
      distanceKm: q.distanceKm, distanceApproximate: q.approximate, etaMin: q.etaMin, declaredValueNgwee: declared, creditAppliedNgwee: credit,
      fees: { deliveryNgwee: q.deliveryNgwee, sendingNgwee: q.sendingNgwee, insuranceNgwee: q.insuranceNgwee, totalNgwee: q.totalNgwee - credit,
        riderEarningsNgwee: q.riderEarningsNgwee, platformFeeNgwee: q.platformFeeNgwee, socialFundNgwee: q.socialFundNgwee,
        rates: { base: cfg.parcelBaseFee, perKm: cfg.parcelPerKmRate, sendingPct: cfg.sendingFeeRate, platformPct: cfg.parcelPlatformFeePct, fundPct: cfg.parcelSocialFundPct } },
      status: "PENDING_PAYMENT", paymentStatus: "PENDING", walletMsisdn: wallet, riderUid: null, notifiedRiders: [], broadcastRound: 0, scheduledFor: scheduledFor ? Timestamp.fromMillis(scheduledFor) : null,
      trackingToken, codeSummary: null, timeline: { PENDING_PAYMENT: FieldValue.serverTimestamp() }, createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(),
    });
    // the 4-digit pickup PIN is for the sender only (rules: readable by senderUid, never by riders)
    tx.set(db.doc(`parcels/${id}/private/pickup`), { senderUid: uid, pin, attempts: 0 });
    tx.set(db.collection(`parcels/${id}/timeline`).doc(), { event: "CREATED", at: FieldValue.serverTimestamp() });
    if (credit > 0) tx.update(sender.ref, { parcelCreditNgwee: FieldValue.increment(-credit) });
  });
  await collect(id, q.totalNgwee - credit, wallet, 0);
  return { parcelId: id, totalNgwee: q.totalNgwee - credit };
});

async function collect(id: string, amountNgwee: number, msisdn: string, attempt: number) {
  const reference = `SELO-COL-${id}${attempt ? `-R${attempt}` : ""}`;
  const r = await lipila.collect(reference, amountNgwee, msisdn, `SeloNaChipa parcel ${id}`).catch(() => ({ ok: false, status: 0 }));
  await ref(id).update({ lipilaRef: reference, walletMsisdn: msisdn, collectionAttempt: attempt, [`attemptWallets.${reference}`]: msisdn,
    paymentStatus: r.ok ? "PENDING" : "FAILED", paymentFailure: r.ok ? null : `${operatorLabel(msisdn)} couldn't start the payment. Try again or use another number.` });
}

export const parcel_retryPayment = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid } = requireUser(req);
  const id = str(req.data?.parcelId, "parcel");
  const wallet = normaliseMsisdn(req.data?.walletMsisdn);
  if (!wallet) throw new HttpsError("invalid-argument", "Enter an Airtel, MTN or Zamtel number.");
  const p = (await ref(id).get()).data();
  if (!p || p.senderUid !== uid) throw new HttpsError("not-found", "Parcel not found.");
  if (p.status !== "PENDING_PAYMENT" || p.paymentStatus !== "FAILED") throw new HttpsError("failed-precondition", "This parcel isn't waiting for a new payment.");
  const attempt = (p.collectionAttempt ?? 0) + 1;
  if (attempt > 5) throw new HttpsError("resource-exhausted", "Too many attempts. Create the parcel again.");
  await collect(id, p.fees.totalNgwee, wallet, attempt);
  return { ok: true };
});

/** Lipila webhook for SELO-COL-PCL-… — escrow HOLD ledger entries, then broadcast (or wait for the scheduled time). */
export async function onParcelCollection(reference: string, ok: boolean, status: string, message?: string) {
  const id = reference.replace(/^SELO-COL-/, "").replace(/-R\d+$/, "");
  const p = (await ref(id).get()).data();
  if (!p) return;
  if (!ok) {
    if (p.status === "PENDING_PAYMENT" && p.lipilaRef === reference) {
      const reason = collectionFailureReason(status, message, p.walletMsisdn);
      await ref(id).update({ paymentStatus: "FAILED", paymentFailure: reason });
      await notify(p.senderUid, "Payment didn't go through", `${reason} Open the parcel to pay with another number.`, "PAYMENT_FAILED", `selo://parcels/${id}`);
    }
    return;
  }
  if (p.status !== "PENDING_PAYMENT") { // duplicate or late approval → give it back
    if (p.paidRef !== reference) await disburse(`SELO-REF-${reference}`, "duplicate_refund", p.senderUid, p.fees.totalNgwee, p.attemptWallets?.[reference] ?? p.walletMsisdn);
    return;
  }
  await db.runTransaction(async (tx) => {
    const fresh = (await tx.get(ref(id))).data();
    if (fresh?.status !== "PENDING_PAYMENT") return;
    move(tx, id, "PENDING_PAYMENT", "AWAITING_RIDER", { paymentStatus: "PAID", paidRef: reference, paidAt: FieldValue.serverTimestamp(), escrowStatus: "HELD" }, "PAYMENT_CONFIRMED");
    const H = (leg: "delivery_fee" | "sending_fee" | "insurance", amount: number) => amount > 0 &&
      writeLedger(tx, { kind: "parcel", orderId: id, type: "HELD", leg, party: "escrow", amountNgwee: amount, ref: reference });
    H("delivery_fee", p.fees.deliveryNgwee); H("sending_fee", p.fees.sendingNgwee - (p.creditAppliedNgwee ?? 0)); H("insurance", p.fees.insuranceNgwee);
  });
  {
    const { issueReceipt } = await import("./payments/receipts");
    await issueReceipt({ reference, kind: "parcel", refId: id, payerUid: p.senderUid, payerName: p.senderName ?? "", payerMsisdn: p.senderMsisdn ?? "",
      onBehalfOf: { type: "rider", name: "your SeloNaChipa delivery rider" },
      lines: [{ label: `Parcel delivery ${p.pickup?.neighbourhood ?? ""} → ${p.dropoff?.neighbourhood ?? ""} (${p.distanceKm} km)`, amountNgwee: p.fees.deliveryNgwee, onBehalfOf: "the delivery rider" },
        { label: "Sending fee", amountNgwee: p.fees.sendingNgwee - (p.creditAppliedNgwee ?? 0), onBehalfOf: "SeloNaChipa" },
        ...(p.fees.insuranceNgwee ? [{ label: "Parcel insurance", amountNgwee: p.fees.insuranceNgwee, onBehalfOf: "SeloNaChipa" }] : [])],
      totalNgwee: p.fees.totalNgwee, walletMsisdn: p.attemptWallets?.[reference] ?? p.walletMsisdn ?? "" }).catch(() => undefined);
  }
  await notify(p.senderUid, "Payment received", `K${(p.fees.totalNgwee / 100).toFixed(2)} is held safely in escrow. ${p.scheduledFor ? "We'll find a rider 30 minutes before your scheduled time." : "Finding a rider now."}`,
    "PAYMENT_CONFIRMED", `selo://parcels/${id}`);
  if (!p.scheduledFor) await broadcastParcel(id);
}

/* ------------------------------------------------------------------ broadcast (§6) */

export async function broadcastParcel(id: string) {
  const p = (await ref(id).get()).data();
  if (!p || p.status !== "AWAITING_RIDER" || p.env === "sandbox") return; // sandbox deliveries are simulated, never offered to real riders
  const cfg = await loadConfig();
  const online = await db.collection("riderPresence").where("isOnline", "==", true).limit(300).get();
  const ids = online.docs.filter((r) => (r.get("activeCount") ?? 0) < cfg.maxConcurrentDeliveries).map((r) => r.id);
  const [areas, zones] = ids.length ? await Promise.all([db.getAll(...ids.map((u) => db.doc(`riderAreas/${u}`))), db.getAll(...ids.map((u) => db.doc(`riderZones/${u}`)))]) : [[], []];
  const widen = (p.broadcastRound ?? 0) >= 2; // after the neighbourhood waves, any rider in the city may see it
  const riders = ids.filter((_, i) => {
    const a = areas[i]?.data();
    if (!a || a.cityId !== p.cityId) return false; // hard city lock (§11)
    if (!widen && !a.wholeCity && !(a.neighbourhoods ?? []).includes(p.pickup.neighbourhood)) return false;
    return withinWorkingHours(zones[i]?.get("workingHours"));
  });
  // Smart dispatch: best-scoring riders first (dispatch.ts); the wave grows each round.
  const round = p.broadcastRound ?? 0;
  const { fresh } = await offerInWaves(`parcels/${id}`, riders.filter((u) => !(p.declinedBy ?? []).includes(u)),
    { pickup: { lat: p.pickup.lat, lng: p.pickup.lng }, dropoff: { lat: p.dropoff.lat, lng: p.dropoff.lng } }, cfg.maxConcurrentDeliveries, round, p.notifiedRiders ?? []);
  await ref(id).update({ broadcastAt: FieldValue.serverTimestamp(), broadcastRound: FieldValue.increment(1) });
  const earnings = `K${(p.fees.riderEarningsNgwee / 100).toFixed(2)}`;
  await Promise.all(fresh.map((r) => notify(r, "New parcel job", `Your Earnings: ${earnings} · ${p.pickup.neighbourhood} → ${p.dropoff.neighbourhood} · ${p.weightBand}`,
    "NEW_ORDER_AVAILABLE", `selo://rider/jobs/parcel/${id}`)));
  if (!riders.length) await ref(id).update({ noRidersAt: FieldValue.serverTimestamp() });
}

/** Every minute: scheduled parcels go out 30 min ahead; unaccepted jobs are re-broadcast every 5 min, refunded after the max wait. */
export const scheduled_parcelDispatch = onSchedule({ schedule: "every 1 minutes", secrets: [LIPILA_API_KEY] }, async () => {
  const cfg = await loadConfig();
  const nowMs = Date.now();
  const waiting = await db.collection("parcels").where("status", "==", "AWAITING_RIDER").limit(300).get();
  for (const d of waiting.docs) {
    const p = d.data();
    if (p.env === "sandbox") continue;
    const sched = (p.scheduledFor as Timestamp | null)?.toMillis();
    if (sched && !p.broadcastAt) { if (sched - nowMs <= 30 * 60_000) await broadcastParcel(d.id); continue; }
    const since = (p.broadcastAt as Timestamp | undefined)?.toMillis() ?? nowMs;
    const first = (p.paidAt as Timestamp | undefined)?.toMillis() ?? since;
    if (nowMs - first >= cfg.parcelMaxBroadcastMin * 60_000) { await refundParcel(d.id, "No rider was available", "system"); continue; }
    const waveMin = (p.broadcastRound ?? 0) < 3 ? 1 : cfg.parcelBroadcastMin; // smart-dispatch waves go out a minute apart
    if (nowMs - since >= waveMin * 60_000) {
      await broadcastParcel(d.id);
      if ((p.broadcastRound ?? 0) >= 3) await notify(p.senderUid, "Still looking for a rider", "No rider has accepted yet. We've widened the search.", "REBROADCAST", `selo://parcels/${d.id}`);
    }
  }
  // auto-offline after 30 min without GPS (§18)
  const stale = await db.collection("riderPresence").where("isOnline", "==", true).where("lastPingAt", "<", Timestamp.fromMillis(nowMs - cfg.riderAutoOfflineMin * 60_000)).limit(200).get();
  for (const r of stale.docs) {
    await r.ref.update({ isOnline: false, offlineReason: "gps_inactive" });
    await notify(r.id, "You're offline", `No GPS for ${cfg.riderAutoOfflineMin} minutes, so we set you offline. Go online again when you're ready.`, "AUTO_OFFLINE");
  }
});

/* ------------------------------------------------------------------ rider side */

/** Operating area: city plus 1–10 neighbourhoods (§11). */
export const rider_setAreas = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const city = cityById(String(req.data?.cityId ?? ""));
  if (!city) throw new HttpsError("invalid-argument", "Choose your city.");
  // "Whole city": the rider takes jobs from every neighbourhood in the chosen city.
  const wholeCity = req.data?.wholeCity === true;
  const nbs = wholeCity ? [] : [...new Set((Array.isArray(req.data?.neighbourhoods) ? req.data.neighbourhoods : []).filter((n: unknown) => typeof n === "string" && city.neighbourhoods.includes(n as string)))];
  if (!wholeCity && (nbs.length < 1 || nbs.length > 10)) throw new HttpsError("invalid-argument", "Choose between 1 and 10 neighbourhoods, or the whole city.");
  await db.doc(`riderAreas/${uid}`).set({ cityId: city.id, cityName: city.name, wholeCity, neighbourhoods: nbs, updatedAt: FieldValue.serverTimestamp() });
  return { ok: true };
});

export const rider_acceptParcel = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const id = str(req.data?.parcelId, "parcel");
  const cfg = await loadConfig();
  const rider = (await db.doc(`users/${uid}`).get()).data();
  if (!rider || rider.accountStatus !== "ACTIVE") throw new HttpsError("permission-denied", "Your account isn't verified yet.");
  const area = (await db.doc(`riderAreas/${uid}`).get()).data();
  const mine = await db.collection("parcels").where("riderUid", "==", uid).where("status", "in", ["RIDER_ASSIGNED", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"]).get();
  const p = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref(id));
    const p = snap.data();
    if (!p || p.status !== "AWAITING_RIDER" || p.riderUid) throw new HttpsError("already-exists", "This job was taken by another rider.");
    if (area?.cityId && area.cityId !== p.cityId) throw new HttpsError("failed-precondition", INTERCITY_MESSAGE);
    const problem = batchProblem(mine.docs.map((m) => m.get("pickup")), p.pickup, cfg);
    if (problem) throw new HttpsError("failed-precondition", problem);
    move(tx, id, "AWAITING_RIDER", "RIDER_ASSIGNED", { riderUid: uid, riderName: rider.fullName ?? "", riderPlate: rider.vehicleDetails?.plate ?? "",
      riderPhotoPath: rider.photoPath ?? null, riderMsisdn: rider.msisdn ?? null, assignedAt: FieldValue.serverTimestamp(), batchSize: mine.size + 1 }, "RIDER_ACCEPTED");
    tx.set(db.doc(`riderWallets/${uid}`), { activeCount: FieldValue.increment(1), pendingNgwee: FieldValue.increment(p.fees.riderEarningsNgwee),
      jobsOffered: FieldValue.increment(1), jobsAccepted: FieldValue.increment(1) }, { merge: true });
    tx.set(db.doc(`riderPresence/${uid}`), { activeCount: FieldValue.increment(1) }, { merge: true });
    // order-scoped sender ↔ rider chat; closes an hour after delivery
    tx.set(db.doc(`chats/${id}_rs`), { participants: [uid, p.senderUid], kind: "rider_sender", orderId: id, riderUid: uid, senderUid: p.senderUid,
      lastMessage: "", lastAt: FieldValue.serverTimestamp(), unread: {}, closesAt: null }, { merge: true });
    return p;
  });
  const eta = p.etaMin ? ` ETA about ${p.etaMin} min.` : "";
  await (await import("./payments/receipts")).setReceiptParty(id, { type: "rider", name: `rider ${rider.fullName ?? ""}`.trim(), uid }).catch(() => undefined);
  await notify(p.senderUid, "Rider on the way", `${rider.fullName} (${rider.vehicleDetails?.plate ?? "plate pending"}) is coming to collect your parcel.${eta}`, "RIDER_ASSIGNED", `selo://parcels/${id}`);
  return { ok: true };
});

export const rider_declineParcel = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const id = str(req.data?.parcelId, "parcel");
  await ref(id).update({ notifiedRiders: FieldValue.arrayRemove(uid), declinedBy: FieldValue.arrayUnion(uid) }).catch(() => undefined);
  await db.doc(`riderWallets/${uid}`).set({ jobsOffered: FieldValue.increment(1), jobsDeclined: FieldValue.increment(1) }, { merge: true });
  return { ok: true };
});

/** §7: rider enters the sender's 4-digit pickup PIN → IN_TRANSIT; receiver gets the "on its way" SMS + push. */
export const rider_confirmPickup = onCall({ secrets: [SMS_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const id = str(req.data?.parcelId, "parcel");
  const pin = String(req.data?.pin ?? "").replace(/\D/g, "");
  const res = await db.runTransaction(async (tx) => {
    const [snap, priv] = await Promise.all([tx.get(ref(id)), tx.get(db.doc(`parcels/${id}/private/pickup`))]);
    const p = snap.data();
    if (!p || p.riderUid !== uid) throw new HttpsError("not-found", "Parcel not found.");
    if (p.status !== "RIDER_ASSIGNED") throw new HttpsError("failed-precondition", "This parcel was already collected.");
    const attempts = (priv.get("attempts") ?? 0) + 1;
    if (attempts > 5) throw new HttpsError("resource-exhausted", "Too many wrong Pickup PINs. Ask the sender to contact support.");
    if (priv.get("pin") !== pin) { tx.update(priv.ref, { attempts }); return { ok: false as const, left: 5 - attempts }; }
    tx.update(priv.ref, { attempts, usedAt: FieldValue.serverTimestamp() });
    move(tx, id, "RIDER_ASSIGNED", "IN_TRANSIT", { pickedUpAt: FieldValue.serverTimestamp(), collectionPhoto: req.data?.photoPath ?? null }, "PICKED_UP");
    return { ok: true as const, p };
  });
  if (!res.ok) throw new HttpsError("permission-denied", `That Pickup PIN doesn't match. ${res.left} attempt${res.left === 1 ? "" : "s"} left.`);
  const p = res.p;
  const track = `${TRACKING_BASE_URL.value()}${p.trackingToken}`;
  const text = `Hi ${p.receiver.name}, your parcel from ${p.senderName} has been picked up by ${p.riderName} (Plate: ${p.riderPlate || "n/a"}) and is on its way. ` +
    `Track: ${track}. You will receive a 5-digit delivery code when the rider arrives. Only share it with the rider once you have your parcel.`;
  await sendTemplate("PARCEL_COLLECTED", p.receiver.msisdn, [p.riderName ?? "your rider", id, track], id);
  if (p.receiver.uid) await notify(p.receiver.uid, "Parcel on the way", text, "IN_TRANSIT", `selo://parcels/${id}`);
  await notify(p.senderUid, "Parcel collected", `${p.riderName} collected your parcel.`, "IN_TRANSIT", `selo://parcels/${id}`);
  return { ok: true };
});

/** Rider GPS every 30 s while online: geofence source of truth + live tracking for senders and receivers. */
export const rider_ping = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  if (!isLL(req.data)) throw new HttpsError("invalid-argument", "Location required.");
  const at = { lat: Number(req.data.lat), lng: Number(req.data.lng) };
  const accuracy = Number(req.data?.accuracy) || null;
  const nowTs = Timestamp.now();
  await db.doc(`riderLocations/${uid}`).set({ ...at, accuracy, at: nowTs });
  await db.doc(`riderPresence/${uid}`).set({ lastPingAt: nowTs, geo: at }, { merge: true });
  await db.doc(`riders/${uid}`).set({ currentLatitude: at.lat, currentLongitude: at.lng, accuracy, heading: Number.isFinite(Number(req.data?.heading)) ? Number(req.data.heading) : null,
    updatedAt: nowTs }, { merge: true });
  const mine = await db.collection("parcels").where("riderUid", "==", uid).where("status", "in", ["RIDER_ASSIGNED", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"]).get();
  const orders = await db.collection("orders").where("riderUid", "==", uid).where("state", "in", ["RIDER_ASSIGNED", "PICKED_UP", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"]).get();
  const b = db.batch();
  [...mine.docs, ...orders.docs].forEach((d) => b.update(d.ref, { riderGeo: { ...at, at: nowTs } }));
  await b.commit();
  return { ok: true };
});

/* ------------------------------------------------------------------ sender actions */

export const parcel_cancel = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid } = requireUser(req);
  const id = str(req.data?.parcelId, "parcel");
  const p = (await ref(id).get()).data();
  if (!p || p.senderUid !== uid) throw new HttpsError("not-found", "Parcel not found.");
  if (!["PENDING_PAYMENT", "AWAITING_RIDER"].includes(p.status)) throw new HttpsError("failed-precondition", "A rider is already assigned. Contact support to cancel.");
  if (p.status === "PENDING_PAYMENT") {
    await db.runTransaction(async (tx) => move(tx, id, "PENDING_PAYMENT", "CANCELLED", { cancelReason: "Cancelled by sender" }));
    return { refundedNgwee: 0 };
  }
  return { refundedNgwee: await refundParcel(id, "Cancelled by sender", `sender:${uid}`) };
});

/** Full (or partial) refund from escrow, ledger REFUNDED, Lipila disbursement back to the paying wallet. */
export async function refundParcel(id: string, reason: string, by: string, amountNgwee?: number) {
  let amount = 0, p: any;
  await db.runTransaction(async (tx) => {
    p = (await tx.get(ref(id))).data();
    if (!p || p.escrowStatus === "REFUNDED" || p.escrowStatus === "RELEASED") return;
    amount = Math.min(amountNgwee ?? p.fees.totalNgwee, p.fees.totalNgwee);
    const to: ParcelStatus = ["AWAITING_RIDER", "RIDER_ASSIGNED"].includes(p.status) ? "CANCELLED" : p.status;
    if (to !== p.status) move(tx, id, p.status, to, { cancelReason: reason });
    tx.update(ref(id), { escrowStatus: amount >= p.fees.totalNgwee ? "REFUNDED" : "PARTIALLY_REFUNDED", refundedNgwee: amount, refundReason: reason });
    writeLedger(tx, { kind: "parcel", orderId: id, type: "REFUNDED", leg: "refund", party: "payer", partyUid: p.senderUid, amountNgwee: amount, by, note: reason });
    if (p.riderUid && ["RIDER_ASSIGNED"].includes(p.status))
      tx.set(db.doc(`riderWallets/${p.riderUid}`), { pendingNgwee: FieldValue.increment(-p.fees.riderEarningsNgwee), activeCount: FieldValue.increment(-1) }, { merge: true });
  });
  if (amount > 0 && p.partnerId) {
    const { post } = await import("./open/settlement");
    await post(p.partnerId, { type: "DELIVERY_REVERSAL", amountNgwee: amount, refType: "delivery", refId: id, env: p.env ?? "production", note: reason });
  } else if (amount > 0) {
    await disburse(`SELO-REF-${id}${amountNgwee ? "-P" : ""}`, "refund", p.senderUid, amount, p.walletMsisdn);
    await notify(p.senderUid, "Parcel refunded", `${reason}. K${(amount / 100).toFixed(2)} is on its way back to your wallet.`, "REFUND", `selo://parcels/${id}`);
  }
  return amount;
}

/** §9.5: the sender may fix the receiver's number only before the parcel is in transit. */
export const parcel_editReceiver = onCall(async (req) => {
  const { uid } = requireUser(req);
  const id = str(req.data?.parcelId, "parcel");
  const m = normaliseMsisdn(req.data?.msisdn);
  if (!m) throw new HttpsError("invalid-argument", "Enter a valid Airtel, MTN or Zamtel number.");
  const p = (await ref(id).get()).data();
  if (!p || p.senderUid !== uid) throw new HttpsError("not-found", "Parcel not found.");
  if (receiverLocked(p.status)) throw new HttpsError("failed-precondition", "The parcel is already on its way. Contact support to change the receiver.");
  const idx = await db.doc(`msisdnIndex/${m}`).get();
  await ref(id).update({ receiver: { name: str(req.data?.name ?? p.receiver.name, "name", 60), msisdn: m, uid: idx.get("uid") ?? null } });
  await db.collection(`parcels/${id}/timeline`).add({ event: "RECEIVER_UPDATED", by: "sender", at: FieldValue.serverTimestamp() });
  return { ok: true };
});

export const admin_editReceiver = onCall(async (req) => {
  const a = requireAdmin(req, "operations", "support");
  const id = str(req.data?.parcelId, "parcel");
  const m = normaliseMsisdn(req.data?.msisdn);
  if (!m) throw new HttpsError("invalid-argument", "Enter a valid number.");
  const reason = str(req.data?.reason, "reason", 300);
  const p = (await ref(id).get()).data();
  if (!p) throw new HttpsError("not-found", "Parcel not found.");
  const idx = await db.doc(`msisdnIndex/${m}`).get();
  const next = { name: String(req.data?.name ?? p.receiver.name).slice(0, 60), msisdn: m, uid: idx.get("uid") ?? null };
  await ref(id).update({ receiver: next, codeRecipient: null });
  await db.collection("admin_audit_log").add({ action: "parcel.edit_receiver", kind: "parcel", orderId: id, actorUid: a.uid, actorEmail: a.email, reason,
    before: { msisdn: p.receiver.msisdn }, after: { msisdn: m }, at: FieldValue.serverTimestamp() });
  await audit(a, "parcel.edit_receiver", `parcels/${id}`, { msisdn: p.receiver.msisdn }, { msisdn: m, reason });
  return { ok: true };
});

export const admin_refundParcel = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const a = requireAdmin(req, "finance", "support");
  const id = str(req.data?.parcelId, "parcel");
  const reason = str(req.data?.reason, "reason", 300);
  const amount = req.data?.amountNgwee != null ? Math.round(Number(req.data.amountNgwee)) : undefined;
  const refunded = await refundParcel(id, reason, `admin:${a.email}`, amount);
  await db.collection("admin_audit_log").add({ action: "parcel.refund", kind: "parcel", orderId: id, actorUid: a.uid, actorEmail: a.email, reason, amountNgwee: refunded, at: FieldValue.serverTimestamp() });
  return { refundedNgwee: refunded };
});

/* ------------------------------------------------------------------ ratings + disputes (§15, §16) */

export const delivery_rate = onCall(async (req) => {
  const { uid } = requireUser(req);
  const kind = req.data?.kind === "order" ? "order" : "parcel";
  const id = str(req.data?.orderId, "order");
  const stars = Math.round(Number(req.data?.stars));
  if (!(stars >= 1 && stars <= 5)) throw new HttpsError("invalid-argument", "Choose 1 to 5 stars.");
  const d = (await db.doc(`${kind === "parcel" ? "parcels" : "orders"}/${id}`).get()).data();
  if (!d) throw new HttpsError("not-found", "Not found.");
  const status = d.status ?? d.state;
  if (!["DELIVERED", "COMPLETED"].includes(status)) throw new HttpsError("failed-precondition", "You can rate after delivery.");
  const allowed = kind === "parcel" ? [d.senderUid, d.receiver?.uid] : [d.buyerUid];
  if (!allowed.includes(uid)) throw new HttpsError("permission-denied", "Not your delivery.");
  const rid = `${kind}_${id}_${uid}`;
  await db.runTransaction(async (tx) => {
    const r = db.doc(`reviews/${rid}`);
    if ((await tx.get(r)).exists) throw new HttpsError("already-exists", "You already rated this delivery.");
    const w = (await tx.get(db.doc(`riderWallets/${d.riderUid}`))).data() ?? {};
    const count = (w.ratingCount ?? 0) + 1, avg = ((w.rating ?? 0) * (count - 1) + stars) / count;
    tx.set(r, { kind, orderId: id, fromUid: uid, toUid: d.riderUid, role: "TO_RIDER", stars, comment: String(req.data?.comment ?? "").slice(0, 300), at: FieldValue.serverTimestamp() });
    tx.set(db.doc(`riderWallets/${d.riderUid}`), { rating: Math.round(avg * 100) / 100, ratingCount: count, ...(count >= 20 && avg < 3 ? { lowRatingFlag: true } : {}) }, { merge: true });
    if (count >= 20 && avg < 3) tx.set(db.doc(`riderFlags/${d.riderUid}`), { riderUid: d.riderUid, reason: "rating_below_3", rating: avg, status: "OPEN", flaggedAt: FieldValue.serverTimestamp() }, { merge: true });
  });
  await notify(d.riderUid, "New rating", `${"★".repeat(stars)}${"☆".repeat(5 - stars)} for ${id}`, "RATING");
  return { ok: true };
});

const ISSUE_TYPES = ["Parcel not delivered", "Parcel damaged", "Wrong item", "Late delivery", "Rider misconduct"];
/** Within 24 h of delivery. The dispute carries the code-verification record so admins can spot "verified but not received". */
export const delivery_reportIssue = onCall(async (req) => {
  const { uid } = requireUser(req);
  const cfg = await loadConfig();
  const kind = req.data?.kind === "order" ? "order" : "parcel";
  const id = str(req.data?.orderId, "order");
  const type = ISSUE_TYPES.includes(req.data?.type) ? req.data.type : "Parcel not delivered";
  const dref = db.doc(`${kind === "parcel" ? "parcels" : "orders"}/${id}`);
  const d = (await dref.get()).data();
  if (!d) throw new HttpsError("not-found", "Not found.");
  const parties = kind === "parcel" ? [d.senderUid, d.receiver?.uid] : [d.buyerUid];
  if (!parties.includes(uid)) throw new HttpsError("permission-denied", "Not your delivery.");
  const at = (d.deliveredAt ?? d.failedAt) as Timestamp | undefined;
  if (at && Date.now() - at.toMillis() > cfg.disputeReportHours * 3_600_000) throw new HttpsError("failed-precondition", `Issues can be reported within ${cfg.disputeReportHours} hours of delivery.`);
  const statusField = kind === "parcel" ? "status" : "state";
  if (!["DELIVERED", "COMPLETED", "DELIVERY_FAILED", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"].includes(d[statusField])) throw new HttpsError("failed-precondition", "You can report an issue once the rider has your item.");
  const attempts = await db.collection("deliveryCodeAttempts").where("orderId", "==", id).orderBy("at", "desc").limit(20).get().catch(() => null);
  const disp = db.collection("disputes").doc();
  await disp.set({ kind, orderId: id, raisedBy: uid, parties: [...parties.filter(Boolean), d.riderUid].filter(Boolean), type, reason: type,
    description: String(req.data?.description ?? "").slice(0, 1000), photoPaths: (req.data?.photoPaths ?? []).slice(0, 4), status: "OPEN",
    verification: d.verification ?? null, codeSummary: d.codeSummary ?? null, deliveryOverride: d.deliveryOverride ?? false,
    codeAttempts: attempts?.docs.map((a) => ({ result: a.get("result"), lat: a.get("lat"), lng: a.get("lng"), at: a.get("at") })) ?? [],
    riderUid: d.riderUid ?? null, createdAt: FieldValue.serverTimestamp() });
  await dref.update({ [statusField]: "DISPUTED", [`timeline.DISPUTED`]: FieldValue.serverTimestamp(), disputeId: disp.id, completeAfter: null });
  await notify(uid, "Issue reported", "Our support team will review it and reply within 24 hours. Rider earnings are not reversed automatically.", "DISPUTE_OPENED");
  return { disputeId: disp.id };
});

/* ------------------------------------------------------------------ public read-only tracking link (§13) */

const esc = (s: unknown) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
const LABEL: Record<string, string> = { PENDING_PAYMENT: "Waiting for payment", AWAITING_RIDER: "Finding a rider", RIDER_ASSIGNED: "Rider on the way to pickup",
  IN_TRANSIT: "On the way", AWAITING_DELIVERY_CODE: "Rider has arrived", DELIVERED: "Delivered", COMPLETED: "Delivered", CANCELLED: "Cancelled",
  DELIVERY_FAILED: "Delivery failed", DISPUTED: "Under review" };

export const parcel_track = onRequest(async (req, res) => {
  const token = String(req.path.split("/").filter(Boolean).pop() ?? "");
  if (!/^[A-Za-z0-9_-]{16}$/.test(token)) { res.status(404).send("Not found"); return; }
  const q = await db.collection("parcels").where("trackingToken", "==", token).limit(1).get();
  const p = q.docs[0]?.data();
  if (!p) { res.status(404).send("Not found"); return; }
  const geo = p.riderGeo && ["RIDER_ASSIGNED", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"].includes(p.status) ? p.riderGeo : null;
  const eta = p.status === "IN_TRANSIT" && p.etaMin ? `About ${p.etaMin} min` : "";
  res.set("Cache-Control", "no-store").set("X-Robots-Tag", "noindex").send(`<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="30"><title>Track parcel ${esc(q.docs[0].id)}</title>
<style>body{font-family:system-ui,sans-serif;margin:0;background:#f5f5f7;color:#1b1b24}header{background:#CC0023;color:#fff;padding:18px 16px}
main{padding:16px;max-width:520px;margin:auto}.card{background:#fff;border-radius:14px;padding:16px;margin-bottom:12px}.s{font-size:22px;font-weight:800}
.m{color:#6b6b78;font-size:14px}a{color:#CC0023;font-weight:700}</style></head><body><header><b>SeloNaChipa</b> · Parcel ${esc(q.docs[0].id)}</header><main>
<div class="card"><div class="s">${esc(LABEL[p.status] ?? p.status)}</div><div class="m">${esc(eta)}</div></div>
<div class="card"><div class="m">From</div><b>${esc(p.pickup?.neighbourhood)}, ${esc(p.cityName)}</b><div class="m" style="margin-top:8px">To</div><b>${esc(p.dropoff?.neighbourhood)}</b></div>
${p.riderName ? `<div class="card"><div class="m">Rider</div><b>${esc(p.riderName)}</b> · ${esc(p.riderPlate)}</div>` : ""}
${geo ? `<div class="card"><a href="https://www.google.com/maps/search/?api=1&query=${geo.lat},${geo.lng}">See the rider's last location</a><div class="m">Updated every 30 seconds</div></div>` : ""}
<p class="m">This page refreshes automatically. The delivery code is only sent to the receiver's phone.</p></main></body></html>`);
});


/* ------------------------------------------------------------------ Open Logistics Network (partner deliveries) */

const trackBase = () => { try { return TRACKING_BASE_URL.value(); } catch { return "https://mabala-f2d65.web.app/t/"; } };

export interface PartnerDeliveryInput {
  partnerId: string; partnerName: string; env: "sandbox" | "production"; externalOrderRef: string; pickup: any; dropoff: any; weightBand: string;
  description: string; receiver: { name: string; msisdn: string }; instructions?: string; express?: boolean; declaredValueNgwee?: number;
  pricing?: import("./open/model").PartnerPricing;
}
/** Partner delivery price (no Lipila: charged to the partner's ledger). Same distance engine as app parcels. */
export async function partnerQuote(pickupIn: any, dropoffIn: any, express: boolean, pricing: import("./open/model").PartnerPricing | undefined) {
  const cfg = await loadConfig();
  const pickup = stop(pickupIn, "pickup"), dropoff = stop(dropoffIn, "dropoff");
  const q = await quote(pickup, dropoff, 0, cfg);
  const { partnerDeliveryPrice } = await import("./open/model");
  const deliveryNgwee = partnerDeliveryPrice(q.deliveryNgwee, q.distanceKm, pricing, express);
  // the rider's share follows the delivery price; the sending fee is waived for API deliveries (priced in the partner tariff)
  const ratio = q.deliveryNgwee > 0 ? deliveryNgwee / q.deliveryNgwee : 1;
  return { pickup, dropoff, cfg, q, deliveryNgwee, riderEarningsNgwee: Math.round(q.riderEarningsNgwee * ratio), platformFeeNgwee: Math.round(q.platformFeeNgwee * ratio),
    socialFundNgwee: Math.round(q.socialFundNgwee * ratio) };
}

/**
 * Creates a delivery job for a partner inside the existing parcels engine: same rider network, smart dispatch, pickup
 * PIN, receiver delivery code, live tracking and proof of delivery. Idempotent per (partner, env, externalOrderRef).
 */
export async function createPartnerParcel(d: PartnerDeliveryInput): Promise<{ id: string; duplicate: boolean; pin: string | null; trackingUrl: string }> {
  const id = `PCL-X${createHash("sha256").update(`${d.partnerId}|${d.env}|${d.externalOrderRef}`).digest("hex").slice(0, 8).toUpperCase()}`;
  const existing = (await ref(id).get()).data();
  if (existing) return { id, duplicate: true, pin: null, trackingUrl: `${trackBase()}${existing.trackingToken}` };
  if (!weightOk(d.weightBand)) throw new HttpsError("invalid-argument", "weightBand must be one of: Under 1 kg, 1–2 kg, 2–3 kg, 3–4 kg, 4–5 kg.");
  const receiverMsisdn = normaliseMsisdn(d.receiver?.msisdn);
  if (!receiverMsisdn) throw new HttpsError("invalid-argument", "receiver.msisdn must be an Airtel, MTN or Zamtel number — the delivery code is sent to it.");
  const pq = await partnerQuote(d.pickup, d.dropoff, !!d.express, d.pricing);
  const { q, cfg } = pq;
  const pin = generatePickupPin();
  const trackingToken = randomBytes(12).toString("base64url");
  const senderUid = `partner:${d.partnerId}`;
  await db.runTransaction(async (tx) => {
    tx.create(ref(id), {
      senderUid, senderRole: "PARTNER", senderName: d.partnerName, senderMsisdn: null, cityId: q.cityId, cityName: q.cityName,
      partnerId: d.partnerId, env: d.env, source: { platform: d.partnerName, partnerId: d.partnerId, externalOrderRef: d.externalOrderRef }, priority: d.express ? "EXPRESS" : "STANDARD",
      pickup: pq.pickup, dropoff: pq.dropoff, weightBand: d.weightBand, description: String(d.description ?? "").slice(0, 200), category: suggestCategory(String(d.description ?? "")),
      handlingNotes: "", receiver: { name: String(d.receiver?.name ?? "").slice(0, 60), msisdn: receiverMsisdn, uid: null },
      deliveryInstructions: String(d.instructions ?? "").slice(0, 300), distanceKm: q.distanceKm, distanceApproximate: q.approximate, etaMin: q.etaMin,
      declaredValueNgwee: 0, creditAppliedNgwee: 0,
      fees: { deliveryNgwee: pq.deliveryNgwee, sendingNgwee: 0, insuranceNgwee: 0, totalNgwee: pq.deliveryNgwee, riderEarningsNgwee: pq.riderEarningsNgwee,
        platformFeeNgwee: pq.platformFeeNgwee, socialFundNgwee: pq.socialFundNgwee,
        rates: { base: cfg.parcelBaseFee, perKm: cfg.parcelPerKmRate, sendingPct: 0, platformPct: cfg.parcelPlatformFeePct, fundPct: cfg.parcelSocialFundPct, partnerTariff: !!d.pricing } },
      status: "AWAITING_RIDER", paymentStatus: "PARTNER_ACCOUNT", escrowStatus: "HELD", paidAt: FieldValue.serverTimestamp(), walletMsisdn: null, riderUid: null,
      notifiedRiders: [], broadcastRound: d.express ? 2 : 0, scheduledFor: null, trackingToken, codeSummary: null,
      timeline: { AWAITING_RIDER: FieldValue.serverTimestamp() }, createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(),
    });
    tx.set(db.doc(`parcels/${id}/private/pickup`), { senderUid, pin, attempts: 0 });
    tx.set(db.collection(`parcels/${id}/timeline`).doc(), { event: "CREATED", by: `partner:${d.partnerId}`, at: FieldValue.serverTimestamp() });
    writeLedger(tx, { kind: "parcel", orderId: id, type: "HELD", leg: "delivery_fee", party: "escrow", amountNgwee: pq.deliveryNgwee, ref: `partner:${d.partnerId}` });
  });
  const { post } = await import("./open/settlement");
  await post(d.partnerId, { type: "DELIVERY_CHARGE", amountNgwee: pq.deliveryNgwee, refType: "delivery", refId: id, env: d.env });
  await broadcastParcel(id); // no-op in sandbox
  return { id, duplicate: false, pin, trackingUrl: `${trackBase()}${trackingToken}` };
}

/** Sandbox: deliveries move through the real states every minute with a test rider, so partners can test webhooks end to end. */
export const scheduled_sandboxDeliveries = onSchedule({ schedule: "every 1 minutes" }, async () => {
  const next: Record<string, ParcelStatus> = { AWAITING_RIDER: "RIDER_ASSIGNED", RIDER_ASSIGNED: "IN_TRANSIT", IN_TRANSIT: "AWAITING_DELIVERY_CODE", AWAITING_DELIVERY_CODE: "COMPLETED" };
  const q = await db.collection("parcels").where("env", "==", "sandbox").where("status", "in", Object.keys(next)).limit(100).get();
  for (const d of q.docs) {
    const from = d.get("status") as ParcelStatus, to = next[from];
    await d.ref.update({ status: to, [`timeline.${to}`]: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(),
      ...(to === "RIDER_ASSIGNED" ? { riderUid: "sandbox-rider", riderName: "Test Rider", riderPlate: "SBX 0001" } : {}),
      ...(to === "COMPLETED" ? { escrowStatus: "RELEASED", deliveredAt: FieldValue.serverTimestamp() } : {}) });
  }
});
