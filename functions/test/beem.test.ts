import { beforeEach, describe, expect, it } from "vitest";
import { authHeader, cleanMsisdn, maskMsisdn, type BeemConfig } from "../src/beem/beem.config";
import { _setTransport } from "../src/beem/beem.http";
import { BeemOtpService } from "../src/beem/beem-otp.service";
import { BeemSmsService } from "../src/beem/beem-sms.service";
import { checkBeemHealth } from "../src/beem/beem-health.service";
import { memoryStore } from "../src/beem/beem.store";
import { SMS_TEMPLATES } from "../src/beem/sms-templates";

const cfg: BeemConfig = { apiKey: "k", secretKey: "s", appId: 226405, senderId: "Mabala", appName: "Selonachipa", application: "selonachipa",
  otpBaseUrl: "https://apiotp.beem.africa/v1", smsBaseUrl: "https://apisms.beem.africa/v1", balanceUrl: "https://apisms.beem.africa/public/v1/vendors/balance",
  countryCodes: ["260"], healthMode: "probe", healthMsisdn: "" };
type Call = { url: string; body: any; auth: string };
let calls: Call[] = [];
function transport(responder: (url: string, body: any) => { status: number; json?: unknown } | "network") {
  calls = [];
  _setTransport((async (url: any, init: any) => {
    const body = init?.body ? JSON.parse(init.body) : undefined;
    calls.push({ url: String(url), body, auth: init.headers.Authorization });
    const r = responder(String(url), body);
    if (r === "network") throw new TypeError("fetch failed");
    return new Response(JSON.stringify(r.json ?? {}), { status: r.status });
  }) as typeof fetch, async () => undefined);
}

describe("number handling", () => {
  it("cleans and validates Zambian numbers", () => {
    expect(cleanMsisdn("+260 977-001 001")).toBe("260977001001");
    expect(cleanMsisdn("0977001001")).toBe("260977001001");
    expect(cleanMsisdn("260955123456")).toBe("260955123456");
    expect(cleanMsisdn("260211123456")).toBeNull(); // landline
    expect(cleanMsisdn("26097700100")).toBeNull();  // 11 digits
    expect(cleanMsisdn("abc")).toBeNull();
  });
  it("masks first 6 and last 3 digits", () => { expect(maskMsisdn("260977001001")).toBe("260977***001"); });
  it("builds Basic auth from key:secret", () => { expect(authHeader({ apiKey: "a", secretKey: "b" })).toBe(`Basic ${Buffer.from("a:b").toString("base64")}`); });
});

