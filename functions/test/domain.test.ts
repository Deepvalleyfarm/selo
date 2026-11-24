import { describe, expect, it } from "vitest";
import { agentCommission, canTransition, insuranceId, milestoneReached, nextRetryMinutes, normaliseAnswer,
  normaliseMsisdn, pinProblem, quote, split } from "../src/domain";
import { DEFAULT_CONFIG } from "../src/domain-defaults";
import { verifyLipilaSignature } from "../src/lipila-signature";
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
    expect(s).toEqual({ sellerNetNgwee: 19_500, platformFeeNgwee: 500, riderNetNgwee: 3_920, riderPlatformFeeNgwee: 0, socialFundNgwee: 80 });
  });
  it("prices insured parcels", () => {
    const q = quote(0, 7.4, DEFAULT_CONFIG, 350_000);
    expect(q.insurancePremiumNgwee).toBe(7_000);
    expect(q.totalNgwee).toBe(11_200);
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

describe("Lipila webhook signature", () => {
  it("verifies HMAC-SHA256 and rejects tampering", () => {
    const body = Buffer.from('{"referenceId":"SELO-COL-SNC-1","status":"successful"}');
    const sig = createHmac("sha256", "s3cret").update(body).digest("hex");
    expect(verifyLipilaSignature(body, sig, "s3cret")).toBe(true);
    expect(verifyLipilaSignature(body, sig, "other")).toBe(false);
    expect(verifyLipilaSignature(Buffer.from("{}"), sig, "s3cret")).toBe(false);
    expect(verifyLipilaSignature(body, undefined, "s3cret")).toBe(false);
  });
});
