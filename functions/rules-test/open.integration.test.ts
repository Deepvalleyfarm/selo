/**
 * SeloNachipa Open Network end-to-end against the Firestore emulator, through the real HTTP pipeline (handle()):
 * OAuth, scopes, idempotency, rate limiting, revocation, platform isolation, product syndication, stock
 * reservations, partner-to-partner orders, SeloNachipa buyer orders for partner products, deliveries in the
 * existing parcel engine, webhooks outbox and settlement ledger reconciliation.
 */
process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "demo-selo";
process.env.OPEN_API_JWT_SECRET = "it-jwt-secret-0123456789abcdef";
process.env.OPEN_API_ENC_KEY = "it-enc-key-0123456789";

import { beforeAll, describe, expect, it } from "vitest";
let db: FirebaseFirestore.Firestore;
let gw: typeof import("../src/open/gateway");
let crypto: typeof import("../src/open/crypto");
const run = Date.now().toString(36);
const A = `ptn_mabala_${run}`, B = `ptn_shop_${run}`;
const secrets: Record<string, string> = {};

function req(method: string, path: string, body: any = {}, headers: Record<string, string> = {}) {
  const h = Object.fromEntries(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));
  return { method, path: `/api/v1${path}`, body, query: {}, ip: "127.0.0.1", headers: h, get: (k: string) => h[k.toLowerCase()] } as any;
}
async function token(cid: string) {
  const r = await gw.handle(req("POST", "/oauth/token", { grant_type: "client_credentials", client_id: cid, client_secret: secrets[cid] }));
  expect(r.status).toBe(200); return (r.body as any).access_token as string;
}
let n = 0;
const call = async (tok: string, method: string, path: string, body?: any, key: string | null = `k-${run}-${++n}`) =>
  gw.handle(req(method, path, body, { authorization: `Bearer ${tok}`, ...(key ? { "idempotency-key": key } : {}) }));

async function partner(id: string, name: string, services: any, scopes: string[], rate = 600) {
  await db.doc(`openPartners/${id}`).set({ name, slug: id, status: "ACTIVE", services, scopes, environments: { sandbox: true, production: true }, rateLimitPerMin: rate,
    pricing: { marketplaceCommissionPct: 5 }, webhook: { url: "https://example.invalid/hook", events: ["order.created", "order.accepted", "delivery.created"], secretEnc: crypto.encrypt("whsec_dGVzdA==", process.env.OPEN_API_ENC_KEY!) } });
  const cid = `live_${id}`; const sec = `sns_${id}`; const { salt, hash } = crypto.hashSecret(sec);
  await db.doc(`openClients/${cid}`).set({ partnerId: id, env: "production", status: "ACTIVE", salt, hash });
  secrets[cid] = sec; return cid;
}
const product = { externalProductId: "SKU-42", externalSellerId: "farm-node-117", sellerName: "Mwila Farm", title: "Tomatoes 20 kg crate", priceNgwee: 18_000, stock: 10,
  category: "Agriculture", images: ["https://cdn.example.com/42.jpg"], location: { lat: -15.4167, lng: 28.2833, address: "Soweto Market" } };
let tA = "", tB = "", cidA = "", cidB = "";

beforeAll(async () => {
  db = (await import("../src/config")).db;
  crypto = await import("../src/open/crypto");
  await import("../src/open"); // registers routes
  gw = await import("../src/open/gateway");
  cidA = await partner(A, "Mabala", { commerce: true, logistics: true }, ["products:write", "products:read", "inventory:write", "orders:read", "orders:write", "delivery:quote", "delivery:write", "delivery:read", "coverage:read", "webhooks:manage", "settlements:read"]);
  cidB = await partner(B, "Other Shop", { commerce: true, logistics: false }, ["catalog:read", "orders:read", "orders:write", "settlements:read", "delivery:write"]);
  tA = await token(cidA); tB = await token(cidB);
});

describe("auth, scopes and isolation", () => {
  it("rejects bad credentials and missing tokens", async () => {
    expect((await gw.handle(req("POST", "/oauth/token", { client_id: cidA, client_secret: "nope" }))).status).toBe(401);
    expect((await gw.handle(req("GET", "/products"))).status).toBe(401);
  });
  it("a scope the partner's services don't include is refused (B has no logistics service)", async () => {
    const r = await call(tB, "POST", "/deliveries", {}); expect(r.status).toBe(403); expect((r.body as any).error.code).toBe("insufficient_scope");
  });
  it("writes need an Idempotency-Key", async () => { expect((await call(tA, "POST", "/products", product, null)).status).toBe(400); });
});

