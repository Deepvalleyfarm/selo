import { identityOf } from "../src/auth";
import { describe, expect, it } from "vitest";
import { agentCommission, canTransition, insuranceId, milestoneReached, nextRetryMinutes, normaliseAnswer,
  normaliseMsisdn, pinProblem, quote, split } from "../src/domain";
import { DEFAULT_CONFIG } from "../src/domain-defaults";
import { createHmac } from "node:crypto";

describe("auth rules", () => {
  it("accepts strong 5-digit PINs only", () => {
    expect(pinProblem("48291")).toBeNull();
    expect(pinProblem("1234")).not.toBeNull();
    expect(pinProblem("12345")).not.toBeNull();
    expect(pinProblem("00000")).not.toBeNull();
    expect(pinProblem(48291)).not.toBeNull();
  });
  it("normalises Zambian numbers", () => {
    expect(normaliseMsisdn("097 123 4512")).toBe("+260971234512");
    expect(normaliseMsisdn("+260 96-123-4512")).toBe("+260961234512");
    expect(normaliseMsisdn("0811234512")).toBeNull();
  });
  it("normalises security answers before hashing", () => {
    expect(normaliseAnswer("  Manchester   UNITED ")).toBe("manchester united");
  });
});

describe("escrow money rules", () => {
  it("matches the PRD worked example (K200 goods, K40 delivery)", () => {
    const s = split(20_000, 4_000, DEFAULT_CONFIG);
    expect(s).toEqual({ sellerNetNgwee: 19_500, platformFeeNgwee: 500, riderNetNgwee: 3_480, riderPlatformFeeNgwee: 320, socialFundNgwee: 200 });
  });
  it("prices insured parcels", () => {
    const q = quote(0, 7.4, DEFAULT_CONFIG, 350_000);
    expect(q.insurancePremiumNgwee).toBe(7_000);
    expect(q.deliveryNgwee).toBe(8_570); // K45 + 7.4 km × K5.50
    expect(q.totalNgwee).toBe(15_570);
  });
  it("delivery fee engine: base K45 + K5.50/km (8 km → K89)", () => {
    expect(quote(0, 8, DEFAULT_CONFIG).deliveryNgwee).toBe(8_900);
    expect(quote(0, 8, { ...DEFAULT_CONFIG, deliveryBaseFee: 30, deliveryFeePerKm: 4 }).deliveryNgwee).toBe(6_200);
  });
  it("caps declared value at K20,000", () => {
    expect(quote(0, 1, DEFAULT_CONFIG, 5_000_000).insurancePremiumNgwee).toBe(40_000);
  });
  it("computes agent commission net of the 5% cut", () => {
    expect(agentCommission(20_000, 10, DEFAULT_CONFIG)).toEqual({ gross: 2_000, net: 1_900 });
  });
  it("enforces the order state machine", () => {
    expect(canTransition("INITIATED", "ESCROW_LOCKED")).toBe(true);
    expect(canTransition("INITIATED", "DELIVERED")).toBe(false);
    expect(canTransition("PICKED_UP", "REFUNDED")).toBe(false);
    expect(canTransition("RIDER_ASSIGNED", "BROADCASTING")).toBe(true);
  });
  it("unlocks the Social Fund every 20 deliveries", () => {
    expect(milestoneReached(20, 20)).toBe(true);
    expect(milestoneReached(21, 20)).toBe(false);
    expect(milestoneReached(0, 20)).toBe(false);
  });
  it("retries payouts with backoff then escalates", () => {
    expect([0, 1, 2, 3, 4].map(nextRetryMinutes)).toEqual([1, 5, 15, 60, 60]);
    expect(nextRetryMinutes(27)).toBeNull();
  });
  it("formats insurance IDs", () => { expect(insuranceId()).toMatch(/^INS-[A-Z2-9]{4}-[A-Z2-9]{4}$/); });
});


import { SECURITY_QUESTION_BANK } from "../src/domain-defaults";
describe("security question bank", () => {
  it("has 12 questions with the same ids as the app", () => {
    expect(Object.keys(SECURITY_QUESTION_BANK)).toHaveLength(12);
    expect(SECURITY_QUESTION_BANK.q_favourite_dish).toBe("What is your favourite traditional Zambian dish?");
  });
});

describe("sign-up identity", () => {
  it("accepts NRC only as XXXXXX/XX/X", () => {
    expect(identityOf({ idType: "NRC", idNumber: "123456/78/1" })).toMatchObject({ idType: "NRC", idNumber: "123456/78/1", key: "NRC_123456781" });
    expect(typeof identityOf({ idType: "NRC", idNumber: "123456781" })).toBe("string");
    expect(typeof identityOf({ idType: "NRC", idNumber: "12345/78/1" })).toBe("string");
  });
  it("accepts passports and driver's licences, rejects missing types", () => {
    expect(identityOf({ idType: "Passport", idNumber: "zn123456" })).toMatchObject({ idNumber: "ZN123456" });
    expect(identityOf({ idType: "Driver's licence", idNumber: "DL-2201934" })).toMatchObject({ idType: "Driver's licence" });
    expect(identityOf({})).toMatch(/Choose your ID type/);
    expect(identityOf({ nrc: "123456/78/1" })).toMatchObject({ idType: "NRC" }); // older app versions
  });
});
