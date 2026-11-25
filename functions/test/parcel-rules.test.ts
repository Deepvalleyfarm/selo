import { describe, expect, it } from "vitest";
import {
  applyAttempt, batchProblem, canIssueCode, canMoveParcel, CITIES, cityOf, codeMatches, deliveryFeeNgwee, generateDeliveryCode, generatePickupPin,
  hashCode, insuranceNgwee, isTrivialCode, PARCEL_DEFAULTS as C, quoteParcel, riderChatBlocked, riderSplit, sameCityProblem, sendingFeeNgwee,
  suggestCategory, weightOk, withinGeofence, type CodeState, formatK, neighbourhoodFallback,
} from "../src/parcel-rules";

describe("§3 fee engine", () => {
  it("delivery fee = K30 + K4.50/km, minimum K30", () => {
    expect(deliveryFeeNgwee(5, C)).toBe(5_250);
    expect(deliveryFeeNgwee(0, C)).toBe(3_000);
    expect(deliveryFeeNgwee(20, C)).toBe(12_000);
  });
  it("sending fee: 15%, capped at 50% of the delivery fee, K10 floor (spec examples)", () => {
    expect(sendingFeeNgwee(5_250, C)).toBe(1_000);   // raw K7.88 → K10.00
    expect(sendingFeeNgwee(12_000, C)).toBe(1_800);  // K18.00
    expect(sendingFeeNgwee(100_000, { ...C, sendingFeeRate: 80 })).toBe(50_000); // never above 50%
  });
  it("rider split is 89 / 10 / 1 of the delivery fee and adds up exactly", () => {
    const s = riderSplit(5_250, C);
    expect(s).toEqual({ platformFeeNgwee: 525, socialFundNgwee: 53, riderEarningsNgwee: 4_672 });
    expect(s.platformFeeNgwee + s.socialFundNgwee + s.riderEarningsNgwee).toBe(5_250);
  });
  it("insurance: 2% of declared value up to K5,000, minimum K5", () => {
    expect(insuranceNgwee(0, C)).toBe(0); expect(insuranceNgwee(10_000, C)).toBe(500);
    expect(insuranceNgwee(300_000, C)).toBe(6_000); expect(insuranceNgwee(2_000_000, C)).toBe(10_000);
  });
  it("quote totals delivery + sending (+ insurance) with a 5-minute ETA buffer", () => {
    const q = quoteParcel(5, 14, 0, C);
    expect(q.totalNgwee).toBe(6_250); expect(q.etaMin).toBe(19); expect(formatK(q.totalNgwee)).toBe("K62.50");
  });
  it("weight is limited to the five bands (≤ 5 kg)", () => { expect(weightOk("4–5 kg")).toBe(true); expect(weightOk("6 kg")).toBe(false); });
  it("suggests a category from the description", () => { expect(suggestCategory("Signed contract papers")).toBe("Document"); expect(suggestCategory("2 chitenge dresses")).toBe("Clothing"); });
});

describe("§1 / §11 cities", () => {
  it("seeds all 20 supported cities with neighbourhoods", () => {
    expect(CITIES).toHaveLength(20);
    expect(CITIES.map((c) => c.name)).toContain("Mufulira");
    expect(CITIES.every((c) => c.neighbourhoods.length >= 6)).toBe(true);
  });
  it("detects the city of a point and blocks inter-city", () => {
    expect(cityOf({ lat: -15.42, lng: 28.32 })?.name).toBe("Lusaka");
    expect(cityOf({ lat: -12.96, lng: 28.64 })?.name).toBe("Ndola");
    expect(cityOf({ lat: -15.56, lng: 28.27 })?.name).toBe("Chilanga"); // nearer Chilanga than Lusaka centre
    expect(cityOf({ lat: -10, lng: 30 })).toBeNull();
    expect(sameCityProblem({ lat: -15.42, lng: 28.32 }, { lat: -15.40, lng: 28.30 })).toBeNull();
    expect(sameCityProblem({ lat: -15.42, lng: 28.32 }, { lat: -12.96, lng: 28.64 })).toBe("SeloNaChipa only supports same-city deliveries. Please enter a Lusaka address.");
  });
  it("neighbourhood stand-in positions stay inside their city", () => {
    CITIES.forEach((c) => c.neighbourhoods.forEach((nb) => expect(cityOf(neighbourhoodFallback(c, nb))?.id).toBe(c.id)));
  });
});

