/**
 * Open Commerce Network — discovery only. Each originating platform keeps its own checkout, payments, orders and
 * stock. SeloNachipa lists its products (same moderation, search and feed as any listing) and sends buyers to the
 * platform's own product page (checkoutUrl) to buy. Products keep their origin: platform, seller, product id, last sync.
 * Platforms can also share products with other approved platforms, which see them in GET /catalog (and link to the
 * same checkoutUrl). Delivery is provided by the Open Logistics Network.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { db } from "../config";
import { compositeKey } from "../algorithm";
import { sha256 } from "./crypto";
import { ApiError, type Ctx, ok, route } from "./gateway";
import { type Env, partnerSellerUid } from "./model";
import { emit } from "./webhooks";

const CATEGORIES = ["Fresh produce", "Groceries", "Fast food", "Electronics", "Phones & accessories", "Fashion", "Beauty", "Home", "Furniture", "Hardware",
  "Building materials", "Agriculture", "Baby", "Vehicles & parts", "Services", "Other"];

export const listingIdFor = (pid: string, env: Env, externalProductId: string) => `ext_${env === "sandbox" ? "sbx_" : ""}${sha256(`${pid}|${externalProductId}`).slice(0, 20)}`;
const str = (v: unknown, max: number) => String(v ?? "").trim().slice(0, max);
const isLL = (v: any) => v && Number.isFinite(Number(v.lat)) && Number.isFinite(Number(v.lng));

export interface ProductInput {
  externalProductId: string; externalSellerId: string; sellerName: string; title: string; description?: string; category?: string; images?: string[];
  priceNgwee: number; currency?: string; stock: number; checkoutUrl: string;
  location?: { lat: number; lng: number; address?: string; district?: string; province?: string };
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
  if (need("checkoutUrl") && !/^https:\/\/\S+$/.test(String(p.checkoutUrl ?? ""))) e.push("checkoutUrl: the https link to buy this product on your platform (buyers pay on your checkout)");
  if (p.images && (!Array.isArray(p.images) || p.images.length > 10 || p.images.some((u) => !/^https:\/\//.test(String(u))))) e.push("images: up to 10 https URLs");
  if (p.location && !isLL(p.location)) e.push("location: lat and lng required");
  if (p.category && !CATEGORIES.includes(p.category)) e.push(`category: one of ${CATEGORIES.join(", ")}`);
  return e;
}

/** What other platforms (and the originating one) see of a listing. */
export function publicProduct(id: string, x: Record<string, any>) {
  return { listingId: id, externalProductId: x.origin?.productId ?? null, originPlatform: x.origin?.partnerName ?? "SeloNachipa", checkoutUrl: x.origin?.checkoutUrl ?? null,
    title: x.title, description: x.description, category: x.category, images: x.photoUrls ?? [], priceNgwee: x.priceNgwee, currency: "ZMW", availableStock: x.stock ?? 0,
    status: x.status, location: x.pickupGeo ? { lat: x.pickupGeo.lat, lng: x.pickupGeo.lng, district: x.district ?? null } : null, deliveryOptions: x.deliveryOptions ?? null,
    lastSyncedAt: (x.origin?.syncedAt as Timestamp | undefined)?.toDate().toISOString() ?? null };
}

