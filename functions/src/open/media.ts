/**
 * Open Network media: the existing Listing Studio (AI video) and AI Ad Generator, exposed to partner platforms.
 * No second pipeline: partners run the same code SeloNachipa sellers use.
 *
 *   POST /products/:externalId/video        start an AI video for one of your products
 *                                           photo → Runway image-to-video product motion → Gemini script →
 *                                           ElevenLabs voiceover → Cloudinary 9:16 branded edit (headline, price, captions)
 *   GET  /products/:externalId/video        status and result (also sent as media.video.ready / media.video.failed)
 *   POST /products/:externalId/video/apply  use the AI video on the SeloNachipa listing (true) or go back to the original
 *   POST /ads                               AI ad from a product photo: 9:16 story + 1:1 square image around the real
 *                                           photo, description, WhatsApp / Facebook / TikTok copy and hashtags
 *   GET  /ads                               your recent ads
 *
 * Sandbox never calls the paid AI providers: it returns clearly marked sample media and fires the same webhooks.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { db, loadConfig } from "../config";
import { listingIdFor } from "./commerce";
import { ApiError, type Ctx, ok, route } from "./gateway";
import { post } from "./settlement";
import { emit } from "./webhooks";

const wrap = async <T>(f: () => Promise<T>): Promise<T> => {
  try { return await f(); } catch (e) {
    if (e instanceof HttpsError) throw new ApiError(e.code === "not-found" ? 404 : e.code === "resource-exhausted" ? 429 : e.code === "unavailable" ? 503 : 422, e.code.replace(/-/g, "_"), e.message);
    throw e;
  }
};
const iso = (t: unknown) => (t as Timestamp | undefined)?.toDate?.().toISOString() ?? null;
const clip = (v: unknown, n: number) => String(v ?? "").trim().slice(0, n);

export const SAMPLE = {
  video: "https://res.cloudinary.com/demo/video/upload/dog.mp4",
  poster: "https://res.cloudinary.com/demo/video/upload/dog.jpg",
  image: "https://res.cloudinary.com/demo/image/upload/sample.jpg",
};

/** What a partner sees of a product's AI video. */
export function studioView(listingId: string, l: Record<string, any>, j?: Record<string, any> | null) {
  const s = l.studio ?? {};
  const status: string = s.status ?? (j ? "processing" : "none");
  return {
    listingId, externalProductId: l.origin?.productId ?? null, status, // none | processing | ready | failed
    progress: j && Array.isArray(j.steps) ? { step: j.steps[Math.min(j.index ?? 0, j.steps.length - 1)] ?? null, done: j.index ?? 0, total: j.steps.length } : null,
    videoUrl: status === "ready" ? s.videoUrl ?? null : null, posterUrl: status === "ready" ? s.posterUrl ?? null : null,
    photoUrls: status === "ready" ? s.photoUrls ?? [] : [], headline: s.headline ?? null, hashtags: s.hashtags ?? [],
    voiceover: !!s.voiceover, captions: !!s.captions, generativeMotion: !!s.generativeMotion, appliedToListing: !!s.applied,
    sample: !!s.sample, error: status === "failed" ? s.error ?? "The video couldn't be produced. Your original media is unchanged." : null,
    finishedAt: iso(s.finishedAt),
  };
}

async function ownListing(c: Ctx) {
  const id = listingIdFor(c.pid, c.env, c.params.externalId);
  const l = (await db.doc(`listings/${id}`).get()).data();
  if (!l || l.origin?.partnerId !== c.pid) throw new ApiError(404, "not_found", "Product not found. Publish it with POST /products first.");
  if (l.status === "archived") throw new ApiError(409, "conflict", "This product is archived.");
  return { id, l };
}

