/** Pure business rules shared with the Android app (selo.Nachipa01.domain). Unit tested. */

export const PIN_LENGTH = 5;
const WEAK = new Set(["00000", "11111", "22222", "33333", "44444", "55555", "66666", "77777", "88888", "99999",
  "12345", "54321", "01234", "98765", "43210"]);

export function pinProblem(pin: unknown): string | null {
  if (typeof pin !== "string" || !/^\d{5}$/.test(pin)) return `PIN must be ${PIN_LENGTH} digits.`;
  if (WEAK.has(pin)) return "That PIN is too easy to guess.";
  return null;
}

export function normaliseMsisdn(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const d = raw.replace(/\D/g, "");
  let n: string;
  if (d.startsWith("260") && d.length === 12) n = d.slice(3);
  else if (d.startsWith("0") && d.length === 10) n = d.slice(1);
  else if (d.length === 9) n = d;
  else return null;
  if (!["97", "77", "96", "76", "95", "75"].includes(n.slice(0, 2))) return null;
  return `+260${n}`;
}

export function network(e164: string): "airtel" | "mtn" | "zamtel" {
  const p = e164.replace("+260", "").slice(0, 2);
  return p === "96" || p === "76" ? "mtn" : p === "95" || p === "75" ? "zamtel" : "airtel";
}

export const normaliseAnswer = (a: string) => a.trim().toLowerCase().replace(/\s+/g, " ");

export const kwachaToNgwee = (k: number) => Math.round(k * 100);

export interface FeeConfig {
  flatPlatformFeeKwacha: number; buyerServiceFeePct: number; riderPlatformFeePct: number; socialFundPct: number;
  deliveryFeePerKm: number; deliveryBaseFee: number; insurancePremiumPct: number; insuranceMaxDeclaredValue: number;
  agentPlatformCutPct: number;
}

export function deliveryFee(distanceKm: number, c: FeeConfig) {
  return kwachaToNgwee(c.deliveryBaseFee + distanceKm * c.deliveryFeePerKm);
}

export function quote(goodsNgwee: number, distanceKm: number, c: FeeConfig, declaredValueNgwee = 0) {
  const delivery = deliveryFee(distanceKm, c);
  const service = Math.round((goodsNgwee * c.buyerServiceFeePct) / 100);
  const capped = Math.min(declaredValueNgwee, kwachaToNgwee(c.insuranceMaxDeclaredValue));
  const premium = Math.round((capped * c.insurancePremiumPct) / 100);
  return { goodsNgwee, deliveryNgwee: delivery, serviceFeeNgwee: service, insurancePremiumNgwee: premium,
    totalNgwee: goodsNgwee + delivery + service + premium };
}

/** Seller leg released at PICKED_UP, rider leg at DELIVERED (Escrow Engine spec). */
export function split(goodsNgwee: number, deliveryNgwee: number, c: FeeConfig) {
  const platform = Math.min(kwachaToNgwee(c.flatPlatformFeeKwacha), goodsNgwee);
  const riderFee = Math.round((deliveryNgwee * c.riderPlatformFeePct) / 100);
  const fund = Math.round((deliveryNgwee * c.socialFundPct) / 100);
  return { sellerNetNgwee: goodsNgwee - platform, platformFeeNgwee: platform,
    riderNetNgwee: deliveryNgwee - riderFee - fund, riderPlatformFeeNgwee: riderFee, socialFundNgwee: fund };
}

export function agentCommission(saleNgwee: number, ratePct: number, c: FeeConfig) {
  const gross = Math.round((saleNgwee * ratePct) / 100);
  return { gross, net: gross - Math.round((gross * c.agentPlatformCutPct) / 100) };
}

export type OrderState = "INITIATED" | "ESCROW_LOCKED" | "BROADCASTING" | "RIDER_ASSIGNED" | "PICKED_UP" |
  "IN_TRANSIT" | "DELIVERED" | "REFUNDED" | "CANCELLED" | "PAYOUT_PENDING_RETRY";

const TRANSITIONS: Record<OrderState, OrderState[]> = {
  INITIATED: ["ESCROW_LOCKED", "CANCELLED"],
  ESCROW_LOCKED: ["BROADCASTING", "REFUNDED"],
  BROADCASTING: ["RIDER_ASSIGNED", "REFUNDED"],
  RIDER_ASSIGNED: ["PICKED_UP", "BROADCASTING", "REFUNDED"],
  PICKED_UP: ["IN_TRANSIT"],
  IN_TRANSIT: ["DELIVERED"],
  DELIVERED: ["PAYOUT_PENDING_RETRY"],
  PAYOUT_PENDING_RETRY: ["DELIVERED"],
  REFUNDED: [],
  CANCELLED: [],
};
export const canTransition = (from: OrderState, to: OrderState) => TRANSITIONS[from]?.includes(to) ?? false;

/** Social Fund unlocks every N completed deliveries. */
export const milestoneReached = (completed: number, every: number) => every > 0 && completed > 0 && completed % every === 0;

/** Exponential retry schedule for failed payouts: 1, 5, 15, 60 min, then hourly for 24 h. */
export function nextRetryMinutes(attempt: number): number | null {
  const steps = [1, 5, 15, 60];
  if (attempt < steps.length) return steps[attempt];
  return attempt < steps.length + 23 ? 60 : null;
}

export function haversineKm(a: { lat: number; lng: number }, b: { lat: number; lng: number }) {
  const R = 6371, toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat), dLng = toRad(b.lng - a.lng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

const ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export function insuranceId(rand: () => number = Math.random) {
  const seg = () => Array.from({ length: 4 }, () => ALPHA[Math.floor(rand() * ALPHA.length)]).join("");
  return `INS-${seg()}-${seg()}`;
}
