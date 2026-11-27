/**
 * Open Logistics Network + partner account endpoints.
 * Deliveries are jobs in the existing parcels engine (createPartnerParcel): same riders, smart dispatch, pickup PIN,
 * receiver delivery code, live tracking, proof of delivery and failed-delivery handling. Partners see only their own
 * deliveries and only the rider details needed for hand-over (first name, plate) while the job is active.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { db } from "../config";
import { cityOf } from "../parcel-rules";
import { encrypt, newWebhookSecret } from "./crypto";
import { ApiError, ok, route } from "./gateway";
import { EVENTS, OPEN_API_ENC_KEY, riderForPartner, secretValue } from "./model";
import { summarise } from "./settlement";
import { replay } from "./webhooks";

const wrap = async <T>(f: () => Promise<T>): Promise<T> => {
  try { return await f(); } catch (e) {
    if (e instanceof HttpsError) throw new ApiError(e.code === "not-found" ? 404 : e.code === "resource-exhausted" ? 429 : 422, e.code.replace(/-/g, "_"), e.message);
    throw e;
  }
};
const iso = (t: unknown) => (t as Timestamp | undefined)?.toDate?.().toISOString() ?? null;

export function deliveryView(id: string, p: Record<string, any>) {
  const active = ["RIDER_ASSIGNED", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"].includes(p.status);
  return { deliveryId: id, externalOrderRef: p.source?.externalOrderRef ?? null, status: p.status, priority: p.priority ?? "STANDARD",
    pickup: { address: p.pickup?.address, area: p.pickup?.neighbourhood, lat: p.pickup?.lat, lng: p.pickup?.lng },
    dropoff: { address: p.dropoff?.address, area: p.dropoff?.neighbourhood, lat: p.dropoff?.lat, lng: p.dropoff?.lng },
    distanceKm: p.distanceKm, etaMin: p.etaMin, priceNgwee: p.fees?.totalNgwee, currency: "ZMW",
    rider: riderForPartner(p, active), proofOfDelivery: p.status === "COMPLETED" || p.status === "DELIVERED" ? { method: "receiver_code", at: iso(p.deliveredAt ?? p.timeline?.COMPLETED) } : null,
    timeline: Object.fromEntries(Object.entries(p.timeline ?? {}).map(([k, v]) => [k, iso(v)])), environment: p.env ?? "production" };
}

/* ---------------------------------------------------------------- quotes & coverage */
route("POST", "/delivery/quotes", "delivery:quote", async (c) => wrap(async () => {
  const { partnerQuote } = await import("../parcels");
  const q = await partnerQuote(c.body?.pickup, c.body?.dropoff, c.body?.priority === "EXPRESS", c.partner.pricing);
  return { priceNgwee: q.deliveryNgwee, currency: "ZMW", distanceKm: q.q.distanceKm, etaMin: q.q.etaMin, city: q.q.cityName, approximate: q.q.approximate,
    priority: c.body?.priority === "EXPRESS" ? "EXPRESS" : "STANDARD", validForMin: 15 };
}), { idempotent: false });
route("GET", "/coverage", "coverage:read", async () => {
  const { CITIES } = await import("../parcel-rules") as any;
  const online = await db.collection("riderPresence").where("isOnline", "==", true).limit(1000).get();
  const byCity: Record<string, number> = {};
  online.docs.forEach((d) => { const g = d.get("geo"); const city = g ? cityOf(g) : null; if (city) byCity[city.id] = (byCity[city.id] ?? 0) + 1; });
  return { cities: (CITIES ?? []).map((ct: any) => ({ cityId: ct.id, name: ct.name, neighbourhoods: ct.neighbourhoods, onlineRiders: byCity[ct.id] ?? 0 })),
    rules: { maxWeight: "5 kg (weight bands: Under 1 kg … 4–5 kg)", sameCityOnly: true } };
});
route("GET", "/riders/availability", "coverage:read", async (c) => {
  const lat = Number(c.query.lat), lng = Number(c.query.lng);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) throw new ApiError(422, "validation_failed", "lat and lng query parameters are required.");
  const city = cityOf({ lat, lng });
  if (!city) return { served: false, onlineRiders: 0 };
  const online = await db.collection("riderPresence").where("isOnline", "==", true).limit(1000).get();
  const near = online.docs.filter((d) => { const g = d.get("geo"); if (!g) return false; const dx = (g.lat - lat) * 111, dy = (g.lng - lng) * 111 * Math.cos(lat * Math.PI / 180); return Math.hypot(dx, dy) <= 5; });
  return { served: true, city: city.name, onlineRiders: near.length, estimatedPickupMin: near.length ? 10 : null }; // counts only, never rider identities
});

