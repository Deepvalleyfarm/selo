/**
 * Open Commerce Network. External products become ordinary SeloNachipa listings (same moderation, discovery, cart,
 * checkout, escrow and rider delivery) that keep their origin: platform, seller, product id, last sync. The originating
 * platform stays authoritative for its product and stock; SeloNachipa only holds reservations against that stock.
 *
 * Marketplace orders:
 *  - A SeloNachipa buyer pays for a partner product → stock is reserved → order.created webhook → the partner accepts
 *    (rider dispatch starts) or rejects (buyer refunded). No answer within the window → auto-reject + refund.
 *  - An approved marketplace partner can place an order for a product shared with it (POST /orders) → same reserve /
 *    accept / reject cycle between the two platforms; settlement through the partner ledgers.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import type { Transaction } from "firebase-admin/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { db } from "../config";
import { compositeKey } from "../algorithm";
import { sha256 } from "./crypto";
import { ApiError, type Ctx, ok, route } from "./gateway";
import { commissionOn, type Env, OPEN_SECRETS, partnerSellerUid } from "./model";
import { emit } from "./webhooks";
import { post } from "./settlement";

const ACCEPT_WINDOW_MIN = 30;
const CATEGORIES = ["Fresh produce", "Groceries", "Fast food", "Electronics", "Phones & accessories", "Fashion", "Beauty", "Home", "Furniture", "Hardware",
  "Building materials", "Agriculture", "Baby", "Vehicles & parts", "Services", "Other"];

export const listingIdFor = (pid: string, env: Env, externalProductId: string) => `ext_${env === "sandbox" ? "sbx_" : ""}${sha256(`${pid}|${externalProductId}`).slice(0, 20)}`;
const str = (v: unknown, max: number) => String(v ?? "").trim().slice(0, max);
const isLL = (v: any) => v && Number.isFinite(Number(v.lat)) && Number.isFinite(Number(v.lng));

export interface ProductInput {
  externalProductId: string; externalSellerId: string; sellerName: string; title: string; description?: string; category?: string; images?: string[];
  priceNgwee: number; currency?: string; stock: number; location?: { lat: number; lng: number; address?: string; district?: string; province?: string };
  deliveryOptions?: { selonachipaDelivery?: boolean; pickup?: boolean }; sharing?: { selonachipaMarketplace?: boolean; partners?: string[] };
  status?: "published" | "draft"; tags?: string[];
}
/** Validation shared by create and update (returns problems, empty = OK). */
export function productProblems(p: Partial<ProductInput>, partial = false): string[] {
  const e: string[] = [];
  const need = (k: keyof ProductInput) => !partial || p[k] !== undefined;
  if (need("externalProductId") && !/^[\w.:-]{1,100}$/.test(String(p.externalProductId ?? ""))) e.push("externalProductId: 1–100 letters, digits, . _ : -");
  if (need("externalSellerId") && !/^[\w.:-]{1,100}$/.test(String(p.externalSellerId ?? ""))) e.push("externalSellerId: 1–100 letters, digits, . _ : -");
  if (need("title") && str(p.title, 200).length < 3) e.push("title: at least 3 characters");
  if (need("priceNgwee") && !(Number.isInteger(p.priceNgwee) && (p.priceNgwee as number) > 0)) e.push("priceNgwee: positive integer (K 1.00 = 100)");
  if (p.currency && p.currency !== "ZMW") e.push("currency: only ZMW is supported");
  if (need("stock") && !(Number.isInteger(p.stock) && (p.stock as number) >= 0)) e.push("stock: integer ≥ 0");
  if (p.images && (!Array.isArray(p.images) || p.images.length > 10 || p.images.some((u) => !/^https:\/\//.test(String(u))))) e.push("images: up to 10 https URLs");
  if (p.location && !isLL(p.location)) e.push("location: lat and lng required");
  if (p.category && !CATEGORIES.includes(p.category)) e.push(`category: one of ${CATEGORIES.join(", ")}`);
  return e;
}

/** What other platforms (and the originating one) see of a listing. */
export function publicProduct(id: string, x: Record<string, any>) {
  return { listingId: id, externalProductId: x.origin?.productId ?? null, originPlatform: x.origin?.partnerName ?? "SeloNachipa", title: x.title, description: x.description,
    category: x.category, images: x.photoUrls ?? [], priceNgwee: x.priceNgwee, currency: "ZMW", availableStock: x.stock ?? 0, status: x.status,
    location: x.pickupGeo ? { lat: x.pickupGeo.lat, lng: x.pickupGeo.lng, district: x.district ?? null } : null, deliveryOptions: x.deliveryOptions ?? null,
    lastSyncedAt: (x.origin?.syncedAt as Timestamp | undefined)?.toDate().toISOString() ?? null };
}

async function heldQty(listingId: string) {
  const q = await db.collection("openReservations").where("listingId", "==", listingId).where("status", "==", "HELD").get();
  return q.docs.reduce((s, d) => s + (d.get("qty") ?? 0), 0);
}

/* ---------------------------------------------------------------- products */
async function upsertProduct(c: Ctx, input: ProductInput, create: boolean) {
  const problems = productProblems(input, !create);
  if (problems.length) throw new ApiError(422, "validation_failed", "Some fields are invalid.", problems);
  const id = listingIdFor(c.pid, c.env, input.externalProductId);
  const ref = db.doc(`listings/${id}`);
  const cur = (await ref.get()).data();
  if (!create && !cur) throw new ApiError(404, "not_found", "No product with that externalProductId. Create it with POST /products.");
  if (cur && cur.origin?.partnerId !== c.pid) throw new ApiError(404, "not_found", "Product not found.");
  // sharing: only with ACTIVE partners that have the commerce service (silently drops unknown ids)
  let partners: string[] | undefined;
  if (input.sharing?.partners) {
    const ids = [...new Set(input.sharing.partners.map(String))].filter((p) => p !== c.pid).slice(0, 50);
    const snaps = ids.length ? await db.getAll(...ids.map((p) => db.doc(`openPartners/${p}`))) : [];
    partners = snaps.filter((s) => s.exists && s.get("status") === "ACTIVE" && s.get("services.commerce") === true).map((s) => s.id);
  }
  const held = cur ? await heldQty(id) : 0;
  const sellerUid = partnerSellerUid(c.pid, input.externalSellerId ?? cur?.origin?.sellerId);
  const loc = input.location ?? (cur?.pickupGeo ? { ...cur.pickupGeo } : null);
  const publish = (input.status ?? (cur ? (cur.status === "archived" || cur.status === "paused" ? "draft" : "published") : "published")) === "published";
  const marketplace = input.sharing?.selonachipaMarketplace ?? cur?.sharing?.selonachipaMarketplace ?? true;
  const status = c.env === "sandbox" ? "sandbox" : !publish || !marketplace ? "paused" : cur?.status === "live" ? "live" : "pending_review";
  const data: Record<string, unknown> = {
    sellerUid, sellerName: str(input.sellerName ?? cur?.sellerName, 80) || c.partner.name, sellerVerified: true, agentUid: null,
    title: str(input.title ?? cur?.title, 80), description: str(input.description ?? cur?.description, 1200),
    priceNgwee: input.priceNgwee ?? cur?.priceNgwee, discountPct: 0, discountNgwee: 0,
    category: input.category ?? cur?.category ?? "Agriculture", condition: "New", availability: "In Stock",
    province: str(input.location?.province ?? cur?.province, 40), district: str(input.location?.district ?? cur?.district, 60), location: str(input.location?.address ?? cur?.location, 120),
    tags: (input.tags ?? cur?.tags ?? []).slice(0, 8).map((t: string) => String(t).toLowerCase().slice(0, 30)),
    mediaType: "photo", photoUrls: input.images ?? cur?.photoUrls ?? [], thumbUrl: (input.images ?? cur?.photoUrls ?? [])[0] ?? null, videoUrl: null,
    stock: Math.max(0, (input.stock ?? (cur ? (cur.stock ?? 0) + held : 0)) - held),
    pickupGeo: loc ? { lat: Number(loc.lat), lng: Number(loc.lng) } : null,
    deliveryOptions: { selonachipaDelivery: input.deliveryOptions?.selonachipaDelivery ?? cur?.deliveryOptions?.selonachipaDelivery ?? true, pickup: input.deliveryOptions?.pickup ?? cur?.deliveryOptions?.pickup ?? false },
    sharing: { selonachipaMarketplace: marketplace, partners: partners ?? cur?.sharing?.partners ?? [] },
    origin: { partnerId: c.pid, partnerName: c.partner.name, sellerId: input.externalSellerId ?? cur?.origin?.sellerId, productId: input.externalProductId ?? cur?.origin?.productId,
      env: c.env, syncedAt: FieldValue.serverTimestamp(), version: (cur?.origin?.version ?? 0) + 1 },
    status, updatedAt: FieldValue.serverTimestamp(),
  };
  if (!cur) Object.assign(data, { shard: compositeKey("lst", sellerUid).shard, aiUsed: false, aiSafe: true, durationMs: null, createdAt: FieldValue.serverTimestamp(), publishedAt: null,
    stats: { views: 0, likes: 0, shares: 0, enquiries: 0, saves: 0, notInterested: 0, watchMs: 0, watchTotalMs: 0, taps: 0, skips: 0 }, sellerEngagementRate: 0, viralityScore: 0 });
  await ref.set(data, { merge: true });
  const version = (cur?.origin?.version ?? 0) + 1;
  const snapshot = publicProduct(id, { ...cur, ...data, origin: { ...(data.origin as object), syncedAt: Timestamp.now() } });
  const share = (data.sharing as { partners: string[] }).partners;
  const type = !cur ? "product.created" : (input.stock !== undefined && Object.keys(input).length <= 2 ? "product.stock_updated" : "product.updated");
  await Promise.all(share.map((p) => emit(p, c.env, type as any, id, { product: snapshot }, version)));
  return ok({ product: snapshot, created: !cur }, cur ? 200 : 201);
}

route("POST", "/products", "products:write", (c) => upsertProduct(c, c.body as ProductInput, true));
route("PATCH", "/products/:externalId", "products:write", (c) => upsertProduct(c, { ...(c.body as ProductInput), externalProductId: c.params.externalId }, false));
route("POST", "/products/:externalId/stock", "inventory:write", async (c) => {
  if (!Number.isInteger(c.body?.stock) || c.body.stock < 0) throw new ApiError(422, "validation_failed", "stock: integer ≥ 0");
  return upsertProduct(c, { externalProductId: c.params.externalId, stock: c.body.stock } as ProductInput, false);
});
async function setStatus(c: Ctx, status: "paused" | "archived") {
  const id = listingIdFor(c.pid, c.env, c.params.externalId), ref = db.doc(`listings/${id}`), cur = (await ref.get()).data();
  if (!cur || cur.origin?.partnerId !== c.pid) throw new ApiError(404, "not_found", "Product not found.");
  await ref.update({ status, updatedAt: FieldValue.serverTimestamp(), "origin.syncedAt": FieldValue.serverTimestamp() });
  await Promise.all((cur.sharing?.partners ?? []).map((p: string) => emit(p, c.env, "product.unpublished", id, { listingId: id, status }, `${status}-${Date.now()}`)));
  return { listingId: id, status };
}
route("POST", "/products/:externalId/unpublish", "products:write", (c) => setStatus(c, "paused"));
route("DELETE", "/products/:externalId", "products:write", (c) => setStatus(c, "archived"));
route("GET", "/products", "products:read", async (c) => {
  const q = await db.collection("listings").where("origin.partnerId", "==", c.pid).where("origin.env", "==", c.env).limit(Math.min(200, Number(c.query.limit) || 100)).get();
  return { products: q.docs.map((d) => publicProduct(d.id, d.data())) };
});
route("GET", "/products/:externalId", "products:read", async (c) => {
  const id = listingIdFor(c.pid, c.env, c.params.externalId), d = (await db.doc(`listings/${id}`).get()).data();
  if (!d || d.origin?.partnerId !== c.pid) throw new ApiError(404, "not_found", "Product not found.");
  return { product: publicProduct(id, d) };
});

/* ---------------------------------------------------------------- catalog (shared with me) */
route("GET", "/catalog", "catalog:read", async (c) => {
  const q = await db.collection("listings").where("sharing.partners", "array-contains", c.pid).where("status", "in", c.env === "sandbox" ? ["sandbox"] : ["live"])
    .limit(Math.min(200, Number(c.query.limit) || 100)).get();
  return { products: q.docs.map((d) => publicProduct(d.id, d.data())) };
});
route("GET", "/catalog/:listingId", "catalog:read", async (c) => {
  const d = (await db.doc(`listings/${c.params.listingId}`).get()).data();
  if (!d || !(d.sharing?.partners ?? []).includes(c.pid)) throw new ApiError(404, "not_found", "Product not found or not shared with you.");
  return { product: publicProduct(c.params.listingId, d) };
});

/* ---------------------------------------------------------------- reservations */
/** Reserve stock inside a transaction (all reads first). Throws if not enough is available. */
export async function reserveInTx(tx: Transaction, items: { listingId: string; qty: number }[], holder: { type: "order" | "partnerOrder"; id: string }, minutes = ACCEPT_WINDOW_MIN) {
  const refs = items.map((i) => db.doc(`listings/${i.listingId}`));
  const snaps = await Promise.all(refs.map((r) => tx.get(r)));
  snaps.forEach((s, k) => { if (!s.exists || (s.get("stock") ?? 0) < items[k].qty) throw new ApiError(409, "insufficient_stock", `Not enough stock for ${s.get("title") ?? items[k].listingId}.`); });
  snaps.forEach((s, k) => {
    tx.update(refs[k], { stock: FieldValue.increment(-items[k].qty) });
    tx.set(db.doc(`openReservations/${holder.type}_${holder.id}_${items[k].listingId}`), { listingId: items[k].listingId, qty: items[k].qty, holderType: holder.type, holderId: holder.id,
      partnerId: s.get("origin.partnerId") ?? null, status: "HELD", expiresAt: Timestamp.fromMillis(Date.now() + minutes * 60_000), createdAt: FieldValue.serverTimestamp() });
  });
}
/** CONFIRMED keeps the stock taken; RELEASED gives it back. Idempotent. */
export async function settleReservations(holderType: "order" | "partnerOrder", holderId: string, to: "CONFIRMED" | "RELEASED") {
  const q = await db.collection("openReservations").where("holderType", "==", holderType).where("holderId", "==", holderId).where("status", "==", "HELD").get();
  await Promise.all(q.docs.map((d) => db.runTransaction(async (tx) => {
    const r = (await tx.get(d.ref)).data(); if (r?.status !== "HELD") return;
    tx.update(d.ref, { status: to, settledAt: FieldValue.serverTimestamp() });
    if (to === "RELEASED") tx.update(db.doc(`listings/${r.listingId}`), { stock: FieldValue.increment(r.qty) });
  })));
}

/* ---------------------------------------------------------------- SeloNachipa buyer orders for partner products */
/** Called by the escrow engine once a buyer's payment for a partner product is confirmed. */
export async function onPartnerOrderPaid(orderId: string): Promise<"awaiting_partner" | "sold_out"> {
  const ref = db.doc(`orders/${orderId}`);
  const o = (await ref.get()).data()!;
  try {
    await db.runTransaction(async (tx) => {
      await reserveInTx(tx, o.items.map((i: { listingId: string; qty: number }) => ({ listingId: i.listingId, qty: i.qty })), { type: "order", id: orderId });
      tx.update(ref, { partnerAcceptance: "PENDING", partnerAcceptDeadline: Timestamp.fromMillis(Date.now() + ACCEPT_WINDOW_MIN * 60_000) });
    });
  } catch (e) { if (e instanceof ApiError) return "sold_out"; throw e; }
  await emit(o.partnerId, o.partnerEnv ?? "production", "order.created", orderId, { order: await partnerOrderView(orderId) });
  return "awaiting_partner";
}

export async function partnerOrderView(orderId: string) {
  const o = (await db.doc(`orders/${orderId}`).get()).data() ?? {};
  const needsDelivery = true;
  return { orderId, source: "selonachipa_marketplace", status: o.state, partnerAcceptance: o.partnerAcceptance ?? null,
    acceptBy: (o.partnerAcceptDeadline as Timestamp | undefined)?.toDate().toISOString() ?? null,
    items: (o.items ?? []).map((i: any) => ({ listingId: i.listingId, externalProductId: i.externalProductId ?? null, externalSellerId: i.externalSellerId ?? null, title: i.title,
      qty: i.qty, unitNgwee: i.unitNgwee, lineNgwee: i.lineNgwee ?? i.qty * i.unitNgwee })),
    goodsNgwee: o.goodsNgwee, currency: "ZMW", commissionNgwee: o.partnerCommissionNgwee ?? null,
    // data minimisation: buyer's first name and drop-off area only; phone never leaves SeloNachipa (riders handle contact)
    buyer: { firstName: String(o.buyerName ?? "").split(" ")[0] || null }, delivery: needsDelivery ? { by: "selonachipa_rider", dropoffArea: String(o.dropoff ?? "").split(",").slice(-2).join(",").trim() } : null,
    rider: o.riderName && ["RIDER_ASSIGNED", "PICKED_UP", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"].includes(o.state) ? { firstName: String(o.riderName).split(" ")[0] } : null };
}

async function answerOrder(c: Ctx, accept: boolean) {
  const id = c.params.orderId;
  if (id.startsWith("PO-")) return answerPartnerOrder(c, accept);
  const ref = db.doc(`orders/${id}`), o = (await ref.get()).data();
  if (!o || o.partnerId !== c.pid) throw new ApiError(404, "not_found", "Order not found.");
  if (o.partnerAcceptance !== "PENDING") return ok({ order: await partnerOrderView(id), note: `Already ${String(o.partnerAcceptance ?? o.state).toLowerCase()}.` });
  if (accept) {
    await db.runTransaction(async (tx) => {
      const fresh = (await tx.get(ref)).data();
      if (fresh?.partnerAcceptance !== "PENDING" || fresh.state !== "ESCROW_LOCKED") throw new ApiError(409, "conflict", "The order changed. Fetch it again.");
      tx.update(ref, { partnerAcceptance: "ACCEPTED", partnerAcceptedAt: FieldValue.serverTimestamp(), partnerOrderRef: str(c.body?.partnerOrderRef, 80) || null,
        state: "BROADCASTING", "timeline.BROADCASTING": FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() });
    });
    await settleReservations("order", id, "CONFIRMED");
    const { broadcast } = await import("../escrow");
    await broadcast(id);
    await emit(c.pid, c.env, "order.accepted", id, { order: await partnerOrderView(id) });
  } else {
    await ref.update({ partnerAcceptance: "REJECTED", partnerRejectReason: str(c.body?.reason, 200) || "Seller couldn't fulfil" });
    await settleReservations("order", id, "RELEASED");
    const { refund } = await import("../escrow");
    await refund(id, "The seller couldn't fulfil this order");
    await emit(c.pid, c.env, "order.rejected", id, { order: await partnerOrderView(id) });
  }
  return { order: await partnerOrderView(id) };
}
route("POST", "/orders/:orderId/accept", "orders:write", (c) => answerOrder(c, true));
route("POST", "/orders/:orderId/reject", "orders:write", (c) => answerOrder(c, false));
route("GET", "/orders", "orders:read", async (c) => {
  const [mine, placed, sold] = await Promise.all([
    db.collection("orders").where("partnerId", "==", c.pid).orderBy("createdAt", "desc").limit(100).get(),
    db.collection("openPartnerOrders").where("buyerPartnerId", "==", c.pid).where("env", "==", c.env).limit(100).get(),
    db.collection("openPartnerOrders").where("sellerPartnerId", "==", c.pid).where("env", "==", c.env).limit(100).get()]);
  return { orders: await Promise.all(mine.docs.filter((d) => (d.get("partnerEnv") ?? "production") === c.env).map((d) => partnerOrderView(d.id))),
    marketplaceOrdersPlaced: placed.docs.map((d) => ({ orderId: d.id, ...pick(d.data()) })), marketplaceOrdersReceived: sold.docs.map((d) => ({ orderId: d.id, ...pick(d.data()) })) };
});
route("GET", "/orders/:orderId", "orders:read", async (c) => {
  const id = c.params.orderId;
  if (id.startsWith("PO-")) { const d = (await db.doc(`openPartnerOrders/${id}`).get()).data();
    if (!d || (d.buyerPartnerId !== c.pid && d.sellerPartnerId !== c.pid)) throw new ApiError(404, "not_found", "Order not found."); return { order: { orderId: id, ...pick(d) } }; }
  const o = (await db.doc(`orders/${id}`).get()).data();
  if (!o || o.partnerId !== c.pid) throw new ApiError(404, "not_found", "Order not found.");
  return { order: await partnerOrderView(id) };
});
const pick = (d: Record<string, any>) => ({ status: d.status, items: d.items, goodsNgwee: d.goodsNgwee, externalOrderRef: d.buyerPartnerId ? d.externalOrderRef : undefined,
  deliveryId: d.deliveryId ?? null, createdAt: (d.createdAt as Timestamp | undefined)?.toDate().toISOString() ?? null });

/* ---------------------------------------------------------------- marketplace partner orders (partner → partner) */
route("POST", "/orders", "orders:write", async (c) => {
  const items = (Array.isArray(c.body?.items) ? c.body.items : []).slice(0, 20).map((i: any) => ({ listingId: String(i.listingId ?? ""), qty: Math.floor(Number(i.qty)) }));
  const ref = str(c.body?.externalOrderRef, 100);
  if (!items.length || items.some((i: any) => !i.listingId || !(i.qty >= 1))) throw new ApiError(422, "validation_failed", "items: [{ listingId, qty ≥ 1 }]");
  if (!ref) throw new ApiError(422, "validation_failed", "externalOrderRef is required (your order id).");
  const id = `PO-${sha256(`${c.pid}|${c.env}|${ref}`).slice(0, 14).toUpperCase()}`;
  const docRef = db.doc(`openPartnerOrders/${id}`);
  const existing = (await docRef.get()).data();
  if (existing) return ok({ order: { orderId: id, ...pick(existing) }, duplicate: true });
  const snaps = await db.getAll(...items.map((i: any) => db.doc(`listings/${i.listingId}`)));
  if (snaps.some((s) => !s.exists || !(s.get("sharing.partners") ?? []).includes(c.pid))) throw new ApiError(404, "not_found", "A product isn't shared with you.");
  const sellers = new Set(snaps.map((s) => s.get("origin.partnerId"))); if (sellers.size !== 1) throw new ApiError(422, "validation_failed", "One originating platform per order.");
  const sellerPartnerId = [...sellers][0] as string;
  const lines = items.map((i: any, k: number) => ({ listingId: i.listingId, externalProductId: snaps[k].get("origin.productId"), title: snaps[k].get("title"), qty: i.qty,
    unitNgwee: snaps[k].get("priceNgwee"), lineNgwee: i.qty * snaps[k].get("priceNgwee") }));
  const goods = lines.reduce((s: number, l: any) => s + l.lineNgwee, 0);
  await db.runTransaction(async (tx) => {
    await reserveInTx(tx, items, { type: "partnerOrder", id });
    tx.create(docRef, { buyerPartnerId: c.pid, sellerPartnerId, env: c.env, externalOrderRef: ref, items: lines, goodsNgwee: goods, status: "PENDING_CONFIRMATION",
      delivery: c.body?.delivery ?? null, acceptBy: Timestamp.fromMillis(Date.now() + ACCEPT_WINDOW_MIN * 60_000), createdAt: FieldValue.serverTimestamp() });
  });
  await emit(sellerPartnerId, c.env, "order.created", id, { order: { orderId: id, source: "partner_marketplace", buyerPlatform: c.partner.name, items: lines, goodsNgwee: goods } });
  return ok({ order: { orderId: id, status: "PENDING_CONFIRMATION", items: lines, goodsNgwee: goods } }, 201);
});
async function answerPartnerOrder(c: Ctx, accept: boolean) {
  const ref = db.doc(`openPartnerOrders/${c.params.orderId}`); const d = (await ref.get()).data();
  if (!d || d.sellerPartnerId !== c.pid) throw new ApiError(404, "not_found", "Order not found.");
  if (d.status !== "PENDING_CONFIRMATION") return ok({ order: { orderId: ref.id, ...pick(d) }, note: "Already answered." });
  await ref.update({ status: accept ? "CONFIRMED" : "REJECTED", answeredAt: FieldValue.serverTimestamp() });
  await settleReservations("partnerOrder", ref.id, accept ? "CONFIRMED" : "RELEASED");
  if (accept) { // the buying platform owes the goods; the selling platform is owed them less commission
    const seller = (await db.doc(`openPartners/${c.pid}`).get()).data() ?? {};
    await post(c.pid, { type: "SALE", amountNgwee: d.goodsNgwee, refType: "partnerOrder", refId: ref.id, env: d.env });
    await post(c.pid, { type: "COMMISSION", amountNgwee: commissionOn(d.goodsNgwee, seller.pricing), refType: "partnerOrder", refId: ref.id, env: d.env });
  }
  await emit(d.buyerPartnerId, d.env, accept ? "order.accepted" : "order.rejected", ref.id, { order: { orderId: ref.id, status: accept ? "CONFIRMED" : "REJECTED", externalOrderRef: d.externalOrderRef } });
  return { order: { orderId: ref.id, ...pick({ ...d, status: accept ? "CONFIRMED" : "REJECTED" }) } };
}
route("POST", "/orders/:orderId/cancel", "orders:write", async (c) => {
  const ref = db.doc(`openPartnerOrders/${c.params.orderId}`); const d = (await ref.get()).data();
  if (!d || (d.buyerPartnerId !== c.pid && d.sellerPartnerId !== c.pid)) throw new ApiError(404, "not_found", "Order not found (SeloNachipa buyer orders are cancelled by rejecting them).");
  if (!["PENDING_CONFIRMATION", "CONFIRMED"].includes(d.status)) throw new ApiError(409, "conflict", `Can't cancel a ${d.status} order.`);
  await ref.update({ status: "CANCELLED", cancelledBy: c.pid, cancelledAt: FieldValue.serverTimestamp() });
  if (d.status === "PENDING_CONFIRMATION") await settleReservations("partnerOrder", ref.id, "RELEASED");
  const other = d.buyerPartnerId === c.pid ? d.sellerPartnerId : d.buyerPartnerId;
  await emit(other, d.env, "order.cancelled", ref.id, { order: { orderId: ref.id, status: "CANCELLED" } });
  return { order: { orderId: ref.id, status: "CANCELLED" } };
});

/** Every 5 minutes: partners that didn't answer in time → reject (buyer refunded) and release stock. */
export const scheduled_openOrderTimeouts = onSchedule({ schedule: "every 5 minutes", secrets: OPEN_SECRETS }, async () => {
  const now = Timestamp.now();
  const late = await db.collection("orders").where("partnerAcceptance", "==", "PENDING").where("partnerAcceptDeadline", "<=", now).limit(100).get();
  const { refund } = await import("../escrow");
  for (const d of late.docs) {
    await d.ref.update({ partnerAcceptance: "EXPIRED" });
    await settleReservations("order", d.id, "RELEASED");
    await refund(d.id, "The seller didn't confirm the order in time");
    await emit(d.get("partnerId"), d.get("partnerEnv") ?? "production", "order.cancelled", d.id, { order: { orderId: d.id, reason: "acceptance_timeout" } });
  }
  const lateP = await db.collection("openPartnerOrders").where("status", "==", "PENDING_CONFIRMATION").where("acceptBy", "<=", now).limit(100).get();
  for (const d of lateP.docs) {
    await d.ref.update({ status: "EXPIRED" });
    await settleReservations("partnerOrder", d.id, "RELEASED");
    await emit(d.get("buyerPartnerId"), d.get("env"), "order.cancelled", d.id, { order: { orderId: d.id, reason: "acceptance_timeout" } });
  }
});
