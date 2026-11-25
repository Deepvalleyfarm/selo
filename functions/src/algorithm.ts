/**
 * Selonachipa discovery algorithm (PRD v6.1 §3, §6). Pure functions only — no Firebase imports —
 * so every rule is unit tested and replayable from the event log.
 */
import { createHash, randomBytes } from "node:crypto";

/* ===================================================================== Infinity Memory: keys */

/** RFC 9562 UUIDv7: 48-bit ms timestamp + random. Time-ordered, collision-free in practice. */
export function uuidv7(now = Date.now(), rand: Buffer = randomBytes(10)): string {
  const b = Buffer.alloc(16);
  b.writeUIntBE(now, 0, 6);
  rand.copy(b, 6, 0, 10);
  b[6] = (b[6] & 0x0f) | 0x70; // version 7
  b[8] = (b[8] & 0x3f) | 0x80; // variant
  const h = b.toString("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export const uuidv7Time = (id: string) => parseInt(id.replace(/-/g, "").slice(0, 12), 16);

/** Jump consistent hash (Lamping & Veach): adding a shard moves only 1/n of keys, never a full rehash. */
export function jumpHash(key: string, buckets: number): number {
  let k = BigInt("0x" + createHash("sha256").update(key).digest("hex").slice(0, 16));
  let b = -1n, j = 0n;
  const n = BigInt(buckets);
  while (j < n) {
    b = j;
    k = (k * 2862933555777941757n + 1n) & 0xffffffffffffffffn;
    j = BigInt(Math.floor(Number(b + 1n) * (Number(1n << 31n) / Number((k >> 33n) + 1n))));
  }
  return Number(b);
}

export const SHARDS = 64;
export type EntityType = "lst" | "med" | "evt" | "ses" | "rec" | "shr" | "alg" | "met";

/** Composite primary key: entity_type + UUIDv7 + shard_id. Shard follows the co-location rule (§3.3). */
export function compositeKey(entity: EntityType, shardBy: string, id = uuidv7()): { key: string; id: string; shard: number } {
  const shard = jumpHash(shardBy, SHARDS);
  return { key: `${entity}_${id}_s${String(shard).padStart(2, "0")}`, id, shard };
}

/* ======================================================================= Behaviour events */

export const EVENT_TYPES = ["listing_view", "video_watch", "like", "share", "enquiry_open", "save", "skip", "not_interested",
  "search", "session_start", "session_end", "details_open", "follow"] as const;
export type EventType = (typeof EVENT_TYPES)[number];

export interface BehaviourEvent {
  type: EventType; userId: string; ts: number; listingId?: string; sellerUid?: string; category?: string;
  watchMs?: number; totalMs?: number; replayed?: boolean; undone?: boolean; channel?: "inapp" | "whatsapp";
  skipSpeed?: "fast" | "normal"; wishlistId?: string; query?: string; resultsCount?: number; firstClickListingId?: string | null;
  source?: "feed" | "search" | "share" | "rail"; mediaType?: "video" | "photo"; durationMs?: number; variant?: string;
}

/** Validates a client-sent event; returns a message or null. Keeps the append-only log clean. */
export function eventProblem(e: Partial<BehaviourEvent>): string | null {
  if (!e.type || !(EVENT_TYPES as readonly string[]).includes(e.type)) return "unknown event type";
  const needsListing = !["search", "session_start", "session_end", "follow"].includes(e.type);
  if (needsListing && !e.listingId) return "listingId required";
  if (e.type === "video_watch" && (typeof e.watchMs !== "number" || typeof e.totalMs !== "number" || e.totalMs <= 0)) return "watchMs/totalMs required";
  if (e.type === "share" && e.channel !== "inapp" && e.channel !== "whatsapp") return "channel must be inapp or whatsapp";
  return null;
}

/* ================================================================ Embeddings & interest vectors */

export const DIM = 50;

/** Feature-hashed 50-d embedding of a listing (category weighted highest, then tags, then title words). */
export function embedListing(l: { category?: string; tags?: string[]; title?: string; condition?: string; priceNgwee?: number }): number[] {
  const v = new Array(DIM).fill(0);
  const add = (token: string, w: number) => {
    const h = createHash("md5").update(token.toLowerCase()).digest();
    v[h[0] % DIM] += h[1] & 1 ? w : -w;
    v[h[2] % DIM] += (h[3] & 1 ? w : -w) * 0.5;
  };
  if (l.category) add(`cat:${l.category}`, 3);
  (l.tags ?? []).forEach((t) => add(`tag:${t}`, 1.2));
  (l.title ?? "").toLowerCase().split(/[^a-z0-9]+/).filter((w) => w.length > 2).forEach((w) => add(`w:${w}`, 0.6));
  if (l.condition) add(`cond:${l.condition}`, 0.4);
  if (l.priceNgwee) add(`price:${Math.round(Math.log10(Math.max(100, l.priceNgwee)) * 2)}`, 0.5);
  return normalise(v);
}

export function normalise(v: number[]): number[] {
  const n = Math.sqrt(v.reduce((s, x) => s + x * x, 0));
  return n === 0 ? v.map(() => 0) : v.map((x) => x / n);
}
export const dot = (a: number[], b: number[]) => a.reduce((s, x, i) => s + x * (b[i] ?? 0), 0);
export const cosine = (a: number[], b: number[]) => { const d = dot(normalise(a), normalise(b)); return Number.isFinite(d) ? d : 0; };

/** How much each event moves the interest vector (negative = push away). */
export const EVENT_AFFINITY: Partial<Record<EventType, number>> = {
  listing_view: 0.1, video_watch: 0, like: 1, share: 1.2, enquiry_open: 1.1, save: 0.8, details_open: 0.5,
  skip: -0.15, not_interested: -1.5,
};

/** Buyer interest vector from the last 100 events (§6.2), recency-decayed with a 3-day half-life. */
export function interestVector(events: BehaviourEvent[], embeddings: Map<string, number[]>, now = Date.now()): number[] {
  const v = new Array(DIM).fill(0);
  const recent = [...events].sort((a, b) => b.ts - a.ts).slice(0, 100);
  for (const e of recent) {
    const emb = e.listingId ? embeddings.get(e.listingId) : undefined;
    if (!emb) continue;
    let w = EVENT_AFFINITY[e.type] ?? 0;
    if (e.type === "video_watch" && e.totalMs) w = Math.min(1, (e.watchMs ?? 0) / e.totalMs) * 0.9 - 0.2;
    if (e.undone) w = -w;
    const decay = Math.pow(0.5, (now - e.ts) / (3 * 86_400_000));
    for (let i = 0; i < DIM; i++) v[i] += emb[i] * w * decay;
  }
  return normalise(v);
}

/* ================================================================ Cohorts: k-means (k = 20) */

function mulberry32(seed: number) {
  return () => { seed |= 0; seed = (seed + 0x6d2b79f5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

/** Deterministic k-means++ on interest vectors. Returns centroids and the assignment of each vector. */
export function kmeans(vectors: number[][], k = 20, iterations = 25, seed = 7): { centroids: number[][]; assignment: number[] } {
  const rnd = mulberry32(seed);
  const pts = vectors.filter((v) => v.some((x) => x !== 0));
  if (!pts.length) return { centroids: [], assignment: vectors.map(() => 0) };
  const kk = Math.min(k, pts.length);
  const centroids: number[][] = [pts[Math.floor(rnd() * pts.length)]];
  while (centroids.length < kk) {
    const d2 = pts.map((p) => Math.min(...centroids.map((c) => 1 - cosine(p, c))) ** 2);
    const total = d2.reduce((s, x) => s + x, 0);
    let r = rnd() * total, i = 0;
    while (i < d2.length - 1 && (r -= d2[i]) > 0) i++;
    centroids.push(pts[i]);
  }
  let assign = pts.map(() => 0);
  for (let it = 0; it < iterations; it++) {
    assign = pts.map((p) => nearest(p, centroids));
    for (let c = 0; c < kk; c++) {
      const members = pts.filter((_, i) => assign[i] === c);
      if (members.length) centroids[c] = normalise(members.reduce((s, m) => s.map((x, j) => x + m[j]), new Array(DIM).fill(0)));
    }
  }
  return { centroids, assignment: vectors.map((v) => (v.some((x) => x !== 0) ? nearest(v, centroids) : 0)) };
}
export function nearest(v: number[], centroids: number[][]): number {
  let best = 0, bestS = -Infinity;
  centroids.forEach((c, i) => { const s = cosine(v, c); if (s > bestS) { bestS = s; best = i; } });
  return best;
}

/* ============================================================ Ranking: retrieval + scoring */

export const SIGNALS = ["watchTimeRatio", "like", "share", "enquiry", "save", "notInterested", "categoryMatch", "sellerEngagement"] as const;
export type Signal = (typeof SIGNALS)[number];
export type Weights = Record<Signal, number>;

export const DEFAULT_WEIGHTS: Weights = {
  watchTimeRatio: 0.30, like: 0.20, share: 0.18, enquiry: 0.15, save: 0.08, notInterested: -0.25, categoryMatch: 0.05, sellerEngagement: 0.04,
};
export interface RetrievalMix { cohort: number; trending: number; recent: number; exploration: number }
export const DEFAULT_MIX: RetrievalMix = { cohort: 0.40, trending: 0.30, recent: 0.20, exploration: 0.10 };

export interface CohortState {
  cohortId: number; weights: Weights; mix: RetrievalMix;
  categoryShare: Record<string, number>; // multiplier per category (1 = normal)
  typeBoost: { video: number; photo: number; until?: number };
  targetSessionMin: number;
}
export const defaultCohortState = (cohortId: number): CohortState => ({
  cohortId, weights: { ...DEFAULT_WEIGHTS }, mix: { ...DEFAULT_MIX }, categoryShare: {}, typeBoost: { video: 1, photo: 1 }, targetSessionMin: 25,
});

export interface CandidateListing {
  id: string; sellerUid: string; category: string; mediaType: "video" | "photo"; status: string; publishedAt: number;
  province?: string; district?: string; embedding: number[]; priceNgwee?: number;
  stats: { views: number; likes: number; shares: number; enquiries: number; saves: number; notInterested: number; watchMs: number; watchTotalMs: number };
  sellerEngagementRate: number; viralityScore: number; discountPct?: number;
}

/** Per-listing signal estimates (Bayesian-smoothed rates so new listings aren't punished). */
export function signalsFor(l: CandidateListing, buyerVec: number[]): Record<Signal, number> {
  const v = l.stats.views + 10;
  return {
    watchTimeRatio: l.mediaType === "video" ? (l.stats.watchMs + 5_000) / (l.stats.watchTotalMs + 10_000) : 0.5,
    like: (l.stats.likes + 0.5) / v, share: (l.stats.shares + 0.2) / v, enquiry: (l.stats.enquiries + 0.3) / v,
    save: (l.stats.saves + 0.3) / v, notInterested: (l.stats.notInterested + 0.1) / v,
    categoryMatch: Math.max(0, cosine(buyerVec, l.embedding)), sellerEngagement: Math.min(1, l.sellerEngagementRate),
  };
}

/** Rate signals are small numbers; scale them so each default weight has comparable influence. */
const SCALE: Record<Signal, number> = { watchTimeRatio: 1, like: 5, share: 12, enquiry: 8, save: 8, notInterested: 10, categoryMatch: 1, sellerEngagement: 3 };

export function score(l: CandidateListing, buyerVec: number[], w: Weights): number {
  const s = signalsFor(l, buyerVec);
  return SIGNALS.reduce((sum, k) => sum + w[k] * Math.min(1, s[k] * SCALE[k]), 0);
}

export interface RetrievalInput {
  all: CandidateListing[]; buyerVec: number[]; centroid?: number[]; province?: string; district?: string;
  shownAt: Record<string, number>; blockedListings: Set<string>; blockedSellers: Set<string>; now: number; mix: RetrievalMix;
  categoryShare: Record<string, number>; typeBoost: CohortState["typeBoost"]; size?: number; random?: () => number;
}

/** Stage 1 (§6.1): 200 candidates — 40% cohort interest, 30% regional trending, 20% last 24 h, 10% exploration. */
export function retrieve(inp: RetrievalInput): { candidates: CandidateListing[]; sources: Record<string, string> } {
  const size = inp.size ?? 200, rnd = inp.random ?? Math.random;
  const eligible = inp.all.filter((l) => l.status === "live" && !inp.blockedListings.has(l.id) && !inp.blockedSellers.has(l.sellerUid)
    && !(inp.shownAt[l.id] && inp.now - inp.shownAt[l.id] < 72 * 3_600_000));
  const typeMul = (l: CandidateListing) => (inp.typeBoost.until && inp.typeBoost.until < inp.now ? 1 : inp.typeBoost[l.mediaType] ?? 1);
  const catMul = (l: CandidateListing) => inp.categoryShare[l.category] ?? 1;
  const ref = inp.centroid ?? inp.buyerVec;
  const pools: [keyof RetrievalMix, CandidateListing[]][] = [
    ["cohort", [...eligible].sort((a, b) => cosine(ref, b.embedding) * catMul(b) * typeMul(b) - cosine(ref, a.embedding) * catMul(a) * typeMul(a))],
    ["trending", eligible.filter((l) => !inp.province || l.province === inp.province)
      .sort((a, b) => b.viralityScore * typeMul(b) * catMul(b) - a.viralityScore * typeMul(a) * catMul(a))],
    ["recent", eligible.filter((l) => inp.now - l.publishedAt < 86_400_000).sort((a, b) => b.publishedAt - a.publishedAt)],
    ["exploration", [...eligible].sort(() => rnd() - 0.5)],
  ];
  const taken = new Map<string, CandidateListing>(), sources: Record<string, string> = {};
  for (const [name, pool] of pools) {
    const quota = Math.round(size * inp.mix[name]);
    let n = 0;
    for (const l of pool) { if (n >= quota) break; if (!taken.has(l.id)) { taken.set(l.id, l); sources[l.id] = name; n++; } }
  }
  for (const l of pools[0][1]) { if (taken.size >= size) break; if (!taken.has(l.id)) { taken.set(l.id, l); sources[l.id] = "cohort"; } }
  return { candidates: [...taken.values()], sources };
}

/** Stage 2: score, then spread sellers so the same seller never appears twice in a row. */
export function rank(cands: CandidateListing[], buyerVec: number[], w: Weights, count: number): { listing: CandidateListing; score: number }[] {
  const scored = cands.map((l) => ({ listing: l, score: score(l, buyerVec, w) })).sort((a, b) => b.score - a.score);
  const out: typeof scored = [], rest = [...scored];
  while (out.length < count && rest.length) {
    const prev = out.at(-1)?.listing.sellerUid;
    const i = rest.findIndex((r) => r.listing.sellerUid !== prev);
    out.push(...rest.splice(i === -1 ? 0 : i, 1));
  }
  return out;
}

/* ================================================================ Auto-adjustment (§6.3) */

export interface CohortMetrics {
  avgSessionMin: number; skipRateByCategory: Record<string, number>;
  shareRateByType: { video: number; photo: number }; baselineShareRateByType: { video: number; photo: number };
}
export interface TuningChange { rule: string; detail: string }

/** Micro-tuning, every 6 hours. Returns the new state and a log of what changed and why. */
export function microTune(s: CohortState, m: CohortMetrics, now = Date.now()): { state: CohortState; changes: TuningChange[] } {
  const st: CohortState = structuredClone(s);
  const changes: TuningChange[] = [];
  if (m.avgSessionMin < st.targetSessionMin * 0.9) {
    st.weights.watchTimeRatio = round(st.weights.watchTimeRatio + 0.02);
    st.mix = rebalance(st.mix, "exploration", Math.min(0.5, st.mix.exploration * 1.05));
    changes.push({ rule: "session_below_target", detail: `avg ${m.avgSessionMin.toFixed(1)} min < target ${st.targetSessionMin}: watchTime +0.02, exploration +5%` });
  }
  for (const [cat, rate] of Object.entries(m.skipRateByCategory)) {
    if (rate > 0.6) {
      st.categoryShare[cat] = round((st.categoryShare[cat] ?? 1) * 0.9);
      changes.push({ rule: "category_skip_rate", detail: `${cat} skip rate ${(rate * 100).toFixed(0)}% > 60%: retrieval share −10%` });
    }
  }
  for (const t of ["video", "photo"] as const) {
    const base = m.baselineShareRateByType[t];
    if (base > 0 && m.shareRateByType[t] > 2 * base) {
      st.typeBoost = { ...st.typeBoost, [t]: round(1.08), until: now + 86_400_000 };
      changes.push({ rule: "share_spike", detail: `${t} share rate ${(m.shareRateByType[t] / base).toFixed(1)}× baseline: +8% retrieval for 24 h` });
    }
  }
  if (st.typeBoost.until && st.typeBoost.until < now) st.typeBoost = { video: 1, photo: 1 };
  return { state: st, changes };
}

function rebalance(mix: RetrievalMix, key: keyof RetrievalMix, value: number): RetrievalMix {
  const others = (Object.keys(mix) as (keyof RetrievalMix)[]).filter((k) => k !== key);
  const rest = others.reduce((s, k) => s + mix[k], 0);
  const out = { ...mix, [key]: value };
  others.forEach((k) => { out[k] = round((mix[k] / rest) * (1 - value)); });
  return out;
}
const round = (x: number) => Math.round(x * 10_000) / 10_000;

/** Weekly A/B: 4 variants perturbing the current weights; each gets 2.5% of traffic (10% total). */
export function weightVariants(w: Weights, seed = 1): Weights[] {
  const rnd = mulberry32(seed);
  return [0, 1, 2, 3].map(() => {
    const v = { ...w };
    SIGNALS.forEach((k) => { v[k] = round(v[k] * (1 + (rnd() - 0.5) * 0.3)); });
    return v;
  });
}

/** Buyers 0–9.99% of the hash space get a variant (2.5% each); everyone else gets control. */
export function abBucket(userId: string): number | null {
  const h = parseInt(createHash("sha256").update(`ab:${userId}`).digest("hex").slice(0, 8), 16) / 0xffffffff;
  return h < 0.1 ? Math.min(3, Math.floor(h / 0.025)) : null;
}

/** Variant wins if its sessions AND conversions beat control (combined score). */
export function pickWinner(control: { sessionMin: number; conversion: number; n: number },
  variants: { sessionMin: number; conversion: number; n: number }[], minSample = 200): number | null {
  const s = (x: { sessionMin: number; conversion: number }) => x.sessionMin / Math.max(1, control.sessionMin) + x.conversion / Math.max(1e-6, control.conversion);
  let best: number | null = null, bestS = s(control) * 1.02; // must beat control by 2%
  variants.forEach((v, i) => { if (v.n >= minSample && s(v) > bestS) { best = i; bestS = s(v); } });
  return best;
}

/* ===================================================================== Guardrails (§6.4) */

export interface Guardrails { enabled: boolean; sessionCapMinutes: number; warnAtMinutes: number; budgetMultiplier: number; budgetWindowWeeks: number }
export const DEFAULT_GUARDRAILS: Guardrails = { enabled: true, sessionCapMinutes: 145, warnAtMinutes: 130, budgetMultiplier: 13, budgetWindowWeeks: 2 };

export function guardrailState(g: Guardrails, minutesToday: number, weeklyEnquiries: number[]) {
  if (!g.enabled) return { optimise: true, warn: false, budgetCheck: false };
  const [thisWeek = 0, ...previous] = weeklyEnquiries;
  const baseline = previous[g.budgetWindowWeeks - 1] ?? previous.at(-1) ?? 0;
  return {
    optimise: minutesToday < g.sessionCapMinutes,
    warn: minutesToday >= g.warnAtMinutes,
    budgetCheck: baseline > 0 ? thisWeek > baseline * g.budgetMultiplier : thisWeek > g.budgetMultiplier,
  };
}

/* ===================================================================== Search & pricing */

const STOP = new Set(["the", "and", "for", "with", "a", "an", "of", "in", "on", "to"]);
/** Tokens + prefixes (min 2 chars) for Firestore array-contains search over title, description, tags, seller. */
export function searchTokens(...texts: (string | undefined)[]): string[] {
  const out = new Set<string>();
  texts.join(" ").toLowerCase().normalize("NFKD").replace(/[^a-z0-9 ]/g, " ").split(/\s+/)
    .filter((w) => w.length >= 2 && !STOP.has(w)).slice(0, 60)
    .forEach((w) => { for (let i = 2; i <= Math.min(w.length, 12); i++) out.add(w.slice(0, i)); });
  return [...out].slice(0, 400);
}
export const queryTokens = (q: string) => q.toLowerCase().replace(/[^a-z0-9 ]/g, " ").split(/\s+/).filter((w) => w.length >= 2 && !STOP.has(w)).map((w) => w.slice(0, 12));

/** Recency-weighted median price (§4.1.3): last 30 days, 7-day half-life. Returns low/median/high in ngwee. */
export function suggestPrice(samples: { priceNgwee: number; ts: number }[], now = Date.now()) {
  const recent = samples.filter((s) => s.priceNgwee > 0 && now - s.ts <= 30 * 86_400_000)
    .map((s) => ({ p: s.priceNgwee, w: Math.pow(0.5, (now - s.ts) / (7 * 86_400_000)) })).sort((a, b) => a.p - b.p);
  if (!recent.length) return null;
  const total = recent.reduce((s, x) => s + x.w, 0);
  const at = (q: number) => { let acc = 0; for (const x of recent) { acc += x.w; if (acc >= total * q) return x.p; } return recent.at(-1)!.p; };
  return { lowNgwee: at(0.25), medianNgwee: at(0.5), highNgwee: at(0.75), samples: recent.length };
}

/** Seller engagement (rolling 7-day): (likes + shares + enquiries) / views. Virality: share velocity vs category baseline. */
export const engagementRate = (likes: number, shares: number, enquiries: number, views: number) => (views > 0 ? (likes + shares + enquiries) / views : 0);
export const viralityScore = (sharesLast6h: number, categoryBaselinePer6h: number) => sharesLast6h / Math.max(0.5, categoryBaselinePer6h);
