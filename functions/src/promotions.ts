/**
 * Paid Boosts and Feed Stories (PRD v6.2) — extra platform revenue.
 * The seller pays with mobile money; nothing is shown to buyers until the Lipila webhook confirms the collection.
 * Every confirmed payment is written to platformRevenue for the back office.
 */
import { FieldValue, Timestamp, type DocumentSnapshot } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { LIPILA_API_KEY, db, loadConfig } from "./config";
import { collectionFailureReason, normaliseMsisdn, operatorLabel } from "./domain";
import { lipila } from "./lipila";
import {
  boostScore, cleanTargeting, estimateAudience, matchesTargeting, PROMO_DAYS, promoPrice, REACHES, underCap,
  type BuyerSignals, type PromoType, type Reach, type Targeting,
} from "./promo-rules";
import { notify, requireUser } from "./util";

const promoRef = (id: string) => db.doc(`promotions/${id}`);
const refFor = (type: PromoType, id: string, attempt: number) => `SELO-${type === "story" ? "STY" : "BST"}-${id}${attempt > 0 ? `-R${attempt}` : ""}`;
const DAY = 86_400_000;

function parseRequest(d: Record<string, unknown>) {
  const type: PromoType = d.type === "story" ? "story" : "boost";
  const reach = (REACHES.includes(d.reach as Reach) ? d.reach : "LOCAL") as Reach;
  const days = type === "story" ? 1 : PROMO_DAYS.includes(Number(d.days)) ? Number(d.days) : 3;
  return { type, reach, days, targeting: cleanTargeting(d.targeting, reach) };
}

/* ------------------------------------------------------------------ estimate */

async function count(q: FirebaseFirestore.Query) { return (await q.count().get()).data().count; }

export const promo_estimate = onCall(async (req) => {
  requireUser(req, "SELLER", "AGENT");
  const { reach, targeting } = parseRequest(req.data ?? {});
  return { audience: await audienceFor(targeting), reach };
});

async function audienceFor(t: Targeting) {
  const p = db.collection("buyerProfiles");
  const [total, prov, dist, age, interest] = await Promise.all([
    count(p),
    Promise.all(t.provinces.map(async (x) => [x, await count(p.where("province", "==", x))] as const)),
    Promise.all(t.districts.map(async (x) => [x, await count(p.where("district", "==", x))] as const)),
    Promise.all(t.ageBands.map(async (x) => [x, await count(p.where("ageBand", "==", x))] as const)),
    Promise.all(t.interests.map(async (x) => [x, await count(p.where(`catScore.${x}`, ">", 0))] as const)),
  ]);
  return estimateAudience(t, { total, byProvince: Object.fromEntries(prov), byDistrict: Object.fromEntries(dist),
    byAgeBand: Object.fromEntries(age), byInterest: Object.fromEntries(interest) });
}

/* -------------------------------------------------------------------- create */

async function collect(id: string, type: PromoType, attempt: number, priceNgwee: number, msisdn: string, title: string) {
  const ref = refFor(type, id, attempt);
  const res = await lipila.collect(ref, priceNgwee, msisdn, `SeloNaChipa ${type === "story" ? "Feed Story" : "Boost"}: ${title}`.slice(0, 60))
    .catch(() => ({ ok: false, status: 0 }));
  if (!res.ok) {
    await promoRef(id).update({ status: "PAYMENT_FAILED", failureReason: `${operatorLabel(msisdn)} couldn't start the payment. Try again or use another number.`,
      collectionRef: ref, updatedAt: FieldValue.serverTimestamp() });
    return;
  }
  await promoRef(id).update({ collectionRef: ref, status: "PENDING_PAYMENT", failureReason: null, updatedAt: FieldValue.serverTimestamp() });
}

export const promo_create = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "SELLER", "AGENT");
  const d = req.data ?? {};
  const listingId = typeof d.listingId === "string" ? d.listingId : "";
  const wallet = normaliseMsisdn(d.walletMsisdn);
  if (!wallet) throw new HttpsError("invalid-argument", "Enter an Airtel Money, MTN Money or Zamtel Money number.");
  const l = await db.doc(`listings/${listingId}`).get();
  if (!l.exists) throw new HttpsError("not-found", "Listing not found.");
  if (l.get("sellerUid") !== uid && l.get("agentUid") !== uid) throw new HttpsError("permission-denied", "You can only promote your own listings.");
  if (l.get("status") !== "live") throw new HttpsError("failed-precondition", "Only live listings can be promoted.");
  const { type, reach, days, targeting } = parseRequest(d);
  const active = await db.collection("promotions").where("listingId", "==", listingId).where("type", "==", type).where("status", "==", "ACTIVE").limit(1).get();
  if (!active.empty) throw new HttpsError("already-exists", `This listing already has an active ${type === "story" ? "Feed Story" : "Boost"}.`);
  const cfg = await loadConfig();
  const priceNgwee = promoPrice(type, reach, days, cfg);
  const ref = promoRef(db.collection("promotions").doc().id);
  await ref.set({
    type, listingId, listingTitle: l.get("title") ?? "", sellerUid: l.get("sellerUid"), sellerName: l.get("sellerName") ?? "", createdBy: uid,
    reach, days, targeting, priceNgwee, walletMsisdn: wallet, attempt: 0, status: "PENDING_PAYMENT", failureReason: null,
    estimatedReach: await audienceFor(targeting).catch(() => 0), stats: { impressions: 0, clicks: 0, likes: 0, enquiries: 0 },
    category: l.get("category") ?? "", province: l.get("province") ?? "", createdAt: FieldValue.serverTimestamp(),
  });
  await collect(ref.id, type, 0, priceNgwee, wallet, l.get("title") ?? "");
  return { promoId: ref.id, priceNgwee };
});

