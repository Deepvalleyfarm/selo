import { describe, expect, it } from "vitest";
import { adImageUrl, cleanCopy, layerText } from "../src/adgen";
import { monthRange, prevMonth, riderMetrics, sellerMetrics } from "../src/reports";
import { sosSms } from "../src/safety";

describe("AI ad generator", () => {
  it("escapes Cloudinary text layers", () => {
    expect(layerText("Fresh, ripe 5kg/bag")).toBe("Fresh%252C%20ripe%205kg%252Fbag");
  });
  it("builds story and square images around the real photo", () => {
    const u = adImageUrl("demo", "selo/adgen/u/abc", { w: 1080, h: 1920, headline: "Fresh tomatoes", price: "K 80", offer: null });
    expect(u).toContain("w_1080,h_1920,c_pad");
    expect(u).toContain("K%2080");
    expect(u.endsWith("/selo/adgen/u/abc")).toBe(true);
  });
  it("cleans the AI copy and hashtags", () => {
    const c = cleanCopy({ headline: "  Big   deal ", hashtags: ["Lusaka", "#Zambia Biz", ""], whatsapp: "x".repeat(500) }, "Tomatoes");
    expect(c.headline).toBe("Big deal");
    expect(c.hashtags).toEqual(["#Lusaka", "#ZambiaBiz"]);
    expect(c.whatsapp.length).toBe(300);
    expect(c.productName).toBe("Tomatoes");
  });
});

describe("monthly performance report", () => {
  it("uses Lusaka month boundaries", () => {
    const [s, e] = monthRange("2026-11");
    expect(s.toISOString()).toBe("2026-10-31T22:00:00.000Z");
    expect(e.toISOString()).toBe("2026-11-30T22:00:00.000Z");
    expect(prevMonth("2026-01")).toBe("2025-12");
  });
  it("seller: sales, net after flat fee, top product, deal share", () => {
    const o = (goods: number, promo: string | null = null, state = "COMPLETED") => ({ state, goodsNgwee: goods, items: [{ title: "Tomatoes", qty: 2, unitNgwee: goods / 2, lineNgwee: goods, promo }] });
    const r = sellerMetrics([o(10_000), o(20_000, "c1"), o(5_000, null, "REFUNDED")], [o(10_000)], 500);
    const m = Object.fromEntries(r.metrics.map((x) => [x.key, x.value]));
    expect(m.sales).toBe(30_000); expect(m.net).toBe(29_000); expect(m.orders).toBe(2); expect(m.promoShare).toBe(67); expect(m.cancelRate).toBe(33);
    expect(r.top[0]).toMatchObject({ title: "Tomatoes", units: 4 });
    expect(r.highlights[0]).toMatch(/up 200%/);
  });
  it("rider: earnings net of cover premium, deliveries, fund", () => {
    const e = { type: "delivery_credit", net: 2_000, bonus: 100, socialLevy: 120, km: 4.4, insurancePremium: 200 };
    const r = riderMetrics([e, e], [e], { acceptanceRate: 70, completedDeliveries: 9, failedDeliveries: 1 });
    const m = Object.fromEntries(r.metrics.map((x) => [x.key, x.value]));
    expect(m.earnings).toBe(3_800); expect(m.deliveries).toBe(2); expect(m.fund).toBe(240); expect(m.completion).toBe(90);
    expect(r.tips.join(" ")).toMatch(/acceptance rate/);
  });
});

describe("rider SOS", () => {
  it("texts the emergency contact a map link and the live page", () => {
    const t = sosSms("Chanda Mulenga", "+260971234514", -15.41, 28.28, "https://x/s/abc", "SOS");
    expect(t).toContain("https://maps.google.com/?q=-15.410000,28.280000");
    expect(t).toContain("https://x/s/abc");
    expect(t.length).toBeLessThan(300);
  });
});

