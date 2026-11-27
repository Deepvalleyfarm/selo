/**
 * SeloNachipa Open Network — credentials and signatures (no third-party crypto libraries).
 *  - Client secrets are never stored: only a scrypt hash with a per-client salt.
 *  - Access tokens are short-lived HS256 JWTs (OAuth 2.0 client-credentials grant), signed with OPEN_API_JWT_SECRET.
 *  - Webhook signing secrets must be recoverable to sign events, so they are encrypted at rest with AES-256-GCM
 *    (key OPEN_API_ENC_KEY in Secret Manager).
 *  - Webhooks are signed Standard-Webhooks style: v1,base64(HMAC-SHA256(secret, `${id}.${timestamp}.${body}`)).
 */
import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, scryptSync, timingSafeEqual } from "node:crypto";

const b64u = (b: Buffer | string) => Buffer.from(b).toString("base64url");
const fromB64u = (s: string) => Buffer.from(s, "base64url");

export function newClientId(env: "sandbox" | "production") { return `${env === "sandbox" ? "sbx" : "live"}_${randomBytes(9).toString("base64url")}`; }
/** Shown once to the partner; only its hash is kept. */
export function newClientSecret() { return `sns_${randomBytes(32).toString("base64url")}`; }
export function newWebhookSecret() { return `whsec_${randomBytes(24).toString("base64")}`; }

export function hashSecret(secret: string, salt = randomBytes(16).toString("hex")) {
  return { salt, hash: scryptSync(secret, salt, 32).toString("hex") };
}
export function verifySecret(secret: string, salt: string, hash: string) {
  const got = scryptSync(String(secret), salt, 32), want = Buffer.from(hash, "hex");
  return got.length === want.length && timingSafeEqual(got, want);
}

export interface TokenClaims { pid: string; cid: string; env: "sandbox" | "production"; scope: string[]; iat: number; exp: number; jti: string }
export function signToken(c: Omit<TokenClaims, "iat" | "exp" | "jti">, key: string, ttlSec = 3600, now = Math.floor(Date.now() / 1000)): string {
  const head = b64u(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const body = b64u(JSON.stringify({ ...c, iat: now, exp: now + ttlSec, jti: randomBytes(8).toString("hex"), iss: "selonachipa-open-network" }));
  return `${head}.${body}.${b64u(createHmac("sha256", key).update(`${head}.${body}`).digest())}`;
}
export function verifyToken(token: string, key: string, now = Math.floor(Date.now() / 1000)): TokenClaims | null {
  const [h, b, s] = String(token).split(".");
  if (!h || !b || !s) return null;
  const want = createHmac("sha256", key).update(`${h}.${b}`).digest(), got = fromB64u(s);
  if (got.length !== want.length || !timingSafeEqual(got, want)) return null;
  try {
    const head = JSON.parse(fromB64u(h).toString()); if (head.alg !== "HS256") return null;
    const c = JSON.parse(fromB64u(b).toString()) as TokenClaims;
    return c.exp > now ? c : null;
  } catch { return null; }
}

function keyBytes(key: string) { return createHash("sha256").update(key).digest(); }
export function encrypt(plain: string, key: string): string {
  const iv = randomBytes(12), c = createCipheriv("aes-256-gcm", keyBytes(key), iv);
  const enc = Buffer.concat([c.update(plain, "utf8"), c.final()]);
  return `v1.${b64u(iv)}.${b64u(enc)}.${b64u(c.getAuthTag())}`;
}
export function decrypt(blob: string, key: string): string {
  const [v, iv, enc, tag] = blob.split(".");
  if (v !== "v1") throw new Error("unknown ciphertext version");
  const d = createDecipheriv("aes-256-gcm", keyBytes(key), fromB64u(iv)); d.setAuthTag(fromB64u(tag));
  return Buffer.concat([d.update(fromB64u(enc)), d.final()]).toString("utf8");
}

/** Signature header value for a webhook delivery (partners verify with the same formula). */
export function signWebhook(secret: string, id: string, timestamp: number, body: string) {
  const key = secret.startsWith("whsec_") ? Buffer.from(secret.slice(6), "base64") : Buffer.from(secret);
  return `v1,${createHmac("sha256", key).update(`${id}.${timestamp}.${body}`).digest("base64")}`;
}
export function verifyWebhook(secret: string, id: string, timestamp: number, body: string, header: string, nowSec = Math.floor(Date.now() / 1000), toleranceSec = 300) {
  if (Math.abs(nowSec - timestamp) > toleranceSec) return false;
  const want = Buffer.from(signWebhook(secret, id, timestamp, body));
  return String(header).split(" ").some((sig) => { const got = Buffer.from(sig); return got.length === want.length && timingSafeEqual(got, want); });
}
/** Stable hash used for idempotency-key request fingerprints. */
export const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");
