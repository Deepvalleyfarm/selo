/**
 * BEEM-OTP-001 · centralised Beem Africa configuration.
 *
 * The same module serves SeloNaChipa and Mabala: only these values change per application.
 * Secrets (API key, secret key) come from Google Secret Manager in Firebase:
 *   firebase functions:secrets:set BEEM_API_KEY
 *   firebase functions:secrets:set BEEM_SECRET_KEY
 * Non-secret values come from functions/.env.<projectId> (see functions/.env.example). Outside Firebase the module
 * reads the same names from process.env.
 * Credentials are never sent to an app or written to a log.
 */
import { defineSecret, defineString } from "firebase-functions/params";

export const BEEM_API_KEY = defineSecret("BEEM_API_KEY");
export const BEEM_SECRET_KEY = defineSecret("BEEM_SECRET_KEY");
const P = {
  appId: defineString("BEEM_APP_ID", { default: "" }),
  senderId: defineString("BEEM_SENDER_ID", { default: "" }),
  appName: defineString("BEEM_APP_NAME", { default: "Selonachipa" }),
  otpBaseUrl: defineString("BEEM_OTP_BASE_URL", { default: "https://apiotp.beem.africa/v1" }),
  smsBaseUrl: defineString("BEEM_SMS_BASE_URL", { default: "https://apisms.beem.africa/v1" }),
  balanceUrl: defineString("BEEM_BALANCE_URL", { default: "https://apisms.beem.africa/public/v1/vendors/balance" }),
  countryCodes: defineString("BEEM_COUNTRY_CODES", { default: "260" }),
  healthMode: defineString("BEEM_HEALTH_MODE", { default: "probe" }),             // probe | send
  healthMsisdn: defineString("BEEM_HEALTH_TEST_MSISDN", { default: "" }),
};
/** Secrets that every Beem-calling function must declare. */
export const BEEM_SECRETS = [BEEM_API_KEY, BEEM_SECRET_KEY];

export interface BeemConfig {
  apiKey: string; secretKey: string; appId: number; senderId: string; appName: string; application: string;
  otpBaseUrl: string; smsBaseUrl: string; balanceUrl: string; countryCodes: string[]; healthMode: "probe" | "send"; healthMsisdn: string;
}

const read = (p: { value(): string }, env: string) => { try { return p.value() || process.env[env] || ""; } catch { return process.env[env] || ""; } };

export function beemConfig(): BeemConfig {
  const appName = read(P.appName, "BEEM_APP_NAME") || "Selonachipa";
  return {
    apiKey: read(BEEM_API_KEY, "BEEM_API_KEY"), secretKey: read(BEEM_SECRET_KEY, "BEEM_SECRET_KEY"),
    appId: Number(read(P.appId, "BEEM_APP_ID")) || 0, senderId: read(P.senderId, "BEEM_SENDER_ID"), appName, application: appName.toLowerCase(),
    otpBaseUrl: read(P.otpBaseUrl, "BEEM_OTP_BASE_URL") || "https://apiotp.beem.africa/v1",
    smsBaseUrl: read(P.smsBaseUrl, "BEEM_SMS_BASE_URL") || "https://apisms.beem.africa/v1",
    balanceUrl: read(P.balanceUrl, "BEEM_BALANCE_URL") || "https://apisms.beem.africa/public/v1/vendors/balance",
    countryCodes: (read(P.countryCodes, "BEEM_COUNTRY_CODES") || "260").split(",").map((s) => s.trim()).filter(Boolean),
    healthMode: read(P.healthMode, "BEEM_HEALTH_MODE") === "send" ? "send" : "probe",
    healthMsisdn: read(P.healthMsisdn, "BEEM_HEALTH_TEST_MSISDN"),
  };
}

export const configProblem = (c: BeemConfig): string | null =>
  !c.apiKey || !c.secretKey ? "BEEM_API_KEY / BEEM_SECRET_KEY are not set" : !c.appId ? "BEEM_APP_ID is not set" : !c.senderId ? "BEEM_SENDER_ID is not set" : null;

/** HTTP Basic auth header: base64(api_key:secret_key) with Node's Buffer. */
export const authHeader = (c: Pick<BeemConfig, "apiKey" | "secretKey">) => `Basic ${Buffer.from(`${c.apiKey}:${c.secretKey}`).toString("base64")}`;

/**
 * Cleans a number for Beem: strips +, spaces, dashes and brackets; turns a local 0XXXXXXXXX into 260XXXXXXXXX.
 * Returns null unless it is a valid number for a supported country (Zambia: 260 + 9 digits, mobile prefixes 7x/9x).
 */
export function cleanMsisdn(raw: unknown, countryCodes: string[] = ["260"]): string | null {
  let s = String(raw ?? "").replace(/[\s\-+().]/g, "");
  if (!/^\d+$/.test(s)) return null;
  if (s.startsWith("00")) s = s.slice(2);
  if (countryCodes.includes("260")) {
    if (/^0[79]\d{8}$/.test(s)) s = `260${s.slice(1)}`;
    else if (/^[79]\d{8}$/.test(s)) s = `260${s}`;
    if (s.startsWith("260")) return /^260(7[5-7]|9[5-7])\d{7}$/.test(s) ? s : null;
  }
  return countryCodes.some((cc) => cc !== "260" && s.startsWith(cc)) && s.length >= 10 && s.length <= 15 ? s : null;
}

/** Admin-screen masking: first 6 and last 3 digits (260977***001). */
export const maskMsisdn = (m: string) => (m.length > 9 ? `${m.slice(0, 6)}${"*".repeat(Math.max(3, m.length - 9))}${m.slice(-3)}` : "***");
