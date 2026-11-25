/**
 * Parcels v2 QA (§22) against the Firestore emulator: the real transaction code paths in deliveryConfirmation.ts.
 * Run with: npm run test:rules   (firebase emulators:exec … vitest --config rules-test/vitest.config.ts)
 * The "receiver" learns the code the same way a real one does — from the SMS/push the server sends.
 */
process.env.DELIVERY_CODE_SECRET = "test-secret-with-enough-length";
process.env.SELO_SMS_SINK = "1";
process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "demo-selo";

import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { Timestamp } from "firebase-admin/firestore";
import type { Deps } from "../src/deliveryConfirmation";

let dc: typeof import("../src/deliveryConfirmation");
let db: FirebaseFirestore.Firestore;
let sink: { to: string; text: string }[];
let rules: typeof import("../src/parcel-rules");
let domain: typeof import("../src/domain-defaults");

const DROP = { lat: -15.4300, lng: 28.3280 }; // Woodlands, Lusaka
const AT_DOOR = { lat: -15.4301, lng: 28.3281 };
const FAR = { lat: -15.4500, lng: 28.3280 };
let n = 0;

beforeAll(async () => {
  dc = await import("../src/deliveryConfirmation");
  rules = await import("../src/parcel-rules");
  domain = await import("../src/domain-defaults");
  db = (await import("../src/config")).db;
  sink = (await import("../src/sms")).smsTestSink!;
});
beforeEach(() => { sink.length = 0; });

function deps(o: Partial<Deps> = {}): Deps {
  return { ...dc.liveDeps, cfg: async () => ({ ...domain.DEFAULT_CONFIG }), pushCode: async () => false, waitForAck: async () => false, ...o };
}
const lastCode = () => /\b(\d{5})\b/.exec(sink.filter((s) => /delivery code is/.test(s.text)).at(-1)?.text ?? "")?.[1] ?? "";

async function seedParcel(status = "IN_TRANSIT", rider = "rider-1", extra: Record<string, unknown> = {}) {
  const id = `PCL-T${++n}${Date.now() % 100000}`;
  const q = rules.quoteParcel(5, 14, 0, rules.PARCEL_DEFAULTS);
  await db.doc(`parcels/${id}`).set({ senderUid: "sender-1", senderMsisdn: "+260971110001", status, riderUid: rider, cityId: "lusaka",
    pickup: { lat: -15.42, lng: 28.30, address: "Soweto Market", neighbourhood: "Lusaka CBD" }, dropoff: { ...DROP, address: "Woodlands", neighbourhood: "Woodlands" },
    receiver: { name: "Mutale", msisdn: "+260955120210", uid: null }, description: "Documents", distanceKm: 5,
    fees: { deliveryNgwee: q.deliveryNgwee, sendingNgwee: q.sendingNgwee, insuranceNgwee: 0, totalNgwee: q.totalNgwee, riderEarningsNgwee: q.riderEarningsNgwee,
      platformFeeNgwee: q.platformFeeNgwee, socialFundNgwee: q.socialFundNgwee }, escrowStatus: "HELD", ...extra });
  await db.doc(`riderWallets/${rider}`).set({ availableNgwee: 10_000, pendingNgwee: q.riderEarningsNgwee, socialFundNgwee: 0, activeCount: 1, completedDeliveries: 10, tier: "Starter" }, { merge: true });
  await db.doc(`riderLocations/${rider}`).set({ ...AT_DOOR, at: Timestamp.now() });
  return { id, q };
}

const req = (id: string, rider = "rider-1", extra = {}) => ({ kind: "parcel" as const, id, riderUid: rider, ...extra });

