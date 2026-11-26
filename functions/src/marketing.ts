/**
 * Marketing Centre: sellers run discount campaigns, Buy One Get One Free, limited-time offers and product bundles.
 * Prices are always worked out on the server (pricing.ts) at quote and checkout. Each live campaign is mirrored onto its
 * listings as `promo` so product cards show the deal; a scheduler starts and ends campaigns on time.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { db } from "./config";
import { type Campaign, campaignProblem, isLive, promoLabel } from "./pricing";
import { requireUser } from "./util";

const toMs = (v: unknown) => (v instanceof Timestamp ? v.toMillis() : typeof v === "number" ? v : null);
export function toCampaign(id: string, x: Record<string, any>): Campaign {
  return { id, sellerUid: x.sellerUid, type: x.type, title: x.title, listingIds: x.listingIds ?? [], status: x.status, discountPct: x.discountPct ?? undefined,
    bundlePriceNgwee: x.bundlePriceNgwee ?? undefined, startsAt: toMs(x.startsAt) ?? 0, endsAt: toMs(x.endsAt), maxUnits: x.maxUnits ?? null, unitsSold: x.unitsSold ?? 0 };
}

export async function sellerCampaigns(sellerUid: string): Promise<Campaign[]> {
  const q = await db.collection("campaigns").where("sellerUid", "==", sellerUid).where("status", "==", "ACTIVE").limit(50).get();
  return q.docs.map((d) => toCampaign(d.id, d.data()));
}

/** Mirrors the best live deal onto each of the seller's listings (or clears it). */
export async function syncPromos(sellerUid: string, nowMs = Date.now()) {
  const live = (await sellerCampaigns(sellerUid)).filter((c) => isLive(c, nowMs));
  const ls = await db.collection("listings").where("sellerUid", "==", sellerUid).limit(500).get();
  const b = db.batch(); let n = 0;
  for (const d of ls.docs) {
    const c = live.filter((c) => c.listingIds.includes(d.id)).sort((a, z) => (z.discountPct ?? 50) - (a.discountPct ?? 50))[0];
    const promo = c ? { campaignId: c.id, type: c.type, label: promoLabel(c), endsAt: c.endsAt, bundleWith: c.type === "BUNDLE" ? c.listingIds.filter((x) => x !== d.id) : [] } : null;
    if (JSON.stringify(d.get("promo") ?? null) !== JSON.stringify(promo)) { b.update(d.ref, { promo }); n++; }
  }
  if (n) await b.commit();
}

export const campaign_save = onCall(async (req) => {
  const { uid } = requireUser(req, "SELLER");
  const d = req.data ?? {};
  const now = Date.now();
  const c: Partial<Campaign> = {
    type: d.type, title: String(d.title ?? "").trim().slice(0, 60), listingIds: [...new Set<string>((Array.isArray(d.listingIds) ? d.listingIds : []).map(String))].slice(0, 20),
    discountPct: d.discountPct != null ? Math.round(Number(d.discountPct)) : undefined,
    bundlePriceNgwee: d.bundlePriceNgwee != null ? Math.round(Number(d.bundlePriceNgwee)) : undefined,
    startsAt: Number(d.startsAt) > now ? Number(d.startsAt) : now, endsAt: d.endsAt != null ? Number(d.endsAt) : null,
    maxUnits: d.maxUnits != null && Number(d.maxUnits) > 0 ? Math.floor(Number(d.maxUnits)) : null,
  };
  const problem = campaignProblem(c, now);
  if (problem) throw new HttpsError("invalid-argument", problem);
  const listings = await db.getAll(...c.listingIds!.map((id) => db.doc(`listings/${id}`)));
  if (listings.some((l) => !l.exists || l.get("sellerUid") !== uid)) throw new HttpsError("permission-denied", "You can only promote your own products.");
  if (c.type === "BUNDLE") {
    const sum = listings.reduce((s, l) => s + (l.get("priceNgwee") ?? 0), 0);
    if (c.bundlePriceNgwee! >= sum) throw new HttpsError("invalid-argument", `The bundle price must be below K ${(sum / 100).toFixed(2)} (the products bought separately).`);
  }
  const id = typeof d.id === "string" && d.id ? d.id : db.collection("campaigns").doc().id;
  const ref = db.doc(`campaigns/${id}`);
  const prev = (await ref.get()).data();
  if (prev && prev.sellerUid !== uid) throw new HttpsError("permission-denied", "Not your campaign.");
  await ref.set({ ...c, sellerUid: uid, status: prev?.status === "ENDED" ? "ENDED" : "ACTIVE",
    startsAt: Timestamp.fromMillis(c.startsAt!), endsAt: c.endsAt ? Timestamp.fromMillis(c.endsAt) : null,
    unitsSold: prev?.unitsSold ?? 0, revenueNgwee: prev?.revenueNgwee ?? 0, orders: prev?.orders ?? 0,
    updatedAt: FieldValue.serverTimestamp(), ...(prev ? {} : { createdAt: FieldValue.serverTimestamp() }) }, { merge: true });
  await syncPromos(uid);
  return { id };
});

