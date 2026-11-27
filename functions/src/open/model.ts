/**
 * SeloNachipa Open Network — partner model, services and scopes.
 *
 * Two independently enabled services:
 *   commerce  — list products on the SeloNachipa marketplace (discovery) and share them with approved platforms;
 *               buyers are sent to the originating platform's own checkout
 *   logistics — quote and request deliveries carried by the existing SeloNachipa rider network, at the delivery fee
 *               set on SeloNachipa (which SeloNachipa keeps and splits as configured)
 * A partner gets a scope only if the service that owns it is enabled AND an admin granted it.
 */
import { defineSecret } from "firebase-functions/params";

export const OPEN_API_JWT_SECRET = defineSecret("OPEN_API_JWT_SECRET");
export const OPEN_API_ENC_KEY = defineSecret("OPEN_API_ENC_KEY");
export const OPEN_SECRETS = [OPEN_API_JWT_SECRET, OPEN_API_ENC_KEY];
/** Read a secret, falling back to env for tests/emulators. */
export const secretValue = (p: { value(): string }, env: string) => { try { return p.value() || process.env[env] || ""; } catch { return process.env[env] ?? ""; } };

export type Service = "commerce" | "logistics";
export const SCOPES: Record<string, { service: Service | "common"; description: string }> = {
  "products:write": { service: "commerce", description: "Publish, update, unpublish and archive your own products" },
  "products:read": { service: "commerce", description: "Read your own published products" },
  "inventory:write": { service: "commerce", description: "Update stock for your own products" },
  "catalog:read": { service: "commerce", description: "Discover products other platforms explicitly shared with you" },
  "delivery:quote": { service: "logistics", description: "Delivery prices and service coverage" },
  "delivery:write": { service: "logistics", description: "Request and cancel deliveries" },
  "delivery:read": { service: "logistics", description: "Track your deliveries" },
  "coverage:read": { service: "logistics", description: "Cities, neighbourhoods and rider availability" },
  "media:write": { service: "commerce", description: "Generate AI videos and ads for your products" },
  "media:read": { service: "commerce", description: "Read your generated videos and ads" },
  "webhooks:manage": { service: "common", description: "Configure webhook URL and event subscriptions" },
  "settlements:read": { service: "common", description: "Your settlement statement" },
};
export const ALL_SCOPES = Object.keys(SCOPES);

export const EVENTS = ["product.created", "product.updated", "product.stock_updated", "product.unpublished", "delivery.created", "delivery.assigned", "delivery.picked_up", "delivery.in_transit", "delivery.completed", "delivery.failed", "delivery.cancelled", "media.video.ready", "media.video.failed"] as const;
export type EventType = typeof EVENTS[number];

export type Env = "sandbox" | "production";
/**
 * Commercial terms per platform. Delivery is always priced and split by SeloNachipa's own configuration (no partner
 * delivery tariffs): the platform charges its buyer the quoted SeloNachipa fee and owes it in full to SeloNachipa.
 */
export interface PartnerPricing {
  subscriptionMonthlyNgwee?: number | null; apiCallNgwee?: number | null;
  /** Per finished production AI video / per generated ad (0 or unset = included). */ mediaVideoNgwee?: number | null; mediaAdNgwee?: number | null;
  settlementCycle?: "weekly" | "monthly";
}
export interface Partner {
  id: string; name: string; slug: string; status: "PENDING" | "ACTIVE" | "SUSPENDED" | "REJECTED";
  services: { commerce: boolean; logistics: boolean }; scopes: string[];
  environments: { sandbox: boolean; production: boolean };
  rateLimitPerMin: number; pricing: PartnerPricing;
  webhook?: { url: string | null; events: string[]; secretEnc?: string | null } | null;
  contact?: { name?: string; email?: string; phone?: string }; ownerUids?: string[];
}

/** Scopes a token may carry: requested ∩ granted ∩ services enabled. */
export function effectiveScopes(p: Pick<Partner, "scopes" | "services">, requested?: string[] | null): string[] {
  const granted = p.scopes.filter((s) => SCOPES[s] && (SCOPES[s].service === "common" || p.services[SCOPES[s].service as Service]));
  return requested && requested.length ? granted.filter((s) => requested.includes(s)) : granted;
}

/** Why a partner/client can't get a token right now (null = OK). */
export function accessProblem(p: Pick<Partner, "status" | "environments"> | null, client: { status: string; env: Env } | null): string | null {
  if (!p || !client) return "invalid_client";
  if (client.status !== "ACTIVE") return "client_revoked";
  if (p.status !== "ACTIVE") return p.status === "SUSPENDED" ? "partner_suspended" : "partner_not_approved";
  if (!p.environments[client.env]) return "environment_disabled";
  return null;
}

/**
 * SeloNachipa delivery fee for an API delivery: the configured parcel tariff (delivery fee + service fee), with the
 * configured express premium on the delivery part. Returns the split exactly as configured on SeloNachipa.
 */
export function seloDeliveryFee(q: { deliveryNgwee: number; sendingNgwee: number; riderEarningsNgwee: number; platformFeeNgwee: number; socialFundNgwee: number },
  express: boolean, expressPremiumPct = 25) {
  const k = express ? 1 + Math.max(0, expressPremiumPct) / 100 : 1;
  const delivery = Math.round(q.deliveryNgwee * k);
  return { deliveryNgwee: delivery, serviceFeeNgwee: q.sendingNgwee, totalNgwee: delivery + q.sendingNgwee,
    split: { riderEarningsNgwee: Math.round(q.riderEarningsNgwee * k), platformFeeNgwee: Math.round(q.platformFeeNgwee * k), socialFundNgwee: Math.round(q.socialFundNgwee * k),
      serviceFeeNgwee: q.sendingNgwee } };
}

/** Pseudo seller uid for a partner's seller: keeps listings, orders and payouts attributable without creating app users. */
export const partnerSellerUid = (partnerId: string, sellerRef: string) => `partner:${partnerId}:${sellerRef.replace(/[^\w-]/g, "").slice(0, 60)}`;
export const isPartnerUid = (uid: unknown) => typeof uid === "string" && uid.startsWith("partner:");
export const partnerOf = (uid: string) => uid.split(":")[1] ?? "";

/** Map a parcel status change to the delivery webhook events it produces. */
export function deliveryEventsFor(from: string, to: string): EventType[] {
  if (from === to) return [];
  switch (to) {
    case "RIDER_ASSIGNED": return ["delivery.assigned"];
    case "IN_TRANSIT": return from === "RIDER_ASSIGNED" ? ["delivery.picked_up", "delivery.in_transit"] : ["delivery.in_transit"];
    case "PICKED_UP": return ["delivery.picked_up"];
    case "DELIVERED": case "COMPLETED": return ["delivery.completed"];
    case "DELIVERY_FAILED": case "RETURNED": return ["delivery.failed"];
    case "CANCELLED": case "REFUNDED": return ["delivery.cancelled"];
    default: return [];
  }
}

/** Fields a partner may see about a rider: only what's needed to hand over goods. */
export function riderForPartner(r: { riderName?: string | null; riderPlate?: string | null; riderPhotoPath?: string | null } | null, active: boolean) {
  if (!r || !r.riderName || !active) return null;
  return { firstName: String(r.riderName).split(" ")[0], plate: r.riderPlate ?? null };
}