describe("OTP request / verify", () => {
  let store: ReturnType<typeof memoryStore>; let svc: BeemOtpService;
  beforeEach(() => { store = memoryStore(); svc = new BeemOtpService(store, () => cfg); });

  it("sends {appId, msisdn} and returns pinId (never the PIN)", async () => {
    transport(() => ({ status: 200, json: { data: { pinId: "pin-1", message: { code: 100, message: "SMS sent successfully" } } } }));
    const r = await svc.request("0977 001 001", "signup", { ip: "1.2.3.4" });
    expect(r).toEqual({ success: true, pinId: "pin-1", expiresInSeconds: 60, msisdn: "260977001001" });
    expect(calls[0].url).toBe("https://apiotp.beem.africa/v1/request");
    expect(calls[0].body).toEqual({ appId: 226405, msisdn: "260977001001" });
    expect(calls[0].auth).toBe(authHeader(cfg));
    expect(store.otp[0]).toMatchObject({ event_type: "OTP_REQUESTED", success: true, pin_id: "pin-1", app_context: "signup", ip_address: "1.2.3.4" });
  });
  it("rejects invalid numbers before calling Beem", async () => {
    transport(() => ({ status: 200 }));
    const r = await svc.request("12345", "signup");
    expect(r.success).toBe(false); expect(calls).toHaveLength(0);
    if (!r.success) expect(r.message).toBe("That number is not valid. Please check and try again.");
  });
  it("allows 3 requests per number per 10 minutes", async () => {
    transport(() => ({ status: 200, json: { data: { pinId: "p", message: { code: 100 } } } }));
    for (let i = 0; i < 3; i++) expect((await svc.request("260977001001", "signup")).success).toBe(true);
    const r = await svc.request("260977001001", "signup");
    expect(r.success).toBe(false); if (!r.success) expect(r.reason).toBe("rate_limited");
    expect(calls).toHaveLength(3);
  });
  it("maps Beem error codes to user messages", async () => {
    transport(() => ({ status: 200, json: { data: { message: { code: 101, message: "Failed to send SMS" } } } }));
    const r = await svc.request("260977001001", "signup");
    expect(r.success).toBe(false); if (!r.success) { expect(r.beemCode).toBe(101); expect(r.message).toMatch(/couldn't send an SMS/); }
  });
  it("retries once on network failure, never on auth errors", async () => {
    transport(() => "network");
    expect((await svc.request("260977001001", "signup")).success).toBe(false);
    expect(calls).toHaveLength(2);
    transport(() => ({ status: 401, json: {} }));
    const r = await svc.request("260966001001", "signup");
    expect(calls).toHaveLength(1); if (!r.success) expect(r.reason).toBe("auth");
  });
  it("verifies only the pinId issued to that number, then 117 marks it verified", async () => {
    transport(() => ({ status: 200, json: { data: { pinId: "pin-A", message: { code: 100 } } } }));
    await svc.request("260977001001", "signup");
    transport(() => ({ status: 200, json: { data: { message: { code: 117, message: "Valid Pin" } } } }));
    const hijack = await svc.verify("pin-OTHER", "241663", "260977001001");
    expect(hijack.success).toBe(false); expect(calls).toHaveLength(0);
    const ok = await svc.verify("pin-A", "241663", "0977001001");
    expect(ok.success).toBe(true);
    expect(calls[0].body).toEqual({ pinId: "pin-A", pin: "241663" });
    expect(store.sessions.get("260977001001")?.verified).toBe(true);
    expect(store.otp.at(-1)).toMatchObject({ event_type: "OTP_VERIFIED", beem_code: 117, success: true });
    expect(JSON.stringify(store.otp)).not.toContain("241663");
  });
  it.each([[114, /incorrect/], [115, /expired/], [116, /Too many/], [113, /restart/], [118, /already been used/]])("code %i → clear message", async (code, re) => {
    transport(() => ({ status: 200, json: { data: { pinId: "p", message: { code: 100 } } } }));
    await svc.request("260977001001", "signup");
    transport(() => ({ status: 200, json: { data: { message: { code } } } }));
    const r = await svc.verify("p", "111111", "260977001001");
    expect(r.success).toBe(false); if (!r.success) expect(r.message).toMatch(re);
  });
});

describe("transactional SMS", () => {
  it("posts Beem's send body and logs it", async () => {
    const store = memoryStore(); const sms = new BeemSmsService(store, () => cfg);
    transport(() => ({ status: 200, json: { successful: true, code: 100, message: "Message Submitted Successfully" } }));
    const r = await sms.sendTemplate("PARCEL_COLLECTED", "+260977001001", ["Chanda", "PCL-1"], "PCL-1");
    expect(r.success).toBe(true);
    expect(calls[0].url).toBe("https://apisms.beem.africa/v1/send");
    expect(calls[0].body).toEqual({ source_addr: "Mabala", schedule_time: "", encoding: 0, message: SMS_TEMPLATES.PARCEL_COLLECTED("Chanda", "PCL-1"),
      recipients: [{ recipient_id: 1, dest_addr: "260977001001" }] });
    expect(store.sms[0]).toMatchObject({ event_type: "PARCEL_COLLECTED", reference_id: "PCL-1", application: "selonachipa", success: true, beem_code: 100 });
  });
  it("masks delivery codes in sms_logs", async () => {
    const store = memoryStore(); const sms = new BeemSmsService(store, () => cfg);
    transport(() => ({ status: 200, json: { successful: true, code: 100 } }));
    await sms.send({ to: "260977001001", message: SMS_TEMPLATES.DELIVERY_CODE("48213", "parcel PCL-1", 15), eventType: "DELIVERY_CODE" });
    expect(store.sms[0].message_body).not.toContain("48213");
  });
});

describe("health check", () => {
  it("probe mode: 102 on a dummy number + balance OK → LIVE, no SMS sent", async () => {
    transport((url) => url.includes("balance") ? { status: 200, json: { data: { credit_balance: 950 } } } : { status: 200, json: { data: { message: { code: 102 } } } });
    const h = await checkBeemHealth(() => cfg);
    expect(h.state).toBe("LIVE"); expect(h.balance).toBe(950);
    expect(calls.find((c) => c.url.endsWith("/request"))?.body.msisdn).toBe("260000000000");
  });
  it("401 → CONFIG ERROR, 107 → CONFIG ERROR, network → OFFLINE", async () => {
    transport(() => ({ status: 401, json: {} }));
    expect((await checkBeemHealth(() => cfg)).state).toBe("CONFIG_ERROR");
    transport((url) => url.includes("balance") ? { status: 200, json: { data: { credit_balance: 5 } } } : { status: 200, json: { data: { message: { code: 107 } } } });
    const h = await checkBeemHealth(() => cfg); expect(h.state).toBe("CONFIG_ERROR"); expect(h.otp.detail).toBe("Application inactive");
    transport(() => "network");
    expect((await checkBeemHealth(() => cfg)).state).toBe("OFFLINE");
  });
});
