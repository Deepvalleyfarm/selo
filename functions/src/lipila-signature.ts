import { createHmac, timingSafeEqual } from "node:crypto";

/** HMAC-SHA256 over the raw request body, hex encoded, compared in constant time. */
export function verifyLipilaSignature(rawBody: Buffer, signature: string | undefined, secret: string): boolean {
  if (!signature) return false;
  const expected = createHmac("sha256", secret).update(rawBody).digest("hex");
  const a = Buffer.from(expected), b = Buffer.from(signature.replace(/^sha256=/, ""));
  return a.length === b.length && timingSafeEqual(a, b);
}
