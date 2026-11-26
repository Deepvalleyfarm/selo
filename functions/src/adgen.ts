/**
 * AI Ad Generator. The seller uploads one product photo; SeloNaChipa returns:
 *  - a promotional image in two sizes: 9:16 for WhatsApp Status / TikTok and 1:1 for Facebook. It's built by Cloudinary
 *    around the seller's real photo (brand gradient, headline, price badge, "Order on SeloNaChipa"), so the product is
 *    never redrawn or changed by AI;
 *  - a product description;
 *  - short ad copy for WhatsApp Status, Facebook and TikTok (with hashtags), written by Gemini from the photo.
 * Gemini sees the photo plus whatever the seller typed (name, price, offer). It's told never to invent prices,
 * guarantees or features it can't see.
 */
import { createHash } from "node:crypto";
import { GoogleGenAI } from "@google/genai";
import { getStorage } from "firebase-admin/storage";
import { FieldValue } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { GEMINI_API_KEY, GEMINI_MODEL, db } from "./config";
import { CLOUDINARY_URL, cloudinaryCreds, cloudinaryUpload } from "./studio/providers";
import { requireUser } from "./util";

export interface AdCopy { productName: string; headline: string; description: string; whatsapp: string; facebook: string; tiktok: string; hashtags: string[] }

/** Cloudinary text layers need commas and slashes double-escaped. */
export const layerText = (t: string) => encodeURIComponent(t.replace(/[\r\n]+/g, " ").slice(0, 60)).replace(/%2C/g, "%252C").replace(/%2F/g, "%252F");

/** Delivery URL for the composed ad around the uploaded product photo. */
export function adImageUrl(cloud: string, publicId: string, o: { w: number; h: number; headline: string; price: string | null; offer: string | null }) {
  const big = o.h > o.w; const hs = big ? 78 : 60, ps = big ? 84 : 64;
  const t = [
    `w_${o.w},h_${o.h},c_pad,b_auto:predominant_gradient:2:diagonal_desc,q_auto,f_jpg`,
    `l_text:Arial_${hs}_bold:${layerText(o.headline)},co_rgb:FFFFFF,b_rgb:00000099,w_${o.w - 120},c_fit/fl_layer_apply,g_north,y_${big ? 150 : 50}`,
    ...(o.offer ? [`l_text:Arial_${Math.round(hs * 0.7)}_bold:${layerText(o.offer)},co_rgb:111111,b_rgb:FFD54F,w_${o.w - 200},c_fit/fl_layer_apply,g_north,y_${big ? 330 : 150}`] : []),
    ...(o.price ? [`l_text:Arial_${ps}_bold:${layerText(o.price)},co_rgb:FFFFFF,b_rgb:C8102E/fl_layer_apply,g_south_east,x_50,y_${big ? 300 : 130}`] : []),
    `l_text:Arial_${big ? 46 : 36}_bold:${layerText("Order on SeloNaChipa · Pay with Mobile Money")},co_rgb:FFFFFF,b_rgb:C8102E,w_${o.w},c_fit/fl_layer_apply,g_south,y_${big ? 120 : 30}`,
  ].join("/");
  return `https://res.cloudinary.com/${cloud}/image/upload/${t}/${publicId}`;
}

const clip = (s: unknown, n: number) => String(s ?? "").replace(/\s+/g, " ").trim().slice(0, n);
export function cleanCopy(j: any, fallbackName: string): AdCopy {
  const tags = (Array.isArray(j?.hashtags) ? j.hashtags : []).map((h: unknown) => `#${String(h).replace(/[^\p{L}\p{N}_]/gu, "")}`).filter((h: string) => h.length > 2).slice(0, 8);
  return { productName: clip(j?.productName || fallbackName, 60), headline: clip(j?.headline, 40), description: clip(j?.description, 700),
    whatsapp: clip(j?.whatsapp, 300), facebook: clip(j?.facebook, 900), tiktok: clip(j?.tiktok, 220), hashtags: tags };
}

