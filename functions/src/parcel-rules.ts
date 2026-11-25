/**
 * Parcels Module v2 — pure rules (unit tested). No Firestore, no network.
 * Fees, cities, the parcel state machine, delivery-code generation/hashing and the brute-force policy.
 */
import { createHmac, randomInt, timingSafeEqual } from "node:crypto";
import citiesJson from "./data/cities.json";

/* ------------------------------------------------------------------ config */

export interface ParcelConfig {
  parcelBaseFee: number;            // K, default 30
  parcelPerKmRate: number;          // K per km, default 4.5
  parcelMinFee: number;             // K, default 30
  sendingFeeRate: number;           // %, default 15
  sendingFeeCapPct: number;         // % of delivery fee, default 50
  minSendingFee: number;            // K, default 10
  parcelPlatformFeePct: number;     // %, default 10
  parcelSocialFundPct: number;      // %, default 1
  parcelMaxWeightKg: number;        // default 5
  parcelInsurancePct: number;       // %, default 2
  parcelInsuranceMaxDeclared: number; // K, default 5000
  parcelInsuranceMin: number;       // K, default 5
  deliveryGeofenceM: number;        // default 150
  deliveryCodeExpiryMin: number;    // default 15
  deliveryCodeMaxAttempts: number;  // default 5
  deliveryCodeCooldownMin: number;  // default 2
  deliveryCodeMaxPerOrder: number;  // default 3
  pushAckTimeoutSec: number;        // default 30, then SMS
  receiverWaitMin: number;          // default 10 inside the geofence before "Receiver unavailable"
  senderResponseMin: number;        // default 20, then DELIVERY_FAILED
  disputeWindowHours: number;       // default 2 → COMPLETED
  disputeReportHours: number;       // default 24
  failedDeliveryFeePct: number;     // % of delivery fee paid to rider on DELIVERY_FAILED (admin config, default 0)
  riderLockFlagCount: number;       // default 3 locks / 24 h
  parcelBroadcastMin: number;       // default 5 → re-broadcast
  parcelMaxBroadcastMin: number;    // default 60 → refund
  batchPickupRadiusKm: number;      // default 3
  maxBatch: number;                 // default 4
  maxActiveOrdersPerUser: number;   // default 10
  sellerReleaseOn: "delivery_code" | "pickup";
  sellerDisputeHoldHours: number;   // default 0
  referralCreditKwacha: number;     // default 5
  chatCloseAfterMin: number;        // default 60
  riderAutoOfflineMin: number;      // default 30
  failedDeliveryAction: "return_to_sender" | "hold_at_hub";
}

export const PARCEL_DEFAULTS: ParcelConfig = {
  parcelBaseFee: 30, parcelPerKmRate: 4.5, parcelMinFee: 30, sendingFeeRate: 15, sendingFeeCapPct: 50, minSendingFee: 10,
  parcelPlatformFeePct: 10, parcelSocialFundPct: 1, parcelMaxWeightKg: 5, parcelInsurancePct: 2, parcelInsuranceMaxDeclared: 5000,
  parcelInsuranceMin: 5, deliveryGeofenceM: 150, deliveryCodeExpiryMin: 15, deliveryCodeMaxAttempts: 5, deliveryCodeCooldownMin: 2,
  deliveryCodeMaxPerOrder: 3, pushAckTimeoutSec: 30, receiverWaitMin: 10, senderResponseMin: 20, disputeWindowHours: 2,
  disputeReportHours: 24, failedDeliveryFeePct: 0, riderLockFlagCount: 3, parcelBroadcastMin: 5, parcelMaxBroadcastMin: 60,
  batchPickupRadiusKm: 3, maxBatch: 4, maxActiveOrdersPerUser: 10, sellerReleaseOn: "delivery_code", sellerDisputeHoldHours: 0,
  referralCreditKwacha: 5, chatCloseAfterMin: 60, riderAutoOfflineMin: 30, failedDeliveryAction: "return_to_sender",
};

const n = (k: number) => Math.round(k * 100); // kwacha → ngwee

/* ------------------------------------------------------------------ fees (§3) */

export function deliveryFeeNgwee(distanceKm: number, c: Pick<ParcelConfig, "parcelBaseFee" | "parcelPerKmRate" | "parcelMinFee">): number {
  const d = Math.max(0, distanceKm);
  return Math.max(n(c.parcelMinFee), Math.round(n(c.parcelBaseFee) + n(c.parcelPerKmRate) * d));
}

