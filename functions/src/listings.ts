import { GoogleGenAI } from "@google/genai";
import { getStorage } from "firebase-admin/storage";
import { FieldValue } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { GEMINI_API_KEY, GEMINI_MODEL, db } from "./config";
import { requireUser, str } from "./util";

const CATEGORIES = ["Fresh produce", "Groceries", "Fast food", "Electronics", "Fashion", "Home", "Beauty", "Hardware", "Baby", "Other"];

/**
 * AI listing pipeline (PRD §5.3): reads the uploaded video/photos, asks Gemini for a title,
 * description, ZMW price range, category and tags, and compares against live listings nearby.
 */
export const listing_runAiPipeline = onCall({ secrets: [GEMINI_API_KEY], timeoutSeconds: 120, memory: "1GiB" }, async (req) => {
  const { uid } = requireUser(req, "SELLER", "AGENT");
  const paths = ((req.data?.mediaPaths ?? []) as string[]).slice(0, 11);
  const prompt = typeof req.data?.prompt === "string" ? req.data.prompt.slice(0, 600) : "";
  if (!paths.length && prompt.length < 8) throw new HttpsError("invalid-argument", "Add a video, photos or a short description.");
  if (paths.some((p) => !p.replace(/^\//, "").startsWith(`listing-uploads/${uid}/`))) throw new HttpsError("permission-denied", "Invalid media.");

  const bucket = getStorage().bucket();
  const parts: { inlineData?: { mimeType: string; data: string }; text?: string }[] = [];
  for (const p of paths) {
    const f = bucket.file(p.replace(/^\//, ""));
    const [meta] = await f.getMetadata();
    if (Number(meta.size) > 18 * 1024 * 1024) continue; // larger videos are summarised from their photos only
    const [buf] = await f.download();
    parts.push({ inlineData: { mimeType: String(meta.contentType ?? (p.endsWith(".mp4") ? "video/mp4" : "image/jpeg")), data: buf.toString("base64") } });
  }
  const seller = (await db.doc(`users/${uid}`).get()).data() ?? {};
  parts.push({ text: `You write product listings for SeloNaChipa, a Zambian marketplace. Seller location: ${seller.area ?? "Lusaka"}.
Seller notes: "${prompt}".
Return JSON only: {"title": string (max 60 chars), "description": string (2-3 sentences, plain English), "category": one of ${JSON.stringify(CATEGORIES)},
"tags": string[] (3-5 lowercase words), "priceLowKwacha": number, "priceHighKwacha": number, "priceKwacha": number, "safe": boolean (false if illegal or unsafe goods)}.
Prices are in Zambian Kwacha for the Lusaka market.` });

  const ai = new GoogleGenAI({ apiKey: GEMINI_API_KEY.value() });
  const res = await ai.models.generateContent({ model: GEMINI_MODEL.value(), contents: [{ role: "user", parts }],
    config: { responseMimeType: "application/json", temperature: 0.4 } });
  let out: { title: string; description: string; category: string; tags: string[]; priceLowKwacha: number; priceHighKwacha: number; priceKwacha: number; safe: boolean };
  try { out = JSON.parse(res.text ?? "{}"); } catch { throw new HttpsError("internal", "The AI couldn't read your media. Try again or add a description."); }
  if (out.safe === false) {
    await db.collection("moderationQueue").add({ uid, paths, reason: "AI flagged unsafe content", createdAt: FieldValue.serverTimestamp() });
    throw new HttpsError("failed-precondition", "This item can't be listed. Our team will review it.");
  }
  const comparables = (await db.collection("listings").where("status", "==", "live").where("category", "==", out.category).limit(100).get()).size;
  const k = (n: number) => Math.round((Number(n) || 0) * 100);
  return { title: String(out.title ?? "").slice(0, 80), description: String(out.description ?? "").slice(0, 1000),
    category: CATEGORIES.includes(out.category) ? out.category : "Other", tags: (out.tags ?? []).slice(0, 6).map(String),
    priceNgwee: k(out.priceKwacha), priceLowNgwee: k(out.priceLowKwacha), priceHighNgwee: k(out.priceHighKwacha), comparables };
});

/** Publishes (or saves as draft). Live listings first go through moderation if media was flagged. */
export const listing_publish = onCall(async (req) => {
  const { uid, role } = requireUser(req, "SELLER", "AGENT");
  const d = req.data ?? {};
  const sellerUid = role === "AGENT" ? str(d.sellerUid, "seller") : uid;
  if (role === "AGENT") {
    const link = await db.collection("agentLinks").where("agentUid", "==", uid).where("sellerUid", "==", sellerUid).where("status", "==", "ACTIVE").limit(1).get();
    if (link.empty) throw new HttpsError("permission-denied", "You don't manage this seller.");
  }
  const seller = (await db.doc(`users/${sellerUid}`).get()).data() ?? {};
  const approved = seller.accountStatus === "ACTIVE";
  const status = d.asDraft || !approved ? "draft" : "live";
  const ref = await db.collection("listings").add({
    sellerUid, sellerName: seller.storeName ?? seller.fullName ?? "", agentUid: role === "AGENT" ? uid : null,
    title: str(d.title, "title", 80), description: String(d.description ?? "").slice(0, 1000), priceNgwee: Math.max(0, Math.round(Number(d.priceNgwee) || 0)),
    category: String(d.category ?? "Other"), tags: (d.tags ?? []).slice(0, 6), mediaPaths: d.mediaPaths ?? [], stock: Math.max(0, Number(d.stock) || 0),
    zoneName: seller.area ?? "", status, viewCount: 0, saveCount: 0, createdAt: FieldValue.serverTimestamp(),
    publishedAt: status === "live" ? FieldValue.serverTimestamp() : null,
  });
  if (status === "live") await db.doc(`sellerStats/${sellerUid}`).set({ liveListings: FieldValue.increment(1) }, { merge: true });
  return { listingId: ref.id, status };
});
