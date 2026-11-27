import { describe, expect, it } from "vitest";
import { decrypt, encrypt, hashSecret, signToken, signWebhook, verifySecret, verifyToken, verifyWebhook } from "../src/open/crypto";
import { accessProblem, deliveryEventsFor, effectiveScopes, partnerSellerUid, riderForPartner, seloDeliveryFee } from "../src/open/model";
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

describe("SeloNachipa delivery fee and ledger", () => {
  const q = { deliveryNgwee: 5_000, sendingNgwee: 750, riderEarningsNgwee: 4_350, platformFeeNgwee: 400, socialFundNgwee: 250 };
  it("platforms are charged SeloNachipa's configured fee, split exactly as configured", () => {
    const f = seloDeliveryFee(q, false);
    expect(f).toMatchObject({ deliveryNgwee: 5_000, serviceFeeNgwee: 750, totalNgwee: 5_750 });
    expect(f.split.riderEarningsNgwee + f.split.platformFeeNgwee + f.split.socialFundNgwee).toBe(5_000);
  });
  it("express premium is set on SeloNachipa and flows through the same split", () => {
    const f = seloDeliveryFee(q, true, 40);
    expect(f.deliveryNgwee).toBe(7_000); expect(f.totalNgwee).toBe(7_750); expect(f.split.riderEarningsNgwee).toBe(6_090);
  });
  it("ledger: delivery charges are owed by the platform; settlements and reversals clear them", () => {
    const es = [{ type: "DELIVERY_CHARGE", amountNgwee: 5_750 }, { type: "DELIVERY_CHARGE", amountNgwee: 3_000 }, { type: "DELIVERY_REVERSAL", amountNgwee: 3_000 },
      { type: "API_USAGE", amountNgwee: 250 }, { type: "SETTLEMENT", amountNgwee: 6_000 }] as const;
    expect(es.map((e) => signed(e as any))).toEqual([-5_750, -3_000, 3_000, -250, 6_000]);
    expect(summarise(es as any)).toMatchObject({ balanceNgwee: 0, direction: "Settled" });
    expect(entryId({ type: "DELIVERY_CHARGE", refType: "delivery", refId: "PCL-1" })).toBe(entryId({ type: "DELIVERY_CHARGE", refType: "delivery", refId: "PCL-1" }));
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
    const good = { externalProductId: "SKU-1", externalSellerId: "farm-1", title: "Tomatoes", priceNgwee: 18_000, stock: 5, checkoutUrl: "https://mabala.example/p/1" };
    expect(productProblems(good)).toEqual([]);
    expect(productProblems({ ...good, priceNgwee: 1.5 }).join()).toMatch(/priceNgwee/);
    expect(productProblems({ ...good, images: ["http://x"] }).join()).toMatch(/https/);
    expect(productProblems({ ...good, currency: "USD" }).join()).toMatch(/ZMW/);
    expect(productProblems({ ...good, checkoutUrl: "" }).join()).toMatch(/checkoutUrl/); // buyers must have somewhere to pay
    expect(productProblems({ stock: 3 }, true)).toEqual([]);
  });
});

describe("OpenAPI document", () => {
  it("documents every route group, the OAuth flow and all webhook events", () => {
    const s = openApiSpec("https://x/api/v1") as any;
    for (const p of ["/oauth/token", "/products", "/catalog", "/delivery/quotes", "/deliveries", "/coverage", "/webhooks", "/settlements"]) expect(s.paths[p]).toBeTruthy();
    expect(s.paths["/orders"]).toBeUndefined(); // platforms keep their own checkout and orders
    expect(s.components.securitySchemes.oauth2.flows.clientCredentials.tokenUrl).toBe("https://x/api/v1/oauth/token");
    expect(Object.keys(s.webhooks)).toHaveLength(13);
    for (const p of ["/ads", "/products/{externalId}/video", "/products/{externalId}/video/apply"]) expect(s.paths[p]).toBeTruthy();
  });
});

describe("AI media for partners", () => {
  it("video view hides unfinished media and reports progress", async () => {
    const { studioView } = await import("../src/open/media");
    const v = studioView("ext_1", { origin: { productId: "SKU" }, studio: { status: "processing", videoUrl: "https://x/v.mp4" } }, { steps: ["upload", "script", "animate", "render"], index: 2 });
    expect(v).toMatchObject({ status: "processing", videoUrl: null, externalProductId: "SKU", progress: { step: "animate", done: 2, total: 4 } });
    const r = studioView("ext_1", { studio: { status: "ready", videoUrl: "https://x/v.mp4", posterUrl: "https://x/p.jpg", applied: true, generativeMotion: true } });
    expect(r).toMatchObject({ status: "ready", videoUrl: "https://x/v.mp4", appliedToListing: true, generativeMotion: true, error: null });
    expect(studioView("ext_1", {}).status).toBe("none");
    expect(studioView("ext_1", { studio: { status: "failed" } }).error).toMatch(/original media is unchanged/);
  });
  it("only downloads public https images", async () => {
    const { fetchImage } = await import("../src/open/media");
    for (const u of ["http://cdn.example.com/a.jpg", "https://169.254.169.254/x", "https://localhost/a.jpg", "https://metadata.google.internal/x", "https://[::1]/a", "nope"])
      await expect(fetchImage(u, (async () => { throw new Error("must not fetch"); }) as any)).rejects.toMatchObject({ status: 422 });
    const img = (type: string, size = 10) => (async () => ({ ok: true, status: 200, headers: new Headers({ "content-type": type }), arrayBuffer: async () => new ArrayBuffer(size) })) as any;
    await expect(fetchImage("https://cdn.example.com/a.html", img("text/html"))).rejects.toMatchObject({ status: 422 });
    expect((await fetchImage("https://cdn.example.com/a.jpg", img("image/jpeg", 4))).length).toBe(4);
    await expect(fetchImage("https://cdn.example.com/big.jpg", img("image/jpeg", 11 * 1024 * 1024))).rejects.toMatchObject({ status: 422 });
  });
  it("media usage is a charge to the partner", async () => {
    const { signed } = await import("../src/open/settlement");
    expect(signed({ type: "MEDIA_USAGE", amountNgwee: 500 })).toBe(-500);
  });
  it("media scopes belong to the commerce service", async () => {
    const { effectiveScopes } = await import("../src/open/model");
    expect(effectiveScopes({ scopes: ["media:write", "media:read"], services: { commerce: false, logistics: true } })).toEqual([]);
    expect(effectiveScopes({ scopes: ["media:write"], services: { commerce: true, logistics: false } })).toEqual(["media:write"]);
  });
});
