import { describe, expect, it } from "vitest";
import { decrypt, encrypt, hashSecret, signToken, signWebhook, verifySecret, verifyToken, verifyWebhook } from "../src/open/crypto";
import { accessProblem, commissionOn, deliveryEventsFor, effectiveScopes, partnerDeliveryPrice, partnerSellerUid, riderForPartner } from "../src/open/model";
import { entryId, signed, summarise } from "../src/open/settlement";
import { eventId, nextDelayMin } from "../src/open/webhooks";
import { listingIdFor, productProblems } from "../src/open/commerce";
import { openApiSpec } from "../src/open/openapi";

const KEY = "test-jwt-key-0123456789";

describe("credentials", () => {
  it("client secrets are hashed and verified in constant time", () => {
    const { salt, hash } = hashSecret("sns_abc");
    expect(verifySecret("sns_abc", salt, hash)).toBe(true);
    expect(verifySecret("sns_abd", salt, hash)).toBe(false);
    expect(hash).not.toContain("sns_abc");
  });
  it("access tokens: signed, scoped, expiring, tamper-proof", () => {
    const t = signToken({ pid: "p1", cid: "c1", env: "sandbox", scope: ["products:write"] }, KEY, 60, 1_000);
    expect(verifyToken(t, KEY, 1_030)).toMatchObject({ pid: "p1", env: "sandbox", scope: ["products:write"] });
    expect(verifyToken(t, KEY, 1_061)).toBeNull();              // expired
    expect(verifyToken(t, "other-key", 1_030)).toBeNull();      // wrong key
    const [h, b, s] = t.split(".");
    const forged = Buffer.from(JSON.stringify({ ...JSON.parse(Buffer.from(b, "base64url").toString()), pid: "p2" })).toString("base64url");
    expect(verifyToken(`${h}.${forged}.${s}`, KEY, 1_030)).toBeNull(); // can't switch to another partner
  });
  it("webhook secrets are encrypted at rest", () => {
    const blob = encrypt("whsec_secret", "enc-key");
    expect(blob).not.toContain("whsec_secret");
    expect(decrypt(blob, "enc-key")).toBe("whsec_secret");
    expect(() => decrypt(blob, "wrong")).toThrow();
  });
  it("webhook signatures verify, and stale or altered ones fail", () => {
    const secret = "whsec_" + Buffer.from("s3cret").toString("base64"), body = '{"a":1}';
    const sig = signWebhook(secret, "evt_1", 1000, body);
    expect(verifyWebhook(secret, "evt_1", 1000, body, sig, 1100)).toBe(true);
    expect(verifyWebhook(secret, "evt_1", 1000, '{"a":2}', sig, 1100)).toBe(false);
    expect(verifyWebhook(secret, "evt_1", 1000, body, sig, 2000)).toBe(false); // replay after 5 min
  });
});

describe("partner access and scopes", () => {
  const p = { scopes: ["products:write", "delivery:write", "webhooks:manage", "bogus"], services: { commerce: true, logistics: false } };
  it("scopes need the owning service enabled", () => {
    expect(effectiveScopes(p)).toEqual(["products:write", "webhooks:manage"]);
    expect(effectiveScopes(p, ["delivery:write"])).toEqual([]);
  });
  it("suspension, revocation and disabled environments block access", () => {
    const ok = { status: "ACTIVE", environments: { sandbox: true, production: false } } as const;
    expect(accessProblem(ok, { status: "ACTIVE", env: "sandbox" })).toBeNull();
    expect(accessProblem(ok, { status: "REVOKED", env: "sandbox" })).toBe("client_revoked");
    expect(accessProblem({ ...ok, status: "SUSPENDED" }, { status: "ACTIVE", env: "sandbox" })).toBe("partner_suspended");
    expect(accessProblem(ok, { status: "ACTIVE", env: "production" })).toBe("environment_disabled");
  });
});

