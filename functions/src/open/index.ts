/** SeloNachipa Open Network — HTTP entry point (/api/v1/**) and module exports. */
import { onRequest } from "firebase-functions/v2/https";
import { handle, route } from "./gateway";
import { OPEN_SECRETS } from "./model";
import { openApiSpec } from "./openapi";
import "./commerce";
import "./logistics";

route("GET", "/openapi.json", null, async () => openApiSpec());
route("GET", "/health", null, async () => ({ ok: true, service: "selonachipa-open-network", version: "1.0.0" }));

export const openapi_v1 = onRequest({ secrets: OPEN_SECRETS, cors: false, memory: "512MiB", timeoutSeconds: 60 }, async (req, res) => {
  const r = await handle(req);
  if (r.headers) Object.entries(r.headers).forEach(([k, v]) => res.set(k, v));
  res.set("Cache-Control", "no-store").status(r.status).json(r.body);
});

export { onOpenEvent, scheduled_openWebhookRetry } from "./webhooks";
export { scheduled_openOrderTimeouts } from "./commerce";
export { onPartnerOrderChange, onPartnerParcelChange } from "./triggers";
export * from "./manage";
