/** OpenAPI 3.1 description of the SeloNachipa Open Network API (served at /api/v1/openapi.json, rendered by the developer portal). */
import { EVENTS, SCOPES } from "./model";

const ref = (n: string) => ({ $ref: `#/components/schemas/${n}` });
const json = (schema: unknown, example?: unknown) => ({ content: { "application/json": { schema, ...(example ? { example } : {}) } } });
const errs = { 400: { $ref: "#/components/responses/BadRequest" }, 401: { $ref: "#/components/responses/Unauthorized" }, 403: { $ref: "#/components/responses/Forbidden" },
  404: { $ref: "#/components/responses/NotFound" }, 409: { $ref: "#/components/responses/Conflict" }, 422: { $ref: "#/components/responses/Validation" }, 429: { $ref: "#/components/responses/RateLimited" } };
const idem = { name: "Idempotency-Key", in: "header", required: true, schema: { type: "string", maxLength: 120 }, description: "Unique per operation. Retrying with the same key and body returns the original response; a different body returns 409." };
const op = (tag: string, summary: string, scope: string, extra: Record<string, unknown> = {}) => ({ tags: [tag], summary, security: [{ oauth2: [scope] }], ...extra,
  responses: { 200: json({ type: "object" }), ...errs, ...(extra.responses as object ?? {}) } });

const productExample = { checkoutUrl: "https://www.mabala.cloud/shop/p/MBL-SKU-0042", externalProductId: "MBL-SKU-0042", externalSellerId: "farm-node-117", sellerName: "Mwila Family Farm", title: "Fresh tomatoes, 20 kg crate",
  description: "Grade A tomatoes harvested this week in Chongwe.", category: "Agriculture", images: ["https://cdn.example.com/p/42.jpg"], priceNgwee: 18000, currency: "ZMW", stock: 35,
  location: { lat: -15.36, lng: 28.45, address: "Chongwe Farm Block 3", district: "Chongwe", province: "Lusaka" }, deliveryOptions: { selonachipaDelivery: true, pickup: false },
  sharing: { selonachipaMarketplace: true, partners: [] } };

