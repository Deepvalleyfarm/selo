import { describe, expect, it } from "vitest";
import { boostScore, cleanTargeting, estimateAudience, injectBoosts, matchesTargeting, promoPrice, searchedFor, underCap, type Targeting } from "../src/promo-rules";
import { collectionFailureReason, operatorLabel, quote } from "../src/domain";
import { DEFAULT_CONFIG } from "../src/domain-defaults";

const t = (x: Partial<Targeting> = {}): Targeting => ({ ageBands: [], provinces: [], districts: [], interests: [], searchIntent: true, followers: true, ...x });
const phone = { title: "Samsung Galaxy A15 128GB", category: "Phones & accessories", tags: ["samsung", "smartphone"] };

describe("promotion pricing", () => {
  it("charges per day by reach, flat for a story", () => {
    expect(promoPrice("boost", "LOCAL", 3, DEFAULT_CONFIG)).toBe(6_000);
    expect(promoPrice("boost", "REGIONAL", 7, DEFAULT_CONFIG)).toBe(31_500);
    expect(promoPrice("boost", "NATIONAL", 1, DEFAULT_CONFIG)).toBe(9_000);
    expect(promoPrice("story", "NATIONAL", 14, DEFAULT_CONFIG)).toBe(1_000);
  });
  it("clamps days to 1..30", () => {
    expect(promoPrice("boost", "LOCAL", 0, DEFAULT_CONFIG)).toBe(2_000);
    expect(promoPrice("boost", "LOCAL", 99, DEFAULT_CONFIG)).toBe(60_000);
  });
});

describe("targeting", () => {
  it("drops area filters that do not apply to the reach", () => {
    const c = cleanTargeting({ provinces: ["Lusaka"], districts: ["Kafue"], ageBands: ["25–34"] }, "NATIONAL");
    expect(c.provinces).toEqual([]); expect(c.districts).toEqual([]); expect(c.ageBands).toEqual(["25–34"]);
    expect(cleanTargeting({ districts: ["Kafue"] }, "REGIONAL").districts).toEqual([]);
  });
  it("location and age are hard filters", () => {
    const tg = t({ districts: ["Kafue"], ageBands: ["25–34"] });
    expect(matchesTargeting(tg, { district: "Kafue", ageBand: "25–34" }, phone)).toBe(true);
    expect(matchesTargeting(tg, { district: "Lusaka", ageBand: "25–34" }, phone)).toBe(false);
    expect(matchesTargeting(tg, { district: "Kafue", ageBand: "55+" }, phone)).toBe(false);
    expect(matchesTargeting(t({ provinces: ["Copperbelt"] }), { province: "Lusaka" }, phone)).toBe(false);
  });
  it("interest or search intent is required when interests are chosen", () => {
    const tg = t({ interests: ["Phones & accessories"] });
    expect(matchesTargeting(tg, { catAffinity: { "Phones & accessories": 0.6 } }, phone)).toBe(true);
    expect(matchesTargeting(tg, { catAffinity: { Fashion: 1 } }, phone)).toBe(false);
    expect(matchesTargeting(tg, { recentSearches: ["samsung a15"] }, phone)).toBe(true);
    expect(matchesTargeting({ ...tg, searchIntent: false }, { recentSearches: ["samsung a15"] }, phone)).toBe(false);
  });
  it("followers always qualify when included", () => {
    expect(matchesTargeting(t({ districts: ["Kafue"], interests: ["Fashion"] }), { district: "Ndola", following: true }, phone)).toBe(true);
    expect(matchesTargeting(t({ districts: ["Kafue"], followers: false }), { district: "Ndola", following: true }, phone)).toBe(false);
  });
  it("matches searches on title, category and tag words", () => {
    expect(searchedFor(["cheap smartphone"], phone)).toBe(true);
    expect(searchedFor(["sofa"], phone)).toBe(false);
    expect(searchedFor(["a1"], phone)).toBe(false); // too short to count
  });
  it("puts active searchers ahead of interest", () => {
    const tg = t({ interests: ["Phones & accessories"] });
    expect(boostScore(tg, { recentSearches: ["galaxy"] }, phone)).toBeGreaterThan(boostScore(tg, { catAffinity: { "Phones & accessories": 1 } }, phone));
  });
});

describe("audience estimate", () => {
  const counts = { total: 10_000, byProvince: { Lusaka: 4_000, Copperbelt: 3_000 }, byDistrict: { Kafue: 500 }, byAgeBand: { "18–24": 2_500 }, byInterest: { Fashion: 1_000 } };
  it("narrows by area, age and interest", () => {
    expect(estimateAudience(t(), counts)).toBe(10_000);
    expect(estimateAudience(t({ provinces: ["Lusaka", "Copperbelt"] }), counts)).toBe(7_000);
    expect(estimateAudience(t({ districts: ["Kafue"], ageBands: ["18–24"] }), counts)).toBe(125);
    expect(estimateAudience(t({ interests: ["Fashion"], searchIntent: false }), counts)).toBe(1_000);
  });
});

describe("feed placement", () => {
  const page = ["a", "b", "c", "d", "e", "f", "g", "h"].map((id) => ({ id }));
  it("places boosts at slots 2 and 6", () => {
    expect(injectBoosts(page, [{ id: "X" }, { id: "Y" }, { id: "Z" }]).map((x) => x.id)).toEqual(["a", "X", "b", "c", "d", "Y", "e", "f", "g", "h"]);
  });
  it("skips a boost already on the page and respects the per-page limit", () => {
    expect(injectBoosts(page, [{ id: "c" }, { id: "X" }], 1).map((x) => x.id)).toEqual(["a", "X", "b", "c", "d", "e", "f", "g", "h"]);
  });
  it("works on short pages", () => {
    expect(injectBoosts([{ id: "a" }], [{ id: "X" }, { id: "Y" }]).map((x) => x.id)).toEqual(["a", "X", "Y"]);
  });
  it("caps impressions per buyer per day", () => {
    expect(underCap(2, 3)).toBe(true); expect(underCap(3, 3)).toBe(false);
  });
});

describe("buyer payment", () => {
  it("adds the transaction fee to everything the buyer pays", () => {
    const q = quote(20_000, 4, { ...DEFAULT_CONFIG, buyerServiceFeePct: 2, paymentProcessingPct: 1 });
    expect(q.deliveryNgwee).toBe(2_500); expect(q.serviceFeeNgwee).toBe(400);
    expect(q.processingFeeNgwee).toBe(229); // 1% of 22,900
    expect(q.totalNgwee).toBe(23_129);
  });
  it("detects the operator from the prefix", () => {
    expect(operatorLabel("+260977123456")).toBe("Airtel Money");
    expect(operatorLabel("+260966123456")).toBe("MTN Money");
    expect(operatorLabel("+260955123456")).toBe("Zamtel Money");
  });
  it("gives the buyer a reason they can act on", () => {
    expect(collectionFailureReason("FAILED", "Insufficient balance", "+260955123456")).toBe("Insufficient funds on Zamtel Money 0955 ••• 456.");
    expect(collectionFailureReason("cancelled", undefined, "+260977123456")).toContain("declined on Airtel Money");
  });
});
