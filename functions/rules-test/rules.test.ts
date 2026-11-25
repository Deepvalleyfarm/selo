// Firestore security-rules tests for the v6.1 Infinity Memory collections. Run: npm run test:rules
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { readFileSync } from "node:fs";
import { afterAll, beforeAll, describe, it } from "vitest";
import { doc, setDoc, updateDoc, deleteDoc, getDoc } from "firebase/firestore";

let env: RulesTestEnvironment;
const evtId = "evt_0193f0a2-7b1c-7def-8a12-3456789abcde_s61";
beforeAll(async () => {
  env = await initializeTestEnvironment({ projectId: "demo-selo", firestore: { rules: readFileSync("../firebase/firestore.rules", "utf8"), host: "127.0.0.1", port: 8080 } });
});
afterAll(async () => { await env?.cleanup(); });
const as = (uid: string, claims: Record<string, unknown> = { role: "BUYER" }) => env.authenticatedContext(uid, claims).firestore();

describe("behaviourEvents (append-only)", () => {
  it("a user can append their own event", () => assertSucceeds(setDoc(doc(as("u1"), "behaviourEvents", evtId), { type: "like", userId: "u1", ts: 1, listingId: "l1" })));
  it("but not someone else's", () => assertFails(setDoc(doc(as("u1"), "behaviourEvents", evtId.replace("abcde", "abcdf")), { type: "like", userId: "u2", ts: 1 })));
  it("unknown event types are rejected", () => assertFails(setDoc(doc(as("u1"), "behaviourEvents", evtId.replace("abcde", "abcd0")), { type: "hack", userId: "u1", ts: 1 })));
  it("keys must be entity + UUIDv7 + shard", () => assertFails(setDoc(doc(as("u1"), "behaviourEvents", "anything"), { type: "like", userId: "u1", ts: 1 })));
  it("events can never be edited or deleted", async () => {
    await assertFails(updateDoc(doc(as("u1"), "behaviourEvents", evtId), { type: "share" }));
    await assertFails(deleteDoc(doc(as("u1"), "behaviourEvents", evtId)));
  });
  it("users can't read the log; analysts can", async () => {
    await assertFails(getDoc(doc(as("u1"), "behaviourEvents", evtId)));
    await assertSucceeds(getDoc(doc(as("a1", { admin: "analyst" }), "behaviourEvents", evtId)));
  });
});

describe("personal collections", () => {
  it("wishlists are private to their owner", async () => {
    await assertSucceeds(setDoc(doc(as("u1"), "wishlists/u1/lists/christmas"), { name: "Christmas gifts", itemIds: ["l1"] }));
    await assertFails(getDoc(doc(as("u2"), "wishlists/u1/lists/christmas")));
    await assertFails(setDoc(doc(as("u2"), "wishlists/u1/lists/x"), { name: "x", itemIds: [] }));
  });
  it("follows are created only for yourself with the right id", async () => {
    await assertSucceeds(setDoc(doc(as("u1"), "follows/u1_s9"), { followerUid: "u1", sellerUid: "s9" }));
    await assertFails(setDoc(doc(as("u1"), "follows/u2_s9"), { followerUid: "u2", sellerUid: "s9" }));
  });
  it("buyer profiles and likes are written only by the server", async () => {
    await assertFails(setDoc(doc(as("u1"), "buyerProfiles/u1"), { cohort: 3 }));
    await assertFails(setDoc(doc(as("u1"), "likes/u1/items/l1"), { ts: 1 }));
  });
});

describe("algorithm and metrics", () => {
  it("only admins read algorithm state and KPIs", async () => {
    await assertFails(getDoc(doc(as("u1"), "algorithmState/current")));
    await assertSucceeds(getDoc(doc(as("a1", { admin: "operations" }), "algorithmState/current")));
    await assertFails(setDoc(doc(as("a1", { admin: "super_admin" }), "algorithmState/current"), { version: 99 }));
    await assertFails(getDoc(doc(as("u1"), "adminMetrics/met_2026-11-25_daily")));
  });
  it("guardrails can be read by the app but not written", async () => {
    await assertSucceeds(getDoc(doc(as("u1"), "platformConfig/guardrails")));
    await assertFails(setDoc(doc(as("a1", { admin: "super_admin" }), "platformConfig/guardrails"), { enabled: false }));
  });
  it("users may set lead consent on their own profile", async () => {
    await env.withSecurityRulesDisabled(async (c) => { await setDoc(doc(c.firestore(), "users/u1"), { role: "BUYER", fullName: "M" }); });
    await assertSucceeds(updateDoc(doc(as("u1"), "users/u1"), { leadConsent: true, ageBand: "25–34" }));
    await assertFails(updateDoc(doc(as("u1"), "users/u1"), { role: "SELLER" }));
  });
});