export const campaign_setStatus = onCall(async (req) => {
  const { uid } = requireUser(req, "SELLER");
  const status = String(req.data?.status ?? "");
  if (!["ACTIVE", "PAUSED", "ENDED"].includes(status)) throw new HttpsError("invalid-argument", "Unknown status.");
  const ref = db.doc(`campaigns/${String(req.data?.id ?? "")}`);
  const c = (await ref.get()).data();
  if (!c || c.sellerUid !== uid) throw new HttpsError("not-found", "Campaign not found.");
  if (c.status === "ENDED" && status !== "ENDED") throw new HttpsError("failed-precondition", "An ended campaign can't be restarted. Create a new one.");
  await ref.update({ status, updatedAt: FieldValue.serverTimestamp(), ...(status === "ENDED" ? { endedAt: FieldValue.serverTimestamp() } : {}) });
  await syncPromos(uid);
  return { ok: true };
});

export const campaign_list = onCall(async (req) => {
  const { uid } = requireUser(req, "SELLER");
  const q = await db.collection("campaigns").where("sellerUid", "==", uid).orderBy("createdAt", "desc").limit(50).get();
  const now = Date.now();
  return { campaigns: q.docs.map((d) => { const x = d.data(); const c = toCampaign(d.id, x);
    return { ...c, label: promoLabel(c), live: isLive(c, now), revenueNgwee: x.revenueNgwee ?? 0, orders: x.orders ?? 0 }; }) };
});

/** Records sales against campaigns once an order is paid (shown in the Marketing Centre and monthly report). */
export async function recordCampaignSales(items: { promo?: string | null; qty: number; lineNgwee?: number }[], orderId: string) {
  const by = new Map<string, { units: number; revenue: number }>();
  for (const i of items) if (i.promo) { const v = by.get(i.promo) ?? { units: 0, revenue: 0 }; v.units += i.qty; v.revenue += i.lineNgwee ?? 0; by.set(i.promo, v); }
  await Promise.all([...by].map(([id, v]) => db.doc(`campaigns/${id}`).update({ unitsSold: FieldValue.increment(v.units), revenueNgwee: FieldValue.increment(v.revenue),
    orders: FieldValue.increment(1), lastOrderId: orderId }).catch(() => undefined)));
}

/** Every 15 minutes: start scheduled campaigns, end expired ones, refresh the deal badges. */
export const scheduled_campaigns = onSchedule({ schedule: "every 15 minutes" }, async () => {
  const now = Date.now();
  const active = await db.collection("campaigns").where("status", "==", "ACTIVE").limit(500).get();
  const sellers = new Set<string>();
  for (const d of active.docs) {
    const c = toCampaign(d.id, d.data());
    if ((c.endsAt != null && c.endsAt <= now) || (c.maxUnits != null && (c.unitsSold ?? 0) >= c.maxUnits)) {
      await d.ref.update({ status: "ENDED", endedAt: FieldValue.serverTimestamp() }); sellers.add(c.sellerUid);
    } else if (c.startsAt <= now && c.startsAt > now - 16 * 60_000) sellers.add(c.sellerUid);
  }
  for (const s of sellers) await syncPromos(s, now);
});