/** §3.2 corrected: raw = fee × rate; capped at 50% of the fee; floor K10. */
export function sendingFeeNgwee(deliveryNgwee: number, c: Pick<ParcelConfig, "sendingFeeRate" | "sendingFeeCapPct" | "minSendingFee">): number {
  const raw = Math.round((deliveryNgwee * c.sendingFeeRate) / 100);
  const capped = Math.min(raw, Math.round((deliveryNgwee * c.sendingFeeCapPct) / 100));
  return Math.max(capped, n(c.minSendingFee));
}

export function insuranceNgwee(declaredNgwee: number, c: Pick<ParcelConfig, "parcelInsurancePct" | "parcelInsuranceMaxDeclared" | "parcelInsuranceMin">): number {
  if (declaredNgwee <= 0) return 0;
  const capped = Math.min(declaredNgwee, n(c.parcelInsuranceMaxDeclared));
  return Math.max(n(c.parcelInsuranceMin), Math.round((capped * c.parcelInsurancePct) / 100));
}

export interface ParcelQuote {
  distanceKm: number; deliveryNgwee: number; sendingNgwee: number; insuranceNgwee: number; totalNgwee: number;
  riderEarningsNgwee: number; platformFeeNgwee: number; socialFundNgwee: number; etaMin: number;
}

/** §3.3: platform 10%, social fund 1%, rider gets the rest (89%) — rounding residue goes to the rider. */
export function riderSplit(deliveryNgwee: number, c: Pick<ParcelConfig, "parcelPlatformFeePct" | "parcelSocialFundPct">) {
  const platform = Math.round((deliveryNgwee * c.parcelPlatformFeePct) / 100);
  const fund = Math.round((deliveryNgwee * c.parcelSocialFundPct) / 100);
  return { platformFeeNgwee: platform, socialFundNgwee: fund, riderEarningsNgwee: deliveryNgwee - platform - fund };
}

export function quoteParcel(distanceKm: number, durationMin: number, declaredNgwee: number, c: ParcelConfig): ParcelQuote {
  const deliveryNgwee = deliveryFeeNgwee(distanceKm, c);
  const sendingNgwee = sendingFeeNgwee(deliveryNgwee, c);
  const ins = insuranceNgwee(declaredNgwee, c);
  return { distanceKm: Math.round(distanceKm * 10) / 10, deliveryNgwee, sendingNgwee, insuranceNgwee: ins, totalNgwee: deliveryNgwee + sendingNgwee + ins,
    ...riderSplit(deliveryNgwee, c), etaMin: Math.ceil(durationMin) + 5 };
}

export const WEIGHT_BANDS = ["Under 1 kg", "1–2 kg", "2–3 kg", "3–4 kg", "4–5 kg"] as const;
export const weightOk = (band: unknown) => typeof band === "string" && (WEIGHT_BANDS as readonly string[]).includes(band);
export const PARCEL_CATEGORIES = ["Document", "Letter", "Clothing", "Food", "Sample", "Other"] as const;

/** Category suggestion from the description (shown as the auto-suggested chip). */
export function suggestCategory(description: string): (typeof PARCEL_CATEGORIES)[number] {
  const d = description.toLowerCase();
  if (/\b(letter|envelope|card)\b/.test(d)) return "Letter";
  if (/\b(document|contract|certificate|passport|form|file|papers?)\b/.test(d)) return "Document";
  if (/\b(shirt|dress|chitenge|clothe?s?|shoes?|jacket|fabric|suit)\b/.test(d)) return "Clothing";
  if (/\b(food|pizza|chicken|nshima|meal|lunch|cake|burger|snack)\b/.test(d)) return "Food";
  if (/\b(sample|swatch|specimen|tester)\b/.test(d)) return "Sample";
  return "Other";
}

/* ------------------------------------------------------------------ cities (§1, §11) */

export interface City { id: string; name: string; province: string; lat: number; lng: number; radiusKm: number; neighbourhoods: string[] }
export const CITIES: City[] = (citiesJson as { cities: City[] }).cities;
export interface LL { lat: number; lng: number }