describe("paid promotions (v6.2)", () => {
  it("only the server writes promotions; a seller reads their own, finance reads all", async () => {
    await env.withSecurityRulesDisabled(async (c) => { await setDoc(doc(c.firestore(), "promotions/p1"), { sellerUid: "s1", status: "ACTIVE", priceNgwee: 6000 }); });
    await assertFails(setDoc(doc(as("s1", { role: "SELLER" }), "promotions/p2"), { sellerUid: "s1", status: "ACTIVE" }));
    await assertFails(updateDoc(doc(as("s1", { role: "SELLER" }), "promotions/p1"), { status: "ACTIVE", priceNgwee: 0 }));
    await assertSucceeds(getDoc(doc(as("s1", { role: "SELLER" }), "promotions/p1")));
    await assertFails(getDoc(doc(as("s2", { role: "SELLER" }), "promotions/p1")));
    await assertSucceeds(getDoc(doc(as("f1", { admin: "finance" }), "promotions/p1")));
  });
  it("platform revenue is visible to finance only", async () => {
    await assertFails(getDoc(doc(as("s1", { role: "SELLER" }), "platformRevenue/r1")));
    await assertSucceeds(getDoc(doc(as("f1", { admin: "finance" }), "platformRevenue/r1")));
  });
  it("add_to_cart events are accepted", () =>
    assertSucceeds(setDoc(doc(as("u1"), "behaviourEvents", evtId.replace("abcde", "abc11")), { type: "add_to_cart", userId: "u1", ts: 1, listingId: "l1", promoId: "p1" })));
});

describe("Parcels v2 security (QA 10 · QA 11)", () => {
  const seed = async () => env.withSecurityRulesDisabled(async (c) => {
    const f = c.firestore();
    await setDoc(doc(f, "parcels/PCL-R1"), { senderUid: "s1", riderUid: "r1", receiver: { uid: "rc1", msisdn: "+260955000000" }, status: "IN_TRANSIT", notifiedRiders: [] });
    await setDoc(doc(f, "parcels/PCL-R1/deliveryCode/current"), { hash: "abc", state: "sent" });
    await setDoc(doc(f, "orders/SNC-R1/deliveryCode/current"), { hash: "abc", state: "sent" });
    await setDoc(doc(f, "parcels/PCL-R1/private/pickup"), { senderUid: "s1", pin: "4821" });
    await setDoc(doc(f, "riderWallets/r1"), { availableNgwee: 100 });
    await setDoc(doc(f, "escrow_ledger/e1"), { orderId: "PCL-R1", type: "HELD" });
    await setDoc(doc(f, "chats/PCL-R1_rs"), { participants: ["r1", "s1"], riderUid: "r1", closesAt: null });
    await setDoc(doc(f, "chats/PCL-R2_rs"), { participants: ["r1", "s1"], riderUid: "r1", closesAt: new Date(Date.now() - 60_000) });
  });
  it("no client can read or write a delivery code — rider, receiver, sender or admin", async () => {
    await seed();
    for (const [uid, claims] of [["r1", { role: "RIDER" }], ["rc1", { role: "BUYER" }], ["s1", { role: "SELLER" }], ["a1", { admin: "super_admin" }]] as const) {
      await assertFails(getDoc(doc(as(uid, claims), "parcels/PCL-R1/deliveryCode/current")));
      await assertFails(setDoc(doc(as(uid, claims), "parcels/PCL-R1/deliveryCode/current"), { hash: "x", state: "verified" }));
      await assertFails(getDoc(doc(as(uid, claims), "orders/SNC-R1/deliveryCode/current")));
    }
  });
  it("clients can't change status, wallets or the escrow ledger", async () => {
    await assertFails(updateDoc(doc(as("s1", { role: "SELLER" }), "parcels/PCL-R1"), { status: "DELIVERED" }));
    await assertFails(updateDoc(doc(as("r1", { role: "RIDER" }), "parcels/PCL-R1"), { status: "DELIVERED" }));
    await assertFails(updateDoc(doc(as("r1", { role: "RIDER" }), "riderWallets/r1"), { availableNgwee: 999999 }));
    await assertFails(setDoc(doc(as("a1", { admin: "finance" }), "escrow_ledger/e2"), { type: "RELEASED" }));
    await assertSucceeds(getDoc(doc(as("a1", { admin: "finance" }), "escrow_ledger/e1")));
    await assertFails(getDoc(doc(as("s1", { role: "SELLER" }), "escrow_ledger/e1")));
  });
  it("the pickup PIN is readable by the sender only", async () => {
    await assertSucceeds(getDoc(doc(as("s1", { role: "SELLER" }), "parcels/PCL-R1/private/pickup")));
    await assertFails(getDoc(doc(as("r1", { role: "RIDER" }), "parcels/PCL-R1/private/pickup")));
    await assertSucceeds(getDoc(doc(as("rc1", { role: "BUYER" }), "parcels/PCL-R1")));
    await assertFails(getDoc(doc(as("x9", { role: "RIDER" }), "parcels/PCL-R1")));
  });
  it("riders can't send codes or the words code/PIN in chat; chats close after delivery", async () => {
    const r = as("r1", { role: "RIDER" }), s = as("s1", { role: "SELLER" });
    await assertSucceeds(setDoc(doc(r, "chats/PCL-R1_rs/messages/m1"), { senderUid: "r1", text: "I'm at the blue gate" }));
    await assertFails(setDoc(doc(r, "chats/PCL-R1_rs/messages/m2"), { senderUid: "r1", text: "tell me 4 8 2 1 3" }));
    await assertFails(setDoc(doc(r, "chats/PCL-R1_rs/messages/m3"), { senderUid: "r1", text: "What's the Code?" }));
    await assertFails(setDoc(doc(r, "chats/PCL-R1_rs/messages/m4"), { senderUid: "r1", text: "send your pin" }));
    await assertSucceeds(setDoc(doc(s, "chats/PCL-R1_rs/messages/m5"), { senderUid: "s1", text: "Thanks, see you at 14:30" }));
    await assertFails(setDoc(doc(s, "chats/PCL-R2_rs/messages/m6"), { senderUid: "s1", text: "Hello?" }));
  });
});
