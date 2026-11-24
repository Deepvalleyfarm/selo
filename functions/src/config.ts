import { initializeApp, getApps } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { defineSecret, defineString } from "firebase-functions/params";
import { setGlobalOptions } from "firebase-functions/v2";

/** Named Firestore database shared by the Android app and the web back office. */
export const DB_ID = "ai-studio-611c950c-febe-47c1-afff-35dcb0788339";
export const REGION = "us-central1";

if (!getApps().length) initializeApp();
setGlobalOptions({ region: REGION, maxInstances: 20 });

export const db = getFirestore(DB_ID);

// Secrets live in Secret Manager: `firebase functions:secrets:set LIPILA_API_KEY` etc.
export const LIPILA_API_KEY = defineSecret("LIPILA_API_KEY");
export const LIPILA_WEBHOOK_SECRET = defineSecret("LIPILA_WEBHOOK_SECRET");
export const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");

// Non-secret parameters (set in functions/.env.<project>)
export const LIPILA_BASE_URL = defineString("LIPILA_BASE_URL", { default: "https://api.lipila.dev" });
export const LIPILA_CALLBACK_URL = defineString("LIPILA_CALLBACK_URL", { default: "" });
export const GEMINI_MODEL = defineString("GEMINI_MODEL", { default: "gemini-2.5-flash" });

import { DEFAULT_CONFIG, PlatformConfig } from "./domain-defaults";
export { DEFAULT_CONFIG };
export type { PlatformConfig };
export async function loadConfig(): Promise<PlatformConfig> {
  const snap = await db.doc("platformConfig/default").get();
  return { ...DEFAULT_CONFIG, ...(snap.data() as Partial<PlatformConfig> | undefined) };
}
