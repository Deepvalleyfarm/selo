/**
 * Lipila full-balance withdrawals against the Firestore emulator, with Lipila's HTTP API mocked at the transport.
 * Covers: correct PIN → PENDING → Successful callback; wrong PIN → no Lipila call, no lock; Lipila 4xx → no lock left;
 * Failed callback → balance restored; duplicate callback → processed once; timeout → reconciliation, not auto-fail;
 * one in-flight withdrawal per wallet; minimum balance.
 */
process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "demo-selo";
process.env.SELO_SMS_SINK = "1";
process.env.LIPILA_BASE_URL = "https://lipila.test";
process.env.LIPILA_API_KEY = "test-key";
process.env.LIPILA_CALLBACK_URL = "https://example.test/lipila_webhook";
process.env.LIPILA_WEBHOOK_SECRET = "whsec-test";

import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import bcrypt from "bcryptjs";

let W: typeof import("../src/payments/withdrawals");
let L: typeof import("../src/lipila");
let E: typeof import("../src/escrow");
let db: FirebaseFirestore.Firestore;
let calls: { url: string; body: any; headers: Record<string, string> }[] = [];
let respond: (url: string, body: any) => { status: number; json: unknown } | "network" = () => ({ status: 200, json: { status: "Pending", identifier: "LIP-1" } });
let n = 0;

beforeAll(async () => {
  W = await import("../src/payments/withdrawals");
  L = await import("../src/lipila");
  E = await import("../src/escrow");
  db = (await import("../src/config")).db;
  L._setLipilaTransport((async (url: any, init: any) => {
    const body = init?.body ? JSON.parse(init.body) : undefined;
    calls.push({ url: String(url), body, headers: init.headers });
    const r = respond(String(url), body);
    if (r === "network") throw new TypeError("fetch failed");
    return new Response(JSON.stringify(r.json), { status: r.status });
  }) as typeof fetch, async () => undefined);
});
beforeEach(() => { calls = []; respond = () => ({ status: 200, json: { status: "Pending", identifier: "LIP-1" } }); delete process.env.LIPILA_DISBURSEMENT_STATUS_PATH; });

async function rider(balance = 50_000) {
  const uid = `wd-rider-${++n}-${Date.now() % 100000}`;
  await db.doc(`users/${uid}`).set({ role: "RIDER", fullName: "Chanda Mulenga", payoutWallet: "+260971234514" });
  await db.doc(`users/${uid}/private/security`).set({ pinHash: await bcrypt.hash("24680", 4) });
  await db.doc(`riderWallets/${uid}`).set({ availableNgwee: balance, socialFundNgwee: 0 });
  return uid;
}
const wallet = async (uid: string) => (await db.doc(`riderWallets/${uid}`).get()).data()!;
const reqDoc = async (id: string) => (await db.doc(`withdrawal_requests/${id}`).get()).data()!;
const disburseCalls = () => calls.filter((c) => c.url.includes("/disbursements/"));

describe("correct PIN → lock → Pending → Successful callback", () => {
  it("withdraws the full balance and debits it exactly once", async () => {
    const uid = await rider(50_000);
    const r = await W.requestWithdrawal(uid, "RIDER", "24680", "rider");
    expect(r.status).toBe("PENDING"); expect(r.amountNgwee).toBe(50_000);
    expect(disburseCalls()).toHaveLength(1);
    const c = disburseCalls()[0];
    expect(c.url).toBe("https://lipila.test/api/v1/disbursements/mobile-money");
    expect(c.body).toMatchObject({ amount: 500, accountNumber: "260971234514", currency: "ZMW" });
    expect(c.body.referenceId).toMatch(/^SELO-WD-[0-9a-f-]{36}$/);
    expect(c.headers["x-api-key"]).toBe("test-key"); expect(c.headers.callbackUrl).toBe("https://example.test/lipila_webhook");
    expect(JSON.stringify(c.body)).not.toContain("24680"); // PIN never goes to Lipila
    let w = await wallet(uid); expect(w.availableNgwee).toBe(0); expect(w.lockedNgwee).toBe(50_000); expect(w.activeWithdrawalId).toBe(r.withdrawalId);

    const ref = (await reqDoc(r.withdrawalId)).reference_id;
    await E.processLipilaEvent(ref, "Successful", "Transaction successful", { referenceId: ref, status: "Successful" });
    await E.processLipilaEvent(ref, "Successful", "Transaction successful", { referenceId: ref, status: "Successful" }); // duplicate
    w = await wallet(uid); expect(w.availableNgwee).toBe(0); expect(w.lockedNgwee).toBe(0); expect(w.activeWithdrawalId).toBeNull();
    expect((await reqDoc(r.withdrawalId)).status).toBe("COMPLETED");
    expect((await db.collection(`ledgers/${uid}/entries`).get()).size).toBe(1);
    const trail = (await db.collection("withdrawal_audit_log").where("withdrawal_request_id", "==", r.withdrawalId).get()).docs.map((d) => d.get("to_status"));
    expect(trail).toEqual(expect.arrayContaining(["REQUESTED", "PIN_VERIFIED", "LOCKED", "SUBMITTED", "PENDING", "COMPLETED"]));
  });
});

