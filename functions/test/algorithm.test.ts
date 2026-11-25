import { describe, expect, it } from "vitest";
import {
  abBucket, compositeKey, cosine, DEFAULT_GUARDRAILS, DEFAULT_MIX, DEFAULT_WEIGHTS, defaultCohortState, embedListing, eventProblem,
  guardrailState, interestVector, jumpHash, kmeans, microTune, pickWinner, queryTokens, rank, retrieve, score, searchTokens, suggestPrice,
  uuidv7, uuidv7Time, weightVariants, type CandidateListing,
} from "../src/algorithm";

const NOW = Date.UTC(2026, 10, 25, 12);
const mk = (i: number, over: Partial<CandidateListing> = {}): CandidateListing => ({
  id: `l${i}`, sellerUid: `s${i % 7}`, category: ["Fresh produce", "Electronics", "Fashion", "Fast food"][i % 4],
  mediaType: i % 3 ? "video" : "photo", status: "live", publishedAt: NOW - i * 180_000, province: i % 2 ? "Lusaka" : "Copperbelt",
  embedding: embedListing({ category: ["Fresh produce", "Electronics", "Fashion", "Fast food"][i % 4], title: `item ${i}` }),
  stats: { views: 100, likes: i % 10, shares: i % 5, enquiries: i % 4, saves: 1, notInterested: 0, watchMs: 30_000, watchTotalMs: 60_000 },
  sellerEngagementRate: 0.1, viralityScore: (i % 9) / 3, ...over,
});

describe("Infinity Memory keys", () => {
  it("UUIDv7 is time-ordered, version 7 and unique", () => {
    const ids = Array.from({ length: 5000 }, (_, i) => uuidv7(NOW + i));
    expect(new Set(ids).size).toBe(5000);
    expect(ids[0][14]).toBe("7");
    expect([...ids].sort()).toEqual(ids);
    expect(uuidv7Time(ids[10])).toBe(NOW + 10);
  });
  it("composite key = entity + UUIDv7 + shard, co-located by shard key", () => {
    const a = compositeKey("lst", "seller-1"), b = compositeKey("lst", "seller-1");
    expect(a.key).toMatch(/^lst_[0-9a-f-]{36}_s\d{2}$/);
    expect(a.shard).toBe(b.shard);
    expect(a.key).not.toBe(b.key);
  });
  it("jump hash moves only ~1/n keys when a shard is added (no full rehash)", () => {
    const keys = Array.from({ length: 4000 }, (_, i) => `user-${i}`);
    const moved = keys.filter((k) => jumpHash(k, 64) !== jumpHash(k, 65)).length / keys.length;
    expect(moved).toBeLessThan(0.04);
    expect(keys.every((k) => jumpHash(k, 64) >= 0 && jumpHash(k, 64) < 64)).toBe(true);
  });
});

describe("events", () => {
  it("validates the event schema", () => {
    expect(eventProblem({ type: "like", listingId: "l1" })).toBeNull();
    expect(eventProblem({ type: "like" })).toMatch(/listingId/);
    expect(eventProblem({ type: "video_watch", listingId: "l1", watchMs: 3000 })).toMatch(/totalMs/);
    expect(eventProblem({ type: "share", listingId: "l1", channel: "inapp" })).toBeNull();
    expect(eventProblem({ type: "search", query: "phone" })).toBeNull();
    expect(eventProblem({ type: "hack" as never })).toMatch(/unknown/);
  });
});

describe("interest vectors and cohorts", () => {
  const produce = embedListing({ category: "Fresh produce", title: "tomatoes" });
  const phone = embedListing({ category: "Electronics", title: "smartphone" });
  it("liking produce moves the buyer towards produce, not-interested pushes away", () => {
    const emb = new Map([["p", produce], ["e", phone]]);
    const v = interestVector([{ type: "like", userId: "u", ts: NOW, listingId: "p" }, { type: "not_interested", userId: "u", ts: NOW, listingId: "e" }], emb, NOW);
    expect(v).toHaveLength(50);
    expect(cosine(v, produce)).toBeGreaterThan(cosine(v, phone));
    expect(cosine(v, phone)).toBeLessThan(0);
  });
  it("k-means separates clearly different interests", () => {
    const vs = [...Array(30)].map(() => produce).concat([...Array(30)].map(() => phone));
    const { centroids, assignment } = kmeans(vs, 20);
    expect(centroids.length).toBeLessThanOrEqual(20);
    expect(assignment[0]).toBe(assignment[29]);
    expect(assignment[0]).not.toBe(assignment[30]);
  });
});

