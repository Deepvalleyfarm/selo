/**
 * Lipila webhook security (Standard Webhooks scheme, HMAC-SHA256) — reusable, framework-free.
 *
 *   headers:  webhook-id, webhook-timestamp (unix seconds), webhook-signature ("v1,<base64> v1,<base64> …")
 *   signed:   `${webhook-id}.${webhook-timestamp}.${rawBody}`
 *   key:      base64-decoded secret (an optional "whsec_" prefix is stripped)
 *
 * Order of checks: required headers → timestamp within tolerance (replay protection) → signature (constant time).
 * Several secrets may be configured at once (space/comma separated) so a key rotation never drops webhooks.
 * Neither the secret nor signature values are ever logged.
 */
import crypto from "node:crypto";

export class WebhookVerificationError extends Error {
  constructor(message: string) { super(message); this.name = "WebhookVerificationError"; }
}

/** Every field Lipila sends in a callback. */
export interface LipilaWebhookPayload {
  referenceId: string; currency: string; amount: number; accountNumber: string; status: string; paymentType: string;
  type: string; ipAddress: string; identifier: string; message: string; externalId?: string | null; referenceData?: string | null;
}

export const DEFAULT_TOLERANCE_SECONDS = 300;

export function parseSecrets(raw: string): Buffer[] {
  return raw.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean)
    .map((s) => Buffer.from(s.replace(/^whsec_/, ""), "base64")).filter((b) => b.length > 0);
}

export function sign(secret: Buffer, id: string, timestamp: string, rawBody: Buffer | string): string {
  return `v1,${crypto.createHmac("sha256", secret).update(`${id}.${timestamp}.`).update(rawBody).digest("base64")}`;
}

function header(h: Record<string, unknown>, name: string): string {
  const v = h[name] ?? h[name.toLowerCase()];
  return Array.isArray(v) ? String(v[0] ?? "") : String(v ?? "");
}

/**
 * Verifies a Lipila webhook and returns its webhook-id. Throws WebhookVerificationError on any failure.
 * `rawBody` must be the exact bytes received, read before any JSON parsing.
 */
export function verifyLipilaSignature(rawBody: Buffer, headers: Record<string, unknown>, secrets: Buffer[],
  opts: { toleranceSeconds?: number; nowMs?: number } = {}): { webhookId: string; timestamp: number } {
  const id = header(headers, "webhook-id"), ts = header(headers, "webhook-timestamp"), sigHeader = header(headers, "webhook-signature");
  if (!id || !ts || !sigHeader) throw new WebhookVerificationError("Missing webhook-id, webhook-timestamp or webhook-signature header");
  if (!secrets.length) throw new WebhookVerificationError("Webhook secret is not configured");
  const t = Number(ts);
  if (!/^\d+$/.test(ts) || !Number.isFinite(t)) throw new WebhookVerificationError("Invalid webhook-timestamp");
  const tolerance = opts.toleranceSeconds ?? DEFAULT_TOLERANCE_SECONDS;
  const now = Math.floor((opts.nowMs ?? Date.now()) / 1000);
  if (Math.abs(now - t) > tolerance) throw new WebhookVerificationError(`Webhook timestamp outside the ${tolerance}s tolerance`);
  const presented = sigHeader.split(" ").map((s) => s.trim()).filter((s) => s.startsWith("v1,")).map((s) => Buffer.from(s.slice(3), "base64"));
  if (!presented.length) throw new WebhookVerificationError("No v1 signature in webhook-signature");
  for (const secret of secrets) {
    const expected = Buffer.from(sign(secret, id, ts, rawBody).slice(3), "base64");
    for (const p of presented) if (p.length === expected.length && crypto.timingSafeEqual(p, expected)) return { webhookId: id, timestamp: t };
  }
  throw new WebhookVerificationError("Webhook signature does not match");
}

/** Lipila status values, verbatim. Final failures release funds / fail payments; network-type statuses do not. */
export const LIPILA_STATUSES = ["Successful", "Failed", "Pending", "Wrong PIN", "Wrong Number", "Insufficient Funds", "Transaction Limit Exceeded",
  "Timeout", "Cancelled", "Network Error", "System Error", "Unknown"] as const;
const FINAL_FAILURE = new Set(["failed", "wrong pin", "wrong number", "insufficient funds", "transaction limit exceeded", "transaction limit", "cancelled", "canceled", "rejected", "declined"]);
export const isSuccessStatus = (s: unknown) => /^(successful|success|completed)$/i.test(String(s ?? "").trim());
/** Timeout, Network Error, System Error, Pending and Unknown are NOT final: Lipila/MNO may still complete them. */
export const isFinalFailure = (s: unknown) => FINAL_FAILURE.has(String(s ?? "").trim().toLowerCase());

/** Normalises a callback body (tolerates a {data: …} envelope) into the payload shape. */
export function toPayload(body: unknown): LipilaWebhookPayload {
  const b = (body ?? {}) as Record<string, any>;
  const d = (b.data && typeof b.data === "object" ? b.data : b) as Record<string, any>;
  const str = (v: unknown) => (v == null ? "" : String(v).slice(0, 300));
  return { referenceId: str(d.referenceId), currency: str(d.currency || "ZMW"), amount: Number(d.amount ?? 0) || 0, accountNumber: str(d.accountNumber),
    status: str(d.status), paymentType: str(d.paymentType), type: str(d.type), ipAddress: str(d.ipAddress), identifier: str(d.identifier),
    message: str(d.message), externalId: d.externalId != null ? str(d.externalId) : null, referenceData: d.referenceData != null ? str(d.referenceData) : null };
}
