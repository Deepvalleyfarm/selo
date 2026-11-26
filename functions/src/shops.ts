/**
 * Public shop profile: registered business name, logo/profile photo, banner, location and live products.
 * Buyers always see the business name (storeName) — never an ID. When a seller renames the business or changes
 * the logo/banner, every listing and the public store marker are updated.
 */
import { FieldValue } from "firebase-admin/firestore";
import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { DB_ID, db } from "./config";

export const businessName = (u: Record<string, any> | undefined) =>
  String(u?.storeName ?? u?.businessName ?? u?.fullName ?? "").trim() || "SeloNaChipa shop";

export const onSellerProfileChange = onDocumentUpdated({ document: "users/{uid}", database: DB_ID }, async (e) => {
  const before = e.data?.before.data() ?? {}, after = e.data?.after.data() ?? {};
  if (after.role !== "SELLER") return;
  const name = businessName(after);
  const changed = businessName(before) !== name || before.photoUrl !== after.photoUrl || before.coverUrl !== after.coverUrl;
  if (!changed) return;
  await db.doc(`stores/${e.params.uid}`).set({ name, photoUrl: after.photoUrl ?? null, bannerUrl: after.coverUrl ?? null, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  if (businessName(before) === name) return;
  const ls = await db.collection("listings").where("sellerUid", "==", e.params.uid).limit(500).get();
  const b = db.batch(); ls.docs.forEach((d) => b.update(d.ref, { sellerName: name })); await b.commit();
});

/** Shop page for buyers (from "Shops near you", a feed card or a listing). */
export const shop_profile = onCall(async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Please log in again.");
  const uid = String(req.data?.sellerUid ?? "").slice(0, 128);
  const u = (await db.doc(`users/${uid}`).get()).data();
  if (!u || u.role !== "SELLER") throw new HttpsError("not-found", "Shop not found.");
  const store = (await db.doc(`stores/${uid}`).get()).data() ?? {};
  const ls = await db.collection("listings").where("sellerUid", "==", uid).where("status", "==", "live").orderBy("publishedAt", "desc").limit(60).get()
    .catch(() => db.collection("listings").where("sellerUid", "==", uid).where("status", "==", "live").limit(60).get());
  return {
    sellerUid: uid, name: businessName(u), verified: u.kycStatus === "VERIFIED", photoUrl: u.photoUrl ?? null, bannerUrl: u.coverUrl ?? null,
    address: store.address ?? u.location?.address ?? u.area ?? "", district: u.district ?? "", memberSince: u.memberSince ?? null,
    listings: ls.docs.map((d) => { const x = d.data(); return { promo: x.promo && (x.promo.endsAt == null || x.promo.endsAt > Date.now()) ? x.promo : null, id: d.id, title: x.title, priceNgwee: x.priceNgwee ?? 0, discountPct: x.discountPct ?? 0,
      discountNgwee: x.discountNgwee ?? 0, thumbUrl: x.thumbUrl ?? null, videoUrl: x.videoUrl ?? null, mediaType: x.mediaType ?? "photo", photoUrls: x.photoUrls ?? [],
      category: x.category ?? "", stock: x.stock ?? 0, sellerName: businessName(u), sellerUid: uid, district: x.district ?? "", province: x.province ?? "",
      description: x.description ?? "", aiEnhanced: x.aiEnhanced === true, aiGeneratedMotion: x.aiGeneratedMotion === true }; }),
  };
});
