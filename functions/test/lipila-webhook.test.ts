import { describe, expect, it, vi } from "vitest";
import crypto from "node:crypto";
import { isFinalFailure, isSuccessStatus, parseSecrets, sign, toPayload, verifyLipilaSignature, WebhookVerificationError } from "../src/payments/lipila-webhook";

// A throwaway test secret (NOT the production one).
const SECRET = "whsec_" + Buffer.from("test-secret-0123456789abcdef").toString("base64");
const secrets = parseSecrets(SECRET);
const body = Buffer.from(JSON.stringify({ referenceId: "8fa81447-4a14-4c2f-925b-78be40047c63", currency: "ZMW", amount: 1.75, accountNumber: "260971234567",
  status: "Successful", paymentType: "AirtelMoney", type: "Collection", ipAddress: "41.1.1.1", identifier: "SELO-COL-SNC-1", message: "Transaction successful",
  externalId: "MP2406.1234", referenceData: "SeloNaChipa order SNC-1" }));
const now = 1_750_000_000_000;
const ts = String(Math.floor(now / 1000));
const headers = (sig: string, t = ts, id = "msg_2Lk") => ({ "webhook-id": id, "webhook-timestamp": t, "webhook-signature": sig });

describe("Lipila webhook verification (Standard Webhooks, HMAC-SHA256)", () => {
  it("valid signature passes", () => {
    expect(verifyLipilaSignature(body, headers(sign(secrets[0], "msg_2Lk", ts, body)), secrets, { nowMs: now }).webhookId).toBe("msg_2Lk");
  });
  it("accepts the raw base64 secret with or without the whsec_ prefix", () => {
    const raw = SECRET.replace("whsec_", "");
    expect(parseSecrets(raw)[0].equals(secrets[0])).toBe(true);
  });
  it("tampered body fails", () => {
    const sig = sign(secrets[0], "msg_2Lk", ts, body);
    const tampered = Buffer.from(body.toString().replace("1.75", "175.00"));
    expect(() => verifyLipilaSignature(tampered, headers(sig), secrets, { nowMs: now })).toThrow(WebhookVerificationError);
  });
  it("tampered webhook-id fails", () => {
    expect(() => verifyLipilaSignature(body, headers(sign(secrets[0], "msg_2Lk", ts, body), ts, "msg_other"), secrets, { nowMs: now })).toThrow(/does not match/);
  });
  it("expired timestamp (> 300 s) fails before the signature is checked", () => {
    const old = String(Math.floor(now / 1000) - 301);
    const spy = vi.spyOn(crypto, "timingSafeEqual");
    expect(() => verifyLipilaSignature(body, headers(sign(secrets[0], "msg_2Lk", old, body), old), secrets, { nowMs: now })).toThrow(/tolerance/);
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });
  it("future timestamps beyond tolerance fail too", () => {
    const future = String(Math.floor(now / 1000) + 400);
    expect(() => verifyLipilaSignature(body, headers(sign(secrets[0], "msg_2Lk", future, body), future), secrets, { nowMs: now })).toThrow(/tolerance/);
  });
  it("multiple signatures (key rotation): one match is enough", () => {
    const other = Buffer.from("old-secret-xxxxxxxxxxxxxxxx");
    const hdr = `${sign(other, "msg_2Lk", ts, body)} ${sign(secrets[0], "msg_2Lk", ts, body)}`;
    expect(verifyLipilaSignature(body, headers(hdr), secrets, { nowMs: now }).webhookId).toBe("msg_2Lk");
  });
  it("multiple configured secrets (rotation on our side)", () => {
    const next = Buffer.from("next-secret-yyyyyyyyyyyyyyyy");
    const both = parseSecrets(`${SECRET} ${next.toString("base64")}`);
    expect(verifyLipilaSignature(body, headers(sign(next, "msg_2Lk", ts, body)), both, { nowMs: now }).webhookId).toBe("msg_2Lk");
  });
  it("missing headers or no secret fail", () => {
    expect(() => verifyLipilaSignature(body, {}, secrets, { nowMs: now })).toThrow(/Missing/);
    expect(() => verifyLipilaSignature(body, headers(sign(secrets[0], "msg_2Lk", ts, body)), [], { nowMs: now })).toThrow(/not configured/);
  });
  it("compares in constant time (crypto.timingSafeEqual)", () => {
    const spy = vi.spyOn(crypto, "timingSafeEqual");
    expect(() => verifyLipilaSignature(body, headers("v1,AAAA"), secrets, { nowMs: now })).toThrow();
    verifyLipilaSignature(body, headers(sign(secrets[0], "msg_2Lk", ts, body)), secrets, { nowMs: now });
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
  });
});

describe("payload and status semantics", () => {
  it("keeps every Lipila field", () => {
    const p = toPayload(JSON.parse(body.toString()));
    expect(p).toMatchObject({ amount: 1.75, paymentType: "AirtelMoney", type: "Collection", identifier: "SELO-COL-SNC-1", externalId: "MP2406.1234", referenceData: "SeloNaChipa order SNC-1" });
  });
  it("final vs non-final statuses", () => {
    expect(isSuccessStatus("Successful")).toBe(true);
    for (const s of ["Failed", "Wrong PIN", "Wrong Number", "Insufficient Funds", "Transaction Limit Exceeded", "Cancelled"]) expect(isFinalFailure(s)).toBe(true);
    for (const s of ["Pending", "Timeout", "Network Error", "System Error", "Unknown"]) { expect(isFinalFailure(s)).toBe(false); expect(isSuccessStatus(s)).toBe(false); }
  });
});

import { buildTransactionsCsv } from "../src/payments/tx-export";
describe("transaction CSV export", () => {
  it("adds a BOM, escapes commas/quotes and blocks formulas", () => {
    const csv = buildTransactionsCsv([{ processedAt: "2026-11-26T10:00:00Z", referenceId: "r1", amount: 1.75, message: 'Declined, "insufficient"', referenceData: "=HYPERLINK(1)" }]);
    expect(csv.charCodeAt(0)).toBe(0xfeff);
    expect(csv).toContain('"Declined, ""insufficient"""'); expect(csv).toContain("'=HYPERLINK(1)"); expect(csv).toContain(",1.75,");
  });
});