async function upsertProduct(c: Ctx, input: ProductInput, create: boolean) {
  const problems = productProblems(input, !create);
  if (problems.length) throw new ApiError(422, "validation_failed", "Some fields are invalid.", problems);
  const id = listingIdFor(c.pid, c.env, input.externalProductId);
  const ref = db.doc(`listings/${id}`);
  const cur = (await ref.get()).data();
  if (!create && !cur) throw new ApiError(404, "not_found", "No product with that externalProductId. Create it with POST /products.");
  if (cur && cur.origin?.partnerId !== c.pid) throw new ApiError(404, "not_found", "Product not found.");
  // sharing: only with ACTIVE platforms that have the commerce service (unknown ids are dropped)
  let partners: string[] | undefined;
  if (input.sharing?.partners) {
    const ids = [...new Set(input.sharing.partners.map(String))].filter((p) => p !== c.pid).slice(0, 50);
    const snaps = ids.length ? await db.getAll(...ids.map((p) => db.doc(`openPartners/${p}`))) : [];
    partners = snaps.filter((s) => s.exists && s.get("status") === "ACTIVE" && s.get("services.commerce") === true).map((s) => s.id);
  }
  const sellerUid = partnerSellerUid(c.pid, input.externalSellerId ?? cur?.origin?.sellerId);
  const loc = input.location ?? (cur?.pickupGeo ? { ...cur.pickupGeo } : null);
  const publish = (input.status ?? (cur ? (cur.status === "archived" || cur.status === "paused" ? "draft" : "published") : "published")) === "published";
  const marketplace = input.sharing?.selonachipaMarketplace ?? cur?.sharing?.selonachipaMarketplace ?? true;
  const stock = input.stock ?? cur?.stock ?? 0;
  const status = c.env === "sandbox" ? "sandbox" : !publish || !marketplace ? "paused" : cur?.status === "live" ? "live" : "pending_review";
  const version = (cur?.origin?.version ?? 0) + 1;
  const data: Record<string, unknown> = {
    sellerUid, sellerName: str(input.sellerName ?? cur?.sellerName, 80) || c.partner.name, sellerVerified: true, agentUid: null,
    title: str(input.title ?? cur?.title, 80), description: str(input.description ?? cur?.description, 1200),
    priceNgwee: input.priceNgwee ?? cur?.priceNgwee, discountPct: 0, discountNgwee: 0,
    category: input.category ?? cur?.category ?? "Agriculture", condition: "New", availability: stock > 0 ? "In Stock" : "Sold out",
    province: str(input.location?.province ?? cur?.province, 40), district: str(input.location?.district ?? cur?.district, 60), location: str(input.location?.address ?? cur?.location, 120),
    tags: (input.tags ?? cur?.tags ?? []).slice(0, 8).map((t: string) => String(t).toLowerCase().slice(0, 30)),
    mediaType: "photo", photoUrls: input.images ?? cur?.photoUrls ?? [], thumbUrl: (input.images ?? cur?.photoUrls ?? [])[0] ?? null, videoUrl: null,
    stock, pickupGeo: loc ? { lat: Number(loc.lat), lng: Number(loc.lng) } : null,
    deliveryOptions: { selonachipaDelivery: input.deliveryOptions?.selonachipaDelivery ?? cur?.deliveryOptions?.selonachipaDelivery ?? true, pickup: input.deliveryOptions?.pickup ?? cur?.deliveryOptions?.pickup ?? false },
    sharing: { selonachipaMarketplace: marketplace, partners: partners ?? cur?.sharing?.partners ?? [] },
    origin: { partnerId: c.pid, partnerName: c.partner.name, sellerId: input.externalSellerId ?? cur?.origin?.sellerId, productId: input.externalProductId ?? cur?.origin?.productId,
      checkoutUrl: input.checkoutUrl ?? cur?.origin?.checkoutUrl, env: c.env, syncedAt: FieldValue.serverTimestamp(), version },
    status, updatedAt: FieldValue.serverTimestamp(),
  };
  if (!cur) Object.assign(data, { shard: compositeKey("lst", sellerUid).shard, aiUsed: false, aiSafe: true, durationMs: null, createdAt: FieldValue.serverTimestamp(), publishedAt: null,
    stats: { views: 0, likes: 0, shares: 0, enquiries: 0, saves: 0, notInterested: 0, watchMs: 0, watchTotalMs: 0, taps: 0, skips: 0 }, sellerEngagementRate: 0, viralityScore: 0 });
  await ref.set(data, { merge: true });
  const snapshot = publicProduct(id, { ...cur, ...data, origin: { ...(data.origin as object), syncedAt: Timestamp.now() } });
  const type = !cur ? "product.created" : (input.stock !== undefined && Object.keys(input).length <= 2 ? "product.stock_updated" : "product.updated");
  await Promise.all((data.sharing as { partners: string[] }).partners.map((p) => emit(p, c.env, type as any, id, { product: snapshot }, version)));
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

/* ---------------------------------------------------------------- catalog (products shared with me) */
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