describe("QA 1 · happy path: code verified → escrow released 89 / 10 / 1, sending fee to platform", () => {
  it("delivers and pays exactly once", async () => {
    const { id, q } = await seedParcel();
    const r = await dc.requestDeliveryCodeCore(req(id), deps());
    expect(r.state).toBe("sent"); expect(r.channel).toBe("sms");
    expect(JSON.stringify(r)).not.toMatch(/\b\d{5}\b(?!.*expiresAt)/); // the rider never gets the code back
    const code = lastCode(); expect(code).toMatch(/^\d{5}$/);
    const stored = (await db.doc(`parcels/${id}/deliveryCode/current`).get()).data()!;
    expect(JSON.stringify(stored)).not.toContain(code); // only the HMAC is stored
    expect((await db.doc(`parcels/${id}`).get()).get("status")).toBe("AWAITING_DELIVERY_CODE");
    const v = await dc.verifyDeliveryCodeCore({ ...req(id), code }, deps());
    expect(v).toEqual({ result: "verified", riderEarningsNgwee: q.riderEarningsNgwee });
    const p = (await db.doc(`parcels/${id}`).get()).data()!;
    expect(p.status).toBe("DELIVERED"); expect(p.escrowStatus).toBe("RELEASED");
    const w = (await db.doc("riderWallets/rider-1").get()).data()!;
    expect(w.availableNgwee).toBeGreaterThanOrEqual(10_000 + q.riderEarningsNgwee);
    const legs = (await db.collection("escrow_ledger").where("orderId", "==", id).where("type", "==", "RELEASED").get()).docs.map((d) => [d.get("leg"), d.get("amountNgwee")]);
    expect(legs).toEqual(expect.arrayContaining([["rider_earnings", 4_672], ["platform_fee", 525], ["social_fund", 53], ["sending_fee", 1_000]]));
  });
});

describe("QA 2 · outside the geofence", () => {
  it("blocks code generation and logs the attempt", async () => {
    const { id } = await seedParcel();
    await db.doc("riderLocations/rider-1").set({ ...FAR, at: Timestamp.now() });
    await expect(dc.requestDeliveryCodeCore(req(id), deps())).rejects.toThrow("You are not at the delivery location yet.");
    const logs = await db.collection("deliveryCodeAttempts").where("orderId", "==", id).get();
    expect(logs.docs[0].get("result")).toBe("outside_geofence");
    expect(sink.length).toBe(0);
  });
});

describe("QA 3 · brute force", () => {
  it("locks after 5 wrong codes, enforces the cooldown and flags repeat riders", async () => {
    const rider = `rider-bf-${n}`;
    for (let k = 0; k < 2; k++) await db.collection("codeLocks").add({ riderUid: rider, orderId: `x${k}`, at: Timestamp.now() });
    const { id } = await seedParcel("IN_TRANSIT", rider);
    await dc.requestDeliveryCodeCore(req(id, rider), deps());
    const right = lastCode(); const wrong = right === "99998" ? "99997" : "99998";
    const out = [];
    for (let k = 0; k < 5; k++) out.push((await dc.verifyDeliveryCodeCore({ ...req(id, rider), code: wrong }, deps())).result);
    expect(out).toEqual(["wrong", "wrong", "wrong", "wrong", "locked"]);
    expect((await dc.verifyDeliveryCodeCore({ ...req(id, rider), code: right }, deps())).result).toBe("locked");
    await expect(dc.requestDeliveryCodeCore(req(id, rider), deps())).rejects.toThrow(/Wait \d+ min/);
    expect((await db.doc(`riderFlags/${rider}`).get()).get("reason")).toBe("repeated_code_lockouts");
    expect((await db.collection("deliveryCodeAttempts").where("orderId", "==", id).where("action", "==", "verify").get()).size).toBe(6);
  });
});

describe("QA 4 · expiry and resend", () => {
  it("an expired code fails, a resend works and invalidates the old code", async () => {
    const { id } = await seedParcel();
    await dc.requestDeliveryCodeCore(req(id), deps());
    const old = lastCode();
    await db.doc(`parcels/${id}/deliveryCode/current`).update({ expiresAt: Date.now() - 1 });
    expect((await dc.verifyDeliveryCodeCore({ ...req(id), code: old }, deps())).result).toBe("expired");
    await dc.requestDeliveryCodeCore(req(id, "rider-1", { forceNew: true }), deps());
    const fresh = lastCode();
    if (fresh !== old) expect((await dc.verifyDeliveryCodeCore({ ...req(id), code: old }, deps())).result).toBe("wrong");
    expect((await dc.verifyDeliveryCodeCore({ ...req(id), code: fresh }, deps())).result).toBe("verified");
  });
});