import { coverProblem } from "../src/safety";
import { withCover } from "../src/deliveryConfirmation";
describe("rider cover stays off until an insurer is configured", () => {
  const insurer = { enabled: true, insurerName: "Example Insurance Zambia Ltd", policyNumber: "GRP-2026-0001", claimsPhone: "+260971000000",
    claimsEmail: "claims@example.co.zm", termsUrl: "https://example.co.zm/rider-cover", premiumNgwee: 200, coverNgwee: 1_000_000, benefits: "Accident medical costs up to K 10,000." };
  it("requires every insurer field before it can be switched on", () => {
    expect(coverProblem(insurer)).toBeNull();
    expect(coverProblem({ ...insurer, insurerName: "" })).toMatch(/insurer/);
    expect(coverProblem({ ...insurer, policyNumber: "" })).toMatch(/policy number/);
    expect(coverProblem({ ...insurer, termsUrl: "http://x" })).toMatch(/terms/);
  });
  it("deducts nothing unless cover is on, the setup is complete and the rider opted in", () => {
    const optedIn = { insurance: { on: true } };
    expect(withCover(optedIn, undefined).coverPremiumNgwee).toBe(0);                       // never configured
    expect(withCover(optedIn, { ...insurer, enabled: false }).coverPremiumNgwee).toBe(0);  // switched off
    expect(withCover(optedIn, { ...insurer, policyNumber: "" }).coverPremiumNgwee).toBe(0); // incomplete
    expect(withCover({ insurance: { on: false } }, insurer).coverPremiumNgwee).toBe(0);    // rider didn't opt in
    expect(withCover(optedIn, insurer)).toMatchObject({ coverPremiumNgwee: 200, coverInsurer: "Example Insurance Zambia Ltd" });
  });
});

import { remittanceProblem } from "../src/safety";
describe("mark rider cover premiums as paid", () => {
  const rows = [{ id: "a", amountNgwee: 200, insurer: "Ins" }, { id: "b", amountNgwee: 200, insurer: "Ins" }];
  it("accepts a matching amount with a reference", () => expect(remittanceProblem(rows, ["a", "b"], 400, "BANK-1234")).toBeNull());
  it("rejects a wrong amount, missing reference, already paid or mixed insurers", () => {
    expect(remittanceProblem(rows, ["a", "b"], 300, "BANK-1234")).toMatch(/doesn't match/);
    expect(remittanceProblem(rows, ["a", "b"], 400, "")).toMatch(/reference/);
    expect(remittanceProblem([{ ...rows[0], remitted: true }, rows[1]], ["a", "b"], 400, "BANK-1234")).toMatch(/already/);
    expect(remittanceProblem([rows[0], { ...rows[1], insurer: "Other" }], ["a", "b"], 400, "BANK-1234")).toMatch(/one insurer/);
    expect(remittanceProblem([rows[0]], ["a", "b"], 400, "BANK-1234")).toMatch(/no longer exist/);
  });
});

import { agentMetrics } from "../src/reports";
describe("role-specific reports", () => {
  it("rider: earnings split by orders and parcels, best day, busiest area, rider tips", () => {
    const at = new Date("2026-11-13T10:00:00Z"); // a Friday
    const e = (kind: string, net: number) => ({ type: "delivery_credit", kind, net, bonus: 0, socialLevy: 100, km: 3, gross: net + 300, platformFee: 200, dropArea: "Kabwata", at });
    const r = riderMetrics([e("order", 2_000), e("parcel", 3_000), e("parcel", 3_000)], [], { acceptanceRate: 95 });
    expect(r.breakdowns[0].rows.map((x) => x.value)).toEqual(["K 20.00", "K 60.00"]);
    expect(r.highlights.join(" ")).toMatch(/Fridays/);
    expect(r.tips.join(" ")).toMatch(/Kabwata/);
    expect(r.tips.join(" ")).not.toMatch(/Marketing Centre|bundle/);
  });
  it("agent: commission at each seller's rate, net of the platform cut, agent tips", () => {
    const o = (seller: string, goods: number) => ({ state: "COMPLETED", goodsNgwee: goods, sellerUid: seller, sellerName: seller });
    const r = agentMetrics([o("s1", 10_000), o("s2", 20_000)], [], new Map([["s1", 10], ["s2", 5]]), 20, { cur: 0, prev: 0 }, { cur: 2, prev: 0 });
    const m = Object.fromEntries(r.metrics.map((x) => [x.key, x.value]));
    expect(m.commission).toBe(800 + 800); // (1000 − 20%) + (1000 − 20%)
    expect(r.breakdowns[0].title).toBe("Commission by seller");
    expect(r.tips.join(" ")).toMatch(/Onboard/);
    expect(r.tips.join(" ")).not.toMatch(/acceptance rate|Marketing Centre/);
  });
});