export const promo_retryPayment = onCall({ secrets: [LIPILA_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "SELLER", "AGENT");
  const wallet = normaliseMsisdn(req.data?.walletMsisdn);
  if (!wallet) throw new HttpsError("invalid-argument", "Enter an Airtel Money, MTN Money or Zamtel Money number.");
  const id = String(req.data?.promoId ?? "");
  const p = (await promoRef(id).get()).data();
  if (!p || (p.sellerUid !== uid && p.createdBy !== uid)) throw new HttpsError("not-found", "Promotion not found.");
  if (p.status !== "PAYMENT_FAILED") throw new HttpsError("failed-precondition", "This promotion isn't waiting for a new payment.");
  const attempt = (p.attempt ?? 0) + 1;
  if (attempt > 5) throw new HttpsError("resource-exhausted", "Too many attempts. Start a new promotion later.");
  await promoRef(id).update({ attempt, walletMsisdn: wallet });
  await collect(id, p.type, attempt, p.priceNgwee, wallet, p.listingTitle);
  return { ok: true };
});

/* ------------------------------------------------------------------- webhook */

/** Called by lipila_webhook for SELO-BST-/SELO-STY- references. A success for a superseded attempt is refunded. */
export async function onPromoCollection(reference: string, ok: boolean, status: string, message?: string) {
  const m = /^SELO-(BST|STY)-([A-Za-z0-9]+)(?:-R(\d+))?$/.exec(reference);
  if (!m) return;
  const id = m[2];
  const snap = await promoRef(id).get();
  const p = snap.data();
  if (!p) return;
  const current = p.collectionRef === reference;
  if (!ok) {
    if (current && p.status === "PENDING_PAYMENT") {
      const reason = collectionFailureReason(status, message, p.walletMsisdn);
      await promoRef(id).update({ status: "PAYMENT_FAILED", failureReason: reason, updatedAt: FieldValue.serverTimestamp() });
      await notify(p.sellerUid, "Promotion payment didn't go through", `${reason} Pay with another number to start it.`, "PROMO_PAYMENT_FAILED", `selo://seller/promotions/${id}`);
    }
    return;
  }
  if (p.status === "ACTIVE" || p.status === "ENDED") {
    // a second confirmed payment for the same promotion: give it back
    const { disburse } = await import("./escrow");
    await disburse(`SELO-REF-${reference}`, "promo_refund", p.sellerUid, p.priceNgwee, p.walletMsisdn);
    return;
  }
  const now = Date.now();
  await db.runTransaction(async (tx) => {
    tx.update(promoRef(id), { status: "ACTIVE", paidRef: reference, paidAt: FieldValue.serverTimestamp(), failureReason: null,
      startsAt: Timestamp.fromMillis(now), endsAt: Timestamp.fromMillis(now + p.days * DAY), updatedAt: FieldValue.serverTimestamp() });
    tx.set(db.doc(`platformRevenue/rev_${reference}`), { source: p.type === "story" ? "feed_story" : "boost", promoId: id, listingId: p.listingId,
      sellerUid: p.sellerUid, amountNgwee: p.priceNgwee, operator: operatorLabel(p.walletMsisdn), lipilaRef: reference, reach: p.reach, days: p.days,
      at: FieldValue.serverTimestamp() });
    tx.set(db.doc(`listings/${p.listingId}`), { [p.type === "story" ? "storyPromoId" : "boostPromoId"]: id }, { merge: true });
  });
  {
    const { issueReceipt } = await import("./payments/receipts");
    const seller = (await db.doc(`users/${p.sellerUid}`).get()).data() ?? {};
    await issueReceipt({ reference, kind: p.type === "story" ? "story" : "boost", refId: id, payerUid: p.sellerUid, payerName: seller.storeName ?? seller.fullName ?? "",
      payerMsisdn: seller.msisdn ?? "", onBehalfOf: { type: "selonachipa", name: "SeloNaChipa" },
      lines: [{ label: `${p.type === "story" ? "Feed Story (24 h)" : `Boost · ${p.reach} · ${p.days} day${p.days === 1 ? "" : "s"}`}: ${p.listingTitle}`, amountNgwee: p.priceNgwee }],
      totalNgwee: p.priceNgwee, walletMsisdn: p.walletMsisdn ?? "" }).catch(() => undefined);
  }
  await notify(p.sellerUid, p.type === "story" ? "Your Feed Story is live" : "Your Boost is live",
    `${p.listingTitle} is now shown to matching buyers for ${p.type === "story" ? "24 hours" : `${p.days} day${p.days === 1 ? "" : "s"}`}.`, "PROMO_ACTIVE", `selo://seller/promotions/${id}`);
}