export function haversineKm(a: LL, b: LL) {
  const R = 6371, r = (d: number) => (d * Math.PI) / 180;
  const h = Math.sin(r(b.lat - a.lat) / 2) ** 2 + Math.cos(r(a.lat)) * Math.cos(r(b.lat)) * Math.sin(r(b.lng - a.lng) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

/** The supported city a point falls in (nearest centre among cities whose service radius covers it), or null. */
export function cityOf(p: LL): City | null {
  return CITIES.filter((c) => haversineKm(p, c) <= c.radiusKm).sort((a, b) => haversineKm(p, a) - haversineKm(p, b))[0] ?? null;
}
export const cityById = (id: string) => CITIES.find((c) => c.id === id) ?? null;
export function sameCityProblem(pickup: LL, drop: LL): string | null {
  const a = cityOf(pickup), b = cityOf(drop);
  if (!a) return "SeloNaChipa doesn't deliver from that pickup location yet.";
  if (!b || b.id !== a.id) return `SeloNaChipa only supports same-city deliveries. Please enter a ${a.name} address.`;
  return null;
}
export const INTERCITY_MESSAGE = "Inter-city deliveries are not available. Both addresses must be in the same city.";

/** Deterministic stand-in position for a neighbourhood when it can't be geocoded (spread around the city centre). */
export function neighbourhoodFallback(city: City, neighbourhood: string): LL {
  let h = 2166136261; for (const ch of neighbourhood) h = Math.imul(h ^ ch.charCodeAt(0), 16777619) >>> 0;
  const ang = (h % 360) * (Math.PI / 180), dist = (0.2 + ((h >>> 9) % 60) / 100) * city.radiusKm * 0.6;
  return { lat: city.lat + (dist / 111) * Math.cos(ang), lng: city.lng + (dist / (111 * Math.cos((city.lat * Math.PI) / 180))) * Math.sin(ang) };
}

/* ------------------------------------------------------------------ state machine (§5) */

export type ParcelStatus = "PENDING_PAYMENT" | "AWAITING_RIDER" | "RIDER_ASSIGNED" | "IN_TRANSIT" | "AWAITING_DELIVERY_CODE" |
  "DELIVERED" | "COMPLETED" | "CANCELLED" | "DELIVERY_FAILED" | "DISPUTED";

const PARCEL_TRANSITIONS: Record<ParcelStatus, ParcelStatus[]> = {
  PENDING_PAYMENT: ["AWAITING_RIDER", "CANCELLED"],
  AWAITING_RIDER: ["RIDER_ASSIGNED", "CANCELLED"],
  RIDER_ASSIGNED: ["IN_TRANSIT", "AWAITING_RIDER", "CANCELLED"],
  IN_TRANSIT: ["AWAITING_DELIVERY_CODE", "DELIVERY_FAILED", "DISPUTED"],
  AWAITING_DELIVERY_CODE: ["DELIVERED", "IN_TRANSIT", "DELIVERY_FAILED", "DISPUTED"],
  DELIVERED: ["COMPLETED", "DISPUTED"],
  COMPLETED: ["DISPUTED"],
  DELIVERY_FAILED: ["DISPUTED", "COMPLETED"],
  DISPUTED: ["COMPLETED", "CANCELLED"],
  CANCELLED: [],
};
export const canMoveParcel = (from: ParcelStatus, to: ParcelStatus) => PARCEL_TRANSITIONS[from]?.includes(to) ?? false;
/** Statuses where only an admin may change the receiver's phone. */
export const receiverLocked = (s: ParcelStatus) => !["PENDING_PAYMENT", "AWAITING_RIDER", "RIDER_ASSIGNED"].includes(s);

/* ------------------------------------------------------------------ delivery codes (§9.2, §9.3) */

const TRIVIAL = new Set(["00000", "11111", "22222", "33333", "44444", "55555", "66666", "77777", "88888", "99999", "12345", "54321", "01234", "43210", "98765", "56789"]);
export const isTrivialCode = (c: string) => TRIVIAL.has(c);

/** Cryptographically random 5-digit code (crypto.randomInt), never trivial. */
export function generateDeliveryCode(rand: (max: number) => number = (m) => randomInt(0, m)): string {
  for (;;) { const c = String(rand(100_000)).padStart(5, "0"); if (!isTrivialCode(c)) return c; }
}
export function generatePickupPin(rand: (max: number) => number = (m) => randomInt(0, m)): string {
  for (;;) { const c = String(rand(10_000)).padStart(4, "0"); if (!/^(\d)\1{3}$/.test(c) && c !== "1234" && c !== "4321") return c; }
}

/** HMAC-SHA256(secret, orderId + ":" + code). Only this is stored. */
export function hashCode(secret: string, orderId: string, code: string) {
  return createHmac("sha256", secret).update(`${orderId}:${code}`).digest("hex");
}
export function codeMatches(secret: string, orderId: string, code: string, storedHash: string) {
  if (!/^\d{5}$/.test(code) || !/^[0-9a-f]{64}$/.test(storedHash)) return false;
  const a = Buffer.from(hashCode(secret, orderId, code), "hex"), b = Buffer.from(storedHash, "hex");
  return a.length === b.length && timingSafeEqual(a, b);
}

/** Stored state of the current code (parcels/{id}/deliveryCode/current). */
export interface CodeState {
  hash: string; state: "sent" | "verified" | "expired" | "locked" | "superseded"; issuedAt: number; expiresAt: number;
  attempts: number; codesIssued: number; lockedAt?: number | null; channel: "push" | "sms" | "push+sms"; smsResends: number;
}

export type RequestDecision = { ok: true; reuse: boolean } | { ok: false; reason: string };
/** Can the rider ask for a (new) code now? Existing live code → reuse (no new code); limits enforced. */
export function canIssueCode(cur: CodeState | null, now: number, c: ParcelConfig, forceNew = false): RequestDecision {
  if (!cur) return { ok: true, reuse: false };
  if (cur.state === "verified") return { ok: false, reason: "This delivery is already confirmed." };
  if (cur.state === "sent" && cur.expiresAt > now && !forceNew) return { ok: true, reuse: true };
  if (cur.codesIssued >= c.deliveryCodeMaxPerOrder) return { ok: false, reason: "No more codes can be sent for this order. Tap Receiver Unavailable or contact support." };
  if (cur.state === "locked" && cur.lockedAt && now - cur.lockedAt < c.deliveryCodeCooldownMin * 60_000)
    return { ok: false, reason: `Too many wrong codes. Wait ${Math.ceil((c.deliveryCodeCooldownMin * 60_000 - (now - cur.lockedAt)) / 60_000)} min before requesting a new code.` };
  return { ok: true, reuse: false };
}

export type VerifyOutcome =
  | { result: "verified" } | { result: "wrong"; attemptsLeft: number } | { result: "locked" }
  | { result: "expired" } | { result: "no_code" } | { result: "already_verified" };
/** Applies one attempt. Returns the outcome and the next code state (caller persists both atomically). */
export function applyAttempt(cur: CodeState | null, matches: boolean, now: number, c: ParcelConfig): { outcome: VerifyOutcome; next: CodeState | null } {
  if (!cur) return { outcome: { result: "no_code" }, next: null };
  if (cur.state === "verified") return { outcome: { result: "already_verified" }, next: cur };
  if (cur.state === "locked") return { outcome: { result: "locked" }, next: cur };
  if (cur.state !== "sent") return { outcome: { result: "no_code" }, next: cur };
  if (cur.expiresAt <= now) return { outcome: { result: "expired" }, next: { ...cur, state: "expired" } };
  if (matches) return { outcome: { result: "verified" }, next: { ...cur, state: "verified", attempts: cur.attempts + 1 } };
  const attempts = cur.attempts + 1;
  if (attempts >= c.deliveryCodeMaxAttempts) return { outcome: { result: "locked" }, next: { ...cur, attempts, state: "locked", lockedAt: now } };
  return { outcome: { result: "wrong", attemptsLeft: c.deliveryCodeMaxAttempts - attempts }, next: { ...cur, attempts } };
}

export const withinGeofence = (rider: LL, target: LL, metres: number) => haversineKm(rider, target) * 1000 <= metres;

/** §9.7 message text sent to the receiver. */
export const codeMessage = (code: string) =>
  `Your SeloNaChipa delivery code is ${code}. Give it to the rider ONLY when you are holding your parcel/order. Never share it by phone or message.`;

/** §18 chat safety: what the rider may not send. */
export const riderChatBlocked = (text: string) => /\d{5}/.test(text.replace(/[\s-]/g, "")) || /\b(code|pin)\b/i.test(text);

/** §8: a batch may hold up to 4 parcels whose pickups are within 3 km of each other, all in one city. */
export function batchProblem(existingPickups: LL[], newPickup: LL, c: ParcelConfig): string | null {
  if (existingPickups.length >= c.maxBatch) return `You can carry up to ${c.maxBatch} deliveries at once.`;
  const city = cityOf(newPickup);
  if (existingPickups.some((p) => cityOf(p)?.id !== city?.id)) return INTERCITY_MESSAGE;
  if (existingPickups.some((p) => haversineKm(p, newPickup) > c.batchPickupRadiusKm)) return `Batched pickups must be within ${c.batchPickupRadiusKm} km of each other.`;
  return null;
}

/** Currency format K1,250.00 (§21). */
export const formatK = (ngwee: number) => `K${(ngwee / 100).toLocaleString("en-ZM", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