describe("pricing, commission and ledger", () => {
  it("partner tariffs, wholesale discount and express surcharge", () => {
    expect(partnerDeliveryPrice(5_000, 6, {})).toBe(5_000);
    expect(partnerDeliveryPrice(5_000, 6, { deliveryBaseNgwee: 1_000, deliveryPerKmNgwee: 300 })).toBe(2_800);
    expect(partnerDeliveryPrice(5_000, 6, { wholesaleDiscountPct: 20 })).toBe(4_000);
    expect(partnerDeliveryPrice(5_000, 6, { expressSurchargePct: 50 }, true)).toBe(7_500);
    expect(commissionOn(10_000, { marketplaceCommissionPct: 5 })).toBe(500);
    expect(commissionOn(10_000)).toBe(800);
  });
  it("ledger entries are signed from the partner's side, deterministic and reconcile", () => {
    const es = [{ type: "SALE", amountNgwee: 10_000 }, { type: "PLATFORM_FEE", amountNgwee: 500 }, { type: "COMMISSION", amountNgwee: 800 },
      { type: "DELIVERY_CHARGE", amountNgwee: 3_000 }, { type: "SETTLEMENT", amountNgwee: -5_700 }] as const;
    expect(es.map((e) => signed(e as any))).toEqual([10_000, -500, -800, -3_000, -5_700]);
    expect(summarise(es as any).balanceNgwee).toBe(0);
    expect(entryId({ type: "SALE", refType: "order", refId: "SNC-1" })).toBe(entryId({ type: "SALE", refType: "order", refId: "SNC-1" }));
  });
});

describe("webhooks and deliveries", () => {
  it("event ids are deterministic per subject/version (no duplicates)", () => {
    expect(eventId("p", "delivery.assigned", "PCL-1", "RIDER_ASSIGNED")).toBe(eventId("p", "delivery.assigned", "PCL-1", "RIDER_ASSIGNED"));
    expect(eventId("p", "delivery.assigned", "PCL-1", "A")).not.toBe(eventId("q", "delivery.assigned", "PCL-1", "A"));
    expect([0, 1, 2, 7, 20].map(nextDelayMin)).toEqual([1, 5, 15, 1440, 1440]);
  });
  it("parcel status changes map to delivery events", () => {
    expect(deliveryEventsFor("AWAITING_RIDER", "RIDER_ASSIGNED")).toEqual(["delivery.assigned"]);
    expect(deliveryEventsFor("RIDER_ASSIGNED", "IN_TRANSIT")).toEqual(["delivery.picked_up", "delivery.in_transit"]);
    expect(deliveryEventsFor("AWAITING_DELIVERY_CODE", "COMPLETED")).toEqual(["delivery.completed"]);
    expect(deliveryEventsFor("IN_TRANSIT", "DELIVERY_FAILED")).toEqual(["delivery.failed"]);
    expect(deliveryEventsFor("AWAITING_RIDER", "CANCELLED")).toEqual(["delivery.cancelled"]);
  });
  it("partners only get a rider's first name and plate, and only while the job is active", () => {
    expect(riderForPartner({ riderName: "Chanda Mulenga", riderPlate: "ALB 1", riderPhotoPath: "x" }, true)).toEqual({ firstName: "Chanda", plate: "ALB 1" });
    expect(riderForPartner({ riderName: "Chanda Mulenga" }, false)).toBeNull();
  });
});

describe("product syndication", () => {
  it("listing ids are stable per partner + product and separate sandbox from production", () => {
    expect(listingIdFor("p1", "production", "SKU-1")).toBe(listingIdFor("p1", "production", "SKU-1"));
    expect(listingIdFor("p1", "production", "SKU-1")).not.toBe(listingIdFor("p2", "production", "SKU-1"));
    expect(listingIdFor("p1", "sandbox", "SKU-1")).toMatch(/^ext_sbx_/);
    expect(partnerSellerUid("p1", "farm node/117")).toBe("partner:p1:farmnode117");
  });
  it("validates product input", () => {
    const good = { externalProductId: "SKU-1", externalSellerId: "farm-1", title: "Tomatoes", priceNgwee: 18_000, stock: 5 };
    expect(productProblems(good)).toEqual([]);
    expect(productProblems({ ...good, priceNgwee: 1.5 }).join()).toMatch(/priceNgwee/);
    expect(productProblems({ ...good, images: ["http://x"] }).join()).toMatch(/https/);
    expect(productProblems({ ...good, currency: "USD" }).join()).toMatch(/ZMW/);
    expect(productProblems({ stock: 3 }, true)).toEqual([]);
  });
});

describe("OpenAPI document", () => {
  it("documents every route group, the OAuth flow and all webhook events", () => {
    const s = openApiSpec("https://x/api/v1") as any;
    for (const p of ["/oauth/token", "/products", "/catalog", "/orders", "/delivery/quotes", "/deliveries", "/coverage", "/webhooks", "/settlements"]) expect(s.paths[p]).toBeTruthy();
    expect(s.components.securitySchemes.oauth2.flows.clientCredentials.tokenUrl).toBe("https://x/api/v1/oauth/token");
    expect(Object.keys(s.webhooks)).toHaveLength(15);
  });
});
