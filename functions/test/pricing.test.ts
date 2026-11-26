import { describe, expect, it } from "vitest";
import { campaignProblem, priceCart, type Campaign } from "../src/pricing";

const now = Date.UTC(2026, 10, 27, 10);
const c = (x: Partial<Campaign>): Campaign => ({ id: "c", sellerUid: "s", type: "DISCOUNT", title: "Deal", listingIds: ["a"], status: "ACTIVE", startsAt: now - 1000, endsAt: now + 3_600_000, ...x });
const a = { listingId: "a", title: "Tomatoes", qty: 1, priceNgwee: 10_000 };
const b = { listingId: "b", title: "Onions", qty: 1, priceNgwee: 5_000 };

describe("cart pricing", () => {
  it("charges the listing's own discount (what the buyer sees)", () => {
    expect(priceCart([{ ...a, discountPct: 15 }], [], now).goodsNgwee).toBe(8_500);
  });
  it("takes the better of listing discount and campaign, never both", () => {
    const r = priceCart([{ ...a, qty: 2, discountPct: 10 }], [c({ discountPct: 25 })], now);
    expect(r.goodsNgwee).toBe(15_000);
    expect(r.savingsNgwee).toBe(5_000);
    expect(r.applied[0].savingNgwee).toBe(3_000); // saving over the listing's own price
  });
  it("buy one get one free makes every second unit free", () => {
    const r = priceCart([{ ...a, qty: 3 }], [c({ type: "BOGO" })], now);
    expect(r.lines[0].freeQty).toBe(1);
    expect(r.goodsNgwee).toBe(20_000);
  });
  it("bundle price applies per complete set only", () => {
    const r = priceCart([{ ...a, qty: 2 }, b], [c({ type: "BUNDLE", listingIds: ["a", "b"], bundlePriceNgwee: 12_000 })], now);
    expect(r.goodsNgwee).toBe(12_000 + 10_000);
  });
  it("ignores expired, paused, future or sold-out campaigns", () => {
    for (const x of [{ endsAt: now - 1 }, { status: "PAUSED" }, { startsAt: now + 60_000 }, { maxUnits: 5, unitsSold: 5 }])
      expect(priceCart([a], [c({ discountPct: 50, ...x })], now).goodsNgwee).toBe(10_000);
  });
  it("validates seller input", () => {
    expect(campaignProblem({ type: "FLASH", title: "Weekend", listingIds: ["a"], discountPct: 20, startsAt: now, endsAt: now + 10 * 86_400_000 }, now)).toMatch(/7 days/);
    expect(campaignProblem({ type: "BUNDLE", title: "Pack", listingIds: ["a"], bundlePriceNgwee: 100 }, now)).toMatch(/2 to 5/);
    expect(campaignProblem({ type: "BOGO", title: "Two for one", listingIds: ["a"], startsAt: now, endsAt: null }, now)).toBeNull();
  });
});