/* --------------------------------------------------------- serving to buyers */

export interface ActivePromo { id: string; type: PromoType; listingId: string; sellerUid: string; targeting: Targeting; endsAt: number }

let activeCache: { at: number; promos: ActivePromo[] } | null = null;
export async function activePromotions(): Promise<ActivePromo[]> {
  if (activeCache && Date.now() - activeCache.at < 60_000) return activeCache.promos;
  const s = await db.collection("promotions").where("status", "==", "ACTIVE").limit(500).get();
  const now = Date.now();
  const promos = s.docs.map((d) => ({ id: d.id, type: d.get("type"), listingId: d.get("listingId"), sellerUid: d.get("sellerUid"),
    targeting: d.get("targeting"), endsAt: (d.get("endsAt") as Timestamp | undefined)?.toMillis() ?? 0 }) as ActivePromo).filter((p) => p.endsAt > now);
  activeCache = { at: Date.now(), promos };
  return promos;
}

/** Turns a buyerProfiles doc into the signals used for targeting; catScore counts become 0..1 affinities. */
export function buyerSignals(prof: Record<string, any>, following: Set<string>, sellerUid?: string): BuyerSignals {
  const raw = (prof.catScore ?? {}) as Record<string, number>;
  const max = Math.max(1, ...Object.values(raw).map(Number));
  return { ageBand: prof.ageBand ?? undefined, province: prof.province ?? undefined, district: prof.district ?? undefined,
    catAffinity: Object.fromEntries(Object.entries(raw).map(([k, v]) => [k, Number(v) / max])), recentSearches: (prof.recentSearches ?? []).slice(-10),
    following: sellerUid ? following.has(sellerUid) : false };
}

/** Eligible promotions of [type] for this buyer, best first, honouring the daily frequency cap. */
export async function promosFor(uid: string, prof: Record<string, any>, type: PromoType, listingDocs: Map<string, DocumentSnapshot>, cap: number) {
  const promos = (await activePromotions()).filter((p) => p.type === type && p.sellerUid !== uid);
  if (!promos.length) return [];
  const following = new Set((await db.collection("follows").where("followerUid", "==", uid).limit(500).get()).docs.map((d) => d.get("sellerUid") as string));
  const today = new Date(Date.now() + 2 * 3_600_000).toISOString().slice(0, 10);
  const seen = (prof.promoSeen?.[today] ?? {}) as Record<string, number>;
  return promos.map((p) => {
    const l = listingDocs.get(p.listingId); if (!l || l.get("status") !== "live") return null;
    const sig = buyerSignals(prof, following, p.sellerUid);
    const listing = { title: l.get("title") ?? "", category: l.get("category") ?? "", tags: l.get("tags") ?? [] };
    if (!underCap(seen[p.id] ?? 0, cap) || !matchesTargeting(p.targeting, sig, listing)) return null;
    return { promo: p, doc: l, score: boostScore(p.targeting, sig, listing), followed: sig.following };
  }).filter((x): x is NonNullable<typeof x> => !!x).sort((a, b) => b.score - a.score);
}

export async function markSeen(uid: string, ids: string[]) {
  if (!ids.length) return;
  const today = new Date(Date.now() + 2 * 3_600_000).toISOString().slice(0, 10);
  await db.doc(`buyerProfiles/${uid}`).set({ promoSeen: { [today]: Object.fromEntries(ids.map((id) => [id, FieldValue.increment(1)])) } }, { merge: true });
}

export const stories_list = onCall(async (req) => {
  const { uid } = requireUser(req);
  const [prof, cfg] = await Promise.all([db.doc(`buyerProfiles/${uid}`).get(), loadConfig()]);
  const ids = (await activePromotions()).filter((p) => p.type === "story").map((p) => p.listingId);
  const docs = ids.length ? await db.getAll(...ids.slice(0, 100).map((id) => db.doc(`listings/${id}`))) : [];
  const byId = new Map(docs.map((d) => [d.id, d]));
  const picked = (await promosFor(uid, prof.data() ?? {}, "story", byId, cfg.boostFrequencyCap)).slice(0, 15)
    .sort((a, b) => Number(b.followed) - Number(a.followed));
  const { publicListing } = await import("./discovery");
  return { stories: picked.map((x) => ({ promoId: x.promo.id, sellerName: x.doc.get("sellerName") ?? "", followed: x.followed,
    listing: publicListing(x.doc, { promoId: x.promo.id, rankSource: "story" }) })) };
});