describe("§5 state machine", () => {
  it("only allows the documented transitions", () => {
    expect(canMoveParcel("IN_TRANSIT", "AWAITING_DELIVERY_CODE")).toBe(true);
    expect(canMoveParcel("AWAITING_DELIVERY_CODE", "DELIVERED")).toBe(true);
    expect(canMoveParcel("IN_TRANSIT", "DELIVERED")).toBe(false); // never without a code
    expect(canMoveParcel("AWAITING_RIDER", "CANCELLED")).toBe(true);
    expect(canMoveParcel("IN_TRANSIT", "CANCELLED")).toBe(false);
  });
});

describe("🔒 §9.2 code generation", () => {
  it("always 5 digits, never trivial, from the injected secure RNG", () => {
    const seq = [0, 11111, 12345, 4821]; let i = 0;
    expect(generateDeliveryCode(() => seq[i++])).toBe("04821");
    for (let k = 0; k < 500; k++) { const c = generateDeliveryCode(); expect(c).toMatch(/^\d{5}$/); expect(isTrivialCode(c)).toBe(false); }
    expect(generatePickupPin(() => 7)).toBe("0007");
  });
  it("stores only an HMAC bound to the order; constant-time compare", () => {
    const h = hashCode("s3cret", "PCL-1", "48213");
    expect(h).toMatch(/^[0-9a-f]{64}$/); expect(h).not.toContain("48213");
    expect(codeMatches("s3cret", "PCL-1", "48213", h)).toBe(true);
    expect(codeMatches("s3cret", "PCL-2", "48213", h)).toBe(false); // bound to the order
    expect(codeMatches("other", "PCL-1", "48213", h)).toBe(false);
    expect(codeMatches("s3cret", "PCL-1", "4821", h)).toBe(false);
  });
});

describe("🔒 §9.3 brute-force policy", () => {
  const now = 1_000_000;
  const base: CodeState = { hash: "x", state: "sent", issuedAt: now, expiresAt: now + 15 * 60_000, attempts: 0, codesIssued: 1, channel: "push", smsResends: 0 };
  it("locks after 5 wrong attempts", () => {
    let s: CodeState | null = base; const left: number[] = [];
    for (let k = 0; k < 4; k++) { const r = applyAttempt(s, false, now, C); s = r.next; if (r.outcome.result === "wrong") left.push(r.outcome.attemptsLeft); }
    expect(left).toEqual([4, 3, 2, 1]);
    const last = applyAttempt(s, false, now, C); expect(last.outcome.result).toBe("locked"); expect(last.next?.state).toBe("locked");
    expect(applyAttempt(last.next, true, now, C).outcome.result).toBe("locked"); // even the right code fails once locked
  });
  it("expired codes can't be used; verified codes can't be reused", () => {
    expect(applyAttempt(base, true, base.expiresAt + 1, C).outcome.result).toBe("expired");
    const v = applyAttempt(base, true, now, C).next!; expect(applyAttempt(v, true, now, C).outcome.result).toBe("already_verified");
  });
  it("cooldown of 2 minutes after a lock and at most 3 codes per order", () => {
    const locked = { ...base, state: "locked" as const, lockedAt: now };
    expect(canIssueCode(locked, now + 60_000, C).ok).toBe(false);
    expect(canIssueCode(locked, now + 121_000, C)).toEqual({ ok: true, reuse: false });
    expect(canIssueCode({ ...locked, codesIssued: 3 }, now + 999_999, C).ok).toBe(false);
    expect(canIssueCode(base, now, C)).toEqual({ ok: true, reuse: true }); // live code is reused, not regenerated
    expect(canIssueCode({ ...base, state: "expired" }, now, C)).toEqual({ ok: true, reuse: false });
  });
});

describe("geofence, batching and chat safety", () => {
  it("150 m geofence", () => {
    const t = { lat: -15.4000, lng: 28.3000 };
    expect(withinGeofence({ lat: -15.4010, lng: 28.3000 }, t, 150)).toBe(true);   // ~111 m
    expect(withinGeofence({ lat: -15.4020, lng: 28.3000 }, t, 150)).toBe(false);  // ~222 m
  });
  it("batch: max 4, pickups within 3 km, same city", () => {
    const a = { lat: -15.42, lng: 28.30 };
    expect(batchProblem([a], { lat: -15.43, lng: 28.31 }, C)).toBeNull();
    expect(batchProblem([a], { lat: -15.36, lng: 28.36 }, C)).toContain("3 km");
    expect(batchProblem([a, a, a, a], a, C)).toContain("up to 4");
  });
  it("riders can't send codes or the words code/PIN", () => {
    expect(riderChatBlocked("tell me 4 8 2 1 3")).toBe(true); expect(riderChatBlocked("what's the CODE?")).toBe(true);
    expect(riderChatBlocked("send your pin")).toBe(true); expect(riderChatBlocked("I'm at the gate, 5 min")).toBe(false);
  });
});