describe("wrong PIN", () => {
  it("rejects before any lock or Lipila call", async () => {
    const uid = await rider();
    await expect(W.requestWithdrawal(uid, "RIDER", "11111", "rider")).rejects.toThrow("Incorrect PIN");
    expect(calls).toHaveLength(0);
    const w = await wallet(uid); expect(w.availableNgwee).toBe(50_000); expect(w.lockedNgwee ?? 0).toBe(0); expect(w.activeWithdrawalId ?? null).toBeNull();
  });
  it("locks withdrawals for 15 minutes after 5 wrong PINs", async () => {
    const uid = await rider();
    for (let i = 0; i < 5; i++) await expect(W.requestWithdrawal(uid, "RIDER", "11111", "rider")).rejects.toThrow();
    await expect(W.requestWithdrawal(uid, "RIDER", "24680", "rider")).rejects.toThrow(/Too many incorrect PINs/);
    expect(calls).toHaveLength(0);
  });
});

describe("Lipila rejects the request (4xx)", () => {
  it.each([400, 401, 403])("HTTP %i → lock released, balance restored, error surfaced", async (status) => {
    const uid = await rider(30_000);
    respond = () => ({ status, json: { message: `Lipila says no (${status})` } });
    await expect(W.requestWithdrawal(uid, "RIDER", "24680", "rider")).rejects.toThrow(`Lipila says no (${status})`);
    const w = await wallet(uid); expect(w.availableNgwee).toBe(30_000); expect(w.lockedNgwee).toBe(0); expect(w.activeWithdrawalId).toBeNull();
  });
  it("429 is retried with backoff before giving up", async () => {
    const uid = await rider(30_000);
    respond = () => ({ status: 429, json: { message: "Rate limit exceeded" } });
    await expect(W.requestWithdrawal(uid, "RIDER", "24680", "rider")).rejects.toThrow(/busy/);
    expect(disburseCalls()).toHaveLength(3);
    expect((await wallet(uid)).availableNgwee).toBe(30_000);
  });
});

describe("Failed callback", () => {
  it("restores the balance and keeps Lipila's message verbatim", async () => {
    const uid = await rider(40_000);
    const r = await W.requestWithdrawal(uid, "RIDER", "24680", "rider");
    const ref = (await reqDoc(r.withdrawalId)).reference_id;
    await E.processLipilaEvent(ref, "Failed", "Recipient account is not registered for mobile money", {});
    await E.processLipilaEvent(ref, "Failed", "Recipient account is not registered for mobile money", {});
    const w = await wallet(uid); expect(w.availableNgwee).toBe(40_000); expect(w.lockedNgwee).toBe(0);
    const d = await reqDoc(r.withdrawalId); expect(d.status).toBe("FAILED"); expect(d.error_message).toBe("Recipient account is not registered for mobile money");
  });
});

