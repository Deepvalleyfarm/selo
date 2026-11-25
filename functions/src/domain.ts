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

/** Operator from the number prefix: 097/077 Airtel Money, 096/076 MTN Money, 095/075 Zamtel Money. */
export function operatorLabel(e164: string) {
  return { airtel: "Airtel Money", mtn: "MTN Money", zamtel: "Zamtel Money" }[network(e164)];
}
export const maskMsisdn = (e164: string) => { const d = e164.replace("+260", "0"); return `${d.slice(0, 4)} ••• ${d.slice(-3)}`; };

/** Buyer-facing reason for a failed collection, from the gateway status/message. */
export function collectionFailureReason(status: string, message: string | undefined, msisdn: string) {
  const t = `${status} ${message ?? ""}`.toLowerCase();
  const on = `${operatorLabel(msisdn)} ${maskMsisdn(msisdn)}`;
  if (/insufficient|balance|funds/.test(t)) return `Insufficient funds on ${on}.`;
  if (/timeout|expired|timed out/.test(t)) return `The prompt on ${on} timed out.`;
  if (/cancel|reject|declin/.test(t)) return `The payment was declined on ${on}.`;
  if (/pin/.test(t)) return `Wrong PIN entered on ${on}.`;
  return `The payment on ${on} didn't go through.`;
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
  agentPlatformCutPct: number; paymentProcessingPct?: number;
}

export function deliveryFee(distanceKm: number, c: FeeConfig) {
  return kwachaToNgwee(c.deliveryBaseFee + distanceKm * c.deliveryFeePerKm);
}

export function quote(goodsNgwee: number, distanceKm: number, c: FeeConfig, declaredValueNgwee = 0) {
  const delivery = deliveryFee(distanceKm, c);
  const service = Math.round((goodsNgwee * c.buyerServiceFeePct) / 100);
  const capped = Math.min(declaredValueNgwee, kwachaToNgwee(c.insuranceMaxDeclaredValue));
  const premium = Math.round((capped * c.insurancePremiumPct) / 100);
  // transaction (payment processing) fee on everything else the buyer pays
  const processing = Math.round(((goodsNgwee + delivery + service + premium) * (c.paymentProcessingPct ?? 0)) / 100);
  return { goodsNgwee, deliveryNgwee: delivery, serviceFeeNgwee: service, insurancePremiumNgwee: premium, processingFeeNgwee: processing,
    totalNgwee: goodsNgwee + delivery + service + premium + processing };
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

/* ------------------------------------------------------------------ v6.3 riders */

export const RISING_AT = 50, HERO_AT = 300;
export function riderTier(completed: number, ambassador = false) {
  return ambassador && completed >= HERO_AT ? "Ambassador" : completed >= HERO_AT ? "Hero" : completed >= RISING_AT ? "Rising" : "Starter";
}
export const isHero = (tier?: string) => tier === "Hero" || tier === "Ambassador";
/** Platform-funded bonus on the rider's net delivery earnings for Hero and Ambassador tiers. */
export const heroBonus = (riderNetNgwee: number, tier: string | undefined, pct: number) => (isHero(tier) ? Math.round((riderNetNgwee * pct) / 100) : 0);

export interface WorkingDay { day: number; on: boolean; start: string; end: string }
/** day 0 = Monday. True when [atMs] (Lusaka time, UTC+2) falls inside the rider's working hours. No hours set = always. */
export function withinWorkingHours(hours: WorkingDay[] | undefined | null, atMs = Date.now()): boolean {
  if (!hours?.length) return true;
  const d = new Date(atMs + 2 * 3_600_000);
  const day = (d.getUTCDay() + 6) % 7, mins = d.getUTCHours() * 60 + d.getUTCMinutes();
  const h = hours.find((x) => x.day === day);
  if (!h || !h.on) return false;
  const toMin = (s: string) => { const [a, b] = s.split(":").map(Number); return a * 60 + (b || 0); };
  const s = toMin(h.start), e = toMin(h.end);
  return e > s ? mins >= s && mins < e : mins >= s || mins < e; // overnight shifts
}

/** Acceptance rate from offers and accepts (100% until there is history). */
export const acceptanceRate = (accepted: number, offered: number) => (offered > 0 ? Math.round((accepted * 100) / offered) : 100);

export const CLAIM_CATEGORIES = {
  MEDICAL: { label: "Medical emergency", heroOnly: false, photoRequired: false },
  BIKE: { label: "Bike repair or accident", heroOnly: false, photoRequired: true },
  FUNERAL: { label: "Funeral support", heroOnly: false, photoRequired: false },
  SCHOOL: { label: "School fees for children", heroOnly: true, photoRequired: false },
} as const;
export function claimProblem(cat: string, amountNgwee: number, fundNgwee: number, tier: string | undefined, hasPhoto: boolean): string | null {
  const c = CLAIM_CATEGORIES[cat as keyof typeof CLAIM_CATEGORIES];
  if (!c) return "Choose what the claim is for.";
  if (c.heroOnly && !isHero(tier)) return "School fees claims unlock at Hero tier (300 deliveries).";
  if (c.photoRequired && !hasPhoto) return "Add a photo of the damage for a bike repair claim.";
  if (!Number.isFinite(amountNgwee) || amountNgwee <= 0) return "Enter the amount you need.";
  if (amountNgwee > fundNgwee) return `Claims can be up to your fund balance of K${(fundNgwee / 100).toFixed(2)}.`;
  return null;
}
