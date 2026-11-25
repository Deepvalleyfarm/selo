import { describe, expect, it } from "vitest";
import { acceptanceRate, claimProblem, heroBonus, riderTier, split, withinWorkingHours } from "../src/domain";
import { DEFAULT_CONFIG } from "../src/domain-defaults";

const at = (iso: string) => Date.parse(iso) - 2 * 3_600_000; // Lusaka local time → UTC ms

describe("rider money (PRD 6.5)", () => {
  it("takes 8% platform fee and 5% Social Fund from the delivery fee", () => {
    const s = split(0, 4_000, DEFAULT_CONFIG);
    expect(s.riderPlatformFeeNgwee).toBe(320); expect(s.socialFundNgwee).toBe(200); expect(s.riderNetNgwee).toBe(3_480);
  });
  it("adds the Hero bonus only for Hero and Ambassador riders", () => {
    expect(heroBonus(3_480, "Hero", 10)).toBe(348); expect(heroBonus(3_480, "Ambassador", 10)).toBe(348);
    expect(heroBonus(3_480, "Rising", 10)).toBe(0); expect(heroBonus(3_480, "Hero", 0)).toBe(0);
  });
  it("keeps the Social Fund ring-fenced by default", () => { expect(DEFAULT_CONFIG.socialFundMilestoneDeliveries).toBe(0); });
});

describe("tiers and acceptance", () => {
  it("moves through tiers by deliveries", () => {
    expect(riderTier(0)).toBe("Starter"); expect(riderTier(50)).toBe("Rising"); expect(riderTier(300)).toBe("Hero");
    expect(riderTier(320, true)).toBe("Ambassador"); expect(riderTier(120, true)).toBe("Rising");
  });
  it("computes acceptance rate", () => { expect(acceptanceRate(0, 0)).toBe(100); expect(acceptanceRate(46, 50)).toBe(92); });
});

describe("working hours (PRD 6.10)", () => {
  const hours = [{ day: 0, on: true, start: "07:00", end: "19:00" }, { day: 5, on: true, start: "08:00", end: "14:00" }, { day: 6, on: false, start: "07:00", end: "19:00" },
    { day: 4, on: true, start: "20:00", end: "02:00" }];
  it("only dispatches inside the rider's hours, Lusaka time", () => {
    expect(withinWorkingHours(hours, at("2026-11-23T09:30:00Z"))).toBe(true);   // Monday morning
    expect(withinWorkingHours(hours, at("2026-11-23T19:30:00Z"))).toBe(false);  // Monday evening
    expect(withinWorkingHours(hours, at("2026-11-28T15:00:00Z"))).toBe(false);  // Saturday after 14:00
    expect(withinWorkingHours(hours, at("2026-11-29T10:00:00Z"))).toBe(false);  // Sunday off
    expect(withinWorkingHours(hours, at("2026-11-27T23:00:00Z"))).toBe(true);   // Friday overnight shift
    expect(withinWorkingHours(hours, at("2026-11-24T10:00:00Z"))).toBe(false);  // Tuesday not listed
    expect(withinWorkingHours(undefined, at("2026-11-24T03:00:00Z"))).toBe(true);
  });
});

describe("Social Fund claims (PRD 6.8)", () => {
  it("checks category, tier, photo and balance", () => {
    expect(claimProblem("MEDICAL", 20_000, 41_250, "Rising", false)).toBeNull();
    expect(claimProblem("SCHOOL", 20_000, 41_250, "Rising", false)).toContain("Hero tier");
    expect(claimProblem("SCHOOL", 20_000, 41_250, "Hero", false)).toBeNull();
    expect(claimProblem("BIKE", 20_000, 41_250, "Rising", false)).toContain("photo");
    expect(claimProblem("FUNERAL", 50_000, 41_250, "Rising", false)).toContain("up to your fund balance");
    expect(claimProblem("PARTY", 1, 41_250, "Rising", false)).not.toBeNull();
  });
});