/* ------------------------------------------------------------------ AI video */
route("POST", "/products/:externalId/video", "media:write", async (c) => wrap(async () => {
  const { id, l } = await ownListing(c);
  if (!(l.photoUrls ?? []).length && !l.videoUrl) throw new ApiError(422, "validation_failed", "Add at least one product image (images) before making a video.");
  if (l.studio?.status === "processing") return ok({ video: studioView(id, l, (await db.doc(`studioJobs/${id}`).get()).data()), started: false }, 202);
  const autoApply = c.body?.applyToListing !== false;
  if (c.env === "sandbox") {
    // Sample result, no paid providers: lets partners build against the flow and webhooks.
    const studio = { status: "ready", videoUrl: SAMPLE.video, posterUrl: SAMPLE.poster, photoUrls: l.photoUrls ?? [], headline: clip(l.title, 32),
      hashtags: ["Lusaka", "ShopLocal"], voiceover: c.body?.voiceover !== false, captions: false, generativeMotion: true, applied: false, sample: true, finishedAt: Timestamp.now() };
    await db.doc(`listings/${id}`).set({ studio }, { merge: true });
    const view = studioView(id, { ...l, studio });
    await emit(c.pid, c.env, "media.video.ready", id, { video: view }, `ready-${Date.now()}`);
    return ok({ video: view, started: true }, 202);
  }
  const { startStudio } = await import("../studio/studio");
  const r = await startStudio(id, `partner:${c.pid}`, { partnerId: c.pid, env: c.env, autoApply, voiceover: c.body?.voiceover !== false });
  if (!r.started) throw new ApiError(429, "limit_reached", r.reason ?? "Video can't be started right now.");
  const fresh = (await db.doc(`listings/${id}`).get()).data()!;
  return ok({ video: studioView(id, fresh, (await db.doc(`studioJobs/${id}`).get()).data()), started: true }, 202);
}));

route("GET", "/products/:externalId/video", "media:read", async (c) => {
  const { id, l } = await ownListing(c);
  return { video: studioView(id, l, (await db.doc(`studioJobs/${id}`).get()).data()) };
});

route("POST", "/products/:externalId/video/apply", "media:write", async (c) => wrap(async () => {
  const { id, l } = await ownListing(c);
  if (l.studio?.status !== "ready") throw new ApiError(409, "conflict", "There's no finished video to apply yet.");
  const apply = c.body?.apply !== false;
  if (l.studio?.sample) await db.doc(`listings/${id}`).set({ studio: { applied: apply } }, { merge: true });
  else { const { applyStudio } = await import("../studio/studio"); await applyStudio(id, apply); }
  return { video: studioView(id, (await db.doc(`listings/${id}`).get()).data()!) };
}));