/* ---------------------------------------------------------------- deliveries */
route("POST", "/deliveries", "delivery:write", async (c) => wrap(async () => {
  const b = c.body ?? {};
  if (!String(b.externalOrderRef ?? "").trim()) throw new ApiError(422, "validation_failed", "externalOrderRef is required (your order or shipment id).");
  const { createPartnerParcel } = await import("../parcels");
  const r = await createPartnerParcel({ partnerId: c.pid, partnerName: c.partner.name, env: c.env, externalOrderRef: String(b.externalOrderRef).slice(0, 100),
    pickup: b.pickup, dropoff: b.dropoff, weightBand: b.weightBand, description: b.description, receiver: b.receiver, instructions: b.instructions,
    express: b.priority === "EXPRESS", pricing: c.partner.pricing });
  const p = (await db.doc(`parcels/${r.id}`).get()).data()!;
  if (!r.duplicate) { const { emit } = await import("./webhooks"); await emit(c.pid, c.env, "delivery.created", r.id, { delivery: deliveryView(r.id, p) }); }
  // The pickup PIN is shown once: give it to whoever hands the goods to the rider.
  return ok({ delivery: deliveryView(r.id, p), pickupPin: r.pin, trackingUrl: r.trackingUrl, duplicate: r.duplicate }, r.duplicate ? 200 : 201);
}));
route("GET", "/deliveries", "delivery:read", async (c) => {
  const q = await db.collection("parcels").where("partnerId", "==", c.pid).where("env", "==", c.env).orderBy("createdAt", "desc").limit(Math.min(200, Number(c.query.limit) || 50)).get();
  return { deliveries: q.docs.map((d) => deliveryView(d.id, d.data())) };
});
route("GET", "/deliveries/:id", "delivery:read", async (c) => {
  const p = (await db.doc(`parcels/${c.params.id}`).get()).data();
  if (!p || p.partnerId !== c.pid) throw new ApiError(404, "not_found", "Delivery not found.");
  const live = ["RIDER_ASSIGNED", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"].includes(p.status) && p.riderUid && p.env !== "sandbox"
    ? (await db.doc(`riderLocations/${p.riderUid}`).get()).data() : null;
  return { delivery: { ...deliveryView(c.params.id, p), riderLocation: live ? { lat: live.lat, lng: live.lng, at: iso(live.at) } : null } };
});
route("POST", "/deliveries/:id/cancel", "delivery:write", async (c) => wrap(async () => {
  const p = (await db.doc(`parcels/${c.params.id}`).get()).data();
  if (!p || p.partnerId !== c.pid) throw new ApiError(404, "not_found", "Delivery not found.");
  if (!["AWAITING_RIDER", "RIDER_ASSIGNED"].includes(p.status)) throw new ApiError(409, "conflict", `A ${p.status} delivery can't be cancelled. Contact support.`);
  const { refundParcel } = await import("../parcels");
  await refundParcel(c.params.id, String(c.body?.reason ?? "Cancelled by the platform").slice(0, 200), `partner:${c.pid}`);
  return { delivery: deliveryView(c.params.id, (await db.doc(`parcels/${c.params.id}`).get()).data()!) };
}));

/* ---------------------------------------------------------------- partner account */
routesForAccount();
function routesForAccount() {
  route("GET", "/me", "settlements:read", async (c) => ({ partnerId: c.pid, name: c.partner.name, environment: c.env, services: c.partner.services, scopes: c.scopes,
    rateLimitPerMin: c.partner.rateLimitPerMin }));
  route("GET", "/webhooks", "webhooks:manage", async (c) => ({ url: c.partner.webhook?.url ?? null, events: c.partner.webhook?.events ?? [], availableEvents: EVENTS }));
  route("PUT", "/webhooks", "webhooks:manage", async (c) => {
    const url = c.body?.url ? String(c.body.url) : null;
    if (url && !/^https:\/\/[^\s]+$/.test(url)) throw new ApiError(422, "validation_failed", "url must be https://");
    const events = (Array.isArray(c.body?.events) ? c.body.events : []).filter((e: string) => (EVENTS as readonly string[]).includes(e));
    const fresh = !c.partner.webhook?.secretEnc || c.body?.rotateSecret === true;
    const secret = fresh ? newWebhookSecret() : null;
    await db.doc(`openPartners/${c.pid}`).update({ webhook: { url, events, secretEnc: fresh ? encrypt(secret!, secretValue(OPEN_API_ENC_KEY, "OPEN_API_ENC_KEY")) : c.partner.webhook!.secretEnc },
      updatedAt: FieldValue.serverTimestamp() });
    return { url, events, ...(secret ? { signingSecret: secret, note: "Store this secret now: it is shown only once." } : {}) };
  });
  route("GET", "/webhooks/events", "webhooks:manage", async (c) => {
    const q = await db.collection("openEvents").where("pid", "==", c.pid).where("env", "==", c.env).orderBy("createdAt", "desc").limit(Math.min(200, Number(c.query.limit) || 50)).get();
    return { events: q.docs.map((d) => ({ id: d.id, type: d.get("type"), status: d.get("status"), attempts: d.get("attempts"), lastStatus: d.get("lastStatus") ?? null,
      lastError: d.get("lastError") ?? null, createdAt: iso(d.get("createdAt")) })) };
  });
  route("POST", "/webhooks/events/:id/replay", "webhooks:manage", async (c) => {
    const r = await replay(c.params.id, c.pid); if (!r) throw new ApiError(404, "not_found", "Event not found."); return { id: c.params.id, status: r };
  });
  route("POST", "/webhooks/test", "webhooks:manage", async (c) => {
    const { emit } = await import("./webhooks");
    const id = await emit(c.pid, c.env, "delivery.created", `test-${Date.now()}`, { test: true, message: "Test event from SeloNachipa" });
    return { id };
  });
  route("GET", "/settlements", "settlements:read", async (c) => {
    const q = await db.collection(`openLedger/${c.pid}/entries`).where("env", "==", c.env).orderBy("at", "desc").limit(500).get();
    const entries = q.docs.map((d) => ({ id: d.id, type: d.get("type"), amountNgwee: d.get("amountNgwee"), signedNgwee: d.get("signedNgwee"), refType: d.get("refType"),
      refId: d.get("refId"), note: d.get("note") ?? null, at: iso(d.get("at")) }));
    return { currency: "ZMW", ...summarise(entries), entries };
  });
  /** Sandbox only: simulate a SeloNachipa buyer paying for one of your sandbox products (drives order.* webhooks). */
  route("POST", "/sandbox/orders", "orders:read", async (c) => {
    if (c.env !== "sandbox") throw new ApiError(403, "sandbox_only", "Only available with sandbox credentials.");
    const { listingIdFor, onPartnerOrderPaid } = await import("./commerce");
    const id = listingIdFor(c.pid, "sandbox", String(c.body?.externalProductId ?? ""));
    const l = (await db.doc(`listings/${id}`).get()).data();
    if (!l || l.origin?.partnerId !== c.pid) throw new ApiError(404, "not_found", "Publish the sandbox product first (POST /products).");
    const qty = Math.max(1, Math.floor(Number(c.body?.qty) || 1));
    const orderId = `SBX-${Date.now().toString(36).toUpperCase()}`;
    await db.doc(`orders/${orderId}`).set({ buyerUid: "sandbox-buyer", buyerName: "Test Buyer", sellerUid: l.sellerUid, sellerName: l.sellerName, partnerId: c.pid, partnerEnv: "sandbox",
      items: [{ listingId: id, title: l.title, qty, unitNgwee: l.priceNgwee, lineNgwee: qty * l.priceNgwee, externalProductId: l.origin.productId, externalSellerId: l.origin.sellerId }],
      goodsNgwee: qty * l.priceNgwee, totalNgwee: qty * l.priceNgwee, dropoff: "Test drop-off, Kabwata, Lusaka", state: "ESCROW_LOCKED", paymentStatus: "PAID",
      timeline: { ESCROW_LOCKED: FieldValue.serverTimestamp() }, createdAt: FieldValue.serverTimestamp() });
    const r = await onPartnerOrderPaid(orderId);
    return ok({ orderId, result: r }, 201);
  });
}
