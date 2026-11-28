import { GoogleGenAI } from "@google/genai";
import { logger } from "firebase-functions/v2";
import { getStorage } from "firebase-admin/storage";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { GEMINI_API_KEY, GEMINI_MODEL, db, SMS_SECRETS } from "./config";
import { compositeKey, suggestPrice, uuidv7 } from "./algorithm";
import { requireUser, str } from "./util";

/** Selonachipa taxonomy (top level). Kept in sync with the app and back office category list. */
export const CATEGORIES = ["Fresh produce", "Groceries", "Fast food", "Electronics", "Phones & accessories", "Fashion", "Beauty", "Home",
  "Furniture", "Hardware", "Building materials", "Agriculture", "Baby", "Vehicles & parts", "Services", "Other"];
export const CONDITIONS = ["New", "Like new", "Good", "Fair", "For parts"];
export const AVAILABILITY = ["In Stock", "Made to Order", "Last Units"];

const ownsUpload = (uid: string, p: string) => p.replace(/^\//, "").startsWith(`listing-uploads/${uid}/`);

/** Region-filtered, recency-weighted category median (§4.1.3). Falls back to nationwide if the region is thin. */
async function marketPrice(category: string, province?: string) {
  const since = Timestamp.fromMillis(Date.now() - 30 * 86_400_000);
  const q = db.collection("listings").where("category", "==", category).where("publishedAt", ">=", since).select("priceNgwee", "publishedAt", "province").limit(500);
  const docs = (await q.get()).docs;
  const sample = (ds: typeof docs) => ds.map((d) => ({ priceNgwee: d.get("priceNgwee") ?? 0, ts: (d.get("publishedAt") as Timestamp)?.toMillis() ?? 0 }));
  const regional = province ? docs.filter((d) => d.get("province") === province) : docs;
  return { ...(suggestPrice(sample(regional.length >= 5 ? regional : docs)) ?? {}), scope: regional.length >= 5 && province ? province : "Zambia" };
}

/**
 * AI auto-fill (§4.1.3). The app extracts frames on-device (1 fps for 10 s, then 1 per 5 s) and runs an
 * on-device image labeller for an instant category; this call sends those frames to the cloud LLM for the
 * title, 3–5 sentence description, ZMW price range and category. The app falls back to manual entry if this
 * takes longer than the configured target (8 s by default).
 */
export const listing_runAiPipeline = onCall({ secrets: [...SMS_SECRETS, GEMINI_API_KEY], timeoutSeconds: 60, memory: "1GiB" }, async (req) => {
  const { uid } = requireUser(req, "SELLER", "AGENT");
  const frames = ((req.data?.framePaths ?? req.data?.mediaPaths ?? []) as string[]).filter((p) => !p.endsWith(".mp4")).slice(0, 16);
  const labels = ((req.data?.onDeviceLabels ?? []) as { text: string; confidence: number }[]).slice(0, 10);
  const prompt = typeof req.data?.prompt === "string" ? req.data.prompt.slice(0, 600) : "";
  const mediaType = req.data?.mediaType === "video" ? "video" : "photo";
  if (!frames.length && prompt.length < 8) throw new HttpsError("invalid-argument", "Add a video, photos or a short description.");
  if (frames.some((p) => !ownsUpload(uid, p))) throw new HttpsError("permission-denied", "Invalid media.");
  const seller = (await db.doc(`users/${uid}`).get()).data() ?? {};

  const bucket = getStorage().bucket();
  const parts: { inlineData?: { mimeType: string; data: string }; text?: string }[] = [];
  for (const p of frames) {
    const [buf] = await bucket.file(p.replace(/^\//, "")).download();
    parts.push({ inlineData: { mimeType: "image/jpeg", data: buf.toString("base64") } });
  }
  parts.push({ text: `You write product listings for SeloNaChipa, a Zambian marketplace. The ${frames.length} images are ${mediaType === "video" ? "frames from the seller's product video, in order" : "the seller's product photos"}.
Seller location: ${seller.district ?? seller.area ?? "Lusaka"}, ${seller.province ?? "Lusaka"} Province.
On-device labels: ${labels.map((l) => `${l.text} (${Math.round(l.confidence * 100)}%)`).join(", ") || "none"}.
Seller notes: "${prompt}".
Describe: the product type, visible attributes (colour, size, quantity, condition), how and where it is used, and price it for the Zambian market in Kwacha.
Return JSON only: {"title": string (max 60 chars), "description": string (3 to 5 sentences, plain English), "category": one of ${JSON.stringify(CATEGORIES)},
"condition": one of ${JSON.stringify(CONDITIONS)}, "tags": string[] (3-6 lowercase words), "priceLowKwacha": number, "priceHighKwacha": number, "priceKwacha": number,
"safe": boolean (false if illegal, dangerous, counterfeit or adult goods)}.` });

  const ai = new GoogleGenAI({ apiKey: GEMINI_API_KEY.value() });
  const res = await ai.models.generateContent({ model: GEMINI_MODEL.value(), contents: [{ role: "user", parts }],
    config: { responseMimeType: "application/json", temperature: 0.4 } });
  let out: { title: string; description: string; category: string; condition: string; tags: string[]; priceLowKwacha: number; priceHighKwacha: number; priceKwacha: number; safe: boolean };
  try { out = JSON.parse(res.text ?? "{}"); } catch { throw new HttpsError("internal", "Auto-fill couldn't read your media."); }
  if (out.safe === false) {
    await db.collection("moderationQueue").add({ uid, paths: frames, reason: "AI flagged unsafe content", createdAt: FieldValue.serverTimestamp() });
    throw new HttpsError("failed-precondition", "This item can't be listed. Our team will review it.");
  }
  const category = CATEGORIES.includes(out.category) ? out.category : "Other";
  const k = (n: number) => Math.round((Number(n) || 0) * 100);
  const market = await marketPrice(category, seller.province);
  // Blend: market median wins when there's enough local data; the LLM estimate fills gaps.
  const priceNgwee = market.medianNgwee ? Math.round(market.medianNgwee * 0.7 + k(out.priceKwacha) * 0.3) : k(out.priceKwacha);
  return {
    title: String(out.title ?? "").slice(0, 80), description: String(out.description ?? "").slice(0, 1200), category,
    condition: CONDITIONS.includes(out.condition) ? out.condition : "Good", tags: (out.tags ?? []).slice(0, 6).map(String),
    priceNgwee, priceLowNgwee: market.lowNgwee ?? k(out.priceLowKwacha), priceHighNgwee: market.highNgwee ?? k(out.priceHighKwacha),
    comparables: market.samples ?? 0, priceScope: market.scope,
  };
});

/** Price suggestion alone (manual-entry fallback still shows the market range). */
export const listing_priceHint = onCall(async (req) => {
  const { uid } = requireUser(req, "SELLER", "AGENT");
  const seller = (await db.doc(`users/${uid}`).get()).data() ?? {};
  return marketPrice(str(req.data?.category, "category"), seller.province);
});

/**
 * Posts a listing as pending_review; onListingWrite auto-approves it if no content-policy flag is raised.
 * Media is copied from the private upload area to public listing-media/{listingId}/ in the seller's chosen order.
 */
export const listing_publish = onCall({ timeoutSeconds: 120 }, async (req) => {
  const { uid, role } = requireUser(req, "SELLER", "AGENT");
  const d = req.data ?? {};
  const sellerUid = role === "AGENT" ? str(d.sellerUid, "seller") : uid;
  if (role === "AGENT") {
    const link = await db.collection("agentLinks").where("agentUid", "==", uid).where("sellerUid", "==", sellerUid).where("status", "==", "ACTIVE").limit(1).get();
    if (link.empty) throw new HttpsError("permission-denied", "You don't manage this seller.");
  }
  const seller = (await db.doc(`users/${sellerUid}`).get()).data() ?? {};
  const mediaType = d.mediaType === "video" ? "video" : "photo";
  const video = typeof d.videoPath === "string" ? d.videoPath : null;
  const photos = ((d.photoPaths ?? []) as string[]).slice(0, mediaType === "photo" ? 4 : 1);
  const thumb = typeof d.thumbPath === "string" ? d.thumbPath : photos[0] ?? null;
  for (const p of [video, thumb, ...photos].filter(Boolean) as string[]) if (!ownsUpload(uid, p)) throw new HttpsError("permission-denied", "Invalid media.");
  if (mediaType === "video" && !video) throw new HttpsError("invalid-argument", "Record a video first.");
  if (mediaType === "photo" && !photos.length) throw new HttpsError("invalid-argument", "Add at least one photo.");
  const approved = seller.accountStatus === "ACTIVE";
  const { key: listingId, shard } = compositeKey("lst", sellerUid);

  const bucket = getStorage().bucket();
  const url = (p: string) => `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/${encodeURIComponent(p)}?alt=media`;
  const copy = async (src: string, name: string) => { const dest = `listing-media/${listingId}/${name}`; await bucket.file(src.replace(/^\//, "")).copy(bucket.file(dest)); return dest; };
  const media: { path: string; kind: string; order: number }[] = [];
  const videoDest = video ? await copy(video, "video.mp4") : null;
  if (videoDest) media.push({ path: videoDest, kind: "video", order: 0 });
  const photoDests: string[] = [];
  for (const [i, p] of photos.entries()) { const dst = await copy(p, `photo-${i + 1}.jpg`); photoDests.push(dst); media.push({ path: dst, kind: "photo", order: i }); }
  const thumbDest = thumb ? (photos.includes(thumb) ? photoDests[photos.indexOf(thumb)] : await copy(thumb, "thumb.jpg")) : null;

  const priceNgwee = Math.max(0, Math.round(Number(d.priceNgwee) || 0));
  const discountPct = Math.min(90, Math.max(0, Number(d.discountPct) || 0));
  const discountNgwee = Math.min(priceNgwee, Math.max(0, Math.round(Number(d.discountNgwee) || 0)));
  const status = d.asDraft || !approved ? "draft" : "pending_review";
  await db.doc(`listings/${listingId}`).set({
    sellerUid, sellerName: (await import("./shops")).businessName(seller), sellerVerified: seller.kycStatus === "VERIFIED",
    agentUid: role === "AGENT" ? uid : null, shard,
    title: str(d.title, "title", 80), description: String(d.description ?? "").slice(0, 1200), priceNgwee, discountPct, discountNgwee,
    category: CATEGORIES.includes(d.category) ? d.category : "Other", condition: CONDITIONS.includes(d.condition) ? d.condition : "Good",
    availability: AVAILABILITY.includes(d.availability) ? d.availability : "In Stock",
    province: String(d.province ?? seller.province ?? ""), district: String(d.district ?? seller.district ?? ""), location: String(d.location ?? seller.area ?? "").slice(0, 120),
    tags: ((d.tags ?? []) as string[]).slice(0, 8).map((t) => String(t).toLowerCase().slice(0, 30)),
    mediaType, videoUrl: videoDest ? url(videoDest) : null, photoUrls: photoDests.map(url), thumbUrl: thumbDest ? url(thumbDest) : null,
    durationMs: Number(d.durationMs) || null, aiUsed: !!d.aiUsed, aiSafe: d.aiSafe !== false,
    stats: { views: 0, likes: 0, shares: 0, enquiries: 0, saves: 0, notInterested: 0, watchMs: 0, watchTotalMs: 0, taps: 0, skips: 0 },
    sellerEngagementRate: 0, viralityScore: 0, status, stock: Math.max(0, Number(d.stock) || 0),
    createdAt: FieldValue.serverTimestamp(), publishedAt: null,
  });
  const b = db.batch();
  media.forEach((m) => b.set(db.doc(`listingMedia/med_${uuidv7()}_${listingId}`), { listingId, sellerUid, ...m, createdAt: FieldValue.serverTimestamp() }));
  await b.commit();
  // Listing Studio: produce the professional version in the background (originals stay live until it's ready).
  const cfg = await (await import("./config")).loadConfig() as Record<string, unknown>;
  if (cfg.studioEnabled !== false && cfg.studioAutoStart !== false)
    await (await import("./studio/studio")).startStudio(listingId, uid).catch((e) => logger.warn("studio start failed", String(e)));
  return { listingId, status };
});

/** Seller edits after posting (title, price, offer, availability, status). Text changes re-index via onListingWrite. */
export const listing_update = onCall(async (req) => {
  const { uid } = requireUser(req, "SELLER", "AGENT");
  const id = str(req.data?.listingId, "listing");
  const ref = db.doc(`listings/${id}`);
  const cur = (await ref.get()).data();
  if (!cur || (cur.sellerUid !== uid && cur.agentUid !== uid)) throw new HttpsError("not-found", "Listing not found.");
  const c = (req.data?.changes ?? {}) as Record<string, unknown>;
  const upd: Record<string, unknown> = { updatedAt: FieldValue.serverTimestamp() };
  if (typeof c.title === "string") upd.title = c.title.slice(0, 80);
  if (typeof c.description === "string") upd.description = c.description.slice(0, 1200);
  if (typeof c.priceNgwee === "number") upd.priceNgwee = Math.max(0, Math.round(c.priceNgwee));
  if (typeof c.discountPct === "number") upd.discountPct = Math.min(90, Math.max(0, c.discountPct));
  if (typeof c.availability === "string" && AVAILABILITY.includes(c.availability)) upd.availability = c.availability;
  if (typeof c.condition === "string" && CONDITIONS.includes(c.condition)) upd.condition = c.condition;
  if (Array.isArray(c.tags)) upd.tags = c.tags.slice(0, 8).map((t) => String(t).toLowerCase().slice(0, 30));
  if (typeof c.status === "string") {
    const allowed: Record<string, string[]> = { live: ["paused"], paused: ["live"], sold: ["live", "paused"], archived: ["live", "paused", "sold", "draft", "flagged"], pending_review: ["draft"] };
    if (!allowed[c.status]?.includes(cur.status)) throw new HttpsError("failed-precondition", `Can't change a ${cur.status} listing to ${c.status}.`);
    upd.status = c.status;
    if (c.status === "archived") upd.deletedAt = FieldValue.serverTimestamp();
  }
  await ref.update(upd);
  return { ok: true };
});