export const ad_generate = onCall({ secrets: [GEMINI_API_KEY, CLOUDINARY_URL], timeoutSeconds: 120, memory: "512MiB" }, async (req) => {
  const { uid } = requireUser(req, "SELLER", "AGENT");
  const path = String(req.data?.photoPath ?? "");
  if (!path.startsWith(`adgen/${uid}/`)) throw new HttpsError("invalid-argument", "Upload a product photo first.");
  const name = clip(req.data?.productName, 60), offer = clip(req.data?.offer, 40);
  const priceNgwee = Math.max(0, Math.round(Number(req.data?.priceNgwee) || 0));
  const price = priceNgwee > 0 ? `K ${(priceNgwee / 100).toLocaleString("en-ZM", { maximumFractionDigits: 2 })}` : null;

  // Fair use: 20 ads per seller per day.
  const day = new Date().toISOString().slice(0, 10);
  const usage = db.doc(`adgenUsage/${uid}_${day}`);
  const used = await db.runTransaction(async (tx) => { const n = ((await tx.get(usage)).get("n") ?? 0) as number; if (n >= 20) return n; tx.set(usage, { n: n + 1, uid, day }, { merge: true }); return n; });
  if (used >= 20) throw new HttpsError("resource-exhausted", "You've made 20 ads today. Try again tomorrow.");

  const [bytes] = await getStorage().bucket().file(path).download().catch(() => { throw new HttpsError("not-found", "We couldn't read that photo. Upload it again."); });
  if (bytes.length > 10 * 1024 * 1024) throw new HttpsError("invalid-argument", "That photo is too large (10 MB max).");
  const seller = (await db.doc(`users/${uid}`).get()).data() ?? {};
  const shop = clip(seller.storeName ?? seller.fullName, 50);

  const prompt = `You write short, honest adverts for small Zambian sellers on SeloNaChipa (a mobile-money marketplace with escrow and rider delivery).
Look at the product photo. Seller's details — product name: "${name || "(not given)"}", price: "${price ?? "(not given)"}", offer: "${offer || "(none)"}", shop: "${shop}".
Rules: describe only what you can see or what the seller gave. Never invent prices, discounts, brands, warranties or health claims. Use simple English a Zambian buyer
uses every day; a little Bemba/Nyanja flavour is fine ("Shani!", "Muli bwanji") but keep it clear. Mention paying with mobile money and delivery to the door.
Return JSON only:
{"productName": "short product name", "headline": "max 5 words, catchy", "description": "60-90 words product description for the listing",
 "whatsapp": "WhatsApp Status text, max 180 characters, 1-3 emojis, include the price if given and 'Order on SeloNaChipa'",
 "facebook": "Facebook post, 2 short paragraphs, max 600 characters, ends with a call to action",
 "tiktok": "TikTok caption, max 150 characters, punchy", "hashtags": ["5 to 8 hashtags without #, e.g. Lusaka, ZambiaBusiness"]}`;
  const ai = new GoogleGenAI({ apiKey: GEMINI_API_KEY.value() });
  const r = await ai.models.generateContent({ model: GEMINI_MODEL.value(), config: { responseMimeType: "application/json", temperature: 0.7 },
    contents: [{ role: "user", parts: [{ inlineData: { mimeType: "image/jpeg", data: bytes.toString("base64") } }, { text: prompt }] }] })
    .catch(() => { throw new HttpsError("unavailable", "The AI writer is busy. Try again in a minute."); });
  let copy: AdCopy;
  try { copy = cleanCopy(JSON.parse(r.text ?? "{}"), name); } catch { throw new HttpsError("unavailable", "The AI writer returned something unexpected. Try again."); }
  if (!copy.headline) copy.headline = copy.productName || "New in store";

  const creds = cloudinaryCreds();
  if (!creds) throw new HttpsError("failed-precondition", "Image service isn't configured yet.");
  const publicId = `selo/adgen/${uid}/${createHash("sha256").update(path).digest("hex").slice(0, 16)}`;
  await cloudinaryUpload(`data:image/jpeg;base64,${bytes.toString("base64")}`, { publicId, resourceType: "image", overwrite: true });
  const story = adImageUrl(creds.cloud, publicId, { w: 1080, h: 1920, headline: copy.headline, price, offer: offer || null });
  const square = adImageUrl(creds.cloud, publicId, { w: 1080, h: 1080, headline: copy.headline, price, offer: offer || null });

  const ref = await db.collection("adCreatives").add({ uid, sourcePath: path, ...copy, price, offer: offer || null, storyUrl: story, squareUrl: square,
    createdAt: FieldValue.serverTimestamp() });
  return { id: ref.id, storyUrl: story, squareUrl: square, ...copy };
});