/* ------------------------------------------------------------------ AI ads */
/** Downloads a partner image: https only, public hosts only, image content, 10 MB max. */
export async function fetchImage(url: string, http: typeof fetch = fetch): Promise<Buffer> {
  let u: URL;
  try { u = new URL(url); } catch { throw new ApiError(422, "validation_failed", "imageUrl must be a valid https URL."); }
  if (u.protocol !== "https:" || /^(localhost|metadata\b|.*\.internal$|.*\.local$)/i.test(u.hostname) || /^[\d.]+$|^\[|:/.test(u.hostname))
    throw new ApiError(422, "validation_failed", "imageUrl must be a public https URL (no IP addresses or internal hosts).");
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 15_000);
  try {
    const r = await http(u.toString(), { signal: ctl.signal, redirect: "error" });
    if (!r.ok) throw new ApiError(422, "validation_failed", `We couldn't download imageUrl (HTTP ${r.status}).`);
    if (!/^image\//.test(r.headers.get("content-type") ?? "")) throw new ApiError(422, "validation_failed", "imageUrl must point to an image (JPEG or PNG).");
    const len = Number(r.headers.get("content-length") ?? 0);
    if (len > 10 * 1024 * 1024) throw new ApiError(422, "validation_failed", "That image is too large (10 MB max).");
    const b = Buffer.from(await r.arrayBuffer());
    if (b.length > 10 * 1024 * 1024) throw new ApiError(422, "validation_failed", "That image is too large (10 MB max).");
    return b;
  } catch (e) {
    if (e instanceof ApiError) throw e;
    throw new ApiError(422, "validation_failed", "We couldn't download imageUrl. Check that it's public.");
  } finally { clearTimeout(t); }
}

export const adView = (id: string, a: Record<string, any>) => ({ adId: id, externalProductId: a.externalProductId ?? null, storyImageUrl: a.storyUrl, squareImageUrl: a.squareUrl,
  productName: a.productName, headline: a.headline, description: a.description, copy: { whatsapp: a.whatsapp, facebook: a.facebook, tiktok: a.tiktok }, hashtags: a.hashtags ?? [],
  price: a.price ?? null, offer: a.offer ?? null, sample: !!a.sample, createdAt: iso(a.createdAt) });

route("POST", "/ads", "media:write", async (c) => wrap(async () => {
  const b = c.body ?? {};
  let imageUrl = clip(b.imageUrl, 2000), productName = clip(b.productName, 60), priceNgwee = Number(b.priceNgwee) || 0, extId: string | null = null;
  if (b.externalProductId) {
    const lid = listingIdFor(c.pid, c.env, String(b.externalProductId));
    const l = (await db.doc(`listings/${lid}`).get()).data();
    if (!l || l.origin?.partnerId !== c.pid) throw new ApiError(404, "not_found", "Product not found.");
    imageUrl ||= l.photoUrls?.[0] ?? ""; productName ||= clip(l.title, 60); priceNgwee ||= l.priceNgwee ?? 0; extId = String(b.externalProductId);
  }
  if (!imageUrl) throw new ApiError(422, "validation_failed", "Send imageUrl, or externalProductId of a product with images.");
  if (priceNgwee && !(Number.isInteger(priceNgwee) && priceNgwee > 0)) throw new ApiError(422, "validation_failed", "priceNgwee: positive integer (K 1.00 = 100)");
  const cta = clip(b.callToAction, 40) || `Order on ${c.partner.name}`;
  const offer = clip(b.offer, 40);

  if (c.env === "sandbox") {
    const ref = await db.collection("adCreatives").add({ uid: `partner:${c.pid}`, partnerId: c.pid, env: c.env, externalProductId: extId, sample: true,
      productName: productName || "Sample product", headline: "Fresh and ready", description: "Sample description. In production this is written by AI from your photo.",
      whatsapp: `${productName || "Sample product"} now available. ${cta}.`, facebook: "Sample Facebook post.", tiktok: "Sample TikTok caption", hashtags: ["#Lusaka", "#ShopLocal"],
      price: priceNgwee ? `K ${(priceNgwee / 100).toFixed(2)}` : null, offer: offer || null, storyUrl: SAMPLE.image, squareUrl: SAMPLE.image, createdAt: FieldValue.serverTimestamp() });
    return ok({ ad: adView(ref.id, (await ref.get()).data()!) }, 201);
  }
  const cfg = await loadConfig() as Record<string, any>;
  const { adQuota, generateAd } = await import("../adgen");
  if (!(await adQuota(`partner:${c.pid}`, Number(cfg.openAdDailyCap ?? 200)))) throw new ApiError(429, "limit_reached", "Daily ad limit reached for your platform. Try again tomorrow.");
  const bytes = await fetchImage(imageUrl);
  const ad = await generateAd({ bytes, productName, priceNgwee, offer, shop: c.partner.name, ownerKey: `partner:${c.pid}`, sourceRef: imageUrl, cta,
    extra: { partnerId: c.pid, env: c.env, externalProductId: extId } });
  const price = Number(c.partner.pricing?.mediaAdNgwee ?? 0);
  if (price > 0) await post(c.pid, { type: "MEDIA_USAGE", amountNgwee: price, refType: "media", refId: `ad:${ad.id}`, env: c.env, note: "AI ad" });
  return ok({ ad: adView(ad.id, (await db.doc(`adCreatives/${ad.id}`).get()).data()!) }, 201);
}));

route("GET", "/ads", "media:read", async (c) => {
  const q = await db.collection("adCreatives").where("partnerId", "==", c.pid).where("env", "==", c.env).orderBy("createdAt", "desc").limit(Math.min(100, Number(c.query.limit) || 20)).get();
  return { ads: q.docs.map((d) => adView(d.id, d.data())) };
});