describe("ranking", () => {
  const all = Array.from({ length: 400 }, (_, i) => mk(i));
  it("retrieves 200 candidates with the 40/30/20/10 mix and respects filters", () => {
    const shownAt = { l0: NOW - 3_600_000, l1: NOW - 80 * 3_600_000 };
    const { candidates, sources } = retrieve({ all: [...all, mk(999, { status: "paused" })], buyerVec: all[2].embedding, province: "Lusaka", shownAt,
      blockedListings: new Set(["l2"]), blockedSellers: new Set(["s3"]), now: NOW, mix: DEFAULT_MIX, categoryShare: {}, typeBoost: { video: 1, photo: 1 }, random: () => 0.5 });
    expect(candidates).toHaveLength(200);
    const ids = new Set(candidates.map((c) => c.id));
    expect(ids.has("l0")).toBe(false); // shown in last 72 h
    expect(ids.has("l999")).toBe(false); // not active
    expect(ids.has("l2")).toBe(false); // blocked listing
    expect(candidates.some((c) => c.sellerUid === "s3")).toBe(false); // blocked seller
    const count = (s: string) => Object.values(sources).filter((x) => x === s).length;
    expect(count("cohort")).toBe(80); expect(count("trending")).toBe(60); expect(count("recent")).toBe(40); expect(count("exploration")).toBe(20);
  });
  it("backfills from the cohort pool when a source runs short", () => {
    const old = all.map((l) => ({ ...l, publishedAt: NOW - 5 * 86_400_000 }));
    const { candidates, sources } = retrieve({ all: old, buyerVec: old[2].embedding, shownAt: {}, blockedListings: new Set(), blockedSellers: new Set(),
      now: NOW, mix: DEFAULT_MIX, categoryShare: {}, typeBoost: { video: 1, photo: 1 }, random: () => 0.5 });
    expect(candidates).toHaveLength(200);
    expect(Object.values(sources).filter((x) => x === "recent")).toHaveLength(0);
  });
  it("scores engagement up and not-interested down; never repeats a seller back-to-back", () => {
    const good = mk(1, { stats: { views: 100, likes: 40, shares: 20, enquiries: 15, saves: 10, notInterested: 0, watchMs: 55_000, watchTotalMs: 60_000 } });
    const bad = mk(2, { stats: { views: 100, likes: 0, shares: 0, enquiries: 0, saves: 0, notInterested: 30, watchMs: 2_000, watchTotalMs: 60_000 } });
    expect(score(good, good.embedding, DEFAULT_WEIGHTS)).toBeGreaterThan(score(bad, good.embedding, DEFAULT_WEIGHTS));
    const ranked = rank(all.slice(0, 50), all[0].embedding, DEFAULT_WEIGHTS, 20);
    expect(ranked).toHaveLength(20);
    ranked.slice(1).forEach((r, i) => expect(r.listing.sellerUid).not.toBe(ranked[i].listing.sellerUid));
  });
});

