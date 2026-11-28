/** SeloNachipa Open Network — HTTP entry point (/api/v1/**) and module exports. */
import { onRequest } from "firebase-functions/v2/https";
import { handle, route } from "./gateway";
import { CLOUDINARY_URL } from "../studio/providers";
import { GEMINI_API_KEY, SMS_SECRETS } from "../config";
import { OPEN_SECRETS } from "./model";
import { openApiSpec } from "./openapi";
import "./commerce";
import "./logistics";
import "./media";

route("GET", "/openapi.json", null, async () => openApiSpec());
route("GET", "/health", null, async () => ({ ok: true, service: "selonachipa-open-network", version: "1.0.0" }));

export const openapi_v1 = onRequest({ secrets: [...SMS_SECRETS, ...OPEN_SECRETS, GEMINI_API_KEY, CLOUDINARY_URL], cors: false, memory: "512MiB", timeoutSeconds: 120 }, async (req, res) => {
  const r = await handle(req);
  if (r.headers) Object.entries(r.headers).forEach(([k, v]) => res.set(k, v));
  res.set("Cache-Control", "no-store").status(r.status).json(r.body);
});

export { onOpenEvent, scheduled_openWebhookRetry } from "./webhooks";
export { onPartnerParcelChange } from "./triggers";
export * from "./manage";
