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