describe("QA 5 · idempotency", () => {
  it("concurrent and repeated verifications release escrow exactly once", async () => {
    const rider = `rider-idem-${n}`;
    const { id, q } = await seedParcel("IN_TRANSIT", rider);
    await dc.requestDeliveryCodeCore(req(id, rider), deps());
    const code = lastCode();
    const before = (await db.doc(`riderWallets/${rider}`).get()).get("availableNgwee");
    const res = await Promise.all([1, 2, 3].map(() => dc.verifyDeliveryCodeCore({ ...req(id, rider), code }, deps())));
    expect(res.every((r) => r.result === "verified")).toBe(true);
    await dc.verifyDeliveryCodeCore({ ...req(id, rider), code }, deps());
    expect((await db.doc(`riderWallets/${rider}`).get()).get("availableNgwee")).toBe(before + q.riderEarningsNgwee);
    expect((await db.collection("escrow_ledger").where("orderId", "==", id).where("leg", "==", "rider_earnings").get()).size).toBe(1);
  });
});

describe("QA 6 · channels", () => {
  it("receiver without the app gets SMS; with the app and an ack, push only; no ack → push + SMS", async () => {
    const a = await seedParcel();
    expect((await dc.requestDeliveryCodeCore(req(a.id), deps())).channel).toBe("sms");
    const b = await seedParcel();
    expect((await dc.requestDeliveryCodeCore(req(b.id), deps({ pushCode: async () => true, waitForAck: async () => true }))).channel).toBe("push");
    sink.length = 0;
    const c = await seedParcel();
    expect((await dc.requestDeliveryCodeCore(req(c.id), deps({ pushCode: async () => true, waitForAck: async () => false }))).channel).toBe("push+sms");
    expect(sink.length).toBe(1);
  });
});

describe("QA 7 · receiver unavailable → DELIVERY_FAILED, no payment", () => {
  it("needs a 10-minute wait, then fails without releasing escrow", async () => {
    const rider = `rider-ru-${n}`;
    const { id, q } = await seedParcel("IN_TRANSIT", rider);
    await dc.requestDeliveryCodeCore(req(id, rider), deps());
    await expect(dc.receiverUnavailableCore("parcel", id, rider, AT_DOOR, { ...domain.DEFAULT_CONFIG })).rejects.toThrow(/wait at the drop-off/);
    await db.doc(`parcels/${id}`).update({ arrivedAt: Timestamp.fromMillis(Date.now() - 11 * 60_000) });
    const r = await dc.receiverUnavailableCore("parcel", id, rider, AT_DOOR, { ...domain.DEFAULT_CONFIG });
    expect(r.deadline).toBeGreaterThan(Date.now());
    await dc.failDelivery("parcel", id, "receiver_unavailable_no_response");
    const p = (await db.doc(`parcels/${id}`).get()).data()!;
    expect(p.status).toBe("DELIVERY_FAILED"); expect(p.escrowStatus).toBe("HELD_PENDING_ADMIN");
    const w = (await db.doc(`riderWallets/${rider}`).get()).data()!;
    expect(w.availableNgwee).toBe(10_000); expect(w.pendingNgwee).toBe(q.riderEarningsNgwee - q.riderEarningsNgwee);
  });
});

