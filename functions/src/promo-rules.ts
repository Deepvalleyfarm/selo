/**
 * Pure rules for paid Boosts and Feed Stories (PRD v6.2). Shared by promotions.ts and discovery.ts; unit tested.
 * Prices mirror the Android Fees.promoPriceNgwee.
 */
export type PromoType = "boost" | "story";
export type Reach = "LOCAL" | "REGIONAL" | "NATIONAL";
export const REACHES: Reach[] = ["LOCAL", "REGIONAL", "NATIONAL"];
export const PROMO_DAYS = [1, 3, 7, 14];

export interface Targeting {
  ageBands: string[]; provinces: string[]; districts: string[]; interests: string[]; searchIntent: boolean; followers: boolean;
}
export interface PromoPricing { boostLocalPerDay: number; boostRegionalPerDay: number; boostNationalPerDay: number; storyPrice24h: number }

export function promoPrice(type: PromoType, reach: Reach, days: number, c: PromoPricing): number {
  if (type === "story") return Math.round(c.storyPrice24h * 100);
  const perDay = reach === "LOCAL" ? c.boostLocalPerDay : reach === "REGIONAL" ? c.boostRegionalPerDay : c.boostNationalPerDay;
  return Math.round(perDay * 100) * Math.min(30, Math.max(1, Math.floor(days)));
}

const list = (v: unknown, max = 20) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && x.length <= 40).slice(0, max) : []);
export function cleanTargeting(raw: unknown, reach: Reach): Targeting {
  const t = (raw ?? {}) as Record<string, unknown>;
  return {
    ageBands: list(t.ageBands, 5), provinces: reach === "NATIONAL" ? [] : list(t.provinces, 10), districts: reach === "LOCAL" ? list(t.districts) : [],
    interests: list(t.interests, 16), searchIntent: t.searchIntent !== false, followers: t.followers !== false,
  };
}

/** What we know about a buyer when deciding whether a promotion may be shown to them. */
export interface BuyerSignals {
  ageBand?: string; province?: string; district?: string;
  /** category → affinity 0..1 from watching, liking and buying */
  catAffinity?: Record<string, number>;
  recentSearches?: string[]; following?: boolean;
}

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();

/**
 * Location and age are hard filters (the seller paid for them). With interests selected, the buyer must also
 * have an affinity for one of them, or (with search intent on) have searched for the product recently.
 * Followers always qualify when the seller kept them in.
 */
export function matchesTargeting(t: Targeting, b: BuyerSignals, listing: { title: string; category: string; tags?: string[] }): boolean {
  if (t.followers && b.following) return true;
  if (t.districts.length && !(b.district && t.districts.includes(b.district))) return false;
  if (!t.districts.length && t.provinces.length && !(b.province && t.provinces.includes(b.province))) return false;
  if (t.ageBands.length && !(b.ageBand && t.ageBands.includes(b.ageBand))) return false;
  if (!t.interests.length) return true; // area and age only
  if (t.interests.some((c) => (b.catAffinity?.[c] ?? 0) >= 0.15)) return true;
  return t.searchIntent && searchedFor(b.recentSearches ?? [], listing);
}

/** Order eligible boosts: people actively looking first, then strongest interest. */
export function boostScore(t: Targeting, b: BuyerSignals, listing: { title: string; category: string; tags?: string[] }): number {
  const intent = t.searchIntent && searchedFor(b.recentSearches ?? [], listing) ? 1 : 0;
  const interest = Math.max(0, ...t.interests.map((c) => b.catAffinity?.[c] ?? 0), b.catAffinity?.[listing.category] ?? 0);
  return intent * 2 + interest + (b.following ? 0.5 : 0);
}

export function searchedFor(searches: string[], l: { title: string; category: string; tags?: string[] }): boolean {
  const words = new Set([...norm(l.title).split(" "), ...norm(l.category).split(" "), ...(l.tags ?? []).map(norm)].filter((w) => w.length >= 3));
  return searches.some((q) => norm(q).split(" ").some((w) => w.length >= 3 && words.has(w)));
}

/** Rough audience size for the estimate shown before paying. Population figures come from buyerProfiles counts. */
export function estimateAudience(t: Targeting, counts: { total: number; byProvince: Record<string, number>; byDistrict: Record<string, number>;
  byAgeBand: Record<string, number>; byInterest: Record<string, number> }): number {
  const total = Math.max(1, counts.total);
  let area = t.districts.length ? t.districts.reduce((s, d) => s + (counts.byDistrict[d] ?? 0), 0)
    : t.provinces.length ? t.provinces.reduce((s, p) => s + (counts.byProvince[p] ?? 0), 0) : total;
  const ageShare = t.ageBands.length ? t.ageBands.reduce((s, a) => s + (counts.byAgeBand[a] ?? 0), 0) / total : 1;
  const interestShare = t.interests.length ? Math.min(1, t.interests.reduce((s, c) => s + (counts.byInterest[c] ?? 0), 0) / total + (t.searchIntent ? 0.05 : 0)) : 1;
  area = Math.round(area * Math.min(1, ageShare) * interestShare);
  return Math.max(0, area);
}

/**
 * Places boosted listings into an organic page at fixed slots (2 and 6 by default, 0-based 1 and 5),
 * skipping any already on the page, at most [maxSlots] per page.
 */
export function injectBoosts<T extends { id: string }>(organic: T[], boosts: T[], maxSlots = 2, slots = [1, 5]): T[] {
  const out = [...organic];
  const fresh = boosts.filter((b) => !organic.some((o) => o.id === b.id)).slice(0, Math.min(maxSlots, slots.length));
  fresh.forEach((b, i) => { const at = Math.min(slots[i], out.length); out.splice(at, 0, b); });
  return out;
}

/** Frequency cap: how many times today this buyer has seen this promotion. */
export const underCap = (seenToday: number, cap: number) => seenToday < Math.max(1, cap);
