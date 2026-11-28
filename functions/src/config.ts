import { initializeApp, getApps } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { defineSecret, defineString } from "firebase-functions/params";
import { setGlobalOptions } from "firebase-functions/v2";
import { BEEM_SECRETS } from "./beem/beem.config";

/** Named Firestore database shared by the Android app and the web back office. */
export const DB_ID = "ai-studio-611c950c-febe-47c1-afff-35dcb0788339";
export const REGION = "us-central1";

if (!getApps().length) initializeApp();
/**
 * Beem (SMS + OTP) keys for every function. Transactional SMS (sign-up, orders, parcels, delivery codes, SOS) is sent
 * from many functions, often indirectly (payment webhook → order confirmed → SMS), and a function can only read a
 * secret it declares. Functions with their own `secrets` must include SMS_SECRETS too: their list replaces this one.
 */
export const SMS_SECRETS = BEEM_SECRETS;
setGlobalOptions({ region: REGION, maxInstances: 20, secrets: SMS_SECRETS });

export const db = getFirestore(DB_ID);

// Secrets live in Secret Manager: `firebase functions:secrets:set LIPILA_API_KEY` etc.
export const LIPILA_API_KEY = defineSecret("LIPILA_API_KEY");
export const LIPILA_WEBHOOK_SECRET = defineSecret("LIPILA_WEBHOOK_SECRET");
export const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
/** Google Maps Platform key with the Routes API and Geocoding API enabled (rider turn-by-turn). */
export const MAPS_API_KEY = defineSecret("MAPS_API_KEY");
/** Server secret for HMAC-hashing delivery codes. Rotate only when no code is live (open codes would stop matching). */
export const DELIVERY_CODE_SECRET = defineSecret("DELIVERY_CODE_SECRET");
/** Zambian SMS gateway (fallback channel for delivery codes and key events). */
export const SMS_API_KEY = defineSecret("SMS_API_KEY");
export const SMS_PROVIDER = defineString("SMS_PROVIDER", { default: "beem" }); // beem | log
export const SMS_USERNAME = defineString("SMS_USERNAME", { default: "" });
export const SMS_SENDER_ID = defineString("SMS_SENDER_ID", { default: "SeloNaChipa" });
export const SMS_HTTP_URL = defineString("SMS_HTTP_URL", { default: "" });
export const TRACKING_BASE_URL = defineString("TRACKING_BASE_URL", { default: "https://mabala-f2d65.web.app/t/" });

// Non-secret parameters (set in functions/.env.<project>)
// Production host must be set explicitly in functions/.env.<project> (no default: never fall back to the sandbox).
export const LIPILA_BASE_URL = defineString("LIPILA_BASE_URL", { default: "" });
export const LIPILA_CALLBACK_URL = defineString("LIPILA_CALLBACK_URL", { default: "" });
export const GEMINI_MODEL = defineString("GEMINI_MODEL", { default: "gemini-2.5-flash" });

import { DEFAULT_CONFIG, PlatformConfig } from "./domain-defaults";
export { DEFAULT_CONFIG };
export type { PlatformConfig };
export async function loadConfig(): Promise<PlatformConfig> {
  const snap = await db.doc("platformConfig/default").get();
  return { ...DEFAULT_CONFIG, ...(snap.data() as Partial<PlatformConfig> | undefined) };
}