describe("auto-adjustment", () => {
  const base = defaultCohortState(3);
  it("micro-tunes per the three §6.3 rules", () => {
    const { state, changes } = microTune(base, { avgSessionMin: 10, skipRateByCategory: { Electronics: 0.7, Fashion: 0.3 },
      shareRateByType: { video: 0.05, photo: 0.01 }, baselineShareRateByType: { video: 0.02, photo: 0.01 } }, NOW);
    expect(state.weights.watchTimeRatio).toBeCloseTo(0.32);
    expect(state.mix.exploration).toBeCloseTo(0.105);
    expect(Object.values(state.mix).reduce((s, x) => s + x, 0)).toBeCloseTo(1, 3);
    expect(state.categoryShare.Electronics).toBeCloseTo(0.9);
    expect(state.categoryShare.Fashion).toBeUndefined();
    expect(state.typeBoost.video).toBeCloseTo(1.08);
    expect(state.typeBoost.until).toBe(NOW + 86_400_000);
    expect(changes.map((c) => c.rule)).toEqual(["session_below_target", "category_skip_rate", "share_spike"]);
    expect(base.weights.watchTimeRatio).toBe(0.3); // input untouched (versioned, never mutated)
  });
  it("does nothing when the cohort is healthy", () => {
    expect(microTune(base, { avgSessionMin: 30, skipRateByCategory: { X: 0.2 }, shareRateByType: { video: 1, photo: 1 }, baselineShareRateByType: { video: 1, photo: 1 } }).changes).toEqual([]);
  });
  it("A/B: 10% of buyers split across 4 variants; winner must beat control", () => {
    const buckets = Array.from({ length: 20000 }, (_, i) => abBucket(`u${i}`));
    const inTest = buckets.filter((b) => b !== null).length / buckets.length;
    expect(inTest).toBeGreaterThan(0.085); expect(inTest).toBeLessThan(0.115);
    expect(new Set(buckets.filter((b) => b !== null))).toEqual(new Set([0, 1, 2, 3]));
    expect(weightVariants(DEFAULT_WEIGHTS)).toHaveLength(4);
    const control = { sessionMin: 20, conversion: 0.05, n: 5000 };
    expect(pickWinner(control, [{ sessionMin: 20, conversion: 0.05, n: 300 }, { sessionMin: 26, conversion: 0.06, n: 300 }, { sessionMin: 40, conversion: 0.09, n: 50 }])).toBe(1);
    expect(pickWinner(control, [{ sessionMin: 19, conversion: 0.05, n: 300 }])).toBeNull();
  });
});

describe("guardrails", () => {
  it("stops optimising at 145 min, warns at 130, flags >13x enquiry growth over 2 weeks", () => {
    expect(guardrailState(DEFAULT_GUARDRAILS, 100, [2, 2, 2])).toEqual({ optimise: true, warn: false, budgetCheck: false });
    expect(guardrailState(DEFAULT_GUARDRAILS, 131, [2, 2, 2]).warn).toBe(true);
    expect(guardrailState(DEFAULT_GUARDRAILS, 145, [2, 2, 2]).optimise).toBe(false);
    expect(guardrailState(DEFAULT_GUARDRAILS, 10, [40, 5, 3]).budgetCheck).toBe(true);
    expect(guardrailState(DEFAULT_GUARDRAILS, 10, [39, 5, 3]).budgetCheck).toBe(false);
    expect(guardrailState({ ...DEFAULT_GUARDRAILS, enabled: false }, 500, [99, 1, 1])).toEqual({ optimise: true, warn: false, budgetCheck: false });
  });
});

describe("search and pricing", () => {
  it("indexes prefixes and drops stop words", () => {
    const t = searchTokens("Fresh Tomatoes for sale", "Mama Bupe's Veg");
    expect(t).toContain("tom"); expect(t).toContain("tomatoes"); expect(t).toContain("bupe"); expect(t).not.toContain("for");
    expect(queryTokens("The tomatoes!")).toEqual(["tomatoes"]);
  });
  it("suggests a recency-weighted price from the last 30 days only", () => {
    const day = 86_400_000;
    const p = suggestPrice([{ priceNgwee: 8000, ts: NOW - day }, { priceNgwee: 9000, ts: NOW - 2 * day }, { priceNgwee: 7500, ts: NOW - 3 * day },
      { priceNgwee: 99_999, ts: NOW - 40 * day }], NOW)!;
    expect(p.samples).toBe(3);
    expect(p.medianNgwee).toBe(8000);
    expect(p.highNgwee).toBeLessThan(99_999);
    expect(suggestPrice([], NOW)).toBeNull();
  });
});