export function openApiSpec(server = "https://mabala-f2d65.web.app/api/v1") {
  return {
    openapi: "3.1.0",
    info: { title: "SeloNachipa Open Network API", version: "1.0.0",
      description: "Open Commerce: list your products on the SeloNachipa marketplace for discovery — buyers are sent to your own checkout (checkoutUrl) and you keep your orders, payments and stock. " +
        "Open Logistics: quote and request deliveries by the SeloNachipa rider network. The delivery fee is set by SeloNachipa: charge your buyer the quoted priceNgwee on your checkout; you owe it to SeloNachipa in full, and SeloNachipa splits it to the rider, platform and Social Fund.\n\n" +
        "Authentication: OAuth 2.0 client credentials. POST /oauth/token with your client_id and client_secret (server-side only — never in browser or mobile code), then send `Authorization: Bearer <token>`. Tokens last 1 hour.\n\n" +
        "Money is in ngwee (K 1.00 = 100). Every write needs an Idempotency-Key header. Sandbox products are never shown to buyers and sandbox deliveries never reach real riders — they move through every status automatically, one step a minute, firing your webhooks.",
      contact: { name: "SeloNachipa developer support", email: "support@mabala.cloud" } },
    servers: [{ url: server }],
    tags: ["Auth", "Products", "Catalog", "Deliveries", "Coverage", "Webhooks", "Settlements"].map((name) => ({ name })),
    paths: {
      "/oauth/token": { post: { tags: ["Auth"], summary: "Get an access token (client credentials)", security: [],
        requestBody: json({ type: "object", required: ["grant_type", "client_id", "client_secret"], properties: { grant_type: { const: "client_credentials" }, client_id: { type: "string" },
          client_secret: { type: "string" }, scope: { type: "string", description: "Space-separated subset of your granted scopes" } } }, { grant_type: "client_credentials", client_id: "sbx_…", client_secret: "sns_…" }),
        responses: { 200: json(ref("Token")), 401: json(ref("OAuthError")), 403: json(ref("OAuthError")) } } },
      "/me": { get: op("Auth", "Your platform, environment, services and scopes", "settlements:read") },
      "/products": {
        post: { ...op("Products", "Publish a product (create or replace by externalProductId)", "products:write"), parameters: [idem], requestBody: json(ref("ProductInput"), productExample),
          responses: { 201: json({ type: "object", properties: { product: ref("Product"), created: { type: "boolean" } } }), ...errs } },
        get: op("Products", "List your published products", "products:read") },
      "/products/{externalId}": {
        get: op("Products", "Get one of your products", "products:read", { parameters: [{ name: "externalId", in: "path", required: true, schema: { type: "string" } }] }),
        patch: op("Products", "Update fields (title, price, images, sharing, …)", "products:write", { parameters: [{ name: "externalId", in: "path", required: true, schema: { type: "string" } }, idem], requestBody: json(ref("ProductInput")) }),
        delete: op("Products", "Archive (remove from all marketplaces)", "products:write", { parameters: [{ name: "externalId", in: "path", required: true, schema: { type: "string" } }, idem] }) },
      "/products/{externalId}/stock": { post: op("Products", "Set available stock (your system stays authoritative)", "inventory:write",
        { parameters: [{ name: "externalId", in: "path", required: true, schema: { type: "string" } }, idem], requestBody: json({ type: "object", required: ["stock"], properties: { stock: { type: "integer", minimum: 0 } } }, { stock: 28 }) }) },
      "/products/{externalId}/unpublish": { post: op("Products", "Hide from marketplaces (keeps the record)", "products:write", { parameters: [{ name: "externalId", in: "path", required: true, schema: { type: "string" } }, idem] }) },
      "/catalog": { get: op("Catalog", "Products other platforms shared with you", "catalog:read") },
      "/catalog/{listingId}": { get: op("Catalog", "One shared product", "catalog:read", { parameters: [{ name: "listingId", in: "path", required: true, schema: { type: "string" } }] }) },
      "/delivery/quotes": { post: { ...op("Deliveries", "SeloNachipa delivery fee to charge your buyer, with distance and ETA", "delivery:quote"), requestBody: json(ref("QuoteInput"),
        { pickup: { lat: -15.4167, lng: 28.2833, address: "Soweto Market" }, dropoff: { lat: -15.43, lng: 28.30, address: "Kabwata" }, priority: "STANDARD" }), responses: { 200: json(ref("Quote")), ...errs } } },
      "/deliveries": {
        post: { ...op("Deliveries", "Request a delivery (dispatched to the SeloNachipa rider network)", "delivery:write"), parameters: [idem], requestBody: json(ref("DeliveryInput"),
          { externalOrderRef: "MBL-ORD-5531", pickup: { lat: -15.4167, lng: 28.2833, address: "Soweto Market, stall 14" }, dropoff: { lat: -15.43, lng: 28.30, address: "Plot 9, Kabwata" },
            weightBand: "4–5 kg", description: "Vegetables, 1 crate", receiver: { name: "Chanda Mwila", msisdn: "+260971234567" }, instructions: "Call on arrival", priority: "STANDARD" }),
          responses: { 201: json({ type: "object", properties: { delivery: ref("Delivery"), pickupPin: { type: "string", description: "Shown once: give it to whoever hands over the goods" }, trackingUrl: { type: "string" } } }), ...errs } },
        get: op("Deliveries", "Your deliveries", "delivery:read") },
      "/deliveries/{id}": { get: op("Deliveries", "One delivery with live rider position while active", "delivery:read", { parameters: [{ name: "id", in: "path", required: true, schema: { type: "string" } }] }) },
      "/deliveries/{id}/cancel": { post: op("Deliveries", "Cancel before pickup (charge reversed)", "delivery:write", { parameters: [{ name: "id", in: "path", required: true, schema: { type: "string" } }, idem] }) },
      "/coverage": { get: op("Coverage", "Cities, neighbourhoods and online riders per city", "coverage:read") },
      "/riders/availability": { get: op("Coverage", "Riders online within 5 km (counts only)", "coverage:read", { parameters: [{ name: "lat", in: "query", required: true, schema: { type: "number" } }, { name: "lng", in: "query", required: true, schema: { type: "number" } }] }) },
      "/webhooks": { get: op("Webhooks", "Your webhook URL and subscribed events", "webhooks:manage"),
        put: op("Webhooks", "Set URL and events (returns the signing secret once)", "webhooks:manage", { parameters: [idem], requestBody: json({ type: "object", properties: { url: { type: "string", format: "uri" }, events: { type: "array", items: { enum: EVENTS } }, rotateSecret: { type: "boolean" } } }) }) },
      "/webhooks/events": { get: op("Webhooks", "Event history with delivery status", "webhooks:manage") },
      "/webhooks/events/{id}/replay": { post: op("Webhooks", "Re-send an event", "webhooks:manage", { parameters: [{ name: "id", in: "path", required: true, schema: { type: "string" } }, idem] }) },
      "/webhooks/test": { post: op("Webhooks", "Send a test event", "webhooks:manage", { parameters: [idem] }) },
      "/settlements": { get: op("Settlements", "Ledger: delivery charges and reversals, API usage, subscription, settlements and balance", "settlements:read") },
    },
    webhooks: Object.fromEntries(EVENTS.map((e) => [e, { post: { summary: e, description: "Headers: webhook-id, webhook-timestamp, webhook-signature (v1,base64 HMAC-SHA256 of `${id}.${timestamp}.${body}` with your signing secret, base64-decoded after the whsec_ prefix). Reject timestamps older than 5 minutes and de-duplicate by webhook-id.",
      requestBody: json(ref("Event")), responses: { 200: { description: "Acknowledge with any 2xx within 10 s; otherwise we retry (1 m, 5 m, 15 m, 1 h, 3 h, 6 h, 12 h, 24 h)." } } } }])),
    components: {
      securitySchemes: { oauth2: { type: "oauth2", flows: { clientCredentials: { tokenUrl: `${server}/oauth/token`, scopes: Object.fromEntries(Object.entries(SCOPES).map(([k, v]) => [k, v.description])) } } } },
      responses: Object.fromEntries([["BadRequest", 400, "idempotency_key_required"], ["Unauthorized", 401, "unauthorized"], ["Forbidden", 403, "insufficient_scope"], ["NotFound", 404, "not_found"],
        ["Conflict", 409, "idempotency_conflict / conflict"], ["Validation", 422, "validation_failed"], ["RateLimited", 429, "rate_limited (see Retry-After)"]]
        .map(([n, , code]) => [n, { description: String(code), ...json(ref("Error")) }])),
      schemas: {
        Error: { type: "object", properties: { error: { type: "object", properties: { code: { type: "string" }, message: { type: "string" }, details: {} } } } },
        OAuthError: { type: "object", properties: { error: { type: "string" }, error_description: { type: "string" } } },
        Token: { type: "object", properties: { access_token: { type: "string" }, token_type: { const: "Bearer" }, expires_in: { type: "integer" }, scope: { type: "string" }, environment: { enum: ["sandbox", "production"] } } },
        ProductInput: { type: "object", required: ["externalProductId", "externalSellerId", "title", "priceNgwee", "stock", "checkoutUrl"], properties: {
          checkoutUrl: { type: "string", format: "uri", description: "https link to buy this product on your platform; SeloNachipa sends buyers here" },
          externalProductId: { type: "string" }, externalSellerId: { type: "string" }, sellerName: { type: "string" }, title: { type: "string", minLength: 3 }, description: { type: "string" },
          category: { type: "string" }, images: { type: "array", maxItems: 10, items: { type: "string", format: "uri" } }, priceNgwee: { type: "integer", minimum: 1 }, currency: { const: "ZMW" },
          stock: { type: "integer", minimum: 0 }, location: { type: "object", properties: { lat: { type: "number" }, lng: { type: "number" }, address: { type: "string" }, district: { type: "string" }, province: { type: "string" } } },
          deliveryOptions: { type: "object", properties: { selonachipaDelivery: { type: "boolean" }, pickup: { type: "boolean" } } },
          sharing: { type: "object", properties: { selonachipaMarketplace: { type: "boolean" }, partners: { type: "array", items: { type: "string" }, description: "Partner ids allowed to resell" } } },
          status: { enum: ["published", "draft"] } } },
        Product: { type: "object", properties: { listingId: { type: "string" }, externalProductId: { type: "string" }, originPlatform: { type: "string" }, checkoutUrl: { type: "string" }, title: { type: "string" }, priceNgwee: { type: "integer" },
          availableStock: { type: "integer" }, status: { type: "string", description: "pending_review → live (after automatic moderation), paused, archived, sandbox" }, lastSyncedAt: { type: "string", format: "date-time" } } },
        QuoteInput: { type: "object", required: ["pickup", "dropoff"], properties: { pickup: ref("Point"), dropoff: ref("Point"), priority: { enum: ["STANDARD", "EXPRESS"] } } },
        Quote: { type: "object", properties: { priceNgwee: { type: "integer", description: "Total SeloNachipa delivery fee: charge your buyer this; owed to SeloNachipa in full" },
          breakdown: { type: "object", properties: { deliveryFeeNgwee: { type: "integer" }, serviceFeeNgwee: { type: "integer" } } }, currency: { const: "ZMW" }, distanceKm: { type: "number" }, etaMin: { type: "integer" }, city: { type: "string" }, validForMin: { type: "integer" } } },
        Point: { type: "object", required: ["lat", "lng"], properties: { lat: { type: "number" }, lng: { type: "number" }, address: { type: "string" }, label: { type: "string" }, landmark: { type: "string" } } },
        DeliveryInput: { type: "object", required: ["externalOrderRef", "pickup", "dropoff", "weightBand", "receiver"], properties: { externalOrderRef: { type: "string" }, pickup: ref("Point"), dropoff: ref("Point"),
          weightBand: { enum: ["Under 1 kg", "1–2 kg", "2–3 kg", "3–4 kg", "4–5 kg"] }, description: { type: "string" }, receiver: { type: "object", properties: { name: { type: "string" }, msisdn: { type: "string" } } },
          instructions: { type: "string" }, priority: { enum: ["STANDARD", "EXPRESS"] } } },
        Delivery: { type: "object", properties: { deliveryId: { type: "string" }, externalOrderRef: { type: "string" }, status: { enum: ["AWAITING_RIDER", "RIDER_ASSIGNED", "IN_TRANSIT", "AWAITING_DELIVERY_CODE", "COMPLETED", "DELIVERY_FAILED", "CANCELLED"] },
          priceNgwee: { type: "integer" }, distanceKm: { type: "number" }, rider: { type: ["object", "null"], properties: { firstName: { type: "string" }, plate: { type: "string" } } }, timeline: { type: "object" } } },
        Event: { type: "object", properties: { id: { type: "string" }, type: { enum: EVENTS }, created: { type: "string", format: "date-time" }, environment: { enum: ["sandbox", "production"] }, data: { type: "object" } } },
      },
    },
  };
}