describe("QA 8 · batch independence", () => {
  it("each stop has its own code and release", async () => {
    const rider = `rider-batch-${n}`;
    const a = await seedParcel("IN_TRANSIT", rider), b = await seedParcel("IN_TRANSIT", rider);
    await db.doc(`riderWallets/${rider}`).set({ pendingNgwee: a.q.riderEarningsNgwee * 2, activeCount: 2 }, { merge: true });
    await dc.requestDeliveryCodeCore(req(a.id, rider), deps()); const codeA = lastCode();
    await dc.requestDeliveryCodeCore(req(b.id, rider), deps()); const codeB = lastCode();
    if (codeA !== codeB) expect((await dc.verifyDeliveryCodeCore({ ...req(b.id, rider), code: codeA }, deps())).result).toBe("wrong");
    expect((await dc.verifyDeliveryCodeCore({ ...req(a.id, rider), code: codeA }, deps())).result).toBe("verified");
    expect((await db.doc(`parcels/${b.id}`).get()).get("status")).toBe("AWAITING_DELIVERY_CODE");
    expect((await db.collection("escrow_ledger").where("orderId", "==", b.id).where("type", "==", "RELEASED").get()).size).toBe(0);
  });
});

describe("QA 9 · marketplace order uses the same flow (buyer is the receiver)", () => {
  it("releases the rider leg and the seller proceeds on code verification", async () => {
    const id = `SNC-T${++n}`;
    const rider = `rider-mkt-${n}`;
    await db.doc(`orders/${id}`).set({ buyerUid: "buyer-9", buyerMsisdn: "+260971234512", buyerName: "Mwila", sellerUid: "seller-9", riderUid: rider, state: "IN_TRANSIT",
      goodsNgwee: 20_000, deliveryNgwee: 4_000, totalNgwee: 24_000, dropoffGeo: DROP, sellerPayoutStatus: "HELD", isParcel: false, items: [{ title: "Tomatoes", qty: 2 }],
      feeSnapshot: { ...domain.DEFAULT_CONFIG } });
    await db.doc(`riderWallets/${rider}`).set({ availableNgwee: 0, pendingNgwee: 3_480, activeCount: 1 });
    await db.doc(`riderLocations/${rider}`).set({ ...AT_DOOR, at: Timestamp.now() });
    await dc.requestDeliveryCodeCore({ kind: "order", id, riderUid: rider }, deps());
    expect(sink.at(-1)!.to).toBe("+260971234512");
    const v = await dc.verifyDeliveryCodeCore({ kind: "order", id, riderUid: rider, code: lastCode() }, deps());
    expect(v).toEqual({ result: "verified", riderEarningsNgwee: 3_480 });
    const legs = (await db.collection("escrow_ledger").where("orderId", "==", id).get()).docs.map((d) => d.get("leg"));
    expect(legs).toEqual(expect.arrayContaining(["rider_earnings", "seller_proceeds", "social_fund", "platform_fee"]));
    expect((await db.doc(`orders/${id}`).get()).get("state")).toBe("DELIVERED");
  });
});

describe("QA 12 · admin override", () => {
  it("needs a reason and evidence, releases once and is flagged override in ledger and audit log", async () => {
    const { id } = await seedParcel("IN_TRANSIT", `rider-ovr-${n}`);
    const admin = { uid: "admin-1", email: "ops@selonachipa.zm", role: "operations" };
    await expect(dc.overrideDeliveryCore("parcel", id, admin, "short", ["call"])).rejects.toThrow(/reason/);
    await expect(dc.overrideDeliveryCore("parcel", id, admin, "Receiver confirmed by phone call", [])).rejects.toThrow(/evidence/);
    await dc.overrideDeliveryCore("parcel", id, admin, "Receiver confirmed by phone call", ["call-log:2026-11-25 14:02"]);
    await expect(dc.overrideDeliveryCore("parcel", id, admin, "Receiver confirmed by phone call", ["x"])).rejects.toThrow(/already released/);
    const led = await db.collection("escrow_ledger").where("orderId", "==", id).where("leg", "==", "rider_earnings").get();
    expect(led.size).toBe(1); expect(led.docs[0].get("override")).toBe(true);
    expect((await db.collection("admin_audit_log").where("orderId", "==", id).get()).docs[0].get("action")).toBe("delivery.override");
  });
});