describe("product syndication, stock and partner orders", () => {
  let listingId = "";
  it("publishes once per external id; idempotent replay; updates don't duplicate", async () => {
    const r1 = await call(tA, "POST", "/products", product, `pub-${run}`);
    expect(r1.status).toBe(201); listingId = (r1.body as any).product.listingId;
    const again = await call(tA, "POST", "/products", product, `pub-${run}`);
    expect((again.body as any).idempotentReplay).toBe(true);
    const conflict = await call(tA, "POST", "/products", { ...product, priceNgwee: 1 }, `pub-${run}`);
    expect(conflict.status).toBe(409);
    const upd = await call(tA, "POST", "/products", { ...product, priceNgwee: 17_500 });
    expect(upd.status).toBe(200); expect((upd.body as any).product.listingId).toBe(listingId);
    const l = (await db.doc(`listings/${listingId}`).get()).data()!;
    expect(l).toMatchObject({ priceNgwee: 17_500, stock: 10, sellerUid: `partner:${A}:farm-node-117`, origin: { partnerId: A, productId: "SKU-42", sellerId: "farm-node-117" } });
  });
  it("another platform can't see or change it until it is explicitly shared", async () => {
    expect((await call(tB, "GET", `/catalog/${listingId}`, undefined, null)).status).toBe(404);
    expect((await call(tB, "POST", "/products/SKU-42/stock", { stock: 0 })).status).toBe(403); // no products scope
    await db.doc(`listings/${listingId}`).update({ status: "live" });
    await call(tA, "PATCH", "/products/SKU-42", { sharing: { partners: [B] } });
    expect((await call(tB, "GET", `/catalog/${listingId}`, undefined, null)).status).toBe(200);
  });
  it("partner-to-partner order reserves stock, is de-duplicated, and settles on acceptance", async () => {
    const o = await call(tB, "POST", "/orders", { externalOrderRef: `SHOP-${run}`, items: [{ listingId, qty: 3 }] });
    expect(o.status).toBe(201);
    const orderId = (o.body as any).order.orderId;
    expect((await db.doc(`listings/${listingId}`).get()).get("stock")).toBe(7);
    const dup = await call(tB, "POST", "/orders", { externalOrderRef: `SHOP-${run}`, items: [{ listingId, qty: 3 }] });
    expect((dup.body as any).duplicate).toBe(true);
    expect((await db.doc(`listings/${listingId}`).get()).get("stock")).toBe(7);
    // origin pushes its own stock: open reservations are subtracted
    await call(tA, "POST", "/products/SKU-42/stock", { stock: 10 });
    expect((await db.doc(`listings/${listingId}`).get()).get("stock")).toBe(7);
    expect((await call(tB, "POST", `/orders/${orderId}/accept`, {})).status).toBe(404); // only the seller platform answers
    const acc = await call(tA, "POST", `/orders/${orderId}/accept`, {});
    expect((acc.body as any).order.status).toBe("CONFIRMED");
    const led = await call(tA, "GET", "/settlements", undefined, null);
    expect((led.body as any).byType).toMatchObject({ SALE: 52_500, COMMISSION: -2_625 });
    const evs = await db.collection("openEvents").where("pid", "==", B).where("type", "==", "order.accepted").get();
    expect(evs.size).toBe(1);
  });
  it("a SeloNachipa buyer order for a partner product waits for the partner, then dispatches; seller money goes to the ledger", async () => {
    const orderId = `SNC-IT-${run}`;
    await db.doc(`orders/${orderId}`).set({ buyerUid: "buyer-it", buyerName: "Mwila Banda", sellerUid: `partner:${A}:farm-node-117`, partnerId: A, partnerEnv: "production",
      items: [{ listingId, title: "Tomatoes", qty: 2, unitNgwee: 17_500, lineNgwee: 35_000 }], goodsNgwee: 35_000, deliveryNgwee: 2_000, totalNgwee: 37_000,
      state: "ESCROW_LOCKED", dropoff: "Plot 9, Kabwata, Lusaka", feeSnapshot: {} });
    const commerce = await import("../src/open/commerce");
    expect(await commerce.onPartnerOrderPaid(orderId)).toBe("awaiting_partner");
    expect((await db.doc(`orders/${orderId}`).get()).get("partnerAcceptance")).toBe("PENDING");
    const ev = await db.collection("openEvents").where("pid", "==", A).where("subject", "==", orderId).get();
    expect(ev.docs[0].get("data.order.buyer")).toEqual({ firstName: "Mwila" }); // no phone number shared
    const acc = await call(tA, "POST", `/orders/${orderId}/accept`, { partnerOrderRef: "MBL-SALE-1" });
    expect(acc.status).toBe(200);
    expect((await db.doc(`orders/${orderId}`).get()).get("state")).toBe("BROADCASTING");
    const { disburse } = await import("../src/escrow");
    await disburse(`SELO-SELR-${orderId}`, "seller", `partner:${A}:farm-node-117`, 34_500, "", orderId);
    await disburse(`SELO-SELR-${orderId}`, "seller", `partner:${A}:farm-node-117`, 34_500, "", orderId); // retried: no double entry
    const sale = await db.collection(`openLedger/${A}/entries`).where("refId", "==", orderId).get();
    expect(sale.docs.map((d) => [d.get("type"), d.get("signedNgwee")]).sort()).toEqual([["COMMISSION", -1_750], ["PLATFORM_FEE", -500], ["SALE", 35_000]]);
    expect((await db.doc(`payouts/SELO-SELR-${orderId}`).get()).get("status")).toBe("PARTNER_LEDGER"); // never sent to Lipila
  });
});

