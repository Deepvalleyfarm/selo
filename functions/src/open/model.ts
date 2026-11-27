/**
 * SeloNachipa Open Network — partner model, services and scopes.
 *
 * Two independently enabled services:
 *   commerce  — publish products, share them with approved marketplaces, receive and answer marketplace orders
 *   logistics — quote and request deliveries carried by the existing SeloNachipa rider network
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
  "orders:read": { service: "commerce", description: "Read orders for your products, and orders you placed" },
  "orders:write": { service: "commerce", description: "Accept, reject, fulfil or cancel orders; place marketplace orders" },
  "delivery:quote": { service: "logistics", description: "Delivery prices and service coverage" },
  "delivery:write": { service: "logistics", description: "Request and cancel deliveries" },
  "delivery:read": { service: "logistics", description: "Track your deliveries" },
  "coverage:read": { service: "logistics", description: "Cities, neighbourhoods and rider availability" },
  "webhooks:manage": { service: "common", description: "Configure webhook URL and event subscriptions" },
  "settlements:read": { service: "common", description: "Your settlement statement" },
};
export const ALL_SCOPES = Object.keys(SCOPES);

export const EVENTS = ["product.created", "product.updated", "product.stock_updated", "product.unpublished", "order.created", "order.accepted", "order.rejected",
  "order.cancelled", "delivery.created", "delivery.assigned", "delivery.picked_up", "delivery.in_transit", "delivery.completed", "delivery.failed", "delivery.cancelled"] as const;
export type EventType = typeof EVENTS[number];

export type Env = "sandbox" | "production";
export interface PartnerPricing {
  /** Delivery pricing override (otherwise the standard parcel tariff). */
  deliveryBaseNgwee?: number | null; deliveryPerKmNgwee?: number | null; expressSurchargePct?: number | null;
  /** Discount on the standard delivery price for wholesale partners. */
  wholesaleDiscountPct?: number | null;
  /** Commission SeloNachipa keeps on this partner's products sold on the marketplace. */
  marketplaceCommissionPct?: number | null;
  subscriptionMonthlyNgwee?: number | null; apiCallNgwee?: number | null;
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

/** Delivery price for a partner: the standard parcel tariff, or the partner's own rates, then express and wholesale adjustments. */
export function partnerDeliveryPrice(standardNgwee: number, distanceKm: number, pr: PartnerPricing = {}, express = false): number {
  let p = pr.deliveryBaseNgwee != null || pr.deliveryPerKmNgwee != null
    ? Math.round((pr.deliveryBaseNgwee ?? 0) + (pr.deliveryPerKmNgwee ?? 0) * Math.max(0, distanceKm)) : standardNgwee;
  if (pr.wholesaleDiscountPct) p = Math.round(p * (1 - Math.min(90, pr.wholesaleDiscountPct) / 100));
  if (express) p = Math.round(p * (1 + Math.max(0, pr.expressSurchargePct ?? 25) / 100));
  return p;
}

/** Marketplace commission on a partner sale (default 8 %). */
export const commissionOn = (saleNgwee: number, pr: PartnerPricing = {}) => Math.round((saleNgwee * Math.max(0, pr.marketplaceCommissionPct ?? 8)) / 100);

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