describe("one in-flight withdrawal and minimum", () => {
  it("blocks a second withdrawal while one is pending (API layer)", async () => {
    const uid = await rider(40_000);
    await W.requestWithdrawal(uid, "RIDER", "24680", "rider");
    await db.doc(`riderWallets/${uid}`).set({ availableNgwee: 9_000 }, { merge: true }); // new earnings arrive
    await expect(W.requestWithdrawal(uid, "RIDER", "24680", "rider")).rejects.toThrow("Withdrawal in progress");
    expect(disburseCalls()).toHaveLength(1);
  });
  it("refuses below the minimum without calling Lipila", async () => {
    const uid = await rider(1_500);
    await expect(W.requestWithdrawal(uid, "RIDER", "24680", "rider")).rejects.toThrow(/Minimum withdrawal is ZMW 20.00/);
    expect(calls).toHaveLength(0);
  });
});

describe("no callback → reconciliation (never auto-failed)", () => {
  it("status lookup Successful completes it", async () => {
    const uid = await rider(25_000);
    const r = await W.requestWithdrawal(uid, "RIDER", "24680", "rider");
    process.env.LIPILA_DISBURSEMENT_STATUS_PATH = "/api/v1/disbursements/status?referenceId={referenceId}";
    respond = (url) => url.includes("/status") ? { status: 200, json: { status: "Successful" } } : { status: 200, json: { status: "Pending" } };
    await W.reconcileWithdrawals(Date.now() + 6 * 60_000);
    expect((await reqDoc(r.withdrawalId)).status).toBe("COMPLETED");
    expect((await wallet(uid)).lockedNgwee).toBe(0);
  });
  it("inconclusive → MANUAL_REVIEW with funds still locked", async () => {
    const uid = await rider(25_000);
    const r = await W.requestWithdrawal(uid, "RIDER", "24680", "rider");
    await W.reconcileWithdrawals(Date.now() + 6 * 60_000); // no status endpoint configured
    const d = await reqDoc(r.withdrawalId); expect(d.status).toBe("MANUAL_REVIEW");
    const w = await wallet(uid); expect(w.lockedNgwee).toBe(25_000); expect(w.availableNgwee).toBe(0);
    // a late Successful callback still resolves it, exactly once
    await E.processLipilaEvent(d.reference_id, "Successful", undefined, {});
    expect((await reqDoc(r.withdrawalId)).status).toBe("COMPLETED");
  });
  it("network error on submit keeps the lock and waits for reconciliation", async () => {
    const uid = await rider(25_000);
    respond = () => "network";
    const r = await W.requestWithdrawal(uid, "RIDER", "24680", "rider");
    expect(r.status).toBe("SUBMITTED");
    expect((await wallet(uid)).lockedNgwee).toBe(25_000);
  });
});

describe("Lipila status semantics", () => {
  it("Timeout / Network Error are not final: the lock stays until reconciliation", async () => {
    const uid = await rider(20_000);
    const r = await W.requestWithdrawal(uid, "RIDER", "24680", "rider");
    const ref = (await reqDoc(r.withdrawalId)).reference_id;
    await E.processLipilaEvent(ref, "Timeout", "MNO did not respond", {});
    await E.processLipilaEvent(ref, "Network Error", "MNO connectivity issue", {});
    expect((await reqDoc(r.withdrawalId)).status).toBe("PENDING");
    expect((await wallet(uid)).lockedNgwee).toBe(20_000);
    await E.processLipilaEvent(ref, "Wrong Number", "Mobile number not registered", {});
    expect((await reqDoc(r.withdrawalId)).status).toBe("FAILED");
    expect((await wallet(uid)).availableNgwee).toBe(20_000);
  });
  it("matches a callback that only carries Lipila's own reference via identifier", async () => {
    const uid = await rider(20_000);
    const ident = `LIP-${Date.now()}`;
    respond = () => ({ status: 200, json: { status: "Pending", identifier: ident } });
    const r = await W.requestWithdrawal(uid, "RIDER", "24680", "rider");
    await E.processLipilaEvent("some-lipila-guid", "Successful", undefined, {}, ident);
    expect((await reqDoc(r.withdrawalId)).status).toBe("COMPLETED");
  });
});