describe("logistics through the existing parcel engine", () => {
  it("quotes, creates once per external ref, charges and reverses the ledger on cancel", async () => {
    const pickup = { lat: -15.4167, lng: 28.2833, address: "Soweto Market" }, dropoff = { lat: -15.43, lng: 28.30, address: "Kabwata" };
    const q = await call(tA, "POST", "/delivery/quotes", { pickup, dropoff }, null);
    expect(q.status).toBe(200); expect((q.body as any).priceNgwee).toBeGreaterThan(0);
    const body = { externalOrderRef: `MBL-ORD-${run}`, pickup, dropoff, weightBand: "4–5 kg", description: "Vegetables", receiver: { name: "Chanda", msisdn: "+260971234567" } };
    const d = await call(tA, "POST", "/deliveries", body);
    expect(d.status).toBe(201);
    const id = (d.body as any).delivery.deliveryId;
    expect((d.body as any).pickupPin).toMatch(/^\d{4}$/);
    const p = (await db.doc(`parcels/${id}`).get()).data()!;
    expect(p).toMatchObject({ partnerId: A, status: "AWAITING_RIDER", paymentStatus: "PARTNER_ACCOUNT", source: { platform: "Mabala", externalOrderRef: `MBL-ORD-${run}` } });
    const dup = await call(tA, "POST", "/deliveries", body); // new key, same order ref → same delivery
    expect((dup.body as any).duplicate).toBe(true); expect((dup.body as any).delivery.deliveryId).toBe(id);
    expect((await call(tB, "GET", `/deliveries/${id}`, undefined, null)).status).toBe(403); // B has no delivery:read
    const cancel = await call(tA, "POST", `/deliveries/${id}/cancel`, { reason: "Buyer changed mind" });
    expect((cancel.body as any).delivery.status).toBe("CANCELLED");
    const led = await db.collection(`openLedger/${A}/entries`).where("refId", "==", id).get();
    expect(led.docs.map((x) => x.get("signedNgwee")).reduce((a, b) => a + b, 0)).toBe(0);
  });
});

describe("revocation, suspension and rate limits block immediately", () => {
  it("revoked client and suspended partner can't use a still-valid token", async () => {
    await db.doc(`openClients/${cidB}`).update({ status: "REVOKED" });
    expect((await call(tB, "GET", "/catalog", undefined, null)).status).toBe(403);
    await db.doc(`openClients/${cidB}`).update({ status: "ACTIVE" });
    await db.doc(`openPartners/${B}`).update({ status: "SUSPENDED" });
    expect((await call(tB, "GET", "/catalog", undefined, null)).status).toBe(403);
    await db.doc(`openPartners/${B}`).update({ status: "ACTIVE", rateLimitPerMin: 2 });
    const codes = [];
    for (let i = 0; i < 3; i++) codes.push((await call(tB, "GET", "/catalog", undefined, null)).status);
    expect(codes.at(-1)).toBe(429);
  });
});
